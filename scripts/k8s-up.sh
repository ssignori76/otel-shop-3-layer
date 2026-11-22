#!/usr/bin/env bash
set -euo pipefail

export PATH="${HOME}/.local/bin:${PATH}"
./scripts/k8s-ensure-tools.sh

CLUSTER_NAME="${KIND_CLUSTER_NAME:-commerce-synthetic}"
APP_VERSION="${APP_VERSION:-1.0.0}"
IMAGE_REGISTRY="${IMAGE_REGISTRY:-commerce}"
K8S_OVERLAY="${1:-${K8S_OVERLAY:-local-observability}}"

if ! kind get clusters | grep -qx "${CLUSTER_NAME}"; then
  echo "[k8s-up] Creating kind cluster '${CLUSTER_NAME}'..."
  kind create cluster --config k8s/kind-config.yaml
else
  echo "[k8s-up] Reusing existing kind cluster '${CLUSTER_NAME}'."
fi

kubectl config use-context "kind-${CLUSTER_NAME}"

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

echo "[k8s-up] Loading local application images into kind cluster '${CLUSTER_NAME}'..."
for img in "${IMAGES[@]}"; do
  full_img="${IMAGE_REGISTRY}/${img}:${APP_VERSION}"
  if ! docker image inspect "${full_img}" >/dev/null 2>&1; then
    echo "[ERROR] Missing local image ${full_img}. Run 'make build' first." >&2
    exit 1
  fi
  kind load docker-image "${full_img}" --name "${CLUSTER_NAME}"
done

echo "[k8s-up] Applying Kustomize overlay: k8s/overlays/${K8S_OVERLAY}"
kubectl apply -k "k8s/overlays/${K8S_OVERLAY}"

echo "[k8s-up] Waiting for commerce-data StatefulSets (mysql, rabbitmq)..."
kubectl -n commerce-data rollout status statefulset/mysql --timeout=180s
kubectl -n commerce-data rollout status statefulset/rabbitmq --timeout=180s

if [ "${K8S_OVERLAY}" = "local-observability" ]; then
  echo "[k8s-up] Waiting for commerce-observability components..."
  kubectl -n commerce-observability rollout status deployment/jaeger --timeout=120s
  kubectl -n commerce-observability rollout status deployment/victoria-metrics --timeout=120s
  kubectl -n commerce-observability rollout status deployment/victoria-logs --timeout=120s
  kubectl -n commerce-observability rollout status deployment/otel-collector --timeout=120s
  kubectl -n commerce-observability rollout status daemonset/fluent-bit --timeout=120s
fi

echo "[k8s-up] Waiting for commerce-core Deployments..."
for svc in cart-service merchant-service checkout-service fulfillment-service; do
  kubectl -n commerce-core rollout status "deployment/${svc}" --timeout=180s
done

echo "[k8s-up] Waiting for commerce-edge Deployments..."
for svc in api-gateway apache-web journey-generator; do
  kubectl -n commerce-edge rollout status "deployment/${svc}" --timeout=180s
done

# Allow kube-proxy endpoints to settle after rolling updates
sleep 5

echo "[k8s-up] Cluster '${CLUSTER_NAME}' (${K8S_OVERLAY}) is ready."
kubectl get pods -A -l app.kubernetes.io/part-of=commerce-synthetic
