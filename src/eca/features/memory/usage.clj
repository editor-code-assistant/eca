(ns eca.features.memory.usage
  "Usage ledger for memory files: per-file read counts and last-read
   timestamps persisted to <memory-root>/usage.edn. One user-local ledger for
   all notes; reads of files outside the memory dirs are never recorded. Each
   update reads the latest disk ledger under JVM and OS locks, then replaces
   the file through a unique sibling temp file."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [eca.db :as db]
   [eca.digest :as digest]
   [eca.features.memory :as memory]
   [eca.file-io :as file-io]
   [eca.logger :as logger]
   [eca.shared :as shared])
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
;; diverge exactly where it hurts: /memory-consolidate normalizes tags and
;; trims descriptions, which rewrites files without changing what they say,
;; refreshing every mtime it touches and corrupting the staleness signal its
;; own next run depends on. Hashing the parsed body (frontmatter excluded by
;; construction) makes the distinction mechanical instead of asking the model
;; to judge it: a merge or rewrite moves the date, a retag does not.

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

(defn ^:private update-ledger!
  "Apply f to the latest disk ledger under the update lock, then prune. `f`
   runs first, so it still sees the entry of a file that was just moved and
   can re-key it. Returns the new ledger when the replacement succeeded, and
   the current on-disk ledger when the read or write failed. Never throws."
  [f]
  (try
    (with-ledger-lock-fn
      (fn []
        (let [ledger (read-ledger-from-disk!)
              updated (prune (f ledger))]
          (when (not= ledger updated)
            (file-io/replace-file! (usage-file) #(spit % (pr-str updated))))
          updated)))
    (catch Throwable e
      (logger/warn logger-tag "Could not persist usage ledger" (str (usage-file)) e)
      (read-ledger-from-disk!))))

(defn record-read!
  "Record a read of `path` in the usage ledger: bump `:reads` and stamp
   `:last-read-at`, using the latest disk entry under the update lock.

   No-op when memory is disabled, when the chat or its parent chat (subagent
   escape hatch) is consolidating, or when `path` is not inside one of the
   chat's memory dirs. Never throws."
  [{:keys [chat-id db config]} path]
  (logger/with-chat-context chat-id (db/parent-chat-id db chat-id)
    (try
      (when (and (not (db/memory-consolidating? db chat-id))
                 (memory/memory-path? db config path))
        (update-ledger!
         (fn [ledger]
           (update ledger (memory/abs-path path)
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
        (when (memory/memory-path? db config path)
          (update-ledger!
           (fn [ledger]
             (update ledger (memory/abs-path path) (fnil assoc {})
                     :last-write {:chat-id chat-id
                                  :parent-chat-id parent-chat-id
                                  :tool-call-id tool-call-id
                                  :at-ms (System/currentTimeMillis)
                                  :maintenance? (db/memory-consolidating? db chat-id)}))))
        (catch Throwable e
          (logger/warn logger-tag "Failed to record memory write" (str path) e))))))

(defn record-move!
  "Re-key a moved file or directory's descendants after a successful move.
   Pass the canonical source captured BEFORE moving. Entries whose physical
   source still exists (including unchanged symlink targets) stay in place.
   Entries leaving memory are dropped. Never throws."
  [{:keys [chat-id db config]} source destination]
  (logger/with-chat-context chat-id (db/parent-chat-id db chat-id)
    (try
      (when (and source destination (memory/enabled? config))
        (let [source-key (str source)]
          (update-ledger!
           (fn [ledger]
             (reduce-kv
              (fn [result path entry]
                (if (and (shared/path-inside-root? path source-key)
                         (not (fs/exists? path)))
                  (let [relative (fs/relativize source-key path)
                        target (memory/abs-path (fs/path destination relative))]
                    (cond-> (dissoc result path)
                      (memory/memory-path? db config target) (assoc target entry)))
                  result))
              ledger ledger)))))
      (catch Throwable e
        (logger/warn logger-tag "Failed to re-key memory ledger entry" (str source) e)))))

(defn ^:private body-change-updates
  "Ledger updates `{path {:body-hash h :changed-at ms}}` for entries whose body
   hash is missing or different, and nothing for the rest, so a scan that
   observes no content change writes nothing.

   First sight of a file seeds `:changed-at` from its mtime rather than `now`:
   enabling this on an existing store must not make every memory look freshly
   changed. A changed body takes the mtime too: the file time is when the body
   was written, and `now-ms` is only when this scan noticed it, which can be
   weeks later. A file time in the future is cut to `now-ms`. Entries without
   a `:body` key (never produced by `scan-memories`, but cheap to tolerate)
   are skipped."
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

(defn enrich
  "Merge usage stats into scan-memories-style entries: adds `:reads` (0 when
   never read) and `:last-read-at` (nil when never read), and refines
   `:updated-at-ms` from the file's mtime to when the knowledge actually
   changed, once the ledger has seen this body before.

   Not pure: observing a changed body is what records it, so this also stamps
   the ledger and persists, but only when a hash actually moved, so the
   steady state of a routine scan is read-only. `:mtime-ms` keeps its
   filesystem meaning; it seeds the first `:changed-at` of a new body."
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

(def ^:private ms-per-day 86400000.0)

(def ^:private score-decay-days
  "After this many days a read or a change weighs 1/e (about a third) of a
   fresh one in `usage-score`."
  30.0)

(defn ^:private usage-score
  "Decay-weighted relevance score for a scan entry at `now-ms`:
   `ln(1 + reads) · exp(-age_last_read_days/30) + exp(-age_mtime_days/30)`,
   where day ages are floating-point and clamped at 0 (future timestamps
   count as fresh). Higher score = more relevant.

   The read count enters LOGARITHMICALLY (diminishing returns, the same
   trick search engines use for term frequency): the second read matters,
   the fortieth barely anything. A linear count lets one intense debug
   session (40 consults in a day) pin a memory to the top of the index for
   ~110 days; with `ln(1 + reads)` that same burst fades below a fresh
   memory after ~39 days, while genuinely frequent references still rank
   higher than rarely consulted ones. The ledger stores only a count and a
   last-read timestamp, so burst and steady usage are indistinguishable in
   the data; damping is the robust answer for both.

   Entries with no recorded usage (`reads` 0 or `:last-read-at` nil)
   degenerate to the recency term, which is monotonic in the timestamp, so
   with an empty ledger the resulting order is exactly plain descending
   recency.

   That timestamp is `:updated-at-ms`: when the knowledge last changed, which
   is the file's mtime until the usage ledger knows better. Without it, a
   /memory-consolidate pass that only normalizes tags would reshuffle every
   never-read memory to the top."
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

(defn ranked-memories
  "The store as the prompt index and /memory-consolidate see it: the entries
   of `memory/scan-memories` with ledger stats (see `enrich`), each with its
   `usage-score` as `:score`, sorted by that score inside each memory dir.
   The dir order stays, so the sort never crosses a dir, and with an empty
   ledger the order is plain recency. The index uses `:score` to pick the
   best project notes across workspace roots.

   One call takes the ledger lock once and can write the ledger (see
   `enrich`). `:now-ms` pins the reference time for tests."
  ([db] (ranked-memories db {}))
  ([db {:keys [now-ms] :or {now-ms (System/currentTimeMillis)}}]
   (let [now-ms (long now-ms)]
     (into []
           (comp (partition-by :label)
                 (mapcat (fn [dir-entries]
                           (sort-by :score >
                                    (map #(assoc % :score (usage-score % now-ms)) dir-entries)))))
           (enrich (memory/scan-memories db))))))
