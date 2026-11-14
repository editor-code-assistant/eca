(ns eca.modules.manage-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.features.commands :as f.commands]
   [eca.modules :as modules]
   [eca.modules.skills :as m.skills]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(defn- skill-named [config skill-name]
  (->> (m.skills/all config [])
       (filter #(= skill-name (:name %)))
       first))

(deftest skills-test
  (testing "eca/manage ships eca-info and eca-manage, built when the model loads them"
    (is (match? [{:name "eca-info" :module :eca/manage :body var?}
                 {:name "eca-manage" :module :eca/manage :body var?}]
                (m.skills/all {:pureConfig true} []))))
  (testing "turning eca/manage off drops them"
    (is (empty? (m.skills/all {:pureConfig true :modules {:eca/manage {:enabled false}}} [])))))

(deftest commands-test
  (testing "/init and /skill-create come from eca/manage"
    (is (= :eca/manage (:id (:entry (#'f.commands/find-module-command "init" {})))))
    (is (= :eca/manage (:id (:entry (#'f.commands/find-module-command "skill-create" {}))))))
  (testing "eca/manage stays on without eca/skills, for its commands"
    (is (modules/active? {:modules {:eca/skills {:enabled false}}} :eca/manage))))

(deftest skill-tool-test
  (testing "the skill tool builds eca-info's body with all skills"
    (let [{:keys [error contents]} (#'m.skills/skill {"name" "eca-info"}
                                                     {:db {:workspace-folders [] :auth {} :models {} :mcp-clients {}}
                                                      :config {:pureConfig true}
                                                      :agent "code"})
          text (:text (first contents))]
      (is (false? error))
      (is (string/starts-with? text "**Skill**: eca-info\n**Base directory**: (built-in)\n\n# ECA Self-Debug Report\n"))
      (is (string/includes? text "## Skills (2)"))
      (is (string/includes? text "- eca-manage — Change ECA itself"))
      (is (string/includes? text "(module:eca/manage)")))))

(deftest eca-manage-test
  (testing "its body points to ECA's docs, and tells this ECA's version and modules"
    (modules/add-external! "/tmp/mods/hello.clj" {:id :acme/hello} {:kind :global :rank 3})
    (modules/add-external! "/tmp/mods/broken.clj" nil {:kind :global :rank 3 :error "Boom (broken.clj:3:1)"})
    (try
      (let [config {:pureConfig true}
            skill (skill-named config "eca-manage")
            body ((:body skill) {:db {} :config config :skills [skill]})]
        (is (string/starts-with? body "# Changing ECA\n"))
        (is (string/includes? body "https://eca.dev/config.json"))
        (is (string/includes? body (str "- Version: " (config/eca-version) ".")))
        (is (re-find #"- Built-in modules, active: .*`eca/manage`" body))
        (is (re-find #"- Built-in modules, not active: .*`eca/repl`" body))
        (is (string/includes? body "  - `acme/hello` (active, from `/tmp/mods/hello.clj`)"))
        (is (string/includes? body "  - `broken.clj` (failed, from `/tmp/mods/broken.clj`): Failed to load: Boom (broken.clj:3:1)"))
        (is (< (count body) 5000))
        (testing "it names every page of docs/config, and only those"
          (let [linked (map second (re-seq #"https://eca\.dev/config/([a-z-]+)/" body))
                also (some->> (re-find #"Also (.+), at `https://eca\.dev/config/<page>/`" body)
                              second
                              (re-seq #"`([a-z-]+)`")
                              (map second))
                pages (map #(string/replace (fs/file-name %) #"\.md$" "") (fs/glob "docs/config" "*.md"))]
            (is (seq linked))
            (is (= (set pages) (set (concat linked also))))
            (is (apply distinct? (concat linked also))))))
      (finally
        (modules/reset-registry!)))))

(deftest eca-info-test
  (testing "eca-info's body is a markdown report with expected sections"
    (let [skill (skill-named {:pureConfig true} "eca-info")
          body ((:body skill)
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
          skill (skill-named config "eca-info")
          body-for (fn [agent]
                     ((:body skill)
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
