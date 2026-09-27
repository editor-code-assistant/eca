(ns eca.features.memory.retrieval
  "Per-turn memory index retrieval: builds the always-on memory context block
   injected into the chat prompt."
  (:require
   [eca.db :as db]
   [eca.features.memory :as memory]
   [eca.features.memory.render :as render]
   [eca.logger :as logger]
   [eca.metrics :as metrics]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[MEMORY]")

(defn provide
  "Return the per-turn memory index and counts, or nil. When entries exist
   but no index text fits, return counts without content for client reporting.
   Failures are contained so a memory problem never breaks prompt sending.
   Skipped when memory is disabled or the chat is a subagent."
  [{:keys [chat-id db config metrics]}]
  (logger/with-chat-context chat-id (db/parent-chat-id db chat-id)
    (try
      (when (and (memory/enabled? config)
                 (not (get-in db [:chats chat-id :subagent])))
        (memory/pre-create-dirs! db)
        (let [entries (memory/scan-memories db config)
              rendered (render/index-context {:entries entries
                                              :max-entries (memory/index-max-entries config)
                                              :max-tokens (memory/index-max-tokens config)})]
          (when rendered
            ;; The write guidance and the memory dir listing are NOT part of
            ;; this block: both are session-stable and live in the static
            ;; system instructions (see
            ;; eca.features.prompt/memory-guidance-section). Only the volatile
            ;; per-turn entry lines ride the tail injection.
            (logger/info logger-tag "Memory index context prepared"
                         {:injected-count (:item-count rendered)
                          :token-cost (:tokens rendered)})
            (when metrics
              (metrics/count-up! "memory-index-injected" {} metrics)))
          (or rendered
              (when (seq entries)
                {:item-count 0 :total-count (count entries) :items []}))))
      (catch Throwable e
        (logger/warn logger-tag "Memory index context failed; continuing without memory" e)
        nil))))
