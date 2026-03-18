(ns eca.oauth-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.oauth :as oauth]
   [hato.client :as http]
   [ring.util.codec :as ring.util]))

(deftest generate-pkce-test
  (testing "generates verifier and challenge"
    (let [{:keys [verifier challenge]} (oauth/generate-pkce)]
      (is (string? verifier))
      (is (string? challenge))
      (is (not= verifier challenge))
      (is (not (string/includes? challenge "="))
          "challenge should not contain padding"))))

(defn- make-auth-response [www-authenticate-header]
  {:status 401
   :headers {"www-authenticate" www-authenticate-header}})

(defn- make-json-response [status body]
  {:status status
   :body body})

(deftest oauth-info-prm-discovery-test
  (testing "discovers metadata via resource_metadata from WWW-Authenticate header"
    (let [get-urls (atom [])]
      (with-redefs [http/head (fn [_ _] {:status 200})
                    http/post (fn [url _]
                                (if (string/includes? url "register")
                                  {:status 404}
                                  (make-auth-response
                                   "Bearer realm=\"test\", resource_metadata=\"https://example.com/prm\"")))
                    http/get (fn [url _opts]
                               (swap! get-urls conj url)
                               (cond
                                 (= url "https://example.com/prm")
                                 (make-json-response 200
                                                     {:authorization_servers ["https://auth.example.com"]
                                                      :scopes_supported ["read" "write"]})

                                 (string/includes? url "oauth-authorization-server")
                                 (make-json-response 200
                                                     {:authorization_endpoint "https://auth.example.com/authorize"
                                                      :token_endpoint "https://auth.example.com/token"})
                                 :else
                                 {:status 404}))]
        (let [info (oauth/oauth-info "https://example.com/mcp")]
          (is (some? info))
          (is (= "https://auth.example.com/token" (:token-endpoint info)))
          (is (= "https://example.com/mcp" (:resource info)))
          (is (string/includes? (:authorization-endpoint info) "scope=read+write"))
          (is (not (string/includes? (:authorization-endpoint info) "scopes=")))
          (is (string/includes? (:authorization-endpoint info)
                                "resource=https%3A%2F%2Fexample.com%2Fmcp"))
          (is (= "https://example.com/prm" (first @get-urls))
              "should fetch resource_metadata URL first")))))

  (testing "falls back to /.well-known/oauth-protected-resource when no resource_metadata in header"
    (let [get-urls (atom [])]
      (with-redefs [http/head (fn [_ _] {:status 200})
                    http/post (fn [url _]
                                (if (string/includes? url "register")
                                  {:status 404}
                                  (make-auth-response "Bearer realm=\"test\"")))
                    http/get (fn [url _]
                               (swap! get-urls conj url)
                               (cond
                                 (= url "https://example.com/.well-known/oauth-protected-resource")
                                 (make-json-response 200
                                                     {:authorization_servers ["https://auth.example.com"]
                                                      :scopes_supported ["all"]})

                                 (string/includes? url "oauth-authorization-server")
                                 (make-json-response 200
                                                     {:authorization_endpoint "https://auth.example.com/authorize"
                                                      :token_endpoint "https://auth.example.com/token"})
                                 :else
                                 {:status 404}))]
        (let [info (oauth/oauth-info "https://example.com/mcp")]
          (is (some? info))
          (is (= "https://auth.example.com/token" (:token-endpoint info)))
          (is (= "https://example.com/.well-known/oauth-protected-resource"
                 (first @get-urls))
              "should try PRM well-known path first")))))

  (testing "falls back to /.well-known/oauth-authorization-server when PRM not available"
    (with-redefs [http/head (fn [_ _] {:status 200})
                  http/post (fn [url _]
                              (if (string/includes? url "register")
                                {:status 404}
                                (make-auth-response "Bearer realm=\"test\"")))
                  http/get (fn [url _]
                             (cond
                               (string/includes? url "oauth-protected-resource")
                               {:status 404}

                               (= url "https://example.com/.well-known/oauth-authorization-server")
                               (make-json-response 200
                                                   {:authorization_endpoint "https://example.com/authorize"
                                                    :token_endpoint "https://example.com/token"
                                                    :scopes_supported ["api"]})
                               :else
                               {:status 404}))]
      (let [info (oauth/oauth-info "https://example.com/mcp")]
        (is (some? info))
        (is (= "https://example.com/token" (:token-endpoint info)))))))

(deftest oauth-info-oidc-discovery-test
  (testing "falls back to OIDC discovery when RFC 8414 metadata not available"
    (with-redefs [http/head (fn [_ _] {:status 200})
                  http/post (fn [url _]
                              (if (string/includes? url "register")
                                {:status 404}
                                (make-auth-response "Bearer realm=\"test\"")))
                  http/get (fn [url _]
                             (cond
                               (string/includes? url "oauth-protected-resource")
                               (make-json-response 200
                                                   {:authorization_servers ["https://auth.example.com/oidc"]})

                               (string/includes? url "oauth-authorization-server")
                               {:status 404}

                               (= url "https://auth.example.com/.well-known/openid-configuration")
                               (make-json-response 200
                                                   {:authorization_endpoint "https://auth.example.com/oidc/authorize"
                                                    :token_endpoint "https://auth.example.com/oidc/token"
                                                    :scopes_supported ["openid" "sql"]})
                               :else
                               {:status 404}))]
      (let [info (oauth/oauth-info "https://example.com/mcp")]
        (is (some? info))
        (is (= "https://auth.example.com/oidc/token" (:token-endpoint info)))
        (is (string/includes? (:authorization-endpoint info) "scope=openid+sql"))))))

