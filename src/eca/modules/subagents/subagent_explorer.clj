(ns eca.modules.subagents.subagent-explorer
  "The `:eca/subagent-explorer` module: the explorer subagent, which finds and
   reads code without changing it. It only adds the agent's entry to the
   `agent` config, so the user's config, or another module, can change it."
  (:require
   [eca.modules.agents :as agents]))

(set! *warn-on-reflection* true)

(def module
  {:id :eca/subagent-explorer
   :description "The explorer subagent: finds and reads code without changing it."
   :requires #{:eca/subagents}
   :config-defaults {:agent {"explorer" {:mode "subagent"
                                         :description "${classpath:prompts/explorer_agent_description.md}"
                                         :systemPrompt "${classpath:prompts/explorer_agent.md}"
                                         :disabledTools ["edit_file" "write_file" "move_file" "preview_file_change" "git"]
                                         :toolCall {:approval {:byDefault "ask"
                                                               :allow {"eca__shell_command"
                                                                       {:argsMatchers {"command" agents/read-only-shell-commands}}
                                                                       "eca__compact_chat" {}
                                                                       "eca__read_file" {}
                                                                       "eca__directory_tree" {}
                                                                       "eca__grep" {}
                                                                       "eca__editor_diagnostics" {}
                                                                       "eca__skill" {}
                                                                       "eca__search_tools" {}
                                                                       "eca__task" {}
                                                                       "eca__fetch_rule" {}}
                                                               :deny {"eca__shell_command"
                                                                      {:argsMatchers {"command" agents/dangerous-commands-regexes}}}}}}}}})
