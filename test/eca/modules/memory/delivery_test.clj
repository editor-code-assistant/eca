(ns eca.modules.memory.delivery-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.chat.lifecycle :as f.chat.lifecycle]
   [eca.modules.memory.delivery :as m.memory.delivery]
   [eca.modules.memory.render :as m.memory.render]
   [eca.modules.memory.store :as m.memory.store]
   [eca.modules.memory.usage :as m.memory.usage]
   [eca.shared :as shared]
   [eca.test-helper :as h]))

(set! *warn-on-reflection* true)

(def ^:private db
  {:workspace-folders [{:uri (h/file-uri "file:///repo/one") :name "one"}]})

(def ^:private config
  {:memory {:writeMode "agent"
            :index {:maxEntries 100 :maxTokens 2000}}})

(defn ^:private memory-dirs [root]
  (with-redefs [m.memory.store/memory-root-dir (constantly root)]
    (into {} (map (juxt :tier :dir)) (m.memory.store/memory-dirs db))))

(defn ^:private write-note!
  "Writes a valid memory file."
  [file name description body & {:keys [tags]}]
  (fs/create-dirs (fs/parent file))
  (spit (str file)
        (str "---\n"
             "name: " name "\n"
             "type: fact\n"
             "description: " description "\n"
             (when tags (str "tags: [" (string/join ", " tags) "]\n"))
             "---\n\n"
             body "\n")))

(defn ^:private turn
  "Runs one delivery step. `messages` is the window."
  ([root messages] (turn root messages config))
  ([root messages cfg]
   (with-redefs [m.memory.store/memory-root-dir (constantly root)]
     (m.memory.delivery/turn-block {:chat-id "chat-1"
                                    :db db
                                    :config cfg
                                    :window messages
                                    :module-id :eca/memory}))))

(defn ^:private block-text [block]
  (:text block))

(defn ^:private state [block]
  (:data block))

(defn ^:private context-block
  "The module's context on a stored user message: its block and its state."
  ([state] (context-block :eca/memory state))
  ([module-id state]
   (f.chat.lifecycle/context-block {:content "..." :module module-id :data state})))

(defn ^:private window-with [state & extra]
  (vec (cons {:role "user" :content [{:type :text :text "hi"} (context-block state)]} extra)))

(defn ^:private tool-output
  "A stored tool call output, as ECA writes it into chat history."
  ([name arguments] (tool-output "call-1" name arguments))
  ([id name arguments]
   {:role "tool_call_output"
    :content {:id id
              :server "eca"
              :name name
              :arguments arguments
              :output {:contents []}}}))

