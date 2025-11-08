(ns eca.features.tools.util
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [eca.logger :as logger]
   [eca.shared :as shared]))

(def ^:private logger-tag "[TOOLS-UTIL]")

(defmulti tool-call-details-before-invocation
  "Return the tool call details before invoking the tool."
  (fn [full-name _arguments _server _ctx] (keyword full-name)))

(defmethod tool-call-details-before-invocation :default [_full-name _arguments _server _ctx]
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
  (fn [full-name _resource-kwd _resource] (keyword full-name)))

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

(defn workspace-root-paths
  "Returns a vector of workspace root absolute paths from `db`."
  [db]
  (mapv (comp shared/uri->filename :uri) (:workspace-folders db)))

(defn path-outside-workspace?
  "Returns true if `path` is outside any workspace root in `db`.
   Works for existing or non-existing paths by absolutizing."
  [db path]
  (let [p (when path (str (fs/absolutize path)))
        roots (workspace-root-paths db)]
    (and p (not-any? #(fs/starts-with? p %) roots))))

(defn eca-cache-base-dir
  "Get the base ECA cache directory.
   Returns: ~/.cache/eca/ (or $XDG_CACHE_HOME/eca/)"
  []
  (let [cache-home (or (System/getenv "XDG_CACHE_HOME")
                       (io/file (System/getProperty "user.home") ".cache"))]
    (io/file cache-home "eca")))

(defn eca-shell-output-cache-file?
  "Check if a path is within the ECA shell output cache directory.
   This is used to auto-allow reading shell output files without additional approval,
   since the shell command that generated them was already approved."
  [path]
  (when path
    (try
      (let [abs-path (str (fs/canonicalize path))
            cache-base (str (eca-cache-base-dir))]
        (and (fs/starts-with? abs-path cache-base)
             (string/includes? abs-path "shell-output")))
      (catch Exception _ false))))

(defn require-approval-when-outside-workspace
  "Returns a function suitable for tool `:require-approval-fn` that triggers
   approval when any of the provided `path-keys` in args is outside the
   workspace roots."
  [path-keys]
  (fn [args {:keys [db]}]
    (when (seq path-keys)
      (some (fn [k]
              (when-let [p (get args k)]
                (path-outside-workspace? db p)))
            path-keys))))

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

(defn required-params-error
  "Given a tool `parameters` JSON schema (object) and an args map, return a
  single-text-content error when any required parameter is missing. Returns nil
  if all required parameters are present."
  [parameters args]
  (when-let [req (seq (:required parameters))]
    (let [args (update-keys args name)
          missing (->> req (map name) (filter #(nil? (get args %))) vec)]
      (when (seq missing)
        (single-text-content
         (format "INVALID_ARGS: missing required params: %s"
                 (->> missing (map #(str "`" % "`")) (string/join ", ")))
         :error)))))
