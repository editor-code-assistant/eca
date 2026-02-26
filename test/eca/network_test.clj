(ns eca.network-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.network :as network]))

(deftest proxy-urls-system-env-get-tests
  (testing "Returns correct HTTP and HTTPS proxy values when only lowercase env vars are set"
    (with-redefs [config/get-env (fn [k] (case k "http_proxy" "http://lc-http" "https_proxy" "https://lc-https"))]
      (is (= (network/proxy-urls-system-env-get) {:http "http://lc-http" :https "https://lc-https"}))))

  (testing "Returns correct HTTP and HTTPS proxy values when only uppercase env vars are set"
    (with-redefs [config/get-env (fn [k] (case k "HTTP_PROXY" "http://uc-http" "HTTPS_PROXY" "https://uc-https" nil))]
      (is (= (network/proxy-urls-system-env-get) {:http "http://uc-http" :https "https://uc-https"}))))

  (testing "Lowercase env vars take precedence over uppercase"
    (with-redefs [config/get-env (fn [k] (case k "http_proxy" "http://lc-http" "HTTP_PROXY" "http://uc-http"
                                          "https_proxy" "https://lc-https" "HTTPS_PROXY" "https://uc-https"))]
      (is (= (network/proxy-urls-system-env-get) {:http "http://lc-http" :https "https://lc-https"}))))

  (testing "Returns nil when no proxy environment variables are set"
    (with-redefs [config/get-env (fn [_] nil)]
      (is (= (network/proxy-urls-system-env-get) {:http nil :https nil}))))

  (testing "Handles mixed case: lowercase and uppercase env vars present"
    (with-redefs [config/get-env (fn [k] (case k "HTTP_PROXY" "http://uc-http"
                                               "https_proxy" "https://lc-https" "HTTPS_PROXY" "https://uc-https"
                                               nil))]
      (is (= (network/proxy-urls-system-env-get) {:http "http://uc-http" :https "https://lc-https"})))))

(deftest parse-proxy-url-tests
  (testing "Parses full URL with scheme, host, port, username, and password"
    (is (= (network/parse-proxy-url "http://user:pass@example.com:8080")
           {:host "example.com" :port 8080 :username "user" :password "pass"})))

  (testing "Defaults port to 80 for http when not provided"
    (is (= (network/parse-proxy-url "http://example.com")
           {:host "example.com" :port 80 :username nil :password nil})))

  (testing "Defaults port to 443 for https when not provided"
    (is (= (network/parse-proxy-url "https://example.com")
           {:host "example.com" :port 443 :username nil :password nil})))

  (testing "Returns nil for blank or nil input"
    (is (nil? (network/parse-proxy-url nil)))
    (is (nil? (network/parse-proxy-url "")))
    (is (nil? (network/parse-proxy-url "   "))))

  (testing "Parses URL with no user info"
    (is (= (network/parse-proxy-url "http://example.com:8080")
           {:host "example.com" :port 8080 :username nil :password nil})))

  (testing "Decodes URL-encoded username and password"
    (is (= (network/parse-proxy-url "http://user%20name:pa%24s@example.com:8080")
           {:host "example.com" :port 8080 :username "user name" :password "pa$s"})))

  (testing "Throws IllegalArgumentException if scheme is unknown and port not provided"
    (is (thrown-with-msg? IllegalArgumentException
          #"Unsupported scheme: ftp"
          (network/parse-proxy-url "ftp://example.com"))))

  (testing "Handles URLs with only host and scheme"
    (is (= (network/parse-proxy-url "https://example.com")
           {:host "example.com" :port 443 :username nil :password nil})))

  (testing "Handles URLs with unusual but valid characters in host or user info"
    (is (= (network/parse-proxy-url "http://u_ser:pa-ss@sub.example.com:8080")
           {:host "sub.example.com" :port 8080 :username "u_ser" :password "pa-ss"}))))

