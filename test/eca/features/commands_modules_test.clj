(ns eca.features.commands-modules-test
  "Commands come from the active modules' `:commands`."
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.commands :as f.commands]
   [eca.modules :as modules]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(defn- command-names [config]
  (mapv :name (f.commands/all-commands (h/db) config)))

(deftest built-in-commands-test
  (testing "built-in commands keep their order and type"
    (is (= ["init" "login" "model" "agent" "skills" "skill-create" "costs" "quota" "context" "approvals"]
           (take 10 (command-names {}))))
    (is (every? #(= :native (:type %)) (take 34 (f.commands/all-commands (h/db) {})))))
  (testing "disabling a module removes its commands"
    (let [names (set (command-names {:modules {:eca/plugins {:enabled false}
                                               :eca/create {:enabled false}}}))]
      (is (not (contains? names "plugins")))
      (is (not (contains? names "plugin-install")))
      (is (not (contains? names "init")))
      (is (contains? names "login")))))

(def ^:private test-module
  {:id :eca/test-commands
   :commands {"greet" {:description "Say hi."
                       :arguments [{:name "who"}]
                       :handler (fn [ctx {:keys [who raw]}]
                                  (str "Hi " who " from " (:module-id ctx) " (" raw ")"))}
              "ask" {:description "Ask the model."
                     :handler (fn [_ctx {:keys [raw]}] {:prompt (str "Answer: " raw)})}
              "quiet" {:description "Does it all itself."
                       :handler (fn [_ctx _args] nil)}}})

(deftest module-command-test
  (with-redefs [modules/built-in-modules (constantly (conj (h/built-in-modules-with) test-module))]
    (let [ctx {:chat-id "c1" :db* (h/db*) :config {} :messenger (h/messenger)}]
      (testing "listed after the built-in ones"
        (is (match? [{:name "greet" :type :native :description "Say hi." :arguments [{:name "who"}]}
                     {:name "ask"} {:name "quiet"}]
                    (filter #(#{"greet" "ask" "quiet"} (:name %)) (f.commands/all-commands (h/db) {})))))
      (testing "a string is shown as a system message; args are by name, plus :raw"
        (is (= {:type :chat-messages
                :chats {"c1" {:messages [{:role "system"
                                          :content [{:type :text :text "Hi ana from :eca/test-commands (ana now)"}]}]}}}
               (f.commands/handle-command! "greet" ["ana" "now"] (assoc ctx :message "/greet ana now")))))
      (testing "{:prompt ..} is sent as the user's message"
        (is (= {:type :send-prompt :prompt "Answer: why?"}
               (f.commands/handle-command! "ask" ["why?"] (assoc ctx :message "/ask why?")))))
      (testing "nil: nothing more to do"
        (is (= {:type :new-chat-status :status :idle}
               (f.commands/handle-command! "quiet" [] ctx))))
      (testing "the full id works too"
        (is (match? {:name "greet"} (f.commands/find-command "eca:greet" {})))
        (is (match? {:type :chat-messages} (f.commands/handle-command! "eca:greet" ["bo"] ctx)))))))

(deftest name-clash-test
  (testing "a name two modules share is listed by full id"
    (with-redefs [modules/built-in-modules
                  (constantly (conj (h/built-in-modules-with)
                                    test-module
                                    {:id :eca/other :prefix "other"
                                     :commands {"greet" {:handler (fn [_ _] "other")}}}))]
      (let [names (set (command-names {}))]
        (is (contains? names "eca:greet"))
        (is (contains? names "other:greet"))
        (is (not (contains? names "greet"))))
      (is (= "other" (get-in (f.commands/handle-command! "other:greet" [] {:chat-id "c1" :config {}})
                             [:chats "c1" :messages 0 :content 0 :text]))))))
