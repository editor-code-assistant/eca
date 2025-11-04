(ns integration.rewrite.openai-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [integration.eca :as eca]
   [integration.fixture :as fixture]
   [integration.helper :refer [match-rewrite-content]]
   [llm-mock.mocks :as llm.mocks]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(eca/clean-after-test)

(deftest basic-rewrite-openai
  (eca/start-process!)

  ;; initialize server with rewrite model set to openai to hit the openai mock
  (let [init-opts (assoc-in fixture/default-init-options [:rewrite :model] "openai/gpt-4.1")]
    (eca/request! (fixture/initialize-request {:initializationOptions init-opts
                                               :capabilities {:codeAssistant {:chat {}}}})))
  (eca/notify! (fixture/initialized-notification))

  (testing "Rewrite streams text and finishes, and body contains user prompt input"
    (llm.mocks/set-case! :simple-text-0)
    (let [resp (eca/request! (fixture/rewrite-prompt-request
                              {:id "rw-1"
                               :prompt "Please rewrite"
                               :text "Original text"
                               :range {:start {:line 1 :character 0}
                                       :end {:line 1 :character 13}}}))]
      (is (match?
           {:model "openai/gpt-4.1"
            :status "prompting"}
           resp))

      ;; rewrite/contentReceived notifications
      (match-rewrite-content "rw-1" {:type "started"})
      (match-rewrite-content "rw-1" {:type "text" :text "Knock"})
      (match-rewrite-content "rw-1" {:type "text" :text " knock!"})
      (match-rewrite-content "rw-1" {:type "finished" :totalTimeMs (m/pred number?)})

      ;; Verify request body sent to mock: user input contains the prompt we sent
      (is (match?
           {:input [{:role "user"
                     :content [{:type "input_text" :text "Please rewrite"}]}]
            :instructions (m/pred string?)}
           (llm.mocks/get-req-body :simple-text-0))))))
