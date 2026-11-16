(ns eca.features.chat.turn
  "A turn's requests to the LLM, from the user's prompt until the chat is
   idle again: each `prompt-messages!` is one, with its callbacks
   (`f.chat.callbacks`) and its recovery (`f.chat.recovery`).

   Besides what `eca.features.chat/prompt` puts in chat-ctx (`:db*`,
   `:config`, `:chat-id`, `:agent`, `:message`, `:user-content-id`,
   `:instructions`, `:full-model`...), it carries the turn's flow:
   - `:prompt-id`, `:subscription?`, `:continue-turn-fn`: the request's, set here;
   - `:auto-continue-count`: recoveries in a row (`f.chat.recovery`);
   - `:module-recovery-attempted?`, `:image-retried?`, `:error-recovery-attempted?`:
     recoveries tried once per prompt;
   - `:auto-compactions`, `:auto-compacted?` (a compaction was blocked: no more in
     the turn), `:post-compact-guard*`, `:compact-trigger`: `f.chat.compaction`'s;
   - `:compaction?`: a `/compact` prompt, not a user request;
   - `:on-finished-side-effect`, `:on-after-finish!`: what finishing the request
     runs (compaction, commands, `continue-turn!`); `:skip-post-request-hooks?`."
  (:require
   [clojure.string :as string]
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.chat.callbacks :as f.chat.callbacks]
   [eca.features.chat.compaction :as f.chat.compaction]
   [eca.features.chat.lifecycle :as f.chat.lifecycle]
   [eca.features.chat.ops :as f.chat.ops]
   [eca.features.chat.recovery :as f.chat.recovery]
   [eca.features.chat.system-prompt :as f.chat.system-prompt]
   [eca.features.prompt :as f.prompt]
   [eca.features.quota :as f.quota]
   [eca.features.tools :as f.tools]
   [eca.llm-api :as llm-api]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.message-sanitize :as message-sanitize]
   [eca.messenger :as messenger]
   [eca.modules.events :as events]
   [eca.shared :as shared :refer [assoc-some future*]]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CHAT]")

(defn ^:private finish-blocked-or-stopped-pre-request!
  "Ends the turn of a request the `:chat/before-request` handlers blocked or
   stopped, telling the user who did and why."
  [chat-ctx {:keys [blocked? block-reason stop-turn? stop-reason ended-by]}]
  (when (or stop-turn? blocked?)
    (if blocked?
      (f.chat.lifecycle/send-ended! chat-ctx "Request blocked" ended-by block-reason)
      (f.chat.lifecycle/send-ended! chat-ctx "Turn stopped" ended-by stop-reason))
    (f.chat.lifecycle/finish-chat-prompt-stopped! :idle chat-ctx)))

(defn ^:private run-pre-request!
  "Runs the `:chat/before-request` event, the preRequest shell hooks last
   (4.17): handlers may rewrite the prompt (when `modify-allowed?`), add
   context, or block or stop the request. Returns `{:final-prompt
   :additional-contexts}`, the contexts as `{:module :content :data}` in the
   handlers' order (a hook's has no `:module`), or `:blocked?` with the
   `:block-reason`, or `:stop-turn?` with the `:stop-reason`, and `:ended-by`.
   A compaction's prompt (`/compact`, `:compaction?` in `chat-ctx`) isn't a
   user request: only the handlers asking for it get it, the shell hooks."
  [chat-ctx original-text modify-allowed?]
  (let [event (events/emit! chat-ctx :chat/before-request
                            (f.chat.lifecycle/chat-event chat-ctx (cond-> {:prompt original-text}
                                                                    (:compaction? chat-ctx) (assoc :compaction? true))))]
    (cond
      (:block event)
      {:blocked? true :block-reason (:block event) :ended-by (f.chat.lifecycle/ended-by event)}

      (contains? event :stop)
      {:stop-turn? true :stop-reason (:stop event) :ended-by (f.chat.lifecycle/ended-by event)}

      :else
      {:final-prompt (if (and modify-allowed? (string? (:prompt event))) (:prompt event) original-text)
       :additional-contexts (vec (::events/contexts event))})))

