(ns eca.features.rules-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.features.rules :as f.rules]
   [eca.shared :as shared]
   [eca.test-helper :as h]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(defn ^:private names
  [rules]
  (map :name rules))

(deftest rule-loading-test
  (testing "absolute config rule outside the workspace is global static"
    (with-redefs [clojure.core/slurp (constantly "MY_RULE_CONTENT")
                  fs/absolute? (constantly true)
                  fs/exists? #(= (h/file-path "/path/to/my-rule.md") %)
                  fs/canonicalize identity
                  fs/file-name (constantly "cool-name")]
      (let [config {:rules [{:path (h/file-path "/path/to/my-rule.md")}]}]
        (is (match?
             (m/embeds [{:type :user-config
                         :path (h/file-path "/path/to/my-rule.md")
                         :name "cool-name"
                         :scope :global
                         :content "MY_RULE_CONTENT"}])
             (f.rules/static-rules config []))))))

  (testing "absolute config rule inside the workspace is project-scoped static"
    (with-redefs [clojure.core/slurp (constantly "MY_RULE_CONTENT")
                  fs/absolute? (constantly true)
                  fs/exists? #(= (h/file-path "/my/project/.foo/cool-rule.md") %)
                  fs/canonicalize identity
                  fs/file-name (constantly "cool-name")]
      (let [config {:rules [{:path (h/file-path "/my/project/.foo/cool-rule.md")}]}]
        (is (match?
             (m/embeds [{:type :user-config
                         :path (h/file-path "/my/project/.foo/cool-rule.md")
                         :name "cool-name"
                         :scope :project
                         :workspace-root (h/file-path "/my/project")
                         :content "MY_RULE_CONTENT"}])
             (f.rules/static-rules config [{:uri (h/file-uri "file:///my/project")}]))))))

  (testing "relative config rule is project-scoped static"
    (with-redefs [fs/absolute? (constantly false)
                  fs/exists? #(contains? #{(h/file-path "/my/project/.eca/rules")
                                           (h/file-path "/my/project/.foo/cool-rule.md")} (str %))
                  fs/glob (constantly [])
                  fs/canonicalize identity
                  fs/file-name (constantly "cool-name")
                  clojure.core/slurp (constantly "MY_RULE_CONTENT")]
      (let [config {:rules [{:path ".foo/cool-rule.md"}]}
            roots [{:uri (h/file-uri "file:///my/project")}]]
        (is (match?
             (m/embeds [{:type :user-config
                         :path (h/file-path "/my/project/.foo/cool-rule.md")
                         :name "cool-name"
                         :scope :project
                         :workspace-root (h/file-path "/my/project")
                         :content "MY_RULE_CONTENT"}])
             (f.rules/static-rules config roots))))))

  (testing "local file rules load as project-scoped static rules"
    (with-redefs [fs/exists? #(contains? #{(h/file-path "/my/project/.eca/rules")
                                           (h/file-path "/my/project")
                                           (h/file-path "/my/project/.eca/rules/cool.md")} (str %))
                  fs/glob (constantly [(fs/path (h/file-path "/my/project/.eca/rules/cool.md"))])
                  fs/canonicalize identity
                  fs/file-name (constantly "cool-name")
                  clojure.core/slurp (constantly "MY_RULE_CONTENT")]
      (let [roots [{:uri (h/file-uri "file:///my/project")}]]
        (is (match?
             (m/embeds [{:type :user-local-file
                         :path (h/file-path "/my/project/.eca/rules/cool.md")
                         :name "cool-name"
                         :scope :project
                         :workspace-root (h/file-path "/my/project")
                         :content "MY_RULE_CONTENT"}])
             (f.rules/static-rules {} roots))))))

  (testing "global file rules load as global static rules"
    (with-redefs [config/get-env (constantly (h/file-path "/home/someuser/.config"))
                  fs/exists? #(contains? #{(h/file-path "/home/someuser/.config/eca/rules")
                                           (h/file-path "/home/someuser/.config/eca/rules/cool.md")
                                           (h/file-path "/home/someuser/.config")
                                           (h/file-path "/home/someuser/.config/eca")} (str %))
                  fs/glob (constantly [(fs/path (h/file-path "/home/someuser/.config/eca/rules/cool.md"))])
                  fs/canonicalize identity
                  fs/file-name (constantly "cool-name")
                  clojure.core/slurp (constantly "MY_RULE_CONTENT")]
      (let [roots [{:uri (h/file-uri "file:///my/project")}]]
        (is (match?
             (m/embeds [{:type :user-global-file
                         :path (h/file-path "/home/someuser/.config/eca/rules/cool.md")
                         :name "cool-name"
                         :scope :global
                         :content "MY_RULE_CONTENT"}])
             (f.rules/static-rules {} roots)))))))

