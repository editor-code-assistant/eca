(ns eca.features.chat
  (:require
   [cheshire.core :as json]
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
   [eca.features.tools :as f.tools]
   [eca.features.tools.mcp :as f.mcp]
   [eca.llm-api :as llm-api]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.metrics :as metrics]
   [eca.shared :as shared :refer [assoc-some future*]]))

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

(defn ^:private notify-before-hook-action! [chat-ctx {:keys [id name type visible?]}]
  (when visible?
    (send-content! chat-ctx :system
                   {:type :hookActionStarted
                    :action-type type
                    :name name
                    :id id})))

(defn ^:private notify-after-hook-action! [chat-ctx {:keys [id name output error status type visible?]}]
  (when visible?
    (send-content! chat-ctx :system
                   {:type :hookActionFinished
                    :action-type type
                    :id id
                    :name name
                    :status status
                    :output output
                    :error error})))

(defn finish-chat-prompt! [status {:keys [message chat-id db* metrics config on-finished-side-effect] :as chat-ctx}]
  (swap! db* assoc-in [:chats chat-id :status] status)
  (f.hooks/trigger-if-matches! :postRequest
                               {:chat-id chat-id
                                :prompt message}
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

  Active tool calls are those not in the following states: :completed, :rejected."
  [db chat-id]
  (->> (get-in db [:chats chat-id :tool-calls] {})
       (remove (fn [[_ state]]
                 (#{:completed :rejected} (:status state))))
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
    :actions [:set-decision-reason :deliver-approval-false]}

   [:waiting-approval :user-reject]
   {:status :rejected
    :actions [:set-decision-reason :deliver-approval-false :log-rejection]}

   [:rejected :send-reject]
   {:status :rejected
    :actions [:send-toolCallRejected]}

   [:execution-approved :hook-rejected]
   {:status :rejected
    :actions [:set-decision-reason]}

   [:execution-approved :execution-start]
   {:status :executing
    :actions [:set-start-time :add-future :send-toolCallRunning :send-progress]}

   [:executing :execution-end]
   {:status :cleanup
    :actions [:deliver-future-cleanup-completed :send-toolCalled :log-metrics :send-progress]}

   [:cleanup :cleanup-finished]
   {:status :completed
    :actions [:destroy-all-resources :remove-all-resources :remove-all-promises :remove-future :trigger-post-tool-call-hook]}

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
    :actions [:deliver-future-cleanup-completed :send-toolCallRejected]}

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
    (let [tool-call-state (get-tool-call-state @db* (:chat-id chat-ctx) tool-call-id)]
      (f.hooks/trigger-if-matches!
       :postToolCall
       {:chat-id (:chat-id chat-ctx)
        :tool-name (:name tool-call-state)
        :server (:server tool-call-state)
        :arguments (:arguments tool-call-state)}
       {:on-before-action (partial notify-before-hook-action! chat-ctx)
        :on-after-action (partial notify-after-hook-action! chat-ctx)}
       @db*
       (:config chat-ctx)))

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
          (f.tools/tool-call-destroy-resource! (:name event-data) resource-kwd resource))))

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

(defn ^:private tool-name->origin [name all-tools]
  (:origin (first (filter #(= name (:name %)) all-tools))))

(defn ^:private tool-name->server [name all-tools]
  (:server (first (filter #(= name (:name %)) all-tools))))

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
      (future* config
        (when-let [{:keys [result]} (llm-api/sync-prompt!
                                     {:provider provider
                                      :model model
                                      :model-capabilities (assoc model-capabilities
                                                                 :reason? false
                                                                 :tools false
                                                                 :web-search false)
                                      :instructions (f.prompt/title-prompt)
                                      :user-messages user-messages
                                      :config config
                                      :provider-auth provider-auth})]
          (when result
            (let [title (subs result 0 (min (count result) 30))]
              (swap! db* assoc-in [:chats chat-id :title] title)
              (send-content! chat-ctx :system (assoc-some
                                               {:type :metadata}
                                               :title title))
              ;; user prompt responded faster than title was generated
              (when (= :idle (get-in @db* [:chats chat-id :status]))
                (db/update-workspaces-cache! @db* metrics)))))))
    (send-content! chat-ctx :system {:type :progress
                                     :state :running
                                     :text "Waiting model"})
    (llm-api/async-prompt!
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
                                                      :server (tool-name->server name all-tools)
                                                      :origin (tool-name->origin name all-tools)
                                                      :arguments-text arguments-text
                                                      :summary (f.tools/tool-call-summary all-tools name nil config)}))
      :on-tools-called (fn [tool-calls]
                         ;; If there are multiple tool calls, they are allowed to execute concurrently.
                         (assert-chat-not-stopped! chat-ctx)
                         ;; Flush any pending assistant text once before processing multiple tool calls
                         (when-not (string/blank? @received-msgs*)
                           (add-to-history! {:role "assistant" :content [{:type :text :text @received-msgs*}]})
                           (reset! received-msgs* ""))
                         (let [any-rejected-tool-call?* (atom nil)]
                           (run! (fn do-tool-call [{:keys [id name arguments] :as tool-call}]
                                   (let [approved?* (promise) ; created here, stored in the state.
                                         db @db*
                                         hook-approved?* (atom true)
                                         origin (tool-name->origin name all-tools)
                                         server (tool-name->server name all-tools)
                                         server-name (:name server)
                                         approval (f.tools/approval all-tools name arguments db config behavior)
                                         ask? (= :ask approval)
                                         details (f.tools/tool-call-details-before-invocation name arguments server db ask?)
                                         summary (f.tools/tool-call-summary all-tools name arguments config)]
                                     ;; assert: In :preparing or :stopping or :cleanup
                                     ;; Inform client the tool is about to run and store approval promise
                                     (when-not (#{:stopping :cleanup} (:status (get-tool-call-state db chat-id id)))
                                       (transition-tool-call! db* chat-ctx id :tool-run
                                                              {:approved?* approved?*
                                                               :future-cleanup-complete?* (promise)
                                                               :name name
                                                               :server server-name
                                                               :origin origin
                                                               :arguments arguments
                                                               :manual-approval ask?
                                                               :details details
                                                               :summary summary}))
                                     ;; assert: In: :check-approval or :stopping or :cleanup or :rejected
                                     (when-not (#{:stopping :cleanup :rejected} (:status (get-tool-call-state db chat-id id)))
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
                                     (f.hooks/trigger-if-matches! :preToolCall
                                                                  {:chat-id chat-id
                                                                   :tool-name name
                                                                   :server server-name
                                                                   :arguments arguments}
                                                                  {:on-before-action (partial notify-before-hook-action! chat-ctx)
                                                                   :on-after-action (fn [result]
                                                                                      (when (= 2 (:status result))
                                                                                        (transition-tool-call! db* chat-ctx id :hook-rejected
                                                                                                               {:reason {:code :hook-rejected
                                                                                                                         :text (str "Tool call rejected by hook, output: " (:output result))}})
                                                                                        (reset! hook-approved?* false))
                                                                                      (notify-after-hook-action! chat-ctx result))}
                                                                  db
                                                                  config)
                                     (if (and @approved?* @hook-approved?*)
                                       ;; assert: In :execution-approved or :stopping or :cleanup
                                       (when-not (#{:stopping :cleanup} (:status (get-tool-call-state @db* chat-id id)))
                                         (assert-chat-not-stopped! chat-ctx)
                                         (let [;; Since a future starts executing immediately,
                                               ;; we need to delay the future so that the add-future action,
                                               ;; used implicitly in the transition-tool-call! on the :execution-start event,
                                               ;; can activate the future only *after* the state transition to :executing.
                                               delayed-future
                                               (delay
                                                 (future
                                                   ;; assert: In :executing
                                                   (let [result (f.tools/call-tool! name arguments chat-id id behavior db* config messenger metrics
                                                                                    (partial get-tool-call-state @db* chat-id id)
                                                                                    (partial transition-tool-call! db* chat-ctx id))
                                                         details (f.tools/tool-call-details-after-invocation name arguments details result)
                                                         {:keys [start-time]} (get-tool-call-state @db* chat-id id)
                                                         total-time-ms (- (System/currentTimeMillis) start-time)]
                                                     (add-to-history! {:role "tool_call"
                                                                       :content (assoc tool-call
                                                                                       :details details
                                                                                       :summary summary
                                                                                       :origin origin
                                                                                       :server server-name)})
                                                     (add-to-history! {:role "tool_call_output"
                                                                       :content (assoc tool-call
                                                                                       :error (:error result)
                                                                                       :output result
                                                                                       :total-time-ms total-time-ms
                                                                                       :details details
                                                                                       :summary summary
                                                                                       :origin origin
                                                                                       :server server-name)})
                                                     ;; assert: In :executing or :stopping
                                                     (let [state (get-tool-call-state  @db* chat-id id)
                                                           status (:status state)]
                                                       (case status
                                                         :executing (transition-tool-call! db* chat-ctx id :execution-end
                                                                                           {:origin origin
                                                                                            :name name
                                                                                            :server server-name
                                                                                            :arguments arguments
                                                                                            :error (:error result)
                                                                                            :outputs (:contents result)
                                                                                            :total-time-ms total-time-ms
                                                                                            :progress-text "Generating"
                                                                                            :details details
                                                                                            :summary summary})
                                                         :stopping (transition-tool-call! db* chat-ctx id :stop-attempted
                                                                                          {:origin origin
                                                                                           :name name
                                                                                           :server server-name
                                                                                           :arguments arguments
                                                                                           :error (:error result)
                                                                                           :outputs (:contents result)
                                                                                           :total-time-ms total-time-ms
                                                                                           :reason :user-stop
                                                                                           :details details
                                                                                           :summary summary})
                                                         (logger/warn logger-tag "Unexpected value of :status in tool call" {:status status}))))))]
                                           (transition-tool-call! db* chat-ctx id :execution-start
                                                                  {:delayed-future delayed-future
                                                                   :origin origin
                                                                   :name name
                                                                   :server server-name
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
                                         (reset! any-rejected-tool-call?* code)
                                         (transition-tool-call! db* chat-ctx id :send-reject
                                                                {:origin origin
                                                                 :name name
                                                                 :server server-name
                                                                 :arguments arguments
                                                                 :reason code
                                                                 :details details
                                                                 :summary summary})))))
                                 tool-calls)
                           (assert-chat-not-stopped! chat-ctx)
                           ;; assert: In :cleanup
                           ;; assert: Only those tool calls that have reached :executing have futures.
                           ;; Before we handle interrupts, we will wait for all tool calls with futures to complete naturally.
                           ;; Since a deref of a cancelled future *immediately* results in a CancellationException without waiting for the future to cleanup,
                           ;; we have to use another promise and deref that to know when the tool call is finished cleaning up.
                           (doseq [[tool-call-id state] (get-active-tool-calls @db* chat-id)]
                             (when-let [f (:future state)]
                               (try
                                 (deref f) ; TODO: A timeout would be useful for tools that get into an infinite loop.
                                 (catch java.util.concurrent.CancellationException _
                                   ;; The future was cancelled
                                   ;; TODO: Why not just wait for the promise and not bother about the future?
                                   ;; If future was cancelled, wait for the future's cleanup to finish.
                                   (when-let [p (:future-cleanup-complete?* state)]
                                     (logger/debug logger-tag "Caught CancellationException.  Waiting for future to finish cleanup."
                                                   {:tool-call-id tool-call-id
                                                    :promise p})
                                     (deref p) ; TODO: May need a timeout here too, in case the tool does not clean up.
                                     ))
                                 (catch Throwable t
                                   (logger/debug logger-tag "Ignoring a Throwable while deref'ing a tool call future"
                                                 {:tool-call-id tool-call-id
                                                  :ex-data (ex-data t)
                                                  :message (.getMessage t)
                                                  :cause (.getCause t)}))
                                 (finally (try
                                            (transition-tool-call! db* chat-ctx tool-call-id :cleanup-finished
                                                                   {:name name})
                                            (catch Throwable t
                                              (logger/debug logger-tag "Ignoring an exception while finishing tool call"
                                                            {:tool-call-id tool-call-id
                                                             :ex-data (ex-data t)
                                                             :message (.getMessage t)
                                                             :cause (.getCause t)})))))))
                           (if-let [reason-code @any-rejected-tool-call?*]
                             (do
                               (if (= :hook-rejected reason-code)
                                 (do
                                   (send-content! chat-ctx :system
                                                  {:type :text
                                                   :text "Tool rejected by hook"})
                                   (add-to-history! {:role "user" :content [{:type :text
                                                                             :text "A user hook rejected one or more tool calls with the following reason"}]}))
                                 (do
                                   (send-content! chat-ctx :system
                                                  {:type :text
                                                   :text "Tell ECA what to do differently for the rejected tool(s)"})
                                   (add-to-history! {:role "user" :content [{:type :text
                                                                             :text "I rejected one or more tool calls with the following reason"}]})))
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
                     :finished (let [total-time-ms (- (System/currentTimeMillis) (get-in @reasonings* [id :start-time]))]
                                 (add-to-history! {:role "reason" :content {:id id
                                                                            :external-id external-id
                                                                            :total-time-ms total-time-ms
                                                                            :text (get-in @reasonings* [id :text])}})
                                 (send-content! chat-ctx :assistant
                                                {:type :reasonFinished
                                                 :total-time-ms total-time-ms
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
     "assistant") [(reduce
                    (fn [m content]
                      (case (:type content)
                        :text (assoc m
                                     :type :text
                                     :text (str (:text m) "\n" (:text content)))
                        m))
                    {}
                    message-content)]
    "tool_call" [{:type :toolCallPrepare
                  :origin (:origin message-content)
                  :name (:name message-content)
                  :server (:server message-content)
                  :arguments-text ""
                  :id (:id message-content)}]
    "tool_call_output" [{:type :toolCalled
                         :origin (:origin message-content)
                         :name (:name message-content)
                         :server (:server message-content)
                         :arguments (:arguments message-content)
                         :total-time-ms (:total-time-ms message-content)
                         :error (:error message-content)
                         :id (:id message-content)
                         :outputs (:contents (:output message-content))}]
    "reason" [{:type :reasonStarted
               :id (:id message-content)}
              {:type :reasonText
               :id (:id message-content)
               :text (:text message-content)}
              {:type :reasonFinished
               :id (:id message-content)
               :total-time-ms (:total-time-ms message-content)}]))

(defn ^:private handle-command! [{:keys [command args]} chat-ctx]
  (let [{:keys [type on-finished-side-effect] :as result} (f.commands/handle-command! command args chat-ctx)]
    (case type
      :chat-messages (do
                       (doseq [[chat-id {:keys [messages title]}] (:chats result)]
                         (doseq [message messages]
                           (let [new-chat-ctx (assoc chat-ctx :chat-id chat-id)]
                             (doseq [chat-content (message-content->chat-content (:role message) (:content message))]
                               (send-content! new-chat-ctx
                                              (:role message)
                                              chat-content))
                             (when title
                               (send-content! new-chat-ctx :system (assoc-some
                                                                    {:type :metadata}
                                                                    :title title))))))
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
        refined-contexts (concat
                          (f.context/agents-file-contexts db)
                          (f.context/raw-contexts->refined contexts db))
        repo-map* (delay (f.index/repo-map db config {:as-string? true}))
        instructions (f.prompt/build-instructions refined-contexts
                                                  rules
                                                  repo-map*
                                                  selected-behavior
                                                  config)
        image-contents (->> refined-contexts
                            (filter #(= :image (:type %))))
        expanded-prompt-contexts (when-let [contexts-str (some-> (f.context/contexts-str-from-prompt message db)
                                                                 seq
                                                                 (f.prompt/contexts-str repo-map*))]
                                   [{:type :text :text contexts-str}])
        user-messages [{:role "user" :content (vec (concat [{:type :text :text message}]
                                                           expanded-prompt-contexts
                                                           image-contents))}]
        chat-ctx {:chat-id chat-id
                  :message message
                  :contexts contexts
                  :behavior selected-behavior
                  :behavior-config behavior-config
                  :instructions instructions
                  :user-messages user-messages
                  :full-model full-model
                  :db* db*
                  :metrics metrics
                  :config config
                  :messenger messenger}
        decision (message->decision message)
        hook-outputs* (atom [])
        _ (f.hooks/trigger-if-matches! :preRequest
                                       {:chat-id chat-id
                                        :prompt message}
                                       {:on-before-action (partial notify-before-hook-action! chat-ctx)
                                        :on-after-action (fn [result]
                                                           (when (and (= 0 (:status result))
                                                                      (:output result))
                                                             (swap! hook-outputs* conj (:output result)))
                                                           (notify-after-hook-action! chat-ctx result))}
                                       db
                                       config)
        user-messages (if (seq @hook-outputs*)
                        (update-in user-messages [0 :content 0 :text] #(str % " " (string/join "\n" @hook-outputs*)))
                        user-messages)]
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
  [{:keys [chat-id]} db* metrics]
  (swap! db* update :chats dissoc chat-id)
  (db/update-workspaces-cache! @db* metrics))
