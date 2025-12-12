(ns integration.chat.openai-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [integration.eca :as eca]
   [integration.fixture :as fixture]
   [integration.helper :refer [match-content] :as h]
   [llm-mock.mocks :as llm.mocks]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(eca/clean-after-test)

(deftest simple-text
  (eca/start-process!)

  (eca/request! (fixture/initialize-request))
  (eca/notify! (fixture/initialized-notification))
  (let [chat-id* (atom nil)]
    (testing "We send a simple hello message"
      (llm.mocks/set-case! :simple-text-0)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:model "openai/gpt-4.1"
                                 :message "Tell me a joke!"}))
            chat-id (reset! chat-id* (:chatId resp))]

        (is (match?
             {:chatId (m/pred string?)
              :model "openai/gpt-4.1"
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
                                         :lastMessageCost (m/pred string?)
                                         :sessionCost (m/pred string?)})
        (match-content chat-id "system" {:type "progress" :state "finished"})
        (is (match?
             {:input [{:role "user" :content [{:type "input_text" :text "Tell me a joke!"}]}]
              :instructions (m/pred string?)}
             (llm.mocks/get-req-body :simple-text-0)))))

    (testing "We reply"
      (llm.mocks/set-case! :simple-text-1)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:chat-id @chat-id*
                                 :model "openai/gpt-4.1"
                                 :message "Who's there?"}))
            chat-id @chat-id*]

        (is (match?
             {:chatId (m/pred string?)
              :model "openai/gpt-4.1"
              :status "prompting"}
             resp))

        (match-content chat-id "user" {:type "text" :text "Who's there?\n"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "text" :text "Foo"})
        (match-content chat-id "system" {:type "usage"
                                         :sessionTokens 15
                                         :lastMessageCost (m/pred string?)
                                         :sessionCost (m/pred string?)})
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
                                 :model "openai/gpt-4.1"
                                 :message "What foo?"}))
            chat-id @chat-id*]

        (is (match?
             {:chatId (m/pred string?)
              :model "openai/gpt-4.1"
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
                                         :lastMessageCost (m/pred string?)
                                         :sessionCost (m/pred string?)})
        (match-content chat-id "system" {:type "progress" :state "finished"})
        (is (match?
             {:input [{:role "user" :content [{:type "input_text" :text "Tell me a joke!"}]}
                      {:role "assistant" :content [{:type "output_text" :text "Knock knock!"}]}
                      {:role "user" :content [{:type "input_text" :text "Who's there?"}]}
                      {:role "assistant" :content [{:type "output_text" :text "Foo"}]}
                      {:role "user" :content [{:type "input_text" :text "What foo?"}]}]}
             (llm.mocks/get-req-body :simple-text-2)))))))

(deftest reasoning-text
  (eca/start-process!)

  (eca/request! (fixture/initialize-request))
  (eca/notify! (fixture/initialized-notification))
  (let [chat-id* (atom nil)]
    (testing "We send a hello message"
      (llm.mocks/set-case! :reasoning-0)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:model "openai/gpt-5"
                                 :message "hello!"}))
            chat-id (reset! chat-id* (:chatId resp))]

        (is (match?
             {:chatId (m/pred string?)
              :model "openai/gpt-5"
              :status "prompting"}
             resp))

        (match-content chat-id "user" {:type "text" :text "hello!\n"})
        (match-content chat-id "system" {:type "metadata" :title "Some Cool Title"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "reasonStarted" :id "123"})
        (match-content chat-id "assistant" {:type "reasonText" :id "123" :text "I should say"})
        (match-content chat-id "assistant" {:type "reasonText" :id "123" :text " hello"})
        (match-content chat-id "assistant" {:type "reasonFinished" :id "123" :totalTimeMs (m/pred number?)})
        (match-content chat-id "assistant" {:type "text" :text "hello"})
        (match-content chat-id "assistant" {:type "text" :text " there!"})
        (match-content chat-id "system" {:type "usage"
                                         :sessionTokens 35
                                         :lastMessageCost (m/pred string?)
                                         :sessionCost (m/pred string?)})
        (match-content chat-id "system" {:type "progress" :state "finished"})
        (is (match?
             {:input [{:role "user" :content [{:type "input_text" :text "hello!"}]}]
              :instructions (m/pred string?)}
             (llm.mocks/get-req-body :reasoning-0)))))

    (testing "We reply"
      (llm.mocks/set-case! :reasoning-1)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:chat-id @chat-id*
                                 :model "openai/gpt-5"
                                 :message "how are you?"}))
            chat-id @chat-id*]

        (is (match?
             {:chatId (m/pred string?)
              :model "openai/gpt-5"
              :status "prompting"}
             resp))

        (match-content chat-id "user" {:type "text" :text "how are you?\n"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "reasonStarted" :id "234"})
        (match-content chat-id "assistant" {:type "reasonText" :id "234" :text "I should say"})
        (match-content chat-id "assistant" {:type "reasonText" :id "234" :text " fine"})
        (match-content chat-id "assistant" {:type "reasonFinished" :id "234" :totalTimeMs (m/pred number?)})
        (match-content chat-id "assistant" {:type "text" :text "I'm "})
        (match-content chat-id "assistant" {:type "text" :text " fine"})
        (match-content chat-id "system" {:type "usage"
                                         :sessionTokens 30
                                         :lastMessageCost (m/pred string?)
                                         :sessionCost (m/pred string?)})
        (match-content chat-id "system" {:type "progress" :state "finished"})
        (is (match?
             {:input [{:role "user" :content [{:type "input_text" :text "hello!"}]}
                      {:type "reasoning"
                       :id "123"
                       :summary [{:type "summary_text" :text "I should say hello"}]
                       :encrypted_content "enc-123"}
                      {:role "assistant" :content [{:type "output_text" :text "hello there!"}]}
                      {:role "user" :content [{:type "input_text" :text "how are you?"}]}]
              :instructions (m/pred string?)}
             (llm.mocks/get-req-body :reasoning-1)))))))

