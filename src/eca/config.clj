(ns eca.config
  "Waterfall of ways to get eca config, deep merging from top to bottom:

  1. base: fixed config var `eca.config/initial-config`.
  2. env var: searching for a `ECA_CONFIG` env var which should contains a valid json config.
  3. local config-file: searching from a local `.eca/config.json` file.
  4. `initializatonOptions` sent in `initialize` request.

  Finally, any files listed in `:extraConfigs` are deep merged last, overriding all of the above.

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
   [eca.cache :as cache]
   [eca.features.agents :as agents]
   [eca.interpolation :as interpolation]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.shared :as shared :refer [multi-str]]
   [rewrite-json.core :as rj])
  (:import
   [java.io File]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CONFIG]")

(def ^:dynamic *env-var-config-error* false)
(def ^:dynamic *custom-config-error* false)
(def ^:dynamic *global-config-error* false)
(def ^:dynamic *local-config-error* false)
(def ^:dynamic *extra-config-error* false)

(def ^:private listen-idle-ms 3000)

(def custom-config-file-path* (atom nil))

(defn get-env [env] (System/getenv env))
(defn get-property [property] (System/getProperty property))
(defn user-home [] (cache/user-home))

(def ^:private dangerous-commands-regexes
  [".*[12&]?>>?\\s*(?!/dev/null\\b)(?!/tmp/\\S*\\b)(?!&\\d+\\b)(?!>)\\S+.*" ;; output redirection (except /dev/null and /tmp/)
   ".*\\|\\s*(tee|dd|xargs).*",                                                          ;; pipe to tee/dd/xargs
   ".*\\b(sed|awk|perl)\\s+.*-i.*",                                                      ;; in-place editing
   ".*\\b(rm|mv|cp|touch|mkdir)\\b.*",                                                   ;; file mutation commands
   ".*git\\s+(add|commit|push).*",                                                       ;; git write ops
   ".*npm\\s+install.*",                                                                 ;; npm install
   ".*-c\\s+[\"'].*open.*[\"']w[\"'].*",                                                 ;; python open(...,'w')
   ".*bash.*-c.*[12&]?>>?\\s*(?!/dev/null\\b)(?!/tmp/\\S*\\b)(?!&\\d+\\b)(?!>)\\S+.*"])

(def ^:private openai-variants
  {"none" {:reasoning {:effort "none"}}
   "low" {:reasoning {:effort "low" :summary "auto"}}
   "medium" {:reasoning {:effort "medium" :summary "auto"}}
   "high" {:reasoning {:effort "high" :summary "auto"}}
   "xhigh" {:reasoning {:effort "xhigh" :summary "auto"}}})

(def ^:private anthropic-variants
  {"low" {:output_config {:effort "low"} :thinking {:type "adaptive"}}
   "medium" {:output_config {:effort "medium"} :thinking {:type "adaptive"}}
   "high" {:output_config {:effort "high"} :thinking {:type "adaptive"}}
   "max" {:output_config {:effort "max"} :thinking {:type "adaptive"}}})

(def ^:private anthropic-v2-variants
  {"default" {:thinking {:type "adaptive" :display "summarized"}}
   "low" {:output_config {:effort "low"} :thinking {:type "adaptive" :display "summarized"}}
   "medium" {:output_config {:effort "medium"} :thinking {:type "adaptive" :display "summarized"}}
   "high" {:output_config {:effort "high"} :thinking {:type "adaptive" :display "summarized"}}
   "xhigh" {:output_config {:effort "xhigh"} :thinking {:type "adaptive" :display "summarized"}}
   "max" {:output_config {:effort "max"} :thinking {:type "adaptive" :display "summarized"}}})

(def ^:private deepseek-variants
  {"none" {:thinking {:type "disabled"}}
   "high" {:reasoning_effort "high"}
   "max" {:reasoning_effort "max"}})

(def ^:private glm-variants
  {"none" {:reasoning_effort "none"}
   "high" {:reasoning_effort "high"}
   "max" {:reasoning_effort "max"}})

