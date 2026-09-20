(ns eca.features.tools.agent-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.cache :as cache]
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.chat :as f.chat]
   [eca.features.chat.lifecycle :as lifecycle]
   [eca.features.chat.tool-calls :as tool-calls]
   [eca.features.hooks :as hooks]
   [eca.features.tools :as f.tools]
   [eca.features.tools.agent :as f.tools.agent]
   [eca.features.tools.util :as tools.util]
   [eca.llm-api :as llm-api]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(def ^:private test-config
  {:agent {"explorer" {:mode "subagent"
                       :description "Explores codebases"
                       :maxSteps 5
                       :systemPrompt "You are an explorer."}
           "general" {:mode "subagent"
                      :description "General purpose agent"}
           "variant-worker" {:mode "subagent"
                             :description "Worker with a configured variant"
                             :variant "high"}
           "code" {:mode "primary"
                   :description "Code agent"}
           "swiss-knife" {:mode ["primary" "subagent"]
                          :description "Works as primary or subagent"}
           "duel-worker" {:mode "subagent"
                          :description "Private duel worker"
                          :spawnableBy "duel"}
           "duel-reviewer" {:mode "subagent"
                            :description "Private duel reviewer"
                            :spawnableBy ["duel" "another-orchestrator"]}}
   :variantsByModel {".*sonnet[-._]4[-._]6|opus[-._]4[-._][56]"
                     {:variants {"low" {:thinking {:type "adaptive"}}
                                 "medium" {:thinking {:type "adaptive"}}
                                 "high" {:thinking {:type "adaptive"}}
                                 "max" {:thinking {:type "adaptive"}}}}
                     ".*gpt[-._]5"
                     {:variants {"none" {:reasoning {:effort "none"}}
                                 "low" {:reasoning {:effort "low"}}
                                 "medium" {:reasoning {:effort "medium"}}
                                 "high" {:reasoning {:effort "high"}}}}}})

(def ^:private test-db
  {:models {"anthropic/claude-sonnet-4-6" {}
            "anthropic/claude-opus-4-6" {}
            "openai/gpt-4.1" {}}})

(defn ^:private spawn-handler []
  (get-in (f.tools.agent/definitions test-config test-db) ["spawn_agent" :handler]))

(defn ^:private spawn-summary [args]
  ((get-in (f.tools.agent/definitions test-config test-db) ["spawn_agent" :summary-fn]) {:args args}))

(defn ^:private spawn-description [parent-agent-name]
  (get-in (f.tools.agent/definitions test-config test-db parent-agent-name)
          ["spawn_agent" :description]))

(defn ^:private stub-requiring-resolve
  [db* subagent-chat-id chat-prompt-called*]
  (fn [sym]
    (case sym
      eca.features.chat/prompt
      (fn [params _db* _messenger _config _metrics]
        (deliver chat-prompt-called* params)
        (swap! db* assoc-in [:chats subagent-chat-id :status] :idle)
        (swap! db* assoc-in [:chats subagent-chat-id :messages]
               [{:role "assistant"
                 :content [{:type :text :text "Done."}]}]))
      (clojure.lang.RT/var (namespace sym) (name sym)))))

