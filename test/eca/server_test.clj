(ns eca.server-test
  (:require
   [babashka.process :as p]
   [clojure.test :refer [deftest is testing]]
   [eca.server :as server]
   [eca.test-helper :as h]))

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
