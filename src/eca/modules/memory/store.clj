(ns eca.modules.memory.store
  "Memory storage layout, path resolution, frontmatter validation, and scanning
   across per-project and global Markdown note directories."
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
   [java.io File]
   [java.nio.file Path]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[MEMORY]")
(def ^:private valid-write-modes #{"agent" "explicit"})

(defn write-mode
  "Effective write mode (`\"agent\"` default, `\"explicit\"` fallback on invalid values)."
  [config]
  (let [mode (get-in config [:memory :writeMode] "agent")]
    (if (contains? valid-write-modes mode) mode "explicit")))

(defn index-max-entries
  "Max entries in the memory index (`memory.index.maxEntries`, default 100)."
  [config]
  (get-in config [:memory :index :maxEntries] 100))

(defn index-max-tokens
  "Token budget for the memory index (`memory.index.maxTokens`, default 2000)."
  [config]
  (get-in config [:memory :index :maxTokens] 2000))

(defn abs-path
  "Canonical absolute path string shared as the join key across scan, locate, and usage."
  ^String [path]
  (shared/normalize-path path))

(defn memory-root-dir
  "Root directory for personal memory: `<XDG_DATA_HOME or ~/.local/share>/eca/memory`."
  ^File []
  (let [xdg-data-home (or (not-empty (config/get-env "XDG_DATA_HOME"))
                          (io/file (config/user-home) ".local" "share"))]
    (io/file xdg-data-home "eca" "memory")))

(defn global-dir
  "Global memory directory: `<memory-root>/global`."
  ^File []
  (io/file (memory-root-dir) "global"))

(def ^:private max-slug-readable-chars 64)

(defn ^:private short-path-hash ^String [^String s]
  (subs (digest/sha-256-hex s) 0 8))

(defn ^:private project-identity [path]
  (-> path abs-path cache/canonicalize-workspace-path abs-path))

(defn project-slug
  "Stable `<tail>-<8-char-hash>` slug for a workspace path (worktrees share one slug)."
  [path]
  (let [canonical (project-identity path)
        dashed (string/replace canonical #"[^A-Za-z0-9]+" "-")
        tail (subs dashed (max 0 (- (count dashed) max-slug-readable-chars)))
        readable (string/replace tail #"^-+|-+$" "")]
    (if (seq readable)
      (format "%s-%s" readable (short-path-hash canonical))
      (short-path-hash canonical))))

(defn personal-dir
  "Per-project memory directory: `<memory-root>/projects/<project-slug>`."
  ^File [workspace-path]
  (io/file (memory-root-dir) "projects" (project-slug workspace-path)))

(defn workspace-paths [db]
  (->> (:workspace-folders db)
       (keep #(some-> (:uri %) shared/uri->filename))
       (mapv str)))

(def ^:private global-label "global")

(defn ^:private root-basename [canonical-root]
  (or (some-> (fs/file-name (fs/file canonical-root)) str not-empty)
      canonical-root))

(defn ^:private unique-labels
  "Disambiguates duplicate root basenames (or one named `global`) with their short hash."
  [personal]
  (let [counts (frequencies (map :label personal))]
    (mapv (fn [{:keys [label] :as entry}]
            (cond-> (dissoc entry ::canonical)
              (or (> (long (counts label)) 1) (= global-label label))
              (assoc :label (str label " (" (short-path-hash (::canonical entry)) ")"))))
          personal)))

(defn memory-dirs
  "Ordered vector of `{:dir :tier :label}` for this chat: deduplicated per-project
   personal dirs (worktrees share one), then the global dir."
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

(defn markdown-file?
  "True when `path` has a `.md` extension (case-insensitive)."
  [path]
  (= "md" (some-> (fs/extension (fs/file path)) string/lower-case)))

(defn hidden-note-path?
  "True when `local-path` or any ancestor below `dir` is hidden."
  [dir local-path]
  (let [root (fs/path dir)]
    (loop [p (fs/path local-path)]
      (when (and p (not= p root))
        (or (try (fs/hidden? p) (catch java.nio.file.NoSuchFileException _ false))
            (recur (fs/parent p)))))))

(defn ^:private resolve-parent-and-leaf
  "Resolves symlinks through the parent directory (matching POSIX `symlink/..`)
   without following a symlink on the leaf itself."
  [^Path p]
  (let [raw-leaf (some-> (fs/file-name p) str)]
    (cond
      (nil? raw-leaf) nil
      (or (= "." raw-leaf) (= ".." raw-leaf))
      (let [resolved (fs/path (abs-path p))]
        (when-let [parent (fs/parent resolved)]
          [(str parent) (str (fs/file-name resolved))]))
      :else
      (when-let [parent (fs/parent p)]
        [(abs-path parent) raw-leaf]))))

(defn locate-memory-path
  "Returns `{:dir :path :link?}` when `path` resolves inside one of the chat's
   memory dirs (resolving parent symlinks, not leaf symlinks), or nil."
  [db path]
  (when path
    (let [dirs (mapv :dir (memory-dirs db))
          ^Path p (fs/absolutize (fs/file path))]
      (when-let [[real-parent leaf] (resolve-parent-and-leaf p)]
        (when-let [dir (some #(when (shared/path-inside-root? real-parent %) %) dirs)]
          (let [local (fs/path real-parent leaf)]
            {:dir dir
             :path (str local)
             :link? (boolean (fs/sym-link? local))}))))))

(defn indexable-memory-path
  "Returns the canonical path key for a visible, non-link `.md` note in a
   memory dir, or nil. Short-circuits on non-`.md` paths."
  [db path]
  (when (and path (markdown-file? path))
    (when-let [{:keys [dir path link?]} (locate-memory-path db path)]
      (when (and (not link?)
                 (not (hidden-note-path? dir path)))
        path))))

(defn create-dirs!
  "Idempotently creates `dirs`, logging per-dir failures without throwing."
  [dirs]
  (doseq [dir dirs]
    (try
      (fs/create-dirs dir)
      (catch Throwable e
        (logger/warn logger-tag "Could not create memory dir" (str dir) e)))))

(defn pre-create-dirs!
  "Idempotently creates all memory dirs for `db`. Never throws."
  [db]
  (try
    (create-dirs! (map :dir (memory-dirs db)))
    (catch Throwable e
      (logger/warn logger-tag "Could not pre-create memory dirs" e))))

(defn trash-dirs
  "Map of `{<memory-dir> <trash-dir>}` under `<memory-root>/trash`."
  [db]
  (let [trash (io/file (memory-root-dir) "trash")]
    (into {(abs-path (global-dir)) (abs-path (io/file trash "global"))}
          (map (fn [root]
                 [(abs-path (personal-dir root))
                  (abs-path (io/file trash "projects" (project-slug root)))]))
          (workspace-paths db))))

(defn trash-path?
  "True when `path` lies inside any trash dir of `db`."
  [db path]
  (boolean
   (and path
        (let [path (abs-path path)]
          (some #(shared/path-inside-root? path %) (vals (trash-dirs db)))))))

(def ^:private max-tags 4)

(defn ^:private normalize-tags
  "Normalizes a YAML list or comma-separated string to at most `max-tags`
   distinct lowercase tags."
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

(defn ^:private parsed-content-error [{:keys [name description]}]
  (cond
    (string/blank? (some-> name str string/trim))
    "Invalid memory file content: YAML frontmatter is missing required key `name` (memory files need non-blank name and description); fix and retry."

    (string/blank? (some-> description str string/trim))
    "Invalid memory file content: YAML frontmatter is missing required key `description` (memory files need non-blank name and description); fix and retry."))

(defn ^:private parse-error-message [e]
  (format "Invalid memory file content: YAML frontmatter does not parse (%s); fix and retry."
          (ex-message e)))

(defn ^:private non-text-field-message [field]
  (format (str "Invalid memory file content: `%s` has a value that YAML does not read as text (a date, a number,"
               " or a word such as `yes`), so the index would show different text. Put the value in single"
               " quotes, for example `%s: '2026-11-12'`, and retry.")
          field field))

(defn ^:private non-text-tags? [tags]
  (cond
    (or (nil? tags) (string? tags)) false
    (sequential? tags) (boolean (some #(not (or (nil? %) (string? %))) tags))
    :else true))

(def ^:private non-text-tags-message
  (str "Invalid memory file content: `tags` has a value that YAML does not read as text (a number such as"
       " `679`, or a word such as `yes`), so the tag would be lost. Put the whole tags value in single quotes,"
       " for example `tags: 'auth, 679'`, and retry."))

(defn ^:private base-entry [path {:keys [name description type tags body]}]
  (let [type (some-> type str string/trim)]
    {:path path
     :name (some-> name str string/trim)
     :description (some-> description str string/trim)
     :type (if (string/blank? type) "note" type)
     :tags (normalize-tags tags)
     :body (or body "")}))

(defn ^:private parse-memory-file
  "Parses one memory file into `{:ok <entry>}` or `{:skip <reason>}` (logged)."
  [file]
  (let [result
        (try
          ;; Read outside the inner try so I/O errors are not reported as YAML errors.
          (let [content (slurp (str file))
                parsed (try
                         (shared/parse-md content)
                         (catch Exception e
                           {::parse-error (parse-error-message e)}))]
            (if-let [error (or (::parse-error parsed)
                               (parsed-content-error parsed))]
              {:skip error}
              {:ok (base-entry (abs-path file) parsed)}))
          (catch Throwable e
            {:skip (str "cannot read file: "
                        (first (string/split (str (ex-message e)) #"\n")))}))]
    (when-let [reason (:skip result)]
      (logger/warn logger-tag "Skipping memory file" reason (str file)))
    result))

(defn note-hash
  "Content hash of a note's name, type, description, and body (excluding tags and mtime)."
  [{:keys [name type description body]}]
  (digest/sha-256-hex (pr-str [name type description body])))

(defn note-content-error
  "Validates note frontmatter (YAML syntax, non-blank `name`/`description`, and
   string-typed scalars/tags). Returns an error string or nil."
  [content]
  (try
    (let [parsed (shared/parse-md content)
          errors (concat [(parsed-content-error parsed)]
                         (for [field [:name :description]
                               :let [value (get parsed field)]
                               :when (and (some? value) (not (string? value)))]
                           (non-text-field-message (name field)))
                         [(when (non-text-tags? (:tags parsed)) non-text-tags-message)])]
      (some->> (seq (remove nil? errors)) (string/join "\n")))
    (catch Exception e
      (parse-error-message e))))

(def ^:private link-reason
  (str "not indexed: this is a link. ECA reads only regular files inside the memory directories;"
       " to keep memory somewhere else, link the whole memory directory instead."))

(defn ^:private scan-dir
  "Scans `.md` files under one memory dir (skipping symlinks and non-regular
   files), returning `{:entries :skipped}` sorted newest-first, or nil."
  [{:keys [dir tier label]}]
  (let [dir-file (fs/file dir)]
    (when (fs/directory? dir-file)
      (let [files (filter #(and (markdown-file? %)
                                (or (fs/sym-link? %)
                                    (not (fs/directory? %))))
                          (fs/glob dir-file "**"))
            skip (fn [acc path reason]
                   (update acc :skipped conj {:path path :tier tier :label label :reason reason}))
            {:keys [entries skipped]}
            (reduce (fn [acc f]
                      (cond
                        (fs/sym-link? f)
                        (skip acc (str f) link-reason)

                        (not (fs/regular-file? f))
                        (skip acc (str f) "not indexed: not a regular file.")

                        :else
                        (let [result (parse-memory-file f)
                              mtime-ms (.lastModified ^File (fs/file f))]
                          (if-let [entry (:ok result)]
                            (update acc :entries conj (assoc entry
                                                             :tier tier :label label
                                                             :mtime-ms mtime-ms
                                                             ;; Seeded from mtime; refined from body-hash in `usage/enrich`.
                                                             :updated-at-ms mtime-ms))
                            (skip acc (abs-path f) (:skip result))))))
                    {:entries [] :skipped []}
                    files)]
        {:entries (vec (sort-by :updated-at-ms > entries))
         :skipped skipped}))))

(defn scan-memories
  "Scans all memory dirs for `db`, returning `{:entries :skipped}` in dir order."
  [db]
  (reduce (partial merge-with into)
          {:entries [] :skipped []}
          (keep scan-dir (memory-dirs db))))
