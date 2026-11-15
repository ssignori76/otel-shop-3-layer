#!/usr/bin/env bash
set -euo pipefail

docker compose -f compose.yaml -f compose.test.yaml --profile e2e down -v --remove-orphans
