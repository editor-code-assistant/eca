(ns eca.features.chat.tool-calls
  (:require
   [clojure.string :as string]
   [eca.features.chat.lifecycle :as lifecycle]
   [eca.features.hooks :as f.hooks]
   [eca.features.tools :as f.tools]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.shared :refer [assoc-some]]))

(set! *warn-on-reflection* true)

(defn ^:private check-subagent-max-steps!
  "Check if subagent has reached max steps. Increments step count.
   Returns true if max steps reached, false otherwise.
   When max-steps is nil, the subagent runs with no step limit.
   Only applies to subagents (chats with :subagent)."
  [db* chat-id]
  (when-let [subagent (get-in @db* [:chats chat-id :subagent])]
    (let [max-steps (:max-steps subagent)
          new-db (swap! db* update-in [:chats chat-id :current-step] (fnil inc 0))
          new-step (get-in new-db [:chats chat-id :current-step])]
      (when max-steps
        (>= new-step max-steps)))))

(def ^:private logger-tag "[CHAT]")

(defn ^:private append-post-tool-additional-context!
  "Append additionalContext (wrapped as XML) from a postToolCall hook to the
   matching tool_call_output message so LLM sees it in the next round."
  [db* chat-id tool-call-id hook-name additional-context]
  (when (not (string/blank? additional-context))
    (let [entry {:type :text :text (lifecycle/wrap-additional-context hook-name additional-context)}]
      (swap! db* update-in [:chats chat-id :messages]
             ;; Optimized: Scans messages backwards since the tool output is likely one of the last items.
             #(let [idx (llm-util/find-last-msg-idx
                         (fn [msg]
                           (and (= "tool_call_output" (:role msg))
                                (= tool-call-id (get-in msg [:content :id]))))
                         %)]
                (if idx
                  (update-in % [idx :content :output :contents] conj entry)
                  %))))))

;;; Helper functions for tool call state management

(defn get-tool-call-state
  "Get the complete state map for a specific tool call."
  [db chat-id tool-call-id]
  (get-in db [:chats chat-id :tool-calls tool-call-id]))

