(ns eca.features.memory.delivery-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.memory :as memory]
   [eca.features.memory.delivery :as delivery]
   [eca.features.memory.render :as render]
   [eca.shared :as shared]
   [eca.test-helper :as h]))

(set! *warn-on-reflection* true)

(def ^:private db
  {:workspace-folders [{:uri (h/file-uri "file:///repo/one") :name "one"}]})

(def ^:private config
  {:memory {:enabled true
            :writeMode "agent"
            :index {:maxEntries 100 :maxTokens 2000}}})

(defn ^:private with-memory-root [f]
  (let [root (fs/create-temp-dir {:prefix "eca-memory-delivery-test"})]
    (with-redefs [memory/memory-root-dir (constantly (fs/file root))]
      (try
        (f (fs/file root))
        (finally
          (fs/delete-tree root))))))

(defn ^:private memory-dirs [root]
  (with-redefs [memory/memory-root-dir (constantly root)]
    (into {} (map (juxt :tier :dir)) (memory/memory-dirs db))))

(defn ^:private write-note!
  "Writes a valid memory file. Every edit changes the body size, so the parse
   cache always notices it."
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
  ([root messages config]
   (with-redefs [memory/memory-root-dir (constantly root)]
     (delivery/turn-block {:chat-id "chat-1" :db db :config config
                           :metrics (h/metrics) :window messages}))))

(defn ^:private block-text [result]
  (get-in result [:block :text]))

(defn ^:private state [result]
  (get-in result [:block :memory]))

(defn ^:private window-with [state & extra]
  (vec (cons {:role "user" :content [{:type :text :text "hi"}] :memory state} extra)))

(defn ^:private tool-output
  ([name arguments] (tool-output name arguments nil))
  ([name arguments output-extra]
   {:role "tool_call_output"
    :content (merge {:server "eca"
                     :name name
                     :arguments arguments
                     :output {:contents []}}
                    output-extra)}))

(deftest first-prompt-gets-a-full-index-test
  (with-memory-root
    (fn [root]
      (let [dirs (memory-dirs root)]
        (write-note! (str (fs/file (get dirs :global) "a.md")) "Cache" "Cache notes" "body")
        (write-note! (str (fs/file (get dirs :personal) "b.md")) "Build" "Build notes" "body")
        (let [result (turn root [])]
          (testing "the first prompt of a window carries the whole index"
            (is (string/starts-with? (block-text result) "<memory-index description="))
            (is (string/includes? (block-text result) "- Cache [fact]"))
            (is (string/includes? (block-text result) "- Build [fact]")))
          (testing "the block carries the full current state"
            (is (= #{(str (fs/file (get dirs :global) "a.md"))
                     (str (fs/file (get dirs :personal) "b.md"))}
                   (set (keys (state result)))))
            (is (= "Cache" (get-in (state result) [(str (fs/file (get dirs :global) "a.md")) :name])))
            (is (= "global" (get-in (state result) [(str (fs/file (get dirs :global) "a.md")) :label])))))))))

(deftest empty-store-then-first-note-test
  (with-memory-root
    (fn [root]
      (let [dirs (memory-dirs root)]
        (testing "an empty store sends nothing"
          (let [result (turn root [])]
            (is (nil? (:block result)))
            (is (nil? (:render result)))
            (is (empty? (:entries result)))))
        (testing "after the first note the next prompt gets a full index"
          (write-note! (str (fs/file (get dirs :global) "a.md")) "Cache" "Cache notes" "body")
          (let [result (turn root [])]
            (is (string/starts-with? (block-text result) "<memory-index description="))))))))

(deftest no-change-sends-nothing-test
  (with-memory-root
    (fn [root]
      (let [dirs (memory-dirs root)]
        (write-note! (str (fs/file (get dirs :global) "a.md")) "Cache" "Cache notes" "body")
        (let [first-result (turn root [])]
          (is (some? (:block first-result)))
          (is (nil? (:block (turn root (window-with (state first-result))))))
          (testing "a second walk over the same store still sends nothing"
            (is (nil? (:block (turn root (window-with (state first-result))))))))))))

(deftest change-list-for-new-changed-and-deleted-test
  (with-memory-root
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
        (testing "a deleted note is listed with its name"
          (fs/delete (fs/file b))
          (let [result (turn root (window-with @baseline*))]
            (is (string/includes? (block-text result) "- Build (deleted)"))
            (is (= "Build" (get-in @baseline* [b :name])) "the old state remembers the name")
            (is (not (contains? (state result) b))
                "the new state has no entry for the deleted note")))))))

