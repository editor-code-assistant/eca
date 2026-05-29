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
                    :desc "Path to db.transit.json file"
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
       "Workspace inputs are normalized and resolved with the same order-sensitive cache path logic ECA uses.\n\n"
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

(defn- try-parse [f]
  (try
    (f)
    (catch Exception _ nil)))

(defn resolve-db-cache-path
  "Resolve the db cache path from explicit opts.
   Accepts either :db-cache-path or repeated :workspace paths."
  [opts]
  (if-let [path (:db-cache-path opts)]
    path
    (when-let [workspaces (seq (:workspace opts))]
      (let [workspace-uris (mapv (fn [wpath] {:uri (shared/filename->uri wpath)}) workspaces)]
        (str (cache/workspace-cache-file workspace-uris "db.transit.json" shared/uri->filename))))))

(defn- parse-date-ms [^String s]
  (or (when-let [[_ amount-str unit] (re-matches #"^(\d+)([mhd])$" s)]
        (try-parse #(- (System/currentTimeMillis)
                       (* (Long/parseLong amount-str) (get relative-unit->ms unit)))))
      (try-parse #(.toEpochMilli ^Instant (Instant/parse s)))
      (try-parse #(.toEpochMilli (.toInstant (.atStartOfDay ^LocalDate (LocalDate/parse s) ZoneOffset/UTC))))
      (throw (ex-info (str "Invalid date format: " s
                           ". Use relative (e.g. 2h, 30m, 1d) or ISO-8601 (e.g. 2025-01-01 or 2025-01-01T00:00:00Z).")
                      {:value s :type ::invalid-date}))))

(defn- parse-time-bounds
  "Parse :since/:until from opts into epoch-millis. Returns {:since-ms ... :until-ms ...}."
  [opts]
  {:since-ms (when-let [value (:since opts)] (parse-date-ms value))
   :until-ms (when-let [value (:until opts)] (parse-date-ms value))})

(defn- within-time-bounds?
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

(defn- message-matches?
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

(defn- warn! [msg]
  (binding [*out* *err*]
    (println (str "Warning: " msg))))

(defn run
  [opts]
  (let [path (resolve-db-cache-path opts)
        workspace-inputs (seq (:workspace opts))]
    (when-not path
      (throw (ex-info "Missing required input source: pass --db-cache-path <PATH> or one or more --workspace <PATH>"
                      {:type ::missing-path})))
    (let [db (try
               (read-db path)
               (catch clojure.lang.ExceptionInfo e
                 (if (= (:type (ex-data e)) ::file-not-found)
                   (throw (ex-info
                           (if workspace-inputs
                             (str "Resolved DB cache path does not exist: " path
                                  ". The provided --workspace set likely does not match the original ECA session workspaces.")
                             (str "DB cache file not found: " path
                                  ". Check --db-cache-path and try again."))
                           (shared/assoc-some (ex-data e) :workspace-inputs workspace-inputs)
                           e))
                   (throw e))))]
      (when-not (= (:version db) db/version)
        (warn! (str "DB version mismatch. File has version " (:version db)
                    ", expected " db/version ". Output may be incomplete.")))
      (if-let [chat-id (:chat-id opts)]
        (let [{:keys [messages warnings]} (chat-messages db chat-id opts)]
          (doseq [w warnings] (warn! w))
          (emit-jsonl! messages))
        (emit-jsonl! (list-chats db opts)))
      (.flush ^java.io.Writer *out*)
      {:result-code 0})))
