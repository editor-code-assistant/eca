(ns eca.features.commands
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.index :as f.index]
   [eca.features.login :as f.login]
   [eca.features.plugins :as f.plugins]
   [eca.features.prompt :as f.prompt]
   [eca.features.rules :as f.rules]
   [eca.features.skills :as f.skills]
   [eca.features.tools.mcp :as f.mcp]
   [eca.features.tools.util :as tools.util]
   [eca.interpolation :as interpolation]
   [eca.llm-api :as llm-api]
   [eca.llm-util :as llm-util]
   [eca.messenger :as messenger]
   [eca.secrets :as secrets]
   [eca.shared :as shared :refer [multi-str]])
  (:import
   [clojure.lang PersistentVector]
   [java.lang ProcessHandle]
   [java.time Instant ZoneId]
   [java.time.format DateTimeFormatter]))

(set! *warn-on-reflection* true)

(defn fork-title [title]
  (let [title (or title "Untitled")]
    (if-let [[_ base n] (re-matches #"(.+?)\s*\((\d+)\)\s*$" title)]
      (str base " (" (inc (parse-long n)) ")")
      (str title " (2)"))))

(defn ^:private normalize-command-name [f]
  (string/lower-case (fs/strip-ext (fs/file-name f))))

(defn ^:private markdown-file? [file]
  (and (not (fs/directory? file))
       (string/ends-with? (string/lower-case (str file)) ".md")))

(defn ^:private configured-command-files [path]
  (cond
    (not (fs/exists? path)) []
    (fs/directory? path) (filter markdown-file? (fs/glob path "**" {:follow-links true}))
    :else [path]))

(defn ^:private command-file->command [type file opts]
  (let [base (normalize-command-name file)
        content (interpolation/replace-dynamic-strings (slurp (str file)) (str (fs/parent file)) nil)]
    (cond-> {:name (if-let [plugin (:plugin opts)]
                     (shared/prefixed-name plugin base)
                     base)
             :path (str (fs/canonicalize file))
             :type type
             :content content
             :arguments (shared/extract-args-from-content content)}
      (:plugin opts) (assoc :plugin (:plugin opts)))))

(defn ^:private global-file-commands []
  (let [xdg-config-home (or (config/get-env "XDG_CONFIG_HOME")
                            (io/file (config/get-property "user.home") ".config"))
        commands-dir (io/file xdg-config-home "eca" "commands")]
    (map #(command-file->command :user-global-file % {})
         (configured-command-files commands-dir))))

(defn ^:private local-file-commands [roots]
  (->> roots
       (mapcat (fn [{:keys [uri]}]
                 (configured-command-files (fs/file (shared/uri->filename uri) ".eca" "commands"))))
       (map #(command-file->command :user-local-file % {}))))

(defn ^:private config-commands [config roots]
  (->> (get config :commands)
       (mapcat
        (fn [{:keys [path plugin]}]
          (let [path (str (fs/expand-home path))
                opts (cond-> {}
                       plugin (assoc :plugin plugin))]
            (->> (if (fs/absolute? path)
                   (configured-command-files path)
                   (mapcat (fn [{:keys [uri]}]
                             (configured-command-files (fs/file (shared/uri->filename uri) path)))
                           roots))
                 (map #(command-file->command :user-config % opts))))))))

(defn ^:private custom-commands [config roots]
  (concat (config-commands config roots)
          (when-not (:pureConfig config)
            (global-file-commands))
          (local-file-commands roots)))

(defn all-commands [db config]
  (let [mcp-prompts (->> (f.mcp/all-prompts db)
                         (mapv #(-> %
                                    (assoc :name (str (:server %) ":" (:name %))
                                           :type :mcpPrompt)
                                    (dissoc :server))))
        eca-commands [{:name "init"
                       :type :native
                       :description "Create/update the AGENTS.md file teaching LLM about the project"
                       :arguments []}
                      {:name "login"
                       :type :native
                       :description "Log into a provider (Ex: /login gitub-copilot)"
                       :arguments [{:name "provider-id"}]}
                      {:name "model"
                       :type :native
                       :description "Select model for current chat (Ex: /model anthropic/claude-sonnet-4-6)"
                       :arguments [{:name "full-model"}]}
                      {:name "skills"
                       :type :native
                       :description "List available skills"
                       :arguments []}
                      {:name "skill-create"
                       :type :native
                       :description "Create a skill considering a user request"
                       :arguments [{:name "name" :description "The skill name" :required true}
                                   {:name "prompt" :description "What to consider as this skill content" :required true}]}
                      {:name "costs"
                       :type :native
                       :description "Total costs of the current chat session."
                       :arguments []}
                      {:name "compact"
                       :type :native
                       :description "Summarize the chat so far cleaning previous chat history to reduce context."
                       :arguments [{:name "additional-input"}]}
                      {:name "fork"
                       :type :native
                       :description "Fork current chat into a new chat with the same history and settings."
                       :arguments []}
                      {:name "resume"
                       :type :native
                       :description "Resume the specified chat-id. Blank to list chats or 'latest'."
                       :arguments [{:name "chat-id"}]}
                      {:name "remote"
                       :type :native
                       :description "Show remote server connection details."
                       :arguments []}
                      {:name "config"
                       :type :native
                       :description "Show ECA config for troubleshooting."
                       :arguments []}
                      {:name "doctor"
                       :type :native
                       :description "Check ECA details for troubleshooting."
                       :arguments []}
                      {:name "repo-map-show"
                       :type :native
                       :description "Actual repoMap of current session."
                       :arguments []}
                      {:name "rules"
                       :type :native
                       :description "List available rules and their filtering metadata (agent, model, paths)."
                       :arguments []}
                      {:name "prompt-show"
                       :type :native
                       :description "Prompt sent to LLM as system instructions."
                       :arguments [{:name "optional-prompt"}]}
                      {:name "subagents"
                       :type :native
                       :description "List available subagents and their configuration."
                       :arguments []}
                      {:name "plugins"
                       :type :native
                       :description "List available plugins from configured marketplaces."
                       :arguments []}
                      {:name "plugin-install"
                       :type :native
                       :description "Install a plugin (e.g. /plugin-install my-plugin or /plugin-install my-plugin@marketplace)"
                       :arguments [{:name "plugin" :description "Plugin name or plugin@marketplace" :required true}]}
                      {:name "plugin-uninstall"
                       :type :native
                       :description "Uninstall a plugin (e.g. /plugin-uninstall my-plugin)"
                       :arguments [{:name "plugin" :description "Plugin name" :required true}]}]
        custom-cmds (map (fn [custom]
                           {:name (:name custom)
                            :type :custom-prompt
                            :description (:path custom)
                            :arguments (:arguments custom)})
                         (custom-commands config (:workspace-folders db)))
        skills-cmds (->> (f.skills/all config (:workspace-folders db))
                         (mapv (fn [skill]
                                 {:name (:name skill)
                                  :type :skill
                                  :description (:description skill)
                                  :arguments (:arguments skill)})))]
    (concat mcp-prompts
            eca-commands
            skills-cmds
            custom-cmds)))

(defn ^:private substitute-args [content args]
  (let [args-joined (string/join " " args)
        content-with-args (-> content
                              (string/replace "$ARGS" args-joined)
                              (string/replace "$ARGUMENTS" args-joined))]
    (reduce (fn [c [i arg]]
              (-> c
                  (string/replace (str "$ARG" (inc i)) arg)
                  (string/replace (str "$" (inc i)) arg)))
            content-with-args
            (map-indexed vector args))))

(defn ^:private get-custom-command [command args custom-cmds]
  (when-let [raw-content (:content (first (filter #(= command (:name %))
                                                  custom-cmds)))]
    (substitute-args raw-content args)))

(defn ^:private format-tool-permissions [{:keys [toolCall]}]
  (when-let [approval (:approval toolCall)]
    (let [by-default (:byDefault approval)
          allow-tools (keys (:allow approval))
          deny-tools (keys (:deny approval))
          ask-tools (keys (:ask approval))
          parts (cond-> []
                  by-default (conj (str "  Default: " by-default))
                  (seq allow-tools) (conj (str "  Allow: " (string/join ", " (sort allow-tools))))
                  (seq ask-tools) (conj (str "  Ask: " (string/join ", " (sort ask-tools))))
                  (seq deny-tools) (conj (str "  Deny: " (string/join ", " (sort deny-tools)))))]
      (when (seq parts)
        (string/join "\n" parts)))))

(defn ^:private subagents-msg [config]
  (let [subagents (->> (:agent config)
                       (filter (fn [[_ v]] (= "subagent" (:mode v))))
                       (sort-by first))]
    (if (empty? subagents)
      "No subagents configured, double check your configuration via json or markdown."
      (reduce
       (fn [s [agent-name agent-config]]
         (let [desc (:description agent-config)
               model (:defaultModel agent-config)
               steps (:maxSteps agent-config)
               permissions (format-tool-permissions agent-config)]
           (str s "- **" agent-name "**"
                (when desc (str ": " desc))
                "\n"
                (when model (str "  Model: " model "\n"))
                (when steps (str "  Max steps: " steps "\n"))
                (when permissions (str "  Tool permissions:\n" permissions "\n"))
                "\n")))
       "Subagents available:\n\n"
       subagents))))

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
                 "Credential files: None found"))))

(defn ^:private rules-msg [config roots agent full-model all-tools]
  (let [{static :static path-scoped :path-scoped} (f.rules/all-rules config roots agent full-model)
        visible-path-scoped (when (tools.util/tool-available? all-tools "eca__fetch_rule")
                              path-scoped)
        format-content-preview (fn [content]
                                 (let [content (or content "")
                                       preview (subs content 0 (min 120 (count content)))]
                                   (str preview (when (> (count content) 120) "..."))))
        format-rule (fn [{rule-name :name :keys [scope path agents models paths content]} include-content?]
                      (str "### " rule-name " (" (some-> scope name) ")\n"
                           "Source: " path "\n"
                           "Agent filter: " (if agents (string/join ", " agents) "all") "\n"
                           "Model filter: " (if models (string/join ", " models) "all") "\n"
                           (when paths
                             (str "Path filter: " (string/join ", " paths) "\n"))
                           (when include-content?
                             (str "Content preview: " (format-content-preview content) "\n"))
                           "\n"))]
    (if (or (seq static) (seq visible-path-scoped))
      (str
       (when (seq static)
         (str "Static rules (full content is included directly in the system prompt):\n\n"
              (reduce (fn [s rule] (str s (format-rule rule true))) "" static)))
       (when (seq visible-path-scoped)
         (str "Path-scoped rules (only a catalog is included in the system prompt; load full content with fetch_rule using the rule id and target path):\n\n"
              (reduce (fn [s rule] (str s (format-rule rule false))) "" visible-path-scoped))))
      "No rules available for the current agent and model.")))

(defn handle-command! [command args {:keys [chat-id db* config messenger full-model agent all-tools instructions user-messages metrics] :as chat-ctx}]
  (let [db @db*
        custom-cmds (custom-commands config (:workspace-folders db))
        skills (f.skills/all config (:workspace-folders db))]
    (case command
      "init" {:type :send-prompt
              :on-finished-side-effect (fn []
                                         (swap! db* assoc-in [:chats chat-id :messages] []))
              :prompt (f.prompt/init-prompt all-tools agent db config)}
      "compact" (do
                  (swap! db* assoc-in [:chats chat-id :compacting?] true)
                  {:type :send-prompt
                   :on-finished-side-effect (fn []
                                              (shared/compact-side-effect! chat-ctx false))
                   :prompt (f.prompt/compact-prompt (string/join " " args) all-tools agent config db)})
      "login" (do
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
      "model" (let [selected-model (first args)
                    current-model (or (get-in db [:chats chat-id :model])
                                      full-model
                                      (llm-api/default-model db config))
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
                     (multi-str (str "Current model: `" current-model "`")
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
                     db*)
                    (chat-message
                     (multi-str (str "Selected model: `" selected-model "`")
                                "Using model defaults.")))))
      "fork" (let [chat (get-in db [:chats chat-id])
                   new-id (str (random-uuid))
                   now (System/currentTimeMillis)
                   new-title (fork-title (:title chat))
                   new-chat {:id new-id
                             :title new-title
                             :status :idle
                             :created-at now
                             :updated-at now
                             :model (:model chat)
                             :last-api (:last-api chat)
                             :messages (vec (:messages chat))
                             :prompt-finished? true}]
               (swap! db* assoc-in [:chats new-id] new-chat)
               (db/update-workspaces-cache! @db* metrics)
               (messenger/chat-opened messenger {:chat-id new-id :title new-title})
               {:type :chat-messages
                :chats {new-id {:messages (:messages chat)
                                :title new-title}
                        chat-id {:messages [{:role "system"
                                             :content [{:type :text
                                                        :text (str "Chat forked to: " new-title)}]}]}}})
      "resume" (let [chats (into {}
                                 (filter #(and (not= chat-id (first %))
                                               (not (:subagent (second %)))))
                                 (:chats db))
                     chats-ids (vec (sort-by #(:created-at (get chats %)) (keys chats)))
                     selected-chat-id (try (if (= "latest" (first args))
                                             (count chats-ids)
                                             (parse-long (first args)))
                                           (catch Exception _ nil))
                     selected-chat-id (when selected-chat-id (nth chats-ids (dec selected-chat-id) nil))
                     chat-message-fn (fn [text]
                                       {:type :chat-messages
                                        :chats {chat-id {:messages [{:role "system" :content [{:type :text :text text}]}]}}})]
                 (cond
                   (empty? chats)
                   (chat-message-fn "No past chats found to resume")

                   (string/blank? (first args))
                   (chat-message-fn (multi-str
                                     "Chats:"
                                     ""
                                     "ID - Created at - Title"
                                     (reduce
                                      (fn [s chat-id]
                                        (let [chat (get chats chat-id)
                                              msgs-count (count (filter #(= "user" (:role %))
                                                                        (:messages chat)))
                                              flags (->> (:messages chat)
                                                         (filter #(= "flag" (:role %)))
                                                         (map #(get-in % [:content :text])))]
                                          (if (> msgs-count 0)
                                            (str s (format "%s - %s - %s%s\n"
                                                           (inc (.indexOf ^PersistentVector chats-ids chat-id))
                                                           (shared/ms->presentable-date (:created-at chat) "dd/MM/yyyy HH:mm")
                                                           (or (:title chat) (format "No chat title (%s user messages)" msgs-count))
                                                           (if (seq flags)
                                                             (str " 🚩 " (string/join ", " flags))
                                                             "")))
                                            s)))
                                      ""
                                      chats-ids)
                                     "Run `/resume <chat-id>` or `/resume latest`"))

                   (not selected-chat-id)
                   (chat-message-fn "Chat ID not found.")

                   :else
                   (let [chat (get chats selected-chat-id)]
                     (swap! db* assoc-in [:chats chat-id] chat)
                     (swap! db* update-in [:chats chat-id] dissoc :prompt-finished? :auto-compacting? :compacting?)
                     (swap! db* assoc-in [:chats chat-id :prompt-id] (:prompt-id chat-ctx))
                     (swap! db* update-in [:chats] #(dissoc % selected-chat-id))
                     (db/update-workspaces-cache! @db* metrics)
                     ;; Align the client's selected model with the resumed chat
                     ;; so the LLM call keeps using the chat's original model. #417
                     (config/notify-selected-model-changed! (:model chat) db* messenger config)
                     ;; Align the client's trust indicator with the resumed
                     ;; chat's persisted :trust so the icon matches the
                     ;; auto-approval behavior the server will apply. #426
                     (config/notify-selected-trust-changed! (:trust chat) db* messenger)
                     {:type :chat-messages
                      :clear-before? true
                      :chats {chat-id {:title (:title chat)
                                       :messages (concat [{:role "system" :content [{:type :text :text (str "Resuming chat: " selected-chat-id)}]}]
                                                         (:messages chat))}}})))
      "costs" (let [total-input-tokens (get-in db [:chats chat-id :usage :total-input-tokens] 0)
                    total-input-cache-creation-tokens (get-in db [:chats chat-id :usage :total-input-cache-creation-tokens] nil)
                    total-input-cache-read-tokens (get-in db [:chats chat-id :usage :total-input-cache-read-tokens] nil)
                    total-output-tokens (get-in db [:chats chat-id :usage :total-output-tokens] 0)
                    model-capabilities (get-in db [:models full-model])
                    text (multi-str (str "Total input tokens: " total-input-tokens)
                                    (when total-input-cache-creation-tokens
                                      (str "Total input cache creation tokens: " total-input-cache-creation-tokens))
                                    (when total-input-cache-read-tokens
                                      (str "Total input cache read tokens: " total-input-cache-read-tokens))
                                    (str "Total output tokens: " total-output-tokens)
                                    (str "Total cost: $" (shared/tokens->cost total-input-tokens total-input-cache-creation-tokens total-input-cache-read-tokens total-output-tokens model-capabilities)))]
                {:type :chat-messages
                 :chats {chat-id {:messages [{:role "system" :content [{:type :text :text text}]}]}}})
      "skills" (let [skills (f.skills/all config (:workspace-folders db))
                     msg (reduce
                          (fn [s {:keys [name description]}]
                            (str s "- " name ": " description "\n"))
                          "Skills available:\n\n"
                          skills)]
                 {:type :chat-messages
                  :chats {chat-id {:messages [{:role "system" :content [{:type :text :text msg}]}]}}})
      "skill-create" (let [skill-name (first args)
                           user-prompt (second args)]
                       {:type :send-prompt
                        :prompt (f.prompt/skill-create-prompt skill-name user-prompt all-tools agent db config)})
      "remote" (let [connect-url (:remote-connect-url db)
                     host (:remote-host db)
                     token (:remote-token db)
                     private? (:remote-private-host? db)
                     text (cond
                            (not connect-url)
                            "Remote server is not enabled. Set `remote.enabled: true` in your ECA config to start it."

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
                  :chats {chat-id {:messages [{:role "system" :content [{:type :text :text text}]}]}}})
      "config" {:type :chat-messages
                :chats {chat-id {:messages [{:role "system" :content [{:type :text :text (with-out-str (pprint/pprint config))}]}]}}}
      "doctor" {:type :chat-messages
                :chats {chat-id {:messages [{:role "system" :content [{:type :text :text (doctor-msg db config)}]}]}}}
      "repo-map-show" {:type :chat-messages
                       :chats {chat-id {:messages [{:role "system" :content [{:type :text :text (f.index/repo-map db config {:as-string? true})}]}]}}}
      "rules" (let [roots (:workspace-folders db)
                    msg (rules-msg config roots agent full-model all-tools)]
                {:type :chat-messages
                 :chats {chat-id {:messages [{:role "system" :content [{:type :text :text msg}]}]}}})
      "prompt-show" (let [full-prompt (str "Instructions:\n" (f.prompt/instructions->str instructions) "\n"
                                           "Prompt:\n" (reduce
                                                        (fn [s {:keys [content]}]
                                                          (str
                                                           s
                                                           (reduce
                                                            #(str %1 (string/replace-first (:text %2) "/prompt-show " "") "\n")
                                                            ""
                                                            content)))
                                                        ""
                                                        user-messages))]
                      {:type :chat-messages
                       :chats {chat-id {:messages [{:role "system"
                                                    :content [{:type :text
                                                               :text full-prompt}]}]}}})
      "subagents" (let [msg (subagents-msg config)]
                    {:type :chat-messages
                     :chats {chat-id {:messages [{:role "system" :content [{:type :text :text msg}]}]}}})
      "plugins" (let [plugins-config (:plugins config)
                      plugins (f.plugins/list-marketplace-plugins plugins-config)
                      msg (if (seq plugins)
                            (let [by-source (group-by :source-name plugins)]
                              (multi-str (reduce-kv
                                          (fn [s source-name source-plugins]
                                            (str s "**" source-name "** (`" (:source-url (first source-plugins)) "`)\n"
                                                 (reduce
                                                  (fn [s2 {:keys [name description installed?]}]
                                                    (str s2 "- "
                                                         (when installed? "✅ ")
                                                         name
                                                         (when description (str " — " description))
                                                         "\n"))
                                                  ""
                                                  source-plugins)
                                                 "\n"))
                                          "Plugins available:\n\n"
                                          by-source)
                                         "Use `/plugin-install <name>` to install a plugin."))
                            "No plugin marketplaces configured. Add plugin sources to your config under the `plugins` key.")]
                  {:type :chat-messages
                   :chats {chat-id {:messages [{:role "system" :content [{:type :text :text msg}]}]}}})
      "plugin-install" (let [plugin-input (first args)
                             result (if (string/blank? plugin-input)
                                      {:status :error
                                       :message "Usage: `/plugin-install <plugin-name>` or `/plugin-install <plugin-name@marketplace>`"}
                                      (f.plugins/install-plugin! (:plugins config) plugin-input))]
                         {:type :chat-messages
                          :chats {chat-id {:messages [{:role "system" :content [{:type :text :text (:message result)}]}]}}})
      "plugin-uninstall" (let [plugin-input (first args)
                               result (if (string/blank? plugin-input)
                                        {:status :error
                                         :message "Usage: `/plugin-uninstall <plugin-name>`"}
                                        (f.plugins/uninstall-plugin! (:plugins config) plugin-input))]
                           {:type :chat-messages
                            :chats {chat-id {:messages [{:role "system" :content [{:type :text :text (:message result)}]}]}}})

      ;; else check if a custom command or skill
      (if-let [custom-command-prompt (get-custom-command command args custom-cmds)]
        {:type :send-prompt
         :prompt custom-command-prompt}
        (if-let [skill (first (filter #(= command (:name %)) skills))]
          {:type :send-prompt
           :prompt (if (seq args)
                     (substitute-args (:body skill) args)
                     (str "Load skill: " (:name skill)))}
          {:type :text
           :text (str "Unknown command: " command)})))))
