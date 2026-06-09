(ns eca.features.hooks-test
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.features.chat :as f.chat]
   [eca.features.chat.lifecycle :as lifecycle]
   [eca.features.chat.tool-calls :as tc]
   [eca.features.hooks :as f.hooks]
   [eca.features.tools :as f.tools]
   [eca.logger :as logger]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(defn cache-session-id [db-cache-path]
  (some-> db-cache-path fs/file fs/parent fs/file-name str))

(defn set-action-payload [a*]
  (fn [p]
    (reset! a* p)))

;;; Basic trigger and matching tests

(deftest trigger-if-matches!-test
  (testing "preRequest hook triggers and provides callbacks"
    (h/reset-components!)
    (h/config! {:hooks {"my-hook" {:type "preRequest"
                                   :actions [{:type "shell"
                                              :shell "echo hey"}]}}})
    (let [on-before-action* (atom nil)
          on-after-action* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "hey" :err nil})]
        (f.hooks/trigger-if-matches!
         :preRequest
         {:foo "1"}
         {:on-before-action (set-action-payload on-before-action*)
          :on-after-action (set-action-payload on-after-action*)}
         (h/db)
         (h/config)))
      (is (match? {:name "my-hook" :visible? true} @on-before-action*))
      (is (match? {:name "my-hook" :exit 0 :raw-output "hey"} @on-after-action*))))

  (testing "preToolCall matcher filters correctly"
    (h/reset-components!)
    (h/config! {:hooks {"my-hook" {:type "preToolCall"
                                   :matcher "my-mcp__my.*"
                                   :actions [{:type "shell" :shell "echo hey"}]}}})
    (let [result* (atom nil)]
      ;; Should NOT match
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "hey" :err nil})]
        (f.hooks/trigger-if-matches! :preToolCall
                                     {:server "other-mcp" :tool-name "my-tool"}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (nil? @result*))

      ;; Should match
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "hey" :err nil})]
        (f.hooks/trigger-if-matches! :preToolCall
                                     {:server "my-mcp" :tool-name "my-tool"}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (match? {:name "my-hook"} @result*))))

  (testing "preCompact matcher filters by triggered"
    (h/reset-components!)
    (h/config! {:hooks {"manual-only" {:type "preCompact"
                                       :matcher "manual"
                                       :actions [{:type "shell" :shell "echo hey"}]}}})
    (let [result* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "hey" :err nil})]
        (f.hooks/trigger-if-matches! :preCompact
                                     {:triggered "auto"}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (nil? @result*))

      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "hey" :err nil})]
        (f.hooks/trigger-if-matches! :preCompact
                                     {:triggered "manual"}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (match? {:name "manual-only"} @result*))))

  (testing "preCompact without matcher runs for manual and auto triggers"
    (h/reset-components!)
    (h/config! {:hooks {"all-compact" {:type "preCompact"
                                       :actions [{:type "shell" :shell "echo hey"}]}}})
    (let [triggers* (atom [])]
      (with-redefs [f.hooks/run-shell-cmd (fn [{:keys [input]}]
                                            (swap! triggers* conj (:triggered (json/parse-string input true)))
                                            {:exit 0 :out "hey" :err nil})]
        (doseq [triggered ["manual" "auto"]]
          (f.hooks/trigger-if-matches! :preCompact
                                       {:triggered triggered}
                                       {}
                                       (h/db) (h/config))))
      (is (= ["manual" "auto"] @triggers*))))

  (testing "compact object matcher warns and skips"
    (h/reset-components!)
    (h/config! {:hooks {"bad-compact" {:type "preCompact"
                                       :matcher {"manual" {}}
                                       :actions [{:type "shell" :shell "echo hey"}]}}})
    (let [result* (atom nil)
          warnings* (atom [])]
      (with-redefs [logger/warn (fn [& args] (swap! warnings* conj args))
                    f.hooks/run-shell-cmd (constantly {:exit 0 :out "hey" :err nil})]
        (f.hooks/trigger-if-matches! :preCompact
                                     {:triggered "manual"}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (nil? @result*))
      (is (some #(= "Ignoring unsupported compact hook matcher" (second %)) @warnings*))))

  (testing "exit code 2 does not stop remaining pre hooks"
    (doseq [[hook-type data] [[:preRequest {:foo "1"}]
                              [:preToolCall {:server "eca" :tool-name "write_file"}]
                              [:preCompact {:triggered "manual"}]]]
      (h/reset-components!)
      (h/config! {:hooks {"a-block" {:type (name hook-type)
                                     :actions [{:type "shell" :shell "exit 2"}]}
                          "b-after" {:type (name hook-type)
                                     :actions [{:type "shell" :shell "echo after"}]}}})
      (let [calls* (atom [])]
        (with-redefs [f.hooks/run-shell-cmd (fn [{:keys [input]}]
                                              (let [hook-name (:hook_name (json/parse-string input true))]
                                                (swap! calls* conj hook-name)
                                                (if (= "a-block" hook-name)
                                                  {:exit f.hooks/hook-rejection-exit-code
                                                   :out nil
                                                   :err "blocked"}
                                                  {:exit 0
                                                   :out "after"
                                                   :err nil})))]
          (f.hooks/trigger-if-matches! hook-type data {} (h/db) (h/config)))
        (is (= ["a-block" "b-after"] @calls*)))))

  (testing "exit 0 continue false stops remaining non-chatEnd hooks"
    (h/reset-components!)
    (h/config! {:hooks {"a-stop" {:type "postRequest"
                                  :actions [{:type "shell" :shell "echo"}]}
                        "b-after" {:type "postRequest"
                                   :actions [{:type "shell" :shell "echo after"}]}}})
    (let [calls* (atom [])]
      (with-redefs [f.hooks/run-shell-cmd (fn [{:keys [input]}]
                                            (let [hook-name (:hook_name (json/parse-string input true))]
                                              (swap! calls* conj hook-name)
                                              (if (= "a-stop" hook-name)
                                                {:exit 0
                                                 :out "{\"continue\":false}"
                                                 :err nil}
                                                {:exit 0
                                                 :out "after"
                                                 :err nil})))]
        (f.hooks/trigger-if-matches! :postRequest {:prompt "hi"} {} (h/db) (h/config)))
      (is (= ["a-stop"] @calls*))))

  (testing "non-zero continue false does not stop remaining hooks"
    (h/reset-components!)
    (h/config! {:hooks {"a-fail" {:type "postRequest"
                                  :actions [{:type "shell" :shell "echo"}]}
                        "b-after" {:type "postRequest"
                                   :actions [{:type "shell" :shell "echo after"}]}}})
    (let [calls* (atom [])]
      (with-redefs [f.hooks/run-shell-cmd (fn [{:keys [input]}]
                                            (let [hook-name (:hook_name (json/parse-string input true))]
                                              (swap! calls* conj hook-name)
                                              (if (= "a-fail" hook-name)
                                                {:exit 1
                                                 :out "{\"continue\":false}"
                                                 :err "failed"}
                                                {:exit 0
                                                 :out "after"
                                                 :err nil})))]
        (f.hooks/trigger-if-matches! :postRequest {:prompt "hi"} {} (h/db) (h/config)))
      (is (= ["a-fail" "b-after"] @calls*))))

  (testing "chatEnd continue false stops remaining hooks"
    (h/reset-components!)
    (h/config! {:hooks {"a-cleanup" {:type "chatEnd"
                                     :actions [{:type "shell" :shell "echo"}]}
                        "b-cleanup" {:type "chatEnd"
                                     :actions [{:type "shell" :shell "echo after"}]}}})
    (let [calls* (atom [])]
      (with-redefs [f.hooks/run-shell-cmd (fn [{:keys [input]}]
                                            (let [hook-name (:hook_name (json/parse-string input true))]
                                              (swap! calls* conj hook-name)
                                              {:exit 0
                                               :out "{\"continue\":false}"
                                               :err nil}))]
        (f.hooks/trigger-if-matches! :chatEnd {:chat-id "chat-1"} {} (h/db) (h/config)))
      (is (= ["a-cleanup"] @calls*)))))

;;; JSON parsing tests

(deftest json-output-parsing-test
  (testing "valid JSON output is parsed"
    (h/reset-components!)
    (h/config! {:hooks {"test" {:type "preRequest"
                                :actions [{:type "shell"
                                           :shell "echo '{\"additionalContext\":\"test\"}'"}]}}})
    (let [result* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0
                                                       :out "{\"additionalContext\":\"test\"}"
                                                       :err nil})]
        (f.hooks/trigger-if-matches! :preRequest {:foo "1"}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (= "test" (get-in @result* [:parsed "additionalContext"])))))

  (testing "invalid JSON falls back to plain text"
    (h/reset-components!)
    (h/config! {:hooks {"test" {:type "preRequest"
                                :actions [{:type "shell" :shell "echo 'plain'"}]}}})
    (let [result* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "plain" :err nil})]
        (f.hooks/trigger-if-matches! :preRequest {:foo "1"}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (nil? (:parsed @result*)))
      (is (= "plain" (:raw-output @result*))))))

;;; New features tests

(deftest tool-input-and-tool-response-test
  (testing "preToolCall uses tool_input (renamed from arguments)"
    (h/reset-components!)
    (swap! (h/db*) assoc :chats {"chat-1" {:agent "code"}})
    (h/config! {:hooks {"test" {:type "preToolCall"
                                :actions [{:type "shell" :shell "cat"}]}}})
    (let [result* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (fn [opts]
                                            (reset! result* (json/parse-string (:input opts) true))
                                            {:exit 0 :out "" :err nil})]
        (f.hooks/trigger-if-matches! :preToolCall
                                     (merge (f.hooks/chat-hook-data (h/db) {:chat-id "chat-1" :agent "code"})
                                            {:tool-name "read_file"
                                             :server "eca"
                                             :tool-input {:path "/foo"}
                                             :approval :ask
                                             :tool-call-id "call-abc"})
                                     {} (h/db) (h/config)))
      (is (= {:path "/foo"} (:tool_input @result*)))
      (is (= "call-abc" (:tool_call_id @result*)))
      (is (not (contains? @result* :arguments)))))

  (testing "postToolCall receives tool_input and tool_response"
    (h/reset-components!)
    (swap! (h/db*) assoc :chats {"chat-1" {:agent "code"}})
    (h/config! {:hooks {"test" {:type "postToolCall"
                                :actions [{:type "shell" :shell "cat"}]}}})
    (let [result* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (fn [opts]
                                            (reset! result* (json/parse-string (:input opts) true))
                                            {:exit 0 :out "" :err nil})]
        (f.hooks/trigger-if-matches! :postToolCall
                                     (merge (f.hooks/chat-hook-data (h/db) {:chat-id "chat-1" :agent "code"})
                                            {:tool-name "read_file"
                                             :server "eca"
                                             :tool-input {:path "/foo"}
                                             :tool-response "file content\n"
                                             :tool-call-id "call-def"})
                                     {} (h/db) (h/config)))
      (is (= {:path "/foo"} (:tool_input @result*)))
      (is (= "call-def" (:tool_call_id @result*)))
      (is (= "file content\n" (:tool_response @result*))))))

(deftest follow-up-active-test
  (testing "postRequest receives follow_up_active flag"
    (h/reset-components!)
    (swap! (h/db*) assoc :chats {"chat-1" {:agent "code"}})
    (h/config! {:hooks {"test" {:type "postRequest"
                                :actions [{:type "shell" :shell "cat"}]}}})
    (let [result* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (fn [opts]
                                            (reset! result* (json/parse-string (:input opts) true))
                                            {:exit 0 :out "" :err nil})]
        (f.hooks/trigger-if-matches! :postRequest
                                     (merge (f.hooks/chat-hook-data (h/db) {:chat-id "chat-1" :agent "code"})
                                            {:prompt "test"
                                             :follow-up-active false})
                                     {} (h/db) (h/config)))
      (is (false? (:follow_up_active @result*))))))

