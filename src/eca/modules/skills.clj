(ns eca.modules.skills
  "The `:eca/skills` module: skills, `SKILL.md` folders from the config, the
   workspaces, ~/.config/eca/skills, ~/.agents/skills, plugins and ECA itself.
   The system prompt lists their names and descriptions; the model loads one
   with the `skill` tool, and the user with its command."
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [eca.config :as config]
   [eca.features.prompt :as f.prompt]
   [eca.features.tools.util :as tools.util]
   [eca.interpolation :as interpolation]
   [eca.logger :as logger]
   [eca.modules.skills.builtin :as skills.builtin]
   [eca.shared :as shared :refer [multi-str]]))

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
           (skills.builtin/all config))))

;; --- The skill tool and the module

(defn ^:private skill
  [arguments {:keys [db config agent]}]
  (let [skill-name (get arguments "name")
        all-skills (all config (:workspace-folders db))
        skill (first (filter
                      #(= skill-name (:name %))
                      all-skills))]
    (if skill
      (let [body (if-let [handler (:handler-fn skill)]
                   (handler {:db db :config config :skills all-skills :agent agent})
                   (:body skill))]
        {:error false
         :contents [{:type :text
                     :text (format (multi-str "**Skill**: %s"
                                              "**Base directory**: %s"
                                              ""
                                              "%s")
                                   (:name skill)
                                   (or (:dir skill) "(built-in)")
                                   body)}]})
      {:error true
       :contents [{:type :text
                   :text (format "Skill '%s' not found, available skills: %s" skill-name (mapv :name all-skills))}]})))

(def definitions
  {"skill"
   {:description (tools.util/read-tool-description "skill")
    :parameters {:type "object"
                 :properties {"name" {:type "string"
                                      :description "The skill identifier from available skills to load (e.g. review-pr)"}}
                 :required ["name"]}
    :handler #'skill
    :enabled-fn (fn [{:keys [db config]}]
                  (seq (all config (:workspace-folders db))))
    :summary-fn (fn [{:keys [args]}]
                  (if-let [name (get args "name")]
                    (format "Loading skill '%s'" name)
                    "Loading skill"))}})

(defn ^:private skills-command [{:keys [db* config]} _args]
  (reduce
   (fn [s {:keys [name description]}]
     (str s "- " name ": " description "\n"))
   "Skills available:\n\n"
   (all config (:workspace-folders @db*))))

(def module
  {:id :eca/skills
   :description "Skills: SKILL.md folders the model loads when it needs them."
   :config-spec {:skills {:type :array :default []}}
   :config-defaults {:toolCall {:approval {:allow {"eca__skill" {}}}}}
   :system-prompts [{:id :eca/skills :content #'f.prompt/skills-section}]   ; names and descriptions only
   :tools (fn [_ctx] (tools.util/->module-tools definitions))
   ;; each skill is also a command, listed after the built-in ones
   :commands {"skills" {:description "List available skills"
                        :arguments []
                        :handler #'skills-command}}})
