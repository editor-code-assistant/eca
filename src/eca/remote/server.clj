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
   [java.io IOException]
   [java.net BindException Inet4Address InetAddress NetworkInterface]
   [org.eclipse.jetty.server NetworkConnector Server ServerConnector]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[REMOTE]")

(def ^:private default-port
  "Base port for the remote server when no explicit port is configured."
  7777)

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

(defn ^:private private-ip?
  "Returns true if the given IP string is a private/local address
   (10.x.x.x, 172.16-31.x.x, 192.168.x.x, or 127.x.x.x)."
  [^String ip]
  (try
    (.isSiteLocalAddress (InetAddress/getByName ip))
    (catch Exception _ false)))

(defn ^:private try-start-jetty
  "Tries to start Jetty on the given port and host.
   Returns the Server on success, nil on BindException/IOException."
  ^Server [handler port host]
  (try
    (let [server (jetty/run-jetty handler {:port port :host host :join? false})]
      (logger/debug logger-tag (str "Bound to " host ":" port))
      server)
    (catch BindException _ nil)
    (catch IOException _ nil)))

(defn ^:private add-connector!
  "Adds a secondary ServerConnector to an existing Jetty server.
   Returns true on success, false on bind failure."
  [^Server server port host]
  (try
    (let [connector (doto (ServerConnector. server)
                      (.setHost host)
                      (.setPort port))]
      (.addConnector server connector)
      (.start connector)
      (logger/debug logger-tag (str "Added connector " host ":" port))
      true)
    (catch BindException _ false)
    (catch IOException _ false)))

(defn ^:private try-start-jetty-any-host
  "Tries to start Jetty on the given port. Attempts 0.0.0.0 first for full
   connectivity. When that fails (e.g. Tailscale holds the port on its virtual
   interface), binds to 127.0.0.1 and adds the LAN IP as a secondary connector
   so that both Tailscale proxy (which targets localhost) and Direct LAN work.
   Returns [server bind-host] on success, nil if all fail."
  [handler port lan-ip]
  ;; 1. Try 0.0.0.0 — covers all interfaces in one binding
  (if-let [server (try-start-jetty handler port "0.0.0.0")]
    [server "0.0.0.0"]
    ;; 2. 0.0.0.0 failed — bind localhost first (for Tailscale proxy), then
    ;;    add the LAN IP as a secondary connector so Direct LAN also works.
    (when-let [server (try-start-jetty handler port "127.0.0.1")]
      (when lan-ip
        (if (add-connector! server port lan-ip)
          (logger/debug logger-tag (str "Also listening on " lan-ip ":" port " for Direct LAN"))
          (logger/warn logger-tag (str "Could not bind to " lan-ip ":" port " — Direct LAN connections may not work"))))
      [server (if lan-ip "127.0.0.1+lan" "127.0.0.1")])))

(defn ^:private start-with-retry
  "Tries sequential ports starting from base-port up to max-port-attempts.
   For each port, tries all bind-hosts before moving to the next port.
   Returns [server actual-port bind-host] on success, nil if all attempts fail."
  [handler base-port lan-ip]
  (loop [port base-port
         attempts 0]
    (when (< attempts max-port-attempts)
      (if-let [[server bind-host] (try-start-jetty-any-host handler port lan-ip)]
        [server (.getLocalPort ^NetworkConnector (first (.getConnectors ^Server server))) bind-host]
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
            lan-ip (detect-lan-ip)
            user-port (:port remote-config)
            ;; Use atom so the handler sees host:port after Jetty resolves the actual port
            host+port* (atom host-base)
            handler (routes/create-handler components
                                           {:token token
                                            :host* host+port*
                                            :sse-connections* sse-connections*})]
        (try
          (if-let [[^Server jetty-server actual-port bind-host]
                   (if user-port
                     ;; User-specified port: single attempt, try all bind hosts
                     (if-let [[server bh] (try-start-jetty-any-host handler user-port lan-ip)]
                       [server (.getLocalPort ^NetworkConnector (first (.getConnectors ^Server server))) bh]
                       (do (logger/warn logger-tag "Port" user-port "is already in use."
                                        "Remote server will not start.")
                           nil))
                     ;; Default: try sequential ports starting from default-port
                     (or (start-with-retry handler default-port lan-ip)
                         (do (logger/warn logger-tag
                                          (str "Could not bind to ports " default-port "-"
                                               (+ default-port (dec max-port-attempts))
                                               ". Remote server will not start."))
                             nil)))]
            (let [host-with-port (str host-base ":" actual-port)
                  _ (reset! host+port* host-with-port)
                  heartbeat-ch (sse/start-heartbeat! sse-connections*)
                  private? (private-ip? host-base)
                  localhost-only? (and (= bind-host "127.0.0.1") (not lan-ip))
                  connect-url (if private?
                                (str "https://web.eca.dev?host="
                                     host-with-port
                                     "&pass=" token
                                     "&protocol=http")
                                (str "https://web.eca.dev?host="
                                     host-with-port
                                     "&pass=" token
                                     "&protocol=https"))]
              (when (and localhost-only? private? (not= host-base "127.0.0.1"))
                (logger/warn logger-tag
                             (str "⚠️  Bound to 127.0.0.1:" actual-port " (localhost only) because another service "
                                  "(e.g. Tailscale) holds port " actual-port " on the external interface. "
                                  "Direct LAN connections to " host-base " will not work. "
                                  "Use a different port, stop the conflicting service, or connect via Tailscale.")))
              (logger/info logger-tag (str "🌐 Remote server started on port " actual-port " — use /remote for connection details"))
              {:server jetty-server
               :sse-connections* sse-connections*
               :heartbeat-stop-ch heartbeat-ch
               :token token
               :host host-with-port
               :private-host? private?
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
