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
   [eca.features.tools.filesystem :as f.tools.filesystem]
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

(defn ^:private disk-ledger
  "Current on-disk ledger. The ledger keeps no process-wide state, so the file
   is the single source of truth; each test uses its own temp memory root."
  []
  (#'usage/read-ledger-from-disk!))

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
        (usage/record-read! (ctx db) (str f))
        (usage/record-read! (ctx db) (str f))
        (let [ledger (disk-ledger)
              key (str (fs/absolutize (fs/file f)))]
          (is (= 2 (get-in ledger [key :reads])) "two reads increment the counter")
          (is (number? (get-in ledger [key :last-read-at])))
          (is (= key (str (fs/absolutize (fs/file f))))
              "key normalization matches the memory.clj scan :path form"))))))

(deftest corrupt-ledger-warns-and-starts-empty-test
  (with-memory-root
    (fn [root]
      (let [warns* (atom [])]
        (with-redefs [logger/warn (fn [& args] (swap! warns* conj args))]
          (spit (str (usage/usage-file)) "{{:reads (unclosed")
          (is (= {} (disk-ledger)) "corrupt ledger is treated as empty, no throw")
          (is (seq @warns*) "corruption logs a warning"))
        (testing "recording still works after corruption"
          (let [db (test-db [(test-helper/file-path "/repo/one")])
                f (fs/file root "projects"
                           (memory/project-slug (test-helper/file-path "/repo/one"))
                           "note.md")]
            (write-md! f)
            (usage/record-read! (ctx db) (str f))
            (is (= 1 (get-in (edn/read-string (slurp (str (usage/usage-file))))
                             [(str (fs/absolutize (fs/file f))) :reads]))
                "the corrupt file is healed by the next convergent write")))))))

(deftest memory-path-test
  (with-memory-root
    (fn [root]
      (let [workspace-root (temp-dir)
            db (test-db [(str workspace-root)])
            slug (memory/project-slug (str workspace-root))
            personal (str (fs/file root "projects" slug "a.md"))]
        (testing "a path in the personal dir is a memory path"
          (is (true? (memory/memory-path? db enabled-config personal))))
        (testing "a path in the global dir is a memory path"
          (is (true? (memory/memory-path? db enabled-config (str (fs/file root "global" "g.md"))))))
        (testing "a repo-local .eca path is not (there is no team tier)"
          (is (false? (memory/memory-path? db enabled-config
                                           (str (fs/file workspace-root ".eca" "memory" "team.md"))))))
        (testing "a path outside any memory dir is not"
          (is (false? (memory/memory-path? db enabled-config
                                           (str (fs/file workspace-root "src" "main.clj"))))))
        (testing "nothing is a memory path when memory is disabled, or without a path"
          (is (false? (memory/memory-path? db {} personal)))
          (is (false? (memory/memory-path? db enabled-config nil))))))))

(deftest repo-local-markdown-reads-not-recorded-test
  (with-memory-root
    (fn [_root]
      (let [workspace-root (temp-dir)
            db (test-db [(str workspace-root)])
            f (fs/file workspace-root ".eca" "memory" "team.md")]
        (write-md! f)
        (usage/record-read! (ctx db) (str f))
        (is (empty? (disk-ledger))
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
        (testing "the chat's own :memory-consolidating? flag suppresses recording"
          (usage/record-read! (assoc-in (ctx db) [:db :chats "chat-1" :memory-consolidating?] true)
                              (str f))
          (is (empty? (disk-ledger))))
        (testing "the parent chat's flag suppresses recording (subagent escape hatch)"
          (let [db2 (-> db
                        (assoc-in [:chats "chat-1" :parent-chat-id] "parent-1")
                        (assoc-in [:chats "parent-1" :memory-consolidating?] true))]
            (usage/record-read! (ctx db2) (str f))
            (is (empty? (disk-ledger)))))
        (testing "recording works again once no flag is set"
          (usage/record-read! (ctx db) (str f))
          (is (= 1 (get-in (disk-ledger) [(str (fs/absolutize (fs/file f))) :reads]))))))))

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
        ;; A peer records 10 reads before this process records its own.
        (spit (str (usage/usage-file)) (pr-str {key {:reads 10 :last-read-at future-ms}}))
        (usage/record-read! (ctx db) (str f))
        (let [on-disk (edn/read-string (slurp (str (usage/usage-file))))]
          (is (= 11 (get-in on-disk [key :reads])) "a peer's increment is not lost")
          (is (= future-ms (get-in on-disk [key :last-read-at])) "last-read-at takes the max"))))))

