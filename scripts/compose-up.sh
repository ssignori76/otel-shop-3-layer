#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-dev}"

case "${MODE}" in
  dev)
    docker compose -f compose.yaml -f compose.dev.yaml up -d
    ;;
  prod)
    docker compose -f compose.yaml -f compose.prod.yaml up -d
    ;;
  prod-otel)
    docker compose -f compose.yaml -f compose.prod-otel.yaml up -d
    ;;
  *)
    echo "Usage: $0 {dev|prod|prod-otel}" >&2
    exit 1
    ;;
esac
