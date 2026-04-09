(ns eca.features.background-tasks
  "Registry and lifecycle management for background jobs (shell processes,
   future subagents). Jobs live independently of the tool calls that spawn them
   and are cleaned up on ECA shutdown."
  (:require
   [babashka.process :as p]
   [eca.logger :as logger])
  (:import
   [java.io BufferedReader InputStream InputStreamReader]
   [java.time Instant]
   [java.time.temporal ChronoUnit]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[BG-TASKS]")

(def max-jobs 10)
(def ^:private max-output-lines 2000)

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(defonce registry* (atom {:next-id 1 :jobs {}}))

;; ---------------------------------------------------------------------------
;; Output buffer helpers
;; ---------------------------------------------------------------------------

(defn ^:private append-output-line!
  "Append a line to the job's output ring-buffer, dropping oldest lines
   beyond `max-output-lines`."
  [output* line]
  (swap! output*
         (fn [{:keys [lines total-lines]}]
           (let [new-lines (conj lines line)
                 new-lines (if (> (count new-lines) max-output-lines)
                             (subvec new-lines (- (count new-lines) max-output-lines))
                             new-lines)]
             {:lines new-lines
              :total-lines (inc total-lines)}))))

(defn ^:private capture-stream!
  "Read lines from an InputStream in a daemon thread, appending each to the
   job's output buffer. Returns the Thread."
  [output* ^InputStream stream]
  (let [t (Thread.
           (fn []
             (try
               (with-open [reader (BufferedReader. (InputStreamReader. stream "UTF-8"))]
                 (loop []
                   (when-let [line (.readLine reader)]
                     (append-output-line! output* line)
                     (recur))))
               (catch InterruptedException _
                 (.interrupt (Thread/currentThread)))
               (catch java.io.IOException _)
               (catch Exception e
                 (logger/debug logger-tag "Output capture error" {:message (.getMessage e)})))))]
    (.setDaemon t true)
    (.start t)
    t))

;; ---------------------------------------------------------------------------
;; Type-dispatched operations (extensible for future :subagent)
;; ---------------------------------------------------------------------------

(defmulti kill-job-impl
  "Kill the underlying resource for a job. Dispatches on :type."
  (fn [job] (:type job)))

(defmethod kill-job-impl :shell [job]
  (when-let [proc (:process job)]
    (p/destroy-tree proc)))

(defmethod kill-job-impl :default [job]
  (logger/warn logger-tag "No kill implementation for job type" {:type (:type job)}))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn running-count
  "Number of currently running background jobs."
  []
  (count (filter #(= :running (:status %))
                 (sort-by :id (vals (:jobs @registry*))))))

(defn get-job
  "Return the job map for the given ID, or nil."
  [job-id]
  (get-in @registry* [:jobs job-id]))

(defn list-jobs
  "Return all jobs (running and finished)."
  []
  (vals (:jobs @registry*)))

