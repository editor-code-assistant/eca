(ns eca.db.migration
  "Loading the caches at startup: the global one, each workspace's chat
   index checked against its chat files, and the whole-workspace caches
   older ECAs wrote, migrated."
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.cache :as cache]
   [eca.db.chats :as db.chats]
   [eca.db.io :as db.io]
   [eca.logger :as logger]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[DB]")

(defn ^:private chat-files-on-disk
  "The per-chat cache files inside the workspace's chats dir (excluding the
   index)."
  [workspaces]
  (let [dir (db.io/chats-dir workspaces)]
    (if (fs/exists? dir)
      (into []
            (comp (map fs/file)
                  (filter (fn [^java.io.File f]
                            (and (.isFile f)
                                 (string/ends-with? (.getName f) ".transit.json")
                                 (not= "index.transit.json" (.getName f))))))
            (fs/list-dir dir))
      [])))

(defn ^:private reconcile-index-with-chat-files
  "Self-heals index `entries` against the actual per-chat files: files missing
   from the index are read and their meta added (index lost, or a crash landed
   between a chat write and its index write); entries whose file vanished are
   dropped (deleted by a peer process). Returns [entries dropped-ids changed?];
   dropped ids must be tombstoned by the caller so the index merge-on-write
   cannot resurrect them from the on-disk index."
  [entries workspaces metrics]
  (let [files (chat-files-on-disk workspaces)
        file-names (into #{} (map (fn [^java.io.File f] (.getName f))) files)
        indexed-file-names (into #{} (map db.io/chat-file-name) (keys entries))
        [entries dropped-ids] (reduce-kv (fn [[entries dropped-ids] id _]
                                           (if (contains? file-names (db.io/chat-file-name id))
                                             [entries dropped-ids]
                                             [(dissoc entries id) (conj dropped-ids id)]))
                                         [entries #{}]
                                         entries)
        [entries added?] (reduce (fn [[entries added?] ^java.io.File f]
                                   (if (contains? indexed-file-names (.getName f))
                                     [entries added?]
                                     (if-let [chat (:chat (db.io/read-cache f db.io/chats-version metrics))]
                                       (if-let [id (:id chat)]
                                         [(assoc entries id (db.chats/chat-list-meta chat)) true]
                                         [entries added?])
                                       [entries added?])))
                                 [entries false]
                                 files)]
    [entries dropped-ids (boolean (or added? (seq dropped-ids)))]))

(defn read-legacy-workspace-cache
  "Reads a pre-v7 whole-workspace chat blob, returning its stamped :chats map
   or nil. Entries with non-string ids are dropped (old bugs left e.g. a
   nil-keyed chat in long-lived caches); they have no addressable identity and
   would poison the migration. The blob is kept as `.bak`, so nothing is lost."
  [^java.io.File f metrics]
  (when-let [chats (:chats (db.io/read-cache f db.io/version metrics))]
    (let [invalid-ids (remove string? (keys chats))]
      (when (seq invalid-ids)
        (logger/warn logger-tag (str "Dropping " (count invalid-ids) " chat(s) with invalid ids from legacy cache " f)))
      (db.chats/stamp-chat-ids (into {} (filter (comp string? key)) chats)))))

(defn migrate-legacy-workspace-caches!
  "Migrates pre-v7 whole-workspace `db.transit.json` blobs into the per-chat
   layout (#557). Reads the canonical blob plus blobs living in redundant
   cache dirs for the same workspace set (legacy hash-only names, different
   folder-order prefixes, pre-worktree-canonicalization dirs - #558), merges
   them (newest chat wins), splits the result into per-chat files - skipping
   ids whose per-chat file is already newer, so bouncing between ECA versions
   converges without losing the newest copy - refreshes the index, renames the
   canonical blob to `db.transit.json.bak` (rollback insurance) and removes
   the redundant dirs. Best-effort and idempotent; runs under the chats index
   file lock so it cannot race writes from another live server."
  [workspaces metrics]
  (try
    (let [canonical (db.io/legacy-workspace-db-file workspaces)
          redundant (cache/redundant-workspace-cache-files workspaces "db.transit.json" shared/uri->filename)
          legacy-files (filterv fs/exists? (cons canonical redundant))]
      (when (seq legacy-files)
        (let [index-file (db.io/chats-index-file workspaces)]
          (db.io/with-os-file-lock-fn
            (db.io/workspace-cache-lock-file index-file)
            (fn []
              (let [legacy-chats (db.io/merge-chats (keep #(read-legacy-workspace-cache % metrics) legacy-files))]
                (logger/info logger-tag (str "Migrating " (count legacy-chats) " chat(s) from legacy workspace cache(s) to per-chat files..."))
                (doseq [[chat-id chat] legacy-chats]
                  ;; Per-chat so one unmigratable chat cannot abort the whole
                  ;; migration (which would leave history invisible until fixed).
                  (try
                    (let [existing (db.chats/read-chat-file workspaces chat-id metrics)]
                      (when (or (nil? existing)
                                (> (db.io/chat-recency chat) (db.io/chat-recency existing)))
                        (db.chats/write-chat-file! workspaces chat-id chat metrics)))
                    (catch Throwable e
                      (logger/warn logger-tag (str "Could not migrate chat " chat-id) e))))
                ;; Refresh the index; existing entries win over migrated meta
                ;; when newer or tied, matching the per-chat file rule above
                ;; (only a strictly newer legacy chat overwrote the file).
                (let [disk-entries (:chats (db.io/read-cache index-file db.io/chats-version metrics))
                      entries (db.io/merge-chats [disk-entries
                                                  (db.chats/stamp-chat-ids (update-vals legacy-chats db.chats/chat-list-meta))])
                      workspace-paths (vec (cache/sorted-workspace-paths workspaces shared/uri->filename))]
                  (db.io/upsert-cache! {:version db.io/chats-version :workspaces workspace-paths :chats entries} index-file metrics)
                  (db.io/record-workspace-write-attrs! index-file))
                (when (fs/exists? canonical)
                  (db.io/atomic-move! canonical (io/file (str (.getPath canonical) ".bak"))))
                (doseq [^java.io.File f redundant]
                  (try
                    (fs/delete-tree (.getParentFile f))
                    (catch Throwable e
                      (logger/warn logger-tag (str "Could not remove redundant cache dir " (.getParentFile f)) e))))))))))
    (catch Throwable e
      (logger/warn logger-tag "Could not migrate legacy workspace cache" e))))

(defn load-db-from-cache! [db* config metrics]
  (when-not (:pureConfig config)
    (when-let [global-cache (db.io/read-global-cache metrics)]
      (logger/info logger-tag "Loading from global-cache caches...")
      (swap! db* shared/deep-merge global-cache))
    (let [workspaces (db.io/db-workspaces @db*)]
      (migrate-legacy-workspace-caches! workspaces metrics)
      (let [index-entries (:chats (db.io/read-cache (db.io/chats-index-file workspaces) db.io/chats-version metrics))
            [entries dropped-ids changed?] (reconcile-index-with-chat-files index-entries workspaces metrics)
            entries (update-vals (db.chats/stamp-chat-ids entries) #(assoc % :index-only? true))]
        (when (seq dropped-ids)
          ;; Tombstone entries whose chat file vanished so the index
          ;; merge-on-write (which merges the on-disk index on this session's
          ;; first write) cannot resurrect them.
          (swap! db* update :deleted-chat-ids (fnil into #{}) dropped-ids))
        (when (seq entries)
          (logger/info logger-tag (str "Loading " (count entries) " chat(s) from workspace chats index..."))
          ;; Chats load as index-only entries (meta without history, marked
          ;; :index-only?); history is hydrated on demand (chat/prompt,
          ;; chat/open, /resume, ...).
          (swap! db* update :chats #(merge entries %)))
        (when changed?
          (db.chats/update-chats-index! @db* metrics))))))
