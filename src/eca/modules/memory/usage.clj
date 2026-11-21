(ns eca.modules.memory.usage
  "Per-file memory usage ledger persisted at `<memory-root>/usage.edn`.
   Updates read-modify-write under JVM and OS locks via atomic replacement."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [eca.db :as db]
   [eca.digest :as digest]
   [eca.file-io :as file-io]
   [eca.logger :as logger]
   [eca.modules.memory.store :as m.memory.store]
   [eca.shared :as shared])
  (:import
   [java.io File]
   [java.nio.file Files NoSuchFileException]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[MEMORY-USAGE]")

(defn usage-file
  "Path to the user-local usage ledger: `<memory-root>/usage.edn`."
  ^File []
  (io/file (m.memory.store/memory-root-dir) "usage.edn"))

;; abs-path -> {:reads long :last-read-at ms :body-hash string :changed-at ms
;;              :writes [{:chat-id :tool-call-id :parent-chat-id? :consolidation?}] }
;; `:body-hash`/`:changed-at` track body edits separately from mtime so tag-only
;; consolidation rewrites do not reset a note's staleness date.

(defn ^:private prune
  "Drops entries whose file definitely does not exist (`Files/notExists` keeps
   entries in temporarily unreadable directories)."
  [ledger]
  (into {}
        (keep (fn [[path entry]]
                (when-not (Files/notExists (fs/path path) (make-array java.nio.file.LinkOption 0))
                  [path entry])))
        ledger))

(defn ^:private read-ledger-text [^File f]
  (Files/readString (.toPath f)))

(defn ^:private parse-ledger [text f]
  (try
    (let [data (edn/read-string text)]
      (if (map? data)
        data
        (do
          (logger/warn logger-tag "Usage ledger content is not a map; treating as empty" (str f))
          {})))
    (catch Exception e
      (logger/warn logger-tag "Could not parse usage ledger; treating as empty" (str f) e)
      {})))

(defn ^:private read-ledger-from-disk!
  "Returns `{}` for missing/corrupt ledgers, or nil on I/O failure so callers
   skip writing over unread history."
  []
  (let [f (usage-file)]
    (try
      (parse-ledger (read-ledger-text f) f)
      (catch NoSuchFileException _ {})
      (catch Exception e
        (logger/warn logger-tag "Could not read usage ledger" (str f) e)
        nil))))

(def ^:private ledger-lock-timeout-ms 2000)

(defn ^:private with-ledger-lock-fn [f]
  (file-io/with-os-file-lock-fn (io/file (str (usage-file) ".lock"))
                                {:timeout-ms ledger-lock-timeout-ms}
                                f))

(defn ^:private update-ledger!
  "Applies `f` under the ledger lock; falls back to disk read on lock/write error."
  [f]
  (try
    (with-ledger-lock-fn
      (fn []
        (when-let [ledger (read-ledger-from-disk!)]
          (let [updated (f ledger)]
            (when (not= ledger updated)
              (file-io/replace-file! (usage-file) #(spit % (pr-str updated) :encoding "UTF-8")))
            updated))))
    (catch Throwable e
      (logger/warn logger-tag "Could not persist usage ledger" (str (usage-file)) e)
      (read-ledger-from-disk!))))

(defn record-read!
  "Increments `:reads` and updates `:last-read-at` for an indexable memory note.
   No-op for non-memory paths. Never throws."
  [{:keys [chat-id db]} path]
  (logger/with-chat-context chat-id (db/parent-chat-id db chat-id)
    (try
      (when-let [key (m.memory.store/indexable-memory-path db path)]
        (update-ledger!
         (fn [ledger]
           (update ledger key
                   (fn [entry]
                     ;; Don't move :last-read-at backwards on peer clock skew.
                     (assoc entry
                            :reads (inc (long (or (:reads entry) 0)))
                            :last-read-at (max (long (or (:last-read-at entry) 0))
                                               (System/currentTimeMillis))))))))
      (catch Throwable e
        (logger/warn logger-tag "Failed to record memory read" (str path) e)))))

(def ^:private max-writes 10)

(defn ^:private add-write
  "Appends `pointer` unless the same chat/kind already exists; past `max-writes`,
   drops the oldest non-creator pointers."
  [writes pointer]
  (let [writes (vec writes)
        same? #(= (dissoc % :tool-call-id) (dissoc pointer :tool-call-id))]
    (if (some same? writes)
      writes
      (let [writes (conj writes pointer)
            n (count writes)]
        (if (> n max-writes)
          (into [(first writes)] (subvec writes (- n (dec max-writes))))
          writes)))))

(defn record-write!
  "Records a write pointer (`:chat-id`, `:tool-call-id`, optional `:parent-chat-id`
   and `:consolidation?`) for an indexable memory note. Never throws."
  [{:keys [chat-id tool-call-id db consolidation?]} path]
  (let [parent-chat-id (db/parent-chat-id db chat-id)]
    (logger/with-chat-context chat-id parent-chat-id
      (try
        (when-let [key (m.memory.store/indexable-memory-path db path)]
          (let [pointer (into {}
                              (remove (comp nil? val))
                              {:chat-id chat-id
                               :tool-call-id tool-call-id
                               :parent-chat-id parent-chat-id
                               :consolidation? (when consolidation? true)})]
            (update-ledger!
             (fn [ledger]
               (update ledger key (fnil update {})
                       :writes add-write pointer)))))
        (catch Throwable e
          (logger/warn logger-tag "Failed to record memory write" (str path) e))))))

(defn record-move!
  "Re-keys moved file/directory descendants after a move (`source` captured
   before moving). Keeps entries moving into trash; drops entries leaving memory.
   Never throws."
  [{:keys [chat-id db]} source destination]
  (logger/with-chat-context chat-id (db/parent-chat-id db chat-id)
    (try
      (when (and source destination)
        (let [source-key (str source)]
          (update-ledger!
           (fn [ledger]
             (reduce-kv
              (fn [result path entry]
                ;; Skip entries whose physical source still exists (e.g. moving a symlink).
                (if (and (shared/path-inside-root? path source-key)
                         (not (fs/exists? path)))
                  (let [relative (fs/relativize source-key path)
                        raw-target (str (fs/path destination relative))
                        target (or (m.memory.store/indexable-memory-path db raw-target)
                                   (when (m.memory.store/trash-path? db raw-target)
                                     (m.memory.store/abs-path raw-target)))]
                    (cond-> (dissoc result path)
                      target (assoc target entry)))
                  result))
              ledger ledger)))))
      (catch Throwable e
        (logger/warn logger-tag "Failed to re-key memory ledger entry" (str source) e)))))

(defn ^:private body-change-updates
  "Returns `{path {:body-hash :changed-at}}` only for notes whose body hash is
   missing or changed, seeding `:changed-at` from mtime (clamped to `now-ms`)."
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
                  :else (assoc acc path {:body-hash hash
                                         :changed-at (min now-ms (or mtime-ms now-ms))})))))
          {}
          entries))

