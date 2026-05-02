(ns eca.features.completion.response-encoding.shared-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.completion :as markers]
   [eca.features.completion.response-encoding.shared :as shared]
   [eca.logger :as logger]
   [matcher-combinators.test :refer [match?]]))

(deftest splice-test
  (testing "replaces a middle substring"
    (is (= "abXYef" (shared/splice "abcdef" 2 4 "XY"))))
  (testing "supports boundary replacements"
    (is (= "XYcdef" (shared/splice "abcdef" 0 2 "XY")))
    (is (= "abcdXY" (shared/splice "abcdef" 4 6 "XY")))
    (is (= "abcXYdef" (shared/splice "abcdef" 3 3 "XY")))
    (is (= "XY" (shared/splice "abcdef" 0 6 "XY")))))

(deftest trim-pair-edges-test
  (testing "trims leading and trailing blank lines from both sides"
    (is (= ["needle" "replacement"]
           (shared/trim-pair-edges ["\n \nneedle\n\n"
                                    "\nreplacement\n \n"]))))
  (testing "treats nil sides as empty strings"
    (is (= ["" ""] (shared/trim-pair-edges [nil nil])))))

(deftest split-keeping-newlines-test
  (testing "keeps line endings with each line"
    (is (= ["a\n" "b\n" "c"]
           (shared/split-keeping-newlines "a\nb\nc"))))
  (testing "returns one empty line for empty input"
    (is (= [""] (shared/split-keeping-newlines ""))))
  (testing "preserves an unterminated final line"
    (is (= ["a\n" "b"] (shared/split-keeping-newlines "a\nb"))))
  (testing "preserves CRLF line endings"
    (is (= ["a\r\n" "b"] (shared/split-keeping-newlines "a\r\nb")))))

(deftest collapse-blank-line-test
  (testing "collapses whitespace-only lines to their line ending"
    (is (= "\n" (shared/collapse-blank-line "   \n"))))
  (testing "preserves CRLF line endings"
    (is (= "\r\n" (shared/collapse-blank-line "\t  \r\n"))))
  (testing "leaves empty and non-blank lines unchanged"
    (is (= "" (shared/collapse-blank-line "")))
    (is (= "  value\n" (shared/collapse-blank-line "  value\n")))))

(deftest try-line-aligned-match-test
  (testing "matches when only whitespace-only lines differ"
    (is (= {:start 0 :end 16}
           (shared/try-line-aligned-match "alpha\n    \nomega"
                                          "alpha\n\nomega"))))
  (testing "rejects ambiguous line-aligned matches"
    (is (nil? (shared/try-line-aligned-match
               "alpha\n    \nomega\nalpha\n\t\nomega\n"
               "alpha\n\nomega\n"))))
  (testing "does not reinterpret literal substrings"
    (is (nil? (shared/try-line-aligned-match
               "alpha\n\nomega\nalpha\n   \nomega"
               "alpha\n\nomega"))))
  (testing "defers unconditionally when a literal match exists, even if unique"
    (is (nil? (shared/try-line-aligned-match
               "alpha\n\nomega"
               "alpha\n\nomega")))))

(deftest match-needle-blank-line-whitespace-test
  (testing "rescues a needle whose blank lines differ only in whitespace"
    ;; Buffer has a whitespace-only blank line ("    \n"); the model emits a
    ;; truly empty blank line. Literal index-of finds zero matches, so
    ;; try-line-aligned-match collapses both sides and resolves a unique window.
    (let [buf "fn foo() {\n    \n  return 1;\n}\n"
          needle "fn foo() {\n\n  return 1;\n}\n"]
      (is (nil? (clojure.string/index-of buf needle))
          "precondition: literal substring lookup must miss")
      (is (= {:start 0 :end (count buf)}
             (shared/match-needle buf needle {})))))
  (testing "and the symmetric case: needle has the whitespace, buffer is clean"
    (let [buf "fn foo() {\n\n  return 1;\n}\n"
          needle "fn foo() {\n   \n  return 1;\n}\n"]
      (is (nil? (clojure.string/index-of buf needle)))
      (is (= {:start 0 :end (count buf)}
             (shared/match-needle buf needle {}))))))

