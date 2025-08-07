(ns eca.features.tools.mcp
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.logger :as logger]
   [eca.shared :as shared])
  (:import
   [com.fasterxml.jackson.databind ObjectMapper]
   [io.modelcontextprotocol.client McpClient McpSyncClient]
   [io.modelcontextprotocol.client.transport ServerParameters StdioClientTransport]
   [io.modelcontextprotocol.spec
    McpSchema$CallToolRequest
    McpSchema$ClientCapabilities
    McpSchema$Content
    McpSchema$GetPromptRequest
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
   [java.time Duration]
   [java.util List Map]))

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

(defn ^:private ->transport ^McpTransport [{:keys [command args env]} workspaces]
  (let [command ^String (replace-env-vars command)
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
                     (config/get-property "user.home"))]
    (proxy [StdioClientTransport] [(.build b)]
      (getProcessBuilder [] (-> (ProcessBuilder. ^List pb-init-args)
                                (.directory (io/file work-dir)))))))

(defn ^:private ->client ^McpSyncClient [transport config]
  (-> (McpClient/sync transport)
      (.requestTimeout (Duration/ofSeconds (:mcpTimeoutSeconds config)))
      (.capabilities (-> (McpSchema$ClientCapabilities/builder)
                         (.roots true)
                         (.build)))
      (.build)))

(defn ^:private ->server [mcp-name server-config status db]
  {:name (name mcp-name)
   :command (:command server-config)
   :args (:args server-config)
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
      (logger/debug logger-tag "Could not list tools:" (.getMessage e))
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
      (logger/debug logger-tag "Could not list prompts:" (.getMessage e))
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
      (logger/debug logger-tag "Could not list resources:" (.getMessage e))
      [])))

(defn ^:private initialize-server! [name db* config on-server-updated]
  (let [db @db*
        workspaces (:workspace-folders @db*)
        server-config (get-in config [:mcpServers (keyword name)])
        obj-mapper (ObjectMapper.)]
    (try
      (let [transport (->transport server-config workspaces)
            client (->client transport config)]
        (on-server-updated (->server name server-config :starting db))
        (swap! db* assoc-in [:mcp-clients name] {:client client})
        (doseq [{:keys [name uri]} workspaces]
          (.addRoot client (McpSchema$Root. uri name)))
        (.initialize client)
        (swap! db* assoc-in [:mcp-clients name :tools] (list-server-tools obj-mapper client))
        (swap! db* assoc-in [:mcp-clients name :prompts] (list-server-prompts client))
        (swap! db* assoc-in [:mcp-clients name :resources] (list-server-resources client))
        (on-server-updated (->server name server-config :running @db*)))
      (catch Exception e
        (logger/warn logger-tag (format "Could not initialize MCP server %s. Error: %s" name (.getMessage e)))
        (on-server-updated (->server name server-config :failed db))))))

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
    (let [server-config (get-in config [:mcpServers (keyword name)])]
      (on-server-updated (->server name server-config :stopping @db*))
      (.closeGracefully ^McpSyncClient client)
      (swap! db* update :mcp-clients dissoc name)
      (on-server-updated (->server name server-config :stopped @db*))
      (logger/info logger-tag (format "Stopped MCP server %s" name)))))

(defn start-server! [name db* config {:keys [on-server-updated]}]
  (when-let [server-config (get-in config [:mcpServers (keyword name)])]
    (if (get server-config :disabled false)
      (logger/warn logger-tag (format "MCP server %s is disabled and cannot be started" name))
      (do
        (initialize-server! name db* config on-server-updated)
        (logger/info logger-tag (format "Started MCP server %s" name))))))

(defn all-tools [db]
  (into []
        (mapcat (fn [[_name {:keys [tools]}]]
                  tools))
        (:mcp-clients db)))

(defn call-tool! [^String name ^Map arguments db]
  (let [mcp-client (->> (vals (:mcp-clients db))
                        (keep (fn [{:keys [client tools]}]
                                (when (some #(= name (:name %)) tools)
                                  client)))
                        first)
        result (.callTool ^McpSyncClient mcp-client
                          (McpSchema$CallToolRequest. name arguments))]
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
