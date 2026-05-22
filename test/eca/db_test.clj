(ns eca.db-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [cognitect.transit :as transit]
   [eca.db :as db])
  (:import
   [java.io File]))

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

(defn ^:private read-transit-file ^Object [^File f]
  (with-open [is (io/input-stream f)]
    (transit/read (transit/reader is :json))))

(deftest atomic-upsert-cache-test
  (testing "writes via tmp file then atomically renames so a crash mid-write cannot truncate the destination"
    (let [tmpdir (str (fs/create-temp-dir))
          cache-file (File. ^String tmpdir "db.transit.json")
          tmp-file (File. (str (.getPath cache-file) ".tmp"))
          upsert! @#'db/upsert-cache!]
      (try
        (upsert! {:version db/version :chats {"c1" {:id "c1"}}} cache-file nil)
        (is (.exists cache-file) "destination should exist after a successful write")
        (is (not (.exists tmp-file)) "tmp file should be cleaned up after the rename")
        (is (= db/version (:version (read-transit-file cache-file)))
            "written payload should round-trip")
        (finally (fs/delete-tree tmpdir))))))

(deftest stale-tmp-does-not-corrupt-next-write-test
  (testing "a leftover .tmp file from a previous crashed save is replaced cleanly by the next save"
    (let [tmpdir (str (fs/create-temp-dir))
          cache-file (File. ^String tmpdir "db.transit.json")
          tmp-file (File. (str (.getPath cache-file) ".tmp"))
          upsert! @#'db/upsert-cache!]
      (try
        (io/make-parents tmp-file)
        (spit tmp-file "garbage from a previous crash")
        (upsert! {:version db/version :chats {"c1" {:id "c1"}}} cache-file nil)
        (is (.exists cache-file))
        (is (not (.exists tmp-file))
            "stale .tmp from a previous run should not survive the next save")
        (is (= db/version (:version (read-transit-file cache-file))))
        (finally (fs/delete-tree tmpdir))))))

(deftest normalize-preserves-empty-message-chats-test
  (let [normalize @#'db/normalize-db-for-workspace-write
        result (normalize {:chats {"empty" {:id "empty" :messages []}
                                   "no-msgs-key" {:id "no-msgs-key"}
                                   "with-msg" {:id "with-msg"
                                               :messages [{:role "user" :content "hi"}]
                                               :tool-calls {"t1" {:status :completed}}}}})]
    (testing "chats with empty :messages are kept (e.g. after rollback or early provider error)"
      (is (contains? (:chats result) "empty")))
    (testing "chats without a :messages key are also kept"
      (is (contains? (:chats result) "no-msgs-key")))
    (testing ":tool-calls runtime state is stripped before persisting"
      (is (not (contains? (get-in result [:chats "with-msg"]) :tool-calls))))))
