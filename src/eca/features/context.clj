(ns eca.features.context
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.features.index :as f.index]
   [eca.features.tools.mcp :as f.mcp]
   [eca.llm-api :as llm-api]
   [eca.logger :as logger]
   [eca.shared :as shared :refer [assoc-some]])
  (:import
   [java.util Base64]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CONTEXT]")

(defn ^:private agents-file-contexts
  "Search for AGENTS.md file both in workspaceRoot and global config dir."
  [db _config]
  ;; TODO make it customizable by behavior
  (let [agent-file "AGENTS.md"
        local-agent-files (keep (fn [{:keys [uri]}]
                                  (let [agent-file (fs/path (shared/uri->filename uri) agent-file)]
                                    (when (fs/readable? agent-file)
                                      (fs/canonicalize agent-file))))
                                (:workspace-folders db))
        global-agent-file (let [agent-file (fs/path (config/global-config-dir) agent-file)]
                            (when (fs/readable? agent-file)
                              (fs/canonicalize agent-file)))]
    (mapv (fn [path]
            {:type :file
             :path (str path)
             :partial false
             :content (llm-api/refine-file-context (str path) nil)})
          (concat local-agent-files
                  (when global-agent-file [global-agent-file])))))

(defn ^:private file->refined-context [path lines-range]
  (let [ext (string/lower-case (fs/extension path))]
    (if (contains? #{"png" "jpg" "jpeg" "gif" "webp"} ext)
      {:type :image
       :media-type (case ext
                     "jpg" "image/jpeg"
                     (str "image/" ext))
       :base64 (.encodeToString (Base64/getEncoder)
                                (fs/read-all-bytes (fs/file path)))
       :path path}
      (assoc-some
       {:type :file
        :path path
        :content (llm-api/refine-file-context path lines-range)}
       :partial lines-range))))

(defn raw-contexts->refined [contexts db config]
  (concat (agents-file-contexts db config)
          (mapcat (fn [{:keys [type path lines-range position uri]}]
                    (case (name type)
                      "file" [(file->refined-context path lines-range)]
                      "directory" (->> (fs/glob path "**")
                                       (remove fs/directory?)
                                       (map (fn [path]
                                              (let [filename (str (fs/canonicalize path))]
                                                (file->refined-context filename nil)))))
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
                  contexts)))

(defn ^:private all-files-from* [root-filename] (fs/glob root-filename "**"))
(def ^:private all-files-from (memoize all-files-from*))

(defn ^:private contexts-for [root-filename query config]
  (let [all-paths (all-files-from root-filename)
        filtered (if (or (nil? query) (string/blank? query))
                   all-paths
                   (filter (fn [p]
                             (string/includes? (-> (str p) string/lower-case)
                                               (string/lower-case query)))
                           all-paths))
        allowed-files (f.index/filter-allowed filtered root-filename config)]
    allowed-files))

(defn ^:private file->context [file-or-dir]
  (let [path (str (fs/canonicalize file-or-dir))]
    (if (fs/directory? file-or-dir)
      {:type "directory"
       :path path}
      {:type "file"
       :path path})))

(defn all-contexts [query files-only? db* config]
  (let [query (or (some-> query string/trim) "")
        first-project-path (shared/uri->filename (:uri (first (:workspace-folders @db*))))
        relative-path (and query
                           (or
                            (when (string/starts-with? query "~")
                              (fs/expand-home (fs/file query)))
                            (when (string/starts-with? query "/")
                              (fs/file query))
                            (when (or (string/starts-with? query "./")
                                      (string/starts-with? query "../"))
                              (fs/file first-project-path query))))
        relative-files (when relative-path
                         (mapv file->context
                               (try
                                 (if (fs/exists? relative-path)
                                   (fs/list-dir relative-path)
                                   (fs/list-dir (fs/parent relative-path)))
                                 (catch Exception _ nil))))
        workspace-files (when-not relative-path
                          (into []
                                (comp
                                 (map :uri)
                                 (map shared/uri->filename)
                                 (mapcat #(contexts-for % query config))
                                 (take 100) ;; for performance, user can always make query specific for better results.
                                 (map file->context))
                                (:workspace-folders @db*)))
        root-dirs (mapv (fn [{:keys [uri]}] {:type "directory"
                                             :path (shared/uri->filename uri)})
                        (:workspace-folders @db*))
        mcp-resources (mapv #(assoc % :type "mcpResource") (f.mcp/all-resources @db*))]
    (if files-only?
      (concat relative-files
              workspace-files)
      (concat [{:type "repoMap"}
               {:type "cursor"}]
              root-dirs
              relative-files
              workspace-files
              mcp-resources))))
