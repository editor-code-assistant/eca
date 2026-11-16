(ns eca.features.chat.tool-calls
  (:require
   [camel-snake-kebab.core :as csk]
   [eca.db :as db]
   [eca.features.chat.lifecycle :as f.chat.lifecycle]
   [eca.features.tools :as f.tools]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.modules.events :as events]
   [eca.shared :as shared :refer [assoc-some]]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CHAT]")

(defn ^:private update-tool-output-contents!
  "Apply `f` to the :contents of the tool_call_output message matching
   `tool-call-id`, a no-op when no such message exists. Scans messages backwards
   since the tool output is usually one of the last items."
  [db* chat-id tool-call-id f]
  (swap! db* update-in [:chats chat-id :messages]
         (fn [messages]
           (let [idx (llm-util/find-last-msg-idx
                      #(and (= "tool_call_output" (:role %))
                            (= tool-call-id (get-in % [:content :id])))
                      messages)]
             (if idx
               (update-in messages [idx :content :output :contents] f)
               messages)))))

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

(def waiting-approval-progress-text "Waiting for tool call approval")

(defn ^:private progress-text
  "Progress text for a tool call transition of `chat-id`. While any of its tool
   calls waits for approval that is shown instead of `text`, so an approved
   tool call finishing in the background doesn't hide the pending approval."
  [db chat-id text]
  (if (some #(= :waiting-approval (:status %))
            (vals (get-in db [:chats chat-id :tool-calls])))
    waiting-approval-progress-text
    text))

(defn module-result
  "The tool result a `:tool/after-call` handler set as `:result`: a result map
   with sequential `:contents`, whose `:error` defaults to the original's, or a
   string, a text result. Nil for any other value."
  [original new]
  (cond
    (and (map? new) (sequential? (:contents new)))
    (assoc new :error (boolean (if (contains? new :error) (:error new) (:error original))))

    (string? new)
    {:error (boolean (:error original)) :contents [{:type :text :text new}]}))

