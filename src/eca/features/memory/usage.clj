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

;; abs-path-string -> {:reads long :last-read-at ms :body-hash string :changed-at ms
;;                     :last-write {:chat-id :parent-chat-id :tool-call-id :at-ms :maintenance?}}
;;
;; `:last-write` is provenance: the server (never the model) stamps it after a
;; successful write/edit and re-keys it on move. The id doubles as an evidence
;; pointer while `chatRetentionDays` keeps the chat on disk; after that a
;; missing chat simply means "no evidence". `:maintenance?` marks writes made
;; by /memory-consolidate itself so organic churn stays distinguishable.
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

(defn ^:private ledger-key
  "Ledger key form of a memory path. Must match the scan entry `:path` form in
   `memory.clj` so reads, writes and rankings join on the same key."
  [path]
  (str (fs/absolutize (fs/file path))))

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
                 (not (db/memory-consolidating? db chat-id))
                 (memory/memory-dir-info-for-path db config path))
        (update-ledger!
         (fn [ledger]
           (update ledger (ledger-key path)
                   (fn [entry]
                     ;; Keep body fields and do not move last-read-at back
                     ;; if a peer process has a clock ahead of ours.
                     (assoc entry
                            :reads (inc (long (or (:reads entry) 0)))
                            :last-read-at (max (long (or (:last-read-at entry) 0))
                                               (System/currentTimeMillis))))))))
      (catch Throwable e
        (logger/warn logger-tag "Failed to record memory read" (str path) e)))))

(defn record-write!
  "Record that the chat wrote `path`: replace `:last-write` with
   `{:chat-id :parent-chat-id :tool-call-id :at-ms :maintenance?}`, using the
   latest disk entry under the update lock.

   Unlike reads, writes during /memory-consolidate ARE recorded and flagged
   `:maintenance?` (true when the chat or its parent is consolidating), so
   organic writes stay distinguishable from consolidation churn. No-op when
   memory is disabled or `path` is not inside one of the chat's memory dirs.
   Never throws."
  [{:keys [chat-id tool-call-id db config]} path]
  (let [parent-chat-id (db/parent-chat-id db chat-id)]
    (logger/with-chat-context chat-id parent-chat-id
      (try
        (when (and path
                   (memory/enabled? config)
                   (memory/memory-dir-info-for-path db config path))
          (update-ledger!
           (fn [ledger]
             (update ledger (ledger-key path) (fnil assoc {})
                     :last-write {:chat-id chat-id
                                  :parent-chat-id parent-chat-id
                                  :tool-call-id tool-call-id
                                  :at-ms (System/currentTimeMillis)
                                  :maintenance? (db/memory-consolidating? db chat-id)}))))
        (catch Throwable e
          (logger/warn logger-tag "Failed to record memory write" (str path) e))))))

(defn record-move!
  "Re-key the ledger entry when a memory file is moved or renamed (the ledger
   is keyed by absolute path). Call BEFORE the move: after it, the source no
   longer exists and `update-ledger!`'s prune would drop the entry before it
   could be re-keyed. When the destination is outside the chat's memory dirs
   the file left the store, so the entry is dropped instead. No-op when memory
   is disabled or no entry exists for `source` (nothing is persisted then,
   thanks to the unchanged check in `update-ledger!`). Never throws. Directory
   moves are not handled; orphaned entries fall to `prune`."
  [{:keys [chat-id db config]} source destination]
  (logger/with-chat-context chat-id (db/parent-chat-id db chat-id)
    (try
      (when (and source destination (memory/enabled? config))
        (let [source-key (ledger-key source)
              destination-in-memory? (memory/memory-dir-info-for-path db config destination)]
          (update-ledger!
           (fn [ledger]
             (if-let [entry (get ledger source-key)]
               (if destination-in-memory?
                 (-> ledger (dissoc source-key) (assoc (ledger-key destination) entry))
                 (dissoc ledger source-key))
               ledger)))))
      (catch Throwable e
        (logger/warn logger-tag "Failed to re-key memory ledger entry" (str source) e)))))

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
