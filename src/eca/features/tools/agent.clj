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

(def ^:private poll-interval-ms
  "How often the subagent chat status is checked while waiting for it."
  1000)

(def ^:private settle-poll-ms
  "How often a stopped or finishing subagent is checked while waiting for it to settle."
  50)

(def ^:private stop-settle-timeout-ms
  "Max time to wait for a subagent stopped with its parent to unwind. Short, as
   the parent's stop waits for it, and only replay completeness depends on it."
  (* 5 1000))

(def ^:private summary-turn-timeout-ms
  "Max time to wait for a halted subagent to settle and write its final summary."
  (* 2 60 1000))

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
                  :timeout-seconds (let [timeout (:timeoutSeconds agent-config)]
                                     (when (and (number? timeout) (pos? timeout))
                                       (long timeout)))
                  :system-prompt (:systemPrompt agent-config)
                  :tool-call (:toolCall agent-config)})))
       vec))

(defn ^:private get-agent
  [agent-name config parent-agent-name]
  (first (filter #(= agent-name (:name %)) (all-agents config parent-agent-name))))

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

(defn ^:private failure-guidance
  "Actionable next-step hint for the parent agent based on the error type."
  [error-type]
  (when error-type
    (if (contains? llm-providers.errors/retryable-error-types error-type)
      "This is a transient provider error. Continue this agent with the returned `chat_id` and the same agent (optionally with a different `model`) instead of performing the task yourself."
      "Retrying this agent the same way is unlikely to help. Consider continuing it with the returned `chat_id` and a different `model`, or handling the task yourself.")))

(defn ^:private text-result [error? text]
  {:error error?
   :contents [{:type :text :text text}]})

(defn ^:private failed-agent-result [agent-name prompt-error partial-output]
  (let [{:keys [message error-type status code request-id response-id rate-limit-resets-at]} prompt-error]
    (text-result true (str "## Agent '" agent-name "' Failed\n\n"
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
                              (str "\n\n## Partial result\n\n" partial-output))))))

(defn ^:private ->subagent-chat-id
  "Generate a deterministic subagent chat id from the tool-call-id."
  [tool-call-id]
  (str "subagent-" tool-call-id))

(defn ^:private send-step-progress!
  "Send a toolCallRunning notification with current step progress to the parent chat."
  [{:keys [messenger chat-id tool-call-id agent-name subagent subagent-chat-id arguments]} db step]
  (let [child (get-in db [:chats subagent-chat-id])
        activity (get arguments "activity")]
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
                :details (shared/assoc-some {:type :subagent
                                             :subagent-chat-id subagent-chat-id
                                             :model (:model child)
                                             :agent-name agent-name
                                             :step step
                                             :max-steps (:max-steps subagent)}
                                            :variant (:variant child))}})))

