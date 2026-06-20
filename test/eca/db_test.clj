(ns eca.db-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [cognitect.transit :as transit]
   [eca.cache :as cache]
   [eca.db :as db]
   [eca.shared :as shared])
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

(deftest resolve-trust-test
  (let [db {:chats {"root"      {:id "root" :trust true}
                    "sub"       {:id "sub" :parent-chat-id "root"}
                    "sub-own"   {:id "sub-own" :parent-chat-id "root" :trust false}
                    "mid"       {:id "mid" :parent-chat-id "root"}
                    "leaf"      {:id "leaf" :parent-chat-id "mid"}
                    "no-trust"  {:id "no-trust"}
                    "deny-root" {:id "deny-root" :trust false}
                    "deny-sub"  {:id "deny-sub" :parent-chat-id "deny-root"}
                    "loop"      {:id "loop" :parent-chat-id "loop"}}}]
    (testing "returns the chat's own trust when set"
      (is (true? (db/resolve-trust db "root"))))
    (testing "a running subagent with no own trust inherits the parent's trust toggled after spawn (#504)"
      (is (true? (db/resolve-trust db "sub"))))
    (testing "a nested subagent inherits trust from the root of the chain"
      (is (true? (db/resolve-trust db "leaf"))))
    (testing "the subagent's own trust takes precedence over the parent"
      (is (false? (db/resolve-trust db "sub-own"))))
    (testing "an explicit false is respected (inherited, not treated as unset)"
      (is (false? (db/resolve-trust db "deny-sub"))))
    (testing "returns nil when no chat in the chain has trust set"
      (is (nil? (db/resolve-trust db "no-trust"))))
    (testing "returns nil for an unknown chat-id"
      (is (nil? (db/resolve-trust db "missing"))))
    (testing "a self-referential parent chain terminates instead of looping forever"
      (is (nil? (db/resolve-trust db "loop"))))))

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

