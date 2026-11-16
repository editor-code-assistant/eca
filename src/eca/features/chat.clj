(ns eca.features.chat
  (:require
   [clojure.string :as string]
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.chat.lifecycle :as f.chat.lifecycle]
   [eca.features.chat.ops :as f.chat.ops]
   [eca.features.chat.recovery :as f.chat.recovery]
   [eca.features.chat.replay :as f.chat.replay]
   [eca.features.chat.steer :as f.chat.steer]
   [eca.features.chat.system-prompt :as f.chat.system-prompt]
   [eca.features.chat.tool-calls :as f.chat.tool-calls]
   [eca.features.chat.turn :as f.chat.turn]
   [eca.features.commands :as f.commands]
   [eca.features.context :as f.context]
   [eca.features.prompt :as f.prompt]
   [eca.features.tools :as f.tools]
   [eca.llm-api :as llm-api]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.metrics :as metrics]
   [eca.models :as models]
   [eca.modules.events :as events]
   [eca.modules.mcp :as m.mcp]
   [eca.modules.repo-map :as m.repo-map]
   [eca.modules.rules :as m.rules]
   [eca.modules.skills :as m.skills]
   [eca.shared :as shared :refer [assoc-some future*]]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CHAT]")

(defn default-model [db config]
  (llm-api/default-model db config))

(defn ^:private selected-provider
  "Provider currently selected for `chat-id`, derived from the chat's own model
   or, for subagents, its parent chat's model."
  [db chat-id]
  (let [model (or (get-in db [:chats chat-id :model])
                  (when-let [parent-id (db/parent-chat-id db chat-id)]
                    (get-in db [:chats parent-id :model])))]
    (some-> model shared/full-model->provider+model first)))

(defn ^:private resolve-full-model
  "Resolve the full model id for a prompt response and LLM request.
   Model ids are resolved alias-first: a bare id is matched against the currently
   selected provider's models before being treated as a literal full model id."
  [requested-model db chat-id agent-config config]
  (let [provider (selected-provider db chat-id)]
    (or (when requested-model
          (or (models/full-model-for db provider requested-model)
              requested-model))
        (let [stored-model (get-in db [:chats chat-id :model])
              agent-default-model (:defaultModel agent-config)]
          (cond
            (and stored-model
                 (contains? (:models db) stored-model))
            stored-model

            agent-default-model
            (or (models/full-model-for db provider agent-default-model)
                (default-model db config))

            :else
            (default-model db config))))))

(declare prompt)

