(ns eca.remote.messenger-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.messenger :as messenger]
   [eca.remote.messenger :as remote.messenger]
   [eca.remote.sse :as sse]
   [eca.test-helper :as h]))

(h/reset-components-before-test)

(deftest broadcast-messenger-delegates-and-broadcasts-test
  (let [inner (h/messenger)
        sse-connections* (sse/create-connections)
        broadcast-messenger (remote.messenger/make-broadcast-messenger inner sse-connections*)
        os (java.io.ByteArrayOutputStream.)
        _client (sse/add-client! sse-connections* os)]

    (testing "chat-content-received delegates to inner and broadcasts camelCase"
      (let [data {:chat-id "c1" :role :assistant :content {:type :text :text "hi"}}]
        (messenger/chat-content-received broadcast-messenger data)
        (Thread/sleep 100)
        (is (seq (:chat-content-received (h/messages))))
        (let [output (.toString os "UTF-8")]
          (is (.contains output "chat:content-received"))
          (is (.contains output "\"chatId\"") "SSE broadcast should use camelCase keys")
          (is (not (.contains output "\"chat-id\"")) "SSE broadcast should not use kebab-case keys"))))

    (testing "chat-status-changed delegates and broadcasts camelCase"
      (let [params {:chat-id "c1" :status :running}]
        (messenger/chat-status-changed broadcast-messenger params)
        (Thread/sleep 100)
        (is (seq (:chat-status-changed (h/messages))))
        (let [output (.toString os "UTF-8")]
          (is (.contains output "chat:status-changed"))
          (is (.contains output "\"chatId\"")))))

    (testing "chat-deleted delegates and broadcasts camelCase"
      (let [params {:chat-id "c1"}]
        (messenger/chat-deleted broadcast-messenger params)
        (Thread/sleep 100)
        (is (seq (:chat-deleted (h/messages))))
        (let [output (.toString os "UTF-8")]
          (is (.contains output "chat:deleted"))
          (is (.contains output "\"chatId\"")))))

    (testing "editor-diagnostics delegates to inner only (no broadcast)"
      (let [os2 (java.io.ByteArrayOutputStream.)
            _client2 (sse/add-client! sse-connections* os2)]
        (messenger/editor-diagnostics broadcast-messenger nil)
        (Thread/sleep 100)
        (is (not (.contains (.toString os2 "UTF-8") "editor")))))

    (testing "rewrite-content-received delegates to inner only (no broadcast)"
      (let [os3 (java.io.ByteArrayOutputStream.)
            _client3 (sse/add-client! sse-connections* os3)
            data {:chat-id "c1" :content {:type :text :text "rewritten"}}]
        (messenger/rewrite-content-received broadcast-messenger data)
        (Thread/sleep 100)
        (is (seq (:rewrite-content-received (h/messages))))
        (is (not (.contains (.toString os3 "UTF-8") "rewrite")))))

    (sse/close-all! sse-connections*)))

(deftest ask-question-broadcasts-and-resolves-via-answer-test
  (testing "ask-question registers a promise, broadcasts SSE, and answer-question! resolves it"
    (let [inner (h/messenger)
          ;; Editor doesn't answer; isolates the SSE path (inner is also asked).
          _ (reset! (:ask-question-response* inner) :block)
          sse-connections* (sse/create-connections)
          broadcast-messenger (remote.messenger/make-broadcast-messenger inner sse-connections*)
          os (java.io.ByteArrayOutputStream.)
          _client (sse/add-client! sse-connections* os)
          p (messenger/ask-question broadcast-messenger {:chat-id "c1" :question "Why?"})]
      (Thread/sleep 100)
      (is (not (realized? p)) "promise should not be realized before answer")
      (let [output (.toString os "UTF-8")]
        (is (.contains output "chat:ask-question") "SSE event name should be chat:ask-question")
        (is (.contains output "\"chatId\":\"c1\"") "payload should be camel-cased")
        (is (.contains output "\"requestId\"") "payload should include a generated requestId"))
      (let [pending @(:pending-questions* broadcast-messenger)
            [request-id _] (first pending)]
        (is (= 1 (count pending)) "exactly one pending question should be registered")
        (is (string? request-id))
        (is (= true (remote.messenger/answer-question! broadcast-messenger request-id "because" false)))
        (is (realized? p) "promise should be realized after answer-question!")
        (is (= {:answer "because" :cancelled false} @p))
        (is (empty? @(:pending-questions* broadcast-messenger))
            "registry should be cleared after delivery"))
      (sse/close-all! sse-connections*))))

(deftest ask-question-uses-caller-supplied-request-id-test
  (testing "caller-supplied :request-id is used as the SSE requestId and pending-questions* key"
    (let [inner (h/messenger)
          _ (reset! (:ask-question-response* inner) :block)
          sse-connections* (sse/create-connections)
          broadcast-messenger (remote.messenger/make-broadcast-messenger inner sse-connections*)
          os (java.io.ByteArrayOutputStream.)
          _client (sse/add-client! sse-connections* os)
          supplied-id "fixed-id-for-test"
          p (messenger/ask-question broadcast-messenger {:chat-id "c1" :question "Q?" :request-id supplied-id})]
      (Thread/sleep 100)
      (let [output (.toString os "UTF-8")
            pending @(:pending-questions* broadcast-messenger)]
        (is (contains? pending supplied-id) "pending-questions* should be keyed by the supplied id")
        (is (.contains output (str "\"requestId\":\"" supplied-id "\""))
            "SSE payload should carry the supplied requestId")
        (is (not (.contains output "\"request-id\"")) ":request-id should not appear in the SSE wire payload"))
      (is (true? (remote.messenger/answer-question! broadcast-messenger supplied-id "ok" false)))
      (is (= {:answer "ok" :cancelled false} @p))
      (sse/close-all! sse-connections*))))