(deftest agent-filtering-test
  (testing "keeps backward compatibility for rules without frontmatter"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(= (h/file-path "/path/to/my-rule.md") %)
                  fs/file-name (constantly "cool-name")
                  clojure.core/slurp (constantly "RAW_RULE_CONTENT")]
      (let [config {:rules [{:path (h/file-path "/path/to/my-rule.md")}]}]
        (is (match?
             [{:type :user-config
               :path (h/file-path "/path/to/my-rule.md")
               :name "cool-name"
               :content "RAW_RULE_CONTENT"}]
             (f.rules/static-rules config [] "code"))))))

  (testing "filters rule by single agent in frontmatter"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(= (h/file-path "/path/to/my-rule.md") %)
                  fs/file-name (constantly "cool-name")
                  clojure.core/slurp (constantly "---\nagent: code\n---\n\nOnly code")]
      (let [config {:rules [{:path (h/file-path "/path/to/my-rule.md")}]}]
        (is (match?
             [{:type :user-config
               :path (h/file-path "/path/to/my-rule.md")
               :name "cool-name"
               :content "Only code"
               :agents ["code"]}]
             (f.rules/static-rules config [] "code")))
        (is (empty? (f.rules/static-rules config [] "plan"))))))

  (testing "filters rule by multiple agents in frontmatter"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(= (h/file-path "/path/to/my-rule.md") %)
                  fs/file-name (constantly "cool-name")
                  clojure.core/slurp (constantly "---\nagent:\n  - code\n  - plan\n---\n\nShared rule")]
      (let [config {:rules [{:path (h/file-path "/path/to/my-rule.md")}]}]
        (is (= 1 (count (f.rules/static-rules config [] "code"))))
        (is (= 1 (count (f.rules/static-rules config [] "plan"))))
        (is (empty? (f.rules/static-rules config [] "reviewer"))))))

  (testing "empty agent frontmatter applies to all agents"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(= (h/file-path "/path/to/my-rule.md") %)
                  fs/file-name (constantly "cool-name")
                  clojure.core/slurp (constantly "---\nagent: \"\"\n---\n\nGlobal rule")]
      (let [config {:rules [{:path (h/file-path "/path/to/my-rule.md")}]}]
        (is (= 1 (count (f.rules/static-rules config [] "code"))))
        (is (= 1 (count (f.rules/static-rules config [] "plan")))))))

  (testing "frontmatter without agent key is global"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(= (h/file-path "/path/to/my-rule.md") %)
                  fs/file-name (constantly "cool-name")
                  clojure.core/slurp (constantly "---\ntitle: some-rule\n---\n\nRule body")]
      (let [config {:rules [{:path (h/file-path "/path/to/my-rule.md")}]}]
        (is (match?
             [{:type :user-config
               :name "cool-name"
               :content "Rule body"}]
             (f.rules/static-rules config [] "code")))
        (is (match?
             [{:content "Rule body"}]
             (f.rules/static-rules config [] "plan"))))))

  (testing "malformed frontmatter skips invalid rule"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(= (h/file-path "/path/to/my-rule.md") %)
                  fs/file-name (constantly "cool-name")
                  clojure.core/slurp (constantly "---\n: invalid yaml [[\n---\n\nBody")]
      (let [config {:rules [{:path (h/file-path "/path/to/my-rule.md")}]}]
        (is (empty? (f.rules/static-rules config [] "code"))))))

  (testing "unclosed frontmatter skips invalid rule"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(= (h/file-path "/path/to/my-rule.md") %)
                  fs/file-name (constantly "cool-name")
                  clojure.core/slurp (constantly "---\nagent: code\npaths: src/**/*.clj")]
      (let [config {:rules [{:path (h/file-path "/path/to/my-rule.md")}]}]
        (is (empty? (f.rules/static-rules config [] "code")))))))

