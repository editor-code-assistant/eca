(ns eca.message-sanitize)

(set! *warn-on-reflection* true)

(def ^:private memory-block-type :memory-context)

(defn ^:private map-content-blocks
  "Applies `f` to every content block of a message `:content`. A string, nil
   or any non-sequential content is returned unchanged: only vector content
   carries blocks."
  [content f]
  (if (sequential? content)
    (mapv f content)
    content))

(defn strip-internal-message-fields
  "Removes internal ECA metadata keys (:created-at, :content-id, :memory) from
   a message map. `:memory` must go: the Anthropic and OpenAI Responses
   normalizers copy unknown top-level message keys into the request body,
   which the API rejects."
  [message]
  (apply dissoc message [:created-at :content-id :memory]))

(defn ^:private memory-block->text-block
  "`:memory-context` blocks are ECA-only; vendors accept text blocks only."
  [block]
  (if (and (map? block) (= memory-block-type (:type block)))
    {:type :text :text (:text block)}
    block))

(defn sanitize-outbound-message
  "Prepares one message for a provider: converts ECA-only blocks to what every
   vendor understands, then strips internal top-level metadata."
  [message]
  (-> message
      (update :content map-content-blocks memory-block->text-block)
      strip-internal-message-fields))

(defn sanitize-outbound-messages
  "Strips internal ECA metadata and converts ECA-only blocks for an outbound
   message collection before provider serialization."
  [messages]
  (mapv sanitize-outbound-message messages))

(defn ^:private non-memory-block?
  [block]
  (not (and (map? block) (= memory-block-type (:type block)))))

(defn remove-memory-context
  "Removes all memory data from one message: the `:memory-context` blocks and
   the `:memory` state key. Used where content leaves the machine (export,
   remote API) and when messages are re-sent into a new window."
  [message]
  (-> message
      (update :content (fn [content]
                         (if (sequential? content)
                           (filterv non-memory-block? content)
                           content)))
      (dissoc :memory)))
