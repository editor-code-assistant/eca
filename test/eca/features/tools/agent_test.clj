(ns eca.features.tools.agent-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.tools.agent :as f.tools.agent]
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
           "code" {:mode "primary"
                   :description "Code agent"}}
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
                        (fn [_params _db* _messenger _metrics]
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
          (testing "preserves subagent chat for resume replay"
            (is (some? (get-in @db* [:chats subagent-chat-id])))))))))

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
        (is (thrown? Exception
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
                                "model" {:type "string"
                                         :enum ["anthropic/claude-opus-4-6"
                                                "anthropic/claude-sonnet-4-6"
                                                "openai/gpt-4.1"]}
                                "variant" {:type "string"
                                           :enum ["high" "low" "max" "medium"]}}
                   :required ["agent" "task" "activity"]}
                  (:parameters tool)))))

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
      (is (not (re-find #"- code:" desc))
          "primary agents should not appear in subagent list")))

  (testing "summary-fn formats agent name with activity"
    (let [summary-fn (:summary-fn (get (f.tools.agent/definitions test-config test-db) "spawn_agent"))]
      (is (= "explorer: searching files"
             (summary-fn {:args {"agent" "explorer" "activity" "searching files"}})))
      (is (= "Spawning agent"
             (summary-fn {:args {}}))))))
