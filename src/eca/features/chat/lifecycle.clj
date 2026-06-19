(ns eca.features.chat.lifecycle
  (:require
   [clojure.string :as string]
   [eca.db :as db]
   [eca.features.hooks :as f.hooks]
   [eca.features.login :as f.login]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.shared :as shared :refer [assoc-some]]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CHAT]")

(defn new-content-id []
  (str (random-uuid)))

(defn auto-compact? [chat-id agent-name full-model config db]
  (when (and (not (get-in db [:chats chat-id :compacting?]))
             (not (get-in db [:chats chat-id :auto-compacting?])))
    (let [compact-threshold (or (get-in config [:agent agent-name :autoCompactPercentage])
                                (get-in config [:autoCompactPercentage]))
          {:keys [session-tokens limit]} (shared/usage-sumary chat-id full-model db)
          context-limit (:context limit)]
      (when (and compact-threshold
                 session-tokens
                 (number? context-limit)
                 (pos? context-limit))
        (let [current-percentage (* (/ session-tokens context-limit) 100)]
          (>= current-percentage compact-threshold))))))

(defn send-content! [{:keys [messenger chat-id parent-chat-id]} role content]
  (messenger/chat-content-received
   messenger
   (assoc-some {:chat-id chat-id
                :role role
                :content content}
               :parent-chat-id parent-chat-id)))