(defn get-active-tool-calls
  "Returns a map of tool-call-id -> tool calls that are still active.

  Active tool calls are those not in the following states: :completed, :rejected."
  [db chat-id]
  (->> (get-in db [:chats chat-id :tool-calls] {})
       (remove (fn [[_ state]]
                 (#{:completed :rejected} (:status state))))
       (into {})))

(defn ^:private run-post-tool-call-hooks!
  "Run postToolCall hooks and append any additionalContext to the tool output."
  [db* chat-ctx tool-call-id event-data]
  (let [tool-call-state (get-tool-call-state @db* (:chat-id chat-ctx) tool-call-id)
        chat-id (:chat-id chat-ctx)]
    (f.hooks/trigger-if-matches!
     :postToolCall
     (merge (f.hooks/chat-hook-data @db* chat-id (:agent chat-ctx))
            {:tool-name (:name tool-call-state)
             :server (:server tool-call-state)
             :tool-input (:arguments tool-call-state)
             :tool-response (:outputs event-data)
             :error (:error event-data)})
     {:on-before-action (partial lifecycle/notify-before-hook-action! chat-ctx)
      :on-after-action (fn [{:keys [parsed name] :as result}]
                         ;; Always notify UI
                         (lifecycle/notify-after-hook-action! chat-ctx result)
                         ;; If hook provided additionalContext, append as XML to the tool output
                         (when-let [ac (:additionalContext parsed)]
                           (append-post-tool-additional-context!
                            (:db* chat-ctx)
                            (:chat-id chat-ctx)
                            tool-call-id
                            name
                            ac)))}
     @db*
     (:config chat-ctx))))

;;; Event-driven state machine for tool calls

(def ^:private tool-call-state-machine
  "State machine for tool call lifecycle management.

   Maps [current-status event] -> {:status new-status :actions [action-list]}

   Statuses:
   - :initial             - The initial status.  Ephemeral.
   - :preparing           - Preparing the arguments for the tool call.
   - :check-approval      - Checking to see if the tool call is approved, via config or asking the user.
   - :waiting-approval    - Waiting for user approval or rejection.
   - :execution-approved  - The tool call has been approved for execution, via config or asking the user.
   - :executing           - The tool call is executing.
   - :rejected            - Rejected before starting execution.  Terminal status.
   - :cleanup             - Cleaning up the state after finishing execution.  Either after normal execution or after being user stopped.
   - :completed           - Tool call completion.  Perhaps with tool errors.  With or without being interrupted. Terminal status.
   - :stopping            - In the process of stopping, after execution has started, but before it completed. After getting a :stop-request.

   Events:
   - :tool-prepare        - LLM preparing tool call (can happen multiple times).
   - :tool-run            - LLM ready to run tool call.
   - :user-approve        - User approves tool call.
   - :user-reject         - User rejects tool call.
   - :send-reject         - A made-up event to cause a toolCallReject.  Used in a context where the message data is available.
   - :execution-start     - Tool call execution begins.
   - :execution-end       - Tool call completes normally.  Perhaps with its own errors.
   - :cleanup-finished    - Cleaned up the state after tool call completes, either normally or interrupted.
   - :stop-requested      - An event to request that active tool calls be stopped.
   - :resources-created   - Some new resources were created during the call.
   - :resources-destroyed - Some existing resources were destroyed.
   - :stop-attempted      - We have done all we can to stop the tool call.  The tool may or may not be actually stopped.

   Actions:
   - send-* notifications
   - set-* set various state values
   - add- and remove-resources
   - init, delivery and removal of approval and future-cleanup promises
   - future cancellation
   - logging/metrics

   Note: All actions are run in the order specified.
   Note: The :send-* actions should be last, so that they have the latest values of the state context.
   Note: The :status is updated before any actions are run, so the actions are in the context of the latest :status.

   Note: all choices (i.e. conditionals) have to be made in code and result
   in different events being sent to the state machine.
   For example, from the :check-approval state you can either get
   a :approval-ask event, a :approval-allow event, or a :approval-deny event."
  {;; Note: transition-tool-call! treats no existing state as :initial state
   [:initial :tool-prepare]
   {:status :preparing
    :actions [:init-tool-call-state :send-toolCallPrepare]}

   [:preparing :tool-prepare]
   {:status :preparing
    :actions [:send-toolCallPrepare]} ; Multiple prepares allowed

   [:preparing :tool-run]
   {:status :check-approval
    :actions [:init-arguments :init-approval-promise :init-future-cleanup-promise :send-toolCallRun]}
   ;; All promises must be deref'ed.

   [:check-approval :approval-ask]
   {:status :waiting-approval
    :actions [:send-progress]}

   [:check-approval :approval-allow]
   {:status :execution-approved
    :actions [:set-decision-reason :deliver-approval-true]}

   [:check-approval :approval-deny]
   {:status :rejected
    :actions [:set-decision-reason :deliver-approval-false]}

   [:waiting-approval :user-approve]
   {:status :execution-approved
    :actions [:set-decision-reason :deliver-approval-true]}

   [:waiting-approval :hook-rejected]
   {:status :rejected
    :actions [:set-decision-reason :set-hook-continue :set-hook-stop-reason :deliver-approval-false]}

   [:waiting-approval :user-reject]
   {:status :rejected
    :actions [:set-decision-reason :deliver-approval-false :log-rejection]}

   [:rejected :send-reject]
   {:status :rejected
    :actions [:send-toolCallRejected]}

   [:execution-approved :hook-rejected]
   {:status :rejected
    :actions [:set-decision-reason :set-hook-continue :set-hook-stop-reason]}

   [:execution-approved :execution-start]
   {:status :executing
    :actions [:set-start-time :add-future :send-toolCallRunning :send-progress]}

   [:executing :execution-end]
   {:status :cleanup
    :actions [:save-execution-result :deliver-future-cleanup-completed :send-toolCalled :log-metrics :send-progress :trigger-post-tool-call-hook]}

   [:cleanup :cleanup-finished]
   {:status :completed
    :actions [:destroy-all-resources :remove-all-resources :remove-all-promises :remove-future]}

   [:executing :resources-created]
   {:status :executing
    :actions [:add-resources]}

   [:executing :resources-destroyed]
   {:status :executing
    :actions [:remove-resources]}

   [:stopping :resources-destroyed]
   {:status :stopping
    :actions [:remove-resources]}

   [:stopping :stop-attempted]
   {:status :cleanup
    :actions [:save-execution-result :deliver-future-cleanup-completed :send-toolCallRejected :trigger-post-tool-call-hook]}

   ;; And now all the :stop-requested transitions

   ;; Note: There are, currently, no transitions from the terminal statuses
   ;; on :stop-requested.
   ;; This is because :stop-requested is only sent to active statuses.
   ;; Also, we don't want to have transitions out from terminal states,
   ;; even if they are self-transitions.

   [:executing :stop-requested]
   {:status :stopping
    :actions [:cancel-future]}

   ;; ignore :stop-requested
   [:cleanup :stop-requested]
   {:status :cleanup
    :actions []}

   ;; ignore :stop-requested
   [:stopping :stop-requested]
   {:status :stopping
    :actions []}

   [:execution-approved :stop-requested]
   {:status :cleanup
    :actions [:send-toolCallRejected]}

   [:waiting-approval :stop-requested]
   {:status :rejected
    :actions [:set-decision-reason :deliver-approval-false]}

   [:check-approval :stop-requested]
   {:status :rejected
    :actions [:set-decision-reason :deliver-approval-false]}

   [:preparing :stop-requested]
   {:status :cleanup
    :actions [:set-decision-reason :send-toolCallRejected]}

   [:initial :stop-requested] ; Nothing sent yet, just mark as stopped
   {:status :cleanup
    :actions []}})

(defn ^:private execute-action!
  "Execute a single action during state transition"
  [action db* chat-ctx tool-call-id event-data]
  (case action
    ;; Notification actions
    :save-execution-result
    (swap! db* update-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id]
           merge
           (select-keys event-data [:outputs :error :total-time-ms]))

    :send-progress
    (lifecycle/send-content! chat-ctx :system
                             {:type :progress
                              :state :running
                              :text (:progress-text event-data)})

    :send-toolCallPrepare
    (lifecycle/send-content! chat-ctx :assistant
                             (assoc-some
                              {:type :toolCallPrepare
                               :id tool-call-id
                               :name (:name event-data)
                               :server (:server event-data)
                               :origin (:origin event-data)
                               :arguments-text (:arguments-text event-data)}
                              :summary (:summary event-data)))

    :send-toolCallRun
    (lifecycle/send-content! chat-ctx :assistant
                             (assoc-some
                              {:type :toolCallRun
                               :id tool-call-id
                               :name (:name event-data)
                               :server (:server event-data)
                               :origin (:origin event-data)
                               :arguments (:arguments event-data)
                               :manual-approval (:manual-approval event-data)}
                              :details (:details event-data)
                              :summary (:summary event-data)))

    :send-toolCallRunning
    (lifecycle/send-content! chat-ctx :assistant
                             (assoc-some
                              {:type :toolCallRunning
                               :id tool-call-id
                               :name (:name event-data)
                               :server (:server event-data)
                               :origin (:origin event-data)
                               :arguments (:arguments event-data)}
                              :details (:details event-data)
                              :summary (:summary event-data)))

    :send-toolCalled
    (lifecycle/send-content! chat-ctx :assistant
                             (assoc-some
                              {:type :toolCalled
                               :id tool-call-id
                               :origin (:origin event-data)
                               :name (:name event-data)
                               :server (:server event-data)
                               :arguments (:arguments event-data)
                               :error (:error event-data)
                               :total-time-ms (:total-time-ms event-data)
                               :outputs (:outputs event-data)}
                              :details (:details event-data)
                              :summary (:summary event-data)))

    :send-toolCallRejected
    (let [tool-call-state (get-tool-call-state @db* (:chat-id chat-ctx) tool-call-id)
          name (:name tool-call-state)
          server (:server tool-call-state)
          origin (:origin tool-call-state)
          arguments (:arguments tool-call-state)]
      (lifecycle/send-content! chat-ctx :assistant
                               (assoc-some
                                {:type :toolCallRejected
                                 :id tool-call-id
                                 :origin (or (:origin event-data) origin)
                                 :name (or (:name event-data) name)
                                 :server (or (:server event-data) server)
                                 :arguments (or (:arguments event-data) arguments)
                                 :reason (:code (:reason event-data) :user)}
                                :details (:details event-data)
                                :summary (:summary event-data))))

    :trigger-post-tool-call-hook
    (run-post-tool-call-hooks! db* chat-ctx tool-call-id event-data)

    ;; Actions on parts of the state
    :deliver-approval-false
    (deliver (get-in @db* [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :approved?*])
             false)

    :deliver-approval-true
    (deliver (get-in @db* [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :approved?*])
             true)

    :deliver-future-cleanup-completed
    (when-let [p (get-in @db* [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :future-cleanup-complete?*])]
      (deliver p true))

    :cancel-future
    (when-let [f (get-in @db* [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :future])]
      (future-cancel f))

    :destroy-all-resources
    (when-let [resources (get-in @db* [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :resources])]
      (when-not (empty? resources)
        (doseq [[resource-kwd resource] resources]
          (f.tools/tool-call-destroy-resource! (:full-name event-data) resource-kwd resource))))

    ;; State management actions
    :init-tool-call-state
    (swap! db* update-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id] assoc
           ;; :status (keyword) is initialized by the state transition machinery
           ;; :approved?* (promise) is initialized by the :init-approval-promise action
           ;; :future-cleanup-complete?* (promise) is initialized by the :init-future-cleanup-promise action
           ;; :arguments (map) is initialized by the :init-arguments action
           ;; :start-time (long) is initialized by the :set-start-time action
           ;; :future (future) is initialized by the :add-future action
           ;; :resources (map) is updated by the :add-resources and remove-resources actions
           ;; NOTE: :future and :resources are forcibly removed from the state directly, NOT VIA ACTIONS.
           :name (:name event-data)
           :full-name (:full-name event-data)
           :server (:server event-data)
           :arguments (:arguments event-data)
           :origin (:origin event-data)
           :decision-reason {:code :none
                             :text "No reason"})

    :init-approval-promise
    (swap! db* assoc-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :approved?*]
           (:approved?* event-data))

    :init-future-cleanup-promise
    (swap! db* assoc-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :future-cleanup-complete?*]
           (:future-cleanup-complete?* event-data))

    :init-arguments
    (swap! db* assoc-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :arguments]
           (:arguments event-data))

    :set-decision-reason
    (swap! db* assoc-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :decision-reason]
           (:reason event-data))

    :set-hook-continue
    (swap! db* assoc-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :hook-continue]
           (:hook-continue event-data))

    :set-hook-stop-reason
    (swap! db* assoc-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :hook-stop-reason]
           (:hook-stop-reason event-data))

    :set-start-time
    (swap! db* assoc-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :start-time]
           (:start-time event-data))

    :add-future
    (swap! db* assoc-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :future]
           ;; start the future by forcing the delay and save it in the call state
           (force (:delayed-future event-data)))

    :remove-future
    (swap! db* update-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id]
           dissoc :future)

    :add-resources
    (swap! db* update-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :resources]
           merge (:resources event-data))

    :remove-resources
    (swap! db* update-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :resources]
           #(apply dissoc %1 %2) (:resources event-data))

    :remove-all-resources
    (swap! db* update-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :resources]
           dissoc :resources)

    :remove-all-promises
    (swap! db* update-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id]
           dissoc :approved?* :future-cleanup-complete?*)

    ;; Logging actions
    :log-rejection
    (logger/info logger-tag "Tool call rejected"
                 {:tool-call-id tool-call-id :reason (:reason event-data)})

    :log-metrics
    (logger/debug logger-tag "Tool call completed"
                  {:tool-call-id tool-call-id :duration (:duration event-data)})

    ;; Default case for unknown actions
    (logger/warn logger-tag "Unknown action" {:action action :tool-call-id tool-call-id})))

