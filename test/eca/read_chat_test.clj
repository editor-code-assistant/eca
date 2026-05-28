(ns eca.read-chat-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer [are deftest is testing]]

   [babashka.fs :as fs]
   [cheshire.core :as cheshire]
   [cognitect.transit :as transit]

   [eca.cache :as cache]
   [eca.db :as db]
   [eca.main :as main]
   [eca.read-chat :as read-chat]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn- instant->ms [^String s]
  (.toEpochMilli (java.time.Instant/parse s)))

(def jan-1-ms (instant->ms "2024-01-01T00:00:00Z"))
(def jan-2-ms (instant->ms "2024-01-02T00:00:00Z"))
(def feb-1-ms (instant->ms "2024-02-01T00:00:00Z"))
(def feb-2-ms (instant->ms "2024-02-02T00:00:00Z"))

(def sample-db
  {:version db/version
   :chats {"chat-1" {:id "chat-1"
                     :title "Test Chat 1"
                     :status :idle
                     :model "anthropic/claude-sonnet-4-6"
                     :created-at jan-1-ms
                     :updated-at jan-2-ms
                     :user-prompt-count 3
                     :messages [{:role "user" :content "Hello" :content-id "m1" :created-at jan-1-ms}
                                {:role "assistant" :content "Hi there" :content-id "m2" :created-at (inc jan-1-ms)}
                                {:role "user" :content "How are you?" :content-id "m3" :created-at jan-2-ms}
                                {:role "assistant" :content "I'm fine" :content-id "m4" :created-at (inc jan-2-ms)}
                                {:role "tool_call" :content "some tool" :content-id "m5" :created-at (+ 2 jan-2-ms)}]}
           "chat-2" {:id "chat-2"
                     :title "Test Chat 2"
                     :status :running
                     :model "openai/gpt-5"
                     :created-at feb-1-ms
                     :updated-at feb-2-ms
                     :user-prompt-count 1
                     :messages [{:role "user" :content "Question" :content-id "m6" :created-at feb-1-ms}]}
           "chat-3" {:id "chat-3"
                     :title "Multimodal Chat"
                     :status :idle
                     :model "anthropic/claude-sonnet-4-6"
                     :created-at jan-1-ms
                     :updated-at jan-2-ms
                     :user-prompt-count 2
                     :messages [{:role "user"
                                 :content [{:type :text :text "What is in this image?"}
                                           {:type :image :base64 "iVBOR..." :media-type "image/png"}]
                                 :content-id "m7"
                                 :created-at jan-1-ms}
                                {:role "assistant"
                                 :content [{:type :text :text "That's a diagram."}]
                                 :content-id "m8"
                                 :created-at jan-2-ms}
                                {:role "user"
                                 :content [{:type :text :text "First line"}
                                           {:type :text :text "Second line"}]
                                 :content-id "m9"
                                 :created-at (inc jan-2-ms)}
                                {:role "tool_call"
                                 :content {:name "Read" :arguments {:path "/foo/bar"} :id "tc-1"}
                                 :content-id "m10"
                                 :created-at (+ 2 jan-2-ms)}]}}})

(defn- write-transit-file [path data]
  (with-open [os (io/output-stream path)]
    (transit/write (transit/writer os :json) data)))

(defn- with-tmp-transit-file [data f]
  (let [tmp (fs/create-temp-file {:prefix "eca-test" :suffix ".transit.json"})]
    (try
      (write-transit-file (str tmp) data)
      (f (str tmp))
      (finally
        (fs/delete tmp)))))