(defn hook-system-text
  "User-facing standalone message attributed to a hook by name.
   Ends with a blank line so the message renders as its own paragraph and never
   visually glues to a following assistant stream (e.g. a preRequest systemMessage
   emitted right before the model's reply)."
  [hook-name text]
  (str "Hook '" hook-name "': " text "\n\n"))

(defn turn-stopped-by-hook-message
  "Canonical user-facing message when a hook stops the current turn.
   stopReason is never sent to the LLM; this is the user-only explanation."
  [hook-name stop-reason]
  (if (shared/not-blank stop-reason)
    (str "Turn stopped by hook '" hook-name "': " stop-reason)
    (str "Turn stopped by hook '" hook-name "'.")))

(defn send-turn-stopped-by-hook!
  "Surface the canonical turn-stopped message to the user as a system text."
  [chat-ctx hook-name reason]
  (send-content! chat-ctx :system
                 {:type :text
                  :text (turn-stopped-by-hook-message hook-name reason)}))

(defn request-blocked-by-hook-message
  "Canonical user-facing message when a preRequest hook blocks the prompt."
  [hook-name reason]
  (if (shared/not-blank reason)
    (str "Request blocked by hook '" hook-name "': " reason)
    (str "Request blocked by hook '" hook-name "'.")))

(defn compaction-blocked-by-hook-message
  "Canonical user-facing message when a preCompact hook (exit 2) blocks
   compaction without stopping the turn. Names the blocking hook when known."
  [hook-name]
  (if (shared/not-blank hook-name)
    (str "Compaction blocked by hook '" hook-name "'.")
    "Compaction blocked by hook."))

(defn ^:private format-hook-output
  "Format the hook execution block's text: 'Hook executed' (or raw plain-text
   stdout when the hook emitted no JSON) plus structural effect lines.
   systemMessage is intentionally NOT included here - it is surfaced as a
   standalone user-facing message by notify-after-hook-action!, never as the
   block's text. Tool hooks correlate with their tool widget via the structured
   :tool-call-id sent on hookActionFinished, not via the block text."
  [hook-type {:strs [replacedPrompt additionalContext followUp replacedOutput updatedInput] :as parsed} raw-output]
  (let [replaced-prompt (shared/not-blank replacedPrompt)
        additional-context (shared/not-blank additionalContext)
        follow-up (shared/not-blank followUp)]
    (if parsed
      (cond-> "Hook executed"
        (and (= :preRequest hook-type) replaced-prompt) (str "\nReplacedPrompt: " (pr-str replaced-prompt))
        (and (= :preToolCall hook-type) (some? updatedInput)) (str "\nUpdatedInput: " (pr-str updatedInput))
        (and (#{:postRequest :subagentPostRequest} hook-type) follow-up) (str "\nFollowUp: " follow-up)
        (and (= :postToolCall hook-type) (string? replacedOutput)) (str "\nReplacedOutput: " (pr-str replacedOutput))
        additional-context (str "\nAdditionalContext: " additional-context))
      (not-empty raw-output))))

(defn notify-before-hook-action! [chat-ctx {:keys [id name type visible?]}]
  (when visible?
    (send-content! chat-ctx :system
                   {:type :hookActionStarted
                    :action-type type
                    :name name
                    :id id})))

(defn notify-after-hook-action! [chat-ctx {:keys [id name parsed raw-output raw-error exit type hook-type visible? tool-call-id]}]
  (let [advisory-chat-hook? (contains? #{:chatEnd :chatStatusChanged} hook-type)
        ;; JSON output fields are only honored on exit 0 (and never for advisory chat hooks).
        parsed-effects (when (and (not advisory-chat-hook?) (zero? exit)) parsed)
        suppress? (boolean (get parsed-effects "suppressOutput"))]
    ;; Two independent channels:
    ;; 1. Execution block, gated by `visible?`. If a started event was sent we must
    ;;    send a matching finished (same id) so the client closes the spinner.
    ;;    `suppressOutput` drops only the body, not the block. Never carries systemMessage.
    (when visible?
      (send-content! chat-ctx :system
                     (cond-> (assoc-some {:type :hookActionFinished
                                          :action-type type
                                          :id id
                                          :name name
                                          :status exit}
                                         :tool-call-id tool-call-id)
                       (not suppress?) (assoc :output (format-hook-output hook-type parsed-effects raw-output)
                                              :error raw-error))))

    ;; 2. systemMessage: standalone message, independent of visible/suppressOutput.
    (when-let [system-message (shared/not-blank (get parsed-effects "systemMessage"))]
      (send-content! chat-ctx :system {:type :text :text (hook-system-text name system-message)}))))

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

(defn ^:private compact-hook-data
  [db chat-ctx trigger extra]
  (merge (f.hooks/chat-hook-data db chat-ctx)
         {:triggered trigger}
         extra))

(defn ^:private compact-block-reason
  "Extract the user-facing stopReason from a continue:false hook result, or nil.
   Exit 2 never produces a user-facing reason here."
  [{:keys [parsed exit]}]
  (when (zero? exit)
    (shared/not-blank (get parsed "stopReason"))))

(defn ^:private compact-hook-options
  [chat-ctx after-fn]
  {:on-before-action (partial notify-before-hook-action! chat-ctx)
   :on-after-action (fn [result]
                      (notify-after-hook-action! chat-ctx result)
                      (after-fn result))})

(defn ^:private process-pre-compact-hook-result
  "Pure function: fold a single hook result into accumulated state.

   acc is {:blocked? false, :reason nil, :hook-name nil, :stop-turn? false}"
  [acc result]
  (let [parsed (:parsed result)
        success? (zero? (:exit result))
        exit-code-2? (= f.hooks/hook-rejection-exit-code (:exit result))
        stop-turn-result? (and success? (false? (get parsed "continue" true)))]
    (cond
      ;; continue:false blocks compaction and stops the turn (with optional stopReason).
      stop-turn-result?
      (merge acc {:blocked? true
                  :reason (compact-block-reason result)
                  :hook-name (:name result)
                  :stop-turn? true})

      ;; Exit 2 blocks only compaction (no user-facing reason from stderr), but we
      ;; still remember the first blocking hook's name for provenance in the
      ;; user-facing "Compaction blocked by hook '<name>'." message.
      exit-code-2?
      (cond-> (assoc acc :blocked? true)
        (nil? (:hook-name acc)) (assoc :hook-name (:name result)))

      :else acc)))

(defn run-pre-compact-hooks!
  "Run preCompact hooks. Returns {:blocked? boolean :reason string-or-nil :stop-turn? boolean}.
   Hooks match on triggered: \"manual\" or \"auto\". Compaction is blocked when
   a hook returns {\"continue\":false} or exits with code 2; only continue:false
   also requests stopping the current turn. :reason is only set for continue:false
   (from stopReason); exit 2 does not produce a user-facing reason."
  [{:keys [db* config] :as chat-ctx} trigger custom-instructions]
  (let [hook-state* (atom {:blocked? false
                           :reason nil
                           :hook-name nil
                           :stop-turn? false})
        db @db*]
    (f.hooks/trigger-if-matches!
     :preCompact
     (compact-hook-data db chat-ctx trigger
                        {:custom-instructions (or custom-instructions "")})
     (compact-hook-options
      chat-ctx
      (fn [result]
        (swap! hook-state* process-pre-compact-hook-result result)))
     db
     config)
    (let [{:keys [blocked? reason hook-name stop-turn?]} @hook-state*]
      {:blocked? blocked?
       :reason reason
       :hook-name hook-name
       :stop-turn? stop-turn?})))

(defn ^:private post-compact-summary-index
  "Find the compact summary message inserted by compact-side-effect!.
   The current history layout is compact_marker followed by a user summary;
   return nil if that structure is not present so callers can safely fall back."
  [messages]
  (let [msgs (vec messages)
        n (count msgs)
        marker-idx (loop [i (dec n)]
                     (cond
                       (neg? i) nil
                       (= "compact_marker" (:role (msgs i))) i
                       :else (recur (dec i))))]
    (when marker-idx
      (some (fn [i]
              (when (= "user" (:role (msgs i))) i))
            (range (inc marker-idx) n)))))

(defn ^:private append-post-compact-contexts!
  "Append additionalContext entries to the compact summary message that follows
   the latest compact_marker. If no marker/summary is present, emit a warning
   and leave history untouched."
  [db* chat-id additional-contexts]
  (when (seq additional-contexts)
    (let [summary-idx (post-compact-summary-index (get-in @db* [:chats chat-id :messages]))]
      (if summary-idx
        (let [entries (mapv (fn [{:keys [content]}]
                              {:type :text :text (wrap-additional-context content)})
                            additional-contexts)]
          (swap! db* update-in [:chats chat-id :messages]
                 (fn [messages]
                   (let [messages (vec messages)]
                     (if (and (< summary-idx (count messages))
                              (= "user" (:role (messages summary-idx))))
                       (update-in messages [summary-idx :content]
                                  #(into (if (string? %)
                                           [{:type :text :text %}]
                                           (vec %))
                                         entries))
                       messages)))))
        (let [db @db*]
          (logger/with-chat-context chat-id (db/parent-chat-id db chat-id)
            (logger/warn logger-tag "Skipping postCompact additionalContext: compact summary message not found"
                         {:chat-id chat-id})))))))

(defn run-post-compact-hooks!
  "Run postCompact hooks after ECA has added the compact marker and compact
   summary message. Collects additionalContext entries from successful hooks
   and applies them to the compact summary message in history.

   Returns {:stop-turn? boolean} when no additionalContext was produced; when
   one or more entries were produced, also returns :additional-contexts so
   callers/tests can inspect what was appended."
  [{:keys [db* config chat-id] :as chat-ctx} trigger compact-summary]
  (let [db @db*
        hook-state* (atom {:stop-turn? false
                           :stop-reason nil
                           :stop-hook-name nil
                           :additional-contexts []})]
    (f.hooks/trigger-if-matches!
     :postCompact
     (compact-hook-data db chat-ctx trigger
                        {:compact-summary (or compact-summary "")})
     (compact-hook-options
      chat-ctx
      (fn [{:keys [parsed exit name] :as _result}]
        (when (zero? exit)
          (when (false? (get parsed "continue" true))
            (swap! hook-state* assoc
                   :stop-turn? true
                   :stop-reason (shared/not-blank (get parsed "stopReason"))
                   :stop-hook-name name))
          (when-let [additional-context (shared/not-blank (get parsed "additionalContext"))]
            (swap! hook-state* update :additional-contexts conj
                   {:hook-name name :content additional-context})))))
     db
     config)
    (let [{:keys [stop-turn? stop-reason stop-hook-name additional-contexts]} @hook-state*]
      (append-post-compact-contexts! db* chat-id additional-contexts)
      (cond-> {:stop-turn? stop-turn?
               :stop-reason stop-reason
               :stop-hook-name stop-hook-name}
        (seq additional-contexts) (assoc :additional-contexts additional-contexts)))))

(defn complete-compact!
  "Apply ECA's compact side effects, then run postCompact hooks (which
   internally append any returned additionalContext to the compact summary)."
  [{:keys [db* chat-id] :as chat-ctx} trigger]
  (let [summary (get-in @db* [:chats chat-id :last-summary])]
    (shared/compact-side-effect! chat-ctx (= "auto" trigger))
    (run-post-compact-hooks! chat-ctx trigger summary)))

(defn ^:private run-post-request-hooks!
  "Run postRequest (for primary chats) and subagentPostRequest (for subagents) hooks.
   Returns {:follow-up-text string-or-nil :stop-turn? boolean
            :stop-reason string-or-nil :stop-hook-name string-or-nil}.

   postRequest/subagentPostRequest exit 2 with stderr is treated as followUp:
   stderr becomes the followUp text, analogous to how preToolCall/postToolCall
   exit 2 makes stderr LLM-visible payload. This is because these hooks run
   after the prompt finished, so exit 2 cannot 'block' the request; instead it
   contributes a continuation instruction."
  [{:keys [db* config chat-id response] :as chat-ctx}]
  (let [db @db*
        results* (atom [])
        subagent? (some? (get-in db [:chats chat-id :subagent]))
        follow-up-active? (get-in db [:chats chat-id :follow-up-active?])
        ;; follow-up-active is always sent as a boolean (documented contract),
        ;; so hook authors can rely on the key being present as true or false.
        base-hook-data (assoc-some (f.hooks/chat-hook-data db chat-ctx)
                                   :response response
                                   :follow-up-active (boolean follow-up-active?))
        cb {:on-before-action (partial notify-before-hook-action! chat-ctx)
            :on-after-action (fn [result]
                               (notify-after-hook-action! chat-ctx result)
                               (swap! results* conj result))}
        ;; postRequest is primary-only. Subagent chats use subagentPostRequest.
        _ (if subagent?
            (f.hooks/trigger-if-matches! :subagentPostRequest
                                         (assoc base-hook-data :parent-chat-id (db/parent-chat-id db chat-id))
                                         cb
                                         db
                                         config)
            (f.hooks/trigger-if-matches! :postRequest base-hook-data cb db config))
        hook-results @results*
        follow-ups (->> hook-results
                        (keep (fn [{:keys [parsed exit raw-error]}]
                                (cond
                                  (zero? exit)
                                  (shared/not-blank (get parsed "followUp"))

                                  (= f.hooks/hook-rejection-exit-code exit)
                                  (shared/not-blank raw-error))))
                        seq)
        follow-up-text (some->> follow-ups (string/join "\n\n"))
        stop-result (some (fn [{:keys [parsed name] :as result}]
                            (when (f.hooks/successful-continue-false? result)
                              {:stop-reason (shared/not-blank (get parsed "stopReason"))
                               :stop-hook-name name}))
                          hook-results)]
    {:follow-up-text follow-up-text
     :stop-turn? (boolean stop-result)
     :stop-reason (:stop-reason stop-result)
     :stop-hook-name (:stop-hook-name stop-result)}))

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
  "Trigger the advisory chatStatusChanged hook when the aggregate chat status
   payload changed since the last trigger.

   The protocol `chat/statusChanged` notification is emitted separately and
   carries only the chat id and status. This hook receives the aggregate status,
   including awaiting-user-input and tool-call ids.

   Dedup state is stored at [:chats chat-id :last-status-payload]. Missing chats
   are ignored."
  [{:keys [db* chat-id config] :as chat-ctx}]
  (when (some #(= :chatStatusChanged (keyword (:type %))) (vals (:hooks config)))
    (let [path [:chats chat-id :last-status-payload]
          ;; Compute inside the swap so the stored payload matches the db snapshot.
          [old-db new-db] (swap-vals! db* (fn [db]
                                            (if (get-in db [:chats chat-id])
                                              (assoc-in db path (chat-status-payload db chat-id))
                                              db)))
          payload (get-in new-db path)]
      (when (and (get-in new-db [:chats chat-id])
                 (not= (get-in old-db path) payload))
        (f.hooks/trigger-if-matches!
         :chatStatusChanged
         (merge (f.hooks/chat-hook-data new-db chat-ctx)
                (when (get-in new-db [:chats chat-id :subagent])
                  {:parent-chat-id (db/parent-chat-id new-db chat-id)})
                (dissoc payload :chat-id))
         ;; No action callbacks: advisory hooks do not render in the chat UI.
         {}
         new-db
         config)))))

(defn ^:private apply-status-transition!
  "Update chat status, send status/progress events, set created-at if needed."
  [{:keys [chat-id db* messenger] :as chat-ctx} status]
  (swap! db* assoc-in [:chats chat-id :status] status)
  (messenger/chat-status-changed messenger {:chat-id chat-id :status status})
  (trigger-chat-status-hook! chat-ctx)
  (send-content! chat-ctx :system
                 {:type :progress
                  :state :finished})
  (when-not (get-in @db* [:chats chat-id :created-at])
    (swap! db* assoc-in [:chats chat-id :created-at] (System/currentTimeMillis))))

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
        (swap! db* update-in [:chats chat-id] dissoc :auto-compacting? :compacting?)
        (swap! db* assoc-in [:chats chat-id :follow-up-active?] true)
        (on-follow-up follow-up-text chat-ctx))

      (and (not (or follow-up-text stop-after-finish?)) on-after-finish!)
      (on-after-finish!))))

