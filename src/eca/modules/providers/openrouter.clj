(ns eca.modules.providers.openrouter
  "The `:eca/openrouter` module. Models from many vendors, through
   OpenRouter.")

(set! *warn-on-reflection* true)

(def module
  {:id :eca/openrouter
   :description "Models from many vendors, through OpenRouter."
   :requires #{:eca/api-openai-chat}
   :providers {"openrouter" {:label "OpenRouter"
                             :api "openai-chat"
                             :url "https://openrouter.ai/api/v1"
                             :login {:ask [:api-key :models]}}}})
