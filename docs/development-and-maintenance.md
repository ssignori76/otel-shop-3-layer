# Development, Evolution & Maintenance Guide

This guide describes the technical conventions, maintenance workflows, and procedures for evolving the repository safely and consistently (for both human developers and **AI coding agents**).

---

## 1. Monorepo Structure

```text
otel-shop-3-layer/
├── pom.xml                          # Maven Parent (v2.0.0, Java 21, Spring Boot 3.3.5, Spring Cloud 2023.0.3)
├── frontend/                        # v2.0 Zero-build Vanilla JS (ES Modules) SPA served by Apache
├── libs/
│   ├── scenario-contract/           # Scenario enums, JourneyState, CorrelationHeaders, TelemetryHelper
│   ├── api-contract/                # Validated REST DTOs, ApiErrorResponse, JwtTokenProvider (HS256)
│   └── messaging-contract/          # RabbitMQ topology constants + OrderCreatedMessage contract
├── services/
│   ├── auth-service/                # Spring MVC + JDBC + Flyway + BCrypt/JWT (auth_db, :8086)
│   ├── api-gateway/                 # Spring Cloud Gateway + JwtAuthenticationWebFilter (WebFlux, :8080)
│   ├── cart-service/                # Spring MVC + JDBC + Flyway (cart_db + 3 demo products, :8081)
│   ├── checkout-service/            # Spring MVC + JDBC + Flyway + AMQP (checkout_db, :8082)
│   ├── merchant-service/            # Spring MVC (:8083)
│   └── fulfillment-service/         # Spring MVC + AMQP manual ACK + JDBC + Flyway (fulfillment_db, :8084)
├── journey-generator/               # Fixed-delay authenticated traffic generator + trigger API (:8085)
├── database/                        # Reference SQL migrations + schema/user init script
├── rabbitmq/                        # rabbitmq.conf + definitions.json (topology as code)
├── observability/                   # OTel Collector configs (local and Dynatrace)
├── docker/                          # Shared multi-stage Java Dockerfile, Apache + SPA, OTel Collector
├── compose*.yaml                    # Docker Compose (base, test, dynatrace)
├── k8s/                             # Kubernetes Kustomize manifests (base + overlays)
├── test/                            # Containerized test-runner + Python E2E suite (Gates 1–13)
├── scripts/                         # Operational scripts (build, test, compose, k8s, publish)
└── docs/                            # Normative and operational documentation
```

---

## 2. How to Evolve the Project

### 2.1 Adding a New Fault Scenario
1. Add the enum value in [`ScenarioName.java`](../libs/scenario-contract/src/main/java/com/commerce/synthetic/scenario/ScenarioName.java) and, if needed, a terminal state in [`JourneyState.java`](../libs/scenario-contract/src/main/java/com/commerce/synthetic/scenario/JourneyState.java).
2. Implement the injection point **only** in the owning microservice using `ctx.isScenarioActive(ScenarioName.NEW_SCENARIO)` (guarantees `SCENARIO_ENABLED=false` is respected).
3. Record the error on the span and in logs/metrics via [`TelemetryHelper.recordError(...)`](../libs/scenario-contract/src/main/java/com/commerce/synthetic/scenario/TelemetryHelper.java) and map the exception to [`ApiErrorResponse`](../libs/api-contract/src/main/java/com/commerce/synthetic/api/ApiErrorResponse.java).
4. Add unit tests in the owning service and register the scenario in [`test/scenarios/scenarios.json`](../test/scenarios/scenarios.json) and [`test/end-to-end/run_e2e_suite.py`](../test/end-to-end/run_e2e_suite.py).

### 2.2 Adding a New Metric or Log Attribute
- **Metrics**: use [`TelemetryHelper.incrementCounter(...)`](../libs/scenario-contract/src/main/java/com/commerce/synthetic/scenario/TelemetryHelper.java) or `TelemetryHelper.recordHistogram(...)` with prefix `commerce.<domain>.<name>`. Never create a separate `OpenTelemetrySdk` (`GlobalOpenTelemetry` automatically bridges to the OpenTelemetry Java Agent).
- **Logs**: set correlation fields via `MDC` (in [`TelemetryHelper`](../libs/scenario-contract/src/main/java/com/commerce/synthetic/scenario/TelemetryHelper.java)), update the JSON pattern in `logback-spring.xml` if needed, and always clean up MDC in a `finally` block (`TelemetryHelper.clearMdc()`).
- **Update assertions**: add the metric name in `required_metric_families` in [`test/end-to-end/run_e2e_suite.py`](../test/end-to-end/run_e2e_suite.py) and document it in [`docs/metrics-and-logs-specification.md`](metrics-and-logs-specification.md).

### 2.3 Adding a Database Migration (Flyway)
- Always create a new versioned file `V<N>__<description>.sql` (never edit an already-applied migration: Flyway will fail startup on checksum mismatch, as required by Gate 8).
- Keep `database/<service>/migrations/` and `services/<service>/src/main/resources/db/migration/` synchronized.
- Always preserve schema isolation: no cross-schema queries across `auth_db`, `cart_db`, `checkout_db`, and `fulfillment_db`.

### 2.4 Releasing a New Version to Docker Hub
1. Update `<version>` in the parent [`pom.xml`](../pom.xml) and child POMs (e.g., `2.1.0`), or pass `APP_VERSION=2.1.0`.
2. Run `make test` and `make k8s-test` to generate verified reports with `"overall_passed": true`.
3. Publish to Docker Hub (requires `docker login`):
   ```bash
   APP_VERSION=2.1.0 DRY_RUN=false ./scripts/publish-images.sh
   ```
4. Update `newTag` in [`k8s/overlays/dynatrace-ready/kustomization.yaml`](../k8s/overlays/dynatrace-ready/kustomization.yaml).

---

## 3. Invariant Rules for Developers & Coding Agents

1. **Zero-Host-Tooling**: never introduce direct host `java`, `mvn`, `node`, or `npm` commands in `Makefile` or `scripts/`. Everything must build and test inside Docker containers.
2. **No `latest` tag**: every base and application image in Dockerfiles, Compose files, and Kubernetes manifests must use an explicit version tag.
3. **Mandatory Non-Root**: every application container image must run as a non-root user (`UID != 0`).
4. **No Span Duplication**: never create manual spans for HTTP, JDBC, or RabbitMQ calls already instrumented by the OpenTelemetry Java Agent; enrich only the current span (`Span.current()`).
5. **No Tracked Secrets**: never commit production passwords, Dynatrace tokens (`DT_API_TOKEN`), or `.env` files to Git, and never print secrets/passwords/JWTs in logs or spans.
6. **Docker Compose ↔ Kubernetes Parity**: every change to `test/end-to-end/run_e2e_suite.py` or HTTP/AMQP contracts must pass both `make test` (Compose) and `make k8s-test` (`kind`).
