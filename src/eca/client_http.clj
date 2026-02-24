(ns eca.client-http
  "Support for the  HTTP client to make outbound requests."
  (:require
   [eca.logger :as logger]
   [eca.network :as network])
  (:import
   [java.io IOException]
   [java.net
    InetSocketAddress
    Proxy
    Proxy$Type
    ProxySelector
    URI]
   [java.util.concurrent Executors]))

(set! *warn-on-reflection* true)

(defn hato-client-make
  "Builds an options map for creating a Hato HTTP client.

  Accepts all OPTS supported by `hato.client-http/build-http-client`.

  It also supports optional specialized keys
  `:eca.client-http/proxy-http` and `:eca.client-http/proxy-https`,
  which define proxy servers for outgoing HTTP and HTTPS requests;
  proxy credentials are used from either proxy when provided, and if
  both are present, they must be identical, otherwise an exception is
  thrown.

  Each proxy map includes:
  :host     - the proxy host
  :port     - the proxy port
  :username - optional username for proxy authentication
  :password - optional password for proxy authentication

  Returns a map suitable for passing to `hato.client-http/build-http-client`."
  [{:eca.client-http/keys [proxy-http proxy-https] :as opts}]
  (logger/debug "hato-client-config: " opts)
  (let [{http-host :host http-port :port http-user :username http-pass :password} proxy-http
        {https-host :host https-port :port https-user :username https-pass :password} proxy-https
        opts (apply dissoc opts [:eca.client-http/proxy-http :eca.client-http/proxy-https])
        proxy-http-addr (and http-host http-port (InetSocketAddress. ^String http-host ^int http-port))
        proxy-https-addr (and https-host https-port (InetSocketAddress. ^String https-host ^int https-port))
        proxy-selector (when (or proxy-http-addr proxy-https-addr)
                         (proxy [ProxySelector] []
                           (select [^URI uri]
                             (let [scheme (.getScheme uri)]
                               (cond
                                 (and proxy-http-addr (= scheme "http"))
                                 [(Proxy. Proxy$Type/HTTP proxy-http-addr)]
                                 (and proxy-https-addr (= scheme "https"))
                                 [(Proxy. Proxy$Type/HTTP proxy-https-addr)]
                                 :else
                                 [])))
                           (connectFailed [_ _ ^IOException e]
                             (.printStackTrace e))))
        http-creds  (when (and http-user http-pass)
                      {:user http-user :pass http-pass})
        https-creds (when (and https-user https-pass)
                      {:user https-user :pass https-pass})
        proxy-creds (cond
                      (and http-creds https-creds)
                      (if (= http-creds https-creds)
                        http-creds
                        (throw (ex-info "HTTP and HTTPS proxy credentials must be identical"
                                        {:http http-creds
                                         :https https-creds})))
                      http-creds  http-creds
                      https-creds https-creds
                      :else       nil)]
    (cond-> opts
      proxy-selector
      (assoc :proxy proxy-selector)
      proxy-creds
      (assoc :authenticator proxy-creds))))

(def ^:private shared-executor*
  (delay (Executors/newCachedThreadPool)))

(def ^:dynamic *hato-http-client*
  "Global Hato HTTP client used throughout the application for making
  HTTP requests"
  nil)

(defn merge-with-global-http-client
  "Merge the given Hato HTTP client options with the global
  `*hato-http-client*` and return the result."
  [http-client]
  (merge *hato-http-client* http-client))

(defn merge-llm-headers
  "Merge the given headers with the default LLM headers.
  Request-specific headers take precedence over defaults."
  [headers]
  (merge {"x-llm-application-name" "eca"} headers))

(defn hato-client-global-setup!
  "Builds the Hato HTTP client used throughout the application for making
  HTTP requests from HATO-OPTS and stores it in
  `eca.client-http/*hato-http-client*`.

  HATO-OPTS are the same options accepted by Hato's
  `hato.client-http/build-http-client`. In addition, if HTTP or HTTPS proxy
  settings are present in the environment
  variables (`http_proxy`/`HTTP_PROXY` and `https_proxy`/`HTTPS_PROXY`),
  the corresponding proxy configuration is added to the build.

  When a custom SSL context has been set up via `eca.network/setup!`,
  it is included so that custom CA certificates and mTLS are honoured."
  [hato-opts]
  (let [{:keys [http https] :as _env-proxies} (network/env-proxy-urls-parse)
        ssl-ctx network/*ssl-context*
        opts (cond-> (assoc hato-opts :executor ^java.util.concurrent.ExecutorService @shared-executor*)
               http
               (assoc :eca.client-http/proxy-http http)
               https
               (assoc :eca.client-http/proxy-https https)
               ssl-ctx
               (assoc :ssl-context ssl-ctx))
        hato-http-client (hato-client-make opts)]
    (alter-var-root #'*hato-http-client* (constantly hato-http-client))))
