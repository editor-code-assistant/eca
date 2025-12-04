(ns eca.llm-providers.anthropic
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.features.login :as f.login]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.shared :as shared :refer [assoc-some deep-merge multi-str]]
   [hato.client :as http]
   [ring.util.codec :as ring.util]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[ANTHROPIC]")

(def ^:private messages-path "/v1/messages")

(defn ^:private any-assistant-message-without-thinking-previously?
  "If there is a assistant message, which has no previous any role message with thinking content, returns true."
  [messages]
  (loop [msgs messages
         seen-thinking? false]
    (if-let [msg (first msgs)]
      (let [is-assistant? (= "assistant" (:role msg))
            has-thinking? (and (vector? (:content msg))
                               (some #(= "thinking" (:type %)) (:content msg)))]
        (cond
          ;; If this is an assistant message and we haven't seen thinking before, return true
          (and is-assistant? (not seen-thinking?) (not has-thinking?))
          true

          ;; If this message has thinking content, mark it as seen
          has-thinking?
          (recur (rest msgs) true)

          ;; Otherwise continue
          :else
          (recur (rest msgs) seen-thinking?)))
      ;; No assistant message found without previous thinking
      false)))

(defn ^:private fix-non-thinking-assistant-messages [messages]
  (if (any-assistant-message-without-thinking-previously? messages)
    ;; Anthropic doesn't like assistant messages without thinking blocks,
    ;; we force to be a user one when this happens
    ;; (MCP prompts that return assistant messages as initial step like clojureMCP)
    ;; https://clojurians.slack.com/archives/C093426FPUG/p1757622242502969
    (mapv (fn [{:keys [role content] :as msg}]
            (if (= "assistant" role)
              {:role "user"
               :content content}
              msg))
          messages)
    messages))

(defn ^:private ->tools [tools web-search]
  (cond->
   (mapv (fn [tool]
           {:description (:description tool)
            :input_schema (:parameters tool)
            :name (:full-name tool)}) tools)
    web-search (conj {:type "web_search_20250305"
                      :name "web_search"
                      :max_uses 10
                      :cache_control {:type "ephemeral"}})))

(defn ^:private base-request! [{:keys [rid body api-url api-key auth-type url-relative-path content-block* on-error on-stream http-client]}]
  (let [url (str api-url (or url-relative-path messages-path))
        reason-id (str (random-uuid))
        oauth? (= :auth/oauth auth-type)
        headers (assoc-some
                 {"anthropic-version" "2023-06-01"
                  "Content-Type" "application/json"}
                 "x-api-key" (when-not oauth? api-key)
                 "Authorization" (when oauth? (str "Bearer " api-key))
                 "anthropic-beta" (when oauth? "oauth-2025-04-20"))
        response* (atom nil)
        on-error (if on-stream
                   on-error
                   (fn [error-data]
                     (llm-util/log-response logger-tag rid "response-error" body)
                     (reset! response* error-data)))]
    (llm-util/log-request logger-tag rid url body headers)
    @(http/post
      url
      {:headers headers
       :body (json/generate-string body)
       :throw-exceptions? false
       :async? true
       :http-client http-client
       :as (if on-stream :stream :json)}
      (fn [{:keys [status body]}]
        (try
          (if (not= 200 status)
            (let [body-str (if on-stream (slurp body) body)]
              (logger/warn logger-tag "Unexpected response status: %s body: %s" status body-str)
              (on-error {:message (format "Anthropic response status: %s body: %s" status body-str)}))
            (if on-stream
              (with-open [rdr (io/reader body)]
                (doseq [[event data] (llm-util/event-data-seq rdr)]
                  (llm-util/log-response logger-tag rid event data)
                  (on-stream event data content-block* reason-id)))

              (do
                (llm-util/log-response logger-tag rid "response" body)
                (reset! response*
                        {:output-text (:text (last (:content body)))}))))
          (catch Exception e
            (on-error {:exception e}))))
      (fn [e]
        (on-error {:exception e})))
    @response*))

(defn ^:private normalize-messages [past-messages supports-image?]
  (mapv (fn [{:keys [role content] :as msg}]
          (case role
            "tool_call" {:role "assistant"
                         :content [{:type "tool_use"
                                    :id (:id content)
                                    :name (:full-name content)
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
            (-> msg
                (dissoc :content-id)
                (update :content (fn [c]
                                   (if (string? c)
                                     (string/trim c)
                                     (vec
                                       (keep #(case (name (:type %))

                                                "text"
                                                (update % :text string/trim)

                                                "image"
                                                (when supports-image?
                                                  {:type "image"
                                                   :source {:data (:base64 %)
                                                            :media_type (:media-type %)
                                                            :type "base64"}})

                                                %)
                                            c))))))))
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
         (assoc-in message [:content (dec (count content)) :cache_control] {:type "ephemeral"}))))))

(defn chat!
  [{:keys [model user-messages instructions max-output-tokens
           api-url api-key auth-type url-relative-path reason? past-messages
           tools web-search extra-payload supports-image? http-client]}
   {:keys [on-message-received on-error on-reason on-prepare-tool-call on-tools-called on-usage-updated] :as callbacks}]
  (let [messages (concat (normalize-messages past-messages supports-image?)
                         (normalize-messages (fix-non-thinking-assistant-messages user-messages) supports-image?))
        stream? (boolean callbacks)
        body (deep-merge
              (assoc-some
               {:model model
                :messages (add-cache-to-last-message messages)
                :max_tokens (or max-output-tokens 32000)
                :stream stream?
                :tools (->tools tools web-search)
                :system [{:type "text" :text "You are Claude Code, Anthropic's official CLI for Claude."}
                         {:type "text" :text instructions :cache_control {:type "ephemeral"}}]}
               :thinking (when reason?
                           {:type "enabled" :budget_tokens 2048}))
              extra-payload)
        on-stream-fn
        (when stream?
          (fn handle-stream [event data content-block* reason-id]
            (case event
              "content_block_start" (case (-> data :content_block :type)
                                      "thinking" (do
                                                   (on-reason {:status :started
                                                               :id reason-id})
                                                   (swap! content-block* assoc (:index data) (:content_block data)))
                                      "tool_use" (do
                                                   (on-prepare-tool-call {:full-name (-> data :content_block :name)
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
                                                           (on-prepare-tool-call {:full-name (:name content-block)
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
                                  "tool_use" (let [tool-calls (keep
                                                               (fn [content-block]
                                                                 (when (= "tool_use" (:type content-block))
                                                                   {:id (:id content-block)
                                                                    :full-name (:name content-block)
                                                                    :arguments (json/parse-string (:input-json content-block))}))
                                                               (vals @content-block*))]
                                               (when-let [{:keys [new-messages]} (on-tools-called tool-calls)]
                                                 (let [messages (-> (normalize-messages new-messages supports-image?)
                                                                    add-cache-to-last-message)]
                                                   (reset! content-block* {})
                                                   (base-request!
                                                    {:rid (llm-util/gen-rid)
                                                     :body (assoc body :messages messages)
                                                     :api-url api-url
                                                     :api-key api-key
                                                     :http-client http-client
                                                     :auth-type auth-type
                                                     :url-relative-path url-relative-path
                                                     :content-block* (atom nil)
                                                     :on-error on-error
                                                     :on-stream handle-stream}))))
                                  "end_turn" (do
                                               (reset! content-block* {})
                                               (on-message-received {:type :finish
                                                                     :finish-reason (-> data :delta :stop_reason)}))
                                  "max_tokens" (on-message-received {:type :limit-reached
                                                                     :tokens (:usage data)})
                                  nil))
              nil)))]
    (base-request!
     {:rid (llm-util/gen-rid)
      :body body
      :api-url api-url
      :api-key api-key
      :http-client http-client
      :auth-type auth-type
      :url-relative-path url-relative-path
      :content-block* (atom nil)
      :on-error on-error
      :on-stream on-stream-fn})))

(def ^:private client-id "9d1c250a-e61b-44d9-88ed-5944d1962f5e")

(defn ^:private oauth-url [mode]
  (let [url (str (if (= :console mode) "https://console.anthropic.com" "https://claude.ai") "/oauth/authorize")
        {:keys [challenge verifier]} (llm-util/generate-pkce)]
    {:verifier verifier
     :url (str url "?" (ring.util/form-encode {:code true
                                               :client_id client-id
                                               :response_type "code"
                                               :redirect_uri "https://console.anthropic.com/oauth/code/callback"
                                               :scope "org:create_api_key user:profile user:inference"
                                               :code_challenge challenge
                                               :code_challenge_method "S256"
                                               :state verifier}))}))

(defn ^:private oauth-authorize [code verifier]
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
       :expires-at (+ (quot (System/currentTimeMillis) 1000) (:expires_in body))}
      (throw (ex-info (format "Anthropic token exchange failed: %s" (pr-str body))
                      {:status status
                       :body body})))))

(defn ^:private oauth-refresh [refresh-token]
  (let [url "https://console.anthropic.com/v1/oauth/token"
        body {:grant_type "refresh_token"
              :refresh_token refresh-token
              :client_id client-id}
        {:keys [status body]} (http/post
                               url
                               {:headers {"Content-Type" "application/json"}
                                :body (json/generate-string body)
                                :throw-exceptions? false
                                :as :json})]
    (if (= 200 status)
      {:refresh-token (:refresh_token body)
       :access-token (:access_token body)
       :expires-at (+ (quot (System/currentTimeMillis) 1000) (:expires_in body))}
      (throw (ex-info (format "Anthropic refresh token failed: %s" (pr-str body))
                      {:status status
                       :body body})))))

(defn ^:private create-api-key [access-token]
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

(defmethod f.login/login-step ["anthropic" :login/start] [{:keys [db* chat-id provider send-msg!]}]
  (swap! db* assoc-in [:chats chat-id :login-provider] provider)
  (swap! db* assoc-in [:auth provider] {:step :login/waiting-login-method})
  (send-msg! (multi-str "Now, inform the login method:"
                        ""
                        "max: Claude Pro/Max"
                        "console: Create API Key"
                        "manual: Manually enter API Key")))

(defmethod f.login/login-step ["anthropic" :login/waiting-login-method] [{:keys [db* input provider send-msg!]}]
  (case input
    "max"
    (let [{:keys [verifier url]} (oauth-url :max)]
      (swap! db* assoc-in [:auth provider] {:step :login/waiting-provider-code
                                            :mode :max
                                            :verifier verifier})
      (send-msg! (format "Open your browser at `%s` and authenticate at Anthropic.\nThen paste the code generated in the chat and send it to continue the authentication."
                         url)))
    "console"
    (let [{:keys [verifier url]} (oauth-url :console)]
      (swap! db* assoc-in [:auth provider] {:step :login/waiting-provider-code
                                            :mode :console
                                            :verifier verifier})
      (send-msg! (format "Open your browser at `%s` and authenticate at Anthropic.\nThen paste the code generated in the chat and send it to continue the authentication."
                         url)))
    "manual"
    (do
      (swap! db* assoc-in [:auth provider] {:step :login/waiting-api-key
                                            :mode :manual})
      (send-msg! "Paste your Anthropic API Key"))
    (send-msg! (format "Unknown login method '%s'. Inform one of the options: max, console, manual" input))))

(defmethod f.login/login-step ["anthropic" :login/waiting-provider-code] [{:keys [db* input provider] :as ctx}]
  (let [provider-code input
        {:keys [mode verifier]} (get-in @db* [:auth provider])]
    (case mode
      :console
      (let [{:keys [access-token]} (oauth-authorize provider-code verifier)
            api-key (create-api-key access-token)]
        (swap! db* update-in [:auth provider] merge {:step :login/done
                                                     :type :auth/token
                                                     :api-key api-key})
        (f.login/login-done! ctx))
      :max
      (let [{:keys [access-token refresh-token expires-at]} (oauth-authorize provider-code verifier)]
        (swap! db* update-in [:auth provider] merge {:step :login/done
                                                     :type :auth/oauth
                                                     :refresh-token refresh-token
                                                     :api-key access-token
                                                     :expires-at expires-at})
        (f.login/login-done! ctx)))))

(defmethod f.login/login-step ["anthropic" :login/waiting-api-key] [{:keys [db* input provider send-msg!] :as ctx}]
  (if (string/starts-with? input "sk-")
    (do
      (config/update-global-config! {:providers {"anthropic" {:key input}}})
      (swap! db* update :auth dissoc provider)
      (send-msg! (format "API key and models saved to %s" (.getCanonicalPath (config/global-config-file))))
      (f.login/login-done! ctx))
    (send-msg! (format "Invalid API key '%s'" input))))

(defmethod f.login/login-step ["anthropic" :login/renew-token] [{:keys [db* provider] :as ctx}]
  (let [{:keys [refresh-token]} (get-in @db* [:auth provider])
        {:keys [refresh-token access-token expires-at]} (oauth-refresh refresh-token)]
    (swap! db* update-in [:auth provider] merge {:step :login/done
                                                 :type :auth/oauth
                                                 :refresh-token refresh-token
                                                 :api-key access-token
                                                 :expires-at expires-at})
    (f.login/login-done! ctx :silent? true)))
