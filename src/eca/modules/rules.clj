(ns eca.modules.rules
  "The `:eca/rules` module: rules from the config and from global and project
   files, given to the model in the system prompt, and path-scoped rules, which
   the model fetches with the `fetch_rule` tool before reading or changing the
   files they apply to."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [babashka.fs :as fs]
   [eca.config :as config]
   [eca.features.prompt :as f.prompt]
   [eca.features.tools.util :as tools.util]
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
                            (io/file (config/user-home) ".config"))
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

;; --- Path-scoped rules

(def ^:private validated-path-rules-key :validated-path-rules)

(defn fetch-rule-available?
  [all-tools]
  (tools.util/tool-available? all-tools "eca__fetch_rule"))

(defn record-validated-rule!
  [db* chat-id rule _match-info]
  (swap! db* update-in [:chats chat-id validated-path-rules-key] (fnil conj #{}) (:id rule)))

(defn validated-rule?
  [db chat-id rule-id]
  (contains? (get-in db [:chats chat-id validated-path-rules-key] #{}) rule-id))

(defn enforce-on-modify?
  "Returns true if the rule should be enforced before file modification.
   Default (nil enforce) is modify — backwards compatible."
  [rule]
  (let [enforce (:enforce rule)]
    (or (nil? enforce)
        (some #(= "modify" %) enforce))))

(defn enforce-on-read?
  "Returns true if the rule should be enforced before file reading.
   Default (nil enforce) is false — only opt-in via explicit `enforce: read`."
  [rule]
  (some #(= "read" %) (:enforce rule)))

(defn applicable-path-scoped-rules
  [config db chat-id agent all-tools target-path]
  (when (and (fetch-rule-available? all-tools)
             (not (string/blank? target-path)))
    (let [full-model (get-in db [:chats chat-id :model])]
      (matching-path-scoped-rules config
                                          (:workspace-folders db)
                                          agent
                                          full-model
                                          target-path))))

(defn ^:private missing-path-scoped-rules
  [config db chat-id agent all-tools target-path enforce?]
  (let [target-path (shared/normalize-path target-path)]
    (when target-path
      (->> (applicable-path-scoped-rules config db chat-id agent all-tools target-path)
           (filter (fn [{:keys [rule]}] (enforce? rule)))
           (remove (fn [{:keys [rule]}] (validated-rule? db chat-id (:id rule))))
           vec
           not-empty))))

(defn missing-path-scoped-rules-for-modify
  "Returns rules that require fetching before modifying the target path."
  [config db chat-id agent all-tools target-path]
  (missing-path-scoped-rules config db chat-id agent all-tools target-path enforce-on-modify?))

(defn missing-path-scoped-rules-for-read
  "Returns rules that require fetching before reading the target path."
  [config db chat-id agent all-tools target-path]
  (missing-path-scoped-rules config db chat-id agent all-tools target-path enforce-on-read?))

(defn missing-path-scoped-rules-error
  [target-path missing-rules action]
  (tools.util/single-text-content
   (str "Path-scoped rules must be fetched before " action " '" target-path "'.\n"
        "Fetch the missing rule(s) first:\n"
        (->> missing-rules
             (map (fn [{{:keys [id name]} :rule {:keys [matched-pattern]} :match}]
                    (str "- " name "\n"
                         "  id: " id "\n"
                         "  path: " target-path "\n"
                         "  matched-pattern: " matched-pattern "\n"
                         "  next: call `fetch_rule` with this exact `id` and `path`.")))
             (string/join "\n")))
   :error))

(defn require-fetched-path-scoped-rules
  [target-path {:keys [config db chat-id agent all-tools]}]
  (when-let [missing-rules (missing-path-scoped-rules-for-modify config db chat-id agent all-tools target-path)]
    (missing-path-scoped-rules-error (shared/normalize-path target-path) missing-rules "modifying")))

(defn require-fetched-path-scoped-rules-for-read
  [target-path {:keys [config db chat-id agent all-tools]}]
  (when-let [missing-rules (missing-path-scoped-rules-for-read config db chat-id agent all-tools target-path)]
    (missing-path-scoped-rules-error (shared/normalize-path target-path) missing-rules "reading")))

;; --- The fetch_rule tool and the module

(defn ^:private rendered-rule-content
  [{:keys [content name]} all-tools db chat-id]
  (when-let [rendered (shared/safe-selmer-render content
                                                 (f.prompt/->base-selmer-ctx all-tools chat-id db)
                                                 (str "path-scoped-rule:" name)
                                                 nil)]
    (when-not (string/blank? rendered)
      rendered)))

(defn ^:private path-mismatch-message
  [{:keys [id scope workspace-root paths]} {:keys [path reason relative-path]}]
  (let [java-glob-note (str "Path matching uses Java NIO `PathMatcher` glob syntax against workspace-relative paths. "
                            "Unlike most editor and shell-style glob matchers, patterns containing `**/` do not match the zero-directory case: "
                            "`**/*.clj` does not match `foo.clj`, and `src/**/*.clj` matches nested files under `src/` but not `src/foo.clj`.")]
    (case reason
      :path-not-absolute
      (str "Path '" path "' must be absolute. Pass the exact absolute file or directory path you plan to work with.\n"
           java-glob-note)

      :outside-rule-workspace
      (str "Rule id '" id "' does not apply to path '" path "' because it is outside the rule workspace root '"
           workspace-root
           "'.\n"
           "Allowed patterns: " (string/join ", " paths) "\n"
           java-glob-note)

      :outside-workspaces
      (str "Rule id '" id "' does not apply to path '" path "' because global path-scoped rules are matched only against the current workspace roots.\n"
           "Allowed patterns: " (string/join ", " paths) "\n"
           java-glob-note)

      :pattern-mismatch
      (str "Rule id '" id "' does not apply to path '" path "'.\n"
           "Checked relative path: " relative-path
           (when (= :project scope)
             (str " (from workspace root '" workspace-root "')"))
           "\nAllowed patterns: " (string/join ", " paths) "\n"
           java-glob-note)

      (str "Rule id '" id "' does not apply to path '" path "'."))))

(defn ^:private fetch-rule
  [arguments {:keys [all-tools db db* config chat-id agent]}]
  (let [rule-id (get arguments "id")
        target-path (get arguments "path")
        roots (:workspace-folders db)
        full-model (get-in db [:chats chat-id :model])
        rule (find-rule-by-id config roots rule-id agent full-model)
        match-info (when rule
                     (match-path-scoped-rule rule roots target-path))]
    (cond
      (nil? rule)
      {:error true
       :contents [{:type :text
                   :text (format "Rule id '%s' not found in the current path-scoped rules catalog. Use the exact id from the catalog or /rules command." rule-id)}]}

      (not (:match? match-info))
      {:error true
       :contents [{:type :text
                   :text (path-mismatch-message rule match-info)}]}

      (validated-rule? db chat-id rule-id)
      {:error false
       :contents [{:type :text
                   :text (str "**" (:name rule) "** — already loaded in this chat, reuse the previously fetched content.")}]}

      :else
      (do
        (record-validated-rule! db* chat-id rule match-info)
        (let [header (str "**Rule**: " (:name rule) "\n"
                          "**Path**: " (:path match-info) "\n"
                          "**Matched pattern**: " (:matched-pattern match-info) "\n")]
          (if-let [content (rendered-rule-content rule all-tools db chat-id)]
            {:error false
             :contents [{:type :text
                         :text (str header "\n" content)}]}
            {:error false
             :contents [{:type :text
                         :text (str header "\nThis rule contains no usable content for the current chat context and does not need to be loaded again in this chat.")}]}))))))

(defn ^:private describe-rule
  [{rule-name :name :keys [id paths enforce scope workspace-root]}]
  (str "- " rule-name "\n"
       "  id: " id "\n"
       "  scope: " (some-> scope name) "\n"
       (when workspace-root
         (str "  workspace-root: " workspace-root "\n"))
       "  paths: " (string/join ", " paths) "\n"
       "  enforce: " (string/join ", " (or enforce ["modify"]))))

(defn ^:private rules-catalog
  "The tool description part listing the path-scoped rules available to the
   chat, or nil when there are none."
  [config db chat-id agent-name]
  (let [roots (:workspace-folders db)
        full-model (get-in db [:chats chat-id :model])]
    (when-let [rules (seq (path-scoped-rules config roots agent-name full-model))]
      (str "\n\nAvailable path-scoped rules"
           (when chat-id
             " for the current chat")
           ":\n"
           (->> rules
                (map describe-rule)
                (string/join "\n"))))))

(defn definitions
  [config db chat-id agent-name]
  (let [catalog (rules-catalog config db chat-id agent-name)]
    {"fetch_rule"
     {:description (str (tools.util/read-tool-description "fetch_rule") catalog)
      ;; Kept after a `prompts.tools` override of the description.
      :description-suffix catalog
      :parameters {:type "object"
                   :properties {"id" {:type "string"
                                      :description "The exact rule id from the path-scoped rules catalog"}
                                "path" {:type "string"
                                        :description "The exact absolute file or directory path you plan to work with"}}
                   :required ["id" "path"]}
      :handler #'fetch-rule
      :enabled-fn (fn [{:keys [db config agent chat-id]}]
                    (let [roots (:workspace-folders db)
                          full-model (get-in db [:chats chat-id :model])]
                      (seq (path-scoped-rules config roots agent full-model))))
      :summary-fn (fn [{:keys [args]}]
                    (if-let [rule-id (get args "id")]
                      (format "Fetching rule '%s'" rule-id)
                      "Fetching rule"))}}))

(defn ^:private rules-msg [config roots agent full-model all-tools]
  (let [{static :static path-scoped :path-scoped} (all-rules config roots agent full-model)
        visible-path-scoped (when (tools.util/tool-available? all-tools "eca__fetch_rule")
                              path-scoped)
        format-content-preview (fn [content]
                                 (let [content (or content "")
                                       preview (subs content 0 (min 120 (count content)))]
                                   (str preview (when (> (count content) 120) "..."))))
        format-rule (fn [{rule-name :name :keys [scope path agents models paths content]} include-content?]
                      (str "### " rule-name " (" (some-> scope name) ")\n"
                           "Source: " path "\n"
                           "Agent filter: " (if agents (string/join ", " agents) "all") "\n"
                           "Model filter: " (if models (string/join ", " models) "all") "\n"
                           (when paths
                             (str "Path filter: " (string/join ", " paths) "\n"))
                           (when include-content?
                             (str "Content preview: " (format-content-preview content) "\n"))
                           "\n"))]
    (if (or (seq static) (seq visible-path-scoped))
      (str
       (when (seq static)
         (str "Static rules (full content is included directly in the system prompt):\n\n"
              (reduce (fn [s rule] (str s (format-rule rule true))) "" static)))
       (when (seq visible-path-scoped)
         (str "Path-scoped rules (only a catalog is included in the system prompt; load full content with fetch_rule using the rule id and target path):\n\n"
              (reduce (fn [s rule] (str s (format-rule rule false))) "" visible-path-scoped))))
      "No rules available for the current agent and model.")))

(def module
  {:id :eca/rules
   :description "Rules: global, project and path-scoped."
   :config-spec {:rules {:type :array :default []}}
   :config-defaults {:toolCall {:approval {:allow {"eca__fetch_rule" {}}}}}
   :system-prompts [{:id :eca/rules :content #'f.prompt/rules-section}]
   ;; fetch_rule exists only when the chat has path-scoped rules
   :tools (fn [{:keys [config db chat-id agent]}]
            (tools.util/->module-tools (definitions config db chat-id agent)))
   :commands {"rules" {:description "List available rules and their filtering metadata (agent, model, paths)."
                       :arguments []
                       :handler (fn [{:keys [db* config agent full-model all-tools]} _args]
                                  (rules-msg config (:workspace-folders @db*) agent full-model all-tools))}}})
