(ns eca.features.tools.todo-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [matcher-combinators.test :refer [match?]]
   [eca.features.tools :as f.tools]
   [eca.features.tools.todo :as todo]))

(set! *warn-on-reflection* true)

;; --- Chat Isolation Tests ---

(deftest chat-isolation-test
  (testing "each chat has its own TODO"
    (let [db* (atom {})
          handler (get-in todo/definitions ["todo" :handler])]
      (handler {"op" "add" "task" {"subject" "Chat 1 Task" "description" "Desc"}} {:db* db* :chat-id "chat-1"})
      (handler {"op" "add" "task" {"subject" "Chat 2 Task" "description" "Desc"}} {:db* db* :chat-id "chat-2"})
      (is (= ["Chat 1 Task"] (map :subject (:tasks (todo/get-todo @db* "chat-1")))))
      (is (= ["Chat 2 Task"] (map :subject (:tasks (todo/get-todo @db* "chat-2")))))))

  (testing "empty chat returns empty TODO"
    (is (= {:next-id 1 :active-summary nil :tasks []}
           (todo/get-todo {} "nonexistent-chat")))))

;; --- State Access Tests ---

(deftest get-todo-test
  (testing "returns empty todo for missing chat state"
    (is (= {:next-id 1 :active-summary nil :tasks []}
           (todo/get-todo {} "missing-chat"))))

  (testing "returns stored todo state as-is"
    (let [state {:next-id 2
                 :active-summary "Doing stuff"
                 :tasks [{:id 1 :subject "T1" :description "D1" :status :pending :priority :medium :blocked-by #{}}]}
          db {:chats {"c1" {:todo state}}}]
      (is (= state (todo/get-todo db "c1"))))))

;; --- Read Operation Tests ---

(deftest op-read-test
  (let [handler (get-in todo/definitions ["todo" :handler])]
    (testing "reads empty state"
      (let [result (handler {"op" "read"} {:db {} :chat-id "c1"})]
        (is (not (:error result)))
        (is (match? {:details {:type :todo :tasks []}} result))))

    (testing "includes structured details"
      (let [db {:chats {"c1" {:todo {:active-summary "My active task"
                                     :tasks [{:id 1
                                              :subject "Task 1"
                                              :description "Desc 1"
                                              :status :in-progress
                                              :priority :high
                                              :blocked-by #{}}]}}}}
            result (handler {"op" "read"} {:db db :chat-id "c1"})]
        (is (match? {:details {:type :todo
                               :active-summary "My active task"
                               :in-progress-task-ids [1]
                               :tasks [{:id 1
                                        :subject "Task 1"
                                        :description "Desc 1"
                                        :status "in-progress"
                                        :priority "high"
                                        :is-blocked false}]
                               :summary {:done 0
                                         :in-progress 1
                                         :pending 0
                                         :total 1}}}
                    result))))

    (testing "read returns full task list text for llm"
      (let [db {:chats {"c1" {:todo {:active-summary "My active task"
                                     :tasks [{:id 1
                                              :subject "Task 1"
                                              :description "Desc 1"
                                              :status :in-progress
                                              :priority :high
                                              :blocked-by #{}}
                                             {:id 2
                                              :subject "Task 2"
                                              :description "Desc 2"
                                              :status :pending
                                              :priority :medium
                                              :blocked-by #{1}}]}}}}
            result (handler {"op" "read"} {:db db :chat-id "c1"})
            text (get-in result [:contents 0 :text])]
        (is (string? text))
        (is (re-find #"Active Summary: My active task" text))
        (is (re-find #"Summary: 0 done, 1 in progress, 1 pending, 2 total" text))
        (is (re-find #"#1 \[in-progress\] \[high\] Task 1" text))
        (is (re-find #"description: Desc 1" text))
        (is (re-find #"#2 \[pending\] \[medium\] Task 2" text))
        (is (re-find #"blocked_by: 1" text))))))

;; --- Plan Operation Tests ---

(deftest op-plan-test
  (let [handler (get-in todo/definitions ["todo" :handler])]
    (testing "creates TODO with tasks"
      (let [db* (atom {})
            result (handler {"op" "plan"
                             "tasks" [{"subject" "Task 1" "description" "Desc 1"}
                                      {"subject" "Task 2" "description" "Desc 2" "blocked_by" [1]}]}
                            {:db* db* :chat-id "c1"})]
        (is (not (:error result)))
        (is (= 2 (count (get-in @db* [:chats "c1" :todo :tasks]))))
        (is (= ["Task 1" "Task 2"] (map :subject (get-in @db* [:chats "c1" :todo :tasks]))))
        (is (= :pending (get-in @db* [:chats "c1" :todo :tasks 0 :status])))
        (is (= :medium (get-in @db* [:chats "c1" :todo :tasks 0 :priority])))))

    (testing "replaces existing TODO completely"
      (let [db* (atom {:chats {"c1" {:todo {:active-summary "Old summary"
                                            :tasks [{:id 1 :subject "Old Task" :description "Old D" :status :in-progress}
                                                    {:id 2 :subject "Old Task 2" :description "Old D2" :status :pending}]
                                            :next-id 3}}}})]
        (handler {"op" "plan"
                  "tasks" [{"subject" "New Task" "description" "New D"}]}
                 {:db* db* :chat-id "c1"})
        (is (nil? (get-in @db* [:chats "c1" :todo :active-summary])))
        (is (not-any? #(= "Old Task" (:subject %))
                      (get-in @db* [:chats "c1" :todo :tasks])))
        (is (= 1 (count (get-in @db* [:chats "c1" :todo :tasks]))))
        (is (= "New Task" (get-in @db* [:chats "c1" :todo :tasks 0 :subject])))
        (is (= 2 (get-in @db* [:chats "c1" :todo :next-id])))))

    (testing "requires tasks"
      (let [db* (atom {})
            result (handler {"op" "plan"} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"requires 'tasks'"}]} result))))

    (testing "requires non-empty tasks array"
      (let [db* (atom {})
            result (handler {"op" "plan" "tasks" []} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"non-empty array"}]} result))))

    (testing "rejects status in plan task payload"
      (let [db* (atom {})
            result (handler {"op" "plan"
                             "tasks" [{"subject" "Task 1" "description" "D1" "status" "done"}]}
                            {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Cannot set status when creating tasks"}]} result))))))

;; --- Add Operation Tests ---

(deftest op-add-test
  (let [handler (get-in todo/definitions ["todo" :handler])]
    (testing "adds single task"
      (let [db* (atom {})]
        (handler {"op" "add" "task" {"subject" "Task 1" "description" "Desc 1"}} {:db* db* :chat-id "c1"})
        (is (= 1 (count (get-in @db* [:chats "c1" :todo :tasks]))))
        (is (= "Task 1" (get-in @db* [:chats "c1" :todo :tasks 0 :subject])))
        (is (= 2 (get-in @db* [:chats "c1" :todo :next-id])))))

    (testing "adds batch of tasks"
      (let [db* (atom {})]
        (handler {"op" "add" "tasks" [{"subject" "T1" "description" "D1"} {"subject" "T2" "description" "D2"}]} {:db* db* :chat-id "c1"})
        (let [tasks (get-in @db* [:chats "c1" :todo :tasks])]
          (is (= 2 (count tasks)))
          (is (= [1 2] (map :id tasks))))))

    (testing "validates required subject"
      (let [db* (atom {})
            result (handler {"op" "add" "task" {"subject" "" "description" "D1"}} {:db* db* :chat-id "c1"})]
        (is (:error result))))

    (testing "rejects status in add payload"
      (let [db* (atom {})
            result (handler {"op" "add" "task" {"subject" "Task 1" "description" "D1" "status" "done"}} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Cannot set status when creating tasks"}]} result))))

    (testing "requires task or tasks"
      (let [db* (atom {})
            result (handler {"op" "add"} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"add requires"}]} result))))))

;; --- Update Operation Tests ---

(deftest op-update-test
  (let [handler (get-in todo/definitions ["todo" :handler])]
    (testing "updates task metadata"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :subject "Old" :description "Old D" :status :pending :priority :medium :blocked-by #{}}]}}}})]
        (handler {"op" "update" "id" 1 "task" {"subject" "New" "priority" "high"}} {:db* db* :chat-id "c1"})
        (let [task (first (get-in @db* [:chats "c1" :todo :tasks]))]
          (is (= "New" (:subject task)))
          (is (= :high (:priority task))))))

    (testing "rejects status changes via update"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :subject "T1" :description "D1" :status :pending :priority :medium :blocked-by #{}}]}}}})
            result (handler {"op" "update" "id" 1 "task" {"status" "done"}} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Cannot set status via update"}]} result))
        (is (= :pending (get-in @db* [:chats "c1" :todo :tasks 0 :status])))))

    (testing "rejects empty updates"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :subject "T1" :description "D1" :status :pending}]}}}})
            result (handler {"op" "update" "id" 1 "task" {}} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"No updatable fields"}]} result))))

    (testing "returns an error response for unknown id"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :subject "T1" :description "D1" :status :pending}]}}}})
            result (handler {"op" "update" "id" 999 "task" {"subject" "New"}} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"not found"}]} result))))))

;; --- Start Operation Tests ---

(deftest op-start-test
  (let [handler (get-in todo/definitions ["todo" :handler])]
    (testing "starts pending tasks by ids"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :subject "T1" :description "D1" :status :pending :priority :medium :blocked-by #{}}
                                                    {:id 2 :subject "T2" :description "D2" :status :pending :priority :medium :blocked-by #{}}]}}}})]
        (handler {"op" "start" "ids" [1 2] "active_summary" "Doing T1 and T2"} {:db* db* :chat-id "c1"})
        (is (= :in-progress (get-in @db* [:chats "c1" :todo :tasks 0 :status])))
        (is (= :in-progress (get-in @db* [:chats "c1" :todo :tasks 1 :status])))
        (is (= "Doing T1 and T2" (get-in @db* [:chats "c1" :todo :active-summary])))))

    (testing "does not demote other in-progress tasks"
      (let [db* (atom {:chats {"c1" {:todo {:active-summary "Doing T1" :tasks [{:id 1 :subject "T1" :description "D1" :status :in-progress :priority :medium :blocked-by #{}}
                                                    {:id 2 :subject "T2" :description "D2" :status :pending :priority :medium :blocked-by #{}}]}}}})]
        (handler {"op" "start" "ids" [2] "active_summary" "Doing T1 and T2"} {:db* db* :chat-id "c1"})
        (is (= :in-progress (get-in @db* [:chats "c1" :todo :tasks 0 :status])))
        (is (= :in-progress (get-in @db* [:chats "c1" :todo :tasks 1 :status])))
        (is (= "Doing T1 and T2" (get-in @db* [:chats "c1" :todo :active-summary])))))

    (testing "requires ids for start"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :subject "T1" :description "D1" :status :pending :priority :medium :blocked-by #{}}]}}}})
            result (handler {"op" "start" "active_summary" "Doing T1"} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"non-empty array"}]} result))))

    (testing "requires active_summary for start"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :subject "T1" :description "D1" :status :pending :priority :medium :blocked-by #{}}]}}}})
            result (handler {"op" "start" "ids" [1]} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"active_summary must be a non-blank string"}]} result))))

    (testing "rejects blank active_summary for start"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :subject "T1" :description "D1" :status :pending :priority :medium :blocked-by #{}}]}}}})
            result (handler {"op" "start" "ids" [1] "active_summary" "   "} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"active_summary must be a non-blank string"}]} result))))

    (testing "rejects starting a done task"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :subject "T1" :description "D1" :status :done :priority :medium :blocked-by #{}}]}}}})
            result (handler {"op" "start" "ids" [1] "active_summary" "Doing T1"} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"already done"}]} result))))

    (testing "rejects starting a blocked task"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :subject "T1" :description "D1" :status :pending :priority :medium :blocked-by #{}}
                                                    {:id 2 :subject "T2" :description "D2" :status :pending :priority :medium :blocked-by #{1}}]}}}})
            result (handler {"op" "start" "ids" [2] "active_summary" "Doing T2"} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"blocked by 1"}]} result))
        (is (= :pending (get-in @db* [:chats "c1" :todo :tasks 1 :status])))))

    (testing "rejects duplicate ids for start"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :subject "T1" :description "D1" :status :pending :priority :medium :blocked-by #{}}]}}}})
            result (handler {"op" "start" "ids" [1 1] "active_summary" "Doing T1"} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Duplicate IDs"}]} result))))))

;; --- Complete Operation Tests ---

(deftest op-complete-test
  (let [handler (get-in todo/definitions ["todo" :handler])]
    (testing "completes tasks by ids"
      (let [db* (atom {:chats {"c1" {:todo {:active-summary "My task" :tasks [{:id 1 :subject "T1" :description "D1" :status :in-progress :priority :medium :blocked-by #{}}
                                                    {:id 2 :subject "T2" :description "D2" :status :in-progress :priority :medium :blocked-by #{}}]}}}})]
        (handler {"op" "complete" "ids" [1 2]} {:db* db* :chat-id "c1"})
        (is (= :done (get-in @db* [:chats "c1" :todo :tasks 0 :status])))
        (is (= :done (get-in @db* [:chats "c1" :todo :tasks 1 :status])))
        (is (nil? (get-in @db* [:chats "c1" :todo :active-summary])))))

    (testing "leaves active summary if tasks are in-progress"
      (let [db* (atom {:chats {"c1" {:todo {:active-summary "My task" :tasks [{:id 1 :subject "T1" :description "D1" :status :in-progress :priority :medium :blocked-by #{}}
                                                    {:id 2 :subject "T2" :description "D2" :status :in-progress :priority :medium :blocked-by #{}}]}}}})]
        (handler {"op" "complete" "ids" [1]} {:db* db* :chat-id "c1"})
        (is (= :done (get-in @db* [:chats "c1" :todo :tasks 0 :status])))
        (is (= :in-progress (get-in @db* [:chats "c1" :todo :tasks 1 :status])))
        (is (= "My task" (get-in @db* [:chats "c1" :todo :active-summary])))))

    (testing "shows unblocked tasks"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :subject "T1" :description "D1" :status :in-progress :priority :medium :blocked-by #{}}
                                                    {:id 2 :subject "T2" :description "D2" :status :pending :priority :medium :blocked-by #{1}}]}}}})
            result (handler {"op" "complete" "ids" [1]} {:db* db* :chat-id "c1"})]
        (is (match? {:contents [{:type :text :text #"Unblocked"}]} result))))

    (testing "rejects blocked tasks for complete"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :subject "T1" :description "D1" :status :pending :priority :medium :blocked-by #{}}
                                                    {:id 2 :subject "T2" :description "D2" :status :pending :priority :medium :blocked-by #{1}}]}}}})
            result (handler {"op" "complete" "ids" [2]} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Cannot complete task 2: blocked by 1"}]} result))
        (is (= :pending (get-in @db* [:chats "c1" :todo :tasks 1 :status])))))

    (testing "requires ids for complete"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :subject "T1" :description "D1" :status :pending :priority :medium :blocked-by #{}}]}}}})
            result (handler {"op" "complete"} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"non-empty array"}]} result))))

    (testing "rejects duplicate ids for complete"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :subject "T1" :description "D1" :status :pending :priority :medium :blocked-by #{}}]}}}})
            result (handler {"op" "complete" "ids" [1 1]} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Duplicate IDs"}]} result))))))

;; --- Delete Operation Tests ---

(deftest op-delete-test
  (let [handler (get-in todo/definitions ["todo" :handler])]
    (testing "deletes tasks by ids and clears blocked_by references"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :subject "T1" :description "D1" :status :pending :priority :medium :blocked-by #{}}
                                                    {:id 2 :subject "T2" :description "D2" :status :pending :priority :medium :blocked-by #{1}}
                                                    {:id 3 :subject "T3" :description "D3" :status :pending :priority :medium :blocked-by #{1 2}}]}}}})]
        (handler {"op" "delete" "ids" [1 2]} {:db* db* :chat-id "c1"})
        (is (= 1 (count (get-in @db* [:chats "c1" :todo :tasks]))))
        (is (= 3 (get-in @db* [:chats "c1" :todo :tasks 0 :id])))
        (is (empty? (get-in @db* [:chats "c1" :todo :tasks 0 :blocked-by])))))

    (testing "deletes tasks clears active-summary when no in-progress remaining"
      (let [db* (atom {:chats {"c1" {:todo {:active-summary "My task" :tasks [{:id 1 :subject "T1" :description "D1" :status :in-progress :priority :medium :blocked-by #{}}
                                                    {:id 2 :subject "T2" :description "D2" :status :pending :priority :medium :blocked-by #{1}}]}}}})]
        (handler {"op" "delete" "ids" [1]} {:db* db* :chat-id "c1"})
        (is (nil? (get-in @db* [:chats "c1" :todo :active-summary])))))

    (testing "delete leaves active-summary when in-progress task remains"
      (let [db* (atom {:chats {"c1" {:todo {:active-summary "My task" :tasks [{:id 1 :subject "T1" :description "D1" :status :in-progress :priority :medium :blocked-by #{}}
                                                    {:id 2 :subject "T2" :description "D2" :status :in-progress :priority :medium :blocked-by #{}}]}}}})]
        (handler {"op" "delete" "ids" [1]} {:db* db* :chat-id "c1"})
        (is (= "My task" (get-in @db* [:chats "c1" :todo :active-summary])))))

    (testing "requires ids for delete"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :subject "T1" :description "D1" :status :pending :priority :medium :blocked-by #{}}]}}}})
            result (handler {"op" "delete"} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"non-empty array"}]} result))))

    (testing "rejects duplicate ids for delete"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :subject "T1" :description "D1" :status :pending :priority :medium :blocked-by #{}}]}}}})
            result (handler {"op" "delete" "ids" [1 1]} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Duplicate IDs"}]} result))))))

;; --- Clear Operation Tests ---

(deftest op-clear-test
  (let [handler (get-in todo/definitions ["todo" :handler])]
    (testing "resets to empty state"
      (let [db* (atom {:chats {"c1" {:todo {:active-summary "Summary" :tasks [{:id 1}] :next-id 2}}}})]
        (handler {"op" "clear"} {:db* db* :chat-id "c1"})
        (is (empty? (get-in @db* [:chats "c1" :todo :tasks])))
        (is (= 1 (get-in @db* [:chats "c1" :todo :next-id])))
        (is (nil? (get-in @db* [:chats "c1" :todo :active-summary])))))))

;; --- Priority Validation Tests ---

(deftest priority-validation-test
  (let [handler (get-in todo/definitions ["todo" :handler])]
    (testing "rejects invalid priority in add"
      (let [db* (atom {})
            result (handler {"op" "add" "task" {"subject" "T1" "description" "D1" "priority" "urgent"}} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Invalid priority"}]} result))))

    (testing "rejects invalid priority in update"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :subject "T1" :description "D1" :status :pending :priority :medium :blocked-by #{}}]}}}})
            result (handler {"op" "update" "id" 1 "task" {"priority" "urgent"}} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Invalid priority"}]} result))
        (is (= :medium (get-in @db* [:chats "c1" :todo :tasks 0 :priority])))))

    (testing "rejects uppercase priority values"
      (let [db* (atom {})
            result (handler {"op" "add" "task" {"subject" "T1" "description" "D1" "priority" "HIGH"}} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Invalid priority"}]} result))
        (is (empty? (:tasks (todo/get-todo @db* "c1"))))))))

;; --- Blocked By Validation Tests ---

(deftest blocked-by-validation-test
  (let [handler (get-in todo/definitions ["todo" :handler])]
    (testing "rejects non-array blocked_by"
      (let [db* (atom {:chats {"c1" {:todo {:tasks []}}}})
            result (handler {"op" "add" "task" {"subject" "T1" "description" "D1" "blocked_by" "not an array"}} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"blocked_by must be an array"}]} result))))

    (testing "rejects blocked_by with non-integer ids"
      (let [db* (atom {})
            result (handler {"op" "add" "task" {"subject" "T1" "description" "D1" "blocked_by" ["nope"]}} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"blocked_by must contain integer task IDs"}]} result))
        (is (empty? (:tasks (todo/get-todo @db* "c1"))))))

    (testing "rejects self-reference in update"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :subject "T1" :description "D1" :status :pending :priority :medium :blocked-by #{}}]}}}})
            result (handler {"op" "update" "id" 1 "task" {"blocked_by" [1]}} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"cannot block itself"}]} result))))))

;; --- Dependency Cycle Detection Tests ---

(deftest dependency-cycle-detection-test
  (let [handler (get-in todo/definitions ["todo" :handler])]
    (testing "detects 2-node cycle via update"
      (let [db* (atom {:chats {"c1" {:todo {:next-id 3
                                            :tasks [{:id 1 :subject "T1" :description "D1" :status :pending :priority :medium :blocked-by #{}}
                                                    {:id 2 :subject "T2" :description "D2" :status :pending :priority :medium :blocked-by #{1}}]}}}})
            result (handler {"op" "update" "id" 1 "task" {"blocked_by" [2]}} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Dependency cycle detected: Task 1 -> Task 2 -> Task 1"}]}
                    result))))

    (testing "detects cycle involving existing + new tasks"
      (let [db* (atom {:chats {"c1" {:todo {:next-id 2
                                            :tasks [{:id 1 :subject "T1" :description "D1" :status :pending :priority :medium :blocked-by #{}}]}}}})
            _ (handler {"op" "add" "task" {"subject" "T2" "description" "D2" "blocked_by" [1]}} {:db* db* :chat-id "c1"})
            result (handler {"op" "update" "id" 1 "task" {"blocked_by" [2]}} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Dependency cycle detected"}]} result))))

    (testing "detects cycle in disconnected component"
      (let [db* (atom {})
            result (handler {"op" "plan"
                             "tasks" [{"subject" "Task 1" "description" "D1"}
                                      {"subject" "Task 2" "description" "D2" "blocked_by" [3]}
                                      {"subject" "Task 3" "description" "D3" "blocked_by" [2]}]}
                            {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Dependency cycle detected"}]} result))))))

;; --- Forward Reference in Batch Tests ---

(deftest forward-reference-test
  (let [handler (get-in todo/definitions ["todo" :handler])]
    (testing "allows forward references in batch add"
      (let [db* (atom {})]
        (handler {"op" "plan"
                  "tasks" [{"subject" "Task 1" "description" "D1" "blocked_by" [2]}
                           {"subject" "Task 2" "description" "D2"}]}
                 {:db* db* :chat-id "c1"})
        (is (= 2 (count (get-in @db* [:chats "c1" :todo :tasks]))))
        (is (= #{2} (get-in @db* [:chats "c1" :todo :tasks 0 :blocked-by])))))

    (testing "allows reference to earlier task in batch"
      (let [db* (atom {})]
        (handler {"op" "plan"
                  "tasks" [{"subject" "Task 1" "description" "D1"}
                           {"subject" "Task 2" "description" "D2" "blocked_by" [1]}]}
                 {:db* db* :chat-id "c1"})
        (is (= #{1} (get-in @db* [:chats "c1" :todo :tasks 1 :blocked-by])))))

    (testing "rejects reference to non-existent task"
      (let [db* (atom {})
            result (handler {"op" "plan"
                             "tasks" [{"subject" "Task 1" "description" "D1" "blocked_by" [99]}]}
                            {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Invalid blocked_by references"}]} result))))))

(deftest todo-tool-call-details-test
  (testing "todo details are propagated to tool call details for clients"
    (let [result {:error false
                  :details {:type :todo
                            :tasks []
                            :summary {:done 0 :in-progress 0 :pending 0 :total 0}}
                  :contents [{:type :text :text "TODO created"}]}
          details (f.tools/tool-call-details-after-invocation
                   :todo
                   {"op" "plan"}
                   nil
                   result
                   nil)]
      (is (= (:details result) details)))))
