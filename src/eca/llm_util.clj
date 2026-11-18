(ns eca.llm-util
  (:require
   [camel-snake-kebab.core :as csk]
   [cheshire.core :as json]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.logger :as logger]
   [eca.modules :as modules]
   [eca.secrets :as secrets]
   [eca.shared :as shared])
  (:import
   [java.io BufferedReader Closeable EOFException]
   [java.net ConnectException SocketTimeoutException UnknownHostException]
   [java.net.http HttpConnectTimeoutException]
   [javax.net.ssl SSLException]))

(set! *warn-on-reflection* true)

(defn find-last-msg-idx
  "Returns the index of the last message in `msgs` for which `(pred msg)` is true."
  [pred msgs]
  (->> msgs
       (keep-indexed (fn [i msg] (when (pred msg) i)))
       last))

(defn find-last-user-msg-idx [messages]
  ;; Returns the index of the last :role "user" message, or nil if none.
  (find-last-msg-idx #(= "user" (:role %)) messages))

(defn event-data-seq
  "Read stream events. With :stop-on-done? true, stop at [DONE] without another read."
  [^BufferedReader rdr & {:keys [stop-on-done?]}]
  (letfn [(next-group []
            (loop [event-line nil]
              (let [line (.readLine rdr)]
                (cond
                  ;; EOF
                  (nil? line)
                  nil

                  ;; skip blank lines
                  (string/blank? line)
                  (recur event-line)

                  ;; event: <event>
                  (string/starts-with? line "event:")
                  (recur line)

                  ;; data: <data>
                  (string/starts-with? line "data:")
                  (let [data-str (string/triml (subs line 5))]
                    (if (= data-str "[DONE]")
                      (when-not stop-on-done?
                        (recur event-line))
                      (let [event-type (if event-line
                                         ;; Handle both "event: foo" and "event:foo" formats
                                         (string/triml (subs event-line 6))
                                         (-> (json/parse-string data-str true)
                                             :type))]
                        (cons [event-type (json/parse-string data-str true)]
                              (lazy-seq (next-group))))))

                  ;; data directly
                  (string/starts-with? line "{")
                  (cons ["data" (json/parse-string line true)]
                        (lazy-seq (next-group)))

                  :else
                  (recur event-line)))))]
    (next-group)))

(defn gen-rid
  "Generates a request-id for tracking requests"
  []
  (str (rand-int 9999)))

(defn stringfy-tool-result [result]
  (reduce
   (fn [acc content]
     (str acc
          (case (:type content)
            :image (format "[Image: %s]" (:media-type content))
            (:text content))
          "\n"))
   ""
   (-> result :output :contents)))

(def ^:private tool-call-arguments-tail-length
  "How many characters from the end of invalid tool call arguments the LLM gets back."
  200)

(defn parse-tool-call-arguments
  "Parses the JSON `arguments-text` of a tool call sent by the LLM, returning
   {:arguments ...}. Never throws: for text that is not valid JSON, returns
   empty :arguments and an :arguments-error telling the LLM what failed and how
   its text ended, so the chat can reject only that call with the error as its
   result, the same way for every provider."
  [arguments-text]
  (try
    {:arguments (json/parse-string arguments-text)}
    (catch Exception e
      (let [text (str arguments-text)
            tail-start (max 0 (- (count text) (long tool-call-arguments-tail-length)))]
        {:arguments {}
         :arguments-error (format "%s. The arguments ended with: %s%s"
                                  (or (some-> (ex-message e) string/split-lines first)
                                      "Invalid JSON")
                                  (if (pos? tail-start) "..." "")
                                  (subs text tail-start))}))))

(defn log-request [tag rid url body headers]
  (let [obfuscated-headers (-> headers
                               (shared/update-some "Authorization" #(shared/obfuscate % {:preserve-num 8}))
                               (shared/update-some "x-api-key" shared/obfuscate))]
    (logger/debug tag (format "[%s] Sending body: '%s', headers: '%s', url: '%s'" rid body obfuscated-headers url))))

(defn log-response [tag rid event data]
  (logger/debug tag (format "[%s] %s %s" rid (or event "") data)))

(def ^:private default-stream-idle-timeout-ms 300000)
(def ^:private default-stream-check-interval-ms 500)

(defn start-stream-watchdog!
  "Starts a daemon thread that monitors a streaming connection.
   Closes `closeable` when `cancelled?` returns true or when no SSE events
   have been received for `idle-timeout-ms` while actively reading.

   Returns a map with:
   - :touch-fn        - call on each received event to reset the idle timer,
                        except keepalives (e.g. Anthropic pings), which a
                        stalled generation keeps sending
   - :set-reading-fn  - call with true/false to track whether blocked on .readLine
   - :stop-fn         - call to stop the watchdog (e.g. in finally)
   - :reason*         - atom; :cancelled or :idle-timeout when triggered, nil otherwise"
  [^Closeable closeable cancelled? {:keys [idle-timeout-ms check-interval-ms]
                                    :or {idle-timeout-ms default-stream-idle-timeout-ms
                                         check-interval-ms default-stream-check-interval-ms}}]
  (let [last-activity* (atom (System/currentTimeMillis))
        in-read?* (atom false)
        running?* (atom true)
        reason* (atom nil)
        thread (Thread.
                (fn []
                  (try
                    (while @running?*
                      (Thread/sleep (long check-interval-ms))
                      (when @running?*
                        (cond
                          (and cancelled? (cancelled?))
                          (do (reset! reason* :cancelled)
                              (reset! running?* false)
                              (.close closeable))

                          (and @in-read?*
                               (> (- (System/currentTimeMillis) @last-activity*)
                                  idle-timeout-ms))
                          (do (reset! reason* :idle-timeout)
                              (reset! running?* false)
                              (.close closeable)))))
                    (catch Exception _))))]
    (.setDaemon thread true)
    (.start thread)
    {:touch-fn (fn [] (reset! last-activity* (System/currentTimeMillis)))
     :set-reading-fn (fn [reading?] (reset! in-read?* (boolean reading?)))
     :stop-fn (fn []
                (reset! running?* false)
                (.interrupt thread))
     :reason* reason*}))

(defn idle-timeout-error
  "Error data for a stream closed by the watchdog's idle timeout."
  [stream-idle-timeout-seconds exception]
  (cond-> {:message (format "Stream idle timeout: no data received for %d seconds"
                            (or stream-idle-timeout-seconds (quot default-stream-idle-timeout-ms 1000)))
           :idle-timeout? true}
    exception (assoc :exception exception)))

(defn provider-api-key [provider provider-auth config]
  (or (when-let [key (not-empty (get-in config [:providers (name provider) :key]))]
        [:auth/token key])
      (when-let [key (:api-key provider-auth)]
        [(get provider-auth :type :auth/oauth) key])
      (when-let [key (config/get-env (str (csk/->SCREAMING_SNAKE_CASE (name provider)) "_API_KEY"))]
        [:auth/token key])
      ;; legacy
      (when-let [key (some-> (get-in config [:providers (name provider) :keyRc])
                             (secrets/get-credential (:netrcFile config)))]
        [:auth/token key])
      (when-let [key (some-> (get-in config [:providers (name provider) :keyEnv])
                             config/get-env)]
        [:auth/token key])))

(defn provider-api-url [provider config]
  (some-> (or (not-empty (get-in config [:providers (name provider) :url]))
              (config/get-env (str (csk/->SCREAMING_SNAKE_CASE (name provider)) "_API_URL"))
              (some-> (get-in config [:providers (name provider) :urlEnv]) config/get-env)) ;; legacy
          shared/normalize-api-url
          not-empty))

(defn subscription?
  "Whether requests to `provider` authenticate with a subscription login
   (OAuth), following the precedence of `provider-api-key`, so a configured
   API key taking precedence counts as API billing."
  [provider db config]
  (boolean
   (when provider
     (= :auth/oauth (first (provider-api-key provider (get-in db [:auth provider]) config))))))

(defn ^:private guarded-declaration
  "Module `entry`'s declaration of `provider-id`, with its functions guarded
   when it's an external module (`modules/guard-fns`)."
  [config entry provider-id declaration]
  (modules/guard-fns (modules/module-ctx {:config config} entry) entry (str "provider " provider-id) declaration))

(defn provider-declaration
  "The declaration (`:providers`) of the provider `provider-id`, from the
   first active module that has one; an external module's functions in it
   are guarded."
  [config provider-id]
  (when-let [[entry declaration] (modules/find-contribution config :providers provider-id)]
    (guarded-declaration config entry provider-id declaration)))

(defn provider-declarations
  "Provider id → declaration (see `provider-declaration`), from the active
   modules' `:providers`: the first module that declares a provider wins it."
  [config]
  (reduce (fn [acc [entry providers]]
            (reduce-kv (fn [acc provider-id declaration]
                         (if (contains? acc provider-id)
                           acc
                           (assoc acc provider-id (guarded-declaration config entry provider-id declaration))))
                       acc
                       providers))
          {}
          (modules/contributions config :providers)))

(defn api-entry
  "The LLM API (`:apis`) named `api-name`, from the first active module that
   has one, else the first that has the name in an API's `:aliases`:
   `[entry name api]`, `name` the API's own name. nil when none."
  [config api-name]
  (when-let [api-name (when (or (string? api-name) (keyword? api-name)) (name api-name))]
    (or (when-let [[entry api] (modules/find-contribution config :apis api-name)]
          [entry api-name api])
        (some (fn [[entry apis]]
                (some (fn [[n api]]
                        (when (some #{api-name} (:aliases api))
                          [entry n api]))
                      apis))
              (modules/contributions config :apis)))))

(def no-native-models
  "What a provider's `:models-override-fn` returns when it has no models and
   the generic native /models fetch mustn't be tried either, e.g. because it
   needs headers only the provider sends: the provider's models then come
   from models.dev."
  ::no-native-models)

(defn provider-models-override
  "Provider+auth specific override for native /models fetching: the
   provider's `:models-override-fn`, of `:provider-base` (see
   `config/provider-base`) when present so inheriting providers get their
   parent's override. It gets this ctx (`:api-url`, `:auth-type`, `:api-key`,
   `:extra-headers`...) and returns a map of model-id -> model-config in the
   same shape users put under `:providers <p> :models` (e.g. `{\"gpt-5.5\"
   {:limit {:context 272000}}}`), letting the generic catalog code apply them
   through the existing override path, nil to fall back to the generic native
   /models fetch, or `no-native-models`."
  [{:keys [provider provider-base config] :as ctx}]
  (when-let [override (:models-override-fn (provider-declaration config (or provider-base provider)))]
    ;; one that fails, e.g. an external module's, leaves the generic fetch
    (try
      (override ctx)
      (catch Exception e
        (logger/warn "[LLM-UTIL]" (format "Provider '%s': could not get its models: %s" provider (ex-message e)))
        nil))))

(defn ^:private cause-chain
  "Returns a seq of `e` followed by every nested cause."
  [^Throwable e]
  (->> (iterate (fn [^Throwable t] (.getCause t)) e)
       (take-while some?)))

(defn ^:private root-message [^Throwable e]
  (or (ex-message e) (.getName (class e))))

(defn ^:private tls-record-layer-failure? [causes]
  (some (fn [^Throwable c]
          (when (instance? SSLException c)
            (some-> (ex-message c)
                    string/lower-case
                    (string/replace "_" " ")
                    (string/includes? "bad record mac"))))
        causes))

(defn classify-connection-exception
  "Walks the cause chain of `e` and classifies common HTTP/TLS failures
   into a user-friendly map: {:kind <keyword> :message <string>}.

   Recognized kinds:
   - :tls-untrusted     - PKIX path building failed (private/corporate CA not trusted)
   - :connection-closed - connection dropped mid-request (EOF, reset, closed, TLS record failure)
   - :tls-other         - other TLS/SSL handshake errors
   - :dns               - UnknownHostException
   - :connect-refused   - ConnectException (connection refused, etc.)
   - :timeout           - connection/socket timeouts
   - :unknown           - fallback; keeps the historical 'Connection error: ...' format"
  [^Throwable e]
  (let [msg (root-message e)
        causes (cause-chain e)
        pkix? (some (fn [^Throwable c]
                      (some-> (ex-message c)
                              (string/includes? "PKIX path building failed")))
                    causes)
        closed? (some (fn [^Throwable c]
                        (or (instance? EOFException c)
                            (when-let [m (some-> (ex-message c) string/lower-case)]
                              (or (string/includes? m "eof reached")
                                  (string/includes? m "connection reset")
                                  (= m "closed")))))
                      causes)
        tls-record-failure? (tls-record-layer-failure? causes)
        ssl?  (some #(instance? SSLException %) causes)
        dns?  (some #(instance? UnknownHostException %) causes)
        connect-refused? (some #(instance? ConnectException %) causes)
        timeout? (some #(or (instance? HttpConnectTimeoutException %)
                            (instance? SocketTimeoutException %))
                       causes)]
    (cond
      pkix?
      {:kind :tls-untrusted
       :message (str "TLS certificate not trusted: PKIX path building failed. "
                     "The server's certificate is signed by a CA not in the JVM truststore "
                     "(common with private/corporate CAs). "
                     "Fix: set `network.caCertFile` in your ECA config or the `SSL_CERT_FILE` "
                     "env var to a PEM bundle containing the missing CA. "
                     "See docs/config/network.md for details. Original error: " msg)}

      (or closed? tls-record-failure?)
      {:kind :connection-closed
       :message (str "Connection closed unexpectedly: " msg
                     ". The server, a proxy or the network dropped the connection mid-request"
                     " (common behind corporate proxies/VPNs with idle or streaming timeouts).")}

      ssl?
      {:kind :tls-other
       :message (str "TLS error: " msg
                     ". See docs/config/network.md for trust and mTLS configuration.")}

      dns?
      {:kind :dns
       :message (str "DNS resolution failed: " msg
                     ". Check the provider URL and your network/proxy settings.")}

      connect-refused?
      {:kind :connect-refused
       :message (str "Could not connect: " msg
                     ". Check the provider URL and whether the server is reachable. "
                     "Corporate networks may require HTTP_PROXY / HTTPS_PROXY env vars.")}

      timeout?
      {:kind :timeout
       :message (str "Connection timed out: " msg ".")}

      :else
      {:kind :unknown
       :message (format "Connection error: %s" msg)})))

(defn connection-error-message
  "Returns a user-friendly message describing a connection-level exception.
   Always non-nil. See `classify-connection-exception` for recognized error kinds."
  [^Throwable e]
  (:message (classify-connection-exception e)))
