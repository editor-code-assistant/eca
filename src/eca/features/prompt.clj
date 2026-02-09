(ns eca.features.prompt
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.features.skills :as f.skills]
   [eca.features.tools.mcp :as f.mcp]
   [eca.logger :as logger]
   [eca.shared :refer [multi-str] :as shared]
   [selmer.parser :as selmer])
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
  (let [config-prompt (get-config-prompt :chat agent-name config)
        agent-config (get-in config [:agent agent-name])
        legacy-config-prompt (:systemPrompt agent-config)
        legacy-config-prompt-file (:systemPromptFile agent-config)]
    (cond
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

(defn ^:private ->base-selmer-ctx [all-tools db]
  (merge
   {:workspaceRoots (shared/workspaces-as-str db)}
   (reduce
    (fn [m tool]
      (assoc m (keyword (str "toolEnabled_" (:full-name tool))) true))
    {}
    all-tools)))

(defn build-chat-instructions [refined-contexts rules skills repo-map* agent-name config chat-id all-tools db]
  (let [selmer-ctx (->base-selmer-ctx all-tools db)]
    (multi-str
     (selmer/render (eca-chat-prompt agent-name config) selmer-ctx)
     (when (seq rules)
       ["## Rules"
        ""
        "<rules description=\"Rules defined by user\">\n"
        (reduce
         (fn [rule-str {:keys [name content]}]
           (str rule-str (format "<rule name=\"%s\">%s</rule>\n" name content)))
         ""
         rules)
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
     (when (seq refined-contexts)
       ["## Contexts"
        ""
        (contexts-str refined-contexts repo-map* (get-in db [:chats chat-id :startup-context]))])
     ""
     (selmer/render (load-builtin-prompt "additional_system_info.md") selmer-ctx))))

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
    (selmer/render prompt-str
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
                                  "```"))}))))

(defn init-prompt [all-tools agent-name db config]
  (selmer/render
   (get-config-prompt :init agent-name config)
   (->base-selmer-ctx all-tools db)))

(defn skill-create-prompt [skill-name user-prompt all-tools agent-name db config]
  (selmer/render
   (get-config-prompt :skillCreate agent-name config)
   (merge
    (->base-selmer-ctx all-tools db)
    {:skillFilePath (str (fs/file (f.skills/global-skills-dir) skill-name "SKILL.md"))
     :skillName skill-name
     :userPrompt user-prompt})))

(defn chat-title-prompt [agent-name config]
  (get-config-prompt :chatTitle agent-name config))

(defn compact-prompt [additional-input all-tools agent-name config db]
  (selmer/render
   (or (:compactPrompt config) ;; legacy
       (get-config-prompt :compact agent-name config)
       (compact-prompt-template (:compactPromptFile config)) ;; legacy
       )
   (merge
    (->base-selmer-ctx all-tools db)
    {:additionalUserInput (if additional-input
                            (format "You MUST respect this user input in the summarization: %s." additional-input)
                            "")})))

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
