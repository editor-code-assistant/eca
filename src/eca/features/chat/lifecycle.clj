(ns eca.features.chat.lifecycle
  (:require
   [eca.db :as db]
   [eca.features.hooks :as f.hooks]
   [eca.features.login :as f.login]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.shared :as shared :refer [assoc-some]]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CHAT]")

(defn new-content-id []
  (str (random-uuid)))

(defn auto-compact? [chat-id agent-name full-model config db]
  (when (and (not (get-in db [:chats chat-id :compacting?]))
             (not (get-in db [:chats chat-id :auto-compacting?])))
    (let [compact-threshold (or (get-in config [:agent agent-name :autoCompactPercentage])
                                (get-in config [:autoCompactPercentage]))
          {:keys [session-tokens limit]} (shared/usage-sumary chat-id full-model db)]
      (when (and compact-threshold session-tokens (:context limit))
        (let [current-percentage (* (/ session-tokens (:context limit)) 100)]
          (>= current-percentage compact-threshold))))))

(defn send-content! [{:keys [messenger chat-id parent-chat-id]} role content]
  (messenger/chat-content-received
   messenger
   (assoc-some {:chat-id chat-id
                :role role
                :content content}
               :parent-chat-id parent-chat-id)))

(defn- format-hook-output
  "Format hook output for display, showing parsed JSON fields or raw output."
  [{:keys [systemMessage replacedPrompt additionalContext] :as parsed} raw-output]
  (if parsed
    (cond-> (or systemMessage "Hook executed")
      replacedPrompt  (str "\nReplacedPrompt: " (pr-str replacedPrompt))
      additionalContext (str "\nAdditionalContext: " additionalContext))
    raw-output))

(defn notify-before-hook-action! [chat-ctx {:keys [id name type visible?]}]
  (when visible?
    (send-content! chat-ctx :system
                   {:type :hookActionStarted
                    :action-type type
                    :name name
                    :id id})))

(defn notify-after-hook-action! [chat-ctx {:keys [id name parsed raw-output raw-error exit type visible?]}]
  (when (and visible? (not (:suppressOutput parsed)))
    (send-content! chat-ctx :system
                   {:type :hookActionFinished
                    :action-type type
                    :id id
                    :name name
                    :status exit
                    :output (format-hook-output parsed raw-output)
                    :error raw-error})))

(defn wrap-additional-context
  "Return XML-wrapped additional context attributed to `from`."
  [from content]
  (format "<additionalContext from=\"%s\">\n%s\n</additionalContext>"
          (name from)
          content))

(defn finish-chat-prompt! [status {:keys [message chat-id db* metrics config on-finished-side-effect prompt-id] :as chat-ctx}]
  (when-not (and prompt-id (not= prompt-id (get-in @db* [:chats chat-id :prompt-id])))
    (when-not (get-in @db* [:chats chat-id :auto-compacting?])
      (swap! db* assoc-in [:chats chat-id :status] status)
      (let [db @db*
            subagent? (some? (get-in db [:chats chat-id :subagent]))
            hook-type (if subagent? :subagentPostRequest :postRequest)
            hook-data (cond-> (merge (f.hooks/chat-hook-data db chat-id (:agent chat-ctx))
                                     {:prompt message})
                        subagent? (assoc :parent-chat-id (get-in db [:chats chat-id :parent-chat-id])))]
        (f.hooks/trigger-if-matches! hook-type
                                     hook-data
                                     {:on-before-action (partial notify-before-hook-action! chat-ctx)
                                      :on-after-action (partial notify-after-hook-action! chat-ctx)}
                                     db
                                     config))
      (send-content! chat-ctx :system
                     {:type :progress
                      :state :finished})
      (when-not (get-in @db* [:chats chat-id :created-at])
        (swap! db* assoc-in [:chats chat-id :created-at] (System/currentTimeMillis))))
    (when on-finished-side-effect
      (on-finished-side-effect))
    (db/update-workspaces-cache! @db* metrics)))

(defn maybe-renew-auth-token [chat-ctx]
  (f.login/maybe-renew-auth-token!
   {:provider (:provider chat-ctx)
    :on-renewing (fn []
                   (send-content! chat-ctx :system {:type  :progress
                                                    :state :running
                                                    :text  "Renewing auth token"}))
    :on-error (fn [error-msg]
                (send-content! chat-ctx :system {:type :text :text error-msg})
                (finish-chat-prompt! :idle (dissoc chat-ctx :on-finished-side-effect))
                (throw (ex-info "Auth token renew failed" {})))}
   chat-ctx))

(defn assert-chat-not-stopped! [{:keys [chat-id db* prompt-id] :as chat-ctx}]
  (let [chat (get-in @db* [:chats chat-id])
        superseded? (and prompt-id (not= prompt-id (:prompt-id chat)))
        stopped? (or (identical? :stopping (:status chat)) superseded?)]
    (when stopped?
      (finish-chat-prompt! :idle (dissoc chat-ctx :on-finished-side-effect))
      (logger/info logger-tag "Chat prompt stopped:" chat-id (when superseded? "(superseded)"))
      (throw (ex-info "Chat prompt stopped" {:silent? true
                                             :chat-id chat-id})))))
