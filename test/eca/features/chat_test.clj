(ns eca.features.chat-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.chat :as f.chat]
   [eca.features.prompt :as f.prompt]
   [eca.features.tools :as f.tools]
   [eca.features.tools.mcp :as f.mcp]
   [eca.llm-api :as llm-api]
   [eca.test-helper :as h]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(defn ^:private prompt! [params mocks]
  (let [{:keys [chat-id] :as resp}
        (with-redefs [llm-api/sync-or-async-prompt! (:api-mock mocks)
                      llm-api/sync-prompt! (constantly nil)
                      f.tools/call-tool! (:call-tool-mock mocks)
                      f.tools/all-tools (:all-tools-mock mocks)
                      f.tools/approval (constantly :allow)]
          (h/config! {:env "test"})
          (f.chat/prompt params (h/db*) (h/messenger) (h/config) (h/metrics)))]
    (is (match? {:chat-id string? :status :prompting} resp))
    {:chat-id chat-id}))

(defn ^:private deep-sleep
  "Sleep for the given duration in milliseconds, ignoring interrupts.
   Continues sleeping until the full duration has elapsed."
  [millis]
  (let [deadline (+ (System/currentTimeMillis) millis)]
    (loop [remaining (- deadline (System/currentTimeMillis))]
      (when (pos? remaining)
        (try
          (Thread/sleep (long remaining))
          (catch InterruptedException _))
        (recur (- deadline (System/currentTimeMillis)))))))

(deftest prompt-basic-test
  (testing "Simple hello"
    (h/reset-components!)
    (let [{:keys [chat-id]}
          (prompt!
           {:message "Hey!"}
           {:all-tools-mock (constantly [])
            :api-mock
            (fn [{:keys [on-first-response-received
                         on-message-received]}]
              (on-first-response-received {:type :text :text "Hey"})
              (on-message-received {:type :text :text "Hey"})
              (on-message-received {:type :text :text " you!"})
              (on-message-received {:type :finish}))})]
      (is (match?
           {chat-id {:id chat-id
                     :messages [{:role "user" :content [{:type :text :text "Hey!"}]}
                                {:role "assistant" :content [{:type :text :text "Hey you!"}]}]}}
           (:chats (h/db))))
      (is (match?
           {:chat-content-received
            [{:chat-id chat-id
              :content {:type :text :text "Hey!\n"}
              :role :user}
             {:chat-id chat-id
              :content {:type :progress :state :running :text "Waiting model"}
              :role :system}
             {:chat-id chat-id
              :content {:type :progress :state :running :text "Generating"}
              :role :system}
             {:chat-id chat-id
              :content {:type :text :text "Hey"}
              :role :assistant}
             {:chat-id chat-id
              :content {:type :text :text " you!"}
              :role :assistant}
             {:chat-id chat-id
              :content {:state :finished :type :progress}
              :role :system}]}
           (h/messages)))))
  (testing "LLM error"
    (h/reset-components!)
    (let [{:keys [chat-id]}
          (prompt!
           {:message "Hey!"}
           {:all-tools-mock (constantly [])
            :api-mock
            (fn [{:keys [on-error]}]
              (on-error {:message "Error from mocked API"}))})]
      (is (match?
           {chat-id {:id chat-id :messages m/absent}}
           (:chats (h/db))))
      (is (match?
           {:chat-content-received
            [{:chat-id chat-id
              :content {:type :text :text "Hey!\n"}
              :role :user}
             {:chat-id chat-id
              :content {:type :progress :state :running :text "Waiting model"}
              :role :system}
             {:chat-id chat-id
              :content {:type :text :text "Error from mocked API"}
              :role :system}
             {:chat-id chat-id
              :content {:state :finished :type :progress}
              :role :system}]}
           (h/messages))))))

