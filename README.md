# Commerce Synthetic Application (`otel-shop-3-layer`)

Distributed 3-layer synthetic e-commerce application designed to generate deterministic **OpenTelemetry Traces (Spans)**, **Metrics**, and **Logs** across real HTTP, JDBC (MySQL), and AMQP (RabbitMQ) boundaries.

---

## 1. Architecture Overview

```text
Journey Generator (Java 21 + OTel Agent, :8085)
        |
        | HTTP (POST /api/journeys)
        v
Apache HTTP Server (httpd:2.4.62-alpine, :80 -> host :18080)
        |
        | reverse proxy /api/* (preserves W3C traceparent + x-run-id + x-journey-id)
        v
Spring Cloud Gateway (api-gateway, WebFlux, :8080)
        |
        +------------------------------+
        |                              |
        v                              v
  Cart Service                  Checkout Service
  (:8081, MVC)                  (:8082, MVC)
        |                        |      |      |
        | JDBC                   | JDBC | HTTP | AMQP (Publisher Confirms)
        v                        v      v      v
     MySQL                    MySQL  Merchant RabbitMQ (commerce.orders)
   (cart_db)              (checkout_db) (:8083)        |
                                                       | AMQP (Manual ACK after DB commit)
                                                       v
                                              Fulfillment Service
                                                (:8084, MVC+AMQP)
                                                       |
                                                       | JDBC
                                                       v
                                                     MySQL
                                                (fulfillment_db)
```

### Telemetry Pipeline (`compose.test.yaml`)

```text
Java Services (6x) + OpenTelemetry Java Agent v2.10.0
   |
   | OTLP/gRPC (Traces + Metrics + Logs)
   v
OpenTelemetry Collector (otel-collector:4317 / :4318)
   |
   +---> [Traces Pipeline]  ---> Jaeger OTLP (jaeger:4317, UI/API :16686) + /var/log/otel/traces.json
   |
   +---> [Metrics Pipeline] ---> Prometheus Exporter (:8889/metrics)      + /var/log/otel/metrics.json
   |
   +---> [Logs Pipeline]    ---> Structured OTLP JSON                     + /var/log/otel/logs.json
```

---

## 2. Deployable Components

| Component | Image | Runtime / Base | Internal Port | Primary Responsibility |
|---|---|---|---:|---|
| `apache-web` | `commerce/apache-web:1.0.0` | `httpd:2.4.62-alpine` (non-root `UID 82`) | `80` (host `18080`) | Public entry point, `/healthz`, reverse proxy `/api/*` |
| `api-gateway` | `commerce/api-gateway:1.0.0` | Java 21, Spring Cloud Gateway | `8080` | Route dispatch, `/api/journeys` orchestration, header propagation |
| `cart-service` | `commerce/cart-service:1.0.0` | Java 21, Spring Boot MVC + JDBC + Flyway | `8081` | Cart creation (`POST /internal/carts`) and read (`GET /internal/carts/{id}`) |
| `checkout-service` | `commerce/checkout-service:1.0.0` | Java 21, Spring Boot MVC + JDBC + AMQP | `8082` | Checkout orchestration, MySQL order persistence, RabbitMQ confirmed publish |
| `merchant-service` | `commerce/merchant-service:1.0.0` | Java 21, Spring Boot MVC | `8083` | Payment authorization simulation (`POST /internal/payments/authorize`) |
| `fulfillment-service` | `commerce/fulfillment-service:1.0.0` | Java 21, Spring Boot MVC + AMQP + JDBC | `8084` | Manual-ACK RabbitMQ consumer, idempotent MySQL fulfillment persistence |
| `journey-generator` | `commerce/journey-generator:1.0.0` | Java 21, Spring Boot Scheduler + REST | `8085` | Fixed-delay journey scheduler (default 10s, `max_in_flight=1`) and trigger API |
| `mysql` | `mysql:8.4.3` | MySQL 8.4 LTS (`utf8mb4_0900_ai_ci`, UTC) | `3306` | Isolated schemas (`cart_db`, `checkout_db`, `fulfillment_db`) and dedicated users |
| `rabbitmq` | `rabbitmq:3.13.7-management-alpine` | RabbitMQ 3.13 | `5672`, `15672` | Durable topic exchanges and queues with Dead-Letter routing |
| `otel-collector` | `commerce/otel-collector:1.0.0` | `otel/opentelemetry-collector-contrib:0.114.0` | `4317`, `4318`, `8889` | Receives OTLP Traces, Metrics, and Logs; exports to Jaeger, Prometheus, and JSON |
| `jaeger` | `jaegertracing/all-in-one:1.62.0` | Jaeger All-in-One | `4317`, `16686` | Test OTLP trace backend and HTTP query API |
| `test-runner` | `commerce/test-runner:1.0.0` | `python:3.12.7-alpine` | — | Automated verification of Gates 2–12 (DB, AMQP, Jaeger Traces, Metrics, Logs) |

---

## 3. Functional Workflow & State Machine

