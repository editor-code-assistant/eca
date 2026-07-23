(ns eca.features.plugins-test
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [eca.config :as config]
   [eca.features.commands :as commands]
   [eca.features.plugins :as plugins]
   [eca.features.rules :as rules]
   [eca.interpolation :as interpolation]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(use-fixtures :each
  (fn [t]
    (interpolation/reset-plugin-dirs!)
    (try
      (t)
      (finally
        (interpolation/reset-plugin-dirs!)))))

(deftest sanitize-source-url-test
  (testing "HTTPS URL"
    (is (= "github.com-my-org-my-plugins"
           (#'plugins/sanitize-source-url "https://github.com/my-org/my-plugins.git"))))
  (testing "SSH URL"
    (is (= "github.com-my-org-my-plugins"
           (#'plugins/sanitize-source-url "git@github.com:my-org/my-plugins.git"))))
  (testing "GitLab URL"
    (is (= "gitlab.com-org-repo"
           (#'plugins/sanitize-source-url "https://gitlab.com/org/repo.git")))))

(deftest git-url?-test
  (testing "recognizes HTTPS URLs"
    (is (true? (#'plugins/git-url? "https://github.com/org/repo.git"))))
  (testing "recognizes SSH URLs"
    (is (true? (#'plugins/git-url? "git@github.com:org/repo.git"))))
  (testing "rejects local paths"
    (is (false? (#'plugins/git-url? "/home/user/plugins")))
    (is (false? (#'plugins/git-url? "./relative/path")))))

(deftest read-marketplace-test
  (let [tmp-dir (fs/create-temp-dir)]
    (try
      (testing "reads valid marketplace.json"
        (let [eca-plugin-dir (fs/file tmp-dir ".eca-plugin")]
          (fs/create-dirs eca-plugin-dir)
          (spit (fs/file eca-plugin-dir "marketplace.json")
                (json/generate-string
                 {:plugins [{:name "test-plugin"
                             :description "A test plugin"
                             :source "./plugins/test/test-plugin"
                             :category "development"
                             :version "1.0.0"}
                            {:name "mcp-plugin"
                             :description "An MCP plugin"
                             :source "./plugins/test/mcp-plugin"
                             :category "mcp"
                             :version "1.0.0"
                             :mcpServers "./plugins/test/mcp-plugin/.mcp.json"}]}))
          (is (match? [{:name "test-plugin"
                        :description "A test plugin"
                        :source "./plugins/test/test-plugin"}
                       {:name "mcp-plugin"
                        :source "./plugins/test/mcp-plugin"}]
                      (#'plugins/read-marketplace (fs/file tmp-dir))))))

      (testing "returns nil for missing marketplace.json"
        (let [empty-dir (fs/file tmp-dir "empty")]
          (fs/create-dirs empty-dir)
          (is (nil? (#'plugins/read-marketplace (fs/file empty-dir))))))

      (testing "returns nil for malformed JSON"
        (let [bad-dir (fs/file tmp-dir "bad")]
          (fs/create-dirs (fs/file bad-dir ".eca-plugin"))
          (spit (fs/file bad-dir ".eca-plugin" "marketplace.json") "{invalid json")
          (is (nil? (#'plugins/read-marketplace (fs/file bad-dir))))))
      (finally
        (fs/delete-tree tmp-dir)))))

(deftest discover-components-test
  (let [tmp-dir (fs/create-temp-dir)
        plugin-dir (fs/file tmp-dir "test-plugin")]
    (try
      (testing "discovers skills dir in config-fragment"
        (fs/create-dirs (fs/file plugin-dir "skills" "my-skill"))
        (spit (fs/file plugin-dir "skills" "my-skill" "SKILL.md")
              "---\nname: my-skill\ndescription: Test\n---\nBody")
        (let [result (#'plugins/discover-components (fs/file plugin-dir))]
          (is (= 1 (count (get-in result [:config-fragment :pluginSkillDirs]))))))

      (testing "discovers agents as parsed config"
        (fs/create-dirs (fs/file plugin-dir "agents"))
        (spit (fs/file plugin-dir "agents" "test-agent.md")
              "---\nname: test-agent\ndescription: A helper agent\n---\nAgent prompt body")
        (let [result (#'plugins/discover-components (fs/file plugin-dir))]
          (is (match? {"test-agent" {:description "A helper agent"
                                     :systemPrompt "Agent prompt body"}}
                      (:agents result)))))

      (testing "skips README.md in agents dir"
        (spit (fs/file plugin-dir "agents" "README.md") "# Docs")
        (let [result (#'plugins/discover-components (fs/file plugin-dir))]
          (is (not (contains? (:agents result) "readme")))))

      (testing "discovers commands as path entries"
        (fs/create-dirs (fs/file plugin-dir "commands"))
        (spit (fs/file plugin-dir "commands" "my-cmd.md")
              "---\ndescription: A command\n---\nCommand body")
        (let [result (#'plugins/discover-components (fs/file plugin-dir))]
          (is (= 1 (count (:commands result))))
          (is (string? (:path (first (:commands result)))))))

      (testing "attaches plugin name to commands when provided"
        (let [result (#'plugins/discover-components (fs/file plugin-dir) "my-plugin")]
          (is (match? (m/embeds [{:plugin "my-plugin"
                                  :path string?}])
                      (:commands result)))))

      (testing "attaches plugin name to skill dirs when provided"
        (let [result (#'plugins/discover-components (fs/file plugin-dir) "my-plugin")]
          (is (match? (m/embeds [{:plugin "my-plugin"
                                  :dir string?}])
                      (get-in result [:config-fragment :pluginSkillDirs])))))

      (testing "discovers rules as path entries"
        (fs/create-dirs (fs/file plugin-dir "rules"))
        (spit (fs/file plugin-dir "rules" "my-rule.mdc")
              "---\ndescription: A rule\nglobs: \"**/*.md\"\n---\nRule body")
        (let [result (#'plugins/discover-components (fs/file plugin-dir))]
          (is (= 1 (count (:rules result))))
          (is (string? (:path (first (:rules result)))))))

      (testing "discovers MCP servers in config-fragment"
        (spit (fs/file plugin-dir ".mcp.json")
              (json/generate-string
               {:mcpServers {"test-server" {:type "http"
                                            :url "https://example.com/mcp"}}}))
        (let [result (#'plugins/discover-components (fs/file plugin-dir))]
          (is (match? {:test-server {:url "https://example.com/mcp"}}
                      (get-in result [:config-fragment :mcpServers])))))

      (testing "discovers eca.json config overrides in config-fragment"
        (spit (fs/file plugin-dir "eca.json")
              (json/generate-string {:mcpTimeoutSeconds 30}))
        (let [result (#'plugins/discover-components (fs/file plugin-dir))]
          (is (= 30 (get-in result [:config-fragment :mcpTimeoutSeconds])))))

      (testing "returns empty for non-existent directories"
        (let [empty-plugin (fs/file tmp-dir "empty-plugin")]
          (fs/create-dirs empty-plugin)
          (let [result (#'plugins/discover-components (fs/file empty-plugin))]
            (is (empty? (:agents result)))
            (is (empty? (:commands result)))
            (is (empty? (:rules result)))
            (is (nil? (get-in result [:config-fragment :mcpServers]))))))
      (finally
        (fs/delete-tree tmp-dir)))))

(deftest parse-sources-test
  (testing "extracts source entries, filtering install"
    (let [config {"my-org" {:source "https://github.com/my-org/my-plugins.git"}
                  "local" {:source "/home/user/plugins"}
                  "install" ["plugin-a" "plugin-b"]}]
      (is (match? (m/in-any-order [["my-org" "https://github.com/my-org/my-plugins.git"]
                                   ["local" "/home/user/plugins"]])
                  (#'plugins/parse-sources config)))))

  (testing "returns empty for no sources"
    (is (empty? (#'plugins/parse-sources {"install" ["plugin-a"]})))))

(deftest resolve-all!-test
  (let [tmp-dir (fs/create-temp-dir)]
    (try
      (testing "full resolution with local source"
        (let [source-dir (fs/file tmp-dir "repo")
              plugin-dir (fs/file source-dir "plugins" "test" "my-plugin")]
          (fs/create-dirs (fs/file source-dir ".eca-plugin"))
          (fs/create-dirs (fs/file plugin-dir "skills" "hello"))
          (fs/create-dirs (fs/file plugin-dir "agents"))
          (fs/create-dirs (fs/file plugin-dir "commands"))
          (spit (fs/file source-dir ".eca-plugin" "marketplace.json")
                (json/generate-string
                 {:plugins [{:name "my-plugin"
                             :description "Test"
                             :source "./plugins/test/my-plugin"}]}))
          (spit (fs/file plugin-dir "skills" "hello" "SKILL.md")
                "---\nname: hello\ndescription: Greet\n---\nSay hello")
          (spit (fs/file plugin-dir "agents" "helper.md")
                "---\nname: helper\ndescription: Helps\n---\nHelp prompt")
          (spit (fs/file plugin-dir "commands" "do-thing.md")
                "---\ndescription: Does a thing\n---\nDo the thing")
          (spit (fs/file plugin-dir ".mcp.json")
                (json/generate-string
                 {:mcpServers {"test-mcp" {:type "http"
                                           :url "https://example.com/mcp"}}}))
          (let [result (plugins/resolve-all!
                        {"my-source" {:source (str source-dir)}
                         "install" ["my-plugin"]})]
            (is (= 1 (count (get-in result [:config-fragment :pluginSkillDirs]))))
            (is (match? (m/embeds [{:plugin "my-plugin" :dir string?}])
                        (get-in result [:config-fragment :pluginSkillDirs])))
            (is (match? {:test-mcp {:url "https://example.com/mcp"}}
                        (get-in result [:config-fragment :mcpServers])))
            (is (match? {"helper" {:description "Helps"}} (:agents result)))
            (is (= 1 (count (:commands result))))
            (is (match? (m/embeds [{:plugin "my-plugin" :path string?}])
                        (:commands result))))))

      (testing "returns empty for nil config"
        (let [result (plugins/resolve-all! nil)]
          (is (empty? (:agents result)))
          (is (empty? (:commands result)))))

      (testing "returns empty for empty install"
        (let [result (plugins/resolve-all!
                      {"my-source" {:source "/some/path"}
                       "install" []})]
          (is (empty? (:agents result)))))

      (testing "skips missing plugins gracefully"
        (let [source-dir (fs/file tmp-dir "repo2")]
          (fs/create-dirs (fs/file source-dir ".eca-plugin"))
          (spit (fs/file source-dir ".eca-plugin" "marketplace.json")
                (json/generate-string {:plugins [{:name "exists" :source "./plugins/exists"}]}))
          (let [result (plugins/resolve-all!
                        {"src" {:source (str source-dir)}
                         "install" ["does-not-exist"]})]
            (is (empty? (:agents result))))))
      (finally
        (fs/delete-tree tmp-dir)))))

(deftest resolve-all!-dependencies-test
  (let [tmp-dir (fs/create-temp-dir)
        src-a (fs/file tmp-dir "src-a")
        src-b (fs/file tmp-dir "src-b")]
    (try
      (fs/create-dirs (fs/file src-a ".eca-plugin"))
      (spit (fs/file src-a ".eca-plugin" "marketplace.json")
            (json/generate-string
             {:plugins [{:name "meta" :description "Meta" :source "./plugins/meta"
                         :dependencies ["dep-entry"]}
                        {:name "dep-entry" :description "Dep" :source "./plugins/dep-entry"}
                        {:name "manifest-meta" :description "Manifest meta" :source "./plugins/manifest-meta"}
                        {:name "cycle-a" :description "A" :source "./plugins/cycle-a"
                         :dependencies ["cycle-b"]}
                        {:name "cycle-b" :description "B" :source "./plugins/cycle-b"
                         :dependencies ["cycle-a"]}]}))
      ;; meta: pure meta-plugin (no components besides a config override)
      (fs/create-dirs (fs/file src-a "plugins" "meta"))
      (spit (fs/file src-a "plugins" "meta" "eca.json")
            (json/generate-string {:whoWins "meta"}))
      ;; dep-entry: provides a command and a conflicting config override
      (fs/create-dirs (fs/file src-a "plugins" "dep-entry" "commands"))
      (spit (fs/file src-a "plugins" "dep-entry" "commands" "dep-cmd.md")
            "---\ndescription: Dep command\n---\nDep body")
      (spit (fs/file src-a "plugins" "dep-entry" "eca.json")
            (json/generate-string {:whoWins "dep-entry"}))
      ;; manifest-meta: dependencies declared in .eca-plugin/plugin.json
      (fs/create-dirs (fs/file src-a "plugins" "manifest-meta" ".eca-plugin"))
      (spit (fs/file src-a "plugins" "manifest-meta" ".eca-plugin" "plugin.json")
            (json/generate-string {:name "manifest-meta"
                                   :dependencies ["dep-entry"
                                                  "remote-dep@src-b"
                                                  "ghost@nowhere"
                                                  "ghost"]}))
      ;; cycle plugins depending on each other
      (fs/create-dirs (fs/file src-a "plugins" "cycle-a" "commands"))
      (spit (fs/file src-a "plugins" "cycle-a" "commands" "cycle-a-cmd.md")
            "---\ndescription: Cycle A\n---\nA body")
      (fs/create-dirs (fs/file src-a "plugins" "cycle-b" "commands"))
      (spit (fs/file src-a "plugins" "cycle-b" "commands" "cycle-b-cmd.md")
            "---\ndescription: Cycle B\n---\nB body")
      ;; second source
      (fs/create-dirs (fs/file src-b ".eca-plugin"))
      (spit (fs/file src-b ".eca-plugin" "marketplace.json")
            (json/generate-string
             {:plugins [{:name "remote-dep" :description "Remote" :source "./plugins/remote-dep"}]}))
      (fs/create-dirs (fs/file src-b "plugins" "remote-dep" "agents"))
      (spit (fs/file src-b "plugins" "remote-dep" "agents" "remote-helper.md")
            "---\nname: remote-helper\ndescription: Remote helper\n---\nRemote prompt")

      (testing "dependencies from the marketplace entry are loaded"
        (let [result (plugins/resolve-all!
                      {"src-a" {:source (str src-a)}
                       "install" ["meta"]})]
          (is (match? (m/embeds [{:plugin "dep-entry" :path string?}])
                      (:commands result)))
          (testing "directly installed plugin wins config conflicts over its dependencies"
            (is (= "meta" (get-in result [:config-fragment :whoWins]))))))

      (testing "dependencies from .eca-plugin/plugin.json load, cross-marketplace refs work, unknown deps are skipped"
        (let [result (plugins/resolve-all!
                      {"src-a" {:source (str src-a)}
                       "src-b" {:source (str src-b)}
                       "install" ["manifest-meta"]})]
          (is (match? (m/embeds [{:plugin "dep-entry" :path string?}])
                      (:commands result)))
          (is (match? {"remote-helper" {:description "Remote helper"}}
                      (:agents result)))))

      (testing "dependency cycles terminate, loading each plugin once"
        (let [result (plugins/resolve-all!
                      {"src-a" {:source (str src-a)}
                       "install" ["cycle-a"]})]
          (is (match? (m/in-any-order [{:plugin "cycle-a" :path string?}
                                       {:plugin "cycle-b" :path string?}])
                      (:commands result)))))

      (testing "shared dependencies are loaded once"
        (let [result (plugins/resolve-all!
                      {"src-a" {:source (str src-a)}
                       "src-b" {:source (str src-b)}
                       "install" ["meta" "manifest-meta"]})]
          (is (= 1 (count (filter #(= "dep-entry" (:plugin %)) (:commands result)))))))

      (testing "dependency already explicitly installed is not loaded twice"
        (let [result (plugins/resolve-all!
                      {"src-a" {:source (str src-a)}
                       "install" ["dep-entry" "meta"]})]
          (is (= 1 (count (filter #(= "dep-entry" (:plugin %)) (:commands result)))))
          (testing "later explicit install wins config conflicts per install order"
            (is (= "meta" (get-in result [:config-fragment :whoWins]))))))
      (finally
        (fs/delete-tree tmp-dir)))))

(deftest plugin-root-interpolation-test
  (let [tmp-dir (fs/create-temp-dir)]
    (try
      (let [source-dir (fs/file tmp-dir "repo")
            plugin-dir (fs/file source-dir "plugins" "test" "demo")
            secret "line \"quoted\"\nbackslash \\ ok"]
        (fs/create-dirs (fs/file source-dir ".eca-plugin"))
        (fs/create-dirs (fs/file plugin-dir "hooks"))
        (fs/create-dirs (fs/file plugin-dir "commands"))
        (fs/create-dirs (fs/file plugin-dir "rules"))
        (spit (fs/file source-dir ".eca-plugin" "marketplace.json")
              (json/generate-string
               {:plugins [{:name "demo"
                           :description "Demo"
                           :source "./plugins/test/demo"}]}))
        (spit (fs/file plugin-dir "secret.txt") secret)
        (spit (fs/file plugin-dir ".mcp.json")
              (json/generate-string
               {:mcpServers {"local" {:command "${plugin:root}/bin/server"}}}))
        (spit (fs/file plugin-dir "eca.json")
              (json/generate-string
               {:pluginRoot "${plugin:root}"
                :quotedSecret "${file:secret.txt}"}))
        (spit (fs/file plugin-dir "hooks" "hooks.json")
              (json/generate-string
               {:PostToolUse [{:hooks [{:type "command"
                                         :command "node ${plugin:root}/hooks/check.js"}]}]}))
        (spit (fs/file plugin-dir "commands" "where.md")
              "Plugin command: ${plugin:root}")
        (spit (fs/file plugin-dir "rules" "where.md")
              "Plugin rule: ${plugin:root}")
        (let [plugin-root (str (fs/canonicalize plugin-dir))
              result (plugins/resolve-all!
                      {"local" {:source (str source-dir)}
                       "install" ["demo"]})]
          (is (= (str plugin-root "/bin/server")
                 (get-in result [:config-fragment :mcpServers :local :command])))
          (is (= plugin-root
                 (get-in result [:config-fragment :pluginRoot])))
          (is (= secret
                 (get-in result [:config-fragment :quotedSecret])))
          (is (= (str "node " plugin-root "/hooks/check.js")
                 (get-in result [:config-fragment :hooks "demo::PostToolUse" 0 :hooks 0 :command])))
          (is (not-any? #(string/includes? (str %) ":::")
                        (keys (get-in result [:config-fragment :hooks]))))
          (let [loaded-commands (vec (#'commands/custom-commands
                                      {:pureConfig true
                                       :commands (:commands result)}
                                      []))]
            (is (= [(str "Plugin command: " plugin-root)]
                   (mapv :content loaded-commands))))
          (let [loaded-rules (vec (#'rules/config-rules {:rules (:rules result)} []))]
            (is (= [(str "Plugin rule: " plugin-root)]
                   (mapv :content loaded-rules))))))
      (finally
        (fs/delete-tree tmp-dir)))))

(deftest merge-components-test
  (testing "merges multiple plugin components"
    (let [c1 {:config-fragment {:mcpServers {:server-a {:url "http://a"}}
                                :hooks {"hook-a" {:type "postToolCall"}}
                                :pluginSkillDirs ["/a/skills"]
                                :mcpTimeoutSeconds 30}
              :agents {"agent-a" {:description "A"}}
              :commands [{:path "/a/commands/cmd.md"}]
              :rules []}
          c2 {:config-fragment {:mcpServers {:server-b {:url "http://b"}}
                                :pluginSkillDirs ["/b/skills"]
                                :mcpTimeoutSeconds 60}
              :agents {"agent-b" {:description "B"}}
              :commands []
              :rules [{:path "/b/rules/rule.mdc"}]}
          result (#'plugins/merge-components [c1 c2])]
      (is (= ["/a/skills" "/b/skills"]
             (get-in result [:config-fragment :pluginSkillDirs])))
      (is (match? {:server-a {:url "http://a"}
                   :server-b {:url "http://b"}}
                  (get-in result [:config-fragment :mcpServers])))
      (is (= 60 (get-in result [:config-fragment :mcpTimeoutSeconds])))
      (is (match? {"agent-a" {:description "A"}
                   "agent-b" {:description "B"}}
                  (:agents result)))
      (is (= [{:path "/a/commands/cmd.md"}] (:commands result)))
      (is (= [{:path "/b/rules/rule.mdc"}] (:rules result))))))

(deftest plugin-agent-with-claude-tools-list-becomes-primary-test
  (let [tmp-dir (fs/create-temp-dir)
        source-dir (fs/file tmp-dir "repo")
        plugin-dir (fs/file source-dir "plugins" "test" "design-review")
        prev-plugin-components @config/plugin-components*
        prev-init-config @config/initialization-config*]
    (try
      (fs/create-dirs (fs/file source-dir ".eca-plugin"))
      (fs/create-dirs (fs/file plugin-dir "agents"))
      (spit (fs/file source-dir ".eca-plugin" "marketplace.json")
            (json/generate-string
             {:plugins [{:name "design-review"
                         :description "Design review plugin"
                         :source "./plugins/test/design-review"}]}))
      ;; Lucas's exact reproducer: Claude-style frontmatter with tools-as-list.
      (spit (fs/file plugin-dir "agents" "glp-engineer.agent.md")
            (str "---\n"
                 "name: GLP-Reviewer\n"
                 "description: \"Review any design document with DRC-style structured feedback and rubric scoring\"\n"
                 "tools:\n"
                 "  - read\n"
                 "  - search\n"
                 "  - agent\n"
                 "---\n\n"
                 "Role & Goal: review designs."))
      (let [resolved (plugins/resolve-all!
                      {"local" {:source (str source-dir)}
                       "install" ["design-review"]})]
        (testing "plugin discovery loads the Claude-style agent without dropping it"
          (is (contains? (:agents resolved) "glp-reviewer")))

        (reset! config/plugin-components* resolved)
        (reset! config/initialization-config* {:pureConfig false})
        (let [final-config (#'config/all* {:workspace-folders []})
              primaries (set (config/primary-agent-names final-config))]
          (testing "tools list is normalized to byDefault=ask + allow"
            (is (match? {:approval {:byDefault "ask"
                                    :allow {"read" {}
                                            "search" {}
                                            "agent" {}}}}
                        (get-in final-config [:agent "glp-reviewer" :toolCall]))))
          (testing "agent appears in primary-agent-names"
            (is (contains? primaries "glp-reviewer")))))
      (finally
        (reset! config/plugin-components* prev-plugin-components)
        (reset! config/initialization-config* prev-init-config)
        (fs/delete-tree tmp-dir)))))

(deftest plugin-agent-without-mode-becomes-primary-test
  (let [tmp-dir (fs/create-temp-dir)
        source-dir (fs/file tmp-dir "repo")
        plugin-dir (fs/file source-dir "plugins" "test" "doc-authoring")
        prev-plugin-components @config/plugin-components*
        prev-init-config @config/initialization-config*]
    (try
      (fs/create-dirs (fs/file source-dir ".eca-plugin"))
      (fs/create-dirs (fs/file plugin-dir "agents"))
      (spit (fs/file source-dir ".eca-plugin" "marketplace.json")
            (json/generate-string
             {:plugins [{:name "doc-authoring"
                         :description "Test plugin"
                         :source "./plugins/test/doc-authoring"}]}))
      ;; Claude-style plugin agent: only `name` and `description` in frontmatter, no `mode`.
      ;; Filename uses the `*.agent.md` convention seen in real marketplace plugins.
      (spit (fs/file plugin-dir "agents" "architect.agent.md")
            (str "---\n"
                 "name: Architect\n"
                 "description: Designs system architecture\n"
                 "---\n\n"
                 "Role & Goal: shape architectural decisions."))
      (let [resolved (plugins/resolve-all!
                      {"local" {:source (str source-dir)}
                       "install" ["doc-authoring"]})]
        (testing "discovery picks up the plugin agent under the YAML `name:` id"
          (is (contains? (:agents resolved) "architect"))
          (is (= "Designs system architecture"
                 (get-in resolved [:agents "architect" :description])))
          (is (nil? (get-in resolved [:agents "architect" :mode]))))

        (reset! config/plugin-components* resolved)
        (reset! config/initialization-config* {:pureConfig false})
        (let [final-config (#'config/all* {:workspace-folders []})
              primaries (set (config/primary-agent-names final-config))]
          (testing "agent reaches the merged :agent map without :mode"
            (is (= "Designs system architecture"
                   (get-in final-config [:agent "architect" :description])))
            (is (nil? (get-in final-config [:agent "architect" :mode]))))
          (testing "plugin agent without mode appears in primary-agent-names"
            (is (contains? primaries "architect")))
          (testing "built-in primary agents remain available"
            (is (contains? primaries "code"))
            (is (contains? primaries "plan")))))
      (finally
        (reset! config/plugin-components* prev-plugin-components)
        (reset! config/initialization-config* prev-init-config)
        (fs/delete-tree tmp-dir)))))

(deftest uninstall-plugin!-test
  (testing "removes plugin from install list"
    (let [updated (atom nil)]
      (with-redefs [config/update-global-config! (fn [c] (reset! updated c))]
        (let [result (plugins/uninstall-plugin!
                      {"install" ["alpha" "beta" "gamma"]}
                      "beta")]
          (is (= :ok (:status result)))
          (is (= ["alpha" "gamma"] (get-in @updated [:plugins :install])))))))

  (testing "returns error when plugin is not installed"
    (let [result (plugins/uninstall-plugin!
                  {"install" ["alpha"]}
                  "beta")]
      (is (= :error (:status result)))
      (is (re-find #"not installed" (:message result)))))

  (testing "returns error when install list is empty"
    (let [result (plugins/uninstall-plugin!
                  {"install" []}
                  "beta")]
      (is (= :error (:status result))))))
