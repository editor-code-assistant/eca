(ns eca.features.hooks
  (:require
   [babashka.process :as p]
   [cheshire.core :as json]
   [eca.logger :as logger]
   [eca.shared :as shared]))

(def ^:private logger-tag "[HOOK]")

(defn ^:private hook-matches? [type data hook]
  (case type
    (:preToolCall :postToolCall)
    (re-matches (re-pattern (or (:matcher hook) ".*"))
                (str (:server data) "__" (:name data)))

    true))

(defn ^:private run-hook-action! [action name data db]
  (case (:type action)
    "shell" (let [cwd (some-> (:workspace-folders db)
                              first
                              :uri
                              shared/uri->filename)
                  shell (:shell action)
                  input (json/generate-string (merge {:hook-name name} data))]
              (logger/info logger-tag (format "Running hook '%s' shell '%s' with input '%s'" name shell input))
              (let [{:keys [out err exit]} @(p/sh {:dir cwd
                                                   :continue true}
                                                  "bash" "-c" shell "--" input)]
                [exit out err]))
    (logger/warn logger-tag (format "Unknown hook action %s for %s" (:type action) name))))

(defn trigger-if-matches!
  "Run hook of specified type if matches any config for that type"
  [type data {:keys [on-before-execute on-after-execute]} db config]
  (let [outputs* (atom [])
        status* (atom 0)]
    (doseq [[name hook] (:hooks config)]
      (when (= type (keyword (:type hook)))
        (when (hook-matches? type data hook)
          (let [id (str (random-uuid))]
            (on-before-execute {:id id
                                :name name})
            (doseq [action (:actions hook)]
              (when-let [[status output] (run-hook-action! action name data db)]
                (reset! status* (max @status* status))
                (swap! outputs* conj output)))
            (on-after-execute {:id id
                               :name name
                               :status @status*
                               :outputs @outputs*})))))))
