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

(deftest base-request-error-includes-response-headers-test
  (testing "non-200 error-data includes response headers for rate-limit handling"
    (with-client-proxied {}
      (fn handler [_req]
        {:status 429
         :body {:type "error" :error {:type "rate_limit_error" :message "Rate limit exceeded"}}
         :headers {"retry-after" "7"
                   "anthropic-ratelimit-unified-reset" "1783958400"}})
      (let [response (#'llm-providers.anthropic/base-request!
                      {:rid "r1"
                       :api-key "fake-key"
                       :api-url "http://localhost:1"
                       :body {:model "claude-v1"
                              :input "hi"
                              :stream false}
                       :url-relative-path "/v1/messages"
                       :auth-type :auth/key})]
        (is (= 429 (:status response)))
        (is (match? {"retry-after" "7"
                     "anthropic-ratelimit-unified-reset" "1783958400"}
                    (:headers response)))))))

(deftest base-request-resolves-dynamic-extra-headers-test
  (let [seen-body* (atom nil)
        req* (atom nil)]
    (with-client-proxied {}
      (fn handler [req]
        (reset! req* req)
        {:status 200
         :body {:content [{:text "ok"}]}})
      (#'llm-providers.anthropic/base-request!
       {:rid "r1"
        :api-key "oauth-token"
        :api-url "http://localhost:1"
        :body {:model "claude" :messages [{:role "user"}] :stream false}
        :auth-type :auth/oauth
        :extra-headers (fn [{:keys [body]}]
                         (reset! seen-body* body)
                         {"anthropic-beta" "interleaved-thinking-2025-05-14"
                          "x-initiator" "user"})})
      (is (= {:model "claude" :messages [{:role "user"}] :stream false}
             @seen-body*))
            (is (= "interleaved-thinking-2025-05-14"
                   (get-in @req* [:headers "anthropic-beta"])))
            (is (not (string/includes? (get-in @req* [:headers "anthropic-beta"])
                                       "oauth-2025-04-20"))
                "Copilot's GitHub bearer must not opt into Anthropic OAuth beta behavior")
            (is (= "user" (get-in @req* [:headers "x-initiator"]))))))

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

(deftest normalize-messages-nil-type-resilience-test
  (testing "content blocks with nil :type are filtered out"
    (is (match?
         [{:role "user"
           :content [{:type :text :text "hello"}]}]
         (#'llm-providers.anthropic/normalize-messages
          [{:role "user"
            :content [{:type :text :text "hello"}
                      {:text "orphan without type"}
                      nil]}]
          true))))
  (testing "message with keyword :type content works (additionalContext injection)"
    (is (match?
         [{:role "user"
           :content [{:type :text :text "compact the chat"}
                     {:type :text :text "<additionalContext>\ntoday is monday\n</additionalContext>"}]}]
         (#'llm-providers.anthropic/normalize-messages
          [{:role "user"
            :content [{:type :text :text "compact the chat"}
                      {:type :text :text "<additionalContext>\ntoday is monday\n</additionalContext>"}]}]
          true)))))

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

(deftest reason-empty-thinking-test
  (testing "reason message with nil :text serializes :thinking to \"\" (not nil)"
    ;; Regression: Anthropic rejects requests where a replayed thinking block
    ;; has a non-string :thinking field with
    ;;   400 messages.N.content.0.thinking.thinking: Input should be a valid string
    ;; This happens when a content_block_start of type "thinking" arrived with no
    ;; thinking_delta before content_block_stop, leaving (:text content) nil.
    (let [out (vec (#'llm-providers.anthropic/normalize-messages
                    [{:role "reason" :content {:id "r1" :external-id "sig1" :text nil}}]
                    true))
          thinking-block (-> out first :content first)]
      (is (= 1 (count out)))
      (is (match? {:role "assistant"
                   :content [{:type "thinking" :signature "sig1" :thinking ""}]}
                  (first out)))
      (is (= "" (:thinking thinking-block)))
      (is (string? (:thinking thinking-block)))))
  (testing "reason message with missing :text key also serializes :thinking to \"\""
    (let [out (vec (#'llm-providers.anthropic/normalize-messages
                    [{:role "reason" :content {:id "r1" :external-id "sig1"}}]
                    true))]
      (is (= "" (-> out first :content first :thinking)))))
  (testing "reason message with present :text is preserved verbatim"
    (let [out (vec (#'llm-providers.anthropic/normalize-messages
                    [{:role "reason" :content {:id "r1" :external-id "sig1" :text "Let me think."}}]
                    true))]
      (is (= "Let me think." (-> out first :content first :thinking)))))
  (testing "redacted reason message is unaffected (no :thinking field)"
    (let [out (vec (#'llm-providers.anthropic/normalize-messages
                    [{:role "reason" :content {:id "r1" :redacted? true :data "enc"}}]
                    true))]
      (is (match? {:role "assistant"
                   :content [{:type "redacted_thinking" :data "enc"}]}
                  (first out))))))

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

