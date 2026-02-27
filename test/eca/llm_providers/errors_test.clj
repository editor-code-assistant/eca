(ns eca.llm-providers.errors-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.llm-providers.errors :as errors]))

(deftest classify-error-context-overflow-test
  (testing "Anthropic prompt too long"
    (is (= {:error/type :context-overflow}
           (errors/classify-error
            {:status 400
             :body "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"prompt is too long: 273112 tokens > 200000 maximum\"}}"
             :message "Anthropic response status: 400 body: ..."}))))

  (testing "OpenAI context length exceeded"
    (is (= {:error/type :context-overflow}
           (errors/classify-error
            {:status 400
             :body "{\"error\":{\"message\":\"This model's maximum context length is 128000 tokens.\",\"type\":\"invalid_request_error\",\"code\":\"context_length_exceeded\"}}"
             :message "LLM response status: 400 body: ..."}))))

  (testing "generic too many tokens"
    (is (= {:error/type :context-overflow}
           (errors/classify-error
            {:status 400
             :body "too many tokens in the input"
             :message "LLM response status: 400 body: too many tokens in the input"}))))

  (testing "413 request too large"
    (is (= {:error/type :context-overflow}
           (errors/classify-error
            {:status 413
             :body ""
             :message "LLM response status: 413 body: "}))))

  (testing "message-only fallback for SSE stream errors"
    (is (= {:error/type :context-overflow}
           (errors/classify-error
            {:message "Anthropic error response: prompt is too long: 273112 tokens > 200000 maximum"})))))

(deftest classify-error-rate-limited-test
  (testing "429 status"
    (is (= {:error/type :rate-limited}
           (errors/classify-error
            {:status 429
             :body "{\"error\":{\"message\":\"Rate limit exceeded\"}}"
             :message "LLM response status: 429 body: ..."}))))

  (testing "503 with overloaded_error in body"
    (is (= {:error/type :rate-limited}
           (errors/classify-error
            {:status 503
             :body "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"
             :message "Anthropic response status: 503 body: ..."}))))

  (testing "message-only fallback for rate limit"
    (is (= {:error/type :rate-limited}
           (errors/classify-error
            {:message "rate limit exceeded, please retry"})))))

(deftest classify-error-auth-test
  (testing "401 unauthorized"
    (is (= {:error/type :auth}
           (errors/classify-error
            {:status 401
             :body "Unauthorized"
             :message "Anthropic response status: 401 body: Unauthorized"}))))

  (testing "403 forbidden"
    (is (= {:error/type :auth}
           (errors/classify-error
            {:status 403
             :body "Forbidden"
             :message "Anthropic response status: 403 body: Forbidden"})))))

(deftest classify-error-overloaded-test
  (testing "503 without rate-limit pattern"
    (is (= {:error/type :overloaded}
           (errors/classify-error
            {:status 503
             :body "Service temporarily unavailable"
             :message "Anthropic response status: 503 body: Service temporarily unavailable"}))))

  (testing "529 overloaded"
    (is (= {:error/type :overloaded}
           (errors/classify-error
            {:status 529
             :body ""
             :message "Anthropic response status: 529 body: "})))))

(deftest classify-error-unknown-test
  (testing "500 is classified as overloaded"
    (is (= {:error/type :overloaded}
           (errors/classify-error
            {:status 500
             :body "Internal server error"
             :message "LLM response status: 500 body: Internal server error"}))))

  (testing "no status, no matching message"
    (is (= {:error/type :unknown}
           (errors/classify-error
            {:message "Something went wrong"}))))

  (testing "exception-only with no matching message"
    (is (= {:error/type :unknown}
           (errors/classify-error
            {:exception (Exception. "Connection reset")}))))

  (testing "exception with context overflow message"
    (is (= {:error/type :context-overflow}
           (errors/classify-error
            {:exception (Exception. "prompt is too long")})))))

(deftest context-overflow?-test
  (testing "returns true for overflow errors"
    (is (true? (errors/context-overflow?
                {:status 400
                 :body "prompt is too long: 273112 tokens > 200000 maximum"}))))

  (testing "returns false for non-overflow errors"
    (is (false? (errors/context-overflow?
                 {:status 429
                  :body "Rate limit exceeded"})))))
