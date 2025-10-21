(ns eca.models
  (:require
   [cheshire.core :as json]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.llm-providers.ollama :as llm-providers.ollama]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.shared :refer [assoc-some] :as shared]))

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
    "openai/gpt-5"
    "openai/gpt-5-mini"
    "openai/gpt-5-nano"
    "anthropic/claude-sonnet-4-5-20250929"
    "anthropic/claude-sonnet-4-20250514"
    "anthropic/claude-opus-4-20250514"
    "anthropic/claude-opus-4-1-20250805"
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
    (and (llm-util/provider-api-url provider config)
         (llm-util/provider-api-key provider (get-in db [:auth provider]) config))))

(defn sync-models! [db* config on-models-updated]
  (let [all-models (all)
        db @db*
        all-supported-models (reduce
                              (fn [p [provider provider-config]]
                                (merge p
                                       (reduce
                                        (fn [m [model model-config]]
                                          (let [real-model-name (or (:modelName model-config) model)
                                                full-real-model (str provider "/" real-model-name)
                                                full-model (str provider "/" model)
                                                model-capabilities (merge
                                                                    (or (get all-models full-real-model)
                                                                           ;; we guess the capabilities from
                                                                           ;; the first model with same name
                                                                        (when-let [found-full-model (first (filter #(= (shared/normalize-model-name real-model-name)
                                                                                                                       (shared/normalize-model-name (second (string/split % #"/" 2))))
                                                                                                                   (keys all-models)))]
                                                                          (get all-models found-full-model))
                                                                        {:tools true
                                                                         :reason? true
                                                                         :web-search false})
                                                                    {:model-name real-model-name})]
                                            (assoc m full-model model-capabilities)))
                                        {}
                                        (:models provider-config))))
                              {}
                              (:providers config))
        authenticated-models (into {}
                                   (filter #(auth-valid? (first %) db config) all-supported-models))
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
        all-models (merge authenticated-models local-models)]
    (swap! db* assoc :models all-models)
    (on-models-updated all-models)))

(comment
  (require '[clojure.pprint :as pprint])
  (pprint/pprint (models-dev))
  (pprint/pprint (all)))
