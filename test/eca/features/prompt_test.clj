(ns eca.features.prompt-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.prompt :as prompt]
   [eca.test-helper :as h]))

(defn ^:private build-instructions
  [refined-contexts static-rules path-scoped-rules skills repo-map* agent-name config chat-id all-tools db]
  (let [db (cond-> db
             (empty? (:workspace-folders db))
             (assoc :workspace-folders [{:uri "file:///workspace"}]))]
    (prompt/build-chat-instructions refined-contexts static-rules path-scoped-rules skills repo-map* agent-name config chat-id all-tools db)))

(deftest contexts-attribute-formatting-test
  (testing "keeps copy-sensitive attribute values raw while preserving quote delimiters"
    (let [result (prompt/contexts-str [{:type :file
                                        :path "/tmp/a&b<c>.clj"
                                        :content "content"}
                                       {:type :file
                                        :path "/tmp/has\"quote.clj"
                                        :content "content"}
                                       {:type :file
                                        :path "/tmp/has\"both'quotes.clj"
                                        :content "content"}]
                                      nil nil)]
      (is (string/includes? result "path=\"/tmp/a&b<c>.clj\""))
      (is (string/includes? result "path='/tmp/has\"quote.clj'"))
      (is (string/includes? result "path=\"/tmp/has&quot;both'quotes.clj\"")))))

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
                  {:name "grill-me" :description "Use when user mentions \"grill me\"."}
                  {:name "lint-fix" :description "Fix a lint"}]
          fake-repo-map (delay "TREE")
          agent-name "code"
          config {}
          {:keys [static dynamic]} (build-instructions refined-contexts static-rules path-scoped-rules skills fake-repo-map agent-name config nil [{:full-name "eca__fetch_rule"}] (h/db))]
      (is (string/includes? static "You are ECA"))
      (is (string/includes? static "<rules description=\"Rules defined by user. Follow them as closely as possible.\">"))
      (is (string/includes? static "<global-rules description=\"Global user rules; project rules are more specific.\">"))
      (is (string/includes? static "<project-rules description=\"Workspace rules; prefer over global rules when they conflict.\">"))
      (is (string/includes? static "<rule name=\"rule1\">First rule</rule>"))
      (is (string/includes? static "<rule name=\"rule2\">Second rule</rule>"))
      (is (string/includes? static "<path-scoped-rules description=\"Rules that apply to matching file paths. Use fetch_rule before actions required by enforce (read, modify, or both). Each rule only needs to be fetched once per chat.\">"))
      (is (string/includes? static "<global-path-scoped-rules description=\"Path-scoped rules loaded outside the current workspace.\">"))
      (is (string/includes? static "<workspace-path-scoped-rules root=\"/workspace/a\">"))
      (is (string/includes? static "<rule id=\"/workspace/a/.eca/rules/format.md\" name=\"format.md\" scope=\"project\" workspace-root=\"/workspace/a\" paths=\"**/*.clj\" enforce=\"modify\"/>"))
      (is (string/includes? static "<rule id=\"/home/user/.config/eca/rules/no-network.md\" name=\"no-network.md\" scope=\"global\" paths=\"src/**/*.clj\" enforce=\"modify\"/>"))
      (is (string/includes? static "<skill name=\"review-pr\" description=\"Review a PR\"/>"))
      (is (string/includes? static "<skill name=\"grill-me\" description='Use when user mentions \"grill me\".'/>"))
      (is (string/includes? static "<skill name=\"lint-fix\" description=\"Fix a lint\"/>"))
      (is (string/includes? static "<contexts description=\"User-provided context. Treat as current and accurate.\">"))
      (is (string/includes? static "<file path=\"foo.clj\">(ns foo)</file>"))
      (is (string/includes? static "<file line-start=\"1\" line-end=\"1\" path=\"bar.clj\">(def a 1)</file>"))
      (is (string/includes? static "<repo-map description=\"Workspace structure tree; spaces represent file hierarchy.\">TREE</repo-map>"))
      (is (string/includes? static "</contexts>"))
      (is (string/includes? static "## Workspace Roots"))
      (is (string/includes? static "<workspace-roots description=\"Workspace roots used for path resolution and tool scoping.\">"))
      (is (string/includes? static "<workspace-root path="))
      (is (string/includes? static "## Path Resolution"))
      (is (string/includes? static "Use workspace roots as the base for resolving relative paths."))
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
      (is (string/includes? static "<contexts description=\"User-provided context. Treat as current and accurate.\">"))
      (is (string/includes? static "<file path=\"foo.clj\">(ns foo)</file>"))
      (is (string/includes? static "<file line-start=\"1\" line-end=\"1\" path=\"bar.clj\">(def a 1)</file>"))
      (is (string/includes? static "<repo-map description=\"Workspace structure tree; spaces represent file hierarchy.\">TREE</repo-map>"))
      (is (string/includes? static "</contexts>"))
      (is (string/includes? static "## Workspace Roots"))
      (is (string/includes? static "<workspace-roots description=\"Workspace roots used for path resolution and tool scoping.\">"))
      (is (string/includes? static "<workspace-root path="))
      (is (string/includes? static "## Path Resolution"))
      (is (string/includes? static "Use workspace roots as the base for resolving relative paths."))
      (is (nil? dynamic) "dynamic should be nil when no volatile contexts or MCP servers"))))

