# Kubernetes Deployment & Observability Guide

This guide describes the multi-namespace Kubernetes architecture of the project, the 3 unified Kustomize overlays (`dev`, `prod`, and `prod-otel`), persistent MySQL storage, the local `kind` workflow, and deployment to remote clusters monitored by Dynatrace.

---

## 1. Multi-Namespace Architecture

The application is split across **3 application namespaces** (plus an optional 4th namespace dedicated to local observability):

| Namespace | Role / Layer | Workloads | Ports / Service FQDN |
|---|---|---|---|
| `commerce-edge` | Edge / Frontend | `apache-web` (`Deployment` + v2.0 SPA), `api-gateway` (`Deployment`), `journey-generator` (`Deployment`) | `apache-web.commerce-edge.svc.cluster.local:80` (NodePort `30080` → host `18080`)<br>`api-gateway.commerce-edge.svc.cluster.local:8080`<br>`journey-generator.commerce-edge.svc.cluster.local:8085` |
| `commerce-core` | Backend / Microservices | `auth-service`, `cart-service`, `checkout-service`, `merchant-service`, `fulfillment-service` (`Deployment`) | `auth-service.commerce-core.svc.cluster.local:8086`<br>`cart-service.commerce-core.svc.cluster.local:8081`<br>`checkout-service.commerce-core.svc.cluster.local:8082`<br>`merchant-service.commerce-core.svc.cluster.local:8083`<br>`fulfillment-service.commerce-core.svc.cluster.local:8084` |
| `commerce-data` | Data & Messaging | `mysql` (`StatefulSet`, MySQL 8.4.3 + `5Gi` PVC), `rabbitmq` (`StatefulSet`, RabbitMQ 3.13.7) | `mysql.commerce-data.svc.cluster.local:3306`<br>`rabbitmq.commerce-data.svc.cluster.local:5672` / `:15672` |
| `commerce-observability` | Local Observability *(only `dev` overlay)* | `otel-collector`, `jaeger`, `victoria-metrics`, `victoria-logs`, `fluent-bit` (`DaemonSet`) | `otel-collector.commerce-observability.svc.cluster.local:4317` / `:4318` / `:8889`<br>`jaeger:16686` (host `16686`)<br>`victoria-metrics:8428` (host `18428`)<br>`victoria-logs:9428` (host `19428`) |

### Kubernetes Best Practices Applied in `k8s/base/`
- **Persistent MySQL Storage**: the `mysql` `StatefulSet` uses `volumeClaimTemplates` (`PersistentVolumeClaim` `data-mysql-0`, `5Gi`, `ReadWriteOnce`) so users, carts, orders, and fulfillments survive pod restarts and redeployments.
- **Non-Root & Pod Security**: `runAsNonRoot: true`, `allowPrivilegeEscalation: false`, `capabilities.drop: ["ALL"]`, `seccompProfile.type: RuntimeDefault`. `apache-web` runs as `UID 82` on unprivileged port `8080` (exposed on the Service as port `80`).
- **Complete Probes**: `startupProbe`, `readinessProbe` (`/actuator/health/readiness`, `/healthz`, or `SELECT 1`), and `livenessProbe` on every workload.
- **Resource Requests & Limits**: explicit sizing (`1Gi` RAM and `2` CPU limits on Java services to avoid CFS throttling or non-heap OOMKills with the OpenTelemetry Java Agent).
- **Graceful Shutdown**: `terminationGracePeriodSeconds: 30` aligned with Spring Boot `server.shutdown: graceful`.
- **Separated ConfigMaps & Secrets**: credentials and `JWT_SECRET` live exclusively in Kubernetes `Secret` objects (`mysql-credentials`, `commerce-core-secrets`, `commerce-edge-secrets`).

---

## 2. Kustomize Overlays (3 Unified Modes)

### 2.1 Mode 1 (`dev`) — `k8s/overlays/dev` (Local `kind` with Local Observability Stack)

