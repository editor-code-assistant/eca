(ns eca.features.chat.lifecycle-test
  (:require
   [clojure.test :refer [are deftest is testing]]
   [eca.features.chat.lifecycle :as lifecycle]
   [matcher-combinators.test :refer [match?]]))

(set! *warn-on-reflection* true)

(def ^:private chat-id "c1")
(def ^:private agent-name :default)
(def ^:private full-model "openai/test")
(def ^:private config {:autoCompactPercentage 75})

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
