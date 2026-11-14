(ns eca.modules.agents.agent-code
  "The `:eca/agent-code` module: the code agent, for coding tasks, and ECA's
   default agent. It only adds the agent's entry to the `agent` config, so the
   user's config, or another module, can change it.")

(set! *warn-on-reflection* true)

(def module
  {:id :eca/agent-code
   :description "The code agent, for coding tasks. The default agent."
   :requires #{:eca/agents}
   :config-defaults {:defaultAgent "code"
                     :agent {"code" {:mode "primary"
                                     :prompts {:chat "${classpath:prompts/code_agent.md}"}
                                     :disabledTools ["preview_file_change"]}}}})
