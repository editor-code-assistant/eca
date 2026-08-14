(ns eca.features.login-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.cache :as cache]
   [eca.db :as db]
   [eca.features.login :as login]
   [eca.llm-providers.anthropic]
   [eca.llm-providers.copilot :as llm-providers.copilot]
   [eca.messenger :as messenger]
   [hato.client :as http]
   [matcher-combinators.test :refer [match?]]))

(deftest test-login-step
  (let [msg-log (atom [])
        send-msg! (fn [msg] (swap! msg-log conj msg))

        db* (atom {:auth {"google" {}
                          "github-copilot" {}}
                   :chats {1 {}}})]

    (testing "user just typed /login for the first time"
      (login/login-step {:provider nil
                         :step :login/start
                         :chat-id 0
                         :input ""
                         :db* db*
                         :send-msg! send-msg!})

      (testing "should ask to choose a provider"
        (is (= "Inform the provider:\n\n- github-copilot\n- google\n\n\nFor other providers, configure manually in your ECA config."
               (last @msg-log)))))

    (testing "user is confused"
      (login/login-step {:provider nil
                         :step :login/start
                         :chat-id 0
                         :input "/login github"
                         :db* db*
                         :send-msg! send-msg!})

      (testing "should ask to choose a provider and provide instructions"
        (is (= "Inform the provider:\n\n- github-copilot\n- google\n\n\nFor other providers, configure manually in your ECA config."
               (last @msg-log)))

        (testing "state didn't change"
          (is (= {:auth {"github-copilot" {}
                         "google" {}}
                  :chats {1 {}}}
                 @db*)))))

    (testing "valid input is provided"
      (let [poll-args* (atom nil)]
        (with-redefs-fn {#'http/post (constantly {:body {:user_code "1234"
                                                         :decide_code "5678"
                                                         :verification_uri "https://mock.github.com/login/device"}})
                         #'llm-providers.copilot/poll-device-authorization! (fn [& args]
                                                                              (reset! poll-args* args)
                                                                              nil)}
          #(login/login-step {:provider nil
                              :step :login/start
                              :chat-id 0
                              :input "github-copilot"
                              :db* db*
                              :send-msg! send-msg!}))

        (testing "should proceed to the next step with clickable link and start background polling"
          (is (= (str "First, make sure you have Copilot enabled in your GitHub account: [https://github.com/settings/copilot/features](https://github.com/settings/copilot/features)\n"
                      "\n"
                      "Then, open your browser at [https://mock.github.com/login/device](https://mock.github.com/login/device) and authenticate using the code:\n"
                      "\n"
                      "`1234`\n"
                      "\n"
                      "ECA will complete the login automatically after you authorize, type 'cancel' anytime to abort.")
                 (last @msg-log)))
          (is (some? @poll-args*)
              "background device authorization polling should be started")

          (testing "state is update to reflect in-progress login"
            (is (match? {:auth {"github-copilot" {;; skipping :device-code attr
                                                  :step :login/waiting-user-confirmation}
                                "google" {}}
                         :chats {0 {:login-provider "github-copilot"}
                                 1 {}}}

                        @db*))))))))

(deftest login-step-warns-about-configured-key-test
  (testing "selecting a provider during /login warns when a configured key overrides login auth"
    (let [msg-log (atom [])
          send-msg! (fn [msg] (swap! msg-log conj msg))
          db* (atom {:auth {"github-copilot" {}}
                     :chats {0 {}}})]
      (with-redefs-fn {#'http/post (constantly {:body {:user_code "1234"
                                                       :decide_code "5678"
                                                       :verification_uri "https://mock.github.com/login/device"}})
                       #'llm-providers.copilot/poll-device-authorization! (fn [& _] nil)}
        #(login/login-step {:provider nil
                            :step :login/start
                            :chat-id 0
                            :input "github-copilot"
                            :config {:providers {"github-copilot" {:key "ghp_test"}}}
                            :db* db*
                            :send-msg! send-msg!}))
      (is (some #(re-find #"take precedence over login auth" %) @msg-log)
          "a precedence warning should be among the sent messages"))))

