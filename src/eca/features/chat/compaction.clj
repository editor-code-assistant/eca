(ns eca.features.chat.compaction
  "Compaction: when the chat compacts (the thresholds, a module's request, a
   context overflow, a steered `/compact`), the compaction events and
   checkpoints, the summary prompt in the middle of a turn, and pruning old
   tool results."
  (:require
   [clojure.string :as string]
   [eca.db :as db]
   [eca.features.chat.lifecycle :as f.chat.lifecycle]
   [eca.features.prompt :as f.prompt]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.message-sanitize :as message-sanitize]
   [eca.messenger :as messenger]
   [eca.modules :as modules]
   [eca.modules.events :as events]
   [eca.shared :as shared :refer [assoc-some estimate-tokens]]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CHAT]")

(defn auto-compact-percentage
  "Threshold percentage at which the conversation is auto-compacted.
   Per-agent value wins over the global one; nil when disabled."
  [config agent-name]
  (or (get-in config [:agent agent-name :autoCompactPercentage])
      (get-in config [:autoCompactPercentage])))

(defn auto-compact-max-per-turn
  "How many times a single turn may auto-compact (`autoCompactMaxPerTurn`).
   Per-agent value wins over the global one; nil when unset (no limit)."
  [config agent-name]
  (or (get-in config [:agent agent-name :autoCompactMaxPerTurn])
      (get-in config [:autoCompactMaxPerTurn])))

(defn ^:private auto-compact-tokens-left
  "Remaining-tokens threshold (`autoCompactTokensLeft`) at which the
   conversation is auto-compacted. Per-agent value wins over the global one;
   nil when unset or not applicable to a model with `context-limit` tokens
   (it must be smaller than the limit, otherwise every turn would compact)."
  [config agent-name context-limit]
  (let [tokens-left (or (get-in config [:agent agent-name :autoCompactTokensLeft])
                        (get-in config [:autoCompactTokensLeft]))]
    (when (and (number? tokens-left)
               (pos? tokens-left)
               (number? context-limit)
               (< tokens-left context-limit))
      tokens-left)))

(defn compaction-module-active?
  "Whether `:eca/compact-as-summary`, which owns compaction, is active: without
   it ECA never compacts on its own."
  [config]
  (modules/active? config :eca/compact-as-summary))

(defn auto-compact-threshold
  "Effective auto-compaction threshold as a percentage of `context-limit`, as
   sent to clients: derived from `autoCompactTokensLeft` when applicable,
   otherwise `autoCompactPercentage`. nil when auto-compact is disabled."
  [config agent-name context-limit]
  (when (compaction-module-active? config)
    (if-let [tokens-left (auto-compact-tokens-left config agent-name context-limit)]
      (double (* 100 (/ (- context-limit tokens-left) context-limit)))
      (auto-compact-percentage config agent-name))))

(defn auto-compact? [chat-id agent-name full-model config db]
  (when (and (compaction-module-active? config)
             (not (get-in db [:chats chat-id :compacting?]))
             (not (get-in db [:chats chat-id :auto-compacting?]))
             (not (get-in db [:chats chat-id :continuing?])))
    (let [{:keys [session-tokens limit]} (shared/usage-sumary chat-id full-model db)
          context-limit (:context limit)]
      (when (and session-tokens
                 (number? context-limit)
                 (pos? context-limit))
        (if-let [tokens-left (auto-compact-tokens-left config agent-name context-limit)]
          (<= (- context-limit session-tokens) tokens-left)
          (when-let [compact-threshold (auto-compact-percentage config agent-name)]
            (>= (* (/ session-tokens context-limit) 100) compact-threshold)))))))

(defn ^:private post-compact-summary-index
  "Find the compact summary message inserted by compact-side-effect!.
   The current history layout is compact_marker followed by a user summary;
   return nil if that structure is not present so callers can safely fall back."
  [messages]
  (let [msgs (vec messages)
        n (count msgs)
        marker-idx (loop [i (dec n)]
                     (cond
                       (neg? i) nil
                       (= "compact_marker" (:role (msgs i))) i
                       :else (recur (dec i))))]
    (when marker-idx
      (some (fn [i]
              (when (= "user" (:role (msgs i))) i))
            (range (inc marker-idx) n)))))

