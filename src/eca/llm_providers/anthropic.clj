(ns eca.llm-providers.anthropic
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.llm-providers.auth :as llm-providers.auth]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.shared :as shared :refer [assoc-some]]
   [hato.client :as http]
   [ring.util.codec :as ring.util]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[ANTHROPIC]")

(def ^:private messages-path "/v1/messages")

(def base-url "https://api.anthropic.com")

(defn ^:private ->tools [tools web-search]
  (cond->
   (mapv (fn [tool]
           (assoc (select-keys tool [:name :description])
                  :input_schema (:parameters tool))) tools)
    web-search (conj {:type "web_search_20250305"
                      :name "web_search"
                      :max_uses 10
                      :cache_control {:type "ephemeral"}})))

(defn ^:private base-request! [{:keys [rid body api-url api-key content-block* on-error on-response]}]
  (let [url (str api-url messages-path)
        reason-id (str (random-uuid))]
    (llm-util/log-request logger-tag rid url body)
    (http/post
     url
     {:headers {"x-api-key" api-key
                "anthropic-version" "2023-06-01"
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
             (on-error {:message (format "Anthropic response status: %s body: %s" status body-str)}))
           (with-open [rdr (io/reader body)]
             (doseq [[event data] (llm-util/event-data-seq rdr)]
               (llm-util/log-response logger-tag rid event data)
               (on-response event data content-block* reason-id))))
         (catch Exception e
           (on-error {:exception e}))))
     (fn [e]
       (on-error {:exception e})))))

(defn ^:private normalize-messages [past-messages]
  (mapv (fn [{:keys [role content] :as msg}]
          (case role
            "tool_call" {:role "assistant"
                         :content [{:type "tool_use"
                                    :id (:id content)
                                    :name (:name content)
                                    :input (or (:arguments content) {})}]}

            "tool_call_output"
            {:role "user"
             :content [{:type "tool_result"
                        :tool_use_id (:id content)
                        :content (llm-util/stringfy-tool-result content)}]}
            "reason"
            {:role "assistant"
             :content [{:type "thinking"
                        :signature (:external-id content)
                        :thinking (:text content)}]}
            (update msg :content (fn [c]
                                   (if (string? c)
                                     (string/trim c)
                                     (mapv #(if (= "text" (name (:type %)))
                                              (update % :text string/trim)
                                              %)
                                           c))))))
        past-messages))

(defn ^:private add-cache-to-last-message [messages]
  ;; TODO add cache_control to last non thinking message
  (shared/update-last
   (vec messages)
   (fn [message]
     (let [content (get-in message [:content])]
       (if (string? content)
         (assoc-in message [:content] [{:type :text
                                        :text content
                                        :cache_control {:type "ephemeral"}}])
         (assoc-in message [:content 0 :cache_control] {:type "ephemeral"}))))))

