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
(def ^:private provider-models-timeout-ms 10000)

;; Provider API types that support native /models endpoint fetching
(def ^:private native-models-endpoint-providers
  #{"anthropic" "openai" "openai-chat" "openai-responses"})

(defn ^:private provider-models-endpoint-path
  "Returns the appropriate /models endpoint path for a given provider API type."
  [api-type]
  (case api-type
    "anthropic" "/v1/models"
    ("openai" "openai-responses") "/v1/models"
    "openai-chat" "/models"
    nil))

(defn ^:private models-endpoint-headers
  [auth-type api-type api-key]
  (let [oauth? (= :auth/oauth auth-type)
        anthropic? (= "anthropic" api-type)]
    (client/merge-llm-headers
     (assoc-some
      {"Content-Type" "application/json"}
      "x-api-key" (when (and api-key anthropic? (not oauth?)) api-key)
      "Authorization" (when (and api-key (or oauth? (not anthropic?))) (str "Bearer " api-key))))))

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

(defn ^:private models-dev []
  (try
    (let [data (fetch-models-dev-data)]
      (if (map? data)
        data
        (do
          (logger/warn logger-tag " Unexpected models.dev payload shape. Ignoring payload.")
          {})))
    (catch Exception e
      (logger/error logger-tag " Error fetching models from models.dev:" (.getMessage e))
      {})))

(def ^:private one-million 1000000)

(defn ^:private pos-num
  "Return n when n is a positive number, otherwise nil. Used to drop
   meaningless 0/non-positive limits coming from upstream catalogs (e.g.
   models.dev returning 0 for image-only models like
   `openai/chatgpt-image-latest`)."
  [n]
  (when (and (number? n) (pos? n)) n))

(def ^:private models-with-image-generation-support
  "Mainline OpenAI chat models that support the built-in `image_generation`
   tool on the Responses API. Sourced from OpenAI's image-generation tool
   guide (explicit list: gpt-4.1, gpt-4.1-mini, gpt-4.1-nano, gpt-4o-mini,
   gpt-5, gpt-5.5, o3) plus the published guide rule: \"gpt-5 and newer
   models should support the image generation tool\". Codex variants are
   deliberately excluded — OpenAI restricts them to function calling,
   structured outputs, streaming, and prompt caching."
  #{"openai/gpt-4.1"
    "openai/gpt-4.1-mini"
    "openai/gpt-4.1-nano"
    "openai/gpt-4o-mini"
    "openai/gpt-5"
    "openai/gpt-5-mini"
    "openai/gpt-5-nano"
    "openai/gpt-5.1"
    "openai/gpt-5.2"
    "openai/gpt-5.4"
    "openai/gpt-5.5"
    "openai/o3"})

(def ^:private models-with-web-search-support
  "Models that support a built-in web-search tool. OpenAI portion sourced
   from the web-search tool guide; explicit denylist names only
   `gpt-4.1-nano` and `gpt-5 with minimal reasoning` (the latter is a
   runtime config, not a model id, so `gpt-5` itself stays in the set).
   Codex variants are deliberately excluded."
  #{"openai/gpt-4.1"
    "openai/gpt-4.1-mini"
    "openai/gpt-4o"
    "openai/gpt-4o-mini"
    "openai/gpt-5"
    "openai/gpt-5-mini"
    "openai/gpt-5-nano"
    "openai/gpt-5.1"
    "openai/gpt-5.2"
    "openai/gpt-5.4"
    "openai/gpt-5.5"
    "openai/o3"
    "openai/o3-mini"
    "openai/o4-mini"
    "anthropic/claude-sonnet-4-5"
    "anthropic/claude-sonnet-4-6"
    "anthropic/claude-opus-4.1"
    "anthropic/claude-opus-4.5"
    "anthropic/claude-opus-4.6"
    "anthropic/claude-opus-4-6"
    "anthropic/claude-haiku-4.5"
    "anthropic/claude-sonnet-4-5-20250929"
    "anthropic/claude-sonnet-4-20250514"
    "anthropic/claude-opus-4-20250514"
    "anthropic/claude-opus-4-1-20250805"
    "anthropic/claude-opus-4-5-20251101"
    "anthropic/claude-opus-4.7"
    "anthropic/claude-opus-4-7"
    "anthropic/claude-haiku-4-5-20251001"})

