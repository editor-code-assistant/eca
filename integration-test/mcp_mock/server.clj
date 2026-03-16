(ns mcp-mock.server
  "Mock MCP remote server for integration testing.

  Implements the MCP streamable-http transport protocol over HTTP,
  handling JSON-RPC requests for initialize, tools/list, tools/call,
  and prompts/list. Supports configurable tools, instructions, and
  request tracking for test assertions."
  (:require
   [cheshire.core :as json]
   [org.httpkit.server :as hk])
  (:import
   [java.util UUID]))

(set! *warn-on-reflection* true)

(def port 5551)

(defonce ^:private server* (atom nil))

(def ^:private session-id* (atom nil))

(defonce ^:private requests* (atom []))

(def ^:private default-tools
  [{:name "echo"
    :description "Echoes back the message"
    :inputSchema {:type "object"
                  :properties {:message {:type "string" :description "Message to echo"}}
                  :required ["message"]}}
   {:name "add"
    :description "Adds two numbers"
    :inputSchema {:type "object"
                  :properties {:a {:type "number" :description "First number"}
                               :b {:type "number" :description "Second number"}}
                  :required ["a" "b"]}}])

(def ^:private default-instructions
  "This is a test MCP server for integration testing.")

(defonce ^:private config*
  (atom {:tools default-tools
         :instructions default-instructions}))

(defn get-requests
  "Returns all JSON-RPC requests received by the mock server."
  []
  @requests*)

(defn get-requests-by-method
  "Returns all requests matching the given JSON-RPC method."
  [method]
  (filterv #(= method (:method %)) @requests*))

(defn reset-requests!
  "Clears all recorded requests."
  []
  (reset! requests* []))

(defn set-config!
  "Override the mock server configuration. Keys: :tools, :instructions."
  [config]
  (swap! config* merge config))

(defn reset-config! []
  (reset! config* {:tools default-tools
                   :instructions default-instructions}))

(defn ^:private handle-initialize [body]
  (let [sid (str (UUID/randomUUID))]
    (reset! session-id* sid)
    {:status 200
     :headers {"Content-Type" "application/json"
               "Mcp-Session-Id" sid}
     :body (json/generate-string
            {:jsonrpc "2.0"
             :id (:id body)
             :result {:protocolVersion "2025-03-26"
                      :capabilities {:tools {:listChanged false}
                                     :prompts {:listChanged false}}
                      :serverInfo {:name "test-mcp-mock" :version "1.0.0"}
                      :instructions (:instructions @config*)}})}))

(defn ^:private handle-tools-list [body]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string
          {:jsonrpc "2.0"
           :id (:id body)
           :result {:tools (:tools @config*)}})})

(defn ^:private call-echo [arguments]
  {:content [{:type "text" :text (:message arguments)}]})

(defn ^:private call-add [arguments]
  {:content [{:type "text" :text (str (+ (double (:a arguments))
                                         (double (:b arguments))))}]})

(defn ^:private handle-tool-call [body]
  (let [tool-name (get-in body [:params :name])
        arguments (get-in body [:params :arguments])
        result (case tool-name
                 "echo" (call-echo arguments)
                 "add" (call-add arguments)
                 {:content [{:type "text" :text (str "Unknown tool: " tool-name)}]
                  :isError true})]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/generate-string
            {:jsonrpc "2.0"
             :id (:id body)
             :result result})}))

(defn ^:private handle-prompts-list [body]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string
          {:jsonrpc "2.0"
           :id (:id body)
           :result {:prompts []}})})

(defn ^:private handle-post [req]
  (let [body-str (slurp (:body req))
        body (json/parse-string body-str true)
        method (:method body)]
    (swap! requests* conj body)
    (case method
      "initialize" (handle-initialize body)
      "notifications/initialized" {:status 202 :headers {} :body ""}
      "tools/list" (handle-tools-list body)
      "tools/call" (handle-tool-call body)
      "prompts/list" (handle-prompts-list body)
      "resources/list" {:status 200
                        :headers {"Content-Type" "application/json"}
                        :body (json/generate-string
                               {:jsonrpc "2.0"
                                :id (:id body)
                                :result {:resources []}})}
      ;; Default: method not found
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string
              {:jsonrpc "2.0"
               :id (:id body)
               :error {:code -32601 :message (str "Method not found: " method)}})})))

(defn ^:private handle-get
  "Handle GET requests for SSE stream. Opens a channel and keeps it alive
   for the plumcp client's background SSE connection."
  [req]
  (hk/as-channel
   req
   {:on-open (fn [ch]
               (hk/send! ch {:status 200
                             :headers {"Content-Type" "text/event-stream"
                                       "Cache-Control" "no-cache"
                                       "Connection" "keep-alive"}}
                         false))}))

(defn ^:private app [req]
  (let [{:keys [request-method]} req]
    (case request-method
      :post (handle-post req)
      :get (handle-get req)
      :delete {:status 202 :headers {} :body ""}
      {:status 405 :headers {} :body "Method not allowed"})))

(defn start! []
  (when-not @server*
    (println "Starting MCP mock server on port" port "...")
    (reset! session-id* nil)
    (reset! requests* [])
    (reset! server* (hk/run-server app {:port port})))
  :started)

(defn stop! []
  (when-let [server @server*]
    (server :timeout 100)
    (reset! server* nil)
    (reset! session-id* nil)
    (reset! requests* [])
    (reset-config!)
    :stopped))
