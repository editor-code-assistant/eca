(ns eca.read-chat
  (:require
   [clojure.java.io :as io]

   [babashka.cli :as cli]
   [cheshire.core :as cheshire]

   [eca.cache :as cache]
   [eca.db :as db]
   [eca.shared :as shared])
  (:import
   [java.time Instant LocalDate ZoneOffset]))

(set! *warn-on-reflection* true)

(def read-chat-spec
  {:order [:db-cache-path :workspace :chat-id :role :since :until :help]
   :spec
   {:help {:alias :h
           :desc "Print read-chat options"}
    :db-cache-path {:ref "<PATH>"
                    :desc "Path to the workspace chat cache dir (or a legacy db.transit.json file)"
                    :coerce :string}
    :workspace {:ref "<PATH>"
                :desc "Workspace path. Repeat in the same order as the ECA session. Alternative to --db-cache-path"
                :coerce []}
    :chat-id {:ref "<CHAT-ID>"
              :desc "Focus on a specific chat. Without it, lists all chats."
              :coerce :string}
    :role {:ref "<ROLE>"
           :desc "Filter messages by exact persisted role string (detail mode only). Common roles: user, assistant, tool_call, tool_call_output, reason, server_tool_use, server_tool_result, image_generation_call, compact_marker, flag"
           :coerce :string}
    :since {:ref "<DATE>"
            :desc "Listing: chats updated after date. Detail: messages created after date. Relative: 2h, 30m, 1d."
            :coerce :string}
    :until {:ref "<DATE>"
            :desc "Listing: chats updated before date. Detail: messages created before date. Relative: 2h, 30m, 1d."
            :coerce :string}}})

(defn help
  []
  (str "Usage: eca read-chat [<options>]\n\n"
       "Reads ECA's chat database cache and emits raw structured records as JSONL (one JSON object per line).\n\n"
       "Listing mode (no --chat-id):\n"
       "  Streams chat summaries, sorted by :updated-at desc.\n"
       "  --since/--until filter chats by :updated-at.\n\n"
       "Detail mode (--chat-id <id>):\n"
       "  Streams messages from the chat in chronological order.\n"
       "  --since/--until filter messages by :created-at.\n"
       "  --role filters by message role.\n\n"
       "Input source: pass either --db-cache-path <PATH> or one or more --workspace <PATH> values.\n"
       "--db-cache-path accepts the workspace cache dir (per-chat layout) or a legacy db.transit.json file.\n"
       "Workspace inputs are normalized and resolved with the same cache path logic ECA uses.\n\n"
       "Date formats: relative (2h, 30m, 1d) or ISO-8601 (2025-01-01, 2025-01-01T00:00:00Z).\n\n"
       "Options:\n"
       (cli/format-opts read-chat-spec)))

(defn read-db
  [path]
  (or (try
        (db/read-transit-file (io/file path))
        (catch Exception e
          (throw (ex-info (str "Could not read or parse transit data in " path ": " (.getMessage e)
                               ". The ECA server may be writing to this file.")
                          {:path path :type ::read-error} e))))
      (throw (ex-info (str "DB cache file is missing or empty: " path)
                      {:path path :type ::file-not-found}))))

(def ^:private relative-unit->ms
  {"m" 60000
   "h" 3600000
   "d" 86400000})

(defn ^:private try-parse [f]
  (try
    (f)
    (catch Exception _ nil)))

(defn resolve-db-cache-path
  "Resolve the db cache path from explicit opts.
   Accepts either :db-cache-path or repeated :workspace paths; the latter
   resolves to the workspace cache dir (per-chat layout)."
  [opts]
  (if-let [path (:db-cache-path opts)]
    path
    (when-let [workspaces (seq (:workspace opts))]
      (let [workspace-uris (mapv (fn [wpath] {:uri (shared/filename->uri wpath)}) workspaces)]
        (str (cache/workspace-cache-dir workspace-uris shared/uri->filename))))))

