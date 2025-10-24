(ns eca.handlers
  (:require
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.chat :as f.chat]
   [eca.features.completion :as f.completion]
   [eca.features.login :as f.login]
   [eca.features.tools :as f.tools]
   [eca.features.tools.mcp :as f.mcp]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.metrics :as metrics]
   [eca.models :as models]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn initialize [{:keys [db* metrics]} params]
  (metrics/task metrics :eca/initialize
    (reset! config/initialization-config* (shared/map->camel-cased-map (:initialization-options params)))
    (let [config (config/all @db*)]
      (logger/debug "Considered config: " config)
      (swap! db* assoc
             :client-info (:client-info params)
             :workspace-folders (:workspace-folders params)
             :client-capabilities (:capabilities params))
      (metrics/set-extra-metrics! db*)
      (when-not (:pureConfig config)
        (db/load-db-from-cache! db* config metrics))

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

(defn initialized [{:keys [db* messenger config metrics]}]
  (metrics/task metrics :eca/initialized
    (let [sync-models-and-notify!
          (fn [config]
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
      (sync-models-and-notify! config)))
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
    (f.tools/init-servers! db* messenger config metrics)))

(defn shutdown [{:keys [db* metrics]}]
  (metrics/task metrics :eca/shutdown
    (f.mcp/shutdown! db*)
    (swap! db* assoc :stopping true)
    nil))

(defn chat-prompt [{:keys [messenger db* config metrics]} params]
  (metrics/task metrics :eca/chat-prompt
    (case (get-in @db* [:chats (:chat-id params) :status])
      :login (f.login/handle-step params db* messenger config metrics)
      (f.chat/prompt params db* messenger config metrics))))

(defn chat-query-context [{:keys [db* config metrics]} params]
  (metrics/task metrics :eca/chat-query-context
    (f.chat/query-context params db* config)))

(defn chat-query-files [{:keys [db* config metrics]} params]
  (metrics/task metrics :eca/chat-query-files
    (f.chat/query-files params db* config)))

(defn chat-query-commands [{:keys [db* config metrics]} params]
  (metrics/task metrics :eca/chat-query-commands
                (f.chat/query-commands params db* config)))

(defn chat-tool-call-approve [{:keys [messenger db* metrics]} params]
  (metrics/task metrics :eca/chat-tool-call-approve
    (f.chat/tool-call-approve params db* messenger metrics)))

(defn chat-tool-call-reject [{:keys [messenger db* metrics]} params]
  (metrics/task metrics :eca/chat-tool-call-reject
    (f.chat/tool-call-reject params db* messenger metrics)))

(defn chat-prompt-stop [{:keys [db* messenger metrics]} params]
  (metrics/task metrics :eca/chat-prompt-stop
    (f.chat/prompt-stop params db* messenger metrics)))

(defn chat-delete [{:keys [db* metrics]} params]
  (metrics/task metrics :eca/chat-delete
    (f.chat/delete-chat params db* metrics)
    {}))

(defn mcp-stop-server [{:keys [db* messenger metrics config]} params]
  (metrics/task metrics :eca/mcp-stop-server
    (f.tools/stop-server! (:name params) db* messenger config metrics)))

(defn mcp-start-server [{:keys [db* messenger metrics config]} params]
  (metrics/task metrics :eca/mcp-start-server
    (f.tools/start-server! (:name params) db* messenger config metrics)))

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
  [{:keys [db* messenger config metrics]} {:keys [behavior]}]
  (metrics/task metrics :eca/chat-selected-behavior-changed
    (let [validated-behavior (config/validate-behavior-name behavior config)
          behavior-config (get-in config [:behavior validated-behavior])
          tool-status-fn (f.tools/make-tool-status-fn config validated-behavior)]
      (update-behavior-model! behavior-config config messenger db*)
      (f.tools/refresh-tool-servers! tool-status-fn db* messenger config))))

(defn completion-inline
  [{:keys [db* config metrics messenger]} params]
  (metrics/task metrics :eca/completion-inline
    (f.completion/complete params db* config messenger)))
