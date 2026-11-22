# Commerce Synthetic Application (`otel-shop-3-layer`)

Applicazione e-commerce distribuita sintetica a **3 livelli** progettata per generare e validare in modo deterministico **Tracce distribuite (Span)**, **Metriche** e **Log** OpenTelemetry attraverso chiamate reali **HTTP**, **JDBC (MySQL)** e **AMQP (RabbitMQ)**.

Il progetto funziona in modo identico su **Docker Compose** e su **Kubernetes** (`kind` in locale o cluster remoti monitorati da **Dynatrace**), con sviluppo, build e test eseguiti **interamente in container**.

---

## 1. Scopo del Progetto

- Esercitare un flusso e-commerce end-to-end reale attraverso più processi indipendenti (Web Tier → API Gateway → Microservizi → MySQL → RabbitMQ → Consumer asincrono).
- Propagare nativamente il **W3C Trace Context** (`traceparent`, `tracestate`, `baggage`) e gli header di correlazione business (`x-run-id`, `x-journey-id`) su tutti i confini HTTP e AMQP.
- Generare traffico continuo controllato (via `journey-generator`) e iniettare **6 scenari di errore deterministici** per validare l'osservabilità in condizioni normali e degradate.
- Verificare automaticamente **Span, Metriche e Log** su uno stack open-source locale leggero (**Jaeger**, **VictoriaMetrics**, **VictoriaLogs**, **Fluent Bit**, **OpenTelemetry Collector**) ed essere pronto per il deployment pulito su cluster Kubernetes con **Dynatrace**.

---

## 2. Architettura e Struttura

```text
Journey Generator (Java 21, fixed-delay 10s, :8085)
        |
        | HTTP  POST /api/journeys
        v
Apache HTTP Server (httpd:2.4.62-alpine, :80 / host :18080)
        |
        | reverse proxy /api/* (preserva W3C traceparent + x-run-id + x-journey-id)
        v
Spring Cloud Gateway (api-gateway, WebFlux, :8080)
        |
        +-----------------------------------+
        |                                   |
        v                                   v
  Cart Service                       Checkout Service
  (:8081, MVC)                       (:8082, MVC)
        |                             |      |      |
        | JDBC                        | JDBC | HTTP | AMQP (Publisher Confirms)
        v                             v      v      v
     MySQL                         MySQL  Merchant RabbitMQ (commerce.orders)
   (cart_db)                   (checkout_db) (:8083)         |
                                                             | AMQP (Manual ACK post-commit)
                                                             v
                                                    Fulfillment Service
                                                      (:8084, MVC+AMQP)
                                                             |
                                                             | JDBC
                                                             v
                                                           MySQL
                                                      (fulfillment_db)
```

### Componenti e Layer

| Layer (K8s Namespace) | Componente | Tecnologia | Responsabilità |
|---|---|---|---|
| **Edge** (`commerce-edge`) | `apache-web` | Apache `httpd:2.4.62-alpine` (`UID 82`) | Entry point pubblico, `/healthz`, reverse proxy `/api/*` |
| **Edge** (`commerce-edge`) | `api-gateway` | Java 21, Spring Cloud Gateway | Orchestrazione `POST /api/journeys`, rotte statiche, propagazione header |
| **Edge** (`commerce-edge`) | `journey-generator` | Java 21, Spring Boot | Simulatore traffico continuo (10s, `max_in_flight=1`) + API trigger |
| **Core** (`commerce-core`) | `cart-service` | Java 21, Spring MVC + JDBC + Flyway | Creazione e lettura carrello su `cart_db` |
| **Core** (`commerce-core`) | `checkout-service` | Java 21, Spring MVC + JDBC + AMQP | Orchestrazione checkout, ordine su `checkout_db`, publish RabbitMQ confermato |
| **Core** (`commerce-core`) | `merchant-service` | Java 21, Spring MVC | Simulazione autorizzazione pagamento |
| **Core** (`commerce-core`) | `fulfillment-service` | Java 21, Spring MVC + AMQP + JDBC | Consumer AMQP (prefetch 1, ACK manuale dopo commit su `fulfillment_db`) |
| **Data** (`commerce-data`) | `mysql` | MySQL `8.4.3` (`utf8mb4_0900_ai_ci`, UTC) | 3 schemi e 3 utenti isolati (`cart_db`, `checkout_db`, `fulfillment_db`) |
| **Data** (`commerce-data`) | `rabbitmq` | RabbitMQ `3.13.7-management-alpine` | Exchange `commerce.orders`, coda `commerce.fulfillment`, DLX/DLQ durevoli |
| **Observability** (`commerce-observability`) | `otel-collector`, `jaeger`, `victoria-metrics`, `victoria-logs`, `fluent-bit` | Stack OTel open-source leggero *(solo test/locale)* | Raccolta e visualizzazione di Traces, Metrics e Logs (OTLP + container stdout) |

