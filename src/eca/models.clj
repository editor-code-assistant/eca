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

(def ^:private max-error-body-log-length 500)

(defn ^:private truncate-for-log
  "Coerce `v` to a string and truncate it so error logs stay readable. Hato with
   `:coerce :unexceptional` returns the raw body string on non-2xx responses."
  [v]
  (let [s (cond
            (nil? v) ""
            (string? v) v
            :else (pr-str v))]
    (if (> (count s) max-error-body-log-length)
      (str (subs s 0 max-error-body-log-length) "...(truncated)")
      s)))

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
  [provider auth-type api-type api-key extra-headers]
  (let [oauth? (= :auth/oauth auth-type)
        anthropic? (= "anthropic" api-type)]
    (client/merge-llm-headers
     (merge
      (assoc-some
       (cond-> {"Content-Type" "application/json"}
         (= "github-copilot" provider) (merge (llm-util/copilot-ide-headers)))
       "anthropic-version" (when anthropic? "2023-06-01")
       "x-api-key" (when (and api-key anthropic? (not oauth?)) api-key)
       "Authorization" (when (and api-key (or oauth? (not anthropic?))) (str "Bearer " api-key))
       "anthropic-beta" (when (and anthropic? oauth?) "oauth-2025-04-20"))
      extra-headers))))

