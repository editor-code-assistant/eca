(ns eca.modules.subagents.subagent-general
  "The `:eca/subagent-general` module: the general subagent, for research and
   multi-step tasks. It only adds the agent's entry to the `agent` config, so
   the user's config, or another module, can change it.")

(set! *warn-on-reflection* true)

(def module
  {:id :eca/subagent-general
   :description "The general subagent: research and multi-step tasks."
   :requires #{:eca/subagents}
   :config-defaults {:agent {"general" {:mode "subagent"
                                        :description "${classpath:prompts/general_agent_description.md}"
                                        :systemPrompt "${classpath:prompts/code_agent.md}"
                                        :disabledTools ["preview_file_change"]}}}})
