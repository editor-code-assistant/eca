(ns eca.features.context
  (:require
   [babashka.fs :as fs]
   [eca.features.index :as f.index]
   [eca.features.tools.mcp :as f.mcp]
   [eca.llm-api :as llm-api]
   [eca.logger :as logger]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CONTEXT]")

(defn raw-contexts->refined [contexts db]
  (mapcat (fn [{:keys [type path lines-range uri]}]
            (case (name type)
              "file" [{:type :file
                       :path path
                       :partial (boolean lines-range)
                       :content (llm-api/refine-file-context path lines-range)}]
              "directory" (->> (fs/glob path "**")
                               (remove fs/directory?)
                               (map (fn [path]
                                      (let [filename (str (fs/canonicalize path))]
                                        {:type :file
                                         :path filename
                                         :content (llm-api/refine-file-context filename nil)}))))
              "repoMap" [{:type :repoMap}]
              "mcpResource" (try
                              (mapv
                               (fn [{:keys [text]}]
                                 {:type :mcpResource
                                  :uri uri
                                  :content text})
                               (:contents (f.mcp/get-resource! uri db)))
                              (catch Exception e
                                (logger/warn logger-tag (format "Error getting MCP resource %s: %s" uri (.getMessage e)))
                                []))))
          contexts))

(defn ^:private contexts-for [root-filename query config]
  (let [all-files (fs/glob root-filename (str "**" (or query "") "**"))
        allowed-files (f.index/filter-allowed all-files root-filename config)]
    allowed-files))

(defn all-contexts [query db* config]
  (let [all-subfiles-and-dirs (into []
                                    (comp
                                     (map :uri)
                                     (map shared/uri->filename)
                                     (mapcat #(contexts-for % query config))
                                     (take 200) ;; for performance, user can always make query specific for better results.
                                     (map (fn [file-or-dir]
                                            {:type (if (fs/directory? file-or-dir)
                                                     :directory
                                                     :file)
                                             :path (str (fs/canonicalize file-or-dir))})))
                                    (:workspace-folders @db*))
        root-dirs (mapv (fn [{:keys [uri]}] {:type :directory
                                             :path (shared/uri->filename uri)})
                        (:workspace-folders @db*))
        mcp-resources (mapv #(assoc % :type :mcpResource) (f.mcp/all-resources @db*))]
    (concat [{:type :repoMap}]
            root-dirs
            all-subfiles-and-dirs
            mcp-resources)))
