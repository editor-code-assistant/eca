(ns eca.features.chat-events-test
  "The chat lifecycle events modules get: :chat/input, :chat/started,
   :chat/before-request, :chat/after-request, :chat/message-added and
   :chat/title."
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.api :as eca]
   [eca.config :as config]
   [eca.features.chat :as f.chat]
   [eca.features.tools :as f.tools]
   [eca.llm-api :as llm-api]
   [eca.modules :as modules]
   [eca.modules.hooks :as m.hooks]
   [eca.test-helper :as h]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(def ^:private events* (atom []))

(defn- with-module!
  "Adds an external module with these event handlers, each recording the
   events it gets in `events*`."
  [handlers]
  (reset! events* [])
  (modules/add-external! "/tmp/acme-events.clj"
                         {:id :acme/events
                          :event-handlers (update-vals handlers
                                                       (fn [f]
                                                         (fn [ctx event]
                                                           (swap! events* conj event)
                                                           (f ctx event))))}))

(defn- prompt!
  "Prompts through f.chat/prompt with a mocked LLM, returning
   `{:chat-id :requests}`: the user messages of each LLM request."
  ([params] (prompt! params (atom [])))
  ([params requests*]
   (let [{:keys [chat-id]}
         (with-redefs [llm-api/sync-or-async-prompt! (fn [{:keys [user-messages on-first-response-received on-message-received]}]
                                                       (swap! requests* conj user-messages)
                                                       (on-first-response-received {:type :text :text "ok"})
                                                       (on-message-received {:type :text :text "ok"})
                                                       (on-message-received {:type :finish}))
                       llm-api/sync-prompt! (constantly nil)
                       f.tools/all-tools (constantly [])
                       config/await-plugins-resolved! (constantly true)]
           (h/config! {:env "test"})
           (swap! (h/db*) update :models #(merge {"openai/gpt-5.2" {:tools true}} %))
           (f.chat/prompt params (h/db*) (h/messenger) (h/config) (h/metrics)))]
     {:chat-id chat-id :requests @requests*})))

(defn- request-text [user-messages]
  (->> user-messages first :content (keep :text) (string/join "\n")))

(defn- system-texts []
  (->> (:chat-content-received (h/messages))
       (filter #(= :system (:role %)))
       (keep #(get-in % [:content :text]))))

(deftest chat-input-test
  (testing "a module changes the text before anything reads it"
    (with-module! {:chat/input (fn [_ {:keys [text]}] {:text (string/replace text "secret-123" "[REDACTED]")})})
    (let [{:keys [requests]} (prompt! {:message "my key is secret-123"})]
      (is (= "my key is [REDACTED]" (request-text (first requests))))
      (is (match? [{:text "my key is secret-123" :source :user :chat-id string?}] @events*))))
  (testing "or handles it: the model isn't called"
    (h/reset-messenger!)
    (with-module! {:chat/input (fn [_ _] {:handled "Done by the module."})})
    (let [{:keys [requests]} (prompt! {:message "do it"})]
      (is (empty? requests))
      (is (some #{"Done by the module."} (system-texts))))))

(deftest chat-before-request-test
  (testing "a module rewrites the prompt and adds context"
    (with-module! {:chat/before-request (fn [_ {:keys [prompt]}]
                                          {:prompt (str prompt ", please") :additional-context "Be brief."})})
    (let [{:keys [requests]} (prompt! {:message "explain"})]
      (is (= "explain, please\n<additionalContext>\nBe brief.\n</additionalContext>" (request-text (first requests))))))
  (testing "or blocks the request"
    (h/reset-messenger!)
    (with-module! {:chat/before-request (fn [_ _] {:block "Budget reached."})})
    (let [{:keys [requests]} (prompt! {:message "explain"})]
      (is (empty? requests))
      (is (some #{"Request blocked by module 'acme/events': Budget reached."} (system-texts)))))
  (testing "or stops the turn"
    (h/reset-messenger!)
    (with-module! {:chat/before-request (fn [_ _] {:stop "Not now."})})
    (let [{:keys [requests]} (prompt! {:message "explain"})]
      (is (empty? requests))
      (is (some #{"Turn stopped by module 'acme/events': Not now."} (system-texts))))))

(deftest compact-is-not-a-request-for-modules-test
  (testing "/compact runs no :chat/before-request, and contexts added for the next request wait for it"
    (with-module! {:chat/before-request (constantly nil)})
    (h/config! {:prompts {:compact "Summarize the chat."}
                :hooks {"guard" {:type "preRequest" :actions [{:type "command" :shell "true"}]}}})
    (let [hooks* (atom [])]
      (with-redefs [m.hooks/run-hook-action! (fn [_action hook-name hook-type & _]
                                               (swap! hooks* conj [hook-type hook-name])
                                               nil)]
        (let [{:keys [chat-id]} (prompt! {:message "hi"})]
          (eca/add-context! {:module-id :acme/events :chat-id chat-id :db* (h/db*)} "Notes index.")
          (reset! events* [])
          (reset! hooks* [])
          (let [{:keys [requests]} (prompt! {:message "/compact" :chat-id chat-id})]
            (is (= 1 (count requests)) "the summary request was sent")
            (is (not (string/includes? (request-text (first requests)) "Notes index.")))
            (is (empty? @events*))
            (is (= [[:preRequest "guard"]] @hooks*) "the preRequest hooks still run, as before modules")
            (is (= [{:module :acme/events :content "Notes index."}]
                   (get-in (h/db) [:chats chat-id :module-contexts]))))
          (testing "the next request gets them"
            (let [{:keys [requests]} (prompt! {:message "go on" :chat-id chat-id})]
              (is (string/includes? (request-text (first requests)) "Notes index."))
              (is (= 1 (count @events*))))))))))

(deftest chat-started-test
  (testing "the first prompt of a chat starts it, with the module's context"
    (with-module! {:chat/started (fn [_ _] {:additional-context "Project notes."})})
    (let [{:keys [chat-id]} (prompt! {:message "hi"})]
      (is (match? [{:resumed? false :chat-id chat-id}] @events*))
      (is (= "Project notes." (get-in (h/db) [:chats chat-id :startup-context])))
      (testing "later prompts don't"
        (prompt! {:message "again" :chat-id chat-id})
        (is (= 1 (count @events*))))))
  (testing "a module can stop the first turn"
    (h/reset-messenger!)
    (with-module! {:chat/started (fn [_ _] {:stop "Set up first."})})
    (let [{:keys [requests]} (prompt! {:message "hi"})]
      (is (empty? requests))
      (is (some #{"Turn stopped by module 'acme/events': Set up first."} (system-texts))))))

(deftest chat-after-request-test
  (testing "a module's follow-up is sent as the next user message, once"
    (let [follow-ups (atom 0)]
      (with-module! {:chat/after-request (fn [_ _]
                                           (when (= 1 (swap! follow-ups inc))
                                             {:follow-up "Now run the tests."}))})
      (let [{:keys [requests]} (prompt! {:message "fix it"})]
        (is (= ["fix it" "Now run the tests."] (map request-text requests)))
        (is (match? [{:chat-id string?} {:chat-id string?}] @events*)))))
  (testing "a module can stop the turn after the response"
    (h/reset-messenger!)
    (with-module! {:chat/after-request (fn [_ _] {:stop "Enough."})})
    (prompt! {:message "fix it"})
    (is (some #{"Turn stopped by module 'acme/events': Enough."} (system-texts)))))

(deftest chat-message-added-test
  (with-module! {:chat/message-added (fn [_ _] nil)})
  (prompt! {:message "hello"})
  (is (match? (m/embeds [{:message {:role "user" :content [{:type :text :text "hello"}]}}
                         {:message {:role "assistant"}}])
              @events*)))

(deftest chat-title-test
  (with-module! {:chat/title (fn [_ {:keys [messages]}]
                               {:title (str "About " (-> messages last :content first :text))})})
  (h/config! {:chat {:title true}})
  (let [{:keys [chat-id]} (prompt! {:message "caching"})]
    (testing "a module gives the chat its title, instead of the model"
      (is (= "About caching"
             (loop [n 0]
               (or (get-in (h/db) [:chats chat-id :title])
                   (when (< n 50) (Thread/sleep 50) (recur (inc n))))))))))

(deftest module-contexts-in-messages-test
  (testing "a module's context carries its id and :data, in the request and in the history"
    (with-module! {:chat/before-request (constantly {:additional-context {:text "Notes index." :data {:v 1}}})})
    (let [{:keys [chat-id requests]} (prompt! {:message "explain"})
          content [{:type :text :text "explain"}
                   {:type :text :text "<additionalContext>\nNotes index.\n</additionalContext>"
                    :module :acme/events :data {:v 1}}]]
      (is (= content (:content (first (first requests)))))
      (is (= content (:content (first (get-in (h/db) [:chats chat-id :messages])))))))
  (testing "the chat's title isn't made from it"
    (h/reset-messenger!)
    (with-module! {:chat/before-request (constantly {:additional-context "Notes index."})
                   :chat/title (fn [_ {:keys [messages]}]
                                 {:title (str "About " (string/join " " (mapcat #(keep :text (:content %)) messages)))})})
    (h/config! {:chat {:title true}})
    (let [{:keys [chat-id]} (prompt! {:message "caching"})]
      (is (= "About caching"
             (loop [n 0]
               (or (get-in (h/db) [:chats chat-id :title])
                   (when (< n 50) (Thread/sleep 50) (recur (inc n))))))))))
