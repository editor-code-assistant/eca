(ns eca.modules.core
  "The `:eca/core` module: what stays in core (protocol, persistence, config,
   the chat loop and the module runtime), as a module so its config keys,
   prompt sections and commands are listed like any other module's. It can't
   be disabled."
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.features.chat.compaction :as f.chat.compaction]
   [eca.features.chat.lifecycle :as f.chat.lifecycle]
   [eca.features.prompt :as f.prompt]
   [eca.features.tools :as f.tools]
   [eca.llm-api :as llm-api]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.modules :as modules]
   [eca.modules.config-spec :as config-spec]
   [eca.modules.loader :as loader]
   [eca.secrets :as secrets]
   [eca.shared :as shared :refer [multi-str]])
  (:import
   [java.lang ProcessHandle]
   [java.time Instant ZoneId]
   [java.time.format DateTimeFormatter]))

(set! *warn-on-reflection* true)

;; /modules: list the modules, and enable, disable or reload one for the session.

(def ^:private status-icon
  {:active "✅"
   :disabled "⏸️"
   :inactive "⚠️"
   :failed "❌"
   :refused "⛔"})

(defn ^:private parse-id
  "A module id as users type it: `eca/mcp` or `:eca/mcp`."
  [s]
  (when-let [s (some-> s string/trim not-empty)]
    (let [s (string/replace-first s #"^:" "")]
      (when (string/includes? s "/")
        (keyword s)))))

(defn ^:private code-list [xs]
  (string/join ", " (map #(str "`" % "`") xs)))

(defn ^:private module-fn-value
  "What a module's `:tools` or `:commands` function `f` gives outside of a
   chat, or nil when it fails."
  [entry ctx what f]
  (let [module-ctx (modules/module-ctx ctx entry)]
    (try
      (modules/guarded-call module-ctx entry what f module-ctx)
      (catch Exception e
        (logger/warn "[modules]" (format "Could not list the %s of %s:" what (:id entry)) e)
        nil))))

(defn ^:private pairs
  "The `[name value]` pairs of a module's `:tools` or `:commands`."
  [m]
  (when (coll? m)
    (filter #(and (vector? %) (= 2 (count %))) m)))

(defn ^:private tool-names
  "The full names of a module's tools: `<prefix>__<name>`, or the name a tool
   `:replaces`. For `:tools` functions, the ones they give outside of a chat."
  [{:keys [module] :as entry} ctx]
  (let [prefix (modules/prefix entry)
        tools (:tools module)
        tools (if (modules/fn-like? tools) (module-fn-value entry ctx "tools" tools) tools)]
    (sort (for [[tool-name tool] (pairs tools)]
            (or (:replaces tool) (str prefix "__" tool-name))))))

(defn ^:private command-names
  "The names of a module's own commands. For `:commands` functions, the ones
   they give outside of a chat, without the ones it gives for MCP prompts,
   skills and custom commands (`:client-type`)."
  [{:keys [module] :as entry} ctx]
  (let [commands (:commands module)
        commands (if (modules/fn-like? commands) (module-fn-value entry ctx "commands" commands) commands)]
    (sort (distinct (for [[command-name command] (pairs commands)
                          :when (not (:client-type command))]
                      command-name)))))

(defn ^:private contributions [{:keys [module] :as entry} ctx]
  (let [{:keys [system-prompts providers apis event-handlers config-spec rpc]} module
        tools (tool-names entry ctx)
        commands (command-names entry ctx)]
    (remove nil?
            [(when (seq tools)
               (str "tools " (code-list tools)))
             (when (seq commands)
               (str "commands " (code-list (map #(str "/" %) commands))))
             (when (seq system-prompts)
               (str "prompt sections " (code-list (map :id system-prompts))))
             (when (seq providers)
               (str "providers " (code-list (sort (keys providers)))))
             (when (seq apis)
               (str "APIs " (code-list (sort (keys apis)))))
             (when (seq rpc)
               (str "protocol methods " (code-list (sort (keys rpc)))))
             (when (seq event-handlers)
               (str "handles " (code-list (sort (keys event-handlers)))))
             (when-let [paths (seq (keys (config-spec/leaves config-spec)))]
               (str "config " (code-list (sort (map config-spec/path-str paths)))))])))

(defn ^:private llm-usage-text
  "The module's LLM calls this session (`eca.api/complete!`), or nil."
  [{:keys [db]} id]
  (when-let [{:keys [calls cost]} (get-in db [:module-usage id])]
    (str calls (if (= 1 calls) " LLM call" " LLM calls")
         (when cost (str " ($" (shared/format-cost cost) ")")))))

(defn ^:private module-text [{:keys [id module status reason source built-in? priority running?
                                     warnings failures errors]
                              :as entry}
                             ctx]
  (let [{:keys [description version]} module]
    (multi-str
     ;; a module that failed to load may have no id yet
     (str (status-icon status "•") " `" (or id (str (fs/file-name source))) "`"
          (when version (str " " version))
          (when description (str " — " description)))
     (str "  " (string/join " · " (remove nil? [(if built-in? "built-in" (str "from `" source "`"))
                                                (when priority (str "priority " priority))
                                                (when running? "running")
                                                (llm-usage-text ctx id)
                                                (when (pos? (or failures 0)) (str failures " failures in a row"))])))
     (when (and reason (not= :active status))
       (str "  " (name status) ": " reason))
     (when (= :active status)
       (for [line (contributions entry ctx)]
         (str "  " line)))
     (for [warning warnings]
       (str "  warning: " warning))
     (when-let [{:keys [what message]} (last errors)]
       (str "  last error, in " what ": " message)))))

(defn ^:private modules-text
  "The `/modules` listing."
  [config db]
  (let [{:keys [active] :as by-status} (group-by #(if (= :active (:status %)) :active :other)
                                                 (modules/statuses config))]
    (multi-str
     (format "## Modules (%d active)" (count active))
     ""
     (for [entry (concat active (:other by-status))]
       (module-text entry {:config config :db db}))
     ""
     "`/modules enable <id>`, `/modules disable <id>` and `/modules reload <id>` take effect now, for this session."
     "To keep a choice, set `modules.<id>.enabled` in your config.")))

(defn ^:private known-ids [config]
  (set (map :id (modules/statuses config))))

(defn ^:private dependents-text [id before after]
  (let [stopped (sort (remove (conj after id) before))]
    (when (seq stopped)
      (str "Also inactive now: " (code-list stopped) "."))))

(defn ^:private handle!
  "Runs `/modules [enable|disable|reload <id>]`, returning the text to show."
  [args {:keys [db* messenger metrics config]}]
  (let [[action id-str] args
        components {:db* db* :messenger messenger :metrics metrics}
        fresh-config #(config/all @db*)
        id (parse-id id-str)]
    (cond
      (nil? action)
      (modules-text config @db*)

      (not (#{"enable" "disable" "reload"} action))
      (str "Unknown action `" action "`. Use `/modules`, `/modules enable <id>`, `/modules disable <id>`"
           " or `/modules reload <id>`.")

      (nil? id)
      (str "Which module? E.g. `/modules " action " eca/mcp`.")

      (not (contains? (known-ids config) id))
      (str "No module `" id "`. `/modules` lists them.")

      (and (= "disable" action) (= :eca/core id))
      "`:eca/core` can't be disabled."

      :else
      (let [before (:active-ids (modules/resolution config))]
        (case action
          "enable" (modules/set-enabled! id true)
          "disable" (modules/set-enabled! id false)
          ;; an external module is evaluated again from its source first
          "reload" (do (loader/reload! id config @db*)
                       (modules/restart! components (fresh-config) id)))
        (let [config (fresh-config)
              _ (modules/reconcile-if-started! components config)
              ;; the module may add, replace or remove tools
              _ (when messenger
                  (f.tools/refresh-module-tool-servers! db* messenger config))
              after (:active-ids (modules/resolution config))
              status (some #(when (= id (:id %)) %) (modules/statuses config))]
          (multi-str
           (case action
             "enable" (if (= :active (:status status))
                        (str "Enabled `" id "` for this session.")
                        (str "`" id "` is enabled but not active: " (:reason status)))
             "disable" (str "Disabled `" id "` for this session.")
             "reload" (if (= :active (:status status))
                        (str "Reloaded `" id "`.")
                        (str "`" id "` didn't come back: " (:reason status))))
           (dependents-text id before after)))))))

(defn ^:private active-ids-text
  "One line naming the active modules, for `/doctor`."
  [config]
  (code-list (sort (map str (:active-ids (modules/resolution config))))))

(defn ^:private value-kind [value]
  (cond
    (string? value) "a string"
    (number? value) "a number"
    (boolean? value) "a boolean"
    (map? value) "an object"
    (sequential? value) "an array"
    :else (str "a " (type value))))

(defn ^:private config-problems
  "Configured values that don't fit the type a module declares for them.
   Values aren't shown: they may be secrets."
  [config]
  (for [{:keys [path value type]} (config-spec/type-problems (modules/declared-keys config) config)]
    (format "`%s` should be %s, got %s" (config-spec/path-str path)
            (case type
              :enum "one of the allowed values"
              :map "an object"
              :array "an array"
              (str "a " (name type)))
            (value-kind value))))

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

(defn ^:private doctor-msg [db config]
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
               (str "Active modules: " (active-ids-text config))
               (when-let [unknown (seq (config/unknown-config-keys config))]
                 (config/unknown-config-keys-text unknown))
               (when-let [problems (seq (config-problems config))]
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

(defn ^:private context-usage-text
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

(defn ^:private prompt-show-text [instructions user-messages]
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
        subscription? (llm-util/subscription? provider db config)
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
                                   {:subscription? (llm-util/subscription? provider db config)})
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
  "Shows the config, with the values of the keys modules declare `:secret?` masked."
  [{:keys [chat-id config]} _args]
  (let [shown (config-spec/masked (modules/declared-keys config) config)]
    {:type :chat-messages
     :chats {chat-id {:messages [{:role "system" :content [{:type :text :text (with-out-str (pprint/pprint shown))}]}]}}}))

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
  (let [msg (handle! args chat-ctx)]
    {:type :chat-messages
     :chats {chat-id {:messages [{:role "system" :content [{:type :text :text msg}]}]}}}))

;; System prompt sections: each gets the prompt ctx, what the chat's system prompt is built from.

(defn ^:private workspace-roots-section [db]
  (when-let [roots (seq (:workspace-folders db))]
    (multi-str
     "## Workspace Roots"
     ""
     (format "<workspace-roots%s>" (f.prompt/attr-str {:description "Workspace roots used for path resolution and tool scoping."}))
     (->> roots
          (map (fn [{:keys [uri]}]
                 (format "<workspace-root%s/>"
                         (f.prompt/attr-str {:path (shared/uri->filename uri)}))))
          (string/join "\n"))
     "</workspace-roots>"
     "")))

(def ^:private path-resolution-section
  (multi-str
   "## Path Resolution"
   ""
   "Use workspace roots as the base for resolving relative paths. Tool paths must be absolute unless the tool says otherwise."))

(def ^:private additional-system-info
  (delay (slurp (io/resource "prompts/additional_system_info.md"))))

(defn ^:private additional-info-section
  "`:eca/additional-info`: OS, shell and other session facts."
  [{:keys [selmer-ctx]}]
  (shared/safe-selmer-render @additional-system-info selmer-ctx "additional-system-info"))

(defn ^:private workspace-roots-prompt-section
  "`:eca/workspace-roots`."
  [{:keys [db]}]
  (workspace-roots-section db))

(defn ^:private path-resolution-prompt-section
  "`:eca/path-resolution`."
  [_ctx]
  path-resolution-section)

(defn ^:private context-section
  "`:eca/context`: AGENTS.md files, attached stable contexts and the chat's
   startup context."
  [{:keys [refined-contexts chat-id db]}]
  (let [stable-contexts (filter f.prompt/static-prompt-context? refined-contexts)
        startup-ctx (get-in db [:chats chat-id :startup-context])]
    (when (or (seq stable-contexts) (not (string/blank? startup-ctx)))
      [""
       "## Context"
       ""
       (f.prompt/contexts-str stable-contexts startup-ctx)])))

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
   :system-prompts [{:id :eca/additional-info :order 500 :content #'additional-info-section}
                    {:id :eca/workspace-roots :order 600 :content #'workspace-roots-prompt-section}
                    {:id :eca/path-resolution :order 700 :content #'path-resolution-prompt-section}
                    {:id :eca/context :order 800 :content #'context-section}]  ; AGENTS.md, startup context
   :commands (commands
              {"compact" #'compact-command "model" #'model-command "costs" #'costs-command "sync-system-prompt" #'sync-system-prompt-command "context" #'context-command "remote" #'remote-command "config" #'config-command "doctor" #'doctor-command "prompt-show" #'prompt-show-command "modules" #'modules-command}
              [["model" "Select model for current chat (Ex: /model anthropic/claude-sonnet-4-6)" [{:name "full-model"}]]
               ["costs" "Total costs of the current chat session." []]
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