(def ^:private initial-config*
  {:providers {"openai" {:api "openai-responses"
                         :url "${env:OPENAI_API_URL:https://api.openai.com}"
                         :key "${env:OPENAI_API_KEY}"
                         :requiresAuth? true
                         :models {"gpt-4.1" {}
                                  "gpt-5" {}
                                  "gpt-5-mini" {}
                                  "gpt-5.2" {}
                                  "gpt-5.3-codex" {}}}
               "anthropic" {:api "anthropic"
                            :url "${env:ANTHROPIC_API_URL:https://api.anthropic.com}"
                            :key "${env:ANTHROPIC_API_KEY}"
                            :requiresAuth? true
                            :models {"claude-sonnet-4-6" {}
                                     "claude-opus-4-6" {}
                                     "claude-opus-4-7" {}
                                     "claude-opus-4-8" {}
                                     "claude-fable-5" {}
                                     "claude-mythos-5" {}}}
               "github-copilot" {:api "openai-chat"
                                 :url "${env:GITHUB_COPILOT_API_URL:https://api.githubcopilot.com}"
                                 :key nil ;; not supported, requires login auth
                                 :requiresAuth? true
                                 :models {"gpt-5.5" {}}}
               "google" {:api "openai-chat"
                         :url "${env:GOOGLE_API_URL:https://generativelanguage.googleapis.com/v1beta/openai}"
                         :key "${env:GOOGLE_API_KEY}"
                         :requiresAuth? true
                         :models {"gemini-2.5-pro" {}}}
               "ollama" {:url "${env:OLLAMA_API_URL:http://localhost:11434}"}}
   :defaultAgent "code"
   :agent {"code" {:mode "primary"
                   :prompts {:chat "${classpath:prompts/code_agent.md}"}
                   :disabledTools ["preview_file_change"]}
           "plan" {:mode "primary"
                   :prompts {:chat "${classpath:prompts/plan_agent.md}"}
                   :disabledTools ["edit_file" "write_file" "move_file" "git"]
                   :toolCall {:approval {:byDefault "ask"
                                         :allow {"eca__shell_command"
                                                 {:argsMatchers {"command" ["pwd"
                                                                            "git\\s+diff(\\s+.*)?"
                                                                            "git\\s+log(\\s+.*)?"
                                                                            "git\\s+show(\\s+.*)?"
                                                                            "find(\\s+.*)?"
                                                                            "ls(\\s+.*)?"]}}
                                                 "eca__compact_chat" {}
                                                 "eca__preview_file_change" {}
                                                 "eca__read_file" {}
                                                 "eca__directory_tree" {}
                                                 "eca__grep" {}
                                                 "eca__editor_diagnostics" {}
                                                 "eca__skill" {}
                                                 "eca__task" {}
                                                 "eca__fetch_rule" {}
                                                 "eca__spawn_agent" {}}
                                         :deny {"eca__shell_command"
                                                {:argsMatchers {"command" dangerous-commands-regexes}}}}}}
           "explorer" {:mode "subagent"
                       :description "${classpath:prompts/explorer_agent_description.md}"
                       :systemPrompt "${classpath:prompts/explorer_agent.md}"
                       :disabledTools ["edit_file" "write_file" "move_file" "preview_file_change" "git"]
                       :toolCall {:approval {:byDefault "ask"
                                             :allow {"eca__shell_command"
                                                     {:argsMatchers {"command" ["pwd"
                                                                                "git\\s+diff(\\s+.*)?"
                                                                                "git\\s+log(\\s+.*)?"
                                                                                "git\\s+show(\\s+.*)?"
                                                                                "find(\\s+.*)?"
                                                                                "ls(\\s+.*)?"]}}
                                                     "eca__compact_chat" {}
                                                     "eca__read_file" {}
                                                     "eca__directory_tree" {}
                                                     "eca__grep" {}
                                                     "eca__editor_diagnostics" {}
                                                     "eca__skill" {}
                                                     "eca__task" {}
                                                     "eca__fetch_rule" {}}
                                             :deny {"eca__shell_command"
                                                    {:argsMatchers {"command" dangerous-commands-regexes}}}}}}
           "general" {:mode "subagent"
                      :description "${classpath:prompts/general_agent_description.md}"
                      :systemPrompt "${classpath:prompts/code_agent.md}"
                      :disabledTools ["preview_file_change"]}}
   :defaultModel nil
   :prompts {:chat "${classpath:prompts/code_agent.md}" ;; default to code agent
             :chatTitle "${classpath:prompts/title.md}"
             :compact "${classpath:prompts/compact.md}"
             :init "${classpath:prompts/init.md}"
             :skillCreate "${classpath:prompts/skill_create.md}"
             :completion "${classpath:prompts/inline_completion.md}"
             :rewrite "${classpath:prompts/rewrite.md}"}
   :chat {:title true
          :defaultTrust false}
   :rewrite {:fullFileMaxLines 2000}
   :hooks {}
   :rules []
   :commands []
   :skills []
   :extraConfigs []
   :disabledTools []
   :toolCall {:approval {:byDefault "ask"
                         :allow {"eca__compact_chat" {}
                                 "eca__preview_file_change" {}
                                 "eca__read_file" {}
                                 "eca__directory_tree" {}
                                 "eca__grep" {}
                                 "eca__editor_diagnostics" {}
                                 "eca__skill" {}
                                 "eca__task" {}
                                 "eca__ask_user" {}
                                 "eca__fetch_rule" {}
                                 "eca__spawn_agent" {}}
                         :ask {}
                         :deny {}}
              :readFile {:maxLines 2000}
              :shellCommand {:summaryMaxLength 35}
              :outputTruncation {:lines 2000 :sizeKb 50}}
   :variantsByModel {".*sonnet[-._]4[-._]6|opus[-._]4[-._][56]" {:variants anthropic-variants}
                     ".*opus[-._]4[-._][78]|.*fable[-._]5|.*mythos[-._]5" {:variants anthropic-v2-variants}
                     ".*gpt[-._]5(?:[-._](?:2|4|5)(?!\\d)|[-._]3[-._]codex)" {:variants openai-variants
                                                                              :excludeProviders ["github-copilot"]}
                     ".*deepseek[-._]v4[-._]pro" {:variants deepseek-variants
                                                  :api "openai-chat"}
                     "(?i).*glm[-._]5[-._]2" {:variants glm-variants}}
   :mcpTimeoutSeconds 60
   :mcpKeepAliveSeconds 30
   :lspTimeoutSeconds 30
   :streamIdleTimeoutSeconds 120
   :mcpServers {}
   :welcomeMessage (multi-str "# Welcome to ECA!"
                              ""
                              "Type `/` to see all commands"
                              ""
                              "- `/login` to authenticate with providers"
                              "- `/init` to create/update AGENTS.md"
                              "- `/doctor` or `/config` to troubleshoot"
                              "- `/remote` for remote connection details"
                              ""
                              "__User context__ (preserved through history):"
                              "- Complete `#` to expand to a resource path (let LLM find it)"
                              "- Complete `@` to insert that resource's content (pass content to LLM)."
                              ""
                              "__System context__ (available only during the prompt being sent):"
                              "- Add it to the `@` area above the user prompt"
                              ""
                              "Toggle **trust mode** to auto-accept all tool calls"
                              "")
   :index {:ignoreFiles [{:type :gitignore}]
           :repoMap {:maxTotalEntries 800
                     :maxEntriesPerDir 50}}
   :completion {:model "openai/gpt-4.1"}
   :netrcFile nil
   :autoCompactPercentage 75
   :includeParentAgentsFiles false
   :plugins {"eca" {:source "https://github.com/editor-code-assistant/eca-plugins.git"}}
   :remote {:enabled false}
   :env "prod"})

