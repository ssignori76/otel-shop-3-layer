# Quality Gates & Testing Strategy

This document defines all test levels, the **13 mandatory Quality Gates**, how to run containerized verification (on both Docker Compose and Kubernetes `kind`), and how to inspect the generated reports.

---

## 1. Test Pyramid (100% Containerized)

No host Java, Maven, Node, or Python tooling is required: all tests execute inside Docker containers.

| Level | Command | What It Verifies |
|---|---|---|
| **Unit Tests & JaCoCo** | `make unit-test` | Compiles all 11 Maven modules (including `auth-service`), runs JUnit 5 tests covering contracts, JWT signing/verification, Gateway JWT filter, state machine, REST controllers, AMQP listener, idempotency, poison messages, and JaCoCo coverage (stage `unit-test` in [`docker/java-service/Dockerfile`](../docker/java-service/Dockerfile)). |
| **Image Build & Non-Root Gate** | `make build` | Multi-stage builds of all 10 `2.0.0` images (including SPA in `apache-web` and `auth-service`), CycloneDX SBOM generation (`/app/sbom.cdx.json`), and automated verification that no application container runs as `UID 0`. |
| **Integration Checks** | `make integration-test` | Starts stack, verifies real `SELECT 1` on MySQL 8.4 with non-root user `cart_app`, and runs RabbitMQ AMQP port diagnostics. |
| **E2E Suite (Docker Compose)** | `make e2e-test` or `make test` | Runs [`test/end-to-end/run_e2e_suite.py`](../test/end-to-end/run_e2e_suite.py) against the Compose `dev` stack (`compose.yaml` + `compose.dev.yaml`), verifying Gates 3–13 (Jaeger, VictoriaMetrics, VictoriaLogs) and writing reports to `test/reports/`. |
| **E2E Suite (Kubernetes `kind`)** | `make k8s-test` | Static Kustomize validation of `prod` and `prod-otel` overlays (0 local observability resources) + in-cluster execution of Job `commerce-e2e-test-runner` on `kind` (`dev`) verifying Jaeger, **VictoriaMetrics** (PromQL), **VictoriaLogs** (OTLP + **Fluent Bit** LogsQL), and Gate 13 (SPA/JWT/Fulfillment). |

---

## 2. Complete List of 13 Quality Gates

