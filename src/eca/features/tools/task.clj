(ns eca.features.tools.task
  (:require
   [clojure.string :as str]
   [eca.features.tools.util :as tools.util]))

(set! *warn-on-reflection* true)

;; --- Helpers ---

(def ^:private empty-task {:next-id 1 :active-summary nil :tasks []})
(def ^:private valid-priorities #{:high :medium :low})

(defn ^:private error [msg]
  (tools.util/single-text-content msg true))

(defn ^:private find-task [state id]
  (some #(when (= id (:id %)) %) (:tasks state)))

(defn ^:private task-index
  "Index tasks by ID for O(1) lookups."
  [tasks]
  (into {} (map (juxt :id identity)) tasks))

(defn ^:private active-blockers
  "Sorted seq of IDs blocking task `id` (blockers not yet :done), or nil."
  [tasks-by-id id]
  (when-let [task (get tasks-by-id id)]
    (->> (:blocked-by task)
         (remove #(= :done (:status (get tasks-by-id %))))
         sort
         seq)))

;; --- State management ---

(defn get-task
  "Get task list state for the chat."
  [db chat-id]
  (get-in db [:chats chat-id :task] empty-task))

(defn ^:private mutate-task!
  "Atomically update task list for a chat via compare-and-set loop.
   `mutate-fn` receives current state, returns {:state ...} or {:error true ...}.
   Extra keys in the result map are preserved."
  [db* chat-id mutate-fn]
  (loop []
    (let [db @db*
          state (get-task db chat-id)
          result (mutate-fn state)]
      (if (:error result)
        result
        (let [new-db (assoc-in db [:chats chat-id :task] (:state result))]
          (if (compare-and-set! db* db new-db)
            result
            (recur)))))))

;; --- Validation ---
;; Contract: validators return nil on success or an error response map.
;; Values flow through function arguments, not through validator return values.

(defn ^:private require-nonblank [label v]
  (cond
    (nil? v)
    (error (str label " must be a non-blank string"))

    (not (string? v))
    (error (str label " must be a string"))

    (str/blank? v)
    (error (str label " must be a non-blank string"))))

(defn ^:private validate-priority [priority]
  (when-not (and (string? priority)
                 (contains? valid-priorities (keyword priority)))
    (error (format "Invalid priority: %s. Allowed: high, medium, low" priority))))

(defn ^:private resolve-ids
  "Validate task IDs. Returns [ids error]."
  [state raw-ids]
  (if-not (and (sequential? raw-ids) (seq raw-ids))
    [nil (error "'ids' must be a non-empty array")]
    (let [ids (vec raw-ids)]
      (cond
        (not-every? integer? ids)
        [nil (error "All IDs must be integers")]

        (not= (count ids) (count (distinct ids)))
        [nil (error "Duplicate IDs are not allowed")]

        :else
        (let [existing-ids (set (map :id (:tasks state)))
              missing (remove existing-ids ids)]
          (if (seq missing)
            [nil (error (format "Tasks not found: %s" (str/join ", " (sort missing))))]
            [ids nil]))))))

(defn ^:private resolve-id
  "Validate a single task ID. Returns [id error]."
  [state raw-id]
  (cond
    (not (integer? raw-id)) [nil (error "Task ID must be an integer")]
    (not (find-task state raw-id)) [nil (error (format "Task %d not found" raw-id))]
    :else [raw-id nil]))

(defn ^:private validate-blocked-by
  "Validate blocked_by references. Returns [normalized-set error]."
  [raw allowed-ids self-id]
  (cond
    (nil? raw) [#{} nil]
    (not (sequential? raw)) [nil (error "blocked_by must be an array")]
    :else
    (let [ids (vec raw)]
      (cond
        (not-every? integer? ids)
        [nil (error "blocked_by must contain integer task IDs")]

        (and self-id (some #{self-id} ids))
        [nil (error (format "Task cannot block itself (id: %d)" self-id))]

        :else
        (let [bad (remove (set allowed-ids) ids)]
          (if (seq bad)
            [nil (error (format "Invalid blocked_by references: %s" (str/join ", " (sort bad))))]
            [(set ids) nil]))))))

(defn ^:private detect-cycle
  "DFS cycle detection. Returns error response if cycle found, nil otherwise."
  [tasks]
  (let [graph (into {} (map (juxt :id #(:blocked-by % #{}))) tasks)]
    (letfn [(dfs [state node path]
              (let [path-set (set path)]
                (cond
                  (contains? path-set node)
                  (let [path-vec (vec path)
                        cycle (conj (subvec path-vec (.indexOf ^java.util.List path-vec node)) node)]
                    (error (format "Dependency cycle detected: %s"
                                   (str/join " -> " (map #(str "Task " %) cycle)))))

                  (contains? state node) state

                  :else
                  (reduce (fn [s dep]
                            (let [res (dfs s dep (conj path node))]
                              (if (:error res) (reduced res) res)))
                          (conj state node)
                          (sort (get graph node #{}))))))]
      (let [result (reduce (fn [state node]
                             (let [res (dfs state node [])]
                               (if (:error res) (reduced res) res)))
                           #{}
                           (sort (keys graph)))]
        (when (:error result) result)))))

;; --- Task construction ---

(defn ^:private make-task
  "Build a task map from string-keyed JSON input. Returns {:task ...} or error response."
  [raw-task id allowed-ids]
  (let [{:strs [subject description priority blocked_by]} raw-task]
    (or (require-nonblank "subject" subject)
        (require-nonblank "description" description)
        (when (contains? raw-task "status")
          (error "Cannot set status when creating tasks. New tasks always start as pending; use 'start' or 'complete'."))
        (when (contains? raw-task "priority")
          (validate-priority priority))
        (let [[blocked-by err] (validate-blocked-by blocked_by allowed-ids id)]
          (or err
              {:task {:id id
                      :subject subject
                      :description description
                      :status :pending
                      :priority (if (contains? raw-task "priority")
                                  (keyword priority)
                                  :medium)
                      :blocked-by blocked-by}})))))

(defn ^:private build-tasks
  "Validate and build a batch of tasks. Returns {:tasks [...]} or error response."
  [state raw-tasks]
  (if-not (and (sequential? raw-tasks) (seq raw-tasks))
    (error "tasks must be a non-empty array")
    (let [next-id (:next-id state 1)
          existing-ids (set (map :id (:tasks state)))
          batch-ids (set (range next-id (+ next-id (count raw-tasks))))
          all-ids (into existing-ids batch-ids)]
      (reduce
       (fn [acc [idx raw]]
         (if-not (map? raw)
           (reduced (error (format "Task at index %d must be an object" idx)))
           (let [result (make-task raw (+ next-id idx) all-ids)]
             (if (:error result)
               (reduced result)
               (update acc :tasks conj (:task result))))))
       {:tasks []}
       (map-indexed vector raw-tasks)))))

;; --- Response formatting ---

(defn ^:private status-counts [tasks]
  (let [freqs (frequencies (map :status tasks))]
    {:done (freqs :done 0) :in-progress (freqs :in-progress 0) :pending (freqs :pending 0)}))

(defn ^:private summary-line [tasks]
  (let [{:keys [done in-progress pending]} (status-counts tasks)]
    (format "%d done, %d in progress, %d pending" done in-progress pending)))

(defn ^:private summary-line-with-total [tasks]
  (format "%s, %d total" (summary-line tasks) (count tasks)))

(defn ^:private read-task-line-full [{:keys [id subject description status priority blocked-by]}]
  (let [base (format "- #%d [%s] [%s] %s" id (name status) (name priority) subject)]
    (str/join "\n"
              (cond-> [base]
                true (conj (str "  description: " description))
                (seq blocked-by) (conj (str "  blocked_by: " (str/join ", " (sort blocked-by))))))))

(defn ^:private read-task-line-short [{:keys [id subject status]}]
  (format "- #%d [%s] %s" id (name status) subject))

(defn ^:private read-text [state]
  (let [tasks (:tasks state)]
    (str (when-let [summary (:active-summary state)]
           (str "Active Summary: " summary "\n"))
         "Summary: " (summary-line-with-total tasks) "\n"
         "Tasks:\n"
         (if (seq tasks)
           (str/join "\n" (map read-task-line-full tasks))
           "(none)"))))

(defn ^:private task-details
  "Structured data for client rendering."
  [state]
  (let [{:keys [tasks active-summary]} state
        tasks-by-id (task-index tasks)
        {:keys [done in-progress pending]} (status-counts tasks)]
    (cond-> {:type :task
             :in-progress-task-ids (mapv :id (filter #(= :in-progress (:status %)) tasks))
             :tasks (mapv (fn [{:keys [id subject description status priority blocked-by]}]
                            (cond-> {:id id
                                     :subject subject
                                     :description description
                                     :status (name status)
                                     :priority (name priority)
                                     :is-blocked (boolean (active-blockers tasks-by-id id))}
                              (seq blocked-by) (assoc :blocked-by (vec (sort blocked-by)))))
                          tasks)
             :summary {:done done :in-progress in-progress :pending pending :total (count tasks)}}
      active-summary (assoc :active-summary active-summary))))

(defn ^:private success [state text]
  (assoc (tools.util/single-text-content text) :details (task-details state)))

(defn ^:private format-tasks-list [tasks]
  (str/join "\n" (map read-task-line-short tasks)))

(defmethod tools.util/tool-call-details-after-invocation :task
  [_name _arguments before-details result _ctx]
  (or (:details result) before-details))

;; --- Operations ---

(defn ^:private op-read [_arguments {:keys [db chat-id]}]
  (let [state (get-task db chat-id)]
    (success state (read-text state))))

(defn ^:private op-plan [{:strs [tasks]} {:keys [db* chat-id]}]
  (or (when-not (and (sequential? tasks) (seq tasks))
        (error "plan requires 'tasks' (non-empty array)"))
      (let [result (mutate-task! db* chat-id
                                 (fn [_state]
                                   (let [built (build-tasks empty-task tasks)]
                                     (if (:error built)
                                       built
                                       (or (detect-cycle (:tasks built))
                                           {:state (assoc empty-task
                                                          :tasks (:tasks built)
                                                          :next-id (inc (count (:tasks built))))})))))]
        (if (:error result)
          result
          (success (:state result)
                   (str "Task list created with " (count (get-in result [:state :tasks])) " tasks"))))))

(defn ^:private op-add [{:strs [tasks task] :as _arguments} {:keys [db* chat-id]}]
  (let [result (mutate-task! db* chat-id
                             (fn [state]
                               (cond
                                 tasks
                                 (let [built (build-tasks state tasks)]
                                   (if (:error built)
                                     built
                                     (or (detect-cycle (into (:tasks state) (:tasks built)))
                                         {:state (-> state
                                                     (update :tasks into (:tasks built))
                                                     (update :next-id + (count (:tasks built))))
                                          :added (:tasks built)})))

                                 (map? task)
                                 (let [result (make-task task
                                                         (:next-id state)
                                                         (set (map :id (:tasks state))))]
                                   (if (:error result)
                                     result
                                     (or (detect-cycle (conj (:tasks state) (:task result)))
                                         {:state (-> state
                                                     (update :tasks conj (:task result))
                                                     (update :next-id inc))
                                          :added [(:task result)]})))

                                 :else
                                 (error "add requires 'task' or 'tasks'"))))]
    (if (:error result)
      result
      (let [added (:added result)
            ids (mapv :id added)]
        (success (:state result)
                 (format "Added %d task(s): %s" (count added) (str/join ", " ids)))))))

(def ^:private updatable-keys #{"subject" "description" "priority" "blocked_by"})

(defn ^:private op-update [{:strs [id task]} {:keys [db* chat-id]}]
  (let [result (mutate-task! db* chat-id
                             (fn [state]
                               (let [[id err] (resolve-id state id)]
                                 (or err
                                     (cond
                                       (not (map? task))
                                       (error "Missing 'task' object")

                                       (contains? task "status")
                                       (error "Cannot set status via update. Use 'start' or 'complete'.")

                                       (empty? (select-keys task updatable-keys))
                                       (error "No updatable fields in task")

                                       :else
                                       (let [{:strs [subject description priority blocked_by]} task]
                                         (or (when (contains? task "subject")
                                               (require-nonblank "subject" subject))
                                             (when (contains? task "description")
                                               (require-nonblank "description" description))
                                             (when (contains? task "priority")
                                               (validate-priority priority))
                                             (let [[blocked-by err] (if (contains? task "blocked_by")
                                                                      (validate-blocked-by
                                                                       blocked_by
                                                                       (map :id (:tasks state))
                                                                       id)
                                                                      [nil nil])]
                                               (or err
                                                   (let [new-tasks (mapv (fn [t]
                                                                           (if (= id (:id t))
                                                                             (cond-> t
                                                                               (contains? task "subject") (assoc :subject subject)
                                                                               (contains? task "description") (assoc :description description)
                                                                               (contains? task "priority") (assoc :priority (keyword priority))
                                                                               (contains? task "blocked_by") (assoc :blocked-by blocked-by))
                                                                             t))
                                                                         (:tasks state))]
                                                     (or (detect-cycle new-tasks)
                                                         {:state (assoc state :tasks new-tasks) :task-id id})))))))))))]
    (if (:error result)
      result
      (let [state (:state result)
            task (find-task state (:task-id result))]
        (success state (format "Task %d updated:\n%s"
                               (:task-id result)
                               (format-tasks-list [task])))))))

(defn ^:private set-status
  "Set status on tasks by IDs."
  [tasks id-set status]
  (mapv #(if (contains? id-set (:id %)) (assoc % :status status) %) tasks))

(defn ^:private op-start [{:strs [ids active_summary]} {:keys [db* chat-id]}]
  (or (require-nonblank "active_summary" active_summary)
      (let [result (mutate-task! db* chat-id
                                 (fn [state]
                                   (let [tasks-by-id (task-index (:tasks state))
                                         [ids err] (resolve-ids state ids)]
                                     (or err
                                         (some (fn [id]
                                                 (let [task (get tasks-by-id id)]
                                                   (cond
                                                     (= :done (:status task))
                                                     (error (format "Cannot start task %d: already done" id))

                                                     (seq (active-blockers tasks-by-id id))
                                                     (error (format "Cannot start task %d: blocked by %s"
                                                                    id (str/join ", " (active-blockers tasks-by-id id)))))))
                                               ids)
                                         {:state (-> state
                                                     (assoc :tasks (set-status (:tasks state) (set ids) :in-progress))
                                                     (assoc :active-summary active_summary))
                                          :ids ids}))))]
        (if (:error result)
          result
          (let [state (:state result)
                started (filter #(contains? (set (:ids result)) (:id %)) (:tasks state))]
            (success state
                     (format "Started %d task(s):\n%s"
                             (count started)
                             (format-tasks-list started))))))))

(defn ^:private clean-summary-if-no-in-progress [state]
  (if (empty? (filter #(= :in-progress (:status %)) (:tasks state)))
    (assoc state :active-summary nil)
    state))

(defn ^:private op-complete [{:strs [ids]} {:keys [db* chat-id]}]
  (let [result (mutate-task! db* chat-id
                             (fn [state]
                               (let [tasks-by-id (task-index (:tasks state))
                                     [ids err] (resolve-ids state ids)]
                                 (or err
                                     (some (fn [id]
                                             (let [task (get tasks-by-id id)
                                                   blockers (active-blockers tasks-by-id id)]
                                               (when (and (not= :done (:status task))
                                                          (seq blockers))
                                                 (error (format "Cannot complete task %d: blocked by %s"
                                                                id
                                                                (str/join ", " blockers))))))
                                           ids)
                                     {:state (-> state
                                                 (assoc :tasks (set-status (:tasks state) (set ids) :done))
                                                 clean-summary-if-no-in-progress)
                                      :ids ids}))))]
    (if (:error result)
      result
      (let [state (:state result)
            tasks-by-id (task-index (:tasks state))
            ids (set (:ids result))
            unblocked (keep (fn [t]
                              (when (and (= :pending (:status t))
                                         (some ids (:blocked-by t))
                                         (not (active-blockers tasks-by-id (:id t))))
                                (:id t)))
                            (:tasks state))
            completed (filter #(contains? ids (:id %)) (:tasks state))]
        (success state
                 (str (format "Completed %d task(s):\n%s"
                              (count completed)
                              (format-tasks-list completed))
                      (when (seq unblocked)
                        (format "\nUnblocked: %s" (str/join ", " unblocked)))))))))

(defn ^:private op-delete [{:strs [ids]} {:keys [db* chat-id]}]
  (let [result (mutate-task! db* chat-id
                             (fn [state]
                               (let [[ids err] (resolve-ids state ids)]
                                 (or err
                                     (let [id-set (set ids)
                                           remaining (->> (:tasks state)
                                                          (remove #(contains? id-set (:id %)))
                                                          (mapv #(update % :blocked-by (fn [b] (reduce disj b ids)))))]
                                       {:state (-> state
                                                   (assoc :tasks remaining)
                                                   clean-summary-if-no-in-progress)
                                        :ids ids
                                        :deleted (filter #(contains? id-set (:id %)) (:tasks state))})))))]
    (if (:error result)
      result
      (success (:state result)
               (format "Deleted %d task(s):\n%s"
                       (count (:deleted result))
                       (format-tasks-list (:deleted result)))))))

(defn ^:private op-clear [_arguments {:keys [db* chat-id]}]
  (let [result (mutate-task! db* chat-id (fn [_] {:state empty-task}))]
    (if (:error result)
      result
      (success (:state result) "Task list cleared"))))

;; --- Dispatch ---

(def ^:private ops
  {"read" op-read
   "plan" op-plan
   "add" op-add
   "update" op-update
   "start" op-start
   "complete" op-complete
   "delete" op-delete
   "clear" op-clear})

(defn ^:private execute-task [arguments ctx]
  (let [op (get arguments "op")
        handler (get ops op)]
    (if handler
      (handler arguments ctx)
      (error (str "Unknown operation: " op)))))

(def definitions
  {"task"
   {:description (tools.util/read-tool-description "task")
    :parameters {:type "object"
                 :properties {:op {:type "string"
                                   :enum ["read" "plan" "add" "update" "start" "complete" "delete" "clear"]
                                   :description "Operation to perform"}
                              :id {:type ["integer" "null"]
                                   :description "Task ID (required for update)"}
                              :ids {:type "array"
                                    :items {:type "integer"}
                                    :description "Task IDs (required for start/complete/delete)"}
                              :active_summary {:type "string"
                                              :description "Summary of what will be done in the current active session. Required for start operation."}
                              :task {:type "object"
                                     :description "Single task data (for add/update)"
                                     :properties {:subject {:type "string" :description "Task subject/title (required)"}
                                                  :description {:type "string" :description "Detailed description of the task (required)"}
                                                  :priority {:type "string" :enum ["high" "medium" "low"]
                                                             :description "Task priority (default: medium)"}
                                                  :blocked_by {:type "array" :items {:type "integer"}
                                                               :description "IDs of blocking tasks"}}}
                              :tasks {:type "array"
                                      :description "Array of tasks (required for plan, alternative for add)"
                                      :items {:type "object"
                                              :properties {:subject {:type "string" :description "Task subject/title (required)"}
                                                           :description {:type "string" :description "Detailed description of the task (required)"}
                                                           :priority {:type "string" :enum ["high" "medium" "low"]}
                                                           :blocked_by {:type "array" :items {:type "integer"}}}
                                              :required ["subject" "description"]}}}
                 :required ["op"]}
    :handler execute-task}})
