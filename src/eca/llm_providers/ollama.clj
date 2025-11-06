(ns eca.llm-providers.ollama
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.shared :refer [deep-merge]]
   [hato.client :as http]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[OLLAMA]")

(def ^:private chat-url "%s/api/chat")
(def ^:private list-models-url "%s/api/tags")
(def ^:private show-model-url "%s/api/show")

(defn list-models [{:keys [api-url]}]
  (try
    (let [rid (llm-util/gen-rid)
          {:keys [status body]} (http/get
                                 (format list-models-url api-url)
                                 {:throw-exceptions? false
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
                                 (format show-model-url api-url)
                                 {:throw-exceptions? false
                                  :body (json/generate-string {:model model})
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

(defn ^:private base-chat-request! [{:keys [rid url body on-error on-stream]}]
  (let [reason-id (str (random-uuid))
        reasoning?* (atom false)
        response* (atom nil)
        on-error (if on-stream
                   on-error
                   (fn [error-data]
                     (llm-util/log-response logger-tag rid "response-error" body)
                     (reset! response* error-data)))]
    (llm-util/log-request logger-tag rid url body)
    @(http/post
      url
      {:body (json/generate-string body)
       :throw-exceptions? false
       :async? true
       :as (if on-stream :stream :json)}
      (fn [{:keys [status body]}]
        (try
          (if (not= 200 status)
            (let [body-str (if on-stream (slurp body) body)]
              (logger/warn logger-tag (format "Unexpected response status: %s body: %s" status body-str))
              (on-error {:message (format "Ollama response status: %s body: %s" status body-str)}))
            (if on-stream
              (with-open [rdr (io/reader body)]
                (doseq [[event data] (llm-util/event-data-seq rdr)]
                  (llm-util/log-response logger-tag rid event data)
                  (on-stream rid event data reasoning?* reason-id)))
              (do
                (llm-util/log-response logger-tag rid "response" body)
                (reset! response*
                        {:output-text (:content (:message body))}))))
          (catch Exception e
            (on-error {:exception e}))))
      (fn [e]
        (on-error {:exception e})))
    @response*))

(defn ^:private ->tools [tools]
  (mapv (fn [tool]
          {:type "function"
           :function (-> (select-keys tool [:description :parameters])
                         (assoc :name (:full-name tool)))})
        tools))

(defn ^:private normalize-messages [past-messages]
  (mapv (fn [{:keys [role content] :as msg}]
          (case role
            "tool_call" {:role "assistant" :tool-calls [{:type "function"
                                                         :function (-> content
                                                                       (assoc :name (:full-name content))
                                                                       (dissoc :full-name))}]}
            "tool_call_output" {:role "tool" :content (llm-util/stringfy-tool-result content)}
            "reason" {:role "assistant" :content (:text content)}
            {:role (:role msg)
             :content (if (string? (:content msg))
                        (:content msg)
                        (-> msg :content first :text))
             ;; TODO add image supprt
             ;; :images []
             }))
        past-messages))

(defn chat! [{:keys [model user-messages reason? instructions api-url past-messages tools]}
             {:keys [on-message-received on-error on-prepare-tool-call on-tools-called
                     on-reason extra-payload] :as callbacks}]
  (let [messages (concat
                  (normalize-messages (concat [{:role "system" :content instructions}] past-messages))
                  (normalize-messages user-messages))
        stream? (boolean callbacks)
        body (deep-merge
              {:model model
               :messages messages
               :think reason?
               :tools (->tools tools)
               :stream stream?}
              extra-payload)
        url (format chat-url api-url)
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
                                              :arguments (:arguments function)}]
                               (on-prepare-tool-call (assoc tool-call :arguments-text ""))
                               (swap! tool-calls* assoc rid tool-call))

                             done_reason
                             (if-let [tool-call (get @tool-calls* rid)]
                                 ;; TODO support multiple tool calls
                               (when-let [{:keys [new-messages]} (on-tools-called [tool-call])]
                                 (swap! tool-calls* dissoc rid)
                                 (base-chat-request!
                                  {:rid (llm-util/gen-rid)
                                   :url url
                                   :body (assoc body :messages (normalize-messages new-messages))
                                   :on-error on-error
                                   :on-stream handle-stream}))
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
      :on-error on-error
      :on-stream on-stream-fn})))
