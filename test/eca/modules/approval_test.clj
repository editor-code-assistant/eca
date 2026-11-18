(ns eca.modules.approval-test
  "The `toolCall.approval` rules of `:eca/approval`."
  (:require
   [clojure.test :refer [are deftest is testing]]
   [eca.config :as config]
   [eca.features.tools :as f.tools]
   [eca.features.tools.util :as tools.util]
   [eca.modules.approval :as m.approval]
   [eca.test-helper :as h]))

(set! *warn-on-reflection* true)

(defn ^:private db-remembering
  "A db whose chat \"c1\" remembered `approvals` via approve & remember."
  [approvals]
  {:chats {"c1" {:id "c1" :remembered-approvals approvals}}})

(deftest approval-test
  (let [read-tool {:name "read" :server {:name "eca"} :origin :native}
        write-tool {:name "write" :server {:name "eca"} :origin :native}
        shell-tool {:name "shell" :server {:name "eca"} :origin :native :require-approval-fn (constantly true)}
        plan-tool {:name "plan" :server {:name "eca"} :origin :native :require-approval-fn (constantly false)}
        request-tool {:name "request" :server {:name "web"} :origin :mcp}
        download-tool {:name "download" :server {:name "web"} :origin :mcp}
        web-write-tool {:name "write" :server {:name "web"} :origin :mcp}
        all-tools [read-tool write-tool shell-tool plan-tool request-tool download-tool web-write-tool]]
    (testing "tool has require-approval-fn which returns true"
      (is (= :ask (#'m.approval/approval all-tools shell-tool {} {} {} nil))))
    (testing "tool has require-approval-fn which returns false we ignore it"
      (is (= :ask (#'m.approval/approval all-tools plan-tool {} {} {} nil))))
    (testing "tool was remembered to approve by user"
      (is (= :allow (#'m.approval/approval all-tools plan-tool {} (db-remembering {"eca__plan" {:remember-to-approve? true}}) {} nil
                                           {:chat-id "c1"}))))
    (testing "remember-to-approve takes precedence over require-approval-fn"
      (is (= :allow (#'m.approval/approval all-tools shell-tool {} (db-remembering {"eca__shell" {:remember-to-approve? true}}) {} nil
                                           {:chat-id "c1"}))))
    (testing "if legacy-manual-approval present, considers it"
      (is (= :ask (#'m.approval/approval all-tools request-tool {} {} {:toolCall {:manualApproval true}} nil))))
    (testing "if approval config is provided"
      (testing "when matches allow config"
        (is (= :allow (#'m.approval/approval all-tools request-tool {} {} {:toolCall {:approval {:allow {"web__request" {}}}}} nil)))
        (is (= :allow (#'m.approval/approval all-tools read-tool {} {} {:toolCall {:approval {:allow {"read" {}}}}} nil)))
        (is (= :ask (#'m.approval/approval all-tools web-write-tool {} {} {:toolCall {:approval {:allow {"write" {}}}}} nil)))
        (is (= :allow (#'m.approval/approval all-tools request-tool {} {} {:toolCall {:approval {:allow {"web" {}}}}} nil)))
        (is (= :allow (#'m.approval/approval all-tools read-tool {} {} {:toolCall {:approval {:allow {"eca" {}}}}} nil))))
      (testing "when matches ask config"
        (is (= :ask (#'m.approval/approval all-tools request-tool {} {} {:toolCall {:approval {:ask {"web__request" {}}}}} nil)))
        (is (= :ask (#'m.approval/approval all-tools read-tool {} {} {:toolCall {:approval {:ask {"read" {}}}}} nil)))
        (is (= :ask (#'m.approval/approval all-tools request-tool {} {} {:toolCall {:approval {:ask {"web" {}}}}} nil))))
      (testing "when matches deny config"
        (is (= :deny (#'m.approval/approval all-tools request-tool {} {} {:toolCall {:approval {:deny {"web__request" {}}}}} nil)))
        (is (= :deny (#'m.approval/approval all-tools read-tool {} {} {:toolCall {:approval {:deny {"read" {}}}}} nil)))
        (is (= :deny (#'m.approval/approval all-tools request-tool {} {} {:toolCall {:approval {:deny {"web" {}}}}} nil))))
      (testing "when contains argsMatchers"
        (testing "has arg but not matches"
          (is (= :ask (#'m.approval/approval all-tools request-tool {"url" "http://bla.com"} {}
                                             {:toolCall {:approval {:allow {"web__request" {:argsMatchers {"url" [".*foo.*"]}}}}}} nil))))
        (testing "has arg and matches for allow"
          (is (= :allow (#'m.approval/approval all-tools request-tool {"url" "http://foo.com"} {}
                                               {:toolCall {:approval {:allow {"web__request" {:argsMatchers {"url" [".*foo.*"]}}}}}} nil)))
          (is (= :allow (#'m.approval/approval all-tools request-tool {"url" "foobar"} {}
                                               {:toolCall {:approval {:allow {"web__request" {:argsMatchers {"url" ["foo.*"]}}}}}} nil))))
        (testing "has arg and matches for deny"
          (is (= :deny (#'m.approval/approval all-tools request-tool {"url" "http://foo.com"} {}
                                              {:toolCall {:approval {:deny {"web__request" {:argsMatchers {"url" [".*foo.*"]}}}}}} nil)))
          (is (= :deny (#'m.approval/approval all-tools request-tool {"url" "foobar"} {}
                                              {:toolCall {:approval {:deny {"web__request" {:argsMatchers {"url" ["foo.*"]}}}}}} nil))))
        (testing "has not that arg"
          (is (= :ask (#'m.approval/approval all-tools request-tool {"crazy-url" "http://foo.com"} {}
                                             {:toolCall {:approval {:allow {"web__request" {:argsMatchers {"url" [".*foo.*"]}}}}}} nil))))))
    (testing "if no approval config matches"
      (testing "checks byDefault"
        (testing "when 'ask', return true"
          (is (= :ask (#'m.approval/approval all-tools request-tool {} {} {:toolCall {:approval {:byDefault "ask"}}} nil))))
        (testing "when 'allow', return false"
          (is (= :allow (#'m.approval/approval all-tools request-tool {} {} {:toolCall {:approval {:byDefault "allow"}}} nil)))))
      (testing "fallback to manual approval"
        (is (= :ask (#'m.approval/approval all-tools request-tool {} {} {} nil)))))))

(deftest approval-decision-test
  (let [read-tool {:name "read" :server {:name "eca"} :origin :native}
        shell-tool {:name "shell" :server {:name "eca"} :origin :native :require-approval-fn (constantly true)}
        request-tool {:name "request" :server {:name "web"} :origin :mcp}
        all-tools [read-tool shell-tool request-tool]]
    (testing "config rules return the matched selector"
      (is (= {:decision :deny :rule {:source :config-deny :selector "web__request"}}
             (#'m.approval/approval-decision all-tools request-tool {} {} {:toolCall {:approval {:deny {"web__request" {}}}}} nil)))
      (is (= {:decision :ask :rule {:source :config-ask :selector "web"}}
             (#'m.approval/approval-decision all-tools request-tool {} {} {:toolCall {:approval {:ask {"web" {}}}}} nil)))
      (is (= {:decision :allow :rule {:source :config-allow :selector "read"}}
             (#'m.approval/approval-decision all-tools read-tool {} {} {:toolCall {:approval {:allow {"read" {}}}}} nil))))
    (testing "config rules with argsMatchers return the matched regex"
      (is (= {:decision :allow :rule {:source :config-allow :selector "web__request" :args-matcher ".*foo.*"}}
             (#'m.approval/approval-decision all-tools request-tool {"url" "http://foo.com"} {}
                                             {:toolCall {:approval {:allow {"web__request" {:argsMatchers {"url" [".*bar.*" ".*foo.*"]}}}}}} nil))))
    (testing "session remember"
      (is (= {:decision :allow :rule {:source :session-remember}}
             (#'m.approval/approval-decision all-tools read-tool {} (db-remembering {"eca__read" {:remember-to-approve? true}}) {} nil
                                             {:chat-id "c1"}))))
    (testing "tool built-in check"
      (is (= {:decision :ask :rule {:source :tool-built-in-check}}
             (#'m.approval/approval-decision all-tools shell-tool {} {} {} nil))))
    (testing "legacy manualApproval"
      (is (= {:decision :ask :rule {:source :legacy-manual-approval}}
             (#'m.approval/approval-decision all-tools request-tool {} {} {:toolCall {:manualApproval true}} nil))))
    (testing "byDefault"
      (is (= {:decision :allow :rule {:source :by-default}}
             (#'m.approval/approval-decision all-tools request-tool {} {} {:toolCall {:approval {:byDefault "allow"}}} nil))))
    (testing "fallback when no config"
      (is (= {:decision :ask :rule {:source :fallback}}
             (#'m.approval/approval-decision all-tools request-tool {} {} {} nil))))
    (testing "trust promotion keeps the matched rule"
      (is (= {:decision :trust/allow :rule {:source :fallback}}
             (#'m.approval/approval-decision all-tools request-tool {} {} {} nil {:trust true}))))))

(deftest inside-workspace-approval-test
  (let [read-tool {:name "read_file" :server {:name "eca"} :origin :native
                   :require-approval-fn (tools.util/require-approval-when-outside-workspace ["path"])}
        write-tool (assoc read-tool :name "write_file")
        all-tools [read-tool write-tool]
        notes (h/file-path "/tmp/notes")
        note {"path" (h/file-path "/tmp/notes/today.md")}
        config (fn [approval] {:toolCall {:approval (merge {:allow {"eca__read_file" {}} :byDefault "ask"} approval)}})]
    (testing "a path outside the workspace asks, whatever the rules say"
      (is (= {:decision :ask :rule {:source :tool-built-in-check}}
             (#'m.approval/approval-decision all-tools read-tool note {} (config {}) nil))))
    (testing "in a folder of toolCall.approval.insideWorkspace, the rules decide"
      (is (= {:decision :allow :rule {:source :config-allow :selector "eca__read_file"}}
             (#'m.approval/approval-decision all-tools read-tool note {} (config {:insideWorkspace [notes]}) nil)))
      (is (= {:decision :ask :rule {:source :by-default}}
             (#'m.approval/approval-decision all-tools write-tool note {} (config {:insideWorkspace [notes]}) nil))))
    (testing "an agent's list adds to the global one"
      (let [config (assoc-in (config {:insideWorkspace [(h/file-path "/tmp/other")]})
                             [:agent "plan" :toolCall :approval :insideWorkspace] [notes])]
        (is (= {:decision :allow :rule {:source :config-allow :selector "eca__read_file"}}
               (#'m.approval/approval-decision all-tools read-tool note {} config "plan")))
        (is (= {:decision :ask :rule {:source :tool-built-in-check}}
               (#'m.approval/approval-decision all-tools read-tool note {} config "code"))
            "only for that agent")))))

(deftest granular-approval-test
  (let [shell-tool {:name "shell_command" :server {:name "eca"} :origin :native
                    :approval-keys-fn #(f.tools/tool-approval-keys "shell_command" %)}
        all-tools [shell-tool]
        remembered-db (db-remembering {"eca__shell_command" {:remembered-command-keys #{"git status" "rg"}}})
        opts {:chat-id "c1"}]
    (testing "tool-approval-keys derives keys from native definitions"
      (is (= {:keys #{"git status" "rg"} :complete? true}
             (f.tools/tool-approval-keys "shell_command" {"command" "git status && rg foo"})))
      (is (= {:keys #{"git status"} :complete? true}
             (f.tools/tool-approval-keys "git" {"command" "git status"})))
      (is (= {:keys #{"cd" "git log"} :complete? true}
             (f.tools/tool-approval-keys "git" {"command" "cd /workspace && git log -1"})))
      (is (nil? (f.tools/tool-approval-keys "read_file" {"path" "/foo"}))))
    (testing "all commands remembered approves"
      (is (= :allow (#'m.approval/approval all-tools shell-tool {"command" "git status | rg foo"} remembered-db {} nil opts))))
    (testing "partially remembered command asks"
      (is (= :ask (#'m.approval/approval all-tools shell-tool {"command" "git status && git push"} remembered-db {} nil opts))))
    (testing "dynamic constructs ask even when commands remembered"
      (is (= :ask (#'m.approval/approval all-tools shell-tool {"command" "git status $(evil)"} remembered-db {} nil opts))))
    (testing "blanket remember-to-approve? is ignored for granular tools"
      (is (= :ask (#'m.approval/approval all-tools shell-tool {"command" "ls"}
                                         (db-remembering {"eca__shell_command" {:remember-to-approve? true}}) {} nil opts))))
    (testing "config deny beats remembered commands"
      (is (= :deny (#'m.approval/approval all-tools shell-tool {"command" "git status"} remembered-db
                                          {:toolCall {:approval {:deny {"shell_command" {}}}}} nil opts))))
    (testing "config deny beats legacy remember-to-approve?"
      (is (= :deny (#'m.approval/approval all-tools {:name "request" :server {:name "web"} :origin :mcp} {}
                                          (db-remembering {"web__request" {:remember-to-approve? true}})
                                          {:toolCall {:approval {:deny {"web__request" {}}}}} nil opts))))
    (testing "missing command args ask"
      (is (= :ask (#'m.approval/approval all-tools shell-tool {} remembered-db {} nil opts))))))

(deftest chat-scoped-remembered-approvals-test
  (let [write-tool {:name "write_file" :server {:name "eca"} :origin :native}
        mcp-write-tool {:name "write_file" :server {:name "fs"} :origin :mcp}
        shell-tool {:name "shell_command" :server {:name "eca"} :origin :native
                    :approval-keys-fn #(f.tools/tool-approval-keys "shell_command" %)}
        all-tools [write-tool mcp-write-tool shell-tool]
        db {:chats {"a" {:id "a" :remembered-approvals {"eca__write_file" {:remember-to-approve? true}
                                                        "eca__shell_command" {:remembered-command-keys #{"rg"}}}}
                    "a-sub" {:id "a-sub" :parent-chat-id "a"}
                    "b" {:id "b"}}}
        approval (fn [tool args chat-id]
                   (#'m.approval/approval all-tools tool args db {} nil {:chat-id chat-id}))]
    (testing "remembered approvals apply to the chat that remembered them"
      (is (= :allow (approval write-tool {} "a")))
      (is (= :allow (approval shell-tool {"command" "rg foo"} "a"))))
    (testing "a remembered approval in chat A does not auto-approve in chat B"
      (is (= :ask (approval write-tool {} "b")))
      (is (= :ask (approval shell-tool {"command" "rg foo"} "b"))))
    (testing "a subagent inherits the remembered approvals of its parent chat"
      (is (= :allow (approval write-tool {} "a-sub")))
      (is (= :allow (approval shell-tool {"command" "rg foo"} "a-sub"))))
    (testing "without a chat no remembered approval applies"
      (is (= :ask (approval write-tool {} nil))))
    (testing "approvals are remembered by full tool name, a same-named tool of another server still asks"
      (is (= :ask (approval mcp-write-tool {} "a"))))))

(deftest approval-trust-test
  (let [request-tool {:name "request" :server {:name "web"} :origin :mcp}
        all-tools [request-tool]]
    (testing "trust promotes :ask to :trust/allow"
      (is (= :trust/allow (#'m.approval/approval all-tools request-tool {} {} {} nil {:trust true}))))
    (testing "trust does not override :deny"
      (is (= :deny (#'m.approval/approval all-tools request-tool {} {}
                                          {:toolCall {:approval {:deny {"web__request" {}}}}} nil {:trust true}))))
    (testing "trust does not change :allow"
      (is (= :allow (#'m.approval/approval all-tools request-tool {} {}
                                           {:toolCall {:approval {:allow {"web__request" {}}}}} nil {:trust true}))))
    (testing "no trust returns :ask as-is"
      (is (= :ask (#'m.approval/approval all-tools request-tool {} {} {} nil)))
      (is (= :ask (#'m.approval/approval all-tools request-tool {} {} {} nil nil)))
      (is (= :ask (#'m.approval/approval all-tools request-tool {} {} {} nil {:trust false}))))))

(deftest agent-specific-approval-test
  (let [shell-tool {:name "shell_command" :full-name "eca__shell_command" :server {:name "eca"} :origin :native}
        read-tool {:name "read_file" :full-name "eca__read_file" :server {:name "eca"} :origin :native}
        all-tools [shell-tool read-tool]]
    (testing "agent-specific approval overrides global rules"
      (let [config {:toolCall {:approval {:byDefault "allow"}}
                    :agent {"plan" {:toolCall {:approval {:deny {"shell_command" {:argsMatchers {"command" [".*rm.*"]}}}
                                                          :byDefault "ask"}}}}}]
        ;; Global config would allow shell commands (no agent specified)
        (is (= :allow (#'m.approval/approval all-tools shell-tool {"command" "ls -la"} {} config nil)))
        ;; But plan agent denies rm commands
        (is (= :deny (#'m.approval/approval all-tools shell-tool {"command" "rm file.txt"} {} config "plan")))
        ;; Plan agent allows other shell commands with ask (agent byDefault)
        (is (= :ask (#'m.approval/approval all-tools shell-tool {"command" "ls -la"} {} config "plan")))))
    (testing "agent without toolCall approval uses global rules"
      (let [config {:toolCall {:approval {:allow {"read_file" {}}}}
                    :agent {"custom" {}}}]
        (is (= :allow (#'m.approval/approval all-tools read-tool {} {} config "custom")))))
    (testing "plan agent shell restrictions work as configured"
      (let [config {:agent {"plan" {:toolCall {:approval {:deny {"shell_command" {:argsMatchers {"command" [".*>.*" ".*rm.*"]}}}}}}}}]
        (is (= :deny (#'m.approval/approval all-tools shell-tool {"command" "cat file.txt > output.txt"} {} config "plan")))
        (is (= :deny (#'m.approval/approval all-tools shell-tool {"command" "rm -rf folder"} {} config "plan")))
        ;; Safe commands should use byDefault (ask)
        (is (= :ask (#'m.approval/approval all-tools shell-tool {"command" "ls -la"} {} config "plan")))))
    (testing "code agent does NOT have plan restrictions"
      (let [config {:agent {"plan" {:toolCall {:approval {:deny {"shell_command" {:argsMatchers {"command" [".*>.*" ".*rm.*"]}}}}}}}}]
        ;; Same dangerous commands that are denied in plan mode should be allowed in build mode
        (is (= :ask (#'m.approval/approval all-tools shell-tool {"command" "rm -rf folder"} {} config "code")))
        (is (= :ask (#'m.approval/approval all-tools shell-tool {"command" "cat file.txt > output.txt"} {} config "code")))
        ;; No agent specified (nil) should also not have plan restrictions
        (is (= :ask (#'m.approval/approval all-tools shell-tool {"command" "rm file.txt"} {} config nil)))))
    (testing "regex patterns match dangerous commands correctly"
      (let [config (config/initial-config)]
        ;; Test output redirection patterns
        (is (= :deny (#'m.approval/approval all-tools shell-tool {"command" "echo test > file.txt"} {} config "plan")))
        (is (= :deny (#'m.approval/approval all-tools shell-tool {"command" "ls >> log.txt"} {} config "plan")))
        ;; Test pipe to dangerous commands
        (is (= :deny (#'m.approval/approval all-tools shell-tool {"command" "echo test | tee file.txt"} {} config "plan")))
        (is (= :deny (#'m.approval/approval all-tools shell-tool {"command" "find . | xargs rm"} {} config "plan")))
        ;; Test file operations
        (is (= :deny (#'m.approval/approval all-tools shell-tool {"command" "rm -rf folder"} {} config "plan")))
        (is (= :deny (#'m.approval/approval all-tools shell-tool {"command" "touch newfile.txt"} {} config "plan")))
        ;; Test git operations
        (is (= :deny (#'m.approval/approval all-tools shell-tool {"command" "git add ."} {} config "plan")))
        ;; Test safe commands that should NOT be denied
        (is (= :allow (#'m.approval/approval all-tools shell-tool {"command" "ls -la"} {} config "plan")))
        (is (= :ask (#'m.approval/approval all-tools shell-tool {"command" "git status"} {} config "plan")))
        ;; Test /dev/null redirections are allowed (not denied)
        (is (= :allow (#'m.approval/approval all-tools shell-tool {"command" "find /home/user/project -path '*/hato*' -name '*.clj' 2>/dev/null | sort"} {} config "plan")))
        (is (= :allow (#'m.approval/approval all-tools shell-tool {"command" "ls -la 2>/dev/null"} {} config "plan")))
        (is (= :ask (#'m.approval/approval all-tools shell-tool {"command" "some-cmd >/dev/null 2>&1"} {} config "plan")))
        (is (= :ask (#'m.approval/approval all-tools shell-tool {"command" "some-cmd 2> /dev/null"} {} config "plan")))
        ;; But redirection to actual files is still denied
        (is (= :deny (#'m.approval/approval all-tools shell-tool {"command" "echo test 2> error.log"} {} config "plan")))))))

(deftest plan-mode-approval-restrictions-test
  (let [shell-tool {:name "shell_command" :server {:name "eca"} :origin :native}
        all-tools [shell-tool]
        config (config/initial-config)]

    (testing "dangerous commands blocked in plan mode via approval"
      (are [command] (= :deny
                        (#'m.approval/approval all-tools shell-tool
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
                        (#'m.approval/approval all-tools shell-tool
                                               {"command" command} {} config "plan"))
        "python --version"  ; not matching dangerous patterns, defaults to ask
        "node script.js"     ; not matching dangerous patterns, defaults to ask
        "clojure -M:test"))  ; not matching dangerous patterns, defaults to ask

    (testing "safe commands not denied in plan mode"
      (are [command] (not= :deny
                           (#'m.approval/approval all-tools shell-tool
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
                           (#'m.approval/approval all-tools shell-tool
                                                  {"command" command} {} config "plan"))
        "gh api repos/editor-code-assistant/eca-emacs/contents/eca-chat.el --jq '.content' 2>/dev/null | base64 -d 2>/dev/null > /tmp/eca-chat.el && wc -l /tmp/eca-chat.el"
        "echo test > /tmp/output.txt"
        "cat file.txt > /tmp/result.log"
        "ls -la >> /tmp/listing.txt"
        "some-cmd 2> /tmp/errors.log"
        "bash -c 'echo test > /tmp/file.txt'"))

    (testing "same commands work fine in code agent mode (not denied)"
      (are [command] (not= :deny
                           (#'m.approval/approval all-tools shell-tool
                                                  {"command" command} {} config "code"))
        "echo 'test' > file.txt"
        "rm file.txt"
        "git add ."
        "python --version"))))