(deftest upsert-cache!-handles-concurrent-writers-test
  (testing "many threads writing to the same cache-file do not error and leave a valid snapshot"
    (let [tmpdir (str (fs/create-temp-dir))
          cache-file (File. ^String tmpdir "db.transit.json")
          upsert! @#'db/upsert-cache!
          n 32
          payloads (mapv (fn [i] {:version db/version
                                  :chats {"c" {:id (str "c" i)}}})
                         (range n))]
      (try
        (let [start-gate (java.util.concurrent.CountDownLatch. 1)
              futs (mapv (fn [payload]
                           (future
                             (.await start-gate)
                             (upsert! payload cache-file nil)))
                         payloads)]
          (.countDown start-gate)
          (doseq [f futs] @f))
        (is (.exists cache-file)
            "destination should exist after concurrent writes")
        (let [final (read-transit-file cache-file)]
          (is (= db/version (:version final))
              "destination should round-trip as a valid Transit payload")
          (is (some #(= % final) payloads)
              "final destination should equal one of the written snapshots (last-writer-wins)"))
        (let [stragglers (->> (.listFiles (File. ^String tmpdir))
                              (filter (fn [^File f]
                                        (.endsWith (.getName f) ".tmp"))))]
          (is (empty? stragglers)
              "no leftover *.tmp files should remain after the writers finish"))
        (finally (fs/delete-tree tmpdir))))))

(deftest sync-auth-from-cache!-adopts-fresher-disk-tokens-test
  (testing "when on-disk :auth has a different :expires-at, in-memory state is overwritten"
    (let [tmpdir (str (fs/create-temp-dir))]
      (with-redefs [cache/global-dir (constantly (io/file tmpdir))]
        (try
          (let [fresh-auth {:type :auth/oauth
                            :mode :max
                            :step :login/done
                            :refresh-token "fresh-refresh"
                            :api-key "fresh-access"
                            :expires-at 9999999999}
                disk-db (atom {:auth {"anthropic" fresh-auth}})
                _ (db/update-global-cache! @disk-db nil)
                stale-mem (atom {:auth {"anthropic" {:type :auth/oauth
                                                     :mode :max
                                                     :step :login/done
                                                     :refresh-token "stale-refresh"
                                                     :api-key "stale-access"
                                                     :expires-at 1000}}})
                updated? (db/sync-auth-from-cache! stale-mem "anthropic" nil)]
            (is updated?)
            (is (= "fresh-refresh" (get-in @stale-mem [:auth "anthropic" :refresh-token])))
            (is (= "fresh-access" (get-in @stale-mem [:auth "anthropic" :api-key])))
            (is (= 9999999999 (get-in @stale-mem [:auth "anthropic" :expires-at]))))
          (finally (fs/delete-tree tmpdir)))))))

(deftest sync-auth-from-cache!-noop-when-disk-matches-memory-test
  (testing "when on-disk :expires-at matches memory, no update happens"
    (let [tmpdir (str (fs/create-temp-dir))]
      (with-redefs [cache/global-dir (constantly (io/file tmpdir))]
        (try
          (let [auth {:type :auth/oauth
                      :refresh-token "same"
                      :api-key "same"
                      :expires-at 7777}
                disk-db (atom {:auth {"anthropic" auth}})
                _ (db/update-global-cache! @disk-db nil)
                mem (atom {:auth {"anthropic" auth}})
                updated? (db/sync-auth-from-cache! mem "anthropic" nil)]
            (is (not updated?))
            (is (= auth (get-in @mem [:auth "anthropic"]))))
          (finally (fs/delete-tree tmpdir)))))))

(deftest sync-auth-from-cache!-noop-when-no-disk-cache-test
  (testing "when no global cache file exists, returns falsy and leaves memory untouched"
    (let [tmpdir (str (fs/create-temp-dir))]
      (with-redefs [cache/global-dir (constantly (io/file tmpdir))]
        (try
          (let [mem (atom {:auth {"anthropic" {:refresh-token "x" :expires-at 1}}})
                updated? (db/sync-auth-from-cache! mem "anthropic" nil)]
            (is (not updated?))
            (is (= "x" (get-in @mem [:auth "anthropic" :refresh-token]))))
          (finally (fs/delete-tree tmpdir)))))))

(deftest with-global-cache-lock-runs-body-and-releases-test
  (testing "the lock can be acquired sequentially without leaking handles"
    (let [tmpdir (str (fs/create-temp-dir))]
      (with-redefs [cache/global-dir (constantly (io/file tmpdir))]
        (try
          (let [ran (atom 0)]
            (db/with-global-cache-lock (swap! ran inc))
            (db/with-global-cache-lock (swap! ran inc))
            (is (= 2 @ran)))
          (finally (fs/delete-tree tmpdir)))))))

(deftest with-global-cache-lock-serializes-concurrent-threads-test
  (testing "two threads acquiring the lock cannot interleave inside the body"
    (let [tmpdir (str (fs/create-temp-dir))]
      (with-redefs [cache/global-dir (constantly (io/file tmpdir))]
        (try
          (let [inside (atom 0)
                max-inside (atom 0)
                iterations 50
                worker (fn []
                         (dotimes [_ iterations]
                           (db/with-global-cache-lock
                             (let [v (swap! inside inc)]
                               (swap! max-inside max v)
                               ;; small spin so a non-locking impl would observe overlap
                               (Thread/sleep 1)
                               (swap! inside dec)))))
                f1 (future (worker))
                f2 (future (worker))]
            @f1 @f2
            (is (= 1 @max-inside)
                "no two threads should ever be inside the locked body at the same time"))
          (finally (fs/delete-tree tmpdir)))))))

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

(deftest stamp-chat-ids-test
  (testing "every chat value gets its map key as :id"
    (is (= {"a" {:id "a" :title "A"}
            "b" {:id "b"}}
           (db/stamp-chat-ids {"a" {:title "A"}
                               "b" {}}))))
  (testing "a stale/mismatched :id is corrected to the map key"
    (is (= {"a" {:id "a"}}
           (db/stamp-chat-ids {"a" {:id "wrong"}}))))
  (testing "nil chats normalize to an empty map"
    (is (= {} (db/stamp-chat-ids nil)))))

(deftest normalize-stamps-chat-id-test
  (let [normalize @#'db/normalize-db-for-workspace-write
        result (normalize {:chats {"legacy" {:title "no id in value"}}})]
    (testing "persisted chats always carry their :id"
      (is (= "legacy" (get-in result [:chats "legacy" :id]))))))

(deftest consolidate-workspace-cache!-merges-and-removes-redundant-dirs-test
  (testing "merges chats from a hash-only dir into the canonical dir (newest wins) and removes the redundant dir"
    (let [tmpdir (str (fs/create-temp-dir))]
      (with-redefs [cache/global-dir (constantly (io/file tmpdir))]
        (try
          (let [workspaces [{:uri "file:///home/user/projX"}]
                canonical (cache/workspace-cache-file workspaces "db.transit.json" shared/uri->filename)
                ws-hash (cache/workspaces-hash workspaces shared/uri->filename)
                hash-only-dir (io/file (cache/global-dir) ws-hash)
                hash-only-file (io/file hash-only-dir "db.transit.json")
                upsert! @#'db/upsert-cache!]
            ;; canonical holds an older copy of chat "a"
            (upsert! {:version db/version :chats {"a" {:id "a" :updated-at 100 :title "old-a"}}} canonical nil)
            ;; a legacy hash-only dir holds a newer "a" plus an extra chat "b"
            (upsert! {:version db/version :chats {"a" {:id "a" :updated-at 200 :title "new-a"}
                                                  "b" {:id "b" :updated-at 50 :title "b"}}} hash-only-file nil)
            (db/consolidate-workspace-cache! workspaces nil)
            (let [merged (:chats (read-transit-file canonical))]
              (is (= #{"a" "b"} (set (keys merged))) "all chats end up in the canonical dir")
              (is (= "new-a" (get-in merged ["a" :title])) "newest :updated-at wins on conflict")
              (is (not (fs/exists? hash-only-dir)) "redundant dir is removed")))
          (finally (fs/delete-tree tmpdir)))))))
