(ns eca.features.chat.callbacks
  "The callbacks of a turn's request to the LLM (`llm-api/sync-or-async-prompt!`):
   streamed text, reasoning, tool calls and the tools the provider runs itself,
   usage, and the next round after tools ran. Each gets the request's `turn`
   map (see `eca.features.chat.turn/prompt-messages!`)."
  (:require
   [cheshire.core :as json]
   [clojure.string :as string]
   [eca.features.background-tasks :as bg]
   [eca.features.chat.compaction :as f.chat.compaction]
   [eca.features.chat.lifecycle :as f.chat.lifecycle]
   [eca.features.chat.recovery :as f.chat.recovery]
   [eca.features.chat.steer :as f.chat.steer]
   [eca.features.chat.tool-batch :as f.chat.tool-batch]
   [eca.features.chat.tool-calls :as f.chat.tool-calls]
   [eca.features.prompt :as f.prompt]
   [eca.features.providers :as f.providers]
   [eca.features.quota :as f.quota]
   [eca.features.tools :as f.tools]
   [eca.llm-api :as llm-api]
   [eca.logger :as logger]
   [eca.modules.events :as events]
   [eca.shared :as shared :refer [assoc-some]]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CHAT]")

(defn ^:private truncated-response?
  "Returns true when the response text shows signs of being truncated mid-stream.
   Checks for unclosed code fences (odd number of ``` markers at line start)."
  [^String text]
  (when-not (string/blank? text)
    (odd? (count (re-seq #"(?m)^```" text)))))

(defn ^:private on-response-headers
  [{:keys [chat-ctx]} headers]
  (let [{:keys [db* config provider messenger]} chat-ctx]
    (when (:subscription? chat-ctx)
      ;; Display-only, so never let it break the request.
      (try
        (when (f.quota/update-from-headers! db* provider headers)
          (f.providers/notify-provider-updated! provider db* config messenger))
        (catch Exception e
          (logger/warn logger-tag "Failed to update provider quota" e))))))

(defn ^:private on-usage-updated
  [{:keys [chat-ctx all-tools]} usage]
  (let [{:keys [db* config chat-id agent full-model instructions]} chat-ctx]
    (when-let [usage (shared/usage-msg->usage usage full-model chat-ctx)]
      (f.chat.compaction/release-post-compact-guard! chat-ctx)
      ;; Never let the context-breakdown (a display-only
      ;; aid) throw into the streaming path, or the error
      ;; gets misclassified as a retryable provider error.
      (let [breakdown (try
                        (shared/context-breakdown
                         {:system-prompt (f.prompt/instructions->str instructions)
                          :tools (f.tools/tools-for-llm all-tools)
                          :messages (get-in @db* [:chats chat-id :messages] [])
                          :context-limit (get-in usage [:limit :context])
                          :session-tokens (:session-tokens usage)})
                        (catch Throwable e
                          (logger/warn logger-tag "Failed to compute context breakdown" e)
                          nil))]
        (f.chat.lifecycle/send-content! chat-ctx :system
                                        (merge {:type :usage} usage
                                               (when breakdown {:context-breakdown breakdown})
                                               (when-let [pct (f.chat.compaction/auto-compact-threshold config agent (get-in usage [:limit :context]))]
                                                 {:auto-compact-percentage pct}))))
      (let [context-limit (get-in usage [:limit :context])
            session-tokens (:session-tokens usage)]
        (events/emit! chat-ctx :chat/usage-updated
                      {:chat-id chat-id
                       :parent-chat-id (:parent-chat-id chat-ctx)
                       :agent agent
                       :model full-model
                       :variant (:variant chat-ctx)
                       :usage usage
                       :session-tokens session-tokens
                       :context-limit context-limit
                       :context-used (when (and (number? session-tokens)
                                                (number? context-limit)
                                                (pos? context-limit))
                                       (double (/ session-tokens context-limit)))})))))

(defn ^:private on-history-sanitized
  "History keeps the foreign entries (only the request is filtered), so they
   are dropped again on every later prompt: notify only on the first prompt
   after swapping to a different api. #660"
  [{:keys [chat-ctx db]} {:keys [dropped-count dropped-apis target-api]}]
  (let [{:keys [chat-id]} chat-ctx]
    (when (not= target-api (get-in db [:chats chat-id :last-api]))
      (let [from-label (->> dropped-apis
                            (remove nil?)
                            (map name)
                            sort
                            (string/join ", "))]
        (logger/info logger-tag
                     (format "Dropped %d cross-provider history entries on switch to %s"
                             dropped-count (some-> target-api name))
                     {:chat-id chat-id
                      :dropped-count dropped-count
                      :dropped-apis dropped-apis})
        (f.chat.lifecycle/send-content!
         chat-ctx :system
         {:type :text
          :text (format "Note: dropped %d history %s from a previous provider (%s) because the current model (%s) cannot reuse its opaque ids. Reasoning context is lost across providers; tool calls were also removed if they originated elsewhere."
                        dropped-count
                        (if (= 1 dropped-count) "entry" "entries")
                        (if (seq from-label) from-label "unknown")
                        (some-> target-api name))})))))

(defn ^:private on-first-response-received
  [{:keys [chat-ctx user-messages model-capabilities add-to-history!]} & _]
  (let [{:keys [db* config chat-id provider model]} chat-ctx]
    (f.chat.lifecycle/assert-chat-not-stopped! chat-ctx)
    ;; Before the history append, which saves the chat,
    ;; so attached contexts are tracked as sent together
    ;; with the messages holding them. #683
    (when-let [sent (:pending-sent-attached-contexts chat-ctx)]
      (swap! db* assoc-in [:chats chat-id :sent-attached-contexts] sent))
    (doseq [message user-messages]
      (add-to-history!
       (assoc message :content-id (:user-content-id chat-ctx))))
    (swap! db* assoc-in [:chats chat-id :last-api] (:api (llm-api/provider->api-handler provider model model-capabilities config)))
    (f.chat.lifecycle/send-content! chat-ctx :system {:type :progress
                                                      :state :running
                                                      :text "Generating"})))

(defn ^:private finish-response!
  "The response ended: its text goes to the history and the request
   finishes, or, when the response looks cut (premature, or an unclosed code
   fence), the model is asked to go on (auto-continue)."
  [{:keys [chat-ctx received-msgs* auto-continue-count* auto-continue-limit add-to-history!]} msg]
  (let [{:keys [db* chat-id] :as chat-ctx} (assoc chat-ctx :auto-continue-count @auto-continue-count*)
        response-text @received-msgs*
        stopping? (identical? :stopping (get-in @db* [:chats chat-id :status]))]
    (when-not (string/blank? response-text)
      (add-to-history! {:role "assistant"
                        :content [{:type :text :text response-text}]}))
    (if (and (not stopping?)
             (not (string/blank? response-text))
             (or (:premature? msg)
                 (truncated-response? response-text))
             (< (:auto-continue-count chat-ctx 0) auto-continue-limit)
             (not (or (:on-finished-side-effect chat-ctx)
                      (:on-after-finish! chat-ctx))))
      (do
        (logger/info logger-tag "Truncated or premature response detected, auto-continuing"
                     {:chat-id chat-id
                      :premature? (:premature? msg)
                      :truncated? (truncated-response? response-text)
                      :attempt (inc (:auto-continue-count chat-ctx 0))
                      :max-auto-continues auto-continue-limit})
        (f.chat.lifecycle/send-content! chat-ctx :system
                                        {:type :progress :state :running
                                         :text (format "Response interrupted, continuing (recovery %d/%d)..."
                                                       (inc (:auto-continue-count chat-ctx 0)) auto-continue-limit)})
        (f.chat.recovery/continue-turn! chat-ctx
                                        [(f.chat.recovery/user-message f.chat.recovery/interrupted-text)]
                                        :auto-continue
                                        (update chat-ctx :auto-continue-count (fnil inc 0))))
      (f.chat.lifecycle/finish-chat-prompt!
       :idle
       (assoc-some chat-ctx :response (some-> response-text string/trim not-empty))))))

(defn ^:private on-message-received
  [{:keys [chat-ctx received-msgs* add-to-history!] :as turn} {:keys [type] :as msg}]
  (let [{:keys [db* chat-id]} chat-ctx]
    (f.chat.lifecycle/assert-chat-not-stopped! chat-ctx)
    (case type
      :text (do (swap! received-msgs* str (:text msg))
                (f.chat.lifecycle/send-content! chat-ctx :assistant {:type :text :text (:text msg)}))
      :url (f.chat.lifecycle/send-content! chat-ctx :assistant {:type :url :title (:title msg) :url (:url msg)})
      :image (let [client-content {:type :image
                                   :media-type (:media-type msg)
                                   :base64 (:base64 msg)}
                   history-content (assoc-some
                                    {:media-type (:media-type msg)
                                     :base64 (:base64 msg)}
                                    :id (:id msg))]
               ;; Provider normalize-messages converts this role back to a user-role image for replay.
               (add-to-history! {:role "image_generation_call"
                                 :content history-content})
               (f.chat.lifecycle/send-content! chat-ctx :assistant client-content))
      :limit-reached (do (f.chat.lifecycle/send-content!
                          chat-ctx
                          :system
                          {:type :text
                           :text (str "API limit reached. Tokens: "
                                      (json/generate-string (:tokens msg)))})
                         (swap! db* update-in [:chats chat-id] dissoc :auto-compacting? :continuing? :compacting?)
                         (f.chat.lifecycle/finish-chat-prompt-stopped! :idle chat-ctx))
      :refusal (do (f.chat.lifecycle/send-content!
                    chat-ctx
                    :system
                    {:type :text
                     :text (str "The model declined to generate this response (safety classifier refusal"
                                (when-let [category (:category msg)]
                                  (str ", category: " category))
                                ")."
                                (when-let [explanation (:explanation msg)]
                                  (str " " explanation))
                                " Try rephrasing or switching to a different model.")})
                   (swap! db* update-in [:chats chat-id] dissoc :auto-compacting? :continuing? :compacting?)
                   (f.chat.lifecycle/finish-chat-prompt-stopped! :idle chat-ctx))
      :finish (finish-response! turn msg))))

(defn ^:private on-prepare-tool-call
  [{:keys [chat-ctx all-tools]} {:keys [id full-name arguments-text]}]
  (let [{:keys [db* config]} chat-ctx]
    (f.chat.lifecycle/assert-chat-not-stopped! chat-ctx)
    (let [tool (f.tools/resolve-tool full-name all-tools)
          resolved-full-name (or (:full-name tool) full-name)]
      (when-not tool
        (logger/warn logger-tag "Tool not found for prepare"
                     {:full-name full-name
                      :available-tools (mapv :full-name all-tools)}))
      (f.chat.tool-calls/transition-tool-call! db* chat-ctx id :tool-prepare
                                               {:name (or (:name tool) full-name)
                                                :server (:name (:server tool))
                                                :full-name resolved-full-name
                                                :origin (or (:origin tool) :unknown)
                                                :arguments-text arguments-text
                                                :summary (f.tools/tool-call-summary all-tools resolved-full-name nil config @db*)}))))

(defn ^:private consume-pending-job-notifications!
  "Reads and clears any pending job notifications for the chat in a single swap.
   For each notification, adds a user message to history so the LLM is aware.
   Marks each job as :notified true and evicts them from the registry."
  [chat-id db* add-to-history!]
  (let [notifications* (volatile! nil)]
    (swap! db* (fn [db]
                 (if-let [notifs (seq (get-in db [:chats chat-id :pending-job-notifications]))]
                   (do (vreset! notifications* (vec notifs))
                       (assoc-in db [:chats chat-id :pending-job-notifications] []))
                   db)))
    (when-let [notifications @notifications*]
      (doseq [{:keys [job-id status exit-code label output-tail]} notifications]
        (let [text (str "Background job " job-id " (`" label "`) "
                        (name status) " with exit code " exit-code "."
                        (when (seq output-tail)
                          (str "\nLast 20 lines of output:\n"
                               (string/join "\n" output-tail))))
              user-message {:role "user"
                            :content [{:type :text :text text}]
                            :content-id (str (random-uuid))}]
          (add-to-history! user-message)))
      (doseq [{:keys [job-id]} notifications]
        (swap! bg/registry* assoc-in [:jobs job-id :notified] true))
      (bg/evict-notified-jobs!))))

(defn ^:private next-tool-round
  "After the tools of a response ran (`:continue-fn` of the tool batch): the
   next request of the LLM's tool loop, `{:tools :new-messages}` with the
   steered message and finished background jobs, or nil when the turn ends
   or goes on otherwise (a `/compact` steered meanwhile, a compaction a
   module asked for, the auto-compaction)."
  [{:keys [chat-ctx auto-continue-count* add-to-history!]} tc-all-tools tc-user-messages]
  (reset! auto-continue-count* 0)
  (let [{:keys [db* config chat-id agent full-model] :as chat-ctx} (assoc chat-ctx :auto-continue-count 0)
        continue-round (fn []
                         (f.chat.steer/consume-steer-message! chat-id db* chat-ctx add-to-history!)
                         (consume-pending-job-notifications! chat-id db* add-to-history!)
                         {:tools tc-all-tools
                          :new-messages (f.chat.recovery/messages-to-send @db* chat-id full-model)})]
    (cond
      ;; Manual /compact maintenance prompt: skip postRequest hooks
      ;; (postCompact provides compact_summary; postRequest would
      ;; just see the trailing "Compacted successfully!" tool result).
      (get-in @db* [:chats chat-id :compact-done?])
      (do (f.chat.lifecycle/finish-chat-prompt! :idle
                                                (assoc chat-ctx :skip-post-request-hooks? true))
          nil)

      :else
      ;; A /compact steered while the turn was running is applied here
      ;; with its instructions, then the task resumes. Checked before the
      ;; threshold so the chat is not compacted twice. #600
      (if-let [{:keys [custom-instructions content-id]} (f.chat.steer/consume-steered-compact! chat-id db* chat-ctx)]
        (f.chat.compaction/trigger-compact! (assoc chat-ctx :user-content-id content-id)
                                            tc-all-tools
                                            tc-user-messages
                                            {:trigger "manual"
                                             :custom-instructions custom-instructions
                                             :on-blocked continue-round})
        (do
          ;; Other steered native commands safe to run mid-turn execute here
          ;; and the task resumes, so the LLM never sees them as text. #610
          (f.chat.steer/consume-steered-native-command! chat-id db* chat-ctx)
          (if-let [{:keys [result]} (f.chat.compaction/run-requested-compaction!
                                     chat-ctx tc-all-tools tc-user-messages
                                     continue-round)]
            result
            (if (and (f.chat.compaction/auto-compact? chat-id agent full-model config @db*)
                     (not (f.chat.compaction/auto-compact-suppressed? chat-ctx)))
              (f.chat.compaction/trigger-auto-compact! chat-ctx tc-all-tools tc-user-messages)
              (continue-round))))))))

(defn ^:private on-reason
  [{:keys [chat-ctx reasonings* add-to-history!]} {:keys [status id text external-id delta-reasoning? redacted? data]}]
  (f.chat.lifecycle/assert-chat-not-stopped! chat-ctx)
  (case status
    :started  (do (swap! reasonings* assoc-in [id :start-time] (System/currentTimeMillis))
                  (when redacted?
                    (swap! reasonings* assoc-in [id :redacted?] true)
                    (swap! reasonings* assoc-in [id :data] data))
                  (f.chat.lifecycle/send-content! chat-ctx :assistant {:type :reasonStarted :id id}))
    :thinking (do (swap! reasonings* update-in [id :text] str text)
                  (f.chat.lifecycle/send-content! chat-ctx :assistant {:type :reasonText :id id :text text}))
    :finished (when-let [start-time (get-in @reasonings* [id :start-time])]
                (let [total-time-ms (- (System/currentTimeMillis) start-time)
                      reasoning (get @reasonings* id)]
                  (add-to-history! {:role "reason"
                                    :content (cond-> {:id id
                                                      :external-id external-id
                                                      :delta-reasoning? delta-reasoning?
                                                      :total-time-ms total-time-ms
                                                      :text (:text reasoning)}
                                               (:redacted? reasoning)
                                               (assoc :redacted? true
                                                      :data (:data reasoning)))})
                  (f.chat.lifecycle/send-content! chat-ctx :assistant {:type :reasonFinished :total-time-ms total-time-ms :id id})))
    nil))

(defn ^:private server-tool-started!
  "A tool the LLM provider runs itself (web search, image generation)
   started: for clients, it goes through the tool call states as an
   approved call."
  [{:keys [chat-ctx server-tool-times*]} id {:keys [name arguments summary progress-text]}]
  (let [{:keys [db*]} chat-ctx]
    (swap! server-tool-times* assoc id (System/currentTimeMillis))
    (f.chat.tool-calls/transition-tool-call! db* chat-ctx id :tool-prepare
                                             {:name name
                                              :server :llm
                                              :origin :server
                                              :arguments-text ""
                                              :summary summary})
    (f.chat.tool-calls/transition-tool-call! db* chat-ctx id :tool-run
                                             {:approved?* (promise)
                                              :future-cleanup-complete?* (promise)
                                              :name name
                                              :server :llm
                                              :origin :server
                                              :arguments arguments
                                              :manual-approval false
                                              :summary summary})
    (f.chat.tool-calls/transition-tool-call! db* chat-ctx id :approval-allow
                                             {:reason :server-tool})
    (f.chat.tool-calls/transition-tool-call! db* chat-ctx id :execution-start
                                             {:delayed-future (delay nil)
                                              :origin :server
                                              :name name
                                              :server :llm
                                              :arguments arguments
                                              :start-time (System/currentTimeMillis)
                                              :summary summary
                                              :progress-text progress-text})))

(defn ^:private server-tool-time-ms [{:keys [server-tool-times*]} id]
  (if-let [start-time (get @server-tool-times* id)]
    (- (System/currentTimeMillis) start-time)
    0))

(defn ^:private server-tool-finished!
  "A tool the LLM provider runs itself finished, see `server-tool-started!`."
  [{:keys [chat-ctx]} id {:keys [default-name outputs total-time-ms summary]}]
  (let [{:keys [db* chat-id]} chat-ctx
        tool-name (get-in (f.chat.tool-calls/get-tool-call-state @db* chat-id id) [:name] default-name)]
    (f.chat.tool-calls/transition-tool-call! db* chat-ctx id :execution-end
                                             {:origin :server
                                              :name tool-name
                                              :server :llm
                                              :arguments {}
                                              :error false
                                              :outputs outputs
                                              :total-time-ms total-time-ms
                                              :progress-text "Generating"
                                              :summary summary})
    (f.chat.tool-calls/transition-tool-call! db* chat-ctx id :cleanup-finished
                                             {:name tool-name})))

(defn ^:private on-server-web-search
  [{:keys [chat-ctx pending-server-tool-uses* add-to-history!] :as turn}
   {:keys [status id name input output raw-content]}]
  (f.chat.lifecycle/assert-chat-not-stopped! chat-ctx)
  (let [summary (format "Web searching%s"
                        (if-let [query (:query input)]
                          (format " '%s'" query)
                          ""))
        arguments (or input {})]
    (case status
      :started (server-tool-started! turn id {:name name
                                              :arguments arguments
                                              :summary summary
                                              :progress-text "Searching the web"})
      :input-ready (swap! pending-server-tool-uses* assoc id
                          {:role "server_tool_use"
                           :content {:id id
                                     :name name
                                     :input arguments}})
      :finished (let [total-time-ms (server-tool-time-ms turn id)
                      outputs (when (seq output)
                                (mapv (fn [{:keys [title url]}]
                                        {:type :text
                                         :text (format "%s: %s" title url)})
                                      output))]
                  (when-let [pending-tool-use (get @pending-server-tool-uses* id)]
                    (add-to-history! pending-tool-use)
                    (swap! pending-server-tool-uses* dissoc id))
                  (add-to-history! {:role "server_tool_result"
                                    :content {:tool-use-id id
                                              :raw-content raw-content}})
                  (server-tool-finished! turn id {:default-name "web_search"
                                                  :outputs outputs
                                                  :total-time-ms total-time-ms
                                                  :summary summary}))
      nil)))

(defn ^:private on-server-image-generation
  [{:keys [chat-ctx] :as turn} {:keys [status id name]}]
  (f.chat.lifecycle/assert-chat-not-stopped! chat-ctx)
  (let [summary "Generating image"]
    (case status
      :started (server-tool-started! turn id {:name name
                                              :arguments {}
                                              :summary summary
                                              :progress-text "Generating image"})
      :finished (server-tool-finished! turn id {:default-name "image_generation"
                                                :outputs [{:type :text :text "Generated image (png)"}]
                                                :total-time-ms (server-tool-time-ms turn id)
                                                :summary summary})
      nil)))

(defn callbacks
  "The callbacks of the request of `turn`."
  [{:keys [chat-ctx user-messages received-msgs* add-to-history!] :as turn}]
  {:on-retry (partial f.chat.recovery/on-retry turn)
   :on-history-sanitized (partial on-history-sanitized turn)
   :on-first-response-received (partial on-first-response-received turn)
   :on-usage-updated (partial on-usage-updated turn)
   :on-response-headers (partial on-response-headers turn)
   :on-message-received (partial on-message-received turn)
   :on-prepare-tool-call (partial on-prepare-tool-call turn)
   :on-tools-called (f.chat.tool-batch/on-tools-called!
                     (assoc chat-ctx :continue-fn (partial next-tool-round turn))
                     received-msgs* add-to-history! user-messages)
   :on-reason (partial on-reason turn)
   :on-server-web-search (partial on-server-web-search turn)
   :on-server-image-generation (partial on-server-image-generation turn)
   :on-error (partial f.chat.recovery/on-error turn)})
