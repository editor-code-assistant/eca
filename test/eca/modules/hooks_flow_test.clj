(ns eca.modules.hooks-flow-test
  "The user's shell hooks seen from ECA's entry points: prompts, tool calls,
   `/compact`, deleting a chat and shutting down, with each hook's output
   scripted. The texts, decisions and order are 0.171.1's."
  (:require
   [cheshire.core :as json]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.features.chat :as f.chat]
   [eca.features.chat.ops :as f.chat.ops]
   [eca.features.prompt :as f.prompt]
   [eca.features.tools :as f.tools]
   [eca.handlers :as handlers]
   [eca.llm-api :as llm-api]
   [eca.modules.compact-as-summary :as m.compact-as-summary]
   [eca.modules.hooks :as m.hooks]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(defn- hook
  "A hook of `type` with one shell action."
  [type & {:as opts}]
  (merge {:type type :actions [{:type "shell" :shell "true"}]} opts))

(defn- out
  "A hook's output: exit 0, with `m` as JSON."
  [m]
  {:exit 0 :out (json/generate-string m)})

(defn- exit-2 [err]
  {:exit 2 :err err})

(def ^:private runs* (atom []))

(defn- shell-mock
  "A `run-shell-cmd` answering each hook from `outputs`, hook name → its
   output or a fn of the hook's input (exit 0 and no output when absent).
   Records each hook's input in `runs*`."
  [outputs]
  (fn [{:keys [input]}]
    (let [data (json/parse-string input)
          answer (get outputs (get data "hook_name") {:exit 0 :out ""})]
      (swap! runs* conj data)
      (if (fn? answer) (answer data) answer))))

(defn- runs
  "The hooks that ran, as `[name type]`."
  []
  (mapv (juxt #(get % "hook_name") #(get % "hook_type")) @runs*))

(defn- run-input
  "The input of the first run of hook `hook-name`."
  [hook-name]
  (some #(when (= hook-name (get % "hook_name")) %) @runs*))

(def ^:private tools
  [{:name "tool_a" :full-name "eca__tool_a" :origin :native :server {:name "eca"}}
   {:name "compact_chat" :full-name "eca__compact_chat" :origin :native :server {:name "eca"}}])

(defn- call-tool-mock
  "Records each call in `calls*`; `compact_chat` runs for real, so a compaction
   gets its summary, and other tools return `result`."
  [calls* result]
  (fn [full-name arguments chat-id & _]
    (swap! calls* conj [full-name arguments])
    (if (= "eca__compact_chat" full-name)
      ((get-in m.compact-as-summary/definitions ["compact_chat" :handler]) arguments {:db* (h/db*) :chat-id chat-id})
      result)))

(defn- llm-mock
  "Answers each LLM request with the next of `steps`, a text or `{:tool-calls
   [..]}`, then \"done\". After a tool call batch, the turn goes on with the
   next step, as the provider would be asked again. Records each request's
   `:user-messages` and `:instructions` in `requests*`, and what a batch left
   for the model as `{:new-messages ..}`."
  [requests* steps]
  (let [steps* (atom steps)
        next-step! #(or (first (first (swap-vals! steps* rest))) "done")]
    (fn [{:keys [user-messages instructions on-first-response-received on-message-received
                 on-prepare-tool-call on-tools-called]}]
      (swap! requests* conj {:user-messages user-messages :instructions instructions})
      (on-first-response-received)
      (loop [step (next-step!)]
        (if-let [calls (:tool-calls step)]
          (do (doseq [{:keys [id full-name arguments]} calls]
                (on-prepare-tool-call {:id id :full-name full-name :arguments-text (json/generate-string arguments)}))
              (when-let [continue (on-tools-called calls)]
                (swap! requests* conj {:new-messages (:new-messages continue)})
                (recur (next-step!))))
          (do (on-message-received {:type :text :text step})
              (on-message-received {:type :finish})))))))

(defn- with-hooks*
  "Calls `f` with the hooks answering from `outputs`."
  [outputs f]
  (with-redefs [m.hooks/run-shell-cmd (shell-mock outputs)]
    (f)))

(defn- prompt!
  "Prompts `message`, in chat `chat-id` or a new one, with `hooks` configured
   and answering from `outputs`, the LLM answering from `steps`, tool_a's
   calls approved as `approval` and returning `result`. Returns `:chat-id`,
   the LLM `:requests` and the tool `:calls` that ran."
  [message {:keys [chat-id hooks outputs steps approval config result]
            :or {approval :allow
                 result {:error false :contents [{:type :text :text "ok"}]}}}]
  (let [requests* (atom [])
        calls* (atom [])]
    (h/config! (merge {:env "test" :hooks hooks} config))
    (swap! (h/db*) update :models merge {"openai/gpt-5.2" {:tools true}})
    (let [{:keys [chat-id]}
          (with-redefs [llm-api/sync-or-async-prompt! (llm-mock requests* steps)
                        llm-api/sync-prompt! (constantly nil)
                        f.tools/all-tools (constantly tools)
                        f.tools/approval (fn [_ tool & _] (if (= "compact_chat" (:name tool)) :allow approval))
                        f.tools/call-tool! (call-tool-mock calls* result)
                        ;; the chat start reads the config from the db
                        config/all (fn [& _] (h/config))
                        config/await-plugins-resolved! (constantly true)]
            (with-hooks* outputs
              #(f.chat/prompt (cond-> {:message message} chat-id (assoc :chat-id chat-id))
                              (h/db*) (h/messenger) (h/config) (h/metrics))))]
      {:chat-id chat-id :requests @requests* :calls @calls*})))

(defn- fresh! []
  (h/reset-components!)
  (reset! runs* []))

(defn- client-contents []
  (mapv :content (:chat-content-received (h/messages))))

(defn- system-texts
  "The text messages the client got as system ones."
  []
  (->> (:chat-content-received (h/messages))
       (filter #(and (= :system (:role %)) (= :text (get-in % [:content :type]))))
       (mapv #(get-in % [:content :text]))))

(defn- context-block [text]
  {:type :text :text (str "<additionalContext>\n" text "\n</additionalContext>")})

(defn- last-user-content
  "The content of the last user message of an LLM request."
  [{:keys [user-messages]}]
  (:content (last user-messages)))

(defn- history [chat-id]
  (get-in (h/db) [:chats chat-id :messages]))

(defn- tool-output
  "The output of tool call `id` in the chat's history: what the model gets."
  [chat-id id]
  (some #(when (and (= "tool_call_output" (:role %)) (= id (get-in % [:content :id])))
           (get-in % [:content :output]))
        (history chat-id)))

(def ^:private call-a
  {:id "call-1" :full-name "eca__tool_a" :arguments {"x" 1}})

(deftest pre-request-test
  (testing "a hook rewrites the prompt the model gets and the next hook sees it; each context is its own plain block"
    (fresh!)
    (let [{:keys [chat-id requests]}
          (prompt! "hello" {:hooks {"a" (hook "preRequest") "b" (hook "preRequest")}
                            :outputs {"a" (out {"replacedPrompt" "rewritten" "additionalContext" "ctx a"})
                                      "b" (out {"additionalContext" "ctx b"})}})]
      (is (= [["a" "preRequest"] ["b" "preRequest"]] (runs)))
      (is (= "hello" (get (run-input "a") "prompt")))
      (is (= "rewritten" (get (run-input "b") "prompt")))
      (is (= [{:type :text :text "rewritten"} (context-block "ctx a") (context-block "ctx b")]
             (last-user-content (first requests))))
      (is (= [{:type :text :text "rewritten"} (context-block "ctx a") (context-block "ctx b")]
             (:content (first (history chat-id))))
          "the history keeps the contexts as plain blocks")))

  (testing "exit 2 blocks the request and the next hooks still run; an invisible hook's stderr is the message"
    (fresh!)
    (let [{:keys [requests]}
          (prompt! "hello" {:hooks {"a" (hook "preRequest")
                                    "b" (hook "preRequest" :visible false)
                                    "c" (hook "preRequest")}
                            :outputs {"a" (exit-2 "nope a") "b" (exit-2 "nope b")}})]
      (is (= [["a" "preRequest"] ["b" "preRequest"] ["c" "preRequest"]] (runs)))
      (is (empty? requests))
      (is (= ["Request blocked by hook 'b': nope b"] (system-texts)))
      (is (= [["a" 2 "nope a"] ["c" 0 nil]]
             (->> (client-contents)
                  (filter #(= :hookActionFinished (:type %)))
                  (mapv (juxt :name :status :error))))
          "the invisible hook shows no block")))

  (testing "visible hooks blocking: the first one is named"
    (fresh!)
    (prompt! "hello" {:hooks {"a" (hook "preRequest") "b" (hook "preRequest")}
                      :outputs {"a" (exit-2 "nope a") "b" (exit-2 "nope b")}})
    (is (= ["Request blocked by hook 'a'."] (system-texts))))

  (testing "two invisible hooks blocking: one message each, in order"
    (fresh!)
    (prompt! "hello" {:hooks {"a" (hook "preRequest" :visible false) "b" (hook "preRequest" :visible false)}
                      :outputs {"a" (exit-2 "ra") "b" (exit-2 nil)}})
    (is (= ["Request blocked by hook 'a': ra" "Request blocked by hook 'b'."] (system-texts))))

  (testing "continue:false stops the turn and the next hooks"
    (fresh!)
    (let [{:keys [chat-id requests]}
          (prompt! "hello" {:hooks {"a" (hook "preRequest") "b" (hook "preRequest")}
                            :outputs {"a" (out {"continue" false "stopReason" "enough"})}})]
      (is (= [["a" "preRequest"]] (runs)))
      (is (empty? requests))
      (is (= ["Turn stopped by hook 'a': enough"] (system-texts)))
      (is (= :idle (get-in (h/db) [:chats chat-id :status])))))

  (testing "a block, then continue:false: only the stop message"
    (fresh!)
    (prompt! "hello" {:hooks {"a" (hook "preRequest" :visible false) "b" (hook "preRequest")}
                      :outputs {"a" (exit-2 "ra") "b" (out {"continue" false})}})
    (is (= ["Turn stopped by hook 'b'."] (system-texts)))))

(deftest chat-start-test
  (testing "a chatStart hook's context goes in the system prompt, once per chat"
    (fresh!)
    (let [{:keys [chat-id requests]}
          (prompt! "hello" {:hooks {"s" (hook "chatStart")} :outputs {"s" (out {"additionalContext" "start ctx"})}})]
      (is (= [["s" "chatStart"]] (runs)))
      (is (false? (get (run-input "s") "resumed")))
      (is (string/includes? (f.prompt/instructions->str (:instructions (first requests))) "start ctx"))
      (let [{:keys [requests]} (prompt! "again" {:chat-id chat-id
                                                 :hooks {"s" (hook "chatStart")}
                                                 :outputs {"s" (out {"additionalContext" "start ctx"})}})]
        (is (= [["s" "chatStart"]] (runs)) "not again for the same chat")
        (is (string/includes? (f.prompt/instructions->str (:instructions (first requests))) "start ctx")))))

  (testing "continue:false stops the first turn"
    (fresh!)
    (let [{:keys [requests]} (prompt! "hello" {:hooks {"s" (hook "chatStart")}
                                               :outputs {"s" (out {"continue" false "stopReason" "not now"})}})]
      (is (empty? requests))
      (is (= ["Turn stopped by hook 's': not now"] (system-texts))))))

(deftest post-request-test
  (testing "followUp is the next user message; the hook gets the response and whether a follow-up runs"
    (fresh!)
    (let [n* (atom 0)
          {:keys [requests]} (prompt! "hello" {:hooks {"p" (hook "postRequest")}
                                               :outputs {"p" (fn [_] (if (= 1 (swap! n* inc)) (out {"followUp" "go on"}) (out {})))}
                                               :steps ["first answer" "second answer"]})]
      (is (= 2 (count requests)))
      (is (= [{:type :text :text "go on"}] (last-user-content (second requests))))
      (is (= ["first answer" "second answer"] (mapv #(get % "response") @runs*)))
      (is (= [false true] (mapv #(get % "follow_up_active") @runs*)))))

  (testing "exit 2's stderr is a followUp"
    (fresh!)
    (let [n* (atom 0)
          {:keys [requests]} (prompt! "hello" {:hooks {"p" (hook "postRequest")}
                                               :outputs {"p" (fn [_] (if (= 1 (swap! n* inc)) (exit-2 "fix it") (out {})))}})]
      (is (= [{:type :text :text "fix it"}] (last-user-content (second requests))))))

  (testing "continue:false ends the turn with a message and no follow-up"
    (fresh!)
    (let [{:keys [requests]} (prompt! "hello" {:hooks {"p" (hook "postRequest")}
                                               :outputs {"p" (out {"continue" false "stopReason" "stop" "followUp" "x"})}})]
      (is (= 1 (count requests)))
      (is (= ["Turn stopped by hook 'p': stop"] (system-texts))))))

(defn- prompt-rejecting-asks!
  "Like `prompt!`, rejecting each tool call that waits for approval. Returns
   `prompt!`'s result and `:asked`, the ids of the calls that waited."
  [message opts]
  (let [asked* (atom [])
        result (future (prompt! message opts))]
    (loop [n 0]
      (when (and (< n 500) (not (realized? result)))
        (doseq [[chat-id chat] (:chats (h/db))
                [id {:keys [status]}] (:tool-calls chat)
                :when (= :waiting-approval status)]
          (swap! asked* conj id)
          (f.chat/tool-call-reject {:chat-id chat-id :tool-call-id id} (h/db*) (h/messenger) (h/config) (h/metrics)))
        (Thread/sleep 10)
        (recur (inc n))))
    (assoc (deref result 5000 nil) :asked (distinct @asked*))))

(deftest pre-tool-call-test
  (testing "the hook gets the call; updatedInput changes the arguments the tool gets"
    (fresh!)
    (let [{:keys [calls]} (prompt! "go" {:hooks {"t" (hook "preToolCall")}
                                         :outputs {"t" (out {"updatedInput" {"x" 2 "y" 3}})}
                                         :steps [{:tool-calls [call-a]}]})]
      (is (match? {"tool_name" "tool_a" "server" "eca" "tool_input" {"x" 1} "approval" "allow" "tool_call_id" "call-1"}
                  (run-input "t")))
      (is (= [["eca__tool_a" {"x" 2 "y" 3}]] calls))))

  (testing "approval ask makes an allowed call ask"
    (fresh!)
    (let [{:keys [asked calls]} (prompt-rejecting-asks! "go" {:hooks {"t" (hook "preToolCall")}
                                                              :outputs {"t" (out {"approval" "ask"})}
                                                              :steps [{:tool-calls [call-a]}]})]
      (is (= ["call-1"] asked))
      (is (empty? calls))))

  (testing "a hook's allow doesn't loosen a config ask, and the hook sees ask"
    (fresh!)
    (let [{:keys [asked]} (prompt-rejecting-asks! "go" {:hooks {"t" (hook "preToolCall")}
                                                        :outputs {"t" (out {"approval" "allow"})}
                                                        :steps [{:tool-calls [call-a]}]
                                                        :approval :ask})]
      (is (= ["call-1"] asked))
      (is (= "ask" (get (run-input "t") "approval")))))

  (testing "approval deny rejects the call; the model gets the hook's additionalContext as the reason"
    (fresh!)
    (let [{:keys [chat-id calls]} (prompt! "go" {:hooks {"t" (hook "preToolCall")}
                                                 :outputs {"t" (out {"approval" "deny" "additionalContext" "not here"})}
                                                 :steps [{:tool-calls [call-a]}]})]
      (is (empty? calls))
      (is (string/includes? (-> (tool-output chat-id "call-1") :contents first :text) "Reason: not here"))
      (is (match? [{:type :toolCallRejected :id "call-1" :reason :hookRejected}]
                  (filterv #(= :toolCallRejected (:type %)) (client-contents))))))

  (testing "exit 2 rejects the call with its stderr as the reason"
    (fresh!)
    (let [{:keys [chat-id calls]} (prompt! "go" {:hooks {"t" (hook "preToolCall")}
                                                 :outputs {"t" (exit-2 "against policy")}
                                                 :steps [{:tool-calls [call-a]}]})]
      (is (empty? calls))
      (is (string/includes? (-> (tool-output chat-id "call-1") :contents first :text) "Reason: against policy"))))

  (testing "continue:false stops the turn before the tool runs"
    (fresh!)
    (let [{:keys [chat-id calls requests]} (prompt! "go" {:hooks {"t" (hook "preToolCall")}
                                                          :outputs {"t" (out {"continue" false "stopReason" "halt"})}
                                                          :steps [{:tool-calls [call-a]}]})]
      (is (empty? calls))
      (is (= 1 (count requests)) "the model isn't asked again")
      (is (string/includes? (-> (tool-output chat-id "call-1") :contents first :text) "Reason: Tool call stopped by hook"))
      (is (= ["Turn stopped by hook 't': halt"] (system-texts)))))

  (testing "a config deny: the hook sees deny and can't allow the call"
    (fresh!)
    (let [{:keys [calls]} (prompt! "go" {:hooks {"t" (hook "preToolCall")}
                                         :outputs {"t" (out {"approval" "allow"})}
                                         :steps [{:tool-calls [call-a]}]
                                         :approval :deny})]
      (is (= "deny" (get (run-input "t") "approval")))
      (is (empty? calls)))))

(deftest post-tool-call-test
  (testing "exit 2 hides the result from the model, not from the client, which got it before the hook ran"
    (fresh!)
    (let [{:keys [chat-id requests]} (prompt! "go" {:hooks {"u" (hook "postToolCall")}
                                                    :outputs {"u" (exit-2 "hidden")}
                                                    :steps [{:tool-calls [call-a]}]})
          contents (client-contents)
          index-of (fn [pred] (first (keep-indexed #(when (pred %2) %1) contents)))]
      (is (match? {"tool_name" "tool_a" "tool_input" {"x" 1} "tool_response" "ok\n" "error" false}
                  (run-input "u")))
      (is (= [{:type :text :text "hidden"}] (:contents (tool-output chat-id "call-1"))))
      (is (match? [{:type :toolCalled :id "call-1" :outputs [{:type :text :text "ok"}]}]
                  (filterv #(= :toolCalled (:type %)) contents)))
      (is (< (index-of #(= :toolCalled (:type %))) (index-of #(= :hookActionStarted (:type %)))))
      (is (some #(= [{:type :text :text "hidden"}] (get-in % [:content :output :contents]))
                (:new-messages (second requests)))
          "the model's next request has the hidden result")))

  (testing "replacedOutput replaces the result for the model; additionalContext follows it as a plain block"
    (fresh!)
    (let [{:keys [chat-id]} (prompt! "go" {:hooks {"u" (hook "postToolCall") "v" (hook "postToolCall")}
                                           :outputs {"u" (out {"replacedOutput" "short"})
                                                     "v" (out {"additionalContext" "note"})}
                                           :steps [{:tool-calls [call-a]}]})]
      (is (= [{:type :text :text "short"} (context-block "note")] (:contents (tool-output chat-id "call-1"))))))

  (testing "a later replacement drops the contexts before it"
    (fresh!)
    (let [{:keys [chat-id]} (prompt! "go" {:hooks {"u" (hook "postToolCall") "v" (hook "postToolCall")}
                                           :outputs {"u" (out {"additionalContext" "note"})
                                                     "v" (exit-2 nil)}
                                           :steps [{:tool-calls [call-a]}]})]
      (is (= [{:type :text :text "[Tool output hidden by hook]"}] (:contents (tool-output chat-id "call-1"))))))

  (testing "continue:false ends the turn after the batch"
    (fresh!)
    (let [{:keys [calls requests]} (prompt! "go" {:hooks {"u" (hook "postToolCall")}
                                                  :outputs {"u" (out {"continue" false "stopReason" "done here"})}
                                                  :steps [{:tool-calls [call-a]}]})]
      (is (= 1 (count calls)))
      (is (= 1 (count requests)) "the model isn't asked again")
      (is (= ["Turn stopped by hook 'u': done here"] (system-texts)))))

  (testing "a failed call skips the hooks unless runOnError"
    (fresh!)
    (prompt! "go" {:hooks {"u" (hook "postToolCall") "w" (hook "postToolCall" :runOnError true)}
                   :steps [{:tool-calls [call-a]}]
                   :result {:error true :contents [{:type :text :text "boom"}]}})
    (is (= [["w" "postToolCall"]] (runs)))))

(deftest chat-status-changed-test
  (testing "the hook gets each new status, once"
    (fresh!)
    (prompt! "hello" {:hooks {"w" (hook "chatStatusChanged")}})
    (is (= ["running" "idle"] (mapv #(get % "status") @runs*)))
    (is (match? {"awaiting_user_input" false "pending_approval_tool_call_ids" [] "running_tool_call_ids" []}
                (first @runs*)))
    (is (not-any? #(#{:hookActionStarted :hookActionFinished} (:type %)) (client-contents))
        "advisory: nothing shows in the chat")))

(def ^:private compact-config
  {:prompts {:compact "Summarize. {{additionalUserInput}}"}})

(def ^:private compact-steps
  [{:tool-calls [{:id "k-1" :full-name "eca__compact_chat" :arguments {"summary" "The summary."}}]}])

(deftest compact-test
  (testing "preCompact exit 2 blocks the compaction; the hook gets the trigger and instructions"
    (fresh!)
    (let [{:keys [requests]} (prompt! "/compact focus on tests" {:hooks {"c" (hook "preCompact")}
                                                                 :outputs {"c" (exit-2 "no")}
                                                                 :config compact-config})]
      (is (empty? requests))
      (is (match? {"triggered" "manual" "custom_instructions" "focus on tests"} (run-input "c")))
      (is (= ["Compaction blocked by hook 'c'."] (system-texts)))))

  (testing "preCompact continue:false stops the turn"
    (fresh!)
    (prompt! "/compact" {:hooks {"c" (hook "preCompact")}
                         :outputs {"c" (out {"continue" false "stopReason" "later"})}
                         :config compact-config})
    (is (= ["Turn stopped by hook 'c': later"] (system-texts))))

  (testing "preRequest hooks run for the compaction's request too, with what was typed"
    (fresh!)
    (prompt! "/compact focus" {:hooks {"r" (hook "preRequest")} :config compact-config :steps compact-steps})
    (is (= "/compact focus" (get (run-input "r") "prompt"))))

  (testing "postCompact gets the summary; its context follows the summary as a plain block"
    (fresh!)
    (let [{:keys [chat-id]} (prompt! "/compact" {:hooks {"pc" (hook "postCompact")}
                                                 :outputs {"pc" (out {"additionalContext" "after"})}
                                                 :config compact-config
                                                 :steps compact-steps})
          messages (history chat-id)
          summary (last messages)]
      (is (match? {"triggered" "manual" "compact_summary" "The summary."} (run-input "pc")))
      (is (= "compact_marker" (:role (last (butlast messages)))))
      (is (= (context-block "after") (last (:content summary))))))

  (testing "postCompact continue:false stops the turn"
    (fresh!)
    (prompt! "/compact" {:hooks {"pc" (hook "postCompact")}
                         :outputs {"pc" (out {"continue" false "stopReason" "enough"})}
                         :config compact-config
                         :steps compact-steps})
    (is (some #{"Turn stopped by hook 'pc': enough"} (system-texts)))))

(deftest chat-end-test
  (testing "a chatEnd hook runs when a chat is deleted, with the chat's data"
    (fresh!)
    (let [{:keys [chat-id]} (prompt! "hello" {:hooks {"e" (hook "chatEnd")}})]
      (is (empty? (runs)))
      (with-hooks* {} #(f.chat.ops/delete-chat {:chat-id chat-id} (h/db*) (h/messenger) (h/config) (h/metrics)))
      (is (= [["e" "chatEnd"]] (runs)))
      (is (match? {"chat_id" chat-id "agent" "code"} (run-input "e"))))))

(deftest session-end-test
  (testing "a sessionEnd hook runs when ECA shuts down, with the session's data"
    (fresh!)
    (h/config! {:hooks {"z" (hook "sessionEnd")}})
    (with-hooks* {} #(handlers/shutdown {:db* (h/db*) :config (h/config) :metrics (h/metrics)}))
    (is (= [["z" "sessionEnd"]] (runs)))
    (is (contains? (run-input "z") "workspaces"))))
