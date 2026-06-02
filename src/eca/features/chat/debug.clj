(ns eca.features.chat.debug
  "Dumps a single chat's in-memory state to a file for debugging corrupted/stuck
   chats. User/assistant/reasoning text and tool-call input/output are obfuscated
   (privacy + size reduction) while the message-history structure and the chat
   metadata that affects message sending (model, variant, tool names, statuses)
   are preserved."
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as string]
   [eca.config :as config]))

(set! *warn-on-reflection* true)

(defn ^:private idx->label
  "Bijective base-26 label (spreadsheet-style): 0->A, 25->Z, 26->AA, 27->AB ..."
  [n]
  (loop [n (long n) acc ""]
    (let [q (quot n 26)
          r (mod n 26)
          ch (char (+ (int \A) r))]
      (if (pos? q)
        (recur (dec q) (str ch acc))
        (str ch acc)))))

(defn ^:private size-bucket
  "Coarse log10 size signal so an anomalously large entry still stands out while
   the content is reduced to a tiny token."
  [^long len]
  (-> (Math/log10 (inc (double len))) Math/ceil long (max 1) (min 12)))

(defn make-obfuscator
  "Stateful obfuscator. Identical source strings reuse the same label so
   duplicated/echoed content (a corruption smell) stays detectable."
  []
  (atom {:n 0 :memo {}}))

(defn ^:private label-for [state* s]
  (-> (swap! state* (fn [{:keys [n memo] :as st}]
                      (if (contains? memo s)
                        st
                        (-> st (assoc :n (inc n)) (assoc-in [:memo s] (idx->label n))))))
      (get-in [:memo s])))

(defn obfuscate-str
  "Replace a string with its label repeated to encode a coarse size bucket,
   e.g. a ~1k char first chunk -> \"AAAA\", a ~100 char second chunk -> \"BBB\"."
  [obf s]
  (let [s (str s)]
    (if (string/blank? s)
      s
      (let [label (label-for obf s)]
        (apply str (repeat (size-bucket (count s)) label))))))

(defn ^:private obf-or-nil [obf s]
  (when s (obfuscate-str obf s)))

(defn ^:private edn-safe-scalar [data]
  (if (or (number? data) (boolean? data) (keyword? data)
          (symbol? data) (uuid? data) (inst? data))
    data
    (str "#unprintable[" (.getName ^Class (class data)) "]")))

