(ns integration.chat.hooks-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]
   [integration.eca :as eca]
   [integration.fixture :as fixture]
   [integration.helper :as h]
   [llm-mock.mocks :as llm.mocks]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(eca/clean-after-test)

(defn- hooks-init-options [hooks]
  (assoc fixture/default-init-options :hooks hooks))

(deftest prerequest-chaining-and-stop-test
  (testing "preRequest chaining rewrites prompt and stop prevents LLM call"
    (eca/start-process!)

    (llm.mocks/set-case! :simple-text-0)

    (eca/request!
     (fixture/initialize-request
      {:initializationOptions
       (hooks-init-options
        {"rewrite" {:type "preRequest"
                    :actions [{:type "shell"
                               :shell "echo '{\"replacedPrompt\":\"REWRITTEN\"}'"}]}
         "stop" {:type "preRequest"
                 :actions [{:type "shell"
                            :shell "echo '{\"continue\":false,\"stopReason\":\"STOPPED BY HOOK\"}'"}]}})}))

    (eca/notify! (fixture/initialized-notification))

    (let [resp (eca/request! (fixture/chat-prompt-request
                              {:model "openai/gpt-4.1"
                               :message "ORIGINAL"}))
          chat-id (:chatId resp)
          notifications (loop [acc []]
                          (let [n (eca/client-awaits-server-notification :chat/contentReceived)]
                            (if (and (= chat-id (:chatId n))
                                     (= "system" (:role n))
                                     (= "progress" (get-in n [:content :type]))
                                     (= "finished" (get-in n [:content :state])))
                              (conj acc n)
                              (recur (conj acc n)))))]
      (is (match?
           {:chatId (m/pred string?)
            :model "openai/gpt-4.1"
            :status "prompting"}
           resp))

      ;; System receives stopReason from hook and finishes.
      (is (match?
           (m/embeds
            [{:chatId chat-id
              :role "system"
              :content {:type "text" :text "STOPPED BY HOOK"}}
             {:chatId chat-id
              :role "system"
              :content {:type "progress" :state "finished"}}])
           notifications))

      ;; LLM must not be called when continue:false.
      (is (nil? (llm.mocks/get-req-body :simple-text-0))))))