(deftest build-instructions-empty-workspace-roots-test
  (testing "omits Workspace Roots section when there are no workspace folders, but keeps Path Resolution"
    (let [db (assoc (h/db) :workspace-folders [])
          {:keys [static]} (prompt/build-chat-instructions [] [] [] [] (delay "TREE") "code" {} nil [] db)]
      (is (not (string/includes? static "## Workspace Roots")))
      (is (not (string/includes? static "<workspace-roots")))
      (is (string/includes? static "## Path Resolution")))))

(deftest build-instructions-startup-context-test
  (testing "chatStart startup-context renders even without any stable contexts"
    (let [db (assoc-in (h/db) [:chats "chat-1" :startup-context] "injected by chatStart")
          {:keys [static]} (build-instructions [] [] [] [] (delay "TREE") "code" {} "chat-1" [] db)]
      (is (string/includes? static "<contexts description=\"User-provided context. Treat as current and accurate.\">"))
      (is (string/includes? static "<additional-context>\ninjected by chatStart\n</additional-context>"))))

  (testing "no Contexts section when there are neither stable contexts nor startup-context"
    (let [{:keys [static]} (build-instructions [] [] [] [] (delay "TREE") "code" {} "chat-1" [] (h/db))]
      (is (not (string/includes? static "<contexts description"))))))

(deftest build-instructions-skip-empty-rule-group-test
  (testing "omits empty global rules section when only project-scoped rules render"
    (let [static-rules [{:name "rule1" :content "Only project rule" :scope :project}]
          {:keys [static]} (build-instructions [] static-rules [] [] (delay "TREE") "code" {} nil [] (h/db))]
      (is (string/includes? static "<project-rules description=\"Workspace rules; prefer over global rules when they conflict.\">"))
      (is (not (string/includes? static "<global-rules"))))))

(deftest build-instructions-path-scoped-only-test
  (testing "renders only the path-scoped catalog when there are no static rules"
    (let [path-scoped-rules [{:id "/workspace/a/.eca/rules/format.md"
                              :name "format.md"
                              :scope :project
                              :workspace-root "/workspace/a"
                              :paths ["**/*.clj" "**/*.cljs"]}]
          {:keys [static]} (build-instructions [] [] path-scoped-rules [] (delay "TREE") "code" {} nil [{:full-name "eca__fetch_rule"}] (h/db))]
      (is (string/includes? static "<path-scoped-rules description=\"Rules that apply to matching file paths. Use fetch_rule before actions required by enforce (read, modify, or both). Each rule only needs to be fetched once per chat.\">"))
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
      (is (string/includes? dynamic "Use test-server for testing"))
      (is (string/includes? dynamic "## MCP Server Instructions"))))

  (testing "cursor context is excluded from both :static and :dynamic (delivered in the user message)"
    (let [refined-contexts [{:type :file :path "foo.clj" :content "(ns foo)"}
                            {:type :cursor :path "bar.clj"
                             :position {:start {:line 10 :character 0}
                                        :end {:line 10 :character 5}}}]
          {:keys [static dynamic]} (build-instructions refined-contexts [] [] [] (delay "TREE") "code" {} nil [] (h/db))]
      (is (string/includes? static "<file path=\"foo.clj\">(ns foo)</file>"))
      (is (not (string/includes? static "cursor")))
      (is (nil? dynamic))))

  (testing "mcpResource context goes into :dynamic, not :static"
    (let [refined-contexts [{:type :file :path "foo.clj" :content "(ns foo)"}
                            {:type :mcpResource :uri "custom://my-resource" :content "volatile-content"}]
          {:keys [static dynamic]} (build-instructions refined-contexts [] [] [] (delay "TREE") "code" {} nil [] (h/db))]
      (is (string/includes? static "<file path=\"foo.clj\">(ns foo)</file>"))
      (is (not (string/includes? static "volatile-content")))
      (is (string? dynamic))
      (is (string/includes? dynamic "volatile-content")))))