(deftest paths-frontmatter-test
  (testing "single paths string is parsed into :paths vector"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(= (h/file-path "/path/to/my-rule.md") %)
                  fs/file-name (constantly "cool-name")
                  clojure.core/slurp (constantly "---\npaths: \"src/**/*.clj\"\n---\n\nRule body")]
      (let [config {:rules [{:path (h/file-path "/path/to/my-rule.md")}]}]
        (is (match?
             [{:name "cool-name"
               :content "Rule body"
               :paths ["src/**/*.clj"]}]
             (f.rules/path-scoped-rules config []))))))

  (testing "paths list is parsed into :paths vector"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(= (h/file-path "/path/to/my-rule.md") %)
                  fs/file-name (constantly "cool-name")
                  clojure.core/slurp (constantly "---\npaths:\n  - \"src/**/*.{ts,tsx}\"\n  - \"lib/**/*.ts\"\n---\n\nRule body")]
      (let [config {:rules [{:path (h/file-path "/path/to/my-rule.md")}]}]
        (is (match?
             [{:name "cool-name"
               :content "Rule body"
               :paths ["src/**/*.{ts,tsx}" "lib/**/*.ts"]}]
             (f.rules/path-scoped-rules config []))))))

  (testing "blank paths behaves like no path filter"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(= (h/file-path "/path/to/my-rule.md") %)
                  fs/file-name (constantly "cool-name")
                  clojure.core/slurp (constantly "---\npaths: \"\"\n---\n\nRule body")]
      (let [config {:rules [{:path (h/file-path "/path/to/my-rule.md")}]}]
        (is (match?
             [{:name "cool-name"
               :content "Rule body"}]
             (f.rules/static-rules config [])))
        (is (nil? (:paths (first (f.rules/static-rules config []))))))))

  (testing "combined agent + paths frontmatter retains both"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(= (h/file-path "/path/to/my-rule.md") %)
                  fs/file-name (constantly "cool-name")
                  clojure.core/slurp (constantly "---\nagent: code\npaths: \"src/**/*.clj\"\n---\n\nRule body")]
      (let [config {:rules [{:path (h/file-path "/path/to/my-rule.md")}]}]
        (is (match?
             [{:name "cool-name"
               :content "Rule body"
               :agents ["code"]
               :paths ["src/**/*.clj"]}]
             (f.rules/path-scoped-rules config [] "code")))))))

(deftest model-frontmatter-test
  (testing "single model string is parsed into :models vector"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(= (h/file-path "/path/to/my-rule.md") %)
                  fs/file-name (constantly "cool-name")
                  clojure.core/slurp (constantly "---\nmodel: \"claude-sonnet-4.*\"\n---\n\nRule body")]
      (let [config {:rules [{:path (h/file-path "/path/to/my-rule.md")}]}]
        (is (match?
             [{:name "cool-name"
               :content "Rule body"
               :models ["claude-sonnet-4.*"]}]
             (f.rules/static-rules config [] nil "anthropic/claude-sonnet-4-20250514"))))))

  (testing "model list is parsed into :models vector"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(= (h/file-path "/path/to/my-rule.md") %)
                  fs/file-name (constantly "cool-name")
                  clojure.core/slurp (constantly "---\nmodel:\n  - \"claude-sonnet-4.*\"\n  - \"gpt-4.*\"\n---\n\nRule body")]
      (let [config {:rules [{:path (h/file-path "/path/to/my-rule.md")}]}]
        (is (match?
             [{:name "cool-name"
               :content "Rule body"
               :models ["claude-sonnet-4.*" "gpt-4.*"]}]
             (f.rules/static-rules config [] nil "anthropic/claude-sonnet-4-20250514"))))))

  (testing "blank model behaves like no filter"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(= (h/file-path "/path/to/my-rule.md") %)
                  fs/file-name (constantly "cool-name")
                  clojure.core/slurp (constantly "---\nmodel: \"\"\n---\n\nRule body")]
      (let [config {:rules [{:path (h/file-path "/path/to/my-rule.md")}]}]
        (is (match?
             [{:name "cool-name"
               :content "Rule body"}]
             (f.rules/static-rules config [])))
        (is (nil? (:models (first (f.rules/static-rules config []))))))))

  (testing "combined agent + model frontmatter retains both"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(= (h/file-path "/path/to/my-rule.md") %)
                  fs/file-name (constantly "cool-name")
                  clojure.core/slurp (constantly "---\nagent: code\nmodel: \"claude-sonnet-4.*\"\n---\n\nRule body")]
      (let [config {:rules [{:path (h/file-path "/path/to/my-rule.md")}]}]
        (is (match?
             [{:name "cool-name"
               :content "Rule body"
               :agents ["code"]
               :models ["claude-sonnet-4.*"]}]
             (f.rules/static-rules config [] "code" "anthropic/claude-sonnet-4-20250514")))))))

