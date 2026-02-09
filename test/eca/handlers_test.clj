(ns eca.handlers-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.tools :as f.tools]
   [eca.handlers :as handlers]
   [eca.models :as models]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(deftest initialize-test
  (testing "initializationOptions config is merged properly with default init config"
    (let [db* (atom {})]
      (with-redefs [models/sync-models! (constantly nil)
                    db/load-db-from-cache! (constantly nil)]
        (is (match?
             {}
             (handlers/initialize (h/components)
                                  {:initialization-options
                                   {:pureConfig true
                                    :providers {"github-copilot" {:key "123"
                                                                  :models {"gpt-5" {:a 1}}}}}})))
        (is (match?
             {:providers {"github-copilot" {:key "123"
                                            :models {"gpt-5" {:a 1}
                                                     "gpt-5.2" {}}
                                            :url string?}}}
             (#'config/all* @db*)))))))

(deftest chat-selected-agent-changed-test
  (testing "Switching to agent with defaultModel updates model"
    (h/reset-components!)
    (h/config! {:agent {"custom" {:defaultModel "gpt-4.1"}}})

    (handlers/chat-selected-agent-changed (h/components)
                                          {:agent "custom"})
    (is (match? {:config-updated [{:chat {:select-model "gpt-4.1"}}]
                 :tool-server-update [{}]}
                (h/messages))))

  (testing "Switching to agent without defaultModel uses global default"
    (h/reset-components!)
    (h/config! {:defaultModel "claude-opus-4"
                :agent {"plan" {}}})
    (handlers/chat-selected-agent-changed (h/components)
                                          {:agent "plan"})
    (is (match? {:config-updated [{:chat {:select-model "claude-opus-4"}}]
                 :tool-server-update [{}]}
                (h/messages))))

  (testing "Switching agent updates tool status"
    (h/reset-components!)
    (h/config! {:agent {"plan" {:disabledTools ["edit_file" "write_file"]}}})
    (with-redefs [f.tools/native-tools (constantly [{:name "edit_file"
                                                     :server {:name "eca"}}
                                                    {:name "read_file"
                                                     :server {:name "eca"}}])]
      (handlers/chat-selected-agent-changed (h/components)
                                            {:agent "plan"})
      (is (match? {:tool-server-update [{:tools [{:name "edit_file"
                                                  :disabled true}
                                                 {:name "read_file"
                                                  :disabled false}]}]}
                  (h/messages)))))

  (testing "Switching to undefined agent uses defaults"
    (h/reset-components!)
    (h/config! {:defaultModel "fallback-model"})
    (handlers/chat-selected-agent-changed (h/components)
                                          {:agent "nonexistent"})
    (is (match? {:config-updated [{:chat {:select-model "fallback-model"}}]
                 :tool-server-update [{}]}
                (h/messages))))

  (testing "Backward compat: accepts 'behavior' param"
    (h/reset-components!)
    (h/config! {:agent {"custom" {:defaultModel "gpt-4.1"}}})

    (handlers/chat-selected-agent-changed (h/components)
                                          {:behavior "custom"})
    (is (match? {:config-updated [{:chat {:select-model "gpt-4.1"}}]
                 :tool-server-update [{}]}
                (h/messages)))))
