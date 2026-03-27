(ns eca.remote.handlers
  "REST API handlers for the remote web control server.
   Each handler receives components map and Ring request, delegates to
   the same feature functions used by JSON-RPC handlers."
  (:require
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [eca.config :as config]
   [eca.features.chat :as f.chat]
   [eca.handlers :as handlers]
   [eca.remote.sse :as sse]
   [eca.shared :as shared]
   [ring.core.protocols :as ring.protocols])
  (:import
   [java.io InputStream]
   [java.time Instant]))

(set! *warn-on-reflection* true)

(defn ^:private parse-body [request]
  (when-let [body (:body request)]
    (try
      (json/parse-string
       (if (instance? InputStream body)
         (slurp ^InputStream body)
         (str body))
       true)
      (catch Exception _e nil))))

(defn ^:private json-response
  ([status body]
   {:status status
    :headers {"Content-Type" "application/json; charset=utf-8"}
    :body (json/generate-string body)})
  ([body] (json-response 200 body)))

(defn ^:private error-response [status code message]
  (json-response status {:error {:code code :message message}}))

(defn ^:private no-content []
  {:status 204 :headers {} :body nil})

(defn ^:private chat-or-404 [db* chat-id]
  (get-in @db* [:chats chat-id]))

(defn ^:private camel-keys [m]
  (shared/map->camel-cased-map m))

(defn ^:private session-state
  "Builds the session state map used for both GET /session and SSE session:connected."
  [db config]
  (let [last-config (:last-config-notified db)
        default-model (or (get-in last-config [:chat :select-model])
                          (f.chat/default-model db config))
        default-agent-name (or (get-in last-config [:chat :select-agent])
                               (config/validate-agent-name
                                (or (:defaultAgent (:chat config))
                                    (:defaultAgent config))
                                config))
        variants (or (get-in last-config [:chat :variants]) [])
        selected-variant (get-in last-config [:chat :select-variant])]
    {:version (config/eca-version)
     :protocolVersion "1.0"
     :workspaceFolders (mapv #(shared/uri->filename (:uri %)) (:workspace-folders db))
     :models (mapv (fn [[id _]] {:id id :name id :provider (first (shared/full-model->provider+model id))})
                   (:models db))
     :agents (mapv (fn [name] {:id name :name name :description (get-in config [:agent name :description])})
                   (config/primary-agent-names config))
     :mcpServers (mapv (fn [[name client-info]]
                         {:name name :status (or (:status client-info) "unknown")})
                       (:mcp-clients db))
     :chats (let [editor-open (:chat-start-fired db)]
              (->> (vals (:chats db))
                   (remove :subagent)
                   (filter #(get editor-open (:id %)))
                   (mapv (fn [chat]
                           (camel-keys
                            {:id (:id chat)
                             :title (:title chat)
                             :status (or (:status chat) :idle)
                             :created-at (:created-at chat)
                             :updated-at (:updated-at chat)})))))
     :startedAt (when-let [ms (:started-at db)]
                  (.toString (Instant/ofEpochMilli ^long ms)))
     :welcomeMessage (handlers/welcome-message config)
     :selectModel default-model
     :selectAgent default-agent-name
     :variants variants
     :trust (boolean (:trust db))
     :selectedVariant selected-variant}))

(defn handle-root [{:keys [db*]} _request {:keys [host password]}]
  (let [url (if (:remote-private-host? @db*)
              "https://eca.dev/config/remote"
              (str "https://web.eca.dev?host=" host "&pass=" password))]
    {:status 302
     :headers {"Location" url}
     :body nil}))

(defn handle-health [_components _request]
  (json-response {:status "ok" :version (config/eca-version)}))

(defn handle-session [{:keys [db*]} _request]
  (let [db @db*
        config (config/all db)]
    (json-response (session-state db config))))

(defn handle-list-chats [{:keys [db*]} _request]
  (let [db @db*
        editor-open (:chat-start-fired db)
        chats (->> (vals (:chats db))
                   (remove :subagent)
                   (filter #(get editor-open (:id %)))
                   (mapv (fn [{:keys [id title status created-at updated-at]}]
                           {:id id
                            :title title
                            :status (or status :idle)
                            :createdAt created-at
                            :updatedAt updated-at})))]
    (json-response chats)))

(defn handle-get-chat [{:keys [db*]} _request chat-id]
  (if-let [chat (chat-or-404 db* chat-id)]
    (json-response
     (camel-keys
      {:id (:id chat)
       :title (:title chat)
       :status (or (:status chat) :idle)
       :created-at (:created-at chat)
       :updated-at (:updated-at chat)
       :messages (or (:messages chat) [])
       :task (:task chat)}))
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))))

(defn handle-prompt [{:keys [db*] :as components} request chat-id]
  (let [body (parse-body request)]
    (if-not (:message body)
      (error-response 400 "invalid_request" "Missing required field: message")
      (let [config (config/all @db*)
            params (cond-> {:chat-id chat-id
                            :message (:message body)}
                     (:model body) (assoc :model (:model body))
                     (:agent body) (assoc :agent (:agent body))
                     (:variant body) (assoc :variant (:variant body))
                     (:trust body) (assoc :trust (:trust body)))
            result (handlers/chat-prompt (assoc components :config config) params)]
        (json-response (camel-keys result))))))

(defn handle-stop [{:keys [db*] :as components} _request chat-id]
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
        (let [config (config/all @db*)
              model (or (get-in @db* [:chats chat-id :model])
                        (f.chat/default-model @db* config))]
          (handlers/chat-selected-model-changed
           (assoc components :config config)
           {:model model
            :variant (:variant body)})
          (no-content))))))

