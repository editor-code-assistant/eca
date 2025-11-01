(ns eca.features.login-test
  (:require [clojure.test :refer [deftest is testing]]
            [eca.features.login :as login]
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
        (is (= "Please type the name of your chosen provider and press Enter:\n- github-copilot\n- google\n"
               (last @msg-log)))))

    (testing "user is confused"
      (login/login-step {:provider nil
                         :step :login/start
                         :chat-id 0
                         :input "/login github"
                         :db* db*
                         :send-msg! send-msg!})

      (testing "should ask to choose a provider and provide instructions"
        (is (= "Sorry, \"/login github\" is not a valid provider.\nPlease type the name of your chosen provider and press Enter:\n- github-copilot\n- google\n"
               (last @msg-log)))

        (testing "state didn't change"
          (is (= {:auth {"github-copilot" {}
                         "google" {}}
                  :chats {1 {}}}
                 @db*)))))

    (testing "valid input is provided"

      (login/login-step {:provider nil
                         :step :login/start
                         :chat-id 0
                         :input "github-copilot"
                         :db* db*
                         :send-msg! send-msg!})

      (testing "should proceed to the next step"
        (is (re-find #"(?m)Open your browser at `https://github.com/login/device` and authenticate using the code: `.+`\nThen type anything in the chat and send it to continue the authentication."
                     (last @msg-log)))

        (testing "state is update to reflect in-progress login"
          (is (match? {:auth {"github-copilot" {;; skipping :device-code attr
                                                :step :login/waiting-user-confirmation}
                              "google" {}}
                       :chats {0 {:login-provider "github-copilot"}
                               1 {}}}

                      @db*)))))))