### Canonical Journey State Model

```text
STARTED -> PAGE_LOADED -> CART_CREATED -> CART_READ -> PAYMENT_AUTHORIZED -> ORDER_PUBLISHED -> FULFILLED
```

Terminal failure states:
- `CHECKOUT_FAILED` (`BE_ERROR`)
- `DB_TIMEOUT` (`DB_TIMEOUT`)
- `MERCHANT_TIMEOUT` (`MERCHANT_TIMEOUT`)
- `PAYMENT_AUTHORIZED_NOT_PUBLISHED` (`BROKER_UNAVAILABLE`)
- `FULFILLMENT_FAILED` (`FULFILLMENT_ERROR`)
- `INTERNAL_FAILURE`

### Propagated Headers

| Header | Purpose | Scope |
|---|---|---|
| `traceparent` | W3C Trace Context | All HTTP and RabbitMQ boundaries |
| `tracestate` | W3C vendor state | Preserved across HTTP and AMQP |
| `baggage` | W3C baggage | Preserved across HTTP and AMQP |
| `x-run-id` | Run correlation UUID | All business HTTP calls, AMQP headers, spans, and logs |
| `x-journey-id` | Journey correlation UUID | All business HTTP calls, AMQP headers, spans, and logs |
| `x-simulation-scenario` | Selected fault scenario | Internal network / test harness |
| `x-simulation-enabled` | Fault activation flag (`true`/`false`) | Internal network / test harness |

---

## 4. Database & Messaging Design

### MySQL 8.4 Schema Separation

| Service | Schema | Dedicated User | Flyway Migration | Primary Table & Constraints |
|---|---|---|---|---|
| `cart-service` | `cart_db` | `cart_app` | [`V1__create_cart_table.sql`](database/cart/migrations/V1__create_cart_table.sql) | `cart` (`PK cart_id`, `UNIQUE journey_id`) |
| `checkout-service` | `checkout_db` | `checkout_app` | [`V1__create_orders_table.sql`](database/checkout/migrations/V1__create_orders_table.sql) | `orders` (`PK order_id`, `UNIQUE journey_id`) |
| `fulfillment-service` | `fulfillment_db` | `fulfillment_app` | [`V1__create_fulfillment_table.sql`](database/fulfillment/migrations/V1__create_fulfillment_table.sql) | `fulfillment` (`PK fulfillment_id`, `UNIQUE order_id`, `UNIQUE journey_id`) |

Cross-schema queries between application users are rejected at the MySQL privilege layer and verified in Gate 8.

### RabbitMQ Topology

- **Exchange**: `commerce.orders` (`topic`, `durable=true`)
- **Routing key**: `order.created`
- **Queue**: `commerce.fulfillment` (`durable=true`, `x-dead-letter-exchange=commerce.orders.dlx`)
- **Dead-letter exchange**: `commerce.orders.dlx` (`topic`, `durable=true`)
- **Dead-letter queue**: `commerce.fulfillment.dlq` (`durable=true`)
- **Reliability**:
  - Publisher confirms (`ConfirmType.CORRELATED`) are mandatory before updating order status to `PUBLISHED`.
  - Consumer acknowledgement is `MANUAL` (`prefetch=1`) and is sent strictly after the MySQL `fulfillment_db` transaction commits.

---

## 5. Deterministic Scenarios

`SCENARIO_ENABLED=false` forces the `NORMAL` execution path even when a non-normal `SCENARIO_NAME` is supplied.

| Scenario | Injection Point | HTTP Status | DB & Messaging Outcome | Telemetry Outcome (Spans, Metrics, Logs) |
|---|---|---:|---|---|
| `NORMAL` | None | `202` | Cart `CART_READ`, Order `PUBLISHED`, Fulfillment `FULFILLED` | Single 6-service trace, completion metrics incremented, `INFO` state logs |
| `DB_SLOW` | Checkout MySQL | `202` | Real `SELECT SLEEP(1.5)` query; full workflow completes | Delayed JDBC span visible in Jaeger; completion metrics & `INFO` logs |
| `BE_ERROR` | Checkout pre-DB | `500` | Fails before DB; no order row, no Merchant call, no AMQP publish | Error spans on Checkout & Gateway, `commerce.checkouts.failed` incremented, `ERROR` log |
| `DB_TIMEOUT` | Checkout MySQL | `504` | Real JDBC `SQLTimeoutException`; no Merchant call, no AMQP publish | Error JDBC & server spans, `error_code=DB_TIMEOUT`, `ERROR` log |
| `MERCHANT_TIMEOUT` | Merchant HTTP call | `504` | Real HTTP read timeout; no order row, no AMQP publish | Error client & server spans, `error_code=MERCHANT_TIMEOUT`, `ERROR` log |
| `BROKER_UNAVAILABLE` | RabbitMQ publish | `503` | Order persisted with `PAYMENT_AUTHORIZED_NOT_PUBLISHED`; no consumer message | Error span on Checkout, `error_code=BROKER_UNAVAILABLE`, `ERROR` log |
| `FULFILLMENT_ERROR` | Fulfillment consumer | `202` | Order `PUBLISHED`, consumer rejects (`requeue=false`) to `commerce.fulfillment.dlq`, no fulfillment row | Error consumer span in Jaeger, `commerce.fulfillments.failed` incremented, `ERROR` log |

