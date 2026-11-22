# Operations Runbook (`otel-shop-3-layer`)

Practical day-2 command reference for inspecting containers/pods, streaming logs, connecting to MySQL, running inspection queries, and checking RabbitMQ across both **Docker Compose** and **Kubernetes** (Dev/Test and Production/Dynatrace modes).

---

## 1. Checking Container & Pod Status

### 1.1 Docker Compose

#### Mode 1 — Development (`compose.yaml` + `compose.dev.yaml`)
```bash
docker compose -f compose.yaml -f compose.dev.yaml ps
docker stats --no-stream $(docker compose -f compose.yaml -f compose.dev.yaml ps -q)
curl -s http://localhost:18080/healthz
curl -s http://localhost:18080/api/products
```

#### Mode 2 — Production OneAgent (`compose.yaml` + `compose.prod.yaml`)
```bash
docker compose -f compose.yaml -f compose.prod.yaml ps
docker inspect --format '{{json .State.Health}}' commerce-api-gateway
```

#### Mode 3 — Production with Dynatrace OTel Collector (`compose.yaml` + `compose.prod-otel.yaml`)
```bash
docker compose -f compose.yaml -f compose.prod-otel.yaml ps
docker logs commerce-dynatrace-otel-collector --tail=50
```

---

### 1.2 Kubernetes (`kind` Local Dev & Remote Production / Dynatrace)

> [!NOTE]
> On local `kind`, ensure `~/.local/bin` is in your `PATH`: `export PATH="${HOME}/.local/bin:${PATH}"`.  
> In **Production (`prod` / `prod-otel`)**, only the 3 application namespaces exist (`commerce-edge`, `commerce-core`, `commerce-data`). In **Development (`dev`)**, the 4th namespace `commerce-observability` is also present.

```bash
# Overview of all pods across all project namespaces
kubectl get pods -A -l app.kubernetes.io/part-of=commerce-synthetic -o wide

# Check Deployments and StatefulSets rollout status
kubectl get deploy,sts -A -l app.kubernetes.io/part-of=commerce-synthetic

# Check Services and NodePorts
kubectl get svc -A -l app.kubernetes.io/part-of=commerce-synthetic

# Check persistent MySQL volume (PVC)
kubectl -n commerce-data get pvc

# Check recent cluster events (useful if a pod is Pending or CrashLoopBackOff)
kubectl get events -A --sort-by='.lastTimestamp' | tail -n 25

# Describe a specific pod (probes, env, mount errors)
kubectl -n commerce-core describe pod -l app.kubernetes.io/name=auth-service
```

---

## 2. Inspecting Logs

All Java services and Apache emit structured single-line JSON logs to `stdout` containing `trace_id`, `span_id`, `run_id`, `journey_id`, and `journey_state`.

### 2.1 Docker Compose Logs

```bash
# Tail logs from all services (dev / prod / prod-otel)
docker compose -f compose.yaml -f compose.dev.yaml logs -f --tail=100
docker compose -f compose.yaml -f compose.prod.yaml logs -f --tail=100
docker compose -f compose.yaml -f compose.prod-otel.yaml logs -f --tail=100

# Tail a single service
docker compose -f compose.yaml -f compose.dev.yaml logs -f auth-service
docker compose -f compose.yaml -f compose.dev.yaml logs -f api-gateway
docker compose -f compose.yaml -f compose.dev.yaml logs -f apache-web
docker compose -f compose.yaml -f compose.dev.yaml logs -f cart-service
docker compose -f compose.yaml -f compose.dev.yaml logs -f checkout-service
docker compose -f compose.yaml -f compose.dev.yaml logs -f merchant-service
docker compose -f compose.yaml -f compose.dev.yaml logs -f fulfillment-service
docker compose -f compose.yaml -f compose.dev.yaml logs -f journey-generator

# Search logs for a specific trace_id or journey_id
docker compose -f compose.yaml -f compose.dev.yaml logs --tail=500 | grep "<trace_id_or_journey_id>"
```

### 2.2 Kubernetes Logs

```bash
# Edge layer (commerce-edge)
kubectl -n commerce-edge logs -f deploy/apache-web --tail=100
kubectl -n commerce-edge logs -f deploy/api-gateway --tail=100
kubectl -n commerce-edge logs -f deploy/journey-generator --tail=100

# Core microservices (commerce-core)
kubectl -n commerce-core logs -f deploy/auth-service --tail=100
kubectl -n commerce-core logs -f deploy/cart-service --tail=100
kubectl -n commerce-core logs -f deploy/checkout-service --tail=100
kubectl -n commerce-core logs -f deploy/merchant-service --tail=100
kubectl -n commerce-core logs -f deploy/fulfillment-service --tail=100

# Data layer (commerce-data)
kubectl -n commerce-data logs -f sts/mysql --tail=100
kubectl -n commerce-data logs -f sts/rabbitmq --tail=100

# Local observability layer (only in dev overlay)
kubectl -n commerce-observability logs -f deploy/otel-collector --tail=100
kubectl -n commerce-observability logs -f ds/fluent-bit --tail=100

# Search across all core services for a specific trace_id
kubectl -n commerce-core logs -l app.kubernetes.io/part-of=commerce-synthetic --tail=300 | grep "<trace_id>"

# View logs from a previous crashed container instance
kubectl -n commerce-core logs deploy/checkout-service --previous
```

