(ns eca.modules.memory.usage-test
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [eca.digest :as digest]
   [eca.file-io :as file-io]
   [eca.logger :as logger]
   [eca.modules.memory.store :as m.memory.store]
   [eca.modules.memory.usage :as m.memory.usage]
   [eca.shared :as shared]
   [eca.test-helper :as test-helper])
  (:import
   [java.io IOException RandomAccessFile]
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.util.concurrent TimeUnit]))

(set! *warn-on-reflection* true)

(def ^:private ms-per-day 86400000)

(defn ^:private temp-dir []
  (fs/file (fs/canonicalize (Files/createTempDirectory "eca-memory-usage-test" (make-array FileAttribute 0)))))

(defn ^:private disk-ledger
  "Current on-disk ledger. The ledger keeps no process-wide state, so the file
   is the single source of truth; each test uses its own temp memory root."
  []
  (#'m.memory.usage/read-ledger-from-disk!))

(defn ^:private write-ledger! [ledger]
  (spit (str (m.memory.usage/usage-file)) (pr-str ledger)))

(defn ^:private test-db [roots]
  {:workspace-folders (mapv (fn [r] {:uri (shared/filename->uri (str r))}) roots)})

(defn ^:private ctx [db]
  {:chat-id "chat-1" :db db})

(defn ^:private write-md! [f]
  (fs/create-dirs (fs/parent f))
  (spit (str f) "---\nname: Note\ndescription: D\n---\n\nbody\n"))

(defn ^:private write-md-at!
  "Writes a valid note and sets its file time to `mtime-ms`."
  [f mtime-ms]
  (write-md! f)
  (fs/set-last-modified-time f (fs/millis->file-time mtime-ms)))

;; --- Recording

(deftest aliased-reads-and-writes-use-one-key-test
  (test-helper/with-memory-root
    (fn [root]
      (let [note (fs/path root "global" "note.md")
            canonical (m.memory.store/abs-path note)
            alias (str (fs/parent note) "/./note.md")
            db (test-db [])
            context (assoc (ctx db) :tool-call-id "write-evidence")]
        (write-md! note)
        (m.memory.usage/record-read! context alias)
        (m.memory.usage/record-write! context alias)
        (m.memory.usage/record-read! context canonical)
        (let [ledger (disk-ledger)]
          (is (= #{canonical} (set (keys ledger))))
          (is (= 2 (get-in ledger [canonical :reads])))
          (is (= "write-evidence" (get-in ledger [canonical :writes 0 :tool-call-id]))))
        (is (= 2 (:reads (first (:entries (m.memory.usage/ranked-scan db))))))))))

(deftest read-count-respects-filename-case-test
  (test-helper/with-memory-root
    (fn [root]
      (let [note (fs/path root "global" "Docker.md")
            lowercase (fs/path root "global" "docker.md")
            db (test-db [])]
        (write-md! note)
        (let [case-insensitive? (fs/exists? lowercase)]
          (when-not case-insensitive?
            (write-md! lowercase))
          (m.memory.usage/record-read! (ctx db) (str lowercase))
          (is (= (if case-insensitive?
                   {"Docker.md" 1}
                   {"Docker.md" 0 "docker.md" 1})
                 (into {} (map (juxt (comp fs/file-name :path) :reads))
                       (:entries (m.memory.usage/ranked-scan db))))))))))

(deftest non-memory-paths-record-nothing-test
  (test-helper/with-memory-root
    (fn [root]
      (let [workspace-root (temp-dir)
            db (test-db [(str workspace-root)])
            repo-local (fs/file workspace-root ".eca" "memory" "team.md")]
        (write-md! repo-local)
        (m.memory.usage/record-read! (ctx db) (str repo-local))
        (m.memory.usage/record-write! (ctx db) (str repo-local))
        (is (empty? (disk-ledger))
            "repo-local markdown is not a memory file")
        (is (not (fs/exists? (m.memory.usage/usage-file)))
            "nothing is persisted for a path outside the memory dirs")
        (testing "a file that is not .md in a memory dir is not a memory"
          (let [txt (fs/file root "global" "notes.txt")]
            (write-md! txt)
            (m.memory.usage/record-read! (ctx db) (str txt))
            (m.memory.usage/record-read! (ctx db) (str (fs/file workspace-root "src" "main.clj")))
            (m.memory.usage/record-write! (assoc (ctx db) :tool-call-id "tc-txt") (str txt))
            (is (empty? (disk-ledger)))
            (is (not (fs/exists? (m.memory.usage/usage-file))))))
        (testing "a hidden file or a file inside a hidden dir records neither reads nor writes"
          (let [hidden-dir (fs/create-dirs (fs/path root "global" ".obsidian"))
                hidden-nested (fs/file hidden-dir "note.md")
                hidden-leaf (fs/file root "global" ".secret.md")]
            (when shared/windows-os?
              (fs/set-attribute hidden-dir "dos:hidden" true))
            (write-md! hidden-nested)
            (write-md! hidden-leaf)
            (when shared/windows-os?
              (fs/set-attribute hidden-leaf "dos:hidden" true))
            (doseq [f [hidden-nested hidden-leaf]]
              (m.memory.usage/record-read! (ctx db) (str f))
              (m.memory.usage/record-write! (assoc (ctx db) :tool-call-id "tc-hidden") (str f)))
            (is (empty? (disk-ledger)))))
        (when-not shared/windows-os?
          (testing "symlinks below a memory dir record neither reads nor writes"
            (let [global (fs/create-dirs (fs/path root "global"))
                  own (fs/file global "own.md")
                  alias (fs/path global "alias.md")
                  elsewhere (fs/create-dirs (fs/path root "elsewhere"))
                  outside (fs/file elsewhere "outside.md")
                  outside-link (fs/path global "outside-link.md")
                  linked-dir (fs/path global "linked-dir")]
              (write-md! own)
              (write-md! outside)
              (fs/create-sym-link alias own)
              (fs/create-sym-link outside-link outside)
              (fs/create-sym-link linked-dir elsewhere)
              (doseq [p [alias outside-link (fs/path linked-dir "outside.md")]]
                (m.memory.usage/record-read! (ctx db) (str p))
                (m.memory.usage/record-write! (assoc (ctx db) :tool-call-id "tc-link") (str p)))
              (is (empty? (disk-ledger))))))
        (fs/delete-tree workspace-root)))))

(deftest increment-latest-disk-test
  (test-helper/with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])
            f (fs/file root "projects"
                       (m.memory.store/project-slug (test-helper/file-path "/repo/one"))
                       "note.md")
            key (str (fs/absolutize (fs/file f)))
            future-ms (+ (System/currentTimeMillis) 60000)]
        (write-md! f)
        ;; A peer records 10 reads before this process records its own.
        (write-ledger! {key {:reads 10 :last-read-at future-ms}})
        (m.memory.usage/record-read! (ctx db) (str f))
        (let [on-disk (disk-ledger)]
          (is (= 11 (get-in on-disk [key :reads])) "a peer's increment is not lost")
          (is (= future-ms (get-in on-disk [key :last-read-at])) "last-read-at takes the max"))))))

