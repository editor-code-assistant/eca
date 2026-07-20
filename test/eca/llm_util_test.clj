(ns eca.llm-util-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.llm-util :as llm-util]
   [eca.secrets :as secrets]
   [matcher-combinators.test :refer [match?]])
  (:import
   [java.io ByteArrayInputStream EOFException IOException]
   [java.net ConnectException SocketException SocketTimeoutException UnknownHostException]
   [javax.net.ssl SSLException SSLHandshakeException]))

(deftest event-data-seq-test
  (testing "when there is a event line and another data line"
    (with-open [r (io/reader (ByteArrayInputStream. (.getBytes (str "event: foo.bar\n"
                                                                    "data: {\"type\": \"foo.bar\"}\n"
                                                                    "\n"
                                                                    "event: foo.baz\n"
                                                                    "data: {\"type\": \"foo.baz\"}"))))]
      (is (match?
           [["foo.bar" {:type "foo.bar"}]
            ["foo.baz" {:type "foo.baz"}]]
           (llm-util/event-data-seq r)))))
  (testing "when there is no event line, only a data line"
    (with-open [r (io/reader (ByteArrayInputStream. (.getBytes (str "data: {\"type\": \"foo.bar\"}\n"
                                                                    "\n"
                                                                    "data: {\"type\": \"foo.baz\"}"))))]
      (is (match?
           [["foo.bar" {:type "foo.bar"}]
            ["foo.baz" {:type "foo.baz"}]]
           (llm-util/event-data-seq r)))))
  (testing "when there is no event line, only a data line with the content directly in each line"
    (with-open [r (io/reader (ByteArrayInputStream. (.getBytes (str "{\"bar\": \"baz\"}\n"
                                                                    "{\"bar\": \"foo\"}"))))]
      (is (match?
           [["data" {:bar "baz"}]
            ["data" {:bar "foo"}]]
           (llm-util/event-data-seq r)))))
  (testing "Ignore [DONE] when exists"
    (with-open [r (io/reader (ByteArrayInputStream. (.getBytes (str "data: {\"type\": \"foo.bar\"}\n"
                                                                    "\n"
                                                                    "data: {\"type\": \"foo.baz\"}\n"
                                                                    "\n"
                                                                    "data: [DONE]\n"))))]
      (is (match?
           [["foo.bar" {:type "foo.bar"}]
            ["foo.baz" {:type "foo.baz"}]]
           (llm-util/event-data-seq r)))))
  (testing "when no extra space after data:"
    (with-open [r (io/reader (ByteArrayInputStream. (.getBytes (str "data:{\"type\": \"foo.bar\"}\n"
                                                                    "\n"
                                                                    "data:{\"type\": \"foo.baz\"}\n"
                                                                    "\n"
                                                                    "data:[DONE]\n"))))]
      (is (match?
           [["foo.bar" {:type "foo.bar"}]
            ["foo.baz" {:type "foo.baz"}]]
           (llm-util/event-data-seq r)))))
  (testing "when no extra space after event: (moonshot/kimi format)"
    (with-open [r (io/reader (ByteArrayInputStream. (.getBytes (str "event:content_block_start\n"
                                                                    "data:{\"type\":\"content_block_start\",\"index\":0}\n"
                                                                    "\n"
                                                                    "event:content_block_delta\n"
                                                                    "data:{\"type\":\"content_block_delta\",\"index\":0}\n"
                                                                    "\n"
                                                                    "event:message_delta\n"
                                                                    "data:{\"type\":\"message_delta\"}"))))]
      (is (match?
           [["content_block_start" {:type "content_block_start" :index 0}]
            ["content_block_delta" {:type "content_block_delta" :index 0}]
            ["message_delta" {:type "message_delta"}]]
           (llm-util/event-data-seq r))))))

