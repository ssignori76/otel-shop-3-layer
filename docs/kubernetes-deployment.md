# Kubernetes Deployment & Observability Guide

Questa guida descrive l'architettura Kubernetes multi-namespace del progetto, i due overlay Kustomize (`local-observability` e `dynatrace-ready`), il workflow con `kind` in locale e il deployment su cluster remoti monitorati da Dynatrace.

---

## 1. Architettura Multi-Namespace

Il sistema applicativo è suddiviso in **3 namespace applicativi** (più un 4° namespace opzionale dedicato all'osservabilità locale):

| Namespace | Ruolo / Layer | Workload | Porte / Service FQDN |
|---|---|---|---|
| `commerce-edge` | Edge / Frontend | `apache-web` (`Deployment`), `api-gateway` (`Deployment`), `journey-generator` (`Deployment`) | `apache-web.commerce-edge.svc.cluster.local:80` (NodePort `30080` → host `18080`)<br>`api-gateway.commerce-edge.svc.cluster.local:8080`<br>`journey-generator.commerce-edge.svc.cluster.local:8085` |
| `commerce-core` | Backend / Microservizi | `cart-service`, `checkout-service`, `merchant-service`, `fulfillment-service` (`Deployment`) | `cart-service.commerce-core.svc.cluster.local:8081`<br>`checkout-service.commerce-core.svc.cluster.local:8082`<br>`merchant-service.commerce-core.svc.cluster.local:8083`<br>`fulfillment-service.commerce-core.svc.cluster.local:8084` |
| `commerce-data` | Dati & Messaging | `mysql` (`StatefulSet`, MySQL 8.4.3), `rabbitmq` (`StatefulSet`, RabbitMQ 3.13.7) | `mysql.commerce-data.svc.cluster.local:3306`<br>`rabbitmq.commerce-data.svc.cluster.local:5672` / `:15672` |
| `commerce-observability` | Osservabilità locale *(solo overlay `local-observability`)* | `otel-collector`, `jaeger`, `victoria-metrics`, `victoria-logs`, `fluent-bit` (`DaemonSet`) | `otel-collector.commerce-observability.svc.cluster.local:4317` / `:4318` / `:8889`<br>`jaeger:16686` (host `16686`)<br>`victoria-metrics:8428` (host `18428`)<br>`victoria-logs:9428` (host `19428`) |

### Best Practice Kubernetes applicate nei manifest (`k8s/base/`)
- **Non-root & Pod Security**: `runAsNonRoot: true`, `allowPrivilegeEscalation: false`, `capabilities.drop: ["ALL"]`, `seccompProfile.type: RuntimeDefault`. `apache-web` gira con `UID 82` su porta non privilegiata `8080` (esposta sul Service come porta `80`).
- **Probe complete**: `startupProbe`, `readinessProbe` (`/actuator/health/readiness` o `/healthz` o `SELECT 1`) e `livenessProbe` su ogni workload.
- **Resource Requests & Limits**: dimensionamento esplicito (`1Gi` RAM e `2` CPU limit sui servizi Java per evitare throttling CFS o OOMKill non-heap con l'OpenTelemetry Java Agent).
- **Graceful Shutdown**: `terminationGracePeriodSeconds: 30` allineato a `server.shutdown: graceful` di Spring Boot.
- **ConfigMap e Secret separati**: nessuna credenziale nei file di configurazione o nei manifest di Deployment.

---

## 2. Overlay Kustomize

### 2.1 `k8s/overlays/local-observability` (Sviluppo e test locale su `kind`)

Include `k8s/base` + il namespace `commerce-observability` con uno stack open-source leggero (< 300 MB RAM totali):
- **OpenTelemetry Collector** (`commerce/otel-collector:1.0.0`): riceve OTLP/gRPC (`:4317`) dai 6 servizi Java e smista:
  - **Traces** → Jaeger (`jaeger.commerce-observability.svc.cluster.local:4317`)
  - **Metrics** → VictoriaMetrics (`http://victoria-metrics:8428/opentelemetry/v1/metrics`) + Prometheus scrape (`:8889/metrics`)
  - **Logs** → VictoriaLogs (`http://victoria-logs:9428/insert/opentelemetry/v1/logs`)
- **Fluent Bit** (`DaemonSet`, `cr.fluentbit.io/fluent/fluent-bit:3.1.9`): raccoglie i log `stdout` JSON di tutti i container in `commerce-edge`, `commerce-core`, `commerce-data` (incluso Apache) e li invia via HTTP JSONLine a **VictoriaLogs**.

**Avvio e UI locali (`kind`)**:
```bash
make k8s-up
```
- **Apache Entry Point**: `http://localhost:18080/healthz`
- **Jaeger UI (Traces)**: `http://localhost:16686`
- **VictoriaMetrics UI (Metrics)**: `http://localhost:18428/vmui`
- **VictoriaLogs UI (Logs)**: `http://localhost:19428/select/vmui`

### 2.2 `k8s/overlays/dynatrace-ready` (Cluster remoto con Dynatrace)

Include **solo** i 3 namespace applicativi (`commerce-edge`, `commerce-core`, `commerce-data`). Non deploya `commerce-observability`, Jaeger, VictoriaMetrics, VictoriaLogs né Fluent Bit.
- **Immagini pubbliche Docker Hub**: usa il transformer Kustomize `images:` per riscrivere automaticamente le immagini verso `docker.io/lusig76/otel-shop-*:1.0.0`.
- **Modalità A (Default — Dynatrace OneAgent)**: imposta `JAVA_TOOL_OPTIONS=""` sui 6 servizi Java per disabilitare l'OTel Java Agent integrato ed evitare conflitti da doppio agent sulla JVM quando Dynatrace Operator / OneAgent è attivo sul cluster.
- **Modalità B (Alternativa — Dynatrace OTLP Ingest)**: descritta nei commenti di [`k8s/overlays/dynatrace-ready/patches/disable-local-otel-agent.yaml`](../k8s/overlays/dynatrace-ready/patches/disable-local-otel-agent.yaml), consente di mantenere l'OTel Java Agent puntandolo all'endpoint OTLP Dynatrace.

**Comandi per cluster remoto**:
```bash
# Verifica anteprima manifest generati
make k8s-render-dynatrace

# Applicazione su cluster Kubernetes remoto (con contesto kubectl configurato)
kubectl apply -k k8s/overlays/dynatrace-ready
```

---

## 3. Gestione del Cluster `kind` e Tooling Zero-Pollution

Lo script [`scripts/k8s-ensure-tools.sh`](../scripts/k8s-ensure-tools.sh) installa `kind` (`v0.25.0`) e `kubectl` (`v1.31.3`, con verifica checksum SHA-256) esclusivamente in `~/.local/bin`, senza permessi `root` né pacchetti di sistema.

| Comando | Operazione |
|---|---|
| `make k8s-up` | Crea cluster `kind` (`commerce-synthetic`), carica le immagini locali e applica `local-observability` |
| `make k8s-test` | Valida staticamente `dynatrace-ready` ed esegue il Job E2E `commerce-e2e-test-runner` su `kind` |
| `make k8s-up-dynatrace` | Applica l'overlay `dynatrace-ready` sul cluster corrente |
| `make k8s-down` | Elimina completamente il cluster `kind` `commerce-synthetic` |
