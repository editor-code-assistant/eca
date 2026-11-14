(ns eca.modules.repo-map-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.index :as f.index]
   [eca.modules.repo-map :as m.repo-map]
   [eca.shared :refer [multi-str]]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

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
           (m.repo-map/repo-map {:workspace-folders [{:uri (h/file-uri "file:///fake/repo")}]} {})))))
  (testing "returns string tree with as-string? true"
    (with-redefs [f.index/git-ls-files (constantly ["foo.clj" "bar/baz.clj"])]
      (is (= (multi-str (h/file-path "/fake/repo")
                        " bar"
                        "  baz.clj"
                        " foo.clj"
                        "")
             (m.repo-map/repo-map {:workspace-folders [{:uri (h/file-uri "file:///fake/repo")}]}
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
      (let [out (m.repo-map/repo-map {:workspace-folders [{:uri (h/file-uri "file:///fake/repo")}]}
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
      (let [out (m.repo-map/repo-map {:workspace-folders [{:uri (h/file-uri "file:///fake/repo")}]}
                                     {:index {:repoMap {:maxTotalEntries 3
                                                        :maxEntriesPerDir 800}}}
                                     {:as-string? true})]
        ;; Contains a global truncated line
        (is (string/includes? out "\n... truncated output ("))))))
