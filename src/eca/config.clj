(ns eca.config
  "Waterfall of ways to get eca config, deep merging from top to bottom:

  1. base: fixed config var `eca.config/initial-config`.
  2. env var: searching for a `ECA_CONFIG` env var which should contains a valid json config.
  3. local config-file: searching from a local `.eca/config.json` file.
  4. `initializatonOptions` sent in `initialize` request."
  (:require
   [cheshire.core :as json]
   [cheshire.factory :as json.factory]
   [clojure.core.memoize :as memoize]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def initial-config
  {;; LLM providers authentication configuration
   :openaiApiKey nil

   :anthropicApiKey nil

   ;; Gemini can be authenticated using different methods - setting only one of them is enough
   ;; but will depend on the provider configuration, IAM and other bits outof the scope of this project.
   ;; api key for Gemini API
   :geminiApiKey nil

   ;; Vertex AI with API key:
   :googleApiKey nil

   ;; Google Cloud ADC + Vertex and requires injection of applicaton credentials
   ;; obtained via `gcloud auth application-default login`
   :googleProjectId nil
   :googleProjectLocation nil
   :googleApplicationCredentials nil

   ;; all other settings
   :rules []
   :nativeTools {:filesystem {:enabled true}
                 :shell {:enabled true
                         :excludeCommands []}}
   :disabledTools []
   :mcpTimeoutSeconds 60
   :mcpServers {}
   :ollama {:host "http://localhost"
            :port 11434
            :useTools true
            :think true}
   :chat {:welcomeMessage "Welcome to ECA!\n\nType '/' for commands\n\n"}
   :customProviders {}
   :index {:ignoreFiles [{:type :gitignore}]}})

(defn get-env [env] (System/getenv env))
(defn get-property [property] (System/getProperty property))

(def ^:private ttl-cache-config-ms 5000)

(defn ^:private safe-read-json-string [raw-string]
  (try
    (binding [json.factory/*json-factory* (json.factory/make-json-factory
                                           {:allow-comments true})]
      (json/parse-string raw-string true))
    (catch Exception _
      nil)))

(defn ^:private config-from-envvar* []
  (some-> (System/getenv "ECA_CONFIG")
          (safe-read-json-string)))

(def ^:private config-from-envvar (memoize config-from-envvar*))

(defn ^:private config-from-global-file* []
  (let [xdg-config-home (or (get-env "XDG_CONFIG_HOME")
                            (io/file (get-property "user.home") ".config"))
        config-file (io/file xdg-config-home "eca" "config.json")]
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

(defn all [db]
  (deep-merge initial-config
              (config-from-envvar)
              (config-from-global-file)
              (config-from-local-file (:workspace-folders db))))