(defn ^:private parse-dynamic-string-values
  "walk through config parsing dynamic string contents if value is a string."
  [config cwd]
  (walk/postwalk
   (fn [x]
     (if (string? x)
       (interpolation/replace-dynamic-strings x cwd config)
       x))
   config))

(defn initial-config []
  (parse-dynamic-string-values initial-config* (io/file ".")))

(defn ^:private regex-matches? [pattern-str s]
  (try
    (some? (re-find (re-pattern pattern-str) s))
    (catch Exception e
      (logger/warn logger-tag "Invalid regex pattern in variantsByModel:" pattern-str (.getMessage e))
      false)))

(defn effective-model-variants
  "Returns effective variants for a model by merging built-in variants (from
   :variantsByModel regex matching on the model key) with user-defined variants.
   User-defined variants override built-in ones on name clash.
   A variant set to {} is removed from the result, allowing users to disable
   built-in variants."
  [config provider model-name user-variants]
  (let [provider-api (get-in config [:providers provider :api])
        api-match? (fn [api config-val]
                     (cond (sequential? config-val) (some #{api} config-val)
                           config-val (= api config-val)
                           :else true))
        builtin (when model-name
                  (some (fn [[pattern-str {:keys [variants excludeProviders api]}]]
                          (when (and (regex-matches? pattern-str model-name)
                                     (not (some #{provider} excludeProviders))
                                     (api-match? provider-api api))
                            variants))
                        (:variantsByModel config)))
        merged (cond
                 (and builtin user-variants) (merge builtin user-variants)
                 builtin builtin
                 :else user-variants)]
    (when merged
      (let [filtered (into {} (remove (fn [[_ v]] (= {} v))) merged)]
        (when (seq filtered)
          filtered)))))

(defn selectable-variant-names
  "Returns sorted variant names suitable for UI display, excluding internal-only
           variants like \"default\" which are applied automatically."
  [variants]
  (when (seq variants)
    (vec (sort (remove #{"default"} (keys variants))))))

(def ^:private fallback-agent "code")

(def ^:private default-modes #{"primary" "subagent"})

(defn agent-modes
  "Returns the effective set of modes for an agent config.

   The `:mode` field accepts either a single string (e.g. \"primary\") or a
   collection of strings (e.g. [\"primary\" \"subagent\"]). When `:mode` is
   absent, nil, or an empty collection, defaults to both `primary` and
   `subagent`."
  [agent-config]
  (let [mode (:mode agent-config)]
    (cond
      (string? mode) #{mode}
      (and (coll? mode) (seq mode)) (set mode)
      :else default-modes)))

(defn primary-agent-names
  "Returns the names of agents usable as primary (i.e. whose effective
   modes include \"primary\")."
  [config]
  (->> (:agent config)
       (filter (fn [[_ v]] (contains? (agent-modes v) "primary")))
       (map key)
       distinct))

(defn validate-agent-name
  "Validates if an agent exists in config. Returns the agent name if valid,
   or the fallback agent if not."
  [agent-name config]
  (if (contains? (:agent config) agent-name)
    agent-name
    (do (logger/warn logger-tag (format "Unknown agent '%s' specified, falling back to '%s'"
                                        agent-name fallback-agent))
        fallback-agent)))

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

(def ^:private config-from-custom
  (memoize/ttl config-from-custom* :ttl/threshold ttl-cache-config-ms))

(defn global-config-file ^File []
  (io/file (shared/global-config-dir) "config.json"))

(defn ^:private config-from-global-file []
  (let [config-file (global-config-file)]
    (when (.exists config-file)
      (some-> (safe-read-json-string (slurp config-file) (var *global-config-error*))
              (parse-dynamic-string-values (shared/global-config-dir))))))

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

(def plugin-components* (atom nil))

(def ^:private plugins-resolved* (promise))

(defn deliver-plugins-resolved!
  "Signal that plugin resolution has finished (successfully or not)."
  []
  (deliver plugins-resolved* true))

(defn await-plugins-resolved!
  "Block until plugin resolution has finished. Returns true when resolved,
   nil on timeout (30s)."
  []
  (deref plugins-resolved* 30000 nil))

(defn ^:private deep-merge [& maps]
  (apply merge-with (fn [& args]
                      (if (every? #(or (map? %) (nil? %)) args)
                        (apply deep-merge args)
                        (last args)))
         maps))

(defn ^:private resolve-extra-config-file
  "Resolves an `:extraConfigs` path entry to a `File`. Absolute paths (and `~`)
   are used as-is; relative paths resolve against the first workspace root, or
   the process cwd when there are no workspace roots."
  ^File [path roots]
  (let [expanded (fs/expand-home (str path))]
    (if (fs/absolute? expanded)
      (fs/file expanded)
      (if-let [root (some-> (first roots) :uri shared/uri->filename)]
        (fs/file (fs/path root (str expanded)))
        (fs/file expanded)))))

(defn ^:private config-from-extra-configs
  "Reads and deep-merges every existing file listed in `:extraConfigs`, in
   listed order (later entries win). Missing paths are logged and skipped;
   parse errors are logged, surfaced via `*extra-config-error*` and skipped.
   Non-recursive: an `:extraConfigs` declared inside an extra file is ignored."
  [paths roots]
  (let [paths (cond
                (string? paths) [paths]
                (sequential? paths) paths
                :else [])]
    (reduce
     (fn [final-config path]
       (let [^File config-file (resolve-extra-config-file path roots)]
         (if (.exists config-file)
           (deep-merge final-config
                       (or (some-> (safe-read-json-string (slurp config-file) (var *extra-config-error*))
                                   (parse-dynamic-string-values (fs/file (fs/parent config-file))))
                           {}))
           (do
             (logger/warn logger-tag (format "extraConfigs path not found, skipping: %s" (.getPath config-file)))
             final-config))))
     {}
     paths)))

(defn ^:private resolve-agent-inheritance
  "Resolves :inherit keys in agent configs. When an agent has :inherit \"other\",
   its config is deep-merged on top of the parent agent's config (child wins).
   The :inherit key is stripped from the resolved config."
  [agents]
  (reduce-kv
   (fn [result agent-name agent-config]
     (if-let [parent-name (:inherit agent-config)]
       (let [parent-config (get agents parent-name)]
         (cond
           (= parent-name agent-name)
           (do (logger/warn logger-tag (format "Agent '%s' inherits from itself, ignoring inherit" agent-name))
               (assoc result agent-name (dissoc agent-config :inherit)))

           (nil? parent-config)
           (do (logger/warn logger-tag (format "Agent '%s' inherits from unknown agent '%s', ignoring inherit" agent-name parent-name))
               (assoc result agent-name (dissoc agent-config :inherit)))

           :else
           (assoc result agent-name (deep-merge (dissoc parent-config :inherit)
                                                (dissoc agent-config :inherit)))))
       (assoc result agent-name agent-config)))
   {}
   agents))

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
   [[:providers]
    [:network]]
   :keywordize-val
   [[:providers :ANY :httpClient]
    [:providers :ANY :models :ANY :reasoningHistory]]
   :stringfy-key
   [[:agent]
    [:providers]
    [:providers :ANY :models]
    [:providers :ANY :models :ANY :extraHeaders]
    [:providers :ANY :models :ANY :variants]
    [:hooks :ANY :matcher]
    [:hooks :ANY :matcher :ANY :argsMatchers]
    [:toolCall :approval :allow]
    [:toolCall :approval :allow :ANY :argsMatchers]
    [:toolCall :approval :ask]
    [:toolCall :approval :ask :ANY :argsMatchers]
    [:toolCall :approval :deny]
    [:toolCall :approval :deny :ANY :argsMatchers]
    [:customTools]
    [:customTools :ANY :schema :properties]
    [:mcpServers]
    [:variantsByModel]
    [:variantsByModel :ANY :variants]
    [:prompts :tools]
    [:agent :ANY :prompts :tools]
    [:agent :ANY :toolCall :approval :allow]
    [:agent :ANY :toolCall :approval :allow :ANY :argsMatchers]
    [:agent :ANY :toolCall :approval :ask]
    [:agent :ANY :toolCall :approval :ask :ANY :argsMatchers]
    [:agent :ANY :toolCall :approval :deny]
    [:agent :ANY :toolCall :approval :deny :ANY :argsMatchers]
    ;; Legacy: support old "behavior" config key
    [:behavior]
    [:behavior :ANY :prompts :tools]
    [:behavior :ANY :toolCall :approval :allow]
    [:behavior :ANY :toolCall :approval :allow :ANY :argsMatchers]
    [:behavior :ANY :toolCall :approval :ask]
    [:behavior :ANY :toolCall :approval :ask :ANY :argsMatchers]
    [:behavior :ANY :toolCall :approval :deny]
    [:behavior :ANY :toolCall :approval :deny :ANY :argsMatchers]
    [:otlp]
    [:plugins]]})

(defn ^:private migrate-legacy-agent-name
  "Migrates legacy agent names 'agent' and 'build' to 'code'."
  [agent-name]
  (case agent-name
    ("agent" "build") "code"
    agent-name))

(defn ^:private migrate-legacy-config
  "Migrates legacy config keys to new names for backward compatibility:
   - 'behavior' config key → 'agent'
   - 'defaultBehavior' → 'defaultAgent'
   - 'agent'/'build' agent name → 'code' (inside agent map)"
  [config]
  (cond-> config
    ;; Migrate 'behavior' key → 'agent' (merge, don't overwrite)
    (contains? config :behavior)
    (-> (update :agent (fn [existing]
                         (let [legacy (:behavior config)
                               ;; Rename legacy "agent"/"build" entries to "code"
                               migrated (reduce-kv (fn [m k v]
                                                     (assoc m (migrate-legacy-agent-name k) v))
                                                   {}
                                                   legacy)]
                           (merge migrated existing))))
        (dissoc :behavior))

    ;; Migrate 'defaultBehavior' → 'defaultAgent'
    (and (contains? config :defaultBehavior)
         (not (contains? config :defaultAgent)))
    (-> (assoc :defaultAgent (migrate-legacy-agent-name (:defaultBehavior config)))
        (dissoc :defaultBehavior))

    ;; Also migrate defaultBehavior when nested under :chat (legacy)
    (and (get-in config [:chat :defaultBehavior])
         (not (get-in config [:chat :defaultAgent])))
    (-> (assoc-in [:chat :defaultAgent] (migrate-legacy-agent-name (get-in config [:chat :defaultBehavior])))
        (update :chat dissoc :defaultBehavior))))

(defn ^:private all* [db]
  (let [initialization-config @initialization-config*
        pure-config? (:pureConfig initialization-config)
        merge-config (fn [c1 c2]
                       (deep-merge c1 (normalize-fields normalization-rules c2)))
        plugin-data (when-not pure-config? @plugin-components*)
        plugin-config (when plugin-data
                        (let [cfg (:config-fragment plugin-data)]
                          ;; commands/rules are vectors — separate them to avoid deep-merge replacement
                          (dissoc cfg :commands :rules)))
        plugin-commands (:commands plugin-data)
        plugin-rules (:rules plugin-data)
        plugin-agents (:agents plugin-data)]
    (-> (as-> {} $
          (merge-config $ (initial-config))
          (merge-config $ initialization-config)
          (merge-config $ (when-not pure-config?
                            (config-from-envvar)))
          (if-let [custom-config (config-from-custom)]
            (merge-config $ (when-not pure-config? custom-config))
            (-> $
                (merge-config (when-not pure-config? (config-from-global-file)))
                (merge-config (when-not pure-config? (config-from-local-file (:workspace-folders db))))))
          ;; Plugin config merges after all file configs (user local config wins via later merge)
          (merge-config $ plugin-config)
          ;; extraConfigs merge last, overriding all previous sources
          (merge-config $ (config-from-extra-configs (:extraConfigs $) (:workspace-folders db))))
        ;; Append plugin commands/rules (vector concat, not deep-merge replace)
        (cond->
         (seq plugin-commands) (update :commands #(vec (concat % plugin-commands)))
         (seq plugin-rules) (update :rules #(vec (concat % plugin-rules))))
        migrate-legacy-config
        ;; Merge markdown-defined agents (lowest priority — JSON config agents win)
        ;; Plugin agents merge at same level as markdown agents
        (as-> config
              (let [md-agent-configs (when-not pure-config?
                                       (agents/all-md-agents (:workspace-folders db)))]
                (if (or (seq md-agent-configs) (seq plugin-agents))
                  (update config :agent (fn [existing]
                                          (merge md-agent-configs plugin-agents existing)))
                  config)))
        (update :agent resolve-agent-inheritance))))

(def all (memoize/ttl all* :ttl/threshold ttl-cache-config-ms))

(defn read-file-configs
  "Reads and merges config from file-based sources only (initial config,
  env var, global file, custom file). Does not include
  `initializationOptions` or local project config. Useful for config
  needed before the server is fully initialized (e.g. network/TLS
  settings)."
  []
  (let [merge-config (fn [c1 c2]
                       (deep-merge c1 (normalize-fields normalization-rules c2)))]
    (-> {}
        (merge-config (initial-config))
        (merge-config (config-from-envvar))
        (merge-config (if (some? @custom-config-file-path*)
                        (config-from-custom)
                        (config-from-global-file))))))

(defn validation-error []
  (cond
    *env-var-config-error* "ENV"
    *global-config-error* "global"
    *local-config-error* "local"
    *extra-config-error* "extraConfigs"

    ;; all good
    :else nil))

(defn ^:private maybe-notify-validation-error! [messenger prev-error new-error]
  (when (and new-error (not= new-error prev-error))
    (try
      (messenger/showMessage messenger
                             {:type "warning"
                              :message (format "Failed to parse '%s' config, check stderr logs."
                                               new-error)})
      (catch Exception e
        (logger/warn logger-tag "Failed to notify config validation error:" (.getMessage e))))))

(defn listen-for-changes!
  "Polling loop that detects config changes and dispatches to registered
   `:config-updated-fns` listeners. Listeners receive `[prev-config new-config]`
   so they can diff against the last seen snapshot (e.g. MCP reconciliation).
   Parse/validation failures surface to the client via `$/showMessage`."
  [db* messenger]
  (loop [prev-config nil
         prev-validation-error nil]
    (when-not (:stopping @db*)
      (Thread/sleep ^long listen-idle-ms)
      (let [db @db*
            new-config (try (all db)
                            (catch Exception e
                              (logger/warn logger-tag "Error reloading config:" (.getMessage e))
                              prev-config))
            new-validation-error (validation-error)]
        (maybe-notify-validation-error! messenger prev-validation-error new-validation-error)
        (let [new-config-hash (hash new-config)]
          (when (not= new-config-hash (:config-hash db))
            (swap! db* assoc :config-hash new-config-hash)
            (doseq [config-updated-fns (vals (:config-updated-fns db))]
              (try
                (config-updated-fns prev-config new-config)
                (catch Exception e
                  (logger/error logger-tag "Error in config-updated fn:" (.getMessage e)))))))
        (recur new-config new-validation-error)))))

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

(defn notify-fields-changed-only!
  "Emit `config/updated` with the fields that have changed against the
   session-level mirror (`:last-config-notified`) and update that mirror.

   When called with `chat-id` (4-arity), the broadcast is scoped to that
   chat: it includes `:chat-id` in the payload so the client can apply
   the change only to that chat's UI state, and bypasses the session-
   level mirror diff (so per-chat changes never collapse against the
   session mirror or each other). Used by `chat/selectedModelChanged`
   and `chat/selectedAgentChanged` when the client supplies a `chatId`."
  ([config-updated messenger db*]
   (let [config-to-notify (diff-keeping-vectors (:last-config-notified @db*)
                                                config-updated)]
     (when (seq config-to-notify)
       (swap! db* update :last-config-notified shared/deep-merge config-to-notify)
       (messenger/config-updated messenger config-to-notify))))
  ([config-updated messenger _db* chat-id]
   (when chat-id
     (messenger/config-updated messenger (assoc config-updated :chat-id chat-id)))))

(defn notify-selected-model-changed!
  "Server-initiated equivalent of a client `chat/selectedModelChanged`: aligns
   the client-side selected model to `full-model`, re-computing the available
   variants and the suggested selected variant. Emits via
   `notify-fields-changed-only!`, so it is a no-op when nothing changed.
   Returns nil when `full-model` is missing or no longer in `(:models @db*)`,
   so a stale persisted model does not bubble to the UI. Used by chat resume
   flows (`chat/open`, `/resume`) to restore the model each chat was using.

   When `chat-variant` is provided (the chat's persisted `:variant`) and is
   still supported by `full-model`, the broadcast keeps it; otherwise it
   falls back to the default agent's configured variant when valid, else
   nil. This lets resume preserve the variant the user last saw on the
   chat instead of resetting to the default agent's variant."
  ([full-model db* messenger config]
   (notify-selected-model-changed! full-model db* messenger config nil))
  ([full-model db* messenger config chat-variant]
   (when (and full-model (contains? (:models @db*) full-model))
     (let [default-agent-name (validate-agent-name
                               (or (:defaultAgent (:chat config))
                                   (:defaultAgent config))
                               config)
           agent-config (get-in config [:agent default-agent-name])
           [provider model-name] (shared/full-model->provider+model full-model)
           user-variants (when (and provider model-name)
                           (get-in config [:providers provider :models model-name :variants]))
           variants (when (and provider model-name)
                      (selectable-variant-names
                       (effective-model-variants config provider model-name user-variants)))
           agent-variant (:variant agent-config)
           valid? (fn [v] (and v variants (some #{v} variants)))
           select-variant (cond
                            (valid? chat-variant) chat-variant
                            (valid? agent-variant) agent-variant
                            :else nil)]
       (notify-fields-changed-only!
        {:chat {:select-model full-model
                :variants (or variants [])
                :select-variant select-variant}}
        messenger
        db*)))))

(defn notify-selected-trust-changed!
  "Server-initiated equivalent of a client-side trust toggle: aligns the
   client-side trust indicator with the chat's persisted `:trust` value.
   Emits via `notify-fields-changed-only!`, so it is a no-op when nothing
   changed. Used by chat resume flows (`chat/open`, `/resume`) so the icon
   the client shows matches the auto-approval behavior the server is about
   to apply (#426)."
  [trust db* messenger]
  (notify-fields-changed-only!
   {:chat {:select-trust (boolean trust)}}
   messenger
   db*))

(def ^:private config-schema-url "https://eca.dev/config.json")

(defn ^:private flatten-to-paths
  "Recursively walks a nested map and returns a sequence of [path value] pairs,
   where path is a vector of string keys and value is a leaf (non-map) value.
   Mirrors deep-merge semantics: only the touched leaf paths are written."
  ([m] (flatten-to-paths [] m))
  ([prefix m]
   (reduce-kv (fn [acc k v]
                (let [path (conj prefix (if (keyword? k) (name k) (str k)))]
                  (if (and (map? v) (seq v))
                    (into acc (flatten-to-paths path v))
                    (conj acc [path v]))))
              []
              m)))

(defn update-global-config! [config]
  (let [file (global-config-file)
        raw (if (.exists file) (slurp file) "{}")
        root (rj/parse-string raw)
        root (reduce (fn [r [path v]] (rj/assoc-in r path v))
                     root
                     (flatten-to-paths config))
        root (rj/assoc-in root ["$schema"] config-schema-url)]
    (io/make-parents file)
    (spit file (rj/to-string root))))
