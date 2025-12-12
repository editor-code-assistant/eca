(ns integration.initialize-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [integration.eca :as eca]
   [integration.fixture :as fixture]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(eca/clean-after-test)

(deftest default-initialize-and-shutdown
  (eca/start-process!)

  (let [models ["anthropic/claude-haiku-4.5"
                "anthropic/claude-opus-4.1"
                "anthropic/claude-opus-4.5"
                "anthropic/claude-sonnet-4.5"
                "github-copilot/claude-haiku-4.5"
                "github-copilot/claude-opus-4.1"
                "github-copilot/claude-opus-4.5"
                "github-copilot/claude-sonnet-4.5"
                "github-copilot/gemini-2.5-pro"
                "github-copilot/gpt-4.1"
                "github-copilot/gpt-4o"
                "github-copilot/gpt-5"
                "github-copilot/gpt-5-mini"
                "github-copilot/gpt-5.1"
                "github-copilot/gpt-5.2"
                "github-copilot/grok-code-fast-1"
                "google/gemini-2.0-flash"
                "google/gemini-2.5-pro"
                "openai/gpt-4.1"
                "openai/gpt-5"
                "openai/gpt-5-codex"
                "openai/gpt-5-mini"
                "openai/gpt-5-nano"
                "openai/gpt-5.1"
                "openai/gpt-5.2"
                "openai/o3"
                "openai/o4-mini"]]
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
           {:chat {:models models
                   :selectModel "anthropic/claude-sonnet-4.5"
                   :behaviors ["agent" "plan"]
                   :selectBehavior "plan"
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
  (let [models ["anthropic/claude-haiku-4.5"
                "anthropic/claude-opus-4.1"
                "anthropic/claude-opus-4.5"
                "anthropic/claude-sonnet-4.5"
                "github-copilot/claude-haiku-4.5"
                "github-copilot/claude-opus-4.1"
                "github-copilot/claude-opus-4.5"
                "github-copilot/claude-sonnet-4.5"
                "github-copilot/gemini-2.5-pro"
                "github-copilot/gpt-4.1"
                "github-copilot/gpt-4o"
                "github-copilot/gpt-5"
                "github-copilot/gpt-5-mini"
                "github-copilot/gpt-5.1"
                "github-copilot/gpt-5.2"
                "github-copilot/grok-code-fast-1"
                "google/gemini-2.0-flash"
                "google/gemini-2.5-pro"
                "my-custom/bar2"
                "my-custom/foo1"
                "openai/gpt-4.1"
                "openai/gpt-5"
                "openai/gpt-5-codex"
                "openai/gpt-5-mini"
                "openai/gpt-5-nano"
                "openai/gpt-5.1"
                "openai/gpt-5.2"
                "openai/o3"
                "openai/o4-mini"]]
    (testing "initialize request with custom providers"
      (is (match?
           {:chatWelcomeMessage (m/pred #(string/includes? % "Welcome to ECA!"))}
           (eca/request! (fixture/initialize-request
                          {:initializationOptions (merge fixture/default-init-options
                                                         {:defaultModel "my-custom/bar-2"
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
           {:chat {:models models
                   :selectModel "my-custom/bar-2"
                   :behaviors ["agent" "plan"]
                   :selectBehavior "agent"
                   :welcomeMessage (m/pred #(string/includes? % "Welcome to ECA!"))}}
           (eca/client-awaits-server-notification :config/updated))))))
