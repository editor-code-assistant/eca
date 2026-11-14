(ns eca.api
  "The module API: what modules call, built-in and external alike. External
   modules, interpreted with SCI, see only the `eca.api*` namespaces of ECA.

   Every function that needs one takes first the `ctx` ECA passed to the
   module's function (`:start`, a tool or command handler, an event
   handler...). `ctx` is opaque: what it carries depends on where the
   function runs. Within one module API version (`:api` 1) this namespace
   only grows."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.modules :as modules]
   [eca.modules.data :as modules.data]
   [eca.modules.events :as events]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn ^:private db [ctx]
  (or (some-> (:db* ctx) deref) (:db ctx)))

(defn module-id
  "The id of the module this `ctx` was given to, e.g. `:acme/jira`."
  [ctx]
  (:module-id ctx))

(defn config
  "ECA's config, with defaults applied: ECA's keys and every module's, e.g.
   `(:jiraUrl (config ctx))`. Keys are keywords, at the paths users write
   them, e.g. `(get-in (config ctx) [:toolCall :readFile :maxLines])`."
  [ctx]
  (or (:config ctx)
      (some-> (:db* ctx) deref config/all)))

(defn state
  "The module's state: the value its `:start` returned, or nil."
  [ctx]
  (modules/state (:module-id ctx)))

(defn workspaces
  "The workspace folders, as absolute paths."
  [ctx]
  (mapv (comp shared/uri->filename :uri) (:workspace-folders (db ctx))))

(defn log
  "Writes to ECA's log, on stderr, tagged with the module id. `level` is
   `:debug`, `:info`, `:warn` or `:error`; `args` are joined like `str` with
   spaces, and an exception among them is logged with its stack trace."
  [ctx level & args]
  (let [log-fn (case level
                 :error logger/error
                 :warn logger/warn
                 :debug logger/debug
                 logger/info)
        chat-id (:chat-id ctx)
        parent-chat-id (or (:parent-chat-id ctx)
                           (when chat-id (get-in (db ctx) [:chats chat-id :parent-chat-id])))]
    (logger/with-chat-context chat-id parent-chat-id
      (apply log-fn (str "[MODULE " (some-> (:module-id ctx) symbol) "]") args))
    nil))

(defn ^:private owner
  "The id of the module `ctx` belongs to; registrations need one."
  [ctx]
  (or (:module-id ctx)
      (throw (ex-info "This ctx belongs to no module" {}))))

(defn client
  "The connected client: `{:name :version :capabilities}`, as it introduced
   itself, e.g. `{:name \"Emacs\" ...}`."
  [ctx]
  (let [db (db ctx)]
    {:name (get-in db [:client-info :name])
     :version (get-in db [:client-info :version])
     :capabilities (:client-capabilities db)}))

;; --- Chat

(defn ^:private chat-id-of
  "The chat `ctx` runs in; functions that act on a chat need one."
  [ctx]
  (or (:chat-id ctx)
      (throw (ex-info "This ctx belongs to no chat" {}))))

(defn ^:private db*-of
  "The session's db atom; functions that change chats need it."
  [ctx]
  (or (:db* ctx)
      (throw (ex-info "This ctx can't change chats: ECA's session hasn't started" {}))))

