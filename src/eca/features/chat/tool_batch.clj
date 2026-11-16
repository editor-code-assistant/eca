(ns eca.features.chat.tool-batch
  "A batch of tool calls the model asked for: each call's decision from the
   `:tool/before-call` event, running them, and how the turn goes on."
  (:require
   [clojure.string :as string]
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.chat.lifecycle :as f.chat.lifecycle]
   [eca.features.chat.tool-calls :as f.chat.tool-calls]
   [eca.features.tools :as f.tools]
   [eca.features.tools.util :as tools.util]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.modules.events :as events]
   [eca.modules.mcp :as m.mcp]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CHAT]")

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

(def ^:private rejected-tool-call-message
  "Leading message for every rejected/blocked tool call result sent back to the
   LLM. Some models otherwise assume a rejected edit was actually applied (#507),
   so this states plainly that the call never ran and changed nothing."
  (str "This tool call did NOT run and made NO changes. Any files, code, or "
       "system state remain exactly as they were before this call, so do not "
       "assume the action was applied or succeeded. If it is still needed, ask "
       "the user how to proceed or try a different approach."))

(defn ^:private rejected-tool-call-output-contents
  "LLM-facing :contents for a rejected/blocked tool call: the standard
   `rejected-tool-call-message` plus the specific reason, when present."
  [reason-text]
  [{:type :text
    :text (cond-> rejected-tool-call-message
            (shared/not-blank reason-text) (str "\nReason: " reason-text))}])

(def ^:private max-invalid-arguments-rounds
  "How many LLM responses in a row may hold a tool call with invalid JSON
   arguments. The LLM gets the error back to retry; at this number the turn
   stops, as a model that keeps failing would otherwise loop forever."
  3)

(defn ^:private invalid-arguments-tool-call-output-contents
  "LLM-facing :contents for a tool call whose arguments were not valid JSON
   (see `llm-util/parse-tool-call-arguments`), so it can send the call again."
  [arguments-error]
  [{:type :text
    :text (str "This tool call did NOT run and made NO changes, because its arguments were not valid JSON: "
               arguments-error
               "\nIf the call is still needed, send it again with valid JSON arguments.")}])

(defn ^:private post-tool-call-stop-info
  "Single pass over tool-call states. Returns
   {:stop-turn? boolean :stop-reason string-or-nil :ended-by map-or-nil}
   describing whether a `:tool/after-call` or `:tool/called` handler (e.g. a
   postToolCall hook's continue:false) stopped the turn in this prompt, and
   the first stop reason and who stopped found, if any."
  [tool-calls prompt-id]
  (let [current-prompt-stop? (fn [state]
                               (and (some? prompt-id)
                                    (:post-tool-call-stop? state)
                                    (= prompt-id (:post-tool-call-stop-prompt-id state))))]
    (reduce (fn [acc [_ state]]
              (if (current-prompt-stop? state)
                (cond-> (assoc acc :stop-turn? true)
                  (and (nil? (:stop-reason acc)) (:post-tool-call-stop-reason state))
                  (assoc :stop-reason (:post-tool-call-stop-reason state))

                  (and (nil? (:ended-by acc)) (:post-tool-call-stop-ended-by state))
                  (assoc :ended-by (:post-tool-call-stop-ended-by state)))
                acc))
            {:stop-turn? false :stop-reason nil :ended-by nil}
            tool-calls)))

;;; Helper functions for tool call state management

(defn ^:private run-after-call-event!
  "Runs the `:tool/after-call` event for the `result` of a tool call that ran.
   Returns `{:result :module-contexts}`: the result, which modules may
   replace, and the contexts they add for the model. A module's `:stop` is
   recorded in the tool call state like a postToolCall hook's, so the turn
   ends after this batch, and then `:tool/called` isn't emitted."
  [{:keys [db* chat-id prompt-id] :as chat-ctx} tool-call-id {:keys [full-name server arguments]} result]
  (logger/with-chat-context chat-id (db/parent-chat-id @db* chat-id)
    (let [event (events/emit! (assoc chat-ctx :tool-call-id tool-call-id) :tool/after-call
                              (f.chat.lifecycle/chat-event chat-ctx {:tool full-name
                                                                     :server server
                                                                     :arguments arguments
                                                                     :result result
                                                                     :error? (boolean (:error result))
                                                                     :tool-call-id tool-call-id}))
          new-result (:result event)
          changed-result (when-not (identical? result new-result)
                           (or (f.chat.tool-calls/module-result result new-result)
                               (do (logger/warn logger-tag "Ignoring the :result a :tool/after-call handler set"
                                                {:tool-call-id tool-call-id :result new-result})
                                   nil)))]
      (when (contains? event :stop)
        (swap! db* update-in [:chats chat-id :tool-calls tool-call-id] assoc
               :post-tool-call-stop? true
               :post-tool-call-stop-prompt-id prompt-id
               :post-tool-call-stop-reason (shared/not-blank (:stop event))
               :post-tool-call-stop-ended-by (f.chat.lifecycle/ended-by event)))
      {:result (or changed-result result)
       :module-contexts (::events/contexts event)})))

(defn ^:private with-module-contexts
  "`output`, a tool call output, with the modules' contexts for the model,
   `{:module :content :data}`, after its contents."
  [output contexts]
  (if (seq contexts)
    (update output :contents #(into (vec %) (map f.chat.lifecycle/context-block) contexts))
    output))

(defn ^:private valid-before-call-event
  "`event`, as the `:tool/before-call` handlers left it, with their changes of
   the wrong type undone, so they can't break the call: `:approval` must be
   :allow, :ask or :deny (else the call asks), `:arguments` a map, and
   `:reason` a map with a keyword `:code`."
  [event arguments]
  (let [invalid (cond-> []
                  (not (#{:allow :ask :deny} (:approval event)))
                  (conj :approval)

                  (not (or (map? (:arguments event)) (identical? arguments (:arguments event))))
                  (conj :arguments)

                  (and (contains? event :reason)
                       (not (and (map? (:reason event)) (keyword? (get-in event [:reason :code])))))
                  (conj :reason))]
    (when (seq invalid)
      (logger/warn logger-tag "Ignoring values of the wrong type :tool/before-call handlers set"
                   (select-keys event invalid)))
    (cond-> event
      (some #{:approval} invalid) (assoc :approval :ask)
      (some #{:arguments} invalid) (assoc :arguments arguments)
      (some #{:reason} invalid) (dissoc :reason))))

(defn ^:private decide-tool-call-action
  "Decides what to do with a tool call from the `:tool/before-call` event:
   :eca/approval sets the approval from the config rules first (trust
   promotion included) and keeps a config deny after the other modules,
   which may change the approval or the arguments, block the call or stop
   the turn; the preToolCall hooks come last (4.17). Without any module
   setting it, the call asks.

   Returns a plan (data) with:
   - :decision (:ask | :allow | :deny)
   - :arguments (as the handlers left them)
   - :tool-call-rejected-by-hook? (boolean, a handler's :block, e.g. a hook's exit 2 or deny)
   - :tool-call-blocked-by-hook? (boolean, a :block or a :stop prevents execution)
   - :reason (map with :code and :text, when decision is :allow or :deny)
   - :stop-turn? (boolean, a handler's :stop, e.g. a hook's continue:false)
   - :stop-reason (string, for turn stops)
   - :ended-by (who stopped the turn, see `f.chat.lifecycle/ended-by`)
   - :module-contexts (the modules' contexts for the call's output, `{:module :content :data}`)

   `chat-ctx` is the handlers' ctx: the hooks show their actions in the chat."
  [{:keys [full-name arguments] tool-call-id :id} all-tools db config agent-name chat-id
   & [{:keys [chat-ctx trust full-model variant]}]]
  (let [tool (f.tools/resolve-tool full-name all-tools)
        ;; Which rule caused the config's decision, to help debugging approval configs. #536
        _ (logger/debug logger-tag "Tool call approval decision"
                        (assoc (f.tools/approval-decision all-tools tool arguments db config agent-name
                                                          {:trust trust :chat-id chat-id})
                               :tool full-name :agent agent-name))
        event (events/emit! (assoc chat-ctx :config config :db db :all-tools all-tools :trust trust
                                   :chat-id chat-id :agent agent-name
                                   ;; for :eca/approval to keep a deny from the config
                                   :requested-arguments arguments)
                            :tool/before-call
                            {:tool (:full-name tool full-name)
                             :server (:name (:server tool))
                             :arguments arguments
                             :approval :ask
                             :tool-call-id tool-call-id
                             :chat-id chat-id
                             :agent agent-name
                             :model full-model
                             :variant variant})
        event (valid-before-call-event event arguments)
        final-arguments (:arguments event arguments)
        approval (:approval event)
        trusted? (= :trust-allow (get-in event [:reason :code]))

        ;; A :block (a hook's exit 2 or deny) rejects the call; a :stop (a
        ;; hook's continue:false) ends the turn.
        block (when (:block event)
                (or (shared/not-blank (:block event))
                    (str "Tool call blocked by module '" (some-> (::events/ended-by event) symbol) "'")))
        stop-turn? (contains? event :stop)
        tool-call-rejected-by-hook? (some? block)
        ;; A current-turn stop is not a tool-specific rejection, but it still
        ;; blocks this prepared tool and downstream must stop the agent loop.
        ;; tool-call-blocked-by-hook? = any intervention that prevents the
        ;; tool from running.
        tool-call-blocked-by-hook? (or tool-call-rejected-by-hook? stop-turn?)
        turn-stopped-only? (and stop-turn? (not tool-call-rejected-by-hook?))
        final-decision (if tool-call-blocked-by-hook? :deny approval)
        ;; each module's context, `{:module :content :data}`
        module-contexts (::events/contexts event)

        ;; The reason map. A current-turn stop is represented separately from a
        ;; tool-specific rejection so downstream code and the LLM-visible history
        ;; do not conflate the two intents.
        ;; The modules' :reason explains their decision; :eca/approval's says
        ;; trust or user config, as before modules. A module that changes the
        ;; approval without a :reason leaves the earlier one, so check it fits.
        event-reason-code (get-in event [:reason :code])
        allow-reason (when-not (= :user-config-deny event-reason-code) (:reason event))
        deny-reason (when-not (#{:trust-allow :user-config-allow} event-reason-code) (:reason event))
        reason (case final-decision
                 :allow (or allow-reason
                            (if trusted?
                              {:code :trust-allow
                               :text "Tool call allowed by trust mode"}
                              {:code :user-config-allow
                               :text "Tool call allowed by user config"}))
                 :deny (cond
                         ;; Turn stop: LLM-visible text must stay neutral;
                         ;; stopReason is surfaced separately to the user.
                         turn-stopped-only?
                         {:code :hook-stopped
                          :text "Tool call stopped by hook"}

                         tool-call-rejected-by-hook?
                         {:code :hook-rejected
                          :text block}

                         (and (= :deny approval) deny-reason)
                         deny-reason

                         :else
                         {:code :user-config-deny
                          :text "Tool call rejected by user config"})
                 nil)]

    ;; Return the decision plan
    (cond-> {:decision final-decision
             :arguments final-arguments
             :tool-call-rejected-by-hook? tool-call-rejected-by-hook?
             :tool-call-blocked-by-hook? tool-call-blocked-by-hook?
             :stop-turn? stop-turn?
             :arguments-modified? (not= arguments final-arguments)}
      reason (assoc :reason reason)
      stop-turn? (assoc :stop-reason (shared/not-blank (:stop event))
                        :ended-by (f.chat.lifecycle/ended-by event))
      (seq module-contexts) (assoc :module-contexts module-contexts))))

(defn on-tools-called! [{:keys [db* config chat-id agent messenger metrics invalid-arguments-rounds*]
                         ;; One counter per returned fn, i.e. per LLM tool loop:
                         ;; see `max-invalid-arguments-rounds`.
                         :or {invalid-arguments-rounds* (atom 0)}
                         :as chat-ctx}
                        received-msgs* add-to-history! user-messages]
  (fn [tool-calls]
    ;; postToolCall hooks report continue:false through the tool call state,
    ;; accumulated per-tool by the state machine action :trigger-post-tool-call-hook.
    (let [all-tools (f.tools/all-tools chat-id agent @db* config)
          ;; Checked first so the final summary turn doesn't count as a step.
          summary-requested? (get-in @db* [:chats chat-id :summary-requested?])
          max-steps-reached? (and (not summary-requested?)
                                  (check-subagent-max-steps! db* chat-id))]
      (f.chat.lifecycle/assert-chat-not-stopped! chat-ctx)
      ;; Subagent reached max steps or was asked for its final summary,
      ;; finish without executing more tools
      (if (or summary-requested? max-steps-reached?)
        (do
          (if summary-requested?
            (logger/info logger-tag "Subagent tried to call tools during its final summary, finishing" {:chat-id chat-id})
            (do
              (logger/info logger-tag "Subagent reached max steps, finishing" {:chat-id chat-id})
              (swap! db* assoc-in [:chats chat-id :max-steps-reached?] true)))
          (when-not (string/blank? @received-msgs*)
            (add-to-history! {:role "assistant" :content [{:type :text :text @received-msgs*}]}))
          (f.chat.lifecycle/finish-chat-prompt! :idle chat-ctx)
          nil)
        (do
          (when-not (string/blank? @received-msgs*)
            (add-to-history! {:role "assistant" :content [{:type :text :text @received-msgs*}]})
            (reset! received-msgs* ""))
          (if (some :arguments-error tool-calls)
            (swap! invalid-arguments-rounds* inc)
            (reset! invalid-arguments-rounds* 0))
          (let [blocked-tool-call-infos* (atom (sorted-map))
                prepare-tool-call
                (fn prepare-tool-call [idx {:keys [id full-name] :as tool-call}]
                  (let [approved?*                                                    (promise)
                        {:keys [origin name server parameters]
                         :as   resolved-tool}                                           (f.tools/resolve-tool full-name all-tools)
                        full-name                                                     (or (:full-name resolved-tool) full-name)
                        server-name                                                   (:name server)
                        normalize-arguments                                           (or (:normalize-arguments-fn resolved-tool) identity)
                        arguments                                                     (if parameters
                                                                                        (tools.util/omit-optional-empty-string-args parameters (:arguments tool-call))
                                                                                        (:arguments tool-call))
                        tool-call                                                     (assoc tool-call
                                                                                             :full-name full-name
                                                                                             :arguments (normalize-arguments arguments))
                        arguments-error                                               (:arguments-error tool-call)
                        _                                                             (when arguments-error
                                                                                        (logger/warn logger-tag "Tool call arguments are not valid JSON, rejecting the call"
                                                                                                     {:tool-call-id id
                                                                                                      :tool full-name
                                                                                                      :error arguments-error}))
                        decision-plan                                                 (if arguments-error
                                                                                        ;; Nothing valid to approve or pass to hooks: reject with
                                                                                        ;; the parse error, which the LLM gets as the call's result.
                                                                                        {:decision  :deny
                                                                                         :arguments (:arguments tool-call)
                                                                                         :reason    {:code :invalid-arguments
                                                                                                     :text arguments-error}}
                                                                                        (decide-tool-call-action
                                                                                         tool-call all-tools @db* config agent chat-id
                                                                                         {:chat-ctx              chat-ctx
                                                                                          :trust                 (db/resolve-trust @db* chat-id)
                                                                                          :full-model            (:full-model chat-ctx)
                                                                                          :variant               (:variant chat-ctx)}))
                        {:keys [decision tool-call-blocked-by-hook? reason stop-turn? stop-reason ended-by]
                         before-contexts :module-contexts} decision-plan
                        ;; updatedInput is surfaced inside the real preToolCall
                        ;; hook finished block (see eca.modules.hooks/format-hook-output),
                        ;; so it respects visible:false / suppressOutput and the
                        ;; hook's real id/name. No synthetic event here.
                        arguments                                                     (normalize-arguments (:arguments decision-plan))
                        _                                                             (swap! db* assoc-in [:chats chat-id :tool-calls id :arguments] arguments)
                        tool-call                                                     (assoc tool-call :arguments arguments)
                        ask?                                                          (= :ask decision)
                        details                                                       (when-not arguments-error
                                                                                        (f.tools/tool-call-details-before-invocation name arguments server @db* config chat-id ask? id))
                        summary                                                       (f.tools/tool-call-summary all-tools full-name arguments config @db*)]
                    {:ask? ask?
                     :stop-batch? (and tool-call-blocked-by-hook? stop-turn?)
                     :run! (fn run-tool-call! []
                             (when-not (#{:stopping :cleanup} (:status (f.chat.tool-calls/get-tool-call-state @db* chat-id id)))
                               (f.chat.tool-calls/transition-tool-call! db* chat-ctx id :tool-run {:approved?*                approved?*
                                                                                 :future-cleanup-complete?* (promise)
                                                                                 :name                      name
                                                                                 :server                    server-name
                                                                                 :origin                    origin
                                                                                 :arguments                 arguments
                                                                                 :manual-approval           ask?
                                                                                 :details                   details
                                                                                 :summary                   summary}))
                             (when-not (#{:stopping :cleanup :rejected} (:status (f.chat.tool-calls/get-tool-call-state @db* chat-id id)))
                               (case decision
                                 :ask   (f.chat.tool-calls/transition-tool-call! db* chat-ctx id :approval-ask {:progress-text f.chat.tool-calls/waiting-approval-progress-text})
                                 :allow (f.chat.tool-calls/transition-tool-call! db* chat-ctx id :approval-allow {:reason reason})
                                 :deny  (f.chat.tool-calls/transition-tool-call! db* chat-ctx id :approval-deny {:reason reason})
                                 (logger/warn logger-tag "Unknown value of approval" {:approval decision :tool-call-id id})))
                             (if (and @approved?* (not tool-call-blocked-by-hook?))
                               (when-not (#{:stopping :cleanup} (:status (f.chat.tool-calls/get-tool-call-state @db* chat-id id)))
                                 (f.chat.lifecycle/assert-chat-not-stopped! chat-ctx)
                                 (let [delayed-future
                                       (delay
                                         (future
                                           (let [tool-result          (f.tools/call-tool! full-name
                                                                                          arguments
                                                                                          chat-id
                                                                                          id
                                                                                          agent
                                                                                          db*
                                                                                          config
                                                                                          messenger
                                                                                          metrics
                                                                                          #(f.chat.tool-calls/get-tool-call-state @db* chat-id id)
                                                                                          (partial f.chat.tool-calls/transition-tool-call! db* chat-ctx id)
                                                                                          {:trust       (db/resolve-trust @db* chat-id)
                                                                                           :on-progress (partial f.chat.tool-calls/report-tool-call-progress! db* chat-ctx id)})
                                                 details              (f.tools/tool-call-details-after-invocation name arguments details tool-result
                                                                                                                  {:db           @db*
                                                                                                                   :config       config
                                                                                                                   :chat-id      chat-id
                                                                                                                   :tool-call-id id})
                                                 ;; Modules may replace the result and add context
                                                 ;; for the model, which only the history gets.
                                                 {result         :result
                                                  after-contexts :module-contexts}
                                                 (run-after-call-event! chat-ctx id {:full-name full-name
                                                                                     :server    server-name
                                                                                     :arguments arguments}
                                                                        tool-result)
                                                 {:keys [start-time]} (f.chat.tool-calls/get-tool-call-state @db* chat-id id)
                                                 total-time-ms        (- (System/currentTimeMillis) start-time)]
                                             (add-to-history! {:role    "tool_call"
                                                               :content (assoc tool-call
                                                                               :name name
                                                                               :details details
                                                                               :summary summary
                                                                               :origin origin
                                                                               :server server-name)})
                                             (add-to-history! {:role    "tool_call_output"
                                                               :content (assoc tool-call
                                                                               :name name
                                                                               :error (:error result)
                                                                               :output (with-module-contexts result (concat before-contexts after-contexts))
                                                                               :total-time-ms total-time-ms
                                                                               :details details
                                                                               :summary summary
                                                                               :origin origin
                                                                               :server server-name)})
                                             (let [state (f.chat.tool-calls/get-tool-call-state @db* chat-id id) status (:status state)]
                                               (case status
                                                 :executing (f.chat.tool-calls/transition-tool-call! db*
                                                                                   chat-ctx
                                                                                   id
                                                                                   :execution-end {:full-name     full-name
                                                                                                   :origin        origin
                                                                                                   :name          name
                                                                                                   :server        server-name
                                                                                                   :arguments     arguments
                                                                                                   :error         (:error result)
                                                                                                   :outputs       (:contents result)
                                                                                                   :total-time-ms total-time-ms
                                                                                                   :progress-text "Generating"
                                                                                                   :details       details
                                                                                                   :summary       summary})
                                                 :stopping  (f.chat.tool-calls/transition-tool-call! db*
                                                                                   chat-ctx
                                                                                   id
                                                                                   :stop-attempted {:full-name     full-name
                                                                                                    :origin        origin
                                                                                                    :name          name
                                                                                                    :server        server-name
                                                                                                    :arguments     arguments
                                                                                                    :error         (:error result)
                                                                                                    :outputs       (:contents result)
                                                                                                    :total-time-ms total-time-ms
                                                                                                    :reason        {:code :user-stop}
                                                                                                    :details       details
                                                                                                    :summary       summary})
                                                 (logger/warn logger-tag "Unexpected value of :status in tool call" {:status status}))))))]
                                   (f.chat.tool-calls/transition-tool-call! db*
                                                          chat-ctx
                                                          id
                                                          :execution-start {:delayed-future delayed-future
                                                                            :origin         origin
                                                                            :name           name
                                                                            :server         server-name
                                                                            :arguments      arguments
                                                                            :start-time     (System/currentTimeMillis)
                                                                            :details        details
                                                                            :summary        summary
                                                                            :progress-text  (if (= name "spawn_agent")
                                                                                              "Waiting subagent"
                                                                                              "Calling tool")})))
                               (let [tool-call-state (f.chat.tool-calls/get-tool-call-state @db* chat-id id)
                                     {:keys [code text]} (:decision-reason tool-call-state)]
                                 (add-to-history! {:role "tool_call" :content tool-call})
                                 (add-to-history! {:role    "tool_call_output"
                                                   :content (assoc tool-call :output (with-module-contexts
                                                                                      {:error true
                                                                                       :contents (if (= :invalid-arguments code)
                                                                                                   (invalid-arguments-tool-call-output-contents text)
                                                                                                   (rejected-tool-call-output-contents text))}
                                                                                      before-contexts))})
                                 ;; Keyed by batch position: calls run out of order, but the
                                 ;; last rejected call in the batch still decides the outcome.
                                 (swap! blocked-tool-call-infos* assoc idx
                                        {:code code
                                         :stop-turn? (when tool-call-blocked-by-hook? stop-turn?)
                                         :stop-reason (when tool-call-blocked-by-hook? stop-reason)
                                         :ended-by (when tool-call-blocked-by-hook? ended-by)})
                                 (f.chat.tool-calls/transition-tool-call! db* chat-ctx id :send-reject {:origin    origin
                                                                                      :name      name
                                                                                      :server    server-name
                                                                                      :arguments arguments
                                                                                      :reason    {:code code}
                                                                                      :details   details
                                                                                      :summary   summary}))))}))
                prepared (reduce (fn [acc [idx tool-call]]
                                   (let [prepared-call (prepare-tool-call idx tool-call)]
                                     ;; preToolCall continue:false short-circuits the rest of this batch.
                                     (cond-> (conj acc prepared-call)
                                       (:stop-batch? prepared-call) reduced)))
                                 []
                                 (map-indexed vector tool-calls))]
            ;; Auto-decided calls start first so an approval prompt doesn't hold them back.
            (run! #((:run! %)) (remove :ask? prepared))
            (run! #((:run! %)) (filter :ask? prepared))
            (doseq [[tool-call-id state] (f.chat.tool-calls/get-active-tool-calls @db* chat-id)]
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
                     (finally (try (let [tool-call-state (f.chat.tool-calls/get-tool-call-state @db* (:chat-id chat-ctx) tool-call-id)]
                                     (f.chat.tool-calls/transition-tool-call!
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
            (f.chat.lifecycle/assert-chat-not-stopped! chat-ctx)
            (m.mcp/await-pending-tools-refresh @db* 5000)
            ;; Token can expire during long tool calls (e.g. spawn_agent),
            ;; so renew before any continuation branch.
            (f.chat.lifecycle/maybe-renew-auth-token chat-ctx)
            (let [all-tools (f.tools/all-tools chat-id agent @db* config)
                  refreshed-provider-auth (get-in @db* [:auth (:provider chat-ctx)])
                  [_ refreshed-api-key] (llm-util/provider-api-key (:provider chat-ctx)
                                                                   refreshed-provider-auth
                                                                   (config/all @db*))
                  with-fresh-auth #(some-> % (assoc :fresh-api-key refreshed-api-key
                                                    :provider-auth refreshed-provider-auth))]
              (if-let [blocked-info (let [blocked-infos (vals @blocked-tool-call-infos*)]
                                      ;; A real rejection in the batch decides over calls with invalid arguments.
                                      (or (last (remove #(= :invalid-arguments (:code %)) blocked-infos))
                                          (last blocked-infos)))]
                (let [reason-code
                      (if (map? blocked-info) (:code blocked-info) blocked-info)
                      stop-turn?
                      (when (map? blocked-info) (:stop-turn? blocked-info))
                      stop-reason
                      (when (map? blocked-info) (:stop-reason blocked-info))
                      ended-by
                      (when (map? blocked-info) (:ended-by blocked-info))]
                  (if (contains? #{:hook-rejected :hook-stopped :invalid-arguments} reason-code)
                    (cond
                      ;; Turn stop: stopReason is user-only; tool_call_output stays neutral.
                      stop-turn?
                      (do (f.chat.lifecycle/send-ended! chat-ctx "Turn stopped" ended-by stop-reason)
                          (f.chat.lifecycle/finish-chat-prompt-stopped! :idle chat-ctx)
                          nil)

                      ;; The LLM keeps sending invalid arguments: end the turn as failed.
                      (and (= :invalid-arguments reason-code)
                           (>= @invalid-arguments-rounds* max-invalid-arguments-rounds))
                      (let [message (format "The model sent tool calls with invalid JSON arguments %d times in a row, so the turn was stopped."
                                            max-invalid-arguments-rounds)]
                        (logger/warn logger-tag "Stopping the turn, tool call arguments are invalid again" {:chat-id chat-id})
                        (swap! db* update-in [:chats chat-id]
                               #(-> %
                                    (dissoc :auto-compacting? :compacting?)
                                    (assoc :prompt-error {:message message
                                                          :error-type :invalid-tool-arguments})))
                        (f.chat.lifecycle/send-content! chat-ctx :system {:type :text :text (str "\n\n" message)})
                        (f.chat.lifecycle/finish-chat-prompt-stopped! :idle chat-ctx)
                        nil)

                      :else
                      (with-fresh-auth
                        {:tools all-tools
                         :new-messages (shared/messages-after-last-compact-marker
                                        (get-in @db* [:chats chat-id :messages]))}))
                    (if (get-in @db* [:chats chat-id :subagent])
                      ;; Subagent: user can't provide rejection input directly, so continue
                      ;; the LLM loop with a rejection message letting the subagent adapt
                      (do (add-to-history! {:role "user"
                                            :content [{:type :text
                                                       :text "I rejected one or more tool calls; they did not run and nothing was changed. Try a different approach to complete the task."}]})
                          (with-fresh-auth
                            {:tools all-tools
                             :new-messages (shared/messages-after-last-compact-marker
                                            (get-in @db* [:chats chat-id :messages]))}))
                      (do (f.chat.lifecycle/send-content! chat-ctx :system {:type :text
                                                                            :text "Tell ECA what to do differently for the rejected tool(s)"})
                          (add-to-history! {:role "user"
                                            :content [{:type :text
                                                       :text "I rejected one or more tool calls with the following reason"}]})
                          (f.chat.lifecycle/finish-chat-prompt! :idle chat-ctx)
                          nil))))
                (let [{:keys [stop-turn? stop-reason ended-by]}
                      (post-tool-call-stop-info (get-in @db* [:chats chat-id :tool-calls] {})
                                                (:prompt-id chat-ctx))]
                  (if stop-turn?
                    (do
                      (f.chat.lifecycle/send-ended! chat-ctx "Turn stopped" ended-by stop-reason)
                      (f.chat.lifecycle/finish-chat-prompt-stopped! :idle chat-ctx)
                      nil)
                    (with-fresh-auth
                      (if-let [continue-fn (:continue-fn chat-ctx)]
                        (continue-fn all-tools user-messages)
                        {:tools all-tools
                         :new-messages (shared/messages-after-last-compact-marker
                                        (get-in @db* [:chats chat-id :messages]))}))))))))))))