(defn ^:private all
  "Return all known existing models with their capabilities and configs."
  [models-dev-data]
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
                        :image-generation? (contains? models-with-image-generation-support (str provider "/" model))
                        :tools (get model-config "tool_call")
                        :max-output-tokens (pos-num (get-in model-config ["limit" "output"]))}
                       :limit {:context (pos-num (get-in model-config ["limit" "context"]))
                               :output (pos-num (get-in model-config ["limit" "output"]))}
                       :input-token-cost (some-> (get-in model-config ["cost" "input"]) float (/ one-million))
                       :output-token-cost (some-> (get-in model-config ["cost" "output"]) float (/ one-million))
                       :input-cache-creation-token-cost (some-> (get-in model-config ["cost" "cache_write"]) float (/ one-million))
                       :input-cache-read-token-cost (some-> (get-in model-config ["cost" "cache_read"]) float (/ one-million)))))
             {}
             (get provider-config "models"))))
   {}
   models-dev-data))

(defn ^:private auth-valid? [full-model db config]
  (let [[provider _model] (shared/full-model->provider+model full-model)]
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

(defn ^:private fetch-model-catalog-enabled?
  [provider-config]
  (boolean
   (and (:api provider-config)
        (not= false (:fetchModels provider-config)))))

(defn ^:private add-models-from-models-dev?
  "Returns true when provider should load model catalog from models.dev.
   Opt-out with fetchModels=false."
  [provider provider-config config models-dev-index]
  (let [provider-api-url (llm-util/provider-api-url provider config)]
    (boolean
     (and (fetch-model-catalog-enabled? provider-config)
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

(defn ^:private fetch-provider-native-models
  "Fetches models from provider's native /models endpoint.
   Returns a map of model-id -> {} on success, nil on failure."
  [{:keys [api-url auth-type api-key api-type provider]}]
  (when-let [models-path (provider-models-endpoint-path api-type)]
    (let [url (shared/join-api-url api-url models-path)
          rid (llm-util/gen-rid)
          headers (models-endpoint-headers auth-type api-type api-key)]
      (try
        (logger/debug logger-tag (format "[%s] Provider '%s': Fetching models from %s" rid provider url))
        (let [{:keys [status body]} (http/get url
                                              {:headers headers
                                               :throw-exceptions? false
                                               :as :json
                                               :http-client (client/merge-with-global-http-client {})
                                               :timeout provider-models-timeout-ms})]
          (if (not= 200 status)
            (logger/warn logger-tag
                         (format "Provider '%s': /models endpoint returned status %s"
                                 provider status))

            (let [models-data (:data body)]
              (if (not (sequential? models-data))
                (logger/warn logger-tag
                             (format "Provider '%s': /models payload missing sequential :data (status %s, keys %s)"
                                     provider status (if (map? body) (-> body keys sort vec) :non-map-body)))
                (do
                  (logger/debug logger-tag
                                (format "[%s] Provider '%s': Received %d models from %s"
                                        rid provider (count models-data) url))
                  (zipmap (keep :id models-data) (repeat {})))))))
        (catch Exception e
          (logger/warn logger-tag
                       (format "Provider '%s': Failed to fetch models from %s: %s"
                               provider url e)))))))

(defn ^:private fetch-provider-native-models-with-fallback
  "Tries to fetch models from provider's native endpoint first.
   Returns a map of model-id -> model-config map, or nil."
  [provider provider-config config db]
  (when (contains? native-models-endpoint-providers (:api provider-config))
    (let [api-url (or (get-in db [:auth provider :api-url])
                      (llm-util/provider-api-url provider config))
          [auth-type api-key] (llm-util/provider-api-key provider
                                                         (get-in db [:auth provider])
                                                         config)
          api-type (:api provider-config)]
      (when api-url
        (when-let [models (fetch-provider-native-models
                           {:provider provider
                            :api-url api-url
                            :auth-type auth-type
                            :api-key api-key
                            :api-type api-type})]
          (logger/info logger-tag
                       (format "Provider '%s': Discovered %d models from native /models endpoint"
                               provider (count models)))
          models)))))

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

(defn ^:private fetch-single-provider-models-dev
  "Fetches models from models.dev for a single provider.
   Returns {model-name -> model-config} or nil if not found."
  [provider provider-config config models-dev-index]
  (when (add-models-from-models-dev? provider provider-config config models-dev-index)
    (let [provider-api-url (llm-util/provider-api-url provider config)
          models-dev-provider (resolve-models-dev-provider
                               provider provider-api-url models-dev-index)
          provider-models (some->> (get models-dev-provider "models")
                                   (parse-models-dev-provider-models provider))]
      (when (using-models-dev-provider-id-fallback? provider provider-api-url models-dev-index)
        (logger/info logger-tag
                     (format "Provider '%s': Using models.dev provider-id fallback (url '%s' not matched)"
                             provider provider-api-url)))
      (when provider-models
        (logger/info logger-tag
                     (format "Provider '%s': Loaded %d models from models.dev"
                             provider (count provider-models))))
      provider-models)))

(defn ^:private build-model-capabilities
  "Build capabilities for a single model, looking up from known models database."
  [all-models provider model model-config]
  (let [real-model-name (or (:modelName model-config) model)
        full-real-model (str provider "/" real-model-name)
        full-model (str provider "/" model)
        model-capabilities (merge
                            (or (get all-models full-real-model)
                                ;; when real-model-name already includes a provider prefix
                                ;; (e.g. "anthropic/claude-opus-4-6"), try direct lookup
                                (get all-models real-model-name)
                                ;; we guess the capabilities from
                                ;; the first model with same name
                                (when-let [found-full-model
                                           (->> (keys all-models)
                                                (filter #(or (= (shared/normalize-model-name (string/replace-first real-model-name
                                                                                                                   #"(.+/)"
                                                                                                                   ""))
                                                                (shared/normalize-model-name (second (shared/full-model->provider+model %))))
                                                             (= (shared/normalize-model-name real-model-name)
                                                                (shared/normalize-model-name (second (shared/full-model->provider+model %))))))
                                                first)]
                                  (get all-models found-full-model))
                                {:tools true
                                 :reason? true
                                 :web-search false
                                 :image-generation? false})
                            {:model-name real-model-name})]
    [full-model model-capabilities]))

(defn ^:private merge-provider-models
  "Merges static config models with dynamically fetched models.
   Static config takes precedence (allows user overrides), while preserving
   dynamic defaults for the same model (for example modelName aliases)."
  [static-models dynamic-models]
  (merge-with merge dynamic-models static-models))

(defn ^:private fetch-provider-models-with-priority
  "Fetches models for all providers, trying native endpoint first, then models.dev.
   Returns a map of {provider-name -> {model-name -> model-config}}."
  ([config db]
   (fetch-provider-models-with-priority config db (models-dev)))
  ([config db models-dev-data]
   (let [models-dev-index (models-dev-provider-index models-dev-data)
         start-ns (System/nanoTime)
         futures (into []
                       (keep (fn [[provider provider-config]]
                               (when (fetch-model-catalog-enabled? provider-config)
                                 [provider
                                  (future
                                    (or (fetch-provider-native-models-with-fallback
                                         provider provider-config config db)
                                        (fetch-single-provider-models-dev
                                         provider provider-config config models-dev-index)))])))
                       (:providers config))
         result (reduce
                 (fn [acc [provider f]]
                   (if-let [models @f]
                     (assoc acc provider models)
                     acc))
                 {}
                 futures)
         elapsed-ms (/ (- (System/nanoTime) start-ns) 1e6)]
     (logger/info logger-tag
                  (format "Fetched model catalogs from %d providers in %.1fms"
                          (count result) elapsed-ms))
     result)))

(defn ^:private fetch-provider-model-catalogs
  ([config db]
   (fetch-provider-model-catalogs config db (models-dev)))
  ([config db models-dev-data]
   {:models (fetch-provider-models-with-priority config db models-dev-data)}))

(defn ^:private build-all-supported-models
  [known-models config discovered-provider-models]
  (reduce
   (fn [p [provider provider-config]]
     (let [static-models (:models provider-config)
           dynamic-models (get discovered-provider-models provider)
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
  (let [models-dev-data (models-dev)
        known-models (all models-dev-data)
        db @db*
        {:keys [models]} (fetch-provider-model-catalogs config db models-dev-data)
        all-supported-models (build-all-supported-models
                              known-models
                              config
                              models)
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
  (pprint/pprint (all (models-dev)))
  (require '[eca.db :as db])
  (sync-models! db/db*
                (config/all @db/db*)
                (fn [new-models]
                  (pprint/pprint new-models))))
