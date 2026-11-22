# Commerce Synthetic Application (`otel-shop-3-layer`)

A **3-layer distributed e-commerce application** designed to deterministically generate and validate OpenTelemetry **Distributed Traces (Spans)**, **Metrics**, and **Logs** across real **HTTP**, **JDBC (MySQL)**, and **AMQP (RabbitMQ)** calls, featuring an interactive **Single Page Application (SPA)** storefront with **JWT authentication** ready for **Dynatrace RUM**.

The project runs identically on **Docker Compose** and **Kubernetes** (local `kind` or remote clusters monitored by **Dynatrace**), with development, build, and testing executed **100% inside containers**.

---

## 1. Project Purpose

- Exercise a complete end-to-end e-commerce workflow across independent processes (Browser SPA / Journey Generator → Web Tier → API Gateway → Microservices → MySQL → RabbitMQ → Async Fulfillment Consumer).
- Provide an interactive **v2.0 SPA Storefront** (3 demo products, user registration, JWT login, shopping cart, checkout confirmation, and live order fulfillment tracking) ready to attach **Dynatrace RUM** (`dtrum.identifyUser()` hook, History API routing, `fetch()` correlation).
- Propagate **W3C Trace Context** (`traceparent`, `tracestate`, `baggage`) and business correlation headers (`x-run-id`, `x-journey-id`, `x-user-id`) across all HTTP and AMQP boundaries.
- Generate continuous controlled traffic (via `journey-generator`, including auth verification) and inject **6 deterministic fault scenarios** to validate observability under normal and degraded conditions.
- Automatically verify **Spans, Metrics, Logs, and SPA/Auth flows** on a lightweight local open-source stack (**Jaeger**, **VictoriaMetrics**, **VictoriaLogs**, **Fluent Bit**, **OpenTelemetry Collector**) while staying ready for clean deployment on Kubernetes clusters monitored by **Dynatrace**.

---

## 2. Architecture & Structure

```text
Browser (SPA + Dynatrace RUM) / Journey Generator (Java 21, fixed-delay 10s, :8085)
        |
        | HTTP  GET / (SPA) & /api/* (JWT Bearer + W3C traceparent + x-run-id + x-journey-id)
        v
Apache HTTP Server (httpd:2.4.62-alpine, :80 / host :18080, SPA FallbackResource)
        |
        | reverse proxy /api/*
        v
Spring Cloud Gateway (api-gateway, WebFlux + JwtAuthenticationWebFilter, :8080)
        |
        +-------------------+-------------------+-------------------+
        |                   |                   |                   |
        v                   v                   v                   v
  Auth Service        Cart Service       Checkout Service    Fulfillment Service
  (:8086, MVC)        (:8081, MVC)         (:8082, MVC)       (:8084, MVC+AMQP)
        |                   |             |      |      |           ^     |
        | JDBC              | JDBC        | JDBC | HTTP | AMQP      |     | JDBC
        v                   v             v      v      v           |     v
     MySQL               MySQL         MySQL  Merchant RabbitMQ ----+   MySQL
   (auth_db)           (cart_db)   (checkout_db) (:8083) (commerce.orders) (fulfillment_db)
```

### Components & Layers