(deftest tool-calling
  (eca/start-process!)

  (eca/request! (fixture/initialize-request))
  (eca/notify! (fixture/initialized-notification))
  (let [chat-id* (atom nil)]
    (testing "We ask what files LLM see"
      (llm.mocks/set-case! :tool-calling-0)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:model "openai/gpt-5"
                                 :message "What files you see?"}))
            chat-id (reset! chat-id* (:chatId resp))]

        (is (match?
             {:chatId (m/pred string?)
              :model "openai/gpt-5"
              :status "prompting"}
             resp))

        (match-content chat-id "user" {:type "text" :text "What files you see?\n"})
        (match-content chat-id "system" {:type "metadata" :title "Some Cool Title"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "reasonStarted" :id "123"})
        (match-content chat-id "assistant" {:type "reasonText" :id "123" :text "I should call tool"})
        (match-content chat-id "assistant" {:type "reasonText" :id "123" :text " eca__directory_tree"})
        (match-content chat-id "assistant" {:type "reasonFinished" :id "123" :totalTimeMs (m/pred number?)})
        (match-content chat-id "assistant" {:type "text" :text "I will list files"})
        (match-content chat-id "assistant" {:type "toolCallPrepare"
                                            :origin "native"
                                            :id "tool-1"
                                            :name "directory_tree"
                                            :argumentsText ""
                                            :summary "Listing file tree"})
        (match-content chat-id "assistant" {:type "toolCallPrepare"
                                            :origin "native"
                                            :id "tool-1"
                                            :name "directory_tree"
                                            :argumentsText "{\"pat"
                                            :summary "Listing file tree"})
        (match-content chat-id "assistant" {:type "toolCallPrepare"
                                            :origin "native"
                                            :id "tool-1"
                                            :name "directory_tree"
                                            :argumentsText (str "h\":\"" (h/project-path->canon-path "resources") "\"}")
                                            :summary "Listing file tree"})
        (match-content chat-id "system" {:type "usage"
                                         :sessionTokens 35
                                         :lastMessageCost (m/pred string?)
                                         :sessionCost (m/pred string?)})
        (match-content chat-id "assistant" {:type "toolCallRun"
                                            :origin "native"
                                            :id "tool-1"
                                            :name "directory_tree"
                                            :arguments {:path (h/project-path->canon-path "resources")}
                                            :manualApproval false
                                            :summary "Listing file tree"})
        (match-content chat-id "assistant" {:type "toolCallRunning"
                                            :origin "native"
                                            :id "tool-1"
                                            :name "directory_tree"
                                            :arguments {:path (h/project-path->canon-path "resources")}
                                            :summary "Listing file tree"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Calling tool"})
        (match-content chat-id "assistant" {:type "toolCalled"
                                            :origin "native"
                                            :id "tool-1"
                                            :name "directory_tree"
                                            :arguments {:path (h/project-path->canon-path "resources")}
                                            :summary "Listing file tree"
                                            :totalTimeMs (m/pred number?)
                                            :error false
                                            :outputs [{:type "text" :text (str (h/project-path->canon-path "resources") "\n"
                                                                               " file1.md\n"
                                                                               " file2.md\n\n"
                                                                               "0 directories, 2 files")}]})
        (match-content chat-id "assistant" {:type "text" :text "The files I see:\n"})
        (match-content chat-id "assistant" {:type "text" :text "file1\nfile2\n"})
        (match-content chat-id "system" {:type "progress" :state "finished"})
        (is (match?
             {:input [{:role "user" :content [{:type "input_text" :text "What files you see?"}]}
                      {:type "reasoning"
                       :id "123"
                       :summary [{:type "summary_text" :text "I should call tool eca__directory_tree"}]
                       :encrypted_content "enc-123"}
                      {:role "assistant" :content [{:type "output_text" :text "I will list files"}]}
                      {:type "function_call"
                       :name "eca__directory_tree"
                       :call_id "tool-1"
                       :arguments (str "{\"path\":\"" (h/json-escape-path (h/project-path->canon-path "resources")) "\"}")}
                      {:type "function_call_output"
                       :call_id "tool-1"
                       :output (str (h/project-path->canon-path "resources") "\n"
                                    " file1.md\n"
                                    " file2.md\n\n"
                                    "0 directories, 2 files\n")}]
              :tools (m/embeds
                      [{:name "eca__directory_tree"}])
              :instructions (m/pred string?)}
             (llm.mocks/get-req-body :tool-calling-0)))))))
