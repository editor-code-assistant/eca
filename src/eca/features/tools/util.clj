(ns eca.features.tools.util
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [eca.logger :as logger]
   [eca.shared :as shared]))

(def ^:private logger-tag "[TOOLS-UTIL]")

(defmulti tool-call-details-before-invocation
  "Return the tool call details before invoking the tool."
  (fn [name _arguments _server _ctx] (keyword name)))

(defmethod tool-call-details-before-invocation :default [_name _arguments _server _ctx]
  nil)

(defmulti tool-call-details-after-invocation
  "Return the tool call details after invoking the tool."
  (fn [name _arguments _before-details _result] (keyword name)))

(defn ^:private json-outputs-if-any [result]
  (when-let [jsons (->> (:contents result)
                        (keep (fn [content]
                                (when (= :text (:type content))
                                  (let [text (string/trim (:text content))]
                                    (when (or (and (string/starts-with? text "{")
                                                   (string/ends-with? text "}"))
                                              (and (string/starts-with? text "[")
                                                   (string/ends-with? text "]")))
                                      (try
                                        (json/generate-string (json/parse-string text) {:pretty true})
                                        (catch Exception e
                                          (logger/warn logger-tag "Could not pretty format json text output for %s: %s" content (.getMessage e))
                                          nil)))))))
                        seq)]
    {:type :jsonOutputs
     :jsons jsons}))

(defmethod tool-call-details-after-invocation :default [_name _arguments before-details result]
  (or before-details
      (json-outputs-if-any result)))

(defmulti tool-call-destroy-resource!
  "Destroy the tool call resource."
  (fn [name _resource-kwd _resource] (keyword name)))

(defmethod tool-call-destroy-resource! :default [_name _resource-kwd _resource]
  ;; By default, do nothing
  )

(defn single-text-content [text & [error]]
  {:error (boolean error)
   :contents [{:type :text
               :text text}]})

(defn workspace-roots-strs [db]
  (->> (:workspace-folders db)
       (map #(shared/uri->filename (:uri %)))
       (string/join "\n")))

(defn command-available? [command & args]
  (try
    (zero? (:exit (apply shell/sh (concat [command] args))))
    (catch Exception _ false)))

(defn invalid-arguments [arguments validator]
  (first (keep (fn [[key pred error-msg]]
                 (let [value (get arguments key)]
                   (when-not (pred value)
                     (single-text-content (string/replace error-msg (str "$" key) (str value))
                                          :error))))
               validator)))

(defn read-tool-description
  "Read tool description from prompts/tools/<tool-name>.md file"
  [tool-name]
  (-> (io/resource (str "prompts/tools/" tool-name ".md"))
      (slurp)))
