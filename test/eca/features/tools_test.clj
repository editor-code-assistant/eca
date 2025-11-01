(ns eca.features.tools-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.features.tools :as f.tools]
   [eca.features.tools.filesystem :as f.tools.filesystem]
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
         (m/embeds [{:name "eca_directory_tree"
                     :server {:name "eca"}
                     :description string?
                     :parameters some?
                     :origin :native}])
         (f.tools/all-tools "123" "agent" {} {}))))
  (testing "Do not include disabled native tools"
    (is (match?
         (m/embeds [(m/mismatch {:name "eca_directory_tree"})])
         (f.tools/all-tools "123" "agent" {} {:disabledTools ["eca_directory_tree"]}))))
  (testing "Plan mode includes preview tool but excludes mutating tools"
    (let [plan-config {:behavior {"plan" {:disabledTools ["eca_edit_file" "eca_write_file" "eca_move_file"]}}}
          plan-tools (f.tools/all-tools "123" "plan" {} plan-config)
          tool-names (set (map :name plan-tools))]
      ;; Verify that preview tool is included
      (is (contains? tool-names "eca_preview_file_change"))
      ;; Verify that shell command is now allowed in plan mode (with restrictions in prompt)
      (is (contains? tool-names "eca_shell_command"))
      ;; Verify that mutating tools are excluded
      (is (not (contains? tool-names "eca_edit_file")))
      (is (not (contains? tool-names "eca_write_file")))
      (is (not (contains? tool-names "eca_move_file")))))
  (testing "Do not include plan edit tool if agent behavior"
    (is (match?
         (m/embeds [(m/mismatch {:name "eca_preview_file_change"})
                    {:name "eca_edit_file"}])
         (f.tools/all-tools "123" "agent" {} {}))))
  (testing "Replace special vars description"
    (is (match?
         (m/embeds [{:name "eca_directory_tree"
                     :description (format "Only in %s" (h/file-path "/path/to/project/foo"))
                     :parameters some?
                     :origin :native}])
         (with-redefs [f.tools.filesystem/definitions {"eca_directory_tree" {:description "Only in {workspaceRoots}"
                                                                             :parameters {}}}]
           (f.tools/all-tools "123" "agent" {:workspace-folders [{:name "foo" :uri (h/file-uri "file:///path/to/project/foo")}]}
                              {}))))))

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
  (let [all-tools [{:name "eca_read" :server {:name "eca"}}
                   {:name "eca_write" :server {:name "eca"}}
                   {:name "eca_shell" :server {:name "eca"} :require-approval-fn (constantly true)}
                   {:name "eca_plan" :server {:name "eca"} :require-approval-fn (constantly false)}
                   {:name "request" :server {:name "web"}}
                   {:name "download" :server {:name "web"}}]]
    (testing "tool has require-approval-fn which returns true"
      (is (= :ask (f.tools/approval all-tools "eca_shell" {} {} {} nil))))
    (testing "tool has require-approval-fn which returns false we ignore it"
      (is (= :ask (f.tools/approval all-tools "eca_plan" {} {} {} nil))))
    (testing "tool was remembered to approve by user"
      (is (= :allow (f.tools/approval all-tools "eca_plan" {} {:tool-calls {"eca_plan" {:remember-to-approve? true}}} {} nil))))
    (testing "if legacy-manual-approval present, considers it"
      (is (= :ask (f.tools/approval all-tools "request" {} {} {:toolCall {:manualApproval true}} nil))))
    (testing "if approval config is provided"
      (testing "when matches allow config"
        (is (= :allow (f.tools/approval all-tools "request" {} {} {:toolCall {:approval {:allow {"web__request" {}}}}} nil)))
        (is (= :allow (f.tools/approval all-tools "eca_read" {} {} {:toolCall {:approval {:allow {"eca_read" {}}}}} nil)))
        (is (= :allow (f.tools/approval all-tools "request" {} {} {:toolCall {:approval {:allow {"web" {}}}}} nil)))
        (is (= :allow (f.tools/approval all-tools "eca_read" {} {} {:toolCall {:approval {:allow {"eca" {}}}}} nil))))
      (testing "when matches ask config"
        (is (= :ask (f.tools/approval all-tools "request" {} {} {:toolCall {:approval {:ask {"web__request" {}}}}} nil)))
        (is (= :ask (f.tools/approval all-tools "eca_read" {} {} {:toolCall {:approval {:ask {"eca_read" {}}}}} nil)))
        (is (= :ask (f.tools/approval all-tools "request" {} {} {:toolCall {:approval {:ask {"web" {}}}}} nil))))
      (testing "when matches deny config"
        (is (= :deny (f.tools/approval all-tools "request" {} {} {:toolCall {:approval {:deny {"web__request" {}}}}} nil)))
        (is (= :deny (f.tools/approval all-tools "eca_read" {} {} {:toolCall {:approval {:deny {"eca_read" {}}}}} nil)))
        (is (= :deny (f.tools/approval all-tools "request" {} {} {:toolCall {:approval {:deny {"web" {}}}}} nil))))
      (testing "when contains argsMatchers"
        (testing "has arg but not matches"
          (is (= :ask (f.tools/approval all-tools "request" {"url" "http://bla.com"} {}
                                        {:toolCall {:approval {:allow {"web__request" {:argsMatchers {"url" [".*foo.*"]}}}}}} nil))))
        (testing "has arg and matches for allow"
          (is (= :allow (f.tools/approval all-tools "request" {"url" "http://foo.com"} {}
                                          {:toolCall {:approval {:allow {"web__request" {:argsMatchers {"url" [".*foo.*"]}}}}}} nil)))
          (is (= :allow (f.tools/approval all-tools "request" {"url" "foobar"} {}
                                          {:toolCall {:approval {:allow {"web__request" {:argsMatchers {"url" ["foo.*"]}}}}}} nil))))
        (testing "has arg and matches for deny"
          (is (= :deny (f.tools/approval all-tools "request" {"url" "http://foo.com"} {}
                                         {:toolCall {:approval {:deny {"web__request" {:argsMatchers {"url" [".*foo.*"]}}}}}} nil)))
          (is (= :deny (f.tools/approval all-tools "request" {"url" "foobar"} {}
                                         {:toolCall {:approval {:deny {"web__request" {:argsMatchers {"url" ["foo.*"]}}}}}} nil))))
        (testing "has not that arg"
          (is (= :ask (f.tools/approval all-tools "request" {"crazy-url" "http://foo.com"} {}
                                        {:toolCall {:approval {:allow {"web__request" {:argsMatchers {"url" [".*foo.*"]}}}}}} nil))))))
    (testing "if no approval config matches"
      (testing "checks byDefault"
        (testing "when 'ask', return true"
          (is (= :ask (f.tools/approval all-tools "request" {} {} {:toolCall {:approval {:byDefault "ask"}}} nil))))
        (testing "when 'allow', return false"
          (is (= :allow (f.tools/approval all-tools "request" {} {} {:toolCall {:approval {:byDefault "allow"}}} nil)))))
      (testing "fallback to manual approval"
        (is (= :ask (f.tools/approval all-tools "request" {} {} {} nil)))))))

