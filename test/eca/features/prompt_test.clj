(ns eca.features.prompt-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.prompt :as prompt]
   [eca.test-helper :as h]))

(deftest build-instructions-test
  (testing "Should return a map with :static and :dynamic keys"
    (let [result (prompt/build-chat-instructions [] [] [] (delay "TREE") "code" {} nil [] (h/db))]
      (is (map? result))
      (is (contains? result :static))
      (is (contains? result :dynamic))
      (is (string? (:static result)))))
  (testing "Should create instructions with rules, contexts, and code agent"
    (let [refined-contexts [{:type :file :path "foo.clj" :content "(ns foo)"}
                            {:type :file :path "bar.clj" :content "(def a 1)" :lines-range {:start 1 :end 1}}
                            {:type :repoMap}]
          rules [{:name "rule1" :content "First rule"}
                 {:name "rule2" :content "Second rule"}]
          skills [{:name "review-pr" :description "Review a PR"}
                  {:name "lint-fix" :description "Fix a lint"}]
          fake-repo-map (delay "TREE")
          agent-name "code"
          config {}
          {:keys [static dynamic]} (prompt/build-chat-instructions refined-contexts rules skills fake-repo-map agent-name config nil [] (h/db))]
      (is (string/includes? static "You are ECA"))
      (is (string/includes? static "<rules description=\"Rules defined by user\">"))
      (is (string/includes? static "<rule name=\"rule1\">First rule</rule>"))
      (is (string/includes? static "<rule name=\"rule2\">Second rule</rule>"))
      (is (string/includes? static "<skill name=\"review-pr\" description=\"Review a PR\"/>"))
      (is (string/includes? static "<skill name=\"lint-fix\" description=\"Fix a lint\"/>"))
      (is (string/includes? static "<contexts description=\"User-Provided. This content is current and accurate. Treat this as sufficient context for answering the query.\">"))
      (is (string/includes? static "<file path=\"foo.clj\">(ns foo)</file>"))
      (is (string/includes? static "<file line-start=1 line-end=1 path=\"bar.clj\">(def a 1)</file>"))
      (is (string/includes? static "<repoMap description=\"Workspaces structure in a tree view, spaces represent file hierarchy\" >TREE</repoMap>"))
      (is (string/includes? static "</contexts>"))
      (is (nil? dynamic) "dynamic should be nil when no volatile contexts or MCP servers")))
  (testing "Should create instructions with rules, contexts, and plan agent"
    (let [refined-contexts [{:type :file :path "foo.clj" :content "(ns foo)"}
                            {:type :file :path "bar.clj" :content "(def a 1)" :lines-range {:start 1 :end 1}}
                            {:type :repoMap}]
          rules [{:name "rule1" :content "First rule"}
                 {:name "rule2" :content "Second rule"}]
          skills [{:name "review-pr" :description "Review a PR"}
                  {:name "lint-fix" :description "Fix a lint"}]
          fake-repo-map (delay "TREE")
          agent-name "plan"
          config {}
          {:keys [static dynamic]} (prompt/build-chat-instructions refined-contexts rules skills fake-repo-map agent-name config nil [] (h/db))]
      (is (string/includes? static "You are ECA"))
      (is (string/includes? static "<rules description=\"Rules defined by user\">"))
      (is (string/includes? static "<rule name=\"rule1\">First rule</rule>"))
      (is (string/includes? static "<rule name=\"rule2\">Second rule</rule>"))
      (is (string/includes? static "<skill name=\"review-pr\" description=\"Review a PR\"/>"))
      (is (string/includes? static "<skill name=\"lint-fix\" description=\"Fix a lint\"/>"))
      (is (string/includes? static "<contexts description=\"User-Provided. This content is current and accurate. Treat this as sufficient context for answering the query.\">"))
      (is (string/includes? static "<file path=\"foo.clj\">(ns foo)</file>"))
      (is (string/includes? static "<file line-start=1 line-end=1 path=\"bar.clj\">(def a 1)</file>"))
      (is (string/includes? static "<repoMap description=\"Workspaces structure in a tree view, spaces represent file hierarchy\" >TREE</repoMap>"))
      (is (string/includes? static "</contexts>"))
      (is (nil? dynamic) "dynamic should be nil when no volatile contexts or MCP servers"))))

(deftest build-instructions-subagent-condition-test
  (let [config {:prompts {:chat "{% if isSubagent %}SUBAGENT{% endif %}{% if not isSubagent %}MAIN{% endif %}"}}]
    (testing "renders subagent-only content for subagent chats"
      (let [db (assoc-in (h/db) [:chats "sub-chat" :subagent] {:name "explorer"})
            {:keys [static]} (prompt/build-chat-instructions [] [] [] (delay "TREE") "code" config "sub-chat" [] db)]
        (is (string/includes? static "SUBAGENT"))
        (is (not (string/includes? static "MAIN")))))

    (testing "renders main-agent-only content for non-subagent chats"
      (let [db (assoc-in (h/db) [:chats "main-chat"] {:id "main-chat"})
            {:keys [static]} (prompt/build-chat-instructions [] [] [] (delay "TREE") "code" config "main-chat" [] db)]
        (is (string/includes? static "MAIN"))
        (is (not (string/includes? static "SUBAGENT")))))))

(deftest build-instructions-dynamic-content-test
  (testing "MCP instructions go into :dynamic, not :static"
    (let [db (assoc (h/db) :mcp-clients {:test-server {:status :running
                                                        :instructions "Use test-server for testing"}})
          {:keys [static dynamic]} (prompt/build-chat-instructions [] [] [] (delay "TREE") "code" {} nil [] db)]
      (is (not (string/includes? static "test-server")))
      (is (string? dynamic))
      (is (string/includes? dynamic "test-server"))
      (is (string/includes? dynamic "Use test-server for testing"))))

  (testing "cursor context goes into :dynamic, not :static"
    (let [refined-contexts [{:type :file :path "foo.clj" :content "(ns foo)"}
                            {:type :cursor :path "bar.clj"
                             :position {:start {:line 10 :character 0}
                                        :end {:line 10 :character 5}}}]
          {:keys [static dynamic]} (prompt/build-chat-instructions refined-contexts [] [] (delay "TREE") "code" {} nil [] (h/db))]
      (is (string/includes? static "<file path=\"foo.clj\">(ns foo)</file>"))
      (is (not (string/includes? static "cursor")))
      (is (string? dynamic))
      (is (string/includes? dynamic "cursor"))))

  (testing "mcpResource context goes into :dynamic, not :static"
    (let [refined-contexts [{:type :file :path "foo.clj" :content "(ns foo)"}
                            {:type :mcpResource :uri "custom://my-resource" :content "volatile-content"}]
          {:keys [static dynamic]} (prompt/build-chat-instructions refined-contexts [] [] (delay "TREE") "code" {} nil [] (h/db))]
      (is (string/includes? static "<file path=\"foo.clj\">(ns foo)</file>"))
      (is (not (string/includes? static "volatile-content")))
      (is (string? dynamic))
      (is (string/includes? dynamic "volatile-content")))))

(deftest instructions->str-test
  (testing "flattens map with both parts to joined string"
    (is (= "static\ndynamic" (prompt/instructions->str {:static "static" :dynamic "dynamic"}))))
  (testing "flattens map with nil dynamic to just static"
    (is (= "static" (prompt/instructions->str {:static "static" :dynamic nil})))))
