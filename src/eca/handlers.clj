(ns eca.handlers
  (:require
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.chat :as f.chat]
   [eca.features.login :as f.login]
   [eca.features.tools :as f.tools]
   [eca.features.tools.mcp :as f.mcp]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.models :as models]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn initialize [{:keys [db*]} params]
  (logger/logging-task
   :eca/initialize
   (reset! config/initialization-config* (shared/map->camel-cased-map (:initialization-options params)))
   (let [config (config/all @db*)]
     (logger/debug "Considered config: " config)
     (swap! db* assoc
            :client-info (:client-info params)
            :workspace-folders (:workspace-folders params)
            :client-capabilities (:capabilities params))
     (when-not (:pureConfig config)
       (db/load-db-from-cache! db* config))

     ;; Deprecated
     ;; For backward compatibility,
     ;; we now return chat config via `config/updated` notification.
     (models/sync-models! db* config (fn [_]))
     (let [db @db*]
       {:models (sort (keys (:models db)))
        :chat-behaviors (distinct (keys (:behavior config)))
        :chat-default-model (f.chat/default-model db config)
        :chat-default-behavior (config/validate-behavior-name
                                (or (:defaultBehavior (:chat config)) ;;legacy
                                    (:defaultBehavior config))
                                config)
        :chat-welcome-message (or (:welcomeMessage (:chat config)) ;;legacy
                                  (:welcomeMessage config))}))))

(defn initialized [{:keys [db* messenger config]}]
  (let [sync-models-and-notify! (fn [config]
                                  (let [new-providers-hash (hash (:providers config))]
                                    (when (not= (:providers-config-hash @db*) new-providers-hash)
                                      (swap! db* assoc :providers-config-hash new-providers-hash)
                                      (models/sync-models! db* config (fn [models]
                                                                        (let [db @db*]
                                                                          (config/notify-fields-changed-only!
                                                                           {:chat
                                                                            {:models (sort (keys models))
                                                                             :behaviors (distinct (keys (:behavior config)))
                                                                             :select-model (f.chat/default-model db config)
                                                                             :select-behavior (config/validate-behavior-name
                                                                                               (or (:defaultBehavior (:chat config)) ;;legacy
                                                                                                   (:defaultBehavior config))
                                                                                               config)
                                                                             :welcome-message (or (:welcomeMessage (:chat config)) ;;legacy
                                                                                                  (:welcomeMessage config))
                                                                             ;; Deprecated, remove after changing emacs, vscode and intellij.
                                                                             :default-model (f.chat/default-model db config)
                                                                             :default-behavior (config/validate-behavior-name
                                                                                                (or (:defaultBehavior (:chat config)) ;;legacy
                                                                                                    (:defaultBehavior config))
                                                                                                config)}}
                                                                           messenger
                                                                           db*)))))))]
    (swap! db* assoc-in [:config-updated-fns :sync-models] #(sync-models-and-notify! %))
    (sync-models-and-notify! config))
  (future
    (Thread/sleep 1000) ;; wait chat window is open in some editors.
    (when-let [error (config/validation-error)]
      (messenger/chat-content-received
       messenger
       {:role "system"
        :content {:type :text
                  :text (format "\nFailed to parse '%s' config, check stderr logs, double check your config and restart\n"
                                error)}}))
    (config/listen-for-changes! db*))
  (future
    (f.tools/init-servers! db* messenger config)))

(defn shutdown [{:keys [db*]}]
  (logger/logging-task
   :eca/shutdown
   (f.mcp/shutdown! db*)
   (swap! db* assoc :stopping true)
   nil))

(defn chat-prompt [{:keys [messenger db* config]} params]
  (logger/logging-task
   :eca/chat-prompt
   (case (get-in @db* [:chats (:chat-id params) :status])
     :login (f.login/handle-step params db* messenger config)
     (f.chat/prompt params db* messenger config))))

(defn chat-query-context [{:keys [db* config]} params]
  (logger/logging-task
   :eca/chat-query-context
   (f.chat/query-context params db* config)))

(defn chat-query-commands [{:keys [db* config]} params]
  (logger/logging-task
   :eca/chat-query-commands
   (f.chat/query-commands params db* config)))

(defn chat-tool-call-approve [{:keys [messenger db*]} params]
  (logger/logging-task
   :eca/chat-tool-call-approve
   (f.chat/tool-call-approve params db* messenger)))

(defn chat-tool-call-reject [{:keys [messenger db*]} params]
  (logger/logging-task
   :eca/chat-tool-call-reject
   (f.chat/tool-call-reject params db* messenger)))

(defn chat-prompt-stop [{:keys [db* messenger]} params]
  (logger/logging-task
   :eca/chat-prompt-stop
   (f.chat/prompt-stop params db* messenger)))

(defn chat-delete [{:keys [db*]} params]
  (logger/logging-task
   :eca/chat-delete
   (f.chat/delete-chat params db*)
   {}))

(defn mcp-stop-server [{:keys [db* messenger config]} params]
  (logger/logging-task
   :eca/mcp-stop-server
   (f.tools/stop-server! (:name params) db* messenger config)))

(defn mcp-start-server [{:keys [db* messenger config]} params]
  (logger/logging-task
   :eca/mcp-start-server
   (f.tools/start-server! (:name params) db* messenger config)))

(defn ^:private update-behavior-model!
  "Updates the selected model based on behavior configuration."
  [behavior-config config messenger db*]
  (when-let [model (or (:defaultModel behavior-config)
                       (:defaultModel config))]
    (config/notify-fields-changed-only!
     {:chat {:select-model model}}
     messenger
     db*)))

(defn chat-selected-behavior-changed
  "Switches model to the one defined in custom-behavior or to the default-one
   and updates tool status for the new behavior"
  [{:keys [db* messenger config]} {:keys [behavior]}]
  (let [validated-behavior (config/validate-behavior-name behavior config)
        behavior-config (get-in config [:behavior validated-behavior])
        tool-status-fn (f.tools/make-tool-status-fn config validated-behavior)]
    (update-behavior-model! behavior-config config messenger db*)
    (f.tools/refresh-tool-servers! tool-status-fn db* messenger config)))

(defn completion-inline [{:keys []} {:keys [uri doc-version position]}]
  (let [{:keys [line character]} position]
    (Thread/sleep 300)
    {:items [{:id "123"
              :text "foo"
              :doc-version doc-version
              :range {:start {:line line :character character}
                      :end {:line line :character character}}}
             {:id "234"
              :text "foobar"
              :doc-version doc-version
              :range {:start {:line line :character character}
                      :end {:line line :character character}}}]}))
