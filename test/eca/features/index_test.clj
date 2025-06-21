(ns eca.features.index-test
  (:require
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is testing]]
   [eca.features.index :refer [ignore?]]))

;; a helper config that only looks at :gitignore entries
(def gitignore-config
  {:index {:ignore-files [{:type :gitignore}]}})

(deftest ignore?-test
  (testing "gitignore type"
    (let [root  "/fake/repo"
          file1 "ignored.txt"
          file2 "not-ignored.txt"]
      (testing "returns true when `git check-ignore` exits 0"
        (with-redefs [shell/sh (fn [& _args] {:exit 0})]
          (is (true?  (ignore? file1 root gitignore-config)))
          ;; even a different filename still is seen as ignored
          (is (true?  (ignore? file2 root gitignore-config)))))

      (testing "returns false when `git check-ignore` exits non-zero"
        (with-redefs [shell/sh (fn [& _] {:exit 1})]
          (is (false? (ignore? file1 root gitignore-config)))))

      (testing "returns false when `git check-ignore` throws an exception"
        (with-redefs [shell/sh (fn [& _] (throw (Exception. "boom")))]
          (is (false? (ignore? file1 root gitignore-config))))))))