---

## 3. Scenari Previsti

Il flusso canonico attraversa gli stati `STARTED → PAGE_LOADED → CART_CREATED → CART_READ → PAYMENT_AUTHORIZED → ORDER_PUBLISHED → FULFILLED`.  
Con `SCENARIO_ENABLED=false` il sistema esegue sempre il flusso `NORMAL` anche se è configurato un altro `SCENARIO_NAME`.

| Scenario | Punto di iniezione | HTTP | Effetto su DB / RabbitMQ | Telemetria attesa |
|---|---|---:|---|---|
| `NORMAL` | Nessuno | `202` | Carrello `CART_READ`, Ordine `PUBLISHED`, Fulfillment `FULFILLED` | Singola traccia a 6 servizi, metriche completamento, log `INFO` |
| `DB_SLOW` | MySQL in Checkout | `202` | Query reale `SELECT SLEEP(1.5)` su `checkout_db`; flusso completato | Span JDBC lento visibile su Jaeger; completamento normale |
| `BE_ERROR` | Checkout pre-DB | `500` | Errore prima del DB; nessun ordine, nessuna chiamata Merchant/RabbitMQ | Span di errore su Checkout/Gateway, `error_code=BE_ERROR`, log `ERROR` |
| `DB_TIMEOUT` | MySQL in Checkout | `504` | Timeout JDBC reale (`SQLTimeoutException`); nessun publish AMQP | Span JDBC con timeout, `error_code=DB_TIMEOUT`, log `ERROR` |
| `MERCHANT_TIMEOUT` | Chiamata HTTP Merchant | `504` | Timeout client HTTP reale; nessun ordine né publish AMQP | Span HTTP client in timeout, `error_code=MERCHANT_TIMEOUT`, log `ERROR` |
| `BROKER_UNAVAILABLE` | Publish RabbitMQ | `503` | Ordine salvato `PAYMENT_AUTHORIZED_NOT_PUBLISHED`; nessun consumer | Span errore publish, `error_code=BROKER_UNAVAILABLE`, log `ERROR` |
| `FULFILLMENT_ERROR` | Consumer Fulfillment | `202` | Ordine `PUBLISHED`, consumer rifiuta (`requeue=false`) verso DLQ `commerce.fulfillment.dlq`, nessuna riga fulfillment | Span consumer con errore su Jaeger, `commerce.fulfillments.failed`, log `ERROR` |

---

## 4. Osservabilità: Span, Metriche e Log

Tutti i 6 servizi Java integrano l'**OpenTelemetry Java Agent `v2.10.0`** più la libreria condivisa `scenario-contract` ([`TelemetryHelper.java`](libs/scenario-contract/src/main/java/com/commerce/synthetic/scenario/TelemetryHelper.java)):

- **Traces (Spans)**: auto-strumentazione HTTP, JDBC e Spring AMQP arricchita con attributi business (`commerce.run_id`, `commerce.journey_id`, `commerce.scenario`, `commerce.journey.state`, `commerce.cart_id`, `commerce.order_id`, `commerce.fulfillment_id`).
- **Metrics**: 4 famiglie runtime automatiche (`http.server.request.duration`, `http.client.request.duration`, `jvm.memory.used`, `db.client.connections.usage`) + **20 metriche custom di business** (`commerce.journeys.*`, `commerce.gateway.*`, `commerce.carts.*`, `commerce.checkouts.*`, `commerce.orders.published`, `commerce.payments.*`, `commerce.fulfillments.*`).
- **Logs**: doppio canale — **JSON strutturato su `stdout`** (incluso Apache, raccolto in K8s da **Fluent Bit**) + **OTLP LogRecords** con `trace_id`, `span_id` e attributi MDC (`run_id`, `journey_id`, `scenario`, `journey_state`).

