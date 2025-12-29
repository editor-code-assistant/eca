(ns eca.client-test-helpers-test
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [eca.client-http :as client]
   [eca.client-test-helpers :as cth]
   [hato.client :as hato])
  (:import [io.netty.buffer Unpooled]
           [io.netty.handler.codec.http DefaultFullHttpRequest DefaultFullHttpResponse DefaultHttpRequest HttpMethod HttpVersion]
           [java.io IOException]
           [java.net Authenticator InetSocketAddress PasswordAuthentication ProxySelector URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [org.littleshoot.proxy.impl  DefaultHttpProxyServer]))

(deftest source-handler-tests
  (testing "successful plain text request with plain text response"
    (let [handler-called (atom nil)
          handler-fn (fn [req]
                       (reset! handler-called req)
                       {:status 201
                        :headers {"X-Reply" "yes"}
                        :body "plain-response"})
          filters   (.filterRequest  (cth/proxy-filters-handler-make handler-fn)
                                    nil nil)
          content   (Unpooled/copiedBuffer "hello world" StandardCharsets/UTF_8)
          req       (DefaultFullHttpRequest.
                     HttpVersion/HTTP_1_1 HttpMethod/POST "/t" content)]

      (.set (.headers req) "X-Test" "value")

      (let [^DefaultFullHttpResponse resp (.proxyToServerRequest filters req)
            body (.toString (.content resp) StandardCharsets/UTF_8)]
        (is (= 201 (.code (.getStatus resp))))
        (is (= "plain-response" body))
        (is (= "yes" (.get (.headers resp) "X-Reply")))
        (is (= {:method "POST"
                :uri "/t"
                :protocol "HTTP/1.1"
                :headers {"X-Test" "value"}
                :body "hello world"}
               @handler-called)))))

  (testing "successful plain text request with map body as application/json response"
    (let [handler-fn (fn [_] {:status 202
                              :headers {"X-Type" "json"}
                              :body {:msg "ok"}})
          filters   (.filterRequest (cth/proxy-filters-handler-make handler-fn)
                                    nil nil)
          content   (Unpooled/copiedBuffer "ignored" StandardCharsets/UTF_8)
          req       (DefaultFullHttpRequest.
                     HttpVersion/HTTP_1_1 HttpMethod/GET "/json" content)

          ^DefaultFullHttpResponse resp (.proxyToServerRequest filters req)
          body      (.toString (.content resp) StandardCharsets/UTF_8)]

      (is (= 202 (.code (.getStatus resp))))
      (is (= {:msg "ok"} (json/parse-string body true)))
      ;; handler returned a map -> Content-Type should be json
      (is (= "application/json" (.get (.headers resp) "Content-Type")))))

  (testing "successful application/json request with map boyd as application/json response"
    (let [handler-called (atom nil)
          handler-fn (fn [req]
                       (reset! handler-called req)
                       {:status 200
                        :body {:echo (:body req)}})
          filters   (.filterRequest (cth/proxy-filters-handler-make handler-fn)
                                    nil nil)
          payload   "{\"a\":1}"
          content   (Unpooled/copiedBuffer payload StandardCharsets/UTF_8)
          req       (DefaultFullHttpRequest.
                     HttpVersion/HTTP_1_1 HttpMethod/POST "/echo" content)]

      (.set (.headers req) "Content-Type" "application/json")

      (let [^DefaultFullHttpResponse resp (.proxyToServerRequest filters req)
            body (.toString (.content resp) StandardCharsets/UTF_8)]

        (is (= 200 (.code (.getStatus resp))))
        (is (= {:echo {:a 1}} (json/parse-string body true)))
        (is (= {:method "POST"
                :uri "/echo"
                :protocol "HTTP/1.1"
                :headers {"Content-Type" "application/json"}
                :body {:a 1}}
               @handler-called)))))

  (testing "successful plain text request with handler throwing an exception"
    (let [handler-fn (fn [_] (throw (ex-info "handler failure" {})))
          filters   (.filterRequest (cth/proxy-filters-handler-make handler-fn)
                                    nil nil)
          content   (Unpooled/copiedBuffer "hello" StandardCharsets/UTF_8)
          req       (DefaultFullHttpRequest.
                     HttpVersion/HTTP_1_1 HttpMethod/POST "/fail" content)
          ^DefaultFullHttpResponse resp      (.proxyToServerRequest filters req)
          body      (.toString (.content resp) StandardCharsets/UTF_8)]

      ;; The proxy should return 400 and include the error message
      (is (= 400 (.code (.getStatus resp))))
      (is (clojure.string/includes? body "handler failure"))))

  (testing "request is not a FullHttpRequest"
    (let [handler-fn (fn [_] {:status 200 :body "should not be called"})
          proxy-src  (cth/proxy-filters-handler-make handler-fn)
          filters    (.filterRequest proxy-src nil nil)
          ;; create a non-FullHttpRequest (DefaultHttpRequest without aggregated content)
          req        (DefaultHttpRequest. HttpVersion/HTTP_1_1 HttpMethod/POST "/not-full")
          ^DefaultFullHttpResponse resp       (.proxyToServerRequest filters req)
          body       (.toString (.content resp) StandardCharsets/UTF_8)]

      ;; The proxy should return 400 with the dev error message
      (is (= 400 (.code (.getStatus resp))))
      (is (clojure.string/includes? body "expected FullHttpRequest")))))

