(ns eca.modules.core
  "The `:eca/core` module: what stays in core (protocol, persistence, config,
   the chat loop and the module runtime), as a module so its config keys,
   events and commands are listed like any other module's. It can't be
   disabled."
  (:require
   [clojure.pprint :as pprint]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.features.chat.compaction :as f.chat.compaction]
   [eca.features.chat.lifecycle :as f.chat.lifecycle]
   [eca.features.login :as f.login]
   [eca.features.modules :as f.modules]
   [eca.features.prompt :as f.prompt]
   [eca.features.providers :as f.providers]
   [eca.features.quota :as f.quota]
   [eca.llm-api :as llm-api]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.modules.events :as events]
   [eca.secrets :as secrets]
   [eca.shared :as shared :refer [multi-str]])
  (:import
   [java.lang ProcessHandle]
   [java.time Instant ZoneId]
   [java.time.format DateTimeFormatter]))

(set! *warn-on-reflection* true)

(defn module-usage-lines
  "`/costs` lines for the LLM calls modules made (`eca.api/complete!`), by
   module, or nil when there were none."
  [module-usage]
  (when (seq module-usage)
    (cons "\nModules' own LLM calls, not in the totals above:"
          (for [[id {:keys [calls input-tokens output-tokens cost]}] (sort-by (comp str key) module-usage)]
            (str "- `" (symbol id) "`: " calls (if (= 1 calls) " call, " " calls, ")
                 input-tokens " input tokens, " output-tokens " output tokens"
                 (when cost (str ", $" (shared/format-cost cost))))))))

