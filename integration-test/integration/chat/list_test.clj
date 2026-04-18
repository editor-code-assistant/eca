(ns integration.chat.list-test
  "Exercises the `chat/list` and `chat/open` JSON-RPC methods end-to-end: creates
  a chat via a simple prompt, asks the server for the current chat list, then
  replays it via `chat/open` and asserts the server emits the expected
  `chat/cleared` + `chat/opened` + `chat/contentReceived` notifications."
  (:require
   [clojure.test :refer [deftest is testing]]
   [integration.eca :as eca]
   [integration.fixture :as fixture]
   [llm-mock.mocks :as llm.mocks]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(eca/clean-after-test)

(defn- drain-until-progress-finished!
  "Drain chat/contentReceived notifications for `chat-id` until we observe the
  finishing progress event. Used to wait for a prompt to settle."
  [chat-id]
  (loop []
    (let [n (eca/client-awaits-server-notification :chat/contentReceived)]
      (when-not (and (= chat-id (:chatId n))
                     (= "system" (:role n))
                     (= "progress" (get-in n [:content :type]))
                     (= "finished" (get-in n [:content :state])))
        (recur)))))

(deftest chat-list-and-open-test
  (testing "chat/list returns summaries and chat/open replays the chat"
    (eca/start-process!)

    (llm.mocks/set-case! :simple-text-0)

    (eca/request! (fixture/initialize-request))
    (eca/notify! (fixture/initialized-notification))

    (let [prompt-resp (eca/request! (fixture/chat-prompt-request
                                     {:model "openai/gpt-4.1"
                                      :message "Hello"}))
          chat-id (:chatId prompt-resp)]
      (is (string? chat-id))
      (drain-until-progress-finished! chat-id)

      (testing "chat/list includes the new chat"
        (let [result (eca/request! [:chat/list {}])]
          (is (match? {:chats (m/embeds
                               [{:id chat-id
                                 :status "idle"
                                 :messageCount (m/pred pos-int?)}])}
                      result))))

      (testing "chat/list honours :limit"
        (let [result (eca/request! [:chat/list {:limit 1}])]
          (is (= 1 (count (:chats result))))))

      (testing "chat/open replays chat/cleared + chat/opened + contentReceived"
        (let [open-resp (eca/request! [:chat/open {:chat-id chat-id}])
              cleared (eca/client-awaits-server-notification :chat/cleared)
              opened (eca/client-awaits-server-notification :chat/opened)]
          (is (match? {:found true :chatId chat-id} open-resp))
          (is (match? {:chatId chat-id :messages true} cleared))
          (is (match? {:chatId chat-id} opened))
          ;; At least one content-received notification must follow
          (is (match? {:chatId chat-id}
                      (eca/client-awaits-server-notification :chat/contentReceived)))))

      (testing "chat/open returns found=false for unknown chat ids"
        (let [result (eca/request! [:chat/open {:chat-id "does-not-exist"}])]
          (is (match? {:found false} result)))))))