(deftest little-proxy-interceptor-make-test
  (testing "intercepting simple GET request via LittleProxy"
    (let [handler-called (atom nil)
          handler-fn (fn [req]
                       (reset! handler-called req)
                       {:status 200 :body {:msg "ok"}})
          ;; start the proxy
          prx (cth/little-proxy-interceptor-make handler-fn)
          prx-host (:host prx)
          prx-port (:port prx)]
      (try
        (let [client (-> (HttpClient/newBuilder)
                         (.proxy (ProxySelector/of (InetSocketAddress. ^String prx-host ^long prx-port)))
                         (.build))
              request (-> (HttpRequest/newBuilder)
                          (.uri (URI/create "http://localhost:99/test"))
                          (.GET)
                          (.build))
              response (.send client request (HttpResponse$BodyHandlers/ofString))
              body-str (.body response)
              body-map (json/parse-string body-str true)]
          (is (= 200 (.statusCode response)))
          (is (= {:msg "ok"} body-map))
          (is (= "/test" (:uri @handler-called)))
          (is (= "GET" (:method @handler-called))))
        (finally
          (.abort ^DefaultHttpProxyServer (:px prx))))))

  (testing "intercepting simple POST request via LittleProxy"
    (let [handler-called (atom nil)
          handler-fn (fn [req]
                       (reset! handler-called req)
                       {:status 201
                        :body {:msg "created"}})
          prx (cth/little-proxy-interceptor-make handler-fn)
          prx-host (:host prx)
          prx-port (:port prx)]
      (try
        (let [client (-> (HttpClient/newBuilder)
                         (.proxy (ProxySelector/of (InetSocketAddress. ^String prx-host ^long prx-port)))
                         (.build))
              payload "{\"foo\":\"bar\"}"
              request (-> (HttpRequest/newBuilder)
                          (.uri (URI/create "http://localhost:99/create"))
                          (.POST (HttpRequest$BodyPublishers/ofString payload))
                          (.header "Content-Type" "application/json")
                          (.build))
              response (.send client request (HttpResponse$BodyHandlers/ofString))
              body-str (.body response)
              body-map (json/parse-string body-str true)]
          (is (= 201 (.statusCode response)))
          (is (= {:msg "created"} body-map))
          (is (= "/create" (:uri @handler-called)))
          (is (= "POST" (:method @handler-called)))
          (is (= {:foo "bar"} (:body @handler-called))))
        (finally
          (.abort ^DefaultHttpProxyServer (:px prx))))))

  (testing "intercepting simple POST request with error response via LittleProxy"
    (let [handler-fn (fn [_] (throw (ex-info "handler failure" {})))
          prx (cth/little-proxy-interceptor-make handler-fn)
          ^String prx-host (:host prx)
          ^long prx-port (:port prx)]
      (try
        (let [client (-> (HttpClient/newBuilder)
                         (.proxy (ProxySelector/of (InetSocketAddress. prx-host prx-port)))
                         (.build))
              payload "{\"bad\":true}"
              request (-> (HttpRequest/newBuilder)
                          (.uri (URI/create "http://localhost:99/fail"))
                          (.POST (HttpRequest$BodyPublishers/ofString payload))
                          (.header "Content-Type" "application/json")
                          (.build))
              response (.send client request (HttpResponse$BodyHandlers/ofString))
              body-str (.body response)]
          (is (= 400 (.statusCode response)))
          (is (clojure.string/includes? body-str "handler failure")))
        (finally
          (.abort ^DefaultHttpProxyServer (:px prx))))))

  (testing "HTTPS CONNECT request hits proxy"
    (let [req* (atom nil)
          handler-fn (fn [req]
                       (reset! req* req)
                       {:status 200 :body ""})
          prx (cth/little-proxy-interceptor-make handler-fn)
          ^String host (:host prx)
          ^long port (:port prx)]

      (try
        (let [client (-> (HttpClient/newBuilder)
                         (.proxy (ProxySelector/of
                                  (InetSocketAddress. host port)))
                         (.build))
              request (-> (HttpRequest/newBuilder)
                          (.uri (URI/create "https://localhost/"))
                          (.GET)
                          (.build))]

          ;; We don't care if HTTPS fails after tunneling,
          ;; we only care that CONNECT hit the proxy.
          (try
            (.send client request (HttpResponse$BodyHandlers/ofString))
            (catch Exception _))

          ;; Confirm CONNECT request observed
          (is (= "CONNECT" (:method @req*))))

        (finally
          (.abort ^DefaultHttpProxyServer (:px prx))))))

  (testing "intercepting simple GET request with via LittleProxy with authentication"
    (let [handler-called (atom nil)
          handler-fn (fn [req]
                       (reset! handler-called req)
                       {:status 200 :body {:msg "ok"}})
          username "user1"
          password "pass1"
          prx (cth/little-proxy-interceptor-make handler-fn username password)
          ^String prx-host (:host prx)
          ^long prx-port (:port prx)]
      (try
        (let [client (-> (HttpClient/newBuilder)
                         (.proxy (ProxySelector/of (InetSocketAddress. prx-host prx-port)))
                         (.authenticator (proxy [Authenticator] []
                                           (getPasswordAuthentication []
                                             (PasswordAuthentication. username (char-array password)))))
                         (.build))
              request (-> (HttpRequest/newBuilder)
                          (.uri (URI/create "http://localhost:99/auth"))
                          (.GET)
                          (.build))
              response (.send client request (HttpResponse$BodyHandlers/ofString))
              body-str (.body response)
              body-map (json/parse-string body-str true)]
          (is (= 200 (.statusCode response)))
          (is (= {:msg "ok"} body-map))
          (is (= "/auth" (:uri @handler-called))))
        (finally
          (.abort ^DefaultHttpProxyServer (:px prx))))))

  (testing "intercepting simple GET request via LittleProxy with wrong authentication"
    (let [handler-fn (fn [_] {:status 200 :body {:msg "ok"}})
          username "user1"
          password "pass1"
          prx (cth/little-proxy-interceptor-make handler-fn username password)
          ^String prx-host (:host prx)
          ^long prx-port (:port prx)]
      (try
        ;; HttpClient supplies wrong creds
        (let [client (-> (HttpClient/newBuilder)
                         (.proxy (ProxySelector/of (InetSocketAddress. prx-host prx-port)))
                         (.authenticator (proxy [Authenticator] []
                                           (getPasswordAuthentication []
                                             (PasswordAuthentication. "wrong" (char-array "creds")))))
                         (.build))
              request (-> (HttpRequest/newBuilder)
                          (.uri (URI/create "http://localhost:99/auth-fail"))
                          (.GET)
                          (.build))]
          (is (thrown-with-msg?
               IOException
               #"too many authentication attempts"
               (.send client request (HttpResponse$BodyHandlers/ofString)))))
        (finally
          (.abort ^DefaultHttpProxyServer (:px prx))))))

  (testing "proxy requires authentication but client provides none"
    (let [handler-fn (fn [_] {:status 200 :body {:msg "ok"}})
          username "user1"
          password "pass1"
          prx (cth/little-proxy-interceptor-make handler-fn username password)
          ^String prx-host (:host prx)
          ^long prx-port (:port prx)]
      (try
        ;; client has no authenticator at all
        (let [client (-> (HttpClient/newBuilder)
                         (.proxy (ProxySelector/of (InetSocketAddress. prx-host prx-port)))
                         (.build))
              request (-> (HttpRequest/newBuilder)
                          (.uri (URI/create "http://localhost:13/no-auth"))
                          (.GET)
                          (.build))
              response (.send client request (HttpResponse$BodyHandlers/ofString))]

          ;; Proxy Authentication Required (407)
          (is (= 407 (.statusCode response))))

        (finally
          (.abort ^DefaultHttpProxyServer (:px prx))))))

  (testing "intercepting simple HTTPS GET via proxy with authentication"
    ;; requires 'jdk.http.auth.tunneling.disabledSchemes=', because
    ;; we're going to make an https requests to an http proxy with
    ;; BASIC authentications, which is otherwise in th disabled scheme.
    (let [req* (atom nil)
          handler-fn (fn [req]
                       (reset! req* req)
                       {:status 200 :body {:msg "ok"}})
          user "u1"
          pass "p1"
          prx (cth/little-proxy-interceptor-make handler-fn user pass)
          ^String host (:host prx)
          ^long port (:port prx)]
      (try
        (let [client (-> (HttpClient/newBuilder)
                         (.proxy (ProxySelector/of (InetSocketAddress. host port)))
                         (.authenticator
                          (proxy [Authenticator] []
                            (getPasswordAuthentication []
                              (PasswordAuthentication. user (char-array pass)))))
                         (.build))
              req (-> (HttpRequest/newBuilder)
                      (.uri (URI/create "https://localhost/x"))
                      (.GET)
                      (.build))]
          ;; We don't care if HTTPS fails after tunneling,
          ;; we only care that CONNECT hit the proxy.
          (try
            (.send client req (HttpResponse$BodyHandlers/ofString))
            (catch Exception _))

          ;; Confirm CONNECT request observed
          (is (= "CONNECT" (:method @req*))))
        (finally
          (.abort ^DefaultHttpProxyServer (:px prx)))))))

