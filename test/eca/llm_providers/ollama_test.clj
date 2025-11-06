(ns eca.llm-providers.ollama-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.llm-providers.ollama :as llm-providers.ollama]
   [matcher-combinators.test :refer [match?]]))

(deftest ->normalize-messages-test
  (testing "no previous history"
    (is (match?
         []
         (#'llm-providers.ollama/normalize-messages []))))
  (testing "With basic text history"
    (is (match?
         [{:role "user" :content "Count with me: 1"}
          {:role "assistant" :content "2"}]
         (#'llm-providers.ollama/normalize-messages
          [{:role "user" :content "Count with me: 1"}
           {:role "assistant" :content "2"}]))))
  (testing "With tool_call history"
    (is (match?
         [{:role "user" :content "List the files you are allowed"}
          {:role "assistant" :content "Ok!"}
          {:role "assistant" :tool-calls [{:type "function"
                                           :function {:name "eca__list_allowed_directories"
                                                      :arguments {}}}]}
          {:role "tool" :content "Allowed directories: /foo/bar\n"}
          {:role "assistant" :content "I see /foo/bar"}]
         (#'llm-providers.ollama/normalize-messages
          [{:role "user" :content "List the files you are allowed"}
           {:role "assistant" :content "Ok!"}
           {:role "tool_call" :content {:id "call-1" :full-name "eca__list_allowed_directories" :arguments {}}}
           {:role "tool_call_output" :content {:id "call-1"
                                               :full-name "eca__list_allowed_directories"
                                               :arguments {}
                                               :output {:contents [{:type :text
                                                                    :error false
                                                                    :text "Allowed directories: /foo/bar"}]}}}
           {:role "assistant" :content "I see /foo/bar"}])))))
