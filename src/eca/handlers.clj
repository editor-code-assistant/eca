(ns eca.handlers
  (:require
   [eca.cache :as cache]
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.chat :as f.chat]
   [eca.features.completion :as f.completion]
   [eca.features.hooks :as f.hooks]
   [eca.features.login :as f.login]
   [eca.features.rewrite :as f.rewrite]
   [eca.features.tools :as f.tools]
   [eca.features.tools.mcp :as f.mcp]
   [eca.messenger :as messenger]
   [eca.metrics :as metrics]
   [eca.models :as models]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn ^:private model-variants
  "Returns sorted variant names for a full model (e.g. \"anthropic/claude-sonnet-4-5\")
   by looking up [:providers provider :models model :variants] in config."
  ^clojure.lang.IPersistentVector [config ^String full-model]
  (when full-model
    (let [idx (.indexOf full-model "/")]
      (when (pos? idx)
        (let [provider (subs full-model 0 idx)
              model (subs full-model (inc idx))
              variants (get-in config [:providers provider :models model :variants])]
          (when (seq variants)
            (vec (sort (keys variants)))))))))

(defn ^:private select-variant
  "Returns the variant to select: the agent's configured variant if it exists
   in the available variants, otherwise nil."
  [agent-config variants]
  (let [agent-variant (:variant agent-config)]
    (when (and agent-variant variants (some #{agent-variant} variants))
      agent-variant)))

(defn initialize [{:keys [db* metrics]} params]
  (metrics/task metrics :eca/initialize
    (reset! config/initialization-config* (shared/map->camel-cased-map (:initialization-options params)))
    (let [config (config/all @db*)]
      (swap! db* assoc
             :client-info (:client-info params)
             :workspace-folders (:workspace-folders params)
             :client-capabilities (:capabilities params))
      (metrics/set-extra-metrics! db*)
      (when-not (:pureConfig config)
        (db/load-db-from-cache! db* config metrics))

      {:chat-welcome-message (or (:welcomeMessage (:chat config)) ;;legacy
                                 (:welcomeMessage config))})))

(defn initialized [{:keys [db* messenger config metrics]}]
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
                                                       :welcome-message (or (:welcomeMessage (:chat config)) ;;legacy
                                                                            (:welcomeMessage config))
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
    (f.tools/init-servers! db* messenger config metrics))
  (future
    (cache/cleanup-tool-call-outputs!))
  ;; Trigger sessionStart hook after initialization
  (f.hooks/trigger-if-matches! :sessionStart
                               (f.hooks/base-hook-data @db*)
                               {}
                               @db*
                               config))

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

(defn chat-delete [{:keys [db* config metrics]} params]
  (metrics/task metrics :eca/chat-delete
    (f.chat/delete-chat params db* config metrics)
    {}))

(defn chat-rollback [{:keys [db* metrics messenger]} params]
  (metrics/task metrics :eca/chat-rollback
    (f.chat/rollback-chat params db* messenger)))

(defn mcp-stop-server [{:keys [db* messenger metrics config]} params]
  (metrics/task metrics :eca/mcp-stop-server
    (f.tools/stop-server! (:name params) db* messenger config metrics)))

(defn mcp-start-server [{:keys [db* messenger metrics config]} params]
  (metrics/task metrics :eca/mcp-start-server
    (f.tools/start-server! (:name params) db* messenger config metrics)))

(defn ^:private update-agent-model-and-variants!
  "Updates the selected model and variants based on agent configuration."
  [agent-config config messenger db*]
  (when-let [model (or (:defaultModel agent-config)
                       (:defaultModel config))]
    (let [variants (model-variants config model)]
      (config/notify-fields-changed-only!
       {:chat {:select-model model
               :variants (or variants [])
               :select-variant (select-variant agent-config variants)}}
       messenger
       db*))))

(defn chat-selected-agent-changed
  "Switches model to the one defined in custom agent or to the default-one
   and updates tool status for the new agent"
  [{:keys [db* messenger config metrics]} {:keys [agent behavior]}]
  (metrics/task metrics :eca/chat-selected-agent-changed
    (let [agent-name (or agent behavior) ;; backward compat: accept old 'behavior' param
          validated-agent (config/validate-agent-name agent-name config)
          agent-config (get-in config [:agent validated-agent])
          tool-status-fn (f.tools/make-tool-status-fn config validated-agent)]
      (update-agent-model-and-variants! agent-config config messenger db*)
      (f.tools/refresh-tool-servers! tool-status-fn db* messenger config))))

(defn chat-selected-model-changed
  [{:keys [db* messenger config metrics]} {:keys [model]}]
  (metrics/task metrics :eca/chat-selected-model-changed
    (let [default-agent-name (config/validate-agent-name
                              (or (:defaultAgent (:chat config))
                                  (:defaultAgent config))
                              config)
          agent-config (get-in config [:agent default-agent-name])
          variants (model-variants config model)]
      (config/notify-fields-changed-only!
       {:chat {:variants (or variants [])
               :select-variant (select-variant agent-config variants)}}
       messenger
       db*))))

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
