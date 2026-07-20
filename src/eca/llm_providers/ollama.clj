(ns eca.llm-providers.ollama
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [eca.client-http :as client]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.message-sanitize :as message-sanitize]
   [eca.shared :refer [deep-merge join-api-url]]
   [hato.client :as http]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[OLLAMA]")

(def ^:private chat-path "/api/chat")
(def ^:private list-models-path "/api/tags")
(def ^:private show-model-path "/api/show")

(defn list-models [{:keys [api-url]}]
  (try
    (let [rid (llm-util/gen-rid)
          {:keys [status body]} (http/get
                                 (join-api-url api-url list-models-path)
                                 {:throw-exceptions? false
                                  :http-client (client/merge-with-global-http-client {})
                                  :as :json})]
      (if (= 200 status)
        (do
          (llm-util/log-response logger-tag rid "api/tags" body)
          (:models body))
        (do
          (logger/warn logger-tag "Unknown status code:" status)
          [])))
    (catch Exception _
      [])))

(defn model-capabilities [{:keys [model api-url]}]
  (try
    (let [rid (llm-util/gen-rid)
          {:keys [status body]} (http/post
                                 (join-api-url api-url show-model-path)
                                 {:throw-exceptions? false
                                  :body (json/generate-string {:model model})
                                  :http-client (client/merge-with-global-http-client {})
                                  :as :json})]
      (if (= 200 status)
        (do
          (llm-util/log-response logger-tag rid "api/show" body)
          (:capabilities body))
        (do
          (logger/warn logger-tag "Unknown status code:" status)
          [])))
    (catch Exception e
      (logger/warn logger-tag "Error getting model:" (ex-message e))
      [])))

(defn ^:private base-chat-request! [{:keys [rid url body on-error on-stream extra-headers]}]
  (let [reason-id (str (random-uuid))
        reasoning?* (atom false)
        response* (atom nil)
        headers (client/merge-llm-headers (merge {} extra-headers))
        on-error (if on-stream
                   on-error
                   (fn [error-data]
                     (llm-util/log-response logger-tag rid "response-error" body)
                     (reset! response* error-data)))]
    (llm-util/log-request logger-tag rid url body headers)
    (try
      (let [{:keys [status body]} (http/post
                                   url
                                   {:headers headers
                                    :body (json/generate-string body)
                                    :throw-exceptions? false
                                    :http-client (client/merge-with-global-http-client {})
                                    :as (if on-stream :stream :json)})]
        (if (not= 200 status)
          (let [body-str (if on-stream (slurp body) body)]
            (logger/warn logger-tag (format "Unexpected response status: %s body: %s" status body-str))
            (on-error {:message (format "Ollama response status: %s body: %s" status body-str)
                       :status status
                       :body body-str}))
          (if on-stream
            (with-open [rdr (io/reader body)]
              (doseq [[event data] (llm-util/event-data-seq rdr)]
                (llm-util/log-response logger-tag rid event data)
                (on-stream rid event data reasoning?* reason-id)))
            (do
              (llm-util/log-response logger-tag rid "response" body)
              (reset! response*
                      {:output-text (:content (:message body))})))))
      (catch Exception e
        (on-error {:exception e
                   :message (llm-util/connection-error-message e)})))
    @response*))

(defn ^:private ->tools [tools]
  (mapv (fn [tool]
          {:type "function"
           :function (-> (select-keys tool [:description :parameters])
                         (assoc :name (:full-name tool)))})
        tools))