(deftest model-filtering-test
  (testing "rule without :models always matches any full-model"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(= (h/file-path "/path/to/my-rule.md") %)
                  fs/file-name (constantly "no-model-rule")
                  clojure.core/slurp (constantly "No model filter")]
      (let [config {:rules [{:path (h/file-path "/path/to/my-rule.md")}]}]
        (is (= 1 (count (f.rules/static-rules config [] nil "anthropic/claude-sonnet-4-20250514"))))
        (is (= 1 (count (f.rules/static-rules config [] nil "openai/gpt-4o"))))
        (is (= 1 (count (f.rules/static-rules config [] nil nil)))))))

  (testing "rule with :models only appears when full-model matches regex"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(= (h/file-path "/path/to/my-rule.md") %)
                  fs/file-name (constantly "model-rule")
                  clojure.core/slurp (constantly "---\nmodel: \"claude-sonnet-4.*\"\n---\n\nClaude rule")]
      (let [config {:rules [{:path (h/file-path "/path/to/my-rule.md")}]}]
        (is (= 1 (count (f.rules/static-rules config [] nil "anthropic/claude-sonnet-4-20250514"))))
        (is (empty? (f.rules/static-rules config [] nil "openai/gpt-4o"))))))

  (testing "rule with :models is excluded when full-model is nil"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(= (h/file-path "/path/to/my-rule.md") %)
                  fs/file-name (constantly "model-rule")
                  clojure.core/slurp (constantly "---\nmodel: \"claude-sonnet-4.*\"\n---\n\nClaude rule")]
      (let [config {:rules [{:path (h/file-path "/path/to/my-rule.md")}]}]
        (is (empty? (f.rules/static-rules config [] nil nil))))))

  (testing "model regex matching works with provider-prefixed full-model"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(= (h/file-path "/path/to/my-rule.md") %)
                  fs/file-name (constantly "model-rule")
                  clojure.core/slurp (constantly "---\nmodel: \"claude-sonnet-4.*\"\n---\n\nClaude rule")]
      (let [config {:rules [{:path (h/file-path "/path/to/my-rule.md")}]}]
        (is (= 1 (count (f.rules/static-rules config [] nil "anthropic/claude-sonnet-4-20250514"))))
        (is (= 1 (count (f.rules/static-rules config [] nil "claude-sonnet-4-20250514")))))))

  (testing "static-rules respects model filtering"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(contains? #{(h/file-path "/path/to/no-model.md")
                                           (h/file-path "/path/to/model-rule.md")} %)
                  fs/file-name (fn [p]
                                 (cond (= (h/file-path "/path/to/no-model.md") p) "no-model"
                                       (= (h/file-path "/path/to/model-rule.md") p) "model-rule"
                                       :else "unknown"))
                  clojure.core/slurp (fn [p]
                                       (cond (= (h/file-path "/path/to/no-model.md") p) "Always active"
                                             (= (h/file-path "/path/to/model-rule.md") p) "---\nmodel: \"claude.*\"\n---\n\nClaude only"))]
      (let [config {:rules [{:path (h/file-path "/path/to/no-model.md")}
                            {:path (h/file-path "/path/to/model-rule.md")}]}]
        (is (= 2 (count (f.rules/static-rules config [] nil "anthropic/claude-sonnet-4-20250514"))))
        (is (= 1 (count (f.rules/static-rules config [] nil "openai/gpt-4o"))))
        (is (= ["no-model"] (names (f.rules/static-rules config [] nil "openai/gpt-4o")))))))

  (testing "path-scoped-rules respects model filtering"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(contains? #{(h/file-path "/path/to/path-rule.md")
                                           (h/file-path "/path/to/no-model-path.md")} %)
                  fs/file-name (fn [p]
                                 (cond (= (h/file-path "/path/to/path-rule.md") p) "path-rule"
                                       (= (h/file-path "/path/to/no-model-path.md") p) "no-model-path"
                                       :else "unknown"))
                  clojure.core/slurp (fn [p]
                                       (cond (= (h/file-path "/path/to/path-rule.md") p) "---\nmodel: \"gpt.*\"\npaths: \"src/**/*.ts\"\n---\n\nGPT path rule"
                                             (= (h/file-path "/path/to/no-model-path.md") p) "---\npaths: \"src/**/*.clj\"\n---\n\nClojure path rule"))]
      (let [config {:rules [{:path (h/file-path "/path/to/path-rule.md")}
                            {:path (h/file-path "/path/to/no-model-path.md")}]}]
        (is (= 2 (count (f.rules/path-scoped-rules config [] nil "openai/gpt-4o"))))
        (is (= 1 (count (f.rules/path-scoped-rules config [] nil "anthropic/claude-sonnet-4-20250514"))))
        (is (= ["no-model-path"] (names (f.rules/path-scoped-rules config [] nil "anthropic/claude-sonnet-4-20250514"))))))))