Per il catalogo completo di metriche, attributi e formati log vedere [`docs/metrics-and-logs-specification.md`](docs/metrics-and-logs-specification.md).

---

## 5. Come si Utilizza

Il progetto supporta **due modalità operative nettamente separate**: un ambiente locale di sviluppo/test autosufficiente con stack di osservabilità open-source, e un ambiente reale pensato per cluster monitorati da **Dynatrace**.

**Prerequisiti sull'host**: solo **Docker Engine**, **Docker Compose** e **Git** (nessun JDK o Maven locale richiesto).

---

### Parte A — Ambiente di Sviluppo e Test (con Stack di Observability Locale)

Include lo stack di osservabilità open-source locale per ispezionare e verificare automaticamente **Span (Jaeger)**, **Metriche (VictoriaMetrics / Prometheus)** e **Log (VictoriaLogs + Fluent Bit / OTLP)** senza dipendenze esterne.

#### 1. Avvio e Test su Docker Compose
```bash
make test       # Esegue unit test, build immagini non-root e suite E2E completa (Gate 1–12)
make up         # Avvia lo stack applicativo + Jaeger + OTel Collector
make down       # Ferma e rimuove container e volumi effimeri
```

#### 2. Avvio e Test su Kubernetes Locale (`kind`)
Installa automaticamente `kind` e `kubectl` in `~/.local/bin` (zero modifiche di sistema), crea il cluster `commerce-synthetic` con **4 namespace** (`commerce-edge`, `commerce-core`, `commerce-data`, `commerce-observability`), carica le immagini locali ed esegue tutti i test E2E dentro il cluster:
```bash
make build      # Costruisce le immagini locali (se non già presenti)
make k8s-up     # Avvia kind con overlay k8s/overlays/local-observability (4 namespace)
make k8s-test   # Valida overlay dynatrace-ready ed esegue il Job E2E su kind
make k8s-down   # Elimina il cluster kind
```

**Endpoint e UI locali esposti dal cluster `kind`**:
- **Apache Entry Point**: `http://localhost:18080/healthz`
- **Jaeger UI (Traces)**: `http://localhost:16686`
- **VictoriaMetrics UI (Metrics)**: `http://localhost:18428/vmui`
- **VictoriaLogs UI (Logs)**: `http://localhost:19428/select/vmui`

---

### Parte B — Ambiente Reale con Dynatrace (senza Stack di Observability Locale)

Pensato per l'installazione su un **cluster Kubernetes reale** (o host Docker) già monitorato da **Dynatrace**, escludendo completamente Jaeger, VictoriaMetrics, VictoriaLogs, Fluent Bit e il namespace `commerce-observability`.

#### 1. Deploy su Cluster Kubernetes con Dynatrace
L'overlay [`k8s/overlays/dynatrace-ready`](k8s/overlays/dynatrace-ready/kustomization.yaml):
- Deploya **esclusivamente** i 3 namespace applicativi: `commerce-edge`, `commerce-core`, `commerce-data`.
- Scarica direttamente da Docker Hub le immagini pubblicate (`docker.io/lusig76/otel-shop-*:1.0.0`), senza bisogno di build locale o `kind load`.
- **Modalità A (Default — Dynatrace OneAgent)**: disabilita l'OTel Java Agent integrato (`JAVA_TOOL_OPTIONS=""`) evitando sovrapposizioni sulla JVM con Dynatrace Operator / OneAgent.
- **Modalità B (Alternativa — Dynatrace OTLP Ingest)**: consente di mantenere l'OTel Java Agent inviando Traces, Metrics e Logs direttamente all'endpoint OTLP Dynatrace (vedi [`docs/kubernetes-deployment.md`](docs/kubernetes-deployment.md)).

```bash
# Anteprima dei manifest generati (verifica assenza risorse observability locali)
make k8s-render-dynatrace

# Applicazione sul cluster Kubernetes target
kubectl apply -k k8s/overlays/dynatrace-ready
```

