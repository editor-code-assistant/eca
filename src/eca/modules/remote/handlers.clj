(ns eca.modules.remote.handlers
  "REST API handlers for the remote web control server.
   Each handler receives components map and Ring request. Actions run the
   protocol methods editors call, through the session's `:call-method`, so
   web clients get what editors get: a disabled module's methods are gone
   for them too."
  (:require
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [eca.config :as config]
   [eca.db.chats :as db.chats]
   [eca.features.chat :as f.chat]
   [eca.features.chat.history :as f.chat.history]
   [eca.modules.remote.messenger :as m.remote.messenger]
   [eca.modules.remote.sse :as m.remote.sse]
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

(def ^:private method-not-found
  "What the session's `:call-method` gives for a method no active module
   serves, like eca/mcp's when it's off."
  :eca.server/method-not-found)

(defn ^:private call!
  "Runs protocol method `method` of `kind` as an editor's would run, on this
   thread, through the session's `:call-method`: what it returns, or
   `method-not-found`."
  [components kind method params]
  ((:call-method components) kind method params))

(defn ^:private method-not-found-response [method]
  (error-response 404 "method_not_found" (str "Method " method " not found")))

(defn ^:private chat-or-404 [db* chat-id]
  (get-in @db* [:chats chat-id]))

(defn ^:private camel-keys [m]
  (shared/map->camel-cased-map m))

(defn ^:private pending-tool-call? [[_ tc]]
  (or (= :waiting-approval (:status tc))
      ;; a tool that waits for the user's answer, like ask_user, blocks inside
      ;; its handler while :executing; surface it so reconnecting clients can
      ;; render the question input.
      (and (= :executing (:status tc))
           (:asks-user? tc))))

(defn ^:private pending-tool-calls
  "Tool calls awaiting user interaction, shaped for the REST API so clients
   that missed the SSE events can still render the approval card or question."
  [chat]
  (->> (:tool-calls chat)
       (filter pending-tool-call?)
       (mapv (fn [[id tc]]
               (shared/assoc-some
                {:id id
                 :name (:name tc)
                 :server (:server tc)
                 :origin (:origin tc)
                 :arguments (:arguments tc)
                 :manual-approval (boolean (:manual-approval tc))}
                :summary (:summary tc)
                :details (:details tc)
                :request-id (:ask-question-request-id tc))))))

(defn ^:private pending-approval-count [chat]
  (->> (:tool-calls chat) (filter (fn [[_ tc]] (= :waiting-approval (:status tc)))) count))

(defn ^:private chat-summary [chat]
  (camel-keys
   {:id (:id chat)
    :title (:title chat)
    :status (or (:status chat) :idle)
    :created-at (:created-at chat)
    :updated-at (:updated-at chat)
    :pending-approval-count (pending-approval-count chat)}))

(defn ^:private resolve-default-model
  "Session-level default model: explicit selection from last notified config,
   falling back to the computed default."
  [db config]
  (or (get-in db [:last-config-notified :chat :select-model])
      (f.chat/default-model db config)))

(defn ^:private resolve-default-agent
  "Session-level default agent: explicit selection from last notified config,
   falling back to the validated configured default agent."
  [db config]
  (or (get-in db [:last-config-notified :chat :select-agent])
      (config/validate-agent-name (config/default-agent-name config) config)))

(defn ^:private resolve-default-variant
  "Session-level selected variant, may be nil when none is selected."
  [db]
  (get-in db [:last-config-notified :chat :select-variant]))

(defn ^:private session-state
  "Builds the session state map used for both GET /session and SSE session:connected."
  [db config]
  (let [last-config (:last-config-notified db)
        default-model (resolve-default-model db config)
        default-agent-name (resolve-default-agent db config)
        variants (or (get-in last-config [:chat :variants]) [])
        selected-variant (resolve-default-variant db)]
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
     :chats (let [editor-open (:editor-open-chats db)]
              (->> (:chats db)
                   (remove (fn [[_ chat]] (:subagent chat)))
                   (filter (fn [[id _]] (contains? editor-open id)))
                   (mapv (fn [[id chat]] (chat-summary (assoc chat :id id))))))
     :startedAt (when-let [ms (:started-at db)]
                  (.toString (Instant/ofEpochMilli ^long ms)))
     :welcomeMessage (config/welcome-message config)
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
        editor-open (:editor-open-chats db)
        chats (->> (:chats db)
                   (remove (fn [[_ chat]] (:subagent chat)))
                   (filter (fn [[id _]] (contains? editor-open id)))
                   (mapv (fn [[id chat]] (chat-summary (assoc chat :id id)))))]
    (json-response chats)))

(defn handle-get-chat [{:keys [db* metrics]} request chat-id]
  (db.chats/hydrate-chat! db* chat-id metrics)
  (if-let [chat (chat-or-404 db* chat-id)]
    (let [db @db*
          config (config/all db)
          params (:params request)
          paginate? (some #(contains? params %) [:limit :before :after])
          all-messages (vec (or (:messages chat) []))
          page (when paginate?
                 (f.chat.history/window-messages all-messages
                                                 {:limit (:limit params)
                                                  :before (:before params)
                                                  :after (:after params)}))
          base {:id chat-id
                :title (:title chat)
                :status (or (:status chat) :idle)
                :created-at (:created-at chat)
                :updated-at (:updated-at chat)
                :messages (if paginate? (:messages page) all-messages)
                :task (:task chat)
                :pending-tool-calls (pending-tool-calls chat)
                ;; Effective selection: per-chat override if set, otherwise the
                ;; resolved session-level default. :variant may legitimately be nil.
                :model (or (:model chat) (resolve-default-model db config))
                :variant (or (:variant chat) (resolve-default-variant db))
                :agent (or (:agent chat) (resolve-default-agent db config))}]
      (cond
        (= :cursor-expired (:error page))
        (error-response 409 "cursor_expired"
                        "Cursor no longer points to an existing message; refetch the latest page")

        paginate?
        (json-response
         (camel-keys
          (assoc base :messages-meta {:total (:total page)
                                      :returned (:returned page)
                                      :before-cursor (:before-cursor page)
                                      :after-cursor (:after-cursor page)
                                      :compaction-cursor (f.chat.history/compaction-cursor all-messages)})))

        :else
        (json-response (camel-keys base))))
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))))