(deftest with-proxy-test
  (testing "can http post to the proxy and get a successful response"
    (cth/with-proxy {}
      (fn [_] {:status 201 :body "ok"})
      (let [client (-> (HttpClient/newBuilder)
                       (.proxy (ProxySelector/of
                                (InetSocketAddress. cth/*proxy-host* ^long cth/*proxy-port*)))
                       (.build))

            req (-> (HttpRequest/newBuilder)
                    (.uri (URI/create "http://localhost:99/test"))
                    (.POST (HttpRequest$BodyPublishers/ofString "hello"))
                    (.build))

            resp (.send client req (HttpResponse$BodyHandlers/ofString))]

        (is (= 201 (.statusCode resp)))
        (is (= "ok" (.body resp))))))

  (testing "can http post to the proxy but handler returns an exception"
    (cth/with-proxy {}
      (fn [_] (throw (ex-info "boom" {})))
      (let [client (-> (HttpClient/newBuilder)
                       (.proxy (ProxySelector/of
                                (InetSocketAddress. cth/*proxy-host* ^long cth/*proxy-port*)))
                       (.build))

            req (-> (HttpRequest/newBuilder)
                    (.uri (URI/create "http://localhost:99/fail"))
                    (.POST (HttpRequest$BodyPublishers/ofString "x"))
                    (.build))

            resp (.send client req (HttpResponse$BodyHandlers/ofString))]

        (is (= 400 (.statusCode resp)))
        (is (clojure.string/includes? (.body resp) "boom"))))))

(deftest with-proxy-authentication-tests
  (testing "Requires username and password when proxy authentication is enabled"
    (cth/with-proxy {:user "user1" :pass "secret"}
      (fn [_] {:status 200 :body "ok"})
      (let [client (-> (HttpClient/newBuilder)
                       (.proxy (ProxySelector/of (InetSocketAddress. cth/*proxy-host* ^long cth/*proxy-port*)))
                       (.authenticator
                        (proxy [Authenticator] []
                          (getPasswordAuthentication []
                            (PasswordAuthentication. "user1" (char-array "secret")))))
                       (.build))
            req (-> (HttpRequest/newBuilder)
                    (.uri (URI/create "http://localhost:99/test"))
                    (.GET)
                    (.build))
            resp (.send client req (HttpResponse$BodyHandlers/ofString))]
        (is (= 200 (.statusCode resp)))
        (is (= "ok" (.body resp))))))

  (testing "Rejects requests without credentials if authentication is set"
    (cth/with-proxy {:user "user1" :pass "secret"}
      (fn [_] {:status 200 :body "ok"})
      (let [client (-> (HttpClient/newBuilder)
                       (.proxy (ProxySelector/of (InetSocketAddress. cth/*proxy-host* ^long cth/*proxy-port*)))
                       (.build))
            req (-> (HttpRequest/newBuilder)
                    (.uri (URI/create "http://localhost:99/test"))
                    (.GET)
                    (.build))
            resp (.send client req (HttpResponse$BodyHandlers/ofString))]
        (is (not= 200 (.statusCode resp)))
        (is (clojure.string/includes? (.body resp) "Proxy Authentication"))))))

(deftest with-client-proxied-test
  (testing "Restores `eca.client-http/*hato-http-client*` to nil after BODY executes"
    (is (nil? client/*hato-http-client*))
    (cth/with-client-proxied {}
      (fn [_] {:status 200 :body "ok"})
      ;; Inside BODY, the client is set
      (is (some? client/*hato-http-client*)))
    ;; After BODY, the client should be reset
    (is (nil? client/*hato-http-client*)))

  (testing "Routes HTTP requests made through Hato to the temporary proxy"
    (cth/with-client-proxied {}
      (fn [req] {:status 200 :body (:uri req)})
      (let [resp (hato/get "http://localhost:99/test"
                           {:http-client client/*hato-http-client*})]
        (is (= 200 (:status resp)))
        (is (= "/test" (:body resp))))))

  (testing "Captures all calls to `merge-with-global-http-client` during BODY"
    (cth/with-client-proxied {:abc 52}
      (fn [_] {:status 200 :body "ok"})
        ;; Make some merge calls inside BODY
      (client/merge-with-global-http-client {:foo "bar"})
      (client/merge-with-global-http-client {:baz 42})
        ;; Assert that all merged results were captured
      (let [captures @cth/*http-client-captures*]
        (is (= 2 (count captures)))
        (is (= {:foo "bar" :abc 52} (dissoc (first captures) :proxy)))
        (is (= {:baz 42 :abc 52} (dissoc (second captures) :proxy)))))))