(deftest spawn-agent-parent-visibility-test
  (testing "unrestricted subagents are visible to every primary agent and without a parent"
    (doseq [parent-agent-name [nil "code" "duel"]]
      (let [description (spawn-description parent-agent-name)]
        (is (string/includes? description "explorer: Explores codebases"))
        (is (string/includes? description "general: General purpose agent")))))

  (testing "restricted subagents are visible only to allowed parents"
    (is (string/includes? (spawn-description "duel") "duel-worker: Private duel worker"))
    (is (string/includes? (spawn-description "duel") "duel-reviewer: Private duel reviewer"))
    (is (string/includes? (spawn-description "another-orchestrator") "duel-reviewer: Private duel reviewer"))
    (is (not (string/includes? (spawn-description "another-orchestrator") "duel-worker")))
    (is (not (string/includes? (spawn-description "code") "duel-worker")))
    (is (not (string/includes? (spawn-description nil) "duel-worker"))))

  (testing "native tool generation propagates the current primary agent"
    (let [duel-description (->> (f.tools/native-tools "chat-1" "duel" test-db test-config)
                                (filter #(= "spawn_agent" (:name %)))
                                first
                                :description)
          code-description (->> (f.tools/native-tools "chat-1" "code" test-db test-config)
                                (filter #(= "spawn_agent" (:name %)))
                                first
                                :description)]
      (is (string/includes? duel-description "duel-worker"))
      (is (not (string/includes? code-description "duel-worker"))))))

(deftest spawn-agent-activity-summary-test
  (testing "normal activity label is unchanged"
    (is (= "explorer: searching files"
           (spawn-summary {"agent" "explorer" "activity" "searching files"}))))

  (testing "whitespace and newlines are collapsed"
    (is (= "explorer: searching files"
           (spawn-summary {"agent" "explorer" "activity" "  searching\n\t files  "}))))

  (testing "long activity label is truncated"
    (let [long-label (apply str (repeat 80 "a"))]
      (is (= (str "explorer: " (apply str (repeat 40 "a")) "...")
             (spawn-summary {"agent" "explorer" "activity" long-label})))))

  (testing "blank activity omits summary suffix"
    (is (= "explorer"
           (spawn-summary {"agent" "explorer" "activity" "  \n  "})))
    (is (= "explorer"
           (spawn-summary {"agent" "explorer"})))))

(deftest spawn-agent-normalize-arguments-test
  (is (= {"agent" "explorer" "task" "find" "activity" "searching files"}
         (f.tools.agent/normalize-arguments {"agent" "explorer"
                                             "task" "find"
                                             "activity" " searching\nfiles "})))
  (is (= {"agent" "explorer" "task" "find"}
         (f.tools.agent/normalize-arguments {"agent" "explorer"
                                             "task" "find"
                                             "activity" ""})))
  (is (= {"agent" "explorer" "task" "find"}
         (f.tools.agent/normalize-arguments {"agent" "explorer"
                                             "task" "find"
                                             "activity" ["not" "string"]})))
  (is (= {"agent" "explorer" "task" "find" "activity" "searching files"}
         (f.tools.agent/normalize-arguments
          (f.tools.agent/normalize-arguments {"agent" "explorer"
                                              "task" "find"
                                              "activity" " searching\nfiles "})))))

(deftest spawn-agent-not-found-test
  (testing "throws when agent is not found"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1"}}})
          result (try
                   ((spawn-handler)
                    {"agent" "nonexistent" "task" "do stuff" "activity" "working"}
                    {:db* db*
                     :config test-config
                     :messenger (h/messenger)
                     :metrics (h/metrics)
                     :chat-id "chat-1"
                     :tool-call-id "tc-1"
                     :call-state-fn (constantly {:status :executing})})
                   (catch Exception e
                     {:error true :ex-data (ex-data e) :message (ex-message e)}))]
      (is (match? {:error true
                   :message #"not found"}
                  result))
      (is (match? {:agent-name "nonexistent"}
                  (:ex-data result))))))

(deftest spawn-agent-parent-authorization-test
  (testing "an allowed parent can spawn a restricted subagent"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1" :model "test/model"}}})
          subagent-chat-id "subagent-tc-private"
          chat-prompt-called* (promise)]
      (with-redefs [requiring-resolve
                    (fn [sym]
                      (case sym
                        eca.features.chat/prompt
                        (fn [params _db* _messenger _config _metrics]
                          (deliver chat-prompt-called* params)
                          (swap! db* assoc-in [:chats subagent-chat-id :status] :idle)
                          (swap! db* assoc-in [:chats subagent-chat-id :messages]
                                 [{:role "assistant"
                                   :content [{:type :text :text "Private work complete."}]}]))
                        (clojure.lang.RT/var (namespace sym) (name sym))))]
        (let [result ((spawn-handler)
                      {"agent" "duel-worker" "task" "implement"}
                      {:db* db*
                       :config test-config
                       :messenger (h/messenger)
                       :metrics (h/metrics)
                       :agent "duel"
                       :chat-id "chat-1"
                       :tool-call-id "tc-private"
                       :call-state-fn (constantly {:status :executing})})]
          (is (match? {:error false
                       :contents [{:text #"Private work complete"}]}
                      result))
          (is (= "duel-worker" (:agent @chat-prompt-called*)))))))

  (testing "an unauthorized parent cannot spawn a restricted subagent or discover it in the error"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1" :model "test/model"}}})
          result (try
                   ((spawn-handler)
                    {"agent" "duel-worker" "task" "implement"}
                    {:db* db*
                     :config test-config
                     :messenger (h/messenger)
                     :metrics (h/metrics)
                     :agent "code"
                     :chat-id "chat-1"
                     :tool-call-id "tc-denied"
                     :call-state-fn (constantly {:status :executing})})
                   (catch Exception e
                     {:message (ex-message e)
                      :data (ex-data e)}))]
      (is (string/includes? (:message result) "not found or not available"))
      (is (not (string/includes? (:message result) "duel-worker")))
      (is (not (string/includes? (:message result) "Private duel worker")))
      (is (not (some #{"duel-worker"} (:available (:data result)))))
      (is (nil? (get-in @db* [:chats "subagent-tc-denied"])))))

  (testing "a missing parent cannot spawn a restricted subagent"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1" :model "test/model"}}})
          result (try
                   ((spawn-handler)
                    {"agent" "duel-worker" "task" "implement"}
                    {:db* db*
                     :config test-config
                     :messenger (h/messenger)
                     :metrics (h/metrics)
                     :chat-id "chat-1"
                     :tool-call-id "tc-no-parent"
                     :call-state-fn (constantly {:status :executing})})
                   (catch Exception e
                     {:message (ex-message e)
                      :data (ex-data e)}))]
      (is (string/includes? (:message result) "not found or not available"))
      (is (not (some #{"duel-worker"} (:available (:data result)))))
      (is (nil? (get-in @db* [:chats "subagent-tc-no-parent"]))))))

(deftest spawn-agent-nesting-prevention-test
  (testing "throws when subagent tries to spawn another subagent"
    (let [db* (atom {:chats {"sub-chat" {:id "sub-chat"
                                         :subagent {:name "explorer"}}}})
          result (try
                   ((spawn-handler)
                    {"agent" "general" "task" "do stuff" "activity" "working"}
                    {:db* db*
                     :config test-config
                     :messenger (h/messenger)
                     :metrics (h/metrics)
                     :chat-id "sub-chat"
                     :tool-call-id "tc-1"
                     :call-state-fn (constantly {:status :executing})})
                   (catch Exception e
                     {:error true :message (ex-message e)}))]
      (is (match? {:error true
                   :message #"nesting not allowed"}
                  result)))))

(deftest spawn-agent-completion-test
  (testing "returns summary when subagent completes successfully"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1" :model "test/model"}}})
          subagent-chat-id "subagent-tc-1"
          chat-prompt-called* (promise)]
      (with-redefs [;; Mock chat/prompt to simulate subagent running and completing
                    requiring-resolve
                    (fn [sym]
                      (case sym
                        eca.features.chat/prompt
                        (fn [params _db* _messenger _config _metrics]
                          (deliver chat-prompt-called* params)
                          ;; Simulate the subagent completing with a response
                          (swap! db* assoc-in [:chats subagent-chat-id :status] :idle)
                          (swap! db* assoc-in [:chats subagent-chat-id :messages]
                                 [{:role "assistant"
                                   :content [{:type :text :text "Found 3 files matching the pattern."}]}]))
                        (clojure.lang.RT/var (namespace sym) (name sym))))]
        (let [result ((spawn-handler)
                      {"agent" "explorer" "task" "find files" "activity" "exploring"}
                      {:db* db*
                       :config test-config
                       :messenger (h/messenger)
                       :metrics (h/metrics)
                       :chat-id "chat-1"
                       :tool-call-id "tc-1"
                       :call-state-fn (constantly {:status :executing})})]
          (is (match? {:error false
                       :contents [{:type :text
                                   :text #"Found 3 files"}]}
                      result))
          (testing "passes correct params to chat/prompt"
            (is (match? {:chat-id subagent-chat-id
                         :agent "explorer"
                         :model "test/model"}
                        @chat-prompt-called*)))
          (testing "preserves subagent chat for resume replay"
            (is (some? (get-in @db* [:chats subagent-chat-id])))))))))

(deftest spawn-agent-provider-error-test
  (testing "returns a failed tool result with provider error and partial output"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1" :model "test/model"}}})
          subagent-chat-id "subagent-tc-error"]
      (with-redefs [requiring-resolve
                    (fn [sym]
                      (case sym
                        eca.features.chat/prompt
                        (fn [_params _db* _messenger _config _metrics]
                          (swap! db* assoc-in [:chats subagent-chat-id :status] :idle)
                          (swap! db* assoc-in [:chats subagent-chat-id :prompt-error]
                                 {:message "Our servers are currently overloaded. Please try again later."
                                  :error-type :overloaded
                                  :request-id "req_overloaded"})
                          (swap! db* assoc-in [:chats subagent-chat-id :messages]
                                 [{:role "assistant"
                                   :content [{:type :text :text "Partial findings"}]}]))
                        (clojure.lang.RT/var (namespace sym) (name sym))))]
        (let [result ((spawn-handler)
                      {"agent" "explorer" "task" "find files" "activity" "exploring"}
                      {:db* db*
                       :config test-config
                       :messenger (h/messenger)
                       :metrics (h/metrics)
                       :chat-id "chat-1"
                       :tool-call-id "tc-error"
                       :call-state-fn (constantly {:status :executing})})]
          (is (match? {:error true
                       :contents [{:type :text
                                   :text #"(?s)Failed.*servers are currently overloaded.*Error type: overloaded.*Request ID: req_overloaded.*Partial result.*Partial findings"}]}
                      result))))))

  (testing "rate-limited error includes reset time and retry guidance"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1" :model "test/model"}}})
          subagent-chat-id "subagent-tc-rate-limited"]
      (with-redefs [requiring-resolve
                    (fn [sym]
                      (case sym
                        eca.features.chat/prompt
                        (fn [_params _db* _messenger _config _metrics]
                          (swap! db* assoc-in [:chats subagent-chat-id :status] :idle)
                          (swap! db* assoc-in [:chats subagent-chat-id :prompt-error]
                                 {:message "Anthropic rate_limit_error: This request would exceed your rate limit"
                                  :error-type :rate-limited
                                  :status 429
                                  :code "rate_limit_error"
                                  :rate-limit-resets-at 1756204800000}))
                        (clojure.lang.RT/var (namespace sym) (name sym))))]
        (let [result ((spawn-handler)
                      {"agent" "explorer" "task" "find files"}
                      {:db* db*
                       :config test-config
                       :messenger (h/messenger)
                       :metrics (h/metrics)
                       :chat-id "chat-1"
                       :tool-call-id "tc-rate-limited"
                       :call-state-fn (constantly {:status :executing})})]
          (is (match? {:error true
                       :contents [{:type :text
                                   :text #"(?s)Failed.*rate limit.*Error type: rate-limited.*Status: 429.*Code: rate_limit_error.*Rate limit resets at: 2025-08-26T10:40:00Z.*transient provider error\. Continue this agent using the returned `chat_id`"}]}
                      result))))))

  (testing "non-retryable error advises against retrying the same way"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1" :model "test/model"}}})
          subagent-chat-id "subagent-tc-auth"]
      (with-redefs [requiring-resolve
                    (fn [sym]
                      (case sym
                        eca.features.chat/prompt
                        (fn [_params _db* _messenger _config _metrics]
                          (swap! db* assoc-in [:chats subagent-chat-id :status] :idle)
                          (swap! db* assoc-in [:chats subagent-chat-id :prompt-error]
                                 {:message "Invalid API key"
                                  :error-type :auth
                                  :status 401}))
                        (clojure.lang.RT/var (namespace sym) (name sym))))]
        (let [result ((spawn-handler)
                      {"agent" "explorer" "task" "find files"}
                      {:db* db*
                       :config test-config
                       :messenger (h/messenger)
                       :metrics (h/metrics)
                       :chat-id "chat-1"
                       :tool-call-id "tc-auth"
                       :call-state-fn (constantly {:status :executing})})]
          (is (match? {:error true
                       :contents [{:type :text
                                   :text #"(?s)Failed.*Invalid API key.*Error type: auth.*Status: 401.*unlikely to help"}]}
                      result))))))

  (testing "an error status without structured details still returns failure"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1" :model "test/model"}}})
          subagent-chat-id "subagent-tc-error-status"]
      (with-redefs [requiring-resolve
                    (fn [sym]
                      (case sym
                        eca.features.chat/prompt
                        (fn [_params _db* _messenger _config _metrics]
                          (swap! db* assoc-in [:chats subagent-chat-id :status] :error))
                        (clojure.lang.RT/var (namespace sym) (name sym))))]
        (let [result ((spawn-handler)
                      {"agent" "general" "task" "investigate"}
                      {:db* db*
                       :config test-config
                       :messenger (h/messenger)
                       :metrics (h/metrics)
                       :chat-id "chat-1"
                       :tool-call-id "tc-error-status"
                       :call-state-fn (constantly {:status :executing})})]
          (is (match? {:error true
                       :contents [{:text #"sub-agent prompt failed"}]}
                      result)))))))

(deftest spawn-agent-trust-propagation-test
  (testing "forwards trust to subagent chat/prompt"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1" :model "test/model"}}})
          subagent-chat-id "subagent-tc-1"
          chat-prompt-called* (promise)]
      (with-redefs [requiring-resolve
                    (fn [sym]
                      (case sym
                        eca.features.chat/prompt
                        (fn [params _db* _messenger _config _metrics]
                          (deliver chat-prompt-called* params)
                          (swap! db* assoc-in [:chats subagent-chat-id :status] :idle)
                          (swap! db* assoc-in [:chats subagent-chat-id :messages]
                                 [{:role "assistant"
                                   :content [{:type :text :text "Done."}]}]))
                        (clojure.lang.RT/var (namespace sym) (name sym))))]
        ((spawn-handler)
         {"agent" "explorer" "task" "find files" "activity" "exploring"}
         {:db* db*
          :config test-config
          :messenger (h/messenger)
          :metrics (h/metrics)
          :chat-id "chat-1"
          :tool-call-id "tc-1"
          :call-state-fn (constantly {:status :executing})
          :trust true})
        (is (match? {:chat-id subagent-chat-id
                     :agent "explorer"
                     :trust true}
                    @chat-prompt-called*)))))

  (testing "does not forward trust when not set"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1" :model "test/model"}}})
          subagent-chat-id "subagent-tc-1"
          chat-prompt-called* (promise)]
      (with-redefs [requiring-resolve
                    (fn [sym]
                      (case sym
                        eca.features.chat/prompt
                        (fn [params _db* _messenger _config _metrics]
                          (deliver chat-prompt-called* params)
                          (swap! db* assoc-in [:chats subagent-chat-id :status] :idle)
                          (swap! db* assoc-in [:chats subagent-chat-id :messages]
                                 [{:role "assistant"
                                   :content [{:type :text :text "Done."}]}]))
                        (clojure.lang.RT/var (namespace sym) (name sym))))]
        ((spawn-handler)
         {"agent" "explorer" "task" "find files" "activity" "exploring"}
         {:db* db*
          :config test-config
          :messenger (h/messenger)
          :metrics (h/metrics)
          :chat-id "chat-1"
          :tool-call-id "tc-1"
          :call-state-fn (constantly {:status :executing})})
        (is (nil? (:trust @chat-prompt-called*)))))))

(deftest spawn-agent-max-steps-reached-test
  (testing "returns halted result when subagent reaches max steps"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1" :model "test/model"}}})
          subagent-chat-id "subagent-tc-1"]
      (with-redefs [requiring-resolve
                    (fn [sym]
                      (case sym
                        eca.features.chat/prompt
                        (fn [_params _db* _messenger _config _metrics]
                          (swap! db* assoc-in [:chats subagent-chat-id :status] :idle)
                          (swap! db* assoc-in [:chats subagent-chat-id :max-steps-reached?] true)
                          (swap! db* assoc-in [:chats subagent-chat-id :messages]
                                 [{:role "assistant"
                                   :content [{:type :text :text "Partial results so far."}]}]))
                        (clojure.lang.RT/var (namespace sym) (name sym))))]
        (let [result ((spawn-handler)
                      {"agent" "explorer" "task" "find files" "activity" "exploring"}
                      {:db* db*
                       :config test-config
                       :messenger (h/messenger)
                       :metrics (h/metrics)
                       :chat-id "chat-1"
                       :tool-call-id "tc-1"
                       :call-state-fn (constantly {:status :executing})})]
          (is (match? {:error true
                       :contents [{:type :text
                                   :text #"(?s)Halted.*maximum number of steps \(5\)"}]}
                      result)))))))

(deftest spawn-agent-parent-stop-test
  (testing "stops subagent when parent chat is stopped"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1" :model "test/model"}}})
          subagent-chat-id "subagent-tc-1"
          call-state* (atom {:status :executing})]
      (with-redefs [requiring-resolve
                    (fn [sym]
                      (case sym
                        eca.features.chat/prompt
                        (fn [_params _db* _messenger _config _metrics]
                          ;; Simulate subagent still running — parent will stop it
                          (swap! db* assoc-in [:chats subagent-chat-id :status] :running)
                          ;; Signal parent stop so the poll loop picks it up
                          (reset! call-state* {:status :stopping}))
                        eca.features.chat/prompt-stop
                        (fn [_params _db* _messenger _config _metrics _opts]
                          (swap! db* assoc-in [:chats subagent-chat-id :status] :idle))
                        (clojure.lang.RT/var (namespace sym) (name sym))))]
        (let [result ((spawn-handler)
                      {"agent" "explorer" "task" "explore" "activity" "exploring"}
                      {:db* db*
                       :config test-config
                       :messenger (h/messenger)
                       :metrics (h/metrics)
                       :chat-id "chat-1"
                       :tool-call-id "tc-1"
                       :call-state-fn #(deref call-state*)})]
          (is (match? {:error true
                       :contents [{:type :text :text #"was stopped"}]}
                      result))
          (is (true? (get-in @db* [:subagent-runs subagent-chat-id :interrupted?])))
          (with-redefs [requiring-resolve (fn [sym]
                                           (if (= sym 'eca.features.chat/prompt)
                                             (fn [& _] (swap! db* assoc-in [:chats subagent-chat-id :status] :idle))
                                             (clojure.lang.RT/var (namespace sym) (name sym))))]
            (is (false? (:error
                         ((spawn-handler) {"agent" "explorer" "task" "resume" "chat_id" subagent-chat-id}
                          {:db* db* :config test-config :chat-id "chat-1" :tool-call-id "resume"
                           :call-state-fn (constantly {:status :executing})})))))
          (testing "preserves subagent chat for resume replay"
            (is (some? (get-in @db* [:chats subagent-chat-id])))))))))

(deftest spawn-agent-setup-cancellation-test
  (doseq [failure [(InterruptedException.) (ex-info "setup cancelled" {})]]
    (let [db* (atom {:chats {"parent" {:model "test/model"}}})
          stopped* (atom false)]
      (with-redefs [f.chat/prompt (fn [& _]
                                   (swap! db* assoc-in [:chats "subagent-setup" :status] :running)
                                   (throw failure))
                    f.chat/prompt-stop (fn [& _] (reset! stopped* true))]
        (is (true? (:error ((spawn-handler) {"agent" "explorer" "task" "work"}
                           {:db* db* :config test-config :chat-id "parent" :tool-call-id "setup"
                            :call-state-fn (constantly {:status (if (instance? InterruptedException failure)
                                                                 :executing :stopping)})}))))
        (is @stopped* "Cancellation during synchronous setup must stop the dispatched child")))))

(deftest spawn-agent-cleanup-on-exception-test
  (testing "preserves subagent state when chat/prompt throws"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1" :model "test/model"}}})
          subagent-chat-id "subagent-tc-1"]
      (with-redefs [requiring-resolve
                    (fn [sym]
                      (case sym
                        eca.features.chat/prompt
                        (fn [_params _db* _messenger _config _metrics]
                          (throw (ex-info "LLM provider error" {})))
                        (clojure.lang.RT/var (namespace sym) (name sym))))]
        (is (match? {:error true :contents [{:text #"(?s)^Subagent chat_id: subagent-tc-1\n\n.*Failed.*LLM provider error"}]}
                     ((spawn-handler)
                      {"agent" "explorer" "task" "explore" "activity" "exploring"}
                      {:db* db*
                       :config test-config
                       :messenger (h/messenger)
                       :metrics (h/metrics)
                       :chat-id "chat-1"
                       :tool-call-id "tc-1"
                       :call-state-fn (constantly {:status :executing})})))
        (testing "subagent chat is preserved for resume replay"
          (is (some? (get-in @db* [:chats subagent-chat-id]))))))))

(deftest spawn-agent-user-specified-model-test
  (testing "uses user-specified model over agent default and parent model"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1" :model "test/parent-model"}}
                     :models {"anthropic/claude-sonnet-4-6" {}
                              "openai/gpt-4.1" {}}})
          subagent-chat-id "subagent-tc-1"
          chat-prompt-called* (promise)]
      (with-redefs [requiring-resolve
                    (fn [sym]
                      (case sym
                        eca.features.chat/prompt
                        (fn [params _db* _messenger _config _metrics]
                          (deliver chat-prompt-called* params)
                          (swap! db* assoc-in [:chats subagent-chat-id :status] :idle)
                          (swap! db* assoc-in [:chats subagent-chat-id :messages]
                                 [{:role "assistant"
                                   :content [{:type :text :text "Done."}]}]))
                        (clojure.lang.RT/var (namespace sym) (name sym))))]
        (let [result ((spawn-handler)
                      {"agent" "explorer" "task" "explore" "activity" "exploring"
                       "model" "anthropic/claude-sonnet-4-6"}
                      {:db* db*
                       :config test-config
                       :messenger (h/messenger)
                       :metrics (h/metrics)
                       :chat-id "chat-1"
                       :tool-call-id "tc-1"
                       :call-state-fn (constantly {:status :executing})})]
          (is (match? {:error false} result))
          (is (= "anthropic/claude-sonnet-4-6" (:model @chat-prompt-called*))))))))

(deftest spawn-agent-invalid-model-test
  (testing "throws when user specifies a model not in available models"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1" :model "test/model"}}
                     :models {"anthropic/claude-sonnet-4-6" {}}})
          result (try
                   ((spawn-handler)
                    {"agent" "explorer" "task" "explore" "activity" "exploring"
                     "model" "nonexistent/model"}
                    {:db* db*
                     :config test-config
                     :messenger (h/messenger)
                     :metrics (h/metrics)
                     :chat-id "chat-1"
                     :tool-call-id "tc-1"
                     :call-state-fn (constantly {:status :executing})})
                   (catch Exception e
                     {:error true :ex-data (ex-data e) :message (ex-message e)}))]
      (is (match? {:error true
                   :message #"not available"}
                  result))
      (is (match? {:model "nonexistent/model"}
                  (:ex-data result))))))

(deftest spawn-agent-user-specified-variant-test
  (testing "passes user-specified variant to chat/prompt"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1" :model "anthropic/claude-sonnet-4-6"}}
                     :models {"anthropic/claude-sonnet-4-6" {}}})
          subagent-chat-id "subagent-tc-1"
          chat-prompt-called* (promise)]
      (with-redefs [requiring-resolve
                    (fn [sym]
                      (case sym
                        eca.features.chat/prompt
                        (fn [params _db* _messenger _config _metrics]
                          (deliver chat-prompt-called* params)
                          (swap! db* assoc-in [:chats subagent-chat-id :status] :idle)
                          (swap! db* assoc-in [:chats subagent-chat-id :messages]
                                 [{:role "assistant"
                                   :content [{:type :text :text "Done."}]}]))
                        (clojure.lang.RT/var (namespace sym) (name sym))))]
        (let [result ((spawn-handler)
                      {"agent" "explorer" "task" "explore" "activity" "exploring"
                       "variant" "high"}
                      {:db* db*
                       :config test-config
                       :messenger (h/messenger)
                       :metrics (h/metrics)
                       :chat-id "chat-1"
                       :tool-call-id "tc-1"
                       :call-state-fn (constantly {:status :executing})})]
          (is (match? {:error false} result))
          (is (= "high" (:variant @chat-prompt-called*)))))))

  (testing "does not include variant in chat/prompt params when not specified"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1" :model "anthropic/claude-sonnet-4-6"}}
                     :models {"anthropic/claude-sonnet-4-6" {}}})
          subagent-chat-id "subagent-tc-1"
          chat-prompt-called* (promise)]
      (with-redefs [requiring-resolve
                    (fn [sym]
                      (case sym
                        eca.features.chat/prompt
                        (fn [params _db* _messenger _config _metrics]
                          (deliver chat-prompt-called* params)
                          (swap! db* assoc-in [:chats subagent-chat-id :status] :idle)
                          (swap! db* assoc-in [:chats subagent-chat-id :messages]
                                 [{:role "assistant"
                                   :content [{:type :text :text "Done."}]}]))
                        (clojure.lang.RT/var (namespace sym) (name sym))))]
        ((spawn-handler)
         {"agent" "explorer" "task" "explore" "activity" "exploring"}
         {:db* db*
          :config test-config
          :messenger (h/messenger)
          :metrics (h/metrics)
          :chat-id "chat-1"
          :tool-call-id "tc-1"
          :call-state-fn (constantly {:status :executing})})
        (is (nil? (:variant @chat-prompt-called*)))))))

