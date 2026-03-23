(ns eca.remote.middleware
  "CORS and JSON middleware for the remote server.")

(set! *warn-on-reflection* true)

(defn- cors-headers-for
  [request]
  (let [origin (get-in request [:headers "origin"])
        pna-request? (= "true" (get-in request [:headers "access-control-request-private-network"]))]
    (cond-> {"Access-Control-Allow-Origin" (or origin "*")
             "Access-Control-Allow-Methods" "GET, POST, DELETE, OPTIONS"
             "Access-Control-Allow-Headers" "Content-Type, Authorization"}
      pna-request? (assoc "Access-Control-Allow-Private-Network" "true"))))

(defn wrap-cors
  "Ring middleware adding permissive CORS headers.
   Reflects the request Origin (or * if absent). Handles OPTIONS preflight with 204."
  [handler]
  (fn [request]
    (let [headers (cors-headers-for request)]
      (if (= :options (:request-method request))
        {:status 204
         :headers headers
         :body nil}
        (let [response (handler request)]
          (update response :headers merge headers))))))
