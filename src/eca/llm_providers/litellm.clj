(ns eca.llm-providers.litellm)

(set! *warn-on-reflection* true)

(def module
  {:id :eca/litellm
   :description "A LiteLLM proxy, at the user's URL."
   :requires #{:eca/api-openai-responses}
   :providers {"litellm" {:label "LiteLLM"
                          :api "openai-responses"
                          :login {:ask [:api-key
                                        [:url "API URL (e.g. https://litellm.my-company.com)"]
                                        :models]}}}})