(defn ^:private normalize-messages [messages]
  ;; Defense-in-depth against #209: skip entries whose :content :api was
  ;; tagged by another provider. Ollama's chat shape doesn't carry
  ;; provider-specific opaque ids, but foreign tool_call/server_tool_*
  ;; entries can still confuse the request. The central sanitizer in
  ;; eca.llm-api drops these first; this guard protects direct callers.
  (into []
        (keep (fn [{:keys [role content] :as msg}]
                (let [foreign-api? (let [origin (:api content)]
                                     (and origin (not= :ollama origin)))]
                  (case role
                    "tool_call" (when-not foreign-api?
                                  {:role "assistant"
                                   :tool-calls [{:type "function"
                                                 :function (-> content
                                                               (assoc :name (:full-name content))
                                                               (dissoc :full-name))}]})
                    ;; NOTE: Image content from MCP tool results is flattened to
                    ;; placeholder text via `stringfy-tool-result`. Image round-trip
                    ;; is implemented for openai-responses and anthropic; see those
                    ;; providers' `tool_call_output` branches for the pattern.
                    "tool_call_output" (when-not foreign-api?
                                         {:role "tool"
                                          :content (llm-util/stringfy-tool-result content)})
                    "reason" (when-not foreign-api?
                               {:role "assistant" :content (:text content)})
                    "server_tool_use" nil
                    "server_tool_result" nil
                    {:role (:role msg)
                     :content (if (string? (:content msg))
                                (:content msg)
                                (-> msg :content first :text))
                     ;; TODO add image supprt
                     ;; :images []
                     }))))
        messages))

(defn chat! [{:keys [model user-messages reason? instructions api-url past-messages tools max-output-tokens
                     extra-headers extra-payload]}
             {:keys [on-message-received on-error on-prepare-tool-call on-tools-called
                     on-reason] :as callbacks}]
  (let [messages (concat
                  (normalize-messages (concat [{:role "system" :content instructions}] past-messages))
                  (normalize-messages user-messages))
        stream? (boolean callbacks)
        body (deep-merge
              (cond-> {:model model
                       :messages messages
                       :think reason?
                       :tools (->tools tools)
                       :stream stream?}
                max-output-tokens
                (assoc :options {:num_predict max-output-tokens}))
              extra-payload)
        url (join-api-url api-url chat-path)
        tool-calls* (atom {})
        on-stream-fn (when stream?
                       (fn handle-stream [rid _event data reasoning?* reason-id]
                         (let [{:keys [message done_reason]} data]
                           (cond
                             (seq (:tool_calls message))
                             (let [function (:function (first (seq (:tool_calls message))))
                                   call-id (str (random-uuid))
                                   tool-call {:id call-id
                                              :full-name (:name function)
                                              :arguments (update-keys (:arguments function) name)}]
                               (on-prepare-tool-call (assoc tool-call :arguments-text ""))
                               (swap! tool-calls* assoc rid tool-call))

                             done_reason
                             (if-let [tool-call (get @tool-calls* rid)]
                                 ;; TODO support multiple tool calls
                               (when-let [{:keys [new-messages tools]} (on-tools-called [tool-call])]
                                 (let [new-messages (message-sanitize/sanitize-outbound-messages new-messages)]
                                   (swap! tool-calls* dissoc rid)
                                   (base-chat-request!
                                    {:rid (llm-util/gen-rid)
                                     :url url
                                     :body (assoc body :messages (normalize-messages new-messages)
                                                  :tools (->tools tools))
                                     :extra-headers extra-headers
                                     :on-error on-error
                                     :on-stream handle-stream})))
                               (on-message-received {:type :finish
                                                     :finish-reason done_reason}))

                             message
                             (if (:thinking message)
                               (do
                                 (when-not @reasoning?*
                                   (on-reason {:status :started
                                               :id reason-id})
                                   (reset! reasoning?* true))
                                 (on-reason {:status :thinking
                                             :id reason-id
                                             :text (:thinking message)}))
                               (do
                                 (when @reasoning?*
                                   (on-reason {:status :finished
                                               :id reason-id})
                                   (reset! reasoning?* false))
                                 (on-message-received {:type :text
                                                       :text (:content message)})))))))]
    (base-chat-request!
     {:rid (llm-util/gen-rid)
      :url url
      :body body
      :extra-headers extra-headers
      :on-error on-error
      :on-stream on-stream-fn})))
