(ns eca.server
  (:require
   [clojure.core.async :as async]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.db :as db]
   [eca.handlers :as handlers]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.metrics :as metrics]
   [eca.modules :as modules]
   [eca.modules.otel :as m.otel]
   [eca.modules.remote :as m.remote]
   [eca.modules.remote.messenger :as m.remote.messenger]
   [eca.modules.remote.server :as m.remote.server]
   [eca.nrepl :as nrepl]
   [eca.shared :as shared :refer [assoc-some]]
   [jsonrpc4clj.io-server :as io-server]
   [jsonrpc4clj.server :as jsonrpc.server]
   [promesa.core :as p])
  (:import
   [java.lang ProcessHandle]
   [java.util Optional]
   [java.util.concurrent CompletableFuture]
   [java.util.function BiConsumer]))

(set! *warn-on-reflection* true)

(defn ^:private format-jsonrpc-trace
  "Adapt jsonrpc4clj verbose-trace text to ECA's log format."
  [message]
  (if (and (string? message)
           (string/starts-with? message "[Trace - "))
    (-> message
        ;; Ours is already on the line, so strip jsonrpc4clj's redundant timestamp.
        (string/replace #"^\[Trace - [^\]]+\] " "[TRACE] ")
        ;; Notifications without arguments otherwise produce noisy trailing `Params: null`.
        (string/replace #"\nParams: null\n*$" "")
        ;; jsonrpc4clj appends blank lines as a visual separator for LSP debugging.
        (string/replace #"\n+$" "")
        (string/replace #"\n" "\n  "))
    message))

(defn ^:private log-wrapper-fn
  "Route jsonrpc4clj log/trace entries through ECA's logger.
   jsonrpc4clj always tags trace messages as :debug, but they should
   pass at INFO level so --verbose works independently of --log-level."
  [level & args]
  (let [log-fn (case level
                 :error logger/error
                 :warn  logger/warn
                 :info  logger/info
                 :debug logger/info
                 logger/info)
        args (cons (format-jsonrpc-trace (first args)) (rest args))]
    (apply log-fn args)))

(defn ^:private exit [server]
  (metrics/task
    :eca/exit
    ;; the remote server stops here, as before modules: shutdown leaves the
    ;; eca/remote module running
    (let [remote-stop-f (when-let [rs (m.remote/running)]
                          (future (m.remote.server/stop! rs)))]
      (jsonrpc.server/shutdown server) ;; blocks, waiting up to 10s for previously received messages to be processed
      (some-> remote-stop-f deref))
    (shutdown-agents)
    (System/exit 0)))

(defn ^:private with-config [components]
  (assoc components :config (config/all @(:db* components))))

(defmacro ^:private eventually
  "Dispatches handler body to a background thread, returning a promise.
  Releases the protocol thread immediately. jsonrpc4clj resolves the promise
  and sends the JSON-RPC response when the work completes."
  [& body]
  `(p/thread ~@body))

(defmacro ^:private async-notify
  "Dispatches notification handler body to a background thread.
  Releases the protocol thread immediately. Exceptions are logged since
  notification handlers have no response channel."
  [& body]
  `(p/thread
     (try
       ~@body
       (catch Throwable e#
         (logger/error e# "[server] Error in async notification handler")))))

(defn ^:private start-liveness-probe!
  "Monitor parent process `ppid`; invoke `on-exit` once when the parent
  disappears. Event-driven via `ProcessHandle.onExit` so we don't shell out
  to `kill -0` (which loops forever when the binary is missing)."
  [ppid on-exit]
  (let [fire! (fn []
                (try (on-exit)
                     (catch Throwable t
                       (logger/error t "[server] Liveness probe - on-exit threw"))))]
    (try
      (let [opt ^Optional (ProcessHandle/of (long ppid))]
        (if (.isPresent opt)
          (let [^ProcessHandle handle (.get opt)
                ^CompletableFuture fut (.onExit handle)]
            (.whenComplete fut
                           (reify BiConsumer
                             (accept [_ _result ex]
                               (if (some? ex)
                                 (logger/warn ex "[server] Liveness probe - failed waiting for parent" ppid "- monitor disabled")
                                 (do
                                   (logger/info "[server] Liveness probe - parent" ppid "exited - exiting server")
                                   (fire!))))))
            nil)
          (do
            (logger/info "[server] Liveness probe - parent" ppid "is not running - exiting server")
            (fire!))))
      (catch Throwable t
        (logger/error t "[server] Liveness probe - failed to start; parent monitoring disabled")
        nil))))

(defn ^:private initialize [{:keys [server] :as components} params]
  (when-let [parent-process-id (:process-id params)]
    (start-liveness-probe! parent-process-id #(exit server)))
  (handlers/initialize components params))

(def ^:private core-methods
  "The protocol methods core serves, by name: `:kind`, `:request` or
   `:notification`, and `:handler`, `(fn [components params])`. It runs on a
   thread of its own when `:thread?` (`eventually`, `async-notify`), else on
   the protocol thread, and gets the session's config in its components
   unless `:no-config?`. Modules serve more with `:rpc`."
  {"initialize"                          {:kind :request :no-config? true :handler initialize}
   "initialized"                         {:kind :notification :thread? true :handler (fn [components _] (handlers/initialized components))}
   "shutdown"                            {:kind :request :handler (fn [components _] (handlers/shutdown components))}
   "exit"                                {:kind :notification :no-config? true :handler (fn [{:keys [server]} _] (exit server))}
   "workspace/didChangeWorkspaceFolders" {:kind :notification :thread? true :handler #'handlers/workspace-did-change-folders}
   ;; resolves its config itself, after echoing the prompt
   "chat/prompt"                         {:kind :request :thread? true :no-config? true :handler #'handlers/chat-prompt}
   "chat/inlinePrompt"                   {:kind :request :thread? true :handler #'handlers/chat-inline-prompt}
   "chat/queryContext"                   {:kind :request :thread? true :handler #'handlers/chat-query-context}
   "chat/queryFiles"                     {:kind :request :thread? true :handler #'handlers/chat-query-files}
   "chat/queryCommands"                  {:kind :request :thread? true :handler #'handlers/chat-query-commands}
   "chat/toolCallApprove"                {:kind :notification :handler #'handlers/chat-tool-call-approve}
   "chat/toolCallReject"                 {:kind :notification :handler #'handlers/chat-tool-call-reject}
   "chat/promptStop"                     {:kind :notification :handler #'handlers/chat-prompt-stop}
   "chat/promptSteer"                    {:kind :notification :handler #'handlers/chat-prompt-steer}
   "chat/promptSteerRemove"              {:kind :notification :handler #'handlers/chat-prompt-steer-remove}
   "chat/delete"                         {:kind :request :thread? true :handler #'handlers/chat-delete}
   "chat/clear"                          {:kind :request :thread? true :handler #'handlers/chat-clear}
   "chat/rollback"                       {:kind :request :thread? true :handler #'handlers/chat-rollback}
   "chat/addFlag"                        {:kind :request :thread? true :handler #'handlers/chat-add-flag}
   "chat/removeFlag"                     {:kind :request :thread? true :handler #'handlers/chat-remove-flag}
   "chat/fork"                           {:kind :request :thread? true :handler #'handlers/chat-fork}
   "chat/update"                         {:kind :request :thread? true :handler #'handlers/chat-update}
   "chat/list"                           {:kind :request :thread? true :handler #'handlers/chat-list}
   "chat/open"                           {:kind :request :thread? true :handler #'handlers/chat-open}
   "chat/history"                        {:kind :request :thread? true :handler #'handlers/chat-history}
   "chat/selectedAgentChanged"           {:kind :notification :thread? true :handler #'handlers/chat-selected-agent-changed}
   ;; legacy: the old name of chat/selectedAgentChanged
   "chat/selectedBehaviorChanged"        {:kind :notification :thread? true :handler #'handlers/chat-selected-agent-changed}
   "chat/selectedModelChanged"           {:kind :notification :thread? true :handler #'handlers/chat-selected-model-changed}
   "mcp/stopServer"                      {:kind :notification :thread? true :handler #'handlers/mcp-stop-server}
   "mcp/startServer"                     {:kind :notification :thread? true :handler #'handlers/mcp-start-server}
   "mcp/connectServer"                   {:kind :notification :thread? true :handler #'handlers/mcp-connect-server}
   "mcp/logoutServer"                    {:kind :notification :thread? true :handler #'handlers/mcp-logout-server}
   "mcp/disableServer"                   {:kind :notification :thread? true :handler #'handlers/mcp-disable-server}
   "mcp/enableServer"                    {:kind :notification :thread? true :handler #'handlers/mcp-enable-server}
   "mcp/updateServer"                    {:kind :request :thread? true :handler #'handlers/mcp-update-server}
   "mcp/addServer"                       {:kind :request :thread? true :handler #'handlers/mcp-add-server}
   "mcp/removeServer"                    {:kind :request :thread? true :handler #'handlers/mcp-remove-server}
   "jobs/list"                           {:kind :request :thread? true :handler #'handlers/jobs-list}
   "jobs/kill"                           {:kind :request :thread? true :handler #'handlers/jobs-kill}
   "jobs/readOutput"                     {:kind :request :thread? true :handler #'handlers/jobs-read-output}
   "providers/list"                      {:kind :request :thread? true :handler #'handlers/providers-list}
   "providers/login"                     {:kind :request :thread? true :handler #'handlers/providers-login}
   "providers/loginInput"                {:kind :request :thread? true :handler #'handlers/providers-login-input}
   "providers/logout"                    {:kind :request :thread? true :handler #'handlers/providers-logout}})

(defn ^:private module-method
  "The method a module serves as `method` (`:rpc`), when it's of `kind`: the
   first active module's, else a built-in module's whatever its state, as
   core served these methods before modules. It runs on a thread of its own."
  [config kind method]
  (let [[found & others] (for [[entry rpc] (modules/contributions config :rpc)
                               :let [rpc-method (get rpc method)]
                               :when rpc-method]
                           [entry rpc-method])
        _ (doseq [[other] others]
            (logger/warn "[server]" (format "Module %s has a method %s, like module %s; ignoring it"
                                            (:id other) method (:id (first found)))))
        [entry rpc-method] (or found
                               (some (fn [module]
                                       (when-let [rpc-method (get-in module [:rpc method])]
                                         [{:id (:id module) :module module :built-in? true} rpc-method]))
                                     (modules/built-in-modules)))]
    (when (= kind (:kind rpc-method))
      {:kind kind
       :thread? true
       :handler (fn [components params]
                  (let [ctx (modules/module-ctx components entry)
                        handler (:handler rpc-method)]
                    (if (:built-in? entry)
                      (handler ctx params)
                      (modules/invoke ctx entry (str "method " method) {} handler ctx params))))})))

(defn ^:private find-method
  "Method `method` of `kind`: core's, else a module's."
  [kind method {:keys [db*]}]
  (if-let [core-method (get core-methods method)]
    (when (= kind (:kind core-method))
      core-method)
    (module-method (config/all @db*) kind method)))

(defn ^:private run-method [{:keys [kind handler thread? no-config?]} components params]
  (let [run #(handler (if no-config? components (with-config components)) params)]
    (cond
      (not thread?) (run)
      (= :request kind) (eventually (run))
      :else (async-notify (run)))))

;; jsonrpc4clj's own default methods answer ::method-not-found: "Method not
;; found" for a request, a warning for a notification
(defmethod jsonrpc.server/receive-request :default [method components params]
  (if-let [m (find-method :request method components)]
    (run-method m components params)
    ::jsonrpc.server/method-not-found))

(defmethod jsonrpc.server/receive-notification :default [method components params]
  (if-let [m (find-method :notification method components)]
    (run-method m components params)
    ::jsonrpc.server/method-not-found))

(defn ^:private monitor-server-logs [log-ch]
  ;; Start monitoring immediately so startup logs and traces are not lost before
  ;; the client sends `initialize`.
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
    (jsonrpc.server/discarding-stdout
     (jsonrpc.server/send-notification server "chat/contentReceived" content)))
  (chat-cleared [_this params]
    (jsonrpc.server/discarding-stdout
     (jsonrpc.server/send-notification server "chat/cleared" params)))
  (chat-truncated [_this params]
    (jsonrpc.server/discarding-stdout
     (jsonrpc.server/send-notification server "chat/truncated" params)))
  (chat-status-changed [_this params]
    (jsonrpc.server/discarding-stdout
     (jsonrpc.server/send-notification server "chat/statusChanged" params)))
  (chat-deleted [_this params]
    (jsonrpc.server/discarding-stdout
     (jsonrpc.server/send-notification server "chat/deleted" params)))
  (chat-opened [_this params]
    (jsonrpc.server/discarding-stdout
     (jsonrpc.server/send-notification server "chat/opened" params)))
  (config-updated [_this params]
    (jsonrpc.server/discarding-stdout
     (jsonrpc.server/send-notification server "config/updated" params)))
  (tool-server-updated [_this params]
    (jsonrpc.server/discarding-stdout
     (jsonrpc.server/send-notification server "tool/serverUpdated" params)))
  (tool-server-removed [_this params]
    (jsonrpc.server/discarding-stdout
     (jsonrpc.server/send-notification server "tool/serverRemoved" params)))
  (provider-updated [_this params]
    (jsonrpc.server/discarding-stdout
     (jsonrpc.server/send-notification server "providers/updated" params)))
  (notify! [_this method params]
    (jsonrpc.server/discarding-stdout
     (jsonrpc.server/send-notification server method params)))
  (showMessage [_this msg]
    (jsonrpc.server/discarding-stdout
     (jsonrpc.server/send-notification server "$/showMessage" msg)))
  (progress [_this params]
    (jsonrpc.server/discarding-stdout
     (jsonrpc.server/send-notification server "$/progress" params)))
  (editor-diagnostics [_this uri]
    (jsonrpc.server/discarding-stdout
     (jsonrpc.server/send-request server "editor/getDiagnostics" (assoc-some {} :uri uri))))
  (editor-definition [_this uri position]
    (jsonrpc.server/discarding-stdout
     (jsonrpc.server/send-request server "editor/getDefinition" {:uri uri :position position})))
  (editor-references [_this uri position include-declaration]
    (jsonrpc.server/discarding-stdout
     (jsonrpc.server/send-request server "editor/getReferences" (assoc-some {:uri uri :position position}
                                                                            :include-declaration include-declaration))))
  (ask-question [_this params]
    (jsonrpc.server/discarding-stdout
     (jsonrpc.server/send-request server "chat/askQuestion" params))))

(defn ^:private ->Metrics
  "The session's metrics: switchable, so the eca/otel module can turn the
   export on and off; exporting from the start when the module is on, as
   before modules."
  [db*]
  (let [config (config/all @db*)
        metrics (metrics/->switchable (metrics/->NoopMetrics))]
    (when (and (:otlp config) (modules/active? config :eca/otel))
      (m.otel/install! metrics (:otlp config)))
    metrics))

(defn start-server! [server]
  (let [db* (atom (assoc db/initial-db :started-at (System/currentTimeMillis)))
        metrics (->Metrics db*)
        stdio-messenger (->ServerMessenger server db*)
        ;; Always create SSE connections and BroadcastMessenger so the remote
        ;; HTTP server can be started later (e.g. when local project config
        ;; enables it after initialize). Broadcasting to an empty set is a no-op.
        sse-connections* (atom #{})
        ;; the eca/remote module's server (eca.modules.remote) takes them from it
        messenger (m.remote.messenger/make-broadcast-messenger stdio-messenger sse-connections* db*)
        components {:db* db*
                    :messenger messenger
                    :metrics metrics
                    :server server}]
    (logger/info "[server]" "Starting server...")
    (metrics/start! metrics)
    (monitor-server-logs (:log-ch server))
    (setup-dev-environment db* components)
    (jsonrpc.server/start server components)))

(defn run-io-server! [verbose?]
  (jsonrpc.server/discarding-stdout
   (let [log-ch (async/chan (async/sliding-buffer 20))
         server (io-server/stdio-server {:log-ch log-ch
                                         :trace-ch log-ch
                                         :trace-level (if verbose? "verbose" "off")})]
     (start-server! server))))