(ns eca.features.hooks-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]
   [eca.features.hooks :as f.hooks]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

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
      (is (match? {:name "my-hook"} @result*)))))

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
      (is (= "test" (get-in @result* [:parsed :additionalContext])))))

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
                                     (merge (f.hooks/chat-hook-data (h/db) "chat-1" "code")
                                            {:tool-name "read_file"
                                             :server "eca"
                                             :tool-input {:path "/foo"}
                                             :approval :ask})
                                     {} (h/db) (h/config)))
      (is (= {:path "/foo"} (:tool_input @result*)))
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
                                     (merge (f.hooks/chat-hook-data (h/db) "chat-1" "code")
                                            {:tool-name "read_file"
                                             :server "eca"
                                             :tool-input {:path "/foo"}
                                             :tool-response {:content "data"}})
                                     {} (h/db) (h/config)))
      (is (= {:path "/foo"} (:tool_input @result*)))
      (is (= {:content "data"} (:tool_response @result*))))))

(deftest stop-hook-active-test
  (testing "postRequest receives stop_hook_active flag"
    (h/reset-components!)
    (swap! (h/db*) assoc :chats {"chat-1" {:agent "code"}})
    (h/config! {:hooks {"test" {:type "postRequest"
                                :actions [{:type "shell" :shell "cat"}]}}})
    (let [result* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (fn [opts]
                                            (reset! result* (json/parse-string (:input opts) true))
                                            {:exit 0 :out "" :err nil})]
        (f.hooks/trigger-if-matches! :postRequest
                                     (merge (f.hooks/chat-hook-data (h/db) "chat-1" "code")
                                            {:prompt "test"
                                             :stop-hook-active false})
                                     {} (h/db) (h/config)))
      (is (false? (:stop_hook_active @result*))))))

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
      (is (= "deny" (get-in @result* [:parsed :approval])))
      (is (= "Too large" (get-in @result* [:parsed :additionalContext]))))))

;;; Lifecycle hooks tests

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
                                     (merge (f.hooks/chat-hook-data (h/db) "chat-1" "code")
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
                                     (merge (f.hooks/chat-hook-data (h/db) "chat-1" "code")
                                            {:tool-name "tool" :server "eca" :error true})
                                     {} (h/db) (h/config)))
      (is (true? @ran?*)))))

(deftest subagent-finished-test
  (testing "subagentFinished hook triggers with parent_chat_id"
    (h/reset-components!)
    (swap! (h/db*) assoc :chats {"sub-1" {:agent "explorer"}})
    (h/config! {:hooks {"test" {:type "subagentFinished"
                                :actions [{:type "shell" :shell "cat"}]}}})
    (let [result* (atom nil)]
      (with-redefs [f.hooks/run-shell-cmd (fn [opts]
                                            (reset! result* (json/parse-string (:input opts) true))
                                            {:exit 0 :out "" :err nil})]
        (f.hooks/trigger-if-matches! :subagentFinished
                                     (merge (f.hooks/chat-hook-data (h/db) "sub-1" "explorer")
                                            {:prompt "explore the codebase"
                                             :parent-chat-id "parent-1"})
                                     {} (h/db) (h/config)))
      (is (= "sub-1" (:chat_id @result*)))
      (is (= "explorer" (:agent @result*)))
      (is (= "parent-1" (:parent_chat_id @result*)))
      (is (= "explore the codebase" (:prompt @result*)))))

  (testing "postRequest does not trigger for subagentFinished hook type"
    (h/reset-components!)
    (h/config! {:hooks {"test" {:type "postRequest"
                                :actions [{:type "shell" :shell "echo hey"}]}}})
    (let [ran?* (atom false)]
      (with-redefs [f.hooks/run-shell-cmd (fn [_] (reset! ran?* true) {:exit 0 :out "" :err nil})]
        (f.hooks/trigger-if-matches! :subagentFinished
                                     {:prompt "task"}
                                     {} (h/db) (h/config)))
      (is (false? @ran?*))))

  (testing "subagentFinished does not trigger for postRequest hook type"
    (h/reset-components!)
    (h/config! {:hooks {"test" {:type "subagentFinished"
                                :actions [{:type "shell" :shell "echo hey"}]}}})
    (let [ran?* (atom false)]
      (with-redefs [f.hooks/run-shell-cmd (fn [_] (reset! ran?* true) {:exit 0 :out "" :err nil})]
        (f.hooks/trigger-if-matches! :postRequest
                                     {:prompt "task"}
                                     {} (h/db) (h/config)))
      (is (false? @ran?*)))))
