(ns eca.features.memory.delivery
  "Chooses the memory block for the next normal user prompt: a full index once
   per window, then only the notes that changed from outside this chat, sent
   as a short change list or as a new full index, whichever is smaller.

   The state lives on the stored user message that carries a block (the
   `:memory` message key). There is no chat-level state, so clear, rollback,
   fork and resume need no extra code. A compaction starts a new window
   without a state, so the next block is a full index; a compaction in the
   middle of a turn builds that block when it resumes the task."
  (:require
   [babashka.fs :as fs]
   [eca.db :as db]
   [eca.features.memory :as memory]
   [eca.features.memory.render :as render]
   [eca.features.memory.usage :as usage]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.metrics :as metrics]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[MEMORY]")

(defn ^:private current-state
  "path -> `{:hash :name :label}` for every valid note in `entries`. The map
   holds ALL notes, also the notes that the index caps hide, so a later change
   list can report them and name deleted ones."
  [entries]
  (into {}
        (map (fn [{:keys [path name label] :as entry}]
               [path {:hash (memory/note-hash entry) :name name :label label}]))
        entries))

(defn ^:private last-state
  "`[state messages-after]` for the last message of `window` that carries a
   `:memory` state: that state, and the messages after it (the own writes that
   no stored block reflects yet). nil when no message carries a state."
  [window]
  (let [window (vec window)]
    (when-let [idx (last (keep-indexed (fn [i message] (when (:memory message) i)) window))]
      [(:memory (nth window idx)) (subvec window (inc idx))])))

(defn ^:private own-write-hashes
  "path -> note hash right after this chat's last write of that memory path in
   `messages` (nil: the write left no note there, as for the source of a move).
   The native file tools put `:memory-hashes` in the result of a write that
   happened (see `eca.features.tools.filesystem`). Shell commands, MCP edit
   tools, subagents and directory moves add nothing, so their changes count as
   external, which is the safe direction. Pruning of old tool outputs keeps
   these hashes, including when compaction is blocked or fails."
  [messages]
  (transduce (comp (filter #(= "tool_call_output" (:role %)))
                   (keep #(get-in % [:content :output :memory-hashes])))
             merge
             {}
             messages))

(defn ^:private changed-paths
  "Every path whose note appeared, changed (hash) or disappeared between
   `previous` and `current`."
  [previous current]
  (into #{}
        (remove #(and (contains? previous %)
                      (contains? current %)
                      (= (:hash (previous %)) (:hash (current %)))))
        (concat (keys previous) (keys current))))

(defn ^:private change-items
  "Items of the change list: current entries of changed paths in scan order,
   then the notes that left the index (their `:name` and `:label` come from
   `previous`). Only files in `skipped` are invalid. Other existing files
   may have left the scan because a workspace root was removed."
  [entries external previous skipped]
  (let [current-paths (into #{} (map :path) entries)
        skipped-paths (into #{} (map :path) skipped)]
    (into
     (vec (keep (fn [{:keys [path] :as entry}]
                  (when (contains? external path)
                    {:label (:label entry) :name (:name entry) :entry entry}))
                entries))
     (map (fn [path]
            {:label (get-in previous [path :label])
             :name (get-in previous [path :name])
             :invalid? (contains? skipped-paths path)
             :unindexed? (fs/exists? (fs/file path))}))
     (remove current-paths external))))

(defn ^:private choose-block
  "The smaller of the change list for `external` (not empty) and the full
   index; on a tie the full index wins, because it is never stale. Returns
   `{:text <string> :kind <string>}`."
  [entries external previous full-index skipped]
  (let [changes (render/changes-context (change-items entries external previous skipped))
        change-tokens (shared/estimate-tokens changes)
        full-tokens (:tokens full-index)]
    (if (and full-tokens (>= change-tokens full-tokens))
      {:text (:content full-index) :kind "full"}
      {:text changes :kind "changes"})))

(defn ^:private render-full-index
  "The full index of `entries` with the configured caps, or nil when nothing
   fits (see `render/index-context`)."
  [entries config]
  (render/index-context {:entries entries
                         :max-entries (memory/index-max-entries config)
                         :max-tokens (memory/index-max-tokens config)}))

(defn turn-block
  "Decide the memory block for the next normal user prompt of the chat.

   `window` is what the model will see: `shared/messages-after-last-compact-marker`
   of the chat messages. Returns
   `{:block {:text <string> :memory <state map>} or nil
     :entries <scan entries>
     :render <full index render or nil>}`,
   or nil on any error: a memory problem must never stop a prompt (fail open)."
  [{:keys [chat-id db config metrics window]}]
  (logger/with-chat-context chat-id (db/parent-chat-id db chat-id)
    (try
      (when (memory/enabled-for-chat? db config chat-id)
        (memory/pre-create-dirs! db)
        (let [entries (usage/ranked-memories db)
              current (current-state entries)
              full-index (render-full-index entries config)
              [previous after-previous] (last-state window)
              text+kind (if (nil? previous)
                          (when (:content full-index)
                            {:text (:content full-index) :kind "full"})
                          (let [own (own-write-hashes after-previous)
                                ;; Own only while the note is still what this chat
                                ;; wrote: a later outside edit moves the hash.
                                own? #(and (contains? own %)
                                           (= (own %) (get-in current [% :hash])))
                                external (into #{} (remove own?) (changed-paths previous current))]
                            (when (seq external)
                              (choose-block entries external previous full-index
                                            (memory/skipped-files db)))))]
          (when (and text+kind metrics)
            (metrics/count-up! "memory-block-added" {:kind (:kind text+kind)} metrics))
          {:block (when text+kind
                    {:text (:text text+kind)
                     :memory current})
           :entries entries
           :render full-index}))
      (catch Throwable e
        (logger/warn logger-tag "Memory block failed; continuing without memory" e)
        nil))))

(defn index-loaded-params
  "Payload of the `memory/indexLoaded` notification: the notes a full index
   would show NOW, not an exact record of what the model saw (own writes and
   change lists can differ)."
  [chat-id entries rendered]
  {:chat-id chat-id
   :count (or (:item-count rendered) 0)
   :total-count (count entries)
   :items (or (:items rendered) [])})

(defn notify-index-loaded!
  "Scans the store and sends one `memory/indexLoaded` notification for `chat-id`.
   Nothing when memory is disabled or the chat is a subagent; a scan error sends
   nothing (fail open, logged). No fingerprint, no dedupe: this describes the
   current store, not the exact content the model saw."
  [{:keys [chat-id db config messenger]}]
  (when (memory/enabled-for-chat? db config chat-id)
    (logger/with-chat-context chat-id (db/parent-chat-id db chat-id)
      (try
        (let [entries (usage/ranked-memories db)]
          (messenger/memory-index-loaded messenger
                                         (index-loaded-params chat-id entries (render-full-index entries config))))
        (catch Throwable e
          (logger/warn logger-tag "Memory index notification failed" e))))))
