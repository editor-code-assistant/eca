(ns eca.oauth
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.logger :as logger]
   [selmer.parser :as selmer]
   [hato.client :as http]
   [ring.adapter.jetty :as jetty]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.middleware.params :refer [wrap-params]]
   [ring.util.codec :as ring.util]
   [ring.util.response :as response])
  (:import
   [java.nio.charset StandardCharsets]
   [java.security KeyStore MessageDigest SecureRandom]
   [java.util Base64]
   [javax.net.ssl KeyManagerFactory SSLContext]
   [org.eclipse.jetty.server Server]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[OAUTH]")

(defonce ^:private oauth-server-by-port* (atom {}))

(def eca-client-id "Ov23liT613uPA2ydLTa8")

(def ^:private logo-svg
  (delay
    (-> (slurp (io/resource "logo.svg"))
        ;; Change fill color to white for display on colored background
        (string/replace #"fill:#f8f8f8" "fill:#ffffff"))))

(defn ^:private render-oauth-page
  "Render the OAuth HTML page with success or error state."
  [{:keys [success? error-code error-description error-uri]}]
  (selmer/render-file "webpages/oauth.html"
                      {:success success?
                       :error (not success?)
                       :error-code (or error-code "unknown_error")
                       :error-description error-description
                       :error-uri error-uri
                       :logo-svg @logo-svg}))

(defn ^:private url->base-url
  "Extract the base URL (scheme + host + port) from a full URL.
   E.g., 'https://api.example.com/v1/mcp' -> 'https://api.example.com'"
  [^String url]
  (let [uri (java.net.URI. url)]
    (str (.getScheme uri) "://" (.getHost uri)
         (when (pos? (.getPort uri))
           (str ":" (.getPort uri))))))

(defn url-without-query
  "Strip query string and fragment from a URL, preserving scheme, authority and path.
   E.g., 'https://api.example.com/v1/mcp?token=x#frag' -> 'https://api.example.com/v1/mcp'
   Returns nil for nil input."
  [^String url]
  (when url
    (let [uri (java.net.URI. url)]
      (str (.getScheme uri) "://" (.getAuthority uri) (.getPath uri)))))

(defn get-free-port []
  (let [socket (java.net.ServerSocket. 0)
        port (.getLocalPort socket)]
    (.close socket)
    port))

(defn ^:private parse-www-authenticate
  "Parse WWW-Authenticate header to extract OAuth parameters.
   Returns a map with :realm, :error, :error_description, :resource_metadata if present."
  [header-value]
  (when (and header-value (string/starts-with? (string/lower-case header-value) "bearer"))
    (let [params-str (string/trim (subs header-value (count "Bearer")))
          ;; Parse key="value" pairs, handling commas within values
          param-pattern #"(\w+)=\"([^\"]*)\""
          matches (re-seq param-pattern params-str)]
      (into {:type :bearer}
            (for [[_ k v] matches]
              [(keyword k) v])))))

(defn ^:private rand-bytes
  "Returns a random byte array of the specified size."
  [size]
  (let [seed (byte-array size)]
    (.nextBytes (SecureRandom.) seed)
    seed))

(defn <-base64 ^String [^String s]
  (String. (.decode (Base64/getDecoder) s)))

(defn ^:private ->base64 [^bytes bs]
  (.encodeToString (Base64/getEncoder) bs))

(defn ^:private ->base64url [base64-str]
  (-> base64-str (string/replace "+" "-") (string/replace "/" "_")))

(defn ^:private str->sha256 [^String s]
  (-> (MessageDigest/getInstance "SHA-256")
      (.digest (.getBytes s StandardCharsets/UTF_8))))

(defn ^:private random-verifier []
  (->base64url (->base64 (rand-bytes 63))))

(defn generate-pkce []
  (let [verifier (random-verifier)]
    {:verifier verifier
     :challenge (-> verifier str->sha256 ->base64 ->base64url (string/replace "=" ""))}))

(defn ^:private oauth-handler [request on-success on-error]
  ;; The HTML response is built and returned synchronously, but on-success/on-error
  ;; run on a background thread after a brief delay so Jetty has time to flush the
  ;; page to the browser. This lets callers safely call stop-oauth-server! from
  ;; their callbacks without racing the response write.
  (let [{:keys [code error error_description error_uri state]} (:params request)
        defer-callback! (fn [f]
                          (future
                            (Thread/sleep 500)
                            (try (f)
                                 (catch Exception e
                                   (logger/error logger-tag "OAuth callback error:" (ex-message e))))))]
    (if code
      (do
        (defer-callback! #(on-success {:code code :state state}))
        (-> (response/response (render-oauth-page {:success? true}))
            (response/content-type "text/html")))
      (do
        (defer-callback! #(on-error error))
        (-> (response/response (render-oauth-page {:success? false
                                                   :error-code error
                                                   :error-description error_description
                                                   :error-uri error_uri}))
            (response/content-type "text/html"))))))

(defn ^:private successful-json-body
  "Extracts :body from an HTTP response only when status is 2xx and body is a map.
   Hato with {:as :json :coerce :unexceptional} leaves :body as a raw String for
   non-2xx responses, which would break callers expecting a map."
  [response]
  (when (and response (<= 200 (:status response) 299))
    (let [body (:body response)]
      (when (map? body) body))))

(defn ^:private valid-auth-challenge?
  "A 401 is only a valid OAuth challenge if it includes a www-authenticate header.
   Some proxies (e.g. istio-envoy) return 401 for unsupported methods like HEAD
   without actually requiring authentication."
  [response]
  (and (= 401 (:status response))
       (some? (get (:headers response) "www-authenticate"))))

(defn ^:private probe-auth
  "Probe the MCP server for a 401 auth challenge with www-authenticate header.
   Tries HEAD first; falls back to POST if HEAD doesn't return a valid challenge
   (some servers like Glean don't support HEAD, some proxies like istio-envoy
   return 401 on HEAD without actually requiring auth)."
  [^String url]
  (let [head-response (http/head url {:timeout 10000
                                      :throw-exceptions? false})]
    (if (valid-auth-challenge? head-response)
      head-response
      (let [post-response (http/post url {:timeout 10000
                                          :throw-exceptions? false
                                          :headers {"Content-Type" "application/json"
                                                    "Accept" "application/json, text/event-stream"}
                                          :body "{}"})]
        (when (valid-auth-challenge? post-response)
          post-response)))))

(defn ^:private fetch-json
  "GET a URL and return parsed JSON body only on 2xx, nil otherwise."
  [^String url]
  (successful-json-body
   (http/get url {:timeout 10000
                  :throw-exceptions? false
                  :as :json})))

(defn ^:private discover-resource-metadata
  "Discover Protected Resource Metadata (RFC 9728) following MCP spec priority:
   1. resource_metadata URL from WWW-Authenticate header
   2. /.well-known/oauth-protected-resource on the base URL
   3. /.well-known/oauth-authorization-server on the base URL (legacy)
   4. /.well-known/openid-configuration on the base URL (OIDC)
   5. /oidc/.well-known/openid-configuration (common provider convention)"
  [www-authenticate ^String base-url]
  (or (when-let [rm-url (:resource_metadata www-authenticate)]
        (fetch-json rm-url))
      (fetch-json (str base-url "/.well-known/oauth-protected-resource"))
      (fetch-json (str base-url "/.well-known/oauth-authorization-server"))
      (fetch-json (str base-url "/.well-known/openid-configuration"))
      (fetch-json (str base-url "/oidc/.well-known/openid-configuration"))))

(defn ^:private discover-auth-server-metadata
  "Discover Authorization Server Metadata following MCP spec:
   Try RFC 8414 (/.well-known/oauth-authorization-server) first,
   then OIDC Discovery 1.0 (/.well-known/openid-configuration)."
  [^String auth-server]
  (let [uri (java.net.URI. auth-server)
        base (str (.getScheme uri) "://" (.getAuthority uri))
        path (.getPath uri)
        path (if (= "/" path) "" path)]
    (or (fetch-json (str base "/.well-known/oauth-authorization-server" path))
        (fetch-json (str base "/.well-known/openid-configuration")))))

(defn oauth-info
  "Perform OAuth discovery for the given MCP server URL.
   Optional `configured-client-id` skips dynamic client registration when provided.
   Optional `configured-client-secret` enables confidential OAuth (e.g. Slack MCP).
   Optional `configured-oauth-port` uses a fixed port for the OAuth callback (required
   by providers like Slack that mandate pre-registered redirect URIs)."
  ([^String url] (oauth-info url nil nil))
  ([^String url configured-client-id] (oauth-info url configured-client-id nil))
  ([^String url configured-client-id configured-client-secret]
   (oauth-info url configured-client-id configured-client-secret nil))
  ([^String url configured-client-id configured-client-secret configured-oauth-port]
   (let [base-url (url->base-url url)
         auth-response (probe-auth url)]
     (when-let [headers (:headers auth-response)]
       (let [ssl? (some? configured-oauth-port)
             callback-port (if configured-oauth-port
                             (int configured-oauth-port)
                             (get-free-port))
             scheme (if ssl? "https" "http")
             redirect-uri (format "%s://localhost:%s/auth/callback" scheme callback-port)
             www-authenticate (some-> (get headers "www-authenticate") parse-www-authenticate)
             ;; Step 1: Discover resource/auth metadata (PRM per RFC 9728, then legacy fallback)
             first-meta (discover-resource-metadata www-authenticate base-url)
             auth-server (first (:authorization_servers first-meta))
             ;; Step 2: If PRM returned authorization_servers but no token_endpoint,
             ;; fetch AS metadata via RFC 8414 or OIDC Discovery 1.0
             auth-server-meta (when (and auth-server (not (:token_endpoint first-meta)))
                                (discover-auth-server-metadata auth-server))
             ;; Merge: auth server metadata takes precedence, PRM as fallback
             meta (merge first-meta auth-server-meta)]
         ;; Only proceed if we discovered a usable authorization_endpoint
         (when (:authorization_endpoint meta)
           (let [base-auth-endpoint (:authorization_endpoint meta)
                 ;; Skip DCR when a client-id is pre-configured
                 new-client-id (when-not configured-client-id
                                 (when-let [reg-endpoint (:registration_endpoint meta)]
                                   (let [auth-method (if configured-client-secret
                                                       "client_secret_post"
                                                       "none")
                                         res (http/post
                                              reg-endpoint
                                              {:timeout 10000
                                               :as :json
                                               :throw-exceptions? false
                                               :headers {"Content-Type" "application/json"}
                                               :body (json/generate-string
                                                      {:redirect_uris [redirect-uri]
                                                       :token_endpoint_auth_method auth-method
                                                       :grant_types ["authorization_code" "refresh_token"]
                                                       :response_types ["code"]
                                                       :client_name "ECA (Editor Code Assistant)"
                                                       :client_uri "http://github.com/editor-code-assistant/eca"
                                                       :client_id eca-client-id
                                                       :client_id_issued_at (.getEpochSecond (java.time.Instant/now))})})]
                                     (when (<= 200 (:status res) 299)
                                       (:client_id (:body res))))))
                 {:keys [challenge verifier]} (generate-pkce)
                 client-id (or configured-client-id new-client-id eca-client-id)
                 scope (when-let [scopes (:scopes_supported meta)]
                         (if (coll? scopes)
                           (string/join " " scopes)
                           scopes))
                 query-params (ring.util/form-encode
                               (cond-> {:response_type "code"
                                        :client_id client-id
                                        :code_challenge_method "S256"
                                        :code_challenge challenge
                                        :state verifier
                                        :redirect_uri redirect-uri
                                        :resource url}
                                 scope (assoc :scope scope)))]
            (cond-> {:callback-port callback-port
                     :token-endpoint (or (:token_endpoint meta)
                                         (str auth-server "/access_token"))
                     :verifier verifier
                     :client-id client-id
                     :redirect-uri redirect-uri
                     :resource url
                     :authorization-endpoint (str base-auth-endpoint "?" query-params)}
              ssl? (assoc :ssl? true)
              configured-client-secret (assoc :client-secret configured-client-secret)))))))))

(comment
  (oauth-info "https://mcp.atlassian.com/v1/sse")
  (oauth-info "https://mcp.miro.com/")
  (oauth-info "https://api.githubcopilot.com/mcp/"))

(defn ^:private build-ssl-context
  "Loads the bundled localhost PKCS12 keystore from classpath and builds
   an SSLContext. Avoids file I/O and bypasses ring-jetty's SSL config
   path (which uses Jetty reflection that fails in native images)."
  ^SSLContext []
  (let [res (io/resource "tls/localhost.p12")]
    (when-not res
      (throw (ex-info "Bundled localhost keystore not found on classpath"
                      {:resource "tls/localhost.p12"})))
    (let [password (.toCharArray "ecalocal")
          ks (doto (KeyStore/getInstance "PKCS12")
               (.load (io/input-stream res) password))
          kmf (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
                (.init ks password))
          ctx (doto (SSLContext/getInstance "TLS")
                (.init (.getKeyManagers kmf) nil nil))]
      ctx)))

(defn start-oauth-server!
  "Start local server on port to handle OAuth redirect.
   When :ssl? is true, starts HTTPS using the bundled localhost certificate."
  [{:keys [on-error on-success port ssl?]}]
  (when-not (get @oauth-server-by-port* port)
    (try
      (let [handler (-> oauth-handler
                        wrap-keyword-params
                        wrap-params)
            request-handler (fn [request]
                              (if (= "/auth/callback" (:uri request))
                                (handler request on-success on-error)
                                (-> (response/response "404 Not Found")
                                    (response/status 404))))
            jetty-opts (if ssl?
                         {:ssl? true
                          :ssl-port (or port (get-free-port))
                          :http? false
                          :ssl-context (build-ssl-context)
                          :join? false}
                         {:port (or port (get-free-port))
                          :join? false})
            server (jetty/run-jetty request-handler jetty-opts)
            scheme (if ssl? "https" "http")]
        (swap! oauth-server-by-port* assoc port server)
        (logger/info logger-tag (str "OAuth server started on " scheme "://localhost:" port))
        {:server server
         :port port})
      (catch java.net.BindException _
        (logger/error logger-tag (format "Port %d is already in use — is another ECA instance running?" port))
        nil))))

(defn stop-oauth-server!
  "Stop the local OAuth server"
  [port]
  (when-let [^Server server (get @oauth-server-by-port* port)]
    (.stop server)
    (swap! oauth-server-by-port* dissoc port)
    (logger/info logger-tag "OAuth server stopped")))

(defn ^:private parse-body
  "Attempt to parse body as JSON, then URL-encoded, falling back to raw string."
  [^String body-str]
  (cond
    (nil? body-str) nil
    (string/blank? body-str) nil
    :else
    (or
     ;; Try JSON first (starts with { or [)
     (when (or (string/starts-with? (string/trim body-str) "{")
               (string/starts-with? (string/trim body-str) "["))
       (try
         (json/parse-string body-str true)
         (catch Exception _ nil)))
     ;; Try URL-encoded format (key=value&key2=value2)
     (when (and (string/includes? body-str "=")
                (not (string/includes? body-str "\n")))
       (try
         (-> (ring.util/form-decode body-str)
             (update-keys keyword))
         (catch Exception _ nil)))
     ;; Fallback to raw error
     {:raw_error body-str})))

(defn authorize-token! [{:keys [token-endpoint verifier client-id client-secret redirect-uri resource]} code]
  (let [{:keys [status body]} (http/post
                               token-endpoint
                               {:headers {"Content-Type" "application/x-www-form-urlencoded"
                                          "Accept" "application/json"}
                                :body (ring.util/form-encode
                                       (cond-> {:grant_type "authorization_code"
                                                :client_id client-id
                                                :code code
                                                :code_verifier verifier
                                                :redirect_uri redirect-uri}
                                         client-secret (assoc :client_secret client-secret)
                                         resource (assoc :resource resource)))
                                :throw-exceptions? false
                                :as :stream})
        body-str (when body (slurp body))
        parsed-body (parse-body body-str)]
    (logger/debug logger-tag (format "Token response status=%d body=%s" status (pr-str parsed-body)))
    (cond
      ;; Success case
      (and (= 200 status) (:access_token parsed-body))
      {:refresh-token (:refresh_token parsed-body)
       :access-token (:access_token parsed-body)
       :expires-at (+ (quot (System/currentTimeMillis) 1000)
                      (or (:expires_in parsed-body) 3600))}

      ;; Error in response body (some OAuth servers return 200 with error in body)
      (:error parsed-body)
      (throw (ex-info (format "OAuth error: %s - %s"
                              (:error parsed-body)
                              (or (:error_description parsed-body) "No description"))
                      {:status status
                       :error (:error parsed-body)
                       :error-description (:error_description parsed-body)
                       :body parsed-body}))

      ;; Non-200 status
      (not= 200 status)
      (throw (ex-info (format "OAuth token exchange failed (HTTP %d): %s" status (or body-str "No response body"))
                      {:status status
                       :body parsed-body}))

      ;; 200 but no access_token
      :else
      (throw (ex-info (format "OAuth response missing access_token: %s" (pr-str parsed-body))
                      {:status status
                       :body parsed-body})))))

(defn refresh-token!
  "Refresh an OAuth access token using a refresh token.
   Returns {:access-token :refresh-token :expires-at} on success, nil if refresh fails."
  [token-endpoint client-id refresh-token & {:keys [client-secret resource]}]
  (try
    (let [{:keys [status body]} (http/post
                                 token-endpoint
                                 {:headers {"Content-Type" "application/x-www-form-urlencoded"
                                            "Accept" "application/json"}
                                  :body (ring.util/form-encode
                                         (cond-> {:grant_type "refresh_token"
                                                  :client_id client-id
                                                  :refresh_token refresh-token}
                                           client-secret (assoc :client_secret client-secret)
                                           resource (assoc :resource resource)))
                                  :throw-exceptions? false
                                  :as :stream})
          body-str (when body (slurp body))
          parsed-body (parse-body body-str)]
      (logger/debug logger-tag (format "Refresh token response status=%d body=%s" status (pr-str parsed-body)))
      (when (and (= 200 status) (:access_token parsed-body))
        {:refresh-token (or (:refresh_token parsed-body) refresh-token)
         :access-token (:access_token parsed-body)
         :expires-at (+ (quot (System/currentTimeMillis) 1000)
                        (or (:expires_in parsed-body) 3600))}))
    (catch Exception e
      (logger/warn logger-tag (format "Failed to refresh token: %s" (.getMessage e)))
      nil)))
