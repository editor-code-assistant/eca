(ns eca.modules.built-in
  "The built-in modules, compiled into ECA: each one is the `module` var of
   its namespace under `eca.modules`, named after its id. A provider's
   namespace also holds its API's module, `api-module`. Their order here
   doesn't matter: modules start after the ones they require, then by
   priority and id."
  (:require
   [eca.modules.agents :as agents]
   [eca.modules.agents.agent-code :as agent-code]
   [eca.modules.agents.agent-plan :as agent-plan]
   [eca.modules.approval :as approval]
   [eca.modules.compact-as-summary :as compact-as-summary]
   [eca.modules.core :as core]
   [eca.modules.create :as create]
   [eca.modules.custom-commands :as custom-commands]
   [eca.modules.hooks :as hooks]
   [eca.modules.manage :as manage]
   [eca.modules.mcp :as mcp]
   [eca.modules.otel :as otel]
   [eca.modules.plugins :as plugins]
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
   [eca.modules.remote :as remote]
   [eca.modules.repl :as repl]
   [eca.modules.repo-map :as repo-map]
   [eca.modules.rules :as rules]
   [eca.modules.skills :as skills]
   [eca.modules.subagents :as subagents]
   [eca.modules.subagents.subagent-explorer :as subagent-explorer]
   [eca.modules.subagents.subagent-general :as subagent-general]
   [eca.modules.tools.ask-user :as ask-user]
   [eca.modules.tools.custom-tools :as custom-tools]
   [eca.modules.tools.editor :as editor]
   [eca.modules.tools.filesystem :as filesystem]
   [eca.modules.tools.git :as git]
   [eca.modules.tools.shell :as shell]
   [eca.modules.tools.task :as task]
   [eca.modules.tools.tool-search :as tool-search]))

(set! *warn-on-reflection* true)

(def modules
  [core/module
   ;; tools
   filesystem/module
   shell/module
   git/module
   editor/module
   ask-user/module
   task/module
   ;; agents
   agents/module
   subagents/module
   agent-code/module
   agent-plan/module
   subagent-explorer/module
   subagent-general/module
   ;; skills, rules, tool search
   skills/module
   manage/module
   rules/module
   tool-search/module
   ;; MCP
   mcp/module
   ;; user extensions
   custom-tools/module
   custom-commands/module
   hooks/module
   plugins/module
   ;; chat behavior
   approval/module
   compact-as-summary/module
   create/module
   ;; editor features
   repo-map/module
   ;; APIs, each in its provider's namespace
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
   remote/module
   otel/module
   repl/module])
