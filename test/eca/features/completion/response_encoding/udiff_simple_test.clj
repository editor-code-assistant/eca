(ns eca.features.completion.response-encoding.udiff-simple-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.completion.response-encoding.udiff-simple :as udiff-simple]
   [matcher-combinators.test :refer [match?]]))

(deftest full-unified-diff-test
  (testing "L1: full unified diff with file headers and line numbers applies cleanly"
    (let [doc "fn add(a, b):\n  return a-b"
          out (str "diff --git a/foo.py b/foo.py\n"
                   "--- a/foo.py\n"
                   "+++ b/foo.py\n"
                   "@@ -1,2 +1,2 @@\n"
                   " fn add(a, b):\n"
                   "-  return a-b\n"
                   "+  return a+b\n")]
      (is (match? [{:doc-version 1 :text string? :range map?}]
                  (udiff-simple/build-items
                   {:doc-text doc
                    :doc-version 1
                    :output-text out}))))))

(deftest bare-diff-body-test
  (testing "L3: bare body, no @@ at all"
    (let [doc "foo\nbar\nbaz"
          out (str " foo\n"
                   "-bar\n"
                   "+BAR\n"
                   " baz\n")]
      (is (match? [{:doc-version 1}]
                  (udiff-simple/build-items
                   {:doc-text doc
                    :doc-version 1
                    :output-text out})))))

  (testing "L4: bare body wrapped in a ```diff fence"
    (let [doc "foo\nbar\nbaz"
          out (str "```diff\n"
                   " foo\n"
                   "-bar\n"
                   "+BAR\n"
                   " baz\n"
                   "```")]
      (is (match? [{:doc-version 1}]
                  (udiff-simple/build-items
                   {:doc-text doc
                    :doc-version 1
                    :output-text out}))))))

(deftest multi-hunk-test
  (testing "two non-adjacent edits in a single response"
    (let [doc "import a\nimport b\n\ncall_a()\ncall_b()"
          out (str "@@ @@\n"
                   "-import a\n"
                   "+import a as A\n"
                   " import b\n"
                   "@@ @@\n"
                   " call_a()\n"
                   "-call_b()\n"
                   "+call_b(arg=1)\n")]
      (is (match? [{:doc-version 1}]
                  (udiff-simple/build-items
                   {:doc-text doc
                    :doc-version 1
                    :output-text out}))))))

(deftest pure-addition-without-context-test
  (testing "pure addition with no anchoring context is rejected"
    (let [doc "anything"
          out "+brand new line\n"]
      (is (nil? (udiff-simple/build-items
                 {:doc-text doc
                  :doc-version 1
                  :output-text out}))))))
