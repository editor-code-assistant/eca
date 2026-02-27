(ns eca.llm-api-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.client-test-helpers :refer [with-client-proxied *http-client-captures*]]
   [eca.config :as config]
   [eca.llm-api :as llm-api]
   [eca.secrets :as secrets]
   [eca.test-helper :as h]))

(h/reset-components-before-test)

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
