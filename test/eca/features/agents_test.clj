(ns eca.features.agents-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.features.agents :as agents]
   [eca.shared :as shared]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(deftest parse-md-test
  (testing "parses simple frontmatter with body"
    (is (match? {:description "A test agent"
                 :mode "subagent"
                 :body "Hello world"}
                (shared/parse-md "---\ndescription: A test agent\nmode: subagent\n---\n\nHello world"))))

  (testing "parses nested YAML (tools config)"
    (let [md (str "---\n"
                  "description: Reviewer\n"
                  "tools:\n"
                  "  byDefault: ask\n"
                  "  allow:\n"
                  "    - eca__read_file\n"
                  "    - eca__grep\n"
                  "  deny:\n"
                  "    - eca__shell_command\n"
                  "---\n"
                  "\n"
                  "Review the code")]
      (is (match? {:description "Reviewer"
                   :tools {"byDefault" "ask"
                           "allow" ["eca__read_file" "eca__grep"]
                           "deny" ["eca__shell_command"]}
                   :body "Review the code"}
                  (shared/parse-md md)))))

  (testing "parses numeric values"
    (is (match? {:steps 5
                 :body "Do things"}
                (shared/parse-md "---\nsteps: 5\n---\n\nDo things"))))

  (testing "no frontmatter returns body only"
    (is (match? {:body "Just a prompt with no config"}
                (shared/parse-md "Just a prompt with no config"))))

  (testing "empty frontmatter returns body"
    (is (match? {:body "Content after empty frontmatter"}
                (shared/parse-md "---\n---\n\nContent after empty frontmatter"))))

  (testing "trims body whitespace"
    (is (match? {:description "test"
                 :body "Trimmed body"}
                (shared/parse-md "---\ndescription: test\n---\n\n\n  Trimmed body  \n\n")))))

