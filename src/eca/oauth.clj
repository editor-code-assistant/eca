(ns eca.oauth
  (:require
   [eca.logger :as logger]
   [ring.adapter.jetty :as jetty]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.middleware.params :refer [wrap-params]]
   [ring.util.response :as response])
  (:import
   [org.eclipse.jetty.server Server]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[OAUTH]")

(defonce ^:private oauth-server* (atom nil))

(defn ^:private oauth-handler [request on-success on-error]
  (let [{:keys [code error state]} (:params request)]
    (if code
      (do
        (on-success {:code code
                     :state state})
        (-> (response/response (str "<html>"
                                    "<head>"
                                    "<meta charset=\"UTF-8\">"
                                    "<title>My Web Page</title>"
                                    "</head>"
                                    "<body>"
                                    "<h2>✅ Authentication Successful!</h2>"
                                    "<p>You can close this window and return to ECA.</p>"
                                    "<script>window.close();</script>"
                                    "</body></html>"))
            (response/content-type "text/html")))
      (do
        (on-error error)
        (-> (response/response (str "<html>"
                                    "<head>"
                                    "<meta charset=\"UTF-8\">"
                                    "<title>My Web Page</title>"
                                    "</head>"
                                    "<body>"
                                    "<h2>❌ Authentication Failed</h2>"
                                    "<p>Error: " (or error "Unknown error") "</p>"
                                    "<p>You can close this window and return to ECA.</p>"
                                    "</body></html>"))
            (response/content-type "text/html"))))))

(defn start-oauth-server!
  "Start local server on port to handle OAuth redirect"
  [{:keys [on-error on-success port]}]
  (when-not @oauth-server*
    (let [handler (-> oauth-handler
                      wrap-keyword-params
                      wrap-params)
          server (jetty/run-jetty
                  (fn [request]
                    (if (= "/auth/callback" (:uri request))
                      (handler request on-success on-error)
                      (-> (response/response "404 Not Found")
                          (response/status 404))))
                  {:port port
                   :join? false})]
      (reset! oauth-server* server)
      (logger/info logger-tag (str "OAuth server started on http://localhost:" port))
      server)))

(defn stop-oauth-server!
  "Stop the local OAuth server"
  []
  (when-let [^Server server @oauth-server*]
    (.stop server)
    (reset! oauth-server* nil)
    (logger/info logger-tag "OAuth server stopped")))
