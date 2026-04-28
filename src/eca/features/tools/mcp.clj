(ns eca.features.tools.mcp
  (:require
   [cheshire.core :as json]
   [cheshire.factory :as json.factory]
   [clojure.core.memoize :as memoize]
   [clojure.java.browse :as browse]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.db :as db]
   [eca.logger :as logger]
   [eca.network :as network]
   [eca.oauth :as oauth]
   [eca.shared :as shared]
   [plumcp.core.api.capability :as pcap]
   [plumcp.core.api.entity-support :as pes]
   [plumcp.core.api.mcp-client :as pmc]
   [plumcp.core.client.client-support :as pcs]
   [plumcp.core.client.http-client-transport :as phct]
   [plumcp.core.client.stdio-client-transport :as psct]
   [plumcp.core.protocol :as pp]
   [plumcp.core.schema.schema-defs :as psd]
   [plumcp.core.support.http-client :as phc]
   [rewrite-json.core :as rj])
  (:import
   [java.io IOException]))

(set! *warn-on-reflection* true)

;; TODO create tests for this ns

(def ^:private logger-tag "[MCP]")

(def ^:private init-threads*
  "Tracks in-flight MCP server initialization threads (server-name → Thread)
   so they can be interrupted during shutdown."
  (atom {}))

(defn ^:private register-init-thread! [server-name ^Thread thread]
  (swap! init-threads* assoc server-name thread))

(defn ^:private deregister-init-thread! [server-name]
  (swap! init-threads* dissoc server-name))

(defn ^:private interrupt-init-threads!
  "Interrupt all in-flight init threads to unblock stuck startups."
  []
  (doseq [[server-name ^Thread thread] @init-threads*]
    (logger/info logger-tag (format "Interrupting init thread for server '%s'" server-name))
    (.interrupt thread))
  (reset! init-threads* {}))

