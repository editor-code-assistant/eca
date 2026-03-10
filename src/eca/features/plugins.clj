(ns eca.features.plugins
  "Plugin system for loading external configuration from git repos or local paths.

   Each source must contain .eca-plugin/marketplace.json listing available plugins.
   Installed plugins are discovered for skills, agents, commands, rules, hooks, and MCP servers.
   All components are returned as a config-ready data structure that config.clj merges
   into the waterfall without requiring changes to individual feature modules."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.cache :as cache]
   [eca.config :as config]
   [eca.features.agents :as agents]
   [eca.logger :as logger]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[PLUGINS]")

(defn ^:private sanitize-source-url
  "Converts a git URL into a human-readable directory name.
   e.g. 'https://github.com/my-org/my-plugins.git' -> 'github.com-my-org-my-plugins'"
  ^String [^String url]
  (-> url
      (string/replace #"^https?://" "")
      (string/replace #"^git@" "")
      (string/replace #"\.git$" "")
      (string/replace #":" "-")
      (string/replace #"/" "-")))

(defn ^:private source-cache-path
  "Returns the local cache directory for a given source URL."
  ^java.io.File [^String source-url]
  (io/file (cache/plugins-dir) (sanitize-source-url source-url)))

(defn ^:private git-url?
  "Returns true if the source string looks like a git URL rather than a local path."
  [^String source]
  (or (string/starts-with? source "http://")
      (string/starts-with? source "https://")
      (string/starts-with? source "git@")))

(def ^:private git-timeout-ms 30000)

(def ^:private pull-ttl-ms
  "Minimum time between git pull attempts for the same source (1 hour)."
  (* 60 60 1000))

(def ^:private last-pull-times (atom {}))

(defn ^:private run-git!
  "Runs a git command with a timeout and returns {:exit :out :err}."
  [& args]
  (try
    (let [proc (apply p/process {:out :string :err :string} "git" args)
          result (deref proc git-timeout-ms nil)]
      (if result
        {:exit (:exit result)
         :out (:out result)
         :err (:err result)}
        (do (p/destroy-tree proc)
            {:exit 1 :out "" :err (str "git operation timed out after " (/ git-timeout-ms 1000) "s")})))
    (catch Exception e
      {:exit 1 :out "" :err (.getMessage e)})))

(defn ^:private pull-needed?
  "Returns true if enough time has passed since the last pull for this source."
  [^String source-url]
  (let [last-pull (get @last-pull-times source-url 0)
        now (System/currentTimeMillis)]
    (> (- now last-pull) pull-ttl-ms)))

(defn ^:private clone-or-pull!
  "Clones a git repo if not cached, or pulls if already cached (respecting TTL).
   Returns the local directory path or nil on failure."
  [^String source-url]
  (let [cache-dir (source-cache-path source-url)]
    (if (fs/exists? (io/file cache-dir ".git"))
      (if (pull-needed? source-url)
        (let [{:keys [exit err]} (run-git! "-C" (str cache-dir) "pull" "--ff-only" "-q")]
          (swap! last-pull-times assoc source-url (System/currentTimeMillis))
          (if (zero? exit)
            (do (logger/info logger-tag "Updated plugin source:" source-url)
                cache-dir)
            (do (logger/warn logger-tag "Failed to update plugin source, using cached version:"
                             source-url err)
                cache-dir)))
        (do (logger/debug logger-tag "Plugin source recently pulled, using cached version:" source-url)
            cache-dir))
      (do
        (fs/create-dirs (fs/parent cache-dir))
        (let [{:keys [exit err]} (run-git! "clone" "--depth" "1" "-q" source-url (str cache-dir))]
          (if (zero? exit)
            (do (logger/info logger-tag "Cloned plugin source:" source-url)
                (swap! last-pull-times assoc source-url (System/currentTimeMillis))
                cache-dir)
            (do (logger/warn logger-tag "Failed to clone plugin source:" source-url err)
                nil)))))))

(defn ^:private resolve-source!
  "Resolves a plugin source to a local directory.
   For git URLs: clones/pulls to cache. For local paths: verifies existence.
   Returns a File or nil."
  [^String source]
  (if (git-url? source)
    (clone-or-pull! source)
    (let [local-dir (io/file source)]
      (if (fs/exists? local-dir)
        (do (logger/debug logger-tag "Using local plugin source:" source)
            local-dir)
        (do (logger/warn logger-tag "Local plugin source not found:" source)
            nil)))))

(defn ^:private read-marketplace
  "Reads and parses .eca-plugin/marketplace.json from a source directory.
   Returns a vector of plugin entries or nil."
  [^java.io.File source-dir]
  (let [marketplace-file (io/file source-dir ".eca-plugin" "marketplace.json")]
    (if (fs/exists? marketplace-file)
      (try
        (let [content (json/parse-string (slurp marketplace-file) true)]
          (or (:plugins content) []))
        (catch Exception e
          (logger/warn logger-tag "Failed to parse marketplace.json:" (str marketplace-file)
                       (.getMessage e))
          nil))
      (do (logger/warn logger-tag "No .eca-plugin/marketplace.json found in:" (str source-dir))
          nil))))

(defn ^:private find-plugin-entry
  "Finds a plugin entry by name in a marketplace plugin list."
  [^String plugin-name plugins]
  (first (filter #(= plugin-name (:name %)) plugins)))

(defn ^:private resolve-plugin-dir
  "Resolves the absolute path to a plugin directory given a source dir and marketplace entry.
   Returns a File or nil."
  [^java.io.File source-dir {:keys [source path]}]
  (let [relative-path (or source path)]
    (when relative-path
      (let [plugin-dir (io/file source-dir relative-path)]
        (when (fs/exists? plugin-dir)
          plugin-dir)))))

;; -- Component readers --

(defn ^:private read-hooks
  "Reads hooks/hooks.json from a plugin directory. Expects ECA native hook format."
  [^java.io.File plugin-dir]
  (let [hooks-file (io/file plugin-dir "hooks" "hooks.json")]
    (when (fs/exists? hooks-file)
      (try
        (json/parse-string (slurp hooks-file) true)
        (catch Exception e
          (logger/warn logger-tag "Failed to parse hooks.json:" (str hooks-file)
                       (.getMessage e))
          nil)))))

(defn ^:private read-mcp-servers
  "Reads .mcp.json from a plugin directory and returns mcpServers map."
  [^java.io.File plugin-dir]
  (let [mcp-file (io/file plugin-dir ".mcp.json")]
    (when (fs/exists? mcp-file)
      (try
        (let [content (json/parse-string (slurp mcp-file) true)]
          (:mcpServers content))
        (catch Exception e
          (logger/warn logger-tag "Failed to parse .mcp.json:" (str mcp-file)
                       (.getMessage e))
          nil)))))

(defn ^:private read-eca-config
  "Reads eca.json from a plugin directory for arbitrary ECA config overrides."
  [^java.io.File plugin-dir]
  (let [config-file (io/file plugin-dir "eca.json")]
    (when (fs/exists? config-file)
      (try
        (json/parse-string (slurp config-file) true)
        (catch Exception e
          (logger/warn logger-tag "Failed to parse eca.json:" (str config-file)
                       (.getMessage e))
          nil)))))

(defn ^:private read-agents
  "Reads agents/*.md from a plugin directory and returns a map of {agent-name agent-config}.
   Skips non-agent files like README.md."
  [^java.io.File plugin-dir]
  (let [agents-dir (io/file plugin-dir "agents")]
    (when (fs/exists? agents-dir)
      (->> (fs/glob agents-dir "*.md" {:follow-links true})
           (remove (fn [f]
                     (let [fname (string/lower-case (str (fs/file-name f)))]
                       (= fname "readme.md"))))
           (keep agents/agent-md-file->agent)
           (into {})))))

(defn ^:private read-commands
  "Reads commands/*.md from a plugin directory and returns a vector of {:path ...} entries."
  [^java.io.File plugin-dir]
  (let [commands-dir (io/file plugin-dir "commands")]
    (when (fs/exists? commands-dir)
      (->> (fs/glob commands-dir "**" {:follow-links true})
           (keep (fn [file]
                   (when (and (not (fs/directory? file))
                              (string/ends-with? (str (fs/file-name file)) ".md"))
                     {:path (str (fs/canonicalize file))})))
           vec))))

(defn ^:private read-rules
  "Reads rules/** from a plugin directory and returns a vector of {:path ...} entries."
  [^java.io.File plugin-dir]
  (let [rules-dir (io/file plugin-dir "rules")]
    (when (fs/exists? rules-dir)
      (->> (fs/glob rules-dir "**" {:follow-links true})
           (keep (fn [file]
                   (when-not (fs/directory? file)
                     {:path (str (fs/canonicalize file))})))
           vec))))

(defn ^:private read-skill-dirs
  "Returns skill directories from a plugin directory."
  [^java.io.File plugin-dir]
  (let [skills-dir (io/file plugin-dir "skills")]
    (when (fs/exists? skills-dir)
      [(str (fs/canonicalize skills-dir))])))

;; -- Discovery and resolution --

(defn ^:private discover-components
  "Walks a plugin directory and discovers all components.
   Returns a config-ready map:
   {:config-fragment {...}  — deep-mergeable into ECA config (mcpServers, hooks, pluginSkillDirs, eca.json overrides)
    :agents {...}           — agent-name -> agent-config map (merged alongside markdown agents)
    :commands [{:path ...}] — appended to :commands config vector
    :rules [{:path ...}]}   — appended to :rules config vector"
  [^java.io.File plugin-dir]
  (let [mcp-servers (read-mcp-servers plugin-dir)
        hooks (read-hooks plugin-dir)
        eca-config (read-eca-config plugin-dir)
        skill-dirs (read-skill-dirs plugin-dir)
        config-fragment (cond-> (or eca-config {})
                          (seq mcp-servers) (assoc :mcpServers mcp-servers)
                          (seq hooks) (assoc :hooks hooks)
                          (seq skill-dirs) (update :pluginSkillDirs (fnil into []) skill-dirs))]
    {:config-fragment config-fragment
     :agents (read-agents plugin-dir)
     :commands (read-commands plugin-dir)
     :rules (read-rules plugin-dir)}))

(defn ^:private merge-components
  "Merges components from multiple plugins into a single map.
   Config fragments are deep-merged, but :pluginSkillDirs is concatenated (not replaced)."
  [components-list]
  (reduce
   (fn [acc components]
     (let [new-skill-dirs (get-in components [:config-fragment :pluginSkillDirs])
           fragment-rest (dissoc (:config-fragment components) :pluginSkillDirs)]
       (-> acc
           (update :config-fragment shared/deep-merge fragment-rest)
           (cond->
            (seq new-skill-dirs)
             (update-in [:config-fragment :pluginSkillDirs] (fnil into []) new-skill-dirs))
           (update :agents merge (:agents components))
           (update :commands into (:commands components))
           (update :rules into (:rules components)))))
   {:config-fragment {}
    :agents {}
    :commands []
    :rules []}
   components-list))

(defn ^:private parse-sources
  "Extracts plugin sources from config, filtering out the install key.
   Returns a seq of [source-name source-url] pairs."
  [plugins-config]
  (->> plugins-config
       (remove (fn [[k _]] (= "install" (name k))))
       (keep (fn [[source-name source-config]]
               (when-let [source-url (if (map? source-config)
                                       (get source-config :source)
                                       nil)]
                 [(name source-name) source-url])))))

(def ^:private empty-result
  {:config-fragment {} :agents {} :commands [] :rules []})

(defn resolve-all!
  "Main entry point: resolves all plugin sources, reads marketplaces,
   discovers components from installed plugins.
   Returns a merged result with :config-fragment, :agents, :commands, :rules."
  [plugins-config]
  (if (or (nil? plugins-config) (empty? plugins-config))
    empty-result
    (let [auto-install (get plugins-config "install" [])
          sources (parse-sources plugins-config)]
      (if (empty? auto-install)
        (do (logger/debug logger-tag "No plugins in install, skipping")
            empty-result)
        (let [components
              (doall
               (for [[source-name source-url] sources
                     :let [_ (logger/info logger-tag "Resolving plugin source:" source-name source-url)
                           source-dir (resolve-source! source-url)]
                     :when source-dir
                     :let [marketplace (read-marketplace source-dir)]
                     :when marketplace
                     plugin-name auto-install
                     :let [entry (find-plugin-entry plugin-name marketplace)]
                     :when (do (when-not entry
                                 (logger/debug logger-tag "Plugin not found in source" source-name ":" plugin-name))
                               entry)
                     :let [plugin-dir (resolve-plugin-dir source-dir entry)]
                     :when (do (when-not plugin-dir
                                 (logger/warn logger-tag "Plugin directory not found:" plugin-name
                                              "in" (str source-dir)))
                               plugin-dir)]
                 (do (logger/info logger-tag "Loading plugin:" plugin-name "from" source-name)
                     (discover-components plugin-dir))))]
          (merge-components components))))))

(defn list-marketplace-plugins
  "Lists all available plugins from configured marketplace sources.
   Returns a seq of {:name :source-name :source-url :description :installed?} maps."
  [plugins-config]
  (when (seq plugins-config)
    (let [installed-set (set (get plugins-config "install" []))
          sources (parse-sources plugins-config)]
      (doall
       (for [[source-name source-url] sources
             :let [source-dir (resolve-source! source-url)]
             :when source-dir
             :let [marketplace (read-marketplace source-dir)]
             :when marketplace
             plugin marketplace]
         {:name (:name plugin)
          :description (:description plugin)
          :source-name source-name
          :source-url source-url
          :installed? (contains? installed-set (:name plugin))})))))

(defn ^:private parse-plugin-arg
  "Parses a plugin install argument. Supports 'plugin-name' or 'plugin-name@marketplace'.
   Returns {:plugin-name ... :marketplace ...} where :marketplace may be nil."
  [^String arg]
  (let [parts (string/split arg #"@" 2)]
    {:plugin-name (first parts)
     :marketplace (when (= 2 (count parts)) (second parts))}))

(defn ^:private find-plugin-in-marketplaces
  "Finds a plugin by name across all resolved marketplaces, optionally filtered by source name.
   Returns {:name :source-name :source-url} or nil."
  [plugins-config plugin-name marketplace-filter]
  (let [sources (parse-sources plugins-config)]
    (first
     (for [[source-name source-url] sources
           :when (or (nil? marketplace-filter) (= marketplace-filter source-name))
           :let [source-dir (resolve-source! source-url)]
           :when source-dir
           :let [marketplace (read-marketplace source-dir)]
           :when marketplace
           :let [entry (find-plugin-entry plugin-name marketplace)]
           :when entry]
       {:name plugin-name
        :source-name source-name
        :source-url source-url}))))

(defn install-plugin!
  "Installs a plugin by adding it to the global config install list.
   `input` is either 'plugin-name' or 'plugin-name@marketplace'.
   Returns {:status :ok/:error, :message ...}."
  [plugins-config ^String input]
  (let [{:keys [plugin-name marketplace]} (parse-plugin-arg input)
        sources (parse-sources plugins-config)
        current-install (set (get plugins-config "install" []))]
    (cond
      (empty? sources)
      {:status :error
       :message "No plugin marketplaces configured. Add plugin sources to your config under the `plugins` key."}

      (contains? current-install plugin-name)
      {:status :error
       :message (str "Plugin `" plugin-name "` is already installed.")}

      :else
      (if-let [found (find-plugin-in-marketplaces plugins-config plugin-name marketplace)]
        (let [new-install (vec (sort (conj current-install plugin-name)))]
          (config/update-global-config! {:plugins {:install new-install}})
          {:status :ok
           :message (str "Plugin `" plugin-name "` installed from **" (:source-name found) "**. Restart ECA to activate it.")})
        {:status :error
         :message (if marketplace
                    (str "Plugin `" plugin-name "` not found in marketplace `" marketplace "`.")
                    (str "Plugin `" plugin-name "` not found in any configured marketplace."))}))))

(defn uninstall-plugin!
  "Uninstalls a plugin by removing it from the global config install list.
   Returns {:status :ok/:error, :message ...}."
  [plugins-config ^String plugin-name]
  (let [current-install (set (get plugins-config "install" []))]
    (if (contains? current-install plugin-name)
      (let [new-install (vec (sort (disj current-install plugin-name)))]
        (config/update-global-config! {:plugins {:install new-install}})
        {:status :ok
         :message (str "Plugin `" plugin-name "` uninstalled. Restart ECA to apply.")})
      {:status :error
       :message (str "Plugin `" plugin-name "` is not installed.")})))
