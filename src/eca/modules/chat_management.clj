(ns eca.modules.chat-management
  "The `:eca/chat-management` module: fork a chat, ask a side question in a
   fork, and resume, list, delete, export and import chats, or dump one for
   debugging."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as string]
   [eca.cache :as cache]
   [eca.config :as config]
   [eca.db.chats :as db.chats]
   [eca.db.io :as db.io]
   [eca.db.migration :as db.migration]
   [eca.features.chat.history :as f.chat.history]
   [eca.features.chat.ops :as f.chat.ops]
   [eca.logger :as logger]
   [eca.message-sanitize :as message-sanitize]
   [eca.messenger :as messenger]
   [eca.shared :as shared :refer [multi-str]])
  (:import
   [clojure.lang PersistentVector]))

(set! *warn-on-reflection* true)

(defn btw-title [question]
  (let [question (string/trim question)]
    (str "btw: " (if (> (count question) 40)
                   (str (subs question 0 40) "...")
                   question))))

(defn ^:private fork-chat!
  "Creates a new chat copying `chat`'s history (sanitized) and base settings
   plus `extra-copied-keys`, persists it and notifies the client.
   Returns `{:chat new-chat :replay-messages msgs}`, where `msgs` are the
   messages to send to the client: only the newest page for clients
   supporting incremental updates, announced in `chat/opened` `:history`."
  [chat new-title extra-copied-keys {:keys [db* messenger metrics]}]
  (let [new-id (str (random-uuid))
        now (System/currentTimeMillis)
        new-chat (merge {:id new-id
                         :title new-title
                         :status :idle
                         :created-at now
                         :updated-at now
                         :model (:model chat)
                         :last-api (:last-api chat)
                         :messages (f.chat.ops/drop-dangling-tool-calls (vec (:messages chat)))
                         :prompt-finished? true}
                        ;; the modules' chat state goes with the chat
                        (select-keys chat (conj (vec extra-copied-keys) :modules)))
        messages (:messages new-chat)
        page (f.chat.history/server-created-page @db* messages)]
    (swap! db* assoc-in [:chats new-id] new-chat)
    (db.chats/save-chat! @db* new-id metrics)
    (messenger/chat-opened messenger
                           (shared/assoc-some {:chat-id new-id :title new-title}
                                              :history (when page
                                                         (f.chat.history/page-meta page messages))))
    {:chat new-chat
     :replay-messages (if page (:messages page) messages)}))

(defn ^:private fork-command
  [{:keys [db* chat-id] :as chat-ctx} _args]
  (let [db @db*
        chat (get-in db [:chats chat-id])
        {{new-id :id new-title :title} :chat new-messages :replay-messages}
        (fork-chat! chat (f.chat.ops/fork-title (:title chat)) [] chat-ctx)]
    {:type :chat-messages
     :chats {new-id {:messages new-messages
                     :title new-title}
             chat-id {:messages [{:role "system"
                                  :content [{:type :text
                                             :text (str "Chat forked to: " new-title)}]}]}}}))

