(ns eca.features.chat.compaction-module-test
  ":eca/compact-as-summary owns compaction: disabled, ECA never compacts."
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.chat.lifecycle :as f.chat.lifecycle]
   [eca.features.commands :as f.commands]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(def ^:private disabled {:modules {:eca/compact-as-summary {:enabled false}}})

(deftest auto-compact-test
  (let [config {:autoCompactPercentage 75}
        usage {:session-tokens 90 :limit {:context 100}}
        ctx (fn [config] {:config config :chat-id "c1" :db* (h/db*) :agent "code"
                          :full-model "anthropic/claude-sonnet-4-6"})]
    (swap! (h/db*) assoc-in [:chats "c1"] {:id "c1"})
    (testing "past the module's threshold, it asks for a compaction and gives clients the threshold"
      (is (= 75 (f.chat.lifecycle/usage-updated! (ctx config) usage 100)))
      (is (match? {:module :eca/compact-as-summary :trigger :auto}
                  (get-in (h/db) [:chats "c1" :compaction-request]))))
    (testing "disabled: no auto-compaction, and no threshold for clients, even when configured"
      (swap! (h/db*) update-in [:chats "c1"] dissoc :compaction-request)
      (is (nil? (f.chat.lifecycle/usage-updated! (ctx (merge config disabled)) usage 100)))
      (is (nil? (get-in (h/db) [:chats "c1" :compaction-request]))))))

(deftest compact-command-test
  (testing "disabled, /compact says no module handled it"
    (is (match? {:type :chat-messages
                 :chats {"c1" {:messages [{:content [{:text "No compaction module handled this chat."}]}]}}}
                (f.commands/handle-command! "compact" [] {:chat-id "c1" :db* (h/db*) :config disabled
                                                          :messenger (h/messenger)})))))
