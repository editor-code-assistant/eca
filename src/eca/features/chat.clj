(ns eca.features.chat
  (:require
   [cheshire.core :as json]
   [clojure.set :as set]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.commands :as f.commands]
   [eca.features.context :as f.context]
   [eca.features.index :as f.index]
   [eca.features.login :as f.login]
   [eca.features.prompt :as f.prompt]
   [eca.features.rules :as f.rules]
   [eca.features.tools :as f.tools]
   [eca.features.tools.mcp :as f.mcp]
   [eca.llm-api :as llm-api]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.shared :as shared :refer [assoc-some]]
   [eca.metrics :as metrics]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CHAT]")

(defn default-model [db config]
  (llm-api/default-model db config))

(defn ^:private send-content! [{:keys [messenger chat-id]} role content]
  (messenger/chat-content-received
   messenger
   {:chat-id chat-id
    :role role
    :content content}))

(defn finish-chat-prompt! [status {:keys [chat-id db* metrics on-finished-side-effect] :as chat-ctx}]
  (swap! db* assoc-in [:chats chat-id :status] status)
  (send-content! chat-ctx :system
                 {:type :progress
                  :state :finished})
  (when on-finished-side-effect
    (on-finished-side-effect))
  (db/update-workspaces-cache! @db* metrics))

(defn ^:private assert-chat-not-stopped! [{:keys [chat-id db*] :as chat-ctx}]
  (when (identical? :stopping (get-in @db* [:chats chat-id :status]))
    (finish-chat-prompt! :idle chat-ctx)
    (logger/info logger-tag "Chat prompt stopped:" chat-id)
    (throw (ex-info "Chat prompt stopped" {:silent? true
                                           :chat-id chat-id}))))

;;; Helper functions for tool call state management

(defn ^:private get-tool-call-state
  "Get the complete state map for a specific tool call."
  [db chat-id tool-call-id]
  (get-in db [:chats chat-id :tool-calls tool-call-id]))

(defn ^:private get-active-tool-calls
  "Returns a map of tool-call-id -> tool calls that are still active.

  Active tool calls are those not in the following states: :completed, :rejected, :stopping, :stopped."
  [db chat-id]
  (->> (get-in db [:chats chat-id :tool-calls] {})
       (remove (fn [[_ state]]
                 (#{:completed :rejected :stopping :stopped} (:status state))))
       (into {})))

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
   - :completed           - Normal completion.  Perhaps with tool errors.  Terminal status.
   - :stopping            - In the process of stopping, after execution has started, but before it completed.
   - :stopped             - Stopped by user action after executing, but before normal completion.  Terminal status.

   Events:
   - :tool-prepare        - LLM preparing tool call (can happen multiple times).
   - :tool-run            - LLM ready to run tool call.
   - :user-approve        - User approves tool call.
   - :user-reject         - User rejects tool call.
   - :send-reject         - A made-up event to cause a toolCallReject.  Used in a context where the message data is available.
   - :execution-start     - Tool call execution begins.
   - :execution-end       - Tool call completes normally.  Perhaps with its own errors.
   - :stop-requested      - An event to request that active tool calls be stopped.
   - :resources-created   - Some new resources were created during the call.
   - :resources-destroyed - Some existing resources were destroyed.
   - :stop-attempted      - We have done all we can to stop the tool call.  The tool may or may not be actually stopped.

   Actions:
   - send-* notifications
   - set-* set various state values
   - add- and remove-resources
   - promise init & delivery
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
    :actions [:init-arguments :init-approval-promise :send-toolCallRun]}
   ;; TODO: What happens if the promise is created, but no deref happens since the call is stopped?
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
    :actions [:set-start-time :set-call-future :send-toolCallRunning :send-progress]}

   [:executing :execution-end]
   {:status :completed
    :actions [:send-toolCalled :log-metrics :send-progress]}

   [:executing :resources-created]
   {:status :executing
    :actions [:add-resources]}

   [:executing :resources-destroyed]
   {:status :executing
    :actions [:remove-resources]}

   ;; I don't think this transition is needed
   ;; [:stopping :resources-created]
   ;; {:status :stopping
   ;;  :actions [:add-resources]}

   [:stopping :resources-destroyed]
   {:status :stopping
    :actions [:remove-resources]}

   [:stopping :stop-attempted]
   {:status :stopped
    :actions [:send-toolCallRejected]}

   ;; And now all the :stop-requested transitions

   ;; Note: There are, currently, no transitions from the terminal statuses
   ;; (and :stopping) on :stop-requested.
   ;; This is because :stop-requested is only sent to active statuses.
   ;; But arguably, there should be.  For completeness and robustness.

   [:executing :stop-requested]
   {:status :stopping
    :actions []}

   [:execution-approved :stop-requested]
   {:status :stopped
    :actions [:send-toolCallRejected]}

   [:waiting-approval :stop-requested]
   {:status :rejected
    :actions [:set-decision-reason :deliver-approval-false]}

   [:check-approval :stop-requested]
   {:status :rejected
    :actions [:set-decision-reason :deliver-approval-false]}

   [:preparing :stop-requested]
   {:status :stopped
    :actions [:set-decision-reason :send-toolCallRejected]}

   [:initial :stop-requested] ; Nothing sent yet, just mark as stopped
   {:status :stopped
    :actions []}})

