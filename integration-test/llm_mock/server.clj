(ns llm-mock.server
  (:require
   [integration.eca :as eca]
   [llm-mock.anthropic :as llm-mock.anthropic]
   [llm-mock.ollama :as llm-mock.ollama]
   [llm-mock.openai :as llm-mock.openai]
   [llm-mock.openai-chat :as llm-mock.openai-chat]
   [org.httpkit.server :as hk]))

(def port 5550)

(defonce ^:private server* (atom nil))

(defn ^:private app
  "Handle mock HTTP request REQ

  If `eca/*http-proxy*` is set, validates that the request came through the proxy;
  otherwise responds with 403."
  [req]
  (let [{:keys [request-method uri headers]} req]
    (if (and eca/*http-proxy* (not (get headers "via")))
      {:status 403
       :headers {"Content-Type" "text/plain"}
       :body (str "requests must go through a proxy: " req)}
      (cond
        (and (= :post request-method)
             (= uri "/openai/v1/responses"))
        (llm-mock.openai/handle-openai-responses req)

        (and (= :post request-method)
             (= uri "/openai-chat/chat/completions"))
        (llm-mock.openai-chat/handle-openai-chat req)

        (and (= :post request-method)
             (= uri "/github-copilot/chat/completions"))
        (llm-mock.openai-chat/handle-openai-chat req)

        (and (= :post request-method)
             (= uri "/google/chat/completions"))
        (llm-mock.openai-chat/handle-openai-chat req)

        (and (= :post request-method)
             (= uri "/anthropic/v1/messages"))
        (llm-mock.anthropic/handle-anthropic-messages req)

        (and (= :get request-method)
             (= uri "/ollama/api/tags"))
        (llm-mock.ollama/handle-ollama-tags req)

        (and (= :post request-method)
             (= uri "/ollama/api/show"))
        (llm-mock.ollama/handle-ollama-show req)

        (and (= :post request-method)
             (= uri "/ollama/api/chat"))
        (llm-mock.ollama/handle-ollama-chat req)

        :else {:status 404 :headers {} :body "not found"}))))

(defn start! []
  (when-not @server*
    (println "Starting LLM mock server...")
    (reset! server* (hk/run-server app {:port port})))
  :started)

(defn stop! []
  (when-let [server @server*]
    (server :timeout 100)
    (reset! server* nil)
    :stopped))
