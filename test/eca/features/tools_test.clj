(ns eca.features.tools-test
  (:require
   [clojure.test :refer [are deftest is testing]]
   [eca.config :as config]
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
         (f.tools/all-tools "123" "agent"
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
         (f.tools/all-tools "123" "agent" {} {}))))
  (testing "Do not include disabled native tools"
    (is (match?
         (m/embeds [(m/mismatch {:name "directory_tree"})])
         (f.tools/all-tools "123" "agent" {} {:disabledTools ["directory_tree"]}))))
  (testing "Plan mode includes preview tool but excludes mutating tools"
    (let [plan-config {:behavior {"plan" {:disabledTools ["edit_file" "write_file" "move_file"]}}}
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
  (testing "Do not include plan edit tool if agent behavior"
    (is (match?
         (m/embeds [(m/mismatch {:name "preview_file_change"})
                    {:name "edit_file"}])
         (f.tools/all-tools "123" "agent" {} {}))))
  (testing "Replace special vars description"
    (is (match?
         (m/embeds [{:name "directory_tree"
                     :description (format "Only in %s" (h/file-path "/path/to/project/foo"))
                     :parameters some?
                     :origin :native}])
         (with-redefs [f.tools.filesystem/definitions {"directory_tree" {:description "Only in {{workspaceRoots}}"
                                                                         :parameters {}}}]
           (f.tools/all-tools "123" "agent" {:workspace-folders [{:name "foo" :uri (h/file-uri "file:///path/to/project/foo")}]}
                              {})))))
  (testing "Override native tool description via global prompts config"
    (let [config {:prompts {:tools {"eca__directory_tree" "Custom global description"}}}
          tools (f.tools/all-tools "123" "agent" {} config)
          tool (some #(when (= "eca__directory_tree" (:full-name %)) %) tools)]
      (is (= "Custom global description" (:description tool)))))
  (testing "Override native tool description via behavior-specific prompts config"
    (let [config {:behavior {"agent" {:prompts {:tools {"eca__directory_tree" "Custom agent description"}}}}}
          tools (f.tools/all-tools "123" "agent" {} config)
          tool (some #(when (= "eca__directory_tree" (:full-name %)) %) tools)]
      (is (= "Custom agent description" (:description tool)))))
  (testing "Behavior-specific prompts takes precedence over global prompts"
    (let [config {:prompts {:tools {"eca__directory_tree" "Global description"}}
                  :behavior {"agent" {:prompts {:tools {"eca__directory_tree" "Agent description"}}}}}
          tools (f.tools/all-tools "123" "agent" {} config)
          tool (some #(when (= "eca__directory_tree" (:full-name %)) %) tools)]
      (is (= "Agent description" (:description tool)))))
  (testing "Different behaviors can have different tool descriptions"
    (let [config {:behavior {"agent" {:prompts {:tools {"eca__directory_tree" "Agent description"}}}
                             "plan" {:prompts {:tools {"eca__directory_tree" "Plan description"}}
                                     :disabledTools ["edit_file" "write_file" "move_file"]}}}
          agent-tools (f.tools/all-tools "123" "agent" {} config)
          plan-tools (f.tools/all-tools "123" "plan" {} config)
          agent-tool (some #(when (= "eca__directory_tree" (:full-name %)) %) agent-tools)
          plan-tool (some #(when (= "eca__directory_tree" (:full-name %)) %) plan-tools)]
      (is (= "Agent description" (:description agent-tool)))
      (is (= "Plan description" (:description plan-tool)))))
  (testing "Override MCP tool description via global prompts config"
    (let [db {:mcp-clients {"myMCP" {:version "1.0"
                                     :tools [{:name "my_tool"
                                              :description "Original MCP description"
                                              :parameters {"type" "object"}}]}}}
          config {:prompts {:tools {"myMCP__my_tool" "Custom MCP description"}}}
          tools (f.tools/all-tools "123" "agent" db config)
          tool (some #(when (= "myMCP__my_tool" (:full-name %)) %) tools)]
      (is (= "Custom MCP description" (:description tool)))))
  (testing "Falls back to original description when no override specified"
    (let [config {:prompts {:tools {"eca__some_other_tool" "Override for other tool"}}}
          tools (f.tools/all-tools "123" "agent" {} config)
          tool (some #(when (= "eca__directory_tree" (:full-name %)) %) tools)]
      (is (string? (:description tool)))
      (is (not= "Override for other tool" (:description tool)))))
  (testing "Falls back to global when behavior has no override for specific tool"
    (let [config {:prompts {:tools {"eca__directory_tree" "Global description"}}
                  :behavior {"agent" {:prompts {:tools {"eca__read_file" "Agent read_file description"}}}}}
          tools (f.tools/all-tools "123" "agent" {} config)
          dir-tool (some #(when (= "eca__directory_tree" (:full-name %)) %) tools)
          read-tool (some #(when (= "eca__read_file" (:full-name %)) %) tools)]
      (is (= "Global description" (:description dir-tool)))
      (is (= "Agent read_file description" (:description read-tool))))))

(deftest get-disabled-tools-test
  (testing "merges global and behavior-specific disabled tools"
    (let [config {:disabledTools ["global_tool"]
                  :behavior {"plan" {:disabledTools ["plan_tool"]}
                             "custom" {:disabledTools ["custom_tool"]}}}]
      (is (= #{"global_tool" "plan_tool"}
             (#'f.tools/get-disabled-tools config "plan")))
      (is (= #{"global_tool" "custom_tool"}
             (#'f.tools/get-disabled-tools config "custom")))))
  (testing "behavior with no disabled tools"
    (let [config {:disabledTools ["global_tool"]
                  :behavior {"empty" {}}}]
      (is (= #{"global_tool"}
             (#'f.tools/get-disabled-tools config "empty")))))
  (testing "nil behavior returns only global disabled tools"
    (let [config {:disabledTools ["global_tool"]
                  :behavior {"plan" {:disabledTools ["plan_tool"]}}}]
      (is (= #{"global_tool"}
             (#'f.tools/get-disabled-tools config nil)))))
  (testing "no global disabled tools"
    (let [config {:behavior {"plan" {:disabledTools ["plan_tool"]}}}]
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

(deftest behavior-specific-approval-test
  (let [shell-tool {:name "shell_command" :full-name "eca__shell_command" :server {:name "eca"} :origin :native}
        read-tool {:name "read_file" :full-name "eca__read_file" :server {:name "eca"} :origin :native}
        all-tools [shell-tool read-tool]]
    (testing "behavior-specific approval overrides global rules"
      (let [config {:toolCall {:approval {:byDefault "allow"}}
                    :behavior {"plan" {:toolCall {:approval {:deny {"shell_command" {:argsMatchers {"command" [".*rm.*"]}}}
                                                             :byDefault "ask"}}}}}]
        ;; Global config would allow shell commands (no behavior specified)
        (is (= :allow (f.tools/approval all-tools shell-tool {"command" "ls -la"} {} config nil)))
        ;; But plan behavior denies rm commands
        (is (= :deny (f.tools/approval all-tools shell-tool {"command" "rm file.txt"} {} config "plan")))
        ;; Plan behavior allows other shell commands with ask (behavior byDefault)
        (is (= :ask (f.tools/approval all-tools shell-tool {"command" "ls -la"} {} config "plan")))))
    (testing "behavior without toolCall approval uses global rules"
      (let [config {:toolCall {:approval {:allow {"read_file" {}}}}
                    :behavior {"custom" {}}}]
        (is (= :allow (f.tools/approval all-tools read-tool {} {} config "custom")))))
    (testing "plan behavior shell restrictions work as configured"
      (let [config {:behavior {"plan" {:toolCall {:approval {:deny {"shell_command" {:argsMatchers {"command" [".*>.*" ".*rm.*"]}}}}}}}}]
        (is (= :deny (f.tools/approval all-tools shell-tool {"command" "cat file.txt > output.txt"} {} config "plan")))
        (is (= :deny (f.tools/approval all-tools shell-tool {"command" "rm -rf folder"} {} config "plan")))
        ;; Safe commands should use byDefault (ask)
        (is (= :ask (f.tools/approval all-tools shell-tool {"command" "ls -la"} {} config "plan")))))
    (testing "agent behavior does NOT have plan restrictions"
      (let [config {:behavior {"plan" {:toolCall {:approval {:deny {"shell_command" {:argsMatchers {"command" [".*>.*" ".*rm.*"]}}}}}}}}]
        ;; Same dangerous commands that are denied in plan mode should be allowed in agent mode
        (is (= :ask (f.tools/approval all-tools shell-tool {"command" "rm -rf folder"} {} config "agent")))
        (is (= :ask (f.tools/approval all-tools shell-tool {"command" "cat file.txt > output.txt"} {} config "agent")))
        ;; No behavior specified (nil) should also not have plan restrictions
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
        (is (= :ask (f.tools/approval all-tools shell-tool {"command" "ls -la"} {} config "plan")))
        (is (= :ask (f.tools/approval all-tools shell-tool {"command" "git status"} {} config "plan")))))))

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
        "grep 'test' file.txt"
        "cat file.txt"
        "head -10 file.txt"
        "pwd"
        "date"
        "env"))

    (testing "same commands work fine in agent mode (not denied)"
      (are [command] (not= :deny
                           (f.tools/approval all-tools shell-tool
                                             {"command" command} {} config "agent"))
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
            "agent"
            (h/db*)
            (h/config)
            (h/messenger)
            (h/metrics)
            identity
            identity))))))

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
            "agent"
            (h/db*)
            (h/config)
            (h/messenger)
            (h/metrics)
            identity
            identity))))))

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
            "agent"
            (h/db*)
            (h/config)
            (h/messenger)
            (h/metrics)
            identity
            identity))))))
