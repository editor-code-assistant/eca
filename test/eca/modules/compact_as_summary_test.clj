(ns eca.modules.compact-as-summary-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.modules.compact-as-summary :as m.compact-as-summary]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(def ^:private chat-id "c1")
(def ^:private agent-name :default)
(def ^:private full-model "openai/test")
(def ^:private config {:autoCompactPercentage 75})

(deftest compact-chat-test
  (testing "Successfully compacts a chat with summary"
    (let [db* (h/db*)
          chat-id "test-chat-123"
          test-summary "This is a summary of the chat conversation covering the main points discussed."]
      ;; Set up initial state - chat is compacting
      (swap! db* assoc-in [:chats chat-id :compacting?] true)
      
      (let [result ((get-in m.compact-as-summary/definitions ["compact_chat" :handler])
                    {"summary" test-summary}
                    {:db* db* :chat-id chat-id})]
        (testing "returns correct response format"
          (is (match?
               {:contents [{:type :text :text "Compacted successfully!"}]}
               result)))
        
        (testing "updates database state correctly"
          (let [chat-state (get-in @db* [:chats chat-id])]
            (is (= false (:compacting? chat-state))
                "Should set compacting? to false")
            (is (= test-summary (:last-summary chat-state))
                "Should save the summary as last-summary")
            (is (= true (:compact-done? chat-state))
                "Should set compact-done? to true"))))))

  (testing "Successfully compacts an auto-compacting chat"
    (let [db* (h/db*)
          chat-id "test-chat-auto-compacting"
          test-summary "Auto-compacted summary"]
      (swap! db* assoc-in [:chats chat-id :auto-compacting?] true)
      (let [result ((get-in m.compact-as-summary/definitions ["compact_chat" :handler])
                    {"summary" test-summary}
                    {:db* db* :chat-id chat-id})
            chat-state (get-in @db* [:chats chat-id])]
        (is (false? (:error result)))
        (is (= test-summary (:last-summary chat-state)))
        (is (true? (:compact-done? chat-state))))))

  (testing "Handles empty summary"
    (let [db* (h/db*)
          chat-id "test-chat-456"
          empty-summary ""]
      (swap! db* assoc-in [:chats chat-id :compacting?] true)
      
      (let [result ((get-in m.compact-as-summary/definitions ["compact_chat" :handler])
                    {"summary" empty-summary}
                    {:db* db* :chat-id chat-id})]
        (is (match?
             {:contents [{:type :text :text "Compacted successfully!"}]}
             result))
        
        (let [chat-state (get-in @db* [:chats chat-id])]
          (is (= false (:compacting? chat-state)))
          (is (= empty-summary (:last-summary chat-state)))
          (is (= true (:compact-done? chat-state))))))))

(deftest compact-chat-requires-active-compaction-test
  (let [db* (h/db*)
        chat-id "test-chat-not-compacting"
        handler (get-in m.compact-as-summary/definitions ["compact_chat" :handler])]
    (swap! db* assoc-in [:chats chat-id] {:id chat-id})
    (let [before @db*
          result (handler {"summary" "Must not be stored"}
                          {:db* db* :chat-id chat-id})]
      (is (match? {:error true
                   :contents [{:type :text
                               :text "Chat compaction is not active for this request. This tool is available only while chat compaction is in progress. To compact manually, the user must use the `/compact` command; compaction may also start automatically when context usage reaches the configured threshold."}]}
                  result))
      (is (= before @db*)
          "Inactive compact tool calls must not mutate chat state"))))

(deftest compact-chat-summary-fn-test
  (testing "Summary function returns constant string"
    (is (= "Compacting..." ((get-in m.compact-as-summary/definitions ["compact_chat" :summary-fn]) {})))))

(deftest compact-chat-tool-definition-test
  (testing "Tool definition has correct structure"
    (let [tool-def (get m.compact-as-summary/definitions "compact_chat")]
      (is (some? tool-def) "Tool definition should exist")
      (is (string? (:description tool-def)) "Should have a description")
      (is (map? (:parameters tool-def)) "Should have parameters")
      (is (or (fn? (:handler tool-def)) (var? (:handler tool-def))) "Should have a handler function or var")
      (is (not (contains? tool-def :enabled-fn))
          "Tool availability must not change the provider tool schema")
      (is (fn? (:summary-fn tool-def)) "Should have a summary-fn")))

  (testing "Tool parameters schema is correct"
    (let [params (get-in m.compact-as-summary/definitions ["compact_chat" :parameters])]
      (is (= "object" (:type params)))
      (is (contains? (:properties params) "summary"))
      (is (= "string" (get-in params [:properties "summary" :type])))
      (is (= ["summary"] (:required params))))))

(defn- db-with [usage model-caps]
  {:chats {chat-id {:usage usage}}
   :models {full-model model-caps}})

