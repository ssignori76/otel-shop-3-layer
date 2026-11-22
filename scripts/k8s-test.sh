#!/usr/bin/env bash
set -euo pipefail

export PATH="${HOME}/.local/bin:${PATH}"
./scripts/k8s-ensure-tools.sh

CLUSTER_NAME="${KIND_CLUSTER_NAME:-commerce-synthetic}"

echo "[k8s-test] Step 1: Validating Kustomize rendering for dynatrace-ready overlay (no local observability)..."
DT_RENDER="$(kubectl kustomize k8s/overlays/dynatrace-ready)"
for forbidden in "commerce-observability" "jaeger" "victoria-metrics" "victoria-logs" "fluent-bit"; do
  if echo "${DT_RENDER}" | grep -q "${forbidden}"; then
    echo "[ERROR] dynatrace-ready overlay must not contain '${forbidden}'!" >&2
    exit 1
  fi
done
echo "[k8s-test] Verified dynatrace-ready overlay renders cleanly with 0 local observability resources."

echo "[k8s-test] Step 2: Rebuilding test-runner image and ensuring kind cluster is up..."
docker build -f test/Dockerfile -t "${IMAGE_REGISTRY:-commerce}/test-runner:${APP_VERSION:-1.0.0}" .
./scripts/k8s-up.sh local-observability

echo "[k8s-test] Step 3: Launching E2E verification Job in namespace commerce-observability..."
kubectl -n commerce-observability delete job commerce-e2e-test-runner --ignore-not-found=true
kubectl apply -f k8s/overlays/local-observability/test-runner-job.yaml

echo "[k8s-test] Waiting for test-runner pod to start..."
kubectl -n commerce-observability wait --for=condition=Ready pod -l job-name=commerce-e2e-test-runner --timeout=60s || true

echo "[k8s-test] Streaming test-runner logs..."
kubectl -n commerce-observability logs -f job/commerce-e2e-test-runner

echo "[k8s-test] Checking Job completion status..."
kubectl -n commerce-observability wait --for=condition=complete job/commerce-e2e-test-runner --timeout=30s
echo "[k8s-test] Kubernetes E2E verification PASSED."