#### 2. Deploy su Docker Compose con Dynatrace OTLP
Utilizza [`compose.dynatrace.yaml`](compose.dynatrace.yaml) e [`observability/dynatrace/otel-collector-dynatrace.yaml`](observability/dynatrace/otel-collector-dynatrace.yaml) (con processore `cumulativetodelta` per le metriche):
```bash
export DT_OTLP_ENDPOINT="https://<environment-id>.live.dynatrace.com/api/v2/otlp"
export DT_API_TOKEN="<dynatrace-ingest-token>"
./scripts/compose-up.sh dynatrace
```

#### Immagini Pubblicate su Docker Hub (`lusig76`)
Tutte le immagini usate in ambiente reale sono pubblicate con tag semantico (`1.0.0`) e short Git SHA (mai `latest`):
`lusig76/otel-shop-apache-web`, `lusig76/otel-shop-api-gateway`, `lusig76/otel-shop-cart-service`, `lusig76/otel-shop-checkout-service`, `lusig76/otel-shop-merchant-service`, `lusig76/otel-shop-fulfillment-service`, `lusig76/otel-shop-journey-generator`, `lusig76/otel-shop-otel-collector`, `lusig76/otel-shop-test-runner`.

---

## 6. Note di Sviluppo e Qualità

- **Container-only & Zero-Host-Pollution**: compilazione Maven multi-modulo, test JUnit 5, coverage JaCoCo e generazione SBOM CycloneDX (`/app/sbom.cdx.json`) avvengono nel Dockerfile multi-stage condiviso [`docker/java-service/Dockerfile`](docker/java-service/Dockerfile).
- **Sicurezza by-design**: immagini base version-pinned, esecuzione non-root (`UID 10001` per Java/OTel/Test-runner, `UID 82` per Apache), utenti MySQL isolati per schema, nessuna credenziale tracciata in Git o esposta in span/log.
- **Qualità verificata**: 12 Quality Gate automatizzati che coprono stabilità su 30 journey `NORMAL`, 6x3 scenari di fault injection, integrità DB/RabbitMQ, tracce Jaeger, 24 famiglie di metriche e log correlati.

---

## 7. Indice Documentazione & Mappa per Agenti Coder

Questo `README.md` è l'indice principale del repository. Per dettagli specialistici o attività di sviluppo/evoluzione consultare i documenti dedicati in [`docs/`](docs/):

| Documento | Contenuto principale | Quando leggerlo (umani & agenti coder) |
|---|---|---|
| [`docs/definitive-specification.md`](docs/definitive-specification.md) | Specifica normativa baseline: architettura, contratti HTTP/AMQP/DB, state machine, scenari e Gate 1–10. *(Copia canonica della specifica in root)* | Prima di modificare contratti REST/AMQP, schemi DB o semantica degli scenari. |
| [`docs/metrics-and-logs-specification.md`](docs/metrics-and-logs-specification.md) | Estensione normativa per OpenTelemetry Metrics (24 famiglie) e Logs (stdout JSON + OTLP + MDC) e Gate 11–12. | Prima di aggiungere/modificare metriche custom, campi MDC o pipeline Collector. |
| [`docs/kubernetes-deployment.md`](docs/kubernetes-deployment.md) | Architettura K8s a 4 namespace, best practice manifest, `kind`, overlay `local-observability` vs `dynatrace-ready` (Modalità A/B). | Per deploy su `kind` o su cluster Kubernetes remoto con Dynatrace. |
| [`docs/quality-and-testing.md`](docs/quality-and-testing.md) | Piramide di test containerizzata, definizione completa dei **12 Quality Gate**, esecuzione test unit/integration/E2E e lettura report. | Per eseguire verifiche di qualità, diagnosticare fallimenti o estendere la suite di test. |
| [`docs/development-and-maintenance.md`](docs/development-and-maintenance.md) | Come evolvere e mantenere il monorepo (nuovi scenari, metriche, migrazioni Flyway, rilascio immagini Docker Hub) e regole invarianti per agenti coder. | Prima di qualsiasi evoluzione del codice o rilascio di nuova versione. |
