(ns eca.modules.create
  "The `:eca/create` module: helps the user set up and change ECA, with
   `/init` (AGENTS.md), `/skill-create` and the built-in `eca-manage` skill."
  (:require
   [babashka.fs :as fs]
   [eca.features.prompt :as f.prompt]
   [eca.modules.skills :as f.skills]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn ^:private init-prompt [all-tools agent-name db config]
  (shared/safe-selmer-render
   (f.prompt/get-config-prompt :init agent-name config)
   (f.prompt/->base-selmer-ctx all-tools db)
   "init-prompt"))

(defn ^:private skill-create-prompt [skill-name user-prompt all-tools agent-name db config]
  (shared/safe-selmer-render
   (f.prompt/get-config-prompt :skillCreate agent-name config)
   (merge
    (f.prompt/->base-selmer-ctx all-tools db)
    {:skillFilePath (str (fs/file (f.skills/global-skills-dir) skill-name "SKILL.md"))
     :skillName skill-name
     :userPrompt user-prompt})
   "skill-create-prompt"))

(defn ^:private init-command [{:keys [chat-id db* config agent all-tools]} _args]
  {:type :send-prompt
   :on-finished-side-effect (fn []
                              (swap! db* assoc-in [:chats chat-id :messages] []))
   :prompt (init-prompt all-tools agent @db* config)})

(defn ^:private skill-create-command [{:keys [db* config agent all-tools]} {args :argv}]
  (let [skill-name (first args)
        user-prompt (second args)]
    {:type :send-prompt
     :prompt (skill-create-prompt skill-name user-prompt all-tools agent @db* config)}))

(def module
  {:id :eca/create
   :description "Help the user set up and change ECA: AGENTS.md, skills, and the eca-manage skill."
   :config-spec {:prompts {:init {:type :string :default "${classpath:prompts/init.md}"}
                           :skillCreate {:type :string :default "${classpath:prompts/skill_create.md}"}}}
   :commands {"init" {:description "Create/update the AGENTS.md file teaching LLM about the project"
                      :arguments []
                      :handler #'init-command}
              "skill-create" {:description "Create a skill considering a user request"
                              :arguments [{:name "name" :description "The skill name" :required true}
                                          {:name "prompt" :description "What to consider as this skill content" :required true}]
                              :handler #'skill-create-command}}})
