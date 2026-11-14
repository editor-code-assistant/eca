(ns eca.modules.providers.moonshot)

(set! *warn-on-reflection* true)

(def module
  {:id :eca/moonshot
   :description "Moonshot's Kimi models."
   :requires #{:eca/api-openai-chat}
   :providers {"moonshot" {:label "Moonshot"
                           :api "openai-chat"
                           :url "https://api.kimi.com/coding/v1"
                           :login {:ask [:api-key :models]}}}})
