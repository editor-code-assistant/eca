(ns eca.remote.handlers
  "REST API handlers for the remote web control server.
   Each handler receives components map and Ring request, delegates to
   the same feature functions used by JSON-RPC handlers."
  (:require
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.features.chat :as f.chat]
   [eca.handlers :as handlers]
   [eca.remote.messenger :as remote.messenger]
   [eca.remote.sse :as sse]
   [eca.shared :as shared]
   [ring.core.protocols :as ring.protocols])
  (:import
   [java.io InputStream]
   [java.nio.charset StandardCharsets]
   [java.time Instant]
   [java.util Base64]))

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

(defn ^:private pending-tool-call? [[_ tc]]
  (or (= :waiting-approval (:status tc))
      ;; ask_user blocks inside its handler while :executing; surface it so
      ;; reconnecting clients can render the question input.
      (and (= :executing (:status tc))
           (= "ask_user" (:name tc)))))

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
      (config/validate-agent-name
       (or (:defaultAgent (:chat config))
           (:defaultAgent config))
       config)))

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
              (->> (vals (:chats db))
                   (remove :subagent)
                   (filter #(contains? editor-open (:id %)))
                   (mapv chat-summary)))
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
        editor-open (:editor-open-chats db)
        chats (->> (vals (:chats db))
                   (remove :subagent)
                   (filter #(contains? editor-open (:id %)))
                   (mapv chat-summary))]
    (json-response chats)))

;; --- Message pagination (opt-in via `limit`/`before`/`after` query params) -----
;;
;; Cursors are opaque tokens: base64url of "<index>.<checksum>" where checksum
;; fingerprints the message. Resolving trusts the index when its checksum still
;; matches, else rescans by checksum (tolerating shifts like flag insert/remove);
;; a cursor whose message is gone resolves to :expired (-> 409).

(def ^:private last-compaction-sentinel
  "Literal cursor value resolving to the position of the last compaction marker."
  "lastCompaction")

(defn ^:private message-checksum
  "Stable-enough fingerprint of a message to validate/resolve a cursor."
  [message]
  (Integer/toHexString (hash [(:role message) (:created-at message) (:content message)])))

(defn ^:private encode-cursor [idx message]
  (let [raw (.getBytes (str idx "." (message-checksum message)) StandardCharsets/UTF_8)]
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) raw)))

