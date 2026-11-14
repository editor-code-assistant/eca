(ns eca.modules.skills-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.modules :as modules]
   [eca.modules.skills :as m.skills]
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
             (m.skills/all {} roots))))))

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
             (m.skills/all {} roots))))))

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
             (m.skills/all {} roots))))))

  (testing "config skill directories load SKILL.md files recursively"
    (let [tmp-dir (fs/create-temp-dir)
          skills-dir (fs/file tmp-dir "skills")
          skill-dir (fs/file skills-dir "configured")]
      (try
        (fs/create-dirs skill-dir)
        (spit (fs/file skill-dir "SKILL.md") "---\nname: configured\ndescription: Configured skill\n---\nConfigured body")
        (spit (fs/file skills-dir "ignored.md") "Ignored")
        (let [config {:pureConfig true :skills [{:path (str skills-dir)}]}
              ;; Exclude the skills modules ship (they have a :module) so
              ;; this test stays focused on configured on-disk skills.
              result (->> (m.skills/all config [])
                          (remove :module)
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
               (m.skills/all {:pureConfig true} roots))))
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
        (let [skill (#'m.skills/skill-file->skill (fs/file skills-dir "SKILL.md"))]
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
             (m.skills/all {} roots))))))

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
             (m.skills/all config []))))))

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
             (m.skills/all config []))))))

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
             (m.skills/all config [])))))))

(defn ^:private write-skill! [skills-dir skill-name description]
  (let [skill-dir (fs/file skills-dir skill-name)]
    (fs/create-dirs skill-dir)
    (spit (fs/file skill-dir "SKILL.md")
          (str "---\nname: " skill-name "\ndescription: " description "\n---\nBody"))))

(defn ^:private on-disk-skills
  "Calls `setup!` with a temp home and workspace dir, then returns the
   [name description] of each skill on disk found by `m.skills/all`."
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
        (->> (m.skills/all config [{:uri (shared/filename->uri (str workspace))}])
             (remove :module)
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

(deftest module-skills-test
  (let [test-module {:id :eca/test-skills
                     :skills [{:name "static" :description "A static skill" :body "Review $ARGS"}
                              {:name "computed" :description "A computed skill"
                               :body (fn [{:keys [module-id agent skills]}]
                                       (str "Built by " module-id " for " agent ", with " (count skills) " skills"))}]}
        tool-ctx {:db {:workspace-folders []} :config {:pureConfig true} :agent "code"}]
    (with-redefs [modules/built-in-modules (constantly (conj (h/built-in-modules-with :eca/skills) test-module))]
      (testing "a module's skills are listed with its id; a string body gets its arguments"
        (is (match? [{:name "static" :module :eca/test-skills :arguments [{:name "arg1"}]}
                     {:name "computed" :module :eca/test-skills}]
                    (m.skills/all {:pureConfig true} []))))
      (testing "a skill on disk with the same name wins"
        (let [tmp-dir (fs/create-temp-dir)]
          (try
            (write-skill! tmp-dir "static" "From disk")
            (is (match? [{:name "static" :description "From disk" :dir string?}
                         {:name "computed" :module :eca/test-skills}]
                        (m.skills/all {:pureConfig true :skills [{:path (str tmp-dir)}]} [])))
            (finally
              (fs/delete-tree tmp-dir)))))
      (testing "the skill tool returns a string body as is"
        (is (match? {:error false
                     :contents [{:type :text :text #"\*\*Base directory\*\*: \(built-in\)\n\nReview \$ARGS$"}]}
                    (#'m.skills/skill {"name" "static"} tool-ctx))))
      (testing "and calls a function body as its module, with all skills"
        (is (match? {:error false
                     :contents [{:type :text :text #"\n\nBuilt by :eca/test-skills for code, with 2 skills$"}]}
                    (#'m.skills/skill {"name" "computed"} tool-ctx))))
      (testing "turning the module off drops its skills"
        (is (empty? (m.skills/all {:pureConfig true :modules {:eca/test-skills {:enabled false}}} [])))))))
