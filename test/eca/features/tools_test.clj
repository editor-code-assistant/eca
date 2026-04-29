(ns eca.features.tools-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [clojure.test :refer [are deftest is testing]]
   [eca.config :as config]
   [eca.features.rules :as f.rules]
   [eca.features.tools :as f.tools]
   [eca.features.tools.filesystem :as f.tools.filesystem]
   [eca.features.tools.mcp :as f.mcp]
   [eca.test-helper :as h]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(deftest all-tools-test
  (testing "Include mcp tools"
    (is (match?
         (m/embeds [{:name "eval"
                     :server {:name "clojureMCP" :version "1.0.2"}
                     :description "eval code"
                     :parameters {"type" "object"
                                  :properties {"code" {:type "string"}}}
                     :origin :mcp}])
         (f.tools/all-tools "123" "code"
                            {:mcp-clients {"clojureMCP"
                                           {:version "1.0.2"
                                            :tools [{:name "eval"
                                                     :description "eval code"
                                                     :parameters {"type" "object"
                                                                  :properties {"code" {:type "string"}}}}]}}}
                            {}))))
  (testing "Include enabled native tools"
    (is (match?
         (m/embeds [{:name "directory_tree"
                     :server {:name "eca"}
                     :description string?
                     :parameters some?
                     :origin :native}])
         (f.tools/all-tools "123" "code" {} {}))))

  (testing "Subagent excludes spawn_agent, task, git, and ask_user tools"
    (let [db {:chats {"sub-1" {:subagent {:name "explorer"}}}}
          tools (f.tools/all-tools "sub-1" "code" db {})
          tool-names (set (map :name tools))]
      (is (not (contains? tool-names "spawn_agent")))
      (is (not (contains? tool-names "task")))
      (is (not (contains? tool-names "git")))
      (is (not (contains? tool-names "ask_user")))))

  (testing "Do not include disabled native tools"
    (is (match?
         (m/embeds [(m/mismatch {:name "directory_tree"})])
         (f.tools/all-tools "123" "code" {} {:disabledTools ["directory_tree"]}))))
  (testing "Plan mode includes preview tool but excludes mutating tools"
    (let [plan-config {:agent {"plan" {:disabledTools ["edit_file" "write_file" "move_file"]}}}
          plan-tools (f.tools/all-tools "123" "plan" {} plan-config)
          tool-names (set (map :name plan-tools))]
      ;; Verify that preview tool is included
      (is (contains? tool-names "preview_file_change"))
      ;; Verify that shell command is now allowed in plan mode (with restrictions in prompt)
      (is (contains? tool-names "shell_command"))
      ;; Verify that mutating tools are excluded
      (is (not (contains? tool-names "edit_file")))
      (is (not (contains? tool-names "write_file")))
      (is (not (contains? tool-names "move_file")))))
  (testing "Do not include plan edit tool if code agent"
    (is (match?
         (m/embeds [(m/mismatch {:name "preview_file_change"})
                    {:name "edit_file"}])
         (f.tools/all-tools "123" "code" {} {}))))
  (testing "Replace special vars description"
    (is (match?
         (m/embeds [{:name "directory_tree"
                     :description (format "Only in %s" (h/file-path "/path/to/project/foo"))
                     :parameters some?
                     :origin :native}])
         (with-redefs [f.tools.filesystem/definitions {"directory_tree" {:description "Only in {{workspaceRoots}}"
                                                                         :parameters {}}}]
           (f.tools/all-tools "123" "code" {:workspace-folders [{:name "foo" :uri (h/file-uri "file:///path/to/project/foo")}]}
                              {})))))
  (testing "Override native tool description via global prompts config"
    (let [config {:prompts {:tools {"eca__directory_tree" "Custom global description"}}}
          tools (f.tools/all-tools "123" "code" {} config)
          tool (some #(when (= "eca__directory_tree" (:full-name %)) %) tools)]
      (is (= "Custom global description" (:description tool)))))
  (testing "Override native tool description via agent-specific prompts config"
    (let [config {:agent {"code" {:prompts {:tools {"eca__directory_tree" "Custom code description"}}}}}
          tools (f.tools/all-tools "123" "code" {} config)
          tool (some #(when (= "eca__directory_tree" (:full-name %)) %) tools)]
      (is (= "Custom code description" (:description tool)))))
  (testing "Agent-specific prompts takes precedence over global prompts"
    (let [config {:prompts {:tools {"eca__directory_tree" "Global description"}}
                  :agent {"code" {:prompts {:tools {"eca__directory_tree" "Code description"}}}}}
          tools (f.tools/all-tools "123" "code" {} config)
          tool (some #(when (= "eca__directory_tree" (:full-name %)) %) tools)]
      (is (= "Code description" (:description tool)))))
  (testing "Different agents can have different tool descriptions"
    (let [config {:agent {"code" {:prompts {:tools {"eca__directory_tree" "Code description"}}}
                          "plan" {:prompts {:tools {"eca__directory_tree" "Plan description"}}
                                  :disabledTools ["edit_file" "write_file" "move_file"]}}}
          code-tools (f.tools/all-tools "123" "code" {} config)
          plan-tools (f.tools/all-tools "123" "plan" {} config)
          code-tool (some #(when (= "eca__directory_tree" (:full-name %)) %) code-tools)
          plan-tool (some #(when (= "eca__directory_tree" (:full-name %)) %) plan-tools)]
      (is (= "Code description" (:description code-tool)))
      (is (= "Plan description" (:description plan-tool)))))
  (testing "Override MCP tool description via global prompts config"
    (let [db {:mcp-clients {"myMCP" {:version "1.0"
                                     :tools [{:name "my_tool"
                                              :description "Original MCP description"
                                              :parameters {"type" "object"}}]}}}
          config {:prompts {:tools {"myMCP__my_tool" "Custom MCP description"}}}
          tools (f.tools/all-tools "123" "code" db config)
          tool (some #(when (= "myMCP__my_tool" (:full-name %)) %) tools)]
      (is (= "Custom MCP description" (:description tool)))))
  (testing "Falls back to original description when no override specified"
    (let [config {:prompts {:tools {"eca__some_other_tool" "Override for other tool"}}}
          tools (f.tools/all-tools "123" "code" {} config)
          tool (some #(when (= "eca__directory_tree" (:full-name %)) %) tools)]
      (is (string? (:description tool)))
      (is (not= "Override for other tool" (:description tool)))))
  (testing "Falls back to global when agent has no override for specific tool"
    (let [config {:prompts {:tools {"eca__directory_tree" "Global description"}}
                  :agent {"code" {:prompts {:tools {"eca__read_file" "Code read_file description"}}}}}
          tools (f.tools/all-tools "123" "code" {} config)
          dir-tool (some #(when (= "eca__directory_tree" (:full-name %)) %) tools)
          read-tool (some #(when (= "eca__read_file" (:full-name %)) %) tools)]
      (is (= "Global description" (:description dir-tool)))
      (is (= "Code read_file description" (:description read-tool))))))

(deftest get-disabled-tools-test
  (testing "merges global and agent-specific disabled tools"
    (let [config {:disabledTools ["global_tool"]
                  :agent {"plan" {:disabledTools ["plan_tool"]}
                          "custom" {:disabledTools ["custom_tool"]}}}]
      (is (= #{"global_tool" "plan_tool"}
             (#'f.tools/get-disabled-tools config "plan")))
      (is (= #{"global_tool" "custom_tool"}
             (#'f.tools/get-disabled-tools config "custom")))))
  (testing "agent with no disabled tools"
    (let [config {:disabledTools ["global_tool"]
                  :agent {"empty" {}}}]
      (is (= #{"global_tool"}
             (#'f.tools/get-disabled-tools config "empty")))))
  (testing "nil agent returns only global disabled tools"
    (let [config {:disabledTools ["global_tool"]
                  :agent {"plan" {:disabledTools ["plan_tool"]}}}]
      (is (= #{"global_tool"}
             (#'f.tools/get-disabled-tools config nil)))))
  (testing "no global disabled tools"
    (let [config {:agent {"plan" {:disabledTools ["plan_tool"]}}}]
      (is (= #{"plan_tool"}
             (#'f.tools/get-disabled-tools config "plan"))))))

(deftest approval-test
  (let [read-tool {:name "read" :server {:name "eca"} :origin :native}
        write-tool {:name "write" :server {:name "eca"} :origin :native}
        shell-tool {:name "shell" :server {:name "eca"} :origin :native :require-approval-fn (constantly true)}
        plan-tool {:name "plan" :server {:name "eca"} :origin :native :require-approval-fn (constantly false)}
        request-tool {:name "request" :server {:name "web"} :origin :mcp}
        download-tool {:name "download" :server {:name "web"} :origin :mcp}
        all-tools [read-tool write-tool shell-tool plan-tool request-tool download-tool]]
    (testing "tool has require-approval-fn which returns true"
      (is (= :ask (f.tools/approval all-tools shell-tool {} {} {} nil))))
    (testing "tool has require-approval-fn which returns false we ignore it"
      (is (= :ask (f.tools/approval all-tools plan-tool {} {} {} nil))))
    (testing "tool was remembered to approve by user"
      (is (= :allow (f.tools/approval all-tools plan-tool {} {:tool-calls {"plan" {:remember-to-approve? true}}} {} nil))))
    (testing "remember-to-approve takes precedence over require-approval-fn"
      (is (= :allow (f.tools/approval all-tools shell-tool {} {:tool-calls {"shell" {:remember-to-approve? true}}} {} nil))))
    (testing "if legacy-manual-approval present, considers it"
      (is (= :ask (f.tools/approval all-tools request-tool {} {} {:toolCall {:manualApproval true}} nil))))
    (testing "if approval config is provided"
      (testing "when matches allow config"
        (is (= :allow (f.tools/approval all-tools request-tool {} {} {:toolCall {:approval {:allow {"web__request" {}}}}} nil)))
        (is (= :allow (f.tools/approval all-tools read-tool {} {} {:toolCall {:approval {:allow {"read" {}}}}} nil)))
        (is (= :allow (f.tools/approval all-tools request-tool {} {} {:toolCall {:approval {:allow {"web" {}}}}} nil)))
        (is (= :allow (f.tools/approval all-tools read-tool {} {} {:toolCall {:approval {:allow {"eca" {}}}}} nil))))
      (testing "when matches ask config"
        (is (= :ask (f.tools/approval all-tools request-tool {} {} {:toolCall {:approval {:ask {"web__request" {}}}}} nil)))
        (is (= :ask (f.tools/approval all-tools read-tool {} {} {:toolCall {:approval {:ask {"read" {}}}}} nil)))
        (is (= :ask (f.tools/approval all-tools request-tool {} {} {:toolCall {:approval {:ask {"web" {}}}}} nil))))
      (testing "when matches deny config"
        (is (= :deny (f.tools/approval all-tools request-tool {} {} {:toolCall {:approval {:deny {"web__request" {}}}}} nil)))
        (is (= :deny (f.tools/approval all-tools read-tool {} {} {:toolCall {:approval {:deny {"read" {}}}}} nil)))
        (is (= :deny (f.tools/approval all-tools request-tool {} {} {:toolCall {:approval {:deny {"web" {}}}}} nil))))
      (testing "when contains argsMatchers"
        (testing "has arg but not matches"
          (is (= :ask (f.tools/approval all-tools request-tool {"url" "http://bla.com"} {}
                                        {:toolCall {:approval {:allow {"web__request" {:argsMatchers {"url" [".*foo.*"]}}}}}} nil))))
        (testing "has arg and matches for allow"
          (is (= :allow (f.tools/approval all-tools request-tool {"url" "http://foo.com"} {}
                                          {:toolCall {:approval {:allow {"web__request" {:argsMatchers {"url" [".*foo.*"]}}}}}} nil)))
          (is (= :allow (f.tools/approval all-tools request-tool {"url" "foobar"} {}
                                          {:toolCall {:approval {:allow {"web__request" {:argsMatchers {"url" ["foo.*"]}}}}}} nil))))
        (testing "has arg and matches for deny"
          (is (= :deny (f.tools/approval all-tools request-tool {"url" "http://foo.com"} {}
                                         {:toolCall {:approval {:deny {"web__request" {:argsMatchers {"url" [".*foo.*"]}}}}}} nil)))
          (is (= :deny (f.tools/approval all-tools request-tool {"url" "foobar"} {}
                                         {:toolCall {:approval {:deny {"web__request" {:argsMatchers {"url" ["foo.*"]}}}}}} nil))))
        (testing "has not that arg"
          (is (= :ask (f.tools/approval all-tools request-tool {"crazy-url" "http://foo.com"} {}
                                        {:toolCall {:approval {:allow {"web__request" {:argsMatchers {"url" [".*foo.*"]}}}}}} nil))))))
    (testing "if no approval config matches"
      (testing "checks byDefault"
        (testing "when 'ask', return true"
          (is (= :ask (f.tools/approval all-tools request-tool {} {} {:toolCall {:approval {:byDefault "ask"}}} nil))))
        (testing "when 'allow', return false"
          (is (= :allow (f.tools/approval all-tools request-tool {} {} {:toolCall {:approval {:byDefault "allow"}}} nil)))))
      (testing "fallback to manual approval"
        (is (= :ask (f.tools/approval all-tools request-tool {} {} {} nil)))))))

(deftest approval-trust-test
  (let [request-tool {:name "request" :server {:name "web"} :origin :mcp}
        all-tools [request-tool]]
    (testing "trust promotes :ask to :trust/allow"
      (is (= :trust/allow (f.tools/approval all-tools request-tool {} {} {} nil {:trust true}))))
    (testing "trust does not override :deny"
      (is (= :deny (f.tools/approval all-tools request-tool {} {}
                                     {:toolCall {:approval {:deny {"web__request" {}}}}} nil {:trust true}))))
    (testing "trust does not change :allow"
      (is (= :allow (f.tools/approval all-tools request-tool {} {}
                                      {:toolCall {:approval {:allow {"web__request" {}}}}} nil {:trust true}))))
    (testing "no trust returns :ask as-is"
      (is (= :ask (f.tools/approval all-tools request-tool {} {} {} nil)))
      (is (= :ask (f.tools/approval all-tools request-tool {} {} {} nil nil)))
      (is (= :ask (f.tools/approval all-tools request-tool {} {} {} nil {:trust false}))))))

(deftest agent-specific-approval-test
  (let [shell-tool {:name "shell_command" :full-name "eca__shell_command" :server {:name "eca"} :origin :native}
        read-tool {:name "read_file" :full-name "eca__read_file" :server {:name "eca"} :origin :native}
        all-tools [shell-tool read-tool]]
    (testing "agent-specific approval overrides global rules"
      (let [config {:toolCall {:approval {:byDefault "allow"}}
                    :agent {"plan" {:toolCall {:approval {:deny {"shell_command" {:argsMatchers {"command" [".*rm.*"]}}}
                                                          :byDefault "ask"}}}}}]
        ;; Global config would allow shell commands (no agent specified)
        (is (= :allow (f.tools/approval all-tools shell-tool {"command" "ls -la"} {} config nil)))
        ;; But plan agent denies rm commands
        (is (= :deny (f.tools/approval all-tools shell-tool {"command" "rm file.txt"} {} config "plan")))
        ;; Plan agent allows other shell commands with ask (agent byDefault)
        (is (= :ask (f.tools/approval all-tools shell-tool {"command" "ls -la"} {} config "plan")))))
    (testing "agent without toolCall approval uses global rules"
      (let [config {:toolCall {:approval {:allow {"read_file" {}}}}
                    :agent {"custom" {}}}]
        (is (= :allow (f.tools/approval all-tools read-tool {} {} config "custom")))))
    (testing "plan agent shell restrictions work as configured"
      (let [config {:agent {"plan" {:toolCall {:approval {:deny {"shell_command" {:argsMatchers {"command" [".*>.*" ".*rm.*"]}}}}}}}}]
        (is (= :deny (f.tools/approval all-tools shell-tool {"command" "cat file.txt > output.txt"} {} config "plan")))
        (is (= :deny (f.tools/approval all-tools shell-tool {"command" "rm -rf folder"} {} config "plan")))
        ;; Safe commands should use byDefault (ask)
        (is (= :ask (f.tools/approval all-tools shell-tool {"command" "ls -la"} {} config "plan")))))
    (testing "code agent does NOT have plan restrictions"
      (let [config {:agent {"plan" {:toolCall {:approval {:deny {"shell_command" {:argsMatchers {"command" [".*>.*" ".*rm.*"]}}}}}}}}]
        ;; Same dangerous commands that are denied in plan mode should be allowed in build mode
        (is (= :ask (f.tools/approval all-tools shell-tool {"command" "rm -rf folder"} {} config "code")))
        (is (= :ask (f.tools/approval all-tools shell-tool {"command" "cat file.txt > output.txt"} {} config "code")))
        ;; No agent specified (nil) should also not have plan restrictions
        (is (= :ask (f.tools/approval all-tools shell-tool {"command" "rm file.txt"} {} config nil)))))
    (testing "regex patterns match dangerous commands correctly"
      (let [config (config/initial-config)]
        ;; Test output redirection patterns
        (is (= :deny (f.tools/approval all-tools shell-tool {"command" "echo test > file.txt"} {} config "plan")))
        (is (= :deny (f.tools/approval all-tools shell-tool {"command" "ls >> log.txt"} {} config "plan")))
        ;; Test pipe to dangerous commands
        (is (= :deny (f.tools/approval all-tools shell-tool {"command" "echo test | tee file.txt"} {} config "plan")))
        (is (= :deny (f.tools/approval all-tools shell-tool {"command" "find . | xargs rm"} {} config "plan")))
        ;; Test file operations
        (is (= :deny (f.tools/approval all-tools shell-tool {"command" "rm -rf folder"} {} config "plan")))
        (is (= :deny (f.tools/approval all-tools shell-tool {"command" "touch newfile.txt"} {} config "plan")))
        ;; Test git operations
        (is (= :deny (f.tools/approval all-tools shell-tool {"command" "git add ."} {} config "plan")))
        ;; Test safe commands that should NOT be denied
        (is (= :allow (f.tools/approval all-tools shell-tool {"command" "ls -la"} {} config "plan")))
        (is (= :ask (f.tools/approval all-tools shell-tool {"command" "git status"} {} config "plan")))
        ;; Test /dev/null redirections are allowed (not denied)
        (is (= :allow (f.tools/approval all-tools shell-tool {"command" "find /home/user/project -path '*/hato*' -name '*.clj' 2>/dev/null | sort"} {} config "plan")))
        (is (= :allow (f.tools/approval all-tools shell-tool {"command" "ls -la 2>/dev/null"} {} config "plan")))
        (is (= :ask (f.tools/approval all-tools shell-tool {"command" "some-cmd >/dev/null 2>&1"} {} config "plan")))
        (is (= :ask (f.tools/approval all-tools shell-tool {"command" "some-cmd 2> /dev/null"} {} config "plan")))
        ;; But redirection to actual files is still denied
        (is (= :deny (f.tools/approval all-tools shell-tool {"command" "echo test 2> error.log"} {} config "plan")))))))

(deftest plan-mode-approval-restrictions-test
  (let [shell-tool {:name "shell_command" :server {:name "eca"} :origin :native}
        all-tools [shell-tool]
        config (config/initial-config)]

    (testing "dangerous commands blocked in plan mode via approval"
      (are [command] (= :deny
                        (f.tools/approval all-tools shell-tool
                                          {"command" command} {} config "plan"))
        "echo 'test' > file.txt"
        "cat file.txt > output.txt"
        "ls >> log.txt"
        "rm file.txt"
        "mv old.txt new.txt"
        "cp file1.txt file2.txt"
        "touch newfile.txt"
        "mkdir newdir"
        "sed -i 's/old/new/' file.txt"
        "git add ."
        "git commit -m 'test'"
        "npm install package"
        "python -c \"open('file.txt','w').write('test')\""
        "bash -c 'echo test > file.txt'"))

    (testing "non-dangerous commands default to ask in plan mode"
      (are [command] (= :ask
                        (f.tools/approval all-tools shell-tool
                                          {"command" command} {} config "plan"))
        "python --version"  ; not matching dangerous patterns, defaults to ask
        "node script.js"     ; not matching dangerous patterns, defaults to ask
        "clojure -M:test"))  ; not matching dangerous patterns, defaults to ask

    (testing "safe commands not denied in plan mode"
      (are [command] (not= :deny
                           (f.tools/approval all-tools shell-tool
                                             {"command" command} {} config "plan"))
        "git status"
        "ls -la"
        "find . -name '*.clj'"
        "find /home/user/project -path '*/hato*' -name '*.clj' 2>/dev/null | sort"
        "grep 'test' file.txt"
        "cat file.txt"
        "cat file.txt 2>/dev/null"
        "head -10 file.txt"
        "pwd"
        "date"
        "env"
        "cd /tmp && jar xf /home/user/.m2/repository/lib/lib-1.0.jar src/foo.cljc 2>&1; echo \"---DONE---\""
        "some-cmd 2>&1; echo done"
        "some-cmd 2>/dev/null; echo done"))

    (testing "redirections to /tmp/ are not denied in plan mode"
      (are [command] (not= :deny
                           (f.tools/approval all-tools shell-tool
                                             {"command" command} {} config "plan"))
        "gh api repos/editor-code-assistant/eca-emacs/contents/eca-chat.el --jq '.content' 2>/dev/null | base64 -d 2>/dev/null > /tmp/eca-chat.el && wc -l /tmp/eca-chat.el"
        "echo test > /tmp/output.txt"
        "cat file.txt > /tmp/result.log"
        "ls -la >> /tmp/listing.txt"
        "some-cmd 2> /tmp/errors.log"
        "bash -c 'echo test > /tmp/file.txt'"))

    (testing "same commands work fine in code agent mode (not denied)"
      (are [command] (not= :deny
                           (f.tools/approval all-tools shell-tool
                                             {"command" command} {} config "code"))
        "echo 'test' > file.txt"
        "rm file.txt"
        "git add ."
        "python --version"))))

(deftest call-tool!-test
  (testing "INVALID_ARGS for missing required param on native tool"
    (is (match?
         {:error    true
          :contents [{:type :text
                      :text "INVALID_ARGS: missing required params: `path`"}]}
         (with-redefs [f.tools.filesystem/definitions
                       {"test_native_tool"
                        {:description "Test tool"
                         :parameters  {"type"      "object"
                                       :properties {"path" {:type "string"}}
                                       :required   ["path"]}
                         :handler     (fn [& _]
                                        {:error    false
                                         :contents [{:type :text :text "OK"}]})}}]
           (f.tools/call-tool!
            "eca__test_native_tool"
            {}
            "chat-1"
            "call-1"
            "code"
            (h/db*)
            (h/config)
            (h/messenger)
            (h/metrics)
            identity
            identity
            nil))))))

(deftest call-tool!-mcp-missing-required-test
  (testing "INVALID_ARGS for missing required param on MCP tool"
    (is (match?
         {:error    true
          :contents [{:type :text
                      :text "INVALID_ARGS: missing required params: `code`"}]}
         (with-redefs [f.mcp/all-tools  (fn [_]
                                          [{:name        "mcp_eval"
                                            :server      {:name "clojureMCP"}
                                            :description "eval code"
                                            :parameters  {"type"      "object"
                                                          :properties {"code" {:type "string"}}
                                                          :required   ["code"]}}])
                       f.mcp/call-tool! (fn [& _]
                                          {:error    false
                                           :contents [{:type :text :text "should-not-be-called"}]})]
           (f.tools/call-tool!
            "clojureMCP__mcp_eval"
            {}
            "chat-1"
            "call-2"
            "code"
            (h/db*)
            (h/config)
            (h/messenger)
            (h/metrics)
            identity
            identity
            nil))))))

(deftest call-tool!-missing-multiple-required-test
  (testing "INVALID_ARGS for multiple missing required params on native tool"
    (is (match?
         {:error    true
          :contents [{:type :text
                      :text "INVALID_ARGS: missing required params: `path`, `content`"}]}
         (with-redefs [f.tools.filesystem/definitions
                       {"test_native_multi"
                        {:description "Test tool multi"
                         :parameters  {"type"      "object"
                                       :properties {"path" {:type "string"}
                                                    "content" {:type "string"}}
                                       :required   ["path" "content"]}
                         :handler     (fn [& _]
                                        {:error    false
                                         :contents [{:type :text :text "OK"}]})}}]
           (f.tools/call-tool!
            "eca__test_native_multi"
            {}
            "chat-2"
            "call-3"
            "code"
            (h/db*)
            (h/config)
            (h/messenger)
            (h/metrics)
            identity
            identity
            nil))))))

(deftest fetch-rule-tool-test
  (testing "fetch_rule renders the matching path-scoped rule content for the current chat context"
    (let [rule-id (h/file-path "/workspace/a/.eca/rules/format.md")
          target-path (h/file-path "/workspace/a/src/nested/foo.clj")
          workspace-root (h/file-path "/workspace/a")]
      (with-redefs [f.rules/path-scoped-rules (constantly [{:id rule-id
                                                            :name "format.md"
                                                            :scope :project
                                                            :path rule-id
                                                            :workspace-root workspace-root
                                                            :paths ["src/**/*.clj"]
                                                            :content "{% if toolEnabled_eca__read_file %}Model rule{% endif %}{% if toolEnabled_eca__shell_command %} + shell enabled{% endif %}"}])]
        (let [db {:workspace-folders []
                  :chats {"chat-1" {:agent "code"
                                    :model "anthropic/claude-sonnet-4-20250514"}}}
              db* (atom db)
              handler (some #(when (= "fetch_rule" (:name %)) %) (f.tools/all-tools "chat-1" "code" db {}))
              result (f.tools/call-tool! "eca__fetch_rule"
                                         {"id" rule-id
                                          "path" target-path}
                                         "chat-1"
                                         "call-1"
                                         "code"
                                         db*
                                         {}
                                         (h/messenger)
                                         (h/metrics)
                                         identity
                                         identity
                                         nil)
              text (get-in result [:contents 0 :text])]
          (is (false? (:error result)))
          (is (string/includes? text (str "**Rule**: format.md\n**Path**: " target-path "\n**Matched pattern**: src/**/*.clj\n")))
          (is (string/includes? text "Model rule + shell enabled"))
          (is (= "fetch_rule" (:name handler)))
          (is (match? #{rule-id}
                      (get-in @db* [:chats "chat-1" :validated-path-rules])))))))

  (testing "fetch_rule rejects a path that does not match the selected rule"
    (let [rule-id (h/file-path "/workspace/a/.eca/rules/format.md")
          target-path (h/file-path "/workspace/a/src/foo.clj")
          workspace-root (h/file-path "/workspace/a")
          rule {:id rule-id
                :name "format.md"
                :scope :project
                :workspace-root workspace-root
                :paths ["src/**/*.clj"]
                :content "Rule body"}]
      (with-redefs [f.rules/path-scoped-rules (constantly [rule])
                    f.rules/find-rule-by-id (constantly rule)]
        (let [db {:workspace-folders []
                  :chats {"chat-1" {:agent "code"
                                    :model "anthropic/claude-sonnet-4-20250514"}}}
              result (f.tools/call-tool! "eca__fetch_rule"
                                         {"id" rule-id
                                          "path" target-path}
                                         "chat-1"
                                         "call-mismatch"
                                         "code"
                                         (atom db)
                                         {}
                                         (h/messenger)
                                         (h/metrics)
                                         identity
                                         identity
                                         nil)
              text (get-in result [:contents 0 :text])]
          (is (:error result))
          (is (string/includes? text (str "Rule id '" rule-id "' does not apply to path '" target-path "'.")))
          (is (re-find #"Checked relative path: src[/\\]foo\.clj" text))
          (is (string/includes? text "Allowed patterns: src/**/*.clj"))
          (is (string/includes? text "Java NIO `PathMatcher` glob syntax"))
          (is (string/includes? text "`src/**/*.clj` matches nested files under `src/` but not `src/foo.clj`"))))))

  (testing "fetch_rule suppresses broken rendered template content"
    (let [rule-id (h/file-path "/workspace/a/.eca/rules/broken.md")
          target-path (h/file-path "/workspace/a/src/nested/foo.clj")
          workspace-root (h/file-path "/workspace/a")]
      (with-redefs [f.rules/path-scoped-rules (constantly [{:id rule-id
                                                            :name "broken.md"
                                                            :scope :project
                                                            :workspace-root workspace-root
                                                            :paths ["src/**/*.clj"]
                                                            :content "{% if isSubagent %}BROKEN"}])]
        (let [db {:workspace-folders []
                  :chats {"chat-1" {:agent "code"
                                    :model "anthropic/claude-sonnet-4-20250514"}}}
              result (f.tools/call-tool! "eca__fetch_rule"
                                         {"id" rule-id
                                          "path" target-path}
                                         "chat-1"
                                         "call-broken"
                                         "code"
                                         (atom db)
                                         {}
                                         (h/messenger)
                                         (h/metrics)
                                         identity
                                         identity
                                         nil)
              text (get-in result [:contents 0 :text])]
          (is (false? (:error result)))
          (is (string/includes? text "**Matched pattern**: src/**/*.clj"))
          (is (string/includes? text "This rule contains no usable content for the current chat context and does not need to be loaded again in this chat."))))))

  (testing "fetch_rule rejects rules outside the current filtered catalog"
    (let [rule-id (h/file-path "/workspace/a/.eca/rules/format.md")
          target-path (h/file-path "/workspace/a/src/foo.clj")]
      (with-redefs [f.rules/path-scoped-rules (constantly [{:id (h/file-path "/other/rule.md")
                                                            :name "other.md"
                                                            :scope :project
                                                            :paths ["**/*.rb"]}])
                    f.rules/find-rule-by-id (constantly nil)]
        (let [db {:workspace-folders []
                  :chats {"chat-1" {:agent "code"
                                    :model "openai/gpt-4o"}}}
              result (f.tools/call-tool! "eca__fetch_rule"
                                         {"id" rule-id
                                          "path" target-path}
                                         "chat-1"
                                         "call-2"
                                         "code"
                                         (atom db)
                                         {}
                                         (h/messenger)
                                         (h/metrics)
                                         identity
                                         identity
                                         nil)]
          (is (match? {:error true
                       :contents [{:type :text
                                   :text (str "Rule id '" rule-id "' not found in the current path-scoped rules catalog. Use the exact id from the catalog or /rules command.")}]}
                      result))))))

  (testing "fetch_rule tool description lists only the current chat's path-scoped rules and explains the path contract"
    (let [format-id (h/file-path "/workspace/a/.eca/rules/format.md")
          network-id (h/file-path "/home/user/.config/eca/rules/no-network.md")
          workspace-root (h/file-path "/workspace/a")]
      (with-redefs [f.rules/path-scoped-rules (fn [_config _roots agent full-model]
                                                (if (and (= agent "code")
                                                         (= full-model "anthropic/claude-sonnet-4-20250514"))
                                                  [{:id format-id
                                                    :name "format.md"
                                                    :scope :project
                                                    :workspace-root workspace-root
                                                    :paths ["src/**/*.clj"]}
                                                   {:id network-id
                                                    :name "no-network.md"
                                                    :scope :global
                                                    :paths ["**/*.clj" "**/*.cljs"]}]
                                                  []))]
        (let [db {:workspace-folders []
                  :chats {"chat-1" {:agent "code"
                                    :model "anthropic/claude-sonnet-4-20250514"}
                          "chat-2" {:agent "plan"
                                    :model "openai/gpt-4o"}}}
              fetch-tool (some #(when (= "fetch_rule" (:name %)) %) (f.tools/all-tools "chat-1" "code" db {}))]
          (is (string? (:description fetch-tool)))
          (is (re-find #"exact absolute target path" (:description fetch-tool)))
          (is (re-find #"Java NIO `PathMatcher` glob syntax" (:description fetch-tool)))
          (is (re-find (re-pattern (str "id: " (java.util.regex.Pattern/quote format-id))) (:description fetch-tool)))
          (is (re-find #"scope: project" (:description fetch-tool)))
          (is (re-find (re-pattern (str "workspace-root: " (java.util.regex.Pattern/quote workspace-root))) (:description fetch-tool)))
          (is (re-find (re-pattern (str "id: " (java.util.regex.Pattern/quote network-id))) (:description fetch-tool)))
          (is (re-find #"scope: global" (:description fetch-tool)))
          (is (not (re-find #"chat-2" (:description fetch-tool)))))))))

(deftest file-tools-path-rule-enforcement-test
  (let [workspace-root (h/file-path "/workspace/a")
        db {:workspace-folders [{:uri (h/file-uri "file:///workspace/a")}]
            :chats {"chat-1" {:agent "code"
                              :model "openai/gpt-5.2"}}}
        call-tool! (fn [db* call-id tool-name args]
                     (f.tools/call-tool! tool-name
                                         args
                                         "chat-1"
                                         call-id
                                         "code"
                                         db*
                                         {}
                                         (h/messenger)
                                         (h/metrics)
                                         identity
                                         identity
                                         nil))
        fetch-rule! (fn [db* call-id rule-id path]
                      (call-tool! db* call-id "eca__fetch_rule" {"id" rule-id
                                                                  "path" path}))
        write-file! (fn [db* call-id path content]
                      (call-tool! db* call-id "eca__write_file" {"path" path
                                                                  "content" content}))]
    (testing "write_file is blocked until fetch_rule validates the same path"
      (let [rule-id (h/file-path "/workspace/a/.eca/rules/format.md")
            target-path (h/file-path "/workspace/a/src/foo.clj")
            rule {:id rule-id
                  :name "format.md"
                  :scope :project
                  :workspace-root workspace-root
                  :path rule-id
                  :paths ["src/**.clj"]
                  :content "Use the project formatter."}
            db* (atom db)
            writes* (atom {})]
        (with-redefs [f.rules/path-scoped-rules (constantly [rule])
                      f.rules/find-rule-by-id (constantly rule)
                      fs/create-dirs (constantly nil)
                      spit (fn [path content] (swap! writes* assoc path content))
                      slurp (fn [path]
                              (if (= target-path (str path))
                                (throw (java.io.FileNotFoundException. "missing"))
                                ""))]
          (let [blocked (write-file! db* "call-write-1" target-path "(ns foo)")]
            (is (:error blocked))
            (is (string/includes? (get-in blocked [:contents 0 :text]) (str "Path-scoped rules must be fetched before modifying '" target-path "'.")))
            (is (string/includes? (get-in blocked [:contents 0 :text]) "call `fetch_rule` with this exact `id` and `path`"))
            (is (empty? @writes*)))

          (let [fetched (fetch-rule! db* "call-fetch-1" rule-id target-path)
                written (write-file! db* "call-write-2" target-path "(ns foo)")]
            (is (false? (:error fetched)))
            (is (false? (:error written)))
            (is (= "(ns foo)" (get @writes* target-path)))))))

    (testing "fetching a matching rule once satisfies modify enforcement for another matching path"
      (let [rule-id (h/file-path "/workspace/a/.eca/rules/format.md")
            fetched-path (h/file-path "/workspace/a/src/a/foo.clj")
            second-path (h/file-path "/workspace/a/src/b/bar.clj")
            rule {:id rule-id
                  :name "format.md"
                  :scope :project
                  :workspace-root workspace-root
                  :path rule-id
                  :paths ["src/**/*.clj"]
                  :content "Use the project formatter."}
            db* (atom db)
            writes* (atom {})]
        (with-redefs [f.rules/path-scoped-rules (constantly [rule])
                      f.rules/find-rule-by-id (constantly rule)
                      fs/create-dirs (constantly nil)
                      spit (fn [path content] (swap! writes* assoc path content))
                      slurp (constantly "")]
          (let [fetched (fetch-rule! db* "call-fetch-cross-path" rule-id fetched-path)
                written (write-file! db* "call-write-cross-path" second-path "(ns bar)")]
            (is (false? (:error fetched)))
            (is (false? (:error written)))
            (is (= "(ns bar)" (get @writes* second-path)))))))

    (testing "rules with the same path but different enforce values remain independently required"
      (let [read-rule-id (h/file-path "/workspace/a/.eca/rules/read.md")
            modify-rule-id (h/file-path "/workspace/a/.eca/rules/modify.md")
            target-path (h/file-path "/workspace/a/src/a/foo.clj")
            read-rule {:id read-rule-id
                       :name "read.md"
                       :scope :project
                       :workspace-root workspace-root
                       :path read-rule-id
                       :paths ["src/**/*.clj"]
                       :enforce ["read"]
                       :content "Read guidance."}
            modify-rule {:id modify-rule-id
                         :name "modify.md"
                         :scope :project
                         :workspace-root workspace-root
                         :path modify-rule-id
                         :paths ["src/**/*.clj"]
                         :enforce ["modify"]
                         :content "Modify guidance."}
            rules [read-rule modify-rule]
            db* (atom db)
            writes* (atom {})]
        (with-redefs [f.rules/path-scoped-rules (constantly rules)
                      f.rules/find-rule-by-id (fn [_config _roots rule-id _agent _full-model]
                                                (first (filter #(= rule-id (:id %)) rules)))
                      fs/create-dirs (constantly nil)
                      spit (fn [path content] (swap! writes* assoc path content))
                      slurp (constantly "")]
          (let [read-fetched (fetch-rule! db* "call-fetch-read-rule" read-rule-id target-path)
                blocked-write (write-file! db* "call-write-before-modify-rule" target-path "(ns foo)")
                modify-fetched (fetch-rule! db* "call-fetch-modify-rule" modify-rule-id target-path)
                written (write-file! db* "call-write-after-modify-rule" target-path "(ns foo)")]
            (is (false? (:error read-fetched)))
            (is (:error blocked-write))
            (is (string/includes? (get-in blocked-write [:contents 0 :text]) modify-rule-id))
            (is (false? (:error modify-fetched)))
            (is (false? (:error written)))
            (is (= "(ns foo)" (get @writes* target-path)))))))))

(deftest call-tool!-omits-optional-empty-string-args-test
  (testing "optional empty string args are omitted before native tool invocation"
    (let [received-args* (atom nil)]
      (is (match?
           {:error false
            :contents [{:type :text :text "OK"}]}
           (with-redefs [f.tools.filesystem/definitions
                         {"test_optional_empty"
                          {:description "Test tool optional empty"
                           :parameters  {"type"      "object"
                                         :properties {"path" {:type "string"}
                                                      "pattern" {:type "string"}}
                                         :required   ["path"]}
                           :handler     (fn [args _]
                                          (reset! received-args* args)
                                          {:error false
                                           :contents [{:type :text :text "OK"}]})}}]
             (f.tools/call-tool!
              "eca__test_optional_empty"
              {"path" "/tmp/file"
               "pattern" ""}
              "chat-3"
              "call-4"
              "code"
              (h/db*)
              (h/config)
              (h/messenger)
              (h/metrics)
              identity
              identity
              nil))))
      (is (= {"path" "/tmp/file"} @received-args*)))))

(deftest call-tool!-preserves-required-empty-string-args-test
  (testing "required empty string args are preserved"
    (let [received-args* (atom nil)]
      (is (match?
           {:error false
            :contents [{:type :text :text "OK"}]}
           (with-redefs [f.tools.filesystem/definitions
                         {"test_required_empty"
                          {:description "Test tool required empty"
                           :parameters  {"type"      "object"
                                         :properties {"path" {:type "string"}}
                                         :required   ["path"]}
                           :handler     (fn [args _]
                                          (reset! received-args* args)
                                          {:error false
                                           :contents [{:type :text :text "OK"}]})}}]
             (f.tools/call-tool!
              "eca__test_required_empty"
              {"path" ""}
              "chat-4"
              "call-5"
              "code"
              (h/db*)
              (h/config)
              (h/messenger)
              (h/metrics)
              identity
              identity
              nil))))
      (is (= {"path" ""} @received-args*)))))
