(ns eca.db
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [cognitect.transit :as transit]
   [eca.cache :as cache]
   [eca.digest :as digest]
   [eca.logger :as logger]
   [eca.metrics :as metrics]
   [eca.shared :as shared])
  (:import
   [java.io OutputStream RandomAccessFile]
   [java.nio.channels FileChannel FileLock]
   [java.nio.file AtomicMoveNotSupportedException CopyOption Files LinkOption StandardCopyOption]
   [java.nio.file.attribute BasicFileAttributes FileAttribute]
   [java.util.concurrent ConcurrentHashMap]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[DB]")

(def version
  "Schema version of the global cache (`db.transit.json` in the data dir, plus
   its legacy cache dir copy) and of the legacy whole-workspace chat blobs read
   only for migration. Kept at 6 on purpose: the global (auth) schema did not
   change with the per-chat layout, so rolling back to an older ECA does not
   log users out."
  6)

(def chats-version
  "Schema version of the per-chat layout files (`chats/index.transit.json` and
   `chats/<chat-id>.transit.json`). Successor of the version 6 whole-workspace
   blob (#557); older ECA versions never read these files."
  7)

(def ^:private _db-spec
  "Used for documentation only"
  {:client-info {:name :string
                 :version :string}
   :workspace-folders [{:name :string :uri :string}]
   :client-capabilities {:code-assistant {:editor {:diagnostics :boolean
                                                   :definition :boolean
                                                   :references :boolean}
                                          :chat-capabilities {:ask-question :boolean}}}
   :config-hash :string
   :providers-config-hash :string
   :last-config-notified ::any-map
   :stopping :boolean
   ;; chat ids deleted in this session; excluded from workspace cache writes so
   ;; the merge-on-write never resurrects them from a shared cache file.
   :deleted-chat-ids #{:string}
   :models {"<model-name>" {:web-search :boolean
                            :tools :boolean
                            :reason? :boolean
                            :image-input? :boolean
                            :max-output-tokens :number
                            :model-name :string ;; real model name used for requests
                            :limit {:context :number :output :number}
                            :input-token-cost (or :number nil)
                            :output-token-cost (or :number nil)
                            :input-cache-read-token-cost (or :number nil)
                            :input-cache-creation-token-cost (or :number nil)}}
   :mcp-clients {"<client-id>" {:client :McpSyncClient
                                :status (or :requires-auth :starting :running :failed :stopping :stopped)
                                :version :string
                                :tools [{:name :string
                                         :description :string
                                         :parameters ::any-map}]
                                :prompts [{:name :string
                                           :description :string
                                           :arguments [{:name :string
                                                        :description :string
                                                        :required :boolean}]}]
                                :resources [{:uri :string
                                             :name :string
                                             :description :string
                                             :mime-type :string}]}}
   ;; In-memory chats: entries loaded from the chats index start "index-only"
   ;; (marked :index-only?, no :messages, plus precomputed :message-count/
   ;; :user-message-count/:flags) until hydrate-chat! loads their per-chat
   ;; cache file. Live chats never carry the marker: their memory state is
   ;; authoritative and never overwritten from disk.
   :chats {"<chat-id>" {:id :string
                        :title (or :string nil)
                        :title-custom? :boolean ;; user manually renamed the chat
                        :status (or :idle :running :stopping :login)
                        :created-at :number
                        :updated-at :number
                        :login-provider :string
                        :model :string ;; last full model id used for this chat, e.g. "anthropic/claude-sonnet-4-6"
                        :last-api :keyword
                        :trust :boolean
                        :kind (or :inline nil) ;; nil for regular chats
                        :prompt-id :uuid
                        :user-prompt-count :number
                        :subagent :boolean
                        :parent-chat-id :string
                        :startup-context :string
                        :prompt-cache ::any-map
                        :messages [{:role (or "user" "assistant" "tool_call" "tool_call_output" "reason" "compact_marker" "flag" "server_tool_use" "server_tool_result")
                                    :content (or :string [::any-map]) ;; string for simple text, map/vector for structured content
                                    :content-id :string
                                    :created-at :number}]
                        :task {:next-id :number
                               :active-summary (or :string nil)
                               :tasks [{:id :number
                                        :subject :string
                                        :description :string
                                        :status (or :pending :in-progress :done)
                                        :priority (or :high :medium :low)
                                        :blocked-by #{:number}}]}
                        :tool-calls {"<tool-call-id>"
                                     {:status (or :initial :preparing :check-approval :waiting-approval
                                                  :execution-approved :executing :rejected :cleanup
                                                  :completed :stopping)

                                      :name :string
                                      :full-name :string
                                      :server :string
                                      :origin (or :native :mcp)
                                      :arguments ::any-map
                                      :decision-reason {:code :keyword :text :string}
                                      :approved?* :promise
                                      :future-cleanup-complete?* :promise
                                      :start-time :long
                                      :future :future
                                      :resources ::any-map
                                      :rollback-changes [{:path :string
                                                          :content (or :string nil)}]}}}}
   :auth {"<provider-name>" {:step (or :login/start :login/waiting-login-method
                                       :login/waiting-provider-code :login/waiting-api-key
                                       :login/waiting-user-confirmation :login/done :login/renew-token)
                             :type (or :auth/token :auth/oauth nil)
                             :mode (or :manual :console :max nil)
                             :api-key :string
                             :access-token :string
                             :refresh-token :string
                             :expires-at :long
                             :verifier :string
                             :device-code :string}}
   :mcp-auth {"<mcp-server-name>" {:type :auth/oauth
                                   :access-token :string
                                   :refresh-token :string
                                   :expires-at :long}}})

(defn parent-chat-id [db chat-id]
  (get-in db [:chats chat-id :parent-chat-id]))

(defn resolve-trust
  "Resolve the effective trust for `chat-id`: the chat's own `:trust` when set,
   otherwise the nearest ancestor's `:trust` by walking up `:parent-chat-id`.
   Returns nil when no chat in the chain has trust set. This lets trust toggled
   on a parent chat reach already-running subagents (#504)."
  [db chat-id]
  (loop [id chat-id
         seen #{}]
    (when (and id (not (contains? seen id)))
      (let [chat (get-in db [:chats id])]
        (if (some? (:trust chat))
          (:trust chat)
          (recur (:parent-chat-id chat) (conj seen id)))))))

(defonce initial-db
  {:client-info {}
   :workspace-folders []
   :client-capabilities {}
   :config-hash nil
   :providers-config-hash nil
   :last-config-notified {}
   :stopping false
   :models {}
   :mcp-clients {}
   ;; Approved tool calls remembered for this session (not cached):
   ;; {tool-name {:remember-to-approve? boolean
   ;;             :remembered-command-keys #{string}}}
   :tool-calls {}
   ;; Chat ids deleted in this session (not cached), see _db-spec.
   :deleted-chat-ids #{}

   ;; cacheable; bump `chats-version` when changing :chats shape, `version`
   ;; when changing :auth/:mcp-auth shape
   :chats {}
   :auth {"anthropic" {}
          "azure" {}
          "deepseek" {}
          "github-copilot" {}
          "google" {}
          "litellm" {}
          "lmstudio" {}
          "mistral" {}
          "moonshot" {}
          "openai" {}
          "openrouter" {}
          "z-ai" {}}
   :mcp-auth {}})

(defonce db* (atom initial-db))

(defn ^:private no-flush-output-stream [^OutputStream os]
  (proxy [java.io.BufferedOutputStream] [os]
    (flush [])
    (close []
      (let [^java.io.BufferedOutputStream this this]
        (proxy-super flush)
        (proxy-super close)))))

(defn ^:private transit-global-db-file ^java.io.File []
  (io/file (cache/data-dir) "db.transit.json"))

(defn ^:private legacy-global-db-file
  "The global cache's copy in `cache/legacy-data-dir`, or nil."
  ^java.io.File []
  (some-> (cache/legacy-data-dir) (io/file "db.transit.json")))

(defn ^:private legacy-workspace-db-file
  "Pre-v7 whole-workspace chat blob (every chat in one file). Only read to
   migrate it into the per-chat layout; never written anymore (#557)."
  ^java.io.File [workspaces]
  (cache/workspace-cache-file workspaces "db.transit.json" shared/uri->filename))

(defn ^:private db-workspaces
  "Workspace set keying this session's cache dir. Prefers the folders the
   session initialized with so mid-session workspace folder changes do not
   retarget the cache dir."
  [db]
  (or (:initial-workspace-folders db)
      (:workspace-folders db)))

(defn ^:private chats-dir
  ^java.io.File [workspaces]
  (io/file (cache/workspace-cache-dir workspaces shared/uri->filename) "chats"))

(defn ^:private chats-index-file
  ^java.io.File [workspaces]
  (io/file (chats-dir workspaces) "index.transit.json"))

(defn ^:private legacy-chats-dir
  "The workspace's chats dir under `cache/legacy-data-dir`, or nil. Only read
   (and chat files deleted), never written: it is where an older ECA keeps
   writing after a downgrade."
  ^java.io.File [workspaces]
  (some-> (cache/legacy-workspace-cache-dir workspaces shared/uri->filename) (io/file "chats")))

(defn ^:private legacy-chats-index-file
  ^java.io.File [workspaces]
  (some-> (legacy-chats-dir workspaces) (io/file "index.transit.json")))

(defn chat-file-name
  "Filesystem-safe file name for `chat-id`. Server-generated ids (lowercase
   UUIDs) are used as-is; anything else falls back to a digest: client-supplied
   ids may contain path separators or filesystem-hostile characters, uppercase
   would collide on case-insensitive filesystems, and ids prefixed `index`
   could collide with the index file name. Public so `read-chat` can locate a
   chat's file inside a cache dir."
  ^String [chat-id]
  (let [chat-id (str chat-id)]
    (str (if (and (re-matches #"[a-z0-9][a-z0-9_-]{7,127}" chat-id)
                  (not (string/starts-with? chat-id "index")))
           chat-id
           (digest/sha-256-hex chat-id))
         ".transit.json")))

(defn ^:private chat-file
  ^java.io.File [workspaces chat-id]
  (io/file (chats-dir workspaces) (chat-file-name chat-id)))

(defn ^:private legacy-chat-file
  ^java.io.File [workspaces chat-id]
  (some-> (legacy-chats-dir workspaces) (io/file (chat-file-name chat-id))))

(defn read-transit-file
  "Read and return the transit+json data from a cache file.
   Returns nil when the file does not exist. Throws on I/O or parse errors."
  [^java.io.File cache-file]
  (when (fs/exists? cache-file)
    (with-open [is (io/input-stream cache-file)]
      (transit/read (transit/reader is :json)))))

(defn ^:private read-cache [cache-file expected-version metrics]
  (try
    (metrics/task metrics :db/read-cache
      (if-let [cache (read-transit-file cache-file)]
        (when (= expected-version (:version cache))
          cache)
        (logger/info logger-tag (str "No existing DB cache found for " cache-file))))
    (catch Throwable e
      (logger/error logger-tag (str "Could not load cache from " cache-file) e))))

(defn ^:private atomic-move!
  "Rename `src` to `dest`. Tries an atomic move first so a crash mid-rename
   cannot leave the destination half-written; falls back to a non-atomic
   replace on filesystems that do not support ATOMIC_MOVE."
  [^java.io.File src ^java.io.File dest]
  (try
    (Files/move (.toPath src)
                (.toPath dest)
                (into-array CopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))
    (catch AtomicMoveNotSupportedException _
      (Files/move (.toPath src)
                  (.toPath dest)
                  (into-array CopyOption
                              [StandardCopyOption/REPLACE_EXISTING])))))

(defonce ^:private ^ConcurrentHashMap file-locks (ConcurrentHashMap.))

(defn ^:private file-lock
  "Return a process-wide JVM monitor keyed by the absolute path of `f`.
   Concurrent writers targeting the same cache file synchronize on this
   monitor so they cannot race on the temp-file rename."
  ^Object [^java.io.File f]
  (let [^ConcurrentHashMap m file-locks
        k (.getAbsolutePath f)]
    (or (.get m k)
        (let [o (Object.)]
          (or (.putIfAbsent m k o) o)))))

(defn ^:private upsert-cache!
  "Persist `cache` to `cache-file` durably.

   The payload is written to a unique sibling temp file first and then
   renamed atomically over the destination. If the JVM/process dies
   mid-write the original file stays intact, so we never trade a corrupted
   cache for a more frequent save cadence. Concurrent writers in the same
   process targeting the same `cache-file` are serialized on `file-lock`
   so they cannot race on the rename."
  [cache cache-file metrics]
  (try
    (metrics/task metrics :db/upsert-cache
      (io/make-parents cache-file)
      (let [dest ^java.io.File cache-file]
        ;; `file-lock` interns the lock object in `file-locks`, so it is
        ;; not actually local to this scope; suppress the false positive.
        #_{:clj-kondo/ignore [:locking-suspicious-lock]}
        (locking (file-lock dest)
          ;; Best-effort cleanup of the legacy fixed-name `<dest>.tmp` left by
          ;; pre-unique-tmp versions of ECA. Safe to delete because new code
          ;; only ever creates random-suffixed temps via Files/createTempFile.
          (let [legacy-tmp ^java.io.File (io/file (str (.getPath dest) ".tmp"))]
            (when (.exists legacy-tmp)
              (try (.delete legacy-tmp) (catch Throwable _))))
          (let [parent ^java.io.File (.getParentFile dest)
                prefix (str (.getName dest) ".")
                tmp ^java.io.File (.toFile
                                   (Files/createTempFile
                                    (.toPath parent)
                                    prefix
                                    ".tmp"
                                    (make-array FileAttribute 0)))]
            (try
              ;; https://github.com/cognitect/transit-clj/issues/43
              (with-open [os ^OutputStream (no-flush-output-stream (io/output-stream tmp))]
                (let [writer (transit/writer os :json)]
                  (transit/write writer cache)))
              (atomic-move! tmp dest)
              (finally
                (when (.exists tmp)
                  (.delete tmp))))))))
    (catch Throwable e
      (logger/error logger-tag (str "Could not upsert db cache to " cache-file) e))))

(defn ^:private read-global-cache
  "Reads the global (auth) cache from the most recently written of the data
   dir file and its legacy cache dir copy, so tokens an older ECA refreshed
   after a downgrade are adopted. Falls back to the other copy when that one
   cannot be read."
  [metrics]
  (let [files (->> [(transit-global-db-file) (legacy-global-db-file)]
                   (remove nil?)
                   (filter fs/exists?)
                   (sort-by #(.lastModified ^java.io.File %) >))]
    (if (seq files)
      (some #(read-cache % version metrics) files)
      (read-cache (transit-global-db-file) version metrics))))

(defn ^:private chat-recency [chat]
  (or (:updated-at chat) (:created-at chat) 0))

(defn ^:private merge-chats
  "Merges chat maps into one. On a duplicate chat id, keeps the entry with the
   greater recency (`:updated-at`, falling back to `:created-at`), so a newer
   chat is never clobbered by a staler copy living in another cache dir."
  [chat-maps]
  (reduce (fn [acc chats]
            (reduce-kv (fn [m id chat]
                         (if-let [existing (get m id)]
                           (if (> (chat-recency chat) (chat-recency existing))
                             (assoc m id chat)
                             m)
                           (assoc m id chat)))
                       acc
                       chats))
          {}
          chat-maps))

(defn ^:private with-os-file-lock-fn
  "Run `f` while holding both a JVM monitor for `lock-file` and an OS advisory
   exclusive lock on it. The JVM monitor avoids `OverlappingFileLockException`
   when two threads in the same ECA server race; the file lock serializes
   across `eca server` processes that share the same cache dir. Blocks until
   both are acquired."
  [^java.io.File lock-file f]
  ;; `file-lock` interns the lock object in `file-locks`, so it is
  ;; not actually local to this scope; suppress the false positive.
  #_{:clj-kondo/ignore [:locking-suspicious-lock]}
  (locking (file-lock lock-file)
    (io/make-parents lock-file)
    (let [^RandomAccessFile raf (RandomAccessFile. lock-file "rw")
          ^FileChannel channel (.getChannel raf)
          lock-ref (volatile! nil)]
      (try
        (vreset! lock-ref ^FileLock (.lock channel))
        (f)
        (finally
          (when-let [^FileLock lock @lock-ref]
            (try (.release lock)
                 (catch Throwable e
                   (logger/warn logger-tag "Could not release cache lock" e))))
          (try (.close channel) (catch Throwable _))
          (try (.close raf) (catch Throwable _)))))))

(defn ^:private workspace-cache-lock-file ^java.io.File [^java.io.File cache-file]
  (io/file (str (.getPath cache-file) ".lock")))

(defonce ^:private ^ConcurrentHashMap last-workspace-write-attrs (ConcurrentHashMap.))

(defn ^:private cache-file-attrs
  "Returns [last-modified-time size] for `f`, or nil when it does not exist."
  [^java.io.File f]
  (try
    (when (.exists f)
      (let [^BasicFileAttributes attrs (Files/readAttributes
                                        (.toPath f)
                                        BasicFileAttributes
                                        ^"[Ljava.nio.file.LinkOption;" (into-array LinkOption []))]
        [(.lastModifiedTime attrs) (.size attrs)]))
    (catch Throwable _ nil)))

(defn ^:private record-workspace-write-attrs!
  "Remembers the on-disk attributes of `f` right after this process wrote it,
   so the next write can cheaply detect whether another process wrote in
   between (see `workspace-cache-changed-on-disk?`)."
  [^java.io.File f]
  (if-let [attrs (cache-file-attrs f)]
    (.put last-workspace-write-attrs (.getAbsolutePath f) attrs)
    (.remove last-workspace-write-attrs (.getAbsolutePath f))))

(defn ^:private workspace-cache-changed-on-disk?
  "True when `f` exists with different attributes than the last write this
   process made to it - i.e. another process wrote it (or this process never
   wrote it yet), so its content must be merged instead of overwritten."
  [^java.io.File f]
  (let [attrs (cache-file-attrs f)]
    (boolean (and attrs (not= attrs (.get last-workspace-write-attrs (.getAbsolutePath f)))))))

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
      (dissoc :tool-calls :last-status-payload :unsent-user-messages)
      (assoc :id chat-id)))

(defn ^:private read-chat-file
  "Reads a chat's own cache file, returning the chat map or nil. Its legacy
   cache dir copy is read too and wins when strictly newer, e.g. a chat an
   older ECA continued after a downgrade."
  [workspaces chat-id metrics]
  (let [chat (:chat (read-cache (chat-file workspaces chat-id) chats-version metrics))
        legacy-file (legacy-chat-file workspaces chat-id)
        legacy-chat (when (and legacy-file (fs/exists? legacy-file))
                      (:chat (read-cache legacy-file chats-version metrics)))]
    (if (and legacy-chat
             (or (nil? chat) (> (chat-recency legacy-chat) (chat-recency chat))))
      legacy-chat
      chat)))

(defn ^:private chat-file-exists? [workspaces chat-id]
  (boolean (or (fs/exists? (chat-file workspaces chat-id))
               (some-> (legacy-chat-file workspaces chat-id) fs/exists?))))

(defn ^:private write-chat-file!
  "Writes `chat` to its cache file, unless the on-disk copy is strictly newer:
   when the file changed on disk since this process last wrote it (peer
   process sharing the cache dir, #558), the disk copy is read and kept if its
   recency wins. Chat-level recency with ties keeping this process's copy
   mirrors the old whole-blob merge semantics; the check is attrs-based so the
   hot save path does no extra reads."
  [workspaces chat-id chat metrics]
  (let [dest (chat-file workspaces chat-id)
        chat (normalize-chat-for-write chat-id chat)
        disk-chat (when (workspace-cache-changed-on-disk? dest)
                    (:chat (read-cache dest chats-version metrics)))]
    (when-not (and disk-chat (> (chat-recency disk-chat) (chat-recency chat)))
      (upsert-cache! {:version chats-version :chat chat} dest metrics)
      (record-workspace-write-attrs! dest))))

(defn ^:private db->index-entries [db]
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
                 (upsert-cache! {:version chats-version :workspaces workspace-paths :chats entries} dest metrics)
                 (record-workspace-write-attrs! dest))]
    (try
      (with-os-file-lock-fn
        (workspace-cache-lock-file dest)
        (fn []
          (let [disk-entries (when (workspace-cache-changed-on-disk? dest)
                               (:chats (read-cache dest chats-version metrics)))
                entries (cond-> entries
                          (seq disk-entries) (as-> $ (merge-chats [$ disk-entries]))
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
  (let [workspaces (db-workspaces db)
        dest (chats-index-file workspaces)
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
    (let [workspaces (db-workspaces db)]
      (if (hydrated? chat)
        (write-chat-file! workspaces chat-id chat metrics)
        (let [disk-chat (read-chat-file workspaces chat-id metrics)]
          (if (and (nil? disk-chat) (chat-file-exists? workspaces chat-id))
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
  (let [workspaces (db-workspaces db)]
    (doseq [[chat-id chat] (:chats db)
            :when (hydrated? chat)]
      (write-chat-file! workspaces chat-id chat metrics))
    (update-chats-index! db metrics)))

(defn ^:private delete-chat-file!
  "Deletes a chat's cache file and its legacy cache dir copy, so reading both
   locations cannot resurrect a deleted chat."
  [workspaces chat-id]
  (doseq [f (remove nil? [(chat-file workspaces chat-id)
                          (legacy-chat-file workspaces chat-id)])]
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
  (delete-chat-file! (db-workspaces db) chat-id)
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
      (let [disk-chat (read-chat-file (db-workspaces db) chat-id metrics)]
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
                     (if (> (chat-recency disk-chat) (chat-recency mem-meta))
                       (merge mem-meta disk-chat)
                       (merge disk-chat mem-meta))))))))))

(defn ^:private chat-files-in
  "The per-chat cache files inside chats `dir` (excluding the index)."
  [dir]
  (if (and dir (fs/exists? dir))
    (into []
          (comp (map fs/file)
                (filter (fn [^java.io.File f]
                          (and (.isFile f)
                               (string/ends-with? (.getName f) ".transit.json")
                               (not= "index.transit.json" (.getName f))))))
          (fs/list-dir dir))
    []))

(defn ^:private chat-files-on-disk
  "The per-chat cache files inside the workspace's chats dir (excluding the
   index), plus the legacy cache dir ones it lacks, so chats only an older ECA
   wrote are not dropped from the index."
  [workspaces]
  (let [files (chat-files-in (chats-dir workspaces))
        file-names (into #{} (map (fn [^java.io.File f] (.getName f))) files)]
    (into files
          (remove (fn [^java.io.File f] (contains? file-names (.getName f))))
          (chat-files-in (legacy-chats-dir workspaces)))))

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
        indexed-file-names (into #{} (map chat-file-name) (keys entries))
        [entries dropped-ids] (reduce-kv (fn [[entries dropped-ids] id _]
                                           (if (contains? file-names (chat-file-name id))
                                             [entries dropped-ids]
                                             [(dissoc entries id) (conj dropped-ids id)]))
                                         [entries #{}]
                                         entries)
        [entries added?] (reduce (fn [[entries added?] ^java.io.File f]
                                   (if (contains? indexed-file-names (.getName f))
                                     [entries added?]
                                     (if-let [chat (:chat (read-cache f chats-version metrics))]
                                       (if-let [id (:id chat)]
                                         [(assoc entries id (chat-list-meta chat)) true]
                                         [entries added?])
                                       [entries added?])))
                                 [entries false]
                                 files)]
    [entries dropped-ids (boolean (or added? (seq dropped-ids)))]))

(defn ^:private read-legacy-workspace-cache
  "Reads a pre-v7 whole-workspace chat blob, returning its stamped :chats map
   or nil. Entries with non-string ids are dropped (old bugs left e.g. a
   nil-keyed chat in long-lived caches); they have no addressable identity and
   would poison the migration. The blob is kept as `.bak`, so nothing is lost."
  [^java.io.File f metrics]
  (when-let [chats (:chats (read-cache f version metrics))]
    (let [invalid-ids (remove string? (keys chats))]
      (when (seq invalid-ids)
        (logger/warn logger-tag (str "Dropping " (count invalid-ids) " chat(s) with invalid ids from legacy cache " f)))
      (stamp-chat-ids (into {} (filter (comp string? key)) chats)))))

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
    (let [canonical (legacy-workspace-db-file workspaces)
          redundant (cache/redundant-workspace-cache-files workspaces "db.transit.json" shared/uri->filename)
          legacy-files (filterv fs/exists? (cons canonical redundant))]
      (when (seq legacy-files)
        (let [index-file (chats-index-file workspaces)]
          (with-os-file-lock-fn
            (workspace-cache-lock-file index-file)
            (fn []
              (let [legacy-chats (merge-chats (keep #(read-legacy-workspace-cache % metrics) legacy-files))]
                (logger/info logger-tag (str "Migrating " (count legacy-chats) " chat(s) from legacy workspace cache(s) to per-chat files..."))
                (doseq [[chat-id chat] legacy-chats]
                  ;; Per-chat so one unmigratable chat cannot abort the whole
                  ;; migration (which would leave history invisible until fixed).
                  (try
                    (let [existing (read-chat-file workspaces chat-id metrics)]
                      (when (or (nil? existing)
                                (> (chat-recency chat) (chat-recency existing)))
                        (write-chat-file! workspaces chat-id chat metrics)))
                    (catch Throwable e
                      (logger/warn logger-tag (str "Could not migrate chat " chat-id) e))))
                ;; Refresh the index; existing entries win over migrated meta
                ;; when newer or tied, matching the per-chat file rule above
                ;; (only a strictly newer legacy chat overwrote the file).
                (let [disk-entries (:chats (read-cache index-file chats-version metrics))
                      entries (merge-chats [disk-entries
                                            (stamp-chat-ids (update-vals legacy-chats chat-list-meta))])
                      workspace-paths (vec (cache/sorted-workspace-paths workspaces shared/uri->filename))]
                  (upsert-cache! {:version chats-version :workspaces workspace-paths :chats entries} index-file metrics)
                  (record-workspace-write-attrs! index-file))
                (when (fs/exists? canonical)
                  (atomic-move! canonical (io/file (str (.getPath canonical) ".bak"))))
                (doseq [^java.io.File f redundant]
                  (try
                    (fs/delete-tree (.getParentFile f))
                    (catch Throwable e
                      (logger/warn logger-tag (str "Could not remove redundant cache dir " (.getParentFile f)) e))))))))))
    (catch Throwable e
      (logger/warn logger-tag "Could not migrate legacy workspace cache" e))))

(defn ^:private global-cache-lock-file
  "Sidecar lock of the global cache. Stays in the cache dir rather than the
   data dir, so ECA versions from before and after #623 still serialize token
   refreshes on the same lock."
  []
  (io/file (cache/global-dir) "db.transit.json.lock"))

(defn with-global-cache-lock-fn
  "Run `f` while holding both a JVM-wide mutex and an OS advisory exclusive
   lock on a sidecar of the global cache file. The JVM mutex avoids
   `OverlappingFileLockException` when two threads in the same ECA server
   race a renew; the file lock serializes across `eca server` processes
   that share `~/.cache/eca/`. Blocks until both are acquired."
  [f]
  (with-os-file-lock-fn (global-cache-lock-file) f))

(defmacro with-global-cache-lock
  "See `with-global-cache-lock-fn`. Runs `body` while holding the lock."
  [& body]
  `(with-global-cache-lock-fn (fn [] ~@body)))

(def ^:private legacy-copy-marker-name
  "Marker in the data dir recording that `copy-legacy-cache-dir!` completed."
  ".legacy-cache-dir-copied")

(defn ^:private legacy-chats-holder?
  "True for a legacy cache dir entry holding a workspace's chats (per-chat
   layout or pre-v7 blob), as opposed to cache-only dirs."
  [^java.io.File dir]
  (or (fs/directory? (io/file dir "chats"))
      (fs/exists? (io/file dir "db.transit.json"))
      (fs/exists? (io/file dir "db.transit.json.bak"))))

(defn ^:private copy-atomically!
  "Copies file or dir `src` to `dest` through a hidden sibling temp path
   renamed into place, so a crash mid-copy never leaves a partial `dest`."
  [^java.io.File src ^java.io.File dest]
  (let [tmp (io/file (.getParentFile dest) (str "." (.getName dest) ".copying"))]
    (try
      (when (fs/exists? tmp)
        (fs/delete-tree tmp))
      (io/make-parents tmp)
      (if (fs/directory? src)
        (fs/copy-tree src tmp)
        (fs/copy src tmp))
      (atomic-move! tmp dest)
      (finally
        (when (fs/exists? tmp)
          (try (fs/delete-tree tmp) (catch Throwable _)))))))

(defn ^:private copy-legacy-cache-dir!
  "One-time copy of chat history and login data from where they lived before
   #623 (`cache/legacy-data-dir`) into the data dir, so wiping the cache dir
   loses nothing. Copies, never moves: an older ECA still finds its data after
   a downgrade, and what it writes there keeps being read. Only dirs holding
   chats and missing from the data dir are copied. A marker, written once
   everything copied, keeps dirs later removed from the data dir (healing,
   user cleanup) from being copied back. Best-effort: on failure chats stay
   readable from the legacy dir."
  []
  (when-let [legacy-root (cache/legacy-data-dir)]
    (let [data-root (cache/data-dir)
          marker (io/file data-root legacy-copy-marker-name)]
      (when (and (fs/directory? legacy-root) (not (fs/exists? marker)))
        (try
          (with-global-cache-lock-fn
            (fn []
              (when-not (fs/exists? marker)
                (let [copied (atom 0)
                      failed? (atom false)
                      copy! (fn [^java.io.File src ^java.io.File dest]
                              (try
                                (copy-atomically! src dest)
                                (swap! copied inc)
                                (catch Throwable e
                                  (reset! failed? true)
                                  (logger/warn logger-tag (str "Could not copy " src " to " dest) e))))
                      legacy-db (io/file legacy-root "db.transit.json")
                      data-db (transit-global-db-file)]
                  (when (and (fs/exists? legacy-db) (not (fs/exists? data-db)))
                    (copy! legacy-db data-db))
                  (doseq [^java.io.File dir (cache/workspace-dirs-in legacy-root)
                          :let [dest (io/file data-root (.getName dir))]
                          :when (and (legacy-chats-holder? dir) (not (fs/exists? dest)))]
                    (copy! dir dest))
                  (when (pos? @copied)
                    (logger/info logger-tag (str "Copied chat history and login data from " legacy-root " to " data-root
                                                 " (" @copied " item(s))")))
                  (when-not @failed?
                    (fs/create-dirs data-root)
                    (spit marker (str legacy-root "\n")))))))
          (catch Throwable e
            (logger/warn logger-tag (str "Could not copy chats from " legacy-root " to " data-root) e)))))))

(defn ^:private read-legacy-chats-index
  "Entries of the workspace's legacy cache dir chats index, or nil."
  [workspaces metrics]
  (when-let [f (legacy-chats-index-file workspaces)]
    (when (fs/exists? f)
      (:chats (read-cache f chats-version metrics)))))

(defn load-db-from-cache! [db* config metrics]
  (when-not (:pureConfig config)
    (copy-legacy-cache-dir!)
    (when-let [global-cache (read-global-cache metrics)]
      (logger/info logger-tag "Loading from global-cache caches...")
      (swap! db* shared/deep-merge global-cache))
    (let [workspaces (db-workspaces @db*)]
      (migrate-legacy-workspace-caches! workspaces metrics)
      (let [index-entries (merge-chats [(:chats (read-cache (chats-index-file workspaces) chats-version metrics))
                                        ;; chats an older ECA indexed after a downgrade
                                        (read-legacy-chats-index workspaces metrics)])
            [entries dropped-ids changed?] (reconcile-index-with-chat-files index-entries workspaces metrics)
            entries (update-vals (stamp-chat-ids entries) #(assoc % :index-only? true))]
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
          (update-chats-index! @db* metrics))))))

(defn ^:private normalize-db-for-global-write [db]
  (select-keys db [:auth :mcp-auth]))

(defn update-global-cache!
  "Persists the global (auth) cache to the data dir. While its legacy cache
   dir copy exists it is updated too: an older ECA reads only that one, so a
   downgrade would otherwise hit already rotated refresh tokens."
  [db metrics]
  (let [payload (assoc (normalize-db-for-global-write db) :version version)]
    (upsert-cache! payload (transit-global-db-file) metrics)
    (when-let [legacy-file (legacy-global-db-file)]
      (when (fs/exists? legacy-file)
        (upsert-cache! payload legacy-file metrics)))))

(defn sync-auth-from-cache!
  "Re-read the global cache from disk and, if its `:auth` entry for `provider`
   has a different `:expires-at` than the in-memory copy, overwrite the
   in-memory `[:auth provider]` with the disk version. This lets a process
   that lost a token-refresh race adopt the winner's freshly rotated tokens
   instead of POSTing with a stale refresh token.

   Returns a truthy value when in-memory state was updated."
  [db* provider metrics]
  (try
    (when-let [disk-auth (some-> (read-global-cache metrics) :auth (get provider))]
      (let [in-mem-auth (get-in @db* [:auth provider])]
        (when (and (:expires-at disk-auth)
                   (not= (:expires-at disk-auth) (:expires-at in-mem-auth)))
          (logger/info logger-tag
                       (format "Adopting %s auth tokens refreshed by peer process (expires-at %s)"
                               provider (:expires-at disk-auth)))
          (swap! db* assoc-in [:auth provider] disk-auth)
          true)))
    (catch Throwable e
      (logger/warn logger-tag "Could not sync auth from cache" e)
      false)))

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
              workspaces (db-workspaces db)]
          (doseq [chat-id removed-ids]
            (delete-chat-file! workspaces chat-id))
          (update-chats-index! db metrics))))))

(def ^:private workspaces-hash-length 8)

(defn ^:private workspace-dir-name-parts
  "Splits a workspace cache dir name into [approx-name hash]. The dir name is
   '<sanitized-project-name>_<hash>' or just '<hash>'; the 8-char base64url
   hash may itself contain '_', so it is parsed positionally (last 8 chars)
   rather than by splitting on '_'. Unknown formats yield a nil hash."
  [^String dir-name]
  (let [n (count dir-name)]
    (cond
      (= n workspaces-hash-length)
      [dir-name dir-name]

      (and (> n (inc workspaces-hash-length))
           (= \_ (.charAt dir-name (- n (inc workspaces-hash-length)))))
      [(subs dir-name 0 (- n (inc workspaces-hash-length)))
       (subs dir-name (- n workspaces-hash-length))]

      :else [dir-name nil])))

(defn ^:private read-workspace-dir-chats
  "Reads the persisted chats of one workspace cache `dir`: the v7 chats index
   when present, falling back to the legacy pre-v7 whole-workspace blob
   (workspaces never reopened since v7). Returns
   {:workspaces [path...]-or-nil :chats {chat-id meta}} or nil when the dir
   has no readable chats."
  [^java.io.File dir metrics]
  (try
    (let [index-file (io/file dir "chats" "index.transit.json")
          legacy-file (io/file dir "db.transit.json")]
      (cond
        (fs/exists? index-file)
        (when-let [index (read-cache index-file chats-version metrics)]
          {:workspaces (not-empty (vec (:workspaces index)))
           :chats (stamp-chat-ids (:chats index))})

        (fs/exists? legacy-file)
        (when-let [chats (read-legacy-workspace-cache legacy-file metrics)]
          {:workspaces nil
           :chats (update-vals chats chat-list-meta)})))
    (catch Throwable e
      (logger/warn logger-tag (str "Could not read chats from workspace cache dir " dir) e)
      nil)))

(defn ^:private workspace-dir-chats-mtime
  "Last-modified time of the file `read-workspace-dir-chats` reads in `dir`
   (chats index, else legacy blob), or 0 when there is none."
  [^java.io.File dir]
  (let [index-file (io/file dir "chats" "index.transit.json")
        legacy-file (io/file dir "db.transit.json")]
    (cond
      (.exists index-file) (.lastModified index-file)
      (.exists legacy-file) (.lastModified legacy-file)
      :else 0)))

(defn ^:private recover-unknown-workspaces
  "Fills `:workspaces` of groups lacking persisted paths by *verifying*
   candidates against the group's one-way dir `:hash`: sibling directories of
   known workspace roots (`extra-roots` plus the groups' own persisted paths)
   are hashed with the same fn that named the cache dir, so a match recovers
   the exact original root. Guess-free (no match, no label) and single-root
   workspaces only: multi-root sets are combinatorial."
  [groups extra-roots]
  (let [unresolved (filter (every-pred (comp nil? :workspaces) :hash) groups)
        known-roots (into (set extra-roots) (mapcat :workspaces) groups)]
    (if (or (empty? unresolved) (empty? known-roots))
      groups
      (let [candidate-dirs (into #{}
                                 (comp (keep #(try (some-> (fs/parent (fs/file %)) str)
                                                   (catch Throwable _ nil)))
                                       (distinct)
                                       (mapcat (fn [parent]
                                                 (try
                                                   (->> (fs/list-dir parent)
                                                        (filter fs/directory?)
                                                        (map str))
                                                   (catch Throwable _ nil)))))
                                 known-roots)
            hash->path (reduce (fn [m path]
                                 (assoc m (cache/workspace-paths-hash [path]) path))
                               {}
                               candidate-dirs)]
        (mapv (fn [{:keys [workspaces hash] :as group}]
                (if-let [path (and (nil? workspaces) (get hash->path hash))]
                  (assoc group :workspaces [path])
                  group))
              groups)))))

(defn list-all-workspaces-chats
  "Lists persisted chats across ALL workspace cache dirs (data dir and legacy
   cache dir), so a chat can be located without opening each workspace. The
   current workspace's chats come from memory (freshest); other dirs are read
   from disk, tolerating unreadable ones, and a workspace with a dir in both
   locations is merged into one group. Subagent chats are excluded and a
   chat id duplicated across dirs (legacy duplicate dirs, #558) keeps only
   its most recent copy. Groups without persisted paths get a hash-verified
   recovery attempt (see `recover-unknown-workspaces`).

   Returns workspace groups sorted by most recent chat activity:
   [{:name string            ;; dir-name derived label, approximate
     :hash string-or-nil     ;; dir-name hash key
     :workspaces [path ...]  ;; exact roots when known (persisted, current or recovered)
     :current? boolean
     :chats [chat-meta ...]} ;; sorted by recency desc
    ...]"
  [db metrics]
  (let [workspaces (db-workspaces db)
        current-dir-name (.getName (cache/workspace-cache-dir workspaces shared/uri->filename))
        current-paths (not-empty (vec (cache/sorted-workspace-paths workspaces shared/uri->filename)))
        [current-name current-hash] (workspace-dir-name-parts current-dir-name)
        current-group {:name current-name
                       :hash current-hash
                       :workspaces current-paths
                       :current? true
                       :chats (vec (vals (db->index-entries db)))}
        ;; Same dir name = same workspace, data dir copy first so it wins
        ;; recency ties. The legacy copy is only read when written after it
        ;; (e.g. by an older ECA after a downgrade): the one-time copy left it
        ;; older, and reading every workspace twice would double listing time.
        other-groups (->> (cache/workspace-cache-dirs)
                          (remove (fn [^java.io.File dir] (= current-dir-name (.getName dir))))
                          (group-by (fn [^java.io.File dir] (.getName dir)))
                          (keep (fn [[dir-name [dir & other-dirs]]]
                                  (when-let [dir-chats (->> other-dirs
                                                            (filter #(> (workspace-dir-chats-mtime %)
                                                                        (workspace-dir-chats-mtime dir)))
                                                            (cons dir)
                                                            (keep #(read-workspace-dir-chats % metrics))
                                                            (seq))]
                                    (let [[approx-name hash] (workspace-dir-name-parts dir-name)]
                                      {:name approx-name
                                       :hash hash
                                       :workspaces (some :workspaces dir-chats)
                                       :current? false
                                       :chats (vec (vals (merge-chats (map :chats dir-chats))))})))))
        groups (mapv #(update % :chats (fn [chats] (vec (remove :subagent chats))))
                     (cons current-group other-groups))
        ;; Duplicated chat ids across dirs keep only the most recent copy.
        winners (reduce (fn [acc idx]
                          (reduce (fn [acc chat]
                                    (let [id (:id chat)
                                          existing (get acc id)]
                                      (if (or (nil? existing)
                                              (> (chat-recency chat) (chat-recency (:chat existing))))
                                        (assoc acc id {:idx idx :chat chat})
                                        acc)))
                                  acc
                                  (:chats (nth groups idx))))
                        {}
                        (range (count groups)))]
    (-> (->> groups
             (keep-indexed (fn [idx group]
                             (let [chats (->> (:chats group)
                                              (filter #(= idx (:idx (get winners (:id %)))))
                                              (sort-by chat-recency #(compare %2 %1))
                                              (vec))]
                               (when (seq chats)
                                 (assoc group :chats chats)))))
             (sort-by #(chat-recency (first (:chats %))) #(compare %2 %1))
             (vec))
        (recover-unknown-workspaces current-paths))))
