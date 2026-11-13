(ns eca.features.hooks-module-test
  "The shell hooks belong to :eca/hooks; chat lifecycle events reach modules."
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.chat.lifecycle :as lifecycle]
   [eca.features.hooks :as f.hooks]
   [eca.modules :as modules]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(def ^:private hooks {"my-hook" {:type "chatStatusChanged" :actions [{:type "shell" :shell "true"}]}})

(deftest active-hooks-test
  (is (= hooks (f.hooks/active-hooks {:hooks hooks})))
  (testing "disabling :eca/hooks turns off every shell hook"
    (is (nil? (f.hooks/active-hooks {:hooks hooks :modules {:eca/hooks {:enabled false}}})))
    (let [ran* (atom 0)]
      (with-redefs [f.hooks/run-hook-action! (fn [& _] (swap! ran* inc) {:exit 0})]
        (f.hooks/trigger-if-matches! :chatStatusChanged {} {} (h/db)
                                     {:hooks hooks :modules {:eca/hooks {:enabled false}}})
        (is (= 0 @ran*))
        (f.hooks/trigger-if-matches! :chatStatusChanged {} {} (h/db) {:hooks hooks})
        (is (= 1 @ran*))))))

(deftest status-changed-event-test
  (testing "a module handling :chat/status-changed gets each new aggregate status"
    (let [events* (atom [])]
      (with-redefs [modules/built-in-modules
                    (constantly (conj (h/built-in-modules-with)
                                      {:id :eca/test-status
                                       :event-handlers {:chat/status-changed (fn [_ctx event] (swap! events* conj event) nil)}}))]
        (swap! (h/db*) assoc-in [:chats "c1"] {:id "c1" :status :running})
        (lifecycle/trigger-chat-status-hook! {:db* (h/db*) :chat-id "c1" :config {}})
        (lifecycle/trigger-chat-status-hook! {:db* (h/db*) :chat-id "c1" :config {}})
        (is (match? [{:chat-id "c1" :status :running}] @events*))))))
