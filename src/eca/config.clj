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
   [eca.logger :as logger]
   [eca.shared :as shared]
   [camel-snake-kebab.core :as csk])
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
   :behavior {"agent" {:systemPromptFile "prompts/agent_behavior.md"}
              "plan" {:systemPromptFile "prompts/plan_behavior.md"}}
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
                         :ask {}}}
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
      (json/parse-string raw-string))
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
                  [[:providers]
                   [:behavior]]
                  :stringfy
                  [[:providers]
                   [:providers :ANY :models]
                   [:behavior]
                   [:behavior :ANY :disabledTools]
                   [:behavior :ANY :toolCall :approval :allow]
                   [:behavior :ANY :toolCall :approval :allow :ANY :argsMatchers]
                   [:behavior :ANY :toolCall :approval :ask]
                   [:behavior :ANY :toolCall :approval :ask :ANY :argsMatchers]
                   [:toolCall :approval :allow]
                   [:toolCall :approval :allow :ANY :argsMatchers]
                   [:toolCall :approval :ask]
                   [:toolCall :approval :ask :ANY :argsMatchers]
                   [:mcpServers]]}
                 (deep-merge initialization-config
                             (when-not pure-config? (config-from-envvar))
                             (when-not pure-config? (config-from-global-file))
                             (when-not pure-config? (config-from-local-file (:workspace-folders db))))))))
