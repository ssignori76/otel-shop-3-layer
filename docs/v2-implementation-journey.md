# v2.0.0 Implementation Journey (`feat/v2-spa-auth-ecommerce`)

This document tracks every planned and unplanned activity executed during the development of version **`2.0.0`** of `otel-shop-3-layer`.

---

## Status Dashboard

- **Git Branch**: `feat/v2-spa-auth-ecommerce` (branched from `main` @ `7405d2a`)
- **Target Version**: `2.0.0`
- **Progress**: `10 / 10` planned steps completed (`100%`)
- **Last Updated (UTC)**: `2026-11-22T14:06:00Z`

---

## Step-by-Step Operational Plan

| ID | Phase | Activity | Status | Notes / Evidence |
|---|---|---|---|---|
| **S0** | Preparation | Stop active processes (`kind` + Compose) and create branch `feat/v2-spa-auth-ecommerce` | `DONE` | Deleted `kind` cluster `commerce-synthetic`; ran `compose-down.sh`; verified clean `docker ps` (only unrelated `open-webui`); created branch `feat/v2-spa-auth-ecommerce`. |
| **S1** | Tracking | Create `docs/v2-implementation-journey.md` and link it in `README.md` | `DONE` | File created and referenced in Section 7 of `README.md`. |
| **S2** | Contracts & Version | Bump version to `2.0.0` (POM/Compose/K8s) + new DTOs in `libs/api-contract` and user headers in `libs/scenario-contract` | `DONE` | Updated Maven parent and child modules to `2.0.0`; added `x-user-id`/`x-user-email` to `CorrelationHeaders`; added Auth/Products/CartItems/Orders/Fulfillment DTOs and zero-dependency `JwtTokenProvider` (HS256) + unit tests. |
| **S3** | Authentication | Create `services/auth-service` (`:8086`), `auth_db`, Flyway (with demo user `demo@otelshop.local`), JWT HS256 + BCrypt, metrics/logs, and unit tests | `DONE` | Created `services/auth-service`, Flyway `V1__create_users_table.sql`, idempotent `DemoUserSeeder`, `AuthController` (`/register`, `/login`, `/me`), and `AuthControllerTest`. |
| **S4** | API Gateway | Add new routes (`/api/auth/**`, `/api/products`, `/api/orders/**`, `/api/fulfillments/**`) + `JwtAuthenticationWebFilter` and unit tests | `DONE` | Added all routes to `application.yml` and `config/gateway/routes.yml`; implemented `JwtAuthenticationWebFilter` (JWT + optional `GENERATOR_STATIC_TOKEN` + anti-spoofing) and `JwtAuthenticationWebFilterTest`. |
| **S5** | Core Services | Evolve `cart-service` (Flyway V2: 3 demo products + `cart_items`), `checkout-service` (Flyway V2: `user_id`/`total_cents` + `GET /internal/orders`), `fulfillment-service` (`GET /internal/fulfillments/{orderId}`) and unit tests | `DONE` | Added Flyway V2 migrations on `cart_db` and `checkout_db`; added `ProductController`, `OrdersController`, `FulfillmentController`; extended repositories, controllers, and unit tests. |
| **S6** | Frontend SPA & Apache | Create zero-build Vanilla JS ES Modules SPA in `frontend/` (3-product storefront, registration, JWT login, cart, checkout, orders `PUBLISHED→FULFILLED`, Dynatrace RUM hook) + `apache-web` config (Dockerfile + `httpd.conf` + K8s ConfigMap) | `DONE` | Created `frontend/index.html`, `styles.css`, `js/state.js`, `js/api.js`, `js/app.js`; updated `docker/apache/Dockerfile`, `httpd.conf`, and `k8s/base/edge/apache-web.yaml` with `mod_dir` and `FallbackResource /index.html`. |
| **S7** | Traffic Generator | Evolve `journey-generator` to verify registration (idempotently on startup) and JWT login / predefined test token on each journey | `DONE` | Extended `GeneratorProperties`, `JourneyGeneratorService`, and `application.yml` with `GENERATOR_AUTH_*` and `GENERATOR_STATIC_TOKEN`. |
| **S8** | Persistence & Infra | Persistent MySQL storage on K8s (`volumeClaimTemplates` `5Gi` PVC) and Compose (volumes preserved on `compose-down.sh`), add `auth_db`/`auth_app`, K8s `auth-service` + secrets, `local-observability` & `dynatrace-ready` overlays, `container-build.sh` & `publish-images.sh` | `DONE` | Updated `database/init/01-init-schemas-and-users.sh`, `compose.yaml`, `compose.test.yaml`, `scripts/compose-down.sh`, `Makefile`, `scripts/container-build.sh`, `scripts/publish-images.sh`, `scripts/k8s-up.sh`, `k8s/base/data/mysql.yaml` (`5Gi` PVC), `k8s/base/core/auth-service.yaml`, `k8s/base/*/config.yaml`, and `dynatrace-ready` overlay. |
| **S9** | Verification & Closure | Extend E2E suite (`run_e2e_suite.py` Gate 13), run `make unit-test`, `make build`, `make test`, `make k8s-up && make k8s-test`, update final documentation | `DONE` | All 11 Maven modules PASS (`make unit-test`); 10/10 `2.0.0` non-root images (`make build`); Compose E2E Gates 1–13 PASS + MySQL persistence verified (`3→3` users across `make down` + restart); Kubernetes `kind` PVC + `dynatrace-ready` + E2E Gates 1–13 PASS (Jaeger + VictoriaMetrics + VictoriaLogs/Fluent Bit); docs updated. |

