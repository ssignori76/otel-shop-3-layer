# Quality Gates & Testing Strategy

Questo documento definisce tutti i livelli di test, i **12 Quality Gate** obbligatori, come eseguire le verifiche in modalità containerizzata (sia su Docker Compose sia su Kubernetes `kind`) e come leggere i report generati.

---

## 1. Piramide di Test (100% Containerized)

Nessun tool Java/Maven o Python è richiesto sull'host: tutti i test vengono eseguiti dentro container Docker.

| Livello | Comando | Cosa verifica |
|---|---|---|
| **Unit Test & JaCoCo** | `make unit-test` | Compilazione di tutti i 10 moduli Maven, test JUnit 5 di contratti, state machine, controller REST, listener AMQP, idempotenza, poison-message e coverage JaCoCo (stage `unit-test` in [`docker/java-service/Dockerfile`](../docker/java-service/Dockerfile)). |
| **Image Build & Non-Root Gate** | `make build` | Build multi-stage delle 9 immagini, generazione SBOM CycloneDX (`/app/sbom.cdx.json`) e verifica automatica che nessun container applicativo giri come `UID 0`. |
| **Integration Checks** | `make integration-test` | Avvio stack, verifica reale `SELECT 1` su MySQL 8.4 con utente non-root `cart_app` e diagnostica AMQP su RabbitMQ. |
| **E2E Suite (Docker Compose)** | `make e2e-test` o `make test` | Esecuzione di [`test/end-to-end/run_e2e_suite.py`](../test/end-to-end/run_e2e_suite.py) contro lo stack Compose (`compose.yaml` + `compose.test.yaml`) con verifica di tutti i Gate 3–12 e scrittura report in `test/reports/`. |
| **E2E Suite (Kubernetes `kind`)** | `make k8s-test` | Validazione statica Kustomize dell'overlay `dynatrace-ready` (0 risorse observability locali) + esecuzione del Job `commerce-e2e-test-runner` nel cluster `kind` con verifica di Jaeger, **VictoriaMetrics** (PromQL) e **VictoriaLogs** (OTLP + **Fluent Bit** LogsQL). |

---

## 2. Elenco dei 12 Quality Gate

