(ns eca.client-http-test
  (:require [clojure.test :refer [deftest is testing]]
            [eca.client-http :as client]
            [eca.client-test-helpers :refer [with-proxy *proxy-host* *proxy-port*]]
            [eca.config :as config]
            [hato.client :as hato])
  (:import [java.io IOException]))

(deftest hato-client-make-test
  (testing "proxy http setup"
    (with-proxy {}
      (fn [_req]
        {:status 200
         :body "hello"})

      (let [client (client/hato-client-make {:eca.client-http/proxy-http {:host *proxy-host*
                                                                          :port *proxy-port*}})
            response (hato/post
                      "http://localhost:99/now"
                      {:http-client client})]
        (is (= {:uri "http://localhost:99/now",
                :status 200,
                :body "hello",
                :version :http-1.1
                :request {:user-info nil,
                          :http-client {},
                          :headers {"accept-encoding" "gzip, deflate"},
                          :server-port 99,
                          :url "http://localhost:99/now",
                          :uri "/now",
                          :server-name "localhost",
                          :query-string nil,
                          :scheme :http,
                          :request-method :post}}
               (-> response
                   (select-keys  [:uri :status :body :version :request])
                   (update-in [:request :http-client] dissoc :proxy)
                   (update-in [:request] dissoc :http-request)))))))

  (testing "Handles both HTTP and HTTPS proxies and selects based on URI scheme"
    (let [reqs* (atom [])]
      (with-proxy {}
        (fn [req]
          (swap! reqs* conj req)
          {:status 200 :body (:uri req)})

        (let [client (client/hato-client-make {:eca.client-http/proxy-http {:host *proxy-host* :port *proxy-port*}
                                               :eca.client-http/proxy-https {:host *proxy-host* :port *proxy-port*}})]
          ;; HTTP request
          (let [http-resp (hato/post "http://localhost:99/http" {:http-client client})]
            (is (= 200 (:status http-resp)))
            (is (= "/http" (:body http-resp))))

          ;; HTTPS request
          (is (thrown-with-msg? ;; expected as we only testing rerouting through proxy
               Exception
               #"Unrecognized SSL message, plaintext connection?" (hato/post "https://localhost/https" {:http-client client})))
          (let [req2 (second @reqs*)]
            (is (= {:method "CONNECT" :uri "localhost:443"} (select-keys  req2 [:method :uri]))))))))

  (testing "Includes an authenticator when proxy username and password are provided"
    (with-proxy {:user "u" :pass "p"}
      (fn [req] {:status 200 :body (:uri req)})
      (let [client (client/hato-client-make {:eca.client-http/proxy-http {:host *proxy-host* :port *proxy-port* :username "u" :password "p"}})
            response (hato/post "http://localhost:99/auth" {:http-client client})]
        (is (= 200 (:status response)))
        (is (= "/auth" (:body response))))))

  (testing "Includes an authenticator when proxy username and password are provided (HTTPS)"
    (let [req* (atom nil)]
      (with-proxy {:user "us" :pass "ps"}
        (fn [req]
          (reset! req* req)
          {:status 200 :body (:uri req)})
        (let [client (client/hato-client-make {:eca.client-http/proxy-https {:host *proxy-host* :port *proxy-port* :username "us" :password "ps"}})]
          (is (thrown-with-msg?
               Exception
               #"Unrecognized SSL message, plaintext connection?" (hato/post "https://localhost/auth" {:http-client client})))
          (is (= {:method "CONNECT" :uri "localhost:443"} (select-keys @req* [:method :uri])) @req*)))))

  (testing "Rejects requests when proxy username and password are incorrect"
    (with-proxy {:user "correct-user" :pass "correct-pass"}
      (fn [req]
        {:status 200 :body (:uri req)})
      (let [client (client/hato-client-make
                    {:eca.client-http/proxy-http
                     {:host *proxy-host*
                      :port *proxy-port*
                      :username "wrong-user"
                      :password "wrong-pass"}})]
        (is (thrown-with-msg? ;; expected as we only testing rerouting through proxy
             IOException
             #"too many authentication attempts"
             (hato/post "http://localhost:99/fail-auth" {:http-client client}))))))

  (testing "Rejects requests when proxy username and password are incorrect (HTTPS)"
    (let [req* (atom nil)]
      (with-proxy {:user "correct-user" :pass "correct-pass"}
        (fn [req]
          (reset! req* req)
          {:status 200 :body (:uri req)})
        (let [client (client/hato-client-make
                      {:eca.client-http/proxy-https
                       {:host *proxy-host*
                        :port *proxy-port*
                        :username "wrong-user"
                        :password "wrong-pass"}})]
          ;; it throws a very intrinsic exception when proxy authentication fails using https BASIC authentication:
          ;;   Caused by: java.lang.NullPointerException: Cannot invoke "jdk.internal.net.http.ExchangeImpl.cancel(java.io.IOException)" because "exch.exchImpl" is null ...
          (is (thrown? ;; expected as we only testing rerouting through proxy
               Exception
               (hato/post "https://localhost/fail-auth" {:http-client client})))
          (is (nil? @req*))))))

  (testing "uses shared proxy credentials when both HTTP and HTTPS credentials match"
    (let [reqs* (atom [])]
      (with-proxy {:user "u" :pass "p"}
        (fn [req]
          (swap! reqs* conj req)
          {:status 200 :body (:uri req)})

        (let [client (client/hato-client-make
                      {:eca.client-http/proxy-http
                       {:host *proxy-host* :port *proxy-port* :username "u" :password "p"}
                       :eca.client-http/proxy-https
                       {:host *proxy-host* :port *proxy-port* :username "u" :password "p"}})]

          ;; HTTP request uses proxy + creds
          (let [resp (hato/post "http://localhost:99/http" {:http-client client})]
            (is (= 200 (:status resp)))
            (is (= "/http" (:body resp))))

          ;; HTTPS request routes via CONNECT using same creds
          (is (thrown-with-msg?
               Exception
               #"Unrecognized SSL message, plaintext connection?"
               (hato/post "https://localhost/https" {:http-client client})))

          (is (= {:method "CONNECT" :uri "localhost:443"}
                 (select-keys (second @reqs*) [:method :uri])))))))

  (testing "rejects configuration when HTTP and HTTPS proxy credentials differ"
    (with-proxy {:user "u1" :pass "p1"}
      (fn [_req] {:status 200 :body "ok"})
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"must be identical"
           (client/hato-client-make
            {:eca.client-http/proxy-http
             {:host *proxy-host* :port *proxy-port* :username "u1" :password "p1"}
             :eca.client-http/proxy-https
             {:host *proxy-host* :port *proxy-port* :username "u2" :password "p2"}}))))))

(deftest hato-client-global-setup-tests
  (testing "Hato uses a system proxy through *hato-http-client*"
    (with-proxy {}
      (fn [req]
        {:status 200
         :body (str "proxied:" (:uri req))})

      ;; Override get-env to simulate HTTP/HTTPS proxy variables
      (with-redefs [config/get-env (fn [env]
                                     (case env
                                       "http_proxy"  (str "http://" *proxy-host* ":" *proxy-port*)
                                       nil))]
        (try
          (client/hato-client-global-setup! {:timeout 1000})

          ;; Make a request using the global client
          (let [resp (hato/get "http://localhost:99/test" {:http-client client/*hato-http-client*})]
            (is (= 200 (:status resp)))
            (is (= "proxied:/test" (:body resp))))

          (finally
            (alter-var-root #'client/*hato-http-client* (constantly nil)))))))

  (testing "Hato uses an HTTPS system proxy through *hato-http-client*"
    (let [req* (atom nil)]
      (with-proxy {}
        (fn [req]
          (reset! req* req)
          {:status 200
           :body (:uri req)})

        (with-redefs [config/get-env (fn [env]
                                       (case env
                                         "https_proxy" (str "http://" *proxy-host* ":" *proxy-port*)
                                         nil))]
          (try
            (client/hato-client-global-setup! {:timeout 1000})

            (is (thrown-with-msg? ;; expected as we only testing rerouting through proxy
                 Exception
                 #"Unrecognized SSL message, plaintext connection?" (hato/get "https://localhost/test" {:http-client client/*hato-http-client*})))
            (is (= {:method "CONNECT" :uri "localhost:443"} (select-keys @req* [:method :uri])))

            (finally
              (alter-var-root #'client/*hato-http-client* (constantly nil)))))))))
#_(hato-client-global-setup-tests)
