(ns eca.modules.memory-test
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.api.test :as api.test]
   [eca.config :as config]
   [eca.features.chat :as f.chat]
   [eca.features.chat.ops :as f.chat.ops]
   [eca.features.commands :as f.commands]
   [eca.features.prompt :as f.prompt]
   [eca.features.tools :as f.tools]
   [eca.llm-api :as llm-api]
   [eca.modules :as modules]
   [eca.modules.approval :as m.approval]
   [eca.modules.chat-management :as m.chat-management]
   [eca.modules.memory :as m.memory]
   [eca.modules.memory.render :as m.memory.render]
   [eca.modules.memory.store :as m.memory.store]
   [eca.modules.memory.usage :as m.memory.usage]
   [eca.modules.tools.filesystem :as m.filesystem]
   [eca.shared :as shared]
   [eca.test-helper :as h]))

(set! *warn-on-reflection* true)

(h/reset-components-before-test)

(def ^:private on
  "The config that turns the module on."
  {:modules {:eca/memory {:enabled true}}})

(def ^:private valid-note "---\nname: A\ndescription: D\n---\n\nbody\n")

(defn ^:private write-note!
  ([f] (write-note! f "Note"))
  ([f name]
   (fs/create-dirs (fs/parent f))
   (spit (str f) (str "---\nname: " name "\ndescription: About " name "\ntype: fact\n---\n\nbody\n"))))

(defn ^:private test-db [roots]
  {:workspace-folders (mapv (fn [r] {:uri (shared/filename->uri (str r))}) roots)})

(defn ^:private temp-dir []
  (fs/file (fs/canonicalize (fs/create-temp-dir {:prefix "eca-memory-module-test"}))))

