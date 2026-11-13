(ns eca.features.tools.fetch-rule
  (:require
   [clojure.string :as string]
   [eca.features.built-in-command :as built-in-command]
   [eca.features.prompt :as f.prompt]
   [eca.features.rules :as f.rules]
   [eca.features.tools.path-rules :as f.tools.path-rules]
   [eca.features.tools.util :as tools.util]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

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
        rule (f.rules/find-rule-by-id config roots rule-id agent full-model)
        match-info (when rule
                     (f.rules/match-path-scoped-rule rule roots target-path))]
    (cond
      (nil? rule)
      {:error true
       :contents [{:type :text
                   :text (format "Rule id '%s' not found in the current path-scoped rules catalog. Use the exact id from the catalog or /rules command." rule-id)}]}

      (not (:match? match-info))
      {:error true
       :contents [{:type :text
                   :text (path-mismatch-message rule match-info)}]}

      (f.tools.path-rules/validated-rule? db chat-id rule-id)
      {:error false
       :contents [{:type :text
                   :text (str "**" (:name rule) "** — already loaded in this chat, reuse the previously fetched content.")}]}

      :else
      (do
        (f.tools.path-rules/record-validated-rule! db* chat-id rule match-info)
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
    (when-let [rules (seq (f.rules/path-scoped-rules config roots agent-name full-model))]
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
                      (seq (f.rules/path-scoped-rules config roots agent full-model))))
      :summary-fn (fn [{:keys [args]}]
                    (if-let [rule-id (get args "id")]
                      (format "Fetching rule '%s'" rule-id)
                      "Fetching rule"))}}))

(def module
  {:id :eca/rules
   :description "Rules: global, project and path-scoped."
   :config-spec {:rules {:type :array :default []}}
   :config-defaults {:toolCall {:approval {:allow {"eca__fetch_rule" {}}}}}
   :system-prompts [{:id :eca/rules :content #'f.prompt/rules-section}]
   ;; fetch_rule exists only when the chat has path-scoped rules
   :tools (fn [{:keys [config db chat-id agent]}]
            (tools.util/->module-tools (definitions config db chat-id agent)))
   :commands (built-in-command/commands
              [["rules" "List available rules and their filtering metadata (agent, model, paths)." []]])})
