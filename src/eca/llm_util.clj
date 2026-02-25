(ns eca.llm-util
  (:require
   [camel-snake-kebab.core :as csk]
   [cheshire.core :as json]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.logger :as logger]
   [eca.secrets :as secrets]
   [eca.shared :as shared])
  (:import
   [java.io BufferedReader Closeable]))

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

(defn event-data-seq [^BufferedReader rdr]
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
                      (recur event-line) ; skip [DONE]
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

(defn log-request [tag rid url body headers]
  (let [obfuscated-headers (-> headers
                               (shared/update-some "Authorization" #(shared/obfuscate % {:preserve-num 8}))
                               (shared/update-some "x-api-key" shared/obfuscate))]
    (logger/debug tag (format "[%s] Sending body: '%s', headers: '%s', url: '%s'" rid body obfuscated-headers url))))

(defn log-response [tag rid event data]
  (logger/debug tag (format "[%s] %s %s" rid (or event "") data)))

(def ^:private default-stream-idle-timeout-ms 120000)
(def ^:private default-stream-check-interval-ms 500)

(defn start-stream-watchdog!
  "Starts a daemon thread that monitors a streaming connection.
   Closes `closeable` when `cancelled?` returns true or when no SSE events
   have been received for `idle-timeout-ms` while actively reading.

   Returns a map with:
   - :touch-fn        - call on each received event to reset the idle timer
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
