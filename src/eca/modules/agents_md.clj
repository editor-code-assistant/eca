(ns eca.modules.agents-md
  "The `:eca/agents-md` module: the AGENTS.md files, in every chat's system
   prompt: each workspace's, its parents' with `includeParentAgentsFiles`,
   and the global one, with the files they mention with `@path`."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [eca.features.context :as f.context]
   [eca.llm-api :as llm-api]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn ^:private extract-at-mentions
  "Extract all @path mentions from content. Supports relative and absolute paths with any extension."
  [content]
  (let [pattern #"@([^\s\)]+\.\w+)"
        matches (re-seq pattern content)]
    (map second matches)))

(defn ^:private parse-agents-file
  ([path] (parse-agents-file path #{}))
  ([path visited]
   (if (contains? visited path)
     []
     (if-let [root-content (llm-api/refine-file-context path nil)]
       (let [visited' (conj visited path)
             at-mentions (extract-at-mentions root-content)
             parent-dir (str (fs/parent path))
             resolved-paths (map (fn [mention]
                                   (cond
                                     ;; Absolute path
                                     (string/starts-with? mention "/")
                                     (str (fs/canonicalize (fs/file mention)))

                                     ;; Relative path (./... or ../...)
                                     (or (string/starts-with? mention "./")
                                         (string/starts-with? mention "../"))
                                     (str (fs/canonicalize (fs/file parent-dir mention)))

                                     ;; Simple filename, relative to current file's directory
                                     :else
                                     (str (fs/canonicalize (fs/file parent-dir mention)))))
                                 at-mentions)
             ;; Deduplicate resolved paths
             unique-paths (distinct resolved-paths)
             ;; Recursively parse all mentioned files
             nested-results (mapcat #(parse-agents-file % visited') unique-paths)]
         (concat [{:type :agents-file
                   :path path
                   :content root-content}]
                 nested-results))
       []))))

(defn ^:private ancestor-paths
  "Return all ancestor directories of `path` in outermost-first order.
   Excludes `path` itself."
  [path]
  (loop [p (fs/parent path)
         acc '()]
    (if (nil? p)
      (vec acc)
      (recur (fs/parent p) (conj acc p)))))

(defn agents-file-contexts
  "Search for AGENTS.md file both in workspaceRoot and global config dir.
   When `:includeParentAgentsFiles` is true in `config`, also include
   AGENTS.md files from each workspace's parent directories, ordered
   outermost parent first, then the workspace's own AGENTS.md.
   Process any found @paths mentions recursively, supporting both relative and absolute paths.
   Deduplicates files to avoid reading the same file multiple times."
  [db config]
  ;; TODO make it customizable by agent
  (let [agent-file-name "AGENTS.md"
        include-parents? (boolean (:includeParentAgentsFiles config))
        readable-agent-file-in (fn [dir]
                                 (let [p (fs/path dir agent-file-name)]
                                   (when (fs/readable? p)
                                     (str (fs/canonicalize p)))))
        {:keys [paths seen]}
        (reduce
         (fn [{:keys [paths seen]} {:keys [uri]}]
           (let [ws-path (f.context/safe-canonicalize (shared/uri->filename uri))
                 candidate-dirs (cond-> []
                                  include-parents? (into (ancestor-paths ws-path))
                                  true (conj ws-path))
                 new-paths (->> candidate-dirs
                                (keep readable-agent-file-in)
                                (remove seen))]
             {:paths (into paths new-paths)
              :seen (into seen new-paths)}))
         {:paths [] :seen #{}}
         (:workspace-folders db))
        global-agent-file (readable-agent-file-in (shared/global-config-dir))
        all-paths (cond-> paths
                    (and global-agent-file (not (contains? seen global-agent-file)))
                    (conj global-agent-file))]
    (mapcat parse-agents-file all-paths)))

(defn ^:private always-contexts [{:keys [db config]}]
  (agents-file-contexts db config))

(def module
  {:id :eca/agents-md
   :description "AGENTS.md files: the workspaces', their parents' and the global one, in every chat."
   :config-spec {:includeParentAgentsFiles {:type :boolean :default false}}
   ;; core renders them in the system prompt's :eca/context section
   :contexts {:always #'always-contexts}})
