(ns eca.features.tools.mcp
  (:require
   [cheshire.core :as json]
   [clojure.java.browse :as browse]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.logger :as logger]
   [eca.oauth :as oauth]
   [eca.shared :as shared])
  (:import
   [com.fasterxml.jackson.databind ObjectMapper]
   [io.modelcontextprotocol.client McpClient McpSyncClient]
   [io.modelcontextprotocol.client.transport HttpClientStreamableHttpTransport ServerParameters StdioClientTransport]
   [io.modelcontextprotocol.client.transport.customizer McpSyncHttpClientRequestCustomizer]
   [io.modelcontextprotocol.json McpJsonMapper]
   [io.modelcontextprotocol.spec
    McpSchema$CallToolRequest
    McpSchema$CallToolResult
    McpSchema$ClientCapabilities
    McpSchema$Content
    McpSchema$GetPromptRequest
    McpSchema$LoggingMessageNotification
    McpSchema$Prompt
    McpSchema$PromptArgument
    McpSchema$PromptMessage
    McpSchema$ReadResourceRequest
    McpSchema$Resource
    McpSchema$ResourceContents
    McpSchema$Root
    McpSchema$TextContent
    McpSchema$TextResourceContents
    McpSchema$Tool
    McpTransport]
   [java.net URI]
   [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
   [java.time Duration]
   [java.util List Map]
   [java.util.concurrent TimeoutException]))

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

(defn ^:private url->base-url
  "Extract the base URL (scheme + host + port) from a full URL.
   E.g., 'https://api.example.com/v1/mcp' -> 'https://api.example.com'"
  [^String url]
  (let [uri (URI. url)]
    (str (.getScheme uri) "://" (.getHost uri)
         (when (pos? (.getPort uri))
           (str ":" (.getPort uri))))))

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