(defn ^:private apply-scan-updates [entries ledger now-ms]
  (let [updates (body-change-updates entries ledger now-ms)]
    (prune (merge-with merge ledger updates))))

(defn enrich
  "Merges `:reads`, `:last-read-at`, and body-based `:updated-at-ms` into scan
   entries. Reads lock-free first and only takes the OS lock when body hashes
   changed or deleted entries need pruning."
  [entries]
  (let [disk (read-ledger-from-disk!)
        ledger (when (some? disk)
                 (try
                   (let [candidate (apply-scan-updates entries disk (System/currentTimeMillis))]
                     (if (= disk candidate)
                       disk
                       (update-ledger!
                        (fn [latest]
                          (apply-scan-updates entries latest (System/currentTimeMillis))))))
                   (catch Throwable e
                     (logger/warn logger-tag "Could not apply usage ledger; using notes without usage stats" (str (usage-file)) e)
                     nil)))]
    (mapv (fn [entry]
            (let [raw (get ledger (:path entry))
                  usage (when (map? raw) raw)
                  reads (:reads usage)
                  last-read-at (:last-read-at usage)
                  changed-at (:changed-at usage)]
              (assoc entry
                     :reads (if (number? reads) (long reads) 0)
                     :last-read-at (when (number? last-read-at) last-read-at)
                     :updated-at-ms (or (when (number? changed-at) changed-at)
                                        (:updated-at-ms entry)
                                        (:mtime-ms entry)))))
          entries)))

(def ^:private ms-per-day 86400000.0)
(def ^:private score-decay-days 30.0)

(defn ^:private usage-score
  "Decay-weighted relevance score: `ln(1 + reads) * exp(-read-age/30) + exp(-change-age/30)`.
   Log damping keeps a single read burst from pinning a stale note on top;
   unread notes reduce to plain recency."
  [entry now-ms]
  (let [now-ms (long now-ms)
        age-days (fn [ts] (max 0.0 (/ (- now-ms (long ts)) ms-per-day)))
        decay (fn [ts] (Math/exp (- (/ (age-days ts) score-decay-days))))
        reads (long (or (:reads entry) 0))
        last-read-at (:last-read-at entry)
        habit-term (if (and (pos? reads) last-read-at)
                     (* (Math/log (inc (double reads))) (decay last-read-at))
                     0.0)
        recency-term (decay (long (or (:updated-at-ms entry) 0)))]
    (+ habit-term recency-term)))

(defn ranked-scan
  "Scans the memory store, enriches usage stats, and sorts `:entries` by `:score`
   descending within each directory."
  ([db] (ranked-scan db {}))
  ([db {:keys [now-ms] :or {now-ms (System/currentTimeMillis)}}]
   (let [{:keys [entries] :as scan} (m.memory.store/scan-memories db)
         now-ms (long now-ms)
         ranked (into []
                      (comp (partition-by :label)
                            (mapcat (fn [dir-entries]
                                      (sort-by :score >
                                               (map #(assoc % :score (usage-score % now-ms)) dir-entries)))))
                      (enrich entries))]
     (assoc scan :entries ranked))))