| Gate | Scope | Automatically Verified Acceptance Criteria |
|---|---|---|
| **Gate 1** | Clean Containerized Build | All 11 Maven modules compile and pass unit tests in Docker; all 10 images use pinned tags (zero `latest`) and run non-root (`UID 10001` / `UID 82`). |
| **Gate 2** | Stack Startup & Readiness | All containers/pods reach `healthy`/`Ready` without restart loops; MySQL executes `SELECT 1`; RabbitMQ accepts AMQP connections; Jaeger and OTel Collector accept OTLP. |
| **Gate 3** | Scheduling & Route Guard | `journey-generator` configured with `interval=10s`, `max_in_flight=1`; unknown routes (`/api/unknown-route-check`) rejected by the Gateway with HTTP `404`. |
| **Gate 4** | Normal-Flow Stability | **30 consecutive `NORMAL` journeys** complete with HTTP `202`, `cart.status=CART_READ`, `orders.status=PUBLISHED`, `fulfillment.status=FULFILLED`, clean `commerce.fulfillment` queue (`ready=0, unacked=0`). Includes override check `SCENARIO_NAME=DB_TIMEOUT` with `SCENARIO_ENABLED=false` → `NORMAL` path. |
| **Gate 5** | Distributed Tracing (Spans) | Single `traceID` in Jaeger connecting the services (`journey-generator`, `api-gateway`, `cart-service`, `checkout-service`, `merchant-service`, `fulfillment-service`, plus `auth-service` on auth flows); no orphan parent spans; JDBC spans (`db.system=mysql`) and W3C propagation across HTTP and AMQP; zero credentials in spans. |
| **Gate 6** | Deterministic Fault Scenarios | Each of the **6 fault scenarios** executed **3 consecutive times** (`18` runs):<br>• `DB_SLOW` (`202`, duration ≥ 1400ms, completed)<br>• `BE_ERROR` (`500`, no order, Merchant/Fulfillment spans absent)<br>• `DB_TIMEOUT` (`504`, real JDBC timeout, no publish)<br>• `MERCHANT_TIMEOUT` (`504`, real HTTP client timeout, no publish)<br>• `BROKER_UNAVAILABLE` (`503`, order `PAYMENT_AUTHORIZED_NOT_PUBLISHED`, no consumer)<br>• `FULFILLMENT_ERROR` (`202` sync, no fulfillment row, routed to `commerce.fulfillment.dlq`, consumer error span). |
| **Gate 7** | RabbitMQ Reliability | Exchanges `commerce.orders` and `commerce.orders.dlx` + queues `commerce.fulfillment` and `commerce.fulfillment.dlq` verified `durable=true`; mandatory publisher confirms; manual ACK post-DB-commit; prefetch `1`; poison messages routed to DLQ without infinite loops. |
| **Gate 8** | MySQL Integrity & Isolation | `flyway_schema_history` verified across schemas; cross-schema queries rejected with `OperationalError` for dedicated users (`cart_app`, `checkout_app`, `fulfillment_app`); persistent storage across restarts. |
| **Gate 9** | Automated Jaeger Validation | Programmatic queries via `GET /api/traces/{trace_id}` asserting structure, present/absent services, `error=true`/`otel.status_code=ERROR` tags, and JSON report with `run_id`, `journey_id`, `trace_id`. |
| **Gate 10** | Security & Release Quality | Zero secrets in Git, spans, metrics, or logs; zero `latest` tags; CycloneDX SBOM generated at `/app/sbom.cdx.json`; registry publication blocked unless `e2e-test-report.json` reports `"overall_passed": true`. |
| **Gate 11** | OpenTelemetry Metrics | All **24+ metric families** (4 runtime + custom `commerce.*` business metrics) verified at `otel-collector:8889/metrics` and `metrics.json` (Compose) or **VictoriaMetrics** `/api/v1/label/__name__/values` (Kubernetes). |
| **Gate 12** | OpenTelemetry Logs | Correlated OTLP logs with `trace_id`, `span_id`, `run_id`, `journey_id` verified across services with `ERROR`/`WARN` records on fault scenarios and zero credential leaks, checked in `logs.json` (Compose) or **VictoriaLogs** + **Fluent Bit** `stdout` logs (Kubernetes). |
| **Gate 13** | v2.0 SPA, JWT Auth & Fulfillment Loop | Verifies `GET /` and SPA fallback `GET /login` (`200 OK`), `GET /api/products` (3 demo products), `401` on `/api/carts/active` without JWT, demo login (`demo@otelshop.local`), new user registration (`201`) + duplicate conflict (`409`), `GET /api/auth/me`, authenticated 2-item cart → checkout (`202`) → `GET /api/orders` → `GET /api/fulfillments/{orderId}` reaching `FULFILLED`. |

---

## 3. Reports & Diagnostics

### Docker Compose
After running `make test` or `make e2e-test`, two reports are written:
- `test/reports/e2e-test-summary.txt`: human-readable summary of all gates (`PASS`/`FAIL`).
- `test/reports/e2e-test-report.json`: structured report containing every gate outcome and all 48 journey records (`scenario`, `iteration`, `run_id`, `journey_id`, `trace_id`, `expected_http_status`, `observed_http_status`, `passed`).

### Kubernetes (`kind`)
During `make k8s-test`, logs from Job `commerce-e2e-test-runner` stream to stdout and the command exits `0` only if `job/commerce-e2e-test-runner` reaches `condition=complete`. To re-inspect logs:
```bash
kubectl -n commerce-observability logs job/commerce-e2e-test-runner
```

---

## 4. Pre-Release Checklist

Before publishing a new version to Docker Hub (`DRY_RUN=false ./scripts/publish-images.sh`):
1. Run `make test` (unit tests + build + Compose E2E) → confirm `Overall: PASS` in `test/reports/e2e-test-summary.txt`.
2. Run `make k8s-test` (`prod` & `prod-otel` overlay validation + Kubernetes `kind` `dev` E2E) → confirm `Kubernetes E2E verification PASSED`.
3. Verify `git status` is clean and no `.env` files, backups, or secrets are tracked.
