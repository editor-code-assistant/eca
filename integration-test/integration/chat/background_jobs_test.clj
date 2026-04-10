(ns integration.chat.background-jobs-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [integration.eca :as eca]
   [integration.fixture :as fixture]
   [llm-mock.mocks :as llm.mocks]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(eca/clean-after-test)

(deftest background-job-lifecycle
  (eca/start-process!)
  (eca/request! (fixture/initialize-request))
  (eca/notify! (fixture/initialized-notification))

  (let [chat-id* (atom nil)
        job-id* (atom nil)]

    (testing "Prompt triggers a background shell command"
      (llm.mocks/set-case! :bg-shell-0)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                 {:model "anthropic/claude-sonnet-4-6"
                                  :message "Run a background command"}))]
        (reset! chat-id* (:chatId resp))
        (is (match? {:chatId (m/pred string?)
                     :status "prompting"}
                    resp))))

    (testing "Server sends jobs/updated notification when job starts"
      (let [notification (eca/client-awaits-server-notification :jobs/updated)]
        (is (match? {:jobs (m/pred #(pos? (count %)))}
                    notification))
        (let [job (first (:jobs notification))]
          (reset! job-id* (:id job))
          (is (match? {:id (m/pred string?)
                       :type "shell"
                       :status "running"
                       :label (m/pred #(re-find #"echo bg-test-output" %))
                       :summary "bg-test"}
                      job)))))

    (testing "jobs/list returns the running job"
      (let [resp (eca/request! [:jobs/list {}])]
        (is (match? {:jobs (m/embeds [{:id @job-id*
                                       :status "running"}])}
                    resp))))

    (testing "jobs/readOutput returns captured output with stream tags"
      (let [resp (eca/request! [:jobs/readOutput {:job-id @job-id*}])]
        (is (match? {:lines (m/pred #(some (fn [l] (re-find #"bg-test-output" (:text l))) %))
                     :status "running"}
                    resp))
        (is (every? #(contains? #{"stdout" "stderr"} (:stream %)) (:lines resp)))))

    (testing "jobs/kill terminates the running job"
      (let [resp (eca/request! [:jobs/kill {:job-id @job-id*}])]
        (is (match? {:killed true} resp))))

    (testing "Server sends jobs/updated notification after kill"
      (let [notification (eca/client-awaits-server-notification :jobs/updated)]
        (is (match? {:jobs (m/embeds [{:id @job-id*
                                       :status "killed"}])}
                    notification))))

    (testing "jobs/kill on already-killed job returns false"
      (let [resp (eca/request! [:jobs/kill {:job-id @job-id*}])]
        (is (match? {:killed false} resp))))

    (testing "jobs/readOutput on non-existent job returns empty"
      (let [resp (eca/request! [:jobs/readOutput {:job-id "job-999"}])]
        (is (match? {:lines [] :status nil} resp))))))
