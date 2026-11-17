(ns eca.features.chat.recovery
  "What a turn does when its request fails or its response is cut: modules
   asked first (`:llm/request-failed`), then compaction on overflow, a retry
   without images, the provider's recovery, auto-continue on transient
   errors; else the turn ends with the error."
  (:require
   [clojure.string :as string]
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.chat.compaction :as f.chat.compaction]
   [eca.features.chat.lifecycle :as f.chat.lifecycle]
   [eca.features.chat.tool-calls :as f.chat.tool-calls]
   [eca.llm-api.errors :as llm-providers.errors]
   [eca.logger :as logger]
   [eca.shared :as shared :refer [assoc-some]]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CHAT]")

(def ^:private default-max-auto-continues 3)

(defn provider-max-auto-continues [config provider]
  (let [configured (get-in config [:providers provider :retry :maxAutoContinues])]
    (if (and (integer? configured) (not (neg? configured)))
      (long configured)
      default-max-auto-continues)))

(defn ^:private prompt-error-data
  "Returns serializable terminal prompt error details for chat state."
  [{:keys [message exception status code request-id response-id rate-limit-resets-at]} error-type]
  (assoc-some {:message (or (shared/not-blank message)
                            (some-> exception ex-message shared/not-blank)
                            "Unknown provider error")
               :error-type error-type}
              :status status
              :code code
              :request-id request-id
              :response-id response-id
              :rate-limit-resets-at rate-limit-resets-at))

(def ^:private removed-image-placeholder
  {:type :text :text "[image removed: rejected by the LLM provider]"})

(defn ^:private strip-messages-images
  "Replaces :image content entries (user attachments and MCP tool result
   images) with a text placeholder in the given messages. Returns
   {:messages messages :stripped n} where n is the number of images replaced."
  [messages]
  (let [stripped* (volatile! 0)
        strip-contents (fn [contents]
                         (if (sequential? contents)
                           (mapv (fn [content]
                                   (if (and (map? content) (= :image (:type content)))
                                     (do (vswap! stripped* inc)
                                         removed-image-placeholder)
                                     content))
                                 contents)
                           contents))
        messages (mapv (fn [{:keys [role] :as msg}]
                         (case role
                           "user" (update msg :content strip-contents)
                           "tool_call_output" (if (sequential? (get-in msg [:content :output :contents]))
                                                (update-in msg [:content :output :contents] strip-contents)
                                                msg)
                           msg))
                       messages)]
    {:messages messages
     :stripped @stripped*}))

(defn messages-to-send
  "History slice sent to the LLM. When this chat's current model previously
   rejected an image (`:images-rejected-by-model`, e.g. below xAI's minimum
   pixels), image contents are stripped at send time so images (including new
   tool results) cannot poison every subsequent request. History itself is
   left untouched: clients keep displaying images and switching to an
   image-capable model replays them again."
  [db chat-id full-model]
  (let [messages (shared/messages-after-last-compact-marker
                  (get-in db [:chats chat-id :messages] []))]
    (if (= full-model (get-in db [:chats chat-id :images-rejected-by-model]))
      (:messages (strip-messages-images messages))
      messages)))

(def ^:private interrupted-tool-call-reason
  {:code :interrupted
   :text "Tool call interrupted because the LLM response failed"})

(defn reject-active-tool-calls!
  "Requests a stop for every unfinished tool call of the chat, so the ones
   that won't make progress anymore are rejected for clients."
  [db* {:keys [chat-id] :as chat-ctx} reason]
  (doseq [[tool-call-id _] (f.chat.tool-calls/get-active-tool-calls @db* chat-id)]
    (f.chat.tool-calls/transition-tool-call! db* chat-ctx tool-call-id :stop-requested {:reason reason})))

(defn ^:private duration-str
  "Humanizes a millis duration, e.g. 42s, 3m05s, 2h14m."
  [ms]
  (let [total-secs (max 0 (quot (long ms) 1000))
        h (quot total-secs 3600)
        m (quot (mod total-secs 3600) 60)
        s (mod total-secs 60)]
    (cond
      (pos? h) (format "%dh%02dm" h m)
      (pos? m) (format "%dm%02ds" m s)
      :else (format "%ds" s))))

(def interrupted-text
  "Asks the model to go on with a response that was cut."
  "Your previous response was interrupted mid-stream. Continue from where you left off, do not redo completed steps.")

(defn user-message [text]
  {:role "user"
   :content [{:type :text :text text}]})

