(ns eca.features.skills
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [eca.config :as config]
   [eca.features.skills.builtin :as skills.builtin]
   [eca.interpolation :as interpolation]
   [eca.logger :as logger]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[skills]")

(defn ^:private skill-file->skill [skill-file]
  (try
    (let [content (interpolation/replace-dynamic-strings (slurp (str skill-file)) (fs/parent skill-file) nil)
          {:keys [name description body]} (shared/parse-md (or content ""))]
      (when (and name description)
        {:name name
         :description description
         :body body
         :arguments (shared/extract-args-from-content body)
         :dir (str (fs/canonicalize (fs/parent skill-file)))}))
    (catch Exception e
      (logger/warn logger-tag (format "Error parsing skill file '%s': %s" (str skill-file) (.getMessage e)))
      nil)))

(defn global-skills-dir []
  (let [xdg-config-home (or (config/get-env "XDG_CONFIG_HOME")
                            (io/file (config/user-home) ".config"))]
    (io/file xdg-config-home "eca" "skills")))

(defn ^:private agents-global-skills-dir
  "User-level skills dir from the cross-tool agentskills.io convention,
   also read by other agents like Codex, Cursor, Copilot and pi."
  []
  (io/file (config/user-home) ".agents" "skills"))

(defn ^:private skill-file? [file]
  (and (not (fs/directory? file))
       (= "SKILL.md" (fs/file-name file))))

(defn ^:private skill-files [path]
  (cond
    (not (fs/exists? path)) []
    (fs/directory? path) (filter skill-file? (fs/glob path "**" {:follow-links true}))
    :else [path]))

(defn ^:private plugin-skill [plugin file]
  (when-let [skill (skill-file->skill file)]
    (cond-> skill
      plugin (assoc :plugin plugin
                    :name (shared/prefixed-name plugin (:name skill))))))

(defn ^:private global-skills []
  (->> [(global-skills-dir)
        (agents-global-skills-dir)]
       (mapcat skill-files)
       (keep skill-file->skill)))

(defn ^:private local-skills [roots]
  (->> roots
       (mapcat (fn [{:keys [uri]}]
                 (let [root (shared/uri->filename uri)]
                   (mapcat skill-files
                           [(fs/file root ".eca" "skills")
                            (fs/file root ".agents" "skills")]))))
       (keep skill-file->skill)))

(defn ^:private plugin-skills [plugin-skill-dirs]
  (->> plugin-skill-dirs
       (mapcat (fn [entry]
                 (let [{:keys [dir plugin]} (if (string? entry)
                                              {:dir entry}
                                              entry)]
                   (when dir
                     (keep #(plugin-skill plugin %)
                           (skill-files (fs/file dir)))))))))

(defn ^:private config-skills [config roots]
  (->> (get config :skills)
       (mapcat
        (fn [{:keys [path]}]
          (let [path (str (fs/expand-home path))]
            (if (fs/absolute? path)
              (skill-files path)
              (mapcat (fn [{:keys [uri]}]
                        (skill-files (fs/file (shared/uri->filename uri) path)))
                      roots)))))
       (keep skill-file->skill)))

(defn ^:private distinct-by-name
  "Keeps only the first skill of each name, preserving order."
  [skills]
  (:skills
   (reduce (fn [{:keys [seen] :as acc} {:keys [name] :as skill}]
             (if (contains? seen name)
               acc
               (-> acc
                   (update :seen conj name)
                   (update :skills conj skill))))
           {:seen #{} :skills []}
           skills)))

(defn all
  "Returns all available skills, one per name. When names clash, the first
   source wins: configured paths, workspace (.eca/skills then .agents/skills),
   global (~/.config/eca/skills then ~/.agents/skills), plugins, built-ins."
  [config roots]
  (distinct-by-name
   (concat (config-skills config roots)
           (local-skills roots)
           (when-not (:pureConfig config)
             (global-skills))
           (plugin-skills (:pluginSkillDirs config))
           (skills.builtin/all))))
