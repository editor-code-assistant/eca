(ns eca.llm-providers.openai-chat-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.client-test-helpers :refer [with-client-proxied]]
   [eca.llm-providers.openai-chat :as llm-providers.openai-chat]
   [matcher-combinators.test :refer [match?]]))

(def thinking-start-tag "<think>")
(def thinking-end-tag "</think>")

(deftest base-chat-req-test
  (testing "builds a chat request and extracts assistant output text"
    (let [req* (atom nil)]
      (with-client-proxied {}

        (fn handler [req]
          (reset! req* req)
          {:status 200
           :body {:id "chatcmpl-p7ezf7cu8pbcg5e20p6er6",
                  :object "chat.completion",
                  :created 1763927678,
                  :model "ibm/granite-4-h-tiny",
                  :choices [{:index 0,
                             :message {:role "assistant",
                                       :content "Hello there!"
                                       :tool_calls []},
                             :logprobs nil,
                             :finish_reason "stop"}]

                  :usage {:prompt_tokens 17,
                          :completion_tokens 32,
                          :ytotal_tokens 49},
                  :stats {},
                  :system_fingerprint "ibm/granite-4-h-tiny"}})

        (let [body {:model "ibm/granite-4-h-tiny"
                    :messages [{:role "system" :content "# title generator"}
                               {:role "user" :content "hi"}]
                    :stream false
                    :max_completion_tokens 32000}
              response (#'llm-providers.openai-chat/base-chat-request!
                        {:api-key "username:password"
                         :api-url "http://localhost:1"
                         :body body
                         :url-relative-path "/v1/chat/completions"})]
          (is (= {:method "POST"
                  :uri "/v1/chat/completions"
                  :body body}
                 (select-keys @req* [:method :uri :body])))
          (is (= {:output-text "Hello there!"}
                 (select-keys response [:output-text]))))))))