(defn transition-tool-call!
  "Execute an event-driven state transition for a tool call.

   Args:
   - db*: Database atom
   - chat-ctx: Chat context map with :chat-id, :request-id, :messenger
   - tool-call-id: Tool call identifier
   - event: Event keyword (e.g., :tool-prepare, :tool-run, :user-approve)
   - event-data: Optional map with event-specific data

   Returns: {:status new-status :actions actions-executed}

   Throws: ex-info if the transition is invalid for the current state.

   Note: The status is updated before any actions are run.
   Actions are run in the order specified."
  [db* chat-ctx tool-call-id event & [event-data]]
  (let [current-state (get-tool-call-state @db* (:chat-id chat-ctx) tool-call-id)
        current-status (:status current-state :initial) ; Default to :initial if no state
        transition-key [current-status event]
        {:keys [status actions]} (get tool-call-state-machine transition-key)]

    (logger/debug logger-tag "Tool call transition"
                  {:tool-call-id tool-call-id :current-status current-status :event event :status status})

    (when-not status
      (let [valid-events (map second (filter #(= current-status (first %))
                                             (keys tool-call-state-machine)))]
        (throw (ex-info "Invalid state transition"
                        {:current-status current-status
                         :event event
                         :tool-call-id tool-call-id
                         :valid-events valid-events}))))

    ;; Atomic status update
    (swap! db* assoc-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :status] status)

    ;; Execute all actions sequentially
    (doseq [action actions]
      (execute-action! action db* chat-ctx tool-call-id event-data))

    {:status status :actions actions}))

