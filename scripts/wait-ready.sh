#!/usr/bin/env bash
set -euo pipefail

TIMEOUT_SECONDS="${WAIT_TIMEOUT_SECONDS:-120}"
START_TS="$(date +%s)"

CONTAINERS=(
  "commerce-mysql"
  "commerce-rabbitmq"
  "commerce-auth-service"
  "commerce-cart-service"
  "commerce-merchant-service"
  "commerce-checkout-service"
  "commerce-fulfillment-service"
  "commerce-api-gateway"
  "commerce-apache-web"
  "commerce-journey-generator"
)

if docker ps --format '{{.Names}}' | grep -q '^commerce-jaeger$'; then
  CONTAINERS+=("commerce-jaeger" "commerce-otel-collector")
fi

echo "[wait-ready] Waiting up to ${TIMEOUT_SECONDS}s for all containers to report healthy..."

for cname in "${CONTAINERS[@]}"; do
  while true; do
    now="$(date +%s)"
    elapsed=$((now - START_TS))
    if [ "${elapsed}" -ge "${TIMEOUT_SECONDS}" ]; then
      echo "[ERROR] Timeout waiting for ${cname} to become healthy after ${elapsed}s" >&2
      docker ps -a
      exit 1
    fi

    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${cname}" 2>/dev/null || echo 'missing')"
    if [ "${status}" = "healthy" ] || [ "${status}" = "running" ]; then
      if [ "${status}" = "healthy" ]; then
        echo "[wait-ready] ${cname} is healthy (${elapsed}s)"
        break
      fi
    fi
    sleep 2
  done
done

echo "[wait-ready] All required services are healthy."