(deftest record-write-keeps-pointers-to-the-writes-test
  (test-helper/with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])
            f (fs/file root "projects"
                       (m.memory.store/project-slug (test-helper/file-path "/repo/one"))
                       "note.md")
            key (str (fs/absolutize (fs/file f)))
            write! (fn [chat-id tool-call-id & {:as opts}]
                     (m.memory.usage/record-write! (merge {:chat-id chat-id :tool-call-id tool-call-id :db db} opts)
                                                   (str f)))
            writes #(get-in (disk-ledger) [key :writes])]
        (write-md! f)
        (write! "chat-1" "tc-1")
        (is (= [{:chat-id "chat-1" :tool-call-id "tc-1"}] (writes))
            "only the ids of the write, and no key without a value")
        (testing "more writes of the same chat keep its first pointer"
          (write! "chat-1" "tc-2")
          (is (= [{:chat-id "chat-1" :tool-call-id "tc-1"}] (writes))))
        (testing "a read afterwards preserves the pointers"
          (m.memory.usage/record-read! (ctx db) (str f))
          (is (= 1 (get-in (disk-ledger) [key :reads])))
          (is (= [{:chat-id "chat-1" :tool-call-id "tc-1"}] (writes))))
        (testing "another chat adds its pointer, and a consolidation write is marked, also in a chat that wrote before"
          (write! "chat-2" "tc-3")
          (write! "chat-1" "tc-4" :consolidation? true)
          (is (= [{:chat-id "chat-1" :tool-call-id "tc-1"}
                  {:chat-id "chat-2" :tool-call-id "tc-3"}
                  {:chat-id "chat-1" :tool-call-id "tc-4" :consolidation? true}]
                 (writes))))
        (testing "a subagent's write also points to its parent chat"
          (m.memory.usage/record-write! {:chat-id "sub-1" :tool-call-id "tc-5"
                                         :db (assoc-in db [:chats "sub-1" :parent-chat-id] "chat-1")}
                                        (str f))
          (is (= {:chat-id "sub-1" :tool-call-id "tc-5" :parent-chat-id "chat-1"} (last (writes)))))
        (testing "past 10 pointers the oldest go, except the one of the chat that created the note"
          (doseq [i (range 6 14)]
            (write! (str "chat-" i) (str "tc-" i)))
          (is (= ["chat-1" "sub-1" "chat-6" "chat-7" "chat-8" "chat-9" "chat-10" "chat-11" "chat-12" "chat-13"]
                 (mapv :chat-id (writes))))
          (is (= {:chat-id "chat-1" :tool-call-id "tc-1"} (first (writes)))))))))

