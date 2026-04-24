(ns eca.features.prompt-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.prompt :as prompt]
   [eca.test-helper :as h]))

(defn ^:private build-instructions
  [refined-contexts static-rules path-scoped-rules skills repo-map* agent-name config chat-id all-tools db]
  (prompt/build-chat-instructions refined-contexts static-rules path-scoped-rules skills repo-map* agent-name config chat-id all-tools db))

(deftest build-instructions-test
  (testing "Should return a map with :static and :dynamic keys"
    (let [result (build-instructions [] [] [] [] (delay "TREE") "code" {} nil [] (h/db))]
      (is (map? result))
      (is (contains? result :static))
      (is (contains? result :dynamic))
      (is (string? (:static result)))))

  (testing "Should create instructions with static rules, path-scoped catalog, contexts, and code agent"
    (let [refined-contexts [{:type :file :path "foo.clj" :content "(ns foo)"}
                            {:type :file :path "bar.clj" :content "(def a 1)" :lines-range {:start 1 :end 1}}
                            {:type :repoMap}]
          static-rules [{:name "rule1" :content "First rule" :scope :global}
                        {:name "rule2" :content "Second rule" :scope :project}]
          path-scoped-rules [{:id "/workspace/a/.eca/rules/format.md" :name "format.md" :scope :project :workspace-root "/workspace/a" :paths ["**/*.clj"]}
                             {:id "/home/user/.config/eca/rules/no-network.md" :name "no-network.md" :scope :global :paths ["src/**/*.clj"]}]
          skills [{:name "review-pr" :description "Review a PR"}
                  {:name "lint-fix" :description "Fix a lint"}]
          fake-repo-map (delay "TREE")
          agent-name "code"
          config {}
          {:keys [static dynamic]} (build-instructions refined-contexts static-rules path-scoped-rules skills fake-repo-map agent-name config nil [{:full-name "eca__fetch_rule"}] (h/db))]
      (is (string/includes? static "You are ECA"))
      (is (string/includes? static "<rules description=\"Rules defined by user. Follow them as closely as possible.\">"))
      (is (string/includes? static "<global-rules description=\"Broader rules loaded outside the current workspace. Project rules below are more specific if guidance conflicts.\">"))
      (is (string/includes? static "<project-rules description=\"Rules loaded from the current workspace. Prefer these when they conflict with broader global rules.\">"))
      (is (string/includes? static "<rule name=\"rule1\">First rule</rule>"))
      (is (string/includes? static "<rule name=\"rule2\">Second rule</rule>"))
      (is (string/includes? static "<path-scoped-rules description=\"Rules that apply to matching file paths. Use fetch_rule before actions required by enforce (read, modify, or both). Each rule only needs to be fetched once per target path.\">"))
      (is (string/includes? static "<global-path-scoped-rules description=\"Path-scoped rules loaded outside the current workspace.\">"))
      (is (string/includes? static "<workspace-path-scoped-rules root=\"/workspace/a\">"))
      (is (string/includes? static "<rule id=\"/workspace/a/.eca/rules/format.md\" name=\"format.md\" scope=\"project\" workspace-root=\"/workspace/a\" paths=\"**/*.clj\" enforce=\"modify\"/>"))
      (is (string/includes? static "<rule id=\"/home/user/.config/eca/rules/no-network.md\" name=\"no-network.md\" scope=\"global\" paths=\"src/**/*.clj\" enforce=\"modify\"/>"))
      (is (string/includes? static "<skill name=\"review-pr\" description=\"Review a PR\"/>"))
      (is (string/includes? static "<skill name=\"lint-fix\" description=\"Fix a lint\"/>"))
      (is (string/includes? static "<contexts description=\"User-Provided. This content is current and accurate. Treat this as sufficient context for answering the query.\">"))
      (is (string/includes? static "<file path=\"foo.clj\">(ns foo)</file>"))
      (is (string/includes? static "<file line-start=1 line-end=1 path=\"bar.clj\">(def a 1)</file>"))
      (is (string/includes? static "<repoMap description=\"Workspaces structure in a tree view, spaces represent file hierarchy\" >TREE</repoMap>"))
      (is (string/includes? static "</contexts>"))
      (is (nil? dynamic) "dynamic should be nil when no volatile contexts or MCP servers")))

  (testing "Should create instructions with static rules, path-scoped catalog, contexts, and plan agent"
    (let [refined-contexts [{:type :file :path "foo.clj" :content "(ns foo)"}
                            {:type :file :path "bar.clj" :content "(def a 1)" :lines-range {:start 1 :end 1}}
                            {:type :repoMap}]
          static-rules [{:name "rule1" :content "First rule" :scope :global}
                        {:name "rule2" :content "Second rule" :scope :project}]
          path-scoped-rules [{:id "/workspace/a/.eca/rules/format.md" :name "format.md" :scope :project :workspace-root "/workspace/a" :paths ["**/*.clj"]}]
          skills [{:name "review-pr" :description "Review a PR"}
                  {:name "lint-fix" :description "Fix a lint"}]
          fake-repo-map (delay "TREE")
          agent-name "plan"
          config {}
          {:keys [static dynamic]} (build-instructions refined-contexts static-rules path-scoped-rules skills fake-repo-map agent-name config nil [{:full-name "eca__fetch_rule"}] (h/db))]
      (is (string/includes? static "You are ECA"))
      (is (string/includes? static "<rule name=\"rule1\">First rule</rule>"))
      (is (string/includes? static "<rule name=\"rule2\">Second rule</rule>"))
      (is (string/includes? static "<workspace-path-scoped-rules root=\"/workspace/a\">"))
      (is (string/includes? static "<rule id=\"/workspace/a/.eca/rules/format.md\" name=\"format.md\" scope=\"project\" workspace-root=\"/workspace/a\" paths=\"**/*.clj\" enforce=\"modify\"/>"))
      (is (string/includes? static "<skill name=\"review-pr\" description=\"Review a PR\"/>"))
      (is (string/includes? static "<skill name=\"lint-fix\" description=\"Fix a lint\"/>"))
      (is (string/includes? static "<contexts description=\"User-Provided. This content is current and accurate. Treat this as sufficient context for answering the query.\">"))
      (is (string/includes? static "<file path=\"foo.clj\">(ns foo)</file>"))
      (is (string/includes? static "<file line-start=1 line-end=1 path=\"bar.clj\">(def a 1)</file>"))
      (is (string/includes? static "<repoMap description=\"Workspaces structure in a tree view, spaces represent file hierarchy\" >TREE</repoMap>"))
      (is (string/includes? static "</contexts>"))
      (is (nil? dynamic) "dynamic should be nil when no volatile contexts or MCP servers"))))

