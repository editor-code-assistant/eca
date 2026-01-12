(ns eca.features.chat
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.commands :as f.commands]
   [eca.features.context :as f.context]
   [eca.features.hooks :as f.hooks]
   [eca.features.index :as f.index]
   [eca.features.login :as f.login]
   [eca.features.prompt :as f.prompt]
   [eca.features.rules :as f.rules]
   [eca.features.skills :as f.skills]
   [eca.features.tools :as f.tools]
   [eca.features.tools.mcp :as f.mcp]
   [eca.llm-api :as llm-api]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.metrics :as metrics]
   [eca.shared :as shared :refer [assoc-some future*]]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CHAT]")

(defn ^:private new-content-id []
  (str (random-uuid)))

(defn default-model [db config]
  (llm-api/default-model db config))

(defn ^:private send-content! [{:keys [messenger chat-id]} role content]
  (messenger/chat-content-received
   messenger
   {:chat-id chat-id
    :role role
    :content content}))

(defn ^:private notify-before-hook-action! [chat-ctx {:keys [id name type visible?]}]
  (when visible?
    (send-content! chat-ctx :system
                   {:type :hookActionStarted
                    :action-type type
                    :name name
                    :id id})))

(defn ^:private format-hook-output
  "Format hook output for display, showing parsed JSON fields or raw output."
  [{:keys [systemMessage replacedPrompt additionalContext] :as parsed} raw-output]
  (if parsed
    (cond-> (or systemMessage "Hook executed")
      replacedPrompt  (str "\nReplacedPrompt: " (pr-str replacedPrompt))
      additionalContext (str "\nAdditionalContext: " additionalContext))
    raw-output))

(defn ^:private notify-after-hook-action! [chat-ctx {:keys [id name parsed raw-output raw-error exit type visible?]}]
  (when (and visible? (not (:suppressOutput parsed)))
    (send-content! chat-ctx :system
                   {:type :hookActionFinished
                    :action-type type
                    :id id
                    :name name
                    :status exit
                    :output (format-hook-output parsed raw-output)
                    :error raw-error})))

(defn ^:private wrap-additional-context
  "Return XML-wrapped additional context attributed to `from`."
  [from content]
  (format "<additionalContext from=\"%s\">\n%s\n</additionalContext>"
          (name from)
          content))

