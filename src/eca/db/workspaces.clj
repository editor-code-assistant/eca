(ns eca.db.workspaces
  "The chats of every workspace ECA cached, for /resume and the chats
   listed across workspaces."
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [eca.cache :as cache]
   [eca.db.chats :as db.chats]
   [eca.db.io :as db.io]
   [eca.db.migration :as db.migration]
   [eca.logger :as logger]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[DB]")

(def ^:private workspaces-hash-length 8)

(defn ^:private workspace-dir-name-parts
  "Splits a workspace cache dir name into [approx-name hash]. The dir name is
   '<sanitized-project-name>_<hash>' or just '<hash>'; the 8-char base64url
   hash may itself contain '_', so it is parsed positionally (last 8 chars)
   rather than by splitting on '_'. Unknown formats yield a nil hash."
  [^String dir-name]
  (let [n (count dir-name)]
    (cond
      (= n workspaces-hash-length)
      [dir-name dir-name]

      (and (> n (inc workspaces-hash-length))
           (= \_ (.charAt dir-name (- n (inc workspaces-hash-length)))))
      [(subs dir-name 0 (- n (inc workspaces-hash-length)))
       (subs dir-name (- n workspaces-hash-length))]

      :else [dir-name nil])))

(defn ^:private read-workspace-dir-chats
  "Reads the persisted chats of one workspace cache `dir`: the v7 chats index
   when present, falling back to the legacy pre-v7 whole-workspace blob
   (workspaces never reopened since v7). Returns
   {:workspaces [path...]-or-nil :chats {chat-id meta}} or nil when the dir
   has no readable chats."
  [^java.io.File dir metrics]
  (try
    (let [index-file (io/file dir "chats" "index.transit.json")
          legacy-file (io/file dir "db.transit.json")]
      (cond
        (fs/exists? index-file)
        (when-let [index (db.io/read-cache index-file db.io/chats-version metrics)]
          {:workspaces (not-empty (vec (:workspaces index)))
           :chats (db.chats/stamp-chat-ids (:chats index))})

        (fs/exists? legacy-file)
        (when-let [chats (db.migration/read-legacy-workspace-cache legacy-file metrics)]
          {:workspaces nil
           :chats (update-vals chats db.chats/chat-list-meta)})))
    (catch Throwable e
      (logger/warn logger-tag (str "Could not read chats from workspace cache dir " dir) e)
      nil)))

(defn ^:private recover-unknown-workspaces
  "Fills `:workspaces` of groups lacking persisted paths by *verifying*
   candidates against the group's one-way dir `:hash`: sibling directories of
   known workspace roots (`extra-roots` plus the groups' own persisted paths)
   are hashed with the same fn that named the cache dir, so a match recovers
   the exact original root. Guess-free (no match, no label) and single-root
   workspaces only: multi-root sets are combinatorial."
  [groups extra-roots]
  (let [unresolved (filter (every-pred (comp nil? :workspaces) :hash) groups)
        known-roots (into (set extra-roots) (mapcat :workspaces) groups)]
    (if (or (empty? unresolved) (empty? known-roots))
      groups
      (let [candidate-dirs (into #{}
                                 (comp (keep #(try (some-> (fs/parent (fs/file %)) str)
                                                   (catch Throwable _ nil)))
                                       (distinct)
                                       (mapcat (fn [parent]
                                                 (try
                                                   (->> (fs/list-dir parent)
                                                        (filter fs/directory?)
                                                        (map str))
                                                   (catch Throwable _ nil)))))
                                 known-roots)
            hash->path (reduce (fn [m path]
                                 (assoc m (cache/workspace-paths-hash [path]) path))
                               {}
                               candidate-dirs)]
        (mapv (fn [{:keys [workspaces hash] :as group}]
                (if-let [path (and (nil? workspaces) (get hash->path hash))]
                  (assoc group :workspaces [path])
                  group))
              groups)))))

(defn list-all-workspaces-chats
  "Lists persisted chats across ALL workspace cache dirs under the global
   cache dir, so a chat can be located without opening each workspace. The
   current workspace's chats come from memory (freshest); other dirs are read
   from disk, tolerating unreadable ones. Subagent chats are excluded and a
   chat id duplicated across dirs (legacy duplicate dirs, #558) keeps only
   its most recent copy. Groups without persisted paths get a hash-verified
   recovery attempt (see `recover-unknown-workspaces`).

   Returns workspace groups sorted by most recent chat activity:
   [{:name string            ;; dir-name derived label, approximate
     :hash string-or-nil     ;; dir-name hash key
     :workspaces [path ...]  ;; exact roots when known (persisted, current or recovered)
     :current? boolean
     :chats [chat-meta ...]} ;; sorted by recency desc
    ...]"
  [db metrics]
  (let [workspaces (db.io/db-workspaces db)
        current-dir (cache/workspace-cache-dir workspaces shared/uri->filename)
        current-dir-path (.getAbsolutePath current-dir)
        current-paths (not-empty (vec (cache/sorted-workspace-paths workspaces shared/uri->filename)))
        [current-name current-hash] (workspace-dir-name-parts (.getName current-dir))
        current-group {:name current-name
                       :hash current-hash
                       :workspaces current-paths
                       :current? true
                       :chats (vec (vals (db.chats/db->index-entries db)))}
        other-groups (keep (fn [^java.io.File dir]
                             (when (not= (.getAbsolutePath dir) current-dir-path)
                               (when-let [{:keys [workspaces chats]} (read-workspace-dir-chats dir metrics)]
                                 (let [[approx-name hash] (workspace-dir-name-parts (.getName dir))]
                                   {:name approx-name
                                    :hash hash
                                    :workspaces workspaces
                                    :current? false
                                    :chats (vec (vals chats))}))))
                           (cache/workspace-cache-dirs))
        groups (mapv #(update % :chats (fn [chats] (vec (remove :subagent chats))))
                     (cons current-group other-groups))
        ;; Duplicated chat ids across dirs keep only the most recent copy.
        winners (reduce (fn [acc idx]
                          (reduce (fn [acc chat]
                                    (let [id (:id chat)
                                          existing (get acc id)]
                                      (if (or (nil? existing)
                                              (> (db.io/chat-recency chat) (db.io/chat-recency (:chat existing))))
                                        (assoc acc id {:idx idx :chat chat})
                                        acc)))
                                  acc
                                  (:chats (nth groups idx))))
                        {}
                        (range (count groups)))]
    (-> (->> groups
             (keep-indexed (fn [idx group]
                             (let [chats (->> (:chats group)
                                              (filter #(= idx (:idx (get winners (:id %)))))
                                              (sort-by db.io/chat-recency #(compare %2 %1))
                                              (vec))]
                               (when (seq chats)
                                 (assoc group :chats chats)))))
             (sort-by #(db.io/chat-recency (first (:chats %))) #(compare %2 %1))
             (vec))
        (recover-unknown-workspaces current-paths))))
