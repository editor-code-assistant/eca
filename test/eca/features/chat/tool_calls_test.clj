(ns eca.features.chat.tool-calls-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [are deftest is testing]]
   [eca.features.chat.lifecycle :as lifecycle]
   [eca.features.chat.tool-calls :as tc]
   [eca.features.hooks :as f.hooks]
   [eca.features.tools :as f.tools]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(deftest decide-tool-call-action-test
  (testing "config-based approval - allow"
    (h/reset-components!)
    (let [tool-call {:id "call-1"
                     :full-name "eca__test_tool"
                     :arguments {:foo "bar"}}
          all-tools [{:name "test_tool"
                      :full-name "eca__test_tool"
                      :origin :native
                      :server {:name "eca"}}]
          db (h/db)
          config (h/config)
          agent-name :default
          chat-id "test-chat"]
      (with-redefs [f.tools/approval (constantly :allow)
                    f.hooks/trigger-if-matches! (fn [_ _ _ _ _] nil)]
        (let [plan (#'tc/decide-tool-call-action tool-call all-tools db config agent-name chat-id)]
          (is (match? {:decision :allow
                       :arguments {:foo "bar"}
                       :approval-override nil
                       :tool-call-rejected-by-hook? false
                       :arguments-modified? false
                       :reason {:code :user-config-allow
                                :text string?}}
                      plan))))))

  (testing "config-based approval - ask"
    (h/reset-components!)
    (let [tool-call {:id "call-1"
                     :full-name "eca__test_tool"
                     :arguments {:foo "bar"}}
          all-tools [{:name "test_tool"
                      :full-name "eca__test_tool"
                      :origin :native
                      :server {:name "eca"}}]
          db (h/db)
          config (h/config)
          agent-name :default
          chat-id "test-chat"]
      (with-redefs [f.tools/approval (constantly :ask)
                    f.hooks/trigger-if-matches! (fn [_ _ _ _ _] nil)]
        (let [plan (#'tc/decide-tool-call-action tool-call all-tools db config agent-name chat-id)]
          (is (match? {:decision :ask
                       :arguments {:foo "bar"}
                       :approval-override nil
                       :tool-call-rejected-by-hook? false
                       :arguments-modified? false}
                      plan))))))

  (testing "config-based approval - deny"
    (h/reset-components!)
    (let [tool-call {:id "call-1"
                     :full-name "eca__test_tool"
                     :arguments {:foo "bar"}}
          all-tools [{:name "test_tool"
                      :full-name "eca__test_tool"
                      :origin :native
                      :server {:name "eca"}}]
          db (h/db)
          config (h/config)
          agent-name :default
          chat-id "test-chat"]
      (with-redefs [f.tools/approval (constantly :deny)
                    f.hooks/trigger-if-matches! (fn [_ _ _ _ _] nil)]
        (let [plan (#'tc/decide-tool-call-action tool-call all-tools db config agent-name chat-id)]
          (is (match? {:decision :deny
                       :arguments {:foo "bar"}
                       :approval-override nil
                       :tool-call-rejected-by-hook? false
                       :arguments-modified? false
                       :reason {:code :user-config-deny
                                :text string?}}
                      plan))))))

  (testing "hook approval override - allow to ask"
    (h/reset-components!)
    (let [tool-call {:id "call-1"
                     :full-name "eca__test_tool"
                     :arguments {:foo "bar"}}
          all-tools [{:name "test_tool"
                      :full-name "eca__test_tool"
                      :origin :native
                      :server {:name "eca"}}]
          db (h/db)
          config (h/config! {:hooks {"hook1" {:type    "preToolCall"
                                              :actions [{:type "shell" :command "echo 'approval override'"}]}}})
          agent-name :default
          chat-id "test-chat"
          hook-call-count (atom 0)]
      (with-redefs [f.tools/approval (constantly :allow)
                    f.hooks/trigger-if-matches! (fn [event _ callbacks _ _]
                                                  (when (= event :preToolCall)
                                                    (swap! hook-call-count inc)
                                                    (when-let [on-after (:on-after-action callbacks)]
                                                      (on-after {:parsed {"approval" "ask"}
                                                                 :exit 0}))))]
        (let [plan (#'tc/decide-tool-call-action tool-call all-tools db config agent-name chat-id)]
          (is (= 1 @hook-call-count))
          (is (match? {:decision :ask
                       :arguments {:foo "bar"}
                       :approval-override "ask"
                       :tool-call-rejected-by-hook? false
                       :arguments-modified? false}
                      plan))))))

  (testing "hook approval precedence (deny > ask > allow; hook-allow never overrides config ask/deny)"
    (h/reset-components!)
    (let [tool-call {:id "call-1"
                     :full-name "eca__test_tool"
                     :arguments {:foo "bar"}}
          all-tools [{:name "test_tool"
                      :full-name "eca__test_tool"
                      :origin :native
                      :server {:name "eca"}}]
          config (h/config! {:hooks {"hook1" {:type    "preToolCall"
                                              :actions [{:type "shell" :command "echo"}]}}})
          run-plan (fn [config-approval hook-approvals]
                     (with-redefs [f.tools/approval (constantly config-approval)
                                   f.hooks/trigger-if-matches! (fn [event _ callbacks _ _]
                                                                 (when (= event :preToolCall)
                                                                   (when-let [on-after (:on-after-action callbacks)]
                                                                     (doseq [approval hook-approvals]
                                                                       (on-after {:parsed {"approval" approval}
                                                                                  :exit 0
                                                                                  :raw-error nil})))))]
                       (#'tc/decide-tool-call-action tool-call all-tools (h/db) config :default "test-chat")))]
      ;; deny wins over ask and allow
      (is (match? {:decision :deny
                   :approval-override "deny"
                   :tool-call-rejected-by-hook? true
                   :reason {:code :hook-rejected :text "Tool call rejected by hook"}}
                  (run-plan :allow ["allow" "deny" "ask"])))
      ;; ask wins over allow
      (is (match? {:decision :ask
                   :approval-override "ask"
                   :tool-call-rejected-by-hook? false}
                  (run-plan :allow ["allow" "ask"])))
      ;; a hook "allow" never loosens a stricter config decision (ask/deny)
      (is (match? {:decision :ask
                   :approval-override "allow"
                   :tool-call-rejected-by-hook? false}
                  (run-plan :ask ["allow"])))
      (is (match? {:decision :deny
                   :approval-override "allow"
                   :tool-call-rejected-by-hook? false
                   :reason {:code :user-config-deny}}
                  (run-plan :deny ["allow"])))))

  (testing "hook rejection via exit code 2"
    (h/reset-components!)
    (let [tool-call {:id "call-1"
                     :full-name "eca__test_tool"
                     :arguments {:foo "bar"}}
          all-tools [{:name "test_tool"
                      :full-name "eca__test_tool"
                      :origin :native
                      :server {:name "eca"}}]
          db (h/db)
          config (h/config! {:hooks {"hook1" {:type    "preToolCall"
                                              :actions [{:type "shell" :command "exit 2"}]}}})
          agent-name :default
          chat-id "test-chat"]
      (with-redefs [f.tools/approval (constantly :allow)
                    f.hooks/trigger-if-matches! (fn [event _ callbacks _ _]
                                                  (when (= event :preToolCall)
                                                    (when-let [on-after (:on-after-action callbacks)]
                                                      (on-after {:parsed {"additionalContext" "Hook rejected"}
                                                                 :exit 2
                                                                 :raw-error "Command failed"}))))]
        (let [plan (#'tc/decide-tool-call-action tool-call all-tools db config agent-name chat-id)]
          (is (match? {:decision :deny
                       :arguments {:foo "bar"}
                       :approval-override nil
                       :tool-call-rejected-by-hook? true
                       :arguments-modified? false
                       :reason {:code :hook-rejected
                                :text "Command failed"}
                       :stop-turn? false}
                      plan))
          (is (not (contains? plan :stop-reason)))))))

  (testing "hook approval deny does not include stop-reason"
    (h/reset-components!)
    (let [tool-call {:id "call-1"
                     :full-name "eca__test_tool"
                     :arguments {:foo "bar"}}
          all-tools [{:name "test_tool"
                      :full-name "eca__test_tool"
                      :origin :native
                      :server {:name "eca"}}]
          db (h/db)
          config (h/config! {:hooks {"hook1" {:type    "preToolCall"
                                              :actions [{:type "shell" :command "echo"}]}}})
          agent-name :default
          chat-id "test-chat"]
      (with-redefs [f.tools/approval (constantly :allow)
                    f.hooks/trigger-if-matches! (fn [event _ callbacks _ _]
                                                  (when (= event :preToolCall)
                                                    (when-let [on-after (:on-after-action callbacks)]
                                                      (on-after {:parsed {"approval" "deny"
                                                                          "stopReason" "user-only"}
                                                                 :exit 0
                                                                 :raw-error nil}))))]
        (let [plan (#'tc/decide-tool-call-action tool-call all-tools db config agent-name chat-id)]
          (is (match? {:decision :deny
                       :tool-call-rejected-by-hook? true
                       :stop-turn? false
                       :reason {:code :hook-rejected
                                :text "Tool call rejected by hook"}}
                      plan))
          (is (not (contains? plan :stop-reason)))))))

  (testing "trust mode converts ask to allow"
    (h/reset-components!)
    (let [tool-call {:id "call-1"
                     :full-name "eca__test_tool"
                     :arguments {:foo "bar"}}
          all-tools [{:name "test_tool"
                      :full-name "eca__test_tool"
                      :origin :native
                      :server {:name "eca"}}]
          db (h/db)
          config (h/config)
          agent-name :default
          chat-id "test-chat"]
      (with-redefs [f.tools/approval (fn [& args]
                                       (let [opts (last args)]
                                         (if (:trust opts) :trust/allow :ask)))
                    f.hooks/trigger-if-matches! (fn [_ _ _ _ _] nil)]
        (let [plan (#'tc/decide-tool-call-action tool-call all-tools db config agent-name chat-id
                                                 {:trust true})]
          (is (match? {:decision :allow
                       :arguments {:foo "bar"}
                       :tool-call-rejected-by-hook? false
                       :arguments-modified? false
                       :reason {:code :trust-allow
                                :text string?}}
                      plan))))))

  (testing "trust mode does not override deny"
    (h/reset-components!)
    (let [tool-call {:id "call-1"
                     :full-name "eca__test_tool"
                     :arguments {:foo "bar"}}
          all-tools [{:name "test_tool"
                      :full-name "eca__test_tool"
                      :origin :native
                      :server {:name "eca"}}]
          db (h/db)
          config (h/config)
          agent-name :default
          chat-id "test-chat"]
      (with-redefs [f.tools/approval (constantly :deny)
                    f.hooks/trigger-if-matches! (fn [_ _ _ _ _] nil)]
        (let [plan (#'tc/decide-tool-call-action tool-call all-tools db config agent-name chat-id
                                                 {:trust true})]
          (is (match? {:decision :deny
                       :reason {:code :user-config-deny
                                :text string?}}
                      plan))))))

  (testing "trust mode sends allow approval to hooks"
    (h/reset-components!)
    (let [tool-call {:id "call-1"
                     :full-name "eca__test_tool"
                     :arguments {:foo "bar"}}
          all-tools [{:name "test_tool"
                      :full-name "eca__test_tool"
                      :origin :native
                      :server {:name "eca"}}]
          db (h/db)
          config (h/config! {:hooks {"hook1" {:type    "preToolCall"
                                              :actions [{:type "shell" :command "echo ok"}]}}})
          agent-name :default
          chat-id "test-chat"
          hook-data* (atom nil)]
      (with-redefs [f.tools/approval (fn [& args]
                                       (let [opts (last args)]
                                         (if (:trust opts) :trust/allow :ask)))
                    f.hooks/trigger-if-matches! (fn [event data _ _ _]
                                                  (when (= event :preToolCall)
                                                    (reset! hook-data* data)))]
        (#'tc/decide-tool-call-action tool-call all-tools db config agent-name chat-id
                                      {:trust true})
        (is (= :allow (:approval @hook-data*))))))

  (testing "trust mode does not override hook rejection"
    (h/reset-components!)
    (let [tool-call {:id "call-1"
                     :full-name "eca__test_tool"
                     :arguments {:foo "bar"}}
          all-tools [{:name "test_tool"
                      :full-name "eca__test_tool"
                      :origin :native
                      :server {:name "eca"}}]
          db (h/db)
          config (h/config! {:hooks {"hook1" {:type    "preToolCall"
                                              :actions [{:type "shell" :command "exit 2"}]}}})
          agent-name :default
          chat-id "test-chat"]
      (with-redefs [f.tools/approval (constantly :allow)
                    f.hooks/trigger-if-matches! (fn [event _ callbacks _ _]
                                                  (when (= event :preToolCall)
                                                    (when-let [on-after (:on-after-action callbacks)]
                                                      (on-after {:parsed {"additionalContext" "Hook rejected"}
                                                                 :exit 2
                                                                 :raw-error "Command failed"}))))]
        (let [plan (#'tc/decide-tool-call-action tool-call all-tools db config agent-name chat-id
                                                 {:trust true})]
          (is (match? {:decision :deny
                       :tool-call-rejected-by-hook? true
                       :reason {:code :hook-rejected
                                :text "Command failed"}}
                      plan))
          (is (not (contains? plan :stop-reason)))))))

  (testing "hook modifies arguments"
    (h/reset-components!)
    (let [tool-call {:id "call-1"
                     :full-name "eca__test_tool"
                     :arguments {"foo" "bar"}}
          all-tools [{:name "test_tool"
                      :full-name "eca__test_tool"
                      :origin :native
                      :server {:name "eca"}}]
          db (h/db)
          config (h/config! {:hooks {"hook1" {:type    "preToolCall"
                                              :actions [{:type "shell" :command "echo 'modify args'"}]}}})
          agent-name :default
          chat-id "test-chat"]
      (with-redefs [f.tools/approval (constantly :allow)
                    f.hooks/trigger-if-matches! (fn [event _ callbacks _ _]
                                                  (when (= event :preToolCall)
                                                    (when-let [on-after (:on-after-action callbacks)]
                                                      (on-after {:parsed {"updatedInput" {"baz" "qux"}}
                                                                 :exit 0}))))]
        (let [plan (#'tc/decide-tool-call-action tool-call all-tools db config agent-name chat-id)]
          (is (match? {:decision :allow
                       :arguments {"foo" "bar" "baz" "qux"}
                       :approval-override nil
                       :tool-call-rejected-by-hook? false
                       :arguments-modified? true}
                      plan))))))

  (testing "preToolCall notifies UI with tool-call-id for correlation"
    (h/reset-components!)
    (let [tool-call {:id "call-1"
                     :full-name "eca__test_tool"
                     :arguments {"foo" "bar"}}
          all-tools [{:name "test_tool"
                      :full-name "eca__test_tool"
                      :origin :native
                      :server {:name "eca"}}]
          db (h/db)
          config (h/config! {:hooks {"hook1" {:type    "preToolCall"
                                              :actions [{:type "shell" :command "echo ok"}]}}})
          agent-name :default
          chat-id "test-chat"
          notified* (atom nil)]
      (with-redefs [f.tools/approval (constantly :allow)
                    f.hooks/trigger-if-matches! (fn [event _ callbacks _ _]
                                                  (when (= event :preToolCall)
                                                    (when-let [on-after (:on-after-action callbacks)]
                                                      (on-after {:parsed {} :exit 0}))))]
        (#'tc/decide-tool-call-action tool-call all-tools db config agent-name chat-id
                                      {:on-after-hook-action (fn [result] (reset! notified* result))})
        ;; tool-call-id correlates the hook with its tool widget; clients can
        ;; use it to associate the hook block with the tool call.
        (is (match? {:tool-call-id "call-1"}
                    @notified*)))))

  (testing "ask_user with allowed config sends :ask to preToolCall hook"
    (h/reset-components!)
    (let [tool-call {:id "call-1"
                     :full-name "eca__ask_user"
                     :arguments {"question" "Why?"}}
          all-tools [{:name "ask_user"
                      :full-name "eca__ask_user"
                      :origin :native
                      :server {:name "eca"}}]
          db (h/db)
          config (h/config)
          agent-name :default
          chat-id "test-chat"
          hook-data* (atom nil)]
      (with-redefs [f.tools/approval (constantly :allow)
                    f.hooks/trigger-if-matches! (fn [event data _ _ _]
                                                  (when (= event :preToolCall)
                                                    (reset! hook-data* data)))]
        (let [plan (#'tc/decide-tool-call-action tool-call all-tools db config agent-name chat-id)]
          (is (= :ask (:approval @hook-data*))
              "preToolCall hook receives :ask so notification hooks fire while waiting on user")
          (is (match? {:decision :allow
                       :tool-call-rejected-by-hook? false}
                      plan)
              "tool actually executes (:allow) so the question still reaches the user")))))

  (testing "ask_user with denied config keeps :deny in preToolCall hook"
    (h/reset-components!)
    (let [tool-call {:id "call-1"
                     :full-name "eca__ask_user"
                     :arguments {"question" "Why?"}}
          all-tools [{:name "ask_user"
                      :full-name "eca__ask_user"
                      :origin :native
                      :server {:name "eca"}}]
          db (h/db)
          config (h/config)
          agent-name :default
          chat-id "test-chat"
          hook-data* (atom nil)]
      (with-redefs [f.tools/approval (constantly :deny)
                    f.hooks/trigger-if-matches! (fn [event data _ _ _]
                                                  (when (= event :preToolCall)
                                                    (reset! hook-data* data)))]
        (let [plan (#'tc/decide-tool-call-action tool-call all-tools db config agent-name chat-id)]
          (is (= :deny (:approval @hook-data*))
              "explicit :deny is preserved, not overridden to :ask")
          (is (= :deny (:decision plan)))))))

  (testing "non ask_user tool keeps :allow in preToolCall hook (regression)"
    (h/reset-components!)
    (let [tool-call {:id "call-1"
                     :full-name "eca__test_tool"
                     :arguments {:foo "bar"}}
          all-tools [{:name "test_tool"
                      :full-name "eca__test_tool"
                      :origin :native
                      :server {:name "eca"}}]
          db (h/db)
          config (h/config)
          agent-name :default
          chat-id "test-chat"
          hook-data* (atom nil)]
      (with-redefs [f.tools/approval (constantly :allow)
                    f.hooks/trigger-if-matches! (fn [event data _ _ _]
                                                  (when (= event :preToolCall)
                                                    (reset! hook-data* data)))]
        (#'tc/decide-tool-call-action tool-call all-tools db config agent-name chat-id)
        (is (= :allow (:approval @hook-data*))
            "ask_user override does not leak to other tools")))))

(deftest on-tools-called!-returns-provider-auth-test
  (testing "returns refreshed provider auth in result after token is renewed during tool execution"
    ;; Regression test for: auth captured in LLM provider closure at prompt start.
    ;; When a token expires mid-stream, maybe-renew-auth-token updates db*,
    ;; and on-tools-called! must return the refreshed auth so provider-specific
    ;; continuation metadata (not just the api key) can be reused.
    (h/reset-components!)
    (let [chat-id   "test-chat"
          provider  "github-copilot"
          renewed-provider-auth {:api-key "fresh-token-xyz"
                                 :expires-at 9999999999}
          db*       (h/db*)
          _         (swap! db* #(-> %
                                    (assoc-in [:auth provider :api-key] "stale-token-abc")
                                    (assoc-in [:chats chat-id :status] :running)
                                    (assoc-in [:chats chat-id :messages] [])
                                    (assoc-in [:chats chat-id :tool-calls "call-1" :status] :preparing)))
          ;; Pre-set tool call to :preparing state, as it would be after on-prepare-tool-call
          ;; fires during the streaming phase before on-tools-called! is invoked.
          chat-ctx  {:db*       db*
                     :config    (h/config)
                     :chat-id   chat-id
                     :provider  provider
                     :agent     :default
                     :messenger (h/messenger)
                     :metrics   (h/metrics)}
          received-msgs* (atom "")
          add-to-history! (fn [msg]
                            (swap! db* update-in [:chats chat-id :messages] (fnil conj []) msg))
          tool-calls [{:id           "call-1"
                       :full-name    "eca__test_tool"
                       :arguments    {}
                       :arguments-text "{}"}]
          all-tools  [{:name      "test_tool"
                       :full-name "eca__test_tool"
                       :origin    :eca
                       :server    {:name "eca"}}]
          expected-provider-auth (merge {:api-key "stale-token-abc"} renewed-provider-auth)]
      (with-redefs [f.tools/all-tools                       (constantly all-tools)
                    f.tools/approval                        (constantly :allow)
                    f.hooks/trigger-if-matches!             (fn [_ _ _ _ _] nil)
                    f.tools/call-tool!                      (fn [& _] {:contents [{:text "result" :type :text}]})
                    f.tools/tool-call-details-before-invocation (constantly nil)
                    f.tools/tool-call-details-after-invocation  (constantly nil)
                    f.tools/tool-call-summary               (constantly "Test tool")
                    lifecycle/maybe-renew-auth-token
                    (fn [ctx]
                      (swap! (:db* ctx) update-in [:auth provider] merge renewed-provider-auth))]
        (let [result ((tc/on-tools-called! chat-ctx received-msgs* add-to-history! []) tool-calls)]
          (is (= (:api-key expected-provider-auth) (:fresh-api-key result))
              "fresh-api-key must be returned so the provider can use it in the recursive streaming call")
          (is (= expected-provider-auth
                 (:provider-auth result))
              "provider-auth must be returned so providers can reuse refreshed auth metadata"))))))

(deftest on-tools-called!-rejection-returns-fresh-auth-test
  (testing "rejected subagent path also propagates refreshed auth"
    ;; Previously rejection branches skipped maybe-renew-auth-token and
    ;; returned without :fresh-api-key, breaking long-running subagents.
    (h/reset-components!)
    (let [chat-id   "test-chat"
          provider  "github-copilot"
          renewed-provider-auth {:api-key "fresh-token-after-rejection"
                                 :expires-at 9999999999}
          db*       (h/db*)
          _         (swap! db* #(-> %
                                    (assoc-in [:auth provider :api-key] "stale-token")
                                    (assoc-in [:chats chat-id :subagent] {:max-steps nil})
                                    (assoc-in [:chats chat-id :status] :running)
                                    (assoc-in [:chats chat-id :messages] [])
                                    (assoc-in [:chats chat-id :tool-calls "call-1" :status] :preparing)))
          chat-ctx  {:db*       db*
                     :config    (h/config)
                     :chat-id   chat-id
                     :provider  provider
                     :agent     :default
                     :messenger (h/messenger)
                     :metrics   (h/metrics)}
          received-msgs* (atom "")
          add-to-history! (fn [msg]
                            (swap! db* update-in [:chats chat-id :messages] (fnil conj []) msg))
          tool-calls [{:id           "call-1"
                       :full-name    "eca__test_tool"
                       :arguments    {}
                       :arguments-text "{}"}]
          all-tools  [{:name      "test_tool"
                       :full-name "eca__test_tool"
                       :origin    :eca
                       :server    {:name "eca"}}]]
      (with-redefs [f.tools/all-tools                           (constantly all-tools)
                    f.tools/approval                            (constantly :deny)
                    f.hooks/trigger-if-matches!                 (fn [_ _ _ _ _] nil)
                    f.tools/call-tool!                          (fn [& _] {:contents [{:text "ignored" :type :text}]})
                    f.tools/tool-call-details-before-invocation (constantly nil)
                    f.tools/tool-call-details-after-invocation  (constantly nil)
                    f.tools/tool-call-summary                   (constantly "Test tool")
                    lifecycle/maybe-renew-auth-token
                    (fn [ctx]
                      (swap! (:db* ctx) update-in [:auth provider] merge renewed-provider-auth))]
        (let [result ((tc/on-tools-called! chat-ctx received-msgs* add-to-history! []) tool-calls)]
          (is (some? result)
              "subagent rejection path must continue the loop (non-nil) instead of aborting")
          (is (= (:api-key renewed-provider-auth) (:fresh-api-key result))
              "rejection path must also return fresh api-key so the next subagent request uses the renewed token")
          (is (= (:api-key renewed-provider-auth) (:api-key (:provider-auth result)))
              "rejection path must also return refreshed provider-auth"))))))

(deftest on-tools-called!-pretoolcall-continue-false-halts-batch-test
  (testing "a preToolCall continue:false on the first tool stops the batch: the second tool is never processed"
    (h/reset-components!)
    (let [chat-id   "test-chat"
          db*       (h/db*)
          _         (swap! db* #(-> %
                                    (assoc-in [:chats chat-id :status] :running)
                                    (assoc-in [:chats chat-id :messages] [])
                                    (assoc-in [:chats chat-id :tool-calls "call-1" :status] :preparing)
                                    (assoc-in [:chats chat-id :tool-calls "call-2" :status] :preparing)))
          chat-ctx  {:db*       db*
                     :config    (h/config)
                     :chat-id   chat-id
                     :provider  "openai"
                     :agent     :default
                     :messenger (h/messenger)
                     :metrics   (h/metrics)}
          received-msgs* (atom "")
          add-to-history! (fn [msg]
                            (swap! db* update-in [:chats chat-id :messages] (fnil conj []) msg))
          tool-calls [{:id "call-1" :full-name "eca__tool_a" :arguments {} :arguments-text "{}"}
                      {:id "call-2" :full-name "eca__tool_b" :arguments {} :arguments-text "{}"}]
          all-tools  [{:name "tool_a" :full-name "eca__tool_a" :origin :eca :server {:name "eca"}}
                      {:name "tool_b" :full-name "eca__tool_b" :origin :eca :server {:name "eca"}}]
          ;; tool names that reached the preToolCall hook (i.e. were processed)
          hooked-tools* (atom [])
          ;; tools that were actually executed
          called-tools* (atom [])
          ;; system messages surfaced to the user
          sent* (atom [])]
      (with-redefs [f.tools/all-tools                           (constantly all-tools)
                    f.tools/approval                            (constantly :allow)
                    f.hooks/trigger-if-matches!
                    (fn [hook-type data {:keys [on-after-action]} _ _]
                      (when (= :preToolCall hook-type)
                        (swap! hooked-tools* conj (:tool-name data))
                        ;; The first tool's hook returns a successful continue:false.
                        (when (= "tool_a" (:tool-name data))
                          (on-after-action {:exit 0
                                            :name "stopper"
                                            :parsed {"continue" false "stopReason" "halt"}}))))
                    f.tools/call-tool!                          (fn [full-name & _]
                                                                  (swap! called-tools* conj full-name)
                                                                  {:contents [{:text "ok" :type :text}]})
                    f.tools/tool-call-details-before-invocation (constantly nil)
                    f.tools/tool-call-details-after-invocation  (constantly nil)
                    f.tools/tool-call-summary                   (constantly "Tool")
                    lifecycle/maybe-renew-auth-token            (fn [_] nil)
                    lifecycle/send-content!                     (fn [_ role content]
                                                                  (swap! sent* conj {:role role :content content}))]
        (let [result ((tc/on-tools-called! chat-ctx received-msgs* add-to-history! []) tool-calls)]
          ;; continue:false stops the turn — the loop returns nil (no continuation).
          (is (nil? result))
          ;; Only the first tool was ever processed by preToolCall hooks.
          (is (= ["tool_a"] @hooked-tools*)
              "the second tool must not be processed after a current-turn stop")
          ;; The second tool was never executed.
          (is (not-any? #{"eca__tool_b"} @called-tools*)
              "the second tool must not run after a current-turn stop")
          ;; A turn-stop message carrying the hook's reason is surfaced to the user.
          ;; The exact wording is covered by lifecycle/turn-stopped-by-hook-message-test.
          (is (some #(some-> (get-in % [:content :text]) (string/includes? "halt")) @sent*)
              "a turn-stopped message must be shown"))))))

(deftest send-toolCalled-image-content-emission-test
  (testing "image outputs are split into separate ChatImageContent events"
    (h/reset-components!)
    (let [chat-ctx {:chat-id "chat-1" :messenger (h/messenger)}
          event-data {:name "create-image"
                      :origin :mcp
                      :server "openai-image"
                      :arguments {:prompt "knight"}
                      :total-time-ms 1234
                      :outputs [{:type :text :text "ok"}
                                {:type :image :media-type "image/png" :base64 "AAAA"}
                                {:type :image :media-type "image/jpeg" :base64 "BBBB"}]}]
      (#'tc/execute-action! :send-toolCalled (h/db*) chat-ctx "call-42" event-data)
      (let [events (->> (h/messages) :chat-content-received (mapv :content))]
        (is (= 3 (count events))
            "expects 1 toolCalled + 2 image events")
        (is (match? {:type :toolCalled
                     :id "call-42"
                     :outputs [{:type :text :text "ok"}]}
                    (first events))
            "toolCalled :outputs is text-only per protocol")
        (is (match? {:type :image :media-type "image/png" :base64 "AAAA"}
                    (second events)))
        (is (match? {:type :image :media-type "image/jpeg" :base64 "BBBB"}
                    (nth events 2))))))

  (testing "no images preserves prior single-event behavior"
    (h/reset-components!)
    (let [chat-ctx {:chat-id "chat-1" :messenger (h/messenger)}
          event-data {:name "shell_command"
                      :origin :native
                      :server "eca"
                      :arguments {}
                      :total-time-ms 7
                      :outputs [{:type :text :text "done"}]}]
      (#'tc/execute-action! :send-toolCalled (h/db*) chat-ctx "call-43" event-data)
      (let [events (->> (h/messages) :chat-content-received (mapv :content))]
        (is (= 1 (count events)))
        (is (match? {:type :toolCalled
                     :outputs [{:type :text :text "done"}]}
                    (first events)))))))

(deftest post-tool-call-stop-info-test
  (let [prompt-id "prompt-2"]
    ;; [tool-calls => expected stop info]
    (are [tool-calls expected] (= expected (#'tc/post-tool-call-stop-info tool-calls prompt-id))
      ;; empty tool-calls map reports no stop
      {} {:stop-turn? false :stop-reason nil :stop-hook-name nil}
      ;; no hook requested a stop
      {"t1" {:status :completed}} {:stop-turn? false :stop-reason nil :stop-hook-name nil}
      ;; stale stop from an older prompt is ignored
      {"t1" {:post-tool-call-stop? true
             :post-tool-call-stop-prompt-id "prompt-1"
             :post-tool-call-stop-reason "old halt"
             :post-tool-call-stop-hook-name "old-redact"}} {:stop-turn? false :stop-reason nil :stop-hook-name nil}
      ;; stop without prompt id is ignored; postToolCall stops are current-turn only
      {"t1" {:post-tool-call-stop? true}} {:stop-turn? false :stop-reason nil :stop-hook-name nil}
      ;; current prompt stop requested without a reason
      {"t1" {:post-tool-call-stop? true
             :post-tool-call-stop-prompt-id prompt-id}} {:stop-turn? true :stop-reason nil :stop-hook-name nil}
      ;; current prompt stop requested with a reason
      {"t1" {:post-tool-call-stop? true
             :post-tool-call-stop-prompt-id prompt-id
             :post-tool-call-stop-reason "halt"
             :post-tool-call-stop-hook-name "redact"}} {:stop-turn? true :stop-reason "halt" :stop-hook-name "redact"})

    (testing "reason is found even when it lives on a different current-prompt tool-call than the first stop flag"
      (is (match? {:stop-turn? true :stop-reason "halt"}
                  (#'tc/post-tool-call-stop-info
                   (array-map "t1" {:post-tool-call-stop? true
                                    :post-tool-call-stop-prompt-id prompt-id}
                              "t2" {:post-tool-call-stop? true
                                    :post-tool-call-stop-prompt-id prompt-id
                                    :post-tool-call-stop-reason "halt"})
                   prompt-id))))

    (testing "old prompt stop does not leak when a later tool call has no stop"
      (is (= {:stop-turn? false :stop-reason nil :stop-hook-name nil}
             (#'tc/post-tool-call-stop-info
              (array-map "old" {:post-tool-call-stop? true
                                :post-tool-call-stop-prompt-id "prompt-1"
                                :post-tool-call-stop-reason "old halt"}
                         "current" {:status :completed})
              prompt-id))))))

(deftest trigger-post-tool-call-hook-tags-stop-with-prompt-id-test
  (testing "postToolCall continue:false stop state is scoped to the current prompt"
    (h/reset-components!)
    (let [db* (h/db*)]
      (swap! db* assoc-in [:chats "chat-1" :tool-calls "tool-1"] {:status :completed})
      (with-redefs-fn {#'tc/run-post-tool-call-hooks! (constantly {:stop-turn? true
                                                                   :stop-reason "halt"
                                                                   :stop-hook-name "guard"})}
        (fn []
          (#'tc/execute-action! :trigger-post-tool-call-hook
                                db*
                                {:chat-id "chat-1"
                                 :prompt-id "prompt-2"
                                 :messenger (h/messenger)}
                                "tool-1"
                                {:outputs [{:type :text :text "ok"}]})))
      (is (match? {:post-tool-call-stop? true
                   :post-tool-call-stop-prompt-id "prompt-2"
                   :post-tool-call-stop-reason "halt"
                   :post-tool-call-stop-hook-name "guard"}
                  (get-in @db* [:chats "chat-1" :tool-calls "tool-1"]))))))
