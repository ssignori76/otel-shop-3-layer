#!/usr/bin/env bash
set -euo pipefail

JAEGER_HOST_PORT="${JAEGER_UI_HOST_PORT:-16686}"
SERVICES_JSON="$(docker compose -f compose.yaml -f compose.dev.yaml exec -T jaeger wget -qO- http://127.0.0.1:16686/api/services)"

echo "[verify-jaeger] Observed services in Jaeger: ${SERVICES_JSON}"

for svc in auth-service journey-generator api-gateway cart-service checkout-service merchant-service fulfillment-service; do
  if ! echo "${SERVICES_JSON}" | grep -q "\"${svc}\""; then
    echo "[ERROR] Required service '${svc}' not found in Jaeger!" >&2
    exit 1
  fi
done

echo "[verify-jaeger] All 7 required OpenTelemetry services verified in Jaeger."
