(ns eca.features.chat.ops
  "Operations on a chat outside its turns: list, open, clear, rename, roll
   back, flag, fork and delete it, and its history."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.chat.history :as f.chat.history]
   [eca.features.chat.lifecycle :as f.chat.lifecycle]
   [eca.features.chat.replay :as f.chat.replay]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.modules.data :as modules.data]
   [eca.modules.events :as events]
   [eca.shared :as shared :refer [assoc-some]]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CHAT]")

(defn fork-title [title]
  (let [title (or title "Untitled")]
    (if-let [[_ base n] (re-matches #"(.+?)\s*\((\d+)\)\s*$" title)]
      (str base " (" (inc (parse-long n)) ")")
      (str title " (2)"))))

(defn drop-dangling-tool-calls
  "Drops tool_call/server_tool_use entries without their paired
   tool_call_output/server_tool_result, which can exist mid-turn (outputs are
   appended to history only after execution). A fork taken at that moment would
   otherwise replay an unpaired tool call that providers reject."
  [messages]
  (let [output-ids (into #{}
                         (keep #(when (= "tool_call_output" (:role %))
                                  (get-in % [:content :id])))
                         messages)
        result-ids (into #{}
                         (keep #(when (= "server_tool_result" (:role %))
                                  (get-in % [:content :tool-use-id])))
                         messages)]
    (filterv (fn [{:keys [role content]}]
               (case role
                 "tool_call" (contains? output-ids (:id content))
                 "server_tool_use" (contains? result-ids (:id content))
                 true))
             messages)))