---

## 6. OpenTelemetry Signals: Spans, Metrics, and Logs

### 6.1 Distributed Traces (Spans)
- Instrumented via **OpenTelemetry Java Agent `v2.10.0`** with W3C `tracecontext,baggage` propagation.
- Enriched with business attributes (`commerce.run_id`, `commerce.journey_id`, `commerce.scenario`, `commerce.scenario_enabled`, `commerce.effective_scenario`, `commerce.journey.state`, `commerce.cart_id`, `commerce.order_id`, `commerce.fulfillment_id`, `commerce.error_code`).

### 6.2 OpenTelemetry Metrics (Automatic + Custom Business Metrics)
Exported via OTLP (`OTEL_METRICS_EXPORTER=otlp`) to `otel-collector` and exposed at `http://otel-collector:8889/metrics` and `/var/log/otel/metrics.json`:
- **Automatic Agent Metrics**:
  - `http.server.request.duration` (`s`)
  - `http.client.request.duration` (`s`)
  - `jvm.memory.used` (`By`)
  - `db.client.connections.usage` (`{connection}`)
- **Custom Business Metrics (`commerce.synthetic.metrics`)**:
  - `journey-generator`: `commerce.journeys.started`, `commerce.journeys.completed`, `commerce.journeys.failed`, `commerce.journey.client.duration` (`ms`)
  - `api-gateway`: `commerce.gateway.requests`, `commerce.gateway.orchestration.duration` (`ms`)
  - `cart-service`: `commerce.carts.created`, `commerce.carts.read`, `commerce.cart.operation.duration` (`ms`)
  - `checkout-service`: `commerce.checkouts.started`, `commerce.checkouts.completed`, `commerce.checkouts.failed`, `commerce.orders.published`, `commerce.checkout.duration` (`ms`)
  - `merchant-service`: `commerce.payments.requests`, `commerce.payment.authorization.duration` (`ms`)
  - `fulfillment-service`: `commerce.fulfillments.received`, `commerce.fulfillments.completed`, `commerce.fulfillments.failed`, `commerce.fulfillment.duration` (`ms`)

### 6.3 OpenTelemetry Logs & Structured Stdout Logs
- **Stdout JSON Logs**: Single-line JSON emitted by all Java services (`logback-spring.xml`) and Apache (`httpd.conf` `json_telemetry` format) with `timestamp`, `severity`, `service`, `trace_id`, `span_id`, `run_id`, `journey_id`, `scenario`, `journey_state`, `error_code`, and `message`.
- **OTLP LogRecords**: Exported via `OTEL_LOGS_EXPORTER=otlp` with `-Dotel.instrumentation.logback-appender.experimental.capture-mdc-attributes=*`, preserving native OTLP `traceId`, `spanId`, `severityText`, and all MDC correlation attributes in `/var/log/otel/logs.json`.

---

## 7. Host Requirements & Operator Commands

The host requires **only Docker Engine, Docker Compose, and Git**. No local JDK or Maven installation is needed.

| Command | Description |
|---|---|
| `make build` | Build all container images with CycloneDX SBOMs and verify non-root UIDs |
| `make unit-test` | Run Maven unit tests and JaCoCo coverage inside Docker |
| `make integration-test` | Start test stack and verify MySQL and RabbitMQ readiness contracts |
| `make e2e-test` | Execute complete E2E suite (30 normal journeys + 6x3 fault scenarios + Jaeger, Metrics, Logs gates) |
| `make test` | Run unit tests, build all images, and run the complete E2E verification suite |
| `make up` | Start the test stack in background and wait for all health checks |
| `make down` | Stop and remove all containers and ephemeral volumes |
| `make clean` | Tear down stack and clean generated test reports |
| `make publish` | Push verified images to container registry (requires passing `test/reports/e2e-test-report.json`) |

### Dynatrace OTLP Mode

To run with Dynatrace OTLP export (`traces`, `metrics` with `cumulativetodelta`, and `logs`):

```bash
export DT_OTLP_ENDPOINT="https://<environment-id>.live.dynatrace.com/api/v2/otlp"
export DT_API_TOKEN="<dynatrace-ingest-token>"
./scripts/compose-up.sh dynatrace
```

---

## 8. Normative Documentation & Reports

- [Definitive Architecture, Implementation and Test Specification](docs/definitive-specification.md)
- [OpenTelemetry Metrics and Logs Specification](docs/metrics-and-logs-specification.md)
- Generated E2E Reports (created by `make test` / `make e2e-test`):
  - `test/reports/e2e-test-summary.txt`
  - `test/reports/e2e-test-report.json`
