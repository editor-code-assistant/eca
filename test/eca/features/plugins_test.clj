(ns eca.features.plugins-test
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
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
                 (get-in result [:config-fragment :hooks :PostToolUse 0 :hooks 0 :command])))
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
