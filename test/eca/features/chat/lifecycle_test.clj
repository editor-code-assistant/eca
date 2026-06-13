(ns eca.features.chat.lifecycle-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [are deftest is testing]]
   [eca.features.chat.lifecycle :as lifecycle]
   [eca.features.hooks :as f.hooks]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(set! *warn-on-reflection* true)

(def ^:private chat-id "c1")
(def ^:private agent-name :default)
(def ^:private full-model "openai/test")
(def ^:private config {:autoCompactPercentage 75})

(deftest chat-status-payload-test
  (testing "computes sorted aggregate status fields and classifies ask_user separately"
    (let [db {:chats {chat-id {:status :running
                               :tool-calls {"tool-z" {:status :waiting-approval
                                                       :name "write_file"
                                                       :server "eca"}
                                            "tool-a" {:status :waiting-approval
                                                       :name "shell"
                                                       :server "eca"}
                                            "question" {:status :executing
                                                        :name "ask_user"
                                                        :server "eca"}
                                            "other-question" {:status :executing
                                                              :name "ask_user"
                                                              :server "other"}
                                            "running" {:status :executing
                                                       :name "shell"
                                                       :server "eca"}
                                            "done" {:status :completed
                                                    :name "shell"
                                                    :server "eca"}}}}}]
      (is (match? {:chat-id chat-id
                   :status :running
                   :awaiting-user-input true
                   :waiting-reason "toolApproval"
                   :pending-approval-tool-call-ids ["tool-a" "tool-z"]
                   :pending-question-tool-call-ids ["question"]
                   :running-tool-call-ids ["other-question" "running"]}
                  (#'lifecycle/chat-status-payload db chat-id)))))

  (testing "omits waiting reason when nothing is awaiting user input"
    (let [payload (#'lifecycle/chat-status-payload
                   {:chats {chat-id {:status :idle
                                      :tool-calls {"done" {:status :completed}}}}}
                   chat-id)]
      (is (match? {:awaiting-user-input false
                   :pending-approval-tool-call-ids []
                   :pending-question-tool-call-ids []
                   :running-tool-call-ids []}
                  payload))
      (is (not (contains? payload :waiting-reason))))))

(deftest trigger-chat-status-hook!-dedups-and-runs-advisory-hook-test
  (h/reset-components!)
  (h/config! {:hooks {"a-stop" {:type "chatStatusChanged"
                                 :actions [{:type "shell" :shell "status"}]}
                       "b-after" {:type "chatStatusChanged"
                                  :actions [{:type "shell" :shell "after"}]}}})
  (let [db* (h/db*)
        calls* (atom [])
        hook-inputs* (atom [])
        ctx {:chat-id chat-id
             :db* db*
             :messenger (h/messenger)
             :config (h/config)
             :agent "plan"
             :full-model full-model
             :variant "fast"}]
    (swap! db* assoc-in [:chats chat-id]
           {:id chat-id
            :status :running
            :tool-calls {"tool-2" {:status :waiting-approval
                                    :name "shell"
                                    :server "eca"}}})
    (with-redefs [f.hooks/run-shell-cmd
                  (fn [{:keys [input]}]
                    (let [payload (json/parse-string input true)
                          hook-name (:hook_name payload)]
                      (swap! calls* conj hook-name)
                      (swap! hook-inputs* conj payload)
                      (if (= "a-stop" hook-name)
                        {:exit 0 :out "{\"continue\":false,\"systemMessage\":\"ignored\"}" :err nil}
                        {:exit 0 :out "after" :err nil})))]
      (lifecycle/trigger-chat-status-hook! ctx)
      (lifecycle/trigger-chat-status-hook! ctx)
      (is (nil? (:chat-status-changed (h/messages)))
          "hook-only helper does not emit the protocol notification")
      (is (= ["a-stop"] @calls*)
          "unchanged aggregate is deduped; continue:false only stops later chatStatusChanged hooks for this event")
      (is (nil? (:chat-content-received (h/messages)))
          "advisory status hooks render no chat UI")
      (is (match? {:hook_name "a-stop"
                   :hook_type "chatStatusChanged"
                   :chat_id chat-id
                   :agent "plan"
                   :full_model full-model
                   :variant "fast"
                   :status "running"
                   :awaiting_user_input true
                   :waiting_reason "toolApproval"
                   :pending_approval_tool_call_ids ["tool-2"]
                   :pending_question_tool_call_ids []
                   :running_tool_call_ids []}
                  (first @hook-inputs*)))

      (swap! db* assoc-in [:chats chat-id]
             {:id chat-id :status :idle :tool-calls {}})
      (lifecycle/trigger-chat-status-hook! ctx)
      (is (= ["a-stop" "a-stop"] @calls*)
          "an actual aggregate change fires the hooks again")
      (is (match? {:status "idle"
                   :awaiting_user_input false
                   :pending_approval_tool_call_ids []}
                  (last @hook-inputs*))))))

(defn- db-with [usage model-caps]
  {:chats {chat-id {:usage usage}}
   :models {full-model model-caps}})

(deftest auto-compact?-does-not-crash-on-zero-or-missing-context-limit
  (testing "zero context limit (e.g. models.dev returning 0 for image-only models) is treated as unknown and returns falsy"
    (let [db (db-with {:last-input-tokens 100 :last-output-tokens 50}
                      {:limit {:context 0 :output 0}})]
      (is (not (lifecycle/auto-compact? chat-id agent-name full-model config db)))))

  (testing "nil context limit returns falsy"
    (let [db (db-with {:last-input-tokens 100 :last-output-tokens 50}
                      {:limit {:context nil :output nil}})]
      (is (not (lifecycle/auto-compact? chat-id agent-name full-model config db)))))

  (testing "missing :limit map returns falsy"
    (let [db (db-with {:last-input-tokens 100 :last-output-tokens 50}
                      {})]
      (is (not (lifecycle/auto-compact? chat-id agent-name full-model config db)))))

  (testing "unknown model (no entry in :models) returns falsy"
    (let [db {:chats {chat-id {:usage {:last-input-tokens 100 :last-output-tokens 50}}}}]
      (is (not (lifecycle/auto-compact? chat-id agent-name full-model config db))))))

(deftest auto-compact?-with-positive-context-limit
  (testing "returns truthy when usage meets/exceeds the configured threshold"
    (let [db (db-with {:last-input-tokens 800 :last-output-tokens 0}
                      {:limit {:context 1000 :output 1000}})]
      (is (lifecycle/auto-compact? chat-id agent-name full-model config db))))

  (testing "returns false when usage is below the configured threshold"
    (let [db (db-with {:last-input-tokens 100 :last-output-tokens 0}
                      {:limit {:context 1000 :output 1000}})]
      (is (false? (lifecycle/auto-compact? chat-id agent-name full-model config db)))))

  (testing "respects per-agent autoCompactPercentage override"
    (let [db (db-with {:last-input-tokens 500 :last-output-tokens 0}
                      {:limit {:context 1000 :output 1000}})]
      (is (lifecycle/auto-compact? chat-id agent-name full-model
                                   {:agent {agent-name {:autoCompactPercentage 40}}
                                    :autoCompactPercentage 99}
                                   db)))))

(deftest auto-compact?-respects-in-progress-flags
  (testing "returns nil when chat is already compacting"
    (let [db (-> (db-with {:last-input-tokens 800 :last-output-tokens 0}
                          {:limit {:context 1000 :output 1000}})
                 (assoc-in [:chats chat-id :compacting?] true))]
      (is (nil? (lifecycle/auto-compact? chat-id agent-name full-model config db)))))

  (testing "returns nil when chat is already auto-compacting"
    (let [db (-> (db-with {:last-input-tokens 800 :last-output-tokens 0}
                          {:limit {:context 1000 :output 1000}})
                 (assoc-in [:chats chat-id :auto-compacting?] true))]
      (is (nil? (lifecycle/auto-compact? chat-id agent-name full-model config db))))))

(deftest format-hook-output-test
  ;; [hook-type parsed raw-output => expected]
  (are [hook-type parsed raw-output expected]
       (= expected (#'lifecycle/format-hook-output hook-type parsed raw-output))
    ;; postToolCall replacedOutput is surfaced for visible hooks
    :postToolCall {"replacedOutput" "redacted"} nil "Hook executed\nReplacedOutput: \"redacted\""
    ;; replacedOutput is ignored for non-postToolCall hook types
    :postRequest {"replacedOutput" "redacted"} nil "Hook executed"
    ;; preToolCall updatedInput is surfaced as a structural effect line
    :preToolCall {"updatedInput" {"recursive" true}} nil "Hook executed\nUpdatedInput: {\"recursive\" true}"
    ;; updatedInput is ignored for non-preToolCall hook types
    :postToolCall {"updatedInput" {"recursive" true}} nil "Hook executed"
    ;; non-string replacedOutput is not rendered
    :postToolCall {"replacedOutput" 42} nil "Hook executed"
    ;; blank additive fields are not rendered as structural effect lines
    :preRequest {"replacedPrompt" "   " "additionalContext" ""} nil "Hook executed"
    :postRequest {"followUp" ""} nil "Hook executed"
    ;; empty replacedOutput remains a meaningful explicit replacement
    :postToolCall {"replacedOutput" ""} nil "Hook executed\nReplacedOutput: \"\""
    ;; systemMessage is NOT part of the block text (surfaced standalone instead)
    :postToolCall {"systemMessage" "all good" "additionalContext" "extra"} nil "Hook executed\nAdditionalContext: extra"
    ;; falls back to raw-output when there is no parsed JSON
    :postToolCall nil "raw text" "raw text"
    ;; no parsed JSON and no raw-output yields nil (no block body)
    :postToolCall nil nil nil))

(deftest hook-system-text-test
  (testing "prefixes the hook name and ends with a blank line so it never glues to a following stream"
    (is (= "Hook 'my-hook': all good\n\n"
           (lifecycle/hook-system-text "my-hook" "all good")))))

(deftest turn-stopped-by-hook-message-test
  (testing "blank stopReason uses the generic message"
    (is (= "Turn stopped by hook 'guard'."
           (lifecycle/turn-stopped-by-hook-message "guard" "")))
    (is (= "Turn stopped by hook 'guard'."
           (lifecycle/turn-stopped-by-hook-message "guard" "   ")))))

(deftest compaction-blocked-by-hook-message-test
  (testing "names the blocking hook when known"
    (is (= "Compaction blocked by hook 'guard'."
           (lifecycle/compaction-blocked-by-hook-message "guard"))))

  (testing "falls back to the generic message when the hook name is unknown"
    (is (= "Compaction blocked by hook."
           (lifecycle/compaction-blocked-by-hook-message nil)))
    (is (= "Compaction blocked by hook."
           (lifecycle/compaction-blocked-by-hook-message "")))))

(deftest process-pre-compact-hook-result-test
  (testing "exit 2 blocks compaction and remembers the first blocking hook name (no stderr reason)"
    (let [acc {:blocked? false :reason nil :hook-name nil :stop-turn? false}
          result {:exit 2 :name "first" :raw-error "stderr should not be a reason"}]
      (is (match? {:blocked? true
                   :hook-name "first"
                   :reason nil
                   :stop-turn? false}
                  (#'lifecycle/process-pre-compact-hook-result acc result)))))

  (testing "exit 2 keeps the first hook name when a second hook also blocks"
    (let [acc {:blocked? true :reason nil :hook-name "first" :stop-turn? false}
          result {:exit 2 :name "second"}]
      (is (= "first" (:hook-name (#'lifecycle/process-pre-compact-hook-result acc result))))))

  (testing "successful continue:false blocks, stops the turn and records its hook name"
    (let [acc {:blocked? false :reason nil :hook-name nil :stop-turn? false}
          result {:exit 0 :name "stopper" :parsed {"continue" false "stopReason" "no compacting now"}}]
      (is (match? {:blocked? true
                   :hook-name "stopper"
                   :reason "no compacting now"
                   :stop-turn? true}
                  (#'lifecycle/process-pre-compact-hook-result acc result))))))

(defn- dispatch-ctx
  "Build a chat-ctx for dispatch-finish-callbacks! whose callbacks record their
   invocations into `calls*` (a vector atom). Extra keys override defaults."
  [calls* extra]
  (merge {:chat-id chat-id
          :db* (atom {:chats {chat-id {:auto-compacting? true :compacting? true}}})
          :on-finished-side-effect (fn [] (swap! calls* conj :side-effect) nil)
          :on-after-finish! (fn [] (swap! calls* conj :after-finish))
          :on-follow-up (fn [text _ctx] (swap! calls* conj [:follow-up text]))}
         extra))

(deftest dispatch-finish-callbacks!-test
  (testing "on-finished-side-effect always runs, then on-after-finish! in the default path"
    (let [calls* (atom [])]
      (#'lifecycle/dispatch-finish-callbacks! (dispatch-ctx calls* {}) {})
      (is (= [:side-effect :after-finish] @calls*))))

  (testing "stopping? halts continuations but the side-effect still runs"
    (let [calls* (atom [])]
      (#'lifecycle/dispatch-finish-callbacks! (dispatch-ctx calls* {}) {:stopping? true})
      (is (= [:side-effect] @calls*))))

  (testing "stop-turn? halts continuations but the side-effect still runs"
    (let [calls* (atom [])]
      (#'lifecycle/dispatch-finish-callbacks! (dispatch-ctx calls* {}) {:stop-turn? true})
      (is (= [:side-effect] @calls*))))

  (testing "follow-up text fires on-follow-up (not on-after-finish!) and flags the chat"
    (let [calls* (atom [])
          ctx (dispatch-ctx calls* {})]
      (#'lifecycle/dispatch-finish-callbacks! ctx {:follow-up-text "do more"})
      (is (= [:side-effect [:follow-up "do more"]] @calls*))
      (is (true? (get-in @(:db* ctx) [:chats chat-id :follow-up-active?])))
      (is (nil? (get-in @(:db* ctx) [:chats chat-id :auto-compacting?])))
      (is (nil? (get-in @(:db* ctx) [:chats chat-id :compacting?])))))

  (testing "stop-turn? takes precedence over follow-up text"
    (let [calls* (atom [])]
      (#'lifecycle/dispatch-finish-callbacks! (dispatch-ctx calls* {})
                                              {:follow-up-text "do more" :stop-turn? true})
      (is (= [:side-effect] @calls*))))

  (testing "follow-up text with no on-follow-up callback fires nothing else (no fallback to on-after-finish!)"
    (let [calls* (atom [])]
      (#'lifecycle/dispatch-finish-callbacks! (dispatch-ctx calls* {:on-follow-up nil})
                                              {:follow-up-text "do more"})
      (is (= [:side-effect] @calls*))))

  (testing "stop-after-finish? from opts suppresses on-after-finish!"
    (let [calls* (atom [])]
      (#'lifecycle/dispatch-finish-callbacks! (dispatch-ctx calls* {}) {:stop-after-finish? true})
      (is (= [:side-effect] @calls*))))

  (testing "stop-after-finish? requested by the side-effect result suppresses on-after-finish!"
    (let [calls* (atom [])
          ctx (dispatch-ctx calls* {:on-finished-side-effect
                                    (fn [] (swap! calls* conj :side-effect) {:stop-after-finish? true})})]
      (#'lifecycle/dispatch-finish-callbacks! ctx {})
      (is (= [:side-effect] @calls*)))))

(deftest finish-chat-prompt-stopped!-test
  (testing "skips postRequest hooks and strips finish side-effect callbacks (keeps on-follow-up)"
    (let [captured* (atom nil)]
      (with-redefs [lifecycle/finish-chat-prompt! (fn [status ctx]
                                                    (reset! captured* {:status status :ctx ctx}))]
        (lifecycle/finish-chat-prompt-stopped!
         :idle
         {:chat-id "c1"
          :skip-post-request-hooks? false
          :on-finished-side-effect (fn [])
          :on-after-finish! (fn [])
          :on-follow-up (fn [_ _])}))
      (is (= :idle (:status @captured*)))
      ;; postRequest hooks must not run for a hook-stopped turn
      (is (true? (get-in @captured* [:ctx :skip-post-request-hooks?])))
      ;; finish side-effect callbacks are stripped so no continuation fires
      (is (not (contains? (:ctx @captured*) :on-finished-side-effect)))
      (is (not (contains? (:ctx @captured*) :on-after-finish!)))
      ;; on-follow-up is preserved (but skip-post-request-hooks? means it won't fire)
      (is (contains? (:ctx @captured*) :on-follow-up)))))

(deftest trigger-chat-status-hook!-exit-2-is-non-blocking-test
  (testing "chatStatusChanged hook exit 2 is logged but does not stop later hooks"
    (h/reset-components!)
    ;; Names are chosen so aaa-exit2 runs before bbb-after.
    (h/config! {:hooks {"aaa-exit2" {:type "chatStatusChanged"
                                     :actions [{:type "shell" :shell "exit2"}]}
                        "bbb-after" {:type "chatStatusChanged"
                                     :actions [{:type "shell" :shell "after"}]}}})
    (let [db* (h/db*)
          calls* (atom [])
          ctx {:chat-id chat-id
               :db* db*
               :messenger (h/messenger)
               :config (h/config)
               :agent "default"
               :full-model full-model}]
      (swap! db* assoc-in [:chats chat-id] {:id chat-id :status :running :tool-calls {}})
      (with-redefs [f.hooks/run-shell-cmd
                    (fn [{:keys [input]}]
                      (let [hook-name (:hook_name (json/parse-string input true))]
                        (swap! calls* conj hook-name)
                        (if (= "aaa-exit2" hook-name)
                          {:exit 2 :out nil :err "something went wrong"}
                          {:exit 0 :out nil :err nil})))]
        (lifecycle/trigger-chat-status-hook! ctx)
        (is (= ["aaa-exit2" "bbb-after"] @calls*)
            "exit 2 does not stop later hooks in the chain")
        (is (nil? (:chat-content-received (h/messages)))
            "exit 2 hook produces no chat content")))))

(deftest finish-chat-prompt!-always-emits-protocol-status-test
  (testing "every turn finish emits the protocol chat/statusChanged even when the aggregate is unchanged (command-only turns stay idle throughout)"
    (h/reset-components!)
    (let [db* (h/db*)
          ctx {:chat-id chat-id
               :db* db*
               :messenger (h/messenger)
               :config (h/config)
               :metrics (h/metrics)
               :agent "default"}]
      (swap! db* assoc-in [:chats chat-id] {:id chat-id :status :idle})
      ;; First command-style finish: idle -> idle.
      (lifecycle/finish-chat-prompt! :idle ctx)
      ;; Simulate the next command turn: prompt entry clears :prompt-finished?.
      (swap! db* update-in [:chats chat-id] dissoc :prompt-finished?)
      (lifecycle/finish-chat-prompt! :idle ctx)
      (is (= 2 (count (:chat-status-changed (h/messages))))
          "both finishes must notify clients despite the unchanged idle payload")
      (is (every? #(= :idle (:status %)) (:chat-status-changed (h/messages)))))))
