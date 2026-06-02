(ns eca.llm-api-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.client-test-helpers :refer [with-client-proxied *http-client-captures*]]
   [eca.config :as config]
   [eca.llm-api :as llm-api]
   [eca.llm-providers.anthropic :as llm-providers.anthropic]
   [eca.llm-providers.openai :as llm-providers.openai]
   [eca.secrets :as secrets]
   [eca.test-helper :as h]))

(h/reset-components-before-test)

(deftest sanitize-past-messages-for-api-test
  (testing "drops anthropic-origin reason when target is :openai-chat"
    (let [past [{:role "user" :content [{:type :text :text "hi"}]}
                {:role "reason" :content {:id "r1" :external-id "sig-xyz" :text "thinking"
                                          :api :anthropic}}
                {:role "assistant" :content [{:type :text :text "ok"}]}]
          {:keys [messages dropped-count dropped-apis]}
          (llm-api/sanitize-past-messages-for-api :openai-chat past)]
      (is (= 1 dropped-count))
      (is (= #{:anthropic} dropped-apis))
      (is (= 2 (count messages)))
      (is (= ["user" "assistant"] (mapv :role messages)))))

  (testing "drops openai-responses-origin reason when target is :anthropic"
    (let [past [{:role "reason" :content {:id "rs_abc" :external-id "encrypted-blob" :text "thinking"
                                          :api :openai-responses}}
                {:role "user" :content [{:type :text :text "hi"}]}]
          {:keys [messages dropped-count dropped-apis]}
          (llm-api/sanitize-past-messages-for-api :anthropic past)]
      (is (= 1 dropped-count))
      (is (= #{:openai-responses} dropped-apis))
      (is (= 1 (count messages)))
      (is (= "user" (:role (first messages))))))

  (testing "drops tool_call/tool_call_output pairs whose :api differs from target"
    (let [past [{:role "user" :content [{:type :text :text "hi"}]}
                {:role "tool_call" :content {:id "toolu_aaa" :full-name "read"
                                             :api :anthropic}}
                {:role "tool_call_output" :content {:id "toolu_aaa" :output {:contents []}
                                                    :api :anthropic}}
                {:role "user" :content [{:type :text :text "next"}]}]
          {:keys [messages dropped-count]}
          (llm-api/sanitize-past-messages-for-api :openai-chat past)]
      (is (= 2 dropped-count) "tool_call and tool_call_output both removed")
      (is (= 2 (count messages)))
      (is (every? #(= "user" (:role %)) messages))))

  (testing "drops anthropic server_tool_use and server_tool_result on switch away"
    (let [past [{:role "server_tool_use" :content {:id "stu_1" :name "web_search"
                                                   :api :anthropic}}
                {:role "server_tool_result" :content {:tool-use-id "stu_1" :raw-content {}
                                                      :api :anthropic}}
                {:role "user" :content [{:type :text :text "k"}]}]
          {:keys [messages dropped-count]}
          (llm-api/sanitize-past-messages-for-api :openai-responses past)]
      (is (= 2 dropped-count))
      (is (= [{:role "user" :content [{:type :text :text "k"}]}] messages))))

  (testing "same-api round-trip preserves all entries"
    (let [past [{:role "user" :content [{:type :text :text "hi"}]}
                {:role "reason" :content {:id "r1" :external-id "sig" :text "t"
                                          :api :anthropic}}
                {:role "tool_call" :content {:id "toolu_a" :full-name "read"
                                             :api :anthropic}}
                {:role "tool_call_output" :content {:id "toolu_a" :output {:contents []}
                                                    :api :anthropic}}]
          {:keys [messages dropped-count dropped-apis]}
          (llm-api/sanitize-past-messages-for-api :anthropic past)]
      (is (zero? dropped-count))
      (is (empty? dropped-apis))
      (is (= past messages))))

  (testing "untagged (legacy) entries are preserved as-is"
    (let [past [{:role "user" :content [{:type :text :text "hi"}]}
                {:role "reason" :content {:id "r1" :external-id "sig" :text "t"}}
                {:role "tool_call" :content {:id "toolu_a" :full-name "read"}}]
          {:keys [messages dropped-count]}
          (llm-api/sanitize-past-messages-for-api :openai-chat past)]
      (is (zero? dropped-count))
      (is (= past messages))))

  (testing "internal-only top-level message fields are stripped before provider serialization"
    (let [past [{:role "user"
                 :content [{:type :text :text "hi"}]
                 :created-at 123
                 :content-id "c1"}
                {:role "assistant"
                 :content [{:type :text :text "ok"}]
                 :created-at 456
                 :content-id "c2"}]
          {:keys [messages dropped-count]}
          (llm-api/sanitize-past-messages-for-api :openai-chat past)]
      (is (zero? dropped-count))
      (is (= [{:role "user" :content [{:type :text :text "hi"}]}
              {:role "assistant" :content [{:type :text :text "ok"}]}]
             messages))
      (is (every? #(not (contains? % :created-at)) messages))
      (is (every? #(not (contains? % :content-id)) messages))))

  (testing "mixed history: tagged foreign entries dropped, untagged + matching kept"
    (let [past [{:role "user" :content [{:type :text :text "u1"}]}
                {:role "reason" :content {:id "r0" :text "legacy"}}                            ; untagged → kept
                {:role "reason" :content {:id "r1" :text "t" :api :anthropic}}                 ; foreign → dropped
                {:role "reason" :content {:id "r2" :text "t" :api :openai-chat}}               ; matching → kept
                {:role "assistant" :content [{:type :text :text "a"}] :created-at 999}]
          {:keys [messages dropped-count dropped-apis]}
          (llm-api/sanitize-past-messages-for-api :openai-chat past)]
      (is (= 1 dropped-count))
      (is (= #{:anthropic} dropped-apis))
      (is (= 4 (count messages)))
      (is (= ["user" "reason" "reason" "assistant"] (mapv :role messages)))
      (is (= ["r0" "r2"]
             (->> messages (filter #(= "reason" (:role %))) (map #(get-in % [:content :id])))))
      (is (not (contains? (last messages) :created-at))))))

(deftest default-model-test
  (testing "Custom provider defaultModel present"
    (with-redefs [config/get-env (constantly nil)
                  secrets/credential-file-paths (constantly [])]
      (let [db {:models {"my-provider/my-model" {}}}
            config {:defaultModel "my-provider/my-model"}]
        (is (= "my-provider/my-model" (llm-api/default-model db config))))))

  (testing "Ollama running model present"
    (with-redefs [config/get-env (constantly nil)
                  secrets/credential-file-paths (constantly [])]
      (let [db {:models {"ollama/foo" {:tools true}
                         "gpt-4.1" {:tools true}
                         "other-model" {:tools true}}}
            config {}]
        (is (= "ollama/foo" (llm-api/default-model db config))))))

  (testing "Anthropic API key present in config"
    (with-redefs [config/get-env (constantly nil)
                  secrets/credential-file-paths (constantly [])]
      (let [db {:models {"anthropic/claude-sonnet-4.5" {}}}
            config {:providers {"anthropic" {:key "something"}}}]
        (is (= "anthropic/claude-sonnet-4.5" (llm-api/default-model db config))))))

  (testing "Anthropic API key present in ENV"
    (with-redefs [config/get-env (fn [k] (when (= k "ANTHROPIC_API_KEY") "env-anthropic"))
                  secrets/credential-file-paths (constantly [])]
      (let [db {:models {"anthropic/claude-sonnet-4.5" {}}}
            config {:providers {"anthropic" {:keyEnv "ANTHROPIC_API_KEY"}}}]
        (is (= "anthropic/claude-sonnet-4.5" (llm-api/default-model db config))))))

  (testing "OpenAI API key present in config"
    (with-redefs [config/get-env (constantly nil)
                  secrets/credential-file-paths (constantly [])]
      (let [db {:models {"openai/gpt-5.2" {}}}
            config {:providers {"openai" {:key "yes!"}}}]
        (is (= "openai/gpt-5.2" (llm-api/default-model db config))))))

  (testing "OpenAI API key present in ENV"
    (with-redefs [config/get-env (fn [k] (when (= k "OPENAI_API_KEY") "env-openai"))
                  secrets/credential-file-paths (constantly [])]
      (let [db {:models {"openai/gpt-5.2" {}}}
            config {:providers {"anthropic" {:key nil :keyEnv nil :keyRc nil}
                                "openai" {:keyEnv "OPENAI_API_KEY"}}}]
        (is (= "openai/gpt-5.2" (llm-api/default-model db config))))))

  (testing "Fallback default (no keys anywhere)"
    (with-redefs [config/get-env (constantly nil)
                  secrets/credential-file-paths (constantly [])]
      (let [db {:models {"anthropic/claude-sonnet-4.5" {}
                         "openai/gpt-5.2" {}}}
            config {}]
        (is (= "anthropic/claude-sonnet-4.5" (llm-api/default-model db config))))))

  (testing "Returns nil when no models are available"
    (with-redefs [config/get-env (constantly nil)
                  secrets/credential-file-paths (constantly [])]
      (let [db {:models {}}
            config {}]
        (is (nil? (llm-api/default-model db config))))))

  (testing "Missing configured defaultModel falls back to deterministic first available model"
    (with-redefs [config/get-env (constantly nil)
                  secrets/credential-file-paths (constantly [])]
      (let [db {:models {"z-model" {}
                         "a-model" {}}
                :auth {}}
            config {:defaultModel "missing-model"}]
        (is (= "a-model" (llm-api/default-model db config))))))

  (testing "When key-based default is unavailable, falls back to deterministic first available model"
    (with-redefs [config/get-env (constantly nil)
                  secrets/credential-file-paths (constantly [])]
      (let [db {:models {"openai/gpt-4.1" {}
                         "custom/zeta" {}}
                :auth {}}
            config {:providers {"anthropic" {:key "something"}}}]
        (is (= "custom/zeta" (llm-api/default-model db config)))))))

(deftest prompt-test
  (testing "Custom OpenAI provider behavior and proper passing of httpClient options to the Hato client"
    (let [req* (atom nil)]
      (with-client-proxied {}

        (fn handler [req]
          (reset! req* req)
          {:status 200
           :body {:usage {:prompt_tokens 5 :completion_tokens 2}
                  :choices [{:message {:content "hi"
                                       :reasoning_content "think more"}}]}})

        (let [response (#'eca.llm-api/prompt!
                        {:config {:providers {"lmstudio"
                                              {:api "openai-chat",
                                               :url "http://localhost:1234",
                                               :completionUrlRelativePath "/v1/chat/completions",
                                               :httpClient {:version :http-1.1},
                                               :models {"ibm/granite-4-h-tiny" {}}}}}

                         :provider "lmstudio"
                         :model "ibm/granite-4-h-tiny"

                         :model-capabilities {:tools false,
                                              :reason? false,
                                              :web-search false,
                                              :model-name "ibm/granite-4-h-tiny"}
                         :sync? true})]
          (is (= {:method "POST",
                  :uri "/v1/chat/completions"}
                 (select-keys @req* [:method :uri])))
          ;; Verify that a single Hato HTTP client request occurred and used HTTP/1.1
          (is (= [{:version :http-1.1}] (map #(dissoc % :proxy) @*http-client-captures*)))
          (is (= {:usage {:input-tokens 5, :output-tokens 2, :input-cache-read-tokens nil},
                  :tools-to-call (),
                  :reason-text "think more",
                  :reasoning-content "think more",
                  :output-text "hi"}
                 (select-keys response [:usage :tools-to-call :reason-text :reasoning-content :output-text])) response)))))

  (testing "Custom provider allows dynamically discovered models even when not present in provider :models config"
    (let [req* (atom nil)]
      (with-client-proxied {}

        (fn handler [req]
          (reset! req* req)
          {:status 200
           :body {:usage {:prompt_tokens 5 :completion_tokens 2}
                  :choices [{:message {:content "hi"}}]}})

        (let [response (#'eca.llm-api/prompt!
                        {:config {:providers {"synthetic"
                                              {:api "openai-chat"
                                               :url "http://localhost:1234"
                                               :completionUrlRelativePath "/v1/chat/completions"
                                               :httpClient {:version :http-1.1}
                                               :models {}}}}

                         :provider "synthetic"
                         :model "qwen-3-235b-instruct"

                         :model-capabilities {:tools false
                                              :reason? false
                                              :web-search false
                                              :model-name "hf:Qwen/Qwen3-235B-A22B-Instruct-2507"}
                         :sync? true})]
          (is (= {:method "POST"
                  :uri "/v1/chat/completions"}
                 (select-keys @req* [:method :uri])))
          (is (= "hi" (:output-text response))))))))

(deftest prompt-passes-image-generation-to-openai-handler-test
  (testing "openai branch forwards :image-generation true to create-response! when capability is on"
    (let [captured* (atom nil)]
      (with-redefs [llm-providers.openai/create-response!
                    (fn [opts _callbacks] (reset! captured* opts) :ok)]
        (#'eca.llm-api/prompt!
         {:provider "openai"
          :model "gpt-5.2"
          :model-capabilities {:tools true
                               :reason? false
                               :web-search false
                               :image-generation? true
                               :model-name "gpt-5.2"}
          :user-messages [{:role "user" :content [{:type :text :text "hi"}]}]
          :past-messages []
          :tools []
          :provider-auth {:api-key "test-key"}
          :config {:providers {"openai" {:url "https://api.openai.com" :key "test-key"}}}
          :sync? false}))
      (is (= true (:image-generation @captured*))
          "openai handler should receive :image-generation true")))

  (testing "openai branch strips internal top-level message fields before reaching handler"
    (let [captured* (atom nil)]
      (with-redefs [llm-providers.openai/create-response!
                    (fn [opts _callbacks] (reset! captured* opts) :ok)]
        (#'eca.llm-api/prompt!
         {:provider "openai"
          :model "gpt-5.2"
          :model-capabilities {:tools true
                               :reason? false
                               :web-search false
                               :image-generation? false
                               :model-name "gpt-5.2"}
          :user-messages [{:role "user"
                           :content [{:type :text :text "hi"}]
                           :created-at 111
                           :content-id "user-1"}]
          :past-messages [{:role "assistant"
                           :content [{:type :text :text "ok"}]
                           :created-at 222
                           :content-id "past-1"}]
          :tools []
          :provider-auth {:api-key "test-key"}
          :config {:providers {"openai" {:url "https://api.openai.com" :key "test-key"}}}
          :sync? false}))
      (is (= [{:role "user" :content [{:type :text :text "hi"}]}]
             (:user-messages @captured*)))
      (is (= [{:role "assistant" :content [{:type :text :text "ok"}]}]
             (:past-messages @captured*)))))

  (testing "openai tool-call replay strips internal top-level message fields before follow-up request"
    (let [seen-bodies* (atom [])]
      (with-redefs [eca.llm-providers.openai/normalize-messages
                    (fn [messages _supports-image?] (vec messages))
                    eca.llm-providers.openai/base-responses-request!
                    (fn [{:keys [body on-stream]}]
                      (swap! seen-bodies* conj body)
                      (if (= 1 (count @seen-bodies*))
                        (on-stream "response.completed"
                                   {:response {:status "completed"
                                               :usage {:input_tokens 1 :output_tokens 1}
                                               :output [{:type "function_call"
                                                         :id "item_1"
                                                         :call_id "call_1"
                                                         :name "demo/tool"
                                                         :arguments "{}"}]}})
                        (on-stream "response.completed"
                                   {:response {:status "completed"
                                               :usage {:input_tokens 1 :output_tokens 1}
                                               :output []}}))
                      :ok)]
        (llm-providers.openai/create-response!
         {:model "gpt-5.2"
          :instructions nil
          :reason? false
          :supports-image? false
          :api-key "test-key"
          :api-url "https://api.openai.com"
          :past-messages []
          :user-messages [{:role "user" :content [{:type :text :text "hi"}]}]
          :tools [{:full-name "demo/tool" :description "d" :parameters {}}]
          :web-search false
          :image-generation false}
         {:on-message-received identity
          :on-error identity
          :on-prepare-tool-call identity
          :on-reason identity
          :on-usage-updated identity
          :on-server-web-search identity
          :on-server-image-generation identity
          :on-tools-called (fn [_]
                             {:new-messages [{:role "assistant"
                                              :content [{:type :text :text "after tool"}]
                                              :created-at 333
                                              :content-id "replay-1"}]
                              :tools []})}))
      (is (= 2 (count @seen-bodies*)))
      (is (= [{:role "assistant"
               :content [{:type :text :text "after tool"}]}]
             (:input (second @seen-bodies*))))))

  (testing "openai branch forwards :image-generation false (or nil) when capability is off"
    (let [captured* (atom nil)]
      (with-redefs [llm-providers.openai/create-response!
                    (fn [opts _callbacks] (reset! captured* opts) :ok)]
        (#'eca.llm-api/prompt!
         {:provider "openai"
          :model "gpt-4-legacy"
          :model-capabilities {:tools true
                               :reason? false
                               :web-search false
                               :image-generation? false
                               :model-name "gpt-4-legacy"}
          :user-messages [{:role "user" :content [{:type :text :text "hi"}]}]
          :past-messages []
          :tools []
          :provider-auth {:api-key "test-key"}
          :config {:providers {"openai" {:url "https://api.openai.com" :key "test-key"}}}
          :sync? false}))
      (is (not (true? (:image-generation @captured*)))
          "openai handler should NOT receive :image-generation true when capability is off"))))

(deftest prompt-forwards-stream-idle-timeout-and-cache-retention-to-anthropic-handler-test
  (testing "custom provider with :api anthropic forwards :stream-idle-timeout-seconds and :cache-retention to chat!"
    (let [captured* (atom nil)]
      (with-redefs [llm-providers.anthropic/chat!
                    (fn [opts _callbacks] (reset! captured* opts) :ok)]
        (#'eca.llm-api/prompt!
         {:provider "my-proxy"
          :model "claude-sonnet-4-6"
          :model-capabilities {:tools true
                               :reason? false
                               :web-search false
                               :model-name "claude-sonnet-4-6"}
          :user-messages [{:role "user" :content [{:type :text :text "hi"}]}]
          :past-messages []
          :tools []
          :provider-auth {:api-key "test-key"}
          :config {:streamIdleTimeoutSeconds 300
                   :providers {"my-proxy" {:api "anthropic"
                                           :url "https://my-proxy.example.com/v1"
                                           :key "test-key"
                                           :cacheRetention "long"
                                           :models {"claude-sonnet-4-6" {}}}}}
          :sync? false}))
      (is (= "long" (:cache-retention @captured*))
          "anthropic handler should receive :cache-retention from provider-config")
      (is (= 300 (:stream-idle-timeout-seconds @captured*))
          "anthropic handler should receive :stream-idle-timeout-seconds from top-level config"))))

(deftest retry-delay-ms-test
  ;; Formula: (quot capped 2) + rand(0, capped)
  ;; Range: [capped/2, capped/2 + capped) = [capped/2, capped*3/2)
  (testing "exponential backoff with jitter stays within bounds"
    (dotimes [_ 50]
      (let [d0 (#'llm-api/retry-delay-ms 0)]
        (is (<= 1000 d0 3000) "attempt 0: base 2s")))
    (dotimes [_ 50]
      (let [d1 (#'llm-api/retry-delay-ms 1)]
        (is (<= 2000 d1 6000) "attempt 1: base 4s")))
    (dotimes [_ 50]
      (let [d2 (#'llm-api/retry-delay-ms 2)]
        (is (<= 4000 d2 12000) "attempt 2: base 8s"))))

  (testing "capped at max-delay-ms for high attempts"
    (dotimes [_ 50]
      (let [d9 (#'llm-api/retry-delay-ms 9)]
        (is (<= 30000 d9 90000) "attempt 9: capped at 60s base")))))

(deftest sleep-with-cancel-test
  (testing "completes when not cancelled"
    (is (true? (#'llm-api/sleep-with-cancel 50 (constantly false)))))

  (testing "returns false when already cancelled"
    (is (false? (#'llm-api/sleep-with-cancel 1000 (constantly true)))))

  (testing "returns false when cancelled during sleep"
    (let [cancelled* (atom false)
          result (future (#'llm-api/sleep-with-cancel 5000 #(deref cancelled*)))]
      (Thread/sleep 200)
      (reset! cancelled* true)
      (is (false? (deref result 2000 :timeout))))))

(defn- make-prompt-opts
  "Creates minimal sync-or-async-prompt! opts for testing retry behavior.
   Pass :stream false in overrides for sync mode, defaults to async (stream true)."
  [overrides]
  (let [stream (get overrides :stream true)]
    (merge {:provider "anthropic"
            :model "claude-sonnet-4-6"
            :model-capabilities {:tools false :reason? false :web-search false}
            :instructions "test"
            :user-messages [{:role "user" :content [{:type :text :text "hello"}]}]
            :past-messages []
            :tools []
            :config {:providers {"anthropic" {:key "test-key"
                                              :url "http://test"
                                              :models {"claude-sonnet-4-6" {:extraPayload {:stream stream}}}}}}
            :provider-auth {:api-key "test-key"}}
           (dissoc overrides :stream))))

(deftest refresh-provider-auth-fn-used-for-initial-and-retry-test
  (testing "refresh-provider-auth-fn supplies a fresh provider-auth on each prompt! call"
    ;; Previously provider-auth was captured once and reused across retries,
    ;; so expired tokens caused all retries to fail.
    (let [refresh-calls* (atom 0)
          seen-api-keys* (atom [])]
      (with-redefs [eca.llm-api/prompt! (fn [{:keys [provider-auth]}]
                                          (swap! seen-api-keys* conj (:api-key provider-auth))
                                          (let [attempt (count @seen-api-keys*)]
                                            (if (= 1 attempt)
                                              {:error {:status 429
                                                       :body "Rate limit exceeded"
                                                       :message "LLM response status: 429"}}
                                              {:output-text "success"
                                               :usage {:input-tokens 1 :output-tokens 1}})))
                    eca.llm-api/sleep-with-cancel (fn [_ cancelled?] (not (cancelled?)))]
        (llm-api/sync-or-async-prompt!
         (make-prompt-opts
          {:stream false
           :provider-auth {:api-key "stale-token"}
           :refresh-provider-auth-fn (fn []
                                       (let [n (swap! refresh-calls* inc)]
                                         {:api-key (str "fresh-token-" n)}))
           :on-error identity
           :on-message-received identity})))
      (is (= 2 @refresh-calls*)
          "refresh-provider-auth-fn is called once per prompt! attempt (initial + retry)")
      (is (= ["fresh-token-1" "fresh-token-2"] @seen-api-keys*)
          "each prompt! invocation (including the retry) must see a freshly read api-key")))

  (testing "falls back to captured provider-auth when refresh-provider-auth-fn throws"
    (let [seen-api-keys* (atom [])]
      (with-redefs [eca.llm-api/prompt! (fn [{:keys [provider-auth]}]
                                          (swap! seen-api-keys* conj (:api-key provider-auth))
                                          {:output-text "ok"
                                           :usage {:input-tokens 1 :output-tokens 1}})
                    eca.llm-api/sleep-with-cancel (fn [_ _] true)]
        (llm-api/sync-or-async-prompt!
         (make-prompt-opts
          {:stream false
           :provider-auth {:api-key "fallback-token"}
           :refresh-provider-auth-fn (fn [] (throw (ex-info "boom" {})))
           :on-error identity
           :on-message-received identity})))
      (is (= ["fallback-token"] @seen-api-keys*)
          "when refresh-provider-auth-fn throws, prompt! keeps running with the statically captured provider-auth"))))

(deftest sync-retry-on-rate-limited-test
  (testing "retries on 429 and succeeds on subsequent attempt"
    (let [attempt* (atom 0)
          retry-events* (atom [])
          on-error-called* (atom false)]
      (with-redefs [eca.llm-api/prompt! (fn [_opts]
                                          (let [attempt (swap! attempt* inc)]
                                            (if (= 1 attempt)
                                              {:error {:status 429
                                                       :body "Rate limit exceeded"
                                                       :message "LLM response status: 429"}}
                                              {:output-text "success"
                                               :usage {:input-tokens 10 :output-tokens 5}})))
                    eca.llm-api/sleep-with-cancel (fn [_ cancelled?] (not (cancelled?)))]
        (llm-api/sync-or-async-prompt!
         (make-prompt-opts
          {:stream false
           :on-retry (fn [event] (swap! retry-events* conj event))
           :on-error (fn [_] (reset! on-error-called* true))
           :on-message-received identity})))
      (is (= 2 @attempt*))
      (is (= 1 (count @retry-events*)))
      (is (= 1 (:attempt (first @retry-events*))))
      (is (false? @on-error-called*)))))

(deftest sync-no-retry-on-auth-error-test
  (testing "does not retry on auth errors (401)"
    (let [attempt* (atom 0)
          on-error-called* (atom false)]
      (with-redefs [eca.llm-api/prompt! (fn [_]
                                          (swap! attempt* inc)
                                          {:error {:status 401
                                                   :body "Unauthorized"
                                                   :message "LLM response status: 401"}})
                    eca.llm-api/sleep-with-cancel (fn [_ _] true)]
        (llm-api/sync-or-async-prompt!
         (make-prompt-opts
          {:stream false
           :on-error (fn [_] (reset! on-error-called* true))
           :on-message-received identity})))
      (is (= 1 @attempt*))
      (is (true? @on-error-called*)))))

(deftest async-retry-on-custom-retry-rule-error-pattern-test
  (testing "retries async when custom retryRules errorPattern matches error message"
    (let [attempt* (atom 0)
          retry-events* (atom [])
          received-text* (atom "")
          on-error-called* (atom false)]
      (with-redefs [eca.llm-api/prompt! (fn [{:keys [on-message-received on-error]}]
                                          (let [attempt (swap! attempt* inc)]
                                            (if (= 1 attempt)
                                              (on-error {:message "Remote host terminated the handshake"})
                                              (do
                                                (on-message-received {:type :text :text "hello"})
                                                (on-message-received {:type :finish :finish-reason "stop"})))))
                    eca.llm-api/sleep-with-cancel (fn [_ cancelled?] (not (cancelled?)))]
        (llm-api/sync-or-async-prompt!
         (make-prompt-opts
          {:config {:providers {"anthropic" {:key "test-key"
                                             :url "http://test"
                                             :retryRules [{:errorPattern "terminated.*handshake"
                                                           :label "TLS handshake failed"}]
                                             :models {"claude-sonnet-4-6" {}}}}}
           :on-retry (fn [event] (swap! retry-events* conj event))
           :on-error (fn [_] (reset! on-error-called* true))
           :on-message-received (fn [{:keys [type text]}]
                                  (when (= :text type)
                                    (swap! received-text* str text)))})))
      (is (= 2 @attempt*))
      (is (= 1 (count @retry-events*)))
      (is (= "TLS handshake failed" (get-in (first @retry-events*) [:classified :error/label])))
      (is (false? @on-error-called*))
      (is (= "hello" @received-text*)))))

(deftest sync-retry-exhaustion-test
  (testing "calls on-error after all retries exhausted"
    (let [attempt* (atom 0)
          on-error-called* (atom false)]
      (with-redefs [eca.llm-api/prompt! (fn [_]
                                          (swap! attempt* inc)
                                          {:error {:status 429
                                                   :body "Rate limit exceeded"
                                                   :message "LLM response status: 429"}})
                    eca.llm-api/default-max-retries 3
                    eca.llm-api/sleep-with-cancel (fn [_ _] true)]
        (llm-api/sync-or-async-prompt!
         (make-prompt-opts
          {:stream false
           :on-error (fn [_] (reset! on-error-called* true))
           :on-message-received identity})))
      (is (= 4 @attempt*) "1 initial + 3 retries")
      (is (true? @on-error-called*)))))

(deftest sync-retry-cancelled-test
  (testing "stops retrying when cancelled"
    (let [attempt* (atom 0)
          on-error-called* (atom false)
          cancelled* (atom false)]
      (with-redefs [eca.llm-api/prompt! (fn [_]
                                          (let [attempt (swap! attempt* inc)]
                                            (when (= 2 attempt)
                                              (reset! cancelled* true))
                                            {:error {:status 429
                                                     :body "Rate limit exceeded"
                                                     :message "LLM response status: 429"}}))
                    eca.llm-api/sleep-with-cancel (fn [_ cancelled?] (not (cancelled?)))]
        (llm-api/sync-or-async-prompt!
         (make-prompt-opts
          {:stream false
           :cancelled? #(deref cancelled*)
           :on-error (fn [_] (reset! on-error-called* true))
           :on-message-received identity})))
      (is (<= @attempt* 3) "should stop after cancellation")
      (is (true? @on-error-called*)))))

(deftest async-retry-on-overloaded-test
  (testing "retries async streaming on 503 overloaded and succeeds"
    (let [attempt* (atom 0)
          retry-events* (atom [])
          received-text* (atom "")
          on-error-called* (atom false)]
      (with-redefs [eca.llm-api/prompt! (fn [{:keys [on-message-received on-error]}]
                                          (let [attempt (swap! attempt* inc)]
                                            (if (= 1 attempt)
                                              (on-error {:status 503
                                                         :body "Service temporarily unavailable"
                                                         :message "LLM response status: 503"})
                                              (do
                                                (on-message-received {:type :text :text "hello"})
                                                (on-message-received {:type :finish :finish-reason "stop"})))))
                    eca.llm-api/sleep-with-cancel (fn [_ cancelled?] (not (cancelled?)))]
        (llm-api/sync-or-async-prompt!
         (make-prompt-opts
          {:on-retry (fn [event] (swap! retry-events* conj event))
           :on-error (fn [_] (reset! on-error-called* true))
           :on-message-received (fn [{:keys [type text]}]
                                  (when (= :text type)
                                    (swap! received-text* str text)))})))
      (is (= 2 @attempt*))
      (is (= 1 (count @retry-events*)))
      (is (false? @on-error-called*))
      (is (= "hello" @received-text*)))))

(deftest async-no-retry-on-context-overflow-test
  (testing "does not retry on context overflow"
    (let [attempt* (atom 0)
          on-error-called* (atom false)]
      (with-redefs [eca.llm-api/prompt! (fn [{:keys [on-error]}]
                                          (swap! attempt* inc)
                                          (on-error {:status 400
                                                     :body "prompt is too long: 273112 tokens > 200000 maximum"
                                                     :message "LLM response status: 400"}))
                    eca.llm-api/sleep-with-cancel (fn [_ _] true)]
        (llm-api/sync-or-async-prompt!
         (make-prompt-opts
          {:on-error (fn [_] (reset! on-error-called* true))
           :on-message-received identity})))
      (is (= 1 @attempt*))
      (is (true? @on-error-called*)))))

(deftest sync-retry-on-custom-retry-rule-test
  (testing "retries when custom retryRules status matches"
    (let [attempt* (atom 0)
          retry-events* (atom [])
          on-error-called* (atom false)]
      (with-redefs [eca.llm-api/prompt! (fn [_opts]
                                          (let [attempt (swap! attempt* inc)]
                                            (if (= 1 attempt)
                                              {:error {:status 418
                                                       :body "I'm a teapot"
                                                       :message "LLM response status: 418"}}
                                              {:output-text "success"
                                               :usage {:input-tokens 10 :output-tokens 5}})))
                    eca.llm-api/sleep-with-cancel (fn [_ cancelled?] (not (cancelled?)))]
        (llm-api/sync-or-async-prompt!
         (make-prompt-opts
          {:stream false
           :config {:providers {"anthropic" {:key "test-key"
                                             :url "http://test"
                                             :retryRules [{:status 418 :label "Proxy throttle"}]
                                             :models {"claude-sonnet-4-6" {:extraPayload {:stream false}}}}}}
           :on-retry (fn [event] (swap! retry-events* conj event))
           :on-error (fn [_] (reset! on-error-called* true))
           :on-message-received identity})))
      (is (= 2 @attempt*))
      (is (= 1 (count @retry-events*)))
      (is (= :retryable-custom (get-in (first @retry-events*) [:classified :error/type])))
      (is (= "Proxy throttle" (get-in (first @retry-events*) [:classified :error/label])))
      (is (false? @on-error-called*)))))

(deftest async-retry-on-custom-retry-rule-error-pattern-body-test
  (testing "retries async when custom retryRules errorPattern matches response body"
    (let [attempt* (atom 0)
          retry-events* (atom [])
          received-text* (atom "")
          on-error-called* (atom false)]
      (with-redefs [eca.llm-api/prompt! (fn [{:keys [on-message-received on-error]}]
                                          (let [attempt (swap! attempt* inc)]
                                            (if (= 1 attempt)
                                              (on-error {:status 500
                                                         :body "server capacity exceeded"
                                                         :message "LLM response status: 500"})
                                              (do
                                                (on-message-received {:type :text :text "hello"})
                                                (on-message-received {:type :finish :finish-reason "stop"})))))
                    eca.llm-api/sleep-with-cancel (fn [_ cancelled?] (not (cancelled?)))]
        (llm-api/sync-or-async-prompt!
         (make-prompt-opts
          {:config {:providers {"anthropic" {:key "test-key"
                                             :url "http://test"
                                             :retryRules [{:errorPattern "capacity.*exceeded"
                                                           :label "Capacity exceeded"}]
                                             :models {"claude-sonnet-4-6" {}}}}}
           :on-retry (fn [event] (swap! retry-events* conj event))
           :on-error (fn [_] (reset! on-error-called* true))
           :on-message-received (fn [{:keys [type text]}]
                                  (when (= :text type)
                                    (swap! received-text* str text)))})))
      (is (= 2 @attempt*))
      (is (= 1 (count @retry-events*)))
      (is (= "Capacity exceeded" (get-in (first @retry-events*) [:classified :error/label])))
      (is (false? @on-error-called*))
      (is (= "hello" @received-text*))))

  (testing "does not retry when no custom rule matches"
    (let [attempt* (atom 0)
          on-error-called* (atom false)]
      (with-redefs [eca.llm-api/prompt! (fn [{:keys [on-error]}]
                                          (swap! attempt* inc)
                                          (on-error {:status 418
                                                     :body "I'm a teapot"
                                                     :message "LLM response status: 418"}))
                    eca.llm-api/sleep-with-cancel (fn [_ _] true)]
        (llm-api/sync-or-async-prompt!
         (make-prompt-opts
          {:config {:providers {"anthropic" {:key "test-key"
                                             :url "http://test"
                                             :retryRules [{:status 599 :label "Something else"}]
                                             :models {"claude-sonnet-4-6" {}}}}}
           :on-error (fn [_] (reset! on-error-called* true))
           :on-message-received identity})))
      (is (= 1 @attempt*))
      (is (true? @on-error-called*)))))
