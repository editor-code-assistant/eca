(ns eca.message-sanitize
  "What ECA keeps in a chat message that only ECA reads, and how messages
   leave without it: for providers, titles, exports and clients.")

(set! *warn-on-reflection* true)

(defn module-context?
  "Whether `block`, a message content block, is context a module added: a
   text block with the module's id, `:module`, and maybe its `:data`."
  [block]
  (and (map? block)
       (some? (:module block))
       (contains? #{:text "text"} (:type block))))

(defn ^:private update-blocks
  "`message` with `f` applied to its content blocks: its content, when a
   vector of blocks, or a tool call's output contents."
  [message f]
  (cond
    (sequential? (:content message))
    (update message :content (comp vec f))

    (sequential? (get-in message [:content :output :contents]))
    (update-in message [:content :output :contents] (comp vec f))

    :else message))

(defn without-module-contexts
  "`message` without the contexts modules added, see `module-context?`."
  [message]
  (update-blocks message #(remove module-context? %)))

(defn strip-internal-message-fields
  "Removes internal ECA metadata from a message map: the top-level
   :created-at and :content-id, and the :module and :data of the contexts
   modules added, whose text stays for the model."
  [message]
  (-> (dissoc message :created-at :content-id)
      (update-blocks (fn [blocks]
                       (map #(if (module-context? %) (dissoc % :module :data) %) blocks)))))

(defn sanitize-outbound-messages
  "Strips internal ECA message metadata from an outbound message collection
   before provider serialization."
  [messages]
  (mapv strip-internal-message-fields messages))
