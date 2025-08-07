(ns eca.features.chat
  (:require
   [cheshire.core :as json]
   [clojure.set :as set]
   [clojure.string :as string]
   [eca.features.context :as f.context]
   [eca.features.index :as f.index]
   [eca.features.prompt :as f.prompt]
   [eca.features.rules :as f.rules]
   [eca.features.tools :as f.tools]
   [eca.features.tools.mcp :as f.mcp]
   [eca.llm-api :as llm-api]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.shared :as shared :refer [assoc-some multi-str]]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CHAT]")

(defn default-model [db config]
  (llm-api/default-model db config))

(defn ^:private send-content! [{:keys [messenger chat-id request-id]} role content]
  (messenger/chat-content-received
   messenger
   {:chat-id chat-id
    :request-id request-id
    :role role
    :content content}))

(defn finish-chat-prompt! [status {:keys [chat-id db*] :as chat-ctx}]
  (swap! db* assoc-in [:chats chat-id :status] status)
  (send-content! chat-ctx :system
                 {:type :progress
                  :state :finished}))

(defn ^:private assert-chat-not-stopped! [{:keys [chat-id db*] :as chat-ctx}]
  (when (identical? :stoping (get-in @db* [:chats chat-id :status]))
    (finish-chat-prompt! :idle chat-ctx)
    (logger/info logger-tag "Chat prompt stopped:" chat-id)
    (throw (ex-info "Chat prompt stopped" {:silent? true
                                           :chat-id chat-id}))))

