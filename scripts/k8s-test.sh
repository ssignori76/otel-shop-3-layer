#!/usr/bin/env bash
set -euo pipefail

export PATH="${HOME}/.local/bin:${PATH}"
./scripts/k8s-ensure-tools.sh

CLUSTER_NAME="${KIND_CLUSTER_NAME:-commerce-synthetic}"

echo "[k8s-test] Step 1a: Validating Kustomize rendering for prod overlay (OneAgent, zero local observability)..."
PROD_RENDER="$(kubectl kustomize k8s/overlays/prod)"
for forbidden in "commerce-observability" "jaeger" "victoria-metrics" "victoria-logs" "fluent-bit" "dynatrace-otel-collector"; do
  if echo "${PROD_RENDER}" | grep -q "${forbidden}"; then
    echo "[ERROR] prod overlay must not contain '${forbidden}'!" >&2
    exit 1
  fi
done
echo "[k8s-test] Verified prod overlay renders cleanly with 0 local observability resources."

echo "[k8s-test] Step 1b: Validating Kustomize rendering for prod-otel overlay (official Dynatrace OTel Collector + cumulative_to_delta)..."
PROD_OTEL_RENDER="$(kubectl kustomize k8s/overlays/prod-otel)"
for forbidden in "commerce-observability" "jaeger" "victoria-metrics" "victoria-logs" "fluent-bit"; do
  if echo "${PROD_OTEL_RENDER}" | grep -q "${forbidden}"; then
    echo "[ERROR] prod-otel overlay must not contain '${forbidden}'!" >&2
    exit 1
  fi
done
for required in "ghcr.io/dynatrace/dynatrace-otel-collector/dynatrace-otel-collector:0.60.0" "cumulative_to_delta" "otlp_http/dynatrace" "OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE"; do
  if ! echo "${PROD_OTEL_RENDER}" | grep -q "${required}"; then
    echo "[ERROR] prod-otel overlay missing required element '${required}'!" >&2
    exit 1
  fi
done
echo "[k8s-test] Verified prod-otel overlay renders cleanly with official Dynatrace OTel Collector."

echo "[k8s-test] Step 2: Rebuilding test-runner image and ensuring kind cluster (dev) is up..."
docker build -f test/Dockerfile -t "${IMAGE_REGISTRY:-commerce}/test-runner:${APP_VERSION:-2.0.0}" .
./scripts/k8s-up.sh dev

echo "[k8s-test] Step 3: Launching E2E verification Job in namespace commerce-observability..."
kubectl -n commerce-observability delete job commerce-e2e-test-runner --ignore-not-found=true
kubectl apply -f k8s/overlays/dev/test-runner-job.yaml

echo "[k8s-test] Waiting for test-runner pod to start..."
kubectl -n commerce-observability wait --for=condition=Ready pod -l job-name=commerce-e2e-test-runner --timeout=60s || true

echo "[k8s-test] Streaming test-runner logs..."
kubectl -n commerce-observability logs -f job/commerce-e2e-test-runner

echo "[k8s-test] Checking Job completion status..."
kubectl -n commerce-observability wait --for=condition=complete job/commerce-e2e-test-runner --timeout=30s
echo "[k8s-test] Kubernetes E2E verification PASSED."