(deftest postrequest-follow-up-active-defaults-to-false-via-finish-test
  (testing "finish-chat-prompt! always sends follow_up_active as a boolean (false when no follow-up is active)"
    (h/reset-components!)
    (swap! (h/db*) assoc :chats {"chat-1" {:agent "code"}})
    (h/config! {:hooks {"pr" {:type "postRequest"
                              :actions [{:type "shell" :shell "cat"}]}}})
    (let [input* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (fn [opts]
                                            (reset! input* (json/parse-string (:input opts) true))
                                            {:exit 0 :out "" :err nil})]
        (lifecycle/finish-chat-prompt! :idle {:db* (h/db*)
                                              :config (h/config)
                                              :chat-id "chat-1"
                                              :agent "code"
                                              :messenger (h/messenger)
                                              :metrics (h/metrics)}))
      (is (some? @input*))
      ;; The key is always present, even when false.
      (is (contains? @input* :follow_up_active))
      (is (false? (:follow_up_active @input*))))))

(deftest post-request-response-test
  (testing "postRequest receives current response, not prompt"
    (h/reset-components!)
    (swap! (h/db*) assoc :chats {"chat-1" {:agent "code"
                                            :messages [{:role "assistant"
                                                        :content [{:type :text :text "old answer"}]}]}})
    (h/config! {:hooks {"test" {:type "postRequest"
                                :actions [{:type "shell" :shell "cat"}]}}})
    (let [input* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (fn [opts]
                                            (reset! input* (json/parse-string (:input opts) true))
                                            {:exit 0 :out "" :err nil})]
        (lifecycle/finish-chat-prompt! :idle {:db* (h/db*)
                                              :config (h/config)
                                              :chat-id "chat-1"
                                              :agent "code"
                                              :message "original prompt"
                                              :response "final answer"
                                              :messenger (h/messenger)
                                              :metrics (h/metrics)}))
      (is (= "final answer" (:response @input*)))
      (is (not (contains? @input* :prompt)))))

  (testing "postRequest omits response instead of falling back to older history"
    (h/reset-components!)
    (swap! (h/db*) assoc :chats {"chat-1" {:agent "code"
                                            :messages [{:role "assistant"
                                                        :content [{:type :text :text "old answer"}]}]}})
    (h/config! {:hooks {"test" {:type "postRequest"
                                :actions [{:type "shell" :shell "cat"}]}}})
    (let [input* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (fn [opts]
                                            (reset! input* (json/parse-string (:input opts) true))
                                            {:exit 0 :out "" :err nil})]
        (lifecycle/finish-chat-prompt! :idle {:db* (h/db*)
                                              :config (h/config)
                                              :chat-id "chat-1"
                                              :agent "code"
                                              :messenger (h/messenger)
                                              :metrics (h/metrics)}))
      (is (not (contains? @input* :response))))))

(deftest finish-chat-prompt-finalization-test
  (testing "finalizer runs while auto-compacting so maintenance prompts can unblock"
    (h/reset-components!)
    (swap! (h/db*) assoc :chats {"chat-1" {:agent "code"
                                            :auto-compacting? true}})
    (let [finalized?* (atom false)]
      (lifecycle/finish-chat-prompt! :idle {:db* (h/db*)
                                            :config (h/config)
                                            :chat-id "chat-1"
                                            :agent "code"
                                            :messenger (h/messenger)
                                            :metrics (h/metrics)
                                            :on-finished-side-effect (fn []
                                                            (reset! finalized?* true)
                                                            (swap! (h/db*) update-in [:chats "chat-1"] dissoc :auto-compacting?))})
      (is (true? @finalized?*))
      (is (not (get-in (h/db) [:chats "chat-1" :auto-compacting?])))))

  (testing "postRequest hooks are skipped while auto-compacting (maintenance prompt finish)"
    (h/reset-components!)
    (swap! (h/db*) assoc :chats {"chat-1" {:agent "code"
                                            :auto-compacting? true}})
    (h/config! {:hooks {"pr" {:type "postRequest"
                              :actions [{:type "shell" :shell "echo"}]}}})
    (let [ran?* (atom false)]
      (with-redefs [f.hooks/run-shell-cmd (fn [_]
                                            (reset! ran?* true)
                                            {:exit 0 :out "" :err nil})]
        (lifecycle/finish-chat-prompt! :idle {:db* (h/db*)
                                              :config (h/config)
                                              :chat-id "chat-1"
                                              :agent "code"
                                              :messenger (h/messenger)
                                              :metrics (h/metrics)}))
      (is (false? @ran?*) "postRequest must not fire after the auto-compact maintenance prompt")))

  (testing "postRequest hooks are skipped when caller sets :skip-post-request-hooks? (manual /compact)"
    (h/reset-components!)
    (swap! (h/db*) assoc :chats {"chat-1" {:agent "code"}})
    (h/config! {:hooks {"pr" {:type "postRequest"
                              :actions [{:type "shell" :shell "echo"}]}}})
    (let [ran?* (atom false)]
      (with-redefs [f.hooks/run-shell-cmd (fn [_]
                                            (reset! ran?* true)
                                            {:exit 0 :out "" :err nil})]
        (lifecycle/finish-chat-prompt! :idle {:db* (h/db*)
                                              :config (h/config)
                                              :chat-id "chat-1"
                                              :agent "code"
                                              :messenger (h/messenger)
                                              :metrics (h/metrics)
                                              :skip-post-request-hooks? true}))
      (is (false? @ran?*) "postRequest must not fire when :skip-post-request-hooks? is set (manual /compact)")))

  (testing "postRequest followUp does not suppress mandatory finalization"
    (h/reset-components!)
    (swap! (h/db*) assoc :chats {"chat-1" {:agent "code"}})
    (h/config! {:hooks {"follow" {:type "postRequest"
                                   :actions [{:type "shell" :shell "echo"}]}}})
    (let [finalized?* (atom false)
          after-finish?* (atom false)
          follow-up* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0
                                                       :out "{\"followUp\":\"please continue\"}"
                                                       :err nil})]
        (lifecycle/finish-chat-prompt! :idle {:db* (h/db*)
                                              :config (h/config)
                                              :chat-id "chat-1"
                                              :agent "code"
                                              :messenger (h/messenger)
                                              :metrics (h/metrics)
                                              :on-finished-side-effect #(reset! finalized?* true)
                                              :on-after-finish! #(reset! after-finish?* true)
                                              :on-follow-up (fn [follow-up-text _chat-ctx]
                                                              (reset! follow-up* follow-up-text))}))
      (is (true? @finalized?*))
      (is (false? @after-finish?*))
      (is (= "please continue" @follow-up*))))

  (testing "postRequest continue false does not suppress mandatory finalization"
    (h/reset-components!)
    (swap! (h/db*) assoc :chats {"chat-1" {:agent "code"}})
    (h/config! {:hooks {"stop" {:type "postRequest"
                                 :actions [{:type "shell" :shell "echo"}]}}})
    (let [finalized?* (atom false)
          after-finish?* (atom false)]
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0
                                                       :out "{\"continue\":false}"
                                                       :err nil})]
        (lifecycle/finish-chat-prompt! :idle {:db* (h/db*)
                                              :config (h/config)
                                              :chat-id "chat-1"
                                              :agent "code"
                                              :messenger (h/messenger)
                                              :metrics (h/metrics)
                                              :on-finished-side-effect #(reset! finalized?* true)
                                              :on-after-finish! #(reset! after-finish?* true)}))
      (is (true? @finalized?*))
      (is (false? @after-finish?*)))))

(deftest approval-field-test
  (testing "preToolCall can return approval in JSON output"
    (h/reset-components!)
    (h/config! {:hooks {"test" {:type "preToolCall"
                                :actions [{:type "shell"
                                           :shell "echo '{\"approval\":\"deny\",\"additionalContext\":\"Too large\"}'"}]}}})
    (let [result* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0
                                                       :out "{\"approval\":\"deny\",\"additionalContext\":\"Too large\"}"
                                                       :err nil})]
        (f.hooks/trigger-if-matches! :preToolCall
                                     {:server "eca" :tool-name "read"}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (= "deny" (get-in @result* [:parsed "approval"])))
      (is (= "Too large" (get-in @result* [:parsed "additionalContext"]))))))

;;; Lifecycle hooks tests

(deftest hook-action-notification-test
  (testing "hook output display reads string-keyed parsed JSON fields"
    (let [sent* (atom [])]
      (with-redefs [lifecycle/send-content! (fn [_ role content]
                                              (swap! sent* conj {:role role :content content}))]
        (lifecycle/notify-after-hook-action!
         {}
         {:id "action-1"
          :name "my-hook"
          :type "shell"
          :hook-type :preRequest
          :visible? true
          :exit 0
          :parsed {"systemMessage" "Hook done"
                   "replacedPrompt" "new prompt"
                   "additionalContext" "extra context"}
          :raw-output nil
          :raw-error nil}))
      (is (= [{:role :system
               :content {:type :hookActionFinished
                         :action-type "shell"
                         :id "action-1"
                         :name "my-hook"
                         :status 0
                         :output "Hook executed\nReplacedPrompt: \"new prompt\"\nAdditionalContext: extra context"
                         :error nil}}
              ;; systemMessage is surfaced standalone, prefixed with the hook name
              {:role :system
               :content {:type :text :text "Hook 'my-hook': Hook done\n\n"}}]
             @sent*))))

  (testing "tool hooks carry tool-call-id for correlation (no Tool line in the body)"
    (let [sent* (atom [])]
      (with-redefs [lifecycle/send-content! (fn [_ role content]
                                              (swap! sent* conj {:role role :content content}))]
        (lifecycle/notify-after-hook-action!
         {}
         {:id "action-1"
          :name "my-hook"
          :type "shell"
          :hook-type :postToolCall
          :visible? true
          :exit 0
          :parsed {"replacedOutput" "redacted"}
          :raw-output nil
          :raw-error nil
          :tool-call-id "call-42"}))
      (is (= [{:role :system
               :content {:type :hookActionFinished
                         :action-type "shell"
                         :id "action-1"
                         :name "my-hook"
                         :status 0
                         :tool-call-id "call-42"
                         :output "Hook executed\nReplacedOutput: \"redacted\""
                         :error nil}}]
             @sent*))))

  (testing "tool-call-id survives suppressOutput (metadata, not body)"
    (let [sent* (atom [])]
      (with-redefs [lifecycle/send-content! (fn [_ role content]
                                              (swap! sent* conj {:role role :content content}))]
        (lifecycle/notify-after-hook-action!
         {}
         {:id "action-1"
          :name "my-hook"
          :type "shell"
          :hook-type :postToolCall
          :visible? true
          :exit 0
          :parsed {"suppressOutput" true}
          :raw-output nil
          :raw-error nil
          :tool-call-id "call-42"}))
      (is (= [{:role :system
               :content {:type :hookActionFinished
                         :action-type "shell"
                         :id "action-1"
                         :name "my-hook"
                         :status 0
                         :tool-call-id "call-42"}}]
             @sent*))))

  (testing "replacedPrompt display is limited to preRequest"
    (let [sent* (atom [])]
      (with-redefs [lifecycle/send-content! (fn [_ role content]
                                              (swap! sent* conj {:role role :content content}))]
        (lifecycle/notify-after-hook-action!
         {}
         {:id "action-1"
          :name "my-hook"
          :type "shell"
          :hook-type :postRequest
          :visible? true
          :exit 0
          :parsed {"systemMessage" "Hook done"
                   "replacedPrompt" "ignored"
                   "additionalContext" "extra context"}
          :raw-output nil
          :raw-error nil}))
      (is (= "Hook executed\nAdditionalContext: extra context"
             (get-in (first @sent*) [:content :output])))))

  (testing "suppressOutput keeps the finished event (closes the spinner) but drops output/error"
    (let [sent* (atom [])]
      (with-redefs [lifecycle/send-content! (fn [_ role content]
                                              (swap! sent* conj {:role role :content content}))]
        (lifecycle/notify-after-hook-action!
         {}
         {:id "action-1"
          :name "my-hook"
          :type "shell"
          :visible? true
          :exit 0
          :parsed {"suppressOutput" true}
          :raw-output nil
          :raw-error nil}))
      ;; visible? sent a started, so a matching finished must close the spinner;
      ;; suppressOutput only strips the body (no :output/:error).
      (is (= [{:role :system
               :content {:type :hookActionFinished
                         :action-type "shell"
                         :id "action-1"
                         :name "my-hook"
                         :status 0}}]
             @sent*))))

  (testing "non-zero exit displays raw stdout instead of parsed JSON fields"
    (let [sent* (atom [])
          raw-output "{\"additionalContext\":\"ignored\"}"]
      (with-redefs [lifecycle/send-content! (fn [_ role content]
                                              (swap! sent* conj {:role role :content content}))]
        (lifecycle/notify-after-hook-action!
         {}
         {:id "action-1"
          :name "my-hook"
          :type "shell"
          :visible? true
          :exit 1
          :parsed {"additionalContext" "ignored"}
          :raw-output raw-output
          :raw-error "failed"}))
      (is (= [{:role :system
               :content {:type :hookActionFinished
                         :action-type "shell"
                         :id "action-1"
                         :name "my-hook"
                         :status 1
                         :output raw-output
                         :error "failed"}}]
             @sent*))))

  (testing "chatEnd ignores parsed output effects"
    (let [sent* (atom [])
          raw-output "{\"suppressOutput\":true,\"additionalContext\":\"ignored\"}"]
      (with-redefs [lifecycle/send-content! (fn [_ role content]
                                              (swap! sent* conj {:role role :content content}))]
        (lifecycle/notify-after-hook-action!
         {}
         {:id "action-1"
          :name "cleanup"
          :type "shell"
          :hook-type :chatEnd
          :visible? true
          :exit 0
          :parsed {"suppressOutput" true
                   "additionalContext" "ignored"}
          :raw-output raw-output
          :raw-error nil}))
      (is (= [{:role :system
               :content {:type :hookActionFinished
                         :action-type "shell"
                         :id "action-1"
                         :name "cleanup"
                         :status 0
                         :output raw-output
                         :error nil}}]
             @sent*)))))

