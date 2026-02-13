(ns eca.features.agents
  "Discovers agent definitions from markdown files (YAML frontmatter + body as system prompt).
   Scans both global (~/.config/eca/agents/*.md) and local (.eca/agents/*.md) directories.
   Compatible with Claude Code / OpenCode agent markdown format."
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.logger :as logger]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[AGENTS-MD]")

(defn ^:private tools-list->approval-map
  [tool-names]
  (when (seq tool-names)
    (into {} (map (fn [name] [(str name) {}]) tool-names))))

(defn ^:private md->agent-config
  [{:keys [description mode model steps tools body]}]
  (cond-> {}
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

(defn ^:private agent-md-file->agent
  [md-file]
  (try
    (let [agent-name (string/lower-case (fs/strip-ext (fs/file-name md-file)))
          content (slurp (str md-file))
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
