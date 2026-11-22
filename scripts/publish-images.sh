#!/usr/bin/env bash
set -euo pipefail

if [ ! -f "test/reports/e2e-test-report.json" ]; then
  echo "[ERROR] Cannot publish images: test/reports/e2e-test-report.json not found. Run 'make test' first." >&2
  exit 1
fi

if ! grep -q '"overall_passed": true' test/reports/e2e-test-report.json; then
  echo "[ERROR] Cannot publish images: E2E release gates did not pass!" >&2
  exit 1
fi

APP_VERSION="${APP_VERSION:-1.0.0}"
LOCAL_REGISTRY="${IMAGE_REGISTRY:-commerce}"
PUBLISH_REGISTRY="${PUBLISH_REGISTRY:-lusig76}"
IMAGE_PREFIX="${IMAGE_PREFIX:-otel-shop-}"
GIT_REVISION="$(git rev-parse --short HEAD 2>/dev/null || echo 'local')"

IMAGES=(
  "api-gateway"
  "cart-service"
  "checkout-service"
  "merchant-service"
  "fulfillment-service"
  "journey-generator"
  "apache-web"
  "otel-collector"
  "test-runner"
)

for img in "${IMAGES[@]}"; do
  local_ref="${LOCAL_REGISTRY}/${img}:${APP_VERSION}"
  remote_ver="${PUBLISH_REGISTRY}/${IMAGE_PREFIX}${img}:${APP_VERSION}"
  remote_sha="${PUBLISH_REGISTRY}/${IMAGE_PREFIX}${img}:${GIT_REVISION}"

  if ! docker image inspect "${local_ref}" >/dev/null 2>&1; then
    echo "[ERROR] Local image ${local_ref} not found. Run 'make build' first." >&2
    exit 1
  fi

  docker tag "${local_ref}" "${remote_ver}"
  docker tag "${local_ref}" "${remote_sha}"

  if [ "${DRY_RUN:-true}" = "true" ]; then
    echo "[publish] DRY_RUN=true: tagged ${remote_ver} and ${remote_sha} (ready for push)"
  else
    echo "[publish] Pushing ${remote_ver} and ${remote_sha}..."
    docker push "${remote_ver}"
    docker push "${remote_sha}"
  fi
done