(defn sanitize-title
  "Clean up a chat title: take first meaningful line, strip control chars,
   markdown header prefixes, collapse whitespace, and truncate to 40 chars.

   If the first non-blank line is a bare markdown header with nothing else
   (e.g. '## Understand' — a planning-mode section the title model sometimes
   mimics), fall through to the next non-blank line when one exists."
  [^String s]
  (when s
    (let [lines (->> (string/split s #"\n")
                     (map string/trim)
                     (remove string/blank?))
          bare-header? (fn [^String line]
                         (boolean (re-matches #"#+\s+\S.*" line)))
          picked (or (when-let [first-line (first lines)]
                       (if (and (bare-header? first-line)
                                (seq (rest lines)))
                         (first (rest lines))
                         first-line))
                     "")]
      (-> picked
          (string/replace #"[\x00-\x1f\x7f]" " ")
          (string/replace #"^#+\s*" "")
          (string/replace #"\s+" " ")
          (string/trim)
          (as-> t (subs t 0 (min (count t) 40)))))))

(defn mark-editor-open!
  "Records that the editor has this chat open this run, so the remote endpoint
   lists it. Separate from :chat-start-fired, which only gates the chatStart hook."
  [db* chat-id]
  (swap! db* update :editor-open-chats (fnil conj #{}) chat-id))

(defn delete-chat
  [{:keys [chat-id]} db* messenger config metrics]
  (let [db @db*]
    (logger/with-chat-context chat-id (db/parent-chat-id db chat-id)
      (when-let [chat (get-in db [:chats chat-id])]
        ;; BEFORE deleting, so the chatEnd hooks find the chat in the cache
        (let [{:keys [agent model variant]} chat]
          (events/emit! {:db* db* :messenger messenger :metrics metrics :config config :chat-id chat-id}
                        :chat/ended
                        (assoc-some {:chat-id chat-id :agent agent :model model}
                                    :variant variant
                                    :parent-chat-id (db/parent-chat-id db chat-id)))))
      ;; Delete chat from memory; tombstone the id so the cache merge-on-write
      ;; never resurrects it from a cache file shared with another live server.
      (swap! db* (fn [db]
                   (-> db
                       (update :chats dissoc chat-id)
                       (update :deleted-chat-ids (fnil conj #{}) chat-id))))
      (messenger/chat-deleted messenger {:chat-id chat-id})
      ;; Delete the chat's cache file and drop it from the chats index
      (db/delete-chat-from-cache! @db* chat-id metrics)
      ;; and the modules' files for it (eca.api/data-dir)
      (try
        (modules.data/delete-chat-dirs! @db* chat-id)
        (catch Exception e
          (logger/warn logger-tag "Could not delete the modules' files of the chat:" (ex-message e)))))))

(defn clear-chat
  "Clear specific aspects of a chat. Currently supports clearing :messages."
  [{:keys [chat-id messages]} db* messenger metrics]
  (when (get-in @db* [:chats chat-id])
    ;; Hydrate first so the dissocs below apply to the full chat and the
    ;; save writes memory wholesale instead of merging over the disk copy
    ;; (which would resurrect :task/:usage/:prompt-cache on reload).
    (db/hydrate-chat! db* chat-id metrics)
    (swap! db* update-in [:chats chat-id]
           (fn [chat]
             (cond-> chat
               ;; A cleared chat is a fresh conversation: drop the prompt cache
               ;; so the next prompt rebuilds the system prompt silently instead
               ;; of warning about an invalidated cache that no longer exists
               ;; (#530), the "approve & remember" approvals too (#639), and
               ;; what modules keep for the chat: their chat state, contexts for
               ;; the next request and a compaction they asked for.
               messages (-> (assoc :messages [])
                            (dissoc :tool-calls :last-api :usage :task
                                    :prompt-cache :last-editor-state
                                    :sent-attached-contexts
                                    :remembered-approvals
                                    :modules :module-contexts :compaction-request)))))
    (messenger/chat-cleared messenger {:chat-id chat-id :messages messages})
    (db/save-chat! @db* chat-id metrics)))

(defn update-chat
  "Update chat metadata like title and trust.
   Broadcasts changes to all connected clients.
   Marks the title as custom to suppress automatic re-titling.
   Trust changes apply immediately to subsequent tool calls in the active prompt."
  [{:keys [chat-id title trust]} db* messenger metrics]
  (when (get-in @db* [:chats chat-id])
    (when (some? trust)
      (swap! db* assoc-in [:chats chat-id :trust] trust))
    (when title
      (let [title (sanitize-title title)]
        (swap! db* assoc-in [:chats chat-id :title] title)
        (swap! db* assoc-in [:chats chat-id :title-custom?] true)
        (messenger/chat-content-received messenger
                                         {:chat-id chat-id
                                          :role    "system"
                                          :content {:type :metadata :title title}})
        (db/save-chat! @db* chat-id metrics))))
  {})

(defn ^:private rollback-target-idx
  "Index of the first message carrying `content-id`. A turn may store several
   messages under the same id, so rolling it back removes all of them."
  [messages content-id]
  (first (keep-indexed (fn [idx message]
                         (when (= content-id (:content-id message)) idx))
                       messages)))

(defn ^:private rollback-error [code message]
  (logger/warn logger-tag "Chat rollback rejected:" message)
  {:error {:code code :message message}})

(defn rollback-chat
  "Remove the message identified by content-id and every message after it.
   Then notify the client: `chat/truncated` when it supports incremental
   updates, otherwise `chat/cleared` followed by the kept messages.
   Returns an `:error` without touching history or files when the chat is
   unknown or content-id is missing or matches no message: many messages have
   no id, so a nil id would match the first of those instead (#669)."
  [{:keys [chat-id content-id include]} db* messenger metrics]
  (logger/with-chat-context chat-id (db/parent-chat-id @db* chat-id)
    (db/hydrate-chat! db* chat-id metrics)
    (let [chat (get-in @db* [:chats chat-id])
          all-messages (vec (:messages chat))
          target-idx (some->> (shared/not-blank content-id)
                              (rollback-target-idx all-messages))]
      (cond
        (nil? chat)
        (rollback-error "chat_not_found" (str "Chat " chat-id " not found"))

        (not (shared/not-blank content-id))
        (rollback-error "invalid_request" "contentId is required and must be a non-blank string")

        (nil? target-idx)
        (rollback-error "content_not_found" (str "No message with contentId " content-id " in chat " chat-id))

        :else
        (let [include (if (seq include)
                        (set include)
                        ;; backwards compatibility
                        #{"messages" "tools"})
              tool-calls (:tool-calls chat)
              new-messages (when (contains? include "messages")
                             (into [] (take target-idx) all-messages))
              removed-messages (when (contains? include "tools")
                                 (subvec all-messages target-idx))
              rollback-changes (->> removed-messages
                                    (filter #(= "tool_call_output" (:role %)))
                                    (keep #(get-in tool-calls [(:id (:content %)) :rollback-changes]))
                                    flatten
                                    reverse)]
          (doseq [{:keys [path content]} rollback-changes]
            (logger/info (format "Rolling back change for '%s' to content: '%s'" path content))
            (if content
              (spit path content)
              (io/delete-file path true)))
          (when new-messages
            (swap! db* update-in [:chats chat-id]
                   #(-> %
                        (assoc :messages new-messages)
                        ;; The removed messages may hold the cursor or attached
                        ;; contexts, so send them again on the next prompt. #683
                        (dissoc :last-editor-state :sent-attached-contexts)))
            ;; Rollback is the user's recovery tool for a chat that got into a bad
            ;; state. Persist immediately so the cleaned-up history survives a
            ;; restart instead of relying on the next unrelated save.
            (db/save-chat! @db* chat-id metrics)
            (if (f.chat.history/incremental-updates? @db*)
              (messenger/chat-truncated messenger {:chat-id chat-id
                                                   :content-id content-id})
              (do
                (messenger/chat-cleared
                 messenger
                 {:chat-id chat-id
                  :messages true})
                (f.chat.replay/send-chat-contents!
                 new-messages
                 {:chat-id chat-id
                  :db* db*
                  :messenger messenger}))))
          {})))))

(defn ^:private find-last-message-idx
  "Find the last message index matching content-id by checking both
   :content-id (user messages) and [:content :id] (tool calls, etc).
   A matched reason message extends to the end of its run of adjacent reason
   messages, as clients show the whole run as one block with the first id."
  [messages content-id]
  (when-let [idx (loop [i (dec (count messages))]
                   (cond
                     (neg? i) nil
                     (let [msg (messages i)]
                       (or (= content-id (:content-id msg))
                           (= content-id (get-in msg [:content :id])))) i
                     :else (recur (dec i))))]
    (if (= "reason" (:role (messages idx)))
      (loop [i idx]
        (if (= "reason" (:role (get messages (inc i))))
          (recur (inc i))
          i))
      idx)))

(defn add-flag
  "Add a named flag after the message identified by content-id.
   Searches both :content-id and [:content :id] to support placement
   after any message type (user, tool call, reason, etc).
   Clients supporting incremental updates receive only the flag, with
   `afterContentId` telling where to render it; others get a clear and
   full replay so the flag renders at the correct position."
  [{:keys [chat-id content-id text]} db* messenger metrics]
  (db/hydrate-chat! db* chat-id metrics)
  (let [messages (vec (get-in @db* [:chats chat-id :messages]))
        insert-idx (find-last-message-idx messages content-id)]
    (when insert-idx
      (let [flag-id (str (random-uuid))
            flag-msg {:role "flag" :content {:text text} :content-id flag-id :created-at (System/currentTimeMillis)}
            insert-after (inc insert-idx)
            new-messages (into (subvec messages 0 insert-after)
                               (cons flag-msg (subvec messages insert-after)))]
        (swap! db* assoc-in [:chats chat-id :messages] new-messages)
        (db/save-chat! @db* chat-id metrics)
        (if (f.chat.history/incremental-updates? @db*)
          (messenger/chat-content-received messenger
                                           {:chat-id chat-id
                                            :role :system
                                            :content {:type :flag
                                                      :text text
                                                      :contentId flag-id
                                                      :afterContentId content-id}})
          (do
            (messenger/chat-cleared messenger {:chat-id chat-id :messages true})
            (f.chat.replay/send-chat-contents! new-messages {:chat-id chat-id :db* db* :messenger messenger})))))
    {}))

(defn remove-flag
  "Remove a flag message identified by content-id from the chat."
  [{:keys [chat-id content-id]} db* metrics]
  (db/hydrate-chat! db* chat-id metrics)
  (when-let [messages (get-in @db* [:chats chat-id :messages])]
    (let [new-messages (vec (remove #(and (= "flag" (:role %))
                                          (= content-id (:content-id %)))
                                    messages))]
      (when (not= (count new-messages) (count messages))
        (swap! db* assoc-in [:chats chat-id :messages] new-messages)
        (db/save-chat! @db* chat-id metrics))))
  {})

(defn fork-chat
  "Fork the chat creating a new chat with messages up to and including
   the message identified by content-id.
   Clients supporting incremental updates get only the newest page of kept
   messages; the paging meta is returned as `:meta` and in `chat/opened` so
   the client can page older via `chat/history`."
  [{:keys [chat-id content-id]} db* messenger metrics]
  (db/hydrate-chat! db* chat-id metrics)
  (let [chat (get-in @db* [:chats chat-id])
        messages (vec (:messages chat))
        target-idx (find-last-message-idx messages content-id)]
    (if-not target-idx
      {}
      (let [new-id (str (random-uuid))
            now (System/currentTimeMillis)
            new-title (fork-title (:title chat))
            kept-messages (subvec messages 0 (inc target-idx))
            new-chat (assoc-some {:id new-id
                                  :title new-title
                                  :status :idle
                                  :created-at now
                                  :updated-at now
                                  :model (:model chat)
                                  :last-api (:last-api chat)
                                  :messages kept-messages
                                  :prompt-finished? true}
                                 ;; the modules' chat state goes with the chat
                                 :modules (:modules chat))
            page (f.chat.history/server-created-page @db* kept-messages)
            meta (when page (f.chat.history/page-meta page kept-messages))]
        (swap! db* assoc-in [:chats new-id] new-chat)
        (mark-editor-open! db* new-id)
        (db/save-chat! @db* new-id metrics)
        (messenger/chat-opened messenger (assoc-some {:chat-id new-id :title new-title}
                                                     :history meta))
        (f.chat.replay/send-chat-contents! (if page (:messages page) kept-messages)
                             {:chat-id new-id :db* db* :messenger messenger})
        (f.chat.lifecycle/send-content! {:messenger messenger :chat-id new-id}
                                        :system
                                        (assoc-some {:type :metadata} :title new-title))
        (f.chat.lifecycle/send-content! {:messenger messenger :chat-id chat-id}
                                        :system
                                        {:type :text :text (str "Chat forked to: " new-title)})
        (assoc-some {:chat-id new-id} :meta meta)))))

(defn list-chats
  "Pure projection over `(:chats db)`: returns a summary list intended for the
   client sidebar. Subagent chats are excluded. Supports optional
   `:limit` (positive int) and `:sort-by` (`:updated-at` or `:created-at`;
   default `:updated-at`). Results are sorted descending by the chosen
   timestamp, falling back to the other when the primary is nil.
   The map key is used as the authoritative `:id` because legacy DB rows
   may have been persisted without an `:id` inside the value, and clients
   need a non-nil id to call `chat/open`."
  [db {:keys [limit] sort-key :sort-by}]
  (let [primary (or sort-key :updated-at)
        secondary (if (= primary :updated-at) :created-at :updated-at)
        chats (->> (:chats db)
                   (remove (fn [[_ v]] (:subagent v)))
                   (sort-by (fn [[_ v]] (or (get v primary) (get v secondary) 0)) >)
                   (mapv (fn [[k chat]]
                           (let [{:keys [title status created-at updated-at model message-count kind]}
                                 (db/chat-list-meta chat)]
                             (assoc-some
                              {:id k
                               :title title
                               :status (or status :idle)
                               :message-count (or message-count 0)}
                              :created-at created-at
                              :updated-at updated-at
                              :model model
                              :kind kind)))))]
    {:chats (if (and limit (pos? (long limit)))
              (vec (take (long limit) chats))
              chats)}))

(defn open-chat!
  "Replay a persisted chat over the wire so a freshly-started client can render
   it. Emits `chat/cleared` (messages) followed by `chat/opened` and streams each
   persisted message via `send-chat-contents!`. Also re-aligns the client's
   selected model to the resumed chat's stored `:model` via a scoped
   `config/updated` notification and returns the restored model, agent, variant,
   variants, and trust as an atomic `:selection` snapshot (#417). Performs no
   DB mutation otherwise.

   Optional `:limit`/`:before`/`:after` window the replay (same cursors as
   `chat/history`); when provided, the response includes `:meta` so the client
   can page older via `chat/history`. Without them the full history is replayed.
   Returns `{:found false}` when the chat does not exist or is a subagent,
   otherwise `{:found true :chat-id ... :title ... :selection ... :meta? ...}`."
  ([params db* messenger config]
   (open-chat! params db* messenger config nil))
  ([{:keys [chat-id limit before after] :as params} db* messenger config metrics]
   (db/hydrate-chat! db* chat-id metrics)
   (let [chat (get-in @db* [:chats chat-id])
         windowed? (some #(contains? params %) [:limit :before :after])
         page (when windowed?
                (f.chat.history/window-messages (vec (:messages chat))
                                                {:limit limit :before before :after after}))]
     (cond
       (or (nil? chat) (:subagent chat))
       {:found false}

       (= :cursor-expired (:error page))
       {:found true :chat-id chat-id
        :error {:code "cursor_expired"
                :message "Cursor no longer points to an existing message; refetch the latest page"}}

       :else
       (let [title (:title chat)
             messages (if windowed? (:messages page) (:messages chat))
             chat-ctx {:chat-id chat-id :db* db* :messenger messenger}]
         (mark-editor-open! db* chat-id)
         (messenger/chat-cleared messenger {:chat-id chat-id :messages true})
         (messenger/chat-opened messenger (assoc-some {:chat-id chat-id} :title title))
         (f.chat.replay/send-chat-contents! messages chat-ctx)
         (f.chat.lifecycle/send-content! chat-ctx :system (assoc-some {:type :metadata} :title title))
         (let [model-selection (config/notify-selected-model-changed!
                                (:model chat) db* messenger config (:variant chat) chat-id)
               trust-selection (config/notify-selected-trust-changed!
                                (:trust chat) db* messenger chat-id)
               agent-selection (config/validate-agent-name
                                (or (:agent chat)
                                    (:defaultAgent (:chat config))
                                    (:defaultAgent config))
                                config)
               selection (merge {:model nil :variant nil :variants []}
                                model-selection
                                {:agent agent-selection
                                 :trust trust-selection})]
           (assoc-some {:found true
                        :chat-id chat-id
                        :title title
                        :selection selection}
                       :meta (when windowed?
                               (f.chat.history/page-meta page (:messages chat))))))))))

(defn fetch-history
  "Window a chat's persisted messages and return the transformed content items
   plus pagination meta, for the request/response `chat/history` method.

   `before`/`after` are opaque cursors (or the `lastCompaction` sentinel) and
   `limit` bounds the page. Returns {:contents [...] :meta {...}}, or
   {:error {:code :message}} when the chat is unknown or a cursor is stale."
  ([params db*]
   (fetch-history params db* nil))
  ([{:keys [chat-id limit before after]} db* metrics]
   (db/hydrate-chat! db* chat-id metrics)
   (let [db @db*
         chat (get-in db [:chats chat-id])]
     (if (or (nil? chat) (:subagent chat))
       {:error {:code "chat_not_found" :message (str "Chat " chat-id " not found")}}
       (let [messages (vec (:messages chat))
             result (f.chat.history/window-messages messages {:limit limit :before before :after after})]
         (if (= :cursor-expired (:error result))
           {:error {:code "cursor_expired"
                    :message "Cursor no longer points to an existing message; refetch the latest page"}}
           {:contents (f.chat.replay/messages->contents (:messages result)
                                          {:chat-id chat-id :parent-chat-id nil :db db})
            :meta (f.chat.history/page-meta result messages)}))))))
