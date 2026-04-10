(ns eca.features.tools.background
  (:require
   [clojure.string :as string]
   [eca.features.background-tasks :as bg]
   [eca.features.tools.util :as tools.util]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Action handlers
;; ---------------------------------------------------------------------------

(defn ^:private action-list [_arguments]
  (let [jobs (bg/list-jobs)]
    (if (seq jobs)
      (tools.util/single-text-content
       (string/join
        "\n"
        (map (fn [{:keys [id type label summary status exit-code] :as job}]
               (str "- " id
                    (when summary (str " (" summary ")"))
                    " [" (name type) "] "
                    (name status)
                    (when exit-code (str " (exit " exit-code ")"))
                    " | " (bg/elapsed-str job)
                    " | " label))
             jobs)))
      (tools.util/single-text-content "No background jobs."))))

(defn ^:private action-read-output [{:keys [job-id]}]
  (if-let [result (bg/read-output! job-id)]
    (let [{:keys [lines dropped status exit-code]} result
          output (if (seq lines)
                   (string/join "\n" lines)
                   "(no new output)")]
      (tools.util/single-text-content
       (str "Job " job-id " — " (name status)
            (when exit-code (str " (exit " exit-code ")"))
            (when (pos? dropped) (str "\n[" dropped " lines dropped from buffer]"))
            "\n\n" output)))
    (tools.util/single-text-content (str "Background job " job-id " not found.") true)))

(defn ^:private action-kill [{:keys [job-id]}]
  (if (bg/get-job job-id)
    (if (bg/kill-job! job-id)
      (tools.util/single-text-content (str "Background job " job-id " killed."))
      (let [current-status (:status (bg/get-job job-id))]
        (tools.util/single-text-content
         (str "Background job " job-id " is not running"
              (when current-status (str " (status: " (name current-status) ")")) ".")
         true)))
    (tools.util/single-text-content (str "Background job " job-id " not found.") true)))

;; ---------------------------------------------------------------------------
;; Tool handler
;; ---------------------------------------------------------------------------

(defn ^:private bg-job [arguments _ctx]
  (let [action (get arguments "action")
        job-id (get arguments "job_id")]
    (case action
      "list" (action-list arguments)
      "read_output" (if job-id
                      (action-read-output {:job-id job-id})
                      (tools.util/single-text-content "job_id is required for read_output action." true))
      "kill" (if job-id
               (action-kill {:job-id job-id})
               (tools.util/single-text-content "job_id is required for kill action." true))
      (tools.util/single-text-content (str "Unknown action: " action ". Use list, read_output, or kill.") true))))

;; ---------------------------------------------------------------------------
;; Tool definition
;; ---------------------------------------------------------------------------

(def definitions
  {"bg_job"
   {:description (tools.util/read-tool-description "bg_job")
    :parameters {:type "object"
                 :properties {"action" {:type "string"
                                        :enum ["list" "read_output" "kill"]
                                        :description "The action to perform: list all background jobs, read_output from a job, or kill a job."}
                              "job_id" {:type "string"
                                        :description "The background job ID (e.g. \"job-1\"). Required for read_output and kill actions."}}
                 :required ["action"]}
    :summary-fn (fn [{:keys [args]}]
                  (let [action (get args "action")
                        job-id (get args "job_id")]
                    (case action
                      "list" "Listing background jobs"
                      "read_output" (str "Reading output of " job-id)
                      "kill" (str "Killing " job-id)
                      "Managing background job")))
    :handler #'bg-job}})
