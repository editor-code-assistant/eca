(ns eca.features.prompt-sections-test
  "The static system prompt is built from the active modules' sections."
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.prompt :as f.prompt]
   [eca.modules :as modules]
   [eca.test-helper :as h]))

(h/reset-components-before-test)

(def ^:private skills [{:name "review" :description "Review code."}])

(defn- build [config]
  (f.prompt/build-static-instructions [] [] [] skills (delay nil) "code" config "chat-1" [] (h/db)))

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
