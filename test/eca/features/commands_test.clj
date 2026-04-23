(ns eca.features.commands-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [eca.features.commands :as f.commands]
   [eca.test-helper :as h]))

(h/reset-components-before-test)

(deftest get-custom-command-tests
  (testing "returns nil when command not found"
    (is (nil? (#'f.commands/get-custom-command "nope" [] []))))

  (testing "$ARGS is replaced with the joined args"
    (let [custom [{:name "greet" :content "Hello $ARGS!"}]]
      (is (= "Hello Alice Bob!"
             (#'f.commands/get-custom-command "greet" ["Alice" "Bob"] custom)))))

  (testing "numbered $ARGn placeholders are replaced and $ARGS contains all args"
    (let [custom [{:name "pair" :content "First:$ARG1 Second:$ARG2 All:$ARGS"}]]
      (is (= "First:one Second:two All:one two"
             (#'f.commands/get-custom-command "pair" ["one" "two"] custom)))))

  (testing "unmatched placeholders remain when args are missing"
    (let [custom [{:name "partial" :content "A:$ARG1 B:$ARG2 C:$ARG3"}]]
      (is (= "A:only B: C:$ARG3"
             (#'f.commands/get-custom-command "partial" ["only" ""] custom)))))

  (testing "multiple occurrences of the same placeholder are all replaced"
    (let [custom [{:name "dup" :content "$ARG1-$ARG1 $ARGS"}]]
      (is (= "x-x x y"
             (#'f.commands/get-custom-command "dup" ["x" "y"] custom)))))

  (testing "$ARGUMENTS is supported as alias for $ARGS"
    (let [custom [{:name "test" :content "Process $ARGUMENTS here"}]]
      (is (= "Process one two here"
             (#'f.commands/get-custom-command "test" ["one" "two"] custom))))))

(deftest substitute-args-test
  (testing "replaces $ARGS with all args joined"
    (is (= "Review https://github.com/org/repo/pull/1"
           (#'f.commands/substitute-args "Review $ARGS" ["https://github.com/org/repo/pull/1"]))))

  (testing "replaces $ARGUMENTS with all args joined"
    (is (= "Review https://github.com/org/repo/pull/1"
           (#'f.commands/substitute-args "Review $ARGUMENTS" ["https://github.com/org/repo/pull/1"]))))

  (testing "replaces positional $ARGn placeholders"
    (is (= "First:a Second:b"
           (#'f.commands/substitute-args "First:$ARG1 Second:$ARG2" ["a" "b"]))))

  (testing "unmatched positional placeholders remain"
    (is (= "A:x B:$ARG2"
           (#'f.commands/substitute-args "A:$ARG1 B:$ARG2" ["x"]))))

  (testing "returns content as-is when no placeholders"
    (is (= "No placeholders here"
           (#'f.commands/substitute-args "No placeholders here" ["ignored"]))))

  (testing "works with empty args"
    (is (= "Hello  world"
           (#'f.commands/substitute-args "Hello $ARGS world" []))))

  (testing "replaces Claude Code compatible $n positional placeholders"
    (is (= "First:a Second:b"
           (#'f.commands/substitute-args "First:$1 Second:$2" ["a" "b"]))))

  (testing "replaces both $ARGn and $n placeholders"
    (is (= "A:x B:x"
           (#'f.commands/substitute-args "A:$ARG1 B:$1" ["x"])))))

(deftest config-commands-plugin-prefix-test
  (let [tmp-dir (fs/create-temp-dir)]
    (try
      (testing "plugin commands get prefixed with plugin name"
        (let [cmd-file (fs/file tmp-dir "deploy.md")]
          (spit cmd-file "Plugin command body")
          (let [config {:commands [{:path (str cmd-file) :plugin "ui"}]}
                result (vec (#'f.commands/config-commands config []))]
            (is (= 1 (count result)))
            (is (= "ui:deploy" (:name (first result))))
            (is (= "ui" (:plugin (first result)))))))

      (testing "plugin command with same name as plugin drops the prefix"
        (let [cmd-file (fs/file tmp-dir "tdd.md")]
          (spit cmd-file "TDD body")
          (let [config {:commands [{:path (str cmd-file) :plugin "tdd"}]}
                result (vec (#'f.commands/config-commands config []))]
            (is (= 1 (count result)))
            (is (= "tdd" (:name (first result))))
            (is (= "tdd" (:plugin (first result)))))))

      (testing "user-config commands without a plugin stay unprefixed"
        (let [cmd-file (fs/file tmp-dir "plain.md")]
          (spit cmd-file "Plain body")
          (let [config {:commands [{:path (str cmd-file)}]}
                result (vec (#'f.commands/config-commands config []))]
            (is (= 1 (count result)))
            (is (= "plain" (:name (first result))))
            (is (not (contains? (first result) :plugin))))))
      (finally
        (fs/delete-tree tmp-dir)))))

(deftest all-commands-include-model-command-test
  (let [commands (f.commands/all-commands {:workspace-folders []} {})]
    (is (some #(= {:name "model"
                   :type :native
                   :description "Select model for current chat (Ex: /model anthropic/claude-sonnet-4-6)"
                   :arguments [{:name "full-model"}]}
                 %)
              commands))))

(deftest handle-model-command-test
  (testing "lists current and available models when no arg is given"
    (swap! (h/db*) assoc :models {"anthropic/claude-sonnet-4-6" {}
                                  "openai/gpt-5.2" {}})
    (let [result (f.commands/handle-command! "model"
                                             []
                                             {:chat-id "chat-1"
                                              :db* (h/db*)
                                              :config (h/config)
                                              :messenger (h/messenger)
                                              :full-model "openai/gpt-5.2"
                                              :agent "code"
                                              :agent-config {}
                                              :all-tools []
                                              :instructions {}
                                              :user-messages []
                                              :metrics (h/metrics)})]
      (is (= :chat-messages (:type result)))
      (is (re-find #"Current model: `openai/gpt-5.2`"
                   (get-in result [:chats "chat-1" :messages 0 :content 0 :text])))
      (is (re-find #"anthropic/claude-sonnet-4-6"
                   (get-in result [:chats "chat-1" :messages 0 :content 0 :text])))))

  (testing "selecting model updates client selection and clears variant options"
    (swap! (h/db*) assoc :models {"anthropic/claude-sonnet-4-6" {}
                                  "openai/gpt-5.2" {}})
    (swap! (h/db*) assoc-in [:chats "chat-1" :variant] "high")
    (let [result (f.commands/handle-command! "model"
                                             ["anthropic/claude-sonnet-4-6"]
                                             {:chat-id "chat-1"
                                              :db* (h/db*)
                                              :config (h/config)
                                              :messenger (h/messenger)
                                              :full-model "openai/gpt-5.2"
                                              :agent "code"
                                              :all-tools []
                                              :instructions {}
                                              :user-messages []
                                              :metrics (h/metrics)})]
      (is (= :chat-messages (:type result)))
      (is (= "anthropic/claude-sonnet-4-6"
             (get-in @(h/db*) [:chats "chat-1" :model])))
      (is (contains? (get-in @(h/db*) [:chats "chat-1"]) :variant))
      (is (nil? (get-in @(h/db*) [:chats "chat-1" :variant])))
      (is (re-find #"Using model defaults\."
                   (get-in result [:chats "chat-1" :messages 0 :content 0 :text])))
      (is (= [{:chat {:select-model "anthropic/claude-sonnet-4-6"
                      :variants []
                      :select-variant nil}}]
             (:config-updated (h/messages))))))

  (testing "invalid model returns helpful error"
    (swap! (h/db*) assoc :models {"openai/gpt-5.2" {}})
    (let [result (f.commands/handle-command! "model"
                                             ["bad/model"]
                                             {:chat-id "chat-1"
                                              :db* (h/db*)
                                              :config (h/config)
                                              :messenger (h/messenger)
                                              :full-model "openai/gpt-5.2"
                                              :agent "code"
                                              :agent-config {}
                                              :all-tools []
                                              :instructions {}
                                              :user-messages []
                                              :metrics (h/metrics)})]
      (is (re-find #"Unknown model: `bad/model`"
                   (get-in result [:chats "chat-1" :messages 0 :content 0 :text]))))))
