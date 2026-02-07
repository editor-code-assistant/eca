(ns eca.features.tools
  "This ns centralizes all available tools for LLMs including
   eca native tools and MCP servers."
  (:require
   [clojure.string :as string]
   [clojure.walk :as walk]
   [eca.features.tools.agent :as f.tools.agent]
   [eca.features.tools.chat :as f.tools.chat]
   [eca.features.tools.custom :as f.tools.custom]
   [eca.features.tools.editor :as f.tools.editor]
   [eca.features.tools.filesystem :as f.tools.filesystem]
   [eca.features.tools.mcp :as f.mcp]
   [eca.features.tools.mcp.clojure-mcp]
   [eca.features.tools.shell :as f.tools.shell]
   [eca.features.tools.skill :as f.tools.skill]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.metrics :as metrics]
   [eca.shared :refer [assoc-some]]
   [selmer.parser :as selmer])
  (:import
   [java.util Map]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[TOOLS]")

(defn legacy-manual-approval? [config tool-name]
  (let [manual-approval? (get-in config [:toolCall :manualApproval] nil)]
    (if (coll? manual-approval?)
      (some #(= tool-name (str %)) manual-approval?)
      manual-approval?)))

(defn ^:private approval-matches? [[server-or-full-tool-name config] tool-call-server tool-call-name args native-tools]
  (let [args-matchers (:argsMatchers config)
        [server-name tool-name] (if (string/includes? server-or-full-tool-name "__")
                                  (string/split server-or-full-tool-name #"__" 2)
                                  (if (some #(= server-or-full-tool-name (:name %)) native-tools)
                                    ["eca" server-or-full-tool-name]
                                    [server-or-full-tool-name nil]))]
    (cond
      ;; specified server name in config
      (and (nil? tool-name)
           ;; but the name doesn't match
           (not= tool-call-server server-name))
      false

      ;; tool or server not match
      (and tool-name
           (or (not= tool-call-server server-name)
               (not= tool-call-name tool-name)))
      false

      (map? args-matchers)
      (some (fn [[arg-name matchers]]
              (when-let [arg (get args arg-name)]
                (some #(re-matches (re-pattern (str %)) (str arg))
                      matchers)))
            args-matchers)

      :else
      true)))

(defn approval
  "Return the approval keyword for the specific tool call: ask, allow or deny.
   Behavior parameter is required - pass nil for global-only approval rules."
  [all-tools tool args db config behavior]
  (let [{:keys [server name require-approval-fn]} tool
        remember-to-approve? (get-in db [:tool-calls name :remember-to-approve?])
        native-tools (filter #(= :native (:origin %)) all-tools)
        {:keys [allow ask deny byDefault]}   (merge (get-in config [:toolCall :approval])
                                                    (get-in config [:behavior behavior :toolCall :approval]))]
    (cond
      remember-to-approve?
      :allow

      (and require-approval-fn (require-approval-fn args {:db db}))
      :ask

      (some #(approval-matches? % (:name server) name args native-tools) deny)
      :deny

      (some #(approval-matches? % (:name server) name args native-tools) ask)
      :ask

      (some #(approval-matches? % (:name server) name args native-tools) allow)
      :allow

      (legacy-manual-approval? config name)
      :ask

      (= "ask" byDefault)
      :ask

      (= "allow" byDefault)
      :allow

      (= "deny" byDefault)
      :deny

       ;; Probably a config error, default to ask
      :else
      :ask)))

(defn ^:private get-disabled-tools
  "Returns a set of disabled tools, merging global and behavior-specific."
  [config behavior]
  (set (concat (get config :disabledTools [])
               (if behavior
                 (get-in config [:behavior behavior :disabledTools] [])
                 []))))

(defn ^:private tool-disabled? [tool disabled-tools]
  (or (contains? disabled-tools (str (:name (:server tool)) "__" (:name tool)))
      (contains? disabled-tools (:name tool))))

(defn make-tool-status-fn
  "Returns a function that marks tools as disabled based on config and behavior.
   If behavior is nil, only uses global disabledTools."
  [config behavior]
  (let [disabled-tools (get-disabled-tools config behavior)]
    (fn [tool]
      (assoc-some tool :disabled (tool-disabled? tool disabled-tools)))))

(defn ^:private replace-string-values-with-vars
  "walk through config parsing dynamic string contents if value is a string."
  [m vars]
  (walk/postwalk
   (fn [x]
     (if (string? x)
       (selmer/render x vars)
       x))
   m))

(defn ^:private native-definitions [db config]
  (into
   {}
   (map (fn [[name tool]]
          [name (-> tool
                    (assoc :name name)
                    (replace-string-values-with-vars
                     {:workspaceRoots (tools.util/workspace-roots-strs db)
                      :readFileMaxLines (get-in config [:toolCall :readFile :maxLines])}))]))
   (merge {}
          f.tools.filesystem/definitions
          f.tools.shell/definitions
          f.tools.editor/definitions
          f.tools.chat/definitions
          f.tools.skill/definitions
          (f.tools.agent/definitions config)
          (f.tools.custom/definitions config))))

(defn native-tools [db config]
  (mapv #(assoc % :server {:name "eca"}) (vals (native-definitions db config))))

(defn ^:private filter-subagent-tools
  "Filter tools for subagent execution.
   Excludes spawn_agent to prevent nesting."
  [tools]
  (filterv #(not= "spawn_agent" (:name %)) tools))

(defn all-tools
  "Returns all available tools, including both native ECA tools
   (like filesystem and shell tools) and tools provided by MCP servers.
   Removes denied tools.
   When chat is a subagent (has :agent-def), filters tools based on agent definition."
  [chat-id behavior db config]
  (let [disabled-tools (get-disabled-tools config behavior)
        ;; presence of :agent-def indicates this is a subagent
        agent-def (get-in db [:chats chat-id :agent-def])
        all-tools (->> (concat
                        (mapv #(assoc % :origin :native) (native-tools db config))
                        (mapv #(assoc % :origin :mcp) (f.mcp/all-tools db)))
                       (mapv #(assoc % :full-name (str (-> % :server :name) "__" (:name %))))
                       (mapv (fn [tool]
                               (update tool :description
                                       (fn [desc]
                                         (or (get-in config [:behavior behavior :prompts :tools (:full-name tool)])
                                             (get-in config [:prompts :tools (:full-name tool)])
                                             desc)))))
                       (filterv (fn [tool]
                                  (and (not (tool-disabled? tool disabled-tools))
                                       ;; check for enabled-fn if present
                                       ((or (:enabled-fn tool) (constantly true))
                                        {:behavior behavior
                                         :db db
                                         :chat-id chat-id
                                         :config config})))))
        ;; Apply subagent tool filtering if applicable
        all-tools (if agent-def
                    (filter-subagent-tools all-tools)
                    all-tools)]
    (remove (fn [tool]
              (= :deny (approval all-tools tool {} db config behavior)))
            all-tools)))

(defn call-tool! [^String full-name ^Map arguments chat-id tool-call-id behavior db* config messenger metrics
                  call-state-fn         ; thunk
                  state-transition-fn   ; params: event & event-data
                  ]
  (logger/info logger-tag (format "Calling tool '%s' with args '%s'" full-name arguments))
  (let [[server-name tool-name] (string/split full-name #"__")
        arguments (update-keys arguments clojure.core/name)
        db @db*
        all-tools (all-tools chat-id behavior db config)
        tool-meta (some #(when (= full-name (:full-name %)) %) all-tools)
        required-args-error (when-let [parameters (:parameters tool-meta)]
                              (tools.util/required-params-error parameters arguments))]
    (try
      (when-not tool-meta
        (throw (ex-info (format "Tool '%s' not found" full-name) {:full-name full-name
                                                                  :server-name server-name
                                                                  :arguments arguments
                                                                  :all-tools (mapv :full-name all-tools)})))
      (let [result (-> (if required-args-error
                         required-args-error
                         (if-let [native-tool-handler (and (= "eca" server-name)
                                                           (get-in (native-definitions db config) [tool-name :handler]))]
                           (native-tool-handler arguments {:db db
                                                           :db* db*
                                                           :config config
                                                           :messenger messenger
                                                           :behavior behavior
                                                           :metrics metrics
                                                           :chat-id chat-id
                                                           :tool-call-id tool-call-id
                                                           :call-state-fn call-state-fn
                                                           :state-transition-fn state-transition-fn})
                           (f.mcp/call-tool! tool-name arguments {:db db})))
                       (tools.util/maybe-truncate-output config tool-call-id))]
        (logger/debug logger-tag "Tool call result: " result)
        (metrics/count-up! "tool-called" {:name full-name :error (:error result)} metrics)
        (if-let [r (:rollback-changes result)]
          (do
            (swap! db* assoc-in [:chats chat-id :tool-calls tool-call-id :rollback-changes] r)
            (dissoc result :rollback-changes))
          result))
      (catch Exception e
        (logger/warn logger-tag (format "Error calling tool %s: %s\n%s" full-name (.getMessage e) (with-out-str (.printStackTrace e))))
        (metrics/count-up! "tool-called" {:name full-name :error true} metrics)
        {:error true
         :contents [{:type :text
                     :text (str "Error calling tool: " (.getMessage e))}]}))))

(defn ^:private notify-server-updated [metrics messenger tool-status-fn server]
  (metrics/count-up! "mcp-server-status" {:name (:name server)
                                          :status (:status server)} metrics)
  (messenger/tool-server-updated messenger (-> server
                                               (assoc :type :mcp)
                                               (update :tools #(mapv tool-status-fn %)))))

(defn init-servers! [db* messenger config metrics]
  (let [default-behavior (get config :defaultBehavior)
        tool-status-fn (make-tool-status-fn config default-behavior)]
    (messenger/tool-server-updated messenger {:type :native
                                              :name "ECA"
                                              :status "running"
                                              :tools (->> (native-tools @db* config)
                                                          (remove #(= "compact_chat" (:name %)))
                                                          (mapv tool-status-fn)
                                                          (mapv #(select-keys % [:name :description :parameters :disabled])))})
    (f.mcp/initialize-servers-async!
     {:on-server-updated (partial notify-server-updated metrics messenger tool-status-fn)}
     db*
     config
     metrics)))

(defn stop-server! [name db* messenger config metrics]
  (let [tool-status-fn (make-tool-status-fn config nil)]
    (f.mcp/stop-server!
     name
     db*
     config
     {:on-server-updated (partial notify-server-updated metrics messenger tool-status-fn)})))

(defn start-server! [name db* messenger config metrics]
  (let [tool-status-fn (make-tool-status-fn config nil)]
    (f.mcp/start-server!
     name
     db*
     config
     metrics
     {:on-server-updated (partial notify-server-updated metrics messenger tool-status-fn)})))

(defn tool-call-summary [all-tools full-name args config db]
  (when-let [summary-fn (:summary-fn (first (filter #(= full-name (:full-name %))
                                                    all-tools)))]
    (try
      (summary-fn {:args args
                   :config config
                   :db db})
      (catch Exception e
        (logger/error (format "Error in tool call summary fn %s: %s" name (.getMessage e)))
        nil))))

(defn tool-call-details-before-invocation
  "Return the tool call details before invoking the tool."
  [name arguments server db config chat-id ask-approval? tool-call-id]
  (try
    (tools.util/tool-call-details-before-invocation name arguments server {:db db
                                                                           :config config
                                                                           :chat-id chat-id
                                                                           :ask-approval? ask-approval?
                                                                           :tool-call-id tool-call-id})
    (catch Exception e
      ;; Avoid failling tool call because of error on getting details.
      (logger/error logger-tag (format "Error getting details for %s with args %s: %s" name arguments e))
      nil)))

(defn tool-call-details-after-invocation
  "Return the tool call details after invoking the tool."
  [name arguments details result ctx]
  (tools.util/tool-call-details-after-invocation name arguments details result ctx))

(defn tool-call-destroy-resource!
  "Destroy the resource in the tool call named `name`."
  [full-name resource-kwd resource]
  (tools.util/tool-call-destroy-resource! full-name resource-kwd resource))

(defn refresh-tool-servers!
  "Updates all tool servers (native and MCP) with new behavior status."
  [tool-status-fn db* messenger config]
  (messenger/tool-server-updated messenger {:type :native
                                            :name "ECA"
                                            :status "running"
                                            :tools (->> (native-tools @db* config)
                                                        (mapv tool-status-fn)
                                                        (mapv #(select-keys % [:name :description :parameters :disabled])))})
  (doseq [[server-name {:keys [tools status]}] (:mcp-clients @db*)]
    (messenger/tool-server-updated messenger {:type :mcp
                                              :name server-name
                                              :status (name (or status :unknown))
                                              :tools (mapv tool-status-fn (or tools []))}))
  (doseq [[server-name server-config] (:mcpServers config)]
    (when (and (get server-config :disabled false)
               (not (contains? (:mcp-clients @db*) server-name)))
      (messenger/tool-server-updated messenger {:type :mcp
                                                :name server-name
                                                :status "disabled"
                                                :tools []}))))
