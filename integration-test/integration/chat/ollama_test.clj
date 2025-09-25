(ns integration.chat.ollama-test
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

  (is (match?
       {:chatDefaultModel "ollama/qwen3"
        :models (m/embeds ["ollama/qwen3"])}
       (eca/request! (fixture/initialize-request {:initializationOptions (h/deep-merge fixture/default-init-options
                                                                                       {:providers {"anthropic" {:key nil}
                                                                                                    "openai" {:key nil}
                                                                                                    "github-copilot" {:key nil}
                                                                                                    "ollama" {:url (str fixture/base-llm-mock-url "/ollama")}}})
                                                  :capabilities {:codeAssistant {:chat {}}}}))))
  (eca/notify! (fixture/initialized-notification))
  (let [chat-id* (atom nil)]
    (testing "We send a simple hello message"
      (llm.mocks/set-case! :simple-text-0)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:model "ollama/qwen3"
                                 :message "Tell me a joke!"}))
            chat-id (reset! chat-id* (:chatId resp))]

        (is (match?
             {:chatId (m/pred string?)
              :model "ollama/qwen3"
              :status "prompting"}
             resp))

        (match-content chat-id "user" {:type "text" :text "Tell me a joke!\n"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "text" :text "Knock"})
        (match-content chat-id "assistant" {:type "text" :text " knock!"})
        (match-content chat-id "system" {:type "progress" :state "finished"})
        (is (match?
             {:model "qwen3"
              :messages [{:role "system" :content (m/pred string?)}
                         {:role "user" :content "Tell me a joke!"}]}
             llm.mocks/*last-req-body*))))

    (testing "We reply"
      (llm.mocks/set-case! :simple-text-1)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:chat-id @chat-id*
                                 :model "ollama/qwen3"
                                 :message "Who's there?"}))
            chat-id @chat-id*]

        (is (match?
             {:chatId (m/pred string?)
              :model "ollama/qwen3"
              :status "prompting"}
             resp))

        (match-content chat-id "user" {:type "text" :text "Who's there?\n"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "text" :text "Foo"})
        (match-content chat-id "system" {:type "progress" :state "finished"})))

    (testing "model reply again keeping context"
      (llm.mocks/set-case! :simple-text-2)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:chat-id @chat-id*
                                 :model "ollama/qwen3"
                                 :message "What foo?"}))
            chat-id @chat-id*]

        (is (match?
             {:chatId (m/pred string?)
              :model "ollama/qwen3"
              :status "prompting"}
             resp))

        (match-content chat-id "user" {:type "text" :text "What foo?\n"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "text" :text "Foo"})
        (match-content chat-id "assistant" {:type "text" :text " bar!"})
        (match-content chat-id "assistant" {:type "text" :text "\n\n"})
        (match-content chat-id "assistant" {:type "text" :text "Ha!"})
        (match-content chat-id "system" {:type "progress" :state "finished"})
        (is (match?
             {:model "qwen3"
              :messages [{:role "system" :content (m/pred string?)}
                         {:role "user" :content "Tell me a joke!"}
                         {:role "assistant" :content "Knock knock!"}
                         {:role "user" :content "Who's there?"}
                         {:role "assistant" :content "Foo"}
                         {:role "user" :content "What foo?"}]}
             llm.mocks/*last-req-body*))))))

(deftest reasoning-text
  (eca/start-process!)

  (is (match?
       {:chatDefaultModel "ollama/qwen3"
        :models (m/embeds ["ollama/qwen3"])}
       (eca/request! (fixture/initialize-request {:initializationOptions (h/deep-merge fixture/default-init-options
                                                                                       {:providers {"anthropic" {:key nil}
                                                                                                    "openai" {:key nil}
                                                                                                    "github-copilot" {:key nil}
                                                                                                    "ollama" {:url (str fixture/base-llm-mock-url "/ollama")}}})
                                                  :capabilities {:codeAssistant {:chat {}}}}))))
  (eca/notify! (fixture/initialized-notification))
  (let [chat-id* (atom nil)]
    (testing "We send a hello message"
      (llm.mocks/set-case! :reasoning-0)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:model "ollama/qwen3"
                                 :message "hello!"}))
            chat-id (reset! chat-id* (:chatId resp))]

        (is (match?
             {:chatId (m/pred string?)
              :model "ollama/qwen3"
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
        (match-content chat-id "system" {:type "progress" :state "finished"})
        (is (match?
             {:model "qwen3"
              :messages [{:role "system" :content (m/pred string?)}
                         {:role "user" :content "hello!"}]}
             llm.mocks/*last-req-body*))))

    (testing "We reply"
      (llm.mocks/set-case! :reasoning-1)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:chat-id @chat-id*
                                 :model "ollama/qwen3"
                                 :message "how are you?"}))
            chat-id @chat-id*]

        (is (match?
             {:chatId (m/pred string?)
              :model "ollama/qwen3"
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
        (match-content chat-id "system" {:type "progress" :state "finished"})
        (is (match?
             {:model "qwen3"
              :messages [{:role "system" :content (m/pred string?)}
                         {:role "user" :content "hello!"}
                         {:role "assistant" :content "I should say hello"}
                         {:role "assistant" :content "hello there!"}
                         {:role "user" :content "how are you?"}]}
             llm.mocks/*last-req-body*))))))

(deftest tool-calling
  (eca/start-process!)

  (is (match?
       {:chatDefaultModel "ollama/qwen3"
        :models (m/embeds ["ollama/qwen3"])}
       (eca/request! (fixture/initialize-request {:initializationOptions (h/deep-merge fixture/default-init-options
                                                                                       {:providers {"anthropic" {:key nil}
                                                                                                    "openai" {:key nil}
                                                                                                    "github-copilot" {:key nil}
                                                                                                    "ollama" {:url (str fixture/base-llm-mock-url "/ollama")}}})
                                                  :capabilities {:codeAssistant {:chat {}}}}))))
  (eca/notify! (fixture/initialized-notification))
  (let [chat-id* (atom nil)]
    (testing "We ask what files LLM see"
      (llm.mocks/set-case! :tool-calling-0)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:model "ollama/qwen3"
                                 :message "What files you see?"}))
            chat-id (reset! chat-id* (:chatId resp))]

        (is (match?
             {:chatId (m/pred string?)
              :model "ollama/qwen3"
              :status "prompting"}
             resp))

        (match-content chat-id "user" {:type "text" :text "What files you see?\n"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "text" :text "I will list files"})
        (match-content chat-id "assistant" {:type "toolCallPrepare"
                                            :origin "native"
                                            :id (m/pred string?)
                                            :name "eca_directory_tree"
                                            :argumentsText ""
                                            :summary "Listing file tree"})
        (match-content chat-id "assistant" {:type "toolCallRun"
                                            :origin "native"
                                            :id (m/pred string?)
                                            :name "eca_directory_tree"
                                            :arguments {:path (h/project-path->canon-path "resources")}
                                            :manualApproval false
                                            :summary "Listing file tree"})
        (match-content chat-id "assistant" {:type "toolCallRunning"
                                            :origin "native"
                                            :id (m/pred string?)
                                            :name "eca_directory_tree"
                                            :arguments {:path (h/project-path->canon-path "resources")}
                                            :summary "Listing file tree"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Calling tool"})
        (match-content chat-id "assistant" {:type "toolCalled"
                                            :origin "native"
                                            :id (m/pred string?)
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
        (is (match?
             {:model "qwen3"
              :messages [{:role "user" :content "What files you see?"}
                         {:role "assistant" :content "I will list files"}
                         {:role "assistant" :tool-calls [{:type "function"
                                                          :function {:id (m/pred string?)
                                                                     :name "eca_directory_tree"
                                                                     :arguments {:path (h/project-path->canon-path "resources")}
                                                                     :summary "Listing file tree"
                                                                     :origin "native"}}]}
                         {:role "tool" :content (str (h/project-path->canon-path "resources") "\n"
                                                     " file1.md\n"
                                                     " file2.md\n\n"
                                                     "0 directories, 2 files\n")}]
              :tools (m/embeds [{:type "function" :function {:name "eca_directory_tree"}}])}
             llm.mocks/*last-req-body*))))))
