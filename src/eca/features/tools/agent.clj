(ns eca.features.tools.agent
  "Tool for spawning subagents to perform focused tasks in isolated context."
  (:require
   [clojure.string :as str]
   [eca.features.agents :as f.agents]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[AGENT-TOOL]")

(defn ^:private build-task-prompt
  [task max-turns]
  (format "%s\n\nIMPORTANT: You have a maximum of %d turns to complete this task. Be efficient and provide a clear summary of your findings before reaching the limit."
          task max-turns))

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

(defn subagent-chat-id
  "Generate a deterministic subagent chat id from the tool-call-id."
  [tool-call-id]
  (str "subagent-" tool-call-id))

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
  [arguments {:keys [db* config messenger metrics chat-id behavior tool-call-id call-state-fn]}]
  (let [agent-name (get arguments "agent")
        task (get arguments "task")
        db @db*

        ;; Check for nesting - prevent subagents from spawning other subagents
        ;; (presence of :agent-def indicates this is a subagent)
        _ (when (get-in db [:chats chat-id :agent-def])
            (throw (ex-info "Agents cannot spawn other agents (nesting not allowed)"
                            {:agent-name agent-name
                             :parent-chat-id chat-id})))

        ;; Load agent definition
        agent-def (f.agents/get-agent agent-name config (:workspace-folders db))
        _ (when-not agent-def
            (let [available (f.agents/all config (:workspace-folders db))]
              (throw (ex-info (format "Agent '%s' not found. Available agents: %s"
                                      agent-name
                                      (if (seq available)
                                        (str/join ", " (map :name available))
                                        "none"))
                              {:agent-name agent-name
                               :available (map :name available)}))))

        ;; Create subagent chat session using deterministic id based on tool-call-id
        subagent-chat-id* (subagent-chat-id tool-call-id)

        ;; Determine model (inherit from parent if not specified)
        parent-model (get-in db [:chats chat-id :model])
        subagent-model (or (:model agent-def) parent-model)]

    (logger/info logger-tag (format "Spawning agent '%s' for task: %s" agent-name task))

    ;; Initialize subagent chat in db
    ;; Note: presence of :agent-def indicates this is a subagent
    (let [max-turns (:max-turns agent-def 25)]
      (swap! db* assoc-in [:chats subagent-chat-id*]
             {:id subagent-chat-id*
              :parent-chat-id chat-id
              :agent-name agent-name
              :agent-def agent-def
              :max-turns max-turns
              :current-turn 0})

      ;; Require chat ns here to avoid circular dependency
      (let [chat-prompt (requiring-resolve 'eca.features.chat/prompt)
            task-prompt (build-task-prompt task max-turns)]
        ;; Start subagent execution
        (chat-prompt
         {:message task-prompt
          :chat-id subagent-chat-id*
          :model subagent-model
          :behavior behavior
          :contexts []}
         db*
         messenger
         config
         metrics)))

    ;; Wait for subagent to complete by polling status
    (let [stopped-result (fn []
                           (logger/info logger-tag (format "Agent '%s' stopped by parent chat" agent-name))
                           (stop-subagent-chat! db* messenger metrics subagent-chat-id* agent-name)
                           {:error true
                            :contents [{:type :text
                                        :text (format "Agent '%s' was stopped because the parent chat was stopped." agent-name)}]})]
      (try
        (loop []
          (let [status (get-in @db* [:chats subagent-chat-id* :status])]
            (cond
              ;; Parent chat stopped — propagate stop to subagent
              (= :stopping (:status (call-state-fn)))
              (stopped-result)

              ;; Subagent completed
              (#{:idle :error} status)
              (let [messages (get-in @db* [:chats subagent-chat-id* :messages] [])
                    summary (extract-final-summary messages)
                    turn-count (get-in @db* [:chats subagent-chat-id* :current-turn] 0)]
                (logger/info logger-tag (format "Agent '%s' completed after %d turns" agent-name turn-count))
                (swap! db* update :chats dissoc subagent-chat-id*)
                {:error false
                 :contents [{:type :text
                             :text (format "## Agent '%s' Result\n\n%s" agent-name summary)}]})

              ;; Keep waiting
              :else
              (do
                (Thread/sleep 1000)
                (recur)))))
        (catch InterruptedException _
          (stopped-result))))))

(defn ^:private build-description
  "Build tool description with available agents listed."
  [db config]
  (let [base-description (tools.util/read-tool-description "spawn_agent")
        agents (f.agents/all config (:workspace-folders db))
        agents-section (str "\n\nAvailable agents:\n"
                            (->> agents
                                 (map (fn [{:keys [name description]}]
                                        (str "- " name ": " description)))
                                 (str/join "\n")))]
    (str base-description agents-section)))

(defn definitions
  [db config]
  {"spawn_agent"
   {:description (build-description db config)
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
                    (f.agents/get-agent agent-name config (:workspace-folders db)))
        parent-model (get-in db [:chats chat-id :model])
        subagent-model (or (:model agent-def) parent-model)]
    {:type :subagent
     :subagent-chat-id (when tool-call-id
                         (subagent-chat-id tool-call-id))
     :model subagent-model}))