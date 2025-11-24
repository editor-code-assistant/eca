(ns eca.llm-providers.openai
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.features.login :as f.login]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.oauth :as oauth]
   [eca.shared :refer [assoc-some deep-merge multi-str]]
   [hato.client :as http]
   [ring.util.codec :as ring.util]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[OPENAI]")

(def ^:private responses-path "/v1/responses")
(def ^:private codex-url "https://chatgpt.com/backend-api/codex/responses")

(defn ^:private jtw-token->account-id [api-key]
  (let [[_ base64] (string/split api-key #"\.")
        payload (some-> base64
                        llm-util/<-base64
                        json/parse-string)]
    (get-in payload ["https://api.openai.com/auth" "chatgpt_account_id"])))

(defn ^:private response-body->result [body]
  {:output-text (reduce
                 #(str %1 (:text %2))
                 ""
                 (:content (last (:output body))))})

(defn ^:private base-responses-request! [{:keys [rid body api-url auth-type url-relative-path api-key on-error on-stream]}]
  (let [oauth? (= :auth/oauth auth-type)
        url (if oauth?
              codex-url
              (str api-url (or url-relative-path responses-path)))
        headers (assoc-some
                 {"Authorization" (str "Bearer " api-key)
                  "Content-Type" "application/json"}
                 "chatgpt-account-id" (jtw-token->account-id api-key)
                 "OpenAI-Beta" (when oauth? "responses=experimental"),
                 "Originator" (when oauth? "codex_cli_rs")
                 "Session-ID" (when oauth? (str (random-uuid))))
        on-error (if on-stream
                   on-error
                   (fn [error-data]
                     (llm-util/log-response logger-tag rid "response-error" body)
                     {:error error-data}))]
    (llm-util/log-request logger-tag rid url body headers)
    @(http/post
      url
      {:headers headers
       :body (json/generate-string body)
       :throw-exceptions? false
       :async? true
       :as (if on-stream :stream :json)}
      (fn [{:keys [status body]}]
        (try
          (if (not= 200 status)
            (let [body-str (if on-stream (slurp body) body)]
              (logger/warn logger-tag "Unexpected response status: %s body: %s" status body-str)
              (on-error {:message (format "OpenAI response status: %s body: %s" status body-str)}))
            (if on-stream
              (with-open [rdr (io/reader body)]
                (doseq [[event data] (llm-util/event-data-seq rdr)]
                  (llm-util/log-response logger-tag rid event data)
                  (on-stream event data)))
              (do
                (llm-util/log-response logger-tag rid "response" body)
                (response-body->result body))))
          (catch Exception e
            (on-error {:exception e}))))
      (fn [e]
        (on-error {:exception e})))))

(defn ^:private normalize-messages [messages supports-image?]
  (keep (fn [{:keys [role content] :as msg}]
          (case role
            "tool_call" {:type "function_call"
                         :name (:full-name content)
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
            (-> msg
                (dissoc :content-id)
                (update :content (fn [c]
                                   (if (string? c)
                                     c
                                     (keep #(case (name (:type %))

                                              "text"
                                              (assoc % :type (if (= "user" role)
                                                               "input_text"
                                                               "output_text"))

                                              "image"
                                              (when supports-image?
                                                {:type "input_image"
                                                 :image_url (format "data:%s;base64,%s"
                                                                    (:media-type %)
                                                                    (:base64 %))})

                                              %)
                                           c)))))))
        messages))

(defn ^:private ->tools [tools]
  (mapv (fn [tool]
          {:type "function"
           :name (:full-name tool)
           :description (:description tool)
           :parameters (:parameters tool)})
        tools))

(defn create-response! [{:keys [model user-messages instructions reason? supports-image? api-key api-url url-relative-path
                                max-output-tokens past-messages tools web-search extra-payload auth-type]}
                        {:keys [on-message-received on-error on-prepare-tool-call on-tools-called on-reason on-usage-updated] :as callbacks}]
  (let [input (concat (normalize-messages past-messages supports-image?)
                      (normalize-messages user-messages supports-image?))
        tools (cond-> (->tools tools)
                web-search (conj {:type "web_search_preview"}))
        stream? (boolean callbacks)
        body (deep-merge
              (assoc-some
               {:model model
                :input input
                :prompt_cache_key (str (System/getProperty "user.name") "@ECA")
                :instructions (if (= :auth/oauth auth-type)
                                (str "You are Codex." instructions)
                                instructions)
                :tools tools
                :include (when reason?
                           ["reasoning.encrypted_content"])
                :store false
                :reasoning (when reason?
                             {:effort "medium"
                              :summary "detailed"})
                :stream stream?}
               :max_output_tokens max-output-tokens
               :parallel_tool_calls (:parallel_tool_calls extra-payload))
              extra-payload)
        tool-call-by-item-id* (atom {})
        on-stream-fn
        (when stream?
          (fn handle-stream [event data]
            (case event
              ;; text
              "response.output_text.delta"
              (on-message-received {:type :text
                                    :text (:delta data)})
              ;; tools
              "response.function_call_arguments.delta" (let [call (get @tool-call-by-item-id* (:item_id data))]
                                                         (on-prepare-tool-call {:id (:id call)
                                                                                :full-name (:full-name call)
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

              "response.reasoning_summary_text.done"
              (on-reason {:status :thinking
                          :id (:item_id data)
                          :text "\n"})

              "response.output_item.added"
              (case (-> data :item :type)
                "reasoning" (on-reason {:status :started
                                        :id (-> data :item :id)})
                "function_call" (let [call-id (-> data :item :call_id)
                                      item-id (-> data :item :id)
                                      function-name (-> data :item :name)
                                      function-args (-> data :item :arguments)]
                                  (swap! tool-call-by-item-id* assoc item-id {:full-name function-name :id call-id})
                                  (on-prepare-tool-call {:id call-id
                                                         :full-name function-name
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
                                           (swap! tool-call-by-item-id* assoc id {:full-name name :id call_id})
                                           (on-prepare-tool-call {:id call_id
                                                                  :full-name name
                                                                  :arguments-text arguments}))
                                         {:id call_id
                                          :item-id id
                                          :full-name name
                                          :arguments (json/parse-string arguments)}))
                                     (:output response))]
                (on-usage-updated (let [input-cache-read-tokens (-> response :usage :input_tokens_details :cached_tokens)]
                                    {:input-tokens (if input-cache-read-tokens
                                                     (- (-> response :usage :input_tokens) input-cache-read-tokens)
                                                     (-> response :usage :input_tokens))
                                     :output-tokens (-> response :usage :output_tokens)
                                     :input-cache-read-tokens input-cache-read-tokens}))
                (if (seq tool-calls)
                  (when-let [{:keys [new-messages]} (on-tools-called tool-calls)]
                    (base-responses-request!
                     {:rid (llm-util/gen-rid)
                      :body (assoc body :input (normalize-messages new-messages supports-image?))
                      :api-url api-url
                      :url-relative-path url-relative-path
                      :api-key api-key
                      :auth-type auth-type
                      :on-error on-error
                      :on-stream handle-stream})
                    (doseq [tool-call tool-calls]
                      (swap! tool-call-by-item-id* dissoc (:item-id tool-call))))
                  (on-message-received {:type :finish
                                        :finish-reason (-> data :response :status)})))

              "response.failed" (do
                                  (when-let [error (-> data :response :error)]
                                    (on-error {:message (:message error)}))
                                  (on-message-received {:type :finish
                                                        :finish-reason (-> data :response :status)}))
              nil)))]
    (base-responses-request!
     {:rid (llm-util/gen-rid)
      :body body
      :api-url api-url
      :url-relative-path url-relative-path
      :api-key api-key
      :auth-type auth-type
      :on-error on-error
      :on-stream on-stream-fn})))

(def ^:private client-id "app_EMoamEEZ73f0CkXaXp7hrann")

(defn ^:private oauth-url [server-url]
  (let [url "https://auth.openai.com/oauth/authorize"
        {:keys [challenge verifier]} (llm-util/generate-pkce)]
    {:verifier verifier
     :url (str url "?" (ring.util/form-encode {:client_id client-id
                                               :response_type "code"
                                               :redirect_uri server-url
                                               :scope "openid profile email offline_access"
                                               :id_token_add_organizations "true"
                                               :prompt "login"
                                               :codex_cli_simplified_flow "true"
                                               :code_challenge challenge
                                               :code_challenge_method "S256"
                                               :state verifier}))}))

(defn ^:private oauth-authorize [server-url code verifier]
  (let [{:keys [status body]} (http/post
                               "https://auth.openai.com/oauth/token"
                               {:headers {"Content-Type" "application/json"}
                                :body (json/generate-string
                                       {:grant_type "authorization_code"
                                        :client_id client-id
                                        :code code
                                        :code_verifier verifier
                                        :redirect_uri server-url})
                                :as :json})]
    (if (= 200 status)
      {:refresh-token (:refresh_token body)
       :access-token (:access_token body)
       :expires-at (+ (quot (System/currentTimeMillis) 1000) (:expires_in body))}
      (throw (ex-info (format "OpenAI token exchange failed: %s" (pr-str body))
                      {:status status
                       :body body})))))

(defmethod f.login/login-step ["openai" :login/start] [{:keys [db* chat-id provider send-msg!]}]
  (swap! db* assoc-in [:chats chat-id :login-provider] provider)
  (swap! db* assoc-in [:auth provider] {:step :login/waiting-login-method})
  (send-msg! (multi-str "Now, inform the login method:"
                        ""
                        ;; "pro: GPT Pro (subscription)"
                        "manual: Manually enter API Key")))

(defmethod f.login/login-step ["openai" :login/waiting-login-method] [{:keys [db* input provider send-msg!] :as ctx}]
  (case input
    "pro"
    (let [local-server-port 1455 ;; openai requires this port
          server-url (str "http://localhost:" local-server-port "/auth/callback")
          {:keys [verifier url]} (oauth-url server-url)]
      (throw (ex-info "Unsupported login" {}))
      (oauth/start-oauth-server!
       {:port local-server-port
        :on-success (fn [{:keys [code]}]
                      (let [{:keys [access-token refresh-token expires-at]} (oauth-authorize server-url code verifier)]
                        (swap! db* update-in [:auth provider] merge {:step :login/done
                                                                     :type :auth/oauth
                                                                     :refresh-token refresh-token
                                                                     :api-key access-token
                                                                     :expires-at expires-at})
                        (send-msg! "")
                        (f.login/login-done! ctx))
                      (future
                        (Thread/sleep 2000) ;; wait to render success page
                        (oauth/stop-oauth-server!)))
        :on-error (fn [error]
                    (send-msg! (str "Error authenticating via oauth: " error))
                    (oauth/stop-oauth-server!))})
      (send-msg! (format "Open your browser at `%s` and authenticate at OpenAI.\n\nThen ECA will finish the login automatically." url)))
    "manual"
    (do
      (swap! db* assoc-in [:auth provider] {:step :login/waiting-api-key
                                            :mode :manual})
      (send-msg! "Paste your API Key"))
    (send-msg! (format "Unknown login method '%s'. Inform one of the options: pro, manual" input))))

(defmethod f.login/login-step ["openai" :login/waiting-api-key] [{:keys [input db* provider send-msg!] :as ctx}]
  (if (string/starts-with? input "sk-")
    (do (config/update-global-config! {:providers {"openai" {:key input}}})
        (swap! db* update :auth dissoc provider)
        (send-msg! (str "API key saved in " (.getCanonicalPath (config/global-config-file))))

        (f.login/login-done! ctx :update-cache? false))
    (send-msg! (format "Invalid API key '%s'" input))))
