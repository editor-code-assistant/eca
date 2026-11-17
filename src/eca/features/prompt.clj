(ns eca.features.prompt
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.cache :as cache]
   [eca.logger :as logger]
   [eca.modules :as modules]
   [eca.shared :refer [multi-str] :as shared]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[PROMPT]")

;; Built-in agent prompts are now complete files, not templates
(defn ^:private load-builtin-prompt* [filename]
  (slurp (io/resource (str "prompts/" filename))))

(def ^:private load-builtin-prompt (memoize load-builtin-prompt*))

(defn get-config-prompt
  "The prompt `key` of the `prompts` config: the agent's own, else the
   global one."
  [key agent-name config]
  (or (get-in config [:agent agent-name :prompts key])
      (get-in config [:prompts key])))

(defn ^:private absolute-path? [path]
  (and (string? path)
       (.isAbsolute (io/file path))))

(defn prompt-file
  "The text of prompt file `path`, a legacy `systemPromptFile`: the file
   itself when absolute, else ECA's own prompt under `prompts/`."
  [path]
  (if (absolute-path? path)
    (slurp path)
    (load-builtin-prompt (some-> path (string/replace-first #"prompts/" "")))))

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

(defn attr-str
  "The attributes of a prompt tag: ` k=\"v\"` for each non-nil value, with
   the quotes the value needs."
  [attrs]
  (->> attrs
       (keep (fn [[k v]]
               (when (some? v)
                 (format " %s=%s" (name k) (attr-value-str v)))))
       string/join))

(defn ^:private render-context [{:keys [type path position content lines-range label]}]
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
    :cursor (format "<cursor%s/>"
                    (attr-str {:description "Editor cursor position (line:character)."
                               :path path
                               :start (str (:line (:start position)) ":" (:character (:start position)))
                               :end (str (:line (:end position)) ":" (:character (:end position)))}))
    :text (format "<text%s>%s</text>" (attr-str {:label label}) content)
    nil))

(defn contexts-str
  "The `<contexts>` block of `refined-contexts`. A context may also be its
   text already, as a module renders the contexts of its own types."
  ([refined-contexts startup-ctx]
   (contexts-str refined-contexts startup-ctx {}))
  ([refined-contexts startup-ctx {:keys [tag description]
                                  :or {tag "contexts"
                                       description "User-provided context. Treat as current and accurate."}}]
   (let [body (cond-> (vec (keep #(if (string? %) % (render-context %)) refined-contexts))
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

(def ^:private editor-state-context-types
  "Volatile editor-state contexts (e.g. cursor) delivered per-turn in the user message."
  #{:cursor})

(def ^:private static-prompt-context-types
  "Context types rendered into the cached static prompt block.
   Context-area files are data that changes between turns, so they
   are delivered in the user message instead, see `build-attached-contexts`. #683"
  #{:agents-file})

(defn static-prompt-context?
  "True when ctx renders into the cached static prompt block."
  [ctx]
  (boolean (static-prompt-context-types (:type ctx))))

;; System prompt sections. Each one belongs to a module (`:system-prompts`)
;; and gets the prompt ctx: what the chat's system prompt is built from.

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
  [{:keys [refined-contexts chat-id db]}]
  (let [stable-contexts (filter static-prompt-context? refined-contexts)
        startup-ctx (get-in db [:chats chat-id :startup-context])]
    (when (or (seq stable-contexts) (not (string/blank? startup-ctx)))
      [""
       "## Context"
       ""
       (contexts-str stable-contexts startup-ctx)])))

(def ^:private built-in-static-sections
  "The order of the built-in static sections."
  [:eca/agent-prompt :eca/rules :eca/skills :eca/deferred-tools :eca/additional-info
   :eca/workspace-roots :eca/path-resolution :eca/context])

(defn ^:private section-items
  "The active modules' sections of one part (static or dynamic), as
   `{:entry :section}`, in module order."
  [config dynamic?]
  (for [[entry sections] (modules/contributions config :system-prompts)
        section sections
        :when (= (boolean dynamic?) (boolean (:dynamic? section)))]
    {:entry entry :section section}))

(defn ^:private place-sections
  "The active modules' sections of one part (static or dynamic), in order:
   built-in ones in `built-in-order`; the others right before or after the
   section their `:before`/`:after` names, else at the end, in module order."
  [config dynamic? built-in-order]
  (let [sections (section-items config dynamic?)
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

(defn ^:private static-prompt-ctx
  "The prompt ctx static sections get: `prompt-ctx` and its Selmer ctx."
  [{:keys [all-tools chat-id db] :as prompt-ctx}]
  (assoc prompt-ctx :selmer-ctx (->base-selmer-ctx all-tools chat-id db)))

(defn build-static-instructions
  "Builds the cacheable static system-prompt prefix, from the active modules'
   static `:system-prompts` sections. `prompt-ctx` is what a chat's system
   prompt is built from: `:refined-contexts`, `:agent`, `:config`,
   `:chat-id`, `:all-tools`, `:db` and `:full-model`; sections compute their
   own data from it."
  [{:keys [config] :as prompt-ctx}]
  (let [ctx (static-prompt-ctx prompt-ctx)]
    (apply multi-str (map #(section-part ctx %) (place-sections config false built-in-static-sections)))))

(defn section-signatures
  "What the cache signature follows of the built-in static sections that
   give a `:signature` (built-in only): `[key signature resync?]` of each,
   keyed by its id's name (`:rules`, `:skills`), in prompt order. Computed
   whether their module is active or not, as core followed their data before
   modules. `resync?` is false for a section with `:resync? false`: its
   changes alone never re-sync a chat's pinned prompt (skills)."
  [prompt-ctx]
  (let [by-id (into {} (for [module (modules/built-in-modules)
                             section (:system-prompts module)
                             :when (and (:signature section) (not (:dynamic? section)))]
                         [(:id section) [module section]]))]
    (vec (for [id built-in-static-sections
               :let [[module section] (by-id id)]
               :when section]
           [(keyword (name id))
            ((:signature section) (modules/module-ctx prompt-ctx {:id (:id module) :module module :built-in? true}))
            (not (false? (:resync? section)))]))))

(defn uncategorized-static-sections
  "What the static prompt holds that the cache signature's categories
   (prompt, contexts, rules, skills, tools) don't follow, or nil when that's
   nothing: `:sections`, `[id before after text]` of each static section
   that isn't a built-in module's built-in section, its text as it renders
   now, and `:missing`, the built-in sections no active module gives, e.g.
   when `eca/skills` is turned off."
  [{:keys [config] :as prompt-ctx}]
  (let [items (section-items config false)
        built-in-ids (set built-in-static-sections)
        categorized? (fn [{:keys [entry section]}]
                       (boolean (and (:built-in? entry) (built-in-ids (:id section)))))
        given (into #{} (comp (filter categorized?) (map (comp :id :section))) items)
        others (remove categorized? items)
        missing (filterv (complement given) built-in-static-sections)]
    (when (or (seq others) (seq missing))
      (let [ctx (static-prompt-ctx prompt-ctx)]
        {:sections (mapv (fn [{:keys [section] :as item}]
                           [(:id section) (:before section) (:after section) (section-part ctx item)])
                         others)
         :missing missing}))))

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
      (contexts-str editor-state-contexts nil
                    {:tag "editor-state"
                     :description "Editor state reference; not a user request. Use only when relevant."}))))

(defn build-text-contexts
  "Renders client-supplied text contexts (e.g. editor buffers) for the
   user message. Returns nil when none."
  [refined-contexts]
  (let [text-contexts (filter #(= :text (:type %)) refined-contexts)]
    (when (seq text-contexts)
      (contexts-str text-contexts nil))))

(defn ^:private changed-context-entry [{:keys [type path lines-range]}]
  (case type
    :file (format "<file%s/>" (attr-str {:path path
                                         :line-start (:start lines-range)
                                         :line-end (:end lines-range)}))
    nil))

(defn build-attached-contexts
  "Renders context-area contexts (files) for the user message:
   `new-contexts` in full, `changed-contexts` as one-line notices since their
   earlier copy in the chat history is outdated. Returns nil when none. #683"
  [new-contexts changed-contexts]
  (let [changed-entries (keep changed-context-entry changed-contexts)]
    (when (or (seq new-contexts) (seq changed-entries))
      (multi-str
       (when (seq new-contexts)
         (contexts-str new-contexts nil
                       {:description "Context attached by the user, current as of this message. Later changes are announced."}))
       (when (seq changed-entries)
         [(format "<changed-contexts%s>"
                  (attr-str {:description "Attached context changed since it was sent, its earlier copy in this chat is outdated. Re-read it with tools when relevant."}))
          (string/join "\n" changed-entries)
          "</changed-contexts>"])))))

(defn build-chat-instructions
  "Returns {:static :dynamic} system instructions, see
   `build-static-instructions` for `prompt-ctx`."
  [{:keys [refined-contexts db config] :as prompt-ctx}]
  {:static (build-static-instructions prompt-ctx)
   :dynamic (build-dynamic-instructions refined-contexts db config)})

(defn instructions->str
  "Flattens {:static :dynamic} into a single string for non-Anthropic providers."
  [{:keys [static dynamic]}]
  (if dynamic
    (multi-str static "" dynamic)
    static))
