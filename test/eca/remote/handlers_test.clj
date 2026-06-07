(ns eca.remote.handlers-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.messenger :as messenger]
   [eca.remote.handlers :as handlers]
   [eca.remote.messenger :as remote.messenger]
   [eca.remote.sse :as sse]
   [eca.test-helper :as h]))

(h/reset-components-before-test)

(defn- components []
  (let [c (h/components)]
    (assoc c :config (config/all @(:db* c)))))

(deftest handle-health-test
  (testing "returns ok with version"
    (let [response (handlers/handle-health nil nil)
          body (json/parse-string (:body response) true)]
      (is (= 200 (:status response)))
      (is (= "ok" (:status body)))
      (is (string? (:version body))))))

(deftest handle-root-test
  (testing "redirects to web.eca.dev for public hosts"
    (let [response (handlers/handle-root (components) nil {:host "100.64.0.1:7888" :password "abc123"})]
      (is (= 302 (:status response)))
      (is (= "https://web.eca.dev?host=100.64.0.1:7888&pass=abc123"
             (get-in response [:headers "Location"])))))

  (testing "redirects to docs for private hosts"
    (swap! (h/db*) assoc :remote-private-host? true)
    (let [response (handlers/handle-root (components) nil {:host "192.168.1.1:7888" :password "abc123"})]
      (is (= 302 (:status response)))
      (is (= "https://eca.dev/config/remote"
             (get-in response [:headers "Location"]))))))

