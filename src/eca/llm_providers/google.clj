(ns eca.llm-providers.google)

(set! *warn-on-reflection* true)

(def module
  {:id :eca/google
   :description "Gemini models, through Google's OpenAI-compatible API."
   :requires #{:eca/api-openai-chat}
   ;; the entry is a default, so login only saves the key
   :providers {"google" {:label "Google"
                         :login {:ask [:api-key]}}}
   :config-defaults {:providers {"google" {:api "openai-chat"
                                           :url "${env:GOOGLE_API_URL:https://generativelanguage.googleapis.com/v1beta/openai}"
                                           :key "${env:GOOGLE_API_KEY}"
                                           :requiresAuth? true
                                           :models {"gemini-2.5-pro" {}}}}}})
