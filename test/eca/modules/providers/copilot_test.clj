(ns eca.modules.providers.copilot-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [hato.client :as http]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]
   [eca.client-test-helpers :refer [with-client-proxied]]
   [eca.llm-api.errors :as llm-providers.errors]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.modules.providers.copilot :as m.copilot]
   [eca.test-helper :as h]))

(def ^:private test-provider-settings
  "Provider settings using plain HTTP so requests route through the test proxy."
  {:auth {:url "http://localhost:99"}})

(deftest github-url-derivation-test
  (testing "defaults to github.com when no auth config"
    (is (= "https://github.com" (#'m.copilot/github-base-url {})))
    (is (= "https://api.github.com" (#'m.copilot/github-api-base-url {})))
    (is (= "Iv1.b507a08c87ecfe98" (#'m.copilot/copilot-client-id {}))))

  (testing "uses custom GitHub Enterprise URL"
    (let [settings {:auth {:url "https://ghe.example.com"}}]
      (is (= "https://ghe.example.com" (#'m.copilot/github-base-url settings)))
      (is (= "https://ghe.example.com/api/v3" (#'m.copilot/github-api-base-url settings)))))

  (testing "uses custom client ID"
    (let [settings {:auth {:clientId "custom-id"}}]
      (is (= "custom-id" (#'m.copilot/copilot-client-id settings)))))

  (testing "defaults client ID when only URL is overridden"
    (let [settings {:auth {:url "https://ghe.example.com"}}]
      (is (= "Iv1.b507a08c87ecfe98" (#'m.copilot/copilot-client-id settings))))))

(deftest oauth-url-test
  (testing "constructs GitHub device OAuth request and parses key response fields"
    (let [req* (atom nil)]
      (with-client-proxied {}

        (fn handler [req]
          (reset! req* req)
          {:status 200
           :body {:user_code        "USER-CODE"
                  :device_code      "DEVICE-CODE"
                  :verification_uri "https://github.com/login/device"}})

        (let [result (#'m.copilot/oauth-url test-provider-settings)]

          ;; request validation
          (is (= {:method "POST"
                  :uri    "/login/device/code"}
                 (select-keys @req* [:method :uri])))

          (is (= {:client_id "Iv1.b507a08c87ecfe98"
                  :scope     "read:user"}
                 (:body @req*))
              "Outgoing payload should match device-code request")

          ;; response parsing
          (is (= {:user-code   "USER-CODE"
                  :device-code "DEVICE-CODE"
                  :url         "https://github.com/login/device"}
                 result)))))))

(deftest oauth-access-token-test
  (testing "builds device access-token request and parses access token"
    (let [req* (atom nil)]
      (with-client-proxied {}

        (fn handler [req]
          (reset! req* req)
          {:status 200
           :body {:access_token "gh-access-token"}})

        (let [device-code "device-code-123"
              result (#'m.copilot/oauth-access-token test-provider-settings device-code)]

          ;; request validation
          (is (= {:method "POST"
                  :uri    "/login/oauth/access_token"}
                 (select-keys @req* [:method :uri])))

          (is (= {:client_id   "Iv1.b507a08c87ecfe98"
                  :device_code device-code
                  :grant_type  "urn:ietf:params:oauth:grant-type:device_code"}
                 (:body @req*))
              "Outgoing payload should match access-token exchange")

          ;; response parsing
          (is (= "gh-access-token" result)))))))

(deftest oauth-renew-token-test
  (testing "sends token renewal request and extracts API key and expiry"
    (let [req* (atom nil)]
      (with-client-proxied {}

        (fn handler [req]
          (reset! req* req)
          {:status 200
           :body {:token      "copilot-api-key"
                  :expires_at 9999999999
                  :endpoints  {:api "https://copilot-proxy.ghe.example.com"}}})

        (let [access-token "gh-access-123"
              result (#'m.copilot/oauth-renew-token test-provider-settings access-token)]

          ;; request validation — uses /api/v3 prefix since test settings use a custom auth URL
          (is (= {:method "GET"
                  :uri    "/api/v3/copilot_internal/v2/token"}
                 (select-keys @req* [:method :uri])))

          (is (= {"authorization" (str "token " access-token)
                  "Content-Type" "application/json"
                  "Accept" "application/json"
                  "editor-plugin-version" "eca/*"}
                 (select-keys (:headers @req*) ["authorization" "Content-Type" "Accept" "editor-plugin-version"]))
              (str "Headers should include auth headers and access-token: " (:headers @req*)))

          ;; response parsing — includes api-url from endpoints.api
          (is (= {:api-key    "copilot-api-key"
                  :expires-at 9999999999
                  :api-url    "https://copilot-proxy.ghe.example.com"}
                 result)))))))

(deftest oauth-renew-token-retry-test
  (testing "retries one transient server failure"
    (let [requests* (atom 0)]
      (with-client-proxied {}
        (fn handler [_req]
          (if (= 1 (swap! requests* inc))
            {:status 503 :body "<html>temporary outage</html>"}
            {:status 200
             :body {:token "copilot-api-key"
                    :expires_at 9999999999}}))

        (is (= {:api-key "copilot-api-key"
                :expires-at 9999999999}
               (#'m.copilot/oauth-renew-token test-provider-settings "gh-access-123")))
        (is (= 2 @requests*))))))

(deftest oauth-renew-token-server-error-test
  (testing "preserves the response body after retrying a gateway error"
    (let [requests* (atom 0)
          error (with-client-proxied {}
                  (fn handler [_req]
                    (swap! requests* inc)
                    {:status 502 :body "<html>bad gateway</html>"})

                  (try
                    (#'m.copilot/oauth-renew-token test-provider-settings "gh-access-123")
                    nil
                    (catch Exception e e)))]
      (is (= 2 @requests*))
      (is (= "Error on copilot login: <html>bad gateway</html>" (ex-message error)))
      (is (= {:status 502 :body "<html>bad gateway</html>"} (ex-data error)))))

  (testing "does not retry other 5xx statuses"
    (let [requests* (atom 0)
          error (with-client-proxied {}
                  (fn handler [_req]
                    (swap! requests* inc)
                    {:status 511 :body "network authentication required"})

                  (try
                    (#'m.copilot/oauth-renew-token test-provider-settings "gh-access-123")
                    nil
                    (catch Exception e e)))]
      (is (= 1 @requests*))
      (is (= {:status 511 :body "network authentication required"} (ex-data error))))))

(deftest poll-device-authorization!-test
  (testing "completes with token data when github authorizes"
    (let [db* (atom {:auth {"github-copilot" {:step :login/waiting-user-confirmation
                                              :device-code "device-123"}}})
          post-calls* (atom 0)
          result* (promise)]
      (with-client-proxied {}
        (fn handler [req]
          (if (= "POST" (:method req))
            (do (swap! post-calls* inc)
                (if (< @post-calls* 3)
                  ;; github answers 200 with an error body while authorization is pending
                  {:status 200 :body {:error "authorization_pending"}}
                  {:status 200 :body {:access_token "gh-access"}}))
            {:status 200 :body {:token "copilot-api-key" :expires_at 9999999999}}))
        (#'m.copilot/poll-device-authorization!
         "github-copilot"
         test-provider-settings
         db*
         {:interval-ms 10
          :max-attempts 20
          :on-token (fn [token-data] (deliver result* token-data))
          :on-timeout (fn [] (deliver result* :timeout))})
        (is (= {:api-key "copilot-api-key"
                :expires-at 9999999999
                :access-token "gh-access"}
               (deref result* 5000 :never-completed))))))

  (testing "calls on-timeout when attempts are exhausted"
    (let [db* (atom {:auth {"github-copilot" {:step :login/waiting-user-confirmation
                                              :device-code "device-123"}}})
          result* (promise)]
      (with-client-proxied {}
        (fn handler [_req] {:status 200 :body {:error "authorization_pending"}})
        (#'m.copilot/poll-device-authorization!
         "github-copilot"
         test-provider-settings
         db*
         {:interval-ms 10
          :max-attempts 2
          :on-token (fn [token-data] (deliver result* token-data))
          :on-timeout (fn [] (deliver result* :timeout))})
        (is (= :timeout (deref result* 5000 :never-completed))))))

  (testing "stops silently when the login was cancelled"
    (let [db* (atom {:auth {"github-copilot" {}}})
          called* (atom false)]
      (with-client-proxied {}
        (fn handler [_req] {:status 200 :body {:access_token "gh-access"}})
        (let [polling (#'m.copilot/poll-device-authorization!
                       "github-copilot"
                       test-provider-settings
                       db*
                       {:interval-ms 10
                        :max-attempts 2
                        :on-token (fn [_] (reset! called* true))
                        :on-timeout (fn [] (reset! called* true))})]
          @polling
          (is (false? @called*)))))))

(def ^:private test-auth
  {:api-url "http://localhost:99"
   :api-key "test-api-key"})

(defn ^:private answering-messenger
  "Messenger stub whose ask-question records params in `asked*` and answers `answer`."
  [asked* answer]
  (h/stub-messenger
   {:ask-question (fn [params]
                    (reset! asked* params)
                    (doto (promise) (deliver answer)))}))

(deftest model-not-supported-error?-test
  (testing "matches 400 with model_not_supported in string body"
    (is (true? (m.copilot/model-not-supported-error?
                {:status 400
                 :body "{\"error\":{\"message\":\"The requested model is not supported.\",\"code\":\"model_not_supported\"}}"}))))

  (testing "matches 400 with parsed map body"
    (is (true? (m.copilot/model-not-supported-error?
                {:status 400 :body {:error {:code "model_not_supported"}}}))))

  (testing "ignores other statuses and errors"
    (is (false? (m.copilot/model-not-supported-error?
                 {:status 429 :body "model_not_supported"})))
    (is (false? (m.copilot/model-not-supported-error?
                 {:status 400 :body "{\"error\":{\"code\":\"bad_request\"}}"})))
    (is (false? (m.copilot/model-not-supported-error? {})))))

(deftest enrich-model-not-supported-error-test
  (testing "rewrites message with model and guidance, keeping error data"
    (let [error {:message "OpenAI response status: 400 body: model_not_supported"
                 :status 400
                 :body "{\"error\":{\"code\":\"model_not_supported\"}}"}
          enriched (m.copilot/enrich-model-not-supported-error error "gpt-6")]
      (is (string/includes? (:message enriched) "gpt-6"))
      (is (string/includes? (:message enriched) "not supported or not enabled"))
      (is (= 400 (:status enriched)))
      (is (= (:body error) (:body enriched)))))

  (testing "other errors pass through unchanged"
    (let [error {:message "boom" :status 500 :body "internal"}]
      (is (= error (m.copilot/enrich-model-not-supported-error error "gpt-6"))))))

(def ^:private models-catalog-response
  {:status 200
   :body {:data [{:id "gated" :policy {:state "disabled" :terms "The terms."}}
                 {:id "open" :policy {:state "enabled" :terms "T"}}
                 {:id "no-policy-model"}]}})

(deftest fetch-model-policy-test
  (testing "returns policy entry for models in the catalog"
    (let [req* (atom nil)]
      (with-client-proxied {}
        (fn handler [req]
          (reset! req* req)
          models-catalog-response)

        (is (= {:listed? true :policy {:state "disabled" :terms "The terms."}}
               (m.copilot/fetch-model-policy test-auth "gated")))

        (is (= {:method "GET" :uri "/models"}
               (select-keys @req* [:method :uri])))
        (is (= "Bearer test-api-key" (get-in @req* [:headers "Authorization"])))

        (is (= {:listed? true :policy nil}
               (m.copilot/fetch-model-policy test-auth "no-policy-model")))
        (is (= {:listed? false :policy nil}
               (m.copilot/fetch-model-policy test-auth "missing"))))))

  (testing "returns nil when the catalog cannot be fetched"
    (with-client-proxied {}
      (fn handler [_req] {:status 401 :body "IDE token expired"})
      (is (nil? (m.copilot/fetch-model-policy test-auth "gated"))))))

(deftest enable-model-policy!-test
  (testing "POSTs enabled state for the model policy"
    (let [req* (atom nil)]
      (with-client-proxied {}
        (fn handler [req]
          (reset! req* req)
          {:status 200 :body {:state "enabled"}})

        (is (= {:enabled? true}
               (m.copilot/enable-model-policy! test-auth "gated")))

        (is (= {:method "POST" :uri "/models/gated/policy"}
               (select-keys @req* [:method :uri])))
        (is (= {:state "enabled"} (:body @req*)))
        (is (= "Bearer test-api-key" (get-in @req* [:headers "Authorization"]))))))

  (testing "returns failure details on error status"
    (with-client-proxied {}
      (fn handler [_req] {:status 403 :body {:message "forbidden"}})
      ;; error bodies are not json-coerced by the client, they stay raw strings
      (is (= {:enabled? false :status 403 :body "{\"message\":\"forbidden\"}"}
             (m.copilot/enable-model-policy! test-auth "gated"))))))

(deftest ask-to-enable-model!-test
  (testing "enables the model policy when user accepts"
    (let [asked* (atom nil)
          post* (atom nil)]
      (with-client-proxied {}
        (fn handler [req]
          (if (= "POST" (:method req))
            (do (reset! post* req)
                {:status 200 :body {:state "enabled"}})
            (if @post*
              ;; after enabling, the catalog verification sees the policy enabled
              {:status 200
               :body {:data [{:id "gated" :policy {:state "enabled" :terms "The terms."}}]}}
              models-catalog-response)))

        (is (= {:result :enabled}
               (m.copilot/ask-to-enable-model!
                {:messenger (answering-messenger asked* {:answer "Enable model"})
                 :chat-id "chat-1"
                 :auth test-auth
                 :model "gated"})))

        ;; question shows the policy terms and targets the chat
        (is (= "chat-1" (:chatId @asked*)))
        (is (false? (:allowFreeform @asked*)))
        (is (string/includes? (:question @asked*) "The terms."))
        (is (= ["Enable model" "Cancel"] (mapv :label (:options @asked*))))

        (is (= "/models/gated/policy" (:uri @post*))))))

  (testing "declining keeps the model policy untouched"
    (let [asked* (atom nil)
          post* (atom nil)]
      (with-client-proxied {}
        (fn handler [req]
          (if (= "POST" (:method req))
            (do (reset! post* req) {:status 200 :body {}})
            models-catalog-response))

        (is (= {:result :declined}
               (m.copilot/ask-to-enable-model!
                {:messenger (answering-messenger asked* {:answer "Cancel"})
                 :chat-id "chat-1"
                 :auth test-auth
                 :model "gated"})))
        (is (nil? @post*)))))

  (testing "cancelled question keeps the model policy untouched"
    (let [asked* (atom nil)
          post* (atom nil)]
      (with-client-proxied {}
        (fn handler [req]
          (if (= "POST" (:method req))
            (do (reset! post* req) {:status 200 :body {}})
            models-catalog-response))

        (is (= {:result :cancelled}
               (m.copilot/ask-to-enable-model!
                {:messenger (answering-messenger asked* {:cancelled true})
                 :chat-id "chat-1"
                 :auth test-auth
                 :model "gated"})))
        (is (nil? @post*)))))

  (testing "does not ask for already enabled or unknown models"
    (let [asked* (atom nil)]
      (with-client-proxied {}
        (fn handler [_req] models-catalog-response)

        (is (= {:result :no-policy :listed? true :policy {:state "enabled" :terms "T"}}
               (m.copilot/ask-to-enable-model!
                {:messenger (answering-messenger asked* {:answer "Enable model"})
                 :chat-id "chat-1"
                 :auth test-auth
                 :model "open"})))
        (is (= {:result :no-policy :listed? false :policy nil}
               (m.copilot/ask-to-enable-model!
                {:messenger (answering-messenger asked* {:answer "Enable model"})
                 :chat-id "chat-1"
                 :auth test-auth
                 :model "missing"})))
        (is (nil? @asked*)))))

  (testing "reports enable request failures"
    (let [asked* (atom nil)]
      (with-client-proxied {}
        (fn handler [req]
          (if (= "POST" (:method req))
            {:status 403 :body {:message "forbidden"}}
            models-catalog-response))

        (is (= {:result :enable-failed :enabled? false :status 403 :body "{\"message\":\"forbidden\"}"}
               (m.copilot/ask-to-enable-model!
                {:messenger (answering-messenger asked* {:answer "Enable model"})
                 :chat-id "chat-1"
                 :auth test-auth
                 :model "gated"}))))))

  (testing "reports plan-rejected enablement when the 200 answer is a no-op"
    (let [asked* (atom nil)]
      (with-client-proxied {}
        (fn handler [req]
          (if (= "POST" (:method req))
            {:status 200 :body ""}
            ;; catalog still reports the policy disabled after the POST
            models-catalog-response))

        (is (= {:result :enable-rejected :policy {:state "disabled" :terms "The terms."}}
               (m.copilot/ask-to-enable-model!
                {:messenger (answering-messenger asked* {:answer "Enable model"})
                 :chat-id "chat-1"
                 :auth test-auth
                 :model "gated"})))))))

(deftest error-recovery-extensions-test
  (testing "enrich-provider-error dispatches to the copilot enrichment"
    (let [error {:message "raw" :status 400 :body "{\"error\":{\"code\":\"model_not_supported\"}}"}]
      (is (string/includes?
           (:message (llm-providers.errors/enrich-provider-error
                      {:provider "github-copilot" :model "gpt-6" :error-data error}))
           "gpt-6"))))

  (testing "recoverable-error? requires a matching error and the ask-question capability"
    (let [error {:status 400 :body "{\"error\":{\"code\":\"model_not_supported\"}}"}
          db-with-capability {:client-capabilities {:code-assistant {:chat-capabilities {:ask-question true}}}}]
      (is (true? (llm-providers.errors/recoverable-error?
                  {:provider "github-copilot" :error-data error :db db-with-capability})))
      (is (false? (llm-providers.errors/recoverable-error?
                   {:provider "github-copilot" :error-data error :db {}})))
      (is (false? (llm-providers.errors/recoverable-error?
                   {:provider "github-copilot" :error-data {:status 500 :body "boom"} :db db-with-capability})))))

  (testing "recover-error! maps consent outcomes to generic recovery outcomes"
    (let [recover! (fn [result]
                     (with-redefs [m.copilot/ask-to-enable-model! (fn [_] result)]
                       (llm-providers.errors/recover-error!
                        {:provider "github-copilot"
                         :model "gpt-6"
                         :db {}
                         :chat-id "chat-1"
                         :messenger (answering-messenger (atom nil) {:answer "Enable model"})})))]
      (let [outcome (recover! {:result :enabled})]
        (is (true? (:retry? outcome)))
        (is (string/includes? (:notice outcome) "enabled"))
        (is (string? (:retry-user-message outcome))))
      (let [outcome (recover! {:result :declined})]
        (is (false? (:retry? outcome)))
        (is (string/includes? (:notice outcome) "not enabled")))
      (let [outcome (recover! {:result :enable-rejected})]
        (is (false? (:retry? outcome)))
        (is (string/includes? (:notice outcome) "Copilot plan")))
      (let [outcome (recover! {:result :enable-failed :status 403})]
        (is (false? (:retry? outcome)))
        (is (string/includes? (:notice outcome) "Failed to enable")))
      (let [outcome (recover! {:result :no-policy})]
        (is (false? (:retry? outcome)))
        (is (nil? (:notice outcome)))))))

(deftest models-override-catalog-metadata-test
  (with-redefs [http/get (fn [_url _opts]
                           {:status 200
                            :body {:data [{:id "gpt-responses"
                                           :supported_endpoints ["/responses" "/chat/completions"]
                                           :capabilities {:supports {:reasoning_effort ["low" "max"]}}}
                                          {:id "gpt-chat"
                                           :supported_endpoints ["/chat/completions"]
                                           :capabilities {:supports {:reasoning_effort ["low" "high"]}}}
                                          {:id "claude-adaptive"
                                           :supported_endpoints ["/chat/completions" "/responses" "/v1/messages"]
                                           :capabilities {:supports {:adaptive_thinking true
                                                                     :reasoning_effort ["low" "high"]}}}
                                          {:id "claude-opus-4-7"
                                           :supported_endpoints ["/v1/messages"]
                                           :capabilities {:supports {:adaptive_thinking true
                                                                     :reasoning_effort ["high"]}}}
                                          {:id "claude-adaptive-budget"
                                           :supported_endpoints ["/v1/messages"]
                                           :capabilities {:supports {:adaptive_thinking true
                                                                     :max_thinking_budget 10000}}}
                                          {:id "claude-budget"
                                           :supported_endpoints ["/v1/messages"]
                                           :capabilities {:supports {:max_thinking_budget 10000}}}
                                          {:id "claude-small-budget"
                                           :supported_endpoints ["/v1/messages"]
                                           :capabilities {:supports {:min_thinking_budget 1400
                                                                     :max_thinking_budget 1500}}}
                                          {:id "claude-invalid-budget"
                                           :supported_endpoints ["/v1/messages"]
                                           :capabilities {:supports {:max_thinking_budget 1000}}}]}})]
    (is (match?
         {"gpt-responses" {:discovered-api :openai-responses
                           :discovered-reason? true
                           :discovered-variants
                           {"low" {:reasoning {:effort "low" :summary "auto"}}
                            "max" {:reasoning {:effort "max" :summary "auto"}}}}
          "gpt-chat" {:discovered-api :openai-chat
                      :discovered-reason? true
                      :discovered-variants
                      {"low" {:reasoning_effort "low"}
                       "high" {:reasoning_effort "high"}}}
          "claude-adaptive" {:discovered-api :anthropic
                             :discovered-reason? true
                             :discovered-variants
                             {"default" {:thinking {:type "adaptive"}}
                              "low" {:thinking {:type "adaptive"}
                                     :output_config {:effort "low"}}
                              "high" {:thinking {:type "adaptive"}
                                      :output_config {:effort "high"}}}}
          "claude-opus-4-7" {:discovered-api :anthropic
                             :discovered-reason? true
                             :discovered-variants
                             {"default" {:thinking {:type "adaptive" :display "summarized"}}
                              "high" {:thinking {:type "adaptive" :display "summarized"}
                                      :output_config {:effort "high"}}}}
          "claude-adaptive-budget" {:discovered-api :anthropic
                                    :discovered-reason? true
                                    :discovered-variants
                                    (m/equals {"default" {:thinking {:type "adaptive"}}})}
          "claude-budget" {:discovered-api :anthropic
                           :discovered-reason? true
                           :discovered-variants
                           {"high" {:thinking {:type "enabled" :budget_tokens 5000}}
                            "max" {:thinking {:type "enabled" :budget_tokens 9999}}}}
          "claude-small-budget" {:discovered-api :anthropic
                                 :discovered-reason? true
                                 :discovered-variants
                                 {"high" {:thinking {:type "enabled" :budget_tokens 1400}}
                                  "max" {:thinking {:type "enabled" :budget_tokens 1499}}}}
          "claude-invalid-budget" {:discovered-api :anthropic
                                   :discovered-reason? true}}
         (#'m.copilot/models-override
          {:provider "github-copilot"
           :api-url "https://api.githubcopilot.com"
           :auth-type :auth/oauth
           :api-key "tok"})))))

(deftest models-override-picker-filter-test
  (let [fetch (fn []
                (#'m.copilot/models-override
                 {:provider "github-copilot"
                  :api-url "https://api.githubcopilot.com"
                  :auth-type :auth/oauth
                  :api-key "tok"}))]
    (testing "Skips entries hidden from the picker or with a disabled policy, keeps enabled, unconfigured and unflagged ones"
      (with-redefs [http/get (fn [_url _opts]
                               {:status 200
                                :body {:data [{:id "gpt-5.4"
                                               :model_picker_enabled true
                                               :model_picker_category "powerful"
                                               :policy {:state "enabled"}
                                               :supported_endpoints ["/responses"]}
                                              {:id "claude-sonnet-5"
                                               :model_picker_enabled true
                                               :model_picker_category "versatile"
                                               :policy {:state "unconfigured" :terms "Enable access"}
                                               :supported_endpoints ["/v1/messages"]}
                                              {:id "unflagged-model"
                                               :supported_endpoints ["/chat/completions"]}
                                              {:id "claude-opus-5"
                                               :model_picker_enabled true
                                               :model_picker_category "powerful"
                                               :policy {:state "disabled"}
                                               :supported_endpoints ["/v1/messages"]}
                                              {:id "auto-model-3"
                                               :model_picker_enabled false
                                               :supported_endpoints ["/chat/completions"]}
                                              {:id "text-embedding-3-small"
                                               :model_picker_enabled false}
                                              {:id "exec-agent-a"
                                               :model_picker_enabled false
                                               :policy {:state "enabled"}
                                               :supported_endpoints ["/chat/completions"]}]}})]
        (is (match?
             (m/equals
              {"gpt-5.4" {:discovered-api :openai-responses
                          :discovered-provider-data {:picker-category "powerful"}}
               "claude-sonnet-5" {:discovered-api :anthropic
                                  :discovered-provider-data {:picker-category "versatile"}}
               "unflagged-model" {:discovered-api :openai-chat}})
             (fetch)))))

    (testing "Hidden entries listed in the provider models config keep their discovered metadata, by id or modelName alias"
      (with-redefs [http/get (fn [_url _opts]
                               {:status 200
                                :body {:data [{:id "gpt-5.6-sol"
                                               :model_picker_enabled true
                                               :policy {:state "disabled"}
                                               :supported_endpoints ["/responses" "ws:/responses"]
                                               :capabilities {:supports {:reasoning_effort ["low" "max"]}}}
                                              {:id "claude-opus-5"
                                               :model_picker_enabled true
                                               :policy {:state "disabled"}
                                               :supported_endpoints ["/v1/messages"]}
                                              {:id "exec-agent-a"
                                               :model_picker_enabled false
                                               :supported_endpoints ["/chat/completions"]}
                                              {:id "gpt-6-astra"
                                               :model_picker_enabled true
                                               :policy {:state "disabled"}
                                               :supported_endpoints ["/responses"]}]}})]
        (is (match?
             (m/equals
              {"gpt-5.6-sol" {:discovered-api :openai-responses
                              :discovered-reason? true
                              :discovered-variants {"low" {:reasoning {:effort "low" :summary "auto"}}
                                                    "max" {:reasoning {:effort "max" :summary "auto"}}}}
               "claude-opus-5" {:discovered-api :anthropic}
               "exec-agent-a" {:discovered-api :openai-chat}})
             (#'m.copilot/models-override
              {:provider "github-copilot"
               :api-url "https://api.githubcopilot.com"
               :auth-type :auth/oauth
               :api-key "tok"
               :static-models {"gpt-5.6-sol" {}
                               "opus" {:modelName "github-copilot/claude-opus-5"}
                               "agent" {:modelName "exec-agent-a"}
                               "other-provider-alias" {:modelName "openai/gpt-6-astra"}}})))))

    (testing "A catalog with entries but none usable is an empty map, not nil, so models.dev is not used as fallback"
      (let [warnings* (atom [])]
        (with-redefs [http/get (fn [_url _opts]
                                 {:status 200
                                  :body {:data [{:id "gpt-5.5"
                                                 :model_picker_enabled true
                                                 :policy {:state "disabled"}}
                                                {:id "auto-model-3"
                                                 :model_picker_enabled false}]}})
                      logger/warn (fn [& args] (swap! warnings* conj (apply str args)) nil)]
          (is (= {} (fetch)))
          (is (some #(re-find #"none is available for this account's plan" %) @warnings*)))))

    (testing "An empty catalog or a failed fetch gives no models, for models.dev's, without the generic fetch"
      (with-redefs [http/get (fn [_url _opts] {:status 200 :body {:data []}})]
        (is (= llm-util/no-native-models (fetch))))
      (with-redefs [http/get (fn [_url _opts] {:status 503 :body {:error "temporary"}})
                    logger/warn (fn [& _] nil)]
        (is (= llm-util/no-native-models (fetch)))))))
