(ns eca.llm-api
  (:require
   [clojure.string :as string]
   [eca.config :as config]
   [eca.llm-providers.anthropic :as llm-providers.anthropic]
   [eca.llm-providers.google :as llm-providers.google]
   [eca.llm-providers.ollama :as llm-providers.ollama]
   [eca.llm-providers.openai :as llm-providers.openai]
   [eca.logger :as logger]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[LLM-API]")

(defn extra-models [config]
  (let [ollama-host (:host (:ollama config))
        ollama-port (:port (:ollama config))]
    (mapv
     (fn [{:keys [model] :as ollama-model}]
       (let [capabilities (llm-providers.ollama/model-capabilities {:host ollama-host :port ollama-port :model model})]
         (assoc ollama-model
                :tools (and (get-in config [:ollama :useTools] true)
                            (boolean (some #(= % "tools") capabilities)))
                :reason? (and (get-in config [:ollama :think] true)
                              (boolean (some #(= % "thinking") capabilities))))))
     (llm-providers.ollama/list-models {:host ollama-host :port ollama-port}))))

;; TODO ask LLM for the most relevant parts of the path
(defn refine-file-context [path lines-range]
  (let [content (slurp path)]
    (if lines-range
      (let [lines (string/split-lines content)
            start (dec (:start lines-range))
            end (min (count lines) (:end lines-range))]
        (string/join "\n" (subvec lines start end)))
      content)))

;; NTOE: this is not going to scale ;-)
(defn ^:private anthropic-api-key [config]
  (or (:anthropicApiKey config)
      (config/get-env "ANTHROPIC_API_KEY")))

(defn ^:private anthropic-api-url []
  (or (config/get-env "ANTHROPIC_API_URL")
      llm-providers.anthropic/base-url))

(defn ^:private openai-api-key [config]
  (or (:openaiApiKey config)
      (config/get-env "OPENAI_API_KEY")))

(defn ^:private openai-api-url []
  (or (config/get-env "OPENAI_API_URL")
      llm-providers.openai/base-url))

;; Google Gemini auth
(defn ^:private gemini-api-key [config]
  (or (:geminiApiKey config)
      (config/get-env "GEMINI_API_KEY")))

(defn ^:private google-api-key [config]
  (or (:googleApiKey config)
      (config/get-env "GOOGLE_API_KEY")))

(defn ^:private google-project-id [config]
  (or (:googleProjectId config)
      (config/get-env "GOOGLE_PROJECT_ID")))

(defn ^:private google-project-location [config]
  (or (:googleProjectLocation config)
      (config/get-env "GOOGLE_PROJECT_LOCATION")))

(defn ^:private google-any-auth? [config]
  (or (gemini-api-key config)
      (and (google-project-id config) (google-project-location config) (google-api-key config))
      (and (google-project-id config) (google-project-location config))))

(defn default-model
  "Returns the default LLM model checking this waterfall:
  - Any custom provider with defaultModel set
  - Anthropic api key set
  - Openai api key set
  - Ollama first model if running
  - Anthropic default model."
  [db config]
  (let [[decision model]
        (or
         (when-let [custom-provider-default-model (first (keep (fn [[model config]]
                                                                 (when (and (:custom-provider? config)
                                                                            (:default-model? config))
                                                                   model))
                                                               (:models db)))]
           [:custom-provider-default-model custom-provider-default-model])

         (when (anthropic-api-key config)
           [:api-key-found "claude-sonnet-4-0"])

         (when (openai-api-key config)
           [:api-key-found "gpt-5"])

         (when (google-any-auth? config)
           [:google-auth-found "gemini-2.5-pro"])

         (when-let [ollama-model (first (filter #(string/starts-with? % config/ollama-model-prefix) (keys (:models db))))]
           [:ollama-running ollama-model])

         ;; else:
         [:default "claude-sonnet-4-0"])]
    (logger/info logger-tag (format "Default LLM model '%s' decision '%s'" model decision))
    model))

(defn ^:private tool->llm-tool [tool]
  (assoc (select-keys tool [:name :description :parameters])
         :type "function"))

(defn complete!
  [{:keys [model model-config instructions reason? user-messages config on-first-response-received
           on-message-received on-error on-prepare-tool-call on-tool-called on-reason on-usage-updated
           past-messages tools]}]
  (let [first-response-received* (atom false)
        emit-first-message-fn (fn [& args]
                                (when-not @first-response-received*
                                  (reset! first-response-received* true)
                                  (apply on-first-response-received args)))
        on-message-received-wrapper (fn [& args]
                                      (apply emit-first-message-fn args)
                                      (apply on-message-received args))
        on-reason-wrapper (fn [& args]
                            (apply emit-first-message-fn args)
                            (apply on-reason args))
        on-prepare-tool-call-wrapper (fn [& args]
                                       (apply emit-first-message-fn args)
                                       (apply on-prepare-tool-call args))
        on-error-wrapper (fn [{:keys [exception] :as args}]
                           (when-not (:silent? (ex-data exception))
                             (logger/error args)
                             (on-error args)))
        tools (when (:tools model-config)
                (mapv tool->llm-tool tools))
        web-search (:web-search model-config)
        max-output-tokens (:max-output-tokens model-config)
        reason-tokens (:reason-tokens model-config)
        custom-providers (:customProviders config)
        custom-models (set (mapcat (fn [[k v]]
                                     (map #(str (name k) "/" %) (:models v)))
                                   custom-providers))
        extra-payload (get-in config [:models (keyword model) :extraPayload])
        callbacks {:on-message-received on-message-received-wrapper
                   :on-error on-error-wrapper
                   :on-prepare-tool-call on-prepare-tool-call-wrapper
                   :on-tool-called on-tool-called
                   :on-reason on-reason-wrapper
                   :on-usage-updated on-usage-updated}]
    (cond
      (contains? #{"o4-mini"
                   "o3"
                   "gpt-4.1"
                   "gpt-5"
                   "gpt-5-mini"
                   "gpt-5-nano"} model)
      (llm-providers.openai/completion!
       {:model model
        :instructions instructions
        :user-messages user-messages
        :max-output-tokens max-output-tokens
        :reason-tokens reason-tokens
        :reason? (and reason? (:reason? model-config))
        :past-messages past-messages
        :tools tools
        :web-search web-search
        :extra-payload extra-payload
        :api-url (openai-api-url)
        :api-key (openai-api-key config)}
       callbacks)

      (contains? #{"claude-sonnet-4-0"
                   "claude-opus-4-0"
                   "claude-opus-4-1"
                   "claude-3-5-haiku-latest"} model)
      (llm-providers.anthropic/completion!
       {:model model
        :instructions instructions
        :user-messages user-messages
        :max-output-tokens max-output-tokens
        :reason-tokens reason-tokens
        :reason? (and reason? (:reason? model-config))
        :past-messages past-messages
        :tools tools
        :web-search web-search
        :extra-payload extra-payload
        :api-url (anthropic-api-url)
        :api-key (anthropic-api-key config)}
       callbacks)

      (contains? #{"gemini-2.5-pro"
                   "gemini-2.5-flash"} model)
      (llm-providers.google/completion!
       {:model model
        :instructions instructions
        :user-messages user-messages
        :max-output-tokens max-output-tokens
        :reason-tokens reason-tokens
        :reason? (and reason? (:reason? model-config))
        :past-messages past-messages
        :tools tools
        :web-search web-search
        ;; NOTE: no :api-url here, because Google provider figures it out
        ;;       because it depends on how we're authenticated
        :gemini-api-key (gemini-api-key config)
        :google-api-key (google-api-key config)
        :google-project-id (google-project-id config)
        :google-project-location (google-project-location config)}
       callbacks)

      (string/starts-with? model config/ollama-model-prefix)
      (llm-providers.ollama/completion!
       {:host (-> config :ollama :host)
        :port (-> config :ollama :port)
        :reason? (and reason? (:reason? model-config))
        :model (string/replace-first model config/ollama-model-prefix "")
        :instructions instructions
        :user-messages user-messages
        :past-messages past-messages
        :tools tools
        :extra-payload extra-payload}
       callbacks)

      (contains? custom-models model)
      (let [[provider model] (string/split model #"/")
            provider-config (get custom-providers (keyword provider))
            provider-fn (case (:api provider-config)
                          "openai" llm-providers.openai/completion!
                          "anthropic" llm-providers.anthropic/completion!
                          (on-error-wrapper {:msg (format "Unknown custom model %s for provider %s" (:api provider-config) provider)}))
            url (or (:url provider-config) (config/get-env (:urlEnv provider-config)))
            key (or (:key provider-config) (config/get-env (:keyEnv provider-config)))]
        (provider-fn
         {:model model
          :instructions instructions
          :user-messages user-messages
          :max-output-tokens max-output-tokens
          :reason-tokens reason-tokens
          :reason? (and reason? (:reason? model-config))
          :past-messages past-messages
          :web-search web-search
          :tools tools
          :extra-payload extra-payload
          :api-url url
          :api-key key}
         callbacks))

      :else
      (on-error-wrapper {:msg (str "ECA Unsupported model: " model)}))))
