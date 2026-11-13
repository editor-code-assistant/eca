(ns eca.modules.built-in
  "The built-in modules, compiled into ECA. Each one is a `module` var in the
   namespace of its feature."
  (:require
   [eca.features.core :as f.core]
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
   z-ai/module])
