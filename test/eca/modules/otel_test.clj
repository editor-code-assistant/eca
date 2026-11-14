(ns eca.modules.otel-test
  {:clj-kondo/config '{:lint-as {eca.modules.otel-test/with-fake-exporter clojure.core/fn}}}
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.metrics :as metrics]
   [eca.modules :as modules]
   [eca.modules.otel :as m.otel]
   [eca.server :as server]
   [eca.test-helper :as h]))

(h/reset-components-before-test)

(def ^:private otlp {"otel.metrics.exporter" "none"})

(defn- config
  "ECA's config without the user's files, with `extra` from the client."
  [& [extra]]
  (reset! config/initialization-config* (merge {:pureConfig true} extra))
  (config/clear-cache!)
  (config/all (h/db)))

(defrecord ^:private FakeExporter [events*]
  metrics/IMetrics
  (start! [_] (swap! events* conj :start))
  (count! [_ metric-name _ _] (swap! events* conj [:count metric-name])))

(defmacro ^:private with-fake-exporter
  "Runs body with OpenTelemetry's exporter faked, recording what happens to
   it in `events*`, and no exporter made yet."
  [[events*] & body]
  `(let [~events* (atom [])]
     (with-redefs [m.otel/->OtelMetrics (fn [otlp-config#]
                                                 (swap! ~events* conj [:new otlp-config#])
                                                 (->FakeExporter ~events*))]
       (reset! @#'m.otel/exporter* nil)
       (try
         ~@body
         (finally
           (reset! @#'m.otel/exporter* nil))))))

(deftest activation-test
  (testing "off by default"
    (is (not (modules/active? (config) :eca/otel))))
  (testing "an otlp config turns it on, as it turned on the export before modules"
    (is (modules/active? (config {:otlp otlp}) :eca/otel)))
  (testing "modules.eca/otel.enabled wins"
    (is (not (modules/active? (config {:otlp otlp :modules {:eca/otel {:enabled false}}}) :eca/otel)))))

(deftest install-test
  (with-fake-exporter [events*]
    (let [m (metrics/->switchable (metrics/->NoopMetrics))]
      (testing "the first install makes the exporter and starts it"
        (m.otel/install! m otlp)
        (metrics/count! m "x" 1 {})
        (is (= [[:new otlp] :start [:count "x"]] @events*)))
      (testing "off, nothing is exported"
        (m.otel/uninstall! m)
        (metrics/count! m "y" 1 {})
        (is (= [[:count "x"]] (filterv vector? (rest @events*)))))
      (testing "on again, the same exporter counts, started once: OpenTelemetry's global SDK is set only once"
        (m.otel/install! m otlp)
        (metrics/count! m "z" 1 {})
        (is (= [[:new otlp] :start [:count "x"] [:count "z"]] @events*))))))

(deftest module-test
  (with-fake-exporter [_events*]
    (let [m (metrics/->switchable (metrics/->NoopMetrics))
          components {:db* (h/db*) :messenger (h/messenger) :metrics m}]
      (with-redefs [modules/built-in-modules (constantly (h/built-in-modules-with :eca/otel))]
        (try
          (testing "an otlp config starts the export"
            (modules/start! components (config {:otlp otlp}))
            (is (instance? FakeExporter (metrics/current m))))
          (testing "turning it off stops it"
            (modules/set-enabled! :eca/otel false)
            (modules/reconcile! components (config {:otlp otlp}))
            (is (instance? eca.metrics.NoopMetrics (metrics/current m))))
          (finally
            (modules/stop-all! components)))))))

(deftest startup-metrics-test
  (with-fake-exporter [_events*]
    (testing "with an otlp config, ECA exports from its start, as before modules"
      (config {:otlp otlp})
      (is (instance? FakeExporter (metrics/current (#'server/->Metrics (h/db*))))))
    (testing "without, nothing is exported"
      (config)
      (is (instance? eca.metrics.NoopMetrics (metrics/current (#'server/->Metrics (h/db*))))))))
