(ns eca.llm-providers.anthropic-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.client-test-helpers :refer [with-client-proxied]]
   [eca.llm-providers.anthropic :as llm-providers.anthropic]
   [matcher-combinators.test :refer [match?]]))

(deftest base-request-test
  (testing "constructs an Anthropics API request and extracts completion text"
    (let [req* (atom nil)
          fake-response {:content [{:text "Hello from Anthropics proxy!"}]}]
      (with-client-proxied {}

        (fn handler [req]
          (reset! req* req)
          {:status 200
           :body fake-response})

        (let [body {:model "claude-v1"
                    :input "hi"
                    :stream false}
              response (#'llm-providers.anthropic/base-request!
                        {:rid "r1"
                         :api-key "fake-key"
                         :api-url "http://localhost:1"
                         :body body
                         :url-relative-path "/v1/messages"
                         :auth-type :auth/key})]

          (is (= {:method "POST"
                  :uri "/v1/messages"
                  :body body}
                 (select-keys @req* [:method :uri :body])))

          (is (= {:output-text "Hello from Anthropics proxy!"}
                 (select-keys response [:output-text]))))))))

(deftest oauth-authorize-test
  (testing "exchanges an OAuth code for tokens and returns refresh/access tokens with expiry"
    (let [req* (atom nil)
          now-seconds (quot (System/currentTimeMillis) 1000)]
      (with-client-proxied {}

        (fn handler [req]
          (reset! req* req)
          {:status 200
           :body {:refresh_token "r-token"
                  :access_token  "a-token"
                  :expires_in    3600}})

        (let [raw-code   "abc123#stateXYZ"
              verifier   "verifierXYZ"
              [code state] (string/split raw-code #"#")
              result     (with-redefs [llm-providers.anthropic/oauth-token-url
                                       "http://localhost:99/v1/oauth/token"]
                           (#'llm-providers.anthropic/oauth-authorize
                            raw-code verifier))]

          (is (= {:method "POST"
                  :uri    "/v1/oauth/token"}
                 (select-keys @req* [:method :uri])))

          (is (= {:grant_type    "authorization_code"
                  :code          code
                  :state         state
                  :client_id     @#'llm-providers.anthropic/client-id
                  :redirect_uri  "https://console.anthropic.com/oauth/code/callback"
                  :code_verifier verifier}
                 (:body @req*))
              "Outgoing payload should match token-exchange fields")

          (is (= "r-token" (:refresh-token result)))
          (is (= "a-token" (:access-token result)))

          ;; expires-at should be > now
          (is (> (:expires-at result) now-seconds)
              "expires-at should be computed relative to current time"))))))

(deftest oauth-refresh-test
  (testing "refreshes an OAuth token and returns new refresh/access tokens with expiry"
    (let [req* (atom nil)
          now-seconds (quot (System/currentTimeMillis) 1000)]
      (with-client-proxied {}

        (fn handler [req]
          (reset! req* req)
          {:status 200
           :body {:refresh_token "new-r-token"
                  :access_token  "new-a-token"
                  :expires_in    3600}})

        (let [refresh-token "old-r-token"
              result        (with-redefs [llm-providers.anthropic/oauth-token-url
                                          "http://localhost:99/v1/oauth/token"]
                              (#'llm-providers.anthropic/oauth-refresh refresh-token))]

          (is (= {:method "POST"
                  :uri    "/v1/oauth/token"}
                 (select-keys @req* [:method :uri])))

          (is (= {:grant_type    "refresh_token"
                  :refresh_token refresh-token
                  :client_id     @#'llm-providers.anthropic/client-id}
                 (:body @req*))
              "Outgoing payload should match refresh-token fields")

          (is (= "new-r-token" (:refresh-token result)))
          (is (= "new-a-token" (:access-token result)))

          ;; expires-at should be > now
          (is (> (:expires-at result) now-seconds)
              "expires-at should be computed relative to current time"))))))

(deftest create-api-key-test
  (testing "creates a new API key and sets the appropriate authorization headers"
    (let [req* (atom nil)]
      (with-client-proxied {}

        (fn handler [req]
          (reset! req* req)
          {:status 200
           :body {:raw_key "sk-ant-test-key"}})

        (let [access-token "access-123"
              result       (with-redefs [llm-providers.anthropic/create-api-key-url
                                         "http://localhost:99/api/oauth/claude_cli/create_api_key"]
                             (#'llm-providers.anthropic/create-api-key access-token))]

          (is (= {:method "POST"
                  :uri    "/api/oauth/claude_cli/create_api_key"}
                 (select-keys @req* [:method :uri])))

          (is (= {"Authorization" "Bearer access-123"
                  "Content-Type"  "application/x-www-form-urlencoded"
                  "Accept"        "application/json, text/plain, */*"}
                 (select-keys (:headers @req*) ["Authorization" "Content-Type" "Accept"]))
              "Authorization and content headers should be set")
          (is (= "sk-ant-test-key" result)))))))

(deftest ->normalize-messages-test
  (testing "no previous history"
    (is (match?
         []
         (#'llm-providers.anthropic/normalize-messages [] true))))
  (testing "With basic text history"
    (is (match?
         [{:role "user" :content "Count with me: 1"}
          {:role "assistant" :content "2"}]
         (#'llm-providers.anthropic/normalize-messages
          [{:role "user" :content "Count with me: 1"}
           {:role "assistant" :content "2"}]
          true))))
  (testing "With tool_call history"
    (is (match?
         [{:role "user" :content "List the files you are allowed"}
          {:role "assistant" :content "Ok!"}
          {:role "assistant" :content [{:type "tool_use"
                                        :id "call-1"
                                        :name "eca__list_allowed_directories"
                                        :input {}}]}
          {:role "user" :content [{:type "tool_result"
                                   :tool_use_id "call-1"
                                   :content "Allowed directories: /foo/bar\n"}]}
          {:role "assistant" :content "I see /foo/bar"}]
         (#'llm-providers.anthropic/normalize-messages
          [{:role "user" :content "List the files you are allowed"}
           {:role "assistant" :content "Ok!"}
           {:role "tool_call" :content {:id "call-1" :full-name "eca__list_allowed_directories" :arguments {}}}
           {:role "tool_call_output" :content {:id "call-1"
                                               :full-name "eca__list_allowed_directories"
                                               :arguments {}
                                               :output {:contents [{:type :text
                                                                    :error false
                                                                    :text "Allowed directories: /foo/bar"}]}}}
           {:role "assistant" :content "I see /foo/bar"}]
          true)))
    (testing "With server_tool_use and server_tool_result history"
      (is (match?
           [{:role "assistant" :content [{:type "server_tool_use"
                                          :id "srvtoolu_123"
                                          :name "web_search"
                                          :input {:query "test"}}]}
            {:role "assistant" :content [{:type "web_search_tool_result"
                                          :tool_use_id "srvtoolu_123"
                                          :content [{:type "web_search_result" :title "Test" :url "https://test.com"}]}]}]
           (#'llm-providers.anthropic/normalize-messages
            [{:role "server_tool_use" :content {:id "srvtoolu_123" :name "web_search" :input {:query "test"}}}
             {:role "server_tool_result" :content {:tool-use-id "srvtoolu_123"
                                                   :raw-content [{:type "web_search_result" :title "Test" :url "https://test.com"}]}}]
            true))))))

(deftest server-web-search-full-pipeline-test
  (testing "thinking + server web search + thinking + text normalizes to single assistant message"
    (let [input [{:role "user" :content "search for something"}
                 {:role "reason" :content {:id "r1" :external-id "sig1" :text "Let me search."}}
                 {:role "server_tool_use" :content {:id "srvtoolu_1" :name "web_search" :input {:query "test"}}}
                 {:role "server_tool_result" :content {:tool-use-id "srvtoolu_1"
                                                       :raw-content [{:type "web_search_result"
                                                                      :title "Result"
                                                                      :url "https://example.com"
                                                                      :encrypted_content "abc123"}]}}
                 {:role "reason" :content {:id "r2" :external-id "sig2" :text "Now I'll summarize."}}
                 {:role "assistant" :content [{:type :text :text "Here are the results."}]}]
          result (-> input
                     (#'llm-providers.anthropic/group-parallel-tool-calls)
                     (#'llm-providers.anthropic/normalize-messages true)
                     (#'llm-providers.anthropic/merge-adjacent-assistants)
                     (#'llm-providers.anthropic/merge-adjacent-tool-results))]
      (is (match?
           [{:role "user" :content "search for something"}
            {:role "assistant"
             :content [{:type "thinking" :signature "sig1" :thinking "Let me search."}
                       {:type "server_tool_use" :id "srvtoolu_1" :name "web_search" :input {:query "test"}}
                       {:type "web_search_tool_result" :tool_use_id "srvtoolu_1"
                        :content [{:type "web_search_result" :title "Result" :url "https://example.com" :encrypted_content "abc123"}]}
                       {:type "thinking" :signature "sig2" :thinking "Now I'll summarize."}
                       {:type :text :text "Here are the results."}]}]
           result)))))

(deftest group-parallel-tool-calls-test
  (testing "single tool call passes through unchanged")
  (is (match?
       [{:role "user" :content "do something"}
        {:role "assistant" :content "ok"}
        {:role "tool_call" :content {:id "c1"}}
        {:role "tool_call_output" :content {:id "c1"}}
        {:role "assistant" :content "done"}]
       (#'llm-providers.anthropic/group-parallel-tool-calls
        [{:role "user" :content "do something"}
         {:role "assistant" :content "ok"}
         {:role "tool_call" :content {:id "c1"}}
         {:role "tool_call_output" :content {:id "c1"}}
         {:role "assistant" :content "done"}])))
  (testing "interleaved parallel tool calls are reordered: calls first, then outputs"
    (is (match?
         [{:role "tool_call" :content {:id "c1"}}
          {:role "tool_call" :content {:id "c2"}}
          {:role "tool_call_output" :content {:id "c1"}}
          {:role "tool_call_output" :content {:id "c2"}}]
         (#'llm-providers.anthropic/group-parallel-tool-calls
          [{:role "tool_call" :content {:id "c1"}}
           {:role "tool_call_output" :content {:id "c1"}}
           {:role "tool_call" :content {:id "c2"}}
           {:role "tool_call_output" :content {:id "c2"}}]))))
  (testing "outputs are sorted to match call order"
    (is (match?
         [{:role "tool_call" :content {:id "c2"}}
          {:role "tool_call" :content {:id "c1"}}
          {:role "tool_call_output" :content {:id "c2"}}
          {:role "tool_call_output" :content {:id "c1"}}]
         (#'llm-providers.anthropic/group-parallel-tool-calls
          [{:role "tool_call" :content {:id "c2"}}
           {:role "tool_call_output" :content {:id "c2"}}
           {:role "tool_call" :content {:id "c1"}}
           {:role "tool_call_output" :content {:id "c1"}}])))))

(deftest merge-adjacent-tool-results-test
  (testing "single tool result passes through unchanged"
    (is (match?
         [{:role "user" :content [{:type "tool_result" :tool_use_id "c1" :content "ok"}]}]
         (#'llm-providers.anthropic/merge-adjacent-tool-results
          [{:role "user" :content [{:type "tool_result" :tool_use_id "c1" :content "ok"}]}]))))
  (testing "adjacent tool_result user messages are merged"
    (is (match?
         [{:role "user"
           :content [{:type "tool_result" :tool_use_id "c1" :content "result1"}
                     {:type "tool_result" :tool_use_id "c2" :content "result2"}]}]
         (#'llm-providers.anthropic/merge-adjacent-tool-results
          [{:role "user" :content [{:type "tool_result" :tool_use_id "c1" :content "result1"}]}
           {:role "user" :content [{:type "tool_result" :tool_use_id "c2" :content "result2"}]}]))))
  (testing "non-tool-result user messages are not merged"
    (is (match?
         [{:role "user" :content "hello"}
          {:role "user" :content "world"}]
         (#'llm-providers.anthropic/merge-adjacent-tool-results
          [{:role "user" :content "hello"}
           {:role "user" :content "world"}]))))
  (testing "mixed content user messages are not merged with tool results"
    (is (match?
         [{:role "user" :content [{:type "tool_result" :tool_use_id "c1" :content "ok"}]}
          {:role "user" :content [{:type "text" :text "follow up"}]}]
         (#'llm-providers.anthropic/merge-adjacent-tool-results
          [{:role "user" :content [{:type "tool_result" :tool_use_id "c1" :content "ok"}]}
           {:role "user" :content [{:type "text" :text "follow up"}]}])))))

(deftest parallel-tool-calls-full-pipeline-test
  (testing "interleaved parallel tool calls normalize to valid Anthropic message structure"
    (let [input [{:role "user" :content "read two files"}
                 {:role "assistant" :content "I'll read both files."}
                 {:role "tool_call" :content {:id "c1" :full-name "eca__read_file" :arguments {:path "/a"}}}
                 {:role "tool_call_output" :content {:id "c1" :output {:contents [{:type :text :text "content-a"}]}}}
                 {:role "tool_call" :content {:id "c2" :full-name "eca__read_file" :arguments {:path "/b"}}}
                 {:role "tool_call_output" :content {:id "c2" :output {:contents [{:type :text :text "content-b"}]}}}]
          result (-> input
                     (#'llm-providers.anthropic/group-parallel-tool-calls)
                     (#'llm-providers.anthropic/normalize-messages true)
                     (#'llm-providers.anthropic/merge-adjacent-assistants)
                     (#'llm-providers.anthropic/merge-adjacent-tool-results))]
      (is (match?
           [{:role "user" :content "read two files"}
            {:role "assistant"
             :content [{:type "text" :text "I'll read both files."}
                       {:type "tool_use" :id "c1" :name "eca__read_file" :input {:path "/a"}}
                       {:type "tool_use" :id "c2" :name "eca__read_file" :input {:path "/b"}}]}
            {:role "user"
             :content [{:type "tool_result" :tool_use_id "c1" :content "content-a\n"}
                       {:type "tool_result" :tool_use_id "c2" :content "content-b\n"}]}]
           result)))))

(deftest add-cache-to-last-message-test
  (is (match?
       []
       (#'llm-providers.anthropic/add-cache-to-last-message [])))
  (testing "when message content is a vector"
    (is (match?
         [{:role "user" :content [{:type :text :text "Hey" :cache_control {:type "ephemeral"}}]}]
         (#'llm-providers.anthropic/add-cache-to-last-message
          [{:role "user" :content [{:type :text :text "Hey"}]}])))
    (is (match?
         [{:role "user" :content [{:type :text :text "Hey"}]}
          {:role "user" :content [{:type :text :text "Ho" :cache_control {:type "ephemeral"}}]}]
         (#'llm-providers.anthropic/add-cache-to-last-message
          [{:role "user" :content [{:type :text :text "Hey"}]}
           {:role "user" :content [{:type :text :text "Ho"}]}]))))
  (testing "when message content is string"
    (is (match?
         [{:role "user" :content [{:type :text :text "Hey" :cache_control {:type "ephemeral"}}]}]
         (#'llm-providers.anthropic/add-cache-to-last-message
          [{:role "user" :content "Hey"}])))
    (is (match?
         [{:role "user" :content "Hey"}
          {:role "user" :content [{:type :text :text "Ho" :cache_control {:type "ephemeral"}}]}]
         (#'llm-providers.anthropic/add-cache-to-last-message
          [{:role "user" :content "Hey"}
           {:role "user" :content "Ho"}])))))