(deftest static-rules-test
  (testing "static-rules returns only rules without :paths"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(contains? #{(h/file-path "/path/to/static.md")
                                           (h/file-path "/path/to/scoped.md")} %)
                  fs/file-name (fn [p]
                                 (cond (= (h/file-path "/path/to/static.md") p) "static"
                                       (= (h/file-path "/path/to/scoped.md") p) "scoped"
                                       :else "unknown"))
                  clojure.core/slurp (fn [p]
                                       (cond (= (h/file-path "/path/to/static.md") p) "Static rule"
                                             (= (h/file-path "/path/to/scoped.md") p) "---\npaths: \"src/**/*.clj\"\n---\n\nScoped rule"))]
      (let [config {:rules [{:path (h/file-path "/path/to/static.md")}
                            {:path (h/file-path "/path/to/scoped.md")}]}]
        (is (= 1 (count (f.rules/static-rules config []))))
        (is (= ["static"] (names (f.rules/static-rules config []))))
        (is (nil? (:paths (first (f.rules/static-rules config []))))))))

  (testing "path-scoped-rules returns only rules with :paths"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(contains? #{(h/file-path "/path/to/static.md")
                                           (h/file-path "/path/to/scoped.md")} %)
                  fs/file-name (fn [p]
                                 (cond (= (h/file-path "/path/to/static.md") p) "static"
                                       (= (h/file-path "/path/to/scoped.md") p) "scoped"
                                       :else "unknown"))
                  clojure.core/slurp (fn [p]
                                       (cond (= (h/file-path "/path/to/static.md") p) "Static rule"
                                             (= (h/file-path "/path/to/scoped.md") p) "---\npaths: \"src/**/*.clj\"\n---\n\nScoped rule"))]
      (let [config {:rules [{:path (h/file-path "/path/to/static.md")}
                            {:path (h/file-path "/path/to/scoped.md")}]}]
        (is (= 1 (count (f.rules/path-scoped-rules config []))))
        (is (= ["scoped"] (names (f.rules/path-scoped-rules config []))))
        (is (some? (:paths (first (f.rules/path-scoped-rules config []))))))))

  (testing "static-rules is filtered by agent and model"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(contains? #{(h/file-path "/path/to/global-rule.md")
                                           (h/file-path "/path/to/code-rule.md")
                                           (h/file-path "/path/to/claude-rule.md")} %)
                  fs/file-name (fn [p]
                                 (cond (= (h/file-path "/path/to/global-rule.md") p) "global-rule"
                                       (= (h/file-path "/path/to/code-rule.md") p) "code-rule"
                                       (= (h/file-path "/path/to/claude-rule.md") p) "claude-rule"
                                       :else "unknown"))
                  clojure.core/slurp (fn [p]
                                       (cond (= (h/file-path "/path/to/global-rule.md") p) "Global rule"
                                             (= (h/file-path "/path/to/code-rule.md") p) "---\nagent: code\n---\n\nCode rule"
                                             (= (h/file-path "/path/to/claude-rule.md") p) "---\nmodel: \"claude.*\"\n---\n\nClaude rule"))]
      (let [config {:rules [{:path (h/file-path "/path/to/global-rule.md")}
                            {:path (h/file-path "/path/to/code-rule.md")}
                            {:path (h/file-path "/path/to/claude-rule.md")}]}]
        (is (= 3 (count (f.rules/static-rules config [] "code" "anthropic/claude-sonnet-4-20250514"))))
        (is (= 2 (count (f.rules/static-rules config [] "plan" "anthropic/claude-sonnet-4-20250514"))))
        (is (= 2 (count (f.rules/static-rules config [] "code" "openai/gpt-4o")))))))

  (testing "path-scoped-rules is filtered by agent and model"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(contains? #{(h/file-path "/path/to/global-path.md")
                                           (h/file-path "/path/to/code-path.md")
                                           (h/file-path "/path/to/claude-path.md")} %)
                  fs/file-name (fn [p]
                                 (cond (= (h/file-path "/path/to/global-path.md") p) "global-path"
                                       (= (h/file-path "/path/to/code-path.md") p) "code-path"
                                       (= (h/file-path "/path/to/claude-path.md") p) "claude-path"
                                       :else "unknown"))
                  clojure.core/slurp (fn [p]
                                       (cond (= (h/file-path "/path/to/global-path.md") p) "---\npaths: \"src/**\"\n---\n\nGlobal path rule"
                                             (= (h/file-path "/path/to/code-path.md") p) "---\nagent: code\npaths: \"src/**/*.clj\"\n---\n\nCode path rule"
                                             (= (h/file-path "/path/to/claude-path.md") p) "---\nmodel: \"claude.*\"\npaths: \"src/**/*.ts\"\n---\n\nClaude path rule"))]
      (let [config {:rules [{:path (h/file-path "/path/to/global-path.md")}
                            {:path (h/file-path "/path/to/code-path.md")}
                            {:path (h/file-path "/path/to/claude-path.md")}]}]
        (is (= 3 (count (f.rules/path-scoped-rules config [] "code" "anthropic/claude-sonnet-4-20250514"))))
        (is (= 2 (count (f.rules/path-scoped-rules config [] "plan" "anthropic/claude-sonnet-4-20250514"))))
        (is (= 2 (count (f.rules/path-scoped-rules config [] "code" "openai/gpt-4o"))))))))

