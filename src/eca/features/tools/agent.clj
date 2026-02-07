(ns eca.features.tools.agent
  "Tool for spawning subagents to perform focused tasks in isolated context."
  (:require
   [clojure.string :as str]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]
   [eca.messenger :as messenger]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[AGENT-TOOL]")

(defn ^:private all-agents
  [config]
  (->> (:behavior config)
       (keep (fn [[behavior-name behavior-config]]
               (when (and (= "subagent" (:mode behavior-config))
                          (:description behavior-config))
                 {:name behavior-name
                  :description (:description behavior-config)
                  :model (:defaultModel behavior-config)
                  :max-steps (:maxSteps behavior-config)
                  :system-prompt (:systemPrompt behavior-config)
                  :tool-call (:toolCall behavior-config)})))
       vec))

(defn ^:private get-agent
  [agent-name config]
  (first (filter #(= agent-name (:name %)) (all-agents config))))

(defn ^:private max-steps [agent-def]
  (:max-steps agent-def))

(defn ^:private extract-final-summary
  "Extract the final assistant message as summary from chat messages."
  [messages]
  (let [assistant-messages (->> messages
                                (filter #(= "assistant" (:role %)))
                                (map :content)
                                (filter seq))]
    (if (seq assistant-messages)
      (let [last-content (last assistant-messages)]
        (->> last-content
             (filter #(= :text (:type %)))
             (map :text)
             (str/join "\n")))
      "Agent completed without producing output.")))

(defn ^:private ->subagent-chat-id
  "Generate a deterministic subagent chat id from the tool-call-id."
  [tool-call-id]
  (str "subagent-" tool-call-id))

(defn ^:private send-step-progress!
  "Send a toolCallRunning notification with current step progress to the parent chat."
  [messenger chat-id tool-call-id agent-name subagent-chat-id step max-steps model arguments]
  (messenger/chat-content-received
   messenger
   {:chat-id chat-id
    :role :assistant
    :content {:type :toolCallRunning
              :id tool-call-id
              :name "spawn_agent"
              :server "eca"
              :origin "native"
              :summary (format "Running agent '%s'" agent-name)
              :arguments arguments
              :details {:type :subagent
                        :subagent-chat-id subagent-chat-id
                        :model model
                        :agent-name agent-name
                        :step step
                        :max-steps max-steps}}}))

(defn ^:private stop-subagent-chat!
  "Stop a running subagent chat and clean up its state from db."
  [db* messenger metrics subagent-chat-id agent-name]
  (let [prompt-stop (requiring-resolve 'eca.features.chat/prompt-stop)]
    (try
      (prompt-stop {:chat-id subagent-chat-id} db* messenger metrics)
      (catch Exception e
        (logger/warn logger-tag (format "Error stopping subagent '%s': %s" agent-name (.getMessage e))))))
  (swap! db* update :chats dissoc subagent-chat-id))

(defn ^:private spawn-agent
  "Handler for the spawn_agent tool.
   Spawns a subagent to perform a focused task and returns the result."
  [arguments {:keys [db* config messenger metrics chat-id tool-call-id call-state-fn]}]
  (let [agent-name (get arguments "agent")
        task (get arguments "task")
        db @db*

        ;; Check for nesting - prevent subagents from spawning other subagents
        _ (when (get-in db [:chats chat-id :agent-def])
            (throw (ex-info "Agents cannot spawn other agents (nesting not allowed)"
                            {:agent-name agent-name
                             :parent-chat-id chat-id})))

        ;; Load agent definition
        agent-def (get-agent agent-name config)
        _ (when-not agent-def
            (let [available (all-agents config)]
              (throw (ex-info (format "Agent '%s' not found. Available agents: %s"
                                      agent-name
                                      (if (seq available)
                                        (str/join ", " (map :name available))
                                        "none"))
                              {:agent-name agent-name
                               :available (map :name available)}))))

        ;; Create subagent chat session using deterministic id based on tool-call-id
        subagent-chat-id (->subagent-chat-id tool-call-id)

        parent-model (get-in db [:chats chat-id :model])
        subagent-model (or (:model agent-def) parent-model)]

    (logger/info logger-tag (format "Spawning agent '%s' for task: %s" agent-name task))

    (let [max-steps (max-steps agent-def)]
      (swap! db* assoc-in [:chats subagent-chat-id]
             (cond-> {:id subagent-chat-id
                      :parent-chat-id chat-id
                      :agent-name agent-name
                      :agent-def agent-def
                      :current-step 1}
               max-steps (assoc :max-steps max-steps)))

      ;; Require chat ns here to avoid circular dependency
      (let [chat-prompt (requiring-resolve 'eca.features.chat/prompt)
            task-prompt (if max-steps
                          (format "%s\n\nIMPORTANT: You have a maximum of %d steps to complete this task. Be efficient and provide a clear summary of your findings before reaching the limit."
                                  task max-steps)
                          task)]
        (chat-prompt
         {:message task-prompt
          :chat-id subagent-chat-id
          :model subagent-model
          :behavior agent-name
          :contexts []}
         db*
         messenger
         config
         metrics)))

    ;; Wait for subagent to complete by polling status
    (let [stopped-result (fn []
                           (logger/info logger-tag (format "Agent '%s' stopped by parent chat" agent-name))
                           (stop-subagent-chat! db* messenger metrics subagent-chat-id agent-name)
                           {:error true
                            :contents [{:type :text
                                        :text (format "Agent '%s' was stopped because the parent chat was stopped." agent-name)}]})]
      (try
        (loop [last-step 0]
          (let [db @db*
                status (get-in db [:chats subagent-chat-id :status])
                current-step (get-in db [:chats subagent-chat-id :current-step] 1)]
            ;; Send step progress when step advances
            (when (> current-step last-step)
              (send-step-progress! messenger chat-id tool-call-id agent-name
                                   subagent-chat-id current-step (max-steps agent-def) subagent-model arguments))
            (cond
              ;; Parent chat stopped — propagate stop to subagent
              (= :stopping (:status (call-state-fn)))
              (stopped-result)

              ;; Subagent completed
              (#{:idle :error} status)
              (let [messages (get-in db [:chats subagent-chat-id :messages] [])
                    summary (extract-final-summary messages)]
                (logger/info logger-tag (format "Agent '%s' completed after %d steps" agent-name current-step))
                (swap! db* (fn [db]
                             (-> db
                                 (assoc-in [:chats chat-id :tool-calls tool-call-id :subagent-final-step] current-step)
                                 (update :chats dissoc subagent-chat-id))))
                {:error false
                 :contents [{:type :text
                             :text (format "## Agent '%s' Result\n\n%s" agent-name summary)}]})

              ;; Keep waiting
              :else
              (do
                (Thread/sleep 1000)
                (recur (long (max last-step current-step)))))))
        (catch InterruptedException _
          (stopped-result))))))

(defn ^:private build-description
  "Build tool description with available agents listed."
  [config]
  (let [base-description (tools.util/read-tool-description "spawn_agent")
        agents (all-agents config)
        agents-section (str "\n\nAvailable agents:\n"
                            (->> agents
                                 (map (fn [{:keys [name description]}]
                                        (str "- " name ": " description)))
                                 (str/join "\n")))]
    (str base-description agents-section)))

(defn definitions
  [config]
  {"spawn_agent"
   {:description (build-description config)
    :parameters {:type "object"
                 :properties {"agent" {:type "string"
                                       :description "Name of the agent to spawn"}
                              "task" {:type "string"
                                      :description "Clear description of what the agent should accomplish"}}
                 :required ["agent" "task"]}
    :handler #'spawn-agent
    :summary-fn (fn [{:keys [args]}]
                  (if-let [agent-name (get args "agent")]
                    (format "Running agent '%s'" agent-name)
                    "Spawning agent"))}})

(defmethod tools.util/tool-call-details-before-invocation :spawn_agent
  [_name arguments _server {:keys [db config chat-id tool-call-id]}]
  (let [agent-name (get arguments "agent")
        agent-def (when agent-name
                    (get-agent agent-name config))
        parent-model (get-in db [:chats chat-id :model])
        subagent-model (or (:model agent-def) parent-model)
        subagent-chat-id (when tool-call-id
                           (->subagent-chat-id tool-call-id))]
    {:type :subagent
     :subagent-chat-id subagent-chat-id
     :model subagent-model
     :agent-name agent-name
     :step (get-in db [:chats subagent-chat-id :current-step] 1)
     :max-steps (max-steps agent-def)}))

(defmethod tools.util/tool-call-details-after-invocation :spawn_agent
  [_name _arguments before-details _result {:keys [db chat-id tool-call-id]}]
  (let [final-step (get-in db [:chats chat-id :tool-calls tool-call-id :subagent-final-step]
                           (or (:step before-details) 1))]
    (assoc before-details :step final-step)))