(defn ^:private format-expires-at [^long expires-at]
  (let [instant (Instant/ofEpochSecond expires-at)
        formatter (.withZone (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm") (ZoneId/systemDefault))]
    (.format formatter instant)))

(defn ^:private format-auth-type [provider provider-auth config]
  (let [[auth-type api-key] (llm-util/provider-api-key provider provider-auth config)
        base (case auth-type
               :auth/oauth "Subscription (oauth)"
               :auth/token (str "API key (" (shared/obfuscate api-key) ")")
               "Unknown")]
    (if-let [expires-at (:expires-at provider-auth)]
      (str base ", expires " (format-expires-at expires-at))
      base)))

(defn doctor-msg [db config]
  (let [model (llm-api/default-model db config)
        [model-provider] (when model (shared/full-model->provider+model model))
        model-auth-type (when model-provider
                          (format-auth-type model-provider
                                            (get-in db [:auth model-provider])
                                            config))
        active-providers (filter (fn [[_ auth]] (seq auth)) (:auth db))
        cred-check (secrets/check-credential-files (:netrcFile config))
        existing-files (filter :exists (:files cred-check))]
    (multi-str (str "ECA version: " (config/eca-version))
               ""
               (str "Server cmd: " (.orElse (.commandLine (.info (ProcessHandle/current))) nil))
               ""
               (str "Workspaces: " (shared/workspaces-as-str db))
               ""
               (str "Default model: " model
                    (when model-auth-type (str " (" model-auth-type ")")))
               ""
               (if (seq active-providers)
                 (str "Logged providers: " (reduce
                                            (fn [s [provider auth]]
                                              (str s provider ": " (format-auth-type provider auth config) "\n"))
                                            "\n"
                                            active-providers))
                 "Logged providers: None")
               (str "Relevant env vars: " (reduce (fn [s [key val]]
                                                    (cond
                                                      (or (string/includes? key "KEY")
                                                          (string/includes? key "TOKEN"))
                                                      (str s key "=" (shared/obfuscate val) "\n")

                                                      (or (string/includes? key "API")
                                                          (string/includes? key "URL")
                                                          (string/includes? key "BASE"))
                                                      (str s key "=" val "\n")

                                                      :else s))
                                                  "\n"
                                                  (System/getenv)))
               ""
               (if (seq existing-files)
                 (str "Credential files:"
                      (reduce
                       (fn [s file-info]
                         (str s "\n  " (:path file-info) ":"
                              (when (contains? file-info :readable)
                                (str "\n    Readable: " (:readable file-info)))
                              (when (contains? file-info :permissions-secure)
                                (str "\n    Permissions: " (if (:permissions-secure file-info) "secure" "INSECURE (should be 0600)")))
                              (when (:credentials-count file-info)
                                (str "\n    Credentials: " (:credentials-count file-info)))
                              (when (:parse-error file-info)
                                (str "\n    Parse error: " (:parse-error file-info)))
                              (when (:suggestion file-info)
                                (str "\n    " (:suggestion file-info)))))
                       ""
                       existing-files))
                 "Credential files: None found")
               ""
               (str "Active modules: " (f.modules/active-ids-text config))
               (when-let [unknown (seq (f.modules/unknown-config-keys config))]
                 (f.modules/unknown-config-keys-text unknown))
               (when-let [problems (seq (f.modules/config-problems config))]
                 (str "Config problems:\n" (string/join "\n" (map #(str "  " %) problems)))))))

(defn ^:private fmt-tokens
  "Formats a token count compactly (e.g. 5300 -> \"5.3k\")."
  ^String [n]
  (let [n (long n)]
    (if (>= n 1000)
      (format "%.1fk" (/ n 1000.0))
      (str n))))

(defn ^:private fmt-pct [n limit]
  (if (and limit (pos? (long limit)))
    (format "%.1f%%" (* 100.0 (/ (double n) (double limit))))
    "-"))

(def ^:private context-instructions-categories
  "Category names grouped under the legend's Instructions section; the rest
   (Tool calls, Conversation) go under Chat."
  #{"System prompt" "Rules" "Skills" "AGENTS.md" "Tool definitions"})

(def ^:private context-compact-emoji
  "Grid swatch marking the cell where auto-compaction triggers."
  "🔲")

(defn ^:private context-grid-rows
  "Builds a COLS-wide proportional emoji grid (ROWS rows) for the breakdown:
   category emoji proportional to the context window, then free emoji to fill.
   COMPACT-PERCENTAGE, when set, marks the auto-compaction cell with
   `context-compact-emoji'."
  [categories free-emoji context-limit cols rows compact-percentage]
  (let [total-cells (* cols rows)
        window (max 1 (long context-limit))
        counts (mapv (fn [{:keys [emoji tokens]}]
                       [emoji (max 0 (long (Math/round (* (double total-cells)
                                                          (/ (double tokens) window)))))])
                     categories)
        used (reduce + 0 (map second counts))
        cells (vec (concat (mapcat (fn [[e n]] (repeat n e)) counts)
                           (repeat (max 0 (- total-cells used)) free-emoji)))
        cells (cond
                (> (count cells) total-cells) (subvec cells 0 total-cells)
                (< (count cells) total-cells) (into cells (repeat (- total-cells (count cells)) free-emoji))
                :else cells)
        cells (if compact-percentage
                (let [idx (min (dec total-cells)
                               (max 0 (long (Math/round (* (double total-cells)
                                                           (/ (double compact-percentage) 100.0))))))]
                  (assoc cells idx context-compact-emoji))
                cells)]
    (mapv #(apply str %) (partition cols cells))))

(defn context-usage-text
  "Renders a human-readable context-window usage breakdown: a fixed 10x10
   proportional emoji grid to the left of a two-section legend (Instructions and
   Chat) plus the free-space row. COMPACT-PERCENTAGE, when set, adds a line
   stating at what point the conversation is auto-compacted."
  [model {:keys [categories used-tokens free-tokens context-limit free-emoji]} compact-percentage]
  (let [all-rows (vec (concat categories
                              (when free-tokens [{:name "Free space" :tokens free-tokens :emoji free-emoji}])))
        label-w (reduce max 0 (map #(count (:name %)) all-rows))
        fmt-cat (fn [indent {:keys [name tokens emoji]}]
                  (format "%s%s %s%s  %s tokens (%s)"
                          indent
                          (or emoji " ")
                          name
                          (apply str (repeat (- label-w (count name)) \space))
                          (fmt-tokens tokens)
                          (fmt-pct tokens context-limit)))
        header (if context-limit
                 (format "%s  %s/%s tokens (%s used)"
                         model (fmt-tokens used-tokens) (fmt-tokens context-limit)
                         (fmt-pct used-tokens context-limit))
                 (format "%s  %s tokens" model (fmt-tokens used-tokens)))
        compact-line (when (and compact-percentage context-limit (pos? (long context-limit)))
                       (let [pct (double compact-percentage)
                             pct-str (if (== pct (Math/rint pct))
                                       (str (long pct))
                                       (format "%.1f" pct))]
                         (format "%s Auto-compaction at %s%% (~%s tokens)"
                                 context-compact-emoji
                                 pct-str
                                 (fmt-tokens (Math/round (* (double context-limit) (/ pct 100.0)))))))]
    (if (and context-limit (pos? (long context-limit)))
      (let [instr (filterv #(contains? context-instructions-categories (:name %)) categories)
            chat (filterv #(not (contains? context-instructions-categories (:name %))) categories)
            legend (vec (concat
                         (when (seq instr) (cons "Instructions:" (mapv #(fmt-cat "  " %) instr)))
                         (when (seq chat) (cons "Chat:" (mapv #(fmt-cat "  " %) chat)))
                         (when free-tokens [(fmt-cat "" {:name "Free space" :tokens free-tokens :emoji free-emoji})])))
            grid (context-grid-rows categories free-emoji context-limit 10 10 compact-percentage)
            zipped (map-indexed (fn [i row]
                                  (string/trimr (str row "  " (nth legend i ""))))
                                grid)]
        (multi-str "Context Usage" "" header compact-line "" "Estimated usage" "" zipped))
      (multi-str "Context Usage" header "" (mapv #(fmt-cat "" %) all-rows)))))

(defn ^:private prompt-show-section [title body]
  (when-not (string/blank? body)
    (multi-str
     "────────────────────────────────────────"
     (str "# " title)
     "────────────────────────────────────────"
     (string/trim body))))

(defn ^:private prompt-show-user-messages-body [user-messages]
  (->> (mapcat :content user-messages)
       (map-indexed (fn [idx content]
                      (when-let [text (shared/not-blank
                                       (cond
                                         (some? (:text content))    (:text content)
                                         (= :image (:type content)) (format "[image: media-type=%s, base64 omitted (%s chars)]"
                                                                            (:media-type content)
                                                                            (count (or (:base64 content) "")))
                                         :else                      (pr-str (dissoc content :base64))))]
                        (if (and (zero? idx) text)
                          (string/replace-first text #"^/prompt-show(?:\s+|$)" "")
                          text))))
       (remove string/blank?)
       (string/join "\n\n")))

(defn prompt-show-text [instructions user-messages]
  (let [{:keys [static dynamic]} (if (map? instructions)
                                   instructions
                                   {:static instructions :dynamic nil})
        system-prompt (->> [static dynamic]
                           (remove string/blank?)
                           (string/join "\n\n"))
        sections (remove nil?
                         [(prompt-show-section "Instructions (System prompt)" system-prompt)
                          (prompt-show-section "Chat (User prompt)" (prompt-show-user-messages-body user-messages))])]
    (multi-str
     (string/join "\n\n" sections)
     ""
     "_Tool schemas are sent separately and are not included in this text dump._")))

(defn ^:private compact-command
  [{:keys [chat-id db* config all-tools] :as chat-ctx} {args :argv}]
  (let [custom-instructions (string/join " " args)
        no-compaction {:type :chat-messages
                       :chats {chat-id {:messages [{:role "system"
                                                    :content [{:type :text
                                                               :text "No compaction module handled this chat."}]}]}}}
        send-stopped! (fn [{:keys [ended-by stop-reason]}]
                        (f.chat.lifecycle/send-ended! chat-ctx "Turn stopped" ended-by stop-reason))
        {:keys [blocked? reason stop-turn? ended-by instructions]}
        (when (f.chat.compaction/compaction-handled? config)
          (f.chat.compaction/run-pre-compact! chat-ctx "manual" custom-instructions))
        answer (when (and (f.chat.compaction/compaction-handled? config) (not blocked?))
                 (f.chat.compaction/compact-with-module! chat-ctx "manual" instructions))]
    (cond
                    ;; only modules compact
      (not (f.chat.compaction/compaction-handled? config))
      no-compaction

      blocked?
      (do
                      ;; continue:false stops the turn; exit 2 blocks compaction only.
        (if stop-turn?
          (send-stopped! {:ended-by ended-by :stop-reason reason})
          (f.chat.lifecycle/send-ended! chat-ctx "Compaction blocked" ended-by reason))
        {:type :new-chat-status :status :idle})

                    ;; the module compacts with a request of the chat
      (:request answer)
      (let [{:keys [prompt done? apply!]} (:request answer)
            compact-prompt (prompt instructions all-tools)]
        (swap! db* assoc-in [:chats chat-id :compacting?] true)
        {:type :send-prompt
                       ;; a compaction's prompt, not a user request for modules
         :compaction? true
         :compaction-done? done?
         :on-finished-side-effect #(let [{:keys [compacted? stop-turn?] :as result}
                                         (f.chat.compaction/complete-compaction! chat-ctx "manual" apply!)]
                                     (when stop-turn?
                                       (send-stopped! result))
                                     {:stop-after-finish? (or stop-turn? (not compacted?))})
         :prompt compact-prompt})

      answer
      (let [{:keys [stop-turn?] :as result} (f.chat.compaction/finish-module-compaction! chat-ctx "manual" answer)]
        (when stop-turn?
          (send-stopped! result))
        {:type :new-chat-status :status :idle})

      :else
      no-compaction)))

(defn ^:private login-command
  [{:keys [chat-id db* config messenger metrics]} {args :argv}]
  (messenger/chat-content-received
   messenger
   {:chat-id chat-id
    :role "system"
    :content {:type :text
              :text (multi-str
                     "Login started, type 'cancel' anytime to exit login."
                     ""
                     "Note: Login should be used to authenticate via oauth (subs) on some providers or auto configure ECA config with a provider API key.")}})
  (f.login/handle-step {:message (or (first args) "")
                        :chat-id chat-id}
                       db*
                       messenger
                       config
                       metrics)
  {:type :new-chat-status
   :status :login})

(defn ^:private model-command
  [{:keys [chat-id db* messenger full-model]} {args :argv}]
  (let [db @db*
        selected-model (first args)
        available-models (sort (keys (:models db)))
        chat-message (fn [text]
                       {:type :chat-messages
                        :chats {chat-id {:messages [{:role "system"
                                                     :content [{:type :text
                                                                :text text}]}]}}})]
    (cond
      (string/blank? selected-model)
      (if (seq available-models)
        (chat-message
         (multi-str (str "Current model: `" full-model "`")
                    ""
                    "Available models:"
                    (string/join "\n" (map #(str "- `" % "`") available-models))
                    ""
                    "Run `/model <provider/model>` to switch chat model."))
        (chat-message
         (multi-str "No models available."
                    ""
                    "Sync models or login first, for example `/login anthropic`.")))

      (not (contains? (:models db) selected-model))
      (chat-message
       (multi-str (str "Unknown model: `" selected-model "`")
                  ""
                  (when (seq available-models)
                    (str "Available models:\n"
                         (string/join "\n" (map #(str "- `" % "`") available-models))))))

      :else
      (do
        (swap! db* update-in [:chats chat-id] assoc :model selected-model :variant nil)
        (config/notify-fields-changed-only!
         {:chat {:select-model selected-model
                 :variants []
                 :select-variant nil}}
         messenger
         db*
         chat-id)
        (chat-message
         (multi-str (str "Selected model: `" selected-model "`")
                    "Using model defaults."))))))

(defn ^:private costs-command
  [{:keys [db* chat-id config provider full-model]} _args]
  (let [db @db*
        total-input-tokens (get-in db [:chats chat-id :usage :total-input-tokens] 0)
        total-input-cache-creation-tokens (get-in db [:chats chat-id :usage :total-input-cache-creation-tokens] nil)
        total-input-cache-read-tokens (get-in db [:chats chat-id :usage :total-input-cache-read-tokens] nil)
        total-output-tokens (get-in db [:chats chat-id :usage :total-output-tokens] 0)
        model-capabilities (get-in db [:models full-model])
        subscription? (f.quota/subscription? provider db config)
        text (multi-str (str "Total input tokens: " total-input-tokens)
                        (when total-input-cache-creation-tokens
                          (str "Total input cache creation tokens: " total-input-cache-creation-tokens))
                        (when total-input-cache-read-tokens
                          (str "Total input cache read tokens: " total-input-cache-read-tokens))
                        (str "Total output tokens: " total-output-tokens)
                        (str "Total cost: $" (shared/tokens->cost total-input-tokens total-input-cache-creation-tokens total-input-cache-read-tokens total-output-tokens model-capabilities)
                             (when subscription?
                               " (API pricing estimate, subscription logins are not billed per token, see /quota)"))
                        (module-usage-lines (get-in db [:chats chat-id :module-usage])))]
    {:type :chat-messages
     :chats {chat-id {:messages [{:role "system" :content [{:type :text :text text}]}]}}}))

(defn ^:private quota-command
  [{:keys [chat-id db* config messenger] :as chat-ctx} _args]
  (let [db @db*
        providers (f.quota/subscription-providers db config)]
    (doseq [provider providers]
      (f.login/maybe-renew-auth-token!
       {:provider provider
        :on-error (fn [msg]
                    (logger/warn "[commands]" (format "Could not renew '%s' auth token before fetching quota: %s" provider msg)))}
       chat-ctx)
      (f.providers/refresh-quota! provider db* config messenger :force? true))
    {:type :chat-messages
     :chats {chat-id {:messages [{:role "system"
                                  :content [{:type :text
                                             :text (f.quota/quota-text @db* providers)}]}]}}}))

(defn ^:private sync-system-prompt-command
  [{:keys [chat-id db*]} _args]
  (swap! db* update-in [:chats chat-id] dissoc :prompt-cache)
  {:type :chat-messages
   :chats {chat-id {:messages [{:role "system"
                                :content [{:type :text
                                           :text "System prompt will be re-synced on the next message (prompt cache invalidated)."}]}]}}})

(defn ^:private context-command
  [{:keys [db* chat-id config provider full-model all-tools instructions] :as chat-ctx} _args]
  (let [db @db*
        messages (get-in db [:chats chat-id :messages] [])
        usage (shared/usage-sumary chat-id full-model db
                                   {:subscription? (f.quota/subscription? provider db config)})
        context-limit (get-in db [:models full-model :limit :context])
        compact-percentage (f.chat.lifecycle/usage-updated! chat-ctx usage context-limit)
        breakdown (shared/context-breakdown
                   {:system-prompt (f.prompt/instructions->str instructions)
                    :tools all-tools
                    :messages messages
                    :context-limit context-limit
                    :session-tokens (:session-tokens usage)})]
                  ;; Refresh the client's live usage payload so the header-line
                  ;; context bar reflects the latest breakdown immediately.
    (f.chat.lifecycle/send-content! chat-ctx :system
                                    (merge {:type :usage} usage {:context-breakdown breakdown}
                                           (when compact-percentage
                                             {:auto-compact-percentage compact-percentage})))
    {:type :chat-messages
     :chats {chat-id {:messages [{:role "system"
                                  :content [{:type :text
                                             :text (context-usage-text full-model breakdown compact-percentage)}]}]}}}))

(defn ^:private remote-command
  [{:keys [db* chat-id]} _args]
  (let [db @db*
        connect-url (:remote-connect-url db)
        host (:remote-host db)
        token (:remote-token db)
        private? (:remote-private-host? db)
        text (cond
               (not connect-url)
               (str "Remote server is not enabled. Set `remote.enabled: true` in your ECA config to start it, "
                    "or turn on its module for this session: `/modules enable eca/remote`.")

               private?
               (multi-str "## 🌐 Remote Server"
                          ""
                          (str "**Host:** `" host "`")
                          (str "**Password:** `" token "`")
                          (str "**URL:** " connect-url)
                          ""
                          "This is a private/LAN address. You need to configure your client to connect directly."
                          ""
                          "📖 [Setup guide](https://eca.dev/config/remote)")

               :else
               (multi-str "## 🌐 Remote Server"
                          ""
                          (str "**URL:** " connect-url)
                          (str "**Password:** `" token "`")
                          ""
                          "📖 [Setup guide](https://eca.dev/config/remote)"))]
    {:type :chat-messages
     :chats {chat-id {:messages [{:role "system" :content [{:type :text :text text}]}]}}}))

(defn ^:private config-command
  [{:keys [chat-id config]} _args]
  {:type :chat-messages
   :chats {chat-id {:messages [{:role "system" :content [{:type :text :text (with-out-str (pprint/pprint config))}]}]}}})

(defn ^:private doctor-command
  [{:keys [db* chat-id config]} _args]
  (let [db @db*]
    {:type :chat-messages
     :chats {chat-id {:messages [{:role "system" :content [{:type :text :text (doctor-msg db config)}]}]}}}))

(defn ^:private prompt-show-command
  [{:keys [chat-id instructions user-messages]} _args]
  {:type :chat-messages
   :chats {chat-id {:messages [{:role "system"
                                :content [{:type :text
                                           :text (prompt-show-text instructions user-messages)}]}]}}})

(defn ^:private modules-command
  [{:keys [chat-id] :as chat-ctx} {args :argv}]
  (let [msg (f.modules/handle! args chat-ctx)]
    {:type :chat-messages
     :chats {chat-id {:messages [{:role "system" :content [{:type :text :text msg}]}]}}}))

(defn ^:private commands
  "The module's commands, `{name {:description :arguments :handler}}`, from
   `[name description arguments]` entries and the handlers by name."
  [handlers entries]
  (into {} (for [[name description arguments] entries]
             [name {:description description :arguments arguments :handler (handlers name)}])))

(def module
  {:id :eca/core
   :description "Chats, config, models and the module runtime."
   :config-spec {:modules {:type :map
                           :doc "Per module id: enabled, source and priority."}
                 :disabledTools {:type :array :default []}
                 :toolCall {:outputTruncation {:type :map :default {:lines 2000 :sizeKb 50}}}}
   :defines {:events events/core-events}
   :system-prompts [{:id :eca/additional-info :content #'f.prompt/additional-info-section}
                    {:id :eca/workspace-roots :content #'f.prompt/workspace-roots-prompt-section}
                    {:id :eca/path-resolution :content #'f.prompt/path-resolution-prompt-section}
                    {:id :eca/context :content #'f.prompt/context-section}]  ; AGENTS.md, startup context
   :commands (commands
              {"compact" #'compact-command "login" #'login-command "model" #'model-command "costs" #'costs-command "quota" #'quota-command "sync-system-prompt" #'sync-system-prompt-command "context" #'context-command "remote" #'remote-command "config" #'config-command "doctor" #'doctor-command "prompt-show" #'prompt-show-command "modules" #'modules-command}
              [["login" "Log into a provider (Ex: /login github-copilot)" [{:name "provider-id"}]]
               ["model" "Select model for current chat (Ex: /model anthropic/claude-sonnet-4-6)" [{:name "full-model"}]]
               ["costs" "Total costs of the current chat session." []]
               ["quota" "Subscription quota usage and reset times (Claude Pro/Max, ChatGPT, GitHub Copilot)." []]
               ["context" "Context window usage breakdown for the current chat." []]
               ["compact" "Summarize the chat so far cleaning previous chat history to reduce context."
                [{:name "additional-input"}]]
               ["remote" "Show remote server connection details." []]
               ["config" "Show ECA config for troubleshooting." []]
               ["doctor" "Check ECA details for troubleshooting." []]
               ["prompt-show" "Prompt sent to LLM as system instructions." [{:name "optional-prompt"}]]
               ["sync-system-prompt" (str "Re-sync the system prompt (AGENTS.md, rules, skills) on the next message, "
                                          "invalidating the chat prompt cache.")
                []]
               ["modules" "List modules; enable, disable or reload one (Ex: /modules disable eca/mcp)."
                [{:name "action"} {:name "module-id"}]]])})
