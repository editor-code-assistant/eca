(ns integration.initialize-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [integration.eca :as eca]
   [integration.fixture :as fixture]
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
  [models]
  (every? #(provider-model-present? models %)
          ["anthropic" "github-copilot" "google" "openai"]))

(deftest default-initialize-and-shutdown
  (eca/start-process!)
  (let [models-pred (fn [models]
                      (and (vector? models)
                           (built-in-providers-present? models)
                           (model-present? models "anthropic/claude-sonnet-4.5")))]
    (testing "initialize request with default config"
      (is (match?
           {:chatWelcomeMessage (m/pred #(string/includes? % "Welcome to ECA!"))}
           (eca/request! (fixture/initialize-request
                          {:initializationOptions (merge fixture/default-init-options
                                                         {:chat {:defaultBehavior "plan"}})})))))

    (testing "initialized notification"
      (eca/notify! (fixture/initialized-notification)))

    (testing "config updated"
      (is (match?
           {:chat {:models (m/pred models-pred)
                   :selectModel "anthropic/claude-sonnet-4.5"
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
