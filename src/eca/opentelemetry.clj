(ns eca.opentelemetry
  (:require
   [eca.metrics :as metrics]
   [steffan-westcott.clj-otel.api.metrics.instrument :as instrument]
   [steffan-westcott.clj-otel.api.otel :as otel-api])
  (:import
   [io.opentelemetry.sdk.autoconfigure AutoConfiguredOpenTelemetrySdk]
   [java.util.function Function]))

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
