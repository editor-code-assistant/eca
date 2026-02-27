(ns eca.client-test-helpers
  (:require
   [cheshire.core :as json]
   [eca.client-http :as client])
  (:import
   [io.netty.buffer Unpooled]
   [io.netty.handler.codec.http
    DefaultFullHttpResponse
    FullHttpRequest
    HttpHeaders
    HttpResponseStatus
    HttpResponseStatus
    HttpVersion]
   [java.nio.charset StandardCharsets]
   [org.littleshoot.proxy HttpFiltersAdapter HttpFiltersSource ProxyAuthenticator]
   [org.littleshoot.proxy.impl DefaultHttpProxyServer]))

(defn proxy-filters-handler-make
  "Creates a LittleProxy `HttpFiltersSource` that intercepts HTTP requests,
  normalizes them into a simple map, passes them to HANDLER-FN, and converts
  the handler's return value into an HTTP response.

  Request map passed to HANDLER-FN includes:
  :method   - HTTP method as a string
  :uri      - request URI
  :protocol - protocol version
  :headers  - request headers as a map
  :body     - request body; JSON bodies are parsed into maps

  HANDLER-FN should return a map with:
  :status   - HTTP response status code (default 200)
  :body     - response body; maps are automatically encoded as JSON
  :headers  - optional map of response headers

  Buffer limits are set so the proxy can accept request bodies up to
  32 KB and does not buffer responses."
  ^HttpFiltersSource [handler-fn]
  (proxy [HttpFiltersSource] []
    (filterRequest [original-request ctx]
      (proxy [HttpFiltersAdapter] [original-request]
        (proxyToServerRequest [http-obj]
          (let [{:keys [response ^String error]}
                (try
                  (if (instance? FullHttpRequest http-obj)
                    (let [req ^FullHttpRequest http-obj
                          headers-map (into {}
                                            (for [^java.util.Map$Entry h (.entries (.headers req))]
                                              [(.getKey h) (.getValue h)]))
                          content-type (get headers-map "Content-Type")
                          body-str (.toString (.content req) StandardCharsets/UTF_8)
                          parsed-body (if (and content-type
                                               (.contains ^String content-type "application/json"))
                                        (json/parse-string body-str true)
                                        body-str)
                          body-map {:method (.name (.getMethod req))
                                    :uri (.getUri req)
                                    :protocol (str (.getProtocolVersion req))
                                    :headers headers-map
                                    :body parsed-body}
                          response-data (handler-fn body-map)
                          status (get response-data :status)
                          response-body (get response-data :body)
                          response-headers (get response-data :headers)
                          response-body-map? (map? response-body)
                          response-body-str (if response-body-map?
                                              (json/generate-string response-body)
                                              (str response-body))
                          response-headers (cond-> response-headers
                                             response-body-map?
                                             (assoc "Content-Type" "application/json"))
                          content (Unpooled/copiedBuffer response-body-str StandardCharsets/UTF_8)
                          response (DefaultFullHttpResponse.
                                    HttpVersion/HTTP_1_1
                                    (HttpResponseStatus/valueOf status)
                                    content)]
                      ;; Set headers
                      (doseq [[^String k v] response-headers]
                        (.set ^HttpHeaders (.headers response) k v))
                      (.set (.headers response) "Content-Length" (str (.readableBytes content)))
                      {:response response})

                    ;; error
                    {:error "Eca test proxy handler error: expected FullHttpRequest, check (getMaximumRequestBufferSizeInBytes) value"})
                  (catch Exception e
                    {:error (str "Eca test proxy handler error: " e)}))]

            (if error
              (let [content (Unpooled/copiedBuffer error StandardCharsets/UTF_8)
                    response (DefaultFullHttpResponse.
                              HttpVersion/HTTP_1_1
                              HttpResponseStatus/BAD_REQUEST ;; 400
                              content)]
                (.set (.headers response) "Content-Length" (str (.readableBytes content)))
                (.set (.headers response) "Connection" "close")
                response)

              ;; success
              response)))))

    (getMaximumRequestBufferSizeInBytes [] (* 1024 32))
    (getMaximumResponseBufferSizeInBytes [] 0)))

(defn proxy-authenticator-make
  "Creates a LittleProxy ProxyAuthenticator that returns true if the
  provided USERNAME and PASSWORD match those of the incoming request,
  allowing it to authenticate."
  [username password]
  (proxy [ProxyAuthenticator] []
    (authenticate [user pass]
      (and (= user username)
           (= pass password)))
    (getRealm [] "EcaLittleProxyTestRealm")
    (authScheme [_] "Basic")))