(deftest proxy-urls-parse-tests
  (testing "Parses both :http and :https URLs correctly"
    (let [urls {:http  "http://user:pass@http.com:8080"
                :https "https://https.com:8443"}]
      (is (= (network/proxy-urls-parse urls)
             {:http  {:host "http.com" :port 8080 :username "user" :password "pass"}
              :https {:host "https.com" :port 8443 :username nil :password nil}}))))

  (testing "Returns nil for missing or nil URL values"
    (let [urls {:http  nil
                :https ""}]
      (is (= (network/proxy-urls-parse urls)
             {:http nil :https nil}))))

  (testing "Handles URLs with only one of :http or :https present"
    (let [urls {:http "http://only-http.com"}]
      (is (= (network/proxy-urls-parse urls)
             {:http {:host "only-http.com" :port 80 :username nil :password nil}}))))

  (testing "Ignores unrelated keys in the input map"
    (let [urls {:http "http://http.com"
                :https "https://https.com"
                :ftp "ftp://ftp.com"}]
      (is (= (network/proxy-urls-parse urls)
             {:http  {:host "http.com" :port 80 :username nil :password nil}
              :https {:host "https.com" :port 443 :username nil :password nil}})))))

(deftest env-proxy-urls-parse-tests
  (testing "Parses both HTTP and HTTPS proxy environment variables correctly"
    (with-redefs [config/get-env (fn [env] (case env
                                              "http_proxy"  "http://user:pass@http.com:8080"
                                              "https_proxy" "https://https.com:8443"))]
      (is (= (network/env-proxy-urls-parse)
             {:http  {:host "http.com" :port 8080 :username "user" :password "pass"}
              :https {:host "https.com" :port 8443 :username nil :password nil}}))))

  (testing "Returns nil for proxies not set in the environment"
    (with-redefs [config/get-env (fn [_] nil)]
      (is (= (network/env-proxy-urls-parse)
             {:http nil :https nil}))))

  (testing "Handles cases where only one of :http or :https is set"
    (with-redefs [config/get-env (fn [env] (case env
                                              "http_proxy" "http://only-http.com"
                                              nil))]
      (is (= (network/env-proxy-urls-parse)
             {:http  {:host "only-http.com" :port 80 :username nil :password nil}
              :https nil})))))

;;;; ---- TLS tests -----------------------------------------------------------

(deftest read-network-config-test
  (testing "Reads from config network section"
    (let [cfg {:network {:caCertFile "/path/to/ca.pem"
                         :clientCert "/path/to/cert.pem"
                         :clientKey "/path/to/key.pem"
                         :clientKeyPassphrase "secret"}}]
      (with-redefs [config/get-env (fn [_] nil)]
        (is (= (network/read-network-config cfg)
               {:ca-cert-file "/path/to/ca.pem"
                :client-cert "/path/to/cert.pem"
                :client-key "/path/to/key.pem"
                :client-key-passphrase "secret"})))))

  (testing "Falls back to env vars when config is empty"
    (with-redefs [config/get-env (fn [k] (case k
                                           "SSL_CERT_FILE" "/env/ca.pem"
                                           "ECA_CLIENT_CERT" "/env/cert.pem"
                                           "ECA_CLIENT_KEY" "/env/key.pem"
                                           "ECA_CLIENT_KEY_PASSPHRASE" "env-secret"
                                           nil))]
      (is (= (network/read-network-config {})
             {:ca-cert-file "/env/ca.pem"
              :client-cert "/env/cert.pem"
              :client-key "/env/key.pem"
              :client-key-passphrase "env-secret"}))))

  (testing "Falls back to NODE_EXTRA_CA_CERTS when SSL_CERT_FILE is not set"
    (with-redefs [config/get-env (fn [k] (case k
                                           "NODE_EXTRA_CA_CERTS" "/node/ca.pem"
                                           nil))]
      (is (= (:ca-cert-file (network/read-network-config {}))
             "/node/ca.pem"))))

  (testing "Config takes precedence over env vars"
    (let [cfg {:network {:caCertFile "/config/ca.pem"}}]
      (with-redefs [config/get-env (fn [k] (case k
                                             "SSL_CERT_FILE" "/env/ca.pem"
                                             nil))]
        (is (= (:ca-cert-file (network/read-network-config cfg))
               "/config/ca.pem")))))

  (testing "Returns nil values when nothing is configured"
    (with-redefs [config/get-env (fn [_] nil)]
      (is (= (network/read-network-config {})
             {:ca-cert-file nil
              :client-cert nil
              :client-key nil
              :client-key-passphrase nil})))))