(defn tool-by-full-name [full-name all-tools]
  (first (filter #(= full-name (:full-name %)) all-tools)))

(defn ^:private process-pre-tool-call-hook-result
  "Pure function: fold a single hook result into accumulated state.

   acc is {:hook-results [], :approval-override nil, :hook-rejected? false,
           :hook-rejection-reason nil, :hook-continue true, :hook-stop-reason nil}"
  [acc result]
  (let [parsed (:parsed result)
        hook-approval (:approval parsed)
        exit-code-2? (= f.hooks/hook-rejection-exit-code (:exit result))]
    (cond-> (update acc :hook-results conj result)
      ;; Handle rejection (exit code 2 or explicit deny)
      (or exit-code-2? (= "deny" hook-approval))
      (merge {:hook-rejected? true
              :hook-rejection-reason (or (:additionalContext parsed)
                                         (:raw-error result)
                                         "Tool call rejected by hook")
              :hook-continue (get parsed :continue true)
              :hook-stop-reason (:stopReason parsed)})

      ;; Handle approval override (allow/ask) when not exit-code-2
      (and hook-approval (not exit-code-2?))
      (assoc :approval-override hook-approval))))

(defn ^:private decide-tool-call-action
  "Decides what action to take for a tool call, running hooks and collecting their results.

   Returns a plan (data) with:
   - :decision (:ask | :allow | :deny)
   - :arguments (potentially modified by hooks)
   - :approval-override (from hooks)
   - :hook-rejected? (boolean)
   - :reason (map with :code and :text, when decision is :allow or :deny)
   - :hook-continue (boolean, for hook rejections)
   - :hook-stop-reason (string, for hook rejections)

   The on-before-hook-action and on-after-hook-action callbacks are optional (default to noops)
   and are used for UI notifications. In tests, these can be omitted."
  [{:keys [full-name arguments]} all-tools db config agent-name chat-id
   & [{:keys [on-before-hook-action on-after-hook-action trust]
       :or {on-before-hook-action (fn [_] nil)
            on-after-hook-action (fn [_] nil)}}]]
  (let [tool (tool-by-full-name full-name all-tools)
        name (:name tool)
        server (:server tool)
        server-name (:name server)

        ;; 1. Determine approval (trust promotion handled inside f.tools/approval)
        approval (f.tools/approval all-tools tool arguments db config agent-name {:trust trust})
        trusted? (= :trust/allow approval)
        effective-approval (if trusted? :allow approval)

        ;; 2. Run hooks to collect modifications and approval overrides
        hook-state* (atom {:hook-results []
                           :approval-override nil
                           :hook-rejected? false
                           :hook-rejection-reason nil
                           :hook-continue true
                           :hook-stop-reason nil})

        _ (f.hooks/trigger-if-matches!
           :preToolCall
           (merge (f.hooks/chat-hook-data db chat-id agent-name)
                  {:tool-name name
                   :server server-name
                   :tool-input arguments
                   :approval effective-approval})
           {:on-before-action on-before-hook-action
            :on-after-action (fn [result]
                               (on-after-hook-action result)
                               (swap! hook-state* process-pre-tool-call-hook-result result))}
           db
           config)

        ;; 3. Merge all updatedInput from hooks
        {:keys [hook-results approval-override hook-rejected?
                hook-rejection-reason hook-continue hook-stop-reason]} @hook-state*
        updated-inputs (keep #(get-in % [:parsed :updatedInput]) hook-results)
        final-arguments (if (not-empty updated-inputs)
                          (reduce merge arguments updated-inputs)
                          arguments)
        arguments-modified? (boolean (seq updated-inputs))

        ;; 4. Determine Final Approval (Hook Override > Config, but hook rejection takes precedence)
        final-decision (cond
                         hook-rejected? :deny
                         approval-override (keyword approval-override)
                         :else effective-approval)

        ;; 5. Build the reason map
        reason (case final-decision
                 :allow (if trusted?
                          {:code :trust-allow
                           :text "Tool call allowed by trust mode"}
                          {:code :user-config-allow
                           :text "Tool call allowed by user config"})
                 :deny (if hook-rejected?
                         {:code :hook-rejected
                          :text hook-rejection-reason}
                         {:code :user-config-deny
                          :text "Tool call rejected by user config"})
                 nil)]

    ;; Return the decision plan
    (cond-> {:decision final-decision
             :arguments final-arguments
             :approval-override approval-override
             :hook-rejected? hook-rejected?
             :arguments-modified? arguments-modified?}
      reason (assoc :reason reason)
      hook-rejected? (assoc :hook-continue hook-continue
                            :hook-stop-reason hook-stop-reason))))

(defn on-tools-called! [{:keys [db* config chat-id agent messenger metrics] :as chat-ctx}
                        received-msgs* add-to-history! user-messages]
  (fn [tool-calls]
    (let [all-tools (f.tools/all-tools chat-id agent @db* config)
          max-steps-reached? (check-subagent-max-steps! db* chat-id)]
      (lifecycle/assert-chat-not-stopped! chat-ctx)
      ;; Check subagent max steps - if reached, finish without executing more tools
      (if max-steps-reached?
        (do
          (logger/info logger-tag "Subagent reached max steps, finishing" {:chat-id chat-id})
          (swap! db* assoc-in [:chats chat-id :max-steps-reached?] true)
          (when-not (string/blank? @received-msgs*)
            (add-to-history! {:role "assistant" :content [{:type :text :text @received-msgs*}]}))
          (lifecycle/finish-chat-prompt! :idle chat-ctx)
          nil)
        (do
          (when-not (string/blank? @received-msgs*)
            (add-to-history! {:role "assistant" :content [{:type :text :text @received-msgs*}]})
            (reset! received-msgs* ""))
          (let [rejected-tool-call-info* (atom nil)]
            (run! (fn do-tool-call [{:keys [id full-name] :as tool-call}]
                    (let [approved?*                                     (promise)
                          {:keys [origin name server]}                   (tool-by-full-name full-name all-tools)
                          server-name                                    (:name server)
                          decision-plan                                  (decide-tool-call-action
                                                                          tool-call all-tools @db* config agent chat-id
                                                                          {:on-before-hook-action (partial lifecycle/notify-before-hook-action! chat-ctx)
                                                                           :on-after-hook-action  (partial lifecycle/notify-after-hook-action! chat-ctx)
                                                                           :trust                 (:trust chat-ctx)})
                          {:keys [decision arguments hook-rejected? reason hook-continue
                                  hook-stop-reason arguments-modified?]} decision-plan
                          _ (when arguments-modified?
                              (lifecycle/send-content! chat-ctx :system {:type :hookActionFinished
                                                                         :action-type "shell"
                                                                         :id (str (random-uuid))
                                                                         :name "input-modification"
                                                                         :status 0
                                                                         :output "Hook modified tool arguments"}))
                          _ (swap! db* assoc-in [:chats chat-id :tool-calls id :arguments] arguments)
                          tool-call (assoc tool-call :arguments arguments)
                          ask? (= :ask decision)
                          details (f.tools/tool-call-details-before-invocation name arguments server @db* config chat-id ask? id)
                          summary (f.tools/tool-call-summary all-tools full-name arguments config @db*)]
                      (when-not (#{:stopping :cleanup} (:status (get-tool-call-state @db* chat-id id)))
                        (transition-tool-call! db* chat-ctx id :tool-run {:approved?* approved?*
                                                                          :future-cleanup-complete?* (promise)
                                                                          :name name
                                                                          :server server-name
                                                                          :origin origin
                                                                          :arguments arguments
                                                                          :manual-approval ask?
                                                                          :details details
                                                                          :summary summary}))
                      (when-not (#{:stopping :cleanup :rejected} (:status (get-tool-call-state @db* chat-id id)))
                        (case decision
                          :ask (transition-tool-call! db* chat-ctx id :approval-ask {:progress-text "Waiting for tool call approval"})
                          :allow (transition-tool-call! db* chat-ctx id :approval-allow {:reason reason})
                          :deny (transition-tool-call! db* chat-ctx id :approval-deny {:reason reason})
                          (logger/warn logger-tag "Unknown value of approval" {:approval decision :tool-call-id id})))
                      (if (and @approved?* (not hook-rejected?))
                        (when-not (#{:stopping :cleanup} (:status (get-tool-call-state @db* chat-id id)))
                          (lifecycle/assert-chat-not-stopped! chat-ctx)
                          (let [delayed-future
                                (delay
                                  (future
                                    (let [result (f.tools/call-tool! full-name
                                                                     arguments
                                                                     chat-id
                                                                     id
                                                                     agent
                                                                     db*
                                                                     config
                                                                     messenger
                                                                     metrics
                                                                     (partial get-tool-call-state @db* chat-id id)
                                                                     (partial transition-tool-call! db* chat-ctx id)
                                                                     {:trust (:trust chat-ctx)})
                                          details (f.tools/tool-call-details-after-invocation name arguments details result
                                                                                              {:db @db*
                                                                                               :config config
                                                                                               :chat-id chat-id
                                                                                               :tool-call-id id})
                                          {:keys [start-time]} (get-tool-call-state @db* chat-id id)
                                          total-time-ms (- (System/currentTimeMillis) start-time)]
                                      (add-to-history! {:role "tool_call"
                                                        :content (assoc tool-call
                                                                        :name name
                                                                        :details details
                                                                        :summary summary
                                                                        :origin origin
                                                                        :server server-name)})
                                      (add-to-history! {:role "tool_call_output"
                                                        :content (assoc tool-call
                                                                        :name name
                                                                        :error (:error result)
                                                                        :output result
                                                                        :total-time-ms total-time-ms
                                                                        :details details
                                                                        :summary summary
                                                                        :origin origin
                                                                        :server server-name)})
                                      (let [state (get-tool-call-state @db* chat-id id) status (:status state)]
                                        (case status
                                          :executing (transition-tool-call! db*
                                                                            chat-ctx
                                                                            id
                                                                            :execution-end {:origin origin
                                                                                            :name name
                                                                                            :server server-name
                                                                                            :arguments arguments
                                                                                            :error (:error result)
                                                                                            :outputs (:contents result)
                                                                                            :total-time-ms total-time-ms
                                                                                            :progress-text "Generating"
                                                                                            :details details
                                                                                            :summary summary})
                                          :stopping (transition-tool-call! db*
                                                                           chat-ctx
                                                                           id
                                                                           :stop-attempted {:origin origin
                                                                                            :name name
                                                                                            :server server-name
                                                                                            :arguments arguments
                                                                                            :error (:error result)
                                                                                            :outputs (:contents result)
                                                                                            :total-time-ms total-time-ms
                                                                                            :reason :user-stop :details
                                                                                            details
                                                                                            :summary summary})
                                          (logger/warn logger-tag "Unexpected value of :status in tool call" {:status status}))))))]
                            (transition-tool-call! db*
                                                   chat-ctx
                                                   id
                                                   :execution-start {:delayed-future delayed-future
                                                                     :origin origin
                                                                     :name name
                                                                     :server server-name
                                                                     :arguments arguments
                                                                     :start-time (System/currentTimeMillis)
                                                                     :details details
                                                                     :summary summary
                                                                     :progress-text (if (= name "spawn_agent")
                                                                         "Waiting subagent"
                                                                         "Calling tool")})))
                        (let [tool-call-state (get-tool-call-state @db* chat-id id)
                              {:keys [code text]} (:decision-reason tool-call-state)
                              effective-hook-continue (when hook-rejected? hook-continue)
                              effective-hook-stop-reason (when hook-rejected? hook-stop-reason)]
                          (add-to-history! {:role "tool_call" :content tool-call})
                          (add-to-history! {:role "tool_call_output"
                                            :content (assoc tool-call :output {:error true :contents [{:text text :type :text}]})})
                          (reset! rejected-tool-call-info* {:code code
                                                            :hook-continue effective-hook-continue
                                                            :hook-stop-reason effective-hook-stop-reason})
                          (transition-tool-call! db* chat-ctx id :send-reject {:origin origin
                                                                               :name name
                                                                               :server server-name
                                                                               :arguments arguments
                                                                               :reason code
                                                                               :details details
                                                                               :summary summary})))))
                  tool-calls)
            (lifecycle/assert-chat-not-stopped! chat-ctx)
            (doseq [[tool-call-id state] (get-active-tool-calls @db* chat-id)]
              (when-let [f (:future state)]
                (try (deref f)
                     (catch java.util.concurrent.CancellationException _
                       (when-let [p (:future-cleanup-complete?* state)]
                         (logger/debug logger-tag
                                       "Caught CancellationException.  Waiting for future to finish cleanup."
                                       {:tool-call-id tool-call-id :promise p})
                         (deref p)))
                     (catch Throwable t
                       (logger/debug logger-tag
                                     "Ignoring a Throwable while deref'ing a tool call future"
                                     {:tool-call-id tool-call-id
                                      :ex-data (ex-data t)
                                      :message (.getMessage ^Throwable t)
                                      :cause (.getCause ^Throwable t)}))
                     (finally (try (let [tool-call-state (get-tool-call-state @db* (:chat-id chat-ctx) tool-call-id)]
                                     (transition-tool-call!
                                      db*
                                      chat-ctx
                                      tool-call-id
                                      :cleanup-finished (merge {:name (:name tool-call-state)
                                                                :full-name (:full-name tool-call-state)}
                                                               (select-keys tool-call-state [:outputs :error :total-time-ms]))))
                                   (catch Throwable t
                                     (logger/debug logger-tag "Ignoring an exception while finishing tool call"
                                                   {:tool-call-id tool-call-id
                                                    :ex-data (ex-data t)
                                                    :message (.getMessage ^Throwable t)
                                                    :cause (.getCause ^Throwable t)})))))))
            (let [all-tools (f.tools/all-tools chat-id agent @db* config)]
              (if-let [rejection-info @rejected-tool-call-info*]
                (let [reason-code
                      (if (map? rejection-info) (:code rejection-info) rejection-info)
                      hook-continue
                      (when (map? rejection-info) (:hook-continue rejection-info))
                      hook-stop-reason
                      (when (map? rejection-info) (:hook-stop-reason rejection-info))]
                  (if (= :hook-rejected reason-code)
                    (if (false? hook-continue)
                      (do (lifecycle/send-content! chat-ctx :system {:type :text
                                                                     :text (or hook-stop-reason "Tool rejected by hook")})
                          (lifecycle/finish-chat-prompt! :idle chat-ctx) nil)
                      {:tools all-tools
                       :new-messages (get-in @db* [:chats chat-id :messages])})
                    (if (get-in @db* [:chats chat-id :subagent])
                      ;; Subagent: user can't provide rejection input directly, so continue
                      ;; the LLM loop with a rejection message letting the subagent adapt
                      (do (add-to-history! {:role "user"
                                            :content [{:type :text
                                                       :text "I rejected one or more tool calls. The tool call was not allowed. Try a different approach to complete the task."}]})
                          {:tools all-tools
                           :new-messages (get-in @db* [:chats chat-id :messages])})
                      (do (lifecycle/send-content! chat-ctx :system {:type :text
                                                                     :text "Tell ECA what to do differently for the rejected tool(s)"})
                          (add-to-history! {:role "user"
                                            :content [{:type :text
                                                       :text "I rejected one or more tool calls with the following reason"}]})
                          (lifecycle/finish-chat-prompt! :idle chat-ctx)
                          nil))))
                (do
                  (lifecycle/maybe-renew-auth-token chat-ctx)
                  (if-let [continue-fn (:continue-fn chat-ctx)]
                    (continue-fn all-tools user-messages)
                    {:tools all-tools
                     :new-messages (get-in @db* [:chats chat-id :messages])}))))))))))
