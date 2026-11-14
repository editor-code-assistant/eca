(ns eca.modules.remote.routes
  "Ring route table and middleware composition for the remote server."
  (:require
   [cheshire.core :as json]
   [clojure.string :as string]
   [eca.modules.remote.auth :as m.remote.auth]
   [eca.modules.remote.handlers :as m.remote.handlers]
   [eca.modules.remote.middleware :as m.remote.middleware]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.middleware.params :refer [wrap-params]]))

(set! *warn-on-reflection* true)

(defn ^:private path-segments
  "Splits a URI path into segments, e.g. /api/v1/chats/abc → [\"api\" \"v1\" \"chats\" \"abc\"]"
  [^String uri]
  (filterv (complement string/blank?) (string/split uri #"/")))

(defn ^:private match-route
  "Simple path-based router. Returns [handler-fn & args] or nil."
  [components request {:keys [token host* sse-connections*]}]
  (let [method (:request-method request)
        segments (path-segments (:uri request))]
    (case segments
      [] (when (= :get method)
           [m.remote.handlers/handle-root components request {:host @host* :password token}])

      ["api" "v1" "health"]
      (when (= :get method)
        [m.remote.handlers/handle-health components request])

      ["api" "v1" "session"]
      (when (= :get method)
        [m.remote.handlers/handle-session components request])

      ["api" "v1" "chats"]
      (when (= :get method)
        [m.remote.handlers/handle-list-chats components request])

      ["api" "v1" "events"]
      (when (= :get method)
        [m.remote.handlers/handle-events components request {:sse-connections* sse-connections*}])

      ["api" "v1" "trust"]
      (when (= :post method)
        [m.remote.handlers/handle-set-trust components request {:sse-connections* sse-connections*}])

      ["api" "v1" "answer"]
      (when (= :post method)
        [m.remote.handlers/handle-answer-question components request])

      ["api" "v1" "mcp"]
      (when (= :post method)
        [m.remote.handlers/handle-mcp-add components request])

      ;; Dynamic routes with path params
      (when (and (>= (count segments) 4)
                 (= "api" (nth segments 0))
                 (= "v1" (nth segments 1)))
        (let [resource (nth segments 2)]
          (case resource
            "chats"
            (let [chat-id (nth segments 3)]
              (case (count segments)
                4 (case method
                    :get [m.remote.handlers/handle-get-chat components request chat-id]
                    :delete [m.remote.handlers/handle-delete-chat components request chat-id]
                    nil)
                5 (let [action (nth segments 4)]
                    (when (= :post method)
                      (case action
                        "prompt"  [m.remote.handlers/handle-prompt components request chat-id]
                        "stop"    [m.remote.handlers/handle-stop components request chat-id]
                        "rollback" [m.remote.handlers/handle-rollback components request chat-id]
                        "clear"   [m.remote.handlers/handle-clear components request chat-id]
                        "model"   [m.remote.handlers/handle-change-model components request chat-id]
                        "agent"   [m.remote.handlers/handle-change-agent components request chat-id]
                        "variant" [m.remote.handlers/handle-change-variant components request chat-id]
                        "update"  [m.remote.handlers/handle-update-chat components request chat-id]
                        nil)))
                6 (let [action (nth segments 4)
                        tcid (nth segments 5)]
                    (when (= :post method)
                      (case action
                        "approve" [m.remote.handlers/handle-approve components request chat-id tcid]
                        "reject"  [m.remote.handlers/handle-reject components request chat-id tcid]
                        nil)))
                nil))

            "mcp"
            (cond
              (and (= 4 (count segments)) (= :delete method))
              (let [server-name (nth segments 3)]
                [m.remote.handlers/handle-mcp-remove components request server-name])

              (and (= 5 (count segments)) (= :post method))
              (let [server-name (nth segments 3)
                    action (nth segments 4)]
                (case action
                  "start"   [m.remote.handlers/handle-mcp-start components request server-name]
                  "stop"    [m.remote.handlers/handle-mcp-stop components request server-name]
                  "connect" [m.remote.handlers/handle-mcp-connect components request server-name]
                  "logout"  [m.remote.handlers/handle-mcp-logout components request server-name]
                  "disable" [m.remote.handlers/handle-mcp-disable components request server-name]
                  "enable"  [m.remote.handlers/handle-mcp-enable components request server-name]
                  nil))

              :else nil)

            nil))))))

(defn ^:private not-found-response []
  {:status 404
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body "{\"error\":{\"code\":\"not_found\",\"message\":\"Route not found\"}}"})

(defn create-handler
  "Creates the Ring handler with middleware composition.
   components: the ECA components map {:db* :messenger :metrics :server}
   opts: {:token :host* :sse-connections*}"
  [components opts]
  (let [token (:token opts)]
    (-> (fn [request]
          (if-let [match (match-route components request opts)]
            (let [[handler & args] match]
              (try
                (apply handler args)
                (catch Exception e
                  {:status 500
                   :headers {"Content-Type" "application/json; charset=utf-8"}
                   :body (json/generate-string
                          {:error {:code "internal_error"
                                   :message (or (.getMessage e) "Unknown error")}})})))
            (not-found-response)))
        (wrap-keyword-params)
        (wrap-params)
        (m.remote.auth/wrap-bearer-auth token ["/" "/api/v1/health"])
        (m.remote.middleware/wrap-cors))))
