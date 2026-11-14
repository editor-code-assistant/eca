(ns eca.modules.create
  "The `:eca/create` module: helps the user set up and change ECA, with
   `/init` (AGENTS.md), `/skill-create` and the built-in `eca-manage` skill."
  (:require
   [eca.features.built-in-command :as built-in-command]))

(set! *warn-on-reflection* true)

(def module
  {:id :eca/create
   :description "Help the user set up and change ECA: AGENTS.md, skills, and the eca-manage skill."
   :config-spec {:prompts {:init {:type :string :default "${classpath:prompts/init.md}"}
                           :skillCreate {:type :string :default "${classpath:prompts/skill_create.md}"}}}
   :commands (built-in-command/commands
              [["init" "Create/update the AGENTS.md file teaching LLM about the project" []]
               ["skill-create" "Create a skill considering a user request"
                [{:name "name" :description "The skill name" :required true}
                 {:name "prompt" :description "What to consider as this skill content" :required true}]]])})
