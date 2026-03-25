(ns integration.chat.commands-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [integration.eca :as eca]
   [integration.fixture :as fixture]
   [integration.helper :refer [match-content] :as h]
   [llm-mock.mocks :as llm.mocks]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(eca/clean-after-test)

(deftest query-commands
  (eca/start-process!)

  (eca/request! (fixture/initialize-request))
  (eca/notify! (fixture/initialized-notification))

  (testing "We query all available commands"
    (let [resp (eca/request! (fixture/chat-query-commands-request
                              {:query ""}))]
      (is (match?
           {:chatId nil
            :commands [{:name "init" :arguments []}
                       {:name "login" :arguments [{:name "provider-id"}]}
                       {:name "skills" :arguments []}
                       {:name "skill-create" :arguments [{:name "name"} {:name "prompt"}]}
                       {:name "costs" :arguments []}
                       {:name "compact" :arguments [{:name "additional-input"}]}
                       {:name "resume" :arguments [{:name "chat-id"}]}
                       {:name "remote" :arguments []}
                       {:name "config" :arguments []}
                       {:name "doctor" :arguments []}
                       {:name "repo-map-show" :arguments []}
                       {:name "prompt-show" :arguments [{:name "optional-prompt"}]}
                       {:name "subagents" :arguments []}
                       {:name "plugins" :arguments []}
                       {:name "plugin-install" :arguments [{:name "plugin"}]}
                       {:name "plugin-uninstall" :arguments [{:name "plugin"}]}]}
           resp))))

  (testing "We query specific commands"
    (let [resp (eca/request! (fixture/chat-query-commands-request
                              {:query "co"}))]
      (is (match?
           {:chatId nil
            :commands [{:name "login" :arguments [{:name "provider-id"}]}
                       {:name "skill-create" :arguments [{:name "name"} {:name "prompt"}]}
                       {:name "costs" :arguments []}
                       {:name "compact" :arguments [{:name "additional-input"}]}
                       {:name "remote" :arguments []}
                       {:name "config" :arguments []}
                       {:name "subagents" :arguments []}
                       {:name "plugins" :arguments []}]}
           resp))))

  (testing "We send a built-in command"
    (let [resp (eca/request! (fixture/chat-prompt-request
                              {:message "/prompt-show"}))
          chat-id (:chatId resp)]
      (is (match?
           {:chatId string?
            :model string?
            :status "prompting"}
           resp))

      (match-content chat-id "user" {:type "text" :text "/prompt-show\n"})
      (match-content chat-id "system" {:type "text" :text (m/pred #(string/includes? % "You are ECA"))})
      (match-content chat-id "system" {:type "progress" :state "finished"}))))

(deftest mcp-prompts
  (eca/start-process!)

  (eca/request! (fixture/initialize-request
                 {:initializationOptions (merge fixture/default-init-options
                                                {:mcpServers {"mcp-server-sample"
                                                              (if h/windows?
                                                                {:command "cmd.exe"
                                                                 :args ["/c" (str "cd /d " h/mcp-server-sample-path " && clojure -M:server")]}
                                                                {:command "bash"
                                                                 :args ["-c" (str "cd " h/mcp-server-sample-path " && clojure -M:server")]})}})}))
  (eca/notify! (fixture/initialized-notification))
  (testing "ECA tools"
    (is (match? {:type "native"}
                (eca/client-awaits-server-notification :tool/serverUpdated))))
  (testing "Mcp starting"
    (is (match? {:type "mcp"
                 :name "mcpServerSample"}
                (eca/client-awaits-server-notification :tool/serverUpdated))))
  (testing "Mcp started"
    (is (match? {:type "mcp"
                 :name "mcpServerSample"}
                (eca/client-awaits-server-notification :tool/serverUpdated))))

  (testing "MCP prompts available when querying commands"
    (let [resp (eca/request! (fixture/chat-query-commands-request
                              {:query ""}))]
      (is (match?
           {:chatId nil
            :commands (m/embeds
                       [{:name "mcpServerSample:my-prompt" :arguments [{:name "some-arg-1"}]}])}
           resp)))))

(deftest compact-command
  (eca/start-process!)

  (eca/request! (fixture/initialize-request))
  (eca/notify! (fixture/initialized-notification))

  (let [chat-id* (atom nil)]
    (testing "Setup: send an initial message to create chat history"
      (llm.mocks/set-case! :simple-text-0)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:model "anthropic/claude-sonnet-4-6"
                                 :message "Tell me a joke!"}))
            chat-id (reset! chat-id* (:chatId resp))]
        (match-content chat-id "user" {:type "text" :text "Tell me a joke!\n"})
        (match-content chat-id "system" {:type "metadata" :title "Some Cool Title"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "text" :text "Knock"})
        (match-content chat-id "assistant" {:type "text" :text " knock!"})
        (match-content chat-id "system" {:type "usage"})
        (match-content chat-id "system" {:type "progress" :state "finished"})))

    (testing "Compact calls the tool and finishes cleanly without a second LLM request"
      (llm.mocks/set-case! :compact-0)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:chat-id @chat-id*
                                 :model "anthropic/claude-sonnet-4-6"
                                 :message "/compact"}))
            chat-id (:chatId resp)]

        (is (match? {:chatId (m/pred string?)
                     :model "anthropic/claude-sonnet-4-6"
                     :status "prompting"}
                    resp))

        ;; User message
        (match-content chat-id "user" {:type "text" :text "/compact\n"})
        ;; Progress
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        ;; Tool call preparation (streaming)
        (match-content chat-id "assistant" {:type "toolCallPrepare"
                                            :origin "native"
                                            :id "compact-1"
                                            :name "compact_chat"
                                            :argumentsText ""
                                            :summary "Compacting..."})
        (match-content chat-id "assistant" {:type "toolCallPrepare"
                                            :origin "native"
                                            :id "compact-1"
                                            :name "compact_chat"
                                            :argumentsText "{\"summary\":\"Test summary of the conversation\"}"
                                            :summary "Compacting..."})
        ;; Usage from LLM response
        (match-content chat-id "system" {:type "usage"})
        ;; Tool execution
        (match-content chat-id "assistant" {:type "toolCallRun"
                                            :origin "native"
                                            :id "compact-1"
                                            :name "compact_chat"
                                            :arguments {:summary "Test summary of the conversation"}
                                            :manualApproval false
                                            :summary "Compacting..."})
        (match-content chat-id "assistant" {:type "toolCallRunning"
                                            :origin "native"
                                            :id "compact-1"
                                            :name "compact_chat"
                                            :summary "Compacting..."})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Calling tool"})
        (match-content chat-id "assistant" {:type "toolCalled"
                                            :origin "native"
                                            :id "compact-1"
                                            :name "compact_chat"
                                            :error false
                                            :totalTimeMs (m/pred number?)
                                            :outputs [{:type "text" :text "Compacted successfully!"}]})
        ;; Chat finishes, then compact side-effect sends summary messages
        (match-content chat-id "system" {:type "progress" :state "finished"})
        (match-content chat-id "system" {:type "text" :text "Compacted chat"})
        (match-content chat-id "system" {:type "usage"})

        ;; Key assertion: only one LLM request was made (no tool_result continuation).
        ;; Before the fix, the continue-fn would trigger a second LLM call whose
        ;; request body would overwrite this one and contain tool_result messages.
        (is (not-any? (fn [{:keys [content]}]
                        (and (sequential? content)
                             (some #(= "tool_result" (:type %)) content)))
                      (:messages (llm.mocks/get-req-body :compact-0)))
            "Only one LLM request should be made - no tool_result continuation")))))
