(ns integration.chat.rollback-test
  "Exercises `chat/rollback` end-to-end over JSON-RPC: a null or unknown
  `contentId` is answered with an error and leaves the history intact (#669),
  the contentId of a flag rolls the chat back to the flag, keeping it, while
  the contentId of a user message rolls the chat back to before it."
  (:require
   [clojure.test :refer [deftest is testing]]
   [integration.eca :as eca]
   [integration.fixture :as fixture]
   [llm-mock.mocks :as llm.mocks]
   [matcher-combinators.test :refer [match?]]))

(eca/clean-after-test)

(defn- drain-until-progress-finished!
  "Drain chat/contentReceived notifications for `chat-id` until the prompt
  finishes, returning the contentId of the user message echoed on the way."
  [chat-id]
  (loop [user-content-id nil]
    (let [n (eca/client-awaits-server-notification :chat/contentReceived)
          own? (= chat-id (:chatId n))
          user-content-id (or user-content-id
                              (when (and own? (= "user" (:role n)))
                                (get-in n [:content :contentId])))]
      (if (and own?
               (= "system" (:role n))
               (= "progress" (get-in n [:content :type]))
               (= "finished" (get-in n [:content :state])))
        user-content-id
        (recur user-content-id)))))

(defn- history [chat-id]
  (:contents (eca/request! [:chat/history {:chat-id chat-id}])))

(deftest chat-rollback-test
  (eca/start-process!)

  (llm.mocks/set-case! :simple-text-0)

  (eca/request! (fixture/initialize-request))
  (eca/notify! (fixture/initialized-notification))

  (let [chat-id (:chatId (eca/request! (fixture/chat-prompt-request
                                        {:model "openai/gpt-4.1"
                                         :message "Hello"})))
        user-content-id (drain-until-progress-finished! chat-id)
        history-before (history chat-id)]
    (is (string? user-content-id))
    (is (match? [{:role "user" :content {:contentId user-content-id}}
                 {:role "assistant" :content {:type "text"}}
                 {:role "system" :content {:type "turnUsage" :model "openai/gpt-4.1"}}]
                history-before))

    (testing "a null contentId is rejected and the history is kept"
      (is (match? {:error {:code "invalid_request"}}
                  (eca/request! [:chat/rollback {:chat-id chat-id
                                                 :content-id nil
                                                 :include ["messages" "tools"]}])))
      (is (= history-before (history chat-id))))

    (testing "an unknown contentId is rejected and the history is kept"
      (is (match? {:error {:code "content_not_found"}}
                  (eca/request! [:chat/rollback {:chat-id chat-id
                                                 :content-id "missing"
                                                 :include ["messages"]}])))
      (is (= history-before (history chat-id))))

    (testing "the contentId of a flag rolls the chat back to the flag, keeping it"
      (is (= {} (eca/request! [:chat/addFlag {:chat-id chat-id
                                              :content-id user-content-id
                                              :text "checkpoint"}])))
      (is (match? {:chatId chat-id :messages true}
                  (eca/client-awaits-server-notification :chat/cleared)))
      (let [flag-id (some #(when (= "flag" (get-in % [:content :type]))
                             (get-in % [:content :contentId]))
                          (history chat-id))]
        (is (string? flag-id))
        (is (= {} (eca/request! [:chat/rollback {:chat-id chat-id
                                                 :content-id flag-id
                                                 :include ["messages"]}])))
        (is (match? {:chatId chat-id :messages true}
                    (eca/client-awaits-server-notification :chat/cleared)))
        (is (match? [{:role "user" :content {:contentId user-content-id}}
                     {:role "system" :content {:type "flag" :text "checkpoint" :contentId flag-id}}]
                    (history chat-id)))))

    (testing "the user message contentId rolls the chat back"
      (is (= {} (eca/request! [:chat/rollback {:chat-id chat-id
                                               :content-id user-content-id
                                               :include ["messages"]}])))
      (is (match? {:chatId chat-id :messages true}
                  (eca/client-awaits-server-notification :chat/cleared)))
      (is (= [] (history chat-id))))))