(defmacro ^:private with-init-thread
  "Registers the current thread in `init-threads*` for the given server name,
  executes body, and deregisters in a finally block. Allows `stop-server!` and
  `shutdown!` to interrupt in-progress initializations."
  [server-name & body]
  `(let [name# ~server-name]
     (register-init-thread! name# (Thread/currentThread))
     (try
       ~@body
       (finally
         (deregister-init-thread! name#)))))

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
                           (when-let [access-token (get-in @db* [:mcp-auth server-name :access-token])]
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
                       (config/get-property "user.home"))]
      {:transport (psct/run-command
                   {:command-tokens (into [(replace-env-vars command)]
                                          (map replace-env-vars)
                                          (or args []))
                    :dir work-dir
                    :env (when env (update-keys env name))
                    :on-stderr-text (fn [msg]
                                      (logger/info logger-tag (format "[%s] %s" server-name msg)))})
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

(defn ^:private ->client [name transport init-timeout workspaces
                          {:keys [on-tools-change pending-tools-refresh*]}]
  (let [tools-consumer (fn [tools]
                         (logger/info logger-tag
                                      (format "[%s] Tools list changed, received %d tools"
                                              name (count tools)))
                         (on-tools-change tools))
        client (pmc/make-mcp-client
                {:info (pes/make-info "ECA" (config/eca-version) "Editor Code Assistant")
                 :client-transport transport
                 :primitives {:roots (mapv #(pcap/make-root-item (:uri %)
                                                                 {:name (:name %)})
                                           workspaces)}
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
   :has-auth (boolean (get-in db [:mcp-auth mcp-name :access-token]))
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
  "Adapt plumcp tool map to ECA's internal tool shape."
  [tool]
  {:name (:name tool)
   :description (:description tool)
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

(defn ^:private list-server-tools [client]
  (if (get-in (pmc/get-initialize-result client) [:capabilities :tools])
    (or (some->> (pmc/list-tools client {:on-error (on-list-error "tools")})
                 (mapv tool->internal))
        [])
    []))

(defn ^:private list-server-prompts [client]
  (if (get-in (pmc/get-initialize-result client) [:capabilities :prompts])
    (or (pmc/list-prompts client {:on-error (on-list-error "prompts")})
        [])
    []))

(defn ^:private list-server-resources [client]
  (if (get-in (pmc/get-initialize-result client) [:capabilities :resources])
    (or (pmc/list-resources client {:on-error (on-list-error "resources")})
        [])
    []))

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
  [name db* url metrics {:keys [clientId clientSecret oauthPort]}]
  (let [mcp-auth (get-in @db* [:mcp-auth name])
        {:keys [refresh-token]} mcp-auth]
    (when refresh-token
      (logger/info logger-tag (format "Attempting to refresh token for MCP server '%s'" name))
      (when-let [oauth-info (oauth/oauth-info (replace-env-vars url)
                                              (some-> clientId replace-env-vars)
                                              (some-> clientSecret replace-env-vars)
                                              oauthPort)]
        (when-let [new-tokens (oauth/refresh-token!
                               (:token-endpoint oauth-info)
                               (:client-id oauth-info)
                               refresh-token
                               :client-secret (:client-secret oauth-info)
                               :resource (:resource oauth-info))]
          (logger/info logger-tag (format "Successfully refreshed token for MCP server '%s'" name))
          (swap! db* assoc-in [:mcp-auth name]
                 (merge mcp-auth new-tokens))
          (db/update-global-cache! @db* metrics)
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
            mcp-auth (get-in @db* [:mcp-auth name])
            ;; Invalidate cached credentials when base URL changed (ignore query params)
            mcp-auth (when (= (oauth/url-without-query url)
                              (oauth/url-without-query (:url mcp-auth))) mcp-auth)
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
                         (oauth/oauth-info (replace-env-vars url)
                                           (some-> (:clientId server-config) replace-env-vars)
                                           (some-> (:clientSecret server-config) replace-env-vars)
                                           (:oauthPort server-config)))]
        (if oauth-info
          (initialize-mcp-oauth oauth-info
                                name
                                db*
                                server-config
                                {:on-server-updated on-server-updated})
          (let [init-timeout (:mcpTimeoutSeconds config)
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
                                                     :pending-tools-refresh* pending-tools-refresh*})
                                   init-result (pmc/get-initialize-result client)
                                   version (get-in init-result [:serverInfo :version])]
                               (swap! db* assoc-in [:mcp-clients name] (cond-> {:client client
                                                                                :status :starting
                                                                                :needs-reinit?* needs-reinit?*
                                                                                :pending-tools-refresh* pending-tools-refresh*}
                                                                         http-client (assoc :http-client http-client)))
                               (swap! db* assoc-in [:mcp-clients name :version] version)
                               (swap! db* assoc-in [:mcp-clients name :instructions] (:instructions init-result))
                               (swap! db* assoc-in [:mcp-clients name :tools] (list-server-tools client))
                               (swap! db* assoc-in [:mcp-clients name :prompts] (list-server-prompts client))
                               (swap! db* assoc-in [:mcp-clients name :resources] (list-server-resources client))
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
                                     :ok)))
                             (catch Exception e
                               (try (pp/stop-client-transport! transport false) (catch Exception _))
                               (if (and (transient-transport-error? e) (< attempt max-init-retries))
                                 (do
                                   (logger/warn logger-tag (format "Transient HTTP error initializing MCP server %s (attempt %d/%d), retrying: %s"
                                                                   name attempt max-init-retries
                                                                   (some-> e .getCause .getMessage)))
                                   :retry)
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
        (swap! db* assoc-in [:mcp-clients name :status] :failed)
        (on-server-updated (->server name server-config :failed @db*)))
      (catch Exception e
        (logger/error logger-tag (format "Unexpected error initializing MCP server %s: %s" name (.getMessage e)))
        (swap! db* assoc-in [:mcp-clients name :status] :failed)
        (on-server-updated (->server name server-config :failed @db*))))))

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
    (.interrupt init-thread)
    (deregister-init-thread! name))
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
                        (let [{:keys [access-token refresh-token expires-at]} (oauth/authorize-token! oauth-info code)]
                          (swap! db* assoc-in [:mcp-auth name] {:type :auth/oauth
                                                                :url (:url server-config)
                                                                :refresh-token refresh-token
                                                                :access-token access-token
                                                                :expires-at expires-at}))
                        (oauth/stop-oauth-server! callback-port)
                        (db/update-global-cache! @db* metrics)
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

(defn logout-server!
  "Logout from an MCP server by clearing stored OAuth credentials and restarting it."
  [name db* config metrics {:keys [on-server-updated]}]
  (when (get-in config [:mcpServers name])
    (swap! db* update :mcp-auth dissoc name)
    (db/update-global-cache! @db* metrics)
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
    (memoize/memo-clear! config/all)
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
    (memoize/memo-clear! config/all)
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
    (memoize/memo-clear! config/all)
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
      (memoize/memo-clear! config/all)
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
        config-file (resolve-config-file server-name db)]
    (when (get-in db [:mcp-clients server-name :client])
      (stop-server! server-name db* config {:on-server-updated on-server-updated}))
    (swap! db* update :mcp-auth dissoc server-name)
    (update-config-file! config-file
                         #(rj/dissoc-in % ["mcpServers" server-name]))
    (memoize/memo-clear! config/all)
    (on-server-removed {:name server-name})
    {:name server-name :removed true}))

(defn all-tools [db]
  (into []
        (mapcat (fn [[name {:keys [tools version]}]]
                  (map #(assoc % :server {:name name
                                          :version version}) tools)))
        (:mcp-clients db)))

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
   gone server-side), then runs a fresh initialize-server! cycle."
  [server-name old-client db* config metrics]
  (logger/info logger-tag (format "Re-initializing MCP server '%s'" server-name))
  (try (pp/stop-client-transport! (pcs/?transport old-client) false) (catch Exception _))
  (swap! db* update :mcp-clients dissoc server-name)
  (initialize-server! server-name db* config metrics (constantly nil)))

(def ^:private tool-call-timeout-ms 120000)

(defn ^:private tool-call-error [msg]
  {:error true
   :contents [{:type :text :text msg}]})

(defn ^:private reinit-worthy-http-status?
  "HTTP status codes that indicate the session/auth is broken and the server
   connection should be re-initialized (e.g. expired token, server error)."
  [status]
  (or (= 401 (long status))
      (= 403 (long status))
      (<= 500 (long status))))

(defn ^:private do-call-tool
  "Execute a tool call. Delegates timeout handling to plumcp via :timeout-millis.
   All non-200 HTTP errors are returned as JSON-RPC errors by plumcp with
   :plumcp.core/http-status on the error map."
  [mcp-client name arguments needs-reinit?*]
  (locking mcp-client
    (let [error-msg* (atom nil)
          call-opts {:timeout-millis tool-call-timeout-ms
                     :on-error (fn [_id jsonrpc-error]
                                 (let [msg (or (:message jsonrpc-error) "Unknown JSON-RPC error")]
                                   (logger/warn logger-tag "Error calling tool:" (format-jsonrpc-error jsonrpc-error))
                                   (reset! error-msg* msg)
                                   (when-let [http-status (:plumcp.core/http-status jsonrpc-error)]
                                     (when (and needs-reinit?* (reinit-worthy-http-status? http-status))
                                       (logger/warn logger-tag (format "HTTP %d error, flagging for re-initialization" http-status))
                                       (reset! needs-reinit?* true))))
                                 nil)}
          result (try
                   (pmc/call-tool mcp-client name arguments call-opts)
                   (catch Exception e
                     (if (transient-transport-error? e)
                       (do (logger/warn logger-tag (format "Transient transport error, retrying tool call: %s" (.getMessage e)))
                           ::retry)
                       (do (logger/warn logger-tag (format "Error during tool call: %s" (or (.getMessage e) (.getName (class e)))))
                           {::error-msg (or (.getMessage e) (.getName (class e)))}))))]
      (cond
        (= ::retry result)
        nil

        (::error-msg result)
        (tool-call-error (format "MCP server error: %s" (::error-msg result)))

        (map? result)
        {:error (:isError result)
         :contents (into [] (keep ->content) (:content result))}

        :else
        (tool-call-error (or @error-msg* "MCP server returned empty response"))))))

(defn ^:private reinit-and-call-tool! [server-name mcp-client db* config metrics name arguments]
  (reinitialize-server! server-name mcp-client db* config metrics)
  (if-let [new-client (get-in @db* [:mcp-clients server-name :client])]
    (do-call-tool new-client name arguments nil)
    (tool-call-error (format "Failed to re-initialize MCP server '%s'" server-name))))

(defn call-tool! [name arguments {:keys [db db* config metrics]}]
  (if-let [[server-name mcp-client needs-reinit?*]
           (->> (:mcp-clients db)
                (keep (fn [[sn {:keys [client tools needs-reinit?*]}]]
                        (when (some #(= name (:name %)) tools)
                          [sn client needs-reinit?*])))
                first)]
    (if (and needs-reinit?* @needs-reinit?* db* config metrics)
      ;; Already flagged — reinit before attempting the call
      (reinit-and-call-tool! server-name mcp-client db* config metrics name arguments)
      (let [result (do-call-tool mcp-client name arguments needs-reinit?*)]
        (cond
          ;; nil = transient transport error, retry once
          (nil? result)
          (do-call-tool mcp-client name arguments needs-reinit?*)

          ;; Flagged during the call (e.g. HTTP 401/403/5xx) — reinit and retry
          (and (:error result) needs-reinit?* @needs-reinit?* db* config metrics)
          (reinit-and-call-tool! server-name mcp-client db* config metrics name arguments)

          :else result)))
    (tool-call-error (format "Tool '%s' not found in any connected MCP server" name))))

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

(defn get-prompt! [name arguments db]
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
