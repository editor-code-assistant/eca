(ns eca.server-test
  (:require
   [babashka.process :as p]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.handlers :as handlers]
   [eca.modules :as modules]
   [eca.modules.completion :as m.completion]
   [eca.server :as server]
   [eca.test-helper :as h]
   [jsonrpc4clj.server :as jsonrpc.server]))

(h/reset-components-before-test)

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

(defn ^:private client-methods
  "`[method kind]` of each method clients send, as docs/protocol.md lists
   them: its sections marked ↩ (a request) or → (a notification), with the
   kind its `_Request:_` or `_Notification:_` says."
  []
  (for [section (rest (string/split (slurp "docs/protocol.md") #"\n### "))
        :let [heading (first (string/split-lines section))
              method (second (re-find #"\* method: `([^`]+)`" section))
              kind (some-> (re-find #"_(Request|Notification):_" section) second string/lower-case keyword)]
        :when (and method (re-find #"\((↩|→)\)\s*$" heading))]
    [method kind]))

(deftest documented-methods-test
  (testing "every method clients send is served, with its kind: core's, or a built-in module's"
    (let [methods (client-methods)]
      (is (< 40 (count methods)))
      (is (some #{["completion/inline" :request]} methods))
      (doseq [[method kind] methods]
        (is (some? (#'server/find-method kind method (h/components))) (str kind " " method))))))

(defn ^:private request [method params]
  (let [result (jsonrpc.server/receive-request method (h/components) params)]
    (if (future? result) (deref result 5000 :timeout) result)))

(deftest dispatch-test
  (h/reset-components!)
  (testing "core's methods"
    (with-redefs [handlers/chat-query-commands (fn [{:keys [config]} params] {:params params :config? (map? config)})]
      (is (= {:params {:query "x"} :config? true} (request "chat/queryCommands" {:query "x"})))))
  (testing "a built-in module's method, also while the module is disabled, as before modules"
    (with-redefs [m.completion/complete (fn [params & _] {:items [{:text (:doc-text params)}]})]
      (is (= {:items [{:text "abc"}]} (request "completion/inline" {:doc-text "abc"})))
      (reset! config/initialization-config* {:modules {:eca/completion {:enabled false}}})
      (config/clear-cache!)
      (is (not (modules/active? (config/all (h/db)) :eca/completion)))
      (is (= {:items [{:text "abc"}]} (request "completion/inline" {:doc-text "abc"})))))
  (h/reset-components!)
  (testing "an external module's method, while it's active"
    (modules/add-external! "/tmp/mods/ping.clj"
                           {:id :acme/ping
                            :rpc {"acme/ping" {:kind :request
                                               :handler (fn [ctx params] {:module (:module-id ctx) :params params})}}}
                           {:kind :global :rank 3})
    (is (= {:module :acme/ping :params {:a 1}} (request "acme/ping" {:a 1})))
    (testing "not as the other kind"
      (is (= ::jsonrpc.server/method-not-found (jsonrpc.server/receive-notification "acme/ping" (h/components) {}))))
    (reset! config/initialization-config* {:modules {:acme/ping {:enabled false}}})
    (config/clear-cache!)
    (is (= ::jsonrpc.server/method-not-found (request "acme/ping" {}))))
  (h/reset-components!)
  (testing "unknown methods, and core's with the other kind: jsonrpc4clj's \"Method not found\""
    (is (= ::jsonrpc.server/method-not-found (request "nope/nope" {})))
    (is (= ::jsonrpc.server/method-not-found (jsonrpc.server/receive-notification "nope/nope" (h/components) {})))
    (is (= ::jsonrpc.server/method-not-found (request "chat/promptStop" {})))))
