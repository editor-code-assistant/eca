(ns eca.features.context-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.context :as f.context]
   [eca.features.index :as f.index]
   [eca.features.tools.mcp :as f.mcp]
   [eca.llm-api :as llm-api]
   [eca.shared :refer [multi-str]]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]
   [matcher-combinators.matchers :as m]))

(h/reset-components-before-test)

(deftest all-contexts-test
  (testing "includes repoMap, root directories, files/dirs, and mcp resources"
    (h/reset-components!)
    (let [root (h/file-path "/fake/repo")
          ;; Fake filesystem entries under the root
          fake-paths [(h/file-path (str root "/dir"))
                      (h/file-path (str root "/foo.txt"))
                      (h/file-path (str root "/dir/nested.txt"))
                      (h/file-path (str root "/bar.txt"))]]
      (swap! (h/db*) assoc :workspace-folders [{:uri (h/file-uri "file:///fake/repo")}])
      (with-redefs [f.context/all-files-from #'f.context/all-files-from*
                    fs/glob (fn [_root-filename pattern]
                              ;; Very simple glob: filter by substring present in pattern ("**<q>**")
                              (let [q (string/replace pattern #"\*" "")]
                                (filter #(string/includes? (str %) q) fake-paths)))
                    fs/directory? (fn [p] (string/ends-with? (str p) "/dir"))
                    f.index/filter-allowed (fn [file-paths _root _config] file-paths)
                    f.mcp/all-resources (fn [_db] [{:uri "mcp://r1"}])]
        (is (match?
             [{:type "repoMap"}
              {:type "cursor"}
              {:type "directory" :path root}
              {#_#_:type "directory" :path (h/file-path (str root "/dir"))}
              {:type "file" :path (h/file-path (str root "/foo.txt"))}
              {:type "file" :path (h/file-path (str root "/dir/nested.txt"))}
              {:type "file" :path (h/file-path (str root "/bar.txt"))}
              {:type "mcpResource" :uri "mcp://r1"}]
             (f.context/all-contexts nil false (h/db*) (h/config)))))))

  (testing "respects the query by limiting glob results"
    (h/reset-components!)
    (let [root (h/file-path "/fake/repo")
          fake-paths [(str root "/foo.txt")
                      (str root "/bar.txt")]]
      (swap! (h/db*) assoc :workspace-folders [{:uri (h/file-uri "file:///fake/repo")}])
      (with-redefs [f.context/all-files-from #'f.context/all-files-from*
                    fs/glob (fn [_root-filename pattern]
                              (let [q (string/replace pattern #"\*" "")]
                                (filter #(string/includes? (str %) q) fake-paths)))
                    fs/directory? (constantly false)
                    fs/canonicalize identity
                    f.index/filter-allowed (fn [file-paths _root _config] file-paths)
                    f.mcp/all-resources (fn [_db] [])]
        (let [result (f.context/all-contexts "foo" false (h/db*) {})]
          ;; Should include foo.txt but not bar.txt
          (is (some #(= {:type "file" :path (str root "/foo.txt")} %) result))
          (is (not (some #(= {:type "file" :path (str root "/bar.txt")} %) result)))))))

  (testing "aggregates entries across multiple workspace roots"
    (h/reset-components!)
    (let [root1 (h/file-path "/fake/repo1")
          root2 (h/file-path "/fake/repo2")]
      (swap! (h/db*) assoc :workspace-folders [{:uri (h/file-uri "file:///fake/repo1")}
                                               {:uri (h/file-uri "file:///fake/repo2")}])
      (with-redefs [f.context/all-files-from #'f.context/all-files-from*
                    fs/glob (fn [root-filename pattern]
                              (let [q (string/replace pattern #"\*" "")]
                                (cond
                                  (string/includes? (str root-filename) (h/file-path "/fake/repo1"))
                                  (filter #(string/includes? (str %) q)
                                          [(str root1 "/a.clj")])

                                  (string/includes? (str root-filename) (h/file-path "/fake/repo2"))
                                  (filter #(string/includes? (str %) q)
                                          [(str root2 "/b.clj")])

                                  :else [])))
                    fs/directory? (constantly false)
                    fs/canonicalize identity
                    f.index/filter-allowed (fn [file-paths _root _config] file-paths)
                    f.mcp/all-resources (fn [_db] [])]
        (let [result (f.context/all-contexts nil false (h/db*) {})]
          ;; Root directories present
          (is (some #(= {:type "directory" :path root1} %) result))
          (is (some #(= {:type "directory" :path root2} %) result))
          ;; Files from both roots present
          (is (some #(= {:type "file" :path (str root1 "/a.clj")} %) result))
          (is (some #(= {:type "file" :path (str root2 "/b.clj")} %) result)))))))

(deftest case-insensitive-query-test
  (testing "Should find README.md when searching for 'readme' (case-insensitive)"
    (let [readme (h/file-path "/fake/repo/README.md")
          core (h/file-path "/fake/repo/src/core.clj")]
      (with-redefs [fs/glob (fn [_root-filename pattern]
                              (cond
                                (= pattern "**") [readme core]
                                (= pattern "**readme**") []
                                :else []))
                    fs/directory? (constantly false)
                    fs/canonicalize identity
                    f.index/filter-allowed (fn [files _root _config] files)]
        (let [db* (atom {:workspace-folders [{:uri (h/file-uri "file:///fake/repo")}]})
              config {}
              results (f.context/all-contexts "readme" false db* config)
              file-paths (->> results (filter #(= "file" (:type %))) (map :path) set)]
          (is (contains? file-paths readme)))))))

(deftest relative-path-query-test
  (testing "./relative path lists entries in that directory (no glob)"
    (let [root (h/file-path "/fake/repo")
          rel (str root "/./src")
          entries [(str rel "/a.clj") (str rel "/pkg")]
          db* (atom {:workspace-folders [{:uri (h/file-uri "file:///fake/repo")}]})]
      (with-redefs [fs/glob (fn [& _] (throw (ex-info "glob should not be called for relative paths" {})))
                    fs/file (fn [& parts] (string/join "/" parts))
                    fs/exists? (fn [p] (= p rel))
                    fs/list-dir (fn [p]
                                  (is (= p rel))
                                  entries)
                    fs/parent (fn [_] (throw (ex-info "parent should not be used when path exists" {})))
                    fs/directory? (fn [p] (string/ends-with? (str p) "/pkg"))
                    fs/canonicalize identity
                    f.index/filter-allowed (fn [file-paths _root _config] file-paths)
                    f.mcp/all-resources (fn [_] [])]
        (let [result (f.context/all-contexts "./src" false db* {})]
          ;; Root directory present
          (is (some #(= {:type "directory" :path root} %) result))
          ;; Entries mapped from the relative listing
          (is (some #(= {:type "file" :path (str rel "/a.clj")} %) result))
          (is (some #(= {:type "directory" :path (str rel "/pkg")} %) result))))))

  (testing "./relative path falls back to parent directory when non-existent"
    (let [root (h/file-path "/fake/repo")
          rel (str root "/./missing/file")
          parent (str root "/./missing")
          entries [(str parent "/x.txt") (str parent "/subdir")]
          db* (atom {:workspace-folders [{:uri (h/file-uri "file:///fake/repo")}]})]
      (with-redefs [fs/glob (fn [& _] (throw (ex-info "glob should not be called for relative paths" {})))
                    fs/file (fn [& parts] (string/join "/" parts))
                    fs/exists? (fn [p] (= p "exists-nowhere")) ;; ensure rel does not exist
                    fs/list-dir (fn [p]
                                  (is (= p parent))
                                  entries)
                    fs/parent (fn [p]
                                (is (= p rel))
                                parent)
                    fs/directory? (fn [p] (string/ends-with? (str p) "/subdir"))
                    fs/canonicalize identity
                    f.index/filter-allowed (fn [file-paths _root _config] file-paths)
                    f.mcp/all-resources (fn [_] [])]
        (let [result (f.context/all-contexts "./missing/file" false db* {})]
          ;; Root directory present
          (is (some #(= {:type "directory" :path root} %) result))
          ;; Entries mapped from the parent listing
          (is (some #(= {:type "file" :path (str parent "/x.txt")} %) result))
          (is (some #(= {:type "directory" :path (str parent "/subdir")} %) result))))))

  (testing "~ expands to home and lists entries"
    (let [root (h/file-path "/fake/repo")
          home "/home/tester"
          entries [(str home "/.bashrc") (str home "/projects")]
          db* (atom {:workspace-folders [{:uri (h/file-uri "file:///fake/repo")}]})]
      (with-redefs [fs/glob (fn [& _] (throw (ex-info "glob should not be called for ~ paths" {})))
                    fs/file (fn [& parts] (string/join "/" parts))
                    fs/expand-home (fn [p]
                                     (is (= p "~"))
                                     home)
                    fs/exists? (fn [p] (= p home))
                    fs/list-dir (fn [p]
                                  (is (= p home))
                                  entries)
                    fs/directory? (fn [p] (string/ends-with? (str p) "/projects"))
                    fs/canonicalize identity
                    f.index/filter-allowed (fn [file-paths _root _config] file-paths)
                    f.mcp/all-resources (fn [_] [])]
        (let [result (f.context/all-contexts "~" false db* {})]
          ;; Root directory present
          (is (some #(= {:type "directory" :path root} %) result))
          ;; Entries from home listing
          (is (some #(= {:type "file" :path (str home "/.bashrc")} %) result))
          (is (some #(= {:type "directory" :path (str home "/projects")} %) result)))))))

(deftest parse-agents-file-test
  (testing "simple agents file with no path inclusion"
    (let [a-file (h/file-path "/fake/AGENTS.md")
          a-content (multi-str
                     "- do foo")]
      (with-redefs [llm-api/refine-file-context (fn [p _l]
                                                  (condp = p
                                                    a-file a-content))]
        (is (match?
             (m/in-any-order
              [{:type :agents-file
                :path a-file
                :content a-content}])
             (#'f.context/parse-agents-file a-file))))))
  (testing "Single relative path inclusion"
    (let [a-file (h/file-path "/fake/AGENTS.md")
          a-content (multi-str
                     "- do foo"
                     "- follow @b.md")
          b-file (h/file-path "/fake/b.md")
          b-content (multi-str
                     "- do bar")]
      (with-redefs [llm-api/refine-file-context (fn [p _l]
                                                  (condp = p
                                                    a-file a-content
                                                    b-file b-content))]
        (is (match?
             (m/in-any-order
              [{:type :agents-file
                :path a-file
                :content a-content}
               {:type :agents-file
                :path b-file
                :content b-content}])
             (#'f.context/parse-agents-file a-file))))))
  (testing "Single absolute path inclusion"
    (let [a-file (h/file-path "/fake/AGENTS.md")
          a-content (multi-str
                     "- do foo"
                     "@/fake/src/b.md is where the nice things live")
          b-file (h/file-path "/fake/src/b.md")
          b-content (multi-str
                     "- do bar")]
      (with-redefs [llm-api/refine-file-context (fn [p _l]
                                                  (condp = (h/file-path p)
                                                    a-file a-content
                                                    b-file b-content))]
        (is (match?
             (m/in-any-order
              [{:type :agents-file
                :path a-file
                :content a-content}
               {:type :agents-file
                :path b-file
                :content b-content}])
             (#'f.context/parse-agents-file a-file))))))
  (testing "Multiple path inclusions with different extensions"
    (let [a-file (h/file-path "/fake/AGENTS.md")
          a-content (multi-str
                     "- do foo"
                     "- check @./src/b.md for b things"
                     "- also follow @../c.txt")
          b-file (h/file-path "/fake/src/b.md")
          b-content (multi-str
                     "- do bar")
          c-file (h/file-path "/c.txt")
          c-content (multi-str
                     "- do bazzz")]
      (with-redefs [llm-api/refine-file-context (fn [p _l]
                                                  (condp = p
                                                    a-file a-content
                                                    b-file b-content
                                                    c-file c-content))]
        (is (match?
             (m/in-any-order
              [{:type :agents-file
                :path a-file
                :content a-content}
               {:type :agents-file
                :path b-file
                :content b-content}
               {:type :agents-file
                :path c-file
                :content c-content}])
             (#'f.context/parse-agents-file a-file))))))
  (testing "Recursive path inclusions"
    (let [a-file (h/file-path "/fake/AGENTS.md")
          a-content (multi-str
                     "- do foo"
                     "- check @b.md for b things")
          b-file (h/file-path "/fake/b.md")
          b-content (multi-str
                     "- check @../c.md")
          c-file (h/file-path "/c.md")
          c-content (multi-str
                     "- do bazzz")]
      (with-redefs [llm-api/refine-file-context (fn [p _l]
                                                  (condp = p
                                                    a-file a-content
                                                    b-file b-content
                                                    c-file c-content))]
        (is (match?
             (m/in-any-order
              [{:type :agents-file
                :path a-file
                :content a-content}
               {:type :agents-file
                :path b-file
                :content b-content}
               {:type :agents-file
                :path c-file
                :content c-content}])
             (#'f.context/parse-agents-file a-file))))))
  (testing "Multiple mentions to same file include it once"
    (let [a-file (h/file-path "/fake/AGENTS.md")
          a-content (multi-str
                     "- do foo"
                     "- check @b.md for b things"
                     "- make sure you check @b.md for sure")
          b-file (h/file-path "/fake/b.md")
          b-content (multi-str
                     "- yeah")]
      (with-redefs [llm-api/refine-file-context (fn [p _l]
                                                  (condp = p
                                                    a-file a-content
                                                    b-file b-content))]
        (is (match?
             (m/in-any-order
              [{:type :agents-file
                :path a-file
                :content a-content}
               {:type :agents-file
                :path b-file
                :content b-content}])
             (#'f.context/parse-agents-file a-file)))))))
