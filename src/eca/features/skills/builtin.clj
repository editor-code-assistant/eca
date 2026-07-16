(ns eca.features.skills.builtin
  "Built-in skills shipped with ECA itself.

  Unlike on-disk skills, built-in skills compute their body lazily at
  invocation time so they can include live runtime info (db / config).
  Each entry exposes :name, :description and :handler-fn instead of :body."
  (:require
   [clojure.string :as string]
   [eca.config :as config]
   [eca.llm-util :as llm-util]
   [eca.secrets :as secrets]
   [eca.shared :as shared :refer [multi-str]])
  (:import
   [java.lang ProcessHandle]
   [java.time Instant ZoneId]
   [java.time.format DateTimeFormatter]))

(set! *warn-on-reflection* true)

(defn ^:private format-expires-at [^long expires-at]
  (let [instant (Instant/ofEpochSecond expires-at)
        formatter (.withZone (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm")
                             (ZoneId/systemDefault))]
    (.format formatter instant)))

(defn ^:private format-auth-type [provider provider-auth config]
  (let [[auth-type api-key] (llm-util/provider-api-key provider provider-auth config)
        base (case auth-type
               :auth/oauth "Subscription (oauth)"
               :auth/token (str "API key (" (shared/obfuscate api-key) ")")
               "Unknown")]
    (if-let [expires-at (:expires-at provider-auth)]
      (str base ", expires " (format-expires-at expires-at))
      base)))

(defn ^:private versions-section [db]
  (let [{:keys [name version]} (:client-info db)]
    (multi-str "## Versions"
               (str "- ECA server: " (config/eca-version))
               (when name
                 (str "- Client: " name (when version (str " " version))))
               (str "- OS: " (System/getProperty "os.name")
                    " " (System/getProperty "os.version"))
               (str "- Java: " (System/getProperty "java.version")))))

(defn ^:private server-section []
  (let [process (ProcessHandle/current)
        info (.info process)
        cmd (.orElse (.commandLine info) nil)]
    (multi-str "## Server process"
               (str "- PID: " (.pid process))
               (str "- Command: " (or cmd "(unknown)")))))

(defn ^:private workspaces-section [db]
  (let [folders (:workspace-folders db)]
    (multi-str "## Workspaces"
               (if (seq folders)
                 (->> folders
                      (map (fn [{:keys [uri]}]
                             (str "- " (or (some-> uri shared/uri->filename) uri))))
                      (string/join "\n"))
                 "- (none)"))))

(defn ^:private models-section [db config]
  (let [configured-default (:defaultModel config)
        all-models (sort (keys (:models db)))]
    (multi-str "## Models"
               (str "- Configured default: " (or configured-default "(none)"))
               (str "- Models known: " (count all-models))
               (when (seq all-models)
                 (str "- Sample: " (string/join ", " (take 5 all-models))
                      (when (> (count all-models) 5) " ..."))))))

(defn ^:private auth-section [db config]
  (let [active (filter (fn [[_ auth]] (seq auth)) (:auth db))]
    (multi-str "## Logged providers"
               (if (seq active)
                 (->> active
                      (sort-by first)
                      (map (fn [[provider auth]]
                             (str "- " provider ": "
                                  (format-auth-type provider auth config))))
                      (string/join "\n"))
                 "- (none)"))))

(defn ^:private mcp-section [db]
  (let [clients (:mcp-clients db)]
    (multi-str
     (str "## MCP servers (" (count clients) ")")
     (if (seq clients)
       (->> clients
            (sort-by first)
            (map (fn [[server-name {:keys [status version tools prompts resources instructions]}]]
                   (multi-str
                    (str "### " server-name " — " (or (some-> status name) "?"))
                    (when version (str "- Version: " version))
                    (str "- Tools: " (count tools))
                    (str "- Prompts: " (count prompts))
                    (str "- Resources: " (count resources))
                    (when (seq instructions)
                      (str "- Instructions: "
                           (subs instructions 0 (min 80 (count instructions)))
                           (when (> (count instructions) 80) "..."))))))
            (string/join "\n\n"))
       "- (none)"))))

(defn ^:private skills-section [skills]
  (multi-str
   (str "## Skills (" (count skills) ")")
   (if (seq skills)
     (->> skills
          (sort-by :name)
          (map (fn [{:keys [name description dir handler-fn plugin]}]
                 (let [source (cond
                                handler-fn "built-in"
                                plugin (str "plugin:" plugin)
                                dir dir
                                :else "?")]
                   (str "- " name " — " description " (" source ")"))))
          (string/join "\n"))
     "- (none)")))

(defn ^:private subagents-section [config parent-agent-name]
  (let [subagents (->> (config/available-subagents config parent-agent-name)
                       (sort-by first))]
    (multi-str
     (str "## Subagents (" (count subagents) ")")
     (if (seq subagents)
       (->> subagents
            (map (fn [[agent-name {:keys [description defaultModel]}]]
                   (str "- " agent-name
                        (when description (str ": " description))
                        (when defaultModel (str " — model: " defaultModel)))))
            (string/join "\n"))
       "- (none)"))))

(defn ^:private env-vars-section []
  (multi-str
   "## Relevant env vars (obfuscated)"
   (let [entries (->> (System/getenv)
                      (keep (fn [[k v]]
                              (cond
                                (or (string/includes? k "KEY")
                                    (string/includes? k "TOKEN"))
                                (str "- " k "=" (shared/obfuscate v))

                                (or (string/includes? k "API")
                                    (string/includes? k "URL")
                                    (string/includes? k "BASE"))
                                (str "- " k "=" v)

                                :else nil)))
                      sort)]
     (if (seq entries)
       (string/join "\n" entries)
       "- (none matching)"))))

(defn ^:private credentials-section [config]
  (let [check (secrets/check-credential-files (:netrcFile config))
        existing (filter :exists (:files check))]
    (multi-str
     "## Credential files"
     (if (seq existing)
       (->> existing
            (map (fn [{:keys [path readable permissions-secure
                              credentials-count parse-error suggestion]}]
                   (multi-str
                    (str "- " path)
                    (when (some? readable)
                      (str "  - Readable: " readable))
                    (when (some? permissions-secure)
                      (str "  - Permissions: "
                           (if permissions-secure
                             "secure"
                             "INSECURE (should be 0600)")))
                    (when credentials-count
                      (str "  - Credentials: " credentials-count))
                    (when parse-error
                      (str "  - Parse error: " parse-error))
                    (when suggestion
                      (str "  - " suggestion)))))
            (string/join "\n"))
       "- (none found)"))))

(defn ^:private eca-info-body [{:keys [db config skills agent]}]
  (multi-str "# ECA Self-Debug Report"
             ""
             (versions-section db)
             ""
             (server-section)
             ""
             (workspaces-section db)
             ""
             (models-section db config)
             ""
             (auth-section db config)
             ""
             (mcp-section db)
             ""
             (skills-section skills)
             ""
             (subagents-section config agent)
             ""
             (env-vars-section)
             ""
             (credentials-section config)))

(defn all
  "Returns the list of built-in skills.

  Each skill has :name, :description and :handler-fn (a function of
  {:keys [db config skills agent]} returning the markdown body).
  Built-in skills compute their body lazily; they have no :body or :dir."
  []
  [{:name "eca-info"
    :description (str "Inspect running ECA for self-debug: versions, "
                      "client, default model, providers/auth, MCP servers "
                      "(status/tools), skills, subagents, env vars, "
                      "credential files.")
    :handler-fn eca-info-body}])
