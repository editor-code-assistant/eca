(ns eca.llm-providers.deepseek)

(set! *warn-on-reflection* true)

(def module
  {:id :eca/deepseek
   :description "DeepSeek models."
   :requires #{:eca/api-openai-chat}
   :providers {"deepseek" {:label "DeepSeek"
                           :api "openai-chat"
                           :url "https://api.deepseek.com"
                           :login {:ask [:api-key :models]}}}})
