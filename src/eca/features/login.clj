(ns eca.features.login
  (:require
   [clojure.string :as string]
   [eca.config :as config]
   [eca.db :as db]
   [eca.messenger :as messenger]
   [eca.models :as models]))

(defmulti login-step (fn [ctx] [(:provider ctx) (:step ctx)]))

(defmethod login-step :default [{:keys [send-msg!]}]
  (send-msg! "Error: Unknown login step"))

;; No provider selected
(defmethod login-step [nil :login/start] [{:keys [db* chat-id input send-msg!] :as ctx}]
  (let [provider (string/trim input)
        providers (->> @db* :auth keys sort)]
    (if (get-in @db* [:auth provider])
      (do (swap! db* assoc-in [:chats chat-id :login-provider] provider)
          (swap! db* assoc-in [:auth provider] {:step :login/start})
          (login-step (assoc ctx :provider provider)))
      (send-msg! (reduce
                  (fn [s provider]
                    (str s "- " provider "\n"))
                  "Choose a provider:\n"
                  providers)))))

(defn handle-step [{:keys [message chat-id]} db* messenger config metrics]
  (let [provider (get-in @db* [:chats chat-id :login-provider])
        step (get-in @db* [:auth provider :step] :login/start)
        input (string/trim message)
        ctx {:chat-id chat-id
             :step step
             :input input
             :db* db*
             :config config
             :messenger messenger
             :metrics metrics
             :provider provider
             :send-msg! (fn [msg]
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
                                      :state :finished}}))}]
    (messenger/chat-content-received
     messenger
     {:chat-id chat-id
      :role "user"
      :content {:type :text
                :text (str input "\n")}})
    (login-step ctx)
    {:chat-id chat-id
     :status :login}))

(defn renew-auth!
  [provider
   {:keys [db* messenger config metrics]}
   {:keys [on-error]}]
  (try
    (login-step
     {:provider provider
      :messenger messenger
      :config config
      :step :login/renew-token
      :db* db*})
    (db/update-global-cache! @db* metrics)
    (catch Exception e
      (on-error (.getMessage e)))))

(defn login-done! [{:keys [chat-id db* messenger metrics provider send-msg!]}
                   & {:keys [silent?]
                      :or {silent? false}}]
  (when (get-in @db* [:auth provider])
    (db/update-global-cache! @db* metrics))
  (models/sync-models! db*
                       (config/all @db*) ;; force get updated config
                       (fn [new-models]
                         (messenger/config-updated
                          messenger
                          {:chat
                           {:models (sort (keys new-models))}})))
  (swap! db* assoc-in [:chats chat-id :login-provider] nil)
  (swap! db* assoc-in [:chats chat-id :status] :idle)
  (when-not silent?
    (send-msg! (format "\nLogin successful! You can now use the '%s' models." provider))))
