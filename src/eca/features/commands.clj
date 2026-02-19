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
   [eca.features.prompt :as f.prompt]
   [eca.features.skills :as f.skills]
   [eca.features.tools.mcp :as f.mcp]
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

(defn ^:private normalize-command-name [f]
  (string/lower-case (fs/strip-ext (fs/file-name f))))

(defn ^:private global-file-commands []
  (let [xdg-config-home (or (config/get-env "XDG_CONFIG_HOME")
                            (io/file (config/get-property "user.home") ".config"))
        commands-dir (io/file xdg-config-home "eca" "commands")]
    (when (fs/exists? commands-dir)
      (keep (fn [file]
              (when-not (fs/directory? file)
                {:name (normalize-command-name file)
                 :path (str (fs/canonicalize file))
                 :type :user-global-file
                 :content (slurp (fs/file file))}))
            (fs/glob commands-dir "**" {:follow-links true})))))

(defn ^:private local-file-commands [roots]
  (->> roots
       (mapcat (fn [{:keys [uri]}]
                 (let [commands-dir (fs/file (shared/uri->filename uri) ".eca" "commands")]
                   (when (fs/exists? commands-dir)
                     (fs/glob commands-dir "**" {:follow-links true})))))
       (keep (fn [file]
               (when-not (fs/directory? file)
                 {:name (normalize-command-name file)
                  :path (str (fs/canonicalize file))
                  :type :user-local-file
                  :content (slurp (fs/file file))})))))

(defn ^:private config-commands [config roots]
  (->> (get config :commands)
       (map
        (fn [{:keys [path]}]
          (if (fs/absolute? path)
            (when (fs/exists? path)
              {:name (normalize-command-name path)
               :path path
               :type :user-config
               :content (slurp path)})
            (keep (fn [{:keys [uri]}]
                    (let [f (fs/file (shared/uri->filename uri) path)]
                      (when (fs/exists? f)
                        {:name (normalize-command-name f)
                         :path (str (fs/canonicalize f))
                         :type :user-config
                         :content (slurp f)})))
                  roots))))
       (flatten)
       (remove nil?)))

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
                      {:name "skills"
                       :type :native
                       :description "List available skills"
                       :arguments []}
                      {:name "skill-create"
                       :type :native
                       :description "Create a skill considering a user request"
                       :arguments [{:name "name" :description "The skill name"}
                                   {:name "prompt" :description "What to consider as this skill content"}]}
                      {:name "costs"
                       :type :native
                       :description "Total costs of the current chat session."
                       :arguments []}
                      {:name "compact"
                       :type :native
                       :description "Summarize the chat so far cleaning previous chat history to reduce context."
                       :arguments [{:name "additional-input"}]}
                      {:name "resume"
                       :type :native
                       :description "Resume the specified chat-id. Blank to list chats or 'latest'."
                       :arguments [{:name "chat-id"}]}
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
                      {:name "prompt-show"
                       :type :native
                       :description "Prompt sent to LLM as system instructions."
                       :arguments [{:name "optional-prompt"}]}
                      {:name "subagents"
                       :type :native
                       :description "List available subagents and their configuration."
                       :arguments []}]
        custom-cmds (map (fn [custom]
                           {:name (:name custom)
                            :type :custom-prompt
                            :description (:path custom)
                            :arguments []})
                         (custom-commands config (:workspace-folders db)))
        skills-cmds (->> (f.skills/all config (:workspace-folders db))
                         (mapv (fn [skill]
                                 {:name (:name skill)
                                  :type :skill
                                  :description (:description skill)
                                  :arguments []})))]
    (concat mcp-prompts
            eca-commands
            skills-cmds
            custom-cmds)))

(defn ^:private get-custom-command [command args custom-cmds]
  (when-let [raw-content (:content (first (filter #(= command (:name %))
                                                  custom-cmds)))]
    (let [args-joined (string/join " " args)
          content-with-args (-> raw-content
                                (string/replace "$ARGS" args-joined)
                                (string/replace "$ARGUMENTS" args-joined))]
      (reduce (fn [content [i arg]]
                (string/replace content (str "$ARG" (inc i)) arg))
              content-with-args
              (map-indexed vector args)))))

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
                            :text "Logged started, type 'cancel' anytime to exit login."}})
                (f.login/handle-step {:message (or (first args) "")
                                      :chat-id chat-id}
                                     db*
                                     messenger
                                     config
                                     metrics)
                {:type :new-chat-status
                 :status :login})
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
                                        (let [chat (get chats chat-id)]
                                          (str s (format "%s - %s - %s\n"
                                                         (inc (.indexOf ^PersistentVector chats-ids chat-id))
                                                         (shared/ms->presentable-date (:created-at chat) "dd/MM/yyyy HH:mm")
                                                         (or (:title chat) (format "No chat title (%s user messages)" (count (filter #(= "user" (:role %))
                                                                                                                                     (:messages chat)))))))))
                                      ""
                                      chats-ids)
                                     "Run `/resume <chat-id>` or `/resume latest`"))

                   (not selected-chat-id)
                   (chat-message-fn "Chat ID not found.")

                   :else
                   (let [chat (get chats selected-chat-id)]
                     (swap! db* assoc-in [:chats chat-id] chat)
                     (swap! db* update-in [:chats] #(dissoc % selected-chat-id))
                     (db/update-workspaces-cache! @db* metrics)
                     {:type :chat-messages
                      :chats {chat-id {:title (:title chat)
                                       :messages (concat [{:role "system" :content [{:type :text :text (str "Resuming chat: " selected-chat-id)}]}]
                                                         (:messages chat))}}})))
      "costs" (let [total-input-tokens (get-in db [:chats chat-id :total-input-tokens] 0)
                    total-input-cache-creation-tokens (get-in db [:chats chat-id :total-input-cache-creation-tokens] nil)
                    total-input-cache-read-tokens (get-in db [:chats chat-id :total-input-cache-read-tokens] nil)
                    total-output-tokens (get-in db [:chats chat-id :total-output-tokens] 0)
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
      "config" {:type :chat-messages
                :chats {chat-id {:messages [{:role "system" :content [{:type :text :text (with-out-str (pprint/pprint config))}]}]}}}
      "doctor" {:type :chat-messages
                :chats {chat-id {:messages [{:role "system" :content [{:type :text :text (doctor-msg db config)}]}]}}}
      "repo-map-show" {:type :chat-messages
                       :chats {chat-id {:messages [{:role "system" :content [{:type :text :text (f.index/repo-map db config {:as-string? true})}]}]}}}
      "prompt-show" (let [full-prompt (str "Instructions:\n" instructions "\n"
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

      ;; else check if a custom command or skill
      (if-let [custom-command-prompt (get-custom-command command args custom-cmds)]
        {:type :send-prompt
         :prompt custom-command-prompt}
        (if-let [skill (first (filter #(= command (:name %)) skills))]
          {:type :send-prompt
           :prompt (str "Load skill: " (:name skill))}
          {:type :text
           :text (str "Unknown command: " command)})))))