(deftest invisible-hook-notification-test
  (testing "invisible hook with no systemMessage stays silent"
    (let [sent* (atom [])]
      (with-redefs [lifecycle/send-content! (fn [_ role content]
                                              (swap! sent* conj {:role role :content content}))]
        (lifecycle/notify-after-hook-action!
         {}
         {:id "a1" :name "quiet" :type "shell" :hook-type :preToolCall
          :visible? false :exit 0
          :parsed {"additionalContext" "ctx" "replacedOutput" "x"}
          :raw-output "stdout" :raw-error "stderr"}))
      (is (empty? @sent*)
          "narration, stdout/stderr and LLM-payload fields are suppressed for invisible hooks")))

  (testing "invisible hook surfaces systemMessage as a plain system text"
    (let [sent* (atom [])]
      (with-redefs [lifecycle/send-content! (fn [_ role content]
                                              (swap! sent* conj {:role role :content content}))]
        (lifecycle/notify-after-hook-action!
         {}
         {:id "a1" :name "quiet" :type "shell" :hook-type :preToolCall
          :visible? false :exit 0
          :parsed {"systemMessage" "Heads up" "additionalContext" "ctx"}
          :raw-output nil :raw-error nil}))
      (is (= [{:role :system :content {:type :text :text "Hook 'quiet': Heads up\n\n"}}]
             @sent*)
          "only the deliberate systemMessage is surfaced (prefixed), not additionalContext/diagnostics")))

  (testing "blank systemMessage is treated as absent"
    (let [sent* (atom [])]
      (with-redefs [lifecycle/send-content! (fn [_ role content]
                                              (swap! sent* conj {:role role :content content}))]
        (lifecycle/notify-after-hook-action!
         {}
         {:id "a1" :name "quiet" :type "shell" :hook-type :preToolCall
          :visible? false :exit 0
          :parsed {"systemMessage" "   "}
          :raw-output nil :raw-error nil}))
      (is (empty? @sent*))))

  (testing "suppressOutput hides the block but never the systemMessage"
    (let [sent* (atom [])]
      (with-redefs [lifecycle/send-content! (fn [_ role content]
                                              (swap! sent* conj {:role role :content content}))]
        (lifecycle/notify-after-hook-action!
         {}
         {:id "a1" :name "quiet" :type "shell" :hook-type :preToolCall
          :visible? false :exit 0
          :parsed {"systemMessage" "Heads up" "suppressOutput" true}
          :raw-output nil :raw-error nil}))
      (is (= [{:role :system :content {:type :text :text "Hook 'quiet': Heads up\n\n"}}]
             @sent*))))

  (testing "visible hook with suppressOutput still surfaces systemMessage (block hidden, message kept)"
    (let [sent* (atom [])]
      (with-redefs [lifecycle/send-content! (fn [_ role content]
                                              (swap! sent* conj {:role role :content content}))]
        (lifecycle/notify-after-hook-action!
         {}
         {:id "a1" :name "loud" :type "shell" :hook-type :preToolCall
          :visible? true :exit 0
          :parsed {"systemMessage" "Heads up" "suppressOutput" true "additionalContext" "ctx"}
          :raw-output nil :raw-error "noise"}))
      ;; visible? -> a finished still closes the spinner, but suppressOutput strips
      ;; its body (no stderr/additionalContext). systemMessage survives separately.
      (is (= [{:role :system
               :content {:type :hookActionFinished
                         :action-type "shell"
                         :id "a1"
                         :name "loud"
                         :status 0}}
              {:role :system :content {:type :text :text "Hook 'loud': Heads up\n\n"}}]
             @sent*)
          "suppressOutput drops the block body but keeps the finished event; systemMessage survives")))

  (testing "invisible hook does not surface systemMessage on non-zero exit (JSON effects only on exit 0)"
    (let [sent* (atom [])]
      (with-redefs [lifecycle/send-content! (fn [_ role content]
                                              (swap! sent* conj {:role role :content content}))]
        (lifecycle/notify-after-hook-action!
         {}
         {:id "a1" :name "quiet" :type "shell" :hook-type :preToolCall
          :visible? false :exit 2
          :parsed {"systemMessage" "Heads up"}
          :raw-output nil :raw-error "boom"}))
      (is (empty? @sent*)))))

(deftest session-hooks-test
  (testing "sessionStart has base fields only"
    (h/reset-components!)
    (h/config! {:hooks {"test" {:type "sessionStart"
                                :actions [{:type "shell" :shell "cat"}]}}})
    (let [result* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (fn [opts]
                                            (reset! result* (json/parse-string (:input opts) true))
                                            {:exit 0 :out "" :err nil})]
        (f.hooks/trigger-if-matches! :sessionStart
                                     (f.hooks/base-hook-data (h/db))
                                     {} (h/db) (h/config)))
      (is (contains? @result* :workspaces))
      (is (contains? @result* :db_cache_path))
      (is (= (cache-session-id (:db_cache_path @result*)) (:session_id @result*)))
      (is (= (first (:workspaces @result*)) (:cwd @result*)))
      (is (not (contains? @result* :chat_id)))
      (is (not (contains? @result* :behavior)))
      (is (not (contains? @result* :agent)))))

  (testing "sessionEnd has active-chats-count but NOT session_end field"
    (h/reset-components!)
    (swap! (h/db*) assoc :chats {"c1" {} "c2" {}})
    (h/config! {:hooks {"test" {:type "sessionEnd"
                                :actions [{:type "shell" :shell "cat"}]}}})
    (let [result* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (fn [opts]
                                            (reset! result* (json/parse-string (:input opts) true))
                                            {:exit 0 :out "" :err nil})]
        (f.hooks/trigger-if-matches! :sessionEnd
                                     (merge (f.hooks/base-hook-data (h/db))
                                            {:active-chats-count 2})
                                     {} (h/db) (h/config)))
      (is (= 2 (:active_chats_count @result*)))
      (is (not (contains? @result* :session_end))))))

(deftest chat-hooks-test
  (testing "chatStart with resumed flag"
    (h/reset-components!)
    (h/config! {:hooks {"test" {:type "chatStart"
                                :actions [{:type "shell" :shell "cat"}]}}})
    (let [result* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (fn [opts]
                                            (reset! result* (json/parse-string (:input opts) true))
                                            {:exit 0 :out "" :err nil})]
        (f.hooks/trigger-if-matches! :chatStart
                                     (merge (f.hooks/base-hook-data (h/db))
                                            {:chat-id "new-chat"
                                             :resumed false})
                                     {} (h/db) (h/config)))
      (is (= "new-chat" (:chat_id @result*)))
      (is (false? (:resumed @result*)))))

  (testing "chatEnd with metadata but NOT session_end field"
    (h/reset-components!)
    (h/config! {:hooks {"test" {:type "chatEnd"
                                :actions [{:type "shell" :shell "cat"}]}}})
    (let [result* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (fn [opts]
                                            (reset! result* (json/parse-string (:input opts) true))
                                            {:exit 0 :out "" :err nil})]
        (f.hooks/trigger-if-matches! :chatEnd
                                     (merge (f.hooks/base-hook-data (h/db))
                                            {:chat-id "chat-1"
                                             :title "Test"
                                             :message-count 10})
                                     {} (h/db) (h/config)))
      (is (= "chat-1" (:chat_id @result*)))
      (is (= "Test" (:title @result*)))
      (is (= 10 (:message_count @result*)))
      (is (not (contains? @result* :session_end))))))

