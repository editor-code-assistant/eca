(ns eca.features.chat.history
  "Transport-agnostic windowing and cursor logic over a chat's persisted
   messages, shared by the remote HTTP API and the JSON-RPC `chat/history`
   method.

   Cursors are opaque tokens: base64url of \"<index>.<checksum>\" where checksum
   fingerprints the message. Resolving trusts the encoded index when its checksum
   still matches, else rescans by checksum (tolerating shifts like flag
   insert/remove); a cursor whose message was cut off by truncating the history
   resolves to its end, and any other cursor whose message is gone resolves to
   :expired."
  (:require
   [clojure.string :as string])
  (:import
   [java.nio.charset StandardCharsets]
   [java.util Base64]))

(set! *warn-on-reflection* true)

(def last-compaction-sentinel
  "Literal cursor value resolving to the position of the last compaction marker."
  "lastCompaction")

(defn ^:private message-checksum
  "Stable-enough fingerprint of a message to validate/resolve a cursor."
  [message]
  (Integer/toHexString (hash [(:role message) (:created-at message) (:content message)])))

(defn ^:private encode-cursor [idx message]
  (let [raw (.getBytes (str idx "." (message-checksum message)) StandardCharsets/UTF_8)]
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) raw)))

(defn ^:private decode-cursor [^String cursor]
  (try
    (let [decoded (String. (.decode (Base64/getUrlDecoder) cursor) StandardCharsets/UTF_8)
          [idx-s checksum] (string/split decoded #"\." 2)]
      {:idx (Integer/parseInt idx-s) :checksum checksum})
    (catch Exception _ nil)))

(defn ^:private last-compaction-idx
  "Index of the last compact_marker message, or nil when never compacted."
  [messages]
  (loop [i (dec (count messages))]
    (cond
      (neg? i) nil
      (= "compact_marker" (:role (nth messages i))) i
      :else (recur (dec i)))))

(defn ^:private resolve-cursor
  "Resolves an opaque cursor to an index, or :expired if its message is gone.
   A gone message whose index is past the end resolves to the end, as the
   history was truncated at or before it (rollback) and every remaining
   message is older."
  [messages ^String cursor]
  (if-let [{:keys [idx checksum]} (decode-cursor cursor)]
    (let [n (count messages)]
      (if (and (<= 0 idx) (< idx n)
               (= checksum (message-checksum (nth messages idx))))
        idx
        (or (first (keep-indexed (fn [i m] (when (= checksum (message-checksum m)) i)) messages))
            (when (>= idx n) n)
            :expired)))
    :expired))

(defn ^:private resolve-bound
  "Resolves a `before`/`after` value to an index, :expired, or nil (absent).
   The `lastCompaction` sentinel resolves to the last compaction marker, or
   :no-compaction when the chat was never compacted."
  [messages value]
  (when value
    (if (= value last-compaction-sentinel)
      (or (last-compaction-idx messages) :no-compaction)
      (resolve-cursor messages value))))

(defn ^:private coerce-limit
  "Coerces a limit that may arrive as an int (JSON-RPC) or string (HTTP query)."
  [value]
  (when value
    (try
      (let [n (if (integer? value) value (Integer/parseInt (str value)))]
        (when (pos? n) n))
      (catch Exception _ nil))))

(defn ^:private reason-at? [messages idx]
  (= "reason" (:role (nth messages idx nil))))

(defn ^:private widen-over-reason-runs
  "Widens the [start end) slice so it never cuts through a run of adjacent
   reason messages, which clients render as a single block, without leaving
   the [win-start win-end) window."
  [messages win-start win-end [start end]]
  [(loop [s start]
     (if (and (> s win-start) (reason-at? messages s) (reason-at? messages (dec s)))
       (recur (dec s))
       s))
   (loop [e end]
     (if (and (< e win-end) (reason-at? messages (dec e)) (reason-at? messages e))
       (recur (inc e))
       e))])

(defn window-messages
  "Windows `messages` by the (after, before) cursors and `limit`.

   `after` is an exclusive lower bound, `before` an exclusive upper bound. The
   page is the newest `limit` messages in the window; an opaque `after` cursor as
   the only bound pages forward (oldest `limit`). `before-cursor`/`after-cursor`
   are computed against full history so paging crosses the compaction boundary,
   and are nil at the ends. A page never splits a run of adjacent reason
   messages, so it may hold a few more than `limit` messages.

   Returns {:messages :before-cursor :after-cursor :total :returned} or
   {:error :cursor-expired}."
  [messages {:keys [limit before after]}]
  (let [messages (vec messages)
        n (count messages)
        limit (coerce-limit limit)
        after-res (resolve-bound messages after)
        before-res (resolve-bound messages before)]
    (if (or (= :expired after-res) (= :expired before-res))
      {:error :cursor-expired}
      (let [lo (if (integer? after-res) after-res -1)        ;; exclusive lower
            hi (if (integer? before-res) before-res n)       ;; exclusive upper
            win-start (max 0 (min (inc lo) n))
            win-end (max win-start (min hi n))
            ;; Opaque `after` cursor pages forward; the sentinel stays newest-anchored.
            forward? (and (integer? after-res)
                          (not= after last-compaction-sentinel)
                          (nil? before-res))
            [slice-start slice-end]
            (widen-over-reason-runs
             messages win-start win-end
             (cond
               (nil? limit) [win-start win-end]
               forward? [win-start (min win-end (+ win-start limit))]
               :else [(max win-start (- win-end limit)) win-end]))
            slice (subvec messages slice-start slice-end)]
        {:messages slice
         :total n
         :returned (count slice)
         :before-cursor (when (pos? slice-start)
                          (encode-cursor slice-start (nth messages slice-start)))
         :after-cursor (when (< slice-end n)
                         (encode-cursor (dec slice-end) (nth messages (dec slice-end))))}))))

(defn compaction-cursor
  "Opaque cursor for the last compaction boundary, or nil when never compacted."
  [messages]
  (when-let [idx (last-compaction-idx (vec messages))]
    (encode-cursor idx (nth (vec messages) idx))))

(defn page-meta
  "Pagination meta for a `window-messages` `page` of `messages`."
  [page messages]
  {:total (:total page)
   :returned (:returned page)
   :before-cursor (:before-cursor page)
   :after-cursor (:after-cursor page)
   :compaction-cursor (compaction-cursor messages)})

(def default-history-page-size
  "Messages sent when the server creates a chat for a client supporting
   incremental updates that did not declare a `historyPageSize`."
  50)

(defn ^:private incremental-updates-capability [db]
  (get-in db [:client-capabilities :code-assistant :chat-capabilities :incremental-updates]))

(defn incremental-updates?
  "Whether the client applies chat history changes in place and pages
   server-created chats instead of needing full replays."
  [db]
  (boolean (incremental-updates-capability db)))

(defn history-page-size
  "Number of messages to send for a chat the server creates, as declared by
   the client's `incrementalUpdates` capability."
  [db]
  (let [capability (incremental-updates-capability db)]
    (or (coerce-limit (when (map? capability) (:history-page-size capability)))
        default-history-page-size)))

(defn server-created-page
  "The newest page of `messages` to send for a chat the server creates, or nil
   when the client needs the full history."
  [db messages]
  (when (incremental-updates? db)
    (window-messages messages {:limit (history-page-size db)})))
