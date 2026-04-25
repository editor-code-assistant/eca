(ns eca.features.chat-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.features.chat :as f.chat]
   [eca.features.chat.lifecycle :as lifecycle]
   [eca.features.prompt :as f.prompt]
   [eca.features.tools :as f.tools]
   [eca.features.tools.mcp :as f.mcp]
   [eca.llm-api :as llm-api]
   [eca.test-helper :as h]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(defn ^:private prompt! [params mocks]
  (let [{:keys [chat-id] :as resp}
        (with-redefs [llm-api/sync-or-async-prompt! (:api-mock mocks)
                      llm-api/sync-prompt! (constantly nil)
                      f.tools/call-tool! (:call-tool-mock mocks)
                      f.tools/all-tools (:all-tools-mock mocks)
                      f.tools/approval (constantly :allow)
                      config/await-plugins-resolved! (constantly true)]
          (h/config! {:env "test"})
          (swap! (h/db*) update :models
                 (fn [models]
                   (merge {"openai/gpt-5.2" {:tools true}}
                          (or models {}))))
          (f.chat/prompt params (h/db*) (h/messenger) (h/config) (h/metrics)))]
    (is (match? {:chat-id string? :status :prompting} resp))
    {:chat-id chat-id}))

(defn ^:private deep-sleep
  "Sleep for the given duration in milliseconds, ignoring interrupts.
   Continues sleeping until the full duration has elapsed."
  [millis]
  (let [deadline (+ (System/currentTimeMillis) millis)]
    (loop [remaining (- deadline (System/currentTimeMillis))]
      (when (pos? remaining)
        (try
          (Thread/sleep (long remaining))
          (catch InterruptedException _))
        (recur (- deadline (System/currentTimeMillis)))))))

(deftest prompt-basic-test
  (testing "Simple hello"
    (h/reset-components!)
    (let [{:keys [chat-id]}
          (prompt!
           {:message "Hey!"}
           {:all-tools-mock (constantly [])
            :api-mock
            (fn [{:keys [on-first-response-received
                         on-message-received]}]
              (on-first-response-received {:type :text :text "Hey"})
              (on-message-received {:type :text :text "Hey"})
              (on-message-received {:type :text :text " you!"})
              (on-message-received {:type :finish}))})]
      (is (match?
           {chat-id {:id chat-id
                     :messages [{:role "user" :content [{:type :text :text "Hey!"}]}
                                {:role "assistant" :content [{:type :text :text "Hey you!"}]}]}}
           (:chats (h/db))))
      (is (match?
           {:chat-content-received
            [{:chat-id chat-id
              :content {:type :text :text "Hey!\n"}
              :role :user}
             {:chat-id chat-id
              :content {:type :progress :state :running :text "Waiting model"}
              :role :system}
             {:chat-id chat-id
              :content {:type :progress :state :running :text "Generating"}
              :role :system}
             {:chat-id chat-id
              :content {:type :text :text "Hey"}
              :role :assistant}
             {:chat-id chat-id
              :content {:type :text :text " you!"}
              :role :assistant}
             {:chat-id chat-id
              :content {:state :finished :type :progress}
              :role :system}]}
           (h/messages)))))
  (testing "LLM error"
    (h/reset-components!)
    (let [{:keys [chat-id]}
          (prompt!
           {:message "Hey!"}
           {:all-tools-mock (constantly [])
            :api-mock
            (fn [{:keys [on-error]}]
              (on-error {:message "Error from mocked API"}))})]
      (is (match?
           {chat-id {:id chat-id :messages m/absent}}
           (:chats (h/db))))
      (is (match?
           {:chat-content-received
            [{:chat-id chat-id
              :content {:type :text :text "Hey!\n"}
              :role :user}
             {:chat-id chat-id
              :content {:type :progress :state :running :text "Waiting model"}
              :role :system}
             {:chat-id chat-id
              :content {:type :text :text "\n\nError from mocked API"}
              :role :system}
             {:chat-id chat-id
              :content {:state :finished :type :progress}
              :role :system}]}
           (h/messages))))))

(deftest prompt-multiple-text-interaction-test
  (testing "Chat history"
    (h/reset-components!)
    (let [res-1
          (prompt!
           {:message "Count with me: 1 mississippi"}
           {:all-tools-mock (constantly [])
            :api-mock
            (fn [{:keys [on-first-response-received
                         on-message-received]}]
              (on-first-response-received {:type :text :text "2"})
              (on-message-received {:type :text :text "2"})
              (on-message-received {:type :text :text " mississippi"})
              (on-message-received {:type :finish}))})
          chat-id-1 (:chat-id res-1)]
      (is (match?
           {chat-id-1 {:id chat-id-1
                       :messages [{:role "user" :content [{:type :text :text "Count with me: 1 mississippi"}]}
                                  {:role "assistant" :content [{:type :text :text "2 mississippi"}]}]}}
           (:chats (h/db))))
      (is (match?
           {:chat-content-received
            [{:chat-id chat-id-1
              :content {:type :text :text "Count with me: 1 mississippi\n"}
              :role :user}
             {:chat-id chat-id-1
              :content {:type :progress :state :running :text "Waiting model"}
              :role :system}
             {:chat-id chat-id-1
              :content {:type :progress :state :running :text "Generating"}
              :role :system}
             {:chat-id chat-id-1
              :content {:type :text :text "2"}
              :role :assistant}
             {:chat-id chat-id-1
              :content {:type :text :text " mississippi"}
              :role :assistant}
             {:chat-id chat-id-1
              :content {:state :finished :type :progress}
              :role :system}]}
           (h/messages)))
      (h/reset-messenger!)
      (let [res-2
            (prompt!
             {:message "3 mississippi"
              :chat-id chat-id-1}
             {:all-tools-mock (constantly [])
              :api-mock
              (fn [{:keys [on-first-response-received
                           on-message-received]}]
                (on-first-response-received {:type :text :text "4"})
                (on-message-received {:type :text :text "4"})
                (on-message-received {:type :text :text " mississippi"})
                (on-message-received {:type :finish}))})
            chat-id-2 (:chat-id res-2)]
        (is (match?
             {chat-id-2 {:id chat-id-2
                         :messages [{:role "user" :content [{:type :text :text "Count with me: 1 mississippi"}]}
                                    {:role "assistant" :content [{:type :text :text "2 mississippi"}]}
                                    {:role "user" :content [{:type :text :text "3 mississippi"}]}
                                    {:role "assistant" :content [{:type :text :text "4 mississippi"}]}]}}
             (:chats (h/db))))
        (is (match?
             {:chat-content-received
              [{:chat-id chat-id-2
                :content {:type :text :text "3 mississippi\n"}
                :role :user}
               {:chat-id chat-id-2
                :content {:type :progress :state :running :text "Waiting model"}
                :role :system}
               {:chat-id chat-id-2
                :content {:type :progress :state :running :text "Generating"}
                :role :system}
               {:chat-id chat-id-2
                :content {:type :text :text "4"}
                :role :assistant}
               {:chat-id chat-id-2
                :content {:type :text :text " mississippi"}
                :role :assistant}
               {:chat-id chat-id-2
                :content {:state :finished :type :progress}
                :role :system}]}
             (h/messages)))))))