(defn ^:private fetch-oauth-metadata
  "Fetch OAuth authorization server metadata from /.well-known/oauth-authorization-server.
   Returns a map with :authorization_endpoint, :token_endpoint, etc. or nil on failure."
  [^String base-url]
  (try
    (let [metadata-url (str base-url "/.well-known/oauth-authorization-server")
          client (HttpClient/newHttpClient)
          request (-> (HttpRequest/newBuilder)
                      (.uri (URI. metadata-url))
                      (.timeout (Duration/ofSeconds 10))
                      (.header "Accept" "application/json")
                      (.GET)
                      (.build))
          response (.send client request (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)]
      (when (= 200 status)
        (json/parse-string (.body response) true)))
    (catch Exception e
      (logger/debug logger-tag "Could not fetch OAuth metadata:" (.getMessage e))
      nil)))

(defn ^:private fetch-protected-resource-metadata
  "Fetch OAuth protected resource metadata from a resource_metadata URL.
   Returns a map with :authorization_servers, :scopes_supported, etc. or nil on failure."
  [^String resource-metadata-url]
  (try
    (let [client (HttpClient/newHttpClient)
          request (-> (HttpRequest/newBuilder)
                      (.uri (URI. resource-metadata-url))
                      (.timeout (Duration/ofSeconds 10))
                      (.header "Accept" "application/json")
                      (.GET)
                      (.build))
          response (.send client request (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)]
      (when (= 200 status)
        (json/parse-string (.body response) true)))
    (catch Exception e
      (logger/debug logger-tag "Could not fetch protected resource metadata:" (.getMessage e))
      nil)))

(defn ^:private ->transport ^McpTransport [server-name server-config workspaces]
  (if (:url server-config)
    ;; HTTP Streamable transport
    (let [url (replace-env-vars (:url server-config))]
      (-> (HttpClientStreamableHttpTransport/builder url)
          (.httpRequestCustomizer (reify McpSyncHttpClientRequestCustomizer
                                    (customize [_this builder method endpoint body context]
                                      (logger/info "-->" method endpoint body context))))
          (.build)))

    ;; STDIO transport
    (let [{:keys [command args env]} server-config
          command ^String (replace-env-vars command)
          b (ServerParameters/builder command)
          b (if args
              (.args b ^List (mapv replace-env-vars (or args [])))
              b)
          b (if env
              (.env b (update-keys env name))
              b)
          pb-init-args []
          ;; TODO we are hard coding the first workspace
          work-dir (or (some-> workspaces
                               first
                               :uri
                               shared/uri->filename)
                       (config/get-property "user.home"))
          stdio-transport (proxy [StdioClientTransport] [(.build b) (McpJsonMapper/getDefault)]
                            (getProcessBuilder [] (-> (ProcessBuilder. ^List pb-init-args)
                                                      (.directory (io/file work-dir)))))]
      (.setStdErrorHandler stdio-transport (fn [msg]
                                             (logger/info logger-tag (format "[%s] %s" server-name msg))))
      stdio-transport)))

(defn ^:private ->client ^McpSyncClient [name transport init-timeout workspaces]
  (-> (McpClient/sync transport)
      (.requestTimeout (Duration/ofHours 10)) ;; required any value for initializationTimeout work
      (.initializationTimeout (Duration/ofSeconds init-timeout))
      (.capabilities (-> (McpSchema$ClientCapabilities/builder)
                         (.roots true)
                         (.build)))
      (.roots ^List (mapv #(McpSchema$Root. (:uri %) (:name %)) workspaces))
      (.loggingConsumer (fn [^McpSchema$LoggingMessageNotification notification]
                          (logger/info logger-tag (str "[MCP-" name "]") (.data notification))))
      (.build)))

(defn ^:private ->server [mcp-name server-config status db]
  {:name (name mcp-name)
   :command (:command server-config)
   :args (:args server-config)
   :url (:url server-config)
   :tools (get-in db [:mcp-clients mcp-name :tools])
   :prompts (get-in db [:mcp-clients mcp-name :prompts])
   :resources (get-in db [:mcp-clients mcp-name :resources])
   :status status})

(defn ^:private ->content [^McpSchema$Content content-client]
  (case (.type content-client)
    "text" {:type :text
            :text (.text ^McpSchema$TextContent content-client)}
    nil))

(defn ^:private ->resource-content [^McpSchema$ResourceContents resource-content-client]
  (cond
    (instance? McpSchema$TextResourceContents resource-content-client)
    {:type :text
     :uri (.uri resource-content-client)
     :text (.text ^McpSchema$TextResourceContents resource-content-client)}

    :else
    nil))

(defn ^:private list-server-tools [^ObjectMapper obj-mapper ^McpSyncClient client]
  (try
    (when (.tools (.getServerCapabilities client))
      (mapv (fn [^McpSchema$Tool tool-client]
              {:name (.name tool-client)
               :description (.description tool-client)
               ;; We convert to json to then read so we have a clojure map
               ;; TODO avoid this converting to clojure map directly
               :parameters (json/parse-string (.writeValueAsString obj-mapper (.inputSchema tool-client)) true)})
            (.tools (.listTools client))))
    (catch Exception e
      (logger/warn logger-tag "Could not list tools:" (.getMessage e))
      [])))

(defn ^:private list-server-prompts [^McpSyncClient client]
  (try
    (when (.prompts (.getServerCapabilities client))
      (mapv (fn [^McpSchema$Prompt prompt-client]
              {:name (.name prompt-client)
               :description (.description prompt-client)
               :arguments (mapv (fn [^McpSchema$PromptArgument content]
                                  {:name (.name content)
                                   :description (.description content)
                                   :required (.required content)})
                                (.arguments prompt-client))})
            (.prompts (.listPrompts client))))
    (catch Exception e
      (logger/warn logger-tag "Could not list prompts:" (.getMessage e))
      [])))

(defn ^:private list-server-resources [^McpSyncClient client]
  (try
    (when (.resources (.getServerCapabilities client))
      (mapv (fn [^McpSchema$Resource resource-client]
              {:uri (.uri resource-client)
               :name (.name resource-client)
               :description (.description resource-client)
               :mime-type (.mimeType resource-client)})
            (.resources (.listResources client))))
    (catch Exception e
      (logger/warn logger-tag "Could not list resources:" (.getMessage e))
      [])))

(defn ^:private initialize-server! [name db* config on-server-updated]
  (let [db @db*
        workspaces (:workspace-folders @db*)
        server-config (get-in config [:mcpServers name])
        url (:url server-config)
        {:keys [authorization-endpoint] :as oauth-info} (when url (oauth/oauth-start-info (replace-env-vars url)))]
    ;; For HTTP servers, check if OAuth authentication is required before initializing
    (when oauth-info
      (logger/info logger-tag (format "Authenticating MCP server '%s' at '%s'" name authorization-endpoint))
      (swap! db* assoc-in [:mcp-clients name] {:status :requires-auth
                                               :oauth-info oauth-info})
      (oauth/start-oauth-server!
       {:on-success (fn [res]
                      (logger/info "============>" res))
        :on-error (fn [error]
                    (logger/info "---------> " error))})
      (browse/browse-url authorization-endpoint)
      (on-server-updated (->server name server-config :requires-auth db)))
    (let [obj-mapper (ObjectMapper.)
          init-timeout (:mcpTimeoutSeconds config)
          transport (->transport name server-config workspaces)
          client (->client name transport init-timeout workspaces)]
      (on-server-updated (->server name server-config :starting db))
      (swap! db* assoc-in [:mcp-clients name] {:client client :status :starting})
      (try
        (.initialize client)
        (swap! db* assoc-in [:mcp-clients name :version] (.version (.getServerInfo client)))
        (swap! db* assoc-in [:mcp-clients name :tools] (list-server-tools obj-mapper client))
        (swap! db* assoc-in [:mcp-clients name :prompts] (list-server-prompts client))
        (swap! db* assoc-in [:mcp-clients name :resources] (list-server-resources client))
        (swap! db* assoc-in [:mcp-clients name :status] :running)
        (on-server-updated (->server name server-config :running @db*))
        (logger/info logger-tag (format "Started MCP server %s" name))
        (catch Exception e
          (let [cause-message (if (instance? TimeoutException (.getCause e))
                                (format "Timeout of %s secs waiting for server start" init-timeout)
                                (.getMessage (.getCause e)))]
            (logger/error logger-tag (format "Could not initialize MCP server %s: %s: %s" name (.getMessage e) cause-message)))
          (swap! db* assoc-in [:mcp-clients name :status] :failed)
          (on-server-updated (->server name server-config :failed db))
          (.close client))))))

(defn initialize-servers-async! [{:keys [on-server-updated]} db* config]
  (let [db @db*]
    (doseq [[name-kwd server-config] (:mcpServers config)]
      (let [name (name name-kwd)]
        (when-not (get-in db [:mcp-clients name])
          (if (get server-config :disabled false)
            (on-server-updated (->server name server-config :disabled db))
            (future
              (initialize-server! name db* config on-server-updated))))))))

(defn stop-server! [name db* config {:keys [on-server-updated]}]
  (when-let [{:keys [client]} (get-in @db* [:mcp-clients name])]
    (let [server-config (get-in config [:mcpServers name])]
      (swap! db* assoc-in [:mcp-clients name :status] :stopping)
      (on-server-updated (->server name server-config :stopping @db*))
      (.closeGracefully ^McpSyncClient client)
      (swap! db* assoc-in [:mcp-clients name :status] :stopped)
      (on-server-updated (->server name server-config :stopped @db*))
      (swap! db* update :mcp-clients dissoc name)
      (logger/info logger-tag (format "Stopped MCP server %s" name)))))

(defn start-server! [name db* config {:keys [on-server-updated]}]
  (when-let [server-config (get-in config [:mcpServers name])]
    (if (get server-config :disabled false)
      (logger/warn logger-tag (format "MCP server %s is disabled and cannot be started" name))
      (initialize-server! name db* config on-server-updated))))

(defn all-tools [db]
  (into []
        (mapcat (fn [[name {:keys [tools version]}]]
                  (map #(assoc % :server {:name name
                                          :version version}) tools)))
        (:mcp-clients db)))

(defn call-tool! [^String name ^Map arguments {:keys [db]}]
  (let [mcp-client (->> (vals (:mcp-clients db))
                        (keep (fn [{:keys [client tools]}]
                                (when (some #(= name (:name %)) tools)
                                  client)))
                        first)
        ;; Synchronize on the client to prevent concurrent tool calls to the same MCP server
        ^McpSchema$CallToolResult result (locking mcp-client
                                           (.callTool ^McpSyncClient mcp-client
                                                      (McpSchema$CallToolRequest. name arguments)))]
    {:error (.isError result)
     :contents (mapv ->content (.content result))}))

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

(defn get-prompt! [^String name ^Map arguments db]
  (let [mcp-client (->> (vals (:mcp-clients db))
                        (keep (fn [{:keys [client prompts]}]
                                (when (some #(= name (:name %)) prompts)
                                  client)))
                        first)
        prompt (.getPrompt ^McpSyncClient mcp-client (McpSchema$GetPromptRequest. name arguments))]
    {:description (.description prompt)
     :messages (mapv (fn [^McpSchema$PromptMessage message]
                       {:role (string/lower-case (str (.role message)))
                        :content [(->content (.content message))]})
                     (.messages prompt))}))

(defn get-resource! [^String uri db]
  (let [mcp-client (->> (vals (:mcp-clients db))
                        (keep (fn [{:keys [client resources]}]
                                (when (some #(= uri (:uri %)) resources)
                                  client)))
                        first)
        resource (.readResource ^McpSyncClient mcp-client (McpSchema$ReadResourceRequest. uri))]
    {:contents (mapv ->resource-content (.contents resource))}))

(defn shutdown! [db*]
  (doseq [[_name {:keys [client]}] (:mcp-clients @db*)]
    (.closeGracefully ^McpSyncClient client))
  (swap! db* assoc :mcp-clients {}))
