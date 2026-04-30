(ns eca.features.completion.response-encoding.search-replace-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.completion :as markers]
   [eca.features.completion.response-encoding.search-replace :as search-replace]
   [matcher-combinators.test :refer [match?]]))

(defn ^:private build
  ([doc out] (build doc out nil))
  ([doc out opts]
   (search-replace/build-items
    (merge {:doc-text doc
            :doc-version 1
            :output-text out}
           opts))))

(deftest search-replace-block-test
  (testing "search/replace patches unique text in the document"
    (let [doc "outside unique\ncursor line\noutside suffix"
          out "<<<<<<< SEARCH\noutside unique\n=======\noutside patched\n>>>>>>> REPLACE"]
      (is (match? [{:doc-version 1 :text string? :range map?}]
                  (build doc out))))))

(deftest empty-replace-deletes-test
  (testing "empty REPLACE deletes the SEARCH text"
    (let [doc "keep\ndelete me\nkeep"
          out "<<<<<<< SEARCH\ndelete me\n=======\n>>>>>>> REPLACE"]
      (is (match? [{:doc-version 1 :text "" :range map?}]
                  (build doc out))))))

(deftest malformed-model-output-test
  (testing "prompt markers leaked into SEARCH are recovered before matching"
    (let [doc "alpha\nneedle typo\nomega"
          out (str "<<<<<<< SEARCH\n"
                   "alpha\n"
                   markers/window-start-marker "\n"
                   "needle ty<ECA_CURSOR>po\n"
                   markers/window-end-marker "\n"
                   "omega\n"
                   "=======\n"
                   "alpha\n"
                   "needle fixed\n"
                   "omega\n"
                   ">>>>>>> REPLACE")]
      (is (match? [{:doc-version 1 :text string? :range map?}]
                  (build doc out)))))
  (testing "malformed `<ECA_CURSOR` in SEARCH is stripped so the needle matches"
    (let [doc "    console.log(isdraf);"
          out (str "<<<<<<< SEARCH\n"
                   "    console.log(isdraf<ECA_CURSOR);\n"
                   "=======\n"
                   "    console.log(isDraft);\n"
                   ">>>>>>> REPLACE")]
      (is (match? [{:doc-version 1 :text "Draft" :range map?}]
                  (build doc out)))))
  (testing "5-9 chevrons and optional `SEARCH>` are accepted"
    (let [doc "one\ntwo\nthree"]
      (is (match? [{:doc-version 1 :text "TWO" :range map?}]
                  (build doc "<<<<< SEARCH\ntwo\n=====\nTWO\n>>>>> REPLACE")))
      (is (match? [{:doc-version 1 :text "TWO" :range map?}]
                  (build doc "<<<<<<<<< SEARCH\ntwo\n=========\nTWO\n>>>>>>>>> REPLACE")))
      (is (match? [{:doc-version 1 :text "TWO" :range map?}]
                  (build doc "<<<<<<< SEARCH>\ntwo\n=======\nTWO\n>>>>>>> REPLACE")))))
  (testing "markdown ```diff fence around the block is ignored"
    (let [doc "one\ntwo\nthree"
          out "```diff\n<<<<<<< SEARCH\ntwo\n=======\nTWO\n>>>>>>> REPLACE\n```"]
      (is (match? [{:doc-version 1 :text "TWO" :range map?}]
                  (build doc out))))))

(deftest multiple-blocks-test
  (testing "multiple SEARCH/REPLACE blocks with wrapper text are applied left-to-right"
    (let [doc "one\ntwo\nthree"
          out (str "Suggested patch:\n"
                   "<<<<<<< SEARCH\none\n=======\nONE\n>>>>>>> REPLACE\n\n"
                   "<<<<<<< SEARCH\nthree\n=======\nTHREE\n>>>>>>> REPLACE")]
      (is (match? [{:doc-version 1 :text string? :range map?}]
                  (build doc out))))))

(deftest no-edit-signals-test
  (testing "prose without a SEARCH/REPLACE block yields ::no-edits"
    (is (= markers/no-edits
           (build "one\ntwo\nthree" "Replace two with TWO.")))))

(deftest empty-search-after-cleanup-test
  (testing "SEARCH that cleans to blank yields ::no-edits"
    (let [doc "one\ntwo\nthree"]
      (is (= markers/no-edits
             (build doc "<<<<<<< SEARCH\n=======\nprefix-\n>>>>>>> REPLACE")))
      (is (= markers/no-edits
             (build doc (str "<<<<<<< SEARCH\n"
                             markers/window-start-marker "\n"
                             markers/window-end-marker "\n"
                             "=======\nTWO\n>>>>>>> REPLACE"))))
      (is (= markers/no-edits
             (build doc "<<<<<<< SEARCH\n<ECA_CURSOR>\n=======\nTWO\n>>>>>>> REPLACE"))))))
