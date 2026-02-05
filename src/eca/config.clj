(ns eca.config
  "Waterfall of ways to get eca config, deep merging from top to bottom:

  1. base: fixed config var `eca.config/initial-config`.
  2. env var: searching for a `ECA_CONFIG` env var which should contains a valid json config.
  3. local config-file: searching from a local `.eca/config.json` file.
  4. `initializatonOptions` sent in `initialize` request.

  When `:config-file` from cli option is passed, it uses that instead of searching default locations."
  (:require
   [babashka.fs :as fs]
   [camel-snake-kebab.core :as csk]
   [cheshire.core :as json]
   [cheshire.factory :as json.factory]
   [clojure.core.memoize :as memoize]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.walk :as walk]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.secrets :as secrets]
   [eca.shared :as shared :refer [multi-str]])
  (:import
   [java.io File]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CONFIG]")

(def ^:dynamic *env-var-config-error* false)
(def ^:dynamic *custom-config-error* false)
(def ^:dynamic *global-config-error* false)
(def ^:dynamic *local-config-error* false)

(def ^:private listen-idle-ms 3000)

(def custom-config-file-path* (atom nil))

(defn get-env [env] (System/getenv env))
(defn get-property [property] (System/getProperty property))

(def ^:private initial-config*
  {:providers {"openai" {:api "openai-responses"
                         :url "${env:OPENAI_API_URL:https://api.openai.com}"
                         :key "${env:OPENAI_API_KEY}"
                         :requiresAuth? true
                         :models {"gpt-5.2" {}
                                  "gpt-5.2-codex" {}
                                  "gpt-5.3-codex" {}
                                  "gpt-5-mini" {}
                                  "gpt-5-nano" {}
                                  "gpt-4.1" {}}}
               "anthropic" {:api "anthropic"
                            :url "${env:ANTHROPIC_API_URL:https://api.anthropic.com}"
                            :key "${env:ANTHROPIC_API_KEY}"
                            :requiresAuth? true
                            :models {"claude-sonnet-4.5" {:modelName "claude-sonnet-4-5-20250929"}
                                     "claude-opus-4.6" {:modelName "claude-opus-4-6"}
                                     "claude-opus-4.5" {:modelName "claude-opus-4-5-20251101"}
                                     "claude-opus-4.1" {:modelName "claude-opus-4-1-20250805"}
                                     "claude-haiku-4.5" {:modelName "claude-haiku-4-5-20251001"}}}
               "github-copilot" {:api "openai-chat"
                                 :url "${env:GITHUB_COPILOT_API_URL:https://api.githubcopilot.com}"
                                 :key nil ;; not supported, requires login auth
                                 :requiresAuth? true
                                 :models {"claude-haiku-4.5" {}
                                          "claude-opus-4.1" {}
                                          "claude-opus-4.5" {}
                                          "claude-opus-4.6" {}
                                          "claude-sonnet-4.5" {}
                                          "gpt-5.2" {}
                                          "gpt-5.1" {}
                                          "gpt-5" {}
                                          "gpt-5-mini" {}
                                          "gpt-4.1" {}
                                          "gpt-4o" {}
                                          "grok-code-fast-1" {}
                                          "gemini-2.5-pro" {}
                                          "gemini-3-pro-preview" {}
                                          "gemini-3-flash-preview" {}}}
               "google" {:api "openai-chat"
                         :url "${env:GOOGLE_API_URL:https://generativelanguage.googleapis.com/v1beta/openai}"
                         :key "${env:GOOGLE_API_KEY}"
                         :requiresAuth? true
                         :models {"gemini-2.0-flash" {}
                                  "gemini-2.5-pro" {}
                                  "gemini-3-pro-preview" {}
                                  "gemini-3-flash-preview" {}}}
               "ollama" {:url "${env:OLLAMA_API_URL:http://localhost:11434}"}}
   :defaultBehavior "agent"
   :behavior {"agent" {:prompts {:chat "${classpath:prompts/agent_behavior.md}"}
                       :disabledTools ["preview_file_change"]}
              "plan" {:prompts {:chat "${classpath:prompts/plan_behavior.md}"}
                      :disabledTools ["edit_file" "write_file" "move_file"]
                      :toolCall {:approval {:allow {"eca__shell_command"
                                                    {:argsMatchers {"command" ["pwd"]}}
                                                    "eca__preview_file_change" {}
                                                    "eca__grep" {}
                                                    "eca__read_file" {}
                                                    "eca__directory_tree" {}}
                                            :deny {"eca__shell_command"
                                                   {:argsMatchers {"command" ["[12&]?>>?\\s*(?!/dev/null($|\\s))\\S+"
                                                                              ".*>.*",
                                                                              ".*\\|\\s*(tee|dd|xargs).*",
                                                                              ".*\\b(sed|awk|perl)\\s+.*-i.*",
                                                                              ".*\\b(rm|mv|cp|touch|mkdir)\\b.*",
                                                                              ".*git\\s+(add|commit|push).*",
                                                                              ".*npm\\s+install.*",
                                                                              ".*-c\\s+[\"'].*open.*[\"']w[\"'].*",
                                                                              ".*bash.*-c.*>.*"]}}}}}}}
   :defaultModel nil
   :prompts {:chat "${classpath:prompts/agent_behavior.md}" ;; default to agent
             :chatTitle "${classpath:prompts/title.md}"
             :compact "${classpath:prompts/compact.md}"
             :init "${classpath:prompts/init.md}"
             :skillCreate "${classpath:prompts/skill_create.md}"
             :completion "${classpath:prompts/inline_completion.md}"
             :rewrite "${classpath:prompts/rewrite.md}"}
   :hooks {}
   :rules []
   :commands []
   :disabledTools []
   :toolCall {:approval {:byDefault "ask"
                         :allow {"eca__compact_chat" {}
                                 "eca__preview_file_change" {}
                                 "eca__read_file" {}
                                 "eca__directory_tree" {}
                                 "eca__grep" {}
                                 "eca__editor_diagnostics" {}
                                 "eca__skill" {}}
                         :ask {}
                         :deny {}}
              :readFile {:maxLines 2000}
              :shellCommand {:summaryMaxLength 25}}
   :mcpTimeoutSeconds 60
   :lspTimeoutSeconds 30
   :mcpServers {}
   :welcomeMessage (multi-str "# Welcome to ECA!"
                              ""
                              "Complete `/` for all commands"
                              ""
                              "- `/login` to authenticate with providers"
                              "- `/init` to create/update AGENTS.md"
                              "- `/doctor` or `/config` to troubleshoot"
                              ""
                              "Add more contexts in `@`"
                              ""
                              "")
   :index {:ignoreFiles [{:type :gitignore}]
           :repoMap {:maxTotalEntries 800
                     :maxEntriesPerDir 50}}
   :completion {:model "openai/gpt-4.1"}
   :netrcFile nil
   :autoCompactPercentage 75
   :env "prod"})

