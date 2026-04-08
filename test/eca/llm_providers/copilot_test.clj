(ns eca.llm-providers.copilot-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.client-test-helpers :refer [with-client-proxied]]
   [eca.llm-providers.copilot :as llm-providers.copilot]))

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
                  :expires_at 9999999999}})

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

          ;; response parsing
          (is (= {:api-key   "copilot-api-key"
                  :expires-at 9999999999}
                 result)))))))
