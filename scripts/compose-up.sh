#!/usr/bin/env bash
set -euo pipefail

PROFILE="${1:-default}"

if [ "${PROFILE}" = "test" ]; then
  docker compose -f compose.yaml -f compose.test.yaml up -d
elif [ "${PROFILE}" = "dynatrace" ]; then
  docker compose -f compose.yaml -f compose.dynatrace.yaml up -d
else
  docker compose -f compose.yaml up -d
fi