(defn ^:private fetch-models-dev-data []
  (let [{:keys [status body]} (http/get models-dev-api-url
                                        {:throw-exceptions? false
                                         :http-client (client/merge-with-global-http-client {})
                                         :timeout models-dev-timeout-ms
                                         :coerce :unexceptional
                                         :as :json-string-keys})]
    (if (= 200 status)
      body
      (throw (ex-info (format "models.dev request failed with status %s: %s"
                              status (truncate-for-log body))
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

(defn ^:private sane-output-limit
  "Return `output` unless it is >= `context`. Catalogs sometimes report a
   model's max output tokens as a copy of its context window (e.g. models.dev
   `openrouter/x-ai/grok-4.5` with 500k/500k); requesting that many output
   tokens can never succeed since providers validate
   input + max-output <= context, so treat such output limits as unknown."
  [context output]
  (when-not (and context output (>= output context))
    output))

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
    "openai/gpt-5.6-luna"
    "openai/gpt-5.6-terra"
    "openai/gpt-5.6-sol"
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
    "openai/gpt-5.6-luna"
    "openai/gpt-5.6-terra"
    "openai/gpt-5.6-sol"
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
    "anthropic/claude-opus-4.8"
    "anthropic/claude-opus-4-8"
    "anthropic/claude-sonnet-5"
    "anthropic/claude-fable-5"
    "anthropic/claude-mythos-5"
    "anthropic/claude-haiku-4-5-20251001"})

(def ^:private models-with-mid-conversation-system-support
  "Models that accept `role: \"system\"` entries inside the messages array
   (mid-conversation system messages), letting volatile instructions be sent
   after a user turn without invalidating the cached history prefix.
   Documented for Claude Opus 4.8."
  #{"anthropic/claude-opus-4.8"
    "anthropic/claude-opus-4-8"})

(defn ^:private all
  "Return all known existing models with their capabilities and configs."
  [models-dev-data]
  (reduce
   (fn [m [provider provider-config]]
     (merge m
            (reduce
             (fn [p [model model-config]]
               (let [context (pos-num (get-in model-config ["limit" "context"]))
                     output (sane-output-limit context (pos-num (get-in model-config ["limit" "output"])))]
                 (assoc p (str provider "/" model)
                        (assoc-some
                         {:reason? (get model-config "reasoning")
                          :image-input? (contains? (set (get-in model-config ["modalities" "input"])) "image")
                          ;; TODO how to check for web-search mode dynamically,
                          ;; maybe fixed after web-search toolcall is implemented
                          :web-search (contains? models-with-web-search-support (str provider "/" model))
                          :mid-conversation-system? (contains? models-with-mid-conversation-system-support (str provider "/" model))
                          :image-generation? (contains? models-with-image-generation-support (str provider "/" model))
                          :tools (get model-config "tool_call")
                          :max-output-tokens output}
                         :limit {:context context
                                 :output output}
                         :input-token-cost (some-> (get-in model-config ["cost" "input"]) float (/ one-million))
                         :output-token-cost (some-> (get-in model-config ["cost" "output"]) float (/ one-million))
                         :input-cache-creation-token-cost (some-> (get-in model-config ["cost" "cache_write"]) float (/ one-million))
                         :input-cache-read-token-cost (some-> (get-in model-config ["cost" "cache_read"]) float (/ one-million))))))
             {}
             (get provider-config "models"))))
   {}
   models-dev-data))

(defn ^:private provider-configured?
  "True when the user has enough configuration to reach `provider`: either it
   doesn't require auth (e.g. local providers) or both an API URL and API key
   resolve (from config, login auth, or env vars). Used to avoid firing
   model-fetch requests for providers the user never set up."
  [provider provider-config db config]
  (or (not (get provider-config :requiresAuth? false))
      (boolean
       (and (llm-util/provider-api-url provider config)
            (llm-util/provider-api-key provider (get-in db [:auth provider]) config)))))

(defn ^:private auth-valid? [full-model db config]
  (let [[provider _model] (shared/full-model->provider+model full-model)]
    (provider-configured? provider (get-in config [:providers provider]) db config)))

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

(defn ^:private copilot-model-api [supported-endpoints]
  (let [endpoints (set supported-endpoints)]
    (cond
      (contains? endpoints "/v1/messages") :anthropic
      (contains? endpoints "/responses") :openai-responses
      (contains? endpoints "/chat/completions") :openai-chat)))

(defn ^:private copilot-reasoning-variants [model-id api supports]
  (let [efforts (some->> (:reasoning_effort supports)
                         (filter string?)
                         seq)
        adaptive? (true? (:adaptive_thinking supports))
        min-budget (:min_thinking_budget supports)
        max-budget (:max_thinking_budget supports)]
    (case api
      :openai-responses
      (when efforts
        (into {}
              (map (fn [effort]
                     [effort {:reasoning {:effort effort :summary "auto"}}]))
              efforts))

      :openai-chat
      (when efforts
        (into {}
              (map (fn [effort]
                     [effort {:reasoning_effort effort}]))
              efforts))

      :anthropic
      (let [adaptive-thinking (cond-> {:type "adaptive"}
                                ;; Opus 4.7 defaults adaptive thinking display to "omitted", which
                                ;; produces empty thinking blocks. Request summaries explicitly.
                                (re-find #"(?i)opus[-._]4[-._]7(?:$|[-._])" model-id)
                                (assoc :display "summarized"))]
        (cond
          ;; Adaptive-thinking models (Opus 4.7+/Sonnet 5+) reject thinking.type
          ;; "enabled", so a "default" variant keeps no-variant requests on
          ;; adaptive instead of the enabled fallback in the provider (#528).
          (and efforts adaptive?)
          (into {"default" {:thinking adaptive-thinking}}
                (map (fn [effort]
                       [effort {:thinking adaptive-thinking
                                :output_config {:effort effort}}]))
                efforts)

          adaptive?
          {"default" {:thinking adaptive-thinking}}

          ;; Some Copilot Messages models (for example Claude Opus/Sonnet 4.5) advertise
          ;; budget bounds instead of named reasoning efforts. Project that range into
          ;; the existing variant picker without exposing raw token budgets in the UI.
          (and (number? max-budget) (pos? max-budget))
          (let [min-value (max 1024 (long (if (number? min-budget) min-budget 1024)))
                max-value (dec (long max-budget))
                high-value (max min-value (quot (long max-budget) 2))]
            (when (<= min-value max-value)
              {"high" {:thinking {:type "enabled"
                                  :budget_tokens high-value}}
               "max" {:thinking {:type "enabled"
                                 :budget_tokens max-value}}}))

          :else nil))

      nil)))

(defn ^:private parse-copilot-model-entry [{:keys [id supported_endpoints capabilities]}]
  (when (and (string? id) (not (string/blank? id)))
    (let [supports (:supports capabilities)
          api (copilot-model-api supported_endpoints)
          variants (copilot-reasoning-variants id api supports)
          reason? (or (true? (:adaptive_thinking supports))
                      (seq (:reasoning_effort supports))
                      (number? (:max_thinking_budget supports))
                      (number? (:min_thinking_budget supports)))]
      [id (assoc-some {}
                      :discovered-api api
                      :discovered-reason? (when reason? true)
                      :discovered-variants (not-empty variants))])))

(defn ^:private parse-native-model-entry
  "Parses a generic /models entry. OpenRouter-shaped entries expose
   `context_length` and `top_provider.max_completion_tokens`; keep them as
   discovered limits so provider-reported data wins over models.dev catalogs.
   Entries without that metadata (e.g. plain OpenAI/Anthropic) keep an empty
   config, preserving the previous id-only behavior."
  [{:keys [id context_length top_provider]}]
  (when (and (string? id) (not (string/blank? id)))
    (let [context (pos-num context_length)
          output (sane-output-limit context (pos-num (:max_completion_tokens top_provider)))]
      [id (assoc-some {}
                      :discovered-limit (not-empty
                                         (assoc-some {}
                                                     :context context
                                                     :output output)))])))

(defn ^:private fetch-provider-native-models
  "Fetches models from provider's native /models endpoint.
   Returns a map of model-id -> discovered model config on success, nil on failure."
  [{:keys [api-url auth-type api-key api-type provider extra-headers]}]
  (when-let [models-path (provider-models-endpoint-path api-type)]
    (let [url (shared/join-api-url api-url models-path)
          rid (llm-util/gen-rid)
          headers (models-endpoint-headers provider auth-type api-type api-key extra-headers)]
      (try
        (logger/debug logger-tag (format "[%s] Provider '%s': Fetching models from %s" rid provider url))
        (let [{:keys [status body]} (http/get url
                                              {:headers headers
                                               :throw-exceptions? false
                                               :as :json
                                               :coerce :unexceptional
                                               :http-client (client/merge-with-global-http-client {})
                                               :timeout provider-models-timeout-ms})]
          (if (not= 200 status)
            (logger/warn logger-tag
                         (format "Provider '%s': /models endpoint returned status %s: %s"
                                 provider status (truncate-for-log body)))

            (let [models-data (:data body)]
              (if (not (sequential? models-data))
                (logger/warn logger-tag
                             (format "Provider '%s': /models payload missing sequential :data (status %s, keys %s)"
                                     provider status (if (map? body) (-> body keys sort vec) :non-map-body)))
                (do
                  (logger/debug logger-tag
                                (format "[%s] Provider '%s': Received %d models from %s"
                                        rid provider (count models-data) url))
                  (not-empty
                   (if (= "github-copilot" provider)
                     (into {} (keep parse-copilot-model-entry) models-data)
                     (into {} (keep parse-native-model-entry) models-data))))))))
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
      ;; Provider+auth specific source first (e.g. OpenAI OAuth -> ChatGPT Codex
      ;; /models, registered via `llm-util/provider-models-override`), then the
      ;; generic native /models endpoint.
      (when-let [models (or (llm-util/provider-models-override
                             {:provider provider
                              :auth-type auth-type
                              :api-key api-key
                              :static-models (:models provider-config)})
                            (when api-url
                              (fetch-provider-native-models
                               {:provider provider
                                :api-url api-url
                                :auth-type auth-type
                                :api-key api-key
                                :api-type api-type
                                :extra-headers (:extraHeaders provider-config)})))]
        (logger/debug logger-tag
                      (format "Provider '%s': Discovered %d models"
                              provider (count models)))
        models))))

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
        (logger/debug logger-tag
                      (format "Provider '%s': Using models.dev provider-id fallback (url '%s' not matched)"
                              provider provider-api-url)))
      (when provider-models
        (logger/debug logger-tag
                      (format "Provider '%s': Loaded %d models from models.dev"
                              provider (count provider-models))))
      provider-models)))

