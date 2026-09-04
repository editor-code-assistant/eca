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
                       {:name "model" :arguments [{:name "full-model"}]}
                       {:name "skills" :arguments []}
                       {:name "skill-create"
                        :arguments [{:name "name" :description "The skill name" :required true}
                                    {:name "prompt" :description "What to consider as this skill content" :required true}]}
                       {:name "costs" :arguments []}
                       {:name "context" :arguments []}
                       {:name "compact" :arguments [{:name "additional-input"}]}
                       {:name "fork" :arguments []}
                       {:name "btw" :arguments [{:name "prompt" :required true}]}
                       {:name "resume" :arguments [{:name "chat-id"}]}
                       {:name "chats" :arguments [{:name "title-filter"}]}
                       {:name "delete-chat" :arguments [{:name "chat-id"}]}
                       {:name "export" :arguments [{:name "filepath"}]}
                       {:name "import" :arguments [{:name "filepath"}]}
                       {:name "remote" :arguments []}
                       {:name "config" :arguments []}
                       {:name "doctor" :arguments []}
                       {:name "debug-chat" :arguments [{:name "filepath"}]}
                       {:name "repo-map-show" :arguments []}
                       {:name "rules" :arguments []}
                       {:name "prompt-show" :arguments [{:name "optional-prompt"}]}
                       {:name "sync-system-prompt" :arguments []}
                       {:name "subagents" :arguments []}
                       {:name "plugins" :arguments []}
                       {:name "plugin-install"
                        :arguments [{:name "plugin" :description "Plugin name or plugin@marketplace" :required true}]}
                       {:name "plugin-uninstall"
                        :arguments [{:name "plugin" :description "Plugin name" :required true}]}
                       {:name "hooks" :arguments []}
                       {:name "eca-info" :arguments nil}]}
           resp))))

  (testing "We query specific commands"
    (let [resp (eca/request! (fixture/chat-query-commands-request
                              {:query "co"}))]
      (is (match?
           {:chatId nil
            :commands [{:name "login" :arguments [{:name "provider-id"}]}
                       {:name "skill-create"
                        :arguments [{:name "name" :description "The skill name" :required true}
                                    {:name "prompt" :description "What to consider as this skill content" :required true}]}
                       {:name "costs" :arguments []}
                       {:name "context" :arguments []}
                       {:name "compact" :arguments [{:name "additional-input"}]}
                       {:name "delete-chat" :arguments [{:name "chat-id"}]}
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
      (match-content chat-id "system" {:type "progress" :state "running" :text "Loading config"})
      (match-content chat-id "system" {:type "text" :text (m/pred #(and (string/includes? % "# Instructions (System prompt)")
                                                                        (string/includes? % "You are ECA")
                                                                        (not (string/includes? % "# Chat (User prompt)"))
                                                                        (not (string/includes? % "/prompt-show"))
                                                                        (string/includes? % "Tool schemas are sent separately")))})
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
  (testing "Mcp prompts fetched"
    ;; Prompts are listed after the server reports running, in a second
    ;; notification. Await it before querying commands.
    (is (match? {:type "mcp"
                 :name "mcpServerSample"
                 :prompts [{:name "my-prompt" :arguments [{:name "some-arg-1"}]}]}
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
        (match-content chat-id "system" {:type "progress" :state "running" :text "Loading config"})
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
        (match-content chat-id "system" {:type "progress" :state "running" :text "Loading config"})
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

(deftest btw-command
  (eca/start-process!)

  ;; Disable chat title generation for deterministic content ordering.
  (eca/request! (fixture/initialize-request
                 {:initializationOptions (merge fixture/default-init-options
                                                {:chat {:title false}})}))
  (eca/notify! (fixture/initialized-notification))

  (let [chat-id* (atom nil)
        btw-chat-id* (atom nil)]
    (testing "Setup: send an initial message to create chat history"
      (llm.mocks/set-case! :simple-text-0)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:model "openai/gpt-5.2"
                                 :message "Tell me a joke!"}))
            chat-id (reset! chat-id* (:chatId resp))]
        (match-content chat-id "user" {:type "text" :text "Tell me a joke!\n"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Loading config"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "text" :text "Knock"})
        (match-content chat-id "assistant" {:type "text" :text " knock!"})
        (match-content chat-id "system" {:type "usage"})
        (match-content chat-id "system" {:type "progress" :state "finished"})))

    (testing "/btw forks the chat and prompts the question there"
      (llm.mocks/set-case! :simple-text-1)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:chat-id @chat-id*
                                 :model "openai/gpt-5.2"
                                 :message "/btw Who's there?"}))
            chat-id @chat-id*
            opened (eca/client-awaits-server-notification :chat/opened)
            btw-chat-id (reset! btw-chat-id* (:chatId opened))]
        (is (match? {:chatId chat-id
                     :model "openai/gpt-5.2"
                     :status "prompting"}
                    resp))
        (is (match? {:chatId (m/pred string?)
                     :title "btw: Who's there?"}
                    opened))
        (is (not= chat-id btw-chat-id))

        ;; Origin chat shows the command and the fork notice only.
        (match-content chat-id "user" {:type "text" :text "/btw Who's there?\n"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Loading config"})
        (match-content chat-id "system" {:type "text" :text "Side question forked to: btw: Who's there?"})
        ;; Forked chat replays the copied history and gets its title.
        (match-content btw-chat-id "user" {:type "text" :text "\nTell me a joke!"})
        (match-content btw-chat-id "assistant" {:type "text" :text "\nKnock knock!"})
        (match-content btw-chat-id "system" {:type "metadata" :title "btw: Who's there?"})
        ;; Origin chat turn finishes while the fork answers the question.
        (match-content chat-id "system" {:type "progress" :state "finished"})
        (match-content btw-chat-id "user" {:type "text" :text "Who's there?\n"})
        (match-content btw-chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content btw-chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content btw-chat-id "assistant" {:type "text" :text "Foo"})
        (match-content btw-chat-id "system" {:type "usage"})
        (match-content btw-chat-id "system" {:type "progress" :state "finished"})

        ;; The forked chat request contains the copied history + the question.
        (is (match?
             {:input [{:role "user" :content [{:type "input_text" :text "Tell me a joke!"}]}
                      {:role "assistant" :content [{:type "output_text" :text "Knock knock!"}]}
                      {:role "user" :content [{:type "input_text" :text "Who's there?"}]}]}
             (llm.mocks/get-req-body :simple-text-1)))))

    (testing "the origin chat history stays clean after /btw"
      (llm.mocks/set-case! :simple-text-2)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:chat-id @chat-id*
                                 :model "openai/gpt-5.2"
                                 :message "What foo?"}))
            chat-id @chat-id*]
        (is (match? {:chatId chat-id :status "prompting"} resp))
        (match-content chat-id "user" {:type "text" :text "What foo?\n"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Loading config"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})
        (match-content chat-id "assistant" {:type "text" :text "Foo"})
        (match-content chat-id "assistant" {:type "text" :text " bar!"})
        (match-content chat-id "assistant" {:type "text" :text "\n\n"})
        (match-content chat-id "assistant" {:type "text" :text "Ha!"})
        (match-content chat-id "system" {:type "usage"})
        (match-content chat-id "system" {:type "progress" :state "finished"})
        ;; No trace of the /btw exchange in the origin chat request.
        (is (match?
             {:input [{:role "user" :content [{:type "input_text" :text "Tell me a joke!"}]}
                      {:role "assistant" :content [{:type "output_text" :text "Knock knock!"}]}
                      {:role "user" :content [{:type "input_text" :text "What foo?"}]}]}
             (llm.mocks/get-req-body :simple-text-2)))))))
