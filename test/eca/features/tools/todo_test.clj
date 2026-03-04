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
      (handler {"op" "add" "task" {"content" "Chat 1 Task"}} {:db* db* :chat-id "chat-1"})
      (handler {"op" "add" "task" {"content" "Chat 2 Task"}} {:db* db* :chat-id "chat-2"})
      (is (= ["Chat 1 Task"] (map :content (:tasks (todo/get-todo @db* "chat-1")))))
      (is (= ["Chat 2 Task"] (map :content (:tasks (todo/get-todo @db* "chat-2")))))))

  (testing "empty chat returns empty TODO"
    (is (= {:goal "" :next-id 1 :tasks []}
           (todo/get-todo {} "nonexistent-chat")))))

;; --- State Access Tests ---

(deftest get-todo-test
  (testing "returns empty todo for missing chat state"
    (is (= {:goal "" :next-id 1 :tasks []}
           (todo/get-todo {} "missing-chat"))))

  (testing "returns stored todo state as-is"
    (let [state {:goal "G"
                 :next-id 2
                 :tasks [{:id 1 :content "T1" :status :pending :priority :medium :blocked-by #{}}]}
          db {:chats {"c1" {:todo state}}}]
      (is (= state (todo/get-todo db "c1"))))))

;; --- Read Operation Tests ---

(deftest op-read-test
  (let [handler (get-in todo/definitions ["todo" :handler])]
    (testing "reads empty state"
      (let [result (handler {"op" "read"} {:db {} :chat-id "c1"})]
        (is (not (:error result)))
        (is (match? {:details {:type :todo :goal "" :tasks []}} result))))

    (testing "includes structured details"
      (let [db {:chats {"c1" {:todo {:goal "Test Goal"
                                     :tasks [{:id 1
                                              :content "Task 1"
                                              :status :in-progress
                                              :priority :high
                                              :done-when "criteria"
                                              :blocked-by #{}}]}}}}
            result (handler {"op" "read"} {:db db :chat-id "c1"})]
        (is (match? {:details {:type :todo
                               :goal "Test Goal"
                               :inProgressTaskIds [1]
                               :tasks [{:id 1
                                        :content "Task 1"
                                        :status "in-progress"
                                        :priority "high"
                                        :isBlocked false
                                        :doneWhen "criteria"}]
                               :summary {:done 0
                                         :inProgress 1
                                         :pending 0
                                         :total 1}}}
                    result))))

    (testing "read returns full task list text for llm"
      (let [db {:chats {"c1" {:todo {:goal "Test Goal"
                                     :tasks [{:id 1
                                              :content "Task 1"
                                              :status :in-progress
                                              :priority :high
                                              :done-when "criteria"
                                              :blocked-by #{}}
                                             {:id 2
                                              :content "Task 2"
                                              :status :pending
                                              :priority :medium
                                              :blocked-by #{1}}]}}}}
            result (handler {"op" "read"} {:db db :chat-id "c1"})
            text (get-in result [:contents 0 :text])]
        (is (string? text))
        (is (re-find #"Goal: Test Goal" text))
        (is (re-find #"Summary: 0 done, 1 in progress, 1 pending, 2 total" text))
        (is (re-find #"#1 \[in-progress\] \[high\] Task 1" text))
        (is (re-find #"done_when: criteria" text))
        (is (re-find #"#2 \[pending\] \[medium\] Task 2" text))
        (is (re-find #"blocked_by: 1" text))))))

;; --- Plan Operation Tests ---

(deftest op-plan-test
  (let [handler (get-in todo/definitions ["todo" :handler])]
    (testing "creates TODO with goal and tasks"
      (let [db* (atom {})
            result (handler {"op" "plan"
                             "goal" "My Goal"
                             "tasks" [{"content" "Task 1"}
                                      {"content" "Task 2" "blocked_by" [1]}]}
                            {:db* db* :chat-id "c1"})]
        (is (not (:error result)))
        (is (= "My Goal" (get-in @db* [:chats "c1" :todo :goal])))
        (is (= 2 (count (get-in @db* [:chats "c1" :todo :tasks]))))
        (is (= ["Task 1" "Task 2"] (map :content (get-in @db* [:chats "c1" :todo :tasks]))))
        (is (= :pending (get-in @db* [:chats "c1" :todo :tasks 0 :status])))
        (is (= :medium (get-in @db* [:chats "c1" :todo :tasks 0 :priority])))))

    (testing "replaces existing TODO completely"
      (let [db* (atom {:chats {"c1" {:todo {:goal "Old Goal"
                                            :tasks [{:id 1 :content "Old Task" :status :in-progress}
                                                    {:id 2 :content "Old Task 2" :status :pending}]
                                            :next-id 3}}}})]
        (handler {"op" "plan"
                  "goal" "New Goal"
                  "tasks" [{"content" "New Task"}]}
                 {:db* db* :chat-id "c1"})
        (is (= "New Goal" (get-in @db* [:chats "c1" :todo :goal])))
        (is (not-any? #(= "Old Task" (:content %))
                      (get-in @db* [:chats "c1" :todo :tasks])))
        (is (= 1 (count (get-in @db* [:chats "c1" :todo :tasks]))))
        (is (= "New Task" (get-in @db* [:chats "c1" :todo :tasks 0 :content])))
        (is (= 2 (get-in @db* [:chats "c1" :todo :next-id])))))

    (testing "requires goal"
      (let [db* (atom {})
            result (handler {"op" "plan" "tasks" [{"content" "Task 1"}]} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"goal must be a non-blank string"}]} result))))

    (testing "requires non-blank goal"
      (let [db* (atom {})
            result (handler {"op" "plan" "goal" "   " "tasks" [{"content" "Task 1"}]} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"goal must be a non-blank string"}]} result))))

    (testing "requires tasks"
      (let [db* (atom {})
            result (handler {"op" "plan" "goal" "My Goal"} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"requires 'tasks'"}]} result))))

    (testing "requires non-empty tasks array"
      (let [db* (atom {})
            result (handler {"op" "plan" "goal" "My Goal" "tasks" []} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"non-empty array"}]} result))))

    (testing "rejects status in plan task payload"
      (let [db* (atom {})
            result (handler {"op" "plan"
                             "goal" "My Goal"
                             "tasks" [{"content" "Task 1" "status" "done"}]}
                            {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Cannot set status when creating tasks"}]} result))))))

;; --- Add Operation Tests ---

(deftest op-add-test
  (let [handler (get-in todo/definitions ["todo" :handler])]
    (testing "adds single task"
      (let [db* (atom {})]
        (handler {"op" "add" "task" {"content" "Task 1"}} {:db* db* :chat-id "c1"})
        (is (= 1 (count (get-in @db* [:chats "c1" :todo :tasks]))))
        (is (= "Task 1" (get-in @db* [:chats "c1" :todo :tasks 0 :content])))
        (is (= 2 (get-in @db* [:chats "c1" :todo :next-id])))))

    (testing "adds batch of tasks"
      (let [db* (atom {})]
        (handler {"op" "add" "tasks" [{"content" "T1"} {"content" "T2"}]} {:db* db* :chat-id "c1"})
        (let [tasks (get-in @db* [:chats "c1" :todo :tasks])]
          (is (= 2 (count tasks)))
          (is (= [1 2] (map :id tasks))))))

    (testing "validates required content"
      (let [db* (atom {})
            result (handler {"op" "add" "task" {"content" ""}} {:db* db* :chat-id "c1"})]
        (is (:error result))))

    (testing "rejects status in add payload"
      (let [db* (atom {})
            result (handler {"op" "add" "task" {"content" "Task 1" "status" "done"}} {:db* db* :chat-id "c1"})]
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
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :content "Old" :status :pending :priority :medium :blocked-by #{}}]}}}})]
        (handler {"op" "update" "id" 1 "task" {"content" "New" "priority" "high"}} {:db* db* :chat-id "c1"})
        (let [task (first (get-in @db* [:chats "c1" :todo :tasks]))]
          (is (= "New" (:content task)))
          (is (= :high (:priority task))))))

    (testing "rejects status changes via update"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :content "T1" :status :pending :priority :medium :blocked-by #{}}]}}}})
            result (handler {"op" "update" "id" 1 "task" {"status" "done"}} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Cannot set status via update"}]} result))
        (is (= :pending (get-in @db* [:chats "c1" :todo :tasks 0 :status])))))

    (testing "rejects empty updates"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :content "T1" :status :pending}]}}}})
            result (handler {"op" "update" "id" 1 "task" {}} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"No updatable fields"}]} result))))

    (testing "returns an error response for unknown id"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :content "T1" :status :pending}]}}}})
            result (handler {"op" "update" "id" 999 "task" {"content" "New"}} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"not found"}]} result))))))

;; --- Start Operation Tests ---

(deftest op-start-test
  (let [handler (get-in todo/definitions ["todo" :handler])]
    (testing "starts pending tasks by ids"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :content "T1" :status :pending :priority :medium :blocked-by #{}}
                                                    {:id 2 :content "T2" :status :pending :priority :medium :blocked-by #{}}]}}}})]
        (handler {"op" "start" "ids" [1 2]} {:db* db* :chat-id "c1"})
        (is (= :in-progress (get-in @db* [:chats "c1" :todo :tasks 0 :status])))
        (is (= :in-progress (get-in @db* [:chats "c1" :todo :tasks 1 :status])))))

    (testing "does not demote other in-progress tasks"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :content "T1" :status :in-progress :priority :medium :blocked-by #{}}
                                                    {:id 2 :content "T2" :status :pending :priority :medium :blocked-by #{}}]}}}})]
        (handler {"op" "start" "ids" [2]} {:db* db* :chat-id "c1"})
        (is (= :in-progress (get-in @db* [:chats "c1" :todo :tasks 0 :status])))
        (is (= :in-progress (get-in @db* [:chats "c1" :todo :tasks 1 :status])))))

    (testing "requires ids for start"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :content "T1" :status :pending :priority :medium :blocked-by #{}}]}}}})
            result (handler {"op" "start"} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"non-empty array"}]} result))))

    (testing "rejects starting a done task"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :content "T1" :status :done :priority :medium :blocked-by #{}}]}}}})
            result (handler {"op" "start" "ids" [1]} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"already done"}]} result))))

    (testing "rejects starting a blocked task"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :content "T1" :status :pending :priority :medium :blocked-by #{}}
                                                    {:id 2 :content "T2" :status :pending :priority :medium :blocked-by #{1}}]}}}})
            result (handler {"op" "start" "ids" [2]} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"blocked by 1"}]} result))
        (is (= :pending (get-in @db* [:chats "c1" :todo :tasks 1 :status])))))

    (testing "rejects duplicate ids for start"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :content "T1" :status :pending :priority :medium :blocked-by #{}}]}}}})
            result (handler {"op" "start" "ids" [1 1]} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Duplicate IDs"}]} result))))))

;; --- Complete Operation Tests ---

(deftest op-complete-test
  (let [handler (get-in todo/definitions ["todo" :handler])]
    (testing "completes tasks by ids"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :content "T1" :status :in-progress :priority :medium :blocked-by #{}}
                                                    {:id 2 :content "T2" :status :in-progress :priority :medium :blocked-by #{}}]}}}})]
        (handler {"op" "complete" "ids" [1 2]} {:db* db* :chat-id "c1"})
        (is (= :done (get-in @db* [:chats "c1" :todo :tasks 0 :status])))
        (is (= :done (get-in @db* [:chats "c1" :todo :tasks 1 :status])))))

    (testing "shows unblocked tasks"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :content "T1" :status :in-progress :priority :medium :blocked-by #{}}
                                                    {:id 2 :content "T2" :status :pending :priority :medium :blocked-by #{1}}]}}}})
            result (handler {"op" "complete" "ids" [1]} {:db* db* :chat-id "c1"})]
        (is (match? {:contents [{:type :text :text #"Unblocked"}]} result))))

    (testing "rejects blocked tasks for complete"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :content "T1" :status :pending :priority :medium :blocked-by #{}}
                                                    {:id 2 :content "T2" :status :pending :priority :medium :blocked-by #{1}}]}}}})
            result (handler {"op" "complete" "ids" [2]} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Cannot complete task 2: blocked by 1"}]} result))
        (is (= :pending (get-in @db* [:chats "c1" :todo :tasks 1 :status])))))

    (testing "requires ids for complete"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :content "T1" :status :pending :priority :medium :blocked-by #{}}]}}}})
            result (handler {"op" "complete"} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"non-empty array"}]} result))))

    (testing "rejects duplicate ids for complete"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :content "T1" :status :pending :priority :medium :blocked-by #{}}]}}}})
            result (handler {"op" "complete" "ids" [1 1]} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Duplicate IDs"}]} result))))))

;; --- Delete Operation Tests ---

(deftest op-delete-test
  (let [handler (get-in todo/definitions ["todo" :handler])]
    (testing "deletes tasks by ids and clears blocked_by references"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :content "T1" :status :pending :priority :medium :blocked-by #{}}
                                                    {:id 2 :content "T2" :status :pending :priority :medium :blocked-by #{1}}
                                                    {:id 3 :content "T3" :status :pending :priority :medium :blocked-by #{1 2}}]}}}})]
        (handler {"op" "delete" "ids" [1 2]} {:db* db* :chat-id "c1"})
        (is (= 1 (count (get-in @db* [:chats "c1" :todo :tasks]))))
        (is (= 3 (get-in @db* [:chats "c1" :todo :tasks 0 :id])))
        (is (empty? (get-in @db* [:chats "c1" :todo :tasks 0 :blocked-by])))))

    (testing "requires ids for delete"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :content "T1" :status :pending :priority :medium :blocked-by #{}}]}}}})
            result (handler {"op" "delete"} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"non-empty array"}]} result))))

    (testing "rejects duplicate ids for delete"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :content "T1" :status :pending :priority :medium :blocked-by #{}}]}}}})
            result (handler {"op" "delete" "ids" [1 1]} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Duplicate IDs"}]} result))))))

;; --- Clear Operation Tests ---

(deftest op-clear-test
  (let [handler (get-in todo/definitions ["todo" :handler])]
    (testing "resets to empty state"
      (let [db* (atom {:chats {"c1" {:todo {:goal "G" :tasks [{:id 1}] :next-id 2}}}})]
        (handler {"op" "clear"} {:db* db* :chat-id "c1"})
        (is (empty? (get-in @db* [:chats "c1" :todo :tasks])))
        (is (= 1 (get-in @db* [:chats "c1" :todo :next-id])))
        (is (= "" (get-in @db* [:chats "c1" :todo :goal])))))))

;; --- Priority Validation Tests ---

(deftest priority-validation-test
  (let [handler (get-in todo/definitions ["todo" :handler])]
    (testing "rejects invalid priority in add"
      (let [db* (atom {})
            result (handler {"op" "add" "task" {"content" "T1" "priority" "urgent"}} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Invalid priority"}]} result))))

    (testing "rejects invalid priority in update"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :content "T1" :status :pending :priority :medium :blocked-by #{}}]}}}})
            result (handler {"op" "update" "id" 1 "task" {"priority" "urgent"}} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Invalid priority"}]} result))
        (is (= :medium (get-in @db* [:chats "c1" :todo :tasks 0 :priority])))))

    (testing "rejects uppercase priority values"
      (let [db* (atom {})
            result (handler {"op" "add" "task" {"content" "T1" "priority" "HIGH"}} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Invalid priority"}]} result))
        (is (empty? (:tasks (todo/get-todo @db* "c1"))))))))

;; --- Blocked By Validation Tests ---

(deftest blocked-by-validation-test
  (let [handler (get-in todo/definitions ["todo" :handler])]
    (testing "rejects non-array blocked_by"
      (let [db* (atom {:chats {"c1" {:todo {:tasks []}}}})
            result (handler {"op" "add" "task" {"content" "T1" "blocked_by" "not an array"}} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"blocked_by must be an array"}]} result))))

    (testing "rejects blocked_by with non-integer ids"
      (let [db* (atom {})
            result (handler {"op" "add" "task" {"content" "T1" "blocked_by" ["nope"]}} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"blocked_by must contain integer task IDs"}]} result))
        (is (empty? (:tasks (todo/get-todo @db* "c1"))))))

    (testing "rejects self-reference in update"
      (let [db* (atom {:chats {"c1" {:todo {:tasks [{:id 1 :content "T1" :status :pending :priority :medium :blocked-by #{}}]}}}})
            result (handler {"op" "update" "id" 1 "task" {"blocked_by" [1]}} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"cannot block itself"}]} result))))))

;; --- Dependency Cycle Detection Tests ---

(deftest dependency-cycle-detection-test
  (let [handler (get-in todo/definitions ["todo" :handler])]
    (testing "detects 2-node cycle via update"
      (let [db* (atom {:chats {"c1" {:todo {:next-id 3
                                            :tasks [{:id 1 :content "T1" :status :pending :priority :medium :blocked-by #{}}
                                                    {:id 2 :content "T2" :status :pending :priority :medium :blocked-by #{1}}]}}}})
            result (handler {"op" "update" "id" 1 "task" {"blocked_by" [2]}} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Dependency cycle detected: Task 1 -> Task 2 -> Task 1"}]}
                    result))))

    (testing "detects cycle involving existing + new tasks"
      (let [db* (atom {:chats {"c1" {:todo {:next-id 2
                                            :tasks [{:id 1 :content "T1" :status :pending :priority :medium :blocked-by #{}}]}}}})
            _ (handler {"op" "add" "task" {"content" "T2" "blocked_by" [1]}} {:db* db* :chat-id "c1"})
            result (handler {"op" "update" "id" 1 "task" {"blocked_by" [2]}} {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Dependency cycle detected"}]} result))))

    (testing "detects cycle in disconnected component"
      (let [db* (atom {})
            result (handler {"op" "plan"
                             "goal" "Test"
                             "tasks" [{"content" "Task 1"}
                                      {"content" "Task 2" "blocked_by" [3]}
                                      {"content" "Task 3" "blocked_by" [2]}]}
                            {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Dependency cycle detected"}]} result))))))

;; --- Forward Reference in Batch Tests ---

(deftest forward-reference-test
  (let [handler (get-in todo/definitions ["todo" :handler])]
    (testing "allows forward references in batch add"
      (let [db* (atom {})]
        (handler {"op" "plan"
                  "goal" "Test"
                  "tasks" [{"content" "Task 1" "blocked_by" [2]}
                           {"content" "Task 2"}]}
                 {:db* db* :chat-id "c1"})
        (is (= 2 (count (get-in @db* [:chats "c1" :todo :tasks]))))
        (is (= #{2} (get-in @db* [:chats "c1" :todo :tasks 0 :blocked-by])))))

    (testing "allows reference to earlier task in batch"
      (let [db* (atom {})]
        (handler {"op" "plan"
                  "goal" "Test"
                  "tasks" [{"content" "Task 1"}
                           {"content" "Task 2" "blocked_by" [1]}]}
                 {:db* db* :chat-id "c1"})
        (is (= #{1} (get-in @db* [:chats "c1" :todo :tasks 1 :blocked-by])))))

    (testing "rejects reference to non-existent task"
      (let [db* (atom {})
            result (handler {"op" "plan"
                             "goal" "Test"
                             "tasks" [{"content" "Task 1" "blocked_by" [99]}]}
                            {:db* db* :chat-id "c1"})]
        (is (:error result))
        (is (match? {:contents [{:type :text :text #"Invalid blocked_by references"}]} result))))))

(deftest todo-tool-call-details-test
  (testing "todo details are propagated to tool call details for clients"
    (let [result {:error false
                  :details {:type :todo
                            :goal "Goal"
                            :tasks []
                            :summary {:done 0 :inProgress 0 :pending 0 :total 0}}
                  :contents [{:type :text :text "TODO created"}]}
          details (f.tools/tool-call-details-after-invocation
                   :todo
                   {"op" "plan"}
                   nil
                   result
                   nil)]
      (is (= (:details result) details)))))
