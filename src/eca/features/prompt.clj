(ns eca.features.prompt
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.features.tools.mcp :as f.mcp]
   [eca.logger :as logger]
   [eca.shared :refer [multi-str] :as shared])
  (:import
   [java.util Map]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[PROMPT]")

;; Built-in behavior prompts are now complete files, not templates
(defn ^:private load-builtin-prompt* [filename]
  (slurp (io/resource (str "prompts/" filename))))

(def ^:private load-builtin-prompt (memoize load-builtin-prompt*))

(defn ^:private init-prompt-template* [] (slurp (io/resource "prompts/init.md")))
(def ^:private init-prompt-template (memoize init-prompt-template*))

(defn ^:private title-prompt-template* [] (slurp (io/resource "prompts/title.md")))
(def ^:private title-prompt-template (memoize title-prompt-template*))

(defn ^:private compact-prompt-template* [file-path]
  (if (fs/relative? file-path)
    (slurp (io/resource file-path))
    (slurp (io/file file-path))))

(def ^:private compact-prompt-template (memoize compact-prompt-template*))

(defn ^:private replace-vars [s vars]
  (reduce
   (fn [p [k v]]
     (string/replace p (str "{" (name k) "}") (str v)))
   s
   vars))

(defn ^:private eca-chat-prompt [behavior config]
  (let [behavior-config (get-in config [:behavior behavior])
        ;; Use systemPromptFile from behavior config, or fall back to built-in
        prompt-file (or (:systemPromptFile behavior-config)
                        ;; For built-in behaviors without explicit config
                        (when (#{"agent" "plan"} behavior)
                          (str "prompts/" behavior "_behavior.md")))]
    (cond
      ;; Custom behavior with absolute path
      (and prompt-file (string/starts-with? prompt-file "/"))
      (slurp prompt-file)

      ;; Built-in or resource path
      prompt-file
      (load-builtin-prompt (some-> prompt-file (string/replace-first #"prompts/" "")))

      ;; Fallback for unknown behavior
      :else
      (load-builtin-prompt "agent_behavior.md"))))

(defn contexts-str [refined-contexts repo-map*]
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
                                       (format "<agents-file description=\"Instructions following AGENTS.md spec.\" path=\"%s\">" path)
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
   "</contexts>"))

(defn build-chat-instructions [refined-contexts rules repo-map* behavior config db]
  (multi-str
   (eca-chat-prompt behavior config)
   (when (seq rules)
     ["## Rules"
      ""
      "<rules description=\"Rules defined by user\">\n"
      (reduce
       (fn [rule-str {:keys [name content]}]
         (str rule-str (format "<rule name=\"%s\">%s</rule>\n" name content)))
       ""
       rules)
      "</rules>"])
   ""
   (when (seq refined-contexts)
     ["## Contexts"
      ""
      (contexts-str refined-contexts repo-map*)])
   ""
   (replace-vars
    (load-builtin-prompt "additional_system_info.md")
    {:workspaceRoots (shared/workspaces-as-str db)})))

(defn build-rewrite-instructions [text path full-text range config]
  (let [prompt-file (-> config :rewrite :systemPromptFile)
        prompt-str (cond
                     ;; Absolute path
                     (and prompt-file (string/starts-with? prompt-file "/"))
                     (slurp prompt-file)

                     ;; Resource path
                     :else
                     (load-builtin-prompt (some-> prompt-file (string/replace-first #"prompts/" ""))))]
    (replace-vars
     prompt-str
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
                   "```"))})))

(defn init-prompt [db]
  (replace-vars
   (init-prompt-template)
   {:workspaceFolders (shared/workspaces-as-str db)}))

(defn title-prompt []
  (title-prompt-template))

(defn compact-prompt [additional-input config]
  (replace-vars
   (compact-prompt-template (:compactPromptFile config))
   {:additionalUserInput (if additional-input
                           (format "You MUST respect this user input in the summarization: %s." additional-input)
                           "")}))

(defn inline-completion-prompt [config]
  (let [prompt-file (get-in config [:completion :systemPromptFile])]
    (cond
      ;; Absolute path
      (and prompt-file (string/starts-with? prompt-file "/"))
      (slurp prompt-file)

      ;; Resource path
      :else
      (load-builtin-prompt (some-> prompt-file (string/replace-first #"prompts/" ""))))))

(defn get-prompt! [^String name ^Map arguments db]
  (logger/info logger-tag (format "Calling prompt '%s' with args '%s'" name arguments))
  (try
    (let [result (f.mcp/get-prompt! name arguments db)]
      (logger/debug logger-tag "Prompt result: " result)
      result)
    (catch Exception e
      (logger/warn logger-tag (format "Error calling prompt %s: %s" name e))
      {:error-message (str "Error calling prompt: " (.getMessage e))})))
