(ns eca.features.agents
  "Discovers agent definitions from markdown files (YAML frontmatter + body as system prompt).
   Scans both global (~/.config/eca/agents/*.md) and local (.eca/agents/*.md) directories.
   Compatible with Claude Code / OpenCode agent markdown format."
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.interpolation :as interpolation]
   [eca.logger :as logger]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[AGENTS-MD]")

(def ^:private tool-arg-name
  "Maps tool names to the argument name used for regex pattern matching in argsMatchers.
   Only tools that support pattern-based approval need an entry here."
  {"eca__shell_command" "command"})

(defn ^:private parse-tool-entry
  "Parses a tool entry string into [tool-name config].
   Plain names like 'eca__read_file' -> ['eca__read_file' {}]
   Pattern entries like 'eca__shell_command(npm run .*)' -> ['eca__shell_command' {:argsMatchers {'command' ['npm run .*']}}]"
  [entry]
  (let [s (str entry)]
    (if-let [[_ tool-name pattern] (re-matches #"(.+?)\((.+)\)" s)]
      (if-let [arg-name (get tool-arg-name tool-name)]
        [tool-name {:argsMatchers {arg-name [pattern]}}]
        (do (logger/warn logger-tag (format "Tool '%s' has pattern '%s' but no arg-name mapping in tool-arg-name; pattern will be ignored" tool-name pattern))
            [tool-name {}]))
      [s {}])))

(defn ^:private tools-list->approval-map
  [tool-entries]
  (when (seq tool-entries)
    (reduce
     (fn [acc entry]
       (let [[tool-name config] (parse-tool-entry entry)]
         (if (contains? acc tool-name)
           ;; Merge argsMatchers patterns for repeated tool entries
           (update-in acc [tool-name :argsMatchers]
                      (fn [existing new-matchers]
                        (merge-with into existing new-matchers))
                      (:argsMatchers config))
           (assoc acc tool-name config))))
     {}
     tool-entries)))

(defn ^:private md->agent-config
  [{:keys [description mode model steps tools body inherit]}]
  (cond-> {}
    inherit (assoc :inherit (str inherit))
    description (assoc :description description)
    mode (assoc :mode (str mode))
    model (assoc :defaultModel (str model))
    steps (assoc :maxSteps (long steps))
    (seq body) (assoc :systemPrompt body)
    tools (assoc :toolCall
                 (let [tools-map (if (map? tools) tools (into {} tools))]
                   (cond-> {:approval {}}
                     (get tools-map "byDefault")
                     (assoc-in [:approval :byDefault] (get tools-map "byDefault"))

                     (get tools-map "allow")
                     (assoc-in [:approval :allow] (tools-list->approval-map (get tools-map "allow")))

                     (get tools-map "deny")
                     (assoc-in [:approval :deny] (tools-list->approval-map (get tools-map "deny")))

                     (get tools-map "ask")
                     (assoc-in [:approval :ask] (tools-list->approval-map (get tools-map "ask"))))))))

(defn agent-md-file->agent
  [md-file]
  (try
    (let [agent-name (string/lower-case (fs/strip-ext (fs/file-name md-file)))
          content (slurp (str md-file))
          content (interpolation/replace-dynamic-strings content (fs/parent md-file) nil)
          parsed (shared/parse-md content)
          agent-config (md->agent-config parsed)]
      (when (seq agent-config)
        [agent-name agent-config]))
    (catch Exception e
      (logger/warn logger-tag (format "Error parsing agent file '%s': %s" (str md-file) (.getMessage e)))
      nil)))

(defn ^:private global-md-agents
  []
  (let [agents-dir (io/file (shared/global-config-dir) "agents")]
    (when (fs/exists? agents-dir)
      (keep agent-md-file->agent
            (fs/glob agents-dir "*.md" {:follow-links true})))))

(defn ^:private local-md-agents
  [roots]
  (->> roots
       (mapcat (fn [{:keys [uri]}]
                 (let [agents-dir (fs/file (shared/uri->filename uri) ".eca" "agents")]
                   (when (fs/exists? agents-dir)
                     (fs/glob agents-dir "*.md" {:follow-links true})))))
       (keep agent-md-file->agent)))

(defn all-md-agents
  "Discovers all markdown-defined agents from global and local directories.
   Returns a map of {agent-name agent-config} suitable for merging into config :agent.
   Local agents override global agents of the same name."
  [roots]
  (into {}
        (concat (global-md-agents)
                (local-md-agents roots))))
