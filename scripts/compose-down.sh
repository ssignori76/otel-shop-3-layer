#!/usr/bin/env bash
set -euo pipefail

DOWN_ARGS="--remove-orphans"
if [ "${REMOVE_VOLUMES:-false}" = "true" ]; then
  DOWN_ARGS="-v --remove-orphans"
fi

docker compose -f compose.yaml -f compose.dev.yaml --profile e2e down ${DOWN_ARGS}
docker compose -f compose.yaml -f compose.prod.yaml down ${DOWN_ARGS} 2>/dev/null || true
docker compose -f compose.yaml -f compose.prod-otel.yaml down ${DOWN_ARGS} 2>/dev/null || true
