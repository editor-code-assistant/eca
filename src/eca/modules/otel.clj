(ns eca.modules.otel
  "The `:eca/otel` module: ECA's metrics exported with OpenTelemetry, from the
   `otlp` config. Off by default: an `otlp` config turns it on, as it turned
   on the export before modules, and so do `modules.eca/otel.enabled` and
   `/modules enable eca/otel`. The export starts when ECA starts, as before
   modules (`eca.server`), so the startup's metrics count; the module's
   `:start` and `:stop` turn it on and off later."
  (:require
   [eca.metrics :as metrics]
   [steffan-westcott.clj-otel.api.metrics.instrument :as instrument]
   [steffan-westcott.clj-otel.api.otel :as otel-api])
  (:import
   [io.opentelemetry.sdk.autoconfigure AutoConfiguredOpenTelemetrySdk]
   [java.util.function Function]))

(set! *warn-on-reflection* true)

(defrecord OtelMetrics [otlp-config]
  metrics/IMetrics

  (start! [_this]
    (otel-api/set-global-otel!
     (-> (AutoConfiguredOpenTelemetrySdk/builder)
         (.addPropertiesCustomizer ^Function (constantly otlp-config))
         (.build)
         .getOpenTelemetrySdk)))

  (count! [_this name value attrs]
    (-> (instrument/instrument {:name (str "eca-" name)
                                :instrument-type :counter})
        (instrument/add! {:value value
                          :attributes attrs}))))

;; OpenTelemetry's global SDK can be set only once per process: the exporter
;; is made once, and turning the module off only stops counting with it
(defonce ^:private exporter* (atom nil))

(def ^:private lock (Object.))

(defn install!
  "Makes `metrics`, the session's switchable metrics, export with
   OpenTelemetry, starting the exporter from `otlp-config` the first time."
  [metrics otlp-config]
  (locking lock
    (let [exporter (or @exporter*
                       (let [exporter (->OtelMetrics otlp-config)]
                         (metrics/start! exporter)
                         (reset! exporter* exporter)))]
      (metrics/switch! metrics exporter))))

(defn uninstall!
  "Stops `metrics` exporting."
  [metrics]
  (metrics/switch! metrics (metrics/->NoopMetrics)))

(def module
  {:id :eca/otel
   :description "Export ECA's metrics with OpenTelemetry, from the otlp config."
   :default-enabled? false
   :config-spec {:otlp {:type :map}}
   ;; at startup the export already runs: install! changes nothing then
   :start (fn [{:keys [metrics config]}]
            (when-let [otlp-config (:otlp config)]
              (install! metrics otlp-config))
            nil)
   :stop (fn [{:keys [metrics]}] (uninstall! metrics))})
