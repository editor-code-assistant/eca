(ns eca.features.completion-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.completion :as f.completion]
   [eca.test-helper :as h]))

(deftest normalize-code-result-test
  (testing "triple backticks with language label"
    (is (= "(+ 1 2)\n(foo)"
           (#'f.completion/normalize-code-result "```clojure\n(+ 1 2)\n(foo)\n```"))))

  (testing "triple backticks without language label"
    (is (= "(+ 1 2)\n(foo)"
           (#'f.completion/normalize-code-result "```\n(+ 1 2)\n(foo)\n```"))))

  (testing "single backticks wrapping the whole content (even multiline)"
    (is (= "(+ 1 2)\n(foo)"
           (#'f.completion/normalize-code-result "`(+ 1 2)\n(foo)`"))))

  (testing "no fences: return as-is"
    (is (= "(inc x)"
           (#'f.completion/normalize-code-result "(inc x)"))))

  (testing "preserve leading spaces when no fences"
    (is (= "    (println \"indented\")"
           (#'f.completion/normalize-code-result "    (println \"indented\")")))))

(deftest complete-with-nil-model-test
  (testing "Returns clean error when no completion model is configured (nil full-model)"
    (h/reset-components!)
    (h/config! {:env "test"})
    (let [result (f.completion/complete
                  {:doc-text "(defn foo [] 1)"
                   :doc-version 1
                   :position {:line 1 :character 16}}
                  (h/db*)
                  (h/config)
                  (h/messenger)
                  (h/metrics))]
      (is (some? (:error result)) "Should return an error map")
      (is (= :warning (get-in result [:error :type])))
      (is (string? (get-in result [:error :message]))))))
