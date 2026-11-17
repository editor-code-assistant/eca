(ns eca.features.context
  (:require
   [babashka.fs :as fs]
   [clojure.core.memoize :as memoize]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.digest :as digest]
   [eca.features.index :as f.index]
   [eca.llm-api :as llm-api]
   [eca.logger :as logger]
   [eca.modules :as modules]
   [eca.shared :as shared :refer [assoc-some]])
  (:import
   [java.util Base64]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CONTEXT]")

(defn safe-canonicalize
  "Canonicalize `path`, falling back to a non-resolved `fs/path` if the
   path does not exist (e.g. a stale workspace folder or test fixture)."
  [path]
  (try (fs/canonicalize path)
       (catch Exception _ (fs/path path))))

(defn always-contexts
  "The contexts the active modules give every chat, first (`:contexts`'
   `:always`, built-in only), e.g. eca/agents-md's AGENTS.md files."
  [db config]
  (mapcat (fn [[entry {:keys [always]}]]
            (when always
              (always (modules/module-ctx {:db db :config config} entry))))
          (modules/contributions config :contexts)))

(defn ^:private module-context-type
  "The active module that gives context type `type-name` (`:contexts`'
   `:types`, built-in only): `[entry definition]`, or nil."
  [config type-name]
  (some (fn [[entry {:keys [types]}]]
          (when-let [definition (get types type-name)]
            [entry definition]))
        (modules/contributions config :contexts)))

(defn ^:private module-context-items
  "The `@` completion items of the context types modules give."
  [db config]
  (for [[entry {:keys [types]}] (modules/contributions config :contexts)
        [_type-name {list-items :list}] types
        :when list-items
        item (list-items (modules/module-ctx {:db db :config config} entry))]
    item))

(defn ^:private file->refined-context [path lines-range]
  (if (fs/readable? path)
    (if-let [media-type (shared/image-media-type path)]
      {:type :image
       :media-type media-type
       :base64 (.encodeToString (Base64/getEncoder)
                                (fs/read-all-bytes (fs/file path)))
       :path path}
      (when-let [content (llm-api/refine-file-context path lines-range)]
        (assoc-some
         {:type :file
          :path path
          :content content}
         :lines-range lines-range)))
    (logger/warn logger-tag "File not found or unreadable at" path)))

(defn raw-contexts->refined [contexts db]
  (mapcat (fn [{:keys [type path lines-range position media-type mediaType base64 label content] :as raw-context}]
            (case (name type)
              "file" (if-let [ctx (file->refined-context path lines-range)]
                       [ctx]
                       [])
              "directory" (->> (fs/glob path "**")
                               (remove fs/directory?)
                               (keep (fn [path]
                                       (let [filename (str (fs/canonicalize path))]
                                         (file->refined-context filename nil)))))
              "image" (let [mt (or media-type mediaType)]
                        (if (and mt base64)
                          [{:type :image
                            :media-type mt
                            :base64 base64}]
                          (do (logger/warn logger-tag "Image context missing mediaType or base64; ignoring")
                              [])))
              "text" (if (and label content)
                       [{:type :text
                         :label label
                         :content content}]
                       (do (logger/warn logger-tag "Text context missing label or content; ignoring")
                           []))
              "cursor" [{:type :cursor
                         :path path
                         :position position}]
              ;; a type a module gives (:contexts), e.g. MCP resources
              (let [config (config/all db)]
                (when-let [[entry {:keys [refine]}] (module-context-type config (name type))]
                  (when refine
                    (refine (modules/module-ctx {:db db :config config} entry) raw-context))))))
          contexts))

