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
        (remove (fn [[path _]]
                  (Files/notExists (fs/path path) (make-array java.nio.file.LinkOption 0))))
        ledger))

(defn ^:private read-ledger-text [^File f]
  (Files/readString (.toPath f)))

(defn ^:private parse-ledger [text f]
  (try
    (let [data (edn/read-string text)]
      (if (map? data)
        data
        (do
          (logger/warn logger-tag "Usage ledger content is not a map; treating as empty" f)
          {})))
    (catch Exception e
      (logger/warn logger-tag "Could not parse usage ledger; treating as empty" f e)
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
        (logger/warn logger-tag "Could not read usage ledger" f e)
        nil))))

(def ^:private ledger-lock-timeout-ms 2000)

(defn ^:private update-ledger!
  "Applies `f` under the ledger lock; falls back to disk read on lock/write error."
  [f]
  (try
    (file-io/with-os-file-lock-fn
      (io/file (str (usage-file) ".lock"))
      {:timeout-ms ledger-lock-timeout-ms}
      (fn []
        (when-let [ledger (read-ledger-from-disk!)]
          (let [updated (f ledger)]
            (when (not= ledger updated)
              (file-io/replace-file! (usage-file) #(spit % (pr-str updated) :encoding "UTF-8")))
            updated))))
    (catch Throwable e
      (logger/warn logger-tag "Could not persist usage ledger" (usage-file) e)
      (read-ledger-from-disk!))))

(defn record-read!
  "Increments `:reads` and updates `:last-read-at` for an indexable memory note.
   No-op for non-memory paths. Never throws."
  [{:keys [chat-id db]} path]
  (logger/with-chat-context chat-id (db/parent-chat-id db chat-id)
    (try
      (when-let [key (m.memory.store/indexable-memory-path db path)]
        (update-ledger!
         #(update % key
                  (fn [entry]
                    (assoc entry
                           :reads (inc (or (:reads entry) 0))
                           :last-read-at (System/currentTimeMillis))))))
      (catch Throwable e
        (logger/warn logger-tag "Failed to record memory read" path e)))))

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
                              (filter val)
                              {:chat-id chat-id
                               :tool-call-id tool-call-id
                               :parent-chat-id parent-chat-id
                               :consolidation? consolidation?})]
            (update-ledger!
             #(update % key (fnil update {}) :writes add-write pointer))))
        (catch Throwable e
          (logger/warn logger-tag "Failed to record memory write" path e))))))

(defn record-move!
  "Re-keys moved file/directory descendants after a move (`source` captured
   before moving). Keeps entries moving into trash; drops entries leaving memory.
   Never throws."
  [{:keys [chat-id db]} source destination]
  (logger/with-chat-context chat-id (db/parent-chat-id db chat-id)
    (try
      (update-ledger!
       (fn [ledger]
         (reduce-kv
          (fn [result path entry]
            ;; Skip entries whose physical source still exists (e.g. moving a symlink).
            (if (and (shared/path-inside-root? path source)
                     (not (fs/exists? path)))
              (let [relative (fs/relativize source path)
                    raw-target (str (fs/path destination relative))
                    target (or (m.memory.store/indexable-memory-path db raw-target)
                               (when (m.memory.store/trash-path? db raw-target)
                                 (m.memory.store/abs-path raw-target)))]
                (cond-> (dissoc result path)
                  target (assoc target entry)))
              result))
          ledger ledger)))
      (catch Throwable e
        (logger/warn logger-tag "Failed to re-key memory ledger entry" source e)))))

(defn ^:private body-change-updates
  "Returns `{path {:body-hash :changed-at}}` only for notes whose body hash is
   missing or changed, taking `:changed-at` from mtime (clamped to `now-ms`)."
  [entries ledger now-ms]
  (reduce (fn [acc {:keys [path body mtime-ms]}]
            (let [hash (digest/sha-256-hex body)]
              (if (= hash (:body-hash (get ledger path)))
                acc
                (assoc acc path {:body-hash hash :changed-at (min now-ms mtime-ms)}))))
          {}
          entries))

(defn ^:private apply-scan-updates [entries ledger now-ms]
  (prune (merge-with merge ledger (body-change-updates entries ledger now-ms))))

(defn enrich
  "Merges `:reads`, `:last-read-at`, and body-based `:updated-at-ms` into scan
   entries. Reads lock-free first and only takes the OS lock when body hashes
   changed or deleted entries need pruning."
  [entries]
  (let [ledger (when-some [disk (read-ledger-from-disk!)]
                 (try
                   (let [candidate (apply-scan-updates entries disk (System/currentTimeMillis))]
                     (if (= disk candidate)
                       disk
                       (update-ledger!
                        #(apply-scan-updates entries % (System/currentTimeMillis)))))
                   (catch Throwable e
                     (logger/warn logger-tag "Could not apply usage ledger; using notes without usage stats" (usage-file) e)
                     nil)))]
    (mapv (fn [entry]
            (let [raw (get ledger (:path entry))
                  {:keys [reads last-read-at changed-at]} (when (map? raw) raw)]
              (assoc entry
                     :reads (if (number? reads) reads 0)
                     :last-read-at (when (number? last-read-at) last-read-at)
                     :updated-at-ms (if (number? changed-at) changed-at (:updated-at-ms entry)))))
          entries)))

(def ^:private ms-per-day 86400000.0)
(def ^:private score-decay-days 30.0)

(defn ^:private usage-score
  "Decay-weighted relevance score: `ln(1 + reads) * exp(-read-age/30) + exp(-change-age/30)`.
   Log damping keeps a single read burst from pinning a stale note on top;
   unread notes reduce to plain recency."
  [{:keys [reads last-read-at updated-at-ms]} now-ms]
  (let [age-days (fn [ts] (max 0.0 (/ (- now-ms ts) ms-per-day)))
        decay (fn [ts] (Math/exp (- (/ (age-days ts) score-decay-days))))
        habit-term (if (and (pos? reads) last-read-at)
                     (* (Math/log (inc reads)) (decay last-read-at))
                     0.0)
        recency-term (decay updated-at-ms)]
    (+ habit-term recency-term)))

(defn ranked-scan
  "Scans the memory store, enriches usage stats, and sorts `:entries` by `:score`
   descending within each directory."
  ([db] (ranked-scan db {}))
  ([db {:keys [now-ms] :or {now-ms (System/currentTimeMillis)}}]
   (let [{:keys [entries] :as scan} (m.memory.store/scan-memories db)]
     (assoc scan :entries
            (into []
                  (comp (partition-by :label)
                        (mapcat (fn [dir-entries]
                                  (sort-by :score >
                                           (map #(assoc % :score (usage-score % now-ms)) dir-entries)))))
                  (enrich entries))))))
