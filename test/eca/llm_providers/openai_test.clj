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

(deftest oauth-refresh-test
  (testing "that OAuth token refresh is routed through the http proxy"
    (let [req* (atom nil)
          now-seconds (quot (System/currentTimeMillis) 1000)]
      (with-client-proxied {}

        (fn handler [req]
          ;; capture the outgoing request
          (reset! req* req)
          ;; fake token endpoint response
          {:status 200
           :body {:refresh_token "new-r-token"
                  :access_token  "new-a-token"
                  :expires_in     3600}})

        (let [refresh-token "old-r-token"
              result        (with-redefs [llm-providers.openai/oauth-token-url "http://localhost:99/oauth/token"]
                              (#'llm-providers.openai/oauth-refresh refresh-token))]

          ;; request validation
          (is (= {:method "POST"
                  :uri    "/oauth/token"}
                 (select-keys @req* [:method :uri])))

          (is (= {:grant_type     "refresh_token"
                  :refresh_token  refresh-token
                  :client_id      @#'llm-providers.openai/client-id}
                 (:body @req*))
              "Outgoing payload should match refresh token fields")

          ;; response parsing
          (is (= "new-r-token" (:refresh-token result)))
          (is (= "new-a-token" (:access-token result)))
          ;; expires-at should be > now
          (is (> (:expires-at result) now-seconds)
              "expires-at should be computed relative to current time"))))))

(deftest create-response-refreshes-account-id-after-tool-call-test
  (testing "uses refreshed provider auth metadata after a long-running tool call"
    (let [requests* (atom [])]
      (with-redefs [llm-providers.openai/base-responses-request!
                    (fn [{:keys [api-key account-id on-stream] :as _opts}]
                      (swap! requests* conj {:api-key api-key
                                             :account-id account-id})
                      (when (= 1 (count @requests*))
                        (on-stream "response.completed"
                                   {:response {:output [{:type "function_call"
                                                         :id "item-1"
                                                         :call_id "call-1"
                                                         :name "eca__spawn_agent"
                                                         :arguments "{}"}]
                                               :usage {:input_tokens 1
                                                       :output_tokens 1}}}))
                      :ok)]
        (llm-providers.openai/create-response!
         {:model "gpt-test"
          :user-messages [{:role "user" :content [{:type :text :text "hi"}]}]
          :instructions "ins"
          :reason? false
          :supports-image? false
          :api-key "stale-token"
          :api-url "http://localhost:1"
          :past-messages []
          :tools [{:full-name "eca__spawn_agent" :description "spawn" :parameters {:type "object"}}]
          :web-search false
          :extra-payload {}
          :extra-headers nil
          :auth-type :auth/oauth
          :account-id "old-account"}
         {:on-message-received (fn [_])
          :on-error (fn [e] (throw (ex-info "err" e)))
          :on-prepare-tool-call (fn [_])
          :on-tools-called (fn [_]
                             {:new-messages []
                              :tools []
                              :fresh-api-key "fresh-token"
                              :provider-auth {:account-id "new-account"}})
          :on-reason (fn [_])
          :on-usage-updated (fn [_])
          :on-server-web-search (fn [_])})
        (is (= [{:api-key "stale-token"
                 :account-id "old-account"}
                {:api-key "fresh-token"
                 :account-id "new-account"}]
               @requests*))))))

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
          true))))
  (testing "With tool_call with nil arguments defaults to empty object"
    (is (match?
         [{:role "user" :content [{:type "input_text" :text "Check diagnostics"}]}
          {:type "function_call"
           :call_id "call-1"
           :name "eca__editor_diagnostics"
           :arguments "{}"}]
         (#'llm-providers.openai/normalize-messages
          [{:role "user" :content [{:type :text :text "Check diagnostics"}]}
           {:role "tool_call" :content {:id "call-1" :full-name "eca__editor_diagnostics" :arguments nil}}]
          true)))))

(defn- base-provider-params []
  {:model "gpt-test"
   :user-messages [{:role "user" :content [{:type :text :text "hi"}]}]
   :instructions "test"
   :reason? false
   :supports-image? false
   :api-key "fake-key"
   :api-url "http://localhost:1"
   :past-messages []
   :tools [{:full-name "eca__shell_command" :description "run" :parameters {:type "object"}}]
   :web-search false
   :extra-payload {}
   :extra-headers nil
   :auth-type :auth/api-key
   :account-id nil})

(defn- base-callbacks [{:keys [on-prepare-tool-call on-tools-called on-message-received on-error]
                        :or {on-prepare-tool-call (fn [_])
                             on-tools-called (fn [_] {:new-messages [] :tools []})
                             on-message-received (fn [_])
                             on-error (fn [e] (throw (ex-info "unexpected error in test" e)))}}]
  {:on-message-received on-message-received
   :on-error on-error
   :on-prepare-tool-call on-prepare-tool-call
   :on-tools-called on-tools-called
   :on-reason (fn [_])
   :on-usage-updated (fn [_])
   :on-server-web-search (fn [_])})

