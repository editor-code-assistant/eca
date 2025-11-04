(ns eca.features.rewrite-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.login :as f.login]
   [eca.features.prompt :as f.prompt]
   [eca.features.rewrite :as f.rewrite]
   [eca.llm-api :as llm-api]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(defn ^:private rewrite!
  "Helper to invoke rewrite/prompt with configurable mocks.
   Returns {:resp <function-return> :finished? <promise>}"
  [params {:keys [api-mock build-instructions-mock refine-file-context-mock
                  login-renew-mock default-model-mock]
           :or {build-instructions-mock (constantly "INSTR")
                refine-file-context-mock (constantly "FULL-CONTENT")
                login-renew-mock (fn [& _] nil)
                default-model-mock (constantly "anthropic/claude-dev")}}]
  (let [finished? (promise)
        resp (with-redefs [llm-api/sync-or-async-prompt!
                           (fn [opts]
                             (when api-mock
                               (api-mock (assoc opts :finished? finished?)))
                             nil)
                           f.prompt/build-rewrite-instructions build-instructions-mock
                           f.login/maybe-renew-auth-token! login-renew-mock
                           llm-api/refine-file-context refine-file-context-mock
                           llm-api/default-model default-model-mock]
               ;; ensure test env config is set
               (h/config! {:env "test"})
               (let [r (f.rewrite/prompt params (h/db*) (h/config) (h/messenger) (h/metrics))]
                 ;; Keep redefs in scope until async code runs
                 (deref finished? 2000 nil)
                 r))]
    {:resp resp :finished? finished?}))

(deftest prompt-basic-flow-test
  (testing "Basic rewrite flow: started -> reasoning -> text -> finish"
    (h/reset-components!)
    (let [api-opts* (atom nil)
          {:keys [resp finished?]}
          (rewrite!
           {:id "rw-1"
            :prompt "Please improve the following"
            :text "Original text"
            :range {:start {:line 1 :character 0}
                    :end {:line 1 :character 14}}}
           {:api-mock
            (fn [{:keys [on-first-response-received on-reason on-message-received] :as opts}]
              (reset! api-opts* opts)
              ;; Emit the events in the expected order
              (on-first-response-received {:type :text})
              (on-reason {:status :started})
              (on-reason {:status :thinking :text "..."})
              (on-message-received {:type :text :text "Hello"})
              (on-message-received {:type :text :text ", world!"})
              (on-message-received {:type :finish})
              (deliver (:finished? opts) true))
            :build-instructions-mock (constantly "INSTR")})]
      ;; Wait for async future to publish the finish event
      (is (true? (deref finished? 2000 false)) "Timed out waiting for finish event")
      ;; Function return value
      (is (= {:status "prompting" :model "anthropic/claude-dev"} resp))
      ;; Messenger events captured by TestMessenger
      (let [msgs (get (h/messages) :rewrite-content-received)]
        ;; Ensure order and content types
        (is (= 5 (count msgs)))
        (is (match? [{:rewrite-id "rw-1" :content {:type :started}}
                     {:rewrite-id "rw-1" :content {:type :reasoning}}
                     {:rewrite-id "rw-1" :content {:type :text :text "Hello"}}
                     {:rewrite-id "rw-1" :content {:type :text :text ", world!"}}
                     {:rewrite-id "rw-1" :content {:type :finished :total-time-ms number?}}]
                    msgs))))))

(deftest prompt-instructions-and-contexts-test
  (testing "Passes instructions from build-rewrite-instructions and uses file context when path provided"
    (h/reset-components!)
    ;; Prepare DB model capabilities for assertion
    (swap! (h/db*) assoc :models {"openai/gpt-test" {:max-output-tokens 1024}})
    (let [captured-instr-args* (atom nil)
          api-opts* (atom nil)
          finished? (promise)
          resp
          (with-redefs [llm-api/sync-or-async-prompt!
                        (fn [opts]
                          (reset! api-opts* opts)
                          ((:on-first-response-received opts) {:type :text})
                          ((:on-message-received opts) {:type :finish})
                          (deliver finished? true))
                        f.prompt/build-rewrite-instructions
                        (fn [text path full-text range cfg]
                          (reset! captured-instr-args* {:text text :path path :full-text full-text :range range :config cfg})
                          "MY-INSTR")
                        f.login/maybe-renew-auth-token! (fn [& _] nil)
                        llm-api/refine-file-context (fn [path _] (str "CTX:" path))]
            (h/config! {:env "test" :rewrite {:model "openai/gpt-test"}})
            (let [r (f.rewrite/prompt {:id "rw-2"
                                       :prompt "Do it"
                                       :text "T"
                                       :path (h/file-path "/tmp/file.txt")
                                       :range {:start {:line 10 :character 2}
                                               :end {:line 12 :character 5}}}
                                      (h/db*) (h/config) (h/messenger) (h/metrics))]
              (deref finished? 2000 nil)
              r))]
      (is (= {:status "prompting" :model "openai/gpt-test"} resp))
      (is (true? (deref finished? 2000 false)))
      ;; build-rewrite-instructions received expected args
      (is (match? {:text "T"
                   :path (h/file-path "/tmp/file.txt")
                   :full-text (str "CTX:" (h/file-path "/tmp/file.txt"))
                   :range {:start {:line 10 :character 2}
                           :end {:line 12 :character 5}}}
                  @captured-instr-args*))
      ;; llm-api called with provider/model split and our instructions
      (is (= "openai" (:provider @api-opts*)))
      (is (= "gpt-test" (:model @api-opts*)))
      (is (= "MY-INSTR" (:instructions @api-opts*)))
      (is (= [{:role "user" :content [{:type :text :text "Do it"}]}]
             (:user-messages @api-opts*)))
      (is (= {:max-output-tokens 1024}
             (:model-capabilities @api-opts*))))))

(deftest prompt-default-model-and-auth-renew-test
  (testing "Falls back to default model when rewrite.model not set and calls auth renew with provider"
    (h/reset-components!)
    ;; Prepare DB auth and models
    (swap! (h/db*) assoc-in [:auth "google"] {:token "abc"})
    (swap! (h/db*) assoc :models {"google/gemini-dev" {:reason? true}})
    (let [renew-called* (atom nil)
          api-opts* (atom nil)
          finished? (promise)
          resp
          (with-redefs [llm-api/sync-or-async-prompt!
                        (fn [opts]
                          (reset! api-opts* opts)
                          ((:on-first-response-received opts) {:type :text})
                          ((:on-message-received opts) {:type :finish})
                          (deliver finished? true))
                        f.prompt/build-rewrite-instructions (constantly "INSTR")
                        f.login/maybe-renew-auth-token!
                        (fn [{:keys [provider]} _ctx]
                          (reset! renew-called* provider))
                        llm-api/default-model (constantly "google/gemini-dev")]
            (h/config! {:env "test"})
            (let [r (f.rewrite/prompt {:id "rw-3"
                                       :prompt "X"
                                       :text "Y"}
                                      (h/db*) (h/config) (h/messenger) (h/metrics))]
              (deref finished? 2000 nil)
              r))]
      (is (= {:status "prompting" :model "google/gemini-dev"} resp))
      (is (true? (deref finished? 2000 false)))
      (is (= "google" @renew-called*))
      (is (= "google" (:provider @api-opts*)))
      (is (= "gemini-dev" (:model @api-opts*)))
      (is (= {:token "abc"} (:provider-auth @api-opts*))))))
