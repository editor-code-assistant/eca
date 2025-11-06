(ns integration.chat.google-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [integration.eca :as eca]
   [integration.fixture :as fixture]
   [integration.helper :refer [match-content] :as h]
   [llm-mock.mocks :as llm.mocks]
   [llm-mock.openai-chat :as llm-mock.openai-chat]
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
                                {:model "google/gemini-2.5-pro"
                                 :message "Tell me a joke!"}))
            chat-id (reset! chat-id* (:chatId resp))]

        (is (match?
             {:chatId (m/pred string?)
              :model "google/gemini-2.5-pro"
              :status "prompting"}
             resp))

        (match-content chat-id "user" {:type "text" :text "Tell me a joke!\n"})
        (match-content chat-id "system" {:type "metadata" :title "Some Cool Title"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "text" :text "Knoc"})
        (match-content chat-id "system" {:type "usage"})
        (match-content chat-id "assistant" {:type "text" :text "k knock!"})
        (match-content chat-id "system" {:type "progress" :state "finished"})
        (is (match?
             {:input [{:role "user" :content [{:type "input_text" :text "Tell me a joke!"}]}]
              :instructions (m/pred string?)}
             (llm.mocks/get-req-body :simple-text-0)))))

    (testing "We reply"
      (llm.mocks/set-case! :simple-text-1)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:chat-id @chat-id*
                                 :model "google/gemini-2.5-pro"
                                 :message "Who's there?"}))
            chat-id @chat-id*]

        (is (match?
             {:chatId (m/pred string?)
              :model "google/gemini-2.5-pro"
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
                      {:role "user" :content [{:type "input_text" :text "Who's there?"}]}]
              :instructions (m/pred string?)}
             (llm.mocks/get-req-body :simple-text-1)))))

    (testing "model reply again keeping context"
      (llm.mocks/set-case! :simple-text-2)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:chat-id @chat-id*
                                 :model "google/gemini-2.5-pro"
                                 :message "What foo?"}))
            chat-id @chat-id*]

        (is (match?
             {:chatId (m/pred string?)
              :model "google/gemini-2.5-pro"
              :status "prompting"}
             resp))

        (match-content chat-id "user" {:type "text" :text "What foo?\n"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "text" :text "Fo"})
        (match-content chat-id "assistant" {:type "text" :text "o b"})
        (match-content chat-id "system" {:type "usage"})
        (match-content chat-id "assistant" {:type "text" :text "ar!\n\nHa!"})
        (match-content chat-id "system" {:type "progress" :state "finished"})
        (is (match?
             {:input [{:role "user" :content [{:type "input_text" :text "Tell me a joke!"}]}
                      {:role "assistant" :content [{:type "output_text" :text "Knock knock!"}]}
                      {:role "user" :content [{:type "input_text" :text "Who's there?"}]}
                      {:role "assistant" :content [{:type "output_text" :text "Foo"}]}
                      {:role "user" :content [{:type "input_text" :text "What foo?"}]}]
              :instructions (m/pred string?)}
             (llm.mocks/get-req-body :simple-text-2)))))))

(deftest reasoning-text
  (eca/start-process!)

  (eca/request! (fixture/initialize-request))
  (eca/notify! (fixture/initialized-notification))
  (llm-mock.openai-chat/set-thinking-tag! "thought")
  (let [chat-id* (atom nil)]
    (testing "We send a hello message"
      (llm.mocks/set-case! :reasoning-0)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:model "google/gemini-2.5-pro"
                                 :message "hello!"}))
            chat-id (reset! chat-id* (:chatId resp))]

        (is (match?
             {:chatId (m/pred string?)
              :model "google/gemini-2.5-pro"
              :status "prompting"}
             resp))

        (match-content chat-id "user" {:type "text" :text "hello!\n"})
        (match-content chat-id "system" {:type "metadata" :title "Some Cool Title"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "reasonStarted" :id (m/pred string?)})
        (match-content chat-id "assistant" {:type "reasonText" :id (m/pred string?) :text "I s"})
        (match-content chat-id "assistant" {:type "reasonText" :id (m/pred string?) :text "hould "})
        (match-content chat-id "assistant" {:type "reasonText" :id (m/pred string?) :text "say hello"})
        (match-content chat-id "assistant" {:type "reasonFinished" :id (m/pred string?) :totalTimeMs (m/pred number?)})
        (match-content chat-id "assistant" {:type "text" :text "hell"})
        (match-content chat-id "system" {:type "usage"})
        (match-content chat-id "assistant" {:type "text" :text "o there!"})
        (match-content chat-id "system" {:type "progress" :state "finished"})
        (is (match?
             {:input [{:role "user" :content [{:type "input_text" :text "hello!"}]}]
              :instructions (m/pred string?)}
             (llm.mocks/get-req-body :reasoning-0)))))

    (testing "We reply"
      (llm.mocks/set-case! :reasoning-1)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:chat-id @chat-id*
                                 :model "google/gemini-2.5-pro"
                                 :message "how are you?"}))
            chat-id @chat-id*]

        (is (match?
             {:chatId (m/pred string?)
              :model "google/gemini-2.5-pro"
              :status "prompting"}
             resp))

        (match-content chat-id "user" {:type "text" :text "how are you?\n"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "reasonStarted" :id (m/pred string?)})
        (match-content chat-id "assistant" {:type "reasonText" :id (m/pred string?) :text "I s"})
        (match-content chat-id "assistant" {:type "reasonText" :id (m/pred string?) :text "hould"})
        (match-content chat-id "assistant" {:type "reasonText" :id (m/pred string?) :text " say fine"})
        (match-content chat-id "assistant" {:type "reasonFinished" :id (m/pred string?) :totalTimeMs (m/pred number?)})
        (match-content chat-id "assistant" {:type "text" :text "I"})
        (match-content chat-id "system" {:type "usage"})
        (match-content chat-id "assistant" {:type "text" :text "'m  fine"})
        (match-content chat-id "system" {:type "progress" :state "finished"})
        (is (match?
             {:input [{:role "user" :content [{:type "input_text" :text "hello!"}]}
                      {:role "assistant" :content [{:type "output_text" :text "<thought>I should say hello</thought>"}]}
                      {:role "assistant" :content [{:type "output_text" :text "hello there!"}]}
                      {:role "user" :content [{:type "input_text" :text "how are you?"}]}]
              :instructions (m/pred string?)}
             (llm.mocks/get-req-body :reasoning-1)))))))