(deftest match-needle-leading-whitespace-drift-test
  (testing "needle whose content lines have drifted indentation"
    ;; Buffer is indented 4 spaces; model echoed the same block at 2 spaces.
    ;; Literal lookup misses; the line-aligned fallback strips leading
    ;; whitespace per line and resolves the unique window.
    (let [buf "if (cond) {\n    doThing();\n    doOther();\n}\n"
          needle "if (cond) {\n  doThing();\n  doOther();\n}\n"]
      (is (nil? (clojure.string/index-of buf needle))
          "precondition: literal substring lookup must miss")
      (is (= {:start 0 :end (count buf)}
             (shared/match-needle buf needle {}))))))

(deftest clean-raw-output-test
  (testing "strips whole-line window markers (with leading whitespace)"
    (is (= "foo\nbar\n"
           (markers/strip-leaked-markers
            (str "foo\n  " markers/window-start-marker "\nbar\n" markers/window-end-marker "\n")))))
  (testing "strips inline window markers (literal substring)"
    (is (= "before  after\n"
           (markers/strip-leaked-markers (str "before " markers/window-start-marker " after\n")))))
  (testing "strips cursor marker on its own line"
    (is (= "\n"
           (markers/strip-leaked-markers (str markers/cursor-marker "\n")))))
  (testing "strips malformed inline marker variants"
    (is (= "x" (markers/strip-leaked-markers "x<ECA_CURSOR")))
    (is (= "x" (markers/strip-leaked-markers "ECA_CURSOR>x")))
    (is (= "x" (markers/strip-leaked-markers "x<ECA_CURSO>")))
    (is (= "x" (markers/strip-leaked-markers "ECA_CURSORxECA_CURSOR"))))
  (testing "treats nil as empty"
    (is (= "" (markers/strip-leaked-markers nil)))))

(deftest edits->items-test
  (testing "applies edits and emits completion items"
    (with-redefs [logger/debug (fn [& _])]
      (is (match? [{:doc-version 7
                    :text "replacement"
                    :range map?}]
                  (shared/edits->items
                   {:encoder-id "test"
                    :output-text "raw"
                    :doc-text "needle"
                    :doc-version 7
                    :edits [["needle" "replacement"]]})))))
  (testing "blank output yields no edits"
    (with-redefs [logger/debug (fn [& _])]
      (is (= markers/no-edits
             (shared/edits->items
              {:encoder-id "test"
               :output-text "  \n"
               :doc-text "needle"
               :doc-version 1
               :edits [["needle" "replacement"]]})))))
  (testing "empty edits yield no edits"
    (with-redefs [logger/debug (fn [& _])]
      (is (= markers/no-edits
             (shared/edits->items
              {:encoder-id "test"
               :output-text "raw"
               :doc-text "needle"
               :doc-version 1
               :edits []})))))
  (testing "no-op patched text yields no edits"
    (with-redefs [logger/debug (fn [& _])]
      (is (= markers/no-edits
             (shared/edits->items
              {:encoder-id "test"
               :output-text "raw"
               :doc-text "needle"
               :doc-version 1
               :edits [["needle" "needle"]]}))))))

(deftest apply-edits-no-region-pure-splice-test
  (testing "splices the entire replacement over the matched range regardless of line counts"
    (let [doc "alpha\nbeta\ngamma\n"
          needle "beta\n"
          replacement "one\ntwo\nthree\n"]
      (is (= "alpha\none\ntwo\nthree\ngamma\n"
             (shared/apply-edits doc [[needle replacement]]))))))

(deftest apply-edits-sequential-pollution-test
  (testing "original-buffer matching prevents pollution from earlier replacements"
    (let [doc "aaa\nbbb\nccc\n"
          edits [["aaa" "aaa\nccc"]
                 ["ccc" "CCC"]]]
      (is (= "aaa\nccc\nbbb\nCCC\n"
             (shared/apply-edits doc edits))
          "both edits match against the original snapshot, not a mutated buffer"))))