(deftest create-response-tool-calls-via-output-test
  (testing "tool calls in response.completed output trigger callbacks correctly"
    (let [prepare-calls* (atom [])
          tools-called* (atom [])
          requests* (atom [])]
      (with-redefs [llm-providers.openai/base-responses-request!
                    (fn [{:keys [on-stream] :as opts}]
                      (swap! requests* conj opts)
                      (when (= 1 (count @requests*))
                        (on-stream "response.output_item.added"
                                   {:item {:type "function_call"
                                           :id "item-1"
                                           :call_id "call-1"
                                           :name "eca__shell_command"
                                           :arguments ""}})
                        (on-stream "response.function_call_arguments.delta"
                                   {:item_id "item-1"
                                    :delta "{\"command\":\"ls\"}"})
                        (on-stream "response.output_item.done"
                                   {:item {:type "function_call"
                                           :id "item-1"
                                           :call_id "call-1"
                                           :name "eca__shell_command"
                                           :arguments "{\"command\":\"ls\"}"}})
                        (on-stream "response.completed"
                                   {:response {:output [{:type "function_call"
                                                         :id "item-1"
                                                         :call_id "call-1"
                                                         :name "eca__shell_command"
                                                         :arguments "{\"command\":\"ls\"}"}]
                                               :usage {:input_tokens 10
                                                       :output_tokens 5}
                                               :status "completed"}})))]
        (llm-providers.openai/create-response!
         (base-provider-params)
         (base-callbacks
          {:on-prepare-tool-call (fn [data] (swap! prepare-calls* conj data))
           :on-tools-called (fn [tool-calls]
                              (swap! tools-called* conj tool-calls)
                              {:new-messages [] :tools []})}))
        (is (pos? (count @prepare-calls*)))
        (is (= "call-1" (:id (first @prepare-calls*))))
        (is (= "eca__shell_command" (:full-name (first @prepare-calls*))))
        (is (= 1 (count @tools-called*)))
        (is (match? [{:id "call-1"
                      :full-name "eca__shell_command"
                      :arguments {"command" "ls"}}]
                    (first @tools-called*)))
        (is (= 2 (count @requests*)))))))

(deftest create-response-prompt-cache-key-test
  (testing "prompt_cache_key uses the provided :prompt-cache-key verbatim"
    (let [requests* (atom [])]
      (with-redefs [llm-providers.openai/base-responses-request!
                    (fn [{:keys [on-stream] :as opts}]
                      (swap! requests* conj opts)
                      (on-stream "response.completed"
                                 {:response {:output []
                                             :usage {:input_tokens 0 :output_tokens 0}
                                             :status "completed"}}))]
        (llm-providers.openai/create-response!
         (assoc (base-provider-params) :prompt-cache-key "alice@ECA/plan")
         (base-callbacks {}))
        (is (= 1 (count @requests*)))
        (is (= "alice@ECA/plan"
               (get-in (first @requests*) [:body :prompt_cache_key]))
            "Body should pass the caller-supplied cache key unchanged"))))
  (testing "prompt_cache_key falls back to $USER@ECA when :prompt-cache-key is absent"
    (let [requests* (atom [])]
      (with-redefs [llm-providers.openai/base-responses-request!
                    (fn [{:keys [on-stream] :as opts}]
                      (swap! requests* conj opts)
                      (on-stream "response.completed"
                                 {:response {:output []
                                             :usage {:input_tokens 0 :output_tokens 0}
                                             :status "completed"}}))]
        (llm-providers.openai/create-response!
         (base-provider-params)
         (base-callbacks {}))
        (is (= 1 (count @requests*)))
        (is (= (str (System/getProperty "user.name") "@ECA")
               (get-in (first @requests*) [:body :prompt_cache_key]))
            "Body should use the default $USER@ECA key when no cache key is provided")))))

(deftest create-response-tool-calls-fallback-via-atom-test
  (testing "empty output in response.completed still triggers on-tools-called via atom fallback"
    (let [tools-called* (atom [])
          requests* (atom [])]
      (with-redefs [llm-providers.openai/base-responses-request!
                    (fn [{:keys [on-stream] :as opts}]
                      (swap! requests* conj opts)
                      (when (= 1 (count @requests*))
                        (on-stream "response.output_item.added"
                                   {:item {:type "function_call"
                                           :id "item-1"
                                           :call_id "call-1"
                                           :name "eca__shell_command"
                                           :arguments ""}})
                        (on-stream "response.function_call_arguments.delta"
                                   {:item_id "item-1"
                                    :delta "{\"command\":\"ls\"}"})
                        (on-stream "response.output_item.done"
                                   {:item {:type "function_call"
                                           :id "item-1"
                                           :call_id "call-1"
                                           :name "eca__shell_command"
                                           :arguments "{\"command\":\"ls\"}"}})
                        ;; response.completed with EMPTY output — fallback must kick in
                        (on-stream "response.completed"
                                   {:response {:output []
                                               :usage {:input_tokens 10
                                                       :output_tokens 5}
                                               :status "completed"}})))]
        (llm-providers.openai/create-response!
         (base-provider-params)
         (base-callbacks
          {:on-tools-called (fn [tool-calls]
                              (swap! tools-called* conj tool-calls)
                              {:new-messages [] :tools []})}))
        (is (= 1 (count @tools-called*)))
        (is (match? [{:id "call-1"
                      :full-name "eca__shell_command"
                      :arguments {"command" "ls"}}]
                    (first @tools-called*)))
        (is (= 2 (count @requests*)))))))

