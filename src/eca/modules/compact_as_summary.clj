(ns eca.modules.compact-as-summary
  "The `:eca/compact-as-summary` module: when the context fills up, the chat
   model summarizes the chat. It asks for a compaction past its threshold,
   and when a request was too long for the model. It does the compactions
   it's asked for, `/compact`'s too, as a request of the chat where the model
   writes the summary with `compact_chat`; the summary becomes the chat's
   checkpoint."
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [eca.api :as eca]
   [eca.features.chat.lifecycle :as f.chat.lifecycle]
   [eca.features.prompt :as f.prompt]
   [eca.features.tools.util :as tools.util]
   [eca.messenger :as messenger]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn ^:private compact-chat [arguments {:keys [db* chat-id]}]
  (let [chat (get-in @db* [:chats chat-id])]
    (if (or (:compacting? chat) (:auto-compacting? chat))
      (do
        (swap! db* update-in [:chats chat-id]
               assoc
               :compacting? false
               :last-summary (get arguments "summary")
               :compact-done? true)
        (tools.util/single-text-content "Compacted successfully!"))
      (tools.util/single-text-content
       "Chat compaction is not active for this request. This tool is available only while chat compaction is in progress. To compact manually, the user must use the `/compact` command; compaction may also start automatically when context usage reaches the configured threshold."
       :error))))

(def definitions
  {"compact_chat"
   {:description "During chat compaction, submit a summary that will become the active conversation context"
    :parameters {:type "object"
                 :properties {"summary" {:type "string"
                                         :description "The summary/compacted text"}}
                 :required ["summary"]}
    :handler #'compact-chat
    :summary-fn (constantly "Compacting...")}})

;; When

(defn ^:private auto-compact-percentage
  "Threshold percentage at which the conversation is auto-compacted.
   Per-agent value wins over the global one; nil when disabled."
  [config agent-name]
  (or (get-in config [:agent agent-name :autoCompactPercentage])
      (get-in config [:autoCompactPercentage])))

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

(defn ^:private auto-compact-threshold
  "Effective auto-compaction threshold as a percentage of `context-limit`, as
   sent to clients: derived from `autoCompactTokensLeft` when applicable,
   otherwise `autoCompactPercentage`. nil when auto-compact is disabled."
  [config agent-name context-limit]
  (if-let [tokens-left (auto-compact-tokens-left config agent-name context-limit)]
    (double (* 100 (/ (- context-limit tokens-left) context-limit)))
    (auto-compact-percentage config agent-name)))

(defn ^:private past-threshold?
  "Whether a context holding `session-tokens` of `context-limit` is past the
   auto-compaction threshold."
  [config agent-name session-tokens context-limit]
  (when (and session-tokens
             (number? context-limit)
             (pos? context-limit))
    (if-let [tokens-left (auto-compact-tokens-left config agent-name context-limit)]
      (<= (- context-limit session-tokens) tokens-left)
      (when-let [compact-threshold (auto-compact-percentage config agent-name)]
        (>= (* (/ session-tokens context-limit) 100) compact-threshold)))))

(defn ^:private chat-past-threshold?
  "Whether the chat's context, as its last response reported it, is past the
   auto-compaction threshold."
  [chat-id agent-name full-model config db]
  (let [{:keys [session-tokens limit]} (shared/usage-sumary chat-id full-model db)]
    (past-threshold? config agent-name session-tokens (:context limit))))

(defn ^:private compact-past-threshold
  "`:chat/usage-updated`: past the threshold, asks for a compaction, which
   runs before the turn's next request; gives clients the threshold."
  [{:keys [config] :as ctx} {:keys [agent session-tokens context-limit]}]
  (when (past-threshold? config agent session-tokens context-limit)
    (eca/request-compaction! ctx {:trigger :auto}))
  (when-let [percentage (auto-compact-threshold config agent context-limit)]
    {:auto-compact-percentage percentage}))

(defn ^:private compact-before-request
  "`:chat/before-request`: a chat already past the threshold at the user's
   request (resumed after a restart, a lowered threshold) compacts first;
   one no longer past it drops the compaction asked for before. A summary
   left from a compaction stopped before it was written goes."
  [{:keys [db* config chat-id agent full-model] :as ctx} _event]
  (when (get-in @db* [:chats chat-id :compact-done?])
    (swap! db* update-in [:chats chat-id] dissoc :compact-done?))
  (if (chat-past-threshold? chat-id agent full-model config @db*)
    (eca/request-compaction! ctx {:trigger :auto})
    (eca/cancel-compaction! ctx))
  nil)

(defn ^:private compact-on-overflow
  "`:llm/request-failed`: a request too long for the model compacts the
   chat, then the turn goes on."
  [_ctx {:keys [error]}]
  (when (= :context-overflow (:type error))
    {:compact {:trigger :overflow}}))

