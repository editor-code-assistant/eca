(ns eca.llm-providers.mistral)

(set! *warn-on-reflection* true)

(def module
  {:id :eca/mistral
   :description "Mistral models."
   :requires #{:eca/api-openai-chat}
   :providers {"mistral" {:label "Mistral"
                          :api "openai-chat"
                          :url "https://api.mistral.ai/v1"
                          :login {:ask [:api-key :models]}}}})
