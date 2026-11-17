(ns eca.modules.agents-md-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.context :as f.context]
   [eca.llm-api :as llm-api]
   [eca.modules.agents-md :as m.agents-md]
   [eca.shared :as shared :refer [multi-str]]
   [eca.test-helper :as h]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

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
             (#'m.agents-md/parse-agents-file a-file))))))
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
             (#'m.agents-md/parse-agents-file a-file))))))
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
             (#'m.agents-md/parse-agents-file a-file))))))
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
             (#'m.agents-md/parse-agents-file a-file))))))
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
             (#'m.agents-md/parse-agents-file a-file))))))
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
             (#'m.agents-md/parse-agents-file a-file)))))))
(testing "Missing referenced file is ignored"
  (let [a-file (h/file-path "/fake/AGENTS.md")
        a-content (multi-str
                   "- do foo"
                   "- check @missing.md for missing things")
        missing-file (h/file-path "/fake/missing.md")]
    (with-redefs [llm-api/refine-file-context (fn [p _l]
                                                (condp = p
                                                  a-file a-content
                                                  missing-file nil))]
      (is (match?
           (m/in-any-order
            [{:type :agents-file
              :path a-file
              :content a-content}])
           (#'m.agents-md/parse-agents-file a-file))))))

(deftest agents-file-contexts-test
  (testing "Flag disabled: only the workspace's own AGENTS.md plus global"
    (h/reset-components!)
    (let [ws "/a/b/c/d"
          ws-agents (str ws "/AGENTS.md")
          global-dir "/global"
          global-agents (str global-dir "/AGENTS.md")
          readable #{ws-agents global-agents}]
      (swap! (h/db*) assoc :workspace-folders [{:uri "file:///a/b/c/d"}])
      (with-redefs [shared/uri->filename (constantly ws)
                    shared/global-config-dir (constantly global-dir)
                    fs/canonicalize identity
                    fs/path (fn [& parts] (string/join "/" (map str parts)))
                    fs/readable? (fn [p] (contains? readable (str p)))
                    llm-api/refine-file-context (constantly "content")]
        (is (match?
             [{:type :agents-file :path ws-agents :content "content"}
              {:type :agents-file :path global-agents :content "content"}]
             (m.agents-md/agents-file-contexts (h/db) {:includeParentAgentsFiles false}))))))

  (testing "Flag enabled: parents emitted outermost first, then workspace, then global"
    (h/reset-components!)
    (let [ws "/a/b/c/d"
          parent-ab-agents "/a/b/AGENTS.md"
          parent-abc-agents "/a/b/c/AGENTS.md"
          global-dir "/global"
          global-agents (str global-dir "/AGENTS.md")
          readable #{parent-ab-agents parent-abc-agents global-agents}
          parent-map {"/a/b/c/d" "/a/b/c"
                      "/a/b/c" "/a/b"
                      "/a/b" "/a"
                      "/a" "/"
                      "/" nil}]
      (swap! (h/db*) assoc :workspace-folders [{:uri "file:///a/b/c/d"}])
      (with-redefs [shared/uri->filename (constantly ws)
                    shared/global-config-dir (constantly global-dir)
                    fs/canonicalize identity
                    fs/parent (fn [p] (get parent-map (str p)))
                    fs/path (fn [& parts] (string/join "/" (map str parts)))
                    fs/readable? (fn [p] (contains? readable (str p)))
                    llm-api/refine-file-context (constantly "content")]
        (is (match?
             [{:type :agents-file :path parent-ab-agents :content "content"}
              {:type :agents-file :path parent-abc-agents :content "content"}
              {:type :agents-file :path global-agents :content "content"}]
             (m.agents-md/agents-file-contexts (h/db) {:includeParentAgentsFiles true}))))))

  (testing "Flag enabled but no parent has AGENTS.md: behaves like disabled"
    (h/reset-components!)
    (let [ws "/a/b/c/d"
          ws-agents (str ws "/AGENTS.md")
          global-dir "/global"
          global-agents (str global-dir "/AGENTS.md")
          readable #{ws-agents global-agents}
          parent-map {"/a/b/c/d" "/a/b/c"
                      "/a/b/c" "/a/b"
                      "/a/b" "/a"
                      "/a" "/"
                      "/" nil}]
      (swap! (h/db*) assoc :workspace-folders [{:uri "file:///a/b/c/d"}])
      (with-redefs [shared/uri->filename (constantly ws)
                    shared/global-config-dir (constantly global-dir)
                    fs/canonicalize identity
                    fs/parent (fn [p] (get parent-map (str p)))
                    fs/path (fn [& parts] (string/join "/" (map str parts)))
                    fs/readable? (fn [p] (contains? readable (str p)))
                    llm-api/refine-file-context (constantly "content")]
        (is (match?
             [{:type :agents-file :path ws-agents :content "content"}
              {:type :agents-file :path global-agents :content "content"}]
             (m.agents-md/agents-file-contexts (h/db) {:includeParentAgentsFiles true}))))))

  (testing "Two nested workspaces share ancestors via dedup"
    (h/reset-components!)
    (let [a-agents "/a/AGENTS.md"
          ab-agents "/a/b/AGENTS.md"
          abc-agents "/a/b/c/AGENTS.md"
          global-dir "/global"
          global-agents (str global-dir "/AGENTS.md")
          readable #{a-agents ab-agents abc-agents global-agents}
          parent-map {"/a/b/c/d" "/a/b/c"
                      "/a/b/c" "/a/b"
                      "/a/b" "/a"
                      "/a" "/"
                      "/" nil}
          uri->filename-map {"file:///a/b" "/a/b"
                             "file:///a/b/c/d" "/a/b/c/d"}]
      (swap! (h/db*) assoc :workspace-folders [{:uri "file:///a/b"}
                                               {:uri "file:///a/b/c/d"}])
      (with-redefs [shared/uri->filename (fn [u] (get uri->filename-map u))
                    shared/global-config-dir (constantly global-dir)
                    fs/canonicalize identity
                    fs/parent (fn [p] (get parent-map (str p)))
                    fs/path (fn [& parts] (string/join "/" (map str parts)))
                    fs/readable? (fn [p] (contains? readable (str p)))
                    llm-api/refine-file-context (constantly "content")]
        (is (match?
             [{:type :agents-file :path a-agents}
              {:type :agents-file :path ab-agents}
              {:type :agents-file :path abc-agents}
              {:type :agents-file :path global-agents}]
             (m.agents-md/agents-file-contexts (h/db) {:includeParentAgentsFiles true}))))))

  (testing "Workspace at filesystem root: no parent walk"
    (h/reset-components!)
    (let [ws "/"
          ws-agents "//AGENTS.md"
          global-dir "/global"
          global-agents (str global-dir "/AGENTS.md")
          readable #{ws-agents global-agents}
          parent-map {"/" nil}]
      (swap! (h/db*) assoc :workspace-folders [{:uri "file:///"}])
      (with-redefs [shared/uri->filename (constantly ws)
                    shared/global-config-dir (constantly global-dir)
                    fs/canonicalize identity
                    fs/parent (fn [p] (get parent-map (str p)))
                    fs/path (fn [& parts] (string/join "/" (map str parts)))
                    fs/readable? (fn [p] (contains? readable (str p)))
                    llm-api/refine-file-context (constantly "content")]
        (is (match?
             [{:type :agents-file :path ws-agents}
              {:type :agents-file :path global-agents}]
             (m.agents-md/agents-file-contexts (h/db) {:includeParentAgentsFiles true})))))))

(deftest always-contexts-test
  (testing "every chat gets the AGENTS.md files through core's always contexts, unless the module is off"
    (with-redefs [m.agents-md/agents-file-contexts (fn [_db _config]
                                                     [{:type :agents-file :path "/w/AGENTS.md" :content "Be nice"}])]
      (is (= [{:type :agents-file :path "/w/AGENTS.md" :content "Be nice"}]
             (f.context/always-contexts (h/db) {})))
      (is (empty? (f.context/always-contexts (h/db) {:modules {:eca/agents-md {:enabled false}}}))))))