(deftest oauth-info-scope-formatting-test
  (testing "formats scopes as space-separated string using 'scope' param"
    (with-redefs [http/head (fn [_ _] {:status 200})
                  http/post (fn [url _]
                              (if (string/includes? url "register")
                                {:status 404}
                                (make-auth-response "Bearer realm=\"test\"")))
                  http/get (fn [url _]
                             (if (string/includes? url "oauth-authorization-server")
                               (make-json-response 200
                                                   {:authorization_endpoint "https://example.com/authorize"
                                                    :token_endpoint "https://example.com/token"
                                                    :scopes_supported ["read" "write" "admin"]})
                               {:status 404}))]
      (let [info (oauth/oauth-info "https://example.com/mcp")
            auth-url (:authorization-endpoint info)]
        (is (string/includes? auth-url "scope=read+write+admin"))
        (is (not (string/includes? auth-url "scopes="))))))

  (testing "omits scope param when scopes_supported is nil"
    (with-redefs [http/head (fn [_ _] {:status 200})
                  http/post (fn [url _]
                              (if (string/includes? url "register")
                                {:status 404}
                                (make-auth-response "Bearer realm=\"test\"")))
                  http/get (fn [url _]
                             (if (string/includes? url "oauth-authorization-server")
                               (make-json-response 200
                                                   {:authorization_endpoint "https://example.com/authorize"
                                                    :token_endpoint "https://example.com/token"})
                               {:status 404}))]
      (let [info (oauth/oauth-info "https://example.com/mcp")
            auth-url (:authorization-endpoint info)]
        (is (not (string/includes? auth-url "scope=")))))))

(deftest oauth-info-resource-parameter-test
  (testing "includes resource parameter in authorization URL"
    (with-redefs [http/head (fn [_ _] {:status 200})
                  http/post (fn [url _]
                              (if (string/includes? url "register")
                                {:status 404}
                                (make-auth-response "Bearer realm=\"test\"")))
                  http/get (fn [url _]
                             (if (string/includes? url "oauth-authorization-server")
                               (make-json-response 200
                                                   {:authorization_endpoint "https://example.com/authorize"
                                                    :token_endpoint "https://example.com/token"})
                               {:status 404}))]
      (let [info (oauth/oauth-info "https://example.com/api/mcp")]
        (is (= "https://example.com/api/mcp" (:resource info)))
        (is (string/includes? (:authorization-endpoint info)
                              "resource=https%3A%2F%2Fexample.com%2Fapi%2Fmcp")))))

  (testing "passes resource in authorize-token!"
    (let [posted-body (atom nil)]
      (with-redefs [http/post (fn [_ opts]
                                (reset! posted-body (:body opts))
                                {:status 200
                                 :body (java.io.ByteArrayInputStream.
                                        (.getBytes "{\"access_token\":\"tok\",\"expires_in\":3600}"))})]
        (oauth/authorize-token! {:token-endpoint "https://example.com/token"
                                 :verifier "test-verifier"
                                 :client-id "test-client"
                                 :redirect-uri "http://localhost:9999/auth/callback"
                                 :resource "https://example.com/api/mcp"}
                                "auth-code")
        (let [decoded (ring.util/form-decode @posted-body)]
          (is (= "https://example.com/api/mcp" (get decoded "resource")))))))

  (testing "passes resource in refresh-token!"
    (let [posted-body (atom nil)]
      (with-redefs [http/post (fn [_ opts]
                                (reset! posted-body (:body opts))
                                {:status 200
                                 :body (java.io.ByteArrayInputStream.
                                        (.getBytes "{\"access_token\":\"new-tok\",\"expires_in\":3600}"))})]
        (oauth/refresh-token! "https://example.com/token"
                              "test-client"
                              "old-refresh-token"
                              :resource "https://example.com/api/mcp")
        (let [decoded (ring.util/form-decode @posted-body)]
          (is (= "https://example.com/api/mcp" (get decoded "resource")))))))

  (testing "omits resource from token request when nil"
    (let [posted-body (atom nil)]
      (with-redefs [http/post (fn [_ opts]
                                (reset! posted-body (:body opts))
                                {:status 200
                                 :body (java.io.ByteArrayInputStream.
                                        (.getBytes "{\"access_token\":\"tok\",\"expires_in\":3600}"))})]
        (oauth/authorize-token! {:token-endpoint "https://example.com/token"
                                 :verifier "test-verifier"
                                 :client-id "test-client"
                                 :redirect-uri "http://localhost:9999/auth/callback"}
                                "auth-code")
        (let [decoded (ring.util/form-decode @posted-body)]
          (is (nil? (get decoded "resource"))))))))

