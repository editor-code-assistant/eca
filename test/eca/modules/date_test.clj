(ns eca.modules.date-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.api.test :as api.test]
   [eca.config :as config]
   [eca.features.chat :as f.chat]
   [eca.features.chat.ops :as f.chat.ops]
   [eca.features.tools :as f.tools]
   [eca.llm-api :as llm-api]
   [eca.modules.date :as m.date]
   [eca.test-helper :as h]))

(h/reset-components-before-test)

(defn ^:private today []
  (str "Current date: " (java.time.LocalDate/now)))

(defn ^:private date-block
  "A user message content block with the module's context for `text`, as
   ECA stores it."
  [text]
  {:type :text :text (str "<additionalContext>\n" text "\n</additionalContext>") :module :eca/date})

(defn ^:private before-request [messages]
  (api.test/run-event m.date/module :chat/before-request {:prompt "hi"}
                      (api.test/mock-ctx {:module m.date/module :messages messages})))

(deftest before-request-test
  (testing "a context window without a date gets today's"
    (is (= (today) (:additional-context (before-request [])))))

  (testing "a window that has today's date gets none"
    (is (nil? (:additional-context
               (before-request [{:role "user" :content [{:type :text :text "first"} (date-block (today))]}
                                {:role "assistant" :content [{:type :text :text "ok"}]}])))))

  (testing "an earlier day's date doesn't count"
    (is (= (today) (:additional-context
                    (before-request [{:role "user" :content [{:type :text :text "old"}
                                                             (date-block "Current date: 2020-01-01")]}])))))

  (testing "a date before the last compaction doesn't count"
    (is (= (today) (:additional-context
                    (before-request [{:role "user" :content [{:type :text :text "old"} (date-block (today))]}
                                     {:role "compact_marker" :content {:auto? false}}
                                     {:role "user" :content [{:type :text :text "Summary"}]}])))))

  (testing "today's date on the summary after the compaction counts"
    (is (nil? (:additional-context
               (before-request [{:role "compact_marker" :content {:auto? false}}
                                {:role "user" :content [{:type :text :text "Summary"} (date-block (today))]}])))))

  (testing "the same text from someone else, not the module, doesn't count"
    (is (= (today) (:additional-context
                    (before-request [{:role "user" :content [{:type :text :text (today)}]}]))))))

(deftest after-compact-test
  (testing "the summary gets today's date"
    (is (= (today) (:additional-context
                    (api.test/run-event m.date/module :chat/after-compact {:trigger :manual}))))))

(defn ^:private prompt!
  "Prompts through f.chat/prompt with a mocked LLM; returns the text of the
   user message the LLM got."
  [params]
  (let [sent* (atom nil)]
    (with-redefs [llm-api/sync-or-async-prompt! (fn [{:keys [user-messages on-first-response-received on-message-received]}]
                                                  (reset! sent* user-messages)
                                                  (on-first-response-received {:type :text :text "ok"})
                                                  (on-message-received {:type :text :text "ok"})
                                                  (on-message-received {:type :finish}))
                  llm-api/sync-prompt! (constantly nil)
                  f.tools/all-tools (constantly [])
                  config/await-plugins-resolved! (constantly true)]
      (swap! (h/db*) update :models #(merge {"openai/gpt-5.2" {:tools true}} %))
      (f.chat/prompt params (h/db*) (h/messenger) (h/config) (h/metrics)))
    (->> @sent* last :content (keep :text) (string/join "\n"))))

(deftest date-in-chat-test
  (testing "the first prompt of a chat carries today's date, the next one on the same day doesn't"
    (h/config! {:env "test"})
    (is (string/includes? (prompt! {:message "first" :chat-id "chat-1"}) (h/date-context)))
    (is (not (string/includes? (prompt! {:message "second" :chat-id "chat-1"}) "Current date:"))))

  (testing "a rollback to before the date adds it again"
    (h/reset-components!)
    (h/config! {:env "test"})
    (prompt! {:message "first" :chat-id "chat-1"})
    (let [content-id (get-in (h/db) [:chats "chat-1" :messages 0 :content-id])]
      (f.chat.ops/rollback-chat {:chat-id "chat-1" :content-id content-id :include ["messages"]}
                                (h/db*) (h/messenger) (h/metrics)))
    (is (empty? (get-in (h/db) [:chats "chat-1" :messages])))
    (is (string/includes? (prompt! {:message "again" :chat-id "chat-1"}) (h/date-context))))

  (testing "turned off, the module adds no date"
    (h/reset-components!)
    (h/config! {:env "test" :modules {:eca/date {:enabled false}}})
    (is (not (string/includes? (prompt! {:message "first" :chat-id "chat-1"}) "Current date:")))))
