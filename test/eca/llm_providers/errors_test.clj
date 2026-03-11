(ns eca.llm-providers.errors-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.llm-providers.errors :as llm-providers.errors]))

(deftest classify-error-context-overflow-test
  (testing "Anthropic prompt too long"
    (is (= {:error/type :context-overflow}
           (llm-providers.errors/classify-error
            {:status 400
             :body "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"prompt is too long: 273112 tokens > 200000 maximum\"}}"
             :message "Anthropic response status: 400 body: ..."}))))

  (testing "OpenAI context length exceeded"
    (is (= {:error/type :context-overflow}
           (llm-providers.errors/classify-error
            {:status 400
             :body "{\"error\":{\"message\":\"This model's maximum context length is 128000 tokens.\",\"type\":\"invalid_request_error\",\"code\":\"context_length_exceeded\"}}"
             :message "LLM response status: 400 body: ..."}))))

  (testing "generic too many tokens"
    (is (= {:error/type :context-overflow}
           (llm-providers.errors/classify-error
            {:status 400
             :body "too many tokens in the input"
             :message "LLM response status: 400 body: too many tokens in the input"}))))

  (testing "413 request too large"
    (is (= {:error/type :context-overflow}
           (llm-providers.errors/classify-error
            {:status 413
             :body ""
             :message "LLM response status: 413 body: "}))))

  (testing "message-only fallback for SSE stream errors"
    (is (= {:error/type :context-overflow}
           (llm-providers.errors/classify-error
            {:message "Anthropic error response: prompt is too long: 273112 tokens > 200000 maximum"})))))

(deftest classify-error-rate-limited-test
  (testing "429 status"
    (is (= {:error/type :rate-limited}
           (llm-providers.errors/classify-error
            {:status 429
             :body "{\"error\":{\"message\":\"Rate limit exceeded\"}}"
             :message "LLM response status: 429 body: ..."}))))

  (testing "503 with overloaded_error in body"
    (is (= {:error/type :rate-limited}
           (llm-providers.errors/classify-error
            {:status 503
             :body "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"
             :message "Anthropic response status: 503 body: ..."}))))

  (testing "message-only fallback for rate limit"
    (is (= {:error/type :rate-limited}
           (llm-providers.errors/classify-error
            {:message "rate limit exceeded, please retry"})))))

(deftest classify-error-auth-test
  (testing "401 unauthorized"
    (is (= {:error/type :auth}
           (llm-providers.errors/classify-error
            {:status 401
             :body "Unauthorized"
             :message "Anthropic response status: 401 body: Unauthorized"}))))

  (testing "403 forbidden"
    (is (= {:error/type :auth}
           (llm-providers.errors/classify-error
            {:status 403
             :body "Forbidden"
             :message "Anthropic response status: 403 body: Forbidden"})))))

(deftest classify-error-overloaded-test
  (testing "503 without rate-limit pattern"
    (is (= {:error/type :overloaded}
           (llm-providers.errors/classify-error
            {:status 503
             :body "Service temporarily unavailable"
             :message "Anthropic response status: 503 body: Service temporarily unavailable"}))))

  (testing "529 overloaded"
    (is (= {:error/type :overloaded}
           (llm-providers.errors/classify-error
            {:status 529
             :body ""
             :message "Anthropic response status: 529 body: "}))))

  (testing "502 bad gateway"
    (is (= {:error/type :overloaded}
           (llm-providers.errors/classify-error
            {:status 502
             :body "Bad Gateway"
             :message "LLM response status: 502 body: Bad Gateway"})))))

