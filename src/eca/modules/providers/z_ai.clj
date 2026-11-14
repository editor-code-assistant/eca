(ns eca.modules.providers.z-ai
  "The `:eca/z-ai` module. Z.ai's GLM models, through its
   Anthropic-compatible API.")

(set! *warn-on-reflection* true)

(def module
  {:id :eca/z-ai
   :description "Z.ai's GLM models, through its Anthropic-compatible API."
   :requires #{:eca/api-anthropic}
   :providers {"z-ai" {:label "Z-AI"
                       :api "anthropic"
                       :url "https://api.z.ai/api/anthropic"
                       :login {:ask [:api-key :models]}}}})