(deftest chat-past-threshold?-does-not-crash-on-zero-or-missing-context-limit
  (testing "zero context limit (e.g. models.dev returning 0 for image-only models) is treated as unknown and returns falsy"
    (let [db (db-with {:last-input-tokens 100 :last-output-tokens 50}
                      {:limit {:context 0 :output 0}})]
      (is (not (#'m.compact-as-summary/chat-past-threshold? chat-id agent-name full-model config db)))))

  (testing "nil context limit returns falsy"
    (let [db (db-with {:last-input-tokens 100 :last-output-tokens 50}
                      {:limit {:context nil :output nil}})]
      (is (not (#'m.compact-as-summary/chat-past-threshold? chat-id agent-name full-model config db)))))

  (testing "missing :limit map returns falsy"
    (let [db (db-with {:last-input-tokens 100 :last-output-tokens 50}
                      {})]
      (is (not (#'m.compact-as-summary/chat-past-threshold? chat-id agent-name full-model config db)))))

  (testing "unknown model (no entry in :models) returns falsy"
    (let [db {:chats {chat-id {:usage {:last-input-tokens 100 :last-output-tokens 50}}}}]
      (is (not (#'m.compact-as-summary/chat-past-threshold? chat-id agent-name full-model config db))))))

(deftest chat-past-threshold?-with-positive-context-limit
  (testing "returns truthy when usage meets/exceeds the configured threshold"
    (let [db (db-with {:last-input-tokens 800 :last-output-tokens 0}
                      {:limit {:context 1000 :output 1000}})]
      (is (#'m.compact-as-summary/chat-past-threshold? chat-id agent-name full-model config db))))

  (testing "returns false when usage is below the configured threshold"
    (let [db (db-with {:last-input-tokens 100 :last-output-tokens 0}
                      {:limit {:context 1000 :output 1000}})]
      (is (false? (#'m.compact-as-summary/chat-past-threshold? chat-id agent-name full-model config db)))))

  (testing "respects per-agent autoCompactPercentage override"
    (let [db (db-with {:last-input-tokens 500 :last-output-tokens 0}
                      {:limit {:context 1000 :output 1000}})]
      (is (#'m.compact-as-summary/chat-past-threshold? chat-id agent-name full-model
                                                       {:agent {agent-name {:autoCompactPercentage 40}}
                                                        :autoCompactPercentage 99}
                                                       db)))))

(deftest chat-past-threshold?-with-tokens-left
  (let [config {:autoCompactPercentage 75 :autoCompactTokensLeft 20000}
        big-model {:limit {:context 1000000 :output 32000}}]
    (testing "ignores the percentage while more than the configured tokens remain"
      (let [db (db-with {:last-input-tokens 800000 :last-output-tokens 0} big-model)]
        (is (false? (#'m.compact-as-summary/chat-past-threshold? chat-id agent-name full-model config db)))))

    (testing "triggers once the remaining tokens reach the configured value"
      (let [db (db-with {:last-input-tokens 980000 :last-output-tokens 0} big-model)]
        (is (#'m.compact-as-summary/chat-past-threshold? chat-id agent-name full-model config db)))
      (let [db (db-with {:last-input-tokens 985000 :last-output-tokens 0} big-model)]
        (is (#'m.compact-as-summary/chat-past-threshold? chat-id agent-name full-model config db))))

    (testing "respects per-agent autoCompactTokensLeft override"
      (let [db (db-with {:last-input-tokens 900000 :last-output-tokens 0} big-model)]
        (is (#'m.compact-as-summary/chat-past-threshold? chat-id agent-name full-model
                                                         (assoc config :agent {agent-name {:autoCompactTokensLeft 150000}})
                                                         db))))

    (testing "falls back to autoCompactPercentage when the value is not below the context limit"
      (let [small-model {:limit {:context 128000 :output 16000}}
            config (assoc config :autoCompactTokensLeft 200000)]
        (is (false? (#'m.compact-as-summary/chat-past-threshold? chat-id agent-name full-model config
                                                                 (db-with {:last-input-tokens 50000 :last-output-tokens 0} small-model))))
        (is (#'m.compact-as-summary/chat-past-threshold? chat-id agent-name full-model config
                                                         (db-with {:last-input-tokens 100000 :last-output-tokens 0} small-model)))))))

(deftest auto-compact-threshold-test
  (testing "returns the configured percentage when no absolute value is set"
    (is (= 75 (#'m.compact-as-summary/auto-compact-threshold config agent-name 1000000))))

  (testing "derives the effective percentage from autoCompactTokensLeft"
    (is (= 98.0 (#'m.compact-as-summary/auto-compact-threshold {:autoCompactTokensLeft 20000} agent-name 1000000)))
    (is (= 87.5 (#'m.compact-as-summary/auto-compact-threshold {:autoCompactPercentage 75 :autoCompactTokensLeft 25000}
                                                               agent-name 200000))))

  (testing "falls back to the percentage when the absolute value is not below the context limit"
    (is (= 75 (#'m.compact-as-summary/auto-compact-threshold {:autoCompactPercentage 75 :autoCompactTokensLeft 200000}
                                                             agent-name 128000))))

  (testing "nil when auto-compact is disabled"
    (is (nil? (#'m.compact-as-summary/auto-compact-threshold {} agent-name 1000000)))))

(deftest write-summary-clears-validated-path-rules-test
  (let [db* (atom {:chats {"chat-1" {:last-summary "Short summary"
                                     :messages []
                                     :validated-path-rules #{"/workspace/a/.eca/rules/format.md"}}}})]
    (#'m.compact-as-summary/write-summary! {:chat-id "chat-1"
                                            :full-model "openai/gpt-5.2"
                                            :db* db*
                                            :messenger (h/messenger)}
                                           false)
    (is (nil? (get-in @db* [:chats "chat-1" :validated-path-rules])))))