(deftest first-prompt-gets-a-full-index-test
  (h/with-memory-root
    (fn [root]
      (let [dirs (memory-dirs root)]
        (testing "an empty store sends nothing (even with prior file writes), and the dirs exist, as the guidance says"
          (is (nil? (turn root [])))
          (is (nil? (turn root [(tool-output "w-1" "write_file" {"path" (str (fs/file root "src" "a.clj"))})])))
          (is (fs/directory? (get dirs :global)))
          (is (fs/directory? (get dirs :personal))))
        (write-note! (str (fs/file (get dirs :global) "a.md")) "Cache" "Cache notes" "body")
        (write-note! (str (fs/file (get dirs :personal) "b.md")) "Build" "Build notes" "body")
        (let [result (turn root [])]
          (testing "the first prompt of a window carries the whole index"
            (is (string/starts-with? (block-text result) "<memory-index description="))
            (is (string/includes? (block-text result) "- Cache [fact]"))
            (is (string/includes? (block-text result) "- Build [fact]")))
          (testing "the block carries the full current state"
            (let [notes (:notes (state result))
                  a (str (fs/file (get dirs :global) "a.md"))]
              (is (= #{a (str (fs/file (get dirs :personal) "b.md"))} (set (keys notes))))
              (is (= "Cache" (get-in notes [a :name])))
              (is (= "global" (get-in notes [a :label]))))))))))

(deftest memory-problems-dont-stop-a-request-test
  (h/with-memory-root
    (fn [root]
      (write-note! (str (fs/file (get (memory-dirs root) :global) "a.md")) "Cache" "Cache notes" "body")
      (with-redefs [m.memory.usage/ranked-scan (fn [& _] (throw (ex-info "scan exploded" {})))]
        (is (nil? (turn root [])) "no block, and no exception for the request")))))

(deftest no-change-sends-nothing-test
  (h/with-memory-root
    (fn [root]
      (let [dirs (memory-dirs root)]
        (write-note! (str (fs/file (get dirs :global) "a.md")) "Cache" "Cache notes" "body")
        (let [first-result (turn root [])]
          (is (some? first-result))
          (is (nil? (turn root (window-with (state first-result))))))))))

(deftest zero-token-budget-sends-nothing-test
  (h/with-memory-root
    (fn [root]
      (let [a (str (fs/file (get (memory-dirs root) :global) "a.md"))
            zero (assoc-in config [:memory :index :maxTokens] 0)]
        (write-note! a "Cache" "Cache notes" "body")
        (is (nil? (turn root [] zero)) "a new window gets no index")
        (testing "set to 0 in an open chat, an outside change sends no change list either"
          (let [first-result (turn root [])]
            (write-note! a "Cache" "Cache notes" "body extended")
            (is (some? (turn root (window-with (state first-result)))) "with a budget it would")
            (is (nil? (turn root (window-with (state first-result)) zero)))))))))

(deftest change-list-for-new-changed-and-deleted-test
  (h/with-memory-root
    (fn [root]
      (let [dirs (memory-dirs root)
            global (get dirs :global)
            a (str (fs/file global "a.md"))
            b (str (fs/file global "b.md"))
            c (str (fs/file global "c.md"))
            baseline* (atom nil)]
        (write-note! a "Cache" "Cache notes" "body")
        (write-note! b "Build" "Build notes" "body")
        (reset! baseline* (state (turn root [])))
        (testing "a new note is listed"
          (write-note! c "Deploy" "Deploy notes" "body")
          (let [result (turn root (window-with @baseline*))]
            (is (string/starts-with? (block-text result) "<memory-changes description="))
            (is (string/includes? (block-text result) "- Deploy [fact]"))
            (is (not (string/includes? (block-text result) "- Cache [fact]")))
            (reset! baseline* (state result))))
        (testing "a changed note is listed, also on the same day"
          (write-note! a "Cache" "Cache notes" "body extended")
          (let [result (turn root (window-with @baseline*))]
            (is (string/starts-with? (block-text result) "<memory-changes description="))
            (is (string/includes? (block-text result) "- Cache [fact]"))
            (is (string/includes? (block-text result) "Cache notes"))
            (reset! baseline* (state result))))
        (testing "a deleted note is listed with its name, under its dir"
          (fs/delete (fs/file b))
          (let [result (turn root (window-with @baseline*))]
            (is (string/includes? (block-text result) "## global\n- Build (deleted)"))
            (is (not (contains? (:notes (state result)) b))
                "the new state has no entry for the deleted note")
            (reset! baseline* (state result))))
        (testing "deleting the last notes is listed too"
          (fs/delete (fs/file a))
          (fs/delete (fs/file c))
          (let [result (turn root (window-with @baseline*))]
            (is (string/includes? (block-text result) "- Cache (deleted)"))
            (is (string/includes? (block-text result) "- Deploy (deleted)"))
            (is (= {} (:notes (state result))))))))))

(deftest retag-is-not-a-change-test
  (h/with-memory-root
    (fn [root]
      (let [dirs (memory-dirs root)
            a (str (fs/file (get dirs :global) "a.md"))]
        (write-note! a "Cache" "Cache notes" "body" :tags ["alpha"])
        (let [first-state (state (turn root []))]
          (testing "a tag-only rewrite sends no block"
            (write-note! a "Cache" "Cache notes" "body" :tags ["beta-gamma"])
            (is (nil? (turn root (window-with first-state)))))
          (testing "a body rewrite is a change"
            (write-note! a "Cache" "Cache notes" "body with more" :tags ["beta-gamma"])
            (is (string/starts-with? (block-text (turn root (window-with first-state)))
                                     "<memory-changes description="))))))))

(defn ^:private fresh-write-store
  "Root with a note `a`, an empty `b` and filler notes, so that a one-line
   change list is estimated smaller than the full index. Calls
   `(f root dirs a b first-state)`."
  [f]
  (h/with-memory-root
    (fn [root]
      (let [dirs (memory-dirs root)
            global (get dirs :global)
            a (str (fs/file global "a.md"))
            b (str (fs/file global "b.md"))]
        (write-note! a "Cache" "Cache notes" "body")
        (doseq [i (range 5)]
          (write-note! (str (fs/file global (str "filler" i ".md")))
                       (str "Filler" i)
                       (str "Filler topic " i)
                       "filler body"))
        (f root dirs a b (state (turn root [])))))))

(deftest changed-index-limits-refresh-once-test
  (fresh-write-store
   (fn [root _dirs a _b first-state]
     (let [step #(turn root (window-with %1) %2)
           hidden (step first-state (assoc-in config [:memory :index :maxEntries] 0))
           listed (step (state hidden) config)
           smaller (assoc-in config [:memory :index :maxTokens] 1000)
           resized (step (state listed) smaller)]
       (is (string/includes? (block-text hidden) "Showing 0 of 6 memories."))
       (is (string/includes? (block-text listed) "- Cache [fact]"))
       (is (= (block-text listed) (block-text resized)) "even a token-only change refreshes the index once")
       (let [renders (atom 0)]
         (with-redefs [m.memory.render/index-context (fn [_] (swap! renders inc) nil)]
           (is (nil? (step (state resized) (assoc-in smaller [:memory :index :maxTokens] 0))))
           (is (nil? (step (state resized) smaller)) "re-enable the last delivered limits")
           (is (zero? @renders) "neither a disabled nor an unchanged turn renders an index")))
       (write-note! a "Cache" "Cache notes" "updated body")
       (is (string/starts-with? (block-text (step (state resized) config)) "<memory-index ")
           "a limit change wins over a shorter change list")))))

(deftest targeted-note-refreshes-on-next-turn-test
  (testing "write_file and edit_file targeting a known note refresh it once, then later turns stay quiet"
    (doseq [tool ["write_file" "edit_file"]]
      (testing tool
        (fresh-write-store
         (fn [root _dirs a _b first-state]
           (write-note! a "Cache" "use pnpm" "body written by the chat")
           (let [result (turn root (window-with first-state (tool-output "w-1" tool {"path" a})))]
             (is (some? result))
             (is (string/starts-with? (block-text result) "<memory-changes description="))
             (is (string/includes? (block-text result) "- Cache [fact]"))
             (is (string/includes? (block-text result) "use pnpm"))
             (is (nil? (turn root (window-with (state result))))
                 "once refreshed, subsequent unchanged turns stay quiet")))))))
  (testing "move_file targeting a known note refreshes both source and destination, then stays quiet"
    (fresh-write-store
     (fn [root _dirs a b first-state]
       (fs/move a b)
       (let [result (turn root (window-with first-state (tool-output "m-1" "move_file" {"source" a "destination" b})))]
         (is (some? result))
         (is (string/starts-with? (block-text result) "<memory-changes description="))
         (is (string/includes? (block-text result) "- Cache (deleted)"))
         (is (string/includes? (block-text result) "- Cache [fact]"))
         (is (nil? (turn root (window-with (state result)))))))))
  (testing "external A -> B -> A reversal is still reported across write_file, edit_file, and move_file (source/destination)"
    (doseq [[label mk-call]
            [["write_file" (fn [a _b] (tool-output "w-1" "write_file" {"path" a}))]
             ["edit_file" (fn [a _b] (tool-output "e-1" "edit_file" {"path" a}))]
             ["move_file source" (fn [a b] (tool-output "m-1" "move_file" {"source" a "destination" b}))]
             ["move_file destination" (fn [a b] (tool-output "m-2" "move_file" {"source" b "destination" a}))]]]
      (testing label
        (fresh-write-store
         (fn [root _dirs a b first-state]
           ;; Chat writes/edits/moves note, then external writer restores exact original A.
           (write-note! a "Cache" "use pnpm" "use pnpm body")
           (write-note! a "Cache" "Cache notes" "body")
           (let [result (turn root (window-with first-state (mk-call a b)))]
             (is (some? result) "must not silently drop the user's reversal back to the original hash")
             (is (string/starts-with? (block-text result) "<memory-changes description="))
             (is (string/includes? (block-text result) "- Cache [fact]"))
             (is (string/includes? (block-text result) "Cache notes"))
             (is (nil? (turn root (window-with (state result)))))))))))
  (testing "external deletion of a note the chat targeted is reported as deleted"
    (fresh-write-store
     (fn [root _dirs a _b first-state]
       (write-note! a "Cache" "use pnpm" "use pnpm body")
       (fs/delete a)
       (let [result (turn root (window-with first-state (tool-output "w-1" "write_file" {"path" a})))]
         (is (string/includes? (block-text result) "- Cache (deleted)"))
         (is (not (contains? (:notes (state result)) a)))))))
  (testing "non-existent paths, moved directories, non-notes, and rejected calls never trigger a block or invent deletions"
    (fresh-write-store
     (fn [root dirs a _b first-state]
       (let [global (get dirs :global)
             outside (str (fs/file root "outside" "note.md"))
             missing (str (fs/file global "missing.md"))
             gone-md-dir (str (fs/file global "archive.md"))
             txt (str (fs/file global "scratch.txt"))
             hidden (str (fs/file global ".hidden.md"))
             md-dir (str (fs/create-dirs (fs/file global "dir.md")))
             link (str (fs/file global "link.md"))]
         (when-not shared/windows-os?
           (fs/create-sym-link link a))
         (let [window (window-with
                       first-state
                       (tool-output "c-1" "write_file" {"path" outside})
                       (tool-output "c-2" "write_file" {"path" txt})
                       (tool-output "c-3" "write_file" {"path" hidden})
                       (tool-output "c-4" "move_file" {"source" (str (fs/file global "old-dir"))
                                                       "destination" md-dir})
                       (tool-output "c-4b" "move_file" {"source" (str (fs/file global "old-archive"))
                                                        "destination" gone-md-dir})
                       (assoc-in (tool-output "c-5" "write_file" {"path" a})
                                 [:content :rejection-reason] :user)
                       (assoc-in (tool-output "c-6" "edit_file" {"path" missing})
                                 [:content :error] true)
                       (tool-output "c-7" "write_file" {"path" (if shared/windows-os? txt link)}))]
           (is (nil? (turn root window)))))))))

(deftest broken-note-is-not-reported-as-deleted-test
  (fresh-write-store
   (fn [root _dirs a _b first-state]
     (spit a "---\nname: Cache\n---\nthe description is gone\n")
     (let [text (block-text (turn root (window-with first-state)))]
       (is (string/includes? text "- Cache (invalid file, not indexed)"))
       (is (not (string/includes? text "(deleted)")))))))

(deftest removed-workspace-note-is-not-invalid-test
  (fresh-write-store
   (fn [root dirs _a _b _first-state]
     (let [note (str (fs/file (get dirs :personal) "workspace.md"))]
       (write-note! note "Workspace note" "About this workspace" "valid body")
       (let [first-state (state (turn root []))
             result (m.memory.delivery/turn-block {:chat-id "chat-1"
                                                   :db (assoc db :workspace-folders [])
                                                   :config config
                                                   :window (window-with first-state)
                                                   :module-id :eca/memory})
             text (block-text result)]
         (is (fs/exists? note) "removing a workspace does not delete its notes")
         (is (string/starts-with? text "<memory-changes description="))
         (is (string/includes? text "- Workspace note (no longer indexed)"))
         (is (not (string/includes? text "invalid")))
         (is (not (string/includes? text "(deleted)")))
         (is (not (contains? (:notes (state result)) note))))))))

(deftest missing-prior-index-sends-full-index-test
  (h/with-memory-root
    (fn [root]
      (let [global (get (memory-dirs root) :global)
            a (str (fs/file global "a.md"))
            b (str (fs/file global "b.md"))]
        (testing "when no prior memory block exists (e.g. after /import), a 1-note store gets the full index"
          (write-note! a "First" "Created by this chat" "body")
          (let [write-a (tool-output "w-1" "write_file" {"path" a})
                result (turn root [write-a])]
            (is (some? result))
            (is (string/starts-with? (block-text result) "<memory-index description="))
            (is (string/includes? (block-text result) "- First [fact]"))
            (is (= #{a} (set (keys (:notes (state result))))))
            (is (nil? (turn root (window-with (state result))))
                "once indexed, later unchanged turns stay quiet")
            (testing "with a second note present too, the missing index still sends a full index covering both"
              (write-note! b "Second" "Another note" "body")
              (let [both (turn root [write-a])]
                (is (string/starts-with? (block-text both) "<memory-index description="))
                (is (string/includes? (block-text both) "- First [fact]"))
                (is (string/includes? (block-text both) "- Second [fact]"))
                (is (= #{a b} (set (keys (:notes (state both))))))))))))))

(deftest external-change-to-a-hidden-note-is-listed-test
  (h/with-memory-root
    (fn [root]
      (let [dirs (memory-dirs root)
            global (get dirs :global)
            a (str (fs/file global "a.md"))
            b (str (fs/file global "b.md"))
            limited (assoc-in config [:memory :index :maxEntries] 1)]
        (write-note! a "Cache" "Cache notes" "body")
        (write-note! b "Hidden" "Hidden notes" "body")
        (let [first-result (turn root [] limited)]
          (is (string/includes? (block-text first-result) "Showing 1 of 2 memories.")
              "the index caps hide one note")
          (is (= 2 (count (:notes (state first-result)))) "the state map holds both notes")
          (write-note! b "Hidden" "Hidden notes" "body changed externally")
          (let [result (turn root (window-with (state first-result)) limited)]
            (is (string/includes? (block-text result) "- Hidden [fact]")
                "changes of hidden notes are reported too")))))))

(deftest smaller-block-wins-test
  (h/with-memory-root
    (fn [root]
      (let [dirs (memory-dirs root)
            global (get dirs :global)
            limited (assoc-in config [:memory :index :maxTokens] 150)
            files (mapv #(str (fs/file global (str "n" % ".md"))) (range 12))]
        (doseq [i (range 12)]
          (write-note! (files i) (str "Note" i) (str "About topic " i) "body"))
        (let [first-state (state (turn root [] limited))]
          (doseq [i (range 12)]
            (write-note! (files i) (str "Note" i) (str "About topic " i) "body changed"))
          (let [result (turn root (window-with first-state) limited)]
            (is (string/starts-with? (block-text result) "<memory-index description=")
                "the capped index is smaller than the twelve-line change list")
            (is (string/includes? (block-text result) " of 12 memories.")
                "the configured maxTokens caps it")))))))

(deftest only-the-modules-own-state-counts-test
  (h/with-memory-root
    (fn [root]
      (let [dirs (memory-dirs root)]
        (write-note! (str (fs/file (get dirs :global) "a.md")) "Cache" "Cache notes" "body")
        (let [first-state (state (turn root []))]
          (testing "another module's context with the same :data shape is not a memory block"
            (is (string/starts-with?
                 (block-text (turn root [{:role "user" :content [{:type :text :text "hi"}
                                                                 (context-block :acme/other first-state)]}]))
                 "<memory-index description=")))
          (testing "the last block's state counts"
            (is (nil? (turn root (into (window-with {:notes {}})
                                       (window-with first-state)))))))))))