(deftest cache-control-value-test
  (let [cache-control #'llm-providers.anthropic/cache-control-value]
    (testing "default 5-min TTL when no cache-retention set"
      (is (= {:type "ephemeral"} (cache-control "https://api.anthropic.com" nil)))
      (is (= {:type "ephemeral"} (cache-control nil nil))))
    (testing "default 5-min TTL for short retention"
      (is (= {:type "ephemeral"} (cache-control "https://api.anthropic.com" "short"))))
    (testing "1-hour TTL for long retention on direct Anthropic API"
      (is (= {:type "ephemeral" :ttl "1h"} (cache-control "https://api.anthropic.com" "long")))
      (is (= {:type "ephemeral" :ttl "1h"} (cache-control "https://api.anthropic.com/v1" "long"))))
    (testing "1-hour TTL when api-url is nil (default direct API)"
      (is (= {:type "ephemeral" :ttl "1h"} (cache-control nil "long"))))
    (testing "falls back to 5-min when using a proxy"
      (is (= {:type "ephemeral"} (cache-control "https://my-proxy.example.com" "long"))))))

(deftest add-cache-to-last-message-test
  (let [default-cache {:type "ephemeral"}]
    (is (match?
         []
         (#'llm-providers.anthropic/add-cache-to-last-message [] default-cache)))
    (testing "when message content is a vector"
      (is (match?
           [{:role "user" :content [{:type :text :text "Hey" :cache_control {:type "ephemeral"}}]}]
           (#'llm-providers.anthropic/add-cache-to-last-message
            [{:role "user" :content [{:type :text :text "Hey"}]}] default-cache)))
      (is (match?
           [{:role "user" :content [{:type :text :text "Hey"}]}
            {:role "user" :content [{:type :text :text "Ho" :cache_control {:type "ephemeral"}}]}]
           (#'llm-providers.anthropic/add-cache-to-last-message
            [{:role "user" :content [{:type :text :text "Hey"}]}
             {:role "user" :content [{:type :text :text "Ho"}]}] default-cache))))
    (testing "when message content is string"
      (is (match?
           [{:role "user" :content [{:type :text :text "Hey" :cache_control {:type "ephemeral"}}]}]
           (#'llm-providers.anthropic/add-cache-to-last-message
            [{:role "user" :content "Hey"}] default-cache)))
      (is (match?
           [{:role "user" :content "Hey"}
            {:role "user" :content [{:type :text :text "Ho" :cache_control {:type "ephemeral"}}]}]
           (#'llm-providers.anthropic/add-cache-to-last-message
            [{:role "user" :content "Hey"}
             {:role "user" :content "Ho"}] default-cache))))
    (testing "with 1-hour TTL"
      (let [long-cache {:type "ephemeral" :ttl "1h"}]
        (is (match?
             [{:role "user" :content [{:type :text :text "Hey" :cache_control {:type "ephemeral" :ttl "1h"}}]}]
             (#'llm-providers.anthropic/add-cache-to-last-message
              [{:role "user" :content [{:type :text :text "Hey"}]}] long-cache)))))))

(deftest add-cache-to-last-tool-test
  (let [default-cache {:type "ephemeral"}
        add-cache (fn [tools] (#'llm-providers.anthropic/add-cache-to-last-tool tools default-cache))]
    (testing "empty tools returns empty"
      (is (match? [] (add-cache [])))
      (is (match? nil (add-cache nil))))

    (testing "adds cache_control to the last tool"
      (is (match?
           [{:name "tool1" :description "first"}
            {:name "tool2" :description "second" :cache_control {:type "ephemeral"}}]
           (add-cache [{:name "tool1" :description "first"}
                       {:name "tool2" :description "second"}]))))

    (testing "single tool gets cache_control"
      (is (match?
           [{:name "tool1" :cache_control {:type "ephemeral"}}]
           (add-cache [{:name "tool1"}]))))

    (testing "web_search tool as last gets cache_control"
      (is (match?
           [{:name "tool1"}
            {:type "web_search_20250305" :name "web_search" :cache_control {:type "ephemeral"}}]
           (add-cache [{:name "tool1"}
                       {:type "web_search_20250305" :name "web_search"}]))))))

(deftest normalize-messages-tool-call-output-image-test
  (let [tool-output-with-image
        {:role "tool_call_output"
         :content {:id "call-1"
                   :name "create-image"
                   :output {:contents [{:type :text :text "saved"}
                                       {:type :image
                                        :media-type "image/png"
                                        :base64 "AAAA"}]}}}]
    (testing "image content + supports-image? true emits a single tool_result with mixed text+image blocks"
      (let [out (vec (#'llm-providers.anthropic/normalize-messages
                      [tool-output-with-image] true))]
        (is (= 1 (count out))
            "Anthropic carries images in tool_result.content natively, no synthetic user message")
        (is (match? {:role "user"
                     :content [{:type "tool_result"
                                :tool_use_id "call-1"
                                :content [{:type "text"
                                           :text #(string/includes? % "[Image: image/png]")}
                                          {:type "image"
                                           :source {:type "base64"
                                                    :media_type "image/png"
                                                    :data "AAAA"}}]}]}
                    (first out)))))

    (testing "image content + supports-image? false falls back to legacy text-only tool_result"
      (let [out (vec (#'llm-providers.anthropic/normalize-messages
                      [tool-output-with-image] false))]
        (is (= 1 (count out)))
        (is (match? {:role "user"
                     :content [{:type "tool_result"
                                :tool_use_id "call-1"
                                :content string?}]}
                    (first out)))))

    (testing "no image content emits the legacy text-only tool_result"
      (let [out (vec (#'llm-providers.anthropic/normalize-messages
                      [{:role "tool_call_output"
                        :content {:id "call-2"
                                  :output {:contents [{:type :text :text "ok"}]}}}]
                      true))]
        (is (= 1 (count out)))
        (is (match? {:role "user"
                     :content [{:type "tool_result"
                                :tool_use_id "call-2"
                                :content "ok\n"}]}
                    (first out)))))))

(deftest max-tokens-input-overflow?-test
  (let [overflow? #'llm-providers.anthropic/max-tokens-input-overflow?]
    (testing "Z.AI-style overflow: huge input, tiny output vs 32k cap"
      (is (true? (overflow? {:input_tokens 202525 :output_tokens 225} 32000))))
    (testing "tiny output vs a moderately large cap is still treated as overflow"
      (is (true? (overflow? {:input_tokens 50000 :output_tokens 100} 8000))))
    (testing "output near the requested cap is a genuine output cap"
      (is (false? (overflow? {:input_tokens 5000 :output_tokens 31900} 32000)))
      (is (false? (overflow? {:input_tokens 5000 :output_tokens 7000} 8000))))
    (testing "output exactly half of cap is treated as genuine cap (boundary)"
      (is (false? (overflow? {:input_tokens 5000 :output_tokens 16000} 32000))))
    (testing "small requested caps never trigger overflow reclassification"
      (is (false? (overflow? {:input_tokens 1000 :output_tokens 100} 500)))
      (is (false? (overflow? {:input_tokens 1000 :output_tokens 50} 3999))))
    (testing "missing :output_tokens defaults to 0 and trips the heuristic"
      (is (true? (overflow? {} 32000)))
      (is (true? (overflow? {:input_tokens 100000} 32000))))))

(deftest finalize-messages-test
  (let [cache {:type "ephemeral"}
        finalize #'llm-providers.anthropic/finalize-messages]
    (testing "mid-system? false behaves like add-cache-to-last-message (no trailing system message)"
      (is (match?
           [{:role "user" :content [{:type :text :text "hi" :cache_control {:type "ephemeral"}}]}]
           (finalize [{:role "user" :content "hi"}] cache false "DYN"))))
    (testing "mid-system? true appends dynamic as a trailing system message, cache stays on the prior turn"
      (is (match?
           [{:role "user" :content [{:type :text :text "hi" :cache_control {:type "ephemeral"}}]}
            {:role "system" :content [{:type "text" :text "DYN"}]}]
           (finalize [{:role "user" :content "hi"}] cache true "DYN"))))
    (testing "the trailing system message carries no cache_control so it stays uncached"
      (is (nil? (-> (finalize [{:role "user" :content "hi"}] cache true "DYN")
                    last :content first :cache_control))))))

(deftest chat!-mid-conversation-system-test
  (let [base-params {:model "claude-opus-4-8"
                     :api-url "http://localhost:1"
                     :api-key "fake-key"
                     :auth-type :auth/key
                     :instructions {:static "STATIC" :dynamic "DYNAMIC"}
                     :user-messages [{:role "user" :content "hello"}]
                     :past-messages []}
        run! (fn [params]
               (let [req* (atom nil)]
                 (with-client-proxied {}
                   (fn handler [req]
                     (reset! req* req)
                     {:status 200 :body {:content [{:text "ok"}]}})
                   (llm-providers.anthropic/chat! params nil))
                 (:body @req*)))]
    (testing "flag off keeps dynamic instructions in the cached :system prefix"
      (let [body (run! (assoc base-params :mid-conversation-system? false))]
        (is (some #(= "DYNAMIC" (:text %)) (:system body))
            "dynamic block present in :system")
        (is (not-any? #(= "system" (:role %)) (:messages body))
            "no system-role entry inside the messages array")))
    (testing "flag on moves dynamic out of :system into a trailing system message after the user turn"
      (let [body (run! (assoc base-params :mid-conversation-system? true))]
        (is (some #(= "STATIC" (:text %)) (:system body))
            "static block still in :system")
        (is (not-any? #(= "DYNAMIC" (:text %)) (:system body))
            "dynamic block removed from :system")
        (is (match? {:role "system" :content [{:type "text" :text "DYNAMIC"}]}
                    (last (:messages body)))
            "dynamic appended as the trailing system message")
        (is (= "user" (:role (last (butlast (:messages body)))))
            "trailing system message follows a user turn")))))