| Layer (K8s Namespace) | Component | Technology | Responsibility |
|---|---|---|---|
| **Edge** (`commerce-edge`) | `apache-web` | Apache `httpd:2.4.62-alpine` (`UID 82`) | Public entry point, **v2.0 Frontend SPA** (`/`), `/healthz`, reverse proxy `/api/*` |
| **Edge** (`commerce-edge`) | `api-gateway` | Java 21, Spring Cloud Gateway | `POST /api/journeys` orchestration, static routes, **JWT validation** (`JwtAuthenticationWebFilter`), header propagation |
| **Edge** (`commerce-edge`) | `journey-generator` | Java 21, Spring Boot | Continuous traffic simulator (10s, `max_in_flight=1`) with JWT auth/login checks + trigger API |
| **Core** (`commerce-core`) | `auth-service` | Java 21, Spring MVC + JDBC + Flyway + BCrypt/JWT | User registration, **JWT HS256** login, `/me` profile, seeded demo user (`demo@otelshop.local`) on `auth_db` |
| **Core** (`commerce-core`) | `cart-service` | Java 21, Spring MVC + JDBC + Flyway | **3 demo products** catalog (`/api/products`), cart and line-item management on `cart_db` |
| **Core** (`commerce-core`) | `checkout-service` | Java 21, Spring MVC + JDBC + AMQP | Checkout orchestration, user order history (`/api/orders`) on `checkout_db`, confirmed RabbitMQ publish |
| **Core** (`commerce-core`) | `merchant-service` | Java 21, Spring MVC | Payment authorization simulation |
| **Core** (`commerce-core`) | `fulfillment-service` | Java 21, Spring MVC + AMQP + JDBC | AMQP consumer (prefetch 1, manual ACK post-commit on `fulfillment_db`) + status API `/api/fulfillments/{orderId}` |
| **Data** (`commerce-data`) | `mysql` | MySQL `8.4.3` (`utf8mb4_0900_ai_ci`, UTC) | **Persistent storage** (Compose named volume / K8s `5Gi` PVC), 4 isolated schemas and users (`auth_db`, `cart_db`, `checkout_db`, `fulfillment_db`) |
| **Data** (`commerce-data`) | `rabbitmq` | RabbitMQ `3.13.7-management-alpine` | Durable `commerce.orders` exchange, `commerce.fulfillment` queue, and DLX/DLQ |
| **Observability** (`commerce-observability`) | `otel-collector`, `jaeger`, `victoria-metrics`, `victoria-logs`, `fluent-bit` | Lightweight open-source OTel stack *(local/test only)* | Collection and visualization of Traces, Metrics, and Logs (OTLP + container stdout) |

---

## 3. Supported Scenarios

The canonical workflow transitions through `STARTED → PAGE_LOADED → CART_CREATED → CART_READ → PAYMENT_AUTHORIZED → ORDER_PUBLISHED → FULFILLED`.  
When `SCENARIO_ENABLED=false`, the system always executes the `NORMAL` path even if another `SCENARIO_NAME` is configured.

| Scenario | Injection Point | HTTP | DB / RabbitMQ Effect | Expected Telemetry |
|---|---|---:|---|---|
| `NORMAL` | None | `202` | Cart `CART_READ`, Order `PUBLISHED`, Fulfillment `FULFILLED` | Single distributed trace across services, completion metrics, `INFO` logs |
| `DB_SLOW` | MySQL in Checkout | `202` | Real `SELECT SLEEP(1.5)` query on `checkout_db`; flow completes | Slow JDBC span visible in Jaeger; normal completion |
| `BE_ERROR` | Checkout pre-DB | `500` | Fails before DB; no order, no Merchant/RabbitMQ calls | Error span on Checkout/Gateway, `error_code=BE_ERROR`, `ERROR` log |
| `DB_TIMEOUT` | MySQL in Checkout | `504` | Real JDBC timeout (`SQLTimeoutException`); no AMQP publish | JDBC timeout span, `error_code=DB_TIMEOUT`, `ERROR` log |
| `MERCHANT_TIMEOUT` | Merchant HTTP call | `504` | Real HTTP client timeout; no order or AMQP publish | HTTP client timeout span, `error_code=MERCHANT_TIMEOUT`, `ERROR` log |
| `BROKER_UNAVAILABLE` | RabbitMQ publish | `503` | Order saved as `PAYMENT_AUTHORIZED_NOT_PUBLISHED`; no consumer | Publish error span, `error_code=BROKER_UNAVAILABLE`, `ERROR` log |
| `FULFILLMENT_ERROR` | Fulfillment consumer | `202` | Order `PUBLISHED`, consumer rejects (`requeue=false`) to `commerce.fulfillment.dlq`, no fulfillment row | Consumer error span in Jaeger, `commerce.fulfillments.failed`, `ERROR` log |

### How to Enable & Disable Fault Scenarios

Fault scenarios can be triggered either **globally** (affecting both the SPA and `journey-generator`) or **per-request** (without restarting any service). Setting `SCENARIO_ENABLED=false` always restores the `NORMAL` path.

