(ns integration.initialize-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [integration.eca :as eca]
   [integration.fixture :as fixture]
   [llm-mock.mocks :as llm.mocks]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(eca/clean-after-test)

(defn ^:private provider-model-present?
  [models provider]
  (some #(string/starts-with? % (str provider "/")) models))

(defn ^:private model-present?
  [models model]
  (contains? (set models) model))

(defn ^:private built-in-providers-present?
  "github-copilot is excluded: its models only come from the account's
   catalog after login, so none is listed against the mock server."
  [models]
  (every? #(provider-model-present? models %)
          ["anthropic" "google" "openai"]))

(deftest default-initialize-and-shutdown
  (eca/start-process!)
  (let [models-pred (fn [models]
                      (and (vector? models)
                           (built-in-providers-present? models)
                           (model-present? models "anthropic/claude-sonnet-4-6")))]
    (testing "initialize request with default config"
      (is (match?
           {:chatWelcomeMessage (m/pred #(string/includes? % "Welcome to ECA!"))}
           (eca/request! (fixture/initialize-request
                          {:initializationOptions (merge fixture/default-init-options
                                                         {:chat {:defaultAgent "plan"}})})))))

    (testing "initialized notification"
      (eca/notify! (fixture/initialized-notification)))

    (testing "config updated"
      (is (match?
           {:chat {:models (m/pred models-pred)
                   :selectModel "anthropic/claude-sonnet-4-6"
                   :agents ["code" "plan"]
                   :selectAgent "plan"
                   :welcomeMessage (m/pred #(string/includes? % "Welcome to ECA!"))}}
           (eca/client-awaits-server-notification :config/updated)))))

  (testing "Native tools updated"
    (is (match?
         {:type "native"
          :name "ECA"
          :status "running"
          :tools (m/pred seq)}
         (eca/client-awaits-server-notification :tool/serverUpdated))))

  (testing "shutdown request"
    (is (match?
         nil
         (eca/request! (fixture/shutdown-request))))

    (testing "exit notification"
      (eca/notify! (fixture/exit-notification)))))

(deftest initialize-with-custom-providers
  (eca/start-process!)
  (let [models-pred (fn [models]
                      (and (vector? models)
                           (built-in-providers-present? models)
                           (model-present? models "my-custom/foo1")
                           (model-present? models "my-custom/bar2")))]
    (testing "initialize request with custom providers"
      (is (match?
           {:chatWelcomeMessage (m/pred #(string/includes? % "Welcome to ECA!"))}
           (eca/request! (fixture/initialize-request
                          {:initializationOptions (merge fixture/default-init-options
                                                         {:defaultModel "my-custom/bar2"
                                                          :providers
                                                          (merge fixture/default-providers
                                                                 {"my-custom" {:api "openai-chat"
                                                                               :url "MY_URL"
                                                                               :key "MY_KEY"
                                                                               :models {"foo1" {}
                                                                                        "bar2" {}}}})})})))))
    (testing "initialized notification"
      (eca/notify! (fixture/initialized-notification)))

    (testing "config updated"
      (is (match?
           {:chat {:models (m/pred models-pred)
                   :selectModel "my-custom/bar2"
                   :agents ["code" "plan"]
                   :selectAgent "code"
                   :welcomeMessage (m/pred #(string/includes? % "Welcome to ECA!"))}}
           (eca/client-awaits-server-notification :config/updated))))))

(deftest refresh-models-in-running-server
  (eca/start-process!)
  (eca/request! (fixture/initialize-request
                 {:initializationOptions
                  (assoc fixture/default-init-options :providers
                         {"ollama" {:url (str fixture/base-llm-mock-url "/ollama")}})}))
  (eca/notify! (fixture/initialized-notification))
  (is (match? {:chat {:models ["ollama/qwen3"]}}
              (eca/client-awaits-server-notification :config/updated)))

  (testing "a refresh returns its result and publishes a changed catalog"
    (llm.mocks/set-case! :refresh-new-model)
    (is (match? {:modelCount (m/pred number?)
                 :warnings (m/pred vector?)}
                (eca/request! [:models/refresh {}])))
    (is (match? {:chat {:models ["ollama/qwen4"]}}
                (eca/client-awaits-server-notification :config/updated))))

  (testing "an empty catalog returns a JSON-RPC error, not a success result"
    (llm.mocks/set-case! :refresh-empty)
    (let [response (eca/request! [:models/refresh {}])]
      (is (match? {:error {:code "no_usable_model_catalog"}} response))
      (is (not (contains? response :result))))))
