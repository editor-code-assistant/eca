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
                      {:role "assistant" :content [{:type "output_text" :text "hello there!"}]}
                      {:role "user" :content [{:type "input_text" :text "how are you?"}]}]
              :instructions (m/pred string?)}
             (llm.mocks/get-req-body :reasoning-1)))))))

(deftest tool-calling-with-thought-signatures
  (eca/start-process!)

  (eca/request! (fixture/initialize-request))
  (eca/notify! (fixture/initialized-notification))
  (llm-mock.openai-chat/set-thinking-tag! "thought")
  (let [chat-id* (atom nil)]
    (testing "We ask what files LLM sees - tool call includes thought signature"
      (llm.mocks/set-case! :tool-calling-with-thought-signature-0)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:model "google/gemini-2.5-pro"
                                 :message "What files you see?"}))
            chat-id (reset! chat-id* (:chatId resp))]

        (is (match?
             {:chatId (m/pred string?)
              :model "google/gemini-2.5-pro"
              :status "prompting"}
             resp))

        (match-content chat-id "user" {:type "text" :text "What files you see?\n"})
        (match-content chat-id "system" {:type "metadata" :title "Some Cool Title"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "reasonStarted" :id (m/pred string?)})
        ;; Note: The buffering in process-text-think-aware keeps a 9-char tail to detect </thought>,
        ;; so chunks get re-split during streaming. The mock sends "I s", "hould call tool", " eca__directory_tree"
        ;; but after buffering we get these chunks:
        (match-content chat-id "assistant" {:type "reasonText" :id (m/pred string?) :text "I should "})
        (match-content chat-id "assistant" {:type "reasonText" :id (m/pred string?) :text "call tool eca__direc"})
        (match-content chat-id "assistant" {:type "reasonText" :id (m/pred string?) :text "tory_tree"})
        (match-content chat-id "assistant" {:type "reasonFinished" :id (m/pred string?) :totalTimeMs (m/pred number?)})
        ;; Text is buffered (8-char tail for <thought> detection), then flushed when tool calls start
        (match-content chat-id "assistant" {:type "text" :text "I will li"})
        (match-content chat-id "assistant" {:type "text" :text "st files"})
        (match-content chat-id "assistant" {:type "toolCallPrepare"
                                            :origin "native"
                                            :id (m/pred string?)
                                            :name "directory_tree"
                                            :argumentsText ""
                                            :summary "Listing file tree"})
        (match-content chat-id "assistant" {:type "toolCallPrepare"
                                            :origin "native"
                                            :id (m/pred string?)
                                            :name "directory_tree"
                                            :argumentsText "{\"pat"
                                            :summary "Listing file tree"})
        (match-content chat-id "assistant" {:type "toolCallPrepare"
                                            :origin "native"
                                            :id (m/pred string?)
                                            :name "directory_tree"
                                            :argumentsText (str "h\":\"" (h/json-escape-path (h/project-path->canon-path "resources")) "\"}")
                                            :summary "Listing file tree"})
        (match-content chat-id "system" {:type "usage"})
        (match-content chat-id "assistant" {:type "toolCallRun"
                                            :origin "native"
                                            :id (m/pred string?)
                                            :name "directory_tree"
                                            :arguments {:path (h/project-path->canon-path "resources")}
                                            :manualApproval false
                                            :summary "Listing file tree"})
        (match-content chat-id "assistant" {:type "toolCallRunning"
                                            :origin "native"
                                            :id (m/pred string?)
                                            :name "directory_tree"
                                            :arguments {:path (h/project-path->canon-path "resources")}
                                            :summary "Listing file tree"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Calling tool"})
        (match-content chat-id "assistant" {:type "toolCalled"
                                            :origin "native"
                                            :id (m/pred string?)
                                            :name "directory_tree"
                                            :arguments {:path (h/project-path->canon-path "resources")}
                                            :summary "Listing file tree"
                                            :totalTimeMs (m/pred number?)
                                            :error false
                                            :outputs [{:type "text" :text (str (h/project-path->canon-path "resources") "\n"
                                                                               " file1.md\n"
                                                                               " file2.md\n\n"
                                                                               "0 directories, 2 files")}]})
        ;; Text chunks get re-split due to 8-char tail buffering for <thought> detection.
        ;; Note: We use m/in-any-order for the final text/usage/progress events since their
        ;; relative ordering can vary due to async processing and buffering.
        (match-content chat-id "assistant" {:type "text" :text "The files"})
        (match-content chat-id "assistant" {:type "text" :text " I see:\nfile"})
        (match-content chat-id "assistant" {:type "text" :text "1\nfile2\n"})
        (match-content chat-id "system" {:type "progress" :state "finished"})

        ;; Verify thought signature was passed back in the second request
        (let [raw-messages (llm.mocks/get-raw-messages :tool-calling-with-thought-signature-0)
              ;; Find the assistant message with tool_calls
              assistant-tool-call-msg (first (filter #(and (= "assistant" (:role %))
                                                           (seq (:tool_calls %)))
                                                     raw-messages))]
          (is (match?
               {:role "assistant"
                :tool_calls [{:extra_content {:google {:thought_signature "thought-sig-abc123"}}}]}
               assistant-tool-call-msg)))))))
