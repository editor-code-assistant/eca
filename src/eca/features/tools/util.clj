(ns eca.features.tools.util
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [eca.shared :as shared]))

(defmulti tool-call-details-before-invocation
  "Return the tool call details before invoking the tool."
  (fn [name _arguments _server _ctx] (keyword name)))

(defmethod tool-call-details-before-invocation :default [_name _arguments _server _ctx]
  nil)

(defmulti tool-call-details-after-invocation
  "Return the tool call details after invoking the tool."
  (fn [name _arguments _details _result] (keyword name)))

(defmethod tool-call-details-after-invocation :default [_name _arguments details _result]
  details)

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
