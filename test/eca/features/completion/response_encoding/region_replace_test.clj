(ns eca.features.completion.response-encoding.region-replace-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.completion :as markers]
   [eca.features.completion.response-encoding.region-replace :as region-replace]
   [matcher-combinators.test :refer [match?]]))

(defn ^:private build
  ([output-text window] (build output-text window nil))
  ([output-text window {:keys [doc-text doc-version start-line]
                        :or {doc-version 1 start-line 1}}]
   (region-replace/build-items
    {:output-text output-text
     :doc-text (or doc-text window)
     :doc-version doc-version
     :region-input {:window window :start-line start-line}})))

(deftest marker-stripping-test
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

(deftest window-in-full-document-test
  (testing "the window needle is resolved inside doc-text (full buffer), not only as an isolated buffer"
    (let [doc "HEAD\nEDIT_ME\nTAIL"
          window "EDIT_ME"]
      (is (match? [{:doc-version 1 :text "FIXED" :range map?}]
                  (build "FIXED" window {:doc-text doc}))))))

(deftest ambiguous-window-in-document-test
  (testing "duplicate window substrings in doc-text yield no suggestion"
    (is (= markers/no-edits
           (build "bar" "foo" {:doc-text "foo\nfoo\n"})))))

(deftest markdown-fence-extracted-test
  (testing "normalize-code-result pulls code out of a prose-wrapped fenced block (encoder pipeline)"
    (let [window "(+ 1 1)"
          out "Sure:\n```clojure\n(* 2 2)\n```\n"]
      (is (match? [{:doc-version 1 :text string? :range map?}]
                  (build out window))))))

(deftest multi-line-window-replace-test
  (testing "multi-line windows still emit a single narrowed completion item"
    (let [doc "BEGIN\naa\nbb\nEND"
          window "aa\nbb"]
      ;; diff-window trims common prefix/suffix; only the first line differs.
      (is (match? [{:doc-version 1 :text "AA" :range map?}]
                  (build "AA\nbb" window {:doc-text doc}))))))
