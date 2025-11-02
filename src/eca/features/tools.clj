(ns eca.features.tools
  "This ns centralizes all available tools for LLMs including
   eca native tools and MCP servers."
  (:require
   [clojure.string :as string]
   [eca.features.tools.chat :as f.tools.chat]
   [eca.features.tools.custom :as f.tools.custom]
   [eca.features.tools.editor :as f.tools.editor]
   [eca.features.tools.filesystem :as f.tools.filesystem]
   [eca.features.tools.mcp :as f.mcp]
   [eca.features.tools.mcp.clojure-mcp]
   [eca.features.tools.shell :as f.tools.shell]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.metrics :as metrics]
   [eca.shared :refer [assoc-some]])
  (:import
   [java.util Map]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[TOOLS]")

(defn ^:private get-disabled-tools
  "Returns a set of disabled tools, merging global and behavior-specific."
  [config behavior]
  (set (concat (get config :disabledTools [])
               (if behavior
                 (get-in config [:behavior behavior :disabledTools] [])
                 []))))

(defn make-tool-status-fn
  "Returns a function that marks tools as disabled based on config and behavior.
   If behavior is nil, only uses global disabledTools."
  [config behavior]
  (let [disabled-tools (get-disabled-tools config behavior)]
    (fn [tool]
      (assoc-some tool :disabled (contains? disabled-tools (:name tool))))))

(defn ^:private native-definitions [db config]
  (into
   {}
   (map (fn [[name tool]]
          [name (-> tool
                    (assoc :name name)
                    (update :description #(-> %
                                              (string/replace #"\{workspaceRoots\}" (constantly (tools.util/workspace-roots-strs db))))))]))
   (merge {}
          f.tools.filesystem/definitions
          f.tools.shell/definitions
          f.tools.editor/definitions
          f.tools.chat/definitions
          (f.tools.custom/definitions config))))

(defn native-tools [db config]
  (mapv #(assoc % :server {:name "eca"}) (vals (native-definitions db config))))

(defn all-tools
  "Returns all available tools, including both native ECA tools
   (like filesystem and shell tools) and tools provided by MCP servers."
  [chat-id behavior db config]
  (let [disabled-tools (get-disabled-tools config behavior)]
    (filterv
     (fn [tool]
       (and (not (contains? disabled-tools (:name tool)))
            ;; check for enabled-fn if present
            ((or (:enabled-fn tool) (constantly true))
             {:behavior behavior
              :db db
              :chat-id chat-id
              :config config})))
     (concat
      (mapv #(assoc % :origin :native) (native-tools db config))
      (mapv #(assoc % :origin :mcp) (f.mcp/all-tools db))))))

(defn call-tool! [^String name ^Map arguments chat-id tool-call-id behavior db* config messenger metrics
                  call-state-fn         ; thunk
                  state-transition-fn   ; params: event & event-data
                  ]
  (logger/info logger-tag (format "Calling tool '%s' with args '%s'" name arguments))
  (let [arguments (update-keys arguments clojure.core/name)
        db @db*
        tool-meta (some #(when (= name (:name %)) %)
                        (all-tools chat-id behavior db config))
        required-args-error (when-let [parameters (:parameters tool-meta)]
                              (tools.util/required-params-error parameters arguments))]
    (try
      (let [result (-> (if required-args-error
                         required-args-error
                         (if-let [native-tool-handler (get-in (native-definitions db config) [name :handler])]
                           (native-tool-handler arguments {:db db
                                                           :db* db*
                                                           :config config
                                                           :messenger messenger
                                                           :behavior behavior
                                                           :chat-id chat-id
                                                           :tool-call-id tool-call-id
                                                           :call-state-fn call-state-fn
                                                           :state-transition-fn state-transition-fn})
                           (f.mcp/call-tool! name arguments {:db db}))))]
        (logger/debug logger-tag "Tool call result: " result)
        (metrics/count-up! "tool-called" {:name name :error (:error result)} metrics)
        result)
      (catch Exception e
        (logger/warn logger-tag (format "Error calling tool %s: %s\n%s" name (.getMessage e) (with-out-str (.printStackTrace e))))
        (metrics/count-up! "tool-called" {:name name :error true} metrics)
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
                                                          (remove #(= "eca_compact_chat" (:name %)))
                                                          (mapv #(select-keys % [:name :description :parameters]))
                                                          (mapv tool-status-fn))})
    (f.mcp/initialize-servers-async!
     {:on-server-updated (partial notify-server-updated metrics messenger tool-status-fn)}
     db*
     config)))

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
     {:on-server-updated (partial notify-server-updated metrics messenger tool-status-fn)})))

(defn legacy-manual-approval? [config tool-name]
  (let [manual-approval? (get-in config [:toolCall :manualApproval] nil)]
    (if (coll? manual-approval?)
      (some #(= tool-name (str %)) manual-approval?)
      manual-approval?)))

(defn ^:private approval-matches? [[server-or-full-tool-name config] tool-call-server tool-call-name args]
  (let [args-matchers (:argsMatchers config)
        [server-name tool-name] (if (string/includes? server-or-full-tool-name "__")
                                  (string/split server-or-full-tool-name #"__" 2)
                                  (if (string/starts-with? server-or-full-tool-name "eca_")
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
  [all-tools tool-call-name args db config behavior]
  (let [remember-to-approve? (get-in db [:tool-calls tool-call-name :remember-to-approve?])
        {:keys [server require-approval-fn]} (first (filter #(= tool-call-name (:name %))
                                                            all-tools))
        {:keys [allow ask deny byDefault]}   (merge (get-in config [:toolCall :approval])
                                                    (get-in config [:behavior behavior :toolCall :approval]))]
    (cond
      (and require-approval-fn (require-approval-fn args {:db db}))
      :ask

      remember-to-approve?
      :allow

      (some #(approval-matches? % (:name server) tool-call-name args) deny)
      :deny

      (some #(approval-matches? % (:name server) tool-call-name args) ask)
      :ask

      (some #(approval-matches? % (:name server) tool-call-name args) allow)
      :allow

      (legacy-manual-approval? config tool-call-name)
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

(defn tool-call-summary [all-tools name args config]
  (when-let [summary-fn (:summary-fn (first (filter #(= name (:name %))
                                                    all-tools)))]
    (try
      (summary-fn {:args args
                   :config config})
      (catch Exception e
        (logger/error (format "Error in tool call summary fn %s: %s" name (.getMessage e)))
        nil))))

(defn tool-call-details-before-invocation
  "Return the tool call details before invoking the tool."
  [name arguments server db ask-approval?]
  (try
    (tools.util/tool-call-details-before-invocation name arguments server {:db db
                                                                           :ask-approval? ask-approval?})
    (catch Exception e
      ;; Avoid failling tool call because of error on getting details.
      (logger/error logger-tag (format "Error getting details for %s with args %s: %s" name arguments e))
      nil)))

(defn tool-call-details-after-invocation
  "Return the tool call details after invoking the tool."
  [name arguments details result]
  (tools.util/tool-call-details-after-invocation name arguments details result))

(defn tool-call-destroy-resource!
  "Destroy the resource in the tool call named `name`."
  [name resource-kwd resource]
  (tools.util/tool-call-destroy-resource! name resource-kwd resource))

(defn refresh-tool-servers!
  "Updates all tool servers (native and MCP) with new behavior status."
  [tool-status-fn db* messenger config]
  (messenger/tool-server-updated messenger {:type :native
                                            :name "ECA"
                                            :status "running"
                                            :tools (->> (native-tools @db* config)
                                                        (mapv #(select-keys % [:name :description :parameters]))
                                                        (mapv tool-status-fn))})
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