(deftest record-move-rekeys-entry-test
  (test-helper/with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])
            slug (m.memory.store/project-slug (test-helper/file-path "/repo/one"))
            f (fs/file root "projects" slug "note.md")
            renamed (fs/file root "projects" slug "renamed.md")
            global-target (fs/file root "global" "moved.md")
            outside-dir (temp-dir)
            outside (fs/file outside-dir "outside.md")
            key-of (fn [x] (str (fs/absolutize (fs/file x))))]
        (write-md! f)
        (m.memory.usage/record-write! (ctx db) (str f))
        (testing "rename inside one dir moves the entry to the new key"
          ;; record-move! runs after the move, matching move-file.
          (fs/move f renamed)
          (m.memory.usage/record-move! (ctx db) (str f) (str renamed))
          (is (not (contains? (disk-ledger) (key-of f))))
          (is (some? (get (disk-ledger) (key-of renamed)))))
        (testing "move to another tier re-keys too"
          (fs/create-dirs (fs/parent global-target))
          (fs/move renamed global-target)
          (m.memory.usage/record-move! (ctx db) (str renamed) (str global-target))
          (is (some? (get (disk-ledger) (key-of global-target)))))
        (testing "move outside memory dirs drops the entry (the file left the store)"
          (fs/move global-target outside)
          (m.memory.usage/record-move! (ctx db) (str global-target) (str outside))
          (is (empty? (disk-ledger))))
        (testing "independent moves preserve each note's history"
          (let [moves (for [n [1 2]]
                        [(fs/file root "global" (str n ".md"))
                         (fs/file root "global" (str "moved-" n ".md"))
                         {:reads n :writes [{:chat-id "chat-1" :tool-call-id (str n)}]}])]
            (write-ledger! (into {} (map (fn [[src _ history]] [(str src) history])) moves))
            (doseq [[src dst] moves]
              (write-md! src)
              (fs/move src dst))
            (doseq [[src dst] moves]
              (m.memory.usage/record-move! (ctx {}) (str src) (str dst)))
            (is (= (into {} (map (fn [[_ dst history]] [(str dst) history])) moves)
                   (disk-ledger)))))
        (fs/delete-tree outside-dir)))))

(deftest scan-prunes-deleted-memories-test
  (test-helper/with-memory-root
    (fn [root]
      (let [deleted (fs/file root "global" "deleted.md")
            dir (fs/path root "global" "private")
            kept (fs/file dir "kept.md")]
        (write-md! deleted)
        (write-md! kept)
        (m.memory.usage/record-read! (ctx {}) (str deleted))
        (fs/delete deleted)
        (m.memory.usage/record-read! (ctx {}) (str kept))
        (m.memory.usage/record-write! (assoc (ctx {}) :tool-call-id "tc-1") (str kept))
        (is (= 1 (get-in (disk-ledger) [(str deleted) :reads])))
        (let [permissions (when (.supportsFileAttributeView (Files/getFileStore dir) "posix")
                            (fs/posix-file-permissions dir))]
          (try
            (when permissions (fs/set-posix-file-permissions dir "---------"))
            (when-not (fs/readable? dir)
              (is (not (fs/exists? kept))
                  "unreadable dir hides kept.md from exists?, exercising the indeterminate-existence branch"))
            (m.memory.usage/ranked-scan {})
            (finally
              (when permissions (fs/set-posix-file-permissions dir permissions)))))
        (let [disk (disk-ledger)]
          (is (not (contains? disk (str deleted))))
          (is (= {:reads 1 :writes [{:chat-id "chat-1" :tool-call-id "tc-1"}]}
                 (select-keys (get disk (str kept)) [:reads :writes]))))
        (testing "deleting the last remaining note still prunes its ledger entry"
          (fs/delete kept)
          (is (empty? (:entries (m.memory.usage/ranked-scan {}))))
          (is (= {} (disk-ledger))))))))

