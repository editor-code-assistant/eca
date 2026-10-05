(ns eca.server-test
  (:require
   [babashka.process :as p]
   [clojure.test :refer [deftest is testing]]
   [eca.db :as db]
   [eca.features.login :as login]
   [eca.handlers :as handlers]
   [eca.models :as models]
   [eca.server :as server]
   [eca.test-helper :as h]
   [jsonrpc4clj.server :as jsonrpc.server]))

(deftest models-refresh-request-test
  (let [entered (promise)
        release (promise)]
    (with-redefs [handlers/models-refresh (fn [{:keys [model-sync-turn]} _]
                                           (deliver entered model-sync-turn)
                                           @release
                                           (deliver (:done model-sync-turn) true)
                                           {:model-count 1 :warnings []})]
      (let [response (jsonrpc.server/receive-request "models/refresh" {} {})]
        (try
          (is (some? (:done (deref entered 5000 nil)))
              "the request dispatches with a reserved sync turn")
          (deliver release true)
          (is (= {:model-count 1 :warnings []} (deref response 5000 nil)))
          (finally
            (deliver release true)))))))

(deftest provider-mutation-precedes-refresh-test
  (doseq [[method handler provider-params initial-auth]
          [["providers/logout" #'handlers/providers-logout {:provider "openai"} true]
           ["providers/loginInput" #'handlers/providers-login-input
            {:provider "openai" :data {:api-key "new-key"}} false]]]
    (testing method
      (h/reset-components!)
      (swap! (h/db*) assoc-in [:auth "openai"]
             (if initial-auth {:step :login/done :type :auth/token :api-key "old-key"} {}))
      (let [entered (promise)
            refresh-entered (promise)
            release (promise)
            original @handler
            original-refresh handlers/models-refresh
            catalog (fn [] (if (get-in @(h/db*) [:auth "openai" :api-key])
                             "openai/authorized" "openai/anonymous"))]
        (with-redefs-fn
          {handler (fn [components params]
                     (deliver entered true)
                     @release
                     (original components params))
           #'handlers/models-refresh (fn [components params]
                                       (deliver refresh-entered true)
                                       (original-refresh components params))
           #'db/update-global-cache! (fn [& _])
           #'login/renew-expiring-auth-tokens! (fn [& _])
           #'eca.models/sync-models-now!
           (fn [db* _config callback _refresh?]
             (let [name (catalog)
                   models {name {}}]
               (swap! db* assoc :models models)
               (callback models)
               {:model-count 1}))}
          (fn []
            (let [mutation (jsonrpc.server/receive-request method (h/components) provider-params)]
              (try
                (is (= true (deref entered 5000 :timeout)))
                (let [refresh (jsonrpc.server/receive-request "models/refresh" (h/components) {})]
                  (is (= true (deref refresh-entered 5000 :timeout)))
                  (deliver release true)
                  (is (not= :timeout (deref mutation 5000 :timeout)))
                  (is (= {:modelCount 1 :warnings []} (deref refresh 5000 :timeout)))
                  (is (= [(if initial-auth "openai/anonymous" "openai/authorized")]
                         (get-in (last (:config-updated (h/messages))) [:chat :models])))
                  (is (= 1 (count (:config-updated (h/messages))))
                      "refresh must not publish the old catalog before the mutation"))
                (finally (deliver release true))))))))))

(deftest provider-unused-turn-does-not-block-refresh-test
  (doseq [[result handler] [[:no-sync (fn [_ _] {:action "input"})]
                          [:error (fn [_ _] (throw (ex-info "invalid input" {})))]]]
    (testing (name result)
      (h/reset-components!)
      (let [entered (promise)
            release (promise)]
        (with-redefs [handlers/providers-login-input (fn [components params]
                                                       (deliver entered true)
                                                       @release
                                                       (handler components params))
                      login/renew-expiring-auth-tokens! (fn [& _])
                      models/models-dev (fn [] {})]
          (let [mutation (jsonrpc.server/receive-request
                          "providers/loginInput" (h/components)
                          {:provider "openai" :data {:api-key "key"}})]
            (try
              (is (= true (deref entered 5000 :timeout)))
              (let [refresh (jsonrpc.server/receive-request "models/refresh" (h/components) {})]
                (deliver release true)
                (is (if (= result :error)
                      (try
                        (deref mutation 5000 :timeout)
                        false
                        (catch java.util.concurrent.ExecutionException _ true))
                      (= {:action "input"} (deref mutation 5000 :timeout))))
                (is (not= :timeout (deref refresh 5000 :timeout))
                    "an unused turn must not strand later refreshes"))
              (finally (deliver release true)))))))))

(defn ^:private spawn-blocking-process []
  ;; Long-running child whose pid we own. `sleep 600` is fine on Linux/macOS;
  ;; subprocess-based tests are skipped on Windows.
  (p/process {:cmd ["sleep" "600"]
              :shutdown p/destroy-tree}))

(defn ^:private pid-of [proc]
  (.pid ^java.lang.Process (:proc proc)))

(deftest start-liveness-probe-with-missing-pid-test
  (testing "an absent parent triggers on-exit at start"
    (let [exited? (promise)]
      (#'server/start-liveness-probe! Long/MAX_VALUE
                                      #(deliver exited? true))
      (is (= true (deref exited? 200 :timeout))
          "on-exit must fire when the parent is not present"))))

(deftest start-liveness-probe-survives-on-exit-throwing-test
  (testing "an exception in on-exit does not propagate out of start!"
    (is (nil? (#'server/start-liveness-probe! Long/MAX_VALUE
                                              #(throw (ex-info "boom" {}))))
        "start! must not raise even when on-exit throws")))

;; The two deftests below are skipped on Windows: they rely on `sleep` and on
;; POSIX-style subprocess semantics that the liveness probe targets. Skipping
;; the whole `deftest` (rather than gating only its body) keeps kaocha from
;; reporting "Test ran without assertions" on Windows.
(when-not h/windows?
  (deftest start-liveness-probe-with-alive-parent-test
    (testing "an alive parent does not trigger on-exit"
      (let [proc (spawn-blocking-process)
            exited? (promise)]
        (try
          (#'server/start-liveness-probe! (pid-of proc)
                                          #(deliver exited? true))
          (is (= :still-alive (deref exited? 100 :still-alive))
              "on-exit must not fire while the parent is alive")
          (finally
            (p/destroy-tree proc)))))))

(when-not h/windows?
  (deftest start-liveness-probe-fires-when-parent-dies-test
    (testing "killing the parent triggers on-exit"
      (let [proc (spawn-blocking-process)
            exited? (promise)]
        (#'server/start-liveness-probe! (pid-of proc)
                                        #(deliver exited? :fired))
        (p/destroy-tree proc)
        (is (= :fired (deref exited? 2000 :timeout))
            "on-exit must fire shortly after the parent dies")))))
