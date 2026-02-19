(ns eca.features.skills
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [eca.config :as config]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn ^:private skill-file->skill [skill-file]
  (let [{:keys [name description body]} (shared/parse-md (slurp (str skill-file)))]
    (when (and name description)
      {:name name
       :description description
       :body body
       :dir (str (fs/canonicalize (fs/parent skill-file)))})))

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

(defn all [config roots]
  (concat []
          (when-not (:pureConfig config)
            (global-skills))
          (local-skills roots)))
