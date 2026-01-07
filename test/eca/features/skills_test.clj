(ns eca.features.skills-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.features.skills :as f.skills]
   [eca.test-helper :as h]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(deftest all-test
  (testing "local skills"
    (with-redefs [config/get-env (constantly nil)
                  config/get-property (constantly (h/file-path "/home/someuser"))
                  fs/exists? #(= (h/file-path "/my/project/.eca/skills") (str %))
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
             (f.skills/all roots))))))

  (testing "global skills"
    (with-redefs [config/get-env (constantly (h/file-path "/home/someuser/.config"))
                  fs/exists? #(= (h/file-path "/home/someuser/.config/eca/skills") (str %))
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
             (f.skills/all roots))))))

  (testing "global skills with XDG_CONFIG_HOME fallback"
    (with-redefs [config/get-env (constantly nil)
                  config/get-property (constantly (h/file-path "/home/someuser"))
                  fs/exists? #(= (h/file-path "/home/someuser/.config/eca/skills") (str %))
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
             (f.skills/all roots))))))

  (testing "skills with quoted YAML values"
    (with-redefs [config/get-env (constantly nil)
                  config/get-property (constantly (h/file-path "/home/someuser"))
                  fs/exists? #(= (h/file-path "/my/project/.eca/skills") (str %))
                  fs/glob (constantly [(fs/path (h/file-path "/my/project/.eca/skills/quoted-skill/SKILL.md"))])
                  fs/canonicalize identity
                  fs/parent (constantly (fs/path (h/file-path "/my/project/.eca/skills/quoted-skill")))
                  clojure.core/slurp (constantly "---\nname: \"quoted-skill\"\ndescription: 'Single quoted description'\n---\nBody")]
      (let [roots [{:uri (h/file-uri "file:///my/project")}]]
        (is (match?
             (m/embeds [{:name "quoted-skill"
                         :description "Single quoted description"
                         :body "Body"}])
             (f.skills/all roots)))))))
