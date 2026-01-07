(ns eca.features.tools.skill
  (:require
   [eca.features.skills :as f.skills]
   [eca.features.tools.util :as tools.util]
   [eca.shared :refer [multi-str]]))

(defn ^:private skill
  [arguments {:keys [db]}]
  (let [skill-name (get arguments "name")
        all-skills (f.skills/all (:workspace-folders db))
        skill (first (filter
                      #(= skill-name (:name %))
                      all-skills))]
    (if skill
      {:error false
       :contents [{:type :text
                   :text (format (multi-str "**Skill**: %s"
                                            "**Base directory**: %s"
                                            ""
                                            "%s")
                                 (:name skill)
                                 (:dir skill)
                                 (:body skill))}]}
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
    :summary-fn (fn [{:keys [args]}]
                  (if-let [name (get args "name")]
                    (format "Loading skill '%s'" name)
                    "Loading skill"))}})
