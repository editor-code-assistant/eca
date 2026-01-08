(ns eca.features.commands
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.features.index :as f.index]
   [eca.features.login :as f.login]
   [eca.features.prompt :as f.prompt]
   [eca.features.skills :as f.skills]
   [eca.features.tools.mcp :as f.mcp]
   [eca.llm-api :as llm-api]
   [eca.messenger :as messenger]
   [eca.secrets :as secrets]
   [eca.shared :as shared :refer [multi-str update-some]])
  (:import
   [clojure.lang PersistentVector]
   [java.lang ProcessHandle]))

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
                       :description "List available skills."
                       :arguments []}
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
                       :arguments [{:name "optional-prompt"}]}]
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

(defn ^:private doctor-msg [db config]
  (let [model (llm-api/default-model db config)
        cred-check (secrets/check-credential-files (:netrcFile config))
        existing-files (filter :exists (:files cred-check))]
    (multi-str (str "ECA version: " (config/eca-version))
               ""
               (str "Server cmd: " (.orElse (.commandLine (.info (ProcessHandle/current))) nil))
               ""
               (str "Workspaces: " (shared/workspaces-as-str db))
               ""
               (str "Default model: " model)
               ""
               (str "Login providers: " (reduce
                                         (fn [s [provider auth]]
                                           (str s provider ": " (-> auth
                                                                    (update-some :verifier shared/obfuscate)
                                                                    (update-some :device-code shared/obfuscate)
                                                                    (update-some :access-token shared/obfuscate)
                                                                    (update-some :refresh-token shared/obfuscate)
                                                                    (update-some :api-key shared/obfuscate)) "\n"))
                                         "\n"
                                         (:auth db)))
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

(defn handle-command! [command args {:keys [chat-id db* config messenger full-model all-tools instructions user-messages metrics]}]
  (let [db @db*
        custom-cmds (custom-commands config (:workspace-folders db))
        skills (f.skills/all config (:workspace-folders db))]
    (case command
      "init" {:type :send-prompt
              :on-finished-side-effect (fn []
                                         (swap! db* assoc-in [:chats chat-id :messages] []))
              :prompt (f.prompt/init-prompt all-tools db)}
      "compact" (do
                  (swap! db* assoc-in [:chats chat-id :compacting?] true)
                  {:type :send-prompt
                   :on-finished-side-effect (fn []
                                              ;; Replace chat history with summary
                                              (swap! db* (fn [db]
                                                           (assoc-in db [:chats chat-id :messages]
                                                                     [{:role "user"
                                                                       :content [{:type :text
                                                                                  :text (str "The conversation was compacted/summarized, consider this summary:\n"
                                                                                             (get-in db [:chats chat-id :last-summary]))}]}])))

                                              ;; Zero chat usage
                                              (swap! db* assoc-in [:chats chat-id :total-input-tokens] nil)
                                              (swap! db* assoc-in [:chats chat-id :total-output-tokens] nil)
                                              (swap! db* assoc-in [:chats chat-id :total-input-cache-creation-tokens] nil)
                                              (swap! db* assoc-in [:chats chat-id :total-input-cache-read-tokens] nil)
                                              (messenger/chat-cleared messenger {:chat-id chat-id :messages true})
                                              (messenger/chat-content-received
                                               messenger
                                               {:chat-id chat-id
                                                :role :system
                                                :content {:type :text
                                                          :text "Compacted chat to:\n\n"}})
                                              (messenger/chat-content-received
                                               messenger
                                               {:chat-id chat-id
                                                :role :assistant
                                                :content {:type :text
                                                          :text (get-in @db* [:chats chat-id :last-summary])}})
                                              (when-let [usage (shared/usage-msg->usage {:input-tokens 0 :output-tokens 0} full-model {:chat-id chat-id :db* db*})]
                                                (messenger/chat-content-received
                                                 messenger
                                                 {:chat-id chat-id
                                                  :role :system
                                                  :content (merge {:type :usage}
                                                                  usage)})))
                   :prompt (f.prompt/compact-prompt (string/join " " args) all-tools config db)})
      "login" (do (f.login/handle-step {:message (or (first args) "")
                                        :chat-id chat-id}
                                       db*
                                       messenger
                                       config
                                       metrics)
                  {:type :new-chat-status
                   :status :login})
      "resume" (let [chats (into {}
                                 (filter #(not= chat-id (first %)))
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

      ;; else check if a custom command or skill
      (if-let [custom-command-prompt (get-custom-command command args custom-cmds)]
        {:type :send-prompt
         :prompt custom-command-prompt}
        (if-let [skill (first (filter #(= command (:name %)) skills))]
          {:type :send-prompt
           :prompt (str "Load skill: " (:name skill))}
          {:type :text
           :text (str "Unknown command: " command)})))))
