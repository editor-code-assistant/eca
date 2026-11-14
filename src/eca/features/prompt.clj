(ns eca.features.prompt
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.cache :as cache]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]
   [eca.modules :as modules]
   [eca.modules.mcp :as f.mcp]
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

(defn get-config-prompt
  "The prompt `key` of the `prompts` config: the agent's own, else the
   global one."
  [key agent-name config]
  (or (get-in config [:agent agent-name :prompts key])
      (get-in config [:prompts key])))

(defn ^:private absolute-path? [path]
  (and (string? path)
       (.isAbsolute (io/file path))))

(defn eca-chat-prompt
  "Selected chat prompt template; single source of truth for rendering and cache identity."
  [agent-name config chat-id db]
  (let [agent-config (get-in config [:agent agent-name])
        is-subagent? (boolean (get-in db [:chats chat-id :subagent]))
        subagent-prompt (and is-subagent?
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
      (absolute-path? legacy-config-prompt-file)
      (slurp legacy-config-prompt-file)

      ;; Built-in or resource path
      legacy-config-prompt-file
      (load-builtin-prompt (some-> legacy-config-prompt-file (string/replace-first #"prompts/" "")))

      ;; Fallback for unknown agent
      :else
      (load-builtin-prompt "code_agent.md"))))

(defn ^:private attr-value-str [value]
  (let [value (str value)]
    (cond
      (not (string/includes? value "\""))
      (format "\"%s\"" value)

      (not (string/includes? value "'"))
      (format "'%s'" value)

      :else
      (format "\"%s\"" (string/replace value "\"" "&quot;")))))

(defn ^:private attr-str [attrs]
  (->> attrs
       (keep (fn [[k v]]
               (when (some? v)
                 (format " %s=%s" (name k) (attr-value-str v)))))
       string/join))

(defn ^:private render-context [{:keys [type path position content lines-range uri label]} repo-map*]
  (case type
    :file (if lines-range
            (format "<file%s>%s</file>"
                    (attr-str {:line-start (:start lines-range)
                               :line-end (:end lines-range)
                               :path path})
                    content)
            (format "<file%s>%s</file>" (attr-str {:path path}) content))
    :agents-file (multi-str
                  (format "<agents-file%s>"
                          (attr-str {:description "Primary System Directives & Coding Standards."
                                     :path path}))
                  content
                  "</agents-file>")
    :repoMap (format "<repo-map%s>%s</repo-map>"
                     (attr-str {:description "Workspace structure tree; spaces represent file hierarchy."})
                     @repo-map*)
    :cursor (format "<cursor%s/>"
                    (attr-str {:description "Editor cursor position (line:character)."
                               :path path
                               :start (str (:line (:start position)) ":" (:character (:start position)))
                               :end (str (:line (:end position)) ":" (:character (:end position)))}))
    :mcpResource (format "<resource%s>%s</resource>" (attr-str {:uri uri}) content)
    :text (format "<text%s>%s</text>" (attr-str {:label label}) content)
    nil))

(defn contexts-str
  ([refined-contexts repo-map* startup-ctx]
   (contexts-str refined-contexts repo-map* startup-ctx {}))
  ([refined-contexts repo-map* startup-ctx {:keys [tag description]
                                            :or {tag "contexts"
                                                 description "User-provided context. Treat as current and accurate."}}]
   (let [body (cond-> (vec (keep #(render-context % repo-map*) refined-contexts))
                startup-ctx (conj (multi-str "<additional-context>"
                                             startup-ctx
                                             "</additional-context>")))]
     (multi-str
      (format "<%s%s>" tag (attr-str {:description description}))
      (when (seq body)
        (string/join "\n\n" body))
      (format "</%s>" tag)))))

(defn ->base-selmer-ctx
  ([all-tools db]
   (->base-selmer-ctx all-tools nil db))
  ([all-tools chat-id db]
   (merge
    {:workspaceRoots (shared/workspaces-as-str db)
     :osName (str (System/getProperty "os.name") " " (System/getProperty "os.version"))
     :shell (or (System/getenv "SHELL") (System/getenv "ComSpec"))
     :userName (System/getProperty "user.name")
     :homeDir (cache/user-home)
     :isSubagent (boolean (get-in db [:chats chat-id :subagent]))}
    (reduce
     (fn [m tool]
       (assoc m (keyword (str "toolEnabled_" (:full-name tool))) true))
     {}
     all-tools))))

(defn ^:private workspace-roots-section [db]
  (when-let [roots (seq (:workspace-folders db))]
    (multi-str
     "## Workspace Roots"
     ""
     (format "<workspace-roots%s>" (attr-str {:description "Workspace roots used for path resolution and tool scoping."}))
     (->> roots
          (map (fn [{:keys [uri]}]
                 (format "<workspace-root%s/>"
                         (attr-str {:path (shared/uri->filename uri)}))))
          (string/join "\n"))
     "</workspace-roots>"
     "")))

(def ^:private path-resolution-section
  (multi-str
   "## Path Resolution"
   ""
   "Use workspace roots as the base for resolving relative paths. Tool paths must be absolute unless the tool says otherwise."))

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
       "## MCP Server Instructions"
       ""
       (format "<mcp-server-instructions%s>" (attr-str {:description "Instructions from running MCP servers."}))
       (->> servers-with-instructions
            (map (fn [{:keys [name instructions]}]
                   (multi-str
                    (format "<mcp-server-instruction%s>" (attr-str {:name name}))
                    instructions
                    "</mcp-server-instruction>")))
            (string/join "\n\n"))
       "</mcp-server-instructions>"))))

(def ^:private editor-state-context-types
  "Volatile editor-state contexts (e.g. cursor) delivered per-turn in the user message."
  #{:cursor})

(def ^:private volatile-context-types
  "Context types that change between turns and must be kept out of the cached
   static prompt."
  (into #{:mcpResource} editor-state-context-types))

(def ^:private static-prompt-context-types
  "Context types rendered into the cached static prompt block.
   Context-area files and repoMap are data that changes between turns, so they
   are delivered in the user message instead, see `build-attached-contexts`. #683"
  #{:agents-file})

(defn static-prompt-context?
  "True when ctx renders into the cached static prompt block."
  [ctx]
  (boolean (static-prompt-context-types (:type ctx))))

(defn ^:private rule-section
  [tag description rendered-rules]
  (when (seq rendered-rules)
    [(format "<%s%s>" tag (attr-str {:description description}))
     (string/join "\n" rendered-rules)
     (format "</%s>" tag)
     ""]))

(defn ^:private path-scoped-rule-attrs
  [{rule-name :name :keys [id paths enforce scope workspace-root]}]
  (attr-str {:id id
             :name rule-name
             :scope (some-> scope name)
             :workspace-root workspace-root
             :paths (string/join "," paths)
             :enforce (string/join "," (or enforce ["modify"]))}))

(defn ^:private path-scoped-rule-catalog-entry
  [rule]
  (str "<rule" (path-scoped-rule-attrs rule) "/>"))

(defn ^:private path-scoped-rule-sections
  [path-scoped-rules render-entry]
  (let [global-rules (filter #(= :global (:scope %)) path-scoped-rules)
        project-rules (filter #(= :project (:scope %)) path-scoped-rules)]
    (multi-str
     (when (seq global-rules)
       [(format "<global-path-scoped-rules%s>" (attr-str {:description "Path-scoped rules loaded outside the current workspace."}))
        (->> global-rules
             (map render-entry)
             (string/join "\n"))
        "</global-path-scoped-rules>"])
     (->> project-rules
          (group-by :workspace-root)
          (sort-by first)
          (map (fn [[workspace-root rules]]
                 (str "<workspace-path-scoped-rules" (attr-str {:root workspace-root}) ">\n"
                      (->> rules
                           (map render-entry)
                           (string/join "\n"))
                      "\n</workspace-path-scoped-rules>")))))))

(defn ^:private path-scoped-rule-catalog
  [path-scoped-rules]
  (path-scoped-rule-sections path-scoped-rules path-scoped-rule-catalog-entry))

(def ^:private deferred-tool-description-max-length 250)

(defn ^:private deferred-tool-summary
  "First paragraph of the tool description, truncated: the catalog is routing
   metadata, the full description arrives when the tool is loaded."
  [description]
  (let [summary (-> (str description)
                    (string/split #"\R\s*\R" 2)
                    first
                    (string/replace #"\s+" " ")
                    string/trim)]
    (if (> (count summary) deferred-tool-description-max-length)
      (str (string/trimr (subs summary 0 deferred-tool-description-max-length)) "…")
      summary)))

(defn ^:private deferred-tools-section
  [all-tools]
  (when-let [deferred-tools (seq (filter :deferrable all-tools))]
    ["## Deferred Tools"
     ""
     (str (format "<deferred-tools%s>"
                  (attr-str {:description (str "Tools that exist but are not loaded: their full descriptions and input schemas are unavailable and they cannot be called yet. "
                                               "Load the ones you need with eca__search_tools, which makes them callable.")}))
          "\n")
     (reduce
      (fn [tools-str {:keys [full-name description]}]
        (str tools-str (format "<deferred-tool%s/>\n"
                               (attr-str {:name full-name
                                          :description (deferred-tool-summary description)}))))
      ""
      (sort-by :full-name deferred-tools))
     "</deferred-tools>"
     ""]))

;; System prompt sections. Each one belongs to a module (`:system-prompts`)
;; and gets the prompt ctx: what the chat's system prompt is built from.

(defn agent-prompt-section
  "`:eca/agent-prompt`: the agent's own prompt, else `prompts.chat`."
  [{:keys [agent config chat-id db selmer-ctx]}]
  [(string/trimr
    (shared/safe-selmer-render (eca-chat-prompt agent config chat-id db)
                               selmer-ctx "chat-prompt"))
   ""])

(defn rules-section
  "`:eca/rules`: the static rules, and the catalog of path-scoped ones."
  [{:keys [static-rules path-scoped-rules all-tools selmer-ctx]}]
  (let [rendered-static-rules (keep (fn [{:keys [name content scope]}]
                                      (when-let [rendered (shared/safe-selmer-render content selmer-ctx (str "rule:" name) nil)]
                                        (when-not (string/blank? rendered)
                                          {:scope (if (= :global scope) :global :project)
                                           :content (format "<rule%s>%s</rule>" (attr-str {:name name}) rendered)})))
                                    static-rules)
        fetch-rule-available? (tools.util/tool-available? all-tools "eca__fetch_rule")
        global-rules (->> rendered-static-rules
                          (filter #(= :global (:scope %)))
                          (map :content))
        project-rules (->> rendered-static-rules
                           (remove #(= :global (:scope %)))
                           (map :content))
        path-scoped-section (when (and fetch-rule-available? (seq path-scoped-rules))
                              [(format "<path-scoped-rules%s>" (attr-str {:description "Rules that apply to matching file paths. Use fetch_rule before actions required by enforce (read, modify, or both). Each rule only needs to be fetched once per chat."}))
                               (path-scoped-rule-catalog path-scoped-rules)
                               "</path-scoped-rules>"])
        has-static-rules? (seq rendered-static-rules)]
    (when (or has-static-rules? path-scoped-section)
      ["## Rules"
       ""
       (format "<rules%s>" (attr-str {:description "Rules defined by user. Follow them as closely as possible."}))
       (rule-section "global-rules"
                     "Global user rules; project rules are more specific."
                     global-rules)
       (rule-section "project-rules"
                     "Workspace rules; prefer over global rules when they conflict."
                     project-rules)
       path-scoped-section
       "</rules>"
       ""])))

(defn skills-section
  "`:eca/skills`: the skills' names and descriptions."
  [{:keys [skills]}]
  (when (seq skills)
    ["## Skills"
     ""
     (str (format "<skills%s>" (attr-str {:description "Available skills; load with eca__skill when relevant."})) "\n")
     (reduce
      (fn [skills-str {:keys [name description]}]
        (str skills-str (format "<skill%s/>\n" (attr-str {:name name :description description}))))
      ""
      skills)
     "</skills>"
     ""]))

(defn deferred-tools-prompt-section
  "`:eca/deferred-tools`: the tools hidden behind search_tools."
  [{:keys [all-tools]}]
  (deferred-tools-section all-tools))

(defn additional-info-section
  "`:eca/additional-info`: OS, shell and other session facts."
  [{:keys [selmer-ctx]}]
  (shared/safe-selmer-render (load-builtin-prompt "additional_system_info.md")
                             selmer-ctx "additional-system-info"))

(defn workspace-roots-prompt-section
  "`:eca/workspace-roots`."
  [{:keys [db]}]
  (workspace-roots-section db))

(defn path-resolution-prompt-section
  "`:eca/path-resolution`."
  [_ctx]
  path-resolution-section)

(defn context-section
  "`:eca/context`: AGENTS.md files, attached stable contexts and the chat's
   startup context."
  [{:keys [refined-contexts repo-map* chat-id db]}]
  (let [stable-contexts (filter static-prompt-context? refined-contexts)
        startup-ctx (get-in db [:chats chat-id :startup-context])]
    (when (or (seq stable-contexts) (not (string/blank? startup-ctx)))
      [""
       "## Context"
       ""
       (contexts-str stable-contexts repo-map* startup-ctx)])))

(def ^:private built-in-static-sections
  "The order of the built-in static sections."
  [:eca/agent-prompt :eca/rules :eca/skills :eca/deferred-tools :eca/additional-info
   :eca/workspace-roots :eca/path-resolution :eca/context])

(defn ^:private place-sections
  "The active modules' sections of one part (static or dynamic), in order:
   built-in ones in `built-in-order`; the others right before or after the
   section their `:before`/`:after` names, else at the end, in module order."
  [config dynamic? built-in-order]
  (let [sections (for [[entry sections] (modules/contributions config :system-prompts)
                       section sections
                       :when (= (boolean dynamic?) (boolean (:dynamic? section)))]
                   {:entry entry :section section})
        order (zipmap built-in-order (range))
        {built-ins true others false} (group-by #(contains? order (:id (:section %))) sections)]
    (reduce (fn [placed {:keys [entry section] :as item}]
              (let [target (or (:before section) (:after section))
                    idx (when target (first (keep-indexed #(when (= target (:id (:section %2))) %1) placed)))]
                (cond
                  (and target (nil? idx))
                  (do (logger/warn logger-tag (format "Prompt section %s of %s: no section %s here, appending it"
                                                      (:id section) (:id entry) target))
                      (conj placed item))

                  (:before section) (into (conj (subvec placed 0 idx) item) (subvec placed idx))
                  (:after section) (into (conj (subvec placed 0 (inc idx)) item) (subvec placed (inc idx)))
                  :else (conj placed item))))
            (vec (sort-by #(order (:id (:section %))) built-ins))
            others)))

(defn ^:private section-part
  "A section's text, or the parts `multi-str` joins: a string `:content` is a
   Selmer template, a function gets the prompt ctx."
  [ctx {:keys [entry section]}]
  (let [{:keys [id content]} section
        module-ctx (modules/module-ctx ctx entry)]
    (cond
      (string? content) (shared/safe-selmer-render content (:selmer-ctx ctx) (str id))
      (not (modules/fn-like? content)) nil
      (:built-in? entry) (content module-ctx)
      :else (modules/invoke-safe module-ctx entry (str "prompt section " id)
                                 {:timeout-ms (modules/timeout-for entry nil modules/default-handler-timeout-ms)}
                                 content module-ctx))))

(defn build-static-instructions
  "Builds the cacheable static system-prompt prefix, from the active modules'
   static `:system-prompts` sections."
  [refined-contexts static-rules path-scoped-rules skills repo-map* agent-name config chat-id all-tools db]
  (let [ctx {:refined-contexts refined-contexts
             :static-rules static-rules
             :path-scoped-rules path-scoped-rules
             :skills skills
             :repo-map* repo-map*
             :agent agent-name
             :config config
             :chat-id chat-id
             :all-tools all-tools
             :db db
             :selmer-ctx (->base-selmer-ctx all-tools chat-id db)}]
    (apply multi-str (map #(section-part ctx %) (place-sections config false built-in-static-sections)))))

(defn mcp-resources-section
  "`:eca/mcp-resources`: the MCP resources attached to the chat."
  [{:keys [refined-contexts]}]
  (let [volatile-contexts (filter #(and (volatile-context-types (:type %))
                                        (not (editor-state-context-types (:type %))))
                                  refined-contexts)]
    (when (seq volatile-contexts)
      ["## MCP Resources"
       ""
       (contexts-str volatile-contexts nil nil
                     {:description "Resources from connected MCP servers."})
       ""])))

(defn mcp-instructions-prompt-section
  "`:eca/mcp-instructions`: the running MCP servers' instructions."
  [{:keys [db]}]
  (mcp-instructions-section db))

(def ^:private built-in-dynamic-sections
  "The order of the built-in dynamic sections."
  [:eca/mcp-resources :eca/mcp-instructions])

(defn build-dynamic-instructions
  "Per-turn dynamic system instructions, from the active modules' `:dynamic?`
   sections (MCP resources, server instructions). Returns nil when empty."
  ([refined-contexts db]
   (build-dynamic-instructions refined-contexts db nil))
  ([refined-contexts db config]
   (let [ctx {:refined-contexts refined-contexts :db db :config config :selmer-ctx {}}
         result (apply multi-str (map #(section-part ctx %) (place-sections config true built-in-dynamic-sections)))]
     (when-not (string/blank? result) result))))

(defn build-editor-state-context
  "Renders editor-state contexts (e.g. cursor) for the user message. Returns nil when none."
  [refined-contexts]
  (let [editor-state-contexts (filter #(editor-state-context-types (:type %)) refined-contexts)]
    (when (seq editor-state-contexts)
      (contexts-str editor-state-contexts nil nil
                    {:tag "editor-state"
                     :description "Editor state reference; not a user request. Use only when relevant."}))))

(defn build-text-contexts
  "Renders client-supplied text contexts (e.g. editor buffers) for the
   user message. Returns nil when none."
  [refined-contexts]
  (let [text-contexts (filter #(= :text (:type %)) refined-contexts)]
    (when (seq text-contexts)
      (contexts-str text-contexts nil nil))))

(defn ^:private changed-context-entry [{:keys [type path lines-range]}]
  (case type
    :file (format "<file%s/>" (attr-str {:path path
                                         :line-start (:start lines-range)
                                         :line-end (:end lines-range)}))
    :repoMap "<repo-map/>"
    nil))

(defn build-attached-contexts
  "Renders context-area contexts (files, repoMap) for the user message:
   `new-contexts` in full, `changed-contexts` as one-line notices since their
   earlier copy in the chat history is outdated. Returns nil when none. #683"
  [new-contexts changed-contexts repo-map*]
  (let [changed-entries (keep changed-context-entry changed-contexts)]
    (when (or (seq new-contexts) (seq changed-entries))
      (multi-str
       (when (seq new-contexts)
         (contexts-str new-contexts repo-map* nil
                       {:description "Context attached by the user, current as of this message. Later changes are announced."}))
       (when (seq changed-entries)
         [(format "<changed-contexts%s>"
                  (attr-str {:description "Attached context changed since it was sent, its earlier copy in this chat is outdated. Re-read it with tools when relevant."}))
          (string/join "\n" changed-entries)
          "</changed-contexts>"])))))

(defn build-chat-instructions
  "Returns {:static :dynamic} system instructions."
  [refined-contexts static-rules path-scoped-rules skills repo-map* agent-name config chat-id all-tools db]
  {:static (build-static-instructions refined-contexts static-rules path-scoped-rules skills repo-map*
                                      agent-name config chat-id all-tools db)
   :dynamic (build-dynamic-instructions refined-contexts db config)})

(defn instructions->str
  "Flattens {:static :dynamic} into a single string for non-Anthropic providers."
  [{:keys [static dynamic]}]
  (if dynamic
    (multi-str static "" dynamic)
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
                     (absolute-path? legacy-prompt-file)
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
      (absolute-path? legacy-config-file-prompt)
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
