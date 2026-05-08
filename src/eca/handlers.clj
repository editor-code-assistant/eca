(ns eca.handlers
  (:require
   [eca.cache :as cache]
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.background-tasks :as f.background-tasks]
   [eca.features.chat :as f.chat]
   [eca.features.completion :as f.completion]
   [eca.features.hooks :as f.hooks]
   [eca.features.login :as f.login]
   [eca.features.plugins :as f.plugins]
   [eca.features.providers :as f.providers]
   [eca.features.rewrite :as f.rewrite]
   [eca.features.tools :as f.tools]
   [eca.features.tools.mcp :as f.mcp]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.metrics :as metrics]
   [eca.models :as models]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn ^:private model-variants
  "Returns sorted variant names for a full model (e.g. \"anthropic/claude-sonnet-4-5\")
   by resolving effective variants: built-in (from :variantsByModel regex match)
   merged with user-defined [:providers provider :models model :variants]."
  ^clojure.lang.IPersistentVector [config ^String full-model]
  (when full-model
    (let [idx (.indexOf full-model "/")]
      (when (pos? idx)
        (let [provider (subs full-model 0 idx)
              model (subs full-model (inc idx))
              user-variants (get-in config [:providers provider :models model :variants])
              variants (config/effective-model-variants config provider model user-variants)]
          (config/selectable-variant-names variants))))))

