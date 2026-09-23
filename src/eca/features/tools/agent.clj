(ns eca.features.tools.agent
  "Tool for spawning or continuing subagents to perform focused tasks in isolated context."
  (:require
   [clojure.string :as str]
   [eca.config :as config]
   [eca.features.tools.util :as tools.util]
   [eca.llm-providers.errors :as llm-providers.errors]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.models :as models]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[AGENT-TOOL]")
(def ^:private activity-summary-max-length 40)

(defn normalize-arguments
  "Normalize spawn_agent arguments before display, history, and invocation."
  [arguments]
  (let [activity (when (string? (get arguments "activity"))
                   (-> (get arguments "activity")
                       str/trim
                       (str/replace #"\s+" " ")))]
    (if (str/blank? activity)
      (dissoc arguments "activity")
      (assoc arguments "activity" (if (> (count activity) activity-summary-max-length)
                                    (str (subs activity 0 activity-summary-max-length) "...")
                                    activity)))))

(defn ^:private all-agents
  [config parent-agent-name]
  (->> (config/available-subagents config parent-agent-name)
       (keep (fn [[agent-name agent-config]]
               (when (:description agent-config)
                 {:name agent-name
                  :description (:description agent-config)
                  :model (:defaultModel agent-config)
                  :variant (:variant agent-config)
                  :max-steps (:maxSteps agent-config)
                  :system-prompt (:systemPrompt agent-config)
                  :tool-call (:toolCall agent-config)})))
       vec))

(defn ^:private get-agent
  [agent-name config parent-agent-name]
  (first (filter #(= agent-name (:name %)) (all-agents config parent-agent-name))))

(defn ^:private max-steps [subagent]
  (:max-steps subagent))

(defn ^:private extract-final-assistant-text
  "Extracts text from the final assistant message, or nil when none exists."
  [messages]
  (some->> messages
           (filter #(= "assistant" (:role %)))
           (map :content)
           (filter seq)
           last
           (filter #(= :text (:type %)))
           (map :text)
           (str/join "\n")
           not-empty))

(defn ^:private extract-final-summary
  "Extract the final assistant message as summary from chat messages."
  [messages]
  (or (extract-final-assistant-text messages)
      "Agent completed without producing output."))

(defn ^:private failure-guidance
  "Actionable next-step hint for the parent agent based on the error type."
  [error-type]
  (when error-type
    (if (contains? llm-providers.errors/retryable-error-types error-type)
      "This is a transient provider error. Continue this agent using the returned `chat_id` and the same agent, without model or variant overrides, instead of performing the task yourself."
      "Retrying this agent the same way is unlikely to help. Consider spawning it again with a different `model` or handling the task yourself.")))

(defn ^:private failed-agent-result [agent-name prompt-error partial-output]
  (let [{:keys [message error-type status code request-id response-id rate-limit-resets-at]} prompt-error]
    {:error true
     :contents [{:type :text
                 :text (str "## Agent '" agent-name "' Failed\n\n"
                            (or message "The sub-agent prompt failed.")
                            (when error-type
                              (str "\n\nError type: " (name error-type)))
                            (when status
                              (str "\nStatus: " status))
                            (when code
                              (str "\nCode: " code))
                            (when request-id
                              (str "\nRequest ID: " request-id))
                            (when response-id
                              (str "\nResponse ID: " response-id))
                            (when rate-limit-resets-at
                              (str "\nRate limit resets at: " (java.time.Instant/ofEpochMilli (long rate-limit-resets-at))))
                            (when-let [guidance (failure-guidance error-type)]
                              (str "\n\n" guidance))
                            (when partial-output
                              (str "\n\n## Partial result\n\n" partial-output)))}]}))

(defn ^:private ->subagent-chat-id
  "Generate a deterministic subagent chat id from the tool-call-id."
  [tool-call-id]
  (str "subagent-" tool-call-id))

(defn ^:private send-step-progress!
  "Send a toolCallRunning notification with current step progress to the parent chat."
  [messenger chat-id tool-call-id agent-name activity subagent-chat-id step max-steps model variant arguments]
  (messenger/chat-content-received
   messenger
   {:chat-id chat-id
    :role :assistant
    :content {:type :toolCallRunning
              :id tool-call-id
              :name "spawn_agent"
              :server "eca"
              :origin "native"
              :summary (if activity
                         (format "%s: %s" agent-name activity)
                         agent-name)
              :arguments arguments
              :details (cond-> {:type :subagent
                                :subagent-chat-id subagent-chat-id
                                :model model
                                :agent-name agent-name
                                :step step
                                :max-steps max-steps}
                         variant (assoc :variant variant))}}))

(defn ^:private stop-subagent-chat!
  "Stop a running subagent chat silently (parent already shows 'Prompt stopped')."
  [db* messenger config metrics subagent-chat-id agent-name]
  (let [prompt-stop (requiring-resolve 'eca.features.chat/prompt-stop)]
    (try
      (prompt-stop {:chat-id subagent-chat-id} db* messenger config metrics {:silent? true})
      (catch Exception e
        (logger/warn logger-tag (format "Error stopping subagent '%s': %s" agent-name (.getMessage e)))))))

(defn ^:private available-model-names
  "Returns a sorted list of available model names from the runtime db."
  [db]
  (some->> (:models db)
           keys
           sort
           vec))

(defn ^:private model-variant-names
  "Returns sorted variant names for a specific full model string (e.g. \"anthropic/claude-sonnet-4-6\")."
  [config db ^String full-model]
  (when full-model
    (let [idx (.indexOf full-model "/")]
      (when (pos? idx)
        (let [provider (subs full-model 0 idx)
              model (subs full-model (inc idx))
              model-capabilities (get-in db [:models full-model])
              user-variants (get-in config [:providers provider :models model :variants])
              variants (config/effective-model-variants config provider model model-capabilities user-variants)]
          (config/selectable-variant-names variants))))))

(defn ^:private resumable-run
  [db id parent-id agent-name config trust]
  (let [run (get-in db [:subagent-runs id])
        child (get-in db [:chats id])]
    (when-not (and run (:subagent child)
                   (= parent-id (:parent-chat-id child) (:parent-chat-id run))
                   (= agent-name (:agent-name child) (:agent-name run)))
      (throw (ex-info "chat_id must name a live subagent owned by this parent with the same agent." {})))
    (when (or (:token run)
              (pos? (:workers run 0))
              (some #(or (:future %) (seq (:resources %))) (vals (:tool-calls child)))
              (not (#{:idle :error} (:status child))))
      (throw (ex-info "Subagent is busy or unsettled and cannot be resumed." {})))
    (when-not (and (= (:config-hash run) (hash config))
                   (= (:workspace-folders run) (:workspace-folders db))
                   (= (:trust run) trust (:trust child)))
      (throw (ex-info "Subagent config, workspace, or trust changed; spawn a new agent." {})))
    run))

(defn ^:private spawn-agent
  "Handler for the spawn_agent tool.
   Runs a focused task in a new or existing subagent conversation and returns the result."
  [arguments {:keys [db* config messenger metrics chat-id tool-call-id call-state-fn trust agent]}]
  (let [arguments (normalize-arguments arguments)
        agent-name (get arguments "agent")
        task (get arguments "task")
        activity (get arguments "activity")
        parent-agent-name agent
        db @db*

        ;; Check for nesting - prevent subagents from spawning other subagents
        _ (when (get-in db [:chats chat-id :subagent])
            (throw (ex-info "Agents cannot spawn other agents (nesting not allowed)"
                            {:agent-name agent-name
                             :parent-chat-id chat-id})))

        subagent (get-agent agent-name config parent-agent-name)
        _ (when-not subagent
            (let [available (all-agents config parent-agent-name)]
              (throw (ex-info (format "Agent not found or not available. Available agents: %s"
                                      (if (seq available)
                                        (str/join ", " (map :name available))
                                        "none"))
                              {:agent-name agent-name
                               :available (map :name available)}))))

        resume? (contains? arguments "chat_id")
        subagent-chat-id (if resume? (get arguments "chat_id") (->subagent-chat-id tool-call-id))
        _ (when (and resume? (or (not (string? subagent-chat-id)) (str/blank? subagent-chat-id)))
            (throw (ex-info "chat_id must be a nonblank string." {})))
        _ (when (and resume? (some #(contains? arguments %) ["model" "variant"]))
            (throw (ex-info "model and variant overrides are not allowed when resuming." {})))
        run (when resume? (resumable-run db subagent-chat-id chat-id agent-name config trust))

        user-model (get arguments "model")
        _ (when user-model
            (let [available-models (:models db)]
              (when (and (seq available-models)
                         (not (contains? available-models user-model)))
                (throw (ex-info (format "Model '%s' is not available. Available models: %s"
                                        user-model
                                        (str/join ", " (available-model-names db)))
                                {:model user-model
                                 :available (available-model-names db)})))))

        parent-model (get-in db [:chats chat-id :model])
        parent-provider (some-> parent-model shared/full-model->provider+model first)
        ;; The agent's :defaultModel may be a bare alias resolved against the
        ;; currently selected (parent) provider; keep it verbatim if it doesn't resolve.
        subagent-model (if resume?
                         (:model run)
                         (or user-model
                             (when-let [agent-model (:model subagent)]
                               (or (models/full-model-for db parent-provider agent-model)
                                   agent-model))
                             parent-model))

        ;; Variant validation: reject only when the resolved model has configured
        ;; variants and the user-specified one isn't among them. Models with no
        ;; configured variants accept any variant (the LLM API will reject if invalid).
        user-variant (get arguments "variant")
        _ (when user-variant
            (let [valid-variants (model-variant-names config db subagent-model)]
              (when (and (seq valid-variants)
                         (not (some #{user-variant} valid-variants)))
                (throw (ex-info (format "Variant '%s' is not available for model '%s'. Available variants: %s"
                                        user-variant subagent-model (str/join ", " valid-variants))
                                {:variant user-variant
                                 :model subagent-model
                                 :available valid-variants})))))
        variant (if resume? (:variant run) (or user-variant (:variant subagent)))
        token (Object.)
        [before _] (swap-vals!
                    db*
                    (fn [db]
                      (if resume?
                        (do (resumable-run db subagent-chat-id chat-id agent-name config trust)
                            (-> db
                                (update-in [:subagent-runs subagent-chat-id]
                                           #(-> % (assoc :token token) (dissoc :interrupted?)))
                                (update-in [:chats subagent-chat-id]
                                           #(-> %
                                                (dissoc :max-steps-reached? :prompt-error :prompt-finished? :follow-up-active?)
                                                (assoc :current-step 0)))))
                        (do
                          (when (or (contains? (:chats db) subagent-chat-id)
                                    (contains? (:subagent-runs db) subagent-chat-id))
                            (throw (ex-info "Subagent chat ID already exists." {})))
                          (-> db
                              (assoc-in [:subagent-runs subagent-chat-id]
                                        {:parent-chat-id chat-id :agent-name agent-name
                                         :model subagent-model :variant variant :trust trust
                                         :config-hash (hash config) :workspace-folders (:workspace-folders db)
                                         :token token :workers 0})
                              (assoc-in [:chats subagent-chat-id]
                                        (cond-> {:id subagent-chat-id :parent-chat-id chat-id
                                                 :agent-name agent-name :subagent subagent
                                                 :trust trust :current-step 0}
                                          (max-steps subagent) (assoc :max-steps (max-steps subagent)))))))))
        starting-message-count (count (get-in before [:chats subagent-chat-id :messages]))
        max-steps-limit (get-in @db* [:chats subagent-chat-id :max-steps])]
    (logger/with-chat-context chat-id (get-in db [:chats chat-id :parent-chat-id])
      (update-in
       (try
        (logger/info logger-tag (format "Running agent '%s' for task: %s (model: %s, variant: %s)" agent-name task subagent-model (or variant "default")))
        ;; Require chat ns here to avoid circular dependency
        (let [chat-prompt (requiring-resolve 'eca.features.chat/prompt)
              task-prompt (if max-steps-limit
                            (format "%s\n\nIMPORTANT: You have a maximum of %d steps to complete this task. Be efficient and provide a clear summary of your findings before reaching the limit."
                                    task max-steps-limit)
                            task)
              prompt-result (chat-prompt
                             (cond-> {:message task-prompt
                                      :subagent-token token
                                      :chat-id subagent-chat-id
                                      :model subagent-model
                                      :agent agent-name
                                      :contexts []
                                      :trust trust}
                               variant (assoc :variant variant))
                             db* messenger config metrics)]
          (when (= :error (:status prompt-result))
            (swap! db* assoc-in [:subagent-runs subagent-chat-id :interrupted?] true)
            (swap! db* update-in [:chats subagent-chat-id]
                   #(assoc % :status :error :prompt-error
                           (or (:prompt-error %) {:message "Subagent prompt setup failed."})))))

        ;; Wait for subagent to complete by polling status
        (let [stopped-result (fn []
                               (logger/info logger-tag (format "Agent '%s' stopped by parent chat" agent-name))
                               (swap! db* assoc-in [:subagent-runs subagent-chat-id :interrupted?] true)
                               (stop-subagent-chat! db* messenger config metrics subagent-chat-id agent-name)
                               {:error true
                                :contents [{:type :text
                                            :text (format "Agent '%s' was stopped because the parent chat was stopped." agent-name)}]})]
          (try
            (loop [last-step 0]
              (let [db @db*
                    status (get-in db [:chats subagent-chat-id :status])
                    current-step (get-in db [:chats subagent-chat-id :current-step] 0)]
                ;; Send step progress when step advances
                (when (> current-step last-step)
                  (send-step-progress! messenger chat-id tool-call-id agent-name activity
                                       subagent-chat-id current-step max-steps-limit
                                       (get-in db [:chats subagent-chat-id :model] subagent-model)
                                       (get-in db [:chats subagent-chat-id :variant] variant) arguments))
                (cond
                  ;; Parent chat stopped — propagate stop to subagent
                  (= :stopping (:status (call-state-fn)))
                  (stopped-result)

                  ;; Subagent completed
                  (and (#{:idle :error} status)
                       (zero? (get-in db [:subagent-runs subagent-chat-id :workers] 0)))
                  (let [messages (drop starting-message-count (get-in db [:chats subagent-chat-id :messages] []))
                        summary (extract-final-summary messages)
                        partial-output (extract-final-assistant-text messages)
                        prompt-error (get-in db [:chats subagent-chat-id :prompt-error])
                        failed? (boolean (or (= :error status) prompt-error
                                             (get-in db [:subagent-runs subagent-chat-id :interrupted?])))
                        max-steps-reached? (get-in db [:chats subagent-chat-id :max-steps-reached?])]
                    (cond
                      max-steps-reached?
                      (logger/info logger-tag (format "Agent '%s' halted after reaching max steps (%d)" agent-name max-steps-limit))

                      failed?
                      (logger/warn logger-tag (format "Agent '%s' failed after %d steps: %s"
                                                      agent-name current-step (:message prompt-error)))

                      :else
                      (logger/info logger-tag (format "Agent '%s' completed after %d steps" agent-name current-step)))
                    (swap! db* update-in [:subagent-runs subagent-chat-id]
                           #(cond-> (merge % (select-keys (get-in db [:chats subagent-chat-id]) [:model :variant]))
                              failed? (assoc :interrupted? true)))
                    (swap! db* assoc-in [:chats chat-id :tool-calls tool-call-id :subagent-final-step] current-step)
                    (cond
                      max-steps-reached?
                      {:error true
                       :contents [{:type :text
                                   :text (format "## Agent '%s' Halted\n\nAgent was halted because it reached the maximum number of steps (%d). The result below may be incomplete.\n\n%s"
                                                 agent-name max-steps-limit summary)}]}

                      failed?
                      (failed-agent-result agent-name prompt-error partial-output)

                      :else
                      {:error false
                       :contents [{:type :text
                                   :text (format "## Agent '%s' Result\n\n%s" agent-name summary)}]}))

                  ;; Keep waiting
                  :else
                  (do
                    (Thread/sleep 1000)
                    (recur (long (max last-step current-step)))))))
            (catch InterruptedException _
              (stopped-result))))
        (catch Exception e
          (swap! db* assoc-in [:subagent-runs subagent-chat-id :interrupted?] true)
          (when (or (instance? InterruptedException e)
                    (= :stopping (:status (call-state-fn))))
            (stop-subagent-chat! db* messenger config metrics subagent-chat-id agent-name))
          (failed-agent-result agent-name {:message (ex-message e)} nil))
        (finally
          (swap! db* update-in [:subagent-runs subagent-chat-id] dissoc :token)))
       [:contents 0 :text] #(str "Subagent chat_id: " subagent-chat-id "\n\n" %)))))

(defn ^:private build-description
  "Build tool description with available agents and models listed."
  [config parent-agent-name]
  (let [base-description (tools.util/read-tool-description "spawn_agent")
        agents (all-agents config parent-agent-name)
        agents-section (str "\n\nAvailable agents:\n"
                            (->> agents
                                 (map (fn [{:keys [name description]}]
                                        (str "- " name ": " description)))
                                 (str/join "\n")))]
    (str base-description agents-section)))

(defn definitions
  ([config db]
   (definitions config db nil))
  ([config _db parent-agent-name]
   {"spawn_agent"
    {:description (build-description config parent-agent-name)
     :parameters  {:type       "object"
                   :properties {"agent"    {:type        "string"
                                            :description "Name of the agent to spawn or continue"}
                                "task"     {:type        "string"
                                            :description "The detailed instructions for the agent"}
                                "activity" {:type        "string"
                                            :description "Optional concise label (max 3-4 words) shown in the UI while the agent runs, e.g. \"exploring codebase\", \"reviewing changes\", \"analyzing tests\"."}
                                "chat_id"  {:type        "string"
                                            :minLength   1
                                            :description "Resume this live same-parent subagent conversation; repeat its agent and omit model/variant overrides."}
                                "model"    {:type        "string"
                                            :description "Optional sub-agent model override. Reserved for explicit user override only. Omit unless the user explicitly named a model."}
                                "variant"  {:type        "string"
                                            :description "Optional sub-agent model variant override. Reserved for explicit user override only. Omit unless the user explicitly named a variant."}}
                   :required   ["agent" "task"]}
     :handler     #'spawn-agent
     :summary-fn  (fn [{:keys [args]}]
                    (if-let [agent-name (get args "agent")]
                      (if-let [activity (get (normalize-arguments args) "activity")]
                        (format "%s: %s" agent-name activity)
                        agent-name)
                      "Spawning agent"))}}))

(defmethod tools.util/tool-call-details-before-invocation :spawn_agent
  [_name arguments _server {:keys [db config chat-id tool-call-id]}]
  (let [agent-name (get arguments "agent")
        user-model (get arguments "model")
        user-variant (get arguments "variant")
        parent-agent-name (get-in db [:chats chat-id :agent])
        subagent (when agent-name
                   (get-agent agent-name config parent-agent-name))
        parent-model (get-in db [:chats chat-id :model])
        resume? (contains? arguments "chat_id")
        subagent-chat-id (if resume? (get arguments "chat_id")
                            (when tool-call-id (->subagent-chat-id tool-call-id)))
        child (get-in db [:chats subagent-chat-id])
        owned? (and (string? subagent-chat-id) (not (str/blank? subagent-chat-id))
                    subagent (:subagent child)
                    (get-in db [:subagent-runs subagent-chat-id])
                    (= chat-id (:parent-chat-id child))
                    (= agent-name (:agent-name child)))
        child (when owned? child)
        subagent-model (if resume? (:model child)
                           (or user-model
                               (when-let [model (:model subagent)]
                                 (or (models/full-model-for db (some-> parent-model shared/full-model->provider+model first) model)
                                     model))
                               parent-model))
        variant (if resume? (:variant child) (or user-variant (:variant subagent)))]
    (cond-> {:type :subagent
             :subagent-chat-id (when (or (not resume?) owned?) subagent-chat-id)
             :model subagent-model
             :agent-name agent-name
             :step (get child :current-step 1)
             :max-steps (if resume? (:max-steps child) (max-steps subagent))}
      variant (assoc :variant variant))))

(defmethod tools.util/tool-call-details-after-invocation :spawn_agent
  [_name _arguments before-details _result {:keys [db chat-id tool-call-id]}]
  (let [final-step (get-in db [:chats chat-id :tool-calls tool-call-id :subagent-final-step]
                           (or (:step before-details) 1))
        child (get-in db [:chats (:subagent-chat-id before-details)])]
    (cond-> (assoc before-details :step final-step)
      (and (= chat-id (:parent-chat-id child))
           (get-in db [:chats chat-id :tool-calls tool-call-id :subagent-final-step]))
      (merge (select-keys child [:model :variant :max-steps])))))
