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

(defn ^:private spawn-agent
  "Handler for the spawn_agent tool.
   Spawns a subagent to perform a focused task and returns the result."
  [arguments {:keys [db* config messenger metrics chat-id behavior tool-call-id]}]
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
    ;; TODO: In future, use a proper callback/promise mechanism
    (loop [wait-count 0]
      (let [status (get-in @db* [:chats subagent-chat-id* :status])]
        (cond
          ;; Completed
          (#{:idle :error} status)
          (let [messages (get-in @db* [:chats subagent-chat-id* :messages] [])
                summary (extract-final-summary messages)
                turn-count (get-in @db* [:chats subagent-chat-id* :current-turn] 0)]
            (logger/info logger-tag (format "Agent '%s' completed after %d turns" agent-name turn-count))
            ;; Cleanup subagent chat
            (swap! db* update :chats dissoc subagent-chat-id*)
            {:error false
             :contents [{:type :text
                         :text (format "## Agent '%s' Result\n\n%s" agent-name summary)}]})

          ;; Timeout after ~5 minutes
          (> wait-count 300)
          (do
            (logger/warn logger-tag (format "Agent '%s' timed out" agent-name))
            (swap! db* update :chats dissoc subagent-chat-id*)
            {:error true
             :contents [{:type :text
                         :text (format "Agent '%s' timed out after 10 minutes" agent-name)}]})

          ;; Keep waiting
          :else
          (do
            (Thread/sleep 1000)
            (recur (inc wait-count))))))))

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
  [_name _arguments _server {:keys [tool-call-id]}]
  (when tool-call-id
    {:type :subagent
     :subagent-chat-id (subagent-chat-id tool-call-id)}))
