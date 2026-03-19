(ns eca.remote.messenger-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [eca.remote.messenger :as remote.messenger]
   [eca.remote.sse :as sse]
   [eca.test-helper :as h]))

(h/reset-components-before-test)

(deftest broadcast-messenger-delegates-and-broadcasts-test
  (let [inner (h/messenger)
        sse-connections* (sse/create-connections)
        broadcast-messenger (remote.messenger/->BroadcastMessenger inner sse-connections*)
        os (java.io.ByteArrayOutputStream.)
        _client (sse/add-client! sse-connections* os)]

    (testing "chat-content-received delegates to inner and broadcasts camelCase"
      (let [data {:chat-id "c1" :role :assistant :content {:type :text :text "hi"}}]
        (eca.messenger/chat-content-received broadcast-messenger data)
        (Thread/sleep 100)
        (is (seq (:chat-content-received (h/messages))))
        (let [output (.toString os "UTF-8")]
          (is (.contains output "chat:content-received"))
          (is (.contains output "\"chatId\"") "SSE broadcast should use camelCase keys")
          (is (not (.contains output "\"chat-id\"")) "SSE broadcast should not use kebab-case keys"))))

    (testing "chat-status-changed delegates and broadcasts camelCase"
      (let [params {:chat-id "c1" :status :running}]
        (eca.messenger/chat-status-changed broadcast-messenger params)
        (Thread/sleep 100)
        (is (seq (:chat-status-changed (h/messages))))
        (let [output (.toString os "UTF-8")]
          (is (.contains output "chat:status-changed"))
          (is (.contains output "\"chatId\"")))))

    (testing "chat-deleted delegates and broadcasts camelCase"
      (let [params {:chat-id "c1"}]
        (eca.messenger/chat-deleted broadcast-messenger params)
        (Thread/sleep 100)
        (is (seq (:chat-deleted (h/messages))))
        (let [output (.toString os "UTF-8")]
          (is (.contains output "chat:deleted"))
          (is (.contains output "\"chatId\"")))))

    (testing "editor-diagnostics delegates to inner only (no broadcast)"
      (let [os2 (java.io.ByteArrayOutputStream.)
            _client2 (sse/add-client! sse-connections* os2)]
        (eca.messenger/editor-diagnostics broadcast-messenger nil)
        (Thread/sleep 100)
        (is (not (.contains (.toString os2 "UTF-8") "editor")))))

    (testing "rewrite-content-received delegates to inner only (no broadcast)"
      (let [os3 (java.io.ByteArrayOutputStream.)
            _client3 (sse/add-client! sse-connections* os3)
            data {:chat-id "c1" :content {:type :text :text "rewritten"}}]
        (eca.messenger/rewrite-content-received broadcast-messenger data)
        (Thread/sleep 100)
        (is (seq (:rewrite-content-received (h/messages))))
        (is (not (.contains (.toString os3 "UTF-8") "rewrite")))))

    (sse/close-all! sse-connections*)))
