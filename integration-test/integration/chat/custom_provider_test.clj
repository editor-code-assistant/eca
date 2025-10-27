(ns integration.chat.custom-provider-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [integration.eca :as eca]
   [integration.fixture :as fixture]
   [integration.helper :refer [match-content] :as h]
   [llm-mock.mocks :as llm.mocks]
   [llm-mock.server :as llm-mock.server]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(eca/clean-after-test)

(deftest simple-text
  (eca/start-process!)

  (testing "We use the default model from custom provider"
    (is (match?
         {:models (m/embeds ["my-provider/foo1"])
          :chatDefaultModel "my-provider/foo1"}
         (eca/request! (fixture/initialize-request
                        {:initializationOptions
                         (merge fixture/default-init-options
                                {:defaultModel "my-provider/foo1"
                                 :providers
                                 {"myProvider"
                                  {:api "openai-responses"
                                   :url (str "http://localhost:" llm-mock.server/port "/openai")
                                   :key "foobar"
                                   :models {"foo0" {}
                                            "foo1" {}}}}})
                         :capabilities {:codeAssistant {:chat {}}}})))))
  (eca/notify! (fixture/initialized-notification))
  (let [chat-id* (atom nil)]
    (testing "We send a simple hello message"
      (llm.mocks/set-case! :simple-text-0)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:model "my-provider/foo1"
                                 :message "Tell me a joke!"}))
            chat-id (reset! chat-id* (:chatId resp))]

        (is (match?
             {:chatId (m/pred string?)
              :model "my-provider/foo1"
              :status "prompting"}
             resp))

        (match-content chat-id "user" {:type "text" :text "Tell me a joke!\n"})
        (match-content chat-id "system" {:type "metadata" :title "Some Cool Title"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "text" :text "Knock"})
        (match-content chat-id "assistant" {:type "text" :text " knock!"})
        (match-content chat-id "system" {:type "usage"
                                         :sessionTokens 30
                                         :lastMessageCost m/absent
                                         :sessionCost m/absent})
        (match-content chat-id "system" {:type "progress" :state "finished"})
        (is (match?
             {:input [{:role "user" :content [{:type "input_text" :text "Tell me a joke!"}]}]
              :instructions (m/pred string?)}
             (llm.mocks/get-req-body :simple-text-0)))))

    (testing "We reply"
      (llm.mocks/set-case! :simple-text-1)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:chat-id @chat-id*
                                 :model "my-provider/foo1"
                                 :message "Who's there?"}))
            chat-id @chat-id*]

        (is (match?
             {:chatId (m/pred string?)
              :model "my-provider/foo1"
              :status "prompting"}
             resp))

        (match-content chat-id "user" {:type "text" :text "Who's there?\n"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "text" :text "Foo"})
        (match-content chat-id "system" {:type "usage"
                                         :sessionTokens 15
                                         :lastMessageCost m/absent
                                         :sessionCost m/absent})
        (match-content chat-id "system" {:type "progress" :state "finished"})
        (is (match?
             {:input [{:role "user" :content [{:type "input_text" :text "Tell me a joke!"}]}
                      {:role "assistant" :content [{:type "output_text" :text "Knock knock!"}]}
                      {:role "user" :content [{:type "input_text" :text "Who's there?"}]}]}
             (llm.mocks/get-req-body :simple-text-1)))))

    (testing "model reply again keeping context"
      (llm.mocks/set-case! :simple-text-2)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:chat-id @chat-id*
                                 :model "my-provider/foo1"
                                 :message "What foo?"}))
            chat-id @chat-id*]

        (is (match?
             {:chatId (m/pred string?)
              :model "my-provider/foo1"
              :status "prompting"}
             resp))

        (match-content chat-id "user" {:type "text" :text "What foo?\n"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "text" :text "Foo"})
        (match-content chat-id "assistant" {:type "text" :text " bar!"})
        (match-content chat-id "assistant" {:type "text" :text "\n\n"})
        (match-content chat-id "assistant" {:type "text" :text "Ha!"})
        (match-content chat-id "system" {:type "usage"
                                         :sessionTokens 20
                                         :lastMessageCost m/absent
                                         :sessionCost m/absent})
        (match-content chat-id "system" {:type "progress" :state "finished"})
        (is (match?
             {:input [{:role "user" :content [{:type "input_text" :text "Tell me a joke!"}]}
                      {:role "assistant" :content [{:type "output_text" :text "Knock knock!"}]}
                      {:role "user" :content [{:type "input_text" :text "Who's there?"}]}
                      {:role "assistant" :content [{:type "output_text" :text "Foo"}]}
                      {:role "user" :content [{:type "input_text" :text "What foo?"}]}]}
             (llm.mocks/get-req-body :simple-text-2)))))))