(deftest compact-hooks-test
  (testing "preCompact receives ECA-style compact fields"
    (h/reset-components!)
    (h/config! {:hooks {"test" {:type "preCompact"
                                :matcher "manual"
                                :visible false
                                :actions [{:type "shell" :shell "cat"}]}}})
    (let [input* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (fn [opts]
                                            (reset! input* (json/parse-string (:input opts) true))
                                            {:exit 0 :out "" :err nil})]
        (is (= {:blocked? false :reason nil :hook-name nil :stop-turn? false}
               (lifecycle/run-pre-compact-hooks! {:db* (h/db*)
                                                  :config (h/config)
                                                  :chat-id "chat-1"
                                                  :agent "code"
                                                  :full-model "openai/gpt"
                                                  :variant "high"}
                                                 "manual"
                                                 "keep decisions"))))
      (is (= "chat-1" (:chat_id @input*)))
      (is (= "preCompact" (:hook_type @input*)))
      (is (= "manual" (:triggered @input*)))
      (is (= "keep decisions" (:custom_instructions @input*)))
      (is (= "openai/gpt" (:full_model @input*)))
      (is (= "high" (:variant @input*)))
      (is (contains? @input* :workspaces))
      (is (= (cache-session-id (:db_cache_path @input*)) (:session_id @input*)))
      (is (= (first (:workspaces @input*)) (:cwd @input*)))
      (is (not (contains? @input* :hook_event_name)))))

  (testing "preCompact can block with continue false and stopReason"
    (h/reset-components!)
    (h/config! {:hooks {"test" {:type "preCompact"
                                :visible false
                                :actions [{:type "shell" :shell "echo"}]}}})
    (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0
                                                     :out "{\"continue\":false,\"stopReason\":\"not now\"}"
                                                     :err nil})]
      (is (= {:blocked? true :reason "not now" :hook-name "test" :stop-turn? true}
             (lifecycle/run-pre-compact-hooks! {:db* (h/db*)
                                                :config (h/config)
                                                :chat-id "chat-1"
                                                :agent "code"}
                                               "auto"
                                               "")))))

  (testing "preCompact continue false without stopReason ignores stderr"
    (h/reset-components!)
    (h/config! {:hooks {"test" {:type "preCompact"
                                :visible false
                                :actions [{:type "shell" :shell "echo"}]}}})
    (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0
                                                     :out "{\"continue\":false}"
                                                     :err "do not show"})]
      (is (= {:blocked? true :reason nil :hook-name "test" :stop-turn? true}
             (lifecycle/run-pre-compact-hooks! {:db* (h/db*)
                                                :config (h/config)
                                                :chat-id "chat-1"
                                                :agent "code"}
                                               "auto"
                                               "")))))

  (testing "preCompact exit 2 blocks compaction without user-facing reason"
    (h/reset-components!)
    (h/config! {:hooks {"test" {:type "preCompact"
                                :visible true
                                :actions [{:type "shell" :shell "exit 2"}]}}})
    (with-redefs [f.hooks/run-shell-cmd (constantly {:exit f.hooks/hook-rejection-exit-code
                                                     :out nil
                                                     :err "  blocked by policy  "})]
      ;; Exit 2 surfaces no stderr reason, but the blocking hook name is kept.
      (is (= {:blocked? true :reason nil :hook-name "test" :stop-turn? false}
             (lifecycle/run-pre-compact-hooks! {:db* (h/db*)
                                                :config (h/config)
                                                :chat-id "chat-1"
                                                :agent "code"
                                                :messenger (h/messenger)}
                                               "manual"
                                               "")))))

  (testing "preCompact exit 2 when invisible also blocks without user-facing reason"
    (h/reset-components!)
    (h/config! {:hooks {"test" {:type "preCompact"
                                :visible false
                                :actions [{:type "shell" :shell "exit 2"}]}}})
    (with-redefs [f.hooks/run-shell-cmd (constantly {:exit f.hooks/hook-rejection-exit-code
                                                     :out nil
                                                     :err "  hidden policy  "})]
      ;; Exit 2 surfaces no stderr reason, but the blocking hook name is kept.
      (is (= {:blocked? true :reason nil :hook-name "test" :stop-turn? false}
             (lifecycle/run-pre-compact-hooks! {:db* (h/db*)
                                                :config (h/config)
                                                :chat-id "chat-1"
                                                :agent "code"}
                                               "manual"
                                               "")))))

  (testing "preCompact non-zero continue false is ignored"
    (h/reset-components!)
    (h/config! {:hooks {"test" {:type "preCompact"
                                :visible false
                                :actions [{:type "shell" :shell "echo"}]}}})
    (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 1
                                                     :out "{\"continue\":false,\"stopReason\":\"ignored\"}"
                                                     :err "failed"})]
      (is (= {:blocked? false :reason nil :hook-name nil :stop-turn? false}
             (lifecycle/run-pre-compact-hooks! {:db* (h/db*)
                                                :config (h/config)
                                                :chat-id "chat-1"
                                                :agent "code"}
                                               "manual"
                                               "")))))

  (testing "postCompact additionalContext is appended after the compact marker"
    (h/reset-components!)
    (swap! (h/db*) assoc-in [:chats "chat-1" :messages]
           [{:role "compact_marker" :content {:auto? false}}
            {:role "user" :content [{:type :text :text "The conversation was compacted."}]}
            {:role "assistant" :content [{:type :text :text "later message"}]}])
    (h/config! {:hooks {"test" {:type "postCompact"
                                :visible false
                                :actions [{:type "shell" :shell "echo"}]}}})
    (let [input* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (fn [opts]
                                            (reset! input* (json/parse-string (:input opts) true))
                                            {:exit 0
                                             :out "{\"additionalContext\":\"resume snapshot\"}"
                                             :err nil})]
        (lifecycle/run-post-compact-hooks! {:db* (h/db*)
                                            :config (h/config)
                                            :chat-id "chat-1"
                                            :agent "code"
                                            :full-model "openai/gpt"
                                            :variant "low"}
                                           "manual"
                                           "summary"))
      (is (= "postCompact" (:hook_type @input*)))
      (is (= "manual" (:triggered @input*)))
      (is (= "summary" (:compact_summary @input*)))
      (is (= "openai/gpt" (:full_model @input*)))
      (is (= "low" (:variant @input*)))
      (is (= (cache-session-id (:db_cache_path @input*)) (:session_id @input*)))
      (is (= (first (:workspaces @input*)) (:cwd @input*))))
    (is (= {:role "compact_marker" :content {:auto? false}}
           (first (get-in (h/db) [:chats "chat-1" :messages]))))
    (is (match? {:role "user"
                 :content [{:type :text :text "The conversation was compacted."}
                           {:type :text :text #"resume snapshot"}]}
                (second (get-in (h/db) [:chats "chat-1" :messages])))))

  (testing "postCompact continue false returns stop-turn without losing summary"
    (h/reset-components!)
    (swap! (h/db*) assoc-in [:chats "chat-1" :messages]
           [{:role "compact_marker" :content {:auto? false}}
            {:role "user" :content [{:type :text :text "The conversation was compacted."}]}])
    (h/config! {:hooks {"test" {:type "postCompact"
                                :visible false
                                :actions [{:type "shell" :shell "echo"}]}}})
    (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0
                                                     :out "{\"continue\":false}"
                                                     :err nil})]
      (is (= {:stop-turn? true :stop-reason nil :stop-hook-name "test"}
             (lifecycle/run-post-compact-hooks! {:db* (h/db*)
                                                 :config (h/config)
                                                 :chat-id "chat-1"
                                                 :agent "code"}
                                                "auto"
                                                "summary"))))
    (is (= [{:type :text :text "The conversation was compacted."}]
           (:content (last (get-in (h/db) [:chats "chat-1" :messages]))))))

  (testing "postCompact plain stdout is NOT treated as additionalContext"
    (h/reset-components!)
    (swap! (h/db*) assoc-in [:chats "chat-1" :messages]
           [{:role "compact_marker" :content {:auto? false}}
            {:role "user" :content [{:type :text :text "The conversation was compacted."}]}])
    (h/config! {:hooks {"test" {:type "postCompact"
                                :visible false
                                :actions [{:type "shell" :shell "echo"}]}}})
    (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0
                                                     :out "plain resume snapshot"
                                                     :err nil})]
      (lifecycle/run-post-compact-hooks! {:db* (h/db*)
                                          :config (h/config)
                                          :chat-id "chat-1"
                                          :agent "code"}
                                         "manual"
                                         "summary"))
    ;; Plain stdout is NOT converted to additionalContext
    (is (= [{:type :text :text "The conversation was compacted."}]
           (:content (last (get-in (h/db) [:chats "chat-1" :messages]))))))

  (testing "postCompact blank additionalContext is treated as absent"
    (h/reset-components!)
    (swap! (h/db*) assoc-in [:chats "chat-1" :messages]
           [{:role "compact_marker" :content {:auto? false}}
            {:role "user" :content [{:type :text :text "The conversation was compacted."}]}])
    (h/config! {:hooks {"test" {:type "postCompact"
                                :visible false
                                :actions [{:type "shell" :shell "echo"}]}}})
    (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0
                                                     :out "{\"additionalContext\":\"\"}"
                                                     :err nil})]
      (is (= {:stop-turn? false :stop-reason nil :stop-hook-name nil}
             (lifecycle/run-post-compact-hooks! {:db* (h/db*)
                                                 :config (h/config)
                                                 :chat-id "chat-1"
                                                 :agent "code"}
                                                "manual"
                                                "summary"))))
    (is (= [{:type :text :text "The conversation was compacted."}]
           (:content (last (get-in (h/db) [:chats "chat-1" :messages]))))))

  (testing "postCompact warns when compact summary message is missing"
    (h/reset-components!)
    (swap! (h/db*) assoc-in [:chats "chat-1" :messages]
           [{:role "assistant" :content [{:type :text :text "no marker"}]}])
    (h/config! {:hooks {"test" {:type "postCompact"
                                :visible false
                                :actions [{:type "shell" :shell "echo"}]}}})
    (let [warnings* (atom [])]
      (with-redefs [logger/warn (fn [& args] (swap! warnings* conj args))
                    f.hooks/run-shell-cmd (constantly {:exit 0
                                                       :out "{\"additionalContext\":\"resume snapshot\"}"
                                                       :err nil})]
        (lifecycle/run-post-compact-hooks! {:db* (h/db*)
                                            :config (h/config)
                                            :chat-id "chat-1"
                                            :agent "code"}
                                           "manual"
                                           "summary"))
      (is (= [{:role "assistant" :content [{:type :text :text "no marker"}]}]
             (get-in (h/db) [:chats "chat-1" :messages])))
      (is (some #(= "Skipping postCompact additionalContext: compact summary message not found" (second %))
                @warnings*)))))

;;; postToolCall runOnError tests

(deftest posttoolcall-runonerror-test
  (testing "runOnError=false (default) skips hook on error"
    (h/reset-components!)
    (swap! (h/db*) assoc :chats {"chat-1" {:agent "code"}})
    (h/config! {:hooks {"test" {:type "postToolCall"
                                :actions [{:type "shell" :shell "echo test"}]}}})
    (let [ran?* (atom false)]
      (with-redefs [f.hooks/run-shell-cmd (fn [_] (reset! ran?* true) {:exit 0 :out "" :err nil})]
        (f.hooks/trigger-if-matches! :postToolCall
                                     (merge (f.hooks/chat-hook-data (h/db) {:chat-id "chat-1" :agent "code"})
                                            {:tool-name "tool" :server "eca" :error true})
                                     {} (h/db) (h/config)))
      (is (false? @ran?*))))

  (testing "runOnError=true runs hook on error"
    (h/reset-components!)
    (swap! (h/db*) assoc :chats {"chat-1" {:agent "code"}})
    (h/config! {:hooks {"test" {:type "postToolCall"
                                :runOnError true
                                :actions [{:type "shell" :shell "echo test"}]}}})
    (let [ran?* (atom false)]
      (with-redefs [f.hooks/run-shell-cmd (fn [_] (reset! ran?* true) {:exit 0 :out "" :err nil})]
        (f.hooks/trigger-if-matches! :postToolCall
                                     (merge (f.hooks/chat-hook-data (h/db) {:chat-id "chat-1" :agent "code"})
                                            {:tool-name "tool" :server "eca" :error true})
                                     {} (h/db) (h/config)))
      (is (true? @ran?*)))))

