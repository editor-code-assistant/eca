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

;; TODO
(defn ^:private inline-completion-prompt-template* [] (slurp (io/resource "prompts/inline_completion.md")))
(defn ^:private inline-completion-prompt-template [] (slurp "/home/greg/dev/eca/resources/prompts/inline_completion.md"))
;; (def ^:private inline-completion-prompt-template (memoize inline-completion-prompt-template*))

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

(defn ^:private eca-prompt [behavior config]
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
   "<contexts description=\"Manually Provided by user. Their content is current and accurate. You MUST use this information first before using tools to read them.\">"
   (reduce
    (fn [context-str {:keys [type path position content partial uri]}]
      (str context-str (case type
                         :file (if partial
                                 (format "<file partial=true path=\"%s\">...\n%s\n...</file>\n" path content)
                                 (format "<file path=\"%s\">%s</file>\n" path content))
                         :agents-file (multi-str
                                       (format "<agents-file description=\"Instructions following AGENTS.md spec.\" path=\"%s\">" path)
                                       content
                                       "</agents-file>\n")
                         :repoMap (format "<repoMap description=\"Workspaces structure in a tree view, spaces represent file hierarchy\" >%s</repoMap>\n" @repo-map*)
                         :cursor (format "<cursor description=\"User editor cursor position (line:character)\" path=\"%s\" start=\"%s\" end=\"%s\"/>\n"
                                         path
                                         (str (:line (:start position)) ":" (:character (:start position)))
                                         (str (:line (:end position)) ":" (:character (:end position))))
                         :mcpResource (format "<resource uri=\"%s\">%s</resource>\n" uri content)
                         "")))
    ""
    refined-contexts)
   "</contexts>"))

(defn build-instructions [refined-contexts rules repo-map* behavior config]
  (multi-str
   (eca-prompt behavior config)
   (when (seq rules)
     ["<rules description=\"Rules defined by user\">\n"
      (reduce
       (fn [rule-str {:keys [name content]}]
         (str rule-str (format "<rule name=\"%s\">%s</rule>\n" name content)))
       ""
       rules)
      "</rules>"])
   ""
   (when (seq refined-contexts)
     [(contexts-str refined-contexts repo-map*)])))

(defn init-prompt [db]
  (replace-vars
   (init-prompt-template)
   {:workspaceFolders (string/join ", " (map (comp shared/uri->filename :uri) (:workspace-folders db)))}))

(defn title-prompt []
  (title-prompt-template))

(defn compact-prompt [additional-input config]
  (replace-vars
   (compact-prompt-template (:compactPromptFile config))
   {:addionalUserInput (if additional-input
                         (format "You MUST respect this user input in the summarization: %s." additional-input)
                         "")}))

(defn inline-completion-prompt [line character]
  (replace-vars
   (inline-completion-prompt-template)
   {:linePosition line
    :characterPosition character}))

(defn get-prompt! [^String name ^Map arguments db]
  (logger/info logger-tag (format "Calling prompt '%s' with args '%s'" name arguments))
  (try
    (let [result (f.mcp/get-prompt! name arguments db)]
      (logger/debug logger-tag "Prompt result: " result)
      result)
    (catch Exception e
      (logger/warn logger-tag (format "Error calling prompt %s: %s" name e))
      {:error-message (str "Error calling prompt: " (.getMessage e))})))