(defn ^:private parse-dynamic-string
  "Given a string and a current working directory, look for patterns replacing its content:
  - `${env:SOME-ENV:default-value}`: Replace with a env falling back to a optional default value
  - `${file:/some/path}`: Replace with a file content checking from cwd if relative
  - `${classpath:path/to/file}`: Replace with a file content found checking classpath
  - `${netrc:api.provider.com}`: Replace with the content from Unix net RC [credential files](https://eca.dev/models/#credential-file-authentication)"
  [s cwd config]
  (some-> s
          (string/replace #"\$\{env:([^:}]+)(?::([^}]*))?\}"
                          (fn [[_match env-var default-value]]
                            (or (get-env env-var) default-value "")))
          (string/replace #"\$\{file:([^}]+)\}"
                          (fn [[_match file-path]]
                            (try
                              (let [file-path (fs/expand-home file-path)]
                                (slurp (str (if (fs/absolute? file-path)
                                              file-path
                                              (if cwd
                                                (fs/path cwd file-path)
                                                (fs/path file-path))))))
                              (catch Exception _
                                (logger/warn logger-tag "File not found when parsing string:" s)
                                ""))))
          (string/replace #"\$\{classpath:([^}]+)\}"
                          (fn [[_match resource-path]]
                            (try
                              (slurp (io/resource resource-path))
                              (catch Exception e
                                (logger/warn logger-tag "Error reading classpath resource:" (.getMessage e))
                                ""))))
          (string/replace #"\$\{netrc:([^}]+)\}"
                          (fn [[_match key-rc]]
                            (try
                              (or (secrets/get-credential key-rc (get config "netrcFile")) "")
                              (catch Exception e
                                (logger/warn logger-tag "Error reading netrc credential:" (.getMessage e))
                                ""))))))

(defn ^:private parse-dynamic-string-values
  "walk through config parsing dynamic string contents if value is a string."
  [config cwd]
  (walk/postwalk
   (fn [x]
     (if (string? x)
       (parse-dynamic-string x cwd config)
       x))
   config))

(defn initial-config []
  (parse-dynamic-string-values initial-config* (io/file ".")))

(def ^:private fallback-behavior "agent")

(defn validate-behavior-name
  "Validates if a behavior exists in config. Returns the behavior if valid,
   or the fallback behavior if not."
  [behavior config]
  (if (contains? (:behavior config) behavior)
    behavior
    (do (logger/warn logger-tag (format "Unknown behavior '%s' specified, falling back to '%s'"
                                        behavior fallback-behavior))
        fallback-behavior)))

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

(defn ^:private config-from-envvar []
  (some-> (System/getenv "ECA_CONFIG")
          (safe-read-json-string (var *env-var-config-error*))
          (parse-dynamic-string-values (io/file "."))))

(defn ^:private config-from-custom* []
  (when-some [path @custom-config-file-path*]
    (let [config-file (io/file path)]
      (when (.exists config-file)
        (some-> (safe-read-json-string (slurp config-file) (var *custom-config-error*))
                (parse-dynamic-string-values (fs/file (fs/parent config-file))))))))

(def ^:private config-from-custom (memoize config-from-custom*))

(defn global-config-dir ^File []
  (let [xdg-config-home (or (get-env "XDG_CONFIG_HOME")
                            (io/file (get-property "user.home") ".config"))]
    (io/file xdg-config-home "eca")))

(defn global-config-file ^File []
  (io/file (global-config-dir) "config.json"))

(defn ^:private config-from-global-file []
  (let [config-file (global-config-file)]
    (when (.exists config-file)
      (some-> (safe-read-json-string (slurp config-file) (var *global-config-error*))
              (parse-dynamic-string-values (global-config-dir))))))

(defn ^:private config-from-local-file [roots]
  (reduce
   (fn [final-config {:keys [uri]}]
     (merge
      final-config
      (let [config-dir (io/file (shared/uri->filename uri) ".eca")
            config-file (io/file config-dir "config.json")]
        (when (.exists config-file)
          (some-> (safe-read-json-string (slurp config-file) (var *local-config-error*))
                  (parse-dynamic-string-values config-dir))))))
   {}
   roots))

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
    :kebab-case-key means convert field names to kebab-case.
    :stringfy-key means convert field names to strings."
  [normalization-rules m]
  (let [kc-paths (set (:kebab-case-key normalization-rules))
        str-paths (set (:stringfy-key normalization-rules))
        keywordize-paths (set (:keywordize-val normalization-rules))
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
                          (let [apply-kebab-key? (applies? kc-paths cur-path)
                                apply-string-key? (applies? str-paths cur-path)
                                apply-keywordize-val? (applies? keywordize-paths cur-path)]
                            (into {}
                                  (map (fn [[k v]]
                                         (let [base-name (cond
                                                           (keyword? k) (name k)
                                                           (string? k) k
                                                           :else (str k))
                                               kebabed (if apply-kebab-key?
                                                         (csk/->kebab-case base-name)
                                                         base-name)
                                               new-k (if apply-string-key?
                                                       kebabed
                                                       (keyword kebabed))
                                               new-v (if apply-keywordize-val?
                                                       (keyword v)
                                                       v)
                                               new-v (normalize-map (conj cur-path new-k) new-v)]
                                           [new-k new-v])))
                                  m*))

                          (sequential? m*)
                          (mapv #(normalize-map cur-path %) m*)

                          :else m*))]
    (normalize-map [] m)))