(defn ^:private append-post-tool-additional-context!
  "Append additionalContext (wrapped as XML) from a postToolCall hook to the
   matching tool_call_output message so LLM sees it in the next round."
  [db* chat-id tool-call-id hook-name additional-context]
  (when (not (string/blank? additional-context))
    (let [entry {:type :text :text (wrap-additional-context hook-name additional-context)}]
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

(defn finish-chat-prompt! [status {:keys [message chat-id db* metrics config on-finished-side-effect] :as chat-ctx}]
  (swap! db* assoc-in [:chats chat-id :status] status)
  (f.hooks/trigger-if-matches! :postRequest
                               (merge (f.hooks/chat-hook-data @db* chat-id (:behavior chat-ctx))
                                      {:prompt message})
                               {:on-before-action (partial notify-before-hook-action! chat-ctx)
                                :on-after-action (partial notify-after-hook-action! chat-ctx)}
                               @db*
                               config)
  (send-content! chat-ctx :system
                 {:type :progress
                  :state :finished})
  (when-not (get-in @db* [:chats chat-id :created-at])
    (swap! db* assoc-in [:chats chat-id :created-at] (System/currentTimeMillis)))
  (when on-finished-side-effect
    (on-finished-side-effect))
  (db/update-workspaces-cache! @db* metrics))

(defn ^:private maybe-renew-auth-token [chat-ctx]
  (f.login/maybe-renew-auth-token!
   {:provider (:provider chat-ctx)
    :on-renewing (fn []
                   (send-content! chat-ctx :system {:type  :progress
                                                    :state :running
                                                    :text  "Renewing auth token"}))
    :on-error (fn [error-msg]
                (send-content! chat-ctx :system {:type :text :text error-msg})
                (finish-chat-prompt! :idle chat-ctx)
                (throw (ex-info "Auth token renew failed" {})))}
   chat-ctx))

(defn ^:private assert-chat-not-stopped! [{:keys [chat-id db*] :as chat-ctx}]
  (when (identical? :stopping (get-in @db* [:chats chat-id :status]))
    (finish-chat-prompt! :idle chat-ctx)
    (logger/info logger-tag "Chat prompt stopped:" chat-id)
    (throw (ex-info "Chat prompt stopped" {:silent? true
                                           :chat-id chat-id}))))

(defn ^:private update-pre-request-state
  "Pure function to compute new state from hook result."
  [{:keys [final-prompt additional-contexts stop?]} {:keys [parsed raw-output exit]} action-name]
  (let [replaced-prompt (:replacedPrompt parsed)
        additional-context (if parsed
                             (:additionalContext parsed)
                             raw-output)
        success? (= 0 exit)]
    {:final-prompt (if (and replaced-prompt success?)
                     replaced-prompt
                     final-prompt)
     :additional-contexts (if (and additional-context success?)
                            (conj additional-contexts
                                  {:hook-name action-name :content additional-context})
                            additional-contexts)
     :stop? (or stop?
                (false? (get parsed :continue true)))}))

(defn ^:private run-pre-request-action!
  "Run a single preRequest hook action, updating the accumulator state.

  State is a map:
  - :final-prompt
  - :additional-contexts
  - :stop? (true when any hook requests stop)"
  [db chat-ctx chat-id hook hook-name idx action state]
  (if (:stop? state)
    state
    (let [id (str (random-uuid))
          action-type (:type action)
          action-name (if (> (count (:actions hook)) 1)
                        (str hook-name "-" (inc idx))
                        hook-name)
          visible? (get hook :visible true)]
      (notify-before-hook-action! chat-ctx {:id id
                                            :visible? visible?
                                            :name action-name})
      ;; Run the hook action
      (if-let [result (f.hooks/run-hook-action! action
                                                action-name
                                                :preRequest
                                                (merge (f.hooks/chat-hook-data db chat-id (:behavior chat-ctx))
                                                       {:prompt (:final-prompt state)})
                                                db)]
        (let [{:keys [parsed raw-output raw-error exit]} result
              should-continue? (get parsed :continue true)]
          ;; Notify after action
          (notify-after-hook-action! chat-ctx (merge result
                                                     {:id id
                                                      :name action-name
                                                      :type action-type
                                                      :visible? visible?
                                                      :status exit
                                                      :output raw-output
                                                      :error raw-error}))
          ;; Check if hook wants to stop
          (when (false? should-continue?)
            (when-let [stop-reason (:stopReason parsed)]
              (send-content! chat-ctx :system {:type :text :text stop-reason}))
            (finish-chat-prompt! :idle chat-ctx))
          ;; Update accumulator
          (update-pre-request-state state
                                    result
                                    action-name))
        ;; No result from action
        (do
          (notify-after-hook-action! chat-ctx {:id id
                                               :name action-name
                                               :visible? visible?
                                               :type action-type
                                               :exit 1
                                               :status 1})
          state)))))

(defn ^:private run-pre-request-hook!
  "Run all actions for a single preRequest hook, threading state."
  [db chat-ctx chat-id state [hook-name hook]]
  (reduce
   (fn [s [idx action]]
     (if (:stop? s)
       (reduced s)
       (run-pre-request-action! db chat-ctx chat-id hook (name hook-name) idx action s)))
   state
   (map-indexed vector (:actions hook))))

(defn ^:private run-pre-request-hooks!
  "Run preRequest hooks with chaining support.

  Returns a map with:
  - :final-prompt
  - :additional-contexts (vector of {:hook-name name :content context})
  - :stop? (true when any hook requests stop)"
  [{:keys [db* config chat-id message] :as chat-ctx}]
  (let [db @db*]
    (reduce
     (fn [state hook-entry]
       (if (:stop? state)
         (reduced state)
         (run-pre-request-hook! db chat-ctx chat-id state hook-entry)))
     {:final-prompt        message
      :additional-contexts []
      :stop?               false}
     (->> (:hooks config)
          (filter #({"preRequest" "prePrompt"} (:type (val %))))
          (sort-by key)))))

;;; Helper functions for tool call state management

(defn ^:private get-tool-call-state
  "Get the complete state map for a specific tool call."
  [db chat-id tool-call-id]
  (get-in db [:chats chat-id :tool-calls tool-call-id]))

(defn ^:private get-active-tool-calls
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
     (merge (f.hooks/chat-hook-data @db* chat-id (:behavior chat-ctx))
            {:tool-name (:name tool-call-state)
             :server (:server tool-call-state)
             :tool-input (:arguments tool-call-state)
             :tool-response (:outputs event-data)
             :error (:error event-data)})
     {:on-before-action (partial notify-before-hook-action! chat-ctx)
      :on-after-action (fn [{:keys [parsed name] :as result}]
                         ;; Always notify UI
                         (notify-after-hook-action! chat-ctx result)
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
    (send-content! chat-ctx :system
                   {:type :progress
                    :state :running
                    :text (:progress-text event-data)})

    :send-toolCallPrepare
    (send-content! chat-ctx :assistant
                   (assoc-some
                    {:type :toolCallPrepare
                     :id tool-call-id
                     :name (:name event-data)
                     :server (:server event-data)
                     :origin (:origin event-data)
                     :arguments-text (:arguments-text event-data)}
                    :summary (:summary event-data)))

    :send-toolCallRun
    (send-content! chat-ctx :assistant
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
    (send-content! chat-ctx :assistant
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
    (send-content! chat-ctx :assistant
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
      (send-content! chat-ctx :assistant
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

(defn ^:private transition-tool-call!
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

(defn ^:private tool-by-full-name [full-name all-tools]
  (first (filter #(= full-name (:full-name %)) all-tools)))

(defn ^:private tokenize-args [^String s]
  (if (string/blank? s)
    []
    (->> (re-seq #"\s*\"([^\"]*)\"|\s*([^\s]+)" s)
         (map (fn [[_ quoted unquoted]] (or quoted unquoted)))
         (vec))))

(defn ^:private message->decision [message db config]
  (let [all-command-names (->> (f.commands/all-commands db config)
                               (map :name)
                               set)
        slash? (string/starts-with? message "/")
        possible-command (when slash? (subs message 1))
        [command-name & args] (when possible-command
                                (let [toks (tokenize-args possible-command)] (if (seq toks) toks [""])))
        args (vec args)
        command? (contains? all-command-names command-name)]
    (if command?
      (if (and command-name (string/includes? command-name ":"))
        (let [[server prompt] (string/split command-name #":" 2)]
          {:type :mcp-prompt
           :server server
           :prompt prompt
           :args args})
        {:type :eca-command
         :command command-name
         :args args})
      {:type :prompt-message
       :message message})))

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
  [{:keys [full-name arguments]} all-tools db config behavior chat-id
   & [{:keys [on-before-hook-action on-after-hook-action]
       :or {on-before-hook-action (fn [_] nil)
            on-after-hook-action (fn [_] nil)}}]]
  (let [tool (tool-by-full-name full-name all-tools)
        name (:name tool)
        server (:server tool)
        server-name (:name server)

        ;; 1. Determine initial config-based approval
        initial-approval (f.tools/approval all-tools tool arguments db config behavior)

        ;; 2. Run hooks to collect modifications and approval overrides
        hook-state* (atom {:hook-results []
                           :approval-override nil
                           :hook-rejected? false
                           :hook-rejection-reason nil
                           :hook-continue true
                           :hook-stop-reason nil})

        _ (f.hooks/trigger-if-matches!
           :preToolCall
           (merge (f.hooks/chat-hook-data db chat-id behavior)
                  {:tool-name name
                   :server server-name
                   :tool-input arguments
                   :approval initial-approval})
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
                         :else initial-approval)

        ;; 5. Build the reason map
        reason (case final-decision
                 :allow {:code :user-config-allow
                         :text "Tool call allowed by user config"}
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

(defn ^:private on-tools-called! [{:keys [db* config chat-id behavior messenger metrics] :as chat-ctx}
                                  received-msgs* add-to-history!]
  (let [all-tools (f.tools/all-tools chat-id behavior @db* config)]
    (fn [tool-calls]
      (assert-chat-not-stopped! chat-ctx)
      (when-not (string/blank? @received-msgs*)
        (add-to-history! {:role "assistant" :content [{:type :text :text @received-msgs*}]})
        (reset! received-msgs* ""))
      (let [rejected-tool-call-info* (atom nil)]
        (run! (fn do-tool-call [{:keys [id full-name] :as tool-call}]
                (let [approved?*                                     (promise)
                      {:keys [origin name server]}                   (tool-by-full-name full-name all-tools)
                      server-name                                    (:name server)
                      decision-plan                                  (decide-tool-call-action
                                                                      tool-call all-tools @db* config behavior chat-id
                                                                      {:on-before-hook-action (partial notify-before-hook-action! chat-ctx)
                                                                       :on-after-hook-action  (partial notify-after-hook-action! chat-ctx)})
                      {:keys [decision arguments hook-rejected? reason hook-continue
                              hook-stop-reason arguments-modified?]} decision-plan
                      _ (when arguments-modified?
                          (send-content! chat-ctx :system {:type :hookActionFinished
                                                           :action-type "shell"
                                                           :id (str (random-uuid))
                                                           :name "input-modification"
                                                           :status 0
                                                           :output "Hook modified tool arguments"}))
                      _ (swap! db* assoc-in [:chats chat-id :tool-calls id :arguments] arguments)
                      tool-call (assoc tool-call :arguments arguments)
                      ask? (= :ask decision)
                      details (f.tools/tool-call-details-before-invocation name arguments server @db* ask?)
                      summary (f.tools/tool-call-summary all-tools full-name arguments config)]
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
                      (assert-chat-not-stopped! chat-ctx)
                      (let [delayed-future
                            (delay
                              (future
                                (let [result (f.tools/call-tool! full-name
                                                                 arguments
                                                                 chat-id
                                                                 id
                                                                 behavior
                                                                 db*
                                                                 config
                                                                 messenger
                                                                 metrics
                                                                 (partial get-tool-call-state @db* chat-id id)
                                                                 (partial transition-tool-call! db* chat-ctx id))
                                      details (f.tools/tool-call-details-after-invocation name arguments details result)
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
                                                                 :progress-text "Calling tool"})))
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
        (assert-chat-not-stopped! chat-ctx)
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
                                  :message (.getMessage t)
                                  :cause (.getCause t)}))
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
                                                :message (.getMessage t)
                                                :cause (.getCause t)})))))))
        (if-let [rejection-info @rejected-tool-call-info*]
          (let [reason-code
                (if (map? rejection-info) (:code rejection-info) rejection-info)
                hook-continue
                (when (map? rejection-info) (:hook-continue rejection-info))
                hook-stop-reason
                (when (map? rejection-info) (:hook-stop-reason rejection-info))]
            (if (= :hook-rejected reason-code)
              (if (false? hook-continue)
                (do (send-content! chat-ctx :system {:type :text
                                                     :text (or hook-stop-reason "Tool rejected by hook")})
                    (finish-chat-prompt! :idle chat-ctx) nil)
                {:new-messages (get-in @db* [:chats chat-id :messages])})
              (do (send-content! chat-ctx :system {:type :text
                                                   :text "Tell ECA what to do differently for the rejected tool(s)"})
                  (add-to-history! {:role "user"
                                    :content [{:type :text
                                               :text "I rejected one or more tool calls with the following reason"}]})
                  (finish-chat-prompt! :idle chat-ctx)
                  nil)))
          (do (maybe-renew-auth-token chat-ctx)
              {:new-messages (get-in @db* [:chats chat-id :messages])}))))))

(defn ^:private assert-compatible-apis-between-models!
  "Ensure new request is compatible with last api used.
   E.g. Anthropic is not compatible with openai and vice versa."
  [db chat-id provider config]
  (let [current-api (:api (llm-api/provider->api-handler provider config))
        last-api (get-in db [:chats chat-id :last-api])]
    (cond
      (not last-api) nil
      (not current-api) nil

      (or (and (= :anthropic current-api)
               (not= :anthropic last-api))
          (and (not= :anthropic current-api)
               (= :anthropic last-api)))
      (throw (ex-info "Incompatible past messages in chat.\nAnthropic models are only compatible with other Anthropic models, switch models or start a new chat." {})))))

(defn ^:private prompt-messages!
  "Send user messages to LLM with hook processing.
   source-type controls hook behavior.
   Run preRequest hooks before any heavy lifting.
   Only :prompt-message supports rewrite, other only allow additionalContext append."
  [user-messages source-type
   {:keys [db* config chat-id provider model full-model behavior instructions metrics message] :as chat-ctx}]
  (let [original-text (or message (-> user-messages first :content first :text))
        modify-allowed? (= source-type :prompt-message)
        run-hooks? (#{:prompt-message :eca-command :mcp-prompt} source-type)
        user-messages (if run-hooks?
                        (let [{:keys [final-prompt additional-contexts stop?]}
                              (run-pre-request-hooks! (assoc chat-ctx :message original-text))]
                          (cond
                            stop? (do (finish-chat-prompt! :idle chat-ctx) nil)
                            :else (let [last-user-idx (llm-util/find-last-user-msg-idx user-messages)
                                        ;; preRequest additionalContext should ideally attach to the last user message,
                                        ;; but some prompt sources may not contain a user role (e.g. prompt templates).
                                        context-idx   (or last-user-idx
                                                          (some-> user-messages seq count dec))
                                        rewritten     (if (and modify-allowed? last-user-idx final-prompt)
                                                        (assoc-in user-messages [last-user-idx :content 0 :text] final-prompt)
                                                        user-messages)
                                        with-contexts (cond
                                                        (and (seq additional-contexts) context-idx)
                                                        (reduce (fn [msgs {:keys [hook-name content]}]
                                                                  (update-in msgs [context-idx :content]
                                                                             #(conj (vec %)
                                                                                    {:type :text
                                                                                     :text (wrap-additional-context hook-name content)})))
                                                                rewritten
                                                                additional-contexts)

                                                        (seq additional-contexts)
                                                        (do (logger/warn logger-tag "Dropping preRequest additionalContext because no message index was found"
                                                                         {:source-type source-type
                                                                          :num-messages (count user-messages)})
                                                            rewritten)

                                                        :else
                                                        rewritten)]
                                    with-contexts)))
                        user-messages)]
    (when user-messages
      (swap! db* assoc-in [:chats chat-id :status] :running)
      (let [_ (maybe-renew-auth-token chat-ctx)
            db @db*
            past-messages (get-in db [:chats chat-id :messages] [])
            model-capabilities (get-in db [:models full-model])
            provider-auth (get-in @db* [:auth provider])
            all-tools (f.tools/all-tools chat-id behavior @db* config)
            received-msgs* (atom "")
            reasonings* (atom {})
            add-to-history! (fn [msg]
                              (swap! db* update-in [:chats chat-id :messages] (fnil conj []) msg))
            on-usage-updated (fn [usage]
                               (when-let [usage (shared/usage-msg->usage usage full-model chat-ctx)]
                                 (send-content! chat-ctx :system (merge {:type :usage} usage))))]
        (assert-compatible-apis-between-models! db chat-id provider config)
        (when-not (get-in db [:chats chat-id :title])
          (future* config
            (when-let [{:keys [output-text]} (llm-api/sync-prompt!
                                              {:provider provider
                                               :model model
                                               :model-capabilities
                                               (assoc model-capabilities :reason? false :tools false :web-search false)
                                               :instructions (f.prompt/title-prompt)
                                               :user-messages user-messages
                                               :config config
                                               :provider-auth provider-auth})]
              (when output-text
                (let [title (subs output-text 0 (min (count output-text) 30))]
                  (swap! db* assoc-in [:chats chat-id :title] title)
                  (send-content! chat-ctx :system (assoc-some {:type :metadata} :title title))
                  (when (= :idle (get-in @db* [:chats chat-id :status]))
                    (db/update-workspaces-cache! @db* metrics)))))))
        (send-content! chat-ctx :system {:type :progress :state :running :text "Waiting model"})
        (future* config
          (llm-api/sync-or-async-prompt!
           {:model model
            :provider provider
            :model-capabilities model-capabilities
            :user-messages user-messages
            :instructions  instructions
            :past-messages  past-messages
            :config  config
            :tools all-tools
            :provider-auth provider-auth
            :on-first-response-received (fn [& _]
                                          (assert-chat-not-stopped! chat-ctx)
                                          (doseq [message user-messages]
                                            (add-to-history!
                                             (assoc message :content-id (:user-content-id chat-ctx))))
                                          (swap! db* assoc-in [:chats chat-id :last-api] (:api (llm-api/provider->api-handler provider config)))
                                          (send-content! chat-ctx :system {:type :progress
                                                                           :state :running
                                                                           :text "Generating"}))
            :on-usage-updated on-usage-updated
            :on-message-received (fn [{:keys [type] :as msg}]
                                   (assert-chat-not-stopped! chat-ctx)
                                   (case type
                                     :text (do (swap! received-msgs* str (:text msg))
                                               (send-content! chat-ctx :assistant {:type :text :text (:text msg)}))
                                     :url (send-content! chat-ctx :assistant {:type :url :title (:title msg) :url (:url msg)})
                                     :limit-reached (do (send-content!
                                                         chat-ctx
                                                         :system
                                                         {:type :text
                                                          :text (str "API limit reached. Tokens: "
                                                                     (json/generate-string (:tokens msg)))})
                                                        (finish-chat-prompt! :idle chat-ctx))
                                     :finish (do (add-to-history! {:role "assistant"
                                                                   :content [{:type :text :text @received-msgs*}]})
                                                 (finish-chat-prompt! :idle chat-ctx))))
            :on-prepare-tool-call (fn [{:keys [id full-name arguments-text]}]
                                    (assert-chat-not-stopped! chat-ctx)
                                    (let [tool (tool-by-full-name full-name all-tools)]
                                      (when-not tool
                                        (logger/warn logger-tag "Tool not found for prepare"
                                                     {:full-name full-name
                                                      :available-tools (mapv :full-name all-tools)}))
                                      (transition-tool-call! db* chat-ctx id :tool-prepare
                                                             {:name (or (:name tool) full-name)
                                                              :server (:name (:server tool))
                                                              :full-name full-name
                                                              :origin (or (:origin tool) :unknown)
                                                              :arguments-text arguments-text
                                                              :summary (f.tools/tool-call-summary all-tools full-name nil config)})))
            :on-tools-called (on-tools-called! chat-ctx received-msgs* add-to-history!)
            :on-reason (fn [{:keys [status id text external-id delta-reasoning?]}]
                         (assert-chat-not-stopped! chat-ctx)
                         (case status
                           :started  (do (swap! reasonings* assoc-in [id :start-time] (System/currentTimeMillis))
                                         (send-content! chat-ctx :assistant {:type :reasonStarted :id id}))
                           :thinking (do (swap! reasonings* update-in [id :text] str text)
                                         (send-content! chat-ctx :assistant {:type :reasonText :id id :text text}))
                           :finished (when-let [start-time (get-in @reasonings* [id :start-time])]
                                       (let [total-time-ms (- (System/currentTimeMillis) start-time)]
                                         (add-to-history! {:role "reason"
                                                           :content {:id id
                                                                     :external-id external-id
                                                                     :delta-reasoning? delta-reasoning?
                                                                     :total-time-ms total-time-ms
                                                                     :text (get-in @reasonings* [id :text])}})
                                         (send-content! chat-ctx :assistant {:type :reasonFinished :total-time-ms total-time-ms :id id})))
                           nil))
            :on-error (fn [{:keys [message exception]}]
                        (send-content! chat-ctx :system {:type :text :text (or message (str "Error: " (ex-message exception)))})
                        (finish-chat-prompt! :idle chat-ctx))}))))))

(defn ^:private send-mcp-prompt!
  [{:keys [prompt args] :as _decision}
   {:keys [db*] :as chat-ctx}]
  (let [{:keys [arguments]} (first (filter #(= prompt (:name %)) (f.mcp/all-prompts @db*)))
        args-vals (zipmap (map :name arguments) args)
        {:keys [messages error-message]} (f.prompt/get-prompt! prompt args-vals @db*)]
    (if error-message
      (send-content! chat-ctx
                     :system
                     {:type :text
                      :text error-message})
      (prompt-messages! messages :mcp-prompt chat-ctx))))

(defn ^:private message-content->chat-content [role message-content content-id]
  (case role
    ("user"
     "system"
     "assistant") [{:role role
                    :content (reduce
                              (fn [m content]
                                (case (:type content)
                                  :text (assoc m
                                               :type :text
                                               :text (str (:text m) "\n" (:text content)))
                                  m))
                              (assoc-some {} :content-id content-id)
                              message-content)}]
    "tool_call" [{:role :assistant
                  :content {:type :toolCallPrepare
                            :origin (:origin message-content)
                            :name (:name message-content)
                            :server (:server message-content)
                            :summary (:summary message-content)
                            :details (:details message-content)
                            :arguments-text ""
                            :id (:id message-content)}}]
    "tool_call_output" [{:role :assistant
                         :content {:type :toolCalled
                                   :origin (:origin message-content)
                                   :name (:name message-content)
                                   :server (:server message-content)
                                   :arguments (:arguments message-content)
                                   :total-time-ms (:total-time-ms message-content)
                                   :summary (:summary message-content)
                                   :details (:details message-content)
                                   :error (:error message-content)
                                   :id (:id message-content)
                                   :outputs (:contents (:output message-content))}}]
    "reason" [{:role :assistant
               :content {:type :reasonStarted
                         :id (:id message-content)}}
              {:role :assistant
               :content {:type :reasonText
                         :id (:id message-content)
                         :text (:text message-content)}}
              {:role :assistant
               :content {:type :reasonFinished
                         :id (:id message-content)
                         :total-time-ms (:total-time-ms message-content)}}]))

(defn ^:private send-chat-contents! [messages chat-ctx]
  (doseq [message messages]
    (doseq [{:keys [role content]} (message-content->chat-content (:role message) (:content message) (:content-id message))]
      (send-content! chat-ctx
                     role
                     content))))

(defn ^:private handle-command! [{:keys [command args]} chat-ctx]
  (let [{:keys [type on-finished-side-effect] :as result} (f.commands/handle-command! command args chat-ctx)]
    (case type
      :chat-messages (do
                       (doseq [[chat-id {:keys [messages title]}] (:chats result)]
                         (let [new-chat-ctx (assoc chat-ctx :chat-id chat-id)]
                           (send-chat-contents! messages new-chat-ctx)
                           (when title
                             (send-content! new-chat-ctx :system (assoc-some
                                                                  {:type :metadata}
                                                                  :title title)))))
                       (finish-chat-prompt! :idle chat-ctx))
      :new-chat-status (finish-chat-prompt! (:status result) chat-ctx)
      :send-prompt (let [prompt-contents (:prompt result)]
                     ;; Keep original slash command in :message for hooks (already in parent chat-ctx)
                     (prompt-messages! [{:role "user" :content prompt-contents}]
                                       :eca-command
                                       (assoc chat-ctx :on-finished-side-effect on-finished-side-effect)))
      nil)))

;; preRequest hook handling moved into prompt-messages!
;; This helper removed.  Logic centralized for all prompt types.

(defn prompt
  [{:keys [message model behavior contexts chat-id]}
   db*
   messenger
   config
   metrics]
  (let [message (string/trim message)
        provided-chat-id chat-id
        chat-id (or chat-id
                    (let [new-id (str (random-uuid))]
                      (swap! db* assoc-in [:chats new-id] {:id new-id})
                      new-id))
        ;; Snapshot DB to detect new/resumed chat BEFORE hooks mutate it
        [db0 _] (swap-vals! db* assoc-in [:chat-start-fired chat-id] true)
        existing-chat-before-prompt (get-in db0 [:chats chat-id])
        chat-start-fired? (get-in db0 [:chat-start-fired chat-id])
        has-messages? (seq (:messages existing-chat-before-prompt))
        resumed? (boolean (and (not chat-start-fired?)
                               provided-chat-id
                               has-messages?))
        ;; Trigger chatStart hook as early as possible so its additionalContext
        ;; is visible in build-chat-instructions and /prompt-show.
        _ (when-not chat-start-fired?
            (let [hook-results* (atom [])
                  hook-ctx {:messenger messenger :chat-id chat-id}]
              (f.hooks/trigger-if-matches! :chatStart
                                           (merge (f.hooks/base-hook-data db0)
                                                  {:chat-id chat-id
                                                   :resumed resumed?})
                                           {:on-before-action (partial notify-before-hook-action! hook-ctx)
                                            :on-after-action (fn [result]
                                                               (notify-after-hook-action! hook-ctx result)
                                                               (swap! hook-results* conj result))}
                                           db0
                                           config)
              ;; Collect additionalContext from all chatStart hooks and store
              ;; it as startup-context for this chat.
              (when-let [additional-contexts (seq (keep #(get-in % [:parsed :additionalContext]) @hook-results*))]
                (swap! db* assoc-in [:chats chat-id :startup-context]
                       (string/join "\n\n" additional-contexts)))
            ;; Mark chatStart as fired for this chat in this server run
              (swap! db* assoc-in [:chat-start-fired chat-id] true)))
        ;; Re-read DB after potential chatStart modifications
        db @db*
        raw-behavior (or behavior
                         (-> config :chat :defaultBehavior) ;; legacy
                         (-> config :defaultBehavior))
        selected-behavior (config/validate-behavior-name raw-behavior config)
        behavior-config (get-in config [:behavior selected-behavior])
        ;; Simple model selection without behavior switching logic
        full-model (or model
                       (:defaultModel behavior-config)
                       (default-model db config))
        rules (f.rules/all config (:workspace-folders db))
        all-tools (f.tools/all-tools chat-id behavior @db* config)
        skills (->> (f.skills/all config (:workspace-folders db))
                    (remove
                     (fn [skill]
                       (= :deny (f.tools/approval all-tools
                                                  {:server {:name "eca"} :name "skill"}
                                                  {"name" (:name skill)}
                                                  db
                                                  config
                                                  behavior)))))
        _ (when (seq contexts)
            (send-content! {:messenger messenger :chat-id chat-id} :system {:type :progress
                                                                            :state :running
                                                                            :text "Parsing given context"}))
        refined-contexts (concat
                          (f.context/agents-file-contexts db)
                          (f.context/raw-contexts->refined contexts db))
        repo-map* (delay (f.index/repo-map db config {:as-string? true}))
        instructions (f.prompt/build-chat-instructions refined-contexts
                                                       rules
                                                       skills
                                                       repo-map*
                                                       selected-behavior
                                                       config
                                                       chat-id
                                                       all-tools
                                                       db)
        image-contents (->> refined-contexts
                            (filter #(= :image (:type %))))
        expanded-prompt-contexts (when-let [contexts-str (some-> (f.context/contexts-str-from-prompt message db)
                                                                 seq
                                                                 (f.prompt/contexts-str repo-map* nil))]
                                   [{:type :text :text contexts-str}])
        user-messages [{:role "user" :content (vec (concat [{:type :text :text message}]
                                                           expanded-prompt-contexts
                                                           image-contents))}]
        [provider model] (string/split full-model #"/" 2)
        chat-ctx {:chat-id chat-id
                  :message message
                  :contexts contexts
                  :behavior selected-behavior
                  :behavior-config behavior-config
                  :instructions instructions
                  :user-messages user-messages
                  :full-model full-model
                  :provider provider
                  :model model
                  :db* db*
                  :metrics metrics
                  :config config
                  :user-content-id (new-content-id)
                  :messenger messenger}
        decision (message->decision message db config)]
    ;; Show original prompt to user, but LLM receives the modified version
    (send-content! chat-ctx :user {:type :text
                                   :content-id (:user-content-id chat-ctx)
                                   :text (str message "\n")})
    (try
      (case (:type decision)
        :mcp-prompt (send-mcp-prompt! decision chat-ctx)
        :eca-command (handle-command! decision chat-ctx)
        :prompt-message (prompt-messages! user-messages :prompt-message chat-ctx))
      (metrics/count-up! "prompt-received"
                         {:full-model full-model
                          :behavior behavior}
                         metrics)
      {:chat-id chat-id
       :model full-model
       :status :prompting}
      (catch Exception e
        (send-content! chat-ctx :system {:type :text :text (str "Error: " (ex-message e))})
        (finish-chat-prompt! :idle chat-ctx)
        {:chat-id chat-id
         :model full-model
         :status :error}))))

(defn tool-call-approve [{:keys [chat-id tool-call-id save]} db* messenger metrics]
  (let [chat-ctx {:chat-id chat-id
                  :db* db*
                  :metrics metrics
                  :messenger messenger}]
    (transition-tool-call! db* chat-ctx tool-call-id :user-approve
                           {:reason {:code :user-choice-allow
                                     :text "Tool call allowed by user choice"}})
    (when (= "session" save)
      (let [tool-call-name (get-in @db* [:chats chat-id :tool-calls tool-call-id :name])]
        (swap! db* assoc-in [:tool-calls tool-call-name :remember-to-approve?] true)))))

(defn tool-call-reject [{:keys [chat-id tool-call-id]} db* messenger metrics]
  (let [chat-ctx {:chat-id chat-id
                  :db* db*
                  :metrics metrics
                  :messenger messenger}]
    (transition-tool-call! db* chat-ctx tool-call-id :user-reject
                           {:reason {:code :user-choice-deny
                                     :text "Tool call rejected by user choice"}})))

(defn query-context
  [{:keys [query contexts chat-id]}
   db*
   config]
  {:chat-id chat-id
   :contexts (set/difference (set (f.context/all-contexts query false db* config))
                             (set contexts))})

(defn query-files
  [{:keys [query chat-id]}
   db*
   config]
  {:chat-id chat-id
   :files (set (f.context/all-contexts query true db* config))})

(defn query-commands
  [{:keys [query chat-id]}
   db*
   config]
  (let [query (string/lower-case query)
        commands (f.commands/all-commands @db* config)
        commands (if (string/blank? query)
                   commands
                   (filter #(or (string/includes? (string/lower-case (:name %)) query)
                                (string/includes? (string/lower-case (:description %)) query))
                           commands))]
    {:chat-id chat-id
     :commands commands}))

(defn prompt-stop
  [{:keys [chat-id]} db* messenger metrics]
  (when (identical? :running (get-in @db* [:chats chat-id :status]))
    (let [chat-ctx {:chat-id chat-id
                    :db* db*
                    :metrics metrics
                    :messenger messenger}]
      (send-content! chat-ctx :system {:type :text
                                       :text "\nPrompt stopped"})

      ;; Handle each active tool call
      (doseq [[tool-call-id _] (get-active-tool-calls @db* chat-id)]
        (transition-tool-call! db* chat-ctx tool-call-id :stop-requested
                               {:reason {:code :user-prompt-stop
                                         :text "Tool call rejected because of user prompt stop"}}))
      (finish-chat-prompt! :stopping chat-ctx))))

(defn delete-chat
  [{:keys [chat-id]} db* config metrics]
  (when-let [chat (get-in @db* [:chats chat-id])]
    ;; Trigger chatEnd hook BEFORE deleting (chat still exists in cache)
    (f.hooks/trigger-if-matches! :chatEnd
                                 (merge (f.hooks/base-hook-data @db*)
                                        {:chat-id chat-id
                                         :title (:title chat)
                                         :message-count (count (:messages chat))})
                                 {}
                                 @db*
                                 config))
  ;; Delete chat from memory
  (swap! db* update :chats dissoc chat-id)
  ;; Save updated cache (without this chat)
  (db/update-workspaces-cache! @db* metrics))

(defn rollback-chat
  "Remove messages from chat in db until content-id matches.
   Then notify to clear chat and then the kept messages."
  [{:keys [chat-id content-id include]} db* messenger]
  (let [include (if (seq include)
                  (set include)
                  ;; backwards compatibility
                  #{"messages" "tools"})
        all-messages (get-in @db* [:chats chat-id :messages])
        tool-calls (get-in @db* [:chats chat-id :tool-calls])
        new-messages (when (contains? include "messages")
                       (vec (take-while #(not= (:content-id %) content-id) all-messages)))
        removed-messages (when (contains? include "tools")
                           (vec (drop-while #(not= (:content-id %) content-id) all-messages)))
        rollback-changes (->> removed-messages
                              (filter #(= "tool_call_output" (:role %)))
                              (keep #(get-in tool-calls [(:id (:content %)) :rollback-changes]))
                              flatten
                              reverse)]
    (doseq [{:keys [path content]} rollback-changes]
      (logger/info (format "Rolling back change for '%s' to content: '%s'" path content))
      (if content
        (spit path content)
        (io/delete-file path true)))
    (when new-messages
      (swap! db* assoc-in [:chats chat-id :messages] new-messages)
      (messenger/chat-cleared
       messenger
       {:chat-id chat-id
        :messages true})
      (send-chat-contents!
       new-messages
       {:chat-id chat-id
        :messenger messenger}))
    {}))