Includes `k8s/base` + the `commerce-observability` namespace with a lightweight open-source stack (< 300 MB total RAM):
- **OpenTelemetry Collector** (`commerce/otel-collector:2.0.0`): receives OTLP/gRPC (`:4317`) from all 7 Java services and routes:
  - **Traces** → Jaeger (`jaeger.commerce-observability.svc.cluster.local:4317`)
  - **Metrics** → VictoriaMetrics (`http://victoria-metrics:8428/opentelemetry/v1/metrics`) + Prometheus scrape (`:8889/metrics`)
  - **Logs** → VictoriaLogs (`http://victoria-logs:9428/insert/opentelemetry/v1/logs`)
- **Fluent Bit** (`DaemonSet`, `cr.fluentbit.io/fluent/fluent-bit:3.1.9`): collects JSON `stdout` logs from all containers in `commerce-edge`, `commerce-core`, and `commerce-data` (including Apache) and ships them via HTTP JSONLine to **VictoriaLogs**.

```bash
make k8s-up-dev
```
- **Frontend E-Commerce SPA**: `http://localhost:18080/`
- **Jaeger UI (Traces)**: `http://localhost:16686`
- **VictoriaMetrics UI (Metrics)**: `http://localhost:18428/vmui`
- **VictoriaLogs UI (Logs)**: `http://localhost:19428/select/vmui`

### 2.2 Mode 2 (`prod`) — `k8s/overlays/prod` (Dynatrace OneAgent + RUM, All OTel Exporters OFF)

Includes **only** the 3 application namespaces (`commerce-edge`, `commerce-core`, `commerce-data`). Excludes all local observability components.
- Pulls published Docker Hub images (`docker.io/lusig76/otel-shop-*:2.0.0`).
- Sets `JAVA_TOOL_OPTIONS=""` and `OTEL_*_EXPORTER=none` on all 7 Java services so Dynatrace Operator / OneAgent instruments the JVMs, collects container `stdout` JSON logs, and auto-injects RUM into `apache-web` without dual-agent conflicts.

```bash
make k8s-render-prod
kubectl apply -k k8s/overlays/prod
```

### 2.3 Mode 3 (`prod-otel`) — `k8s/overlays/prod-otel` (Official Dynatrace OTel Collector)

Includes **only** the 3 application namespaces (`commerce-edge`, `commerce-core`, `commerce-data`) plus the official **Dynatrace OpenTelemetry Collector** (`ghcr.io/dynatrace/dynatrace-otel-collector/dynatrace-otel-collector:0.60.0`) deployed in `commerce-core`:
- Configures `cumulative_to_delta` (`max_staleness: 25h`) on the `metrics` pipeline (required by **Dynatrace Grail**) and `OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE=delta` in `commerce-core-config` / `commerce-edge-config`.
- Authenticates via Kubernetes `Secret` `dynatrace-otlp-credentials` supporting both `DT_AUTH_SCHEME=Api-Token` (Classic API Token) and `DT_AUTH_SCHEME=Bearer` (Platform Token).
- Structured JSON logs remain unconditionally active on container `stdout`.

```bash
make k8s-render-prod-otel
kubectl apply -k k8s/overlays/prod-otel
```

---

## 3. `kind` Cluster Management & Zero-Pollution Tooling

The script [`scripts/k8s-ensure-tools.sh`](../scripts/k8s-ensure-tools.sh) installs `kind` (`v0.25.0`) and `kubectl` (`v1.31.3`, with SHA-256 verification) exclusively into `~/.local/bin`, without `root` privileges or system packages.

| Command | Operation |
|---|---|
| `make k8s-up-dev` | Creates the `kind` cluster (`commerce-synthetic`), loads all 10 local `2.0.0` images, and applies `k8s/overlays/dev` |
| `make k8s-test` | Statically validates `prod` and `prod-otel` overlays and runs the E2E Job `commerce-e2e-test-runner` on `kind` (`dev`) |
| `make k8s-up-prod` | Applies the `k8s/overlays/prod` overlay (OneAgent + RUM) to the current cluster |
| `make k8s-up-prod-otel` | Applies the `k8s/overlays/prod-otel` overlay (Dynatrace OTel Collector) to the current cluster |
| `make k8s-down` | Deletes the `commerce-synthetic` `kind` cluster |
