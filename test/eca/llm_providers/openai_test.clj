(ns eca.llm-providers.openai-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.client-test-helpers :refer [blocking-input-stream with-client-proxied]]
   [eca.llm-providers.openai :as llm-providers.openai]
   [hato.client :as http]
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

(deftest base-responses-req-preserves-decoded-error-body-test
  (testing "non-streaming 429 keeps the decoded JSON body in structured error data"
    (let [error-body {:error {:type "usage_limit_reached"
                              :resets_at 2000000000}}
          request-opts* (atom nil)]
      (with-redefs [http/post (fn [_url opts]
                                (reset! request-opts* opts)
                                {:status 429
                                 :headers {"retry-after" "7"}
                                 :body error-body})]
        (let [result (#'llm-providers.openai/base-responses-request!
                      {:rid "r1"
                       :api-key "fake-key"
                       :api-url "http://localhost:1"
                       :body {:model "mymodel" :input "hi" :stream false}
                       :url-relative-path "/v1/responses"})]
          (is (= :json (:as @request-opts*)))
          (is (= 429 (get-in result [:error :status])))
          (is (= error-body (get-in result [:error :body])))
          (is (= "7" (get-in result [:error :headers "retry-after"]))))))))

(deftest base-responses-codex-routing-test
  (testing "Codex requests send stable routing headers and replay returned turn state"
    (let [requests* (atom [])
          turn-context (#'llm-providers.openai/new-codex-turn-context)
          turn-id (:session-id turn-context)]
      (with-redefs [http/post (fn [url opts]
                                (swap! requests* conj [url opts])
                                {:status 200
                                 :headers {"x-codex-turn-state" "turn-state-1"}
                                 :body {:output [{:content [{:text "ok"}]}]}})]
        (dotimes [_ 2]
          (#'llm-providers.openai/base-responses-request!
           {:rid "r1"
            :api-key "oauth-token"
            :account-id "account-1"
            :api-url "https://api.openai.com"
            :codex? true
            :turn-context turn-context
            :responses-lite? true
            :body {:model "gpt-5.6-sol" :input "hi" :stream false}}))

        (let [[first-url first-request] (first @requests*)
              [_ second-request] (second @requests*)]
          (is (= "https://chatgpt.com/backend-api/codex/responses" first-url))
          (is (= turn-id (get-in first-request [:headers "Session-ID"])))
          (is (= turn-id (get-in first-request [:headers "Thread-ID"])))
          (is (= turn-id (get-in first-request [:headers "x-client-request-id"])))
          (is (= "account-1" (get-in first-request [:headers "ChatGPT-Account-ID"])))
          (is (= "true"
                 (get-in first-request
                         [:headers "x-openai-internal-codex-responses-lite"])))
          (is (nil? (get-in first-request [:headers "OpenAI-Beta"])))
          (is (nil? (get-in first-request [:headers "x-codex-turn-state"])))
          (is (= "turn-state-1"
                 (get-in second-request [:headers "x-codex-turn-state"]))))))))

(deftest create-response-codex-tool-continuation-replays-turn-state-test
  (testing "the post-tool request stays on the first response's Codex turn state"
    (let [requests* (atom [])
          first-stream (str
                        "event: response.completed\n"
                        "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"id\":\"item-1\",\"call_id\":\"call-1\",\"name\":\"eca__shell_command\",\"arguments\":\"{}\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n")
          final-stream (str
                        "event: response.completed\n"
                        "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n")]
      (with-redefs [http/post
                    (fn [_url opts]
                      (swap! requests* conj opts)
                      {:status 200
                       :headers {"x-codex-turn-state"
                                 (if (= 1 (count @requests*)) "state-1" "state-2")}
                       :body (java.io.ByteArrayInputStream.
                              (.getBytes ^String (if (= 1 (count @requests*))
                                                  first-stream
                                                  final-stream)
                                         java.nio.charset.StandardCharsets/UTF_8))})]
        (llm-providers.openai/create-response!
         {:model "gpt-test"
          :user-messages [{:role "user" :content [{:type :text :text "hi"}]}]
          :instructions "test"
          :reason? false
          :supports-image? false
          :api-key "oauth-token"
          :api-url "https://api.openai.com"
          :past-messages []
          :tools [{:full-name "eca__shell_command"
                   :description "run"
                   :parameters {:type "object"}}]
          :web-search false
          :provider "openai"
          :auth-type :auth/oauth}
         {:on-message-received (fn [_])
          :on-error (fn [error] (throw (ex-info "unexpected error" error)))
          :on-prepare-tool-call (fn [_])
          :on-tools-called (fn [_] {:new-messages [] :tools []})
          :on-reason (fn [_])
          :on-usage-updated (fn [_])
          :on-server-web-search (fn [_])
          :on-server-image-generation (fn [_])})
        (is (= 2 (count @requests*)))
        (is (nil? (get-in (first @requests*) [:headers "x-codex-turn-state"])))
        (is (= "state-1"
               (get-in (second @requests*) [:headers "x-codex-turn-state"])))))))

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

(deftest create-response-retries-post-tool-request-test
  (testing "retries the exact post-tool request without executing the tool again"
    (let [requests* (atom [])
          retry-events* (atom [])
          tools-called* (atom 0)
          messages* (atom [])
          errors* (atom [])]
      (with-redefs [llm-providers.openai/base-responses-request!
                    (fn [{:keys [body on-error on-stream] :as _opts}]
                      (let [request-number (count (swap! requests* conj body))]
                        (case request-number
                          1 (on-stream "response.completed"
                                       {:response {:status "completed"
                                                   :output [{:type "function_call"
                                                             :id "item-1"
                                                             :call_id "call-1"
                                                             :name "eca__read_file"
                                                             :arguments "{\"path\":\"/tmp/a\"}"}]
                                                   :usage {:input_tokens 1
                                                           :output_tokens 1}}})
                          2 (on-error {:type "service_unavailable_error"
                                       :code "server_is_overloaded"
                                       :message "Our servers are currently overloaded. Please try again later."
                                       :error/source :openai-responses})
                          3 (do
                              (on-stream "response.output_text.delta" {:delta "Recovered"})
                              (on-stream "response.completed"
                                         {:response {:status "completed"
                                                     :output []
                                                     :usage {:input_tokens 2
                                                             :output_tokens 1}}}))))
                      :ok)]
        (llm-providers.openai/create-response!
         {:model "gpt-test"
          :user-messages [{:role "user" :content [{:type :text :text "inspect"}]}]
          :instructions "ins"
          :reason? false
          :supports-image? false
          :api-key "token"
          :api-url "http://localhost:1"
          :past-messages []
          :tools [{:full-name "eca__read_file" :description "read" :parameters {:type "object"}}]
          :web-search false
          :extra-payload {}
          :extra-headers nil
          :auth-type :auth/api-key}
         {:on-message-received (fn [message] (swap! messages* conj message))
          :on-error (fn [error] (swap! errors* conj error))
          :on-prepare-tool-call (fn [_])
          :on-tools-called (fn [_]
                             (swap! tools-called* inc)
                             {:new-messages [{:role "tool_call"
                                              :content {:id "call-1"
                                                        :full-name "eca__read_file"
                                                        :arguments {:path "/tmp/a"}}}
                                             {:role "tool_call_output"
                                              :content {:id "call-1"
                                                        :full-name "eca__read_file"
                                                        :output {:error false
                                                                 :contents [{:type :text :text "contents"}]}}}]
                              :tools []})
          :on-reason (fn [_])
          :on-usage-updated (fn [_])
          :on-server-web-search (fn [_])
          :on-server-image-generation (fn [_])
          :retry-request (fn [{:keys [error-data attempt replay-safe? retry-fn]}]
                           (swap! retry-events* conj {:error-data error-data
                                                      :attempt attempt
                                                      :replay-safe? replay-safe?})
                           (retry-fn (inc attempt)))})
        (is (= 3 (count @requests*)))
        (is (= 1 @tools-called*) "the completed tool call must not be replayed")
        (is (= (second @requests*) (nth @requests* 2))
            "the retry must replay the exact post-tool request body")
        (is (true? (:replay-safe? (first @retry-events*))))
        (is (= "server_is_overloaded" (get-in (first @retry-events*) [:error-data :code])))
        (is (= [{:type :text :text "Recovered"}
                {:type :finish :finish-reason "completed"}]
               @messages*))
        (is (empty? @errors*))))))

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
          true))))
  (testing "User message with text + image (supports-image? true) emits input_text and input_image data URL"
    (is (match?
         [{:role "user"
           :content [{:type "input_text" :text "edit this"}
                     {:type "input_image"
                      :image_url "data:image/png;base64,AAA"}]}]
         (#'llm-providers.openai/normalize-messages
          [{:role "user" :content [{:type :text :text "edit this"}
                                   {:type :image :media-type "image/png" :base64 "AAA"}]}]
          true))))
  (testing "User message with image and supports-image? false drops the image part"
    (let [normalized (#'llm-providers.openai/normalize-messages
                      [{:role "user" :content [{:type :text :text "edit this"}
                                               {:type :image :media-type "image/png" :base64 "AAA"}]}]
                      false)]
      (is (= 1 (count normalized)))
      (is (= 1 (count (:content (first normalized))))
          "image part should be dropped, leaving only the text part")
      (is (= "input_text" (:type (first (:content (first normalized))))))))
  (testing "image_generation_call role replays as a USER-role input_image data URL"
    ;; OpenAI rejects input_image under assistant role. The standalone
    ;; {type:"image_generation_call",id,result} shape requires :store true
    ;; (the id triggers a server-side lookup, 404s with :store false). Most
    ;; reliable replay path: convert to a user-role input_image, symmetric
    ;; with how user-attached ImageContext images are serialized.
    (is (match?
         [{:role "user"
           :content [{:type "input_image"
                      :image_url "data:image/png;base64,DDD"}]}]
         (#'llm-providers.openai/normalize-messages
          [{:role "image_generation_call"
            :content {:id "ig_xyz" :media-type "image/png" :base64 "DDD"}}]
          true))))
  (testing "image_generation_call role defaults to image/png when :media-type missing"
    (is (match?
         [{:role "user"
           :content [{:type "input_image"
                      :image_url "data:image/png;base64,DDD"}]}]
         (#'llm-providers.openai/normalize-messages
          [{:role "image_generation_call" :content {:base64 "DDD"}}]
          true))))
  (testing "image_generation_call role drops the entry entirely when supports-image? is false"
    (is (= []
           (#'llm-providers.openai/normalize-messages
            [{:role "image_generation_call" :content {:base64 "DDD"}}]
            false))))
  (testing "server web search history artifacts are skipped on replay"
    (is (match?
         [{:role "assistant"
           :content [{:type "output_text" :text "Found the answer."}]}]
         (#'llm-providers.openai/normalize-messages
          [{:role "server_tool_use"
            :content {:id "ws_1" :name "web_search" :input {:query "latest news"}}}
           {:role "server_tool_result"
            :content {:tool-use-id "ws_1" :raw-content nil}}
           {:role "assistant"
            :content [{:type :text :text "Found the answer."}]}]
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

(defn- base-callbacks [{:keys [on-prepare-tool-call on-tools-called on-message-received on-error on-server-image-generation]
                        :or {on-prepare-tool-call (fn [_])
                             on-tools-called (fn [_] {:new-messages [] :tools []})
                             on-message-received (fn [_])
                             on-error (fn [e] (throw (ex-info "unexpected error in test" e)))
                             on-server-image-generation (fn [_])}}]
  {:on-message-received on-message-received
   :on-error on-error
   :on-prepare-tool-call on-prepare-tool-call
   :on-tools-called on-tools-called
   :on-reason (fn [_])
   :on-usage-updated (fn [_])
   :on-server-web-search (fn [_])
   :on-server-image-generation on-server-image-generation})

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

(deftest create-response-parallel-tool-calls-test
  (let [tool-batches* (atom [])
        requests* (atom [])
        read-tool {:full-name "eca__read_file"
                   :description "read"
                   :parameters {:type "object"}}]
    (with-redefs [llm-providers.openai/base-responses-request!
                  (fn [{:keys [on-stream] :as opts}]
                    (let [request-number (count (swap! requests* conj opts))]
                      (on-stream "response.completed"
                                 {:response {:output (if (= 1 request-number)
                                                      [{:type "function_call"
                                                        :id "item-1"
                                                        :call_id "call-1"
                                                        :name "eca__read_file"
                                                        :arguments "{\"path\":\"/a\"}"}
                                                       {:type "function_call"
                                                        :id "item-2"
                                                        :call_id "call-2"
                                                        :name "eca__read_file"
                                                        :arguments "{\"path\":\"/b\"}"}]
                                                      [])
                                             :usage {:input_tokens 10 :output_tokens 5}
                                             :status "completed"}})))]
      (llm-providers.openai/create-response!
       (assoc (base-provider-params)
              :provider "openai"
              :auth-type :auth/oauth
              :reason? true
              :web-search true
              :image-generation true
              :extra-payload {:parallel_tool_calls true}
              :provider-data {:responses-lite? true
                              :parallel-tool-calls? true
                              :parallel-tool-calls-without-lite? true}
              :tools [read-tool])
       (base-callbacks
        {:on-tools-called
         (fn [tool-calls]
           (swap! tool-batches* conj tool-calls)
           {:new-messages
            (mapcat (fn [{:keys [id full-name arguments]}]
                      [{:role "tool_call"
                        :content {:id id
                                  :full-name full-name
                                  :arguments arguments}}
                       {:role "tool_call_output"
                        :content {:id id
                                  :full-name full-name
                                  :output {:error false
                                           :contents [{:type :text :text "contents"}]}}}])
                    tool-calls)
            :tools [read-tool]})}))
      (is (= [["call-1" "item-1"] ["call-2" "item-2"]]
             (mapv (juxt :id :item-id) (first @tool-batches*))))
      (is (= 2 (count @requests*)))
      (doseq [request @requests*]
        (is (false? (:responses-lite? request)))
        (is (= "test" (get-in request [:body :instructions])))
        (is (= ["function"] (mapv :type (get-in request [:body :tools]))))
        (is (true? (get-in request [:body :parallel_tool_calls])))
        (is (= "all_turns" (get-in request [:body :reasoning :context]))))
      (is (= [["function_call" "call-1"]
              ["function_call_output" "call-1"]
              ["function_call" "call-2"]
              ["function_call_output" "call-2"]]
             (mapv (juxt :type :call_id)
                   (get-in (second @requests*) [:body :input])))))))

(deftest create-response-sync-error-test
  (testing "sync completion path returns a structured error instead of throwing on non-200 (#495)"
    (with-client-proxied {:version :http-2}
      (fn [_req]
        {:status 401 :body "unauthorized"})
      ;; callbacks=nil -> sync mode: on-stream is set but on-error is nil,
      ;; which previously caused an NPE when the request returned non-200.
      (let [result (llm-providers.openai/create-response! (base-provider-params) nil)]
        (is (match? {:error {:status 401
                             :body "unauthorized"
                             :message "OpenAI response status: 401 body: unauthorized"}}
                    result))))))

(deftest create-response-failed-event-test
  (let [failed-response {:id "resp_123"
                         :status "failed"
                         :error {:code "server_error"
                                 :type "server_error"
                                 :message "An error occurred while processing your request. You can retry your request."
                                 :provider-detail "preserved"}}
        response-headers {"X-Request-ID" ["req_123"]}
        expected-error {:code "server_error"
                        :type "server_error"
                        :message "An error occurred while processing your request. You can retry your request."
                        :provider-detail "preserved"
                        :error/source :openai-responses
                        :response-id "resp_123"
                        :request-id "req_123"
                        :headers response-headers}]
    (testing "streaming failure closes the stream before error handling and ignores trailing events"
      (let [closed?* (atom false)
            closed-at-error?* (atom false)
            errors* (atom [])
            messages* (atom [])
            tool-prepares* (atom [])
            stream-text (str "event: response.failed\n"
                             "data: {\"type\":\"response.failed\",\"response\":{\"id\":\"resp_123\",\"status\":\"failed\",\"error\":{\"code\":\"server_error\",\"type\":\"server_error\",\"message\":\"An error occurred while processing your request. You can retry your request.\",\"provider-detail\":\"preserved\"}}}\n\n"
                             "event: response.output_text.delta\n"
                             "data: {\"type\":\"response.output_text.delta\",\"delta\":\"stale\"}\n\n"
                             "event: response.output_item.added\n"
                             "data: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"function_call\",\"id\":\"item_1\",\"call_id\":\"call_1\",\"name\":\"tool\",\"arguments\":\"{}\"}}\n\n"
                             "event: response.completed\n"
                             "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n")
            stream-body (proxy [java.io.ByteArrayInputStream]
                                [(.getBytes ^String stream-text java.nio.charset.StandardCharsets/UTF_8)]
                          (close []
                            (reset! closed?* true)
                            (proxy-super close)))]
        (with-redefs [http/post (fn [_url opts]
                                  (is (= :stream (:as opts)))
                                  {:status 200
                                   :headers response-headers
                                   :body stream-body})]
          (llm-providers.openai/create-response!
           (base-provider-params)
           (base-callbacks
            {:on-error (fn [error]
                         (reset! closed-at-error?* @closed?*)
                         (swap! errors* conj error))
             :on-message-received (fn [message] (swap! messages* conj message))
             :on-prepare-tool-call (fn [tool] (swap! tool-prepares* conj tool))})))
        (is (= [expected-error] @errors*))
        (is (true? @closed-at-error?*)
            "retry/error handling must start only after the failed stream is closed")
        (is (empty? @messages*)
            "events after response.failed must not emit text or finish")
        (is (empty? @tool-prepares*)
            "events after response.failed must not prepare tool calls")))

    (testing "stream false JSON failure returns the same structured error"
      (with-redefs [http/post (fn [_url opts]
                                (is (= :json (:as opts)))
                                {:status 200
                                 :headers response-headers
                                 :body failed-response})]
        (is (= {:error expected-error}
               (llm-providers.openai/create-response!
                (assoc (base-provider-params) :extra-payload {:stream false})
                nil)))))

    (testing "structured request ID takes precedence over response and header IDs"
      (let [error (#'llm-providers.openai/response-failed->error-data
                   {:response {:id "resp_123"
                               :request_id "response_req"
                               :error {:message "failed"
                                       :request_id "error_req"}}}
                   {"x-request-id" "header_req"})]
        (is (= "error_req" (:request-id error)))
        (is (= "error_req" (:request_id error)))))))

(deftest create-response-stream-cancelled-test
  (testing "watchdog aborts a hung stream when cancelled, surfacing a silent error"
    (let [errors* (atom [])
          messages* (atom [])
          stream-body (blocking-input-stream)]
      (with-redefs [http/post (fn [_url opts]
                                (is (= :stream (:as opts)))
                                {:status 200
                                 :headers {}
                                 :body stream-body})]
        (llm-providers.openai/create-response!
         (assoc (base-provider-params) :cancelled? (constantly true))
         (base-callbacks
          {:on-error (fn [error] (swap! errors* conj error))
           :on-message-received (fn [message] (swap! messages* conj message))})))
      (is (= 1 (count @errors*)))
      (is (= "Stream cancelled" (ex-message (:exception (first @errors*)))))
      (is (true? (:silent? (ex-data (:exception (first @errors*))))))
      (is (empty? @messages*)))))

(deftest create-response-error-event-test
  (testing "generic SSE error terminates the stream and invokes error handling"
    (let [response-headers {"X-Request-ID" ["req_overloaded"]}
          expected-error {:code "server_is_overloaded"
                          :message "Our servers are currently overloaded. Please try again later."
                          :sequence_number 0
                          :error/source :openai-responses
                          :request-id "req_overloaded"
                          :headers response-headers}
          closed?* (atom false)
          closed-at-error?* (atom false)
          errors* (atom [])
          messages* (atom [])
          stream-text (str "event: error\n"
                           "data: {\"type\":\"error\",\"code\":\"server_is_overloaded\",\"message\":\"Our servers are currently overloaded. Please try again later.\",\"sequence_number\":0}\n\n"
                           "event: response.completed\n"
                           "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n")
          stream-body (proxy [java.io.ByteArrayInputStream]
                              [(.getBytes ^String stream-text java.nio.charset.StandardCharsets/UTF_8)]
                        (close []
                          (reset! closed?* true)
                          (proxy-super close)))]
      (with-redefs [http/post (fn [_url opts]
                                (is (= :stream (:as opts)))
                                {:status 200
                                 :headers response-headers
                                 :body stream-body})]
        (llm-providers.openai/create-response!
         (base-provider-params)
         (base-callbacks
          {:on-error (fn [error]
                       (reset! closed-at-error?* @closed?*)
                       (swap! errors* conj error))
           :on-message-received (fn [message] (swap! messages* conj message))})))
      (is (= [expected-error] @errors*))
      (is (true? @closed-at-error?*)
          "retry/error handling must start only after the errored stream is closed")
      (is (empty? @messages*)
          "events after error must not emit a finish message"))))

(deftest create-response-incomplete-event-test
  (testing "response.incomplete terminates the stream and invokes error handling"
    (let [response-headers {"X-Request-ID" ["req_incomplete"]}
          expected-error {:message "OpenAI response incomplete: max_output_tokens"
                          :error/type :premature-stop
                          :error/source :openai-responses
                          :headers response-headers
                          :response-id "resp_incomplete"
                          :request-id "req_incomplete"
                          :incomplete-reason "max_output_tokens"}
          errors* (atom [])
          messages* (atom [])
          stream-text (str "event: response.incomplete\n"
                           "data: {\"type\":\"response.incomplete\",\"response\":{\"id\":\"resp_incomplete\",\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}\n\n"
                           "event: response.completed\n"
                           "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n")]
      (with-redefs [http/post (fn [_url _opts]
                                {:status 200
                                 :headers response-headers
                                 :body (java.io.ByteArrayInputStream.
                                        (.getBytes ^String stream-text java.nio.charset.StandardCharsets/UTF_8))})]
        (llm-providers.openai/create-response!
         (base-provider-params)
         (base-callbacks
          {:on-error (fn [error] (swap! errors* conj error))
           :on-message-received (fn [message] (swap! messages* conj message))})))
      (is (= [expected-error] @errors*))
      (is (empty? @messages*)
          "events after response.incomplete must not emit a finish message"))))

(deftest create-response-premature-eof-test
  (testing "EOF before a terminal response event invokes error handling"
    (let [response-headers {"X-Request-ID" ["req_disconnected"]}
          expected-error {:message "Stream disconnected before completion: stream closed before response.completed"
                          :error/type :premature-stop
                          :error/source :openai-responses
                          :request-id "req_disconnected"
                          :headers response-headers}
          closed?* (atom false)
          closed-at-error?* (atom false)
          errors* (atom [])
          messages* (atom [])
          stream-text (str "event: response.created\n"
                           "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_123\",\"status\":\"in_progress\"}}\n\n")
          stream-body (proxy [java.io.ByteArrayInputStream]
                              [(.getBytes ^String stream-text java.nio.charset.StandardCharsets/UTF_8)]
                        (close []
                          (reset! closed?* true)
                          (proxy-super close)))]
      (with-redefs [http/post (fn [_url opts]
                                (is (= :stream (:as opts)))
                                {:status 200
                                 :headers response-headers
                                 :body stream-body})]
        (llm-providers.openai/create-response!
         (base-provider-params)
         (base-callbacks
          {:on-error (fn [error]
                       (reset! closed-at-error?* @closed?*)
                       (swap! errors* conj error))
           :on-message-received (fn [message] (swap! messages* conj message))})))
      (is (= [expected-error] @errors*))
      (is (true? @closed-at-error?*)
          "retry/error handling must start only after the disconnected stream is closed")
      (is (empty? @messages*)
          "premature EOF must not emit a finish message"))))

(deftest normalize-messages-tool-call-output-image-test
  (let [tool-output-with-image
        {:role "tool_call_output"
         :content {:id "call-1"
                   :name "create-image"
                   :output {:contents [{:type :text :text "saved"}
                                       {:type :image
                                        :media-type "image/png"
                                        :base64 "AAAA"}]}}}]
    (testing "image content + supports-image? true emits function_call_output + user input_image"
      (let [out (vec (#'llm-providers.openai/normalize-messages
                      [tool-output-with-image] true))]
        (is (= 2 (count out))
            "two messages: function_call_output then user input_image")
        (is (match? {:type "function_call_output"
                     :call_id "call-1"
                     :output #(string/includes? % "[Image: image/png]")}
                    (first out)))
        (is (match? {:role "user"
                     :content [{:type "input_image"
                                :image_url "data:image/png;base64,AAAA"}]}
                    (second out)))))

    (testing "image content + supports-image? false emits only text function_call_output"
      (let [out (vec (#'llm-providers.openai/normalize-messages
                      [tool-output-with-image] false))]
        (is (= 1 (count out)))
        (is (match? {:type "function_call_output" :call_id "call-1"}
                    (first out)))))

    (testing "no image content emits only text function_call_output"
      (let [out (vec (#'llm-providers.openai/normalize-messages
                      [{:role "tool_call_output"
                        :content {:id "call-2"
                                  :output {:contents [{:type :text :text "ok"}]}}}]
                      true))]
        (is (= 1 (count out)))
        (is (match? {:type "function_call_output"
                     :call_id "call-2"
                     :output "ok\n"}
                    (first out)))))))

(deftest ->tools-built-in-tools-test
  (testing "image_generation tool is appended when flag is on"
    (is (match?
         [{:type "image_generation" :output_format "png"}]
         (#'llm-providers.openai/->tools [] false true))))
  (testing "image_generation tool is NOT appended when flag is off"
    (is (= []
           (#'llm-providers.openai/->tools [] false false))))
  (testing "image_generation tool sits alongside web_search and function tools"
    (is (match?
         [{:type "function" :name "eca__foo"}
          {:type "web_search"}
          {:type "image_generation" :output_format "png"}]
         (#'llm-providers.openai/->tools
          [{:full-name "eca__foo" :description "d" :parameters {}}]
          true true))))
  (testing "function tools explicitly opt out of Responses strict mode"
    (is (= [{:type "function"
             :name "mcp__search_records"
             :description "Search records"
             :parameters {:type "object"
                          :properties {"limit" {:type "number"}}}
             :strict false}]
           (#'llm-providers.openai/->tools
            [{:full-name "mcp__search_records"
              :description "Search records"
              :parameters {:type "object"
                           :properties {"limit" {:type "number"}}}}]
            false false)))))

(deftest create-response-standard-preserves-built-in-tools-test
  (testing "standard Responses requests keep web_search and image_generation when enabled"
    (let [requests* (atom [])]
      (with-redefs [llm-providers.openai/base-responses-request!
                    (fn [{:keys [on-stream] :as opts}]
                      (swap! requests* conj opts)
                      (on-stream "response.completed"
                                 {:response {:output []
                                             :usage {:input_tokens 0 :output_tokens 0}
                                             :status "completed"}}))]
        (llm-providers.openai/create-response!
         (assoc (base-provider-params)
                :web-search true
                :image-generation true)
         (base-callbacks {}))
        (is (match? [{:type "function"}
                     {:type "web_search"}
                     {:type "image_generation" :output_format "png"}]
                    (get-in (first @requests*) [:body :tools])))))))

(deftest create-response-codex-partial-live-metadata-stays-lite-test
  (let [request* (atom nil)]
    (with-redefs [http/get
                  (fn [_url _opts]
                    {:status 200
                     :body {:models [{:slug "gpt-5.6-sol"
                                      :supports_parallel_tool_calls true}]}})
                  llm-providers.openai/base-responses-request!
                  (fn [{:keys [on-stream] :as opts}]
                    (reset! request* opts)
                    (on-stream "response.completed"
                               {:response {:output []
                                           :usage {:input_tokens 0 :output_tokens 0}
                                           :status "completed"}}))]
      (let [models (#'llm-providers.openai/fetch-oauth-models "oauth-token" {})
            provider-data (get-in models
                                  ["gpt-5.6-sol" :discovered-provider-data])]
        (is (true? (:responses-lite? provider-data)))
        (is (true? (:parallel-tool-calls? provider-data)))
        (is (nil? (:parallel-tool-calls-without-lite? provider-data)))
        (llm-providers.openai/create-response!
         (assoc (base-provider-params)
                :model "gpt-5.6-sol"
                :provider "openai"
                :auth-type :auth/oauth
                :extra-payload {:parallel_tool_calls true}
                :provider-data provider-data)
         (base-callbacks {}))
        (is (true? (:responses-lite? @request*)))
        (is (false? (get-in @request* [:body :parallel_tool_calls])))
        (is (= "additional_tools" (get-in @request* [:body :input 0 :type])))))))

(deftest create-response-codex-parallel-shape-compatibility-test
  (let [request* (atom nil)
        cases [{:label "requested false stays Lite"
                :params {:extra-payload {:parallel_tool_calls false}
                         :provider-data {:responses-lite? true
                                         :parallel-tool-calls? true
                                         :parallel-tool-calls-without-lite? true}}}
               {:label "no function tools stays Lite"
                :params {:tools []
                         :extra-payload {:parallel_tool_calls true}
                         :provider-data {:responses-lite? true
                                         :parallel-tool-calls? true
                                         :parallel-tool-calls-without-lite? true}}}]]
    (with-redefs [llm-providers.openai/base-responses-request!
                  (fn [{:keys [on-stream] :as opts}]
                    (reset! request* opts)
                    (on-stream "response.completed"
                               {:response {:output []
                                           :usage {:input_tokens 0 :output_tokens 0}
                                           :status "completed"}}))]
      (doseq [{:keys [label params]} cases]
        (testing label
          (llm-providers.openai/create-response!
           (merge (base-provider-params)
                  {:provider "openai" :auth-type :auth/oauth}
                  params)
           (base-callbacks {}))
          (is (true? (:responses-lite? @request*)))
          (is (false? (get-in @request* [:body :parallel_tool_calls])))
          (is (= "additional_tools" (get-in @request* [:body :input 0 :type]))))))))

(deftest create-response-codex-request-shapes-test
  (testing "API-key requests ignore Codex-only Lite metadata, even for Lite models"
    (let [request* (atom nil)]
      (with-redefs [llm-providers.openai/base-responses-request!
                    (fn [{:keys [on-stream] :as opts}]
                      (reset! request* opts)
                      (on-stream "response.completed"
                                 {:response {:output []
                                             :usage {:input_tokens 0 :output_tokens 0}
                                             :status "completed"}}))]
        (llm-providers.openai/create-response!
         (assoc (base-provider-params)
                :model "gpt-5.6-sol"
                :provider "openai"
                :provider-data {:responses-lite? true})
         (base-callbacks {}))
        (is (= "test" (get-in @request* [:body :instructions])))
        (is (= ["function"] (mapv :type (get-in @request* [:body :tools]))))
        (is (nil? (get-in @request* [:body :tool_choice])))
        (is (= "user" (-> @request* :body :input first :role))))))

  (testing "regular Codex requests use top-level instructions without duplicating a system input"
    (let [request* (atom nil)]
      (with-redefs [llm-providers.openai/base-responses-request!
                    (fn [{:keys [on-stream] :as opts}]
                      (reset! request* opts)
                      (on-stream "response.completed"
                                 {:response {:output []
                                             :usage {:input_tokens 0 :output_tokens 0}
                                             :status "completed"}}))]
        (llm-providers.openai/create-response!
         (assoc (base-provider-params)
                :provider "openai"
                :auth-type :auth/oauth)
         (base-callbacks {}))
        (is (= "test" (get-in @request* [:body :instructions])))
        (is (= "auto" (get-in @request* [:body :tool_choice])))
        (is (= ["user"] (mapv :role (get-in @request* [:body :input])))))))

  (testing "Responses Lite moves instructions and function tools into developer input items"
    (let [request* (atom nil)]
      (with-redefs [llm-providers.openai/base-responses-request!
                    (fn [{:keys [on-stream] :as opts}]
                      (reset! request* opts)
                      (on-stream "response.completed"
                                 {:response {:output []
                                             :usage {:input_tokens 0 :output_tokens 0}
                                             :status "completed"}}))]
        ;; gpt-5.6-sol Lite metadata (:responses-lite? true, default effort
        ;; "low") comes from the static fallback, no :provider-data needed.
        (llm-providers.openai/create-response!
         (assoc (base-provider-params)
                :model "gpt-5.6-sol"
                :provider "openai"
                :auth-type :auth/oauth
                :reason? true
                :web-search true
                :image-generation true)
         (base-callbacks {}))
        (let [body (:body @request*)]
          (is (nil? (:instructions body)))
          (is (nil? (:tools body)))
          (is (= "additional_tools" (get-in body [:input 0 :type])))
          (is (= ["function"] (mapv :type (get-in body [:input 0 :tools]))))
          (is (= "developer" (get-in body [:input 1 :role])))
          (is (= {:effort "low" :summary "auto" :context "all_turns"}
                 (:reasoning body)))
          (is (= "auto" (:tool_choice body)))
          (is (false? (:parallel_tool_calls body)))
          (is (true? (:responses-lite? @request*)))))))

  (testing "discovered provider-data wins over the static Lite fallback"
    (let [request* (atom nil)]
      (with-redefs [llm-providers.openai/base-responses-request!
                    (fn [{:keys [on-stream] :as opts}]
                      (reset! request* opts)
                      (on-stream "response.completed"
                                 {:response {:output []
                                             :usage {:input_tokens 0 :output_tokens 0}
                                             :status "completed"}}))]
        (llm-providers.openai/create-response!
         (assoc (base-provider-params)
                :model "gpt-5.6-sol"
                :provider "openai"
                :auth-type :auth/oauth
                :provider-data {:responses-lite? false})
         (base-callbacks {}))
        (is (false? (:responses-lite? @request*)))
        (is (= "test" (get-in @request* [:body :instructions]))))))

  (testing "provider-data can disable parallel tool calls for Codex requests"
    (let [request* (atom nil)]
      (with-redefs [llm-providers.openai/base-responses-request!
                    (fn [{:keys [on-stream] :as opts}]
                      (reset! request* opts)
                      (on-stream "response.completed"
                                 {:response {:output []
                                             :usage {:input_tokens 0 :output_tokens 0}
                                             :status "completed"}}))]
        (llm-providers.openai/create-response!
         (assoc (base-provider-params)
                :provider "openai"
                :auth-type :auth/oauth
                :extra-payload {:parallel_tool_calls true}
                :provider-data {:parallel-tool-calls? false})
         (base-callbacks {}))
        (is (false? (get-in @request* [:body :parallel_tool_calls])))))))

(deftest create-response-image-generation-tool-on-request-test
  (testing "request body includes image_generation tool when :image-generation is true"
    (let [requests* (atom [])]
      (with-redefs [llm-providers.openai/base-responses-request!
                    (fn [{:keys [on-stream] :as opts}]
                      (swap! requests* conj opts)
                      (on-stream "response.completed"
                                 {:response {:output []
                                             :usage {:input_tokens 0 :output_tokens 0}
                                             :status "completed"}}))]
        (llm-providers.openai/create-response!
         (assoc (base-provider-params) :image-generation true)
         (base-callbacks {}))
        (is (= 1 (count @requests*)))
        (is (some #(= {:type "image_generation" :output_format "png"} %)
                  (get-in (first @requests*) [:body :tools]))
            "tools array should include the image_generation built-in tool"))))
  (testing "request body excludes image_generation tool when :image-generation is false"
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
        (is (not-any? #(= "image_generation" (:type %))
                      (get-in (first @requests*) [:body :tools]))
            "tools array should NOT include image_generation when flag is off")))))

(deftest create-response-image-generation-streaming-test
  (testing "image_generation_call streaming events trigger callbacks with base64 payload"
    (let [server-events* (atom [])
          received-msgs* (atom [])
          requests* (atom [])]
      (with-redefs [llm-providers.openai/base-responses-request!
                    (fn [{:keys [on-stream] :as opts}]
                      (swap! requests* conj opts)
                      (on-stream "response.output_item.added"
                                 {:item {:type "image_generation_call"
                                         :id "img-1"}})
                      (on-stream "response.output_item.done"
                                 {:item {:type "image_generation_call"
                                         :id "img-1"
                                         :result "BASE64DATAHERE"}})
                      (on-stream "response.completed"
                                 {:response {:output []
                                             :usage {:input_tokens 0 :output_tokens 0}
                                             :status "completed"}}))]
        (llm-providers.openai/create-response!
         (assoc (base-provider-params) :image-generation true)
         (base-callbacks
          {:on-message-received (fn [m] (swap! received-msgs* conj m))
           :on-server-image-generation (fn [m] (swap! server-events* conj m))}))
        (is (match?
             [{:status :started :id "img-1" :name "image_generation"}
              {:status :finished :id "img-1"}]
             @server-events*)
            "should emit a started event followed by a finished event")
        (is (some (fn [m] (and (= :image (:type m))
                               (= "image/png" (:media-type m))
                               (= "BASE64DATAHERE" (:base64 m))
                               (= "img-1" (:id m))))
                  @received-msgs*)
            "should emit an :image message with base64 payload AND :id (used by chat.clj to persist as image_generation_call for replay)")))))

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

(deftest fetch-oauth-models-live-test
  (testing "resolves Codex /models limits and authorizes with the OAuth token"
    (let [request* (atom nil)]
      (with-redefs [http/get (fn [url opts]
                               (reset! request* [url opts])
                               {:status 200
                                :body {:models [{:slug "gpt-5.5"
                                                 :context_window 272000
                                                 :max_output_tokens 128000}
                                                {:slug "gpt-5.6-sol"
                                                 :context_window 272000
                                                 :use_responses_lite true
                                                 :default_reasoning_level "low"
                                                 :supported_reasoning_levels [{:effort "low"}
                                                                              {:effort "max"}]}
                                                {:slug "gpt-5.6-terra"
                                                 :use_responses_lite false}]}})]
        (let [result (#'llm-providers.openai/fetch-oauth-models
                      "oauth-token" "account-1" {"gpt-5.5" {}})
              client-version (second (re-find #"client_version=(\d+\.\d+\.\d+)"
                                              (first @request*)))]
          (is (re-matches
               #"https://chatgpt\.com/backend-api/codex/models\?client_version=\d+\.\d+\.\d+"
               (first @request*)))
          (is (= "Bearer oauth-token" (get-in @request* [1 :headers "Authorization"])))
          (is (= "codex_cli_rs" (get-in @request* [1 :headers "Originator"])))
          (is (= (str "codex_cli_rs/" client-version)
                 (get-in @request* [1 :headers "User-Agent"])))
          (is (= "account-1" (get-in @request* [1 :headers "ChatGPT-Account-ID"])))
          (is (= 272000 (get-in result ["gpt-5.5" :limit :context])))
          (is (= 128000 (get-in result ["gpt-5.5" :limit :output])))
          (is (true? (get-in result ["gpt-5.6-sol" :discovered-provider-data :responses-lite?])))
          (is (false? (get-in result ["gpt-5.6-terra" :discovered-provider-data :responses-lite?])))
          (is (= "low" (get-in result ["gpt-5.6-sol" :discovered-provider-data :default-reasoning-effort])))
          (is (= #{"low" "max"}
                 (set (keys (get-in result ["gpt-5.6-sol" :discovered-variants]))))))))))

(deftest fetch-oauth-models-fallback-on-error-test
  (testing "falls back to known Codex caps when /models is unauthorized"
    (with-redefs [http/get (fn [_url _opts] {:status 401 :body {:error "unauthorized"}})]
      (let [result (#'llm-providers.openai/fetch-oauth-models "expired-token" {"gpt-5.5" {}})]
        (is (= 272000 (get-in result ["gpt-5.5" :limit :context])))))))

(deftest fetch-oauth-models-live-preserves-fallback-limit-test
  (testing "a live model without a context window keeps the fallback cap"
    (with-redefs [http/get (fn [_url _opts] {:status 200 :body {:models [{:slug "gpt-5.5"}]}})]
      (let [result (#'llm-providers.openai/fetch-oauth-models "oauth-token" {"gpt-5.5" {}})]
        (is (= 272000 (get-in result ["gpt-5.5" :limit :context])))))))

(deftest fetch-oauth-models-no-api-key-uses-fallback-test
  (testing "without an API key, returns fallback caps without hitting the network"
    (let [calls* (atom 0)]
      (with-redefs [http/get (fn [_ _] (swap! calls* inc) {:status 200 :body {}})]
        (let [result (#'llm-providers.openai/fetch-oauth-models nil {})]
          (is (= 272000 (get-in result ["gpt-5.5" :limit :context])))
          (is (zero? @calls*)))))))

(deftest codex-turn-context-headers-test
  (let [context (#'llm-providers.openai/new-codex-turn-context)
        turn-id (:session-id context)
        client-version (second (re-find #"client_version=(\d+\.\d+\.\d+)"
                                        @#'llm-providers.openai/codex-models-url))]
    (testing "session and thread routing stay stable for the turn"
      (let [headers (#'llm-providers.openai/codex-request-headers
                     {:account-id "account-1"
                      :turn-context context})]
        (is (= {"ChatGPT-Account-ID" "account-1"
                "Originator" "codex_cli_rs"
                "Session-ID" turn-id
                "Thread-ID" turn-id
                "x-client-request-id" turn-id}
               (dissoc headers "User-Agent")))
        (is (= (str "codex_cli_rs/" client-version)
               (get headers "User-Agent")))))

    (testing "the first turn state is replayed and later values cannot replace it"
      (#'llm-providers.openai/capture-codex-turn-state! context "state-1")
      (#'llm-providers.openai/capture-codex-turn-state! context "state-2")
      (is (= "state-1"
             (get (#'llm-providers.openai/codex-request-headers {:turn-context context})
                  "x-codex-turn-state"))))))

(deftest codex-responses-lite-body-test
  (let [body (#'llm-providers.openai/codex-responses-lite-body
              {:model "gpt-5.6-sol"
               :instructions "Use the repository rules."
               :input [{:role "user" :content "Fix it"}]
               :tools [{:type "function" :name "eca__read_file"}
                       {:type "web_search"}
                       {:type "image_generation"}]
               :parallel_tool_calls true
               :reasoning {:effort "low" :summary "auto"}
               :stream true})]
    (is (nil? (:instructions body)))
    (is (nil? (:tools body)))
    (is (false? (:parallel_tool_calls body)))
    (is (= {:effort "low" :summary "auto" :context "all_turns"}
           (:reasoning body)))
    (is (= [{:type "additional_tools"
             :role "developer"
             :tools [{:type "function" :name "eca__read_file"}]}
            {:type "message"
             :role "developer"
             :content [{:type "input_text"
                        :text "Use the repository rules."}]}
            {:role "user" :content "Fix it"}]
           (:input body))))
  (testing "Lite always declares all-turn reasoning context"
    (is (= {:context "all_turns"}
           (:reasoning
            (#'llm-providers.openai/codex-responses-lite-body
             {:input [{:role "user" :content "Generate a title"}]}))))))

(deftest codex-live-model-discovery-test
  (is (= {:discovered-provider-data {:responses-lite? true
                                     :parallel-tool-calls-without-lite? true
                                     :default-reasoning-effort "low"
                                     :parallel-tool-calls? true}
          :discovered-variants
          {"low" {:reasoning {:effort "low" :summary "auto"}}
           "max" {:reasoning {:effort "max" :summary "auto"}}}}
         (#'llm-providers.openai/codex-live-model-discovery
          {:use_responses_lite true
           :default_reasoning_level "low"
           :supported_reasoning_levels [{:effort "low"}
                                        {:effort "max"}
                                        {:effort "ultra"}]
           :supports_parallel_tool_calls true})))
  (testing "an explicit live false value overrides static Lite fallbacks"
    (is (false? (get-in (#'llm-providers.openai/codex-live-model-discovery
                         {:use_responses_lite false})
                        [:discovered-provider-data :responses-lite?])))))

(deftest codex-model-fallback-discovery-test
  (testing "all current Lite Codex models retain Lite metadata without /models"
    (doseq [model ["gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6-luna" "gpt-6-astra"]]
      (is (true? (get-in (#'llm-providers.openai/codex-model-fallback-discovery model)
                         [:discovered-provider-data :responses-lite?])))))
  (testing "gpt-6-astra defaults to low effort and has no none variant"
    (let [discovery (#'llm-providers.openai/codex-model-fallback-discovery "gpt-6-astra")]
      (is (= "low" (get-in discovery [:discovered-provider-data :default-reasoning-effort])))
      (is (= #{"low" "medium" "high" "xhigh" "max"}
             (set (keys (:discovered-variants discovery)))))))
  (testing "gpt-6-astra keeps the Codex context cap without /models"
    (is (= 272000
           (get-in (#'llm-providers.openai/fetch-oauth-models nil {})
                   ["gpt-6-astra" :limit :context]))))
  (testing "model matching is case-insensitive"
    (is (true? (get-in (#'llm-providers.openai/codex-model-fallback-discovery "GPT-5.6-SOL")
                       [:discovered-provider-data :responses-lite?])))))
