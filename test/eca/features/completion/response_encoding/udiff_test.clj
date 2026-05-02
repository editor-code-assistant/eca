(ns eca.features.completion.response-encoding.udiff-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.completion :as markers]
   [eca.features.completion.response-encoding.udiff :as udiff]
   [matcher-combinators.test :refer [match?]]))

(defn ^:private build
  ([doc out] (build doc out 1))
  ([doc out doc-version]
   (udiff/build-items
    {:doc-text doc
     :output-text out
     :doc-version doc-version})))

(deftest single-hunk-test
  (testing "a single hunk patches the document"
    (let [doc "line1\nline2\nline3\n"
          out "@@ -2,1 +2,1 @@\n-line2\n+LINE2\n"]
      (is (match? [{:doc-version 1 :text "LINE" :range map?}]
                  (build doc out)))))
  (testing "a single hunk with leading context"
    (let [doc "alpha\nbeta\ngamma\n"
          out "@@ -1,3 +1,3 @@\n alpha\n-beta\n+CHANGED\n gamma\n"]
      (is (match? [{:doc-version 1 :text "CHANGED" :range map?}]
                  (build doc out))))))

(deftest fences-and-git-headers-stripped-test
  (testing "markdown diff fence is stripped"
    (let [doc "one\ntwo\nthree\n"
          out "```diff\n@@ -2,1 +2,1 @@\n-two\n+TWO\n```"]
      (is (match? [{:doc-version 1 :text "TWO" :range map?}]
                  (build doc out)))))
  (testing "diff --git and index headers are stripped"
    (let [doc "one\ntwo\nthree\n"
          out (str "diff --git a/file b/file\n"
                   "index abc..def 100644\n"
                   "--- a/file\n"
                   "+++ b/file\n"
                   "@@ -2,1 +2,1 @@\n"
                   "-two\n"
                   "+TWO\n")]
      (is (match? [{:doc-version 1 :text "TWO" :range map?}]
                  (build doc out))))))

(deftest independent-multi-hunk-test
  (testing "two non-overlapping hunks apply independently"
    (let [doc "one\ntwo\nthree\nfour\nfive\n"
          out "@@ -1,1 +1,1 @@\n-one\n+ONE\n@@ -5,1 +5,1 @@\n-five\n+FIVE\n"]
      (is (match? [{:doc-version 1 :text string? :range map?}]
                  (build doc out))))))

(deftest multi-hunk-dependent-edits-test
  (testing "two hunks that depend on original snapshot positions"
    (let [doc "aaa\nbbb\nccc\n"
          ;; hunk 1: changes line1, hunk 2: changes line3 (both authored
          ;; against the original doc). Sequential needle match would fail
          ;; if hunk 1 changes line offsets.
          out "@@ -1,1 +1,1 @@\n-aaa\n+AAA\n@@ -3,1 +3,1 @@\n-ccc\n+CCC\n"]
      (is (match? [{:doc-version 1 :text string? :range map?}]
                  (build doc out))))))

(deftest duplicate-context-fail-safe-test
  (testing "duplicate context without @@ line numbers yields no item"
    (let [doc "dup line\nunique\nunique\ndup line\n"
          ;; no @@ header with line numbers; ambiguous
          out " dup line\n unique\n-dup line\n+CHANGED\n"]
      (is (= markers/no-edits (build doc out))))))

(deftest overlapping-hunk-reject-test
  (testing "overlapping hunk ranges are rejected"
    (let [doc "one\ntwo\nthree\n"
          out "@@ -1,2 +1,2 @@\n-one\n-two\n+ONE\n+TWO\n@@ -2,2 +2,2 @@\n-two\n-three\n+TWO_MOD\n+THREE\n"]
      (is (= markers/no-edits (build doc out))))))

;; --- Malformed hunk ---