(defn parse-context-reference
  "Parse a path with an optional `:L<start>-L<end>` suffix into a raw context map."
  [reference]
  (when (seq reference)
    (let [[_ path start end] (re-matches #"(.+):L(\d+)-L(\d+)$" reference)
          path (or path reference)
          path (if (string/starts-with? path "~")
                 (str (fs/expand-home path))
                 path)]
      (cond-> {:type (if (fs/directory? path) "directory" "file")
               :path path}
        (and start end) (assoc :lines-range {:start (parse-long start)
                                             :end (parse-long end)})))))

(defn contexts-str-from-prompt
  "Extract all contexts (@something) and refine them.
   Parse lines if present in contexts like @/path/to/file:L1-L4"
  [prompt db]
  (let [;; Capture @<path> with optional :L<start>-L<end>
        context-pattern #"@([/~\.][^\s:]+(?::L\d+-L\d+)?)"
        matches (re-seq context-pattern prompt)
        raw-contexts (mapv (comp parse-context-reference second) matches)]
    (when (seq raw-contexts)
      (raw-contexts->refined raw-contexts db))))

(def ^:private attached-context-types
  "Context-area types delivered in the user message and tracked per chat:
   sent in full once, then only announced when they change. #683"
  #{:file})

(defn attached-context?
  "True when ctx is a context-area item tracked per chat (a file)."
  [ctx]
  (boolean (attached-context-types (:type ctx))))

(defn attached-context-key
  "Identity of an attached context in a chat: the canonical path plus its lines
   range for files, so the same file given as `~/x`, `./x` or `/abs/x` matches."
  [{:keys [type path lines-range]}]
  (case type
    :file (cond-> (str (safe-canonicalize path))
            lines-range (str ":L" (:start lines-range) "-L" (:end lines-range)))
    nil))

(defn attached-contexts-delta
  "Diffs the attached contexts among `contexts` against `sent`, the map of
   context key -> content digest already delivered in the chat.
   Returns `:new` contexts to send in full, `:changed` ones to announce and the
   updated `:sent` map. Removed contexts are dropped from `:sent`, so attaching
   one again sends it in full. Contexts whose key is in the `skip-keys` set
   (already expanded from the prompt text) are recorded as sent but not returned."
  [contexts sent skip-keys]
  (reduce
   (fn [acc ctx]
     (let [k (attached-context-key ctx)]
       (if (contains? (:sent acc) k)
         acc
         (let [content-digest (digest/sha-256-hex (str (:content ctx)))
               previous-digest (get sent k)
               acc (assoc-in acc [:sent k] content-digest)]
           (cond
             (contains? skip-keys k) acc
             (nil? previous-digest) (update acc :new conj ctx)
             (not= previous-digest content-digest) (update acc :changed conj ctx)
             :else acc)))))
   {:new [] :changed [] :sent {}}
   (filter attached-context? contexts)))

(defn ^:private match-sort-key
  "Sort key ranking basename matches first, then earlier and shorter matches."
  [lower-query path]
  (let [path-str (string/lower-case (str path))
        basename (string/lower-case (str (fs/file-name path)))]
    [(if (string/starts-with? basename lower-query) 0 1)
     (if (string/includes? basename lower-query) 0 1)
     (or (string/index-of path-str lower-query) Integer/MAX_VALUE)
     (count path-str)]))

(defn ^:private dirs-of-files*
  "Directories under `root-filename` derived from `files` paths, since the
   workspace file enumeration returns only files, which excludes directories."
  [root-filename files]
  (let [root-str (str root-filename)]
    (into []
          (comp
           (mapcat (fn [file]
                     (->> (iterate fs/parent (fs/parent file))
                          (take-while (fn [dir]
                                        (and dir
                                             (not= (str dir) root-str)
                                             (string/starts-with? (str dir) root-str))))
                          (map str))))
           (distinct))
          files)))

(def ^:private dirs-of-files
  "Content-cached `dirs-of-files*`, recomputed only when the enumerated
   workspace files change."
  (memoize/lu dirs-of-files* :lu/threshold 32))

(defn ^:private contexts-for [root-filename query config]
  (let [all-paths (f.index/allowed-files root-filename config)]
    (if (string/blank? query)
      all-paths
      (let [lower-query (string/lower-case query)
            matches? (fn [p] (string/includes? (string/lower-case (str p)) lower-query))
            matched-files (filterv matches? all-paths)
            matched-dirs (filterv matches? (dirs-of-files root-filename all-paths))]
        (->> (concat matched-dirs matched-files)
             ;; pre-compute the sort key once per path, sort-by would
             ;; recompute it on every comparison.
             (mapv (fn [path] [(match-sort-key lower-query path) path]))
             (sort-by first)
             (mapv peek))))))

(defn ^:private file->context [file-or-dir]
  (let [path (str (fs/canonicalize file-or-dir))]
    (if (fs/directory? file-or-dir)
      {:type "directory"
       :path path}
      {:type "file"
       :path path})))

(defn ^:private list-dir-entries
  "List the entries of `dir` (or of its parent when `dir` does not exist),
   directories first, alphabetically."
  [dir]
  (->> (try
         (if (fs/exists? dir)
           (fs/list-dir dir)
           (fs/list-dir (fs/parent dir)))
         (catch Exception _ nil))
       (sort-by (fn [p] [(if (fs/directory? p) 0 1)
                         (string/lower-case (str p))]))))

(defn ^:private workspace-dirs-to-list
  "Resolve `query` against each workspace root, returning the existing
   directories to list. Only queries containing a path separator opt into
   directory listing, other queries keep substring matching."
  [query roots]
  (when (string/includes? query "/")
    (into []
          (keep (fn [root]
                  (let [root-str (str root)
                        candidate (fs/normalize (fs/path root query))
                        parent (fs/parent candidate)]
                    (cond
                      (and (string/starts-with? (str candidate) root-str)
                           (fs/directory? candidate))
                      candidate

                      (and parent
                           (string/starts-with? (str parent) root-str)
                           (fs/directory? parent))
                      parent

                      :else nil))))
          roots)))

(defn all-contexts [query files-only? db* config]
  (let [query (or (some-> query string/trim) "")
        roots (mapv (comp shared/uri->filename :uri) (:workspace-folders @db*))
        first-project-path (first roots)
        absolute-path (or (when (string/starts-with? query "~")
                            (fs/expand-home (fs/file query)))
                          (when (string/starts-with? query "/")
                            (fs/file query))
                          (when (or (string/starts-with? query "./")
                                    (string/starts-with? query "../"))
                            (fs/file first-project-path query)))
        listed-dirs (when-not absolute-path
                      (workspace-dirs-to-list query roots))
        relative-files (when absolute-path
                         (mapv file->context (list-dir-entries absolute-path)))
        workspace-listed-files (when (seq listed-dirs)
                                 (->> listed-dirs
                                      (mapcat list-dir-entries)
                                      (distinct)
                                      (mapv file->context)))
        workspace-files (when (and (not absolute-path)
                                   (empty? listed-dirs))
                          (into []
                                (comp
                                 (mapcat #(contexts-for % query config))
                                 (take 100) ;; for performance, user can always make query specific for better results.
                                 (map file->context))
                                roots))]
    (if files-only?
      (concat relative-files
              workspace-listed-files
              workspace-files)
      (concat [{:type "cursor"}]
              (mapv (fn [root] {:type "directory" :path root}) roots)
              relative-files
              workspace-listed-files
              workspace-files
              (module-context-items @db* config)))))