(deftest provider-api-key-with-key-rc-test
  (testing "provider-api-key uses keyRc when configured"
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create a test netrc file
        (spit temp-path "machine api.openai.com\nlogin apikey\npassword sk-test-from-netrc\n")

        ;; Mock credential-file-paths to return our test file
        (with-redefs [secrets/credential-file-paths (constantly [temp-path])
                      config/get-env (constantly nil)]
          (let [config {:providers
                        {"openai" {:url "https://api.openai.com"
                                   :keyRc "api.openai.com"}}}
                result (llm-util/provider-api-key "openai" nil config)]
            (is (= [:auth/token "sk-test-from-netrc"] result))))
        (finally
          (.delete temp-file)))))
  (testing "provider-api-key uses :netrcFile in config when configured"
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create a test netrc file
        (spit temp-path "machine api.openai.com\nlogin apikey\npassword sk-test-from-custom-netrc\n")

        (with-redefs [config/get-env (constantly nil)]
          (let [config {:providers
                        {"openai" {:url "https://api.openai.com"
                                   :keyRc "api.openai.com"}}
                        :netrcFile temp-path}
                result (llm-util/provider-api-key "openai" nil config)]
            (is (= [:auth/token "sk-test-from-custom-netrc"] result))))
        (finally
          (.delete temp-file))))))

(deftest provider-api-key-priority-order-test
  (testing "provider-api-key respects priority order: key > keyRc > keyEnv"
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create a test netrc file
        (spit temp-path "machine api.openai.com\nlogin apikey\npassword sk-test-from-netrc\n")

        (with-redefs [secrets/credential-file-paths (constantly [temp-path])
                      config/get-env (constantly nil)]
          ;; Test 1: explicit key takes precedence over keyRc
          (let [config {:providers
                        {"openai" {:key "sk-explicit-key"
                                   :keyRc "api.openai.com"}}}
                result (llm-util/provider-api-key "openai" nil config)]
            (is (= [:auth/token "sk-explicit-key"] result)))

          ;; Test 2: oauth token takes precedence over keyRc
          (let [config {:providers
                        {"openai" {:keyRc "api.openai.com"}}}
                provider-auth {:api-key "sk-oauth-token"}
                result (llm-util/provider-api-key "openai" provider-auth config)]
            (is (= [:auth/oauth "sk-oauth-token"] result)))

          ;; Test 3: keyRc is used when key and oauth are not available
          (let [config {:providers
                        {"openai" {:keyRc "api.openai.com"}}}
                result (llm-util/provider-api-key "openai" nil config)]
            (is (= [:auth/token "sk-test-from-netrc"] result))))
        (finally
          (.delete temp-file))))))

(deftest provider-api-key-with-login-prefix-test
  (testing "provider-api-key works with login prefix in keyRc"
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create a test netrc file with multiple entries
        (spit temp-path (str "machine api.anthropic.com\nlogin work\npassword sk-ant-work-key\n\n"
                             "machine api.anthropic.com\nlogin personal\npassword sk-ant-personal-key\n"))

        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          ;; Test with specific login
          (let [config {:providers
                        {"anthropic" {:keyRc "work@api.anthropic.com"}}}
                result (llm-util/provider-api-key "anthropic" nil config)]
            (is (= [:auth/token "sk-ant-work-key"] result)))

          ;; Test with different login
          (let [config {:providers
                        {"anthropic" {:keyRc "personal@api.anthropic.com"}}}
                result (llm-util/provider-api-key "anthropic" nil config)]
            (is (= [:auth/token "sk-ant-personal-key"] result))))
        (finally
          (.delete temp-file))))))

(deftest provider-api-key-missing-credential-test
  (testing "provider-api-key returns nil when credential not found"
    (with-redefs [config/get-env (constantly nil)]
      (let [config {:providers
                    {"openai" {:keyRc "api.openai.com"}}
                    :netrcFile "/nonexistent/file"}
            result (llm-util/provider-api-key "openai" nil config)]
        (is (nil? result))))))

(deftest provider-api-url-test
  (testing "normalizes provider url from config"
    (with-redefs [config/get-env (constantly nil)]
      (is (= "https://api.example.com/v1"
             (llm-util/provider-api-url
              "openai"
              {:providers {"openai" {:url "  https://api.example.com/v1/  "}}})))))

  (testing "normalizes provider url from env"
    (with-redefs [config/get-env (fn [k] (when (= k "OPENAI_API_URL") " https://api.example.com/v1/ "))]
      (is (= "https://api.example.com/v1"
             (llm-util/provider-api-url "openai" {:providers {"openai" {}}})))))

  (testing "returns nil for blank url"
    (with-redefs [config/get-env (constantly nil)]
      (is (nil? (llm-util/provider-api-url "openai" {:providers {"openai" {:url "   "}}}))))))

