(ns eca.features.commands-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.db :as db]
   [eca.features.chat.export :as f.chat.export]
   [eca.features.commands :as f.commands]
   [eca.features.rules :as f.rules]
   [eca.shared :as shared]
   [eca.test-helper :as h]))

(h/reset-components-before-test)

(defn ^:private command-context [chat-id]
  {:chat-id chat-id
   :db* (h/db*)
   :config (h/config)
   :messenger (h/messenger)
   :full-model "openai/gpt-5.2"
   :agent "code"
   :all-tools []
   :instructions {}
   :user-messages []
   :metrics (h/metrics)})

(deftest prompt-show-text-test
  (testing "uses compact section headings and omits the /prompt-show command itself"
    (let [result (#'f.commands/prompt-show-text
                  {:static "system prompt"}
                  [{:role "user"
                    :content [{:type :text :text "/prompt-show"}
                              {:type :text :text "<editor-state/>"}]}])]
      (is (string/includes? result "────────────────────────────────────────\n# Instructions (System prompt)\n────────────────────────────────────────\nsystem prompt"))
      (is (string/includes? result "system prompt\n\n────────────────────────────────────────\n# Chat (User prompt)"))
      (is (string/includes? result "────────────────────────────────────────\n# Chat (User prompt)\n────────────────────────────────────────\n<editor-state/>"))
      (is (string/includes? result "_Tool schemas are sent separately and are not included in this text dump._"))
      (is (not (string/includes? result "/prompt-show"))))))

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
           (#'f.commands/substitute-args "A:$ARG1 B:$1" ["x"]))))

  (testing "renders named {{placeholders}} via selmer mapping args by order"
    (is (= "Weather for Paris in metric"
           (#'f.commands/substitute-args "Weather for {{city}} in {{units}}" ["Paris" "metric"]))))

  (testing "named placeholders without a provided arg render empty"
    (is (= "Weather for Paris in "
           (#'f.commands/substitute-args "Weather for {{city}} in {{units}}" ["Paris"])))))

(deftest config-commands-plugin-prefix-test
  (let [tmp-dir (fs/create-temp-dir)]
    (try
      (testing "plugin commands get prefixed with plugin name"
        (let [cmd-file (fs/file tmp-dir "deploy.md")]
          (spit cmd-file "Plugin command body")
          (let [config {:pureConfig true :commands [{:path (str cmd-file) :plugin "ui"}]}
                result (vec (#'f.commands/custom-commands config []))]
            (is (= 1 (count result)))
            (is (= "ui:deploy" (:name (first result))))
            (is (= "ui" (:plugin (first result)))))))

      (testing "plugin command with same name as plugin drops the prefix"
        (let [cmd-file (fs/file tmp-dir "tdd.md")]
          (spit cmd-file "TDD body")
          (let [config {:pureConfig true :commands [{:path (str cmd-file) :plugin "tdd"}]}
                result (vec (#'f.commands/custom-commands config []))]
            (is (= 1 (count result)))
            (is (= "tdd" (:name (first result))))
            (is (= "tdd" (:plugin (first result)))))))

      (testing "user-config commands without a plugin stay unprefixed"
        (let [cmd-file (fs/file tmp-dir "plain.md")]
          (spit cmd-file "Plain body")
          (let [config {:pureConfig true :commands [{:path (str cmd-file)}]}
                result (vec (#'f.commands/custom-commands config []))]
            (is (= 1 (count result)))
            (is (= "plain" (:name (first result))))
            (is (not (contains? (first result) :plugin))))))

      (testing "user-config command directories load markdown files recursively"
        (let [cmd-dir (fs/file tmp-dir "commands")
              nested-dir (fs/file cmd-dir "nested")]
          (fs/create-dirs nested-dir)
          (spit (fs/file cmd-dir "review.md") "Review body")
          (spit (fs/file nested-dir "ship.md") "Ship body")
          (spit (fs/file nested-dir "ignore.txt") "Ignored")
          (let [config {:pureConfig true :commands [{:path (str cmd-dir)}]}
                result (vec (#'f.commands/custom-commands config []))]
            (is (= #{"review" "ship"} (set (map :name result))))
            (is (= #{"Review body" "Ship body"} (set (map :content result)))))))

      (finally
        (fs/delete-tree tmp-dir)))))

(deftest command-arguments-test
  (let [tmp-dir (fs/create-temp-dir)]
    (try
      (testing "command with $ARG1 placeholder detects arguments"
        (let [cmd-file (fs/file tmp-dir "greet.md")]
          (spit cmd-file "Greet $ARG1!")
          (let [config {:pureConfig true :commands [{:path (str cmd-file)}]}
                result (vec (#'f.commands/custom-commands config []))]
            (is (= 1 (count result)))
            (is (= "greet" (:name (first result))))
            (is (= [{:name "arg1" :required true}]
                   (:arguments (first result)))))))

      (testing "command without placeholders has empty arguments"
        (let [cmd-file (fs/file tmp-dir "simple.md")]
          (spit cmd-file "Simple body")
          (let [config {:pureConfig true :commands [{:path (str cmd-file)}]}
                result (vec (#'f.commands/custom-commands config []))]
            (is (= [] (:arguments (first result)))))))

      (finally
        (fs/delete-tree tmp-dir)))))

(deftest command-frontmatter-test
  (let [tmp-dir (fs/create-temp-dir)]
    (try
      (testing "frontmatter description and named arguments are parsed"
        (let [cmd-file (fs/file tmp-dir "weather.md")]
          (spit cmd-file (string/join "\n"
                                      ["---"
                                       "description: Generate a weather report"
                                       "arguments:"
                                       "  - name: city"
                                       "    description: City to report on"
                                       "    required: true"
                                       "  - name: units"
                                       "    description: metric or imperial"
                                       "    required: false"
                                       "---"
                                       "Weather for {{city}} using {{units}} units."]))
          (let [config {:pureConfig true :commands [{:path (str cmd-file)}]}
                result (first (#'f.commands/custom-commands config []))]
            (is (= "weather" (:name result)))
            (is (= "Generate a weather report" (:description result)))
            (is (= "Weather for {{city}} using {{units}} units." (:content result)))
            (is (= [{:name "city" :description "City to report on" :required true}
                    {:name "units" :description "metric or imperial" :required false}]
                   (:arguments result))))))

      (testing "frontmatter arguments enrich positional args by index"
        (let [cmd-file (fs/file tmp-dir "pos.md")]
          (spit cmd-file (string/join "\n"
                                      ["---"
                                       "arguments:"
                                       "  - name: city"
                                       "    description: City name"
                                       "---"
                                       "Weather for $1"]))
          (let [config {:pureConfig true :commands [{:path (str cmd-file)}]}
                result (first (#'f.commands/custom-commands config []))]
            (is (= [{:name "city" :description "City name" :required true}]
                   (:arguments result))))))

      (testing "command without frontmatter still loads with positional args"
        (let [cmd-file (fs/file tmp-dir "plain.md")]
          (spit cmd-file "Hello $ARG1")
          (let [config {:pureConfig true :commands [{:path (str cmd-file)}]}
                result (first (#'f.commands/custom-commands config []))]
            (is (nil? (:description result)))
            (is (= "Hello $ARG1" (:content result)))
            (is (= [{:name "arg1" :required true}] (:arguments result))))))

      (testing "command mixing positional and named placeholders is skipped"
        (let [cmd-file (fs/file tmp-dir "bad.md")]
          (spit cmd-file "Use $1 and {{city}}")
          (let [config {:pureConfig true :commands [{:path (str cmd-file)}]}]
            (is (empty? (#'f.commands/custom-commands config []))))))

      (finally
        (fs/delete-tree tmp-dir)))))

(deftest custom-command-description-fallback-test
  (let [tmp-dir (fs/create-temp-dir)]
    (try
      (testing "falls back to the file path when no frontmatter description"
        (let [cmd-file (fs/file tmp-dir "nodesc.md")]
          (spit cmd-file "Body $ARG1")
          (let [config {:pureConfig true :commands [{:path (str cmd-file)}]}
                cmd (->> (f.commands/all-commands {:workspace-folders []} config)
                         (filter #(= "nodesc" (:name %)))
                         first)]
            (is (= :custom-prompt (:type cmd)))
            (is (= (str (fs/canonicalize cmd-file)) (:description cmd))))))

      (testing "uses the frontmatter description when present"
        (let [cmd-file (fs/file tmp-dir "withdesc.md")]
          (spit cmd-file (string/join "\n" ["---" "description: My desc" "---" "Body"]))
          (let [config {:pureConfig true :commands [{:path (str cmd-file)}]}
                cmd (->> (f.commands/all-commands {:workspace-folders []} config)
                         (filter #(= "withdesc" (:name %)))
                         first)]
            (is (= "My desc" (:description cmd))))))

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

(deftest handle-sync-system-prompt-command-test
  (testing "clears the chat prompt cache and confirms"
    (swap! (h/db*) assoc-in [:chats "chat-1" :prompt-cache] {:static "old"
                                                             :static-signature {:skills "abc"}
                                                             :agent "code"
                                                             :model "openai/gpt-5.2"})
    (let [result (f.commands/handle-command! "sync-system-prompt"
                                             []
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
      (is (not (contains? (get-in @(h/db*) [:chats "chat-1"]) :prompt-cache)))
      (is (re-find #"System prompt will be re-synced"
                   (get-in result [:chats "chat-1" :messages 0 :content 0 :text]))))))

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

(deftest restore-command-selection-scoping-test
  (testing "/resume scopes restored selection to the current chat"
    (h/reset-components!)
    (h/config! {:providers {"anthropic" {:models {"claude-sonnet-4-5"
                                                  {:variants {"low" {:effort "low"}
                                                              "high" {:effort "high"}}}}}}})
    (swap! (h/db*) assoc
           :models {"anthropic/claude-sonnet-4-5" {:tools true}}
           :last-config-notified {:chat {:select-model "openai/gpt-5.2"
                                         :select-variant "medium"
                                         :select-trust false}}
           :chats {"chat-a" {:id "chat-a"
                              :created-at 1
                              :model "anthropic/claude-sonnet-4-5"
                              :variant "low"
                              :trust true
                              :messages []}
                   "chat-b" {:id "chat-b"
                              :created-at 2
                              :model "openai/gpt-5.2"
                              :messages []}})
    (let [session-defaults (:last-config-notified (h/db))]
      (with-redefs [db/update-workspaces-cache! (fn [& _])]
        (f.commands/handle-command! "resume" ["1"] (command-context "chat-b")))
      (is (= [{:chat-id "chat-b"
               :chat {:select-model "anthropic/claude-sonnet-4-5"
                      :variants ["high" "low"]
                      :select-variant "low"}}
              {:chat-id "chat-b"
               :chat {:select-trust true}}]
             (:config-updated (h/messages))))
      (is (= {:model "anthropic/claude-sonnet-4-5"
              :variant "low"
              :trust true}
             (select-keys (get-in (h/db) [:chats "chat-b"])
                          [:model :variant :trust])))
      (is (= session-defaults (:last-config-notified (h/db))))))

  (testing "/import scopes restored selection to the imported chat"
    (h/reset-components!)
    (h/config! {:providers {"anthropic" {:models {"claude-sonnet-4-5"
                                                  {:variants {"low" {:effort "low"}
                                                              "high" {:effort "high"}}}}}}})
    (swap! (h/db*) assoc
           :models {"anthropic/claude-sonnet-4-5" {:tools true}}
           :last-config-notified {:chat {:select-model "openai/gpt-5.2"
                                         :select-variant "medium"
                                         :select-trust false}}
           :chats {"chat-b" {:id "chat-b" :messages []}})
    (let [session-defaults (:last-config-notified (h/db))
          imported-chat {:id "chat-a"
                         :title "Imported"
                         :model "anthropic/claude-sonnet-4-5"
                         :variant "low"
                         :trust true
                         :messages []}]
      (with-redefs [db/update-workspaces-cache! (fn [& _])
                    f.chat.export/import-chat! (fn [_] {:chat imported-chat})]
        (f.commands/handle-command! "import" ["/tmp/chat.edn"] (command-context "chat-b")))
      (is (= [{:chat-id "chat-a"
               :chat {:select-model "anthropic/claude-sonnet-4-5"
                      :variants ["high" "low"]
                      :select-variant "low"}}
              {:chat-id "chat-a"
               :chat {:select-trust true}}]
             (:config-updated (h/messages))))
      (is (= session-defaults (:last-config-notified (h/db)))))))

(deftest rules-command-test
  (testing "/rules shows static and path-scoped rules separately with filter metadata"
    (with-redefs [f.rules/all-rules (fn [_config _roots agent full-model]
                                      (is (= "code" agent))
                                      (is (= "anthropic/claude-sonnet-4-20250514" full-model))
                                      {:static [{:name "coding-style.md"
                                                 :scope :project
                                                 :path "/repo/.eca/rules/coding-style.md"
                                                 :agents ["code"]
                                                 :models ["claude-sonnet-4.*"]
                                                 :content "Prefer small functions."}]
                                       :path-scoped [{:name "format.md"
                                                      :scope :project
                                                      :path "/repo/.eca/rules/format.md"
                                                      :agents ["code"]
                                                      :models ["claude-sonnet-4.*"]
                                                      :paths ["src/**/*.clj"]
                                                      :content "Run formatter before saving."}]})]
      (let [db* (atom {:workspace-folders []
                       :chats {"chat-1" {:agent "code"
                                         :model "anthropic/claude-sonnet-4-20250514"}}})
            result (#'f.commands/handle-command! "rules"
                                                 []
                                                 {:chat-id "chat-1"
                                                  :db* db*
                                                  :config {}
                                                  :messenger (h/messenger)
                                                  :full-model "anthropic/claude-sonnet-4-20250514"
                                                  :agent "code"
                                                  :all-tools [{:full-name "eca__fetch_rule"}]
                                                  :instructions "ignored"
                                                  :user-messages []
                                                  :metrics (h/metrics)})
            text (get-in result [:chats "chat-1" :messages 0 :content 0 :text])]
        (is (string/includes? text "Static rules (full content is included directly in the system prompt):"))
        (is (string/includes? text "Path-scoped rules (only a catalog is included in the system prompt; load full content with fetch_rule using the rule id and target path):"))
        (is (string/includes? text "### coding-style.md (project)"))
        (is (string/includes? text "Agent filter: code"))
        (is (string/includes? text "Model filter: claude-sonnet-4.*"))
        (is (string/includes? text "Content preview: Prefer small functions."))
        (is (string/includes? text "### format.md (project)"))
        (is (string/includes? text "Path filter: src/**/*.clj"))
        (is (not (string/includes? text "Content preview: Run formatter before saving."))))))

  (testing "/rules falls back to all when filters are omitted in rule metadata"
    (with-redefs [f.rules/all-rules (fn [& _]
                                      {:static [{:name "global.md"
                                                 :scope :global
                                                 :path "/home/user/.config/eca/rules/global.md"
                                                 :content "Always active."}]
                                       :path-scoped []})]
      (let [result (#'f.commands/handle-command! "rules"
                                                 []
                                                 {:chat-id "chat-1"
                                                  :db* (atom {:workspace-folders []
                                                              :chats {"chat-1" {:agent "code"
                                                                                :model "openai/gpt-4o"}}})
                                                  :config {}
                                                  :messenger (h/messenger)
                                                  :full-model "openai/gpt-4o"
                                                  :agent "code"
                                                  :all-tools []
                                                  :instructions "ignored"
                                                  :user-messages []
                                                  :metrics (h/metrics)})
            text (get-in result [:chats "chat-1" :messages 0 :content 0 :text])]
        (is (string/includes? text "Agent filter: all"))
        (is (string/includes? text "Model filter: all")))))

  (testing "/rules hides path-scoped rules when fetch_rule is unavailable"
    (with-redefs [f.rules/all-rules (constantly {:static []
                                                 :path-scoped [{:name "format.md"
                                                                :scope :project
                                                                :path "/repo/.eca/rules/format.md"
                                                                :paths ["src/**/*.clj"]
                                                                :content "Run formatter before saving."}]})]
      (let [result (#'f.commands/handle-command! "rules"
                                                 []
                                                 {:chat-id "chat-1"
                                                  :db* (atom {:workspace-folders []
                                                              :chats {"chat-1" {:agent "code"
                                                                                :model "openai/gpt-4o"}}})
                                                  :config {}
                                                  :messenger (h/messenger)
                                                  :full-model "openai/gpt-4o"
                                                  :agent "code"
                                                  :all-tools []
                                                  :instructions "ignored"
                                                  :user-messages []
                                                  :metrics (h/metrics)})
            text (get-in result [:chats "chat-1" :messages 0 :content 0 :text])]
        (is (= "No rules available for the current agent and model." text)))))

  (testing "/rules reports when there are no available rules"
    (with-redefs [f.rules/all-rules (constantly {:static [] :path-scoped []})]
      (let [result (#'f.commands/handle-command! "rules"
                                                 []
                                                 {:chat-id "chat-1"
                                                  :db* (atom {:workspace-folders []
                                                              :chats {"chat-1" {:agent "code"
                                                                                :model "openai/gpt-4o"}}})
                                                  :config {}
                                                  :messenger (h/messenger)
                                                  :full-model "openai/gpt-4o"
                                                  :agent "code"
                                                  :all-tools []
                                                  :instructions "ignored"
                                                  :user-messages []
                                                  :metrics (h/metrics)})
            text (get-in result [:chats "chat-1" :messages 0 :content 0 :text])]
        (is (= "No rules available for the current agent and model." text))))))

(deftest extract-args-from-content-test
  (testing "detects $ARG1 placeholder"
    (is (= [{:name "arg1" :required true}]
           (shared/extract-args-from-content "Respond with $ARG1"))))

  (testing "detects multiple $ARGn placeholders"
    (is (= [{:name "arg1" :required true}
            {:name "arg2" :required true}
            {:name "arg3" :required true}]
           (shared/extract-args-from-content "First:$ARG1 Second:$ARG2 Third:$ARG3"))))

  (testing "detects $ARGS without $ARGn"
    (is (= [{:name "arg1" :required true}]
           (shared/extract-args-from-content "Use all args: $ARGS"))))

  (testing "detects $ARGUMENTS without $ARGn"
    (is (= [{:name "arg1" :required true}]
           (shared/extract-args-from-content "Process $ARGUMENTS here"))))

  (testing "detects Claude Code style $1 and $2"
    (is (= [{:name "arg1" :required true}
            {:name "arg2" :required true}]
           (shared/extract-args-from-content "First:$1 Second:$2"))))

  (testing "uses max of all placeholder styles"
    (is (= [{:name "arg1" :required true}]
           (shared/extract-args-from-content "$ARG1 $1"))))

  (testing "returns empty vector when no placeholders present"
    (is (= []
           (shared/extract-args-from-content "No placeholders here"))))

  (testing "returns empty vector for nil/blank content"
    (is (= [] (shared/extract-args-from-content nil)))
    (is (= [] (shared/extract-args-from-content ""))))

  (testing "$ARGn takes precedence over $ARGS for argument count"
    (is (= [{:name "arg1" :required true}]
           (shared/extract-args-from-content "Single:$ARG1 All:$ARGS"))))

  (testing "detects $ARG10 and declares all args up to 10"
    (let [result (shared/extract-args-from-content "Use $ARG10")]
      (is (= 10 (count result)))
      (is (= {:name "arg1" :required true} (first result)))
      (is (= {:name "arg10" :required true} (last result))))))

(deftest hooks-msg-test
  (testing "no hooks configured shows the empty message"
    (is (= "No hooks configured. Add hooks to your config under the `hooks` key."
           (#'f.commands/hooks-msg {:hooks {}}))))

  (testing "a hook with no actions is treated as no hooks"
    (is (= "No hooks configured. Add hooks to your config under the `hooks` key."
           (#'f.commands/hooks-msg {:hooks {"empty" {:type "preToolCall" :actions []}}}))))

  (testing "description is rendered after the hook name"
    (let [out (#'f.commands/hooks-msg
               {:hooks {"guard" {:type "preToolCall"
                                 :description "blocks dangerous tools"
                                 :actions [{:type "shell" :shell "exit 0"}]}}})]
      (is (string/includes? out "`guard`"))
      (is (string/includes? out "— blocks dangerous tools"))))

  (testing "invisible hooks render the hidden marker"
    (let [out (#'f.commands/hooks-msg
               {:hooks {"silent" {:type "preToolCall"
                                  :visible false
                                  :actions [{:type "shell" :shell "exit 0"}]}}})]
      (is (string/includes? out "*(hidden)*"))))

  (testing "visible hooks (default) do not render the hidden marker"
    (let [out (#'f.commands/hooks-msg
               {:hooks {"loud" {:type "preToolCall"
                                :actions [{:type "shell" :shell "exit 0"}]}}})]
      (is (not (string/includes? out "*(hidden)*")))))

  (testing "a string matcher is rendered verbatim"
    (let [out (#'f.commands/hooks-msg
               {:hooks {"m" {:type "preToolCall"
                             :matcher "read_file"
                             :actions [{:type "shell" :shell "exit 0"}]}}})]
      (is (string/includes? out "matcher: `read_file`"))))

  (testing "a map matcher is rendered as JSON"
    (let [out (#'f.commands/hooks-msg
               {:hooks {"m" {:type "preToolCall"
                             :matcher {:tool_name "read_file"}
                             :actions [{:type "shell" :shell "exit 0"}]}}})]
      (is (string/includes? out "matcher: `{\"tool_name\":\"read_file\"}`"))))

  (testing "plugin-prefixed hook names are rendered"
    (let [out (#'f.commands/hooks-msg
               {:hooks {"my-plugin::guard" {:type "preToolCall"
                                            :actions [{:type "shell" :shell "exit 0"}]}}})]
      (is (string/includes? out "`my-plugin::guard`")))))

(deftest context-usage-text-test
  (let [text (#'f.commands/context-usage-text
              "anthropic/claude-sonnet-4-6"
              {:categories [{:name "System prompt" :tokens 5300 :emoji "🟦"}
                            {:name "Conversation" :tokens 1600 :emoji "🟩"}]
               :used-tokens 6900
               :free-tokens 193100
               :free-emoji "⬜"
               :context-limit 200000}
              nil)]
    (testing "renders header with model and used/limit"
      (is (string/includes? text "Context Usage"))
      (is (string/includes? text "anthropic/claude-sonnet-4-6"))
      (is (string/includes? text "6.9k/200.0k tokens")))
    (testing "groups the legend into Instructions and Chat sections"
      (is (string/includes? text "Estimated usage"))
      (is (string/includes? text "Instructions:"))
      (is (string/includes? text "Chat:"))
      (is (string/includes? text "System prompt"))
      (is (string/includes? text "5.3k tokens"))
      (is (string/includes? text "Free space"))
      (is (string/includes? text "193.1k tokens")))
    (testing "prefixes rows with the category emoji"
      (is (string/includes? text "🟦"))
      (is (string/includes? text "🟩"))
      (is (string/includes? text "⬜")))
    (testing "draws a 10x10 proportional emoji grid beside the legend"
      ;; 100 grid cells, mostly free, far more than the single legend swatch
      (is (> (count (re-seq #"⬜" text)) 1)))))

(deftest context-usage-text-compaction-marker-test
  (let [text (#'f.commands/context-usage-text
              "anthropic/claude-sonnet-4-6"
              {:categories [{:name "System prompt" :tokens 5300 :emoji "🟦"}
                            {:name "Conversation" :tokens 1600 :emoji "🟩"}]
               :used-tokens 6900
               :free-tokens 193100
               :free-emoji "⬜"
               :context-limit 200000}
              75)]
    (testing "states the auto-compaction threshold with the marker swatch"
      (is (string/includes? text "Auto-compaction at 75%"))
      (is (string/includes? text "150.0k tokens")))
    (testing "marks the threshold cell in the grid plus the legend swatch"
      ;; one 🔲 in the grid (the threshold cell) + one in the legend line
      (is (= 2 (count (re-seq #"🔲" text)))))))
