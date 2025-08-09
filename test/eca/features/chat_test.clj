(ns eca.features.chat-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.chat :as f.chat]
   [eca.features.tools :as f.tools]
   [eca.features.tools.mcp :as f.mcp]
   [eca.llm-api :as llm-api]
   [eca.test-helper :as h]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(defn ^:private complete! [params mocks]
  (let [req-id (:request-id params)
        {:keys [chat-id] :as resp}
        (with-redefs [llm-api/complete! (:api-mock mocks)
                      f.tools/call-tool! (:call-tool-mock mocks)]
          (f.chat/prompt params (h/db*) (h/messenger) (h/config)))]
    (is (match? {:chat-id string? :status :success} resp))
    {:chat-id chat-id
     :req-id req-id}))

(deftest prompt-basic-test
  (testing "Simple hello"
    (h/reset-components!)
    (let [{:keys [chat-id req-id]}
          (complete!
           {:message "Hey!"
            :request-id "1"}
           {:api-mock
            (fn [{:keys [on-first-response-received
                         on-message-received]}]
              (on-first-response-received {:type :text :text "Hey"})
              (on-message-received {:type :text :text "Hey"})
              (on-message-received {:type :text :text " you!"})
              (on-message-received {:type :finish}))})]
      (is (match?
           {chat-id {:id chat-id
                     :messages [{:role "user" :content "Hey!"}
                                {:role "assistant" :content "Hey you!"}]}}
           (:chats (h/db))))
      (is (match?
           {:chat-content-received
            [{:chat-id chat-id
              :request-id req-id
              :content {:type :text :text "Hey!\n"}
              :role :user}
             {:chat-id chat-id
              :request-id req-id
              :content {:type :progress :state :running :text "Waiting model"}
              :role :system}
             {:chat-id chat-id
              :request-id req-id
              :content {:type :progress :state :running :text "Generating"}
              :role :system}
             {:chat-id chat-id
              :request-id req-id
              :content {:type :text :text "Hey"}
              :role :assistant}
             {:chat-id chat-id
              :request-id req-id
              :content {:type :text :text " you!"}
              :role :assistant}
             {:chat-id chat-id
              :request-id req-id
              :content {:state :finished :type :progress}
              :role :system}]}
           (h/messages)))))
  (testing "LLM error"
    (h/reset-components!)
    (let [{:keys [chat-id req-id]}
          (complete!
           {:message "Hey!"
            :request-id "1"}
           {:api-mock
            (fn [{:keys [on-error]}]
              (on-error {:message "Error from mocked API"}))})]
      (is (match?
           {chat-id {:id chat-id :messages m/absent}}
           (:chats (h/db))))
      (is (match?
           {:chat-content-received
            [{:chat-id chat-id
              :request-id req-id
              :content {:type :text :text "Hey!\n"}
              :role :user}
             {:chat-id chat-id
              :request-id req-id
              :content {:type :progress :state :running :text "Waiting model"}
              :role :system}
             {:chat-id chat-id
              :request-id req-id
              :content {:type :text :text "Error from mocked API"}
              :role :system}
             {:chat-id chat-id
              :request-id req-id
              :content {:state :finished :type :progress}
              :role :system}]}
           (h/messages))))))

(deftest prompt-multiple-text-interaction-test
  (testing "Chat history"
    (h/reset-components!)
    (let [res-1
          (complete!
           {:message "Count with me: 1 mississippi"
            :request-id "1"}
           {:api-mock
            (fn [{:keys [on-first-response-received
                         on-message-received]}]
              (on-first-response-received {:type :text :text "2"})
              (on-message-received {:type :text :text "2"})
              (on-message-received {:type :text :text " mississippi"})
              (on-message-received {:type :finish}))})
          chat-id-1 (:chat-id res-1)
          req-id-1 (:req-id res-1)]
      (is (match?
           {chat-id-1 {:id chat-id-1
                       :messages [{:role "user" :content "Count with me: 1 mississippi"}
                                  {:role "assistant" :content "2 mississippi"}]}}
           (:chats (h/db))))
      (is (match?
           {:chat-content-received
            [{:chat-id chat-id-1
              :request-id req-id-1
              :content {:type :text :text "Count with me: 1 mississippi\n"}
              :role :user}
             {:chat-id chat-id-1
              :request-id req-id-1
              :content {:type :progress :state :running :text "Waiting model"}
              :role :system}
             {:chat-id chat-id-1
              :request-id req-id-1
              :content {:type :progress :state :running :text "Generating"}
              :role :system}
             {:chat-id chat-id-1
              :request-id req-id-1
              :content {:type :text :text "2"}
              :role :assistant}
             {:chat-id chat-id-1
              :request-id req-id-1
              :content {:type :text :text " mississippi"}
              :role :assistant}
             {:chat-id chat-id-1
              :request-id req-id-1
              :content {:state :finished :type :progress}
              :role :system}]}
           (h/messages)))
      (h/reset-messenger!)
      (let [res-2
            (complete!
             {:message "3 mississippi"
              :chat-id chat-id-1
              :request-id "2"}
             {:api-mock
              (fn [{:keys [on-first-response-received
                           on-message-received]}]
                (on-first-response-received {:type :text :text "4"})
                (on-message-received {:type :text :text "4"})
                (on-message-received {:type :text :text " mississippi"})
                (on-message-received {:type :finish}))})
            chat-id-2 (:chat-id res-2)
            req-id-2 (:req-id res-2)]
        (is (match?
             {chat-id-2 {:id chat-id-2
                         :messages [{:role "user" :content "Count with me: 1 mississippi"}
                                    {:role "assistant" :content "2 mississippi"}
                                    {:role "user" :content "3 mississippi"}
                                    {:role "assistant" :content "4 mississippi"}]}}
             (:chats (h/db))))
        (is (match?
             {:chat-content-received
              [{:chat-id chat-id-2
                :request-id req-id-2
                :content {:type :text :text "3 mississippi\n"}
                :role :user}
               {:chat-id chat-id-2
                :request-id req-id-2
                :content {:type :progress :state :running :text "Waiting model"}
                :role :system}
               {:chat-id chat-id-2
                :request-id req-id-2
                :content {:type :progress :state :running :text "Generating"}
                :role :system}
               {:chat-id chat-id-2
                :request-id req-id-2
                :content {:type :text :text "4"}
                :role :assistant}
               {:chat-id chat-id-2
                :request-id req-id-2
                :content {:type :text :text " mississippi"}
                :role :assistant}
               {:chat-id chat-id-2
                :request-id req-id-2
                :content {:state :finished :type :progress}
                :role :system}]}
             (h/messages)))))))