(deftest openai-chat-simple-text
  (eca/start-process!)

  (testing "We use the default model from custom provider"
    (is (match?
         {:models (m/embeds ["my-provider/deepseekcoder"])
          :chatDefaultModel "my-provider/deepseekcoder"}
         (eca/request! (fixture/initialize-request
                        {:initializationOptions
                         (merge fixture/default-init-options
                                {:defaultModel "my-provider/deepseekcoder"
                                 :providers
                                 {"myProvider"
                                  {:api "openai-chat"
                                   :url (str "http://localhost:" llm-mock.server/port "/openai-chat")
                                   :key "foobar"
                                   :models {"deepseekchat" {}
                                            "deepseekcoder" {}}}}})
                         :capabilities {:codeAssistant {:chat {}}}})))))
  (eca/notify! (fixture/initialized-notification))
  (let [chat-id* (atom nil)]
    (testing "We send a simple hello message"
      (llm.mocks/set-case! :simple-text-0)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:model "my-provider/deepseekcoder"
                                 :message "Tell me a joke!"}))
            chat-id (reset! chat-id* (:chatId resp))]

        (is (match?
             {:chatId (m/pred string?)
              :model "my-provider/deepseekcoder"
              :status "prompting"}
             resp))

        (match-content chat-id "user" {:type "text" :text "Tell me a joke!\n"})
        (match-content chat-id "system" {:type "metadata" :title "Some Cool Title"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "text" :text "Knock "})
        (match-content chat-id "system" {:type "usage"})
        (match-content chat-id "assistant" {:type "text" :text "knock!"})
        (match-content chat-id "system" {:type "progress" :state "finished"})
        (is (match?
             {:input [{:role "user" :content [{:type "input_text" :text "Tell me a joke!"}]}]
              :instructions (m/pred string?)}
             (llm.mocks/get-req-body :simple-text-0)))))

    (testing "We reply"
      (llm.mocks/set-case! :simple-text-1)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:chat-id @chat-id*
                                 :model "my-provider/deepseekcoder"
                                 :message "Who's there?"}))
            chat-id @chat-id*]

        (is (match?
             {:chatId (m/pred string?)
              :model "my-provider/deepseekcoder"
              :status "prompting"}
             resp))

        (match-content chat-id "user" {:type "text" :text "Who's there?\n"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "usage"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "text" :text "Foo"})
        (match-content chat-id "system" {:type "progress" :state "finished"})
        (is (match?
             {:input [{:role "user" :content [{:type "input_text" :text "Tell me a joke!"}]}
                      {:role "assistant" :content [{:type "output_text" :text "Knock knock!"}]}
                      {:role "user" :content [{:type "input_text" :text "Who's there?"}]}]}
             (llm.mocks/get-req-body :simple-text-1)))))

    (testing "model reply again keeping context"
      (llm.mocks/set-case! :simple-text-2)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:chat-id @chat-id*
                                 :model "my-provider/deepseekcoder"
                                 :message "What foo?"}))
            chat-id @chat-id*]

        (is (match?
             {:chatId (m/pred string?)
              :model "my-provider/deepseekcoder"
              :status "prompting"}
             resp))

        (match-content chat-id "user" {:type "text" :text "What foo?\n"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "text" :text "Fo"})
        (match-content chat-id "assistant" {:type "text" :text "o "})
        (match-content chat-id "assistant" {:type "text" :text "bar"})
        (match-content chat-id "system" {:type "usage"})
        (match-content chat-id "assistant" {:type "text" :text "!\n\nHa!"})
        (match-content chat-id "system" {:type "progress" :state "finished"})
        (is (match?
             {:input [{:role "user" :content [{:type "input_text" :text "Tell me a joke!"}]}
                      {:role "assistant" :content [{:type "output_text" :text "Knock knock!"}]}
                      {:role "user" :content [{:type "input_text" :text "Who's there?"}]}
                      {:role "assistant" :content [{:type "output_text" :text "Foo"}]}
                      {:role "user" :content [{:type "input_text" :text "What foo?"}]}]}
             (llm.mocks/get-req-body :simple-text-2)))))))
