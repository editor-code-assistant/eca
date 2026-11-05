(ns llm-mock.server
  (:require
   [clojure.java.io :as io]
   [integration.eca :as eca]
   [llm-mock.anthropic :as llm-mock.anthropic]
   [llm-mock.ollama :as llm-mock.ollama]
   [llm-mock.openai :as llm-mock.openai]
   [llm-mock.openai-chat :as llm-mock.openai-chat]
   [org.httpkit.server :as hk]))

(def port 5550)

(def models-dev-url
  "Served in place of https://models.dev/api.json, so servers under test neither
   depend on the network nor on the live catalog's content."
  (str "http://localhost:" port "/models-dev/api.json"))

;; Verbatim subset of https://models.dev/api.json with only the providers and
;; models the tests rely on. To add one, copy its entry from the live catalog.
(def ^:private models-dev-json (delay (slurp (io/resource "llm_mock/models_dev.json"))))

(defonce ^:private models-dev-requests* (atom 0))

(defn models-dev-requests
  "How many times servers under test fetched the models.dev catalog."
  []
  @models-dev-requests*)

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

        (and (= :get request-method)
             (= uri "/models-dev/api.json"))
        (do (swap! models-dev-requests* inc)
            {:status 200
             :headers {"Content-Type" "application/json"}
             :body @models-dev-json})

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
