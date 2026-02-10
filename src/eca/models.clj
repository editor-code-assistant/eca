(ns eca.models
  (:require
   [clojure.string :as string]
   [eca.client-http :as client]
   [eca.config :as config]
   [eca.llm-providers.ollama :as llm-providers.ollama]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.shared :refer [assoc-some] :as shared]
   [hato.client :as http]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[MODELS]")

(def ^:private models-dev-api-url "https://models.dev/api.json")
(def ^:private models-dev-timeout-ms 5000)

(defn ^:private fetch-models-dev-data []
  (let [{:keys [status body]} (http/get models-dev-api-url
                                        {:throw-exceptions? false
                                         :http-client (client/merge-with-global-http-client {})
                                         :timeout models-dev-timeout-ms
                                         :as :json-string-keys})]
    (if (= 200 status)
      body
      (throw (ex-info (format "models.dev request failed with status %s" status)
                      {:status status})))))

;; clojure.core/memoize does NOT cache thrown exceptions.
;; If fetch throws, the next call will retry the HTTP request.
;; Once it succeeds, the result is cached for the process lifetime.
(def ^:private models-dev-fetch-memoized (memoize fetch-models-dev-data))

(defn ^:private models-dev []
  (try
    (let [data (models-dev-fetch-memoized)]
      (if (map? data)
        data
        (do
          (logger/warn logger-tag " Unexpected models.dev payload shape. Ignoring payload.")
          {})))
    (catch Exception e
      (logger/error logger-tag " Error fetching models from models.dev:" (.getMessage e))
      {})))

(def ^:private one-million 1000000)

(def ^:private models-with-web-search-support
  #{"openai/gpt-4.1"
    "openai/gpt-5.2"
    "openai/gpt-5.1"
    "openai/gpt-5"
    "openai/gpt-5-mini"
    "openai/gpt-5-nano"
    "anthropic/claude-sonnet-4-5"
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

(defn ^:private models-dev-providers-by-url
  "Returns a map of models.dev API base URL -> models.dev provider config."
  [models-dev-data]
  (reduce-kv
   (fn [acc _provider models-dev-provider]
     (let [api-url (shared/normalize-api-url (get models-dev-provider "api"))]
       (if (and (string? api-url)
                (not (string/blank? api-url)))
         (assoc acc api-url models-dev-provider)
         acc)))
   {}
   models-dev-data))

(defn ^:private models-dev-provider-index
  "Builds lookup index for models.dev providers."
  [models-dev-data]
  {:by-id models-dev-data
   :by-url (models-dev-providers-by-url models-dev-data)})

(defn ^:private models-dev-provider-without-api?
  [provider-config]
  (string/blank? (shared/normalize-api-url (get provider-config "api"))))

(defn ^:private resolve-models-dev-provider
  "Resolve models.dev provider config by URL first, then by provider id key
   only when models.dev provider has no API URL."
  [provider provider-api-url {:keys [by-id by-url]}]
  (or (get by-url provider-api-url)
      (when-let [provider-by-id (get by-id provider)]
        (when (models-dev-provider-without-api? provider-by-id)
          provider-by-id))))

(defn ^:private using-models-dev-provider-id-fallback?
  [provider provider-api-url {:keys [by-id by-url]}]
  (and (nil? (get by-url provider-api-url))
       (when-let [provider-by-id (get by-id provider)]
         (models-dev-provider-without-api? provider-by-id))))

(defn ^:private add-models-from-models-dev?
  "Returns true when provider should load model catalog from models.dev.
   Opt-out with fetchModels=false."
  [provider provider-config config models-dev-index]
  (let [provider-api-url (llm-util/provider-api-url provider config)
        fetch-models (:fetchModels provider-config)]
    (boolean
     (and (:api provider-config)
          (not= false fetch-models)
          (resolve-models-dev-provider provider provider-api-url models-dev-index)))))

(defn ^:private deprecated-model?
  [model-config]
  (= "deprecated"
     (some-> (get model-config "status")
             string/lower-case)))

(defn ^:private parse-models-dev-model-entry
  [model-key]
  (when (and (string? model-key)
             (not (string/blank? model-key)))
    [model-key {}]))

(defn ^:private warn-invalid-models-dev-entry! [provider model-key]
  (logger/warn logger-tag
               (format "Provider '%s': Ignoring models.dev model entry '%s' with invalid key/model fields"
                       provider model-key)))

(defn ^:private parse-models-dev-provider-models
  "Builds provider model config map from models.dev payload.
   Uses models.dev model key for selection."
  [provider provider-models]
  (when (map? provider-models)
    (not-empty
     (reduce-kv
      (fn [acc model-key model-config]
        (cond
          (not (map? model-config))
          (do
            (warn-invalid-models-dev-entry! provider model-key)
            acc)

          (deprecated-model? model-config)
          acc

          :else
          (if-let [[model-name parsed-config]
                   (parse-models-dev-model-entry model-key)]
            (assoc acc model-name parsed-config)
            (do
              (warn-invalid-models-dev-entry! provider model-key)
              acc))))
      {}
      provider-models))))

(defn ^:private fetch-models-dev-provider-models
  "Loads models from models.dev for providers with matching API URL.
   Fallbacks to provider id key when URL is unavailable in models.dev.
   Returns a map of {provider-name -> {model-name -> model-config}}."
  [config]
  (let [models-dev-data (models-dev)
        models-dev-index (models-dev-provider-index models-dev-data)]
    (reduce
     (fn [acc [provider provider-config]]
       (if-not (add-models-from-models-dev? provider provider-config config models-dev-index)
         acc
         (let [provider-api-url (llm-util/provider-api-url provider config)
               models-dev-provider (resolve-models-dev-provider
                                    provider provider-api-url models-dev-index)
               provider-models (some->> (get models-dev-provider "models")
                                        (parse-models-dev-provider-models provider))]
           (when (using-models-dev-provider-id-fallback? provider provider-api-url models-dev-index)
             (logger/info logger-tag
                          (format "Provider '%s': Using models.dev provider-id fallback (url '%s' not matched)"
                                  provider provider-api-url)))
           (if provider-models
             (do
               (logger/info logger-tag
                            (format "Provider '%s': Loaded %d models from models.dev"
                                    provider (count provider-models)))
               (assoc acc provider provider-models))
             acc))))
     {}
     (:providers config))))

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
   Static config takes precedence (allows user overrides), while preserving
   dynamic defaults for the same model (for example modelName aliases)."
  [static-models dynamic-models]
  (merge-with merge dynamic-models static-models))

(defn ^:private fetch-provider-model-catalogs
  [config]
  {:models-dev (fetch-models-dev-provider-models config)})

(defn ^:private build-all-supported-models
  [known-models config models-dev-provider-models]
  (reduce
   (fn [p [provider provider-config]]
     (let [static-models (:models provider-config)
           dynamic-models (get models-dev-provider-models provider)
           merged-models (merge-provider-models static-models dynamic-models)]
       (merge p
              (reduce
               (fn [m [model model-config]]
                 (let [[full-model capabilities] (build-model-capabilities
                                                  known-models provider model model-config)]
                   (assoc m full-model capabilities)))
               {}
               merged-models))))
   {}
   (:providers config)))

(defn sync-models! [db* config on-models-updated]
  (let [known-models (all)
        db @db*
        {:keys [models-dev]} (fetch-provider-model-catalogs config)
        all-supported-models (build-all-supported-models
                              known-models
                              config
                              models-dev)
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
