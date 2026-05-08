(ns eca.handlers-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.tools :as f.tools]
   [eca.handlers :as handlers]
   [eca.models :as models]
   [eca.test-helper :as h]
   [matcher-combinators.matchers :as m]
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
             (#'config/all* @db*))))))

  (testing "snapshots initial-workspace-folders for stable cache key"
    (h/reset-components!)
    (let [workspace-folders [{:uri "file:///project/main" :name "main"}]]
      (with-redefs [db/load-db-from-cache! (constantly nil)]
        (handlers/initialize (h/components)
                             {:workspace-folders workspace-folders
                              :initialization-options {:pureConfig true}})
        (is (match? {:initial-workspace-folders workspace-folders
                     :workspace-folders workspace-folders}
                    (h/db)))))))

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

(deftest chat-selected-model-changed-per-chat-scoping-test
  (testing "When chat-id is provided, model+variant are persisted on that chat
            and the config/updated broadcast carries :chat-id"
    (h/reset-components!)
    (h/config! {:providers {"anthropic" {:models {"claude-sonnet-4-5"
                                                  {:variants {"low" {:a 1} "high" {:a 2}}}}}}
                :defaultAgent "code"
                :agent {"code" {:variant "high"}}})
    (swap! (h/db*) assoc :chats {"c1" {:id "c1"} "c2" {:id "c2"}})
    (handlers/chat-selected-model-changed
     (h/components)
     {:chat-id "c1"
      :model "anthropic/claude-sonnet-4-5"
      :variant "high"})
    (is (match? {"c1" {:id "c1"
                       :model "anthropic/claude-sonnet-4-5"
                       :variant "high"}
                 "c2" {:id "c2"
                       :model m/absent
                       :variant m/absent}}
                (:chats (h/db))))
    (is (match? {:config-updated [{:chat-id "c1"
                                   :chat {:variants ["high" "low"]
                                          :select-variant "high"}}]}
                (h/messages)))
    (is (empty? (:last-config-notified (h/db)))
        "per-chat path must NOT touch the session-wide last-config-notified mirror"))

  (testing "Without chat-id, the legacy session-wide path is used
            (no :chat-id in payload, session mirror updated)"
    (h/reset-components!)
    (h/config! {:providers {"anthropic" {:models {"claude-sonnet-4-5"
                                                  {:variants {"low" {:a 1} "high" {:a 2}}}}}}
                :defaultAgent "code"
                :agent {"code" {:variant "high"}}})
    (handlers/chat-selected-model-changed
     (h/components)
     {:model "anthropic/claude-sonnet-4-5"})
    (let [[broadcast] (:config-updated (h/messages))]
      (is (nil? (:chat-id broadcast)))
      (is (match? {:chat {:variants ["high" "low"]
                          :select-variant "high"}}
                  broadcast))))

  (testing "Per-chat broadcast does not mutate other chats' :model"
    (h/reset-components!)
    (h/config! {:providers {"anthropic" {:models {"claude-sonnet-4-5" {}
                                                  "claude-opus-4-6" {}}}}
                :defaultAgent "code"
                :agent {"code" {}}})
    (swap! (h/db*) assoc :chats {"c1" {:id "c1" :model "anthropic/claude-sonnet-4-5"}
                                 "c2" {:id "c2" :model "anthropic/claude-opus-4-6"}})
    (handlers/chat-selected-model-changed
     (h/components)
     {:chat-id "c1"
      :model "anthropic/claude-opus-4-6"})
    (is (= "anthropic/claude-opus-4-6" (get-in (h/db) [:chats "c1" :model])))
    (is (= "anthropic/claude-opus-4-6" (get-in (h/db) [:chats "c2" :model]))
        "c2's model is unchanged"))

  (testing "Unknown chat-id falls through to the legacy session-wide path
            (no per-chat broadcast for a chat the server doesn't recognize)"
    (h/reset-components!)
    (h/config! {:providers {"openai" {:models {"gpt-4.1" {}}}}
                :defaultAgent "code"
                :agent {"code" {}}})
    (handlers/chat-selected-model-changed
     (h/components)
     {:chat-id "ghost-chat"
      :model "openai/gpt-4.1"})
    (is (nil? (get-in (h/db) [:chats "ghost-chat"])))
    (let [[broadcast] (:config-updated (h/messages))]
      (is (nil? (:chat-id broadcast))
          "broadcast must NOT carry a chat-id for a chat that does not exist server-side")))

  (testing "Invalid chat-id (subagent- prefix) falls through to legacy session-wide path"
    (h/reset-components!)
    (h/config! {:providers {"openai" {:models {"gpt-4.1" {}}}}
                :defaultAgent "code"
                :agent {"code" {}}})
    (swap! (h/db*) assoc :chats {"subagent-pretender" {:id "subagent-pretender"}})
    (handlers/chat-selected-model-changed
     (h/components)
     {:chat-id "subagent-pretender" :model "openai/gpt-4.1"})
    (is (nil? (get-in (h/db) [:chats "subagent-pretender" :model]))
        "rejected ids must not mutate the chat record either")
    (let [[broadcast] (:config-updated (h/messages))]
      (is (nil? (:chat-id broadcast)))))

  (testing "Concurrent chat/delete cannot resurrect a deleted chat as a ghost record"
    (h/reset-components!)
    (h/config! {:providers {"openai" {:models {"gpt-4.1" {}}}}
                :defaultAgent "code"
                :agent {"code" {}}})
    ;; Simulate: chat existed, was deleted, then a model-change arrives for it.
    ;; The CAS swap must not recreate the entry as `{:model "..."}`.
    (handlers/chat-selected-model-changed
     (h/components)
     {:chat-id "deleted-c1" :model "openai/gpt-4.1"})
    (is (nil? (get-in (h/db) [:chats "deleted-c1"])))))

(deftest chat-selected-agent-changed-per-chat-scoping-test
  (testing "When chat-id is provided, the agent and the agent's defaultModel
            (and the agent's variant) are persisted on that chat and the
            broadcast is scoped"
    (h/reset-components!)
    (h/config! {:providers {"openai" {:models {"gpt-4.1" {:variants {"low" {:a 1} "high" {:a 2}}}}}}
                :agent {"plan" {:defaultModel "openai/gpt-4.1" :variant "high"}}})
    (swap! (h/db*) assoc :chats {"c1" {:id "c1"} "c2" {:id "c2"}})
    (handlers/chat-selected-agent-changed
     (h/components)
     {:chat-id "c1" :agent "plan"})
    (is (= "plan" (get-in (h/db) [:chats "c1" :agent])))
    (is (= "openai/gpt-4.1" (get-in (h/db) [:chats "c1" :model])))
    (is (= "high" (get-in (h/db) [:chats "c1" :variant]))
        "variant should be persisted alongside model so /resume restores it")
    (is (nil? (get-in (h/db) [:chats "c2" :agent])))
    (is (nil? (get-in (h/db) [:chats "c2" :model])))
    (is (nil? (get-in (h/db) [:chats "c2" :variant])))
    (is (match? {:config-updated [{:chat-id "c1"
                                   :chat {:select-model "openai/gpt-4.1"
                                          :select-variant "high"}}]
                 :tool-server-update [{}]}
                (h/messages))))

  (testing "Without chat-id, the legacy session-wide path is used
            (no :chat-id in payload, no per-chat persistence)"
    (h/reset-components!)
    (h/config! {:agent {"plan" {:defaultModel "openai/gpt-4.1"}}})
    (swap! (h/db*) assoc :chats {"c1" {:id "c1"}})
    (handlers/chat-selected-agent-changed
     (h/components)
     {:agent "plan"})
    (is (nil? (get-in (h/db) [:chats "c1" :agent])))
    (let [[broadcast] (:config-updated (h/messages))]
      (is (nil? (:chat-id broadcast)))
      (is (= "openai/gpt-4.1" (get-in broadcast [:chat :select-model])))))

  (testing "Unknown chat-id falls through to legacy session-wide path"
    (h/reset-components!)
    (h/config! {:agent {"plan" {:defaultModel "openai/gpt-4.1"}}})
    (handlers/chat-selected-agent-changed
     (h/components)
     {:chat-id "ghost-chat" :agent "plan"})
    (is (nil? (get-in (h/db) [:chats "ghost-chat"])))
    (let [[broadcast] (:config-updated (h/messages))]
      (is (nil? (:chat-id broadcast)))))

  (testing "Per-chat agent change does not mutate other chats' :agent / :model"
    (h/reset-components!)
    (h/config! {:agent {"plan" {:defaultModel "openai/gpt-4.1"}
                        "code" {:defaultModel "anthropic/claude-sonnet-4-5"}}})
    (swap! (h/db*) assoc :chats {"c1" {:id "c1" :agent "code" :model "anthropic/claude-sonnet-4-5"}
                                 "c2" {:id "c2" :agent "code" :model "anthropic/claude-sonnet-4-5"}})
    (handlers/chat-selected-agent-changed
     (h/components)
     {:chat-id "c1" :agent "plan"})
    (is (= "plan" (get-in (h/db) [:chats "c1" :agent])))
    (is (= "openai/gpt-4.1" (get-in (h/db) [:chats "c1" :model])))
    (is (= "code" (get-in (h/db) [:chats "c2" :agent])))
    (is (= "anthropic/claude-sonnet-4-5" (get-in (h/db) [:chats "c2" :model]))))

  (testing "Tool-server-update is emitted in both legacy and per-chat paths"
    (h/reset-components!)
    (h/config! {:agent {"plan" {:defaultModel "openai/gpt-4.1"}}})
    (swap! (h/db*) assoc :chats {"c1" {:id "c1"}})
    (handlers/chat-selected-agent-changed (h/components) {:chat-id "c1" :agent "plan"})
    (is (= 1 (count (:tool-server-update (h/messages)))))
    (h/reset-messenger!)
    (handlers/chat-selected-agent-changed (h/components) {:agent "plan"})
    (is (= 1 (count (:tool-server-update (h/messages)))))))

(defn ^:private seed-chats!
  "Seed the test db with a map of chats keyed by id."
  [chats]
  (swap! (h/db*) assoc :chats chats))

(deftest chat-list-test
  (testing "Returns empty list when db has no chats"
    (h/reset-components!)
    (is (match? {:chats []}
                (handlers/chat-list (h/components) {}))))

  (testing "Returns summary of all non-subagent chats"
    (h/reset-components!)
    (seed-chats!
     {"a" {:id "a" :title "First" :status :idle
           :created-at 100 :updated-at 300 :model "anthropic/claude"
           :messages [{:role "user" :content "hi"}
                      {:role "assistant" :content "hello"}]}
      "b" {:id "b" :title "Second" :status :idle
           :created-at 200 :updated-at 400
           :messages []}})
    (is (match? {:chats (m/in-any-order
                         [{:id "b" :title "Second" :status :idle
                           :created-at 200 :updated-at 400 :message-count 0}
                          {:id "a" :title "First" :status :idle
                           :created-at 100 :updated-at 300
                           :model "anthropic/claude" :message-count 2}])}
                (handlers/chat-list (h/components) {}))))

  (testing "Subagent chats are excluded"
    (h/reset-components!)
    (seed-chats!
     {"visible" {:id "visible" :title "Normal" :status :idle
                 :created-at 100 :updated-at 100 :messages []}
      "hidden" {:id "hidden" :title "Sub" :status :idle
                :created-at 200 :updated-at 200 :messages []
                :subagent true :parent-chat-id "visible"}})
    (let [{:keys [chats]} (handlers/chat-list (h/components) {})]
      (is (= 1 (count chats)))
      (is (= "visible" (:id (first chats))))))

  (testing "Sorted by :updated-at descending by default"
    (h/reset-components!)
    (seed-chats!
     {"old" {:id "old" :status :idle :created-at 10 :updated-at 100 :messages []}
      "mid" {:id "mid" :status :idle :created-at 20 :updated-at 200 :messages []}
      "new" {:id "new" :status :idle :created-at 30 :updated-at 300 :messages []}})
    (is (= ["new" "mid" "old"]
           (mapv :id (:chats (handlers/chat-list (h/components) {}))))))

  (testing "`limit` caps the number of returned chats after sorting"
    (h/reset-components!)
    (seed-chats!
     {"a" {:id "a" :status :idle :created-at 10 :updated-at 100 :messages []}
      "b" {:id "b" :status :idle :created-at 20 :updated-at 200 :messages []}
      "c" {:id "c" :status :idle :created-at 30 :updated-at 300 :messages []}})
    (is (= ["c" "b"]
           (mapv :id (:chats (handlers/chat-list (h/components) {:limit 2}))))))

  (testing "`sort-by :created-at` switches the sort key"
    (h/reset-components!)
    (seed-chats!
     {"x" {:id "x" :status :idle :created-at 30 :updated-at 100 :messages []}
      "y" {:id "y" :status :idle :created-at 20 :updated-at 200 :messages []}
      "z" {:id "z" :status :idle :created-at 10 :updated-at 300 :messages []}})
    (is (= ["x" "y" "z"]
           (mapv :id (:chats (handlers/chat-list (h/components)
                                                 {:sort-by :created-at})))))))

(deftest chat-open-test
  (testing "Unknown chat returns {:found? false} and emits no messages"
    (h/reset-components!)
    (is (match? {:found? false}
                (handlers/chat-open (h/components) {:chat-id "missing"})))
    (is (nil? (:chat-opened (h/messages)))))

  (testing "Subagent chat is treated as not found"
    (h/reset-components!)
    (seed-chats!
     {"sub" {:id "sub" :status :idle :subagent true :parent-chat-id "main"
             :messages [] :created-at 1 :updated-at 1}})
    (is (match? {:found? false}
                (handlers/chat-open (h/components) {:chat-id "sub"})))
    (is (nil? (:chat-opened (h/messages)))))

  (testing "Known chat emits chat/cleared + chat/opened and returns found? true"
    (h/reset-components!)
    (seed-chats!
     {"c1" {:id "c1" :title "Hello world" :status :idle
            :created-at 10 :updated-at 20
            :messages [{:role "user" :content [{:type :text :text "hi"}]}]}})
    (let [result (handlers/chat-open (h/components) {:chat-id "c1"})]
      (is (match? {:found? true
                   :chat-id "c1"
                   :title "Hello world"}
                  result))
      (is (match? {:chat-clear [{:chat-id "c1" :messages true}]
                   :chat-opened [{:chat-id "c1" :title "Hello world"}]}
                  (h/messages))))))

(deftest mcp-add-server-test
  (testing "duplicate name returns :error"
    (h/reset-components!)
    (h/config! {:mcpServers {"existing" {:url "https://x"}}})
    (let [result (handlers/mcp-add-server (h/components)
                                          {:name "existing"
                                           :command "bin"})]
      (is (match? {:error {:code "invalid_request"
                           :message #"already exists"}}
                  result))))

  (testing "missing transport returns :error"
    (h/reset-components!)
    (h/config! {:mcpServers {}})
    (let [result (handlers/mcp-add-server (h/components)
                                          {:name "s"})]
      (is (match? {:error {:code "invalid_request"
                           :message #"must specify :command.*or :url"}}
                  result))))

  (testing "conflicting :command and :url returns :error"
    (h/reset-components!)
    (h/config! {:mcpServers {}})
    (let [result (handlers/mcp-add-server (h/components)
                                          {:name "s"
                                           :command "bin"
                                           :url "https://x"})]
      (is (match? {:error {:code "invalid_request"
                           :message #"must not specify both"}}
                  result)))))

(deftest mcp-remove-server-test
  (testing "unknown server returns :error"
    (h/reset-components!)
    (h/config! {:mcpServers {}})
    (let [result (handlers/mcp-remove-server (h/components)
                                             {:name "ghost"})]
      (is (match? {:error {:code "invalid_request"
                           :message #"does not exist"}}
                  result)))))

(deftest mcp-update-server-extended-params-test
  (testing "accepts :env and :headers params (forwarded to f.tools/update-server!)"
    (h/reset-components!)
    (h/config! {:mcpServers {"s" {:command "bin"}}})
    (let [captured* (atom nil)]
      (with-redefs [f.tools/update-server!
                    (fn [server-name server-fields _db* _messenger _config _metrics]
                      (reset! captured* {:name server-name :fields server-fields}))]
        (handlers/mcp-update-server (h/components)
                                    {:name "s"
                                     :command "bin"
                                     :args ["-x"]
                                     :env {:FOO "bar"}
                                     :headers {:Authorization "Bearer x"}})
        (is (match? {:name "s"
                     :fields {:command "bin"
                              :args ["-x"]
                              :env {:FOO "bar"}
                              :headers {:Authorization "Bearer x"}}}
                    @captured*))))))
