(ns eca.llm-providers.openai-chat-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.llm-providers.openai-chat :as llm-providers.openai-chat]
   [matcher-combinators.test :refer [match?]]))

(def thinking-start-tag "<think>")
(def thinking-end-tag "</think>")

(deftest normalize-messages-test
  (testing "With tool_call history"
    (is (match?
         [{:role "user" :content [{:type "text" :text "List the files"}]}
          {:role "assistant" :content [{:type "text" :text "I'll list the files for you"}]}
          {:role "assistant"
           :tool_calls [{:id "call-1"
                         :type "function"
                         :function {:name "eca__list_files"
                                    :arguments "{}"}}]}
          {:role "tool"
           :tool_call_id "call-1"
           :content "file1.txt\nfile2.txt\n"}
          {:role "assistant" :content [{:type "text" :text "I found 2 files"}]}]
         (#'llm-providers.openai-chat/normalize-messages
          [{:role "user" :content "List the files"}
           {:role "assistant" :content "I'll list the files for you"}
           {:role "tool_call" :content {:id "call-1" :full-name "eca__list_files" :arguments {}}}
           {:role "tool_call_output" :content {:id "call-1"
                                               :full-name "eca__list_files"
                                               :arguments {}
                                               :output {:contents [{:type :text
                                                                    :error false
                                                                    :text "file1.txt\nfile2.txt"}]}}}
           {:role "assistant" :content "I found 2 files"}]
          true
          thinking-start-tag
          thinking-end-tag))))

  (testing "Skips unsupported message types"
    (is (match?
         [{:role "user" :content [{:type "text" :text "Hello"}]}
          {:role "assistant" :content [{:type "text" :text "<think>Thinking...</think>"}]}
          {:role "assistant" :content [{:type "text" :text "Hi"}]}]
         (remove nil?
                 (#'llm-providers.openai-chat/normalize-messages
                  [{:role "user" :content "Hello"}
                   {:role "reason" :content {:text "Thinking..."}}
                   {:role "assistant" :content "Hi"}]
                  true
                  thinking-start-tag
                  thinking-end-tag))))))

