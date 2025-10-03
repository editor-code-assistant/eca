(ns eca.features.chat-tool-call-state-test
  "A namespace for testing the tool call state transitions."
  (:require
   [clojure.string :as s]
   [clojure.test :refer [deftest is testing]]
   [eca.features.chat :as f.chat]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

;;; TODO: remove the :manual-approval's from event-data of the :tool-prepare event.  They are not needed. But may be needed in :tool-run

;;; Unit testing the actions

(deftest actions-test
  (testing "Test each state management action."
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id "test-chat"
          tool-call-id "call-42"
          tool-name "Shell tool"
          tool-arguments {:command "/bin/sh -c sleep 30"
                          :working_directory "/tmp"}
          tool-origin :native
          chat-ctx {:chat-id chat-id :request-id "req-1" :messenger (h/messenger)}
          resources {:a 1 :b 2}]
      (#'f.chat/execute-action! :init-tool-call-state db* chat-ctx tool-call-id {:name tool-name
                                                                                 :server "eca"
                                                                                 :arguments tool-arguments
                                                                                 :origin tool-origin})
      (is (= (#'f.chat/get-tool-call-state @db* chat-id tool-call-id)
             {:name tool-name
              :server "eca" 
              :arguments tool-arguments
              :origin tool-origin
              :decision-reason {:code :none
                                :text "No reason"}})
          "Expected tool-call-state to be initialized.")

      (#'f.chat/execute-action! :add-resources db* chat-ctx tool-call-id {:resources resources})
      (is (= (:resources (#'f.chat/get-tool-call-state @db* chat-id tool-call-id))
             resources))

      (#'f.chat/execute-action! :remove-resources db* chat-ctx tool-call-id {:resources (keys resources)})
      (is (= (:resources (#'f.chat/get-tool-call-state @db* chat-id tool-call-id))
             {}))
)))

;;; State machine intent tests

(deftest transition-tool-call-state-machine-completeness-test
  ;; Test that all intended state transitions are defined
  (testing "All intended state machine transitions are defined"
    (h/reset-components!)
    (let [state-machine (deref #'f.chat/tool-call-state-machine)]

      ;; Verify state machine has expected transitions
      (is (contains? state-machine [:initial :tool-prepare])
          "Expected state machine to contain [:initial :tool-prepare] transition")
      (is (contains? state-machine [:preparing :tool-prepare])
          "Expected state machine to contain [:preparing :tool-prepare] transition")
      (is (contains? state-machine [:preparing :tool-run])
          "Expected state machine to contain [:preparing :tool-run] transition")
      (is (contains? state-machine [:check-approval :approval-ask])
          "Expected state machine to contain [:check-approval :approval-ask] transition")
      (is (contains? state-machine [:check-approval :approval-allow])
          "Expected state machine to contain [:check-approval :approval-allow] transition")
      (is (contains? state-machine [:check-approval :approval-deny])
          "Expected state machine to contain [:check-approval :approval-deny] transition")
      (is (contains? state-machine [:waiting-approval :user-approve])
          "Expected state machine to contain [:waiting-approval :user-approve] transition")
      (is (contains? state-machine [:waiting-approval :user-reject])
          "Expected state machine to contain [:waiting-approval :user-reject] transition")
      (is (contains? state-machine [:rejected :send-reject])
          "Expected state machine to contain [:rejected :send-reject] transition")
      (is (contains? state-machine [:execution-approved :execution-start])
          "Expected state machine to contain [:execution-approved :execution-start] transition")
      (is (contains? state-machine [:executing :execution-end])
          "Expected state machine to contain [:executing :execution-end] transition")
      (is (contains? state-machine [:executing :resources-created])
          "Expected state machine to contain [:executing :resources-created] transition")
      (is (contains? state-machine [:executing :resources-destroyed])
          "Expected state machine to contain [:executing :resources-destroyed] transition")
      (is (contains? state-machine [:stopping :resources-destroyed])
          "Expected state machine to contain [:stopping :resources-destroyed] transition")
      (is (contains? state-machine [:stopping :stop-attempted])
          "Expected state machine to contain [:stopping :stop-attempted] transition")

      ;; Verify all stop transitions are defined
      (is (contains? state-machine [:initial :stop-requested])
          "Expected state machine to contain [:initial :stop-requested] transition")
      (is (contains? state-machine [:preparing :stop-requested])
          "Expected state machine to contain [:preparing :stop-requested] transition")
      (is (contains? state-machine [:check-approval :stop-requested])
          "Expected state machine to contain [:check-approval :stop-requested] transition")
      (is (contains? state-machine [:waiting-approval :stop-requested])
          "Expected state machine to contain [:waiting-approval :stop-requested] transition")
      (is (contains? state-machine [:execution-approved :stop-requested])
          "Expected state machine to contain [:execution-approved :stop-requested] transition")
      (is (contains? state-machine [:executing :stop-requested])
          "Expected state machine to contain [:executing :stop-requested] transition")

      ;; Note: :rejected, :completed and :stopped are terminal states, so no stop transitions.
      (is (not (contains? state-machine [:stopped :stop-requested]))
          "Expected :rejected state to not have stop transition defined, since it is a terminal state")
      (is (not (contains? state-machine [:rejected :stop-requested]))
          "Expected :rejected state to not have stop transition defined, since it is a terminal state")
      (is (not (contains? state-machine [:completed :stop-requested]))
          "Expected :completed state to not have stop transition defined, since it is a terminal state"))))

(deftest transition-tool-call-invalid-transitions-test
  (testing "Invalid state transitions should throw exceptions"
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id "test-chat"
          chat-ctx {:chat-id chat-id :request-id "req-1" :messenger (h/messenger)}]

      (#'f.chat/transition-tool-call! db* chat-ctx "tool-1" :tool-prepare
                                      {:name "test" :origin "test" :arguments-text "{}"})

      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid state transition"
           (#'f.chat/transition-tool-call! db* chat-ctx "tool-1" :user-approve))
          "Expected exception for invalid transition from :preparing to :user-approve")

      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid state transition"
           (#'f.chat/transition-tool-call! db* chat-ctx "tool-1" :no-such-event))
          "Expected exception for invalid transition from :preparing to :user-approve")

      (try
        (#'f.chat/transition-tool-call! db* chat-ctx "tool-1" :user-approve)
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :preparing (:current-status data))
                "Expected exception data to contain current status :preparing")
            (is (= :user-approve (:event data))
                "Expected exception data to contain event :user-approve")
            (is (= "tool-1" (:tool-call-id data))
                "Expected exception data to contain tool-call-id 'tool-1'")
            (is (seq (:valid-events data)) "Expected exception data to include valid events list")))))))

;;; State transition and flow semantics tests

(deftest transition-tool-call-initial-to-preparing-test
  (testing "First tool-prepare transition: :initial -> :preparing"
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id "test-chat"
          tool-call-id "tool-1"
          chat-ctx {:chat-id chat-id :request-id "req-1" :messenger (h/messenger)}
          event-data {:name "list_files"
                      :origin "filesystem"
                      :arguments-text "{\"path\": \"/tmp\"}"
                      :summary "List files in directory"}]

      (is (nil? (#'f.chat/get-tool-call-state @db* chat-id tool-call-id))
          "Expected no tool call state to exist initially")

      ;; Step 1: :initial -> :preparing
      (let [result (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-prepare event-data)]

        (is (match? {:status :preparing
                     :actions [:init-tool-call-state :send-toolCallPrepare]}
                    result)
            "Expected return value to show :preparing status and send toolCallPrepare action")

        (let [tool-state (#'f.chat/get-tool-call-state @db* chat-id tool-call-id)]
          (is (match? {:status :preparing}
                      tool-state)
              "Expected tool call state to be created and transitioned to :preparing")
          (is (nil? (:approved?* tool-state)) "approved?* promise should not exist during :tool-prepare, only after :tool-run"))

        (let [messages (h/messages)
              chat-messages (:chat-content-received messages)
              prepare-messages (filter #(= :toolCallPrepare (get-in % [:content :type])) chat-messages)]

          (is (= 1 (count prepare-messages)) "Expected exactly one toolCallPrepare notification to be sent")

          (is (match? {:chat-id chat-id
                       :role :assistant
                       :content (merge {:type :toolCallPrepare
                                        :id tool-call-id}
                                       event-data)}
                      (first prepare-messages))
              "Expected toolCallPrepare message to contain correct tool call details that were passed in"))))))

(deftest transition-tool-call-multiple-prepares-test
  (testing "Multiple tool-prepare transitions: :preparing -> :preparing"
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id "test-chat"
          tool-call-id "tool-1"
          chat-ctx {:chat-id chat-id :request-id "req-1" :messenger (h/messenger)}
          event-data-1 {:name "list_files"
                        :origin "filesystem"
                        :arguments-text "{\"path\": \"/tmp\"}"
                        :summary "List files in directory"}
          event-data-2 {:name "list_files"
                        :origin "filesystem"
                        :arguments-text "{\"path\": \"/tmp\", \"recursive\": true}"
                        :summary "List files in directory recursively"}]

      ;; Step 1: :initial -> :preparing
      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-prepare event-data-1)

      ;; Step 2: :preparing -> :preparing
      (let [result (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-prepare event-data-2)]

        (is (match? {:status :preparing
                     :actions [:send-toolCallPrepare]}
                    result)
            "Expected to stay in :preparing state and send toolCallPrepare action")

        (let [tool-state (#'f.chat/get-tool-call-state @db* chat-id tool-call-id)]
          (is (= :preparing (:status tool-state))
              "Expected tool call state to remain in :preparing status"))

        (let [messages (h/messages)
              chat-messages (:chat-content-received messages)
              prepare-messages (filter #(= :toolCallPrepare (get-in % [:content :type])) chat-messages)]

          (is (= 2 (count prepare-messages)) "Expected two toolCallPrepare notifications to be sent")

          (is (match? {:content {:arguments-text "{\"path\": \"/tmp\", \"recursive\": true}"}}
                      (second prepare-messages))
              "Expected second notification to have updated arguments"))))))

(deftest transition-tool-call-preparing-to-check-approval-test
  (testing "Tool-run transition: :preparing -> :check-approval"
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id "test-chat"
          tool-call-id "tool-1"
          chat-ctx {:chat-id chat-id :request-id "req-1" :messenger (h/messenger)}
          approved?* (promise)
          prepare-event-data {:name "list_files"
                              :origin "filesystem"
                              :arguments-text "{\"path\": \"/tmp\"}"
                              :summary "List files in directory"}
          run-event-args {:name "list_files"
                          :origin "filesystem"
                          :arguments {:path "/tmp"}
                          :manual-approval false
                          :details "List files in /tmp directory"
                          :summary "List files in directory"}
          run-event-data (assoc run-event-args :approved?* approved?*)]

      ;; Step 1: :initial -> :preparing
      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-prepare prepare-event-data)

      (let [initial-state (#'f.chat/get-tool-call-state @db* chat-id tool-call-id)]
        (is (= :preparing (:status initial-state))
            "Expected tool call to be in :preparing state after prepare transition")
        (is (nil? (:approved?* initial-state)) "Expected no promise to exist yet"))

      ;; Step 2: :preparing -> :check-approval
      (let [result (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-run run-event-data)]

        (is (match? {:status :check-approval
                     :actions [:init-arguments :init-approval-promise :send-toolCallRun]}
                    result)
            "Expected next state to be :check-approval with actions of :init-approval-promise and :send-toolCallRun")

        (let [tool-state (#'f.chat/get-tool-call-state @db* chat-id tool-call-id)]
          (is (match? {:status :check-approval
                       :approved?* #(instance? clojure.lang.IPending %)}
                      tool-state)
              "Expected tool call state to transition to :check-approval with a pending promise")
          ;; Verify it's the same promise we passed in
          (is (identical? approved?* (:approved?* tool-state))
              "Expected to store the exact same promise passed in event-data"))

        (let [messages (h/messages)
              chat-messages (:chat-content-received messages)
              run-messages (filter #(= :toolCallRun (get-in % [:content :type])) chat-messages)]

          (is (= 1 (count run-messages)) "Expected exactly one toolCallRun notification to be sent")

          (is (match? {:chat-id chat-id
                       :role :assistant
                       :content (merge {:type :toolCallRun
                                        :id tool-call-id}
                                       run-event-args)}
                      (first run-messages))
              "Expected toolCallRun message to contain correct tool call details"))))))

(deftest transition-tool-call-complete-manual-approval-flow-test
  (testing "Complete manual approval flow: :preparing -> :check-approval -> :waiting-approval -> :execution-approved"
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id "test-chat"
          tool-call-id "tool-1"
          chat-ctx {:chat-id chat-id :request-id "req-1" :messenger (h/messenger)}
          approved?* (promise)
          prepare-event-data {:name "dangerous_command"
                              :origin "shell"
                              :arguments-text "{\"command\": \"rm -rf /\"}"
                              :summary "Execute dangerous shell command"}
          run-event-data {:approved?* approved?*
                          :name "dangerous_command"
                          :origin "shell"
                          :arguments {:command "rm -rf /"}
                          :manual-approval true
                          :details "Execute dangerous shell command"
                          :summary "Execute dangerous shell command"}
          manual-approve-event-data {:state :running
                                     :text "Waiting for tool call approval"}]

      ;; Step 1: :initial -> :preparing
      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-prepare prepare-event-data)

      ;; Step 2: :preparing -> :check-approval
      (let [result (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-run run-event-data)]
        (is (match? {:status :check-approval
                     :actions [:init-arguments :init-approval-promise :send-toolCallRun]}
                    result)
            "Expected transition to :check-approval with init promise and send run actions")

        (let [tool-state (#'f.chat/get-tool-call-state @db* chat-id tool-call-id)]
          (is (= :check-approval (:status tool-state))
              "Expected tool call state to be in :check-approval status")
          (is (identical? approved?* (:approved?* tool-state))
              "Expected the exact promise passed in to be stored")))

      ;; Step 3: :check-approval -> :waiting-approval (manual approval needed)
      (let [result (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :approval-ask manual-approve-event-data)]
        (is (match? {:status :waiting-approval
                     :actions [:send-progress]}
                    result)
            "Expected transition to :waiting-approval with send progress action")

        (let [tool-state (#'f.chat/get-tool-call-state @db* chat-id tool-call-id)]
          (is (= :waiting-approval (:status tool-state))
              "Expected tool call state to be in :waiting-approval status")))

      ;; Step 4: :waiting-approval -> :execution-approved (user approves)
      (let [result (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :user-approve)]
        (is (match? {:status :execution-approved
                     :actions [:set-decision-reason :deliver-approval-true]}
                    result)
            "Expected transition to :execution-approved with deliver approval true action")

        (let [tool-state (#'f.chat/get-tool-call-state @db* chat-id tool-call-id)]
          (is (= :execution-approved (:status tool-state))
              "Expected tool call state to be in :execution-approved status")
          ;; Promise should now be delivered with true
          (is (= true (deref (:approved?* tool-state) 100 :timeout))
              "Expected promise to be delivered with true value"))))))

(deftest transition-tool-call-complete-auto-approval-flow-test
  (testing "Complete auto approval flow: :preparing -> :check-approval -> :execution-approved"
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id "test-chat"
          tool-call-id "tool-1"
          chat-ctx {:chat-id chat-id :request-id "req-1" :messenger (h/messenger)}
          approved?* (promise)
          prepare-event-data {:name "safe_command"
                              :origin "filesystem"
                              :arguments-text "{\"path\": \"/tmp\"}"
                              :summary "List safe directory"}
          run-event-data {:approved?* approved?*
                          :name "safe_command"
                          :origin "filesystem"
                          :arguments {:path "/tmp"}
                          :manual-approval false
                          :details "List safe directory"
                          :summary "List safe directory"}]

      ;; Step 1: :initial -> :preparing
      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-prepare prepare-event-data)

      ;; Step 2: :preparing -> :check-approval
      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-run run-event-data)

      ;; Step 3: :check-approval -> :execution-approved (auto approval)
      (let [result (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :approval-allow)]
        (is (match? {:status :execution-approved
                     :actions [:set-decision-reason :deliver-approval-true]}
                    result)
            "Expected transition to :execution-approved with deliver approval true action")

        (let [tool-state (#'f.chat/get-tool-call-state @db* chat-id tool-call-id)]
          (is (= :execution-approved (:status tool-state))
              "Expected tool call state to be in :execution-approved status")
          (is (= true (deref (:approved?* tool-state) 100 :timeout))
              "Expected promise to be delivered with true value"))))))

(deftest transition-tool-call-rejection-flow-test
  (testing "Rejection flow: :waiting-approval -> :rejected -> :rejected"
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id "test-chat"
          tool-call-id "tool-1"
          chat-ctx {:chat-id chat-id :request-id "req-1" :messenger (h/messenger)}
          approved?* (promise)]

      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-prepare
                                      {:name "test" :origin "test" :arguments-text "{}"})
      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-run
                                      {:approved?* approved?* :name "test" :origin "test" :arguments {} :manual-approval true})
      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :approval-ask
                                      {:state :running :text "Waiting"})

      (let [result (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :user-reject)]
        (is (match? {:status :rejected
                     :actions [:set-decision-reason :deliver-approval-false :log-rejection]}
                    result)
            "Expected transition to :rejected with deliver approval false and log rejection actions")

        (let [tool-state (#'f.chat/get-tool-call-state @db* chat-id tool-call-id)]
          (is (= :rejected (:status tool-state))
              "Expected tool call state to be in :rejected status")
          (is (= false (deref (:approved?* tool-state) 100 :timeout))
              "Expected promise to be delivered with false value")))

      (let [result (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :send-reject
                                                   {:origin "test" :name "test" :arguments {} :reason :user})]
        (is (match? {:status :rejected
                     :actions [:send-toolCallRejected]}
                    result)
            "Expected to stay in :rejected status and send toolCallRejected action")))))

(deftest transition-tool-call-execution-flow-test
  (testing "Complete execution flow: :execution-approved -> :executing -> :completed"
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id "test-chat"
          tool-call-id "tool-1"
          chat-ctx {:chat-id chat-id :request-id "req-1" :messenger (h/messenger)}
          approved?* (promise)]

      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-prepare
                                      {:name "list_files" :origin "filesystem" :arguments-text "{\"path\": \"/tmp\"}"})
      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-run
                                      {:approved?* approved?* :name "list_files" :origin "filesystem" :arguments {:path "/tmp"} :manual-approval false})
      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :approval-allow)

      (let [tool-state (#'f.chat/get-tool-call-state @db* chat-id tool-call-id)]
        (is (= :execution-approved (:status tool-state))
            "Expected tool call state to be in :execution-approved status")
        (is (= true (deref (:approved?* tool-state) 100 :timeout))
            "Expected promise to be delivered with true value"))

      ;; Step 1: :execution-approved -> :executing
      (testing ":execution-approved -> :executing transition"
        (let [result (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :execution-start
                                                     {:name "list_files" :origin "filesystem" :arguments {:path "/tmp"}})]
          (is (match? {:status :executing
                       :actions [:set-start-time :set-call-future :send-toolCallRunning :send-progress]}
                      result)
              "Expected transition to :executing status with no additional actions")

          (let [tool-state (#'f.chat/get-tool-call-state @db* chat-id tool-call-id)]
            (is (= :executing (:status tool-state))
                "Expected tool call state to be in :executing status"))))

      ;; Step 2: :executing -> :completed
      (testing ":executing -> :completed transition"
        (let [result-data {:outputs "file1.txt\nfile2.txt\nfile3.txt"
                           :error nil
                           :name "list_files"
                           :origin "filesystem"
                           :arguments {:path "/tmp"}}
              result (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :execution-end result-data)]

          (is (match? {:status :completed
                       :actions [:send-toolCalled :log-metrics :send-progress]}
                      result)
              "Expected transition to :completed with send toolCalled and record metrics actions")

          (let [tool-state (#'f.chat/get-tool-call-state @db* chat-id tool-call-id)]
            (is (= :completed (:status tool-state))
                "Expected tool call state to be in :completed status"))

          (let [messages (h/messages)
                chat-messages (:chat-content-received messages)
                completed-messages (filter #(= :toolCalled (get-in % [:content :type])) chat-messages)]

            (is (= 1 (count completed-messages)) "Expected exactly one toolCalled notification to be sent")

            (is (match? {:chat-id chat-id
                         :role :assistant
                         :content (merge {:type :toolCalled
                                          :id tool-call-id}
                                         result-data)}
                        (first completed-messages))
                "Expected toolCalled message to contain correct completion details")))))))

;;; Tests for stop-prompt functionality.

(deftest transition-tool-call-all-states-to-stop-test
  ;; Test stopping from all possible states
  (testing "Should be able to stop from any state"
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id "test-chat"
          chat-ctx {:chat-id chat-id :request-id "req-1" :messenger (h/messenger)}]

      ;; Test :initial -> :stopped
      (testing ":initial -> :stopped"
        (let [result (#'f.chat/transition-tool-call! db* chat-ctx "tool-initial" :stop-requested)]
          (is (match? {:status :stopped :actions []} result)
              "Expected transition from :initial to :stopped with no actions")))

      ;; Test :preparing -> :stopped (already covered in transition-tool-call-stop-transitions-test)
      ;; Test :check-approval -> :stopped
      (testing ":check-approval -> :stopped"
        (let [approved?* (promise)]
          (#'f.chat/transition-tool-call! db* chat-ctx "tool-check" :tool-prepare
                                          {:name "test" :origin "test" :arguments-text "{}"})
          (#'f.chat/transition-tool-call! db* chat-ctx "tool-check" :tool-run
                                          {:approved?* approved?* :name "test" :origin "test" :arguments {} :manual-approval false})

          (let [result (#'f.chat/transition-tool-call! db* chat-ctx "tool-check" :stop-requested)]
            (is (match? {:status :rejected
                         :actions [:set-decision-reason :deliver-approval-false]}
                        result)
                "Expected transition from :check-approval to :rejected with relevant actions"))))

      ;; Test :executing -> :stopping -> :stopped
      (testing ":executing -> :stopping -> :stopped"
        (let [approved?* (promise)]
          (#'f.chat/transition-tool-call! db* chat-ctx "tool-executing" :tool-prepare
                                          {:name "test" :origin "test" :arguments-text "{}"})
          (#'f.chat/transition-tool-call! db* chat-ctx "tool-executing" :tool-run
                                          {:approved?* approved?* :name "test" :origin "test" :arguments {} :manual-approval false})
          (#'f.chat/transition-tool-call! db* chat-ctx "tool-executing" :approval-allow)
          (#'f.chat/transition-tool-call! db* chat-ctx "tool-executing" :execution-start
                                          {:name "test" :origin "test" :arguments {}})

          (let [result (#'f.chat/transition-tool-call! db* chat-ctx "tool-executing" :stop-requested)]
            (is (match? {:status :stopping
                         :actions []}
                        result)
                "Expected transition from :executing to :stopping with relevant actions"))
          (let [result (#'f.chat/transition-tool-call! db* chat-ctx "tool-executing" :stop-attempted)]
            (is (match? {:status :stopped
                         :actions [:send-toolCallRejected]}
                        result)
                "Expected transition from :stopping to :stopped with relevant actions"))))


      ;; Test :completed -> :stopped (should be no-op or error)
      (testing ":completed -> :stopped (should handle gracefully)"
        (let [approved?* (promise)]
          (#'f.chat/transition-tool-call! db* chat-ctx "tool-completed" :tool-prepare
                                          {:name "test" :origin "test" :arguments-text "{}"})
          (#'f.chat/transition-tool-call! db* chat-ctx "tool-completed" :tool-run
                                          {:approved?* approved?* :name "test" :origin "test" :arguments {} :manual-approval false})
          (#'f.chat/transition-tool-call! db* chat-ctx "tool-completed" :approval-allow)
          (#'f.chat/transition-tool-call! db* chat-ctx "tool-completed" :execution-start
                                          {:name "test" :origin "test" :arguments {}})
          (#'f.chat/transition-tool-call! db* chat-ctx "tool-completed" :execution-end
                                          {:outputs "success" :error nil :name "test" :origin "test" :arguments {}})

          ;; Now try to stop a completed tool call
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"Invalid state transition"
               (#'f.chat/transition-tool-call! db* chat-ctx "tool-completed" :stop-requested))
              "Expected exception as completed tool calls cannot be stopped")))

      ;; Test :rejected -> :stopped
      (testing ":rejected -> :stopped (should handle gracefully)"
        (let [approved?* (promise)]
          (#'f.chat/transition-tool-call! db* chat-ctx "tool-rejected" :tool-prepare
                                          {:name "test" :origin "test" :arguments-text "{}"})
          (#'f.chat/transition-tool-call! db* chat-ctx "tool-rejected" :tool-run
                                          {:approved?* approved?* :name "test" :origin "test" :arguments {} :manual-approval true})
          (#'f.chat/transition-tool-call! db* chat-ctx "tool-rejected" :approval-ask
                                          {:state :running :text "Waiting"})
          (#'f.chat/transition-tool-call! db* chat-ctx "tool-rejected" :user-reject)

          ;; Try to stop an already rejected tool call
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"Invalid state transition"
               (#'f.chat/transition-tool-call! db* chat-ctx "tool-rejected" :stop-requested))
              "Expected exception as already rejected tool calls cannot be stopped"))))))

;; TODO: This test and the previous test seem to be testing similar things.  Clean up.
(deftest transition-tool-call-stop-transitions-test
  (testing "Stop transitions from various states"
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id "test-chat"
          chat-ctx {:chat-id chat-id :request-id "req-1" :messenger (h/messenger)}]

      (testing ":preparing -> :stopped"
        (#'f.chat/transition-tool-call! db* chat-ctx "tool-1" :tool-prepare
                                        {:name "test" :origin "test" :arguments-text "{}"})

        (let [result (#'f.chat/transition-tool-call! db* chat-ctx "tool-1" :stop-requested)]
          (is (match? {:status :stopped
                       :actions [:set-decision-reason :send-toolCallRejected]}
                      result)
              "Expected transition to :stopped with send toolCallRejected action")
          (is (= :stopped (:status (#'f.chat/get-tool-call-state @db* chat-id "tool-1")))
              "Expected tool call state to be in :stopped status")))

      (testing ":waiting-approval -> :rejected"
        (let [approved?* (promise)]
          (#'f.chat/transition-tool-call! db* chat-ctx "tool-2" :tool-prepare
                                          {:name "test" :origin "test" :arguments-text "{}"})
          (#'f.chat/transition-tool-call! db* chat-ctx "tool-2" :tool-run
                                          {:approved?* approved?* :name "test" :origin "test" :arguments {} :manual-approval true})
          (#'f.chat/transition-tool-call! db* chat-ctx "tool-2" :approval-ask
                                          {:state :running :text "Waiting"})

          (let [result (#'f.chat/transition-tool-call! db* chat-ctx "tool-2" :stop-requested)]
            (is (match? {:status :rejected
                         :actions [:set-decision-reason :deliver-approval-false]}
                        result)
                "Expected transition to :refected with deliver approval false action")
            (is (= :rejected (:status (#'f.chat/get-tool-call-state @db* chat-id "tool-2")))
                "Expected tool call state to be in :rejected status")
            (is (= false (deref approved?* 100 :timeout))
                "Expected promise to be delivered with false value"))))

      (testing ":execution-approved -> :stopped"
        (let [approved?* (promise)]
          (#'f.chat/transition-tool-call! db* chat-ctx "tool-3" :tool-prepare
                                          {:name "test" :origin "test" :arguments-text "{}"})
          (#'f.chat/transition-tool-call! db* chat-ctx "tool-3" :tool-run
                                          {:approved?* approved?* :name "test" :origin "test" :arguments {} :manual-approval false})
          (#'f.chat/transition-tool-call! db* chat-ctx "tool-3" :approval-allow)

          (let [result (#'f.chat/transition-tool-call! db* chat-ctx "tool-3" :stop-requested)]
            (is (match? {:status :stopped
                         :actions [:send-toolCallRejected]}
                        result)
                "Expected transition to :stopped with send toolCallRejected action")
            (is (= :stopped (:status (#'f.chat/get-tool-call-state @db* chat-id "tool-3")))
                "Expected tool call state to be in :stopped status")))))))

(deftest test-stop-prompt-messages
  ;; Test what messages are sent when stop-prompt is called
  (testing "should send toolCallRejected for active tool calls"
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id "test-chat"
          chat-ctx {:chat-id chat-id :request-id "req-123" :messenger (h/messenger)}]

      (swap! db* assoc-in [:chats chat-id]
             {:status :running
              :current-request-id "req-123"})

      (#'f.chat/transition-tool-call! db* chat-ctx "tool-1" :tool-prepare
                                      {:name "list_files" :origin "filesystem" :arguments-text "{}"})
      (#'f.chat/transition-tool-call! db* chat-ctx "tool-2" :tool-prepare
                                      {:name "read_file" :origin "filesystem" :arguments-text "{}"})

      (f.chat/prompt-stop {:chat-id chat-id} db* (h/messenger) (h/metrics))

      (let [messages (h/messages)
            chat-messages (:chat-content-received messages)
            system-messages (filter #(= :system (:role %)) chat-messages)
            tool-reject-messages (filter #(= :toolCallRejected (get-in % [:content :type])) chat-messages)]

        (is (< 0 (count system-messages)) "Expected at least one system stop message to be sent")
        (is (some #(s/includes? (get-in % [:content :text]) "stopped") system-messages)
            "Expected system message to contain 'stopped' text")

        (is (< 0 (count tool-reject-messages))
            "Expected at least one toolCallRejected notification to be sent for active tool calls")

        (is (every? (comp :id :content) tool-reject-messages)
            "Expected every toolCallRejected message to have an :id")
        (is (every? (comp :name :content) tool-reject-messages)
            "Expected every toolCallRejected message to have a :name")
        (is (every? (comp :origin :content) tool-reject-messages)
            "Expected every toolCallRejected message to have an :origin")))))

(deftest test-stop-prompt-message-count
  ;; Test that stop-prompt sends appropriate number of messages
  (testing "should send messages including tool call rejections when tool calls exist"
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id "test-chat"
          chat-ctx {:chat-id chat-id :request-id "req-123" :messenger (h/messenger)}]

      (swap! db* assoc-in [:chats chat-id]
             {:status :running
              :current-request-id "req-123"})

      (#'f.chat/transition-tool-call! db* chat-ctx "tool-1" :tool-prepare
                                      {:name "list_files" :origin "filesystem" :arguments-text "{}"})

      (f.chat/prompt-stop {:chat-id chat-id} db* (h/messenger) (h/metrics))

      (let [message-count (count (:chat-content-received (h/messages)))]
        (is (< 1 message-count)
            "Expected more than 1 message when tool calls exist (stop + rejections)")))))

(deftest test-stop-prompt-notification-types
  ;; Test what types of notifications are sent on prompt-stop.
  (testing "should send toolCallRejected in addition to progress/text messages"
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id "test-chat"
          tool-call-id "tool-call-1"
          chat-ctx {:chat-id chat-id :request-id "req-123" :messenger (h/messenger)}
          approved?* (promise)]

      (swap! db* assoc-in [:chats chat-id]
             {:status :running
              :current-request-id "req-123"})

      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-prepare
                                      {:name "list_files" :origin "filesystem" :arguments-text "{}"})
      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-run
                                      {:approved?* approved?*
                                       :name "list_files"
                                       :origin "filesystem"
                                       :arguments {"id" 123 "value" 42}})
      (f.chat/prompt-stop {:chat-id chat-id} db* (h/messenger) (h/metrics))
      (when-not @approved?*
        (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :send-reject
                                        {:approved?* approved?*
                                         :name "list_files"
                                         :origin "filesystem"
                                         :arguments {"id" 123 "value" 42}}))

      (let [chat-messages (:chat-content-received (h/messages))
            tool-call-rejected-messages (filter #(= :toolCallRejected (get-in % [:content :type]))
                                                chat-messages)]

        (is (< 0 (count tool-call-rejected-messages))
            "Expected toolCallRejected notifications when stopping with active tool calls")

        (is (some #(get-in % [:content :arguments] %) tool-call-rejected-messages)
            "Expected at least one toolCallRejected message to include non-nil :arguments")))))

(deftest test-stop-prompt-with-non-running-chat
  ;; Test stop-prompt behavior when chat is not running.
  (testing "should handle non-running chats gracefully without sending messages"
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id "test-chat"]

      (swap! db* assoc-in [:chats chat-id] {:status :idle}) ; Not running

      (is (nil? (f.chat/prompt-stop {:chat-id chat-id} db* (h/messenger) (h/metrics)))
          "Expected nil return value for non-running chat")

      (is (= 0 (count (:chat-content-received (h/messages))))
          "Expected no messages to be sent for non-running chat")

      (is (= :idle (get-in @db* [:chats chat-id :status])) "Expected status to remain unchanged"))))

(deftest test-stop-prompt-logic-behavior-only
  ;; Pure logic test - what the function should do for enhanced behavior.
  (testing "should analyze and handle individual tool calls when stopping"
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id "test-chat"
          chat-ctx {:chat-id chat-id :request-id "req-123" :messenger (h/messenger)}]

      (swap! db* assoc-in [:chats chat-id]
             {:status :running
              :current-request-id "req-123"})

      (#'f.chat/transition-tool-call! db* chat-ctx "tool-1" :tool-prepare
                                      {:name "list_files" :origin "filesystem" :arguments-text "{}"})
      (#'f.chat/transition-tool-call! db* chat-ctx "tool-2" :tool-prepare
                                      {:name "read_file" :origin "filesystem" :arguments-text "{}"})

      (f.chat/prompt-stop {:chat-id chat-id} db* (h/messenger) (h/metrics))

      (let [chat-messages (:chat-content-received (h/messages))]

        (is (< 1 (count chat-messages))
            "Expected multiple actions (handle tool calls + send stop message)")

        (is (some #(s/includes? (get-in % [:content :text] "") "stopped") chat-messages)
            "Expected stop message to be sent")

        (is (some #(= :toolCallRejected (get-in % [:content :type])) chat-messages)
            "Expected toolCallRejected notifications to be sent for active tool calls")))))

;; TODO: This is not really a tool-call state test.  Perhaps move it elsewhere?
(deftest test-stop-prompt-status-change
  ;; Test that chat status changes appropriately.
  (testing "should change status to :stopping after handling tool calls"
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id "test-chat"]

      (swap! db* assoc-in [:chats chat-id] {:status :running})

      (f.chat/prompt-stop {:chat-id chat-id} db* (h/messenger) (h/metrics))

      (let [final-status (get-in @db* [:chats chat-id :status])]
        (is (= :stopping final-status) "Expected status to change to :stopping after stop processing")))))

;;; Edge Cases and Comprehensive Coverage

(deftest transition-tool-call-promise-already-delivered-test
  ;; Test edge case when promise is already delivered before transition
  (testing "Should handle already-delivered promises gracefully"
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id "test-chat"
          tool-call-id "tool-1"
          chat-ctx {:chat-id chat-id :request-id "req-1" :messenger (h/messenger)}
          approved?* (promise)]

      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-prepare
                                      {:name "test" :origin "test" :arguments-text "{}"})
      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-run
                                      {:approved?* approved?* :name "test" :origin "test" :arguments {} :manual-approval false})

      (let [result (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :approval-allow)]
        (is (match? {:status :execution-approved
                     :actions [:set-decision-reason :deliver-approval-true]}
                    result)
            "Expected transition to :execution-approved with deliver approval true action")

        (let [tool-state (#'f.chat/get-tool-call-state @db* chat-id tool-call-id)]
          (is (= :execution-approved (:status tool-state))
              "Expected tool call state to be in :execution-approved status")
          (is (= true (deref (:approved?* tool-state) 100 :timeout))
              "Expected promise to be delivered with true value"))))))

(deftest transition-tool-call-stop-during-execution-test
  ;; Test stopping a tool call during execution 
  (testing ":executing -> :stopping -> :stopped transition"
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id "test-chat"
          tool-call-id "tool-1"
          name "stopping-test"
          origin :native
          chat-ctx {:chat-id chat-id :request-id "req-1" :messenger (h/messenger)}
          approved?* (promise)]

      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-prepare
                                      {:name name :origin origin :arguments-text "{}"})
      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-run
                                      {:approved?* approved?* :name name :origin origin :arguments {} :manual-approval false})
      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :approval-allow)
      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :execution-start
                                      {:name name :origin origin :arguments {}})

      ;; TODO: Expand on these tests as we develop the tool cancellation.
      (let [tool-state (#'f.chat/get-tool-call-state @db* chat-id tool-call-id)
            result (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :stop-requested)]
        (is (= :executing (:status tool-state))
            "Expected tool call state to be in :executing status")
        (is (= :stopping (:status result))
            "Expected tool call state to be :stopping after :stop-requested"))
      (let [tool-state (#'f.chat/get-tool-call-state @db* chat-id tool-call-id)
            result (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :stop-attempted)]
        (is (= :stopping (:status tool-state))
            "Expected tool call state to be in :stopping status")
        (is (= :stopped (:status result))
            "Expected tool call state to be :stopped after :stop-requested"))
      )))

(deftest transition-tool-call-nonexistent-tool-call-operations-test
  ;; Test operations on nonexistent tool calls
  (testing "Should handle operations on tool calls that don't exist"
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id "test-chat"
          tool-call-id "nonexistent-tool"
          chat-ctx {:chat-id chat-id :request-id "req-1" :messenger (h/messenger)}]

      (testing "User approve on nonexistent tool call"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Invalid state transition"
             (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :user-approve))
            "Expected exception for nonexistent tool call")))))

(deftest transition-tool-call-execution-error-handling-test
  ;; Test error scenarios during execution
  (testing "Tool execution with error results"
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id "test-chat"
          tool-call-id "tool-1"
          chat-ctx {:chat-id chat-id :request-id "req-1" :messenger (h/messenger)}
          approved?* (promise)]

      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-prepare
                                      {:name "test" :server "eca" :origin "test" :arguments-text "{}"})
      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-run
                                      {:approved?* approved?* :name "test" :server "eca" :origin "test" :arguments {} :manual-approval false})
      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :approval-allow)
      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :execution-start
                                      {:name "test" :server "eca" :origin "test" :arguments {}})

      (testing ":executing -> :completed with error"
        (let [error-result {:outputs nil
                            :error "File not found: /nonexistent/path"
                            :name "test"
                            :server "eca"
                            :origin "test"
                            :arguments {}}
              result (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :execution-end error-result)]

          (is (match? {:status :completed
                       :actions [:send-toolCalled :log-metrics :send-progress]}
                      result)
              "Expected transition to :completed with send toolCalled and record metrics actions")

          (let [messages (h/messages)
                chat-messages (:chat-content-received messages)
                completed-messages (filter #(= :toolCalled (get-in % [:content :type])) chat-messages)]

            (is (= 1 (count completed-messages))
                "Expected exactly one toolCalled notification")
            (is (match? {:content {:error "File not found: /nonexistent/path"
                                   :outputs nil}}
                        (first completed-messages))
                "Expected toolCalled message to contain error details")))))))

(deftest transition-tool-call-state-persistence-test
  ;; Test that state changes persist correctly across operations
  (testing "State should be maintained across database operations"
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id "test-chat"
          tool-call-id "tool-1"
          chat-ctx {:chat-id chat-id :request-id "req-1" :messenger (h/messenger)}
          approved?* (promise)]

      ;; Create a complete tool call and verify state at each step
      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-prepare
                                      {:name "test" :origin "test" :arguments-text "{\"arg\": \"value\"}"})

      (let [state-after-prepare (#'f.chat/get-tool-call-state @db* chat-id tool-call-id)]
        (is (= :preparing (:status state-after-prepare))
            "Expected state to be :preparing after prepare transition"))

      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-run
                                      {:approved?* approved?* :name "test" :origin "test" :arguments {:arg "value"} :manual-approval false})

      (let [state-after-run (#'f.chat/get-tool-call-state @db* chat-id tool-call-id)]
        (is (= :check-approval (:status state-after-run))
            "Expected state to be :check-approval after run transition")
        (is (identical? approved?* (:approved?* state-after-run))
            "Expected same promise to be stored in state"))

      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :approval-allow)

      (let [state-after-approve (#'f.chat/get-tool-call-state @db* chat-id tool-call-id)]
        (is (= :execution-approved (:status state-after-approve))
            "Expected state to be :execution-approved after approve transition")
        (is (= true (deref (:approved?* state-after-approve) 100 :timeout))
            "Expected promise to be delivered with true value")))))

(deftest transition-tool-call-multiple-chats-isolation-test
  ;; Test that tool calls in different chats are properly isolated
  (testing "Tool calls with same ID in different chats should be independent"
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id-1 "chat-1"
          chat-id-2 "chat-2"
          tool-call-id "same-id"
          chat-ctx-1 {:chat-id chat-id-1 :request-id "req-1" :messenger (h/messenger)}
          chat-ctx-2 {:chat-id chat-id-2 :request-id "req-2" :messenger (h/messenger)}
          approved-1* (promise)
          approved-2* (promise)]

      ;; Tool 1
      (#'f.chat/transition-tool-call! db* chat-ctx-1 tool-call-id :tool-prepare
                                      {:name "test1" :origin "test" :arguments-text "{}"})
      (#'f.chat/transition-tool-call! db* chat-ctx-1 tool-call-id :tool-run
                                      {:approved?* approved-1* :name "test1" :origin "test" :arguments {} :manual-approval false})
      (#'f.chat/transition-tool-call! db* chat-ctx-1 tool-call-id :approval-allow)

      ;; Tool 2
      (#'f.chat/transition-tool-call! db* chat-ctx-2 tool-call-id :tool-prepare
                                      {:name "test2" :origin "test" :arguments-text "{}"})
      (#'f.chat/transition-tool-call! db* chat-ctx-2 tool-call-id :tool-run
                                      {:approved?* approved-2* :name "test2" :origin "test" :arguments {} :manual-approval true})

      (let [state-1 (#'f.chat/get-tool-call-state @db* chat-id-1 tool-call-id)
            state-2 (#'f.chat/get-tool-call-state @db* chat-id-2 tool-call-id)]
        (is (= :execution-approved (:status state-1))
            "Expected first chat's tool call to be in :execution-approved status")
        (is (= :check-approval (:status state-2))
            "Expected second chat's tool call to be in :check-approval status")
        (is (not (identical? (:approved?* state-1) (:approved?* state-2)))
            "Expected different promises to be independent")
        (is (= true (deref (:approved?* state-1) 100 :timeout))
            "Expected first chat's promise to be delivered with true")
        (is (not (realized? (:approved?* state-2)))
            "Expected second chat's promise to not be realized yet")))))

(deftest transition-tool-call-action-execution-errors-test
  ;; Test error handling during action execution
  (testing "Should handle action execution failures gracefully"
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id "test-chat"
          tool-call-id "tool-1"
          ;; Create a chat-ctx with nil messenger to trigger errors
          chat-ctx {:chat-id chat-id :request-id "req-1" :messenger nil}]

      ;; This should work since tool-prepare only triggers send actions
      ;; which would fail with nil messenger, but the state should still transition
      (is (thrown? Exception
                   (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-prepare
                                                   {:name "test" :origin "test" :arguments-text "{}"}))
          "Expected exception when messenger is nil or appropriate error handling"))))

(deftest transition-tool-call-promise-timeout-test
  ;; Test promise timeout behavior
  (testing "Should handle promise timeouts appropriately"
    (h/reset-components!)
    (let [db* (h/db*)
          chat-id "test-chat"
          tool-call-id "tool-1"
          chat-ctx {:chat-id chat-id :request-id "req-1" :messenger (h/messenger)}
          approved?* (promise)]

      ;; Set up tool call with promise but don't approve/reject
      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-prepare
                                      {:name "test" :origin "test" :arguments-text "{}"})
      (#'f.chat/transition-tool-call! db* chat-ctx tool-call-id :tool-run
                                      {:approved?* approved?* :name "test" :origin "test" :arguments {} :manual-approval true})

      ;; Verify promise times out when not delivered
      (let [tool-state (#'f.chat/get-tool-call-state @db* chat-id tool-call-id)]
        (is (= :timeout (deref (:approved?* tool-state) 50 :timeout))
            "Expected promise to timeout when not delivered within timeout period")))))
