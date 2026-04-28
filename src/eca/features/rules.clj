(ns eca.features.rules
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [babashka.fs :as fs]
   [eca.config :as config]
   [eca.interpolation :as interpolation]
   [eca.logger :as logger]
   [eca.shared :as shared :refer [assoc-some]])
  (:import
   [java.nio.file FileSystems Path PathMatcher]))

(set! *warn-on-reflection* true)

(defn ^:private normalize-string-or-seq
  [field]
  (cond
    (and (string? field) (not (string/blank? field))) [(string/trim field)]
    (sequential? field) (->> field
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
                   (when (shared/path-inside-root? path root)
                     root))))
         (sort-by #(count (str %)) >)
         first)))

(defn ^:private rule-file->rule
  ([type path content]
   (rule-file->rule type path content {}))
  ([type path content {:keys [workspace-root]}]
   (let [path (shared/normalize-path path)
         workspace-root (some-> workspace-root shared/normalize-path)
         base-rule (cond-> {:id path
                            :name (fs/file-name path)
                            :path path
                            :type type
                            :scope (if workspace-root :project :global)}
                     workspace-root (assoc :workspace-root workspace-root))]
     (try
       (let [{:keys [body agent model paths enforce]} (shared/parse-md content)
             agents (normalize-string-or-seq agent)
             models (normalize-string-or-seq model)
             parsed-paths (normalize-string-or-seq paths)
             parsed-enforce (normalize-string-or-seq enforce)]
         (assoc-some (assoc base-rule :content body)
                     :agents agents
                     :models models
                     :paths parsed-paths
                     :enforce parsed-enforce))
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
  (when (shared/path-inside-root? path root)
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
        absolute? (and input-path (fs/absolute? input-path))
        normalized-path (when absolute? (shared/normalize-path input-path))
        workspace-root (cond
                         (not absolute?) nil
                         (= :project (:scope rule)) (:workspace-root rule)
                         (= :global (:scope rule)) (workspace-root-for-path roots normalized-path))
        relative-path (when workspace-root
                        (relative-path-within-root workspace-root normalized-path))
        matched-pattern (when relative-path
                          (some #(when (glob-pattern-matches-path? % relative-path) %) (:paths rule)))
        reason (cond
                 (not absolute?) :path-not-absolute
                 (and (= :project (:scope rule)) (nil? relative-path)) :outside-rule-workspace
                 (nil? relative-path) :outside-workspaces
                 (not matched-pattern) :pattern-mismatch)]
    {:match? (boolean matched-pattern)
     :reason reason
     :path (or normalized-path input-path)
     :workspace-root workspace-root
     :relative-path relative-path
     :matched-pattern matched-pattern
     :paths (:paths rule)}))

(defn ^:private rule-files [path]
  (cond
    (not (fs/exists? path)) []
    (fs/directory? path) (remove fs/directory? (fs/glob path "**" {:follow-links true}))
    :else [path]))

(defn ^:private rule-file [type file opts]
  (let [content (interpolation/replace-dynamic-strings (slurp (str file)) (str (fs/parent file)) nil)]
    (rule-file->rule type
                     (fs/canonicalize file)
                     content
                     {:workspace-root (:workspace-root opts)})))

(defn ^:private global-file-rules []
  (let [xdg-config-home (or (config/get-env "XDG_CONFIG_HOME")
                            (io/file (config/get-property "user.home") ".config"))
        rules-dir (io/file xdg-config-home "eca" "rules")]
    (keep #(rule-file :user-global-file % {})
          (rule-files rules-dir))))

(defn ^:private local-file-rules [roots]
  (->> roots
       (mapcat (fn [{:keys [uri]}]
                 (let [workspace-root (shared/normalize-path (shared/uri->filename uri))]
                   (keep #(rule-file :user-local-file % {:workspace-root workspace-root})
                         (rule-files (fs/file workspace-root ".eca" "rules"))))))))

(defn ^:private config-rule-paths
  [roots path]
  (if (fs/absolute? path)
    [{:path path
      :workspace-root (workspace-root-for-path roots path)}]
    (map (fn [{:keys [uri]}]
           (let [workspace-root (shared/normalize-path (shared/uri->filename uri))]
             {:path (fs/file workspace-root path)
              :workspace-root workspace-root}))
         roots)))

(defn ^:private config-rules [config roots]
  (->> (get config :rules)
       (mapcat (fn [{:keys [path]}]
                 (config-rule-paths roots (str (fs/expand-home path)))))
       (mapcat (fn [{:keys [path workspace-root]}]
                 (keep #(rule-file :user-config % {:workspace-root workspace-root})
                       (rule-files path))))))

(defn ^:private loaded-rules
  [config roots]
  (concat (config-rules config roots)
          (global-file-rules)
          (local-file-rules roots)))

(defn ^:private filter-rules
  [rules agent-name full-model]
  (filter #(and (agent-matches? % agent-name)
                (model-matches? % full-model))
          rules))

(defn all-rules
  "Loads all rules from disk once, filters by agent/model, and partitions
   into {:static [...] :path-scoped [...]}. Use this when you need both
   partitions to avoid loading rule files from disk twice."
  [config roots agent-name full-model]
  (let [filtered (filter-rules (loaded-rules config roots) agent-name full-model)]
    {:static (vec (remove :paths filtered))
     :path-scoped (vec (filter :paths filtered))}))

(defn path-scoped-rules
  "Returns rules with :paths — these are rendered as a catalog in the
   system prompt and their full content is loaded on demand via fetch_rule."
  [config roots agent-name full-model]
  (filter-rules (filter :paths (loaded-rules config roots)) agent-name full-model))

(defn matching-path-scoped-rules
  "Returns all path-scoped rules that match `target-path`, together with the
   detailed match info returned by `match-path-scoped-rule`."
  [config roots agent-name full-model target-path]
  (->> (path-scoped-rules config roots agent-name full-model)
       (keep (fn [rule]
               (let [match-info (match-path-scoped-rule rule roots target-path)]
                 (when (:match? match-info)
                   {:rule rule
                    :match match-info}))))
       vec))

(defn find-rule-by-id
  "Finds a path-scoped rule by id after applying the same agent/model
   filtering used for the fetch_rule catalog. Returns the rule map or nil
   if not found or not available for the current chat context."
  [config roots rule-id agent-name full-model]
  (first (filter #(= rule-id (:id %))
                 (path-scoped-rules config roots agent-name full-model))))