(deftest matching-path-scoped-rules-test
  (testing "returns every matching path-scoped rule with match details"
    (with-redefs [f.rules/path-scoped-rules (constantly [{:id "/workspace-a/.eca/rules/format.md"
                                                          :name "format.md"
                                                          :scope :project
                                                          :workspace-root (h/file-path "/workspace-a")
                                                          :paths ["src/**.clj"]}
                                                         {:id "/workspace-a/.eca/rules/notes.md"
                                                          :name "notes.md"
                                                          :scope :project
                                                          :workspace-root (h/file-path "/workspace-a")
                                                          :paths ["docs/**.md"]}])]
      (is (match? [{:rule {:id (h/file-path "/workspace-a/.eca/rules/format.md")}
                    :match {:match? true
                            :matched-pattern "src/**.clj"
                            :relative-path (str (fs/path "src" "core.clj"))}}]
                  (f.rules/matching-path-scoped-rules {}
                                                      [{:uri (h/file-uri "file:///workspace-a")}]
                                                      "code"
                                                      "openai/gpt-4o"
                                                      (h/file-path "/workspace-a/src/core.clj")))))))

(deftest find-rule-by-id-test
  (testing "returns the matching path-scoped rule after agent/model filtering"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(contains? #{(h/file-path "/path/to/rule-a.md")
                                           (h/file-path "/path/to/rule-b.md")
                                           (h/file-path "/path/to/static.md")} %)
                  fs/file-name (fn [p]
                                 (cond (= (h/file-path "/path/to/rule-a.md") p) "rule-a"
                                       (= (h/file-path "/path/to/rule-b.md") p) "rule-b"
                                       (= (h/file-path "/path/to/static.md") p) "static"
                                       :else "unknown"))
                  clojure.core/slurp (fn [p]
                                       (cond (= (h/file-path "/path/to/rule-a.md") p) "---\nagent: code\npaths: \"src/**/*.clj\"\n---\n\nRule A content"
                                             (= (h/file-path "/path/to/rule-b.md") p) "---\nmodel: \"claude.*\"\npaths: \"src/**/*.md\"\n---\n\nRule B content"
                                             (= (h/file-path "/path/to/static.md") p) "Static content"))]
      (let [config {:rules [{:path (h/file-path "/path/to/rule-a.md")}
                            {:path (h/file-path "/path/to/rule-b.md")}
                            {:path (h/file-path "/path/to/static.md")}]}]
        (is (match? {:id (h/file-path "/path/to/rule-a.md") :name "rule-a" :content "Rule A content" :paths ["src/**/*.clj"]}
                    (f.rules/find-rule-by-id config [] (h/file-path "/path/to/rule-a.md") "code" "openai/gpt-4o")))
        (is (match? {:id (h/file-path "/path/to/rule-b.md") :name "rule-b" :content "Rule B content" :paths ["src/**/*.md"]}
                    (f.rules/find-rule-by-id config [] (h/file-path "/path/to/rule-b.md") "plan" "anthropic/claude-sonnet-4-20250514")))
        (is (nil? (f.rules/find-rule-by-id config [] (h/file-path "/path/to/rule-a.md") "plan" "openai/gpt-4o")))
        (is (nil? (f.rules/find-rule-by-id config [] (h/file-path "/path/to/rule-b.md") "plan" "openai/gpt-4o")))
        (is (nil? (f.rules/find-rule-by-id config [] (h/file-path "/path/to/static.md") "code" "openai/gpt-4o"))))))

  (testing "returns nil when no rule matches the id"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(= (h/file-path "/path/to/my-rule.md") %)
                  fs/file-name (constantly "existing-rule")
                  clojure.core/slurp (constantly "---\npaths: \"src/**/*.clj\"\n---\n\nRule content")]
      (let [config {:rules [{:path (h/file-path "/path/to/my-rule.md")}]}]
        (is (nil? (f.rules/find-rule-by-id config [] (h/file-path "/path/to/nonexistent.md") "code" "openai/gpt-4o"))))))

  (testing "different rules can share the same basename because lookup uses exact id"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(contains? #{(h/file-path "/workspace-a/.eca/rules/format.md")
                                           (h/file-path "/workspace-b/.eca/rules/format.md")} %)
                  fs/file-name (constantly "format.md")
                  clojure.core/slurp (fn [p]
                                       (cond (= (h/file-path "/workspace-a/.eca/rules/format.md") p) "---\npaths: \"src/a/**\"\n---\n\nWorkspace A rule"
                                             (= (h/file-path "/workspace-b/.eca/rules/format.md") p) "---\npaths: \"src/b/**\"\n---\n\nWorkspace B rule"))]
      (let [config {:rules [{:path (h/file-path "/workspace-a/.eca/rules/format.md")}
                            {:path (h/file-path "/workspace-b/.eca/rules/format.md")}]}]
        (is (= "Workspace A rule"
               (:content (f.rules/find-rule-by-id config [] (h/file-path "/workspace-a/.eca/rules/format.md") "code" "openai/gpt-4o"))))
        (is (= "Workspace B rule"
               (:content (f.rules/find-rule-by-id config [] (h/file-path "/workspace-b/.eca/rules/format.md") "code" "openai/gpt-4o"))))))))