- **Single request (no restart, via HTTP headers)**:
  ```bash
  curl -i -X POST http://localhost:18080/api/journeys \
    -H "Content-Type: application/json" \
    -H "x-run-id: 11111111-1111-4111-8111-111111111111" \
    -H "x-journey-id: 22222222-2222-4222-8222-222222222222" \
    -H "x-simulation-scenario: DB_TIMEOUT" \
    -H "x-simulation-enabled: true" \
    -d '{"run_id":"11111111-1111-4111-8111-111111111111","journey_id":"22222222-2222-4222-8222-222222222222","sku":"SKU-WATCH-01","quantity":1}'
  ```
- **Global on Docker Compose** (example with `DB_SLOW`, then disable):
  ```bash
  # Enable
  SCENARIO_NAME=DB_SLOW SCENARIO_ENABLED=true ./scripts/compose-up.sh test

  # Disable (return to NORMAL)
  SCENARIO_NAME=NORMAL SCENARIO_ENABLED=false ./scripts/compose-up.sh test
  ```
- **Global on Kubernetes** (example with `BE_ERROR`, then disable):
  ```bash
  # Enable
  kubectl -n commerce-core patch configmap commerce-core-config --type merge -p '{"data":{"SCENARIO_NAME":"BE_ERROR","SCENARIO_ENABLED":"true"}}'
  kubectl -n commerce-edge patch configmap commerce-edge-config --type merge -p '{"data":{"SCENARIO_NAME":"BE_ERROR","SCENARIO_ENABLED":"true"}}'
  kubectl -n commerce-core rollout restart deployment/checkout-service deployment/merchant-service deployment/fulfillment-service
  kubectl -n commerce-edge rollout restart deployment/api-gateway deployment/journey-generator

  # Disable (return to NORMAL)
  kubectl -n commerce-core patch configmap commerce-core-config --type merge -p '{"data":{"SCENARIO_NAME":"NORMAL","SCENARIO_ENABLED":"false"}}'
  kubectl -n commerce-edge patch configmap commerce-edge-config --type merge -p '{"data":{"SCENARIO_NAME":"NORMAL","SCENARIO_ENABLED":"false"}}'
  kubectl -n commerce-core rollout restart deployment/checkout-service deployment/merchant-service deployment/fulfillment-service
  kubectl -n commerce-edge rollout restart deployment/api-gateway deployment/journey-generator
  ```