(deftest md->agent-config-test
  (testing "full markdown agent converts to config format"
    (let [md (str "---\n"
                  "description: You sleep one second when asked\n"
                  "mode: subagent\n"
                  "model: nubank-anthropic/sonnet-4.5\n"
                  "steps: 5\n"
                  "tools:\n"
                  "  byDefault: ask\n"
                  "  deny:\n"
                  "    - foo\n"
                  "  allow:\n"
                  "    - eca__shell_command\n"
                  "---\n"
                  "\n"
                  "You should run sleep 1 and return \"I sleeped 1 second\"")
          parsed (shared/parse-md md)
          config (#'agents/md->agent-config parsed)]
      (is (match? {:description "You sleep one second when asked"
                   :mode "subagent"
                   :defaultModel "nubank-anthropic/sonnet-4.5"
                   :maxSteps 5
                   :systemPrompt "You should run sleep 1 and return \"I sleeped 1 second\""
                   :toolCall {:approval {:byDefault "ask"
                                         :allow {"eca__shell_command" {}}
                                         :deny {"foo" {}}}}}
                  config))))

  (testing "minimal agent with only description and body"
    (let [parsed (shared/parse-md "---\ndescription: Simple agent\nmode: subagent\n---\n\nDo stuff")
          config (#'agents/md->agent-config parsed)]
      (is (match? {:description "Simple agent"
                   :mode "subagent"
                   :systemPrompt "Do stuff"}
                  config))
      (is (nil? (:toolCall config)))
      (is (nil? (:defaultModel config)))
      (is (nil? (:maxSteps config)))))

  (testing "agent with no tools config omits toolCall"
    (let [parsed (shared/parse-md "---\ndescription: No tools\n---\n\nPrompt")
          config (#'agents/md->agent-config parsed)]
      (is (nil? (:toolCall config))))))

(deftest md-agents-from-directory-test
  (let [tmp-dir (fs/create-temp-dir)
        agents-dir (fs/file tmp-dir "agents")]
    (try
      (fs/create-dirs agents-dir)
      ;; Create test agent files
      (spit (fs/file agents-dir "reviewer.md")
            (str "---\n"
                 "description: Reviews code changes\n"
                 "mode: subagent\n"
                 "model: anthropic/sonnet-4.5\n"
                 "steps: 10\n"
                 "---\n\n"
                 "You are a code reviewer."))
      (spit (fs/file agents-dir "sleeper.md")
            (str "---\n"
                 "description: Sleeps for testing\n"
                 "mode: subagent\n"
                 "---\n\n"
                 "Sleep 1 second."))
      ;; Non-md files should be ignored (glob *.md)
      (spit (fs/file agents-dir "notes.txt") "not an agent")

      (let [reviewer (#'agents/agent-md-file->agent (fs/file agents-dir "reviewer.md"))
            sleeper (#'agents/agent-md-file->agent (fs/file agents-dir "sleeper.md"))]
        (is (match? ["reviewer" {:description "Reviews code changes"
                                 :mode "subagent"
                                 :defaultModel "anthropic/sonnet-4.5"
                                 :maxSteps 10
                                 :systemPrompt "You are a code reviewer."}]
                    reviewer))
        (is (match? ["sleeper" {:description "Sleeps for testing"
                                :mode "subagent"
                                :systemPrompt "Sleep 1 second."}]
                    sleeper)))
      (finally
        (fs/delete-tree tmp-dir)))))

(deftest md-agents-merge-with-config-test
  (let [tmp-dir (fs/create-temp-dir)
        local-agents-dir (fs/file tmp-dir ".eca" "agents")]
    (try
      (fs/create-dirs local-agents-dir)
      (spit (fs/file local-agents-dir "tester.md")
            (str "---\n"
                 "description: Runs tests\n"
                 "mode: subagent\n"
                 "steps: 3\n"
                 "---\n\n"
                 "Run the test suite."))

      (let [roots [{:uri (shared/filename->uri (str tmp-dir))}]
            md-agents (agents/all-md-agents roots)]
        (is (match? {"tester" {:description "Runs tests"
                               :mode "subagent"
                               :maxSteps 3
                               :systemPrompt "Run the test suite."}}
                    md-agents)))
      (finally
        (fs/delete-tree tmp-dir)))))

(deftest config-integration-test
  (let [tmp-dir (fs/create-temp-dir)
        local-agents-dir (fs/file tmp-dir ".eca" "agents")]
    (try
      (fs/create-dirs local-agents-dir)
      (spit (fs/file local-agents-dir "md-agent.md")
            (str "---\n"
                 "description: From markdown\n"
                 "mode: subagent\n"
                 "---\n\n"
                 "I am from markdown."))
      ;; Agent defined in JSON config should take precedence over MD agent
      (spit (fs/file local-agents-dir "json-override.md")
            (str "---\n"
                 "description: MD version\n"
                 "mode: subagent\n"
                 "---\n\n"
                 "MD prompt."))

      (reset! config/initialization-config*
              {:pureConfig false
               :agent {"json-override" {:mode "subagent"
                                        :description "JSON version"
                                        :systemPrompt "JSON prompt."}}})
      (let [db {:workspace-folders [{:uri (shared/filename->uri (str tmp-dir))}]}
            result (#'config/all* db)]
        (testing "markdown agent is present in config"
          (is (match? {:description "From markdown"
                       :mode "subagent"
                       :systemPrompt "I am from markdown."}
                      (get-in result [:agent "md-agent"]))))
        (testing "JSON config agent takes precedence over same-named MD agent"
          (is (= "JSON version" (get-in result [:agent "json-override" :description])))
          (is (= "JSON prompt." (get-in result [:agent "json-override" :systemPrompt])))))
      (finally
        (fs/delete-tree tmp-dir)))))

(deftest agent-name-derived-from-filename-test
  (let [tmp-dir (fs/create-temp-dir)
        agents-dir (fs/file tmp-dir "agents")]
    (try
      (fs/create-dirs agents-dir)
      (spit (fs/file agents-dir "My-Reviewer.md")
            (str "---\n"
                 "description: Case test\n"
                 "mode: subagent\n"
                 "---\n\n"
                 "Prompt."))
      (let [[agent-name _] (#'agents/agent-md-file->agent (fs/file agents-dir "My-Reviewer.md"))]
        (is (= "my-reviewer" agent-name)))
      (finally
        (fs/delete-tree tmp-dir)))))