;; --- The scan's view

(deftest ranked-scan-carry-usage-test
  (test-helper/with-memory-root
    (fn [root]
      (let [db (test-db [])
            read-note (fs/file root "global" "read.md")
            unread-note (fs/file root "global" "unread.md")]
        (write-md! read-note)
        (write-md! unread-note)
        (m.memory.usage/record-read! (ctx db) (str read-note))
        (let [by-file (into {}
                            (map (juxt (comp str fs/file-name :path) identity))
                            (:entries (m.memory.usage/ranked-scan db)))]
          (is (= 1 (get-in by-file ["read.md" :reads])))
          (is (number? (get-in by-file ["read.md" :last-read-at])))
          (is (= 0 (get-in by-file ["unread.md" :reads])) "a never-read note has 0 reads")
          (is (nil? (get-in by-file ["unread.md" :last-read-at]))))))))

(deftest change-date-follows-the-body-test
  ;; `:updated-at-ms`, the date the index and the snapshot show, is when the
  ;; knowledge changed. /memory-consolidate rewrites files without changing
  ;; what they say (tags, trims), and that must not move the date.
  (test-helper/with-memory-root
    (fn [root]
      (let [db (test-db [])
            f (fs/file root "global" "note.md")
            key (str f)
            old-ms (- (System/currentTimeMillis) (* 40 ms-per-day))
            write! (fn [tags body mtime-ms]
                     (fs/create-dirs (fs/parent f))
                     (spit (str f) (str "---\nname: Note\ndescription: D\ntags: " tags "\n---\n\n" body "\n"))
                     (fs/set-last-modified-time f (fs/millis->file-time mtime-ms)))
            date-of (fn [] (:updated-at-ms (first (:entries (m.memory.usage/ranked-scan db)))))]
        (write! "alpha" "body" old-ms)
        (testing "first sight takes the file time, so turning memory on doesn't make old notes look new"
          (is (= old-ms (date-of))))
        (testing "a tag-only rewrite gives the file a new time but keeps the date, and writes no ledger"
          (write! "beta" "body" (System/currentTimeMillis))
          (let [writes* (atom 0)]
            (with-redefs [file-io/replace-file! (fn [& _] (swap! writes* inc))]
              (is (= old-ms (date-of))))
            (is (zero? @writes*))))
        (testing "a read keeps the change date"
          (let [change #(select-keys (get (disk-ledger) key) [:body-hash :changed-at])
                before (change)]
            (is (= #{:body-hash :changed-at} (set (keys before))))
            (m.memory.usage/record-read! (ctx db) key)
            (is (= before (change)))))
        (testing "a body change moves the date to the file time, not to the time of the scan"
          (let [edited-ms (- (System/currentTimeMillis) (* 5 ms-per-day))]
            (write! "beta" "new body" edited-ms)
            (is (= edited-ms (date-of)))))
        (testing "a file time in the future, as from a clock ahead, is cut to now"
          (write! "beta" "third body" (+ (System/currentTimeMillis) ms-per-day))
          (is (<= (long (date-of)) (System/currentTimeMillis))))))))

;; --- Ranking

(deftest ranked-scan-usage-score-ordering-test
  (let [now-ms 1755000000000
        days-before (fn [days] (- now-ms (* days ms-per-day)))]
    (testing "a recently used note outranks an older, higher-read stale note and exposes monotonic scores"
      (test-helper/with-memory-root
        (fn [root]
          (let [db (test-db [(test-helper/file-path "/repo/one")])
                global (fs/file root "global")
                stale (fs/file global "stale.md")
                active (fs/file global "active.md")]
            (write-md-at! stale (days-before 365))
            (write-md-at! active (days-before 30))
            (write-ledger! {(str (fs/absolutize stale)) {:reads 60 :last-read-at (days-before 60)}
                            (str (fs/absolutize active)) {:reads 10 :last-read-at (days-before 1)}})
            (let [{:keys [entries]} (m.memory.usage/ranked-scan db {:now-ms now-ms})]
              (is (= ["active.md" "stale.md"] (mapv #(fs/file-name (:path %)) entries)))
              (is (apply > (map :score entries)) "each entry carries its score for downstream index selection"))))))
    (testing "a hot burst outranks a fresh note while hot, then fades below a fresh note after weeks of disuse"
      (test-helper/with-memory-root
        (fn [root]
          (let [db (test-db [])
                global (fs/file root "global")
                burst (fs/file global "burst.md")
                initial-fresh (fs/file global "initial-fresh.md")
                later-fresh (fs/file global "later-fresh.md")]
            (write-md-at! burst now-ms)
            (write-md-at! initial-fresh now-ms)
            (write-ledger! {(str (fs/absolutize burst)) {:reads 40 :last-read-at now-ms}})
            (is (= ["burst.md" "initial-fresh.md"]
                   (mapv #(fs/file-name (:path %))
                         (:entries (m.memory.usage/ranked-scan db {:now-ms now-ms})))))
            (let [later (+ now-ms (* 60 ms-per-day))]
              (write-md-at! later-fresh later)
              (is (= "later-fresh.md"
                     (fs/file-name (:path (first (:entries (m.memory.usage/ranked-scan db {:now-ms later}))))))))))))
    (testing "more reads still rank higher, with sublinear diminishing returns"
      (test-helper/with-memory-root
        (fn [root]
          (let [db (test-db [])
                global (fs/file root "global")
                r40 (fs/file global "r40.md")
                r5 (fs/file global "r5.md")
                r1 (fs/file global "r1.md")]
            (doseq [f [r40 r5 r1]] (write-md-at! f (days-before 30)))
            (write-ledger! {(str (fs/absolutize r40)) {:reads 40 :last-read-at (days-before 1)}
                            (str (fs/absolutize r5)) {:reads 5 :last-read-at (days-before 1)}
                            (str (fs/absolutize r1)) {:reads 1 :last-read-at (days-before 1)}})
            (let [entries (:entries (m.memory.usage/ranked-scan db {:now-ms now-ms}))
                  by-name (into {} (map (juxt #(fs/file-name (:path %)) :score)) entries)]
              (is (= ["r40.md" "r5.md" "r1.md"] (mapv #(fs/file-name (:path %)) entries)))
              (is (< (/ (by-name "r40.md") (by-name "r5.md")) 3.0)
                  "log damping keeps 8x reads well below an 8x score ratio"))))))
    (testing "a file timestamp in the future counts as fresh, not higher than now"
      (test-helper/with-memory-root
        (fn [root]
          (let [db (test-db [])
                global (fs/file root "global")
                current (fs/file global "now.md")
                ahead (fs/file global "ahead.md")]
            (write-md-at! current now-ms)
            (write-md-at! ahead (+ now-ms (* 10 ms-per-day)))
            (let [by-name (into {} (map (juxt #(fs/file-name (:path %)) :score))
                                (:entries (m.memory.usage/ranked-scan db {:now-ms now-ms})))]
              (is (= (by-name "now.md") (by-name "ahead.md"))))))))))

(deftest ranked-scan-usage-ordering-stays-per-group-test
  (testing "a heavily-used global entry must not leapfrog an unused personal one"
    (test-helper/with-memory-root
      (fn [root]
        (let [db (test-db [(test-helper/file-path "/repo/one")])
              now-ms (System/currentTimeMillis)
              personal (fs/file root "projects"
                                (m.memory.store/project-slug (test-helper/file-path "/repo/one")))
              global (fs/file root "global")]
          (write-md-at! (fs/file personal "p.md") (- now-ms ms-per-day))
          (write-md-at! (fs/file global "g.md") (- now-ms 3600000))
          (write-ledger! {(str (fs/absolutize (fs/file global "g.md")))
                          {:reads 100 :last-read-at (- now-ms 3600000)}})
          (let [{:keys [entries]} (m.memory.usage/ranked-scan db {:now-ms now-ms})]
            (is (= ["p.md" "g.md"] (mapv #(fs/file-name (:path %)) entries))
                "usage data must not reorder across group boundaries")
            (is (= [:personal :global] (mapv :tier entries)))))))))

;; --- Disk failures and other processes

(deftest corrupt-ledger-warns-and-starts-empty-test
  (test-helper/with-memory-root
    (fn [root]
      (let [warns* (atom [])]
        (with-redefs [logger/warn (fn [& args] (swap! warns* conj args))]
          (spit (str (m.memory.usage/usage-file)) "{{:reads (unclosed")
          (is (= {} (disk-ledger)) "corrupt ledger is treated as empty, no throw")
          (is (seq @warns*) "corruption logs a warning"))
        (testing "recording still works after corruption"
          (let [db (test-db [(test-helper/file-path "/repo/one")])
                f (fs/file root "projects"
                           (m.memory.store/project-slug (test-helper/file-path "/repo/one"))
                           "note.md")]
            (write-md! f)
            (m.memory.usage/record-read! (ctx db) (str f))
            (is (= 1 (get-in (edn/read-string (slurp (str (m.memory.usage/usage-file))))
                             [(str (fs/absolutize (fs/file f))) :reads]))
                "the corrupt file is healed by the next convergent write")))))))

(deftest unreadable-ledger-keeps-history-test
  (test-helper/with-memory-root
    (fn [root]
      (let [note (fs/file root "global" "note.md")
            path (str note)
            bytes-on-disk #(vec (Files/readAllBytes (.toPath (m.memory.usage/usage-file))))
            warnings (atom [])]
        (write-md! note)
        (write-ledger! {path {:reads 7}})
        (let [before (bytes-on-disk)]
          (with-redefs-fn {#'m.memory.usage/read-ledger-text
                           (fn [_] (throw (IOException. "Permission denied")))
                           #'logger/warn (fn [& args] (swap! warnings conj args))}
            (fn []
              (m.memory.usage/record-read! (ctx {}) path)
              (is (= [0] (mapv :reads (:entries (m.memory.usage/ranked-scan {})))))))
          (is (seq @warnings))
          (is (= before (bytes-on-disk)))
          (is (= [7] (mapv :reads (:entries (m.memory.usage/ranked-scan {}))))))))))

(deftest failed-ledger-replacement-keeps-disk-ledger-test
  ;; A full disk or a lost permission must not stop a prompt.
  (test-helper/with-memory-root
    (fn [root]
      (let [f (fs/file root "global" "note.md")
            warnings (atom [])]
        (write-md! f)
        (m.memory.usage/record-read! (ctx {}) (str f))
        (let [before (disk-ledger)]
          (with-redefs [file-io/atomic-move! (fn [& _] (throw (ex-info "Replacement failed" {})))
                        logger/warn (fn [& args] (swap! warnings conj args))]
            (m.memory.usage/record-read! (ctx {}) (str f)))
          (is (seq @warnings) "the lost update is logged, and nothing is thrown")
          (is (= before (disk-ledger))))))))

(deftest concurrent-reads-test
  (test-helper/with-memory-root
    (fn [root]
      (let [f (fs/file root "global" "note.md")
            path (str f)
            start (promise)]
        (write-md! f)
        (let [workers (mapv (fn [_]
                              (future
                                @start
                                (dotimes [_ 10]
                                  (m.memory.usage/record-read! (ctx {}) path)
                                  (m.memory.usage/enrich [{:path path :body "body" :mtime-ms 100}]))))
                            (range 8))]
          (deliver start true)
          (doseq [worker workers]
            (is (not= ::timeout (deref worker 10000 ::timeout)))))
        (let [disk (disk-ledger)]
          (is (= 80 (get-in disk [path :reads])))
          (is (= 100 (get-in disk [path :changed-at])))
          (is (= (digest/sha-256-hex "body") (get-in disk [path :body-hash]))))
        (is (empty? (fs/glob root "*.tmp")))))))

(defn ^:private start-child!
  "Starts a JVM with the test classpath that runs `code`, Clojure source, with
   its output in `output`. The code goes through a file, not `-e`: Windows
   drops the double quotes of a command-line argument."
  ^Process [root code output]
  (let [script (io/file root (str "child-" (random-uuid) ".clj"))
        java (str (io/file (System/getProperty "java.home") "bin"
                           (if (.startsWith (System/getProperty "os.name") "Windows")
                             "java.exe" "java")))
        ^java.util.List command [java "-cp" (System/getProperty "java.class.path")
                                 "clojure.main" (str script)]]
    (spit script code)
    (.start (doto (ProcessBuilder. command)
              (.redirectErrorStream true)
              (.redirectOutput (io/file output))))))

(defn ^:private await-file [f ^Process process]
  (let [deadline (+ (System/nanoTime) (* 60 1000000000))]
    (loop []
      (cond
        (fs/exists? f) true
        (or (not (.isAlive process)) (> (System/nanoTime) deadline)) false
        :else (do (Thread/sleep 10) (recur))))))

(defn ^:private wait-for-file-code
  "Child code that waits until `f` exists, for at most `seconds`."
  [f seconds]
  (str "(let [deadline (+ (System/nanoTime) " (* seconds 1000000000) ")]"
       " (while (and (not (.exists (java.io.File. " (pr-str (str f)) ")))"
       "             (< (System/nanoTime) deadline))"
       "  (Thread/sleep 10)))"))

(deftest independent-process-concurrent-increments-test
  (test-helper/with-memory-root
    (fn [root]
      (let [f (fs/file root "global" "note.md")
            ready (io/file root "child-ready")
            release (io/file root "child-release")
            attempting (io/file root "child-attempting")
            output (io/file root "child-output")]
        (write-md! f)
        (let [process (start-child!
                       root
                       (str "(require '[eca.modules.memory.store :as m.memory.store] '[eca.modules.memory.usage :as m.memory.usage])"
                            "(with-redefs [m.memory.store/memory-root-dir (constantly (clojure.java.io/file " (pr-str (str root)) "))]"
                            " (spit " (pr-str (str ready)) " \"ready\")"
                            (wait-for-file-code release 60)
                            " (spit " (pr-str (str attempting)) " \"attempting\")"
                            " (m.memory.usage/record-read! " (pr-str (ctx {})) " " (pr-str (str f)) "))"
                            "(shutdown-agents)")
                       output)]
          (try
            (is (await-file ready process) (str "Child failed to start: " (slurp output)))
            ;; The parent commits its increment first; the child then waits for
            ;; the ledger lock and must merge into the parent's value.
            (m.memory.usage/record-read! (ctx {}) (str f))
            (file-io/with-os-file-lock-fn
              (io/file root "usage.edn.lock")
              (fn []
                (spit release "release")
                (is (await-file attempting process) (slurp output))
                (is (not (.waitFor process 250 TimeUnit/MILLISECONDS))
                    "child waits while the parent holds the ledger OS lock")
                (is (= 1 (get-in (disk-ledger) [(str f) :reads])))))
            (let [finished? (.waitFor process 60 TimeUnit/SECONDS)]
              (is finished? "Child must finish within the deadline")
              (when finished?
                (is (zero? (.exitValue process)) (slurp output))))
            (is (= 2 (get-in (disk-ledger) [(str f) :reads]))
                "both independent process increments survive")
            (finally
              (.destroyForcibly process)
              (.waitFor process 5 TimeUnit/SECONDS))))))))

(defn ^:private with-held-ledger-lock
  "Holds the OS lock on `<usage-file>.lock` in-process via a separate FileChannel
   while calling `f`. `file-io/acquire-os-lock` treats an overlapping channel
   lock identically to a lock held by a frozen external process."
  [f]
  (let [lock-file (io/file (str (m.memory.usage/usage-file) ".lock"))]
    (io/make-parents lock-file)
    (with-open [raf (RandomAccessFile. lock-file "rw")]
      (let [held (.lock (.getChannel raf))]
        (try
          (f)
          (finally (.release held)))))))

(deftest frozen-lock-holder-does-not-stop-a-prompt-test
  (test-helper/with-memory-root
    (fn [root]
      (let [f (fs/file root "global" "note.md")
            key (str f)
            warns* (atom [])]
        (write-md! f)
        (m.memory.usage/record-read! (ctx {}) key)
        (with-held-ledger-lock
          (fn []
            (with-redefs-fn {#'m.memory.usage/ledger-lock-timeout-ms 200
                             #'logger/warn (fn [& args] (swap! warns* conj args))}
              (fn []
                (m.memory.usage/record-read! (ctx {}) key)
                (is (= 1 (:reads (first (:entries (m.memory.usage/ranked-scan {})))))
                    "the scan still gets the usage data on disk")))))
        (is (seq @warns*) "the given-up update is logged")
        (is (= 1 (get-in (disk-ledger) [key :reads]))
            "the read made while the lock was held is lost, nothing else")))))

(deftest unchanged-scan-does-not-wait-for-ledger-lock-test
  (test-helper/with-memory-root
    (fn [root]
      (let [f (fs/file root "global" "note.md")
            key (str f)
            warns* (atom [])]
        (write-md! f)
        (m.memory.usage/record-read! (ctx {}) key)
        ;; Seed `:body-hash` and `:changed-at` so the next scan is unchanged.
        (is (= [1] (mapv :reads (:entries (m.memory.usage/ranked-scan {})))))
        (with-held-ledger-lock
          (fn []
            (with-redefs-fn {#'m.memory.usage/ledger-lock-timeout-ms 200
                             #'logger/warn (fn [& args] (swap! warns* conj args))}
              (fn []
                (is (= [1] (mapv :reads (:entries (m.memory.usage/ranked-scan {})))))
                (is (empty? @warns*) "an unchanged scan never attempts to acquire the ledger lock")))))))))

(deftest enrich-rereads-under-lock-preserving-concurrent-read-test
  (test-helper/with-memory-root
    (fn [root]
      (let [f (fs/file root "global" "note.md")
            key (str f)
            real-update @#'m.memory.usage/update-ledger!]
        (write-md! f)
        (m.memory.usage/record-read! (ctx {}) key)
        (m.memory.usage/ranked-scan {})
        (spit (str f) "---\nname: Note\ndescription: D\n---\n\nupdated body\n")
        ;; Between enrich's initial lock-free read and acquiring the lock,
        ;; another read lands on disk; enrich must re-read under lock so it survives.
        (let [bumped* (atom false)]
          (with-redefs-fn {#'m.memory.usage/update-ledger!
                           (fn [update-fn]
                             (when (compare-and-set! bumped* false true)
                               (real-update (fn [ledger]
                                              (update-in ledger [key :reads] (fnil inc 0)))))
                             (real-update update-fn))}
            (fn []
              (let [entries (:entries (m.memory.usage/ranked-scan {}))]
                (is (= [2] (mapv :reads entries))))))
          (let [disk (disk-ledger)]
            (is (= 2 (get-in disk [key :reads])) "concurrent read count survived")
            (is (= (digest/sha-256-hex "updated body") (get-in disk [key :body-hash])))))))))

(deftest malformed-ledger-entry-still-shows-healthy-notes-without-clobbering-disk-test
  (test-helper/with-memory-root
    (fn [root]
      (let [good (fs/file root "global" "good.md")
            key (str good)]
        (write-md! good)
        (doseq [[label bad-ledger] [["numeric key" {123 {:reads 1} key {:reads 5}}]
                                    ["non-map value" {key "corrupt"}]]]
          (testing label
            (let [warnings (atom [])]
              (write-ledger! bad-ledger)
              (let [before (slurp (str (m.memory.usage/usage-file)))]
                (with-redefs [logger/warn (fn [& args] (swap! warnings conj args))]
                  (let [{:keys [entries]} (m.memory.usage/ranked-scan {})]
                    (is (= [key] (mapv :path entries)) "healthy notes are still returned")
                    (is (= [0] (mapv :reads entries)) "falls back to zero usage stats")))
                (is (seq @warnings))
                (is (= before (slurp (str (m.memory.usage/usage-file))))
                    "malformed ledger on disk is not overwritten with an empty map")))))
        (testing "unchanged entry with non-numeric :reads still returns healthy note"
          (write-ledger! {key {:reads "bad" :body-hash (digest/sha-256-hex "body") :changed-at 1000}})
          (let [{:keys [entries]} (m.memory.usage/ranked-scan {})]
            (is (= [key] (mapv :path entries)))
            (is (= [0] (mapv :reads entries)))))))))