(defn finish-chat-prompt! [status {:keys [chat-id db* metrics prompt-id
                                          skip-post-request-hooks?]
                                   :as chat-ctx}]
  (when-not (get-in @db* [:chats chat-id :prompt-finished?])
    (when-not (and prompt-id (not= prompt-id (get-in @db* [:chats chat-id :prompt-id])))
      (let [auto-compacting? (get-in @db* [:chats chat-id :auto-compacting?])
            {:keys [follow-up-text stop-turn? stop-reason stop-hook-name]}
            (when (and (not auto-compacting?) (not skip-post-request-hooks?))
              (run-post-request-hooks! chat-ctx))
            stopping? (identical? :stopping (get-in @db* [:chats chat-id :status]))]
        (when-not auto-compacting?
          (swap! db* assoc-in [:chats chat-id :prompt-finished?] true)
          (swap! db* update-in [:chats chat-id] dissoc :steer-message)
          (apply-status-transition! chat-ctx status))
        ;; A postRequest hook that returned continue:false stops the turn and
        ;; cancels followUp; surface the reason to the user (prefixed with the
        ;; hook name), consistent with the other turn-stopping hooks.
        (when stop-turn?
          (send-turn-stopped-by-hook! chat-ctx stop-hook-name stop-reason))
        (dispatch-finish-callbacks! chat-ctx {:follow-up-text follow-up-text
                                              :stop-turn? stop-turn?
                                              :stopping? stopping?})
        (db/update-workspaces-cache! @db* metrics)))))

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
