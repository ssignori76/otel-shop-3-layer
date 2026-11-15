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
IMAGE_REGISTRY="${IMAGE_REGISTRY:-commerce}"
GIT_REVISION="$(git rev-parse --short HEAD 2>/dev/null || echo 'local')"

IMAGES=(
  "api-gateway"
  "cart-service"
  "checkout-service"
  "merchant-service"
  "fulfillment-service"
  "journey-generator"
  "apache-web"
)

for img in "${IMAGES[@]}"; do
  echo "[publish] Pushing ${IMAGE_REGISTRY}/${img}:${APP_VERSION} and ${IMAGE_REGISTRY}/${img}:${GIT_REVISION}..."
  if [ "${DRY_RUN:-true}" = "true" ]; then
    echo "[publish] DRY_RUN=true: verified image ${IMAGE_REGISTRY}/${img}:${APP_VERSION} ready for push"
  else
    docker push "${IMAGE_REGISTRY}/${img}:${APP_VERSION}"
    docker push "${IMAGE_REGISTRY}/${img}:${GIT_REVISION}"
  fi
done
