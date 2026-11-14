(ns eca.features.index-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is testing]]
   [eca.features.index :as f.index]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(def gitignore-config
  {:index {:ignoreFiles [{:type :gitignore}]}})

(deftest ignore?-test
  (testing "gitignore type"
    (let [root (h/file-path "/fake/repo")
          file1 (fs/path root "ignored.txt")
          file2 (fs/path root "not-ignored.txt")]
      (testing "returns filtered files when `git ls-files` works"
        (with-redefs [f.index/git-ls-files #'f.index/git-ls-files*
                      shell/sh (constantly {:exit 0 :out "not-ignored.txt"})
                      fs/canonicalize #(fs/path root %)]
          (is
           (match?
            [file2]
            (f.index/filter-allowed [file1 file2] root gitignore-config)))))

      (testing "returns all files when `git ls-files` exits non-zero"
        (with-redefs [f.index/git-ls-files #'f.index/git-ls-files*
                      shell/sh (constantly {:exit 1})]
          (is
           (match?
            [file1 file2]
            (f.index/filter-allowed [file1 file2] root gitignore-config))))))))

(deftest allowed-files-test
  (let [root (h/file-path "/fake/repo")]
    (testing "enumerates via git ls-files when gitignore is configured, including hidden tracked files"
      (with-redefs [f.index/git-ls-files #'f.index/git-ls-files*
                    shell/sh (constantly {:exit 0 :out "src/foo.clj\n.github/ci.yml"})
                    fs/glob (fn [& _] (throw (ex-info "should not glob when git enumeration works" {})))]
        (is (match?
             [(str (fs/file root "src/foo.clj"))
              (str (fs/file root ".github/ci.yml"))]
             (f.index/allowed-files root gitignore-config)))))
    (testing "falls back to globbing when git ls-files exits non-zero"
      (with-redefs [f.index/git-ls-files #'f.index/git-ls-files*
                    shell/sh (constantly {:exit 1})
                    f.index/all-files-from #'f.index/all-files-from*
                    fs/glob (fn [_root _pattern] [(fs/path root "a.txt")])]
        (is (match?
             [(fs/path root "a.txt")]
             (f.index/allowed-files root gitignore-config)))))
    (testing "globs when gitignore is not configured"
      (with-redefs [f.index/all-files-from #'f.index/all-files-from*
                    fs/glob (fn [_root _pattern] [(fs/path root "b.txt")])
                    shell/sh (fn [& _] (throw (ex-info "should not call git when gitignore is not configured" {})))]
        (is (match?
             [(fs/path root "b.txt")]
             (f.index/allowed-files root {})))))))
