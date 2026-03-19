(ns eca.remote.handlers
  "REST API handlers for the remote web control server.
   Each handler receives components map and Ring request, delegates to
   the same feature functions used by JSON-RPC handlers."
  (:require
   [cheshire.core :as json]
   [eca.config :as config]
   [eca.features.chat :as f.chat]
   [eca.handlers :as handlers]
   [eca.messenger :as messenger]
   [eca.remote.sse :as sse]
   [eca.shared :as shared])
  (:import
   [java.io InputStream PipedInputStream PipedOutputStream]))

(set! *warn-on-reflection* true)

(defn- parse-body [request]
  (when-let [body (:body request)]
    (try
      (json/parse-string
       (if (instance? InputStream body)
         (slurp ^InputStream body)
         (str body))
       true)
      (catch Exception _e nil))))

(defn- json-response
  ([status body]
   {:status status
    :headers {"Content-Type" "application/json; charset=utf-8"}
    :body (json/generate-string body)})
  ([body] (json-response 200 body)))

(defn- error-response [status code message]
  (json-response status {:error {:code code :message message}}))

(defn- no-content []
  {:status 204 :headers {} :body nil})

(defn- chat-or-404 [db* chat-id]
  (get-in @db* [:chats chat-id]))

(defn- camel-keys [m]
  (shared/map->camel-cased-map m))

;; --- Health & Redirect ---

(defn handle-root [_components _request {:keys [host token]}]
  {:status 302
   :headers {"Location" (str "https://web.eca.dev?host=" host "&token=" token)}
   :body nil})

(defn handle-health [_components _request]
  (json-response {:status "ok" :version (config/eca-version)}))

;; --- Session ---

(defn handle-session [{:keys [db*]} _request]
  (let [db @db*
        config (config/all db)]
    (json-response
     {:version (config/eca-version)
      :protocolVersion "1.0"
      :workspaceFolders (mapv #(shared/uri->filename (:uri %)) (:workspace-folders db))
      :models (mapv (fn [[id _]] {:id id :name id :provider (first (shared/full-model->provider+model id))})
                    (:models db))
      :agents (mapv (fn [name] {:id name :name name :description (get-in config [:agent name :description])})
                    (config/primary-agent-names config))
      :mcpServers (mapv (fn [[name client]]
                          {:name name :status (or (:status client) "unknown")})
                        (:mcp-clients db))})))

;; --- Chats ---

(defn handle-list-chats [{:keys [db*]} _request]
  (let [chats (->> (vals (:chats @db*))
                   (remove :subagent)
                   (mapv (fn [{:keys [id title status created-at]}]
                           {:id id
                            :title title
                            :status (or status :idle)
                            :createdAt created-at})))]
    (json-response chats)))

(defn handle-get-chat [{:keys [db*]} _request chat-id]
  (if-let [chat (chat-or-404 db* chat-id)]
    (json-response
     (camel-keys
      {:id (:id chat)
       :title (:title chat)
       :status (or (:status chat) :idle)
       :created-at (:created-at chat)
       :messages (or (:messages chat) [])
       :tool-calls (or (:tool-calls chat) {})
       :task (:task chat)}))
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))))

;; --- Chat Actions ---

(defn handle-prompt [{:keys [db* messenger metrics] :as components} request chat-id]
  (let [body (parse-body request)]
    (if-not (:message body)
      (error-response 400 "invalid_request" "Missing required field: message")
      (let [config (config/all @db*)
            params (shared/map->camel-cased-map
                    (cond-> {:chat-id chat-id
                             :message (:message body)}
                      (:model body) (assoc :model (:model body))
                      (:agent body) (assoc :agent (:agent body))
                      (:variant body) (assoc :variant (:variant body))))
            result (handlers/chat-prompt (assoc components :config config) params)]
        (json-response (camel-keys result))))))

(defn handle-stop [{:keys [db* messenger metrics] :as components} _request chat-id]
  (if-not (chat-or-404 db* chat-id)
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))
    (if-not (identical? :running (get-in @db* [:chats chat-id :status]))
      (error-response 409 "chat_wrong_status" "Chat is not running")
      (let [config (config/all @db*)]
        (handlers/chat-prompt-stop (assoc components :config config) {:chat-id chat-id})
        (no-content)))))

(defn handle-approve [{:keys [db*] :as components} _request chat-id tool-call-id]
  (if-not (chat-or-404 db* chat-id)
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))
    (if-not (get-in @db* [:chats chat-id :tool-calls tool-call-id])
      (error-response 404 "tool_call_not_found" (str "Tool call " tool-call-id " does not exist"))
      (let [config (config/all @db*)]
        (handlers/chat-tool-call-approve
         (assoc components :config config)
         {:chat-id chat-id :tool-call-id tool-call-id})
        (no-content)))))

