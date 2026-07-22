(ns eca.models-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [hato.client :as http]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.models :as models]))

(set! *warn-on-reflection* true)

(defn ^:private build-supported-models
  [config db models-dev-data]
  (let [known-models (#'models/all models-dev-data)
        {:keys [models]} (#'models/fetch-provider-model-catalogs config db models-dev-data)]
    (#'models/build-all-supported-models known-models config models)))

(deftest fetch-models-dev-data-test
  (testing "Uses hato with json-string-keys and global client options"
    (let [request* (atom nil)]
      (with-redefs [http/get (fn [url opts]
                               (reset! request* [url opts])
                               {:status 200
                                :body {"openai" {"api" "https://api.openai.com"}}})]
        (is (match?
             {"openai" {"api" "https://api.openai.com"}}
             (#'models/fetch-models-dev-data)))
        (is (= "https://models.dev/api.json" (first @request*)))
        (is (= 5000 (get-in @request* [1 :timeout])))
        (is (= :json-string-keys (get-in @request* [1 :as])))
        (is (false? (get-in @request* [1 :throw-exceptions?]))))))

  (testing "Throws on non-200 status"
    (with-redefs [http/get (fn [_url _opts]
                             {:status 503
                              :body {"error" "temporary-failure"}})]
      (try
        (#'models/fetch-models-dev-data)
        (is false "Expected ExceptionInfo on non-200 status")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"status 503" (ex-message e)))))))

  (testing "Includes the response body in the error message"
    (with-redefs [http/get (fn [_url _opts]
                             {:status 500
                              :body "upstream exploded"})]
      (try
        (#'models/fetch-models-dev-data)
        (is false "Expected ExceptionInfo on non-200 status")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"upstream exploded" (ex-message e))))))))

(deftest truncate-for-log-test
  (testing "Returns short strings unchanged"
    (is (= "hello" (#'models/truncate-for-log "hello"))))

  (testing "Stringifies non-strings"
    (is (= "{:a 1}" (#'models/truncate-for-log {:a 1}))))

  (testing "Returns empty string for nil"
    (is (= "" (#'models/truncate-for-log nil))))

  (testing "Truncates long strings"
    (let [out (#'models/truncate-for-log (apply str (repeat 1000 "x")))]
      (is (< (count out) 1000))
      (is (re-find #"truncated" out)))))

(deftest provider-configured?-test
  (testing "Provider requiring auth without resolvable credentials is not configured"
    (is (false? (#'models/provider-configured?
                 "synthetic"
                 {:api "openai-chat" :requiresAuth? true}
                 {}
                 {:providers {"synthetic" {:url "https://api.synthetic.new/v1"}}}))))

  (testing "Provider requiring auth with a config key is configured"
    (is (true? (#'models/provider-configured?
                "synthetic"
                {:api "openai-chat" :requiresAuth? true}
                {}
                {:providers {"synthetic" {:url "https://api.synthetic.new/v1"
                                          :key "sk-test"}}}))))

  (testing "Provider requiring auth with login auth is configured"
    (is (true? (#'models/provider-configured?
                "synthetic"
                {:api "openai-chat" :requiresAuth? true}
                {:auth {"synthetic" {:api-key "tok" :type :auth/oauth}}}
                {:providers {"synthetic" {:url "https://api.synthetic.new/v1"}}}))))

  (testing "Provider that does not require auth is always configured"
    (is (true? (#'models/provider-configured?
                "synthetic"
                {:api "openai-chat"}
                {}
                {:providers {"synthetic" {:url "https://api.synthetic.new/v1"}}})))))

(deftest fetch-provider-models-with-priority-skips-unconfigured-test
  (let [models-dev-data {"synthetic" {"api" "https://api.synthetic.new/v1"
                                      "models" {"qwen" {"id" "qwen"}}}}]
    (testing "Does not hit the network for a provider that requires auth but has no credentials"
      (let [native-calls* (atom 0)]
        (with-redefs [http/get (fn [_url _opts]
                                 (swap! native-calls* inc)
                                 {:status 200 :body {:data [{:id "native-1"}]}})]
          (is (match?
               {}
               (#'models/fetch-provider-models-with-priority
                {:providers {"synthetic" {:api "openai-chat"
                                          :url "https://api.synthetic.new/v1"
                                          :requiresAuth? true}}}
                {}
                models-dev-data)))
          (is (= 0 @native-calls*)))))

    (testing "Still fetches a provider that has credentials"
      (let [native-calls* (atom 0)]
        (with-redefs [http/get (fn [_url _opts]
                                 (swap! native-calls* inc)
                                 {:status 200 :body {:data [{:id "native-1"}]}})]
          (is (match?
               {"synthetic" {"native-1" {}}}
               (#'models/fetch-provider-models-with-priority
                {:providers {"synthetic" {:api "openai-chat"
                                          :url "https://api.synthetic.new/v1"
                                          :key "sk-test"
                                          :requiresAuth? true}}}
                {}
                models-dev-data)))
          (is (= 1 @native-calls*)))))))

(deftest fetch-provider-native-models-logs-body-on-error-test
  (testing "Logs the response body when the /models endpoint returns a non-200"
    (let [warnings* (atom [])]
      (with-redefs [http/get (fn [_url _opts]
                               {:status 400
                                :body "{\"error\":\"model_not_supported\"}"})
                    logger/warn (fn [& args] (swap! warnings* conj (apply str args)) nil)]
        (is (nil? (#'models/fetch-provider-native-models
                   {:provider "github-copilot"
                    :api-url "https://api.githubcopilot.com"
                    :auth-type :auth/oauth
                    :api-key "tok"
                    :api-type "openai-chat"})))
        (is (some #(re-find #"status 400" %) @warnings*))
        (is (some #(re-find #"model_not_supported" %) @warnings*))))))

(deftest fetch-provider-native-copilot-models-metadata-test
  (with-redefs [http/get (fn [_url _opts]
                           {:status 200
                            :body {:data [{:id "gpt-responses"
                                           :supported_endpoints ["/responses" "/chat/completions"]
                                           :capabilities {:supports {:reasoning_effort ["low" "max"]}}}
                                          {:id "gpt-chat"
                                           :supported_endpoints ["/chat/completions"]
                                           :capabilities {:supports {:reasoning_effort ["low" "high"]}}}
                                          {:id "claude-adaptive"
                                           :supported_endpoints ["/chat/completions" "/responses" "/v1/messages"]
                                           :capabilities {:supports {:adaptive_thinking true
                                                                     :reasoning_effort ["low" "high"]}}}
                                          {:id "claude-opus-4-7"
                                           :supported_endpoints ["/v1/messages"]
                                           :capabilities {:supports {:adaptive_thinking true
                                                                     :reasoning_effort ["high"]}}}
                                          {:id "claude-adaptive-budget"
                                           :supported_endpoints ["/v1/messages"]
                                           :capabilities {:supports {:adaptive_thinking true
                                                                     :max_thinking_budget 10000}}}
                                          {:id "claude-budget"
                                           :supported_endpoints ["/v1/messages"]
                                           :capabilities {:supports {:max_thinking_budget 10000}}}
                                          {:id "claude-small-budget"
                                           :supported_endpoints ["/v1/messages"]
                                           :capabilities {:supports {:min_thinking_budget 1400
                                                                     :max_thinking_budget 1500}}}
                                          {:id "claude-invalid-budget"
                                           :supported_endpoints ["/v1/messages"]
                                           :capabilities {:supports {:max_thinking_budget 1000}}}]}})]
    (is (match?
         {"gpt-responses" {:discovered-api :openai-responses
                            :discovered-reason? true
                            :discovered-variants
                            {"low" {:reasoning {:effort "low" :summary "auto"}}
                             "max" {:reasoning {:effort "max" :summary "auto"}}}}
          "gpt-chat" {:discovered-api :openai-chat
                      :discovered-reason? true
                      :discovered-variants
                      {"low" {:reasoning_effort "low"}
                       "high" {:reasoning_effort "high"}}}
          "claude-adaptive" {:discovered-api :anthropic
                             :discovered-reason? true
                             :discovered-variants
                             {"default" {:thinking {:type "adaptive"}}
                              "low" {:thinking {:type "adaptive"}
                                     :output_config {:effort "low"}}
                              "high" {:thinking {:type "adaptive"}
                                      :output_config {:effort "high"}}}}
          "claude-opus-4-7" {:discovered-api :anthropic
                              :discovered-reason? true
                              :discovered-variants
                              {"default" {:thinking {:type "adaptive" :display "summarized"}}
                               "high" {:thinking {:type "adaptive" :display "summarized"}
                                       :output_config {:effort "high"}}}}
          "claude-adaptive-budget" {:discovered-api :anthropic
                                    :discovered-reason? true
                                    :discovered-variants
                                    (m/equals {"default" {:thinking {:type "adaptive"}}})}
          "claude-budget" {:discovered-api :anthropic
                           :discovered-reason? true
                           :discovered-variants
                           {"high" {:thinking {:type "enabled" :budget_tokens 5000}}
                            "max" {:thinking {:type "enabled" :budget_tokens 9999}}}}
          "claude-small-budget" {:discovered-api :anthropic
                                 :discovered-reason? true
                                 :discovered-variants
                                 {"high" {:thinking {:type "enabled" :budget_tokens 1400}}
                                  "max" {:thinking {:type "enabled" :budget_tokens 1499}}}}
          "claude-invalid-budget" {:discovered-api :anthropic
                                   :discovered-reason? true}}
         (#'models/fetch-provider-native-models
          {:provider "github-copilot"
           :api-url "https://api.githubcopilot.com"
           :auth-type :auth/oauth
           :api-key "tok"
           :api-type "openai-chat"})))))

(deftest fetch-provider-native-openrouter-models-limits-test
  (testing "OpenRouter-shaped entries keep context_length and top_provider max_completion_tokens as discovered limits"
    (with-redefs [http/get (fn [_url _opts]
                             {:status 200
                              :body {:data [{:id "x-ai/grok-4.5"
                                             :context_length 500000
                                             :top_provider {:max_completion_tokens nil}}
                                            {:id "qwen/qwen3-coder"
                                             :context_length 262144
                                             :top_provider {:max_completion_tokens 32768}}
                                            {:id "weird/model"
                                             :context_length 100000
                                             :top_provider {:max_completion_tokens 100000}}
                                            {:id "plain-model"}]}})]
      (is (match?
           (m/equals
            {"x-ai/grok-4.5" (m/equals {:discovered-limit {:context 500000}})
             "qwen/qwen3-coder" (m/equals {:discovered-limit {:context 262144 :output 32768}})
             ;; max_completion_tokens >= context_length is as impossible as in catalogs
             "weird/model" (m/equals {:discovered-limit {:context 100000}})
             "plain-model" (m/equals {})})
           (#'models/fetch-provider-native-models
            {:provider "openrouter"
             :api-url "https://openrouter.ai/api/v1"
             :auth-type nil
             :api-key "k"
             :api-type "openai-chat"}))))))

(deftest merge-provider-models-test
  (testing "Static models override dynamic ones"
    (let [dynamic {"gpt-4" {}
                   "gpt-3.5" {}}
          static {"gpt-4" {:extraPayload {:temp 0.5}}}]
      (is (match?
           {"gpt-4" {:extraPayload {:temp 0.5}}
            "gpt-3.5" {}}
           (#'models/merge-provider-models static dynamic)))))

  (testing "Works with empty static models"
    (let [dynamic {"gpt-4" {} "gpt-3.5" {}}]
      (is (match?
           {"gpt-4" {} "gpt-3.5" {}}
           (#'models/merge-provider-models {} dynamic)))))

  (testing "Works with empty dynamic models"
    (let [static {"gpt-4" {:extraPayload {:temp 0.5}}}]
      (is (match?
           {"gpt-4" {:extraPayload {:temp 0.5}}}
           (#'models/merge-provider-models static {})))))

  (testing "Static model config extends dynamic defaults for the same model"
    (let [dynamic {"claude-sonnet-4.6" {:modelName "claude-sonnet-4-6"}}
          static {"claude-sonnet-4.6" {:extraPayload {:temperature 0.2}}}]
      (is (match?
           {"claude-sonnet-4.6" {:modelName "claude-sonnet-4-6"
                                 :extraPayload {:temperature 0.2}}}
           (#'models/merge-provider-models static dynamic)))))

  (testing "Static config can add model variants on top of dynamic models"
    (let [dynamic {"gpt-5.2" {}}
          static {"gpt-5.2-high" {:modelName "gpt-5.2"
                                  :extraPayload {:reasoning {:effort "high"}}}}]
      (is (match?
           {"gpt-5.2" {}
            "gpt-5.2-high" {:modelName "gpt-5.2"
                            :extraPayload {:reasoning {:effort "high"}}}}
           (#'models/merge-provider-models static dynamic)))))

  (testing "Static config key matches models.dev id"
    (let [dynamic (#'models/parse-models-dev-provider-models
                   "google"
                   {"gemini-2.5-pro" {"name" "Gemini 2.5 Pro"
                                      "id" "gemini-2.5-pro"}})
          static {"gemini-2.5-pro" {:extraPayload {:reasoning {:effort "high"}}}}]
      (is (match?
           {"gemini-2.5-pro" {:extraPayload {:reasoning {:effort "high"}}}}
           (#'models/merge-provider-models static dynamic))))))

(deftest provider-with-models-dev-models?-test
  (testing "Returns true when provider URL matches models.dev api and fetchModels is not false"
    (is (true? (#'models/add-models-from-models-dev?
                "synthetic"
                {:api "openai-chat"}
                {:providers {"synthetic" {:url "https://api.synthetic.new/v1"}}}
                {:by-id {}
                 :by-url {"https://api.synthetic.new/v1" {"api" "https://api.synthetic.new/v1"}}}))))

  (testing "Returns false when fetchModels is explicitly false"
    (is (false? (#'models/add-models-from-models-dev?
                 "synthetic"
                 {:api "openai-chat" :fetchModels false}
                 {:providers {"synthetic" {:url "https://api.synthetic.new/v1"}}}
                 {:by-id {}
                  :by-url {"https://api.synthetic.new/v1" {"api" "https://api.synthetic.new/v1"}}}))))

  (testing "Returns false when provider does not declare API type"
    (is (false? (#'models/add-models-from-models-dev?
                 "synthetic"
                 {}
                 {:providers {"synthetic" {:url "https://api.synthetic.new/v1"}}}
                 {:by-id {}
                  :by-url {"https://api.synthetic.new/v1" {"api" "https://api.synthetic.new/v1"}}}))))

  (testing "Returns false when provider URL does not match any models.dev api"
    (is (false? (#'models/add-models-from-models-dev?
                 "synthetic"
                 {:api "openai-chat"}
                 {:providers {"synthetic" {:url "https://api.other.example/v1"}}}
                 {:by-id {}
                  :by-url {"https://api.synthetic.new/v1" {"api" "https://api.synthetic.new/v1"}}}))))

  (testing "Matches even when provider URL contains whitespace and trailing slash"
    (is (true? (#'models/add-models-from-models-dev?
                "synthetic"
                {:api "openai-chat"}
                {:providers {"synthetic" {:url "  https://api.synthetic.new/v1/  "}}}
                {:by-id {}
                 :by-url {"https://api.synthetic.new/v1" {"api" "https://api.synthetic.new/v1"}}}))))

  (testing "Falls back to provider id when models.dev provider has no api url"
    (is (true? (#'models/add-models-from-models-dev?
                "anthropic"
                {:api "anthropic"}
                {:providers {"anthropic" {:url "https://api.anthropic.com"}}}
                {:by-id {"anthropic" {"models" {"claude-sonnet-4-6"
                                                {"id" "claude-sonnet-4-6"
                                                 "name" "Claude Sonnet 4.6"}}}}
                 :by-url {}}))))

  (testing "Does not fallback by provider id when models.dev provider has api url"
    (is (false? (#'models/add-models-from-models-dev?
                 "my-provider"
                 {:api "openai-chat"}
                 {:providers {"my-provider" {:url "https://api.not-matching.test/v1"}}}
                 {:by-id {"my-provider" {"api" "https://api.my-provider.dev/v1"
                                         "models" {"foo" {"id" "foo"}}}}
                  :by-url {}})))))

(deftest parse-models-dev-provider-models-test
  (testing "Uses key as model key"
    (is (match?
         {"claude-sonnet-4-6" {}
          "gemini-3-flash-preview" {}
          "gpt-5.2" {}
          "gemini-2.5-pro" {}}
         (#'models/parse-models-dev-provider-models
          "test-provider"
          {"claude-sonnet-4-6" {"name" "Claude Sonnet 4.6"
                                "id" "claude-sonnet-4-6"}
           "gemini-3-flash-preview" {"id" "gemini-3-flash-preview"
                                     "name" "Gemini 3 Flash"}
           "gpt-5.2" {"name" "GPT 5.2"
                      "id" "gpt-5.2"}
           "gemini-2.5-pro" {"name" "Gemini 2.5 Pro"
                             "id" "gemini-2.5-pro"}}))))

  (testing "Skips models marked as deprecated"
    (is (match?
         {"gpt-5.2" {}}
         (#'models/parse-models-dev-provider-models
          "test-provider"
          {"gpt-5.2" {"id" "gpt-5.2"}
           "gpt-4-legacy" {"id" "gpt-4-legacy"
                           "status" "deprecated"}}))))

  (testing "Returns nil for empty provider model map"
    (is (nil? (#'models/parse-models-dev-provider-models "test-provider" {}))))

  (testing "Ignores invalid entries and keeps valid keys"
    (let [warnings* (atom [])]
      (with-redefs [logger/warn (fn [& args] (swap! warnings* conj args))]
        (is (match?
             {"good" {}
              "fallback-key" {}}
             (#'models/parse-models-dev-provider-models
              "test-provider"
              {"good" {"id" "good-id" "name" "Good Model"}
               "fallback-key" {"name" "Missing Id"}
               "bad-entry" 42})))
        (is (= 1 (count @warnings*)))))))

(deftest fetch-provider-models-with-priority-models-dev-test
  (let [models-dev-data {"oai-like" {"api" "https://api.openai.com"
                                     "models" {"gpt-5.2" {"id" "gpt-5.2"
                                                          "name" "GPT 5.2"}
                                               "gpt-4-legacy" {"id" "gpt-4-legacy"
                                                               "status" "deprecated"}}}
                         "anthropic-like" {"models" {"claude-sonnet-4-6"
                                                     {"id" "claude-sonnet-4-6"
                                                      "name" "Claude Sonnet 4.6"}}}
                         "synthetic" {"api" "https://api.synthetic.new/v1"
                                      "models" {"hf:Qwen/Qwen3-235B-A22B-Instruct-2507"
                                                {"id" "hf:Qwen/Qwen3-235B-A22B-Instruct-2507"
                                                 "name" "Qwen 3 235B Instruct"}}}
                         "my-provider" {"api" "https://api.my-provider.dev/v1"
                                        "models" {"foo" {"id" "foo" "name" "Foo"}}}}]
    (with-redefs [http/get (fn [_url _opts]
                             {:status 401
                              :body {:error "unauthorized"}})]
      (testing "Loads models from models.dev when native endpoint is unavailable"
        (is (match?
             {"oai-like" {"gpt-5.2" {}}
              "anthropic-like" {"claude-sonnet-4-6" {}}
              "synthetic" {"hf:Qwen/Qwen3-235B-A22B-Instruct-2507" {}}}
             (#'models/fetch-provider-models-with-priority
              {:providers {"oai-like" {:api "openai-responses"
                                       :url "https://api.openai.com"}
                           "anthropic-like" {:api "anthropic"
                                             :url "https://api.anthropic.com"}
                           "synthetic" {:api "openai-chat"
                                        :url "https://api.synthetic.new/v1"}
                           "my-provider" {:api "openai-chat"}
                           "unknown-url" {:api "openai-chat"
                                          :url "https://api.unknown.test/v1"}}}
              {}
              models-dev-data))))

      (testing "Skips models.dev loading when fetchModels is false"
        (is (match?
             {}
             (#'models/fetch-provider-models-with-priority
              {:providers {"synthetic" {:api "openai-chat"
                                        :url "https://api.synthetic.new/v1"
                                        :fetchModels false}}}
              {}
              models-dev-data)))))))

(deftest fetch-provider-models-with-priority-fetchmodels-disabled-test
  (let [native-calls* (atom 0)
        models-dev-data {"native-provider" {"api" "https://api.openai.com"
                                            "models" {"from-models-dev" {"id" "from-models-dev"}}}}
        config {:providers {"native-provider" {:api "openai-responses"
                                               :url "https://api.openai.com"
                                               :key "sk-test"
                                               :fetchModels false}}}]
    (with-redefs [http/get (fn [_url _opts]
                             (swap! native-calls* inc)
                             {:status 200
                              :body {:data [{:id "native-1"}]}})]
      (is (match?
           {}
           (#'models/fetch-provider-models-with-priority config {} models-dev-data)))
      (is (= 0 @native-calls*)))))

(deftest fetch-provider-models-with-priority-native-first-test
  (let [request* (atom nil)
        models-dev-data {"native-provider" {"api" "https://api.openai.com"
                                            "models" {"from-models-dev" {"id" "from-models-dev"}}}}]
    (with-redefs [http/get (fn [url opts]
                             (reset! request* [url opts])
                             {:status 200
                              :body {:data [{:id "native-1"}]}})]
      (is (match?
           {"native-provider" {"native-1" {}}}
           (#'models/fetch-provider-models-with-priority
            {:providers {"native-provider" {:api "openai-responses"
                                            :url "https://api.openai.com"
                                            :key "sk-test"}}}
            {}
            models-dev-data)))
      (is (= "https://api.openai.com/v1/models" (first @request*)))
      (is (= "Bearer sk-test" (get-in @request* [1 :headers "Authorization"]))))))

(deftest fetch-provider-models-anthropic-token-uses-x-api-key-test
  (let [request* (atom nil)
        models-dev-data {"anthropic" {"api" "https://api.anthropic.com"
                                      "models" {"from-models-dev" {"id" "from-models-dev"}}}}]
    (with-redefs [http/get (fn [url opts]
                             (reset! request* [url opts])
                             {:status 200
                              :body {:data [{:id "claude-3"}]}})]
      (#'models/fetch-provider-models-with-priority
       {:providers {"anthropic" {:api "anthropic"
                                 :url "https://api.anthropic.com"
                                 :key "sk-ant-test"}}}
       {}
       models-dev-data)
      (is (= "sk-ant-test" (get-in @request* [1 :headers "x-api-key"])))
      (is (= "2023-06-01" (get-in @request* [1 :headers "anthropic-version"])))
      (is (nil? (get-in @request* [1 :headers "Authorization"]))))))

(deftest fetch-provider-models-anthropic-oauth-uses-bearer-test
  (let [request* (atom nil)
        models-dev-data {"anthropic" {"api" "https://api.anthropic.com"
                                      "models" {"from-models-dev" {"id" "from-models-dev"}}}}]
    (with-redefs [http/get (fn [url opts]
                             (reset! request* [url opts])
                             {:status 200
                              :body {:data [{:id "claude-3"}]}})]
      (#'models/fetch-provider-models-with-priority
       {:providers {"anthropic" {:api "anthropic"
                                 :url "https://api.anthropic.com"}}}
       {:auth {"anthropic" {:api-key "oauth-token" :type :auth/oauth}}}
       models-dev-data)
      (is (= "Bearer oauth-token" (get-in @request* [1 :headers "Authorization"])))
      (is (= "2023-06-01" (get-in @request* [1 :headers "anthropic-version"])))
      (is (= "oauth-2025-04-20" (get-in @request* [1 :headers "anthropic-beta"])))
      (is (nil? (get-in @request* [1 :headers "x-api-key"]))))))

(deftest fetch-provider-models-github-copilot-sends-editor-headers-test
  (testing "Copilot /models request carries the Editor-Version headers required for IDE auth"
    (let [request* (atom nil)
          models-dev-data {"github-copilot" {"api" "https://api.githubcopilot.com"
                                             "models" {"from-models-dev" {"id" "from-models-dev"}}}}]
      (with-redefs [http/get (fn [url opts]
                               (reset! request* [url opts])
                               {:status 200
                                :body {:data [{:id "gpt-5.2"}]}})]
        (#'models/fetch-provider-models-with-priority
         {:providers {"github-copilot" {:api "openai-chat"
                                        :url "https://api.githubcopilot.com"
                                        :requiresAuth? true}}}
         {:auth {"github-copilot" {:api-key "copilot-token" :type :auth/oauth}}}
         models-dev-data)
        (is (= "Bearer copilot-token" (get-in @request* [1 :headers "Authorization"])))
        (is (re-find #"^vscode/" (get-in @request* [1 :headers "editor-version"])))
        (is (re-find #"^copilot-chat/" (get-in @request* [1 :headers "editor-plugin-version"])))
        (is (= "vscode-chat" (get-in @request* [1 :headers "copilot-integration-id"])))))))

(deftest copilot-discovered-metadata-builds-model-capabilities-test
  (with-redefs [http/get (fn [_url _opts]
                           {:status 200
                            :body {:data [{:id "gpt-future"
                                           :supported_endpoints ["/responses"]
                                           :capabilities {:supports {:reasoning_effort ["low" "high"]}}}
                                          {:id "claude-future"
                                           :supported_endpoints ["/v1/messages"]
                                           :capabilities {:supports {:adaptive_thinking true
                                                                     :reasoning_effort ["low" "high"]}}}]}})]
    (let [supported (build-supported-models
                     {:providers {"github-copilot" {:api "openai-chat"
                                                     :url "https://api.githubcopilot.com"
                                                     :requiresAuth? true
                                                     :models {"gpt-alias" {:modelName "gpt-future"}
                                                              "claude-alias" {:modelName "github-copilot/claude-future"}}}}}
                     {:auth {"github-copilot" {:api-key "copilot-token"
                                                 :type :auth/oauth}}}
                     {})]
      (is (= :openai-responses (get-in supported ["github-copilot/gpt-future" :api])))
      (is (true? (get-in supported ["github-copilot/gpt-future" :reason?])))
      (is (= {"low" {:reasoning {:effort "low" :summary "auto"}}
              "high" {:reasoning {:effort "high" :summary "auto"}}}
             (get-in supported ["github-copilot/gpt-future" :variants])))
      (is (= :openai-responses (get-in supported ["github-copilot/gpt-alias" :api])))
      (is (= (get-in supported ["github-copilot/gpt-future" :variants])
             (get-in supported ["github-copilot/gpt-alias" :variants])))
      (is (= :anthropic (get-in supported ["github-copilot/claude-alias" :api])))
      (is (= (get-in supported ["github-copilot/claude-future" :variants])
             (get-in supported ["github-copilot/claude-alias" :variants]))))))

(deftest copilot-model-capabilities-fallback-test
  (let [models-dev-data {"github-copilot"
                         {"models" {"claude-sonnet-4-6" {"reasoning" true}
                                    "gpt-5.5" {"reasoning" true}
                                    "gpt-5.6-sol" {"reasoning" true}
                                    "future-reasoner" {"reasoning" true}}}}
        config {:providers {"github-copilot" {:api "openai-chat"
                                               :url "https://api.githubcopilot.com"
                                               :requiresAuth? true
                                               :models {"claude-sonnet-4-6" {}
                                                        "gpt-5.5" {}
                                                        "gpt-5.6-sol" {}
                                                        "future-reasoner" {}}}}}
        db {:auth {"github-copilot" {:api-key "copilot-token"
                                      :type :auth/oauth}}}]
    (with-redefs [http/get (fn [_url _opts]
                             {:status 503 :body {:error "temporary"}})]
      (let [supported (build-supported-models config db models-dev-data)]
        (is (nil? (get-in supported ["github-copilot/claude-sonnet-4-6" :api])))
        (is (nil? (get-in supported ["github-copilot/claude-sonnet-4-6" :variants])))
        (is (nil? (get-in supported ["github-copilot/gpt-5.5" :api])))
        (is (nil? (get-in supported ["github-copilot/gpt-5.5" :variants])))
        (is (nil? (get-in supported ["github-copilot/future-reasoner" :api])))
        (is (nil? (get-in supported ["github-copilot/future-reasoner" :variants])))
        (is (nil? (get-in supported ["github-copilot/gpt-5.6-sol" :api])))
        (is (nil? (get-in supported ["github-copilot/gpt-5.6-sol" :variants])))))

    (testing "Empty native catalogs also fall back to models.dev"
      (with-redefs [http/get (fn [_url _opts]
                               {:status 200 :body {:data []}})]
        (let [supported (build-supported-models config db models-dev-data)]
          (is (contains? supported "github-copilot/claude-sonnet-4-6"))
          (is (nil? (get-in supported ["github-copilot/claude-sonnet-4-6" :variants])))
          (is (nil? (get-in supported ["github-copilot/gpt-5.6-sol" :variants]))))))))

(deftest fetch-provider-models-sends-provider-extra-headers-test
  (testing "Provider-level extraHeaders are sent on the native /models fetch"
    (let [request* (atom nil)]
      (with-redefs [http/get (fn [url opts]
                               (reset! request* [url opts])
                               {:status 200
                                :body {:data [{:id "gpt-5.2"}]}})]
        (is (match?
             {"my-gateway" {"gpt-5.2" {}}}
             (#'models/fetch-provider-models-with-priority
              {:providers {"my-gateway" {:api "openai-chat"
                                         :url "https://gateway.example.com"
                                         :key "sk-test"
                                         :extraHeaders {"Ocp-Apim-Subscription-Key" "apim-secret"}}}}
              {}
              {})))
        (is (= "https://gateway.example.com/models" (first @request*)))
        (is (= "apim-secret" (get-in @request* [1 :headers "Ocp-Apim-Subscription-Key"])))
        (is (= "Bearer sk-test" (get-in @request* [1 :headers "Authorization"])))))))

(deftest fetch-provider-models-with-priority-fallback-test
  (let [models-dev-data {"native-provider" {"api" "https://api.openai.com"
                                            "models" {"from-models-dev" {"id" "from-models-dev"}}}}]
    (with-redefs [http/get (fn [_url _opts]
                             {:status 503
                              :body {:error "temporary"}})]
      (is (match?
           {"native-provider" {"from-models-dev" {}}}
           (#'models/fetch-provider-models-with-priority
            {:providers {"native-provider" {:api "openai-responses"
                                            :url "https://api.openai.com"
                                            :key "sk-test"}}}
            {}
            models-dev-data))))))

(deftest fetch-provider-models-with-priority-invalid-native-payload-test
  (let [warnings* (atom [])
        models-dev-data {"native-provider" {"api" "https://api.openai.com"
                                            "models" {"from-models-dev" {"id" "from-models-dev"}}}}
        config {:providers {"native-provider" {:api "openai-responses"
                                               :url "https://api.openai.com"
                                               :key "sk-test"}}}]
    (with-redefs [http/get (fn [_url _opts]
                             {:status 200
                              :body {:models [{:id "native-ignored"}]}})
                  logger/warn (fn [& args] (swap! warnings* conj args) nil)]
      (is (match?
           {"native-provider" {"from-models-dev" {}}}
           (#'models/fetch-provider-models-with-priority config {} models-dev-data)))
      (is (some #(re-find #"missing sequential :data" (apply str %)) @warnings*)))))

(deftest fetch-provider-models-with-priority-retry-after-transient-failure-test
  (let [native-calls* (atom 0)
        models-dev-data {"native-provider" {"api" "https://api.openai.com"
                                            "models" {"from-models-dev" {"id" "from-models-dev"}}}}
        config {:providers {"native-provider" {:api "openai-responses"
                                               :url "https://api.openai.com"
                                               :key "sk-test"}}}]
    (with-redefs [http/get (fn [_url _opts]
                             (let [call (swap! native-calls* inc)]
                               (if (= call 1)
                                 {:status 503
                                  :body {:error "temporary"}}
                                 {:status 200
                                  :body {:data [{:id "native-2"}]}})))]
      (is (match?
           {"native-provider" {"from-models-dev" {}}}
           (#'models/fetch-provider-models-with-priority config {} models-dev-data)))
      (is (match?
           {"native-provider" {"native-2" {}}}
           (#'models/fetch-provider-models-with-priority config {} models-dev-data)))
      (is (= 2 @native-calls*)))))

(deftest build-model-capabilities-test
  (testing "Uses model-name from config when present"
    (let [all-models {"provider/gpt-4-turbo" {:tools true :reason? false :web-search false}}
          result (#'models/build-model-capabilities
                  all-models
                  "provider"
                  "gpt-4"
                  {:modelName "gpt-4-turbo"})]
      (is (match?
           ["provider/gpt-4" {:tools true :reason? false :web-search false :model-name "gpt-4-turbo"}]
           result))))

  (testing "Merges with known capabilities"
    (let [all-models {"provider/gpt-4" {:tools true :reason? true :web-search true}}
          result (#'models/build-model-capabilities
                  all-models
                  "provider"
                  "gpt-4"
                  {})]
      (is (match?
           ["provider/gpt-4" {:tools true :reason? true :web-search true :model-name "gpt-4"}]
           result))))

  (testing "Uses default capabilities for unknown models"
    (let [all-models {}
          result (#'models/build-model-capabilities
                  all-models
                  "unknown-provider"
                  "unknown-model"
                  {})]
      (is (match?
           ["unknown-provider/unknown-model"
            {:tools true :reason? true :web-search false :image-generation? false :model-name "unknown-model"}]
           result))))

  (testing "Image-generation capability flows through from known models"
    (let [all-models {"openai/gpt-5.2" {:tools true :reason? true :web-search true :image-generation? true}
                      "openai/gpt-4.1" {:tools true :reason? false :web-search true :image-generation? true}
                      "anthropic/claude-sonnet-4-6" {:tools true :reason? true :web-search true :image-generation? false}}]
      (is (match?
           ["openai/gpt-5.2" {:image-generation? true}]
           (#'models/build-model-capabilities all-models "openai" "gpt-5.2" {})))
      (is (match?
           ["openai/gpt-4.1" {:image-generation? true}]
           (#'models/build-model-capabilities all-models "openai" "gpt-4.1" {})))
      (is (match?
           ["anthropic/claude-sonnet-4-6" {:image-generation? false}]
           (#'models/build-model-capabilities all-models "anthropic" "claude-sonnet-4-6" {}))))))

(deftest config-overrides->capabilities-test
  (testing "Translates limit and per-1M cost into internal capability keys"
    (let [caps (#'models/config-overrides->capabilities
                {:limit {:context 131072 :output 8192}
                 :cost {:input 1.5 :output 6.0 :cacheRead 0.15 :cacheWrite 3.0}})]
      (is (= {:context 131072 :output 8192} (:limit caps)))
      (is (= 8192 (:max-output-tokens caps)))
      (is (< 1.49e-6 (:input-token-cost caps) 1.51e-6))
      (is (< 5.99e-6 (:output-token-cost caps) 6.01e-6))
      (is (< 1.49e-7 (:input-cache-read-token-cost caps) 1.51e-7))
      (is (< 2.99e-6 (:input-cache-creation-token-cost caps) 3.01e-6))))

  (testing "Keeps zero cost for free local models"
    (let [caps (#'models/config-overrides->capabilities {:cost {:input 0 :output 0}})]
      (is (zero? (:input-token-cost caps)))
      (is (zero? (:output-token-cost caps)))))

  (testing "Partial limit override only sets provided sub-keys"
    (is (= {:limit {:context 200000}}
           (#'models/config-overrides->capabilities {:limit {:context 200000}}))))

  (testing "Drops non-positive limits"
    (is (= {} (#'models/config-overrides->capabilities {:limit {:context 0 :output -1}}))))

  (testing "Declares image input for custom models via :imageInput"
    (is (true? (:image-input? (#'models/config-overrides->capabilities {:imageInput true}))))
    (is (false? (:image-input? (#'models/config-overrides->capabilities {:imageInput false}))))
    (is (nil? (:image-input? (#'models/config-overrides->capabilities {})))))

  (testing "Returns empty map for nil/empty config"
    (is (= {} (#'models/config-overrides->capabilities nil)))
    (is (= {} (#'models/config-overrides->capabilities {})))))

(deftest build-model-capabilities-overrides-test
  (testing "Config overrides win over and deep-merge with known models.dev limits"
    (let [all-models {"provider/m" {:tools true :reason? true :limit {:context 1000 :output 100}}}
          [full caps] (#'models/build-model-capabilities
                       all-models "provider" "m"
                       {:limit {:context 2000} :cost {:input 0 :output 0}})]
      (is (= "provider/m" full))
      ;; context overridden, output preserved from models.dev
      (is (= {:context 2000 :output 100} (:limit caps)))
      (is (zero? (:input-token-cost caps)))
      (is (true? (:tools caps)))))

  (testing "Custom model declares image input via config; defaults to false"
    (let [[_ caps] (#'models/build-model-capabilities {} "local" "llava" {:imageInput true})]
      (is (true? (:image-input? caps))))
    (let [[_ caps] (#'models/build-model-capabilities {} "local" "plain" {})]
      (is (false? (:image-input? caps)))))

  (testing "Unknown model gains a context limit and max-output from config override"
    (let [[full caps] (#'models/build-model-capabilities
                       {} "local" "qwen"
                       {:limit {:context 131072 :output 8192}})]
      (is (= "local/qwen" full))
      (is (= {:context 131072 :output 8192} (:limit caps)))
      (is (= 8192 (:max-output-tokens caps)))
      (is (= "qwen" (:model-name caps))))))

(deftest provider-models-override-wins-over-native-test
  (testing "A provider-models-override result is used instead of the native /models fetch"
    (let [native-calls* (atom 0)
          models-dev-data {"openai" {"api" "https://api.openai.com"
                                     "models" {"gpt-5.5" {"limit" {"context" 1050000}}}}}
          config {:providers {"openai" {:api "openai-responses"
                                        :url "https://api.openai.com"
                                        :models {"gpt-5.5" {}}}}}
          db {:auth {"openai" {:api-key "oauth-token" :type :auth/oauth}}}]
      (with-redefs [http/get (fn [_url _opts]
                               (swap! native-calls* inc)
                               {:status 200 :body {:data [{:id "gpt-5.5"}]}})
                    llm-util/provider-models-override
                    (fn [{:keys [provider auth-type]}]
                      (when (= [provider auth-type] ["openai" :auth/oauth])
                        {"gpt-5.5" {:limit {:context 272000}}}))]
        (let [supported (build-supported-models config db models-dev-data)]
          (is (= 272000 (get-in supported ["openai/gpt-5.5" :limit :context])))
          (is (zero? @native-calls*) "native /models must not be called when an override wins"))))))

(deftest openai-token-keeps-direct-api-context-window-test
  (testing "API-key (non-OAuth) OpenAI keeps the direct API context window (no override)"
    (let [request* (atom nil)
          models-dev-data {"openai" {"api" "https://api.openai.com"
                                     "models" {"gpt-5.5" {"limit" {"context" 1050000}}}}}
          config {:providers {"openai" {:api "openai-responses"
                                        :url "https://api.openai.com"
                                        :key "sk-test"
                                        :models {"gpt-5.5" {}}}}}]
      (with-redefs [http/get (fn [url opts]
                               (reset! request* [url opts])
                               {:status 200 :body {:data [{:id "gpt-5.5"}]}})]
        (let [supported (build-supported-models config {} models-dev-data)]
          (is (= "https://api.openai.com/v1/models" (first @request*)))
          (is (= 1050000 (get-in supported ["openai/gpt-5.5" :limit :context]))))))))

(deftest sane-output-limit-test
  (testing "keeps output below context"
    (is (= 8192 (#'models/sane-output-limit 200000 8192))))
  (testing "drops output >= context (request can never fit any input)"
    (is (nil? (#'models/sane-output-limit 500000 500000)))
    (is (nil? (#'models/sane-output-limit 500000 600000))))
  (testing "keeps output when context is unknown"
    (is (= 8192 (#'models/sane-output-limit nil 8192))))
  (testing "nil output stays nil"
    (is (nil? (#'models/sane-output-limit 200000 nil)))))

(deftest all-sanitizes-catalog-output-limit-test
  (let [models-dev-data {"openrouter" {"api" "https://openrouter.ai/api/v1"
                                       "models" {"x-ai/grok-4.5" {"limit" {"context" 500000 "output" 500000}}
                                                 "sane/model" {"limit" {"context" 200000 "output" 32000}}
                                                 "no-context/model" {"limit" {"output" 8192}}}}}
        known (#'models/all models-dev-data)]
    (testing "catalog output == context is treated as unknown"
      (is (nil? (get-in known ["openrouter/x-ai/grok-4.5" :limit :output])))
      (is (= 500000 (get-in known ["openrouter/x-ai/grok-4.5" :limit :context])))
      (is (nil? (get-in known ["openrouter/x-ai/grok-4.5" :max-output-tokens]))))
    (testing "output < context is kept"
      (is (= {:context 200000 :output 32000} (get-in known ["openrouter/sane/model" :limit])))
      (is (= 32000 (get-in known ["openrouter/sane/model" :max-output-tokens]))))
    (testing "output without context is kept"
      (is (= 8192 (get-in known ["openrouter/no-context/model" :limit :output])))
      (is (= 8192 (get-in known ["openrouter/no-context/model" :max-output-tokens]))))))

(deftest openrouter-native-limits-precedence-test
  (testing "user config limit > provider-discovered limit > models.dev catalog"
    (let [models-dev-data {"openrouter" {"api" "https://openrouter.ai/api/v1"
                                         "models" {"x-ai/grok-4.5" {"limit" {"context" 500000 "output" 500000}}}}}
          config {:providers {"openrouter" {:api "openai-chat"
                                            :url "https://openrouter.ai/api/v1"
                                            :key "k"
                                            :models {"x-ai/grok-4.5" {}
                                                     "user/tuned" {:limit {:context 90000 :output 9000}}}}}}]
      (with-redefs [http/get (fn [_url _opts]
                               {:status 200
                                :body {:data [{:id "x-ai/grok-4.5"
                                               :context_length 500000
                                               :top_provider {:max_completion_tokens nil}}
                                              {:id "user/tuned"
                                               :context_length 400000
                                               :top_provider {:max_completion_tokens 65536}}]}})]
        (let [supported (build-supported-models config {} models-dev-data)]
          ;; bogus models.dev 500k/500k output sanitized away, discovered context kept,
          ;; leaving max-output-tokens unset so providers use their own fallback
          (is (= 500000 (get-in supported ["openrouter/x-ai/grok-4.5" :limit :context])))
          (is (nil? (get-in supported ["openrouter/x-ai/grok-4.5" :limit :output])))
          (is (nil? (get-in supported ["openrouter/x-ai/grok-4.5" :max-output-tokens])))
          ;; user config beats the discovered 400000/65536
          (is (= {:context 90000 :output 9000} (get-in supported ["openrouter/user/tuned" :limit])))
          (is (= 9000 (get-in supported ["openrouter/user/tuned" :max-output-tokens]))))))))

(deftest full-model-for-test
  (let [db {:models {"github-copilot/explorer-small" {}
                     "company-litellm/explorer-small" {}
                     "anthropic/claude-sonnet-4-6" {}}}]
    (testing "resolves a bare alias against the given provider (provider-relative)"
      (is (= "github-copilot/explorer-small"
             (models/full-model-for db "github-copilot" "explorer-small")))
      (is (= "company-litellm/explorer-small"
             (models/full-model-for db "company-litellm" "explorer-small"))))
    (testing "resolves a literal full model id"
      (is (= "anthropic/claude-sonnet-4-6"
             (models/full-model-for db "github-copilot" "anthropic/claude-sonnet-4-6"))))
    (testing "returns nil when neither alias nor literal matches"
      (is (nil? (models/full-model-for db "github-copilot" "missing")))
      (is (nil? (models/full-model-for db "github-copilot" "openai/missing"))))
    (testing "returns nil for a nil model id"
      (is (nil? (models/full-model-for db "github-copilot" nil))))
    (testing "without a provider only literal full ids resolve"
      (is (nil? (models/full-model-for db nil "explorer-small")))
      (is (= "anthropic/claude-sonnet-4-6"
             (models/full-model-for db nil "anthropic/claude-sonnet-4-6"))))))