| Gate | Ambito | Criteri di superamento verificati automaticamente |
|---|---|---|
| **Gate 1** | Clean Containerized Build | Tutti i 10 moduli Maven compilano e passano gli unit test in Docker; tutte le immagini usano tag fissati (zero `latest`) ed eseguono come non-root (`UID 10001` / `UID 82`). |
| **Gate 2** | Stack Startup & Readiness | Tutti i container/pod raggiungono lo stato `healthy`/`Ready` senza restart loop; MySQL esegue `SELECT 1`; RabbitMQ accetta connessioni AMQP; Jaeger e OTel Collector accettano OTLP. |
| **Gate 3** | Scheduling & Route Guard | `journey-generator` configurato con `interval=10s`, `max_in_flight=1`; richieste su rotte sconosciute (`/api/unknown-route-check`) respinte dal Gateway con HTTP `404`. |
| **Gate 4** | Normal-Flow Stability | **30 journey `NORMAL` consecutivi** completati con HTTP `202`, `cart.status=CART_READ`, `orders.status=PUBLISHED`, `fulfillment.status=FULFILLED`, coda `commerce.fulfillment` pulita (`ready=0, unacked=0`). Include test override `SCENARIO_NAME=DB_TIMEOUT` con `SCENARIO_ENABLED=false` → percorso `NORMAL`. |
| **Gate 5** | Distributed Tracing (Spans) | Singolo `traceID` su Jaeger che collega tutti e 6 i servizi (`journey-generator`, `api-gateway`, `cart-service`, `checkout-service`, `merchant-service`, `fulfillment-service`); nessun parent span orfano; presenza span JDBC (`db.system=mysql`) e propagazione W3C su HTTP e AMQP; zero credenziali negli span. |
| **Gate 6** | Deterministic Fault Scenarios | Ciascuno dei **6 scenari di errore** eseguito **3 volte consecutive** (`18` run):<br>• `DB_SLOW` (`202`, durata ≥ 1400ms, completato)<br>• `BE_ERROR` (`500`, nessun ordine, assenza span Merchant/Fulfillment)<br>• `DB_TIMEOUT` (`504`, timeout JDBC reale, nessun publish)<br>• `MERCHANT_TIMEOUT` (`504`, timeout HTTP client reale, nessun publish)<br>• `BROKER_UNAVAILABLE` (`503`, ordine `PAYMENT_AUTHORIZED_NOT_PUBLISHED`, nessun consumer)<br>• `FULFILLMENT_ERROR` (`202` sincrono, nessuna riga fulfillment, messaggio instradato in `commerce.fulfillment.dlq`, span consumer con errore). |
| **Gate 7** | RabbitMQ Reliability | Exchange `commerce.orders` e `commerce.orders.dlx` + code `commerce.fulfillment` e `commerce.fulfillment.dlq` verificati `durable=true`; publisher confirms obbligatori; ACK manuale dopo commit DB; prefetch `1`; poison-message instradati in DLQ senza loop. |
| **Gate 8** | MySQL Integrity & Isolation | `flyway_schema_history` verificata su `cart_db`, `checkout_db`, `fulfillment_db`; query cross-schema respinte con `OperationalError` per tutti e 3 gli utenti dedicati (`cart_app`, `checkout_app`, `fulfillment_app`). |
| **Gate 9** | Automated Jaeger Validation | Interazione programmatica via `GET /api/traces/{trace_id}` con asserzioni su struttura, servizi assenti/presenti, tag `error=true`/`otel.status_code=ERROR` e report JSON con `run_id`, `journey_id`, `trace_id`. |
| **Gate 10** | Security & Release Quality | Zero segreti in Git, span, metriche e log; nessun tag `latest`; SBOM CycloneDX generato in `/app/sbom.cdx.json`; pubblicazione su registry bloccata se `e2e-test-report.json` non riporta `"overall_passed": true`. |
| **Gate 11** | OpenTelemetry Metrics | Tutte le **24 famiglie di metriche** (4 runtime + 20 business `commerce.*`) verificate su `otel-collector:8889/metrics` e su `metrics.json` (Compose) o **VictoriaMetrics** via `/api/v1/label/__name__/values` (Kubernetes). |
| **Gate 12** | OpenTelemetry Logs | Log OTLP correlati con `trace_id`, `span_id`, `run_id`, `journey_id` presenti per tutti i 6 servizi Java con record `ERROR`/`WARN` sugli scenari di errore e zero leak di credenziali, verificati su `logs.json` (Compose) o **VictoriaLogs** + log `stdout` di **Fluent Bit** (Kubernetes). |

---

## 3. Report e Diagnostica

### Docker Compose
Al termine di `make test` o `make e2e-test` vengono generati:
- `test/reports/e2e-test-summary.txt`: riepilogo leggibile di tutti i gate (`PASS`/`FAIL`).
- `test/reports/e2e-test-report.json`: report strutturato contenente esito di ogni gate e l'elenco completo dei 48 journey (`scenario`, `iteration`, `run_id`, `journey_id`, `trace_id`, `expected_http_status`, `observed_http_status`, `passed`).

### Kubernetes (`kind`)
Al termine di `make k8s-test` i log del Job `commerce-e2e-test-runner` vengono stampati in streaming sullo stdout e il comando termina con exit code `0` solo se `job/commerce-e2e-test-runner` raggiunge `condition=complete`. Per ispezionare nuovamente l'output:
```bash
kubectl -n commerce-observability logs job/commerce-e2e-test-runner
```

---

## 4. Checklist Pre-Release

Prima di pubblicare una nuova versione su Docker Hub (`DRY_RUN=false ./scripts/publish-images.sh`):
1. Eseguire `make test` (unit test + build + E2E Docker Compose) → verificare `Overall: PASS` in `test/reports/e2e-test-summary.txt`.
2. Eseguire `make k8s-test` (validazione overlay `dynatrace-ready` + E2E Kubernetes `kind`) → verificare `Kubernetes E2E verification PASSED`.
3. Verificare `git status` pulito e assenza di file `.env` o credenziali nei file tracciati.
