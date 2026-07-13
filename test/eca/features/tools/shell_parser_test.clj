(ns eca.features.tools.shell-parser-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.tools.shell-parser :as shell-parser]))

(deftest parse-test
  (testing "single command"
    (is (= {:commands [{:command "echo" :args ["hello"] :approvalKey "echo"}]}
           (shell-parser/parse "echo hello"))))
  (testing "chained and piped commands"
    (is (= {:commands [{:command "cd" :args ["/tmp"] :approvalKey "cd"}
                       {:command "ls" :args ["-la" "*.clj"] :approvalKey "ls"}
                       {:command "grep" :args ["foo"] :approvalKey "grep"}]}
           (shell-parser/parse "cd /tmp && ls -la *.clj | grep foo")))
    (is (= {:commands [{:command "a" :args [] :approvalKey "a"}
                       {:command "b" :args [] :approvalKey "b"}
                       {:command "c" :args [] :approvalKey "c"}]}
           (shell-parser/parse "a; b || c"))))
  (testing "quotes preserve separators and spaces"
    (is (= {:commands [{:command "git" :args ["commit" "-m" "a; b && c"] :approvalKey "git commit"}]}
           (shell-parser/parse "git commit -m \"a; b && c\"")))
    (is (= {:commands [{:command "echo" :args ["it's"] :approvalKey "echo"}]}
           (shell-parser/parse "echo \"it's\"")))
    (is (= {:commands [{:command "echo" :args ["a b"] :approvalKey "echo"}]}
           (shell-parser/parse "echo 'a b'"))))
  (testing "escaped spaces keep words together"
    (is (= {:commands [{:command "echo" :args ["a b"] :approvalKey "echo"}]}
           (shell-parser/parse "echo a\\ b"))))
  (testing "env assignment prefixes are stripped"
    (is (= {:commands [{:command "make" :args ["test"] :approvalKey "make test"}]}
           (shell-parser/parse "FOO=1 BAR=x make test"))))
  (testing "comments are ignored"
    (is (= {:commands [{:command "ls" :args [] :approvalKey "ls"}]}
           (shell-parser/parse "ls # some comment"))))
  (testing "commands that always ask have no approvalKey"
    (is (= {:commands [{:command "echo" :args ["hi"]}]}
           (shell-parser/parse "echo hi > /tmp/x")))
    (is (= {:commands [{:command "sudo" :args ["ls"]}]}
           (shell-parser/parse "sudo ls"))))
  (testing "multiline commands split on newline"
    (is (= {:commands [{:command "ls" :args [] :approvalKey "ls"}
                       {:command "pwd" :args [] :approvalKey "pwd"}]}
           (shell-parser/parse "ls\npwd"))))
  (testing "unsafe constructs return nil"
    (is (nil? (shell-parser/parse "echo $(date)")))
    (is (nil? (shell-parser/parse "echo `date`")))
    (is (nil? (shell-parser/parse "(cd /tmp && ls)")))
    (is (nil? (shell-parser/parse "diff <(ls a) <(ls b)")))
    (is (nil? (shell-parser/parse "cat <<EOF\nfoo\nEOF")))
    (is (nil? (shell-parser/parse "echo \"unbalanced"))))
  (testing "blank or nil returns nil"
    (is (nil? (shell-parser/parse nil)))
    (is (nil? (shell-parser/parse "")))
    (is (nil? (shell-parser/parse "   ")))))

(deftest approval-keys-test
  (testing "plain commands use the command as key"
    (is (= {:keys #{"rg"} :complete? true}
           (shell-parser/approval-keys "rg foo ."))))
  (testing "subcommand-aware commands include the subcommand"
    (is (= {:keys #{"git checkout"} :complete? true}
           (shell-parser/approval-keys "git checkout -b foo")))
    (is (= {:keys #{"npm install"} :complete? true}
           (shell-parser/approval-keys "npm install --save-dev x"))))
  (testing "chains derive one key per command"
    (is (= {:keys #{"git status" "rg" "head"} :complete? true}
           (shell-parser/approval-keys "git status && rg foo | head -5"))))
  (testing "quoted args do not leak separators or keys"
    (is (= {:keys #{"git commit"} :complete? true}
           (shell-parser/approval-keys "git commit -m \"a && rm -rf /\""))))
  (testing "fd dups and /dev/null redirects are allowed"
    (is (= {:keys #{"git status"} :complete? true}
           (shell-parser/approval-keys "git status 2>&1 >/dev/null"))))
  (testing "output redirections to files are incomplete"
    (is (= {:keys #{} :complete? false}
           (shell-parser/approval-keys "echo hi > /tmp/x")))
    (is (false? (:complete? (shell-parser/approval-keys "git status && echo hi >> out.txt")))))
  (testing "wrapper commands yield no key"
    (is (= {:keys #{} :complete? false}
           (shell-parser/approval-keys "sudo rm -rf /")))
    (is (= {:keys #{} :complete? false}
           (shell-parser/approval-keys "bash -c 'rm -rf /'")))
    (is (= {:keys #{} :complete? false}
           (shell-parser/approval-keys "xargs rm"))))
  (testing "dynamic command words yield no key"
    (is (= {:keys #{} :complete? false}
           (shell-parser/approval-keys "$FOO bar")))
    (is (false? (:complete? (shell-parser/approval-keys "git $SUB")))))
  (testing "partial keys still returned for mixed chains"
    (is (= {:keys #{"git status"} :complete? false}
           (shell-parser/approval-keys "git status && sudo make install"))))
  (testing "subcommand-aware command without subcommand yields no key"
    (is (= {:keys #{} :complete? false}
           (shell-parser/approval-keys "git"))))
  (testing "unparseable returns nil"
    (is (nil? (shell-parser/approval-keys "echo $(date)")))
    (is (nil? (shell-parser/approval-keys nil)))
    (is (nil? (shell-parser/approval-keys " ")))))
