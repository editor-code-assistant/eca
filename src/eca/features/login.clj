(ns eca.features.login
  (:require
   [clojure.string :as string]
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.providers :as f.providers]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.models :as models]
   [eca.shared :as shared :refer [multi-str]]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[LOGIN]")

(defmulti login-step (fn [ctx] [(:provider ctx) (:step ctx)]))

(defmethod login-step :default [{:keys [send-msg!]}]
  (send-msg! "Error: Unknown login step"))

(def ^:private ask-timeout-ms (* 5 60 1000))

(def ^:private secret-input-steps
  "Steps whose chat input is a secret (OAuth code / API key) and therefore
   must not be echoed back to the chat."
  #{:login/waiting-provider-code :login/waiting-api-key})

(defn ^:private ask-question-supported? [db]
  (boolean (get-in db [:client-capabilities :code-assistant :chat-capabilities :ask-question])))

(defn ^:private cancel-login! [{:keys [db* chat-id messenger send-msg!]}]
  (let [provider (get-in @db* [:chats chat-id :login-provider])]
    (when provider
      (swap! db* assoc-in [:auth provider] {}))
    (swap! db* assoc-in [:chats chat-id :login-provider] nil)
    (swap! db* assoc-in [:chats chat-id :status] :idle)
    (send-msg! "Login cancelled")
    (messenger/chat-status-changed messenger {:chat-id chat-id :status :idle})))

(declare ^:private with-ask-support)

(defn ^:private ask-and-continue!
  "Asks a question via the client's `chat/askQuestion` UI in background and
   feeds the answer back into the login state machine. A cancelled or timed
   out question cancels the login."
  [{:keys [db* chat-id messenger config] :as ctx} {:keys [question options]}]
  (shared/future* config
    (try
      (let [response (deref (messenger/ask-question messenger
                                                    {:chatId chat-id
                                                     :question question
                                                     :allowFreeform false
                                                     :request-id (str (random-uuid))
                                                     :options options})
                            ask-timeout-ms
                            {:cancelled true})
            answer (some-> (:answer response) string/trim)]
        ;; Ignore stale answers when login is no longer active for this chat.
        (when (= :login (get-in @db* [:chats chat-id :status]))
          (if (or (:cancelled response) (string/blank? answer))
            (cancel-login! ctx)
            (let [provider (get-in @db* [:chats chat-id :login-provider])
                  step (get-in @db* [:auth provider :step] :login/start)]
              (login-step (with-ask-support (assoc ctx
                                                   :provider provider
                                                   :step step
                                                   :input answer)))))))
      (catch Exception e
        (logger/error logger-tag "Error handling login answer:" (ex-message e))
        ((:send-msg! ctx) (str "Login error: " (ex-message e)))))))

(defn ^:private with-ask-support
  "Adds an `:ask!` fn to the login ctx when the client supports the
   `chat/askQuestion` request. Login steps use it to ask for choices
   interactively, falling back to the text-based flow when absent."
  [{:keys [db*] :as ctx}]
  (if (ask-question-supported? @db*)
    (assoc ctx :ask! (fn [question-data] (ask-and-continue! ctx question-data)))
    ctx))

;; No provider selected
(defmethod login-step [nil :login/start] [{:keys [db* chat-id input config send-msg! ask!] :as ctx}]
  (let [provider (string/trim input)
        providers (->> @db* :auth keys sort)]
    (if (get-in @db* [:auth provider])
      (do (swap! db* assoc-in [:chats chat-id :login-provider] provider)
          (swap! db* assoc-in [:auth provider] {:step :login/start})
          (when-let [warning (f.providers/key-auth-override-warning provider config)]
            (send-msg! warning))
          (login-step (assoc ctx :provider provider)))
      (if ask!
        (ask! {:question "Select the provider to login (other providers can be configured manually in your ECA config):"
               :options (mapv (fn [p] {:label p}) providers)})
        (send-msg! (multi-str
                     (reduce
                       (fn [s provider]
                         (str s "- " provider "\n"))
                       "Inform the provider:\n\n"
                       providers)
                     ""
                     "For other providers, configure manually in your ECA config."))))))

(defn handle-step [{:keys [message chat-id]} db* messenger config metrics]
  (let [provider (get-in @db* [:chats chat-id :login-provider])
        step (get-in @db* [:auth provider :step] :login/start)
        input (string/trim message)
        send-msg! (fn [msg]
                      (messenger/chat-content-received
                       messenger
                       {:chat-id chat-id
                        :role "system"
                        :content {:type :text
                                  :text msg}})
                      (messenger/chat-content-received
                       messenger
                       {:chat-id chat-id
                        :role "system"
                        :content {:type :progress
                                  :state :finished}}))
        ctx (with-ask-support
              {:chat-id chat-id
               :step step
               :input input
               :db* db*
               :config config
               :messenger messenger
               :metrics metrics
               :provider provider
               :send-msg! send-msg!})
        secret-input? (and (contains? secret-input-steps step)
                           (not= "cancel" input))]
    (messenger/chat-content-received
     messenger
     {:chat-id chat-id
      :role "user"
      :content {:type :text
                :text (str (if secret-input? "*****" input) "\n")}})
    (if (= "cancel" input)
      (do
        (cancel-login! ctx)
        {:chat-id chat-id
         :status :idle})
      (do
        (login-step ctx)
        {:chat-id chat-id
         :status :login}))))

(defn ^:private renew-auth!
  [provider
   {:keys [db* messenger config metrics]}
   {:keys [on-error]}]
  (try
    ;; Serialize across ECA processes that share `~/.cache/eca/db.transit.json`.
    ;; OAuth refresh tokens are single-use, so two concurrent processes
    ;; both POSTing with the same token would have one win and the other
    ;; receive `invalid_grant`. Holding the cache lock + re-reading disk
    ;; lets the loser adopt the winner's rotated tokens instead. #462
    (db/with-global-cache-lock
      (db/sync-auth-from-cache! db* provider metrics)
      (let [expires-at (get-in @db* [:auth provider :expires-at])
            now-plus-60 (+ 60 (quot (System/currentTimeMillis) 1000))]
        (if (and expires-at (> (long expires-at) now-plus-60))
          (logger/info logger-tag
                       (format "Skipping %s renew; peer process already refreshed." provider))
          (do
            (login-step
             {:provider provider
              :metrics metrics
              :messenger messenger
              :config config
              :step :login/renew-token
              :db* db*})
            (db/update-global-cache! @db* metrics)))))
    (catch Exception e
      (on-error (.getMessage e)))))

(defn maybe-renew-auth-token! [{:keys [provider on-renewing on-error]} ctx]
  (when-not (f.providers/key-auth-override-warning provider (:config ctx))
    (when-let [expires-at (get-in @(:db* ctx) [:auth provider :expires-at])]
      ;; Renew 60s before expiration to avoid race between check and request
      (when (<= (long expires-at) (+ 60 (quot (System/currentTimeMillis) 1000)))
        (when on-renewing
          (on-renewing))
        (renew-auth! provider ctx
                     {:on-error on-error})))))

(defn renew-expiring-auth-tokens!
  "Best-effort proactive renewal of any provider auth tokens that are at/near
   expiry. Used by non-interactive paths like model-catalog sync, which would
   otherwise call provider endpoints with a stale short-lived token (e.g.
   Copilot's session token) and get a 401. `ctx` needs {:db* :messenger :config
   :metrics}."
  [{:keys [db*] :as ctx}]
  (doseq [provider (keys (:auth @db*))]
    (maybe-renew-auth-token!
     {:provider provider
      :on-error (fn [msg]
                  (logger/warn logger-tag
                               (format "Could not renew '%s' auth token before sync: %s" provider msg)))}
     ctx)))

(defn login-done! [{:keys [chat-id db* messenger metrics provider send-msg!]}
                   & {:keys [silent? skip-models-sync?]
                      :or {silent? false
                           skip-models-sync? false}}]
  (when (get-in @db* [:auth provider])
    (db/update-global-cache! @db* metrics))
  (when-not skip-models-sync?
    (models/sync-models! db*
                         (config/all @db*) ;; force get updated config
                         (fn [new-models]
                           (messenger/config-updated
                            messenger
                            {:chat
                             {:models (sort (keys new-models))}}))))
  (swap! db* assoc-in [:chats chat-id :login-provider] nil)
  (swap! db* assoc-in [:chats chat-id :status] :idle)
  (when-not silent?
    (send-msg! (format "\nLogin successful! You can now use the '%s' models." provider))
    ;; Login may complete asynchronously (OAuth callback, device-flow polling,
    ;; askQuestion answer), where there is no chat/prompt response to carry the
    ;; new status, so notify the client explicitly.
    (messenger/chat-status-changed messenger {:chat-id chat-id :status :idle})))