(defn- parse-jsonl [s]
  (->> (string/split-lines s)
       (remove string/blank?)
       (mapv #(cheshire/parse-string % keyword))))

(defn- workspace-db-path [paths]
  (str (cache/workspace-cache-file
        (mapv (fn [path] {:uri (shared/filename->uri path)}) paths)
        "db.transit.json"
        shared/uri->filename)))

(defn- listing-ids [opts]
  (set (map :id (read-chat/list-chats sample-db opts))))

(defn- message-ids [chat-id opts]
  (mapv :content-id (:messages (read-chat/chat-messages sample-db chat-id opts))))

;; read-db tests

(deftest read-db-valid-file-test
  (testing "reads a valid transit file"
    (with-tmp-transit-file sample-db
      (fn [path]
        (let [db (read-chat/read-db path)]
          (is (= 6 (:version db)))
          (is (= 3 (count (:chats db)))))))))

(deftest read-db-missing-file-test
  (testing "throws ex-info for missing file"
    (is (thrown? clojure.lang.ExceptionInfo
                 (read-chat/read-db "/nonexistent/path/db.transit.json")))))

(deftest read-db-corrupted-file-test
  (testing "throws ex-info for corrupted transit file"
    (let [tmp (fs/create-temp-file {:prefix "eca-test" :suffix ".transit.json"})]
      (try
        (spit (str tmp) "this is not transit data!!!")
        (is (thrown? clojure.lang.ExceptionInfo
                     (read-chat/read-db (str tmp))))
        (finally
          (fs/delete tmp))))))

;; list-chats tests

(deftest list-chats-no-filter-test
  (testing "lists all chat summaries without message bodies"
    (let [result (vec (read-chat/list-chats sample-db {}))]
      (is (= #{"chat-1" "chat-2" "chat-3"} (set (map :id result))))
      (is (every? #(every? % [:id :title :status :model :created-at :updated-at :user-prompt-count]) result))
      (is (not-any? #(contains? % :messages) result)))))

(deftest list-chats-uses-db-key-as-id-test
  (testing "listing emits the DB key as the id that --chat-id can use"
    (let [db {:chats {"db-key" {:id "stale-stored-id"
                                :title "Mismatched id chat"
                                :status :idle
                                :model "test/model"
                                :created-at jan-1-ms
                                :updated-at jan-2-ms
                                :user-prompt-count 1
                                :messages []}}}
          result (vec (read-chat/list-chats db {}))]
      (is (= "db-key" (:id (first result)))))))

(deftest chat-messages-uses-db-key-as-chat-id-test
  (testing "detail mode uses the same DB key emitted by listing mode"
    (let [db {:chats {"db-key" {:id "stale-stored-id"
                                :messages [{:role "user"
                                            :content "Hello"
                                            :content-id "m1"
                                            :created-at jan-1-ms}]}}}
          {:keys [messages]} (read-chat/chat-messages db "db-key" {})]
      (is (= ["Hello"] (mapv :content messages)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (read-chat/chat-messages db "stale-stored-id" {}))))))

(deftest list-chats-sorted-by-updated-at-desc-test
  (testing "chats are sorted by :updated-at descending"
    (let [result (vec (read-chat/list-chats sample-db {}))
          updated-ats (mapv :updated-at result)]
      (is (= updated-ats (vec (reverse (sort updated-ats)))))
      ;; chat-2 has feb-2 which is newest, so it must be first
      (is (= "chat-2" (:id (first result)))))))

(deftest list-chats-sorted-nil-updated-at-test
  (testing "chats with nil :updated-at end up last"
    (let [db {:chats {"a" {:id "a" :updated-at 100}
                      "b" {:id "b"}
                      "c" {:id "c" :updated-at 200}}}
          result (vec (read-chat/list-chats db {}))]
      (is (= ["c" "a" "b"] (mapv :id result))))))

(deftest list-chats-time-filter-test
  (testing "filters chats by updated-at time bounds"
    (are [opts expected-ids] (= expected-ids (listing-ids opts))
      {:since "2024-01-15"} #{"chat-2"}
      {:until "2024-01-15"} #{"chat-1" "chat-3"}
      {:since "2024-01-01" :until "2024-02-28"} #{"chat-1" "chat-2" "chat-3"}
      {:since "2024-01-02T00:00:00Z"} #{"chat-1" "chat-2" "chat-3"})))

(deftest list-chats-empty-test
  (testing "returns empty seq for empty chats"
    (let [result (vec (read-chat/list-chats {:chats {}} {}))]
      (is (= [] result)))))

(deftest list-chats-invalid-date-test
  (testing "throws ex-info for invalid date format"
    (is (thrown? clojure.lang.ExceptionInfo
                 (doall (read-chat/list-chats sample-db {:since "not-a-date"}))))))

;; relative time parsing tests

(deftest list-chats-since-relative-test
  (testing "filters chats with relative --since"
    (let [now-ms (System/currentTimeMillis)
          db (assoc sample-db :chats
                    {"old" {:id "old" :title "Old" :status :idle :model "x"
                            :created-at (- now-ms 43200000) :updated-at (- now-ms 43200000) :user-prompt-count 0}
                     "recent" {:id "recent" :title "Recent" :status :idle :model "x"
                               :created-at (- now-ms 1800000) :updated-at (- now-ms 1800000) :user-prompt-count 0}})]
      (testing "1h includes recent chat only"
        (let [result (vec (read-chat/list-chats db {:since "1h"}))]
          (is (some #(= "recent" (:id %)) result))
          (is (not (some #(= "old" (:id %)) result)))))
      (testing "1d includes both chats"
        (let [result (vec (read-chat/list-chats db {:since "1d"}))]
          (is (= 2 (count result))))))))

(deftest parse-date-relative-formats-test
  (testing "relative formats"
    (are [input delta-ms] (let [parsed-ms (#'read-chat/parse-date-ms input)
                              expected-ms (- (System/currentTimeMillis) delta-ms)]
                          (<= (Math/abs (long (- parsed-ms expected-ms))) 1000))
      "30m" (* 30 60000)
      "2h" (* 2 3600000)
      "1d" 86400000))
  (testing "invalid relative format throws"
    (is (thrown? clojure.lang.ExceptionInfo (#'read-chat/parse-date-ms "2w"))))
  (testing "absolute still works"
    (are [input] (integer? (#'read-chat/parse-date-ms input))
      "2024-01-01"
      "2024-01-01T00:00:00Z")))

;; chat-messages tests

(deftest chat-messages-no-options-test
  (testing "returns all messages for given chat-id"
    (let [{:keys [messages warnings]} (read-chat/chat-messages sample-db "chat-1" {})]
      (is (= 5 (count (vec messages))))
      (is (= [] warnings)))))

(deftest chat-messages-missing-chat-test
  (testing "throws ex-info for missing chat-id"
    (is (thrown? clojure.lang.ExceptionInfo
                 (read-chat/chat-messages sample-db "nonexistent" {})))))

(deftest chat-messages-filter-test
  (testing "filters messages by role and time bounds"
    (are [opts expected-message-ids] (= expected-message-ids (message-ids "chat-1" opts))
      {:role "user"} ["m1" "m3"]
      {:since "2024-01-02"} ["m3" "m4" "m5"]
      {:until "2024-01-02"} ["m1" "m2"]
      {:since "2024-01-01T00:00:01Z" :until "2024-01-03"} ["m3" "m4" "m5"]
      {:since "2024-01-02" :role "user"} ["m3"])))

(deftest chat-messages-since-excludes-messages-without-created-at-test
  (testing "messages without :created-at are excluded when --since is set; a warning is reported"
    (let [db-without-ts (update-in sample-db [:chats "chat-1" :messages]
                                   (fn [msgs] (mapv #(dissoc % :created-at) msgs)))
          {:keys [messages warnings]} (read-chat/chat-messages db-without-ts "chat-1" {:since "2025-01-01"})]
      (is (= 0 (count (vec messages))))
      (is (= 1 (count warnings)))
      (is (string/includes? (first warnings) "5"))
      (is (string/includes? (first warnings) "without :created-at")))))

(deftest chat-messages-no-warning-without-time-filter-test
  (testing "no warning is produced when no time filter is active"
    (let [db-without-ts (update-in sample-db [:chats "chat-1" :messages]
                                   (fn [msgs] (mapv #(dissoc % :created-at) msgs)))
          {:keys [warnings]} (read-chat/chat-messages db-without-ts "chat-1" {})]
      (is (= [] warnings)))))

;; raw content preservation tests

(deftest chat-messages-preserves-vector-content-test
  (testing "message content vectors are preserved as stored"
    (let [{:keys [messages]} (read-chat/chat-messages sample-db "chat-3" {})
          msgs (vec messages)]
      (is (= [{:type :text :text "What is in this image?"}
              {:type :image :base64 "iVBOR..." :media-type "image/png"}]
             (:content (first msgs))))
      (is (= [{:type :text :text "First line"}
              {:type :text :text "Second line"}]
             (:content (nth msgs 2)))))))

(deftest chat-messages-preserves-tool-map-content-test
  (testing "structured tool content maps are preserved as stored"
    (let [{:keys [messages]} (read-chat/chat-messages sample-db "chat-3" {})
          tool-msg (last (vec messages))]
      (is (= "tool_call" (:role tool-msg)))
      (is (= {:name "Read" :arguments {:path "/foo/bar"} :id "tc-1"}
             (:content tool-msg))))))

;; emit-jsonl! tests

(deftest emit-jsonl-one-line-per-record-test
  (testing "emits one valid JSON object per line"
    (let [data [{:a 1} {:b 2} {:c "hello"}]
          out (with-out-str (read-chat/emit-jsonl! data))
          lines (string/split-lines (string/trim out))]
      (is (= 3 (count lines)))
      (is (= {:a 1} (cheshire/parse-string (nth lines 0) keyword)))
      (is (= {:b 2} (cheshire/parse-string (nth lines 1) keyword)))
      (is (= {:c "hello"} (cheshire/parse-string (nth lines 2) keyword))))))

(deftest emit-jsonl-empty-test
  (testing "empty seq emits nothing"
    (is (= "" (with-out-str (read-chat/emit-jsonl! []))))))

(deftest emit-jsonl-escapes-newlines-in-content-test
  (testing "multi-line content stays on a single line via JSON escaping"
    (let [data [{:content "line one\nline two"}]
          out (with-out-str (read-chat/emit-jsonl! data))
          lines (string/split-lines (string/trim out))]
      (is (= 1 (count lines)))
      (is (= "line one\nline two" (:content (cheshire/parse-string (first lines) keyword)))))))

;; CLI integration tests

(deftest parse-read-chat-command-test
  (testing "parse recognizes read-chat command with explicit db path"
    (let [result (#'main/parse ["read-chat" "--db-cache-path" "/tmp/db.transit.json"])]
      (is (= "read-chat" (:action result)))
      (is (= "/tmp/db.transit.json" (get-in result [:options :db-cache-path])))))
  (testing "parse recognizes repeated workspace inputs"
    (let [result (#'main/parse ["read-chat" "--workspace" "/tmp/a" "--workspace" "/tmp/b"])]
      (is (= "read-chat" (:action result)))
      (is (= ["/tmp/a" "/tmp/b"] (get-in result [:options :workspace]))))))

(deftest parse-read-chat-help-test
  (testing "parse returns help for read-chat --help"
    (let [result (#'main/parse ["read-chat" "--help"])]
      (is (:ok? result))
      (is (string/includes? (:exit-message result) "read-chat"))
      (is (string/includes? (:exit-message result) "raw structured records as JSONL")))))

(deftest parse-read-chat-validation-test
  (testing "missing input source is reported clearly"
    (let [result (#'main/parse ["read-chat"])]
      (is (nil? (:ok? result)))
      (is (string/includes? (:exit-message result) "Missing required input source"))
      (is (not (string/includes? (:exit-message result) ":org.babashka/cli")))))
  (testing "db path and workspace are mutually exclusive"
    (let [result (#'main/parse ["read-chat" "--db-cache-path" "/tmp/db.transit.json" "--workspace" "/tmp/a"])]
      (is (nil? (:action result)))
      (is (string/includes? (:exit-message result) "Use either --db-cache-path or --workspace, not both"))))
  (testing "unexpected positional arguments are rejected"
    (let [result (#'main/parse ["read-chat" "--db-cache-path" "/tmp/db.transit.json" "extra"])]
      (is (nil? (:action result)))
      (is (string/includes? (:exit-message result) "Unexpected argument(s): extra"))))
  (testing "unknown options are rejected"
    (let [result (#'main/parse ["read-chat" "--db-cache-path" "/tmp/db.transit.json" "--chatd-id" "abc"])]
      (is (nil? (:action result)))
      (is (string/includes? (:exit-message result) "Unknown option --chatd-id"))))
  (testing "role filtering requires detail mode"
    (let [result (#'main/parse ["read-chat" "--db-cache-path" "/tmp/db.transit.json" "--role" "nope"])]
      (is (nil? (:action result)))
      (is (string/includes? (:exit-message result) "--role requires --chat-id"))))
  (testing "exact role strings remain allowed in detail mode"
    (let [result (#'main/parse ["read-chat" "--db-cache-path" "/tmp/db.transit.json" "--chat-id" "chat-1" "--role" "future_role"])]
      (is (= "read-chat" (:action result)))
      (is (= "future_role" (get-in result [:options :role])))))
  (testing "date options stay as parsed CLI values"
    (let [result (#'main/parse ["read-chat" "--db-cache-path" "/tmp/db.transit.json" "--since" "2024-01-01"])]
      (is (= "read-chat" (:action result)))
      (is (= "2024-01-01" (get-in result [:options :since])))
      (is (not (contains? (:options result) :since-ms))))))

(deftest parse-top-level-help-still-works-test
  (testing "top-level --help still works"
    (let [result (#'main/parse ["--help"])]
      (is (:ok? result))
      (is (string/includes? (:exit-message result) "read-chat")))))

;; run integration tests

(deftest run-listing-emits-jsonl-test
  (testing "run prints JSONL listing to stdout with explicit db path"
    (with-tmp-transit-file sample-db
      (fn [path]
        (let [output (with-out-str
                       (read-chat/run {:db-cache-path path}))
              records (parse-jsonl output)]
          (is (= 3 (count records)))
          (is (every? #(contains? % :id) records))
          ;; sorted desc by :updated-at, chat-2 has newest
          (is (= "chat-2" (:id (first records))))))))
  (testing "run resolves workspace cache path using ECA cache logic"
    (let [workspaces [(str (fs/create-temp-dir {:prefix "eca-ws-a"}))
                      (str (fs/create-temp-dir {:prefix "eca-ws-b"}))]
          cache-root (fs/create-temp-dir {:prefix "eca-cache"})]
      (try
        (with-redefs [cache/global-dir (fn [] (io/file (str cache-root)))]
          (let [path (workspace-db-path workspaces)]
            (io/make-parents path)
            (write-transit-file path sample-db)
            (let [output (with-out-str
                           (read-chat/run {:workspace workspaces}))
                  records (parse-jsonl output)]
              (is (= 3 (count records)))
              (is (= "chat-2" (:id (first records)))))))
        (finally
          (doseq [ws workspaces]
            (fs/delete-tree ws))
          (fs/delete-tree cache-root))))))

(deftest run-detail-emits-message-stream-test
  (testing "run emits one message per line in detail mode"
    (with-tmp-transit-file sample-db
      (fn [path]
        (let [output (with-out-str
                       (read-chat/run {:db-cache-path path :chat-id "chat-1"}))
              records (parse-jsonl output)]
          (is (= 5 (count records)))
          (is (every? #(contains? % :role) records))
          (is (every? #(contains? % :content) records))
          ;; chat metadata is NOT in detail output
          (is (not-any? #(contains? % :title) records)))))))

(deftest run-missing-path-test
  (testing "run throws a clear error for missing explicit db file"
    (let [^clojure.lang.ExceptionInfo e (try
                                          (read-chat/run {:db-cache-path "/nonexistent/path"})
                                          nil
                                          (catch clojure.lang.ExceptionInfo e
                                            e))]
      (is e)
      (is (string/includes? (.getMessage e) "DB cache file not found: /nonexistent/path"))
      (is (string/includes? (.getMessage e) "Check --db-cache-path")))))

(deftest run-missing-workspace-derived-path-test
  (testing "run throws a clear error when the resolved workspace cache does not exist"
    (let [workspaces ["/tmp/eca-missing-a" "/tmp/eca-missing-b"]
          ^clojure.lang.ExceptionInfo e (try
                                          (read-chat/run {:workspace workspaces})
                                          nil
                                          (catch clojure.lang.ExceptionInfo e
                                            e))]
      (is e)
      (is (string/includes? (.getMessage e) "Resolved DB cache path does not exist"))
      (is (string/includes? (.getMessage e) "--workspace set likely does not match")))))

(deftest run-missing-chat-id-test
  (testing "run throws for missing chat-id"
    (with-tmp-transit-file sample-db
      (fn [path]
        (is (thrown? clojure.lang.ExceptionInfo
                     (read-chat/run {:db-cache-path path :chat-id "nonexistent"})))))))

(deftest run-invalid-date-test
  (testing "run throws a clear error for invalid date filters"
    (with-tmp-transit-file sample-db
      (fn [path]
        (let [^clojure.lang.ExceptionInfo e (try
                                              (read-chat/run {:db-cache-path path :since "yesterday"})
                                              nil
                                              (catch clojure.lang.ExceptionInfo e
                                                e))]
          (is e)
          (is (string/includes? (.getMessage e) "Invalid date format: yesterday")))))))

(deftest run-warning-on-excluded-messages-test
  (testing "run prints warning to stderr when messages are excluded by time filter"
    (with-tmp-transit-file (update-in sample-db [:chats "chat-1" :messages]
                                      (fn [msgs] (mapv #(dissoc % :created-at) msgs)))
      (fn [path]
        (let [err-buf (java.io.StringWriter.)
              out (binding [*err* err-buf]
                    (with-out-str
                      (read-chat/run {:db-cache-path path :chat-id "chat-1" :since "2025-01-01"})))]
          (is (= "" (string/trim out)))
          (is (string/includes? (str err-buf) "without :created-at")))))))
