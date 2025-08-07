(ns eca.features.prompt-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.prompt :as prompt]))

(deftest build-instructions-test
  (testing "Should create instructions with rules, contexts, and behavior"
    (let [refined-contexts [{:type :file :path "foo.clj" :content "(ns foo)"}
                            {:type :file :path "bar.clj" :content "(def a 1)" :partial true}
                            {:type :repoMap}
                            {:type :mcpResource :uri "custom://my-resource" :content "some-cool-content"}]
          rules [{:name "rule1" :content "First rule"}
                 {:name "rule2" :content "Second rule"}]
          fake-repo-map (delay "TREE")
          behavior "agent"
          result (prompt/build-instructions refined-contexts rules fake-repo-map behavior {})]
      (is (string/includes? result "You are ECA"))
      (is (string/includes? result "<rules>"))
      (is (string/includes? result "<rule name=\"rule1\">First rule</rule>"))
      (is (string/includes? result "<rule name=\"rule2\">Second rule</rule>"))
      (is (string/includes? result "<contexts>"))
      (is (string/includes? result "<file path=\"foo.clj\">(ns foo)</file>"))
      (is (string/includes? result "<file partial=true path=\"bar.clj\">...\n(def a 1)\n...</file>"))
      (is (string/includes? result "<repoMap description=\"Workspaces structure in a tree view, spaces represent file hierarchy\" >TREE</repoMap>"))
      (is (string/includes? result "<resource uri=\"custom://my-resource\">some-cool-content</resource>"))
      (is (string/includes? result "</contexts>"))
      (is (string? result))))
  (testing "Should create instructions with rules, contexts, and plan behavior"
    (let [refined-contexts [{:type :file :path "foo.clj" :content "(ns foo)"}
                            {:type :file :path "bar.clj" :content "(def a 1)" :partial true}
                            {:type :repoMap}
                            {:type :mcpResource :uri "custom://my-resource" :content "some-cool-content"}]
          rules [{:name "rule1" :content "First rule"}
                 {:name "rule2" :content "Second rule"}]
          fake-repo-map (delay "TREE")
          behavior "plan"
          result (prompt/build-instructions refined-contexts rules fake-repo-map behavior {})]
      (is (string/includes? result "You are ECA"))
      (is (string/includes? result "<rules>"))
      (is (string/includes? result "<rule name=\"rule1\">First rule</rule>"))
      (is (string/includes? result "<rule name=\"rule2\">Second rule</rule>"))
      (is (string/includes? result "<contexts>"))
      (is (string/includes? result "<file path=\"foo.clj\">(ns foo)</file>"))
      (is (string/includes? result "<file partial=true path=\"bar.clj\">...\n(def a 1)\n...</file>"))
      (is (string/includes? result "<repoMap description=\"Workspaces structure in a tree view, spaces represent file hierarchy\" >TREE</repoMap>"))
      (is (string/includes? result "<resource uri=\"custom://my-resource\">some-cool-content</resource>"))
      (is (string/includes? result "</contexts>"))
      (is (string? result)))))