(defn continue-turn!
  "Ends this request without ending the turn, then sends `user-messages` as
   the turn's next request, with `next-ctx` as its chat-ctx (see
   `:continue-turn-fn`): auto-continue and retries. Meanwhile the chat has
   `:continuing?`, so finishing the request doesn't end the turn."
  [{:keys [db* chat-id continue-turn-fn] :as chat-ctx} user-messages source-type next-ctx]
  (swap! db* assoc-in [:chats chat-id :continuing?] true)
  (f.chat.lifecycle/finish-chat-prompt! :idle
                                        (assoc chat-ctx
                                               :on-finished-side-effect
                                               (fn []
                                                 (swap! db* update-in [:chats chat-id] dissoc :continuing?))
                                               :on-after-finish!
                                               (fn []
                                                 (continue-turn-fn user-messages source-type next-ctx)))))

(defn ^:private user-messages-in-history?
  "Whether the turn's user messages reached the chat history, which they do
   once the LLM starts responding."
  [{:keys [db* chat-id user-content-id]}]
  (boolean (when user-content-id
             (some #(= user-content-id (:content-id %))
                   (get-in @db* [:chats chat-id :messages])))))

(defn on-retry
  "Shows why the provider's request is sent again, and when (`:on-retry`)."
  [{:keys [chat-ctx]} {:keys [attempt max-retries delay-ms resets-at classified]}]
  (let [{error-type :error/type error-label :error/label} classified
        reason (or error-label
                   (case error-type
                     :rate-limited "Rate limited"
                     :overloaded "Provider overloaded"
                     :network "Network issues"
                     :premature-stop "Empty response"
                     "Transient error"))]
    (f.chat.lifecycle/send-content! chat-ctx :system
                                    {:type :progress
                                     :state :running
                                     :text (if resets-at
                                             (format "⏳ %s. Resuming at %s (in %s, attempt %d/%d)"
                                                     reason
                                                     (shared/ms->presentable-date resets-at "HH:mm")
                                                     (duration-str delay-ms)
                                                     attempt max-retries)
                                             (format "⏳ %s. Retrying in %ds (attempt %d/%d)"
                                                     reason (quot delay-ms 1000) attempt max-retries))})))

(defn ^:private compact-as-module-asked!
  "A module asked to compact, then go on with the task. A context overflow
   is shown, and first prunes old tool outputs."
  [{:keys [user-messages all-tools]} {:keys [db* chat-id] :as chat-ctx} {:keys [error-type module-answer]}]
  (let [opts (:compact module-answer)]
    (if (= :context-overflow error-type)
      (do
        (logger/warn logger-tag "Context overflow detected, pruning tool results and auto-compacting"
                     {:chat-id chat-id})
        (f.chat.lifecycle/send-content! chat-ctx :system
                                        {:type :text
                                         :text "Context window exceeded. Auto-compacting conversation..."})
        (f.chat.compaction/prune-tool-results! db* chat-id {}))
      (logger/info logger-tag "Compacting after a failed request, as a module asked"
                   {:chat-id chat-id :error-type error-type}))
    (f.chat.compaction/trigger-requested-compact! chat-ctx all-tools user-messages
                                                  {:trigger (or (when (map? opts) (:trigger opts))
                                                                (when (= :context-overflow error-type) :overflow))
                                                   :instructions (when (map? opts) (:instructions opts))}
                                                  (f.chat.compaction/retry-without-compaction chat-ctx user-messages))))

(defn ^:private retry-as-module-asked!
  "A module asked to send the request again."
  [{:keys [user-messages]} {:keys [chat-id] :as chat-ctx} {:keys [module-answer]}]
  (let [retry-messages (if (user-messages-in-history? chat-ctx)
                         [(user-message "The previous request failed and was sent again. Continue with the task, do not redo completed steps.")]
                         user-messages)]
    (when (and (map? (:retry module-answer)) (seq (:retry module-answer)))
      (logger/warn logger-tag "Retrying the failed request as is: request changes aren't supported yet"
                   {:chat-id chat-id :changes (:retry module-answer)}))
    (f.chat.lifecycle/send-content! chat-ctx :system {:type :text :text "\nRetrying the request, as a module asked...\n"})
    (continue-turn! chat-ctx retry-messages :module-retry (assoc chat-ctx :module-recovery-attempted? true))))

