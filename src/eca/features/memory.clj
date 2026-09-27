(ns eca.features.memory
  "File-based durable memory: two tiers of plain markdown files with YAML
   frontmatter (global, per-project personal). This
   namespace provides the public core API: config accessors, directory
   resolution, and scanning with lenient parsing."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [babashka.fs :as fs]
   [eca.cache :as cache]
   [eca.config :as config]
   [eca.logger :as logger]
   [eca.shared :as shared])
  (:import
   [java.io File]
   [java.nio.charset StandardCharsets]
   [java.security MessageDigest]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[MEMORY]")
(def ^:private valid-write-modes #{"agent" "explicit"})

(defn enabled?
  "True when the memory subsystem is enabled (`memory.enabled`, default false)."
  [config]
  (true? (get-in config [:memory :enabled] false)))

(defn write-mode
  "Effective write policy: \"agent\" (default; agent may record on its own) or
   \"explicit\" (record only when the user asks). Any invalid value fails safe
   to \"explicit\": a typo must never silently grant autonomous writes."
  [config]
  (let [mode (get-in config [:memory :writeMode] "agent")]
    (if (contains? valid-write-modes mode) mode "explicit")))

(defn index-max-entries
  "Maximum number of entries rendered in the memory index
   (`memory.index.maxEntries`, default 100)."
  [config]
  (get-in config [:memory :index :maxEntries] 100))

(defn index-max-tokens
  "Token budget for the whole memory index block
   (`memory.index.maxTokens`, default 2000)."
  [config]
  (get-in config [:memory :index :maxTokens] 2000))

(defn memory-root-dir
  "Root directory of all personal memory: <XDG_CONFIG_HOME or ~/.config>/eca/memory."
  ^File []
  (let [xdg-config-home (or (config/get-env "XDG_CONFIG_HOME")
                            (io/file (config/user-home) ".config"))]
    (io/file xdg-config-home "eca" "memory")))

(defn global-dir
  "Global memory directory (personal, all projects): <memory-root>/global."
  ^File []
  (io/file (memory-root-dir) "global"))

(def ^:private max-slug-readable-chars 64)

(defn ^:private short-path-hash
  "First 8 hex chars of the SHA-256 of `s` — collision-safe, stable across runs."
  ^String [^String s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes s StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" ^byte %) (take 4 digest)))))

(defn project-slug
  "Stable directory slug for a workspace path: canonicalized via
   `eca.cache/canonicalize-workspace-path` (linked worktrees map to their main
   worktree root), then rendered as a human-readable slug (runs of
   non-alphanumeric chars collapse to single dashes, dashes trimmed from both
   ends, max 64 chars) suffixed with a short hash of the canonical path. The
   hash makes distinct paths unique even when their readable slugs are
   identical (`/a/my.project` vs `/a/my project`), and trimming keeps the slug
   shell-friendly (no leading dash, safe as a CLI argument)."
  [path]
  (let [canonical (cache/canonicalize-workspace-path path)
        readable (-> canonical
                     (string/replace #"[^A-Za-z0-9]+" "-")
                     (string/replace #"^-+" "")
                     (#(if (> (count %) max-slug-readable-chars)
                         (subs % 0 max-slug-readable-chars)
                         %))
                     (string/replace #"-+$" ""))]
    (if (seq readable)
      (format "%s-%s" readable (short-path-hash canonical))
      (short-path-hash canonical))))

(defn personal-dir
  "Personal per-project memory directory for a workspace path:
   <memory-root>/projects/<project-slug>."
  ^File [workspace-path]
  (io/file (memory-root-dir) "projects" (project-slug workspace-path)))

(defn workspace-paths
  "Absolute workspace path strings for a chat's `:workspace-folders`."
  [db]
  (->> (:workspace-folders db)
       (keep #(some-> (:uri %) shared/uri->filename))
       (mapv str)))

(defn ^:private root-basename
  "Human-readable basename of a workspace root after worktree canonicalization."
  [root]
  (let [canonical-root (cache/canonicalize-workspace-path root)]
    (or (some-> (fs/file-name (fs/file canonical-root)) str not-empty)
        canonical-root)))

(defn memory-dirs
  "Ordered vector of this chat's memory directories: per workspace root its
   personal dir, then the global dir. Each entry is
   `{:dir <abs path string> :tier :personal|:global :label <string>}`.
   (A shared per-project tier was considered and cut from V1: team knowledge
   belongs in AGENTS.md / .eca/rules, which are versioned and reviewed.)"
  [db _config]
  (into []
        (concat
         (mapv (fn [root]
                 {:dir (str (fs/absolutize (fs/file (personal-dir root))))
                  :tier :personal
                  :label (root-basename root)})
               (workspace-paths db))
         [{:dir (str (fs/absolutize (fs/file (global-dir))))
           :tier :global
           :label "global"}])))

(defn memory-dir-info-for-path
  "The `memory-dirs` entry map whose dir contains `path`, or nil when the path
   is not inside any of the chat's memory dirs. `path` is absolutized first
   (the same normalization scan `:path` uses) so ledger keys join with scan
   entries."
  [db config path]
  (when path
    (let [path (str (fs/absolutize (fs/file path)))]
      (some (fn [{:keys [dir] :as entry}]
              (when (shared/path-inside-root? path dir)
                entry))
            (memory-dirs db config)))))

(defn pre-create-dirs!
  "Idempotently creates the global memory dir and every workspace's personal
   dir. Never throws; per-dir failures are logged. Shared dirs are NOT created."
  [db]
  (try
    (doseq [dir (cons (global-dir) (map personal-dir (workspace-paths db)))]
      (try
        (fs/create-dirs dir)
        (catch Throwable e
          (logger/warn logger-tag "Could not pre-create memory dir" (str dir) e))))
    (catch Throwable e
      (logger/warn logger-tag "Could not pre-create memory dirs" e))))

(def ^:private max-tags
  "Upper bound on tags kept per memory. A tag exists to group this memory with
   others; beyond three, the extras assert groupings that almost never all
   exist and they crowd the index `Tags in use:` line. Read-path only: files
   keep whatever they wrote, /memory-consolidate is what trims them."
  3)

(defn ^:private normalize-tags
  "Tags may be a YAML list or a comma-separated string; always normalize to a
   vector of trimmed, lowercased, distinct, non-blank strings, capped at
   `max-tags`. Lowercasing collapses casing variants (`Playwright` and
   `playwright`) for free; richer variant collapsing (plurals, hyphens) is
   deliberately left to /memory-consolidate, which sees the whole corpus and
   is reviewed before it writes."
  [tags]
  (->> (cond
         (string? tags) (string/split tags #",")
         (sequential? tags) (filter string? tags)
         :else [])
       (map (comp string/lower-case string/trim))
       (remove string/blank?)
       distinct
       (take max-tags)
       vec))

(declare memory-content-error)

(defn ^:private parse-memory-file
  "Parse one memory markdown file. Returns `{:ok <base entry>}` (everything
   except tier/label/mtime) on success or `{:skip <human reason>}` on any
   read/parse/validation failure. Failures are logged once per file
   fingerprint (the file cache suppresses re-warnings for unchanged files).
   The skip reason reuses `memory-content-error` wording so the index,
   consolidate snapshot and logs always describe identically."
  [file]
  (let [result
        (try
          (let [content (slurp (str file))]
            (if-let [error (memory-content-error content)]
              {:skip error}
              (let [{:keys [name description type tags body]} (shared/parse-md content)
                    name (some-> name str string/trim)
                    description (some-> description str string/trim)
                    type (some-> type str string/trim)]
                {:ok {:path (str (fs/absolutize (fs/file file)))
                      :name name
                      :description description
                      :type (if (string/blank? type) "note" type)
                      :tags (normalize-tags tags)
                      :body (or body "")}})))
          (catch Throwable e
            {:skip (str "cannot read file: "
                        (first (string/split (str (ex-message e)) #"\n")))}))]
    (when-let [reason (:skip result)]
      (logger/warn logger-tag "Skipping memory file" reason (str file)))
    result))

(defn memory-content-error
  "Error string explaining why `content` would NOT be a valid memory file, or
   nil when it is valid. Valid means: parses via `shared/parse-md` (YAML
   frontmatter must be a mapping; parse exceptions name the YAML issue) and
   yields non-blank `name` and `description` after trim. `type`/`tags`
   presence and format are intentionally not validated — lenient, mirroring
   `parse-memory-file` — and the body may be anything, including empty.
   Returns a one-line-ish, actionable message that guides the model to fix the
   frontmatter."
  [content]
  (try
    (let [{:keys [name description]} (shared/parse-md content)
          name (some-> name str string/trim)
          description (some-> description str string/trim)]
      (cond
        (string/blank? name)
        "Invalid memory file content: YAML frontmatter is missing required key `name` (memory files need non-blank name and description); fix and retry."

        (string/blank? description)
        "Invalid memory file content: YAML frontmatter is missing required key `description` (memory files need non-blank name and description); fix and retry."

        :else nil))
    (catch Exception e
      (format "Invalid memory file content: YAML frontmatter does not parse (%s); fix and retry."
              (ex-message e)))))

;; abs-path -> {:fingerprint [mtime-ms size-bytes] :parsed <base entry or nil>}.
;; Memory is scanned every prompt turn; parsing unchanged files each time is
;; wasted I/O and YAML work. The fingerprint re-parses on any edit (mtime or
;; size change covers coarse-mtime filesystems), and failed parses are cached
;; too so permanently broken files don't re-warn every turn. Unbounded in
;; theory but proportional to the user's memory store, which stays small.
(defonce ^:private entry-cache* (atom {}))

(defn ^:private cached-parse-memory-file
  "Fuses fingerprint lookup with parse-on-miss. Returns [parse-result mtime-ms]; the parse
   result is `{:ok <base entry>}` or `{:skip <reason>}` from `parse-memory-file` (never nil)."
  [file]
  (let [^File file (fs/file file)
        path (str (fs/absolutize file))
        current-fingerprint [(.lastModified file) (.length file)]
        {:keys [fingerprint parsed]} (get @entry-cache* path)]
    (if (= fingerprint current-fingerprint)
      [parsed (first fingerprint)]
      (let [parsed (parse-memory-file file)]
        (swap! entry-cache* assoc path {:fingerprint current-fingerprint :parsed parsed})
        [parsed (first current-fingerprint)]))))

(defn ^:private scan-dir
  "Parse results under one memory dir: `{:entries [...] :skipped [...]}`, entries
   newest mtime first, skipped as `{:path :tier :label :reason}` in glob order.
   nil when the dir doesn't exist."
  [{:keys [dir tier label]}]
  (let [dir-file (fs/file dir)]
    (when (fs/directory? dir-file)
      ;; follow-links: memory dirs are user-owned, symlinks inside them are
      ;; understood to be intentional (e.g. dotfiles symlinks).
      (->> (fs/glob dir-file "**" {:follow-links true})
           (filter (fn [f] (and (not (fs/directory? f))
                                (= "md" (fs/extension f)))))
           (reduce (fn [acc f]
                     (let [[result mtime-ms] (cached-parse-memory-file f)]
                       (cond
                         (:ok result)
                         (update acc :entries conj (assoc (:ok result)
                                                          :tier tier :label label
                                                          :mtime-ms mtime-ms
                                                          ;; `:updated-at-ms` is THE date everything
                                                          ;; downstream reads. Seeded from mtime here and
                                                          ;; overwritten by the usage ledger's body-hash
                                                          ;; stamp in `usage/enrich`, so consumers never
                                                          ;; choose between two timestamps. `:mtime-ms`
                                                          ;; survives only for `list-memories`, which
                                                          ;; promised it on the protocol.
                                                          :updated-at-ms mtime-ms))
                         (:skip result)
                         (update acc :skipped conj {:path (str (fs/absolutize (fs/file f)))
                                                    :tier tier :label label
                                                    :reason (:skip result)})
                         :else acc)))
                   {:entries [] :skipped []})
           ((fn [{:keys [entries skipped]}]
              {:entries (->> entries (sort-by :updated-at-ms >) vec)
               :skipped skipped}))))))

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
   degenerate to the recency term, which is monotonic in the timestamp — so
   with an empty ledger the resulting order is exactly plain descending
   recency.

   That timestamp is `:updated-at-ms`: when the knowledge last changed, which
   is the file's mtime until the usage ledger knows better. Without it, a
   /memory-consolidate pass that only normalizes tags would reshuffle every
   never-read memory to the top."
  [entry now-ms]
  (let [now-ms (long now-ms)
        age-days (fn [ts] (max 0.0 (/ (- now-ms (long ts)) 86400000.0)))
        reads (long (or (:reads entry) 0))
        last-read-at (:last-read-at entry)
        habit-term (if (and (pos? reads) last-read-at)
                     (* (Math/log (inc (double reads)))
                        (Math/exp (- (/ (age-days last-read-at) 30.0))))
                     0.0)
        recency-term (Math/exp (- (/ (age-days (long (or (:updated-at-ms entry) 0))) 30.0)))]
    (+ habit-term recency-term)))

(defn ^:private usage-enrich
  "Merge usage ledger stats (`:reads`, `:last-read-at`) into scan entries.
   Resolved at call time rather than required at the top of this namespace:
   `eca.features.memory.usage` requires this namespace, so a compile-time
   require would be a cyclic load dependency."
  [entries]
  ((requiring-resolve 'eca.features.memory.usage/enrich) entries))

(defn scan-memories
  "Vector of entry maps, one per valid `.md` file across `(memory-dirs db config)`.
   Grouped in memory-dirs order (per root: personal then shared, then global).
   Within each group entries are sorted by `:updated-at-ms` descending; when
   memory is enabled they are first enriched with usage ledger stats (`:reads`,
   `:last-read-at` — internal, stripped by `list-memories`) and sorted by the
   decay-weighted `usage-score` instead, which keeps the plain recency order
   while no usage data exists. Sorting never crosses group boundaries. Files
   missing `name`/`description` or failing to parse are skipped with a logged
   warning.

   Optional opts map: `:now-ms` pins the reference time for deterministic
   scoring in tests (defaults to the current time)."
  ([db config] (scan-memories db config {}))
  ([db config {:keys [now-ms] :or {now-ms (System/currentTimeMillis)}}]
   (let [now-ms (long now-ms)
         usage? (enabled? config)]
     (->> (memory-dirs db config)
          (mapcat (fn [dir]
                    (let [entries (:entries (scan-dir dir))]
                      (if usage?
                        (->> (usage-enrich entries)
                             (sort-by #(usage-score % now-ms) >))
                        entries))))
          vec))))

(defn skipped-files
  "Vector of `{:path :tier :label :reason}` for memory files found in
   `(memory-dirs db config)` that failed to read or parse (and are thus
   excluded from `scan-memories`). Used by /memory-consolidate so broken
   files are fixable instead of invisible."
  [db config]
  (->> (memory-dirs db config)
       (mapcat (fn [dir] (:skipped (scan-dir dir))))
       vec))

(defn list-memories
  "Public protocol shape for memory panels: vector of
   `{:path :tier (string) :name :description :type :tags :label :mtime-ms}`
   in memory-directory order, newest actual file mtime first within each
   directory. Usage enrichment is not needed for the public list."
  [db config]
  (mapv (fn [{:keys [path tier name description type tags label mtime-ms]}]
          {:path path
           :tier (clojure.core/name tier)
           :name name
           :description description
           :type type
           :tags tags
           :label label
           :mtime-ms mtime-ms})
        (mapcat (comp :entries scan-dir) (memory-dirs db config))))

