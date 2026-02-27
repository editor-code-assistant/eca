(ns eca.llm-providers.errors
  "Classifies LLM provider errors into semantic types for structured error handling.

   Supports context overflow, rate limiting, authentication, and overload detection
   across multiple providers (Anthropic, OpenAI, Google, Ollama, etc.)."
)

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

    (and (#{500 503 529} status)
         (matches-any-pattern? body rate-limited-patterns))
    {:error/type :rate-limited}

    (#{401 403} status)
    {:error/type :auth}

    (#{500 503 529} status)
    {:error/type :overloaded}

    :else nil))

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

      :else nil)))

(defn classify-error
  "Classifies an error map into a semantic error type.

   Accepts the standard on-error map shape: {:message :status :body :exception}.
   Returns a map with :error/type — one of:
     :context-overflow  — prompt exceeds model context window
     :rate-limited      — 429 or rate limit pattern in body/message
     :overloaded        — provider overloaded (503, 529, etc.)
     :auth              — authentication/authorization failure (401, 403)
     :unknown           — unclassified error"
  [{:keys [status exception] :as error-data}]
  (or (when status
        (classify-by-status-and-body error-data))
      (classify-by-message error-data)
      (when exception
        (classify-by-message {:message (ex-message exception)}))
      {:error/type :unknown}))

(defn context-overflow?
  "Returns true if the error is a context window overflow."
  [error-data]
  (= :context-overflow (:error/type (classify-error error-data))))