(defn ^:private send-mcp-prompt!
  [{:keys [prompt args] :as _decision}
   {:keys [db*] :as chat-ctx}]
  (let [{:keys [arguments]} (first (filter #(= prompt (:name %)) (m.mcp/all-prompts @db*)))
        args-vals (zipmap (map :name arguments) args)
        {:keys [messages error-message]} (m.mcp/get-prompt! prompt args-vals @db*)]
    (cond
      error-message
      (do (f.chat.lifecycle/send-content! chat-ctx
                                          :system
                                          {:type :text
                                           :text error-message})
          (f.chat.lifecycle/finish-chat-prompt! :idle chat-ctx))

      (seq messages)
      (f.chat.turn/prompt-messages! messages :mcp-prompt chat-ctx)

      :else
      (do (f.chat.lifecycle/send-content! chat-ctx
                                          :system
                                          {:type :text
                                           :text (format "No response from prompt '%s'." prompt)})
          (f.chat.lifecycle/finish-chat-prompt! :idle chat-ctx)))))

(defn ^:private handle-command! [{:keys [command args]} chat-ctx]
  (try
    (let [{:keys [type on-finished-side-effect on-after-finish!] :as result} (f.commands/handle-command! command args chat-ctx)]
      (case type
        :chat-messages (do
                         (when (:clear-before? result)
                           (messenger/chat-cleared (:messenger chat-ctx) {:chat-id (:chat-id chat-ctx) :messages true})
                           (messenger/chat-status-changed (:messenger chat-ctx) {:chat-id (:chat-id chat-ctx) :status :running}))
                         (f.chat.steer/send-command-chat-messages! result chat-ctx)
                         (f.chat.lifecycle/finish-chat-prompt! :idle (assoc chat-ctx :skip-post-request-hooks? true)))
        :new-chat-status (f.chat.lifecycle/finish-chat-prompt! (:status result) (assoc chat-ctx :skip-post-request-hooks? true))
        :delete-chat (let [{:keys [target-chat-id text]} result
                           {:keys [db* messenger config metrics]} chat-ctx]
                       (when text
                         (f.chat.lifecycle/send-content! chat-ctx :system {:type :text :text text}))
                       ;; Finish the turn BEFORE deleting: finish-chat-prompt!'s
                       ;; status transition on a deleted chat would resurrect it
                       ;; as a ghost record.
                       (f.chat.lifecycle/finish-chat-prompt! :idle (assoc chat-ctx :skip-post-request-hooks? true))
                       (f.chat.ops/delete-chat {:chat-id target-chat-id} db* messenger config metrics))
        :side-prompt (let [{:keys [target-chat-id target-title target-messages message notice-text]} result
                           {:keys [chat-id db*]} chat-ctx]
                       (f.chat.lifecycle/send-content! chat-ctx :system {:type :text :text notice-text})
                       (when target-chat-id
                         (let [target-ctx (assoc chat-ctx :chat-id target-chat-id)]
                           (f.chat.replay/send-chat-contents! target-messages target-ctx)
                           (f.chat.lifecycle/send-content! target-ctx :system (assoc-some {:type :metadata} :title target-title))))
                       ;; Finish the /btw turn in the origin chat only when no prompt is
                       ;; in flight there; finishing mid-run would set :prompt-finished?
                       ;; and cancel the running turn.
                       (when-not (contains? #{:running :stopping} (get-in @db* [:chats chat-id :status]))
                         (f.chat.lifecycle/finish-chat-prompt! :idle (assoc chat-ctx :skip-post-request-hooks? true)))
                       (when target-chat-id
                         (prompt {:chat-id target-chat-id
                                  :message message
                                  :agent (:agent chat-ctx)
                                  :variant (:variant chat-ctx)}
                                 db*
                                 (:messenger chat-ctx)
                                 (:config chat-ctx)
                                 (:metrics chat-ctx))))
        :send-prompt (let [prompt-contents (:prompt result)]
                       ;; Keep original slash command in :message for hooks (already in parent chat-ctx)
                       (f.chat.turn/prompt-messages! [{:role "user" :content prompt-contents}]
                                                     :eca-command
                                                     (assoc-some chat-ctx
                                                                 :on-finished-side-effect on-finished-side-effect
                                                                 :on-after-finish! on-after-finish!
                                                                 :compaction? (:compaction? result)
                                                                 :compaction-done? (:compaction-done? result))))
        nil))
    (catch Exception e
      (logger/error e)
      (f.chat.lifecycle/send-content! chat-ctx :system {:type :text
                                                        :text (str "Error: " (ex-message e) "\n\nCheck ECA stderr for more details.")})
      (f.chat.lifecycle/finish-chat-prompt-stopped! :idle chat-ctx))))

(defn ^:private run-start-hooks!
  "Runs the `:chat/started` event at the chat's first prompt, the chatStart
   (or subagentStart) shell hooks last (4.17). The handlers' context goes in
   the chat's system prompt (`:startup-context`, which build-chat-instructions
   and /prompt-show read); a `:stop` ends the turn: the result has
   `:stop-turn?`, `:stop-reason` and `:ended-by`."
  [{:keys [db* chat-id] :as chat-ctx} db-before-hooks resumed? full-model]
  (config/await-plugins-resolved!)
  (let [config (config/all db-before-hooks)
        event (events/emit! (assoc chat-ctx :config config) :chat/started
                            (f.chat.lifecycle/chat-event (assoc chat-ctx :full-model full-model) {:resumed? (boolean resumed?)}))]
    ;; Note: :chat-start-fired is set by prompt* (via swap-vals!) before this
    ;; runs, since prompt* is what decides whether the start event fires at all.
    (when-let [context (shared/not-blank (:additional-context event))]
      (swap! db* assoc-in [:chats chat-id :startup-context] context))
    (if (contains? event :stop)
      {:stop-turn? true :stop-reason (:stop event) :ended-by (f.chat.lifecycle/ended-by event)}
      {:stop-turn? false})))

(defn ^:private prompt*
  [{:keys [model]}
   {:keys [chat-id contexts message agent agent-config db* messenger config metrics] :as base-chat-ctx}]
  (let [provided-chat-id chat-id
        ;; Snapshot DB to detect new/resumed chat BEFORE hooks mutate it
        [db-before-hooks _] (swap-vals! db* assoc-in [:chat-start-fired chat-id] true)
        _ (f.chat.ops/mark-editor-open! db* chat-id)
        existing-chat-before-prompt (get-in db-before-hooks [:chats chat-id])
        chat-start-fired? (get-in db-before-hooks [:chat-start-fired chat-id])
        has-messages? (seq (:messages existing-chat-before-prompt))
        resumed? (boolean (and (not chat-start-fired?)
                               provided-chat-id
                               has-messages?))
        ;; await-plugins-resolved! must run even though db-before-hooks may not
        ;; have :model yet (model sync can lag behind plugin resolution).
        _ (when-not chat-start-fired? (config/await-plugins-resolved!))
        ;; Respect explicit model; otherwise prefer the chat's stored model
        ;; (so resumed chats keep the provider/model they started with, #417);
        ;; then fall back to the agent default if it resolves to an available
        ;; model; finally, deterministic default-model resolution.
        full-model (resolve-full-model model @db* chat-id agent-config config)
        start-hook-result (when-not chat-start-fired?
                            (run-start-hooks! base-chat-ctx db-before-hooks resumed? full-model))]
    (if (:stop-turn? start-hook-result)
      (do
        (f.chat.lifecycle/send-ended! base-chat-ctx "Turn stopped" (:ended-by start-hook-result) (:stop-reason start-hook-result))
        (f.chat.lifecycle/finish-chat-prompt-stopped! :idle base-chat-ctx)
        {:chat-id chat-id
         :model full-model
         :status :prompting})
      (let [;; Re-read DB after potential chatStart modifications
            db @db*
            _ (when (seq contexts)
                (f.chat.lifecycle/send-content! {:messenger messenger :chat-id chat-id} :system {:type :progress
                                                                                                 :state :running
                                                                                                 :text "Parsing given context"}))
            refined-contexts (concat
                              (f.context/agents-file-contexts db config)
                              (f.context/raw-contexts->refined contexts db))
            {static-rules :static path-scoped-rules :path-scoped} (m.rules/all-rules config (:workspace-folders db) agent full-model)
            all-tools (f.tools/all-tools chat-id agent @db* config {:full-model full-model})
            skills (->> (m.skills/all config (:workspace-folders db))
                        (remove
                         (fn [skill]
                           (= :deny (f.tools/approval all-tools
                                                      {:server {:name "eca"} :name "skill"}
                                                      {"name" (:name skill)}
                                                      db
                                                      config
                                                      agent)))))
            repo-map* (delay (m.repo-map/repo-map db config {:as-string? true}))
            instructions (f.chat.system-prompt/chat-instructions
                          base-chat-ctx db full-model
                          {:refined-contexts refined-contexts
                           :static-rules static-rules
                           :path-scoped-rules path-scoped-rules
                           :skills skills
                           :repo-map* repo-map*
                           :all-tools all-tools})
            image-contents (->> refined-contexts
                                (filter #(= :image (:type %))))
            text-contents (when-let [texts-str (f.prompt/build-text-contexts refined-contexts)]
                            [{:type :text :text texts-str}])
            prompt-contexts (f.context/contexts-str-from-prompt message db)
            expanded-prompt-contexts (when-let [contexts-str (some-> prompt-contexts
                                                                     seq
                                                                     (f.prompt/contexts-str repo-map* nil))]
                                       [{:type :text :text contexts-str}])
            decision (f.chat.steer/message->decision message db config)
            ;; Context-area files and repoMap are delivered in the user message too,
            ;; keeping the cached system prompt stable: in full when new, as a
            ;; one-line notice when changed and not at all once removed or when the
            ;; prompt text already expands them with @path. What was sent is only
            ;; tracked once the message reaches the chat history. #683
            attached-delta (f.context/attached-contexts-delta
                            refined-contexts
                            (get-in db [:chats chat-id :sent-attached-contexts])
                            (set (keep f.context/attached-context-key prompt-contexts))
                            repo-map*)
            attached-contents (when-let [attached-str (f.prompt/build-attached-contexts
                                                       (:new attached-delta)
                                                       (:changed attached-delta)
                                                       repo-map*)]
                                [{:type :text :text attached-str}])
            ;; Cursor (and other volatile editor-state) is delivered per-turn in the
            ;; user message - never the system prompt - and only re-sent when it
            ;; changed. This keeps the cached system/prefix stable across turns,
            ;; avoiding llama.cpp full prompt re-processing on every cursor move. #464
            ;; Only normal prompts send this synthesized user message to the model;
            ;; /prompt-show and MCP prompts use different messages.
            editor-state-context (f.prompt/build-editor-state-context refined-contexts)
            editor-state-changed? (and editor-state-context
                                       (not= editor-state-context
                                             (get-in db [:chats chat-id :last-editor-state])))
            _ (when (and editor-state-changed?
                         (= :prompt-message (:type decision)))
                (swap! db* assoc-in [:chats chat-id :last-editor-state] editor-state-context))
            editor-state-contents (when editor-state-changed?
                                    [{:type :text :text editor-state-context}])
            user-messages [{:role "user" :content (vec (concat [{:type :text :text message}]
                                                               expanded-prompt-contexts
                                                               attached-contents
                                                               text-contents
                                                               editor-state-contents
                                                               image-contents))}]
            [provider model] (when full-model (shared/full-model->provider+model full-model))
            chat-ctx (cond-> (merge base-chat-ctx
                                    {:instructions instructions
                                     :all-tools all-tools
                                     :user-messages user-messages
                                     :full-model full-model
                                     :provider provider
                                     :model model
                                     :messenger messenger})
                       ;; Like the cursor, only normal prompts send the attached
                       ;; contexts; /prompt-show, commands and MCP prompts don't
                       ;; consume them.
                       (= :prompt-message (:type decision))
                       (assoc :attached-contents attached-contents
                              :pending-sent-attached-contexts (:sent attached-delta)))]
        ;; Clear prompt-finished? so finish-chat-prompt! can properly terminate
        ;; this prompt cycle. prompt-messages! already does this for regular
        ;; prompts, but commands and mcp-prompts go through different paths.
        (swap! db* update-in [:chats chat-id] dissoc :prompt-finished? :follow-up-active?)
        (case (:type decision)
          :mcp-prompt (send-mcp-prompt! decision chat-ctx)
          :eca-command (handle-command! decision chat-ctx)
          :prompt-message (f.chat.turn/prompt-messages! user-messages :prompt-message chat-ctx))
        (metrics/count-up! "prompt-received"
                           {:full-model full-model
                            :agent agent}
                           metrics)
        {:chat-id chat-id
         :model full-model
         :status :prompting}))))

(defn ^:private prompt-input*
  "Runs the `:chat/input` event on the text before commands, skills or the
   model see it. A module may change the text, or handle it: then the model
   isn't called, and a string answer is shown in the chat. `:source` is
   `:module` for `eca.api/send-prompt!`, else `:user`."
  [{:keys [source] :as params} {:keys [chat-id db* message] :as base-chat-ctx}]
  (let [event (events/emit! base-chat-ctx :chat/input
                            (f.chat.lifecycle/chat-event base-chat-ctx {:text message :source (or source :user)}))
        handled (:handled event)
        text (:text event)]
    (if handled
      (do
        (when (string? handled)
          (f.chat.lifecycle/send-content! base-chat-ctx :system {:type :text :text handled}))
        (swap! db* update-in [:chats chat-id] dissoc :prompt-finished? :follow-up-active?)
        (f.chat.lifecycle/finish-chat-prompt! :idle (assoc base-chat-ctx :skip-post-request-hooks? true))
        {:chat-id chat-id
         :model (get-in @db* [:chats chat-id :model])
         :status :prompting})
      (prompt* params (cond-> base-chat-ctx
                        (and (string? text) (not (string/blank? text))) (assoc :message text))))))

(def ^:private max-client-chat-id-length 256)

(defn ^:private server-managed-subagent-chat-id?
  [db chat-id]
  (and (string? chat-id)
       (string/starts-with? chat-id "subagent-")
       (some? (get-in db [:chats chat-id :subagent]))))

(defn validate-client-chat-id
  "Validates a client-supplied chat id. Returns nil when valid, otherwise an
   error message string.

   Public so non-prompt selection handlers (`chat/selectedModelChanged`,
   `chat/selectedAgentChanged`) can apply the same rules to their `chat-id`
   field.

   Rejected: blank, the reserved `subagent-` prefix (used for deterministic
   subagent chat ids in `eca.modules.subagents`), embedded whitespace or
   control characters (which would mangle log lines / tab-line titles), and
   ids longer than `max-client-chat-id-length` characters."
  [chat-id]
  (cond
    (string/blank? chat-id)
    "chatId must be a non-blank string"

    (string/starts-with? chat-id "subagent-")
    "chatId prefix 'subagent-' is reserved for server-managed subagent chats"

    (re-find #"[\s\p{Cntrl}]" chat-id)
    "chatId must not contain whitespace or control characters"

    (> (count chat-id) max-client-chat-id-length)
    (str "chatId must be " max-client-chat-id-length " characters or fewer")))

(defn prompt
  "`config` is either the config map or a 0-arity fn resolving it. The fn is
   only called after the user prompt was echoed to the client and a
   \"Loading config\" progress was sent, since resolving config may be slow
   (e.g. `${cmd:...}` keys spawning commands). Callers that already hold the
   config should pass the map."
  [{:keys [message agent behavior chat-id contexts variant trust] :as params} db* messenger config metrics]
  (let [provided-chat-id chat-id
        managed (get-in @db* [:managed-chats chat-id])
        invalid-id-reason (cond
                            ;; Only the run holding the owner token may prompt a managed chat.
                            (and managed
                                 (not (and (:token managed)
                                           (identical? (:token managed) (:owner-token params)))))
                            "this chat is managed (e.g. a subagent) and can only be prompted by its owner"

                            (and (some? provided-chat-id)
                                 (not (server-managed-subagent-chat-id? @db* provided-chat-id)))
                            (validate-client-chat-id provided-chat-id))]
    (if invalid-id-reason
      (do (logger/with-chat-context provided-chat-id (db/parent-chat-id @db* provided-chat-id)
            (logger/warn logger-tag "Rejected chat/prompt with invalid chat-id"
                         {:chat-id provided-chat-id :reason invalid-id-reason}))
          {:chat-id provided-chat-id
           :model "error"
           :status :error})
      (let [chat-id (or provided-chat-id (str (random-uuid)))
            message (string/trim message)
            ;; Atomically seed the chat record if absent and remember whether
            ;; we were the ones to create it. swap-vals! returns [old new] so
            ;; chat-just-created? is true iff the chat was missing pre-swap.
            [old-db _] (swap-vals! db* update :chats
                                   (fn [chats]
                                     (if (contains? chats chat-id)
                                       chats
                                       (assoc chats chat-id {:id chat-id}))))
            chat-just-created? (not (contains? (:chats old-db) chat-id))
            ;; A chat from a previous session may be index-only in memory;
            ;; load its message history before prompting on it.
            _ (db/hydrate-chat! db* chat-id metrics)
            ;; Notify observers (other clients, remote SSE viewers) about a
            ;; new client-initiated chat. Skipped on the legacy null-id path
            ;; because the prompting client learns its id from the response.
            _ (when (and provided-chat-id chat-just-created?)
                (messenger/chat-opened messenger {:chat-id chat-id}))
            parent-chat-id (db/parent-chat-id @db* chat-id)
            user-content-id (f.chat.lifecycle/new-content-id)
            early-chat-ctx (assoc-some {:messenger messenger
                                        :chat-id chat-id}
                                       :parent-chat-id parent-chat-id)
            ;; Show original prompt to user right away, LLM receives the
            ;; modified version later.
            _ (f.chat.lifecycle/send-content! early-chat-ctx :user {:type :text
                                                                     :content-id user-content-id
                                                                     :text (str message "\n")})
            resolve-config? (fn? config)
            _ (when resolve-config?
                (f.chat.lifecycle/send-content! early-chat-ctx :system {:type :progress
                                                                         :state :running
                                                                         :text "Loading config"}))]
        (logger/with-chat-context chat-id parent-chat-id
          (try
            (let [config (if resolve-config? (config) config)
                  raw-agent (or agent
                                behavior ;; backward compat: accept old 'behavior' param
                                (get-in @db* [:chats chat-id :agent])
                                (-> config :chat :defaultAgent) ;; legacy
                                (-> config :defaultAgent))
                  ;; A freshly-created chat with no client-provided trust inherits
                  ;; the server default (chat.defaultTrust), so every editor gets
                  ;; trust-by-default without having to send anything.
                  seeded-default-trust? (and chat-just-created?
                                             (nil? trust)
                                             (boolean (-> config :chat :defaultTrust)))
                  effective-trust (if seeded-default-trust? true trust)
                  selected-agent (config/validate-agent-name raw-agent config)
                  agent-config (get-in config [:agent selected-agent])
                  base-chat-ctx (assoc-some {:metrics metrics
                                             :config config
                                             :contexts contexts
                                             :db* db*
                                             :messenger messenger
                                             :user-content-id user-content-id
                                             :message message
                                             :chat-id chat-id
                                             :agent selected-agent
                                             :agent-config agent-config
                                             :trust effective-trust
                                             :variant (or variant (:variant agent-config))
                                             :on-follow-up (fn [follow-up-text chat-ctx]
                                                             (f.chat.turn/prompt-messages!
                                                              [{:role "user"
                                                                :content [{:type :text
                                                                           :text follow-up-text}]}]
                                                              :follow-up
                                                              chat-ctx))}
                                            :parent-chat-id parent-chat-id)
                  _ (when (some? effective-trust)
                      (swap! db* assoc-in [:chats chat-id :trust] effective-trust))
                  ;; When we seeded the default (the client didn't ask), align the
                  ;; client's per-chat trust indicator with the auto-approval the
                  ;; server is about to apply.
                  _ (when (and seeded-default-trust? provided-chat-id)
                      (config/notify-fields-changed-only! {:chat {:select-trust true}} messenger db* chat-id))]
              (try
                (prompt-input* params base-chat-ctx)
                (catch Exception e
                  (logger/error e)
                  (f.chat.lifecycle/send-content! base-chat-ctx :system {:type :text
                                                                         :text (str "Error: " (ex-message e) "\n\nCheck ECA stderr for more details.")})
                  (f.chat.lifecycle/finish-chat-prompt! :idle (f.chat.lifecycle/strip-hook-callbacks base-chat-ctx))
                  {:chat-id chat-id
                   :model "error"
                   :status :error})))
            ;; Failure before we have a full chat-ctx (e.g. config resolution):
            ;; report it and close the progress we already sent.
            (catch Exception e
              (logger/error e)
              (f.chat.lifecycle/send-content! early-chat-ctx :system {:type :text
                                                                       :text (str "Error: " (ex-message e) "\n\nCheck ECA stderr for more details.")})
              (f.chat.lifecycle/send-content! early-chat-ctx :system {:type :progress :state :finished})
              {:chat-id chat-id
               :model "error"
               :status :error})))))))

(defn ^:private inline-title [message]
  (let [message (-> (or message "")
                    (string/replace #"\s+" " ")
                    (string/trim))]
    (str "inline: " (if (> (count message) 40)
                      (str (subs message 0 40) "...")
                      message))))

(defn inline-prompt
  "Handles `chat/inlinePrompt`: a prompt whose answer is rendered inline in
   the editor (overlay/inlay) while backed by a regular chat session.

   The client mints `chat-id`. On first use, the chat record is seeded with
   `:kind :inline` and, when `source-chat-id` is given, a server-side copy of
   that chat's history (never replayed to the client - only new turns are
   streamed). Model and variant precedence at creation: `chatInline` config >
   source chat > default resolution; both stick to the chat record afterwards.
   Agent defaults from `chatInline.agent` on every call when not provided.
   Delegates to `prompt`, so streaming, tool calls and follow-ups behave
   like any other chat."
  [{:keys [chat-id source-chat-id message trust] :as params} db* messenger config metrics]
  (let [invalid-reason (or (when-not chat-id "chatId is required")
                           (validate-client-chat-id chat-id)
                           (when source-chat-id (validate-client-chat-id source-chat-id))
                           (when (string/blank? message) "message is required"))]
    (if invalid-reason
      (do (logger/warn logger-tag "Rejected chat/inlinePrompt with invalid params"
                       {:chat-id chat-id :source-chat-id source-chat-id :reason invalid-reason})
          {:chat-id chat-id
           :model "error"
           :status :error})
      (let [chat-inline-config (:chatInline config)
            _ (db/hydrate-chat! db* chat-id metrics)
            new-chat (when-not (get-in @db* [:chats chat-id])
                       (let [source-chat (when source-chat-id
                                           (db/hydrate-chat! db* source-chat-id metrics)
                                           (get-in @db* [:chats source-chat-id]))
                             _ (when (and source-chat-id (not source-chat))
                                 (logger/warn logger-tag "chat/inlinePrompt source chat not found, starting empty"
                                              {:chat-id chat-id :source-chat-id source-chat-id}))
                             copied (when source-chat
                                      (merge {:last-api (:last-api source-chat)
                                              :messages (f.chat.ops/drop-dangling-tool-calls
                                                         (vec (:messages source-chat)))}
                                             (select-keys source-chat [:trust :remembered-approvals
                                                                       :prompt-cache :startup-context
                                                                       :modules])))
                             ;; Mirror prompt's default-trust seeding for brand-new
                             ;; chats: explicit trust param wins, then the source
                             ;; chat's trust, then chat.defaultTrust.
                             default-trust? (and (nil? trust)
                                                 (nil? (:trust copied))
                                                 (boolean (-> config :chat :defaultTrust)))
                             now (System/currentTimeMillis)]
                         (cond-> (merge (assoc-some {:id chat-id
                                                     :kind :inline
                                                     :title (inline-title message)
                                                     :status :idle
                                                     :created-at now
                                                     :updated-at now
                                                     :prompt-finished? true}
                                                    :model (or (:model chat-inline-config)
                                                               (:model source-chat))
                                                    :variant (or (:variant chat-inline-config)
                                                                 (:variant source-chat)))
                                        copied)
                           default-trust? (assoc :trust true))))
            ;; Atomically seed the record; swap-vals! tells us whether we won
            ;; a race with a concurrent first prompt for the same id.
            [old-db _] (when new-chat
                         (swap-vals! db* update-in [:chats chat-id] #(or % new-chat)))
            created? (and new-chat (nil? (get-in old-db [:chats chat-id])))
            _ (when created?
                (db/save-chat! @db* chat-id metrics)
                (messenger/chat-opened messenger {:chat-id chat-id :title (:title new-chat)})
                (when (:trust new-chat)
                  (config/notify-fields-changed-only! {:chat {:select-trust true}} messenger db* chat-id)))
            ;; chatInline defaults only steer inline-kind chats; calling this
            ;; method on a regular chat behaves exactly like chat/prompt.
            inline-kind? (= :inline (get-in @db* [:chats chat-id :kind]))
            params (cond-> (-> params
                               (dissoc :source-chat-id)
                               (assoc :chat-id chat-id))
                     inline-kind?
                     (-> (update :agent #(or % (:agent chat-inline-config)))
                         ;; Variant is sticky per inline session: prompt
                         ;; persists the in-use variant on the record every
                         ;; turn, so reading it back here keeps the seeded
                         ;; (config/source) variant across follow-ups and
                         ;; respects switches made in the opened chat UI.
                         (update :variant #(or % (get-in @db* [:chats chat-id :variant])))))]
        (prompt params db* messenger config metrics)))))

(defn tool-call-approve
  [{:keys [chat-id tool-call-id save]} db* messenger config metrics]
  (logger/with-chat-context chat-id (db/parent-chat-id @db* chat-id)
    (if-not (get-in @db* [:chats chat-id :tool-calls tool-call-id])
      (logger/warn logger-tag "tool-call-approve ignored: unknown chat or tool-call"
                   {:chat-id chat-id :tool-call-id tool-call-id})
      (let [chat-ctx {:chat-id chat-id
                      :db* db*
                      :config config
                      :metrics metrics
                      :messenger messenger}]
        (f.chat.tool-calls/transition-tool-call! db* chat-ctx tool-call-id :user-approve
                                                 {:reason {:code :user-choice-allow
                                                           :text "Tool call allowed by user choice"}})
        (when (= "session" save)
          ;; Remembered for the chat session (the root chat, shared with its
          ;; subagents) by full tool name, never server-wide. #639
          (let [{tool-call-name :name :keys [full-name origin arguments]}
                (get-in @db* [:chats chat-id :tool-calls tool-call-id])]
            ;; Granular keys only exist for native tools, an MCP tool named
            ;; like one (e.g. `git`) is remembered as a whole tool.
            (if-let [{keys' :keys} (when (= :native origin)
                                     (f.tools/tool-approval-keys (or full-name tool-call-name) arguments @db* config))]
              ;; Granular remember (e.g. shell commands): remember only the
              ;; derived keys instead of whitelisting the whole tool. #153
              (when (seq keys')
                (swap! db* db/update-remembered-approvals chat-id
                       update-in [full-name :remembered-command-keys] (fnil into #{}) keys'))
              (swap! db* db/update-remembered-approvals chat-id
                     assoc-in [full-name :remember-to-approve?] true))))))))

(defn tool-call-reject
  [{:keys [chat-id tool-call-id]} db* messenger config metrics]
  (logger/with-chat-context chat-id (db/parent-chat-id @db* chat-id)
    (if-not (get-in @db* [:chats chat-id :tool-calls tool-call-id])
      (logger/warn logger-tag "tool-call-reject ignored: unknown chat or tool-call"
                   {:chat-id chat-id :tool-call-id tool-call-id})
      (let [chat-ctx {:chat-id chat-id
                      :db* db*
                      :config config
                      :metrics metrics
                      :messenger messenger}]
        (f.chat.tool-calls/transition-tool-call! db* chat-ctx tool-call-id :user-reject
                                                 {:reason {:code :user-choice-deny
                                                           :text "Tool call rejected by user choice"}})))))

(defn query-context
  [{:keys [query contexts chat-id]}
   db*
   config]
  {:chat-id chat-id
   :contexts (into []
                   (comp (remove (set contexts))
                         (distinct))
                   (f.context/all-contexts query false db* config))})

(defn query-files
  [{:keys [query chat-id]}
   db*
   config]
  {:chat-id chat-id
   :files (into []
                (distinct)
                (f.context/all-contexts query true db* config))})

(defn query-commands
  [{:keys [query chat-id]}
   db*
   config]
  (let [query (string/lower-case query)
        commands (f.commands/all-commands @db* config)
        commands (if (string/blank? query)
                   commands
                   (filter #(or (some-> (:name %) string/lower-case (string/includes? query))
                                (some-> (:description %) string/lower-case (string/includes? query)))
                           commands))]
    {:chat-id chat-id
     :commands commands}))

(defn prompt-steer
  [{:keys [chat-id message]} db* messenger config metrics]
  (logger/with-chat-context chat-id (db/parent-chat-id @db* chat-id)
    (when (and (string? message)
               (not (string/blank? message))
               (get-in @db* [:chats chat-id]))
      (if (f.chat.steer/steer-divertible? message @db* config)
        (do
          (logger/info logger-tag "Steer message diverted to immediate command" {:chat-id chat-id})
          (future* config
            (prompt {:chat-id chat-id
                     :message message
                     :agent (get-in @db* [:chats chat-id :agent])
                     :variant (get-in @db* [:chats chat-id :variant])}
                    db* messenger config metrics)))
        (when (identical? :running (get-in @db* [:chats chat-id :status]))
          (if-let [command (f.chat.steer/steer-refused-command message @db* config)]
            (do
              (logger/info logger-tag "Steer message refused, command can't run while the prompt is running" {:chat-id chat-id :command command})
              (f.chat.lifecycle/send-content! {:chat-id chat-id
                                               :parent-chat-id (db/parent-chat-id @db* chat-id)
                                               :messenger messenger}
                                              :system
                                              {:type :text
                                               :text (format "`/%s` can't run while the chat is working; run it after the current turn finishes.\n" command)}))
            (do
              (logger/info logger-tag "Steer message received" {:chat-id chat-id})
              (swap! db* update-in [:chats chat-id :steer-message]
                     (fn [existing] (if existing (str existing "\n" message) message))))))))))

(defn prompt-steer-remove
  "Drop any pending steer message for the chat.
   No-op if no steer message is pending or the chat is not present.
   Idempotent: cancelling an already-consumed steer is silent."
  [{:keys [chat-id]} db*]
  (logger/with-chat-context chat-id (db/parent-chat-id @db* chat-id)
    (let [removed?* (volatile! false)]
      (swap! db* (fn [db]
                   (if (get-in db [:chats chat-id :steer-message])
                     (do (vreset! removed?* true)
                         (update-in db [:chats chat-id] dissoc :steer-message))
                     db)))
      (when @removed?*
        (logger/info logger-tag "Steer message removed" {:chat-id chat-id})))))

(defn prompt-stop
  [{:keys [chat-id]} db* messenger config metrics & {:keys [silent?]}]
  (logger/with-chat-context chat-id (db/parent-chat-id @db* chat-id)
    (when (identical? :running (get-in @db* [:chats chat-id :status]))
      ;; Set :stopping immediately to prevent race with stream callbacks
      ;; that check status via assert-chat-not-stopped! or cancelled?
      (swap! db* (fn [db]
                   (cond-> (assoc-in db [:chats chat-id :status] :stopping)
                     (contains? (:managed-chats db) chat-id)
                     (assoc-in [:managed-chats chat-id :interrupted?] true))))
      (let [chat-ctx {:chat-id chat-id
                      :db* db*
                      :config config
                      :metrics metrics
                      :messenger messenger
                      :parent-chat-id (db/parent-chat-id @db* chat-id)}]
        (when-not silent?
          (f.chat.lifecycle/send-content! chat-ctx :system {:type :text
                                                            :text "\nPrompt stopped\n"}))

        (f.chat.recovery/reject-active-tool-calls! db* chat-ctx {:code :user-prompt-stop
                                                                 :text "Tool call rejected because of user prompt stop"})
        ;; Clear compacting flags so finish-chat-prompt! isn't blocked
        (swap! db* update-in [:chats chat-id] dissoc :auto-compacting? :continuing? :compacting?)
        (f.chat.lifecycle/finish-chat-prompt! :stopping (f.chat.lifecycle/strip-hook-callbacks chat-ctx))))))