(deftest extract-content-test
  (testing "String input"
    (is (= [{:type "text" :text "Hello world"}]
           (#'llm-providers.openai-chat/extract-content "  Hello world  " true))))

  (testing "Sequential messages with actual format"
    (is (= "First message\nSecond message"
           (#'llm-providers.openai-chat/extract-content
            [{:type :text :text "First message"}
             {:type :text :text "Second message"}]
            true))))

  (testing "Fallback to string conversion"
    (is (= [{:text "{:some :other}" :type "text"}]
           (#'llm-providers.openai-chat/extract-content
            {:some :other}
            true)))))

(deftest ->tools-test
  (testing "Converts ECA tools to OpenAI format"
    (is (match?
         [{:type "function"
           :function {:name "eca__get_weather"
                      :description "Get the weather"
                      :parameters {:type "object"
                                   :properties {:location {:type "string"}}}}}]
         (#'llm-providers.openai-chat/->tools
          [{:full-name "eca__get_weather"
            :description "Get the weather"
            :parameters {:type "object"
                         :properties {:location {:type "string"}}}
            :other-field "ignored"}]))))

  (testing "Empty tools list"
    (is (match?
         []
         (#'llm-providers.openai-chat/->tools [])))))

(deftest transform-message-test
  (testing "Tool call transformation"
    (is (match?
         {:type :tool-call
          :data {:id "call-123"
                 :type "function"
                 :function {:name "foo__get_weather"
                            :arguments "{\"location\":\"NYC\"}"}}}
         (#'llm-providers.openai-chat/transform-message
          {:role "tool_call"
           :content {:id "call-123"
                     :full-name "foo__get_weather"
                     :arguments {:location "NYC"}}}
          true
          thinking-start-tag
          thinking-end-tag))))

  (testing "Tool call output transformation"
    (is (match?
         {:role "tool"
          :tool_call_id "call-123"
          :content "Sunny, 75°F\n"}
         (#'llm-providers.openai-chat/transform-message
          {:role "tool_call_output"
           :content {:id "call-123"
                     :output {:contents [{:type :text :text "Sunny, 75°F"}]}}}
          true
          thinking-start-tag
          thinking-end-tag))))

  (testing "Unsupported role returns nil"
    (is (nil?
         (#'llm-providers.openai-chat/transform-message
          {:role "unsupported" :content "test"}
          true
          thinking-start-tag
          thinking-end-tag)))))

(deftest accumulate-tool-calls-test
  (testing "Multiple sequential tool calls get grouped"
    (is (match?
         [{:role "user" :content "What's the weather?"}
          {:role "assistant"
           :tool_calls [{:id "call-1" :function {:name "get_weather"}}
                        {:id "call-2" :function {:name "get_location"}}]}
          {:role "user" :content "Thanks"}]
         (#'llm-providers.openai-chat/accumulate-tool-calls
          [{:role "user" :content "What's the weather?"}
           {:type :tool-call :data {:id "call-1" :function {:name "get_weather"}}}
           {:type :tool-call :data {:id "call-2" :function {:name "get_location"}}}
           {:role "user" :content "Thanks"}]))))

  (testing "Tool calls at end of messages are flushed"
    (is (match?
         [{:role "user" :content "Test"}
          {:role "assistant"
           :tool_calls [{:id "call-1" :function {:name "test_tool"}}]}]
         (#'llm-providers.openai-chat/accumulate-tool-calls
          [{:role "user" :content "Test"}
           {:type :tool-call :data {:id "call-1" :function {:name "test_tool"}}}])))))

(deftest valid-message-test
  (testing "Tool messages are always kept"
    (is (#'llm-providers.openai-chat/valid-message?
         {:role "tool" :tool_call_id "123" :content ""})))

  (testing "Messages with tool calls are kept"
    (is (#'llm-providers.openai-chat/valid-message?
         {:role "assistant" :tool_calls [{:id "123"}]})))

  (testing "Messages with blank content are filtered"
    (is (not (#'llm-providers.openai-chat/valid-message?
              {:role "user" :content "   "}))))

  (testing "Messages with valid content are kept"
    (is (#'llm-providers.openai-chat/valid-message?
         {:role "user" :content "Hello world"}))))

(deftest external-id-test
  (testing "Tool call with external-id is preserved"
    (is (match?
         {:type :tool-call
          :data {:id "call-123"
                 :type "function"
                 :function {:name "eca__get_weather"
                            :arguments "{\"location\":\"Paris\"}"}
                 :extra_content {:google {:thought_signature "signature-abc-123"}}}}
         (#'llm-providers.openai-chat/transform-message
          {:role "tool_call"
           :content {:id "call-123"
                     :full-name "eca__get_weather"
                     :arguments {:location "Paris"}
                     :external-id "signature-abc-123"}}
          true
          thinking-start-tag
          thinking-end-tag)))))

(defn process-text-think-aware [texts]
  (let [content-buffer* (atom "")
        reasoning-type* (atom nil)
        reasoning-started* (atom false)
        current-reason-id* (atom nil)
        callbacks-called* (atom [])]
    (doseq [text texts]
      (with-redefs [random-uuid (constantly "123")]
        (#'llm-providers.openai-chat/process-text-think-aware
         text
         content-buffer*
         reasoning-type*
         current-reason-id*
         reasoning-started*
         thinking-start-tag
         thinking-end-tag
         (fn [{:keys [text]}]
           (swap! callbacks-called* conj [:text text]))
         (fn [{:keys [status text id]}]
           (let [value (case status
                         :thinking text
                         status)]
             (swap! callbacks-called* conj [:reason value id]))))))
    {:callbacks-called @callbacks-called*
     :content-buffer @content-buffer*}))

(deftest process-text-think-aware-test
  (testing "complete tag by chunk"
    (is (match?
         {:content-buffer " mate!"
          :callbacks-called
          [[:reason :started "123"]
           [:reason "Hum..." "123"]
           [:reason :finished "123"]
           [:text "Hello "]
           [:text "there"]]}
         (process-text-think-aware
          ["<think>" "Hum..." "</think>"
           "Hello" " there " "mate!"]))))
  (testing "thinking tag with content"
    (is (match?
         {:content-buffer " mate!"
          :callbacks-called
          [[:reason :started "123"]
           [:reason "Hum..." "123"]
           [:reason :finished "123"]
           [:text "Hello there"]]}
         (process-text-think-aware
          ["<think>Hum..."
           "</think>Hello there mate!"]))))
  (testing "Single message with thinking and content"
    (is (match?
         {:content-buffer " mate!"
          :callbacks-called
          [[:reason :started "123"]
           [:reason "Hum..." "123"]
           [:reason :finished "123"]
           [:text "Hello there"]]}
         (process-text-think-aware
          ["<think>Hum...</think>Hello there mate!"]))))
  (testing "thinking tag splitted in chunks with content together"
    (is (match?
         {:content-buffer " mate!"
          :callbacks-called
          [[:reason :started "123"]
           [:reason "Hu" "123"]
           [:reason "m..." "123"]
           [:reason :finished "123"]
           [:text "Hello"]
           [:text " the"]
           [:text "re"]]}
         (process-text-think-aware
          ["<thi" "nk>" "Hu" "m..." "</t" "hink>"
           "Hel" "lo " "there" " mat" "e!"]))))
  (testing "thinking tag splitted in chunks with content together"
    (is (match?
         {:content-buffer " mate!"
          :callbacks-called
          [[:reason :started "123"]
           [:reason "H" "123"]
           [:reason "um." "123"]
           [:reason ".." "123"]
           [:reason :finished "123"]
           [:text "Hel"]
           [:text "lo "]
           [:text "the"]
           [:text "re"]]}
         (process-text-think-aware
          ["<" "thi" "nk>H" "um.." ".</" "thi" "nk>H"
           "ello " "the" "re " "mat" "e!"])))))
