(ns eca.modules.memory.delivery
  "Builds the memory block for a user request: a full index once per context
   window, then a change list (or fresh index, whichever is shorter). State
   lives in the previous block's `:data` on the message—no separate cache."
  (:require
   [babashka.fs :as fs]
   [eca.db :as db]
   [eca.logger :as logger]
   [eca.modules.memory.render :as m.memory.render]
   [eca.modules.memory.store :as m.memory.store]
   [eca.modules.memory.usage :as m.memory.usage]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[MEMORY]")

(defn ^:private current-state
  "Map of path -> `{:hash :name :label}` for all valid notes, including hidden ones."
  [entries]
  (into {}
        (map (fn [{:keys [path name label] :as entry}]
               [path {:hash (m.memory.store/note-hash entry) :name name :label label}]))
        entries))

(defn ^:private block-state [message module-id]
  (let [content (:content message)]
    (when (sequential? content)
      (some (fn [block]
              (when (and (map? block) (= module-id (:module block)))
                (:notes (:data block))))
            (reverse content)))))

(defn ^:private last-state
  "Returns `[state messages-after]` for the last block of `module-id` in `window`, or nil."
  [window module-id]
  (let [window (vec window)]
    (when-let [idx (last (keep-indexed (fn [i message] (when (block-state message module-id) i)) window))]
      [(block-state (nth window idx) module-id) (subvec window (inc idx))])))

(def ^:private write-tool-path-keys
  {"write_file" ["path"]
   "edit_file" ["path"]
   "move_file" ["source" "destination"]})

(defn ^:private arg-value
  [arguments k]
  (when (map? arguments)
    (or (get arguments k)
        (get arguments (keyword k)))))

(defn ^:private targeted-note-paths
  "Known note paths (in `previous` or `current`) targeted by non-rejected
   file-write tools in `messages`, so external edits and `A -> B -> A` reversals
   still refresh on the next turn."
  [messages db previous current]
  (into #{}
        (comp (filter (fn [{:keys [role content]}]
                        (and (= "tool_call_output" role)
                             (map? content)
                             (= "eca" (:server content))
                             (not (:rejection-reason content)))))
              (mapcat (fn [{:keys [content]}]
                        (when-let [path-keys (get write-tool-path-keys (:name content))]
                          (keep (fn [k]
                                  (when-let [raw (arg-value (:arguments content) k)]
                                    (when-let [{:keys [path]} (m.memory.store/locate-memory-path db raw)]
                                      (when (or (contains? previous path)
                                                (contains? current path))
                                        path))))
                                path-keys)))))
        messages))

(defn ^:private changed-paths [previous current targeted]
  (into targeted
        (filter (fn [path]
                  (not= (:hash (get previous path))
                        (:hash (get current path)))))
        (concat (keys previous) (keys current))))

(defn ^:private change-items
  "Change items for `external` paths: current entries in scan order, then notes
   from `previous` that left the index (notes created and deleted between scans
   are not reported)."
  [entries external previous skipped]
  (let [current-paths (into #{} (map :path) entries)
        skipped-paths (into #{} (map :path) skipped)]
    (into
     (vec (keep (fn [{:keys [path] :as entry}]
                  (when (contains? external path)
                    {:label (:label entry) :name (:name entry) :entry entry}))
                entries))
     (map (fn [path]
            (let [{:keys [label name]} (get previous path)]
              {:label label
               :name name
               :invalid? (contains? skipped-paths path)
               :unindexed? (fs/exists? (fs/file path))})))
     (remove current-paths external))))

(defn ^:private choose-block
  "Picks the shorter of the change list and full index (full index wins ties)."
  [entries external previous full-index skipped]
  (let [changes (m.memory.render/changes-context (change-items entries external previous skipped))
        change-tokens (shared/estimate-tokens changes)
        full-tokens (:tokens full-index)]
    (if (and full-tokens (>= change-tokens full-tokens))
      (:content full-index)
      changes)))

(defn render-full-index
  "Renders the capped full index of `entries`, or nil when nothing fits."
  [entries config]
  (m.memory.render/index-context {:entries entries
                                  :max-entries (m.memory.store/index-max-entries config)
                                  :max-tokens (m.memory.store/index-max-tokens config)}))

(defn turn-block
  "Returns `{:text :notes}` for the next user request in `window`, or nil when
   unchanged, disabled (`maxTokens <= 0`), or on error (fail-open)."
  [{:keys [chat-id db config window module-id]}]
  (logger/with-chat-context chat-id (db/parent-chat-id db chat-id)
    (try
      (m.memory.store/pre-create-dirs! db)
      (when (pos? (m.memory.store/index-max-tokens config))
        (let [{:keys [entries skipped]} (m.memory.usage/ranked-scan db)
              current (current-state entries)
              [previous after-previous] (last-state window module-id)
              text (if (nil? previous)
                     (when (seq current)
                       (:content (render-full-index entries config)))
                     (let [targeted (targeted-note-paths after-previous db previous current)
                           external (changed-paths previous current targeted)]
                       (when (seq external)
                         (choose-block entries external previous (render-full-index entries config) skipped))))]
          (when text
            {:text text :notes current})))
      (catch Throwable e
        (logger/warn logger-tag "Memory block failed; continuing without memory" e)
        nil))))
