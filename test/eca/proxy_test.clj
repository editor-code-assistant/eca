(ns eca.proxy-test
  (:require [clojure.test :refer [deftest is testing]]
            [eca.config :as config]
            [eca.proxy :as p]))

(deftest proxy-urls-system-env-get-tests
  (testing "Returns correct HTTP and HTTPS proxy values when only lowercase env vars are set"
    (with-redefs [config/get-env (fn [k] (case k "http_proxy" "http://lc-http" "https_proxy" "https://lc-https"))]
      (is (= (p/proxy-urls-system-env-get) {:http "http://lc-http" :https "https://lc-https"}))))

  (testing "Returns correct HTTP and HTTPS proxy values when only uppercase env vars are set"
    (with-redefs [config/get-env (fn [k] (case k "HTTP_PROXY" "http://uc-http" "HTTPS_PROXY" "https://uc-https" nil))]
      (is (= (p/proxy-urls-system-env-get) {:http "http://uc-http" :https "https://uc-https"}))))

  (testing "Lowercase env vars take precedence over uppercase"
    (with-redefs [config/get-env (fn [k] (case k "http_proxy" "http://lc-http" "HTTP_PROXY" "http://uc-http"
                                          "https_proxy" "https://lc-https" "HTTPS_PROXY" "https://uc-https"))]
      (is (= (p/proxy-urls-system-env-get) {:http "http://lc-http" :https "https://lc-https"}))))

  (testing "Returns nil when no proxy environment variables are set"
    (with-redefs [config/get-env (fn [_] nil)]
      (is (= (p/proxy-urls-system-env-get) {:http nil :https nil}))))

  (testing "Handles mixed case: lowercase and uppercase env vars present"
    (with-redefs [config/get-env (fn [k] (case k "HTTP_PROXY" "http://uc-http"
                                               "https_proxy" "https://lc-https" "HTTPS_PROXY" "https://uc-https"
                                               nil))]
      (is (= (p/proxy-urls-system-env-get) {:http "http://uc-http" :https "https://lc-https"})))))

(deftest parse-proxy-url-tests
  (testing "Parses full URL with scheme, host, port, username, and password"
    (is (= (p/parse-proxy-url "http://user:pass@example.com:8080")
           {:host "example.com" :port 8080 :username "user" :password "pass"})))

  (testing "Defaults port to 80 for http when not provided"
    (is (= (p/parse-proxy-url "http://example.com")
           {:host "example.com" :port 80 :username nil :password nil})))

  (testing "Defaults port to 443 for https when not provided"
    (is (= (p/parse-proxy-url "https://example.com")
           {:host "example.com" :port 443 :username nil :password nil})))

  (testing "Returns nil for blank or nil input"
    (is (nil? (p/parse-proxy-url nil)))
    (is (nil? (p/parse-proxy-url "")))
    (is (nil? (p/parse-proxy-url "   "))))

  (testing "Parses URL with no user info"
    (is (= (p/parse-proxy-url "http://example.com:8080")
           {:host "example.com" :port 8080 :username nil :password nil})))

  (testing "Decodes URL-encoded username and password"
    (is (= (p/parse-proxy-url "http://user%20name:pa%24s@example.com:8080")
           {:host "example.com" :port 8080 :username "user name" :password "pa$s"})))

  (testing "Throws IllegalArgumentException if scheme is unknown and port not provided"
    (is (thrown-with-msg? IllegalArgumentException
          #"Unsupported scheme: ftp"
          (p/parse-proxy-url "ftp://example.com"))))

  (testing "Handles URLs with only host and scheme"
    (is (= (p/parse-proxy-url "https://example.com")
           {:host "example.com" :port 443 :username nil :password nil})))

  (testing "Handles URLs with unusual but valid characters in host or user info"
    (is (= (p/parse-proxy-url "http://u_ser:pa-ss@sub.example.com:8080")
           {:host "sub.example.com" :port 8080 :username "u_ser" :password "pa-ss"}))))

(deftest proxy-urls-parse-tests
  (testing "Parses both :http and :https URLs correctly"
    (let [urls {:http  "http://user:pass@http.com:8080"
                :https "https://https.com:8443"}]
      (is (= (p/proxy-urls-parse urls)
             {:http  {:host "http.com" :port 8080 :username "user" :password "pass"}
              :https {:host "https.com" :port 8443 :username nil :password nil}}))))

  (testing "Returns nil for missing or nil URL values"
    (let [urls {:http  nil
                :https ""}]
      (is (= (p/proxy-urls-parse urls)
             {:http nil :https nil}))))

  (testing "Handles URLs with only one of :http or :https present"
    (let [urls {:http "http://only-http.com"}]
      (is (= (p/proxy-urls-parse urls)
             {:http {:host "only-http.com" :port 80 :username nil :password nil}}))))

  (testing "Ignores unrelated keys in the input map"
    (let [urls {:http "http://http.com"
                :https "https://https.com"
                :ftp "ftp://ftp.com"}]
      (is (= (p/proxy-urls-parse urls)
             {:http  {:host "http.com" :port 80 :username nil :password nil}
              :https {:host "https.com" :port 443 :username nil :password nil}})))))

(deftest env-proxy-urls-parse-tests
  (testing "Parses both HTTP and HTTPS proxy environment variables correctly"
    (with-redefs [config/get-env (fn [env] (case env
                                              "http_proxy"  "http://user:pass@http.com:8080"
                                              "https_proxy" "https://https.com:8443"))]
      (is (= (p/env-proxy-urls-parse)
             {:http  {:host "http.com" :port 8080 :username "user" :password "pass"}
              :https {:host "https.com" :port 8443 :username nil :password nil}}))))

  (testing "Returns nil for proxies not set in the environment"
    (with-redefs [config/get-env (fn [_] nil)]
      (is (= (p/env-proxy-urls-parse)
             {:http nil :https nil}))))

  (testing "Handles cases where only one of :http or :https is set"
    (with-redefs [config/get-env (fn [env] (case env
                                              "http_proxy" "http://only-http.com"
                                              nil))]
      (is (= (p/env-proxy-urls-parse)
             {:http  {:host "only-http.com" :port 80 :username nil :password nil}
              :https nil})))))

