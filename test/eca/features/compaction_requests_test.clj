(ns eca.features.compaction-requests-test
  "Compactions modules ask for: eca.api/request-compaction! and the
   answers to :llm/request-failed."
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.api :as eca]
   [eca.config :as config]
   [eca.features.chat :as f.chat]
   [eca.features.tools :as f.tools]
   [eca.llm-api :as llm-api]
   [eca.modules :as modules]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(def ^:private events* (atom []))

(defn- with-module!
  "Adds an external module with these event handlers, each recording
   `[event-id event]` in `events*`."
  [handlers]
  (reset! events* [])
  (modules/add-external! "/tmp/acme-compact.clj"
                         {:id :acme/compact
                          :event-handlers (into {}
                                                (map (fn [[event-id f]]
                                                       [event-id (fn [ctx event]
                                                                   (swap! events* conj [event-id event])
                                                                   (f ctx event))]))
                                                handlers)}))

(def ^:private chat-id "chat-1")

(def ^:private history
  [{:role "user" :content [{:type :text :text "fix the bug"}]}
   {:role "assistant" :content [{:type :text :text "done"}]}])

(defn- setup-chat! []
  (swap! (h/db*) assoc-in [:chats chat-id] {:id chat-id :messages history}))

(defn- module-ctx []
  {:module-id :acme/compact :chat-id chat-id :db* (h/db*)})

(defn- summarize [ctx _]
  (eca/checkpoint! ctx {:messages [{:role "user" :content "Summary."}] :label "Compacted chat (acme)"})
  {:status :done :resume? true})

(def ^:private resume-text "Continue with the task. The previous user request was:")