(deftest oauth-info-oidc-fallback-in-resource-discovery-test
  (testing "discovers metadata via OIDC when PRM and RFC 8414 both fail on base URL"
    (let [get-urls (atom [])]
      (with-redefs [http/head (fn [_ _] {:status 200})
                    http/post (fn [url _]
                                (if (string/includes? url "register")
                                  {:status 404}
                                  (make-auth-response "Bearer realm=\"test\"")))
                    http/get (fn [url _]
                               (swap! get-urls conj url)
                               (cond
                                 (= url "https://example.com/.well-known/openid-configuration")
                                 (make-json-response 200
                                                     {:authorization_endpoint "https://example.com/oidc/authorize"
                                                      :token_endpoint "https://example.com/oidc/token"
                                                      :scopes_supported ["openid" "sql"]})
                                 :else
                                 {:status 404}))]
        (let [info (oauth/oauth-info "https://example.com/mcp")]
          (is (some? info))
          (is (= "https://example.com/oidc/token" (:token-endpoint info)))
          (is (string/includes? (:authorization-endpoint info) "scope=openid+sql"))
          (is (some #(= "https://example.com/.well-known/oauth-protected-resource" %) @get-urls)
              "should have tried PRM first")
          (is (some #(= "https://example.com/.well-known/oauth-authorization-server" %) @get-urls)
              "should have tried RFC 8414 second")
          (is (some #(= "https://example.com/.well-known/openid-configuration" %) @get-urls)
              "should have tried OIDC last"))))))

(deftest oauth-info-returns-nil-when-no-auth-challenge-test
  (testing "returns nil when server does not return 401"
    (with-redefs [http/head (fn [_ _] {:status 200})
                  http/post (fn [_ _] {:status 200})]
      (is (nil? (oauth/oauth-info "https://example.com/mcp"))))))

(deftest oauth-info-returns-nil-when-no-metadata-found-test
  (testing "returns nil when 401 is received but no metadata endpoints respond"
    (with-redefs [http/head (fn [_ _] {:status 200})
                  http/post (fn [_ _]
                              (make-auth-response "Bearer realm=\"test\""))
                  http/get (fn [_ _] {:status 404})]
      (is (nil? (oauth/oauth-info "https://example.com/mcp"))))))

(deftest oauth-info-configured-client-id-test
  (testing "uses configured client-id and skips DCR"
    (let [dcr-called? (atom false)]
      (with-redefs [http/head (fn [_ _] {:status 200})
                    http/post (fn [url _]
                                (when (string/includes? url "register")
                                  (reset! dcr-called? true))
                                (if (string/includes? url "register")
                                  {:status 200 :body {:client_id "dcr-client"}}
                                  (make-auth-response "Bearer realm=\"test\"")))
                    http/get (fn [url _]
                               (if (string/includes? url "oauth-authorization-server")
                                 (make-json-response 200
                                                     {:authorization_endpoint "https://example.com/authorize"
                                                      :token_endpoint "https://example.com/token"
                                                      :registration_endpoint "https://example.com/register"})
                                 {:status 404}))]
        (let [info (oauth/oauth-info "https://example.com/mcp" "my-databricks-client")]
          (is (some? info))
          (is (= "my-databricks-client" (:client-id info)))
          (is (false? @dcr-called?) "DCR should not be attempted when client-id is configured")
          (is (string/includes? (:authorization-endpoint info)
                                "client_id=my-databricks-client"))))))

  (testing "falls back to DCR then hardcoded when no configured client-id"
    (with-redefs [http/head (fn [_ _] {:status 200})
                  http/post (fn [url _]
                              (if (string/includes? url "register")
                                {:status 200 :body {:client_id "dcr-obtained-id"}}
                                (make-auth-response "Bearer realm=\"test\"")))
                  http/get (fn [url _]
                             (if (string/includes? url "oauth-authorization-server")
                               (make-json-response 200
                                                   {:authorization_endpoint "https://example.com/authorize"
                                                    :token_endpoint "https://example.com/token"
                                                    :registration_endpoint "https://example.com/register"})
                               {:status 404}))]
      (let [info (oauth/oauth-info "https://example.com/mcp")]
        (is (= "dcr-obtained-id" (:client-id info))))))

  (testing "nil configured client-id behaves as unset"
    (with-redefs [http/head (fn [_ _] {:status 200})
                  http/post (fn [url _]
                              (if (string/includes? url "register")
                                {:status 404}
                                (make-auth-response "Bearer realm=\"test\"")))
                  http/get (fn [url _]
                             (if (string/includes? url "oauth-authorization-server")
                               (make-json-response 200
                                                   {:authorization_endpoint "https://example.com/authorize"
                                                    :token_endpoint "https://example.com/token"})
                               {:status 404}))]
      (let [info (oauth/oauth-info "https://example.com/mcp" nil)]
        (is (= oauth/eca-client-id (:client-id info)))))))
