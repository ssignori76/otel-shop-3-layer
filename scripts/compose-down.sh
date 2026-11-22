#!/usr/bin/env bash
set -euo pipefail

if [ "${REMOVE_VOLUMES:-false}" = "true" ]; then
  docker compose -f compose.yaml -f compose.test.yaml --profile e2e down -v --remove-orphans
else
  docker compose -f compose.yaml -f compose.test.yaml --profile e2e down --remove-orphans
fi
