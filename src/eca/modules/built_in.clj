(ns eca.modules.built-in
  "The built-in modules, compiled into ECA: each one is the `module` var of
   its namespace under `eca.modules`, named after its id. A provider's
   namespace also holds its API's module, `api-module`. Their order here
   doesn't matter: modules start after the ones they require, then by
   priority and id. ECA's entry point, `eca.main`, hands this list to the
   runtime with `eca.modules/set-built-ins!`."
  (:require
   [eca.modules.agents :as m.agents]
   [eca.modules.agents.agent-code :as m.agent-code]
   [eca.modules.agents.agent-plan :as m.agent-plan]
   [eca.modules.approval :as m.approval]
   [eca.modules.compact-as-summary :as m.compact-as-summary]
   [eca.modules.core :as m.core]
   [eca.modules.custom-commands :as m.custom-commands]
   [eca.modules.hooks :as m.hooks]
   [eca.modules.manage :as m.manage]
   [eca.modules.mcp :as m.mcp]
   [eca.modules.otel :as m.otel]
   [eca.modules.plugins :as m.plugins]
   [eca.modules.providers.anthropic :as m.anthropic]
   [eca.modules.providers.azure :as m.azure]
   [eca.modules.providers.bedrock :as m.bedrock]
   [eca.modules.providers.copilot :as m.copilot]
   [eca.modules.providers.deepseek :as m.deepseek]
   [eca.modules.providers.google :as m.google]
   [eca.modules.providers.litellm :as m.litellm]
   [eca.modules.providers.lmstudio :as m.lmstudio]
   [eca.modules.providers.mistral :as m.mistral]
   [eca.modules.providers.moonshot :as m.moonshot]
   [eca.modules.providers.ollama :as m.ollama]
   [eca.modules.providers.openai :as m.openai]
   [eca.modules.providers.openai-chat :as m.openai-chat]
   [eca.modules.providers.openrouter :as m.openrouter]
   [eca.modules.providers.z-ai :as m.z-ai]
   [eca.modules.remote :as m.remote]
   [eca.modules.repl :as m.repl]
   [eca.modules.repo-map :as m.repo-map]
   [eca.modules.rules :as m.rules]
   [eca.modules.skills :as m.skills]
   [eca.modules.subagents :as m.subagents]
   [eca.modules.subagents.subagent-explorer :as m.subagent-explorer]
   [eca.modules.subagents.subagent-general :as m.subagent-general]
   [eca.modules.tools.ask-user :as m.ask-user]
   [eca.modules.tools.custom-tools :as m.custom-tools]
   [eca.modules.tools.editor :as m.editor]
   [eca.modules.tools.filesystem :as m.filesystem]
   [eca.modules.tools.git :as m.git]
   [eca.modules.tools.shell :as m.shell]
   [eca.modules.tools.task :as m.task]
   [eca.modules.tools.tool-search :as m.tool-search]))

(set! *warn-on-reflection* true)

(def modules
  [m.core/module
   ;; tools
   m.filesystem/module
   m.shell/module
   m.git/module
   m.editor/module
   m.ask-user/module
   m.task/module
   ;; agents
   m.agents/module
   m.subagents/module
   m.agent-code/module
   m.agent-plan/module
   m.subagent-explorer/module
   m.subagent-general/module
   ;; skills, rules, tool search
   m.skills/module
   m.rules/module
   m.tool-search/module
   ;; MCP
   m.mcp/module
   ;; user extensions
   m.custom-tools/module
   m.custom-commands/module
   m.hooks/module
   m.plugins/module
   ;; chat behavior
   m.approval/module
   m.compact-as-summary/module
   m.manage/module
   ;; editor features
   m.repo-map/module
   ;; APIs, each in its provider's namespace
   m.anthropic/api-module
   m.bedrock/api-module
   m.ollama/api-module
   m.openai/api-module
   m.openai-chat/api-module
   ;; providers
   m.anthropic/module
   m.azure/module
   m.bedrock/module
   m.copilot/module
   m.deepseek/module
   m.google/module
   m.litellm/module
   m.lmstudio/module
   m.mistral/module
   m.moonshot/module
   m.ollama/module
   m.openai/module
   m.openrouter/module
   m.z-ai/module
   ;; optional, off by default
   m.remote/module
   m.otel/module
   m.repl/module])
