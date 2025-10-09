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
   [eca.features.tools.mcp :as f.mcp]
   [eca.llm-api :as llm-api]
   [eca.messenger :as messenger]
   [eca.secrets :as secrets]
   [eca.shared :as shared :refer [multi-str update-some]])
  (:import
   [java.lang ProcessHandle]))

(set! *warn-on-reflection* true)

(defn ^:private normalize-command-name [f]
  (string/lower-case (fs/strip-ext (fs/file-name f))))

(defn ^:private global-file-commands []
  (let [xdg-config-home (or (config/get-env "XDG_CONFIG_HOME")
                            (io/file (config/get-property "user.home") ".config"))
        commands-dir (io/file xdg-config-home "eca" "commands")]
    (when (fs/exists? commands-dir)
      (map (fn [file]
             {:name (normalize-command-name file)
              :path (str (fs/canonicalize file))
              :type :user-global-file
              :content (slurp (fs/file file))})
           (fs/list-dir commands-dir)))))

(defn ^:private local-file-commands [roots]
  (->> roots
       (mapcat (fn [{:keys [uri]}]
                 (let [commands-dir (fs/file (shared/uri->filename uri) ".eca" "commands")]
                   (when (fs/exists? commands-dir)
                     (fs/list-dir commands-dir)))))
       (map (fn [file]
              {:name (normalize-command-name file)
               :path (str (fs/canonicalize file))
               :type :user-local-file
               :content (slurp (fs/file file))}))))

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
                       :description "Resume the chats from this session workspaces."
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
                      {:name "prompt-show"
                       :type :native
                       :description "Prompt sent to LLM as system instructions."
                       :arguments []}]
        custom-cmds (map (fn [custom]
                           {:name (:name custom)
                            :type :custom-prompt
                            :description (:path custom)
                            :arguments []})
                         (custom-commands config (:workspace-folders db)))]
    (concat mcp-prompts
            eca-commands
            custom-cmds)))

(defn ^:private get-custom-command [command args custom-cmds]
  (when-let [raw-content (:content (first (filter #(= command (:name %))
                                                  custom-cmds)))]
    (let [raw-content (string/replace raw-content "$ARGS" (string/join " " args))]
      (reduce (fn [content [i arg]]
                (string/replace content (str "$ARG" (inc i)) arg))
              raw-content
              (map-indexed vector args)))))

(defn ^:private doctor-msg [db config]
  (let [model (llm-api/default-model db config)
        cred-check (secrets/check-credential-files)
        existing-files (filter :exists (:files cred-check))]
    (multi-str (str "ECA version: " (config/eca-version))
               ""
               (str "Server cmd: " (.orElse (.commandLine (.info (ProcessHandle/current))) nil))
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
                                                    (if (or (string/includes? key "KEY")
                                                            (string/includes? key "API")
                                                            (string/includes? key "URL")
                                                            (string/includes? key "BASE"))
                                                      (str s key "=" val "\n")
                                                      s))
                                                  "\n"
                                                  (System/getenv)))
               ""
               (if (seq existing-files)
                 (str "Credential files (GPG available: " (:gpg-available cred-check) "):"
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
                 (str "Credential files: None found (GPG available: " (:gpg-available cred-check) ")")))))

(defn handle-command! [command args {:keys [chat-id db* config messenger full-model instructions metrics]}]
  (let [db @db*
        custom-cmds (custom-commands config (:workspace-folders db))]
    (case command
      "init" {:type :send-prompt
              :on-finished-side-effect (fn []
                                         (swap! db* assoc-in [:chats chat-id :messages] []))
              :prompt (f.prompt/init-prompt db)}
      "compact" (do
                  (swap! db* assoc-in [:chats chat-id :compacting?] true)
                  {:type :send-prompt
                   :on-finished-side-effect (fn []
                                              ;; Replace chat history with summary
                                              (swap! db* assoc-in [:chats chat-id :messages]
                                                     [{:role "user"
                                                       :content [{:type :text
                                                                  :text (str "The conversation was compacted/summarized, consider this summary:\n"
                                                                             (get-in @db* [:chats chat-id :last-summary]))}]}])

                                              ;; Zero chat usage
                                              (swap! db* assoc-in [:chats chat-id :total-input-tokens] nil)
                                              (swap! db* assoc-in [:chats chat-id :total-output-tokens] nil)
                                              (swap! db* assoc-in [:chats chat-id :total-input-cache-creation-tokens] nil)
                                              (swap! db* assoc-in [:chats chat-id :total-input-cache-read-tokens] nil)
                                              (when-let [usage (shared/usage-msg->usage {:input-tokens 0 :output-tokens 0} full-model {:chat-id chat-id :db* db*})]
                                                (messenger/chat-content-received
                                                 messenger
                                                 {:chat-id chat-id
                                                  :role :system
                                                  :content (merge {:type :usage}
                                                                  usage)})))
                   :prompt (f.prompt/compact-prompt (string/join " " args) config)})
      "login" (do (f.login/handle-step {:message (or (first args) "")
                                        :chat-id chat-id}
                                       db*
                                       messenger
                                       config
                                       metrics)
                  {:type :new-chat-status
                   :status :login})
      "resume" (let [chats (:chats db)]
                 ;; Override current chat with first chat
                 (when-let [first-chat (second (first chats))]
                   (swap! db* assoc-in [:chats chat-id] first-chat)
                   ;; TODO support multiple chats update
                   {:type :chat-messages
                    :chats {chat-id (:messages first-chat)}}))
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
                 :chats {chat-id [{:role "system" :content [{:type :text :text text}]}]}})
      "config" {:type :chat-messages
                :chats {chat-id [{:role "system" :content [{:type :text :text (with-out-str (pprint/pprint config))}]}]}}
      "doctor" {:type :chat-messages
                :chats {chat-id [{:role "system" :content [{:type :text :text (doctor-msg db config)}]}]}}
      "repo-map-show" {:type :chat-messages
                       :chats {chat-id [{:role "system" :content [{:type :text :text (f.index/repo-map db config {:as-string? true})}]}]}}
      "prompt-show" {:type :chat-messages
                     :chats {chat-id [{:role "system" :content [{:type :text :text instructions}]}]}}

      ;; else check if a custom command
      (if-let [custom-command-prompt (get-custom-command command args custom-cmds)]
        {:type :send-prompt
         :prompt custom-command-prompt}
        {:type :text
         :text (str "Unknown command: " command)}))))
