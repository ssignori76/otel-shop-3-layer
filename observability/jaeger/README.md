# Jaeger Test Fixture Configuration

Jaeger runs in `compose.test.yaml` using `jaegertracing/all-in-one:1.62.0` with `COLLECTOR_OTLP_ENABLED=true`.
It receives OTLP traces on port `4317` from `otel-collector` (and directly from services if configured) and exposes its HTTP query API and UI on port `16686`.