(deftest spawn-agent-configured-variant-test
  (testing "falls back to the agent's configured variant when the argument is absent"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1" :model "anthropic/claude-sonnet-4-6"}}
                     :models {"anthropic/claude-sonnet-4-6" {}}})
          chat-prompt-called* (promise)]
      (with-redefs [requiring-resolve (stub-requiring-resolve db* "subagent-tc-1" chat-prompt-called*)]
        (let [result ((spawn-handler)
                      {"agent" "variant-worker" "task" "work" "activity" "working"}
                      {:db* db*
                       :config test-config
                       :messenger (h/messenger)
                       :metrics (h/metrics)
                       :chat-id "chat-1"
                       :tool-call-id "tc-1"
                       :call-state-fn (constantly {:status :executing})})]
          (is (match? {:error false} result))
          (is (= "high" (:variant @chat-prompt-called*)))))))

  (testing "user-specified variant wins over the agent's configured variant"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1" :model "anthropic/claude-sonnet-4-6"}}
                     :models {"anthropic/claude-sonnet-4-6" {}}})
          chat-prompt-called* (promise)]
      (with-redefs [requiring-resolve (stub-requiring-resolve db* "subagent-tc-1" chat-prompt-called*)]
        (let [result ((spawn-handler)
                      {"agent" "variant-worker" "task" "work" "activity" "working"
                       "variant" "medium"}
                      {:db* db*
                       :config test-config
                       :messenger (h/messenger)
                       :metrics (h/metrics)
                       :chat-id "chat-1"
                       :tool-call-id "tc-1"
                       :call-state-fn (constantly {:status :executing})})]
          (is (match? {:error false} result))
          (is (= "medium" (:variant @chat-prompt-called*)))))))

  (testing "details-before-invocation includes the agent's configured variant"
    (let [db {:chats {"chat-1" {:id "chat-1" :agent "code"
                                :model "anthropic/claude-sonnet-4-6"}}
              :models {"anthropic/claude-sonnet-4-6" {}}}]
      (is (match? {:type :subagent
                   :model "anthropic/claude-sonnet-4-6"
                   :variant "high"
                   :agent-name "variant-worker"}
                  (tools.util/tool-call-details-before-invocation
                   :spawn_agent {"agent" "variant-worker" "task" "work"} nil
                   {:db db :config test-config :chat-id "chat-1" :tool-call-id "tc-1"}))))))

