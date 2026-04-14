(ns eca.features.tools.ask-user-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.tools.ask-user :as f.tools.ask-user]
   [eca.messenger :as messenger]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(defn- call-ask-user [arguments & [{:keys [messenger config chat-id tool-call-id]
                                     :or {tool-call-id "test-tool-call-id"}}]]
  ((get-in f.tools.ask-user/definitions ["ask_user" :handler])
   arguments
   {:messenger (or messenger (h/messenger))
    :config (or config (h/config))
    :chat-id (or chat-id "test-chat-id")
    :tool-call-id tool-call-id}))

(deftest ask-user-option-selected-test
  (testing "User selects a predefined option"
    (reset! (:ask-question-response* (h/messenger))
            {:answer "React" :cancelled false})
    (is (match?
         {:error false
          :contents [{:type :text
                      :text "User answered: React"}]}
         (call-ask-user {"question" "Which framework?"
                         "options" [{"label" "React" "description" "Component-based"}
                                    {"label" "Vue" "description" "Progressive"}]})))))

(deftest ask-user-freeform-answer-test
  (testing "User types a freeform answer"
    (reset! (:ask-question-response* (h/messenger))
            {:answer "I'd prefer Svelte actually" :cancelled false})
    (is (match?
         {:error false
          :contents [{:type :text
                      :text "User answered: I'd prefer Svelte actually"}]}
         (call-ask-user {"question" "Which framework?"
                         "options" [{"label" "React"}]})))))

(deftest ask-user-open-ended-test
  (testing "Open-ended question with no options"
    (reset! (:ask-question-response* (h/messenger))
            {:answer "A todo list app" :cancelled false})
    (is (match?
         {:error false
          :contents [{:type :text
                      :text "User answered: A todo list app"}]}
         (call-ask-user {"question" "What kind of app would you like to build?"})))))

(deftest ask-user-cancelled-test
  (testing "User cancels the question"
    (reset! (:ask-question-response* (h/messenger))
            {:answer nil :cancelled true})
    (is (match?
         {:error true
          :contents [{:type :text
                      :text "User cancelled the question."}]}
         (call-ask-user {"question" "Which framework?"})))))

(deftest ask-user-timeout-test
  (testing "Timeout waiting for user response"
    (reset! (:ask-question-response* (h/messenger)) :block)
    (is (match?
         {:error true
          :contents [{:type :text
                      :text "Timeout waiting for user response."}]}
         (call-ask-user {"question" "Which framework?"}
                        {:config {:askQuestionTimeoutSeconds 0}})))))

(deftest ask-user-missing-question-test
  (testing "Missing question parameter"
    (is (match?
         {:error true
          :contents [{:type :text
                      :text #"INVALID_ARGS.*question.*required"}]}
         (call-ask-user {}))))
  (testing "Blank question parameter"
    (is (match?
         {:error true
          :contents [{:type :text
                      :text #"INVALID_ARGS.*question.*required"}]}
         (call-ask-user {"question" "  "})))))

(deftest ask-user-error-test
  (testing "Exception during ask-question"
    (reset! (:ask-question-response* (h/messenger))
            (Exception. "connection lost"))
    (is (match?
         {:error true
          :contents [{:type :text
                      :text "Error asking user question."}]}
         (call-ask-user {"question" "Which framework?"})))))

(deftest ask-user-enabled-fn-test
  (testing "Tool is enabled when client capability is set"
    (let [enabled-fn (get-in f.tools.ask-user/definitions ["ask_user" :enabled-fn])]
      (is (true? (enabled-fn {:db {:client-capabilities {:code-assistant {:chat-capabilities {:ask-question true}}}}})))
      (is (nil? (enabled-fn {:db {:client-capabilities {:code-assistant {:chat-capabilities {}}}}})))
      (is (nil? (enabled-fn {:db {:client-capabilities {}}}))))))

(deftest ask-user-passes-tool-call-id-test
  (testing "toolCallId is included in params sent to messenger"
    (let [captured-params (atom nil)]
      (call-ask-user {"question" "Proceed?"}
                     {:messenger (reify messenger/IMessenger
                                   (chat-content-received [_ _data])
                                   (ask-question [_ params]
                                     (reset! captured-params params)
                                     (future {:answer "yes" :cancelled false})))
                      :tool-call-id "tc-42"})
      (is (= "tc-42" (:toolCallId @captured-params)))))
  (testing "toolCallId is absent when tool-call-id is nil"
    (let [captured-params (atom nil)]
      (call-ask-user {"question" "Proceed?"}
                     {:messenger (reify messenger/IMessenger
                                   (chat-content-received [_ _data])
                                   (ask-question [_ params]
                                     (reset! captured-params params)
                                     (future {:answer "yes" :cancelled false})))
                      :tool-call-id nil})
      (is (nil? (:toolCallId @captured-params))))))

(deftest ask-user-allow-freeform-test
  (testing "allowFreeform defaults to true"
    (let [captured-params (atom nil)]
      (call-ask-user {"question" "Pick one"}
                     {:messenger (reify messenger/IMessenger
                                   (chat-content-received [_ _data])
                                   (ask-question [_ params]
                                     (reset! captured-params params)
                                     (future {:answer "A" :cancelled false})))})
      (is (true? (:allowFreeform @captured-params)))))
  (testing "allowFreeform false is passed through"
    (let [captured-params (atom nil)]
      (call-ask-user {"question" "Pick one" "allowFreeform" false}
                     {:messenger (reify messenger/IMessenger
                                   (chat-content-received [_ _data])
                                   (ask-question [_ params]
                                     (reset! captured-params params)
                                     (future {:answer "A" :cancelled false})))})
      (is (false? (:allowFreeform @captured-params))))))

(deftest ask-user-summary-fn-test
  (testing "Summary shows Q: prefix with question"
    (let [summary-fn (get-in f.tools.ask-user/definitions ["ask_user" :summary-fn])]
      (is (= "Q: Short question?" (summary-fn {:args {"question" "Short question?"}})))
      (is (= "Q: This is a very long question that exceeds th..."
             (summary-fn {:args {"question" "This is a very long question that exceeds the fifty character limit"}})))))
  (testing "Summary shows preparing when question not yet available"
    (let [summary-fn (get-in f.tools.ask-user/definitions ["ask_user" :summary-fn])]
      (is (= "Preparing question" (summary-fn {:args {}}))))))
