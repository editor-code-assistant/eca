---
description: "Configure ECA metrics: OpenTelemetry integration for exporting tool usage, prompt performance, and server telemetry."
---

# Metrics

## Opentelemetry integration

ECA export via opentelemetry if configured multiple metrics about its features.

The export is the `eca/otel` [module](./modules.md), off by default: an `otlp` config turns it on, and ECA exports from its start. `/modules disable eca/otel` stops exporting for the session; turned on again, it exports with the `otlp` config it first started with, as OpenTelemetry starts once per process.

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


