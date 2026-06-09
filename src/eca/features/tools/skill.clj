(ns eca.features.tools.skill
  (:require
   [eca.features.skills :as f.skills]
   [eca.features.tools.util :as tools.util]
   [eca.shared :refer [multi-str]]))

(set! *warn-on-reflection* true)

(defn ^:private skill
  [arguments {:keys [db config]}]
  (let [skill-name (get arguments "name")
        all-skills (f.skills/all config (:workspace-folders db))
        skill (first (filter
                      #(= skill-name (:name %))
                      all-skills))]
    (if skill
      (let [body (if-let [handler (:handler-fn skill)]
                   (handler {:db db :config config :skills all-skills})
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
                  (seq (f.skills/all config (:workspace-folders db))))
    :summary-fn (fn [{:keys [args]}]
                  (if-let [name (get args "name")]
                    (format "Loading skill '%s'" name)
                    "Loading skill"))}})