(defn ^:private retry-without-images!
  "The provider rejected an image in the request (e.g. an MCP tool returned
   an image below xAI's 512 total-pixels minimum). Such requests fail
   deterministically on every replay, which would otherwise poison the chat
   forever: images become text placeholders and the request goes once more."
  [{:keys [user-messages]} {:keys [db* chat-id full-model] :as chat-ctx}]
  (let [;; When the failure happened before the first response,
        ;; user-messages were never added to history and must be
        ;; re-sent (image-stripped) on retry; otherwise just nudge
        ;; the model to continue from the sanitized history.
        user-msgs-in-history? (user-messages-in-history? chat-ctx)
        {stripped-user-messages :messages user-stripped :stripped} (strip-messages-images user-messages)
        retry-messages (if user-msgs-in-history?
                         [(user-message "An image in the conversation was rejected by the LLM provider and was replaced with a placeholder. Continue the task with the remaining content, do not redo completed steps.")]
                         stripped-user-messages)]
    (logger/warn logger-tag "Provider rejected an image, omitting images for this model and retrying"
                 {:chat-id chat-id
                  :full-model full-model
                  :user-messages-stripped user-stripped})
    ;; Makes messages-to-send strip images from every
    ;; subsequent request while this model is selected, so
    ;; new tool-result images cannot re-poison the chat.
    (swap! db* assoc-in [:chats chat-id :images-rejected-by-model] full-model)
    (f.chat.lifecycle/send-content! chat-ctx :system
                                    {:type :text
                                     :text "The LLM provider rejected an image in the conversation. Retrying without images (omitted while this model is selected)..."})
    (continue-turn! chat-ctx retry-messages :invalid-image-retry (assoc chat-ctx :image-retried? true))))

(defn ^:private recover-provider-error!
  "The provider's interactive recovery for a terminal request error (e.g.
   Copilot asks consent to enable a per-model policy), then a retry."
  [{:keys [user-messages]}
   {:keys [db* config chat-id provider model full-model messenger metrics prompt-id] :as chat-ctx}
   {:keys [error-data error-type db]}]
  (let [real-model (or (get-in db [:models full-model :model-name]) model)
        {:keys [retry? notice retry-user-message]}
        (llm-providers.errors/recover-error! {:provider provider
                                              :provider-base (config/provider-base provider config)
                                              :config config
                                              :model real-model
                                              :error-data error-data
                                              :db db
                                              :messenger messenger
                                              :chat-id chat-id})]
    (if retry?
      (let [retry-messages (if (user-messages-in-history? chat-ctx)
                             [(user-message (or retry-user-message
                                                "The provider issue was just resolved. Continue with the original request."))]
                             user-messages)
            finished-or-superseded? (or (get-in @db* [:chats chat-id :prompt-finished?])
                                        (not= prompt-id (get-in @db* [:chats chat-id :prompt-id])))]
        (logger/info logger-tag "Provider error recovered"
                     {:chat-id chat-id :provider provider :model real-model})
        (if finished-or-superseded?
          (logger/info logger-tag "Skipping retry, prompt finished or superseded" {:chat-id chat-id})
          (do
            (f.chat.lifecycle/send-content! chat-ctx :system
                                            {:type :text
                                             :text (or notice "\nRecovered from provider error, retrying...\n")})
            (continue-turn! chat-ctx retry-messages :error-recovery-retry (assoc chat-ctx :error-recovery-attempted? true)))))
      (let [text (or notice (str "\n" (:message error-data)))]
        (swap! db* assoc-in [:chats chat-id :prompt-error]
               (prompt-error-data error-data error-type))
        (f.chat.lifecycle/send-content! chat-ctx :system {:type :text :text text})
        (db/save-chat! @db* chat-id metrics)
        (f.chat.lifecycle/finish-chat-prompt! :idle (f.chat.lifecycle/strip-hook-callbacks chat-ctx))))))

(defn ^:private error-text
  "What the user reads when the request failed for good."
  [{:keys [message exception] :as error-data} error-type recovery-blocked-reason auto-continue-count auto-continue-limit]
  (if (= :context-overflow error-type)
    (str "\n\nContext window exceeded: this request is larger than the model's context window"
         " (system prompt, MCP server instructions and messages combined)."
         " Try a model with a larger context window, or reduce enabled MCP servers/context.")
    (str "\n\n" (or message (str "Error: " (or (ex-message exception) (.getName (class exception)))))
         (case recovery-blocked-reason
           :disabled "\nAutomatic recovery is disabled for this provider (retry.maxAutoContinues: 0)."
           :limit-reached (format "\nAutomatic recovery limit reached (%d/%d in a row). Send a new message to continue."
                                  auto-continue-count auto-continue-limit)
           nil)
         (when-let [resets-at (:rate-limit-resets-at error-data)]
           (let [in-ms (- (long resets-at) (System/currentTimeMillis))]
             (format "\nRate limit resets at %s%s."
                     (shared/ms->presentable-date resets-at "HH:mm")
                     (if (pos? in-ms) (str " (in " (duration-str in-ms) ")") "")))))))

