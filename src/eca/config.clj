(ns eca.config
  "Waterfall of ways to get eca config, deep merging from top to bottom:

  1. base: fixed config var `eca.config/initial-config`.
  2. env var: searching for a `ECA_CONFIG` env var which should contains a valid json config.
  3. local config-file: searching from a local `.eca/config.json` file.
  4. `initializatonOptions` sent in `initialize` request."
  (:require
   [camel-snake-kebab.core :as csk]
   [cheshire.core :as json]
   [cheshire.factory :as json.factory]
   [clojure.core.memoize :as memoize]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.logger :as logger]
   [eca.shared :as shared])
  (:import
   [java.io File]))

(set! *warn-on-reflection* true)

(def initial-config
  {:providers {"openai" {:api "openai-responses"
                         :url "https://api.openai.com"
                         :key nil
                         :keyEnv "OPENAI_API_KEY"
                         :models {"gpt-5" {}
                                  "gpt-5-mini" {}
                                  "gpt-5-nano" {}
                                  "gpt-4.1" {}
                                  "o4-mini" {}
                                  "o3" {}}}
               "anthropic" {:api "anthropic"
                            :url "https://api.anthropic.com"
                            :key nil
                            :keyEnv "ANTHROPIC_API_KEY"
                            :models {"claude-sonnet-4-20250514" {:extraPayload {:thinking {:type "enabled" :budget_tokens 2048}}}
                                     "claude-opus-4-1-20250805" {:extraPayload {:thinking {:type "enabled" :budget_tokens 2048}}}
                                     "claude-opus-4-20250514" {:extraPayload {:thinking {:type "enabled" :budget_tokens 2048}}}
                                     "claude-3-5-haiku-20241022" {}}}
               "github-copilot" {:api "openai-chat"
                                 :url "https://api.githubcopilot.com"
                                 :key nil ;; not supported, requires login auth
                                 :keyEnv nil ;; not supported, requires login auth
                                 :models {"gpt-5" {}
                                          "gpt-5-mini" {}
                                          "gpt-4.1" {}
                                          "gemini-2.5-pro" {}
                                          "claude-sonnet-4" {}}}
               "ollama" {:url "http://localhost:11434"
                         :urlEnv "OLLAMA_API_URL"}}
   :defaultModel nil
   :rules []
   :commands []
   :nativeTools {:filesystem {:enabled true}
                 :shell {:enabled true
                         :excludeCommands []}
                 :editor {:enabled true}}
   :disabledTools []
   :mcpTimeoutSeconds 60
   :lspTimeoutSeconds 30
   :mcpServers {}
   :defaultBehavior "agent"
   :welcomeMessage "Welcome to ECA!\n\nType '/' for commands\n\n"
   :index {:ignoreFiles [{:type :gitignore}]
           :repoMap {:maxTotalEntries 800
                     :maxEntriesPerDir 50}}})

(defn get-env [env] (System/getenv env))
(defn get-property [property] (System/getProperty property))

(def ^:private ttl-cache-config-ms 5000)

(defn ^:private safe-read-json-string [raw-string]
  (try
    (binding [json.factory/*json-factory* (json.factory/make-json-factory
                                           {:allow-comments true})]
      (json/parse-string raw-string true))
    (catch Exception e
      (logger/warn "Error parsing config json:" (.getMessage e)))))

(defn ^:private config-from-envvar* []
  (some-> (System/getenv "ECA_CONFIG")
          (safe-read-json-string)))

(def ^:private config-from-envvar (memoize config-from-envvar*))

(defn global-config-dir ^File []
  (let [xdg-config-home (or (get-env "XDG_CONFIG_HOME")
                            (io/file (get-property "user.home") ".config"))]
    (io/file xdg-config-home "eca")))

(defn ^:private config-from-global-file* []
  (let [config-file (io/file (global-config-dir) "config.json")]
    (when (.exists config-file)
      (safe-read-json-string (slurp config-file)))))

(def ^:private config-from-global-file (memoize/ttl config-from-global-file* :ttl/threshold ttl-cache-config-ms))

(defn ^:private config-from-local-file* [roots]
  (reduce
   (fn [final-config {:keys [uri]}]
     (merge
      final-config
      (let [config-file (io/file (shared/uri->filename uri) ".eca" "config.json")]
        (when (.exists config-file)
          (safe-read-json-string (slurp config-file))))))
   {}
   roots))

(def ^:private config-from-local-file (memoize/ttl config-from-local-file* :ttl/threshold ttl-cache-config-ms))

(def initialization-config* (atom {}))

(defn ^:private deep-merge [& maps]
  (apply merge-with (fn [& args]
                      (if (every? #(or (map? %) (nil? %)) args)
                        (apply deep-merge args)
                        (last args)))
         maps))

(defn ^:private eca-version* []
  (string/trim (slurp (io/resource "ECA_VERSION"))))

(def eca-version (memoize eca-version*))

(def ollama-model-prefix "ollama/")

(defn ^:private normalize-providers [providers]
  (letfn [(norm-key [k]
            (csk/->kebab-case (string/replace-first (str k) ":" "")))]
    (reduce-kv (fn [m k v]
                 (let [nk (norm-key k)]
                   (if (contains? m nk)
                     (update m nk #(deep-merge % v))
                     (assoc m nk v))))
               {}
               providers)))

(defn ^:private normalize-provider-models [provider]
  (let [models (or (:models provider)
                   (get provider "models"))
        models' (when models
                  (into {}
                        (map (fn [[k v]]
                               [(if (or (keyword? k) (symbol? k))
                                  (string/replace-first (str k) ":" "")
                                  (str k))
                                v])
                             models)))
        provider' (dissoc provider "models")]
    (if models'
      (assoc provider' :models models')
      provider')))

(defn ^:private normalize-fields [config]
  (-> config
      (update-in [:providers]
                 (fn [providers]
                   (when providers
                     (-> (normalize-providers providers)
                         (update-vals normalize-provider-models)))))))

(defn all [db]
  (let [initialization-config @initialization-config*
        pure-config? (:pureConfig initialization-config)]
    (normalize-fields
     (deep-merge initial-config
                 initialization-config
                 (when-not pure-config? (config-from-envvar))
                 (when-not pure-config? (config-from-global-file))
                 (when-not pure-config? (config-from-local-file (:workspace-folders db)))))))
