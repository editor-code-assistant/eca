(ns eca.remote.routes
  "Ring route table and middleware composition for the remote server."
  (:require
   [clojure.string :as string]
   [eca.remote.auth :as auth]
   [eca.remote.handlers :as handlers]
   [eca.remote.middleware :as middleware]))

(set! *warn-on-reflection* true)

(defn- path-segments
  "Splits a URI path into segments, e.g. /api/v1/chats/abc → [\"api\" \"v1\" \"chats\" \"abc\"]"
  [^String uri]
  (filterv (complement string/blank?) (string/split uri #"/")))

(defn- match-route
  "Simple path-based router. Returns [handler-fn & args] or nil."
  [components request {:keys [token host sse-connections*]}]
  (let [method (:request-method request)
        segments (path-segments (:uri request))]
    (case segments
      [] (when (= :get method)
           [handlers/handle-root components request {:host host :token token}])

      ["api" "v1" "health"]
      (when (= :get method)
        [handlers/handle-health components request])

      ["api" "v1" "session"]
      (when (= :get method)
        [handlers/handle-session components request])

      ["api" "v1" "chats"]
      (when (= :get method)
        [handlers/handle-list-chats components request])

      ["api" "v1" "events"]
      (when (= :get method)
        [handlers/handle-events components request {:sse-connections* sse-connections*}])

      ;; Dynamic routes with path params
      (when (and (>= (count segments) 4)
                 (= "api" (nth segments 0))
                 (= "v1" (nth segments 1))
                 (= "chats" (nth segments 2)))
        (let [chat-id (nth segments 3)]
          (case (count segments)
            4 (case method
                :get [handlers/handle-get-chat components request chat-id]
                :delete [handlers/handle-delete-chat components request chat-id]
                nil)
            5 (let [action (nth segments 4)]
                (when (= :post method)
                  (case action
                    "prompt"  [handlers/handle-prompt components request chat-id]
                    "stop"    [handlers/handle-stop components request chat-id]
                    "rollback" [handlers/handle-rollback components request chat-id]
                    "clear"   [handlers/handle-clear components request chat-id]
                    "model"   [handlers/handle-change-model components request chat-id]
                    "agent"   [handlers/handle-change-agent components request chat-id]
                    "variant" [handlers/handle-change-variant components request chat-id]
                    nil)))
            6 (let [action (nth segments 4)
                    tcid (nth segments 5)]
                (when (= :post method)
                  (case action
                    "approve" [handlers/handle-approve components request chat-id tcid]
                    "reject"  [handlers/handle-reject components request chat-id tcid]
                    nil)))
            nil))))))

(defn- not-found-response []
  {:status 404
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body "{\"error\":{\"code\":\"not_found\",\"message\":\"Route not found\"}}"})

(defn create-handler
  "Creates the Ring handler with middleware composition.
   components: the ECA components map {:db* :messenger :metrics :server}
   opts: {:token :host :sse-connections*}"
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
                   :body (str "{\"error\":{\"code\":\"internal_error\","
                              "\"message\":\"" (.getMessage e) "\"}}")})))
            (not-found-response)))
        (middleware/wrap-json-content-type)
        (auth/wrap-bearer-auth token ["/" "/api/v1/health"])
        (middleware/wrap-cors))))