(defn register-shell-job!
  "Register a new background shell job atomically. Returns the job map
   including its generated :id, or nil if the max concurrent limit is reached."
  [{:keys [label process working-directory]}]
  (let [result* (volatile! nil)]
    (swap! registry*
           (fn [{:keys [next-id jobs] :as reg}]
             (if (>= (count (filter #(= :running (:status (val %))) jobs)) max-jobs)
               (do (vreset! result* nil) reg)
               (let [id (str "job-" next-id)
                     output* (atom {:lines [] :total-lines 0})
                     job {:id id
                          :type :shell
                          :label label
                          :status :running
                          :exit-code nil
                          :process process
                          :working-directory working-directory
                          :output* output*
                          :read-cursor* (atom 0)
                          :started-at (Instant/now)
                          :ended-at nil}]
                 (vreset! result* job)
                 (-> reg
                     (assoc :next-id (inc next-id))
                     (assoc-in [:jobs id] job))))))
    (if-let [job @result*]
      (do (logger/info logger-tag "Registered background job" {:id (:id job) :label label})
          job)
      (do (logger/warn logger-tag "Max background jobs reached" {:max max-jobs})
          nil))))

(defn start-output-capture!
  "Start capturing stdout and stderr from a background shell process.
   Returns the capture threads."
  [job ^InputStream out-stream ^InputStream err-stream]
  (let [output* (:output* job)]
    [(capture-stream! output* out-stream)
     (capture-stream! output* err-stream)]))

(defn ^:private update-job-on-exit
  "Atomically update a job's status on process exit, unless already :killed."
  [reg job-id status exit-code]
  (let [current-status (get-in reg [:jobs job-id :status])]
    (if (= :killed current-status)
      reg
      (-> reg
          (assoc-in [:jobs job-id :status] status)
          (assoc-in [:jobs job-id :exit-code] exit-code)
          (assoc-in [:jobs job-id :ended-at] (Instant/now))))))

(defn monitor-process-exit!
  "Start a daemon thread that waits for the process to exit, then updates
   the job's status and exit-code in the registry."
  [job-id process]
  (let [t (Thread.
           (fn []
             (try
               (let [result @process
                     exit-code (:exit result)]
                 (swap! registry* update-job-on-exit job-id
                        (if (zero? exit-code) :completed :failed) exit-code)
                 (logger/info logger-tag "Background job exited" {:id job-id :exit-code exit-code}))
               (catch InterruptedException _
                 (.interrupt (Thread/currentThread)))
               (catch Exception e
                 (swap! registry* update-job-on-exit job-id :failed nil)
                 (logger/warn logger-tag "Background job monitor error" {:id job-id :message (.getMessage e)})))))]
    (.setDaemon t true)
    (.start t)
    t))

(defn read-output!
  "Read new output lines since the last read for the given job.
   Returns {:lines [...] :dropped N :status :kw :exit-code N-or-nil}
   where :dropped is the count of lines that were evicted from the buffer
   before they could be read."
  [job-id]
  (when-let [job (get-job job-id)]
    (let [{:keys [lines total-lines]} @(:output* job)
          prev-cursor (first (swap-vals! (:read-cursor* job) (constantly total-lines)))
          buffer-start (- total-lines (count lines))
          effective-start (max 0 (- prev-cursor buffer-start))
          new-lines (if (< effective-start (count lines))
                      (subvec lines effective-start)
                      [])
          dropped (max 0 (- buffer-start prev-cursor))
          current-job (get-job job-id)]
      {:lines new-lines
       :dropped dropped
       :status (:status current-job)
       :exit-code (:exit-code current-job)})))

(defn kill-job!
  "Kill a running background job. Returns true if killed, false if not
   running or not found."
  [job-id]
  (if-let [job (get-job job-id)]
    (if (= :running (:status job))
      (do
        (kill-job-impl job)
        (swap! registry* (fn [reg]
                           (-> reg
                               (assoc-in [:jobs job-id :status] :killed)
                               (assoc-in [:jobs job-id :ended-at] (Instant/now)))))
        (logger/info logger-tag "Killed background job" {:id job-id})
        true)
      (do
        (logger/debug logger-tag "Job not running, cannot kill" {:id job-id :status (:status job)})
        false))
    (do
      (logger/debug logger-tag "Job not found" {:id job-id})
      false)))

(defn cleanup-all!
  "Kill all running background jobs. Idempotent, safe to call multiple times."
  []
  (doseq [{:keys [id status]} (list-jobs)]
    (when (= :running status)
      (kill-job! id))))

(defn elapsed-str
  "Human-readable elapsed time for a job."
  ^String [job]
  (let [start ^Instant (:started-at job)
        end (or ^Instant (:ended-at job) (Instant/now))
        secs (.between ChronoUnit/SECONDS start end)]
    (cond
      (< secs 60)   (str secs "s")
      (< secs 3600) (format "%dm%ds" (quot secs 60) (mod secs 60))
      :else          (format "%dh%dm" (quot secs 3600) (mod (quot secs 60) 60)))))

;; ---------------------------------------------------------------------------
;; JVM shutdown hook
;; ---------------------------------------------------------------------------

(defonce ^:private _shutdown-hook
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread. ^Runnable cleanup-all!)))
