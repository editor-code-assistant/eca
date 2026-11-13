(ns eca.modules.built-in
  "The built-in modules, compiled into ECA. Each one is a `module` var in the
   namespace of its feature."
  (:require
   [eca.features.agents :as f.agents]
   [eca.features.commands :as f.commands]
   [eca.features.core :as f.core]
   [eca.features.hooks :as f.hooks]
   [eca.features.index :as f.index]
   [eca.features.otel :as f.otel]
   [eca.features.plugins :as f.plugins]
   [eca.features.remote :as f.remote]
   [eca.features.repl :as f.repl]
   [eca.features.tools :as f.tools]
   [eca.features.tools.agent :as tools.agent]
   [eca.features.tools.ask-user :as tools.ask-user]
   [eca.features.tools.chat :as tools.chat]
   [eca.features.tools.custom :as tools.custom]
   [eca.features.tools.editor :as tools.editor]
   [eca.features.tools.fetch-rule :as tools.fetch-rule]
   [eca.features.tools.filesystem :as tools.filesystem]
   [eca.features.tools.git :as tools.git]
   [eca.features.tools.mcp :as f.mcp]
   [eca.features.tools.shell :as tools.shell]
   [eca.features.tools.skill :as tools.skill]
   [eca.features.tools.task :as tools.task]
   [eca.features.tools.tool-search :as tools.tool-search]
   [eca.llm-providers.anthropic :as anthropic]
   [eca.llm-providers.azure :as azure]
   [eca.llm-providers.bedrock :as bedrock]
   [eca.llm-providers.copilot :as copilot]
   [eca.llm-providers.deepseek :as deepseek]
   [eca.llm-providers.google :as google]
   [eca.llm-providers.litellm :as litellm]
   [eca.llm-providers.lmstudio :as lmstudio]
   [eca.llm-providers.mistral :as mistral]
   [eca.llm-providers.moonshot :as moonshot]
   [eca.llm-providers.ollama :as ollama]
   [eca.llm-providers.openai :as openai]
   [eca.llm-providers.openai-chat :as openai-chat]
   [eca.llm-providers.openrouter :as openrouter]
   [eca.llm-providers.z-ai :as z-ai]))

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
   f.agents/code-module
   f.agents/plan-module
   f.agents/explorer-module
   f.agents/general-module
   ;; skills, rules, tool search
   tools.skill/module
   tools.fetch-rule/module
   tools.tool-search/module
   ;; MCP
   f.mcp/module
   ;; user extensions
   tools.custom/module
   f.commands/custom-commands-module
   f.hooks/module
   f.plugins/module
   ;; chat behavior
   f.tools/approval-module
   tools.chat/module
   f.commands/create-module
   ;; editor features
   f.index/module
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