(deftest spawn-agent-invalid-variant-test
  (testing "throws when user specifies a variant not valid for the resolved model"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1" :model "anthropic/claude-sonnet-4-6"}}
                     :models {"anthropic/claude-sonnet-4-6" {}}})
          result (try
                   ((spawn-handler)
                    {"agent" "explorer" "task" "explore" "activity" "exploring"
                     "variant" "xhigh"}
                    {:db* db*
                     :config test-config
                     :messenger (h/messenger)
                     :metrics (h/metrics)
                     :chat-id "chat-1"
                     :tool-call-id "tc-1"
                     :call-state-fn (constantly {:status :executing})})
                   (catch Exception e
                     {:error true :ex-data (ex-data e) :message (ex-message e)}))]
      (is (match? {:error true
                   :message #"not available for model"}
                  result))
      (is (match? {:variant "xhigh"
                   :model "anthropic/claude-sonnet-4-6"
                   :available ["high" "low" "max" "medium"]}
                  (:ex-data result))))))

(deftest spawn-agent-combined-model-and-variant-test
  (testing "passes both user-specified model and variant to chat/prompt"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1" :model "openai/gpt-4.1"}}
                     :models {"anthropic/claude-sonnet-4-6" {}
                              "openai/gpt-4.1" {}}})
          subagent-chat-id "subagent-tc-1"
          chat-prompt-called* (promise)]
      (with-redefs [requiring-resolve
                    (fn [sym]
                      (case sym
                        eca.features.chat/prompt
                        (fn [params _db* _messenger _config _metrics]
                          (deliver chat-prompt-called* params)
                          (swap! db* assoc-in [:chats subagent-chat-id :status] :idle)
                          (swap! db* assoc-in [:chats subagent-chat-id :messages]
                                 [{:role "assistant"
                                   :content [{:type :text :text "Done."}]}]))
                        (clojure.lang.RT/var (namespace sym) (name sym))))]
        (let [result ((spawn-handler)
                      {"agent" "explorer" "task" "explore" "activity" "exploring"
                       "model" "anthropic/claude-sonnet-4-6" "variant" "high"}
                      {:db* db*
                       :config test-config
                       :messenger (h/messenger)
                       :metrics (h/metrics)
                       :chat-id "chat-1"
                       :tool-call-id "tc-1"
                       :call-state-fn (constantly {:status :executing})})]
          (is (match? {:error false} result))
          (is (= "anthropic/claude-sonnet-4-6" (:model @chat-prompt-called*)))
          (is (= "high" (:variant @chat-prompt-called*)))))))

  (testing "validates variant against user-specified model, not parent model"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1" :model "openai/gpt-4.1"}}
                     :models {"anthropic/claude-sonnet-4-6" {}
                              "openai/gpt-4.1" {}}})
          result (try
                   ((spawn-handler)
                    {"agent" "explorer" "task" "explore" "activity" "exploring"
                     "model" "anthropic/claude-sonnet-4-6" "variant" "xhigh"}
                    {:db* db*
                     :config test-config
                     :messenger (h/messenger)
                     :metrics (h/metrics)
                     :chat-id "chat-1"
                     :tool-call-id "tc-1"
                     :call-state-fn (constantly {:status :executing})})
                   (catch Exception e
                     {:error true :ex-data (ex-data e) :message (ex-message e)}))]
      (is (match? {:error true
                   :message #"not available for model"}
                  result))
      (is (match? {:model "anthropic/claude-sonnet-4-6"}
                  (:ex-data result))))))

(deftest spawn-agent-variant-for-model-without-variants-test
  (testing "variant passes through when model has no configured variants"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1" :model "openai/gpt-4.1"}}
                     :models {"openai/gpt-4.1" {}}})
          subagent-chat-id "subagent-tc-1"
          chat-prompt-called* (promise)]
      (with-redefs [requiring-resolve
                    (fn [sym]
                      (case sym
                        eca.features.chat/prompt
                        (fn [params _db* _messenger _config _metrics]
                          (deliver chat-prompt-called* params)
                          (swap! db* assoc-in [:chats subagent-chat-id :status] :idle)
                          (swap! db* assoc-in [:chats subagent-chat-id :messages]
                                 [{:role "assistant"
                                   :content [{:type :text :text "Done."}]}]))
                        (clojure.lang.RT/var (namespace sym) (name sym))))]
        (let [result ((spawn-handler)
                      {"agent" "explorer" "task" "explore" "activity" "exploring"
                       "variant" "high"}
                      {:db* db*
                       :config test-config
                       :messenger (h/messenger)
                       :metrics (h/metrics)
                       :chat-id "chat-1"
                       :tool-call-id "tc-1"
                       :call-state-fn (constantly {:status :executing})})]
          (is (match? {:error false} result))
          (is (= "high" (:variant @chat-prompt-called*))))))))

(deftest spawn-agent-model-accepted-when-models-db-empty-test
  (testing "user-specified model is accepted when models db is empty"
    (let [db* (atom {:chats {"chat-1" {:id "chat-1" :model "test/parent"}}
                     :models {}})
          subagent-chat-id "subagent-tc-1"
          chat-prompt-called* (promise)]
      (with-redefs [requiring-resolve
                    (fn [sym]
                      (case sym
                        eca.features.chat/prompt
                        (fn [params _db* _messenger _config _metrics]
                          (deliver chat-prompt-called* params)
                          (swap! db* assoc-in [:chats subagent-chat-id :status] :idle)
                          (swap! db* assoc-in [:chats subagent-chat-id :messages]
                                 [{:role "assistant"
                                   :content [{:type :text :text "Done."}]}]))
                        (clojure.lang.RT/var (namespace sym) (name sym))))]
        (let [result ((spawn-handler)
                      {"agent" "explorer" "task" "explore" "activity" "exploring"
                       "model" "some/new-model"}
                      {:db* db*
                       :config test-config
                       :messenger (h/messenger)
                       :metrics (h/metrics)
                       :chat-id "chat-1"
                       :tool-call-id "tc-1"
                       :call-state-fn (constantly {:status :executing})})]
          (is (match? {:error false} result))
          (is (= "some/new-model" (:model @chat-prompt-called*))))))))

(deftest spawn-agent-agent-default-model-priority-test
  (testing "user-specified model takes precedence over agent defaultModel"
    (let [config-with-default (assoc-in test-config [:agent "explorer" :defaultModel] "anthropic/claude-opus-4-6")
          db* (atom {:chats {"chat-1" {:id "chat-1" :model "test/parent"}}
                     :models {"anthropic/claude-sonnet-4-6" {}
                              "anthropic/claude-opus-4-6" {}}})
          subagent-chat-id "subagent-tc-1"
          chat-prompt-called* (promise)]
      (with-redefs [requiring-resolve
                    (fn [sym]
                      (case sym
                        eca.features.chat/prompt
                        (fn [params _db* _messenger _config _metrics]
                          (deliver chat-prompt-called* params)
                          (swap! db* assoc-in [:chats subagent-chat-id :status] :idle)
                          (swap! db* assoc-in [:chats subagent-chat-id :messages]
                                 [{:role "assistant"
                                   :content [{:type :text :text "Done."}]}]))
                        (clojure.lang.RT/var (namespace sym) (name sym))))]
        (let [result ((get-in (f.tools.agent/definitions config-with-default test-db) ["spawn_agent" :handler])
                      {"agent" "explorer" "task" "explore" "activity" "exploring"
                       "model" "anthropic/claude-sonnet-4-6"}
                      {:db* db*
                       :config config-with-default
                       :messenger (h/messenger)
                       :metrics (h/metrics)
                       :chat-id "chat-1"
                       :tool-call-id "tc-1"
                       :call-state-fn (constantly {:status :executing})})]
          (is (match? {:error false} result))
          (is (= "anthropic/claude-sonnet-4-6" (:model @chat-prompt-called*))
              "user-specified model should win over agent defaultModel"))))))

