(ns eca.features.skills
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [eca.config :as config]
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
         :dir (str (fs/canonicalize (fs/parent skill-file)))}))
    (catch Exception e
      (logger/warn logger-tag (format "Error parsing skill file '%s': %s" (str skill-file) (.getMessage e)))
      nil)))

(defn global-skills-dir []
  (let [xdg-config-home (or (config/get-env "XDG_CONFIG_HOME")
                            (io/file (config/get-property "user.home") ".config"))]
    (io/file xdg-config-home "eca" "skills")))

(defn ^:private global-skills []
  (let [skills-dir (global-skills-dir)]
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

(defn ^:private prefixed-skill-name
  "Builds the user-invocation name for a plugin skill.
   Returns just the plugin name when it equals the skill name (dedup),
   otherwise 'plugin:skill'."
  [plugin-name skill-name]
  (if (= plugin-name skill-name)
    plugin-name
    (str plugin-name ":" skill-name)))

(defn ^:private plugin-skills [plugin-skill-dirs]
  (->> plugin-skill-dirs
       (mapcat (fn [entry]
                 (let [{:keys [dir plugin]} (if (string? entry)
                                              {:dir entry}
                                              entry)
                       dir-file (fs/file dir)]
                   (when (and dir (fs/exists? dir-file))
                     (->> (fs/glob dir-file "**/SKILL.md" {:follow-links true})
                          (map (fn [f] {:file f :plugin plugin})))))))
       (keep (fn [{:keys [file plugin]}]
               (when-let [skill (skill-file->skill file)]
                 (cond-> skill
                   plugin (assoc :plugin plugin
                                 :name (prefixed-skill-name plugin (:name skill)))))))))

(defn all [config roots]
  (concat []
          (when-not (:pureConfig config)
            (global-skills))
          (plugin-skills (:pluginSkillDirs config))
          (local-skills roots)))