For full step-by-step instructions, timing parameters (`DB_SLOW_DURATION_MS`, `DB_OPERATION_TIMEOUT_MS`, `MERCHANT_TIMEOUT_MS`, etc.), and verification commands see [`docs/operations-runbook.md`](docs/operations-runbook.md#5-enabling--disabling-fault-scenarios).

---

## 4. Observability: Spans, Metrics, and Logs

All Java services bundle the **OpenTelemetry Java Agent `v2.10.0`** plus the shared `scenario-contract` library ([`TelemetryHelper.java`](libs/scenario-contract/src/main/java/com/commerce/synthetic/scenario/TelemetryHelper.java)):

- **Traces (Spans)**: automatic HTTP, JDBC, and Spring AMQP instrumentation enriched with business attributes (`commerce.run_id`, `commerce.journey_id`, `commerce.scenario`, `commerce.journey.state`, `commerce.cart_id`, `commerce.order_id`, `commerce.fulfillment_id`).
- **Metrics**: 4 automatic runtime families (`http.server.request.duration`, `http.client.request.duration`, `jvm.memory.used`, `db.client.connections.usage`) + **custom business metrics** (`commerce.auth.*`, `commerce.journeys.*`, `commerce.gateway.*`, `commerce.carts.*`, `commerce.checkouts.*`, `commerce.orders.published`, `commerce.payments.*`, `commerce.fulfillments.*`).
- **Logs**: dual-channel — **structured JSON on `stdout`** (including Apache, collected in K8s by **Fluent Bit**) + **OTLP LogRecords** carrying `trace_id`, `span_id`, and MDC attributes (`run_id`, `journey_id`, `scenario`, `journey_state`).

For the full metrics, attributes, and log format catalog see [`docs/metrics-and-logs-specification.md`](docs/metrics-and-logs-specification.md).

---

## 5. How to Use

The project supports **two cleanly separated operating modes**: **Development** (self-contained local environment with an open-source observability stack) and **Production** (clean deployment for clusters/hosts monitored by **Dynatrace**, without local observability tools).

**Host prerequisites**: only **Docker Engine**, **Docker Compose**, and **Git** (no local JDK, Maven, Node, or Python required).

---

### Part A — Development Mode (with Local Observability Stack)

Includes the local open-source observability stack to inspect and automatically verify **Spans (Jaeger)**, **Metrics (VictoriaMetrics / Prometheus)**, and **Logs (VictoriaLogs + Fluent Bit / OTLP)** with zero external dependencies.

#### 1. Run and Test on Docker Compose
```bash
make test       # Runs unit tests, non-root image builds, and full E2E suite (Gates 1–13)
make up         # Starts application stack + Jaeger + OTel Collector
make down       # Stops containers while preserving persistent MySQL/RabbitMQ volumes
make clean      # Stops containers, removes volumes, and cleans test reports
```

#### 2. Run and Test on Local Kubernetes (`kind`)
Automatically installs `kind` and `kubectl` into `~/.local/bin` (zero system pollution), creates the `commerce-synthetic` cluster with **4 namespaces** (`commerce-edge`, `commerce-core`, `commerce-data`, `commerce-observability`), loads local images, and runs all E2E tests in-cluster:
```bash
make build      # Builds all 10 local v2.0.0 images
make k8s-up     # Starts kind with k8s/overlays/local-observability (4 namespaces + MySQL PVC)
make k8s-test   # Validates dynatrace-ready overlay and runs the E2E Job on kind (Gates 1–13)
make k8s-down   # Deletes the kind cluster
```

**Local Endpoints & UIs** (`kind` or Docker Compose):
- **Frontend E-Commerce SPA (v2.0)**: `http://localhost:18080/` *(pre-seeded demo account: `demo@otelshop.local` / `DemoPass123!`, or self-register at `/register`)*
- **Apache Health Check**: `http://localhost:18080/healthz`
- **Jaeger UI (Traces)**: `http://localhost:16686`
- **VictoriaMetrics UI (Metrics)**: `http://localhost:18428/vmui`
- **VictoriaLogs UI (Logs)**: `http://localhost:19428/select/vmui`

---

### Part B — Production Mode (Dynatrace-Ready, No Local Observability Stack)

Designed for deployment on a **Production Kubernetes cluster** (or Docker host) already monitored by **Dynatrace**, completely excluding Jaeger, VictoriaMetrics, VictoriaLogs, Fluent Bit, and the `commerce-observability` namespace.

#### 1. Deploy to a Kubernetes Cluster with Dynatrace
The [`k8s/overlays/dynatrace-ready`](k8s/overlays/dynatrace-ready/kustomization.yaml) overlay:
- Deploys **only** the 3 application namespaces: `commerce-edge`, `commerce-core`, `commerce-data`.
- Pulls published images directly from Docker Hub (`docker.io/lusig76/otel-shop-*:2.0.0`) with no local build or `kind load` required.
- **Mode A (Default — Dynatrace OneAgent + RUM)**: disables the bundled OTel Java Agent (`JAVA_TOOL_OPTIONS=""`) to avoid dual-agent conflicts on the JVM while Dynatrace Operator / OneAgent instruments the services and auto-injects RUM into `apache-web`.
- **Mode B (Alternative — Dynatrace OTLP Ingest)**: keeps the OTel Java Agent exporting Traces, Metrics, and Logs directly to the Dynatrace OTLP endpoint (see [`docs/kubernetes-deployment.md`](docs/kubernetes-deployment.md)).

```bash
# Preview generated manifests (verify zero local observability resources)
make k8s-render-dynatrace

# Apply to target Kubernetes cluster
kubectl apply -k k8s/overlays/dynatrace-ready
```

#### 2. Deploy on Docker Compose with Dynatrace OTLP
Uses [`compose.dynatrace.yaml`](compose.dynatrace.yaml) and [`observability/dynatrace/otel-collector-dynatrace.yaml`](observability/dynatrace/otel-collector-dynatrace.yaml) (with `cumulativetodelta` processor for metrics):
```bash
export DT_OTLP_ENDPOINT="https://<environment-id>.live.dynatrace.com/api/v2/otlp"
export DT_API_TOKEN="<dynatrace-ingest-token>"
./scripts/compose-up.sh dynatrace
```

#### Published Docker Hub Images (`lusig76`)
All images used in real environments are published with semantic version tags (`2.0.0`) and short Git SHA (never `latest`):
`lusig76/otel-shop-auth-service`, `lusig76/otel-shop-apache-web`, `lusig76/otel-shop-api-gateway`, `lusig76/otel-shop-cart-service`, `lusig76/otel-shop-checkout-service`, `lusig76/otel-shop-merchant-service`, `lusig76/otel-shop-fulfillment-service`, `lusig76/otel-shop-journey-generator`, `lusig76/otel-shop-otel-collector`, `lusig76/otel-shop-test-runner`.

---

## 6. Development & Quality Notes

- **Container-only & Zero-Host-Pollution**: multi-module Maven compilation, JUnit 5 tests, JaCoCo coverage, and CycloneDX SBOM generation (`/app/sbom.cdx.json`) run inside the shared multi-stage [`docker/java-service/Dockerfile`](docker/java-service/Dockerfile). The frontend SPA uses zero-build Vanilla JS (ES Modules) with no Node/npm dependencies.
- **Security by design**: version-pinned base images, non-root execution (`UID 10001` for Java/OTel/Test-runner, `UID 82` for Apache), isolated MySQL users per schema, BCrypt password hashing, constant-time JWT signature verification, and no credentials tracked in Git or leaked in spans/logs.
- **Verified quality**: **13 automated Quality Gates** covering 30 `NORMAL` journeys, 6×3 fault-injection runs, DB/RabbitMQ integrity, Jaeger traces, 24+ metric families, correlated logs, and full v2.0 SPA/JWT/Fulfillment end-to-end flows.

---

## 7. Documentation Index & Map for Coding Agents

This `README.md` is the master index of the repository. Consult the specialized documents in [`docs/`](docs/) for detailed specifications and operational guides:

| Document | Main Content | When to Read (Humans & Coding Agents) |
|---|---|---|
| [`docs/definitive-specification.md`](docs/definitive-specification.md) | Baseline normative specification: architecture, HTTP/AMQP/DB contracts, state machine, fault scenarios, and Gates 1–10. *(Canonical copy of the root specification)* | Before modifying REST/AMQP contracts, DB schemas, or fault scenario semantics. |
| [`docs/metrics-and-logs-specification.md`](docs/metrics-and-logs-specification.md) | Normative extension for OpenTelemetry Metrics (24+ families) and Logs (stdout JSON + OTLP + MDC) and Gates 11–12. | Before adding/modifying custom metrics, MDC fields, or Collector pipelines. |
| [`docs/kubernetes-deployment.md`](docs/kubernetes-deployment.md) | 4-namespace K8s architecture, manifest best practices, MySQL PVC persistence, `kind`, and `local-observability` vs `dynatrace-ready` overlays (Modes A/B). | When deploying on `kind` or a remote Kubernetes cluster monitored by Dynatrace. |
| [`docs/quality-and-testing.md`](docs/quality-and-testing.md) | Containerized test pyramid, complete definition of all **13 Quality Gates**, unit/integration/E2E execution, and report inspection. | When running quality checks, diagnosing failures, or extending the test suite. |
| [`docs/development-and-maintenance.md`](docs/development-and-maintenance.md) | How to evolve and maintain the monorepo (new scenarios, metrics, Flyway migrations, Docker Hub image releases) and invariant rules for coding agents. | Before making code changes or releasing a new version. |
| [`docs/operations-runbook.md`](docs/operations-runbook.md) | Day-2 operational commands: checking containers/pods (Docker Compose & Kubernetes, Dev & Production), streaming logs, MySQL login + SQL queries, and RabbitMQ checks. | Whenever operating, debugging, or inspecting live containers, logs, MySQL tables, or RabbitMQ queues. |
| [`docs/v2-implementation-journey.md`](docs/v2-implementation-journey.md) | Operational progress tracker for v2.0.0 (`feat/v2-spa-auth-ecommerce`): step-by-step status, unplanned/extra activities, and Architecture Decision Records (ADR). | To inspect the current v2.0.0 progress and architectural decisions at any time. |
