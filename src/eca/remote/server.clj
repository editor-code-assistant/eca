(ns eca.remote.server
  "HTTP server lifecycle for the remote web control server."
  (:require
   [eca.config :as config]
   [eca.logger :as logger]
   [eca.remote.auth :as auth]
   [eca.remote.routes :as routes]
   [eca.remote.sse :as sse]
   [ring.adapter.jetty :as jetty])
  (:import
   [java.net BindException InetAddress]
   [org.eclipse.jetty.server NetworkConnector Server]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[REMOTE]")

(defn- detect-host
  "Auto-detects the LAN IP via InetAddress/getLocalHost.
   Falls back to 127.0.0.1 if detection fails or returns loopback."
  []
  (try
    (let [addr (InetAddress/getLocalHost)
          host (.getHostAddress addr)]
      (if (.isLoopbackAddress addr)
        (do (logger/warn logger-tag "Auto-detected loopback address. Consider setting remote.host in config.")
            "127.0.0.1")
        host))
    (catch Exception e
      (logger/warn logger-tag "Failed to detect LAN IP:" (.getMessage e)
                   "Consider setting remote.host in config.")
      "127.0.0.1")))

(defn- resolve-port
  "Returns the configured port, or 0 for auto-assignment."
  [remote-config]
  (or (:port remote-config) 0))

(defn start!
  "Starts the remote HTTP server if enabled in config.
   Returns a map with :server :sse-connections* :heartbeat-stop-ch :token :host
   or nil if not enabled or port is in use."
  [components]
  (let [db @(:db* components)
        config (config/all db)
        remote-config (:remote config)]
    (when (:enabled remote-config)
      (let [sse-connections* (sse/create-connections)
            token (or (:password remote-config) (auth/generate-token))
            host (or (:host remote-config) (detect-host))
            port (resolve-port remote-config)
            handler (routes/create-handler components
                                           {:token token
                                            :host host
                                            :sse-connections* sse-connections*})]
        (try
          (let [jetty-server ^Server (jetty/run-jetty handler
                                                      {:port port
                                                       :host "0.0.0.0"
                                                       :join? false})
                actual-port (.getLocalPort ^NetworkConnector (first (.getConnectors jetty-server)))
                heartbeat-ch (sse/start-heartbeat! sse-connections*)
                connect-url (str "https://web.eca.dev?host="
                                 host ":" actual-port
                                 "&token=" token)]
            (logger/info logger-tag (str "🌐 Remote server started on port " actual-port))
            (logger/info logger-tag (str "🔗 " connect-url))
            {:server jetty-server
             :sse-connections* sse-connections*
             :heartbeat-stop-ch heartbeat-ch
             :token token
             :host (str host ":" actual-port)})
          (catch BindException e
            (logger/warn logger-tag "Port" port "is already in use:" (.getMessage e)
                         "Remote server will not start.")
            nil)
          (catch Exception e
            (logger/warn logger-tag "Failed to start remote server:" (.getMessage e))
            nil))))))

(defn stop!
  "Stops the remote HTTP server, broadcasting disconnecting event first."
  [{:keys [^Server server sse-connections* heartbeat-stop-ch]}]
  (when server
    (logger/info logger-tag "Stopping remote server...")
    ;; Broadcast disconnecting event
    (when sse-connections*
      (sse/broadcast! sse-connections* "session:disconnecting" {:reason "shutdown"}))
    ;; Stop heartbeat
    (when heartbeat-stop-ch
      (clojure.core.async/close! heartbeat-stop-ch))
    ;; Give in-flight responses a moment
    (Thread/sleep 1000)
    ;; Close all SSE connections
    (when sse-connections*
      (sse/close-all! sse-connections*))
    ;; Stop Jetty with 5s timeout
    (.setStopTimeout server 5000)
    (.stop server)
    (logger/info logger-tag "Remote server stopped.")))
