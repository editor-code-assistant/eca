(ns eca.oauth-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.oauth :as oauth]
   [matcher-combinators.test :refer [match?]])
  (:import
   [java.net ServerSocket]))

(deftest generate-pkce-test
  (testing "generates verifier and challenge"
    (let [{:keys [verifier challenge]} (oauth/generate-pkce)]
      (is (string? verifier))
      (is (string? challenge))
      (is (not= verifier challenge))
      (is (not (string/includes? challenge "="))
          "challenge should not contain padding"))))

(deftest generate-state-test
  (testing "generates a fresh, URL-safe, unpadded state each time"
    (let [state (oauth/generate-state)]
      (is (re-matches #"[A-Za-z0-9_-]{40,}" state))
      (is (not= state (oauth/generate-state))))))

(defn ^:private callback!
  "What the browser gets when the OAuth redirect hits the server on `port`."
  [port query]
  (slurp (str "http://127.0.0.1:" port "/auth/callback?" query)))

(deftest start-oauth-server!-test
  (testing "a newer login attempt on the same port replaces the earlier one's server"
    (let [port (oauth/get-free-port)
          calls (atom [])
          first-attempt (oauth/start-oauth-server!
                         {:port port
                          :sync-success? true
                          :on-success (fn [_] (swap! calls conj :first))
                          :on-error (fn [_])})
          second-attempt (oauth/start-oauth-server!
                          {:port port
                           :sync-success? true
                           :on-success (fn [_] (swap! calls conj :second))
                           :on-error (fn [_])})]
      (try
        (is (string/includes? (callback! port "code=c&state=s") "Authentication Successful"))
        (is (= [:second] @calls)
            "the redirect reaches the latest attempt, whose PKCE verifier can exchange its code")

        (testing "and the earlier attempt stopping its server leaves the newer one serving"
          (oauth/stop-oauth-server! port (:server first-attempt))
          (callback! port "code=c&state=s")
          (is (= [:second :second] @calls)))
        (finally
          (oauth/stop-oauth-server! port (:server second-attempt))))))

  (testing "with sync-success?, the page tells the on-success failure instead of claiming success"
    (let [port (oauth/get-free-port)
          {:keys [server]} (oauth/start-oauth-server!
                            {:port port
                             :sync-success? true
                             :on-success (fn [_]
                                           (throw (ex-info "OpenAI token exchange failed: invalid_grant" {})))
                             :on-error (fn [_])})]
      (try
        (let [page (callback! port "code=c&state=s")]
          (is (string/includes? page "Authentication Failed"))
          (is (string/includes? page "OpenAI token exchange failed: invalid_grant"))
          (is (not (string/includes? page "Authentication Successful"))))
        (finally
          (oauth/stop-oauth-server! port server)))))

  (testing "a port it can't listen on is a clear error the client can show"
    (with-open [taken (ServerSocket. 0)]
      (let [port (.getLocalPort taken)
            error (try
                    (oauth/start-oauth-server! {:port port
                                                :on-success (fn [_])
                                                :on-error (fn [_])})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? error) "starting on a taken port throws")
        (is (match? {:port port
                     :error-response {:message (re-pattern (str "^Could not listen on port " port " for the OAuth callback"))}}
                    (ex-data error)))
        (is (= (ex-message error) (get-in (ex-data error) [:error-response :message])))
        (is (nil? (oauth/stop-oauth-server! port))
            "nothing was registered for the port")))))
