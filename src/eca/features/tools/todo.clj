(ns eca.features.tools.todo
  (:require
   [clojure.string :as str]
   [eca.features.tools.util :as tools.util]))

(set! *warn-on-reflection* true)

;; --- Helpers ---

(def ^:private empty-todo {:goal "" :next-id 1 :tasks []})
(def ^:private valid-priorities #{:high :medium :low})

(defn- error [msg]
  (tools.util/single-text-content msg true))

(defn- find-task [state id]
  (some #(when (= id (:id %)) %) (:tasks state)))

(defn- task-index
  "Index tasks by ID for O(1) lookups."
  [tasks]
  (into {} (map (juxt :id identity)) tasks))

(defn- active-blockers
  "Sorted seq of IDs blocking task `id` (blockers not yet :done), or nil."
  [tasks-by-id id]
  (when-let [task (get tasks-by-id id)]
    (->> (:blocked-by task)
         (remove #(= :done (:status (get tasks-by-id %))))
         sort
         seq)))

;; --- State management ---

(defn get-todo
  "Get TODO state for the chat."
  [db chat-id]
  (get-in db [:chats chat-id :todo] empty-todo))

(defn- mutate-todo!
  "Atomically update TODO for a chat via compare-and-set loop.
   `mutate-fn` receives current state, returns {:state ...} or {:error true ...}.
   Extra keys in the result map are preserved."
  [db* chat-id mutate-fn]
  (loop []
    (let [db @db*
          state (get-todo db chat-id)
          result (mutate-fn state)]
      (if (:error result)
        result
        (let [new-db (assoc-in db [:chats chat-id :todo] (:state result))]
          (if (compare-and-set! db* db new-db)
            result
            (recur)))))))

;; --- Validation ---
;; Contract: validators return nil on success or an error response map.
;; Values flow through function arguments, not through validator return values.

(defn- require-nonblank [label v]
  (cond
    (nil? v)
    (error (str label " must be a non-blank string"))

    (not (string? v))
    (error (str label " must be a string"))

    (str/blank? v)
    (error (str label " must be a non-blank string"))))

(defn- validate-priority [priority]
  (when-not (and (string? priority)
                 (contains? valid-priorities (keyword priority)))
    (error (format "Invalid priority: %s. Allowed: high, medium, low" priority))))

(defn- validate-done-when [done-when]
  (when-not (or (nil? done-when) (string? done-when))
    (error "done_when must be a string when provided")))

(defn- resolve-ids
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

(defn- resolve-id
  "Validate a single task ID. Returns [id error]."
  [state raw-id]
  (cond
    (not (integer? raw-id)) [nil (error "Task ID must be an integer")]
    (not (find-task state raw-id)) [nil (error (format "Task %d not found" raw-id))]
    :else [raw-id nil]))

(defn- validate-blocked-by
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

(defn- detect-cycle
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

(defn- make-task
  "Build a task map from string-keyed JSON input. Returns {:task ...} or error response."
  [raw-task id allowed-ids]
  (let [{:strs [content priority blocked_by done_when]} raw-task]
    (or (require-nonblank "content" content)
        (when (contains? raw-task "status")
          (error "Cannot set status when creating tasks. New tasks always start as pending; use 'start' or 'complete'."))
        (when (contains? raw-task "priority")
          (validate-priority priority))
        (when (contains? raw-task "done_when")
          (validate-done-when done_when))
        (let [[blocked-by err] (validate-blocked-by blocked_by allowed-ids id)]
          (or err
              {:task {:id id
                      :content content
                      :status :pending
                      :priority (if (contains? raw-task "priority")
                                  (keyword priority)
                                  :medium)
                      :done-when done_when
                      :blocked-by blocked-by}})))))

(defn- build-tasks
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

(defn- status-counts [tasks]
  (let [freqs (frequencies (map :status tasks))]
    {:done (freqs :done 0) :in-progress (freqs :in-progress 0) :pending (freqs :pending 0)}))

(defn- summary-line [tasks]
  (let [{:keys [done in-progress pending]} (status-counts tasks)]
    (format "%d done, %d in progress, %d pending" done in-progress pending)))

(defn- summary-line-with-total [tasks]
  (format "%s, %d total" (summary-line tasks) (count tasks)))

(defn- read-task-line [{:keys [id content status priority done-when blocked-by]}]
  (let [base (format "- #%d [%s] [%s] %s" id (name status) (name priority) content)]
    (str/join "\n"
              (cond-> [base]
                (and (string? done-when) (not-empty done-when)) (conj (str "  done_when: " done-when))
                (seq blocked-by) (conj (str "  blocked_by: " (str/join ", " (sort blocked-by))))))))

(defn- read-text [state]
  (let [tasks (:tasks state)]
    (str "Goal: " (or (not-empty (:goal state)) "(none)") "\n"
         "Summary: " (summary-line-with-total tasks) "\n"
         "Tasks:\n"
         (if (seq tasks)
           (str/join "\n" (map read-task-line tasks))
           "(none)"))))

(defn- todo-details
  "Structured data for client rendering."
  [state]
  (let [{:keys [goal tasks]} state
        tasks-by-id (task-index tasks)
        {:keys [done in-progress pending]} (status-counts tasks)]
    {:type :todoState
     :goal (or goal "")
     :inProgressTaskIds (mapv :id (filter #(= :in-progress (:status %)) tasks))
     :tasks (mapv (fn [{:keys [id content status priority done-when blocked-by]}]
                    (cond-> {:id id :content content
                             :status (name status)
                             :priority (name priority)
                             :isBlocked (boolean (active-blockers tasks-by-id id))}
                      (and (string? done-when) (not-empty done-when)) (assoc :doneWhen done-when)
                      (seq blocked-by) (assoc :blockedBy (vec (sort blocked-by)))))
                  tasks)
     :summary {:done done :inProgress in-progress :pending pending :total (count tasks)}}))

(defn- success [state text]
  (assoc (tools.util/single-text-content text) :details (todo-details state)))

(defn- format-tasks-list [tasks]
  (str/join "\n" (map #(format "- #%d: %s" (:id %) (:content %)) tasks)))

(defmethod tools.util/tool-call-details-after-invocation :todo
  [_name _arguments before-details result _ctx]
  (or (:details result) before-details))

;; --- Operations ---

(defn- op-read [_arguments {:keys [db chat-id]}]
  (let [state (get-todo db chat-id)]
    (success state (read-text state))))

(defn- op-plan [{:strs [goal tasks]} {:keys [db* chat-id]}]
  (or (require-nonblank "goal" goal)
      (when-not (and (sequential? tasks) (seq tasks))
        (error "plan requires 'tasks' (non-empty array)"))
      (let [result (mutate-todo! db* chat-id
                                 (fn [_state]
                                   (let [fresh (assoc empty-todo :goal goal)
                                         built (build-tasks fresh tasks)]
                                     (if (:error built)
                                       built
                                       (or (detect-cycle (:tasks built))
                                           {:state (assoc fresh
                                                          :tasks (:tasks built)
                                                          :next-id (inc (count (:tasks built))))})))))]
        (if (:error result)
          result
          (success (:state result)
                   (str "TODO created with " (count (get-in result [:state :tasks])) " tasks"))))))

(defn- op-add [{:strs [tasks task] :as _arguments} {:keys [db* chat-id]}]
  (let [result (mutate-todo! db* chat-id
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

(def ^:private updatable-keys #{"content" "priority" "done_when" "blocked_by"})

(defn- op-update [{:strs [id task]} {:keys [db* chat-id]}]
  (let [result (mutate-todo! db* chat-id
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
                                       (let [{:strs [content priority done_when blocked_by]} task]
                                         (or (when (contains? task "content")
                                               (require-nonblank "content" content))
                                             (when (contains? task "priority")
                                               (validate-priority priority))
                                             (when (contains? task "done_when")
                                               (validate-done-when done_when))
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
                                                                               (contains? task "content")    (assoc :content content)
                                                                               (contains? task "priority")   (assoc :priority (keyword priority))
                                                                               (contains? task "done_when")  (assoc :done-when done_when)
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

(defn- set-status
  "Set status on tasks by IDs."
  [tasks id-set status]
  (mapv #(if (contains? id-set (:id %)) (assoc % :status status) %) tasks))

(defn- op-start [{:strs [ids]} {:keys [db* chat-id]}]
  (let [result (mutate-todo! db* chat-id
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
                                     {:state (assoc state :tasks (set-status (:tasks state) (set ids) :in-progress))
                                      :ids ids}))))]
    (if (:error result)
      result
      (let [state (:state result)
            started (filter #(contains? (set (:ids result)) (:id %)) (:tasks state))]
        (success state
                 (format "Started %d task(s):\n%s"
                         (count started)
                         (format-tasks-list started)))))))

(defn- op-complete [{:strs [ids]} {:keys [db* chat-id]}]
  (let [result (mutate-todo! db* chat-id
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
                                     {:state (assoc state :tasks (set-status (:tasks state) (set ids) :done))
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

(defn- op-delete [{:strs [ids]} {:keys [db* chat-id]}]
  (let [result (mutate-todo! db* chat-id
                             (fn [state]
                               (let [[ids err] (resolve-ids state ids)]
                                 (or err
                                     (let [id-set (set ids)
                                           remaining (->> (:tasks state)
                                                          (remove #(contains? id-set (:id %)))
                                                          (mapv #(update % :blocked-by (fn [b] (reduce disj b ids)))))]
                                       {:state (assoc state :tasks remaining)
                                        :ids ids
                                        :deleted (filter #(contains? id-set (:id %)) (:tasks state))})))))]
    (if (:error result)
      result
      (success (:state result)
               (format "Deleted %d task(s):\n%s"
                       (count (:deleted result))
                       (format-tasks-list (:deleted result)))))))

(defn- op-clear [_arguments {:keys [db* chat-id]}]
  (let [result (mutate-todo! db* chat-id (fn [_] {:state empty-todo}))]
    (if (:error result)
      result
      (success (:state result) "TODO cleared"))))

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

(defn- execute-todo [arguments ctx]
  (let [op (get arguments "op")
        handler (get ops op)]
    (if handler
      (handler arguments ctx)
      (error (str "Unknown operation: " op)))))

(def definitions
  {"todo"
   {:description (tools.util/read-tool-description "todo")
    :parameters {:type "object"
                 :properties {:op {:type "string"
                                   :enum ["read" "plan" "add" "update" "start" "complete" "delete" "clear"]
                                   :description "Operation to perform"}
                              :id {:type ["integer" "null"]
                                   :description "Task ID (required for update)"}
                              :ids {:type "array"
                                    :items {:type "integer"}
                                    :description "Task IDs (required for start/complete/delete)"}
                              :goal {:type "string"
                                     :description "Overall objective (required for plan)"}
                              :task {:type "object"
                                     :description "Single task data (for add/update)"
                                     :properties {:content {:type "string" :description "Task description"}
                                                  :priority {:type "string" :enum ["high" "medium" "low"]
                                                             :description "Task priority (default: medium)"}
                                                  :done_when {:type "string" :description "Optional acceptance criteria (recommended for non-trivial tasks)"}
                                                  :blocked_by {:type "array" :items {:type "integer"}
                                                               :description "IDs of blocking tasks"}}}
                              :tasks {:type "array"
                                      :description "Array of tasks (required for plan, alternative for add)"
                                      :items {:type "object"
                                              :properties {:content {:type "string" :description "Task description (required)"}
                                                           :priority {:type "string" :enum ["high" "medium" "low"]}
                                                           :done_when {:type "string" :description "Optional acceptance criteria (recommended for non-trivial tasks)"}
                                                           :blocked_by {:type "array" :items {:type "integer"}}}
                                              :required ["content"]}}}
                 :required ["op"]}
    :handler execute-todo}})