(deftest subagent-post-request-test
  (testing "subagentPostRequest hook triggers with parent_chat_id and response"
    (h/reset-components!)
    (swap! (h/db*) assoc :chats {"sub-1" {:agent "explorer"}})
    (h/config! {:hooks {"test" {:type "subagentPostRequest"
                                :actions [{:type "shell" :shell "cat"}]}}})
    (let [result* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (fn [opts]
                                            (reset! result* (json/parse-string (:input opts) true))
                                            {:exit 0 :out "" :err nil})]
        (f.hooks/trigger-if-matches! :subagentPostRequest
                                     (merge (f.hooks/chat-hook-data (h/db) {:chat-id "sub-1" :agent "explorer"})
                                            {:response "found the answer"
                                             :parent-chat-id "parent-1"})
                                     {} (h/db) (h/config)))
      (is (= "sub-1" (:chat_id @result*)))
      (is (= "explorer" (:agent @result*)))
      (is (= "parent-1" (:parent_chat_id @result*)))
      (is (= "found the answer" (:response @result*)))
      (is (not (contains? @result* :prompt)))))

  (testing "postRequest does not trigger for subagentPostRequest hook type"
    (h/reset-components!)
    (h/config! {:hooks {"test" {:type "postRequest"
                                :actions [{:type "shell" :shell "echo hey"}]}}})
    (let [ran?* (atom false)]
      (with-redefs [f.hooks/run-shell-cmd (fn [_] (reset! ran?* true) {:exit 0 :out "" :err nil})]
        (f.hooks/trigger-if-matches! :subagentPostRequest
                                     {:prompt "task"}
                                     {} (h/db) (h/config)))
      (is (false? @ran?*))))

  (testing "subagentPostRequest does not trigger for postRequest hook type"
    (h/reset-components!)
    (h/config! {:hooks {"test" {:type "subagentPostRequest"
                                :actions [{:type "shell" :shell "echo hey"}]}}})
    (let [ran?* (atom false)]
      (with-redefs [f.hooks/run-shell-cmd (fn [_] (reset! ran?* true) {:exit 0 :out "" :err nil})]
        (f.hooks/trigger-if-matches! :postRequest
                                     {:prompt "task"}
                                     {} (h/db) (h/config)))
      (is (false? @ran?*)))))

;;; Matcher map tests (map form with argsMatchers)

(deftest matcher-map-normalization-test
  (testing "JSON-shaped matcher config works after normalization"
    (h/reset-components!)
    ;; This intentionally targets config normalization: hook matcher selector keys
    ;; must stay strings, otherwise selectors containing / lose their namespace via `name`.
    (h/config! (#'config/normalize-fields
                @#'config/normalization-rules
                {"hooks" {"spec-check" {"type" "preToolCall"
                                        "matcher" {"vendor/my-mcp__write_file" {"argsMatchers" {"path" [".*\\.allium$"]}}}
                                        "actions" [{"type" "shell" "shell" "echo ok"}]}}}))
    (let [result* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "ok" :err nil})]
        (f.hooks/trigger-if-matches! :preToolCall
                                     {:server "vendor/my-mcp" :tool-name "write_file"
                                      :tool-input {"path" "/foo/bar/spec.allium"}}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      ;; Hook name is stringified by trigger-if-matches! (keywordized config key
      ;; -> string), so downstream string-building never leaks a ':' prefix.
      (is (match? {:name "spec-check"} @result*)))))

(deftest matcher-map-test
  (testing "map matcher with argsMatchers filters by tool argument"
    (h/reset-components!)
    (h/config! {:hooks {"spec-check" {:type "preToolCall"
                                      ;; Simulates config after normalize-fields: matcher and argsMatchers keys may be keywords.
                                      :matcher {:eca__write_file {:argsMatchers {:path [".*\\.allium$"]}}}
                                      :actions [{:type "shell" :shell "echo ok"}]}}})
    (let [result* (atom nil)]
      ;; Should NOT match — wrong extension, with real provider-style string keys.
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "ok" :err nil})]
        (f.hooks/trigger-if-matches! :preToolCall
                                     {:server "eca" :tool-name "write_file"
                                      :tool-input {"path" "/foo/bar/spec.md"}}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (nil? @result*))

      ;; Should NOT match — required arg is missing.
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "ok" :err nil})]
        (f.hooks/trigger-if-matches! :preToolCall
                                     {:server "eca" :tool-name "write_file"
                                      :tool-input {"source" "/foo/bar/spec.allium"}}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (nil? @result*))

      ;; Should match — .allium extension, with real provider-style string keys.
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "ok" :err nil})]
        (f.hooks/trigger-if-matches! :preToolCall
                                     {:server "eca" :tool-name "write_file"
                                      :tool-input {"path" "/foo/bar/spec.allium"}}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (match? {:name "spec-check"} @result*))))

  (testing "map matcher uses toolApproval-like selectors, not regex"
    (h/reset-components!)
    (h/config! {:hooks {"native-write" {:type "preToolCall"
                                        :matcher {"write_file" {}}
                                        :actions [{:type "shell" :shell "echo ok"}]}
                        "server-hook" {:type "preToolCall"
                                       :matcher {"my-mcp" {}}
                                       :actions [{:type "shell" :shell "echo ok"}]}
                        "regex-looking" {:type "preToolCall"
                                         :matcher {".*write_file" {}}
                                         :actions [{:type "shell" :shell "echo ok"}]}}})
    (let [native-tools [{:origin :native :name "write_file"}]
          result* (atom nil)]
      ;; Native short selector matches only the ECA native tool.
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "ok" :err nil})]
        (f.hooks/trigger-if-matches! :preToolCall
                                     {:server "eca" :tool-name "write_file" :tool-input {}}
                                     {:on-after-action (set-action-payload result*)
                                      :native-tools native-tools}
                                     (h/db) (h/config)))
      (is (match? {:name "native-write"} @result*))

      ;; The same short selector must not match an MCP tool with the same name.
      (reset! result* nil)
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "ok" :err nil})]
        (f.hooks/trigger-if-matches! :preToolCall
                                     {:server "my-mcp" :tool-name "write_file" :tool-input {}}
                                     {:on-after-action (set-action-payload result*)
                                      :native-tools native-tools}
                                     (h/db) (h/config)))
      (is (match? {:name "server-hook"} @result*))

      ;; Map keys are selectors, not regexes.
      (reset! result* nil)
      (h/reset-components!)
      (h/config! {:hooks {"regex-looking" {:type "preToolCall"
                                           :matcher {".*write_file" {}}
                                           :actions [{:type "shell" :shell "echo ok"}]}}})
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "ok" :err nil})]
        (f.hooks/trigger-if-matches! :preToolCall
                                     {:server "eca" :tool-name "write_file" :tool-input {}}
                                     {:on-after-action (set-action-payload result*)
                                      :native-tools native-tools}
                                     (h/db) (h/config)))
      (is (nil? @result*))))

  (testing "postToolCall map matcher supports native short selector"
    (h/reset-components!)
    (h/config! {:hooks {"native-write" {:type "postToolCall"
                                        :matcher {"write_file" {}}
                                        :actions [{:type "shell" :shell "echo ok"}]}}})
    (let [result* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "ok" :err nil})]
        (f.hooks/trigger-if-matches! :postToolCall
                                     {:server "eca" :tool-name "write_file" :tool-input {}}
                                     {:on-after-action (set-action-payload result*)
                                      :native-tools [{:origin :native :name "write_file"}]}
                                     (h/db) (h/config)))
      (is (match? {:name "native-write"} @result*))))

  (testing "invalid regex matchers are skipped"
    (h/reset-components!)
    (h/config! {:hooks {"bad-legacy" {:type "preToolCall"
                                      :matcher "["
                                      :actions [{:type "shell" :shell "echo ok"}]}
                        "bad-args" {:type "preToolCall"
                                    :matcher {"eca__write_file" {:argsMatchers {"path" ["["]}}}
                                    :actions [{:type "shell" :shell "echo ok"}]}}})
    (let [result* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "ok" :err nil})]
        (f.hooks/trigger-if-matches! :preToolCall
                                     {:server "eca" :tool-name "write_file"
                                      :tool-input {"path" "/foo/spec.allium"}}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (nil? @result*))))

  (testing "map matcher with empty map value matches without arg filter"
    (h/reset-components!)
    (h/config! {:hooks {"spec-check" {:type "preToolCall"
                                      :matcher {"eca__write_file" {}}
                                      :actions [{:type "shell" :shell "echo ok"}]}}})
    (let [result* (atom nil)]
      ;; Should match — empty map = no arg filtering
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "ok" :err nil})]
        (f.hooks/trigger-if-matches! :preToolCall
                                     {:server "eca" :tool-name "write_file"
                                      :tool-input {"path" "/any/path.txt"}}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (match? {:name "spec-check"} @result*))))

  (testing "map matcher with multiple tools — each with different argsMatchers"
    (h/reset-components!)
    (h/config! {:hooks {"spec-check" {:type "postToolCall"
                                      :matcher {"eca__write_file" {:argsMatchers {"path" [".*\\.allium$"]}}
                                                "eca__edit_file" {:argsMatchers {"path" [".*\\.allium$"]}}
                                                "eca__move_file" {}}
                                      :actions [{:type "shell" :shell "echo ok"}]}}})
    (let [result* (atom nil)]
      ;; write_file with .allium → match
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "ok" :err nil})]
        (f.hooks/trigger-if-matches! :postToolCall
                                     {:server "eca" :tool-name "write_file"
                                      :tool-input {"path" "/foo/spec.allium"}}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (match? {:name "spec-check"} @result*))

      ;; write_file with .txt → no match (argsMatchers filters it out)
      (reset! result* nil)
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "ok" :err nil})]
        (f.hooks/trigger-if-matches! :postToolCall
                                     {:server "eca" :tool-name "write_file"
                                      :tool-input {"path" "/foo/spec.txt"}}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (nil? @result*))

      ;; move_file with any path → match (empty map = no filter)
      (reset! result* nil)
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "ok" :err nil})]
        (f.hooks/trigger-if-matches! :postToolCall
                                     {:server "eca" :tool-name "move_file"
                                      :tool-input {"source" "/foo.txt" "destination" "/bar.txt"}}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (match? {:name "spec-check"} @result*))))

  (testing "map matcher does not match when tool name not in map"
    (h/reset-components!)
    (h/config! {:hooks {"spec-check" {:type "preToolCall"
                                      :matcher {"eca__write_file" {:argsMatchers {"path" [".*\\.allium$"]}}}
                                      :actions [{:type "shell" :shell "echo ok"}]}}})
    (let [result* (atom nil)]
      ;; read_file is NOT in matcher map
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "ok" :err nil})]
        (f.hooks/trigger-if-matches! :preToolCall
                                     {:server "eca" :tool-name "read_file"
                                      :tool-input {:path "/foo/spec.allium"}}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (nil? @result*))))

  (testing "string matcher still works (backward compatibility)"
    (h/reset-components!)
    (h/config! {:hooks {"my-hook" {:type "preToolCall"
                                   :matcher "eca__write_file|eca__edit_file"
                                   :actions [{:type "shell" :shell "echo ok"}]}}})
    (let [result* (atom nil)]
      ;; Should match
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "ok" :err nil})]
        (f.hooks/trigger-if-matches! :preToolCall
                                     {:server "eca" :tool-name "write_file"
                                      :tool-input {:path "/any"}}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (match? {:name "my-hook"} @result*))))

  (testing "nil matcher matches all (backward compatibility)"
    (h/reset-components!)
    (h/config! {:hooks {"my-hook" {:type "preToolCall"
                                   :actions [{:type "shell" :shell "echo ok"}]}}})
    (let [result* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "ok" :err nil})]
        (f.hooks/trigger-if-matches! :preToolCall
                                     {:server "eca" :tool-name "anything"
                                      :tool-input {:path "/any"}}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (match? {:name "my-hook"} @result*))))

  (testing "argsMatchers with multiple patterns — OR within arg"
    (h/reset-components!)
    (h/config! {:hooks {"my-hook" {:type "preToolCall"
                                   :matcher {"eca__write_file" {:argsMatchers {"path" [".*\\.allium$" ".*\\.spec$"]}}}
                                   :actions [{:type "shell" :shell "echo ok"}]}}})
    (let [result* (atom nil)]
      ;; .allium → match
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "ok" :err nil})]
        (f.hooks/trigger-if-matches! :preToolCall
                                     {:server "eca" :tool-name "write_file"
                                      :tool-input {"path" "/foo/bar.allium"}}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (match? {:name "my-hook"} @result*))

      ;; .spec → match
      (reset! result* nil)
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "ok" :err nil})]
        (f.hooks/trigger-if-matches! :preToolCall
                                     {:server "eca" :tool-name "write_file"
                                      :tool-input {"path" "/foo/bar.spec"}}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (match? {:name "my-hook"} @result*))

      ;; .txt → no match
      (reset! result* nil)
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "ok" :err nil})]
        (f.hooks/trigger-if-matches! :preToolCall
                                     {:server "eca" :tool-name "write_file"
                                      :tool-input {"path" "/foo/bar.txt"}}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (nil? @result*))))

  (testing "argsMatchers with multiple args — AND across args"
    (h/reset-components!)
    (h/config! {:hooks {"my-hook" {:type "preToolCall"
                                   :matcher {"eca__write_file" {:argsMatchers {"path" [".*\\.allium$"]
                                                                               "original_content" [".*foo.*"]}}}
                                   :actions [{:type "shell" :shell "echo ok"}]}}})
    (let [result* (atom nil)]
      ;; Both args match → match
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "ok" :err nil})]
        (f.hooks/trigger-if-matches! :preToolCall
                                     {:server "eca" :tool-name "write_file"
                                      :tool-input {"path" "/bar.allium"
                                                   "original_content" "foo bar"}}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (match? {:name "my-hook"} @result*))

      ;; Only path matches, original_content doesn't → no match
      (reset! result* nil)
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "ok" :err nil})]
        (f.hooks/trigger-if-matches! :preToolCall
                                     {:server "eca" :tool-name "write_file"
                                      :tool-input {"path" "/bar.allium"
                                                   "original_content" "no match here"}}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (nil? @result*)))))

