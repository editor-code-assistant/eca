(ns eca.features.memory.usage-test
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [eca.digest :as digest]
   [eca.file-io :as file-io]
   [eca.features.memory :as memory]
   [eca.features.memory.usage :as usage]
   [eca.logger :as logger]
   [eca.shared :as shared]
   [eca.test-helper :as test-helper])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

(set! *warn-on-reflection* true)

(defn ^:private temp-dir []
  (.toFile (Files/createTempDirectory "eca-memory-usage-test" (make-array FileAttribute 0))))

(defn ^:private with-memory-root [f]
  (let [root (temp-dir)]
    (with-redefs [memory/memory-root-dir (fn [] root)]
      (try
        (f root)
        (finally (fs/delete-tree root))))))

;; Isolate from other tests sharing the process-wide ledger state.
(defn ^:private reset-ledger! []
  (reset! @#'usage/ledger* {})
  (reset! @#'usage/loaded?* false))

(defn ^:private test-db [roots]
  {:workspace-folders (mapv (fn [r] {:uri (shared/filename->uri (str r))}) roots)})

(def enabled-config
  {:memory {:enabled true
            :writeMode "agent"
            :index {:maxEntries 100 :maxTokens 1000}}})

(defn ^:private ctx [db]
  {:chat-id "chat-1" :db db :config enabled-config})

(defn ^:private write-md! [f]
  (fs/create-dirs (fs/parent f))
  (spit (str f) "---\nname: Note\ndescription: D\n---\n\nbody\n"))

(deftest record-read-roundtrip-test
  (with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])
            f (fs/file root "projects"
                       (memory/project-slug (test-helper/file-path "/repo/one"))
                       "note.md")]
        (write-md! f)
        (reset-ledger!)
        (usage/record-read! (ctx db) (str f))
        (usage/record-read! (ctx db) (str f))
        ;; simulate a fresh process: reload the ledger from disk
        (reset-ledger!)
        (let [ledger (usage/load!)
              key (str (fs/absolutize (fs/file f)))]
          (is (= 2 (get-in ledger [key :reads])) "two reads increment the counter")
          (is (number? (get-in ledger [key :last-read-at])))
          (is (= key (str (fs/absolutize (fs/file f))))
              "key normalization matches the memory.clj scan :path form"))))))

(deftest prune-on-load-test
  (with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])
            f (fs/file root "projects"
                       (memory/project-slug (test-helper/file-path "/repo/one"))
                       "note.md")]
        (write-md! f)
        (reset-ledger!)
        (usage/record-read! (ctx db) (str f))
        (is (contains? @@#'usage/ledger* (str (fs/absolutize (fs/file f)))))
        (fs/delete f)
        (reset-ledger!)
        (is (empty? (usage/load!)) "entry for a deleted file is pruned on load")))))

(deftest corrupt-ledger-warns-and-starts-empty-test
  (with-memory-root
    (fn [root]
      (let [warns* (atom [])]
        (with-redefs [logger/warn (fn [& args] (swap! warns* conj args))]
          (spit (str (usage/usage-file)) "{{:reads (unclosed")
          (reset-ledger!)
          (is (= {} (usage/load!)) "corrupt ledger is treated as empty, no throw")
          (is (seq @warns*) "corruption logs a warning"))
        (testing "recording still works after corruption"
          (let [db (test-db [(test-helper/file-path "/repo/one")])
                f (fs/file root "projects"
                           (memory/project-slug (test-helper/file-path "/repo/one"))
                           "note.md")]
            (write-md! f)
            (reset-ledger!)
            (usage/record-read! (ctx db) (str f))
            (is (= 1 (get-in (edn/read-string (slurp (str (usage/usage-file))))
                             [(str (fs/absolutize (fs/file f))) :reads]))
                "the corrupt file is healed by the next convergent write")))))))

(deftest memory-dir-info-for-path-tiers-test
  (with-memory-root
    (fn [root]
      (let [workspace-root (temp-dir)
            db (test-db [(str workspace-root)])
            slug (memory/project-slug (str workspace-root))]
        (testing "personal tier resolves"
          (is (= :personal
                 (:tier (memory/memory-dir-info-for-path
                         db enabled-config
                         (str (fs/file root "projects" slug "a.md")))))))
        (testing "a repo-local .eca path resolves to nil (shared tier removed)"
          (is (nil? (memory/memory-dir-info-for-path
                     db enabled-config
                     (str (fs/file workspace-root ".eca" "memory" "team.md"))))))
        (testing "global tier resolves"
          (is (= :global
                 (:tier (memory/memory-dir-info-for-path
                         db enabled-config
                         (str (fs/file root "global" "g.md")))))))
        (testing "path outside any memory dir resolves to nil"
          (is (nil? (memory/memory-dir-info-for-path
                     db enabled-config
                     (str (fs/file workspace-root "src" "main.clj"))))))))))

