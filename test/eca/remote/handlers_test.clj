(ns eca.remote.handlers-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [eca.config :as config]
   [eca.db :as db]
   [eca.remote.handlers :as handlers]
   [eca.test-helper :as h]))

(h/reset-components-before-test)

(defn- components []
  (let [c (h/components)]
    (assoc c :config (config/all @(:db* c)))))

(deftest handle-health-test
  (testing "returns ok with version"
    (let [response (handlers/handle-health nil nil)
          body (json/parse-string (:body response) true)]
      (is (= 200 (:status response)))
      (is (= "ok" (:status body)))
      (is (string? (:version body))))))

(deftest handle-root-test
  (testing "redirects to web.eca.dev"
    (let [response (handlers/handle-root nil nil {:host "192.168.1.1:7888" :token "abc123"})]
      (is (= 302 (:status response)))
      (is (= "https://web.eca.dev?host=192.168.1.1:7888&token=abc123"
             (get-in response [:headers "Location"]))))))

(deftest handle-list-chats-test
  (testing "returns empty list when no chats"
    (let [response (handlers/handle-list-chats (components) nil)
          body (json/parse-string (:body response) true)]
      (is (= 200 (:status response)))
      (is (= [] body))))

  (testing "returns chats excluding subagents"
    (swap! (h/db*) assoc :chats {"c1" {:id "c1" :title "Test" :status :idle :created-at 123}
                                   "c2" {:id "c2" :title "Sub" :status :running :subagent true}})
    (let [response (handlers/handle-list-chats (components) nil)
          body (json/parse-string (:body response) true)]
      (is (= 1 (count body)))
      (is (= "c1" (:id (first body)))))))

(deftest handle-get-chat-test
  (testing "returns 404 for missing chat"
    (let [response (handlers/handle-get-chat (components) nil "nonexistent")
          body (json/parse-string (:body response) true)]
      (is (= 404 (:status response)))
      (is (= "chat_not_found" (get-in body [:error :code])))))

  (testing "returns chat data for existing chat"
    (swap! (h/db*) assoc-in [:chats "c1"] {:id "c1" :title "My Chat" :status :idle :messages []})
    (let [response (handlers/handle-get-chat (components) nil "c1")
          body (json/parse-string (:body response) true)]
      (is (= 200 (:status response)))
      (is (= "c1" (:id body)))
      (is (= "My Chat" (:title body))))))

(deftest handle-stop-test
  (testing "returns 404 for missing chat"
    (let [response (handlers/handle-stop (components) nil "nonexistent")]
      (is (= 404 (:status response)))))

  (testing "returns 409 for non-running chat"
    (swap! (h/db*) assoc-in [:chats "c1"] {:id "c1" :status :idle})
    (let [response (handlers/handle-stop (components) nil "c1")]
      (is (= 409 (:status response))))))

(deftest handle-prompt-test
  (testing "returns 400 for missing message"
    (let [request {:body (java.io.ByteArrayInputStream. (.getBytes "{}" "UTF-8"))}
          response (handlers/handle-prompt (components) request "c1")]
      (is (= 400 (:status response))))))

(deftest handle-session-test
  (testing "returns session info"
    (let [response (handlers/handle-session (components) nil)
          body (json/parse-string (:body response) true)]
      (is (= 200 (:status response)))
      (is (string? (:version body)))
      (is (= "1.0" (:protocolVersion body))))))