;;; New tests for hook consistency improvements

(deftest json-only-additional-context-test
  (testing "preRequest plain stdout is NOT treated as additionalContext"
    (h/reset-components!)
    (h/config! {:hooks {"test" {:type "preRequest"
                                :actions [{:type "shell" :shell "echo plain"}]}}})
    (let [result* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "plain text" :err nil})]
        (f.hooks/trigger-if-matches! :preRequest
                                     {:foo "1"}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      ;; Raw output is present but parsed is nil
      (is (= "plain text" (:raw-output @result*)))
      (is (nil? (:parsed @result*)))))

  (testing "preRequest JSON additionalContext works on exit 0"
    (h/reset-components!)
    (h/config! {:hooks {"test" {:type "preRequest"
                                :actions [{:type "shell" :shell "echo"}]}}})
    (let [result* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0
                                                       :out "{\"additionalContext\":\"ctx\"}"
                                                       :err nil})]
        (f.hooks/trigger-if-matches! :preRequest
                                     {:foo "1"}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (= "ctx" (get-in @result* [:parsed "additionalContext"]))))))

(deftest prerequest-block-does-not-fire-postrequest-test
  (testing "preRequest exit 2 blocks the prompt but not remaining preRequest hooks"
    (h/reset-components!)
    (h/config! {:hooks {"a-block" {:type "preRequest"
                                   :actions [{:type "shell" :shell "exit 2"}]}
                        "b-after" {:type "preRequest"
                                   :actions [{:type "shell" :shell "echo after"}]}
                        "c-post" {:type "postRequest"
                                  :actions [{:type "shell" :shell "echo post"}]}}})
    (let [calls* (atom [])
          inputs* (atom [])]
      (with-redefs [f.hooks/run-shell-cmd (fn [{:keys [input]}]
                                            (let [{:keys [hook_name] :as parsed-input} (json/parse-string input true)
                                                  hook-name hook_name]
                                              (swap! inputs* conj parsed-input)
                                              (swap! calls* conj hook-name)
                                              (if (= "a-block" hook-name)
                                                {:exit f.hooks/hook-rejection-exit-code
                                                 :out nil
                                                 :err "blocked"}
                                                {:exit 0
                                                 :out "after"
                                                 :err nil})))]
        (#'f.chat/prompt-messages!
         [{:role "user" :content [{:type :text :text "hello"}]}]
         :prompt-message
         {:db* (h/db*)
          :config (h/config)
          :chat-id "chat-1"
          :agent "code"
          :message "hello"
          :messenger (h/messenger)
          :metrics (h/metrics)
          :full-model "openai/gpt"
          :variant "high"}))
      (is (= ["a-block" "b-after"] @calls*))
      (is (every? #(= "openai/gpt" (:full_model %)) @inputs*))
      (is (every? #(= "high" (:variant %)) @inputs*))
      (is (every? #(not (contains? % :all_messages)) @inputs*))))

  (testing "preRequest exit 2 does not run command finish side effects and still shows reason when invisible"
    (h/reset-components!)
    (h/config! {:hooks {"a-block" {:type "preRequest"
                                   :visible false
                                   :actions [{:type "shell" :shell "exit 2"}]}}})
    (let [side-effect-ran?* (atom false)]
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit f.hooks/hook-rejection-exit-code
                                                       :out nil
                                                       :err "blocked invisibly"})]
        (#'f.chat/prompt-messages!
         [{:role "user" :content [{:type :text :text "hello"}]}]
         :eca-command
         {:db* (h/db*)
          :config (h/config)
          :chat-id "chat-1"
          :agent "code"
          :message "/custom"
          :messenger (h/messenger)
          :metrics (h/metrics)
          :full-model "openai/gpt"
          :on-finished-side-effect #(reset! side-effect-ran?* true)}))
      (is (false? @side-effect-ran?*))
      (is (some #(= {:chat-id "chat-1"
                     :role :system
                     :content {:type :text :text "Request blocked by hook 'a-block': blocked invisibly"}}
                    %)
                (:chat-content-received (h/messages)))))))

(deftest start-hooks-test
  (testing "chatStart receives documented chat fields"
    (h/reset-components!)
    (h/config! {:hooks {"start" {:type "chatStart"
                                 :visible false
                                 :actions [{:type "shell" :shell "cat"}]}}})
    (let [input* (atom nil)]
      (with-redefs [config/all (constantly (h/config))
                    config/await-plugins-resolved! (fn [] nil)
                    f.hooks/run-shell-cmd (fn [{:keys [input]}]
                                            (reset! input* (json/parse-string input true))
                                            {:exit 0 :out "" :err nil})]
        (is (= {:stop-turn? false}
               (#'f.chat/run-start-hooks! {:db* (h/db*) :chat-id "chat-1" :agent "code"
                                           :messenger (h/messenger) :variant "high"}
                                          (h/db) false "openai/gpt"))))
      (is (match? {:chat_id "chat-1"
                   :agent "code"
                   :behavior "code"
                   :full_model "openai/gpt"
                   :variant "high"
                   :resumed false}
                  @input*))))

  (testing "chatStart continue false returns current-turn stop state"
    (h/reset-components!)
    (h/config! {:hooks {"start" {:type "chatStart"
                                 :visible false
                                 :actions [{:type "shell" :shell "echo"}]}}})
    (with-redefs [config/all (constantly (h/config))
                  config/await-plugins-resolved! (fn [] nil)
                  f.hooks/run-shell-cmd (constantly {:exit 0
                                                     :out "{\"continue\":false,\"stopReason\":\"not now\"}"
                                                     :err nil})]
      (is (= {:stop-turn? true :stop-reason "not now" :stop-hook-name "start"}
             (#'f.chat/run-start-hooks! {:db* (h/db*) :chat-id "chat-1" :agent "code"
                                         :messenger (h/messenger)}
                                        (h/db) false "openai/gpt")))))

  (testing "prompt returns resolved model when chatStart stops without explicit model"
    (h/reset-components!)
    (h/config! {:defaultModel "openai/gpt"
                :hooks {"start" {:type "chatStart"
                                 :visible false
                                 :actions [{:type "shell" :shell "echo"}]}}})
    (swap! (h/db*) assoc :models {"openai/gpt" {}})
    (with-redefs [config/all (constantly (h/config))
                  config/await-plugins-resolved! (fn [] nil)
                  f.hooks/run-shell-cmd (constantly {:exit 0
                                                     :out "{\"continue\":false}"
                                                     :err nil})]
      (is (match? {:chat-id "chat-1"
                   :model "openai/gpt"
                   :status :prompting}
                  (#'f.chat/prompt*
                   {}
                   {:db* (h/db*)
                    :config (h/config)
                    :contexts []
                    :chat-id "chat-1"
                    :agent "code"
                    :agent-config {}
                    :message "hello"
                    :messenger (h/messenger)
                    :metrics (h/metrics)}))))))

(deftest pretoolcall-continue-false-test
  (testing "preToolCall continue false stops the turn without executing the tool"
    (h/reset-components!)
    (h/config! {:hooks {"stop-tool" {:type "preToolCall"
                                     :actions [{:type "shell" :shell "echo"}]}}
                :toolCall {:approval {:byDefault "allow"}}})
    (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0
                                                     :out "{\"continue\":false,\"stopReason\":\"stop now\"}"
                                                     :err nil})]
      (is (match? {:decision :deny
                   :tool-call-rejected-by-hook? false
                   :tool-call-blocked-by-hook? true
                   :stop-turn? true
                   :stop-reason "stop now"
                   :stop-hook-name "stop-tool"
                   ;; Q3: stopReason is user-only; the LLM-visible reason text is neutral.
                   :reason {:code :hook-stopped
                            :text "Tool call stopped by hook"}}
                  (#'tc/decide-tool-call-action
                   {:full-name "eca__shell_command"
                    :arguments {"command" "echo hi"}}
                   (f.tools/all-tools "chat-1" "code" (h/db) (h/config))
                   (h/db)
                   (h/config)
                   "code"
                   "chat-1"))))))

(deftest pretoolcall-structured-denial-test
  (testing "exit 0 + approval deny + additionalContext: LLM-visible denial"
    (h/reset-components!)
    (h/config! {:hooks {"test" {:type "preToolCall"
                                :actions [{:type "shell" :shell "echo"}]}}})
    (let [result* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0
                                                       :out "{\"approval\":\"deny\",\"additionalContext\":\"You must never access /etc/priv\"}"
                                                       :err nil})]
        (f.hooks/trigger-if-matches! :preToolCall
                                     {:server "eca" :tool-name "read"}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      (is (= "deny" (get-in @result* [:parsed "approval"])))
      (is (= "You must never access /etc/priv" (get-in @result* [:parsed "additionalContext"])))))

  (testing "exit 2 rejection keeps agent turn continuable and uses stderr as LLM reason"
    (let [state (#'tc/process-pre-tool-call-hook-result
                 {:hook-results []
                  :approval-override nil
                  :tool-call-rejected-by-hook? false
                  :tool-call-rejection-reason nil
                  :stop-reason nil
                  :stop-hook-name nil
                  :stop-turn? false}
                 {:exit f.hooks/hook-rejection-exit-code
                  :raw-output "{\"additionalContext\":\"should not reach LLM\",\"continue\":false}"
                  :raw-error "Security block"
                  :parsed {"additionalContext" "should not reach LLM"
                           "continue" false}})]
      (is (true? (:tool-call-rejected-by-hook? state)))
      (is (= "Security block" (:tool-call-rejection-reason state)))
      (is (false? (:stop-turn? state)))
      (is (nil? (:stop-reason state)))))

  (testing "approval deny does not treat stopReason as hook stop reason"
    (let [state (#'tc/process-pre-tool-call-hook-result
                 {:hook-results []
                  :approval-override nil
                  :tool-call-rejected-by-hook? false
                  :tool-call-rejection-reason nil
                  :stop-reason nil
                  :stop-hook-name nil
                  :stop-turn? false}
                 {:exit 0
                  :raw-output "{\"approval\":\"deny\",\"stopReason\":\"user-only\"}"
                  :raw-error nil
                  :parsed {"approval" "deny"
                           "stopReason" "user-only"}})]
      (is (true? (:tool-call-rejected-by-hook? state)))
      (is (nil? (:stop-reason state)))))

  (testing "non-zero JSON approval deny is ignored"
    (let [state (#'tc/process-pre-tool-call-hook-result
                 {:hook-results []
                  :approval-override nil
                  :tool-call-rejected-by-hook? false
                  :tool-call-rejection-reason nil
                  :stop-reason nil
                  :stop-hook-name nil
                  :stop-turn? false}
                 {:exit 1
                  :raw-output "{\"approval\":\"deny\",\"additionalContext\":\"ignored\"}"
                  :raw-error "failed"
                  :parsed {"approval" "deny"
                           "additionalContext" "ignored"}})]
      (is (false? (:tool-call-rejected-by-hook? state)))
      (is (nil? (:approval-override state))))))

