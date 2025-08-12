(ns eca.server
  (:require
   [clojure.core.async :as async]
   [eca.config :as config]
   [eca.db :as db]
   [eca.handlers :as handlers]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.nrepl :as nrepl]
   [eca.shared :as shared]
   [lsp4clj.io-server :as io-server]
   [lsp4clj.liveness-probe :as liveness-probe]
   [lsp4clj.server :as lsp.server]))

(set! *warn-on-reflection* true)

(defn ^:private log-wrapper-fn
  [_level & args]
  (apply logger/info args))

(defn ^:private exit [server]
  (logger/logging-task
   :eca/exit
   (lsp.server/shutdown server) ;; blocks, waiting up to 10s for previously received messages to be processed
   (shutdown-agents)
   (System/exit 0)))

(defn ^:private with-config [components]
  (assoc components :config (config/all @(:db* components))))

(defmethod lsp.server/receive-request "initialize" [_ {:keys [server] :as components} params]
  (when-let [parent-process-id (:process-id params)]
    (liveness-probe/start! parent-process-id log-wrapper-fn #(exit server)))
  (reset! config/initialization-config* (shared/map->camel-cased-map (:initialization-options params)))
  (handlers/initialize (with-config components) params))

(defmethod lsp.server/receive-notification "initialized" [_ components _params]
  (handlers/initialized (with-config components)))

(defmethod lsp.server/receive-request "shutdown" [_ components _params]
  (handlers/shutdown (with-config components)))

(defmethod lsp.server/receive-notification "exit" [_ {:keys [server]} _params]
  (exit server))

(defmethod lsp.server/receive-request "chat/prompt" [_ components params]
  (handlers/chat-prompt (with-config components) params))

(defmethod lsp.server/receive-request "chat/queryContext" [_ components params]
  (handlers/chat-query-context (with-config components) params))

(defmethod lsp.server/receive-request "chat/queryCommands" [_ components params]
  (handlers/chat-query-commands (with-config components) params))

(defmethod lsp.server/receive-notification "chat/toolCallApprove" [_ components params]
  (handlers/chat-tool-call-approve (with-config components) params))

(defmethod lsp.server/receive-notification "chat/toolCallReject" [_ components params]
  (handlers/chat-tool-call-reject (with-config components) params))

(defmethod lsp.server/receive-notification "chat/promptStop" [_ components params]
  (handlers/chat-prompt-stop (with-config components) params))

(defmethod lsp.server/receive-request "chat/delete" [_ components params]
  (handlers/chat-delete (with-config components) params))

(defmethod lsp.server/receive-notification "mcp/stopServer" [_ components params]
  (handlers/mcp-stop-server (with-config components) params))

(defmethod lsp.server/receive-notification "mcp/startServer" [_ components params]
  (handlers/mcp-start-server (with-config components) params))

(defn ^:private monitor-server-logs [log-ch]
  ;; NOTE: if this were moved to `initialize`, after timbre has been configured,
  ;; the server's startup logs and traces would appear in the regular log file
  ;; instead of the temp log file. We don't do this though because if anything
  ;; bad happened before `initialize`, we wouldn't get any logs.
  (async/go-loop []
    (when-let [log-args (async/<! log-ch)]
      (apply log-wrapper-fn log-args)
      (recur))))

(defn ^:private setup-dev-environment [db* components]
  ;; We don't have an ENV=development flag, so the next best indication that
  ;; we're in a development environment is whether we're able to start an nREPL.
  (when-let [nrepl-port (nrepl/setup-nrepl)]
    ;; Save the port in the db, so it can be reported in server-info.
    (swap! db* assoc :port nrepl-port)
    ;; Add components to db* so it's possible to manualy call functions
    ;; which expect specific components
    (swap! db* assoc-in [:dev :components] components)
    ;; In the development environment, make the db* atom available globally as
    ;; db/db*, so it can be inspected in the nREPL.
    (alter-var-root #'db/db* (constantly db*))))

(defrecord ^:private ServerMessenger [server db*]
  messenger/IMessenger

  (chat-content-received [_this content]
    (lsp.server/discarding-stdout
     (lsp.server/send-notification server "chat/contentReceived" content)))
  (tool-server-updated [_this params]
    (lsp.server/discarding-stdout
     (lsp.server/send-notification server "tool/serverUpdated" params)))
  (showMessage [_this msg]
    (lsp.server/discarding-stdout
     (lsp.server/send-notification server "$/showMessage" msg))))

(defn start-server! [server]
  (let [db* (atom db/initial-db)
        components {:db* db*
                    :messenger (->ServerMessenger server db*)
                    :server server}]
    (logger/info "[server]" "Starting server...")
    (monitor-server-logs (:log-ch server))
    (setup-dev-environment db* components)
    (lsp.server/start server components)))

(defn run-io-server! [verbose?]
  (lsp.server/discarding-stdout
   (let [log-ch (async/chan (async/sliding-buffer 20))
         server (io-server/stdio-server {:log-ch log-ch
                                         :trace-ch log-ch
                                         :trace-level (if verbose? "verbose" "off")})]
     (start-server! server))))
