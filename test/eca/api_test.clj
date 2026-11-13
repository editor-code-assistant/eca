(ns eca.api-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.api :as eca]
   [eca.db :as db]
   [eca.features.chat :as f.chat]
   [eca.logger :as logger]
   [eca.modules :as modules]
   [eca.shared :as shared]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(defn- chat-ctx
  "A module's ctx in chat-1, with the session's components."
  []
  {:module-id :acme/x :chat-id "chat-1" :db* (h/db*) :messenger (h/messenger) :metrics (h/metrics)
   :config {}})

(defn- add-chat! [chat]
  (swap! (h/db*) assoc-in [:chats "chat-1"] (merge {:id "chat-1"} chat)))

(defn- ask-ui! [response]
  (swap! (h/db*) assoc-in [:client-capabilities :code-assistant :chat-capabilities :ask-question] true)
  (reset! (:ask-question-response* (h/messenger)) response))

(deftest module-id-test
  (is (= :acme/x (eca/module-id {:module-id :acme/x})))
  (is (nil? (eca/module-id {}))))

(deftest config-test
  (testing "the ctx's config"
    (is (= {:jiraUrl "https://jira"} (eca/config {:config {:jiraUrl "https://jira"}}))))
  (testing "else ECA's config, from the db"
    (is (= "ask" (get-in (eca/config {:db* (h/db*)}) [:toolCall :approval :byDefault]))))
  (testing "nil without either, e.g. while config defaults are computed"
    (is (nil? (eca/config {:db {:workspace-folders []}})))))

(deftest state-test
  (with-redefs [modules/state (fn [id] (when (= :acme/x id) {:n 1}))]
    (is (= {:n 1} (eca/state {:module-id :acme/x})))
    (is (nil? (eca/state {:module-id :acme/y})))))

(deftest workspaces-test
  (swap! (h/db*) assoc :workspace-folders [{:uri (shared/filename->uri (h/file-path "/tmp/a"))}
                                           {:uri (shared/filename->uri (h/file-path "/tmp/b"))}])
  (is (= [(h/file-path "/tmp/a") (h/file-path "/tmp/b")] (eca/workspaces {:db* (h/db*)})))
  (is (= [(h/file-path "/tmp/a") (h/file-path "/tmp/b")] (eca/workspaces {:db (h/db)}))))

(deftest log-test
  (testing "logs with the module id, and returns nil"
    (let [logged (atom nil)]
      (with-redefs [logger/warn (fn [& args] (reset! logged args))]
        (is (nil? (eca/log {:module-id :acme/x} :warn "careful" 42))))
      (is (= ["[MODULE acme/x]" "careful" 42] @logged)))))

(deftest results-test
  (is (= {:error false :contents [{:type :text :text "done"}]} (eca/text "done")))
  (is (= {:error true :contents [{:type :text :text "boom"}]} (eca/error "boom"))))

(deftest client-test
  (swap! (h/db*) assoc :client-info {:name "Emacs" :version "1.2"} :client-capabilities {:code-assistant {}})
  (is (= {:name "Emacs" :version "1.2" :capabilities {:code-assistant {}}} (eca/client {:db* (h/db*)}))))

(deftest chat-test
  (add-chat! {:agent "code" :model "anthropic/claude-sonnet-4-6" :variant "high" :status :running})
  (is (= {:id "chat-1" :agent "code" :model "anthropic/claude-sonnet-4-6" :variant "high" :status :running
          :kind nil :subagent? false}
         (eca/chat (chat-ctx))))
  (testing "a subagent's chat names its parent"
    (swap! (h/db*) assoc-in [:chats "sub-1"] {:id "sub-1" :subagent true :parent-chat-id "chat-1"})
    (is (match? {:subagent? true :parent-chat-id "chat-1" :status :idle}
                (eca/chat (assoc (chat-ctx) :chat-id "sub-1")))))
  (testing "nil outside of a chat"
    (is (nil? (eca/chat (dissoc (chat-ctx) :chat-id))))))

(deftest messages-test
  (add-chat! {:messages [{:role "user" :content [{:type :text :text "old"}]}
                         {:role "compact_marker" :content {}}
                         {:role "user" :content [{:type :text :text "summary"}]}]})
  (is (= 3 (count (eca/messages (chat-ctx)))))
  (is (= [{:role "user" :content [{:type :text :text "summary"}]}]
         (eca/messages (chat-ctx) {:since-checkpoint? true}))))