(deftest build-instructions-skip-empty-rule-group-test
  (testing "omits empty global rules section when only project-scoped rules render"
    (let [static-rules [{:name "rule1" :content "Only project rule" :scope :project}]
          {:keys [static]} (build-instructions [] static-rules [] [] (delay "TREE") "code" {} nil [] (h/db))]
      (is (string/includes? static "<project-rules description=\"Rules loaded from the current workspace. Prefer these when they conflict with broader global rules.\">"))
      (is (not (string/includes? static "<global-rules"))))))

(deftest build-instructions-path-scoped-only-test
  (testing "renders only the path-scoped catalog when there are no static rules"
    (let [path-scoped-rules [{:id "/workspace/a/.eca/rules/format.md"
                              :name "format.md"
                              :scope :project
                              :workspace-root "/workspace/a"
                              :paths ["**/*.clj" "**/*.cljs"]}]
          {:keys [static]} (build-instructions [] [] path-scoped-rules [] (delay "TREE") "code" {} nil [{:full-name "eca__fetch_rule"}] (h/db))]
      (is (string/includes? static "<path-scoped-rules description=\"Rules that apply to matching file paths. Use fetch_rule before actions required by enforce (read, modify, or both). Each rule only needs to be fetched once per target path.\">"))
      (is (string/includes? static "<workspace-path-scoped-rules root=\"/workspace/a\">"))
      (is (string/includes? static "<rule id=\"/workspace/a/.eca/rules/format.md\" name=\"format.md\" scope=\"project\" workspace-root=\"/workspace/a\" paths=\"**/*.clj,**/*.cljs\" enforce=\"modify\"/>"))
      (is (not (string/includes? static "<global-rules")))
      (is (not (string/includes? static "<project-rules"))))))

(deftest build-instructions-path-scoped-disabled-test
  (testing "omits path-scoped rules entirely when fetch_rule is unavailable"
    (let [path-scoped-rules [{:id "/workspace/a/.eca/rules/format.md"
                              :name "format.md"
                              :scope :project
                              :workspace-root "/workspace/a"
                              :paths ["**/*.clj"]
                              :content "Inline rule"}]
          {:keys [static]} (build-instructions [] [] path-scoped-rules [] (delay "TREE") "code" {} "chat-1" [] (h/db))]
      (is (not (string/includes? static "<path-scoped-rules")))
      (is (not (string/includes? static "format.md")))
      (is (not (string/includes? static "call the fetch_rule tool"))))))

