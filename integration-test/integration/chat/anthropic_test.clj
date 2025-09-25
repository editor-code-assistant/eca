(ns integration.chat.anthropic-test
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
                                {:model "anthropic/claude-sonnet-4-20250514"
                                 :message "Tell me a joke!"}))
            chat-id (reset! chat-id* (:chatId resp))]

        (is (match?
             {:chatId (m/pred string?)
              :model "anthropic/claude-sonnet-4-20250514"
              :status "prompting"}
             resp))

        (match-content chat-id "user" {:type "text" :text "Tell me a joke!\n"})
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
             {:messages [{:role "user" :content [{:type "text" :text "Tell me a joke!"}]}]
              :system (m/pred vector?)}
             llm.mocks/*last-req-body*))))

    (testing "We reply"
      (llm.mocks/set-case! :simple-text-1)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:chat-id @chat-id*
                                 :model "anthropic/claude-sonnet-4-20250514"
                                 :message "Who's there?"}))
            chat-id @chat-id*]

        (is (match?
             {:chatId (m/pred string?)
              :model "anthropic/claude-sonnet-4-20250514"
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
             {:messages [{:role "user" :content [{:type "text" :text "Tell me a joke!"}]}
                         {:role "assistant" :content [{:type "text" :text "Knock knock!"}]}
                         {:role "user" :content [{:type "text" :text "Who's there?"}]}]}
             llm.mocks/*last-req-body*))))

    (testing "model reply again keeping context"
      (llm.mocks/set-case! :simple-text-2)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:chat-id @chat-id*
                                 :model "anthropic/claude-sonnet-4-20250514"
                                 :message "What foo?"}))
            chat-id @chat-id*]

        (is (match?
             {:chatId (m/pred string?)
              :model "anthropic/claude-sonnet-4-20250514"
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
        (match-content chat-id "system" {:type "metadata" :title "Some Cool Title"})
        (is (match?
             {:messages [{:role "user" :content [{:type "text" :text "Tell me a joke!"}]}
                         {:role "assistant" :content [{:type "text" :text "Knock knock!"}]}
                         {:role "user" :content [{:type "text" :text "Who's there?"}]}
                         {:role "assistant" :content [{:type "text" :text "Foo"}]}
                         {:role "user" :content [{:type "text" :text "What foo?"}]}]}
             llm.mocks/*last-req-body*))))))

(deftest reasoning-text
  (eca/start-process!)

  (eca/request! (fixture/initialize-request))
  (eca/notify! (fixture/initialized-notification))
  (let [chat-id* (atom nil)]
    (testing "We send a hello message"
      (llm.mocks/set-case! :reasoning-0)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:model "anthropic/claude-sonnet-4-20250514"
                                 :message "hello!"}))
            chat-id (reset! chat-id* (:chatId resp))]

        (is (match?
             {:chatId (m/pred string?)
              :model "anthropic/claude-sonnet-4-20250514"
              :status "prompting"}
             resp))

        (match-content chat-id "user" {:type "text" :text "hello!\n"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "reasonStarted" :id (m/pred string?)})
        (match-content chat-id "assistant" {:type "reasonText" :id (m/pred string?) :text "I should say"})
        (match-content chat-id "assistant" {:type "reasonText" :id (m/pred string?) :text " hello"})
        (match-content chat-id "assistant" {:type "reasonFinished" :id (m/pred string?) :totalTimeMs (m/pred number?)})
        (match-content chat-id "assistant" {:type "text" :text "hello"})
        (match-content chat-id "assistant" {:type "text" :text " there!"})
        (match-content chat-id "system" {:type "usage"
                                         :sessionTokens 35
                                         :lastMessageCost (m/pred string?)
                                         :sessionCost (m/pred string?)})
        (match-content chat-id "system" {:type "progress" :state "finished"})
        (match-content chat-id "system" {:type "metadata" :title "Some Cool Title"})
        (is (match?
             {:messages [{:role "user" :content [{:type "text" :text "hello!"}]}]
              :system (m/pred vector?)}
             llm.mocks/*last-req-body*))))

    (testing "We reply"
      (llm.mocks/set-case! :reasoning-1)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:chat-id @chat-id*
                                 :model "anthropic/claude-sonnet-4-20250514"
                                 :message "how are you?"}))
            chat-id @chat-id*]

        (is (match?
             {:chatId (m/pred string?)
              :model "anthropic/claude-sonnet-4-20250514"
              :status "prompting"}
             resp))

        (match-content chat-id "user" {:type "text" :text "how are you?\n"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "reasonStarted" :id (m/pred string?)})
        (match-content chat-id "assistant" {:type "reasonText" :id (m/pred string?) :text "I should say"})
        (match-content chat-id "assistant" {:type "reasonText" :id (m/pred string?) :text " fine"})
        (match-content chat-id "assistant" {:type "reasonFinished" :id (m/pred string?) :totalTimeMs (m/pred number?)})
        (match-content chat-id "assistant" {:type "text" :text "I'm "})
        (match-content chat-id "assistant" {:type "text" :text " fine"})
        (match-content chat-id "system" {:type "usage"
                                         :sessionTokens 30
                                         :lastMessageCost (m/pred string?)
                                         :sessionCost (m/pred string?)})
        (match-content chat-id "system" {:type "progress" :state "finished"})
        (is (match?
             {:messages [{:role "user" :content [{:type "text" :text "hello!"}]}
                         {:role "assistant"
                          :content [{:type "thinking"
                                     :signature "enc-123"
                                     :thinking "I should say hello"}]}
                         {:role "assistant" :content [{:type "text" :text "hello there!"}]}
                         {:role "user" :content [{:type "text" :text "how are you?"}]}]
              :system (m/pred vector?)}
             llm.mocks/*last-req-body*))))))

(deftest tool-calling
  (eca/start-process!)

  (eca/request! (fixture/initialize-request))
  (eca/notify! (fixture/initialized-notification))
  (let [chat-id* (atom nil)]
    (testing "We ask what files LLM see"
      (llm.mocks/set-case! :tool-calling-0)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:model "anthropic/claude-sonnet-4-20250514"
                                 :message "What files you see?"}))
            chat-id (reset! chat-id* (:chatId resp))]

        (is (match?
             {:chatId (m/pred string?)
              :model "anthropic/claude-sonnet-4-20250514"
              :status "prompting"}
             resp))

        (match-content chat-id "user" {:type "text" :text "What files you see?\n"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "reasonStarted" :id (m/pred string?)})
        (match-content chat-id "assistant" {:type "reasonText" :id (m/pred string?) :text "I should call tool"})
        (match-content chat-id "assistant" {:type "reasonText" :id (m/pred string?) :text " eca_directory_tree"})
        (match-content chat-id "assistant" {:type "reasonFinished" :id (m/pred string?) :totalTimeMs (m/pred number?)})
        (match-content chat-id "assistant" {:type "text" :text "I will list files"})
        (match-content chat-id "assistant" {:type "toolCallPrepare"
                                            :origin "native"
                                            :id "tool-1"
                                            :name "eca_directory_tree"
                                            :argumentsText ""
                                            :summary "Listing file tree"})
        (match-content chat-id "assistant" {:type "toolCallPrepare"
                                            :origin "native"
                                            :id "tool-1"
                                            :name "eca_directory_tree"
                                            :argumentsText "{\"pat"
                                            :summary "Listing file tree"})
        (match-content chat-id "assistant" {:type "toolCallPrepare"
                                            :origin "native"
                                            :id "tool-1"
                                            :name "eca_directory_tree"
                                            :argumentsText (str "h\":\"" (h/project-path->canon-path "resources") "\"}")
                                            :summary "Listing file tree"})
        (match-content chat-id "system" {:type "usage"
                                         :sessionTokens 35
                                         :lastMessageCost (m/pred string?)
                                         :sessionCost (m/pred string?)})
        (match-content chat-id "assistant" {:type "toolCallRun"
                                            :origin "native"
                                            :id "tool-1"
                                            :name "eca_directory_tree"
                                            :arguments {:path (h/project-path->canon-path "resources")}
                                            :manualApproval false
                                            :summary "Listing file tree"})
        (match-content chat-id "assistant" {:type "toolCallRunning"
                                            :origin "native"
                                            :id "tool-1"
                                            :name "eca_directory_tree"
                                            :arguments {:path (h/project-path->canon-path "resources")}
                                            :summary "Listing file tree"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Calling tool"})
        (match-content chat-id "assistant" {:type "toolCalled"
                                            :origin "native"
                                            :id "tool-1"
                                            :name "eca_directory_tree"
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
        (match-content chat-id "system" {:type "metadata" :title "Some Cool Title"})
        (is (match?
             {:messages [{:role "user" :content [{:type "text" :text "What files you see?"}]}
                         {:role "assistant"
                          :content [{:type "thinking"
                                     :signature "enc-123"
                                     :thinking "I should call tool eca_directory_tree"}]}
                         {:role "assistant" :content [{:type "text" :text "I will list files"}]}
                         {:role "assistant"
                          :content [{:type "tool_use"
                                     :id "tool-1"
                                     :name "eca_directory_tree"
                                     :input {:path (h/project-path->canon-path "resources")}}]}
                         {:role "user"
                          :content [{:type "tool_result"
                                     :tool_use_id "tool-1"
                                     :content (str (h/project-path->canon-path "resources") "\n"
                                                   " file1.md\n"
                                                   " file2.md\n\n"
                                                   "0 directories, 2 files\n")}]}]
              :tools (m/embeds
                      [{:name "eca_directory_tree"}])
              :system (m/pred vector?)}
             llm.mocks/*last-req-body*))))))