(defn ^:private execute-action!
  "Execute a single action during state transition"
  [action db* chat-ctx tool-call-id event-data]
  (logger/debug logger-tag "About to run the tool-call action" {:tool-call-id tool-call-id :action action :event-data event-data})
  (case action
    ;; Notification actions
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
                     :origin (:origin event-data)
                     :arguments-text (:arguments-text event-data)}
                    :summary (:summary event-data)))

    :send-toolCallRun
    (send-content! chat-ctx :assistant
                   (assoc-some
                    {:type :toolCallRun
                     :id tool-call-id
                     :name (:name event-data)
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
                     :arguments (:arguments event-data)
                     :error (:error event-data)
                     :total-time-ms (:total-time-ms event-data)
                     :outputs (:outputs event-data)}
                    :details (:details event-data)
                    :summary (:summary event-data)))

    :send-toolCallRejected
    (let [tool-call-state (get-tool-call-state @db* (:chat-id chat-ctx) tool-call-id)
          name (:name tool-call-state)
          origin (:origin tool-call-state)
          arguments (:arguments tool-call-state)]
      (send-content! chat-ctx :assistant
                     (assoc-some
                      {:type :toolCallRejected
                       :id tool-call-id
                       :origin (or (:origin event-data) origin)
                       :name (or (:name event-data) name)
                       :arguments (or (:arguments event-data) arguments)
                       :reason (:code (:reason event-data) :user)}
                      :details (:details event-data)
                      :summary (:summary event-data))))

    ;; State management actions
    :init-approval-promise
    (swap! db* assoc-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :approved?*]
           (:approved?* event-data))

    :deliver-approval-false
    (deliver (get-in @db* [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :approved?*])
             false)

    :deliver-approval-true
    (deliver (get-in @db* [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :approved?*])
             true)

    :init-tool-call-state
    (swap! db* update-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id] assoc
           ;; :status (keyword) is initialized by the state transition machinery
           ;; :approval* (promise) is initialized by the :init-approval-promise action
           ;; :arguments (map) is initialized by the :init-arguments action
           ;; :start-time (long) is initialized by the :set-start-time action
           ;; :future (future) is initialized by the :set-call-future action TODO: rename to :set-future
           ;; :resources (map) is updated by the :add-resources action
           :name (:name event-data)
           :arguments (:arguments event-data)
           :origin (:origin event-data)
           :decision-reason {:code :none
                             :text "No reason"})

    :init-arguments
    (swap! db* assoc-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :arguments]
           (:arguments event-data))

    :set-decision-reason
    (swap! db* assoc-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :decision-reason]
           (:reason event-data))

    :set-start-time
    (swap! db* assoc-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :start-time]
           (:start-time event-data))

    :set-call-future
    (swap! db* assoc-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :future]
           ;; start the future by forcing the delay and save it in the call state
           (force (:delayed-future event-data)))

    :add-resources
    (swap! db* update-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :resources]
           merge (:resources event-data))

    :remove-resources
    (swap! db* update-in [:chats (:chat-id chat-ctx) :tool-calls tool-call-id :resources]
           #(apply dissoc %1 %2) (:resources event-data))

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

(defn ^:private tool-name->origin [name all-tools]
  (:origin (first (filter #(= name (:name %)) all-tools))))

(defn ^:private tokenize-args [^String s]
  (if (string/blank? s)
    []
    (->> (re-seq #"\s*\"([^\"]*)\"|\s*([^\s]+)" s)
         (map (fn [[_ quoted unquoted]] (or quoted unquoted)))
         (vec))))

(defn ^:private message->decision [message]
  (let [slash? (string/starts-with? message "/")]
    (if slash?
      (let [command (subs message 1)
            tokens (let [toks (tokenize-args command)] (if (seq toks) toks [""]))
            first-token (first tokens)
            args (vec (rest tokens))]
        (if (and first-token (string/includes? first-token ":"))
          (let [[server prompt] (string/split first-token #":" 2)]
            {:type :mcp-prompt
             :server server
             :prompt prompt
             :args args})
          {:type :eca-command
           :command first-token
           :args args}))
      {:type :prompt-message
       :message message})))

(defn ^:private maybe-renew-auth-token! [db provider chat-ctx]
  (when-let [expires-at (get-in db [:auth provider :expires-at])]
    (when (<= (long expires-at) (quot (System/currentTimeMillis) 1000))
      (send-content! chat-ctx :system {:type :progress
                                       :state :running
                                       :text "Renewing auth token"})
      (f.login/renew-auth! provider chat-ctx
                           {:on-error (fn [error-msg]
                                        (send-content! chat-ctx :system {:type :text
                                                                         :text error-msg})
                                        (finish-chat-prompt! :idle chat-ctx)
                                        (throw (ex-info "Auth token renew failed" {})))}))))

(defn ^:private prompt-messages!
  [user-messages
   {:keys [db* config chat-id behavior full-model instructions messenger metrics] :as chat-ctx}]
  (let [[provider model] (string/split full-model #"/" 2)
        _ (maybe-renew-auth-token! @db* provider chat-ctx)
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
                             (send-content! chat-ctx :system
                                            (merge {:type :usage}
                                                   usage))))]

    (when-not (get-in db [:chats chat-id :title])
      (future
        (when-let [title @(llm-api/simple-prompt
                           {:provider provider
                            :model model
                            :model-capabilities model-capabilities
                            :instructions (f.prompt/title-prompt)
                            :user-messages user-messages
                            :config config
                            :tools []
                            :provider-auth provider-auth})]
          (swap! db* assoc-in [:chats chat-id :title] title)
          (send-content! chat-ctx :system (assoc-some
                                           {:type :metadata}
                                           :title title)))))
    (send-content! chat-ctx :system {:type :progress
                                     :state :running
                                     :text "Waiting model"})
    (llm-api/complete!
     {:model model
      :provider provider
      :model-capabilities model-capabilities
      :user-messages user-messages
      :instructions instructions
      :past-messages past-messages
      :config config
      :tools all-tools
      :provider-auth provider-auth
      :on-first-response-received (fn [& _]
                                    (assert-chat-not-stopped! chat-ctx)
                                    (doseq [message user-messages]
                                      (add-to-history! message))
                                    (send-content! chat-ctx :system {:type :progress
                                                                     :state :running
                                                                     :text "Generating"}))
      :on-usage-updated on-usage-updated
      :on-message-received (fn [{:keys [type] :as msg}]
                             (assert-chat-not-stopped! chat-ctx)
                             (case type
                               :text (do
                                       (swap! received-msgs* str (:text msg))
                                       (send-content! chat-ctx :assistant {:type :text
                                                                           :text (:text msg)}))
                               :url (send-content! chat-ctx :assistant {:type :url
                                                                        :title (:title msg)
                                                                        :url (:url msg)})
                               :limit-reached (do
                                                (send-content! chat-ctx :system
                                                               {:type :text
                                                                :text (str "API limit reached. Tokens: " (json/generate-string (:tokens msg)))})

                                                (finish-chat-prompt! :idle chat-ctx))
                               :finish (do
                                         (add-to-history! {:role "assistant" :content [{:type :text :text @received-msgs*}]})
                                         (finish-chat-prompt! :idle chat-ctx))))
      :on-prepare-tool-call (fn [{:keys [id name arguments-text]}]
                              (assert-chat-not-stopped! chat-ctx)
                              (transition-tool-call! db* chat-ctx id :tool-prepare
                                                     {:name name
                                                      :origin (tool-name->origin name all-tools)
                                                      :arguments-text arguments-text
                                                      :summary (f.tools/tool-call-summary all-tools name nil config)}))
      :on-tools-called (fn [tool-calls]
                         (assert-chat-not-stopped! chat-ctx)
                         ;; Flush any pending assistant text once before processing multiple tool calls
                         (when-not (string/blank? @received-msgs*)
                           (add-to-history! {:role "assistant" :content [{:type :text :text @received-msgs*}]})
                           (reset! received-msgs* ""))
                         (let [any-rejected-tool-call?* (atom false)]
                           (run! (fn do-tool-call [{:keys [id name arguments] :as tool-call}]
                                   (let [approved?* (promise)
                                         details (f.tools/tool-call-details-before-invocation name arguments)
                                         summary (f.tools/tool-call-summary all-tools name arguments config)
                                         origin (tool-name->origin name all-tools)
                                         approval (f.tools/approval all-tools name arguments db config behavior)
                                         ask? (= :ask approval)]
                                     ;; assert: In :preparing or :stopping or :stopped
                                     ;; Inform client the tool is about to run and store approval promise
                                     (when-not (#{:stopping :stopped} (:status (get-tool-call-state @db* chat-id id)))
                                       (transition-tool-call! db* chat-ctx id :tool-run
                                                              {:approved?* approved?*
                                                               :name name
                                                               :origin (tool-name->origin name all-tools)
                                                               :arguments arguments
                                                               :manual-approval ask?
                                                               :details details
                                                               :summary summary}))
                                     ;; assert: In: :check-approval or :stopping or :stopped or :rejected
                                     (when-not (#{:stopping :stopped :rejected} (:status (get-tool-call-state @db* chat-id id)))
                                       (case approval
                                         :ask (transition-tool-call! db* chat-ctx id :approval-ask
                                                                     {:progress-text "Waiting for tool call approval"})
                                         :allow (transition-tool-call! db* chat-ctx id :approval-allow
                                                                       {:reason {:code :user-config-allow
                                                                                 :text "Tool call allowed by user config"}})
                                         :deny (transition-tool-call! db* chat-ctx id :approval-deny
                                                                      {:reason {:code :user-config-deny
                                                                                :text "Tool call rejected by user config"}})
                                         (logger/warn logger-tag "Unknown value of approval in config"
                                                      {:approval approval :tool-call-id id})))
                                     ;; Execute each tool call concurrently
                                     (if @approved?* ;TODO: Should there be a timeout here?  If so, what would be the state transitions?
                                       ;; assert: In :execution-approved or :stopping or :stopped
                                       (when-not (#{:stopping :stopped} (:status (get-tool-call-state @db* chat-id id)))
                                         (assert-chat-not-stopped! chat-ctx)
                                         (let [;; Since a future starts executing immediately,
                                               ;; we need to delay the future so that the set-call-future action,
                                               ;; used implicitly in the transition-tool-call! on the :execution-start event,
                                               ;; can activate the future only *after* the state transition to :executing.
                                               delayed-future
                                               (delay
                                                 (future
                                                   ;; assert: In :executing
                                                   (let [result (f.tools/call-tool! name arguments behavior chat-id id db* config messenger metrics
                                                                                    (partial get-tool-call-state @db* chat-id id)
                                                                                    (partial transition-tool-call! db* chat-ctx id))
                                                         details (f.tools/tool-call-details-after-invocation name arguments details result)
                                                         {:keys [start-time]} (get-tool-call-state @db* chat-id id)]
                                                     (add-to-history! {:role "tool_call"
                                                                       :content (assoc tool-call
                                                                                       :details details
                                                                                       :summary summary
                                                                                       :origin origin)})
                                                     (add-to-history! {:role "tool_call_output"
                                                                       :content (assoc tool-call
                                                                                       :error (:error result)
                                                                                       :output result
                                                                                       :details details
                                                                                       :summary summary
                                                                                       :origin origin)})
                                                     ;; assert: In :executing or :stopping
                                                     (let [status (:status (get-tool-call-state  @db* chat-id id))]
                                                       (case status
                                                         :executing (transition-tool-call! db* chat-ctx id :execution-end
                                                                                           {:origin origin
                                                                                            :name name
                                                                                            :arguments arguments
                                                                                            :error (:error result)
                                                                                            :outputs (:contents result)
                                                                                            :total-time-ms (- (System/currentTimeMillis) start-time)
                                                                                            :progress-text "Generating"
                                                                                            :details details
                                                                                            :summary summary})
                                                         :stopping (transition-tool-call! db* chat-ctx id :stop-attempted
                                                                                          {:origin origin
                                                                                           :name name
                                                                                           :arguments arguments
                                                                                           :error (:error result)
                                                                                           :outputs (:contents result)
                                                                                           :total-time-ms (- (System/currentTimeMillis) start-time)
                                                                                           :reason :user-stop
                                                                                           :details details
                                                                                           :summary summary})
                                                         (logger/warn logger-tag "Unexpected value of :status in tool call" {:status status}))))))]
                                           (transition-tool-call! db* chat-ctx id :execution-start
                                                                  {:delayed-future delayed-future
                                                                   :origin origin
                                                                   :name name
                                                                   :arguments arguments
                                                                   :start-time (System/currentTimeMillis)
                                                                   :details details
                                                                   :summary summary
                                                                   :progress-text "Calling tool"})))
                                       ;; assert: In :rejected state
                                       (let [tool-call-state (get-tool-call-state @db* chat-id id)
                                             {:keys [code text]} (:decision-reason tool-call-state)]
                                         (add-to-history! {:role "tool_call" :content tool-call})
                                         (add-to-history! {:role "tool_call_output"
                                                           :content (assoc tool-call :output {:error true
                                                                                              :contents [{:text text
                                                                                                          :type :text}]})})
                                         (reset! any-rejected-tool-call?* true)
                                         (transition-tool-call! db* chat-ctx id :send-reject
                                                                {:origin origin
                                                                 :name name
                                                                 :arguments arguments
                                                                 :reason code
                                                                 :details details
                                                                 :summary summary})))))
                                 tool-calls)
                           (assert-chat-not-stopped! chat-ctx)
                           ;; Wait for ALL tool calls with futures to complete before returning
                           (->> (vals (get-active-tool-calls @db* chat-id))
                                (map :future)
                                (filter some?)
                                (run! deref))
                           (if @any-rejected-tool-call?*
                             (do
                               (send-content! chat-ctx :system
                                              {:type :text
                                               :text "Tell ECA what to do differently for the rejected tool(s)"})
                               (add-to-history! {:role "user" :content [{:type :text
                                                                         :text "I rejected one or more tool calls with the following reason"}]})
                               (finish-chat-prompt! :idle chat-ctx)
                               nil)
                             {:new-messages (get-in @db* [:chats chat-id :messages])})))
      :on-reason (fn [{:keys [status id text external-id]}]
                   (assert-chat-not-stopped! chat-ctx)
                   (case status
                     :started (do
                                (swap! reasonings* assoc-in [id :start-time] (System/currentTimeMillis))
                                (send-content! chat-ctx :assistant
                                               {:type :reasonStarted
                                                :id id}))
                     :thinking (do
                                 (swap! reasonings* update-in [id :text] str text)
                                 (send-content! chat-ctx :assistant
                                                {:type :reasonText
                                                 :id id
                                                 :text text}))
                     :finished (do
                                 (add-to-history! {:role "reason" :content {:id id
                                                                            :external-id external-id
                                                                            :text (get-in @reasonings* [id :text])}})
                                 (send-content! chat-ctx :assistant
                                                {:type :reasonFinished
                                                 :total-time-ms (- (System/currentTimeMillis) (get-in @reasonings* [id :start-time]))
                                                 :id id}))
                     nil))
      :on-error (fn [{:keys [message exception]}]
                  (send-content! chat-ctx :system
                                 {:type :text
                                  :text (or message (str "Error: " (ex-message exception)))})
                  (finish-chat-prompt! :idle chat-ctx))})))

(defn ^:private send-mcp-prompt!
  [{:keys [prompt args]}
   {:keys [db*] :as chat-ctx}]
  (let [{:keys [arguments]} (first (filter #(= prompt (:name %)) (f.mcp/all-prompts @db*)))
        args-vals (zipmap (map :name arguments) args)
        {:keys [messages error-message]} (f.prompt/get-prompt! prompt args-vals @db*)]
    (if error-message
      (send-content! chat-ctx :system
                     {:type :text
                      :text error-message})
      (prompt-messages! messages chat-ctx))))

(defn ^:private message-content->chat-content [role message-content]
  (case role
    ("user"
     "system"
     "assistant") (reduce
                   (fn [m content]
                     (case (:type content)
                       :text (assoc m
                                    :type :text
                                    :text (str (:text m) "\n" (:text content)))
                       m))
                   {}
                   message-content)
    "tool_call" {:type :toolCallPrepare
                 :origin (:origin message-content)
                 :name (:name message-content)
                 :arguments-text ""
                 :id (:id message-content)}
    "tool_call_output" {:type :toolCalled
                        :origin (:origin message-content)
                        :name (:name message-content)
                        :arguments (:arguments message-content)
                        :error (:error message-content)
                        :id (:id message-content)
                        :outputs (:contents (:output message-content))}
    "reason" {:id (:id message-content)
              :external-id (:external-id message-content)
              :text (:text message-content)}))

(defn ^:private handle-command! [{:keys [command args]} chat-ctx]
  (let [{:keys [type on-finished-side-effect] :as result} (f.commands/handle-command! command args chat-ctx)]
    (case type
      :chat-messages (do
                       (doseq [[chat-id messages] (:chats result)]
                         (doseq [message messages]
                           (send-content! (assoc chat-ctx :chat-id chat-id)
                                          (:role message)
                                          (message-content->chat-content (:role message) (:content message)))))
                       (finish-chat-prompt! :idle chat-ctx))
      :new-chat-status (finish-chat-prompt! (:status result) chat-ctx)
      :send-prompt (prompt-messages! [{:role "user" :content (:prompt result)}] (assoc chat-ctx :on-finished-side-effect on-finished-side-effect))
      nil)))

(defn prompt
  [{:keys [message model behavior contexts chat-id]}
   db*
   messenger
   config
   metrics]
  (let [message (string/trim message)
        chat-id (or chat-id
                    (let [new-id (str (random-uuid))]
                      (swap! db* assoc-in [:chats new-id] {:id new-id})
                      new-id))
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
        _ (when (seq contexts)
            (send-content! {:messenger messenger :chat-id chat-id} :system {:type :progress
                                                                            :state :running
                                                                            :text "Parsing given context"}))
        refined-contexts (f.context/raw-contexts->refined contexts db config)
        repo-map* (delay (f.index/repo-map db config {:as-string? true}))
        instructions (f.prompt/build-instructions refined-contexts
                                                  rules
                                                  repo-map*
                                                  selected-behavior
                                                  config)
        chat-ctx {:chat-id chat-id
                  :contexts contexts
                  :behavior selected-behavior
                  :behavior-config behavior-config
                  :instructions instructions
                  :full-model full-model
                  :db* db*
                  :metrics metrics
                  :config config
                  :messenger messenger}
        decision (message->decision message)
        image-contents (->> refined-contexts
                            (filter #(= :image (:type %))))
        user-messages [{:role "user" :content (concat [{:type :text :text message}]
                                                      image-contents)}]]
    (swap! db* assoc-in [:chats chat-id :status] :running)
    (send-content! chat-ctx :user {:type :text
                                   :text (str message "\n")})
    (case (:type decision)
      :mcp-prompt (send-mcp-prompt! decision chat-ctx)
      :eca-command (handle-command! decision chat-ctx)
      :prompt-message (prompt-messages! user-messages chat-ctx))
    (metrics/count-up! "prompt-received"
                       {:full-model full-model
                        :behavior behavior}
                       metrics)
    {:chat-id chat-id
     :model full-model
     :status :prompting}))

(defn tool-call-approve [{:keys [chat-id tool-call-id request-id]} db* messenger metrics]
  (let [chat-ctx {;; What else is needed?
                  :chat-id chat-id
                  :db* db*
                  :metrics metrics
                  :request-id request-id
                  :messenger messenger}]
    (transition-tool-call! db* chat-ctx tool-call-id :user-approve
                           {:reason {:code :user-choice-allow
                                     :text "Tool call allowed by user choice"}})))

(defn tool-call-reject [{:keys [chat-id tool-call-id request-id]} db* messenger metrics]
  (let [chat-ctx {;; What else is needed?
                  :chat-id chat-id
                  :db* db*
                  :request-id request-id
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
   :contexts (set/difference (set (f.context/all-contexts query db* config))
                             (set contexts))})

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
  [{:keys [chat-id]} db* metrics]
  (swap! db* update :chats dissoc chat-id)
  (db/update-workspaces-cache! @db* metrics))
