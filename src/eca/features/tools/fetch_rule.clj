(ns eca.features.tools.fetch-rule
  (:require
   [clojure.string :as string]
   [eca.features.prompt :as f.prompt]
   [eca.features.rules :as f.rules]
   [eca.features.tools.path-rules :as f.tools.path-rules]
   [eca.features.tools.util :as tools.util]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private java-glob-note
  (str "Path matching uses Java NIO `PathMatcher` glob syntax against workspace-relative paths. "
       "Unlike most editor and shell-style glob matchers, patterns containing `**/` do not match the zero-directory case: "
       "`**/*.clj` does not match `foo.clj`, and `src/**/*.clj` matches nested files under `src/` but not `src/foo.clj`."))

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

    (str "Rule id '" id "' does not apply to path '" path "'.")))

(defn ^:private fetch-rule
  [arguments {:keys [all-tools db db* config chat-id agent]}]
  (let [rule-id (get arguments "id")
        target-path (get arguments "path")
        roots (:workspace-folders db)
        full-model (get-in db [:chats chat-id :model])
        rule (f.rules/find-rule-by-id config roots rule-id agent full-model)]
    (if rule
      (let [match-info (f.rules/match-path-scoped-rule rule roots target-path)]
        (if-not (:match? match-info)
          {:error true
           :contents [{:type :text
                       :text (path-mismatch-message rule match-info)}]}
          (if (f.tools.path-rules/validated-rule? db chat-id (:path match-info) rule-id)
            {:error false
             :contents [{:type :text
                         :text (str "**" (:name rule) "** — already loaded for this path, reuse the previously fetched content.")}]}
            (do
              (f.tools.path-rules/record-validated-rule! db* chat-id rule match-info)
              (let [header (str "**Rule**: " (:name rule) "\n"
                                "**ID**: " (:id rule) "\n"
                                "**Scope**: " (some-> (:scope rule) name) "\n"
                                (when-let [wr (:workspace-root rule)]
                                  (str "**Workspace root**: " wr "\n"))
                                "**Path**: " (:path match-info) "\n"
                                "**Matched pattern**: " (:matched-pattern match-info) "\n"
                                "**Relative path**: " (:relative-path match-info) "\n")]
                (if-let [content (rendered-rule-content rule all-tools db chat-id)]
                  {:error false
                   :contents [{:type :text
                               :text (str header "\n" content)}]}
                  {:error false
                   :contents [{:type :text
                               :text (str header "\nThis rule contains no usable content for the current chat context and does not need to be loaded again for this path.")}]}))))))
      {:error true
       :contents [{:type :text
                   :text (format "Rule id '%s' not found in the current path-scoped rules catalog. Use the exact id from the catalog or /rules command." rule-id)}]})))

(defn ^:private describe-rule
  [{rule-name :name :keys [id paths enforce scope workspace-root]}]
  (str "- " rule-name "\n"
       "  id: " id "\n"
       "  scope: " (some-> scope name) "\n"
       (when workspace-root
         (str "  workspace-root: " workspace-root "\n"))
       "  paths: " (string/join ", " paths) "\n"
       "  enforce: " (string/join ", " (or enforce ["modify"]))))

(defn ^:private build-description
  [config db chat-id agent-name]
  (let [base-description (tools.util/read-tool-description "fetch_rule")
        roots (:workspace-folders db)
        full-model (get-in db [:chats chat-id :model])
        rules (f.rules/path-scoped-rules config roots agent-name full-model)]
    (if (seq rules)
      (str base-description
           "\n\nAvailable path-scoped rules"
           (when chat-id
             " for the current chat")
           ":\n"
           (->> rules
                (map describe-rule)
                (string/join "\n")))
      base-description)))

(defn definitions
  [config db chat-id agent-name]
  {"fetch_rule"
   {:description (build-description config db chat-id agent-name)
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
                    "Fetching rule"))}})
