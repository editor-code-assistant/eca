(ns eca.api.test-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.api :as eca]
   [eca.api.test :as api.test]
   [matcher-combinators.test :refer [match?]]))

(def ^:private module
  {:id :acme/sample
   :tools {"echo" {:description "Echo."
                   :parameters {:type "object"}
                   :handler (fn [ctx {:strs [text]}]
                              (eca/swap-chat-state! ctx update :echoes (fnil inc 0))
                              (eca/text (str (:greeting (eca/config ctx)) " " text)))}}
   :commands {"jira" {:arguments [{:name "key"}]
                      :handler (fn [_ctx args] args)}
              "ask" {:handler (fn [ctx _]
                                (eca/notify! ctx "Asking.")
                                [(eca/confirm ctx "Sure?") (eca/input ctx {:title "Name"})])}}
   :defines {:events {:acme/rank {:kind :handle}}}
   :event-handlers {:chat/before-request [{:priority 10
                                           :handler (fn [_ _] {:additional-context "first"})}
                                          {:match {:prompt "write it"}
                                           :handler (fn [_ _] {:block "No writes."})}
                                          {:handler (fn [_ _] {:additional-context "second"
                                                               :ignored "not accepted"})}]
                    :acme/rank [(fn [_ _] nil)
                                (fn [_ {:keys [results]}] (sort results))]}})

(deftest run-tool-test
  (let [ctx (api.test/mock-ctx {:module module :config {:greeting "Hi"}})]
    (is (= {:error false :contents [{:type :text :text "Hi there"}]}
           (api.test/run-tool module "echo" {"text" "there"} ctx)))
    (testing "the mock chat keeps the module's state"
      (api.test/run-tool module "echo" {"text" "again"} ctx)
      (is (= {:echoes 2} (eca/chat-state ctx)))))
  (testing "a tool the module doesn't have"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no tool nope" (api.test/run-tool module "nope" {})))))

(deftest run-command-test
  (testing "args typed as text"
    (is (= {:key "ECA-12" :argv ["ECA-12" "now"] :raw "ECA-12 now"}
           (api.test/run-command module "jira" "ECA-12 now"))))
  (testing "or as a map"
    (is (= {:key "ECA-1"} (api.test/run-command module "jira" {:key "ECA-1"}))))
  (testing "dialogs get the answers given, in order, and notifications are recorded"
    (let [ctx (api.test/mock-ctx {:module module :answers [true "Ana"]})]
      (is (= [true "Ana"] (api.test/run-command module "ask" "" ctx)))
      (is (match? {:notifications [{:message "Asking."}] :questions ["Sure?" "Name"]}
                  (api.test/recorded ctx))))))

(deftest run-event-test
  (testing "a transform event: handlers by priority, context adds up, other keys are ignored"
    (is (= {:prompt "read it" :additional-context "first\nsecond"}
           (api.test/run-event module :chat/before-request {:prompt "read it"}))))
  (testing ":match, and the first :block ends the chain"
    (is (= {:prompt "write it" :block "No writes." :additional-context "first"}
           (api.test/run-event module :chat/before-request {:prompt "write it"}))))
  (testing "a handle event: the first answer"
    (is (= [1 2 3] (api.test/run-event module :acme/rank {:results [3 1 2]})))))
