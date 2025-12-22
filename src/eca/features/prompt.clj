(ns eca.features.prompt
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.features.tools.mcp :as f.mcp]
   [eca.logger :as logger]
   [eca.shared :refer [multi-str] :as shared]
   [selmer.parser :as selmer])
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

(defn ^:private eca-chat-prompt [behavior config]
  (let [behavior-config (get-in config [:behavior behavior])
        prompt (:systemPrompt behavior-config)
        legacy-prompt-file (:systemPromptFile behavior-config)]
    (cond
      prompt
      prompt

      ;; behavior with absolute path
      (and legacy-prompt-file (string/starts-with? legacy-prompt-file "/"))
      (slurp legacy-prompt-file)

      ;; Built-in or resource path
      legacy-prompt-file
      (load-builtin-prompt (some-> legacy-prompt-file (string/replace-first #"prompts/" "")))

      ;; Fallback for unknown behavior
      :else
      (load-builtin-prompt "agent_behavior.md"))))

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

(defn build-chat-instructions [refined-contexts rules repo-map* behavior config chat-id all-tools db]
  (let [selmer-ctx (->base-selmer-ctx all-tools db)]
    (multi-str
     (selmer/render (eca-chat-prompt behavior config) selmer-ctx)
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
        (contexts-str refined-contexts repo-map* (get-in db [:chats chat-id :startup-context]))])
     ""
     (selmer/render (load-builtin-prompt "additional_system_info.md") selmer-ctx))))

(defn build-rewrite-instructions [text path full-text range all-tools config db]
  (let [legacy-prompt-file (-> config :rewrite :systemPromptFile)
        prompt (-> config :rewrite :systemPrompt)
        prompt-str (cond
                     prompt
                     prompt

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

(defn init-prompt [all-tools db]
  (selmer/render
   (init-prompt-template)
   (->base-selmer-ctx all-tools db)))

(defn title-prompt []
  (title-prompt-template))

(defn compact-prompt [additional-input all-tools config db]
  (selmer/render
   (or (:compactPrompt config)
        ;; legacy
       (compact-prompt-template (:compactPromptFile config)))
   (merge
    (->base-selmer-ctx all-tools db)
    {:additionalUserInput (if additional-input
                            (format "You MUST respect this user input in the summarization: %s." additional-input)
                            "")})))

(defn inline-completion-prompt [config]
  (let [legacy-prompt-file (get-in config [:completion :systemPromptFile])
        prompt (get-in config [:completion :systemPrompt])]
    (cond
      prompt
      prompt

      ;; Absolute path
      (and legacy-prompt-file (string/starts-with? legacy-prompt-file "/"))
      (slurp legacy-prompt-file)

      ;; Resource path
      :else
      (load-builtin-prompt (some-> legacy-prompt-file (string/replace-first #"prompts/" ""))))))

(defn get-prompt! [^String name ^Map arguments db]
  (logger/info logger-tag (format "Calling prompt '%s' with args '%s'" name arguments))
  (try
    (let [result (f.mcp/get-prompt! name arguments db)]
      (logger/debug logger-tag "Prompt result: " result)
      result)
    (catch Exception e
      (logger/warn logger-tag (format "Error calling prompt %s: %s" name e))
      {:error-message (str "Error calling prompt: " (.getMessage e))})))
