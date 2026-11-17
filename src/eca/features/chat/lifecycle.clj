(ns eca.features.chat.lifecycle
  (:require
   [clojure.string :as string]
   [eca.db :as db]
   [eca.db.chats :as db.chats]
   [eca.features.login :as f.login]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.modules.events :as events]
   [eca.shared :as shared :refer [assoc-some]]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CHAT]")

(defn new-content-id []
  (str (random-uuid)))

(def ^:private non-visual-content-types
  "Content types that render no block in the chat, so they never close an open
   reasoning group."
  #{:progress :usage :metadata})

(defn ^:private send-content-raw! [{:keys [messenger chat-id parent-chat-id]} role content]
  (messenger/chat-content-received
   messenger
   (assoc-some {:chat-id chat-id
                :role role
                :content content}
               :parent-chat-id parent-chat-id)))

(defn ^:private swap-reason-group!
  "Atomically applies `f` to the chat's open reasoning group (nil when there is
   none; returning nil closes it). Returns [old-group new-group]."
  [db* chat-id f]
  (let [path [:chats chat-id :reason-group]
        [old-db new-db] (swap-vals! db* (fn [db]
                                          (if (get-in db [:chats chat-id])
                                            (if-let [group (f (get-in db path))]
                                              (assoc-in db path group)
                                              (update-in db [:chats chat-id] dissoc :reason-group))
                                            db)))]
    [(get-in old-db path) (get-in new-db path)]))