(defn ^:private parse-date-ms [^String s]
  (or (when-let [[_ amount-str unit] (re-matches #"^(\d+)([mhd])$" s)]
        (try-parse #(- (System/currentTimeMillis)
                       (* (Long/parseLong amount-str) (get relative-unit->ms unit)))))
      (try-parse #(.toEpochMilli ^Instant (Instant/parse s)))
      (try-parse #(.toEpochMilli (.toInstant (.atStartOfDay ^LocalDate (LocalDate/parse s) ZoneOffset/UTC))))
      (throw (ex-info (str "Invalid date format: " s
                           ". Use relative (e.g. 2h, 30m, 1d) or ISO-8601 (e.g. 2025-01-01 or 2025-01-01T00:00:00Z).")
                      {:value s :type ::invalid-date}))))

(defn ^:private parse-time-bounds
  "Parse :since/:until from opts into epoch-millis. Returns {:since-ms ... :until-ms ...}."
  [opts]
  {:since-ms (when-let [value (:since opts)] (parse-date-ms value))
   :until-ms (when-let [value (:until opts)] (parse-date-ms value))})

(defn ^:private within-time-bounds?
  [timestamp {:keys [since-ms until-ms]}]
  (and (or (nil? since-ms) (>= timestamp since-ms))
       (or (nil? until-ms) (< timestamp until-ms))))

(defn list-chats
  "Returns a seq of chat summary maps, sorted by :updated-at desc."
  [db opts]
  (let [bounds (parse-time-bounds opts)]
    (->> (:chats db)
         (filter (fn [[_ chat]]
                   (within-time-bounds? (or (:updated-at chat) 0) bounds)))
         (sort-by (fn [[_ chat]] (or (:updated-at chat) 0)) >)
         (map (fn [[chat-id chat]]
                (assoc (select-keys chat [:title :status :model :created-at :updated-at :user-prompt-count])
                       :id chat-id))))))

(defn ^:private message-matches?
  [bounds role message]
  (and (or (nil? role) (= role (:role message)))
       (if (or (:since-ms bounds) (:until-ms bounds))
         (when-let [created-at (:created-at message)]
           (within-time-bounds? created-at bounds))
         true)))

(defn chat-messages
  "Returns a map {:messages <lazy-seq> :warnings <vector>} for the given chat-id.
   Throws ex-info if chat-id is not found in the db.

   :messages is filtered per opts (since, until, role) and otherwise preserves
   persisted message shape.
   :warnings contains informational strings about silent exclusions (e.g. messages
   without :created-at that were dropped by the time filter)."
  [db chat-id opts]
  (if-let [chat (get-in db [:chats chat-id])]
    (let [messages (:messages chat [])
          bounds (parse-time-bounds opts)
          time-filter? (or (:since-ms bounds) (:until-ms bounds))
          excluded-no-ts (when time-filter?
                           (count (remove :created-at messages)))]
      {:messages (filter #(message-matches? bounds (:role opts) %) messages)
       :warnings (cond-> []
                   (and time-filter? (pos? excluded-no-ts))
                   (conj (str excluded-no-ts " message(s) without :created-at were excluded by time filter")))})
    (throw (ex-info (str "Chat not found: " chat-id)
                    {:chat-id chat-id :type ::chat-not-found}))))

(defn emit-jsonl!
  "Emit a seq of records as JSONL to *out*. One JSON object per line."
  [records]
  (doseq [r records]
    (println (cheshire/generate-string r))))

(defn ^:private warn! [msg]
  (binding [*out* *err*]
    (println (str "Warning: " msg))))

(defn ^:private resolve-source
  "Classifies `path` into the cache layout it points at: a workspace cache dir
   using the per-chat layout (#557), a dir still holding only a legacy
   `db.transit.json` blob, a chats index file, or a legacy blob file."
  [^String path]
  (let [f (io/file path)]
    (cond
      (.isDirectory f)
      (let [index (io/file f "chats" "index.transit.json")
            legacy (io/file f "db.transit.json")]
        (if (and (not (.exists index)) (.exists legacy))
          {:layout :legacy :file legacy}
          {:layout :per-chat :dir f}))

      (= "index.transit.json" (.getName f))
      {:layout :per-chat :dir (some-> f .getParentFile .getParentFile)}

      :else
      {:layout :legacy :file f})))

(defn ^:private check-version! [actual expected]
  (when-not (= actual expected)
    (warn! (str "DB version mismatch. File has version " actual
                ", expected " expected ". Output may be incomplete."))))

(defn ^:private read-db-or-throw
  "read-db with a friendlier ::file-not-found message, since a missing file
   usually means the input path or --workspace set does not match the
   original ECA session."
  [file workspace-inputs not-found-msg]
  (try
    (read-db file)
    (catch clojure.lang.ExceptionInfo e
      (if (= (:type (ex-data e)) ::file-not-found)
        (throw (ex-info (if workspace-inputs
                          (str "Resolved DB cache path does not exist: " file
                               ". The provided --workspace set likely does not match the original ECA session workspaces.")
                          (str not-found-msg ". Check --db-cache-path and try again."))
                        (shared/assoc-some (ex-data e) :workspace-inputs workspace-inputs)
                        e))
        (throw e)))))

(defn ^:private run-per-chat!
  [dir {:keys [chat-id] :as opts} workspace-inputs]
  (if chat-id
    (let [chat-f (io/file dir "chats" (db/chat-file-name chat-id))
          payload (try
                    (read-db chat-f)
                    (catch clojure.lang.ExceptionInfo e
                      (if (= (:type (ex-data e)) ::file-not-found)
                        (throw (ex-info (str "Chat not found: " chat-id)
                                        {:chat-id chat-id :type ::chat-not-found}
                                        e))
                        (throw e))))
          _ (check-version! (:version payload) db/chats-version)
          {:keys [messages warnings]} (chat-messages {:chats {chat-id (:chat payload)}} chat-id opts)]
      (doseq [w warnings] (warn! w))
      (emit-jsonl! messages))
    (let [index-f (io/file dir "chats" "index.transit.json")
          payload (read-db-or-throw index-f workspace-inputs
                                    (str "Chats index not found: " index-f))]
      (check-version! (:version payload) db/chats-version)
      (emit-jsonl! (list-chats {:chats (:chats payload)} opts)))))

(defn ^:private run-legacy!
  [file {:keys [chat-id] :as opts} workspace-inputs]
  (let [db (read-db-or-throw file workspace-inputs
                             (str "DB cache file not found: " file))]
    (check-version! (:version db) db/version)
    (if chat-id
      (let [{:keys [messages warnings]} (chat-messages db chat-id opts)]
        (doseq [w warnings] (warn! w))
        (emit-jsonl! messages))
      (emit-jsonl! (list-chats db opts)))))

(defn run
  [opts]
  (let [path (resolve-db-cache-path opts)
        workspace-inputs (seq (:workspace opts))]
    (when-not path
      (throw (ex-info "Missing required input source: pass --db-cache-path <PATH> or one or more --workspace <PATH>"
                      {:type ::missing-path})))
    (let [{:keys [layout dir file]} (resolve-source path)]
      (case layout
        :per-chat (run-per-chat! dir opts workspace-inputs)
        :legacy (run-legacy! file opts workspace-inputs)))
    (.flush ^java.io.Writer *out*)
    {:result-code 0}))