(deftest prompt-multiple-text-interaction-test
  (testing "Chat history"
    (h/reset-components!)
    (let [res-1
          (prompt!
           {:message "Count with me: 1 mississippi"}
           {:all-tools-mock (constantly [])
            :api-mock
            (fn [{:keys [on-first-response-received
                         on-message-received]}]
              (on-first-response-received {:type :text :text "2"})
              (on-message-received {:type :text :text "2"})
              (on-message-received {:type :text :text " mississippi"})
              (on-message-received {:type :finish}))})
          chat-id-1 (:chat-id res-1)]
      (is (match?
           {chat-id-1 {:id chat-id-1
                       :messages [{:role "user" :content [{:type :text :text "Count with me: 1 mississippi"}]}
                                  {:role "assistant" :content [{:type :text :text "2 mississippi"}]}]}}
           (:chats (h/db))))
      (is (match?
           {:chat-content-received
            [{:chat-id chat-id-1
              :content {:type :text :text "Count with me: 1 mississippi\n"}
              :role :user}
             {:chat-id chat-id-1
              :content {:type :progress :state :running :text "Waiting model"}
              :role :system}
             {:chat-id chat-id-1
              :content {:type :progress :state :running :text "Generating"}
              :role :system}
             {:chat-id chat-id-1
              :content {:type :text :text "2"}
              :role :assistant}
             {:chat-id chat-id-1
              :content {:type :text :text " mississippi"}
              :role :assistant}
             {:chat-id chat-id-1
              :content {:state :finished :type :progress}
              :role :system}]}
           (h/messages)))
      (h/reset-messenger!)
      (let [res-2
            (prompt!
             {:message "3 mississippi"
              :chat-id chat-id-1}
             {:all-tools-mock (constantly [])
              :api-mock
              (fn [{:keys [on-first-response-received
                           on-message-received]}]
                (on-first-response-received {:type :text :text "4"})
                (on-message-received {:type :text :text "4"})
                (on-message-received {:type :text :text " mississippi"})
                (on-message-received {:type :finish}))})
            chat-id-2 (:chat-id res-2)]
        (is (match?
             {chat-id-2 {:id chat-id-2
                         :messages [{:role "user" :content [{:type :text :text "Count with me: 1 mississippi"}]}
                                    {:role "assistant" :content [{:type :text :text "2 mississippi"}]}
                                    {:role "user" :content [{:type :text :text "3 mississippi"}]}
                                    {:role "assistant" :content [{:type :text :text "4 mississippi"}]}]}}
             (:chats (h/db))))
        (is (match?
             {:chat-content-received
              [{:chat-id chat-id-2
                :content {:type :text :text "3 mississippi\n"}
                :role :user}
               {:chat-id chat-id-2
                :content {:type :progress :state :running :text "Waiting model"}
                :role :system}
               {:chat-id chat-id-2
                :content {:type :progress :state :running :text "Generating"}
                :role :system}
               {:chat-id chat-id-2
                :content {:type :text :text "4"}
                :role :assistant}
               {:chat-id chat-id-2
                :content {:type :text :text " mississippi"}
                :role :assistant}
               {:chat-id chat-id-2
                :content {:state :finished :type :progress}
                :role :system}]}
             (h/messages)))))))

