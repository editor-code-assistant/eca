(ns eca.config
  "Waterfall of ways to get eca config, deep merging from top to bottom:

  1. base: fixed config var `eca.config/initial-config`.
  2. env var: searching for a `ECA_CONFIG` env var which should contains a valid json config.
  3. local config-file: searching from a local `.eca/config.json` file.
  4. `initializatonOptions` sent in `initialize` request."
  (:require
   [babashka.fs :as fs]
   [camel-snake-kebab.core :as csk]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.shared :as shared]))

(def initial-config
  {:openai-api-key nil
   :anthropic-api-key nil
   :google-gemini-api-key nil
   :rules []
   :ollama {:host "http://localhost"
            :port 11434}
   :chat {:welcome-message "Welcome to ECA! What you have in mind?\n\n"}
   :index {:ignore-files [{:type :gitignore}]}})

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

(defn ^:private config-from-file* []
  (let [config-file (io/file ".eca" "config.json")]
    (when (.exists config-file)
      (safe-read-json-string (slurp config-file)))))

(def ^:private config-from-local-file (memoize config-from-file*))

(defn deep-merge [& maps]
  (apply merge-with (fn [& args]
                      (if (every? #(or (map? %) (nil? %)) args)
                        (apply deep-merge args)
                        (last args)))
         maps))

(defn all []
  (deep-merge initial-config
              (config-from-envvar)
              (config-from-local-file)))

(defn ^:private eca-version* []
  (string/trim (slurp (io/resource "ECA_VERSION"))))

(def eca-version (memoize eca-version*))

(def ollama-model-prefix "ollama:")

(defn ^:private file-rules [roots]
  (->> roots
       (mapcat (fn [{:keys [uri]}]
                 (let [rules-dir (fs/file (shared/uri->filename uri) ".eca" "rules")]
                   (when (fs/exists? rules-dir)
                     (fs/list-dir rules-dir)))))
       (map (fn [file]
              {:name (fs/file-name file)
               :path (str (fs/canonicalize file))
               :type :user-file
               :content (slurp (fs/file file))}))))

(defn ^:private config-rules [config roots]
  (->> (get config :rules)
       (map
        (fn [{:keys [path]}]
          (if (fs/absolute? path)
            (when (fs/exists? path)
              {:name (fs/file-name path)
               :path path
               :type :user-config
               :content (slurp path)})
            (keep (fn [{:keys [uri]}]
                    (let [f (fs/file (shared/uri->filename uri) path)]
                      (when (fs/exists? f)
                        {:name (fs/file-name f)
                         :path (str (fs/canonicalize f))
                         :type :user-config
                         :content (slurp f)})))
                  roots))))
       (flatten)
       (remove nil?)))

(defn ^:private system-rules []
  [{:name "ECA System"
    :type :system
    :content (str "You are an expert AI coding tool called ECA (Editor Code Assistant)."
                  "Your behavior is to '<behavior>'."
                  "The chat is markdown mode.")}])

(defn rules [config roots variables]
  (mapv
   (fn [rule]
     (reduce
      (fn [rule [k v]]
        (update rule :content #(string/replace % (str "<" (name k) ">") v)))
      rule
      variables))
   (concat
    (system-rules)
    (config-rules config roots)
    (file-rules roots))))
