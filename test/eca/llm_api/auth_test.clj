(ns eca.llm-api.auth-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [eca.cache :as cache]
   [eca.db.global-cache :as db.global-cache]
   [eca.llm-api.auth :as auth]
   [eca.modules.providers.anthropic :as m.anthropic]))

(def ^:private renew-auth!* @#'auth/renew-auth!)

(deftest renew-auth!-skips-refresh-when-peer-already-refreshed-test
  (testing "if disk holds fresher tokens than memory, renew-auth! adopts them and does NOT run the renew step"
    (let [tmpdir (str (fs/create-temp-dir))]
      (with-redefs [cache/global-dir (constantly (io/file tmpdir))]
        (try
          (let [fresh {:type :auth/oauth :mode :max :step :login/done
                       :refresh-token "peer-fresh" :api-key "peer-access"
                       :expires-at 9999999999}
                stale {:type :auth/oauth :mode :max :step :login/done
                       :refresh-token "mine-stale" :api-key "mine-access"
                       :expires-at 1000}
                _ (db.global-cache/update-global-cache! {:auth {"anthropic" fresh}} nil)
                db* (atom {:auth {"anthropic" stale}})
                step-calls (atom 0)
                on-error-msgs (atom [])]
            (with-redefs [auth/renew-token! (fn [_ctx] (swap! step-calls inc))]
              (renew-auth!* "anthropic"
                            {:db* db* :messenger nil :config nil :metrics nil}
                            {:on-error #(swap! on-error-msgs conj %)}))
            (is (zero? @step-calls)
                "the renew step should not run when disk has fresh tokens")
            (is (empty? @on-error-msgs))
            (is (= "peer-fresh" (get-in @db* [:auth "anthropic" :refresh-token])))
            (is (= "peer-access" (get-in @db* [:auth "anthropic" :api-key])))
            (is (= 9999999999 (get-in @db* [:auth "anthropic" :expires-at]))))
          (finally (fs/delete-tree tmpdir)))))))

(deftest renew-auth!-refreshes-when-disk-also-stale-test
  (testing "when memory and disk are both expired, renew-auth! runs the renew step exactly once and persists"
    (let [tmpdir (str (fs/create-temp-dir))]
      (with-redefs [cache/global-dir (constantly (io/file tmpdir))]
        (try
          (let [stale {:type :auth/oauth :mode :max :step :login/done
                       :refresh-token "stale" :api-key "stale-access"
                       :expires-at 1000}
                _ (db.global-cache/update-global-cache! {:auth {"anthropic" stale}} nil)
                db* (atom {:auth {"anthropic" stale}})
                step-calls (atom 0)
                on-error-msgs (atom [])]
            (with-redefs [auth/renew-token!
                          (fn [{:keys [db* provider]}]
                            (swap! step-calls inc)
                            (swap! db* update-in [:auth provider] merge
                                   {:refresh-token "rotated"
                                    :api-key "rotated-access"
                                    :expires-at 9999999999}))]
              (renew-auth!* "anthropic"
                            {:db* db* :messenger nil :config nil :metrics nil}
                            {:on-error #(swap! on-error-msgs conj %)}))
            (is (= 1 @step-calls))
            (is (empty? @on-error-msgs))
            (is (= "rotated" (get-in @db* [:auth "anthropic" :refresh-token])))
            ;; And the rotated tokens should have been written to disk so the next
            ;; process in the race sees them.
            (let [reloaded (atom {:auth {"anthropic" {}}})]
              (db.global-cache/sync-auth-from-cache! reloaded "anthropic" nil)
              (is (= "rotated" (get-in @reloaded [:auth "anthropic" :refresh-token])))))
          (finally (fs/delete-tree tmpdir)))))))

(deftest renew-auth!-calls-on-error-when-the-renew-step-throws-test
  (testing "exceptions from the provider refresh propagate to on-error and do not persist"
    (let [tmpdir (str (fs/create-temp-dir))]
      (with-redefs [cache/global-dir (constantly (io/file tmpdir))]
        (try
          (let [stale {:type :auth/oauth :refresh-token "stale" :expires-at 1000}
                _ (db.global-cache/update-global-cache! {:auth {"anthropic" stale}} nil)
                db* (atom {:auth {"anthropic" stale}})
                on-error-msgs (atom [])]
            (with-redefs [auth/renew-token!
                          (fn [_ctx] (throw (ex-info "Anthropic refresh token failed" {})))]
              (renew-auth!* "anthropic"
                            {:db* db* :messenger nil :config nil :metrics nil}
                            {:on-error #(swap! on-error-msgs conj %)}))
            (is (= 1 (count @on-error-msgs)))
            (is (= "Anthropic refresh token failed" (first @on-error-msgs))))
          (finally (fs/delete-tree tmpdir)))))))

(deftest renew-token!-runs-the-base-providers-renew-step-test
  (testing "a provider inheriting a built-in one runs its parent's :login/renew-token step"
    (let [renewed* (atom nil)]
      (with-redefs [m.anthropic/renew-token-step (fn [{:keys [provider]}] (reset! renewed* provider))]
        (#'auth/renew-token! {:provider "anthropic-work"
                              :config {:providers {"anthropic-work" {:inherit "anthropic"}}}
                              :db* (atom {})})
        (is (= "anthropic-work" @renewed*)))))
  (testing "a provider without one: an error, which renew-auth! hands to on-error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown login step"
                          (#'auth/renew-token! {:provider "my-proxy" :config {} :db* (atom {})})))))

(deftest renew-expiring-auth-tokens!-only-renews-expiring-providers-test
  (testing "renews only providers whose token is at/near expiry, skipping fresh and keyless ones"
    (let [tmpdir (str (fs/create-temp-dir))]
      (with-redefs [cache/global-dir (constantly (io/file tmpdir))]
        (try
          (let [stale {:type :auth/oauth :step :login/done
                       :access-token "gh-oauth" :api-key "stale-session"
                       :expires-at 1000}
                fresh {:type :auth/oauth :step :login/done
                       :api-key "fresh-session" :expires-at 9999999999}
                _ (db.global-cache/update-global-cache! {:auth {"github-copilot" stale}} nil)
                db* (atom {:auth {"github-copilot" stale
                                  "anthropic" fresh
                                  "openai" {}}})
                renewed* (atom [])]
            (with-redefs [auth/renew-token!
                          (fn [{:keys [db* provider]}]
                            (swap! renewed* conj provider)
                            (swap! db* update-in [:auth provider] merge
                                   {:api-key "rotated-session" :expires-at 9999999999}))]
              (auth/renew-expiring-auth-tokens!
               {:db* db* :messenger nil :config nil :metrics nil}))
            (is (= ["github-copilot"] @renewed*)
                "only the expired provider should be renewed")
            (is (= "rotated-session" (get-in @db* [:auth "github-copilot" :api-key])))
            (is (= "fresh-session" (get-in @db* [:auth "anthropic" :api-key]))))
          (finally (fs/delete-tree tmpdir)))))))

(deftest maybe-renew-auth-token!-skips-when-configured-key-overrides-oauth-test
  (testing "an expired saved OAuth token does not block a provider configured with a static key"
    (let [db* (atom {:auth {"anthropic" {:type :auth/oauth
                                         :api-key "expired-access"
                                         :refresh-token "expired-refresh"
                                         :expires-at 0}}})
          renew-calls* (atom 0)
          renewing-calls* (atom 0)]
      (with-redefs-fn {#'auth/renew-auth! (fn [& _]
                                            (swap! renew-calls* inc))}
        #(auth/maybe-renew-auth-token!
          {:provider "anthropic"
           :on-renewing (fn [] (swap! renewing-calls* inc))}
          {:db* db*
           :config {:providers {"anthropic" {:key "local-proxy-key"}}}}))
      (is (zero? @renew-calls*))
      (is (zero? @renewing-calls*)))))
