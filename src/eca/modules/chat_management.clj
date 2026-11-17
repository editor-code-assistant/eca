(ns eca.modules.chat-management
  "The `:eca/chat-management` module: fork a chat, ask a side question in a
   fork, and resume, list, delete, export and import chats."
  (:require
   [clojure.string :as string]
   [eca.config :as config]
   [eca.db.chats :as db.chats]
   [eca.db.workspaces :as db.workspaces]
   [eca.features.chat.debug :as f.chat.debug]
   [eca.features.chat.export :as f.chat.export]
   [eca.features.chat.history :as f.chat.history]
   [eca.features.chat.ops :as f.chat.ops]
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

(defn ^:private chats-command
  [{:keys [db* chat-id metrics]} {args :argv}]
  (let [db @db*
        title-filter (string/lower-case (string/trim (string/join " " args)))
        matches? (fn [{:keys [title]}]
                   (or (string/blank? title-filter)
                       (string/includes? (string/lower-case (or title "")) title-filter)))
        groups (->> (db.workspaces/list-all-workspaces-chats db metrics)
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

(defn ^:private export-command
  [{:keys [db* chat-id]} {args :argv}]
  (let [db @db*
        {:keys [path message-count error]}
        (f.chat.export/export-chat! {:db db
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
        {:keys [chat error]} (f.chat.export/import-chat! {:filepath (first args)})]
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

(defn ^:private debug-chat-command
  [{:keys [db* chat-id all-tools]} {args :argv}]
  (let [db @db*
        {:keys [path message-count error]}
        (f.chat.debug/dump-chat! {:db db
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