---

## 3. MySQL Login & Useful Inspection Queries

MySQL 8.4.3 uses **4 isolated schemas and dedicated non-root users** (default local dev credentials shown below; in production, read credentials from your Kubernetes `Secret` `mysql-credentials` or `.env`):

| Schema | User | Default Dev Password | Owned By |
|---|---|---|---|
| `auth_db` | `auth_app` | `auth_local_dev_only` | `auth-service` |
| `cart_db` | `cart_app` | `cart_local_dev_only` | `cart-service` |
| `checkout_db` | `checkout_app` | `checkout_local_dev_only` | `checkout-service` |
| `fulfillment_db` | `fulfillment_app` | `fulfillment_local_dev_only` | `fulfillment-service` |

### 3.1 Interactive MySQL Shell Login

#### Docker Compose
```bash
# Connect to auth_db
docker compose -f compose.yaml exec mysql mysql -h 127.0.0.1 -u auth_app -p auth_db

# Connect to cart_db
docker compose -f compose.yaml exec mysql mysql -h 127.0.0.1 -u cart_app -p cart_db

# Connect to checkout_db
docker compose -f compose.yaml exec mysql mysql -h 127.0.0.1 -u checkout_app -p checkout_db

# Connect to fulfillment_db
docker compose -f compose.yaml exec mysql mysql -h 127.0.0.1 -u fulfillment_app -p fulfillment_db

# Admin login (root — inspect all 4 schemas at once)
docker compose -f compose.yaml exec mysql mysql -h 127.0.0.1 -u root -p
```

#### Kubernetes (Dev `kind` & Production)
```bash
# Connect to auth_db
kubectl -n commerce-data exec -it sts/mysql -- mysql -h 127.0.0.1 -u auth_app -p auth_db

# Connect to cart_db
kubectl -n commerce-data exec -it sts/mysql -- mysql -h 127.0.0.1 -u cart_app -p cart_db

# Connect to checkout_db
kubectl -n commerce-data exec -it sts/mysql -- mysql -h 127.0.0.1 -u checkout_app -p checkout_db

# Connect to fulfillment_db
kubectl -n commerce-data exec -it sts/mysql -- mysql -h 127.0.0.1 -u fulfillment_app -p fulfillment_db

# Admin login (root — inspect all 4 schemas at once)
kubectl -n commerce-data exec -it sts/mysql -- mysql -h 127.0.0.1 -u root -p
```

> [!TIP]
> To retrieve the MySQL passwords stored in a Kubernetes cluster secret:
> ```bash
> kubectl -n commerce-data get secret mysql-credentials -o jsonpath='{.data.MYSQL_AUTH_PASSWORD}' | base64 -d; echo
> ```

---

### 3.2 Ready-to-Run SQL Queries

#### Users (`auth_db`)
```sql
-- List all registered users (newest first)
SELECT user_id, email, full_name, created_at
FROM auth_db.users
ORDER BY created_at DESC;

-- Total registered users
SELECT COUNT(*) AS total_users FROM auth_db.users;
```

#### Products & Carts (`cart_db`)
```sql
-- List the 3 demo products
SELECT sku, name, price_cents / 100 AS price_eur, category, badge
FROM cart_db.products
ORDER BY sku;

-- Latest 20 carts with item count
SELECT c.cart_id, c.user_id, c.status, COUNT(ci.sku) AS items, c.created_at
FROM cart_db.cart c
LEFT JOIN cart_db.cart_items ci ON ci.cart_id = c.cart_id
GROUP BY c.cart_id, c.user_id, c.status, c.created_at
ORDER BY c.created_at DESC
LIMIT 20;

-- Inspect items inside a specific cart
SELECT ci.cart_id, ci.sku, p.name, ci.quantity, ci.unit_price_cents / 100 AS unit_eur
FROM cart_db.cart_items ci
LEFT JOIN cart_db.products p ON p.sku = ci.sku
WHERE ci.cart_id = '<cart_id>';
```

