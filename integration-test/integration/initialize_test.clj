(ns integration.initialize-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [integration.eca :as eca]
   [integration.fixture :as fixture]
   [matcher-combinators.test :refer [match?]]
   [matcher-combinators.matchers :as m]))

(eca/clean-after-test)

(deftest default-initialize-and-shutdown
  (eca/start-process!)

  (testing "initialize request with default config"
    (is (match?
         {:models ["claude-3-5-haiku-20241022"
                   "claude-opus-4-1-20250805"
                   "claude-opus-4-20250514"
                   "claude-sonnet-4-20250514"
                   "gpt-4.1"
                   "gpt-5"
                   "gpt-5-mini"
                   "gpt-5-nano"
                   "o3"
                   "o4-mini"]
          :chatDefaultModel "claude-sonnet-4-20250514"
          :chatBehaviors ["agent" "plan"]
          :chatDefaultBehavior "plan"
          :chatWelcomeMessage "Welcome to ECA!\n\nType '/' for commands\n\n"}
         (eca/request! (fixture/initialize-request
                        {:initializationOptions (merge fixture/default-init-options
                                                       {:chatBehavior "plan"})
                         :capabilities {:codeAssistant {:chat {}}}})))))

  (testing "initialized notification"
    (eca/notify! (fixture/initialized-notification)))

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
         (eca/request! (fixture/shutdown-request)))))

  (testing "exit notification"
    (eca/notify! (fixture/exit-notification))))

(deftest initialize-with-custom-providers
  (eca/start-process!)
  (testing "initialize request with custom providers"
    (is (match?
         {:models ["claude-3-5-haiku-20241022"
                   "claude-opus-4-1-20250805"
                   "claude-opus-4-20250514"
                   "claude-sonnet-4-20250514"
                   "gpt-4.1"
                   "gpt-5"
                   "gpt-5-mini"
                   "gpt-5-nano"
                   "myCustom/bar-2"
                   "myCustom/foo-1"
                   "o3"
                   "o4-mini"]
          :chatDefaultModel "myCustom/bar-2"
          :chatBehaviors ["agent" "plan"]
          :chatDefaultBehavior "agent"
          :chatWelcomeMessage "Welcome to ECA!\n\nType '/' for commands\n\n"}
         (eca/request! (fixture/initialize-request
                        {:initializationOptions (merge fixture/default-init-options
                                                       {:customProviders
                                                        {"myCustom" {:api "openai"
                                                                     :urlEnv "MY_CUSTOM_API_URL"
                                                                     :keyEnv "MY_CUSTOM_API_KEY"
                                                                     :models ["foo-1" "bar-2"]
                                                                     :defaultModel "bar-2"}}})
                         :capabilities {:codeAssistant {:chat {}}}}))))))