(defn ^:private stub-messenger
  "Messenger stub recording chat contents in `contents*` and answering
   ask-question requests from the `answers*` queue, recording them in `asked*`."
  [contents* asked* answers*]
  (reify messenger/IMessenger
    (chat-content-received [_ data] (swap! contents* conj data))
    (chat-status-changed [_ _params])
    (ask-question [_ params]
      (swap! asked* conj params)
      (let [answer (first @answers*)]
        (swap! answers* rest)
        (doto (promise) (deliver answer))))))

(deftest handle-step-asks-question-when-supported-test
  (testing "provider and login method are asked via chat/askQuestion instead of text menus"
    (let [contents* (atom [])
          asked* (atom [])
          answers* (atom [{:answer "anthropic"} {:answer "manual"}])
          db* (atom {:client-capabilities {:code-assistant {:chat-capabilities {:ask-question true}}}
                     :auth {"anthropic" {}
                            "github-copilot" {}}
                     :chats {"c1" {:status :login}}})]
      (login/handle-step {:message "" :chat-id "c1"}
                         db*
                         (stub-messenger contents* asked* answers*)
                         {:env "test"}
                         nil)
      (is (= ["Select the provider to login (other providers can be configured manually in your ECA config):"
              "Select the Anthropic login method:"]
             (mapv :question @asked*)))
      (is (= [["anthropic" "github-copilot"]
              ["max" "console" "manual"]]
             (mapv #(mapv :label (:options %)) @asked*)))
      (is (every? #(false? (:allowFreeform %)) @asked*))
      (is (= "anthropic" (get-in @db* [:chats "c1" :login-provider])))
      (is (= :login/waiting-api-key (get-in @db* [:auth "anthropic" :step])))
      (is (some #(= "Paste your Anthropic API Key" (get-in % [:content :text])) @contents*)))))

(deftest handle-step-ask-question-cancelled-test
  (testing "cancelling the question cancels the login"
    (let [contents* (atom [])
          asked* (atom [])
          answers* (atom [{:cancelled true}])
          db* (atom {:client-capabilities {:code-assistant {:chat-capabilities {:ask-question true}}}
                     :auth {"anthropic" {}}
                     :chats {"c1" {:status :login}}})]
      (login/handle-step {:message "" :chat-id "c1"}
                         db*
                         (stub-messenger contents* asked* answers*)
                         {:env "test"}
                         nil)
      (is (= 1 (count @asked*)))
      (is (= :idle (get-in @db* [:chats "c1" :status])))
      (is (nil? (get-in @db* [:chats "c1" :login-provider])))
      (is (some #(= "Login cancelled" (get-in % [:content :text])) @contents*)))))

(deftest handle-step-does-not-echo-secrets-test
  (testing "API key input is masked in the chat echo and not repeated in errors"
    (let [contents* (atom [])
          db* (atom {:auth {"anthropic" {:step :login/waiting-api-key :mode :manual}}
                     :chats {"c1" {:status :login :login-provider "anthropic"}}})]
      (login/handle-step {:message "my-secret-key" :chat-id "c1"}
                         db*
                         (stub-messenger contents* (atom []) (atom []))
                         {:env "test"}
                         nil)
      (is (some #(and (= "user" (:role %))
                      (= "*****\n" (get-in % [:content :text])))
                @contents*))
      (is (not-any? #(string/includes? (str (get-in % [:content :text])) "my-secret-key")
                    @contents*))
      (is (some #(= "Invalid API key, it should start with 'sk-'" (get-in % [:content :text])) @contents*)))))

(def ^:private renew-auth!* @#'login/renew-auth!)

(deftest renew-auth!-skips-refresh-when-peer-already-refreshed-test
  (testing "if disk holds fresher tokens than memory, renew-auth! adopts them and does NOT call login-step"
    (let [tmpdir (str (fs/create-temp-dir))]
      (with-redefs [cache/global-dir (constantly (io/file tmpdir))]
        (try
          (let [fresh {:type :auth/oauth :mode :max :step :login/done
                       :refresh-token "peer-fresh" :api-key "peer-access"
                       :expires-at 9999999999}
                stale {:type :auth/oauth :mode :max :step :login/done
                       :refresh-token "mine-stale" :api-key "mine-access"
                       :expires-at 1000}
                _ (db/update-global-cache! {:auth {"anthropic" fresh}} nil)
                db* (atom {:auth {"anthropic" stale}})
                step-calls (atom 0)
                on-error-msgs (atom [])]
            (with-redefs [login/login-step (fn [_ctx] (swap! step-calls inc))]
              (renew-auth!* "anthropic"
                            {:db* db* :messenger nil :config nil :metrics nil}
                            {:on-error #(swap! on-error-msgs conj %)}))
            (is (zero? @step-calls)
                "login-step should not be invoked when disk has fresh tokens")
            (is (empty? @on-error-msgs))
            (is (= "peer-fresh" (get-in @db* [:auth "anthropic" :refresh-token])))
            (is (= "peer-access" (get-in @db* [:auth "anthropic" :api-key])))
            (is (= 9999999999 (get-in @db* [:auth "anthropic" :expires-at]))))
          (finally (fs/delete-tree tmpdir)))))))

(deftest renew-auth!-refreshes-when-disk-also-stale-test
  (testing "when memory and disk are both expired, renew-auth! invokes login-step exactly once and persists"
    (let [tmpdir (str (fs/create-temp-dir))]
      (with-redefs [cache/global-dir (constantly (io/file tmpdir))]
        (try
          (let [stale {:type :auth/oauth :mode :max :step :login/done
                       :refresh-token "stale" :api-key "stale-access"
                       :expires-at 1000}
                _ (db/update-global-cache! {:auth {"anthropic" stale}} nil)
                db* (atom {:auth {"anthropic" stale}})
                step-calls (atom 0)
                on-error-msgs (atom [])]
            (with-redefs [login/login-step
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
              (db/sync-auth-from-cache! reloaded "anthropic" nil)
              (is (= "rotated" (get-in @reloaded [:auth "anthropic" :refresh-token])))))
          (finally (fs/delete-tree tmpdir)))))))

(deftest renew-auth!-calls-on-error-when-login-step-throws-test
  (testing "exceptions from the provider refresh propagate to on-error and do not persist"
    (let [tmpdir (str (fs/create-temp-dir))]
      (with-redefs [cache/global-dir (constantly (io/file tmpdir))]
        (try
          (let [stale {:type :auth/oauth :refresh-token "stale" :expires-at 1000}
                _ (db/update-global-cache! {:auth {"anthropic" stale}} nil)
                db* (atom {:auth {"anthropic" stale}})
                on-error-msgs (atom [])]
            (with-redefs [login/login-step
                          (fn [_ctx] (throw (ex-info "Anthropic refresh token failed" {})))]
              (renew-auth!* "anthropic"
                            {:db* db* :messenger nil :config nil :metrics nil}
                            {:on-error #(swap! on-error-msgs conj %)}))
            (is (= 1 (count @on-error-msgs)))
            (is (= "Anthropic refresh token failed" (first @on-error-msgs))))
          (finally (fs/delete-tree tmpdir)))))))

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
                _ (db/update-global-cache! {:auth {"github-copilot" stale}} nil)
                db* (atom {:auth {"github-copilot" stale
                                  "anthropic" fresh
                                  "openai" {}}})
                renewed* (atom [])]
            (with-redefs [login/login-step
                          (fn [{:keys [db* provider]}]
                            (swap! renewed* conj provider)
                            (swap! db* update-in [:auth provider] merge
                                   {:api-key "rotated-session" :expires-at 9999999999}))]
              (login/renew-expiring-auth-tokens!
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
      (with-redefs-fn {#'login/renew-auth! (fn [& _]
                                              (swap! renew-calls* inc))}
        #(login/maybe-renew-auth-token!
          {:provider "anthropic"
           :on-renewing (fn [] (swap! renewing-calls* inc))}
          {:db* db*
           :config {:providers {"anthropic" {:key "local-proxy-key"}}}}))
      (is (zero? @renew-calls*))
      (is (zero? @renewing-calls*)))))
