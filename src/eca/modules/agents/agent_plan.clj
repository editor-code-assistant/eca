(ns eca.modules.agents.agent-plan
  "The `:eca/agent-plan` module: the plan agent, which plans changes without
   making them. It only adds the agent's entry to the `agent` config, so the
   user's config, or another module, can change it."
  (:require
   [eca.modules.agents :as m.agents]))

(set! *warn-on-reflection* true)

(def module
  {:id :eca/agent-plan
   :description "The plan agent: plans changes without making them."
   :requires #{:eca/agents}
   :config-defaults {:agent {"plan" {:mode "primary"
                                     :prompts {:chat "${classpath:prompts/plan_agent.md}"}
                                     :disabledTools ["edit_file" "write_file" "move_file" "git"]
                                     :toolCall {:approval {:byDefault "ask"
                                                           :allow {"eca__shell_command"
                                                                   {:argsMatchers {"command" m.agents/read-only-shell-commands}}
                                                                   "eca__compact_chat" {}
                                                                   "eca__preview_file_change" {}
                                                                   "eca__read_file" {}
                                                                   "eca__directory_tree" {}
                                                                   "eca__grep" {}
                                                                   "eca__editor_diagnostics" {}
                                                                   "eca__skill" {}
                                                                   "eca__search_tools" {}
                                                                   "eca__task" {}
                                                                   "eca__fetch_rule" {}
                                                                   "eca__spawn_agent" {}}
                                                           :deny {"eca__shell_command"
                                                                  {:argsMatchers {"command" m.agents/dangerous-commands-regexes}}}}}}}}})
