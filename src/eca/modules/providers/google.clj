(ns eca.modules.providers.google
  "The `:eca/google` module. Gemini models, through Google's
   OpenAI-compatible API.")

(set! *warn-on-reflection* true)

(defn ^:private request-opts
  "A request's options (the declaration's `:request-opts`): Gemini's thought
   tags, its thoughts when reasoning, and no parallel tool calls."
  [_opts {:keys [model flat-instructions user-messages max-output-tokens reason? supports-image? past-messages
                 tools extra-payload extra-headers reasoning-history api-url api-key cancelled?
                 stream-idle-timeout-seconds]}]
  {:model model
   :instructions flat-instructions
   :user-messages user-messages
   :max-output-tokens max-output-tokens
   :reason? reason?
   :supports-image? supports-image?
   :past-messages past-messages
   :tools tools
   :think-tag-start "<thought>"
   :think-tag-end "</thought>"
   :reasoning-history reasoning-history
   :extra-payload (merge {:parallel_tool_calls false}
                         (when reason?
                           {:extra_body {:google {:thinking_config {:include_thoughts true}}}})
                         extra-payload)
   :extra-headers extra-headers
   :api-url api-url
   :api-key api-key
   :cancelled? cancelled?
   :stream-idle-timeout-seconds stream-idle-timeout-seconds})

(def module
  {:id :eca/google
   :description "Gemini models, through Google's OpenAI-compatible API."
   :requires #{:eca/api-openai-chat}
   ;; the entry is a default, so login only saves the key
   :providers {"google" {:label "Google"
                         :login {:ask [:api-key]}
                         :request-opts #'request-opts
                         :api-fn (constantly :openai-chat)}}
   :config-defaults {:providers {"google" {:api "openai-chat"
                                           :url "${env:GOOGLE_API_URL:https://generativelanguage.googleapis.com/v1beta/openai}"
                                           :key "${env:GOOGLE_API_KEY}"
                                           :requiresAuth? true
                                           :models {"gemini-2.5-pro" {}}}}}})
