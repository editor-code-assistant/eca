(ns eca.db.io
  "The cache files: where they are, and transit reads and writes. File locks
   and atomic replacement come from `eca.file-io`."
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [cognitect.transit :as transit]
   [eca.cache :as cache]
   [eca.digest :as digest]
   [eca.file-io :as file-io]
   [eca.logger :as logger]
   [eca.metrics :as metrics]
   [eca.shared :as shared])
  (:import
   [java.io OutputStream]
   [java.nio.file Files LinkOption]
   [java.nio.file.attribute BasicFileAttributes]
   [java.util.concurrent ConcurrentHashMap]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[DB]")

(def version
  "Schema version of the global cache (`~/.cache/eca/db.transit.json`) and of
   the legacy whole-workspace chat blobs read only for migration. Kept at 6 on
   purpose: the global (auth) schema did not change with the per-chat layout,
   so rolling back to an older ECA does not log users out."
  6)

(def chats-version
  "Schema version of the per-chat layout files (`chats/index.transit.json` and
   `chats/<chat-id>.transit.json`). Successor of the version 6 whole-workspace
   blob (#557); older ECA versions never read these files."
  7)

(defn ^:private no-flush-output-stream [^OutputStream os]
  (proxy [java.io.BufferedOutputStream] [os]
    (flush [])
    (close []
      (let [^java.io.BufferedOutputStream this this]
        (proxy-super flush)
        (proxy-super close)))))

(defn transit-global-db-file []
  (io/file (cache/global-dir) "db.transit.json"))

(defn legacy-workspace-db-file
  "Pre-v7 whole-workspace chat blob (every chat in one file). Only read to
   migrate it into the per-chat layout; never written anymore (#557)."
  ^java.io.File [workspaces]
  (cache/workspace-cache-file workspaces "db.transit.json" shared/uri->filename))

(defn db-workspaces
  "Workspace set keying this session's cache dir. Prefers the folders the
   session initialized with so mid-session workspace folder changes do not
   retarget the cache dir."
  [db]
  (or (:initial-workspace-folders db)
      (:workspace-folders db)))

(defn chats-dir
  ^java.io.File [workspaces]
  (io/file (cache/workspace-cache-dir workspaces shared/uri->filename) "chats"))

(defn chats-index-file
  ^java.io.File [workspaces]
  (io/file (chats-dir workspaces) "index.transit.json"))

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

(defn chat-file
  ^java.io.File [workspaces chat-id]
  (io/file (chats-dir workspaces) (chat-file-name chat-id)))

(defn read-transit-file
  "Read and return the transit+json data from a cache file.
   Returns nil when the file does not exist. Throws on I/O or parse errors."
  [^java.io.File cache-file]
  (when (fs/exists? cache-file)
    (with-open [is (io/input-stream cache-file)]
      (transit/read (transit/reader is :json)))))

(defn read-cache [cache-file expected-version metrics]
  (try
    (metrics/task metrics :db/read-cache
      (if-let [cache (read-transit-file cache-file)]
        (when (= expected-version (:version cache))
          cache)
        (logger/info logger-tag (str "No existing DB cache found for " cache-file))))
    (catch Throwable e
      (logger/error logger-tag (str "Could not load cache from " cache-file) e))))

(defn upsert-cache!
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
        ;; The helper shares this monitor across callers; it is not local
        ;; to this scope. Suppress the false positive.
        #_{:clj-kondo/ignore [:locking-suspicious-lock]}
        (locking (file-io/file-lock dest)
          ;; Best-effort cleanup of the legacy fixed-name `<dest>.tmp` left by
          ;; pre-unique-tmp versions of ECA. Safe to delete because new code
          ;; only ever creates random-suffixed temps via Files/createTempFile.
          (let [legacy-tmp ^java.io.File (io/file (str (.getPath dest) ".tmp"))]
            (when (.exists legacy-tmp)
              (try (.delete legacy-tmp) (catch Throwable _))))
          (file-io/replace-file!
           dest
           (fn [tmp]
             ;; https://github.com/cognitect/transit-clj/issues/43
             (with-open [os ^OutputStream (no-flush-output-stream (io/output-stream tmp))]
               (let [writer (transit/writer os :json)]
                 (transit/write writer cache))))))))
    (catch Throwable e
      (logger/error logger-tag (str "Could not upsert db cache to " cache-file) e))))

(defn read-global-cache [metrics]
  (read-cache (transit-global-db-file) version metrics))

(defn chat-recency [chat]
  (or (:updated-at chat) (:created-at chat) 0))

(defn merge-chats
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

(defn workspace-cache-lock-file ^java.io.File [^java.io.File cache-file]
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

(defn record-workspace-write-attrs!
  "Remembers the on-disk attributes of `f` right after this process wrote it,
   so the next write can cheaply detect whether another process wrote in
   between (see `workspace-cache-changed-on-disk?`)."
  [^java.io.File f]
  (if-let [attrs (cache-file-attrs f)]
    (.put last-workspace-write-attrs (.getAbsolutePath f) attrs)
    (.remove last-workspace-write-attrs (.getAbsolutePath f))))

(defn workspace-cache-changed-on-disk?
  "True when `f` exists with different attributes than the last write this
   process made to it - i.e. another process wrote it (or this process never
   wrote it yet), so its content must be merged instead of overwritten."
  [^java.io.File f]
  (let [attrs (cache-file-attrs f)]
    (boolean (and attrs (not= attrs (.get last-workspace-write-attrs (.getAbsolutePath f)))))))