(deftest handle-list-chats-test
  (testing "returns empty list when no chats"
    (let [response (handlers/handle-list-chats (components) nil)
          body (json/parse-string (:body response) true)]
      (is (= 200 (:status response)))
      (is (= [] body))))

  (testing "returns chats excluding subagents"
    (swap! (h/db*) assoc :chats {"c1" {:id "c1" :title "Test" :status :idle :created-at 123}
                                   "c2" {:id "c2" :title "Sub" :status :running :subagent true}}
                                 :editor-open-chats #{"c1" "c2"})
    (let [response (handlers/handle-list-chats (components) nil)
          body (json/parse-string (:body response) true)]
      (is (= 1 (count body)))
      (is (= "c1" (:id (first body))))
      (is (= 0 (:pendingApprovalCount (first body))))))

  (testing "pendingApprovalCount reflects only :waiting-approval tool calls"
    (swap! (h/db*) assoc
           :chats {"c1" {:id "c1"
                         :title "Has pending"
                         :status :running
                         :tool-calls {"tc-1" {:status :waiting-approval}
                                      "tc-2" {:status :waiting-approval}
                                      "tc-3" {:status :executing}
                                      "tc-4" {:status :completed}}}
                   "c2" {:id "c2" :title "No pending" :status :idle}}
           :editor-open-chats #{"c1" "c2"})
    (let [response (handlers/handle-list-chats (components) nil)
          body (json/parse-string (:body response) true)
          by-id (into {} (map (juxt :id identity) body))]
      (is (= 2 (count body)))
      (is (= 2 (get-in by-id ["c1" :pendingApprovalCount])))
      (is (= 0 (get-in by-id ["c2" :pendingApprovalCount]))))))

(deftest handle-get-chat-test
  (testing "returns 404 for missing chat"
    (let [response (handlers/handle-get-chat (components) nil "nonexistent")
          body (json/parse-string (:body response) true)]
      (is (= 404 (:status response)))
      (is (= "chat_not_found" (get-in body [:error :code])))))

  (testing "returns chat data for existing chat"
    (swap! (h/db*) assoc-in [:chats "c1"] {:id "c1" :title "My Chat" :status :idle :messages []})
    (let [response (handlers/handle-get-chat (components) nil "c1")
          body (json/parse-string (:body response) true)]
      (is (= 200 (:status response)))
      (is (= "c1" (:id body)))
      (is (= "My Chat" (:title body)))))

  (testing "pendingToolCalls is an empty array when no tool calls are waiting"
    (swap! (h/db*) assoc-in [:chats "c1"] {:id "c1" :title "T" :status :idle})
    (let [response (handlers/handle-get-chat (components) nil "c1")
          body (json/parse-string (:body response) true)]
      (is (= 200 (:status response)))
      (is (= [] (:pendingToolCalls body)))))

  (testing "pendingToolCalls surfaces tool calls in :waiting-approval, excluding other statuses"
    (swap! (h/db*) assoc-in [:chats "c1"]
           {:id "c1"
            :title "T"
            :status :running
            :tool-calls {"tc-1" {:status :waiting-approval
                                 :name "shell_command"
                                 :server "eca"
                                 :origin :native
                                 :arguments {:command "ls"}
                                 :manual-approval true
                                 :summary "Run ls"
                                 :details {:type "shellCommand"}}
                         "tc-2" {:status :executing
                                 :name "read_file"
                                 :server "eca"
                                 :origin :native
                                 :arguments {:path "/tmp/x"}}
                         "tc-3" {:status :completed
                                 :name "write_file"}}})
    (let [response (handlers/handle-get-chat (components) nil "c1")
          body (json/parse-string (:body response) true)
          pending (:pendingToolCalls body)]
      (is (= 200 (:status response)))
      (is (= 1 (count pending)))
      (is (= {:id "tc-1"
              :name "shell_command"
              :server "eca"
              :origin "native"
              :arguments {:command "ls"}
              :manualApproval true
              :summary "Run ls"
              :details {:type "shellCommand"}}
             (first pending)))))

  (testing "approved/rejected tool calls drop out of pendingToolCalls"
    (swap! (h/db*) assoc-in [:chats "c1"]
           {:id "c1"
            :tool-calls {"tc-1" {:status :rejected :name "x"}
                         "tc-2" {:status :completed :name "y"}}})
    (let [response (handlers/handle-get-chat (components) nil "c1")
          body (json/parse-string (:body response) true)]
      (is (= [] (:pendingToolCalls body)))))

  (testing "executing ask_user tool call appears in pendingToolCalls with requestId"
    (swap! (h/db*) assoc-in [:chats "c1"]
           {:id "c1"
            :title "T"
            :status :running
            :tool-calls {"tc-ask" {:status :executing
                                   :name "ask_user"
                                   :server "eca"
                                   :origin :native
                                   :arguments {"question" "Proceed?" "allowFreeform" true}
                                   :manual-approval false
                                   :ask-question-request-id "req-123"
                                   :summary "Q: Proceed?"}
                         "tc-run" {:status :executing
                                   :name "shell_command"
                                   :server "eca"
                                   :origin :native
                                   :arguments {:command "ls"}}}})
    (let [response (handlers/handle-get-chat (components) nil "c1")
          body (json/parse-string (:body response) true)
          pending (:pendingToolCalls body)]
      (is (= 1 (count pending)) "only ask_user :executing should appear, not other :executing tool calls")
      (is (= "tc-ask" (:id (first pending)))
          "ask_user tool call should appear")
      (is (= "ask_user" (:name (first pending))))
      (is (= false (:manualApproval (first pending))))
      (is (= "req-123" (:requestId (first pending))))
      (is (= "Q: Proceed?" (:summary (first pending))))
      (is (= "Proceed?" (get-in (first pending) [:arguments :question]))
          "arguments are keywordized after JSON round-trip")))

  (testing "executing ask_user without requestId yet omits requestId from pendingToolCalls"
    (swap! (h/db*) assoc-in [:chats "c1"]
           {:id "c1"
            :title "T"
            :status :running
            :tool-calls {"tc-ask" {:status :executing
                                   :name "ask_user"
                                   :server "eca"
                                   :origin :native
                                   :arguments {"question" "Ready?"}
                                   :manual-approval false}}})
    (let [response (handlers/handle-get-chat (components) nil "c1")
          body (json/parse-string (:body response) true)
          pending (:pendingToolCalls body)]
      (is (= 1 (count pending)))
      (is (nil? (:requestId (first pending))))))

  (testing "returns per-chat model/variant/agent overrides when set"
    (swap! (h/db*) assoc-in [:chats "c1"]
           {:id "c1" :title "T" :status :idle
            :model "claude-custom" :variant "high" :agent "plan"})
    (let [response (handlers/handle-get-chat (components) nil "c1")
          body (json/parse-string (:body response) true)]
      (is (= 200 (:status response)))
      (is (= "claude-custom" (:model body)))
      (is (= "high" (:variant body)))
      (is (= "plan" (:agent body)))))

  (testing "falls back to resolved session-level defaults when chat has no override"
    (swap! (h/db*) assoc-in [:chats "c1"] {:id "c1" :title "T" :status :idle})
    (let [response (handlers/handle-get-chat (components) nil "c1")
          body (json/parse-string (:body response) true)
          session (json/parse-string (:body (handlers/handle-session (components) nil)) true)]
      (is (= 200 (:status response)))
      (is (= (:selectModel session) (:model body)))
      (is (= (:selectAgent session) (:agent body)))
      (is (= (:selectedVariant session) (:variant body)))))

  (testing "per-chat overrides take precedence over session-level defaults"
    (swap! (h/db*) assoc
           :last-config-notified {:chat {:select-model "session-model"
                                         :select-agent "session-agent"
                                         :select-variant "session-variant"}})
    (swap! (h/db*) assoc-in [:chats "c1"]
           {:id "c1" :title "T" :status :idle :model "chat-model" :agent "chat-agent"})
    (let [response (handlers/handle-get-chat (components) nil "c1")
          body (json/parse-string (:body response) true)]
      (is (= "chat-model" (:model body)))
      (is (= "chat-agent" (:agent body)))
      ;; variant has no per-chat override, so it falls back to the session value
      (is (= "session-variant" (:variant body))))))

(deftest handle-stop-test
  (testing "returns 404 for missing chat"
    (let [response (handlers/handle-stop (components) nil "nonexistent")]
      (is (= 404 (:status response)))))

  (testing "returns 409 for non-running chat"
    (swap! (h/db*) assoc-in [:chats "c1"] {:id "c1" :status :idle})
    (let [response (handlers/handle-stop (components) nil "c1")]
      (is (= 409 (:status response))))))

(deftest handle-prompt-test
  (testing "returns 400 for missing message"
    (let [request {:body (java.io.ByteArrayInputStream. (.getBytes "{}" "UTF-8"))}
          response (handlers/handle-prompt (components) request "c1")]
      (is (= 400 (:status response))))))

(deftest handle-session-test
  (testing "returns session info"
    (let [response (handlers/handle-session (components) nil)
          body (json/parse-string (:body response) true)]
      (is (= 200 (:status response)))
      (is (string? (:version body)))
      (is (= "1.0" (:protocolVersion body)))))

  (testing "chat entries include pendingApprovalCount"
    (swap! (h/db*) assoc
           :chats {"c1" {:id "c1" :title "Has pending" :status :running
                         :tool-calls {"tc-1" {:status :waiting-approval}
                                      "tc-2" {:status :executing}}}}
           :editor-open-chats #{"c1"})
    (let [response (handlers/handle-session (components) nil)
          body (json/parse-string (:body response) true)
          chat (first (:chats body))]
      (is (= "c1" (:id chat)))
      (is (= 1 (:pendingApprovalCount chat))))))

(deftest handle-answer-question-test
  (let [inner (h/messenger)
        sse-connections* (sse/create-connections)
        broadcast-messenger (remote.messenger/make-broadcast-messenger inner sse-connections*)
        os (java.io.ByteArrayOutputStream.)
        _client (sse/add-client! sse-connections* os)
        request-with-body (fn [body]
                            {:body (java.io.ByteArrayInputStream.
                                    (.getBytes ^String (json/generate-string body) "UTF-8"))})]

    (testing "returns 400 when requestId is missing"
      (let [response (handlers/handle-answer-question
                      {:messenger broadcast-messenger}
                      (request-with-body {:answer "x"}))
            body (json/parse-string (:body response) true)]
        (is (= 400 (:status response)))
        (is (= "invalid_request" (get-in body [:error :code])))))

    (testing "returns 404 when requestId is unknown"
      (let [response (handlers/handle-answer-question
                      {:messenger broadcast-messenger}
                      (request-with-body {:requestId "nonexistent" :answer "x"}))
            body (json/parse-string (:body response) true)]
        (is (= 404 (:status response)))
        (is (= "question_not_found" (get-in body [:error :code])))))

    (testing "returns 204 and resolves the pending promise on a successful answer"
      (let [p (messenger/ask-question broadcast-messenger {:chat-id "c1" :question "Q?"})]
        (Thread/sleep 100)
        (let [pending @(:pending-questions* broadcast-messenger)
              [request-id _] (first pending)
              response (handlers/handle-answer-question
                        {:messenger broadcast-messenger}
                        (request-with-body {:requestId request-id :answer "ok" :cancelled false}))]
          (is (= 204 (:status response)))
          (is (realized? p))
          (is (= {:answer "ok" :cancelled false} @p)))))

    (sse/close-all! sse-connections*)))
