(ns eca.llm-providers.errors
  "Classifies LLM provider errors into semantic types for structured error handling.

   Supports context overflow, rate limiting, authentication, and overload detection
   across multiple providers (Anthropic, OpenAI, Google, Ollama, etc.)."
  (:require
   [clojure.string :as string]))

(set! *warn-on-reflection* true)

(def ^:private context-overflow-patterns
  "Regex patterns matching context window overflow errors across providers."
  [#"(?i)prompt is too long"
   #"(?i)too many tokens"
   #"(?i)context.length.exceeded"
   #"(?i)maximum context length"
   #"(?i)content.*?too.*?large"
   #"(?i)request too large"
   #"(?i)token limit"
   #"(?i)input is too long"
   #"(?i)exceeds? the? ?(?:max(?:imum)?|model).{0,20}(?:token|context)"])

(def ^:private rate-limited-patterns
  "Regex patterns matching rate limit errors across providers."
  [#"(?i)rate.*?limit"
   #"(?i)too many requests"
   #"(?i)throttl"
   #"(?i)overloaded_error"])

(def ^:private overloaded-patterns
  "Regex patterns matching transient connection/infrastructure errors across providers."
  [#"(?i)remote host terminated the handshake"
   #"(?i)host is unreachable"
   #"(?i)connection error"
   #"(?i)internal server error"
   #"(?i)connection refused"
   #"(?i)UnresolvedAddressException"])

(def ^:private openai-transient-message-pattern
  #"(?i)an error occurred while processing your request\.\s+you can retry your request\b")

(defn ^:private matches-any-pattern? [^String text patterns]
  (when text
    (some #(re-find % text) patterns)))

(defn ^:private classify-by-status-and-body
  [{:keys [status body]}]
  (cond
    (and (= 400 status)
         (matches-any-pattern? body context-overflow-patterns))
    {:error/type :context-overflow}

    (= 413 status)
    {:error/type :context-overflow}

    (= 429 status)
    {:error/type :rate-limited}

    (and (#{500 502 503 529} status)
         (matches-any-pattern? body rate-limited-patterns))
    {:error/type :rate-limited}

    (#{401 403} status)
    {:error/type :auth}

    (#{500 502 503 529} status)
    {:error/type :overloaded}

    :else nil))

(defn ^:private classify-openai-responses-error
  [{:keys [code type message] source :error/source}]
  (when (= :openai-responses source)
    (cond
      (some #{"server_error"} [code type])
      {:error/type :overloaded}

      (some #{"rate_limit_exceeded"} [code type])
      {:error/type :rate-limited}

      (or (some? code) (some? type))
      {:error/type :unknown}

      (and (string? message)
           (re-find openai-transient-message-pattern message))
      {:error/type :overloaded}

      :else nil)))

(defn ^:private classify-by-message
  "Fallback classification from unstructured error message strings
   (e.g. SSE stream errors where HTTP status is not available)."
  [{:keys [message]}]
  (when (string? message)
    (cond
      (matches-any-pattern? message context-overflow-patterns)
      {:error/type :context-overflow}

      (matches-any-pattern? message rate-limited-patterns)
      {:error/type :rate-limited}

      (matches-any-pattern? message overloaded-patterns)
      {:error/type :overloaded}

      :else nil)))

(defn ^:private classify-by-custom-rules
  "Checks user-configured retry rules. Each rule may have :status (int),
   :errorPattern (regex string, case-insensitive, matched against body, message,
   and exception message), and :label (string).
   Returns {:error/type :retryable-custom :error/label label} on first match, nil otherwise."
  [{:keys [status body message exception]} retry-rules]
  (when (seq retry-rules)
    (some (fn [{rule-status :status rule-error-pattern :errorPattern rule-label :label}]
            (let [status-matches? (if rule-status
                                    (= rule-status status)
                                    true)
                  error-matches? (if rule-error-pattern
                                   (let [pattern (re-pattern (str "(?i)" rule-error-pattern))]
                                     (or (when (string? body)
                                           (re-find pattern body))
                                         (when (string? message)
                                           (re-find pattern message))
                                         (when exception
                                           (some-> (ex-message exception)
                                                   (as-> msg (re-find pattern msg))))))
                                   true)
                  has-condition? (or rule-status rule-error-pattern)]
              (when (and has-condition? status-matches? error-matches?)
                (cond-> {:error/type :retryable-custom}
                  rule-label (assoc :error/label rule-label)))))
          retry-rules)))

(defn classify-error
  "Classifies an error map into a semantic error type.

   Accepts the standard on-error map shape: {:message :status :body :exception},
   plus optional structured provider fields such as :code, :type, and :error/source.
   Optional `retry-rules` seq of user-configured rules checked before built-in classification.
   Returns a map with :error/type — one of:
     :retryable-custom  — matched a user-configured retry rule (with optional :error/label)
     :context-overflow  — prompt exceeds model context window
     :rate-limited      — 429 or rate limit pattern in body/message
     :overloaded        — provider overloaded (503, 529, etc.)
     :auth              — authentication/authorization failure (401, 403)
     :unknown           — unclassified error"
  ([error-data] (classify-error error-data nil))
  ([{:keys [status exception] :as error-data} retry-rules]
   (or (when-let [pre-type (:error/type error-data)]
         {:error/type pre-type})
       (classify-by-custom-rules error-data retry-rules)
       (classify-openai-responses-error error-data)
       (when status
         (classify-by-status-and-body error-data))
       (classify-by-message error-data)
       (when exception
         (classify-by-message {:message (ex-message exception)}))
       {:error/type :unknown})))

(defn context-overflow?
  "Returns true if the error is a context window overflow."
  [error-data]
  (= :context-overflow (:error/type (classify-error error-data))))

(def ^:private retryable-error-types
  #{:rate-limited :overloaded :retryable-custom :premature-stop})

(defn retryable?
  "Returns true if the error is transient and the request can be retried
   (rate-limited, provider overloaded, or matched a custom retry rule)."
  ([error-data] (retryable? error-data nil))
  ([error-data retry-rules]
   (contains? retryable-error-types
              (:error/type (classify-error error-data retry-rules)))))

(defn ^:private header-str
  "Header value as trimmed non-blank string, unwrapping multi-value collections."
  [headers k]
  (let [v (get headers k)
        v (if (coll? v) (first v) v)]
    (when (string? v)
      (let [v (string/trim v)]
        (when-not (string/blank? v) v)))))

(defn ^:private parse-long-str [s]
  (when (and s (re-matches #"\d+" s))
    (parse-long s)))

(defn ^:private epoch-str->ms
  "Unix epoch string to millis; values already in millis are kept as-is."
  [s]
  (when-let [n (parse-long-str s)]
    (if (> n 100000000000) n (* n 1000))))

(defn ^:private parse-instant-ms
  "RFC 3339 / ISO-8601 timestamp to epoch millis, nil if invalid."
  [s]
  (when s
    (or (try (.toEpochMilli (java.time.Instant/parse s))
             (catch Exception _ nil))
        (try (.toEpochMilli (.toInstant (java.time.OffsetDateTime/parse s)))
             (catch Exception _ nil)))))

(defn ^:private retry-after-ms
  "`retry-after` header (delta seconds or HTTP-date) as absolute epoch millis."
  [headers now-ms]
  (when-let [v (header-str headers "retry-after")]
    (or (some-> (parse-long-str v) (* 1000) (+ now-ms))
        (try (-> (java.time.ZonedDateTime/parse v java.time.format.DateTimeFormatter/RFC_1123_DATE_TIME)
                 .toInstant
                 .toEpochMilli)
             (catch Exception _ nil)))))

(defn ^:private go-duration->ms
  "Parses Go-style duration strings used by OpenAI reset headers,
   e.g. 1s, 6m0s, 12ms, 1h2m3.5s. Returns millis, nil if invalid."
  [s]
  (when (and s (re-matches #"(?:[\d.]+(?:h|m|s|ms))+" s))
    (some->> (re-seq #"([\d.]+)(ms|h|m|s)" s)
             (map (fn [[_ n unit]]
                    (* (parse-double n)
                       (case unit
                         "h" 3600000
                         "m" 60000
                         "s" 1000
                         "ms" 1))))
             (reduce +)
             long)))

(defn ^:private codex-window-reset-ms
  "ChatGPT Codex quota headers (`x-codex-<window>-*`): latest reset epoch-ms
   among exhausted windows (used-percent >= 100), nil otherwise. Windows with
   headroom are ignored on purpose: these headers come on every response with
   far-future resets, so only a clearly exhausted window is a wait signal."
  [headers now-ms]
  (some->> ["primary" "secondary"]
           (keep (fn [w]
                   (let [used (some-> (header-str headers (str "x-codex-" w "-used-percent"))
                                      parse-double)
                         reset-at (epoch-str->ms (header-str headers (str "x-codex-" w "-reset-at")))
                         reset-after-s (parse-long-str (header-str headers (str "x-codex-" w "-reset-after-seconds")))]
                     (when (and used (>= used 100.0))
                       (or reset-at
                           (some-> reset-after-s (* 1000) (+ (long now-ms))))))))
           seq
           (apply max)))

(defn ^:private bucket-resets
  "All rate-limit reset headers (`*ratelimit*-reset` like Anthropic's or
   `*ratelimit*-reset-<bucket>` like OpenAI's) as {:reset-ms .. :remaining ..}
   entries, accepting RFC 3339 timestamps, Go-style durations or unix epoch values."
  [headers now-ms]
  (keep (fn [[k _]]
          (when (and (string? k)
                     (string/includes? k "ratelimit")
                     (or (string/ends-with? k "-reset")
                         (string/includes? k "-reset-")))
            (let [v (header-str headers k)]
              (when-let [reset-ms (or (parse-instant-ms v)
                                      (some->> (go-duration->ms v) (+ (long now-ms)))
                                      (epoch-str->ms v))]
                {:reset-ms reset-ms
                 :remaining (parse-long-str (header-str headers (string/replace k "-reset" "-remaining")))}))))
        headers))

(defn rate-limit-wait
  "Computes when a rate-limited request can be retried, from HTTP response
   headers (lowercase string keys, as returned by the http client).

   Precedence:
   1. `retry-after` (delta seconds or HTTP-date)
   2. `anthropic-ratelimit-unified-reset` (epoch seconds; subscription session limits)
   3. `x-codex-<window>-reset-at/-reset-after-seconds` (ChatGPT subscription),
      only for exhausted windows (used-percent >= 100)
   4. rate-limit reset buckets (Anthropic `*ratelimit*-reset` RFC 3339/epoch,
      OpenAI `*ratelimit*-reset-<bucket>` Go-style durations): latest exhausted
      bucket (remaining = 0), else earliest future reset.

   Returns {:delay-ms N :resets-at epoch-ms} or nil when headers give no usable
   future reset (callers should fall back to exponential backoff)."
  [headers now-ms]
  (when (map? headers)
    (let [resets-at (or (retry-after-ms headers now-ms)
                        (epoch-str->ms (header-str headers "anthropic-ratelimit-unified-reset"))
                        (codex-window-reset-ms headers now-ms)
                        (let [buckets (filter #(> (long (:reset-ms %)) (long now-ms)) (bucket-resets headers now-ms))
                              exhausted (filter #(some-> (:remaining %) (<= 0)) buckets)]
                          (or (some->> (seq exhausted) (map :reset-ms) (apply max))
                              (some->> (seq buckets) (map :reset-ms) (apply min)))))]
      (when (and resets-at (> (long resets-at) (long now-ms)))
        {:delay-ms (- (long resets-at) (long now-ms))
         :resets-at (long resets-at)}))))