(deftest pretoolcall-updated-input-propagates-to-llm-test
  (testing "preToolCall updatedInput is reflected in next LLM call"
    (eca/start-process!)

    (llm.mocks/set-case! :tool-calling-0)

    (eca/request!
     (fixture/initialize-request
      {:initializationOptions
       (hooks-init-options
        {"pre-tool" {:type "preToolCall"
                     :actions [{:type "shell"
                                :shell "echo '{\"updatedInput\":{\"recursive\":true}}'"}]}})}))

    (eca/notify! (fixture/initialized-notification))

    (let [resp (eca/request! (fixture/chat-prompt-request
                              {:model "openai/gpt-5"
                               :message "What files you see?"}))
          chat-id (:chatId resp)
          ;; Drain notifications until tool flow ends (progress finished)
          _ (loop []
              (let [n (eca/client-awaits-server-notification :chat/contentReceived)]
                (when (not (and (= chat-id (:chatId n))
                                (= "system" (:role n))
                                (= "progress" (get-in n [:content :type]))
                                (= "finished" (get-in n [:content :state]))))
                  (recur))))]

      (is (match?
           {:chatId (m/pred string?)
            :model "openai/gpt-5"
            :status "prompting"}
           resp))

      ;; The second OpenAI responses call (captured under :tool-calling-0)
      ;; should see the updated arguments with recursive=true.
      (is (match?
           {:input (m/embeds
                    [{:type "function_call"
                      :name "eca__directory_tree"
                      :arguments (m/pred #(and (string? %)
                                               (re-find #"\"recursive\":true" %)))}])}
           (llm.mocks/get-req-body :tool-calling-0))))))

(deftest lifecycle-hooks-order-test
  (testing "sessionStart, chatStart, chatEnd, sessionEnd ordering"
    (eca/start-process!)

    (let [log-path (io/file h/default-root-project-path ".eca/hooks-log.txt")
          win? (string/starts-with? (System/getProperty "os.name") "Windows")]
      (io/make-parents log-path)
      (spit log-path "")

      (eca/request!
       (fixture/initialize-request
        {:initializationOptions
         (hooks-init-options
          {"session-start" {:type "sessionStart"
                            :actions [{:type "shell"
                                       :shell "echo sessionStart >> .eca/hooks-log.txt"}]}
           "chat-start" {:type "chatStart"
                         :actions [{:type "shell"
                                    :shell "printf 'chatStart:%s\\n' \"$(jq -r '.resumed')\" >> .eca/hooks-log.txt"}]}
           "chat-end" {:type "chatEnd"
                       :actions [{:type "shell"
                                  :shell "echo chatEnd >> .eca/hooks-log.txt"}]}
           "session-end" {:type "sessionEnd"
                          :actions [{:type "shell"
                                     :shell "echo sessionEnd >> .eca/hooks-log.txt"}]}})}))

      (eca/notify! (fixture/initialized-notification))

      (llm.mocks/set-case! :simple-text-0)

      ;; Start new chat
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:model "openai/gpt-4.1"
                                 :message "Hello"}))
            chat-id (:chatId resp)]
        (is (string? chat-id))

        ;; Resume existing chat
        (eca/request! (fixture/chat-prompt-request
                       {:chat-id chat-id
                        :model "openai/gpt-4.1"
                        :message "Resume"}))

        ;; Delete chat to trigger chatEnd.
        (eca/request! [:chat/delete {:chat-id chat-id}]))

      ;; Shutdown session to trigger sessionEnd.
      (eca/request! (fixture/shutdown-request))
      (eca/notify! (fixture/exit-notification))

      (let [lines (->> (slurp log-path)
                       string/split-lines
                       (remove string/blank?))]
        ;; Expected order:
        ;; - sessionStart
        ;; - chatStart:false (new chat, not resumed)
        ;; - chatEnd
        ;; - sessionEnd
        (if win? (is (= 5 (count lines))) ;; The used command results in bad encoding in Windows etc...
            (is (= (if win?
                     ["??sessionStart\r" "chatStart:false\r" "chatEnd\r" "sessionEnd\r" ""]
                     ["sessionStart" "chatStart:false" "chatEnd" "sessionEnd"])
                   lines)))))))

(deftest posttoolcall-receives-tool-response-test
  (testing "postToolCall hook receives tool_response and tool_input after tool execution"
    (eca/start-process!)

    (llm.mocks/set-case! :tool-calling-0)

    (let [log-path (io/file h/default-root-project-path ".eca/posttool-log.txt")
          win? (string/starts-with? (System/getProperty "os.name") "Windows")]
      (io/make-parents log-path)
      (io/delete-file log-path true)

      (eca/request!
       (fixture/initialize-request
        {:initializationOptions
         (hooks-init-options
          {"post-tool" {:type "postToolCall"
                        :actions [{:type "shell"
                                   ;; Use a single jq invocation to extract both values
                                   ;; stdin is only available once per hook execution
                                   :shell (if win?
                                            (str "$Input | Set-Content " log-path)
                                            (str "cat >" log-path))}]}})}))

      (eca/notify! (fixture/initialized-notification))

      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:model "openai/gpt-5"
                                 :message "What files you see?"}))
            chat-id (:chatId resp)
            ;; Drain notifications until progress finished
            _ (loop []
                (let [n (eca/client-awaits-server-notification :chat/contentReceived)]
                  (when-not (and (= chat-id (:chatId n))
                                 (= "system" (:role n))
                                 (= "progress" (get-in n [:content :type]))
                                 (= "finished" (get-in n [:content :state])))
                    (recur))))]

        (is (match?
             {:chatId (m/pred string?)
              :model "openai/gpt-5"
              :status "prompting"}
             resp))

        (let [hook-data (json/parse-string (slurp log-path) true)]
          (is (match?
               {:tool_input    {:path (m/pred string?)}
                :tool_response [{:type "text"
                                 :text (m/pred #(and (string? %)
                                                     (not (string/blank? %))))}]
                :chat_id       (m/pred string?)
                :server        (m/equals "eca")
                :db_cache_path (m/pred string?)
                :agent         (m/equals "build")
                :behavior      (m/equals "build")
                :hook_type     (m/equals "postToolCall")
                :hook_name     (m/equals "postTool")
                :error         (m/equals false)
                :workspaces    (m/seq-of (m/pred string?))
                :tool_name     (m/equals "directory_tree")}
               hook-data))
          ;; Explicitly check that we got some file listing content
          (is (string/includes? (get-in hook-data [:tool_response 0 :text]) "file1.md")))))))

