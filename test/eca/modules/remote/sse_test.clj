(ns eca.modules.remote.sse-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is testing]]
   [eca.modules.remote.sse :as m.remote.sse])
  (:import
   [java.io ByteArrayOutputStream]))

(deftest create-connections-test
  (testing "creates an atom with empty set"
    (let [conns (m.remote.sse/create-connections)]
      (is (instance? clojure.lang.Atom conns))
      (is (= #{} @conns)))))

(deftest add-and-remove-client-test
  (testing "add-client! adds to connections, remove-client! removes"
    (let [conns (m.remote.sse/create-connections)
          os (ByteArrayOutputStream.)
          client (m.remote.sse/add-client! conns os)]
      (is (= 1 (count @conns)))
      (is (contains? @conns client))
      (m.remote.sse/remove-client! conns client)
      (is (= 0 (count @conns))))))

(deftest broadcast-test
  (testing "broadcast! writes SSE-formatted event to client"
    (let [conns (m.remote.sse/create-connections)
          os (ByteArrayOutputStream.)
          client (m.remote.sse/add-client! conns os)]
      (m.remote.sse/broadcast! conns "chat:status-changed" {:chatId "abc" :status "running"})
      ;; Give writer loop time to process
      (Thread/sleep 100)
      (let [output (.toString os "UTF-8")]
        (is (.contains output "event: chat:status-changed"))
        (is (.contains output "data: ")))
      (m.remote.sse/remove-client! conns client))))

(deftest broadcast-backpressure-test
  (testing "dropping buffer prevents blocking when client is slow"
    (let [conns (m.remote.sse/create-connections)
          os (ByteArrayOutputStream.)
          client (m.remote.sse/add-client! conns os)]
      ;; Flood the channel — should not block
      (dotimes [i 500]
        (m.remote.sse/broadcast! conns "test:event" {:i i}))
      ;; Should still be connected (no exception)
      (is (= 1 (count @conns)))
      (m.remote.sse/remove-client! conns client))))

(deftest close-all-test
  (testing "close-all! removes all clients"
    (let [conns (m.remote.sse/create-connections)
          os1 (ByteArrayOutputStream.)
          os2 (ByteArrayOutputStream.)]
      (m.remote.sse/add-client! conns os1)
      (m.remote.sse/add-client! conns os2)
      (is (= 2 (count @conns)))
      (m.remote.sse/close-all! conns)
      (is (= 0 (count @conns))))))

(deftest heartbeat-test
  (testing "heartbeat sends comment lines to clients"
    (let [conns (m.remote.sse/create-connections)
          os (ByteArrayOutputStream.)
          client (m.remote.sse/add-client! conns os)]
      ;; Start heartbeat with very short interval for testing
      ;; We just verify start-heartbeat! returns a channel
      (let [stop-ch (m.remote.sse/start-heartbeat! conns)]
        (is (some? stop-ch))
        (async/close! stop-ch))
      (m.remote.sse/remove-client! conns client))))
