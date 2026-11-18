(ns eca.oauth
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.digest :as digest]
   [eca.logger :as logger]
   [selmer.parser :as selmer]
   [ring.adapter.jetty :as jetty]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.middleware.params :refer [wrap-params]]
   [ring.util.response :as response])
  (:import
   [java.security KeyStore SecureRandom]
   [java.util Base64]
   [javax.net.ssl KeyManagerFactory SSLContext]
   [org.eclipse.jetty.server Server]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[OAUTH]")

(defonce ^:private oauth-server-by-port* (atom {}))

(def ^:private logo-svg
  (delay
    (-> (slurp (io/resource "logo.svg"))
        ;; Change fill color to white for display on colored background
        (string/replace #"fill:#f8f8f8" "fill:#ffffff"))))

(defn ^:private render-oauth-page
  "Render the OAuth HTML page with success or error state."
  [{:keys [success? error-code error-description error-uri]}]
  (selmer/render-file "webpages/oauth.html"
                      {:success success?
                       :error (not success?)
                       :error-code (or error-code "unknown_error")
                       :error-description error-description
                       :error-uri error-uri
                       :logo-svg @logo-svg}))

(defn get-free-port []
  (let [socket (java.net.ServerSocket. 0)
        port (.getLocalPort socket)]
    (.close socket)
    port))

(defn ^:private rand-bytes
  "Returns a random byte array of the specified size."
  [size]
  (let [seed (byte-array size)]
    (.nextBytes (SecureRandom.) seed)
    seed))

(defn <-base64 ^String [^String s]
  (String. (.decode (Base64/getDecoder) s)))

(defn ^:private ->base64 [^bytes bs]
  (.encodeToString (Base64/getEncoder) bs))

(defn ^:private ->base64url [base64-str]
  (-> base64-str (string/replace "+" "-") (string/replace "/" "_")))

(defn ^:private random-verifier []
  (->base64url (->base64 (rand-bytes 63))))

(defn generate-pkce []
  (let [verifier (random-verifier)]
    {:verifier verifier
     :challenge (-> verifier digest/sha-256-bytes ->base64 ->base64url (string/replace "=" ""))}))

(defn ^:private oauth-handler [request on-success on-error]
  ;; The HTML response is built and returned synchronously, but on-success/on-error
  ;; run on a background thread after a brief delay so Jetty has time to flush the
  ;; page to the browser. This lets callers safely call stop-oauth-server! from
  ;; their callbacks without racing the response write.
  (let [{:keys [code error error_description error_uri state]} (:params request)
        defer-callback! (fn [f]
                          (future
                            (Thread/sleep 500)
                            (try (f)
                                 (catch Exception e
                                   (logger/error logger-tag "OAuth callback error:" (ex-message e))))))]
    (if code
      (do
        (defer-callback! #(on-success {:code code :state state}))
        (-> (response/response (render-oauth-page {:success? true}))
            (response/content-type "text/html")))
      (do
        (defer-callback! #(on-error error))
        (-> (response/response (render-oauth-page {:success? false
                                                   :error-code error
                                                   :error-description error_description
                                                   :error-uri error_uri}))
            (response/content-type "text/html"))))))

(defn ^:private build-ssl-context
  "Loads the bundled localhost PKCS12 keystore from classpath and builds
   an SSLContext. Avoids file I/O and bypasses ring-jetty's SSL config
   path (which uses Jetty reflection that fails in native images)."
  ^SSLContext []
  (let [res (io/resource "tls/localhost.p12")]
    (when-not res
      (throw (ex-info "Bundled localhost keystore not found on classpath"
                      {:resource "tls/localhost.p12"})))
    (let [password (.toCharArray "ecalocal")
          ks (doto (KeyStore/getInstance "PKCS12")
               (.load (io/input-stream res) password))
          kmf (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
                (.init ks password))
          ctx (doto (SSLContext/getInstance "TLS")
                (.init (.getKeyManagers kmf) nil nil))]
      ctx)))

(defn start-oauth-server!
  "Start local server on port to handle OAuth redirect.
   When :ssl? is true, starts HTTPS using the bundled localhost certificate."
  [{:keys [on-error on-success port ssl?]}]
  (when-not (get @oauth-server-by-port* port)
    (try
      (let [handler (-> oauth-handler
                        wrap-keyword-params
                        wrap-params)
            request-handler (fn [request]
                              (if (= "/auth/callback" (:uri request))
                                (handler request on-success on-error)
                                (-> (response/response "404 Not Found")
                                    (response/status 404))))
            jetty-opts (if ssl?
                         {:ssl? true
                          :ssl-port (or port (get-free-port))
                          :http? false
                          :ssl-context (build-ssl-context)
                          :join? false}
                         {:port (or port (get-free-port))
                          :join? false})
            server (jetty/run-jetty request-handler jetty-opts)
            scheme (if ssl? "https" "http")]
        (swap! oauth-server-by-port* assoc port server)
        (logger/info logger-tag (str "OAuth server started on " scheme "://localhost:" port))
        {:server server
         :port port})
      (catch java.net.BindException _
        (logger/error logger-tag (format "Port %d is already in use — is another ECA instance running?" port))
        nil))))

(defn stop-oauth-server!
  "Stop the local OAuth server"
  [port]
  (when-let [^Server server (get @oauth-server-by-port* port)]
    (.stop server)
    (swap! oauth-server-by-port* dissoc port)
    (logger/info logger-tag "OAuth server stopped")))
