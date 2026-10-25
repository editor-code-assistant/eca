(ns eca.features.skills-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.features.skills :as f.skills]
   [eca.shared :as shared]
   [eca.test-helper :as h]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(deftest all-test
  (testing "local skills"
    (with-redefs [config/get-env (constantly nil)
                  config/get-property (constantly (h/file-path "/home/someuser"))
                  fs/exists? #(= (h/file-path "/my/project/.eca/skills") (str %))
                  fs/directory? #(= (h/file-path "/my/project/.eca/skills") (str %))
                  fs/glob (constantly [(fs/path (h/file-path "/my/project/.eca/skills/my-skill/SKILL.md"))])
                  fs/canonicalize identity
                  fs/parent (constantly (fs/path (h/file-path "/my/project/.eca/skills/my-skill")))
                  clojure.core/slurp (constantly "---\nname: my-skill\ndescription: A test skill\n---\nSkill body content")]
      (let [roots [{:uri (h/file-uri "file:///my/project")}]]
        (is (match?
             (m/embeds [{:name "my-skill"
                         :description "A test skill"
                         :body "Skill body content"
                         :dir (h/file-path "/my/project/.eca/skills/my-skill")}])
             (f.skills/all {} roots))))))

  (testing "global skills"
    (with-redefs [config/get-env (constantly (h/file-path "/home/someuser/.config"))
                  fs/exists? #(= (h/file-path "/home/someuser/.config/eca/skills") (str %))
                  fs/directory? #(= (h/file-path "/home/someuser/.config/eca/skills") (str %))
                  fs/glob (constantly [(fs/path (h/file-path "/home/someuser/.config/eca/skills/global-skill/SKILL.md"))])
                  fs/canonicalize identity
                  fs/parent (constantly (fs/path (h/file-path "/home/someuser/.config/eca/skills/global-skill")))
                  clojure.core/slurp (constantly "---\nname: global-skill\ndescription: A global skill\n---\nGlobal skill body")]
      (let [roots []]
        (is (match?
             (m/embeds [{:name "global-skill"
                         :description "A global skill"
                         :body "Global skill body"
                         :dir (h/file-path "/home/someuser/.config/eca/skills/global-skill")}])
             (f.skills/all {} roots))))))

  (testing "global skills with XDG_CONFIG_HOME fallback"
    (with-redefs [config/get-env (constantly nil)
                  config/user-home (constantly (h/file-path "/home/someuser"))
                  fs/exists? #(= (h/file-path "/home/someuser/.config/eca/skills") (str %))
                  fs/directory? #(= (h/file-path "/home/someuser/.config/eca/skills") (str %))
                  fs/glob (constantly [(fs/path (h/file-path "/home/someuser/.config/eca/skills/fallback-skill/SKILL.md"))])
                  fs/canonicalize identity
                  fs/parent (constantly (fs/path (h/file-path "/home/someuser/.config/eca/skills/fallback-skill")))
                  clojure.core/slurp (constantly "---\nname: fallback-skill\ndescription: Fallback skill\n---\nFallback body")]
      (let [roots []]
        (is (match?
             (m/embeds [{:name "fallback-skill"
                         :description "Fallback skill"
                         :body "Fallback body"
                         :dir (h/file-path "/home/someuser/.config/eca/skills/fallback-skill")}])
             (f.skills/all {} roots))))))

  (testing "config skill directories load SKILL.md files recursively"
    (let [tmp-dir (fs/create-temp-dir)
          skills-dir (fs/file tmp-dir "skills")
          skill-dir (fs/file skills-dir "configured")]
      (try
        (fs/create-dirs skill-dir)
        (spit (fs/file skill-dir "SKILL.md") "---\nname: configured\ndescription: Configured skill\n---\nConfigured body")
        (spit (fs/file skills-dir "ignored.md") "Ignored")
        (let [config {:pureConfig true :skills [{:path (str skills-dir)}]}
              ;; Exclude built-in skills (identified by :handler-fn) so this
              ;; test stays focused on configured on-disk skills.
              result (->> (f.skills/all config [])
                          (remove :handler-fn)
                          vec)]
          (is (= ["configured"] (mapv :name result)))
          (is (= ["Configured skill"] (mapv :description result))))
        (finally
          (fs/delete-tree tmp-dir)))))

  (testing "local .agents skills"
    (let [tmp-dir (fs/create-temp-dir)
          skill-dir (fs/file tmp-dir ".agents" "skills" "agent-skill")]
      (try
        (fs/create-dirs skill-dir)
        (spit (fs/file skill-dir "SKILL.md") "---\nname: agent-skill\ndescription: Agent skill\n---\nAgent body")
        (let [roots [{:uri (shared/filename->uri (str tmp-dir))}]]
          (is (match?
               (m/embeds [{:name "agent-skill"
                           :description "Agent skill"
                           :body "Agent body"}])
               (f.skills/all {:pureConfig true} roots))))
        (finally
          (fs/delete-tree tmp-dir)))))


  (testing "resolves dynamic strings in skill markdown"
    (let [tmp-dir (fs/create-temp-dir)
          skills-dir (fs/file tmp-dir "skills" "dynamic-skill")
          fragments-dir (fs/file skills-dir "fragments")]
      (try
        (fs/create-dirs fragments-dir)
        (spit (fs/file fragments-dir "instructions.md") "Do it carefully.")
        (spit (fs/file skills-dir "SKILL.md")
              (str "---\n"
                   "name: dynamic-skill\n"
                   "description: A skill with dynamic content\n"
                   "---\n"
                   "Base instructions: ${file:./fragments/instructions.md}"))
        (let [skill (#'f.skills/skill-file->skill (fs/file skills-dir "SKILL.md"))]
          (is (match? {:name "dynamic-skill"
                       :description "A skill with dynamic content"
                       :body "Base instructions: Do it carefully."}
                      skill)))
        (finally
          (fs/delete-tree tmp-dir)))))

  (testing "skills with quoted YAML values"
    (with-redefs [config/get-env (constantly nil)
                  config/get-property (constantly (h/file-path "/home/someuser"))
                  fs/exists? #(= (h/file-path "/my/project/.eca/skills") (str %))
                  fs/directory? #(= (h/file-path "/my/project/.eca/skills") (str %))
                  fs/glob (constantly [(fs/path (h/file-path "/my/project/.eca/skills/quoted-skill/SKILL.md"))])
                  fs/canonicalize identity
                  fs/parent (constantly (fs/path (h/file-path "/my/project/.eca/skills/quoted-skill")))
                  clojure.core/slurp (constantly "---\nname: \"quoted-skill\"\ndescription: 'Single quoted description'\n---\nBody")]
      (let [roots [{:uri (h/file-uri "file:///my/project")}]]
        (is (match?
             (m/embeds [{:name "quoted-skill"
                         :description "Single quoted description"
                         :body "Body"}])
             (f.skills/all {} roots))))))

  (testing "plugin skills are prefixed with plugin name"
    (with-redefs [config/get-env (constantly nil)
                  config/get-property (constantly (h/file-path "/home/someuser"))
                  fs/exists? (fn [p] (= (h/file-path "/plugins/ui/skills") (str p)))
                  fs/directory? #(= (h/file-path "/plugins/ui/skills") (str %))
                  fs/glob (constantly [(fs/path (h/file-path "/plugins/ui/skills/button/SKILL.md"))])
                  fs/canonicalize identity
                  fs/parent (constantly (fs/path (h/file-path "/plugins/ui/skills/button")))
                  clojure.core/slurp (constantly "---\nname: button\ndescription: Button skill\n---\nButton body")]
      (let [config {:pureConfig true
                    :pluginSkillDirs [{:plugin "ui" :dir (h/file-path "/plugins/ui/skills")}]}]
        (is (match?
             (m/embeds [{:name "ui:button"
                         :plugin "ui"
                         :description "Button skill"}])
             (f.skills/all config []))))))

  (testing "plugin skill with same name as plugin drops the prefix"
    (with-redefs [config/get-env (constantly nil)
                  config/get-property (constantly (h/file-path "/home/someuser"))
                  fs/exists? (fn [p] (= (h/file-path "/plugins/tdd/skills") (str p)))
                  fs/directory? #(= (h/file-path "/plugins/tdd/skills") (str %))
                  fs/glob (constantly [(fs/path (h/file-path "/plugins/tdd/skills/tdd/SKILL.md"))])
                  fs/canonicalize identity
                  fs/parent (constantly (fs/path (h/file-path "/plugins/tdd/skills/tdd")))
                  clojure.core/slurp (constantly "---\nname: tdd\ndescription: TDD skill\n---\nTDD body")]
      (let [config {:pureConfig true
                    :pluginSkillDirs [{:plugin "tdd" :dir (h/file-path "/plugins/tdd/skills")}]}]
        (is (match?
             (m/embeds [{:name "tdd"
                         :plugin "tdd"
                         :description "TDD skill"}])
             (f.skills/all config []))))))

  (testing "plugin skill dirs given as bare strings stay unprefixed (legacy)"
    (with-redefs [config/get-env (constantly nil)
                  config/get-property (constantly (h/file-path "/home/someuser"))
                  fs/exists? (fn [p] (= (h/file-path "/plugins/legacy/skills") (str p)))
                  fs/directory? #(= (h/file-path "/plugins/legacy/skills") (str %))
                  fs/glob (constantly [(fs/path (h/file-path "/plugins/legacy/skills/old/SKILL.md"))])
                  fs/canonicalize identity
                  fs/parent (constantly (fs/path (h/file-path "/plugins/legacy/skills/old")))
                  clojure.core/slurp (constantly "---\nname: old\ndescription: Legacy\n---\nOld body")]
      (let [config {:pureConfig true
                    :pluginSkillDirs [(h/file-path "/plugins/legacy/skills")]}]
        (is (match?
             (m/embeds [{:name "old"
                         :description "Legacy"}])
             (f.skills/all config []))))))

  (testing "eca-info built-in skill is always available"
    (is (match?
         (m/embeds [{:name "eca-info"
                     :handler-fn fn?}])
         (f.skills/all {:pureConfig true} [])))))

(defn ^:private write-skill! [skills-dir skill-name description]
  (let [skill-dir (fs/file skills-dir skill-name)]
    (fs/create-dirs skill-dir)
    (spit (fs/file skill-dir "SKILL.md")
          (str "---\nname: " skill-name "\ndescription: " description "\n---\nBody"))))

(defn ^:private on-disk-skills
  "Calls `setup!` with a temp home and workspace dir, then returns the
   [name description] of each non built-in skill found by `f.skills/all`."
  [config setup!]
  (let [tmp-dir (fs/create-temp-dir)
        home (fs/file tmp-dir "home")
        workspace (fs/file tmp-dir "workspace")]
    (try
      (fs/create-dirs home)
      (fs/create-dirs workspace)
      (setup! {:home home :workspace workspace})
      (with-redefs [config/get-env (constantly nil)
                    config/user-home (constantly (str home))]
        (->> (f.skills/all config [{:uri (shared/filename->uri (str workspace))}])
             (remove :handler-fn)
             (mapv (juxt :name :description))))
      (finally
        (fs/delete-tree tmp-dir)))))

(deftest agents-home-skills-test
  (testing "discovers skills from ~/.agents/skills"
    (is (= [["home-skill" "From ~/.agents/skills"]]
           (on-disk-skills {} (fn [{:keys [home]}]
                                (write-skill! (fs/file home ".agents" "skills") "home-skill" "From ~/.agents/skills"))))))

  (testing "skips ~/.agents/skills when pureConfig is set"
    (is (= []
           (on-disk-skills {:pureConfig true} (fn [{:keys [home]}]
                                                (write-skill! (fs/file home ".agents" "skills") "home-skill" "From ~/.agents/skills")))))))

(deftest skill-name-precedence-test
  (testing "keeps skills with distinct names, workspace ones first"
    (is (= [["workspace-skill" "workspace"]
            ["eca-global-skill" "global eca"]
            ["agents-global-skill" "global .agents"]]
           (on-disk-skills {} (fn [{:keys [home workspace]}]
                                (write-skill! (fs/file home ".agents" "skills") "agents-global-skill" "global .agents")
                                (write-skill! (fs/file home ".config" "eca" "skills") "eca-global-skill" "global eca")
                                (write-skill! (fs/file workspace ".agents" "skills") "workspace-skill" "workspace"))))))

  (testing "a workspace skill overrides a global skill with the same name"
    (is (= [["shared" "workspace .agents"]]
           (on-disk-skills {} (fn [{:keys [home workspace]}]
                                (write-skill! (fs/file home ".config" "eca" "skills") "shared" "global eca")
                                (write-skill! (fs/file home ".agents" "skills") "shared" "global .agents")
                                (write-skill! (fs/file workspace ".agents" "skills") "shared" "workspace .agents"))))))

  (testing "workspace .eca/skills overrides workspace .agents/skills"
    (is (= [["shared" "workspace eca"]]
           (on-disk-skills {} (fn [{:keys [workspace]}]
                                (write-skill! (fs/file workspace ".agents" "skills") "shared" "workspace .agents")
                                (write-skill! (fs/file workspace ".eca" "skills") "shared" "workspace eca"))))))

  (testing "~/.config/eca/skills overrides ~/.agents/skills"
    (is (= [["shared" "global eca"]]
           (on-disk-skills {} (fn [{:keys [home]}]
                                (write-skill! (fs/file home ".agents" "skills") "shared" "global .agents")
                                (write-skill! (fs/file home ".config" "eca" "skills") "shared" "global eca"))))))

  (testing "configured skill paths override workspace skills"
    (is (= [["shared" "configured"]]
           (on-disk-skills {:skills [{:path "configured-skills"}]}
                           (fn [{:keys [workspace]}]
                             (write-skill! (fs/file workspace ".eca" "skills") "shared" "workspace eca")
                             (write-skill! (fs/file workspace "configured-skills") "shared" "configured"))))))

  (testing "a workspace skill overrides a plugin skill with the same name"
    (let [plugin-dir (str (fs/create-temp-dir))]
      (try
        (write-skill! plugin-dir "tdd" "plugin")
        (is (= [["tdd" "workspace"]]
               (on-disk-skills {:pluginSkillDirs [{:plugin "tdd" :dir plugin-dir}]}
                               (fn [{:keys [workspace]}]
                                 (write-skill! (fs/file workspace ".eca" "skills") "tdd" "workspace")))))
        (finally
          (fs/delete-tree plugin-dir))))))

(deftest eca-info-handler-test
  (testing "eca-info handler returns a markdown report with expected sections"
    (let [skill (->> (f.skills/all {:pureConfig true} [])
                     (filter #(= "eca-info" (:name %)))
                     first)
          body ((:handler-fn skill)
                {:db {:client-info {:name "test-client" :version "1.0"}
                      :workspace-folders []
                      :auth {}
                      :models {}
                      :mcp-clients {}}
                 :config {}
                 :skills [skill]})]
      (is (string? body))
      (is (string/includes? body "# ECA Self-Debug Report"))
      (is (string/includes? body "## Versions"))
      (is (string/includes? body "## Server process"))
      (is (string/includes? body "## Workspaces"))
      (is (string/includes? body "## Models"))
      (is (string/includes? body "## Logged providers"))
      (is (string/includes? body "## MCP servers"))
      (is (string/includes? body "## Skills"))
      (is (string/includes? body "## Subagents"))
      (is (string/includes? body "## Credential files"))
      (is (string/includes? body "test-client 1.0"))))

  (testing "eca-info lists only subagents visible to the current primary agent"
    (let [config {:pureConfig true
                  :agent {"explorer" {:mode "subagent"
                                      :description "Unrestricted"}
                          "duel-worker" {:mode "subagent"
                                         :description "Private"
                                         :spawnableBy "duel"}}}
          skill (->> (f.skills/all config [])
                     (filter #(= "eca-info" (:name %)))
                     first)
          body-for (fn [agent]
                     ((:handler-fn skill)
                      {:db {:workspace-folders []
                            :auth {}
                            :models {}
                            :mcp-clients {}}
                       :config config
                       :skills [skill]
                       :agent agent}))]
      (is (string/includes? (body-for "duel") "duel-worker"))
      (is (not (string/includes? (body-for "code") "duel-worker")))
      (is (not (string/includes? (body-for nil) "duel-worker")))
      (is (string/includes? (body-for "code") "explorer")))))