(deftest pretoolcall-approval-deny-test
  (testing "preToolCall hook can reject tool calls via approval:deny"
    (eca/start-process!)

    (llm.mocks/set-case! :tool-calling-0)

    (eca/request!
     (fixture/initialize-request
      {:initializationOptions
       (hooks-init-options
        {"deny-tool" {:type "preToolCall"
                      :actions [{:type "shell"
                                 :shell "echo '{\"approval\":\"deny\",\"additionalContext\":\"Tool blocked by policy\"}'"}]}})}))

    (eca/notify! (fixture/initialized-notification))

    (let [resp (eca/request! (fixture/chat-prompt-request
                              {:model "openai/gpt-5"
                               :message "What files you see?"}))
          chat-id (:chatId resp)
          ;; Collect notifications until progress finished
          notifications (loop [acc []]
                          (let [n (eca/client-awaits-server-notification :chat/contentReceived)]
                            (if (and (= chat-id (:chatId n))
                                     (= "system" (:role n))
                                     (= "progress" (get-in n [:content :type]))
                                     (= "finished" (get-in n [:content :state])))
                              (conj acc n)
                              (recur (conj acc n)))))]

      (is (match?
           {:chatId (m/pred string?)
            :model "openai/gpt-5"
            :status "prompting"}
           resp))

      ;; Verify the hook ran (name is camelCased from config key "deny-tool" -> "denyTool")
      (is (some #(and (= "system" (:role %))
                      (= "hookActionFinished" (get-in % [:content :type]))
                      (= "denyTool" (get-in % [:content :name]))
                      (= 0 (get-in % [:content :status])))
                notifications)
          "preToolCall hook should have executed successfully")

      ;; Verify tool call was rejected
      (is (some #(and (= "assistant" (:role %))
                      (= "toolCallRejected" (get-in % [:content :type]))
                      (= "directory_tree" (get-in % [:content :name])))
                notifications)
          "Tool call should have been rejected"))))

(deftest pretoolcall-exit-code-rejection-with-stop-test
  (testing "preToolCall hook exit code 2 rejects tool and continue:false stops chat"
    (let [win? (string/starts-with? (System/getProperty "os.name") "Windows")]
      (eca/start-process!)

      (llm.mocks/set-case! :tool-calling-0)

      (eca/request!
       (fixture/initialize-request
        {:initializationOptions
         (hooks-init-options
          {"reject-and-stop" {:type    "preToolCall"
                              :actions [{:type  "shell"
                                            ;; Exit code 2 means rejection, with continue:false and stopReason
                                         :shell (if win?
                                                  "Write-Output '{\"continue\":false,\"stopReason\":\"Security policy violation\"}'; exit 2"
                                                  "echo '{\"continue\":false,\"stopReason\":\"Security policy violation\"}' && exit 2")}]}})})))

    (eca/notify! (fixture/initialized-notification))

    (let [resp (eca/request! (fixture/chat-prompt-request
                              {:model "openai/gpt-5"
                               :message "What files you see?"}))
          chat-id (:chatId resp)
          ;; Collect notifications until progress finished
          notifications (loop [acc []]
                          (let [n (eca/client-awaits-server-notification :chat/contentReceived)]
                            (if (and (= chat-id (:chatId n))
                                     (= "system" (:role n))
                                     (= "progress" (get-in n [:content :type]))
                                     (= "finished" (get-in n [:content :state])))
                              (conj acc n)
                              (recur (conj acc n)))))]

      (is (match?
           {:chatId (m/pred string?)
            :model "openai/gpt-5"
            :status "prompting"}
           resp))

      ;; Verify tool call was rejected
      (is (some #(and (= "assistant" (:role %))
                      (= "toolCallRejected" (get-in % [:content :type]))
                      (= "directory_tree" (get-in % [:content :name])))
                notifications)
          "Tool call should have been rejected")

      ;; Verify stopReason was displayed
      (is (some #(and (= "system" (:role %))
                      (= "text" (get-in % [:content :type]))
                      (= "Security policy violation" (get-in % [:content :text])))
                notifications)
          "Stop reason should have been displayed"))))

(deftest prerequest-additional-context-test
  (testing "preRequest hook additionalContext is appended to user message"
    (eca/start-process!)

    (llm.mocks/set-case! :simple-text-0)

    (eca/request!
     (fixture/initialize-request
      {:initializationOptions
       (hooks-init-options
        {"add-context" {:type "preRequest"
                        :actions [{:type "shell"
                                   :shell "echo '{\"additionalContext\":\"INJECTED_CONTEXT_FROM_HOOK\"}'"}]}})}))

    (eca/notify! (fixture/initialized-notification))

    (let [resp (eca/request! (fixture/chat-prompt-request
                              {:model "openai/gpt-4.1"
                               :message "Hello"}))
          chat-id (:chatId resp)
          ;; Drain notifications until finished
          _ (loop []
              (let [n (eca/client-awaits-server-notification :chat/contentReceived)]
                (when-not (and (= chat-id (:chatId n))
                               (= "system" (:role n))
                               (= "progress" (get-in n [:content :type]))
                               (= "finished" (get-in n [:content :state])))
                  (recur))))]

      (is (match?
           {:chatId (m/pred string?)
            :model "openai/gpt-4.1"
            :status "prompting"}
           resp))

      ;; The LLM request should contain the additionalContext wrapped in XML
      (is (match?
           {:input (m/embeds
                    [{:role "user"
                      :content (m/embeds
                                [{:type "input_text"
                                  :text (m/pred #(string/includes? % "INJECTED_CONTEXT_FROM_HOOK"))}])}])}
           (llm.mocks/get-req-body :simple-text-0))))))
