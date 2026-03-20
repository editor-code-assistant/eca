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

(def ^:private default-port
  "Base port for the remote server when no explicit port is configured."
  6666)

(def ^:private max-port-attempts
  "Number of sequential ports to try before giving up."
  20)

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

(defn ^:private try-start-jetty
  "Attempts to start Jetty on the given port. Returns the Server on success,
   nil on BindException."
  ^Server [handler port]
  (try
    (jetty/run-jetty handler {:port port :host "0.0.0.0" :join? false})
    (catch BindException _
      nil)))

(defn ^:private start-with-retry
  "Tries sequential ports starting from base-port up to max-port-attempts.
   Returns [server actual-port] on success, nil if all attempts fail."
  [handler base-port]
  (loop [port base-port
         attempts 0]
    (when (< attempts max-port-attempts)
      (if-let [server (try-start-jetty handler port)]
        [server (.getLocalPort ^NetworkConnector (first (.getConnectors ^Server server)))]
        (do (logger/debug logger-tag (str "Port " port " in use, trying " (inc port) "..."))
            (recur (inc port) (inc attempts)))))))

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
            user-port (:port remote-config)
            ;; Use atom so the handler sees host:port after Jetty resolves the actual port
            host+port* (atom host-base)
            handler (routes/create-handler components
                                           {:token token
                                            :host* host+port*
                                            :sse-connections* sse-connections*})]
        (try
          (if-let [[^Server jetty-server actual-port]
                   (if user-port
                     ;; User-specified port: single attempt, no retry
                     (if-let [server (try-start-jetty handler user-port)]
                       [server (.getLocalPort ^NetworkConnector (first (.getConnectors ^Server server)))]
                       (do (logger/warn logger-tag "Port" user-port "is already in use."
                                        "Remote server will not start.")
                           nil))
                     ;; Default: try sequential ports starting from default-port
                     (or (start-with-retry handler default-port)
                         (do (logger/warn logger-tag
                                          (str "Could not bind to ports " default-port "-"
                                               (+ default-port (dec max-port-attempts))
                                               ". Remote server will not start."))
                             nil)))]
            (let [host-with-port (str host-base ":" actual-port)
                  _ (reset! host+port* host-with-port)
                  heartbeat-ch (sse/start-heartbeat! sse-connections*)
                  connect-url (str "https://web.eca.dev?host="
                                   host-with-port
                                   "&pass=" token)]
              (logger/info logger-tag (str "🌐 Remote server started on port " actual-port))
              (logger/info logger-tag (str "🔗 " connect-url))
              {:server jetty-server
               :sse-connections* sse-connections*
               :heartbeat-stop-ch heartbeat-ch
               :token token
               :host host-with-port
               :connect-url connect-url})
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
