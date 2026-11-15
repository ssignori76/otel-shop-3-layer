# Commerce Synthetic Application
## OpenTelemetry Metrics and Logs Specification (Extension to Definitive Specification)

**Status:** active normative extension  
**Scope:** OpenTelemetry Metrics, OpenTelemetry Logs, Structured Stdout Logs, Collector pipeline, and Automated Verification Gates  

---

# 1. Purpose

In the initial baseline specification (`docs/definitive-specification.md`, Section 3 and Section 28), metrics and logs were deferred to a later phase while only distributed trace spans were specified. This extension defines the normative specification for **OpenTelemetry Metrics** and **OpenTelemetry Logs** across all components of the Commerce Synthetic Application, elevating metrics and logs to release-verified signals alongside traces (spans).

# 2. Telemetry Pipeline Architecture

Because Jaeger (`jaegertracing/all-in-one`) only accepts OTLP Traces, an **OpenTelemetry Collector** (`otel-collector`) is deployed as the central OTLP ingestion router on ports `4317` (gRPC) and `4318` (HTTP):

```text
Java Services (6x) + OpenTelemetry Java Agent
   |
   | OTLP/gRPC (Traces + Metrics + Logs)
   v
OpenTelemetry Collector (otel-collector:4317)
   |
   +---> [Traces Pipeline]  ---> Jaeger OTLP (jaeger:4317) + /var/log/otel/traces.json
   |
   +---> [Metrics Pipeline] ---> Prometheus Exporter (:8889/metrics) + /var/log/otel/metrics.json
   |
   +---> [Logs Pipeline]    ---> Structured OTLP JSON (/var/log/otel/logs.json)
```

In `compose.dynatrace.yaml`, all three pipelines (`traces`, `metrics`, `logs`) forward OTLP/HTTP to the target Dynatrace environment endpoint (`/api/v2/otlp/v1/traces`, `/api/v2/otlp/v1/metrics`, `/api/v2/otlp/v1/logs`).

# 3. OpenTelemetry Metrics Specification

All six Java processes (`journey-generator`, `api-gateway`, `cart-service`, `checkout-service`, `merchant-service`, `fulfillment-service`) export both **Automatic Runtime/Infrastructure Metrics** (via the OpenTelemetry Java Agent) and **Custom Business Metrics** (via the OpenTelemetry Metrics API `io.opentelemetry.api.metrics.Meter`).

## 3.1 Required Resource Attributes on All Metrics

- `service.name`: unique per component (`journey-generator`, `api-gateway`, `cart-service`, `checkout-service`, `merchant-service`, `fulfillment-service`)
- `service.namespace`: `commerce-synthetic`
- `deployment.environment.name`: `test` (configurable)
- `service.version`: `1.0.0`

## 3.2 Automatic Runtime & Protocol Metrics

Collected automatically by the OpenTelemetry Java Agent:

| Metric Name | Instrument | Unit | Description |
|---|---|---|---|
| `http.server.request.duration` | Histogram | `s` | Server HTTP request duration across Gateway, Cart, Checkout, Merchant |
| `http.client.request.duration` | Histogram | `s` | Outbound HTTP client call duration |
| `jvm.memory.used` | UpDownCounter | `By` | JVM heap and non-heap memory usage |
| `jvm.thread.count` | UpDownCounter | `{thread}` | Active JVM threads |
| `db.client.connections.usage` | UpDownCounter | `{connection}` | HikariCP JDBC connection pool usage |

## 3.3 Custom Business Metrics

Measured via `GlobalOpenTelemetry.getMeter("commerce.synthetic.metrics")`:

| Component | Metric Name | Type | Unit | Required Attributes |
|---|---|---|---|---|
| `journey-generator` | `commerce.journeys.started` | Counter | `{journey}` | `commerce.scenario`, `commerce.scenario_enabled` |
| `journey-generator` | `commerce.journeys.completed` | Counter | `{journey}` | `commerce.scenario`, `commerce.journey.state`, `http.response.status_code` |
| `journey-generator` | `commerce.journeys.failed` | Counter | `{journey}` | `commerce.scenario`, `commerce.journey.state`, `error.code` |
| `journey-generator` | `commerce.journey.client.duration` | Histogram | `ms` | `commerce.scenario`, `commerce.journey.state` |
| `api-gateway` | `commerce.gateway.requests` | Counter | `{request}` | `commerce.scenario`, `commerce.journey.state`, `http.response.status_code` |
| `api-gateway` | `commerce.gateway.orchestration.duration` | Histogram | `ms` | `commerce.scenario`, `commerce.journey.state` |
| `cart-service` | `commerce.carts.created` | Counter | `{cart}` | `commerce.scenario` |
| `cart-service` | `commerce.carts.read` | Counter | `{cart}` | `commerce.scenario` |
| `cart-service` | `commerce.cart.operation.duration` | Histogram | `ms` | `commerce.operation`, `commerce.scenario` |
| `checkout-service` | `commerce.checkouts.started` | Counter | `{checkout}` | `commerce.scenario` |
| `checkout-service` | `commerce.checkouts.completed` | Counter | `{checkout}` | `commerce.scenario`, `commerce.journey.state` |
| `checkout-service` | `commerce.checkouts.failed` | Counter | `{checkout}` | `commerce.scenario`, `commerce.journey.state`, `error.code` |
| `checkout-service` | `commerce.orders.published` | Counter | `{order}` | `commerce.scenario` |
| `checkout-service` | `commerce.checkout.duration` | Histogram | `ms` | `commerce.scenario`, `commerce.journey.state` |
| `merchant-service` | `commerce.payments.requests` | Counter | `{payment}` | `commerce.scenario`, `payment.status` |
| `merchant-service` | `commerce.payment.authorization.duration` | Histogram | `ms` | `commerce.scenario`, `payment.status` |
| `fulfillment-service` | `commerce.fulfillments.received` | Counter | `{fulfillment}` | `commerce.scenario` |
| `fulfillment-service` | `commerce.fulfillments.completed` | Counter | `{fulfillment}` | `commerce.scenario`, `commerce.journey.state` |
| `fulfillment-service` | `commerce.fulfillments.failed` | Counter | `{fulfillment}` | `commerce.scenario`, `commerce.journey.state`, `error.code` |
| `fulfillment-service` | `commerce.fulfillment.duration` | Histogram | `ms` | `commerce.scenario`, `commerce.journey.state` |