(deftest contexts-in-prompt-test
  (testing "When prompt contains @file we add a user message"
    (h/reset-components!)
    (let [{:keys [chat-id]}
          (prompt!
           {:message "Check @/path/to/file please"}
           {:all-tools-mock (constantly [])
            :api-mock
            (fn [{:keys [on-first-response-received
                         on-message-received]}]
              (on-first-response-received {:type :text :text "On it..."})
              (on-message-received {:type :text :text "On it..."})
              (on-message-received {:type :finish}))})]
      (is (match?
           {chat-id {:id chat-id
                     :messages [{:role "user" :content [{:type :text :text "Check @/path/to/file please"}
                                                        {:type :text :text (m/pred #(string/includes? % "<file path"))}]}
                                {:role "assistant" :content [{:type :text :text "On it..."}]}]}}
           (:chats (h/db))))
      (is (match?
           {:chat-content-received
            [{:chat-id chat-id
              :content {:type :text :text "Check @/path/to/file please\n"}
              :role :user}
             {:chat-id chat-id
              :content {:type :progress :state :running :text "Waiting model"}
              :role :system}
             {:chat-id chat-id
              :content {:type :progress :state :running :text "Generating"}
              :role :system}
             {:chat-id chat-id
              :content {:type :text :text "On it..."}
              :role :assistant}
             {:chat-id chat-id
              :content {:state :finished :type :progress}
              :role :system}]}
           (h/messages))))))

(deftest basic-tool-calling-prompt-test
  (testing "Asking to list directories, LLM will check for allowed directories and then list files"
    (h/reset-components!)
    (let [{:keys [chat-id]}
          (prompt!
           {:message "List the files you are allowed to see"}
           {:all-tools-mock (constantly [{:name "list_allowed_directories" :full-name "eca__list_allowed_directories" :server {:name "eca"}}])
            :api-mock
            (fn [{:keys [on-first-response-received
                         on-message-received
                         on-prepare-tool-call
                         on-tools-called]}]
              (on-first-response-received {:type :text :text "Ok,"})
              (on-message-received {:type :text :text "Ok,"})
              (on-message-received {:type :text :text " working on it"})
              (on-prepare-tool-call {:id "call-1" :full-name "eca__list_allowed_directories" :arguments-text ""})
              (on-tools-called [{:id "call-1" :full-name "eca__list_allowed_directories" :arguments {}}])
              (on-message-received {:type :text :text "I can see: \n"})
              (on-message-received {:type :text :text "/foo/bar"})
              (on-message-received {:type :finish}))
            :call-tool-mock
            (constantly {:error false
                         :contents [{:type :text :text "Allowed directories: /foo/bar"}]})})]
      (is (match?
           {chat-id {:id chat-id
                     :messages [{:role "user" :content [{:type :text :text "List the files you are allowed to see"}]}
                                {:role "assistant" :content [{:type :text :text "Ok, working on it"}]}
                                {:role "tool_call" :content {:id "call-1" :full-name "eca__list_allowed_directories" :arguments {}}}
                                {:role "tool_call_output" :content {:id "call-1" :full-name "eca__list_allowed_directories" :arguments {}
                                                                    :output {:error false
                                                                             :contents [{:text "Allowed directories: /foo/bar"
                                                                                         :type :text}]}}}
                                {:role "assistant" :content [{:type :text :text "I can see: \n/foo/bar"}]}]}}
           (:chats (h/db))))
      (is (match?
           {:chat-content-received
            [{:role :user :content {:type :text :text "List the files you are allowed to see\n"}}
             {:role :system :content {:type :progress :state :running :text "Waiting model"}}
             {:role :system :content {:type :progress :state :running :text "Generating"}}
             {:role :assistant :content {:type :text :text "Ok,"}}
             {:role :assistant :content {:type :text :text " working on it"}}
             {:role :assistant :content {:type :toolCallPrepare :id "call-1" :name "list_allowed_directories" :arguments-text ""}}
             {:role :assistant :content {:type :toolCallRun :id "call-1" :name "list_allowed_directories" :arguments {} :manual-approval false}}
             {:role :assistant :content {:type :toolCallRunning :id "call-1" :name "list_allowed_directories" :arguments {}}}
             {:role :system :content {:type :progress :state :running :text "Calling tool"}}
             {:role :assistant :content {:type :toolCalled :id "call-1" :name "list_allowed_directories" :arguments {} :total-time-ms number? :outputs [{:text "Allowed directories: /foo/bar" :type :text}]}}
             {:role :system :content {:type :progress :state :running :text "Generating"}}
             {:role :assistant :content {:type :text :text "I can see: \n"}}
             {:role :assistant :content {:type :text :text "/foo/bar"}}
             {:role :system :content {:state :finished :type :progress}}]}
           (h/messages))))))

(deftest concurrent-tool-calls-test
  (testing "Running three calls simultaneously"
    (h/reset-components!)
    (let [{:keys [chat-id]}
          (prompt!
           {:message "Run 3 read-only tool calls simultaneously."}
           {:all-tools-mock (constantly [{:name "ro_tool_1" :full-name "eca__ro_tool_1" :server {:name "eca"}}
                                         {:name "ro_tool_2" :full-name "eca__ro_tool_2" :server {:name "eca"}}
                                         {:name "ro_tool_3" :full-name "eca__ro_tool_3" :server {:name "eca"}}])
            :api-mock
            (fn [{:keys [on-first-response-received
                         on-message-received
                         on-prepare-tool-call
                         on-tools-called]}]
              (on-first-response-received {:type :text :text "Ok,"})
              (on-message-received {:type :text :text "Ok,"})
              (on-message-received {:type :text :text " working on it"})
              (on-prepare-tool-call {:id "call-1" :full-name "eca__ro_tool_1" :arguments-text ""})
              (on-prepare-tool-call {:id "call-2" :full-name "eca__ro_tool_2" :arguments-text ""})
              (on-prepare-tool-call {:id "call-3" :full-name "eca__ro_tool_3" :arguments-text ""})
              (on-tools-called [{:id "call-1" :full-name "eca__ro_tool_1" :arguments {}}
                                {:id "call-2" :full-name "eca__ro_tool_2" :arguments {}}
                                {:id "call-3" :full-name "eca__ro_tool_3" :arguments {}}])
              (on-message-received {:type :text :text "The tool calls returned: \n"})
              (on-message-received {:type :text :text "something"})
              (on-message-received {:type :finish}))
            :call-tool-mock
            ;; Ensure that the tools complete in the 3-2-1 order by adjusting sleep times
            (fn [name & _others]
              ;; When this is called, we are already in a future.
              (case name

                "ro_tool_1"
                (do (deep-sleep 900)
                    {:error false
                     :contents [{:type :text :text "RO tool call 1 result"}]})

                "ro_tool_2"
                (do (deep-sleep 600)
                    {:error false
                     :contents [{:type :text :text "RO tool call 2 result"}]})

                "ro_tool_3"
                (do (deep-sleep 100)
                    {:error false
                     :contents [{:type :text :text "RO tool call 3 result"}]})))})]

      (is (match?
           {chat-id {:id chat-id
                     :messages [{:role "user" :content [{:type :text :text "Run 3 read-only tool calls simultaneously."}]}
                                {:role "assistant" :content [{:type :text :text "Ok, working on it"}]}
                                {:role "tool_call" :content {:id "call-3" :full-name "eca__ro_tool_3" :arguments {}}}
                                {:role "tool_call_output" :content {:id "call-3"  :full-name "eca__ro_tool_3" :arguments {}
                                                                    :output {:error false
                                                                             :contents [{:type :text, :text "RO tool call 3 result"}]}}}
                                {:role "tool_call" :content {:id "call-2" :full-name "eca__ro_tool_2" :arguments {}}}
                                {:role "tool_call_output" :content {:id "call-2" :full-name "eca__ro_tool_2" :arguments {}
                                                                    :output {:error false
                                                                             :contents [{:type :text, :text "RO tool call 2 result"}]}}}
                                {:role "tool_call" :content {:id "call-1" :full-name "eca__ro_tool_1" :arguments {}}}
                                {:role "tool_call_output" :content {:id "call-1" :full-name "eca__ro_tool_1" :arguments {}
                                                                    :output {:error false
                                                                             :contents [{:type :text, :text "RO tool call 1 result"}]}}}
                                {:role "assistant" :content [{:type :text, :text "The tool calls returned: \nsomething"}]}]}}
           (:chats (h/db))))
      (is (match?
           {:chat-content-received
            [{:role :user :content {:type :text :text "Run 3 read-only tool calls simultaneously.\n"}}
             {:role :system :content {:type :progress :state :running, :text "Waiting model"}}
             {:role :system :content {:type :progress :state :running, :text "Generating"}}
             {:role :assistant :content {:type :text :text "Ok,"}}
             {:role :assistant :content {:type :text :text " working on it"}}
             {:role :assistant :content {:type :toolCallPrepare :id "call-1" :name "ro_tool_1" :arguments-text ""}}
             {:role :assistant :content {:type :toolCallPrepare :id "call-2" :name "ro_tool_2" :arguments-text ""}}
             {:role :assistant :content {:type :toolCallPrepare :id "call-3" :name "ro_tool_3" :arguments-text ""}}
             {:role :assistant :content {:type :toolCallRun :id "call-1" :name "ro_tool_1" :arguments {} :manual-approval false}}
             {:role :assistant :content {:type :toolCallRunning :id "call-1" :name "ro_tool_1" :arguments {}}}
             {:role :system :content {:type :progress :state :running, :text "Calling tool"}}
             {:role :assistant :content {:type :toolCallRun :id "call-2" :name "ro_tool_2" :arguments {} :manual-approval false}}
             {:role :assistant :content {:type :toolCallRunning :id "call-2" :name "ro_tool_2" :arguments {}}}
             {:role :system :content {:type :progress :state :running, :text "Calling tool"}}
             {:role :assistant :content {:type :toolCallRun :id "call-3" :name "ro_tool_3" :arguments {} :manual-approval false}}
             {:role :assistant :content {:type :toolCallRunning :id "call-3" :name "ro_tool_3" :arguments {}}}
             {:role :system :content {:type :progress :state :running, :text "Calling tool"}}
             {:role :assistant :content {:type :toolCalled :id "call-3" :name "ro_tool_3" :arguments {}
                                         :outputs [{:type :text :text "RO tool call 3 result"}]
                                         :error false}}
             {:role :system :content {:type :progress :state :running, :text "Generating"}}
             {:role :assistant :content {:type :toolCalled :id "call-2" :name "ro_tool_2" :arguments {}
                                         :outputs [{:type :text :text "RO tool call 2 result"}]
                                         :error false}}
             {:role :system :content {:type :progress :state :running, :text "Generating"}}
             {:role :assistant :content {:type :toolCalled :id "call-1" :name "ro_tool_1" :arguments {}
                                         :outputs [{:type :text :text "RO tool call 1 result"}]
                                         :error false}}
             {:role :system :content {:type :progress :state :running, :text "Generating"}}
             {:role :assistant :content {:type :text :text "The tool calls returned: \n"}}
             {:role :assistant :content {:type :text :text "something"}}
             {:role :system :content {:type :progress :state :finished}}]}
           (h/messages))))))

(deftest tool-calls-with-prompt-stop-test
  (testing "Three concurrent tool calls. Stopped before they all finished. Tool call 3 finishes. Calls 1,2 reject."
    (h/reset-components!)
    (let [wait-for-tool3 (promise)
          wait-for-tool2 (promise)
          wait-for-stop (promise)
          {:keys [chat-id]}
          (prompt!
           {:message "Run 3 read-only tool calls simultaneously."}
           {:all-tools-mock (constantly [{:name "ro_tool_1" :full-name "eca__ro_tool_1" :server {:name "eca"}}
                                         {:name "ro_tool_2" :full-name "eca__ro_tool_2" :server {:name "eca"}}
                                         {:name "ro_tool_3" :full-name "eca__ro_tool_3" :server {:name "eca"}}])
            :api-mock
            (fn [{:keys [on-first-response-received
                         on-prepare-tool-call
                         on-tools-called]}]
              (let [chat-id (first (keys (:chats (h/db))))]
                (on-first-response-received {:type :text :text "Ok,"})
                (on-prepare-tool-call {:id "call-1" :full-name "eca__ro_tool_1" :arguments-text ""})
                (on-prepare-tool-call {:id "call-2" :full-name "eca__ro_tool_2" :arguments-text ""})
                (on-prepare-tool-call {:id "call-3" :full-name "eca__ro_tool_3" :arguments-text ""})
                (future (Thread/sleep 400)
                        (when (= :timeout (deref wait-for-tool3 10000 :timeout))
                          (println "tool-calls-with-prompt-stop-test: deref in prompt stop future timed out"))
                        (Thread/sleep 50)
                        (f.chat/prompt-stop {:chat-id chat-id} (h/db*) (h/messenger) (h/metrics))
                        (deliver wait-for-stop true))
                (on-tools-called [{:id "call-1" :full-name "eca__ro_tool_1" :arguments {}}
                                  {:id "call-2" :full-name "eca__ro_tool_2" :arguments {}}
                                  {:id "call-3" :full-name "eca__ro_tool_3" :arguments {}}])))
            :call-tool-mock
            (fn [name & _others]
              ;; When this is called, we are already in a future
              (case name

                "ro_tool_1"
                (do (deep-sleep 1000)
                    (when (= :timeout (deref wait-for-tool2 10000 :timeout))
                      (println "tool-calls-with-prompt-stop-test: deref in tool 1 timed out"))
                    {:error false
                     :contents [{:type :text :text "RO tool call 1 result"}]})

                "ro_tool_2"
                (do (deep-sleep 800)
                    (when (= :timeout (deref wait-for-stop 10000 :timeout))
                      (println "tool-calls-with-prompt-stop-test: deref in tool 2 timed out"))
                    (deliver wait-for-tool2 true)
                    {:error false
                     :contents [{:type :text :text "RO tool call 2 result"}]})

                "ro_tool_3"
                (do (deep-sleep 200)
                    (deliver wait-for-tool3 true)
                    {:error false
                     :contents [{:type :text :text "RO tool call 3 result"}]})))})]
      (is (match? {chat-id
                   {:id chat-id
                    :messages [{:role "user" :content [{:type :text :text "Run 3 read-only tool calls simultaneously."}]}
                               {:role "tool_call" :content {:id "call-3" :full-name "eca__ro_tool_3" :arguments {}}}
                               {:role "tool_call_output" :content {:id "call-3" :full-name "eca__ro_tool_3" :arguments {}
                                                                   :output {:error false
                                                                            :contents [{:type :text, :text "RO tool call 3 result"}]}}}
                               {:role "tool_call" :content {:id "call-2" :full-name "eca__ro_tool_2" :arguments {}}}
                               {:role "tool_call_output" :content {:id "call-2" :full-name "eca__ro_tool_2" :arguments {}
                                                                   :output {:error false
                                                                            :contents [{:type :text, :text "RO tool call 2 result"}]}}}
                               {:role "tool_call" :content {:id "call-1" :full-name "eca__ro_tool_1" :arguments {}}}
                               {:role "tool_call_output" :content {:id "call-1" :full-name "eca__ro_tool_1" :arguments {}
                                                                   :output {:error false
                                                                            :contents [{:type :text, :text "RO tool call 1 result"}]}}}]}}
                  (:chats (h/db))))
      (is (match? {:chat-content-received
                   [{:role :user :content {:type :text :text "Run 3 read-only tool calls simultaneously.\n"}}
                    {:role :system :content {:type :progress :text "Waiting model"}}
                    {:role :system :content {:type :progress :text "Generating"}}
                    {:role :assistant :content {:type :toolCallPrepare :id "call-1" :name "ro_tool_1" :arguments-text ""}}
                    {:role :assistant :content {:type :toolCallPrepare :id "call-2" :name "ro_tool_2" :arguments-text ""}}
                    {:role :assistant :content {:type :toolCallPrepare :id "call-3" :name "ro_tool_3" :arguments-text ""}}
                    {:role :assistant :content {:type :toolCallRun :id "call-1" :name "ro_tool_1" :arguments {}}}
                    {:role :assistant :content {:type :toolCallRunning :id "call-1" :name "ro_tool_1" :arguments {}}}
                    {:role :system :content {:type :progress :state :running :text "Calling tool"}}
                    {:role :assistant :content {:type :toolCallRun :id "call-2" :name "ro_tool_2" :arguments {} :manual-approval false}}
                    {:role :assistant :content {:type :toolCallRunning :id "call-2" :name "ro_tool_2" :arguments {}}}
                    {:role :system :content {:type :progress :state :running :text "Calling tool"}}
                    {:role :assistant :content {:type :toolCallRun :id "call-3" :name "ro_tool_3" :arguments {} :manual-approval false}}
                    {:role :assistant :content {:type :toolCallRunning :id "call-3" :name "ro_tool_3" :arguments {}}}
                    {:role :system :content {:type :progress :state :running :text "Calling tool"}}
                    {:role :assistant :content {:type :toolCalled :id "call-3" :name "ro_tool_3" :arguments {}
                                                :outputs [{:type :text :text "RO tool call 3 result"}]}}
                    {:role :system :content {:type :progress :state :running :text "Generating"}}
                    {:role :system :content {:type :text :text "\nPrompt stopped"}}
                    {:role :system :content {:type :progress :state :finished}}
                    {:role :assistant :content {:type :toolCallRejected :id "call-2" :name "ro_tool_2" :arguments {} :reason :user}}
                    {:role :assistant :content {:type :toolCallRejected :id "call-1" :name "ro_tool_1" :arguments {} :reason :user}}]}
                  (h/messages))))))

(deftest send-mcp-prompt-test
  (testing "Argument mapping for send-mcp-prompt! should map arg values to prompt argument names"
    (let [test-arguments [{:name "foo"} {:name "bar"}]
          prompt-args (atom nil)
          test-chat-ctx {:db* (atom {})}
          invoked? (atom nil)]
      (with-redefs [f.mcp/all-prompts (fn [_]
                                        [{:name "awesome-prompt" :arguments test-arguments}])
                    f.prompt/get-prompt! (fn [_ args-map _]
                                           (reset! prompt-args args-map)
                                           {:messages [{:role :user :content "test"}]})
                    f.chat/prompt-messages! (fn [messages ctx] (reset! invoked? [messages ctx]))]
        (#'f.chat/send-mcp-prompt! {:prompt "awesome-prompt" :args [42 "yo"]} test-chat-ctx)
        (is (match?
             @prompt-args
             {"foo" 42 "bar" "yo"}))
        (is (match?
             @invoked?
             [[{:role :user :content "test"}] test-chat-ctx]))))))

(deftest message->decision-test
  (testing "plain prompt message"
    (is (= {:type :prompt-message
            :message "Hello world"}
           (#'f.chat/message->decision "Hello world" {} {}))))
  (testing "message starting with a absolute path"
    (is (= {:type :prompt-message
            :message "/path/to/file check this out"}
           (#'f.chat/message->decision "/path/to/file check this out" {} {}))))
  (testing "ECA command without args"
    (is (= {:type :eca-command
            :command "doctor"
            :args []}
           (#'f.chat/message->decision "/doctor" {} {}))))
  (testing "ECA command with args"
    (is (= {:type :eca-command
            :command "login"
            :args ["foo" "bar"]}
           (#'f.chat/message->decision "/login foo bar" {} {}))))
  (testing "ECA command with args with spaces in quotes"
    (is (= {:type :eca-command
            :command "login"
            :args ["foo bar" "baz" "qux bla blow"]}
           (#'f.chat/message->decision "/login \"foo bar\" baz \"qux bla blow\"" {} {}))))
  (with-redefs [f.mcp/all-prompts (constantly [{:name "prompt"
                                                :server "server"}])]
    (testing "MCP prompt without args"
      (is (= {:type :mcp-prompt
              :server "server"
              :prompt "prompt"
              :args []}
             (#'f.chat/message->decision "/server:prompt" {} {}))))
    (testing "MCP prompt with args"
      (is (= {:type :mcp-prompt
              :server "server"
              :prompt "prompt"
              :args ["arg1" "arg2"]}
             (#'f.chat/message->decision "/server:prompt arg1 arg2" {} {}))))))

(deftest rollback-chat-test
  (testing "Rollback chat removes messages after content-id"
    (h/reset-components!)
    (let [{:keys [chat-id]}
          (prompt!
           {:message "Count with me: 1"}
           {:all-tools-mock (constantly [])
            :api-mock
            (fn [{:keys [on-first-response-received
                         on-message-received]}]
              (on-first-response-received {:type :text :text "2"})
              (on-message-received {:type :text :text "2"})
              (on-message-received {:type :finish}))})
          first-content-id (get-in (h/db) [:chats chat-id :messages 0 :content-id])
          _ (is (some? first-content-id) "first-content-id should exist")]
      ;; Verify initial state
      (is (match?
           {chat-id {:id chat-id
                     :messages [{:role "user" :content [{:type :text :text "Count with me: 1"}] :content-id first-content-id}
                                {:role "assistant" :content [{:type :text :text "2"}]}]}}
           (:chats (h/db))))

      ;; Add second message
      (h/reset-messenger!)
      (prompt!
       {:message "3"
        :chat-id chat-id}
       {:all-tools-mock (constantly [])
        :api-mock
        (fn [{:keys [on-first-response-received
                     on-message-received]}]
          (on-first-response-received {:type :text :text "4"})
          (on-message-received {:type :text :text "4"})
          (on-message-received {:type :finish}))})
      (let [second-content-id (get-in (h/db) [:chats chat-id :messages 2 :content-id])]

        ;; Verify we now have 4 messages
        (is (match?
             {chat-id {:id chat-id
                       :messages [{:role "user" :content [{:type :text :text "Count with me: 1"}] :content-id first-content-id}
                                  {:role "assistant" :content [{:type :text :text "2"}]}
                                  {:role "user" :content [{:type :text :text "3"}] :content-id second-content-id}
                                  {:role "assistant" :content [{:type :text :text "4"}]}]}}
             (:chats (h/db))))

        ;; Rollback to second message (keep first 2 messages, remove last 2)
        (h/reset-messenger!)
        (is (= {} (f.chat/rollback-chat {:chat-id chat-id :content-id second-content-id} (h/db*) (h/messenger))))

        ;; Verify messages after content-id are removed (keeps messages before content-id)
        (is (match?
             {chat-id {:id chat-id
                       :messages [{:role "user" :content [{:type :text :text "Count with me: 1"}] :content-id first-content-id}
                                  {:role "assistant" :content [{:type :text :text "2"}]}]}}
             (:chats (h/db))))

        ;; Verify messenger received chat-clear and then messages
        (is (match?
             {:chat-clear [{:chat-id chat-id :messages true}]
              :chat-content-received
              [{:chat-id chat-id
                :content {:type :text :text "\nCount with me: 1" :content-id first-content-id}
                :role "user"}
               {:chat-id chat-id
                :content {:type :text :text "\n2"}
                :role "assistant"}]}
             (h/messages)))))))