(deftest repo-local-markdown-reads-not-recorded-test
  (with-memory-root
    (fn [_root]
      (let [workspace-root (temp-dir)
            db (test-db [(str workspace-root)])
            f (fs/file workspace-root ".eca" "memory" "team.md")]
        (write-md! f)
        (reset-ledger!)
        (usage/record-read! (ctx db) (str f))
        (is (empty? @@#'usage/ledger*)
            "reads of repo-local markdown are not memory reads (no shared tier)")
        (is (not (fs/exists? (usage/usage-file)))
            "nothing is persisted for a non-memory-dir read")))))

(deftest consolidating-flag-suppresses-recording-test
  (with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])
            f (fs/file root "projects"
                       (memory/project-slug (test-helper/file-path "/repo/one"))
                       "note.md")]
        (write-md! f)
        (reset-ledger!)
        (testing "the chat's own :memory-consolidating? flag suppresses recording"
          (usage/record-read! (assoc-in (ctx db) [:db :chats "chat-1" :memory-consolidating?] true)
                              (str f))
          (is (empty? @@#'usage/ledger*)))
        (testing "the parent chat's flag suppresses recording (subagent escape hatch)"
          (let [db2 (-> db
                        (assoc-in [:chats "chat-1" :parent-chat-id] "parent-1")
                        (assoc-in [:chats "parent-1" :memory-consolidating?] true))]
            (usage/record-read! (ctx db2) (str f))
            (is (empty? @@#'usage/ledger*))))
        (testing "recording works again once no flag is set"
          (usage/record-read! (ctx db) (str f))
          (is (= 1 (get-in @@#'usage/ledger* [(str (fs/absolutize (fs/file f))) :reads]))))))))

(deftest increment-latest-disk-test
  (with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])
            f (fs/file root "projects"
                       (memory/project-slug (test-helper/file-path "/repo/one"))
                       "note.md")
            key (str (fs/absolutize (fs/file f)))
            future-ms (+ (System/currentTimeMillis) 60000)]
        (write-md! f)
        (reset-ledger!)
        (usage/load!)
        ;; A peer records 10 reads after this process loaded an empty snapshot.
        (spit (str (usage/usage-file)) (pr-str {key {:reads 10 :last-read-at future-ms}}))
        (usage/record-read! (ctx db) (str f))
        (let [on-disk (edn/read-string (slurp (str (usage/usage-file))))]
          (is (= 11 (get-in on-disk [key :reads])) "the stale snapshot does not lose the new increment")
          (is (= future-ms (get-in on-disk [key :last-read-at])) "last-read-at takes the max"))
        (testing "the in-memory ledger converges to the same value"
          (is (= 11 (get-in @@#'usage/ledger* [key :reads]))))))))

(deftest body-change-stamps-changed-at-test
  (with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])
            f (fs/file root "global" "note.md")
            key (str (fs/absolutize (fs/file f)))
            old-ms (- (System/currentTimeMillis) (* 40 86400000))
            entry (fn [body mtime-ms] {:path key :body body :mtime-ms mtime-ms})]
        (write-md! f)
        (reset-ledger!)
        (testing "first sight seeds the stamp from mtime, not from now"
          (let [enriched (first (usage/enrich [(entry "body" old-ms)]))]
            (is (= old-ms (:updated-at-ms enriched))
                "turning this on must not make an existing store look freshly changed")
            (is (= old-ms (get-in @@#'usage/ledger* [key :changed-at])))
            (is (string? (get-in @@#'usage/ledger* [key :body-hash])))))
        (testing "an unchanged body keeps its stamp even when the file was rewritten"
          (let [before (slurp (str (usage/usage-file)))
                enriched (first (usage/enrich [(entry "body" (System/currentTimeMillis))]))]
            (is (= old-ms (:updated-at-ms enriched))
                "a tag-only /memory-consolidate rewrite bumps mtime; the knowledge date must not move")
            (is (= before (slurp (str (usage/usage-file))))
                "a scan observing no content change persists nothing")))
        (testing "a changed body stamps now"
          (let [enriched (first (usage/enrich [(entry "different body" old-ms)]))]
            (is (< old-ms (long (:updated-at-ms enriched)))
                "rewriting what a memory says is a real update")))
        (testing "recording a read preserves the change fields through the locked update"
          (usage/record-read! (ctx db) (str f))
          (is (= 1 (get-in @@#'usage/ledger* [key :reads])))
          (is (string? (get-in @@#'usage/ledger* [key :body-hash]))
              "a read must not drop the body hash from the entry")
          (is (some? (get-in @@#'usage/ledger* [key :changed-at]))))
        (testing "entries with no body fall back to mtime"
          (is (= old-ms (:updated-at-ms (first (usage/enrich [{:path "/nope.md" :mtime-ms old-ms}]))))))))))

(deftest enrich-test
  (with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])
            f (fs/file root "projects"
                       (memory/project-slug (test-helper/file-path "/repo/one"))
                       "note.md")
            other (fs/file root "projects"
                           (memory/project-slug (test-helper/file-path "/repo/one"))
                           "other.md")]
        (write-md! f)
        (reset-ledger!)
        (usage/record-read! (ctx db) (str f))
        (reset-ledger!)
        (let [read-entry (first (usage/enrich [{:path (str (fs/absolutize (fs/file f)))}]))
              unread-entry (first (usage/enrich [{:path (str (fs/absolutize (fs/file other)))}]))]
          (is (= 1 (:reads read-entry)))
          (is (number? (:last-read-at read-entry)))
          (is (= 0 (:reads unread-entry)) "never-read entries default :reads to 0")
          (is (nil? (:last-read-at unread-entry))))))))

(deftest updates-prune-deleted-memories-test
  (with-memory-root
    (fn [root]
      (let [deleted (fs/file root "global" "deleted.md")
            kept (fs/file root "global" "kept.md")]
        (write-md! deleted)
        (write-md! kept)
        (reset-ledger!)
        (usage/record-read! (ctx {}) (str deleted))
        (fs/delete deleted)
        (usage/record-read! (ctx {}) (str kept))
        (let [disk (edn/read-string (slurp (usage/usage-file)))]
          (is (not (contains? disk (str deleted))))
          (is (= 1 (get-in disk [(str kept) :reads])))
          (is (= disk @usage/ledger*)))))))

(deftest concurrent-reads-test
  (with-memory-root
    (fn [root]
      (let [f (fs/file root "global" "note.md")
            path (str f)
            start (promise)]
        (write-md! f)
        (reset-ledger!)
        (let [workers (mapv (fn [_]
                              (future
                                @start
                                (dotimes [_ 10]
                                  (usage/load!)
                                  (usage/record-read! (ctx {}) path)
                                  (usage/enrich [{:path path :body "body" :mtime-ms 100}]))))
                            (range 8))]
          (deliver start true)
          (doseq [worker workers]
            (is (not= ::timeout (deref worker 10000 ::timeout)))))
        (let [disk (edn/read-string (slurp (usage/usage-file)))]
          (is (= 80 (get-in disk [path :reads])))
          (is (= 100 (get-in disk [path :changed-at])))
          (is (= (digest/sha-256-hex "body") (get-in disk [path :body-hash])))
          (is (= disk @usage/ledger*)))
        (is (empty? (fs/glob root "*.tmp")))))))

(deftest load-and-update-use-the-same-lock-test
  (with-memory-root
    (fn [root]
      (let [f (fs/file root "global" "note.md")
            read-disk @#'usage/read-ledger-from-disk!
            read-started (promise)
            release-read (promise)
            update-started (promise)
            first-read? (atom true)]
        (write-md! f)
        (reset-ledger!)
        (with-redefs [usage/read-ledger-from-disk!
                      (fn []
                        (let [snapshot (read-disk)]
                          (when (compare-and-set! first-read? true false)
                            (deliver read-started true)
                            (deref release-read 10000 nil))
                          snapshot))]
          (let [loader (future (usage/load!))]
            (try
              (is (= true (deref read-started 10000 ::timeout)))
              (let [writer (future
                             (deliver update-started true)
                             (usage/record-read! (ctx {}) (str f)))]
                (is (= true (deref update-started 10000 ::timeout)))
                (is (= ::timeout (deref writer 100 ::timeout)) "update waits for the load lock")
                (deliver release-read true)
                (is (not= ::timeout (deref loader 10000 ::timeout)))
                (is (not= ::timeout (deref writer 10000 ::timeout)))
                (is (= 1 (get-in @usage/ledger* [(str f) :reads])))
                (is (= @usage/ledger* (edn/read-string (slurp (usage/usage-file))))))
              (finally (deliver release-read true)))))))))

(deftest enrich-uses-latest-body-fields-test
  (with-memory-root
    (fn [root]
      (let [f (fs/file root "global" "note.md")
            path (str f)
            old {path {:reads 2 :body-hash (digest/sha-256-hex "old") :changed-at 100}}
            latest {path {:reads 3 :last-read-at 400
                          :body-hash (digest/sha-256-hex "new") :changed-at 200}}]
        (write-md! f)
        (reset-ledger!)
        (reset! usage/ledger* old)
        (reset! usage/loaded?* true)
        (spit (usage/usage-file) (pr-str latest))
        (with-redefs [file-io/replace-file! (fn [& _] (throw (ex-info "Unexpected write" {})))
                      logger/warn (fn [& _] (is false "An unchanged body must not write"))]
          (let [entry (first (usage/enrich [{:path path :body "new" :mtime-ms 900}]))]
            (is (= 200 (:updated-at-ms entry)) "keep the peer's exact change date after a retag")
            (is (= 3 (:reads entry)))
            (is (= 400 (:last-read-at entry)))))
        (is (= latest @usage/ledger*))
        (is (= latest (edn/read-string (slurp (usage/usage-file)))))
        (testing "a read preserves the exact hash/date pair"
          (usage/record-read! (ctx {}) path)
          (is (= (select-keys (get latest path) [:body-hash :changed-at])
                 (select-keys (get @usage/ledger* path) [:body-hash :changed-at]))))
        (testing "a changed body gets the supplied observation date"
          (is (= {path {:body-hash (digest/sha-256-hex "changed") :changed-at 500}}
                 (#'usage/body-change-updates [{:path path :body "changed" :mtime-ms 900}]
                                              latest 500))))))))

(deftest failed-ledger-replacement-keeps-cache-test
  (with-memory-root
    (fn [root]
      (let [f (fs/file root "global" "note.md")
            warnings (atom [])]
        (write-md! f)
        (reset-ledger!)
        (usage/record-read! (ctx {}) (str f))
        (let [before @usage/ledger*]
          (with-redefs [file-io/atomic-move! (fn [& _] (throw (ex-info "Replacement failed" {})))
                        logger/warn (fn [& args] (swap! warnings conj args))]
            (usage/record-read! (ctx {}) (str f)))
          (is (seq @warnings))
          (is (= before @usage/ledger*))
          (is (= before (edn/read-string (slurp (usage/usage-file)))))
          (is (empty? (fs/glob root "*.tmp"))))))))

(defn ^:private await-file [f ^Process process]
  (let [deadline (+ (System/nanoTime) (* 60 1000000000))]
    (loop []
      (cond
        (fs/exists? f) true
        (or (not (.isAlive process)) (> (System/nanoTime) deadline)) false
        :else (do (Thread/sleep 10) (recur))))))

(deftest independent-process-stale-snapshot-test
  (with-memory-root
    (fn [root]
      (let [f (fs/file root "global" "note.md")
            ready (io/file root "child-ready")
            release (io/file root "child-release")
            attempting (io/file root "child-attempting")
            output (io/file root "child-output")
            java (str (io/file (System/getProperty "java.home") "bin"
                               (if (.startsWith (System/getProperty "os.name") "Windows")
                                 "java.exe" "java")))
            code (str "(require '[eca.features.memory :as memory] '[eca.features.memory.usage :as usage])"
                      "(with-redefs [memory/memory-root-dir (constantly (clojure.java.io/file " (pr-str (str root)) "))]"
                      " (usage/load!)"
                      " (spit " (pr-str (str ready)) " \"ready\")"
                      " (let [deadline (+ (System/nanoTime) 60000000000)]"
                      "  (loop [] (when-not (.exists (clojure.java.io/file " (pr-str (str release)) "))"
                      "   (when (> (System/nanoTime) deadline) (throw (ex-info \"Timed out\" {})))"
                      "   (Thread/sleep 10) (recur))))"
                      " (spit " (pr-str (str attempting)) " \"attempting\")"
                      " (usage/record-read! " (pr-str (ctx {})) " " (pr-str (str f)) "))"
                      "(shutdown-agents)")]
        (write-md! f)
        (reset-ledger!)
        (let [process (.start (doto (ProcessBuilder. ^java.util.List
                                     [java "-cp" (System/getProperty "java.class.path")
                                      "clojure.main" "-e" code])
                                (.redirectErrorStream true)
                                (.redirectOutput output)))]
          (try
            (is (await-file ready process) (str "Child failed to load: " (slurp output)))
            ;; Child has cached zero reads. The parent commits its increment first.
            (usage/record-read! (ctx {}) (str f))
            (file-io/with-os-file-lock-fn
              (io/file root "usage.edn.lock")
              (fn []
                (spit release "release")
                (is (await-file attempting process) (slurp output))
                (is (not (.waitFor process 250 java.util.concurrent.TimeUnit/MILLISECONDS))
                    "child waits while the parent holds the ledger OS lock")
                (is (= 1 (get-in (edn/read-string (slurp (usage/usage-file))) [(str f) :reads])))))
            (let [finished? (.waitFor process 60 java.util.concurrent.TimeUnit/SECONDS)]
              (is finished? "Child must finish within the deadline")
              (when finished?
                (is (zero? (.exitValue process)) (slurp output))))
            (is (= 2 (get-in (edn/read-string (slurp (usage/usage-file))) [(str f) :reads]))
                "both independent process increments survive")
            (finally
              (.destroyForcibly process)
              (.waitFor process 5 java.util.concurrent.TimeUnit/SECONDS))))))))