(deftest spawn-agent-defaultmodel-alias-test
  (testing "agent defaultModel bare alias resolves against the parent chat's provider"
    (let [config-with-alias (assoc-in test-config [:agent "explorer" :defaultModel] "explorer-small")
          db* (atom {:chats {"chat-1" {:id "chat-1" :model "company-litellm/big"}}
                     :models {"company-litellm/big" {}
                              "company-litellm/explorer-small" {}
                              "github-copilot/explorer-small" {}}})
          subagent-chat-id "subagent-tc-1"
          chat-prompt-called* (promise)]
      (with-redefs [requiring-resolve
                    (fn [sym]
                      (case sym
                        eca.features.chat/prompt
                        (fn [params _db* _messenger _config _metrics]
                          (deliver chat-prompt-called* params)
                          (swap! db* assoc-in [:chats subagent-chat-id :status] :idle)
                          (swap! db* assoc-in [:chats subagent-chat-id :messages]
                                 [{:role "assistant"
                                   :content [{:type :text :text "Done."}]}]))
                        (clojure.lang.RT/var (namespace sym) (name sym))))]
        (let [result ((get-in (f.tools.agent/definitions config-with-alias test-db) ["spawn_agent" :handler])
                      {"agent" "explorer" "task" "explore" "activity" "exploring"}
                      {:db* db*
                       :config config-with-alias
                       :messenger (h/messenger)
                       :metrics (h/metrics)
                       :chat-id "chat-1"
                       :tool-call-id "tc-1"
                       :call-state-fn (constantly {:status :executing})})]
          (is (match? {:error false} result))
          (is (= "company-litellm/explorer-small" (:model @chat-prompt-called*))
              "bare alias should resolve to the parent provider's model"))))))

(deftest extract-final-summary-test
  (testing "extracts text from last assistant message"
    (is (= "Hello world"
           (#'f.tools.agent/extract-final-summary
            [{:role "user" :content [{:type :text :text "Hi"}]}
             {:role "assistant" :content [{:type :text :text "Hello world"}]}]))))

  (testing "uses last assistant message when multiple exist"
    (is (= "Final answer"
           (#'f.tools.agent/extract-final-summary
            [{:role "assistant" :content [{:type :text :text "First response"}]}
             {:role "user" :content [{:type :text :text "More?"}]}
             {:role "assistant" :content [{:type :text :text "Final answer"}]}]))))

  (testing "joins multiple text blocks with newline"
    (is (= "Part 1\nPart 2"
           (#'f.tools.agent/extract-final-summary
            [{:role "assistant" :content [{:type :text :text "Part 1"}
                                          {:type :text :text "Part 2"}]}]))))

  (testing "ignores non-text content types"
    (is (= "Text only"
           (#'f.tools.agent/extract-final-summary
            [{:role "assistant" :content [{:type :tool-use :text "ignored"}
                                          {:type :text :text "Text only"}]}]))))

  (testing "returns default when no assistant messages"
    (is (= "Agent completed without producing output."
           (#'f.tools.agent/extract-final-summary
            [{:role "user" :content [{:type :text :text "Hi"}]}])))))