(defn ^:private append-post-compact-contexts!
  "Append additionalContext entries to the compact summary message that follows
   the latest compact_marker, as `context-block`s. If no marker/summary is
   present, emit a warning and leave history untouched."
  [db* chat-id additional-contexts]
  (when (seq additional-contexts)
    (let [summary-idx (post-compact-summary-index (get-in @db* [:chats chat-id :messages]))]
      (if summary-idx
        (let [entries (mapv f.chat.lifecycle/context-block additional-contexts)]
          (swap! db* update-in [:chats chat-id :messages]
                 (fn [messages]
                   (let [messages (vec messages)]
                     (if (and (< summary-idx (count messages))
                              (= "user" (:role (messages summary-idx))))
                       (update-in messages [summary-idx :content]
                                  #(into (if (string? %)
                                           [{:type :text :text %}]
                                           (vec %))
                                         entries))
                       messages)))))
        (let [db @db*]
          (logger/with-chat-context chat-id (db/parent-chat-id db chat-id)
            (logger/warn logger-tag "Skipping postCompact additionalContext: compact summary message not found"
                         {:chat-id chat-id})))))))

(defn ^:private compact-event-trigger
  "The trigger compaction events report: `:manual`, `:auto`, or the chat
   ctx's `:compact-trigger` (`:overflow` when the request was too long for
   the model). Shell hooks keep \"manual\" and \"auto\"."
  [chat-ctx trigger]
  (if (= "manual" trigger) :manual (or (:compact-trigger chat-ctx) :auto)))

(defn run-pre-compact!
  "Runs the `:chat/before-compact` event, the preCompact shell hooks last
   (4.17). Returns `{:blocked? :stop-turn? :reason :ended-by :instructions}`:
   a `:block` skips the compaction, a `:stop` ends the turn too, `:ended-by`
   says who did, and `:instructions` are what the handlers left."
  [chat-ctx trigger instructions]
  (let [event (events/emit! chat-ctx :chat/before-compact
                            (f.chat.lifecycle/chat-event chat-ctx {:trigger (compact-event-trigger chat-ctx trigger)
                                                                   :instructions (shared/not-blank instructions)}))
        instructions (if (string? (:instructions event)) (:instructions event) instructions)]
    (cond
      (contains? event :stop)
      {:blocked? true :stop-turn? true :reason (shared/not-blank (:stop event)) :ended-by (f.chat.lifecycle/ended-by event)
       :instructions instructions}

      (:block event)
      {:blocked? true :stop-turn? false :reason (shared/not-blank (:block event)) :ended-by (f.chat.lifecycle/ended-by event)
       :instructions instructions}

      :else
      {:blocked? false :stop-turn? false :instructions instructions})))

(defn compaction-handled?
  "Whether something can compact the chat: `:eca/compact-as-summary` is
   active, or a module handles `:chat/compact`."
  [config]
  (or (compaction-module-active? config)
      (boolean (seq (events/handlers config :chat/compact)))))

(defn compact-with-module!
  "Emits `:chat/compact`, when a module handles it: the first module that
   answers does the compaction, instead of `:eca/compact-as-summary`. Returns
   its answer, `{:status :done or :failed, :resume? .., :message ..}`, plus
   `:checkpoint?`, whether it wrote a checkpoint; nil when none answered."
  [{:keys [db* chat-id config] :as chat-ctx} trigger instructions]
  (when (seq (events/handlers config :chat/compact))
    (let [before (count (get-in @db* [:chats chat-id :messages]))
          answer (events/emit! chat-ctx :chat/compact
                               (f.chat.lifecycle/chat-event chat-ctx {:trigger (compact-event-trigger chat-ctx trigger)
                                                                      :instructions (shared/not-blank instructions)}))]
      (when answer
        (let [answer (if (map? answer) answer {})]
          (assoc answer
                 :status (if (= :failed (:status answer)) :failed :done)
                 :checkpoint? (boolean (some #(= "compact_marker" (:role %))
                                             (drop before (get-in @db* [:chats chat-id :messages]))))))))))