(defn handle-set-trust [{:keys [db*]} request {:keys [sse-connections*]}]
  (let [body (parse-body request)
        trust (boolean (:trust body))]
    (swap! db* assoc :trust trust)
    (sse/broadcast! sse-connections* "trust:updated" {:trust trust})
    (json-response {:trust trust})))

(defn handle-mcp-start [{:keys [db*] :as components} _request server-name]
  (let [config (config/all @db*)]
    (handlers/mcp-start-server (assoc components :config config) {:name server-name})
    (no-content)))

(defn handle-mcp-stop [{:keys [db*] :as components} _request server-name]
  (let [config (config/all @db*)]
    (handlers/mcp-stop-server (assoc components :config config) {:name server-name})
    (no-content)))

(defn handle-mcp-connect [{:keys [db*] :as components} _request server-name]
  (let [config (config/all @db*)]
    (handlers/mcp-connect-server (assoc components :config config) {:name server-name})
    (no-content)))

(defn handle-mcp-logout [{:keys [db*] :as components} _request server-name]
  (let [config (config/all @db*)]
    (handlers/mcp-logout-server (assoc components :config config) {:name server-name})
    (no-content)))

(defn handle-mcp-disable [{:keys [db*] :as components} _request server-name]
  (let [config (config/all @db*)]
    (handlers/mcp-disable-server (assoc components :config config) {:name server-name})
    (no-content)))

(defn handle-mcp-enable [{:keys [db*] :as components} _request server-name]
  (let [config (config/all @db*)]
    (handlers/mcp-enable-server (assoc components :config config) {:name server-name})
    (no-content)))

(deftype SSEBody [db* sse-connections*]
  ring.protocols/StreamableResponseBody
  (write-body-to-stream [_ _response os]
    ;; Jetty calls this on its thread with the raw servlet output stream.
    ;; We register it as an SSE client and block until the connection closes.
    (let [done-ch (async/chan)
          client (sse/add-client! sse-connections* os done-ch)]
      (try
        (let [db @db*
              config (config/all db)
              state-dump (session-state db config)]
          (sse/broadcast! (atom #{client}) "session:connected" state-dump))
        ;; Block this Jetty thread until the writer loop terminates
        ;; (client disconnect, write error, or server shutdown)
        (async/<!! done-ch)
        (finally
          (sse/remove-client! sse-connections* client))))))

(defn handle-events [{:keys [db*]} _request {:keys [sse-connections*]}]
  {:status 200
   :headers {"Content-Type" "text/event-stream"
             "Cache-Control" "no-cache"
             "Connection" "keep-alive"
             "X-Accel-Buffering" "no"}
   :body (->SSEBody db* sse-connections*)})
