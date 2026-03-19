(ns eca.remote.middleware
  "CORS and JSON middleware for the remote server.")

(set! *warn-on-reflection* true)

(def ^:private allowed-origin "https://web.eca.dev")

(defn- allowed-origin?
  "Returns the origin if allowed, nil otherwise.
   Allows web.eca.dev and localhost for development."
  [origin]
  (when origin
    (cond
      (= origin allowed-origin) origin
      (re-matches #"http://localhost(:\d+)?" origin) origin
      (re-matches #"http://127\.0\.0\.1(:\d+)?" origin) origin
      :else nil)))

(defn- cors-headers-for
  [request]
  (let [origin (get-in request [:headers "origin"])
        resolved (or (allowed-origin? origin) allowed-origin)]
    {"Access-Control-Allow-Origin" resolved
     "Access-Control-Allow-Methods" "GET, POST, DELETE, OPTIONS"
     "Access-Control-Allow-Headers" "Content-Type, Authorization"}))

(defn wrap-cors
  "Ring middleware adding CORS headers for web.eca.dev and localhost.
   Handles OPTIONS preflight with 204."
  [handler]
  (fn [request]
    (let [headers (cors-headers-for request)]
      (if (= :options (:request-method request))
        {:status 204
         :headers headers
         :body nil}
        (let [response (handler request)]
          (update response :headers merge headers))))))

(defn wrap-json-content-type
  "Ring middleware that sets default JSON content-type on responses
   that don't already have one."
  [handler]
  (fn [request]
    (let [response (handler request)]
      (if (get-in response [:headers "Content-Type"])
        response
        (assoc-in response [:headers "Content-Type"] "application/json; charset=utf-8")))))
