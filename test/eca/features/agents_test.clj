(ns eca.features.agents-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.features.agents :as agents]
   [eca.interpolation :as interpolation]
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
                  "model: my-org-anthropic/sonnet-4.5\n"
                  "maxSteps: 5\n"
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
                   :defaultModel "my-org-anthropic/sonnet-4.5"
                   :maxSteps 5
                   :systemPrompt "You should run sleep 1 and return \"I sleeped 1 second\""
                   :toolCall {:approval {:byDefault "ask"
                                         :allow {"eca__shell_command" {}}
                                         :deny {"foo" {}}}}}
                  config))))

  (testing "tool entries with regex patterns"
    (let [parsed {:tools {"byDefault" "ask"
                          "allow" ["eca__shell_command(npm run .*)"
                                   "eca__shell_command(git commit .*)"
                                   "eca__read_file"]}}
          config (#'agents/md->agent-config parsed)]
      (is (match? {:toolCall {:approval {:byDefault "ask"
                                         :allow {"eca__shell_command" {:argsMatchers {"command" ["npm run .*" "git commit .*"]}}
                                                 "eca__read_file" {}}}}}
                  config))))

  (testing "tool entry with pattern for unknown tool arg (no tool-arg-name entry)"
    (let [parsed {:tools {"allow" ["eca__read_file(/tmp/.*)"]}}
          config (#'agents/md->agent-config parsed)]
      (is (match? {:toolCall {:approval {:allow {"eca__read_file" {}}}}}
                  config))))

  (testing "single tool with pattern"
    (let [parsed {:tools {"allow" ["eca__shell_command(git diff(\\s+.*)?)"]}}
          config (#'agents/md->agent-config parsed)]
      (is (match? {:toolCall {:approval {:allow {"eca__shell_command" {:argsMatchers {"command" ["git diff(\\s+.*)?"]}}}}}}
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
      (is (nil? (:toolCall config)))))

  (testing "agent with no mode produces config without :mode (consumers default to both modes)"
    (let [parsed (shared/parse-md "---\ndescription: An unspecified-mode agent\n---\n\nDo work.")
          config (#'agents/md->agent-config parsed)]
      (is (= "An unspecified-mode agent" (:description config)))
      (is (= "Do work." (:systemPrompt config)))
      (is (nil? (:mode config)))))

  (testing "YAML list mode is preserved as a vector of strings"
    (let [md (str "---\n"
                  "description: Dual-role\n"
                  "mode:\n"
                  "  - primary\n"
                  "  - subagent\n"
                  "---\n\n"
                  "Body.")
          parsed (shared/parse-md md)
          config (#'agents/md->agent-config parsed)]
      (is (= ["primary" "subagent"] (:mode config)))))

  (testing "tools as a YAML list normalizes to byDefault=ask + allow map (Claude form)"
    (let [md (str "---\n"
                  "description: Reviewer\n"
                  "tools:\n"
                  "  - read\n"
                  "  - search\n"
                  "  - agent\n"
                  "---\n\n"
                  "Body.")
          parsed (shared/parse-md md)
          config (#'agents/md->agent-config parsed)]
      (is (match? {:description "Reviewer"
                   :systemPrompt "Body."
                   :toolCall {:approval {:byDefault "ask"
                                         :allow {"read" {}
                                                 "search" {}
                                                 "agent" {}}}}}
                  config))))

  (testing "tools as a malformed string is ignored without crashing the agent"
    (let [config (#'agents/md->agent-config {:description "no tools" :tools "read"})]
      (is (= "no tools" (:description config)))
      (is (nil? (:toolCall config)))))

  (testing "tools as a number is ignored without crashing the agent"
    (let [config (#'agents/md->agent-config {:description "no tools" :tools 42})]
      (is (= "no tools" (:description config)))
      (is (nil? (:toolCall config))))))

(deftest normalize-tools-test
  (testing "map form passes through unchanged"
    (is (= {"byDefault" "ask" "allow" ["read"]}
           (#'agents/normalize-tools {"byDefault" "ask" "allow" ["read"]}))))
  (testing "vector form is wrapped as byDefault=ask + allow"
    (is (= {"byDefault" "ask" "allow" ["read" "search"]}
           (#'agents/normalize-tools ["read" "search"]))))
  (testing "list form (clojure list) is also accepted"
    (is (= {"byDefault" "ask" "allow" ["read" "search"]}
           (#'agents/normalize-tools '("read" "search")))))
  (testing "nil returns nil"
    (is (nil? (#'agents/normalize-tools nil))))
  (testing "string returns nil (treated as malformed)"
    (is (nil? (#'agents/normalize-tools "read"))))
  (testing "number returns nil (treated as malformed)"
    (is (nil? (#'agents/normalize-tools 42)))))

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

(deftest dynamic-string-in-agent-md-test
  (let [tmp-dir (fs/create-temp-dir)
        agents-dir (fs/file tmp-dir "agents")
        fragments-dir (fs/file agents-dir "fragments")]
    (try
      (fs/create-dirs fragments-dir)
      (spit (fs/file fragments-dir "style.md") "Always be concise.")

      (testing "resolves ${file:...} in agent body"
        (spit (fs/file agents-dir "stylist.md")
              (str "---\n"
                   "description: Style agent\n"
                   "mode: subagent\n"
                   "---\n\n"
                   "Follow this style: ${file:./fragments/style.md}"))
        (let [[agent-name config] (#'agents/agent-md-file->agent (fs/file agents-dir "stylist.md"))]
          (is (= "stylist" agent-name))
          (is (= "Follow this style: Always be concise." (:systemPrompt config)))))

      (testing "resolves ${env:...} in agent frontmatter"
        (with-redefs [interpolation/get-env (fn [k] (when (= k "TEST_MODEL") "anthropic/sonnet-4"))]
          (spit (fs/file agents-dir "env-agent.md")
                (str "---\n"
                     "description: Env agent\n"
                     "model: ${env:TEST_MODEL:anthropic/haiku-3}\n"
                     "---\n\n"
                     "Prompt."))
          (let [[_ config] (#'agents/agent-md-file->agent (fs/file agents-dir "env-agent.md"))]
            (is (= "anthropic/sonnet-4" (:defaultModel config))))))

      (testing "resolves ${env:...} default value when env var is not set"
        (with-redefs [interpolation/get-env (constantly nil)]
          (spit (fs/file agents-dir "default-agent.md")
                (str "---\n"
                     "description: Default agent\n"
                     "model: ${env:UNSET_VAR:anthropic/haiku-3}\n"
                     "---\n\n"
                     "Prompt."))
          (let [[_ config] (#'agents/agent-md-file->agent (fs/file agents-dir "default-agent.md"))]
            (is (= "anthropic/haiku-3" (:defaultModel config))))))
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

(deftest agent-name-from-frontmatter-test
  (testing "honors YAML name and lowercases it"
    (is (= "some-role" (#'agents/agent-name-from-frontmatter {:name "Some-Role"}))))
  (testing "trims surrounding whitespace"
    (is (= "foo" (#'agents/agent-name-from-frontmatter {:name "  Foo  "}))))
  (testing "stringifies non-string values"
    (is (= "123" (#'agents/agent-name-from-frontmatter {:name 123}))))
  (testing "returns nil when name is absent"
    (is (nil? (#'agents/agent-name-from-frontmatter {:description "no name"}))))
  (testing "returns nil when name is blank"
    (is (nil? (#'agents/agent-name-from-frontmatter {:name "   "})))
    (is (nil? (#'agents/agent-name-from-frontmatter {:name ""})))))

(deftest agent-name-from-filename-test
  (testing "single extension"
    (is (= "architect" (#'agents/agent-name-from-filename (fs/file "architect.md")))))
  (testing "multi-extension Claude-style file"
    (is (= "architect" (#'agents/agent-name-from-filename (fs/file "architect.agent.md")))))
  (testing "lowercases the result"
    (is (= "my-agent" (#'agents/agent-name-from-filename (fs/file "My-Agent.md")))))
  (testing "handles filenames with multiple dots"
    (is (= "foo" (#'agents/agent-name-from-filename (fs/file "foo.bar.baz.md"))))))

(deftest claude-tools-list-loads-agent-test
  (let [tmp-dir (fs/create-temp-dir)
        agents-dir (fs/file tmp-dir "agents")]
    (try
      (fs/create-dirs agents-dir)
      ;; Lucas's exact reproducer: tools as a YAML list (Claude convention).
      (spit (fs/file agents-dir "glp-engineer.agent.md")
            (str "---\n"
                 "name: GLP-Reviewer\n"
                 "description: \"Review any design document with DRC-style structured feedback and rubric scoring\"\n"
                 "tools:\n"
                 "  - read\n"
                 "  - search\n"
                 "  - agent\n"
                 "---\n\n"
                 "You are a reviewer."))
      (let [result (#'agents/agent-md-file->agent (fs/file agents-dir "glp-engineer.agent.md"))]
        (testing "agent is loaded (not silently dropped by tools-list parse error)"
          (is (some? result)))
        (testing "agent id comes from YAML name"
          (is (= "glp-reviewer" (first result))))
        (testing "description is preserved"
          (is (= "Review any design document with DRC-style structured feedback and rubric scoring"
                 (get-in (second result) [:description])))))
      (finally
        (fs/delete-tree tmp-dir)))))

(deftest agent-id-precedence-test
  (let [tmp-dir (fs/create-temp-dir)
        agents-dir (fs/file tmp-dir "agents")]
    (try
      (fs/create-dirs agents-dir)
      (testing "YAML name wins over filename"
        (spit (fs/file agents-dir "Whatever.md")
              (str "---\n"
                   "name: My-Custom-Name\n"
                   "description: Name override\n"
                   "---\n\n"
                   "Body."))
        (let [[agent-name _] (#'agents/agent-md-file->agent (fs/file agents-dir "Whatever.md"))]
          (is (= "my-custom-name" agent-name))))

      (testing "Claude-style .agent.md falls back to part before first dot when no YAML name"
        (spit (fs/file agents-dir "architect.agent.md")
              (str "---\n"
                   "description: Architect persona\n"
                   "---\n\n"
                   "Body."))
        (let [[agent-name _] (#'agents/agent-md-file->agent (fs/file agents-dir "architect.agent.md"))]
          (is (= "architect" agent-name))))

      (testing "YAML name wins over multi-extension filename"
        (spit (fs/file agents-dir "engineer.agent.md")
              (str "---\n"
                   "name: senior-engineer\n"
                   "description: Engineer persona\n"
                   "---\n\n"
                   "Body."))
        (let [[agent-name _] (#'agents/agent-md-file->agent (fs/file agents-dir "engineer.agent.md"))]
          (is (= "senior-engineer" agent-name))))

      (testing "blank YAML name falls back to filename"
        (spit (fs/file agents-dir "fallback.md")
              (str "---\n"
                   "name: \"   \"\n"
                   "description: Blank name\n"
                   "---\n\n"
                   "Body."))
        (let [[agent-name _] (#'agents/agent-md-file->agent (fs/file agents-dir "fallback.md"))]
          (is (= "fallback" agent-name))))
      (finally
        (fs/delete-tree tmp-dir)))))