(defn ^:private prompt-with-title!
  "Like prompt! but accepts a :sync-prompt-mock for title generation testing.
   Accepts optional :config in mocks to override default config."
  [params mocks]
  (let [api-mock (fn [{:keys [on-first-response-received on-message-received]}]
                   (on-first-response-received {:type :text :text "response"})
                   (on-message-received {:type :text :text "response"})
                   (on-message-received {:type :finish}))
        {:keys [chat-id] :as resp}
        (with-redefs [llm-api/sync-or-async-prompt! api-mock
                      llm-api/sync-prompt! (:sync-prompt-mock mocks)
                      f.tools/call-tool! (constantly nil)
                      f.tools/all-tools (constantly [])
                      f.tools/approval (constantly :allow)
                      config/await-plugins-resolved! (constantly true)]
          (h/config! (merge {:env "test" :chat {:title true}}
                            (:config mocks)))
          (swap! (h/db*) update :models
                 (fn [models]
                   (merge {"openai/gpt-5.2" {:tools true}}
                          (or models {}))))
          (f.chat/prompt params (h/db*) (h/messenger) (h/config) (h/metrics)))]
    (is (match? {:chat-id string? :status :prompting} resp))
    {:chat-id chat-id}))

(deftest sanitize-title-test
  (testing "strips markdown headers"
    (is (= "My Title" (#'f.chat/sanitize-title "## My Title")))
    (is (= "My Title" (#'f.chat/sanitize-title "### My Title"))))
  (testing "takes first non-blank line"
    (is (= "First Line" (#'f.chat/sanitize-title "\n\n  First Line\nSecond Line"))))
  (testing "skips a bare markdown header line when more content follows"
    ;; Regression: Opus in planning mode sometimes answers the title prompt with
    ;; '## Understand' as the first line. Skip bare headers when a real line follows.
    (is (= "actual body line" (#'f.chat/sanitize-title "## Understand\n\nactual body line")))
    (is (= "the real title" (#'f.chat/sanitize-title "# Explore\nthe real title"))))
  (testing "keeps a single-line header when nothing else follows"
    (is (= "Only Title" (#'f.chat/sanitize-title "## Only Title"))))
  (testing "strips control characters"
    (is (= "clean text" (#'f.chat/sanitize-title "clean\u0000 \u001ftext"))))
  (testing "collapses whitespace"
    (is (= "hello world" (#'f.chat/sanitize-title "hello   world"))))
  (testing "truncates to 40 chars"
    (is (= 40 (count (#'f.chat/sanitize-title (apply str (repeat 60 "a")))))))
  (testing "returns empty string for blank input"
    (is (= "" (#'f.chat/sanitize-title "  \n  \n  "))))
  (testing "returns nil for nil input"
    (is (nil? (#'f.chat/sanitize-title nil)))))

(deftest conversation-title-transcript-test
  (testing "renders user/assistant messages as a plain-text transcript"
    (is (= "user: hi\n\nassistant: hello"
           (#'f.chat/conversation->title-transcript
            [{:role "user" :content [{:type :text :text "hi"}]}
             {:role "assistant" :content [{:type :text :text "hello"}]}]))))
  (testing "ignores non-text parts"
    (is (= "user: hi"
           (#'f.chat/conversation->title-transcript
            [{:role "user" :content [{:type :text :text "hi"}
                                     {:type :image :url "x"}]}]))))
  (testing "skips messages with no text content"
    (is (= "user: hi"
           (#'f.chat/conversation->title-transcript
            [{:role "user" :content [{:type :text :text "hi"}]}
             {:role "assistant" :content []}]))))
  (testing "truncates very long messages"
    (let [long-text (apply str (repeat 3000 "x"))
          out (#'f.chat/conversation->title-transcript
               [{:role "user" :content [{:type :text :text long-text}]}])]
      (is (string/ends-with? out " …"))
      (is (< (count out) 2100))))
  (testing "accepts string content for robustness"
    (is (= "user: hi"
           (#'f.chat/conversation->title-transcript
            [{:role "user" :content "hi"}])))))

(deftest title-generation-test
  (testing "generates title on first message and re-generates at third with full context"
    (h/reset-components!)
    (let [sync-prompt-calls* (atom [])
          call-count* (atom 0)
          sync-mock (fn [params]
                      (swap! sync-prompt-calls* conj params)
                      {:output-text (str "Title " (swap! call-count* inc))})
          {:keys [chat-id]} (prompt-with-title! {:message "Help me debug"} {:sync-prompt-mock sync-mock})]
      ;; Message 1: should generate title
      (is (= 1 (count @sync-prompt-calls*))
          "Should call sync-prompt! on first message")
      (is (= "Title 1" (get-in (h/db) [:chats chat-id :title])))
      (is (= 1 (count (:user-messages (first @sync-prompt-calls*))))
          "First title uses only the current user message")

        ;; Message 2: should NOT re-generate title
      (h/reset-messenger!)
      (prompt-with-title! {:message "Also refactor" :chat-id chat-id} {:sync-prompt-mock sync-mock})
      (is (= 1 (count @sync-prompt-calls*))
          "Should NOT call sync-prompt! on second message")
      (is (= "Title 1" (get-in (h/db) [:chats chat-id :title])))

        ;; Inject noisy entries into history before the retitle fires, to
        ;; exercise the role/content cleanup applied to :past-messages.
      (swap! (h/db*) update-in [:chats chat-id :messages]
             (fnil into [])
             [{:role "reason" :content "internal thoughts"}
              {:role "tool_call" :content {:name "read_file"}}
              {:role "tool_call_output" :content "file contents"}
              {:role "flag" :content {:text "ui-only"}}])

        ;; Message 3: should re-generate title with full conversation context
      (h/reset-messenger!)
      (prompt-with-title! {:message "And add tests" :chat-id chat-id} {:sync-prompt-mock sync-mock})
      (is (= 2 (count @sync-prompt-calls*))
          "Should call sync-prompt! again on third message")
      (is (= "Title 2" (get-in (h/db) [:chats chat-id :title])))
      (let [retitle-call (second @sync-prompt-calls*)
            user-text (get-in (first (:user-messages retitle-call))
                              [:content 0 :text])]
          ;; On retitle, history is flattened into a single user message so the
          ;; title model can't mirror prior assistant styling (e.g. '## Understand').
        (is (= 1 (count (:user-messages retitle-call)))
            "Retitle should flatten conversation into a single user message")
        (is (nil? (:past-messages retitle-call))
            "Retitle should not replay role-structured past-messages")
        (is (string/includes? user-text "user:")
            "Flattened transcript should mark user turns")
        (is (string/includes? user-text "assistant:")
            "Flattened transcript should mark assistant turns")
        (is (string/includes? user-text "Help me debug")
            "Flattened transcript should include first user turn")
        (is (string/includes? user-text "And add tests")
            "Flattened transcript should include current user turn")
        (is (not (string/includes? user-text "internal thoughts"))
            "Reason/tool/flag entries should be filtered out of the transcript")
        (is (not (string/includes? user-text "tool_call"))
            "Reason/tool/flag entries should be filtered out of the transcript"))

        ;; Message 4: should NOT re-generate title
      (h/reset-messenger!)
      (prompt-with-title! {:message "One more thing" :chat-id chat-id} {:sync-prompt-mock sync-mock})
      (is (= 2 (count @sync-prompt-calls*))
          "Should NOT call sync-prompt! after third message")))

  (testing "retitle respects the last compact marker, dropping pre-compaction history"
    (h/reset-components!)
    (let [sync-prompt-calls* (atom [])
          call-count* (atom 0)
          sync-mock (fn [params]
                      (swap! sync-prompt-calls* conj params)
                      {:output-text (str "Title " (swap! call-count* inc))})
          {:keys [chat-id]} (prompt-with-title! {:message "Help me debug"} {:sync-prompt-mock sync-mock})]
      (h/reset-messenger!)
      (prompt-with-title! {:message "Also refactor" :chat-id chat-id} {:sync-prompt-mock sync-mock})

        ;; Insert a compact_marker so earlier history is treated as pre-compaction
        ;; and must not reach the title LLM.
      (swap! (h/db*) update-in [:chats chat-id :messages]
             (fnil conj []) {:role "compact_marker" :content {:auto? false}})

      (h/reset-messenger!)
      (prompt-with-title! {:message "And add tests" :chat-id chat-id} {:sync-prompt-mock sync-mock})
      (let [retitle-call (second @sync-prompt-calls*)
            user-text (get-in (first (:user-messages retitle-call))
                              [:content 0 :text])]
        (is (nil? (:past-messages retitle-call))
            "Past-messages should be nil on retitle (conversation is flattened into user-messages)")
        (is (not (string/includes? user-text "Help me debug"))
            "Pre-compact content must not leak into the title transcript")
        (is (not (string/includes? user-text "Also refactor"))
            "Pre-compact content must not leak into the title transcript")
        (is (string/includes? user-text "And add tests")
            "Current user message must be in the title transcript"))))

  (testing "manual rename suppresses automatic re-titling"
    (h/reset-components!)
    (let [sync-prompt-calls* (atom [])
          sync-mock (fn [params]
                      (swap! sync-prompt-calls* conj params)
                      {:output-text "Auto Title"})
          {:keys [chat-id]} (prompt-with-title! {:message "Help me debug"} {:sync-prompt-mock sync-mock})]
      ;; Message 1: generates initial title
      (is (= 1 (count @sync-prompt-calls*)))

        ;; Manual rename
      (f.chat/update-chat {:chat-id chat-id :title "My Custom Title"} (h/db*) (h/messenger) (h/metrics))
      (is (= "My Custom Title" (get-in (h/db) [:chats chat-id :title])))
      (is (true? (get-in (h/db) [:chats chat-id :title-custom?])))

        ;; Messages 2 and 3: should NOT re-generate because of manual rename
      (h/reset-messenger!)
      (prompt-with-title! {:message "Second msg" :chat-id chat-id} {:sync-prompt-mock sync-mock})
      (h/reset-messenger!)
      (prompt-with-title! {:message "Third msg" :chat-id chat-id} {:sync-prompt-mock sync-mock})
      (is (= 1 (count @sync-prompt-calls*))
          "Should NOT re-title after manual rename")
      (is (= "My Custom Title" (get-in (h/db) [:chats chat-id :title])))))

  (testing "title disabled in config skips all generation"
    (h/reset-components!)
    (let [sync-prompt-calls* (atom [])
          sync-mock (fn [params]
                      (swap! sync-prompt-calls* conj params)
                      {:output-text "Should not appear"})
          disabled-mocks {:sync-prompt-mock sync-mock :config {:chat {:title false}}}
          {:keys [chat-id]} (prompt-with-title! {:message "Hello"} disabled-mocks)]
      (prompt-with-title! {:message "Second" :chat-id chat-id} disabled-mocks)
      (prompt-with-title! {:message "Third" :chat-id chat-id} disabled-mocks)
      (is (zero? (count @sync-prompt-calls*))
          "Should never call sync-prompt! when title is disabled")
      (is (nil? (get-in (h/db) [:chats chat-id :title]))))))

(deftest update-chat-trust-test
  (testing "update-chat stores trust in chat state"
    (h/reset-components!)
    (let [chat-id "trust-chat"]
      (swap! (h/db*) assoc-in [:chats chat-id] {:id chat-id})
      (f.chat/update-chat {:chat-id chat-id :trust true} (h/db*) (h/messenger) (h/metrics))
      (is (true? (get-in (h/db) [:chats chat-id :trust])))))

  (testing "update-chat sets trust to false"
    (h/reset-components!)
    (let [chat-id "trust-chat"]
      (swap! (h/db*) assoc-in [:chats chat-id] {:id chat-id :trust true})
      (f.chat/update-chat {:chat-id chat-id :trust false} (h/db*) (h/messenger) (h/metrics))
      (is (false? (get-in (h/db) [:chats chat-id :trust])))))

  (testing "update-chat without trust does not change existing trust"
    (h/reset-components!)
    (let [chat-id "trust-chat"]
      (swap! (h/db*) assoc-in [:chats chat-id] {:id chat-id :trust true})
      (f.chat/update-chat {:chat-id chat-id :title "New Title"} (h/db*) (h/messenger) (h/metrics))
      (is (true? (get-in (h/db) [:chats chat-id :trust])))
      (is (= "New Title" (get-in (h/db) [:chats chat-id :title])))))

  (testing "update-chat with trust and title updates both"
    (h/reset-components!)
    (let [chat-id "trust-chat"]
      (swap! (h/db*) assoc-in [:chats chat-id] {:id chat-id})
      (f.chat/update-chat {:chat-id chat-id :title "My Chat" :trust true} (h/db*) (h/messenger) (h/metrics))
      (is (true? (get-in (h/db) [:chats chat-id :trust])))
      (is (= "My Chat" (get-in (h/db) [:chats chat-id :title]))))))

(deftest context-overflow-auto-compact-guard-test
  (testing "context overflow after auto-compact reports error instead of looping"
    (h/reset-components!)
    (let [api-call-count* (atom 0)
          auto-compact-count* (atom 0)]
      (with-redefs-fn
        {#'f.chat/trigger-auto-compact!
         (fn [chat-ctx _all-tools user-messages]
           (swap! auto-compact-count* inc)
           (#'f.chat/prompt-messages!
            user-messages
            :auto-compact
            (assoc chat-ctx :auto-compacted? true)))}
        (fn []
          (let [{:keys [_chat-id]}
                (prompt!
                 {:message "Do something"}
                 {:all-tools-mock (constantly [])
                  :api-mock
                  (fn [{:keys [on-error]}]
                    (swap! api-call-count* inc)
                    (on-error {:error/type :context-overflow
                               :message "token limit exceeded"}))})]
            (is (= 1 @auto-compact-count*)
                "auto-compact should trigger exactly once, not loop")
            (is (= 2 @api-call-count*)
                "LLM should be called exactly twice: initial prompt and resume after compact")
            (is (match?
                 {:chat-content-received
                  (m/embeds [{:role :system
                              :content {:type :text
                                        :text "Context window exceeded. Auto-compacting conversation..."}}
                             {:role :system
                              :content {:type :text
                                        :text "\n\ntoken limit exceeded"}}])}
                 (h/messages))
                "Should show auto-compact attempt and then the final error")))))))

(defn ^:private make-tool-output-msg [id text]
  {:role "tool_call_output"
   :content {:id id
             :name "read_file"
             :output {:error false
                      :contents [{:type :text :text text}]}}})

(defn ^:private make-tool-call-msg [id]
  {:role "tool_call"
   :content {:id id :full-name "eca__read_file" :arguments {"path" "/foo"}}})

(defn ^:private make-server-tool-result-msg [id text]
  {:role "server_tool_result"
   :content {:tool-use-id id
             :raw-content [{:type "text" :text text}]}})

(deftest prune-tool-results!-test
  (testing "clears old tool results beyond protect budget"
    (let [large-text (apply str (repeat 100000 "x"))
          small-text (apply str (repeat 20000 "y"))
          messages [{:role "user" :content [{:type :text :text "hello"}]}
                    (make-tool-call-msg "1")
                    (make-tool-output-msg "1" large-text)
                    {:role "assistant" :content [{:type :text :text "found it"}]}
                    (make-tool-call-msg "2")
                    (make-tool-output-msg "2" small-text)
                    {:role "assistant" :content [{:type :text :text "done"}]}]
          db* (atom {:chats {"c1" {:messages messages}}})
          freed (#'f.chat/prune-tool-results! db* "c1" {:protect-budget 3000})]
      (is (pos? freed))
      (let [pruned (get-in @db* [:chats "c1" :messages])]
        (is (= "[content cleared to reduce context size]"
               (get-in (nth pruned 2) [:content :output :contents 0 :text])))
        (is (= small-text
               (get-in (nth pruned 5) [:content :output :contents 0 :text])))
        (is (= "hello" (get-in (nth pruned 0) [:content 0 :text])))
        (is (= "found it" (get-in (nth pruned 3) [:content 0 :text]))))))

  (testing "returns 0 and does not modify db when nothing to prune"
    (let [messages [{:role "user" :content [{:type :text :text "hello"}]}
                    (make-tool-call-msg "1")
                    (make-tool-output-msg "1" "short")
                    {:role "assistant" :content [{:type :text :text "ok"}]}]
          db* (atom {:chats {"c1" {:messages messages}}})
          freed (#'f.chat/prune-tool-results! db* "c1" {:protect-budget 40000})]
      (is (zero? freed))
      (is (= messages (get-in @db* [:chats "c1" :messages])))))

  (testing "clears server_tool_result messages beyond budget"
    (let [large-text (apply str (repeat 100000 "z"))
          messages [{:role "user" :content [{:type :text :text "hello"}]}
                    (make-server-tool-result-msg "s1" large-text)
                    {:role "assistant" :content [{:type :text :text "searched"}]}]
          db* (atom {:chats {"c1" {:messages messages}}})
          freed (#'f.chat/prune-tool-results! db* "c1" {:protect-budget 0})]
      (is (pos? freed))
      (is (= "[content cleared to reduce context size]"
             (get-in (nth (get-in @db* [:chats "c1" :messages]) 1) [:content :raw-content 0 :text])))))

  (testing "handles empty message history"
    (let [db* (atom {:chats {"c1" {:messages []}}})]
      (is (zero? (#'f.chat/prune-tool-results! db* "c1" {})))))

  (testing "stops at compact_marker boundary, does not prune pre-compaction messages"
    (let [large-text (apply str (repeat 100000 "x"))
          messages [(make-tool-call-msg "old")
                    (make-tool-output-msg "old" large-text)
                    {:role "compact_marker" :content {:auto? false}}
                    {:role "user" :content [{:type :text :text "summary"}]}
                    (make-tool-call-msg "new")
                    (make-tool-output-msg "new" large-text)]
          db* (atom {:chats {"c1" {:messages messages}}})
          freed (#'f.chat/prune-tool-results! db* "c1" {:protect-budget 0})]
      (is (pos? freed))
      (let [pruned (get-in @db* [:chats "c1" :messages])]
        ;; Pre-compaction tool output should be untouched
        (is (= large-text
               (get-in (nth pruned 1) [:content :output :contents 0 :text])))
        ;; Post-compaction tool output should be cleared
        (is (= "[content cleared to reduce context size]"
               (get-in (nth pruned 5) [:content :output :contents 0 :text])))))))

(deftest contexts-in-prompt-test
  (testing "When prompt contains @file we add a user message"
    (h/reset-components!)
    (with-redefs [fs/readable? (constantly true)
                  llm-api/refine-file-context (constantly "Mocked file content")]
      (let [{:keys [chat-id]}
            (prompt!
             {:message "Check @/path/to/file please"}
             {:all-tools-mock (constantly [])
              :api-mock
              (fn [{:keys [on-first-response-received
                           on-message-received]}]
                (on-first-response-received {:type :text :text "On it..."})
                (on-message-received {:type :text :text "On it..."})
                (on-message-received {:type :finish}))})]
        (is (match?
             {chat-id {:id chat-id
                       :messages [{:role "user"
                                   :content [{:type :text :text "Check @/path/to/file please"}
                                             {:type :text :text (m/pred #(string/includes? % "<file path"))}]}
                                  {:role "assistant" :content [{:type :text :text "On it..."}]}]}}
             (:chats (h/db)))))))
  (testing "When prompt contains @missing-file we do not add context noise"
    (h/reset-components!)
    (let [missing-file "definitely-does-not-exist-eca-test-ctx.md"
          msg (str "Check @" missing-file " please")
          {:keys [chat-id]}
          (prompt!
           {:message msg}
           {:all-tools-mock (constantly [])
            :api-mock
            (fn [{:keys [on-first-response-received
                         on-message-received]}]
              (on-first-response-received {:type :text :text "On it..."})
              (on-message-received {:type :text :text "On it..."})
              (on-message-received {:type :finish}))})]
      (is (match?
           {chat-id {:id chat-id
                     :messages [{:role "user" :content [{:type :text :text msg}]}
                                {:role "assistant" :content [{:type :text :text "On it..."}]}]}}
           (:chats (h/db))))
      (is (match?
           {:chat-content-received
            [{:chat-id chat-id
              :content {:type :text :text (str msg "\n")}
              :role :user}
             {:chat-id chat-id
              :content {:type :progress :state :running :text "Waiting model"}
              :role :system}
             {:chat-id chat-id
              :content {:type :progress :state :running :text "Generating"}
              :role :system}
             {:chat-id chat-id
              :content {:type :text :text "On it..."}
              :role :assistant}
             {:chat-id chat-id
              :content {:state :finished :type :progress}
              :role :system}]}
           (h/messages))))))

(deftest basic-tool-calling-prompt-test
  (testing "Asking to list directories, LLM will check for allowed directories and then list files"
    (h/reset-components!)
    (let [{:keys [chat-id]}
          (prompt!
           {:message "List the files you are allowed to see"}
           {:all-tools-mock (constantly [{:name "list_allowed_directories" :full-name "eca__list_allowed_directories" :server {:name "eca"}}])
            :api-mock
            (fn [{:keys [on-first-response-received
                         on-message-received
                         on-prepare-tool-call
                         on-tools-called]}]
              (on-first-response-received {:type :text :text "Ok,"})
              (on-message-received {:type :text :text "Ok,"})
              (on-message-received {:type :text :text " working on it"})
              (on-prepare-tool-call {:id "call-1" :full-name "eca__list_allowed_directories" :arguments-text ""})
              (on-tools-called [{:id "call-1" :full-name "eca__list_allowed_directories" :arguments {}}])
              (on-message-received {:type :text :text "I can see: \n"})
              (on-message-received {:type :text :text "/foo/bar"})
              (on-message-received {:type :finish}))
            :call-tool-mock
            (constantly {:error false
                         :contents [{:type :text :text "Allowed directories: /foo/bar"}]})})]
      (is (match?
           {chat-id {:id chat-id
                     :messages [{:role "user" :content [{:type :text :text "List the files you are allowed to see"}]}
                                {:role "assistant" :content [{:type :text :text "Ok, working on it"}]}
                                {:role "tool_call" :content {:id "call-1" :full-name "eca__list_allowed_directories" :arguments {}}}
                                {:role "tool_call_output" :content {:id "call-1" :full-name "eca__list_allowed_directories" :arguments {}
                                                                    :output {:error false
                                                                             :contents [{:text "Allowed directories: /foo/bar"
                                                                                         :type :text}]}}}
                                {:role "assistant" :content [{:type :text :text "I can see: \n/foo/bar"}]}]}}
           (:chats (h/db))))
      ;; Note: We use m/in-any-order because there's a race between progress messages
      ;; ("Calling tool", "Generating") and tool state messages (toolCallRunning,
      ;; toolCalled) - their relative order in the middle section is non-deterministic.
      (is (match?
           {:chat-content-received
            (m/in-any-order [{:role :user :content {:type :text :text "List the files you are allowed to see\n"}}
                             {:role :system :content {:type :progress :state :running :text "Waiting model"}}
                             {:role :system :content {:type :progress :state :running :text "Generating"}}
                             {:role :assistant :content {:type :text :text "Ok,"}}
                             {:role :assistant :content {:type :text :text " working on it"}}
                             {:role :assistant :content {:type :toolCallPrepare :id "call-1" :name "list_allowed_directories" :arguments-text ""}}
                             {:role :assistant :content {:type :toolCallRun :id "call-1" :name "list_allowed_directories" :arguments {} :manual-approval false}}
                             {:role :assistant :content {:type :toolCallRunning :id "call-1" :name "list_allowed_directories" :arguments {}}}
                             {:role :system :content {:type :progress :state :running :text "Calling tool"}}
                             {:role :assistant :content {:type :toolCalled :id "call-1" :name "list_allowed_directories" :arguments {} :total-time-ms number? :outputs [{:text "Allowed directories: /foo/bar" :type :text}]}}
                             {:role :system :content {:type :progress :state :running :text "Generating"}}
                             {:role :assistant :content {:type :text :text "I can see: \n"}}
                             {:role :assistant :content {:type :text :text "/foo/bar"}}
                             {:role :system :content {:state :finished :type :progress}}])}
           (h/messages))))))

(deftest concurrent-tool-calls-test
  (testing "Running three calls simultaneously"
    (h/reset-components!)
    (let [{:keys [chat-id]}
          (prompt!
           {:message "Run 3 read-only tool calls simultaneously."}
           {:all-tools-mock (constantly [{:name "ro_tool_1" :full-name "eca__ro_tool_1" :server {:name "eca"}}
                                         {:name "ro_tool_2" :full-name "eca__ro_tool_2" :server {:name "eca"}}
                                         {:name "ro_tool_3" :full-name "eca__ro_tool_3" :server {:name "eca"}}])
            :api-mock
            (fn [{:keys [on-first-response-received
                         on-message-received
                         on-prepare-tool-call
                         on-tools-called]}]
              (on-first-response-received {:type :text :text "Ok,"})
              (on-message-received {:type :text :text "Ok,"})
              (on-message-received {:type :text :text " working on it"})
              (on-prepare-tool-call {:id "call-1" :full-name "eca__ro_tool_1" :arguments-text ""})
              (on-prepare-tool-call {:id "call-2" :full-name "eca__ro_tool_2" :arguments-text ""})
              (on-prepare-tool-call {:id "call-3" :full-name "eca__ro_tool_3" :arguments-text ""})
              (on-tools-called [{:id "call-1" :full-name "eca__ro_tool_1" :arguments {}}
                                {:id "call-2" :full-name "eca__ro_tool_2" :arguments {}}
                                {:id "call-3" :full-name "eca__ro_tool_3" :arguments {}}])
              (on-message-received {:type :text :text "The tool calls returned: \n"})
              (on-message-received {:type :text :text "something"})
              (on-message-received {:type :finish}))
            :call-tool-mock
            ;; Ensure that the tools complete in the 3-2-1 order by adjusting sleep times
            (fn [full-name & _others]
              ;; When this is called, we are already in a future.
              (case full-name

                "eca__ro_tool_1"
                (do (deep-sleep 900)
                    {:error false
                     :contents [{:type :text :text "RO tool call 1 result"}]})

                "eca__ro_tool_2"
                (do (deep-sleep 600)
                    {:error false
                     :contents [{:type :text :text "RO tool call 2 result"}]})

                "eca__ro_tool_3"
                (do (deep-sleep 100)
                    {:error false
                     :contents [{:type :text :text "RO tool call 3 result"}]})))})]

      (is (match?
           {chat-id {:id chat-id
                     :messages [{:role "user" :content [{:type :text :text "Run 3 read-only tool calls simultaneously."}]}
                                {:role "assistant" :content [{:type :text :text "Ok, working on it"}]}
                                {:role "tool_call" :content {:id "call-3" :full-name "eca__ro_tool_3" :arguments {}}}
                                {:role "tool_call_output" :content {:id "call-3"  :full-name "eca__ro_tool_3" :arguments {}
                                                                    :output {:error false
                                                                             :contents [{:type :text, :text "RO tool call 3 result"}]}}}
                                {:role "tool_call" :content {:id "call-2" :full-name "eca__ro_tool_2" :arguments {}}}
                                {:role "tool_call_output" :content {:id "call-2" :full-name "eca__ro_tool_2" :arguments {}
                                                                    :output {:error false
                                                                             :contents [{:type :text, :text "RO tool call 2 result"}]}}}
                                {:role "tool_call" :content {:id "call-1" :full-name "eca__ro_tool_1" :arguments {}}}
                                {:role "tool_call_output" :content {:id "call-1" :full-name "eca__ro_tool_1" :arguments {}
                                                                    :output {:error false
                                                                             :contents [{:type :text, :text "RO tool call 1 result"}]}}}
                                {:role "assistant" :content [{:type :text, :text "The tool calls returned: \nsomething"}]}]}}
           (:chats (h/db))))
      (is (match?
           {:chat-content-received
            [{:role :user :content {:type :text :text "Run 3 read-only tool calls simultaneously.\n"}}
             {:role :system :content {:type :progress :state :running, :text "Waiting model"}}
             {:role :system :content {:type :progress :state :running, :text "Generating"}}
             {:role :assistant :content {:type :text :text "Ok,"}}
             {:role :assistant :content {:type :text :text " working on it"}}
             {:role :assistant :content {:type :toolCallPrepare :id "call-1" :name "ro_tool_1" :arguments-text ""}}
             {:role :assistant :content {:type :toolCallPrepare :id "call-2" :name "ro_tool_2" :arguments-text ""}}
             {:role :assistant :content {:type :toolCallPrepare :id "call-3" :name "ro_tool_3" :arguments-text ""}}
             {:role :assistant :content {:type :toolCallRun :id "call-1" :name "ro_tool_1" :arguments {} :manual-approval false}}
             {:role :assistant :content {:type :toolCallRunning :id "call-1" :name "ro_tool_1" :arguments {}}}
             {:role :system :content {:type :progress :state :running, :text "Calling tool"}}
             {:role :assistant :content {:type :toolCallRun :id "call-2" :name "ro_tool_2" :arguments {} :manual-approval false}}
             {:role :assistant :content {:type :toolCallRunning :id "call-2" :name "ro_tool_2" :arguments {}}}
             {:role :system :content {:type :progress :state :running, :text "Calling tool"}}
             {:role :assistant :content {:type :toolCallRun :id "call-3" :name "ro_tool_3" :arguments {} :manual-approval false}}
             {:role :assistant :content {:type :toolCallRunning :id "call-3" :name "ro_tool_3" :arguments {}}}
             {:role :system :content {:type :progress :state :running, :text "Calling tool"}}
             {:role :assistant :content {:type :toolCalled :id "call-3" :name "ro_tool_3" :arguments {}
                                         :outputs [{:type :text :text "RO tool call 3 result"}]
                                         :error false}}
             {:role :system :content {:type :progress :state :running, :text "Generating"}}
             {:role :assistant :content {:type :toolCalled :id "call-2" :name "ro_tool_2" :arguments {}
                                         :outputs [{:type :text :text "RO tool call 2 result"}]
                                         :error false}}
             {:role :system :content {:type :progress :state :running, :text "Generating"}}
             {:role :assistant :content {:type :toolCalled :id "call-1" :name "ro_tool_1" :arguments {}
                                         :outputs [{:type :text :text "RO tool call 1 result"}]
                                         :error false}}
             {:role :system :content {:type :progress :state :running, :text "Generating"}}
             {:role :assistant :content {:type :text :text "The tool calls returned: \n"}}
             {:role :assistant :content {:type :text :text "something"}}
             {:role :system :content {:type :progress :state :finished}}]}
           (h/messages))))))

(deftest tool-calls-with-prompt-stop-test
  (testing "Three concurrent tool calls. Stopped before they all finished. Tool call 3 finishes. Calls 1,2 reject."
    (h/reset-components!)
    (let [wait-for-tool3 (promise)
          wait-for-tool2 (promise)
          wait-for-stop (promise)
          {:keys [chat-id]}
          (prompt!
           {:message "Run 3 read-only tool calls simultaneously."}
           {:all-tools-mock (constantly [{:name "ro_tool_1" :full-name "eca__ro_tool_1" :server {:name "eca"}}
                                         {:name "ro_tool_2" :full-name "eca__ro_tool_2" :server {:name "eca"}}
                                         {:name "ro_tool_3" :full-name "eca__ro_tool_3" :server {:name "eca"}}])
            :api-mock
            (fn [{:keys [on-first-response-received
                         on-prepare-tool-call
                         on-tools-called]}]
              (let [chat-id (first (keys (:chats (h/db))))]
                (on-first-response-received {:type :text :text "Ok,"})
                (on-prepare-tool-call {:id "call-1" :full-name "eca__ro_tool_1" :arguments-text ""})
                (on-prepare-tool-call {:id "call-2" :full-name "eca__ro_tool_2" :arguments-text ""})
                (on-prepare-tool-call {:id "call-3" :full-name "eca__ro_tool_3" :arguments-text ""})
                (future (Thread/sleep 400)
                        (when (= :timeout (deref wait-for-tool3 10000 :timeout))
                          (println "tool-calls-with-prompt-stop-test: deref in prompt stop future timed out"))
                        (Thread/sleep 50)
                        (f.chat/prompt-stop {:chat-id chat-id} (h/db*) (h/messenger) (h/metrics))
                        (deliver wait-for-stop true))
                (on-tools-called [{:id "call-1" :full-name "eca__ro_tool_1" :arguments {}}
                                  {:id "call-2" :full-name "eca__ro_tool_2" :arguments {}}
                                  {:id "call-3" :full-name "eca__ro_tool_3" :arguments {}}])))
            :call-tool-mock
            (fn [full-name & _others]
              ;; When this is called, we are already in a future
              (case full-name

                "eca__ro_tool_1"
                (do (deep-sleep 1000)
                    (when (= :timeout (deref wait-for-tool2 10000 :timeout))
                      (println "tool-calls-with-prompt-stop-test: deref in tool 1 timed out"))
                    {:error false
                     :contents [{:type :text :text "RO tool call 1 result"}]})

                "eca__ro_tool_2"
                (do (deep-sleep 800)
                    (when (= :timeout (deref wait-for-stop 10000 :timeout))
                      (println "tool-calls-with-prompt-stop-test: deref in tool 2 timed out"))
                    (deliver wait-for-tool2 true)
                    {:error false
                     :contents [{:type :text :text "RO tool call 2 result"}]})

                "eca__ro_tool_3"
                (do (deep-sleep 200)
                    (deliver wait-for-tool3 true)
                    {:error false
                     :contents [{:type :text :text "RO tool call 3 result"}]})))})]
      (is (match? {chat-id
                   {:id chat-id
                    :messages [{:role "user" :content [{:type :text :text "Run 3 read-only tool calls simultaneously."}]}
                               {:role "tool_call" :content {:id "call-3" :full-name "eca__ro_tool_3" :arguments {}}}
                               {:role "tool_call_output" :content {:id "call-3" :full-name "eca__ro_tool_3" :arguments {}
                                                                   :output {:error false
                                                                            :contents [{:type :text, :text "RO tool call 3 result"}]}}}
                               {:role "tool_call" :content {:id "call-2" :full-name "eca__ro_tool_2" :arguments {}}}
                               {:role "tool_call_output" :content {:id "call-2" :full-name "eca__ro_tool_2" :arguments {}
                                                                   :output {:error false
                                                                            :contents [{:type :text, :text "RO tool call 2 result"}]}}}
                               {:role "tool_call" :content {:id "call-1" :full-name "eca__ro_tool_1" :arguments {}}}
                               {:role "tool_call_output" :content {:id "call-1" :full-name "eca__ro_tool_1" :arguments {}
                                                                   :output {:error false
                                                                            :contents [{:type :text, :text "RO tool call 1 result"}]}}}]}}
                  (:chats (h/db))))
      (is (match? {:chat-content-received
                   [{:role :user :content {:type :text :text "Run 3 read-only tool calls simultaneously.\n"}}
                    {:role :system :content {:type :progress :text "Waiting model"}}
                    {:role :system :content {:type :progress :text "Generating"}}
                    {:role :assistant :content {:type :toolCallPrepare :id "call-1" :name "ro_tool_1" :arguments-text ""}}
                    {:role :assistant :content {:type :toolCallPrepare :id "call-2" :name "ro_tool_2" :arguments-text ""}}
                    {:role :assistant :content {:type :toolCallPrepare :id "call-3" :name "ro_tool_3" :arguments-text ""}}
                    {:role :assistant :content {:type :toolCallRun :id "call-1" :name "ro_tool_1" :arguments {}}}
                    {:role :assistant :content {:type :toolCallRunning :id "call-1" :name "ro_tool_1" :arguments {}}}
                    {:role :system :content {:type :progress :state :running :text "Calling tool"}}
                    {:role :assistant :content {:type :toolCallRun :id "call-2" :name "ro_tool_2" :arguments {} :manual-approval false}}
                    {:role :assistant :content {:type :toolCallRunning :id "call-2" :name "ro_tool_2" :arguments {}}}
                    {:role :system :content {:type :progress :state :running :text "Calling tool"}}
                    {:role :assistant :content {:type :toolCallRun :id "call-3" :name "ro_tool_3" :arguments {} :manual-approval false}}
                    {:role :assistant :content {:type :toolCallRunning :id "call-3" :name "ro_tool_3" :arguments {}}}
                    {:role :system :content {:type :progress :state :running :text "Calling tool"}}
                    {:role :assistant :content {:type :toolCalled :id "call-3" :name "ro_tool_3" :arguments {}
                                                :outputs [{:type :text :text "RO tool call 3 result"}]}}
                    {:role :system :content {:type :progress :state :running :text "Generating"}}
                    {:role :system :content {:type :text :text "\nPrompt stopped"}}
                    {:role :system :content {:type :progress :state :finished}}
                    {:role :assistant :content {:type :toolCallRejected :id "call-2" :name "ro_tool_2" :arguments {} :reason :user}}
                    {:role :assistant :content {:type :toolCallRejected :id "call-1" :name "ro_tool_1" :arguments {} :reason :user}}]}
                  (h/messages))))))

(deftest send-mcp-prompt-test
  (testing "Argument mapping for send-mcp-prompt! should map arg values to prompt argument names"
    (let [test-arguments [{:name "foo"} {:name "bar"}]
          prompt-args (atom nil)
          test-chat-ctx {:db* (atom {})}
          invoked? (atom nil)]
      (with-redefs [f.mcp/all-prompts (fn [_]
                                        [{:name "awesome-prompt" :arguments test-arguments}])
                    f.prompt/get-prompt! (fn [_ args-map _]
                                           (reset! prompt-args args-map)
                                           {:messages [{:role :user :content "test"}]})
                    f.chat/prompt-messages! (fn [messages source-type ctx]
                                              (reset! invoked? [messages source-type ctx]))]
        (#'f.chat/send-mcp-prompt! {:prompt "awesome-prompt" :args [42 "yo"]} test-chat-ctx)
        (is (match?
             @prompt-args
             {"foo" 42 "bar" "yo"}))
        (is (match?
             @invoked?
             [[{:role :user :content "test"}] :mcp-prompt test-chat-ctx])))))

  (testing "shows error message and finishes chat when get-prompt! returns error-message"
    (let [test-chat-ctx {:db* (atom {})}
          sent-content (atom nil)
          finished-status (atom nil)]
      (with-redefs [f.mcp/all-prompts (fn [_]
                                        [{:name "failing-prompt" :arguments [{:name "arg1"}]}])
                    f.prompt/get-prompt! (fn [_ _ _]
                                           {:error-message "MCP error getting prompt: code=-32603 message=Invalid required argument: arg1"})
                    lifecycle/send-content! (fn [_ctx _role content]
                                              (reset! sent-content content))
                    lifecycle/finish-chat-prompt! (fn [status _ctx]
                                                    (reset! finished-status status))]
        (#'f.chat/send-mcp-prompt! {:prompt "failing-prompt" :args ["val1"]} test-chat-ctx)
        (is (= :text (:type @sent-content)))
        (is (string/includes? (:text @sent-content) "MCP error getting prompt"))
        (is (= :idle @finished-status)))))

  (testing "shows error message and finishes chat when get-prompt! returns nil"
    (let [test-chat-ctx {:db* (atom {})}
          sent-content (atom nil)
          finished-status (atom nil)]
      (with-redefs [f.mcp/all-prompts (fn [_]
                                        [{:name "nil-prompt" :arguments []}])
                    f.prompt/get-prompt! (fn [_ _ _] nil)
                    lifecycle/send-content! (fn [_ctx _role content]
                                              (reset! sent-content content))
                    lifecycle/finish-chat-prompt! (fn [status _ctx]
                                                    (reset! finished-status status))]
        (#'f.chat/send-mcp-prompt! {:prompt "nil-prompt" :args []} test-chat-ctx)
        (is (= :text (:type @sent-content)))
        (is (string/includes? (:text @sent-content) "No response from prompt"))
        (is (= :idle @finished-status))))))

(deftest message->decision-test
  (testing "plain prompt message"
    (is (= {:type :prompt-message
            :message "Hello world"}
           (#'f.chat/message->decision "Hello world" {} {}))))
  (testing "message starting with a absolute path"
    (is (= {:type :prompt-message
            :message "/path/to/file check this out"}
           (#'f.chat/message->decision "/path/to/file check this out" {} {}))))
  (testing "ECA command without args"
    (is (= {:type :eca-command
            :command "doctor"
            :args []}
           (#'f.chat/message->decision "/doctor" {} {}))))
  (testing "ECA command with args"
    (is (= {:type :eca-command
            :command "login"
            :args ["foo" "bar"]}
           (#'f.chat/message->decision "/login foo bar" {} {}))))
  (testing "ECA command with args with spaces in quotes"
    (is (= {:type :eca-command
            :command "login"
            :args ["foo bar" "baz" "qux bla blow"]}
           (#'f.chat/message->decision "/login \"foo bar\" baz \"qux bla blow\"" {} {}))))
  (with-redefs [f.mcp/all-prompts (constantly [{:name "prompt"
                                                :server "server"}])]
    (testing "MCP prompt without args"
      (is (= {:type :mcp-prompt
              :server "server"
              :prompt "prompt"
              :args []}
             (#'f.chat/message->decision "/server:prompt" {} {}))))
    (testing "MCP prompt with args"
      (is (= {:type :mcp-prompt
              :server "server"
              :prompt "prompt"
              :args ["arg1" "arg2"]}
             (#'f.chat/message->decision "/server:prompt arg1 arg2" {} {}))))))

(deftest rollback-chat-test
  (testing "Rollback chat removes messages after content-id"
    (h/reset-components!)
    (let [{:keys [chat-id]}
          (prompt!
           {:message "Count with me: 1"}
           {:all-tools-mock (constantly [])
            :api-mock
            (fn [{:keys [on-first-response-received
                         on-message-received]}]
              (on-first-response-received {:type :text :text "2"})
              (on-message-received {:type :text :text "2"})
              (on-message-received {:type :finish}))})
          first-content-id (get-in (h/db) [:chats chat-id :messages 0 :content-id])
          _ (is (some? first-content-id) "first-content-id should exist")]
      ;; Verify initial state
      (is (match?
           {chat-id {:id chat-id
                     :messages [{:role "user" :content [{:type :text :text "Count with me: 1"}] :content-id first-content-id}
                                {:role "assistant" :content [{:type :text :text "2"}]}]}}
           (:chats (h/db))))

      ;; Add second message
      (h/reset-messenger!)
      (prompt!
       {:message "3"
        :chat-id chat-id}
       {:all-tools-mock (constantly [])
        :api-mock
        (fn [{:keys [on-first-response-received
                     on-message-received]}]
          (on-first-response-received {:type :text :text "4"})
          (on-message-received {:type :text :text "4"})
          (on-message-received {:type :finish}))})
      (let [second-content-id (get-in (h/db) [:chats chat-id :messages 2 :content-id])]

        ;; Verify we now have 4 messages
        (is (match?
             {chat-id {:id chat-id
                       :messages [{:role "user" :content [{:type :text :text "Count with me: 1"}] :content-id first-content-id}
                                  {:role "assistant" :content [{:type :text :text "2"}]}
                                  {:role "user" :content [{:type :text :text "3"}] :content-id second-content-id}
                                  {:role "assistant" :content [{:type :text :text "4"}]}]}}
             (:chats (h/db))))

        ;; Rollback to second message (keep first 2 messages, remove last 2)
        (h/reset-messenger!)
        (is (= {} (f.chat/rollback-chat
                   {:chat-id chat-id
                    :include ["messages" "tools"]
                    :content-id second-content-id} (h/db*) (h/messenger))))

        ;; Verify messages after content-id are removed (keeps messages before content-id)
        (is (match?
             {chat-id {:id chat-id
                       :messages [{:role "user" :content [{:type :text :text "Count with me: 1"}] :content-id first-content-id}
                                  {:role "assistant" :content [{:type :text :text "2"}]}]}}
             (:chats (h/db))))

        ;; Verify messenger received chat-clear and then messages
        (is (match?
             {:chat-clear [{:chat-id chat-id :messages true}]
              :chat-content-received
              [{:chat-id chat-id
                :content {:type :text :text "\nCount with me: 1" :content-id first-content-id}
                :role "user"}
               {:chat-id chat-id
                :content {:type :text :text "\n2"}
                :role "assistant"}]}
             (h/messages)))))))

(deftest prompt-cache-agent-switch-test
  (testing "Static prompt cache is rebuilt when switching agents within the same chat"
    (h/reset-components!)
    (let [build-calls* (atom 0)
          real-build f.prompt/build-chat-instructions]
      (with-redefs [f.prompt/build-chat-instructions
                    (fn [& args]
                      (swap! build-calls* inc)
                      (apply real-build args))
                    ;; Test config doesn't populate :agent, so validate-agent-name
                    ;; would fall back to "code" for every input. Short-circuit it
                    ;; so the test can actually exercise an agent switch.
                    config/validate-agent-name (fn [agent-name _config] agent-name)]
        (let [mocks {:all-tools-mock (constantly [])
                     :api-mock (fn [{:keys [on-message-received]}]
                                 (on-message-received {:type :finish}))}
              {:keys [chat-id]} (prompt! {:message "Hi" :agent "code"} mocks)]
          (is (= 1 @build-calls*)
              "First prompt should build the static instructions once")
          (h/reset-messenger!)
          (prompt! {:message "Still code" :chat-id chat-id :agent "code"} mocks)
          (is (= 1 @build-calls*)
              "Second prompt with the same agent should reuse cached instructions")
          (h/reset-messenger!)
          (prompt! {:message "Switch to plan" :chat-id chat-id :agent "plan"} mocks)
          (is (= 2 @build-calls*)
              "Switching agent should invalidate the cache and rebuild instructions")
          (h/reset-messenger!)
          (prompt! {:message "Back to code" :chat-id chat-id :agent "code"} mocks)
          (is (= 3 @build-calls*)
              "Switching back to the first agent should also trigger a rebuild"))))))

(deftest prompt-cache-key-includes-agent-test
  (testing "sync-or-async-prompt! receives prompt-cache-key scoped by active agent"
    (h/reset-components!)
    (with-redefs [config/validate-agent-name (fn [agent-name _config] agent-name)]
      (let [captured* (atom [])
            mocks {:all-tools-mock (constantly [])
                   :api-mock (fn [{:keys [on-message-received] :as params}]
                               (swap! captured* conj (:prompt-cache-key params))
                               (on-message-received {:type :finish}))}
            {:keys [chat-id]} (prompt! {:message "hi" :agent "code"} mocks)]
        (h/reset-messenger!)
        (prompt! {:message "hello" :chat-id chat-id :agent "plan"} mocks)
        (is (= 2 (count @captured*)))
        (is (every? some? @captured*))
        (is (string/ends-with? (first @captured*) "/code")
            "First prompt's cache key should be suffixed by /code")
        (is (string/ends-with? (second @captured*) "/plan")
            "Second prompt's cache key should be suffixed by /plan")))))

(defn ^:private capturing-api-mock
  "Returns an api-mock that writes the incoming sync-or-async-prompt! params
   to `captured*` and emits a minimal finish event."
  [captured*]
  (fn [{:keys [on-first-response-received on-message-received] :as params}]
    (reset! captured* params)
    (on-first-response-received {:type :text :text "x"})
    (on-message-received {:type :text :text "x"})
    (on-message-received {:type :finish})))

(deftest resume-preserves-stored-model-test
  (testing "Stored chat :model wins over default when no explicit model is sent (#417)"
    (h/reset-components!)
    (let [captured* (atom nil)
          chat-id "opus-chat"]
      (swap! (h/db*) update :models
             #(merge % {"anthropic/claude-opus-4" {:tools true}}))
      ;; Pre-seed a chat as if it were resumed — stored model is opus.
      (swap! (h/db*) assoc-in [:chats chat-id]
             {:id chat-id
              :model "anthropic/claude-opus-4"
              :messages [{:role "user" :content [{:type :text :text "prior"}]}
                         {:role "assistant" :content [{:type :text :text "ok"}]}]})
      (prompt! {:message "follow up" :chat-id chat-id}
               {:all-tools-mock (constantly [])
                :api-mock (capturing-api-mock captured*)})
      (is (= "anthropic" (:provider @captured*)))
      (is (= "claude-opus-4" (:model @captured*)))
      (is (= "anthropic/claude-opus-4" (get-in (h/db) [:chats chat-id :model])))))

  (testing "Explicit request :model overrides stored chat :model"
    (h/reset-components!)
    (let [captured* (atom nil)
          chat-id "opus-chat"]
      (swap! (h/db*) update :models
             #(merge % {"anthropic/claude-opus-4" {:tools true}}))
      (swap! (h/db*) assoc-in [:chats chat-id]
             {:id chat-id
              :model "anthropic/claude-opus-4"
              :messages [{:role "user" :content [{:type :text :text "prior"}]}]})
      (prompt! {:message "switch" :chat-id chat-id :model "openai/gpt-5.2"}
               {:all-tools-mock (constantly [])
                :api-mock (capturing-api-mock captured*)})
      (is (= "openai" (:provider @captured*)))
      (is (= "gpt-5.2" (:model @captured*)))))

  (testing "Stale stored :model (missing from (:models db)) falls through to default-model"
    (h/reset-components!)
    (let [captured* (atom nil)
          chat-id "opus-chat"]
      ;; Only gpt-5.2 is configured (by `prompt!` helper); opus is no longer
      ;; available (e.g. provider removed).
      (swap! (h/db*) assoc-in [:chats chat-id]
             {:id chat-id
              :model "anthropic/claude-opus-4"
              :messages [{:role "user" :content [{:type :text :text "prior"}]}]})
      (prompt! {:message "follow up" :chat-id chat-id}
               {:all-tools-mock (constantly [])
                :api-mock (capturing-api-mock captured*)})
      (is (= "openai" (:provider @captured*)))
      (is (= "gpt-5.2" (:model @captured*))))))

(deftest open-chat-restores-selected-model-test
  (testing "Opening a chat with a stored :model emits config/updated select-model (#417)"
    (h/reset-components!)
    (let [chat-id "resumed"]
      (swap! (h/db*) update :models
             #(merge % {"anthropic/claude-opus-4" {:tools true}}))
      (swap! (h/db*) assoc-in [:chats chat-id]
             {:id chat-id
              :title "My Opus thread"
              :model "anthropic/claude-opus-4"
              :messages [{:role "user" :content [{:type :text :text "hi"}]}]})
      (let [result (f.chat/open-chat! {:chat-id chat-id}
                                      (h/db*) (h/messenger) (h/config))]
        (is (match? {:found? true :chat-id chat-id :title "My Opus thread"} result))
        (is (match? {:config-updated [{:chat {:select-model "anthropic/claude-opus-4"
                                              :variants []
                                              :select-variant nil}}
                                      {:chat {:select-trust false}}]}
                    (h/messages))))))

  (testing "Opening a chat with no stored :model only emits trust config/updated"
    (h/reset-components!)
    (let [chat-id "no-model"]
      (swap! (h/db*) assoc-in [:chats chat-id]
             {:id chat-id
              :messages [{:role "user" :content [{:type :text :text "hi"}]}]})
      (f.chat/open-chat! {:chat-id chat-id} (h/db*) (h/messenger) (h/config))
      (is (match? {:config-updated [{:chat {:select-trust false}}]}
                  (h/messages)))))

  (testing "Opening a chat with a stale stored :model only emits trust config/updated"
    (h/reset-components!)
    (let [chat-id "stale-model"]
      ;; Opus not in (:models db), so the UI dropdown must not jump to a ghost.
      (swap! (h/db*) assoc-in [:chats chat-id]
             {:id chat-id
              :model "anthropic/claude-opus-4"
              :messages [{:role "user" :content [{:type :text :text "hi"}]}]})
      (f.chat/open-chat! {:chat-id chat-id} (h/db*) (h/messenger) (h/config))
      (is (match? {:config-updated [{:chat {:select-trust false}}]}
                  (h/messages))))))

(deftest open-chat-restores-selected-trust-test
  (testing "Opening a trusted chat emits config/updated select-trust true (#426)"
    (h/reset-components!)
    (let [chat-id "trusted"]
      (swap! (h/db*) assoc-in [:chats chat-id]
             {:id chat-id
              :title "YOLO thread"
              :trust true
              :messages [{:role "user" :content [{:type :text :text "hi"}]}]})
      (f.chat/open-chat! {:chat-id chat-id} (h/db*) (h/messenger) (h/config))
      (is (match? {:config-updated [{:chat {:select-trust true}}]}
                  (h/messages)))))

  (testing "Opening a non-trusted chat emits config/updated select-trust false (#426)"
    (h/reset-components!)
    (let [chat-id "secured"]
      ;; Pre-seed last-config-notified so the diff actually picks up false.
      (swap! (h/db*) assoc :last-config-notified {:chat {:select-trust true}})
      (swap! (h/db*) assoc-in [:chats chat-id]
             {:id chat-id
              :trust false
              :messages [{:role "user" :content [{:type :text :text "hi"}]}]})
      (f.chat/open-chat! {:chat-id chat-id} (h/db*) (h/messenger) (h/config))
      (is (match? {:config-updated [{:chat {:select-trust false}}]}
                  (h/messages)))))

  (testing "Opening a chat with no :trust key normalizes to false (#426)"
    (h/reset-components!)
    (let [chat-id "legacy"]
      (swap! (h/db*) assoc :last-config-notified {:chat {:select-trust true}})
      (swap! (h/db*) assoc-in [:chats chat-id]
             {:id chat-id
              :messages [{:role "user" :content [{:type :text :text "hi"}]}]})
      (f.chat/open-chat! {:chat-id chat-id} (h/db*) (h/messenger) (h/config))
      (is (match? {:config-updated [{:chat {:select-trust false}}]}
                  (h/messages))))))


