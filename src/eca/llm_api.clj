(ns eca.llm-api
  (:require
   [clojure.string :as string]
   [eca.config :as config]
   [eca.llm-providers.anthropic :as llm-providers.anthropic]
   [eca.llm-providers.google :as llm-providers.google]
   [eca.llm-providers.ollama :as llm-providers.ollama]
   [eca.llm-providers.openai :as llm-providers.openai]))

(set! *warn-on-reflection* true)

(defn extra-models [config]
  (llm-providers.ollama/list-running-models {:host (:host (:ollama config))
                                             :port (:port (:ollama config))}))

(defn refine-file-context [path]
  ;; TODO ask LLM for the most relevant parts of the path
  (slurp path))

(defn complete! [{:keys [model context user-prompt config on-message-received on-error]}]
  (cond
    (contains? #{"o4-mini" "gpt-4.1"} model)
    (llm-providers.openai/completion!
     {:model model
      :context context
      :user-prompt user-prompt
      :api-key (:openai-api-key config)}
     {:on-message-received on-message-received
      :on-error on-error})

    (contains? #{"claude-sonnet-4-0"
                 "claude-opus-4-0"
                 "claude-3-5-haiku-latest"} model)
    (llm-providers.anthropic/completion!
     {:model model
      :context context
      :user-prompt user-prompt
      :api-key (:anthropic-api-key config)}
     {:on-message-received on-message-received
      :on-error on-error})
    
    (contains? #{"gemini-2.5-pro"
                 "gemini-2.5-flash"
                 "gemini-2.5-flash-lite-preview-06-17"} model)
    (llm-providers.google/completion!
     {:model model
      :context context
      :user-prompt user-prompt
      :api-key (:google-gemini-api-key config)}
     {:on-message-received on-message-received
      :on-error on-error})

    (string/starts-with? model config/ollama-model-prefix)
    (llm-providers.ollama/completion!
     {:host (-> config :ollama :host)
      :port (-> config :ollama :port)
      :model (string/replace-first model config/ollama-model-prefix "")
      :context context
      :user-prompt user-prompt}
     {:on-message-received on-message-received
      :on-error on-error})

    :else
    (on-error {:msg (str "ECA Unsupported model: " model)})))
