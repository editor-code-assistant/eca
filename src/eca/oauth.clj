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

(defonce ^:private oauth-server-by-port* (atom {}))

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

(defn oauth-info [^String url]
  (let [base-url (url->base-url url)
        {:keys [status headers]} (http/head
                                  url
                                  {:timeout 10000
                                   :throw-exceptions? false})]
    (when (= 401 status)
      (let [callback-port (get-free-port)
            redirect-uri (format "http://localhost:%s/auth/callback" callback-port)
            www-authenticate (some-> (get headers "www-authenticate") parse-www-authenticate)
            auth-resource-meta (:body (http/get
                                       (or (:resource_metadata www-authenticate)
                                           (str base-url "/.well-known/oauth-authorization-server"))
                                       {:timeout 10000
                                        :throw-exceptions? false
                                        :as :json})
                                      :body)
            auth-server (first (:authorization_servers auth-resource-meta))
            base-auth-endpoint (or (:authorization_endpoint auth-resource-meta)
                                   (str auth-server "/authorize"))
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
            client-id (or new-client-id eca-client-id)
            query-params (ring.util/form-encode
                          {:response_type "code"
                           :client_id client-id
                           :code_challenge_method "S256"
                           :code_challenge challenge
                           :scopes (:scopes_supported auth-resource-meta)
                           :state verifier
                           :redirect_uri redirect-uri})]
        {:callback-port callback-port
         :token-endpoint (or (:token_endpoint auth-resource-meta)
                             (str auth-server "/access_token"))
         :verifier verifier
         :client-id client-id
         :redirect-uri redirect-uri
         :authorization-endpoint (str base-auth-endpoint "?" query-params)}))))

(comment
  (oauth-info "https://mcp.atlassian.com/v1/sse")
  (oauth-info "https://mcp.miro.com/")
  (oauth-info "https://api.githubcopilot.com/mcp/"))

(defn start-oauth-server!
  "Start local server on port to handle OAuth redirect"
  [{:keys [on-error on-success port]}]
  (when-not (get @oauth-server-by-port* port)
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
      (swap! oauth-server-by-port* assoc port server)
      (logger/info logger-tag (str "OAuth server started on http://localhost:" port))
      {:server server
       :port port})))

(defn stop-oauth-server!
  "Stop the local OAuth server"
  [port]
  (when-let [^Server server (get oauth-server-by-port* port)]
    (.stop server)
    (swap! oauth-server-by-port* dissoc port)
    (logger/info logger-tag "OAuth server stopped")))

(defn ^:private parse-body
  "Attempt to parse body as JSON, then URL-encoded, falling back to raw string."
  [^String body-str]
  (cond
    (nil? body-str) nil
    (string/blank? body-str) nil
    :else
    (or
     ;; Try JSON first (starts with { or [)
     (when (or (string/starts-with? (string/trim body-str) "{")
               (string/starts-with? (string/trim body-str) "["))
       (try
         (json/parse-string body-str true)
         (catch Exception _ nil)))
     ;; Try URL-encoded format (key=value&key2=value2)
     (when (and (string/includes? body-str "=")
                (not (string/includes? body-str "\n")))
       (try
         (-> (ring.util/form-decode body-str)
             (update-keys keyword))
         (catch Exception _ nil)))
     ;; Fallback to raw error
     {:raw_error body-str})))

(defn authorize-token! [{:keys [token-endpoint verifier client-id redirect-uri]} code]
  (let [{:keys [status body]} (http/post
                               token-endpoint
                               {:headers {"Content-Type" "application/x-www-form-urlencoded"
                                          "Accept" "application/json"}
                                :body (ring.util/form-encode
                                       {:grant_type "authorization_code"
                                        :client_id client-id
                                        :code code
                                        :code_verifier verifier
                                        :redirect_uri redirect-uri})
                                :throw-exceptions? false
                                :as :stream})
        body-str (when body (slurp body))
        parsed-body (parse-body body-str)]
    (logger/debug logger-tag (format "Token response status=%d body=%s" status (pr-str parsed-body)))
    (cond
      ;; Success case
      (and (= 200 status) (:access_token parsed-body))
      {:refresh-token (:refresh_token parsed-body)
       :access-token (:access_token parsed-body)
       :expires-at (+ (quot (System/currentTimeMillis) 1000)
                      (or (:expires_in parsed-body) 3600))}

      ;; Error in response body (some OAuth servers return 200 with error in body)
      (:error parsed-body)
      (throw (ex-info (format "OAuth error: %s - %s"
                              (:error parsed-body)
                              (or (:error_description parsed-body) "No description"))
                      {:status status
                       :error (:error parsed-body)
                       :error-description (:error_description parsed-body)
                       :body parsed-body}))

      ;; Non-200 status
      (not= 200 status)
      (throw (ex-info (format "OAuth token exchange failed (HTTP %d): %s" status (or body-str "No response body"))
                      {:status status
                       :body parsed-body}))

      ;; 200 but no access_token
      :else
      (throw (ex-info (format "OAuth response missing access_token: %s" (pr-str parsed-body))
                      {:status status
                       :body parsed-body})))))