(deftest classify-connection-exception-test
  (testing "PKIX path building failed -> :tls-untrusted with actionable hint"
    (let [e (SSLHandshakeException. "PKIX path building failed: unable to find valid certification path to requested target")
          {:keys [kind message]} (llm-util/classify-connection-exception e)]
      (is (= :tls-untrusted kind))
      (is (re-find #"TLS certificate not trusted" message))
      (is (re-find #"network\.caCertFile" message))
      (is (re-find #"SSL_CERT_FILE" message))
      (is (re-find #"docs/config/network\.md" message))))

  (testing "PKIX detected even when wrapped in a non-SSL outer exception"
    (let [root (Exception. "PKIX path building failed: unable to find valid certification path to requested target")
          wrapped (RuntimeException. "wrapper" root)
          {:keys [kind message]} (llm-util/classify-connection-exception wrapped)]
      (is (= :tls-untrusted kind))
      (is (re-find #"network\.caCertFile" message))))

  (testing "Generic SSLException (no PKIX) -> :tls-other"
    (let [e (SSLException. "handshake_failure")
          {:keys [kind message]} (llm-util/classify-connection-exception e)]
      (is (= :tls-other kind))
      (is (re-find #"TLS error" message))
      (is (re-find #"docs/config/network\.md" message))))

  (testing "UnknownHostException -> :dns"
    (let [e (UnknownHostException. "no-such-host.example")
          {:keys [kind message]} (llm-util/classify-connection-exception e)]
      (is (= :dns kind))
      (is (re-find #"DNS resolution failed" message))))

  (testing "ConnectException (e.g. Connection refused) -> :connect-refused"
    (let [e (ConnectException. "Connection refused")
          {:keys [kind message]} (llm-util/classify-connection-exception e)]
      (is (= :connect-refused kind))
      (is (re-find #"Could not connect" message))
      (is (re-find #"HTTP_PROXY" message))))

  (testing "SocketTimeoutException -> :timeout"
    (let [e (SocketTimeoutException. "Read timed out")
          {:keys [kind message]} (llm-util/classify-connection-exception e)]
      (is (= :timeout kind))
      (is (re-find #"Connection timed out" message))))

  (testing "IOException 'closed' caused by EOFException (proxy dropping stream, #547) -> :connection-closed"
    (let [e (IOException. "closed" (EOFException. "EOF reached while reading"))
          {:keys [kind message]} (llm-util/classify-connection-exception e)]
      (is (= :connection-closed kind))
      (is (re-find #"Connection closed unexpectedly" message))
      (is (re-find #"proxy" message))))

  (testing "SocketException Connection reset -> :connection-closed"
    (let [e (SocketException. "Connection reset")
          {:keys [kind message]} (llm-util/classify-connection-exception e)]
      (is (= :connection-closed kind))
      (is (re-find #"Connection closed unexpectedly" message))))

  (testing "SSLException wrapping a connection reset -> :connection-closed (not :tls-other)"
    (let [e (SSLException. "Connection reset" (SocketException. "Connection reset"))
          {:keys [kind]} (llm-util/classify-connection-exception e)]
      (is (= :connection-closed kind))))

  (testing "Unknown exception falls back to legacy 'Connection error:' format"
    (let [e (Exception. "boom")
          {:keys [kind message]} (llm-util/classify-connection-exception e)]
      (is (= :unknown kind))
      (is (= "Connection error: boom" message))))

  (testing "Exception with nil message uses class name as fallback"
    (let [e (Exception.)
          {:keys [kind message]} (llm-util/classify-connection-exception e)]
      (is (= :unknown kind))
      (is (re-find #"Connection error: java\.lang\.Exception" message)))))

(deftest connection-error-message-test
  (testing "returns the :message from classify-connection-exception"
    (is (re-find #"TLS certificate not trusted"
                 (llm-util/connection-error-message
                  (SSLHandshakeException. "PKIX path building failed: ...")))))
  (testing "is non-nil for any exception"
    (is (string? (llm-util/connection-error-message (Exception. "x"))))
    (is (string? (llm-util/connection-error-message (Exception.))))))