(defn chat
  "The chat this ctx runs in, or nil outside of one: `{:id :agent :model
   :variant :status :kind :subagent?}`, plus `:parent-chat-id` for a
   subagent's chat. `:status` is `:idle`, `:running` or `:stopping`."
  [ctx]
  (when-let [chat-id (:chat-id ctx)]
    (when-let [c (get-in (db ctx) [:chats chat-id])]
      (cond-> {:id chat-id
               :agent (or (:agent c) (:agent ctx))
               :model (:model c)
               :variant (:variant c)
               :status (or (:status c) :idle)
               :kind (:kind c)
               :subagent? (boolean (:subagent c))}
        (:parent-chat-id c) (assoc :parent-chat-id (:parent-chat-id c))))))

(defn messages
  "The chat's messages, oldest first, as ECA keeps them: maps with `:role`
   (\"user\", \"assistant\", \"tool_call\", \"tool_call_output\", \"reason\",
   \"compact_marker\"...) and `:content`. With `{:since-checkpoint? true}`,
   only the ones after the last checkpoint (compaction): what the model
   sees."
  ([ctx] (messages ctx nil))
  ([ctx {:keys [since-checkpoint?]}]
   (let [msgs (get-in (db ctx) [:chats (:chat-id ctx) :messages] [])]
     (vec (if since-checkpoint?
            (shared/messages-after-last-compact-marker msgs)
            msgs)))))

(defn chat-state
  "The module's state in this chat: what `swap-chat-state!` left, or nil.
   It's saved with the chat, so it survives a restart, and copied when the
   chat is forked."
  [ctx]
  (when-let [chat-id (:chat-id ctx)]
    (get-in (db ctx) [:chats chat-id :modules (:module-id ctx)])))

(defn ^:private plain-data?
  "Whether `x` can be saved with a chat: nil, strings, numbers, booleans,
   keywords, symbols, UUIDs, dates, and maps, vectors, lists and sets of
   those."
  [x]
  (cond
    (or (nil? x) (string? x) (number? x) (boolean? x) (keyword? x) (symbol? x) (char? x)
        (uuid? x) (instance? java.util.Date x)) true
    (record? x) false
    (map? x) (every? (fn [[k v]] (and (plain-data? k) (plain-data? v))) x)
    (or (vector? x) (list? x) (set? x) (seq? x)) (every? plain-data? x)
    :else false))

(defn swap-chat-state!
  "Swaps the module's state in this chat with `(apply f state args)`, and
   returns the new state, like `swap!` (`f` may run more than once). The
   state must be plain data, since it's saved with the chat: a state with an
   atom or a function in it is refused with an exception. A no-op, returning
   nil, when the chat is gone."
  [ctx f & args]
  (let [id (owner ctx)
        chat-id (chat-id-of ctx)
        new-db (swap! (db*-of ctx)
                      (fn [db]
                        (if-not (get-in db [:chats chat-id])
                          db
                          (let [state (apply f (get-in db [:chats chat-id :modules id]) args)]
                            (when-not (plain-data? state)
                              (throw (ex-info (str "The chat state of " id " must be plain data, to be saved with"
                                                   " the chat: maps, vectors, sets, strings, numbers, keywords...")
                                              {:module id})))
                            (assoc-in db [:chats chat-id :modules id] state)))))]
    (get-in new-db [:chats chat-id :modules id])))

(defn checkpoint!
  "Writes a checkpoint in the chat's history, as a compaction does: from then
   on the model sees only what comes after it, starting with `:messages`,
   e.g. a summary as `{:role \"user\" :content \"..\"}`. The whole history
   stays, for the user. `:label` is shown to the user, and `:data`, plain
   data, stays in the checkpoint for the module. The chat's usage resets. A
   no-op when the chat is gone; returns nil."
  [ctx {:keys [messages label data]}]
  (let [id (owner ctx)
        chat-id (chat-id-of ctx)
        db* (db*-of ctx)]
    (when-not (and (plain-data? data) (plain-data? messages))
      (throw (ex-info "A checkpoint's :messages and :data must be plain data, to be saved with the chat"
                      {:module id})))
    (when (get-in @db* [:chats chat-id])
      ((requiring-resolve 'eca.features.chat.lifecycle/write-checkpoint!)
       (assoc ctx :chat-id chat-id :db* db* :config (config ctx))
       {:module id :label label :data data :messages messages}))
    nil))

(defn request-compaction!
  "Asks ECA to compact the chat, e.g. from a `:chat/usage-updated` handler
   when the context fills up. ECA does it before the chat's next LLM request,
   with the `:chat/before-compact`, `:chat/compact` and `:chat/after-compact`
   events, then the task goes on. `opts`: `:trigger`, `:auto` (default) or
   `:manual`, and `:instructions`. Returns true when the request is kept;
   false while the chat compacts. ECA drops it when the turn can't compact
   again: right after a compaction, past `autoCompactMaxPerTurn` (3 when
   unset), or after a blocked one."
  ([ctx] (request-compaction! ctx nil))
  ([ctx {:keys [trigger instructions]}]
   (let [id (owner ctx)
         chat-id (chat-id-of ctx)
         request (shared/assoc-some {:module id :trigger (if (= :manual trigger) :manual :auto)}
                                    :instructions (shared/not-blank instructions))
         [_ new-db] (swap-vals! (db*-of ctx)
                                (fn [db]
                                  (let [chat (get-in db [:chats chat-id])]
                                    (if (or (nil? chat) (:compacting? chat) (:auto-compacting? chat))
                                      db
                                      (assoc-in db [:chats chat-id :compaction-request] request)))))]
     (identical? request (get-in new-db [:chats chat-id :compaction-request])))))

(defn send-prompt!
  "Sends `text` to the chat as the user's next message: a new turn when the
   chat is idle, else once the running turn reaches its next step, as when the
   user types while the chat works. Not for subagents' chats, which only
   their parent prompts. Returns nil at once."
  [ctx text]
  (let [chat-id (chat-id-of ctx)
        db* (db*-of ctx)
        {:keys [messenger metrics]} ctx
        config (config ctx)
        chat (get-in @db* [:chats chat-id])]
    (if (= :running (:status chat))
      ((requiring-resolve 'eca.features.chat/prompt-steer)
       {:chat-id chat-id :message text} db* messenger config metrics)
      (shared/future* config
        ((requiring-resolve 'eca.features.chat/prompt)
         {:chat-id chat-id :message text :agent (:agent chat) :variant (:variant chat) :source :module}
         db* messenger config metrics)))
    nil))

(defn add-context!
  "Adds `text` to the context of the chat's next user request, as a shell
   hook's additionalContext: after the user's message, wrapped in
   `<additionalContext>`."
  [ctx text]
  (let [id (owner ctx)
        chat-id (chat-id-of ctx)]
    (when-not (string/blank? text)
      (swap! (db*-of ctx)
             (fn [db]
               (cond-> db
                 (get-in db [:chats chat-id])
                 (update-in [:chats chat-id :module-contexts] (fnil conj [])
                            {:module (str (symbol id)) :content (str text)})))))
    nil))

;; --- UI

(defn notify!
  "Shows `text` to the user as a notification. `:level` is `:info` (the
   default), `:warn` or `:error`."
  ([ctx text] (notify! ctx text nil))
  ([ctx text {:keys [level]}]
   (when-let [messenger (:messenger ctx)]
     (messenger/showMessage messenger {:type (case level :warn :warning :error :error :info)
                                       :message (str text)}))
   nil))

(defn progress!
  "Inside a tool handler: shows `text` as the call's progress, e.g.
   \"Fetched 3 of 10 pages\". A no-op elsewhere."
  [ctx text]
  (when-let [on-progress (:on-progress ctx)]
    (on-progress {:message (str text)}))
  nil)

(defn ui?
  "Whether dialogs (`confirm`, `select`, `input`) can ask the user: the
   client supports them and the ctx runs in a chat."
  [ctx]
  (boolean (and (:chat-id ctx)
                (:messenger ctx)
                (get-in (db ctx) [:client-capabilities :code-assistant :chat-capabilities :ask-question]))))

(defn ^:private ask!
  "Asks the user in the chat and waits for the answer: `{:answer :cancelled}`."
  [ctx question options allow-freeform?]
  (let [params (cond-> {:chatId (:chat-id ctx)
                        :question question
                        :allowFreeform allow-freeform?
                        :request-id (str (random-uuid))}
                 (seq options) (assoc :options options)
                 (:tool-call-id ctx) (assoc :toolCallId (:tool-call-id ctx)))]
    @(messenger/ask-question (:messenger ctx) params)))

(defn confirm
  "Asks the user a yes or no question in the chat and waits for the answer:
   true for yes. False when the user cancels, or without `ui?`."
  [ctx text]
  (boolean (when (ui? ctx)
             (let [{:keys [answer cancelled]} (ask! ctx (str text) [{:label "Yes"} {:label "No"}] false)]
               (and (not cancelled) (= "Yes" answer))))))

(defn select
  "Asks the user to pick one of `options` in the chat and waits: returns the
   option picked, or nil when the user cancels, or without `ui?`. An option
   is a string, or `{:label .. :description ..}`."
  [ctx {:keys [title options]}]
  (when (and (ui? ctx) (seq options))
    (let [label-of #(if (map? %) (str (:label %)) (str %))
          {:keys [answer cancelled]} (ask! ctx (str title)
                                           (mapv #(if (map? %)
                                                    (select-keys % [:label :description])
                                                    {:label (str %)})
                                                 options)
                                           false)]
      (when-not cancelled
        (some #(when (= answer (label-of %)) %) options)))))

(defn input
  "Asks the user for text in the chat and waits: returns it, or nil when the
   user cancels, or without `ui?`. `:placeholder` is shown after the title."
  [ctx {:keys [title placeholder]}]
  (when (ui? ctx)
    (let [{:keys [answer cancelled]} (ask! ctx (str title (when placeholder (str " (" placeholder ")"))) nil true)]
      (when-not (or cancelled (nil? answer))
        (str answer)))))

;; --- Events

(defn emit!
  "Runs the handlers of `event-id` for `event`, e.g. an event the module
   defines in `:defines`. Returns the changed event for a transform event,
   the first answer for a handle event, and nil for a notify event."
  [ctx event-id event]
  (let [config (config ctx)
        result (events/emit! (assoc ctx :config config) event-id event)]
    (case (:kind (get (events/definitions config) event-id))
      :transform result
      :handle result
      nil)))

;; --- Tools and commands

(defn tools
  "The tools the chat's model can call: `{:full-name :name :server
   :description :parameters}`."
  [ctx]
  (let [db (db ctx)
        chat-id (:chat-id ctx)
        agent (or (:agent ctx) (get-in db [:chats chat-id :agent]))]
    (mapv (fn [tool]
            (-> (select-keys tool [:full-name :name :description :parameters])
                (assoc :server (get-in tool [:server :name]))))
          ((requiring-resolve 'eca.features.tools/all-tools) chat-id agent db (config ctx)))))

(defn call-tool!
  "Runs the tool `full-name`, e.g. `eca__read_file`, with `args` (string
   keys) and returns its result, `{:error bool :contents [..]}`. It doesn't
   ask for approval and doesn't show in the chat: the module decides."
  [ctx full-name args]
  (let [db* (db*-of ctx)
        {:keys [messenger metrics chat-id]} ctx
        agent (or (:agent ctx) (get-in @db* [:chats chat-id :agent]))]
    ((requiring-resolve 'eca.features.tools/call-tool!)
     full-name args chat-id (str "module-" (random-uuid)) agent db* (config ctx) messenger metrics
     (constantly nil) (fn [& _] nil) {})))

(defn ^:private tools-changed!
  "Sends the clients the tools again, when ECA runs a session."
  [ctx]
  (when-let [db* (:db* ctx)]
    (when-let [messenger (:messenger ctx)]
      (try
        ((requiring-resolve 'eca.features.tools/refresh-module-tool-servers!) db* messenger (config ctx))
        (catch Exception e
          (logger/warn "[MODULES]" "Could not send the tools to the client:" e))))))

(defn ^:private check!
  "Throws when the entry named `entry-name` has problems, or its name doesn't
   match `name-re`."
  [what entry-name name-re problems]
  (let [problems (cond-> (vec problems)
                   (not (and (string? entry-name) (re-matches name-re entry-name)))
                   (conj (str "the name must be a string matching " name-re)))]
    (when (seq problems)
      (throw (ex-info (format "Invalid %s %s: %s" what (pr-str entry-name) (string/join "; " problems))
                      {:name entry-name :problems problems})))))

(defn register-tool!
  "Adds a tool while the module runs, like an entry of its `:tools`: named
   `<prefix>__<tool-name>`, removed when the module stops. Registering a name
   again replaces the tool."
  [ctx tool-name tool]
  (check! "tool" tool-name #"[A-Za-z0-9_-]+" (modules/tool-problems tool))
  (modules/register! (owner ctx) :tools tool-name tool)
  (tools-changed! ctx)
  nil)

(defn unregister-tool!
  "Removes a tool `register-tool!` added."
  [ctx tool-name]
  (modules/unregister! (owner ctx) :tools tool-name)
  (tools-changed! ctx)
  nil)

(defn register-command!
  "Adds a slash command while the module runs, like an entry of its
   `:commands`: `/<command-name>`, or `/<prefix>:<command-name>`. Removed
   when the module stops."
  [ctx command-name command]
  (check! "command" command-name #"[A-Za-z0-9_.-]+" (modules/command-problems command))
  (modules/register! (owner ctx) :commands command-name command)
  nil)

(defn unregister-command!
  "Removes a command `register-command!` added."
  [ctx command-name]
  (modules/unregister! (owner ctx) :commands command-name)
  nil)

;; --- LLM

(defn models
  "The models ECA can use: full name → capabilities, e.g.
   `{\"anthropic/claude-sonnet-4-6\" {:tools true :limit {:context 200000} ...}}`."
  [ctx]
  (:models (db ctx)))

(defn ^:private text-blocks [content]
  (if (string? content) [{:type :text :text content}] content))

(defn ^:private record-usage!
  "Adds an LLM call's usage to the module's totals: the session's, and the
   chat's when it ran in one, which `/costs` shows."
  [db* id chat-id {:keys [input-tokens output-tokens input-cache-creation-tokens input-cache-read-tokens]}
   model-capabilities]
  (let [cost (shared/tokens->raw-cost (or input-tokens 0) input-cache-creation-tokens input-cache-read-tokens
                                      (or output-tokens 0) model-capabilities)
        add (fn [totals]
              (cond-> (-> totals
                          (update :calls (fnil inc 0))
                          (update :input-tokens (fnil + 0) (or input-tokens 0))
                          (update :output-tokens (fnil + 0) (or output-tokens 0))
                          (update :input-cache-creation-tokens (fnil + 0) (or input-cache-creation-tokens 0))
                          (update :input-cache-read-tokens (fnil + 0) (or input-cache-read-tokens 0)))
                cost (update :cost (fnil + 0) (double cost))))]
    (swap! db* (fn [db]
                 (cond-> (update-in db [:module-usage id] add)
                   (and chat-id (get-in db [:chats chat-id]))
                   (update-in [:chats chat-id :module-usage id] add))))))

(defn complete!
  "One call to an LLM, with any model ECA can use, and waits for it: returns
   `{:text :usage}`, or throws when the call fails. `opts`:
   - `:model`: a full model name, e.g. \"anthropic/claude-haiku-4-5\"; the
     chat's model by default, else `defaultModel`.
   - `:instructions`: the system prompt.
   - `:messages`: the conversation, e.g. `(messages ctx)`, or
     `[{:role \"user\" :content \"..\"}]`.
   - `:prompt`: text sent as the last user message; needed when `:messages`
     doesn't end with the user's.
   - `:max-output-tokens`.
   No tools or reasoning. The call is metered: its tokens and cost count for
   the module, in `/costs` and `/modules`. It can take a while: from an event
   handler, prefer `background!`, or a longer `:timeout-ms`."
  [ctx {:keys [model instructions messages prompt max-output-tokens]}]
  (let [id (owner ctx)
        db* (db*-of ctx)
        db @db*
        config (config ctx)
        chat-id (:chat-id ctx)
        full-model (or model (get-in db [:chats chat-id :model]) (:defaultModel config))
        model-capabilities (or (get-in db [:models full-model])
                               (throw (ex-info (str "Unknown model " (pr-str full-model)) {:model full-model})))
        [provider model-name] (shared/full-model->provider+model full-model)
        messages (cond-> (mapv #(update % :content text-blocks) messages)
                   prompt (conj {:role "user" :content (text-blocks prompt)}))
        _ (when-not (= "user" (:role (peek messages)))
            (throw (ex-info "complete! needs the user's message last: end :messages with one, or give :prompt" {})))
        _ (try
            ((requiring-resolve 'eca.features.login/maybe-renew-auth-token!)
             {:provider provider
              :on-error (fn [msg] (logger/warn "[MODULES]" "Could not renew the auth of" provider msg))}
             {:db* db* :messenger (:messenger ctx) :config config :metrics (:metrics ctx)})
            (catch Exception e
              (logger/warn "[MODULES]" "Could not renew the auth of" provider (ex-message e))))
        result ((requiring-resolve 'eca.llm-api/sync-prompt!)
                {:provider provider
                 :model model-name
                 :model-capabilities (cond-> (assoc model-capabilities :tools false :web-search false :reason? false)
                                       max-output-tokens (assoc :max-output-tokens max-output-tokens))
                 :instructions instructions
                 :past-messages (pop messages)
                 :user-messages [(peek messages)]
                 :config config
                 :provider-auth (get-in @db* [:auth provider])
                 :subagent? true})]
    (when-let [error (:error result)]
      (throw (ex-info (str "The LLM call failed: " (or (:message error) (some-> (:exception error) ex-message) error))
                      {:model full-model})))
    (when-let [usage (:usage result)]
      (record-usage! db* id chat-id usage model-capabilities))
    {:text (:output-text result)
     :usage (:usage result)}))

;; --- Runtime

(defn background!
  "Runs `(f)` in the background, on the module's own threads, and returns at
   once: a future (deref it to wait for the result). With
   `{:pool :name :size n}`, in a pool of `n` threads (4 by default) with that
   name. A pool's jobs queue when its threads are busy. Jobs are interrupted
   when the module stops; an exception in one is logged and counts as a
   failure of the module."
  ([ctx f] (background! ctx {} f))
  ([ctx opts f]
   (modules/background! ctx (owner ctx) opts f)))

(defn data-dir
  "A folder for the module's files, made if needed, in the workspace's cache:
   one per module, or with `{:chat? true}` one for the chat, which is removed
   with the chat. Returns its absolute path."
  ([ctx] (data-dir ctx nil))
  ([ctx {:keys [chat?]}]
   (let [id (owner ctx)
         dir (if chat?
               (modules.data/chat-dir (db ctx) id (chat-id-of ctx))
               (modules.data/module-dir (db ctx) id))]
     (str (fs/create-dirs dir)))))

(defn shell!
  "Runs `command`, a string, in a shell (bash; PowerShell on Windows) and
   waits for it: `{:exit :out :err}`. `opts`:
   - `:cwd`: the folder to run in; the first workspace by default.
   - `:env`: extra environment variables, name → value.
   - `:input`: text for the command's stdin.
   - `:timeout-ms`: 60000 by default. After it the process is killed, and the
     result has `:timeout? true` and a nil `:exit`.
   The `:shell/before-exec` event runs first, so modules can change the
   command, its folder and its variables, e.g. to run it elsewhere."
  ([ctx command] (shell! ctx command nil))
  ([ctx command {:keys [cwd env input timeout-ms] :or {timeout-ms 60000}}]
   (let [config (config ctx)
         shell-config (get-in config [:toolCall :shellCommand])
         opts ((requiring-resolve 'eca.modules.tools.shell/before-exec)
               (assoc ctx :config config)
               (cond-> {:cwd (or cwd (first (workspaces ctx)) (System/getProperty "user.home"))
                        :script (str command)
                        :chat-id (:chat-id ctx)}
                 env (assoc :env env)
                 input (assoc :input input)
                 (:path shell-config) (assoc :shell-path (:path shell-config))
                 (:args shell-config) (assoc :shell-args (:args shell-config)))
               (shared/assoc-some {:source :module :timeout-ms timeout-ms} :module (:module-id ctx)))
         proc ((requiring-resolve 'eca.modules.tools.shell/start-shell-process!) opts)
         result (try
                  (deref proc timeout-ms ::timeout)
                  (catch InterruptedException e
                    (process/destroy-tree proc)
                    (throw e)))]
     (if (= ::timeout result)
       (do (process/destroy-tree proc)
           {:exit nil :out "" :err (str "Timed out after " timeout-ms " ms") :timeout? true})
       {:exit (:exit result) :out (str (:out result)) :err (str (:err result))}))))

;; --- Results

(defn text
  "A tool result holding the text `s`."
  [s]
  {:error false
   :contents [{:type :text :text (str s)}]})

(defn error
  "A tool result that reports an error, with the text `s`."
  [s]
  {:error true
   :contents [{:type :text :text (str s)}]})
