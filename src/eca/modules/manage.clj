(ns eca.modules.manage
  "The `:eca/manage` module: two skills about ECA itself, `eca-info`, a
   report of the running ECA to debug it, and `eca-manage`, how to change
   ECA, from its config to modules, following its docs. Their bodies are
   built when the model loads them, from the live db and config."
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.llm-util :as llm-util]
   [eca.modules :as modules]
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
          (map (fn [{:keys [name description dir module plugin]}]
                 (let [source (cond
                                module (str "module:" (symbol module))
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

(defn ^:private module-ids [entries]
  (if (seq entries)
    (string/join ", " (map #(str "`" (symbol (:id %)) "`") entries))
    "(none)"))

(defn ^:private this-eca-section
  "The running ECA: its version, as the docs follow ECA's latest code, and
   its modules: built-in ones by id, external ones with their source and
   what went wrong."
  [config]
  (let [{built-in true external false} (group-by (comp boolean :built-in?) (modules/statuses config))
        active? #(= :active (:status %))]
    (multi-str
     "## This ECA"
     ""
     (str "- Version: " (config/eca-version) ". The docs follow ECA's latest code, which may be newer than this.")
     (str "- Built-in modules, active: " (module-ids (filter active? built-in)))
     (str "- Built-in modules, not active: " (module-ids (remove active? built-in)))
     (str "- External modules:" (when-not (seq external) " (none)"))
     (for [{:keys [id status reason source errors]} external]
       ;; a module that failed to load may have no id
       (str "  - `" (if id (symbol id) (fs/file-name source)) "` (" (name status) ", from `" source "`)"
            (when (and reason (not (active? {:status status})))
              (str ": " reason))
            (when-let [{:keys [what message]} (last errors)]
              (str " Last error, in " what ": " message)))))))

(defn ^:private eca-manage-body [{:keys [config]}]
  (multi-str
   (string/trimr (slurp (io/resource "prompts/eca_manage_skill.md")))
   ""
   (this-eca-section config)))

(def module
  {:id :eca/manage
   :description "Skills about ECA itself: eca-info, to debug it, and eca-manage, to change it."
   :requires #{:eca/skills}
   ;; each :body gets the skill tool's ctx plus :skills, all skills
   :skills [{:name "eca-info"
             :description (str "Inspect running ECA for self-debug: versions, "
                               "client, default model, providers/auth, MCP servers "
                               "(status/tools), skills, subagents, env vars, "
                               "credential files.")
             :body #'eca-info-body}
            {:name "eca-manage"
             :description (str "Change ECA itself: its config, models, tool approval, agents, skills, rules, commands, "
                               "hooks, MCP servers, plugins or modules, following ECA's docs.")
             :body #'eca-manage-body}]})