(deftest normalize-messages-test
  (testing "With tool_call history - assistant text and tool calls are merged"
    (is (match?
         [{:role "user" :content [{:type "text" :text "List the files"}]}
          {:role "assistant"
           :content [{:type "text" :text "I'll list the files for you"}]
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

  (testing "Reason messages without reasoning-content use think tags, merged with following assistant"
    (is (match?
         [{:role "user" :content [{:type "text" :text "Hello"}]}
          {:role "assistant" :content [{:type "text" :text "<think>Thinking...</think>"}
                                       {:type "text" :text "Hi"}]}]
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

  (testing "Reason messages - use reasoning_content if :delta-reasoning?, otherwise tags"
    ;; Without :delta-reasoning?, uses think tags
    (is (match?
         {:role "assistant"
          :content [{:type "text" :text "<think>Reasoning...</think>"}]}
         (#'llm-providers.openai-chat/transform-message
          {:role "reason"
           :content {:text "Reasoning..."}}
          true
          thinking-start-tag
          thinking-end-tag)))
    ;; With :delta-reasoning?, uses reasoning_content field with :text value
    (is (match?
         {:role "assistant"
          :reasoning_content "Reasoning..."}
         (#'llm-providers.openai-chat/transform-message
          {:role "reason"
           :content {:text "Reasoning..."
                     :delta-reasoning? true}}
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
  (testing "All adjacent assistant messages are merged (even without reasoning_content)"
    (is (match?
         [{:role "user" :content "What's the weather?"}
          {:role "assistant"
           :content ""
           :tool_calls [{:id "call-1" :function {:name "get_weather"}}
                        {:id "call-2" :function {:name "get_location"}}]}
          {:role "user" :content "Thanks"}]
         (#'llm-providers.openai-chat/merge-adjacent-assistants
          [{:role "user" :content "What's the weather?"}
           {:role "assistant" :tool_calls [{:id "call-1" :function {:name "get_weather"}}]}
           {:role "assistant" :tool_calls [{:id "call-2" :function {:name "get_location"}}]}
           {:role "user" :content "Thanks"}]))))

  (testing "Blank string content does not introduce leading newlines"
    (is (match?
         [{:role "user" :content "Hi"}
          {:role "assistant" :content "Hello"}
          {:role "user" :content "Thanks"}]
         (#'llm-providers.openai-chat/merge-adjacent-assistants
          [{:role "user" :content "Hi"}
           {:role "assistant" :content ""}
           {:role "assistant" :content "Hello"}
           {:role "user" :content "Thanks"}]))))

  (testing "DeepSeek: empty assistant content from reasoning does not add newlines to final content"
    (is (match?
         [{:role "user" :content "Q"}
          {:role "assistant"
           :reasoning_content "Thinking..."
           :content "A"}]
         (#'llm-providers.openai-chat/merge-adjacent-assistants
          [{:role "user" :content "Q"}
           {:role "assistant" :reasoning_content "Thinking..." :content ""}
           {:role "assistant" :content "A"}]))))

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
  (testing "Drops reason messages WITH :delta-reasoning? before the last user message (DeepSeek)"
    (is (match?
         [{:role "user" :content "Q1"}
          {:role "assistant" :content "A1"}
          {:role "user" :content "Q2"}
          {:role "reason" :content {:text "r2" :delta-reasoning? true}}
          {:role "assistant" :content "A2"}]
         (#'llm-providers.openai-chat/prune-history
          [{:role "user" :content "Q1"}
           {:role "reason" :content {:text "r1" :delta-reasoning? true}}
           {:role "assistant" :content "A1"}
           {:role "user" :content "Q2"}
           {:role "reason" :content {:text "r2" :delta-reasoning? true}}
           {:role "assistant" :content "A2"}]))))

  (testing "Preserves reason messages WITHOUT :delta-reasoning? (think-tag based)"
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
         {:role "assistant"
          :tool_calls [{:id "call-123"
                        :type "function"
                        :function {:name      "eca__get_weather"
                                   :arguments "{\"location\":\"Paris\"}"}
                        :extra_content {:google {:thought_signature "signature-abc-123"}}}]}
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

(deftest execute-accumulated-tools-with-parse-errors-test
  (testing "When all tools have parse errors, should NOT call on-tools-called-wrapper (prevents infinite loop)"
    (let [tool-calls* (atom {"rid-0" {:index 0
                                      :id "call-123"
                                      :full-name "eca__write_file"
                                      :arguments-text "{\"path\": \"/tmp/test.txt\", \"content\": \"truncated..."}}) ;; Invalid JSON
          wrapper-called? (atom false)
          on-tools-called-wrapper (fn [& _] (reset! wrapper-called? true))]
      (#'llm-providers.openai-chat/execute-accumulated-tools!
       tool-calls*
       on-tools-called-wrapper
       (fn [_] {:new-messages []})
       (fn [& _]))
      ;; The wrapper should NOT be called because there are no valid tools
      (is (not @wrapper-called?)
          "on-tools-called-wrapper should NOT be called when all tools have parse errors")))

  (testing "When some tools are valid and some have errors, should call wrapper with valid tools only"
    (let [tool-calls* (atom {"rid-0" {:index 0
                                      :id "call-123"
                                      :full-name "eca__read_file"
                                      :arguments-text "{\"path\": \"/tmp/test.txt\"}"}  ;; Valid JSON
                             "rid-1" {:index 1
                                      :id "call-456"
                                      :full-name "eca__write_file"
                                      :arguments-text "{\"path\": \"truncated..."}})  ;; Invalid JSON
          passed-tools (atom nil)
          on-tools-called-wrapper (fn [tools & _] (reset! passed-tools tools))]
      (#'llm-providers.openai-chat/execute-accumulated-tools!
       tool-calls*
       on-tools-called-wrapper
       (fn [_] {:new-messages []})
       (fn [& _]))
      ;; Should be called with only the valid tool
      (is (= 1 (count @passed-tools)))
      (is (= "call-123" (:id (first @passed-tools)))))))

(deftest deepseek-non-stream-reasoning-content-test
  (testing "response-body->result captures reasoning_content and normalization uses :text with :delta-reasoning?"
    (let [body {:usage {:prompt_tokens 5 :completion_tokens 2}
                :choices [{:message {:content "hi"
                                     :reasoning_content "think more"}}]}
          result (#'llm-providers.openai-chat/response-body->result body (fn [& _]))
          ;; Simulate how chat.clj would store this: :text has the content, :delta-reasoning? is the flag
          ;; In non-streaming, llm_api.clj converts (some? reasoning-content) to :delta-reasoning?
          normalized (#'llm-providers.openai-chat/normalize-messages
                      [{:role "user" :content "Q"}
                       {:role "reason" :content {:id "r1"
                                                 :text (:reason-text result)
                                                 :delta-reasoning? (some? (:reasoning-content result))}}]
                      true
                      thinking-start-tag
                      thinking-end-tag)]
      (is (= "think more" (:reason-text result)))
      (is (some? (:reasoning-content result)) "reasoning-content should be present in non-streaming result")
      (is (match?
           [{:role "user" :content [{:type "text" :text "Q"}]}
            {:role "assistant"
             :reasoning_content "think more"}]
           normalized)))))
