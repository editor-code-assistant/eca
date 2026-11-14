(ns eca.modules.providers.mistral
  "The `:eca/mistral` module. Mistral models.")

(set! *warn-on-reflection* true)

(def module
  {:id :eca/mistral
   :description "Mistral models."
   :requires #{:eca/api-openai-chat}
   :providers {"mistral" {:label "Mistral"
                          :api "openai-chat"
                          :url "https://api.mistral.ai/v1"
                          :login {:ask [:api-key :models]}}}})
