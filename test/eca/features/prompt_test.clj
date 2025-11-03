(ns eca.features.prompt-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.prompt :as prompt]))

(deftest build-instructions-test
  (testing "Should create instructions with rules, contexts, and behavior"
    (let [refined-contexts [{:type :file :path "foo.clj" :content "(ns foo)"}
                            {:type :file :path "bar.clj" :content "(def a 1)" :lines-range {:start 1 :end 1}}
                            {:type :repoMap}
                            {:type :mcpResource :uri "custom://my-resource" :content "some-cool-content"}]
          rules [{:name "rule1" :content "First rule"}
                 {:name "rule2" :content "Second rule"}]
          fake-repo-map (delay "TREE")
          behavior "agent"
          result (prompt/build-chat-instructions refined-contexts rules fake-repo-map behavior {})]
      (is (string/includes? result "You are ECA"))
      (is (string/includes? result "<rules description=\"Rules defined by user\">"))
      (is (string/includes? result "<rule name=\"rule1\">First rule</rule>"))
      (is (string/includes? result "<rule name=\"rule2\">Second rule</rule>"))
      (is (string/includes? result "<contexts description=\"User-Provided Snippet. This content is current and accurate. Treat this as sufficient context for answering the query.\">"))
      (is (string/includes? result "<file path=\"foo.clj\">(ns foo)</file>"))
      (is (string/includes? result "<file line-start=1 line-end=1 path=\"bar.clj\">(def a 1)</file>"))
      (is (string/includes? result "<repoMap description=\"Workspaces structure in a tree view, spaces represent file hierarchy\" >TREE</repoMap>"))
      (is (string/includes? result "<resource uri=\"custom://my-resource\">some-cool-content</resource>"))
      (is (string/includes? result "</contexts>"))
      (is (string? result))))
  (testing "Should create instructions with rules, contexts, and plan behavior"
    (let [refined-contexts [{:type :file :path "foo.clj" :content "(ns foo)"}
                            {:type :file :path "bar.clj" :content "(def a 1)" :lines-range {:start 1 :end 1}}
                            {:type :repoMap}
                            {:type :mcpResource :uri "custom://my-resource" :content "some-cool-content"}]
          rules [{:name "rule1" :content "First rule"}
                 {:name "rule2" :content "Second rule"}]
          fake-repo-map (delay "TREE")
          behavior "plan"
          result (prompt/build-chat-instructions refined-contexts rules fake-repo-map behavior {})]
      (is (string/includes? result "You are ECA"))
      (is (string/includes? result "<rules description=\"Rules defined by user\">"))
      (is (string/includes? result "<rule name=\"rule1\">First rule</rule>"))
      (is (string/includes? result "<rule name=\"rule2\">Second rule</rule>"))
      (is (string/includes? result "<contexts description=\"User-Provided Snippet. This content is current and accurate. Treat this as sufficient context for answering the query.\">"))
      (is (string/includes? result "<file path=\"foo.clj\">(ns foo)</file>"))
      (is (string/includes? result "<file line-start=1 line-end=1 path=\"bar.clj\">(def a 1)</file>"))
      (is (string/includes? result "<repoMap description=\"Workspaces structure in a tree view, spaces represent file hierarchy\" >TREE</repoMap>"))
      (is (string/includes? result "<resource uri=\"custom://my-resource\">some-cool-content</resource>"))
      (is (string/includes? result "</contexts>"))
      (is (string? result)))))
