(ns eca.message-sanitize)

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Prompt injections — the outbound application seam
;;
;; `:prompt-injections` is the data-driven, request-local mechanism for
;; ephemeral per-turn content (e.g. the memory index). Injections are carried
;; in chat-ctx / LLM call params, NEVER stored on db messages, and applied
;; here (and at the provider-specific seams below) at outbound time.
;;
;; Boundary rule (teaching):
;;   Derived/recomputable per-turn content -> prompt-injections (request-local,
;;   outbound-time, never persisted). Content intrinsic to a specific message
;;   (must survive compaction, needs history-op semantics) -> belongs in db as
;;   real message content; do NOT add it here. There will intentionally be NO
;;   persist-through-compaction flag.
;;
;; Injection map shape:
;;   {:content <string>
;;    :target  :last-user-message    ; append into last user message at outbound
;;             :tail-system-message  ; trailing uncached system message (Anthropic finalize)
;;    :merge   :append-text}
;;
;; `:tail-system-message` injections are consumed by the Anthropic finalize
;; seam (see eca.llm-providers.anthropic/finalize-messages); every other
;; outbound build applies `:last-user-message` injections here.
;; ---------------------------------------------------------------------------

(defn ^:private last-user-message-idx
  "Index of the last message with role \"user\", or nil."
  [messages]
  (->> messages
       (keep-indexed (fn [i msg] (when (= "user" (:role msg)) i)))
       last))

(defn ^:private append-text-block
  "Appends a `{:type :text :text content}` block to a message's :content,
   which may be a string, a vector of blocks, nil, or (defensively) a map."
  [message content]
  (update message :content
          (fn [existing]
            (let [block {:type :text :text content}]
              (cond
                (string? existing) (into [{:type :text :text existing}] [block])
                (sequential? existing) (into (vec existing) [block])
                (nil? existing) [block]
                :else existing)))))

(defn apply-injections
  "Applies request-local `prompt-injections` to an outbound message collection
   before provider serialization.

   `:last-user-message` injections are appended as text blocks to the last
   user-role message (on tool-loop continuations this is the original user
   turn, since tool results use their own db roles at this stage).
   `:tail-system-message` injections are intentionally ignored here — the
   Anthropic finalize seam consumes them after the cached prefix.

   Injections are call-scoped: they are never derived from db message state,
   so every outbound build (initial request AND tool-loop continuations)
   re-applies them from the call params that carry this collection."
  [messages injections]
  (reduce
   (fn [msgs {:keys [target merge content]}]
     (if (and (= :last-user-message target)
              (= :append-text merge)
              (string? content))
       (if-let [idx (last-user-message-idx msgs)]
         (update-in msgs [idx] append-text-block content)
         msgs)
       msgs))
   (vec messages)
   injections))

(defn strip-internal-message-fields
  "Remove ECA-only top-level message metadata before serialization."
  [message]
  (apply dissoc message [:created-at :content-id]))

(defn sanitize-outbound-message
  "Strip internal ECA metadata from a single outbound message."
  [message]
  (strip-internal-message-fields message))

(defn sanitize-outbound-messages
  "Apply request-local prompt injections, then strip internal ECA top-level
   metadata from an outbound message collection before provider serialization."
  ([messages]
   (sanitize-outbound-messages messages nil))
  ([messages injections]
   (mapv sanitize-outbound-message (apply-injections messages injections))))