(defn ^:private tool-name->origin [name all-tools]
  (:origin (first (filter #(= name (:name %)) all-tools))))

(defn ^:private tokens->cost [input-tokens input-cache-creation-tokens input-cache-read-tokens output-tokens model db]
  (let [normalized-model (if (string/includes? model "/")
                           (last (string/split model #"/"))
                           model)
        {:keys [input-token-cost output-token-cost
                input-cache-creation-token-cost input-cache-read-token-cost]} (get-in db [:models normalized-model])
        input-cost (* input-tokens input-token-cost)
        input-cost (if input-cache-creation-tokens
                     (+ input-cost (* input-cache-creation-tokens input-cache-creation-token-cost))
                     input-cost)
        input-cost (if input-cache-read-tokens
                     (+ input-cost (* input-cache-read-tokens input-cache-read-token-cost))
                     input-cost)]
    (when (and input-token-cost output-token-cost)
      (format "%.2f" (+ input-cost
                        (* output-tokens output-token-cost))))))

(defn ^:private usage-msg->usage
  [{:keys [input-tokens output-tokens
           input-cache-creation-tokens input-cache-read-tokens]}
   model
   {:keys [chat-id db*]}]
  (when (and output-tokens input-tokens)
    (swap! db* update-in [:chats chat-id :total-input-tokens] (fnil + 0) input-tokens)
    (swap! db* update-in [:chats chat-id :total-output-tokens] (fnil + 0) output-tokens)
    (when input-cache-creation-tokens
      (swap! db* update-in [:chats chat-id :total-input-cache-creation-tokens] (fnil + 0) input-cache-creation-tokens))
    (when input-cache-read-tokens
      (swap! db* update-in [:chats chat-id :total-input-cache-read-tokens] (fnil + 0) input-cache-read-tokens))
    (let [db @db*
          message-input-cache-tokens (or input-cache-creation-tokens 0)
          total-input-tokens (get-in db [:chats chat-id :total-input-tokens] 0)
          total-input-cache-creation-tokens (get-in db [:chats chat-id :total-input-cache-creation-tokens] nil)
          total-input-cache-read-tokens (get-in db [:chats chat-id :total-input-cache-read-tokens] nil)
          total-input-cache-tokens (or total-input-cache-creation-tokens 0)
          total-output-tokens (get-in db [:chats chat-id :total-output-tokens] 0)]
      (assoc-some {:message-output-tokens output-tokens
                   :message-input-tokens (+ input-tokens message-input-cache-tokens)
                   :session-tokens (+ total-input-tokens total-input-cache-tokens total-output-tokens)}
                  :message-cost (tokens->cost input-tokens input-cache-creation-tokens input-cache-read-tokens output-tokens model db)
                  :session-cost (tokens->cost total-input-tokens total-input-cache-creation-tokens total-input-cache-read-tokens total-output-tokens model db)))))

(defn ^:private message->decision [message]
  (let [slash? (string/starts-with? message "/")
        mcp-prompt? (string/includes? (first (string/split message #" ")) ":")]
    (cond
      (and slash? mcp-prompt?)
      (let [command (subs message 1)
            parts (string/split command #" ")
            [server] (string/split command #":")]
        {:type :mcp-prompt
         :server server
         :prompt (second (string/split (first parts) #":"))
         :args (if (seq parts)
                 (vec (rest parts))
                 [])})

      slash?
      {:type :eca-command
       :command (subs message 1)}

      :else
      {:type :prompt-message
       :message message})))

(defn ^:private prompt-messages!
  [user-messages
   reason?
   {:keys [db* config chat-id contexts behavior model] :as chat-ctx}]
  (when (seq contexts)
    (send-content! chat-ctx :system {:type :progress
                                     :state :running
                                     :text "Parsing given context"}))
  (let [db @db*
        manual-approval? (get-in config [:toolCall :manualApproval] false)
        rules (f.rules/all config (:workspace-folders db))
        refined-contexts (f.context/raw-contexts->refined contexts db)
        repo-map* (delay (f.index/repo-map db {:as-string? true}))
        instructions (f.prompt/build-instructions refined-contexts rules repo-map* (or behavior (:chat-default-behavior db)) config)
        past-messages (get-in db [:chats chat-id :messages] [])
        all-tools (f.tools/all-tools @db* config)
        received-msgs* (atom "")
        received-thinking* (atom "")
        tool-call-by-id* (atom {:args {}})
        add-to-history! (fn [msg]
                          (swap! db* update-in [:chats chat-id :messages] (fnil conj []) msg))]

    (send-content! chat-ctx :system {:type :progress
                                     :state :running
                                     :text "Waiting model"})
    (llm-api/complete!
     {:model model
      :model-config (get-in db [:models model])
      :user-messages user-messages
      :instructions instructions
      :past-messages past-messages
      :config config
      :tools all-tools
      :reason? reason?
      :on-first-response-received (fn [& _]
                                    (assert-chat-not-stopped! chat-ctx)
                                    (doseq [message user-messages]
                                      (add-to-history! message))
                                    (send-content! chat-ctx :system {:type :progress
                                                                     :state :running
                                                                     :text "Generating"}))
      :on-usage-updated (fn [usage]
                          (send-content! chat-ctx :system
                                         (merge {:type :usage}
                                                (usage-msg->usage usage model chat-ctx))))
      :on-message-received (fn [{:keys [type] :as msg}]
                             (assert-chat-not-stopped! chat-ctx)
                             (case type
                               :text (do
                                       (swap! received-msgs* str (:text msg))
                                       (send-content! chat-ctx :assistant {:type :text
                                                                           :text (:text msg)}))
                               :url (send-content! chat-ctx :assistant {:type :url
                                                                        :title (:title msg)
                                                                        :url (:url msg)})
                               :limit-reached (do
                                                (send-content! chat-ctx :system
                                                               {:type :text
                                                                :text (str "API limit reached. Tokens: " (json/generate-string (:tokens msg)))})

                                                (finish-chat-prompt! :idle chat-ctx))
                               :finish (do
                                         (add-to-history! {:role "assistant" :content @received-msgs*})
                                         (finish-chat-prompt! :idle chat-ctx))))
      :on-prepare-tool-call (fn [{:keys [id name arguments-text]}]
                              (assert-chat-not-stopped! chat-ctx)
                              (swap! tool-call-by-id* update-in [id :args] str arguments-text)
                              (send-content! chat-ctx :assistant
                                             {:type :toolCallPrepare
                                              :name name
                                              :origin (tool-name->origin name all-tools)
                                              :arguments-text (get-in @tool-call-by-id* [id :args])
                                              :id id
                                              :manual-approval manual-approval?}))
      :on-tool-called (fn [{:keys [id name arguments] :as tool-call}]
                        (assert-chat-not-stopped! chat-ctx)
                        (let [approved?* (promise)
                              details (f.tools/get-tool-call-details name arguments)]
                          (send-content! chat-ctx :assistant
                                         (assoc-some
                                          {:type :toolCallRun
                                           :name name
                                           :origin (tool-name->origin name all-tools)
                                           :arguments arguments
                                           :id id
                                           :manual-approval manual-approval?}
                                          :details details))
                          (swap! db* assoc-in [:chats chat-id :tool-calls id :approved?*] approved?*)
                          (when-not (string/blank? @received-msgs*)
                            (add-to-history! {:role "assistant" :content @received-msgs*})
                            (reset! received-msgs* ""))
                          (if manual-approval?
                            (send-content! chat-ctx :system
                                           {:type :progress
                                            :state :running
                                            :text "Waiting for tool call approval"})
                             ;; Otherwise auto approve
                            (deliver approved?* true))
                          (if @approved?*
                            (let [result (f.tools/call-tool! name arguments @db* config)]
                              (add-to-history! {:role "tool_call" :content tool-call})
                              (add-to-history! {:role "tool_call_output" :content (assoc tool-call :output result)})
                              (send-content! chat-ctx :assistant
                                             (assoc-some
                                              {:type :toolCalled
                                               :origin (tool-name->origin name all-tools)
                                               :name name
                                               :arguments arguments
                                               :error (:error result)
                                               :id id
                                               :outputs (:contents result)}
                                              :details details)))
                            (do
                              (add-to-history! {:role "tool_call" :content tool-call})
                              (add-to-history! {:role "tool_call_output" :content (assoc tool-call :output {:error true
                                                                                                            :contents [{:text "Tool call rejected by user"
                                                                                                                        :type :text}]})})
                              (send-content! chat-ctx :assistant
                                             (assoc-some
                                              {:type :toolCallRejected
                                               :origin (tool-name->origin name all-tools)
                                               :name name
                                               :arguments arguments
                                               :reason :user
                                               :id id}
                                              :details details))))
                          (swap! tool-call-by-id* dissoc id)
                          (send-content! chat-ctx :system {:type :progress :state :running :text "Generating"})
                          {:new-messages (get-in @db* [:chats chat-id :messages])}))
      :on-reason (fn [{:keys [status id text external-id]}]
                   (assert-chat-not-stopped! chat-ctx)
                   (case status
                     :started (send-content! chat-ctx :assistant
                                             {:type :reasonStarted
                                              :id id})
                     :thinking (do
                                 (swap! received-thinking* str text)
                                 (send-content! chat-ctx :assistant
                                                {:type :reasonText
                                                 :id id
                                                 :text text}))
                     :finished (do
                                 (add-to-history! {:role "reason" :content {:external-id external-id
                                                                            :text @received-thinking*}})
                                 (send-content! chat-ctx :assistant
                                                {:type :reasonFinished
                                                 :id id}))
                     nil))
      :on-error (fn [{:keys [message exception]}]
                  (send-content! chat-ctx :system
                                 {:type :text
                                  :text (or message (ex-message exception))})
                  (finish-chat-prompt! :idle chat-ctx))})))

(defn ^:private send-mcp-prompt!
  [{:keys [prompt args]}
   {:keys [db*] :as chat-ctx}]
  (let [{:keys [arguments]} (first (filter #(= prompt (:name %)) (f.mcp/all-prompts @db*)))
        args-vals (zipmap (map :name arguments) args)
        {:keys [messages error-message]} (f.prompt/get-prompt! prompt args-vals @db*)]
    (if error-message
      (send-content! chat-ctx :system
                     {:type :text
                      :text error-message})
      (prompt-messages! messages false chat-ctx))))

(defn ^:private handle-command! [{:keys [command]} {:keys [chat-id db* model] :as chat-ctx}]
  (let [db @db*]
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
                                    (str "Total cost: $" (tokens->cost total-input-tokens total-input-cache-creation-tokens total-input-cache-read-tokens total-output-tokens model db)))]
                (send-content! chat-ctx :system {:type :text
                                                 :text text}))
      "repo-map-show" (send-content! chat-ctx :system {:type :text
                                                       :text (f.index/repo-map db {:as-string? true})})
      (send-content! chat-ctx :system {:type :text
                                       :text (str "Unknown command: " command)})))
  (finish-chat-prompt! :idle chat-ctx))

(defn prompt
  [{:keys [message model behavior contexts chat-id request-id]}
   db*
   messenger
   config]
  (let [message (string/trim message)
        chat-id (or chat-id
                    (let [new-id (str (random-uuid))]
                      (swap! db* assoc-in [:chats new-id] {:id new-id})
                      new-id))
        chosen-model (or model (default-model @db* config))
        chat-ctx {:chat-id chat-id
                  :request-id request-id
                  :contexts contexts
                  :behavior behavior
                  :model chosen-model
                  :db* db*
                  :config config
                  :messenger messenger}
        decision (message->decision message)]
    (swap! db* assoc-in [:chats chat-id :current-request-id] request-id)
    (swap! db* assoc-in [:chats chat-id :status] :running)
    (send-content! chat-ctx :user {:type :text
                                   :text (str message "\n")})
    (case (:type decision)
      :mcp-prompt (send-mcp-prompt! decision chat-ctx)
      :eca-command (handle-command! decision chat-ctx)
      :prompt-message (prompt-messages! [{:role "user" :content message}] true chat-ctx))
    {:chat-id chat-id
     :model chosen-model
     :status :success}))

(defn tool-call-approve [{:keys [chat-id tool-call-id]} db*]
  (deliver (get-in @db* [:chats chat-id :tool-calls tool-call-id :approved?*]) true))

(defn tool-call-reject [{:keys [chat-id tool-call-id]} db*]
  (deliver (get-in @db* [:chats chat-id :tool-calls tool-call-id :approved?*]) false))

(defn query-context
  [{:keys [query contexts chat-id]}
   db*
   config]
  {:chat-id chat-id
   :contexts (set/difference (set (f.context/all-contexts query db* config))
                             (set contexts))})

(defn query-commands
  [{:keys [query chat-id]}
   db*]
  (let [query (string/lower-case query)
        mcp-prompts (->> (f.mcp/all-prompts @db*)
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
                       :arguments []}]
        commands (concat mcp-prompts
                         eca-commands)
        commands (if (string/blank? query)
                   commands
                   (filter #(or (string/includes? (string/lower-case (:name %)) query)
                                (string/includes? (string/lower-case (:description %)) query))
                           commands))]
    {:chat-id chat-id
     :commands commands}))

(defn prompt-stop
  [{:keys [chat-id]} db* messenger]
  (when (identical? :running (get-in @db* [:chats chat-id :status]))
    (let [request-id (get-in @db* [:chats chat-id :current-request-id])
          chat-ctx {:chat-id chat-id
                    :request-id request-id
                    :db* db*
                    :messenger messenger}]
      (send-content! chat-ctx :system {:type :text
                                       :text "\nPrompt stopped"})
      (finish-chat-prompt! :stoping chat-ctx))))

(defn delete-chat
  [{:keys [chat-id]} db*]
  (swap! db* update :chats dissoc chat-id))
