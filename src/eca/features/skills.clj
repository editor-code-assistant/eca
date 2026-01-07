(ns eca.features.skills
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [eca.config :as config]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn ^:private parse-yaml-value
  "Parses a simple YAML value, handling strings (quoted or unquoted), and basic types."
  [s]
  (let [trimmed (str/trim s)]
    (cond
      ;; Double-quoted string
      (and (str/starts-with? trimmed "\"") (str/ends-with? trimmed "\""))
      (subs trimmed 1 (dec (count trimmed)))

      ;; Single-quoted string
      (and (str/starts-with? trimmed "'") (str/ends-with? trimmed "'"))
      (subs trimmed 1 (dec (count trimmed)))

      ;; Unquoted string
      :else trimmed)))

(defn ^:private parse-md
  "Parses YAML front matter and body from a markdown file.
   Front matter must be delimited by --- at the start and end.
   Returns a map with metadata keys and :body (content after front matter)."
  [md-file]
  (let [content (slurp (str md-file))
        lines (str/split-lines content)]
    (if (and (seq lines)
             (= "---" (str/trim (first lines))))
      (let [after-opening (rest lines)
            metadata-lines (take-while #(not= "---" (str/trim %)) after-opening)
            body-lines (rest (drop-while #(not= "---" (str/trim %)) after-opening))
            metadata (into {}
                           (keep (fn [line]
                                   (when-let [[_ k v] (re-matches #"^([a-zA-Z_][a-zA-Z0-9_-]*)\s*:\s*(.*)$" line)]
                                     [(keyword k) (parse-yaml-value v)])))
                           metadata-lines)]
        (assoc metadata :body (str/join "\n" body-lines)))
      {:body content})))

(defn ^:private skill-file->skill [skill-file]
  (let [{:keys [name description body]} (parse-md skill-file)]
    (when (and name description)
      {:name name
       :description description
       :body body
       :dir (str (fs/canonicalize (fs/parent skill-file)))})))

(defn ^:private global-skills []
  (let [xdg-config-home (or (config/get-env "XDG_CONFIG_HOME")
                            (io/file (config/get-property "user.home") ".config"))
        skills-dir (io/file xdg-config-home "eca" "skills")]
    (when (fs/exists? skills-dir)
      (keep skill-file->skill
            (fs/glob skills-dir "**/SKILL.md" {:follow-links true})))))

(defn ^:private local-skills [roots]
  (->> roots
       (mapcat (fn [{:keys [uri]}]
                 (let [skills-dir (fs/file (shared/uri->filename uri) ".eca" "skills")]
                   (when (fs/exists? skills-dir)
                     (fs/glob skills-dir "**/SKILL.md" {:follow-links true})))))
       (keep skill-file->skill)))

(defn all [roots]
  (concat (global-skills)
          (local-skills roots)))
