(ns eca.features.chat.lifecycle-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]
   [eca.features.chat.lifecycle :as f.chat.lifecycle]
   [eca.modules.hooks :as m.hooks]
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
                  (#'f.chat.lifecycle/chat-status-payload db chat-id)))))

  (testing "omits waiting reason when nothing is awaiting user input"
    (let [payload (#'f.chat.lifecycle/chat-status-payload
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
    (with-redefs [m.hooks/run-shell-cmd
                  (fn [{:keys [input]}]
                    (let [payload (json/parse-string input true)
                          hook-name (:hook_name payload)]
                      (swap! calls* conj hook-name)
                      (swap! hook-inputs* conj payload)
                      (if (= "a-stop" hook-name)
                        {:exit 0 :out "{\"continue\":false,\"systemMessage\":\"ignored\"}" :err nil}
                        {:exit 0 :out "after" :err nil})))]
      (f.chat.lifecycle/trigger-chat-status-hook! ctx)
      (f.chat.lifecycle/trigger-chat-status-hook! ctx)
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
      (f.chat.lifecycle/trigger-chat-status-hook! ctx)
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
      (is (not (f.chat.lifecycle/auto-compact? chat-id agent-name full-model config db)))))

  (testing "nil context limit returns falsy"
    (let [db (db-with {:last-input-tokens 100 :last-output-tokens 50}
                      {:limit {:context nil :output nil}})]
      (is (not (f.chat.lifecycle/auto-compact? chat-id agent-name full-model config db)))))

  (testing "missing :limit map returns falsy"
    (let [db (db-with {:last-input-tokens 100 :last-output-tokens 50}
                      {})]
      (is (not (f.chat.lifecycle/auto-compact? chat-id agent-name full-model config db)))))

  (testing "unknown model (no entry in :models) returns falsy"
    (let [db {:chats {chat-id {:usage {:last-input-tokens 100 :last-output-tokens 50}}}}]
      (is (not (f.chat.lifecycle/auto-compact? chat-id agent-name full-model config db))))))

(deftest auto-compact?-with-positive-context-limit
  (testing "returns truthy when usage meets/exceeds the configured threshold"
    (let [db (db-with {:last-input-tokens 800 :last-output-tokens 0}
                      {:limit {:context 1000 :output 1000}})]
      (is (f.chat.lifecycle/auto-compact? chat-id agent-name full-model config db))))

  (testing "returns false when usage is below the configured threshold"
    (let [db (db-with {:last-input-tokens 100 :last-output-tokens 0}
                      {:limit {:context 1000 :output 1000}})]
      (is (false? (f.chat.lifecycle/auto-compact? chat-id agent-name full-model config db)))))

  (testing "respects per-agent autoCompactPercentage override"
    (let [db (db-with {:last-input-tokens 500 :last-output-tokens 0}
                      {:limit {:context 1000 :output 1000}})]
      (is (f.chat.lifecycle/auto-compact? chat-id agent-name full-model
                                          {:agent {agent-name {:autoCompactPercentage 40}}
                                           :autoCompactPercentage 99}
                                          db)))))

(deftest auto-compact?-with-tokens-left
  (let [config {:autoCompactPercentage 75 :autoCompactTokensLeft 20000}
        big-model {:limit {:context 1000000 :output 32000}}]
    (testing "ignores the percentage while more than the configured tokens remain"
      (let [db (db-with {:last-input-tokens 800000 :last-output-tokens 0} big-model)]
        (is (false? (f.chat.lifecycle/auto-compact? chat-id agent-name full-model config db)))))

    (testing "triggers once the remaining tokens reach the configured value"
      (let [db (db-with {:last-input-tokens 980000 :last-output-tokens 0} big-model)]
        (is (f.chat.lifecycle/auto-compact? chat-id agent-name full-model config db)))
      (let [db (db-with {:last-input-tokens 985000 :last-output-tokens 0} big-model)]
        (is (f.chat.lifecycle/auto-compact? chat-id agent-name full-model config db))))

    (testing "respects per-agent autoCompactTokensLeft override"
      (let [db (db-with {:last-input-tokens 900000 :last-output-tokens 0} big-model)]
        (is (f.chat.lifecycle/auto-compact? chat-id agent-name full-model
                                            (assoc config :agent {agent-name {:autoCompactTokensLeft 150000}})
                                            db))))

    (testing "falls back to autoCompactPercentage when the value is not below the context limit"
      (let [small-model {:limit {:context 128000 :output 16000}}
            config (assoc config :autoCompactTokensLeft 200000)]
        (is (false? (f.chat.lifecycle/auto-compact? chat-id agent-name full-model config
                                                    (db-with {:last-input-tokens 50000 :last-output-tokens 0} small-model))))
        (is (f.chat.lifecycle/auto-compact? chat-id agent-name full-model config
                                            (db-with {:last-input-tokens 100000 :last-output-tokens 0} small-model)))))))

(deftest auto-compact-threshold-test
  (testing "returns the configured percentage when no absolute value is set"
    (is (= 75 (f.chat.lifecycle/auto-compact-threshold config agent-name 1000000))))

  (testing "derives the effective percentage from autoCompactTokensLeft"
    (is (= 98.0 (f.chat.lifecycle/auto-compact-threshold {:autoCompactTokensLeft 20000} agent-name 1000000)))
    (is (= 87.5 (f.chat.lifecycle/auto-compact-threshold {:autoCompactPercentage 75 :autoCompactTokensLeft 25000}
                                                         agent-name 200000))))

  (testing "falls back to the percentage when the absolute value is not below the context limit"
    (is (= 75 (f.chat.lifecycle/auto-compact-threshold {:autoCompactPercentage 75 :autoCompactTokensLeft 200000}
                                                       agent-name 128000))))

  (testing "nil when auto-compact is disabled"
    (is (nil? (f.chat.lifecycle/auto-compact-threshold {} agent-name 1000000)))))

(deftest auto-compact-max-per-turn-test
  (testing "returns the global value"
    (is (= 3 (f.chat.lifecycle/auto-compact-max-per-turn {:autoCompactMaxPerTurn 3} agent-name))))

  (testing "respects per-agent autoCompactMaxPerTurn override"
    (is (= 1 (f.chat.lifecycle/auto-compact-max-per-turn {:agent {agent-name {:autoCompactMaxPerTurn 1}}
                                                          :autoCompactMaxPerTurn 3}
                                                         agent-name))))

  (testing "nil when unset"
    (is (nil? (f.chat.lifecycle/auto-compact-max-per-turn {} agent-name)))))

(deftest auto-compact?-respects-in-progress-flags
  (testing "returns nil when chat is already compacting"
    (let [db (-> (db-with {:last-input-tokens 800 :last-output-tokens 0}
                          {:limit {:context 1000 :output 1000}})
                 (assoc-in [:chats chat-id :compacting?] true))]
      (is (nil? (f.chat.lifecycle/auto-compact? chat-id agent-name full-model config db)))))

  (testing "returns nil when chat is already auto-compacting"
    (let [db (-> (db-with {:last-input-tokens 800 :last-output-tokens 0}
                          {:limit {:context 1000 :output 1000}})
                 (assoc-in [:chats chat-id :auto-compacting?] true))]
      (is (nil? (f.chat.lifecycle/auto-compact? chat-id agent-name full-model config db))))))

(deftest ended-message-test
  (testing "a hook, named by its module with :by; a blank reason gives the short message"
    (is (= "Turn stopped by hook 'guard'."
           (f.chat.lifecycle/ended-message "Turn stopped" {:module :eca/hooks :by "hook 'guard'"} "")))
    (is (= "Turn stopped by hook 'guard'."
           (f.chat.lifecycle/ended-message "Turn stopped" {:module :eca/hooks :by "hook 'guard'"} "   ")))
    (is (= "Compaction blocked by hook 'guard'."
           (f.chat.lifecycle/ended-message "Compaction blocked" {:module :eca/hooks :by "hook 'guard'"} nil))))

  (testing "a module, by its id, with the reason"
    (is (= "Request blocked by module 'acme/x': too risky"
           (f.chat.lifecycle/ended-message "Request blocked" {:module :acme/x} "too risky")))
    (is (= "Turn stopped by module '?'."
           (f.chat.lifecycle/ended-message "Turn stopped" nil nil)))))

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
      (#'f.chat.lifecycle/dispatch-finish-callbacks! (dispatch-ctx calls* {}) {})
      (is (= [:side-effect :after-finish] @calls*))))

  (testing "stopping? halts continuations but the side-effect still runs"
    (let [calls* (atom [])]
      (#'f.chat.lifecycle/dispatch-finish-callbacks! (dispatch-ctx calls* {}) {:stopping? true})
      (is (= [:side-effect] @calls*))))

  (testing "stop-turn? halts continuations but the side-effect still runs"
    (let [calls* (atom [])]
      (#'f.chat.lifecycle/dispatch-finish-callbacks! (dispatch-ctx calls* {}) {:stop-turn? true})
      (is (= [:side-effect] @calls*))))

  (testing "follow-up text fires on-follow-up (not on-after-finish!) and flags the chat"
    (let [calls* (atom [])
          ctx (dispatch-ctx calls* {})]
      (#'f.chat.lifecycle/dispatch-finish-callbacks! ctx {:follow-up-text "do more"})
      (is (= [:side-effect [:follow-up "do more"]] @calls*))
      (is (true? (get-in @(:db* ctx) [:chats chat-id :follow-up-active?])))
      (is (nil? (get-in @(:db* ctx) [:chats chat-id :auto-compacting?])))
      (is (nil? (get-in @(:db* ctx) [:chats chat-id :compacting?])))))

  (testing "stop-turn? takes precedence over follow-up text"
    (let [calls* (atom [])]
      (#'f.chat.lifecycle/dispatch-finish-callbacks! (dispatch-ctx calls* {})
                                                     {:follow-up-text "do more" :stop-turn? true})
      (is (= [:side-effect] @calls*))))

  (testing "follow-up text with no on-follow-up callback fires nothing else (no fallback to on-after-finish!)"
    (let [calls* (atom [])]
      (#'f.chat.lifecycle/dispatch-finish-callbacks! (dispatch-ctx calls* {:on-follow-up nil})
                                                     {:follow-up-text "do more"})
      (is (= [:side-effect] @calls*))))

  (testing "stop-after-finish? from opts suppresses on-after-finish!"
    (let [calls* (atom [])]
      (#'f.chat.lifecycle/dispatch-finish-callbacks! (dispatch-ctx calls* {}) {:stop-after-finish? true})
      (is (= [:side-effect] @calls*))))

  (testing "stop-after-finish? requested by the side-effect result suppresses on-after-finish!"
    (let [calls* (atom [])
          ctx (dispatch-ctx calls* {:on-finished-side-effect
                                    (fn [] (swap! calls* conj :side-effect) {:stop-after-finish? true})})]
      (#'f.chat.lifecycle/dispatch-finish-callbacks! ctx {})
      (is (= [:side-effect] @calls*)))))

(deftest finish-chat-prompt-stopped!-test
  (testing "skips postRequest hooks and strips finish side-effect callbacks (keeps on-follow-up)"
    (let [captured* (atom nil)]
      (with-redefs [f.chat.lifecycle/finish-chat-prompt! (fn [status ctx]
                                                           (reset! captured* {:status status :ctx ctx}))]
        (f.chat.lifecycle/finish-chat-prompt-stopped!
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
      (with-redefs [m.hooks/run-shell-cmd
                    (fn [{:keys [input]}]
                      (let [hook-name (:hook_name (json/parse-string input true))]
                        (swap! calls* conj hook-name)
                        (if (= "aaa-exit2" hook-name)
                          {:exit 2 :out nil :err "something went wrong"}
                          {:exit 0 :out nil :err nil})))]
        (f.chat.lifecycle/trigger-chat-status-hook! ctx)
        (is (= ["aaa-exit2" "bbb-after"] @calls*)
            "exit 2 does not stop later hooks in the chain")
        (is (nil? (:chat-content-received (h/messages)))
            "exit 2 hook produces no chat content")))))

(deftest send-content!-groups-consecutive-reasoning-test
  (let [contents #(mapv :content (:chat-content-received (h/messages)))
        setup! (fn []
                 (h/reset-components!)
                 (swap! (h/db*) assoc-in [:chats chat-id] {:id chat-id :status :running})
                 {:chat-id chat-id :db* (h/db*) :messenger (h/messenger)})
        send! (fn [ctx role content] (f.chat.lifecycle/send-content! ctx role content))]
    (testing "adjacent blocks render as one block with the first id, empty or not"
      (let [ctx (setup!)]
        (send! ctx :assistant {:type :reasonStarted :id "r1"})
        (send! ctx :assistant {:type :reasonFinished :id "r1" :total-time-ms 1000})
        (send! ctx :assistant {:type :reasonStarted :id "r2"})
        (send! ctx :assistant {:type :reasonText :id "r2" :text "Plan"})
        (send! ctx :assistant {:type :reasonText :id "r2" :text "ning"})
        (send! ctx :assistant {:type :reasonFinished :id "r2" :total-time-ms 500})
        (send! ctx :system {:type :progress :state :running :text "Generating"})
        (send! ctx :system {:type :usage :session-tokens 10})
        (send! ctx :assistant {:type :reasonStarted :id "r3"})
        (send! ctx :assistant {:type :reasonText :id "r3" :text "Done"})
        (send! ctx :assistant {:type :reasonFinished :id "r3" :total-time-ms 250})
        (is (= [{:type :reasonStarted :id "r1"}
                {:type :reasonText :id "r1" :text "Plan"}
                {:type :reasonText :id "r1" :text "ning"}
                {:type :progress :state :running :text "Generating"}
                {:type :usage :session-tokens 10}
                {:type :reasonText :id "r1" :text "\n\nDone"}]
               (contents))
            "progress/usage don't close the group and reasonFinished waits for visible content")
        (testing "the next visible content closes the group with the summed time"
          (send! ctx :assistant {:type :text :text "Hello"})
          (is (= [{:type :reasonFinished :id "r1" :total-time-ms 1750}
                  {:type :text :text "Hello"}]
                 (subvec (contents) 6)))
          (is (nil? (get-in @(h/db*) [:chats chat-id :reason-group]))))
        (testing "a late reasonFinished after the group closed is dropped"
          (send! ctx :assistant {:type :reasonFinished :id "r3" :total-time-ms 1})
          (is (= 8 (count (contents)))))))

    (testing "blocks separated by visible content stay separate"
      (let [ctx (setup!)]
        (send! ctx :assistant {:type :reasonStarted :id "r1"})
        (send! ctx :assistant {:type :reasonText :id "r1" :text "a"})
        (send! ctx :assistant {:type :reasonFinished :id "r1" :total-time-ms 100})
        (send! ctx :assistant {:type :toolCallPrepare :id "t1"})
        (send! ctx :assistant {:type :reasonStarted :id "r2"})
        (send! ctx :assistant {:type :reasonFinished :id "r2" :total-time-ms 50})
        (send! ctx :assistant {:type :text :text "x"})
        (is (= [{:type :reasonStarted :id "r1"}
                {:type :reasonText :id "r1" :text "a"}
                {:type :reasonFinished :id "r1" :total-time-ms 100}
                {:type :toolCallPrepare :id "t1"}
                {:type :reasonStarted :id "r2"}
                {:type :reasonFinished :id "r2" :total-time-ms 50}
                {:type :text :text "x"}]
               (contents)))))

    (testing "a block still in flight counts its elapsed time when the group closes"
      (let [ctx (setup!)]
        (send! ctx :assistant {:type :reasonStarted :id "r1"})
        (send! ctx :assistant {:type :reasonFinished :id "r1" :total-time-ms 100})
        (send! ctx :assistant {:type :reasonStarted :id "r2"})
        (send! ctx :system {:type :text :text "Prompt stopped"})
        (is (match? [{:type :reasonStarted :id "r1"}
                     {:type :reasonFinished :id "r1" :total-time-ms #(>= % 100)}
                     {:type :text :text "Prompt stopped"}]
                    (contents)))))

    (testing "text without a started block opens the group"
      (let [ctx (setup!)]
        (send! ctx :assistant {:type :reasonText :id "r1" :text "a"})
        (is (= [{:type :reasonStarted :id "r1"}
                {:type :reasonText :id "r1" :text "a"}]
               (contents)))))

    (testing "content for chats missing from db, or without db, passes through untouched"
      (h/reset-components!)
      (doseq [ctx [{:chat-id "unknown" :db* (h/db*) :messenger (h/messenger)}
                   {:chat-id chat-id :messenger (h/messenger)}]]
        (send! ctx :assistant {:type :reasonStarted :id "r1"})
        (send! ctx :assistant {:type :reasonFinished :id "r1" :total-time-ms 5}))
      (is (= [{:type :reasonStarted :id "r1"}
              {:type :reasonFinished :id "r1" :total-time-ms 5}
              {:type :reasonStarted :id "r1"}
              {:type :reasonFinished :id "r1" :total-time-ms 5}]
             (contents)))
      (is (= {} (:chats (h/db)))))))

(deftest finish-chat-prompt!-closes-open-reason-group-test
  (h/reset-components!)
  (let [db* (h/db*)
        ctx {:chat-id chat-id
             :db* db*
             :messenger (h/messenger)
             :config (h/config)
             :metrics (h/metrics)
             :agent "default"}]
    (swap! db* assoc-in [:chats chat-id] {:id chat-id :status :running})
    (f.chat.lifecycle/send-content! ctx :assistant {:type :reasonStarted :id "r1"})
    (f.chat.lifecycle/send-content! ctx :assistant {:type :reasonFinished :id "r1" :total-time-ms 42})
    (f.chat.lifecycle/finish-chat-prompt! :idle ctx)
    (is (match? [{:content {:type :reasonStarted :id "r1"}}
                 {:content {:type :reasonFinished :id "r1" :total-time-ms 42}}
                 {:content {:type :progress :state :finished}}]
                (:chat-content-received (h/messages))))
    (is (nil? (get-in @db* [:chats chat-id :reason-group])))))

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
      (f.chat.lifecycle/finish-chat-prompt! :idle ctx)
      ;; Simulate the next command turn: prompt entry clears :prompt-finished?.
      (swap! db* update-in [:chats chat-id] dissoc :prompt-finished?)
      (f.chat.lifecycle/finish-chat-prompt! :idle ctx)
      (is (= 2 (count (:chat-status-changed (h/messages))))
          "both finishes must notify clients despite the unchanged idle payload")
      (is (every? #(= :idle (:status %)) (:chat-status-changed (h/messages)))))))
