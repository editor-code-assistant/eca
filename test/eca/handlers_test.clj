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
  (testing "Switching to agent with defaultModel updates model and variants"
    (h/reset-components!)
    (h/config! {:providers {"anthropic" {:models {"claude-sonnet-4-5"
                                                  {:variants {"low" {:a 1} "medium" {:a 2} "high" {:a 3}}}}}}
                :agent {"custom" {:defaultModel "anthropic/claude-sonnet-4-5"
                                  :variant "medium"}}})

    (handlers/chat-selected-agent-changed (h/components)
                                          {:agent "custom"})
    (is (match? {:config-updated [{:chat {:select-model "anthropic/claude-sonnet-4-5"
                                          :variants ["high" "low" "medium"]
                                          :select-variant "medium"}}]
                 :tool-server-update [{}]}
                (h/messages))))

  (testing "Switching to agent with defaultModel without variants sends empty variants"
    (h/reset-components!)
    (h/config! {:agent {"custom" {:defaultModel "openai/gpt-4.1"}}})

    (handlers/chat-selected-agent-changed (h/components)
                                          {:agent "custom"})
    (is (match? {:config-updated [{:chat {:select-model "openai/gpt-4.1"
                                          :variants []
                                          :select-variant nil}}]
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

(deftest chat-selected-model-changed-test
  (testing "Selecting model with variants sends sorted variant names"
    (h/reset-components!)
    (h/config! {:providers {"anthropic" {:models {"claude-sonnet-4-5"
                                                  {:variants {"low" {:a 1} "medium" {:a 2} "high" {:a 3} "max" {:a 4}}}}}}
                :defaultAgent "code"
                :agent {"code" {:variant "medium"}}})

    (handlers/chat-selected-model-changed (h/components)
                                          {:model "anthropic/claude-sonnet-4-5"})
    (is (match? {:config-updated [{:chat {:variants ["high" "low" "max" "medium"]
                                          :select-variant "medium"}}]}
                (h/messages))))

  (testing "Selecting model without variants sends empty variants"
    (h/reset-components!)
    (h/config! {:providers {"openai" {:models {"gpt-4.1" {}}}}
                :defaultAgent "code"
                :agent {"code" {:variant "medium"}}})

    (handlers/chat-selected-model-changed (h/components)
                                          {:model "openai/gpt-4.1"})
    (is (match? {:config-updated [{:chat {:variants []
                                          :select-variant nil}}]}
                (h/messages))))

  (testing "Agent variant not in model variants results in nil select-variant"
    (h/reset-components!)
    (h/config! {:providers {"openai" {:models {"gpt-5.2"
                                               {:variants {"none" {:a 1} "low" {:a 2} "high" {:a 3}}}}}}
                :variantsByModel {"gpt[-._]5[-._]2(?!\\d)"
                                  {:variants {"none" {:reasoning {:effort "none"}}
                                              "low" {:reasoning {:effort "low"}}
                                              "medium" {:reasoning {:effort "medium"}}
                                              "high" {:reasoning {:effort "high"}}
                                              "xhigh" {:reasoning {:effort "xhigh"}}}}}
                :defaultAgent "code"
                :agent {"code" {:variant "not-a-variant"}}})

    (handlers/chat-selected-model-changed (h/components)
                                          {:model "openai/gpt-5.2"})
    (is (match? {:config-updated [{:chat {:variants ["high" "low" "medium" "none" "xhigh"]
                                          :select-variant nil}}]}
                (h/messages))))

  (testing "Selecting ollama model without provider config sends empty variants"
    (h/reset-components!)
    (handlers/chat-selected-model-changed (h/components)
                                          {:model "ollama/llama3"})
    (is (match? {:config-updated [{:chat {:variants []
                                          :select-variant nil}}]}
                (h/messages))))

  (testing "Custom provider with matching model gets built-in variants from variantsByModel"
    (h/reset-components!)
    (h/config! {:providers {"my-proxy" {:api "anthropic"
                                        :models {"claude-opus-4-6" {}}}}
                :variantsByModel {"opus[-._]4[-._][56]"
                                  {:variants {"low" {:output_config {:effort "low"}}
                                              "medium" {:output_config {:effort "medium"}}
                                              "high" {:output_config {:effort "high"}}
                                              "max" {:output_config {:effort "max"}}}}}
                :defaultAgent "code"
                :agent {"code" {}}})

    (handlers/chat-selected-model-changed (h/components)
                                          {:model "my-proxy/claude-opus-4-6"})
    (is (match? {:config-updated [{:chat {:variants ["high" "low" "max" "medium"]
                                          :select-variant nil}}]}
                (h/messages))))

  (testing "excludeProviders prevents built-in variants for that provider"
    (h/reset-components!)
    (h/config! {:providers {"github-copilot" {:models {"gpt-5.2" {}}}}
                :variantsByModel {"gpt[-._]5[-._]2(?!\\d)"
                                  {:variants {"low" {:reasoning {:effort "low"}}}
                                   :excludeProviders ["github-copilot"]}}
                :defaultAgent "code"
                :agent {"code" {}}})

    (handlers/chat-selected-model-changed (h/components)
                                          {:model "github-copilot/gpt-5.2"})
    (is (match? {:config-updated [{:chat {:variants []
                                          :select-variant nil}}]}
                (h/messages))))

  (testing "Client variant not in new model's variants forces select-variant nil"
    (h/reset-components!)
    (h/config! {:providers {"anthropic" {:models {"claude-opus-4-6"
                                                  {:variants {"low" {:a 1} "medium" {:a 2} "high" {:a 3} "max" {:a 4}}}}}
                            "openai" {:models {"gpt-5.3-codex"
                                               {:variants {"none" {:b 1} "low" {:b 2} "high" {:b 3}}}}}}
                :defaultAgent "code"
                :agent {"code" {}}})
    ;; First select anthropic model — no agent variant, so select-variant is nil
    (handlers/chat-selected-model-changed (h/components)
                                          {:model "anthropic/claude-opus-4-6"})
    (is (match? {:config-updated [{:chat {:variants ["high" "low" "max" "medium"]
                                          :select-variant nil}}]}
                (h/messages)))
    ;; Now switch to openai model with "max" still selected on the client.
    ;; "max" doesn't exist for this model, so select-variant nil must be emitted.
    (h/reset-messenger!)
    (handlers/chat-selected-model-changed (h/components)
                                          {:model "openai/gpt-5.3-codex"
                                           :variant "max"})
    (is (match? {:config-updated [{:chat {:variants ["high" "low" "none"]
                                          :select-variant nil}}]}
                (h/messages))))

  (testing "Client variant valid for new model does not force a clear"
    (h/reset-components!)
    (h/config! {:providers {"anthropic" {:models {"claude-opus-4-6"
                                                  {:variants {"low" {:a 1} "medium" {:a 2} "high" {:a 3} "max" {:a 4}}}}}
                            "openai" {:models {"gpt-5.3-codex"
                                               {:variants {"low" {:b 1} "medium" {:b 2} "high" {:b 3}}}}}}
                :defaultAgent "code"
                :agent {"code" {}}})
    (handlers/chat-selected-model-changed (h/components)
                                          {:model "anthropic/claude-opus-4-6"})
    (h/reset-messenger!)
    ;; "high" exists in the new model's variants, so no forced clear
    (handlers/chat-selected-model-changed (h/components)
                                          {:model "openai/gpt-5.3-codex"
                                           :variant "high"})
    ;; variants changed, but select-variant is NOT emitted since it's still nil (no agent variant)
    (is (match? {:config-updated [{:chat {:variants ["high" "low" "medium"]}}]}
                (h/messages))))

  (testing "User variant set to {} removes it from the built-in variants"
    (h/reset-components!)
    (h/config! {:providers {"anthropic" {:models {"claude-sonnet-4-6"
                                                  {:variants {"low" {} "max" {}}}}}}
                :variantsByModel {"sonnet[-._]4[-._]6"
                                  {:variants {"low" {:output_config {:effort "low"}}
                                              "medium" {:output_config {:effort "medium"}}
                                              "high" {:output_config {:effort "high"}}
                                              "max" {:output_config {:effort "max"}}}}}
                :defaultAgent "code"
                :agent {"code" {:variant "high"}}})

    (handlers/chat-selected-model-changed (h/components)
                                          {:model "anthropic/claude-sonnet-4-6"})
    (is (match? {:config-updated [{:chat {:variants ["high" "medium"]
                                          :select-variant "high"}}]}
                (h/messages)))))
