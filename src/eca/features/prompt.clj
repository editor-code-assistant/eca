(ns eca.features.prompt
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.features.tools.mcp :as f.mcp]
   [eca.logger :as logger]
   [eca.shared :refer [multi-str]])
  (:import
   [java.util Map]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[PROMPT]")

(defn ^:private base-prompt-template* [] (slurp (io/resource "prompts/eca_base.txt")))
(def ^:private base-prompt-template (memoize base-prompt-template*))

(defn ^:private plan-behavior* [] (slurp (io/resource "prompts/plan_behavior.txt")))
(def ^:private plan-behavior (memoize plan-behavior*))

(defn ^:private agent-behavior* [] (slurp (io/resource "prompts/agent_behavior.txt")))
(def ^:private agent-behavior (memoize agent-behavior*))

(defn ^:private eca-prompt [behavior config]
  (let [prompt (or (some-> (:systemPromptTemplateFile config) slurp)
                   (base-prompt-template))]
    (reduce
     (fn [p [k v]]
       (string/replace p (str "{" (name k) "}") v))
     prompt
     {:behavior (case behavior
                  "plan" (plan-behavior)
                  "agent" (agent-behavior))})))

(defn build-instructions [refined-contexts rules repo-map* behavior config]
  (multi-str
   (eca-prompt behavior config)
   "<rules>"
   (reduce
    (fn [rule-str {:keys [name content]}]
      (str rule-str (format "<rule name=\"%s\">%s</rule>\n" name content)))
    ""
    rules)
   "</rules>"
   ""
   "<contexts>"
   (reduce
    (fn [context-str {:keys [type path content partial uri]}]
      (str context-str (case type
                         :file (if partial
                                 (format "<file partial=true path=\"%s\">...\n%s\n...</file>\n" path content)
                                 (format "<file path=\"%s\">%s</file>\n" path content))
                         :repoMap (format "<repoMap description=\"Workspaces structure in a tree view, spaces represent file hierarchy\" >%s</repoMap>\n" @repo-map*)
                         :mcpResource (format "<resource uri=\"%s\">%s</resource>\n" uri content)
                         "")))
    ""
    refined-contexts)
   "</contexts>"))

(defn get-prompt! [^String name ^Map arguments db]
  (logger/info logger-tag (format "Calling prompt '%s' with args '%s'" name arguments))
  (try
    (let [result (f.mcp/get-prompt! name arguments db)]
      (logger/debug logger-tag "Prompt result: " result)
      result)
    (catch Exception e
      (logger/warn logger-tag (format "Error calling prompt %s: %s" name (.getMessage e)))
      {:error-message (str "Error calling prompt: " (.getMessage e))})))