#_(deftest tool-calling
    (eca/start-process!)

    (eca/request! (fixture/initialize-request))
    (eca/notify! (fixture/initialized-notification))
    (let [chat-id* (atom nil)]
      (testing "We ask what files LLM see"
        (llm.mocks/set-case! :tool-calling-0)
        (let [0
              resp (eca/request! (fixture/chat-prompt-request
                                  {:model "google/gemini-2.5-pro"
                                   :message "What files you see?"}))
              chat-id (reset! chat-id* (:chatId resp))]

          (is (match?
               {:chatId (m/pred string?)
                :model "google/gemini-2.5-pro"
                :status "prompting"}
               resp))

          (match-content chat-id "user" {:type "text" :text "What files you see?\n"})
          (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
          (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
          (match-content chat-id "assistant" {:type "reasonStarted" :id (m/pred string?)})
          (match-content chat-id "assistant" {:type "reasonText" :id (m/pred string?) :text "I should call tool"})
          (match-content chat-id "assistant" {:type "reasonText" :id (m/pred string?) :text " eca__eca_directory_tree"})
          (match-content chat-id "assistant" {:type "reasonFinished" :id (m/pred string?) :totalTimeMs (m/pred number?)})
          (match-content chat-id "assistant" {:type "text" :text "I will list files"})
          (match-content chat-id "assistant" {:type "toolCallPrepare"
                                              :origin "native"
                                              :id "tool-1"
                                              :name "eca_directory_tree"
                                              :argumentsText ""
                                              :manualApproval false
                                              :summary "Listing file tree"})
          (match-content chat-id "assistant" {:type "toolCallPrepare"
                                              :origin "native"
                                              :id "tool-1"
                                              :name "eca_directory_tree"
                                              :argumentsText "{\"pat"
                                              :manualApproval false
                                              :summary "Listing file tree"})
          (match-content chat-id "assistant" {:type "toolCallPrepare"
                                              :origin "native"
                                              :id "tool-1"
                                              :name "eca_directory_tree"
                                              :argumentsText (str "h\":\"" (h/project-path->canon-path "resources") "\"}")
                                              :manualApproval false
                                              :summary "Listing file tree"})
          (match-content chat-id "system" {:type "usage"
                                           :messageInputTokens 5
                                           :messageOutputTokens 30
                                           :sessionTokens 35
                                           :messageCost (m/pred string?)
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
                                              :totalTimeMs number?
                                              :summary "Listing file tree"})
          (match-content chat-id "assistant" {:type "toolCalled"
                                              :origin "native"
                                              :id "tool-1"
                                              :name "eca_directory_tree"
                                              :arguments {:path (h/project-path->canon-path "resources")}
                                              :summary "Listing file tree"
                                              :error false
                                              :outputs [{:type "text" :text (str "[FILE] " (h/project-path->canon-path "resources/file1.md\n")
                                                                                 "[FILE] " (h/project-path->canon-path "resources/file2.md\n"))}]})
          (match-content chat-id "assistant" {:type "text" :text "The files I see:\n"})
          (match-content chat-id "assistant" {:type "text" :text "file1\nfile2\n"})
          (match-content chat-id "system" {:type "usage"
                                           :messageInputTokens 5
                                           :messageOutputTokens 30
                                           :sessionTokens 70
                                           :messageCost (m/pred string?)
                                           :sessionCost (m/pred string?)})
          (match-content chat-id "system" {:type "progress" :state "finished"})
          (is (match?
               {:messages [{:role "user" :content [{:type "text" :text "What files you see?"}]}
                           {:role "assistant"
                            :content [{:type "thinking"
                                       :signature "enc-123"
                                       :thinking "I should call tool eca__eca_directory_tree"}]}
                           {:role "assistant" :content [{:type "text" :text "I will list files"}]}
                           {:role "assistant"
                            :content [{:type "tool_use"
                                       :id "tool-1"
                                       :name "eca__eca_directory_tree"
                                       :input {:path (h/project-path->canon-path "resources")}}]}
                           {:role "user"
                            :content [{:type "tool_result"
                                       :tool_use_id "tool-1"
                                       :content (str "[FILE] " (h/project-path->canon-path "resources/file1.md\n")
                                                     "[FILE] " (h/project-path->canon-path "resources/file2.md\n\n"))}]}]
                :tools (m/embeds
                        [{:name "eca__eca_directory_tree"}])
                :system (m/pred vector?)}
               llm.mocks/*last-req-body*))))))
