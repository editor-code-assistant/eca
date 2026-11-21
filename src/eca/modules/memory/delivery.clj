(ns eca.modules.memory.delivery
  "Delivers a full index for a new context window or new index limits.
   For note changes, delivers the shorter of the change list and full index.
   State lives in the previous block's `:data` on the message."
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
              (when (= module-id (:module block))
                (:data block)))
            (reverse content)))))

(defn ^:private last-state
  "Returns `[state messages-after]` for the last block of `module-id` in the
   `window` vector, or nil."
  [window module-id]
  (when-let [idx (last (keep-indexed (fn [i message] (when (block-state message module-id) i)) window))]
    [(block-state (nth window idx) module-id) (subvec window (inc idx))]))

(def ^:private write-tool-path-keys
  {"write_file" ["path"]
   "edit_file" ["path"]
   "move_file" ["source" "destination"]})

(defn ^:private arg-value
  [arguments k]
  (or (get arguments k)
      (get arguments (keyword k))))

(defn ^:private targeted-note-paths
  "Known note paths (in `previous` or `current`) targeted by non-rejected
   file-write tools in `messages`, so external edits and `A -> B -> A` reversals
   still refresh on the next turn."
  [messages db previous current]
  (into #{}
        (comp (filter (fn [{:keys [role content]}]
                        (and (= "tool_call_output" role)
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
        (filter #(not= (:hash (get previous %))
                       (:hash (get current %))))
        (concat (keys previous) (keys current))))

(defn ^:private change-items
  "Change items for `external` paths: current entries in scan order, then notes
   from `previous` that left the index (notes created and deleted between scans
   are not reported)."
  [entries external previous skipped]
  (let [current-paths (into #{} (map :path) entries)
        skipped-paths (into #{} (map :path) skipped)]
    (concat
     (keep (fn [{:keys [path label] :as entry}]
             (when (contains? external path)
               {:label label :entry entry}))
           entries)
     (map (fn [path]
            (let [{:keys [label name]} (get previous path)]
              {:label label
               :name name
               :invalid? (contains? skipped-paths path)
               :unindexed? (fs/exists? path)}))
          (remove current-paths external)))))

(defn ^:private choose-block
  "Picks the shorter of the change list and full index (full index wins ties)."
  [entries external previous full-index skipped]
  (let [changes (m.memory.render/changes-context (change-items entries external previous skipped))
        full-tokens (:tokens full-index)]
    (if (and full-tokens (>= (shared/estimate-tokens changes) full-tokens))
      (:content full-index)
      changes)))

(defn index-limits [config]
  {:max-entries (m.memory.store/index-max-entries config)
   :max-tokens (m.memory.store/index-max-tokens config)})

(defn turn-block
  "Returns `{:text :data}` for the next user request in `window`, or nil when
   unchanged, disabled (`maxTokens <= 0`), or on error (fail-open)."
  [{:keys [chat-id db config window module-id]}]
  (logger/with-chat-context chat-id (db/parent-chat-id db chat-id)
    (try
      (m.memory.store/pre-create-dirs! db)
      (let [limits (index-limits config)]
        (when (pos? (:max-tokens limits))
          (let [{:keys [entries skipped]} (m.memory.usage/ranked-scan db)
                current (current-state entries)
                [{previous :notes old-limits :limits} after-previous] (last-state window module-id)
                refresh? (not= limits old-limits)
                changed (when previous
                          (changed-paths previous current (targeted-note-paths after-previous db previous current)))]
            (when (or (seq changed) (and refresh? (seq entries)))
              (let [index (m.memory.render/index-context (assoc limits :entries entries))]
                (when-let [text (or (when refresh? (:content index))
                                    (when (seq changed) (choose-block entries changed previous index skipped)))]
                  {:text text :data {:notes current :limits limits}}))))))
      (catch Throwable e
        (logger/warn logger-tag "Memory block failed; continuing without memory" e)
        nil))))