(deftest chat-state-test
  (add-chat! {})
  (testing "swap-chat-state! returns the new state, which chat-state reads"
    (is (= {:on? true} (eca/swap-chat-state! (chat-ctx) assoc :on? true)))
    (is (= {:n 1 :on? true} (eca/swap-chat-state! (chat-ctx) assoc :n 1)))
    (is (= {:n 1 :on? true} (eca/chat-state (chat-ctx))))
    (is (nil? (eca/chat-state (assoc (chat-ctx) :module-id :acme/other)))))
  (testing "state that can't be saved with the chat is refused, and nothing changes"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be plain data"
                          (eca/swap-chat-state! (chat-ctx) assoc :conn (atom 1))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be plain data"
                          (eca/swap-chat-state! (chat-ctx) assoc :f inc)))
    (is (= {:n 1 :on? true} (eca/chat-state (chat-ctx)))))
  (testing "a gone chat is a no-op"
    (is (nil? (eca/swap-chat-state! (assoc (chat-ctx) :chat-id "gone") assoc :x 1)))
    (is (nil? (get-in (h/db) [:chats "gone"]))))
  (testing "it's saved with the chat"
    (swap! (h/db*) update-in [:chats "chat-1"] assoc :messages [{:role "user" :content "hi"}])
    (db/save-chat! (h/db) "chat-1" (h/metrics))
    (let [saved (#'db/read-chat-file (#'db/db-workspaces (h/db)) "chat-1" (h/metrics))]
      (is (= {:acme/x {:n 1 :on? true}} (:modules saved))))))

(deftest chat-state-forked-test
  (add-chat! {:messages [{:role "user" :content [{:type :text :text "hi"}] :content-id "c1"}]
              :modules {:acme/x {:on? true}}})
  (let [{:keys [chat-id]} (f.chat/fork-chat {:chat-id "chat-1" :content-id "c1"} (h/db*) (h/messenger) (h/metrics))]
    (is (= {:acme/x {:on? true}} (get-in (h/db) [:chats chat-id :modules])))))

(deftest send-prompt-test
  (testing "an idle chat gets a new turn"
    (add-chat! {:status :idle :agent "plan" :variant "low"})
    (let [prompted (promise)]
      (with-redefs [f.chat/prompt (fn [params & _] (deliver prompted params))]
        (is (nil? (eca/send-prompt! (chat-ctx) "Continue.")))
        (is (= {:chat-id "chat-1" :message "Continue." :agent "plan" :variant "low"}
               (deref prompted 5000 :timeout))))))
  (testing "a running chat gets it at its next step, like a message typed while it works"
    (add-chat! {:status :running})
    (eca/send-prompt! (chat-ctx) "Also this.")
    (is (= "Also this." (get-in (h/db) [:chats "chat-1" :steer-message])))))

(deftest add-context-test
  (add-chat! {})
  (eca/add-context! (chat-ctx) "First.")
  (eca/add-context! (chat-ctx) "  ")
  (eca/add-context! (chat-ctx) "Second.")
  (is (= [{:module "acme/x" :content "First."} {:module "acme/x" :content "Second."}]
         (get-in (h/db) [:chats "chat-1" :module-contexts])))
  (testing "they are taken once, by the next request"
    (is (= 2 (count (#'f.chat/take-module-contexts! (h/db*) "chat-1"))))
    (is (nil? (#'f.chat/take-module-contexts! (h/db*) "chat-1")))))

(deftest notify-test
  (eca/notify! (chat-ctx) "Done.")
  (eca/notify! (chat-ctx) "Careful." {:level :warn})
  (eca/notify! (chat-ctx) "Broken." {:level :error})
  (is (= [{:type :info :message "Done."} {:type :warning :message "Careful."} {:type :error :message "Broken."}]
         (:show-message (h/messages)))))

(deftest progress-test
  (let [reports (atom [])]
    (eca/progress! (assoc (chat-ctx) :on-progress #(swap! reports conj %)) "Page 1 of 3")
    (is (= [{:message "Page 1 of 3"}] @reports))
    (testing "a no-op outside of a tool"
      (is (nil? (eca/progress! (chat-ctx) "x"))))))

(deftest dialogs-test
  (add-chat! {})
  (testing "without a client that asks, dialogs don't ask"
    (is (false? (eca/ui? (chat-ctx))))
    (is (false? (eca/confirm (chat-ctx) "Sure?")))
    (is (nil? (eca/select (chat-ctx) {:title "Pick" :options ["a" "b"]})))
    (is (nil? (eca/input (chat-ctx) {:title "Name"}))))
  (testing "confirm"
    (ask-ui! {:answer "Yes" :cancelled false})
    (is (true? (eca/ui? (chat-ctx))))
    (is (true? (eca/confirm (chat-ctx) "Sure?")))
    (ask-ui! {:answer "No" :cancelled false})
    (is (false? (eca/confirm (chat-ctx) "Sure?")))
    (ask-ui! {:answer nil :cancelled true})
    (is (false? (eca/confirm (chat-ctx) "Sure?"))))
  (testing "select returns the option picked"
    (ask-ui! {:answer "b" :cancelled false})
    (is (= "b" (eca/select (chat-ctx) {:title "Pick" :options ["a" "b"]})))
    (ask-ui! {:answer "Fast" :cancelled false})
    (is (= {:label "Fast" :description "Cheaper" :value :fast}
           (eca/select (chat-ctx) {:title "Pick" :options [{:label "Fast" :description "Cheaper" :value :fast}
                                                           {:label "Smart"}]})))
    (ask-ui! {:answer nil :cancelled true})
    (is (nil? (eca/select (chat-ctx) {:title "Pick" :options ["a" "b"]}))))
  (testing "input"
    (ask-ui! {:answer "Ana" :cancelled false})
    (is (= "Ana" (eca/input (chat-ctx) {:title "Name"})))
    (ask-ui! {:answer nil :cancelled true})
    (is (nil? (eca/input (chat-ctx) {:title "Name"}))))
  (testing "outside of a chat there's no ui"
    (is (false? (eca/ui? (dissoc (chat-ctx) :chat-id))))))

(deftest emit-test
  (let [module {:id :acme/events
                :defines {:events {:acme/ping {:kind :notify}
                                   :acme/shape {:kind :transform}
                                   :acme/ask {:kind :handle}}}
                :event-handlers {:acme/ping (fn [_ _] {:ignored true})
                                 :acme/shape (fn [_ {:keys [n]}] {:n (inc n)})
                                 :acme/ask (fn [_ _] {:answer 42})}}]
    (with-redefs [modules/built-in-modules (constantly (h/built-in-modules-with))]
      (modules/add-external! "/tmp/events.clj" module)
      (is (nil? (eca/emit! {:config {}} :acme/ping {})))
      (is (match? {:n 2} (eca/emit! {:config {}} :acme/shape {:n 1})))
      (is (= {:answer 42} (eca/emit! {:config {}} :acme/ask {}))))))

(deftest tools-test
  (let [module {:id :acme/jira
                :tools {"issue" {:description "Fetch an issue."
                                 :parameters {:type "object" :properties {"key" {:type "string"}}}
                                 :handler (fn [_ {:strs [key]}] (eca/text (str "Issue " key)))}}}]
    (with-redefs [modules/built-in-modules (constantly (h/built-in-modules-with :eca/approval))]
      (modules/add-external! "/tmp/jira.clj" module)
      (add-chat! {:agent "code"})
      (is (match? [{:full-name "jira__issue" :name "issue" :server "jira" :description "Fetch an issue."}]
                  (filter #(= "jira" (:server %)) (eca/tools (chat-ctx)))))
      (is (match? {:error false :contents [{:text "Issue ECA-1"}]}
                  (eca/call-tool! (chat-ctx) "jira__issue" {"key" "ECA-1"}))))))

(deftest module-ctx-components-test
  (testing "after the session starts, module functions get its components"
    (modules/start! {:db* (h/db*) :messenger (h/messenger) :metrics (h/metrics)} {:modules {}})
    (is (match? {:db* (h/db*) :messenger (h/messenger) :module-id :acme/x :chat-id "c"}
                (modules/module-ctx {:chat-id "c"} {:id :acme/x})))
    (testing "the caller's keys win"
      (is (= :mine (:db* (modules/module-ctx {:db* :mine} {:id :acme/x})))))))