(defn handle-prompt [components request chat-id]
  (let [body (parse-body request)]
    (if-not (:message body)
      (error-response 400 "invalid_request" "Missing required field: message")
      (let [params (cond-> {:chat-id chat-id
                            :message (:message body)}
                     (:model body) (assoc :model (:model body))
                     (:agent body) (assoc :agent (:agent body))
                     (:variant body) (assoc :variant (:variant body))
                     (:trust body) (assoc :trust (:trust body)))
            result (call! components :request "chat/prompt" params)]
        (json-response (camel-keys result))))))

(defn handle-stop [{:keys [db*] :as components} _request chat-id]
  (if-not (chat-or-404 db* chat-id)
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))
    (if-not (identical? :running (get-in @db* [:chats chat-id :status]))
      (error-response 409 "chat_wrong_status" "Chat is not running")
      (do (call! components :notification "chat/promptStop" {:chat-id chat-id})
          (no-content)))))

(defn handle-approve
  "Body, optional: {:save \"session\"} to remember the approval, as editors'
   \"Accept and remember\"."
  [{:keys [db*] :as components} request chat-id tool-call-id]
  (if-not (chat-or-404 db* chat-id)
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))
    (if-not (get-in @db* [:chats chat-id :tool-calls tool-call-id])
      (error-response 404 "tool_call_not_found" (str "Tool call " tool-call-id " does not exist"))
      (do (call! components :notification "chat/toolCallApprove"
                 (shared/assoc-some {:chat-id chat-id :tool-call-id tool-call-id}
                                    :save (:save (parse-body request))))
          (no-content)))))

(defn handle-reject [{:keys [db*] :as components} _request chat-id tool-call-id]
  (if-not (chat-or-404 db* chat-id)
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))
    (if-not (get-in @db* [:chats chat-id :tool-calls tool-call-id])
      (error-response 404 "tool_call_not_found" (str "Tool call " tool-call-id " does not exist"))
      (do (call! components :notification "chat/toolCallReject" {:chat-id chat-id :tool-call-id tool-call-id})
          (no-content)))))

(defn handle-rollback [{:keys [db*] :as components} request chat-id]
  (if-not (chat-or-404 db* chat-id)
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))
    (let [body (parse-body request)
          {:keys [error]} (call! components :request "chat/rollback" {:chat-id chat-id :content-id (:contentId body)})]
      (if error
        (error-response (if (= "invalid_request" (:code error)) 400 404)
                        (:code error)
                        (:message error))
        (no-content)))))

(defn handle-clear [{:keys [db*] :as components} _request chat-id]
  (if-not (chat-or-404 db* chat-id)
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))
    (do (call! components :request "chat/clear" {:chat-id chat-id :messages true})
        (no-content))))