(defn ^:private log-api-switch!
  "Log when the user swaps to a model whose api differs from the chat's
   `:last-api`. Cross-api history reconciliation is handled by
   `eca.llm-api/sanitize-past-messages-for-api`, which drops entries whose
   opaque ids the new provider would reject and triggers a chat-visible
   notice via `:on-history-sanitized`, shown only on the first prompt after
   the swap (#660). This used to throw outright (#209)."
  [db chat-id provider model config]
  (let [model-capabilities (get-in db [:models (str provider "/" model)])
        current-api (:api (llm-api/provider->api-handler provider model model-capabilities config))
        last-api (get-in db [:chats chat-id :last-api])]
    (when (and last-api current-api (not= last-api current-api))
      (logger/info logger-tag
                   (format "Model swap detected: last-api=%s current-api=%s"
                           (name last-api) (name current-api))
                   {:chat-id chat-id
                    :last-api last-api
                    :current-api current-api
                    :provider provider
                    :model model}))))

(defn ^:private message-text-content
  "Extract plain text from a chat message's :content, ignoring non-text parts
   and the contexts modules added."
  [{:keys [content]}]
  (let [parts (cond
                (string? content)
                [content]

                (sequential? content)
                (into [] (keep (fn [part]
                                 (when (and (map? part)
                                            (#{:text "text"} (:type part))
                                            (not (message-sanitize/module-context? part)))
                                   (:text part))))
                      content)

                :else
                [])
        joined (string/join "\n" (remove string/blank? parts))]
    (when-not (string/blank? joined)
      joined)))

(defn ^:private conversation->title-transcript
  "Render user/assistant messages as a plain-text transcript for title generation.
   Each line is prefixed by role; per-message text is truncated to avoid blowing up
   the title call for very long chats.

   Flattening the history into a single user message (instead of replaying it as
   role-structured past-messages) prevents the title model from mirroring the
   prior assistant's conversational style (e.g. planning-mode '## Understand'
   headers), which was producing garbage titles on Opus."
  [messages]
  (let [max-chars 2000]
    (->> messages
         (keep (fn [{:keys [role] :as msg}]
                 (when-let [text (message-text-content msg)]
                   (let [truncated (if (> (count text) max-chars)
                                     (str (subs text 0 max-chars) " …")
                                     text)]
                     (str role ": " truncated)))))
         (string/join "\n\n"))))

(defn ^:private without-contents
  "Removes the given content blocks from each message content."
  [messages contents]
  (if-let [contents (some-> contents seq set)]
    (mapv (fn [message]
            (cond-> message
              (sequential? (:content message))
              (update :content #(into [] (remove contents) %))))
          messages)
    messages))

(defn ^:private start-prompt-worker!
  "Runs `thunk` in a prompt worker thread. For a managed chat (see
   `:managed-chats` in the db), counts the worker from before dispatch until its
   whole cleanup has unwound, since the chat may publish idle earlier."
  [{:keys [db* config chat-id]} thunk]
  (if-not (contains? (:managed-chats @db*) chat-id)
    (future* config (thunk))
    (do (swap! db* update-in [:managed-chats chat-id :workers] inc)
        (future* config
          (try (thunk)
               (finally (swap! db* update-in [:managed-chats chat-id :workers] dec)))))))

(defn ^:private take-module-contexts!
  "The contexts modules added for the chat's next request (with
   `eca.api/add-context!`), removed from the chat: `[{:module :content :data}]`."
  [db* chat-id]
  (let [[old-db _] (swap-vals! db* (fn [db]
                                     (cond-> db
                                       (get-in db [:chats chat-id :module-contexts])
                                       (update-in [:chats chat-id] dissoc :module-contexts))))]
    (get-in old-db [:chats chat-id :module-contexts])))

(defn ^:private pre-request-messages
  "Runs the `:chat/before-request` handlers for `user-messages`: returns
   them with the prompt rewritten (a :prompt-message only) and the contexts
   handlers and modules added, or nil when the request was blocked or
   stopped, which ended the turn."
  [{:keys [db* chat-id message] :as chat-ctx} user-messages source-type]
  (let [original-text (or message (-> user-messages first :content first :text))
        modify-allowed? (= source-type :prompt-message)
        {:keys [final-prompt additional-contexts stop-turn? blocked?] :as pre-request-state}
        (run-pre-request! chat-ctx original-text modify-allowed?)]
    (if (or stop-turn? blocked?)
      (do (finish-blocked-or-stopped-pre-request! chat-ctx pre-request-state) nil)
      (let [;; contexts modules added with eca.api/add-context! join the hooks' ones,
            ;; as `{:module :content :data}`; a compaction's prompt leaves them for
            ;; the next user request
            additional-contexts (into (vec additional-contexts)
                                      (when-not (:compaction? chat-ctx)
                                        (take-module-contexts! db* chat-id)))
            last-user-idx (llm-util/find-last-user-msg-idx user-messages)
            ;; preRequest additionalContext should ideally attach to the last user message,
            ;; but some prompt sources may not contain a user role (e.g. prompt templates).
            context-idx   (or last-user-idx
                              (some-> user-messages seq count dec))
            rewritten     (if (and modify-allowed? last-user-idx final-prompt)
                            (assoc-in user-messages [last-user-idx :content 0 :text] final-prompt)
                            user-messages)]
        (cond
          (and (seq additional-contexts) context-idx)
          (reduce (fn [msgs context]
                    (update-in msgs [context-idx :content]
                               #(conj (if (string? %)
                                        [{:type :text :text %}]
                                        (vec %))
                                      (f.chat.lifecycle/context-block context))))
                  rewritten
                  additional-contexts)

          (seq additional-contexts)
          (do (logger/warn logger-tag "Dropping preRequest additionalContext because no message index was found"
                           {:source-type source-type
                            :num-messages (count user-messages)})
              rewritten)

          :else
          rewritten)))))

