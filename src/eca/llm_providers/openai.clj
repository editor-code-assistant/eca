(ns eca.llm-providers.openai
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [hato.client :as http]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[OPENAI]")

(def ^:private responses-path "/v1/responses")

(def base-url "https://api.openai.com")

(defn ^:private base-completion-request! [{:keys [rid body api-url api-key on-error on-response]}]
  (let [url (str api-url responses-path)]
    (llm-util/log-request logger-tag rid url body)
    (http/post
     url
     {:headers {"Authorization" (str "Bearer " api-key)
                "Content-Type" "application/json"}
      :body (json/generate-string body)
      :throw-exceptions? false
      :async? true
      :as :stream}
     (fn [{:keys [status body]}]
       (try
         (if (not= 200 status)
           (let [body-str (slurp body)]
             (logger/warn logger-tag "Unexpected response status: %s body: %s" status body-str)
             (on-error {:message (format "OpenAI response status: %s body: %s" status body-str)}))
           (with-open [rdr (io/reader body)]
             (doseq [[event data] (llm-util/event-data-seq rdr)]
               (llm-util/log-response logger-tag rid event data)
               (on-response event data))))
         (catch Exception e
           (on-error {:exception e}))))
     (fn [e]
       (on-error {:exception e})))))

(defn ^:private normalize-messages [past-messages]
  (keep (fn [{:keys [role content] :as msg}]
          (case role
            "tool_call" {:type "function_call"
                         :name (:name content)
                         :call_id (:id content)
                         :arguments (json/generate-string (:arguments content))}
            "tool_call_output"
            {:type "function_call_output"
             :call_id (:id content)
             :output (llm-util/stringfy-tool-result content)}
            "reason" {:type "reasoning"
                      :id (:id content)
                      :summary (if (string/blank? (:text content))
                                 []
                                 [{:type "summary_text"
                                   :text (:text content)}])
                      :encrypted_content (:external-id content)}
            (update msg :content (fn [c]
                                   (if (string? c)
                                     c
                                     (mapv #(if (= "text" (name (:type %)))
                                              (assoc % :type (if (= "user" role)
                                                               "input_text"
                                                               "output_text"))
                                              %) c))))))
        past-messages))

(defn completion! [{:keys [model user-messages instructions reason? api-key api-url
                           max-output-tokens past-messages tools web-search extra-payload]}
                   {:keys [on-message-received on-error on-prepare-tool-call on-tools-called on-reason on-usage-updated]}]
  (let [input (concat (normalize-messages past-messages)
                      (normalize-messages user-messages))
        tools (cond-> tools
                web-search (conj {:type "web_search_preview"}))
        body (merge {:model model
                     :input input
                     :prompt_cache_key (str (System/getProperty "user.name") "@ECA")
                     :parallel_tool_calls true
                     :instructions instructions
                     :tools tools
                     :include (when reason?
                                ["reasoning.encrypted_content"])
                     :store false
                     :reasoning (when reason?
                                  {:effort "medium"
                                   :summary "detailed"})
                     :stream true
                     ;; :verbosity "medium"
                     :max_output_tokens max-output-tokens}
                    extra-payload)
        tool-call-by-item-id* (atom {})
        on-response-fn
        (fn handle-response [event data]
          (case event
            ;; text
            "response.output_text.delta"
            (on-message-received {:type :text
                                  :text (:delta data)})
            ;; tools
            "response.function_call_arguments.delta" (let [call (get @tool-call-by-item-id* (:item_id data))]
                                                       (on-prepare-tool-call {:id (:id call)
                                                                              :name (:name call)
                                                                              :arguments-text (:delta data)}))

            "response.output_item.done"
            (case (:type (:item data))
              "reasoning" (on-reason {:status :finished
                                      :id (-> data :item :id)
                                      :external-id (-> data :item :encrypted_content)})
              nil)

            ;; URL mentioned
            "response.output_text.annotation.added"
            (case (-> data :annotation :type)
              "url_citation" (on-message-received
                              {:type :url
                               :title (-> data :annotation :title)
                               :url (-> data :annotation :url)})
              nil)

            ;; reasoning / tools
            "response.reasoning_summary_text.delta"
            (on-reason {:status :thinking
                        :id (:item_id data)
                        :text (:delta data)})

            "response.output_item.added"
            (case (-> data :item :type)
              "reasoning" (on-reason {:status :started
                                      :id (-> data :item :id)})
              "function_call" (let [call-id (-> data :item :call_id)
                                    item-id (-> data :item :id)
                                    function-name (-> data :item :name)
                                    function-args (-> data :item :arguments)]
                                (swap! tool-call-by-item-id* assoc item-id {:name function-name :id call-id})
                                (on-prepare-tool-call {:id call-id
                                                       :name function-name
                                                       :arguments-text function-args}))
              nil)

            ;; done
            "response.completed"
            (let [response (:response data)
                  tool-calls (keep (fn [{:keys [id call_id name arguments] :as output}]
                                     (when (= "function_call" (:type output))
                                       ;; Fallback case when the tool call was not prepared before when
                                       ;; some models/apis respond only with response.completed (skipping streaming).
                                       (when-not (get @tool-call-by-item-id* id)
                                         (swap! tool-call-by-item-id* assoc id {:name name :id call_id})
                                         (on-prepare-tool-call {:id call_id
                                                                :name name
                                                                :arguments-text arguments}))
                                       {:id call_id
                                        :item-id id
                                        :name name
                                        :arguments (json/parse-string arguments)}))
                                   (:output response))]
              (on-usage-updated {:input-tokens (-> response :usage :input_tokens)
                                 :output-tokens (-> response :usage :output_tokens)})
              (if (seq tool-calls)
                (let [{:keys [new-messages]} (on-tools-called tool-calls)
                      input (normalize-messages new-messages)]
                  (base-completion-request!
                   {:rid (llm-util/gen-rid)
                    :body (assoc body :input input)
                    :api-url api-url
                    :api-key api-key
                    :on-error on-error
                    :on-response handle-response})
                  (doseq [tool-call tool-calls]
                    (swap! tool-call-by-item-id* dissoc (:item-id tool-call))))
                (on-message-received {:type :finish
                                      :finish-reason (-> data :response :status)})))

            "response.failed" (do
                                (when-let [error (-> data :response :error)]
                                  (on-error {:message (:message error)}))
                                (on-message-received {:type :finish
                                                      :finish-reason (-> data :response :status)}))
            nil))]
    (base-completion-request!
     {:rid (llm-util/gen-rid)
      :body body
      :api-url api-url
      :api-key api-key
      :on-error on-error
      :on-response on-response-fn})))