(defn completion!
  [{:keys [model user-messages temperature instructions max-output-tokens
           api-url api-key reason? reason-tokens past-messages tools web-search]
    :or {temperature 1.0}}
   {:keys [on-message-received on-error on-reason on-prepare-tool-call on-tool-called on-usage-updated]}]
  (let [messages (concat (normalize-messages past-messages)
                         (normalize-messages user-messages))
        body (assoc-some
              {:model model
               :messages (add-cache-to-last-message messages)
               :max_tokens max-output-tokens
               :temperature temperature
               :stream true
               :tools (->tools tools web-search)
               :system [{:type "text" :text instructions :cache_control {:type "ephemeral"}}]}
              :thinking (when (and reason? reason-tokens (> reason-tokens 0))
                          {:type "enabled"
                           :budget_tokens reason-tokens}))

        on-response-fn
        (fn handle-response [event data content-block* reason-id]
          (case event
            "content_block_start" (case (-> data :content_block :type)
                                    "thinking" (do
                                                 (on-reason {:status :started
                                                             :id reason-id})
                                                 (swap! content-block* assoc (:index data) (:content_block data)))
                                    "tool_use" (do
                                                 (on-prepare-tool-call {:name (-> data :content_block :name)
                                                                        :id (-> data :content_block :id)
                                                                        :arguments-text ""})
                                                 (swap! content-block* assoc (:index data) (:content_block data)))

                                    nil)
            "content_block_delta" (case (-> data :delta :type)
                                    "text_delta" (on-message-received {:type :text
                                                                       :text (-> data :delta :text)})
                                    "input_json_delta" (let [text (-> data :delta :partial_json)
                                                             _ (swap! content-block* update-in [(:index data) :input-json] str text)
                                                             content-block (get @content-block* (:index data))]
                                                         (on-prepare-tool-call {:name (:name content-block)
                                                                                :id (:id content-block)
                                                                                :arguments-text text}))
                                    "citations_delta" (case (-> data :delta :citation :type)
                                                        "web_search_result_location" (on-message-received
                                                                                      {:type :url
                                                                                       :title (-> data :delta :citation :title)
                                                                                       :url (-> data :delta :citation :url)})
                                                        nil)
                                    "thinking_delta" (on-reason {:status :thinking
                                                                 :id reason-id
                                                                 :text (-> data :delta :thinking)})
                                    "signature_delta" (on-reason {:status :finished
                                                                  :external-id (-> data :delta :signature)
                                                                  :id reason-id})
                                    nil)
            "message_delta" (do
                              (when-let [usage (and (-> data :delta :stop_reason)
                                                    (:usage data))]
                                (on-usage-updated {:input-tokens (:input_tokens usage)
                                                   :input-cache-creation-tokens (:cache_creation_input_tokens usage)
                                                   :input-cache-read-tokens (:cache_read_input_tokens usage)
                                                   :output-tokens (:output_tokens usage)}))
                              (case (-> data :delta :stop_reason)
                                "tool_use" (doseq [content-block (vals @content-block*)]
                                             (when (= "tool_use" (:type content-block))
                                               (let [function-name (:name content-block)
                                                     function-args (:input-json content-block)
                                                     {:keys [new-messages]} (on-tool-called {:id (:id content-block)
                                                                                             :name function-name
                                                                                             :arguments (json/parse-string function-args)})
                                                     messages (-> (normalize-messages new-messages)
                                                                  add-cache-to-last-message)]
                                                 (base-request!
                                                  {:rid (llm-util/gen-rid)
                                                   :body (assoc body :messages messages)
                                                   :api-url api-url
                                                   :api-key api-key
                                                   :content-block* (atom nil)
                                                   :on-error on-error
                                                   :on-response handle-response}))))
                                "end_turn" (do
                                             (reset! content-block* {})
                                             (on-message-received {:type :finish
                                                                   :finish-reason (-> data :delta :stop_reason)}))
                                "max_tokens" (on-message-received {:type :limit-reached
                                                                   :tokens (:usage data)})
                                nil))
            nil))]
    (base-request!
     {:rid (llm-util/gen-rid)
      :body body
      :api-url api-url
      :api-key api-key
      :content-block* (atom nil)
      :on-error on-error
      :on-response on-response-fn})))

(def client-id "9d1c250a-e61b-44d9-88ed-5944d1962f5e")

(defn authorization-oauth [mode]
  (let [url (str (if (= :console mode) "https://console.anthropic.com" "https://claude.ai") "/oauth/authorize")
        {:keys [challenge verifier]} (llm-providers.auth/generate-pkce)]
    {:verifier verifier
     :url (str url "?" (ring.util/form-encode {:code true
                                               :client_id client-id
                                               :response_type "code"
                                               :redirect_uri "https://console.anthropic.com/oauth/code/callback"
                                               :scope "org:create_api_key user:inference user:profile"
                                               :code_challenge challenge
                                               :code_challenge_method "S256"
                                               :state verifier}))}))

(defn exchange [code verifier]
  (let [[code state] (string/split code #"#")
        url "https://console.anthropic.com/v1/oauth/token"
        body {:grant_type "authorization_code"
              :code code
              :state state
              :client_id client-id
              :redirect_uri "https://console.anthropic.com/oauth/code/callback"
              :code_verifier verifier}
        {:keys [status body]} (http/post
                               url
                               {:headers {"Content-Type" "application/json"}
                                :body (json/generate-string body)
                                :as :json})]
    (if (= 200 status)
      {:refresh-token (:refresh_token body)
       :access-token (:access_token body)
       :expires (+ (System/currentTimeMillis) (* 1000 (:expires_in body)))}
      (throw (ex-info (format "Anthropic token exchange failed: %s" (pr-str body))
                      {:status status
                       :body body})))))

(defn create-api-key [access-token]
  (let [url "https://api.anthropic.com/api/oauth/claude_cli/create_api_key"
        {:keys [status body]} (http/post
                               url
                               {:headers {"Authorization" (str "Bearer " access-token)
                                          "Content-Type" "application/x-www-form-urlencoded"
                                          "Accept" "application/json, text/plain, */*"}
                                :as :json})]
    (if (= 200 status)
      (let [raw-key (:raw_key body)]
        (when-not (string/blank? raw-key)
          raw-key))
      (throw (ex-info (format "Anthropic create API token failed: %s" (pr-str body))
                      {:status status
                       :body body})))))

(comment
  (def a (authorization-oauth :console))
  (:verifier a)
  (:url a)

  (def credentials (exchange "<paste-code-here>" (:verifier a)))

  (:access-token credentials)

  (def api-key (create-api-key (:access-token credentials))))
