(ns eca.models
  (:require
   [cheshire.core :as json]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.llm-providers.ollama :as llm-providers.ollama]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.shared :refer [assoc-some] :as shared]
   [hato.client :as http]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[MODELS]")

(defn ^:private models-dev* []
  (try
    (let [response (slurp "https://models.dev/api.json")
          data (json/parse-string response)]
      data)
    (catch Exception e
      (logger/error logger-tag " Error fetching models from models.dev:" (.getMessage e))
      {})))

(def ^:private models-dev (memoize models-dev*))

(def ^:private one-million 1000000)

(def ^:private models-with-web-search-support
  #{"openai/gpt-4.1"
    "openai/gpt-5.2"
    "openai/gpt-5.1"
    "openai/gpt-5"
    "openai/gpt-5-mini"
    "openai/gpt-5-nano"
    "anthropic/claude-sonnet-4.5"
    "anthropic/claude-opus-4.1"
    "anthropic/claude-opus-4.5"
    "anthropic/claude-opus-4.6"
    "anthropic/claude-haiku-4.5"
    "anthropic/claude-sonnet-4-5-20250929"
    "anthropic/claude-sonnet-4-20250514"
    "anthropic/claude-opus-4-20250514"
    "anthropic/claude-opus-4-1-20250805"
    "anthropic/claude-opus-4-5-20251101"
    "anthropic/claude-opus-4-6"
    "anthropic/claude-haiku-4-5-20251001"})

(defn ^:private all
  "Return all known existing models with their capabilities and configs."
  []
  (reduce
   (fn [m [provider provider-config]]
     (merge m
            (reduce
             (fn [p [model model-config]]
               (assoc p (str provider "/" model)
                      (assoc-some
                       {:reason? (get model-config "reasoning")
                        :image-input? (contains? (set (get-in model-config ["modalities" "input"])) "image")
                        ;; TODO how to check for web-search mode dynamically,
                        ;; maybe fixed after web-search toolcall is implemented
                        :web-search (contains? models-with-web-search-support (str provider "/" model))
                        :tools (get model-config "tool_call")
                        :max-output-tokens (get-in model-config ["limit" "output"])}
                       :limit {:context (get-in model-config ["limit" "context"])
                               :output (get-in model-config ["limit" "output"])}
                       :input-token-cost (some-> (get-in model-config ["cost" "input"]) float (/ one-million))
                       :output-token-cost (some-> (get-in model-config ["cost" "output"]) float (/ one-million))
                       :input-cache-creation-token-cost (some-> (get-in model-config ["cost" "cache_write"]) float (/ one-million))
                       :input-cache-read-token-cost (some-> (get-in model-config ["cost" "cache_read"]) float (/ one-million)))))
             {}
             (get provider-config "models"))))
   {}
   (models-dev)))

