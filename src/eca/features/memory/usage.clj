(ns eca.features.memory.usage
  "Usage ledger for memory files: per-file read counts and last-read
   timestamps persisted to <memory-root>/usage.edn. One user-local ledger for
   all tiers (shared-tier reads are deliberately never recorded). Each update
   reads the latest disk ledger under JVM and OS locks, then replaces the
   file through a unique sibling temp file."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [eca.db :as db]
   [eca.digest :as digest]
   [eca.features.memory :as memory]
   [eca.file-io :as file-io]
   [eca.logger :as logger])
  (:import
   [java.io File]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[MEMORY-USAGE]")

(defn usage-file
  "The single user-local usage ledger file: <memory-root>/usage.edn."
  ^File []
  (io/file (memory/memory-root-dir) "usage.edn"))

;; abs-path-string -> {:reads long :last-read-at ms :body-hash string :changed-at ms}
;;
;; `:body-hash`/`:changed-at` answer a different question than the file's
;; mtime: WHEN THE KNOWLEDGE CHANGED, not when the file was written. The two
;; diverge exactly where it hurts — /memory-consolidate normalizes tags and
;; trims descriptions, which rewrites files without changing what they say,
;; refreshing every mtime it touches and corrupting the staleness signal its
;; own next run depends on. Hashing the parsed body (frontmatter excluded by
;; construction) makes the distinction mechanical instead of asking the model
;; to judge it: a merge or rewrite moves the date, a retag does not.
(defonce ledger* (atom {}))

(defonce loaded?* (atom false))

(defn ^:private prune
  "Ledger map without entries whose file no longer exists."
  [ledger]
  (into {}
        (keep (fn [[path entry]]
                (when (fs/exists? (fs/file path))
                  [path entry])))
        ledger))

(defn ^:private read-ledger-from-disk!
  "Parse the current on-disk usage.edn into a map. A missing file is an empty
   ledger; corrupt content logs a warning and is treated as empty. Never
   throws."
  []
  (let [f (usage-file)]
    (if (fs/exists? f)
      (try
        (let [data (edn/read-string (slurp (str f)))]
          (if (map? data)
            data
            (do
              (logger/warn logger-tag "Usage ledger content is not a map; treating as empty"
                           (str f))
              {})))
        (catch Throwable e
          (logger/warn logger-tag "Could not parse usage ledger; treating as empty"
                       (str f) e)
          {}))
      {})))

(defn ^:private with-ledger-lock-fn [f]
  (file-io/with-os-file-lock-fn (io/file (str (usage-file) ".lock")) f))

(defn load!
  "Load once per process, pruning entries whose file no longer exists.
   Uses the update lock so a delayed load cannot overwrite a completed update.
   Missing or corrupt data starts empty; lock failures warn and can be retried."
  []
  (try
    (with-ledger-lock-fn
      (fn []
        (when-not @loaded?*
          (reset! ledger* (prune (read-ledger-from-disk!)))
          (reset! loaded?* true))
        @ledger*))
    (catch Throwable e
      (logger/warn logger-tag "Could not load usage ledger" e)
      @ledger*)))

(defn ^:private update-ledger!
  "Apply f to the latest disk ledger under the update lock. Publish the cached
   value only after a successful replacement (or an unchanged observation).
   Never throws; failed writes leave the cached value unchanged."
  [f]
  (try
    (with-ledger-lock-fn
      (fn []
        (let [ledger (read-ledger-from-disk!)
              updated (f (prune ledger))]
          (when (not= ledger updated)
            (file-io/replace-file! (usage-file) #(spit % (pr-str updated))))
          (reset! ledger* updated)
          (reset! loaded?* true)
          updated)))
    (catch Throwable e
      (logger/warn logger-tag "Could not persist usage ledger" (str (usage-file)) e)
      @ledger*)))

(defn record-read!
  "Record a read of `path` in the usage ledger: bump `:reads` and stamp
   `:last-read-at`, using the latest disk entry under the update lock.

   No-op when memory is disabled, when the chat or its parent chat (subagent
   escape hatch) is consolidating, or when `path` is not inside one of the
   chat's memory dirs. Never throws."
  [{:keys [chat-id db config]} path]
  (logger/with-chat-context chat-id (db/parent-chat-id db chat-id)
    (try
      (when (and path
                 (memory/enabled? config)
                 (not (get-in db [:chats chat-id :memory-consolidating?]))
                 (not (get-in db [:chats (db/parent-chat-id db chat-id) :memory-consolidating?]))
                 (memory/memory-dir-info-for-path db config path))
        (let [path (str (fs/absolutize (fs/file path)))]
          (update-ledger!
           (fn [ledger]
             (update ledger path
                     (fn [entry]
                       ;; Keep body fields and do not move last-read-at back
                       ;; if a peer process has a clock ahead of ours.
                       (assoc entry
                              :reads (inc (long (or (:reads entry) 0)))
                              :last-read-at (max (long (or (:last-read-at entry) 0))
                                                 (System/currentTimeMillis)))))))))
      (catch Throwable e
        (logger/warn logger-tag "Failed to record memory read" (str path) e)))))

(defn ^:private body-change-updates
  "Ledger updates `{path {:body-hash h :changed-at ms}}` for entries whose body
   hash is missing or different, and nothing for the rest — so a scan that
   observes no content change writes nothing.

   First sight of a file seeds `:changed-at` from its mtime rather than `now`:
   enabling this on an existing store must not make every memory look freshly
   changed. Entries without a `:body` key (never produced by `scan-memories`,
   but cheap to tolerate) are skipped."
  [entries ledger now-ms]
  (reduce (fn [acc {:keys [path body mtime-ms]}]
            (if (nil? body)
              acc
              (let [hash (digest/sha-256-hex body)
                    {:keys [body-hash changed-at]} (get ledger path)]
                (cond
                  (= hash body-hash) acc
                  (nil? body-hash) (assoc acc path {:body-hash hash
                                                    :changed-at (or changed-at mtime-ms now-ms)})
                  :else (assoc acc path {:body-hash hash :changed-at now-ms})))))
          {}
          entries))

(defn enrich
  "Merge usage stats into scan-memories-style entries: adds `:reads` (0 when
   never read) and `:last-read-at` (nil when never read), and refines
   `:updated-at-ms` from the file's mtime to when the knowledge actually
   changed, once the ledger has seen this body before.

   Not pure: observing a changed body is what records it, so this also stamps
   the ledger and persists — but only when a hash actually moved, so the
   steady state of a per-turn scan is read-only. `:mtime-ms` keeps its
   filesystem meaning and stays on the entry for `list-memories`."
  [entries]
  (let [ledger (update-ledger!
                 (fn [ledger]
                   (let [updates (body-change-updates entries ledger (System/currentTimeMillis))]
                     (merge-with merge ledger updates))))]
    (mapv (fn [entry]
            (let [usage (get ledger (:path entry))]
              (assoc entry
                     :reads (long (or (:reads usage) 0))
                     :last-read-at (:last-read-at usage)
                     :updated-at-ms (or (:changed-at usage)
                                        (:updated-at-ms entry)
                                        (:mtime-ms entry)))))
          entries)))