(deftest body-change-stamps-changed-at-test
  (with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])
            f (fs/file root "global" "note.md")
            key (str (fs/absolutize (fs/file f)))
            old-ms (- (System/currentTimeMillis) (* 40 86400000))
            entry (fn [body mtime-ms] {:path key :body body :mtime-ms mtime-ms})]
        (write-md! f)
        (testing "first sight seeds the stamp from mtime, not from now"
          (let [enriched (first (usage/enrich [(entry "body" old-ms)]))]
            (is (= old-ms (:updated-at-ms enriched))
                "turning this on must not make an existing store look freshly changed")
            (is (= old-ms (get-in (disk-ledger) [key :changed-at])))
            (is (string? (get-in (disk-ledger) [key :body-hash])))))
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
          (is (= 1 (get-in (disk-ledger) [key :reads])))
          (is (string? (get-in (disk-ledger) [key :body-hash]))
              "a read must not drop the body hash from the entry")
          (is (some? (get-in (disk-ledger) [key :changed-at]))))
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
        (usage/record-read! (ctx db) (str f))
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
        (usage/record-read! (ctx {}) (str deleted))
        (fs/delete deleted)
        (usage/record-read! (ctx {}) (str kept))
        (let [disk (edn/read-string (slurp (usage/usage-file)))]
          (is (not (contains? disk (str deleted))))
          (is (= 1 (get-in disk [(str kept) :reads]))))))))

(deftest concurrent-reads-test
  (with-memory-root
    (fn [root]
      (let [f (fs/file root "global" "note.md")
            path (str f)
            start (promise)]
        (write-md! f)
        (let [workers (mapv (fn [_]
                              (future
                                @start
                                (dotimes [_ 10]
                                  (usage/record-read! (ctx {}) path)
                                  (usage/enrich [{:path path :body "body" :mtime-ms 100}]))))
                            (range 8))]
          (deliver start true)
          (doseq [worker workers]
            (is (not= ::timeout (deref worker 10000 ::timeout)))))
        (let [disk (edn/read-string (slurp (usage/usage-file)))]
          (is (= 80 (get-in disk [path :reads])))
          (is (= 100 (get-in disk [path :changed-at])))
          (is (= (digest/sha-256-hex "body") (get-in disk [path :body-hash]))))
        (is (empty? (fs/glob root "*.tmp")))))))

(deftest enrich-uses-latest-body-fields-test
  (with-memory-root
    (fn [root]
      (let [f (fs/file root "global" "note.md")
            path (str f)
            latest {path {:reads 3 :last-read-at 400
                          :body-hash (digest/sha-256-hex "new") :changed-at 200}}]
        (write-md! f)
        (spit (usage/usage-file) (pr-str latest))
        (with-redefs [file-io/replace-file! (fn [& _] (throw (ex-info "Unexpected write" {})))
                      logger/warn (fn [& _] (is false "An unchanged body must not write"))]
          (let [entry (first (usage/enrich [{:path path :body "new" :mtime-ms 900}]))]
            (is (= 200 (:updated-at-ms entry)) "keep the peer's exact change date after a retag")
            (is (= 3 (:reads entry)))
            (is (= 400 (:last-read-at entry)))))
        (is (= latest (edn/read-string (slurp (usage/usage-file)))))
        (testing "a read preserves the exact hash/date pair"
          (usage/record-read! (ctx {}) path)
          (is (= (select-keys (get latest path) [:body-hash :changed-at])
                 (select-keys (get (disk-ledger) path) [:body-hash :changed-at]))))
        (testing "a changed body gets the supplied observation date"
          (is (= {path {:body-hash (digest/sha-256-hex "changed") :changed-at 500}}
                 (#'usage/body-change-updates [{:path path :body "changed" :mtime-ms 900}]
                                              latest 500))))))))

(deftest failed-ledger-replacement-keeps-disk-ledger-test
  (with-memory-root
    (fn [root]
      (let [f (fs/file root "global" "note.md")
            warnings (atom [])]
        (write-md! f)
        (usage/record-read! (ctx {}) (str f))
        (let [before (disk-ledger)]
          (with-redefs [file-io/atomic-move! (fn [& _] (throw (ex-info "Replacement failed" {})))
                        logger/warn (fn [& args] (swap! warnings conj args))]
            (usage/record-read! (ctx {}) (str f)))
          (is (seq @warnings))
          (is (= before (disk-ledger)))
          (is (= before (edn/read-string (slurp (str (usage/usage-file))))))
          (is (empty? (fs/glob root "*.tmp"))))))))

