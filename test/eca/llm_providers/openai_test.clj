(ns eca.llm-providers.openai-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.client-test-helpers :refer [with-client-proxied]]
   [eca.llm-providers.openai :as llm-providers.openai]
   [matcher-combinators.test :refer [match?]]))

(deftest base-responses-req-test
  (testing "sends a responses request and extracts output text"
    (let [req* (atom nil)]
      (with-client-proxied {:version :http-2}
        (fn [req]
          (reset! req* req)
          ;; fake a successful non-stream JSON response
          {:status 200
           :body {:output [{:content [{:text "Hello from responses!"}]}]}})

        (let [body {:model "mymodel"
                    :input "hi"
                    :stream false}
              response (#'llm-providers.openai/base-responses-request!
                        {:rid "r1"
                         :api-key "fake-key"
                         :api-url "http://localhost:1"
                         :body body
                         :url-relative-path "/v1/responses"})]

          (is (= {:method "POST"
                  :uri "/v1/responses"
                  :body body}
                 (select-keys @req* [:method :uri :body])))

          ;; parsed response
          (is (= {:output-text "Hello from responses!"}
                 (select-keys response [:output-text]))))))))

(deftest oauth-authorize-test
  (testing "that OAuth token exchange is routed through the http proxy"
    (let [req* (atom nil)
          now-seconds (quot (System/currentTimeMillis) 1000)]
      (with-client-proxied {}

        (fn handler [req]
          ;; capture the outgoing request
          (reset! req* req)
          ;; fake token endpoint response
          {:status 200
           :body {:refresh_token "r-token"
                  :access_token  "a-token"
                  :expires_in     3600}})

        (let [server-url "http://localhost/callback"
              code        "abc123"
              verifier    "verifierXYZ"
              result      (with-redefs [llm-providers.openai/oauth-token-url "http://localhost:99/oauth/token"]
                            (#'llm-providers.openai/oauth-authorize
                             server-url code verifier))]

          ;; request validation
          (is (= {:method "POST"
                  :uri    "/oauth/token"}
                 (select-keys @req* [:method :uri])))

          (is (= {:grant_type     "authorization_code"
                  :client_id      @#'llm-providers.openai/client-id
                  :code           code
                  :code_verifier  verifier
                  :redirect_uri   server-url}
                 (:body @req*))
              "Outgoing payload should match token-exchange fields")

          ;; response parsing
          (is (= "r-token" (:refresh-token result)))
          (is (= "a-token" (:access-token result)))
          ;; expires-at should be > now
          (is (> (:expires-at result) now-seconds)
              "expires-at should be computed relative to current time"))))))

(deftest ->normalize-messages-test
  (testing "no previous history"
    (is (match?
         []
         (#'llm-providers.openai/normalize-messages [] true))))

  (testing "With basic text history"
    (is (match?
         [{:role "user" :content [{:type "input_text" :text "Count with me: 1"}]}
          {:role "assistant" :content "2"}]
         (#'llm-providers.openai/normalize-messages
          [{:role "user" :content [{:type :text :text "Count with me: 1"}]}
           {:role "assistant" :content "2"}]
          true))))
  (testing "With tool_call history"
    (is (match?
         [{:role "user" :content [{:type "input_text" :text "List the files you are allowed"}]}
          {:role "assistant" :content [{:type "output_text" :text "Ok!"}]}
          {:type "function_call"
           :call_id "call-1"
           :name "eca__list_allowed_directories"
           :arguments "{}"}
          {:type "function_call_output"
           :call_id "call-1"
           :output "Allowed directories: /foo/bar\n"}
          {:role "assistant" :content [{:type "output_text" :text "I see /foo/bar"}]}]
         (#'llm-providers.openai/normalize-messages
          [{:role "user" :content [{:type :text :text "List the files you are allowed"}]}
           {:role "assistant" :content [{:type :text :text "Ok!"}]}
           {:role "tool_call" :content {:id "call-1" :full-name "eca__list_allowed_directories" :arguments {}}}
           {:role "tool_call_output" :content {:id "call-1"
                                               :full-name "eca__list_allowed_directories"
                                               :arguments {}
                                               :output {:contents [{:type :text
                                                                    :error false
                                                                    :text "Allowed directories: /foo/bar"}]}}}
           {:role "assistant" :content [{:type :text :text "I see /foo/bar"}]}]
          true)))))