(defn ^:private auth-valid? [full-model db config]
  (let [[provider _model] (string/split full-model #"/" 2)]
    (or (not (get-in config [:providers provider :requiresAuth?] false))
        (and (llm-util/provider-api-url provider config)
             (llm-util/provider-api-key provider (get-in db [:auth provider]) config)))))

(def ^:private models-endpoint-path "/models")

(defn ^:private fetch-compatible-models
  "Fetches models from an /models endpoint (both Anthropic and OpenAI).
   Returns a map of model-id -> {} (empty config, to be enriched later).
   On any error, logs a warning and returns nil."
  [{:keys [api-url api-key provider]}]
  (when api-url
    (let [url (str api-url models-endpoint-path)
          rid (llm-util/gen-rid)
          headers (cond-> {"Content-Type" "application/json"}
                    api-key (assoc "Authorization" (str "Bearer " api-key)))]
      (try
        (llm-util/log-request logger-tag rid url nil headers)
        (let [{:keys [status body]} (http/get url
                                              {:headers headers
                                               :throw-exceptions? false
                                               :as :json
                                               :timeout 10000})]
          (if (= 200 status)
            (do
              (llm-util/log-response logger-tag rid "models" body)
              (let [models-data (:data body)]
                (when (seq models-data)
                  (reduce
                   (fn [acc model]
                     (let [model-id (:id model)]
                       (if model-id
                         (assoc acc model-id {})
                         acc)))
                   {}
                   models-data))))
            (logger/warn logger-tag
                         (format "Provider '%s': /models endpoint returned status %s"
                                 provider status))))
        (catch Exception e
          (logger/warn logger-tag
                       (format "Provider '%s': Failed to fetch models from %s: %s"
                               provider url (ex-message e))))))))

(defn ^:private provider-with-fetch-models?
  "Returns true if provider should fetch models dynamically (fetchModels = true)."
  [provider-config]
  (and (:api provider-config)
       (true? (:fetchModels provider-config))))

(defn ^:private fetch-dynamic-provider-models
  "For providers that support dynamic model discovery,
   attempts to fetch available models from the API.
   Returns a map of {provider-name -> {model-id -> model-config}}."
  [config db]
  (reduce
   (fn [acc [provider provider-config]]
     (if (provider-with-fetch-models? provider-config)
       (let [api-url (llm-util/provider-api-url provider config)
             [_auth-type api-key] (llm-util/provider-api-key provider
                                                             (get-in db [:auth provider])
                                                             config)
             fetched-models (fetch-compatible-models
                             {:api-url api-url
                              :api-key api-key
                              :provider provider})]
         (if (seq fetched-models)
           (do
             (logger/info logger-tag
                          (format "Provider '%s': Discovered %d models from /models endpoint"
                                  provider (count fetched-models)))
             (assoc acc provider fetched-models))
           acc))
       acc))
   {}
   (:providers config)))

(defn ^:private build-model-capabilities
  "Build capabilities for a single model, looking up from known models database."
  [all-models provider model model-config]
  (let [real-model-name (or (:modelName model-config) model)
        full-real-model (str provider "/" real-model-name)
        full-model (str provider "/" model)
        model-capabilities (merge
                            (or (get all-models full-real-model)
                                ;; we guess the capabilities from
                                ;; the first model with same name
                                (when-let [found-full-model
                                           (->> (keys all-models)
                                                (filter #(or (= (shared/normalize-model-name (string/replace-first real-model-name
                                                                                                                   #"(.+/)"
                                                                                                                   ""))
                                                                (shared/normalize-model-name (second (string/split % #"/" 2))))
                                                             (= (shared/normalize-model-name real-model-name)
                                                                (shared/normalize-model-name (second (string/split % #"/" 2))))))
                                                first)]
                                  (get all-models found-full-model))
                                {:tools true
                                 :reason? true
                                 :web-search false})
                            {:model-name real-model-name})]
    [full-model model-capabilities]))

(defn ^:private merge-provider-models
  "Merges static config models with dynamically fetched models.
   Static config takes precedence (allows user overrides)."
  [static-models dynamic-models]
  (merge dynamic-models static-models))

(defn sync-models! [db* config on-models-updated]
  (let [all-models (all)
        db @db*
        ;; Fetch dynamic models for providers that support it
        dynamic-provider-models (fetch-dynamic-provider-models config db)
        ;; Build all supported models from config + dynamic sources
        all-supported-models (reduce
                              (fn [p [provider provider-config]]
                                (let [static-models (:models provider-config)
                                      dynamic-models (get dynamic-provider-models provider)
                                      merged-models (merge-provider-models static-models dynamic-models)]
                                  (merge p
                                         (reduce
                                          (fn [m [model model-config]]
                                            (let [[full-model capabilities] (build-model-capabilities
                                                                             all-models provider model model-config)]
                                              (assoc m full-model capabilities)))
                                          {}
                                          merged-models))))
                              {}
                              (:providers config))
        ollama-api-url (llm-util/provider-api-url "ollama" config)
        ollama-models (mapv
                       (fn [{:keys [model] :as ollama-model}]
                         (let [capabilities (llm-providers.ollama/model-capabilities {:api-url ollama-api-url :model model})]
                           (assoc ollama-model
                                  :tools (boolean (some #(= % "tools") capabilities))
                                  :reason? (boolean (some #(= % "thinking") capabilities)))))
                       (llm-providers.ollama/list-models {:api-url ollama-api-url}))
        local-models (reduce
                      (fn [models {:keys [model] :as ollama-model}]
                        (assoc models
                               (str config/ollama-model-prefix model)
                               (select-keys ollama-model [:tools :reason?])))
                      {}
                      ollama-models)
        authenticated-models (into {}
                                   (filter #(auth-valid? (first %) db config) all-supported-models))
        all-models (merge authenticated-models local-models)]
    (swap! db* assoc :models all-models)
    (on-models-updated all-models)))

(comment
  (require '[clojure.pprint :as pprint])
  (pprint/pprint (models-dev))
  (pprint/pprint (all))
  (require '[eca.db :as db])
  (sync-models! db/db*
                (config/all @db/db*)
                (fn [new-models]
                  (pprint/pprint new-models))))
