(ns eca.features.tools
  "This ns centralizes all available tools for LLMs including
   eca native tools and MCP servers."
  (:require
   [clojure.string :as string]
   [eca.diff :as diff]
   [eca.features.tools.filesystem :as f.tools.filesystem]
   [eca.features.tools.mcp :as f.mcp]
   [eca.features.tools.shell :as f.tools.shell]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.shared :refer [assoc-some]])
  (:import
   [java.util Map]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[TOOLS]")

(defn ^:private native-definitions [db config]
  (into
   {}
   (map (fn [[name tool]]
          [name (-> tool
                    (assoc :name name)
                    (update :description #(-> %
                                              (string/replace #"\$workspaceRoots" (constantly (tools.util/workspace-roots-strs db))))))]))
   (merge {}
          (when (get-in config [:nativeTools :filesystem :enabled])
            f.tools.filesystem/definitions)
          (when (get-in config [:nativeTools :shell :enabled])
            f.tools.shell/definitions))))

(defn ^:private native-tools [db config]
  (mapv #(select-keys % [:name :description :parameters])
        (vals (native-definitions db config))))

(defn all-tools
  "Returns all available tools, including both native ECA tools
   (like filesystem and shell tools) and tools provided by MCP servers."
  [db config]
  (let [disabled-tools (set (get-in config [:disabledTools] []))]
    (filterv
     (fn [tool]
       (not (contains? disabled-tools (:name tool))))
     (concat
      (mapv #(assoc % :origin :native) (native-tools db config))
      (mapv #(assoc % :origin :mcp) (f.mcp/all-tools db))))))

(defn call-tool! [^String name ^Map arguments db config]
  (logger/info logger-tag (format "Calling tool '%s' with args '%s'" name arguments))
  (let [arguments (update-keys arguments clojure.core/name)]
    (try
      (let [result (if-let [native-tool-handler (get-in (native-definitions db config) [name :handler])]
                     (native-tool-handler arguments {:db db :config config})
                     (f.mcp/call-tool! name arguments db))]
        (logger/debug logger-tag "Tool call result: " result)
        result)
      (catch Exception e
        (logger/warn logger-tag (format "Error calling tool %s: %s" name (.getMessage e)))
        {:error true
         :contents [{:type :text
                     :text (str "Error calling tool: " (.getMessage e))}]}))))

(defn init-servers! [db* messenger config]
  (let [disabled-tools (set (get-in config [:disabledTools] []))
        with-tool-status (fn [tool]
                           (assoc-some tool :disabled (contains? disabled-tools (:name tool))))]
    (messenger/tool-server-updated messenger {:type :native
                                              :name "ECA"
                                              :status "running"
                                              :tools (mapv with-tool-status (native-tools @db* config))})
    (f.mcp/initialize-servers-async!
     {:on-server-updated (fn [server]
                           (messenger/tool-server-updated messenger (-> server
                                                                        (assoc :type :mcp)
                                                                        (update :tools #(mapv with-tool-status %)))))}
     db*
     config)))

(defn stop-server! [name db* messenger config]
  (let [disabled-tools (set (get-in config [:disabledTools] []))
        with-tool-status (fn [tool]
                           (assoc-some tool :disabled (contains? disabled-tools (:name tool))))]
    (f.mcp/stop-server!
     name
     db*
     config
     {:on-server-updated (fn [server]
                           (messenger/tool-server-updated messenger (-> server
                                                                        (assoc :type :mcp)
                                                                        (update :tools #(mapv with-tool-status %)))))})))

(defn start-server! [name db* messenger config]
  (let [disabled-tools (set (get-in config [:disabledTools] []))
        with-tool-status (fn [tool]
                           (assoc-some tool :disabled (contains? disabled-tools (:name tool))))]
    (f.mcp/start-server!
     name
     db*
     config
     {:on-server-updated (fn [server]
                           (messenger/tool-server-updated messenger (-> server
                                                                        (assoc :type :mcp)
                                                                        (update :tools #(mapv with-tool-status %)))))})))
(defn get-tool-call-details [name arguments]
  (case name
    "eca_write_file" (let [path (get arguments "path")
                           content (get arguments "content")]
                       (when (and path content)
                         (let [{:keys [added removed diff]} (diff/diff "" content path)]
                           {:type :fileChange
                            :path path
                            :linesAdded added
                            :linesRemoved removed
                            :diff diff})))
    "eca_edit_file" (let [path (get arguments "path")
                          original-content (get arguments "original_content")
                          new-content (get arguments "new_content")
                          all? (get arguments "all_occurrences")]
                      (when-let [{:keys [original-full-content
                                         new-full-content]} (and path original-content new-content
                                                                 (f.tools.filesystem/file-change-full-content path original-content new-content all?))]
                        (let [{:keys [added removed diff]} (diff/diff original-full-content new-full-content path)]
                          {:type :fileChange
                           :path path
                           :linesAdded added
                           :linesRemoved removed
                           :diff diff})))
    nil))
