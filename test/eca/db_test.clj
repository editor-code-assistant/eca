(ns eca.db-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.db :as db]))

(set! *warn-on-reflection* true)

(deftest cleanup-old-chats-test
  (let [now (System/currentTimeMillis)
        fifteen-days-ago (- now (* 15 24 60 60 1000))
        two-days-ago (- now (* 2 24 60 60 1000))
        db* (atom {:chats {"old-chat" {:id "old-chat"
                                       :created-at fifteen-days-ago
                                       :messages [{:role "user" :content "hi"}]}
                           "recent-chat" {:id "recent-chat"
                                          :created-at two-days-ago
                                          :messages [{:role "user" :content "hello"}]}
                           "no-timestamp" {:id "no-timestamp"
                                           :messages [{:role "user" :content "hey"}]}}
                   :workspace-folders []})]
    (testing "deletes old chats, keeps recent and chats without created-at"
      (with-redefs [db/update-workspaces-cache! (fn [_ _])]
        (db/cleanup-old-chats! db* nil 14))
      (is (nil? (get-in @db* [:chats "old-chat"]))
          "Chat older than 14 days should be removed")
      (is (some? (get-in @db* [:chats "recent-chat"]))
          "Chat newer than 14 days should be kept")
      (is (some? (get-in @db* [:chats "no-timestamp"]))
          "Chat without created-at should be kept"))))

(deftest cleanup-old-chats-no-op-test
  (let [now (System/currentTimeMillis)
        two-days-ago (- now (* 2 24 60 60 1000))
        db* (atom {:chats {"recent" {:id "recent"
                                     :created-at two-days-ago
                                     :messages [{:role "user" :content "hi"}]}}
                   :workspace-folders []})
        cache-updated? (atom false)]
    (testing "does not flush cache when nothing to clean"
      (with-redefs [db/update-workspaces-cache! (fn [_ _] (reset! cache-updated? true))]
        (db/cleanup-old-chats! db* nil 14))
      (is (some? (get-in @db* [:chats "recent"])))
      (is (false? @cache-updated?)
          "Should not flush cache when no chats were removed"))))

(deftest cleanup-old-chats-disabled-test
  (let [now (System/currentTimeMillis)
        fifteen-days-ago (- now (* 15 24 60 60 1000))
        db* (atom {:chats {"old-chat" {:id "old-chat"
                                       :created-at fifteen-days-ago
                                       :messages [{:role "user" :content "hi"}]}}
                   :workspace-folders []})
        cache-updated? (atom false)]
    (testing "does not clean up when retention-days is 0"
      (with-redefs [db/update-workspaces-cache! (fn [_ _] (reset! cache-updated? true))]
        (db/cleanup-old-chats! db* nil 0))
      (is (some? (get-in @db* [:chats "old-chat"]))
          "Old chat should be kept when cleanup is disabled")
      (is (false? @cache-updated?)))))