(deftest behavior-specific-approval-test
  (let [all-tools [{:name "eca_shell_command" :server {:name "eca"}}
                   {:name "eca_read_file" :server {:name "eca"}}]]
    (testing "behavior-specific approval overrides global rules"
      (let [config {:toolCall {:approval {:byDefault "allow"}}
                    :behavior {"plan" {:toolCall {:approval {:deny {"eca_shell_command" {:argsMatchers {"command" [".*rm.*"]}}}
                                                             :byDefault "ask"}}}}}]
        ;; Global config would allow shell commands (no behavior specified)
        (is (= :allow (f.tools/approval all-tools "eca_shell_command" {"command" "ls -la"} {} config nil)))
        ;; But plan behavior denies rm commands
        (is (= :deny (f.tools/approval all-tools "eca_shell_command" {"command" "rm file.txt"} {} config "plan")))
        ;; Plan behavior allows other shell commands with ask (behavior byDefault)
        (is (= :ask (f.tools/approval all-tools "eca_shell_command" {"command" "ls -la"} {} config "plan")))))
    (testing "behavior without toolCall approval uses global rules"
      (let [config {:toolCall {:approval {:allow {"eca_read_file" {}}}}
                    :behavior {"custom" {}}}]
        (is (= :allow (f.tools/approval all-tools "eca_read_file" {} {} config "custom")))))
    (testing "plan behavior shell restrictions work as configured"
      (let [config {:behavior {"plan" {:toolCall {:approval {:deny {"eca_shell_command" {:argsMatchers {"command" [".*>.*" ".*rm.*"]}}}}}}}}]
        (is (= :deny (f.tools/approval all-tools "eca_shell_command" {"command" "cat file.txt > output.txt"} {} config "plan")))
        (is (= :deny (f.tools/approval all-tools "eca_shell_command" {"command" "rm -rf folder"} {} config "plan")))
        ;; Safe commands should use byDefault (ask)
        (is (= :ask (f.tools/approval all-tools "eca_shell_command" {"command" "ls -la"} {} config "plan")))))
    (testing "agent behavior does NOT have plan restrictions"
      (let [config {:behavior {"plan" {:toolCall {:approval {:deny {"eca_shell_command" {:argsMatchers {"command" [".*>.*" ".*rm.*"]}}}}}}}}]
        ;; Same dangerous commands that are denied in plan mode should be allowed in agent mode
        (is (= :ask (f.tools/approval all-tools "eca_shell_command" {"command" "rm -rf folder"} {} config "agent")))
        (is (= :ask (f.tools/approval all-tools "eca_shell_command" {"command" "cat file.txt > output.txt"} {} config "agent")))
        ;; No behavior specified (nil) should also not have plan restrictions
        (is (= :ask (f.tools/approval all-tools "eca_shell_command" {"command" "rm file.txt"} {} config nil)))))
    (testing "regex patterns match dangerous commands correctly"
      (let [config config/initial-config]
        ;; Test output redirection patterns
        (is (= :deny (f.tools/approval all-tools "eca_shell_command" {"command" "echo test > file.txt"} {} config "plan")))
        (is (= :deny (f.tools/approval all-tools "eca_shell_command" {"command" "ls >> log.txt"} {} config "plan")))
        ;; Test pipe to dangerous commands
        (is (= :deny (f.tools/approval all-tools "eca_shell_command" {"command" "echo test | tee file.txt"} {} config "plan")))
        (is (= :deny (f.tools/approval all-tools "eca_shell_command" {"command" "find . | xargs rm"} {} config "plan")))
        ;; Test file operations
        (is (= :deny (f.tools/approval all-tools "eca_shell_command" {"command" "rm -rf folder"} {} config "plan")))
        (is (= :deny (f.tools/approval all-tools "eca_shell_command" {"command" "touch newfile.txt"} {} config "plan")))
        ;; Test git operations
        (is (= :deny (f.tools/approval all-tools "eca_shell_command" {"command" "git add ."} {} config "plan")))
        ;; Test safe commands that should NOT be denied
        (is (= :ask (f.tools/approval all-tools "eca_shell_command" {"command" "ls -la"} {} config "plan")))
        (is (= :ask (f.tools/approval all-tools "eca_shell_command" {"command" "git status"} {} config "plan")))))))
