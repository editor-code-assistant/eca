(ns eca.features.completion-diff-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.completion-diff :as completion-diff]
   [matcher-combinators.test :refer [match?]]))

(deftest diff-window-test
  (testing "returns nil when windows are identical"
    (is (nil? (completion-diff/diff-window "foo\nbar" "foo\nbar" 1))))

  (testing "pure suffix append yields a zero-width range at insertion point"
    (is (match? {:range {:start {:line 2 :character 1}
                         :end {:line 2 :character 1}}
                 :text "bar\n"}
                (completion-diff/diff-window "foo\n" "foo\nbar\n" 1))))

  (testing "before-cursor typo fix"
    ;; orig = "thersholdd", new = "thershold" → drop trailing 'd' at offset 9
    (is (match? {:range {:start {:line 1 :character 10}
                         :end {:line 1 :character 11}}
                 :text ""}
                (completion-diff/diff-window "thersholdd" "thershold" 1))))

  (testing "swap two characters before cursor"
    ;; orig = "ej", new = "je" → replace whole token
    (is (match? {:range {:start {:line 1 :character 1}
                         :end {:line 1 :character 3}}
                 :text "je"}
                (completion-diff/diff-window "ej" "je" 1))))

  (testing "edit before AND after the cursor on the same line"
    (is (match? {:range {:start {:line 1 :character 3}
                         :end {:line 1 :character 5}}
                 :text "XY"}
                (completion-diff/diff-window "abCDef" "abXYef" 1))))

  (testing "multi-line replacement preserves line numbering relative to start-line"
    ;; orig has "def" on line 2, new has "DEF". Window starts at protocol line 5.
    (let [result (completion-diff/diff-window "abc\ndef\nghi"
                                              "abc\nDEF\nghi"
                                              5)]
      (is (match? {:range {:start {:line 6 :character 1}
                           :end {:line 6 :character 4}}
                   :text "DEF"}
                  result))))

  (testing "common prefix may include newlines"
    (let [result (completion-diff/diff-window "aaa\nbbb\nccc"
                                              "aaa\nbXb\nccc"
                                              10)]
      (is (match? {:range {:start {:line 11 :character 2}
                           :end {:line 11 :character 3}}
                   :text "X"}
                  result))))

  (testing "completely empty orig → all insertion at start"
    (is (match? {:range {:start {:line 1 :character 1}
                         :end {:line 1 :character 1}}
                 :text "hello"}
                (completion-diff/diff-window "" "hello" 1))))

  (testing "completely empty new → all deletion"
    (is (match? {:range {:start {:line 1 :character 1}
                         :end {:line 1 :character 4}}
                 :text ""}
                (completion-diff/diff-window "abc" "" 1)))))

(deftest extract-window-test
  (testing "centers the window on cursor-line with the given radius"
    (let [doc "l1\nl2\nl3\nl4\nl5\nl6\nl7"]
      (is (match? {:window "l2\nl3\nl4\nl5\nl6"
                   :start-line 2
                   :end-line 6}
                  (completion-diff/extract-window doc 4 2)))))

  (testing "clamps to document bounds"
    (let [doc "a\nb\nc"]
      (is (match? {:window "a\nb\nc"
                   :start-line 1
                   :end-line 3}
                  (completion-diff/extract-window doc 1 10)))))

  (testing "single-line document"
    (is (match? {:window "hi"
                 :start-line 1
                 :end-line 1}
                (completion-diff/extract-window "hi" 1 6)))))

(deftest cursor-position-in-window-test
  (testing "first line, first column"
    (is (= 0 (completion-diff/cursor-position-in-window
              "abc\ndef" {:line 1 :character 1} 1))))

  (testing "second line of a multi-line window"
    (is (= 4 (completion-diff/cursor-position-in-window
              "abc\ndef" {:line 2 :character 1} 1))))

  (testing "respects window start-line offset"
    (is (= 2 (completion-diff/cursor-position-in-window
              "abc\ndef" {:line 5 :character 3} 5))))

  (testing "out-of-range cursor returns nil"
    (is (nil? (completion-diff/cursor-position-in-window
               "abc\ndef" {:line 99 :character 1} 1)))))