(def ^:private normalization-rules
  {:kebab-case-key
   [[:providers]]
   :keywordize-val
   [[:providers :ANY :httpClient]
    [:providers :ANY :models :ANY :reasoningHistory]]
   :stringfy-key
   [[:behavior]
    [:providers]
    [:providers :ANY :models]
    [:providers :ANY :models :ANY :extraHeaders]
    [:toolCall :approval :allow]
    [:toolCall :approval :allow :ANY :argsMatchers]
    [:toolCall :approval :ask]
    [:toolCall :approval :ask :ANY :argsMatchers]
    [:toolCall :approval :deny]
    [:toolCall :approval :deny :ANY :argsMatchers]
    [:customTools]
    [:customTools :ANY :schema :properties]
    [:mcpServers]
    [:prompts :tools]
    [:behavior :ANY :prompts :tools]
    [:behavior :ANY :toolCall :approval :allow]
    [:behavior :ANY :toolCall :approval :allow :ANY :argsMatchers]
    [:behavior :ANY :toolCall :approval :ask]
    [:behavior :ANY :toolCall :approval :ask :ANY :argsMatchers]
    [:behavior :ANY :toolCall :approval :deny]
    [:behavior :ANY :toolCall :approval :deny :ANY :argsMatchers]
    [:otlp]]})

(defn ^:private all* [db]
  (let [initialization-config @initialization-config*
        pure-config? (:pureConfig initialization-config)
        merge-config (fn [c1 c2]
                       (deep-merge c1 (normalize-fields normalization-rules c2)))]
    (as-> {} $
      (merge-config $ (initial-config))
      (merge-config $ initialization-config)
      (merge-config $ (when-not pure-config?
                        (config-from-envvar)))
      (if-let [custom-config (config-from-custom)]
        (merge-config $ (when-not pure-config? custom-config))
        (-> $
            (merge-config (when-not pure-config? (config-from-global-file)))
            (merge-config (when-not pure-config? (config-from-local-file (:workspace-folders db)))))))))

(def all (memoize/ttl all* :ttl/threshold ttl-cache-config-ms))

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

(defn update-global-config! [config]
  (let [global-config-file (global-config-file)
        current-config (normalize-fields normalization-rules (config-from-global-file))
        new-config (deep-merge current-config
                               (normalize-fields normalization-rules config))
        new-config-json (json/generate-string new-config {:pretty true})]
    (io/make-parents global-config-file)
    (spit global-config-file new-config-json)))
