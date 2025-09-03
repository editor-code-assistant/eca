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
   [eca.messenger :as messenger]
   [eca.shared :as shared])
  (:import
   [java.io File]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CONFIG]")

(def ^:dynamic *env-var-config-error* false)
(def ^:dynamic *global-config-error* false)
(def ^:dynamic *local-config-error* false)

(def ^:private listen-idle-ms 3000)

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
                            :models {"claude-sonnet-4-20250514" {}
                                     "claude-opus-4-1-20250805" {}
                                     "claude-opus-4-20250514" {}
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
   :toolCall {:approval {:byDefault "ask"
                         :allow {}
                         :ask {}
                         :deny {}}}
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

(defn ^:private safe-read-json-string [raw-string config-dyn-var]
  (try
    (alter-var-root config-dyn-var (constantly false))
    (binding [json.factory/*json-factory* (json.factory/make-json-factory
                                           {:allow-comments true})]
      (json/parse-string raw-string))
    (catch Exception e
      (alter-var-root config-dyn-var (constantly true))
      (logger/warn logger-tag "Error parsing config json:" (.getMessage e)))))

(defn ^:private config-from-envvar* []
  (some-> (System/getenv "ECA_CONFIG")
          (safe-read-json-string (var *env-var-config-error*))))

(def ^:private config-from-envvar (memoize config-from-envvar*))

(defn global-config-dir ^File []
  (let [xdg-config-home (or (get-env "XDG_CONFIG_HOME")
                            (io/file (get-property "user.home") ".config"))]
    (io/file xdg-config-home "eca")))

(defn ^:private config-from-global-file* []
  (let [config-file (io/file (global-config-dir) "config.json")]
    (when (.exists config-file)
      (safe-read-json-string (slurp config-file) (var *global-config-error*)))))

(def ^:private config-from-global-file (memoize/ttl config-from-global-file* :ttl/threshold ttl-cache-config-ms))

(defn ^:private config-from-local-file* [roots]
  (reduce
   (fn [final-config {:keys [uri]}]
     (merge
      final-config
      (let [config-file (io/file (shared/uri->filename uri) ".eca" "config.json")]
        (when (.exists config-file)
          (safe-read-json-string (slurp config-file) (var *local-config-error*))))))
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

(defn ^:private normalize-fields
  "Converts a deep nested map where keys are strings to keywords.
   normalization-rules follow the nest order, :ANY means any field name.
    :kebab-case means convert field names to kebab-case.
    :stringfy means convert field names to strings."
  [normalization-rules m]
  (let [kc-paths (set (:kebab-case normalization-rules))
        str-paths (set (:stringfy normalization-rules))
        ; match a current path against a rule path with :ANY wildcard
        matches-path? (fn [rule-path cur-path]
                        (and (= (count rule-path) (count cur-path))
                             (every? true?
                                     (map (fn [rp cp]
                                            (or (= rp :ANY)
                                                (= rp cp)))
                                          rule-path cur-path))))
        applies? (fn [paths cur-path]
                   (some #(matches-path? % cur-path) paths))
        normalize-map (fn normalize-map [cur-path m*]
                        (cond
                          (map? m*)
                          (let [apply-kebab? (applies? kc-paths cur-path)
                                apply-string? (applies? str-paths cur-path)]
                            (into {}
                                  (map (fn [[k v]]
                                         (let [base-name (cond
                                                           (keyword? k) (name k)
                                                           (string? k) k
                                                           :else (str k))
                                               kebabed (if apply-kebab?
                                                         (csk/->kebab-case base-name)
                                                         base-name)
                                               new-k (if apply-string?
                                                       kebabed
                                                       (keyword kebabed))
                                               new-v (normalize-map (conj cur-path new-k) v)]
                                           [new-k new-v])))
                                  m*))

                          (sequential? m*)
                          (mapv #(normalize-map cur-path %) m*)

                          :else m*))]
    (normalize-map [] m)))

(defn all [db]
  (let [initialization-config @initialization-config*
        pure-config? (:pureConfig initialization-config)]
    (deep-merge initial-config
                (normalize-fields
                 {:kebab-case
                  [[:providers]]
                  :stringfy
                  [[:providers]
                   [:providers :ANY :models]
                   [:toolCall :approval :allow]
                   [:toolCall :approval :allow :ANY :argsMatchers]
                   [:toolCall :approval :ask]
                   [:toolCall :approval :ask :ANY :argsMatchers]
                   [:toolCall :approval :deny]
                   [:toolCall :approval :deny :ANY :argsMatchers]
                   [:customTools]
                   [:customTools :ANY :schema :properties]
                   [:mcpServers]]}
                 (deep-merge initialization-config
                             (when-not pure-config? (config-from-envvar))
                             (when-not pure-config? (config-from-global-file))
                             (when-not pure-config? (config-from-local-file (:workspace-folders db))))))))

(defn validation-error []
  (cond
    *env-var-config-error* "ENV"
    *global-config-error* "global"
    *local-config-error* "local"

    ;; all good
    :else nil))

(defn listen-for-changes! [db*]
  (while (not (:stopping @db*))
    (Thread/sleep ^long listen-idle-ms)
    (let [db @db*
          new-config (all db)
          new-config-hash (hash new-config)]
      (when (not= new-config-hash (:config-hash db))
        (swap! db* assoc :config-hash new-config-hash)
        (doseq [config-updated-fns (vals (:config-updated-fns db))]
          (config-updated-fns new-config))))))

(defn diff-keeping-vectors
  "Like (second (clojure.data/diff a b)) but if a value is a vector, keep vector value from b.

  Example1: (diff-keeping-vectors {:a 1 :b 2}  {:a 1 :b 3}) => {:b 3}
  Example2: (diff-keeping-vectors {:a 1 :b [:bar]}  {:b [:bar :foo]}) => {:b [:bar :foo]}"
  [a b]
  (letfn [(diff-maps [a b]
            (let [all-keys (set (concat (keys a) (keys b)))]
              (reduce
               (fn [acc k]
                 (let [a-val (get a k)
                       b-val (get b k)]
                   (cond
                     ;; Key doesn't exist in b, skip
                     (and (contains? a k) (not (contains? b k)))
                     acc

                     ;; Key doesn't exist in a, include from b
                     (and (not (contains? a k)) (contains? b k))
                     (assoc acc k b-val)

                     ;; Both are vectors and they differ, use the entire vector from b
                     (and (vector? a-val) (vector? b-val) (not= a-val b-val))
                     (assoc acc k b-val)

                     ;; Both are maps, recurse
                     (and (map? a-val) (map? b-val))
                     (let [nested-diff (diff-maps a-val b-val)]
                       (if (seq nested-diff)
                         (assoc acc k nested-diff)
                         acc))

                     ;; Values are different, use value from b
                     (not= a-val b-val)
                     (assoc acc k b-val)

                     ;; Values are the same, skip
                     :else
                     acc)))
               {}
               all-keys)))]
    (let [result (diff-maps a b)]
      (when (seq result)
        result))))

(defn notify-fields-changed-only! [config-updated messenger db*]
  (let [config-to-notify (diff-keeping-vectors (:last-config-notified @db*)
                                               config-updated)]
    (when (seq config-to-notify)
      (swap! db* update :last-config-notified shared/deep-merge config-to-notify)
      (messenger/config-updated messenger config-to-notify))))
