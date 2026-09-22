(ns eca.features.plugins
  "Plugin system for loading external configuration from git repos or local paths.

   Each source must contain .eca-plugin/marketplace.json listing available plugins.
   Installed plugins are discovered for skills, agents, commands, rules, hooks, and MCP servers.
   Plugins may declare dependencies (in the marketplace entry or the plugin's
   .eca-plugin/plugin.json) which are resolved transitively at load time.
   All components are returned as a config-ready data structure that config.clj merges
   into the waterfall without requiring changes to individual feature modules."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [cheshire.factory :as json.factory]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.cache :as cache]
   [eca.config :as config]
   [eca.features.agents :as agents]
   [eca.features.plugins.git :as git]
   [eca.interpolation :as interpolation]
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
  "Returns the legacy cache directory used when adopting a source pin."
  ^java.io.File [^String source-url]
  (io/file (cache/plugins-dir) (sanitize-source-url source-url)))

(defn ^:private git-url?
  "Returns true if the source string looks like a git URL rather than a local path."
  [^String source]
  (or (string/starts-with? source "http://")
      (string/starts-with? source "https://")
      (string/starts-with? source "git@")))

(defn ^:private resolve-source!
  "Returns {:dir File :commit full-oid} for a pinned Git source, or {:dir File}
   for a local development source. Missing local paths return nil; Git failures propagate."
  [^String source]
  (if (git-url? source)
    (git/resolve! source (source-cache-path source))
    (let [local-dir (io/file source)]
      (if (fs/exists? local-dir)
        (do (logger/debug logger-tag "Using local plugin source:" source)
            {:dir local-dir})
        (do (logger/warn logger-tag "Local plugin source not found:" source)
            nil)))))