(deftest retag-is-not-a-change-test
  (with-memory-root
    (fn [root]
      (let [dirs (memory-dirs root)
            a (str (fs/file (get dirs :global) "a.md"))]
        (write-note! a "Cache" "Cache notes" "body" :tags ["alpha"])
        (let [first-state (state (turn root []))]
          (testing "a tag-only rewrite sends no block"
            (write-note! a "Cache" "Cache notes" "body" :tags ["beta-gamma"])
            (is (nil? (:block (turn root (window-with first-state))))))
          (testing "a body rewrite is a change"
            (write-note! a "Cache" "Cache notes" "body with more" :tags ["beta-gamma"])
            (is (string/starts-with? (block-text (turn root (window-with first-state)))
                                     "<memory-changes description="))))))))

(defn ^:private fresh-write-store
  "Root with a note `a`, an empty `b` and filler notes, so that a one-line
   change list is estimated smaller than the full index. Calls
   `(f root dirs a b first-state)`."
  [f]
  (with-memory-root
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

(deftest own-write-tools-send-nothing-test
  (testing "a successful write_file of a memory note"
    (fresh-write-store
     (fn [root _dirs a _b first-state]
       (write-note! a "Cache" "Cache notes" "body written by the chat")
       (is (nil? (:block (turn root (window-with first-state
                                                 (tool-output "write_file" {"path" a})))))))))
  (testing "a successful edit_file of a memory note"
    (fresh-write-store
     (fn [root _dirs a _b first-state]
       (write-note! a "Cache" "Cache notes" "body edited by the chat")
       (is (nil? (:block (turn root (window-with first-state
                                                 (tool-output "edit_file" {"path" a})))))))))
  (testing "a move counts both ends as own writes"
    (fresh-write-store
     (fn [root _dirs a b first-state]
       (fs/move a b)
       (let [result (turn root (window-with
                                first-state
                                (tool-output "move_file" {"source" a "destination" b})))]
         (is (nil? (:block result))
             "no block: the chat caused both the deletion and the new note"))))))

(deftest not-own-writes-are-reported-test
  (testing "a failed write is not own"
    (fresh-write-store
     (fn [root _dirs a _b first-state]
       (write-note! a "Cache" "Cache notes" "body after a failed write")
       (let [result (turn root (window-with
                                first-state
                                (assoc-in (tool-output "write_file" {"path" a})
                                          [:content :error] true)))]
         (is (string/starts-with? (block-text result) "<memory-changes description="))
         (is (string/includes? (block-text result) "- Cache [fact]"))))))
  (testing "a rejected write is not own"
    (fresh-write-store
     (fn [root _dirs a _b first-state]
       (write-note! a "Cache" "Cache notes" "body after a rejected write")
       (let [result (turn root (window-with
                                first-state
                                (tool-output "write_file" {"path" a}
                                             {:output {:error true :message "rejected"}})))]
         (is (string/starts-with? (block-text result) "<memory-changes description="))))))
  (testing "preview_file_change does not write, so it is not own"
    (fresh-write-store
     (fn [root _dirs a _b first-state]
       (write-note! a "Cache" "Cache notes" "body previewed by the chat")
       (let [result (turn root (window-with
                                first-state
                                (tool-output "preview_file_change" {"path" a})))]
         (is (string/starts-with? (block-text result) "<memory-changes description=")))))))

(deftest own-and-external-changes-in-one-period-test
  (fresh-write-store
   (fn [root _dirs a b first-state]
     (write-note! a "Cache" "Cache notes" "body own write again")
     (write-note! b "Deploy" "Deploy notes" "body external change")
     (let [result (turn root (window-with first-state
                                          (tool-output "write_file" {"path" a})))]
       (is (string/includes? (block-text result) "- Deploy [fact]"))
       (is (not (string/includes? (block-text result) "- Cache [fact]"))
           "the chat already knows about its own write")))))

(deftest external-change-to-a-hidden-note-is-listed-test
  (with-memory-root
    (fn [root]
      (let [dirs (memory-dirs root)
            global (get dirs :global)
            a (str (fs/file global "a.md"))
            b (str (fs/file global "b.md"))
            limited (assoc-in config [:memory :index :maxEntries] 1)]
        (write-note! a "Cache" "Cache notes" "body")
        (write-note! b "Hidden" "Hidden notes" "body")
        (let [first-result (turn root [] limited)]
          (is (= 1 (:item-count (:render first-result))) "the index caps hide one note")
          (is (= 2 (count (state first-result))) "the state map holds both notes")
          (write-note! b "Hidden" "Hidden notes" "body changed externally")
          (let [result (turn root (window-with (state first-result)) limited)]
            (is (string/includes? (block-text result) "- Hidden [fact]")
                "changes of hidden notes are reported too")))))))

(deftest smaller-block-wins-test
  (with-memory-root
    (fn [root]
      (let [dirs (memory-dirs root)
            global (get dirs :global)
            limited (assoc-in config [:memory :index :maxEntries] 2)
            files (mapv #(str (fs/file global (str "n" % ".md"))) (range 12))]
        (doseq [i (range 12)]
          (write-note! (files i) (str "Note" i) (str "About topic " i) "body"))
        (let [first-state (state (turn root [] limited))]
          (doseq [i (range 12)]
            (write-note! (files i) (str "Note" i) (str "About topic " i) "body changed"))
          (let [result (turn root (window-with first-state) limited)]
            (is (string/starts-with? (block-text result) "<memory-index description=")
                "the capped index is smaller than the twelve-line change list")))))))

(deftest smaller-change-list-wins-test
  (fresh-write-store
   (fn [root _dirs a _b first-state]
     (write-note! a "Cache" "Cache notes" "body changed")
     (let [result (turn root (window-with first-state))]
       (is (string/starts-with? (block-text result) "<memory-changes description=")
           "one change line is much smaller than the full index")
       (is (string/includes? (block-text result) "- Cache [fact]"))))))

(deftest tie-sends-the-full-index-test
  (let [entries [{:path "/m/a.md" :name "A" :type "fact" :label "global"
                  :description "d" :updated-at-ms 1 :tags []}]
        changes (render/changes-context [{:label "global" :name "A" :entry (first entries)}])
        change-tokens (shared/estimate-tokens changes)]
    (testing "a tie goes to the full index, which is never stale"
      (is (= {:text "<memory-index>fake</memory-index>" :kind "full"}
             (#'delivery/choose-block entries #{"/m/a.md"} {}
                                      {:content "<memory-index>fake</memory-index>"
                                       :tokens change-tokens}))))
    (testing "a smaller full index wins over the change list"
      (is (= "full"
             (:kind (#'delivery/choose-block entries #{"/m/a.md"} {}
                                              {:content "<memory-index>fake</memory-index>"
                                               :tokens 1})))))
    (testing "a smaller change list wins over the full index"
      (is (= "changes"
             (:kind (#'delivery/choose-block entries #{"/m/a.md"} {}
                                              {:content "<memory-index>fake</memory-index>"
                                               :tokens (inc change-tokens)})))))))

(deftest fail-open-test
  (with-memory-root
    (fn [root]
      (with-redefs [memory/scan-memories (fn [& _] (throw (ex-info "boom" {})))]
        (is (nil? (turn root [])))))))

(deftest memory-disabled-sends-nothing-test
  (with-memory-root
    (fn [root]
      (let [dirs (memory-dirs root)]
        (write-note! (str (fs/file (get dirs :global) "a.md")) "Cache" "Cache notes" "body")
        (is (nil? (turn root [] (assoc config :memory {:enabled false}))))))))

(deftest subagent-sends-nothing-test
  (with-memory-root
    (fn [root]
      (let [dirs (memory-dirs root)]
        (write-note! (str (fs/file (get dirs :global) "a.md")) "Cache" "Cache notes" "body")
        (with-redefs [memory/memory-root-dir (constantly root)]
          (is (nil? (delivery/turn-block {:chat-id "sub"
                                          :db (assoc-in db [:chats "sub" :subagent] true)
                                          :config config
                                          :metrics (h/metrics)
                                          :window []}))))))))

(deftest index-loaded-params-test
  (testing "params describe the notes a full index would show now"
    (is (= {:chat-id "chat-1"
            :count 1
            :total-count 3
            :items [{:name "Cache" :type "fact" :source "global" :path "/m/a.md"}]}
           (delivery/index-loaded-params
            "chat-1"
            [{:path "/m/a.md"} {:path "/m/b.md"} {:path "/m/c.md"}]
            {:item-count 1
             :items [{:name "Cache" :type "fact" :source "global" :path "/m/a.md"}]}))))
  (testing "no notes means zero counts and no items"
    (is (= {:chat-id "chat-1" :count 0 :total-count 0 :items []}
           (delivery/index-loaded-params "chat-1" [] nil)))))
