(ns eca.llm-providers.openai
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.client-http :as client]
   [eca.config :as config]
   [eca.features.login :as f.login]
   [eca.features.providers :as f.providers]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.oauth :as oauth]
   [eca.shared :refer [assoc-some join-api-url multi-str]]
   [hato.client :as http]
   [ring.util.codec :as ring.util]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[OPENAI]")

(def ^:private responses-path "/v1/responses")
(def ^:private codex-url "https://chatgpt.com/backend-api/codex/responses")

(defn ^:private jwt-payload->account-id
  "Extract account ID from JWT payload, checking multiple locations like opencode does."
  [payload]
  (or (get payload "chatgpt_account_id")
      (get-in payload ["https://api.openai.com/auth" "chatgpt_account_id"])
      (get-in payload ["organizations" 0 "id"])))

(defn ^:private jwt-token->account-id
  "Extract account ID from a JWT token string.
  Returns nil when the token is not a valid JWT."
  [token]
  (try
    (when (string? token)
      (let [[_ base64] (string/split token #"\.")
            payload (some-> base64
                            oauth/<-base64
                            json/parse-string)]
        (jwt-payload->account-id payload)))
    (catch Exception _)))

(defn ^:private response-body->result [body]
  {:output-text (reduce
                 #(str %1 (:text %2))
                 ""
                 (:content (last (:output body))))})

(defn ^:private base-responses-request! [{:keys [rid body api-url auth-type url-relative-path api-key account-id on-error on-stream http-client extra-headers]}]
  (let [oauth? (= :auth/oauth auth-type)
        url (if oauth?
              codex-url
              (join-api-url api-url (or url-relative-path responses-path)))
        ;; Use persisted account-id first, fall back to extracting from JWT
        resolved-account-id (or account-id (jwt-token->account-id api-key))
        extra-headers (if (fn? extra-headers)
                        (extra-headers {:body body})
                        extra-headers)
        headers (client/merge-llm-headers
                 (merge
                  (assoc-some
                   {"Authorization" (str "Bearer " api-key)
                    "Content-Type" "application/json"}
                   "ChatGPT-Account-Id" resolved-account-id
                   "OpenAI-Beta" (when oauth? "responses=experimental"),
                   "Originator" (when oauth? "codex_cli_rs")
                   "Session-ID" (when oauth? (str (random-uuid))))
                  extra-headers))
        on-error (if on-stream
                   on-error
                   (fn [error-data]
                     (llm-util/log-response logger-tag rid "response-error" body)
                     {:error error-data}))]
    (llm-util/log-request logger-tag rid url body headers)
    (try
      (let [{:keys [status body]} (http/post
                                   url
                                   {:headers headers
                                    :body (json/generate-string body)
                                    :throw-exceptions? false
                                    :http-client (client/merge-with-global-http-client http-client)
                                    :as (if on-stream :stream :json)})]
        (if (not= 200 status)
          (let [body-str (if on-stream (slurp body) body)]
            (logger/warn logger-tag "Unexpected response status: %s body: %s" status body-str)
            (on-error {:message (format "OpenAI response status: %s body: %s" status body-str)
                       :status status
                       :body body-str}))
          (if on-stream
            (with-open [rdr (io/reader body)]
              (doseq [[event data] (llm-util/event-data-seq rdr)]
                (llm-util/log-response logger-tag rid event data)
                (on-stream event data)))
            (do
              (llm-util/log-response logger-tag rid "response" body)
              (response-body->result body)))))
      (catch Exception e
        (let [msg (or (ex-message e) (.getName (class e)))
              prefix (if (ex-data e) "Internal error" "Connection error")]
          (on-error {:exception e
                     :message (format "%s: %s" prefix msg)}))))))

(defn ^:private normalize-messages [messages supports-image?]
  (keep (fn [{:keys [role content] :as msg}]
          (case role
            "tool_call" {:type "function_call"
                         :name (:full-name content)
                         :call_id (:id content)
                         :arguments (json/generate-string (or (:arguments content) {}))}
            "tool_call_output"
            {:type "function_call_output"
             :call_id (:id content)
             :output (llm-util/stringfy-tool-result content)}
            ;; Replays a previously-generated image as a USER-role input_image,
            ;; symmetric with how user-attached ImageContext images flow. The
            ;; alternative standalone {type:"image_generation_call",id,result}
            ;; shape is rejected by the Responses API when :store is false (the
            ;; id triggers a server-side lookup that 404s), and assistant-role
            ;; input_image is rejected outright. Wrapping under user-role makes
            ;; the prior generation visible to the model regardless of :store
            ;; mode, and avoids any provider-specific replay semantics.
            "image_generation_call" (when (and supports-image? (:base64 content))
                                      {:role "user"
                                       :content [{:type "input_image"
                                                  :image_url (format "data:%s;base64,%s"
                                                                     (or (:media-type content) "image/png")
                                                                     (:base64 content))}]})
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

(defn ^:private ->tools [tools web-search image-generation codex?]
  (cond->
   (mapv (fn [tool]
           {:type "function"
            :name (:full-name tool)
            :description (:description tool)
            :parameters (:parameters tool)})
         tools)
    (and web-search (not codex?)) (conj {:type "web_search_preview"})
    (and image-generation (not codex?)) (conj {:type "image_generation" :output_format "png"})))

(defn create-response! [{:keys [model user-messages instructions reason? supports-image? api-key api-url url-relative-path
                                max-output-tokens past-messages tools web-search image-generation extra-payload extra-headers
                                auth-type account-id http-client prompt-cache-key]}
                        {:keys [on-message-received on-error on-prepare-tool-call on-tools-called on-reason on-usage-updated
                                on-server-web-search on-server-image-generation] :as callbacks}]
  (let [codex? (= :auth/oauth auth-type)
        input (concat (normalize-messages past-messages supports-image?)
                      (normalize-messages user-messages supports-image?))
        tools (->tools tools web-search image-generation codex?)
        body (merge
              (assoc-some
               {:model model
                :input (if codex?
                         (concat [{:role "system" :content instructions}] input)
                         input)
                :prompt_cache_key (or prompt-cache-key
                                      (str (System/getProperty "user.name") "@ECA"))
                :instructions instructions
                :tools tools
                :include (when reason?
                           ["reasoning.encrypted_content"])
                :store false
                :reasoning (when reason?
                             {:effort "medium"
                              :summary "auto"})
                :stream true}
               :max_output_tokens (when-not codex? max-output-tokens)
               :parallel_tool_calls (:parallel_tool_calls extra-payload))
              extra-payload)
        tool-call-by-item-id* (atom {})
        reasoning-item-id* (atom nil)
        sync-result* (when-not callbacks (atom nil))
        on-stream-fn
        (if callbacks
          (fn handle-stream [event data]
            (case event
              ;; text
              "response.output_text.delta"
              (on-message-received {:type :text
                                    :text (:delta data)})
              ;; tools
              "response.function_call_arguments.delta" (when-let [call (get @tool-call-by-item-id* (:item_id data))]
                                                         (on-prepare-tool-call {:id (:id call)
                                                                                :full-name (:full-name call)
                                                                                :arguments-text (:delta data)}))

              "response.output_item.done"
              (case (:type (:item data))
                "reasoning" (do (reset! reasoning-item-id* nil)
                                (on-reason {:status :finished
                                            :id (-> data :item :id)
                                            :external-id (-> data :item :encrypted_content)}))
                "function_call" (let [done-item-id (-> data :item :id)
                                      done-call-id (-> data :item :call_id)
                                      args (-> data :item :arguments)]
                                  (swap! tool-call-by-item-id*
                                         (fn [m]
                                           (if-let [existing-key (or (when (contains? m done-item-id) done-item-id)
                                                                     (->> m
                                                                          (some (fn [[k v]]
                                                                                  (when (= done-call-id (:id v)) k)))))]
                                             (assoc-in m [existing-key :arguments] args)
                                             (assoc m done-item-id {:arguments args})))))
                "web_search_call" (on-server-web-search {:status :finished
                                                         :id (-> data :item :id)
                                                         :output nil})
                "image_generation_call" (let [base64 (-> data :item :result)
                                              id (-> data :item :id)]
                                          (when (and base64 on-message-received)
                                            ;; Include :id so chat.clj can persist this as an
                                            ;; image_generation_call history entry for replay
                                            ;; on subsequent turns (OpenAI Responses API rejects
                                            ;; input_image under assistant role; the canonical
                                            ;; shape is a standalone image_generation_call item).
                                            (on-message-received (cond-> {:type :image
                                                                          :media-type "image/png"
                                                                          :base64 base64}
                                                                   id (assoc :id id))))
                                          (on-server-image-generation {:status :finished
                                                                       :id id
                                                                       :output nil}))
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
                          :id (or @reasoning-item-id* (:item_id data))
                          :text (:delta data)})

              "response.reasoning_summary_text.done"
              (on-reason {:status :thinking
                          :id (or @reasoning-item-id* (:item_id data))
                          :text "\n"})

              "response.output_item.added"
              (case (-> data :item :type)
                "reasoning" (let [id (-> data :item :id)]
                              (reset! reasoning-item-id* id)
                              (on-reason {:status :started :id id}))
                "function_call" (let [call-id (-> data :item :call_id)
                                      item-id (-> data :item :id)
                                      function-name (-> data :item :name)
                                      function-args (-> data :item :arguments)]
                                  (swap! tool-call-by-item-id* assoc item-id {:full-name function-name :id call-id})
                                  (on-prepare-tool-call {:id call-id
                                                         :full-name function-name
                                                         :arguments-text function-args}))
                "web_search_call" (on-server-web-search {:status :started
                                                         :id (-> data :item :id)
                                                         :name "web_search"
                                                         :input nil})
                "image_generation_call" (on-server-image-generation {:status :started
                                                                     :id (-> data :item :id)
                                                                     :name "image_generation"
                                                                     :input nil})
                nil)

              ;; done
              "response.completed"
              (let [response (:response data)
                    tool-calls (or (seq (keep (fn [{:keys [id call_id name arguments] :as output}]
                                                (when (= "function_call" (:type output))
                                                  (when-not (some #(= call_id (:id %)) (vals @tool-call-by-item-id*))
                                                    (swap! tool-call-by-item-id* assoc id {:full-name name :id call_id})
                                                    (on-prepare-tool-call {:id call_id
                                                                           :full-name name
                                                                           :arguments-text arguments}))
                                                  {:id call_id
                                                   :item-id id
                                                   :full-name name
                                                   :arguments (json/parse-string arguments)}))
                                              (:output response)))
                                   ;; Fallback: some models stream tool calls via events
                                   ;; but return empty :output in response.completed
                                   (seq (keep (fn [[item-id {:keys [full-name id arguments]}]]
                                                (when arguments
                                                  {:id id
                                                   :item-id item-id
                                                   :full-name full-name
                                                   :arguments (json/parse-string arguments)}))
                                              @tool-call-by-item-id*)))]
                (on-usage-updated (let [input-cache-read-tokens (-> response :usage :input_tokens_details :cached_tokens)]
                                    {:input-tokens (if input-cache-read-tokens
                                                     (- (-> response :usage :input_tokens) input-cache-read-tokens)
                                                     (-> response :usage :input_tokens))
                                     :output-tokens (-> response :usage :output_tokens)
                                     :input-cache-read-tokens input-cache-read-tokens}))
                (if (seq tool-calls)
                  (when-let [{:keys [new-messages tools fresh-api-key provider-auth]} (on-tools-called tool-calls)]
                    (reset! tool-call-by-item-id* {})
                    (base-responses-request!
                     {:rid (llm-util/gen-rid)
                      :body (assoc body
                                   :input (normalize-messages new-messages supports-image?)
                                   :tools (->tools tools web-search image-generation codex?))
                      :api-url api-url
                      :url-relative-path url-relative-path
                      :api-key (or fresh-api-key api-key)
                      :account-id (or (:account-id provider-auth) account-id)
                      :http-client http-client
                      :extra-headers extra-headers
                      :auth-type auth-type
                      :on-error on-error
                      :on-stream handle-stream}))
                  (on-message-received {:type :finish
                                        :finish-reason (-> data :response :status)})))

              "response.failed" (do
                                  (when-let [error (-> data :response :error)]
                                    (on-error {:message (:message error)}))
                                  (on-message-received {:type :finish
                                                        :finish-reason (-> data :response :status)}))
              nil))
          ;; Sync mode: collect text deltas into result atom
          (let [sb (StringBuilder.)]
            (fn handle-sync-stream [event data]
              (case event
                "response.output_text.delta"
                (.append sb ^String (:delta data))
                "response.completed"
                (reset! sync-result* {:output-text (.toString sb)})
                "response.failed"
                (reset! sync-result* {:error {:message (-> data :response :error :message)}})
                nil))))
        result (base-responses-request!
                {:rid (llm-util/gen-rid)
                 :body body
                 :api-url api-url
                 :url-relative-path url-relative-path
                 :api-key api-key
                 :account-id account-id
                 :http-client http-client
                 :extra-headers extra-headers
                 :auth-type auth-type
                 :on-error on-error
                 :on-stream on-stream-fn})]
    (if callbacks
      result
      (or @sync-result* result))))

(def ^:private client-id "app_EMoamEEZ73f0CkXaXp7hrann")

(defn ^:private oauth-url [server-url]
  (let [url "https://auth.openai.com/oauth/authorize"
        {:keys [challenge verifier]} (oauth/generate-pkce)]
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

(def ^:private oauth-token-url
  "https://auth.openai.com/oauth/token")

(defn ^:private extract-account-id
  "Extract account ID from token response, trying id_token first, then access_token."
  [{:keys [id_token access_token]}]
  (or (jwt-token->account-id id_token)
      (jwt-token->account-id access_token)))

(defn ^:private oauth-authorize [server-url code verifier]
  (let [{:keys [status body]} (http/post
                               oauth-token-url
                               {:headers {"Content-Type" "application/json"}
                                :body (json/generate-string
                                       {:grant_type "authorization_code"
                                        :client_id client-id
                                        :code code
                                        :code_verifier verifier
                                        :redirect_uri server-url})
                                :http-client (client/merge-with-global-http-client {})
                                :as :json})]
    (if (= 200 status)
      {:refresh-token (:refresh_token body)
       :access-token (:access_token body)
       :account-id (extract-account-id body)
       :expires-at (+ (quot (System/currentTimeMillis) 1000) (:expires_in body))}
      (throw (ex-info (format "OpenAI token exchange failed: %s" (pr-str body))
                      {:status status
                       :body body})))))

(defn ^:private oauth-refresh [refresh-token]
  (let [{:keys [status body]} (http/post
                               oauth-token-url
                               {:headers {"Content-Type" "application/json"}
                                :body (json/generate-string
                                       {:grant_type "refresh_token"
                                        :refresh_token refresh-token
                                        :client_id client-id})
                                :throw-exceptions? false
                                :http-client (client/merge-with-global-http-client {})
                                :as :json})]
    (if (= 200 status)
      {:refresh-token (:refresh_token body)
       :access-token (:access_token body)
       :expires-at (+ (quot (System/currentTimeMillis) 1000) (:expires_in body))}
      (throw (ex-info (format "OpenAI refresh token failed: %s" (pr-str body))
                      {:status status
                       :body body})))))

;; --- Settings-based login (providers/login flow) ---

(defmethod f.providers/start-login! ["openai" "pro"] [_ _ db* _config messenger metrics]
  (let [local-server-port 1455
        server-url (str "http://localhost:" local-server-port "/auth/callback")
        {:keys [verifier url]} (oauth-url server-url)]
    (oauth/start-oauth-server!
     {:port local-server-port
      :on-success (fn [{:keys [code]}]
                    (try
                      (let [{:keys [access-token refresh-token account-id expires-at]}
                            (oauth-authorize server-url code verifier)]
                        (swap! db* update-in [:auth "openai"] merge
                               {:step :login/done
                                :type :auth/oauth
                                :mode :pro
                                :refresh-token refresh-token
                                :api-key access-token
                                :account-id account-id
                                :expires-at expires-at})
                        (f.providers/sync-and-notify! "openai" db* messenger metrics))
                      (catch Exception e
                        (logger/error logger-tag "OAuth completion failed:" (ex-message e)))
                      (finally
                        (future
                          (Thread/sleep 2000)
                          (oauth/stop-oauth-server! local-server-port)))))
      :on-error (fn [error]
                  (logger/error logger-tag "OAuth error:" error)
                  (oauth/stop-oauth-server! local-server-port))})
    {:action "authorize"
     :url url
     :message "Complete authentication in your browser. ECA will finish the login automatically."}))

;; --- Chat-based login (legacy /login command) ---

(defmethod f.login/login-step ["openai" :login/start] [{:keys [db* chat-id provider send-msg!]}]
  (swap! db* assoc-in [:chats chat-id :login-provider] provider)
  (swap! db* assoc-in [:auth provider] {:step :login/waiting-login-method})
  (send-msg! (multi-str "Now, inform the login method:"
                        ""
                        "- pro: GPT Plus/Pro (subscription)"
                        "- manual: Manually enter API Key")))

(defmethod f.login/login-step ["openai" :login/waiting-login-method] [{:keys [db* input provider send-msg!] :as ctx}]
  (case input
    "pro"
    (let [local-server-port 1455 ;; openai requires this port
          server-url (str "http://localhost:" local-server-port "/auth/callback")
          {:keys [verifier url]} (oauth-url server-url)]
      (oauth/start-oauth-server!
       {:port local-server-port
        :on-success (fn [{:keys [code]}]
                      (let [{:keys [access-token refresh-token account-id expires-at]} (oauth-authorize server-url code verifier)]
                        (swap! db* update-in [:auth provider] merge {:step :login/done
                                                                     :type :auth/oauth
                                                                     :refresh-token refresh-token
                                                                     :api-key access-token
                                                                     :account-id account-id
                                                                     :expires-at expires-at})
                        (send-msg! "")
                        (f.login/login-done! ctx))
                      (future
                        (Thread/sleep 2000) ;; wait to render success page
                        (oauth/stop-oauth-server! local-server-port)))
        :on-error (fn [error]
                    (send-msg! (str "Error authenticating via oauth: " error))
                    (oauth/stop-oauth-server! local-server-port))})
      (send-msg! (format "Open your browser at:\n\n%s\n\nAuthenticate at OpenAI, then ECA will finish the login automatically." url)))
    "manual"
    (do
      (swap! db* assoc-in [:auth provider] {:step :login/waiting-api-key
                                            :mode :manual})
      (send-msg! "Paste your API Key"))
    (send-msg! (format "Unknown login method '%s'. Inform one of the options: pro, manual" input))))

(defmethod f.login/login-step ["openai" :login/waiting-api-key] [{:keys [input db* provider send-msg!] :as ctx}]
  (if (string/starts-with? input "sk-")
    (do (config/update-global-config! {:providers {"openai" {:key input}}})
        (swap! db* assoc-in [:auth provider] {:step :login/done :type :auth/token})
        (send-msg! (str "API key saved in " (.getCanonicalPath (config/global-config-file))))
        (f.login/login-done! ctx))
    (send-msg! (format "Invalid API key '%s'" input))))

(defmethod f.login/login-step ["openai" :login/renew-token] [{:keys [db* provider] :as ctx}]
  (let [current-auth (get-in @db* [:auth provider])
        existing-account-id (:account-id current-auth)
        {:keys [refresh-token access-token expires-at]} (oauth-refresh (:refresh-token current-auth))
        ;; Try to extract new account-id from refreshed tokens, fallback to existing
        new-account-id (or (jwt-token->account-id access-token) existing-account-id)]
    (swap! db* update-in [:auth provider] merge {:step :login/done
                                                 :type :auth/oauth
                                                 :refresh-token refresh-token
                                                 :api-key access-token
                                                 :account-id new-account-id
                                                 :expires-at expires-at})
    (f.login/login-done! ctx :silent? true :skip-models-sync? true)))
