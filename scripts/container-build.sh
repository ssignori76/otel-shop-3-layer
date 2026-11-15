#!/usr/bin/env bash
set -euo pipefail

APP_VERSION="${APP_VERSION:-1.0.0}"
IMAGE_REGISTRY="${IMAGE_REGISTRY:-commerce}"
GIT_REVISION="$(git rev-parse --short HEAD 2>/dev/null || echo 'local')"
BUILD_CREATED="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

export DOCKER_BUILDKIT=1

SERVICES=(
  "api-gateway:services/api-gateway:8080"
  "cart-service:services/cart-service:8081"
  "checkout-service:services/checkout-service:8082"
  "merchant-service:services/merchant-service:8083"
  "fulfillment-service:services/fulfillment-service:8084"
  "journey-generator:journey-generator:8085"
)

for entry in "${SERVICES[@]}"; do
  IFS=":" read -r name module_path port <<< "${entry}"
  echo "[build] Building ${IMAGE_REGISTRY}/${name}:${APP_VERSION} (${module_path})..."
  docker build \
    -f docker/java-service/Dockerfile \
    --build-arg MODULE_PATH="${module_path}" \
    --build-arg APP_NAME="${name}" \
    --build-arg APP_VERSION="${APP_VERSION}" \
    --build-arg GIT_REVISION="${GIT_REVISION}" \
    --build-arg BUILD_CREATED="${BUILD_CREATED}" \
    --build-arg SERVER_PORT="${port}" \
    -t "${IMAGE_REGISTRY}/${name}:${APP_VERSION}" \
    -t "${IMAGE_REGISTRY}/${name}:${GIT_REVISION}" \
    .
done

echo "[build] Building ${IMAGE_REGISTRY}/apache-web:${APP_VERSION}..."
docker build \
  -f docker/apache/Dockerfile \
  --build-arg APP_VERSION="${APP_VERSION}" \
  --build-arg GIT_REVISION="${GIT_REVISION}" \
  --build-arg BUILD_CREATED="${BUILD_CREATED}" \
  -t "${IMAGE_REGISTRY}/apache-web:${APP_VERSION}" \
  -t "${IMAGE_REGISTRY}/apache-web:${GIT_REVISION}" \
  .

echo "[build] Building ${IMAGE_REGISTRY}/otel-collector:${APP_VERSION}..."
docker build \
  -f docker/otel-collector/Dockerfile \
  -t "${IMAGE_REGISTRY}/otel-collector:${APP_VERSION}" \
  .

echo "[build] Building ${IMAGE_REGISTRY}/test-runner:${APP_VERSION}..."
docker build \
  -f test/Dockerfile \
  -t "${IMAGE_REGISTRY}/test-runner:${APP_VERSION}" \
  .

echo "[build] Verifying non-root user configuration on all application images..."
for img in api-gateway cart-service checkout-service merchant-service fulfillment-service journey-generator apache-web; do
  uid="$(docker run --rm --entrypoint id "${IMAGE_REGISTRY}/${img}:${APP_VERSION}" -u)"
  if [ "${uid}" = "0" ]; then
    echo "[ERROR] Image ${IMAGE_REGISTRY}/${img}:${APP_VERSION} runs as root (UID 0)!" >&2
    exit 1
  fi
  echo "[build] Verified ${IMAGE_REGISTRY}/${img}:${APP_VERSION} runs as non-root UID=${uid}"
done
