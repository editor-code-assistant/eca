(ns eca.modules.llm-providers.quota-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.modules.llm-providers.quota :as m.llm-providers.quota]
   [eca.modules.providers.anthropic :as m.anthropic]
   [eca.modules.providers.copilot :as m.copilot]
   [eca.modules.providers.openai :as m.openai]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]])
  (:import
   [java.time OffsetDateTime]))

(defn ^:private epoch [iso]
  (.toEpochSecond (OffsetDateTime/parse iso)))

(defn ^:private headers->snapshot
  "The quota in `provider`'s response `headers`, read by its module (`:quota :headers`)."
  [provider headers]
  (m.llm-providers.quota/headers->snapshot provider headers {}))

(defn ^:private endpoint-snapshot
  "The snapshot of the windows a provider's `:quota :fetch` reads from its
   usage endpoint's response `body`, with its `usage->windows`."
  [usage->windows body]
  (#'m.llm-providers.quota/snapshot (usage->windows body)))

(deftest headers->snapshot-test
  (testing "Claude unified headers: 0-1 utilization fraction and epoch seconds resets"
    (is (match? {:updated-at number?
                 :windows [{:id "5h" :label "5h" :used-percent 26.0 :resets-at 1775455200 :window-minutes 300}
                           {:id "7d" :label "7d" :used-percent 13.0 :resets-at 1776000000 :window-minutes 10080}]}
                (headers->snapshot "anthropic"
                                   {"anthropic-ratelimit-unified-5h-utilization" "0.26"
                                    "anthropic-ratelimit-unified-5h-reset" "1775455200"
                                    "Anthropic-Ratelimit-Unified-7d-Utilization" "0.13"
                                    "anthropic-ratelimit-unified-7d-reset" "1776000000"
                                    "anthropic-ratelimit-unified-status" "allowed"}))))
  (testing "Codex headers: labels follow the reported window minutes"
    (is (match? {:windows [{:id "primary" :label "5h" :used-percent 12.5 :resets-at 1704069000 :window-minutes 300}
                           {:id "secondary" :label "7d" :used-percent 3.0 :window-minutes 10080}]}
                (headers->snapshot "openai"
                                   {"x-codex-primary-used-percent" "12.5"
                                    "x-codex-primary-window-minutes" "300"
                                    "x-codex-primary-reset-at" "1704069000"
                                    "x-codex-secondary-used-percent" "3"
                                    "x-codex-secondary-window-minutes" "10080"
                                    "x-codex-turn-state" "abc"}))))
  (testing "Codex slots without usage nor duration are skipped, like the secondary one of plans without a 5h limit"
    (is (match? {:windows [{:id "primary" :label "7d" :used-percent 37.0 :window-minutes 10080}]}
                (headers->snapshot "openai"
                                   {"x-codex-primary-used-percent" "37"
                                    "x-codex-primary-window-minutes" "10080"
                                    "x-codex-primary-reset-after-seconds" "86400"
                                    "x-codex-secondary-used-percent" "0"
                                    "x-codex-secondary-window-minutes" "0"
                                    "x-codex-secondary-reset-after-seconds" "0"})))
    (is (nil? (headers->snapshot "openai" {"x-codex-secondary-used-percent" "0"})))
    (testing "but unused windows with a duration are kept"
      (is (match? {:windows [{:id "primary" :label "5h" :used-percent 0.0 :window-minutes 300}]}
                  (headers->snapshot "openai"
                                     {"x-codex-primary-used-percent" "0"
                                      "x-codex-primary-window-minutes" "300"})))))
  (testing "Copilot snapshots: remaining percentage, unlimited quotas skipped"
    (is (match? {:windows [{:id "premium_interactions"
                            :label "Premium"
                            :used-percent 2.9
                            :resets-at (epoch "2026-09-01T00:00:00Z")}]}
                (headers->snapshot "github-copilot"
                                   {"x-quota-snapshot-premium_interactions" "ent=300&ov=0.0&ovPerm=false&rem=97.1&rst=2026-09-01T00%3A00%3A00Z"
                                    "x-quota-snapshot-chat" "ent=-1&ov=0.0&ovPerm=false&rem=100.0&rst=2026-09-01T00%3A00%3A00Z"}))))
  (testing "Copilot premium_models wins over the legacy premium_interactions"
    (is (match? {:windows [{:id "premium_models" :used-percent 50.0}]}
                (headers->snapshot "github-copilot"
                                   {"x-quota-snapshot-premium_interactions" "ent=300&rem=90"
                                    "x-quota-snapshot-premium_models" "ent=300&rem=50"}))))
  (testing "repeated headers use their first value"
    (is (match? {:windows [{:id "5h" :used-percent 50.0}]}
                (headers->snapshot "anthropic" {"anthropic-ratelimit-unified-5h-utilization" ["0.5" "0.9"]}))))
  (testing "responses without quota headers have no snapshot"
    (is (nil? (headers->snapshot "anthropic" {"content-type" "text/event-stream"
                                              "anthropic-ratelimit-requests-remaining" "49"})))
    (is (nil? (headers->snapshot "anthropic" nil)))))

(deftest endpoint-snapshots-test
  (testing "Claude api/oauth/usage windows keyed by duration, 0-100 utilization"
    (is (match? {:windows [{:id "5h" :label "5h" :used-percent 42.0 :window-minutes 300
                            :resets-at (epoch "2099-01-01T00:00:00+00:00")}
                           {:id "7d" :label "7d" :used-percent 47.0 :window-minutes 10080}
                           {:id "7d-opus" :label "7d Opus" :used-percent 10.0 :window-minutes 10080
                            :resets-at m/absent}]}
                (endpoint-snapshot
                 #'m.anthropic/usage->windows
                 {:five_hour {:utilization 42.0 :resets_at "2099-01-01T00:00:00+00:00"}
                  :seven_day {:utilization 47.0 :resets_at "2099-01-02T00:00:00+00:00"}
                  :seven_day_opus {:utilization 10.0 :resets_at nil}
                  :seven_day_oauth_apps nil
                  :extra_usage {:is_enabled true :utilization 30.9}}))))
  (testing "ChatGPT wham/usage keeps the header window ids"
    (is (match? {:windows [{:id "primary" :label "5h" :used-percent 30.0 :resets-at 1792007200 :window-minutes 300}
                           {:id "secondary" :label "7d" :used-percent 8.0 :resets-at 1792400000 :window-minutes 10080}]}
                (endpoint-snapshot
                 #'m.openai/usage->windows
                 {:plan_type "pro"
                  :rate_limit {:allowed true
                               :primary_window {:used_percent 30 :limit_window_seconds 18000
                                                :reset_after_seconds 100 :reset_at 1792007200}
                               :secondary_window {:used_percent 8 :limit_window_seconds 604800
                                                  :reset_at 1792400000}}}))))
  (testing "ChatGPT wham/usage skips windows without usage nor duration"
    (is (match? {:windows [{:id "primary" :label "7d" :used-percent 37.0 :window-minutes 10080}]}
                (endpoint-snapshot
                 #'m.openai/usage->windows
                 {:rate_limit {:allowed true
                               :primary_window {:used_percent 37 :limit_window_seconds 604800
                                                :reset_at 1792400000}
                               :secondary_window {:used_percent 0 :limit_window_seconds 0
                                                  :reset_after_seconds 0 :reset_at 0}}}))))
  (testing "Copilot copilot_internal/user quota snapshots"
    (is (match? {:windows [{:id "premium_interactions" :label "Premium" :used-percent 40.0
                            :resets-at (epoch "2026-08-01T00:00:00Z")}]}
                (endpoint-snapshot
                 #'m.copilot/usage->windows
                 {:copilot_plan "individual"
                  :quota_reset_date_utc "2026-08-01T00:00:00.000Z"
                  :quota_snapshots {:chat {:entitlement 0 :percent_remaining 100.0 :unlimited true}
                                    :completions {:entitlement 0 :percent_remaining 100.0 :unlimited true}
                                    :premium_interactions {:entitlement 300 :remaining 180
                                                           :percent_remaining 60.0 :unlimited false}}}))))
  (testing "payloads without quota have no snapshot"
    (is (nil? (endpoint-snapshot #'m.anthropic/usage->windows {:five_hour nil :seven_day nil})))
    (is (nil? (endpoint-snapshot #'m.openai/usage->windows {:plan_type "free"})))
    (is (nil? (endpoint-snapshot #'m.copilot/usage->windows {:quota_snapshots {:chat {:unlimited true :percent_remaining 100}}})))))

(deftest update!-test
  (let [db* (atom {})
        five-hour (fn [pct] {:updated-at 1
                             :windows [{:id "5h" :label "5h" :used-percent pct :resets-at 1000 :window-minutes 300}]})]
    (testing "a first snapshot changes what clients show"
      (is (true? (m.llm-providers.quota/update! db* "anthropic" (five-hour 42.0)))))
    (testing "sub-percent noise is not a change"
      (is (false? (m.llm-providers.quota/update! db* "anthropic" (five-hour 42.3)))))
    (testing "windows merge by id, keeping the ones a source doesn't report"
      (is (true? (m.llm-providers.quota/update! db* "anthropic" {:updated-at 2
                                                                 :windows [{:id "7d-opus" :label "7d Opus" :used-percent 5.0
                                                                            :window-minutes 10080}]})))
      (is (match? {:updated-at 2
                   :windows [{:id "5h" :used-percent 42.3} {:id "7d-opus" :used-percent 5.0}]}
                  (get-in @db* [:provider-quotas "anthropic"]))))
    (testing "headers without quota change nothing"
      (is (false? (m.llm-providers.quota/update-from-headers! db* "anthropic" {"content-type" "application/json"} {}))))))

(deftest subscription-providers-test
  (let [db {:auth {"anthropic" {:type :auth/oauth :mode :max :api-key "oauth-token"}
                   "openai" {:type :auth/token :api-key "sk-key"}
                   "github-copilot" {:api-key "copilot-token"}}}]
    (is (= ["anthropic" "github-copilot"] (m.llm-providers.quota/subscription-providers db {})))))

(deftest refresh!-test
  (let [db* (atom {:auth {"anthropic" {:type :auth/oauth :api-key "token"}
                          "openai" {:type :auth/token :api-key "sk-key"}}})
        calls* (atom 0)]
    (with-redefs [m.llm-providers.quota/fetch-quota (fn [_]
                                                      (swap! calls* inc)
                                                      {:updated-at 1 :windows [{:id "5h" :label "5h" :used-percent 10.0}]})]
      (testing "fetches and reports the change"
        (is (true? (m.llm-providers.quota/refresh! "anthropic" {:db* db* :config {}})))
        (is (match? {:windows [{:id "5h"}]} (get-in @db* [:provider-quotas "anthropic"]))))
      (testing "doesn't fetch again within a minute unless forced"
        (is (false? (m.llm-providers.quota/refresh! "anthropic" {:db* db* :config {}})))
        (is (= 1 @calls*))
        (is (false? (m.llm-providers.quota/refresh! "anthropic" {:db* db* :config {} :force? true})))
        (is (= 2 @calls*)))
      (testing "API key logins are never fetched"
        (is (false? (m.llm-providers.quota/refresh! "openai" {:db* db* :config {}})))
        (is (= 2 @calls*))))
    (testing "fetch failures are only logged"
      (with-redefs [m.llm-providers.quota/fetch-quota (fn [_] (throw (ex-info "boom" {})))]
        (is (false? (m.llm-providers.quota/refresh! "anthropic" {:db* db* :config {} :force? true})))))))

(deftest quota-text-test
  (let [now (quot (System/currentTimeMillis) 1000)
        db {:provider-quotas {"anthropic" {:updated-at now
                                           :windows [{:id "5h" :label "5h" :used-percent 42.4 :resets-at (+ now 8000)}
                                                     {:id "7d" :label "7d" :used-percent 13.0}]}}}]
    (is (= (string/join "\n" ["Subscription quota:"
                              ""
                              "anthropic (updated <1m ago)"
                              "- 5h: 42% used · resets in 2h 13m"
                              "- 7d: 13% used"
                              ""
                              "openai: not available yet, it shows up after the next request."])
           (m.llm-providers.quota/quota-text db ["anthropic" "openai"])))
    (is (string/starts-with? (m.llm-providers.quota/quota-text db []) "No subscription login found"))))
