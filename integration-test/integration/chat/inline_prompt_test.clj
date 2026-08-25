(ns integration.chat.inline-prompt-test
  "Exercises the `chat/inlinePrompt` JSON-RPC method end-to-end: creates an
  inline chat with a client-minted id announced via `chat/opened`, follows it
  up keeping history, forks an existing chat server-side without replaying the
  copied history, applies `chatInline` config defaults, marks the chat kind on
  `chat/list` and rejects invalid params."
  (:require
   [clojure.test :refer [deftest is testing]]
   [integration.eca :as eca]
   [integration.fixture :as fixture]
   [integration.helper :refer [match-content]]
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

(deftest inline-prompt-new-chat-and-follow-up
  (eca/start-process!)

  (eca/request! (fixture/initialize-request))
  (eca/notify! (fixture/initialized-notification))

  (testing "first inline prompt creates the chat and streams like a regular chat"
    (llm.mocks/set-case! :simple-text-0)
    (let [resp (eca/request! (fixture/chat-inline-prompt-request
                              {:chatId "inline-1"
                               :model "openai/gpt-5.2"
                               :message "Tell me a joke!"}))]
      (is (match?
           {:chatId "inline-1"
            :model "openai/gpt-5.2"
            :status "prompting"}
           resp))

      ;; The new inline chat is announced with a title derived from the message.
      (is (match?
           {:chatId "inline-1"
            :title "inline: Tell me a joke!"}
           (eca/client-awaits-server-notification :chat/opened)))

      (match-content "inline-1" "user" {:type "text" :text "Tell me a joke!\n"})
      ;; No :metadata title content here: unlike regular chats, inline chats
      ;; are born titled from the message, so no title is generated.
      (match-content "inline-1" "system" {:type "progress" :state "running" :text "Waiting model"})
      (match-content "inline-1" "system" {:type "progress" :state "running" :text "Generating"})
      (match-content "inline-1" "assistant" {:type "text" :text "Knock"})
      (match-content "inline-1" "assistant" {:type "text" :text " knock!"})
      (match-content "inline-1" "system" {:type "usage" :sessionTokens 30})
      (match-content "inline-1" "system" {:type "progress" :state "finished"})

      (is (match?
           {:input [{:role "user" :content [{:type "input_text" :text "Tell me a joke!"}]}]
            :instructions (m/pred string?)}
           (llm.mocks/get-req-body :simple-text-0)))))

  (testing "a follow-up on the same chat id keeps the history"
    (llm.mocks/set-case! :simple-text-1)
    (let [resp (eca/request! (fixture/chat-inline-prompt-request
                              {:chatId "inline-1"
                               :model "openai/gpt-5.2"
                               :message "Who's there?"}))]
      (is (match?
           {:chatId "inline-1"
            :model "openai/gpt-5.2"
            :status "prompting"}
           resp))
      (drain-until-progress-finished! "inline-1")
      (is (match?
           {:input [{:role "user" :content [{:type "input_text" :text "Tell me a joke!"}]}
                    {:role "assistant" :content [{:type "output_text" :text "Knock knock!"}]}
                    {:role "user" :content [{:type "input_text" :text "Who's there?"}]}]}
           (llm.mocks/get-req-body :simple-text-1)))))

  (testing "chat/list marks the chat as inline"
    (is (match?
         {:chats (m/embeds [{:id "inline-1"
                             :kind "inline"
                             :messageCount (m/pred pos-int?)}])}
         (eca/request! [:chat/list {}])))))

(deftest inline-prompt-fork
  (eca/start-process!)

  (eca/request! (fixture/initialize-request))
  (eca/notify! (fixture/initialized-notification))

  (llm.mocks/set-case! :simple-text-0)
  (let [source-resp (eca/request! (fixture/chat-prompt-request
                                   {:model "openai/gpt-5.2"
                                    :message "Tell me a joke!"}))
        source-chat-id (:chatId source-resp)]
    (is (string? source-chat-id))
    (drain-until-progress-finished! source-chat-id)

    (testing "an inline prompt forking the source chat streams only the new turn"
      (llm.mocks/set-case! :simple-text-1)
      (let [resp (eca/request! (fixture/chat-inline-prompt-request
                                {:chatId "inline-fork-1"
                                 :sourceChatId source-chat-id
                                 :model "openai/gpt-5.2"
                                 :message "Who's there?"}))]
        (is (match?
             {:chatId "inline-fork-1"
              :model "openai/gpt-5.2"
              :status "prompting"}
             resp))
        (is (match?
             {:chatId "inline-fork-1"
              :title "inline: Who's there?"}
             (eca/client-awaits-server-notification :chat/opened)))

        ;; The copied history is never replayed to the client: the very first
        ;; content for the inline chat is the new user message.
        (match-content "inline-fork-1" "user" {:type "text" :text "Who's there?\n"})
        (drain-until-progress-finished! "inline-fork-1")

        ;; But it is sent to the LLM.
        (is (match?
             {:input [{:role "user" :content [{:type "input_text" :text "Tell me a joke!"}]}
                      {:role "assistant" :content [{:type "output_text" :text "Knock knock!"}]}
                      {:role "user" :content [{:type "input_text" :text "Who's there?"}]}]}
             (llm.mocks/get-req-body :simple-text-1)))))

    (testing "the source chat is untouched and only the fork is inline-kind"
      (is (match?
           {:chats (m/embeds [{:id source-chat-id
                               :kind m/absent}
                              {:id "inline-fork-1"
                               :kind "inline"}])}
           (eca/request! [:chat/list {}]))))))

(deftest inline-prompt-config-defaults
  (eca/start-process!)

  (let [init-opts (assoc fixture/default-init-options
                         :chatInline {:model "openai/gpt-4.1"})]
    (eca/request! (fixture/initialize-request {:initializationOptions init-opts
                                               :capabilities {:codeAssistant {:chat {}}}})))
  (eca/notify! (fixture/initialized-notification))

  (testing "new inline chats default to the chatInline config model"
    (llm.mocks/set-case! :simple-text-0)
    (let [resp (eca/request! (fixture/chat-inline-prompt-request
                              {:chatId "inline-cfg-1"
                               :message "Explain this code"}))]
      (is (match?
           {:chatId "inline-cfg-1"
            :model "openai/gpt-4.1"
            :status "prompting"}
           resp))
      (drain-until-progress-finished! "inline-cfg-1"))))

(deftest inline-prompt-invalid-params
  (eca/start-process!)

  (eca/request! (fixture/initialize-request))
  (eca/notify! (fixture/initialized-notification))

  (testing "a blank message is rejected"
    (is (match?
         {:model "error"
          :status "error"}
         (eca/request! (fixture/chat-inline-prompt-request
                        {:chatId "inline-bad-1"
                         :message "   "})))))

  (testing "the reserved subagent chat id prefix is rejected"
    (is (match?
         {:model "error"
          :status "error"}
         (eca/request! (fixture/chat-inline-prompt-request
                        {:chatId "subagent-nope"
                         :message "hi"}))))))
