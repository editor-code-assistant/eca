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
   [eca.digest :as digest]
   [eca.logger :as logger]
   [eca.shared :as shared])
  (:import
   [java.io File StringReader]
   [org.yaml.snakeyaml LoaderOptions Yaml]
   [org.yaml.snakeyaml.error Mark]
   [org.yaml.snakeyaml.events CommentEvent]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[MEMORY]")
(def ^:private valid-write-modes #{"agent" "explicit"})

(defn enabled?
  "True when the memory subsystem is enabled (`memory.enabled`, default false)."
  [config]
  (true? (get-in config [:memory :enabled] false)))

(defn enabled-for-chat?
  "True when memory is enabled and `chat-id` is not a subagent chat. Subagents
   get no memory: no guidance, no memory blocks, no index notification."
  [db config chat-id]
  (and (enabled? config)
       (not (get-in db [:chats chat-id :subagent]))))

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

(defn abs-path
  "Canonical absolute path string of `path` (a string or a file). The one normalization
   of memory paths: memory dirs, scan entry `:path`, `memory-path?` and the
   usage ledger keys all use it, so they join on the same key."
  ^String [path]
  (shared/normalize-path path))

(defn memory-root-dir
  "Root directory of all personal memory: <XDG_DATA_HOME or ~/.local/share>/eca/memory.
   Memory is agent-written personal state, not user-curated configuration, so
   it belongs to the XDG data home; skills and rules stay in the config home."
  ^File []
  (let [xdg-data-home (or (config/get-env "XDG_DATA_HOME")
                           (io/file (config/user-home) ".local" "share"))]
    (io/file xdg-data-home "eca" "memory")))

(defn global-dir
  "Global memory directory (personal, all projects): <memory-root>/global."
  ^File []
  (io/file (memory-root-dir) "global"))

(def ^:private max-slug-readable-chars 64)

(defn ^:private short-path-hash
  "First 8 hex chars of the SHA-256 of `s`: collision-safe, stable across runs."
  ^String [^String s]
  (subs (digest/sha-256-hex s) 0 8))

(defn ^:private project-identity [path]
  (-> path abs-path cache/canonicalize-workspace-path abs-path))

(defn project-slug
  "Stable slug of the real workspace path, with linked worktrees sharing
   their main checkout's identity. A long path keeps its end, because the end
   names the project."
  [path]
  (let [canonical (project-identity path)
        dashed (string/replace canonical #"[^A-Za-z0-9]+" "-")
        tail (subs dashed (max 0 (- (count dashed) max-slug-readable-chars)))
        readable (string/replace tail #"^-+|-+$" "")]
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

(def ^:private global-label "global")

(defn ^:private root-basename
  "Human-readable basename of a canonical workspace root."
  [canonical-root]
  (or (some-> (fs/file-name (fs/file canonical-root)) str not-empty)
      canonical-root))

(defn ^:private unique-labels
  "`personal` dir entries (each with `::canonical`, its canonical root) with
   unique labels: a basename that two roots share, or that equals the global
   label, gets the short hash of its canonical root (the same suffix as its
   dir name), for example `app (3e80ad28)`. The label is the heading of the
   group in the memory blocks, so two groups with one label would merge."
  [personal]
  (let [counts (frequencies (map :label personal))]
    (mapv (fn [{:keys [label] :as entry}]
            (cond-> (dissoc entry ::canonical)
              (or (> (long (counts label)) 1) (= global-label label))
              (assoc :label (str label " (" (short-path-hash (::canonical entry)) ")"))))
          personal)))

(defn memory-dirs
  "Ordered vector of this chat's memory directories: per workspace root its
   personal dir, then the global dir. Each entry is
   `{:dir <abs path string> :tier :personal|:global :label <string>}`.
   Labels are unique (see `unique-labels`). Roots that share one personal dir
   (a main worktree and its linked worktrees) give one entry, so no note is
   scanned or listed twice. There is no team tier on disk: team knowledge
   belongs in AGENTS.md or .eca/rules, which are versioned and reviewed."
  [db]
  (conj (unique-labels
         (into []
               (comp (map (fn [root]
                            (let [canonical (project-identity root)]
                              {:dir (abs-path (personal-dir root))
                               :tier :personal
                               :label (root-basename canonical)
                               ::canonical canonical})))
                     (distinct))
               (workspace-paths db)))
        {:dir (abs-path (global-dir))
         :tier :global
         :label global-label}))

(defn memory-path?
  "True when memory is enabled and `path` is inside one of the chat's memory
   dirs. The one place that answers this question for trust, validation, the
   usage ledger and the file-change tag. `path` is normalized with `abs-path`
   first, so ledger keys join with scan entries."
  [db config path]
  (boolean
   (and path
        (enabled? config)
        (let [path (abs-path path)]
          (some #(shared/path-inside-root? path (:dir %)) (memory-dirs db))))))

(defn pre-create-dirs!
  "Idempotently creates every dir of `memory-dirs`: each workspace's personal
   dir and the global dir. Never throws; per-dir failures are logged."
  [db]
  (try
    (doseq [dir (map :dir (memory-dirs db))]
      (try
        (fs/create-dirs dir)
        (catch Throwable e
          (logger/warn logger-tag "Could not pre-create memory dir" (str dir) e))))
    (catch Throwable e
      (logger/warn logger-tag "Could not pre-create memory dirs" e))))

(def ^:private max-tags
  "Upper bound on tags kept per memory. A tag exists to group this memory with
   others: up to three topics plus a ticket id is enough, and more tags assert
   groupings that almost never all exist. Read-path only: files keep whatever
   they wrote, /memory-consolidate is what trims them."
  4)

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

(defn ^:private parsed-content-error
  "Error string for already-parsed memory frontmatter that is about to become
   an entry, or nil when it is valid: `name` and `description` must be
   non-blank after trim. `type`/`tags` presence and format are intentionally
   not validated here (only a write adds the checks of `memory-content-error`),
   and the body may be anything, including empty."
  [{:keys [name description]}]
  (cond
    (string/blank? (some-> name str string/trim))
    "Invalid memory file content: YAML frontmatter is missing required key `name` (memory files need non-blank name and description); fix and retry."

    (string/blank? (some-> description str string/trim))
    "Invalid memory file content: YAML frontmatter is missing required key `description` (memory files need non-blank name and description); fix and retry."))

(defn ^:private parse-error-message
  "The one wording for an unparseable YAML frontmatter, shared by the write
   validation and the scan so both describe it identically."
  [e]
  (format "Invalid memory file content: YAML frontmatter does not parse (%s); fix and retry."
          (ex-message e)))

(defn ^:private lossy-comments
  "The YAML comments in `yaml-str` that cut text off a value, in file order,
   each as `{:line <0-based line> :text <that line> :lost <the comment text>}`.
   YAML starts a comment at a `#` that begins a value or follows a space, and
   drops it without an error (`description: see #679` reads as `see`). A
   comment loses text when other text comes before it on its line. Two cases
   lose nothing: a comment on its own line, and a comment right after a
   top-level key whose value follows on the next lines (`tags: # topics`, then
   a list); `parsed` (the parsed frontmatter) tells the second case apart from
   `tags: #679`, which leaves the key without a value. Fails open: nil when the
   comment scan itself fails."
  [^String yaml-str parsed]
  (try
    (let [lines (string/split-lines yaml-str)
          yaml (Yaml. (doto (LoaderOptions.) (.setProcessComments true)))
          value-follows? (fn [^String line ^String before]
                           (and (string/ends-with? before ":")
                                (= line (string/triml line))
                                (some? (get parsed (keyword (subs before 0 (dec (count before))))))))]
      (into []
            (keep (fn [event]
                    (when (instance? CommentEvent event)
                      (let [^Mark mark (.getStartMark ^CommentEvent event)
                            ^String line (nth lines (.getLine mark) "")
                            ;; Mark columns count code points, not chars.
                            idx (.offsetByCodePoints line 0 (min (.getColumn mark)
                                                                 (.codePointCount line 0 (count line))))
                            before (string/trimr (subs line 0 idx))]
                        (when-not (or (string/blank? before)
                                      (value-follows? line before))
                          {:line (.getLine mark)
                           :text (string/trim line)
                           :lost (string/trim (subs line idx))})))))
            (.parse yaml (StringReader. yaml-str))))
    (catch Exception _ nil)))

(defn ^:private lossy-comments-message
  "One message that names every line of `comments` (see `lossy-comments`)."
  [comments]
  (format (str "Invalid memory file content: in the frontmatter of a memory file, a `#` that starts a value"
               " or follows a space starts a YAML comment, so %s."
               " Put the whole value in single quotes (for example `tags: 'auth, #679'`; write an apostrophe"
               " inside as `''`), or move a real comment to its own line, and retry. This applies only to the"
               " frontmatter between the `---` lines of memory files; `#` in the note body and in other files is fine.")
          (string/join ", and "
                       (map (fn [{:keys [line text lost]}]
                              ;; Line 1 of the file is the opening `---`.
                              (format "line %d (`%s`) would lose `%s`" (+ 2 line) text lost))
                            comments))))

(defn ^:private non-text-field-message
  "Error for a `name` or `description` that YAML did not read as text. The scan
   would show its `str` form, which differs from what the file says
   (`name: 2026-11-12` becomes a date in the local time zone, `1.10` becomes
   `1.1`)."
  [field]
  (format (str "Invalid memory file content: `%s` has a value that YAML does not read as text (a date, a number,"
               " or a word such as `yes`), so the index would show different text. Put the value in single"
               " quotes, for example `%s: '2026-11-12'`, and retry.")
          field field))

(defn ^:private non-text-tags?
  "True when `tags` holds a value that YAML did not read as text: a number
   (`679`, and `0123` even turns into 83), a boolean (`yes`) or a map. Such a
   tag would be dropped, and its original text cannot be recovered. Empty list
   items lose nothing and pass."
  [tags]
  (cond
    (or (nil? tags) (string? tags)) false
    (sequential? tags) (boolean (some #(not (or (nil? %) (string? %))) tags))
    :else true))

(def ^:private non-text-tags-message
  (str "Invalid memory file content: `tags` has a value that YAML does not read as text (a number such as"
       " `679`, or a word such as `yes`), so the tag would be lost. Put the whole tags value in single quotes,"
       " for example `tags: 'auth, 679'`, and retry."))

(defn ^:private parse-memory-file
  "Parse one memory markdown file. Returns `{:ok <base entry>}` (everything
   except tier/label/mtime) on success or `{:skip <human reason>}` on any
   read/parse/validation failure. Failures are logged once per file
   fingerprint (the file cache suppresses re-warnings for unchanged files).
   The skip reason reuses `memory-content-error` wording so the index,
   consolidate snapshot and logs always describe identically. The YAML is
   parsed once: `memory-content-error` parses the content itself, so calling
   it here would parse a second time."
  [file]
  (let [result
        (try
          (let [;; Read outside the inner `try`: a read error is not a YAML error.
                content (slurp (str file))
                parsed (try
                         (shared/parse-md content)
                         (catch Exception e
                           {::parse-error (parse-error-message e)}))]
            (if-let [error (or (::parse-error parsed)
                               (parsed-content-error parsed))]
              {:skip error}
              (let [{:keys [name description type tags body]} parsed
                    type (some-> type str string/trim)]
                {:ok {:path (abs-path file)
                      :name (some-> name str string/trim)
                      :description (some-> description str string/trim)
                      :type (if (string/blank? type) "note" type)
                      :tags (normalize-tags tags)
                      :body (or body "")}})))
          (catch Throwable e
            {:skip (str "cannot read file: "
                        (first (string/split (str (ex-message e)) #"\n")))}))]
    (when-let [reason (:skip result)]
      (logger/warn logger-tag "Skipping memory file" reason (str file)))
    result))

(defn note-hash
  "Hash of the knowledge of one parsed note (a scan entry or a base entry of
   `parse-memory-file`): name, type, description and body. Tags and file
   times are excluded, so a retag (or a file touch) is not a change."
  [{:keys [name type description body]}]
  (digest/sha-256-hex (pr-str [name type description body])))

(defn file-note-hash
  "`note-hash` of the memory file at `path` as it is on disk now, or nil when
   no valid note is there (a missing file, a directory, or a file the scan
   would skip). Parses the file directly, not through the scan cache, because
   it runs right after a write, which a coarse mtime may not show.
   `parse-memory-file` never throws."
  [path]
  (when (fs/regular-file? (fs/file path))
    (some-> (:ok (parse-memory-file path)) note-hash)))

(defn memory-content-error
  "Error string explaining why `content` must NOT be written as a memory file,
   or nil when it may. Used before a write to a memory dir. Rejects content
   that `parse-memory-file` would skip: the YAML frontmatter must parse (as a
   mapping) and give non-blank `name` and `description` after trim. Also
   rejects frontmatter that would silently lose or change text: a YAML comment
   after text on its line (`see #679`), and a `name`, `description` or tag that
   YAML does not read as text. All problems come back in one message, one per
   line, so the model can fix them in one write. A comment can also cut `name`
   or `description` to nothing, so the missing-key error is left out when a
   comment error explains it. The scan stays lenient: it never applies the
   extra rules. Returns an actionable message that guides the model to fix the
   frontmatter."
  [content]
  (try
    (let [parsed (shared/parse-md content)
          comments (some-> (shared/frontmatter-yaml content) (lossy-comments parsed) seq)
          errors (concat [(if comments
                            (lossy-comments-message comments)
                            (parsed-content-error parsed))]
                         (for [field [:name :description]
                               :let [value (get parsed field)]
                               :when (and (some? value) (not (string? value)))]
                           (non-text-field-message (name field)))
                         [(when (non-text-tags? (:tags parsed)) non-text-tags-message)])]
      (some->> (seq (remove nil? errors)) (string/join "\n")))
    (catch Exception e
      (parse-error-message e))))

;; abs-path -> {:fingerprint [mtime-ms size-bytes] :parsed <base entry or nil>}.
;; Memory is scanned every prompt turn; parsing unchanged files each time is
;; wasted I/O and YAML work. The fingerprint re-parses on any edit (mtime or
;; size change covers coarse-mtime filesystems), and failed parses are cached
;; too so permanently broken files don't re-warn every turn. Unbounded in
;; theory but proportional to the user's memory store, which stays small.
(defonce ^:private entry-cache* (atom {}))

(defn ^:private cached-parse-memory-file
  "Fuses fingerprint lookup with parse-on-miss. `path` is `(abs-path file)`.
   Returns [parse-result mtime-ms]; the parse result is `{:ok <base entry>}` or
   `{:skip <reason>}` from `parse-memory-file` (never nil)."
  [file path]
  (let [^File file (fs/file file)
        mtime-ms (.lastModified file)
        current-fingerprint [mtime-ms (.length file)]
        {:keys [fingerprint parsed]} (get @entry-cache* path)]
    [(if (= fingerprint current-fingerprint)
       parsed
       (let [parsed (parse-memory-file file)]
         (swap! entry-cache* assoc path {:fingerprint current-fingerprint :parsed parsed})
         parsed))
     mtime-ms]))

(defn ^:private outside-link-reason
  "Skip reason for a file in a memory dir whose resolved path (`target`) is
   outside the memory dirs of the chat."
  [target]
  (format (str "not indexed: this path is a link to %s, which is outside the memory directories."
               " Move the note here, or link the whole memory directory instead.")
          target))

(defn ^:private scan-dir
  "Parse results under one memory dir: `{:entries [...] :skipped [...]}`, entries
   newest mtime first, skipped as `{:path :tier :label :reason}` in glob order.
   nil when the dir doesn't exist. `roots` are all memory dirs of the chat. A
   file whose resolved path is outside them (a link to somewhere else) is
   skipped: `memory-path?` is false for it, so reads, writes and
   /memory-consolidate would not treat it as a note."
  [{:keys [dir tier label]} roots]
  (let [dir-file (fs/file dir)]
    (when (fs/directory? dir-file)
      (let [;; follow-links: links inside and between the memory dirs are
            ;; aliases of one note and are listed once (see `distinct-paths`).
            files (filter (fn [f] (and (not (fs/directory? f))
                                       (= "md" (some-> (fs/extension f) string/lower-case))))
                          (fs/glob dir-file "**" {:follow-links true}))
            skip (fn [acc path reason]
                   (update acc :skipped conj {:path path :tier tier :label label :reason reason}))
            {:keys [entries skipped]}
            (reduce (fn [acc f]
                      (let [path (abs-path f)]
                        (if-not (some #(fs/starts-with? path %) roots)
                          ;; The link itself is what the user can fix.
                          (skip acc (str f) (outside-link-reason path))
                          (let [[result mtime-ms] (cached-parse-memory-file f path)]
                            (if-let [entry (:ok result)]
                              (update acc :entries conj (assoc entry
                                                               :tier tier :label label
                                                               :mtime-ms mtime-ms
                                                               ;; `:updated-at-ms` is THE date everything
                                                               ;; downstream reads. Seeded from mtime here and
                                                               ;; overwritten by the usage ledger's body-hash
                                                               ;; stamp in `usage/enrich`, so consumers never
                                                               ;; choose between two timestamps. `:mtime-ms`
                                                               ;; keeps the file time: `list-memories` returns
                                                               ;; it on the protocol, and the ledger seeds a
                                                               ;; first `:changed-at` from it.
                                                               :updated-at-ms mtime-ms))
                              (skip acc path (:skip result)))))))
                    {:entries [] :skipped []}
                    files)]
        {:entries (vec (sort-by :updated-at-ms > entries))
         :skipped skipped}))))

(defn ^:private scan-dirs
  "`scan-dir` of every memory dir of the chat, in `memory-dirs` order."
  [db]
  (let [dirs (memory-dirs db)
        roots (mapv :dir dirs)]
    (keep #(scan-dir % roots) dirs)))

(defn ^:private distinct-paths
  "Keep the first entry for each canonical path, including across groups."
  []
  (let [seen (volatile! #{})]
    (filter (fn [{:keys [path]}]
              (when-not (contains? @seen path)
                (vswap! seen conj path)
                true)))))

(defn scan-memories
  "Vector of entry maps, one per valid `.md` file across `(memory-dirs db)`,
   in memory-dirs order (per root: personal, then global) and newest file
   first inside each dir. A note reachable through several paths is listed
   once. This is a plain read of the store: the usage ranking that the prompt
   index needs is `eca.features.memory.usage/ranked-memories`.
   Files missing `name`/`description` or failing to parse are skipped with a
   logged warning."
  [db]
  (into [] (comp (mapcat :entries) (distinct-paths)) (scan-dirs db)))

(defn skipped-files
  "Vector of `{:path :tier :label :reason}` for memory files found in
   `(memory-dirs db)` that are excluded from `scan-memories`: they failed to
   read or parse, or they are links to a file outside the memory dirs. Used by
   /memory-consolidate so broken files are fixable instead of invisible."
  [db]
  (into [] (comp (mapcat :skipped) (distinct-paths)) (scan-dirs db)))

(defn list-memories
  "Public protocol shape for memory panels: vector of
   `{:path :tier (string) :name :description :type :tags :label :mtime-ms}`
   in memory-directory order, newest actual file mtime first within each
   directory. Usage enrichment is not needed for the public list."
  [db]
  (mapv (fn [{:keys [path tier name description type tags label mtime-ms]}]
          {:path path
           :tier (clojure.core/name tier)
           :name name
           :description description
           :type type
           :tags tags
           :label label
           :mtime-ms mtime-ms})
        (scan-memories db)))