(defn handle-delete-chat [{:keys [db*] :as components} _request chat-id]
  (if-not (chat-or-404 db* chat-id)
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))
    (do (call! components :request "chat/delete" {:chat-id chat-id})
        (no-content))))

(defn handle-change-model [{:keys [db*] :as components} request chat-id]
  (if-not (chat-or-404 db* chat-id)
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))
    (let [body (parse-body request)]
      (if-not (:model body)
        (error-response 400 "invalid_request" "Missing required field: model")
        (do (call! components :notification "chat/selectedModelChanged"
                   {:chat-id chat-id :model (:model body) :variant (:variant body)})
            (no-content))))))

(defn handle-change-agent [{:keys [db* messenger] :as components} request chat-id]
  (if-not (chat-or-404 db* chat-id)
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))
    (let [body (parse-body request)]
      (if-not (:agent body)
        (error-response 400 "invalid_request" "Missing required field: agent")
        (do
          (call! components :notification "chat/selectedAgentChanged" {:chat-id chat-id :agent (:agent body)})
          ;; The editor showing this chat must learn it too, otherwise its
          ;; next prompt sends its stale agent and reverts this change.
          (config/notify-selected-agent-changed!
           (get-in @db* [:chats chat-id :agent]) db* messenger chat-id)
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
          (call! components :notification "chat/selectedModelChanged"
                 {:chat-id chat-id :model model :variant (:variant body)})
          (no-content))))))

(defn handle-update-chat [{:keys [db*] :as components} request chat-id]
  (if-not (chat-or-404 db* chat-id)
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))
    (let [body (parse-body request)]
      (call! components :request "chat/update" {:chat-id chat-id :title (:title body) :trust (:trust body)})
      (no-content))))

(defn handle-steer
  "Body: {:message String}, for the running prompt's next turn (or a command
   that runs at once), as `chat/promptSteer`."
  [{:keys [db*] :as components} request chat-id]
  (if-not (chat-or-404 db* chat-id)
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))
    (let [body (parse-body request)]
      (if-not (:message body)
        (error-response 400 "invalid_request" "Missing required field: message")
        (do (call! components :notification "chat/promptSteer" {:chat-id chat-id :message (:message body)})
            (no-content))))))

(defn handle-remove-steer
  "Drops the steer message the running prompt hasn't taken yet, as
   `chat/promptSteerRemove`."
  [{:keys [db*] :as components} _request chat-id]
  (if-not (chat-or-404 db* chat-id)
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))
    (do (call! components :notification "chat/promptSteerRemove" {:chat-id chat-id})
        (no-content))))

(defn handle-add-flag
  "Body: {:contentId String :text String}: a flag after that message, as
   `chat/addFlag`."
  [{:keys [db*] :as components} request chat-id]
  (if-not (chat-or-404 db* chat-id)
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))
    (let [{:keys [contentId text]} (parse-body request)]
      (cond
        (not contentId) (error-response 400 "invalid_request" "Missing required field: contentId")
        (not text) (error-response 400 "invalid_request" "Missing required field: text")
        :else (do (call! components :request "chat/addFlag" {:chat-id chat-id :content-id contentId :text text})
                  (no-content))))))

(defn handle-remove-flag [{:keys [db*] :as components} _request chat-id content-id]
  (if-not (chat-or-404 db* chat-id)
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))
    (do (call! components :request "chat/removeFlag" {:chat-id chat-id :content-id content-id})
        (no-content))))

(defn handle-fork
  "Body: {:contentId String}: a new chat with the messages up to that one, as
   `chat/fork`; the new chat reaches clients as `chat:opened` and its
   contents. Answers {:chatId ..}, or 404 when no message has that id."
  [{:keys [db*] :as components} request chat-id]
  (if-not (chat-or-404 db* chat-id)
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))
    (let [{:keys [contentId]} (parse-body request)]
      (if-not contentId
        (error-response 400 "invalid_request" "Missing required field: contentId")
        (let [result (call! components :request "chat/fork" {:chat-id chat-id :content-id contentId})]
          (if (:chat-id result)
            (json-response (camel-keys result))
            (error-response 404 "content_not_found"
                            (str "No message with contentId " contentId " in chat " chat-id))))))))

(defn handle-set-trust [{:keys [db*]} request {:keys [sse-connections*]}]
  (let [body (parse-body request)
        trust (boolean (:trust body))]
    (swap! db* assoc :trust trust)
    (m.remote.sse/broadcast! sse-connections* "trust:updated" {:trust trust})
    (json-response {:trust trust})))

