(ns eca.handlers
  (:require
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.chat :as f.chat]
   [eca.features.tools :as f.tools]
   [eca.features.tools.mcp :as f.mcp]
   [eca.llm-api :as llm-api]
   [eca.logger :as logger]
   [eca.models :as models]))

(set! *warn-on-reflection* true)

(defn ^:private initialize-models! [db* config]
  (let [all-models (models/all)
        eca-models (filter
                    (fn [[model _config]]
                      (get-in config [:models model]))
                    all-models)]
    (swap! db* update :models merge eca-models)
    (when-let [custom-providers (seq (:customProviders config))]
      (let [models (reduce
                    (fn [models [provider {provider-models :models default-model :defaultModel}]]
                      (reduce
                       (fn [m model]
                         (let [known-model (get all-models model)]
                           (assoc m
                                  (str (name provider) "/" model)
                                  {:tools (or (:tools known-model) true)
                                   :reason? (or (:reason? known-model) true)
                                   :web-search (or (:web-search known-model) true)
                                   :max-output-tokens (:max-output-tokens known-model)
                                   :custom-provider? true
                                   :default-model? (= model default-model)})))
                       models
                       provider-models))
                    {}
                    custom-providers)]
        (swap! db* update :models merge models))))
  (when-let [ollama-models (seq (llm-api/extra-models config))]
    (let [models (reduce
                  (fn [models {:keys [model] :as ollama-model}]
                    (assoc models
                           (str config/ollama-model-prefix model)
                           (select-keys ollama-model [:tools :reason?])))
                  {}
                  ollama-models)]
      (swap! db* update :models merge models))))

(defn initialize [{:keys [db* config]} params]
  (logger/logging-task
   :eca/initialize
   (swap! db* assoc
          :client-info (:client-info params)
          :workspace-folders (:workspace-folders params)
          :client-capabilities (:capabilities params)
          :chat-behavior (or (-> params :initialization-options :chat-behavior) (:chat-behavior @db*)))
   (initialize-models! db* config)
   (db/load-db-from-cache! db*)
   {:models (keys (:models @db*))
    :chat-default-model (f.chat/default-model @db* config)
    :chat-behaviors (:chat-behaviors @db*)
    :chat-default-behavior (:chat-default-behavior @db*)
    :chat-welcome-message (:welcomeMessage (:chat config))}))

(defn initialized [{:keys [db* messenger config]}]
  (future
    (f.tools/init-servers! db* messenger config)))

(defn shutdown [{:keys [db*]}]
  (logger/logging-task
   :eca/shutdown
   (f.mcp/shutdown! db*)
   (reset! db* db/initial-db)
   nil))

(defn chat-prompt [{:keys [messenger db* config]} params]
  (logger/logging-task
   :eca/chat-prompt
   (f.chat/prompt params db* messenger config)))

(defn chat-query-context [{:keys [db* config]} params]
  (logger/logging-task
   :eca/chat-query-context
   (f.chat/query-context params db* config)))

(defn chat-query-commands [{:keys [db* config]} params]
  (logger/logging-task
   :eca/chat-query-commands
   (f.chat/query-commands params db* config)))

(defn chat-tool-call-approve [{:keys [db*]} params]
  (logger/logging-task
   :eca/chat-tool-call-approve
   (f.chat/tool-call-approve params db*)))

(defn chat-tool-call-reject [{:keys [db*]} params]
  (logger/logging-task
   :eca/chat-tool-call-reject
   (f.chat/tool-call-reject params db*)))

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
