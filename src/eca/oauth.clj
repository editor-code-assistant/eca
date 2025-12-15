(ns eca.oauth
  (:require
   [cheshire.core :as json]
   [clojure.string :as string]
   [eca.logger :as logger]
   [hato.client :as http]
   [ring.adapter.jetty :as jetty]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.middleware.params :refer [wrap-params]]
   [ring.util.codec :as ring.util]
   [ring.util.response :as response])
  (:import
   [java.nio.charset StandardCharsets]
   [java.security MessageDigest SecureRandom]
   [java.util Base64]
   [org.eclipse.jetty.server Server]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[OAUTH]")

(defonce ^:private oauth-server* (atom nil))

(def eca-client-id "Ov23liT613uPA2ydLTa8")

(defn ^:private url->base-url
  "Extract the base URL (scheme + host + port) from a full URL.
   E.g., 'https://api.example.com/v1/mcp' -> 'https://api.example.com'"
  [^String url]
  (let [uri (java.net.URI. url)]
    (str (.getScheme uri) "://" (.getHost uri)
         (when (pos? (.getPort uri))
           (str ":" (.getPort uri))))))

(defn get-free-port []
  (let [socket (java.net.ServerSocket. 0)
        port (.getLocalPort socket)]
    (.close socket)
    port))

(defn ^:private parse-www-authenticate
  "Parse WWW-Authenticate header to extract OAuth parameters.
   Returns a map with :realm, :error, :error_description, :resource_metadata if present."
  [header-value]
  (when (and header-value (string/starts-with? (string/lower-case header-value) "bearer"))
    (let [params-str (string/trim (subs header-value (count "Bearer")))
          ;; Parse key="value" pairs, handling commas within values
          param-pattern #"(\w+)=\"([^\"]*)\""
          matches (re-seq param-pattern params-str)]
      (into {:type :bearer}
            (for [[_ k v] matches]
              [(keyword k) v])))))

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

(defn ^:private str->sha256 [^String s]
  (-> (MessageDigest/getInstance "SHA-256")
      (.digest (.getBytes s StandardCharsets/UTF_8))))

(defn ^:private random-verifier []
  (->base64url (->base64 (rand-bytes 63))))

(defn generate-pkce []
  (let [verifier (random-verifier)]
    {:verifier verifier
     :challenge (-> verifier str->sha256 ->base64 ->base64url (string/replace "=" ""))}))

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

(defn oauth-start-info [^String url]
  (let [base-url (url->base-url url)
        {:keys [status headers]} (http/head
                                  url
                                  {:timeout 10000
                                   :throw-exceptions? false})]
    (when (= 401 status)
      (let [redirect-uri "http://localhost/auth/callback"
            www-authenticate (some-> (get headers "www-authenticate") parse-www-authenticate)
            auth-resource-meta (:body (http/get
                                       (or (:resource_metadata www-authenticate)
                                           (str base-url "/.well-known/oauth-authorization-server"))
                                       {:timeout 10000
                                        :throw-exceptions? false
                                        :as :json})
                                      :body)
            base-auth-endpoint (or (:authorization_endpoint auth-resource-meta)
                                   (str (first (:authorization_servers auth-resource-meta)) "/authorize"))
            new-client-id (when-let [reg-endpoint (:registration_endpoint auth-resource-meta)]
                            (let [res
                                  (http/post
                                   reg-endpoint
                                   {:timeout 10000
                                    :as :json
                                    :body (json/generate-string
                                           {:redirect_uris [redirect-uri]
                                            :token_endpoint_auth_method "none"
                                            :grant_types ["authorization_code" "refresh_token"]
                                            :response_types ["code"]
                                            :client_name "ECA (Editor Code Assistant)"
                                            :client_uri "http://github.com/editor-code-assistant/eca"
                                            :client_id eca-client-id
                                            :client_id_issued_at (.getEpochSecond (java.time.Instant/now))})})]
                              (:client_id (:body res))))
            {:keys [challenge verifier]} (generate-pkce)
            query-params (ring.util/form-encode
                          {:response_type "code"
                           :client_id (or new-client-id eca-client-id)
                           :code_challenge_method "S256"
                           :code_challenge challenge
                           :scopes (:scopes_supported auth-resource-meta)
                           :state verifier
                           :redirect_uri redirect-uri})]
        {:authorization-endpoint (str base-auth-endpoint "?" query-params)}))))

(comment
  (oauth-start-info "https://mcp.atlassian.com/v1/sse")
  (oauth-start-info "https://mcp.miro.com/")
  (oauth-start-info "https://api.githubcopilot.com/mcp/"))

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
                  {:port (or port (get-free-port))
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
