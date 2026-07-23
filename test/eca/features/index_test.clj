(ns eca.features.index-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.index :as f.index]
   [eca.shared :refer [multi-str]]
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

(deftest repo-map-test
  (testing "returns correct tree for a simple git repo"
    (with-redefs [f.index/git-ls-files (constantly ["README.md"
                                                    "src/eca/core.clj"
                                                    "test/eca/core_test.clj"])]
      (is (match?
           {(h/file-path "/fake/repo")
            {"README.md" {}
             "src" {"eca" {"core.clj" {}}}
             "test" {"eca" {"core_test.clj" {}}}}}
           (eca.features.index/repo-map {:workspace-folders [{:uri (h/file-uri "file:///fake/repo")}]} {})))))
  (testing "returns string tree with as-string? true"
    (with-redefs [f.index/git-ls-files (constantly ["foo.clj" "bar/baz.clj"])]
      (is (= (multi-str (h/file-path "/fake/repo")
                        " bar"
                        "  baz.clj"
                        " foo.clj"
                        "")
             (eca.features.index/repo-map {:workspace-folders [{:uri (h/file-uri "file:///fake/repo")}]}
                                          {:index {:repoMap {:maxEntriesPerDir 50 :maxTotalEntries 800}}}
                                          {:as-string? true}))))))

(deftest repo-map-truncation-test
  (testing "per-directory truncation shows indicator and global truncated line"
    (with-redefs [f.index/git-ls-files (constantly ["AGENTS.md"
                                                    "src/a.clj"
                                                    "src/b.clj"
                                                    "src/c.clj"
                                                    "src/d.clj"
                                                    "src/e.clj"
                                                    "src/f.clj"
                                                    "src/g.clj"
                                                    "src/h.clj"])]
      (let [out (eca.features.index/repo-map {:workspace-folders [{:uri (h/file-uri "file:///fake/repo")}]}
                                             {:index {:repoMap {:maxTotalEntries 800
                                                                :maxEntriesPerDir 3}}}
                                             {:as-string? true})]
        (is (string/includes? out (str (h/file-path "/fake/repo") "\n")))
        ;; Under src, only first 3 children (sorted) and a per-dir truncated line should appear
        (is (string/includes? out " src\n"))
        (is (string/includes? out "  a.clj\n"))
        (is (string/includes? out "  b.clj\n"))
        (is (string/includes? out "  c.clj\n"))
        (is (string/includes? out "  ... truncated output (5 more entries)\n"))
        ;; A final global truncated line should also be present
        (is (string/includes? out "\n... truncated output (")))))
  (testing "global truncation appends final truncated line"
    (with-redefs [f.index/git-ls-files (constantly ["AGENTS.md"
                                                    "CHANGELOG.md"
                                                    "LICENSE"
                                                    "src/a.clj"
                                                    "src/b.clj"])]
      (let [out (eca.features.index/repo-map {:workspace-folders [{:uri (h/file-uri "file:///fake/repo")}]}
                                             {:index {:repoMap {:maxTotalEntries 3
                                                                :maxEntriesPerDir 800}}}
                                             {:as-string? true})]
        ;; Contains a global truncated line
        (is (string/includes? out "\n... truncated output ("))))))
