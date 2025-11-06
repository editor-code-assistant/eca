(ns eca.llm-providers.openai-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.llm-providers.openai :as llm-providers.openai]
   [matcher-combinators.test :refer [match?]]))

(deftest ->normalize-messages-test
  (testing "no previous history"
    (is (match?
         []
         (#'llm-providers.openai/normalize-messages [] true))))

  (testing "With basic text history"
    (is (match?
         [{:role "user" :content [{:type "input_text" :text "Count with me: 1"}]}
          {:role "assistant" :content "2"}]
         (#'llm-providers.openai/normalize-messages
          [{:role "user" :content [{:type :text :text "Count with me: 1"}]}
           {:role "assistant" :content "2"}]
          true))))
  (testing "With tool_call history"
    (is (match?
         [{:role "user" :content [{:type "input_text" :text "List the files you are allowed"}]}
          {:role "assistant" :content [{:type "output_text" :text "Ok!"}]}
          {:type "function_call"
           :call_id "call-1"
           :name "eca__list_allowed_directories"
           :arguments "{}"}
          {:type "function_call_output"
           :call_id "call-1"
           :output "Allowed directories: /foo/bar\n"}
          {:role "assistant" :content [{:type "output_text" :text "I see /foo/bar"}]}]
         (#'llm-providers.openai/normalize-messages
          [{:role "user" :content [{:type :text :text "List the files you are allowed"}]}
           {:role "assistant" :content [{:type :text :text "Ok!"}]}
           {:role "tool_call" :content {:id "call-1" :full-name "eca__list_allowed_directories" :arguments {}}}
           {:role "tool_call_output" :content {:id "call-1"
                                               :full-name "eca__list_allowed_directories"
                                               :arguments {}
                                               :output {:contents [{:type :text
                                                                    :error false
                                                                    :text "Allowed directories: /foo/bar"}]}}}
           {:role "assistant" :content [{:type :text :text "I see /foo/bar"}]}]
          true)))))