(defn ^:private last-checkpoint
  "The chat's last checkpoint, `{:marker .. :messages [..]}`: its last
   compact_marker and the messages after it; nil when it has none."
  [messages]
  (let [messages (vec messages)]
    (when-let [idx (llm-util/find-last-msg-idx #(= "compact_marker" (:role %)) messages)]
      {:marker (messages idx) :messages (subvec messages (inc idx))})))

(defn run-after-compact!
  "After a compaction wrote its checkpoint: runs the `:chat/after-compact`
   event, with `compact-summary`, the postCompact shell hooks last (4.17).
   The handlers' contexts go on the summary, in their order; a `:stop` ends
   the turn. Returns `{:stop-turn? :stop-reason :ended-by}`."
  [{:keys [db* chat-id] :as chat-ctx} trigger compact-summary]
  (let [event (events/emit! chat-ctx :chat/after-compact
                            (f.chat.lifecycle/chat-event chat-ctx {:trigger (compact-event-trigger chat-ctx trigger)
                                                                   :checkpoint (last-checkpoint (get-in @db* [:chats chat-id :messages]))
                                                                   :summary compact-summary}))]
    (when-let [contexts (seq (::events/contexts event))]
      (append-post-compact-contexts! db* chat-id contexts))
    (if (contains? event :stop)
      {:stop-turn? true :stop-reason (shared/not-blank (:stop event)) :ended-by (f.chat.lifecycle/ended-by event)}
      {:stop-turn? false})))

(defn finish-module-compaction!
  "After a module answered `:chat/compact`: shows its `:message` and, when it
   wrote a checkpoint, runs `run-after-compact!` with the checkpoint's text
   as the summary hooks get. Returns `{:compacted? :stop-turn? :stop-reason
   :ended-by}`."
  [{:keys [db* chat-id] :as chat-ctx} trigger {:keys [status message checkpoint?]}]
  (when-let [message (or (shared/not-blank message)
                         (when (= :failed status) "Compaction failed. The conversation was not compacted."))]
    (f.chat.lifecycle/send-content! chat-ctx :system {:type :text :text message}))
  (if (and (= :done status) checkpoint?)
    (let [summary (->> (:messages (last-checkpoint (get-in @db* [:chats chat-id :messages])))
                       (mapcat (fn [{:keys [content]}] (if (string? content) [content] (keep :text content))))
                       (string/join "\n"))]
      (assoc (run-after-compact! chat-ctx trigger summary) :compacted? true))
    {:compacted? (= :done status)}))

(defn complete-compact!
  "Apply ECA's compact side effects, then `run-after-compact!` with the
   model's summary."
  [{:keys [db* chat-id] :as chat-ctx} trigger]
  (let [{:keys [compact-done? last-summary]} (get-in @db* [:chats chat-id])]
    (if (and compact-done? (string? last-summary))
      (do
        (swap! db* update-in [:chats chat-id] dissoc :compact-done?)
        (shared/compact-side-effect! chat-ctx (= "auto" trigger))
        (when-let [{:keys [marker messages]} (last-checkpoint (get-in @db* [:chats chat-id :messages]))]
          (f.chat.lifecycle/emit-messages-added! chat-ctx (cons marker messages)))
        (assoc (run-after-compact! chat-ctx trigger last-summary)
               :compacted? true))
      (do
        (swap! db* update-in [:chats chat-id]
               dissoc :auto-compacting? :compacting? :compact-done? :last-summary)
        (f.chat.lifecycle/send-content! chat-ctx :system
                                        {:type :text
                                         :text "Compaction failed: the model did not submit a summary with `compact_chat`. The conversation was not compacted."})
        {:compacted? false
         :stop-after-finish? true}))))

(defn ^:private checkpoint-message
  "A message given to `write-checkpoint!` as the history keeps it, or nil
   when it isn't one: a map with a `:role` and `:content`, a string content
   becoming a text content."
  [message now]
  (when (and (map? message) (string? (:role message)) (some? (:content message)))
    (cond-> (assoc message :created-at now)
      (string? (:content message)) (assoc :content [{:type :text :text (:content message)}]))))

(defn write-checkpoint!
  "Appends a checkpoint to the chat's history, as a compaction does: a
   compact_marker with `:module`, `:label` and `:data`, then `messages`. The
   model sees only what comes after it. The chat's usage resets, the
   contexts sent before it are sent again, and the client shows `:label`."
  [{:keys [db* chat-id messenger] :as chat-ctx} {:keys [module label data messages]}]
  (let [now (System/currentTimeMillis)
        label (shared/not-blank label)
        marker {:role "compact_marker"
                :content (assoc-some {} :module module :label label :data data)
                :created-at now}
        kept (into [] (keep #(checkpoint-message % now)) messages)
        appended (into [marker] kept)]
    (when (not= (count kept) (count messages))
      (logger/warn logger-tag "Ignoring checkpoint messages without a :role and :content" {:module module}))
    (swap! db* update-in [:chats chat-id]
           #(-> %
                (update :messages (fnil into []) appended)
                (dissoc :usage :validated-path-rules :last-editor-state :sent-attached-contexts)))
    (f.chat.lifecycle/emit-messages-added! chat-ctx appended)
    (messenger/chat-content-received messenger {:chat-id chat-id
                                                :role :system
                                                :content {:type :text :text (or label "Compacted chat")}})
    (when-let [usage (shared/usage-sumary chat-id (get-in @db* [:chats chat-id :model]) @db*)]
      (messenger/chat-content-received messenger {:chat-id chat-id
                                                  :role :system
                                                  :content (merge {:type :usage} usage)}))
    nil))

(defn ^:private tool-output-text [msg]
  (let [contents (get-in msg [:content :output :contents])]
    (reduce (fn [^String acc {:keys [text]}]
              (if text (str acc text) acc))
            ""
            contents)))

(defn ^:private server-tool-result-text [msg]
  (let [raw-content (get-in msg [:content :raw-content])]
    (reduce (fn [^String acc item]
              (if-let [text (:text item)]
                (str acc text)
                acc))
            ""
            raw-content)))

(def ^:private cleared-tool-output
  {:error false
   :contents [{:type :text :text "[content cleared to reduce context size]"}]})

(def ^:private cleared-raw-content
  [{:type "text" :text "[content cleared to reduce context size]"}])

(defn prune-tool-results!
  "Prunes old tool result content from chat history to reduce context size.
   Walks messages backwards, protecting the most recent tool outputs up to
   `protect-budget` estimated tokens. Clears older tool outputs with a placeholder.
   Returns the estimated number of tokens freed."
  [db* chat-id {:keys [protect-budget]
                :or {protect-budget 40000}}]
  (let [messages (get-in @db* [:chats chat-id :messages] [])
        n (count messages)
        {:keys [pruned-messages freed-tokens]}
        (loop [i (dec n)
               protected-tokens 0
               freed-tokens 0
               result messages]
          (if (neg? i)
            {:pruned-messages result
             :freed-tokens freed-tokens}
            (let [msg (nth messages i)
                  role (:role msg)]
              (cond
                (= "compact_marker" role)
                {:pruned-messages result
                 :freed-tokens freed-tokens}

                (= "tool_call_output" role)
                (let [text (tool-output-text msg)
                      tokens (estimate-tokens text)]
                  (if (< protected-tokens protect-budget)
                    (recur (dec i) (+ protected-tokens tokens) freed-tokens result)
                    (recur (dec i) protected-tokens (+ freed-tokens tokens)
                           (assoc result i (assoc-in msg [:content :output] cleared-tool-output)))))

                (= "server_tool_result" role)
                (let [text (server-tool-result-text msg)
                      tokens (estimate-tokens text)]
                  (if (< protected-tokens protect-budget)
                    (recur (dec i) (+ protected-tokens tokens) freed-tokens result)
                    (recur (dec i) protected-tokens (+ freed-tokens tokens)
                           (assoc result i (assoc-in msg [:content :raw-content] cleared-raw-content)))))

                :else
                (recur (dec i) protected-tokens freed-tokens result)))))]
    (when (pos? freed-tokens)
      (swap! db* assoc-in [:chats chat-id :messages] pruned-messages))
    freed-tokens))

(defn ^:private compact-finished-side-effect!
  "on-finished-side-effect for a mid-turn compaction: clear the auto-compacting
   flag, apply the compact side effects and run postCompact hooks for `trigger`
   (\"auto\" or \"manual\"). When a postCompact hook stops the turn
   (continue:false), surface the reason and finish here (postRequest hooks were
   already skipped while auto-compacting), returning {:stop-after-finish? true}
   so the resume continuation does not fire."
  [{:keys [db* chat-id] :as chat-ctx} trigger]
  (swap! db* update-in [:chats chat-id] dissoc :auto-compacting?)
  (let [{:keys [compacted? stop-turn? stop-reason ended-by]} (complete-compact! chat-ctx trigger)
        current-prompt-ctx (assoc chat-ctx :prompt-id (get-in @db* [:chats chat-id :prompt-id]))]
    (when stop-turn?
      (f.chat.lifecycle/send-ended! chat-ctx "Turn stopped" ended-by stop-reason))
    (when (or stop-turn? (not compacted?))
      (f.chat.lifecycle/finish-chat-prompt-stopped! :idle current-prompt-ctx))
    {:stop-after-finish? (or stop-turn? (not compacted?))}))

(def ^:private resume-after-compact-message
  {:role "user"
   :content [{:type :text
              :text "Continue with the task. The previous user request was:"}]})

(defn ^:private resume-after-compact!
  "on-after-finish! for a mid-turn compaction: resume the original user task.
   A turn can compact more than once, so a resume prefix from an earlier
   compaction is not repeated. The contexts modules added go: they were for
   the context window before the compaction, and modules add what the new
   one needs with `:chat/after-compact`. The resumed turn carries a fresh
   post-compact guard, see `auto-compact-suppressed?`."
  [chat-ctx user-messages]
  ((:continue-turn-fn chat-ctx)
   (cons resume-after-compact-message
         (->> user-messages
              (remove #(= resume-after-compact-message (select-keys % [:role :content])))
              (map message-sanitize/without-module-contexts)))
   :auto-compact
   (assoc chat-ctx :post-compact-guard* (atom true))))

(defn auto-compact-suppressed?
  "Whether auto-compaction must not run (again) in this turn: a preCompact hook
   blocked it (`:auto-compacted?`, kept for the rest of the turn), the turn
   reached `autoCompactMaxPerTurn`, or the chat was just compacted and no
   response showed the compacted context under the threshold yet
   (`:post-compact-guard*`). Compacting again before that could loop, as the
   compacted context alone may still be too big. #391"
  [{:keys [config agent auto-compactions post-compact-guard*] :as chat-ctx}]
  (let [max-per-turn (auto-compact-max-per-turn config agent)]
    (boolean (or (:auto-compacted? chat-ctx)
                 (and max-per-turn (>= (or auto-compactions 0) max-per-turn))
                 (some-> post-compact-guard* deref)))))

(defn release-post-compact-guard!
  "Called on each usage update: once the compacted context is under the
   auto-compact threshold, compaction may run again later in the same turn."
  [{:keys [db* chat-id agent full-model config post-compact-guard*]}]
  (when (and post-compact-guard*
             @post-compact-guard*
             (not (auto-compact? chat-id agent full-model config @db*)))
    (reset! post-compact-guard* false)))

(defn retry-without-compaction
  "An `on-blocked` for `trigger-compact!`: sends `user-messages` again, in a
   request that doesn't auto-compact."
  [chat-ctx user-messages]
  (fn []
    ((:continue-turn-fn chat-ctx) user-messages :auto-compact-blocked (assoc chat-ctx :auto-compacted? true))
    nil))

(defn trigger-compact!
  "Compact the chat in the middle of a running turn, then resume the original
   task (`user-messages`). `trigger` is \"auto\" (context threshold reached) or
   \"manual\" (steered `/compact`, with `custom-instructions`).

   Runs the `:chat/before-compact` event and the preCompact hooks first:
   continue:false (or a module's :stop) stops the turn (reason from
   stopReason, hook-name or module for provenance); exit 2 (or a module's
   :block) only blocks compaction, in which case `on-blocked` decides how the
   turn goes on and its result is returned. A module answering `:chat/compact`
   compacts instead of the compact prompt; the turn then resumes, unless it
   says `:resume? false`. Otherwise the compact prompt is sent under :auto-compacting?,
   so finishing it does not end the turn: the finish side-effect applies the
   compaction and the after-finish continuation resumes the task.
   `event-trigger` is the trigger compaction events report, e.g. :overflow.
   Returns nil when it took over the turn."
  [{:keys [db* config chat-id agent] :as chat-ctx}
   all-tools
   user-messages
   {:keys [trigger custom-instructions on-blocked event-trigger]}]
  (let [compact-ctx (cond-> chat-ctx event-trigger (assoc :compact-trigger event-trigger))
        {:keys [blocked? reason stop-turn? ended-by instructions]}
        (run-pre-compact! compact-ctx trigger custom-instructions)]
    (if blocked?
      (do
        (logger/info logger-tag "Compaction blocked" {:chat-id chat-id
                                                      :trigger trigger
                                                      :stop-turn? stop-turn?
                                                      :module (:module ended-by)})
        (if stop-turn?
          (do (f.chat.lifecycle/send-ended! chat-ctx "Turn stopped" ended-by reason)
              (f.chat.lifecycle/finish-chat-prompt-stopped! :idle chat-ctx)
              nil)
          (do (f.chat.lifecycle/send-ended! chat-ctx "Compaction blocked" ended-by reason)
              (on-blocked))))
      (if-let [answer (compact-with-module! compact-ctx trigger instructions)]
        (let [{:keys [compacted? stop-turn? stop-reason ended-by]}
              (finish-module-compaction! compact-ctx trigger answer)]
          (logger/info logger-tag "Chat compacted by a module" {:chat-id chat-id :trigger trigger
                                                                :status (:status answer)})
          (cond
            stop-turn?
            (do (f.chat.lifecycle/send-ended! chat-ctx "Turn stopped" ended-by stop-reason)
                (f.chat.lifecycle/finish-chat-prompt-stopped! :idle chat-ctx))

            (not compacted?)
            (f.chat.lifecycle/finish-chat-prompt-stopped! :idle chat-ctx)

            ;; as after the built-in compaction, the interrupted task goes on
            ;; unless the module says not to
            (get answer :resume? true)
            (resume-after-compact! chat-ctx user-messages)

            :else
            (f.chat.lifecycle/finish-chat-prompt! :idle (assoc chat-ctx :skip-post-request-hooks? true)))
          nil)
        (if-not (compaction-module-active? config)
          ;; only a module could have compacted, and none did
          (do (f.chat.lifecycle/send-content! chat-ctx :system {:type :text :text "No compaction module handled this chat."})
              (on-blocked))
          (let [db             @db*
                compact-prompt (f.prompt/compact-prompt (shared/not-blank instructions) all-tools agent config db)]
            (logger/info logger-tag "Compacting chat" {:chat-id chat-id :trigger trigger})
            (swap! db* update-in [:chats chat-id]
                   #(-> %
                        (dissoc :last-summary :compact-done?)
                        (assoc :auto-compacting? true)))
            ((:continue-turn-fn chat-ctx)
             [{:role "user" :content "Compact the chat following the template:"}
              {:role "user" :content compact-prompt}]
             :auto-compact
             (assoc chat-ctx
                    :on-finished-side-effect #(compact-finished-side-effect! compact-ctx trigger)
                    :on-after-finish! #(resume-after-compact! chat-ctx user-messages)))
            nil))))))

(defn trigger-auto-compact!
  "Trigger auto-compact: send compact prompt, then resume the original task.
   When a hook blocks compaction without stopping the turn, the original task is
   (re)sent as a new prompt, marked so auto-compaction is not retried.
   Counts the auto-compactions of the turn, see `autoCompactMaxPerTurn`.
   A `:compact-trigger` in `chat-ctx`, e.g. :overflow, is the trigger
   compaction events report; it's only for this compaction."
  [chat-ctx all-tools user-messages]
  (let [event-trigger (:compact-trigger chat-ctx)
        {:keys [chat-id config agent auto-compactions] :as chat-ctx} (-> chat-ctx
                                                                         (dissoc :compact-trigger)
                                                                         (update :auto-compactions (fnil inc 0)))]
    (when (= (auto-compact-max-per-turn config agent) auto-compactions)
      (logger/warn logger-tag "Last auto-compaction allowed in this turn" {:chat-id chat-id
                                                                           :auto-compactions auto-compactions}))
    (trigger-compact! chat-ctx all-tools user-messages
                      {:trigger "auto"
                       :event-trigger event-trigger
                       :on-blocked (retry-without-compaction chat-ctx user-messages)})))

(def ^:private max-requested-compactions-per-turn
  "Compactions modules may ask for in a turn when `autoCompactMaxPerTurn`
   isn't set: a backstop against a module that keeps asking."
  3)

(defn ^:private take-compaction-request!
  "Removes the compaction a module asked for (eca.api/request-compaction!)
   from the chat and returns it, `{:module :trigger :instructions}`, when
   the chat may compact now: not while it compacts, nor when this turn can't
   auto-compact again (see `auto-compact-suppressed?`), reached its cap, or
   had a requested compaction blocked."
  [{:keys [db* chat-id prompt-id config agent auto-compactions] :as chat-ctx}]
  (let [[old] (swap-vals! db* (fn [db]
                                (cond-> db
                                  (get-in db [:chats chat-id :compaction-request])
                                  (update-in [:chats chat-id] dissoc :compaction-request))))
        {:keys [compaction-request compacting? auto-compacting? continuing? compaction-blocked-prompt-id]}
        (get-in old [:chats chat-id])]
    (when (and compaction-request
               (not compacting?)
               (not auto-compacting?)
               (not continuing?)
               (not (auto-compact-suppressed? chat-ctx))
               (< (or auto-compactions 0)
                  (or (auto-compact-max-per-turn config agent) max-requested-compactions-per-turn))
               (not (and prompt-id (= prompt-id compaction-blocked-prompt-id))))
      compaction-request)))

(defn trigger-requested-compact!
  "Runs the compaction `request` asks for, a module's (see
   `take-compaction-request!`) or a `{:compact ..}` answer to
   `:llm/request-failed`, as an auto-compaction: it counts in the turn's,
   then the task resumes. When it's blocked, no request runs again in this
   prompt and `on-blocked` decides how the turn goes on."
  [{:keys [db* chat-id prompt-id] :as chat-ctx} all-tools user-messages {:keys [module trigger instructions]} on-blocked]
  (logger/info logger-tag "Compacting chat, as a module asked" {:chat-id chat-id :module module :trigger trigger})
  (trigger-compact! (update chat-ctx :auto-compactions (fnil inc 0))
                    all-tools
                    user-messages
                    {:trigger (if (= :manual trigger) "manual" "auto")
                     :event-trigger (or trigger :auto)
                     :custom-instructions instructions
                     :on-blocked (fn []
                                   (swap! db* assoc-in [:chats chat-id :compaction-blocked-prompt-id] prompt-id)
                                   (on-blocked))}))

(defn run-requested-compaction!
  "Runs the compaction a module asked for, if any may run now (see
   `take-compaction-request!`). Returns `{:result ..}`, what
   `trigger-compact!` returned, when it ran one; nil otherwise."
  [chat-ctx all-tools user-messages on-blocked]
  (when-let [request (take-compaction-request! chat-ctx)]
    {:result (trigger-requested-compact! chat-ctx all-tools user-messages request on-blocked)}))
