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
    (let [revised (string/join "\n" ["a" "b" "c" "d"])
          {:keys [added removed diff]} (diff/diff "" revised "file.txt")
          lines (split-diff-lines diff)]
      (is (= 4 added) "two lines added")
      (is (= 0 removed) "no lines removed")
      (is (some #{"+c"} lines) "diff should include +c line")
      (is (some #{"+d"} lines) "diff should include +d line"))))

(deftest diff-bug-259-test
  (testing "replacing one line with many lines should count correctly (bug #259)"
    (let [original (string/join "\n" ["line1" "line-to-remove" "line3"])
          revised (string/join "\n" (concat ["line1"]
                                            (map #(str "new-line-" %) (range 1 29))
                                            ["line3"]))
          {:keys [added removed diff]} (diff/diff original revised "file.txt")
          lines (split-diff-lines diff)]
      (is (= 28 added) "28 lines were added")
      (is (= 1 removed) "1 line was removed, not 28")
      (is (some #{"-line-to-remove"} lines) "diff should include removed line")
      (is (some #{"+new-line-1"} lines) "diff should include first new line")
      (is (some #{"+new-line-28"} lines) "diff should include last new line")))

  (testing "replacing many lines with one line should count correctly"
    (let [original (string/join "\n" (concat ["line1"]
                                             (map #(str "old-line-" %) (range 1 11))
                                             ["line3"]))
          revised (string/join "\n" ["line1" "replacement" "line3"])
          {:keys [added removed diff]} (diff/diff original revised "file.txt")
          lines (split-diff-lines diff)]
      (is (= 1 added) "1 line was added")
      (is (= 10 removed) "10 lines were removed")
      (is (some #{"-old-line-1"} lines) "diff should include first removed line")
      (is (some #{"+replacement"} lines) "diff should include replacement line"))))

(deftest unified-diff-counts-test
  (testing "counts added and removed lines from unified diff"
    (let [example-diff (string/join "\n" ["--- original.txt"
                                          "+++ revised.txt"
                                          "@@ -1,1 +1,2 @@"
                                          "-a"
                                          "+b"
                                          "+c"])
          {:keys [added removed]} (diff/unified-diff-counts example-diff)]
      (is (= 2 added) "one line added in the diff body")
      (is (= 1 removed) "one line removed in the diff body"))))
