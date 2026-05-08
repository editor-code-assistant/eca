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