(deftest posttoolcall-replaced-output-test
  (letfn [(setup! []
            (h/reset-components!)
            (swap! (h/db*) assoc-in [:chats "chat-1"]
                   {:agent "code"
                    :messages [{:role "tool_call_output"
                                :content {:id "tool-1"
                                          :output {:contents [{:type :text :text "original output"}]}}}]
                    :tool-calls {"tool-1" {:name "shell_command"
                                           :server "eca"
                                           :arguments {}}}})
            (h/config! {:hooks {"sanitize" {:type "postToolCall"
                                            :visible false
                                            :actions [{:type "shell" :shell "echo"}]}}}))
          (run-hook! [result]
            (with-redefs [f.hooks/run-shell-cmd (constantly result)]
              (#'tc/run-post-tool-call-hooks!
               (h/db*)
               {:db* (h/db*)
                :config (h/config)
                :chat-id "chat-1"
                :agent "code"}
               "tool-1"
               {:outputs [{:type :text :text "original output"}]})))
          (tool-output []
            (get-in (h/db) [:chats "chat-1" :messages 0 :content :output :contents]))]
    (testing "replacedOutput replaces tool result on exit 0"
      (setup!)
      (run-hook! {:exit 0
                  :out "{\"replacedOutput\":\"sanitized content\"}"
                  :err nil})
      (is (= [{:type :text :text "sanitized content"}]
             (tool-output))))

    (testing "empty replacedOutput hides tool result with empty content"
      (setup!)
      (run-hook! {:exit 0
                  :out "{\"replacedOutput\":\"\"}"
                  :err nil})
      (is (= [{:type :text :text ""}]
             (tool-output))))

    (testing "replacedOutput and continue false are ignored on non-zero exit other than 2"
      (setup!)
      (let [result (run-hook! {:exit 1
                                :out "{\"replacedOutput\":\"sanitized content\",\"continue\":false}"
                                :err "failed"})]
        (is (= [{:type :text :text "original output"}]
               (tool-output)))
        (is (not (:stop-turn? result)))))

    (testing "exit 2 replaces tool result with stderr and ignores stdout JSON"
      (setup!)
      (run-hook! {:exit f.hooks/hook-rejection-exit-code
                  :out "{\"replacedOutput\":\"ignored stdout\"}"
                  :err "redacted by policy"})
      (is (= [{:type :text :text "redacted by policy"}]
             (tool-output))))

    (testing "exit 2 with empty stderr replaces tool result with placeholder"
      (setup!)
      (run-hook! {:exit f.hooks/hook-rejection-exit-code
                  :out "{\"replacedOutput\":\"ignored stdout\"}"
                  :err nil})
      (is (= [{:type :text :text "[Tool output hidden by hook]"}]
             (tool-output))))

    (testing "continue false is current-turn control state only and preserves stopReason"
      (setup!)
      (let [result (run-hook! {:exit 0
                                :out "{\"continue\":false,\"stopReason\":\"pause here\"}"
                                :err nil})]
        (is (:stop-turn? result))
        (is (= "pause here" (:stop-reason result)))
        (is (nil? (get-in (h/db) [:chats "chat-1" :tool-calls "tool-1" :post-tool-call-stop?])))))

    (testing "blank additionalContext and stopReason are treated as absent"
      (setup!)
      (let [result (run-hook! {:exit 0
                                :out "{\"additionalContext\":\"   \",\"continue\":false,\"stopReason\":\"\"}"
                                :err nil})]
        (is (:stop-turn? result))
        (is (nil? (:stop-reason result)))
        (is (= [{:type :text :text "original output"}]
               (tool-output)))))

    (testing "non-string replacedOutput is ignored"
      (setup!)
      (run-hook! {:exit 0
                  :out "{\"replacedOutput\":{\"text\":\"bad\"}}"
                  :err nil})
      (is (= [{:type :text :text "original output"}]
             (tool-output))))))

(deftest compact-matcher-exact-string-test
  (testing "compact matcher uses exact string match, not regex"
    (h/reset-components!)
    (h/config! {:hooks {"manual-regex" {:type "preCompact"
                                        :matcher "man.*"
                                        :actions [{:type "shell" :shell "echo hey"}]}}})
    (let [result* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0 :out "hey" :err nil})]
        (f.hooks/trigger-if-matches! :preCompact
                                     {:triggered "manual"}
                                     {:on-after-action (set-action-payload result*)}
                                     (h/db) (h/config)))
      ;; "man.*" should NOT match "manual" since matchers are exact strings now
      (is (nil? @result*)))))

(deftest hook-notification-action-type-test
  (testing "hookActionStarted includes action-type"
    (let [sent* (atom [])]
      (with-redefs [lifecycle/send-content! (fn [_ role content]
                                              (swap! sent* conj {:role role :content content}))]
        (lifecycle/notify-before-hook-action!
         {}
         {:id "action-1"
          :name "my-hook"
          :type "shell"
          :visible? true}))
      (is (= [{:role :system
               :content {:type :hookActionStarted
                         :action-type "shell"
                         :name "my-hook"
                         :id "action-1"}}]
             @sent*)))))