;; How

(defn ^:private compact-prompt-template* [file-path]
  (if (fs/relative? file-path)
    (slurp (io/resource file-path))
    (slurp (io/file file-path))))

(def ^:private compact-prompt-template (memoize compact-prompt-template*))

(defn ^:private compact-prompt [additional-input all-tools agent-name config db]
  (shared/safe-selmer-render
   (or (:compactPrompt config) ;; legacy
       (f.prompt/get-config-prompt :compact agent-name config)
       (compact-prompt-template (:compactPromptFile config)) ;; legacy
       )
   (merge
    (f.prompt/->base-selmer-ctx all-tools db)
    {:additionalUserInput (if additional-input
                            (format "You MUST respect this user input in the summarization: %s." additional-input)
                            "")})
   "compact-prompt"))

(defn ^:private write-summary!
  "Appends a compact_marker tombstone and the summary to the history, zeros
   the chat's token usage, and tells the user."
  [{:keys [chat-id full-model db* messenger] :as chat-ctx} auto-compact?]
  ;; Append compact marker tombstone + summary to preserve full history
  (swap! db* (fn [db]
               (let [summary (get-in db [:chats chat-id :last-summary])
                     messages (get-in db [:chats chat-id :messages] [])
                     now (System/currentTimeMillis)]
                 (assoc-in db [:chats chat-id :messages]
                           (conj messages
                                 {:role "compact_marker"
                                  :content {:auto? (boolean auto-compact?)}
                                  :created-at now}
                                 {:role "user"
                                  :content [{:type :text
                                             :text (str "The conversation was compacted/summarized, consider this summary:\n"
                                                        summary)}]
                                  :created-at now})))))

  ;; Zero chat usage and clear transient per-chat rule validations.
  ;; Drop :last-editor-state and :sent-attached-contexts so the cursor and the
  ;; attached contexts are re-sent next turn, since the pre-marker history
  ;; holding them is no longer in context. #683
  (swap! db* update-in [:chats chat-id] dissoc :usage :validated-path-rules :last-editor-state :sent-attached-contexts)
  (messenger/chat-content-received
   messenger
   {:chat-id chat-id
    :role :system
    :content {:type :text
              :text (if auto-compact?
                      "Auto-compacted chat"
                      "Compacted chat")}})
  (when-let [usage (shared/usage-sumary chat-id full-model @db* (select-keys chat-ctx [:subscription?]))]
    (messenger/chat-content-received
     messenger
     {:chat-id chat-id
      :role :system
      :content (merge {:type :usage}
                      usage)})))

(defn ^:private summary-written?
  "Whether the model wrote the summary with `compact_chat`."
  [{:keys [db* chat-id]}]
  (get-in @db* [:chats chat-id :compact-done?]))

(defn ^:private apply-summary!
  "Once the summary request finished: writes the summary as the chat's
   checkpoint and returns it, or tells the user the model didn't write one
   and returns nil."
  [{:keys [db* chat-id] :as chat-ctx} trigger]
  (let [{:keys [compact-done? last-summary]} (get-in @db* [:chats chat-id])]
    (if (and compact-done? (string? last-summary))
      (do
        (swap! db* update-in [:chats chat-id] dissoc :compact-done?)
        (write-summary! chat-ctx (= "auto" trigger))
        last-summary)
      (do
        (swap! db* update-in [:chats chat-id] dissoc :compact-done? :last-summary)
        (f.chat.lifecycle/send-content! chat-ctx :system
                                        {:type :text
                                         :text "Compaction failed: the model did not submit a summary with `compact_chat`. The conversation was not compacted."})
        nil))))

(defn ^:private summarize
  "`:chat/compact`: the summary is a request of the chat, where the chat
   model writes it with `compact_chat`."
  [{:keys [db* chat-id config agent]} _event]
  (swap! db* update-in [:chats chat-id] dissoc :last-summary :compact-done?)
  {:request {:prompt (fn [instructions all-tools]
                       (compact-prompt instructions all-tools agent config @db*))
             :done? summary-written?
             :apply! apply-summary!}})

(def module
  {:id :eca/compact-as-summary
   :description "When the context fills up, the chat model summarizes the chat."
   :config-spec {:autoCompactPercentage {:type :number :default 75}
                 :autoCompactTokensLeft {:type :number}
                 :prompts {:compact {:type :string :default "${classpath:prompts/compact.md}"}}}
   :config-defaults {:toolCall {:approval {:allow {"eca__compact_chat" {}}}}}
   :tools (fn [_ctx] (tools.util/->module-tools definitions))
   :event-handlers {:chat/usage-updated {:handler #'compact-past-threshold}
                    :chat/before-request {:handler #'compact-before-request}
                    :llm/request-failed {:handler #'compact-on-overflow}
                    :chat/compact {:handler #'summarize}}})