(deftest build-instructions-subagent-condition-test
  (let [config {:prompts {:chat "{% if isSubagent %}SUBAGENT{% endif %}{% if not isSubagent %}MAIN{% endif %}"}}]
    (testing "renders subagent-only content for subagent chats"
      (let [db (assoc-in (h/db) [:chats "sub-chat" :subagent] {:name "explorer"})
            {:keys [static]} (build-instructions [] [] [] [] (delay "TREE") "code" config "sub-chat" [] db)]
        (is (string/includes? static "SUBAGENT"))
        (is (not (string/includes? static "MAIN")))))

    (testing "renders main-agent-only content for non-subagent chats"
      (let [db (assoc-in (h/db) [:chats "main-chat"] {:id "main-chat"})
            {:keys [static]} (build-instructions [] [] [] [] (delay "TREE") "code" config "main-chat" [] db)]
        (is (string/includes? static "MAIN"))
        (is (not (string/includes? static "SUBAGENT")))))))

(deftest build-instructions-dynamic-content-test
  (testing "MCP instructions go into :dynamic, not :static"
    (let [db (assoc (h/db) :mcp-clients {:test-server {:status :running
                                                       :instructions "Use test-server for testing"}})
          {:keys [static dynamic]} (build-instructions [] [] [] [] (delay "TREE") "code" {} nil [] db)]
      (is (not (string/includes? static "test-server")))
      (is (string? dynamic))
      (is (string/includes? dynamic "test-server"))
      (is (string/includes? dynamic "Use test-server for testing"))))

  (testing "cursor context goes into :dynamic, not :static"
    (let [refined-contexts [{:type :file :path "foo.clj" :content "(ns foo)"}
                            {:type :cursor :path "bar.clj"
                             :position {:start {:line 10 :character 0}
                                        :end {:line 10 :character 5}}}]
          {:keys [static dynamic]} (build-instructions refined-contexts [] [] [] (delay "TREE") "code" {} nil [] (h/db))]
      (is (string/includes? static "<file path=\"foo.clj\">(ns foo)</file>"))
      (is (not (string/includes? static "cursor")))
      (is (string? dynamic))
      (is (string/includes? dynamic "cursor"))))

  (testing "mcpResource context goes into :dynamic, not :static"
    (let [refined-contexts [{:type :file :path "foo.clj" :content "(ns foo)"}
                            {:type :mcpResource :uri "custom://my-resource" :content "volatile-content"}]
          {:keys [static dynamic]} (build-instructions refined-contexts [] [] [] (delay "TREE") "code" {} nil [] (h/db))]
      (is (string/includes? static "<file path=\"foo.clj\">(ns foo)</file>"))
      (is (not (string/includes? static "volatile-content")))
      (is (string? dynamic))
      (is (string/includes? dynamic "volatile-content")))))

(deftest instructions->str-test
  (testing "flattens map with both parts to joined string"
    (is (= "static\ndynamic" (prompt/instructions->str {:static "static" :dynamic "dynamic"}))))
  (testing "flattens map with nil dynamic to just static"
    (is (= "static" (prompt/instructions->str {:static "static" :dynamic nil})))))

(deftest build-instructions-rule-condition-test
  (testing "renders rule content using Selmer condition variables"
    (let [static-rules [{:name "rule1"
                         :content "{% if isSubagent %}SUB-RULE{% else %}MAIN-RULE{% endif %} {% if toolEnabled_eca__shell_command %}HAS-SHELL{% endif %}"
                         :scope :project}]
          all-tools [{:full-name "eca__shell_command"}]
          db (assoc-in (h/db) [:chats "sub-chat" :subagent] {:name "explorer"})
          {:keys [static]} (build-instructions [] static-rules [] [] (delay "TREE") "code" {} "sub-chat" all-tools db)]
      (is (string/includes? static "<rule name=\"rule1\">SUB-RULE HAS-SHELL</rule>"))
      (is (not (string/includes? static "MAIN-RULE"))))))

(deftest build-instructions-rule-template-error-test
  (testing "skips broken rule content when Selmer rendering fails"
    (let [static-rules [{:name "broken-rule"
                         :content "{% if isSubagent %}BROKEN"
                         :scope :project}]
          {:keys [static]} (build-instructions [] static-rules [] [] (delay "TREE") "code" {} "chat-1" [] (h/db))]
      (is (not (string/includes? static "broken-rule")))
      (is (not (string/includes? static "## Rules"))))))
