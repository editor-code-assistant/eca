(ns eca.llm-providers.openai-chat-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.llm-providers.openai-chat :as llm-providers.openai-chat]
   [matcher-combinators.test :refer [match?]]))

(def thinking-start-tag "<think>")
(def thinking-end-tag "</think>")

(deftest normalize-messages-test
  (testing "With tool_call history - tool calls stay separate from preceding assistant (no reasoning_content)"
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

  (testing "Reason messages without reasoning-content use think tags, stay separate from following assistant"
    (is (match?
         [{:role "user" :content [{:type "text" :text "Hello"}]}
          {:role "assistant" :content [{:type "text" :text "<think>Thinking...</think>"}]}
          {:role "assistant" :content [{:type "text" :text "Hi"}]}]
         (#'llm-providers.openai-chat/normalize-messages
          [{:role "user" :content "Hello"}
           {:role "reason" :content {:text "Thinking..."}}
           {:role "assistant" :content "Hi"}]
          true
          thinking-start-tag
          thinking-end-tag)))))

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
  (testing "Tool call transformation - returns assistant message with tool_calls"
    (is (match?
         {:role "assistant"
          :tool_calls [{:id "call-123"
                        :type "function"
                        :function {:name "foo__get_weather"
                                   :arguments "{\"location\":\"NYC\"}"}}]}
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

  (testing "Reason messages - use reasoning_content if present, otherwise tags"
    ;; Without :reasoning-content, uses think tags
    (is (match?
         {:role "assistant"
          :content [{:type "text" :text "<think>Reasoning...</think>"}]}
         (#'llm-providers.openai-chat/transform-message
          {:role "reason"
           :content {:text "Reasoning..."}}
          true
          thinking-start-tag
          thinking-end-tag)))
    ;; With :reasoning-content, uses reasoning_content field
    (is (match?
         {:role "assistant"
          :reasoning_content "opaque"}
         (#'llm-providers.openai-chat/transform-message
          {:role "reason"
           :content {:text "Reasoning..."
                     :reasoning-content "opaque"}}
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

(deftest merge-adjacent-assistants-test
  (testing "Without reasoning_content, adjacent assistants are NOT merged"
    (is (match?
         [{:role "user" :content "What's the weather?"}
          {:role "assistant" :tool_calls [{:id "call-1" :function {:name "get_weather"}}]}
          {:role "assistant" :tool_calls [{:id "call-2" :function {:name "get_location"}}]}
          {:role "user" :content "Thanks"}]
         (#'llm-providers.openai-chat/merge-adjacent-assistants
          [{:role "user" :content "What's the weather?"}
           {:role "assistant" :tool_calls [{:id "call-1" :function {:name "get_weather"}}]}
           {:role "assistant" :tool_calls [{:id "call-2" :function {:name "get_location"}}]}
           {:role "user" :content "Thanks"}]))))

  (testing "DeepSeek: Reasoning content merges with tool calls"
    (is (match?
         [{:role "user" :content "Calc"}
          {:role "assistant"
           :reasoning_content "Thinking about math..."
           :tool_calls [{:id "call-1" :function {:name "calculator"}}]}]
         (#'llm-providers.openai-chat/merge-adjacent-assistants
          [{:role "user" :content "Calc"}
           {:role "assistant" :reasoning_content "Thinking about math..."}
           {:role "assistant" :tool_calls [{:id "call-1" :function {:name "calculator"}}]}]))))

  (testing "DeepSeek: Reasoning content merges with text content"
    (is (match?
         [{:role "user" :content "Hi"}
          {:role "assistant"
           :reasoning_content "Thinking about greeting..."
           :content "Hello!"}]
         (#'llm-providers.openai-chat/merge-adjacent-assistants
          [{:role "user" :content "Hi"}
           {:role "assistant" :reasoning_content "Thinking about greeting..."}
           {:role "assistant" :content "Hello!"}]))))

  (testing "DeepSeek: Standalone reasoning content stays as-is"
    (is (match?
         [{:role "user" :content "Why?"}
          {:role "assistant"
           :reasoning_content "Thinking..."}]
         (#'llm-providers.openai-chat/merge-adjacent-assistants
          [{:role "user" :content "Why?"}
           {:role "assistant" :reasoning_content "Thinking..."}])))))

(deftest prune-history-test
  (testing "Drops reason messages WITH reasoning-content before the last user message (DeepSeek)"
    (is (match?
         [{:role "user" :content "Q1"}
          {:role "assistant" :content "A1"}
          {:role "user" :content "Q2"}
          {:role "reason" :content {:text "r2" :reasoning-content "e2"}}
          {:role "assistant" :content "A2"}]
         (#'llm-providers.openai-chat/prune-history
          [{:role "user" :content "Q1"}
           {:role "reason" :content {:text "r1" :reasoning-content "e1"}}
           {:role "assistant" :content "A1"}
           {:role "user" :content "Q2"}
           {:role "reason" :content {:text "r2" :reasoning-content "e2"}}
           {:role "assistant" :content "A2"}]))))

  (testing "Preserves reason messages WITHOUT reasoning-content (think-tag based)"
    (is (match?
         [{:role "user" :content "Q1"}
          {:role "reason" :content {:text "thinking..."}}
          {:role "assistant" :content "A1"}
          {:role "user" :content "Q2"}
          {:role "reason" :content {:text "more thinking..."}}
          {:role "assistant" :content "A2"}]
         (#'llm-providers.openai-chat/prune-history
          [{:role "user" :content "Q1"}
           {:role "reason" :content {:text "thinking..."}}
           {:role "assistant" :content "A1"}
           {:role "user" :content "Q2"}
           {:role "reason" :content {:text "more thinking..."}}
           {:role "assistant" :content "A2"}]))))

  (testing "No user message leaves list unchanged"
    (let [msgs [{:role "assistant" :content "A"}
                {:role "reason" :content {:text "r"}}]]
      (is (= msgs (#'llm-providers.openai-chat/prune-history msgs))))))

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
  (let [reasoning-state* (atom {:id nil :type nil :content "" :buffer ""})
        callbacks-called* (atom [])]
    (doseq [text texts]
      (with-redefs [random-uuid (constantly "123")]
        (#'llm-providers.openai-chat/process-text-think-aware
         text
         reasoning-state*
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
     :content-buffer (:buffer @reasoning-state*)}))

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
           "ello " "the" "re " "mat" "e!"]))))
  (testing "buffer never grows beyond tag length for long text without tags"
    (let [long-text (apply str (repeat 1000 "x"))
          result (process-text-think-aware [long-text])
          buffer-size (count (:content-buffer result))]
      ;; Buffer should be at most start-tail characters.
      ;; With default tags: start-tail = dec(count "<think>") = dec 7 = 6.
      (is (<= buffer-size 6)
          (str "Buffer should be bounded by tag length, but was " buffer-size))
      ;; Most text should have been emitted
      (let [text-calls (filter #(= :text (first %)) (:callbacks-called result))
            total-emitted (apply str (map second text-calls))]
        (is (<= (- (count long-text) 6) (count total-emitted) (count long-text))
            "Should emit almost all text, keeping only a small buffer")))))

(deftest finish-reasoning-error-handling-test
  (testing "finish-reasoning should not call on-reason when :id is nil (regression test)"
    (let [on-reason-called? (atom false)
          on-reason (fn [_] (reset! on-reason-called? true))
          reasoning-state* (atom {:type :tag :id nil :content "" :buffer ""})]
      ;; With fixed implementation, on-reason should NOT be called when :id is nil
      (#'llm-providers.openai-chat/finish-reasoning! reasoning-state* on-reason)
      (is (not @on-reason-called?) "on-reason should not be called when :id is nil"))))

(deftest deepseek-non-stream-reasoning-content-test
  (testing "response-body->result captures reasoning_content and normalization preserves it"
    (let [body {:usage {:prompt_tokens 5 :completion_tokens 2}
                :choices [{:message {:content "hi"
                                     :reasoning_content "think more"}}]}
          result (#'llm-providers.openai-chat/response-body->result body (fn [& _]))
          normalized (#'llm-providers.openai-chat/normalize-messages
                      [{:role "user" :content "Q"}
                       {:role "reason" :content {:id "r1"
                                                 :reasoning-content (:reasoning-content result)}}]
                      true
                      thinking-start-tag
                      thinking-end-tag)]
      (is (= "think more" (:reasoning-content result)))
      (is (match?
           [{:role "user" :content [{:type "text" :text "Q"}]}
            {:role "assistant"
             :reasoning_content "think more"}]
           normalized)))))
