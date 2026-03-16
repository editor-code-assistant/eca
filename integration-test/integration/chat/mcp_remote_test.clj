(ns integration.chat.mcp-remote-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [integration.eca :as eca]
   [integration.fixture :as fixture]
   [integration.helper :refer [match-content]]
   [llm-mock.mocks :as llm.mocks]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]
   [mcp-mock.server :as mcp-mock]))

(eca/clean-after-test)

(def ^:private mcp-server-config
  {:mcpServers {"test-mcp" {:url (str "http://localhost:" mcp-mock/port "/mcp")}}})

(defn ^:private init-with-mcp-remote! []
  (eca/start-process!)
  (mcp-mock/reset-requests!)
  (eca/request! (fixture/initialize-request
                 {:initializationOptions
                  (merge fixture/default-init-options mcp-server-config)}))
  (eca/notify! (fixture/initialized-notification)))

(deftest mcp-remote-server-connects
  (init-with-mcp-remote!)

  (testing "Native tools loaded"
    (is (match? {:type "native"}
                (eca/client-awaits-server-notification :tool/serverUpdated))))

  (testing "MCP remote server starting"
    (is (match? {:type "mcp"
                 :name "testMcp"}
                (eca/client-awaits-server-notification :tool/serverUpdated))))

  (testing "MCP remote server running with tools"
    (is (match? {:type "mcp"
                 :name "testMcp"
                 :tools (m/embeds [{:name "echo"} {:name "add"}])}
                (eca/client-awaits-server-notification :tool/serverUpdated))))

  (testing "MCP mock received initialize request"
    (let [init-reqs (mcp-mock/get-requests-by-method "initialize")]
      (is (= 1 (count init-reqs)))
      (is (match? {:method "initialize"
                   :params {:capabilities map?
                            :clientInfo map?}}
                  (first init-reqs)))))

  (testing "MCP mock received tools/list request"
    (let [tools-reqs (mcp-mock/get-requests-by-method "tools/list")]
      (is (= 1 (count tools-reqs))))))

(deftest mcp-remote-tool-call-in-chat
  (init-with-mcp-remote!)

  ;; Wait for MCP server to be ready
  (eca/client-awaits-server-notification :tool/serverUpdated) ;; native
  (eca/client-awaits-server-notification :tool/serverUpdated) ;; mcp starting
  (eca/client-awaits-server-notification :tool/serverUpdated) ;; mcp running

  (testing "LLM invokes an MCP tool and ECA processes it"
    (mcp-mock/reset-requests!)
    (llm.mocks/set-case! :mcp-tool-call-0)

    (let [resp (eca/request! (fixture/chat-prompt-request
                              {:model "anthropic/claude-sonnet-4-6"
                               :message "Call the echo tool"}))
          chat-id (:chatId resp)]

      (is (match? {:chatId string?
                   :model "anthropic/claude-sonnet-4-6"
                   :status "prompting"}
                  resp))

      ;; User message
      (match-content chat-id "user" {:type "text" :text "Call the echo tool\n"})

      ;; Title notification
      (match-content chat-id "system" {:type "metadata" :title "Some Cool Title"})

      ;; Progress: waiting model
      (match-content chat-id "system" {:type "progress" :state "running" :text "Waiting model"})
      (match-content chat-id "system" {:type "progress" :state "running" :text "Generating"})

      ;; Assistant text before tool use
      (match-content chat-id "assistant" {:type "text" :text "I will call the echo tool"})

      ;; Tool call prepare/run
      (match-content chat-id "assistant" {:type "toolCallPrepare"
                                          :origin "mcp"
                                          :name "echo"
                                          :id "mcp-tool-1"})
      (match-content chat-id "assistant" {:type "toolCallPrepare"
                                          :origin "mcp"
                                          :name "echo"
                                          :id "mcp-tool-1"})

      ;; Usage from first LLM turn
      (match-content chat-id "system" {:type "usage"})

      (match-content chat-id "assistant" {:type "toolCallRun"
                                          :origin "mcp"
                                          :name "echo"
                                          :id "mcp-tool-1"
                                          :arguments {:message "hello from mcp"}
                                          :manualApproval false})
      (match-content chat-id "assistant" {:type "toolCallRunning"
                                          :origin "mcp"
                                          :name "echo"
                                          :id "mcp-tool-1"
                                          :arguments {:message "hello from mcp"}})
      (match-content chat-id "system" {:type "progress" :state "running" :text "Calling tool"})

      ;; Tool called result — echo returns the same message
      (match-content chat-id "assistant" {:type "toolCalled"
                                          :origin "mcp"
                                          :name "echo"
                                          :id "mcp-tool-1"
                                          :arguments {:message "hello from mcp"}
                                          :error nil
                                          :outputs [{:type "text" :text "hello from mcp"}]})

      ;; Second LLM turn: final response after tool result
      (match-content chat-id "assistant" {:type "text" :text "The echo tool returned: hello from mcp"})
      (match-content chat-id "system" {:type "usage"})
      (match-content chat-id "system" {:type "progress" :state "finished"})

      (testing "MCP mock received tools/call request with correct arguments"
        (let [call-reqs (mcp-mock/get-requests-by-method "tools/call")]
          (is (= 1 (count call-reqs)))
          (is (match? {:method "tools/call"
                       :params {:name "echo"
                                :arguments {:message "hello from mcp"}}}
                      (first call-reqs)))))

      (testing "LLM received tool result in second request"
        (let [req-body (llm.mocks/get-req-body :mcp-tool-call-0)]
          (is (match?
               {:messages (m/embeds
                           [{:role "user"
                             :content [{:type "tool_result"
                                        :tool_use_id "mcp-tool-1"
                                        :content "hello from mcp\n"}]}])
                :tools (m/embeds [{:name "testMcp__echo"}
                                  {:name "testMcp__add"}])}
               req-body)))))))

(deftest mcp-remote-instructions-in-prompt
  (init-with-mcp-remote!)

  ;; Wait for MCP server to be ready
  (eca/client-awaits-server-notification :tool/serverUpdated) ;; native
  (eca/client-awaits-server-notification :tool/serverUpdated) ;; mcp starting
  (eca/client-awaits-server-notification :tool/serverUpdated) ;; mcp running

  (testing "MCP server instructions appear in the system prompt sent to LLM"
    (llm.mocks/set-case! :simple-text-0)
    (let [resp (eca/request! (fixture/chat-prompt-request
                              {:model "anthropic/claude-sonnet-4-6"
                               :message "hello"}))
          chat-id (:chatId resp)]

      ;; Consume notifications so the chat completes
      (match-content chat-id "user" {:type "text"})
      (match-content chat-id "system" {:type "metadata"})
      (match-content chat-id "system" {:type "progress" :state "running"})
      (match-content chat-id "system" {:type "progress" :state "running"})
      (match-content chat-id "assistant" {:type "text"})
      (match-content chat-id "assistant" {:type "text"})
      (match-content chat-id "system" {:type "usage"})
      (match-content chat-id "system" {:type "progress" :state "finished"})

      (let [req-body (llm.mocks/get-req-body :simple-text-0)
            system-text (->> (:system req-body)
                             (map :text)
                             (string/join "\n"))]
        (is (string/includes? system-text "mcp-server-instruction name=\"testMcp\"")
            "System prompt should contain MCP server instruction block")
        (is (string/includes? system-text "This is a test MCP server for integration testing.")
            "System prompt should contain the MCP server's instructions text")))))
