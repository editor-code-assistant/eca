(ns eca.remote.server
  "HTTP server lifecycle for the remote web control server."
  (:require
   [clojure.core.async :as async]
   [eca.config :as config]
   [eca.logger :as logger]
   [eca.remote.auth :as auth]
   [eca.remote.routes :as routes]
   [eca.remote.sse :as sse]
   [ring.adapter.jetty :as jetty])
  (:import
   [java.net BindException Inet4Address InetAddress NetworkInterface]
   [org.eclipse.jetty.server NetworkConnector Server]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[REMOTE]")

(defn ^:private detect-lan-ip
  "Enumerates network interfaces to find a site-local (private) IPv4 address.
   Returns the IP string or nil when none is found."
  []
  (try
    (->> (enumeration-seq (NetworkInterface/getNetworkInterfaces))
         (filter (fn [^NetworkInterface ni]
                   (and (.isUp ni)
                        (not (.isLoopback ni)))))
         (mapcat (fn [^NetworkInterface ni]
                   (enumeration-seq (.getInetAddresses ni))))
         (filter (fn [^InetAddress addr]
                   (and (instance? Inet4Address addr)
                        (.isSiteLocalAddress addr))))
         (some (fn [^InetAddress addr] (.getHostAddress addr))))
    (catch Exception _ nil)))

(defn ^:private detect-host
  "Auto-detects the LAN IP by scanning network interfaces for a private IPv4 address.
   Falls back to InetAddress/getLocalHost, then 127.0.0.1."
  []
  (or (detect-lan-ip)
      (try
        (let [addr (InetAddress/getLocalHost)
              host (.getHostAddress addr)]
          (when-not (.isLoopbackAddress addr) host))
        (catch Exception _ nil))
      (do (logger/warn logger-tag "Could not detect LAN IP. Consider setting remote.host in config.")
          "127.0.0.1")))

(defn ^:private resolve-port
  "Returns the configured port, or 0 for auto-assignment."
  [remote-config]
  (or (:port remote-config) 0))

(defn start!
  "Starts the remote HTTP server if enabled in config.
   sse-connections* is the shared SSE connections atom (also used by BroadcastMessenger).
   Returns a map with :server :sse-connections* :heartbeat-stop-ch :token :host
   or nil if not enabled or port is in use."
  [components sse-connections*]
  (let [db @(:db* components)
        config (config/all db)
        remote-config (:remote config)]
    (when (:enabled remote-config)
      (let [token (or (:password remote-config) (auth/generate-token))
            host-base (or (:host remote-config) (detect-host))
            port (resolve-port remote-config)
            ;; Use atom so the handler sees host:port after Jetty resolves the actual port
            host+port* (atom host-base)
            handler (routes/create-handler components
                                           {:token token
                                            :host* host+port*
                                            :sse-connections* sse-connections*})]
        (try
          (let [jetty-server ^Server (jetty/run-jetty handler
                                                      {:port port
                                                       :host "0.0.0.0"
                                                       :join? false})
                actual-port (.getLocalPort ^NetworkConnector (first (.getConnectors jetty-server)))
                host-with-port (str host-base ":" actual-port)
                _ (reset! host+port* host-with-port)
                heartbeat-ch (sse/start-heartbeat! sse-connections*)
                connect-url (str "https://web.eca.dev?host="
                                 host-with-port
                                 "&token=" token)]
            (logger/info logger-tag (str "🌐 Remote server started on port " actual-port))
            (logger/info logger-tag (str "🔗 " connect-url))
            {:server jetty-server
             :sse-connections* sse-connections*
             :heartbeat-stop-ch heartbeat-ch
             :token token
             :host host-with-port
             :connect-url connect-url})
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
      (async/close! heartbeat-stop-ch))
    ;; Close all SSE connections
    (when sse-connections*
      (sse/close-all! sse-connections*))
    ;; Stop Jetty with 5s timeout
    (.setStopTimeout server 5000)
    (.stop server)
    (logger/info logger-tag "Remote server stopped.")))
