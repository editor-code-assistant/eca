(ns eca.features.memory.delivery
  "Chooses the memory block for the next normal user prompt: a full index once
   per window, then only the notes that changed from outside this chat, sent
   as a short change list or as a new full index, whichever is smaller.

   The state lives on the stored user message that carries a block (the
   `:memory` message key). There is no chat-level state, so compaction, clear,
   rollback, fork and resume need no extra code."
  (:require
   [eca.db :as db]
   [eca.digest :as digest]
   [eca.features.memory :as memory]
   [eca.features.memory.render :as render]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.metrics :as metrics]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[MEMORY]")

(def ^:private own-write-path-keys
  "Native tools that write files, and the argument keys that hold the paths.
   `preview_file_change` does not write; subagent writes belong to the subagent
   chat; shell commands and MCP edit tools are not attributed to this chat.
   All of those count as external, which is the safe direction."
  {"write_file" ["path"]
   "edit_file" ["path"]
   "move_file" ["source" "destination"]})

(defn ^:private entry-hash
  "Hash of the knowledge of one scan entry: name, type, description and body.
   Tags and file times are excluded, so a retag (or a file touch) is not a
   change."
  [{:keys [name type description body]}]
  (digest/sha-256-hex (pr-str [name type description body])))

(defn current-state
  "path -> `{:hash :name :label}` for every valid note in `entries`. The map
   holds ALL notes, also the notes that the index caps hide, so a later change
   list can report them and name deleted ones."
  [entries]
  (into {}
        (map (fn [{:keys [path name label] :as entry}]
               [path {:hash (entry-hash entry) :name name :label label}]))
        entries))

(defn ^:private previous-state
  "The `:memory` value of the last message that carries one, or nil."
  [messages]
  (->> messages (keep :memory) last))

(defn ^:private successful-write?
  "A tool call output that really wrote: no `:error` and no error inside
   `:output` (a rejected call has its error there)."
  [content]
  (and (not (:error content))
       (not (get-in content [:output :error]))))

(defn own-write-paths
  "Paths this chat wrote in `messages` with a successful native write tool.
   Arguments come from JSON with string keys; keyword keys are turned into
   string keys too, so the lookup works for both."
  [db config messages]
  (->> messages
       (filter #(= "tool_call_output" (:role %)))
       (map :content)
       (filter #(and (= "eca" (:server %))
                     (contains? own-write-path-keys (:name %))
                     (successful-write? %)))
       (mapcat (fn [content]
                 (let [arguments (:arguments content)
                       arguments (if (map? arguments) (update-keys arguments name) {})]
                   (keep #(get arguments %) (own-write-path-keys (:name content))))))
       (filter #(memory/memory-path? db config %))
       vec))

(defn ^:private messages-after-last-state
  "Messages after the last message that carries a `:memory` state: the own
   writes that are not yet reflected in any stored block."
  [window]
  (let [window (vec window)
        idx (last (keep-indexed (fn [i message] (when (:memory message) i)) window))]
    (if idx
      (subvec window (inc idx))
      [])))

(defn ^:private changed-paths
  "Every path whose note appeared, changed (hash) or disappeared between
   `previous` and `current`."
  [previous current]
  (let [new-paths (remove #(contains? previous %) (keys current))
        touched (keep (fn [[path state]]
                        (when (and (contains? current path)
                                   (not= (:hash state) (:hash (get current path))))
                          path))
                      previous)
        deleted (remove #(contains? current %) (keys previous))]
    (into #{} (concat new-paths touched deleted))))

(defn ^:private change-items
  "Items of the change list: current entries of changed paths in scan order,
   then the deleted notes (their `:name` and `:label` come from `previous`)."
  [entries external previous]
  (let [current-paths (into #{} (map :path) entries)]
    (into
     (vec (keep (fn [{:keys [path] :as entry}]
                  (when (contains? external path)
                    {:label (:label entry) :name (:name entry) :entry entry}))
                entries))
     (map (fn [path]
            {:label (get-in previous [path :label])
             :name (get-in previous [path :name])}))
     (remove current-paths external))))

(defn ^:private choose-block
  "The smaller of the change list and the full index; on a tie the full index
   wins, because it is never stale. Returns `{:text <string> :kind <string>}`
   or nil when there is nothing to send."
  [entries external previous full-index]
  (when (seq external)
    (let [changes (render/changes-context (change-items entries external previous))
          change-tokens (shared/estimate-tokens changes)
          full-tokens (:tokens full-index)]
      (if (and full-tokens (>= change-tokens full-tokens))
        {:text (:content full-index) :kind "full"}
        {:text changes :kind "changes"}))))

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
      (when (and (memory/enabled? config)
                 (not (get-in db [:chats chat-id :subagent])))
        (memory/pre-create-dirs! db)
        (let [entries (memory/scan-memories db config)
              current (current-state entries)
              full-index (render-full-index entries config)
              previous (previous-state window)
              text+kind (if (nil? previous)
                          (when (:content full-index)
                            {:text (:content full-index) :kind "full"})
                          (let [own (into #{} (own-write-paths db config (messages-after-last-state window)))
                                external (into #{} (remove own) (changed-paths previous current))]
                            (choose-block entries external previous full-index)))]
          (when text+kind
            (when metrics
              (metrics/count-up! "memory-block-added" {:kind (:kind text+kind)} metrics)))
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
  (when (and (memory/enabled? config)
             (not (get-in db [:chats chat-id :subagent])))
    (logger/with-chat-context chat-id (db/parent-chat-id db chat-id)
      (try
        (let [entries (memory/scan-memories db config)]
          (messenger/memory-index-loaded messenger
                                         (index-loaded-params chat-id entries (render-full-index entries config))))
        (catch Throwable e
          (logger/warn logger-tag "Memory index notification failed" e))))))

