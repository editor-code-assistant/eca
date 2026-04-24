(ns eca.features.rules
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [babashka.fs :as fs]
   [eca.config :as config]
   [eca.logger :as logger]
   [eca.shared :as shared])
  (:import
   [java.nio.file FileSystems Path PathMatcher]))

(set! *warn-on-reflection* true)

(defn ^:private normalize-string-or-seq
  [field]
  (cond
    (string? field) (when-not (string/blank? field) [(string/trim field)])
    (sequential? field) (->> field
                             (map str)
                             (map string/trim)
                             (remove string/blank?)
                             vec
                             not-empty)))

(defn ^:private workspace-root-for-path
  [roots path]
  (let [path (shared/normalize-path path)]
    (->> roots
         (keep (fn [{:keys [uri]}]
                 (let [root (shared/normalize-path (shared/uri->filename uri))]
                   (when (and path root (shared/path-inside-root? path root))
                     root))))
         (sort-by #(count (str %)) >)
         first)))

(defn ^:private rule-file->rule
  ([type path content]
   (rule-file->rule type path content {}))
  ([type path content {:keys [scope workspace-root]}]
   (let [path (shared/normalize-path path)
         workspace-root (some-> workspace-root shared/normalize-path)
         base-rule (cond-> {:id path
                            :name (fs/file-name path)
                            :path path
                            :type type
                            :scope (or scope (if workspace-root :project :global))}
                     workspace-root (assoc :workspace-root workspace-root))]
     (try
       (let [{:keys [body agent model paths enforce]} (shared/parse-md content)
             agents (normalize-string-or-seq agent)
             models (normalize-string-or-seq model)
             parsed-paths (normalize-string-or-seq paths)
             parsed-enforce (normalize-string-or-seq enforce)]
         (cond-> (assoc base-rule :content body)
           agents (assoc :agents agents)
           models (assoc :models models)
           parsed-paths (assoc :paths parsed-paths)
           parsed-enforce (assoc :enforce parsed-enforce)))
       (catch Exception e
         (logger/warn "Failed to parse rule file, skipping" (str path) (ex-message e))
         nil)))))

(defn ^:private agent-matches?
  [rule agent-name]
  (let [agents (:agents rule)]
    (or (nil? agents)
        (nil? agent-name)
        (some #(= agent-name %) agents))))

(defn ^:private model-matches?
  "Check if a rule's model patterns match the current full-model string.
   Patterns are regex strings matched against the full model identifier
   (e.g. \"anthropic/claude-sonnet-4-20250514\" or \"github-copilot/.*\" for provider matching).
   Returns true if rule has no :models (matches all models)."
  [rule full-model]
  (if-let [patterns (:models rule)]
    (boolean
     (when full-model
       (some (fn [^String pattern]
               (try
                 (re-find (re-pattern pattern) full-model)
                 (catch Exception e
                   (logger/warn "Invalid model regex pattern" (pr-str pattern) (ex-message e))
                   false)))
             patterns)))
    true))

(defn ^:private relative-path-within-root
  [root path]
  (when (and root path (shared/path-inside-root? path root))
    (str (fs/relativize (fs/path root) (fs/path path)))))

(defn ^:private glob-pattern-matches-path?
  [pattern relative-path]
  (try
    (let [^java.nio.file.FileSystem fs (FileSystems/getDefault)
          ^PathMatcher matcher (.getPathMatcher fs (str "glob:" pattern))
          ^Path relative-path (fs/path relative-path)]
      (.matches matcher relative-path))
    (catch Exception e
      (logger/warn "Invalid rule path glob pattern, skipping match" (pr-str pattern) (ex-message e))
      false)))

(defn match-path-scoped-rule
  "Returns detailed match info for applying a path-scoped rule to a target path.
   `target-path` must be absolute. Matching uses Java NIO PathMatcher glob syntax
   against workspace-relative paths."
  [rule roots target-path]
  (let [input-path (some-> target-path str string/trim not-empty)
        absolute? (boolean (and input-path (fs/absolute? input-path)))
        normalized-path (when absolute? (shared/normalize-path input-path))
        workspace-root (cond
                         (not absolute?) nil
                         (= :project (:scope rule)) (:workspace-root rule)
                         :else (workspace-root-for-path roots normalized-path))
        relative-path (when workspace-root
                        (relative-path-within-root workspace-root normalized-path))
        matched-pattern (when relative-path
                          (some #(when (glob-pattern-matches-path? % relative-path) %) (:paths rule)))
        reason (cond
                 (not absolute?) :path-not-absolute
                 (and (= :project (:scope rule)) (nil? relative-path)) :outside-rule-workspace
                 (nil? relative-path) :outside-workspaces
                 matched-pattern nil
                 :else :pattern-mismatch)]
    {:match? (boolean matched-pattern)
     :reason reason
     :path (or normalized-path input-path)
     :workspace-root workspace-root
     :relative-path relative-path
     :matched-pattern matched-pattern
     :paths (:paths rule)}))

(defn ^:private global-file-rules []
  (let [xdg-config-home (or (config/get-env "XDG_CONFIG_HOME")
                            (io/file (config/get-property "user.home") ".config"))
        rules-dir (io/file xdg-config-home "eca" "rules")]
    (when (fs/exists? rules-dir)
      (keep (fn [file]
              (when-not (fs/directory? file)
                (rule-file->rule :user-global-file
                                 (fs/canonicalize file)
                                 (slurp (fs/file file))
                                 {:scope :global})))
            (fs/glob rules-dir "**" {:follow-links true})))))

(defn ^:private local-file-rules [roots]
  (->> roots
       (mapcat (fn [{:keys [uri]}]
                 (let [workspace-root (shared/normalize-path (shared/uri->filename uri))
                       rules-dir (fs/file workspace-root ".eca" "rules")]
                   (when (fs/exists? rules-dir)
                     (keep (fn [file]
                             (when-not (fs/directory? file)
                               (rule-file->rule :user-local-file
                                                (fs/canonicalize file)
                                                (slurp (fs/file file))
                                                {:scope :project
                                                 :workspace-root workspace-root})))
                           (fs/glob rules-dir "**" {:follow-links true}))))))))

(defn ^:private config-rules [config roots]
  (->> (get config :rules)
       (mapcat
        (fn [{:keys [path]}]
          (if (fs/absolute? path)
            (when (fs/exists? path)
              (let [workspace-root (workspace-root-for-path roots path)]
                [(rule-file->rule :user-config
                                  path
                                  (slurp path)
                                  (cond-> {:scope :global}
                                    workspace-root (assoc :scope :project
                                                          :workspace-root workspace-root)))]))
            (keep (fn [{:keys [uri]}]
                    (let [workspace-root (shared/normalize-path (shared/uri->filename uri))
                          f (fs/file workspace-root path)]
                      (when (fs/exists? f)
                        (rule-file->rule :user-config
                                         (fs/canonicalize f)
                                         (slurp f)
                                         {:scope :project
                                          :workspace-root workspace-root}))))
                  roots))))
       (remove nil?)))

(defn ^:private loaded-rules
  [config roots]
  (concat (config-rules config roots)
          (global-file-rules)
          (local-file-rules roots)))

(defn ^:private filter-rules
  [rules agent-name full-model]
  (->> rules
       (filter #(agent-matches? % agent-name))
       (filter #(model-matches? % full-model))))

(defn all-rules
  "Loads all rules from disk once, filters by agent/model, and partitions
   into {:static [...] :path-scoped [...]}. Use this when you need both
   partitions to avoid loading rule files from disk twice."
  ([config roots]
   (all-rules config roots nil nil))
  ([config roots agent-name]
   (all-rules config roots agent-name nil))
  ([config roots agent-name full-model]
   (let [filtered (filter-rules (loaded-rules config roots) agent-name full-model)]
     {:static (vec (remove :paths filtered))
      :path-scoped (vec (filter :paths filtered))})))

(defn static-rules
  "Returns rules without :paths — these are always active and rendered
   as full content in the system prompt."
  ([config roots]
   (static-rules config roots nil nil))
  ([config roots agent-name]
   (static-rules config roots agent-name nil))
  ([config roots agent-name full-model]
   (filter-rules (remove :paths (loaded-rules config roots)) agent-name full-model)))

(defn path-scoped-rules
  "Returns rules with :paths — these are rendered as a catalog in the
   system prompt and their full content is loaded on demand via fetch_rule."
  ([config roots]
   (path-scoped-rules config roots nil nil))
  ([config roots agent-name]
   (path-scoped-rules config roots agent-name nil))
  ([config roots agent-name full-model]
   (filter-rules (filter :paths (loaded-rules config roots)) agent-name full-model)))

(defn matching-path-scoped-rules
  "Returns all path-scoped rules that match `target-path`, together with the
   detailed match info returned by `match-path-scoped-rule`."
  ([config roots target-path]
   (matching-path-scoped-rules config roots nil nil target-path))
  ([config roots agent-name target-path]
   (matching-path-scoped-rules config roots agent-name nil target-path))
  ([config roots agent-name full-model target-path]
   (->> (path-scoped-rules config roots agent-name full-model)
        (keep (fn [rule]
                (let [match-info (match-path-scoped-rule rule roots target-path)]
                  (when (:match? match-info)
                    {:rule rule
                     :match match-info}))))
        vec)))

(defn find-rule-by-id
  "Finds a path-scoped rule by id after applying the same agent/model
   filtering used for the fetch_rule catalog. Returns the rule map or nil
   if not found or not available for the current chat context."
  ([config roots rule-id]
   (find-rule-by-id config roots rule-id nil nil))
  ([config roots rule-id agent-name]
   (find-rule-by-id config roots rule-id agent-name nil))
  ([config roots rule-id agent-name full-model]
   (first (filter #(= rule-id (:id %))
                  (path-scoped-rules config roots agent-name full-model)))))