(deftest ask-question-falls-back-to-inner-when-no-sse-clients-test
  (testing "ask-question delegates to inner messenger when no SSE clients are connected"
    (let [inner (h/messenger)
          sse-connections* (sse/create-connections)
          broadcast-messenger (remote.messenger/make-broadcast-messenger inner sse-connections*)]
      (reset! (:ask-question-response* inner) {:answer "from-inner" :cancelled false})
      (let [result (messenger/ask-question broadcast-messenger {:chat-id "c1" :question "Why?"})]
        (is (= {:answer "from-inner" :cancelled false} @result))
        (is (empty? @(:pending-questions* broadcast-messenger))
            "no SSE-side registration should occur when delegating to inner")))))

(deftest answer-question-returns-nil-for-unknown-id-test
  (testing "answer-question! returns nil when the request-id is unknown"
    (let [inner (h/messenger)
          sse-connections* (sse/create-connections)
          broadcast-messenger (remote.messenger/make-broadcast-messenger inner sse-connections*)]
      (is (nil? (remote.messenger/answer-question! broadcast-messenger "nonexistent" "x" false))))))

;;; ask-question dual-dispatch: with both an SSE client and the editor (inner)
;;; connected, the question reaches both and the first answer wins.

(deftest ask-question-reaches-editor-and-sse-when-both-connected-test
  (testing "with an SSE client connected, inner (editor) still receives chat/askQuestion"
    (let [inner-params* (atom nil)
          inner (reify messenger/IMessenger
                  (ask-question [_ params]
                    (reset! inner-params* params)
                    (promise)))
          sse-connections* (sse/create-connections)
          broadcast-messenger (remote.messenger/make-broadcast-messenger inner sse-connections*)
          os (java.io.ByteArrayOutputStream.)
          _client (sse/add-client! sse-connections* os)]
      (messenger/ask-question broadcast-messenger
                              {:chat-id "c1" :question "Why?" :request-id "req-1"})
      (Thread/sleep 100)
      (is (some? @inner-params*)
          "editor must receive the question even when an SSE client is connected")
      (is (.contains (.toString os "UTF-8") "chat:ask-question")
          "SSE clients must also receive the question"))))

(deftest ask-question-sse-answer-wins-test
  (testing "an SSE answer resolves the call when both transports are connected"
    (let [inner (reify messenger/IMessenger
                  ;; editor never answers
                  (ask-question [_ _params] (promise)))
          sse-connections* (sse/create-connections)
          broadcast-messenger (remote.messenger/make-broadcast-messenger inner sse-connections*)
          os (java.io.ByteArrayOutputStream.)
          _client (sse/add-client! sse-connections* os)
          result (messenger/ask-question broadcast-messenger
                                         {:chat-id "c1" :question "Q?" :request-id "req-1"})
          watcher (:watcher (get @(:pending-questions* broadcast-messenger) "req-1"))]
      (Thread/sleep 50)
      (is (= :pending (deref result 1 :pending))
          "result should block until someone answers")
      (remote.messenger/answer-question! broadcast-messenger "req-1" "via-sse" false)
      (is (= {:answer "via-sse" :cancelled false} (deref result 1000 :timeout)))
      ;; The editor watcher must be cancelled so it doesn't park forever on an
      ;; editor that may never answer.
      (is (future-cancelled? watcher)))))

(deftest ask-question-sse-answer-retracts-editor-request-test
  (testing "an SSE answer cancels the editor's outstanding request (→ $/cancelRequest)"
    ;; A CompletableFuture models the jsonrpc PendingRequest: future-cancellable,
    ;; and cancelling it is what fires $/cancelRequest in the real ServerMessenger.
    (let [inner-result (java.util.concurrent.CompletableFuture.)
          inner (reify messenger/IMessenger
                  (ask-question [_ _params] inner-result))
          sse-connections* (sse/create-connections)
          broadcast-messenger (remote.messenger/make-broadcast-messenger inner sse-connections*)
          os (java.io.ByteArrayOutputStream.)
          _client (sse/add-client! sse-connections* os)
          result (messenger/ask-question broadcast-messenger
                                         {:chat-id "c1" :question "Q?" :request-id "req-1"})]
      (Thread/sleep 50)
      (remote.messenger/answer-question! broadcast-messenger "req-1" "via-sse" false)
      (is (= {:answer "via-sse" :cancelled false} (deref result 1000 :timeout)))
      (is (future-cancelled? inner-result)
          "the editor's pending request must be cancelled so the server retracts it"))))

(deftest ask-question-editor-answer-wins-test
  (testing "an editor answer resolves the call and cleans up the SSE pending entry"
    (let [inner-promise (promise)
          inner (reify messenger/IMessenger
                  (ask-question [_ _params] inner-promise))
          sse-connections* (sse/create-connections)
          broadcast-messenger (remote.messenger/make-broadcast-messenger inner sse-connections*)
          os (java.io.ByteArrayOutputStream.)
          _client (sse/add-client! sse-connections* os)
          result (messenger/ask-question broadcast-messenger
                                         {:chat-id "c1" :question "Q?" :request-id "req-1"})]
      (Thread/sleep 50)
      (deliver inner-promise {:answer "via-editor" :cancelled false})
      (is (= {:answer "via-editor" :cancelled false} (deref result 1000 :timeout))
          "editor's answer must resolve the call")
      (Thread/sleep 50)
      (is (empty? @(:pending-questions* broadcast-messenger))
          "answering via the editor must clear the SSE pending entry so a late /answer is a no-op"))))
