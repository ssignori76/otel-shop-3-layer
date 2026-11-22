#!/usr/bin/env python3
"""
Automated End-to-End Functional, Database, RabbitMQ, Jaeger Trace, OTel Metrics, and OTel Logs Validator.
Covers Gates 2 through 12 of the specification.
"""

import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import pymysql


APACHE_URL = os.environ.get("APACHE_URL", "http://apache-web:80")
GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://api-gateway:8080")
GENERATOR_URL = os.environ.get("GENERATOR_URL", "http://journey-generator:8085")
JAEGER_URL = os.environ.get("JAEGER_URL", "http://jaeger:16686")
OTEL_PROMETHEUS_URL = os.environ.get("OTEL_PROMETHEUS_URL", "http://otel-collector:8889/metrics")
VICTORIA_METRICS_URL = os.environ.get("VICTORIA_METRICS_URL", "").rstrip("/")
VICTORIA_LOGS_URL = os.environ.get("VICTORIA_LOGS_URL", "").rstrip("/")
OTEL_EXPORT_DIR = os.environ.get("OTEL_EXPORT_DIR", "/var/log/otel")
MYSQL_HOST = os.environ.get("MYSQL_HOST", "mysql")
MYSQL_PORT = int(os.environ.get("MYSQL_PORT", "3306"))
MYSQL_CART_USER = os.environ.get("MYSQL_CART_USER", "cart_app")
MYSQL_CART_PASSWORD = os.environ.get("MYSQL_CART_PASSWORD", "cart_local_dev_only")
MYSQL_CHECKOUT_USER = os.environ.get("MYSQL_CHECKOUT_USER", "checkout_app")
MYSQL_CHECKOUT_PASSWORD = os.environ.get("MYSQL_CHECKOUT_PASSWORD", "checkout_local_dev_only")
MYSQL_FULFILLMENT_USER = os.environ.get("MYSQL_FULFILLMENT_USER", "fulfillment_app")
MYSQL_FULFILLMENT_PASSWORD = os.environ.get("MYSQL_FULFILLMENT_PASSWORD", "fulfillment_local_dev_only")
RABBITMQ_API_URL = os.environ.get("RABBITMQ_API_URL", "http://rabbitmq:15672/api")
RABBITMQ_USER = os.environ.get("RABBITMQ_USER", "commerce_app")
RABBITMQ_PASSWORD = os.environ.get("RABBITMQ_PASSWORD", "rabbit_local_dev_only")
REPORT_DIR = os.environ.get("REPORT_DIR", "/reports")
NORMAL_STABILITY_COUNT = int(os.environ.get("NORMAL_STABILITY_COUNT", "30"))
SCENARIO_REPEAT_COUNT = int(os.environ.get("SCENARIO_REPEAT_COUNT", "3"))

FORBIDDEN_SECRETS = [
    MYSQL_CART_PASSWORD,
    MYSQL_CHECKOUT_PASSWORD,
    MYSQL_FULFILLMENT_PASSWORD,
    RABBITMQ_PASSWORD,
    "root_local_dev_only",
]


