(ns eca.modules.built-in
  "The built-in modules, compiled into ECA. Each one is a `module` var in the
   namespace of its feature."
  (:require
   [eca.features.tools.skill :as tools.skill]
   [eca.modules.agents :as f.agents]
   [eca.modules.agents.agent-code :as agent-code]
   [eca.modules.agents.agent-plan :as agent-plan]
   [eca.modules.approval :as approval]
   [eca.modules.compact-as-summary :as tools.chat]
   [eca.modules.core :as f.core]
   [eca.modules.create :as create]
   [eca.modules.custom-commands :as custom-commands]
   [eca.modules.hooks :as f.hooks]
   [eca.modules.mcp :as f.mcp]
   [eca.modules.otel :as f.otel]
   [eca.modules.plugins :as f.plugins]
   [eca.modules.providers.anthropic :as anthropic]
   [eca.modules.providers.azure :as azure]
   [eca.modules.providers.bedrock :as bedrock]
   [eca.modules.providers.copilot :as copilot]
   [eca.modules.providers.deepseek :as deepseek]
   [eca.modules.providers.google :as google]
   [eca.modules.providers.litellm :as litellm]
   [eca.modules.providers.lmstudio :as lmstudio]
   [eca.modules.providers.mistral :as mistral]
   [eca.modules.providers.moonshot :as moonshot]
   [eca.modules.providers.ollama :as ollama]
   [eca.modules.providers.openai :as openai]
   [eca.modules.providers.openai-chat :as openai-chat]
   [eca.modules.providers.openrouter :as openrouter]
   [eca.modules.providers.z-ai :as z-ai]
   [eca.modules.remote :as f.remote]
   [eca.modules.repl :as f.repl]
   [eca.modules.repo-map :as repo-map]
   [eca.modules.rules :as rules]
   [eca.modules.subagents :as tools.agent]
   [eca.modules.subagents.subagent-explorer :as subagent-explorer]
   [eca.modules.subagents.subagent-general :as subagent-general]
   [eca.modules.tools.ask-user :as tools.ask-user]
   [eca.modules.tools.custom-tools :as tools.custom]
   [eca.modules.tools.editor :as tools.editor]
   [eca.modules.tools.filesystem :as tools.filesystem]
   [eca.modules.tools.git :as tools.git]
   [eca.modules.tools.shell :as tools.shell]
   [eca.modules.tools.task :as tools.task]
   [eca.modules.tools.tool-search :as tools.tool-search]))

(set! *warn-on-reflection* true)

(def modules
  [f.core/module
   ;; tools
   tools.filesystem/module
   tools.shell/module
   tools.git/module
   tools.editor/module
   tools.ask-user/module
   tools.task/module
   ;; agents
   f.agents/module
   tools.agent/module
   agent-code/module
   agent-plan/module
   subagent-explorer/module
   subagent-general/module
   ;; skills, rules, tool search
   tools.skill/module
   rules/module
   tools.tool-search/module
   ;; MCP
   f.mcp/module
   ;; user extensions
   tools.custom/module
   custom-commands/module
   f.hooks/module
   f.plugins/module
   ;; chat behavior
   approval/module
   tools.chat/module
   create/module
   ;; editor features
   repo-map/module
   ;; APIs
   anthropic/api-module
   bedrock/api-module
   ollama/api-module
   openai/api-module
   openai-chat/api-module
   ;; providers
   anthropic/module
   azure/module
   bedrock/module
   copilot/module
   deepseek/module
   google/module
   litellm/module
   lmstudio/module
   mistral/module
   moonshot/module
   ollama/module
   openai/module
   openrouter/module
   z-ai/module
   ;; optional, off by default
   f.remote/module
   f.otel/module
   f.repl/module])
