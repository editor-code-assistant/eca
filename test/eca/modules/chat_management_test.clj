(ns eca.modules.chat-management-test
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [cognitect.transit :as transit]
   [eca.cache :as cache]
   [eca.db.io :as db.io]
   [eca.modules.chat-management :as m.chat-management]
   [eca.shared :as shared]
   [eca.test-helper :as h])
  (:import
   [java.io File]))

(set! *warn-on-reflection* true)

(defn ^:private write-transit! [^File f data]
  (io/make-parents f)
  (with-open [os (io/output-stream f)]
    (transit/write (transit/writer os :json) data)))

;; /chats

(deftest list-all-workspaces-chats-test
  (let [tmpdir (str (fs/create-temp-dir))]
    (with-redefs [cache/global-dir (constantly (io/file tmpdir))]
      (try
        (let [workspaces [{:uri (h/file-uri "file:///home/user/proj-a")}]
              db {:workspace-folders workspaces
                  :chats {"cur-1" {:id "cur-1" :title "Current work" :updated-at 300
                                   :messages [{:role "user" :content "hi"}]}
                          "sub-1" {:id "sub-1" :subagent true :updated-at 400 :messages []}}}]
          ;; another workspace on the current per-chat layout, with persisted paths
          (write-transit! (io/file tmpdir "projb_hash1111" "chats" "index.transit.json")
                          {:version db.io/chats-version
                           :workspaces ["/home/user/proj-b"]
                           :chats {"b-1" {:id "b-1" :title "B newest" :updated-at 200
                                          :message-count 3 :user-message-count 2 :flags []}
                                   "b-sub" {:id "b-sub" :subagent true :updated-at 500}}})
          ;; stale duplicate of b-1 in another dir (legacy duplicate dirs, #558)
          (write-transit! (io/file tmpdir "projbold_hash4444" "chats" "index.transit.json")
                          {:version db.io/chats-version
                           :chats {"b-1" {:id "b-1" :title "B stale" :updated-at 100}}})
          ;; workspace never reopened since v7: legacy whole-workspace blob only
          (write-transit! (io/file tmpdir "legacy_hash2222" "db.transit.json")
                          {:version db.io/version
                           :chats {"l-1" {:title "Legacy chat" :updated-at 150
                                          :messages [{:role "user" :content "hi"}]}}})
          ;; unreadable index is skipped without aborting the scan
          (let [corrupt (io/file tmpdir "corrupt_hash3333" "chats" "index.transit.json")]
            (io/make-parents corrupt)
            (spit corrupt "not-transit{{{"))
          (let [groups (#'m.chat-management/list-all-workspaces-chats db nil)]
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
  (let [parts @#'m.chat-management/workspace-dir-name-parts]
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
      (with-redefs [cache/global-dir (constantly (io/file tmpdir))]
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
                            {:version db.io/chats-version
                             :chats {"b-1" {:id "b-1" :title "B" :updated-at 200}}})
            (let [groups (#'m.chat-management/list-all-workspaces-chats db nil)
                  b-group (first (filter #(= ["b-1"] (mapv :id (:chats %))) groups))]
              (is (= [(str (fs/absolutize proj-b))] (:workspaces b-group))
                  "exact path recovered by matching the dir hash, not guessed")))
          (finally
            (fs/delete-tree tmpdir)
            (fs/delete-tree projects)))))))

;; /export and /import

(def ^:private chat-to-export
  {:id "chat-1"
   :title "Fix the Login Bug!"
   :title-custom? true
   :status :idle
   :created-at 1
   :updated-at 2
   :login-provider "anthropic"
   :model "anthropic/claude-sonnet-4-6"
   :variant "high"
   :last-api :anthropic
   :trust true
   :prompt-id #uuid "00000000-0000-0000-0000-000000000001"
   :user-prompt-count 2
   :subagent false
   :usage {:total-input-tokens 100 :total-output-tokens 50}
   :messages [{:role "user" :content "hello there" :content-id "c1" :created-at 1}
              {:role "assistant" :content [{:type :text :text "general kenobi"}] :content-id "c2" :created-at 2}
              {:role "tool_call" :content {:id "t1" :name "read_file" :full-name "eca__read_file"
                                           :origin :native :arguments {:path "/tmp/x"} :api :anthropic} :content-id "c3" :created-at 3}]
   :task {:next-id 2 :active-summary nil
          :tasks [{:id 1 :subject "do" :description "d" :status :done :priority :high}]}
   ;; the modules' chat state (eca.api/swap-chat-state!)
   :modules {:acme/notes {:seen 2}}
   ;; live/transient state that must NOT be exported:
   :tool-calls {"t1" {:status :completed :approved?* (promise) :future-cleanup-complete?* (promise) :future (future 1)}}
   :last-status-payload {:x 1}
   :prompt-cache {:provider :stuff}})

(def ^:private db-to-export
  {:chats {"chat-1" chat-to-export}})

(deftest build-export-test
  (let [export (#'m.chat-management/build-export db-to-export "chat-1")
        chat (:chat export)]
    (testing "envelope carries version tags"
      (is (contains? export :eca/version))
      (is (contains? export :eca/export-version)))
    (testing "resumable fields preserved verbatim (no obfuscation)"
      (is (= "chat-1" (:id chat)))
      (is (= "Fix the Login Bug!" (:title chat)))
      (is (= "anthropic/claude-sonnet-4-6" (:model chat)))
      (is (= "high" (:variant chat)))
      (is (= :anthropic (:last-api chat)))
      (is (= true (:trust chat)))
      (is (= 2 (:user-prompt-count chat)))
      (is (= {:total-input-tokens 100 :total-output-tokens 50} (:usage chat)))
      (is (= "do" (get-in chat [:task :tasks 0 :subject])))
      (is (= {:acme/notes {:seen 2}} (:modules chat)) "the modules' chat state"))
    (testing "message content kept verbatim and in order"
      (is (= 3 (count (:messages chat))))
      (is (= "hello there" (:content (first (:messages chat)))))
      (is (= ["user" "assistant" "tool_call"] (mapv :role (:messages chat))))
      (is (= "/tmp/x" (get-in (nth (:messages chat) 2) [:content :arguments :path]))))
    (testing "live/transient state excluded"
      (is (not (contains? chat :tool-calls)))
      (is (not (contains? chat :last-status-payload)))
      (is (not (contains? chat :prompt-cache))))))

(deftest build-export-without-module-contexts-test
  (testing "the contexts modules added are left out: the modules of the ECA that imports the chat add their own"
    (let [hook-context {:type :text :text "<additionalContext>\nA hook's.\n</additionalContext>"}
          db {:chats {"chat-1" {:id "chat-1"
                                :messages [{:role "user" :content [{:type :text :text "hi"}
                                                                   {:type :text :text "<additionalContext>\nNotes.\n</additionalContext>"
                                                                    :module :acme/notes :data {:v 1}}
                                                                   hook-context]}
                                           {:role "tool_call_output"
                                            :content {:id "t1" :output {:error false
                                                                        :contents [{:type :text :text "ok"}
                                                                                   {:type :text :text "Lint." :module :acme/lint}]}}}]}}}]
      (is (= [{:role "user" :content [{:type :text :text "hi"} hook-context]}
              {:role "tool_call_output" :content {:id "t1" :output {:error false :contents [{:type :text :text "ok"}]}}}]
             (get-in (#'m.chat-management/build-export db "chat-1") [:chat :messages])))))
  (testing "import-chat! also strips module contexts from hand-crafted exports"
    (let [tmp (str (fs/path (fs/temp-dir) (str "eca-import-sanitize-" (random-uuid) ".edn")))
          raw {:eca/export-version 1
               :chat {:id "chat-1"
                      :messages [{:role "user" :content [{:type :text :text "hi"}
                                                         {:type :text :text "ctx" :module :acme/notes :data {:v 1}}]}
                                 {:role "tool_call_output"
                                  :content {:id "t1" :output {:error false
                                                              :contents [{:type :text :text "ok"}
                                                                         {:type :text :text "Lint." :module :acme/lint}]}}}]}}]
      (try
        (spit tmp (pr-str raw))
        (let [{:keys [chat error]} (#'m.chat-management/import-chat! {:filepath tmp})]
          (is (nil? error))
          (is (= [{:role "user" :content [{:type :text :text "hi"}]}
                  {:role "tool_call_output" :content {:id "t1" :output {:error false :contents [{:type :text :text "ok"}]}}}]
                 (:messages chat))))
        (finally (fs/delete-if-exists tmp))))))

(deftest export->edn-round-trips-test
  (testing "the export serializes and reads back equal (incl. #uuid prompt-id)"
    (let [export (#'m.chat-management/build-export db-to-export "chat-1")
          read-back (edn/read-string (#'m.chat-management/export->edn export))]
      (is (= export read-back))
      (is (= #uuid "00000000-0000-0000-0000-000000000001" (get-in read-back [:chat :prompt-id]))))))

(deftest export-import-round-trip-test
  (testing "export-chat! then import-chat! preserves the chat"
    (let [tmp (str (fs/path (fs/temp-dir) (str "eca-export-test-" (random-uuid) ".edn")))
          {:keys [path message-count error]} (#'m.chat-management/export-chat! {:db db-to-export :chat-id "chat-1" :filepath tmp})]
      (is (nil? error))
      (is (= 3 message-count))
      (is (fs/exists? path))
      (let [{:keys [chat error]} (#'m.chat-management/import-chat! {:filepath path})]
        (is (nil? error))
        (is (= "chat-1" (:id chat)))
        (is (= "anthropic/claude-sonnet-4-6" (:model chat)))
        (is (= "high" (:variant chat)))
        (is (= {:total-input-tokens 100 :total-output-tokens 50} (:usage chat)))
        (is (= (get-in (#'m.chat-management/build-export db-to-export "chat-1") [:chat :messages]) (:messages chat))))
      (fs/delete-if-exists path))))

(deftest export-chat!-defaults-and-errors-test
  (testing "blank filepath falls back to a slugified temp path"
    (let [{:keys [path error]} (#'m.chat-management/export-chat! {:db db-to-export :chat-id "chat-1" :filepath ""})]
      (is (nil? error))
      (is (fs/exists? path))
      (is (string/includes? path "eca-chat-fix-the-login-bug"))
      (fs/delete-if-exists path)))
  (testing "missing chat returns an error"
    (is (:error (#'m.chat-management/export-chat! {:db db-to-export :chat-id "nope" :filepath "/tmp/x.edn"})))))

(deftest export-chat!-directory-target-test
  (testing "an existing directory writes the default filename inside it"
    (let [dir (str (fs/path (fs/temp-dir) (str "eca-export-dir-" (random-uuid))))]
      (fs/create-dirs dir)
      (let [{:keys [path error]} (#'m.chat-management/export-chat! {:db db-to-export :chat-id "chat-1" :filepath dir})]
        (is (nil? error))
        (is (= "eca-chat-fix-the-login-bug.edn" (fs/file-name path)))
        (is (string/starts-with? path dir))
        (is (fs/exists? path)))
      (fs/delete-tree dir)))
  (testing "a trailing-separator path creates the directory and writes inside it"
    (let [dir (str (fs/path (fs/temp-dir) (str "eca-export-newdir-" (random-uuid))))
          {:keys [path error]} (#'m.chat-management/export-chat! {:db db-to-export :chat-id "chat-1" :filepath (str dir "/")})]
      (is (nil? error))
      (is (= "eca-chat-fix-the-login-bug.edn" (fs/file-name path)))
      (is (string/starts-with? path dir))
      (is (fs/exists? path))
      (fs/delete-tree dir))))

(deftest import-chat!-errors-test
  (testing "blank filepath errors"
    (is (:error (#'m.chat-management/import-chat! {:filepath ""}))))
  (testing "non-existent file errors"
    (is (:error (#'m.chat-management/import-chat! {:filepath (str (fs/path (fs/temp-dir) (str "nope-" (random-uuid) ".edn")))}))))
  (testing "content that is not an ECA export errors"
    (let [tmp (str (fs/path (fs/temp-dir) (str "eca-bad-" (random-uuid) ".edn")))]
      (spit tmp (pr-str {:not "an export"}))
      (is (:error (#'m.chat-management/import-chat! {:filepath tmp})))
      (fs/delete-if-exists tmp))))

;; /debug-chat

(def ^:private chat-to-dump
  {:id "chat-1"
   :title "Fix the Login Bug!"
   :status :idle
   :model "anthropic/claude-sonnet-4-6"
   :variant "high"
   :last-api :anthropic
   :trust false
   :user-prompt-count 2
   :messages [{:role "user" :content "secret user text here" :created-at 1}
              {:role "assistant" :content [{:type :text :text "secret assistant reply"}] :created-at 2}
              {:role "reason" :content {:id "r1" :external-id "sig-secret-aBcDeF0123456789aBcDeF0123456789"
                                        :text "secret chain of thought" :total-time-ms 10 :api :anthropic} :created-at 3}
              {:role "tool_call" :content {:id "t1" :name "read_file" :full-name "eca__read_file"
                                           :origin :native :arguments {:path "/etc/passwd"} :api :anthropic} :created-at 4}
              {:role "tool_call_output" :content {:id "t1" :name "read_file" :full-name "eca__read_file"
                                                  :origin :native :error false
                                                  :arguments {:path "/etc/passwd"}
                                                  :output {:error false :contents [{:type :text :text "root:x:0:0:secret"}]}
                                                  :api :anthropic} :created-at 5}
              {:role "user" :content "secret user text here" :created-at 6}]
   :tool-calls {"t1" {:status :completed :name "read_file" :full-name "eca__read_file"
                      :origin :native :start-time 100
                      :approved?* (promise) :future-cleanup-complete?* (promise) :future (future 1)
                      :arguments {:path "/etc/passwd"}}}})

(def ^:private db-to-dump
  {:chats {"chat-1" chat-to-dump}
   :models {"anthropic/claude-sonnet-4-6" {:reason? true :tools true :max-output-tokens 8192
                                           :limit {:context 200000 :output 8192}}}
   :mcp-clients {"my-server" {:status :running :tools [{:name "do-thing" :description "x" :parameters {}}]}}
   :auth {"anthropic" {:type :auth/oauth :access-token "SECRET-TOKEN" :refresh-token "SECRET-REFRESH" :expires-at 999}
          "openai" {}}})

(def ^:private tools-to-dump
  [{:name "read_file" :full-name "eca__read_file" :origin :native :parameters {:big "schema"}}
   {:name "do-thing" :full-name "my-server__do-thing" :origin :mcp :server "my-server"}])

(deftest obfuscate-str-test
  (testing "same input reuses a label, different inputs differ"
    (let [obf (#'m.chat-management/make-obfuscator)
          a1 (#'m.chat-management/obfuscate-str obf "hello")
          b (#'m.chat-management/obfuscate-str obf "world")
          a2 (#'m.chat-management/obfuscate-str obf "hello")]
      (is (= a1 a2))
      (is (not= a1 b))
      (is (string/starts-with? a1 "A"))
      (is (string/starts-with? b "B"))))
  (testing "longer strings produce longer (bucketed) tokens"
    (let [obf (#'m.chat-management/make-obfuscator)
          short-s (#'m.chat-management/obfuscate-str obf "ab")
          long-s (#'m.chat-management/obfuscate-str obf (apply str (repeat 5000 "x")))]
      (is (< (count short-s) (count long-s)))))
  (testing "blank strings pass through untouched"
    (is (= "" (#'m.chat-management/obfuscate-str (#'m.chat-management/make-obfuscator) "")))))

(deftest build-dump-test
  (let [dump (#'m.chat-management/build-dump db-to-dump "chat-1" tools-to-dump)
        chat (:chat dump)
        msgs (:messages chat)]
    (testing "send-affecting metadata preserved verbatim"
      (is (= "anthropic/claude-sonnet-4-6" (:model chat)))
      (is (= "high" (:variant chat)))
      (is (= :idle (:status chat)))
      (is (= :anthropic (:last-api chat)))
      (is (= 2 (:user-prompt-count chat)))
      (is (= 6 (:message-count chat))))
    (testing "message roles and order preserved"
      (is (= ["user" "assistant" "reason" "tool_call" "tool_call_output" "user"]
             (mapv :role msgs))))
    (testing "tool name/id kept, arguments obfuscated but keys kept"
      (let [tc (:content (nth msgs 3))]
        (is (= "read_file" (:name tc)))
        (is (= "eca__read_file" (:full-name tc)))
        (is (= :native (:origin tc)))
        (is (contains? (:arguments tc) :path))
        (is (not= "/etc/passwd" (get-in tc [:arguments :path])))))
    (testing "reasoning internal id kept but external-id obfuscated"
      (let [r (:content (nth msgs 2))]
        (is (= "r1" (:id r)))
        (is (some? (:external-id r)))
        (is (not= "sig-secret-aBcDeF0123456789aBcDeF0123456789" (:external-id r)))))
    (testing "tool output text obfuscated but error/shape kept"
      (let [out (get-in (nth msgs 4) [:content :output])]
        (is (= false (:error out)))
        (is (not= "root:x:0:0:secret" (-> out :contents first :text)))))
    (testing "duplicate user text shares the same label"
      (is (= (:content (first msgs)) (:content (last msgs)))))
    (testing "no original secret text leaks into the EDN"
      (is (not (string/includes? (#'m.chat-management/dump->edn dump) "secret"))))
    (testing "title is obfuscated in the content"
      (is (not= "Fix the Login Bug!" (:title chat))))
    (testing "live tool-call promises/futures/arguments stripped"
      (let [tc (get-in chat [:tool-calls "t1"])]
        (is (= :completed (:status tc)))
        (is (= "read_file" (:name tc)))
        (is (not (contains? tc :approved?*)))
        (is (not (contains? tc :future-cleanup-complete?*)))
        (is (not (contains? tc :future)))
        (is (not (contains? tc :arguments)))))
    (testing "auth summary exposes presence/expiry but no secrets"
      (let [auth (:auth-summary dump)]
        (is (= #{"anthropic"} (set (keys auth))))
        (is (= :auth/oauth (get-in auth ["anthropic" :type])))
        (is (= 999 (get-in auth ["anthropic" :expires-at])))
        (is (not (string/includes? (pr-str auth) "SECRET")))))
    (testing "available tools listed by name without schemas"
      (is (= #{"read_file" "do-thing"} (set (map :name (:available-tools dump)))))
      (is (not-any? :parameters (:available-tools dump))))
    (testing "model capabilities preserved verbatim"
      (is (= true (get-in dump [:model-capabilities :reason?]))))
    (testing "the dump round-trips as EDN"
      (is (map? (edn/read-string (#'m.chat-management/dump->edn dump)))))))

(deftest default-dump-filepath-test
  (testing "slugifies the title"
    (is (string/includes? (#'m.chat-management/default-dump-filepath {:title "Fix the Login Bug!" :id "abc"})
                          "eca-chat-debug-fix-the-login-bug")))
  (testing "falls back to chat id when there is no title"
    (is (string/includes? (#'m.chat-management/default-dump-filepath {:id "abc-123"})
                          "eca-chat-debug-abc-123")))
  (testing "uses the .edn extension"
    (is (string/ends-with? (#'m.chat-management/default-dump-filepath {:id "x"}) ".edn"))))

(deftest dump-chat!-test
  (testing "writes an EDN file and reports the message count"
    (let [tmp (str (fs/path (fs/temp-dir) (str "eca-debug-test-" (random-uuid) ".edn")))
          {:keys [path message-count error]} (#'m.chat-management/dump-chat! {:db db-to-dump :chat-id "chat-1"
                                                                              :all-tools tools-to-dump :filepath tmp})]
      (is (nil? error))
      (is (= 6 message-count))
      (is (fs/exists? path))
      (is (map? (edn/read-string (slurp path))))
      (fs/delete-if-exists path)))
  (testing "missing chat returns an error"
    (is (:error (#'m.chat-management/dump-chat! {:db db-to-dump :chat-id "nope" :all-tools []})))))
