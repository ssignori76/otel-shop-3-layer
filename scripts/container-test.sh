#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-all}"
export DOCKER_BUILDKIT=1

run_unit_tests() {
  echo "[unit-test] Running Maven unit tests and JaCoCo coverage inside container..."
  docker build \
    -f docker/java-service/Dockerfile \
    --target unit-test \
    --progress=plain \
    -t commerce/unit-test-verify:2.0.0 \
    .
}

run_integration_tests() {
  echo "[integration-test] Running containerized integration checks..."
  ./scripts/compose-up.sh test
  ./scripts/wait-ready.sh
  docker compose -f compose.yaml -f compose.test.yaml exec -T mysql \
    sh -c 'mysql -h 127.0.0.1 -ucart_app -pcart_local_dev_only -e "SELECT 1" cart_db'
  docker compose -f compose.yaml -f compose.test.yaml exec -T rabbitmq \
    rabbitmq-diagnostics -q check_port_connectivity
  echo "[integration-test] MySQL and RabbitMQ container contracts verified."
}

run_e2e_tests() {
  echo "[e2e-test] Running full end-to-end test suite (Traces + Metrics + Logs)..."
  mkdir -p test/reports
  chmod 777 test/reports
  REMOVE_VOLUMES=true ./scripts/compose-down.sh
  ./scripts/compose-up.sh test
  ./scripts/wait-ready.sh
  docker compose -f compose.yaml -f compose.test.yaml --profile e2e run --rm test-runner
  ./scripts/verify-jaeger.sh
}

case "${MODE}" in
  unit)
    run_unit_tests
    ;;
  integration)
    run_integration_tests
    ;;
  e2e)
    run_e2e_tests
    ;;
  all)
    run_unit_tests
    ./scripts/container-build.sh
    run_e2e_tests
    ;;
  *)
    echo "Usage: $0 {unit|integration|e2e|all}" >&2
    exit 1
    ;;
esac