---

## Extra / Unplanned Activities

| Date (UTC) | Extra Activity | Reason | Outcome |
|---|---|---|---|
| `2026-11-22T12:09Z` | Refactored `verify_v2_spa_and_auth()` in `test/end-to-end/run_e2e_suite.py` from `requests` to `http_json`/`http_text` (`urllib.request`) | `commerce/test-runner` image uses Python stdlib `urllib.request` without `requests` | Resolved |
| `2026-11-22T14:06Z` | Full English localization of frontend SPA (`frontend/index.html`, `frontend/js/app.js`), product catalog via Flyway `V3__translate_products_to_english.sql`, and all documentation (`README.md`, `docs/*.md`) | User requested English UI and documentation while preserving Flyway V2 checksum compatibility on existing persistent databases | Resolved (`cart-service:2.0.0` + `apache-web:2.0.0` rebuilt, loaded to `kind`, rolled out, and verified live via HTTP) |
| `2026-11-22T15:21Z` | Multi-line layout for order confirmation & order history details (`frontend/styles.css`, `frontend/js/app.js`) + new `docs/operations-runbook.md` linked in `README.md` | User requested clearer multi-line order details and a dedicated day-2 runbook for Docker/K8s status, logs, MySQL login & queries | Resolved (`apache-web:2.0.0` rebuilt and redeployed to `kind`) |
| `2026-11-22T15:29Z` | Refined home hero section (eyebrow + pipeline pills + single-line subtitle) and documented step-by-step fault scenario enable/disable commands in `README.md` & `docs/operations-runbook.md` | User requested visual polish on the storefront hero and clear operational instructions for enabling/disabling fault scenarios | Resolved |

---

## Architecture Decision Records (ADR)

- **ADR-01 (Zero-Build Frontend SPA)**: chose Vanilla JS (ES Modules) + HTML5 + CSS3 without Node/npm for maximum simplicity, zero npm dependency maintenance, instant Apache builds, and full Dynatrace RUM compatibility (History API + `fetch()` + `dtrum.identifyUser()`).
- **ADR-02 (Dedicated Auth Service)**: introduced `auth-service` (`:8086`) with isolated `auth_db` schema and `auth_app` user, preserving the 3-layer separation (`commerce-edge` never accesses `commerce-data` directly) and MySQL isolation (Gate 8).
- **ADR-03 (Backend-Served Product Catalog)**: the 3 demo products are served by `GET /api/products` from `cart-service` + MySQL (Flyway `V2`/`V3`) so even loading the home page generates a real distributed trace for RUM.
- **ADR-04 (Bounded Synthetic Auth Traffic)**: `journey-generator` stays active by default; it checks registration idempotently on startup (preventing unbounded `auth_db.users` growth) and performs JWT login (or uses `GENERATOR_STATIC_TOKEN` when set) on periodic journeys.
- **ADR-05 (Persistent MySQL Storage)**: `volumeClaimTemplates` (`5Gi` PVC) on the Kubernetes `mysql` `StatefulSet` and default volume preservation in `scripts/compose-down.sh` (`make clean` removes volumes explicitly).
- **ADR-06 (Container-Only Development)**: zero host Java/Maven/Node/Python tooling; all builds and tests run exclusively inside Docker containers.
- **ADR-07 (Additive Flyway V3 for English Catalog)**: added `V3__translate_products_to_english.sql` rather than modifying `V2` in place to guarantee zero Flyway checksum conflicts on existing persistent MySQL volumes.