(defn ^:private auto-continue-or-fail!
  "A transient error (overloaded, network, premature stop, idle timeout)
   auto-continues the response, or sends the request again when nothing of
   it arrived, up to the provider's `retry.maxAutoContinues` in a row; any
   other error ends the turn with it."
  [{:keys [user-messages received-msgs* auto-continue-limit add-to-history!]}
   {:keys [db* chat-id metrics] :as chat-ctx}
   {:keys [error-data error-type compacting?]}]
  (let [{:keys [message idle-timeout?]} error-data
        partial-text @received-msgs*
        transient-error? (or (contains? #{:overloaded :premature-stop :network} error-type)
                             idle-timeout?)
        auto-continue-count (:auto-continue-count chat-ctx 0)
        stopping? (identical? :stopping (get-in @db* [:chats chat-id :status]))
        continue-existing-response? (or (not (string/blank? partial-text))
                                        (user-messages-in-history? chat-ctx))
        retry-messages (if continue-existing-response?
                         [(user-message (cond-> interrupted-text
                                          idle-timeout? (str " If you were writing a large file or edit, split it into a few smaller calls.")))]
                         user-messages)
        retry-source-type (if continue-existing-response?
                            :auto-continue
                            :transient-error-retry)
        recovery-blocked-reason (cond
                                  (not transient-error?) :non-transient
                                  stopping? :stopping
                                  compacting? :compacting
                                  (or (:on-finished-side-effect chat-ctx)
                                      (:on-after-finish! chat-ctx)) :finish-callback
                                  (zero? auto-continue-limit) :disabled
                                  (>= auto-continue-count auto-continue-limit) :limit-reached)
        can-auto-continue? (nil? recovery-blocked-reason)]
    (when compacting?
      (swap! db* update-in [:chats chat-id] dissoc :auto-compacting? :continuing? :compacting?))
    (when-not (string/blank? partial-text)
      (add-to-history! {:role "assistant"
                        :content [{:type :text :text partial-text}]}))
    (if can-auto-continue?
      (do
        (logger/info logger-tag "Transient error during response, auto-continuing"
                     {:chat-id chat-id :error-type error-type
                      :attempt (inc auto-continue-count)
                      :max-auto-continues auto-continue-limit})
        (f.chat.lifecycle/send-content! chat-ctx :system
                                        {:type :progress
                                         :state :running
                                         :text (str (or message "Connection interrupted")
                                                    (if continue-existing-response?
                                                      ", continuing"
                                                      ", retrying original request")
                                                    (format " (recovery %d/%d)..."
                                                            (inc auto-continue-count) auto-continue-limit))})
        (continue-turn! chat-ctx retry-messages retry-source-type (update chat-ctx :auto-continue-count (fnil inc 0))))
      (do
        (when transient-error?
          (logger/info logger-tag "Automatic recovery skipped"
                       {:chat-id chat-id :error-type error-type
                        :reason recovery-blocked-reason
                        :auto-continue-count auto-continue-count
                        :max-auto-continues auto-continue-limit}))
        (when-not stopping?
          (swap! db* assoc-in [:chats chat-id :prompt-error]
                 (prompt-error-data error-data error-type))
          (f.chat.lifecycle/send-content! chat-ctx :system
                                          {:type :text
                                           :text (error-text error-data error-type recovery-blocked-reason
                                                             auto-continue-count auto-continue-limit)}))
        ;; Defensive save: finish-chat-prompt! can short-circuit when
        ;; :prompt-finished? was already set or the prompt-id rotated,
        ;; which would leave a chat that hit an error without a save.
        ;; Persist explicitly so users can always /resume an errored chat.
        (db/save-chat! @db* chat-id metrics)
        (f.chat.lifecycle/finish-chat-prompt! :idle (f.chat.lifecycle/strip-hook-callbacks chat-ctx))))))

(defn on-error
  "The request failed (`:on-error`). Modules are asked first
   (`:llm/request-failed`, once per prompt): they may compact or retry. Else
   an overflow auto-compacts, a rejected image is dropped, a provider may
   recover, and a transient error auto-continues; otherwise the turn ends
   with the error."
  [{:keys [chat-ctx user-messages auto-continue-count*] :as turn} {:keys [message] :as error-data}]
  (let [{:keys [db* config chat-id provider full-model prompt-id] :as chat-ctx}
        (assoc chat-ctx :auto-continue-count @auto-continue-count*)
        {error-type :error/type} (llm-providers.errors/classify-error error-data)
        db @db*
        ;; A dead shared connection makes every stacked tool-continuation
        ;; request fail: only the first error belongs to this prompt, later
        ;; ones arrive after it finished or was superseded by an
        ;; auto-continue and must not mutate state or reach the user. #547
        stale? (or (get-in db [:chats chat-id :prompt-finished?])
                   (not= prompt-id (get-in db [:chats chat-id :prompt-id])))
        ;; busy: compacting, or handing over to the turn's next request
        compacting? (or (get-in db [:chats chat-id :compacting?])
                        (get-in db [:chats chat-id :auto-compacting?])
                        (get-in db [:chats chat-id :continuing?]))
        ;; Only auto-compact when there's conversation to compact. Compaction
        ;; can't shrink the static system prompt (incl. MCP server instructions);
        ;; with empty history it would just re-issue the same oversized request
        ;; under a new prompt-id and the error would never surface to the user. (#491)
        compactable? (boolean (seq (shared/messages-after-last-compact-marker
                                    (get-in db [:chats chat-id :messages] []))))
        ;; Modules are asked first (:llm/request-failed), once per prompt.
        module-answer (when-not (or stale? compacting? (:module-recovery-attempted? chat-ctx))
                        (f.chat.lifecycle/request-failed-answer
                         chat-ctx
                         {:model full-model :user-messages user-messages}
                         (assoc-some {:type error-type}
                                     :message message
                                     :status (:status error-data))))
        failure {:error-data error-data
                 :error-type error-type
                 :db db
                 :compacting? compacting?
                 :module-answer module-answer}]
    ;; Tool calls the failed response was still streaming are never
    ;; resumed (a retry starts new ones), so reject them or clients
    ;; keep showing them as in progress forever.
    (when-not stale?
      (reject-active-tool-calls! db* chat-ctx interrupted-tool-call-reason))
    (cond
      stale?
      (logger/info logger-tag "Ignoring error for finished or superseded prompt"
                   {:chat-id chat-id :message message})

      ;; only a chat with history can compact (#491), and no more than the
      ;; turn may (see `auto-compact-suppressed?`)
      (and (:compact module-answer)
           (or (not= :context-overflow error-type) compactable?)
           (not (f.chat.compaction/auto-compact-suppressed? chat-ctx)))
      (compact-as-module-asked! turn chat-ctx failure)

      (:retry module-answer)
      (retry-as-module-asked! turn chat-ctx failure)

      (and (= :invalid-image error-type)
           (not compacting?)
           (not (:image-retried? chat-ctx)))
      (retry-without-images! turn chat-ctx)

      ;; At most one recovery attempt per prompt.
      (and (not compacting?)
           (not (:error-recovery-attempted? chat-ctx))
           (llm-providers.errors/recoverable-error? {:provider provider
                                                     :provider-base (config/provider-base provider config)
                                                     :config config
                                                     :error-data error-data
                                                     :db db}))
      (recover-provider-error! turn chat-ctx failure)

      :else
      (auto-continue-or-fail! turn chat-ctx failure))))

(defn request-threw!
  "The request threw `e`, not a provider error (`on-error` gets those): the
   turn ends with it, unless it stopped (a `:silent?` exception)."
  [{:keys [chat-ctx received-msgs* add-to-history!]} e]
  (let [{:keys [db* chat-id metrics]} chat-ctx]
    (when-not (:silent? (ex-data e))
      (logger/error e)
      (reject-active-tool-calls! db* chat-ctx interrupted-tool-call-reason)
      (swap! db* assoc-in [:chats chat-id :prompt-error]
             (prompt-error-data {:exception e} :unknown))
      (swap! db* update-in [:chats chat-id] dissoc :auto-compacting? :continuing? :compacting?)
      (when-not (string/blank? @received-msgs*)
        (add-to-history! {:role "assistant"
                          :content [{:type :text :text @received-msgs*}]}))
      (f.chat.lifecycle/send-content! chat-ctx :system {:type :text :text (str "\n\n" "Error: " (or (ex-message e) (.getName (class e))))})
      ;; Belt-and-suspenders: persist before finish-chat-prompt!,
      ;; which may short-circuit. See note in `auto-continue-or-fail!`.
      (db/save-chat! @db* chat-id metrics)
      (f.chat.lifecycle/finish-chat-prompt! :idle (f.chat.lifecycle/strip-hook-callbacks chat-ctx)))))
