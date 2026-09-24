(ns eca.features.tools.chat-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.chat.title :as chat.title]
   [eca.features.tools :as f.tools]
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
      
      (let [result ((get-in f.tools.chat/definitions ["compact_chat" :handler])
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
      (let [result ((get-in f.tools.chat/definitions ["compact_chat" :handler])
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
      
      (let [result ((get-in f.tools.chat/definitions ["compact_chat" :handler])
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
        handler (get-in f.tools.chat/definitions ["compact_chat" :handler])]
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
    (is (= "Compacting..." ((get-in f.tools.chat/definitions ["compact_chat" :summary-fn]) {})))))

(deftest compact-chat-tool-definition-test
  (testing "Tool definition has correct structure"
    (let [tool-def (get f.tools.chat/definitions "compact_chat")]
      (is (some? tool-def) "Tool definition should exist")
      (is (string? (:description tool-def)) "Should have a description")
      (is (map? (:parameters tool-def)) "Should have parameters")
      (is (or (fn? (:handler tool-def)) (var? (:handler tool-def))) "Should have a handler function or var")
      (is (not (contains? tool-def :enabled-fn))
          "Tool availability must not change the provider tool schema")
      (is (fn? (:summary-fn tool-def)) "Should have a summary-fn")))

  (testing "Tool parameters schema is correct"
    (let [params (get-in f.tools.chat/definitions ["compact_chat" :parameters])]
      (is (= "object" (:type params)))
      (is (contains? (:properties params) "summary"))
      (is (= "string" (get-in params [:properties "summary" :type])))
      (is (= ["summary"] (:required params))))))

(deftest rename-chat-title-test
  (testing "renames the current chat and notifies clients"
    (let [db* (h/db*)
          chat-id "rename-chat"
          saved* (atom nil)
          handler (get-in f.tools.chat/definitions ["rename_chat_title" :handler])]
      (swap! db* assoc-in [:chats chat-id] {:id chat-id :title "Old title"})
      (with-redefs [db/save-chat! (fn [db chat-id metrics]
                                    (reset! saved* {:db db
                                                    :chat-id chat-id
                                                    :metrics metrics}))]
        (let [result (handler {"title" "New title\nignored"}
                              {:db* db*
                               :chat-id chat-id
                               :messenger (h/messenger)
                               :metrics (h/metrics)})]
          (is (match? {:error false
                       :contents [{:type :text
                                   :text "Chat title renamed to: New title"}]}
                      result))
          (is (= "New title" (get-in @db* [:chats chat-id :title])))
          (is (true? (get-in @db* [:chats chat-id :title-custom?])))
          (is (match? {:chat-id chat-id
                       :role "system"
                       :content {:type :metadata
                                 :title "New title"}}
                      (-> (h/messages) :chat-content-received first)))
          (is (= chat-id (:chat-id @saved*)))
          (is (= "New title" (get-in @saved* [:db :chats chat-id :title])))
          (is (true? (get-in @saved* [:db :chats chat-id :title-custom?])))))))

  (testing "rejects missing or blank titles without mutating state"
    (let [handler (get-in f.tools.chat/definitions ["rename_chat_title" :handler])
          cases [{"title" ""} {"title" "  \n  "} {"title" 42} {}]]
      (doseq [arguments cases]
        (h/reset-components!)
        (let [db* (h/db*)
              chat-id "rename-chat"]
          (swap! db* assoc-in [:chats chat-id] {:id chat-id :title "Old title"})
          (let [before @db*
                result (handler arguments
                                {:db* db*
                                 :chat-id chat-id
                                 :messenger (h/messenger)
                                 :metrics (h/metrics)})]
            (is (match? {:error true
                         :contents [{:type :text
                                     :text "INVALID_ARGS: title is required and must not be blank."}]}
                        result))
            (is (= before @db*))
            (is (nil? (:chat-content-received (h/messages)))))))))

  (testing "returns an error when the chat is missing"
    (let [db* (h/db*)
          handler (get-in f.tools.chat/definitions ["rename_chat_title" :handler])
          result (handler {"title" "New title"}
                          {:db* db*
                           :chat-id "missing-chat"
                           :messenger (h/messenger)
                           :metrics (h/metrics)})]
      (is (match? {:error true
                   :contents [{:type :text
                               :text "Chat not found."}]}
                  result))
      (is (nil? (:chat-content-received (h/messages))))))

  (testing "returns an error when the chat disappears during rename"
    (let [db* (h/db*)
          chat-id "rename-chat"
          handler (get-in f.tools.chat/definitions ["rename_chat_title" :handler])]
      (swap! db* assoc-in [:chats chat-id] {:id chat-id :title "Old title"})
      (with-redefs [chat.title/update-chat-title! (fn [& _] nil)]
        (let [result (handler {"title" "New title"}
                              {:db* db*
                               :chat-id chat-id
                               :messenger (h/messenger)
                               :metrics (h/metrics)})]
          (is (match? {:error true
                       :contents [{:type :text
                                   :text "Chat not found."}]}
                      result)))))))

(deftest rename-chat-title-tool-definition-test
  (testing "Tool definition has correct structure"
    (let [tool-def (get f.tools.chat/definitions "rename_chat_title")]
      (is (some? tool-def) "Tool definition should exist")
      (is (string? (:description tool-def)) "Should have a description")
      (is (map? (:parameters tool-def)) "Should have parameters")
      (is (or (fn? (:handler tool-def)) (var? (:handler tool-def))) "Should have a handler function or var")
      (is (not (contains? tool-def :enabled-fn))
          "Tool availability must not change the provider tool schema")
      (is (fn? (:summary-fn tool-def)) "Should have a summary-fn")
      (is (= "Renaming chat"
             ((:summary-fn tool-def) {:args {"title" 42}})))))

  (testing "Tool parameters schema is correct"
    (let [params (get-in f.tools.chat/definitions ["rename_chat_title" :parameters])]
      (is (= "object" (:type params)))
      (is (contains? (:properties params) "title"))
      (is (= "string" (get-in params [:properties "title" :type])))
      (is (= ["title"] (:required params))))))

(deftest rename-chat-title-asks-by-default-test
  (let [db {:chats {"rename-chat" {:id "rename-chat"}}}
        all-tools (f.tools/all-tools "rename-chat" "code" db config/initial-config)
        tool (f.tools/resolve-tool "eca__rename_chat_title" all-tools)]
    (is (some? tool))
    (is (= :ask
           (f.tools/approval all-tools tool {"title" "New title"}
                             db config/initial-config "code")))))

(deftest rename-chat-title-hidden-from-subagents-test
  (let [db {:chats {"subagent-chat" {:id "subagent-chat"
                                      :parent-chat-id "parent-chat"
                                      :subagent {}}}}
        all-tools (f.tools/all-tools "subagent-chat" "general" db config/initial-config)]
    (is (nil? (f.tools/resolve-tool "eca__rename_chat_title" all-tools)))))
