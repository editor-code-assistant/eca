(ns eca.features.background-tasks-test
  (:require
   [babashka.process :as p]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [eca.features.background-tasks :as bg]
   [eca.features.tools.background :as tools.bg]
   [matcher-combinators.test :refer [match?]])
  (:import
   [java.io ByteArrayInputStream]))

(set! *warn-on-reflection* true)

(defn reset-registry-fixture [f]
  (reset! bg/registry* {:next-id 1 :jobs {}})
  (f)
  (reset! bg/registry* {:next-id 1 :jobs {}}))

(use-fixtures :each reset-registry-fixture)

(defn ^:private mock-process
  "Create a deref-able mock process."
  []
  (let [p (promise)]
    (deliver p {:exit 0})
    p))

;; ---------------------------------------------------------------------------
;; Registry: register, get, list
;; ---------------------------------------------------------------------------

(deftest register-shell-job-test
  (testing "generates unique sequential IDs"
    (with-redefs [p/destroy-tree (constantly nil)]
      (let [j1 (bg/register-shell-job! {:label "cmd1" :process (mock-process) :working-directory "/tmp"})
            j2 (bg/register-shell-job! {:label "cmd2" :process (mock-process) :working-directory "/tmp"})]
        (is (= "job-1" (:id j1)))
        (is (= "job-2" (:id j2))))))

  (testing "returns job with correct structure"
    (with-redefs [p/destroy-tree (constantly nil)]
      (let [job (bg/register-shell-job! {:label "echo hi" :process (mock-process) :working-directory "/home"})]
        (is (match? {:id "job-3"
                     :type :shell
                     :label "echo hi"
                     :status :running
                     :exit-code nil
                     :working-directory "/home"}
                    job)))))

  (testing "enforces max-jobs limit"
    (with-redefs [p/destroy-tree (constantly nil)]
      (dotimes [_ 10]
        (bg/register-shell-job! {:label "fill" :process (mock-process) :working-directory "/tmp"}))
      (is (= 10 (bg/running-count)))
      (is (nil? (bg/register-shell-job! {:label "overflow" :process (mock-process) :working-directory "/tmp"}))))))

(deftest get-job-test
  (testing "returns job by ID"
    (with-redefs [p/destroy-tree (constantly nil)]
      (let [job (bg/register-shell-job! {:label "test" :process (mock-process) :working-directory "/tmp"})]
        (is (= (:id job) (:id (bg/get-job (:id job))))))))

  (testing "returns nil for unknown ID"
    (is (nil? (bg/get-job "job-999")))))