#### Orders (`checkout_db`)
```sql
-- Latest 20 orders
SELECT order_id, user_id, cart_id, status, total_cents / 100 AS total_eur, payment_authorization_id, created_at
FROM checkout_db.orders
ORDER BY created_at DESC
LIMIT 20;

-- Orders grouped by status
SELECT status, COUNT(*) AS count
FROM checkout_db.orders
GROUP BY status;
```

#### Fulfillments (`fulfillment_db`)
```sql
-- Latest 20 fulfillments
SELECT fulfillment_id, order_id, journey_id, status, created_at
FROM fulfillment_db.fulfillment
ORDER BY created_at DESC
LIMIT 20;

-- Cross-check orders vs fulfillments (requires root login)
SELECT o.order_id, o.status AS order_status, f.status AS fulfillment_status, o.created_at
FROM checkout_db.orders o
LEFT JOIN fulfillment_db.fulfillment f ON f.order_id = o.order_id
ORDER BY o.created_at DESC
LIMIT 20;
```

#### Flyway Migration History (any schema)
```sql
SELECT version, description, script, installed_on, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

#### One-Liner Non-Interactive Queries
```bash
# Docker Compose example: count users & orders
docker compose -f compose.yaml exec -T mysql mysql -h 127.0.0.1 -u auth_app -pauth_local_dev_only -e "SELECT email, full_name, created_at FROM users ORDER BY created_at DESC LIMIT 10;" auth_db

# Kubernetes example: latest 10 orders
kubectl -n commerce-data exec sts/mysql -- mysql -h 127.0.0.1 -u checkout_app -pcheckout_local_dev_only -e "SELECT order_id, status, total_cents/100 AS eur, created_at FROM orders ORDER BY created_at DESC LIMIT 10;" checkout_db
```

---

## 4. RabbitMQ Quick Checks

### Docker Compose
```bash
# List queues, message counts, and consumers
docker compose -f compose.yaml exec rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers

# Check cluster/node health
docker compose -f compose.yaml exec rabbitmq rabbitmq-diagnostics -q check_port_connectivity
```

### Kubernetes
```bash
# List queues, message counts, and consumers
kubectl -n commerce-data exec sts/rabbitmq -- rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers

# Inspect dead-letter queue depth (commerce.fulfillment.dlq)
kubectl -n commerce-data exec sts/rabbitmq -- rabbitmqctl list_queues name messages | grep commerce
```

---

## 5. Enabling & Disabling Fault Scenarios

Only one fault scenario is active at a time.

### Precedence & Safety Rules
1. **Master switch**: when `SCENARIO_ENABLED=false`, the system **always** runs `NORMAL`, even if `SCENARIO_NAME` is set to a fault value.
2. **Per-request headers override global config**: sending `x-simulation-scenario` and `x-simulation-enabled` on an HTTP request overrides the service's environment/ConfigMap defaults for that single request only.
3. **Valid `SCENARIO_NAME` values**: `NORMAL`, `DB_SLOW`, `BE_ERROR`, `DB_TIMEOUT`, `MERCHANT_TIMEOUT`, `BROKER_UNAVAILABLE`, `FULFILLMENT_ERROR`.

### Optional Timing Parameters
| Variable | Default | Used By |
|---|---:|---|
| `DB_SLOW_DURATION_MS` | `1500` | `DB_SLOW` (`SELECT SLEEP(...)` on `checkout_db`) |
| `DB_OPERATION_TIMEOUT_MS` | `1000` | `DB_TIMEOUT` (JDBC query timeout on `checkout_db`) |
| `MERCHANT_TIMEOUT_MS` | `1500` | `MERCHANT_TIMEOUT` (HTTP client timeout to `merchant-service`) |
| `BROKER_PUBLISH_TIMEOUT_MS` | `1500` | `BROKER_UNAVAILABLE` (RabbitMQ publish timeout) |
| `FULFILLMENT_ERROR_AFTER_MS` | `100` | `FULFILLMENT_ERROR` (delay before NACK to DLQ) |

---

### 5.1 Single-Request Injection (No Restart)

Ideal for testing one specific error trace in Jaeger or Dynatrace without changing the running environment:

```bash
# Replace DB_TIMEOUT with any scenario name (DB_SLOW, BE_ERROR, DB_TIMEOUT, MERCHANT_TIMEOUT, BROKER_UNAVAILABLE, FULFILLMENT_ERROR)
curl -i -X POST http://localhost:18080/api/journeys \
  -H "Content-Type: application/json" \
  -H "x-run-id: 11111111-1111-4111-8111-111111111111" \
  -H "x-journey-id: 22222222-2222-4222-8222-222222222222" \
  -H "x-simulation-scenario: DB_TIMEOUT" \
  -H "x-simulation-enabled: true" \
  -d '{"run_id":"11111111-1111-4111-8111-111111111111","journey_id":"22222222-2222-4222-8222-222222222222","sku":"SKU-WATCH-01","quantity":1}'
