(ns eca.features.tools.chat-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.tools.chat :as f.tools.chat]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(deftest compact-chat-test
  (testing "Successfully compacts a chat with summary"
    (let [db* (h/db*)
          chat-id "test-chat-123"
          test-summary "This is a summary of the chat conversation covering the main points discussed."]
      ;; Set up initial state - chat is compacting
      (swap! db* assoc-in [:chats chat-id :compacting?] true)
      
      (let [result ((get-in f.tools.chat/definitions ["eca_compact_chat" :handler])
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
                "Should save the summary as last-summary"))))))

  (testing "Handles empty summary"
    (let [db* (h/db*)
          chat-id "test-chat-456"
          empty-summary ""]
      (swap! db* assoc-in [:chats chat-id :compacting?] true)
      
      (let [result ((get-in f.tools.chat/definitions ["eca_compact_chat" :handler])
                    {"summary" empty-summary}
                    {:db* db* :chat-id chat-id})]
        (is (match?
             {:contents [{:type :text :text "Compacted successfully!"}]}
             result))
        
        (let [chat-state (get-in @db* [:chats chat-id])]
          (is (= false (:compacting? chat-state)))
          (is (= empty-summary (:last-summary chat-state))))))))

(deftest compact-chat-enabled-test
  (testing "Tool is enabled when chat is compacting"
    (let [db* (h/db*)
          chat-id "test-chat-compacting"]
      (swap! db* assoc-in [:chats chat-id :compacting?] true)
      
      (is (true? ((get-in f.tools.chat/definitions ["eca_compact_chat" :enabled-fn])
                  {:db @db* :chat-id chat-id})))))

  (testing "Tool is disabled when chat is not compacting"
    (let [db* (h/db*)
          chat-id "test-chat-not-compacting"]
      (swap! db* assoc-in [:chats chat-id :compacting?] false)
      
      (is (false? ((get-in f.tools.chat/definitions ["eca_compact_chat" :enabled-fn])
                   {:db @db* :chat-id chat-id})))))

  (testing "Tool is disabled when compacting? is not set (defaults to false)"
    (let [db* (h/db*)
          chat-id "test-chat-no-compacting-key"]
      ;; Don't set compacting? at all
      
      (is (false? ((get-in f.tools.chat/definitions ["eca_compact_chat" :enabled-fn])
                   {:db @db* :chat-id chat-id})))))

  (testing "Tool is disabled when chat doesn't exist"
    (let [db* (h/db*)
          chat-id "non-existent-chat"]
      
      (is (false? ((get-in f.tools.chat/definitions ["eca_compact_chat" :enabled-fn])
                   {:db @db* :chat-id chat-id}))))))

(deftest compact-chat-summary-fn-test
  (testing "Summary function returns constant string"
    (is (= "Compacting..." ((get-in f.tools.chat/definitions ["eca_compact_chat" :summary-fn]) {})))))

(deftest compact-chat-tool-definition-test
  (testing "Tool definition has correct structure"
    (let [tool-def (get f.tools.chat/definitions "eca_compact_chat")]
      (is (some? tool-def) "Tool definition should exist")
      (is (string? (:description tool-def)) "Should have a description")
      (is (map? (:parameters tool-def)) "Should have parameters")
      (is (or (fn? (:handler tool-def)) (var? (:handler tool-def))) "Should have a handler function or var")
      (is (fn? (:enabled-fn tool-def)) "Should have an enabled-fn")
      (is (fn? (:summary-fn tool-def)) "Should have a summary-fn")))

  (testing "Tool parameters schema is correct"
    (let [params (get-in f.tools.chat/definitions ["eca_compact_chat" :parameters])]
      (is (= "object" (:type params)))
      (is (contains? (:properties params) "summary"))
      (is (= "string" (get-in params [:properties "summary" :type])))
      (is (= ["summary"] (:required params))))))