(defn- texts [user-messages]
  (mapv #(some-> % :content first :text) user-messages))

(defn- prompt!
  "Prompts the chat with a mocked LLM: `api-mock` gets each request's options.
   Tools return `call-tool`'s result."
  [message api-mock & [{:keys [call-tool]}]]
  (with-redefs [llm-api/sync-or-async-prompt! api-mock
                llm-api/sync-prompt! (constantly nil)
                f.tools/call-tool! (or call-tool (constantly {:error false :contents [{:type :text :text "ok"}]}))
                f.tools/all-tools (constantly [{:name "tool_a" :full-name "eca__tool_a" :origin :native :server {:name "eca"}}])
                f.tools/approval (constantly :allow)
                config/await-plugins-resolved! (constantly true)]
    (h/config! {:env "test"})
    (swap! (h/db*) update :models #(merge {"openai/gpt-5.2" {:tools true}} %))
    (f.chat/prompt {:chat-id chat-id :message message} (h/db*) (h/messenger) (h/config) (h/metrics))))

(defn- answer!
  "An `api-mock` reply: the model answers \"ok\"."
  [{:keys [on-first-response-received on-message-received]}]
  (on-first-response-received {:type :text :text "ok"})
  (on-message-received {:type :text :text "ok"})
  (on-message-received {:type :finish}))

(defn- system-texts []
  (->> (:chat-content-received (h/messages))
       (filter #(= :system (:role %)))
       (keep #(get-in % [:content :text]))))

(deftest request-compaction-test
  (setup-chat!)
  (testing "a module's request is kept on the chat"
    (is (true? (eca/request-compaction! (module-ctx) {:instructions "Keep the plan."})))
    (is (= {:module :acme/compact :trigger :auto :instructions "Keep the plan."}
           (get-in (h/db) [:chats chat-id :compaction-request]))))
  (testing "but not while the chat compacts"
    (swap! (h/db*) update-in [:chats chat-id] #(-> % (dissoc :compaction-request) (assoc :auto-compacting? true)))
    (is (false? (eca/request-compaction! (module-ctx))))
    (is (nil? (get-in (h/db) [:chats chat-id :compaction-request])))))

(deftest take-compaction-request-test
  (let [request {:module :acme/compact :trigger :auto}
        take! (fn [ctx]
                (swap! (h/db*) assoc-in [:chats chat-id :compaction-request] request)
                [(#'f.chat/take-compaction-request! (merge {:db* (h/db*) :chat-id chat-id :prompt-id "p1" :config {}} ctx))
                 (get-in (h/db) [:chats chat-id :compaction-request])])]
    (setup-chat!)
    (testing "it's taken off the chat"
      (is (= [request nil] (take! {}))))
    (testing "and dropped when the turn can't compact again"
      (is (= [nil nil] (take! {:auto-compacted? true})) "a compaction was blocked")
      (is (= [nil nil] (take! {:auto-compactions 3})) "the cap, 3 by default")
      (is (= [request nil] (take! {:auto-compactions 3 :config {:autoCompactMaxPerTurn 5}})))
      (is (= [nil nil] (take! {:post-compact-guard* (atom true)})) "right after a compaction"))
    (testing "and after a requested compaction was blocked in this prompt"
      (swap! (h/db*) assoc-in [:chats chat-id :compaction-blocked-prompt-id] "p1")
      (is (= [nil nil] (take! {})))
      (is (= [request nil] (take! {:prompt-id "p2"}))))))

(deftest requested-compaction-before-a-request-test
  (testing "a pending request compacts the chat before the next request, then the task resumes"
    (setup-chat!)
    (with-module! {:chat/compact summarize})
    (eca/request-compaction! (module-ctx))
    (let [requests* (atom [])]
      (prompt! "add a test" (fn [{:keys [user-messages] :as opts}]
                              (swap! requests* conj (texts user-messages))
                              (answer! opts)))
      (is (= [[resume-text "add a test"]] @requests*)))
    (is (match? [[:chat/compact {:trigger :auto}]] @events*))
    (is (match? {:role "compact_marker" :content {:module :acme/compact}}
                (nth (get-in (h/db) [:chats chat-id :messages]) 2)))
    (is (nil? (get-in (h/db) [:chats chat-id :compaction-request])))))

(deftest requested-compaction-in-the-tool-loop-test
  (testing "a request made while tools run compacts the chat before the next request"
    (setup-chat!)
    (with-module! {:chat/compact summarize})
    (let [requests* (atom [])
          loop-results* (atom [])]
      (prompt! "fix it"
               (fn [{:keys [user-messages on-prepare-tool-call on-tools-called] :as opts}]
                 (swap! requests* conj (texts user-messages))
                 (if (= 1 (count @requests*))
                   (do (on-prepare-tool-call {:id "call-1" :full-name "eca__tool_a" :arguments-text "{}"})
                       (swap! loop-results* conj (on-tools-called [{:id "call-1" :full-name "eca__tool_a" :arguments {}}])))
                   (answer! opts)))
               {:call-tool (fn [& _]
                             (eca/request-compaction! (module-ctx))
                             {:error false :contents [{:type :text :text "ok"}]})})
      (is (= [nil] @loop-results*) "the first request's tool loop ends")
      (is (= [["fix it"] [resume-text "fix it"]] @requests*)))
    (is (match? [[:chat/compact {:trigger :auto}]] @events*))))

(deftest request-failed-test
  (testing "a module asks to send the failed request again, once per turn"
    (setup-chat!)
    (h/reset-messenger!)
    (with-module! {:llm/request-failed (constantly {:retry true})})
    (let [calls* (atom 0)]
      (prompt! "hi" (fn [{:keys [on-error]}]
                      (swap! calls* inc)
                      (on-error {:status 400 :message "Bad request"})))
      (is (= 2 @calls*))
      (is (= 1 (count @events*)) "not asked again for the retry")
      (is (match? [[:llm/request-failed {:error {:message "Bad request" :status 400} :request {:model "openai/gpt-5.2"}}]]
                  @events*))
      (is (some #{"\nRetrying the request, as a module asked...\n"} (system-texts)))))
  (testing "or to compact the chat, then the task resumes"
    (setup-chat!)
    (with-module! {:llm/request-failed (constantly {:compact {:instructions "Keep it short."}})
                   :chat/compact summarize})
    (let [requests* (atom [])]
      (prompt! "hi" (fn [{:keys [user-messages on-error] :as opts}]
                      (swap! requests* conj (texts user-messages))
                      (if (= 1 (count @requests*))
                        (on-error {:error/type :context-overflow :message "token limit exceeded"})
                        (answer! opts))))
      (is (= [["hi"] [resume-text "hi"]] @requests*)))
    (is (match? [[:llm/request-failed {:error {:type :context-overflow}}]
                 [:chat/compact {:trigger :overflow :instructions "Keep it short."}]]
                @events*))
    (is (match? {:role "compact_marker" :content {:module :acme/compact}}
                (some #(when (= "compact_marker" (:role %)) %) (get-in (h/db) [:chats chat-id :messages]))))))