(defn ^:private btw-command
  [{:keys [chat-id db*] :as chat-ctx} _args]
  (let [question (string/trim (string/replace-first (:message chat-ctx) #"^/btw\s*" ""))]
    (if (string/blank? question)
      ;; :side-prompt without a target only shows the notice; unlike
      ;; :chat-messages it never finishes a running turn (mid-run /btw).
      {:type :side-prompt
       :notice-text "Usage: /btw <prompt>. Asks a side question in a forked chat, keeping this chat history clean."}
      (let [chat (get-in @db* [:chats chat-id])
            {{new-id :id new-title :title} :chat new-messages :replay-messages}
            (fork-chat! chat (btw-title question)
                        [:variant :trust :remembered-approvals :prompt-cache :startup-context]
                        chat-ctx)]
        {:type :side-prompt
         :target-chat-id new-id
         :target-title new-title
         :target-messages new-messages
         :message question
         :notice-text (str "Side question forked to: " new-title)}))))

(defn ^:private resume-command
  [{:keys [chat-id db* config messenger metrics] :as chat-ctx} {args :argv}]
  (let [db @db*
        chats (into {}
                    (filter #(and (not= chat-id (first %))
                                  (not (:subagent (second %)))))
                    (:chats db))
        chats-ids (vec (sort-by #(:created-at (get chats %)) (keys chats)))
        selected-chat-id (try (if (= "latest" (first args))
                                (count chats-ids)
                                (parse-long (first args)))
                              (catch Exception _ nil))
        selected-chat-id (when selected-chat-id (nth chats-ids (dec selected-chat-id) nil))
        chat-message-fn (fn [text]
                          {:type :chat-messages
                           :chats {chat-id {:messages [{:role "system" :content [{:type :text :text text}]}]}}})]
    (cond
      (empty? chats)
      (chat-message-fn "No past chats found to resume")

      (string/blank? (first args))
      (chat-message-fn (multi-str
                        "Chats:"
                        ""
                        "ID - Created at - Title"
                        (reduce
                         (fn [s chat-id]
                           (let [chat (get chats chat-id)
                                 {:keys [user-message-count flags]} (db.chats/chat-list-meta chat)
                                 msgs-count (or user-message-count 0)]
                             ;; List every persisted chat, even ones with zero user
                             ;; messages (e.g. a chat that hit a provider error very
                             ;; early, or one rolled back to empty). The user can
                             ;; resume any of them and rollback to recover.
                             (str s (format "%s - %s - %s%s\n"
                                            (inc (.indexOf ^PersistentVector chats-ids chat-id))
                                            (shared/ms->presentable-date (:created-at chat) "dd/MM/yyyy HH:mm")
                                            (or (:title chat) (format "No chat title (%s user messages)" msgs-count))
                                            (if (seq flags)
                                              (str " 🚩 " (string/join ", " flags))
                                              "")))))
                         ""
                         chats-ids)
                        "Run `/resume <chat-id>` or `/resume latest`"))

      (not selected-chat-id)
      (chat-message-fn "Chat ID not found.")

      :else
      (let [_ (db.chats/hydrate-chat! db* selected-chat-id metrics)
            chat (get-in @db* [:chats selected-chat-id])]
        (swap! db* assoc-in [:chats chat-id] (assoc chat :id chat-id))
        (swap! db* update-in [:chats chat-id] dissoc :prompt-finished? :auto-compacting? :continuing? :compacting?)
        (swap! db* assoc-in [:chats chat-id :prompt-id] (:prompt-id chat-ctx))
        ;; The resumed content now lives under chat-id; drop the
        ;; old id from memory and cache, tombstoning it so peer
        ;; index merges cannot resurrect it.
        (swap! db* (fn [db]
                     (-> db
                         (update :chats dissoc selected-chat-id)
                         (update :deleted-chat-ids (fnil conj #{}) selected-chat-id))))
        (db.chats/save-chat! @db* chat-id metrics)
        (db.chats/delete-chat-from-cache! @db* selected-chat-id metrics)
        ;; Align the client's selected model with the resumed chat
        ;; so the LLM call keeps using the chat's original model. #417
        (config/notify-selected-model-changed! (:model chat) db* messenger config (:variant chat) chat-id)
        ;; Align the client's trust indicator with the resumed
        ;; chat's persisted :trust so the icon matches the
        ;; auto-approval behavior the server will apply. #426
        (config/notify-selected-trust-changed! (:trust chat) db* messenger chat-id)
        ;; Same for the agent, so the client's current agent isn't
        ;; sent on the next prompt instead of the resumed chat's one.
        (when-let [chat-agent (:agent chat)]
          (config/notify-selected-agent-changed!
           (config/validate-agent-name chat-agent config) db* messenger chat-id))
        {:type :chat-messages
         :clear-before? true
         :chats {chat-id {:title (:title chat)
                          :messages (concat [{:role "system" :content [{:type :text :text (str "Resuming chat: " selected-chat-id)}]}]
                                            (:messages chat))}}}))))

;; /chats: the chats of every workspace ECA cached.

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
      (logger/warn "[DB]" (str "Could not read chats from workspace cache dir " dir) e)
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

(defn ^:private list-all-workspaces-chats
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

(defn ^:private chats-command
  [{:keys [db* chat-id metrics]} {args :argv}]
  (let [db @db*
        title-filter (string/lower-case (string/trim (string/join " " args)))
        matches? (fn [{:keys [title]}]
                   (or (string/blank? title-filter)
                       (string/includes? (string/lower-case (or title "")) title-filter)))
        groups (->> (list-all-workspaces-chats db metrics)
                    (keep (fn [group]
                            (let [chats (->> (:chats group)
                                             (remove #(= chat-id (:id %)))
                                             (filterv matches?))]
                              (when (seq chats)
                                (assoc group :chats chats)))))
                    (vec))
        chat-line (fn [{:keys [title updated-at created-at user-message-count message-count model id]}]
                    (format "- %s - %s (%s msgs%s) `%s`"
                            (if-let [ms (or updated-at created-at)]
                              (shared/ms->presentable-date ms "dd/MM/yyyy HH:mm")
                              "unknown date")
                            (or title (format "No chat title (%s user messages)" (or user-message-count 0)))
                            (or message-count 0)
                            (if model (str ", " model) "")
                            id))
        group-section (fn [{:keys [workspaces current?] approx-name :name :as group}]
                        (str "**" (if workspaces (string/join ", " workspaces) (str "~" approx-name)) "**"
                             (when current? " (current)")
                             ":\n"
                             (string/join "\n" (map chat-line (:chats group)))))
        chat-message-fn (fn [text]
                          {:type :chat-messages
                           :chats {chat-id {:messages [{:role "system" :content [{:type :text :text text}]}]}}})]
    (if (empty? groups)
      (chat-message-fn (if (string/blank? title-filter)
                         "No chats found in any workspace."
                         (format "No chats found matching '%s'." title-filter)))
      (chat-message-fn
       (multi-str
        "Chats by workspace:"
        ""
        (string/join "\n\n" (map group-section groups))
        ""
        (when (some #(nil? (:workspaces %)) groups)
          "`~name` means approximate workspace name: exact paths are recorded once that workspace is opened again with this ECA version.")
        "Open the workspace and run `/resume` to continue a chat.")))))

(defn ^:private delete-chat-command
  [{:keys [db* chat-id]} {args :argv}]
  (let [db @db*
        confirm? (= "confirm" (some-> (last args) string/lower-case))
        target-arg (first (remove #(= "confirm" (some-> % string/lower-case)) args))
        other-chats (into {}
                          (filter #(and (not= chat-id (first %))
                                        (not (:subagent (second %)))))
                          (:chats db))
        ;; Same ordering as /resume so its listed ids work here.
        chats-ids (vec (sort-by #(:created-at (get other-chats %)) (keys other-chats)))
        target-id (cond
                    (nil? target-arg) chat-id
                    (= target-arg chat-id) chat-id
                    (contains? other-chats target-arg) target-arg
                    :else (when-let [n (parse-long target-arg)]
                            (nth chats-ids (dec n) nil)))
        target-chat (get-in db [:chats target-id])
        current? (= target-id chat-id)
        chat-message-fn (fn [text]
                          {:type :chat-messages
                           :chats {chat-id {:messages [{:role "system" :content [{:type :text :text text}]}]}}})
        describe-fn (fn [chat]
                      (or (:title chat)
                          (format "No chat title (%s user messages)"
                                  (or (:user-message-count (db.chats/chat-list-meta chat)) 0))))]
    (cond
      (nil? target-id)
      (chat-message-fn "Chat ID not found. Run `/resume` to list chats.")

      (and (not current?)
           (db.chats/hydrated? target-chat)
           (contains? #{:running :stopping} (:status target-chat)))
      (chat-message-fn (format "Chat '%s' has a running prompt, stop it before deleting." (describe-fn target-chat)))

      (not confirm?)
      (chat-message-fn
       (multi-str
        (if current?
          "This will permanently delete the current chat."
          (format "This will permanently delete chat: %s" (describe-fn target-chat)))
        (str "Run `/delete-chat " (when target-arg (str target-arg " ")) "confirm` to proceed.")))

      :else
      {:type :delete-chat
       :target-chat-id target-id
       :text (when-not current?
               (format "Deleted chat: %s" (describe-fn target-chat)))})))

;; /export and /import: a single chat to and from an EDN file. Unlike the /debug-chat dump
;; (obfuscated for privacy and size), it keeps the chat verbatim, so it can be imported as
;; a fully resumable chat: messages plus model, variant, agent/subagent, usage, trust and
;; the original chat-id (so provider/cache reuse keeps working). Issue #28.

(def ^:private export-version 1)

(def ^:private exported-chat-keys
  "Chat fields preserved on export, the modules' chat state included.
   Excludes live/transient state (:tool-calls promises/futures,
   :last-status-payload) and the machine/provider-specific :prompt-cache."
  [:id :title :title-custom? :status :created-at :updated-at :login-provider
   :model :variant :last-api :trust :prompt-id :user-prompt-count :subagent
   :parent-chat-id :startup-context :messages :usage :task :modules])

(defn ^:private build-export
  "Build the export envelope map for chat-id from the in-memory db. The
   contexts modules added are left out: they come from this ECA's state,
   e.g. notes on this machine, and the modules of the ECA that imports the
   chat add their own."
  [db chat-id]
  (let [chat (select-keys (get-in db [:chats chat-id]) exported-chat-keys)]
    {:eca/version (config/eca-version)
     :eca/export-version export-version
     :exported-at (System/currentTimeMillis)
     :chat (cond-> chat
             (:messages chat) (update :messages #(mapv message-sanitize/without-module-contexts %)))}))

(defn ^:private export->edn [export]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint export))))

(defn ^:private slugify [s]
  (let [slug (-> (or s "")
                 string/lower-case
                 (string/replace #"[^a-z0-9]+" "-")
                 (string/replace #"^-+|-+$" ""))]
    (when-not (string/blank? slug)
      (subs slug 0 (min 60 (count slug))))))

(defn ^:private default-export-filename [chat]
  (let [name (or (slugify (:title chat)) (:id chat) "chat")]
    (str "eca-chat-" name ".edn")))

(defn ^:private default-export-filepath [chat]
  (str (fs/file (fs/temp-dir) (default-export-filename chat))))

(defn ^:private expand [filepath]
  (str (fs/expand-home (string/trim filepath))))

(defn ^:private dir-target?
  "True when the destination is a directory rather than a file: `.`/`..`, a path
   ending with a separator, or an already-existing directory."
  [trimmed expanded]
  (or (contains? #{"." ".."} trimmed)
      (boolean (re-find #"[/\\]$" trimmed))
      (fs/directory? expanded)))

(defn ^:private resolve-export-path
  "Resolve the destination file path: blank -> temp-dir default; a directory
   target -> default filename inside it; otherwise the given path verbatim."
  [filepath chat]
  (-> (if (string/blank? filepath)
        (default-export-filepath chat)
        (let [trimmed (string/trim filepath)
              expanded (expand filepath)]
          (if (dir-target? trimmed expanded)
            (str (fs/path expanded (default-export-filename chat)))
            expanded)))
      fs/absolutize
      fs/normalize
      str))

(defn ^:private export-chat!
  "Serialize chat-id as EDN to filepath (defaults to /tmp/eca-chat-<title>.edn
   when blank). Returns {:path <abs-path> :message-count <n>} on success or
   {:error <msg>} on failure."
  [{:keys [db chat-id filepath]}]
  (try
    (if-let [chat (get-in db [:chats chat-id])]
      (let [path (resolve-export-path filepath chat)]
        (io/make-parents path)
        (spit path (export->edn (build-export db chat-id)))
        {:path path
         :message-count (count (:messages chat))})
      {:error (str "Chat not found: " chat-id)})
    (catch Exception e
      {:error (.getMessage e)})))

(defn ^:private valid-export? [data]
  (and (map? data)
       (contains? data :eca/export-version)
       (map? (:chat data))
       (string? (get-in data [:chat :id]))))

(defn ^:private import-chat!
  "Read and validate an exported chat EDN file. Returns {:chat <chat-map>} with
   the verbatim chat (its original :id preserved) or {:error <msg>}."
  [{:keys [filepath]}]
  (try
    (if (string/blank? filepath)
      {:error "Missing filepath. Ex: /import /tmp/chat.edn"}
      (let [path (expand filepath)]
        (if-not (fs/exists? path)
          {:error (str "File not found: " path)}
          (let [data (edn/read-string (slurp path))]
            (if (valid-export? data)
              {:chat (cond-> (:chat data)
                       (sequential? (get-in data [:chat :messages]))
                       (update :messages #(mapv message-sanitize/without-module-contexts %)))}
              {:error "Not a valid ECA chat export file."})))))
    (catch Exception e
      {:error (.getMessage e)})))

(defn ^:private export-command
  [{:keys [db* chat-id]} {args :argv}]
  (let [db @db*
        {:keys [path message-count error]}
        (export-chat! {:db db
                       :chat-id chat-id
                       :filepath (first args)})
        text (if error
               (str "Failed to export chat: " error)
               (multi-str (str "Chat exported to `" path "`")
                          (str "Messages: " message-count)
                          ""
                          (str "Import it elsewhere with `/import " path "`")))]
    {:type :chat-messages
     :chats {chat-id {:messages [{:role "system" :content [{:type :text :text text}]}]}}}))

(defn ^:private import-command
  [{:keys [chat-id db* config messenger metrics]} {args :argv}]
  (let [db @db*
        {:keys [chat error]} (import-chat! {:filepath (first args)})]
    (if error
      {:type :chat-messages
       :chats {chat-id {:messages [{:role "system" :content [{:type :text :text (str "Failed to import chat: " error)}]}]}}}
      (let [imported-id (:id chat)
            replaced? (contains? (:chats db) imported-id)
            model-available? (contains? (:models db) (:model chat))
            imported-chat (-> chat
                              (dissoc :prompt-finished? :auto-compacting? :continuing? :compacting?
                                      :tool-calls :last-status-payload)
                              (assoc :status :idle :prompt-finished? true))
            summary (multi-str
                     (str "Imported chat: " (or (:title imported-chat) imported-id))
                     (when replaced? "Replaced an existing chat with the same id.")
                     (when-not model-available?
                       (str "Model `" (:model imported-chat) "` is not available here; switch with `/model` or login.")))]
        ;; Persist under the original id so the chat stays /resume-able
        ;; and reuses provider/cache keyed by chat-id (#28).
        (swap! db* assoc-in [:chats imported-id] imported-chat)
        (db.chats/save-chat! @db* imported-id metrics)
        (messenger/chat-opened messenger {:chat-id imported-id :title (:title imported-chat)})
        ;; Align the client's model/trust with the imported chat (like /resume).
        (config/notify-selected-model-changed! (:model imported-chat) db* messenger config (:variant imported-chat) imported-id)
        (config/notify-selected-trust-changed! (:trust imported-chat) db* messenger imported-id)
        {:type :chat-messages
         :chats {imported-id {:title (:title imported-chat)
                              :messages (concat [{:role "system" :content [{:type :text :text summary}]}]
                                                (:messages imported-chat))}
                 chat-id {:messages [{:role "system" :content [{:type :text :text (str "Imported chat to: " (or (:title imported-chat) imported-id))}]}]}}}))))

;; /debug-chat: a single chat's in-memory state in a file, for debugging corrupted or stuck
;; chats. User/assistant/reasoning text and tool-call input/output are obfuscated (privacy +
;; size reduction) while the message-history structure and the chat metadata that affects
;; message sending (model, variant, tool names, statuses) are preserved.

(defn ^:private idx->label
  "Bijective base-26 label (spreadsheet-style): 0->A, 25->Z, 26->AA, 27->AB ..."
  [n]
  (loop [n (long n) acc ""]
    (let [q (quot n 26)
          r (mod n 26)
          ch (char (+ (int \A) r))]
      (if (pos? q)
        (recur (dec q) (str ch acc))
        (str ch acc)))))

(defn ^:private size-bucket
  "Coarse log10 size signal so an anomalously large entry still stands out while
   the content is reduced to a tiny token."
  [^long len]
  (-> (Math/log10 (inc (double len))) Math/ceil long (max 1) (min 12)))

(defn ^:private make-obfuscator
  "Stateful obfuscator. Identical source strings reuse the same label so
   duplicated/echoed content (a corruption smell) stays detectable."
  []
  (atom {:n 0 :memo {}}))

(defn ^:private label-for [state* s]
  (-> (swap! state* (fn [{:keys [n memo] :as st}]
                      (if (contains? memo s)
                        st
                        (-> st (assoc :n (inc n)) (assoc-in [:memo s] (idx->label n))))))
      (get-in [:memo s])))

(defn ^:private obfuscate-str
  "Replace a string with its label repeated to encode a coarse size bucket,
   e.g. a ~1k char first chunk -> \"AAAA\", a ~100 char second chunk -> \"BBB\"."
  [obf s]
  (let [s (str s)]
    (if (string/blank? s)
      s
      (let [label (label-for obf s)]
        (apply str (repeat (size-bucket (count s)) label))))))

(defn ^:private obf-or-nil [obf s]
  (when s (obfuscate-str obf s)))

(defn ^:private edn-safe-scalar [data]
  (if (or (number? data) (boolean? data) (keyword? data)
          (symbol? data) (uuid? data) (inst? data))
    data
    (str "#unprintable[" (.getName ^Class (class data)) "]")))

(defn ^:private obfuscate-data
  "Recursively obfuscate every string leaf while preserving structure: map keys,
   keyword values, numbers, booleans and nil are kept as-is."
  [obf data]
  (cond
    (nil? data) nil
    (string? data) (obfuscate-str obf data)
    (map? data) (reduce-kv (fn [m k v] (assoc m k (obfuscate-data obf v))) {} data)
    (vector? data) (mapv #(obfuscate-data obf %) data)
    (set? data) (into #{} (map #(obfuscate-data obf %)) data)
    (seq? data) (mapv #(obfuscate-data obf %) data)
    :else (edn-safe-scalar data)))

(defn ^:private text-block? [block kind]
  (and (map? block) (#{kind (name kind)} (:type block))))

(defn ^:private obfuscate-text-content [obf content]
  (cond
    (string? content) (obfuscate-str obf content)
    (sequential? content)
    (mapv (fn [block]
            (cond
              (text-block? block :text) (update block :text #(obfuscate-str obf %))
              (text-block? block :image) (-> block (dissoc :base64 :data) (assoc :image :redacted))
              :else (obfuscate-data obf block)))
          content)
    :else (obfuscate-data obf content)))

(defn ^:private obfuscate-tool-output [obf output]
  (cond
    (map? output) (cond-> (select-keys output [:error])
                    (contains? output :contents)
                    (assoc :contents (mapv (fn [c]
                                             (if (and (map? c) (contains? c :text))
                                               (update c :text #(obfuscate-str obf %))
                                               (obfuscate-data obf c)))
                                           (:contents output))))
    (string? output) (obfuscate-str obf output)
    :else (obfuscate-data obf output)))

(defn ^:private obfuscate-content [obf role content]
  (case role
    ("user" "assistant" "system")
    (obfuscate-text-content obf content)

    "reason"
    (-> (select-keys content [:id :delta-reasoning? :total-time-ms :redacted? :api])
        (assoc :text (obfuscate-str obf (:text content))
               :external-id (obf-or-nil obf (:external-id content)))
        (cond-> (:redacted? content) (assoc :data :redacted)))

    "tool_call"
    (-> (select-keys content [:id :name :full-name :origin :server :api])
        (assoc :arguments (obfuscate-data obf (:arguments content))
               :summary (obf-or-nil obf (:summary content))
               :details (obfuscate-data obf (:details content))))

    "tool_call_output"
    (-> (select-keys content [:id :name :full-name :origin :server :error :total-time-ms :api])
        (assoc :arguments (obfuscate-data obf (:arguments content))
               :output (obfuscate-tool-output obf (:output content))
               :summary (obf-or-nil obf (:summary content))
               :details (obfuscate-data obf (:details content))))

    "server_tool_use"
    (-> (select-keys content [:id :name :api])
        (assoc :input (obfuscate-data obf (:input content))))

    "server_tool_result"
    (-> (select-keys content [:tool-use-id :api])
        (assoc :raw-content (obfuscate-data obf (:raw-content content))))

    "image_generation_call"
    {:image :redacted}

    ;; compact_marker, flag and anything else: obfuscate every string leaf.
    (obfuscate-data obf content)))

(defn ^:private obfuscate-message [obf msg]
  (-> (select-keys msg [:role :content-id :created-at])
      (assoc :content (obfuscate-content obf (:role msg) (:content msg)))))

(defn ^:private sanitize-tool-calls
  "Keep only the serializable, diagnostic parts of the live tool-call state
   machine. Drops the promises/futures (:approved?*, :future-cleanup-complete?*,
   :future) and any content-bearing fields."
  [tool-calls]
  (into {}
        (map (fn [[id tc]]
               [id (cond-> (select-keys tc [:status :name :full-name :server :origin :start-time])
                     (:decision-reason tc) (assoc :decision-reason (select-keys (:decision-reason tc) [:code])))]))
        tool-calls))

(defn ^:private auth-summary
  "Presence + type + expiry of each logged provider. Never includes tokens or
   API keys."
  [db]
  (into {}
        (keep (fn [[provider auth]]
                (when (seq auth)
                  [provider (cond-> {:type (:type auth)
                                     :step (:step auth)
                                     :mode (:mode auth)}
                              (:expires-at auth) (assoc :expires-at (:expires-at auth)))])))
        (:auth db)))

(defn ^:private build-dump
  "Build the curated, obfuscated dump map for chat-id from the in-memory db."
  [db chat-id all-tools]
  (let [chat (get-in db [:chats chat-id])
        obf (make-obfuscator)
        messages (:messages chat [])]
    {:eca/version (config/eca-version)
     :dumped-at (System/currentTimeMillis)
     :obfuscation {:scheme :sequential-letter-labels
                   :note (str "Each distinct text is replaced by a base-26 letter label (A, B, C ...) "
                              "repeated to encode a coarse log10 size bucket. Identical source strings "
                              "share a label. Roles, tool names, ids, model, variant, statuses and counts "
                              "are preserved verbatim.")}
     :chat (-> (select-keys chat [:id :title-custom? :status :created-at :updated-at
                                  :login-provider :model :variant :last-api :trust :prompt-id
                                  :user-prompt-count :subagent :parent-chat-id])
               (assoc :title (obf-or-nil obf (:title chat))
                      :startup-context (obf-or-nil obf (:startup-context chat))
                      :flags (select-keys chat [:prompt-finished? :compacting? :auto-compacting? :continuing? :max-steps-reached?])
                      :message-count (count messages)
                      :messages-by-role (frequencies (map :role messages))
                      :task (obfuscate-data obf (:task chat))
                      :tool-calls (sanitize-tool-calls (:tool-calls chat))
                      :messages (mapv #(obfuscate-message obf %) messages)))
     :model-capabilities (get-in db [:models (:model chat)])
     :available-tools (mapv #(select-keys % [:name :full-name :origin :server]) all-tools)
     :mcp-clients (into {}
                        (map (fn [[id c]]
                               [id {:status (:status c)
                                    :tools (mapv :name (:tools c))}]))
                        (:mcp-clients db))
     :auth-summary (auth-summary db)}))

(defn ^:private dump->edn [dump]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint dump))))

(defn ^:private default-dump-filepath [chat]
  (let [name (or (slugify (:title chat)) (:id chat) "chat")]
    (str (fs/file (fs/temp-dir) (str "eca-chat-debug-" name ".edn")))))

(defn ^:private dump-chat!
  "Build the obfuscated dump for chat-id and write it as EDN. Returns
   {:path <abs-path> :message-count <n>} on success or {:error <msg>} on failure.
   Resolves a blank filepath to /tmp/eca-chat-debug-<title>.edn."
  [{:keys [db chat-id all-tools filepath]}]
  (try
    (if-let [chat (get-in db [:chats chat-id])]
      (let [path (-> (if (string/blank? filepath)
                       (default-dump-filepath chat)
                       (str (fs/expand-home (string/trim filepath))))
                     fs/absolutize
                     str)]
        (io/make-parents path)
        (spit path (dump->edn (build-dump db chat-id all-tools)))
        {:path path
         :message-count (count (:messages chat))})
      {:error (str "Chat not found: " chat-id)})
    (catch Exception e
      {:error (.getMessage e)})))

(defn ^:private debug-chat-command
  [{:keys [db* chat-id all-tools]} {args :argv}]
  (let [db @db*
        {:keys [path message-count error]}
        (dump-chat! {:db db
                     :chat-id chat-id
                     :all-tools all-tools
                     :filepath (first args)})
        text (if error
               (str "Failed to dump chat: " error)
               (multi-str (str "Chat dump written to `" path "`")
                          (str "Messages: " message-count)
                          ""
                          "Content is obfuscated (text/reasoning/tool input/output); share this file for debugging."))]
    {:type :chat-messages
     :chats {chat-id {:messages [{:role "system" :content [{:type :text :text text}]}]}}}))

(def module
  {:id :eca/chat-management
   :description "Fork chats, ask side questions, and resume, list, delete, export and import chats."
   :commands {"fork" {:description "Fork current chat into a new chat with the same history and settings."
                      :arguments []
                      :handler #'fork-command}
              "btw" {:description "Ask a side question in a forked chat, keeping this chat history clean (Ex: /btw how does X work?)"
                     :arguments [{:name "prompt" :required true}]
                     :handler #'btw-command}
              "resume" {:description "Resume the specified chat-id. Blank to list chats or 'latest'."
                        :arguments [{:name "chat-id"}]
                        :handler #'resume-command}
              "chats" {:description "List chats from all workspaces to find where a chat lives (Ex: /chats, /chats <title-filter>)"
                       :arguments [{:name "title-filter"}]
                       :handler #'chats-command}
              "delete-chat" {:description (str "Permanently delete the current chat or a chat-id from the /resume list, asking "
                                               "confirmation. (Ex: /delete-chat, /delete-chat 2)")
                             :arguments [{:name "chat-id"}]
                             :handler #'delete-chat-command}
              "export" {:description (str "Export the current chat to a file or directory to transfer/import it elsewhere. "
                                          "Ex: /export /tmp/chat.edn or /export .")
                        :arguments [{:name "filepath"}]
                        :handler #'export-command}
              "import" {:description "Import a chat from a file as a new resumable chat. Ex: /import /tmp/chat.edn"
                        :arguments [{:name "filepath"}]
                        :handler #'import-command}
              "debug-chat" {:description "Dump the current chat (obfuscated) to a file for debugging. Ex: /debug-chat /tmp/out.edn"
                            :arguments [{:name "filepath"}]
                            :handler #'debug-chat-command}}})
