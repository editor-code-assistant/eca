(ns eca.features.tools
  "This ns centralizes all available tools for LLMs including
   eca native tools and MCP servers."
  (:require
   [clojure.string :as string]
   [eca.features.tools.editor :as f.tools.editor]
   [eca.features.tools.filesystem :as f.tools.filesystem]
   [eca.features.tools.mcp :as f.mcp]
   [eca.features.tools.mcp.clojure-mcp]
   [eca.features.tools.shell :as f.tools.shell]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.shared :refer [assoc-some]])
  (:import
   [java.util Map]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[TOOLS]")

;; ============================================================================
;; Tool Registry & Configuration
;; ============================================================================

(def ^:private native-tool-registry
  [{:key :filesystem :definitions f.tools.filesystem/definitions}
   {:key :shell      :definitions f.tools.shell/definitions}
   {:key :editor     :definitions f.tools.editor/definitions}])

;; ============================================================================
;; Native Tool Loading & Processing
;; ============================================================================

(defn ^:private load-user-tools [config]
  (some-> (find-ns 'eca.features.tools.user)
          (ns-resolve 'user-tool-definitions)
          (apply [config])))

(defn ^:private load-enabled-native-tools [config]
  (->> native-tool-registry
       (filter #(get-in config [:nativeTools (:key %) :enabled]))
       (map :definitions)
       (apply merge {})))

(defn ^:private substitute-workspace-roots [db description]
  (string/replace description #"\\$workspaceRoots"
                  (constantly (tools.util/workspace-roots-strs db))))

(defn ^:private transform-tool-definition [db [name tool]]
  [name (-> tool
            (assoc :name name)
            (update :description (partial substitute-workspace-roots db)))])

(defn ^:private native-definitions [db config]
  (->> (merge (load-enabled-native-tools config)
              (load-user-tools config))
       (map (partial transform-tool-definition db))
       (into {})))

(defn ^:private native-tools [db config]
  (vals (native-definitions db config)))

;; ============================================================================
;; Tool Status & Filtering Utilities
;; ============================================================================

(defn ^:private with-tool-status [disabled-tools tool]
  (assoc-some tool :disabled (contains? disabled-tools (:name tool))))

(defn ^:private tool-enabled? [tool {:keys [behavior db config disabled-tools]}]
  (and (not (contains? disabled-tools (:name tool)))
       ((or (:enabled-fn tool) (constantly true))
        {:behavior behavior :db db :config config})))

(defn ^:private find-tool-by-name [tools name]
  (first (filter #(= name (:name %)) tools)))

;; ============================================================================
;; Public API
;; ============================================================================

(defn all-tools
  "Returns all available tools, including both native ECA tools
   (like filesystem and shell tools) and tools provided by MCP servers."
  [behavior db config]
  (let [disabled-tools (set (get-in config [:disabledTools] []))
        context {:behavior behavior :db db :config config :disabled-tools disabled-tools}]
    (filterv
     #(tool-enabled? % context)
     (concat
      (mapv #(assoc % :origin :native) (native-tools db config))
      (mapv #(assoc % :origin :mcp) (f.mcp/all-tools db))))))

(defn call-tool! [^String name ^Map arguments db config messenger behavior]
  (logger/info logger-tag (format "Calling tool '%s' with args '%s'" name arguments))
  (let [arguments (update-keys arguments clojure.core/name)]
    (try
      (let [result (if-let [native-handler (get-in (native-definitions db config) [name :handler])]
                     (native-handler arguments {:db db :config config
                                                :messenger messenger :behavior behavior})
                     (f.mcp/call-tool! name arguments db))]
        (logger/debug logger-tag "Tool call result: " result)
        result)
      (catch Exception e
        (logger/warn logger-tag (format "Error calling tool %s: %s\n%s"
                                        name (.getMessage e)
                                        (with-out-str (.printStackTrace e))))
        {:error true
         :contents [{:type :text
                     :text (str "Error calling tool: " (.getMessage e))}]}))))

;; ============================================================================
;; Server Management
;; ============================================================================

(defn ^:private create-server-update-callback [messenger disabled-tools]
  (fn [server]
    (messenger/tool-server-updated
     messenger
     (-> server
         (assoc :type :mcp)
         (update :tools #(mapv (partial with-tool-status disabled-tools) %))))))

(defn ^:private notify-native-tools-status [messenger db config disabled-tools]
  (messenger/tool-server-updated
   messenger
   {:type :native
    :name "ECA"
    :status "running"
    :tools (->> (native-tools db config)
                (mapv #(select-keys % [:name :description :parameters]))
                (mapv (partial with-tool-status disabled-tools)))}))

(defn init-servers! [db* messenger config]
  (let [disabled-tools (set (get-in config [:disabledTools] []))
        update-callback (create-server-update-callback messenger disabled-tools)]
    (notify-native-tools-status messenger @db* config disabled-tools)
    (f.mcp/initialize-servers-async! {:on-server-updated update-callback} db* config)))

(defn stop-server! [name db* messenger config]
  (let [disabled-tools (set (get-in config [:disabledTools] []))
        update-callback (create-server-update-callback messenger disabled-tools)]
    (f.mcp/stop-server! name db* config {:on-server-updated update-callback})))

(defn start-server! [name db* messenger config]
  (let [disabled-tools (set (get-in config [:disabledTools] []))
        update-callback (create-server-update-callback messenger disabled-tools)]
    (f.mcp/start-server! name db* config {:on-server-updated update-callback})))

;; ============================================================================
;; Tool Metadata & Utilities
;; ============================================================================

(defn manual-approval? [all-tools name args db config]
  (boolean
   (let [tool (find-tool-by-name all-tools name)
         require-approval-fn (:require-approval-fn tool)
         manual-approval-config (get-in config [:toolCall :manualApproval])]
     (or (when require-approval-fn (require-approval-fn args {:db db}))
         (if (coll? manual-approval-config)
           (some #(= name (str %)) manual-approval-config)
           manual-approval-config)))))

(defn tool-call-summary [all-tools name args]
  (when-let [tool (find-tool-by-name all-tools name)]
    (when-let [summary-fn (:summary-fn tool)]
      (try
        (summary-fn args)
        (catch Exception e
          (logger/error (format "Error in tool call summary fn %s: %s" name (.getMessage e)))
          nil)))))

(defn tool-call-details-before-invocation
  "Return the tool call details before invoking the tool."
  [name arguments]
  (tools.util/tool-call-details-before-invocation name arguments))

(defn tool-call-details-after-invocation
  "Return the tool call details after invoking the tool."
  [name arguments details result]
  (tools.util/tool-call-details-after-invocation name arguments details result))
