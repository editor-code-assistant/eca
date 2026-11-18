(ns eca.modules.mcp
  "The `:eca/mcp` module. MCP servers: their tools, prompts, resources and
   instructions."
  (:require
   [cheshire.core :as json]
   [cheshire.factory :as json.factory]
   [clojure.java.browse :as browse]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.cache :as cache]
   [eca.config :as config]
   [eca.db.global-cache :as db.global-cache]
   [eca.diff :as diff]
   [eca.features.prompt :as f.prompt]
   [eca.features.tools :as f.tools]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.metrics :as metrics]
   [eca.modules :as modules]
   [eca.network :as network]
   [eca.oauth :as oauth]
   [eca.shared :as shared :refer [multi-str]]
   [hato.client :as http]
   [plumcp.core.api.capability :as pcap]
   [plumcp.core.api.entity-gen :as peg]
   [plumcp.core.api.entity-support :as pes]
   [plumcp.core.api.mcp-client :as pmc]
   [plumcp.core.client.client-support :as pcs]
   [plumcp.core.client.http-client-transport :as phct]
   [plumcp.core.client.stdio-client-transport :as psct]
   [plumcp.core.protocol :as pp]
   [plumcp.core.schema.schema-defs :as psd]
   [plumcp.core.support.http-client :as phc]
   [rewrite-json.core :as rj]
   [ring.util.codec :as ring.util])
  (:import
   [java.io IOException]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[MCP]")

(defn ^:private traffic-log
  "Log MCP traffic message at DEBUG level with direction arrow."
  [direction msg]
  (logger/debug logger-tag (str direction " " msg)))

(def ^:private mcp-traffic-logger
  "Routes plumcp MCP traffic through ECA's Logback logger at DEBUG level.
   Visible with --log-level debug, suppressed at default INFO."
  (reify pp/ITrafficLogger
    (log-http-request [_ req]
      (traffic-log "-->" (format "HTTP %s %s" (-> req :request-method name .toUpperCase) (or (:uri req) "?"))))
    (log-http-response [_ resp]
      (traffic-log "<--" (format "HTTP %d" (:status resp))))
    (log-http-failure [_ failure]
      (traffic-log "<--" (str "HTTP failure: " failure)))
    (log-incoming-jsonrpc-request [_ req]
      (traffic-log "<--" (format "request %s %s" (or (:id req) "#") (:method req))))
    (log-outgoing-jsonrpc-request [_ req]
      (traffic-log "-->" (format "request %s %s" (or (:id req) "#") (:method req))))
    (log-incoming-jsonrpc-success [_ id result]
      (traffic-log "<--" (format "success %s %s" (or id "#") result)))
    (log-outgoing-jsonrpc-success [_ id result]
      (traffic-log "-->" (format "success %s %s" (or id "#") result)))
    (log-incoming-jsonrpc-failure [_ id error]
      (traffic-log "<--" (format "error %s code=%d" (or id "#") (get-in error [:error :code]))))
    (log-outgoing-jsonrpc-failure [_ id error]
      (traffic-log "-->" (format "error %s code=%d" (or id "#") (get-in error [:error :code]))))
    (log-incoming-jsonrpc-notification [_ notif]
      (traffic-log "<--" (str "notification " (:method notif))))
    (log-outgoing-jsonrpc-notification [_ notif]
      (traffic-log "-->" (str "notification " (:method notif))))
    (log-mcpcall-failure [_ error]
      (traffic-log "<--" (str "call failure: " error)))
    (log-mcp-sse-message [_ msg]
      (traffic-log "<--" (str "SSE " msg)))))

(def ^:private init-threads*
  "Tracks in-flight MCP server initialization threads (server-name → Thread)
   so they can be interrupted during shutdown."
  (atom {}))

(def ^:private stopped-init-threads*
  "The init threads `stop-server!` or `shutdown!` stopped: their server is no
   longer theirs, so they don't mark it failed nor tell clients."
  (atom #{}))

(defn ^:private register-init-thread! [server-name ^Thread thread]
  (swap! init-threads* assoc server-name thread))

(defn ^:private deregister-init-thread!
  "Removes `thread` as `server-name`'s init thread, unless another one took
   its place."
  [server-name thread]
  (swap! init-threads* (fn [threads]
                         (cond-> threads
                           (identical? thread (get threads server-name)) (dissoc server-name)))))

(defn ^:private stop-init-thread!
  "Stops `server-name`'s init thread `thread`: it's no longer the server's,
   then it's interrupted."
  [server-name ^Thread thread]
  (swap! stopped-init-threads* conj thread)
  (deregister-init-thread! server-name thread)
  (.interrupt thread))

(defn ^:private init-stopped?
  "Whether the init running on this thread was stopped."
  []
  (contains? @stopped-init-threads* (Thread/currentThread)))

(defn ^:private interrupt-init-threads!
  "Interrupt all in-flight init threads to unblock stuck startups."
  []
  (doseq [[server-name ^Thread thread] @init-threads*]
    (logger/info logger-tag (format "Interrupting init thread for server '%s'" server-name))
    (stop-init-thread! server-name thread)))

(defmacro ^:private with-init-thread
  "Registers the current thread in `init-threads*` for the given server name,
  executes body, and deregisters in a finally block. Allows `stop-server!` and
  `shutdown!` to interrupt in-progress initializations."
  [server-name & body]
  `(let [name# ~server-name
         thread# (Thread/currentThread)]
     (register-init-thread! name# thread#)
     (try
       ~@body
       (finally
         (deregister-init-thread! name# thread#)
         (swap! stopped-init-threads* disj thread#)))))

(def ^:private env-var-regex
  #"\$(\w+)|\$\{([^}]+)\}")

(defn ^:private replace-env-vars [s]
  (let [env (System/getenv)]
    (string/replace s
                    env-var-regex
                    (fn [[_ var1 var2]]
                      (or (get env (or var1 var2))
                          (str "$" var1)
                          (str "${" var2 "}"))))))

(defn ^:private mcp-auth-key
  "Derive the `:mcp-auth` storage key for a server.

  Tokens are namespaced so distinct projects don't clobber each other while
  still sharing by default. Controlled by the server's `:authScope`:
  - nil / \"global\" (default): bare server name, so a single token is shared
    across every project (and back-compat with previously stored tokens).
  - \"workspace\": scoped to the current workspace folder set.
  - any other value: a named bucket shared across every project that uses it.

  The value is resolved like any other config string, so the full
  dynamic-string interpolation (`${env:...}`, `${file:...}`, `${cmd:...}`, ...)
  already ran at config-load time; `replace-env-vars` here additionally expands
  bare `$VAR` forms, matching how `url`/`headers` are treated.

  The server name always prefixes the composite key so two different servers
  never share a token even under the same scope label."
  [server-name server-config db]
  (let [scope (some-> (:authScope server-config) replace-env-vars string/trim not-empty)]
    (cond
      (or (nil? scope) (= "global" scope))
      server-name

      (= "workspace" scope)
      (str (name server-name) "#ws:"
           (cache/workspaces-hash (:workspace-folders db) shared/uri->filename))

      :else
      (str (name server-name) "#scope:" scope))))

(defn ^:private with-serialized-sends
  "Wraps a client transport so concurrent messages are written to the server one at a time."
  [transport]
  (let [lock (Object.)]
    (reify pp/IClientTransport
      (client-transport-info [_] (pp/client-transport-info transport))
      (start-client-transport [_ on-message] (pp/start-client-transport transport on-message))
      (stop-client-transport! [_ force?] (pp/stop-client-transport! transport force?))
      ;; The stdio transport writes the JSON and the newline separately, so
      ;; unsynchronized concurrent sends can interleave and break framing.
      (send-message-to-server [_ message] (locking lock (pp/send-message-to-server transport message)))
      (upon-handshake-success [_ success] (pp/upon-handshake-success transport success)))))

(defn ^:private ->transport [server-name server-config workspaces db*]
  (if (:url server-config)
    ;; HTTP Streamable transport
    (let [needs-reinit?* (atom false)
          url (replace-env-vars (:url server-config))
          config-headers (:headers server-config)
          ssl-ctx network/*ssl-context*
          rm (fn [request]
               (-> request
                   (assoc :timeout-millis 30000)
                   (update :headers merge
                           (into {} (map (fn [[k v]]
                                           [(name k) (replace-env-vars (str v))]))
                                 config-headers))
                   (update :headers merge
                           (when-let [access-token (get-in @db* [:mcp-auth (mcp-auth-key server-name server-config @db*) :access-token])]
                             {"Authorization" (str "Bearer " access-token)}))))
          hc (phc/make-http-client url (cond-> {:request-middleware rm
                                                :timeout-millis 10000}
                                         ssl-ctx (assoc :ssl-context ssl-ctx)))]
      (when (string/includes? url "/sse")
        (logger/warn logger-tag (format "SSE transport is no longer supported for server '%s'. Using Streamable HTTP instead. Consider updating the URL." server-name)))
      (logger/info logger-tag (format "Creating HTTP transport for server '%s' at %s" server-name url))
      {:transport (phct/make-streamable-http-transport hc)
       :http-client hc
       :needs-reinit?* needs-reinit?*})

    ;; STDIO transport
    (let [{:keys [command args env]} server-config
          work-dir (or (some-> workspaces
                               first
                               :uri
                               shared/uri->filename)
                       (config/user-home))]
      {:transport (with-serialized-sends
                    (psct/run-command
                     {:command-tokens (into [(replace-env-vars command)]
                                            (map replace-env-vars)
                                            (or args []))
                      :dir work-dir
                      :env (when env (update-keys env name))
                      :on-stderr-text (fn [msg]
                                        (logger/info logger-tag (format "[%s] %s" server-name msg)))}))
       :needs-reinit?* nil})))

(defn ^:private non-blocking-handler
  "Wraps a notification handler so it runs on a separate thread.
  Prevents STDIO transport deadlock where the reader thread blocks on a
  synchronous fetch request inside a notification handler, making it
  unable to read incoming responses."
  [f]
  (pcs/wrap-initialized-check
   (fn [jsonrpc-notification]
     (future (f jsonrpc-notification)))))

(defonce ^:private progress-listeners* (atom {}))

(defn ^:private on-progress-notification
  "Passes a server progress notification's params to the listener registered for its progress token."
  [jsonrpc-notification]
  (let [params (:params jsonrpc-notification)]
    (when-let [listener (get @progress-listeners* (:progressToken params))]
      (listener params))))

(defn ^:private ->client [name transport init-timeout workspaces
                          {:keys [on-tools-change pending-tools-refresh* heartbeat-seconds]}]
  (let [tools-consumer (fn [tools]
                         (logger/info logger-tag
                                      (format "[%s] Tools list changed, received %d tools"
                                              name (count tools)))
                         (on-tools-change tools))
        client (pmc/make-mcp-client
                {:info (pes/make-info "ECA" (config/eca-version) "Editor Code Assistant")
                 :client-transport transport
                 :heartbeat-seconds (or heartbeat-seconds 0)
                 :primitives {:roots (mapv #(pcap/make-root-item (:uri %)
                                                                 {:name (:name %)})
                                           workspaces)}
                 :traffic-logger mcp-traffic-logger
                 :notification-handlers
                 {;; Uses custom wrapping instead of non-blocking-handler to coordinate
                  ;; a promise that await-pending-tools-refresh can block on.
                  psd/method-notifications-tools-list_changed
                  (pcs/wrap-initialized-check
                   (fn [jsonrpc-notification]
                     (let [p (promise)]
                       (when pending-tools-refresh*
                         (reset! pending-tools-refresh* p))
                       (future
                         (try
                           (pcs/fetch-tools jsonrpc-notification
                                            {:on-tools tools-consumer})
                           (catch Exception e
                             (logger/error logger-tag
                                           (format "[%s] Failed to refresh tools after list_changed: %s"
                                                   name (.getMessage ^Throwable e))))
                           (finally
                             (when pending-tools-refresh*
                               (compare-and-set! pending-tools-refresh* p nil))
                             (deliver p true)))))))

                  psd/method-notifications-resources-list_changed
                  (non-blocking-handler
                   (fn [notification]
                     (pcs/fetch-resources notification)))

                  psd/method-notifications-prompts-list_changed
                  (non-blocking-handler
                   (fn [notification]
                     (pcs/fetch-prompts notification)))

                  psd/method-notifications-progress
                  on-progress-notification

                  psd/method-notifications-message
                  (fn [params]
                    (logger/info logger-tag
                                 (format "[MCP-%s] %s" name (:data params))))}
                 :print-banner? false})]
    (pmc/initialize-and-notify! client
                                {:timeout-millis (* 1000 init-timeout)})
    client))

(defn ^:private ->server [mcp-name server-config status db]
  {:name (name mcp-name)
   :command (:command server-config)
   :args (:args server-config)
   :url (:url server-config)
   :tools (get-in db [:mcp-clients mcp-name :tools])
   :prompts (get-in db [:mcp-clients mcp-name :prompts])
   :resources (get-in db [:mcp-clients mcp-name :resources])
   :instructions (get-in db [:mcp-clients mcp-name :instructions])
   :has-auth (boolean (get-in db [:mcp-auth (mcp-auth-key mcp-name server-config db) :access-token]))
   :disabled (boolean (:disabled server-config))
   :status status})

(defn ^:private ->content [content-client]
  (case (:type content-client)
    "text" {:type :text
            :text (:text content-client)}
    "image" {:type :image
             :media-type (:mimeType content-client)
             :base64 (:data content-client)}
    "resource" (let [resource (:resource content-client)]
                 (cond
                   (:text resource) {:type :text
                                     :text (:text resource)}
                   (:blob resource) {:type :text
                                     :text (format "[Binary resource: %s]"
                                                   (:uri resource))}
                   :else nil))
    (do (logger/warn logger-tag (format "Unsupported MCP content type: %s"
                                        (:type content-client)))
        nil)))

(defn ^:private ->resource-content [resource-content-client]
  (let [uri (:uri resource-content-client)]
    (cond
      (:text resource-content-client)
      {:type :text :uri uri :text (:text resource-content-client)}

      (:blob resource-content-client)
      {:type :text :uri uri :text (format "[Binary resource: %s]" uri)}

      :else nil)))

(defn ^:private tool->internal
  "Adapt plumcp tool map to ECA's internal tool shape.

  Per the MCP spec, tool `description` is optional; some servers omit it. We
  normalize a missing/empty description here so providers that require a
  non-null string (e.g. Anthropic) don't reject the request."
  [tool]
  {:name (:name tool)
   :description (or (not-empty (:description tool))
                    (not-empty (:title tool))
                    (str "MCP tool: " (:name tool)))
   :parameters (:inputSchema tool)})

(defn ^:private format-jsonrpc-error
  "Format a JSON-RPC error map into a readable string for logging."
  [jsonrpc-error]
  (let [code (:code jsonrpc-error)
        message (:message jsonrpc-error)
        data (:data jsonrpc-error)
        http-status (:plumcp.core/http-status jsonrpc-error)]
    (cond-> ""
      http-status (str "http=" http-status " ")
      code (str "code=" code)
      message (str (when code " ") "message=" message)
      data (str " data=" (pr-str data))
      (and (nil? code) (nil? message)) (str (pr-str jsonrpc-error)))))

(defn ^:private on-list-error [kind]
  (fn [_id jsonrpc-error]
    (logger/warn logger-tag (format "Could not list %s: %s" kind (format-jsonrpc-error jsonrpc-error)))
    []))

(defn ^:private on-list-timeout [kind]
  (fn [_id _timeout-value]
    (logger/warn logger-tag (format "Timeout waiting server response listing %s" kind))
    []))

(defn ^:private list-options [kind timeout-ms]
  {:on-error (on-list-error kind)
   :timeout-millis timeout-ms
   :timeout-value ::list-timeout
   :on-timeout (on-list-timeout kind)})

(defn ^:private list-server-tools [client timeout-ms]
  (if (get-in (pmc/get-initialize-result client) [:capabilities :tools])
    (or (some->> (pmc/list-tools client (list-options "tools" timeout-ms))
                 (mapv tool->internal))
        [])
    []))

(defn ^:private list-server-prompts [client timeout-ms]
  (if (get-in (pmc/get-initialize-result client) [:capabilities :prompts])
    (or (pmc/list-prompts client (list-options "prompts" timeout-ms))
        [])
    []))

(defn ^:private list-server-resources [client timeout-ms]
  (if (get-in (pmc/get-initialize-result client) [:capabilities :resources])
    (or (pmc/list-resources client (list-options "resources" timeout-ms))
        [])
    []))

;; OAuth for MCP servers: discovery (a pre-registered client, ECA's Client ID Metadata Document
;; or Dynamic Client Registration), the token exchange and its refresh. The callback server
;; and PKCE live in eca.oauth, shared with the providers' logins.

(def ^:private oauth-logger-tag "[OAUTH]")

(def ^:private eca-client-id "Ov23liT613uPA2ydLTa8")

(def ^:private cimd-client-id
  "ECA's OAuth Client ID Metadata Document (CIMD) URL, published from
   docs/oauth/client-metadata.json. Authorization servers fetch it to identify
   ECA, replacing Dynamic Client Registration."
  "https://eca.dev/oauth/client-metadata.json")

(defn ^:private url->base-url
  "Extract the base URL (scheme + host + port) from a full URL.
   E.g., 'https://api.example.com/v1/mcp' -> 'https://api.example.com'"
  [^String url]
  (let [uri (java.net.URI. url)]
    (str (.getScheme uri) "://" (.getHost uri)
         (when (pos? (.getPort uri))
           (str ":" (.getPort uri))))))

(defn ^:private url-without-query
  "Strip query string and fragment from a URL, preserving scheme, authority and path.
   E.g., 'https://api.example.com/v1/mcp?token=x#frag' -> 'https://api.example.com/v1/mcp'
   Returns nil for nil input."
  [^String url]
  (when url
    (let [uri (java.net.URI. url)]
      (str (.getScheme uri) "://" (.getAuthority uri) (.getPath uri)))))

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

(def ^:private probe-ping-body
  "JSON-RPC ping body used to probe for OAuth challenges. Some servers
   (e.g. Figma) only return 401 + www-authenticate when the request body is
   a recognizable MCP message; a bare '{}' is treated as malformed and yields
   a generic JSON-RPC error instead of an auth challenge.
   `ping` is used (rather than `initialize`) so the probe is never confused
   with the real MCP handshake by servers that maintain per-connection state
   or by test mocks that count requests by method name."
  (json/generate-string
   {:jsonrpc "2.0"
    :id 0
    :method "ping"}))

(defn ^:private probe-auth
  "Probe the MCP server for a 401 auth challenge with www-authenticate header.
   Tries HEAD first; falls back to POST with a JSON-RPC ping body if HEAD
   doesn't return a valid challenge (some servers like Glean don't support HEAD,
   some proxies like istio-envoy return 401 on HEAD without actually requiring
   auth, and some servers like Figma only emit the auth challenge in response to
   a recognizable MCP request)."
  [^String url]
  (let [head-response (http/head url {:timeout 10000
                                      :throw-exceptions? false})]
    (if (valid-auth-challenge? head-response)
      head-response
      (let [post-response (http/post url {:timeout 10000
                                          :throw-exceptions? false
                                          :headers {"Content-Type" "application/json"
                                                    "Accept" "application/json, text/event-stream"}
                                          :body probe-ping-body})]
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

(defn ^:private discover-oauth-info
  "Perform OAuth discovery for the given MCP server URL.
   The client follows the MCP spec priority: pre-registered `configured-client-id`,
   then ECA's Client ID Metadata Document (CIMD) when the authorization server
   advertises `client_id_metadata_document_supported` and no other client setting
   (secret, oauth port, name) is configured, then Dynamic Client Registration (DCR).
   Optional `configured-client-secret` enables confidential OAuth (e.g. Slack MCP).
   When DCR is performed, a `client_secret` returned by the registration endpoint
   is also captured and used in the subsequent token exchange (some servers like
   Figma issue a secret even when the client requests `token_endpoint_auth_method: none`).
   Optional `configured-oauth-port` uses a fixed port for the OAuth callback (required
   by providers like Slack that mandate pre-registered redirect URIs).
   Optional `configured-client-name` overrides the `client_name` sent during DCR
   (some servers like Figma allowlist clients by name)."
  ([^String url] (discover-oauth-info url nil))
  ([^String url configured-client-id] (discover-oauth-info url configured-client-id nil))
  ([^String url configured-client-id configured-client-secret]
   (discover-oauth-info url configured-client-id configured-client-secret nil))
  ([^String url configured-client-id configured-client-secret configured-oauth-port]
   (discover-oauth-info url configured-client-id configured-client-secret configured-oauth-port nil))
  ([^String url configured-client-id configured-client-secret configured-oauth-port configured-client-name]
   (let [base-url (url->base-url url)
         auth-response (probe-auth url)]
     (when-let [headers (:headers auth-response)]
       (let [ssl? (some? configured-oauth-port)
             callback-port (if configured-oauth-port
                             (int configured-oauth-port)
                             (oauth/get-free-port))
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
                 cimd? (and (true? (:client_id_metadata_document_supported meta))
                            (not (or configured-client-id
                                     configured-client-secret
                                     configured-client-name
                                     configured-oauth-port)))
                 _ (when cimd?
                     (logger/info oauth-logger-tag (format "Using Client ID Metadata Document %s as client_id" cimd-client-id)))
                 ;; Skip DCR when a client-id is pre-configured or CIMD is used
                 new-client-info (when-not (or configured-client-id cimd?)
                                   (when-let [reg-endpoint (:registration_endpoint meta)]
                                     (let [auth-method (if configured-client-secret
                                                         "client_secret_post"
                                                         "none")
                                           client-name (or configured-client-name "ECA (Editor Code Assistant)")
                                           _ (logger/info oauth-logger-tag
                                                          (format "Attempting DCR at %s with client_name=%s"
                                                                  reg-endpoint client-name))
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
                                                         :client_name client-name
                                                         :client_uri "http://github.com/editor-code-assistant/eca"
                                                         :client_id eca-client-id
                                                         :client_id_issued_at (.getEpochSecond (java.time.Instant/now))})})]
                                       (if (<= 200 (:status res) 299)
                                         (let [body (:body res)
                                               cid (:client_id body)
                                               cs (:client_secret body)]
                                           (logger/info oauth-logger-tag
                                                        (format "DCR succeeded at %s, client_id=%s%s"
                                                                reg-endpoint
                                                                cid
                                                                (if cs
                                                                  " (confidential, received client_secret)"
                                                                  "")))
                                           {:client-id cid :client-secret cs})
                                         (do (logger/warn oauth-logger-tag
                                                          (format "DCR rejected by %s status=%s body=%s"
                                                                  reg-endpoint
                                                                  (:status res)
                                                                  (let [b (str (:body res))]
                                                                    (if (> (count b) 200)
                                                                      (str (subs b 0 200) "...")
                                                                      b))))
                                             nil)))))
                 {:keys [challenge verifier]} (oauth/generate-pkce)
                 client-id (or configured-client-id
                               (when cimd? cimd-client-id)
                               (:client-id new-client-info)
                               eca-client-id)
                 client-secret (or configured-client-secret (:client-secret new-client-info))
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
               client-secret (assoc :client-secret client-secret)))))))))

(comment
  (discover-oauth-info "https://mcp.atlassian.com/v1/sse")
  (discover-oauth-info "https://mcp.miro.com/")
  (discover-oauth-info "https://api.githubcopilot.com/mcp/"))

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

(defn ^:private authorize-token! [{:keys [token-endpoint verifier client-id client-secret redirect-uri resource]} code]
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
    (logger/debug oauth-logger-tag (format "Token response status=%d body=%s" status (pr-str parsed-body)))
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

(defn ^:private refresh-token!
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
      (logger/debug oauth-logger-tag (format "Refresh token response status=%d body=%s" status (pr-str parsed-body)))
      (when (and (= 200 status) (:access_token parsed-body))
        {:refresh-token (or (:refresh_token parsed-body) refresh-token)
         :access-token (:access_token parsed-body)
         :expires-at (+ (quot (System/currentTimeMillis) 1000)
                        (or (:expires_in parsed-body) 3600))}))
    (catch Exception e
      (logger/warn oauth-logger-tag (format "Failed to refresh token: %s" (.getMessage e)))
      nil)))

(defn ^:private initialize-mcp-oauth
  [oauth-info
   server-name
   db*
   server-config
   {:keys [on-server-updated]}]
  (logger/info logger-tag (format "MCP server '%s' requires authentication" server-name))
  (swap! db* assoc-in [:mcp-clients server-name] {:status :requires-auth
                                                  :oauth-info oauth-info})
  (on-server-updated (->server server-name server-config :requires-auth @db*)))

(defn ^:private token-expired?
  "Check if the token is expired or will expire in the next 60 seconds."
  [expires-at]
  (when expires-at
    (< expires-at (+ (quot (System/currentTimeMillis) 1000) 60))))

(defn ^:private try-refresh-token!
  "Attempt to refresh an MCP server's OAuth token.
   Returns true if refresh succeeded, false otherwise."
  [name db* url metrics {:keys [clientId clientSecret oauthPort clientName] :as server-config}]
  (let [auth-key (mcp-auth-key name server-config @db*)
        mcp-auth (get-in @db* [:mcp-auth auth-key])
        {:keys [refresh-token]} mcp-auth
        ;; Reuse the client the refresh token was minted under instead of running
        ;; Dynamic Client Registration again. Servers with non-idempotent DCR (e.g.
        ;; RunLayer) issue a new client_id per registration, which would orphan the
        ;; refresh token. Configured values still take precedence over persisted ones.
        client-id (or (some-> clientId replace-env-vars) (:client-id mcp-auth))
        client-secret (or (some-> clientSecret replace-env-vars) (:client-secret mcp-auth))]
    (when refresh-token
      (logger/info logger-tag (format "Attempting to refresh token for MCP server '%s'" name))
      (when-let [oauth-info (discover-oauth-info (replace-env-vars url)
                                                 client-id
                                                 client-secret
                                                 oauthPort
                                                 (some-> clientName replace-env-vars))]
        (when-let [new-tokens (refresh-token!
                               (:token-endpoint oauth-info)
                               (:client-id oauth-info)
                               refresh-token
                               :client-secret (:client-secret oauth-info)
                               :resource (:resource oauth-info))]
          (logger/info logger-tag (format "Successfully refreshed token for MCP server '%s'" name))
          (swap! db* assoc-in [:mcp-auth auth-key]
                 (merge mcp-auth new-tokens))
          (db.global-cache/update-global-cache! @db* metrics)
          true)))))

(def ^:private max-init-retries 3)

(defn ^:private transient-transport-error?
  "Checks if the exception (or its root cause) is a transient transport error
   that warrants a retry. This covers infrastructure issues like load balancers
   or proxies closing connections, network interruptions, and connection resets."
  [^Exception e]
  (letfn [(transient-io-msg? [^String msg]
            (or (string/includes? msg "chunked transfer encoding")
                (string/includes? msg "EOF reached while reading")
                (string/includes? msg "Connection reset")
                (string/includes? msg "Connection refused")
                (string/includes? msg "Broken pipe")
                (string/includes? msg "Read timed out")))]
    (or (and (instance? IOException e)
             (when-let [msg (.getMessage e)]
               (transient-io-msg? msg)))
        (when-let [cause (.getCause e)]
          (and (instance? IOException cause)
               (when-let [msg (.getMessage cause)]
                 (transient-io-msg? msg)))))))

(defn ^:private initialize-server! [name db* config metrics on-server-updated]
  (let [db @db*
        server-config (get-in config [:mcpServers name])]
    (on-server-updated (->server name server-config :starting db))
    (try
      (when (Thread/interrupted)
        (throw (InterruptedException. "Init cancelled")))
      (let [workspaces (:workspace-folders @db*)
            url (:url server-config)
            ;; Skip OAuth entirely if Authorization header is configured
            has-static-auth? (some-> server-config :headers :Authorization some?)
            mcp-auth (get-in @db* [:mcp-auth (mcp-auth-key name server-config @db*)])
            ;; Invalidate cached credentials when base URL changed (ignore query params)
            mcp-auth (when (= (url-without-query url)
                              (url-without-query (:url mcp-auth))) mcp-auth)
            has-token? (some? (:access-token mcp-auth))
            token-expired? (token-expired? (:expires-at mcp-auth))
            ;; Try to refresh if token exists but is expired
            refresh-succeeded? (when (and has-token? token-expired?)
                                 (try-refresh-token! name db* url metrics server-config))
            ;; Only get oauth-info if we don't have a token or refresh failed, and no static auth header
            needs-oauth? (and (not has-static-auth?)
                              (or (not has-token?)
                                  (and token-expired? (not refresh-succeeded?))))
            oauth-info (when (and url needs-oauth?)
                         (discover-oauth-info (replace-env-vars url)
                                              (some-> (:clientId server-config) replace-env-vars)
                                              (some-> (:clientSecret server-config) replace-env-vars)
                                              (:oauthPort server-config)
                                              (some-> (:clientName server-config) replace-env-vars)))]
        (if oauth-info
          (initialize-mcp-oauth oauth-info
                                name
                                db*
                                server-config
                                {:on-server-updated on-server-updated})
          (let [init-timeout (:mcpTimeoutSeconds config)
                list-timeout-ms (* 1000 init-timeout)
                pending-tools-refresh* (atom nil)
                on-tools-change (fn [tools]
                                  (let [tools (mapv tool->internal tools)]
                                    (swap! db* assoc-in [:mcp-clients name :tools] tools)
                                    (on-server-updated (->server name server-config :running @db*))))]
            (loop [attempt 1]
              (let [{:keys [transport http-client needs-reinit?*]} (->transport name server-config workspaces db*)
                    result (try
                             (let [client (->client name transport init-timeout workspaces
                                                    {:on-tools-change on-tools-change
                                                     :pending-tools-refresh* pending-tools-refresh*
                                                     ;; keep-alive pings only for remote servers, where idle
                                                     ;; connections can be dropped by proxies/load balancers
                                                     :heartbeat-seconds (when url (:mcpKeepAliveSeconds config))})
                                   init-result (pmc/get-initialize-result client)
                                   _ (when-not init-result
                                       (throw (ex-info "MCP initialize returned no result"
                                                       {:server name})))
                                   _ (when (init-stopped?)
                                       (throw (InterruptedException. "Init stopped")))
                                   version (get-in init-result [:serverInfo :version])]
                               (swap! db* assoc-in [:mcp-clients name] (cond-> {:client client
                                                                                :status :starting
                                                                                :needs-reinit?* needs-reinit?*
                                                                                :pending-tools-refresh* pending-tools-refresh*}
                                                                         http-client (assoc :http-client http-client)))
                               (swap! db* assoc-in [:mcp-clients name :version] version)
                               (swap! db* assoc-in [:mcp-clients name :instructions] (:instructions init-result))
                               (swap! db* assoc-in [:mcp-clients name :tools] (list-server-tools client list-timeout-ms))
                               (if (and needs-reinit?* @needs-reinit?*)
                                 (do (try (pp/stop-client-transport! transport false) (catch Exception _))
                                     (if (< attempt max-init-retries)
                                       (do (logger/warn logger-tag
                                                        (format "MCP server '%s' transport error during initialization (attempt %d/%d), retrying"
                                                                name attempt max-init-retries))
                                           (try-refresh-token! name db* url metrics server-config)
                                           :retry)
                                       (do (logger/error logger-tag (format "MCP server '%s' transport error during initialization" name))
                                           (swap! db* assoc-in [:mcp-clients name :status] :failed)
                                           (on-server-updated (->server name server-config :failed @db*))
                                           :failed)))
                                 (do (swap! db* assoc-in [:mcp-clients name :status] :running)
                                     (on-server-updated (->server name server-config :running @db*))
                                     (logger/info logger-tag (format "Started MCP server %s" name))
                                     ;; Fetch after flipping to running: servers may take long or never
                                     ;; answer these list requests, and they should not gate the status.
                                     (let [prompts (list-server-prompts client list-timeout-ms)
                                           resources (list-server-resources client list-timeout-ms)]
                                       (swap! db* assoc-in [:mcp-clients name :prompts] prompts)
                                       (swap! db* assoc-in [:mcp-clients name :resources] resources)
                                       ;; Notify again only when something actually arrived, avoiding
                                       ;; a redundant duplicate notification for servers without them.
                                       (when (or (seq prompts) (seq resources))
                                         (on-server-updated (->server name server-config :running @db*))))
                                     :ok)))
                             (catch Exception e
                               (try (pp/stop-client-transport! transport false) (catch Exception _))
                               (cond
                                 (init-stopped?)
                                 (do (logger/info logger-tag (format "Initialization of MCP server %s was stopped" name))
                                     :stopped)

                                 (and (transient-transport-error? e) (< attempt max-init-retries))
                                 (do
                                   (logger/warn logger-tag (format "Transient HTTP error initializing MCP server %s (attempt %d/%d), retrying: %s"
                                                                   name attempt max-init-retries
                                                                   (some-> e .getCause .getMessage)))
                                   :retry)

                                 :else
                                 (do
                                   (let [cause (.getCause e)
                                         cause-message (cond
                                                         (and cause (instance? java.util.concurrent.TimeoutException cause))
                                                         (format "Timeout of %s secs waiting for server start" init-timeout)

                                                         cause
                                                         (.getMessage cause)

                                                         :else
                                                         (.getMessage e))]
                                     (logger/error logger-tag (format "Could not initialize MCP server %s: %s" name cause-message)))
                                   (swap! db* assoc-in [:mcp-clients name :status] :failed)
                                   (on-server-updated (->server name server-config :failed db))
                                   :failed))))]
                (when (= result :retry)
                  (Thread/sleep 1000)
                  (recur (inc attempt))))))))
      (catch InterruptedException _
        (logger/info logger-tag (format "Initialization of MCP server %s was interrupted" name))
        (when-not (init-stopped?)
          (swap! db* assoc-in [:mcp-clients name :status] :failed)
          (on-server-updated (->server name server-config :failed @db*))))
      (catch Exception e
        (logger/error logger-tag (format "Unexpected error initializing MCP server %s: %s" name (.getMessage e)))
        (when-not (init-stopped?)
          (swap! db* assoc-in [:mcp-clients name :status] :failed)
          (on-server-updated (->server name server-config :failed @db*)))))))

(defn ^:private start-single-server-async!
  "Spawn a daemon init thread for a single MCP server. Used by both initial
   startup and runtime add-server!."
  [server-name db* config metrics on-server-updated]
  (let [t (Thread.
           (fn []
             (with-init-thread server-name
               (initialize-server! server-name db* config metrics on-server-updated))))]
    (.setName t (str "mcp-init-" server-name))
    (.setDaemon t true)
    (.start t)))

(defn initialize-servers-async! [{:keys [on-server-updated]} db* config metrics]
  (let [db @db*]
    (doseq [[name-kwd server-config] (:mcpServers config)]
      (let [server-name (name name-kwd)]
        (when-not (get-in db [:mcp-clients server-name])
          (if (get server-config :disabled false)
            (on-server-updated (->server server-name server-config :disabled db))
            (start-single-server-async! server-name db* config metrics on-server-updated)))))))

(def ^:private disconnect-timeout-ms 3000)

(defn stop-server! [name db* config {:keys [on-server-updated]}]
  (when-let [^Thread init-thread (get @init-threads* name)]
    (logger/info logger-tag (format "Interrupting in-progress init thread for server '%s'" name))
    (stop-init-thread! name init-thread)
    ;; a server still starting has no client yet: it's stopped now
    (when-not (get-in @db* [:mcp-clients name])
      (on-server-updated (->server name (get-in config [:mcpServers name]) :stopped @db*))))
  (when-let [{:keys [client http-client]} (get-in @db* [:mcp-clients name])]
    (let [server-config (get-in config [:mcpServers name])]
      (swap! db* assoc-in [:mcp-clients name :status] :stopping)
      (on-server-updated (->server name server-config :stopping @db*))
      (let [f (future (try (pmc/disconnect! client) (catch Exception _ nil)))]
        (when-not (deref f disconnect-timeout-ms nil)
          (logger/warn logger-tag (format "Timeout disconnecting MCP server %s, forcing transport stop" name))
          ;; Fire-and-forget: pp/stop! can block indefinitely on HttpClient.close()
          ;; awaiting internal thread termination, so we must not block the caller.
          (future
            (if http-client
              (try (pp/stop! http-client) (catch Exception _))
              (try (pp/stop-client-transport! (pcs/?transport client) false) (catch Exception _))))))
      (swap! db* assoc-in [:mcp-clients name :status] :stopped)
      (on-server-updated (->server name server-config :stopped @db*))
      (swap! db* update :mcp-clients dissoc name)
      (logger/info logger-tag (format "Stopped MCP server %s" name)))))

(defn start-server! [name db* config metrics {:keys [on-server-updated]}]
  (when-let [server-config (get-in config [:mcpServers name])]
    (when (get server-config :disabled false)
      (logger/info logger-tag (format "Starting MCP server %s from manual request despite :disabled=true" name)))
    (with-init-thread name
      (initialize-server! name db* config metrics on-server-updated))))

(defn ^:private open-browser! [^String url]
  (try
    (if shared/windows-os?
      (let [^java.util.List cmd ["cmd" "/c" "start" "" url]]
        (-> (ProcessBuilder. cmd)
            (.start)))
      (browse/browse-url url))
    (catch Exception e
      (logger/error logger-tag (str "Failed to open browser: " (.getMessage e))))))

(defn connect-server!
  "Initiate OAuth authorization for an MCP server that requires auth.
   Starts the local OAuth callback server and returns the authorization URL."
  [name db* config metrics {:keys [on-server-updated]}]
  (let [server-config (get-in config [:mcpServers name])
        {:keys [oauth-info]} (get-in @db* [:mcp-clients name])]
    (when (and server-config oauth-info)
      (let [{:keys [authorization-endpoint callback-port ssl?]} oauth-info]
        (swap! db* assoc-in [:mcp-clients name :status] :starting)
        (on-server-updated (->server name server-config :starting @db*))
        (oauth/start-oauth-server!
         {:port callback-port
          :ssl? ssl?
          ;; Callbacks run on a background thread after the OAuth HTML page has been
          ;; flushed to the browser, so synchronous stop-oauth-server! is safe here.
          :on-success (fn [{:keys [code]}]
                        (let [{:keys [access-token refresh-token expires-at]} (authorize-token! oauth-info code)
                              auth-key (mcp-auth-key name server-config @db*)]
                          ;; Persist the client identity the tokens were minted under so refreshes
                          ;; reuse it. Servers with non-idempotent Dynamic Client Registration (e.g.
                          ;; RunLayer) issue a new client_id per registration; without this, a later
                          ;; refresh would re-register and orphan the refresh token.
                          (swap! db* assoc-in [:mcp-auth auth-key] (cond-> {:type :auth/oauth
                                                                            :url (:url server-config)
                                                                            :refresh-token refresh-token
                                                                            :access-token access-token
                                                                            :expires-at expires-at
                                                                            :client-id (:client-id oauth-info)}
                                                                     (:client-secret oauth-info)
                                                                     (assoc :client-secret (:client-secret oauth-info)))))
                        (oauth/stop-oauth-server! callback-port)
                        (db.global-cache/update-global-cache! @db* metrics)
                        (initialize-server! name db* config metrics on-server-updated))
          :on-error (fn [error]
                      (oauth/stop-oauth-server! callback-port)
                      (logger/error logger-tag error)
                      (swap! db* assoc-in [:mcp-clients name :status] :failed)
                      (on-server-updated (->server name server-config :failed @db*)))})
        (open-browser! authorization-endpoint)))))

(defn ^:private restart-server!
  "Stop the server if running, then re-initialize it on the current thread."
  [name db* config metrics on-server-updated]
  (when (get-in @db* [:mcp-clients name :client])
    (stop-server! name db* config {:on-server-updated on-server-updated}))
  (with-init-thread name
    (initialize-server! name db* config metrics on-server-updated)))

(defn restart-server-async!
  "Spawn a daemon thread that stops the server (if running) and re-initializes
   it. Used by reconciliation when an MCP server's config changed out-of-band."
  [server-name db* config metrics on-server-updated]
  (let [t (Thread.
           (fn []
             ;; stop runs outside with-init-thread because stop-server! interrupts
             ;; any thread registered in init-threads* for this name.
             (when (get-in @db* [:mcp-clients server-name :client])
               (stop-server! server-name db* config {:on-server-updated on-server-updated}))
             (with-init-thread server-name
               (initialize-server! server-name db* config metrics on-server-updated))))]
    (.setName t (str "mcp-restart-" server-name))
    (.setDaemon t true)
    (.start t)))

(defn reconcile-servers!
  "Diff `:mcpServers` in `prev-config` vs `new-config` and apply lifecycle ops:
   - added (in new, not in old): start async (or emit disabled status)
   - removed (in old, not in new): stop + emit serverRemoved
   - changed (config differs): restart async

   No-op on the first tick (prev-config nil) since `initialize-servers-async!`
   handles the initial start. Respects `:pureConfig`."
  [prev-config new-config db* metrics {:keys [on-server-updated on-server-removed]}]
  (when (and prev-config (not (:pureConfig new-config)))
    (let [old (or (:mcpServers prev-config) {})
          new (or (:mcpServers new-config) {})
          old-keys (set (keys old))
          new-keys (set (keys new))
          removed (remove new-keys old-keys)
          added (remove old-keys new-keys)
          changed (filter (fn [k] (not= (get old k) (get new k)))
                          (filter new-keys old-keys))]
      (doseq [k removed]
        (let [server-name (name k)]
          (logger/info logger-tag (format "Reconcile: removing server '%s'" server-name))
          (future
            (try
              (when (get-in @db* [:mcp-clients server-name :client])
                (stop-server! server-name db* prev-config {:on-server-updated on-server-updated}))
              (swap! db* update :mcp-auth dissoc (mcp-auth-key server-name (get old k) @db*))
              (when on-server-removed
                (on-server-removed {:name server-name}))
              (catch Exception e
                (logger/error logger-tag (format "Reconcile remove failed for '%s': %s"
                                                 server-name (.getMessage e))))))))
      (doseq [k added]
        (let [server-name (name k)
              server-config (get new k)]
          (logger/info logger-tag (format "Reconcile: adding server '%s'" server-name))
          (if (get server-config :disabled false)
            (on-server-updated (->server server-name server-config :disabled @db*))
            (start-single-server-async! server-name db* new-config metrics on-server-updated))))
      (doseq [k changed]
        (let [server-name (name k)]
          (logger/info logger-tag (format "Reconcile: restarting server '%s' (config changed)" server-name))
          (restart-server-async! server-name db* new-config metrics on-server-updated))))))

(defn logout-server!
  "Logout from an MCP server by clearing stored OAuth credentials and restarting it."
  [name db* config metrics {:keys [on-server-updated]}]
  (when-let [server-config (get-in config [:mcpServers name])]
    (swap! db* update :mcp-auth dissoc (mcp-auth-key name server-config @db*))
    (db.global-cache/update-global-cache! @db* metrics)
    (restart-server! name db* config metrics on-server-updated)))

(defn ^:private parse-json-with-comments [^String s]
  (binding [json.factory/*json-factory* (json.factory/make-json-factory {:allow-comments true})]
    (json/parse-string s)))

(defn ^:private find-server-config-source
  "Returns {:source :local :workspace-root-uri uri} or {:source :global}
   indicating where the MCP server `server-name` is defined.
   Checks local workspace configs first (highest priority), then global."
  [server-name db]
  (let [roots (:workspace-folders db)]
    (or (some (fn [{:keys [uri]}]
                (let [config-file (io/file (shared/uri->filename uri) ".eca" "config.json")]
                  (when (.exists ^java.io.File config-file)
                    (let [local-config (parse-json-with-comments (slurp config-file))]
                      (when (get-in local-config ["mcpServers" server-name])
                        {:source :local :workspace-root-uri uri})))))
              roots)
        (let [global-file (config/global-config-file)]
          (when (.exists global-file)
            (let [global-config (parse-json-with-comments (slurp global-file))]
              (when (get-in global-config ["mcpServers" server-name])
                {:source :global}))))
        {:source :global})))

(defn ^:private update-config-file!
  "Apply rewrite-json edits to a config file, preserving comments and formatting.
   `edit-fn` receives a parsed rj root node and returns the modified root."
  [^java.io.File config-file edit-fn]
  (let [raw (if (.exists config-file) (slurp config-file) "{}")
        root (edit-fn (rj/parse-string raw))]
    (io/make-parents config-file)
    (spit config-file (rj/to-string root))))

(defn ^:private walk-server-leaves
  "Walks an MCP server-config map and returns a seq of [string-path-vec value] pairs,
   stringifying keyword keys. Vectors (e.g. :args) are treated as leaves so they
   serialize as JSON arrays."
  ([m] (walk-server-leaves [] m))
  ([prefix m]
   (reduce-kv
    (fn [acc k v]
      (let [path (conj prefix (if (keyword? k) (name k) (str k)))]
        (if (and (map? v) (seq v))
          (into acc (walk-server-leaves path v))
          (conj acc [path v]))))
    []
    m)))

(defn ^:private rj-assoc-server-entry
  "Replace the `mcpServers[server-name]` subtree in a rewrite-json root with
   the contents of `server-config`. Dissocs the existing entry first so stale
   keys (e.g. :url after switching to stdio) do not leak in."
  [root server-name server-config]
  (reduce (fn [r [leaf-path v]]
            (rj/assoc-in r (into ["mcpServers" server-name] leaf-path) v))
          (rj/dissoc-in root ["mcpServers" server-name])
          (walk-server-leaves server-config)))

(defn ^:private resolve-config-file [server-name db]
  (let [{:keys [source workspace-root-uri]} (find-server-config-source server-name db)]
    (if (= source :local)
      (io/file (shared/uri->filename workspace-root-uri) ".eca" "config.json")
      (config/global-config-file))))

(defn ^:private resolve-target-config-file
  "Resolve the config file for a NEW server (no existing source to look up).
   scope is :global (default) or :workspace (workspace-uri required)."
  [scope workspace-uri db]
  (case scope
    :workspace (if workspace-uri
                 (let [roots (:workspace-folders db)
                       uris (into #{} (map :uri) roots)]
                   (when-not (contains? uris workspace-uri)
                     (throw (ex-info (format "workspaceUri '%s' is not an open workspace root" workspace-uri)
                                     {:workspace-uri workspace-uri
                                      :workspace-roots (vec uris)})))
                   (io/file (shared/uri->filename workspace-uri) ".eca" "config.json"))
                 (throw (ex-info ":workspace scope requires :workspace-uri"
                                 {:scope scope})))
    (config/global-config-file)))

(defn update-server!
  "Update an MCP server's config fields (command/args/env/url/headers), persist
   to the correct config file preserving comments and formatting, clear the
   config cache, then restart.

   `server-fields` is a partial map of fields to override. Fields not present
   are preserved from the existing entry EXCEPT when the transport flips:
   switching to HTTP (by supplying :url) strips :command/:args/:env, and
   switching to stdio (by supplying :command or :args) strips :url/:headers."
  [server-name server-fields db* config metrics {:keys [on-server-updated]}]
  (let [db @db*
        current-server-config (get-in config [:mcpServers server-name])
        switching-to-http?  (some? (:url server-fields))
        switching-to-stdio? (or (some? (:command server-fields))
                                (some? (:args server-fields)))
        stripped-keys (cond
                        switching-to-http?  [:command :args :env]
                        switching-to-stdio? [:url :headers]
                        :else [])
        new-server-config (-> (apply dissoc current-server-config stripped-keys)
                              (merge server-fields))
        config-file (resolve-config-file server-name db)]
    (update-config-file! config-file
                         #(rj-assoc-server-entry % server-name new-server-config))
    (config/clear-cache!)
    (let [fresh-config (config/all @db*)]
      (restart-server! server-name db* fresh-config metrics on-server-updated))))

(defn disable-server!
  "Disable an MCP server: persist disabled=true in config, stop if running, notify."
  [server-name db* config {:keys [on-server-updated]}]
  (let [db @db*
        config-file (resolve-config-file server-name db)
        server-config (get-in config [:mcpServers server-name])]
    (update-config-file! config-file
                         #(rj/assoc-in % ["mcpServers" server-name "disabled"] true))
    (config/clear-cache!)
    (when (get-in db [:mcp-clients server-name :client])
      (stop-server! server-name db* config {:on-server-updated on-server-updated}))
    (on-server-updated (->server server-name (assoc server-config :disabled true) :disabled @db*))))

(defn enable-server!
  "Enable an MCP server: remove disabled from config, start the server, notify."
  [server-name db* metrics {:keys [on-server-updated]}]
  (let [db @db*
        config-file (resolve-config-file server-name db)]
    (update-config-file! config-file
                         #(rj/dissoc-in % ["mcpServers" server-name "disabled"]))
    (config/clear-cache!)
    (let [fresh-config (config/all @db*)]
      (start-server! server-name db* fresh-config metrics {:on-server-updated on-server-updated}))))

(defn ^:private normalize-new-server-config
  "Coerce incoming map (from JSON-RPC) into the shape stored in :mcpServers.
   Keyword-izes top-level keys and nested :env/:headers keys so internal
   code paths (->transport, start-server!) work uniformly after config/all
   is re-read."
  [server-config]
  (letfn [(kw-map [m] (into {} (map (fn [[k v]] [(keyword (name k)) v])) m))]
    (cond-> (kw-map server-config)
      (:env server-config)     (update :env kw-map)
      (:headers server-config) (update :headers kw-map))))

(defn add-server!
  "Add a new MCP server definition: validate, persist to the chosen config file
   preserving comments, clear the config cache, then start the server async
   (unless :disabled true, in which case only emit the disabled status).

   `server-config` is the wire-shape entry map. Accepted fields:
     stdio:  :command, :args, :env, :disabled
     HTTP:   :url, :headers, :clientId, :clientSecret, :oauthPort, :disabled

   `opts`:
     :scope         :global (default) or :workspace
     :workspace-uri required when :scope = :workspace"
  [server-name server-config {:keys [scope workspace-uri]} db* config metrics
   {:keys [on-server-updated]}]
  (when (string/blank? server-name)
    (throw (ex-info "MCP server name must be non-blank" {})))
  (when (get-in config [:mcpServers server-name])
    (throw (ex-info (format "MCP server '%s' already exists" server-name)
                    {:server-name server-name})))
  (let [normalized (normalize-new-server-config server-config)
        has-command? (some? (:command normalized))
        has-url?     (some? (:url normalized))]
    (when (and has-command? has-url?)
      (throw (ex-info "MCP server entry must not specify both :command and :url"
                      {:server-name server-name})))
    (when-not (or has-command? has-url?)
      (throw (ex-info "MCP server entry must specify :command (stdio) or :url (HTTP)"
                      {:server-name server-name})))
    (let [scope (or scope :global)
          config-file (resolve-target-config-file scope workspace-uri @db*)]
      (update-config-file! config-file
                           #(rj-assoc-server-entry % server-name normalized))
      (config/clear-cache!)
      (let [fresh-config (config/all @db*)
            fresh-server-config (get-in fresh-config [:mcpServers server-name])]
        (if (get fresh-server-config :disabled false)
          (do (on-server-updated (->server server-name fresh-server-config :disabled @db*))
              (->server server-name fresh-server-config :disabled @db*))
          (do (start-single-server-async! server-name db* fresh-config metrics on-server-updated)
              (->server server-name fresh-server-config :starting @db*)))))))

(defn remove-server!
  "Remove an MCP server: stop if running, dissoc from the config file
   preserving comments, clear cache, clear any stored OAuth tokens, and
   fire on-server-removed."
  [server-name db* config {:keys [on-server-updated on-server-removed]}]
  (when-not (get-in config [:mcpServers server-name])
    (throw (ex-info (format "MCP server '%s' does not exist" server-name)
                    {:server-name server-name})))
  (let [db @db*
        server-config (get-in config [:mcpServers server-name])
        config-file (resolve-config-file server-name db)]
    (when (get-in db [:mcp-clients server-name :client])
      (stop-server! server-name db* config {:on-server-updated on-server-updated}))
    (swap! db* update :mcp-auth dissoc (mcp-auth-key server-name server-config db))
    (update-config-file! config-file
                         #(rj/dissoc-in % ["mcpServers" server-name]))
    (config/clear-cache!)
    (on-server-removed {:name server-name})
    {:name server-name :removed true}))

(defn await-pending-tools-refresh
  "Waits for any pending tool list refreshes to complete, with a timeout.
   Call before reading tools from db* to ensure dynamically loaded tools
   are available after a tools/list_changed notification.
   The pending-tools-refresh* atoms are set by the tools/list_changed
   notification handler and cleared when the refresh completes.
   Uses a shared deadline so multiple pending servers don't multiply the wait."
  [db timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (doseq [[_ {:keys [pending-tools-refresh*]}] (:mcp-clients db)]
      (when-let [p (and pending-tools-refresh* @pending-tools-refresh*)]
        (let [remaining (- deadline (System/currentTimeMillis))]
          (when (pos? remaining)
            (deref p remaining nil)))))))

(defn ^:private reinitialize-server!
  "Re-initialize an MCP server after a transport error (HTTP 401/403/5xx).
   Stops the old transport without attempting disconnect (the session is already
   gone server-side), then runs a fresh initialize-server! cycle.
   Proactively attempts a token refresh first: a server-side token rejection can
   arrive before our local expiry clock predicts it, so forcing a refresh here
   heals the session without falling back to a full browser re-auth. No-op when
   there is no stored refresh token (e.g. static-auth servers)."
  [server-name old-client db* config metrics]
  (logger/info logger-tag (format "Re-initializing MCP server '%s'" server-name))
  (try (pp/stop-client-transport! (pcs/?transport old-client) false) (catch Exception _))
  (swap! db* update :mcp-clients dissoc server-name)
  (let [server-config (get-in config [:mcpServers server-name])]
    (when-let [url (:url server-config)]
      (try-refresh-token! server-name db* url metrics server-config)))
  (initialize-server! server-name db* config metrics (constantly nil)))

(def ^:private tool-call-timeout-ms 120000)
(def ^:private liveness-ping-timeout-ms 5000)

(defn ^:private tool-call-error [msg]
  {:error true
   :contents [{:type :text :text msg}]})

(defn ^:private connection-dead?
  "Probes the server with a short JSON-RPC ping to distinguish a dead
   connection (e.g. pooled HTTP connections silently dropped after machine
   suspend or long idle) from a genuinely slow tool call. Any response,
   even an error one, means the connection is alive; only silence or a
   transport exception means it is dead."
  [mcp-client]
  (let [result (try
                 (pmc/ping mcp-client {:timeout-millis liveness-ping-timeout-ms
                                       :timeout-value ::ping-timeout
                                       :on-timeout (fn [_id timeout-value] timeout-value)
                                       :on-error (fn [_id _jsonrpc-error] ::ping-error-response)})
                 (catch Exception _ ::ping-failed))]
    (contains? #{::ping-timeout ::ping-failed} result)))

(defn ^:private reinit-worthy-http-status?
  "HTTP status codes that indicate the session/auth is broken and the server
   connection should be re-initialized (e.g. expired token, expired session,
   server error). 404 is the Streamable HTTP spec signal for an expired
   session id."
  [status]
  (or (= 401 (long status))
      (= 403 (long status))
      (= 404 (long status))
      (<= 500 (long status))))

(defn ^:private auth-error-message?
  "Heuristic for auth/session failures that some servers (e.g. RunLayer) surface
   as a JSON-RPC error without an HTTP status. Kept narrow to avoid treating
   ordinary tool errors as auth failures."
  [msg]
  (when (string? msg)
    (let [m (string/lower-case msg)]
      (or (string/includes? m "unauthorized")
          (string/includes? m "authentication")
          (string/includes? m "authorization")
          (string/includes? m "invalid token")
          (string/includes? m "invalid_token")
          (string/includes? m "access token")
          (string/includes? m "token expired")
          (string/includes? m "session expired")
          (string/includes? m "401")
          (string/includes? m "403")))))

(defn ^:private request-tool-call
  "Sends a tools/call request and returns the JSON-RPC response, or
   ::call-timeout when no response or progress notification arrives within
   `tool-call-timeout-ms`. A timed-out request is cancelled on the server.
   `request-opts`:
   - `:chat-id`, when given, is sent as `_meta.sessionId` so the server can
     keep per-chat state.
   - `:on-progress`, when given, receives each progress report as
     {:progress :total :message}."
  [mcp-client name arguments {:keys [chat-id on-progress]}]
  (let [progress-token (str (random-uuid))
        ;; plumcp drops a caller-supplied _meta, so its fields are set directly.
        request (-> (peg/make-call-tool-request name arguments)
                    (assoc-in [:params :_meta :progressToken] progress-token)
                    ;; MCP has no standard per-call session id; like other clients we
                    ;; send our own, the chat id hooks get as chat_id. #696
                    (cond-> chat-id (assoc-in [:params :_meta :sessionId] chat-id)))
        request-id (:id request)
        response* (promise)
        last-activity-ms* (atom (System/currentTimeMillis))]
    (swap! progress-listeners* assoc progress-token
           (fn [{:keys [progress total message] :as params}]
             (reset! last-activity-ms* (System/currentTimeMillis))
             (logger/debug logger-tag
                           (format "Tool '%s' progress: %s%s%s"
                                   name progress
                                   (if total (str "/" total) "")
                                   (if message (str " " message) "")))
             (when on-progress
               (try
                 (on-progress (select-keys params [:progress :total :message]))
                 (catch Exception e
                   (logger/warn logger-tag (format "Failed to report progress of tool '%s': %s"
                                                   name (.getMessage e))))))))
    (try
      (try
        (pcs/send-request-to-server mcp-client request #(deliver response* %))
        (catch Exception e
          (pcs/remove-pending-client-request! (pcs/get-client-cache-atom mcp-client) request-id)
          (throw e)))
      (loop []
        (let [remaining-ms (- (+ @last-activity-ms* tool-call-timeout-ms)
                              (System/currentTimeMillis))
              response (if (pos? remaining-ms)
                         (deref response* remaining-ms ::waiting)
                         ::call-timeout)]
          (cond
            (= ::waiting response) (recur)

            (= ::call-timeout response)
            (do (try (pmc/cancel-sent-request mcp-client request-id {:reason "Timed out"})
                     (catch Exception e
                       (logger/debug logger-tag (format "Failed to cancel timed-out tool call '%s': %s"
                                                        name (.getMessage e)))))
                ::call-timeout)

            :else response)))
      (finally
        (swap! progress-listeners* dissoc progress-token)))))

(defn ^:private do-call-tool
  "Execute a tool call, timing out when the server sends neither a response
   nor a progress notification for `tool-call-timeout-ms`.
   All non-200 HTTP errors are returned as JSON-RPC errors by plumcp with
   :plumcp.core/http-status on the error map.
   A timeout is followed by a short liveness ping: a silent server means the
   connection is stale (e.g. after machine suspend) and gets flagged for
   re-initialization. `request-opts` are passed to `request-tool-call`."
  [mcp-client name arguments needs-reinit?* request-opts]
  (let [error-msg* (atom nil)
        call-opts {:on-error (fn [_id jsonrpc-error]
                               (let [msg (or (:message jsonrpc-error) "Unknown JSON-RPC error")
                                     http-status (:plumcp.core/http-status jsonrpc-error)]
                                 (logger/warn logger-tag "Error calling tool:" (format-jsonrpc-error jsonrpc-error))
                                 (reset! error-msg* msg)
                                 (when (and needs-reinit?*
                                            (or (and http-status (reinit-worthy-http-status? http-status))
                                                ;; Some servers (e.g. RunLayer) report an expired session
                                                ;; as a JSON-RPC error without an HTTP status.
                                                (and (nil? http-status) (auth-error-message? msg))))
                                   (logger/warn logger-tag
                                                (format "Auth/transport error (%s), flagging for re-initialization"
                                                        (if http-status (str "HTTP " http-status) "no HTTP status")))
                                   (reset! needs-reinit?* true)))
                               nil)}
        result (try
                 (let [response (request-tool-call mcp-client name arguments request-opts)]
                   (if (= ::call-timeout response)
                     response
                     (pcs/on-jsonrpc-response response "call-tool" call-opts)))
                 (catch Exception e
                   (if (transient-transport-error? e)
                     (do (logger/warn logger-tag (format "Transient transport error, retrying tool call: %s" (.getMessage e)))
                         ::retry)
                     (do (logger/warn logger-tag (format "Error during tool call: %s" (or (.getMessage e) (.getName (class e)))))
                         {::error-msg (or (.getMessage e) (.getName (class e)))}))))]
    (cond
      (= ::retry result)
      nil

      (= ::call-timeout result)
      (let [timeout-s (quot tool-call-timeout-ms 1000)]
        (if (and needs-reinit?* (connection-dead? mcp-client))
          (do (logger/warn logger-tag
                           (format "Tool call '%s' timed out after %ss without response or progress and server does not answer pings, flagging for re-initialization" name timeout-s))
              (reset! needs-reinit?* true)
              (tool-call-error (format "MCP tool call timed out after %ss without response or progress: connection looks stale, re-initializing server" timeout-s)))
          (tool-call-error (format "MCP tool call timed out after %ss without response or progress" timeout-s))))

      (::error-msg result)
      (tool-call-error (format "MCP server error: %s" (::error-msg result)))

      (map? result)
      {:error (:isError result)
       :contents (into [] (keep ->content) (:content result))}

      :else
      (tool-call-error (or @error-msg* "MCP server returned empty response")))))

(defn ^:private reinit-and-call-tool! [server-name mcp-client db* config metrics name arguments request-opts]
  (reinitialize-server! server-name mcp-client db* config metrics)
  (if-let [new-client (get-in @db* [:mcp-clients server-name :client])]
    (do-call-tool new-client name arguments nil request-opts)
    (tool-call-error (format "Failed to re-initialize MCP server '%s'" server-name))))

(defn ^:private refresh-expired-auth!
  "Refresh a locally-expired OAuth token before a tool call so we don't send a
   doomed request. A successful refresh updates db* (the request middleware
   picks it up). Returns true when a full reinit is needed instead (no/failed
   refresh)."
  [server-name db* config metrics]
  (let [db @db*
        server-config (get-in config [:mcpServers server-name])
        url (:url server-config)
        mcp-auth (get-in db [:mcp-auth (mcp-auth-key server-name server-config db)])]
    (when (and url
               (:access-token mcp-auth)
               (token-expired? (:expires-at mcp-auth)))
      (logger/info logger-tag
                   (format "MCP server '%s' OAuth token expired, refreshing before tool call" server-name))
      (not (try-refresh-token! server-name db* url metrics server-config)))))

(defn call-tool!
  "Calls an MCP server tool. `chat-id`, when given, is sent to the server as
   `_meta.sessionId`. `on-progress`, when given, receives each progress
   report the server sends for the call as {:progress :total :message}."
  [server-name name arguments {:keys [db db* config metrics] :as ctx}]
  (if-let [[mcp-client needs-reinit?*]
           (when-let [{:keys [client tools needs-reinit?*]} (get-in db [:mcp-clients server-name])]
             (when (some #(= name (:name %)) tools)
               [client needs-reinit?*]))]
    (let [request-opts (select-keys ctx [:chat-id :on-progress])]
      (if (and needs-reinit?* db* config metrics
               (or @needs-reinit?*
                   (refresh-expired-auth! server-name db* config metrics)))
        ;; Flagged, or expired token couldn't be refreshed — reinit before the call
        (reinit-and-call-tool! server-name mcp-client db* config metrics name arguments request-opts)
        (let [result (do-call-tool mcp-client name arguments needs-reinit?* request-opts)]
          (cond
            ;; nil = transient transport error, retry once
            (nil? result)
            (do-call-tool mcp-client name arguments needs-reinit?* request-opts)

            ;; Flagged during the call (e.g. HTTP 401/403/5xx) — reinit and retry
            (and (:error result) needs-reinit?* @needs-reinit?* db* config metrics)
            (reinit-and-call-tool! server-name mcp-client db* config metrics name arguments request-opts)

            :else result))))
    (tool-call-error (format "Tool '%s' not found in MCP server '%s'" name server-name))))

;; --- Tool call details of the clojure-mcp server's editing tools: the diffs they
;; make

(defn ^:private clojure-edit-details-before-invocation [name args server {:keys [db chat-id ask-approval?]} new-file?]
  (when (not= "0.1.0" (:version server))
    (when ask-approval?
      (let [path (get args "file_path")
            {:keys [error contents]} (call-tool! (:name server) name (assoc args "dry_run" "new-source")
                                                 {:db db :chat-id chat-id})]
        (when-not error
          (when-let [new-source (some->> contents (filter #(= :text (:type %))) first :text)]
            (let [{:keys [added removed diff]} (diff/diff (if new-file?
                                                            ""
                                                            (slurp path))
                                                          new-source
                                                          path)]
              {:type :fileChange
               :path path
               :linesAdded added
               :linesRemoved removed
               :diff diff})))))))

(defn ^:private file-edit-details-after
  "The details after a call of `clojure_edit`, `clojure_edit_replace_sexp` or
   `file_edit`: the diff it made."
  [_ctx arguments _details result]
  (when-not (:error result)
    (when-let [diff (some->> result :contents (filter #(= :text (:type %))) first :text)]
      (let [{:keys [added removed]} (diff/unified-diff-counts diff)]
        {:type :fileChange
         :path (get arguments "file_path")
         :linesAdded added
         :linesRemoved removed
         :diff diff}))))

(defn ^:private file-write-details-after
  "The details after a `file_write` call: the diff it made."
  [_ctx arguments _details result]
  (when-not (:error result)
    (when-let [diff (some->> result :contents
                             (filter #(= :text (:type %)))
                             first :text
                             (string/split-lines)
                             (drop 2)
                             (string/join "\n"))]
      (let [{:keys [added removed]} (diff/unified-diff-counts diff)]
        {:type :fileChange
         :path (get arguments "file_path")
         :linesAdded added
         :linesRemoved removed
         :diff diff}))))

(defn ^:private with-clojure-mcp-details
  "`tool` with its details when it's one of the clojure-mcp server's editing
   tools, known by name on any server, as before modules."
  [{tool-name :name :as tool}]
  (case tool-name
    ("clojure_edit" "clojure_edit_replace_sexp" "file_edit")
    (assoc tool
           :details-before-fn (fn [ctx args]
                                (clojure-edit-details-before-invocation tool-name args (:server ctx) ctx false))
           :details-after-fn #'file-edit-details-after)

    "file_write"
    (assoc tool
           :details-before-fn (fn [ctx args]
                                (clojure-edit-details-before-invocation tool-name args (:server ctx) ctx true))
           :details-after-fn #'file-write-details-after)

    tool))

(defn ^:private tool-servers
  "`:tool-servers` `:list`: the servers in `:mcp-clients`, with their tools,
   then the disabled ones that don't run."
  [{:keys [db config]}]
  (concat
   (for [[server-name {:keys [tools version status]}] (:mcp-clients db)]
     {:type :mcp :name server-name :version version :status status
      :tools (some->> tools (mapv with-clojure-mcp-details))})
   (for [[server-name server-config] (:mcpServers config)
         :when (and (get server-config :disabled false)
                    (not (contains? (:mcp-clients db) server-name)))]
     {:type :mcp :name server-name :status :disabled :tools []})))

(defn ^:private call-server-tool
  "`:tool-servers` `:call`."
  [{:keys [db db* config metrics]} server-name tool-name arguments {:keys [chat-id on-progress]}]
  (call-tool! server-name tool-name arguments {:db db
                                               :db* db*
                                               :config config
                                               :metrics metrics
                                               :chat-id chat-id
                                               :on-progress on-progress}))

(defn ^:private await-servers-refresh
  "`:tool-servers` `:await-refresh`."
  [{:keys [db]} timeout-ms]
  (await-pending-tools-refresh db timeout-ms))

(defn all-prompts [db]
  (into []
        (mapcat (fn [[server-name {:keys [prompts]}]]
                  (mapv #(assoc % :server (name server-name)) prompts)))
        (:mcp-clients db)))

(defn all-resources [db]
  (into []
        (mapcat (fn [[server-name {:keys [resources]}]]
                  (mapv #(assoc % :server (name server-name)) resources)))
        (:mcp-clients db)))

(defn ^:private fetch-prompt [name arguments db]
  (if-let [mcp-client (->> (vals (:mcp-clients db))
                           (keep (fn [{:keys [client prompts]}]
                                   (when (some #(= name (:name %)) prompts)
                                     client)))
                           first)]
    (let [error* (atom nil)
          prompt (->> {:on-error (fn [_id jsonrpc-error]
                                   (logger/warn logger-tag "Error getting prompt:" (format-jsonrpc-error jsonrpc-error))
                                   (reset! error* (format-jsonrpc-error jsonrpc-error)))}
                      (pmc/get-prompt mcp-client name arguments))]
      (if-let [error @error*]
        {:error-message (str "MCP error getting prompt: " error)}
        (when prompt
          {:description (:description prompt)
           :messages (mapv (fn [each-message]
                             {:role (string/lower-case (:role each-message))
                              :content [(->content (:content each-message))]})
                           (:messages prompt))})))
    {:error-message (format "Prompt '%s' not found in any connected MCP server" name)}))

(defn get-prompt!
  "Gets the MCP prompt `name` with `arguments` from the server that has it:
   `{:description :messages}`, or `{:error-message ..}`."
  [name arguments db]
  (logger/info logger-tag (format "Calling prompt '%s' with args '%s'" name arguments))
  (try
    (let [result (fetch-prompt name arguments db)]
      (logger/debug logger-tag "Prompt result: " result)
      result)
    (catch Exception e
      (logger/warn logger-tag (format "Error calling prompt %s: %s" name e))
      {:error-message (str "Error calling prompt: " (.getMessage e))})))

(defn ^:private prompt-result
  "What running MCP prompt `prompt-name` with the typed `args` gives: the
   messages to send to the model, or a text to show when it fails or has
   none. The args fill the prompt's arguments by position."
  [prompt-name args db]
  (let [{:keys [arguments]} (first (filter #(= prompt-name (:name %)) (all-prompts db)))
        {:keys [messages error-message]} (get-prompt! prompt-name (zipmap (map :name arguments) args) db)]
    (cond
      error-message {:type :system-text :text error-message}
      (seq messages) {:type :send-messages :messages messages}
      :else {:type :system-text :text (format "No response from prompt '%s'." prompt-name)})))

(defn ^:private commands
  "Each prompt of the servers as a command, `server:prompt`."
  [{:keys [db]}]
  (for [{:keys [server] prompt-name :name :as prompt} (all-prompts db)]
    [(str server ":" prompt-name)
     (-> prompt
         (dissoc :server :name)
         (assoc :client-type :mcpPrompt
                :handler (fn [{:keys [db*]} {:keys [argv]}]
                           (prompt-result prompt-name argv @db*))))]))

(defn get-resource! [uri db]
  (when-let [mcp-client (->> (vals (:mcp-clients db))
                             (keep (fn [{:keys [client resources]}]
                                     (when (some #(= uri (:uri %)) resources)
                                       client)))
                             first)]
    (when-let [resource (->> {:on-error (fn [_id jsonrpc-error]
                                          (logger/warn logger-tag "Error reading resource:" (format-jsonrpc-error jsonrpc-error)))}
                             (pmc/read-resource mcp-client uri))]
      {:contents (mapv ->resource-content (:contents resource))})))

(defn ^:private resource-items
  "The servers' resources, as `@` completion items."
  [{:keys [db]}]
  (mapv #(assoc % :type "mcpResource") (all-resources db)))

(defn ^:private refine-resource
  "An `mcpResource` context a client sent: the resource's contents."
  [{:keys [db]} {:keys [uri]}]
  (try
    (mapv (fn [{:keys [text]}]
            {:type :mcpResource
             :uri uri
             :content text})
          (:contents (get-resource! uri db)))
    (catch Exception e
      (logger/warn logger-tag (format "Error getting MCP resource %s: %s" uri (.getMessage e)))
      [])))

(defn shutdown!
  "Shutdown MCP servers: interrupts in-flight init threads and disconnects
   running clients in parallel with a total 5s timeout.
   HTTP clients are force-stopped (skipping the slow DELETE handshake),
   while stdio clients go through graceful disconnect with a timeout fallback."
  [db*]
  ;; 1. Interrupt any servers still initializing so they unblock promptly
  (interrupt-init-threads!)
  ;; 2. Disconnect running clients in parallel via daemon threads
  (try
    (let [clients (vals (:mcp-clients @db*))
          latch (java.util.concurrent.CountDownLatch. (count clients))
          threads (doall
                   (map (fn [{:keys [client http-client]}]
                          (doto (Thread.
                                 (fn []
                                   (try
                                     (if http-client
                                         ;; HTTP: force-stop (the DELETE in disconnect! always
                                         ;; times out because the server is slow to respond)
                                       (pp/stop! http-client)
                                         ;; stdio: graceful disconnect
                                       (pmc/disconnect! client))
                                     (catch Exception _)
                                     (finally
                                       (.countDown latch)))))
                            (.setDaemon true)
                            (.start)))
                        clients))]
      (when-not (.await latch disconnect-timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
        (logger/warn logger-tag "Some MCP servers did not disconnect within timeout, forcing stop")
        (doseq [^Thread t threads]
          (.interrupt t))))
    (catch Exception _ nil))
  (swap! db* assoc :mcp-clients {}))

;; --- The module's lifecycle and protocol methods, telling clients about the servers

(defn ^:private notify-server-updated [metrics messenger tool-status-fn server]
  (metrics/count-up! "mcp-server-status" {:name (:name server)
                                          :status (:status server)} metrics)
  (messenger/tool-server-updated messenger (-> server
                                               (assoc :type :mcp)
                                               (update :tools
                                                       #(mapv (comp tool-status-fn
                                                                    (fn [t] (assoc t :server {:name (:name server)})))
                                                              %)))))

(defn ^:private notify-server-removed [metrics messenger params]
  (metrics/count-up! "mcp-server-status" {:name (:name params)
                                          :status "removed"} metrics)
  (messenger/tool-server-removed messenger params))

(defn ^:private notifiers
  "The callbacks that tell clients about the servers: `:on-server-updated`,
   with the tools' status for `agent-name` (nil: the global one), and
   `:on-server-removed`."
  [{:keys [messenger metrics config]} agent-name]
  (let [tool-status-fn (f.tools/make-tool-status-fn config agent-name)]
    {:on-server-updated (partial notify-server-updated metrics messenger tool-status-fn)
     :on-server-removed (partial notify-server-removed metrics messenger)}))

(defn ^:private start-servers!
  "Starts the configured servers that don't run yet."
  [{:keys [db* metrics config] :as ctx}]
  (initialize-servers-async! (notifiers ctx (get config :defaultAgent)) db* config metrics))

(defn ^:private stop-servers!
  "Stops every server, and tells clients they're gone."
  [{:keys [db* config] :as ctx}]
  (let [{:keys [on-server-updated on-server-removed]} (notifiers ctx nil)
        server-names (distinct (concat (keys (:mcp-clients @db*))
                                       (map name (keys (:mcpServers config)))))]
    (->> server-names
         (mapv (fn [server-name]
                 (future
                   (try
                     (stop-server! server-name db* config {:on-server-updated on-server-updated})
                     (on-server-removed {:name server-name})
                     (catch Exception e
                       (logger/error logger-tag (format "Could not stop MCP server '%s': %s"
                                                        server-name (.getMessage e))))))))
         (run! deref))))

(defn ^:private start
  "`:start`: once the session is ready, the servers start; before that,
   `:session/ready` starts them."
  [ctx]
  (when (modules/session-ready?)
    (start-servers! ctx))
  nil)

(defn ^:private stop
  "`:stop`: ECA shutting down shuts the servers down; the module disabled
   stops them, telling clients."
  [{:keys [db*] :as ctx}]
  (if (:stopping @db*)
    (shutdown! db*)
    (stop-servers! (update ctx :config #(or % (config/all @db*))))))

(defn ^:private session-ready
  "`:session/ready` handler: the servers start, after the sessionStart hooks
   and ECA's own tool server, as before modules."
  [ctx _event]
  (start-servers! ctx)
  nil)

(defn ^:private config-changed
  "`:config/changed` handler: an `mcpServers` change adds, removes or
   restarts those servers, when the module was active already; else its
   `:start` starts them all."
  [{:keys [db* metrics] :as ctx} {:keys [prev-config config]}]
  (when (and prev-config (modules/active? prev-config :eca/mcp))
    (reconcile-servers! prev-config config db* metrics
                        (notifiers (assoc ctx :config config) (get config :defaultAgent))))
  nil)

(defn stop-server-method
  "`mcp/stopServer`."
  [{:keys [db* metrics config] :as ctx} params]
  (metrics/task metrics :eca/mcp-stop-server
    (stop-server! (:name params) db* config (notifiers ctx nil))))

(defn start-server-method
  "`mcp/startServer`."
  [{:keys [db* metrics config] :as ctx} params]
  (metrics/task metrics :eca/mcp-start-server
    (start-server! (:name params) db* config metrics (notifiers ctx nil))))

(defn connect-server-method
  "`mcp/connectServer`."
  [{:keys [db* metrics config] :as ctx} params]
  (metrics/task metrics :eca/mcp-connect-server
    (connect-server! (:name params) db* config metrics (notifiers ctx nil))))

(defn logout-server-method
  "`mcp/logoutServer`."
  [{:keys [db* metrics config] :as ctx} params]
  (metrics/task metrics :eca/mcp-logout-server
    (logout-server! (:name params) db* config metrics (notifiers ctx nil))))

(defn update-server-method
  "`mcp/updateServer`."
  [{:keys [db* metrics config] :as ctx} params]
  (metrics/task metrics :eca/mcp-update-server
    (let [server-name (:name params)
          server-fields (cond-> {}
                          (:command params) (assoc :command (:command params))
                          (:args params) (assoc :args (:args params))
                          (:url params) (assoc :url (:url params))
                          (:env params) (assoc :env (:env params))
                          (:headers params) (assoc :headers (:headers params)))]
      (update-server! server-name server-fields db* config metrics (notifiers ctx nil))
      {})))

(defn disable-server-method
  "`mcp/disableServer`."
  [{:keys [db* metrics config] :as ctx} params]
  (metrics/task metrics :eca/mcp-disable-server
    (disable-server! (:name params) db* config (notifiers ctx nil))))

(defn enable-server-method
  "`mcp/enableServer`."
  [{:keys [db* metrics] :as ctx} params]
  (metrics/task metrics :eca/mcp-enable-server
    (enable-server! (:name params) db* metrics (notifiers ctx nil))))

(defn ^:private ->scope-keyword [scope]
  (cond
    (nil? scope) :global
    (keyword? scope) scope
    (string? scope) (keyword scope)
    :else :global))

(defn add-server-method
  "`mcp/addServer`: add a new MCP server definition at runtime.

   Params:
     :name           (required) server identifier.
     :command :args :env           for stdio servers.
     :url :headers :clientId :clientSecret :oauthPort  for HTTP servers.
     :disabled       optional boolean.
     :scope          \"global\" (default) or \"workspace\".
     :workspaceUri   required when :scope = \"workspace\".

   Returns the canonical server map (as sent over tool/serverUpdated) with
   status :starting, :disabled, or :failed."
  [{:keys [db* metrics config] :as ctx} params]
  (metrics/task metrics :eca/mcp-add-server
    (let [server-name (:name params)
          server-config (cond-> {}
                          (:command params)       (assoc :command (:command params))
                          (:args params)          (assoc :args (:args params))
                          (:env params)           (assoc :env (:env params))
                          (:url params)           (assoc :url (:url params))
                          (:headers params)       (assoc :headers (:headers params))
                          (:clientId params)      (assoc :clientId (:clientId params))
                          (:clientSecret params)  (assoc :clientSecret (:clientSecret params))
                          (:oauthPort params)     (assoc :oauthPort (:oauthPort params))
                          (contains? params :disabled) (assoc :disabled (boolean (:disabled params))))
          opts {:scope (->scope-keyword (or (:scope params) (:workspaceScope params)))
                :workspace-uri (or (:workspaceUri params) (:workspace-uri params))}]
      (try
        (let [server (add-server! server-name server-config opts db* config metrics (notifiers ctx nil))]
          {:server server})
        (catch clojure.lang.ExceptionInfo e
          {:error {:code "invalid_request"
                   :message (.getMessage e)
                   :data (ex-data e)}})))))

(defn remove-server-method
  "`mcp/removeServer`: remove an MCP server definition at runtime. Stops the
   server if running, removes the entry from the owning config file, and
   emits tool/serverRemoved.

   Params:
     :name  (required)"
  [{:keys [db* metrics config] :as ctx} params]
  (metrics/task metrics :eca/mcp-remove-server
    (try
      (remove-server! (:name params) db* config (notifiers ctx nil))
      (catch clojure.lang.ExceptionInfo e
        {:error {:code "invalid_request"
                 :message (.getMessage e)
                 :data (ex-data e)}}))))

(defn ^:private mcp-instructions-section [db]
  (let [servers-with-instructions
        (->> (:mcp-clients db)
             (keep (fn [[server-name {:keys [status instructions]}]]
                     (when (and (= :running status)
                                (not (string/blank? instructions)))
                       {:name (name server-name)
                        :instructions instructions})))
             seq)]
    (when servers-with-instructions
      (multi-str
       "## MCP Server Instructions"
       ""
       (format "<mcp-server-instructions%s>" (f.prompt/attr-str {:description "Instructions from running MCP servers."}))
       (->> servers-with-instructions
            (map (fn [{:keys [name instructions]}]
                   (multi-str
                    (format "<mcp-server-instruction%s>" (f.prompt/attr-str {:name name}))
                    instructions
                    "</mcp-server-instruction>")))
            (string/join "\n\n"))
       "</mcp-server-instructions>"))))

(defn mcp-resources-section
  "`:eca/mcp-resources`: the MCP resources attached to the chat."
  [{:keys [refined-contexts]}]
  (let [resources (filter #(= :mcpResource (:type %)) refined-contexts)]
    (when (seq resources)
      ["## MCP Resources"
       ""
       (f.prompt/contexts-str (map (fn [{:keys [uri content]}]
                                     (format "<resource%s>%s</resource>" (f.prompt/attr-str {:uri uri}) content))
                                   resources)
                              nil
                              {:description "Resources from connected MCP servers."})
       ""])))

(defn mcp-instructions-prompt-section
  "`:eca/mcp-instructions`: the running MCP servers' instructions."
  [{:keys [db]}]
  (mcp-instructions-section db))

(def module
  {:id :eca/mcp
   :description "MCP servers: their tools, prompts, resources and instructions."
   ;; servers start when the session is ready and follow config changes, as before;
   ;; while the module is inactive no server runs and their tools and prompts are hidden
   :config-spec {:mcpServers {:type :map :default {}}
                 :mcpTimeoutSeconds {:type :number :default 60}
                 :mcpKeepAliveSeconds {:type :number :default 30}}
   :start #'start
   :stop #'stop
   :event-handlers {:session/ready #'session-ready
                    :config/changed #'config-changed}
   :rpc {"mcp/stopServer"    {:kind :notification :handler #'stop-server-method}
         "mcp/startServer"   {:kind :notification :handler #'start-server-method}
         "mcp/connectServer" {:kind :notification :handler #'connect-server-method}
         "mcp/logoutServer"  {:kind :notification :handler #'logout-server-method}
         "mcp/disableServer" {:kind :notification :handler #'disable-server-method}
         "mcp/enableServer"  {:kind :notification :handler #'enable-server-method}
         "mcp/updateServer"  {:kind :request :handler #'update-server-method}
         "mcp/addServer"     {:kind :request :handler #'add-server-method}
         "mcp/removeServer"  {:kind :request :handler #'remove-server-method}}
   ;; the servers' tools
   :tool-servers {:list #'tool-servers :call #'call-server-tool :await-refresh #'await-servers-refresh}
   :system-prompts [{:id :eca/mcp-resources :dynamic? true :order 100 :content #'mcp-resources-section}
                    {:id :eca/mcp-instructions :dynamic? true :order 200 :content #'mcp-instructions-prompt-section}]
   ;; resources, for the @ list and the contexts clients send
   :contexts {:types {"mcpResource" {:list #'resource-items :refine #'refine-resource}}}
   ;; each prompt as a command, listed first
   :commands #'commands})
