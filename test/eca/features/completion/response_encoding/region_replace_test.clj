(ns eca.features.completion.response-encoding.region-replace-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.completion :as markers]
   [eca.features.completion.response-encoding.region-replace :as region-replace]
   [matcher-combinators.test :refer [match?]]))

(defn ^:private build
  ([output-text window] (build output-text window 1))
  ([output-text window start-line]
   (region-replace/build-items
    {:output-text output-text
     :doc-text window
     :doc-version 1
     :region-input {:window window :start-line start-line}})))

(deftest marker-stripping-test
  ;; HAND REVIEWED.
  (testing "empty lines are stripped"
    (is (= markers/no-edits
           (build "alpha\n   \nomega" "alpha\n\nomega"))))
  (testing "well-formed cursor marker is stripped"
    (let [window "console.log();"
          output "console.log(<ECA_CURSOR>hello);"]
      (is (match? [{:doc-version 1 :text "hello" :range map?}]
                  (build output window)))))
  (testing "malformed cursor marker is stripped"
    (let [window "console.log();"
          output "console.log(<ECA_CURSOhello);"]
      (is (match? [{:doc-version 1 :text "hello" :range map?}]
                  (build output window))))))

(deftest no-edits-test
  (testing "unchanged rewritten window yields ::no-edits"
    (is (= markers/no-edits (build "alpha\nbeta" "alpha\nbeta"))))
  (testing "blank output yields ::no-edits"
    (is (= markers/no-edits (build "" "alpha\nbeta")))
    (is (= markers/no-edits (build "  \n" "alpha\nbeta"))))
  (testing "nil output yields ::no-edits"
    (is (= markers/no-edits (build nil "alpha\nbeta")))))