(defn ^:private run-called-event!
  "Once the client got a tool call's result: runs the `:tool/called` event,
   the postToolCall hooks last. Its handlers may replace what the model sees
   of the result, add context after it, or stop the turn after this batch,
   which the tool call state records. Not when a `:tool/after-call` handler
   stopped the turn."
  [db* {:keys [chat-id prompt-id] :as chat-ctx} tool-call-id {:keys [full-name server arguments error outputs]}]
  (when-not (:post-tool-call-stop? (get-tool-call-state @db* chat-id tool-call-id))
    (logger/with-chat-context chat-id (db/parent-chat-id @db* chat-id)
      (let [result {:error error :contents outputs}
            chat-ctx (assoc chat-ctx :db* db*)
            event (events/emit! (assoc chat-ctx :tool-call-id tool-call-id) :tool/called
                                (f.chat.lifecycle/chat-event chat-ctx {:tool full-name
                                                                       :server server
                                                                       :arguments arguments
                                                                       :result result
                                                                       :error? (boolean error)
                                                                       :tool-call-id tool-call-id}))
            new-result (:result event)]
        (when-not (identical? result new-result)
          (if-let [{:keys [contents]} (module-result result new-result)]
            (update-tool-output-contents! db* chat-id tool-call-id (constantly (vec contents)))
            (logger/warn logger-tag "Ignoring the :result a :tool/called handler set"
                         {:tool-call-id tool-call-id :result new-result})))
        (when-let [contexts (seq (::events/contexts event))]
          (update-tool-output-contents! db* chat-id tool-call-id #(into (vec %) (map f.chat.lifecycle/context-block) contexts)))
        (when (contains? event :stop)
          (swap! db* update-in [:chats chat-id :tool-calls tool-call-id] assoc
                 :post-tool-call-stop? true
                 :post-tool-call-stop-prompt-id prompt-id
                 :post-tool-call-stop-reason (shared/not-blank (:stop event))
                 :post-tool-call-stop-ended-by (f.chat.lifecycle/ended-by event)))))))

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
   Note: :finally-actions run after the actions and the status hook, even when they throw.
   The future-cleanup promise is delivered there, so a stop joins the post-tool hooks too.

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

   [:waiting-approval :user-reject]
   {:status :rejected
    :actions [:set-decision-reason :deliver-approval-false :log-rejection]}

   [:rejected :send-reject]
   {:status :rejected
    :actions [:send-toolCallRejected]}

   [:execution-approved :execution-start]
   {:status :executing
    :actions [:set-start-time :add-future :send-toolCallRunning :send-progress]}

   [:executing :execution-end]
   {:status :cleanup
    :actions [:save-execution-result :send-toolCalled :log-metrics :send-progress :emit-tool-called]
    :finally-actions [:deliver-future-cleanup-completed]}

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
    :actions [:save-execution-result :send-toolCallRejected :emit-tool-called]
    :finally-actions [:deliver-future-cleanup-completed]}

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
    (f.chat.lifecycle/send-content! chat-ctx :system
                                    {:type :progress
                                     :state :running
                                     :text (progress-text @db* (:chat-id chat-ctx) (:progress-text event-data))})

    :send-toolCallPrepare
    (f.chat.lifecycle/send-content! chat-ctx :assistant
                                    (assoc-some
                                     {:type :toolCallPrepare
                                      :id tool-call-id
                                      :name (:name event-data)
                                      :server (:server event-data)
                                      :origin (:origin event-data)
                                      :arguments-text (:arguments-text event-data)}
                                     :summary (:summary event-data)))

    :send-toolCallRun
    (f.chat.lifecycle/send-content! chat-ctx :assistant
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
    (f.chat.lifecycle/send-content! chat-ctx :assistant
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
    ;; Tool results may include image content blocks (e.g. from MCP image
    ;; tools). The protocol declares toolCalled :outputs as text-only, so we
    ;; partition: text outputs go into the toolCalled event, images are
    ;; emitted as separate ChatImageContent events tagged :assistant. This
    ;; reuses the same wire shape produced by the OpenAI Responses-API
    ;; built-in image_generation tool (see chat.clj on-message-received
    ;; :image branch).
    ;; Only partition when outputs is a sequence containing image content
    ;; maps; otherwise pass it through unchanged. This preserves
    ;; pre-existing behaviors where :outputs can be nil or a plain string
    ;; (used by some non-MCP code paths and tests).
    (let [outputs (:outputs event-data)
          image? #(and (map? %) (= :image (:type %)))
          image-outputs (when (sequential? outputs) (filter image? outputs))
          text-outputs (if (seq image-outputs)
                         (vec (remove image? outputs))
                         outputs)]
      (f.chat.lifecycle/send-content! chat-ctx :assistant
                                      (assoc-some
                                       {:type :toolCalled
                                        :id tool-call-id
                                        :origin (:origin event-data)
                                        :name (:name event-data)
                                        :server (:server event-data)
                                        :arguments (:arguments event-data)
                                        :error (:error event-data)
                                        :total-time-ms (:total-time-ms event-data)
                                        :outputs text-outputs}
                                       :details (:details event-data)
                                       :summary (:summary event-data)))
      (doseq [img image-outputs]
        (f.chat.lifecycle/send-content! chat-ctx :assistant
                                        {:type :image
                                         :media-type (:media-type img)
                                         :base64 (:base64 img)})))

    :send-toolCallRejected
    (let [tool-call-state (get-tool-call-state @db* (:chat-id chat-ctx) tool-call-id)
          name (:name tool-call-state)
          server (:server tool-call-state)
          origin (:origin tool-call-state)
          arguments (:arguments tool-call-state)]
      (f.chat.lifecycle/send-content! chat-ctx :assistant
                                      (assoc-some
                                       {:type :toolCallRejected
                                        :id tool-call-id
                                        :origin (or (:origin event-data) origin)
                                        :name (or (:name event-data) name)
                                        :server (or (:server event-data) server)
                                        :arguments (or (:arguments event-data) arguments)
                                        ;; :reason is a decision reason ({:code :text}); its code is
                                        ;; kebab-case here and camelCase in the protocol.
                                        :reason (or (some-> event-data :reason :code csk/->camelCaseKeyword)
                                                    :user)}
                                       :details (:details event-data)
                                       :summary (:summary event-data))))

    :emit-tool-called
    (run-called-event! db* chat-ctx tool-call-id event-data)

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
           ;; :arguments (map), :summary, :details and :manual-approval are
           ;; initialized by the :init-arguments action
           ;; :start-time (long) is initialized by the :set-start-time action
           ;; :future (future) is added by the :add-future action and removed by :remove-future
           ;; :resources (map) is updated by the :add-resources and :remove-resources actions,
           ;; and removed wholesale by :remove-all-resources during cleanup
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
    (swap! db* update-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id]
           #(-> %
                (assoc :arguments (:arguments event-data))
                (assoc-some :summary (:summary event-data)
                            :details (:details event-data)
                            :manual-approval (:manual-approval event-data))))

    :set-decision-reason
    (swap! db* assoc-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :decision-reason]
           (:reason event-data))

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
    (swap! db* update-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id]
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

   Returns: the transition, {:status new-status :actions actions-executed}
   plus its :finally-actions, if any.

   Throws: ex-info if the transition is invalid for the current state.

   Note: The status is updated before any actions are run.
   Actions are run in the order specified."
  [db* chat-ctx tool-call-id event & [event-data]]
  (let [current-state (get-tool-call-state @db* (:chat-id chat-ctx) tool-call-id)
        current-status (:status current-state :initial) ; Default to :initial if no state
        transition-key [current-status event]
        {:keys [status actions finally-actions] :as transition} (get tool-call-state-machine transition-key)]

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

    (try
      (doseq [action actions]
        (execute-action! action db* chat-ctx tool-call-id event-data))
      (f.chat.lifecycle/trigger-chat-status-hook! (assoc chat-ctx :db* db*))
      (finally
        (doseq [action finally-actions]
          (execute-action! action db* chat-ctx tool-call-id event-data))))

    transition))

(defn report-tool-call-progress!
  "Records the latest progress of an executing tool call and re-sends its
   toolCallRunning content with it. Ignored once the call is no longer executing."
  [db* chat-ctx tool-call-id progress]
  (let [path [:chats (:chat-id chat-ctx) :tool-calls tool-call-id]
        executing? #(= :executing (get-in % (conj path :status)))
        [old-db new-db] (swap-vals! db* #(cond-> %
                                           (executing? %) (assoc-in (conj path :progress) progress)))]
    (when (executing? old-db)
      (let [{:keys [name server origin arguments details summary]} (get-in new-db path)]
        (f.chat.lifecycle/send-content! chat-ctx :assistant
                                        (assoc-some
                                         {:type :toolCallRunning
                                          :id tool-call-id
                                          :name name
                                          :server server
                                          :origin origin
                                          :arguments arguments
                                          :progress progress}
                                         :details details
                                         :summary summary))))))