(deftest list-jobs-test
  (testing "returns all registered jobs"
    (with-redefs [p/destroy-tree (constantly nil)]
      (bg/register-shell-job! {:label "a" :process (mock-process) :working-directory "/tmp"})
      (bg/register-shell-job! {:label "b" :process (mock-process) :working-directory "/tmp"})
      (is (= 2 (count (bg/list-jobs))))
      (is (= #{"a" "b"} (set (map :label (bg/list-jobs))))))))

;; ---------------------------------------------------------------------------
;; Output: ring buffer, cursor-based reads
;; ---------------------------------------------------------------------------

(deftest read-output-basic-test
  (with-redefs [p/destroy-tree (constantly nil)]
    (let [job (bg/register-shell-job! {:label "test" :process (mock-process) :working-directory "/tmp"})
          output* (:output* job)]

      (testing "first read returns all buffered lines"
        (reset! output* {:lines ["line1" "line2" "line3"] :total-lines 3})
        (let [result (bg/read-output! (:id job))]
          (is (= ["line1" "line2" "line3"] (:lines result)))
          (is (= 0 (:dropped result)))))

      (testing "second read with no new lines returns empty"
        (let [result (bg/read-output! (:id job))]
          (is (= [] (:lines result)))
          (is (= 0 (:dropped result)))))

      (testing "incremental read returns only new lines"
        (reset! output* {:lines ["line1" "line2" "line3" "line4" "line5"] :total-lines 5})
        (let [result (bg/read-output! (:id job))]
          (is (= ["line4" "line5"] (:lines result)))
          (is (= 0 (:dropped result))))))))

(deftest read-output-ring-buffer-overflow-test
  (with-redefs [p/destroy-tree (constantly nil)]
    (let [job (bg/register-shell-job! {:label "test" :process (mock-process) :working-directory "/tmp"})
          output* (:output* job)]

      (testing "reports dropped lines when buffer overflows before read"
        ;; Simulate: 200 total lines produced, only last 100 in buffer.
        ;; Cursor at 0 (never read) — 100 lines were dropped.
        (reset! output* {:lines (vec (map str (range 100 200))) :total-lines 200})
        (reset! (:read-cursor* job) 0)
        (let [result (bg/read-output! (:id job))]
          (is (= 100 (count (:lines result))))
          (is (= "100" (first (:lines result))))
          (is (= 100 (:dropped result)))))

      (testing "handles cursor within buffer range correctly"
        ;; Cursor at 150 (read up to line 150). Buffer has lines 100-199.
        ;; Should return lines 150-199 (50 lines), 0 dropped.
        (reset! output* {:lines (vec (map str (range 100 200))) :total-lines 200})
        (reset! (:read-cursor* job) 150)
        (let [result (bg/read-output! (:id job))]
          (is (= 50 (count (:lines result))))
          (is (= "150" (first (:lines result))))
          (is (= 0 (:dropped result))))))))

(deftest read-output-nonexistent-test
  (testing "returns nil for non-existent job"
    (is (nil? (bg/read-output! "job-nonexistent")))))

;; ---------------------------------------------------------------------------
;; Output capture with InputStreams
;; ---------------------------------------------------------------------------

(deftest start-output-capture-test
  (with-redefs [p/destroy-tree (constantly nil)]
    (let [job (bg/register-shell-job! {:label "test" :process (mock-process) :working-directory "/tmp"})
          out-data "hello\nworld\n"
          err-data "warning\n"
          out-stream (ByteArrayInputStream. (.getBytes out-data "UTF-8"))
          err-stream (ByteArrayInputStream. (.getBytes err-data "UTF-8"))
          threads (bg/start-output-capture! job out-stream err-stream)]

      (testing "capture threads read lines into output buffer"
        ;; Wait for threads to finish reading
        (doseq [^Thread t threads] (.join t 2000))
        (let [result (bg/read-output! (:id job))]
          (is (= 3 (count (:lines result))))
          (is (= #{"hello" "world" "warning"} (set (:lines result)))))))))

;; ---------------------------------------------------------------------------
;; Kill and cleanup
;; ---------------------------------------------------------------------------

(deftest kill-job-test
  (with-redefs [p/destroy-tree (constantly nil)]
    (testing "kills a running job"
      (let [job (bg/register-shell-job! {:label "server" :process (mock-process) :working-directory "/tmp"})]
        (is (true? (bg/kill-job! (:id job))))
        (is (= :killed (:status (bg/get-job (:id job)))))))

    (testing "returns false for already-killed job"
      (let [job (bg/register-shell-job! {:label "server2" :process (mock-process) :working-directory "/tmp"})]
        (bg/kill-job! (:id job))
        (is (false? (bg/kill-job! (:id job))))))

    (testing "returns false for non-existent job"
      (is (false? (bg/kill-job! "job-nonexistent"))))))

(deftest cleanup-all-test
  (with-redefs [p/destroy-tree (constantly nil)]
    (testing "kills all running jobs"
      (bg/register-shell-job! {:label "a" :process (mock-process) :working-directory "/tmp"})
      (bg/register-shell-job! {:label "b" :process (mock-process) :working-directory "/tmp"})
      (bg/cleanup-all!)
      (is (= 0 (bg/running-count)))
      (is (every? #(= :killed (:status %)) (bg/list-jobs))))

    (testing "is idempotent"
      (bg/cleanup-all!)
      (is (= 0 (bg/running-count))))))

;; ---------------------------------------------------------------------------
;; Monitor process exit: status updates
;; ---------------------------------------------------------------------------

(deftest monitor-process-exit-test
  (with-redefs [p/destroy-tree (constantly nil)]
    (testing "updates status to :completed on zero exit"
      (let [exit-promise (promise)
            job (bg/register-shell-job! {:label "test" :process exit-promise :working-directory "/tmp"})
            ^Thread t (bg/monitor-process-exit! (:id job) exit-promise)]
        (deliver exit-promise {:exit 0})
        (.join t 2000)
        (is (= :completed (:status (bg/get-job (:id job)))))
        (is (= 0 (:exit-code (bg/get-job (:id job)))))))

    (testing "updates status to :failed on non-zero exit"
      (let [exit-promise (promise)
            job (bg/register-shell-job! {:label "test" :process exit-promise :working-directory "/tmp"})
            ^Thread t (bg/monitor-process-exit! (:id job) exit-promise)]
        (deliver exit-promise {:exit 1})
        (.join t 2000)
        (is (= :failed (:status (bg/get-job (:id job)))))
        (is (= 1 (:exit-code (bg/get-job (:id job)))))))

    (testing "does not overwrite :killed status"
      (let [exit-promise (promise)
            job (bg/register-shell-job! {:label "test" :process exit-promise :working-directory "/tmp"})
            ^Thread t (bg/monitor-process-exit! (:id job) exit-promise)]
        (bg/kill-job! (:id job))
        (deliver exit-promise {:exit 137})
        (.join t 2000)
        (is (= :killed (:status (bg/get-job (:id job)))))))))

;; ---------------------------------------------------------------------------
;; Elapsed string
;; ---------------------------------------------------------------------------

(deftest elapsed-str-test
  (testing "formats seconds"
    (let [now (java.time.Instant/now)
          job {:started-at (.minusSeconds now 45) :ended-at now}]
      (is (= "45s" (bg/elapsed-str job)))))

  (testing "formats minutes and seconds"
    (let [now (java.time.Instant/now)
          job {:started-at (.minusSeconds now 125) :ended-at now}]
      (is (= "2m5s" (bg/elapsed-str job)))))

  (testing "formats hours and minutes"
    (let [now (java.time.Instant/now)
          job {:started-at (.minusSeconds now 3725) :ended-at now}]
      (is (= "1h2m" (bg/elapsed-str job))))))

;; ---------------------------------------------------------------------------
;; bg_job tool handler
;; ---------------------------------------------------------------------------

(deftest bg-job-tool-list-test
  (with-redefs [p/destroy-tree (constantly nil)]
    (let [handler (get-in tools.bg/definitions ["bg_job" :handler])]

      (testing "list with no jobs"
        (is (match? {:error false
                     :contents [{:type :text :text "No background jobs."}]}
                    (handler {"action" "list"} {}))))

      (testing "list shows registered jobs"
        (bg/register-shell-job! {:label "npm run dev" :process (mock-process) :working-directory "/tmp"})
        (let [result (handler {"action" "list"} {})]
          (is (match? {:error false
                       :contents [{:type :text :text #"job-\d+.*npm run dev"}]}
                      result)))))))

(deftest bg-job-tool-read-output-test
  (with-redefs [p/destroy-tree (constantly nil)]
    (let [handler (get-in tools.bg/definitions ["bg_job" :handler])]

      (testing "read_output requires job_id"
        (is (match? {:error true
                     :contents [{:type :text :text #"job_id is required"}]}
                    (handler {"action" "read_output"} {}))))

      (testing "read_output for non-existent job"
        (is (match? {:error true
                     :contents [{:type :text :text #"not found"}]}
                    (handler {"action" "read_output" "job_id" "job-999"} {}))))

      (testing "read_output for existing job"
        (let [job (bg/register-shell-job! {:label "test" :process (mock-process) :working-directory "/tmp"})]
          (reset! (:output* job) {:lines ["hello" "world"] :total-lines 2})
          (is (match? {:error false
                       :contents [{:type :text :text #"hello\nworld"}]}
                      (handler {"action" "read_output" "job_id" (:id job)} {}))))))))

(deftest bg-job-tool-kill-test
  (with-redefs [p/destroy-tree (constantly nil)]
    (let [handler (get-in tools.bg/definitions ["bg_job" :handler])]

      (testing "kill requires job_id"
        (is (match? {:error true
                     :contents [{:type :text :text #"job_id is required"}]}
                    (handler {"action" "kill"} {}))))

      (testing "kill non-existent job"
        (is (match? {:error true
                     :contents [{:type :text :text #"not found"}]}
                    (handler {"action" "kill" "job_id" "job-999"} {}))))

      (testing "kill running job"
        (let [job (bg/register-shell-job! {:label "server" :process (mock-process) :working-directory "/tmp"})]
          (is (match? {:error false
                       :contents [{:type :text :text #"killed"}]}
                      (handler {"action" "kill" "job_id" (:id job)} {})))))

      (testing "kill already-stopped job"
        (let [job (bg/register-shell-job! {:label "server2" :process (mock-process) :working-directory "/tmp"})]
          (bg/kill-job! (:id job))
          (is (match? {:error true
                       :contents [{:type :text :text #"not running"}]}
                      (handler {"action" "kill" "job_id" (:id job)} {}))))))))

(deftest bg-job-tool-unknown-action-test
  (let [handler (get-in tools.bg/definitions ["bg_job" :handler])]
    (testing "unknown action returns error"
      (is (match? {:error true
                   :contents [{:type :text :text #"Unknown action"}]}
                  (handler {"action" "invalid"} {}))))))