(defn ^:private await-file [f ^Process process]
  (let [deadline (+ (System/nanoTime) (* 60 1000000000))]
    (loop []
      (cond
        (fs/exists? f) true
        (or (not (.isAlive process)) (> (System/nanoTime) deadline)) false
        :else (do (Thread/sleep 10) (recur))))))

(deftest independent-process-concurrent-increments-test
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
                      " (spit " (pr-str (str ready)) " \"ready\")"
                      " (let [deadline (+ (System/nanoTime) 60000000000)]"
                      "  (loop [] (when-not (.exists (clojure.java.io/file " (pr-str (str release)) "))"
                      "   (when (> (System/nanoTime) deadline) (throw (ex-info \"Timed out\" {})))"
                      "   (Thread/sleep 10) (recur))))"
                      " (spit " (pr-str (str attempting)) " \"attempting\")"
                      " (usage/record-read! " (pr-str (ctx {})) " " (pr-str (str f)) "))"
                      "(shutdown-agents)")]
        (write-md! f)
        (let [process (.start (doto (ProcessBuilder. ^java.util.List
                                     [java "-cp" (System/getProperty "java.class.path")
                                      "clojure.main" "-e" code])
                                (.redirectErrorStream true)
                                (.redirectOutput output)))]
          (try
            (is (await-file ready process) (str "Child failed to start: " (slurp output)))
            ;; The parent commits its increment first; the child then waits for
            ;; the ledger lock and must merge into the parent's value.
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

(deftest record-write-stamps-provenance-test
  (with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])
            f (fs/file root "projects"
                       (memory/project-slug (test-helper/file-path "/repo/one"))
                       "note.md")
            key (str (fs/absolutize (fs/file f)))
            c (assoc (ctx db) :tool-call-id "tc-1")]
        (write-md! f)
        (usage/record-write! c (str f))
        (let [last-write (get-in (disk-ledger) [key :last-write])]
          (is (= "chat-1" (:chat-id last-write)))
          (is (= "tc-1" (:tool-call-id last-write)))
          (is (nil? (:parent-chat-id last-write)) "no parent for a top-level chat")
          (is (number? (:at-ms last-write)))
          (is (false? (:maintenance? last-write))))
        (testing "a second write replaces the stamp instead of accumulating"
          (usage/record-write! (assoc c :tool-call-id "tc-2") (str f))
          (is (= "tc-2" (get-in (disk-ledger) [key :last-write :tool-call-id]))))
        (testing "a read afterwards preserves the write stamp"
          (usage/record-read! (ctx db) (str f))
          (is (= 1 (get-in (disk-ledger) [key :reads])))
          (is (some? (get-in (disk-ledger) [key :last-write]))))
        (testing "the stamp is on disk for any later process"
          (is (some? (get-in (disk-ledger) [key :last-write]))))))))

(deftest record-write-maintenance-flag-test
  (with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])
            f (fs/file root "global" "note.md")
            key (str (fs/absolutize (fs/file f)))]
        (write-md! f)
        (testing "the chat's own :memory-consolidating? flag marks the write"
          (usage/record-write! (assoc-in (ctx db) [:db :chats "chat-1" :memory-consolidating?] true)
                               (str f))
          (is (true? (get-in (disk-ledger) [key :last-write :maintenance?]))))
        (testing "the parent chat's flag marks a subagent write"
          (let [db2 (-> db
                        (assoc-in [:chats "chat-1" :parent-chat-id] "parent-1")
                        (assoc-in [:chats "parent-1" :memory-consolidating?] true))]
            (usage/record-write! (ctx db2) (str f))
            (let [last-write (get-in (disk-ledger) [key :last-write])]
              (is (true? (:maintenance? last-write)))
              (is (= "parent-1" (:parent-chat-id last-write))))))
        (testing "unlike reads, consolidating writes are recorded, not skipped"
          (is (some? (get-in (disk-ledger) [key :last-write]))))))))

