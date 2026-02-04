(ns eca.features.agents
  "Load and parse agent definitions for subagent spawning.

   Agent definitions are Markdown files with YAML frontmatter.
   They can be defined at:
   - Project level: .eca/agents/*.md (highest priority)
   - User level: ~/.config/eca/agents/*.md"
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
      ;; Empty or null
      (or (empty? trimmed)
          (= "null" (str/lower-case trimmed)))
      nil

      ;; Double-quoted string
      (and (str/starts-with? trimmed "\"") (str/ends-with? trimmed "\""))
      (subs trimmed 1 (dec (count trimmed)))

      ;; Single-quoted string
      (and (str/starts-with? trimmed "'") (str/ends-with? trimmed "'"))
      (subs trimmed 1 (dec (count trimmed)))

      ;; Number
      (re-matches #"-?\d+" trimmed)
      (parse-long trimmed)

      ;; Unquoted string
      :else trimmed)))

(defn ^:private parse-yaml-list [lines]
  (loop [remaining lines
         items []]
    (if (empty? remaining)
      [items remaining]
      (let [line (first remaining)
            trimmed (str/trim line)]
        (if (str/starts-with? trimmed "- ")
          (recur (rest remaining)
                 (conj items (parse-yaml-value (subs trimmed 2))))
          [items remaining])))))

(defn ^:private parse-frontmatter [lines]
  (loop [remaining lines
         result {}]
    (if (empty? remaining)
      result
      (let [line (first remaining)]
        (if-let [[_ k v] (re-matches #"^([a-zA-Z_][a-zA-Z0-9_-]*)\s*:\s*(.*)$" line)]
          (let [key (keyword k)
                value-str (str/trim v)]
            (if (empty? value-str)
              ;; Empty value - might be followed by list items
              (let [[list-items rest-lines] (parse-yaml-list (rest remaining))]
                (if (seq list-items)
                  (recur rest-lines (assoc result key list-items))
                  (recur (rest remaining) result)))
              ;; Inline value
              (recur (rest remaining) (assoc result key (parse-yaml-value value-str)))))
          (recur (rest remaining) result))))))

(defn ^:private parse-md [md-file]
  (let [content (slurp (str md-file))
        lines (str/split-lines content)]
    (if (and (seq lines)
             (= "---" (str/trim (first lines))))
      (let [after-opening (rest lines)
            metadata-lines (take-while #(not= "---" (str/trim %)) after-opening)
            body-lines (rest (drop-while #(not= "---" (str/trim %)) after-opening))
            metadata (parse-frontmatter metadata-lines)]
        (assoc metadata :content (str/trim (str/join "\n" body-lines))))
      {:content content})))

(defn ^:private agent-file->agent [agent-file]
  (let [{:keys [name description tools model maxTurns content]} (parse-md agent-file)]
    (when (and name description)
      {:name name
       :description description
       :tools (or tools [])
       :model model
       :max-turns (or maxTurns 25)
       :content content
       :source (str (fs/canonicalize agent-file))})))

(defn global-agents-dir ^java.io.File []
  (let [xdg-config-home (or (config/get-env "XDG_CONFIG_HOME")
                            (io/file (config/get-property "user.home") ".config"))]
    (io/file xdg-config-home "eca" "agents")))

(defn ^:private global-agents
  []
  (let [agents-dir (global-agents-dir)]
    (when (fs/exists? agents-dir)
      (keep agent-file->agent
            (fs/glob agents-dir "*.md" {:follow-links true})))))

(defn ^:private local-agents
  [roots]
  (->> roots
       (mapcat (fn [{:keys [uri]}]
                 (let [agents-dir (fs/file (shared/uri->filename uri) ".eca" "agents")]
                   (when (fs/exists? agents-dir)
                     (fs/glob agents-dir "*.md" {:follow-links true})))))
       (keep agent-file->agent)))

(defn all
  "Returns all available agent definitions.
   Priority: local > global (later definitions override earlier ones by name)."
  [config roots]
  (let [agents-list (concat (when-not (:pureConfig config)
                              (global-agents))
                            (local-agents roots))]
    (->> agents-list
         (reduce (fn [m agent]
                   (assoc m (:name agent) agent))
                 {})
         vals
         vec)))

(defn get-agent
  "Get a specific agent definition by name.
   Returns nil if not found."
  [agent-name config roots]
  (let [agents (all config roots)]
    (first (filter #(= agent-name (:name %)) agents))))