(deftest classify-error-unknown-test
  (testing "500 is classified as overloaded"
    (is (= {:error/type :overloaded}
           (llm-providers.errors/classify-error
            {:status 500
             :body "Internal server error"
             :message "LLM response status: 500 body: Internal server error"}))))

  (testing "no status, no matching message"
    (is (= {:error/type :unknown}
           (llm-providers.errors/classify-error
            {:message "Something went wrong"}))))

  (testing "exception-only with no matching message"
    (is (= {:error/type :unknown}
           (llm-providers.errors/classify-error
            {:exception (Exception. "Connection reset")}))))

  (testing "exception with context overflow message"
    (is (= {:error/type :context-overflow}
           (llm-providers.errors/classify-error
            {:exception (Exception. "prompt is too long")}))))

  (testing "remote host terminated the handshake is overloaded"
    (is (= {:error/type :overloaded}
           (llm-providers.errors/classify-error
            {:message "Remote host terminated the handshake"}))))

  (testing "exception with handshake termination message"
    (is (= {:error/type :overloaded}
           (llm-providers.errors/classify-error
            {:exception (Exception. "Remote host terminated the handshake")}))))

  (testing "connection error message is overloaded"
    (is (= {:error/type :overloaded}
           (llm-providers.errors/classify-error
            {:message "Connection error: java.nio.channels.UnresolvedAddressException"}))))

  (testing "connection refused message is overloaded"
    (is (= {:error/type :overloaded}
           (llm-providers.errors/classify-error
            {:message "Connection error: Connection refused"}))))

  (testing "connection error is retryable"
    (is (true? (llm-providers.errors/retryable?
                {:message "Connection error: java.nio.channels.UnresolvedAddressException"})))))

(deftest context-overflow?-test
  (testing "returns true for overflow errors"
    (is (true? (llm-providers.errors/context-overflow?
                {:status 400
                 :body "prompt is too long: 273112 tokens > 200000 maximum"}))))

  (testing "returns false for non-overflow errors"
    (is (false? (llm-providers.errors/context-overflow?
                 {:status 429
                  :body "Rate limit exceeded"})))))

(deftest classify-error-custom-retry-rules-test
  (testing "matches by status code only"
    (is (= {:error/type :retryable-custom :error/label "Proxy throttle"}
           (llm-providers.errors/classify-error
            {:status 418 :body "I'm a teapot" :message "status 418"}
            [{:status 418 :label "Proxy throttle"}]))))

  (testing "matches by error pattern only"
    (is (= {:error/type :retryable-custom :error/label "Capacity exceeded"}
           (llm-providers.errors/classify-error
            {:status 500 :body "server capacity exceeded, try again" :message "status 500"}
            [{:errorPattern "capacity.*exceeded" :label "Capacity exceeded"}]))))

  (testing "matches by both status and error pattern"
    (is (= {:error/type :retryable-custom :error/label "Maintenance"}
           (llm-providers.errors/classify-error
            {:status 503 :body "scheduled maintenance window" :message "status 503"}
            [{:status 503 :errorPattern "maintenance" :label "Maintenance"}]))))

  (testing "does not match when status differs"
    (is (= {:error/type :unknown}
           (llm-providers.errors/classify-error
            {:status 400 :body "bad request" :message "status 400"}
            [{:status 418 :label "Teapot"}]))))

  (testing "does not match when error pattern does not match"
    (is (= {:error/type :overloaded}
           (llm-providers.errors/classify-error
            {:status 500 :body "internal error" :message "status 500"}
            [{:errorPattern "capacity.*exceeded" :label "Capacity"}]))))

  (testing "label is optional"
    (is (= {:error/type :retryable-custom}
           (llm-providers.errors/classify-error
            {:status 418 :body "" :message "status 418"}
            [{:status 418}]))))

  (testing "first matching rule wins"
    (is (= {:error/type :retryable-custom :error/label "First"}
           (llm-providers.errors/classify-error
            {:status 418 :body "" :message "status 418"}
            [{:status 418 :label "First"}
             {:status 418 :label "Second"}]))))

  (testing "custom rules take priority over built-in classification"
    (is (= {:error/type :retryable-custom :error/label "Custom 429"}
           (llm-providers.errors/classify-error
            {:status 429 :body "Rate limit exceeded" :message "status 429"}
            [{:status 429 :label "Custom 429"}]))))

  (testing "falls through to built-in when no custom rule matches"
    (is (= {:error/type :rate-limited}
           (llm-providers.errors/classify-error
            {:status 429 :body "Rate limit exceeded" :message "status 429"}
            [{:status 418 :label "Teapot"}]))))

  (testing "nil retry-rules falls through to built-in"
    (is (= {:error/type :rate-limited}
           (llm-providers.errors/classify-error
            {:status 429 :body "Rate limit exceeded" :message "status 429"}
            nil))))

  (testing "empty retry-rules falls through to built-in"
    (is (= {:error/type :rate-limited}
           (llm-providers.errors/classify-error
            {:status 429 :body "Rate limit exceeded" :message "status 429"}
            []))))

  (testing "matches by error pattern against message"
    (is (= {:error/type :retryable-custom :error/label "TLS handshake"}
           (llm-providers.errors/classify-error
            {:message "Remote host terminated the handshake"}
            [{:errorPattern "terminated.*handshake" :label "TLS handshake"}]))))

  (testing "error pattern matches against body"
    (is (= {:error/type :retryable-custom :error/label "Custom"}
           (llm-providers.errors/classify-error
            {:status 500 :body "server capacity exceeded"}
            [{:errorPattern "capacity.*exceeded" :label "Custom"}]))))

  (testing "error pattern is case-insensitive"
    (is (= {:error/type :retryable-custom}
           (llm-providers.errors/classify-error
            {:message "REMOTE HOST TERMINATED THE HANDSHAKE"}
            [{:errorPattern "terminated.*handshake"}]))))

  (testing "error pattern matches exception message"
    (is (= {:error/type :retryable-custom :error/label "Connection error"}
           (llm-providers.errors/classify-error
            {:exception (Exception. "Remote host terminated the handshake")}
            [{:errorPattern "terminated.*handshake" :label "Connection error"}]))))

  (testing "matches by status and error pattern"
    (is (= {:error/type :retryable-custom :error/label "Combo"}
           (llm-providers.errors/classify-error
            {:status 500 :message "connection reset"}
            [{:status 500 :errorPattern "connection reset" :label "Combo"}]))))

  (testing "does not match when error pattern does not match"
    (is (= {:error/type :unknown}
           (llm-providers.errors/classify-error
            {:message "Something went wrong"}
            [{:errorPattern "terminated.*handshake"}]))))

  (testing "does not match when status differs but error pattern matches"
    (is (= {:error/type :unknown}
           (llm-providers.errors/classify-error
            {:status 400 :message "some weird error happened"}
            [{:status 500 :errorPattern "terminated.*handshake"}])))))

