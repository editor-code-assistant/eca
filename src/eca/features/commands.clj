(ns eca.features.commands
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.features.index :as f.index]
   [eca.features.tools.mcp :as f.mcp]
   [eca.shared :as shared :refer [multi-str]]))

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
          (global-file-commands)
          (local-file-commands roots)))

(defn all-commands [db config]
  (let [mcp-prompts (->> (f.mcp/all-prompts db)
                         (mapv #(-> %
                                    (assoc :name (str (:server %) ":" (:name %))
                                           :type :mcpPrompt)
                                    (dissoc :server))))
        eca-commands [{:name "costs"
                       :type :native
                       :description "Show the total costs of the current chat session."
                       :arguments []}
                      {:name "repo-map-show"
                       :type :native
                       :description "Show the actual repoMap of current session."
                       :arguments []}
                      {:name "resume"
                       :type :native
                       :description "Resume the chats from this session workspaces."
                       :arguments []}]
        custom-commands (map (fn [custom]
                               {:name (:name custom)
                                :type :custom-prompt
                                :description (:path custom)
                                :arguments []})
                             (custom-commands config (:workspace-folders db)))]
    (concat mcp-prompts
            eca-commands
            custom-commands)))

(defn ^:private get-custom-command [command args custom-commands]
  (when-let [raw-content (:content (first (filter #(= command (:name %))
                                                  custom-commands)))]
    (let [raw-content (string/replace raw-content "$ARGS" (string/join " " args))]
      (reduce (fn [content [i arg]]
                (string/replace content (str "$ARG" (inc i)) arg))
              raw-content
              (map-indexed vector args)))))

(defn handle-command! [command args chat-id model config db*]
  (let [db @db*
        custom-commands (custom-commands config (:workspace-folders db))]
    (case command
      "costs" (let [total-input-tokens (get-in db [:chats chat-id :total-input-tokens] 0)
                    total-input-cache-creation-tokens (get-in db [:chats chat-id :total-input-cache-creation-tokens] nil)
                    total-input-cache-read-tokens (get-in db [:chats chat-id :total-input-cache-read-tokens] nil)
                    total-output-tokens (get-in db [:chats chat-id :total-output-tokens] 0)
                    text (multi-str (str "Total input tokens: " total-input-tokens)
                                    (when total-input-cache-creation-tokens
                                      (str "Total input cache creation tokens: " total-input-cache-creation-tokens))
                                    (when total-input-cache-read-tokens
                                      (str "Total input cache read tokens: " total-input-cache-read-tokens))
                                    (str "Total output tokens: " total-output-tokens)
                                    (str "Total cost: $" (shared/tokens->cost total-input-tokens total-input-cache-creation-tokens total-input-cache-read-tokens total-output-tokens model db)))]
                {:type :chat-messages
                 :chats {chat-id [{:role "system" :content [{:type :text :text text}]}]}})
      "repo-map-show" {:type :chat-messages
                       :chats {chat-id [{:role "system" :content [{:type :text :text (f.index/repo-map db {:as-string? true})}]}]}}

      "resume" (let [chats (:chats db)]
                 ;; Override current chat with first chat
                 (when-let [first-chat (second (first chats))]
                   (swap! db* assoc-in [:chats chat-id] first-chat)
                   ;; TODO support multiple chats update
                   {:type :chat-messages
                    :chats {chat-id (:messages first-chat)}}))

      ;; else check if a custom command
      (if-let [custom-command-prompt (get-custom-command command args custom-commands)]
        {:type :send-prompt
         :prompt custom-command-prompt}
        {:type :text
         :text (str "Unknown command: " command)}))))
