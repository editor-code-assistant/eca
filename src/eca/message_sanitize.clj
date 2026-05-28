(ns eca.message-sanitize)

(set! *warn-on-reflection* true)

(defn strip-internal-message-fields
  "Removes internal ECA metadata keys (:created-at, :content-id) from a message map."
  [message]
  (apply dissoc message [:created-at :content-id]))

(defn sanitize-outbound-messages
  "Strips internal ECA top-level message metadata from an outbound message
   collection before provider serialization."
  [messages]
  (mapv strip-internal-message-fields messages))
