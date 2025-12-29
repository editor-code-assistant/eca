(ns eca.llm-providers.copilot-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.client-test-helpers :refer [with-client-proxied]]
   [eca.llm-providers.copilot :as llm-providers.copilot]))

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

        (let [result
              (with-redefs [llm-providers.copilot/oauth-login-device-url
                            "http://localhost:99/login/device/code"]
                (#'llm-providers.copilot/oauth-url))]

          ;; request validation
          (is (= {:method "POST"
                  :uri    "/login/device/code"}
                 (select-keys @req* [:method :uri])))

          (is (= {:client_id @#'llm-providers.copilot/client-id
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
              result
              (with-redefs [llm-providers.copilot/oauth-login-access-token-url
                            "http://localhost:99/login/oauth/access_token"]
                (#'llm-providers.copilot/oauth-access-token device-code))]

          ;; request validation
          (is (= {:method "POST"
                  :uri    "/login/oauth/access_token"}
                 (select-keys @req* [:method :uri])))

          (is (= {:client_id   @#'llm-providers.copilot/client-id
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
              result
              (with-redefs [llm-providers.copilot/oauth-copilot-token-url
                            "http://localhost:99/copilot_internal/v2/token"]
                (#'llm-providers.copilot/oauth-renew-token access-token))]

          ;; request validation
          (is (= {:method "GET"
                  :uri    "/copilot_internal/v2/token"}
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
