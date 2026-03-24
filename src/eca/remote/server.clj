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

(def ^:private virtual-interface-re
  "Matches network interface names that are typically virtual/software-defined.
   These are deprioritized when detecting the LAN IP."
  #"^(docker|br-|veth|vbox|virbr|tailscale|lo|tun|tap|wg|zt)")

(def ^:private tunnel-interface-re
  "Matches network interface name or display name for tunnel/VPN services that
   may use port-based proxying (e.g. `tailscale serve`). On Windows, binding
   0.0.0.0 on the same port would steal their TLS traffic.
   Java on Windows returns names like 'iftype53_32768' for Tailscale with
   display name 'Tailscale Tunnel', so both fields must be checked."
  #"(?i)(tailscale|wireguard|zerotier)")

(defn ^:private interface-priority
  "Returns a sort priority for a network interface (lower = preferred).
   Real hardware interfaces (wifi, ethernet) are preferred over virtual ones."
  ^long [^NetworkInterface ni]
  (let [name (.getName ni)]
    (if (re-find virtual-interface-re name) 1 0)))

(def ^:private windows?
  (-> (System/getProperty "os.name" "")
      (.toLowerCase)
      (.startsWith "windows")))

(defn ^:private has-tunnel-interfaces?
  "Returns true if any active tunnel/VPN network interface is detected.
   On Windows, binding 0.0.0.0 on a port used by such services (e.g. Tailscale serve)
   would capture their TLS traffic, causing handshake failures.
   Checks both getName() and getDisplayName() because Java on Windows uses
   opaque names like 'iftype53_32768' while the display name is 'Tailscale Tunnel'."
  []
  (try
    (boolean
     (some (fn [^NetworkInterface ni]
             (and (.isUp ni)
                  (or (re-find tunnel-interface-re (.getName ni))
                      (re-find tunnel-interface-re (.getDisplayName ni)))))
           (enumeration-seq (NetworkInterface/getNetworkInterfaces))))
    (catch Exception _ false)))

(defn ^:private detect-lan-ip
  "Enumerates network interfaces to find a site-local (private) IPv4 address.
   Prefers real hardware interfaces (wifi, ethernet) over virtual ones (docker, vbox).
   Returns the IP string or nil when none is found."
  []
  (try
    (->> (enumeration-seq (NetworkInterface/getNetworkInterfaces))
         (filter (fn [^NetworkInterface ni]
                   (and (.isUp ni)
                        (not (.isLoopback ni))
                        (not (.isVirtual ni)))))
         (sort-by interface-priority)
         (mapcat (fn [^NetworkInterface ni]
                   (->> (enumeration-seq (.getInetAddresses ni))
                        (filter (fn [^InetAddress addr]
                                  (and (instance? Inet4Address addr)
                                       (.isSiteLocalAddress addr))))
                        (map (fn [^InetAddress addr] (.getHostAddress addr))))))
         first)
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

(defn ^:private start-on-specific-interfaces
  "Binds to 127.0.0.1 first (for localhost / reverse proxy access), then adds
   the LAN IP as a secondary connector for Direct LAN access.
   Returns [server bind-host] on success, nil if bind fails."
  [handler port lan-ip]
  (when-let [server (try-start-jetty handler port "127.0.0.1")]
    (when lan-ip
      (if (add-connector! server port lan-ip)
        (logger/debug logger-tag (str "Also listening on " lan-ip ":" port " for Direct LAN"))
        (logger/warn logger-tag (str "Could not bind to " lan-ip ":" port " — Direct LAN connections may not work"))))
    [server (if lan-ip "127.0.0.1+lan" "127.0.0.1")]))

(defn ^:private try-start-jetty-any-host
  "Tries to start Jetty on the given port. On Windows with active tunnel
   interfaces (Tailscale, WireGuard, etc.), skips the 0.0.0.0 wildcard bind
   because Windows would capture traffic on the tunnel interface, preventing
   services like `tailscale serve` from terminating TLS on the same port.
   Otherwise attempts 0.0.0.0 first for full connectivity, falling back to
   127.0.0.1 + LAN IP connector.
   Returns [server bind-host] on success, nil if all fail."
  [handler port lan-ip]
  (if (and windows? (has-tunnel-interfaces?))
    ;; On Windows with tunnel interfaces, bind only to specific interfaces
    ;; to avoid stealing traffic from Tailscale/WireGuard virtual interfaces.
    (do (logger/debug logger-tag "Tunnel interface detected on Windows, binding to specific interfaces only")
        (start-on-specific-interfaces handler port lan-ip))
    ;; Default: try 0.0.0.0 first, fall back to specific interfaces
    (if-let [server (try-start-jetty handler port "0.0.0.0")]
      [server "0.0.0.0"]
      (start-on-specific-interfaces handler port lan-ip))))

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