(deftest matcher-warning-policy-test
  (testing "unsupported matcher type warns"
    (h/reset-components!)
    (h/config! {:hooks {"bad" {:type "preToolCall"
                               :matcher 123
                               :actions [{:type "shell" :shell "echo"}]}}})
    (let [warnings* (atom [])]
      (with-redefs [logger/warn (fn [& args] (swap! warnings* conj args))
                    f.hooks/run-shell-cmd (constantly {:exit 0 :out "" :err nil})]
        (f.hooks/trigger-if-matches! :preToolCall
                                     {:server "eca" :tool-name "write_file" :tool-input {}}
                                     {}
                                     (h/db) (h/config)))
      (is (some #(= "Unsupported matcher type, ignoring" (second %)) @warnings*))))

  (testing "argsMatchers not a map warns and matches nothing"
    (h/reset-components!)
    (h/config! {:hooks {"bad" {:type "preToolCall"
                               :matcher {"eca__write_file" {:argsMatchers "not-a-map"}}
                               :actions [{:type "shell" :shell "echo"}]}}})
    (let [warnings* (atom [])
          calls* (atom [])]
      (with-redefs [logger/warn (fn [& args] (swap! warnings* conj args))
                    f.hooks/run-shell-cmd (fn [opts]
                                            (swap! calls* conj opts)
                                            {:exit 0 :out "" :err nil})]
        (f.hooks/trigger-if-matches! :preToolCall
                                     {:server "eca" :tool-name "write_file" :tool-input {}}
                                     {}
                                     (h/db) (h/config)))
      (is (some #(= "argsMatchers must be a map, ignoring matcher entry" (second %)) @warnings*))
      (is (empty? @calls*)))))

;;; Regression guards — negative contracts for intentional behavioral changes

(deftest subagent-triggers-subagentStart-not-chatStart-test
  (testing "subagent chat fires subagentStart, not chatStart"
    (h/reset-components!)
    (swap! (h/db*) assoc :chats {"sub-1" {:agent "explorer"
                                           :subagent {:max-steps 10}}})
    (h/config! {:hooks {"cs" {:type "chatStart"
                               :actions [{:type "shell" :shell "echo cs"}]}
                        "ss" {:type "subagentStart"
                               :actions [{:type "shell" :shell "echo ss"}]}}})
    (let [fired-types* (atom [])]
      (with-redefs [config/all (constantly (h/config))
                    config/await-plugins-resolved! (fn [] nil)
                    f.hooks/run-shell-cmd (fn [{:keys [input]}]
                                            (let [data (json/parse-string input true)]
                                              (swap! fired-types* conj (:hook_type data)))
                                            {:exit 0 :out "" :err nil})]
        (#'f.chat/run-start-hooks! {:db* (h/db*) :chat-id "sub-1" :agent "explorer"
                                    :messenger (h/messenger)}
                                   (h/db) false "openai/gpt"))
      (is (= ["subagentStart"] @fired-types*)))))

(deftest subagent-finish-fires-both-post-request-hooks-test
  (testing "subagent finish fires postRequest then subagentPostRequest"
    (h/reset-components!)
    (swap! (h/db*) assoc :chats {"sub-1" {:agent "explorer"
                                           :subagent {:max-steps 10}
                                           :parent-chat-id "parent-1"}})
    (h/config! {:hooks {"pr" {:type "postRequest"
                               :actions [{:type "shell" :shell "echo pr"}]}
                        "spr" {:type "subagentPostRequest"
                                :actions [{:type "shell" :shell "echo spr"}]}}})
    (let [fired-types* (atom [])]
      (with-redefs [f.hooks/run-shell-cmd (fn [{:keys [input]}]
                                            (let [data (json/parse-string input true)]
                                              (swap! fired-types* conj (:hook_type data)))
                                            {:exit 0 :out "" :err nil})]
        (lifecycle/finish-chat-prompt! :idle {:db* (h/db*)
                                              :config (h/config)
                                              :chat-id "sub-1"
                                              :agent "explorer"
                                              :messenger (h/messenger)
                                              :metrics (h/metrics)}))
      (is (= ["postRequest" "subagentPostRequest"] @fired-types*)))))

(deftest subagent-postrequest-skipped-when-postrequest-stops-test
  (testing "a successful postRequest continue:false stops the turn and skips subagentPostRequest"
    (h/reset-components!)
    (swap! (h/db*) assoc :chats {"sub-1" {:agent "explorer"
                                          :subagent {:max-steps 10}
                                          :parent-chat-id "parent-1"}})
    (h/config! {:hooks {"pr" {:type "postRequest"
                              :actions [{:type "shell" :shell "echo stop"}]}
                        "spr" {:type "subagentPostRequest"
                               :actions [{:type "shell" :shell "echo spr"}]}}})
    (let [fired-types* (atom [])]
      (with-redefs [f.hooks/run-shell-cmd (fn [{:keys [input]}]
                                            (let [data (json/parse-string input true)]
                                              (swap! fired-types* conj (:hook_type data)))
                                            ;; postRequest returns a successful continue:false
                                            {:exit 0
                                             :out "{\"continue\":false,\"stopReason\":\"halt\"}"
                                             :err nil})]
        (lifecycle/finish-chat-prompt! :idle {:db* (h/db*)
                                              :config (h/config)
                                              :chat-id "sub-1"
                                              :parent-chat-id "parent-1"
                                              :agent "explorer"
                                              :messenger (h/messenger)
                                              :metrics (h/metrics)}))
      ;; Only postRequest ran; subagentPostRequest was skipped.
      (is (= ["postRequest"] @fired-types*))
      ;; The turn-stop message is still surfaced on the subagent chat. The binding
      ;; (chat-id/parent-chat-id) is the contract here; the exact wording is covered
      ;; by lifecycle/turn-stopped-by-hook-message-test.
      (is (some (fn [m]
                  (and (= "sub-1" (:chat-id m))
                       (= "parent-1" (:parent-chat-id m))
                       (= :system (:role m))
                       (string/includes? (str (get-in m [:content :text])) "halt")))
                (:chat-content-received (h/messages)))))))

(deftest prerequest-plain-stdout-not-additional-context-test
  (testing "update-pre-request-state ignores raw-output as additionalContext"
    (let [state {:final-prompt "hello"
                 :additional-contexts []
                 :stop-turn? false
                 :blocked? false
                 :stop-reason nil}
          ;; Simulates a hook that outputs plain text (parsed=nil) on exit 0
          result {:parsed nil :exit 0}
          new-state (#'f.chat/update-pre-request-state state result "my-hook")]
      (is (empty? (:additional-contexts new-state)))))

  (testing "blank replacedPrompt/additionalContext/stopReason are treated as absent"
    (let [state {:final-prompt "hello"
                 :additional-contexts []
                 :stop-turn? false
                 :blocked? false
                 :blocked-reasons []
                 :stop-reason nil
                 :stop-hook-name nil}
          result {:parsed {"replacedPrompt" ""
                           "additionalContext" "   "
                           "continue" false
                           "stopReason" ""}
                  :exit 0}
          new-state (#'f.chat/update-pre-request-state state result "my-hook")]
      (is (= "hello" (:final-prompt new-state)))
      (is (empty? (:additional-contexts new-state)))
      (is (true? (:stop-turn? new-state)))
      (is (nil? (:stop-reason new-state))))))

(deftest prerequest-input-has-no-all-messages-test
  (testing "preRequest hook input does not contain all_messages"
    (h/reset-components!)
    (h/config! {:hooks {"test" {:type "preRequest"
                                :actions [{:type "shell" :shell "cat"}]}}})
    (let [input* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (fn [{:keys [input]}]
                                            (reset! input* (json/parse-string input true))
                                            {:exit 0 :out "" :err nil})]
        (#'f.chat/run-pre-request-hooks! {:db* (h/db*)
                                          :config (h/config)
                                          :chat-id "chat-1"
                                          :agent "code"
                                          :message "hello"
                                          :messenger (h/messenger)}))
      (is (some? @input*))
      (is (not (contains? @input* :all_messages))))))

(deftest prerequest-exit-2-captures-blocking-hook-name-test
  (testing "a visible preRequest exit 2 records the blocking hook name for the user-facing message"
    (h/reset-components!)
    (h/config! {:hooks {"blocker" {:type "preRequest"
                                   :visible true
                                   :actions [{:type "shell" :shell "exit 2"}]}}})
    (with-redefs [f.hooks/run-shell-cmd (constantly {:exit f.hooks/hook-rejection-exit-code
                                                     :out nil
                                                     :err "nope"})]
      (let [state (#'f.chat/run-pre-request-hooks! {:db* (h/db*)
                                                    :config (h/config)
                                                    :chat-id "chat-1"
                                                    :agent "code"
                                                    :message "hello"
                                                    :messenger (h/messenger)})]
        (is (match? {:blocked? true
                     :blocked-hook-name "blocker"
                     ;; visible hook: stderr stays in its block, not collected here
                     :blocked-reasons empty?}
                    state))))))

(deftest prerequest-continue-false-stop-reason-test
  (testing "preRequest continue:false with stopReason sends canonical turn-stopped message"
    (h/reset-components!)
    (let [sent* (atom [])]
      (with-redefs [lifecycle/send-content! (fn [_ role content]
                                               (swap! sent* conj {:role role :content content}))]
        (#'f.chat/finish-blocked-or-stopped-pre-request!
         {:db* (h/db*) :config (h/config) :chat-id "chat-1" :messenger (h/messenger)}
         {:blocked? false :stop-turn? true :stop-reason "policy violation" :stop-hook-name "guard"}))
      (is (some #(= "Turn stopped by hook 'guard': policy violation" (get-in % [:content :text])) @sent*)))))

(deftest prerequest-finish-helper-noop-test
  (testing "finish helper is defensive when neither stop nor block is present"
    (h/reset-components!)
    (let [sent* (atom [])
          finished* (atom false)]
      (with-redefs [lifecycle/send-content! (fn [_ role content]
                                               (swap! sent* conj {:role role :content content}))
                    lifecycle/finish-chat-prompt-stopped! (fn [& _]
                                                            (reset! finished* true))]
        (#'f.chat/finish-blocked-or-stopped-pre-request!
         {:db* (h/db*) :config (h/config) :chat-id "chat-1" :messenger (h/messenger)}
         {:blocked? false :stop-turn? false}))
      (is (empty? @sent*))
      (is (false? @finished*)))))

(deftest subagent-postrequest-stop-binds-to-subagent-chat-test
  (testing "subagent postRequest continue:false surfaces the prefixed stop message bound to the subagent chat"
    (h/reset-components!)
    ;; Mark the chat as a subagent so finish runs the post-request hooks for it;
    ;; the chat-ctx carries :parent-chat-id, so send-content! tags the message
    ;; with it (nested under the parent in the UI).
    (swap! (h/db*) assoc-in [:chats "sub-1" :subagent] {:max-steps nil})
    (h/config! {:hooks {"stopper" {:type "postRequest"
                                   :visible false
                                   :actions [{:type "shell" :shell "echo"}]}}})
    (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0
                                                     :out "{\"continue\":false,\"stopReason\":\"halt subagent\"}"
                                                     :err nil})]
      (lifecycle/finish-chat-prompt! :idle
                                     {:chat-id "sub-1"
                                      :parent-chat-id "parent-1"
                                      :db* (h/db*)
                                      :config (h/config)
                                      :messenger (h/messenger)
                                      :metrics (h/metrics)}))
    (is (some (fn [m]
                (and (= "sub-1" (:chat-id m))
                     (= "parent-1" (:parent-chat-id m))
                     (= :system (:role m))
                     (string/includes? (str (get-in m [:content :text])) "halt subagent")))
              (:chat-content-received (h/messages)))
        "the turn-stopped message must be bound to the subagent chat via parent-chat-id")))

(deftest prerequest-exit-2-no-stderr-chat-message-test
  (testing "preRequest exit 2 names the blocking hook without duplicating its stderr"
    (h/reset-components!)
    (let [sent* (atom [])]
      (with-redefs [lifecycle/send-content! (fn [_ role content]
                                               (swap! sent* conj {:role role :content content}))]
        (#'f.chat/finish-blocked-or-stopped-pre-request!
         {:db* (h/db*) :config (h/config) :chat-id "chat-1" :messenger (h/messenger)}
         {:blocked? true :stop-turn? false :stop-reason nil :blocked-hook-name "my-hook"}))
      ;; Exit 2 blocks and names the hook (for visible hooks the stderr lives in
      ;; the hookActionFinished block, so it is not duplicated here).
      (is (some #(= "Request blocked by hook 'my-hook'." (get-in % [:content :text])) @sent*)))))

(deftest prerequest-combined-exit-2-and-continue-false-test
  (testing "combined preRequest exit 2 followed by continue:false prioritizes stop behavior"
    (h/reset-components!)
    (let [sent* (atom [])]
      (with-redefs [lifecycle/send-content! (fn [_ role content]
                                               (swap! sent* conj {:role role :content content}))]
        ;; When both blocked? and stop-turn? are true, stop-turn? takes priority
        (#'f.chat/finish-blocked-or-stopped-pre-request!
         {:db* (h/db*) :config (h/config) :chat-id "chat-1" :messenger (h/messenger)}
         {:blocked? true :stop-turn? true :stop-reason "explicit stop" :stop-hook-name "guard"}))
      (is (some #(= "Turn stopped by hook 'guard': explicit stop" (get-in % [:content :text])) @sent*)))))

(deftest simplified-additional-context-wrapper-test
  (testing "wrap-additional-context no longer includes from attribute"
    (is (= "<additionalContext>\nextra info\n</additionalContext>"
           (lifecycle/wrap-additional-context "extra info")))))

(deftest followup-plain-text-test
  (testing "followUp continuation is plain user text, not XML-wrapped"
    (h/reset-components!)
    (swap! (h/db*) assoc :chats {"chat-1" {:agent "code"}})
    (h/config! {:hooks {"follow" {:type "postRequest"
                                   :actions [{:type "shell" :shell "echo"}]}}})
    (let [follow-up* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0
                                                       :out "{\"followUp\":\"Run the tests\"}"
                                                       :err nil})]
        (lifecycle/finish-chat-prompt! :idle {:db* (h/db*)
                                              :config (h/config)
                                              :chat-id "chat-1"
                                              :agent "code"
                                              :messenger (h/messenger)
                                              :metrics (h/metrics)
                                              :on-follow-up (fn [follow-up-text _chat-ctx]
                                                              (reset! follow-up* follow-up-text))}))
      ;; followUp text should be plain, not wrapped in <additionalContext>
      (is (= "Run the tests" @follow-up*)))))

(deftest strip-hook-callbacks-preserves-on-follow-up-test
  (testing "strip-hook-callbacks preserves :on-follow-up but strips finish side-effect callbacks"
    (let [chat-ctx {:on-finished-side-effect (fn [])
                    :on-after-finish! (fn [])
                    :on-follow-up (fn [_ _])}
          result (lifecycle/strip-hook-callbacks chat-ctx)]
      (is (not (contains? result :on-finished-side-effect)))
      (is (not (contains? result :on-after-finish!)))
      (is (contains? result :on-follow-up)))))

(deftest postcompact-returns-data-test
  (testing "postCompact hook runner returns data and does not mutate history directly"
    (h/reset-components!)
    (swap! (h/db*) assoc-in [:chats "chat-1" :messages]
           [{:role "compact_marker" :content {:auto? false}}
            {:role "user" :content [{:type :text :text "The conversation was compacted."}]}])
    (h/config! {:hooks {"test" {:type "postCompact"
                                :visible false
                                :actions [{:type "shell" :shell "echo"}]}}})
    (with-redefs [f.hooks/run-shell-cmd (constantly {:exit 0
                                                     :out "{\"additionalContext\":\"resume snapshot\"}"
                                                     :err nil})]
      (let [result (lifecycle/run-post-compact-hooks! {:db* (h/db*)
                                                       :config (h/config)
                                                       :chat-id "chat-1"
                                                       :agent "code"}
                                                      "manual"
                                                      "summary")]
        (is (map? result))
        (is (contains? result :stop-turn?))
        (is (contains? result :additional-contexts))
        (is (= false (:stop-turn? result)))
        (is (= 1 (count (:additional-contexts result))))))))

(deftest post-request-exit-2-as-followup-test
  (testing "postRequest exit 2 with stderr treats stderr as followUp text"
    (h/reset-components!)
    (swap! (h/db*) assoc :chats {"chat-1" {:agent "code"}})
    (h/config! {:hooks {"test" {:type "postRequest"
                                 :actions [{:type "shell" :shell "exit 2"}]}}})
    (let [follow-up* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (constantly {:exit f.hooks/hook-rejection-exit-code
                                                       :out nil
                                                       :err "Run the test suite"})]
        (lifecycle/finish-chat-prompt! :idle {:db* (h/db*)
                                              :config (h/config)
                                              :chat-id "chat-1"
                                              :agent "code"
                                              :messenger (h/messenger)
                                              :metrics (h/metrics)
                                              :on-follow-up (fn [follow-up-text _chat-ctx]
                                                              (reset! follow-up* follow-up-text))}))
      (is (= "Run the test suite" @follow-up*)))))