(defn ^:private decode-cursor [^String cursor]
  (try
    (let [decoded (String. (.decode (Base64/getUrlDecoder) cursor) StandardCharsets/UTF_8)
          [idx-s checksum] (string/split decoded #"\." 2)]
      {:idx (Integer/parseInt idx-s) :checksum checksum})
    (catch Exception _ nil)))

(defn ^:private last-compaction-idx
  "Index of the last compact_marker message, or nil when never compacted."
  [messages]
  (loop [i (dec (count messages))]
    (cond
      (neg? i) nil
      (= "compact_marker" (:role (nth messages i))) i
      :else (recur (dec i)))))

(defn ^:private resolve-cursor
  "Resolves an opaque cursor to an index, or :expired if its message is gone."
  [messages ^String cursor]
  (if-let [{:keys [idx checksum]} (decode-cursor cursor)]
    (if (and (<= 0 idx) (< idx (count messages))
             (= checksum (message-checksum (nth messages idx))))
      idx
      (or (first (keep-indexed (fn [i m] (when (= checksum (message-checksum m)) i)) messages))
          :expired))
    :expired))

(defn ^:private resolve-bound
  "Resolves a `before`/`after` query value to an index, :expired, or nil (absent).
   The `lastCompaction` sentinel resolves to the last compaction marker, or
   :no-compaction when the chat was never compacted."
  [messages value]
  (when value
    (if (= value last-compaction-sentinel)
      (or (last-compaction-idx messages) :no-compaction)
      (resolve-cursor messages value))))

(defn ^:private parse-limit [value]
  (when value
    (try
      (let [n (Integer/parseInt (str value))]
        (when (pos? n) n))
      (catch Exception _ nil))))

(defn ^:private paginate-messages
  "Windows `messages` by the (after, before) cursors and `limit`.

   `after` is an exclusive lower bound, `before` an exclusive upper bound. The
   page is the newest `limit` messages in the window; an opaque `after` cursor as
   the only bound pages forward (oldest `limit`). `before-cursor`/`after-cursor`
   are computed against full history so paging crosses the compaction boundary,
   and are nil at the ends.

   Returns {:messages :before-cursor :after-cursor :total :returned} or
   {:error :cursor-expired}."
  [messages {:keys [limit before after]}]
  (let [messages (vec messages)
        n (count messages)
        after-res (resolve-bound messages after)
        before-res (resolve-bound messages before)]
    (if (or (= :expired after-res) (= :expired before-res))
      {:error :cursor-expired}
      (let [lo (if (integer? after-res) after-res -1)        ;; exclusive lower
            hi (if (integer? before-res) before-res n)       ;; exclusive upper
            win-start (max 0 (min (inc lo) n))
            win-end (max win-start (min hi n))
            ;; Opaque `after` cursor pages forward; the sentinel stays newest-anchored.
            forward? (and (integer? after-res)
                          (not= after last-compaction-sentinel)
                          (nil? before-res))
            [slice-start slice-end]
            (cond
              (nil? limit) [win-start win-end]
              forward? [win-start (min win-end (+ win-start limit))]
              :else [(max win-start (- win-end limit)) win-end])
            slice (subvec messages slice-start slice-end)]
        {:messages slice
         :total n
         :returned (count slice)
         :before-cursor (when (pos? slice-start)
                          (encode-cursor slice-start (nth messages slice-start)))
         :after-cursor (when (< slice-end n)
                         (encode-cursor (dec slice-end) (nth messages (dec slice-end))))}))))

(defn ^:private compaction-cursor [messages]
  (when-let [idx (last-compaction-idx messages)]
    (encode-cursor idx (nth messages idx))))

(defn handle-get-chat [{:keys [db*]} request chat-id]
  (if-let [chat (chat-or-404 db* chat-id)]
    (let [db @db*
          config (config/all db)
          params (:params request)
          paginate? (some #(contains? params %) [:limit :before :after])
          all-messages (vec (or (:messages chat) []))
          page (when paginate?
                 (paginate-messages all-messages
                                    {:limit (parse-limit (:limit params))
                                     :before (:before params)
                                     :after (:after params)}))
          base {:id (:id chat)
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
                                      :compaction-cursor (compaction-cursor all-messages)})))

        :else
        (json-response (camel-keys base))))
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
           {:chat-id chat-id
            :model (:model body)
            :variant (:variant body)})
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
           {:chat-id chat-id
            :agent (:agent body)})
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
           {:chat-id chat-id
            :model model
            :variant (:variant body)})
          (no-content))))))

(defn handle-update-chat [{:keys [db*] :as components} request chat-id]
  (if-not (chat-or-404 db* chat-id)
    (error-response 404 "chat_not_found" (str "Chat " chat-id " does not exist"))
    (let [body (parse-body request)
          config (config/all @db*)]
      (handlers/chat-update
       (assoc components :config config)
       {:chat-id chat-id :title (:title body) :trust (:trust body)})
      (no-content))))

(defn handle-set-trust [{:keys [db*]} request {:keys [sse-connections*]}]
  (let [body (parse-body request)
        trust (boolean (:trust body))]
    (swap! db* assoc :trust trust)
    (sse/broadcast! sse-connections* "trust:updated" {:trust trust})
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

      (remote.messenger/answer-question! messenger
                                         request-id
                                         (:answer body)
                                         (:cancelled body))
      (no-content)

      :else
      (error-response 404 "question_not_found"
                      (str "No pending question for requestId " request-id)))))

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

(defn handle-mcp-add [{:keys [db*] :as components} request]
  (let [body (parse-body request)]
    (if-not (:name body)
      (error-response 400 "invalid_request" "Missing required field: name")
      (let [config (config/all @db*)
            result (handlers/mcp-add-server (assoc components :config config) body)]
        (if-let [{:keys [code message]} (:error result)]
          (error-response 400 (or code "invalid_request") (or message "Failed to add MCP server"))
          (json-response (camel-keys result)))))))

(defn handle-mcp-remove [{:keys [db*] :as components} _request server-name]
  (let [config (config/all @db*)
        result (handlers/mcp-remove-server (assoc components :config config) {:name server-name})]
    (if-let [{:keys [code message]} (:error result)]
      (error-response 400 (or code "invalid_request") (or message "Failed to remove MCP server"))
      (no-content))))

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
