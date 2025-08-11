(ns eca.models
  (:require
   [cheshire.core :as json]
   [eca.logger :as logger]
   [eca.shared :refer [assoc-some]]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[MODELS]")

(defn ^:private models-dev* []
  (try
    (let [response (slurp "https://models.dev/api.json")
          data (json/parse-string response keyword)]
      data)
    (catch Exception e
      (logger/error logger-tag " Error fetching models from models.dev:" (.getMessage e))
      {})))

(def ^:private models-dev (memoize models-dev*))

(def ^:private one-million 1000000)

(def ^:private models-with-web-search-support
  #{"gpt-4.1"
    "gpt-5"
    "gpt-5-mini"
    "gpt-5-nano"
    "claude-sonnet-4-20250514"
    "claude-opus-4-20250514"
    "claude-opus-4-1-20250805"
    "claude-3-5-haiku-20241022"})

(defn all
  "Return all known existing models with their capabilities and configs."
  []
  (reduce
   (fn [m [provider provider-config]]
     (merge m
            (reduce
             (fn [p [model model-config]]
               (assoc p (name model) (assoc-some
                                      {:provider (or (namespace model) (name provider))
                                       :reason? (:reasoning model-config)
                                       ;; TODO how to check for web-search mode dynamically
                                       :web-search (contains? models-with-web-search-support (name model))
                                       :tools (:tool_call model-config)
                                       :max-output-tokens (-> model-config :limit :output)}
                                      :input-token-cost (some-> (:input (:cost model-config)) float (/ one-million))
                                      :output-token-cost (some-> (:output (:cost model-config)) float (/ one-million))
                                      :input-cache-creation-token-cost (some-> (:cache_write (:cost model-config)) float (/ one-million))
                                      :input-cache-read-token-cost (some-> (:cache_read (:cost model-config)) float (/ one-million)))))
             {}
             (:models provider-config))))
   {}
   (models-dev)))
