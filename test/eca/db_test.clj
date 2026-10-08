(ns eca.db-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [cognitect.transit :as transit]
   [eca.cache :as cache]
   [eca.db :as db]
   [eca.shared :as shared]
   [eca.test-helper :as h])
  (:import
   [java.io File]))

(set! *warn-on-reflection* true)

(defn ^:private read-transit-file ^Object [^File f]
  (with-open [is (io/input-stream f)]
    (transit/read (transit/reader is :json))))

(defn ^:private write-transit! [^File f data]
  (io/make-parents f)
  (with-open [os (io/output-stream f)]
    (transit/write (transit/writer os :json) data)))

(defn ^:private chat-cache-file ^File [workspaces chat-id]
  (io/file (cache/workspace-cache-dir workspaces shared/uri->filename)
           "chats" (db/chat-file-name chat-id)))

(defn ^:private chats-index-file ^File [workspaces]
  (io/file (cache/workspace-cache-dir workspaces shared/uri->filename)
           "chats" "index.transit.json"))

(defn ^:private read-index [workspaces]
  (:chats (read-transit-file (chats-index-file workspaces))))

(defn ^:private read-chat-cache [workspaces chat-id]
  (:chat (read-transit-file (chat-cache-file workspaces chat-id))))

(defn ^:private legacy-chat-cache-file ^File [workspaces chat-id]
  (io/file (cache/legacy-workspace-cache-dir workspaces shared/uri->filename)
           "chats" (db/chat-file-name chat-id)))

(defn ^:private write-legacy-chat!
  "Persists `chat` in the legacy cache dir the way an older ECA does: its own
   file plus an entry in that dir's chats index."
  [workspaces chat]
  (write-transit! (legacy-chat-cache-file workspaces (:id chat))
                  {:version db/chats-version :chat chat})
  (let [index-file (io/file (cache/legacy-workspace-cache-dir workspaces shared/uri->filename)
                            "chats" "index.transit.json")
        entries (when (fs/exists? index-file) (:chats (read-transit-file index-file)))]
    (write-transit! index-file
                    {:version db/chats-version
                     :chats (assoc entries (:id chat) (db/chat-list-meta chat))})))

