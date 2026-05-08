(ns integration.chat.subagent-test
  "Integration tests for subagent (`spawn_agent`) end-to-end communication
   between a parent chat and a server-managed subagent chat. Guards against
   regressions like the one introduced in v0.133.1 (and fixed in v0.133.2),
   where the chat-id validator rejected the deterministic `subagent-...`
   chat id used internally by `eca.features.tools.agent`, breaking every
   subagent invocation."
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [integration.eca :as eca]
   [integration.fixture :as fixture]
   [llm-mock.mocks :as llm.mocks]
   [matcher-combinators.core :as mc]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(eca/clean-after-test)

(defn ^:private matches?
  "Functional wrapper around matcher-combinators that can be used inside
   `(some ...)` predicates (the `match?` macro only works inside `is`)."
  [matcher actual]
  (mc/indicates-match? (mc/match matcher actual)))

(defn ^:private drain-content-events-until
  "Consume `chat/contentReceived` notifications until `done?` returns truthy
   for the latest event, or the safety cap is reached. Returns the vector of
   all events seen (including the terminal one). Each underlying await call
   has its own timeout, so this also fails fast if the wire goes silent."
  ([done?] (drain-content-events-until done? 200))
  ([done? max-events]
   (loop [collected []]
     (let [event (eca/client-awaits-server-notification :chat/contentReceived)
           collected' (conj collected event)]
       (cond
         (done? event) collected'
         (>= (count collected') max-events)
         (throw (ex-info "drain-content-events-until: cap reached without terminal event"
                         {:event-count (count collected') :last-event event}))
         :else (recur collected'))))))

(deftest spawn-subagent-end-to-end-test
  (eca/start-process!)
  (eca/request! (fixture/initialize-request))
  (eca/notify! (fixture/initialized-notification))

  (llm.mocks/set-case! :subagent-spawn-0)
  (let [resp (eca/request! (fixture/chat-prompt-request
                            {:model "openai/gpt-5.2"
                             :message "What can you find?"}))
        parent-chat-id (:chatId resp)]
    (is (match?
         {:chatId (m/pred string?)
          :model "openai/gpt-5.2"
          :status "prompting"}
         resp))

    ;; Drain notifications until we see the parent's final assistant text
    ;; "Final answer". The events for parent and subagent interleave so we
    ;; assert on the collected set rather than on a strict ordering.
    (let [events (drain-content-events-until
                  (fn [e]
                    (and (= parent-chat-id (:chatId e))
                         (= "assistant" (:role e))
                         (= "text" (-> e :content :type))
                         (= "Final answer" (-> e :content :text)))))
          subagent-chat-ids (->> events
                                 (keep :chatId)
                                 (filter #(string/starts-with? % "subagent-"))
                                 distinct)]

      (testing "exactly one server-managed subagent chat-id is used end-to-end"
        (is (= 1 (count subagent-chat-ids))
            (str "Expected exactly one subagent-* chat id, got: " (vec subagent-chat-ids))))

      (let [subagent-chat-id (first subagent-chat-ids)]

        (testing "parent emits toolCallRun for spawn_agent with subagent details"
          (is (some (fn [e]
                      (and (= parent-chat-id (:chatId e))
                           (= "assistant" (:role e))
                           (matches? {:type "toolCallRun"
                                      :name "spawn_agent"
                                      :details {:type "subagent"
                                                :subagentChatId subagent-chat-id
                                                :agentName "explorer"}}
                                     (:content e))))
                    events)
              "Expected toolCallRun for spawn_agent with details.type=\"subagent\" and matching subagentChatId"))

        (testing "subagent's user task arrives under the subagent chat-id with parentChatId set"
          (is (some (fn [e]
                      (and (= subagent-chat-id (:chatId e))
                           (= parent-chat-id (:parentChatId e))
                           (= "user" (:role e))
                           (matches? {:type "text" :text #"find files"} (:content e))))
                    events)
              "Expected subagent user message routed via subagent chat-id with parentChatId pointing to parent"))

        (testing "subagent assistant text arrives with parentChatId set to parent chat"
          (is (some (fn [e]
                      (and (= subagent-chat-id (:chatId e))
                           (= parent-chat-id (:parentChatId e))
                           (= "assistant" (:role e))
                           (matches? {:type "text" :text "Subagent done"} (:content e))))
                    events)
              "Expected assistant text \"Subagent done\" routed via subagent chat-id with parentChatId pointing to parent"))

        (testing "parent receives toolCalled carrying the agent's textual result"
          (is (some (fn [e]
                      (and (= parent-chat-id (:chatId e))
                           (= "assistant" (:role e))
                           (matches? {:type "toolCalled"
                                      :name "spawn_agent"
                                      :error false
                                      :outputs (m/embeds [{:type "text"
                                                           :text #"^## Agent 'explorer' Result"}])}
                                     (:content e))))
                    events)
              "Expected toolCalled for spawn_agent with output text starting with \"## Agent 'explorer' Result\"")))

      (testing "parent receives final assistant text after subagent completes"
        (is (some (fn [e]
                    (and (= parent-chat-id (:chatId e))
                         (= "assistant" (:role e))
                         (matches? {:type "text" :text "Final answer"} (:content e))))
                  events))))))
