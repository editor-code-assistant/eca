(ns eca.modules.remote
  "The `:eca/remote` module: ECA's remote HTTP/SSE server, for web and remote
   clients (web.eca.dev). Off by default: `remote.enabled` turns it on, as it
   turned on the server before modules, and so do `modules.eca/remote.enabled`
   and `/modules enable eca/remote`. The server starts at the same point of
   ECA's startup as before modules (`eca.handlers/initialized`); the module's
   `:start` and `:stop` start and stop it when the module is turned on or off
   later. Once `remote.enabled` turned it on, it stays on for the session, and
   the server stops when ECA exits, as before modules.

   The server's code, `eca.modules.remote.server`, is resolved when called: it
   requires the remote handlers, which require `eca.handlers`, which requires
   this namespace.")

(set! *warn-on-reflection* true)

(defn ^:private remote-server [fn-name]
  (requiring-resolve (symbol "eca.modules.remote.server" fn-name)))

(def ^:private ask-question-path
  [:client-capabilities :code-assistant :chat-capabilities :ask-question])

;; the running server, as eca.modules.remote.server/start! returns it
(defonce ^:private server* (atom nil))

(def ^:private lock (Object.))

(defn running
  "The running remote server, or nil."
  []
  @server*)

(defn start-server!
  "Starts the remote server, unless it runs, for the session's `:db*` and its
   broadcasting `:messenger`. Returns the server, or nil when it couldn't
   start, e.g. its port is in use."
  [{:keys [db* messenger] :as components}]
  (locking lock
    (or @server*
        (let [ask-question (get-in @db* ask-question-path)]
          (when-let [rs ((remote-server "start!") components (:sse-connections* messenger))]
            (swap! db* assoc
                   :remote-connect-url (:connect-url rs)
                   :remote-host (:host rs)
                   :remote-token (:token rs)
                   :remote-private-host? (:private-host? rs))
            ;; starting it lets the model ask questions through the web clients
            (reset! server* (assoc rs ::ask-question ask-question)))))))

(defn ^:private restore [db path value]
  (if (some? value)
    (assoc-in db path value)
    (update-in db (butlast path) dissoc (last path))))

(defn stop-server!
  "Stops the remote server if it runs, and undoes what starting it changed in
   the session."
  [{:keys [db*]}]
  (locking lock
    (when-let [rs @server*]
      (reset! server* nil)
      ((remote-server "stop!") rs)
      (when db*
        (swap! db* (fn [db]
                     (-> db
                         (dissoc :remote-connect-url :remote-host :remote-token :remote-private-host?)
                         (restore ask-question-path (::ask-question rs)))))))
    nil))

(def module
  {:id :eca/remote
   :description "The remote HTTP/SSE server, for web and remote clients like web.eca.dev."
   :default-enabled? false
   ;; its default, {:enabled false}, stays in ECA's initial config
   :config-spec {:remote {:type :map}}
   ;; at startup the server already runs: start-server! does nothing then
   :start (fn [ctx] (start-server! ctx) nil)
   :stop stop-server!})