(deftest match-path-scoped-rule-test
  (let [workspace-a (h/file-path "/workspace-a")
        workspace-b (h/file-path "/workspace-b")
        roots [{:uri (h/file-uri "file:///workspace-a")}
               {:uri (h/file-uri "file:///workspace-b")}]]
    (testing "project rule matches nested path inside its workspace root"
      (is (match? {:match? true
                   :reason nil
                   :workspace-root workspace-a
                   :relative-path (str (fs/path "src" "nested" "core.clj"))
                   :matched-pattern "src/**/*.clj"
                   :paths ["src/**/*.clj"]}
                  (f.rules/match-path-scoped-rule {:scope :project
                                                   :workspace-root workspace-a
                                                   :paths ["src/**/*.clj"]}
                                                  roots
                                                  (h/file-path "/workspace-a/src/nested/core.clj")))))

    (testing "project rule rejects files outside its workspace root"
      (is (match? {:match? false
                   :reason :outside-rule-workspace
                   :path workspace-b
                   :workspace-root workspace-a
                   :relative-path nil}
                  (f.rules/match-path-scoped-rule {:scope :project
                                                   :workspace-root workspace-a
                                                   :paths ["src/**/*.clj"]}
                                                  roots
                                                  workspace-b))))

    (testing "global rule matches relative path inside the containing workspace root"
      (is (match? {:match? true
                   :reason nil
                   :workspace-root workspace-b
                   :relative-path (str (fs/path "lib" "foo.cljs"))
                   :matched-pattern "lib/**.cljs"}
                  (f.rules/match-path-scoped-rule {:scope :global
                                                   :paths ["lib/**.cljs"]}
                                                  roots
                                                  (h/file-path "/workspace-b/lib/foo.cljs")))))

    (testing "matching requires an absolute target path"
      (is (match? {:match? false
                   :reason :path-not-absolute
                   :path "src/foo.clj"
                   :workspace-root nil
                   :relative-path nil}
                  (f.rules/match-path-scoped-rule {:scope :project
                                                   :workspace-root workspace-a
                                                   :paths ["src/**.clj"]}
                                                  roots
                                                  "src/foo.clj"))))))

