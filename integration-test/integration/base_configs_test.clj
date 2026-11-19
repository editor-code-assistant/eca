(ns integration.base-configs-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [integration.eca :as eca]
   [integration.fixture :as fixture]
   [llm-mock.server :as llm-mock.server]
   [matcher-combinators.test :refer [match?]]))

(eca/clean-after-test)

(defn ^:private await-chat-config
  "Merges the chat fields of the `config/updated` notifications, which only
   carry what changed, until `pred` holds for them; returns them."
  [pred]
  (loop [chat {}
         notifications 0]
    (if (or (pred chat) (= 10 notifications))
      chat
      (recur (merge chat (:chat (eca/client-awaits-server-notification :config/updated)))
             (inc notifications)))))

(deftest base-configs
  (eca/start-process!)

  (testing "initialize with a base config"
    (is (match? {:chatWelcomeMessage string?}
                (eca/request! (fixture/initialize-request
                               {:initializationOptions (merge fixture/default-init-options
                                                              {:baseConfigs [llm-mock.server/base-config-url]})})))))

  (testing "initialized notification"
    (eca/notify! (fixture/initialized-notification)))

  (testing "what the base config defines applies: its provider's models and its default model"
    (let [chat (await-chat-config #(some #{"base-provider/base-model"} (:models %)))]
      (is (some #{"base-provider/base-model"} (:models chat)))
      (is (= "base-provider/base-model" (:selectModel chat)))))

  (testing "the server fetched it from its URL"
    (is (pos? (llm-mock.server/base-config-requests)))))