(defn handle-answer-question
  "Resolves a pending question previously asked via the SSE `chat:ask-question`
   event. Body: {:requestId String :answer (String|nil) :cancelled Boolean}.
   Returns 204 on success, 400 if `requestId` is missing or blank, 404 if the
   requestId is unknown (e.g. already answered or never registered)."
  [{:keys [messenger]} request]
  (let [body (parse-body request)
        request-id (:requestId body)]
    (cond
      (not (and (string? request-id) (seq request-id)))
      (error-response 400 "invalid_request" "Missing required field: requestId")

      (m.remote.messenger/answer-question! messenger
                                           request-id
                                           (:answer body)
                                           (:cancelled body))
      (no-content)

      :else
      (error-response 404 "question_not_found"
                      (str "No pending question for requestId " request-id)))))

(defn ^:private mcp-action
  "Runs eca/mcp's notification `method` on MCP server `server-name`: 204, or
   404 when eca/mcp is off."
  [components method server-name]
  (if (= method-not-found (call! components :notification method {:name server-name}))
    (method-not-found-response method)
    (no-content)))

(defn handle-mcp-start [components _request server-name]
  (mcp-action components "mcp/startServer" server-name))

(defn handle-mcp-stop [components _request server-name]
  (mcp-action components "mcp/stopServer" server-name))

(defn handle-mcp-connect [components _request server-name]
  (mcp-action components "mcp/connectServer" server-name))

(defn handle-mcp-logout [components _request server-name]
  (mcp-action components "mcp/logoutServer" server-name))

(defn handle-mcp-disable [components _request server-name]
  (mcp-action components "mcp/disableServer" server-name))

(defn handle-mcp-enable [components _request server-name]
  (mcp-action components "mcp/enableServer" server-name))

(defn handle-mcp-add [components request]
  (let [body (parse-body request)]
    (if-not (:name body)
      (error-response 400 "invalid_request" "Missing required field: name")
      (let [result (call! components :request "mcp/addServer" body)]
        (cond
          (= method-not-found result)
          (method-not-found-response "mcp/addServer")

          (:error result)
          (let [{:keys [code message]} (:error result)]
            (error-response 400 (or code "invalid_request") (or message "Failed to add MCP server")))

          :else
          (json-response (camel-keys result)))))))

(defn handle-mcp-remove [components _request server-name]
  (let [result (call! components :request "mcp/removeServer" {:name server-name})]
    (cond
      (= method-not-found result)
      (method-not-found-response "mcp/removeServer")

      (:error result)
      (let [{:keys [code message]} (:error result)]
        (error-response 400 (or code "invalid_request") (or message "Failed to remove MCP server")))

      :else
      (no-content))))

(defn ^:private json-method
  "Runs request `method` with `params` and answers its result, as editors get
   it, or 404 when no active module serves it."
  [components method params]
  (let [result (call! components :request method params)]
    (if (= method-not-found result)
      (method-not-found-response method)
      (json-response (camel-keys result)))))

(defn handle-jobs-list [components _request]
  (json-method components "jobs/list" {}))

(defn handle-job-output [components _request job-id]
  (json-method components "jobs/readOutput" {:job-id job-id}))

(defn handle-job-kill [components _request job-id]
  (json-method components "jobs/kill" {:job-id job-id}))

(deftype SSEBody [db* sse-connections*]
  ring.protocols/StreamableResponseBody
  (write-body-to-stream [_ _response os]
    ;; Jetty calls this on its thread with the raw servlet output stream.
    ;; We register it as an SSE client and block until the connection closes.
    (let [done-ch (async/chan)
          client (m.remote.sse/add-client! sse-connections* os done-ch)]
      (try
        (let [db @db*
              config (config/all db)
              state-dump (session-state db config)]
          (m.remote.sse/broadcast! (atom #{client}) "session:connected" state-dump))
        ;; Block this Jetty thread until the writer loop terminates
        ;; (client disconnect, write error, or server shutdown)
        (async/<!! done-ch)
        (finally
          (m.remote.sse/remove-client! sse-connections* client))))))

(defn handle-events [{:keys [db*]} _request {:keys [sse-connections*]}]
  {:status 200
   :headers {"Content-Type" "text/event-stream"
             "Cache-Control" "no-cache"
             "Connection" "keep-alive"
             "X-Accel-Buffering" "no"}
   :body (->SSEBody db* sse-connections*)})