(deftest retryable?-test
  (testing "429 rate-limited is retryable"
    (is (true? (llm-providers.errors/retryable?
                {:status 429
                 :body "{\"error\":{\"message\":\"Rate limit exceeded\"}}"
                 :message "LLM response status: 429 body: ..."}))))

  (testing "503 overloaded is retryable"
    (is (true? (llm-providers.errors/retryable?
                {:status 503
                 :body "Service temporarily unavailable"
                 :message "Anthropic response status: 503 body: Service temporarily unavailable"}))))

  (testing "529 overloaded is retryable"
    (is (true? (llm-providers.errors/retryable?
                {:status 529
                 :body ""
                 :message "Anthropic response status: 529 body: "}))))

  (testing "502 bad gateway is retryable"
    (is (true? (llm-providers.errors/retryable?
                {:status 502
                 :body "Bad Gateway"
                 :message "LLM response status: 502 body: Bad Gateway"}))))

  (testing "503 with overloaded_error pattern (rate-limited) is retryable"
    (is (true? (llm-providers.errors/retryable?
                {:status 503
                 :body "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"
                 :message "Anthropic response status: 503 body: ..."}))))

  (testing "message-only rate limit is retryable"
    (is (true? (llm-providers.errors/retryable?
                {:message "rate limit exceeded, please retry"}))))

  (testing "context-overflow is not retryable"
    (is (false? (llm-providers.errors/retryable?
                 {:status 400
                  :body "prompt is too long: 273112 tokens > 200000 maximum"}))))

  (testing "auth error is not retryable"
    (is (false? (llm-providers.errors/retryable?
                 {:status 401
                  :body "Unauthorized"}))))

  (testing "unknown error is not retryable"
    (is (false? (llm-providers.errors/retryable?
                 {:message "Something went wrong"}))))

  (testing "custom retry rule makes error retryable"
    (is (true? (llm-providers.errors/retryable?
                {:status 418 :body "I'm a teapot"}
                [{:status 418 :label "Teapot"}]))))

  (testing "non-matching custom rule does not affect retryability"
    (is (false? (llm-providers.errors/retryable?
                 {:message "Something went wrong"}
                 [{:status 418 :label "Teapot"}]))))

  (testing "handshake termination is retryable"
    (is (true? (llm-providers.errors/retryable?
                {:message "Remote host terminated the handshake"}))))

  (testing "custom error pattern rule makes error retryable"
    (is (true? (llm-providers.errors/retryable?
                {:message "Remote host terminated the handshake"}
                [{:errorPattern "terminated.*handshake" :label "TLS error"}])))))
