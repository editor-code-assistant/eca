(ns eca.features.chat.lifecycle-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.chat.lifecycle :as lifecycle]))

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