# 4. OpenTelemetry Logs and Structured Logging Specification

## 4.1 Dual-Path Logging (Stdout JSON + OTLP Log Bridge)

1. **Structured JSON Stdout**:
   Every Java service formats stdout logs as single-line JSON records containing:
   - `timestamp` (UTC ISO-8601)
   - `severity` (`INFO`, `WARN`, `ERROR`)
   - `service` (`OTEL_SERVICE_NAME`)
   - `logger`
   - `thread`
   - `trace_id` (32-hex W3C trace ID injected by OpenTelemetry Java Agent)
   - `span_id` (16-hex W3C span ID injected by OpenTelemetry Java Agent)
   - `run_id` (MDC correlation UUID)
   - `journey_id` (MDC correlation UUID)
   - `scenario` (MDC active scenario name)
   - `scenario_enabled` (MDC boolean flag)
   - `journey_state` (MDC canonical journey state)
   - `message`
2. **OTLP LogRecords**:
   The OpenTelemetry Java Agent Logback bridge (`OTEL_LOGS_EXPORTER=otlp`) exports every log event to the OpenTelemetry Collector with:
   - Native OTLP `trace_id` and `span_id` fields populated from the current context;
   - `severity_number` and `severity_text`;
   - Captured MDC attributes (`run_id`, `journey_id`, `scenario`, `scenario_enabled`, `journey_state`, `cart_id`, `order_id`, `fulfillment_id`, `error_code`) enabled via `-Dotel.instrumentation.logback-appender.experimental.capture-mdc-attributes=*`.
3. **Apache HTTP Server Logs**:
   Apache emits structured JSON access logs to `/dev/stdout` containing `timestamp`, `service="apache-web"`, `method`, `uri`, `status`, `duration_us`, `traceparent`, `x_run_id`, `x_journey_id`, and error logs to `/dev/stderr`.

## 4.2 Mandatory Business Log Events

Every canonical state transition and failure terminal state must emit a correlated log event:

- `STARTED` (`journey-generator` before HTTP dispatch)
- `PAGE_LOADED` (`api-gateway` upon receiving `/api/journeys`)
- `CART_CREATED` (`cart-service` after `INSERT` into `cart_db.cart`)
- `CART_READ` (`cart-service` after `SELECT` from `cart_db.cart`)
- `PAYMENT_AUTHORIZED` (`merchant-service` & `checkout-service` after authorization)
- `ORDER_PUBLISHED` (`checkout-service` after positive RabbitMQ publisher confirm)
- `FULFILLED` (`fulfillment-service` after MySQL commit and before manual ACK)
- Terminal error states (`CHECKOUT_FAILED`, `DB_TIMEOUT`, `MERCHANT_TIMEOUT`, `PAYMENT_AUTHORIZED_NOT_PUBLISHED`, `FULFILLMENT_FAILED`, `INTERNAL_FAILURE`) logged at `ERROR` severity with `error_code`.

# 5. Automated Verification Gates for Metrics and Logs

In addition to Gates 1–10 in `docs/definitive-specification.md`:

- **Gate 11 (Metrics Validation)**:
  - The automated test runner queries `http://otel-collector:8889/metrics` and `/var/log/otel/metrics.json`.
  - Verifies presence and positive increments of both automatic runtime metrics (`http_server_request_duration_seconds`, `jvm_memory_used_bytes`) and custom business metrics (`commerce_journeys_*`, `commerce_gateway_*`, `commerce_carts_*`, `commerce_checkouts_*`, `commerce_payments_*`, `commerce_fulfillments_*`) for every service and scenario.
- **Gate 12 (Logs Validation)**:
  - The automated test runner inspects `/var/log/otel/logs.json` exported by the OpenTelemetry Collector.
  - Verifies that OTLP logs are received from all 6 Java services, carry non-empty `traceId` and `spanId` matching the Jaeger trace of the validated `journey_id`, carry `run_id`, `journey_id`, `scenario`, and `journey_state` attributes, emit `ERROR` severity records on failure scenarios, and contain no forbidden sensitive strings.