(defn ^:private ledger []
  (#'m.memory.usage/read-ledger-from-disk!))

(defn ^:private text [result]
  (get-in result [:contents 0 :text]))

;; --- File tools

(defn ^:private module-ctx
  ([db] (module-ctx db "chat-1"))
  ([db chat-id]
   {:module-id :eca/memory :chat-id chat-id :db* (atom db) :config {}}))

(defn ^:private run-file-tool!
  "The result of file tool `tool` alone, as the after-call event gets it."
  [tool args db]
  (dissoc ((get-in m.filesystem/definitions [tool :handler])
           args {:db db :chat-id "chat-1" :config {:toolCall {:readFile {:maxLines 2000}}}})
          :rollback-changes))

(defn ^:private call-file-tool!
  "Runs file tool `tool` with `args` as ECA does with the module on: the
   tool's handler, then the module's `:tool/after-call` handler. Returns the
   result the model gets."
  [tool args db & {:keys [chat-id tool-call-id] :or {chat-id "chat-1" tool-call-id "call-1"}}]
  (let [result (run-file-tool! tool args db)]
    (:result (api.test/run-event m.memory/module :tool/after-call
                                 {:tool (str "eca__" tool) :server "eca" :arguments args :result result
                                  :error? (boolean (:error result)) :tool-call-id tool-call-id}
                                 (module-ctx db chat-id)))))

(deftest write-file-test
  (h/with-memory-root
    (fn [root]
      (let [db (test-db [])
            a (str (fs/file root "global" "a.md"))]
        (fs/create-dirs (fs/file root "global"))
        (testing "a valid note keeps its result untouched and the ledger points to the write"
          (let [result (call-file-tool! "write_file" {"path" a "content" valid-note} db :tool-call-id "w-1")]
            (is (false? (:error result)))
            (is (= #{:error :contents} (set (keys result))))
            (is (string/includes? (text result) "Successfully wrote"))
            (is (= [{:chat-id "chat-1" :tool-call-id "w-1"}] (get-in (ledger) [a :writes])))))
        (testing "write_file validates its content argument rather than whatever is currently on disk"
          (let [raw-result (run-file-tool! "write_file" {"path" a "content" valid-note} db)
                invalid "---\nname: [unclosed\n---\n"]
            (spit a invalid)
            (let [res (:result (api.test/run-event m.memory/module :tool/after-call
                                                   {:tool "eca__write_file" :server "eca"
                                                    :arguments {"path" a "content" valid-note}
                                                    :result raw-result :error? false :tool-call-id "w-arg-valid"}
                                                   (module-ctx db)))]
              (is (false? (:error res)) "a concurrent invalid write on disk does not fail a valid write_file call"))
            (spit a valid-note)
            (let [res (:result (api.test/run-event m.memory/module :tool/after-call
                                                   {:tool "eca__write_file" :server "eca"
                                                    :arguments {"path" a "content" invalid}
                                                    :result raw-result :error? false :tool-call-id "w-arg-invalid"}
                                                   (module-ctx db)))]
              (is (true? (:error res)) "a concurrent valid write on disk does not hide an invalid write_file call"))))
        (testing "a write in a /memory-consolidate turn is marked as one"
          (call-file-tool! "write_file" {"path" a "content" valid-note}
                           (assoc-in db [:chats "chat-1" :modules :eca/memory :consolidating?] true)
                           :tool-call-id "w-2")
          (is (= [{:chat-id "chat-1" :tool-call-id "w-1"}
                  {:chat-id "chat-1" :tool-call-id "w-2" :consolidation? true}]
                 (get-in (ledger) [a :writes]))))
        (testing "an invalid note stays written, and the error says so and what to fix"
          (let [result (call-file-tool! "write_file" {"path" a "content" "---\nname: [unclosed\n---\n"} db :tool-call-id "w-invalid")]
            (is (true? (:error result)))
            (is (string/starts-with? (text result) (str "Wrote " a ", but it is not a valid memory note")))
            (is (string/includes? (text result) "Invalid memory file content"))
            (is (= "---\nname: [unclosed\n---\n" (slurp a)))))
        (testing "a file that isn't .md is flagged and does not pollute the usage ledger"
          (let [txt (str (fs/file root "global" "notes.txt"))
                result (call-file-tool! "write_file" {"path" txt "content" valid-note} db :tool-call-id "w-txt")]
            (is (true? (:error result)))
            (is (string/includes? (text result) "ends in `.md`"))
            (is (not (contains? (ledger) txt)))
            (is (fs/exists? txt))))
        (testing "a write outside the memory dirs is left alone"
          (let [outside (str (fs/file root "outside" "x.md"))
                result (call-file-tool! "write_file" {"path" outside "content" "no frontmatter"} db :tool-call-id "w-outside")]
            (is (false? (:error result)))
            (is (not (contains? (ledger) outside)))))
        (testing "a write through dot segments uses the canonical ledger key"
          (let [alias (str (fs/path root "global" ".." "global" "a.md"))
                result (call-file-tool! "write_file" {"path" alias "content" valid-note} db :chat-id "chat-2" :tool-call-id "w-dotdot")]
            (is (false? (:error result)))
            (is (some #(= "w-dotdot" (:tool-call-id %)) (get-in (ledger) [a :writes])))))))))

(deftest edit-file-test
  (h/with-memory-root
    (fn [root]
      (let [db (test-db [])
            a (str (fs/file root "global" "a.md"))]
        (fs/create-dirs (fs/file root "global"))
        (spit a valid-note)
        (testing "an edit that keeps the note valid records the ledger pointer"
          (let [result (call-file-tool! "edit_file" {"path" a "original_content" "body" "new_content" "new body"}
                                        db :tool-call-id "e-1")]
            (is (false? (:error result)))
            (is (= [{:chat-id "chat-1" :tool-call-id "e-1"}] (get-in (ledger) [a :writes])))))
        (testing "an edit that breaks the frontmatter stays applied, and the error says so"
          (let [result (call-file-tool! "edit_file" {"path" a "original_content" "description: D\n" "new_content" ""} db :tool-call-id "e-broken")]
            (is (true? (:error result)))
            (is (string/starts-with? (text result) (str "Applied the edit to " a ", but it is not a valid memory note")))
            (is (string/includes? (text result) "`description`"))
            (is (not (string/includes? (slurp a) "description:")))))))))

(deftest move-file-test
  (h/with-memory-root
    (fn [root]
      (let [db (test-db [])
            global (fs/file root "global")
            a (str (fs/file global "a.md"))
            b (str (fs/file global "b.md"))]
        (write-note! a)
        (m.memory.usage/record-read! {:chat-id "chat-1" :db db} a)
        (testing "a moved note: the ledger follows"
          (let [result (call-file-tool! "move_file" {"source" a "destination" b} db :tool-call-id "m-1")]
            (is (false? (:error result)))
            (is (nil? (get (ledger) a)))
            (is (= 1 (get-in (ledger) [b :reads])))))
        (testing "a directory move re-keys the notes below it"
          (let [source (fs/path global "nested")
                note (fs/path source "deep" "note.md")
                target (fs/path global "renamed" "deep" "note.md")]
            (write-note! note)
            (m.memory.usage/record-read! {:chat-id "chat-1" :db db} (str note))
            (let [result (call-file-tool! "move_file" {"source" (str source) "destination" (str (fs/path global "renamed"))} db :tool-call-id "m-dir")]
              (is (false? (:error result)))
              (is (= 1 (get-in (ledger) [(str target) :reads])))
              (is (nil? (get (ledger) (str note)))))))
        (testing "an invalid file moved into a memory dir is moved, and the error says so"
          (let [outside (str (fs/file root "outside.md"))
                c (str (fs/file global "c.md"))]
            (spit outside "no frontmatter")
            (let [result (call-file-tool! "move_file" {"source" outside "destination" c} db :tool-call-id "m-invalid")]
              (is (true? (:error result)))
              (is (string/starts-with? (text result) (str "Moved " outside " to " c ", but it is not a valid memory note")))
              (is (not (fs/exists? outside)))
              (is (fs/exists? c)))))
        (testing "a move that failed is left alone: the model gets the tool's own error"
          (let [args {"source" b "destination" (str (fs/file global "c.md"))}
                raw (run-file-tool! "move_file" args db)
                before (ledger)
                result (call-file-tool! "move_file" args db :tool-call-id "m-fail")]
            (is (true? (:error raw)))
            (is (= raw result))
            (is (= before (ledger)))))
        (testing "a file moved out of the memory dirs: destination isn't checked"
          (let [stray (str (fs/file global "notes.txt"))
                outside (str (fs/file root "outside" "notes.txt"))]
            (spit stray "scratch")
            (fs/create-dirs (fs/parent outside))
            (let [result (call-file-tool! "move_file" {"source" stray "destination" outside} db :tool-call-id "m-out")]
              (is (false? (:error result))))))
        (testing "a moved link reports a link error, does not attribute to its target, and keeps its target's ledger"
          (when-not shared/windows-os?
            (let [link (fs/path global "link.md")
                  moved-link (str (fs/path global "moved-link.md"))]
              (fs/create-sym-link link b)
              (let [before (ledger)
                    result (call-file-tool! "move_file" {"source" (str link) "destination" moved-link} db :tool-call-id "m-link")]
                (is (true? (:error result)))
                (is (string/includes? (text result) "not links"))
                (is (= before (ledger)))
                (is (some #(= moved-link (:path %)) (:skipped (m.memory.store/scan-memories db))))))))
        (testing "a link to an outside file moved into a memory dir is flagged as a link and skipped by scan"
          (when-not shared/windows-os?
            (let [outside-target (fs/file root "outside-target.md")
                  outside-link (fs/path root "outside-link.md")
                  dest-link (str (fs/path global "outside-link.md"))]
              (write-note! outside-target "Outside")
              (fs/create-sym-link outside-link outside-target)
              (let [before (ledger)
                    result (call-file-tool! "move_file" {"source" (str outside-link) "destination" dest-link} db :tool-call-id "m-outside-link")]
                (is (true? (:error result)))
                (is (string/includes? (text result) "not links"))
                (is (= before (ledger)))
                (is (some #(= dest-link (:path %)) (:skipped (m.memory.store/scan-memories db))))))))))))

(when-not shared/windows-os?
  (deftest symlink-paths-tool-agreement-test
    (h/with-memory-root
      (fn [root]
        (let [db (test-db [])
              global (fs/create-dirs (fs/path root "global"))]
          (testing "writing and reading inside a linked whole memory dir agrees with scan and ledger"
            (h/with-memory-root
              (fn [link-root]
                (let [real-global (fs/create-dirs (fs/path link-root "synced-global"))
                      linked-global (m.memory.store/global-dir)
                      _ (fs/create-sym-link linked-global real-global)
                      via-link (str (fs/path linked-global "synced.md"))
                      canonical (m.memory.store/abs-path (fs/path real-global "synced.md"))
                      res (call-file-tool! "write_file" {"path" via-link "content" valid-note} db :tool-call-id "w-sync")]
                  (is (false? (:error res)))
                  (call-file-tool! "read_file" {"path" via-link} db)
                  (is (= 1 (get-in (ledger) [canonical :reads])))
                  (is (= [{:chat-id "chat-1" :tool-call-id "w-sync"}] (get-in (ledger) [canonical :writes])))
                  (is (= [canonical] (mapv :path (:entries (m.memory.store/scan-memories db)))))))))
          (testing "symlink + `..` paths match the actual OS target across tool, validator, ledger and scan"
            (testing "dir/link-sub/../outside.md exits memory dir via link-sub"
              (let [elsewhere (fs/create-dirs (fs/path root "elsewhere-posix" "deep"))
                    link-sub (fs/path global "link-posix")
                    _ (fs/create-sym-link link-sub elsewhere)
                    via-dotdot (str (fs/path link-sub ".." "outside.md"))
                    actual-os-file (fs/path root "elsewhere-posix" "outside.md")
                    before (ledger)
                    res (call-file-tool! "write_file" {"path" via-dotdot "content" "no frontmatter"} db :tool-call-id "w-exit")]
                (is (fs/exists? actual-os-file) "OS wrote outside the memory dir")
                (is (not (fs/exists? (fs/path global "outside.md"))))
                (is (false? (:error res)) "outside file is not validated as a memory note")
                (is (= before (ledger)))))
            (testing "outside/link-to-sub/../real.md enters memory dir via link-to-sub"
              (let [sub (fs/create-dirs (fs/path global "real-sub"))
                    outside-dir (fs/create-dirs (fs/path root "outside-entry"))
                    link-to-sub (fs/path outside-dir "link-to-sub")
                    _ (fs/create-sym-link link-to-sub sub)
                    via-dotdot (str (fs/path link-to-sub ".." "entered.md"))
                    expected (m.memory.store/abs-path (fs/path global "entered.md"))
                    res (call-file-tool! "write_file" {"path" via-dotdot "content" valid-note} db :tool-call-id "w-enter")]
                (is (fs/exists? expected) "OS actually wrote inside the memory dir")
                (is (false? (:error res)))
                (is (= [{:chat-id "chat-1" :tool-call-id "w-enter"}] (get-in (ledger) [expected :writes])))
                (is (some #(= expected (:path %)) (:entries (m.memory.store/scan-memories db))))))
            (testing "dir/link-to-inner/../real.md resolves inside memory dir to the real parent"
              (let [inner (fs/create-dirs (fs/path global "sub-a" "inner"))
                    link-to-inner (fs/path global "link-to-inner")
                    _ (fs/create-sym-link link-to-inner inner)
                    via-dotdot (str (fs/path link-to-inner ".." "sibling.md"))
                    expected (m.memory.store/abs-path (fs/path global "sub-a" "sibling.md"))
                    res (call-file-tool! "write_file" {"path" via-dotdot "content" valid-note} db :tool-call-id "w-inner")]
                (is (fs/exists? expected))
                (is (false? (:error res)))
                (is (= [{:chat-id "chat-1" :tool-call-id "w-inner"}] (get-in (ledger) [expected :writes])))
                (is (some #(= expected (:path %)) (:entries (m.memory.store/scan-memories db))))))))))))

(deftest read-file-test
  (h/with-memory-root
    (fn [root]
      (let [a (str (fs/file root "global" "a.md"))
            read! (fn [db] (call-file-tool! "read_file" {"path" a} db))]
        (write-note! a)
        (testing "a read of a note counts as usage, and the result stays as it is"
          (is (string/includes? (text (read! (test-db []))) "name: Note"))
          (is (= 1 (get-in (ledger) [a :reads]))))
        (testing "not in a /memory-consolidate turn"
          (read! (assoc-in (test-db []) [:chats "chat-1" :modules :eca/memory :consolidating?] true))
          (is (= 1 (get-in (ledger) [a :reads]))))
        (testing "nor in a subagent of the consolidating chat"
          (read! (-> (test-db [])
                     (assoc-in [:chats "chat-1" :parent-chat-id] "parent-1")
                     (assoc-in [:chats "parent-1" :modules :eca/memory :consolidating?] true)))
          (is (= 1 (get-in (ledger) [a :reads]))))
        (testing "another chat's flag doesn't count"
          (read! (assoc-in (test-db []) [:chats "other" :modules :eca/memory :consolidating?] true))
          (is (= 2 (get-in (ledger) [a :reads]))))))))

;; --- Memory blocks

(defn ^:private event-ctx
  "A mock ctx for the module's chat event handlers."
  [& {:as opts}]
  (api.test/mock-ctx (merge {:module m.memory/module} opts)))

(defn ^:private block-of
  "The memory block the module's `event-id` handler gives for `event`: its
   `:additional-context`, with the `:data` that `api.test/run-event` would
   leave out."
  [event-id ctx event]
  (let [handler (get-in m.memory/module [:event-handlers event-id])]
    (:additional-context (handler ctx event))))

(deftest hidden-note-paths-test
  (h/with-memory-root
    (fn [root]
      (let [db (test-db [])
            global (fs/path root "global")
            folder (fs/create-dirs (fs/path global ".obsidian"))
            leaf (str (fs/path global ".note.md"))
            nested (str (fs/path folder "note.md"))
            written (call-file-tool! "write_file" {"path" leaf "content" valid-note} db :tool-call-id "w-hidden")]
        (is (= (fs/hidden? leaf) (:error written)))
        (when shared/windows-os? (fs/set-attribute folder "dos:hidden" true))
        (let [moved (call-file-tool! "move_file" {"source" leaf "destination" nested} db :tool-call-id "m-hidden")]
          (is (string/includes? (text moved) "hidden"))
          (is (= valid-note (slurp nested)))
          (is (empty? (ledger)) "hidden notes do not pollute the usage ledger")
          (is (empty? (:entries (m.memory.store/scan-memories db)))))))))

(deftest before-request-test
  (h/with-memory-root
    (fn [root]
      (write-note! (fs/file root "global" "a.md") "Cache")
      (let [ctx (event-ctx)
            run (fn [prompt] (block-of :chat/before-request ctx {:prompt prompt}))]
        (testing "a request without an earlier block gets a full index, with the note state as :data"
          (let [{:keys [text data]} (run "hi")]
            (is (string/starts-with? text "<memory-index description="))
            (is (string/includes? text "- Cache [fact]"))
            (is (= #{(str (fs/file root "global" "a.md"))} (set (keys (:notes data)))))))
        (testing "/memory-consolidate gets no block, and its turn is a consolidation"
          (is (nil? (run "/memory-consolidate project")))
          (is (= {:consolidating? true} (get-in @(:db* ctx) [:chats "test-chat" :modules :eca/memory]))))
        (testing "by its full id too"
          (is (nil? (run "/eca:memory-consolidate"))))
        (testing "the next request ends it"
          (is (some? (run "thanks")))
          (is (nil? (get-in @(:db* ctx) [:chats "test-chat" :modules :eca/memory :consolidating?]))))
        (testing "a text that only starts like the command is a request"
          (is (some? (run "/memory-consolidated notes?"))))))))

(deftest compaction-window-events-test
  (h/with-memory-root
    (fn [root]
      (write-note! (fs/file root "global" "a.md") "Cache")
      (let [first-block (block-of :chat/before-request (event-ctx) {:prompt "hi"})
            before-compact {:role "user"
                            :content [{:type :text :text "hi"}
                                      {:type :text :text "<additionalContext>\n...\n</additionalContext>"
                                       :module :eca/memory :data (:data first-block)}]}
            compact-marker {:role "compact_marker" :content {:auto? false}}]
        (testing "before-request ignores memory blocks from before the last compaction"
          (is (string/starts-with?
               (:text (block-of :chat/before-request
                                (event-ctx :messages [before-compact compact-marker])
                                {:prompt "again"}))
               "<memory-index description=")))
        (testing "after-compact attaches a full index to the new window's summary"
          (let [ctx (event-ctx :messages [compact-marker {:role "user" :content [{:type :text :text "Summary"}]}])
                {:keys [text data]} (block-of :chat/after-compact ctx {:trigger :auto})]
            (is (string/starts-with? text "<memory-index description="))
            (is (seq (:notes data)))))
        (testing "after-compact returns nil when the store is empty"
          (fs/delete (fs/file root "global" "a.md"))
          (is (nil? (block-of :chat/after-compact (event-ctx) {:trigger :manual}))))))))

(deftest subagents-get-no-memory-test
  (h/with-memory-root
    (fn [root]
      (write-note! (fs/file root "global" "a.md") "Cache")
      (let [ctx (event-ctx :chat {:subagent true :parent-chat-id "parent-1"})]
        (is (nil? (block-of :chat/before-request ctx {:prompt "hi"})))
        (is (nil? (block-of :chat/before-request ctx {:prompt "/memory-consolidate"})))
        (is (nil? (get-in @(:db* ctx) [:chats "test-chat" :modules :eca/memory])))
        (is (nil? (block-of :chat/after-compact ctx {:trigger :auto})))))))

;; --- System prompt

(def ^:private write-tools
  [{:full-name "eca__write_file" :name "write_file" :origin :native :server {:name "eca"}}
   {:full-name "eca__edit_file" :name "edit_file" :origin :native :server {:name "eca"}}])

(def ^:private read-tools
  [{:full-name "eca__read_file" :name "read_file" :origin :native :server {:name "eca"}}])

(defn ^:private static-prompt [config chat-id all-tools db]
  (#'f.prompt/build-static-instructions {:refined-contexts [] :agent "code" :config config :chat-id chat-id
                                         :all-tools all-tools
                                         :db (merge {:workspace-folders [{:uri "file:///workspace"}]} db)}))

(deftest guidance-test
  (h/with-memory-root
    (fn [_root]
      (testing "an agent with write tools gets the read and the write part, before the environment facts"
        (let [^java.util.List lines (string/split-lines (static-prompt on "chat-1" write-tools {}))]
          (is (some #{"## Memory"} lines))
          (is (some #{"## Memory write guidance"} lines))
          (is (< -1
                 (.indexOf lines "## Memory")
                 (.indexOf lines "## Memory write guidance")
                 (.indexOf lines "## Environment Context")))))
      (testing "it lists the chat's memory dirs: the workspace's project dir and the global dir"
        (let [static (static-prompt on "chat-1" write-tools {})
              dirs (mapv :dir (m.memory.store/memory-dirs {:workspace-folders [{:uri "file:///workspace"}]}))]
          (is (= 2 (count dirs)))
          (doseq [dir dirs]
            (is (string/includes? static (str "- " dir " (")) dir))))
      (testing "either write tool alone gets the write part"
        (doseq [tool write-tools]
          (is (some #{"## Memory write guidance"} (string/split-lines (static-prompt on "chat-1" [tool] {})))
              (:full-name tool))))
      (testing "an agent without write tools gets only the read part"
        (let [lines (string/split-lines (static-prompt on "chat-1" read-tools {}))]
          (is (some #{"## Memory"} lines))
          (is (not (some #{"## Memory write guidance"} lines)))))
      (testing "the write mode reaches the guidance"
        (let [dirs (m.memory.store/memory-dirs {:workspace-folders [{:uri "file:///workspace"}]})
              agent-mode (static-prompt on "chat-1" write-tools {})
              explicit-mode (static-prompt (assoc on :memory {:writeMode "explicit"}) "chat-1" write-tools {})]
          (is (not= agent-mode explicit-mode))
          (is (string/includes? agent-mode (m.memory.render/guidance-text {:write-mode "agent" :write? true :dirs dirs})))
          (is (string/includes? explicit-mode (m.memory.render/guidance-text {:write-mode "explicit" :write? true :dirs dirs})))))
      (testing "only an agent that can start subagents gets subagent guidance"
        (let [dirs (m.memory.store/memory-dirs {:workspace-folders [{:uri "file:///workspace"}]})
              with-sub (static-prompt on "chat-1" (conj write-tools {:full-name "eca__spawn_agent"}) {})
              without-sub (static-prompt on "chat-1" write-tools {})]
          (is (not= with-sub without-sub))
          (is (string/includes? with-sub (m.memory.render/guidance-text {:write-mode "agent" :write? true :subagents? true :dirs dirs})))))
      (testing "a subagent gets none"
        (is (not (string/includes? (static-prompt on "sub" write-tools {:chats {"sub" {:subagent true}}})
                                   "## Memory"))))
      (testing "the module off adds none"
        (is (not (string/includes? (static-prompt {} "chat-1" write-tools {}) "## Memory")))))))

;; --- Commands

(defn ^:private command! [command args db* config]
  (f.commands/handle-command! command args
                              {:chat-id "chat-1"
                               :db* db*
                               :config config
                               :messenger (h/messenger)
                               :full-model "openai/gpt-5.2"
                               :agent "code"
                               :all-tools []
                               :instructions {}
                               :user-messages []
                               :metrics (h/metrics)}))

(def ^:private consolidate-config
  (assoc on :prompts {:memoryConsolidate "TEST: consolidate my memories"}))

(deftest commands-listed-test
  (let [commands (fn [config]
                   (->> (f.commands/all-commands {:workspace-folders []} config)
                        (filter (comp #{"memories" "memory-consolidate"} :name))))]
    (testing "only with the module on"
      (is (empty? (commands {})))
      (is (= ["memories" "memory-consolidate"] (map :name (commands on)))))
    (testing "/memory-consolidate declares two optional arguments"
      (let [arguments (:arguments (last (commands on)))]
        (is (= ["scope" "instructions"] (mapv :name arguments)))
        (is (every? false? (map :required arguments)))))))

(deftest memory-consolidate-command-test
  (h/with-memory-root
    (fn [root]
      (let [workspace-root (temp-dir)
            personal-dir (fs/file root "projects" (m.memory.store/project-slug (str workspace-root)))
            personal-f (fs/file personal-dir "docker-ipv6.md")
            global-f (fs/file root "global" "team.md")
            db* (atom (test-db [workspace-root]))
            _ (write-note! personal-f)
            _ (write-note! global-f)
            _ (m.memory.usage/record-read! {:chat-id "chat-1" :db @db*} (str personal-f))
            result (command! "memory-consolidate" ["focus on docker"] db* consolidate-config)
            prompt (:prompt result)]
        (testing "the base prompt with the dirs and the usage snapshot"
          (is (= :send-prompt (:type result)))
          (is (string/starts-with? prompt "TEST: consolidate my memories"))
          (is (string/includes? prompt "Scope: project + global"))
          (is (string/includes? prompt (str (fs/absolutize personal-dir))))
          (is (string/includes? prompt (str (fs/absolutize (fs/parent global-f))))))
        (testing "a line per note with its usage"
          (is (re-find #"- Note \[fact\] \(docker-ipv6\.md\) — age 0d, reads 1, last read 0d ago" prompt))
          (is (string/includes? prompt "- Note [fact] (team.md) — age 0d, reads 0, last read never")))
        (testing "the user's request is its own trailing section"
          (is (< (.indexOf ^String prompt "Usage snapshot:")
                 (.indexOf ^String prompt "## Additional instructions from the user")
                 (.indexOf ^String prompt "focus on docker"))))
        (let [personal-trash (fs/file root "trash" "projects" (m.memory.store/project-slug (str workspace-root)))
              global-trash (fs/file root "trash" "global")]
          (testing "each dir comes with its trash, which the command creates"
            (is (fs/directory? personal-trash))
            (is (fs/directory? global-trash))
            (is (string/includes? prompt (str "- " (m.memory.store/abs-path personal-dir)
                                              " (" (fs/file-name workspace-root) "), trash: "
                                              (m.memory.store/abs-path personal-trash) "\n")))
            (is (string/includes? prompt (str "- " (m.memory.store/abs-path (fs/parent global-f))
                                              " (global), trash: " (m.memory.store/abs-path global-trash) "\n"))))
          (testing "a note moved into its trash is out of the scan, and moved back it keeps its history"
            (let [note (m.memory.store/abs-path personal-f)
                  trashed (str (fs/file personal-trash "2026-11-17-docker-ipv6.md"))
                  history (get (ledger) note)
                  result (call-file-tool! "move_file" {"source" note "destination" trashed} @db*)]
              (is (false? (:error result)))
              (is (= [(m.memory.store/abs-path global-f)] (mapv :path (:entries (m.memory.store/scan-memories @db*)))))
              (is (= 1 (:reads history)))
              (is (= history (get (ledger) (m.memory.store/abs-path trashed))) "it waits in the trash")
              (call-file-tool! "move_file" {"source" trashed "destination" note} @db*)
              (is (= history (get (ledger) note)))
              (is (not (contains? (ledger) (m.memory.store/abs-path trashed)))))))
        (fs/delete-tree workspace-root)))))

(deftest memory-consolidate-scope-test
  (h/with-memory-root
    (fn [root]
      (let [workspace-root (temp-dir)
            personal-dir (fs/file root "projects" (m.memory.store/project-slug (str workspace-root)))
            global-dir (fs/file root "global")
            write-bad! (fn [f] (fs/create-dirs (fs/parent f)) (spit (str f) "---\nname: [unclosed\n---\n"))
            _ (write-note! (fs/file personal-dir "project-note.md"))
            _ (write-note! (fs/file global-dir "global-note.md"))
            _ (write-bad! (fs/file personal-dir "project-bad.md"))
            _ (write-bad! (fs/file global-dir "global-bad.md"))
            run (fn [args] (:prompt (command! "memory-consolidate" args (atom (test-db [workspace-root])) consolidate-config)))
            has? (fn [prompt s] (string/includes? prompt s))]
        (testing "no scope keeps both tiers, broken files included"
          (let [prompt (run [])]
            (is (has? prompt "Scope: project + global\n\n"))
            (is (every? #(has? prompt %) ["project-note.md" "global-note.md" "project-bad.md" "global-bad.md"]))))
        (testing "project keeps only the personal dir and its broken files"
          (let [prompt (run ["project"])]
            (is (has? prompt "Scope: project\n\n"))
            (is (every? #(has? prompt %) ["project-note.md" "project-bad.md"]))
            (is (not-any? #(has? prompt %) ["global-note.md" "global-bad.md"]))
            (is (not (has? prompt (str (fs/absolutize global-dir)))))
            (is (not (has? prompt (str (fs/absolutize (fs/file root "trash" "global"))))))
            (is (not (has? prompt "## Additional instructions from the user")))))
        (testing "global, in any case"
          (let [prompt (run ["GLOBAL"])]
            (is (has? prompt "Scope: global\n\n"))
            (is (not-any? #(has? prompt %) ["project-note.md" "project-bad.md"]))))
        (testing "the text after the scope word stays a request"
          (let [prompt (run ["global" "only entries changed in the last 14 days"])]
            (is (has? prompt "## Additional instructions from the user\nonly entries changed in the last 14 days"))))
        (testing "only the first token is a scope word"
          (let [prompt (run ["project" "global"])]
            (is (has? prompt "Scope: project\n\n"))
            (is (has? prompt "## Additional instructions from the user\nglobal"))))
        (testing "a quoted value that starts with a scope word is a request"
          (let [prompt (run ["project notes are too long"])]
            (is (has? prompt "Scope: project + global\n\n"))
            (is (has? prompt "## Additional instructions from the user\nproject notes are too long"))))
        (testing "a typo keeps both tiers and reaches the model as a request"
          (let [prompt (run ["globl"])]
            (is (has? prompt "Scope: project + global\n\n"))
            (is (has? prompt "## Additional instructions from the user\nglobl"))))
        (fs/delete-tree workspace-root)))))

(deftest memory-consolidate-scan-error-test
  ;; The command runner shows the error and sends no request: a cleanup
  ;; prompt without its snapshot would only tell the model to stop.
  (with-redefs [m.memory.store/scan-memories (fn [& _] (throw (ex-info "scan exploded" {})))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"scan exploded"
                          (command! "memory-consolidate" [] (atom (test-db [])) consolidate-config)))))

(deftest memories-command-test
  (h/with-memory-root
    (fn [root]
      (let [workspace-root (temp-dir)
            personal-dir (fs/file root "projects" (m.memory.store/project-slug (str workspace-root)))
            global-dir (fs/file root "global")
            db* (atom (test-db [workspace-root]))
            text-of (fn [result] (get-in result [:chats "chat-1" :messages 0 :content 0 :text]))]
        ;; The text format is covered by `memories-text-test`; this checks what
        ;; the command feeds into it.
        (write-note! (fs/file personal-dir "project-note.md"))
        (write-note! (fs/file global-dir "global-note.md"))
        (spit (str (fs/file global-dir "broken.md")) "---\nname: [unclosed\n---\n")
        (testing "the notes, dirs and skipped files of the chat's store"
          (let [result (command! "memories" [] db* on)
                text (text-of result)]
            (is (= :chat-messages (:type result)) "the text goes to the chat, not to the model")
            (is (string/starts-with? text "Memories in the index: 2 of 2.\n"))
            (is (string/includes? text (str "`" (m.memory.store/abs-path personal-dir) "`\n\n- [fact] `Note`")))
            (is (string/includes? text (str "- `" (m.memory.store/abs-path (fs/file global-dir "broken.md")) "`: ")))))
        (testing "the index caps of the config decide which notes are in the index"
          (let [text (text-of (command! "memories" [] db* (assoc on :memory {:index {:maxEntries 1 :maxTokens 1500}})))]
            (is (string/starts-with? text "Memories in the index: 1 of 2. Limits: `memory.index.maxEntries` 1, `memory.index.maxTokens` 1500."))))
        (testing "the command does not count as a read of the notes"
          (is (= [0 0] (mapv :reads (:entries (m.memory.usage/ranked-scan @db*))))))
        (fs/delete-tree workspace-root)))))

;; --- Config

(defn ^:private with-config-files
  "Calls `f` with ECA's config read only from `init` (initializationOptions)
   and a workspace whose `.eca/config.json` is `project` (none when nil),
   with the workspace's db."
  [init project f]
  (let [dir (temp-dir)
        global-file (fs/file dir "global.json")]
    (try
      (spit (str global-file) "{}")
      (when project
        (io/make-parents (fs/file dir ".eca" "config.json"))
        (spit (str (fs/file dir ".eca" "config.json")) (json/generate-string project)))
      (with-redefs-fn {#'config/initialization-config* (atom init)
                       #'config/custom-config-file-path* (atom nil)
                       #'config/plugin-components* (atom nil)
                       #'config/global-config-file (constantly global-file)
                       #'config/config-from-envvar (constantly nil)}
        (fn []
          (config/clear-cache!)
          (f (test-db [dir]))))
      (finally
        (config/clear-cache!)
        (fs/delete-tree dir)))))

(deftest config-test
  (h/with-memory-root
    (fn [_root]
      (testing "off by default"
        (with-config-files {} nil
          (fn [db] (is (not (modules/active? (config/all db) :eca/memory))))))
      (testing "a project's .eca/config.json turns it on, and off"
        (with-config-files {} {"modules" {"eca/memory" {"enabled" true}}}
          (fn [db] (is (modules/active? (config/all db) :eca/memory))))
        (with-config-files {"modules" {"eca/memory" {"enabled" true}}} {"modules" {"eca/memory" {"enabled" false}}}
          (fn [db] (is (not (modules/active? (config/all db) :eca/memory))))))
      (testing "on: the defaults of its keys, and the default consolidation prompt"
        (with-config-files {"modules" {"eca/memory" {"enabled" true}}} nil
          (fn [db]
            (let [config (config/all db)]
              (is (= "agent" (get-in config [:memory :writeMode])))
              (is (= {:maxEntries 100 :maxTokens 2000} (get-in config [:memory :index])))
              (is (= (slurp (io/resource "prompts/memory_consolidate.md"))
                     (f.prompt/get-config-prompt :memoryConsolidate "code" config)))))))
      (testing "on: the memory dirs count as inside the workspace for the file tools' approval"
        (with-config-files {"modules" {"eca/memory" {"enabled" true}}} nil
          (fn [db]
            (let [config (config/all db)
                  dirs (mapv :dir (m.memory.store/memory-dirs db))
                  tools (f.tools/all-tools "chat-1" "code" db config)
                  tool (fn [full-name] (some #(when (= full-name (:full-name %)) %) tools))
                  note (str (fs/file (last dirs) "a.md"))]
              (is (= 2 (count dirs)) "the project dir and the global dir")
              (is (every? (set (get-in config [:toolCall :approval :insideWorkspace])) dirs))
              (is (= :allow (#'m.approval/approval tools (tool "eca__read_file") {"path" note} db config "code"))
                  "eca/filesystem allows reads inside the workspace"))))
        (testing "so an allow rule for writes decides for memory files as for workspace files"
          (let [allow-writes {"toolCall" {"approval" {"allow" {"eca__write_file" {}}}}}
                decision (fn [db]
                           (let [config (config/all db)
                                 tools (f.tools/all-tools "chat-1" "code" db config)
                                 tool (fn [full-name] (some #(when (= full-name (:full-name %)) %) tools))
                                 note (str (fs/file (m.memory.store/global-dir) "a.md"))]
                             {:read (#'m.approval/approval tools (tool "eca__read_file") {"path" note} db config "code")
                              :write (#'m.approval/approval tools (tool "eca__write_file") {"path" note "content" "x"}
                                                            db config "code")}))]
            (with-config-files (merge allow-writes {"modules" {"eca/memory" {"enabled" true}}}) nil
              (fn [db] (is (= {:read :allow :write :allow} (decision db)))))
            (testing "with the module off, a memory dir is outside the workspace and both ask"
              (with-config-files allow-writes nil
                (fn [db] (is (= {:read :ask :write :ask} (decision db)))))))))
      (testing "a move into the trash follows the same rules as other memory changes"
        (with-config-files {"modules" {"eca/memory" {"enabled" true}}
                            "toolCall" {"approval" {"allow" {"eca__move_file" {}}}}}
          nil
          (fn [db]
            (let [config (config/all db)
                  tools (f.tools/all-tools "chat-1" "code" db config)
                  move (some #(when (= "eca__move_file" (:full-name %)) %) tools)
                  dir (m.memory.store/abs-path (m.memory.store/global-dir))
                  trash (get (m.memory.store/trash-dirs db) dir)]
              (is (= :allow (#'m.approval/approval tools move {"source" (str (fs/file dir "a.md"))
                                                               "destination" (str (fs/file trash "2026-11-17-a.md"))}
                                                   db config "code"))))))))))

;; --- In a chat

(defn ^:private prompt!
  "Prompts through f.chat/prompt with a mocked LLM; returns the user message
   of the LLM request."
  [params]
  (let [sent* (atom nil)]
    (with-redefs [llm-api/sync-or-async-prompt! (fn [{:keys [user-messages on-first-response-received on-message-received]}]
                                                  (reset! sent* (last user-messages))
                                                  (on-first-response-received {:type :text :text "ok"})
                                                  (on-message-received {:type :text :text "ok"})
                                                  (on-message-received {:type :finish}))
                  llm-api/sync-prompt! (constantly nil)
                  f.tools/all-tools (constantly [])
                  config/await-plugins-resolved! (constantly true)]
      (swap! (h/db*) update :models #(merge {"openai/gpt-5.2" {:tools true}} %))
      (f.chat/prompt params (h/db*) (h/messenger) (h/config) (h/metrics)))
    @sent*))

(defn ^:private memory-blocks [message]
  (filterv #(= :eca/memory (:module %)) (:content message)))

(defn ^:private memory-block [message]
  (first (memory-blocks message)))

(deftest blocks-in-a-chat-test
  (h/with-memory-root
    (fn [root]
      (write-note! (fs/file root "global" "a.md") "Cache")
      (h/config! (assoc on :env "test"))
      (let [first-message (prompt! {:message "first" :chat-id "chat-1"})
            block (memory-block first-message)]
        (testing "the first request of a chat carries a full index, as the last part of the user message"
          (is (string/starts-with? (:text block) "<additionalContext>\n<memory-index description="))
          (is (= block (last (:content first-message))))
          (is (seq (get-in block [:data :notes]))))
        (testing "the stored message keeps the block as sent"
          (is (= (:content first-message)
                 (:content (first (get-in (h/db) [:chats "chat-1" :messages]))))))
        (testing "a later request with no change adds no block"
          (is (nil? (memory-block (prompt! {:message "second" :chat-id "chat-1"})))))
        (testing "a fork keeps the state with its messages"
          (let [{fork-id :chat-id} (f.chat.ops/fork-chat {:chat-id "chat-1"
                                                          :content-id (:content-id (first (get-in (h/db) [:chats "chat-1" :messages])))}
                                                         (h/db*) (h/messenger) (h/metrics))]
            (is (nil? (memory-block (prompt! {:message "forked" :chat-id fork-id}))))))
        (testing "a rollback past the block gives a full index"
          (f.chat.ops/rollback-chat {:chat-id "chat-1"
                                     :content-id (:content-id (first (get-in (h/db) [:chats "chat-1" :messages])))
                                     :include ["messages"]}
                                    (h/db*) (h/messenger) (h/metrics))
          (is (some? (memory-block (prompt! {:message "again" :chat-id "chat-1"})))))))))

(deftest write-and-reversal-in-a-chat-test
  (h/with-memory-root
    (fn [root]
      (let [global (fs/file root "global")
            note (str (fs/file global "a.md"))
            calls* (atom 0)
            requests* (atom [])]
        (write-note! note "Cache")
        (doseq [i (range 5)]
          (write-note! (fs/file global (str "filler" i ".md")) (str "Filler" i)))
        (h/config! (assoc on :env "test"))
        (with-redefs [llm-api/sync-or-async-prompt!
                      (fn [{:keys [user-messages on-first-response-received on-message-received
                                   on-prepare-tool-call on-tools-called]}]
                        (swap! requests* conj user-messages)
                        (if (= 1 (swap! calls* inc))
                          (do (on-first-response-received {:type :text :text "Saving"})
                              (on-prepare-tool-call {:id "w-1" :full-name "eca__write_file" :arguments-text "{}"})
                              (on-tools-called [{:id "w-1" :full-name "eca__write_file"
                                                 :arguments {"path" note
                                                             "content" "---\nname: Cache\ndescription: Updated\ntype: fact\n---\n\nnew body\n"}}]))
                          (do (on-first-response-received {:type :text :text "ok"})
                              (on-message-received {:type :text :text "ok"})
                              (on-message-received {:type :finish}))))
                      llm-api/sync-prompt! (constantly nil)
                      m.approval/approval-decision (constantly {:decision :allow})
                      config/await-plugins-resolved! (constantly true)]
          (swap! (h/db*) update :models #(merge {"openai/gpt-5.2" {:tools true}} %))
          (f.chat/prompt {:message "save it" :chat-id "chat-1"} (h/db*) (h/messenger) (h/config) (h/metrics))
          (testing "the stored tool output stays clean"
            (let [output (some #(when (= "tool_call_output" (:role %)) (:content %))
                               (get-in (h/db) [:chats "chat-1" :messages]))]
              (is (string/includes? (slurp note) "new body"))
              (is (= #{:error :contents} (set (keys (:output output)))))))
          (testing "an external A -> B -> A reversal back to the original index content is still reported"
            (write-note! note "Cache")
            (f.chat/prompt {:message "next" :chat-id "chat-1"} (h/db*) (h/messenger) (h/config) (h/metrics))
            (let [block (memory-block (last (last @requests*)))]
              (is (some? block))
              (is (string/includes? (:text block) "<memory-changes"))
              (is (string/includes? (:text block) "- Cache [fact]"))
              (is (string/includes? (:text block) "About Cache"))))
          (testing "once refreshed, subsequent unchanged turns stay quiet"
            (f.chat/prompt {:message "quiet" :chat-id "chat-1"} (h/db*) (h/messenger) (h/config) (h/metrics))
            (is (nil? (memory-block (last (last @requests*)))))))))))

(deftest export-import-in-a-chat-test
  (h/with-memory-root
    (fn [root]
      (write-note! (fs/file root "global" "a.md") "Cache")
      (h/config! (assoc on :env "test"))
      (is (some? (memory-block (prompt! {:message "first" :chat-id "chat-1"}))))
      (let [tmp (str (fs/path (fs/temp-dir) (str "eca-mem-export-" (random-uuid) ".edn")))
            {:keys [path error]} (#'m.chat-management/export-chat! {:db @(h/db*) :chat-id "chat-1" :filepath tmp})]
        (try
          (is (nil? error))
          (let [exported (slurp path)]
            (is (not (string/includes? exported ":eca/memory")))
            (is (not (string/includes? exported ":notes")))
            (is (not (string/includes? exported "<memory-index"))))
          (let [{:keys [chat error]} (#'m.chat-management/import-chat! {:filepath path})]
            (is (nil? error))
            (is (= "chat-1" (:id chat)) "import preserves the original chat ID")
            (testing "same-process, same-store, same-ID import gets a full index"
              (swap! (h/db*) assoc-in [:chats "chat-1"] chat)
              (let [block (memory-block (prompt! {:message "hi again" :chat-id "chat-1"}))]
                (is (some? block))
                (is (string/includes? (:text block) "<memory-index"))
                (is (string/includes? (:text block) "Cache"))))
            (testing "imported into an empty destination store: stays quiet (no phantom deleted note)"
              (h/with-memory-root
                (fn [_empty-root]
                  (swap! (h/db*) assoc-in [:chats "imported-empty"] (assoc chat :id "imported-empty"))
                  (is (nil? (memory-block (prompt! {:message "hi" :chat-id "imported-empty"})))))))
            (testing "imported into a populated destination store: gets the destination's full index"
              (h/with-memory-root
                (fn [dest-root]
                  (write-note! (fs/file dest-root "global" "dest.md") "DestNote")
                  (swap! (h/db*) assoc-in [:chats "imported-pop"] (assoc chat :id "imported-pop"))
                  (let [block (memory-block (prompt! {:message "hi" :chat-id "imported-pop"}))]
                    (is (some? block))
                    (is (string/includes? (:text block) "<memory-index"))
                    (is (string/includes? (:text block) "DestNote"))
                    (is (not (string/includes? (:text block) "(deleted)"))))))))
          (finally (fs/delete-if-exists tmp)))))))

(deftest consolidation-turn-in-a-chat-test
  (h/with-memory-root
    (fn [root]
      (write-note! (fs/file root "global" "a.md") "Cache")
      (h/config! (assoc on :env "test"))
      (let [flags* (atom [])
            flag #(get-in (h/db) [:chats "chat-1" :modules :eca/memory :consolidating?])]
        (with-redefs [llm-api/sync-or-async-prompt! (fn [{:keys [user-messages on-first-response-received on-message-received]}]
                                                      (swap! flags* conj [(flag) (some? (memory-block (last user-messages)))])
                                                      (on-first-response-received {:type :text :text "ok"})
                                                      (on-message-received {:type :text :text "ok"})
                                                      (on-message-received {:type :finish}))
                      llm-api/sync-prompt! (constantly nil)
                      f.tools/all-tools (constantly [])
                      config/await-plugins-resolved! (constantly true)]
          (swap! (h/db*) update :models #(merge {"openai/gpt-5.2" {:tools true}} %))
          (f.chat/prompt {:message "/memory-consolidate" :chat-id "chat-1"} (h/db*) (h/messenger) (h/config) (h/metrics))
          (testing "the consolidation request has its snapshot and no block, in a consolidation turn"
            (is (= [[true false]] @flags*)))
          (testing "the flag stays after the turn, until the next request"
            (is (true? (flag)))
            (f.chat/prompt {:message "thanks" :chat-id "chat-1"} (h/db*) (h/messenger) (h/config) (h/metrics))
            (is (= [nil true] (second @flags*)))
            (is (nil? (flag)))))))))

(defn ^:private continued-turn!
  "Prompts `message` in chat-1 with a mocked LLM whose first request is
   continued in the same turn. `continuation` is `:retry` (the request fails
   before any output, so ECA sends it again), `:continue` (the answer breaks
   off, so ECA asks the model to go on) or `:follow-up` (a module asks for one
   more request). `during-first` runs inside the first request. Returns one
   `[consolidation-flag last-user-message]` per model request."
  [continuation message during-first]
  (when (= :follow-up continuation)
    (let [follow-ups* (atom 0)]
      (modules/add-external! "/tmp/acme-follow-up.clj"
                             {:id :acme/follow-up
                              :event-handlers {:chat/after-request
                                               (fn [_ _]
                                                 (when (= 1 (swap! follow-ups* inc))
                                                   {:follow-up "Check it once more."}))}})))
  (let [requests* (atom [])
        flag #(get-in (h/db) [:chats "chat-1" :modules :eca/memory :consolidating?])
        overloaded {:code "server_is_overloaded"
                    :message "Our servers are currently overloaded. Please try again later."
                    :error/source :openai-responses
                    :error/api-type :overloaded}]
    (with-redefs [llm-api/sync-or-async-prompt!
                  (fn [{:keys [user-messages on-error on-first-response-received on-message-received]}]
                    (let [first? (= 1 (count (swap! requests* conj [(flag) (last user-messages)])))]
                      (when first? (during-first))
                      (cond
                        (and first? (= :retry continuation))
                        (on-error overloaded)

                        (and first? (= :continue continuation))
                        (do (on-first-response-received {:type :text :text "Part"})
                            (on-message-received {:type :text :text "Part"})
                            (on-error overloaded))

                        :else
                        (do (on-first-response-received {:type :text :text "Done"})
                            (on-message-received {:type :text :text "Done"})
                            (on-message-received {:type :finish})))))
                  llm-api/sync-prompt! (constantly nil)
                  f.tools/all-tools (constantly [])
                  config/await-plugins-resolved! (constantly true)]
      (swap! (h/db*) update :models #(merge {"openai/gpt-5.2" {:tools true}} %))
      (f.chat/prompt {:message message :chat-id "chat-1"} (h/db*) (h/messenger) (h/config) (h/metrics)))
    @requests*))

(deftest consolidation-flag-survives-continuations-test
  (doseq [continuation [:retry :continue :follow-up]]
    (testing (name continuation)
      (h/reset-components!)
      (h/with-memory-root
        (fn [_root]
          (h/config! (assoc on :env "test"))
          (is (= [true true] (mapv first (continued-turn! continuation "/memory-consolidate" (constantly nil))))
              "the second model request stays in the consolidation turn")
          (is (true? (get-in (h/db) [:chats "chat-1" :modules :eca/memory :consolidating?]))))))))

(deftest continuations-get-no-memory-block-test
  (doseq [continuation [:retry :continue :follow-up]]
    (testing (name continuation)
      (h/reset-components!)
      (h/with-memory-root
        (fn [root]
          (write-note! (fs/file root "global" "a.md") "Cache")
          (h/config! (assoc on :env "test"))
          (let [[[_ first-message] [_ continued] :as requests]
                (continued-turn! continuation "hello"
                                 #(write-note! (fs/file root "global" "b.md") "Build"))]
            (is (= 2 (count requests)))
            (testing "the first request carries one block"
              (is (= 1 (count (memory-blocks first-message)))))
            (testing "a note added during the first request gets no block in the same turn"
              (if (= :retry continuation)
                (is (= first-message continued) "the retry sends the first request again, as it was")
                (is (= [] (memory-blocks continued)))))
            (testing "the next user request reports it"
              (is (string/includes? (str (:text (memory-block (prompt! {:message "next" :chat-id "chat-1"}))))
                                    "<memory-changes")))))))))
