(ns eca.features.chat.compaction-module-test
  ":eca/compact-as-summary owns compaction: disabled, ECA never compacts."
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.chat.lifecycle :as lifecycle]
   [eca.features.commands :as f.commands]
   [eca.shared :as shared]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(def ^:private disabled {:modules {:eca/compact-as-summary {:enabled false}}})

(defn- db-with-usage []
  ;; 90 of 100 context tokens used
  (-> (h/db)
      (assoc-in [:models "anthropic/claude-sonnet-4-6" :limit :context] 100)
      (assoc-in [:chats "c1" :usage] {:last-message-input-tokens 90 :last-message-output-tokens 0})))

(deftest auto-compact-test
  (let [config {:autoCompactPercentage 75}]
    (testing "the module's threshold triggers auto-compaction"
      (with-redefs [shared/usage-sumary (constantly {:session-tokens 90 :limit {:context 100}})]
        (is (lifecycle/auto-compact? "c1" "code" "anthropic/claude-sonnet-4-6" config (db-with-usage)))
        (is (= 75 (lifecycle/auto-compact-threshold config "code" 100)))))
    (testing "disabled: no auto-compaction, and no threshold for clients, even when configured"
      (with-redefs [shared/usage-sumary (constantly {:session-tokens 90 :limit {:context 100}})]
        (is (not (lifecycle/auto-compact? "c1" "code" "anthropic/claude-sonnet-4-6" (merge config disabled)
                                          (db-with-usage))))
        (is (nil? (lifecycle/auto-compact-threshold (merge config disabled) "code" 100)))))))

(deftest compact-command-test
  (testing "disabled, /compact says no module handled it"
    (is (match? {:type :chat-messages
                 :chats {"c1" {:messages [{:content [{:text "No compaction module handled this chat."}]}]}}}
                (f.commands/handle-command! "compact" [] {:chat-id "c1" :db* (h/db*) :config disabled
                                                          :messenger (h/messenger)})))))