def http_json(url, method="GET", payload=None, headers=None, auth=None, timeout=30):
    req_headers = {"Accept": "application/json"}
    if headers:
        req_headers.update(headers)
    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        req_headers["Content-Type"] = "application/json"
    if auth:
        token = base64.b64encode(f"{auth[0]}:{auth[1]}".encode("utf-8")).decode("ascii")
        req_headers["Authorization"] = f"Basic {token}"

    req = urllib.request.Request(url, data=data, headers=req_headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as err:
        raw = err.read().decode("utf-8")
        try:
            parsed = json.loads(raw) if raw else {}
        except Exception:
            parsed = {"raw": raw}
        return err.code, parsed


def http_text(url, timeout=15):
    req = urllib.request.Request(url, method="GET")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, resp.read().decode("utf-8")


def db_connect(user, password, database):
    return pymysql.connect(
        host=MYSQL_HOST,
        port=MYSQL_PORT,
        user=user,
        password=password,
        database=database,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
        autocommit=True,
    )


def query_one(user, password, database, sql, args=()):
    conn = db_connect(user, password, database)
    try:
        with conn.cursor() as cur:
            cur.execute(sql, args)
            return cur.fetchone()
    finally:
        conn.close()


def trigger_journey(run_id, journey_id, scenario_name, scenario_enabled):
    status, body = http_json(
        f"{GENERATOR_URL}/internal/generator/trigger",
        method="POST",
        payload={
            "run_id": run_id,
            "journey_id": journey_id,
            "scenario_name": scenario_name,
            "scenario_enabled": scenario_enabled,
        },
        timeout=35,
    )
    if status != 200:
        raise RuntimeError(f"Generator trigger endpoint failed with HTTP {status}: {body}")
    return body


def wait_for_fulfillment(journey_id, timeout_sec=8.0):
    deadline = time.time() + timeout_sec
    while time.time() < deadline:
        row = query_one(
            MYSQL_FULFILLMENT_USER,
            MYSQL_FULFILLMENT_PASSWORD,
            "fulfillment_db",
            "SELECT fulfillment_id, order_id, journey_id, status FROM fulfillment WHERE journey_id = %s",
            (journey_id,),
        )
        if row is not None:
            return row
        time.sleep(0.25)
    return None


def fetch_jaeger_trace(trace_id, required_services=None, timeout_sec=12.0):
    deadline = time.time() + timeout_sec
    last_trace = None
    while time.time() < deadline:
        status, data = http_json(f"{JAEGER_URL}/api/traces/{trace_id}", timeout=10)
        if status == 200 and data.get("data"):
            trace_obj = data["data"][0]
            last_trace = trace_obj
            processes = trace_obj.get("processes", {})
            observed_services = {p.get("serviceName") for p in processes.values()}
            spans = trace_obj.get("spans", [])
            span_ids = {s["spanID"] for s in spans}
            has_orphan = any(
                ref.get("spanID") not in span_ids
                for s in spans
                for ref in s.get("references", [])
            )
            if (not required_services or required_services.issubset(observed_services)) and not has_orphan:
                return trace_obj
        time.sleep(0.4)
    return last_trace


def validate_trace_structure(trace_obj, expected_services, absent_services=None, expect_error=False):
    if not trace_obj:
        return False, "Trace not found in Jaeger"

    processes = trace_obj.get("processes", {})
    spans = trace_obj.get("spans", [])
    if not spans:
        return False, "Trace has zero spans"

    observed_services = {p.get("serviceName") for p in processes.values()}
    missing = set(expected_services) - observed_services
    if missing:
        return False, f"Missing expected services in trace: {sorted(missing)} (observed: {sorted(observed_services)})"

    if absent_services:
        unexpected = set(absent_services) & observed_services
        if unexpected:
            return False, f"Prohibited downstream services present in trace: {sorted(unexpected)}"

    span_ids = {s["spanID"] for s in spans}
    has_db_span = False
    has_error_span = False

    for s in spans:
        tags = {t["key"]: t.get("value") for t in s.get("tags", [])}
        raw_str = json.dumps(s)
        for secret in FORBIDDEN_SECRETS:
            if secret and secret in raw_str:
                return False, "Sensitive credential leaked in span tags/logs"

        if tags.get("db.system") == "mysql":
            has_db_span = True
        if tags.get("error") is True or tags.get("otel.status_code") == "ERROR":
            has_error_span = True

        # Verify parent reference exists within same trace for non-root spans
        for ref in s.get("references", []):
            if ref.get("traceID") != trace_obj.get("traceID"):
                return False, f"Broken traceID reference on span {s.get('operationName')}"
            parent_id = ref.get("spanID")
            if parent_id not in span_ids:
                return False, f"Orphan parent spanID {parent_id} on span {s.get('operationName')}"

    if not has_db_span:
        return False, "No MySQL JDBC spans (db.system=mysql) found in trace"

    if expect_error and not has_error_span:
        return False, "Expected error=true / otel.status_code=ERROR span in failure trace"

    return True, f"Valid trace with {len(spans)} spans across {sorted(observed_services)}"


def verify_mysql_isolation():
    # 1. Verify each user can query its own schema and Flyway history
    cart_hist = query_one(
        MYSQL_CART_USER, MYSQL_CART_PASSWORD, "cart_db",
        "SELECT COUNT(*) AS cnt FROM flyway_schema_history WHERE success = 1"
    )
    checkout_hist = query_one(
        MYSQL_CHECKOUT_USER, MYSQL_CHECKOUT_PASSWORD, "checkout_db",
        "SELECT COUNT(*) AS cnt FROM flyway_schema_history WHERE success = 1"
    )
    fulfillment_hist = query_one(
        MYSQL_FULFILLMENT_USER, MYSQL_FULFILLMENT_PASSWORD, "fulfillment_db",
        "SELECT COUNT(*) AS cnt FROM flyway_schema_history WHERE success = 1"
    )
    if not (cart_hist["cnt"] >= 1 and checkout_hist["cnt"] >= 1 and fulfillment_hist["cnt"] >= 1):
        return False, "Flyway migration history missing on one or more schemas"

    # 2. Verify cross-schema access is rejected (Gate 8)
    cross_checks = [
        (MYSQL_CART_USER, MYSQL_CART_PASSWORD, "cart_db", "SELECT * FROM checkout_db.orders LIMIT 1"),
        (MYSQL_CHECKOUT_USER, MYSQL_CHECKOUT_PASSWORD, "checkout_db", "SELECT * FROM fulfillment_db.fulfillment LIMIT 1"),
        (MYSQL_FULFILLMENT_USER, MYSQL_FULFILLMENT_PASSWORD, "fulfillment_db", "SELECT * FROM cart_db.cart LIMIT 1"),
    ]
    for user, pwd, db, forbidden_sql in cross_checks:
        try:
            query_one(user, pwd, db, forbidden_sql)
            return False, f"User {user} was unexpectedly allowed to execute: {forbidden_sql}"
        except pymysql.err.OperationalError:
            pass

    return True, "Flyway migrations verified and cross-schema access rejected for all 3 users"


def verify_rabbitmq_topology():
    auth = (RABBITMQ_USER, RABBITMQ_PASSWORD)
    for ex_name in ["commerce.orders", "commerce.orders.dlx"]:
        st, body = http_json(f"{RABBITMQ_API_URL}/exchanges/%2F/{ex_name}", auth=auth)
        if st != 200 or not body.get("durable"):
            return False, f"Exchange {ex_name} missing or not durable (HTTP {st})"

    for q_name in ["commerce.fulfillment", "commerce.fulfillment.dlq"]:
        st, body = http_json(f"{RABBITMQ_API_URL}/queues/%2F/{q_name}", auth=auth)
        if st != 200 or not body.get("durable"):
            return False, f"Queue {q_name} missing or not durable (HTTP {st})"

    return True, "Durable exchanges and queues verified via RabbitMQ Management API"


def get_queue_messages(queue_name):
    auth = (RABBITMQ_USER, RABBITMQ_PASSWORD)
    st, body = http_json(f"{RABBITMQ_API_URL}/queues/%2F/{queue_name}", auth=auth)
    if st != 200:
        return 0, 0
    return body.get("messages_ready", 0), body.get("messages_unacknowledged", 0)


def wait_for_dlq_increment(dlq_before, timeout_sec=7.0):
    deadline = time.time() + timeout_sec
    last_val = dlq_before
    while time.time() < deadline:
        ready, _ = get_queue_messages("commerce.fulfillment.dlq")
        last_val = ready
        if ready > dlq_before:
            return ready
        time.sleep(0.4)
    return last_val


def verify_scheduling_gate():
    st, status_body = http_json(f"{GENERATOR_URL}/internal/generator/status")
    if st != 200:
        return False, f"Failed to query generator status: HTTP {st}"
    if status_body.get("interval_seconds") != 10 or status_body.get("max_in_flight") != 1:
        return False, f"Unexpected generator scheduling config: {status_body}"

    # Verify unknown public route rejection on Gateway (Section 10)
    unk_status, _ = http_json(f"{APACHE_URL}/api/unknown-route-check")
    if unk_status != 404:
        return False, f"Expected 404 for unknown route on Gateway, got {unk_status}"

    return True, f"Generator config verified (interval=10s, max_in_flight=1) and unknown routes rejected with 404"


def verify_otel_metrics():
    # Allow metric export interval (2s) + batch flush
    time.sleep(3.0)
    status, prom_text = http_text(OTEL_PROMETHEUS_URL)
    if status != 200:
        return False, f"Prometheus endpoint on OTel Collector returned HTTP {status}"

    required_metric_Families = [
        # Automatic Java Agent runtime & HTTP/DB metrics
        "http_server_request_duration_seconds",
        "http_client_request_duration_seconds",
        "jvm_memory_used_bytes",
        "db_client_connections_usage",
        # Custom business metrics across all 6 services
        "commerce_journeys_started",
        "commerce_journeys_completed",
        "commerce_journeys_failed",
        "commerce_journey_client_duration_milliseconds",
        "commerce_gateway_requests",
        "commerce_gateway_orchestration_duration_milliseconds",
        "commerce_carts_created",
        "commerce_carts_read",
        "commerce_cart_operation_duration_milliseconds",
        "commerce_checkouts_started",
        "commerce_checkouts_completed",
        "commerce_checkouts_failed",
        "commerce_orders_published",
        "commerce_checkout_duration_milliseconds",
        "commerce_payments_requests",
        "commerce_payment_authorization_duration_milliseconds",
        "commerce_fulfillments_received",
        "commerce_fulfillments_completed",
        "commerce_fulfillments_failed",
        "commerce_fulfillment_duration_milliseconds",
    ]

    missing_metrics = [m for m in required_metric_Families if m not in prom_text]
    if missing_metrics:
        return False, f"Missing expected OpenTelemetry metrics on Prometheus endpoint: {missing_metrics}"

    if VICTORIA_METRICS_URL:
        vm_status, vm_body = http_json(f"{VICTORIA_METRICS_URL}/api/v1/label/__name__/values", timeout=15)
        if vm_status != 200 or vm_body.get("status") != "success":
            return False, f"VictoriaMetrics label query failed (HTTP {vm_status}): {vm_body}"
        vm_names = set(vm_body.get("data", []))
        # VictoriaMetrics stores OTLP metric names in dot or underscore form depending on ingestion format
        missing_vm = []
        for m in required_metric_Families:
            dot_name = m.replace("_", ".")
            if not any((m in n or dot_name in n or m.replace("_milliseconds", "").replace("_seconds", "").replace("_bytes", "") in n.replace(".", "_")) for n in vm_names):
                missing_vm.append(m)
        if missing_vm:
            return False, f"Missing expected OpenTelemetry metrics in VictoriaMetrics: {missing_vm}"
        return True, (
            f"All {len(required_metric_Families)} runtime and custom business metric families verified "
            f"in OTel Collector Prometheus & VictoriaMetrics ({len(vm_names)} series names ingested)"
        )

    metrics_file = os.path.join(OTEL_EXPORT_DIR, "metrics.json")
    if not os.path.exists(metrics_file) or os.path.getsize(metrics_file) == 0:
        return False, f"OTLP metrics export file {metrics_file} is missing or empty"

    return True, f"All {len(required_metric_Families)} runtime and custom business metric families verified in Prometheus & OTLP JSON"


def verify_otel_logs_via_victorialogs(known_journey_ids, known_trace_ids):
    time.sleep(2.0)
    expected_services = {
        "journey-generator",
        "api-gateway",
        "cart-service",
        "checkout-service",
        "merchant-service",
        "fulfillment-service",
    }
    query_url = f"{VICTORIA_LOGS_URL}/select/logsql/query?query=*&limit=5000"
    st, raw_ndjson = http_text(query_url, timeout=20)
    if st != 200:
        return False, f"VictoriaLogs query failed with HTTP {st}"

    observed_services = set()
    fluentbit_namespaces = set()
    correlated_log_count = 0
    error_severity_count = 0
    matched_traces = set()
    matched_journeys = set()
    total_records = 0

    for line in raw_ndjson.splitlines():
        line = line.strip()
        if not line:
            continue
        total_records += 1
        for secret in FORBIDDEN_SECRETS:
            if secret and secret in line:
                return False, "Sensitive credential leaked in VictoriaLogs records"
        rec = json.loads(line)
        svc = rec.get("service.name", "") or rec.get("parsed.service", "")
        if svc:
            observed_services.add(svc)
        k8s_ns = rec.get("kubernetes.namespace_name", "")
        if k8s_ns:
            fluentbit_namespaces.add(k8s_ns)

        trace_id = rec.get("trace_id", "") or rec.get("parsed.trace_id", "")
        span_id = rec.get("span_id", "") or rec.get("parsed.span_id", "")
        j_id = rec.get("journey_id", "") or rec.get("parsed.journey_id", "")
        sev = (rec.get("severity", "") or rec.get("parsed.severity", "") or "").upper()

        if sev in ("ERROR", "WARN"):
            error_severity_count += 1
        if trace_id and span_id and j_id:
            correlated_log_count += 1
        if trace_id in known_trace_ids:
            matched_traces.add(trace_id)
        if j_id in known_journey_ids:
            matched_journeys.add(j_id)

    missing_svcs = expected_services - observed_services
    if missing_svcs:
        return False, f"Missing services in VictoriaLogs: {sorted(missing_svcs)} (observed: {sorted(observed_services)})"
    require_fluentbit = os.environ.get("REQUIRE_FLUENTBIT_LOGS", "true").lower() == "true"
    if require_fluentbit and not fluentbit_namespaces:
        return False, "No Fluent Bit Kubernetes container logs found in VictoriaLogs"
    if correlated_log_count == 0 or not matched_traces or not matched_journeys:
        return False, (
            f"VictoriaLogs correlation insufficient: total={total_records}, correlated={correlated_log_count}, "
            f"matched_traces={len(matched_traces)}, matched_journeys={len(matched_journeys)}"
        )
    if error_severity_count == 0:
        return False, "Expected ERROR/WARN severity log records in VictoriaLogs, found 0"

    return True, (
        f"VictoriaLogs verified (OTLP + Fluent Bit): total={total_records}, correlated={correlated_log_count}, "
        f"error_records={error_severity_count}, matched_journeys={len(matched_journeys)}, "
        f"fluentbit_namespaces={sorted(fluentbit_namespaces)}"
    )


def verify_otel_logs(known_journey_ids, known_trace_ids):
    if VICTORIA_LOGS_URL:
        return verify_otel_logs_via_victorialogs(known_journey_ids, known_trace_ids)
    logs_file = os.path.join(OTEL_EXPORT_DIR, "logs.json")
    if not os.path.exists(logs_file) or os.path.getsize(logs_file) == 0:
        return False, f"OTLP logs export file {logs_file} is missing or empty"

    expected_services = {
        "journey-generator",
        "api-gateway",
        "cart-service",
        "checkout-service",
        "merchant-service",
        "fulfillment-service",
    }
    observed_services = set()
    correlated_log_count = 0
    error_severity_count = 0
    matched_traces = set()
    matched_journeys = set()

    with open(logs_file, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            for secret in FORBIDDEN_SECRETS:
                if secret and secret in line:
                    return False, "Sensitive credential leaked in OTLP logs"

            payload = json.loads(line)
            for rl in payload.get("resourceLogs", []):
                res_attrs = {
                    a["key"]: a.get("value", {}).get("stringValue", "")
                    for a in rl.get("resource", {}).get("attributes", [])
                }
                svc_name = res_attrs.get("service.name", "")
                if svc_name:
                    observed_services.add(svc_name)

                for sl in rl.get("scopeLogs", []):
                    for rec in sl.get("logRecords", []):
                        trace_id = rec.get("traceId", "")
                        span_id = rec.get("spanId", "")
                        sev_text = rec.get("severityText", "")
                        if sev_text in ("ERROR", "WARN"):
                            error_severity_count += 1
                        rec_attrs = {
                            a["key"]: a.get("value", {}).get("stringValue", "")
                            for a in rec.get("attributes", [])
                        }
                        j_id = rec_attrs.get("journey_id", "")
                        if trace_id and span_id and j_id:
                            correlated_log_count += 1
                        if trace_id in known_trace_ids:
                            matched_traces.add(trace_id)
                        if j_id in known_journey_ids:
                            matched_journeys.add(j_id)

    missing_svcs = expected_services - observed_services
    if missing_svcs:
        return False, f"Missing services in OTLP logs: {sorted(missing_svcs)} (observed: {sorted(observed_services)})"

    if correlated_log_count == 0 or not matched_traces or not matched_journeys:
        return False, (
            f"OTLP log correlation insufficient: correlated_logs={correlated_log_count}, "
            f"matched_traces={len(matched_traces)}, matched_journeys={len(matched_journeys)}"
        )

    if error_severity_count == 0:
        return False, "Expected ERROR/WARN severity log records for failure scenarios, found 0"

    return True, (
        f"OTLP logs verified across all 6 services: correlated_records={correlated_log_count}, "
        f"error_records={error_severity_count}, matched_journeys={len(matched_journeys)}"
    )


def verify_v2_spa_and_auth():
    # 1. SPA root and History API fallback route served by Apache
    st_root, txt_root = http_text(f"{APACHE_URL}/", timeout=10)
    if st_root != 200 or "OTel Shop" not in txt_root:
        return False, f"SPA root / failed: status={st_root}"

    st_spa, txt_spa = http_text(f"{APACHE_URL}/login", timeout=10)
    if st_spa != 200 or "OTel Shop" not in txt_spa:
        return False, f"SPA fallback /login failed: status={st_spa}"

    # 2. Public 3-product catalog from backend DB
    st_prod, products = http_json(f"{APACHE_URL}/api/products", timeout=10)
    if st_prod != 200:
        return False, f"GET /api/products failed: status={st_prod}"
    if not isinstance(products, list) or len(products) != 3:
        return False, f"Expected 3 demo products, got {len(products) if isinstance(products, list) else products}"

    # 3. Protected cart route must reject unauthenticated requests with 401
    st_unauth, _ = http_json(f"{APACHE_URL}/api/carts/active", timeout=10)
    if st_unauth != 401:
        return False, f"Expected 401 on /api/carts/active without JWT, got {st_unauth}"

    # 4. Demo user login
    st_demo, body_demo = http_json(
        f"{APACHE_URL}/api/auth/login",
        method="POST",
        payload={"email": "demo@otelshop.local", "password": "DemoPass123!"},
        timeout=10,
    )
    if st_demo != 200 or not body_demo.get("access_token"):
        return False, f"Demo user login failed: status={st_demo} body={body_demo}"

    # 5. Register unique E2E user + verify duplicate conflict (409)
    e2e_email = f"e2e-{uuid.uuid4().hex[:10]}@otelshop.local"
    st_reg, body_reg = http_json(
        f"{APACHE_URL}/api/auth/register",
        method="POST",
        payload={"full_name": "E2E Shopper", "email": e2e_email, "password": "E2ePassword123!"},
        timeout=10,
    )
    if st_reg != 201 or not body_reg.get("access_token"):
        return False, f"User registration failed: status={st_reg} body={body_reg}"
    jwt_token = body_reg["access_token"]

    st_dup, _ = http_json(
        f"{APACHE_URL}/api/auth/register",
        method="POST",
        payload={"full_name": "E2E Dup", "email": e2e_email, "password": "E2ePassword123!"},
        timeout=10,
    )
    if st_dup != 409:
        return False, f"Expected 409 on duplicate email registration, got {st_dup}"

    # 6. Full authenticated SPA cart -> checkout -> orders -> fulfillment loop
    run_id = str(uuid.uuid4())
    journey_id = str(uuid.uuid4())
    auth_headers = {
        "Authorization": f"Bearer {jwt_token}",
        "x-run-id": run_id,
        "x-journey-id": journey_id,
    }

    st_me, body_me = http_json(f"{APACHE_URL}/api/auth/me", headers=auth_headers, timeout=10)
    if st_me != 200 or body_me.get("email") != e2e_email:
        return False, f"GET /api/auth/me failed: status={st_me}"

    first_sku = products[0]["sku"]
    second_sku = products[1]["sku"]
    st_cart, body_cart = http_json(
        f"{APACHE_URL}/api/carts",
        method="POST",
        headers=auth_headers,
        payload={"run_id": run_id, "journey_id": journey_id, "sku": first_sku, "quantity": 1},
        timeout=10,
    )
    if st_cart != 201:
        return False, f"Authenticated POST /api/carts failed: status={st_cart} body={body_cart}"
    cart_id = body_cart["cart_id"]

    st_add, body_add = http_json(
        f"{APACHE_URL}/api/carts/{cart_id}/items",
        method="POST",
        headers=auth_headers,
        payload={"sku": second_sku, "quantity": 1},
        timeout=10,
    )
    if st_add != 200 or len(body_add.get("items", [])) != 2:
        return False, f"POST /api/carts/{cart_id}/items failed: status={st_add} body={body_add}"
    total_cents = body_add.get("total_cents", 0)

    st_chk, body_chk = http_json(
        f"{APACHE_URL}/api/checkouts",
        method="POST",
        headers=auth_headers,
        payload={"run_id": run_id, "journey_id": journey_id, "cart_id": cart_id, "total_cents": total_cents},
        timeout=15,
    )
    if st_chk != 202:
        return False, f"Authenticated POST /api/checkouts failed: status={st_chk} body={body_chk}"
    order_id = body_chk["order_id"]

    http_json(f"{APACHE_URL}/api/carts/{cart_id}/complete", method="POST", headers=auth_headers, timeout=10)

    st_orders, body_orders = http_json(f"{APACHE_URL}/api/orders", headers=auth_headers, timeout=10)
    if st_orders != 200 or not any(o.get("order_id") == order_id for o in body_orders):
        return False, f"GET /api/orders did not include order {order_id}"

    # Poll GET /api/fulfillments/{order_id} until FULFILLED
    fulfilled = False
    deadline = time.time() + 15
    while time.time() < deadline:
        st_ful, body_ful = http_json(f"{APACHE_URL}/api/fulfillments/{order_id}", headers=auth_headers, timeout=10)
        if st_ful == 200 and body_ful.get("status") == "FULFILLED":
            fulfilled = True
            break
        time.sleep(0.5)
    if not fulfilled:
        return False, f"Order {order_id} did not reach FULFILLED via GET /api/fulfillments/{order_id}"

    # 7. Verify expired/invalid JWT rejection (401 INVALID_TOKEN) and Reset Password flow
    st_bad_tok, body_bad_tok = http_json(
        f"{APACHE_URL}/api/orders",
        headers={"Authorization": "Bearer invalid.expired.token"},
        timeout=10,
    )
    if st_bad_tok != 401 or body_bad_tok.get("error_code") != "INVALID_TOKEN":
        return False, f"Expected 401 INVALID_TOKEN for bad JWT, got status={st_bad_tok} body={body_bad_tok}"

    st_reset, body_reset = http_json(
        f"{APACHE_URL}/api/auth/reset-password",
        method="POST",
        payload={"email": e2e_email, "new_password": "NewE2ePassword999!"},
        timeout=10,
    )
    if st_reset != 200 or body_reset.get("status") != "PASSWORD_UPDATED":
        return False, f"POST /api/auth/reset-password failed: status={st_reset} body={body_reset}"

    st_old_login, _ = http_json(
        f"{APACHE_URL}/api/auth/login",
        method="POST",
        payload={"email": e2e_email, "password": "E2ePassword123!"},
        timeout=10,
    )
    if st_old_login != 401:
        return False, f"Old password unexpectedly succeeded after reset: status={st_old_login}"

    st_new_login, body_new_login = http_json(
        f"{APACHE_URL}/api/auth/login",
        method="POST",
        payload={"email": e2e_email, "password": "NewE2ePassword999!"},
        timeout=10,
    )
    if st_new_login != 200 or not body_new_login.get("access_token"):
        return False, f"Login with new password failed after reset: status={st_new_login}"

    return True, f"SPA + JWT auth + reset-password + 3 products + 2-item cart + checkout ({order_id}) + FULFILLED loop verified"


def main():
    os.makedirs(REPORT_DIR, exist_ok=True)
    report = {
        "started_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "gates": {},
        "journeys": [],
        "overall_passed": True,
    }

    def record_gate(gate_name, passed, details):
        print(f"[{'PASS' if passed else 'FAIL'}] {gate_name}: {details}", flush=True)
        report["gates"][gate_name] = {"passed": passed, "details": details}
        if not passed:
            report["overall_passed"] = False

    # Gate 3: Scheduling & Route Rejection
    ok, msg = verify_scheduling_gate()
    record_gate("Gate 3 - Scheduling & Route Guard", ok, msg)

    # Gate 7: RabbitMQ Topology
    ok, msg = verify_rabbitmq_topology()
    record_gate("Gate 7 - RabbitMQ Topology & Durability", ok, msg)

    # Gate 8: MySQL Integrity & Schema Separation
    ok, msg = verify_mysql_isolation()
    record_gate("Gate 8 - MySQL Schema Separation & Flyway", ok, msg)

    known_journey_ids = set()
    known_trace_ids = set()

    # Gate 4 & Gate 5: Normal-Flow Stability (30 consecutive NORMAL journeys)
    print(f"Running {NORMAL_STABILITY_COUNT} consecutive NORMAL journeys...", flush=True)
    normal_run_id = str(uuid.uuid4())
    normal_passed = True
    normal_failure_reason = ""

    for i in range(NORMAL_STABILITY_COUNT):
        j_id = str(uuid.uuid4())
        # On the last iteration, pass SCENARIO_NAME=DB_TIMEOUT with SCENARIO_ENABLED=false
        # to verify Section 14 requirement that SCENARIO_ENABLED=false forces NORMAL path.
        scen_name = "DB_TIMEOUT" if i == (NORMAL_STABILITY_COUNT - 1) else "NORMAL"
        res = trigger_journey(normal_run_id, j_id, scen_name, False)
        trace_id = res.get("trace_id", "")
        known_journey_ids.add(j_id)
        if trace_id:
            known_trace_ids.add(trace_id)

        if res.get("http_status") != 202:
            normal_passed = False
            normal_failure_reason = f"Iteration {i + 1} returned HTTP {res.get('http_status')}: {res}"
            break

        cart_row = query_one(
            MYSQL_CART_USER, MYSQL_CART_PASSWORD, "cart_db",
            "SELECT cart_id, status FROM cart WHERE journey_id = %s", (j_id,)
        )
        order_row = query_one(
            MYSQL_CHECKOUT_USER, MYSQL_CHECKOUT_PASSWORD, "checkout_db",
            "SELECT order_id, status FROM orders WHERE journey_id = %s", (j_id,)
        )
        fulfill_row = wait_for_fulfillment(j_id, timeout_sec=8.0)

        if not cart_row or cart_row["status"] != "CART_READ":
            normal_passed = False
            normal_failure_reason = f"Iteration {i + 1} invalid cart row: {cart_row}"
            break
        if not order_row or order_row["status"] != "PUBLISHED":
            normal_passed = False
            normal_failure_reason = f"Iteration {i + 1} invalid order row: {order_row}"
            break
        if not fulfill_row or fulfill_row["status"] != "FULFILLED":
            normal_passed = False
            normal_failure_reason = f"Iteration {i + 1} missing/invalid fulfillment row: {fulfill_row}"
            break

        # Validate Jaeger trace on sample normal journeys (first, middle, last) to keep runtime fast while thorough
        if i in (0, NORMAL_STABILITY_COUNT // 2, NORMAL_STABILITY_COUNT - 1):
            req_svcs = {"journey-generator", "api-gateway", "cart-service", "checkout-service", "merchant-service", "fulfillment-service"}
            trace_obj = fetch_jaeger_trace(trace_id, required_services=req_svcs)
            t_ok, t_msg = validate_trace_structure(trace_obj, req_svcs)
            if not t_ok:
                normal_passed = False
                normal_failure_reason = f"Iteration {i + 1} trace {trace_id} invalid: {t_msg}"
                break

        report["journeys"].append({
            "scenario": "NORMAL",
            "iteration": i + 1,
            "run_id": normal_run_id,
            "journey_id": j_id,
            "trace_id": trace_id,
            "expected_http_status": 202,
            "observed_http_status": res.get("http_status"),
            "passed": True,
        })

    ready_q, unacked_q = get_queue_messages("commerce.fulfillment")
    if normal_passed and (ready_q != 0 or unacked_q != 0):
        normal_passed = False
        normal_failure_reason = f"Queue commerce.fulfillment not clean after normal run: ready={ready_q}, unacked={unacked_q}"

    record_gate(
        f"Gate 4 & 5 - Normal-Flow Stability ({NORMAL_STABILITY_COUNT} Journeys) & Distributed Tracing",
        normal_passed,
        f"Completed {NORMAL_STABILITY_COUNT}/{NORMAL_STABILITY_COUNT} normal journeys with end-to-end DB, AMQP, and Jaeger trace coherence"
        if normal_passed else normal_failure_reason,
    )

    # Gate 6 & Gate 9: Deterministic Failure Scenarios (3 runs each)
    with open("/app/test/scenarios/scenarios.json", "r", encoding="utf-8") as f:
        scenarios_spec = json.load(f)["scenarios"]

    all_scenarios_passed = True
    scenario_summary_msgs = []

    for sc in scenarios_spec:
        sc_name = sc["name"]
        if sc_name == "NORMAL":
            continue
        sc_run_id = str(uuid.uuid4())
        for run_idx in range(SCENARIO_REPEAT_COUNT):
            j_id = str(uuid.uuid4())
            dlq_before, _ = get_queue_messages("commerce.fulfillment.dlq")

            res = trigger_journey(sc_run_id, j_id, sc_name, True)
            trace_id = res.get("trace_id", "")
            known_journey_ids.add(j_id)
            if trace_id:
                known_trace_ids.add(trace_id)

            obs_status = res.get("http_status")
            exp_status = sc["expected_http_status"]
            passed_iter = (obs_status == exp_status)
            iter_notes = []

            if not passed_iter:
                iter_notes.append(f"HTTP status {obs_status} != expected {exp_status}")

            if sc.get("expected_error_code") and res.get("error_code") != sc["expected_error_code"]:
                passed_iter = False
                iter_notes.append(f"error_code {res.get('error_code')} != {sc['expected_error_code']}")

            if sc.get("min_duration_ms") and res.get("duration_ms", 0) < sc["min_duration_ms"]:
                passed_iter = False
                iter_notes.append(f"duration_ms {res.get('duration_ms')} < {sc['min_duration_ms']}")

            order_row = query_one(
                MYSQL_CHECKOUT_USER, MYSQL_CHECKOUT_PASSWORD, "checkout_db",
                "SELECT order_id, status FROM orders WHERE journey_id = %s", (j_id,)
            )
            exp_order_status = sc.get("expected_order_status")
            if exp_order_status is None:
                if order_row is not None:
                    passed_iter = False
                    iter_notes.append(f"Unexpected order row present: {order_row}")
            else:
                if not order_row or order_row["status"] != exp_order_status:
                    passed_iter = False
                    iter_notes.append(f"Order status {order_row} != {exp_order_status}")

            if sc_name == "DB_SLOW":
                fulfill_row = wait_for_fulfillment(j_id, timeout_sec=8.0)
                if not fulfill_row or fulfill_row["status"] != "FULFILLED":
                    passed_iter = False
                    iter_notes.append("DB_SLOW did not complete fulfillment")
                req_svcs = {"journey-generator", "api-gateway", "cart-service", "checkout-service", "merchant-service", "fulfillment-service"}
                trace_obj = fetch_jaeger_trace(trace_id, required_services=req_svcs)
                t_ok, t_msg = validate_trace_structure(trace_obj, req_svcs, expect_error=False)
            elif sc_name == "FULFILLMENT_ERROR":
                # Wait for async fulfillment listener to process and NACK to DLQ (RabbitMQ stats refresh every ~5s)
                dlq_after = wait_for_dlq_increment(dlq_before, timeout_sec=12.0)
                fulfill_row = query_one(
                    MYSQL_FULFILLMENT_USER, MYSQL_FULFILLMENT_PASSWORD, "fulfillment_db",
                    "SELECT fulfillment_id, status FROM fulfillment WHERE journey_id = %s", (j_id,)
                )
                if fulfill_row is not None:
                    passed_iter = False
                    iter_notes.append(f"FULFILLMENT_ERROR unexpectedly persisted fulfillment: {fulfill_row}")
                if dlq_after <= dlq_before:
                    passed_iter = False
                    iter_notes.append(f"DLQ count did not increase ({dlq_before} -> {dlq_after})")
                req_svcs = {"journey-generator", "api-gateway", "cart-service", "checkout-service", "merchant-service", "fulfillment-service"}
                trace_obj = fetch_jaeger_trace(trace_id, required_services=req_svcs)
                t_ok, t_msg = validate_trace_structure(trace_obj, req_svcs, expect_error=True)
            else:
                req_svcs = {"journey-generator", "api-gateway", "cart-service", "checkout-service"}
                trace_obj = fetch_jaeger_trace(trace_id, required_services=req_svcs)
                t_ok, t_msg = validate_trace_structure(
                    trace_obj,
                    req_svcs,
                    absent_services=sc.get("absent_services", []),
                    expect_error=True,
                )

            if not t_ok:
                passed_iter = False
                iter_notes.append(t_msg)

            report["journeys"].append({
                "scenario": sc_name,
                "iteration": run_idx + 1,
                "run_id": sc_run_id,
                "journey_id": j_id,
                "trace_id": trace_id,
                "expected_http_status": exp_status,
                "observed_http_status": obs_status,
                "passed": passed_iter,
                "notes": iter_notes,
            })
            if not passed_iter:
                all_scenarios_passed = False
                scenario_summary_msgs.append(f"{sc_name}[{run_idx + 1}]: {'; '.join(iter_notes)}")

    record_gate(
        f"Gate 6 & 9 - Deterministic Fault Scenarios (6x{SCENARIO_REPEAT_COUNT}) & Jaeger Error Assertions",
        all_scenarios_passed,
        f"All 6 failure scenarios x {SCENARIO_REPEAT_COUNT} runs verified functionally and in Jaeger"
        if all_scenarios_passed else "Failures: " + " | ".join(scenario_summary_msgs),
    )

    # Gate 11: OpenTelemetry Metrics Validation
    m_ok, m_msg = verify_otel_metrics()
    record_gate("Gate 11 - OpenTelemetry Metrics (Runtime & Custom Business Metrics)", m_ok, m_msg)

    # Gate 12: OpenTelemetry Logs Validation
    l_ok, l_msg = verify_otel_logs(known_journey_ids, known_trace_ids)
    record_gate("Gate 12 - OpenTelemetry Logs (OTLP LogRecords + Trace/MDC Correlation)", l_ok, l_msg)

    # Gate 13: v2.0 SPA Storefront, JWT Auth, Catalog, Cart, Checkout & Fulfillment Loop
    v2_ok, v2_msg = verify_v2_spa_and_auth()
    record_gate("Gate 13 - v2.0 SPA, JWT Auth, 3-Product Catalog, Cart & Fulfillment Loop", v2_ok, v2_msg)

    report["completed_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())

    report_json_path = os.path.join(REPORT_DIR, "e2e-test-report.json")
    report_txt_path = os.path.join(REPORT_DIR, "e2e-test-summary.txt")

    with open(report_json_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)

    with open(report_txt_path, "w", encoding="utf-8") as f:
        f.write("Commerce Synthetic Application - E2E Verification Summary\n")
        f.write("=========================================================\n")
        f.write(f"Started:   {report['started_at']}\n")
        f.write(f"Completed: {report['completed_at']}\n")
        f.write(f"Overall:   {'PASS' if report['overall_passed'] else 'FAIL'}\n\n")
        for g_name, g_val in report["gates"].items():
            f.write(f"[{'PASS' if g_val['passed'] else 'FAIL'}] {g_name}: {g_val['details']}\n")

    print(f"\nReport written to {report_json_path} and {report_txt_path}", flush=True)
    sys.exit(0 if report["overall_passed"] else 1)


if __name__ == "__main__":
    main()
