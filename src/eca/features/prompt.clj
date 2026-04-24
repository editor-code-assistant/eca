(ns eca.features.prompt
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.features.skills :as f.skills]
   [eca.features.tools.mcp :as f.mcp]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]
   [eca.shared :refer [multi-str] :as shared])
  (:import
   [java.util Map]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[PROMPT]")

;; Built-in agent prompts are now complete files, not templates
(defn ^:private load-builtin-prompt* [filename]
  (slurp (io/resource (str "prompts/" filename))))

(def ^:private load-builtin-prompt (memoize load-builtin-prompt*))

(defn ^:private compact-prompt-template* [file-path]
  (if (fs/relative? file-path)
    (slurp (io/resource file-path))
    (slurp (io/file file-path))))

(def ^:private compact-prompt-template (memoize compact-prompt-template*))

(defn ^:private get-config-prompt [key agent-name config]
  (or (get-in config [:agent agent-name :prompts key])
      (get-in config [:prompts key])))

(defn ^:private eca-chat-prompt [agent-name config]
  (let [agent-config (get-in config [:agent agent-name])
        subagent-prompt (and (= "subagent" (:mode agent-config))
                             (:systemPrompt agent-config))
        config-prompt (get-config-prompt :chat agent-name config)
        legacy-config-prompt (:systemPrompt agent-config)
        legacy-config-prompt-file (:systemPromptFile agent-config)]
    (cond
      subagent-prompt
      subagent-prompt

      legacy-config-prompt
      legacy-config-prompt

      config-prompt
      config-prompt

      ;; agent with absolute path
      (and legacy-config-prompt-file (string/starts-with? legacy-config-prompt-file "/"))
      (slurp legacy-config-prompt-file)

      ;; Built-in or resource path
      legacy-config-prompt-file
      (load-builtin-prompt (some-> legacy-config-prompt-file (string/replace-first #"prompts/" "")))

      ;; Fallback for unknown agent
      :else
      (load-builtin-prompt "code_agent.md"))))

(defn contexts-str [refined-contexts repo-map* startup-ctx]
  (multi-str
   "<contexts description=\"User-Provided. This content is current and accurate. Treat this as sufficient context for answering the query.\">"
   ""
   (reduce
    (fn [context-str {:keys [type path position content lines-range uri]}]
      (str context-str (case type
                         :file (if lines-range
                                 (format "<file line-start=%s line-end=%s path=\"%s\">%s</file>\n\n"
                                         (:start lines-range)
                                         (:end lines-range)
                                         path
                                         content)
                                 (format "<file path=\"%s\">%s</file>\n\n" path content))
                         :agents-file (multi-str
                                       (format "<agents-file description=\"Primary System Directives & Coding Standards.\" path=\"%s\">" path)
                                       content
                                       "</agents-file>\n\n")
                         :repoMap (format "<repoMap description=\"Workspaces structure in a tree view, spaces represent file hierarchy\" >%s</repoMap>\n\n" @repo-map*)
                         :cursor (format "<cursor description=\"User editor cursor position (line:character)\" path=\"%s\" start=\"%s\" end=\"%s\"/>\n\n"
                                         path
                                         (str (:line (:start position)) ":" (:character (:start position)))
                                         (str (:line (:end position)) ":" (:character (:end position))))
                         :mcpResource (format "<resource uri=\"%s\">%s</resource>\n\n" uri content)
                         "")))
    ""
    refined-contexts)
   (when startup-ctx
     (str "\n<additionalContext from=\"chatStart\">\n" startup-ctx "\n</additionalContext>\n\n"))
   "</contexts>"))

(defn ->base-selmer-ctx
  ([all-tools db]
   (->base-selmer-ctx all-tools nil db))
  ([all-tools chat-id db]
   (merge
    {:workspaceRoots (shared/workspaces-as-str db)
     :isSubagent (boolean (get-in db [:chats chat-id :subagent]))}
    (reduce
     (fn [m tool]
       (assoc m (keyword (str "toolEnabled_" (:full-name tool))) true))
     {}
     all-tools))))

(defn ^:private mcp-instructions-section [db]
  (let [servers-with-instructions
        (->> (:mcp-clients db)
             (keep (fn [[server-name {:keys [status instructions]}]]
                     (when (and (= :running status)
                                (not (string/blank? instructions)))
                       {:name (name server-name)
                        :instructions instructions})))
             seq)]
    (when servers-with-instructions
      (multi-str
       "<mcp-server-instructions description=\"Instructions provided by MCP servers describing their capabilities and usage guidelines.\">"
       ""
       (reduce
        (fn [acc {:keys [name instructions]}]
          (str acc (format "<mcp-server-instruction name=\"%s\">\n%s\n</mcp-server-instruction>\n\n" name instructions)))
        ""
        servers-with-instructions)
       "</mcp-server-instructions>"
       ""))))

(def ^:private volatile-context-types
  "Context types that change between turns and belong in the dynamic prompt block."
  #{:cursor :mcpResource})

(defn ^:private rule-section
  [tag description rendered-rules]
  (when (seq rendered-rules)
    [(format "<%s description=\"%s\">" tag description)
     (string/join "\n" rendered-rules)
     (format "</%s>" tag)
     ""]))

(defn ^:private path-scoped-rule-attrs
  [{rule-name :name :keys [id paths enforce scope workspace-root]}]
  (str (format " id=\"%s\"" id)
       (format " name=\"%s\"" rule-name)
       (format " scope=\"%s\"" (if scope (name scope) "unknown"))
       (when workspace-root
         (format " workspace-root=\"%s\"" workspace-root))
       (format " paths=\"%s\"" (string/join "," paths))
       (format " enforce=\"%s\"" (string/join "," (or enforce ["modify"])))))

(defn ^:private path-scoped-rule-catalog-entry
  [rule]
  (str "<rule" (path-scoped-rule-attrs rule) "/>"))

(defn ^:private path-scoped-rule-sections
  [path-scoped-rules render-entry]
  (let [global-rules (filter #(= :global (:scope %)) path-scoped-rules)
        project-rules (remove #(= :global (:scope %)) path-scoped-rules)]
    (multi-str
     (when (seq global-rules)
       ["<global-path-scoped-rules description=\"Path-scoped rules loaded outside the current workspace.\">"
        (->> global-rules
             (map render-entry)
             (string/join "\n"))
        "</global-path-scoped-rules>"])
     (->> project-rules
          (group-by :workspace-root)
          (sort-by first)
          (map (fn [[workspace-root rules]]
                 (str "<workspace-path-scoped-rules root=\"" workspace-root "\">\n"
                      (->> rules
                           (map render-entry)
                           (string/join "\n"))
                      "\n</workspace-path-scoped-rules>")))))))

(defn ^:private path-scoped-rule-catalog
  [path-scoped-rules]
  (path-scoped-rule-sections path-scoped-rules path-scoped-rule-catalog-entry))

(defn build-static-instructions
  "Builds the stable portion of the system prompt: agent prompt, rules, skills,
   stable contexts, and additional system info. Callers may cache the result in
   [:chats chat-id :prompt-cache :static] and reuse it across turns when no
   inputs (rules, contexts) have changed."
  [refined-contexts static-rules path-scoped-rules skills repo-map* agent-name config chat-id all-tools db]
  (let [selmer-ctx (->base-selmer-ctx all-tools chat-id db)
        stable-contexts (remove #(volatile-context-types (:type %)) refined-contexts)
        rendered-static-rules (keep (fn [{:keys [name content scope]}]
                                      (when-let [rendered (shared/safe-selmer-render content selmer-ctx (str "rule:" name) nil)]
                                        (when-not (string/blank? rendered)
                                          {:scope (if (= :global scope) :global :project)
                                           :content (format "<rule name=\"%s\">%s</rule>" name rendered)})))
                                    static-rules)
        fetch-rule-available? (tools.util/tool-available? all-tools "eca__fetch_rule")
        global-rules (->> rendered-static-rules
                          (filter #(= :global (:scope %)))
                          (map :content))
        project-rules (->> rendered-static-rules
                           (remove #(= :global (:scope %)))
                           (map :content))
        path-scoped-section (when (and fetch-rule-available? (seq path-scoped-rules))
                              ["<path-scoped-rules description=\"Rules that apply to specific file paths. Each rule has an enforce attribute: modify means you must fetch the rule before editing a matching file; read means you must fetch before reading; modify,read means you must fetch before both. Call the fetch_rule tool with the rule id and exact target path to validate the match and get the full content. Each rule needs to be fetched only once per target path — once you have the tool output, you don't need to fetch it again.\">"
                               (path-scoped-rule-catalog path-scoped-rules)
                               "</path-scoped-rules>"])
        has-static-rules? (seq rendered-static-rules)]
    (multi-str
     (shared/safe-selmer-render (eca-chat-prompt agent-name config)
                                selmer-ctx "chat-prompt")
     (when (or has-static-rules? path-scoped-section)
       ["## Rules"
        ""
        "<rules description=\"Rules defined by user. Follow them as closely as possible.\">"
        (rule-section "global-rules"
                      "Broader rules loaded outside the current workspace. Project rules below are more specific if guidance conflicts."
                      global-rules)
        (rule-section "project-rules"
                      "Rules loaded from the current workspace. Prefer these when they conflict with broader global rules."
                      project-rules)
        path-scoped-section
        "</rules>"
        ""])
     (when (seq skills)
       ["## Skills"
        ""
        "<skills description=\"Basic information about available skills to load via `eca__skill` tool for more information later if matches user request\">\n"
        (reduce
         (fn [skills-str {:keys [name description]}]
           (str skills-str (format "<skill name=\"%s\" description=\"%s\"/>\n" name description)))
         ""
         skills)
        "</skills>"
        ""])
     (when (seq stable-contexts)
       ["## Contexts"
        ""
        (contexts-str stable-contexts repo-map* (get-in db [:chats chat-id :startup-context]))])
     ""
     (shared/safe-selmer-render (load-builtin-prompt "additional_system_info.md")
                                selmer-ctx "additional-system-info"))))

(defn build-dynamic-instructions
  "Builds the volatile portion of the system prompt: cursor/MCP resource contexts
   and MCP server instructions. Recomputed every turn. Returns nil when empty."
  [refined-contexts db]
  (let [volatile-contexts (filter #(volatile-context-types (:type %)) refined-contexts)
        result (multi-str
                (when (seq volatile-contexts)
                  (contexts-str volatile-contexts nil nil))
                (mcp-instructions-section db))]
    (when-not (string/blank? result) result)))

(defn build-chat-instructions
  "Returns {:static \"...\" :dynamic \"...\"}.
   Static content (agent prompt, rules, skills, stable contexts) can be cached
   when unchanged across turns.
   Dynamic content (cursor, MCP resources, MCP instructions) is recomputed every turn."
  [refined-contexts static-rules path-scoped-rules skills repo-map* agent-name config chat-id all-tools db]
  {:static (build-static-instructions refined-contexts static-rules path-scoped-rules skills repo-map*
                                      agent-name config chat-id all-tools db)
   :dynamic (build-dynamic-instructions refined-contexts db)})

(defn instructions->str
  "Flattens a {:static :dynamic} instructions map into a single string.
   Used by non-Anthropic providers that don't support split system prompt blocks."
  [{:keys [static dynamic]}]
  (if dynamic
    (multi-str static dynamic)
    static))

(defn build-rewrite-instructions [text path full-text range all-tools config db]
  (let [legacy-prompt-file (-> config :rewrite :systemPromptFile)
        legacy-config-prompt (-> config :rewrite :systemPrompt)
        config-prompt (get-config-prompt :rewrite nil config)
        prompt-str (cond
                     legacy-config-prompt
                     legacy-config-prompt

                     config-prompt
                     config-prompt

                     ;; Absolute path
                     (and legacy-prompt-file (string/starts-with? legacy-prompt-file "/"))
                     (slurp legacy-prompt-file)

                     ;; Resource path
                     :else
                     (load-builtin-prompt (some-> legacy-prompt-file (string/replace-first #"prompts/" ""))))]
    (shared/safe-selmer-render prompt-str
                               (merge
                                (->base-selmer-ctx all-tools db)
                                {:text text
                                 :path (when path
                                         (str "- File path: " path))
                                 :rangeText (multi-str
                                             (str "- Start line: " (-> range :start :line))
                                             (str "- Start character: " (-> range :start :character))
                                             (str "- End line: " (-> range :end :line))
                                             (str "- End character: " (-> range :end :character)))
                                 :fullText (when full-text
                                             (multi-str
                                              "- Full file content"
                                              "```"
                                              full-text
                                              "```"))})
                               "rewrite-prompt")))

(defn init-prompt [all-tools agent-name db config]
  (shared/safe-selmer-render
   (get-config-prompt :init agent-name config)
   (->base-selmer-ctx all-tools db)
   "init-prompt"))

(defn skill-create-prompt [skill-name user-prompt all-tools agent-name db config]
  (shared/safe-selmer-render
   (get-config-prompt :skillCreate agent-name config)
   (merge
    (->base-selmer-ctx all-tools db)
    {:skillFilePath (str (fs/file (f.skills/global-skills-dir) skill-name "SKILL.md"))
     :skillName skill-name
     :userPrompt user-prompt})
   "skill-create-prompt"))

(defn chat-title-prompt [agent-name config]
  (get-config-prompt :chatTitle agent-name config))

(defn compact-prompt [additional-input all-tools agent-name config db]
  (shared/safe-selmer-render
   (or (:compactPrompt config) ;; legacy
       (get-config-prompt :compact agent-name config)
       (compact-prompt-template (:compactPromptFile config)) ;; legacy
       )
   (merge
    (->base-selmer-ctx all-tools db)
    {:additionalUserInput (if additional-input
                            (format "You MUST respect this user input in the summarization: %s." additional-input)
                            "")})
   "compact-prompt"))

(defn inline-completion-prompt [config]
  (let [legacy-config-file-prompt (get-in config [:completion :systemPromptFile])
        legacy-config-prompt (get-in config [:completion :systemPrompt])
        config-prompt (get-config-prompt :completion nil config)]
    (cond
      legacy-config-prompt
      legacy-config-prompt

      config-prompt
      config-prompt

      ;; Absolute path
      (and legacy-config-file-prompt (string/starts-with? legacy-config-file-prompt "/"))
      (slurp legacy-config-file-prompt)

      ;; Resource path
      :else
      (load-builtin-prompt (some-> legacy-config-file-prompt (string/replace-first #"prompts/" ""))))))

(defn get-prompt! [^String name ^Map arguments db]
  (logger/info logger-tag (format "Calling prompt '%s' with args '%s'" name arguments))
  (try
    (let [result (f.mcp/get-prompt! name arguments db)]
      (logger/debug logger-tag "Prompt result: " result)
      result)
    (catch Exception e
      (logger/warn logger-tag (format "Error calling prompt %s: %s" name e))
      {:error-message (str "Error calling prompt: " (.getMessage e))})))
