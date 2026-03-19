(ns eca.remote.auth-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]
   [eca.remote.auth :as auth]))

(deftest generate-token-test
  (testing "generates a 64-character hex string"
    (let [token (auth/generate-token)]
      (is (= 64 (count token)))
      (is (re-matches #"[0-9a-f]{64}" token))))

  (testing "generates unique tokens"
    (let [tokens (repeatedly 10 auth/generate-token)]
      (is (= 10 (count (set tokens)))))))

(def ^:private test-token "test-secret-token")

(defn- test-handler [_request]
  {:status 200 :headers {} :body "ok"})

(defn- make-request
  ([method uri] (make-request method uri nil))
  ([method uri token]
   (cond-> {:request-method method
            :uri uri
            :headers {}}
     token (assoc-in [:headers "authorization"] (str "Bearer " token)))))

(deftest wrap-bearer-auth-test
  (let [handler (auth/wrap-bearer-auth test-handler test-token ["/api/v1/health" "/"])]

    (testing "allows requests with valid token"
      (let [response (handler (make-request :get "/api/v1/chats" test-token))]
        (is (= 200 (:status response)))))

    (testing "rejects requests with missing token"
      (let [response (handler (make-request :get "/api/v1/chats"))]
        (is (= 401 (:status response)))
        (let [body (json/parse-string (:body response) true)]
          (is (= "unauthorized" (get-in body [:error :code]))))))

    (testing "rejects requests with wrong token"
      (let [response (handler (make-request :get "/api/v1/chats" "wrong-token"))]
        (is (= 401 (:status response)))))

    (testing "exempt paths skip auth"
      (let [response (handler (make-request :get "/api/v1/health"))]
        (is (= 200 (:status response))))
      (let [response (handler (make-request :get "/"))]
        (is (= 200 (:status response)))))))
