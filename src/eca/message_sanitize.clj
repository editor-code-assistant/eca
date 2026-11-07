(ns eca.message-sanitize)

(set! *warn-on-reflection* true)

(defn ^:private memory-block?
  "True for a `:memory-context` block. These blocks are ECA-only: the memory
   feature adds them to user messages."
  [block]
  (and (map? block) (= :memory-context (:type block))))

(defn ^:private update-blocks
  "Applies `f` to the `:content` of `message` when it is a block vector. A
   string, nil or missing `:content` is left as is."
  [message f]
  (cond-> message
    (sequential? (:content message)) (update :content f)))

(defn strip-internal-message-fields
  "Removes internal ECA metadata keys (:created-at, :content-id, :memory) from
   a message map. `:memory` must go: the Anthropic and OpenAI Responses
   normalizers copy unknown top-level message keys into the request body,
   which the API rejects."
  [message]
  (dissoc message :created-at :content-id :memory))

(defn sanitize-outbound-message
  "Prepares one message for a provider: converts memory blocks to text blocks
   (vendors accept text blocks only), then strips internal top-level metadata."
  [message]
  (-> message
      (update-blocks (fn [blocks]
                       (mapv #(if (memory-block? %) {:type :text :text (:text %)} %)
                             blocks)))
      strip-internal-message-fields))

(defn sanitize-outbound-messages
  "Strips internal ECA metadata and converts ECA-only blocks for an outbound
   message collection before provider serialization."
  [messages]
  (mapv sanitize-outbound-message messages))

(defn remove-memory-context
  "Removes automatic `:memory-context` blocks and the `:memory` state key.
   Used for export, remote chat history and messages re-sent into a new window.
   Normal tool results and consolidation prompt text are not removed."
  [message]
  (-> message
      (update-blocks #(filterv (complement memory-block?) %))
      (dissoc :memory)))
