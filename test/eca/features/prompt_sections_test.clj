(ns eca.features.prompt-sections-test
  "The static system prompt is built from the active modules' sections."
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.prompt :as f.prompt]
   [eca.modules :as modules]
   [eca.modules.rules :as m.rules]
   [eca.modules.skills :as m.skills]
   [eca.test-helper :as h]))

(h/reset-components-before-test)

(def ^:private skills [{:name "review" :description "Review code."}])

(defn- prompt-ctx [config]
  {:refined-contexts [] :agent "code" :config config :chat-id "chat-1" :all-tools [] :db (h/db)})

(defn- build [config]
  (with-redefs [m.rules/all-rules (constantly {:static [] :path-scoped []})
                m.skills/all (constantly skills)]
    (f.prompt/build-static-instructions (prompt-ctx config))))

(defn- index-of [s sub]
  (string/index-of s sub))

(deftest built-in-sections-test
  (let [prompt (build {})]
    (testing "built-in sections keep their order"
      (is (< (index-of prompt "## Skills") (index-of prompt "## Workspace Roots"))))
    (testing "disabling a module drops its section"
      (is (not (string/includes? (build {:modules {:eca/skills {:enabled false}}}) "## Skills"))))))

(deftest module-sections-test
  (with-redefs [modules/built-in-modules
                (constantly (conj (h/built-in-modules-with :eca/skills :eca/agents)
                                  {:id :eca/test-sections
                                   :system-prompts [{:id :test/after-skills :after :eca/skills
                                                     :content "AFTER-SKILLS on {{osName}}"}
                                                    {:id :test/before-skills :before :eca/skills
                                                     :content (fn [ctx] (str "BEFORE-SKILLS for " (:chat-id ctx)))}
                                                    {:id :test/at-end :content "AT-END"}
                                                    {:id :test/nowhere :after :eca/nope :content "NOWHERE"}
                                                    {:id :test/silent :content (constantly nil)}]}))]
    (let [prompt (build {})]
      (testing ":before and :after place a section next to another one"
        (is (< (index-of prompt "BEFORE-SKILLS for chat-1") (index-of prompt "## Skills")
               (index-of prompt "AFTER-SKILLS on ")))
        (is (not (string/includes? prompt "{{osName}}")) "string content is a Selmer template"))
      (testing "without a target, or with a missing one, at the end"
        (is (< (index-of prompt "## Workspace Roots") (index-of prompt "AT-END") (index-of prompt "NOWHERE"))))
      (testing "a nil section is left out"
        (is (not (string/includes? prompt "nil")))))))

(defn- uncategorized [config]
  (with-redefs [m.rules/all-rules (constantly {:static [] :path-scoped []})
                m.skills/all (constantly skills)]
    (f.prompt/uncategorized-static-sections (prompt-ctx config))))

(deftest uncategorized-static-sections-test
  (testing "the built-in sections are all followed by the cache signature's categories"
    (is (nil? (uncategorized {}))))
  (testing "a module's static section isn't: its text, as it renders now, and placement"
    (let [conventions* (atom "Use tabs.")]
      (modules/add-external! "/tmp/acme-prompt.clj"
                             {:id :acme/prompt
                              :system-prompts [{:id :acme/conventions :after :eca/skills
                                                :content (fn [_] @conventions*)}
                                               {:id :acme/live :dynamic? true :content "Not static."}]})
      (is (= {:sections [[:acme/conventions nil :eca/skills "Use tabs."]] :missing []}
             (uncategorized {})))
      (reset! conventions* "Use spaces.")
      (is (= [[:acme/conventions nil :eca/skills "Use spaces."]] (:sections (uncategorized {}))))))
  (testing "nor is a built-in section that's gone, its module turned off"
    (modules/reset-registry!)
    (is (= {:sections [] :missing [:eca/skills]}
           (uncategorized {:modules {:eca/skills {:enabled false}}})))))

(deftest section-signatures-test
  (with-redefs [m.rules/all-rules (constantly {:static [{:id "r1" :name "r1" :scope :global :content "Rule" :extra 1}]
                                               :path-scoped []})
                m.skills/all (constantly skills)]
    (testing "the built-in sections with their own signature, by name, in prompt order; skills alone don't re-sync"
      (is (= [[:rules {:static-rules [{:id "r1" :name "r1" :scope :global :content "Rule"}] :path-scoped-rules []} true]
              [:skills [{:name "review" :description "Review code."}] false]]
             (f.prompt/section-signatures (prompt-ctx {})))))
    (testing "also while their module is off, as core followed their data before modules"
      (is (= [:rules :skills]
             (map first (f.prompt/section-signatures (prompt-ctx {:modules {:eca/skills {:enabled false}}}))))))))
