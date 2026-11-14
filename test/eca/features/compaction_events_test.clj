(ns eca.features.compaction-events-test
  "The compaction events modules get, :chat/before-compact, :chat/compact
   and :chat/after-compact, and eca.api/checkpoint!."
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.api :as eca]
   [eca.features.chat :as f.chat]
   [eca.features.chat.lifecycle :as lifecycle]
   [eca.features.commands :as f.commands]
   [eca.modules :as modules]
   [eca.modules.hooks :as f.hooks]
   [eca.shared :as shared]
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

(def ^:private base-config {:prompts {:compact "Summarize. {{additionalUserInput}}"}})

(def ^:private disabled (assoc base-config :modules {:eca/compact-as-summary {:enabled false}}))

(defn- ctx
  ([] (ctx base-config))
  ([config]
   {:chat-id chat-id :db* (h/db*) :config config :messenger (h/messenger) :metrics (h/metrics)
    :full-model "openai/gpt-5.2" :agent "code" :all-tools [] :instructions {} :user-messages []}))

(defn- setup-chat! []
  (h/reset-messenger!)
  (swap! (h/db*) assoc-in [:chats chat-id] {:id chat-id :model "openai/gpt-5.2" :messages history
                                            :usage {:last-input-tokens 900}}))

(defn- system-texts []
  (->> (:chat-content-received (h/messages))
       (filter #(= :system (:role %)))
       (keep #(get-in % [:content :text]))))

(defn- summarize
  "A :chat/compact handler: it writes a checkpoint with a summary."
  [ctx {:keys [instructions]}]
  (eca/checkpoint! ctx {:messages [{:role "user" :content (str "Summary." (when instructions (str " " instructions)))}]
                        :label "Compacted chat (acme)"
                        :data {:version 1}})
  {:status :done :message "Compacted by acme."})

(defn- context-text [s]
  (str "<additionalContext>\n" s "\n</additionalContext>"))

(deftest checkpoint-test
  (setup-chat!)
  (with-module! {:chat/message-added (constantly nil)})
  (let [module-ctx (assoc (ctx) :module-id :acme/compact)]
    (eca/checkpoint! module-ctx {:messages [{:role "user" :content "The summary."}]
                                 :label "Compacted (acme)"
                                 :data {:turns 2}})
    (let [messages (get-in (h/db) [:chats chat-id :messages])]
      (testing "a marker with the module, label and data, then the messages, after the whole history"
        (is (match? (conj history
                          {:role "compact_marker" :content {:module :acme/compact :label "Compacted (acme)" :data {:turns 2}}}
                          {:role "user" :content [{:type :text :text "The summary."}]})
                    messages)))
      (testing "the model sees only what comes after it"
        (is (match? [{:role "user" :content [{:text "The summary."}]}]
                    (shared/messages-after-last-compact-marker messages)))))
    (testing "the usage resets, and the client shows the label"
      (is (nil? (get-in (h/db) [:chats chat-id :usage])))
      (is (some #{"Compacted (acme)"} (system-texts))))
    (testing "modules see the new messages"
      (is (match? [[:chat/message-added {:message {:role "compact_marker"}}]
                   [:chat/message-added {:message {:role "user"}}]]
                  @events*)))
    (testing "the data must be plain data"
      (is (thrown? Exception (eca/checkpoint! module-ctx {:messages [] :data {:f (fn [])}}))))))

(deftest checkpoint-replay-test
  (testing "a module's checkpoint shows its label when the chat is opened again"
    (is (match? [{:role :system :content {:text "── Compacted (acme) ──"}}]
                (#'f.chat/message-content->chat-content "compact_marker" {:module :acme/compact :label "Compacted (acme)"} nil))))
  (testing "markers of older chats show as before"
    (is (match? [{:content {:text "── Chat auto-compacted ──"}}]
                (#'f.chat/message-content->chat-content "compact_marker" {:auto? true} nil)))
    (is (match? [{:content {:text "── Chat compacted ──"}}]
                (#'f.chat/message-content->chat-content "compact_marker" {:auto? false} nil)))))

(deftest compact-command-with-a-module-test
  (testing "a module compacts the chat on /compact, even with eca/compact-as-summary disabled"
    (setup-chat!)
    (with-module! {:chat/before-compact (constantly nil)
                   :chat/compact summarize
                   :chat/after-compact (constantly {:additional-context "Keep the plan."})})
    (is (= {:type :new-chat-status :status :idle}
           (f.commands/handle-command! "compact" ["focus" "on" "tests"] (ctx disabled))))
    (is (match? (conj history
                      {:role "compact_marker" :content {:module :acme/compact :label "Compacted chat (acme)"}}
                      {:role "user" :content [{:type :text :text "Summary. focus on tests"}
                                              {:type :text :text (context-text "Keep the plan.")}]})
                (get-in (h/db) [:chats chat-id :messages])))
    (is (some #{"Compacted by acme."} (system-texts)))
    (is (match? [[:chat/before-compact {:trigger :manual :instructions "focus on tests" :chat-id chat-id}]
                 [:chat/compact {:trigger :manual :instructions "focus on tests"}]
                 [:chat/after-compact {:trigger :manual
                                       :checkpoint {:marker {:role "compact_marker"}
                                                    :messages [{:role "user"}]}}]]
                @events*)))
  (testing "without a module that compacts, it says so, and no event is sent"
    (with-module! {:chat/before-compact (constantly nil)})
    (is (match? {:type :chat-messages
                 :chats {chat-id {:messages [{:content [{:text "No compaction module handled this chat."}]}]}}}
                (f.commands/handle-command! "compact" [] (ctx disabled))))
    (is (empty? @events*))))

(deftest before-compact-test
  (testing "a module blocks the compaction; then no preCompact hook runs"
    (setup-chat!)
    (with-module! {:chat/before-compact (constantly {:block "Not during a deploy."})})
    (let [hooks* (atom [])]
      (with-redefs [f.hooks/trigger-if-matches! (fn [hook-type & _] (swap! hooks* conj hook-type) nil)]
        (is (= {:type :new-chat-status :status :idle}
               (f.commands/handle-command! "compact" [] (ctx)))))
      (is (not-any? #{:preCompact} @hooks*)))
    (is (some #{"Compaction blocked by module 'acme/compact': Not during a deploy."} (system-texts)))
    (is (= history (get-in (h/db) [:chats chat-id :messages]))))
  (testing "or stops the turn"
    (setup-chat!)
    (with-module! {:chat/before-compact (constantly {:stop "Out of budget."})})
    (f.commands/handle-command! "compact" [] (ctx))
    (is (some #{"Turn stopped by module 'acme/compact': Out of budget."} (system-texts))))
  (testing "or changes the instructions the built-in compaction gets"
    (setup-chat!)
    (with-module! {:chat/before-compact (constantly {:instructions "keep the plan"})})
    (let [{:keys [type prompt]} (f.commands/handle-command! "compact" ["focus"] (ctx))]
      (is (= :send-prompt type))
      (is (string/includes? prompt "summarization: keep the plan."))
      (is (match? [[:chat/before-compact {:instructions "focus"}]] @events*)))))

(deftest built-in-compaction-test
  (testing "the built-in summary sends :chat/after-compact too, and a module may stop the turn there"
    (setup-chat!)
    (swap! (h/db*) update-in [:chats chat-id] assoc :compact-done? true :last-summary "The summary.")
    (with-module! {:chat/after-compact (constantly {:additional-context "Keep the plan." :stop "Review first."})})
    (let [hooks* (atom [])
          result (with-redefs [f.hooks/trigger-if-matches! (fn [hook-type & _] (swap! hooks* conj hook-type) nil)]
                   (lifecycle/complete-compact! (ctx) "auto"))]
      (is (match? {:compacted? true :stop-turn? true :stop-reason "Review first." :stop-module :acme/compact} result))
      (is (not-any? #{:postCompact} @hooks*)))
    (is (match? [[:chat/after-compact {:trigger :auto
                                       :checkpoint {:marker {:role "compact_marker" :content {:auto? true}}
                                                    :messages [{:role "user" :content [{:text #"The summary\.$"}]}]}}]]
                @events*))
    (is (match? {:role "user" :content [{:text #"The summary\.$"} {:text (context-text "Keep the plan.")}]}
                (last (get-in (h/db) [:chats chat-id :messages]))))))

(deftest mid-turn-module-compaction-test
  (testing "a module's compaction resumes the interrupted turn when it says so"
    (setup-chat!)
    (with-module! {:chat/before-compact (constantly nil)
                   :chat/compact (fn [ctx event] (summarize ctx event) {:status :done :resume? true})})
    (let [resumed* (atom nil)]
      (with-redefs-fn {#'f.chat/resume-after-compact! (fn [chat-ctx user-messages]
                                                        (reset! resumed* [chat-ctx user-messages])
                                                        nil)}
        #(#'f.chat/trigger-auto-compact! (assoc (ctx) :compact-trigger :overflow) [] [{:role "user" :content "go on"}]))
      (is (= [{:role "user" :content "go on"}] (second @resumed*)))
      (is (not (contains? (first @resumed*) :compact-trigger)) "the trigger is only for this compaction")
      (is (= 1 (:auto-compactions (first @resumed*)))))
    (is (match? [[:chat/before-compact {:trigger :overflow}] [:chat/compact {:trigger :overflow}]] @events*))
    (is (match? {:role "compact_marker" :content {:module :acme/compact}}
                (nth (get-in (h/db) [:chats chat-id :messages]) 2))))
  (testing "without :resume?, the turn goes on too, as after the built-in compaction; :resume? false ends it"
    (doseq [[answer resumes?] [[{:status :done} true] [{:status :done :resume? false} false]]]
      (setup-chat!)
      (with-module! {:chat/compact (fn [ctx event] (summarize ctx event) answer)})
      (let [resumed* (atom false)
            finished* (atom false)]
        (with-redefs-fn {#'f.chat/resume-after-compact! (fn [& _] (reset! resumed* true) nil)
                         #'lifecycle/finish-chat-prompt! (fn [& _] (reset! finished* true) nil)}
          #(#'f.chat/trigger-auto-compact! (ctx) [] []))
        (is (= [resumes? (not resumes?)] [@resumed* @finished*]) (pr-str answer)))))
  (testing "a failed compaction stops the turn"
    (setup-chat!)
    (with-module! {:chat/compact (constantly {:status :failed :message "The summarizer is down."})})
    (let [stopped* (atom false)]
      (with-redefs [lifecycle/finish-chat-prompt-stopped! (fn [& _] (reset! stopped* true))]
        (#'f.chat/trigger-auto-compact! (ctx) [] []))
      (is @stopped*)
      (is (some #{"The summarizer is down."} (system-texts)))
      (is (= history (get-in (h/db) [:chats chat-id :messages]))))))
