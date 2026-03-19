(ns eca.remote.middleware
  "CORS and JSON middleware for the remote server.")

(set! *warn-on-reflection* true)

(def ^:private cors-headers
  {"Access-Control-Allow-Origin" "https://web.eca.dev"
   "Access-Control-Allow-Methods" "GET, POST, DELETE, OPTIONS"
   "Access-Control-Allow-Headers" "Content-Type, Authorization"})

(defn wrap-cors
  "Ring middleware adding CORS headers for web.eca.dev.
   Handles OPTIONS preflight with 204."
  [handler]
  (fn [request]
    (if (= :options (:request-method request))
      {:status 204
       :headers cors-headers
       :body nil}
      (let [response (handler request)]
        (update response :headers merge cors-headers)))))

(defn wrap-json-content-type
  "Ring middleware that sets default JSON content-type on responses
   that don't already have one."
  [handler]
  (fn [request]
    (let [response (handler request)]
      (if (get-in response [:headers "Content-Type"])
        response
        (assoc-in response [:headers "Content-Type"] "application/json; charset=utf-8")))))