(deftest chat-file-name-test
  (testing "uuid-like ids are used as-is"
    (is (= "aaaaaaaa-1111-2222-3333-444444444444.transit.json"
           (db/chat-file-name "aaaaaaaa-1111-2222-3333-444444444444"))))
  (testing "path-hostile, short, uppercase, or index-colliding ids fall back to a digest"
    (is (re-matches #"[0-9a-f]{64}\.transit\.json" (db/chat-file-name "../../evil")))
    (is (re-matches #"[0-9a-f]{64}\.transit\.json" (db/chat-file-name "short")))
    (is (re-matches #"[0-9a-f]{64}\.transit\.json" (db/chat-file-name "MyChat-12345"))
        "uppercase would collide on case-insensitive filesystems")
    (is (re-matches #"[0-9a-f]{64}\.transit\.json" (db/chat-file-name "index-collision")))
    (is (re-matches #"[0-9a-f]{64}\.transit\.json" (db/chat-file-name nil))
        "non-string ids never throw"))
  (testing "the same id always yields the same file name"
    (is (= (db/chat-file-name "../../evil") (db/chat-file-name "../../evil")))))

(deftest chat-list-meta-test
  (testing "hydrated chats compute message-derived fields"
    (is (= {:id "a" :title "A" :message-count 3 :user-message-count 1 :flags ["pin"]}
           (db/chat-list-meta {:id "a" :title "A"
                               :tool-calls {"t" {:status :completed}}
                               :messages [{:role "user" :content "hi"}
                                          {:role "assistant" :content "yo"}
                                          {:role "flag" :content {:text "pin"}}]}))))
  (testing "index-only entries pass stored fields through (marker itself is not projected)"
    (is (= {:id "a" :message-count 7 :user-message-count 2 :flags []}
           (db/chat-list-meta {:id "a" :index-only? true
                               :message-count 7 :user-message-count 2 :flags []}))))
  (testing "runtime-only keys are never included"
    (is (not (contains? (db/chat-list-meta {:id "a" :messages [] :prompt-cache {:x 1}})
                        :prompt-cache)))))

(deftest normalize-chat-for-write-test
  (let [normalize @#'db/normalize-chat-for-write]
    (testing ":tool-calls and :last-status-payload runtime state is stripped and :id stamped"
      (is (= {:id "a" :title "A" :messages []}
             (normalize "a" {:title "A" :messages []
                             :tool-calls {"t" {:status :completed}}
                             :last-status-payload {:x 1}}))))
    (testing "chats with empty or absent :messages are preserved (e.g. after rollback or early provider error)"
      (is (= {:id "a" :messages []} (normalize "a" {:messages []})))
      (is (= {:id "a"} (normalize "a" {}))))))

(deftest save-chat!-writes-per-chat-file-and-index-test
  (let [tmpdir (str (fs/create-temp-dir))]
    (h/with-cache-root tmpdir
      (try
        (let [workspaces [{:uri "file:///home/user/save"}]
              db {:workspace-folders workspaces
                  :chats {"a" {:id "a" :title "A" :updated-at 100
                               :messages [{:role "user" :content "hi"}]
                               :tool-calls {"t1" {:status :completed}}}}}]
          (db/save-chat! db "a" nil)
          (let [chat (read-chat-cache workspaces "a")]
            (is (= "A" (:title chat)))
            (is (= 1 (count (:messages chat))))
            (is (not (contains? chat :tool-calls)) ":tool-calls runtime state is stripped"))
          (is (= {"a" {:id "a" :title "A" :updated-at 100
                       :message-count 1 :user-message-count 1 :flags []}}
                 (read-index workspaces))))
        (finally (fs/delete-tree tmpdir))))))

(deftest save-chat!-peer-recency-guard-test
  (testing "a stale save never clobbers a chat file a peer process advanced (#558)"
    (let [tmpdir (str (fs/create-temp-dir))]
      (h/with-cache-root tmpdir
        (try
          (let [workspaces [{:uri "file:///home/user/peer-guard"}]
                db {:workspace-folders workspaces
                    :chats {"a" {:id "a" :title "mem" :updated-at 100
                                 :messages [{:role "user" :content "hi"}]}}}]
            (db/save-chat! db "a" nil)
            ;; peer process advances the chat on disk
            (write-transit! (chat-cache-file workspaces "a")
                            {:version db/chats-version
                             :chat {:id "a" :title "peer" :updated-at 200
                                    :messages [{:role "user" :content "hi"}
                                               {:role "assistant" :content "newer"}]}})
            (db/save-chat! db "a" nil)
            (let [chat (read-chat-cache workspaces "a")]
              (is (= "peer" (:title chat)) "strictly newer peer copy is kept")
              (is (= 2 (count (:messages chat)))))
            ;; on equal recency this process's copy wins (mid-prompt mutations)
            (write-transit! (chat-cache-file workspaces "a")
                            {:version db/chats-version
                             :chat {:id "a" :title "peer-tie" :updated-at 100 :messages []}})
            (db/save-chat! db "a" nil)
            (is (= "mem" (:title (read-chat-cache workspaces "a")))))
          (finally (fs/delete-tree tmpdir)))))))

(deftest save-chat!-does-not-clobber-unreadable-file-test
  (testing "a meta-only save skips the file write when the existing chat file cannot be read"
    (let [tmpdir (str (fs/create-temp-dir))]
      (h/with-cache-root tmpdir
        (try
          (let [workspaces [{:uri "file:///home/user/unreadable"}]
                f (chat-cache-file workspaces "a")]
            (io/make-parents f)
            (spit f "garbage from a corrupted write")
            (db/save-chat! {:workspace-folders workspaces
                            :chats {"a" {:id "a" :title "Renamed" :updated-at 100
                                         :index-only? true :message-count 3}}}
                           "a" nil)
            (is (= "garbage from a corrupted write" (slurp f))
                "original bytes stay recoverable"))
          (finally (fs/delete-tree tmpdir)))))))

(deftest save-chat!-hydration-safe-test
  (testing "saving an index-only copy merges its meta over the on-disk chat instead of clobbering messages"
    (let [tmpdir (str (fs/create-temp-dir))]
      (h/with-cache-root tmpdir
        (try
          (let [workspaces [{:uri "file:///home/user/hydration-safe"}]]
            (db/save-chat! {:workspace-folders workspaces
                            :chats {"a" {:id "a" :title "A" :updated-at 100
                                         :messages [{:role "user" :content "hi"}]}}}
                           "a" nil)
            ;; a later session renames the chat without ever loading its messages
            (db/save-chat! {:workspace-folders workspaces
                            :chats {"a" {:id "a" :title "Renamed" :updated-at 200
                                         :index-only? true
                                         :message-count 1 :user-message-count 1 :flags []}}}
                           "a" nil)
            (let [chat (read-chat-cache workspaces "a")]
              (is (= "Renamed" (:title chat)))
              (is (= 1 (count (:messages chat))) "messages survive a meta-only save")
              (is (not (contains? chat :message-count)) "computed index fields never leak into the chat file")))
          (finally (fs/delete-tree tmpdir)))))))

(deftest hydrate-chat!-test
  (let [tmpdir (str (fs/create-temp-dir))]
    (h/with-cache-root tmpdir
      (try
        (let [workspaces [{:uri "file:///home/user/hydrate"}]
              chat {:id "a" :title "A" :updated-at 100
                    :messages [{:role "user" :content "hi"}
                               {:role "assistant" :content "yo"}]}]
          (db/save-chat! {:workspace-folders workspaces :chats {"a" chat}} "a" nil)
          (testing "loads messages for an index-only entry, dropping computed keys and the marker"
            (let [db* (atom {:workspace-folders workspaces
                             :chats {"a" (assoc (db/chat-list-meta chat) :index-only? true)}})]
              (db/hydrate-chat! db* "a" nil)
              (let [hydrated (get-in @db* [:chats "a"])]
                (is (= 2 (count (:messages hydrated))))
                (is (not (contains? hydrated :message-count)))
                (is (not (contains? hydrated :index-only?)))
                (is (= "A" (:title hydrated))))))
          (testing "in-memory meta wins on equal recency (e.g. renamed before hydration)"
            (let [db* (atom {:workspace-folders workspaces
                             :chats {"a" (assoc (db/chat-list-meta chat)
                                                :index-only? true :title "Renamed")}})]
              (db/hydrate-chat! db* "a" nil)
              (is (= "Renamed" (get-in @db* [:chats "a" :title])))
              (is (= 2 (count (get-in @db* [:chats "a" :messages]))))))
          (testing "a strictly newer disk copy wins entirely"
            (let [db* (atom {:workspace-folders workspaces
                             :chats {"a" {:id "a" :title "Stale-mem" :updated-at 50
                                          :index-only? true}}})]
              (db/hydrate-chat! db* "a" nil)
              (is (= "A" (get-in @db* [:chats "a" :title])))))
          (testing "live chats are authoritative: never overwritten from disk even without :messages"
            (let [db* (atom {:workspace-folders workspaces
                             :chats {"a" {:id "a" :title "Live" :updated-at 1}}})]
              (db/hydrate-chat! db* "a" nil)
              (is (= "Live" (get-in @db* [:chats "a" :title])))
              (is (not (contains? (get-in @db* [:chats "a"]) :messages)))))
          (testing "an index-only entry whose file vanished becomes authoritative memory"
            (let [db* (atom {:workspace-folders workspaces
                             :chats {"gone" {:id "gone" :title "G" :index-only? true}}})]
              (db/hydrate-chat! db* "gone" nil)
              (is (= {:id "gone" :title "G"} (get-in @db* [:chats "gone"])))))
          (testing "no-op for unknown ids"
            (let [db* (atom {:workspace-folders workspaces :chats {}})]
              (db/hydrate-chat! db* "missing" nil)
              (is (= {} (:chats @db*))))))
        (finally (fs/delete-tree tmpdir))))))

(deftest save-all-chats!-test
  (testing "persists every hydrated chat and one index covering index-only entries too"
    (let [tmpdir (str (fs/create-temp-dir))]
      (h/with-cache-root tmpdir
        (try
          (let [workspaces [{:uri "file:///home/user/save-all"}]
                db {:workspace-folders workspaces
                    :chats {"h1" {:id "h1" :updated-at 1 :messages [{:role "user" :content "a"}]}
                            "h2" {:id "h2" :updated-at 2 :messages []}
                            "cold" {:id "cold" :title "Cold" :updated-at 3
                                    :index-only? true :message-count 9}}}]
            (db/save-all-chats! db nil)
            (is (fs/exists? (chat-cache-file workspaces "h1")))
            (is (fs/exists? (chat-cache-file workspaces "h2")))
            (is (not (fs/exists? (chat-cache-file workspaces "cold")))
                "index-only entries are not rewritten")
            (let [idx (read-index workspaces)]
              (is (= #{"h1" "h2" "cold"} (set (keys idx))))
              (is (= 9 (get-in idx ["cold" :message-count]))
                  "stored meta passes through for index-only entries")))
          (finally (fs/delete-tree tmpdir)))))))

(deftest delete-chat-from-cache!-test
  (let [tmpdir (str (fs/create-temp-dir))]
    (h/with-cache-root tmpdir
      (try
        (let [workspaces [{:uri "file:///home/user/delete"}]
              db {:workspace-folders workspaces
                  :chats {"a" {:id "a" :updated-at 1 :messages []}
                          "b" {:id "b" :updated-at 2 :messages []}}}]
          (db/save-chat! db "a" nil)
          (db/save-chat! db "b" nil)
          (let [db (-> db
                       (update :chats dissoc "b")
                       (assoc :deleted-chat-ids #{"b"}))]
            (db/delete-chat-from-cache! db "b" nil))
          (is (not (fs/exists? (chat-cache-file workspaces "b"))))
          (is (fs/exists? (chat-cache-file workspaces "a")))
          (is (= #{"a"} (set (keys (read-index workspaces))))))
        (finally (fs/delete-tree tmpdir))))))

(deftest update-chats-index!-merges-peer-writes-test
  (testing "an index write over a file another process changed merges entries instead of clobbering"
    (let [tmpdir (str (fs/create-temp-dir))]
      (h/with-cache-root tmpdir
        (try
          (let [workspaces [{:uri "file:///home/user/merge-peer"}]
                db-a {:workspace-folders workspaces
                      :chats {"a" {:id "a" :updated-at 100 :title "mem-a"
                                   :messages [{:role "user" :content "hi"}]}}}]
            ;; this process saves, then a peer process writes a newer "a" and a new "b"
            (db/save-chat! db-a "a" nil)
            (write-transit! (chats-index-file workspaces)
                            {:version db/chats-version
                             :chats {"a" {:id "a" :updated-at 200 :title "peer-a"}
                                     "b" {:id "b" :updated-at 150 :title "peer-b"}}})
            (db/update-chats-index! (assoc-in db-a [:chats "a" :title] "mem-a2") nil)
            (let [idx (read-index workspaces)]
              (is (= #{"a" "b"} (set (keys idx))) "peer's chat is not clobbered")
              (is (= "peer-a" (get-in idx ["a" :title])) "peer's strictly newer copy wins")))
          (finally (fs/delete-tree tmpdir)))))))

(deftest update-chats-index!-tie-keeps-memory-test
  (testing "on equal recency the in-memory entry wins, so mid-prompt mutations are never dropped"
    (let [tmpdir (str (fs/create-temp-dir))]
      (h/with-cache-root tmpdir
        (try
          (let [workspaces [{:uri "file:///home/user/merge-tie"}]
                db-a {:workspace-folders workspaces
                      :chats {"a" {:id "a" :updated-at 100 :title "mem-a" :messages []}}}]
            (db/save-chat! db-a "a" nil)
            (write-transit! (chats-index-file workspaces)
                            {:version db/chats-version
                             :chats {"a" {:id "a" :updated-at 100 :title "stale-peer-a"}}})
            (db/update-chats-index! (assoc-in db-a [:chats "a" :title] "mem-a2") nil)
            (is (= "mem-a2" (get-in (read-index workspaces) ["a" :title]))))
          (finally (fs/delete-tree tmpdir)))))))

(deftest update-chats-index!-first-write-merges-existing-file-test
  (testing "the first index write of a session merges entries a peer wrote since this process loaded"
    (let [tmpdir (str (fs/create-temp-dir))]
      (h/with-cache-root tmpdir
        (try
          (let [workspaces [{:uri "file:///home/user/merge-first-save"}]]
            (write-transit! (chats-index-file workspaces)
                            {:version db/chats-version
                             :chats {"b" {:id "b" :updated-at 50 :title "peer-b"}}})
            (db/update-chats-index! {:workspace-folders workspaces
                                     :chats {"a" {:id "a" :updated-at 100 :title "mem-a" :messages []}}}
                                    nil)
            (is (= #{"a" "b"} (set (keys (read-index workspaces))))))
          (finally (fs/delete-tree tmpdir)))))))

(deftest update-chats-index!-does-not-resurrect-deleted-chats-test
  (testing "chats deleted in this session are excluded from the merge with the on-disk index"
    (let [tmpdir (str (fs/create-temp-dir))]
      (h/with-cache-root tmpdir
        (try
          (let [workspaces [{:uri "file:///home/user/merge-tombstone"}]]
            (write-transit! (chats-index-file workspaces)
                            {:version db/chats-version
                             :chats {"a" {:id "a" :updated-at 100 :title "deleted-elsewhere"}
                                     "b" {:id "b" :updated-at 50 :title "b"}}})
            (db/update-chats-index! {:workspace-folders workspaces
                                     :chats {"b" {:id "b" :updated-at 50 :title "b" :messages []}}
                                     :deleted-chat-ids #{"a"}}
                                    nil)
            (is (= #{"b"} (set (keys (read-index workspaces))))
                "the deleted chat is not resurrected from disk"))
          (finally (fs/delete-tree tmpdir)))))))

(deftest migrate-legacy-workspace-caches!-test
  (testing "splits legacy blobs (canonical + redundant dirs) into per-chat files, newest chat wins"
    (let [tmpdir (str (fs/create-temp-dir))]
      (h/with-cache-root tmpdir
        (try
          (let [workspaces [{:uri "file:///home/user/projX"}]
                canonical ^File (cache/workspace-cache-file workspaces "db.transit.json" shared/uri->filename)
                ws-hash (cache/workspaces-hash workspaces shared/uri->filename)
                hash-only-dir (io/file (cache/data-dir) ws-hash)
                hash-only-file (io/file hash-only-dir "db.transit.json")]
            ;; canonical holds an older copy of chat "a"
            (write-transit! canonical
                            {:version db/version
                             :chats {"a" {:id "a" :updated-at 100 :title "old-a" :messages []}}})
            ;; a legacy hash-only dir holds a newer "a" plus an extra chat "b"
            (write-transit! hash-only-file
                            {:version db/version
                             :chats {"a" {:id "a" :updated-at 200 :title "new-a" :messages []}
                                     "b" {:id "b" :updated-at 50 :title "b" :messages []}}})
            (db/migrate-legacy-workspace-caches! workspaces nil)
            (is (= "new-a" (:title (read-chat-cache workspaces "a"))) "newest :updated-at wins on conflict")
            (is (= "b" (:title (read-chat-cache workspaces "b"))))
            (is (= #{"a" "b"} (set (keys (read-index workspaces)))))
            (is (not (fs/exists? canonical)) "legacy blob is renamed away")
            (is (fs/exists? (io/file (str (.getPath canonical) ".bak"))) "legacy blob is kept as rollback insurance")
            (is (not (fs/exists? hash-only-dir)) "redundant dir is removed"))
          (finally (fs/delete-tree tmpdir))))))
  (testing "chats with invalid ids (e.g. nil key from old bugs) are dropped instead of aborting the migration"
    (let [tmpdir (str (fs/create-temp-dir))]
      (h/with-cache-root tmpdir
        (try
          (let [workspaces [{:uri "file:///home/user/projNil"}]
                canonical (cache/workspace-cache-file workspaces "db.transit.json" shared/uri->filename)]
            (write-transit! canonical
                            {:version db/version
                             :chats {nil {:title "nil-keyed garbage" :updated-at 10 :messages []}
                                     "aaaaaaaa-1111-2222-3333-444444444444"
                                     {:id "aaaaaaaa-1111-2222-3333-444444444444"
                                      :title "good" :updated-at 100 :messages []}}})
            (db/migrate-legacy-workspace-caches! workspaces nil)
            (is (= "good" (:title (read-chat-cache workspaces "aaaaaaaa-1111-2222-3333-444444444444")))
                "valid chats still migrate")
            (is (= #{"aaaaaaaa-1111-2222-3333-444444444444"} (set (keys (read-index workspaces)))))
            (is (not (fs/exists? canonical)) "migration completes and renames the blob")
            (is (fs/exists? (io/file (str (.getPath ^File canonical) ".bak")))))
          (finally (fs/delete-tree tmpdir))))))
  (testing "a newer per-chat file is not clobbered by a stale legacy blob copy"
    (let [tmpdir (str (fs/create-temp-dir))]
      (h/with-cache-root tmpdir
        (try
          (let [workspaces [{:uri "file:///home/user/projY"}]
                canonical (cache/workspace-cache-file workspaces "db.transit.json" shared/uri->filename)]
            (db/save-chat! {:workspace-folders workspaces
                            :chats {"a" {:id "a" :updated-at 300 :title "newer-per-chat" :messages []}}}
                           "a" nil)
            (write-transit! canonical
                            {:version db/version
                             :chats {"a" {:id "a" :updated-at 100 :title "stale-blob" :messages []}}})
            (db/migrate-legacy-workspace-caches! workspaces nil)
            (is (= "newer-per-chat" (:title (read-chat-cache workspaces "a"))))
            (is (= "newer-per-chat" (get-in (read-index workspaces) ["a" :title]))))
          (finally (fs/delete-tree tmpdir)))))))

(deftest load-db-from-cache!-loads-index-only-entries-test
  (let [tmpdir (str (fs/create-temp-dir))]
    (h/with-cache-root tmpdir
      (try
        (let [workspaces [{:uri "file:///home/user/load"}]]
          (db/save-chat! {:workspace-folders workspaces
                          :chats {"a" {:id "a" :title "A" :updated-at 100
                                       :messages [{:role "user" :content "hi"}
                                                  {:role "flag" :content {:text "pin"}}]}}}
                         "a" nil)
          (let [db* (atom (assoc db/initial-db :workspace-folders workspaces))]
            (db/load-db-from-cache! db* {} nil)
            (let [a (get-in @db* [:chats "a"])]
              (is (some? a))
              (is (not (contains? a :messages)) "history is not loaded eagerly")
              (is (= 2 (:message-count a)))
              (is (= 1 (:user-message-count a)))
              (is (= ["pin"] (:flags a)))
              (is (= "a" (:id a))))))
        (finally (fs/delete-tree tmpdir))))))

(deftest load-db-from-cache!-reconciles-index-test
  (testing "orphan chat files are re-indexed after index loss"
    (let [tmpdir (str (fs/create-temp-dir))]
      (h/with-cache-root tmpdir
        (try
          (let [workspaces [{:uri "file:///home/user/heal"}]]
            (db/save-chat! {:workspace-folders workspaces
                            :chats {"a" {:id "a" :title "A" :updated-at 100
                                         :messages [{:role "user" :content "hi"}]}}}
                           "a" nil)
            (fs/delete (chats-index-file workspaces))
            (let [db* (atom (assoc db/initial-db :workspace-folders workspaces))]
              (db/load-db-from-cache! db* {} nil)
              (is (= "A" (get-in @db* [:chats "a" :title])) "entry rebuilt from the chat file")
              (is (fs/exists? (chats-index-file workspaces)) "index rewritten after reconciliation")))
          (finally (fs/delete-tree tmpdir))))))
  (testing "index entries whose chat file vanished are dropped and tombstoned"
    (let [tmpdir (str (fs/create-temp-dir))]
      (h/with-cache-root tmpdir
        (try
          (let [workspaces [{:uri "file:///home/user/heal2"}]
                db {:workspace-folders workspaces
                    :chats {"a" {:id "a" :updated-at 1 :messages []}
                            "b" {:id "b" :updated-at 2 :messages []}}}]
            (db/save-chat! db "a" nil)
            (db/save-chat! db "b" nil)
            (fs/delete (chat-cache-file workspaces "b"))
            (let [db* (atom (assoc db/initial-db :workspace-folders workspaces))]
              (db/load-db-from-cache! db* {} nil)
              (is (some? (get-in @db* [:chats "a"])))
              (is (nil? (get-in @db* [:chats "b"])))
              (is (contains? (:deleted-chat-ids @db*) "b")
                  "dropped id is tombstoned against index merge resurrection")
              (is (= #{"a"} (set (keys (read-index workspaces))))
                  "the rewritten on-disk index no longer lists the dropped chat")))
          (finally (fs/delete-tree tmpdir)))))))

(deftest cleanup-old-chats-test
  (let [tmpdir (str (fs/create-temp-dir))]
    (h/with-cache-root tmpdir
      (try
        (let [now (System/currentTimeMillis)
              fifteen-days-ago (- now (* 15 24 60 60 1000))
              two-days-ago (- now (* 2 24 60 60 1000))
              workspaces [{:uri "file:///home/user/cleanup"}]
              db* (atom {:workspace-folders workspaces
                         :chats {"old-chat" {:id "old-chat"
                                             :created-at fifteen-days-ago
                                             :messages [{:role "user" :content "hi"}]}
                                 "recent-chat" {:id "recent-chat"
                                                :created-at two-days-ago
                                                :messages [{:role "user" :content "hello"}]}
                                 "old-but-active" {:id "old-but-active"
                                                   :created-at fifteen-days-ago
                                                   :updated-at two-days-ago
                                                   :messages [{:role "user" :content "hi again"}]}
                                 "stale-updated" {:id "stale-updated"
                                                  :created-at fifteen-days-ago
                                                  :updated-at fifteen-days-ago
                                                  :messages [{:role "user" :content "bye"}]}
                                 "no-timestamp" {:id "no-timestamp"
                                                 :messages [{:role "user" :content "hey"}]}}})]
          (doseq [id ["old-chat" "recent-chat" "old-but-active" "stale-updated" "no-timestamp"]]
            (db/save-chat! @db* id nil))
          (testing "deletes stale chats (memory + file), keeps recently updated and chats without timestamps"
            (db/cleanup-old-chats! db* nil 14)
            (is (nil? (get-in @db* [:chats "old-chat"]))
                "Chat older than 14 days should be removed")
            (is (some? (get-in @db* [:chats "recent-chat"]))
                "Chat newer than 14 days should be kept")
            (is (some? (get-in @db* [:chats "old-but-active"]))
                "Chat created long ago but updated recently should be kept")
            (is (nil? (get-in @db* [:chats "stale-updated"]))
                "Chat not updated for more than 14 days should be removed")
            (is (some? (get-in @db* [:chats "no-timestamp"]))
                "Chat without created-at should be kept")
            (is (= #{"old-chat" "stale-updated"} (:deleted-chat-ids @db*))
                "Removed chat ids are tombstoned so index merges cannot resurrect them")
            (is (not (fs/exists? (chat-cache-file workspaces "old-chat")))
                "old chat's cache file is deleted")
            (is (not (fs/exists? (chat-cache-file workspaces "stale-updated")))
                "stale chat's cache file is deleted")
            (is (fs/exists? (chat-cache-file workspaces "recent-chat")))
            (is (= #{"recent-chat" "old-but-active" "no-timestamp"} (set (keys (read-index workspaces)))))))
        (finally (fs/delete-tree tmpdir))))))

(deftest cleanup-old-chats-no-op-test
  (let [now (System/currentTimeMillis)
        two-days-ago (- now (* 2 24 60 60 1000))
        db* (atom {:chats {"recent" {:id "recent"
                                     :created-at two-days-ago
                                     :messages [{:role "user" :content "hi"}]}}
                   :workspace-folders []})
        cache-updated? (atom false)]
    (testing "does not flush the index when nothing to clean"
      (with-redefs [db/update-chats-index! (fn [_ _] (reset! cache-updated? true))]
        (db/cleanup-old-chats! db* nil 14))
      (is (some? (get-in @db* [:chats "recent"])))
      (is (false? @cache-updated?)
          "Should not flush the index when no chats were removed"))))

(deftest cleanup-old-chats-disabled-test
  (let [now (System/currentTimeMillis)
        fifteen-days-ago (- now (* 15 24 60 60 1000))
        db* (atom {:chats {"old-chat" {:id "old-chat"
                                       :created-at fifteen-days-ago
                                       :messages [{:role "user" :content "hi"}]}}
                   :workspace-folders []})
        cache-updated? (atom false)]
    (testing "does not clean up when retention-days is 0"
      (with-redefs [db/update-chats-index! (fn [_ _] (reset! cache-updated? true))]
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
      (h/with-cache-root tmpdir
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
      (h/with-cache-root tmpdir
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
      (h/with-cache-root tmpdir
        (try
          (let [mem (atom {:auth {"anthropic" {:refresh-token "x" :expires-at 1}}})
                updated? (db/sync-auth-from-cache! mem "anthropic" nil)]
            (is (not updated?))
            (is (= "x" (get-in @mem [:auth "anthropic" :refresh-token]))))
          (finally (fs/delete-tree tmpdir)))))))

(deftest with-global-cache-lock-runs-body-and-releases-test
  (testing "the lock can be acquired sequentially without leaking handles"
    (let [tmpdir (str (fs/create-temp-dir))]
      (h/with-cache-root tmpdir
        (try
          (let [ran (atom 0)]
            (db/with-global-cache-lock (swap! ran inc))
            (db/with-global-cache-lock (swap! ran inc))
            (is (= 2 @ran)))
          (finally (fs/delete-tree tmpdir)))))))

(deftest with-global-cache-lock-serializes-concurrent-threads-test
  (testing "two threads acquiring the lock cannot interleave inside the body"
    (let [tmpdir (str (fs/create-temp-dir))]
      (h/with-cache-root tmpdir
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

(deftest chats-index-persists-workspaces-test
  (testing "the chats index records the workspace root paths so offline readers can tell which workspace the chats belong to"
    (let [tmpdir (str (fs/create-temp-dir))]
      (h/with-cache-root tmpdir
        (try
          (let [workspaces [{:uri (h/file-uri "file:///home/user/my-proj")}]
                db {:workspace-folders workspaces
                    :chats {"a" {:id "a" :title "A" :updated-at 100 :messages []}}}]
            (db/save-chat! db "a" nil)
            (is (= [(h/file-path "/home/user/my-proj")]
                   (:workspaces (read-transit-file (chats-index-file workspaces))))))
          (finally (fs/delete-tree tmpdir)))))))

(deftest list-all-workspaces-chats-test
  (let [tmpdir (str (fs/create-temp-dir))]
    (h/with-cache-root tmpdir
      (try
        (let [workspaces [{:uri (h/file-uri "file:///home/user/proj-a")}]
              db {:workspace-folders workspaces
                  :chats {"cur-1" {:id "cur-1" :title "Current work" :updated-at 300
                                   :messages [{:role "user" :content "hi"}]}
                          "sub-1" {:id "sub-1" :subagent true :updated-at 400 :messages []}}}]
          ;; another workspace on the current per-chat layout, with persisted paths
          (write-transit! (io/file tmpdir "projb_hash1111" "chats" "index.transit.json")
                          {:version db/chats-version
                           :workspaces ["/home/user/proj-b"]
                           :chats {"b-1" {:id "b-1" :title "B newest" :updated-at 200
                                          :message-count 3 :user-message-count 2 :flags []}
                                   "b-sub" {:id "b-sub" :subagent true :updated-at 500}}})
          ;; stale duplicate of b-1 in another dir (legacy duplicate dirs, #558)
          (write-transit! (io/file tmpdir "projbold_hash4444" "chats" "index.transit.json")
                          {:version db/chats-version
                           :chats {"b-1" {:id "b-1" :title "B stale" :updated-at 100}}})
          ;; workspace never reopened since v7: legacy whole-workspace blob only
          (write-transit! (io/file tmpdir "legacy_hash2222" "db.transit.json")
                          {:version db/version
                           :chats {"l-1" {:title "Legacy chat" :updated-at 150
                                          :messages [{:role "user" :content "hi"}]}}})
          ;; unreadable index is skipped without aborting the scan
          (let [corrupt (io/file tmpdir "corrupt_hash3333" "chats" "index.transit.json")]
            (io/make-parents corrupt)
            (spit corrupt "not-transit{{{"))
          (let [groups (db/list-all-workspaces-chats db nil)]
            (testing "groups sorted by recency, current workspace flagged, approximate names from dir prefix"
              (is (= [["proj-a" true] ["projb" false] ["legacy" false]]
                     (map (juxt :name :current?) groups))))
            (testing "current workspace chats come from memory with exact paths, excluding subagents"
              (is (= [(h/file-path "/home/user/proj-a")] (:workspaces (nth groups 0))))
              (is (= ["cur-1"] (mapv :id (:chats (nth groups 0))))))
            (testing "persisted :workspaces paths are exposed and duplicated ids keep the most recent copy"
              (is (= ["/home/user/proj-b"] (:workspaces (nth groups 1))))
              (is (= [{:id "b-1" :title "B newest"}]
                     (mapv #(select-keys % [:id :title]) (:chats (nth groups 1))))))
            (testing "legacy blob workspaces have no exact paths and metas are projected"
              (is (nil? (:workspaces (nth groups 2))))
              (is (= [{:id "l-1" :title "Legacy chat" :message-count 1}]
                     (mapv #(select-keys % [:id :title :message-count]) (:chats (nth groups 2))))))))
        (finally (fs/delete-tree tmpdir))))))

(deftest workspace-dir-name-parts-test
  (let [parts @#'db/workspace-dir-name-parts]
    (testing "name and hash split positionally, so a '_' inside the base64url hash is not a separator"
      (is (= ["eca" "ab_c1234"] (parts "eca_ab_c1234")))
      (is (= ["my_proj" "abcd1234"] (parts "my_proj_abcd1234"))))
    (testing "legacy hash-only dir names label as the hash itself"
      (is (= ["abcd1234" "abcd1234"] (parts "abcd1234"))))
    (testing "unknown formats yield no hash"
      (is (= ["short" nil] (parts "short")))
      (is (= ["noseparator123" nil] (parts "noseparator123"))))))

(deftest list-all-workspaces-chats-recovers-workspaces-test
  (testing "unknown workspace paths are recovered by hash-verifying sibling dirs of known roots"
    (let [tmpdir (str (fs/create-temp-dir))
          projects (str (fs/create-temp-dir))]
      (h/with-cache-root tmpdir
        (try
          (let [proj-b (io/file projects "proj-b")
                _ (fs/create-dirs proj-b)
                proj-b-workspaces [{:uri (shared/filename->uri (str proj-b))}]
                workspaces [{:uri (shared/filename->uri (str (io/file projects "proj-a")))}]
                db {:workspace-folders workspaces
                    :chats {"cur-1" {:id "cur-1" :title "Cur" :updated-at 300 :messages []}}}]
            ;; proj-b's index was written by an older ECA: no :workspaces recorded
            (write-transit! (io/file (cache/workspace-cache-dir proj-b-workspaces shared/uri->filename)
                                     "chats" "index.transit.json")
                            {:version db/chats-version
                             :chats {"b-1" {:id "b-1" :title "B" :updated-at 200}}})
            (let [groups (db/list-all-workspaces-chats db nil)
                  b-group (first (filter #(= ["b-1"] (mapv :id (:chats %))) groups))]
              (is (= [(str (fs/absolutize proj-b))] (:workspaces b-group))
                  "exact path recovered by matching the dir hash, not guessed")))
          (finally
            (fs/delete-tree tmpdir)
            (fs/delete-tree projects)))))))

(deftest load-db-from-cache!-first-start-after-upgrade-test
  (testing "the first start copies history and login into the data dir, keeping the cache dir copy for downgrades (#623)"
    (h/with-legacy-and-data-dirs
      (fn [legacy-root _data-root]
        (let [workspaces [{:uri "file:///home/user/upgrade"}]]
          (write-legacy-chat! workspaces {:id "a" :title "A" :updated-at 100
                                          :messages [{:role "user" :content "hi"}]})
          (write-transit! (io/file legacy-root "db.transit.json")
                          {:version db/version :auth {"anthropic" {:api-key "k"}}})
          (let [db* (atom (assoc db/initial-db :workspace-folders workspaces))]
            (db/load-db-from-cache! db* {} nil)
            (is (= "A" (get-in @db* [:chats "a" :title])))
            (is (= "k" (get-in @db* [:auth "anthropic" :api-key])))
            (is (= "A" (:title (read-chat-cache workspaces "a"))) "history now lives in the data dir")
            (is (fs/exists? (legacy-chat-cache-file workspaces "a")) "and stays in the cache dir")))))))

(deftest load-db-from-cache!-reads-legacy-cache-dir-test
  (testing "chats an older ECA wrote to the cache dir after a downgrade load and hydrate, newest copy winning"
    (h/with-legacy-and-data-dirs
      (fn [_legacy-root _data-root]
        (let [workspaces [{:uri "file:///home/user/dual-load"}]]
          (db/save-all-chats! {:workspace-folders workspaces
                               :chats {"data-only" {:id "data-only" :title "Data only" :updated-at 100 :messages []}
                                       "data-newer" {:id "data-newer" :title "data" :updated-at 300 :messages []}
                                       "legacy-newer" {:id "legacy-newer" :title "data" :updated-at 100 :messages []}}}
                              nil)
          (write-legacy-chat! workspaces {:id "legacy-only" :title "Legacy only" :updated-at 200
                                          :messages [{:role "user" :content "from an older ECA"}]})
          (write-legacy-chat! workspaces {:id "data-newer" :title "legacy" :updated-at 100 :messages []})
          (write-legacy-chat! workspaces {:id "legacy-newer" :title "legacy" :updated-at 400
                                          :messages [{:role "user" :content "continued after downgrade"}]})
          (let [db* (atom (assoc db/initial-db :workspace-folders workspaces))]
            (db/load-db-from-cache! db* {} nil)
            (is (= #{"data-only" "data-newer" "legacy-newer" "legacy-only"} (set (keys (:chats @db*)))))
            (is (empty? (:deleted-chat-ids @db*)) "chats with a file only in the cache dir are not dropped")
            (is (= "data" (get-in @db* [:chats "data-newer" :title])))
            (is (= "legacy" (get-in @db* [:chats "legacy-newer" :title])))
            (db/hydrate-chat! db* "legacy-only" nil)
            (db/hydrate-chat! db* "legacy-newer" nil)
            (is (= ["from an older ECA"] (mapv :content (get-in @db* [:chats "legacy-only" :messages]))))
            (is (= ["continued after downgrade"] (mapv :content (get-in @db* [:chats "legacy-newer" :messages]))))
            (testing "saving writes the data dir only"
              (db/save-chat! @db* "legacy-only" nil)
              (is (= "Legacy only" (:title (read-chat-cache workspaces "legacy-only"))))
              (is (= 200 (get-in (read-transit-file (legacy-chat-cache-file workspaces "legacy-only")) [:chat :updated-at]))
                  "the cache dir copy is left as is"))))))))

(deftest save-chat!-index-only-legacy-chat-test
  (testing "a meta-only save of a chat only the cache dir has keeps its messages, writing the data dir"
    (h/with-legacy-and-data-dirs
      (fn [_legacy-root _data-root]
        (let [workspaces [{:uri "file:///home/user/dual-rename"}]]
          (write-legacy-chat! workspaces {:id "a" :title "A" :updated-at 100
                                          :messages [{:role "user" :content "hi"}]})
          (db/save-chat! {:workspace-folders workspaces
                          :chats {"a" {:id "a" :title "Renamed" :updated-at 200
                                       :index-only? true :message-count 1}}}
                         "a" nil)
          (let [chat (read-chat-cache workspaces "a")]
            (is (= "Renamed" (:title chat)))
            (is (= 1 (count (:messages chat))))))))))

(deftest delete-chat-removes-legacy-copy-test
  (testing "deleted and cleaned up chats lose their cache dir copy too, so a reload cannot resurrect them"
    (h/with-legacy-and-data-dirs
      (fn [_legacy-root _data-root]
        (let [now (System/currentTimeMillis)
              workspaces [{:uri "file:///home/user/dual-delete"}]
              chats {"keep" {:id "keep" :updated-at now :messages []}
                     "deleted" {:id "deleted" :updated-at now :messages []}
                     "stale" {:id "stale" :updated-at (- now (* 30 24 60 60 1000)) :messages []}}]
          (db/save-all-chats! {:workspace-folders workspaces :chats chats} nil)
          (doseq [chat (vals chats)]
            (write-legacy-chat! workspaces chat))
          (let [db* (atom (assoc db/initial-db :workspace-folders workspaces))]
            (db/load-db-from-cache! db* {} nil)
            (swap! db* #(-> %
                            (update :chats dissoc "deleted")
                            (update :deleted-chat-ids conj "deleted")))
            (db/delete-chat-from-cache! @db* "deleted" nil)
            (db/cleanup-old-chats! db* nil 14)
            (doseq [id ["deleted" "stale"]]
              (is (not (fs/exists? (chat-cache-file workspaces id))))
              (is (not (fs/exists? (legacy-chat-cache-file workspaces id)))))
            (is (fs/exists? (legacy-chat-cache-file workspaces "keep"))))
          (let [db* (atom (assoc db/initial-db :workspace-folders workspaces))]
            (db/load-db-from-cache! db* {} nil)
            (is (= #{"keep"} (set (keys (:chats @db*)))))))))))

(deftest global-cache-dual-location-test
  (h/with-legacy-and-data-dirs
    (fn [legacy-root data-root]
      (let [legacy-file (io/file legacy-root "db.transit.json")
            data-file (io/file data-root "db.transit.json")
            auth (fn [token] {:auth {"anthropic" {:refresh-token token :expires-at (count token)}}})
            refresh-token (fn [f] (get-in (read-transit-file f) [:auth "anthropic" :refresh-token]))]
        (testing "writes only the data dir when there is no cache dir copy"
          (db/update-global-cache! (auth "first") nil)
          (is (= "first" (refresh-token data-file)))
          (is (not (fs/exists? legacy-file))))
        (testing "keeps an existing cache dir copy in sync, so a downgrade does not hit rotated tokens"
          (write-transit! legacy-file {:version db/version :auth {}})
          (db/update-global-cache! (auth "second") nil)
          (is (= "second" (refresh-token data-file)))
          (is (= "second" (refresh-token legacy-file))))
        (testing "reads the most recently written copy, e.g. tokens an older ECA refreshed"
          (write-transit! legacy-file (assoc (auth "from-older-eca") :version db/version))
          (.setLastModified data-file (- (System/currentTimeMillis) 60000))
          (let [mem (atom (auth "second"))]
            (is (db/sync-auth-from-cache! mem "anthropic" nil))
            (is (= "from-older-eca" (get-in @mem [:auth "anthropic" :refresh-token])))))
        (testing "falls back to the other copy when the newest cannot be read"
          (spit legacy-file "garbage")
          (let [mem (atom (auth "x"))]
            (db/sync-auth-from-cache! mem "anthropic" nil)
            (is (= "second" (get-in @mem [:auth "anthropic" :refresh-token])))))))))

(deftest copy-legacy-cache-dir!-test
  (let [copy! #'db/copy-legacy-cache-dir!
        marker-name @#'db/legacy-copy-marker-name]
    (testing "copies chat dirs and the global db once, keeping the sources and skipping cache-only entries"
      (h/with-legacy-and-data-dirs
        (fn [legacy-root data-root]
          (write-transit! (io/file legacy-root "proj_aaaa1111" "chats" "x.transit.json")
                          {:version db/chats-version :chat {:id "x"}})
          (write-transit! (io/file legacy-root "old_bbbb2222" "db.transit.json")
                          {:version db/version :chats {}})
          (write-transit! (io/file legacy-root "db.transit.json") {:version db/version :auth {}})
          (doseq [d ["tls" "plugins" "toolCallOutputs" "nochats_cccc3333"]]
            (fs/create-dirs (io/file legacy-root d)))
          (spit (io/file legacy-root "models-dev.json") "{}")
          (copy!)
          (is (fs/exists? (io/file data-root "proj_aaaa1111" "chats" "x.transit.json")))
          (is (fs/exists? (io/file data-root "old_bbbb2222" "db.transit.json")))
          (is (= #{"proj_aaaa1111" "old_bbbb2222" "db.transit.json" marker-name}
                 (set (map #(str (fs/file-name %)) (fs/list-dir data-root))))
              "cache-only entries, dirs without chats and temp copies are not left in the data dir")
          (is (fs/exists? (io/file legacy-root "proj_aaaa1111" "chats" "x.transit.json")) "sources are kept")
          (is (fs/exists? (io/file legacy-root "db.transit.json")))
          (testing "runs once: dirs removed from the data dir afterwards are not copied back"
            (fs/delete-tree (io/file data-root "old_bbbb2222"))
            (copy!)
            (is (not (fs/exists? (io/file data-root "old_bbbb2222"))))))))
    (testing "never overwrites what the data dir already has"
      (h/with-legacy-and-data-dirs
        (fn [legacy-root data-root]
          (write-transit! (io/file legacy-root "proj_aaaa1111" "chats" "x.transit.json")
                          {:version db/chats-version :chat {:id "x" :title "legacy"}})
          (write-transit! (io/file legacy-root "db.transit.json") {:version db/version :auth {"a" {:api-key "legacy"}}})
          (write-transit! (io/file data-root "proj_aaaa1111" "chats" "x.transit.json")
                          {:version db/chats-version :chat {:id "x" :title "data"}})
          (write-transit! (io/file data-root "db.transit.json") {:version db/version :auth {"a" {:api-key "data"}}})
          (copy!)
          (is (= "data" (get-in (read-transit-file (io/file data-root "proj_aaaa1111" "chats" "x.transit.json"))
                                [:chat :title])))
          (is (= "data" (get-in (read-transit-file (io/file data-root "db.transit.json")) [:auth "a" :api-key]))))))
    (testing "no-op when the cache dir and the data dir are the same"
      (let [tmpdir (str (fs/create-temp-dir))]
        (h/with-cache-root tmpdir
          (try
            (write-transit! (io/file tmpdir "proj_aaaa1111" "chats" "x.transit.json")
                            {:version db/chats-version :chat {:id "x"}})
            (copy!)
            (is (not (fs/exists? (io/file tmpdir marker-name))))
            (finally (fs/delete-tree tmpdir))))))))

(deftest list-all-workspaces-chats-merges-legacy-cache-dir-test
  (testing "a workspace with a dir in both locations is listed once, the newest copy of each chat winning"
    (h/with-legacy-and-data-dirs
      (fn [legacy-root data-root]
        (let [workspaces [{:uri (h/file-uri "file:///home/user/proj-a")}]
              current-dir-name (.getName (cache/workspace-cache-dir workspaces shared/uri->filename))
              db {:workspace-folders workspaces
                  :chats {"cur-1" {:id "cur-1" :title "Current" :updated-at 300 :messages []}}}
              an-hour-ago (- (System/currentTimeMillis) (* 60 60 1000))
              data-b-index (io/file data-root "projb_hash1111" "chats" "index.transit.json")
              legacy-c-index (io/file legacy-root "projc_hash2222" "chats" "index.transit.json")]
          (write-transit! data-b-index
                          {:version db/chats-version
                           :workspaces ["/home/user/proj-b"]
                           :chats {"b-1" {:id "b-1" :title "B data" :updated-at 200}}})
          ;; written by an older ECA after the data dir copy
          (.setLastModified data-b-index an-hour-ago)
          (write-transit! (io/file legacy-root "projb_hash1111" "chats" "index.transit.json")
                          {:version db/chats-version
                           :chats {"b-1" {:id "b-1" :title "B legacy stale" :updated-at 100}
                                   "b-2" {:id "b-2" :title "B legacy only" :updated-at 150}}})
          ;; not written since the data dir copy, so already merged there
          (write-transit! (io/file data-root "projc_hash2222" "chats" "index.transit.json")
                          {:version db/chats-version
                           :chats {"c-1" {:id "c-1" :title "C" :updated-at 120}}})
          (write-transit! legacy-c-index
                          {:version db/chats-version
                           :chats {"c-1" {:id "c-1" :title "C" :updated-at 120}
                                   "c-gone" {:id "c-gone" :title "Deleted since" :updated-at 110}}})
          (.setLastModified legacy-c-index an-hour-ago)
          ;; the cache dir copy of the current workspace is already merged in memory
          (write-transit! (io/file legacy-root current-dir-name "chats" "index.transit.json")
                          {:version db/chats-version
                           :chats {"cur-old" {:id "cur-old" :title "Old current" :updated-at 50}}})
          (let [groups (db/list-all-workspaces-chats db nil)]
            (is (= [["proj-a" true] ["projb" false] ["projc" false]] (map (juxt :name :current?) groups)))
            (is (= ["/home/user/proj-b"] (:workspaces (second groups))))
            (is (= [["b-1" "B data"] ["b-2" "B legacy only"]]
                   (mapv (juxt :id :title) (:chats (second groups)))))
            (is (= ["c-1"] (mapv :id (:chats (nth groups 2))))
                "an older cache dir copy is not read")))))))