(defn ^:private obfuscate-data
  "Recursively obfuscate every string leaf while preserving structure: map keys,
   keyword values, numbers, booleans and nil are kept as-is."
  [obf data]
  (cond
    (nil? data) nil
    (string? data) (obfuscate-str obf data)
    (map? data) (reduce-kv (fn [m k v] (assoc m k (obfuscate-data obf v))) {} data)
    (vector? data) (mapv #(obfuscate-data obf %) data)
    (set? data) (into #{} (map #(obfuscate-data obf %)) data)
    (seq? data) (mapv #(obfuscate-data obf %) data)
    :else (edn-safe-scalar data)))

(defn ^:private text-block? [block kind]
  (and (map? block) (#{kind (name kind)} (:type block))))

(defn ^:private obfuscate-text-content [obf content]
  (cond
    (string? content) (obfuscate-str obf content)
    (sequential? content)
    (mapv (fn [block]
            (cond
              (text-block? block :text) (update block :text #(obfuscate-str obf %))
              (text-block? block :image) (-> block (dissoc :base64 :data) (assoc :image :redacted))
              :else (obfuscate-data obf block)))
          content)
    :else (obfuscate-data obf content)))

(defn ^:private obfuscate-tool-output [obf output]
  (cond
    (map? output) (cond-> (select-keys output [:error])
                    (contains? output :contents)
                    (assoc :contents (mapv (fn [c]
                                             (if (and (map? c) (contains? c :text))
                                               (update c :text #(obfuscate-str obf %))
                                               (obfuscate-data obf c)))
                                           (:contents output))))
    (string? output) (obfuscate-str obf output)
    :else (obfuscate-data obf output)))

(defn ^:private obfuscate-content [obf role content]
  (case role
    ("user" "assistant" "system")
    (obfuscate-text-content obf content)

    "reason"
    (-> (select-keys content [:id :delta-reasoning? :total-time-ms :redacted? :api])
        (assoc :text (obfuscate-str obf (:text content))
               :external-id (obf-or-nil obf (:external-id content)))
        (cond-> (:redacted? content) (assoc :data :redacted)))

    "tool_call"
    (-> (select-keys content [:id :name :full-name :origin :server :api])
        (assoc :arguments (obfuscate-data obf (:arguments content))
               :summary (obf-or-nil obf (:summary content))
               :details (obfuscate-data obf (:details content))))

    "tool_call_output"
    (-> (select-keys content [:id :name :full-name :origin :server :error :total-time-ms :api])
        (assoc :arguments (obfuscate-data obf (:arguments content))
               :output (obfuscate-tool-output obf (:output content))
               :summary (obf-or-nil obf (:summary content))
               :details (obfuscate-data obf (:details content))))

    "server_tool_use"
    (-> (select-keys content [:id :name :api])
        (assoc :input (obfuscate-data obf (:input content))))

    "server_tool_result"
    (-> (select-keys content [:tool-use-id :api])
        (assoc :raw-content (obfuscate-data obf (:raw-content content))))

    "image_generation_call"
    {:image :redacted}

    ;; compact_marker, flag and anything else: obfuscate every string leaf.
    (obfuscate-data obf content)))

(defn ^:private obfuscate-message [obf msg]
  (-> (select-keys msg [:role :content-id :created-at])
      (assoc :content (obfuscate-content obf (:role msg) (:content msg)))))

(defn ^:private sanitize-tool-calls
  "Keep only the serializable, diagnostic parts of the live tool-call state
   machine. Drops the promises/futures (:approved?*, :future-cleanup-complete?*,
   :future) and any content-bearing fields."
  [tool-calls]
  (into {}
        (map (fn [[id tc]]
               [id (cond-> (select-keys tc [:status :name :full-name :server :origin :start-time])
                     (:decision-reason tc) (assoc :decision-reason (select-keys (:decision-reason tc) [:code])))]))
        tool-calls))

(defn ^:private auth-summary
  "Presence + type + expiry of each logged provider. Never includes tokens or
   API keys."
  [db]
  (into {}
        (keep (fn [[provider auth]]
                (when (seq auth)
                  [provider (cond-> {:type (:type auth)
                                     :step (:step auth)
                                     :mode (:mode auth)}
                              (:expires-at auth) (assoc :expires-at (:expires-at auth)))])))
        (:auth db)))

(defn build-dump
  "Build the curated, obfuscated dump map for chat-id from the in-memory db."
  [db chat-id all-tools]
  (let [chat (get-in db [:chats chat-id])
        obf (make-obfuscator)
        messages (:messages chat [])]
    {:eca/version (config/eca-version)
     :dumped-at (System/currentTimeMillis)
     :obfuscation {:scheme :sequential-letter-labels
                   :note (str "Each distinct text is replaced by a base-26 letter label (A, B, C ...) "
                              "repeated to encode a coarse log10 size bucket. Identical source strings "
                              "share a label. Roles, tool names, ids, model, variant, statuses and counts "
                              "are preserved verbatim.")}
     :chat (-> (select-keys chat [:id :title-custom? :status :created-at :updated-at
                                  :login-provider :model :variant :last-api :trust :prompt-id
                                  :user-prompt-count :subagent :parent-chat-id])
               (assoc :title (obf-or-nil obf (:title chat))
                      :startup-context (obf-or-nil obf (:startup-context chat))
                      :flags (select-keys chat [:prompt-finished? :compacting? :auto-compacting? :max-steps-reached?])
                      :message-count (count messages)
                      :messages-by-role (frequencies (map :role messages))
                      :task (obfuscate-data obf (:task chat))
                      :tool-calls (sanitize-tool-calls (:tool-calls chat))
                      :messages (mapv #(obfuscate-message obf %) messages)))
     :model-capabilities (get-in db [:models (:model chat)])
     :available-tools (mapv #(select-keys % [:name :full-name :origin :server]) all-tools)
     :mcp-clients (into {}
                        (map (fn [[id c]]
                               [id {:status (:status c)
                                    :tools (mapv :name (:tools c))}]))
                        (:mcp-clients db))
     :auth-summary (auth-summary db)}))

(defn dump->edn [dump]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint dump))))

(defn ^:private slugify [s]
  (let [slug (-> (or s "")
                 string/lower-case
                 (string/replace #"[^a-z0-9]+" "-")
                 (string/replace #"^-+|-+$" ""))]
    (when-not (string/blank? slug)
      (subs slug 0 (min 60 (count slug))))))

(defn default-filepath [chat]
  (let [name (or (slugify (:title chat)) (:id chat) "chat")]
    (str (fs/file (fs/temp-dir) (str "eca-chat-debug-" name ".edn")))))

(defn dump-chat!
  "Build the obfuscated dump for chat-id and write it as EDN. Returns
   {:path <abs-path> :message-count <n>} on success or {:error <msg>} on failure.
   Resolves a blank filepath to /tmp/eca-chat-debug-<title>.edn."
  [{:keys [db chat-id all-tools filepath]}]
  (try
    (if-let [chat (get-in db [:chats chat-id])]
      (let [path (-> (if (string/blank? filepath)
                       (default-filepath chat)
                       (str (fs/expand-home (string/trim filepath))))
                     fs/absolutize
                     str)]
        (io/make-parents path)
        (spit path (dump->edn (build-dump db chat-id all-tools)))
        {:path path
         :message-count (count (:messages chat))})
      {:error (str "Chat not found: " chat-id)})
    (catch Exception e
      {:error (.getMessage e)})))