(deftest basic-tool-calling-prompt-test
  (testing "Asking to list directories, LLM will check
for allowed directories and then list files"
    (h/reset-components!)
    (let [{:keys [chat-id]}
          (complete!
           {:message "List the files you are allowed to see"
            :request-id "1"}
           {:api-mock
            (fn [{:keys [on-first-response-received
                         on-message-received
                         on-prepare-tool-call
                         on-tools-called]}]
              (on-first-response-received {:type :text :text "Ok,"})
              (on-message-received {:type :text :text "Ok,"})
              (on-message-received {:type :text :text " working on it"})
              (on-prepare-tool-call {:id "call-1" :name "list_allowed_directories" :arguments-text ""})
              (on-tools-called [{:id "call-1" :name "list_allowed_directories" :arguments {}}])
              (on-message-received {:type :text :text "I can see: \n"})
              (on-message-received {:type :text :text "/foo/bar"})
              (on-message-received {:type :finish}))
            :call-tool-mock
            (constantly {:error false
                         :contents [{:type :text :content "Allowed directories: /foo/bar"}]})})]
      (is (match?
           {chat-id {:id chat-id
                     :messages [{:role "user" :content "List the files you are allowed to see"}
                                {:role "assistant" :content "Ok, working on it"}
                                {:role "tool_call" :content {:id "call-1" :name "list_allowed_directories" :arguments {}}}
                                {:role "tool_call_output" :content {:id "call-1" :name "list_allowed_directories" :arguments {}
                                                                    :output {:error false
                                                                             :contents [{:content "Allowed directories: /foo/bar"
                                                                                         :type :text}]}}}
                                {:role "assistant" :content "I can see: \n/foo/bar"}]}}
           (:chats (h/db))))
      (is (match?
           {:chat-content-received
            [{:role :user :content {:type :text :text "List the files you are allowed to see\n"}}
             {:role :system :content {:type :progress :state :running :text "Waiting model"}}
             {:role :system :content {:type :progress :state :running :text "Generating"}}
             {:role :assistant :content {:type :text :text "Ok,"}}
             {:role :assistant :content {:type :text :text " working on it"}}
             {:role :assistant :content {:type :toolCallPrepare :id "call-1" :name "list_allowed_directories" :arguments-text "" :manual-approval false}}
             {:role :assistant :content {:type :toolCallRun :id "call-1" :name "list_allowed_directories" :arguments {} :manual-approval false}}
             {:role :assistant :content {:type :toolCalled :id "call-1" :name "list_allowed_directories" :arguments {} :outputs [{:content "Allowed directories: /foo/bar" :type :text}]}}
             {:role :system :content {:type :progress :state :running :text "Generating"}}
             {:role :assistant :content {:type :text :text "I can see: \n"}}
             {:role :assistant :content {:type :text :text "/foo/bar"}}
             {:role :system :content {:state :finished :type :progress}}]}
           (h/messages))))))

(deftest send-mcp-prompt-test
  (testing "Argument mapping for send-mcp-prompt! should map arg values to prompt argument names"
    (let [test-arguments [{:name "foo"} {:name "bar"}]
          prompt-args (atom nil)
          test-chat-ctx {:db* (atom {})}
          invoked? (atom nil)]
      (with-redefs [f.mcp/all-prompts (fn [_]
                                        [{:name "awesome-prompt" :arguments test-arguments}])
                    f.mcp/get-prompt! (fn [_ args-map _]
                                        (reset! prompt-args args-map)
                                        {:messages [{:role :user :content "test"}]})
                    f.chat/prompt-messages! (fn [messages _reason? ctx] (reset! invoked? [messages ctx]))]
        (#'f.chat/send-mcp-prompt! {:prompt "awesome-prompt" :args [42 "yo"]} test-chat-ctx)
        (is (match?
             @prompt-args
             {"foo" 42 "bar" "yo"}))
        (is (match?
             @invoked?
             [[{:role :user :content "test"}] test-chat-ctx]))))))
