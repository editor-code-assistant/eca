(ns eca.config
  "Waterfall of ways to get eca config, deep merging from top to bottom:

  1. base: fixed config var `eca.config/initial-config`.
  2. env var: searching for a `ECA_CONFIG` env var which should contains a valid json config.
  3. local config-file: searching from a local `.eca/config.json` file.
  4. `initializatonOptions` sent in `initialize` request."
  (:require
   [camel-snake-kebab.core :as csk]
   [cheshire.core :as json]
   [clojure.core.memoize :as memoize]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.shared :as shared]))

(def ^:private initial-config
  {:openai-api-key nil
   :anthropic-api-key nil
   :google-gemini-api-key nil
   :rules []
   :mcp-servers []
   :ollama {:host "http://localhost"
            :port 11434}
   :chat {:welcome-message "Welcome to ECA! What you have in mind?\n\n"}
   :index {:ignore-files [{:type :gitignore}]}})

(def ttl-cache-config-ms 5000)

(defn ^:private safe-read-json-string [raw-string]
  (try
    (json/parse-string raw-string (fn [key]
                                    (csk/->kebab-case (keyword key))))
    (catch Exception _
      nil)))

(defn ^:private config-from-envvar* []
  (some-> (System/getenv "ECA_CONFIG")
          (safe-read-json-string)))

(def ^:private config-from-envvar (memoize config-from-envvar*))

(defn ^:private config-from-file* [roots]
  (reduce
   (fn [final-config {:keys [uri]}]
     (merge
      final-config
      (let [config-file (io/file (shared/uri->filename uri) ".eca" "config.json")]
        (when (.exists config-file)
          (safe-read-json-string (slurp config-file))))))
   {}
   roots))

(def ^:private config-from-local-file (memoize/ttl config-from-file* :ttl/threshold ttl-cache-config-ms))

(defn ^:private deep-merge [& maps]
  (apply merge-with (fn [& args]
                      (if (every? #(or (map? %) (nil? %)) args)
                        (apply deep-merge args)
                        (last args)))
         maps))

(defn ^:private eca-version* []
  (string/trim (slurp (io/resource "ECA_VERSION"))))

(def eca-version (memoize eca-version*))

(def ollama-model-prefix "ollama:")

(defn all [db]
  (deep-merge initial-config
              (config-from-envvar)
              (config-from-local-file (:workspace-folders db))))