(defn little-proxy-interceptor-make
  "Creates and starts a LittleProxy server that routes all intercepted requests to HANDLER-FN.

  The HANDLER-FN input and output format is defined by `proxy-filters-handler-make`.

  Optional authentication can be enforced by providing USERNAME and PASSWORD.

  Returns a map with:
  :px    - the running proxy server instance
  :host  - the hostname the proxy is listening on
  :port  - the port assigned to the proxy"
  ([handler-fn]
   (little-proxy-interceptor-make handler-fn nil nil))
  ([handler-fn username password]
   (let [px (-> (DefaultHttpProxyServer/bootstrap)
                (.withPort 0) ;; 0 pick random port
                (.withFiltersSource (proxy-filters-handler-make handler-fn))
                (cond-> username (.withProxyAuthenticator (proxy-authenticator-make username password)))
                (.start))]
     {:px px
      :host (.getHostString (.getListenAddress px))
      :port (.getPort (.getListenAddress px))})))

(def ^:dynamic ^String *proxy-host*
  "Dynamic var for the host of a temporary proxy from `with-proxy`."
  nil)

(def ^:dynamic *proxy-port*
  "Dynamic var for the port of a temporary proxy from `with-proxy`."
  nil)

(defmacro with-proxy
  "Runs BODY with a temporary LittleProxy server active at a random local port.

  Starts a proxy using the provided request HANDLER-FN and optional
  OPTS, making its host and port available via the dynamic vars
  `*proxy-host*` and `*proxy-port*`. Ensures the proxy is shut down
  after BODY executes.

  HANDLER-FN is a function that receives a normalized request map and
  returns a response map. The request map includes:
    :method   - HTTP method as a string
    :uri      - request URI
    :protocol - protocol version
    :headers  - request headers as a map
    :body     - request body, JSON is automatically parsed into a map if applicable

  OPTS may include:
  :user - if set, the proxy requires this username for authentication
  :pass - if set, the proxy requires this password for authentication"
  [opts handler-fn & body]
  (let [{:keys [user pass]} opts]
    `(let [prx#  (little-proxy-interceptor-make ~handler-fn ~user ~pass)
           prx-host# (:host prx#)
           prx-port# (:port prx#)]
       (try
         (binding [*proxy-host* prx-host#
                   *proxy-port* prx-port#]
           ~@body)
         (finally
           (.abort ^DefaultHttpProxyServer (:px prx#)))))))

(def ^:dynamic *http-client-captures*
  "A record of all `eca.client-http/merge-with-global-http-client` merge
  requests results done during the call to `with-client-proxied`
  call."
  nil)

(defmacro with-client-proxied
  "Runs BODY with a temporary LittleProxy server on a random local port,
  configuring `eca.client-http/*hato-http-client*` so all Hato
  requests in BODY are routed through the proxy. The proxy is started
  using the provided HANDLER-FN and optional OPTS, and is always shut
  down afterward, with `eca.client-http/*hato-http-client*` reset to
  nil.

  During execution, any calls to `eca.client-http/merge-with-global-http-client`
  are recorded in `*http-client-captures*` as a sequence of merged options maps.

  Accepts all OPTS supported by `hato.client-http/build-http-client`.

  HANDLER-FN is a function that receives a normalized request map and
  returns a response map. The request map includes:
    :method   - HTTP method as a string
    :uri      - request URI
    :protocol - protocol version
    :headers  - request headers as a map
    :body     - request body, JSON is automatically parsed into a map if applicable"
  [opts handler-fn & body]

  `(with-proxy ~opts
     ~handler-fn

     (let [client# (client/hato-client-make (assoc ~opts :eca.client-http/proxy-http {:host *proxy-host* :port *proxy-port*}))]
       (try
         (alter-var-root #'client/*hato-http-client* (constantly client#))
         (let [merges*# (atom [])
               merge-fn# client/merge-with-global-http-client]
           (with-redefs [client/merge-with-global-http-client
                         (fn [& args#]
                           (let [result# (apply merge-fn# args#)]
                             (swap! merges*# conj result#)
                             result#))]
             (binding [*http-client-captures* merges*#]
               ~@body)))
         (finally
           (alter-var-root #'client/*hato-http-client* (constantly nil)))))))