(defn ^:private start-request!
  "The chat runs a new request, `prompt-id`, with the chat's model, variant
   and agent."
  [{:keys [db* chat-id full-model agent messenger] :as chat-ctx} user-messages run-hooks? prompt-id]
  (when (#{:running :stopping} (get-in @db* [:chats chat-id :status]))
    (logger/info logger-tag "Superseding active prompt" {:chat-id chat-id
                                                         :status (get-in @db* [:chats chat-id :status])}))
  (swap! db* assoc-in [:chats chat-id :status] :running)
  ;; :compact-done? is consumed when its compaction is applied; one left behind by a
  ;; compaction stopped before that would end this turn at its first tool call.
  (swap! db* update-in [:chats chat-id] dissoc :prompt-finished? :prompt-error :compact-done?)
  (when (and run-hooks? (:user-content-id chat-ctx))
    ;; Kept until the LLM responds, so a turn that is stopped, fails or is
    ;; superseded before that doesn't lose what the user typed.
    (f.chat.lifecycle/record-unsent-user-messages! db* chat-id chat-ctx)
    (swap! db* assoc-in [:chats chat-id :unsent-user-messages]
           (assoc-some {:content-id (:user-content-id chat-ctx)
                        :messages user-messages}
                       :sent-attached-contexts (:pending-sent-attached-contexts chat-ctx))))
  (swap! db* assoc-in [:chats chat-id :updated-at] (System/currentTimeMillis))
  (messenger/chat-status-changed messenger {:chat-id chat-id :status :running})
  (f.chat.lifecycle/trigger-chat-status-hook! chat-ctx)
  (swap! db* assoc-in [:chats chat-id :prompt-id] prompt-id)
  (swap! db* assoc-in [:chats chat-id :model] full-model)
  ;; Persist the variant the chat is currently using so subsequent
  ;; chat/selectedModelChanged, chat/selectedAgentChanged and resume
  ;; flows can read it back. `chat-ctx :variant` is `(or request-variant
  ;; (:variant agent-config))`, so it tracks whatever variant the LLM
  ;; call is actually using on this prompt, and overwriting on every
  ;; prompt keeps the record in sync with the user's current pick.
  (swap! db* assoc-in [:chats chat-id :variant] (:variant chat-ctx))
  ;; Same for the agent: a selection made before the chat existed on the
  ;; server (chat/selectedAgentChanged on an unknown chat is a no-op)
  ;; would otherwise be lost and resume would fall back to the default.
  (when agent
    (swap! db* assoc-in [:chats chat-id :agent] agent))
  (swap! db* update-in [:chats chat-id :user-prompt-count] (fnil inc 0)))

(defn ^:private add-to-history!
  "Appends `msg` to the chat's history, telling modules
   (`:chat/message-added`), and saves the chat. `current-api` is the origin
   api of the reason/tool_call/server_tool_* entries this request writes,
   for the cross-api history sanitizer in eca.llm-api: a later swap to a
   different model can drop entries whose opaque ids (Anthropic signatures,
   OpenAI rs_*/encrypted_content, toolu_*/call_* tool ids) the new provider
   would reject. #209"
  [{:keys [db* chat-id metrics] :as chat-ctx} current-api {:keys [role content] :as msg}]
  (let [with-ts (update msg :created-at #(or % (System/currentTimeMillis)))
        tagged (if (and current-api
                        (map? content)
                        (#{"reason" "tool_call" "tool_call_output"
                           "server_tool_use" "server_tool_result"} role))
                 (assoc with-ts :content (assoc content :api current-api))
                 with-ts)]
    (swap! db* update-in [:chats chat-id :messages] (fnil conj []) tagged)
    (f.chat.lifecycle/emit-messages-added! chat-ctx [tagged])
    ;; Persist after meaningful history mutations so a
    ;; long-running chat is recoverable mid-loop even if
    ;; ECA dies (crash, kill, host reboot) before the
    ;; end-of-prompt save runs. tool_call and
    ;; server_tool_use are skipped because they are
    ;; always paired with the corresponding *_output /
    ;; *_result entry appended right after, which
    ;; triggers the save in their place.
    (when-not (#{"tool_call" "server_tool_use"} role)
      (db/save-chat! @db* chat-id metrics))))

(defn ^:private turn-state
  "What a request shares with its callbacks: `:chat-ctx`, `:user-messages`,
   `:db` (the db when the request started), `:model-capabilities`,
   `:provider-auth`, `:all-tools`, `:auto-continue-limit` and the request's
   state: `:auto-continue-count*` (consecutive recoveries; reset when a
   response completes and its tools run), `:received-msgs*` (the streamed
   text), `:reasonings*`, `:server-tool-times*`, `:pending-server-tool-uses*`,
   and `:add-to-history!`."
  [{:keys [db* config chat-id provider model full-model agent] :as chat-ctx} user-messages]
  (let [db @db*
        model-capabilities (get-in db [:models full-model])
        provider-auth (get-in @db* [:auth provider])
        all-tools (f.tools/all-tools chat-id agent @db* config {:full-model full-model})
        auto-continue-limit (f.chat.recovery/provider-max-auto-continues config provider)
        auto-continue-count* (atom (:auto-continue-count chat-ctx 0))
        received-msgs* (atom "")
        reasonings* (atom {})
        server-tool-times* (atom {})
        pending-server-tool-uses* (atom {})
        current-api (:api (llm-api/provider->api-handler provider model model-capabilities config))]
    {:chat-ctx chat-ctx
     :user-messages user-messages
     :db db
     :model-capabilities model-capabilities
     :provider-auth provider-auth
     :all-tools all-tools
     :auto-continue-limit auto-continue-limit
     :auto-continue-count* auto-continue-count*
     :received-msgs* received-msgs*
     :reasonings* reasonings*
     :server-tool-times* server-tool-times*
     :pending-server-tool-uses* pending-server-tool-uses*
     :add-to-history! (partial add-to-history! chat-ctx current-api)}))

(defn ^:private generate-title!
  "Titles the chat in the background, at its first prompt, and again at its
   third: a module may give the title (`:chat/title`), else the chat model
   writes it. A title set meanwhile wins."
  [{:keys [chat-ctx db user-messages model-capabilities provider-auth]}]
  (let [{:keys [db* config chat-id provider model agent metrics]} chat-ctx
        prompt-count (get-in db [:chats chat-id :user-prompt-count] 0)
        retitle? (= prompt-count 3)
        generate-title? (and (get-in config [:chat :title])
                             (not (get-in db [:chats chat-id :title-custom?]))
                             (or (and (not (get-in db [:chats chat-id :title]))
                                      (not retitle?))
                                 retitle?))]
    (when generate-title?
      ;; On retitle (3rd prompt), flatten the conversation into a single
      ;; user message instead of replaying it as role-structured past-messages.
      ;; This prevents the title model (notably Opus) from mimicking the prior
      ;; assistant's style and emitting section-header titles like "Understand".
      (let [title-user-messages
            (if retitle?
              (let [history (->> (get-in db [:chats chat-id :messages] [])
                                 shared/messages-after-last-compact-marker
                                 (filterv #(contains? #{"user" "assistant"} (:role %))))
                    transcript (conversation->title-transcript
                                (into (vec history) user-messages))]
                [{:role "user"
                  :content [{:type :text
                             :text (str "Summarize the following conversation as a thread title.\n"
                                        "Follow the rules from the system prompt. Output only the title.\n\n"
                                        "Conversation:\n"
                                        transcript)}]}])
              ;; Attached contexts may be whole files or directories, not
              ;; needed to title the prompt (#683), and the contexts modules
              ;; added aren't the user's words.
              (mapv message-sanitize/without-module-contexts
                    (without-contents user-messages (:attached-contents chat-ctx))))]
        (future* config
          (when-let [output-text
                     (or
                      ;; a module may give the title (:chat/title); else the chat model writes it
                      (some-> (events/emit! chat-ctx :chat/title
                                            (f.chat.lifecycle/chat-event
                                             chat-ctx
                                             {:messages (->> (-> (->> (get-in db [:chats chat-id :messages] [])
                                                                      shared/messages-after-last-compact-marker
                                                                      (filterv #(contains? #{"user" "assistant"} (:role %))))
                                                                 (into user-messages))
                                                             (mapv message-sanitize/without-module-contexts))
                                              :retitle? retitle?}))
                              :title
                              shared/not-blank)
                      (:output-text (llm-api/sync-prompt!
                                     {:provider provider
                                      :model model
                                      :model-capabilities
                                      (assoc model-capabilities :reason? false :tools false :web-search false)
                                      :instructions (f.prompt/chat-title-prompt agent config)
                                      :past-messages nil
                                      :user-messages title-user-messages
                                      :config config
                                      :provider-auth provider-auth
                                      :subagent? true})))]
            ;; A title set meanwhile (chat/update, e.g. a client renaming
            ;; the chat while this request ran) wins over the generated one.
            (when (and output-text
                       (not (get-in @db* [:chats chat-id :title-custom?])))
              (let [title (f.chat.ops/sanitize-title output-text)]
                (swap! db* assoc-in [:chats chat-id :title] title)
                (f.chat.lifecycle/send-content! chat-ctx :system (assoc-some {:type :metadata} :title title))
                (when (= :idle (get-in @db* [:chats chat-id :status]))
                  (db/save-chat! @db* chat-id metrics))))))))))

(defn ^:private request
  "What `llm-api/sync-or-async-prompt!` gets for the request of `turn`."
  [{:keys [chat-ctx user-messages model-capabilities provider-auth all-tools] :as turn}]
  (let [{:keys [db* config chat-id provider model full-model agent instructions prompt-id]} chat-ctx]
    (merge
     {:model model
      :provider provider
      :model-capabilities model-capabilities
      :user-messages user-messages
      :instructions  instructions
      :past-messages (f.chat.recovery/messages-to-send @db* chat-id full-model)
      :config  config
      :tools all-tools
      ;; modules may change what the model sees, in every request (:llm/before-request)
      :on-before-request (partial f.chat.lifecycle/before-llm-request chat-ctx)
      :provider-auth provider-auth
      ;; Renew before each prompt! invocation so long-running chats
      ;; (spawn_agent, retries) always get a fresh token.
      :refresh-provider-auth-fn (fn []
                                  (f.chat.lifecycle/maybe-renew-auth-token chat-ctx)
                                  (get-in @db* [:auth provider]))
      ;; `config` is the turn's snapshot, reused by compaction, auto-continue
      ;; and subagents, so a `${cmd:...}` key must be re-read too.
      :refresh-config-fn #(config/all @db*)
      :variant (:variant chat-ctx)
      :prompt-cache-key (f.chat.system-prompt/prompt-cache-key agent)
      :subagent? (some? (get-in @db* [:chats chat-id :subagent]))
      :cancelled? (fn []
                    (let [chat (get-in @db* [:chats chat-id])]
                      (or (identical? :stopping (:status chat))
                          (:prompt-finished? chat)
                          (not= prompt-id (:prompt-id chat)))))}
     (f.chat.callbacks/callbacks turn))))

(defn ^:private request-ended!
  "The request's worker ends: when nothing finished the request, the chat is
   idle again."
  [{:keys [chat-ctx]}]
  (let [{:keys [db* chat-id metrics prompt-id]} chat-ctx]
    (when (and (= prompt-id (get-in @db* [:chats chat-id :prompt-id]))
               (contains? #{:stopping :running} (get-in @db* [:chats chat-id :status])))
      (swap! db* assoc-in [:chats chat-id :status] :idle)
      ;; Only notify client if finish-chat-prompt! hasn't already run,
      ;; otherwise the belated statusChanged causes duplicate finished handling.
      (when-not (get-in @db* [:chats chat-id :prompt-finished?])
        (when (contains? (:managed-chats @db*) chat-id)
          (swap! db* assoc-in [:managed-chats chat-id :interrupted?] true))
        (messenger/chat-status-changed (:messenger chat-ctx) {:chat-id chat-id :status :idle})
        (f.chat.lifecycle/trigger-chat-status-hook! chat-ctx))
      (db/save-chat! @db* chat-id metrics))))

(defn ^:private send-request! [turn]
  (try
    (llm-api/sync-or-async-prompt! (request turn))
    (catch Exception e
      (f.chat.recovery/request-threw! turn e))
    (finally
      (request-ended! turn))))

(defn prompt-messages!
  "Sends `user-messages` to the LLM, in a request of the turn: its first one
   or a next one (a retry, an auto-continue, after a compaction...).
   `source-type` says where the messages come from: for :prompt-message,
   :eca-command and :mcp-prompt the `:chat/before-request` handlers run
   first, and only a :prompt-message may be rewritten. Compaction and
   recovery send the turn's next request with `:continue-turn-fn`, this
   function, in chat-ctx."
  [user-messages source-type {:keys [db* config chat-id provider model full-model agent] :as chat-ctx}]
  (when-not full-model
    (throw (ex-info llm-api/no-available-model-error-msg {})))
  (let [run-hooks? (#{:prompt-message :eca-command :mcp-prompt} source-type)
        user-messages (if run-hooks?
                        (pre-request-messages chat-ctx user-messages source-type)
                        user-messages)
        prompt-id (random-uuid)]
    (when user-messages
      (start-request! chat-ctx user-messages run-hooks? prompt-id)
      (let [chat-ctx (assoc chat-ctx
                            :prompt-id prompt-id
                            ;; Subscription logins have no per-token cost, usage shows their quota instead.
                            :subscription? (f.quota/subscription? provider @db* config)
                            :continue-turn-fn prompt-messages!)
            _ (f.chat.lifecycle/maybe-renew-auth-token chat-ctx) ;; ensures captured provider-auth fallback is fresh
            {:keys [db all-tools] :as turn} (turn-state chat-ctx user-messages)]
        (log-api-switch! db chat-id provider model config)
        (generate-title! turn)
        (f.chat.lifecycle/send-content! chat-ctx :system {:type :progress :state :running :text "Waiting model"})
        (cond
          ;; a module asked to compact (eca.api/request-compaction!)
          (f.chat.compaction/run-requested-compaction! chat-ctx all-tools user-messages
                                                       (f.chat.compaction/retry-without-compaction chat-ctx user-messages))
          nil

          (and (f.chat.compaction/auto-compact? chat-id agent full-model config @db*)
               (not (f.chat.compaction/auto-compact-suppressed? chat-ctx)))
          (f.chat.compaction/trigger-auto-compact! chat-ctx all-tools user-messages)

          :else
          (start-prompt-worker! chat-ctx #(send-request! turn)))))))