(deftest real-file-rule-loading-test
  (testing "loads local path-scoped rules from real workspace files and matches target paths"
    (let [tmp-dir (fs/create-temp-dir {:prefix "eca-rules-test-"})
          rules-dir (fs/file tmp-dir ".eca" "rules")
          rule-file (fs/file rules-dir "clojure.md")
          target-file (fs/file tmp-dir "src" "nested" "foo.clj")]
      (try
        (fs/create-dirs rules-dir)
        (fs/create-dirs (fs/parent target-file))
        (spit rule-file "---\nagent: code\nmodel: \"openai/.*\"\npaths: \"src/**/*.clj\"\nenforce:\n  - read\n  - modify\n---\n\nUse project Clojure style.")
        (spit target-file "(ns foo)")
        (let [workspace-root (shared/normalize-path tmp-dir)
              roots [{:uri (shared/filename->uri (str tmp-dir))}]
              rules (f.rules/path-scoped-rules {} roots "code" "openai/gpt-5.2")
              rule (first rules)]
          (is (= 1 (count rules)))
          (is (match? {:name "clojure.md"
                       :scope :project
                       :workspace-root workspace-root
                       :content "Use project Clojure style."
                       :agents ["code"]
                       :models ["openai/.*"]
                       :paths ["src/**/*.clj"]
                       :enforce ["read" "modify"]}
                      rule))
          (is (match? {:match? true
                       :reason nil
                       :workspace-root workspace-root
                       :relative-path (str (fs/path "src" "nested" "foo.clj"))
                       :matched-pattern "src/**/*.clj"}
                      (f.rules/match-path-scoped-rule rule roots (str target-file)))))
        (finally
          (fs/delete-tree tmp-dir))))))

(deftest all-rules-test
  (testing "loads rules once and partitions into :static and :path-scoped"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(contains? #{(h/file-path "/path/to/static.md")
                                           (h/file-path "/path/to/scoped.md")} %)
                  fs/file-name (fn [p]
                                 (cond (= (h/file-path "/path/to/static.md") p) "static.md"
                                       (= (h/file-path "/path/to/scoped.md") p) "scoped.md"
                                       :else "unknown"))
                  clojure.core/slurp (fn [p]
                                       (cond (= (h/file-path "/path/to/static.md") p) "Always active"
                                             (= (h/file-path "/path/to/scoped.md") p) "---\npaths: \"src/**/*.clj\"\n---\n\nPath rule"))]
      (let [config {:rules [{:path (h/file-path "/path/to/static.md")}
                            {:path (h/file-path "/path/to/scoped.md")}]}
            {:keys [static path-scoped]} (f.rules/all-rules config [])]
        (is (= 1 (count static)))
        (is (= "static.md" (:name (first static))))
        (is (nil? (:paths (first static))))
        (is (= 1 (count path-scoped)))
        (is (= "scoped.md" (:name (first path-scoped))))
        (is (= ["src/**/*.clj"] (:paths (first path-scoped)))))))

  (testing "applies agent and model filters"
    (with-redefs [fs/absolute? (constantly true)
                  fs/exists? #(contains? #{(h/file-path "/path/to/code-only.md")
                                           (h/file-path "/path/to/global.md")} %)
                  fs/file-name (fn [p]
                                 (cond (= (h/file-path "/path/to/code-only.md") p) "code-only.md"
                                       (= (h/file-path "/path/to/global.md") p) "global.md"
                                       :else "unknown"))
                  clojure.core/slurp (fn [p]
                                       (cond (= (h/file-path "/path/to/code-only.md") p) "---\nagent: code\n---\n\nCode only"
                                             (= (h/file-path "/path/to/global.md") p) "For everyone"))]
      (let [config {:rules [{:path (h/file-path "/path/to/code-only.md")}
                            {:path (h/file-path "/path/to/global.md")}]}]
        (is (= 2 (count (:static (f.rules/all-rules config [] "code")))))
        (is (= 1 (count (:static (f.rules/all-rules config [] "plan")))))
        (is (= ["global.md"] (names (:static (f.rules/all-rules config [] "plan")))))))))
