# Metrics

## Opentelemetry integration

ECA export via opentelemetry if configured multiple metrics about its features.

To configure, add your OTLP collector config via `:otlp` map following [otlp auto-configure settings](https://opentelemetry.io/docs/languages/java/configuration/#properties-general). Example:

```javascript title="~/.config/eca/config.json"
{
  "otlp": {
    "otel.exporter.otlp.metrics.protocol": "http/protobuf",
    "otel.exporter.otlp.metrics.endpoint": "https://my-otlp-endpoint.com/foo",
    "otel.exporter.otlp.headers": "Authorization=Bearer 123456"
  }
}
```