(defn flush-reason-group!
  "Closes the chat's open reasoning group, if any, sending its single
   reasonFinished with the total time of the merged blocks, counting blocks
   still in flight (e.g. stopped mid-thinking) up to now."
  [{:keys [db* chat-id] :as chat-ctx}]
  (when (and db* (get-in @db* [:chats chat-id :reason-group]))
    (when-let [{:keys [id total-time-ms open]} (first (swap-reason-group! db* chat-id (constantly nil)))]
      (let [now (System/currentTimeMillis)]
        (send-content-raw! chat-ctx :assistant
                           {:type :reasonFinished
                            :id id
                            :total-time-ms (reduce + total-time-ms (map #(- now %) (vals open)))})))))

(defn ^:private send-reason-content!
  [{:keys [db* chat-id] :as chat-ctx} role {:keys [type id text total-time-ms] :as content}]
  (let [now (System/currentTimeMillis)
        new-group {:id id :total-time-ms 0 :open {id now}}]
    (case type
      :reasonStarted
      (let [[old] (swap-reason-group! db* chat-id #(if % (assoc-in % [:open id] now) new-group))]
        (when-not old
          (send-content-raw! chat-ctx role content)))

      :reasonText
      (let [text? (not (string/blank? text))
            [old group] (swap-reason-group! db* chat-id #(cond-> (or % new-group)
                                                           text? (assoc :text? true :last-text-block-id id)))
            display-id (or (:id group) id)]
        (when-not old
          (send-content-raw! chat-ctx role {:type :reasonStarted :id display-id}))
        (send-content-raw! chat-ctx role
                           {:type :reasonText
                            :id display-id
                            :text (if (and text? (:text? old) (not= id (:last-text-block-id old)))
                                    (str "\n\n" text)
                                    text)}))

      :reasonFinished
      (swap-reason-group! db* chat-id #(cond-> %
                                         (get-in % [:open id])
                                         (-> (update :total-time-ms + (or total-time-ms 0))
                                             (update :open dissoc id)))))))

(defn send-content!
  "Sends `content` to the client as chat content of `role`.

   Reasoning is grouped for display only: consecutive reasoning blocks with no
   visible content between them render as a single block that keeps the first
   block id, joins texts with a blank line and gets one reasonFinished with the
   summed time, sent right before the next visible content or when the prompt
   finishes. Chat history still keeps every provider block. #643"
  [{:keys [db* chat-id] :as chat-ctx} role {:keys [type] :as content}]
  (cond
    (not (and db* (get-in @db* [:chats chat-id])))
    (send-content-raw! chat-ctx role content)

    (#{:reasonStarted :reasonText :reasonFinished} type)
    (send-reason-content! chat-ctx role content)

    :else
    (do (when-not (contains? non-visual-content-types type)
          (flush-reason-group! chat-ctx))
        (send-content-raw! chat-ctx role content))))

(defn chat-event
  "A core chat event: `m` with the chat's `:chat-id`, `:agent`, `:model`,
   `:variant` and, for a subagent, `:parent-chat-id`."
  [{:keys [chat-id agent full-model variant parent-chat-id db*]} m]
  (let [chat (some-> db* deref (get-in [:chats chat-id]))]
    (assoc-some m
                :chat-id chat-id
                :agent (or agent (:agent chat))
                :model (or full-model (:model chat))
                :variant (or variant (:variant chat))
                :parent-chat-id (or parent-chat-id (:parent-chat-id chat)))))

(defn emit-messages-added!
  "Tells modules about `messages` added to the chat's history
   (`:chat/message-added`)."
  [chat-ctx messages]
  (doseq [message messages]
    (events/emit! chat-ctx :chat/message-added (chat-event chat-ctx {:message message}))))

(def ^:private request-changes
  "The keys of a request `:llm/before-request` handlers may change, and what
   their values must be."
  [[:past-messages sequential?]
   [:user-messages sequential?]
   [:instructions #(or (string? %) (map? %))]
   [:tools sequential?]])

(defn before-llm-request
  "Runs the `:llm/before-request` event for the chat's next LLM request and
   returns `request` with the modules' changes. `request` holds what the
   model will see: `:past-messages`, `:user-messages` (new user messages,
   not in the history yet), `:instructions` and `:tools`. The history
   doesn't change. Without handlers, `request` is returned as is."
  [{:keys [db* chat-id full-model config] :as chat-ctx} {:keys [user-messages] :as request}]
  (if-not (seq (events/handlers config :llm/before-request))
    request
    (logger/with-chat-context chat-id (db/parent-chat-id @db* chat-id)
      (let [db @db*
            history (get-in db [:chats chat-id :messages] [])
            since-checkpoint (into (shared/messages-after-last-compact-marker history) user-messages)
            {:keys [session-tokens limit]} (shared/usage-sumary chat-id full-model db)
            context-limit (:context limit)
            sent (assoc request :model full-model)
            event (events/emit! chat-ctx :llm/before-request
                                (chat-event chat-ctx
                                            (assoc-some {:request sent
                                                         :history history
                                                         :since-checkpoint since-checkpoint
                                                         :turn (subvec since-checkpoint
                                                                       (or (llm-util/find-last-user-msg-idx since-checkpoint) 0))}
                                                        :budget (when (and (number? session-tokens) (number? context-limit))
                                                                  (max 0 (- context-limit session-tokens))))))
            changed (:request event)]
        (cond
          (identical? sent changed)
          request

          (not (map? changed))
          (do (logger/warn logger-tag "Ignoring the :request a :llm/before-request handler set" {:value changed})
              request)

          :else
          (do
            (when (not= full-model (:model changed))
              (logger/warn logger-tag "Ignoring the :model a :llm/before-request handler set: not supported yet"
                           {:model (:model changed)}))
            (reduce (fn [request [k valid?]]
                      (let [v (get changed k)]
                        (cond
                          (identical? v (get request k)) request
                          (valid? v) (assoc request k v)
                          :else (do (logger/warn logger-tag (format "Ignoring the %s a :llm/before-request handler set" k)
                                                 {:value v})
                                    request))))
                    request
                    request-changes)))))))

(defn request-failed-answer
  "Asks modules what to do about a failed LLM request of the chat
   (`:llm/request-failed`): returns the first answer with `:retry` or
   `:compact`, or nil, when ECA handles the failure as before."
  [{:keys [config] :as chat-ctx} request error]
  (when (seq (events/handlers config :llm/request-failed))
    (let [answer (events/emit! chat-ctx :llm/request-failed (chat-event chat-ctx {:request request :error error}))]
      (when (and (map? answer) (or (:retry answer) (:compact answer)))
        answer))))

(defn usage-updated!
  "Emits `:chat/usage-updated` for `usage`, the chat's usage as clients get
   it, in a context of `context-limit` tokens. Returns the auto-compaction
   threshold modules gave, in percent of the context limit, or nil."
  [chat-ctx usage context-limit]
  (let [session-tokens (:session-tokens usage)
        percentage (:auto-compact-percentage
                    (events/emit! chat-ctx :chat/usage-updated
                                  {:chat-id (:chat-id chat-ctx)
                                   :parent-chat-id (:parent-chat-id chat-ctx)
                                   :agent (:agent chat-ctx)
                                   :model (:full-model chat-ctx)
                                   :variant (:variant chat-ctx)
                                   :usage usage
                                   :session-tokens session-tokens
                                   :context-limit context-limit
                                   :context-used (when (and (number? session-tokens)
                                                            (number? context-limit)
                                                            (pos? context-limit))
                                                   (double (/ session-tokens context-limit)))}))]
    (when (number? percentage)
      percentage)))

(defn ended-by
  "Who ended an event's chain of handlers with a `:block` or `:stop`, as
   `ended-message` names them: `{:module id}`, with `:by` when the module
   named who acted for it (e.g. \"hook 'lint'\"); nil when none did."
  [event]
  (when-let [module (::events/ended-by event)]
    (assoc-some {:module module} :by (::events/by event))))

(defn ended-message
  "The user-facing message when `ended-by` ended something, `what`, e.g.
   \"Turn stopped by hook 'lint': too risky\" or \"Request blocked by module
   'acme/x'.\". A stop's reason is never sent to the model."
  [what {:keys [module by]} reason]
  (str what " by " (or by (str "module '" (if module (str (symbol module)) "?") "'"))
       (if-let [reason (shared/not-blank reason)]
         (str ": " reason)
         ".")))

(defn send-ended!
  "Shows the user `ended-message` as a system text."
  [chat-ctx what ended-by reason]
  (send-content! chat-ctx :system {:type :text :text (ended-message what ended-by reason)}))

(defn strip-hook-callbacks
  "Remove finish side-effect callbacks so abort/stop paths do not resume
   special flows. Intentionally preserves :on-follow-up; whether postRequest
   followUp can fire is controlled by :skip-post-request-hooks?."
  [chat-ctx]
  (dissoc chat-ctx :on-finished-side-effect :on-after-finish!))

(defn wrap-additional-context
  "Return XML-wrapped additional context."
  [content]
  (format "<additionalContext>\n%s\n</additionalContext>"
          content))

(defn context-block
  "The content block of a context for the model, `{:type :text :text ..}`
   wrapped in `<additionalContext>`. A module's context, `{:module ..}`,
   keeps the module's id and its `:data`, which no provider gets (see
   `eca.message-sanitize`); a hook's has neither."
  [{:keys [content module data]}]
  (cond-> {:type :text :text (wrap-additional-context content)}
    module (assoc :module module)
    (some? data) (assoc :data data)))

(defn ^:private run-after-request!
  "Runs the `:chat/after-request` event, the postRequest shell hooks last
   (4.17). Returns `{:follow-up-text}`, what the handlers want sent next, or
   `{:stop-turn? true :stop-reason .. :ended-by ..}`."
  [{:keys [response] :as chat-ctx}]
  (let [event (events/emit! chat-ctx :chat/after-request (chat-event chat-ctx {:response response}))]
    (if (contains? event :stop)
      {:stop-turn? true
       :stop-reason (:stop event)
       :ended-by (ended-by event)}
      {:follow-up-text (shared/not-blank (:follow-up event))})))

(defn ^:private chat-status-payload
  "Build the aggregate chatStatusChanged hook payload from a db snapshot."
  [db chat-id]
  (let [chat (get-in db [:chats chat-id])
        tool-calls (:tool-calls chat)
        ask-user? (fn [tc]
                    (and (= "ask_user" (:name tc))
                         (= "eca" (:server tc))))
        ids-by (fn [pred]
                 (->> tool-calls
                      (filter (fn [[_ tc]] (pred tc)))
                      (map key)
                      sort
                      vec))
        pending-approval (ids-by #(= :waiting-approval (:status %)))
        pending-question (ids-by #(and (= :executing (:status %)) (ask-user? %)))
        running (ids-by #(and (= :executing (:status %)) (not (ask-user? %))))
        awaiting? (boolean (or (seq pending-approval) (seq pending-question)))]
    (cond-> {:chat-id chat-id
             :status (or (:status chat) :idle)
             :awaiting-user-input awaiting?
             :pending-approval-tool-call-ids pending-approval
             :pending-question-tool-call-ids pending-question
             :running-tool-call-ids running}
      awaiting? (assoc :waiting-reason (if (seq pending-approval)
                                         "toolApproval"
                                         "userQuestion")))))

(defn trigger-chat-status-hook!
  "Emits `:chat/status-changed` (the chatStatusChanged hooks are its last
   handler) when the chat's aggregate status changed since the last time.

   The protocol `chat/statusChanged` notification is emitted separately and
   carries only the chat id and status; the event has the aggregate status,
   including the tool-call ids waiting or running.

   Dedup state is stored at [:chats chat-id :last-status-payload]. Missing chats
   are ignored."
  [{:keys [db* chat-id config] :as chat-ctx}]
  (when (seq (events/handlers config :chat/status-changed))
    (let [path [:chats chat-id :last-status-payload]
          ;; Compute inside the swap so the stored payload matches the db snapshot.
          [old-db new-db] (swap-vals! db* (fn [db]
                                            (if (get-in db [:chats chat-id])
                                              (assoc-in db path (chat-status-payload db chat-id))
                                              db)))
          payload (get-in new-db path)]
      (when (and (get-in new-db [:chats chat-id])
                 (not= (get-in old-db path) payload))
        (events/emit! chat-ctx :chat/status-changed
                      (assoc-some {:chat-id chat-id
                                   :status (:status payload)
                                   :pending-approval-ids (:pending-approval-tool-call-ids payload)
                                   :pending-question-ids (:pending-question-tool-call-ids payload)
                                   :running-tool-ids (:running-tool-call-ids payload)}
                                  :waiting-reason (:waiting-reason payload)
                                  :parent-chat-id (when (get-in new-db [:chats chat-id :subagent])
                                                    (db/parent-chat-id new-db chat-id))))))))

(defn ^:private finished-error
  "The error that ended the turn (a provider or network error, a context
  overflow…), for the `finished` progress, or nil when the turn succeeded."
  [db chat-id]
  (when-let [{:keys [message error-type]} (get-in db [:chats chat-id :prompt-error])]
    (assoc-some {:message (or message "Unknown error")}
                :type (some-> error-type name))))

(defn ^:private apply-status-transition!
  "Update chat status, send status/progress events, set created-at if needed."
  [{:keys [chat-id db* messenger] :as chat-ctx} status]
  (swap! db* assoc-in [:chats chat-id :status] status)
  (messenger/chat-status-changed messenger {:chat-id chat-id :status status})
  (trigger-chat-status-hook! chat-ctx)
  (send-content! chat-ctx :system
                 (assoc-some {:type :progress
                              :state :finished}
                             :error (finished-error @db* chat-id)))
  (when-not (get-in @db* [:chats chat-id :created-at])
    (swap! db* assoc-in [:chats chat-id :created-at] (System/currentTimeMillis))))

(defn ^:private record-unsent-user-messages*
  "Moves the turn's unsent user messages to the history; returns the db
   before and after."
  [db* chat-id]
  (when (get-in @db* [:chats chat-id :unsent-user-messages])
    (swap-vals! db* update-in [:chats chat-id]
           (fn [{:keys [unsent-user-messages messages] :as chat}]
             (let [{:keys [content-id sent-attached-contexts] user-messages :messages} unsent-user-messages
                   record? (and (seq user-messages)
                                (not-any? #(= content-id (:content-id %)) messages))]
               (cond-> (dissoc chat :unsent-user-messages)
                 record?
                 (update :messages (fnil into [])
                         (map #(assoc % :content-id content-id :created-at (System/currentTimeMillis)))
                         user-messages)

                 ;; The attached contexts in these messages reach the history too. #683
                 (and record? sent-attached-contexts)
                 (assoc :sent-attached-contexts sent-attached-contexts)))))))

(defn record-unsent-user-messages!
  "User messages only reach the chat history once the LLM starts responding.
   Adds the ones of a turn that ended (stopped, failed or superseded) before
   that, so the next prompt and a resumed chat still have them. With
   `chat-ctx`, modules hear of them (`:chat/message-added`)."
  ([db* chat-id] (record-unsent-user-messages! db* chat-id nil))
  ([db* chat-id chat-ctx]
   (let [[old-db new-db] (record-unsent-user-messages* db* chat-id)
         added (when new-db
                 (drop (count (get-in old-db [:chats chat-id :messages]))
                       (get-in new-db [:chats chat-id :messages])))]
     (when (and chat-ctx (seq added))
       (emit-messages-added! chat-ctx added))
     nil)))

(defn ^:private dispatch-finish-callbacks!
  "Dispatch finish-flow callbacks. `on-finished-side-effect` always runs first
   (and may itself request `stop-after-finish?`). After that, exactly one
   continuation may fire, in priority order:

   - nothing, when the turn is stopping or a hook returned continue:false;
   - `on-follow-up` with the followUp text, when present;
   - `on-after-finish!`, unless a side-effect/caller requested stop-after-finish?."
  [{:keys [db* chat-id] :as chat-ctx}
   {:keys [follow-up-text stop-turn? stopping? stop-after-finish?]
    :or {stop-turn? false stopping? false stop-after-finish? false}}]
  (let [{:keys [on-follow-up on-finished-side-effect on-after-finish!]} chat-ctx
        side-effect-result (when on-finished-side-effect (on-finished-side-effect))
        stop-after-finish? (or stop-after-finish? (:stop-after-finish? side-effect-result))]
    (cond
      (or stopping? stop-turn?)
      nil

      (and follow-up-text on-follow-up)
      (do
        (swap! db* update-in [:chats chat-id] dissoc :auto-compacting? :continuing? :compacting?)
        (swap! db* assoc-in [:chats chat-id :follow-up-active?] true)
        (on-follow-up follow-up-text chat-ctx))

      (and (not (or follow-up-text stop-after-finish?)) on-after-finish!)
      (on-after-finish!))))

(defn finish-chat-prompt! [status {:keys [chat-id db* metrics prompt-id
                                          skip-post-request-hooks?]
                                   :as chat-ctx}]
  (when-not (get-in @db* [:chats chat-id :prompt-finished?])
    (when-not (and prompt-id (not= prompt-id (get-in @db* [:chats chat-id :prompt-id])))
      (flush-reason-group! chat-ctx)
      (let [;; the request ends, not the turn: a compaction's, or one `continue-turn!` follows
            turn-goes-on? (let [chat (get-in @db* [:chats chat-id])]
                            (or (:auto-compacting? chat) (:continuing? chat)))
            {:keys [follow-up-text stop-turn? stop-reason ended-by]}
            (when (and (not turn-goes-on?) (not skip-post-request-hooks?))
              (run-after-request! chat-ctx))
            stopping? (identical? :stopping (get-in @db* [:chats chat-id :status]))]
        (when-not turn-goes-on?
          (swap! db* assoc-in [:chats chat-id :prompt-finished?] true)
          (swap! db* update-in [:chats chat-id] dissoc :steer-message)
          (record-unsent-user-messages! db* chat-id chat-ctx)
          (apply-status-transition! chat-ctx status))
        ;; A stop from the :chat/after-request handlers (a postRequest hook's
        ;; continue:false) ends the turn and cancels the follow-up; the user
        ;; sees why, as for the other turn stops.
        (when stop-turn?
          (send-ended! chat-ctx "Turn stopped" ended-by stop-reason))
        (dispatch-finish-callbacks! chat-ctx {:follow-up-text follow-up-text
                                              :stop-turn? stop-turn?
                                              :stopping? stopping?})
        (db.chats/save-chat! @db* chat-id metrics)))))

(defn finish-chat-prompt-stopped!
  "Finish a turn that was halted by a hook (continue:false) or otherwise aborted.
   Skips postRequest hooks and strips finish side-effect callbacks so no
   postRequest / followUp / continuation fires — the turn ends cleanly and the
   user can start a new prompt. Use this for every hook turn-stop so they behave
   consistently (preRequest, preToolCall, postToolCall, ...)."
  [status chat-ctx]
  (finish-chat-prompt! status
                       (-> chat-ctx
                           (assoc :skip-post-request-hooks? true)
                           strip-hook-callbacks)))

(defn maybe-renew-auth-token [chat-ctx]
  (f.login/maybe-renew-auth-token!
   {:provider (:provider chat-ctx)
    :on-renewing (fn []
                   (send-content! chat-ctx :system {:type  :progress
                                                    :state :running
                                                    :text  "Renewing auth token"}))
    :on-error (fn [error-msg]
                (send-content! chat-ctx :system {:type :text :text error-msg})
                (finish-chat-prompt! :idle (strip-hook-callbacks chat-ctx))
                (throw (ex-info "Auth token renew failed" {})))}
   chat-ctx))

(defn assert-chat-not-stopped! [{:keys [chat-id db* prompt-id] :as chat-ctx}]
  (let [chat (get-in @db* [:chats chat-id])
        superseded? (and prompt-id (not= prompt-id (:prompt-id chat)))
        stopped? (or (identical? :stopping (:status chat))
                     (:prompt-finished? chat)
                     superseded?)]
    (when stopped?
      (finish-chat-prompt! :idle (strip-hook-callbacks chat-ctx))
      (logger/info logger-tag "Chat prompt stopped:" chat-id (when superseded? "(superseded)"))
      (throw (ex-info "Chat prompt stopped" {:silent? true
                                             :chat-id chat-id})))))
