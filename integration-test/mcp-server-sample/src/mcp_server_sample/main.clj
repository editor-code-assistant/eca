(ns mcp-server-sample.main
  (:require
   [mcp-server-sample.mcp :as mcp]
   [mcp-server-sample.util :refer [log]])
  (:import
   [io.modelcontextprotocol.json McpJsonMapper]
   [io.modelcontextprotocol.server McpServer]
   [io.modelcontextprotocol.server.transport StdioServerTransportProvider]
   [io.modelcontextprotocol.spec McpSchema$ServerCapabilities]))

(def prompts
  [{:name "my-prompt"
    :description "Do something cool"
    :arguments [{:name "some-arg-1"
                 :description "Some arg 1"
                 :required true}]
    :handler (constantly "Do something really cool")}])

(defn create-server []
  (let [transport-provider (StdioServerTransportProvider. (McpJsonMapper/getDefault))
        server (-> (McpServer/sync transport-provider)
                   (.serverInfo "ECA MCP Sample" "0.1.0")
                   (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                      (.tools true)
                                      (.resources false false)
                                      (.prompts true)
                                      (.logging)
                                      (.build)))
                   (.build))]
    ;; (doseq [tool tools]
    ;;   (add-tool tool components server))

    (doseq [prompt prompts]
      (let [prompt-spec (mcp/create-prompt-specification prompt)]
        (.addPrompt server prompt-spec)))

    (log "MCP server sample started")

    server))

(defn -main [& _args]
  (with-open [_server (create-server)]
    @(promise)))