(deftest malformed-hunk-indented-at-sign-header-test
  (testing "leading spaces before @@ must not drop the edit (LLMs often indent)"
    ;; Same logical hunk as @@ -2,1 +2,1 @@ with one context line, but the
    ;; header is indented like fenced-example / prose alignment.
    (let [doc "alpha\nbeta\ngamma\n"
          out (str "    @@ -1,3 +1,3 @@\n"
                   " alpha\n"
                   "-beta\n"
                   "+BETA\n"
                   " gamma\n")]
      (is (match? [{:doc-version 1 :text "BETA" :range map?}]
                  (build doc out))))))


(deftest line-disambiguation-first-occurrence-test
  (testing "hunk targets first repeated line via @@ line numbers"
    (let [doc "repeated line\nmarker one\nrepeated line\nmarker two\n"
          out "@@ -1,2 +1,2 @@\n repeated line\n-marker one\n+MARKER ONE\n"]
      (is (match? [{:doc-version 1 :text "MARKER ONE" :range map?}]
                  (build doc out))))))

(deftest line-disambiguation-later-occurrence-test
  (testing "hunk targets second repeated line via @@ line numbers"
    (let [doc "repeated line\nmarker one\nrepeated line\nmarker two\n"
          out "@@ -3,2 +3,2 @@\n repeated line\n-marker two\n+MARKER TWO\n"]
      (is (match? [{:doc-version 1 :text "MARKER TWO" :range map?}]
                  (build doc out))))))

(deftest sub-line-range-test
  (testing "range/text narrows to changed token, not whole line"
    (let [doc "let x = 1;\nlet y = 2;\n"
          out "@@ -1,1 +1,1 @@\n-let x = 1;\n+let x = 42;\n"]
      (is (match? [{:doc-version 1 :text "42" :range map?}]
                  (build doc out))))))

(deftest no-trailing-newline-on-buffer-test
  (testing "buffer without trailing newline is handled"
    (let [doc "foo\nbar\nbaz"]
      (is (match? [{:doc-version 1 :text string? :range map?}]
                  (build doc "@@ -2,1 +2,1 @@\n-bar\n+qux\n"))))))

(deftest unique-anchor-via-preceding-context-test
  (testing "duplicate tail resolved by unique preceding context"
    (let [doc "one\ntwo\nthree\nfour\nfive\nmore\nfour\nfive\n"
          out "@@ -1,5 +1,5 @@\n one\n two\n three\n four\n-five\n+FIVE\n"]
      (is (match? [{:doc-version 1 :text "FIVE" :range map?}]
                  (build doc out))))))

(deftest eof-phantom-newline-test
  (testing "context newline at EOF matches buffer without trailing newline"
    (let [doc "line1\nline2"]
      (is (match? [{:doc-version 1 :text string? :range map?}]
                  (build doc "@@ -2,1 +2,1 @@\n-line2\n+LINE2\n"))))))

(deftest edit-range-clamp-at-eof-test
  (testing "replacing last line with no trailing newline stays in bounds"
    (let [doc "line1\nline2"]
      (is (match? [{:doc-version 1 :text string? :range map?}]
                  (build doc "@@ -2,1 +2,1 @@\n-line2\n+replaced\n")))))
  (testing "replacing multibyte last line with no trailing newline"
    (let [doc "hello\n세계"]
      (is (match? [{:doc-version 1 :text string? :range map?}]
                  (build doc "@@ -2,1 +2,1 @@\n-세계\n+world\n"))))))

(deftest strip-metadata-test
  (testing "diff --git metadata is stripped before parsing"
    (let [doc "one\ntwo\nthree\n"
          out (str "diff --git a/file.txt b/file.txt\n"
                   "index 0123456..abcdefg 100644\n"
                   "--- a/file.txt\n"
                   "+++ b/file.txt\n"
                   "@@ -2,1 +2,1 @@\n"
                   "-two\n"
                   "+TWO\n")]
      (is (match? [{:doc-version 1 :text "TWO" :range map?}]
                  (build doc out))))))