(deftest record-write-guards-test
  (with-memory-root
    (fn [root]
      (let [workspace-root (temp-dir)
            db (test-db [(str workspace-root)])
            slug (memory/project-slug (str workspace-root))
            not-memory (fs/file workspace-root "src" "notes.md")]
        (write-md! not-memory)
        (usage/record-write! (ctx db) (str not-memory))
        (is (empty? (disk-ledger)) "writes outside memory dirs are not recorded")
        (is (not (fs/exists? (usage/usage-file))) "nothing is persisted for a non-memory write")
        (testing "disabled memory records nothing even for a memory path"
          (let [f (fs/file root "projects" slug "note.md")]
            (write-md! f)
            (usage/record-write! {:chat-id "chat-1" :db db :config {:memory {:enabled false}}}
                                 (str f))
            (is (empty? (disk-ledger)))))))))

(deftest record-move-rekeys-entry-test
  (with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])
            slug (memory/project-slug (test-helper/file-path "/repo/one"))
            f (fs/file root "projects" slug "note.md")
            renamed (fs/file root "projects" slug "renamed.md")
            global-target (fs/file root "global" "moved.md")
            outside-dir (temp-dir)
            outside (fs/file outside-dir "outside.md")
            key-of (fn [x] (str (fs/absolutize (fs/file x))))]
        (write-md! f)
        (usage/record-write! (ctx db) (str f))
        (testing "rename inside one dir moves the entry to the new key"
          ;; record-move! must run before the move, matching move-file: after
          ;; the move the source entry would already be pruned away.
          (usage/record-move! (ctx db) (str f) (str renamed))
          (fs/move f renamed)
          (is (not (contains? (disk-ledger) (key-of f))))
          (is (some? (get (disk-ledger) (key-of renamed)))))
        (testing "move to another tier re-keys too"
          (fs/create-dirs (fs/parent global-target))
          (usage/record-move! (ctx db) (str renamed) (str global-target))
          (fs/move renamed global-target)
          (is (some? (get (disk-ledger) (key-of global-target)))))
        (testing "move outside memory dirs drops the entry (the file left the store)"
          (usage/record-move! (ctx db) (str global-target) (str outside))
          (fs/move global-target outside)
          (is (empty? (disk-ledger))))
        (fs/delete-tree outside-dir)))))

(deftest record-move-without-entry-persists-nothing-test
  (with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])
            slug (memory/project-slug (test-helper/file-path "/repo/one"))
            f (fs/file root "projects" slug "a.md")
            g (fs/file root "projects" slug "b.md")]
        (write-md! f)
        (usage/record-move! (ctx db) (str f) (str g))
        (is (not (fs/exists? (usage/usage-file)))
            "a move with no source entry persists nothing")))))

(deftest filesystem-tools-call-the-ledger-test
  ;; The ledger functions are tested directly above; this proves the native
  ;; file tools call them: read_file records a read, write_file and edit_file
  ;; stamp write provenance, and move_file re-keys the entry.
  (with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])
            f (fs/file root "global" "note.md")
            dest (fs/file root "global" "renamed.md")
            key-of (fn [x] (str (fs/absolutize (fs/file x))))
            handler (fn [tool] (get-in f.tools.filesystem/definitions [tool :handler]))
            c {:chat-id "chat-1" :db db :config enabled-config}
            read-ctx (assoc c :config (assoc enabled-config :toolCall {:readFile {:maxLines 2000}}))]
        (write-md! f)
        (testing "read_file records a read"
          (is (false? (:error ((handler "read_file") {"path" (str f)} read-ctx))))
          (is (= 1 (get-in (disk-ledger) [(key-of f) :reads]))))
        (testing "write_file stamps write provenance"
          (is (false? (:error ((handler "write_file")
                               {"path" (str f)
                                "content" "---\nname: N\ndescription: D\n---\n\nbody\n"}
                               c))))
          (is (= "chat-1" (get-in (disk-ledger) [(key-of f) :last-write :chat-id]))))
        (testing "edit_file stamps write provenance"
          (is (false? (:error ((handler "edit_file")
                               {"path" (str f)
                                "original_content" "description: D"
                                "new_content" "description: E"}
                               c))))
          (is (= "chat-1" (get-in (disk-ledger) [(key-of f) :last-write :chat-id]))))
        (testing "move_file re-keys the entry"
          (is (false? (:error ((handler "move_file")
                               {"source" (str f) "destination" (str dest)}
                               c))))
          (let [ledger (disk-ledger)]
            (is (nil? (get ledger (key-of f))) "the old key is gone")
            (is (= "chat-1" (get-in ledger [(key-of dest) :last-write :chat-id]))
                "the entry moved with the file")))))))
