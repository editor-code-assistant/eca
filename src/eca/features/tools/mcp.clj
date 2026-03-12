(ns eca.features.tools.mcp
  (:require
   [clojure.java.browse :as browse]
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
   [plumcp.core.support.http-client :as phc])
  (:import
   [java.io IOException]))

(set! *warn-on-reflection* true)

;; TODO create tests for this ns

(def ^:private logger-tag "[MCP]")

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

(defn ^:private ->transport [server-name server-config workspaces db]
  (if (:url server-config)
    ;; HTTP Streamable transport
    (let [url (replace-env-vars (:url server-config))
          config-headers (:headers server-config)
          ssl-ctx network/*ssl-context*
          rm (fn [request]
               (-> request
                   (update :headers merge
                           (into {} (map (fn [[k v]]
                                           [(name k) (replace-env-vars (str v))]))
                                 config-headers))
                   (update :headers merge
                           (when-let [access-token (get-in db [:mcp-auth server-name :access-token])]
                             {"Authorization" (str "Bearer " access-token)}))))
          hc (phc/make-http-client url (cond-> {:request-middleware rm}
                                         ssl-ctx (assoc :ssl-context ssl-ctx)))]
      (when (string/includes? url "/sse")
        (logger/warn logger-tag (format "SSE transport is no longer supported for server '%s'. Using Streamable HTTP instead. Consider updating the URL." server-name)))
      (logger/info logger-tag (format "Creating HTTP transport for server '%s' at %s" server-name url))
      (phct/make-streamable-http-transport hc))

    ;; STDIO transport
    (let [{:keys [command args env]} server-config
          work-dir (or (some-> workspaces
                               first
                               :uri
                               shared/uri->filename)
                       (config/get-property "user.home"))]
      (psct/run-command
        {:command-tokens (into [(replace-env-vars command)]
                               (map replace-env-vars)
                               (or args []))
         :dir work-dir
         :env (when env (update-keys env name))
         :on-stderr-text (fn [msg]
                           (logger/info logger-tag (format "[%s] %s" server-name msg)))}))))


(defn ^:private ->client [name transport init-timeout workspaces
                          {:keys [on-tools-change]}]
  (let [tools-consumer (fn [tools]
                         (logger/info logger-tag
                                      (format "[%s] Tools list changed, received %d tools"
                                              name (count tools)))
                         (on-tools-change tools))
        tools-nhandler (pcs/wrap-initialized-check
                        (fn [jsonrpc-notification]
                          (pcs/fetch-tools jsonrpc-notification
                                           {:on-tools tools-consumer})))
        client (pmc/make-mcp-client
                {:info (pes/make-info name "current")
                 :client-transport transport
                 :primitives {:roots (mapv #(pcap/make-root-item (:uri %)
                                                                 {:name (:name %)})
                                           workspaces)}
                 :notification-handlers
                 {psd/method-notifications-tools-list_changed tools-nhandler
                  psd/method-notifications-message (fn [params]
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

(defn ^:private on-list-error [kind]
  (fn [_id jsonrpc-error]
    (logger/warn logger-tag (format "Could not list %s: %s" kind (:message jsonrpc-error)))
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
  [{:keys [authorization-endpoint callback-port] :as oauth-info}
   server-name
   db*
   server-config
   {:keys [on-success on-error on-server-updated]}]
  (oauth/start-oauth-server!
   {:port callback-port
    :on-success (fn [{:keys [code]}]
                  (let [{:keys [access-token refresh-token expires-at]} (oauth/authorize-token! oauth-info code)]
                    (swap! db* assoc-in [:mcp-auth server-name] {:type :auth/oauth
                                                                 :url (:url server-config)
                                                                 :refresh-token refresh-token
                                                                 :access-token access-token
                                                                 :expires-at expires-at}))
                  (oauth/stop-oauth-server! callback-port)
                  (on-success))
    :on-error (fn [error]
                (oauth/stop-oauth-server! callback-port)
                (on-error error))})
  (logger/info logger-tag (format "Authenticating MCP server '%s' at '%s'" server-name authorization-endpoint))
  (swap! db* assoc-in [:mcp-clients server-name] {:status :requires-auth})
  (browse/browse-url authorization-endpoint)
  (on-server-updated (->server server-name server-config :requires-auth @db*)))

(defn ^:private token-expired?
  "Check if the token is expired or will expire in the next 60 seconds."
  [expires-at]
  (when expires-at
    (< expires-at (+ (quot (System/currentTimeMillis) 1000) 60))))

(defn ^:private try-refresh-token!
  "Attempt to refresh an MCP server's OAuth token.
   Returns true if refresh succeeded, false otherwise."
  [name db* url metrics]
  (let [mcp-auth (get-in @db* [:mcp-auth name])
        {:keys [refresh-token]} mcp-auth]
    (when refresh-token
      (logger/info logger-tag (format "Attempting to refresh token for MCP server '%s'" name))
      (when-let [oauth-info (oauth/oauth-info (replace-env-vars url))]
        (when-let [new-tokens (oauth/refresh-token!
                               (:token-endpoint oauth-info)
                               (:client-id oauth-info)
                               refresh-token)]
          (logger/info logger-tag (format "Successfully refreshed token for MCP server '%s'" name))
          (swap! db* assoc-in [:mcp-auth name]
                 (merge mcp-auth new-tokens))
          (db/update-global-cache! @db* metrics)
          true)))))

(def ^:private max-init-retries 3)

(defn ^:private transient-http-error?
  "Checks if the exception root cause is a transient HTTP error (e.g. chunked
   encoding EOF) that warrants a retry. This can happen when infrastructure
   (load balancers, proxies) closes HTTP streaming connections."
  [^Exception e]
  (let [cause (.getCause e)]
    (and (instance? IOException cause)
         (when-let [msg (.getMessage cause)]
           (or (string/includes? msg "chunked transfer encoding")
               (string/includes? msg "EOF reached while reading"))))))

(defn ^:private initialize-server! [name db* config metrics on-server-updated]
  (let [db @db*
        server-config (get-in config [:mcpServers name])]
    (on-server-updated (->server name server-config :starting db))
    (try
      (let [workspaces (:workspace-folders @db*)
            url (:url server-config)
            ;; Skip OAuth entirely if Authorization header is configured
            has-static-auth? (some-> server-config :headers :Authorization some?)
            mcp-auth (get-in @db* [:mcp-auth name])
            ;; Invalidate cached credentials when URL changed
            mcp-auth (when (= url (:url mcp-auth)) mcp-auth)
            has-token? (some? (:access-token mcp-auth))
            token-expired? (token-expired? (:expires-at mcp-auth))
            ;; Try to refresh if token exists but is expired
            refresh-succeeded? (when (and has-token? token-expired?)
                                 (try-refresh-token! name db* url metrics))
            ;; Only get oauth-info if we don't have a token or refresh failed, and no static auth header
            needs-oauth? (and (not has-static-auth?)
                              (or (not has-token?)
                                  (and token-expired? (not refresh-succeeded?))))
            oauth-info (when (and url needs-oauth?)
                         (oauth/oauth-info (replace-env-vars url)))]
        (if oauth-info
          (initialize-mcp-oauth oauth-info
                                name
                                db*
                                server-config
                                {:on-server-updated on-server-updated
                                 :on-success (fn []
                                               ;; :mcp-auth exists now
                                               (db/update-global-cache! @db* metrics)
                                               (initialize-server! name db* config metrics on-server-updated))
                                 :on-error (fn [error]
                                             (logger/error logger-tag error)
                                             (swap! db* assoc-in [:mcp-clients name :status] :failed)
                                             (on-server-updated (->server name server-config :failed db)))})
          (let [init-timeout (:mcpTimeoutSeconds config)
                on-tools-change (fn [tools]
                                  (let [tools (mapv tool->internal tools)]
                                    (swap! db* assoc-in [:mcp-clients name :tools] tools)
                                    (on-server-updated (->server name server-config :running @db*))))]
            (loop [attempt 1]
              (let [transport (->transport name server-config workspaces db)
                    result (try
                             (let [client (->client name transport init-timeout workspaces
                                                    {:on-tools-change on-tools-change})
                                   init-result (pmc/get-initialize-result client)
                                   version (get-in init-result [:serverInfo :version])]
                               (swap! db* assoc-in [:mcp-clients name] {:client client :status :starting})
                               (swap! db* assoc-in [:mcp-clients name :version] version)
                               (swap! db* assoc-in [:mcp-clients name :tools] (list-server-tools client))
                               (swap! db* assoc-in [:mcp-clients name :prompts] (list-server-prompts client))
                               (swap! db* assoc-in [:mcp-clients name :resources] (list-server-resources client))
                               (swap! db* assoc-in [:mcp-clients name :status] :running)
                               (on-server-updated (->server name server-config :running @db*))
                               (logger/info logger-tag (format "Started MCP server %s" name))
                               :ok)
                             (catch Exception e
                               (try (pp/stop-client-transport! transport false) (catch Exception _))
                               (if (and (transient-http-error? e) (< attempt max-init-retries))
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
      (catch Exception e
        (logger/error logger-tag (format "Unexpected error initializing MCP server %s: %s" name (.getMessage e)))
        (swap! db* assoc-in [:mcp-clients name :status] :failed)
        (on-server-updated (->server name server-config :failed @db*))))))

(defn initialize-servers-async! [{:keys [on-server-updated]} db* config metrics]
  (let [db @db*]
    (doseq [[name-kwd server-config] (:mcpServers config)]
      (let [name (name name-kwd)]
        (when-not (get-in db [:mcp-clients name])
          (if (get server-config :disabled false)
            (on-server-updated (->server name server-config :disabled db))
            (future
              (initialize-server! name db* config metrics on-server-updated))))))))

(defn stop-server! [name db* config {:keys [on-server-updated]}]
  (when-let [{:keys [client]} (get-in @db* [:mcp-clients name])]
    (let [server-config (get-in config [:mcpServers name])]
      (swap! db* assoc-in [:mcp-clients name :status] :stopping)
      (on-server-updated (->server name server-config :stopping @db*))
      (pmc/disconnect! client)
      (swap! db* assoc-in [:mcp-clients name :status] :stopped)
      (on-server-updated (->server name server-config :stopped @db*))
      (swap! db* update :mcp-clients dissoc name)
      (logger/info logger-tag (format "Stopped MCP server %s" name)))))

(defn start-server! [name db* config metrics {:keys [on-server-updated]}]
  (when-let [server-config (get-in config [:mcpServers name])]
    (when (get server-config :disabled false)
      (logger/info logger-tag (format "Starting MCP server %s from manual request despite :disabled=true" name)))
    (initialize-server! name db* config metrics on-server-updated)))

(defn all-tools [db]
  (into []
        (mapcat (fn [[name {:keys [tools version]}]]
                  (map #(assoc % :server {:name name
                                          :version version}) tools)))
        (:mcp-clients db)))

(defn call-tool! [name arguments {:keys [db]}]
  (if-let [mcp-client (->> (vals (:mcp-clients db))
                           (keep (fn [{:keys [client tools]}]
                                   (when (some #(= name (:name %)) tools)
                                     client)))
                           first)]
    ;; Synchronize on the client to prevent concurrent tool calls to the same MCP server
    (locking mcp-client
      (if-let [result (->> {:on-error (fn [_id jsonrpc-error]
                                        (logger/warn logger-tag "Error calling tool:" (:message jsonrpc-error))
                                        nil)}
                           (pmc/call-tool mcp-client name arguments))]
        {:error (:isError result)
         :contents (into [] (keep ->content) (:content result))}
        {:error true
         :contents nil}))
    {:error true
     :contents nil}))

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
  (when-let [mcp-client (->> (vals (:mcp-clients db))
                             (keep (fn [{:keys [client prompts]}]
                                     (when (some #(= name (:name %)) prompts)
                                       client)))
                             first)]
    (when-let [prompt (->> {:on-error (fn [_id jsonrpc-error]
                                        (logger/warn logger-tag "Error getting prompt:" (:message jsonrpc-error)))}
                           (pmc/get-prompt mcp-client name arguments))]
      {:description (:description prompt)
       :messages (mapv (fn [each-message]
                         {:role (string/lower-case (:role each-message))
                          :content [(->content (:content each-message))]})
                       (:messages prompt))})))

(defn get-resource! [uri db]
  (when-let [mcp-client (->> (vals (:mcp-clients db))
                             (keep (fn [{:keys [client resources]}]
                                     (when (some #(= uri (:uri %)) resources)
                                       client)))
                             first)]
    (when-let [resource (->> {:on-error (fn [_id jsonrpc-error]
                                          (logger/warn logger-tag "Error reading resource:" (:message jsonrpc-error)))}
                             (pmc/read-resource mcp-client uri))]
      {:contents (mapv ->resource-content (:contents resource))})))

(defn shutdown!
  "Shutdown MCP servers in parallel waiting max 5s in total."
  [db*]
  (try
    (let [clients (vals (:mcp-clients @db*))
          futures (doall
                   (pmap (fn [{:keys [client]}]
                           (future
                             (try (pmc/disconnect! client)
                                  (catch Exception _ nil))))
                         clients))]
      (doseq [f futures]
        (try (deref f 5000 nil) (catch Exception _ nil))))
    (catch Exception _ nil))
  (swap! db* assoc :mcp-clients {}))