(defn ^:private cost-per-1m->per-token
  "models.dev and user config express token costs per 1M tokens; internally we
   store cost per single token."
  [n]
  (some-> n float (/ one-million)))

(defn ^:private config-overrides->capabilities
  "Translate per-model config and discovered provider metadata into internal
   capability keys. User limits, costs, and image support override catalog
   values; provider discovery contributes API routing, reasoning variants, and
   token limits (user config > provider-discovered > models.dev catalog)."
  [model-config]
  (let [limit (:limit model-config)
        cost (:cost model-config)
        discovered-limit (:discovered-limit model-config)
        context-override (or (pos-num (:context limit)) (:context discovered-limit))
        output-override (or (pos-num (:output limit)) (:output discovered-limit))
        limit-overrides (assoc-some {}
                                    :context context-override
                                    :output output-override)]
    (assoc-some {}
                :reason? (:discovered-reason? model-config)
                :api (:discovered-api model-config)
                :variants (:discovered-variants model-config)
                :image-input? (:imageInput model-config)
                :limit (not-empty limit-overrides)
                :max-output-tokens output-override
                :input-token-cost (cost-per-1m->per-token (:input cost))
                :output-token-cost (cost-per-1m->per-token (:output cost))
                :input-cache-creation-token-cost (cost-per-1m->per-token (:cacheWrite cost))
                :input-cache-read-token-cost (cost-per-1m->per-token (:cacheRead cost)))))

