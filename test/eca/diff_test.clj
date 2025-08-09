(ns eca.diff-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.diff :as diff]))

(defn- split-diff-lines [s]
  (when s (string/split-lines s)))

(deftest diff-test
  (testing "adding new lines"
    (let [original (string/join "\n" ["a" "b"])
          revised  (string/join "\n" ["a" "b" "c" "d"])
          {:keys [added removed diff]} (diff/diff original revised "file.txt")
          lines (split-diff-lines diff)]
      (is (= 2 added) "two lines added")
      (is (= 0 removed) "no lines removed")
      (is (some #{"+c"} lines) "diff should include +c line")
      (is (some #{"+d"} lines) "diff should include +d line")))

  (testing "changing an existing line counts as one added and one removed"
    (let [original (string/join "\n" ["a" "b" "c"])
          revised  (string/join "\n" ["a" "B" "c"])
          {:keys [added removed diff]} (diff/diff original revised "file.txt")
          lines (split-diff-lines diff)]
      (is (= 1 added) "one line added due to change")
      (is (= 1 removed) "one line removed due to change")
      (is (some #{"-b"} lines) "diff should include -b line")
      (is (some #{"+B"} lines) "diff should include +B line")))

  (testing "removing lines"
    (let [original (string/join "\n" ["a" "b" "c"])
          revised  (string/join "\n" ["a"])
          {:keys [added removed diff]} (diff/diff original revised "file.txt")
          lines (split-diff-lines diff)]
      (is (= 0 added) "no lines added")
      (is (= 2 removed) "two lines removed")
      (is (some #{"-b"} lines) "diff should include -b line")
      (is (some #{"-c"} lines) "diff should include -c line")))

  (testing "new file"
    (let [revised  (string/join "\n" ["a" "b" "c" "d"])
          {:keys [added removed diff]} (diff/diff "" revised "file.txt")
          lines (split-diff-lines diff)]
      (is (= 4 added) "two lines added")
      (is (= 0 removed) "no lines removed")
      (is (some #{"+c"} lines) "diff should include +c line")
      (is (some #{"+d"} lines) "diff should include +d line"))))