(defn ^:private stop-subagent-chat!
  "Stop a running subagent chat silently (parent already shows 'Prompt stopped')."
  [{:keys [db* messenger config metrics subagent-chat-id agent-name]}]
  (let [prompt-stop (requiring-resolve 'eca.features.chat/prompt-stop)]
    (try
      (prompt-stop {:chat-id subagent-chat-id} db* messenger config metrics {:silent? true})
      (catch Exception e
        (logger/warn logger-tag (format "Error stopping subagent '%s': %s" agent-name (.getMessage e)))))))

(defn ^:private prompt-subagent!
  "Sends `message` to the subagent chat, claimed by this run's token."
  [{:keys [db* messenger config metrics subagent-chat-id agent-name model variant trust token]} message]
  ;; Resolved at runtime to avoid a circular dependency with the chat ns.
  ((requiring-resolve 'eca.features.chat/prompt)
   (shared/assoc-some {:owner-token token
                       :chat-id subagent-chat-id
                       :model model
                       :agent agent-name
                       :contexts []
                       :trust trust
                       :message message}
                      :variant variant)
   db* messenger config metrics))

(defn ^:private summary-turn-prompt [reason]
  (str reason " Tool calls are no longer allowed.\n\n"
       "Without calling any tools, reply now with your final report: what you found so far, "
       "with concrete evidence like file paths, what you did not get to, and any open questions."))

(defn ^:private settled?
  "True when the subagent has no running turn and no prompt worker still
   unwinding, so nothing else can write to its history."
  [db subagent-chat-id]
  (and (#{:idle :error} (get-in db [:chats subagent-chat-id :status]))
       (zero? (get-in db [:managed-chats subagent-chat-id :workers] 0))))

(defn ^:private await-settled!
  "Waits until the subagent is `settled?` or `deadline` (epoch ms) passes.
   Returns true when it settled."
  [db* subagent-chat-id deadline]
  (loop []
    (cond
      (settled? @db* subagent-chat-id) true
      (< (System/currentTimeMillis) (long deadline)) (do (Thread/sleep (long settle-poll-ms))
                                                         (recur))
      :else false)))

(defn ^:private run-summary-turn!
  "Prompts the subagent one last time, with tool calls refused, so it reports what
   it found. A stopped turn may still be unwinding, so waits for the subagent to
   settle first, and again after the summary turn, so it can be continued right
   away. Both waits share `summary-turn-timeout-ms`; when it passes, the summary is
   skipped or the subagent stopped."
  [{:keys [db* chat-id subagent-chat-id agent-name] :as run} reason]
  (logger/with-chat-context subagent-chat-id chat-id
    (let [deadline (+ (System/currentTimeMillis) (long summary-turn-timeout-ms))]
      (if-not (await-settled! db* subagent-chat-id deadline)
        (logger/warn logger-tag (format "Agent '%s' did not stop in time, skipping its final summary" agent-name))
        (do
          (logger/info logger-tag (format "Requesting final summary from agent '%s'" agent-name))
          (swap! db* assoc-in [:chats subagent-chat-id :summary-requested?] true)
          (prompt-subagent! run (summary-turn-prompt reason))
          (when-not (await-settled! db* subagent-chat-id deadline)
            (logger/warn logger-tag (format "Agent '%s' did not finish its final summary in time, stopping it" agent-name))
            (stop-subagent-chat! run)))))))

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

(defn ^:private validate-model! [db user-model]
  (let [available-models (:models db)]
    (when (and user-model
               (seq available-models)
               (not (contains? available-models user-model)))
      (throw (ex-info (format "Model '%s' is not available. Available models: %s"
                              user-model
                              (str/join ", " (available-model-names db)))
                      {:model user-model
                       :available (available-model-names db)})))))

(defn ^:private validate-variant!
  "Rejects a variant only when the model has configured variants and it isn't
   among them. Models with no configured variants accept any variant (the LLM
   API will reject if invalid)."
  [config db model user-variant]
  (when user-variant
    (let [valid-variants (model-variant-names config db model)]
      (when (and (seq valid-variants)
                 (not (some #{user-variant} valid-variants)))
        (throw (ex-info (format "Variant '%s' is not available for model '%s'. Available variants: %s"
                                user-variant model (str/join ", " valid-variants))
                        {:variant user-variant
                         :model model
                         :available valid-variants}))))))

(defn ^:private new-model+variant
  "[model variant] for a new subagent: explicit args first, then the agent config,
   then the parent's model. The agent's :defaultModel may be a bare alias resolved
   against the parent's provider; it is kept verbatim if it doesn't resolve."
  [db subagent parent-chat-id user-model user-variant]
  (let [parent-model (get-in db [:chats parent-chat-id :model])
        parent-provider (some-> parent-model shared/full-model->provider+model first)]
    [(or user-model
         (when-let [agent-model (:model subagent)]
           (or (models/full-model-for db parent-provider agent-model)
               agent-model))
         parent-model)
     (or user-variant (:variant subagent))]))

(defn ^:private continued-model+variant
  "[model variant] for a continued subagent: explicit args first, else what it
   used last. A new model gets the agent's configured variant instead of the
   old one, which may not exist for it."
  [child subagent user-model user-variant]
  (let [model (or user-model (:model child))]
    [model (or user-variant
               (if (= model (:model child))
                 (:variant child)
                 (:variant subagent)))]))

(defn ^:private owned-subagent
  "The chat of `subagent-chat-id` when `parent-chat-id` spawned it with
   `agent-name` in this server session, else nil."
  [db subagent-chat-id parent-chat-id agent-name]
  (let [child (get-in db [:chats subagent-chat-id])]
    (when (and (contains? (:managed-chats db) subagent-chat-id)
               (= parent-chat-id (:parent-chat-id child))
               (= agent-name (:agent-name child)))
      child)))

(defn ^:private admit-new
  "Registers the chat of a new subagent, claimed by this run's `token`."
  [db {:keys [subagent-chat-id chat-id agent-name subagent model variant trust token]}]
  (when (contains? (:chats db) subagent-chat-id)
    (throw (ex-info "Subagent chat ID already exists." {:chat-id subagent-chat-id})))
  (-> db
      (assoc-in [:managed-chats subagent-chat-id] {:token token :workers 0})
      (assoc-in [:chats subagent-chat-id]
                (shared/assoc-some {:id subagent-chat-id
                                    :parent-chat-id chat-id
                                    :agent-name agent-name
                                    :subagent subagent
                                    :model model
                                    :trust trust
                                    :current-step 0}
                                   :variant variant
                                   :max-steps (:max-steps subagent)))))

(defn ^:private admit-resume
  "Claims a settled subagent of this parent for another run, with this run's
   `token`. Its limits follow the current agent config, with a fresh budget."
  [db {:keys [subagent-chat-id chat-id agent-name subagent token]}]
  (let [child (owned-subagent db subagent-chat-id chat-id agent-name)]
    (when-not (and child
                   (not (get-in db [:managed-chats subagent-chat-id :token]))
                   (settled? db subagent-chat-id)
                   (not-any? #(or (:future %) (seq (:resources %))) (vals (:tool-calls child))))
      ;; The parent LLM only knows its conversation, so say it in those terms.
      (throw (ex-info (format (str "chat_id '%s' cannot be continued with agent '%s'. It must come from an earlier "
                                   "spawn_agent result of this agent that has finished, and older subagents may "
                                   "no longer be available. Omit chat_id to spawn a new subagent instead.")
                              subagent-chat-id agent-name)
                      {:chat-id subagent-chat-id}))))
  (-> db
      (update-in [:managed-chats subagent-chat-id] #(-> % (assoc :token token) (dissoc :interrupted?)))
      ;; Clear how the previous run ended.
      (update-in [:chats subagent-chat-id] #(-> (dissoc % :max-steps-reached? :summary-requested? :prompt-error
                                                        :prompt-finished? :follow-up-active?)
                                               (assoc :subagent subagent
                                                      :max-steps (:max-steps subagent)
                                                      :current-step 0)))))

(defn ^:private release
  "Ends this run's claim on the subagent and records the part of its chat that
   this call ran, for replay. A stopped subagent may still append a few messages
   after this; they are not replayed."
  [db {:keys [chat-id tool-call-id subagent-chat-id start-count]}]
  (-> db
      (update-in [:managed-chats subagent-chat-id] dissoc :token)
      (assoc-in [:chats chat-id :tool-calls tool-call-id :subagent-message-range]
                [start-count (count (get-in db [:chats subagent-chat-id :messages]))])))

(defn ^:private task-message
  "The task as sent to the subagent."
  [task max-steps after-summary?]
  (cond-> task
    ;; The summary turn told it that tools are no longer allowed.
    after-summary?
    (str "\n\nTool calls are allowed again.")

    max-steps
    (str (format "\n\nIMPORTANT: You have a maximum of %d steps to complete this task. Be efficient and provide a clear summary of your findings before reaching the limit."
                 max-steps))))

(defn ^:private start-run!
  "Prompts the subagent with its task. A prompt that fails before it starts
   marks the subagent as failed, so the wait ends at once."
  [{:keys [db* subagent-chat-id] :as run} message]
  (when (= :error (:status (prompt-subagent! run message)))
    (swap! db* update-in [:chats subagent-chat-id]
           #(assoc % :status :error :prompt-error
                   (or (:prompt-error %) {:message "Subagent prompt setup failed."})))))

(defn ^:private run-output
  "The final assistant text of this run only, so an earlier run's answer never
   leaks into its result."
  [{:keys [db* subagent-chat-id start-count]}]
  (extract-final-assistant-text
   (drop start-count (get-in @db* [:chats subagent-chat-id :messages] []))))

(defn ^:private stopped-result [{:keys [db* subagent-chat-id agent-name] :as run}]
  (logger/info logger-tag (format "Agent '%s' stopped by parent chat" agent-name))
  (stop-subagent-chat! run)
  ;; A cancelled tool still appends its result; wait for it, so this call's replay
  ;; range includes it and the subagent can be continued right away.
  (await-settled! db* subagent-chat-id (+ (System/currentTimeMillis) (long stop-settle-timeout-ms)))
  (text-result true (str (format "Agent '%s' was stopped because the parent chat was stopped." agent-name)
                         (when-let [output (run-output run)]
                           (str "\n\n## Partial result\n\n" output)))))

(defn ^:private timed-out-result [{:keys [agent-name subagent] :as run}]
  (let [timeout-seconds (:timeout-seconds subagent)]
    (logger/info logger-tag (format "Agent '%s' timed out after %ds" agent-name timeout-seconds))
    (stop-subagent-chat! run)
    (run-summary-turn! run (format "You reached your time limit of %d seconds and your work was interrupted." timeout-seconds))
    (text-result true (format "## Agent '%s' Timed out\n\nAgent was stopped because it reached its timeout (%ds). The result below may be incomplete.\n\n%s"
                              agent-name timeout-seconds
                              (or (run-output run) "Agent produced no output before timing out.")))))

(defn ^:private settled-result [{:keys [db* subagent-chat-id agent-name subagent] :as run} step]
  (let [db @db*
        {:keys [status prompt-error max-steps-reached?]} (get-in db [:chats subagent-chat-id])
        max-steps (:max-steps subagent)
        output (run-output run)
        failure (cond
                  (or prompt-error (= :error status)) (or prompt-error {})
                  ;; Only interrupted, e.g. the user stopped the subagent chat.
                  (get-in db [:managed-chats subagent-chat-id :interrupted?])
                  {:message "The sub-agent was stopped before it finished."})]
    (cond
      max-steps-reached?
      (do
        (logger/info logger-tag (format "Agent '%s' halted after reaching max steps (%d)" agent-name max-steps))
        (run-summary-turn! run (format "You reached your maximum number of steps (%d)." max-steps))
        (text-result true (format "## Agent '%s' Halted\n\nAgent was halted because it reached the maximum number of steps (%d). The result below may be incomplete.\n\n%s"
                                  agent-name max-steps
                                  (or (run-output run) "Agent completed without producing output."))))

      failure
      (do
        (logger/warn logger-tag (format "Agent '%s' failed after %d steps: %s" agent-name step (:message failure)))
        (failed-agent-result agent-name failure output))

      :else
      (do
        (logger/info logger-tag (format "Agent '%s' completed after %d steps" agent-name step))
        (text-result false (format "## Agent '%s' Result\n\n%s"
                                   agent-name (or output "Agent completed without producing output.")))))))

(defn ^:private await-outcome
  "Polls the subagent, sending step progress, until the parent stops, the
   subagent settles or its running turn passes the deadline.
   Returns [outcome last-step], outcome being :stopped, :settled or :timed-out."
  [{:keys [db* subagent-chat-id call-state-fn deadline] :as run}]
  (loop [last-step 0]
    (let [db @db*
          status (get-in db [:chats subagent-chat-id :status])
          step (get-in db [:chats subagent-chat-id :current-step] 0)]
      (when (> step last-step)
        (send-step-progress! run db step))
      (cond
        (= :stopping (:status (call-state-fn))) [:stopped step]
        (settled? db subagent-chat-id) [:settled step]
        ;; A turn that already finished is only unwinding, so let it complete.
        (and deadline
             (= :running status)
             (>= (System/currentTimeMillis) (long deadline))) [:timed-out step]
        :else (do (Thread/sleep (long poll-interval-ms))
                  (recur (long (max last-step step))))))))

(defn ^:private await-result [{:keys [db* chat-id tool-call-id] :as run}]
  (try
    (let [[outcome step] (await-outcome run)]
      (when-not (= :stopped outcome)
        (swap! db* assoc-in [:chats chat-id :tool-calls tool-call-id :subagent-final-step] step))
      (case outcome
        :stopped (stopped-result run)
        :settled (settled-result run step)
        :timed-out (timed-out-result run)))
    (catch InterruptedException _
      (stopped-result run))))

(defn ^:private spawn-agent
  "Handler for the spawn_agent tool.
   Runs a focused task in a new or existing subagent conversation and returns the result."
  [arguments {:keys [db* config chat-id tool-call-id call-state-fn agent] :as ctx}]
  (let [arguments (normalize-arguments arguments)
        {agent-name "agent" task "task" resume-id "chat_id"
         user-model "model" user-variant "variant"} arguments
        db @db*
        _ (when (get-in db [:chats chat-id :subagent])
            (throw (ex-info "Agents cannot spawn other agents (nesting not allowed)"
                            {:agent-name agent-name
                             :parent-chat-id chat-id})))
        ;; `agent` in the context is the parent's agent.
        subagent (or (get-agent agent-name config agent)
                     (let [available (map :name (all-agents config agent))]
                       (throw (ex-info (format "Agent not found or not available. Available agents: %s"
                                               (if (seq available) (str/join ", " available) "none"))
                                       {:agent-name agent-name
                                        :available available}))))
        subagent-chat-id (or resume-id (->subagent-chat-id tool-call-id))
        child (get-in db [:chats subagent-chat-id])
        _ (validate-model! db user-model)
        [model variant] (if resume-id
                          (continued-model+variant child subagent user-model user-variant)
                          (new-model+variant db subagent chat-id user-model user-variant))
        _ (validate-variant! config db model user-variant)
        run (assoc ctx
                   :arguments arguments
                   :agent-name agent-name
                   :subagent subagent
                   :subagent-chat-id subagent-chat-id
                   :model model
                   :variant variant
                   :token (Object.)
                   :deadline (when-let [seconds (:timeout-seconds subagent)]
                               (+ (System/currentTimeMillis) (* 1000 (long seconds)))))
        admitted-db (swap! db* (if resume-id admit-resume admit-new) run)
        run (assoc run :start-count (count (get-in admitted-db [:chats subagent-chat-id :messages])))]
    (logger/with-chat-context chat-id (get-in db [:chats chat-id :parent-chat-id])
      (-> (try
            (logger/info logger-tag (format "Running agent '%s' for task: %s (model: %s, variant: %s)"
                                            agent-name task model (or variant "default")))
            ;; `child` is read before admission, which clears the summary flag.
            (start-run! run (task-message task (:max-steps subagent) (:summary-requested? child)))
            (await-result run)
            (catch Exception e
              (when (or (instance? InterruptedException e)
                        (= :stopping (:status (call-state-fn))))
                (stop-subagent-chat! run))
              (failed-agent-result agent-name {:message (ex-message e)} nil))
            (finally
              (swap! db* release run)))
          ;; The chat_id goes first, so output truncation keeps it.
          (update-in [:contents 0 :text] #(str "Subagent chat_id: " subagent-chat-id "\n\n" %))))))

(defn replayed-messages
  "The part of a subagent chat's `messages` that one spawn_agent call ran, from
   the call's tool-call `details`. A continued subagent runs over several calls,
   so each call replays only its own part. Histories from before continuation
   have no range and replay all."
  [messages {:keys [details]}]
  (if-let [[start end] (:subagent-message-range details)]
    (take (- end start) (drop start messages))
    messages))

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
                                            :description "Optional chat_id returned by an earlier spawn_agent call of this chat, to continue that subagent conversation. Repeat its agent. It keeps its model and variant unless you override them."}
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
        subagent (when agent-name
                   (get-agent agent-name config (get-in db [:chats chat-id :agent])))
        resume-id (get arguments "chat_id")
        resume? (some? resume-id)
        child (when (and resume? subagent)
                (owned-subagent db resume-id chat-id agent-name))
        [model variant] (if resume?
                          (continued-model+variant child subagent (get arguments "model") (get arguments "variant"))
                          (new-model+variant db subagent chat-id (get arguments "model") (get arguments "variant")))]
    (shared/assoc-some {:type :subagent
                        :subagent-chat-id (if resume?
                                            (:id child)
                                            (some-> tool-call-id ->subagent-chat-id))
                        :model model
                        :agent-name agent-name
                        :step 1
                        :max-steps (:max-steps subagent)}
                       :variant variant)))

(defmethod tools.util/tool-call-details-after-invocation :spawn_agent
  [_name _arguments before-details _result {:keys [db chat-id tool-call-id]}]
  (let [{:keys [subagent-final-step subagent-message-range]} (get-in db [:chats chat-id :tool-calls tool-call-id])
        child (get-in db [:chats (:subagent-chat-id before-details)])]
    (cond-> (assoc before-details
                   :step (or subagent-final-step (:step before-details) 1)
                   ;; Replay shows only this part of the subagent chat; none when the call did not run.
                   :subagent-message-range (or subagent-message-range [0 0]))
      ;; The subagent ran, so report the model it really used.
      subagent-final-step (shared/assoc-some :model (:model child) :variant (:variant child)))))
