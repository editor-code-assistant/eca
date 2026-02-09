(ns eca.features.prompt-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.prompt :as prompt]
   [eca.test-helper :as h]))

(deftest build-instructions-test
  (testing "Should create instructions with rules, contexts, and build agent"
    (let [refined-contexts [{:type :file :path "foo.clj" :content "(ns foo)"}
                            {:type :file :path "bar.clj" :content "(def a 1)" :lines-range {:start 1 :end 1}}
                            {:type :repoMap}
                            {:type :mcpResource :uri "custom://my-resource" :content "some-cool-content"}]
          rules [{:name "rule1" :content "First rule"}
                 {:name "rule2" :content "Second rule"}]
          skills [{:name "review-pr" :description "Review a PR"}
                  {:name "lint-fix" :description "Fix a lint"}]
          fake-repo-map (delay "TREE")
          agent-name "code"
          config {}
          result (prompt/build-chat-instructions refined-contexts rules skills fake-repo-map agent-name config nil [] (h/db))]
      (is (string/includes? result "You are ECA"))
      (is (string/includes? result "<rules description=\"Rules defined by user\">"))
      (is (string/includes? result "<rule name=\"rule1\">First rule</rule>"))
      (is (string/includes? result "<rule name=\"rule2\">Second rule</rule>"))
      (is (string/includes? result "<skill name=\"review-pr\" description=\"Review a PR\"/>"))
      (is (string/includes? result "<skill name=\"lint-fix\" description=\"Fix a lint\"/>"))
      (is (string/includes? result "<contexts description=\"User-Provided. This content is current and accurate. Treat this as sufficient context for answering the query.\">"))
      (is (string/includes? result "<file path=\"foo.clj\">(ns foo)</file>"))
      (is (string/includes? result "<file line-start=1 line-end=1 path=\"bar.clj\">(def a 1)</file>"))
      (is (string/includes? result "<repoMap description=\"Workspaces structure in a tree view, spaces represent file hierarchy\" >TREE</repoMap>"))
      (is (string/includes? result "<resource uri=\"custom://my-resource\">some-cool-content</resource>"))
      (is (string/includes? result "</contexts>"))
      (is (string? result))))
  (testing "Should create instructions with rules, contexts, and plan agent"
    (let [refined-contexts [{:type :file :path "foo.clj" :content "(ns foo)"}
                            {:type :file :path "bar.clj" :content "(def a 1)" :lines-range {:start 1 :end 1}}
                            {:type :repoMap}
                            {:type :mcpResource :uri "custom://my-resource" :content "some-cool-content"}]
          rules [{:name "rule1" :content "First rule"}
                 {:name "rule2" :content "Second rule"}]
          skills [{:name "review-pr" :description "Review a PR"}
                  {:name "lint-fix" :description "Fix a lint"}]
          fake-repo-map (delay "TREE")
          agent-name "plan"
          config {}
          result (prompt/build-chat-instructions refined-contexts rules skills fake-repo-map agent-name config nil [] (h/db))]
      (is (string/includes? result "You are ECA"))
      (is (string/includes? result "<rules description=\"Rules defined by user\">"))
      (is (string/includes? result "<rule name=\"rule1\">First rule</rule>"))
      (is (string/includes? result "<rule name=\"rule2\">Second rule</rule>"))
      (is (string/includes? result "<skill name=\"review-pr\" description=\"Review a PR\"/>"))
      (is (string/includes? result "<skill name=\"lint-fix\" description=\"Fix a lint\"/>"))
      (is (string/includes? result "<contexts description=\"User-Provided. This content is current and accurate. Treat this as sufficient context for answering the query.\">"))
      (is (string/includes? result "<file path=\"foo.clj\">(ns foo)</file>"))
      (is (string/includes? result "<file line-start=1 line-end=1 path=\"bar.clj\">(def a 1)</file>"))
      (is (string/includes? result "<repoMap description=\"Workspaces structure in a tree view, spaces represent file hierarchy\" >TREE</repoMap>"))
      (is (string/includes? result "<resource uri=\"custom://my-resource\">some-cool-content</resource>"))
      (is (string/includes? result "</contexts>"))
      (is (string? result)))))
