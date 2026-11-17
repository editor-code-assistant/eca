(ns eca.db.chats
  "Chat files: each chat in a file of its own in its workspace's cache, and
   an index to list them; saving, loading, deleting and cleaning them up."
  (:require
   [babashka.fs :as fs]
   [eca.cache :as cache]
   [eca.db.io :as db.io]
   [eca.logger :as logger]
   [eca.modules.data :as modules.data]
   [eca.shared :as shared])
  (:import
   [java.util.concurrent ConcurrentHashMap]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[DB]")

(defn stamp-chat-ids
  "Ensures every chat value carries its map key as :id, so readers can rely on
   it. Heals legacy rows persisted before chats were seeded with an :id."
  [chats]
  (reduce-kv (fn [m k v] (assoc m k (assoc v :id k))) {} chats))

(def ^:private chat-meta-keys
  "Chat keys persisted in the chats index and loaded eagerly at startup.
   Everything else (notably :messages) lives only in the per-chat file and is
   loaded on demand via `hydrate-chat!`."
  [:id :title :title-custom? :status :created-at :updated-at :model :variant
   :agent :trust :subagent :parent-chat-id :user-prompt-count :kind])

(def ^:private chat-computed-meta-keys
  "Message-derived index fields, precomputed on save so chat listings never
   need to load message history."
  [:message-count :user-message-count :flags])

(defn hydrated?
  "True when the in-memory `chat` is authoritative (created or already loaded
   in this session), false for index-only entries loaded lazily at startup.
   Keyed on the explicit `:index-only?` marker rather than the presence of
   `:messages`: a live chat legitimately has no `:messages` before the first
   token arrives, and its memory state must never be overwritten from disk."
  [chat]
  (not (:index-only? chat)))

(defn chat-list-meta
  "Small projection of `chat` used for chat listings and the on-disk chats
   index. For hydrated chats the message-derived fields are computed; for
   index-only entries the stored values pass through."
  [chat]
  (if (hydrated? chat)
    (let [messages (:messages chat)]
      (assoc (select-keys chat chat-meta-keys)
             :message-count (count messages)
             :user-message-count (count (filterv #(= "user" (:role %)) messages))
             :flags (into []
                          (comp (filter #(= "flag" (:role %)))
                                (map #(get-in % [:content :text])))
                          messages)))
    (select-keys chat (into chat-meta-keys chat-computed-meta-keys))))

(defn ^:private normalize-chat-for-write [chat-id chat]
  ;; Persist every chat that lives in memory, even with empty/absent
  ;; :messages: dropping them erased chats intentionally rolled back to empty
  ;; and chats that hit a provider error before any token arrived. Cleanup of
  ;; stale chats is handled by cleanup-old-chats! instead.
  (-> (apply dissoc chat :index-only? chat-computed-meta-keys)
      (dissoc :tool-calls :last-status-payload :unsent-user-messages :remembered-approvals :reason-group
              ;; for the running session only: a compaction a module asked for
              :compaction-request :compaction-blocked-prompt-id)
      (assoc :id chat-id)))

(defn read-chat-file
  "Reads a chat's own cache file, returning the chat map or nil."
  [workspaces chat-id metrics]
  (:chat (db.io/read-cache (db.io/chat-file workspaces chat-id) db.io/chats-version metrics)))

(defn write-chat-file!
  "Writes `chat` to its cache file, unless the on-disk copy is strictly newer:
   when the file changed on disk since this process last wrote it (peer
   process sharing the cache dir, #558), the disk copy is read and kept if its
   recency wins. Chat-level recency with ties keeping this process's copy
   mirrors the old whole-blob merge semantics; the check is attrs-based so the
   hot save path does no extra reads."
  [workspaces chat-id chat metrics]
  (let [dest (db.io/chat-file workspaces chat-id)
        chat (normalize-chat-for-write chat-id chat)
        disk-chat (when (db.io/workspace-cache-changed-on-disk? dest)
                    (:chat (db.io/read-cache dest db.io/chats-version metrics)))]
    (when-not (and disk-chat (> (db.io/chat-recency disk-chat) (db.io/chat-recency chat)))
      (db.io/upsert-cache! {:version db.io/chats-version :chat chat} dest metrics)
      (db.io/record-workspace-write-attrs! dest))))

(defn db->index-entries [db]
  (stamp-chat-ids (update-vals (:chats db) chat-list-meta)))

(defn ^:private write-chats-index!
  "Persists `entries` ({chat-id meta}) to the workspace chats index, stamping
   the workspace root paths (`workspace-paths`) so offline readers can tell
   which workspace the chats belong to (the cache dir hash is one-way).

   Safe across processes sharing one cache dir (#558): when the index changed
   on disk since this process last wrote it, on-disk entries are merged in
   (newest recency wins per chat, ties keep this process's copy). Ids in
   `deleted-ids` are never resurrected by the merge. Runs under a
   cross-process file lock; if locking fails, falls back to a plain overwrite."
  [entries deleted-ids workspace-paths dest metrics]
  (let [write! (fn [entries]
                 (db.io/upsert-cache! {:version db.io/chats-version :workspaces workspace-paths :chats entries} dest metrics)
                 (db.io/record-workspace-write-attrs! dest))]
    (try
      (db.io/with-os-file-lock-fn
        (db.io/workspace-cache-lock-file dest)
        (fn []
          (let [disk-entries (when (db.io/workspace-cache-changed-on-disk? dest)
                               (:chats (db.io/read-cache dest db.io/chats-version metrics)))
                entries (cond-> entries
                          (seq disk-entries) (as-> $ (db.io/merge-chats [$ disk-entries]))
                          (seq deleted-ids) (as-> $ (apply dissoc $ deleted-ids)))]
            (write! entries))))
      (catch Throwable e
        (logger/warn logger-tag (str "Chats index lock failed, writing without merge: " (ex-message e)))
        (write! entries)))))

(defonce ^:private ^ConcurrentHashMap last-index-state
  ;; abs path of index file -> [entries deleted-ids] as of this process's last
  ;; write, so saves that did not change any chat meta skip the index write.
  (ConcurrentHashMap.))

(defn update-chats-index!
  "Derives the chats index from the in-memory `db` and persists it. Skips the
   write when nothing changed since this process's last index write."
  [db metrics]
  (let [workspaces (db.io/db-workspaces db)
        dest (db.io/chats-index-file workspaces)
        k (.getAbsolutePath dest)
        entries (db->index-entries db)
        deleted-ids (not-empty (:deleted-chat-ids db))
        workspace-paths (vec (cache/sorted-workspace-paths workspaces shared/uri->filename))
        state [entries deleted-ids]]
    ;; Skip only when nothing changed AND the file is still there, so a
    ;; deleted/lost index is always recreated.
    (when-not (and (= state (.get last-index-state k))
                   (fs/exists? dest))
      (write-chats-index! entries deleted-ids workspace-paths dest metrics)
      (.put last-index-state k state))))

(defn save-chat!
  "Persists one chat to its own cache file and refreshes the chats index: the
   cost of saving a history mutation is O(this chat) plus a metadata-only
   index refresh, not O(all workspace history) (#557). Hydration-safe: when
   the in-memory copy is index-only, its metadata is merged over the on-disk
   chat so messages are never clobbered (e.g. renaming a chat that was never
   opened this session); if that on-disk chat exists but cannot be read, the
   file write is skipped instead of replacing history with a message-less
   chat."
  [db chat-id metrics]
  (when-let [chat (get-in db [:chats chat-id])]
    (let [workspaces (db.io/db-workspaces db)]
      (if (hydrated? chat)
        (write-chat-file! workspaces chat-id chat metrics)
        (let [disk-chat (read-chat-file workspaces chat-id metrics)]
          (if (and (nil? disk-chat) (fs/exists? (db.io/chat-file workspaces chat-id)))
            (logger/warn logger-tag (str "Skipping cache save of chat " chat-id ": existing chat file is unreadable"))
            (write-chat-file! workspaces chat-id
                              (merge disk-chat
                                     (apply dissoc chat :index-only? chat-computed-meta-keys))
                              metrics))))
      (update-chats-index! db metrics))))

(defn save-all-chats!
  "Persists every hydrated in-memory chat plus the chats index. Used as a
   final flush at lifecycle boundaries (shutdown); index-only entries are
   already on disk and are left untouched."
  [db metrics]
  (let [workspaces (db.io/db-workspaces db)]
    (doseq [[chat-id chat] (:chats db)
            :when (hydrated? chat)]
      (write-chat-file! workspaces chat-id chat metrics))
    (update-chats-index! db metrics)))

(defn ^:private delete-chat-file! [workspaces chat-id]
  (let [f (db.io/chat-file workspaces chat-id)]
    (try
      (when (fs/exists? f)
        (fs/delete f))
      (catch Throwable e
        (logger/warn logger-tag (str "Could not delete chat cache file " f) e)))))

(defn delete-chat-from-cache!
  "Deletes a chat's cache file and drops it from the chats index. The caller
   must have removed the chat from (:chats db) and tombstoned the id in
   :deleted-chat-ids so peer-process index merges cannot resurrect it."
  [db chat-id metrics]
  (delete-chat-file! (db.io/db-workspaces db) chat-id)
  (update-chats-index! db metrics))

(defn hydrate-chat!
  "Ensures `chat-id`'s message history is loaded into memory, reading its
   cache file when the in-memory copy is index-only. When the on-disk copy is
   strictly newer (a peer process advanced the chat), it wins entirely;
   otherwise in-memory metadata stays on top of the on-disk history. No-op for
   hydrated chats and ids without a cache file."
  [db* chat-id metrics]
  (let [db @db*
        chat (get-in db [:chats chat-id])]
    (when (and chat (not (hydrated? chat)))
      (let [disk-chat (read-chat-file (db.io/db-workspaces db) chat-id metrics)]
        (swap! db* update-in [:chats chat-id]
               (fn [mem-chat]
                 (cond
                   (or (nil? mem-chat) (hydrated? mem-chat))
                   ;; raced with a concurrent hydration/mutation; keep it
                   mem-chat

                   ;; file vanished: memory becomes authoritative
                   (nil? disk-chat)
                   (dissoc mem-chat :index-only?)

                   :else
                   (let [mem-meta (apply dissoc mem-chat :index-only? chat-computed-meta-keys)]
                     (if (> (db.io/chat-recency disk-chat) (db.io/chat-recency mem-meta))
                       (merge mem-meta disk-chat)
                       (merge disk-chat mem-meta))))))))))

(defn cleanup-old-chats!
  "Deletes chats not updated for retention-days (falling back to `:created-at`
   when `:updated-at` is missing): removes them from memory and their
   per-chat cache files, then refreshes the chats index once. When
   retention-days is non-positive, cleanup is disabled."
  [db* metrics retention-days]
  (when (pos? retention-days)
    (let [retention-ms (* retention-days 24 60 60 1000)
          cutoff (- (System/currentTimeMillis) retention-ms)
          removed-ids* (atom #{})]
      (swap! db* update :chats
             (fn [chats]
               (into {}
                     (filter (fn [[id chat]]
                               (let [recency (or (:updated-at chat) (:created-at chat))]
                                 (if (and recency (< recency cutoff))
                                   (do (swap! removed-ids* conj id) false)
                                   true))))
                     chats)))
      (when-let [removed-ids (not-empty @removed-ids*)]
        ;; Tombstone the ids so the index merge-on-write does not resurrect
        ;; them from an index file shared with another live server.
        (swap! db* update :deleted-chat-ids (fnil into #{}) removed-ids)
        (logger/info logger-tag (str "Cleaned up " (count removed-ids) " chat(s) not updated for " retention-days " days"))
        (let [db @db*
              workspaces (db.io/db-workspaces db)]
          (doseq [chat-id removed-ids]
            (delete-chat-file! workspaces chat-id)
            ;; and the modules' files for it (eca.api/data-dir)
            (try
              (modules.data/delete-chat-dirs! db chat-id)
              (catch Exception e
                (logger/warn logger-tag "Could not delete the modules' files of chat" chat-id (ex-message e)))))
          (update-chats-index! db metrics))))))