```

> [!NOTE]
> Use a fresh `journey_id` UUID on every request (each table enforces a unique `journey_id` index).

---

### 5.2 Global Activation & Deactivation on Docker Compose

#### Enable a Scenario (affects both SPA checkouts and `journey-generator`)
```bash
# Dev/Test stack (replace DB_SLOW with the desired scenario)
SCENARIO_NAME=DB_SLOW SCENARIO_ENABLED=true ./scripts/compose-up.sh test

# Production / Dynatrace stack
SCENARIO_NAME=DB_SLOW SCENARIO_ENABLED=true ./scripts/compose-up.sh dynatrace
```

#### Disable (Restore `NORMAL` Behavior)
```bash
# Dev/Test stack
SCENARIO_NAME=NORMAL SCENARIO_ENABLED=false ./scripts/compose-up.sh test

# Production / Dynatrace stack
SCENARIO_NAME=NORMAL SCENARIO_ENABLED=false ./scripts/compose-up.sh dynatrace
```

#### Verify Active Scenario in Docker Compose
```bash
docker compose -f compose.yaml exec checkout-service env | grep '^SCENARIO_'
```

---

### 5.3 Global Activation & Deactivation on Kubernetes (`kind` & Production)

#### Enable a Scenario (example: `MERCHANT_TIMEOUT`)
```bash
# 1. Update ConfigMaps in commerce-core and commerce-edge
kubectl -n commerce-core patch configmap commerce-core-config --type merge \
  -p '{"data":{"SCENARIO_NAME":"MERCHANT_TIMEOUT","SCENARIO_ENABLED":"true"}}'

kubectl -n commerce-edge patch configmap commerce-edge-config --type merge \
  -p '{"data":{"SCENARIO_NAME":"MERCHANT_TIMEOUT","SCENARIO_ENABLED":"true"}}'

# 2. Restart affected Deployments so pods pick up the updated ConfigMaps
kubectl -n commerce-core rollout restart deployment/checkout-service deployment/merchant-service deployment/fulfillment-service
kubectl -n commerce-edge rollout restart deployment/api-gateway deployment/journey-generator

# 3. Wait for rollout completion
kubectl -n commerce-core rollout status deployment/checkout-service deployment/merchant-service deployment/fulfillment-service --timeout=120s
kubectl -n commerce-edge rollout status deployment/api-gateway deployment/journey-generator --timeout=120s
```

#### Disable (Restore `NORMAL` Behavior)
```bash
# 1. Reset ConfigMaps to NORMAL / false
kubectl -n commerce-core patch configmap commerce-core-config --type merge \
  -p '{"data":{"SCENARIO_NAME":"NORMAL","SCENARIO_ENABLED":"false"}}'

kubectl -n commerce-edge patch configmap commerce-edge-config --type merge \
  -p '{"data":{"SCENARIO_NAME":"NORMAL","SCENARIO_ENABLED":"false"}}'

# 2. Restart affected Deployments
kubectl -n commerce-core rollout restart deployment/checkout-service deployment/merchant-service deployment/fulfillment-service
kubectl -n commerce-edge rollout restart deployment/api-gateway deployment/journey-generator

# 3. Wait for rollout completion
kubectl -n commerce-core rollout status deployment/checkout-service deployment/merchant-service deployment/fulfillment-service --timeout=120s
kubectl -n commerce-edge rollout status deployment/api-gateway deployment/journey-generator --timeout=120s
```

#### Verify Active Scenario in Kubernetes
```bash
kubectl -n commerce-core get configmap commerce-core-config -o jsonpath='SCENARIO_NAME={.data.SCENARIO_NAME} SCENARIO_ENABLED={.data.SCENARIO_ENABLED}{"\n"}'
kubectl -n commerce-edge get configmap commerce-edge-config -o jsonpath='SCENARIO_NAME={.data.SCENARIO_NAME} SCENARIO_ENABLED={.data.SCENARIO_ENABLED}{"\n"}'
```

---

## 6. Persistence & Safe Restart Commands

- **Docker Compose**:
  - `make down` stops and removes containers **while preserving** `mysql_data` and `rabbitmq_data` volumes.
  - `make clean` stops containers **and deletes all volumes** (`REMOVE_VOLUMES=true`).
- **Kubernetes**:
  - Restarting a pod (`kubectl -n commerce-data delete pod mysql-0`) reattaches the existing `5Gi` `PersistentVolumeClaim` (`data-mysql-0`), preserving all users, carts, orders, and fulfillments.
  - Rolling restart of a single microservice:
    ```bash
    kubectl -n commerce-core rollout restart deployment/cart-service
    kubectl -n commerce-edge rollout restart deployment/apache-web
    ```
