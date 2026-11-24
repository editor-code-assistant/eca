(ns eca.oauth
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.digest :as digest]
   [eca.logger :as logger]
   [eca.shared :as shared]
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

(defn generate-state
  "A random OAuth `state`, telling a login attempt's redirect from others'.
   Unlike the PKCE verifier, it may travel through the browser."
  []
  (-> (rand-bytes 32) ->base64 ->base64url (string/replace "=" "")))

(defn ^:private html-response [page-opts]
  (-> (response/response (render-oauth-page page-opts))
      (response/content-type "text/html")))

(defn ^:private oauth-handler [request on-success on-error sync-success?]
  ;; The HTML response is built and returned synchronously, but on-success/on-error
  ;; run on a background thread after a brief delay so Jetty has time to flush the
  ;; page to the browser. This lets callers safely call stop-oauth-server! from
  ;; their callbacks without racing the response write.
  ;; With sync-success?, on-success runs first instead (e.g. to exchange the code),
  ;; so the page reports whether the login worked rather than claiming success.
  (let [{:keys [code error error_description error_uri state]} (:params request)
        defer-callback! (fn [f]
                          (future
                            (Thread/sleep 500)
                            (try (f)
                                 (catch Exception e
                                   (logger/error logger-tag "OAuth callback error:" (ex-message e))))))]
    (cond
      (and code sync-success?)
      (let [failure (try
                      (on-success {:code code :state state})
                      nil
                      (catch Exception e
                        (or (ex-message e) (str e))))]
        (html-response (if failure
                         {:success? false
                          :error-code "login_failed"
                          :error-description failure}
                         {:success? true})))

      code
      (do
        (defer-callback! #(on-success {:code code :state state}))
        (html-response {:success? true}))

      :else
      (do
        (defer-callback! #(on-error error))
        (html-response {:success? false
                        :error-code error
                        :error-description error_description
                        :error-uri error_uri})))))

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

(def ^:private bind-host
  "On Windows, loopback only: listening on all interfaces makes Windows
   Defender Firewall ask the user to allow eca.exe on their networks, while the
   browser only ever reaches the callback over loopback. Elsewhere, all
   interfaces, so setups forwarding the port (containers, remotes) keep
   working."
  (when shared/windows-os? "127.0.0.1"))

(defn ^:private root-cause ^Throwable [^Throwable e]
  (if-let [cause (ex-cause e)]
    (recur cause)
    e))

(defn ^:private start-failure-message
  "Why the callback server couldn't start (Jetty wraps the bind error) and
   what the user can do about it."
  [port ^Throwable e]
  (let [cause (root-cause e)]
    (str (format "Could not listen on port %d for the OAuth callback (%s)."
                 port (or (ex-message cause) (.getName (class cause))))
         " Another program may hold it, like another ECA instance still waiting for a login: finish or cancel that login, or restart it."
         (when shared/windows-os?
           (str " If nothing listens on it, Windows may have reserved the port (often for Hyper-V, WSL or Docker):"
                " check `netsh interface ipv4 show excludedportrange protocol=tcp`; restarting WinNAT as administrator"
                " (`net stop winnat`, then `net start winnat`) usually frees it.")))))

(defn stop-oauth-server!
  "Stop the local OAuth server on `port`. Given the `server` a login attempt
   started, only while that server still serves `port`: a newer attempt may
   have replaced it, and must keep listening."
  ([port]
   (stop-oauth-server! port nil))
  ([port server]
   (locking oauth-server-by-port*
     (when-let [^Server current (get @oauth-server-by-port* port)]
       (when (or (nil? server) (identical? server current))
         (.stop current)
         (swap! oauth-server-by-port* dissoc port)
         (logger/info logger-tag "OAuth server stopped"))))))

(defn start-oauth-server!
  "Start local server on `port` (a free one when nil) to handle the OAuth
   redirect of a login attempt, returning `{:server :port}`.

   A server this process already runs on `port` belongs to an earlier attempt,
   e.g. one the user gave up on and restarted because the browser tab didn't
   come to the front. It is replaced: the redirect will carry a code of the
   latest attempt, which only that attempt's PKCE verifier can exchange.

   `on-success`/`on-error` run on a background thread once the page is sent.
   With `:sync-success?`, `on-success` runs before the page is rendered, which
   then reports the failure if it throws; it must not stop the server itself
   synchronously.

   When :ssl? is true, starts HTTPS using the bundled localhost certificate.
   Throws an ex-info with an `:error-response` when the server can't start."
  [{:keys [on-error on-success port ssl? sync-success?]}]
  (locking oauth-server-by-port*
    (let [port (or port (get-free-port))]
      (when (get @oauth-server-by-port* port)
        (logger/info logger-tag (format "Replacing the OAuth server on port %d with a newer login attempt" port))
        (stop-oauth-server! port))
      (let [handler (-> (fn [request]
                          (oauth-handler request on-success on-error sync-success?))
                        wrap-keyword-params
                        wrap-params)
            request-handler (fn [request]
                              (if (= "/auth/callback" (:uri request))
                                (handler request)
                                (-> (response/response "404 Not Found")
                                    (response/status 404))))
            jetty-opts (cond-> (if ssl?
                                 {:ssl? true
                                  :ssl-port port
                                  :http? false
                                  :ssl-context (build-ssl-context)
                                  :join? false}
                                 {:port port
                                  :join? false})
                         bind-host (assoc :host bind-host))
            server (try
                     (jetty/run-jetty request-handler jetty-opts)
                     (catch Exception e
                       (let [message (start-failure-message port e)]
                         (logger/error logger-tag message)
                         (throw (ex-info message
                                         {:port port
                                          :error-response {:message message}}
                                         e)))))
            scheme (if ssl? "https" "http")]
        (swap! oauth-server-by-port* assoc port server)
        (logger/info logger-tag (str "OAuth server started on " scheme "://localhost:" port))
        {:server server
         :port port}))))