(defn ^:private merge-capabilities
  "Deep-merges capability maps so overriding only part of `:limit` (e.g. just
   `:context`) keeps the other sub-keys; scalars are overwritten."
  [base overrides]
  (merge-with (fn [a b]
                (if (and (map? a) (map? b))
                  (merge a b)
                  b))
              base
              overrides))

(defn ^:private build-model-capabilities
  "Build capabilities for a single model, looking up from known models database."
  [all-models provider model model-config]
  (let [real-model-name (or (:modelName model-config) model)
        full-real-model (str provider "/" real-model-name)
        full-model (str provider "/" model)
        base-capabilities (or (get all-models full-real-model)
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
                               :mid-conversation-system? false
                               :image-generation? false
                               :image-input? false})
        model-capabilities (-> (merge-capabilities base-capabilities
                                                   (config-overrides->capabilities model-config))
                               (assoc :model-name real-model-name))]
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
                                 (if (provider-configured? provider provider-config db config)
                                   [provider
                                    (future
                                      (or (fetch-provider-native-models-with-fallback
                                           provider provider-config config db)
                                          (fetch-single-provider-models-dev
                                           provider provider-config config models-dev-index)))]
                                   (do
                                     (logger/debug logger-tag
                                                   (format "Provider '%s': Skipping model fetch (not configured)"
                                                           provider))
                                     nil)))))
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
                 (let [real-model-name (:modelName model-config)
                       [real-provider real-model] (when (and (string? real-model-name)
                                                             (string/includes? real-model-name "/"))
                                                    (shared/full-model->provider+model real-model-name))
                       discovered-config (when real-model-name
                                           (or (get dynamic-models real-model-name)
                                               (when (= provider real-provider)
                                                 (get dynamic-models real-model))))
                       model-config (merge discovered-config model-config)
                       [full-model capabilities] (build-model-capabilities
                                                  known-models provider model model-config)]
                   (assoc m full-model capabilities)))
               {}
               merged-models))))
   {}
   (:providers config)))

(defn full-model-for
  "Resolve `model-id` to a full \"provider/model\" key present in `db`'s `:models`.
   Checks a provider-local alias first (\"<provider>/<model-id>\", when `model-id`
   has no provider prefix), then the literal `model-id`. Returns nil when neither
   matches a known model."
  [db provider model-id]
  (when model-id
    (let [models (:models db)
          alias-model (when (and provider (not (string/includes? model-id "/")))
                        (str provider "/" model-id))]
      (cond
        (and alias-model (contains? models alias-model)) alias-model
        (contains? models model-id) model-id))))

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
        ollama-models-config (get-in config [:providers "ollama" :models])
        local-models (reduce
                      (fn [models {:keys [model] :as ollama-model}]
                        (assoc models
                               (str config/ollama-model-prefix model)
                               (merge-capabilities
                                (select-keys ollama-model [:tools :reason?])
                                (config-overrides->capabilities (get ollama-models-config model)))))
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