(defn handle-reject [{:keys [db*] :as components} _request chat-id tool-call-id]
  (if-not (chat-or-404 db* chat-id)
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))
    (if-not (get-in @db* [:chats chat-id :tool-calls tool-call-id])
      (error-response 404 "tool_call_not_found" (str "Tool call " tool-call-id " does not exist"))
      (let [config (config/all @db*)]
        (handlers/chat-tool-call-reject
         (assoc components :config config)
         {:chat-id chat-id :tool-call-id tool-call-id})
        (no-content)))))

(defn handle-rollback [{:keys [db*] :as components} request chat-id]
  (if-not (chat-or-404 db* chat-id)
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))
    (let [body (parse-body request)
          config (config/all @db*)]
      (handlers/chat-rollback
       (assoc components :config config)
       {:chat-id chat-id :content-id (:contentId body)})
      (no-content))))

(defn handle-clear [{:keys [db*] :as components} _request chat-id]
  (if-not (chat-or-404 db* chat-id)
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))
    (let [config (config/all @db*)]
      (handlers/chat-clear
       (assoc components :config config)
       {:chat-id chat-id :messages true})
      (messenger/chat-cleared (:messenger components) {:chat-id chat-id :messages true})
      (no-content))))

(defn handle-delete-chat [{:keys [db*] :as components} _request chat-id]
  (if-not (chat-or-404 db* chat-id)
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))
    (let [config (config/all @db*)]
      (handlers/chat-delete
       (assoc components :config config)
       {:chat-id chat-id})
      (no-content))))

(defn handle-change-model [{:keys [db*] :as components} request chat-id]
  (if-not (chat-or-404 db* chat-id)
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))
    (let [body (parse-body request)]
      (if-not (:model body)
        (error-response 400 "invalid_request" "Missing required field: model")
        (let [config (config/all @db*)]
          (handlers/chat-selected-model-changed
           (assoc components :config config)
           {:model (:model body)})
          (no-content))))))

(defn handle-change-agent [{:keys [db*] :as components} request chat-id]
  (if-not (chat-or-404 db* chat-id)
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))
    (let [body (parse-body request)]
      (if-not (:agent body)
        (error-response 400 "invalid_request" "Missing required field: agent")
        (let [config (config/all @db*)]
          (handlers/chat-selected-agent-changed
           (assoc components :config config)
           {:agent (:agent body)})
          (no-content))))))

(defn handle-change-variant [{:keys [db*] :as components} request chat-id]
  (if-not (chat-or-404 db* chat-id)
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))
    (let [body (parse-body request)]
      (if-not (:variant body)
        (error-response 400 "invalid_request" "Missing required field: variant")
        (let [config (config/all @db*)]
          (handlers/chat-selected-model-changed
           (assoc components :config config)
           {:model (get-in @db* [:chats chat-id :model])
            :variant (:variant body)})
          (no-content))))))

;; --- SSE Events ---

(defn handle-events [{:keys [db*]} _request {:keys [sse-connections*]}]
  (let [pipe-out (PipedOutputStream.)
        pipe-in (PipedInputStream. pipe-out)]
    ;; Start the SSE writer on a separate thread
    (future
      (try
        (let [client (sse/add-client! sse-connections* pipe-out)
              db @db*
              config (config/all db)
              state-dump {:version (config/eca-version)
                          :protocolVersion "1.0"
                          :chats (->> (vals (:chats db))
                                      (remove :subagent)
                                      (mapv (fn [{:keys [id title status created-at]}]
                                              {:id id
                                               :title title
                                               :status (or status :idle)
                                               :createdAt created-at})))
                          :models (mapv (fn [[id _]] {:id id :name id})
                                        (:models db))
                          :agents (mapv (fn [name] {:id name :name name})
                                        (config/primary-agent-names config))
                          :mcpServers (mapv (fn [[name client-info]]
                                              {:name name :status (or (:status client-info) "unknown")})
                                            (:mcp-clients db))
                          :workspaceFolders (mapv #(shared/uri->filename (:uri %))
                                                  (:workspace-folders db))}]
          ;; Send initial state dump
          (sse/broadcast! (atom #{client}) "session:connected" state-dump)
          ;; Block to keep the connection open — writer loop handles events
          (try
            (while true
              (Thread/sleep 60000))
            (catch InterruptedException _e nil)
            (catch Exception _e nil))
          (sse/remove-client! sse-connections* client))
        (catch Exception _e nil)))
    {:status 200
     :headers {"Content-Type" "text/event-stream"
               "Cache-Control" "no-cache"
               "Connection" "keep-alive"
               "X-Accel-Buffering" "no"}
     :body pipe-in}))