(deftest build-editor-state-context-test
  (testing "renders the cursor context block"
    (let [result (prompt/build-editor-state-context
                  [{:type :cursor :path "bar.clj"
                    :position {:start {:line 10 :character 0}
                               :end {:line 10 :character 5}}}])]
      (is (string? result))
      (is (string/includes? result "<editor-state description=\"Editor state reference; not a user request. Use only when relevant.\">"))
      (is (string/includes? result "<cursor"))
      (is (string/includes? result "bar.clj"))
      (is (string/includes? result "10:0"))
      (is (string/includes? result "10:5"))))
  (testing "ignores non-editor-state contexts and returns nil when none"
    (is (nil? (prompt/build-editor-state-context [])))
    (is (nil? (prompt/build-editor-state-context [{:type :file :path "foo.clj" :content "(ns foo)"}])))
    (is (nil? (prompt/build-editor-state-context [{:type :mcpResource :uri "custom://x" :content "c"}])))))

(deftest instructions->str-test
  (testing "flattens map with both parts to joined string separated by a blank line"
    (is (= "static\n\ndynamic" (prompt/instructions->str {:static "static" :dynamic "dynamic"}))))
  (testing "flattens map with nil dynamic to just static"
    (is (= "static" (prompt/instructions->str {:static "static" :dynamic nil})))))

(deftest build-instructions-context-sections-ordering-test
  (testing "Context section is labeled and rendered after Environment, workspace, and path sections"
    (let [refined-contexts [{:type :file :path "foo.clj" :content "(ns foo)"}]
          {:keys [static]} (build-instructions refined-contexts [] [] [] (delay "TREE") "code" {} nil [] (h/db))]
      (is (string/includes? static "## Context"))
      (is (string/includes? static "## Environment Context"))
      (is (string/includes? static "## Workspace Roots"))
      (is (string/includes? static "## Path Resolution"))
      (is (< (string/index-of static "## Environment Context")
             (string/index-of static "## Workspace Roots")
             (string/index-of static "## Path Resolution")
             (string/index-of static "\n## Context")))))
  (testing "Static context comes before MCP resources in the flattened prompt"
    (let [refined-contexts [{:type :file :path "foo.clj" :content "(ns foo)"}
                            {:type :mcpResource :uri "custom://my-resource" :content "volatile-content"}]
          flat (prompt/instructions->str
                (build-instructions refined-contexts [] [] [] (delay "TREE") "code" {} nil [] (h/db)))]
      (is (< (string/index-of flat "\n## Context")
             (string/index-of flat "## MCP Resources"))))))

(deftest instructions->str-no-double-blank-line-test
  (testing "static-to-dynamic boundary has exactly one blank line, not two, when no Context section"
    (let [refined-contexts [{:type :mcpResource :uri "custom://my-resource" :content "volatile-content"}]
          flat (prompt/instructions->str
                (build-instructions refined-contexts [] [] [] (delay "TREE") "code" {} nil [] (h/db)))]
      (is (string/includes? flat "## Path Resolution"))
      (is (string/includes? flat "## MCP Resources"))
      (is (not (string/includes? flat "\n\n\n## MCP Resources"))
          "there should be exactly one blank line before ## MCP Resources, not two"))))

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