(deftest definitions-test
  (testing "spawn_agent tool definition has correct structure"
    (let [defs (f.tools.agent/definitions test-config test-db)
          tool (get defs "spawn_agent")]
      (is (some? tool))
      (is (string? (:description tool)))
      (is (match? {:type "object"
                   :properties {"agent" {:type "string"}
                                "task" {:type "string"}
                                "activity" {:type "string"}
                                "chat_id" {:type "string" :minLength 1}
                                "model" {:type "string"}
                                "variant" {:type "string"}}
                   :required ["agent" "task"]}
                  (:parameters tool)))
      (is (= ["agent" "task" "activity" "chat_id" "model" "variant"]
             (vec (keys (get-in tool [:parameters :properties])))))))

  (testing "model and variant enums are absent when no models in db"
    (let [defs (f.tools.agent/definitions test-config {})
          props (get-in defs ["spawn_agent" :parameters :properties])]
      (is (= "string" (:type (get props "model"))))
      (is (nil? (:enum (get props "model"))))
      (is (= "string" (:type (get props "variant"))))
      (is (nil? (:enum (get props "variant"))))))

  (testing "gracefully handles nil db"
    (let [defs (f.tools.agent/definitions test-config nil)
          props (get-in defs ["spawn_agent" :parameters :properties])]
      (is (some? (get props "model")))
      (is (nil? (:enum (get props "model"))))
      (is (nil? (:enum (get props "variant"))))))

  (testing "description includes available subagents"
    (let [desc (:description (get (f.tools.agent/definitions test-config test-db) "spawn_agent"))]
      (is (re-find #"- explorer:" desc))
      (is (re-find #"- general:" desc))
      (is (re-find #"- swiss-knife:" desc)
          "agents whose :mode list includes subagent should appear in subagent list")
      (is (not (re-find #"- code:" desc))
          "primary-only agents should not appear in subagent list")))

  (testing "summary-fn formats agent name with activity"
    (let [summary-fn (:summary-fn (get (f.tools.agent/definitions test-config test-db) "spawn_agent"))]
      (is (= "explorer: searching files"
             (summary-fn {:args {"agent" "explorer" "activity" "searching files"}})))
      (is (= "Spawning agent"
             (summary-fn {:args {}}))))))

(deftest resume-agent-test
  (let [db* (atom (assoc test-db :chats {"parent" {:model "openai/gpt-4.1"}}))
        calls* (atom [])
        context {:db* db* :config test-config :chat-id "parent" :tool-call-id "resume"
                 :messenger (h/messenger) :metrics (h/metrics)
                 :call-state-fn (constantly {:status :executing})}
        handler (spawn-handler)]
    (with-redefs [f.chat/prompt
                  (fn [params & _]
                    (swap! calls* conj params)
                    (swap! db* update-in [:chats (:chat-id params)]
                           #(-> % (assoc :status :idle :current-step 3 :prompt-cache {:kept true})
                                (update :messages (fnil conj [])
                                        {:role "assistant" :content [{:type :text :text (:message params)}]}))))]
      (handler {"agent" "explorer" "task" "first"} context)
      (let [history (get-in @db* [:chats "subagent-resume" :messages])]
        (swap! db* assoc-in [:chats "parent" :model] "anthropic/claude-opus-4-6")
        (let [result (handler {"agent" "explorer" "task" "second" "chat_id" "subagent-resume"}
                              (assoc context :tool-call-id "second"))]
          (is (string/includes? (tools.util/contents->text (:contents result)) "subagent-resume"))
          (is (= "openai/gpt-4.1" (:model (last @calls*))))
          (is (= history (take (count history) (get-in @db* [:chats "subagent-resume" :messages]))))
          (is (= {:kept true} (get-in @db* [:chats "subagent-resume" :prompt-cache])))))
      (doseq [selector [nil "" "  " 42 [] "missing" "parent"]]
        (let [before @db* calls @calls*]
          (is (thrown? clojure.lang.ExceptionInfo
                       (handler {"agent" "explorer" "task" "invalid" "chat_id" selector}
                                (assoc context :tool-call-id "invalid"))))
          (is (= before @db*))
          (is (= calls @calls*))))
      (testing "changed caller-supplied trust rejects resume"
        (let [before @db*]
          (is (thrown? clojure.lang.ExceptionInfo
                       (handler {"agent" "explorer" "task" "next" "chat_id" "subagent-resume"}
                                (assoc context :trust true))))
          (is (= before @db*)))))))

(deftest resume-admission-and-run-isolation-test
  (let [db* (atom {:chats {"parent" {:model "openai/gpt-4.1"}}})
        context {:db* db* :config test-config :messenger (h/messenger) :metrics (h/metrics)
                 :chat-id "parent" :tool-call-id "isolation" :trust true
                 :call-state-fn (constantly {:status :executing})}
        handler (spawn-handler)
        args {"agent" "explorer" "task" "next" "chat_id" "subagent-isolation"}]
    (with-redefs [f.chat/prompt (fn [{:keys [chat-id]} & _]
                                 (swap! db* update-in [:chats chat-id]
                                        #(assoc % :status :idle :current-step 5 :max-steps-reached? true
                                                :messages [{:role "assistant" :content [{:type :text :text "old answer"}]}])))]
      (handler {"agent" "explorer" "task" "first" "variant" "high"} context))
    (let [baseline @db*]
      (doseq [[label change changed-args changed-context]
              [["foreign parent" identity args (assoc context :chat-id "foreign")]
               ["different agent" identity (assoc args "agent" "general") context]
               ["authorization" identity args (assoc-in context [:config :agent "explorer" :spawnableBy] "other")]
               ["model override" identity (assoc args "model" "openai/gpt-4.1") context]
               ["variant override" identity (assoc args "variant" "") context]
               ["config drift" identity args (assoc-in context [:config :changed] true)]
               ["workspace drift" #(assoc % :workspace-folders [{:uri (h/file-uri "/other")}]) args context]
               ["trust drift" identity args (assoc context :trust false)]
               ["child trust drift" #(assoc-in % [:chats "subagent-isolation" :trust] false) args context]
               ["ordinary child" #(update-in % [:chats "subagent-isolation"] dissoc :subagent) args context]
               ["tool future" #(assoc-in % [:chats "subagent-isolation" :tool-calls "old" :future] (delay nil)) args context]
               ["tool resources" #(assoc-in % [:chats "subagent-isolation" :tool-calls "old" :resources] {:process :remaining}) args context]
               ["outstanding worker" #(assoc-in % [:subagent-runs "subagent-isolation" :workers] 1) args context]
               ["running" #(assoc-in % [:chats "subagent-isolation" :status] :running) args context]
               ["stopping" #(assoc-in % [:chats "subagent-isolation" :status] :stopping) args context]]]
        (testing label
          (reset! db* (change baseline))
          (let [before @db*]
            (with-redefs [f.chat/prompt (fn [& _] (is false "Rejected resume must not prompt"))]
              (is (thrown? clojure.lang.ExceptionInfo (handler changed-args changed-context)))
              (is (= before @db*))))))
      (reset! db* baseline)
      (testing "failed resource destruction retains the future and blocks admission"
        (swap! db* assoc-in [:chats "subagent-isolation" :tool-calls "old"]
               {:status :cleanup :future (delay nil) :resources {:process :remaining}})
        (with-redefs [f.tools/tool-call-destroy-resource! (fn [& _] (throw (ex-info "cleanup failed" {})))]
          (is (thrown? clojure.lang.ExceptionInfo
                       (tool-calls/transition-tool-call! db* {:chat-id "subagent-isolation"}
                                                         "old" :cleanup-finished {}))))
        (is (some? (get-in @db* [:chats "subagent-isolation" :tool-calls "old" :future])))
        (is (thrown? clojure.lang.ExceptionInfo (handler args context))))
      (reset! db* baseline)
      (testing "before details never disclose a foreign child's settings"
        (is (match? {:model nil :max-steps nil :step 1}
                    (tools.util/tool-call-details-before-invocation
                     :spawn_agent args nil {:db @db* :config test-config :chat-id "foreign" :tool-call-id "x"}))))
      (testing "settled interruption resets its outcome and ignores unstarted cleanup promises"
        (swap! db* assoc-in [:subagent-runs "subagent-isolation" :interrupted?] true)
        (swap! db* assoc-in [:chats "subagent-isolation" :tool-calls "rejected"]
               {:status :rejected :future-cleanup-complete?* (promise)})
        (with-redefs [f.chat/prompt (fn [params & _]
                                     (is (= "high" (:variant params)))
                                     (is (= 0 (get-in @db* [:chats "subagent-isolation" :current-step])))
                                     (is (nil? (get-in @db* [:chats "subagent-isolation" :max-steps-reached?])))
                                     (is (nil? (get-in @db* [:chats "subagent-isolation" :prompt-error])))
                                     (swap! db* assoc-in [:chats "subagent-isolation" :status] :idle))]
          (let [result (handler args (assoc context :tool-call-id "empty"))]
            (is (false? (:error result)))
            (is (not (string/includes? (tools.util/contents->text (:contents result)) "old answer"))))))
      (testing "immediate prompt errors finish without polling and allow settled reuse"
        (with-redefs [f.chat/prompt (constantly {:status :error})]
          (let [run (future (handler args (assoc context :tool-call-id "error")))]
            (try
              (is (match? {:error true :contents [{:text #"(?s)^Subagent chat_id: subagent-isolation\n\n.*Failed.*setup failed"}]}
                          (deref run 5000 ::timeout)))
              (is (true? (:error (handler args context))))
              (finally (when-not (realized? run) (future-cancel run))))))))))

(deftest managed-subagent-worker-unwind-test
  (doseq [failure [:silent-stop :finally-error]]
    (testing (name failure)
      (h/reset-components!)
      (h/config! {:env "test" :agent {"explorer" {:mode "subagent" :description "Explorer"}}})
      (swap! (h/db*) assoc-in [:chats "parent"] {:model "openai/gpt-5.2"})
      (let [worker-entered?* (atom false)
            context {:db* (h/db*) :config (h/config) :messenger (h/messenger) :metrics (h/metrics)
                     :chat-id "parent" :tool-call-id "unwind" :call-state-fn (constantly {:status :executing})}
            handler (spawn-handler)]
        (with-redefs [llm-api/sync-prompt! (constantly nil)
                      f.tools/all-tools (constantly [])
                      config/await-plugins-resolved! (constantly true)
                      db/save-chat! (fn [& _]
                                      (when (and @worker-entered?* (= :finally-error failure))
                                        (throw (ex-info "save failure" {}))))
                      llm-api/sync-or-async-prompt! (fn [_]
                                                    (reset! worker-entered?* true)
                                                    (when (= :silent-stop failure)
                                                      (throw (ex-info "stopped" {:silent? true}))))]
          (let [result (handler {"agent" "explorer" "task" "work"} context)]
            (is @worker-entered?*)
            (is (true? (:error result)))
            (is (string/includes? (tools.util/contents->text (:contents result)) "subagent-unwind"))
            (is (= 0 (get-in @(h/db*) [:subagent-runs "subagent-unwind" :workers])))
            (is (true? (get-in @(h/db*) [:subagent-runs "subagent-unwind" :interrupted?])))
            (is (nil? (get-in @(h/db*) [:subagent-runs "subagent-unwind" :token])))
            (is (true? (:error
                        (handler {"agent" "explorer" "task" "resume" "chat_id" "subagent-unwind"} context))))))))))

(deftest spawn-agent-replay-authorization-test
  (doseq [[id child allowed?]
          [["owned" {:subagent {:name "explorer"} :parent-chat-id "parent" :agent-name "explorer"} true]
           ["foreign" {:subagent {:name "explorer"} :parent-chat-id "other" :agent-name "explorer"} false]
           ["ordinary" {:parent-chat-id "parent" :agent-name "explorer"} false]
           ["wrong-agent" {:subagent {:name "general"} :parent-chat-id "parent" :agent-name "general"} false]
           [42 {:subagent {:name "explorer"} :parent-chat-id "parent" :agent-name "explorer"} false]
           ["" {:subagent {:name "explorer"} :parent-chat-id "parent" :agent-name "explorer"} false]
           [nil {} false]]]
    (testing (str "reference " (pr-str id))
      (let [db {:chats {"parent" {:agent "code"}
                        id (assoc child :messages [{:role "assistant" :content [{:type :text :text "Child transcript"}]}])}
                :subagent-runs {id {:parent-chat-id (:parent-chat-id child) :agent-name (:agent-name child)}}}
            details (tools.util/tool-call-details-before-invocation
                     :spawn_agent {"agent" "explorer" "chat_id" id} nil
                     {:db db :config test-config :chat-id "parent" :tool-call-id "rejected"})
            replay (fn [details]
                     (f.chat/messages->contents
                      [{:role "tool_call_output"
                        :content {:id "rejected" :name "spawn_agent" :error (not allowed?)
                                  :details details :output {:contents [{:type :text :text "Tool result"}]}}}]
                      {:chat-id "parent" :db (dissoc db :subagent-runs)}))]
        (is (= (when allowed? id) (:subagent-chat-id details)))
        (doseq [stored [details {:type :subagent :agent-name "explorer" :subagent-chat-id id}]]
          (is (= allowed? (boolean (some #(= "\nChild transcript" (get-in % [:content :text]))
                                        (replay stored))))))))))

(deftest spawn-agent-truncated-id-test
  (doseq [failed? [false true]]
    (testing (if failed? "failure with partial output" "success")
      (h/reset-components!)
      (h/config! {:agent {"explorer" {:mode "subagent" :description "Explorer"}}
                  :toolCall {:outputTruncation {:lines 100 :sizeKb 1}}})
      (let [id "subagent-truncated"
            saved* (atom nil)]
        (with-redefs [cache/save-tool-call-output! (fn [_ text] (reset! saved* text) "/unused/output.txt")
                      f.chat/prompt (fn [& _]
                                      (swap! (h/db*) update-in [:chats id]
                                             #(cond-> (assoc % :status :idle
                                                             :messages [{:role "assistant"
                                                                         :content [{:type :text :text (apply str (repeat 5000 "x"))}]}])
                                                failed? (assoc :prompt-error {:message "Provider failed"}))))]
          (let [result (f.tools/call-tool! "eca__spawn_agent" {"agent" "explorer" "task" "work"}
                                          "parent" "truncated" "code" (h/db*) (h/config) (h/messenger) (h/metrics)
                                          (constantly {:status :executing}) (fn [& _]) {})
                text (tools.util/contents->text (:contents result))]
            (is (= failed? (:error result)))
            (is (string/starts-with? text (str "Subagent chat_id: " id "\n\n")))
            (is (string/includes? text (if failed? "## Agent 'explorer' Failed" "## Agent 'explorer' Result")))
            (when failed?
              (is (string/includes? text "Provider failed"))
              (is (string/includes? text "## Partial result")))
            (is (string/includes? text "[OUTPUT TRUNCATED]"))
            (is (> (count @saved*) (count text)))))))))

(deftest spawn-agent-empty-selector-call-tool-test
  (h/config! {:agent {"explorer" {:mode "subagent" :description "Explorer"}}})
  (with-redefs [f.chat/prompt (fn [& _] (is false "Empty selector must never spawn a fresh child"))]
    (let [before @(h/db*)
          result (f.tools/call-tool! "eca__spawn_agent" {"agent" "explorer" "task" "work" "chat_id" ""}
                                    "parent" "empty" "code" (h/db*) (h/config) (h/messenger) (h/metrics)
                                    (constantly {:status :executing}) (fn [& _]) {})]
      (is (true? (:error result)))
      (is (= before @(h/db*))))))

(deftest managed-subagent-followup-workers-test
  (h/config! {:env "dev" :hooks {"status" {:type "chatStatusChanged"}}
              :agent {"explorer" {:mode "subagent" :description "Explorer"}}})
  (swap! (h/db*) assoc-in [:chats "parent"] {:model "openai/gpt-5.2"})
  (let [idle (promise) release-idle (promise) polled (promise)
        second-finished (promise) release-worker (promise)
        requests* (atom 0) idle-count* (atom 0)
        context {:db* (h/db*) :config (h/config) :messenger (h/messenger) :metrics (h/metrics)
                 :chat-id "parent" :tool-call-id "workers"
                 :call-state-fn (fn []
                                  (when (= :idle (get-in @(h/db*) [:chats "subagent-workers" :status]))
                                    (deliver polled true))
                                  {:status :executing})}
        handler (spawn-handler)]
    (with-redefs [llm-api/sync-prompt! (constantly nil)
                  config/await-plugins-resolved! (constantly true)
                  f.tools/all-tools (constantly [])
                  hooks/trigger-if-matches!
                  (fn [type data callbacks & _]
                    (when (and (= :subagentPostRequest type) (not (:follow-up-active data)))
                      ((:on-after-action callbacks) {:name "follow" :exit 0 :parsed {"followUp" "continue"}}))
                    (when (and (= :chatStatusChanged type) (= :idle (:status data))
                               (= 1 (swap! idle-count* inc)))
                      (deliver idle true)
                      (is (= true (deref release-idle 10000 ::timeout)))))
                  llm-api/sync-or-async-prompt!
                  (fn [{:keys [on-first-response-received on-message-received]}]
                    (let [n (swap! requests* inc)]
                      (on-first-response-received {:type :text :text "answer"})
                      (on-message-received {:type :text :text (str "answer " n)})
                      (on-message-received {:type :finish})
                      (when (= 2 n)
                        (deliver second-finished true)
                        (is (= true (deref release-worker 10000 ::timeout))))))]
      (let [run (future (handler {"agent" "explorer" "task" "work"} context))]
        (try
          (is (= true (deref idle 10000 ::timeout)))
          (is (= true (deref polled 10000 ::timeout)))
          (is (not (realized? run)))
          (is (= 1 (get-in @(h/db*) [:subagent-runs "subagent-workers" :workers])))
          (doseq [args [{"agent" "explorer" "task" "duplicate"}
                       {"agent" "explorer" "task" "resume" "chat_id" "subagent-workers"}]]
            (is (thrown? clojure.lang.ExceptionInfo (handler args context))))
          (let [before @(h/db*)]
            (is (thrown? clojure.lang.ExceptionInfo
                         (f.chat/prompt {:chat-id "subagent-workers" :message "bypass"}
                                        (h/db*) (h/messenger) (h/config) (h/metrics))))
            (is (= before @(h/db*))))
          (deliver release-idle true)
          (is (= true (deref second-finished 10000 ::timeout)))
          (is (pos? (get-in @(h/db*) [:subagent-runs "subagent-workers" :workers])))
          (is (not (realized? run)))
          (deliver release-worker true)
          (let [result (deref run 10000 ::timeout)]
            (is (map? result))
            (is (string/includes? (tools.util/contents->text (:contents result)) "answer 2")))
          (is (= 0 (get-in @(h/db*) [:subagent-runs "subagent-workers" :workers])))
          (is (nil? (get-in @(h/db*) [:subagent-runs "subagent-workers" :token])))
          (finally
            (deliver release-idle true)
            (deliver release-worker true)
            (when (= ::timeout (deref run 10000 ::timeout)) (future-cancel run))))))))

(deftest spawn-agent-real-max-steps-resume-test
  (h/config! {:env "test"
              :agent {"explorer" {:mode "subagent" :description "Explorer" :maxSteps 1}}})
  (swap! (h/db*) assoc-in [:chats "parent"] {:model "openai/gpt-5.2"})
  (let [requests* (atom [])
        id "subagent-budget"
        context {:db* (h/db*) :config (h/config) :messenger (h/messenger) :metrics (h/metrics)
                 :chat-id "parent" :tool-call-id "budget"
                 :call-state-fn (constantly {:status :executing})}]
    (with-redefs [llm-api/sync-prompt! (constantly nil)
                  config/await-plugins-resolved! (constantly true)
                  f.tools/all-tools (constantly [])
                  llm-api/sync-or-async-prompt!
                  (fn [{:keys [on-first-response-received on-message-received on-tools-called] :as request}]
                    (swap! requests* conj request)
                    (on-first-response-received {:type :text :text "Findings"})
                    (on-message-received {:type :text :text "Findings"})
                    (if (= 1 (count @requests*))
                      (on-tools-called [{:id "lookup" :full-name "eca__lookup" :arguments {}}])
                      (do
                        (is (= 0 (get-in @(h/db*) [:chats id :current-step])))
                        (is (nil? (get-in @(h/db*) [:chats id :max-steps-reached?])))
                        (on-message-received {:type :finish}))))]
      (let [halted ((spawn-handler) {"agent" "explorer" "task" "find"} context)
            history (get-in @(h/db*) [:chats id :messages])]
        (is (true? (:error halted)))
        (is (re-find #"^Subagent chat_id: subagent-budget\n\n## Agent 'explorer' Halted"
                     (tools.util/contents->text (:contents halted))))
        (is (true? (get-in @(h/db*) [:chats id :max-steps-reached?])))
        (is (= 1 (get-in @(h/db*) [:chats id :current-step])))
        (is (not (get-in @(h/db*) [:subagent-runs id :interrupted?])))
        (let [resumed ((spawn-handler) {"agent" "explorer" "task" "finish" "chat_id" id}
                       (assoc context :tool-call-id "continued"))]
          (is (false? (:error resumed)))
          (is (re-find #"^Subagent chat_id: subagent-budget\n\n## Agent 'explorer' Result"
                       (tools.util/contents->text (:contents resumed))))
          (is (= 2 (count @requests*)))
          (is (seq history))
          (is (= history (take (count history) (get-in @(h/db*) [:chats id :messages]))))
          (is (some #(= "assistant" (:role %)) (:past-messages (last @requests*))))
          (is (= 0 (get-in @(h/db*) [:subagent-runs id :workers]))))))))

(deftest spawn-agent-provider-failure-resume-test
  (h/config! {:env "test" :providers {"openai" {:retry {:maxAutoContinues 0}}}
              :agent {"explorer" {:mode "subagent" :description "Explorer"}}})
  (swap! (h/db*) assoc-in [:chats "parent"] {:model "openai/gpt-5.2"})
  (let [requests* (atom [])
        context {:db* (h/db*) :config (h/config) :messenger (h/messenger) :metrics (h/metrics)
                 :chat-id "parent" :tool-call-id "network" :call-state-fn (constantly {:status :executing})}
        handler (spawn-handler)]
    (with-redefs [llm-api/sync-prompt! (constantly nil)
                  config/await-plugins-resolved! (constantly true)
                  f.tools/all-tools (constantly [{:name "lookup" :full-name "eca__lookup"
                                                  :server {:name "eca"} :origin :native}])
                  f.tools/approval (constantly :allow)
                  f.tools/call-tool! (constantly {:contents [{:type :text :text "earlier tool result"}]})
                  llm-api/sync-or-async-prompt!
                  (fn [{:keys [on-first-response-received on-message-received on-prepare-tool-call
                               on-tools-called on-error] :as request}]
                    (swap! requests* conj request)
                    (on-first-response-received {:type :text :text "started"})
                    (case (count @requests*)
                      1 (do (on-prepare-tool-call {:id "lookup" :full-name "eca__lookup" :arguments-text "{}"})
                            (on-tools-called [{:id "lookup" :full-name "eca__lookup" :arguments {}}])
                            (on-message-received {:type :text :text "partial finding"})
                            (on-error {:message "Connection lost" :exception (java.net.ConnectException. "Connection refused")}))
                      (do (is (nil? (get-in @(h/db*) [:chats "subagent-network" :prompt-error])))
                          (on-message-received {:type :text :text "recovered answer"})
                          (on-message-received {:type :finish}))))]
      (let [failed (handler {"agent" "explorer" "task" "find"} context)
            history (get-in @(h/db*) [:chats "subagent-network" :messages])]
        (is (true? (:error failed)))
        (is (string/includes? (tools.util/contents->text (:contents failed)) "returned `chat_id`"))
        (let [result (handler {"agent" "explorer" "task" "continue" "chat_id" "subagent-network"} context)
              past (:past-messages (last @requests*))]
          (is (false? (:error result)))
          (is (string/includes? (tools.util/contents->text (:contents result)) "recovered answer"))
          (is (= 2 (count @requests*)))
          (is (some #(= "earlier tool result" (get-in % [:content :output :contents 0 :text])) past))
          (is (some #(= "partial finding" (get-in % [:content 0 :text])) past))
          (is (= history (take (count history) (get-in @(h/db*) [:chats "subagent-network" :messages])))))))))

(deftest spawn-agent-stopped-tool-resume-test
  (doseq [phase [:dispatch :post-hook :status-hook :cooperative :uninterruptible]]
    (testing (name phase)
      (h/reset-components!)
      (h/config! {:env "dev" :agent {"explorer" {:mode "subagent" :description "Explorer"}}})
      (swap! (h/db*) assoc-in [:chats "parent"] {:model "openai/gpt-5.2"})
      (let [entered (promise) release (promise) stopped (promise) joining (promise) tool-ended (promise)
            requests* (atom []) call-state* (atom {:status :executing}) callback* (atom nil)
            tool-thread* (atom nil)
            id "subagent-cancel"
            context {:db* (h/db*) :config (h/config) :messenger (h/messenger) :metrics (h/metrics)
                     :chat-id "parent" :tool-call-id "cancel" :call-state-fn #(deref call-state*)}
            handler (spawn-handler)
            transition tool-calls/transition-tool-call!
            active tool-calls/get-active-tool-calls
            ;; Ignore cancellation only until the test explicitly releases old work.
            wait! (fn [] (loop []
                           (let [result (try (deref release 30000 ::timeout)
                                             (catch InterruptedException _ ::interrupted))]
                             (if (= ::interrupted result)
                               (recur)
                               (is (= true result))))))
            settled? (fn [] (loop [n 1000]
                             (cond (zero? (get-in @(h/db*) [:subagent-runs id :workers] 0)) true
                                   (zero? n) false
                                   :else (do (Thread/sleep 10) (recur (dec n))))))
            stop! (fn []
                    (reset! call-state* {:status :stopping})
                    (f.chat/prompt-stop {:chat-id id} (h/db*) (h/messenger) (h/config) (h/metrics) {:silent? true})
                    (deliver stopped true))]
        (with-redefs [llm-api/sync-prompt! (constantly nil)
                      config/await-plugins-resolved! (constantly true)
                      f.tools/all-tools (constantly [{:name "lookup" :full-name "eca__lookup"
                                                      :server {:name "eca"} :origin :native}])
                      f.tools/approval (constantly :allow)
                      tool-calls/get-active-tool-calls (fn [db chat-id]
                                                        (when (realized? stopped) (deliver joining true))
                                                        (active db chat-id))
                      tool-calls/transition-tool-call!
                      (fn [db* ctx tool-id event & data]
                        (try
                          (let [result (apply transition db* ctx tool-id event data)]
                            (when (and (= phase :dispatch) (= event :execution-start))
                              (is (= true (deref entered 10000 ::timeout)))
                              (stop!))
                            result)
                          (finally
                            (when (#{:execution-end :stop-attempted} event) (deliver tool-ended true)))))
                      hooks/trigger-if-matches!
                      (fn [type _ callbacks & _]
                        (when (and (= phase :post-hook) (= type :postToolCall))
                          (deliver entered true)
                          (wait!)
                          ((:on-after-action callbacks) {:name "amend" :exit 0
                                                        :parsed {"replacedOutput" "hook result"}})))
                      lifecycle/trigger-chat-status-hook!
                      (fn [_]
                        (when (and (= phase :status-hook)
                                   (= @tool-thread* (Thread/currentThread))
                                   (= :cleanup (get-in @(h/db*) [:chats id :tool-calls "lookup" :status])))
                          (deliver entered true)
                          (wait!)))
                      f.tools/call-tool!
                      (fn [& args]
                        (reset! tool-thread* (Thread/currentThread))
                        (reset! callback* (nth args 9))
                        (when (#{:dispatch :cooperative :uninterruptible} phase)
                          (deliver entered true)
                          (if (= :cooperative phase)
                            (try (deref release 10000 ::timeout) (catch InterruptedException _ nil))
                            (wait!)))
                        {:contents [{:type :text :text "old tool result"}]})
                      llm-api/sync-or-async-prompt!
                      (fn [{:keys [on-first-response-received on-message-received on-prepare-tool-call on-tools-called]
                            :as request}]
                        (swap! requests* conj request)
                        (on-first-response-received {:type :text :text "start"})
                        (if (= 1 (count @requests*))
                          (do (on-prepare-tool-call {:id "lookup" :full-name "eca__lookup" :arguments-text "{}"})
                              (on-tools-called [{:id "lookup" :full-name "eca__lookup" :arguments {}}]))
                          (do (on-message-received {:type :text :text "corrected answer"})
                              (on-message-received {:type :finish}))))]
          (let [run (future (handler {"agent" "explorer" "task" "work"} context))]
            (try
              (is (= true (deref entered 10000 ::timeout)))
              (when-not (= phase :dispatch) (stop!))
              (is (= true (deref stopped 10000 ::timeout)))
              (when-not (= phase :cooperative)
                (is (= (if (#{:post-hook :status-hook} phase) :cleanup :stopping)
                       (:status (@callback*))) "Tool observes live state")
                (when (#{:post-hook :status-hook} phase)
                  ;; A cleanup-state future is not cancelled by prompt-stop; cancel it
                  ;; to exercise the same join path as a tool stopped while executing.
                  (future-cancel (get-in @(h/db*) [:chats id :tool-calls "lookup" :future])))
                (when (= phase :dispatch) (is (= true (deref joining 1000 ::timeout))))
                (is (map? (deref run 10000 ::timeout)))
                (is (= 1 (get-in @(h/db*) [:subagent-runs id :workers])))
                (is (thrown? clojure.lang.ExceptionInfo
                             (handler {"agent" "explorer" "task" "too soon" "chat_id" id} context))))
              (deliver release true)
              (is (map? (deref run 10000 ::timeout)))
              (is (settled?))
              (reset! call-state* {:status :executing})
              (let [history (get-in @(h/db*) [:chats id :messages])
                    resumed (handler {"agent" "explorer" "task" "correct course" "chat_id" id} context)
                    past (:past-messages (last @requests*))]
                (is (false? (:error resumed)))
                (is (string/includes? (tools.util/contents->text (:contents resumed)) "corrected answer"))
                (is (= 2 (count @requests*)) "No provider continuation after stop")
                (is (every? (set (map :role past)) ["tool_call" "tool_call_output"]))
                (is (some #(= (if (= phase :post-hook) "hook result" "old tool result")
                              (get-in % [:content :output :contents 0 :text])) past))
                (is (= history (take (count history) (get-in @(h/db*) [:chats id :messages])))))
              (finally
                (deliver release true)
                (is (= true (deref tool-ended 10000 ::timeout)))
                (is (not= ::timeout (deref run 10000 ::timeout)))
                (is (settled?))))))))))

(deftest spawn-agent-real-chat-prompt-test
  ;; Regression: chat/prompt must accept the server-managed "subagent-..." ID.
  ;; Exercise the real chat layer for both fresh and resumed delegation.
  (testing "spawn handler drives real chat/prompt to success"
    (h/reset-components!)
    (h/config! {:env "test"
                :agent {"explorer" {:mode "subagent"
                                    :description "Explores codebases"
                                    :systemPrompt "You are an explorer."}}})
    (swap! (h/db*) update :models
           (fn [models] (merge {"openai/gpt-5.2" {:tools true}} (or models {}))))
    (swap! (h/db*) assoc-in [:chats "parent-1"]
           {:id "parent-1" :model "openai/gpt-5.2"})
    (let [requests* (atom [])
          api-mock (fn [{:keys [on-first-response-received on-message-received
                               on-prepare-tool-call on-tools-called] :as request}]
                     (swap! requests* conj request)
                     (on-first-response-received {:type :text :text "Found it"})
                     (when (= 1 (count @requests*))
                       (on-prepare-tool-call {:id "lookup" :full-name "eca__lookup" :arguments-text "{}"})
                       (on-tools-called [{:id "lookup" :full-name "eca__lookup" :arguments {}}]))
                     (on-message-received {:type :text :text "Found it"})
                     (on-message-received {:type :finish}))]
      (with-redefs [llm-api/sync-or-async-prompt! api-mock
                    llm-api/sync-prompt! (constantly nil)
                    f.tools/all-tools (constantly [{:name "lookup" :full-name "eca__lookup"
                                                    :server {:name "eca"} :origin :native
                                                    :parameters {:type "object" :properties {}}}])
                    f.tools/call-tool! (constantly {:error false :contents [{:type :text :text "file found"}]})
                    f.tools/approval (constantly :allow)
                    config/await-plugins-resolved! (constantly true)]
        (let [handler (get-in (f.tools.agent/definitions (h/config) (h/db)) ["spawn_agent" :handler])
              ;; Bound the wait so a stalled prompt fails the test; allow headroom
              ;; for slower CI runners.
              result-fut (future
                           (handler
                            {"agent" "explorer" "task" "find files" "activity" "exploring"}
                            {:db* (h/db*)
                             :config (h/config)
                             :messenger (h/messenger)
                             :metrics (h/metrics)
                             :chat-id "parent-1"
                             :tool-call-id "tc-1"
                             :call-state-fn (constantly {:status :executing})}))
              result (deref result-fut 30000 ::timeout)
              timeout-details (when (identical? ::timeout result)
                                (pr-str {:parent-chat (get-in @(h/db*) [:chats "parent-1"])
                                         :subagent-chat (get-in @(h/db*) [:chats "subagent-tc-1"])
                                         :messages (h/messages)}))]
          (when (identical? ::timeout result)
            (future-cancel result-fut))
          (testing "spawn handler completes within the timeout"
            (is (not (identical? ::timeout result))
                (str "spawn handler did not complete in 30s. " timeout-details)))
          (when (map? result)
            (testing "spawn handler returns success"
              (is (match? {:error false
                           :contents [{:type :text
                                       :text #"^Subagent chat_id: subagent-tc-1\n\n## Agent 'explorer' Result"}]}
                          result)))
            (testing "resume retains the real transcript and original selections"
              (let [child (get-in @(h/db*) [:chats "subagent-tc-1"])
                    history (:messages child)
                    cache (:prompt-cache child)]
                (is (every? (set (map :role history)) ["user" "assistant" "tool_call" "tool_call_output"]))
                (swap! (h/db*) assoc-in [:chats "parent-1" :model] "other/model")
                (let [resumed (handler {"agent" "explorer" "task" "Explain that finding" "chat_id" "subagent-tc-1"}
                                       {:db* (h/db*) :config (h/config) :messenger (h/messenger) :metrics (h/metrics)
                                        :chat-id "parent-1" :tool-call-id "tc-2"
                                        :call-state-fn (constantly {:status :executing})})]
                  (is (false? (:error resumed)))
                  (is (= history (take (count history) (get-in @(h/db*) [:chats "subagent-tc-1" :messages]))))
                  (is (every? (set (map :role (:past-messages (last @requests*))))
                              ["user" "assistant" "tool_call" "tool_call_output"]))
                  (is (some #(= "file found" (get-in % [:content :output :contents 0 :text]))
                            (:past-messages (last @requests*))))
                  (is (= "Explain that finding"
                         (get-in (last @requests*) [:user-messages 0 :content 0 :text])))
                  (is (= "gpt-5.2" (:model (last @requests*))))
                  (is (= cache (get-in @(h/db*) [:chats "subagent-tc-1" :prompt-cache]))))))
            (testing "subagent chat reaches :idle through real chat/prompt"
              (is (= :idle (get-in @(h/db*) [:chats "subagent-tc-1" :status]))))
            (testing "subagent chat carries the parent-chat-id"
              (is (= "parent-1" (get-in @(h/db*) [:chats "subagent-tc-1" :parent-chat-id]))))
            (testing "real chat/prompt streamed assistant content under the subagent chat-id with parent-chat-id"
              (is (some (fn [m] (and (= "subagent-tc-1" (:chat-id m))
                                     (= "parent-1" (:parent-chat-id m))
                                     (= :assistant (:role m))
                                     (= {:type :text :text "Found it"} (:content m))))
                        (:chat-content-received (h/messages)))))))))))
