(ns eca.features.hooks
  (:require
   [babashka.process :as p]
   [cheshire.core :as json]
   [eca.logger :as logger]
   [eca.shared :as shared]))

(def ^:private logger-tag "[HOOK]")

(defn ^:private hook-matches? [type data hook]
  (let [hook-config-type (keyword (:type hook))
        hook-config-type (cond ;; legacy values
                           (= :prePrompt hook-config-type) :preRequest
                           (= :postPrompt hook-config-type) :postRequest
                           :else hook-config-type)]
    (cond
      (not= type hook-config-type)
      false

      (contains? #{:preToolCall :postToolCall} type)
      (re-matches (re-pattern (or (:matcher hook) ".*"))
                  (str (:server data) "__" (:tool-name data)))

      :else
      true)))

(defn ^:private run-hook-action! [action name data db]
  (case (:type action)
    "shell" (let [cwd (some-> (:workspace-folders db)
                              first
                              :uri
                              shared/uri->filename)
                  shell (:shell action)
                  input (json/generate-string (merge {:hook-name name} data))]
              (logger/info logger-tag (format "Running hook '%s' shell '%s' with input '%s'" name shell input))
              (let [{:keys [exit out err]} (p/sh {:dir cwd}
                                                 "bash" "-c" shell "--" input)]
                [exit (not-empty out) (not-empty err)]))
    (logger/warn logger-tag (format "Unknown hook action %s for %s" (:type action) name))))

(defn trigger-if-matches!
  "Run hook of specified type if matches any config for that type"
  [type
   data
   {:keys [on-before-action on-after-action]
    :or {on-before-action identity
         on-after-action identity}}
   db
   config]
  (doseq [[name hook] (:hooks config)]
    (when (hook-matches? type data hook)
      (vec
       (map-indexed (fn [i action]
                      (let [id (str (random-uuid))
                            type (:type action)
                            name (if (> 1 (count (:actions hook)))
                                   (str name "-" (inc i))
                                   name)]
                        (on-before-action {:id id
                                           :name name})
                        (if-let [[status output error] (run-hook-action! action name data db)]
                          (on-after-action {:id id
                                            :name name
                                            :type type
                                            :status status
                                            :output output
                                            :error error})
                          (on-after-action {:id id
                                            :name name
                                            :type type
                                            :status -1}))))
                    (:actions hook))))))
