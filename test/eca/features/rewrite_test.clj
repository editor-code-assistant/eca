(ns eca.features.rewrite-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.login :as f.login]
   [eca.features.prompt :as f.prompt]
   [eca.features.rewrite :as f.rewrite]
   [eca.llm-api :as llm-api]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(deftest prompt-basic-flow-test
  (testing "Basic rewrite flow: started -> reasoning -> text -> finish"
    (h/reset-components!)
    (let [api-opts* (atom nil)
          resp (with-redefs [llm-api/sync-or-async-prompt!
                             (fn [{:keys [on-first-response-received on-reason on-message-received] :as opts}]
                               (reset! api-opts* opts)
                               ;; Emit the events in the expected order
                               (on-first-response-received {:type :text})
                               (on-reason {:status :started})
                               (on-reason {:status :thinking :text "..."})
                               (on-message-received {:type :text :text "Hello"})
                               (on-message-received {:type :text :text ", world!"})
                               (on-message-received {:type :finish}))
                             f.prompt/build-rewrite-instructions (constantly "INSTR")
                             f.login/maybe-renew-auth-token! (fn [& _] nil)
                             llm-api/refine-file-context (constantly "FULL-CONTENT")
                             llm-api/default-model (constantly "openai/gpt-4.1")]
                 (h/config! {:env "test"})
                 (f.rewrite/prompt {:id "rw-1"
                                    :prompt "Please improve the following"
                                    :text "Original text"
                                    :range {:start {:line 1 :character 0}
                                            :end {:line 1 :character 14}}}
                                   (h/db*) (h/config) (h/messenger) (h/metrics)))]
      (is (= {:status "prompting" :model "openai/gpt-4.1"} resp))
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
          resp
          (with-redefs [llm-api/sync-or-async-prompt!
                        (fn [opts]
                          (reset! api-opts* opts)
                          ((:on-first-response-received opts) {:type :text})
                          ((:on-message-received opts) {:type :finish}))
                        f.prompt/build-rewrite-instructions
                        (fn [text path full-text range _all-tools cfg _db]
                          (reset! captured-instr-args* {:text text :path path :full-text full-text :range range :config cfg})
                          "MY-INSTR")
                        f.login/maybe-renew-auth-token! (fn [& _] nil)
                        llm-api/refine-file-context (fn [path _] (str "CTX:" path))]
            (h/config! {:env "test" :rewrite {:model "openai/gpt-test"}})
            (f.rewrite/prompt {:id "rw-2"
                               :prompt "Do it"
                               :text "T"
                               :path (h/file-path "/tmp/file.txt")
                               :range {:start {:line 10 :character 2}
                                       :end {:line 12 :character 5}}}
                              (h/db*) (h/config) (h/messenger) (h/metrics)))]
      (is (= {:status "prompting" :model "openai/gpt-test"} resp))
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

(deftest prompt-strips-markdown-fences-test
  (testing "Sends replace content when LLM response contains markdown fences"
    (h/reset-components!)
    (with-redefs [llm-api/sync-or-async-prompt!
                  (fn [{:keys [on-first-response-received on-message-received]}]
                    (on-first-response-received {:type :text})
                    (on-message-received {:type :text :text "```clojure\n"})
                    (on-message-received {:type :text :text "(+ 1 2)"})
                    (on-message-received {:type :text :text "\n```"})
                    (on-message-received {:type :finish}))
                  f.prompt/build-rewrite-instructions (constantly "INSTR")
                  f.login/maybe-renew-auth-token! (fn [& _] nil)
                  llm-api/default-model (constantly "openai/gpt-4.1")]
      (h/config! {:env "test"})
      (f.rewrite/prompt {:id "rw-fence"
                         :prompt "Rewrite this"
                         :text "(+ 1 1)"
                         :range {:start {:line 1 :character 0}
                                 :end {:line 1 :character 7}}}
                        (h/db*) (h/config) (h/messenger) (h/metrics))
      (let [msgs (get (h/messages) :rewrite-content-received)]
        (is (= 6 (count msgs)))
        (is (match? [{:content {:type :started}}
                     {:content {:type :text :text "```clojure\n"}}
                     {:content {:type :text :text "(+ 1 2)"}}
                     {:content {:type :text :text "\n```"}}
                     {:content {:type :replace :text "(+ 1 2)"}}
                     {:content {:type :finished :total-time-ms number?}}]
                    msgs))))))

(deftest prompt-no-replace-when-no-fences-test
  (testing "Does not send replace content when LLM response has no markdown fences"
    (h/reset-components!)
    (with-redefs [llm-api/sync-or-async-prompt!
                  (fn [{:keys [on-first-response-received on-message-received]}]
                    (on-first-response-received {:type :text})
                    (on-message-received {:type :text :text "(+ 1 2)"})
                    (on-message-received {:type :finish}))
                  f.prompt/build-rewrite-instructions (constantly "INSTR")
                  f.login/maybe-renew-auth-token! (fn [& _] nil)
                  llm-api/default-model (constantly "openai/gpt-4.1")]
      (h/config! {:env "test"})
      (f.rewrite/prompt {:id "rw-no-fence"
                         :prompt "Rewrite this"
                         :text "(+ 1 1)"
                         :range {:start {:line 1 :character 0}
                                 :end {:line 1 :character 7}}}
                        (h/db*) (h/config) (h/messenger) (h/metrics))
      (let [msgs (get (h/messages) :rewrite-content-received)]
        (is (= 3 (count msgs)))
        (is (match? [{:content {:type :started}}
                     {:content {:type :text :text "(+ 1 2)"}}
                     {:content {:type :finished :total-time-ms number?}}]
                    msgs))))))

(deftest prompt-windows-large-file-context-test
  (testing "When target file exceeds rewrite.fullFileMaxLines, inlines a window centered on the selection with a truncation marker"
    (h/reset-components!)
    (let [total 5000
          max-lines 2000
          file-content (string/join "\n" (map #(str "line-" (inc %)) (range total)))
          captured-args* (atom nil)]
      (with-redefs [llm-api/sync-or-async-prompt!
                    (fn [opts]
                      ((:on-first-response-received opts) {:type :text})
                      ((:on-message-received opts) {:type :finish}))
                    f.prompt/build-rewrite-instructions
                    (fn [_text _path full-text _range _all-tools _cfg _db]
                      (reset! captured-args* {:full-text full-text})
                      "INSTR")
                    f.login/maybe-renew-auth-token! (fn [& _] nil)
                    llm-api/refine-file-context (fn [_ _] file-content)
                    llm-api/default-model (constantly "openai/gpt-4.1")]
        (h/config! {:env "test" :rewrite {:fullFileMaxLines max-lines}})
        (f.rewrite/prompt {:id "rw-window"
                           :prompt "Improve"
                           :text "t"
                           :path "/tmp/big.clj"
                           :range {:start {:line 2500 :character 0}
                                   :end {:line 2500 :character 1}}}
                          (h/db*) (h/config) (h/messenger) (h/metrics)))
      (let [full-text (:full-text @captured-args*)
            lines (string/split-lines full-text)]
        (is (string? full-text))
        ;; marker line + max-lines content lines
        (is (= (inc max-lines) (count lines)))
        ;; selection at 2500, half = 1000 -> window [1500..3499]
        (is (= ";; [File truncated: showing lines 1500-3499 of 5000]" (first lines)))
        (is (= "line-1500" (second lines)))
        (is (= "line-3499" (last lines)))))))

(deftest prompt-preserves-small-file-context-test
  (testing "When target file fits rewrite.fullFileMaxLines, inlines the full file unchanged"
    (h/reset-components!)
    (let [total 500
          max-lines 2000
          file-content (string/join "\n" (map #(str "line-" (inc %)) (range total)))
          captured-args* (atom nil)]
      (with-redefs [llm-api/sync-or-async-prompt!
                    (fn [opts]
                      ((:on-first-response-received opts) {:type :text})
                      ((:on-message-received opts) {:type :finish}))
                    f.prompt/build-rewrite-instructions
                    (fn [_text _path full-text _range _all-tools _cfg _db]
                      (reset! captured-args* {:full-text full-text})
                      "INSTR")
                    f.login/maybe-renew-auth-token! (fn [& _] nil)
                    llm-api/refine-file-context (fn [_ _] file-content)
                    llm-api/default-model (constantly "openai/gpt-4.1")]
        (h/config! {:env "test" :rewrite {:fullFileMaxLines max-lines}})
        (f.rewrite/prompt {:id "rw-small"
                           :prompt "Improve"
                           :text "t"
                           :path "/tmp/small.clj"
                           :range {:start {:line 50 :character 0}
                                   :end {:line 50 :character 1}}}
                          (h/db*) (h/config) (h/messenger) (h/metrics)))
      (let [full-text (:full-text @captured-args*)]
        (is (= file-content full-text))
        (is (not (string/includes? full-text "[File truncated")))))))

(deftest prompt-default-model-and-auth-renew-test
  (testing "Falls back to default model when rewrite.model not set and calls auth renew with provider"
    (h/reset-components!)
    ;; Prepare DB auth and models
    (swap! (h/db*) assoc-in [:auth "google"] {:token "abc"})
    (swap! (h/db*) assoc :models {"google/gemini-dev" {:reason? true}})
    (let [renew-called* (atom nil)
          api-opts* (atom nil)
          resp
          (with-redefs [llm-api/sync-or-async-prompt!
                        (fn [opts]
                          (reset! api-opts* opts)
                          ((:on-first-response-received opts) {:type :text})
                          ((:on-message-received opts) {:type :finish}))
                        f.prompt/build-rewrite-instructions (constantly "INSTR")
                        f.login/maybe-renew-auth-token!
                        (fn [{:keys [provider]} _ctx]
                          (reset! renew-called* provider))
                        llm-api/default-model (constantly "google/gemini-dev")]
            (h/config! {:env "test"})
            (f.rewrite/prompt {:id "rw-3"
                               :prompt "X"
                               :text "Y"}
                              (h/db*) (h/config) (h/messenger) (h/metrics)))]
      (is (= {:status "prompting" :model "google/gemini-dev"} resp))
      (is (= "google" @renew-called*))
      (is (= "google" (:provider @api-opts*)))
      (is (= "gemini-dev" (:model @api-opts*)))
      (is (= {:token "abc"} (:provider-auth @api-opts*))))))
