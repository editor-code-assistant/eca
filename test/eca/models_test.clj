(ns eca.models-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [hato.client :as http]
   [matcher-combinators.test :refer [match?]]
   [eca.logger :as logger]
   [eca.models :as models]))

(set! *warn-on-reflection* true)

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
          (is (re-find #"status 503" (ex-message e))))))))

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
    (let [dynamic {"claude-sonnet-4.5" {:modelName "claude-sonnet-4-5"}}
          static {"claude-sonnet-4.5" {:extraPayload {:temperature 0.2}}}]
      (is (match?
           {"claude-sonnet-4.5" {:modelName "claude-sonnet-4-5"
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
                {:by-id {"anthropic" {"models" {"claude-sonnet-4-5"
                                                {"id" "claude-sonnet-4-5"
                                                 "name" "Claude Sonnet 4.5"}}}}
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
         {"claude-sonnet-4-5" {}
          "gemini-3-flash-preview" {}
          "gpt-5.2" {}
          "gemini-2.5-pro" {}}
         (#'models/parse-models-dev-provider-models
          "test-provider"
          {"claude-sonnet-4-5" {"name" "Claude Sonnet 4.5"
                                "id" "claude-sonnet-4-5"}
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
                         "anthropic-like" {"models" {"claude-sonnet-4-5"
                                                     {"id" "claude-sonnet-4-5"
                                                      "name" "Claude Sonnet 4.5"}}}
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
              "anthropic-like" {"claude-sonnet-4-5" {}}
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
            {:tools true :reason? true :web-search false :model-name "unknown-model"}]
           result)))))
