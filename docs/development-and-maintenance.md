# Development, Evolution & Maintenance Guide

Questa guida descrive le convenzioni tecniche, i workflow di manutenzione e le procedure per evolvere il progetto in modo sicuro e coerente (pensata sia per sviluppatori sia per **agenti AI coder**).

---

## 1. Struttura del Monorepo

```text
otel-shop-3-layer/
├── pom.xml                          # Maven Parent (Java 21, Spring Boot 3.3.5, Spring Cloud 2023.0.3)
├── libs/
│   ├── scenario-contract/           # Enum scenari, JourneyState, CorrelationHeaders, TelemetryHelper
│   ├── api-contract/                # DTO REST validati con Jakarta Bean Validation + ApiErrorResponse
│   └── messaging-contract/          # Costanti RabbitMQ + contratto OrderCreatedMessage
├── services/
│   ├── api-gateway/                 # Spring Cloud Gateway (WebFlux, :8080)
│   ├── cart-service/                # Spring MVC + JDBC + Flyway (cart_db, :8081)
│   ├── checkout-service/            # Spring MVC + JDBC + Flyway + AMQP (checkout_db, :8082)
│   ├── merchant-service/            # Spring MVC (:8083)
│   └── fulfillment-service/         # Spring MVC + AMQP manual ACK + JDBC + Flyway (fulfillment_db, :8084)
├── journey-generator/               # Fixed-delay traffic generator + API trigger (:8085)
├── database/                        # Migrazioni SQL di riferimento + script init utenti/schemi
├── rabbitmq/                        # rabbitmq.conf + definitions.json (topology as code)
├── observability/                   # Config OTel Collector (locale e Dynatrace)
├── docker/                          # Dockerfile multi-stage Java, Apache, OTel Collector
├── compose*.yaml                    # Docker Compose (base, test, dynatrace)
├── k8s/                             # Manifest Kubernetes Kustomize (base + overlays)
├── test/                            # Container test-runner + suite E2E Python + scenari JSON
├── scripts/                         # Script operativi (build, test, compose, k8s, publish)
└── docs/                            # Documentazione normativa e operativa
```

---

## 2. Come Evolvere il Progetto

### 2.1 Aggiungere un nuovo Scenario di Errore
1. Aggiungere il valore in [`ScenarioName.java`](../libs/scenario-contract/src/main/java/com/commerce/synthetic/scenario/ScenarioName.java) e, se necessario, il nuovo stato terminale in [`JourneyState.java`](../libs/scenario-contract/src/main/java/com/commerce/synthetic/scenario/JourneyState.java).
2. Implementare il punto di iniezione **solo** nel microservizio proprietario usando `ctx.isScenarioActive(ScenarioName.NUOVO_SCENARIO)` (garantisce il rispetto di `SCENARIO_ENABLED=false`).
3. Registrare l'errore sullo span e nei log/metriche tramite [`TelemetryHelper.recordError(...)`](../libs/scenario-contract/src/main/java/com/commerce/synthetic/scenario/TelemetryHelper.java) e mappare l'eccezione in [`ApiErrorResponse`](../libs/api-contract/src/main/java/com/commerce/synthetic/api/ApiErrorResponse.java).
4. Aggiungere unit test nel servizio interessato e aggiungere la definizione dello scenario in [`test/scenarios/scenarios.json`](../test/scenarios/scenarios.json) e [`test/end-to-end/run_e2e_suite.py`](../test/end-to-end/run_e2e_suite.py).

### 2.2 Aggiungere una nuova Metrica o un nuovo Attributo di Log
- **Metriche**: usare [`TelemetryHelper.incrementCounter(...)`](../libs/scenario-contract/src/main/java/com/commerce/synthetic/scenario/TelemetryHelper.java) o `TelemetryHelper.recordHistogram(...)` con prefisso `commerce.<dominio>.<nome>`. Non creare un `OpenTelemetrySdk` separato (il `GlobalOpenTelemetry` si collega automaticamente all'OpenTelemetry Java Agent).
- **Log**: popolare i campi di correlazione via `MDC` (in [`TelemetryHelper`](../libs/scenario-contract/src/main/java/com/commerce/synthetic/scenario/TelemetryHelper.java)), aggiornare se necessario il pattern JSON in `logback-spring.xml` del servizio e pulire sempre l'MDC in un blocco `finally` (`TelemetryHelper.clearMdc()`).
- **Aggiornare le verifiche**: aggiungere il nome della metrica in `required_metric_Families` dentro [`test/end-to-end/run_e2e_suite.py`](../test/end-to-end/run_e2e_suite.py) e documentarla in [`docs/metrics-and-logs-specification.md`](metrics-and-logs-specification.md).

### 2.3 Aggiungere una Migrazione Database (Flyway)
- Creare un nuovo file versionato `V2__<descrizione>.sql` (non modificare mai una migrazione `V1__*` già applicata: Flyway fallirebbe per checksum mismatch, come richiesto dal Gate 8).
- Mantenere allineati i file in `database/<servizio>/migrations/` e in `services/<servizio>/src/main/resources/db/migration/`.
- Rispettare sempre la separazione degli schemi: nessuna query cross-schema tra `cart_db`, `checkout_db` e `fulfillment_db`.

### 2.4 Rilasciare una nuova Versione delle Immagini su Docker Hub
1. Aggiornare `<version>` nel [`pom.xml`](../pom.xml) parent e nei POM figli (es. `1.1.0`), oppure passare `APP_VERSION=1.1.0`.
2. Eseguire `make test` e `make k8s-test` per rigenerare i report con `"overall_passed": true`.
3. Pubblicare su Docker Hub (richiede `docker login`):
   ```bash
   APP_VERSION=1.1.0 DRY_RUN=false ./scripts/publish-images.sh
   ```
4. Aggiornare `newTag` in [`k8s/overlays/dynatrace-ready/kustomization.yaml`](../k8s/overlays/dynatrace-ready/kustomization.yaml).

---

## 3. Regole Invarianti per Sviluppatori e Agenti Coder

1. **Zero-Host-Tooling**: non introdurre mai comandi `java` o `mvn` diretti sull'host nel `Makefile` o in `scripts/`. Tutto deve compilare e testare dentro container Docker.
2. **Nessun tag `latest`**: ogni immagine base e applicativa nei Dockerfile, Compose e manifest Kubernetes deve usare una versione esplicita.
3. **Non-root obbligatorio**: ogni nuova immagine applicativa deve girare con utente non-root (`UID != 0`).
4. **Nessuna duplicazione degli Span**: non creare span manuali per chiamate HTTP, JDBC o RabbitMQ già strumentate dall'OpenTelemetry Java Agent; arricchire solo lo span corrente (`Span.current()`).
5. **Nessun segreto tracciato**: mai scrivere password di produzione, token Dynatrace (`DT_API_TOKEN`) o file `.env` nel repository Git né stamparli nei log/span.
6. **Retrocompatibilità Docker Compose ↔ Kubernetes**: ogni modifica a `test/end-to-end/run_e2e_suite.py` o ai contratti HTTP/AMQP deve continuare a funzionare sia su `make test` (Compose) sia su `make k8s-test` (`kind`).