(deftest create-response-text-only-no-phantom-calls-test
  (testing "text-only final response doesn't produce phantom tool calls from stale atom entries"
    (let [tools-called* (atom [])
          finish-received* (atom false)
          requests* (atom [])]
      (with-redefs [llm-providers.openai/base-responses-request!
                    (fn [{:keys [on-stream] :as opts}]
                      (swap! requests* conj opts)
                      (case (count @requests*)
                        ;; First call: tool call with Copilot-style mismatched item IDs
                        1 (do
                            (on-stream "response.output_item.added"
                                       {:item {:type "function_call"
                                               :id "stream-added-id"
                                               :call_id "call-1"
                                               :name "eca__shell_command"
                                               :arguments ""}})
                            (on-stream "response.function_call_arguments.delta"
                                       {:item_id "stream-added-id"
                                        :delta "{\"command\":\"ls\"}"})
                            (on-stream "response.output_item.done"
                                       {:item {:type "function_call"
                                               :id "stream-done-id"
                                               :call_id "call-1"
                                               :name "eca__shell_command"
                                               :arguments "{\"command\":\"ls\"}"}})
                            (on-stream "response.completed"
                                       {:response {:output [{:type "function_call"
                                                             :id "output-id"
                                                             :call_id "call-1"
                                                             :name "eca__shell_command"
                                                             :arguments "{\"command\":\"ls\"}"}]
                                                   :usage {:input_tokens 10 :output_tokens 5}
                                                   :status "completed"}}))
                        ;; Second call: text-only response (no tool calls)
                        2 (on-stream "response.completed"
                                     {:response {:output [{:type "message"
                                                           :id "msg-1"
                                                           :content [{:text "Done."}]}]
                                                 :usage {:input_tokens 5 :output_tokens 3}
                                                 :status "completed"}})
                        nil))]
        (llm-providers.openai/create-response!
         (base-provider-params)
         (base-callbacks
          {:on-message-received (fn [msg]
                                  (when (= :finish (:type msg))
                                    (reset! finish-received* true)))
           :on-tools-called (fn [tool-calls]
                              (swap! tools-called* conj tool-calls)
                              {:new-messages [] :tools []})}))
        (is (= 1 (count @tools-called*))
            "on-tools-called should fire exactly once, not for phantom calls")
        (is (true? @finish-received*)
            "text-only response should trigger :finish")
        (is (= 2 (count @requests*))
            "should make exactly 2 requests, no retry loop")))))

(deftest create-response-mismatched-item-ids-test
  (testing "different item IDs across streaming events still produce correct tool calls"
    (let [tools-called* (atom [])
          requests* (atom [])]
      (with-redefs [llm-providers.openai/base-responses-request!
                    (fn [{:keys [on-stream] :as opts}]
                      (swap! requests* conj opts)
                      (when (= 1 (count @requests*))
                        ;; Copilot-style: different encrypted IDs for the same tool call
                        (on-stream "response.output_item.added"
                                   {:item {:type "function_call"
                                           :id "encrypted-added-id"
                                           :call_id "call-1"
                                           :name "eca__shell_command"
                                           :arguments ""}})
                        (on-stream "response.function_call_arguments.delta"
                                   {:item_id "encrypted-added-id"
                                    :delta "{\"command\":\"ls\"}"})
                        ;; output_item.done uses a DIFFERENT encrypted id
                        (on-stream "response.output_item.done"
                                   {:item {:type "function_call"
                                           :id "encrypted-done-id"
                                           :call_id "call-1"
                                           :name "eca__shell_command"
                                           :arguments "{\"command\":\"ls\"}"}})
                        ;; response.completed uses yet ANOTHER encrypted id
                        (on-stream "response.completed"
                                   {:response {:output [{:type "function_call"
                                                         :id "encrypted-output-id"
                                                         :call_id "call-1"
                                                         :name "eca__shell_command"
                                                         :arguments "{\"command\":\"ls\"}"}]
                                               :usage {:input_tokens 10 :output_tokens 5}
                                               :status "completed"}})))]
        (llm-providers.openai/create-response!
         (base-provider-params)
         (base-callbacks
          {:on-tools-called (fn [tool-calls]
                              (swap! tools-called* conj tool-calls)
                              {:new-messages [] :tools []})}))
        (is (= 1 (count @tools-called*)))
        (is (match? [{:id "call-1"
                      :full-name "eca__shell_command"
                      :arguments {"command" "ls"}}]
                    (first @tools-called*)))
        (is (= 2 (count @requests*)))))))