(deftest load-pem-certificates-test
  (testing "Throws when file does not exist"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
          #"CA certificate file not found"
          (network/load-pem-certificates "/nonexistent/ca.pem"))))

  (testing "Loads certificates from a PEM file"
    (let [pem-file (java.io.File/createTempFile "test-ca" ".pem")
          pem-content (str "-----BEGIN CERTIFICATE-----\n"
                           "MIIDAzCCAeugAwIBAgIUMx/fpgdW9LHOeQRv8q1+wc8+wmUwDQYJKoZIhvcNAQEL\n"
                           "BQAwETEPMA0GA1UEAwwGdGVzdGNhMB4XDTI2MDIyNDE1MTcxOFoXDTM2MDIyMjE1\n"
                           "MTcxOFowETEPMA0GA1UEAwwGdGVzdGNhMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A\n"
                           "MIIBCgKCAQEAooyukAvNkloXIXMNG9eTY/3KMEKdvit/yglMfzK6dfF10p1G++9c\n"
                           "fkNa8OOSQSkMHseT2je70ROU7rY3hc/1fEL/KqFpqPKWMM6AAaJPqmQ+rfNTJg9H\n"
                           "tXQn8hryJ6Ojd37MFYTJCBpOVTXAvODJcmW3IzFtiOvom5pW3Qc/SwTPk0NFUyh6\n"
                           "2dYDuLLlIPDHv0tWXSGylUQV0gjPJHMUHYFMLfdE9eswYVCV3+3ann6arjRXHSK2\n"
                           "+5EouTodcBgB90luKbCzY+jEfLW6E2FHkaQBOBBSFnpJ6vs1RYG34AMO7PZuKvzP\n"
                           "R5xkkyPK6e8o3os81FXC6erJ8mQOsie4RQIDAQABo1MwUTAdBgNVHQ4EFgQUUUk0\n"
                           "q2u16V+myKaceWR1dBbS7QMwHwYDVR0jBBgwFoAUUUk0q2u16V+myKaceWR1dBbS\n"
                           "7QMwDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAbDcbpk8URZqQ\n"
                           "FwW0w/pcbi5PlzwZawVInODDhaN5G/Ko7zlrOfl6+Nn4HsUXdCve0Oj01HzFD9HT\n"
                           "X6A4hgeWOi5ATsqc/ooNmOssXA50B0Fj1rDApPwwyaUOfyeASwWP9FSChtaAIyq/\n"
                           "Ycy+LMg+4/crkKV+GXvLv/Hw8WbwBWn9MBmfrcMMNj/pxDJD2gvbGwiHcEHENukH\n"
                           "WM7vJgQ16FvmZYWwt24DX8n+tO6bQ+Pl9VSA/heA3NbVpCLHpUGJjPtSR5MColTu\n"
                           "oKmDDKwuz5jEOF1sQi1lBcI6/zxndHqUyQQCo1T+GnvejgqRfUgjaRyK95OOj+Ll\n"
                           "GKxDzKQrYg==\n"
                           "-----END CERTIFICATE-----\n")]
      (try
        (spit pem-file pem-content)
        (let [certs (network/load-pem-certificates (.getAbsolutePath pem-file))]
          (is (= 1 (count certs)))
          (is (instance? java.security.cert.X509Certificate (first certs))))
        (finally
          (.delete pem-file))))))

(deftest build-ssl-context-test
  (testing "Returns nil when no TLS settings"
    (is (nil? (network/build-ssl-context {:ca-cert-file nil
                                          :client-cert nil
                                          :client-key nil}))))

  (testing "Throws when CA cert file does not exist"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
          #"CA certificate file not found"
          (network/build-ssl-context {:ca-cert-file "/nonexistent/ca.pem"})))))

(deftest load-private-key-test
  (testing "Throws when key file does not exist"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
          #"Client key file not found"
          (network/load-private-key "/nonexistent/key.pem" nil)))))