(defn ^:private parse-marketplace
  "Reads marketplace data only, without interpolation or component discovery.
   Throws on missing, malformed, or ambiguous marketplace entries."
  [^java.io.File source-dir]
  (let [marketplace-file (io/file source-dir ".eca-plugin" "marketplace.json")
        content (json/parse-string (slurp marketplace-file) true)
        plugins (:plugins content)
        nonblank-string? #(and (string? %) (not (string/blank? %)))]
    (when-not (and (map? content)
                   (vector? plugins)
                   (every? (fn [entry]
                             (and (map? entry)
                                  (nonblank-string? (:name entry))
                                  (nonblank-string? (or (:source entry) (:path entry)))
                                  (or (nil? (:dependencies entry))
                                      (and (vector? (:dependencies entry))
                                           (every? nonblank-string? (:dependencies entry))))))
                           plugins)
                   (= (count plugins) (count (distinct (map :name plugins)))))
      (throw (ex-info (str "Invalid marketplace.json at `" marketplace-file
                           "`: expected uniquely named plugins with source/path strings and optional dependency refs.")
                      {:marketplace-file (str marketplace-file)})))
    plugins))

(defn ^:private read-marketplace
  "Reads marketplace entries. Strict resolution propagates failures; legacy callers get nil."
  ([^java.io.File source-dir]
   (read-marketplace source-dir false))
  ([^java.io.File source-dir strict?]
   (try
     (parse-marketplace source-dir)
     (catch Exception e
       (if strict?
         (throw e)
         (do (logger/warn logger-tag "Failed to read marketplace.json:" (str source-dir)
                          (.getMessage e))
             nil))))))

(defn ^:private read-plugin-manifest
  "Reads and parses the optional .eca-plugin/plugin.json from a plugin directory.
   May declare `dependencies`. Returns the parsed map or nil."
  [^java.io.File plugin-dir]
  (let [manifest-file (io/file plugin-dir ".eca-plugin" "plugin.json")]
    (when (fs/exists? manifest-file)
      (try
        (json/parse-string (slurp manifest-file) true)
        (catch Exception e
          (logger/warn logger-tag "Failed to parse plugin.json:" (str manifest-file)
                       (.getMessage e))
          nil)))))

(defn ^:private find-plugin-entry
  "Finds a plugin entry by name in a marketplace plugin list."
  [^String plugin-name plugins]
  (first (filter #(= plugin-name (:name %)) plugins)))

(defn ^:private parse-plugin-arg
  "Parses a plugin ref. Supports 'plugin-name' or 'plugin-name@marketplace'.
   Returns {:plugin-name ... :marketplace ...} where :marketplace may be nil."
  [^String arg]
  (let [parts (string/split arg #"@" 2)]
    {:plugin-name (first parts)
     :marketplace (when (= 2 (count parts)) (second parts))}))

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

(defn ^:private plugin-hook-key
  [plugin-name hook-key]
  (str plugin-name "::" (name hook-key)))

(defn ^:private read-hooks
  "Reads hooks/hooks.json from a plugin directory. Expects ECA native hook format.
   Applies dynamic string interpolation after JSON parsing.
   Prefixes hook map keys with 'plugin-name::' so plugin hooks are identifiable."
  [^java.io.File plugin-dir plugin-name]
  (let [hooks-file (io/file plugin-dir "hooks" "hooks.json")]
    (when (fs/exists? hooks-file)
      (try
        (let [parsed (-> (json/parse-string (slurp hooks-file) true)
                         (interpolation/replace-dynamic-strings-in-data (str (fs/parent hooks-file)) nil))]
          (if plugin-name
            (update-keys parsed #(plugin-hook-key plugin-name %))
            parsed))
        (catch Exception e
          (logger/warn logger-tag "Failed to parse hooks.json:" (str hooks-file)
                       (.getMessage e))
          nil)))))

(defn ^:private read-mcp-servers
  "Reads .mcp.json from a plugin directory and returns mcpServers map.
   Applies dynamic string interpolation after JSON parsing."
  [^java.io.File plugin-dir]
  (let [mcp-file (io/file plugin-dir ".mcp.json")]
    (when (fs/exists? mcp-file)
      (try
        (let [content (-> (json/parse-string (slurp mcp-file) true)
                          (interpolation/replace-dynamic-strings-in-data (str plugin-dir) nil))]
          (:mcpServers content))
        (catch Exception e
          (logger/warn logger-tag "Failed to parse .mcp.json:" (str mcp-file)
                       (.getMessage e))
          nil)))))

(defn ^:private read-eca-config
  "Reads eca.json from a plugin directory for arbitrary ECA config overrides.
   Applies dynamic string interpolation after JSON parsing."
  [^java.io.File plugin-dir]
  (let [config-file (io/file plugin-dir "eca.json")]
    (when (fs/exists? config-file)
      (try
        (-> (json/parse-string (slurp config-file) true)
            (interpolation/replace-dynamic-strings-in-data (str plugin-dir) nil))
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
  "Reads commands/*.md from a plugin directory and returns a vector of
   {:path ... :plugin <plugin-name>} entries. `plugin-name` may be nil
   (e.g. for tests calling discover-components without a name)."
  [^java.io.File plugin-dir plugin-name]
  (let [commands-dir (io/file plugin-dir "commands")]
    (when (fs/exists? commands-dir)
      (->> (fs/glob commands-dir "**" {:follow-links true})
           (keep (fn [file]
                   (when (and (not (fs/directory? file))
                              (string/ends-with? (str (fs/file-name file)) ".md"))
                     (cond-> {:path (str (fs/canonicalize file))}
                       plugin-name (assoc :plugin plugin-name)))))
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
  "Returns skill directories from a plugin directory as a vector of
   {:dir <absolute-path> :plugin <plugin-name>} entries. `plugin-name` may be nil."
  [^java.io.File plugin-dir plugin-name]
  (let [skills-dir (io/file plugin-dir "skills")]
    (when (fs/exists? skills-dir)
      [(cond-> {:dir (str (fs/canonicalize skills-dir))}
         plugin-name (assoc :plugin plugin-name))])))

;; -- Discovery and resolution --

(defn ^:private discover-components
  "Walks a plugin directory and discovers all components.
   `plugin-name` is the marketplace name of the plugin and is attached to
   components that support user-invocation namespacing (commands, skill dirs).
   Returns a config-ready map:
   {:config-fragment {...}  — deep-mergeable into ECA config (mcpServers, hooks, pluginSkillDirs, eca.json overrides)
    :agents {...}           — agent-name -> agent-config map (merged alongside markdown agents)
    :commands [{:path ... :plugin ...}] — appended to :commands config vector
    :rules [{:path ...}]}   — appended to :rules config vector"
  ([^java.io.File plugin-dir]
   (discover-components plugin-dir nil))
  ([^java.io.File plugin-dir plugin-name]
   (let [mcp-servers (read-mcp-servers plugin-dir)
         hooks (read-hooks plugin-dir plugin-name)
         eca-config (read-eca-config plugin-dir)
         skill-dirs (read-skill-dirs plugin-dir plugin-name)
         config-fragment (cond-> (or eca-config {})
                           (seq mcp-servers) (assoc :mcpServers mcp-servers)
                           (seq hooks) (assoc :hooks hooks)
                           (seq skill-dirs) (update :pluginSkillDirs (fnil into []) skill-dirs))]
     {:config-fragment config-fragment
      :agents (read-agents plugin-dir)
      :commands (read-commands plugin-dir plugin-name)
      :rules (read-rules plugin-dir)})))

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
  "Extracts plugin sources from config, filtering out reserved install keys.
   Returns a seq of [source-name source-url] pairs."
  [plugins-config]
  (->> plugins-config
       (remove (comp #{"install" "installMode"} name key))
       (keep (fn [[source-name source-config]]
               (when-let [source-url (if (map? source-config)
                                       (get source-config :source)
                                       nil)]
                 [(name source-name) source-url])))))

(def ^:private empty-result
  {:config-fragment {} :agents {} :commands [] :rules []})

(defn ^:private plugin-dependencies
  "Returns the plugin refs a plugin depends on: the union of the marketplace
   entry's `dependencies` and the plugin's .eca-plugin/plugin.json `dependencies`."
  [entry ^java.io.File plugin-dir]
  (->> (concat (:dependencies entry)
               (:dependencies (read-plugin-manifest plugin-dir)))
       (filter string?)
       (distinct)
       (vec)))

(defn ^:private ambiguous-plugin
  [plugin-name refs]
  (ex-info (str "Plugin `" plugin-name "` is ambiguous. Use one of: "
                (string/join ", " (map #(str "`" % "`") (sort refs))) ".")
           {:plugin-name plugin-name :refs (vec (sort refs))}))

(defn ^:private resolve-ref
  "Resolves a plugin ref against resolved sources, rejecting ambiguous bare names.
   Qualified refs match only the named source. Returns zero or one matches with
   {:plugin-name :source-name :source-dir :plugin-dir :entry} (plugin-dir may be nil)."
  [resolved-sources ^String plugin-ref]
  (let [{:keys [plugin-name marketplace]} (parse-plugin-arg plugin-ref)
        matches (vec
                 (for [{:keys [source-name source-dir marketplace-plugins]} resolved-sources
                       :when (or (nil? marketplace) (= marketplace source-name))
                       :let [entry (find-plugin-entry plugin-name marketplace-plugins)]
                       :when entry]
                   {:plugin-name plugin-name
                    :source-name source-name
                    :source-dir source-dir
                    :plugin-dir (resolve-plugin-dir source-dir entry)
                    :entry entry}))]
    (when (and (nil? marketplace) (> (count matches) 1))
      (throw (ambiguous-plugin plugin-name
                               (map #(str plugin-name "@" (:source-name %)) matches))))
    matches))

(defn ^:private expand-install-list
  "Expands install plugin refs with their transitive dependencies (breadth-first).
   Dedupes by [plugin source] so shared dependencies and cycles resolve once.
   Returns plugins ordered so dependencies merge before their dependents and
   directly installed plugins merge last, winning config conflicts. Each item:
   {:plugin-name :source-name :plugin-dir :entry :auto? :required-by}."
  [resolved-sources install]
  (loop [queue (mapv (fn [plugin-ref] {:ref plugin-ref}) install)
         visited #{}
         direct []
         auto []]
    (if-let [{plugin-ref :ref :keys [auto? required-by]} (first queue)]
      (let [rest-queue (subvec queue 1)
            matches (resolve-ref resolved-sources plugin-ref)]
        (if (empty? matches)
          (do (if auto?
                (logger/warn logger-tag "Plugin dependency not found in any configured marketplace:"
                             plugin-ref (str "(dependency of " required-by ")"))
                (logger/debug logger-tag "Plugin not found in any source:" plugin-ref))
              (recur rest-queue visited direct auto))
          (let [new-matches (remove #(contains? visited [(:plugin-name %) (:source-name %)]) matches)
                valid (filterv :plugin-dir new-matches)
                dep-items (vec (for [{:keys [plugin-name entry plugin-dir]} valid
                                     dep-ref (plugin-dependencies entry plugin-dir)]
                                 {:ref dep-ref :auto? true :required-by plugin-name}))
                emitted (mapv #(assoc % :auto? (boolean auto?) :required-by required-by) valid)]
            (doseq [{:keys [plugin-name source-dir]} (remove :plugin-dir new-matches)]
              (logger/warn logger-tag "Plugin directory not found:" plugin-name "in" (str source-dir)))
            (recur (into rest-queue dep-items)
                   (into visited (map (fn [{:keys [plugin-name source-name]}] [plugin-name source-name])) new-matches)
                   (if auto? direct (into direct emitted))
                   (if auto? (into auto emitted) auto)))))
      (into (vec (rseq auto)) direct))))

(defn ^:private resolve-sources!
  "Resolves pinned Git snapshots or local dirs and reads marketplace data.
   Source failures propagate: dropping a source could make a bare ref look unique."
  [sources]
  (mapv (fn [[source-name source-url]]
          (logger/info logger-tag "Resolving plugin source:" source-name source-url)
          (try
            (let [{:keys [dir commit]} (or (resolve-source! source-url)
                                          (throw (ex-info "Source directory not found." {:source-url source-url})))]
              {:source-name source-name
               :source-url source-url
               :source-dir dir
               :commit commit
               :marketplace-plugins (read-marketplace dir true)})
            (catch Exception e
              (throw (ex-info (str "Could not resolve marketplace `" source-name "`: " (ex-message e))
                              {:source-name source-name :source-url source-url}
                              e)))))
        sources))

(defn resolve-all!
  "Main entry point: resolves all plugin sources, reads marketplaces,
   expands the install list with transitive plugin dependencies and
   discovers components from all resolved plugins.
   Returns a merged result with :config-fragment, :agents, :commands, :rules."
  [plugins-config]
  (if (or (nil? plugins-config) (empty? plugins-config))
    empty-result
    (let [install (get plugins-config "install" [])
          sources (parse-sources plugins-config)]
      (if (empty? install)
        (do (logger/debug logger-tag "No plugins in install, skipping")
            empty-result)
        (let [resolved-sources (resolve-sources! sources)
              expanded (expand-install-list resolved-sources install)
              components (doall
                          (for [{:keys [plugin-name source-name plugin-dir required-by]} expanded]
                            (do (if required-by
                                  (logger/info logger-tag "Loading plugin:" plugin-name "from" source-name
                                               (str "(dependency of " required-by ")"))
                                  (logger/info logger-tag "Loading plugin:" plugin-name "from" source-name))
                                (interpolation/register-plugin-dir! (str plugin-dir))
                                (discover-components plugin-dir plugin-name))))]
          (merge-components components))))))

(defn list-marketplace-plugins
  "Lists available plugins. Legacy bare install refs mark only unique matches."
  [plugins-config]
  (when (seq plugins-config)
    (let [installed-set (set (get plugins-config "install" []))
          sources (resolve-sources! (parse-sources plugins-config))
          name-counts (frequencies (mapcat #(map :name (:marketplace-plugins %)) sources))]
      (vec
       (for [{:keys [source-name source-url commit marketplace-plugins]} sources
             plugin marketplace-plugins
             :let [plugin-name (:name plugin)]]
         {:name plugin-name
          :description (:description plugin)
          :source-name source-name
          :source-url source-url
          :commit commit
          :installed? (or (contains? installed-set (str plugin-name "@" source-name))
                          (and (= 1 (get name-counts plugin-name))
                               (contains? installed-set plugin-name)))})))))

(defn update-source!
  "Explicitly updates a configured Git marketplace pin after data-only validation.
   Returns {:status :ok/:error :message ...}; activation requires a restart."
  [plugins-config source-name]
  (try
    (if-let [source-url (get (into {} (parse-sources plugins-config)) source-name)]
      (if (git-url? source-url)
        (let [{:keys [commit previous-commit]} (git/update! source-url parse-marketplace)]
          {:status :ok
           :message (str "Marketplace `" source-name "` "
                         (if (= commit previous-commit)
                           (str "is already up to date at `" commit "`.")
                           (str "pin updated from `" (or previous-commit "unpinned") "` to `" commit "`."))
                         " Restart ECA to apply this commit to all plugins from this marketplace.")})
        {:status :error
         :message (str "Marketplace `" source-name "` is an unpinned local development directory; it cannot be updated with /plugin-update.")})
      {:status :error
       :message (str "Unknown marketplace `" source-name "`. Use a configured marketplace source name.")})
    (catch Exception e
      {:status :error
       :message (str "Could not update marketplace `" source-name "`: " (ex-message e)
                     " The existing pin was not changed.")})))

(defn ^:private read-global-install
  "Reads only global install entries, without interpolation or merged project config."
  []
  (let [file (config/global-config-file)]
    (try
      (let [global-config (if (.exists file)
                            (binding [json.factory/*json-factory* (json.factory/make-json-factory
                                                                  {:allow-comments true})]
                              (json/parse-string (slurp file)))
                            {})
            install (get-in global-config ["plugins" "install"] [])]
        (when-not (and (map? global-config) (vector? install) (every? string? install))
          (throw (ex-info "Expected plugins.install to be an array of plugin refs." {})))
        install)
      (catch Exception e
        (throw (ex-info (str "Could not read the global config file at `" file "`. "
                             "Fix the JSON error, then retry. " (ex-message e))
                        {:file (str file)} e))))))

(defn ^:private find-plugin-in-marketplaces
  "Resolves a unique plugin, restricting qualified refs to the selected source."
  [plugins-config plugin-name marketplace-filter]
  (let [sources (cond->> (parse-sources plugins-config)
                  marketplace-filter (filter #(= marketplace-filter (first %))))
        resolved-sources (resolve-sources! sources)]
    (first (resolve-ref resolved-sources
                        (str plugin-name (when marketplace-filter (str "@" marketplace-filter)))))))

(defn install-plugin!
  "Installs a uniquely resolved plugin as name@marketplace in global config only.
   `input` is either 'plugin-name' or 'plugin-name@marketplace'.
   Returns {:status :ok/:error, :message ...}."
  [plugins-config ^String input]
  (try
    (let [{:keys [plugin-name marketplace]} (parse-plugin-arg input)
          sources (parse-sources plugins-config)]
      (if (empty? sources)
        {:status :error
         :message "No plugin marketplaces configured. Add plugin sources to your config under the `plugins` key."}
        (if-let [found (find-plugin-in-marketplaces plugins-config plugin-name marketplace)]
          (let [qualified-ref (str plugin-name "@" (:source-name found))
                global-install (read-global-install)
                current-install (set (concat global-install (get plugins-config "install" [])))
                other-refs (filter (fn [ref]
                                     (let [parsed (parse-plugin-arg ref)]
                                       (and (= plugin-name (:plugin-name parsed))
                                            (:marketplace parsed)
                                            (not= qualified-ref ref))))
                                   current-install)
                migrate? (some #{plugin-name} global-install)]
            (cond
              (seq other-refs)
              {:status :error
               :message (str "Plugin `" plugin-name "` is installed from another marketplace as "
                             (string/join ", " (sort other-refs))
                             ". Uninstall that entry before selecting `" qualified-ref "`.")}

              (and (contains? current-install qualified-ref) (not migrate?))
              {:status :error
               :message (str "Plugin `" qualified-ref "` is already installed.")}

              (and (contains? current-install plugin-name) (not migrate?))
              {:status :error
               :message (str "Plugin `" plugin-name "` has a bare install entry in another config source. "
                             "Replace it there with `" qualified-ref "` to preserve marketplace identity.")}

              :else
              (let [migrated (mapv #(if (= plugin-name %) qualified-ref %) global-install)
                    new-install (if (some #{qualified-ref} migrated)
                                  (vec (distinct migrated))
                                  (conj migrated qualified-ref))]
                (config/update-global-config! {:plugins {:install new-install}})
                {:status :ok
                 :message (str "Plugin `" qualified-ref "` installed from **" (:source-name found)
                               "**. Restart ECA to activate it.")})))
          {:status :error
           :message (if marketplace
                      (str "Plugin `" plugin-name "` not found in marketplace `" marketplace "`.")
                      (str "Plugin `" plugin-name "` not found in any configured marketplace."))})))
    (catch Exception e
      {:status :error
       :message (str "Could not install plugin `" input "`: " (ex-message e))})))

(defn uninstall-plugin!
  "Removes an exact global ref, or a uniquely identifiable installed bare name.
   Exact refs take precedence. Project-only entries are never written globally.
   Returns {:status :ok/:error, :message ...}."
  [plugins-config ^String input]
  (try
    (let [global-install (read-global-install)
          installed (set (concat global-install (get plugins-config "install" [])))
          {:keys [plugin-name marketplace]} (parse-plugin-arg input)
          matches (when (and (nil? marketplace) (not (contains? installed input)))
                    (filter #(= plugin-name (:plugin-name (parse-plugin-arg %))) installed))
          _ (when (> (count matches) 1)
              (throw (ambiguous-plugin plugin-name matches)))
          plugin-ref (or (first matches) input)]
      (cond
        (some #{plugin-ref} global-install)
        (do
          (config/update-global-config!
           {:plugins {:install (filterv #(not= plugin-ref %) global-install)}})
          {:status :ok
           :message (str "Global install entry for plugin `" plugin-ref "` removed. "
                         "Other config sources can still install it; remove the entry there too. Restart ECA to apply.")})

        (contains? installed plugin-ref)
        {:status :error
         :message (str "Plugin `" plugin-ref "` has no global install entry. "
                       "Remove it from plugins.install in its source config (for example, ECA_CONFIG, initialization options, or project config).")}

        :else
        {:status :error
         :message (str "Plugin `" input "` is not installed.")}))
    (catch Exception e
      {:status :error :message (ex-message e)})))
