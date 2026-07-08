(ns eca.features.context
  (:require
   [babashka.fs :as fs]
   [clojure.core.memoize :as memoize]
   [clojure.string :as string]
   [eca.features.index :as f.index]
   [eca.features.tools.mcp :as f.mcp]
   [eca.llm-api :as llm-api]
   [eca.logger :as logger]
   [eca.shared :as shared :refer [assoc-some]])
  (:import
   [java.util Base64]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CONTEXT]")

(defn ^:private extract-at-mentions
  "Extract all @path mentions from content. Supports relative and absolute paths with any extension."
  [content]
  (let [pattern #"@([^\s\)]+\.\w+)"
        matches (re-seq pattern content)]
    (map second matches)))

(defn ^:private parse-agents-file
  ([path] (parse-agents-file path #{}))
  ([path visited]
   (if (contains? visited path)
     []
     (if-let [root-content (llm-api/refine-file-context path nil)]
       (let [visited' (conj visited path)
             at-mentions (extract-at-mentions root-content)
             parent-dir (str (fs/parent path))
             resolved-paths (map (fn [mention]
                                   (cond
                                     ;; Absolute path
                                     (string/starts-with? mention "/")
                                     (str (fs/canonicalize (fs/file mention)))

                                     ;; Relative path (./... or ../...)
                                     (or (string/starts-with? mention "./")
                                         (string/starts-with? mention "../"))
                                     (str (fs/canonicalize (fs/file parent-dir mention)))

                                     ;; Simple filename, relative to current file's directory
                                     :else
                                     (str (fs/canonicalize (fs/file parent-dir mention)))))
                                 at-mentions)
             ;; Deduplicate resolved paths
             unique-paths (distinct resolved-paths)
             ;; Recursively parse all mentioned files
             nested-results (mapcat #(parse-agents-file % visited') unique-paths)]
         (concat [{:type :agents-file
                   :path path
                   :content root-content}]
                 nested-results))
       []))))

(defn ^:private ancestor-paths
  "Return all ancestor directories of `path` in outermost-first order.
   Excludes `path` itself."
  [path]
  (loop [p (fs/parent path)
         acc '()]
    (if (nil? p)
      (vec acc)
      (recur (fs/parent p) (conj acc p)))))

(defn ^:private safe-canonicalize
  "Canonicalize `path`, falling back to a non-resolved `fs/path` if the
   path does not exist (e.g. a stale workspace folder or test fixture)."
  [path]
  (try (fs/canonicalize path)
       (catch Exception _ (fs/path path))))

(defn agents-file-contexts
  "Search for AGENTS.md file both in workspaceRoot and global config dir.
   When `:includeParentAgentsFiles` is true in `config`, also include
   AGENTS.md files from each workspace's parent directories, ordered
   outermost parent first, then the workspace's own AGENTS.md.
   Process any found @paths mentions recursively, supporting both relative and absolute paths.
   Deduplicates files to avoid reading the same file multiple times."
  [db config]
  ;; TODO make it customizable by agent
  (let [agent-file-name "AGENTS.md"
        include-parents? (boolean (:includeParentAgentsFiles config))
        readable-agent-file-in (fn [dir]
                                 (let [p (fs/path dir agent-file-name)]
                                   (when (fs/readable? p)
                                     (str (fs/canonicalize p)))))
        {:keys [paths seen]}
        (reduce
         (fn [{:keys [paths seen]} {:keys [uri]}]
           (let [ws-path (safe-canonicalize (shared/uri->filename uri))
                 candidate-dirs (cond-> []
                                  include-parents? (into (ancestor-paths ws-path))
                                  true (conj ws-path))
                 new-paths (->> candidate-dirs
                                (keep readable-agent-file-in)
                                (remove seen))]
             {:paths (into paths new-paths)
              :seen (into seen new-paths)}))
         {:paths [] :seen #{}}
         (:workspace-folders db))
        global-agent-file (readable-agent-file-in (shared/global-config-dir))
        all-paths (cond-> paths
                    (and global-agent-file (not (contains? seen global-agent-file)))
                    (conj global-agent-file))]
    (mapcat parse-agents-file all-paths)))

(defn ^:private file->refined-context [path lines-range]
  (if (fs/readable? path)
    (let [ext (string/lower-case (or (fs/extension path) ""))]
      (if (contains? #{"png" "jpg" "jpeg" "gif" "webp"} ext)
        {:type :image
         :media-type (case ext
                       "jpg" "image/jpeg"
                       (str "image/" ext))
         :base64 (.encodeToString (Base64/getEncoder)
                                  (fs/read-all-bytes (fs/file path)))
         :path path}
        (when-let [content (llm-api/refine-file-context path lines-range)]
          (assoc-some
           {:type :file
            :path path
            :content content}
           :lines-range lines-range))))
    (logger/warn logger-tag "File not found or unreadable at" path)))

(defn raw-contexts->refined [contexts db]
  (mapcat (fn [{:keys [type path lines-range position uri media-type mediaType base64]}]
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
              "repoMap" [{:type :repoMap}]
              "cursor" [{:type :cursor
                         :path path
                         :position position}]
              "mcpResource" (try
                              (mapv
                               (fn [{:keys [text]}]
                                 {:type :mcpResource
                                  :uri uri
                                  :content text})
                               (:contents (f.mcp/get-resource! uri db)))
                              (catch Exception e
                                (logger/warn logger-tag (format "Error getting MCP resource %s: %s" uri (.getMessage e)))
                                []))
              nil))
          contexts))

(defn contexts-str-from-prompt
  "Extract all contexts (@something) and refine them.
   Parse lines if present in contexts like @/path/to/file:L1-L4"
  [prompt db]
  (let [ ;; Capture @<path> with optional :L<start>-L<end>
        context-pattern #"@([/~\.][^\s:]+)(?::L(\d+)-L(\d+))?"
        matches (re-seq context-pattern prompt)
        raw-contexts (mapv (fn [[_ path s e]]
                             (assoc-some {:type (if (fs/directory? path) "directory" "file")
                                          :path path}
                                         :lines-range (when (and s e)
                                                        {:start (Integer/parseInt s)
                                                         :end   (Integer/parseInt e)})))
                           matches)]
    (when (seq raw-contexts)
      (raw-contexts->refined raw-contexts db))))

(def ^:private ttl-all-files-ms 5000)

(defn ^:private all-files-from* [root-filename] (fs/glob root-filename "**"))
(def ^:private all-files-from (memoize/ttl all-files-from* :ttl/threshold ttl-all-files-ms))

(defn ^:private match-sort-key
  "Sort key ranking basename matches first, then earlier and shorter matches."
  [lower-query path]
  (let [path-str (string/lower-case (str path))
        basename (string/lower-case (str (fs/file-name path)))]
    [(if (string/starts-with? basename lower-query) 0 1)
     (if (string/includes? basename lower-query) 0 1)
     (or (string/index-of path-str lower-query) Integer/MAX_VALUE)
     (count path-str)]))

(defn ^:private matching-dirs-of
  "Directories under `root-filename` matching `lower-query`, derived from
   the matched `files` since the workspace glob results are usually
   filtered to git-tracked files only, which excludes directories."
  [root-filename lower-query files]
  (let [root-str (str root-filename)]
    (->> files
         (mapcat (fn [file]
                   (->> (iterate fs/parent (fs/parent file))
                        (take-while (fn [dir]
                                      (and dir
                                           (not= (str dir) root-str)
                                           (string/starts-with? (str dir) root-str)))))))
         (distinct)
         (filter #(string/includes? (string/lower-case (str %)) lower-query)))))

(defn ^:private contexts-for [root-filename query config]
  (let [all-paths (all-files-from root-filename)]
    (if (string/blank? query)
      (f.index/filter-allowed all-paths root-filename config)
      (let [lower-query (string/lower-case query)
            filtered (filter (fn [p]
                               (string/includes? (-> (str p) string/lower-case)
                                                 lower-query))
                             all-paths)
            allowed-files (f.index/filter-allowed filtered root-filename config)
            dirs (matching-dirs-of root-filename lower-query allowed-files)]
        (sort-by (partial match-sort-key lower-query)
                 (concat dirs allowed-files))))))

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
                                roots))
        root-dirs (mapv (fn [root] {:type "directory" :path root}) roots)
        mcp-resources (mapv #(assoc % :type "mcpResource") (f.mcp/all-resources @db*))]
    (if files-only?
      (concat relative-files
              workspace-listed-files
              workspace-files)
      (concat [{:type "repoMap"}
               {:type "cursor"}]
              root-dirs
              relative-files
              workspace-listed-files
              workspace-files
              mcp-resources))))