(defn ^:private select-variant
  "Returns the variant to select: the agent's configured variant if it exists
   in the available variants, otherwise nil."
  [agent-config variants]
  (let [agent-variant (:variant agent-config)]
    (when (and agent-variant variants (some #{agent-variant} variants))
      agent-variant)))

(defn welcome-message
  "Builds the welcome message from config, appending a remote hint when available."
  [config]
  (or (:welcomeMessage (:chat config)) ;;legacy
      (:welcomeMessage config)))

(defn initialize [{:keys [db* metrics]} params]
  (metrics/task metrics :eca/initialize
    (reset! config/initialization-config* (shared/map->camel-cased-map (:initialization-options params)))
    (let [config (config/all @db*)]
      (swap! db* assoc
             :client-info (:client-info params)
             :workspace-folders (:workspace-folders params)
             :initial-workspace-folders (:workspace-folders params)
             :client-capabilities (:capabilities params))
      (metrics/set-extra-metrics! db*)
      (when-not (:pureConfig config)
        (db/load-db-from-cache! db* config metrics))

      {:chat-welcome-message (welcome-message config)})))

(defn ^:private send-progress! [db* messenger params]
  (when-not (:stopping @db*)
    (try
      (messenger/progress messenger params)
      (catch Exception _))))

(defn initialized [{:keys [db* messenger config metrics start-remote-server!] :as components}]
  (metrics/task metrics :eca/initialized
    (let [sync-models-and-notify!
          (fn [config]
            (let [new-providers-hash (hash (:providers config))]
              (when (not= (:providers-config-hash @db*) new-providers-hash)
                (swap! db* assoc :providers-config-hash new-providers-hash)
                (models/sync-models! db* config (fn [models]
                                                  (let [db @db*
                                                        default-model (f.chat/default-model db config)
                                                        default-agent-name (config/validate-agent-name
                                                                            (or (:defaultAgent (:chat config))
                                                                                (:defaultAgent config))
                                                                            config)
                                                        default-agent-config (get-in config [:agent default-agent-name])
                                                        variants (model-variants config default-model)]
                                                    (config/notify-fields-changed-only!
                                                     {:chat
                                                      {:models (sort (keys models))
                                                       :agents (config/primary-agent-names config)
                                                       :select-model default-model
                                                       :select-agent default-agent-name
                                                       :variants (or variants [])
                                                       :select-variant (select-variant default-agent-config variants)
                                                       :welcome-message (welcome-message config)
                                                          ;; Deprecated, remove after changing emacs, vscode and intellij.
                                                       :default-model default-model
                                                       :default-agent default-agent-name
                                                       ;; Legacy: backward compat for clients using old key names
                                                       :behaviors (distinct (keys (:agent config)))
                                                       :select-behavior default-agent-name
                                                       :default-behavior default-agent-name}}
                                                     messenger
                                                     db*)))))))]
      (swap! db* assoc-in [:config-updated-fns :sync-models] #(sync-models-and-notify! %))
      (shared/future* config
        (do (send-progress! db* messenger {:type "start" :taskId "models" :title "Syncing models"})
            (sync-models-and-notify! config)
            (send-progress! db* messenger {:type "finish" :taskId "models" :title "Syncing models"}))))
    (when (get-in config [:remote :enabled])
      (send-progress! db* messenger {:type "start" :taskId "remote-server" :title "Starting remote server"})
      (start-remote-server! components)
      (send-progress! db* messenger {:type "finish" :taskId "remote-server" :title "Starting remote server"}))
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
      (send-progress! db* messenger {:type "start" :taskId "plugins" :title "Resolving plugins"})
      (try
        (let [plugins-config (:plugins config)]
          (when (seq plugins-config)
            (reset! config/plugin-components* (f.plugins/resolve-all! plugins-config))))
        (catch Exception e
          (logger/warn "[PLUGINS]" "Plugin resolution failed:" (.getMessage e))))
      (send-progress! db* messenger {:type "finish" :taskId "plugins" :title "Resolving plugins"})
      (let [config (config/all @db*)]
        ;; Trigger sessionStart before delivering plugins-resolved so it
        ;; completes before any chatStart hook can fire (chatStart waits
        ;; on await-plugins-resolved!).
        (f.hooks/trigger-if-matches! :sessionStart
                                     (f.hooks/base-hook-data @db*)
                                     {}
                                     @db*
                                     config)
        (config/deliver-plugins-resolved!)
        (send-progress! db* messenger {:type "start" :taskId "mcp-servers" :title "Initializing MCP servers"})
        (f.tools/init-servers! db* messenger config metrics)
        (send-progress! db* messenger {:type "finish" :taskId "mcp-servers" :title "Initializing MCP servers"})))
    (future
      (send-progress! db* messenger {:type "start" :taskId "cleanup" :title "Cleaning up"})
      (let [retention-days (get config :chatRetentionDays 14)]
        (cache/cleanup-tool-call-outputs! retention-days)
        (db/cleanup-old-chats! db* metrics retention-days))
      (send-progress! db* messenger {:type "finish" :taskId "cleanup" :title "Cleaning up"}))))

(defn workspace-did-change-folders [{:keys [db*]} params]
  (let [{:keys [added removed]} (:event params)
        removed-uris (into #{} (map :uri) removed)]
    (swap! db* update :workspace-folders
           (fn [folders]
             (let [filtered (vec (remove #(contains? removed-uris (:uri %)) folders))]
               (into filtered added))))
    (logger/info "[handlers]" "Workspace folders updated:"
                 (shared/workspaces-as-str @db*))))

(defn shutdown [{:keys [db* config metrics]}]
  (metrics/task metrics :eca/shutdown
    ;; 1. Save cache BEFORE hook so db-cache-path contains current state
    (db/update-workspaces-cache! @db* metrics)

    ;; 2. Trigger sessionEnd hook
    (f.hooks/trigger-if-matches! :sessionEnd
                                 (f.hooks/base-hook-data @db*)
                                 {}
                                 @db*
                                 config)

    ;; 3. Then shutdown
    (f.background-tasks/cleanup-all!)
    (swap! db* assoc :stopping true)
    (f.mcp/shutdown! db*)
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

(defn chat-prompt-steer [{:keys [db* metrics]} params]
  (metrics/task metrics :eca/chat-prompt-steer
    (f.chat/prompt-steer params db*)))

(defn chat-prompt-steer-remove [{:keys [db* metrics]} params]
  (metrics/task metrics :eca/chat-prompt-steer-remove
    (f.chat/prompt-steer-remove params db*)))

(defn chat-delete [{:keys [db* messenger config metrics]} params]
  (metrics/task metrics :eca/chat-delete
    (f.chat/delete-chat params db* messenger config metrics)
    {}))

(defn chat-clear [{:keys [db* messenger metrics]} params]
  (metrics/task metrics :eca/chat-clear
    (f.chat/clear-chat params db* messenger metrics)
    {}))

(defn chat-rollback [{:keys [db* metrics messenger]} params]
  (metrics/task metrics :eca/chat-rollback
    (f.chat/rollback-chat params db* messenger)))

(defn chat-add-flag [{:keys [db* metrics messenger]} params]
  (metrics/task metrics :eca/chat-add-flag
    (f.chat/add-flag params db* messenger metrics)))

(defn chat-remove-flag [{:keys [db* metrics]} params]
  (metrics/task metrics :eca/chat-remove-flag
    (f.chat/remove-flag params db* metrics)))

(defn chat-fork [{:keys [db* metrics messenger]} params]
  (metrics/task metrics :eca/chat-fork
    (f.chat/fork-chat params db* messenger metrics)))

(defn chat-update [{:keys [db* messenger metrics]} params]
  (metrics/task metrics :eca/chat-update
    (f.chat/update-chat params db* messenger metrics)))

(defn chat-list
  "Return a summary list of persisted chats for the current DB.
   Supports optional :limit and :sort-by params (see `f.chat/list-chats`)."
  [{:keys [db* metrics]} params]
  (metrics/task metrics :eca/chat-list
    (f.chat/list-chats @db* params)))

(defn chat-open
  "Replay a persisted chat over the wire for the client to render.
   Emits chat/cleared, chat/opened and per-message chat/contentReceived
   notifications for the target chat, plus a config/updated notification
   to restore the chat's stored model selection. Returns `{:found? bool ...}`."
  [{:keys [db* messenger config metrics]} params]
  (metrics/task metrics :eca/chat-open
    (f.chat/open-chat! params db* messenger config)))

(defn mcp-stop-server [{:keys [db* messenger metrics config]} params]
  (metrics/task metrics :eca/mcp-stop-server
    (f.tools/stop-server! (:name params) db* messenger config metrics)))

(defn mcp-start-server [{:keys [db* messenger metrics config]} params]
  (metrics/task metrics :eca/mcp-start-server
    (f.tools/start-server! (:name params) db* messenger config metrics)))

(defn mcp-connect-server [{:keys [db* messenger metrics config]} params]
  (metrics/task metrics :eca/mcp-connect-server
    (f.tools/connect-server! (:name params) db* messenger config metrics)))

(defn mcp-logout-server [{:keys [db* messenger metrics config]} params]
  (metrics/task metrics :eca/mcp-logout-server
    (f.tools/logout-server! (:name params) db* messenger config metrics)))

(defn mcp-update-server [{:keys [db* messenger metrics config]} params]
  (metrics/task metrics :eca/mcp-update-server
    (let [server-name (:name params)
          server-fields (cond-> {}
                          (:command params) (assoc :command (:command params))
                          (:args params) (assoc :args (:args params))
                          (:url params) (assoc :url (:url params))
                          (:env params) (assoc :env (:env params))
                          (:headers params) (assoc :headers (:headers params)))]
      (f.tools/update-server! server-name server-fields db* messenger config metrics)
      {})))

(defn mcp-disable-server [{:keys [db* messenger metrics config]} params]
  (metrics/task metrics :eca/mcp-disable-server
    (f.tools/disable-server! (:name params) db* messenger config metrics)))

(defn mcp-enable-server [{:keys [db* messenger metrics config]} params]
  (metrics/task metrics :eca/mcp-enable-server
    (f.tools/enable-server! (:name params) db* messenger config metrics)))

(defn ^:private ->scope-keyword [scope]
  (cond
    (nil? scope) :global
    (keyword? scope) scope
    (string? scope) (keyword scope)
    :else :global))

(defn mcp-add-server
  "Add a new MCP server definition at runtime.

   Params:
     :name           (required) server identifier.
     :command :args :env           for stdio servers.
     :url :headers :clientId :clientSecret :oauthPort  for HTTP servers.
     :disabled       optional boolean.
     :scope          \"global\" (default) or \"workspace\".
     :workspaceUri   required when :scope = \"workspace\".

   Returns the canonical server map (as sent over tool/serverUpdated) with
   status :starting, :disabled, or :failed."
  [{:keys [db* messenger metrics config]} params]
  (metrics/task metrics :eca/mcp-add-server
    (let [server-name (:name params)
          server-config (cond-> {}
                          (:command params)       (assoc :command (:command params))
                          (:args params)          (assoc :args (:args params))
                          (:env params)           (assoc :env (:env params))
                          (:url params)           (assoc :url (:url params))
                          (:headers params)       (assoc :headers (:headers params))
                          (:clientId params)      (assoc :clientId (:clientId params))
                          (:clientSecret params)  (assoc :clientSecret (:clientSecret params))
                          (:oauthPort params)     (assoc :oauthPort (:oauthPort params))
                          (contains? params :disabled) (assoc :disabled (boolean (:disabled params))))
          opts {:scope (->scope-keyword (or (:scope params) (:workspaceScope params)))
                :workspace-uri (or (:workspaceUri params) (:workspace-uri params))}]
      (try
        (let [server (f.tools/add-server! server-name server-config opts db* messenger config metrics)]
          {:server server})
        (catch clojure.lang.ExceptionInfo e
          {:error {:code "invalid_request"
                   :message (.getMessage e)
                   :data (ex-data e)}})))))

(defn mcp-remove-server
  "Remove an MCP server definition at runtime. Stops the server if running,
   removes the entry from the owning config file, and emits tool/serverRemoved.

   Params:
     :name  (required)"
  [{:keys [db* messenger metrics config]} params]
  (metrics/task metrics :eca/mcp-remove-server
    (try
      (f.tools/remove-server! (:name params) db* messenger config metrics)
      (catch clojure.lang.ExceptionInfo e
        {:error {:code "invalid_request"
                 :message (.getMessage e)
                 :data (ex-data e)}}))))

(defn ^:private sanitize-client-chat-id
  "Returns CHAT-ID when the client-supplied id is valid, otherwise nil.
   On rejection, logs a warning so the selection still proceeds via the
   legacy session-wide path instead of silently no-oping."
  [chat-id method]
  (when chat-id
    (if-let [reason (f.chat/validate-client-chat-id chat-id)]
      (do (logger/warn (str "[" method "]") "Ignoring invalid chat-id"
                       {:chat-id chat-id :reason reason})
          nil)
      chat-id)))

(defn ^:private update-agent-model-and-variants!
  "Updates the selected model and variants based on agent configuration.
   When `chat-id` is provided AND that chat exists in the db, the change
   is persisted on that chat record and the broadcast is scoped via
   `:chat-id`. When `chat-id` is unknown or absent, falls back to the
   session-wide path so we never broadcast per-chat state for a chat the
   server does not recognize."
  ([agent-config config messenger db*]
   (update-agent-model-and-variants! agent-config config messenger db* nil))
  ([agent-config config messenger db* chat-id]
   (when-let [model (or (:defaultModel agent-config)
                        (:defaultModel config))]
     (let [variants (model-variants config model)
           selected-variant (select-variant agent-config variants)
           payload {:chat {:select-model model
                           :variants (or variants [])
                           :select-variant selected-variant}}
           ;; CAS: only mutate the chat record if it still exists at swap
           ;; time, avoiding TOCTOU resurrection when chat/delete races us.
           [old-db _new-db] (when chat-id
                              (swap-vals! db* update-in [:chats chat-id]
                                          (fn [c]
                                            (when c
                                              (cond-> (assoc c :model model)
                                                selected-variant (assoc :variant selected-variant))))))
           chat-existed? (and chat-id (some? (get-in old-db [:chats chat-id])))]
       (if chat-existed?
         (config/notify-fields-changed-only! payload messenger db* chat-id)
         (config/notify-fields-changed-only! payload messenger db*))))))

(defn chat-selected-agent-changed
  "Switches model to the one defined in custom agent or to the default-one
   and updates tool status for the new agent.
   When `chatId` is provided AND valid, persists the agent on that chat
   record and scopes the broadcast so other chats are not affected
   client-side. Invalid ids fall through to the session-wide path with a
   warn log."
  [{:keys [db* messenger config metrics]} {:keys [chat-id agent behavior]}]
  (metrics/task metrics :eca/chat-selected-agent-changed
    (let [chat-id (sanitize-client-chat-id chat-id "chat/selectedAgentChanged")
          agent-name (or agent behavior) ;; backward compat: accept old 'behavior' param
          validated-agent (config/validate-agent-name agent-name config)
          agent-config (get-in config [:agent validated-agent])
          tool-status-fn (f.tools/make-tool-status-fn config validated-agent)]
      (when chat-id
        (swap! db* update-in [:chats chat-id]
               (fn [c] (when c (assoc c :agent validated-agent)))))
      (update-agent-model-and-variants! agent-config config messenger db* chat-id)
      (f.tools/refresh-tool-servers! tool-status-fn db* messenger config))))

(defn chat-selected-model-changed
  "When `chatId` is provided AND valid AND the chat exists, persists model
   + variant on that chat record and scopes the broadcast (`:chat-id` is
   included in the `config/updated` payload). Invalid or unknown ids fall
   through to the legacy session-wide path with a warn log so we never
   announce per-chat state for a chat the server doesn't recognize."
  [{:keys [db* messenger config metrics]} {:keys [chat-id model variant]}]
  (metrics/task metrics :eca/chat-selected-model-changed
    (let [chat-id (sanitize-client-chat-id chat-id "chat/selectedModelChanged")
          default-agent-name (config/validate-agent-name
                              (or (:defaultAgent (:chat config))
                                  (:defaultAgent config))
                              config)
          agent-config (get-in config [:agent default-agent-name])
          variants (model-variants config model)
          payload {:chat {:variants (or variants [])
                          :select-variant (select-variant agent-config variants)}}
          ;; CAS: only mutate the chat record if it still exists at swap
          ;; time, avoiding TOCTOU resurrection when chat/delete races us.
          [old-db _new-db] (when chat-id
                             (swap-vals! db* update-in [:chats chat-id]
                                         (fn [c]
                                           (when c
                                             (cond-> (assoc c :model model)
                                               variant (assoc :variant variant))))))
          chat-existed? (and chat-id (some? (get-in old-db [:chats chat-id])))]
      (if chat-existed?
        (config/notify-fields-changed-only! payload messenger db* chat-id)
        (do
          ;; Legacy session-wide path: keep the historical hack that forces
          ;; the next diff to emit when the requested variant is not in the
          ;; new model's variant set.
          (when (and variant (not (some #{variant} variants)))
            (swap! db* assoc-in [:last-config-notified :chat :select-variant] variant))
          (config/notify-fields-changed-only! payload messenger db*))))))

(defn completion-inline
  [{:keys [db* config metrics messenger]} params]
  (metrics/task metrics :eca/completion-inline
    (f.completion/complete params db* config messenger metrics)))

(defmacro handle-expected-errors
  "Executes body, catching any ExceptionInfo with :error-response.
  If caught, return {:error error-response}"
  [& body]
  `(try
     ~@body
     (catch clojure.lang.ExceptionInfo e#
       (if-let [error-response# (:error-response (ex-data e#))]
         {:error error-response#}
         (throw e#)))))

(defn rewrite-prompt
  [{:keys [db* config metrics messenger]} params]
  (metrics/task metrics :eca/rewrite-prompt
    (handle-expected-errors
     (f.rewrite/prompt params db* config messenger metrics))))

(defn jobs-list [{:keys [db* metrics]} _params]
  (metrics/task metrics :eca/jobs-list
    {:jobs (f.background-tasks/jobs-summary db*)}))

(defn jobs-kill [{:keys [db* messenger metrics]} params]
  (metrics/task metrics :eca/jobs-kill
    (let [job-id (:job-id params)
          killed? (f.background-tasks/kill-job! job-id)]
      (when killed?
        (messenger/jobs-updated messenger {:jobs (f.background-tasks/jobs-summary db*)}))
      {:killed killed?})))

(defn jobs-read-output [{:keys [metrics]} params]
  (metrics/task metrics :eca/jobs-read-output
    (if-let [result (f.background-tasks/peek-output (:job-id params))]
      {:lines (mapv (fn [{:keys [text stream]}]
                      {:text text :stream (name stream)})
                    (:lines result))
       :status (:status result)
       :exit-code (:exit-code result)}
      {:lines []
       :status nil
       :exit-code nil})))

(defn providers-list [{:keys [db* config metrics]} _params]
  (metrics/task metrics :eca/providers-list
    (f.providers/providers-list db* config)))

(defn providers-login [{:keys [db* config messenger metrics]} params]
  (metrics/task metrics :eca/providers-login
    (handle-expected-errors
     (f.providers/provider-login (:provider params) (:method params) db* config messenger metrics))))

(defn providers-login-input [{:keys [db* config messenger metrics]} params]
  (metrics/task metrics :eca/providers-login-input
    (handle-expected-errors
     (f.providers/provider-login-input (:provider params) (:data params) db* config messenger metrics))))

(defn providers-logout [{:keys [db* config messenger metrics]} params]
  (metrics/task metrics :eca/providers-logout
    (f.providers/provider-logout (:provider params) db* config messenger metrics)))