(ns eca.llm-providers.copilot-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.client-test-helpers :refer [with-client-proxied]]
   [eca.llm-providers.copilot :as llm-providers.copilot]
   [eca.llm-providers.errors :as llm-providers.errors]
   [eca.messenger :as messenger]))

(def ^:private test-provider-settings
  "Provider settings using plain HTTP so requests route through the test proxy."
  {:auth {:url "http://localhost:99"}})

(deftest github-url-derivation-test
  (testing "defaults to github.com when no auth config"
    (is (= "https://github.com" (#'llm-providers.copilot/github-base-url {})))
    (is (= "https://api.github.com" (#'llm-providers.copilot/github-api-base-url {})))
    (is (= "Iv1.b507a08c87ecfe98" (#'llm-providers.copilot/copilot-client-id {}))))

  (testing "uses custom GitHub Enterprise URL"
    (let [settings {:auth {:url "https://ghe.example.com"}}]
      (is (= "https://ghe.example.com" (#'llm-providers.copilot/github-base-url settings)))
      (is (= "https://ghe.example.com/api/v3" (#'llm-providers.copilot/github-api-base-url settings)))))

  (testing "uses custom client ID"
    (let [settings {:auth {:clientId "custom-id"}}]
      (is (= "custom-id" (#'llm-providers.copilot/copilot-client-id settings)))))

  (testing "defaults client ID when only URL is overridden"
    (let [settings {:auth {:url "https://ghe.example.com"}}]
      (is (= "Iv1.b507a08c87ecfe98" (#'llm-providers.copilot/copilot-client-id settings))))))

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

        (let [result (#'llm-providers.copilot/oauth-url test-provider-settings)]

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
              result (#'llm-providers.copilot/oauth-access-token test-provider-settings device-code)]

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
              result (#'llm-providers.copilot/oauth-renew-token test-provider-settings access-token)]

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

(def ^:private test-auth
  {:api-url "http://localhost:99"
   :api-key "test-api-key"})

(defn ^:private answering-messenger
  "Messenger stub whose ask-question records params in `asked*` and answers `answer`."
  [asked* answer]
  (reify messenger/IMessenger
    (chat-content-received [_ _data])
    (ask-question [_ params]
      (reset! asked* params)
      (doto (promise) (deliver answer)))))

(deftest model-not-supported-error?-test
  (testing "matches 400 with model_not_supported in string body"
    (is (true? (llm-providers.copilot/model-not-supported-error?
                {:status 400
                 :body "{\"error\":{\"message\":\"The requested model is not supported.\",\"code\":\"model_not_supported\"}}"}))))

  (testing "matches 400 with parsed map body"
    (is (true? (llm-providers.copilot/model-not-supported-error?
                {:status 400 :body {:error {:code "model_not_supported"}}}))))

  (testing "ignores other statuses and errors"
    (is (false? (llm-providers.copilot/model-not-supported-error?
                 {:status 429 :body "model_not_supported"})))
    (is (false? (llm-providers.copilot/model-not-supported-error?
                 {:status 400 :body "{\"error\":{\"code\":\"bad_request\"}}"})))
    (is (false? (llm-providers.copilot/model-not-supported-error? {})))))

(deftest enrich-model-not-supported-error-test
  (testing "rewrites message with model and guidance, keeping error data"
    (let [error {:message "OpenAI response status: 400 body: model_not_supported"
                 :status 400
                 :body "{\"error\":{\"code\":\"model_not_supported\"}}"}
          enriched (llm-providers.copilot/enrich-model-not-supported-error error "gpt-6")]
      (is (string/includes? (:message enriched) "gpt-6"))
      (is (string/includes? (:message enriched) "not supported or not enabled"))
      (is (= 400 (:status enriched)))
      (is (= (:body error) (:body enriched)))))

  (testing "other errors pass through unchanged"
    (let [error {:message "boom" :status 500 :body "internal"}]
      (is (= error (llm-providers.copilot/enrich-model-not-supported-error error "gpt-6"))))))

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
               (llm-providers.copilot/fetch-model-policy test-auth "gated")))

        (is (= {:method "GET" :uri "/models"}
               (select-keys @req* [:method :uri])))
        (is (= "Bearer test-api-key" (get-in @req* [:headers "Authorization"])))

        (is (= {:listed? true :policy nil}
               (llm-providers.copilot/fetch-model-policy test-auth "no-policy-model")))
        (is (= {:listed? false :policy nil}
               (llm-providers.copilot/fetch-model-policy test-auth "missing"))))))

  (testing "returns nil when the catalog cannot be fetched"
    (with-client-proxied {}
      (fn handler [_req] {:status 401 :body "IDE token expired"})
      (is (nil? (llm-providers.copilot/fetch-model-policy test-auth "gated"))))))

(deftest enable-model-policy!-test
  (testing "POSTs enabled state for the model policy"
    (let [req* (atom nil)]
      (with-client-proxied {}
        (fn handler [req]
          (reset! req* req)
          {:status 200 :body {:state "enabled"}})

        (is (= {:enabled? true}
               (llm-providers.copilot/enable-model-policy! test-auth "gated")))

        (is (= {:method "POST" :uri "/models/gated/policy"}
               (select-keys @req* [:method :uri])))
        (is (= {:state "enabled"} (:body @req*)))
        (is (= "Bearer test-api-key" (get-in @req* [:headers "Authorization"]))))))

  (testing "returns failure details on error status"
    (with-client-proxied {}
      (fn handler [_req] {:status 403 :body {:message "forbidden"}})
      ;; error bodies are not json-coerced by the client, they stay raw strings
      (is (= {:enabled? false :status 403 :body "{\"message\":\"forbidden\"}"}
             (llm-providers.copilot/enable-model-policy! test-auth "gated"))))))

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
               (llm-providers.copilot/ask-to-enable-model!
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
               (llm-providers.copilot/ask-to-enable-model!
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
               (llm-providers.copilot/ask-to-enable-model!
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
               (llm-providers.copilot/ask-to-enable-model!
                {:messenger (answering-messenger asked* {:answer "Enable model"})
                 :chat-id "chat-1"
                 :auth test-auth
                 :model "open"})))
        (is (= {:result :no-policy :listed? false :policy nil}
               (llm-providers.copilot/ask-to-enable-model!
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
               (llm-providers.copilot/ask-to-enable-model!
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
               (llm-providers.copilot/ask-to-enable-model!
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
                     (with-redefs [llm-providers.copilot/ask-to-enable-model! (fn [_] result)]
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
