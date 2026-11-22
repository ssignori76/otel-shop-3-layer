#!/usr/bin/env bash
set -euo pipefail

export PATH="${HOME}/.local/bin:${PATH}"
CLUSTER_NAME="${KIND_CLUSTER_NAME:-commerce-synthetic}"

if command -v kind >/dev/null 2>&1 && kind get clusters | grep -qx "${CLUSTER_NAME}"; then
  echo "[k8s-down] Deleting kind cluster '${CLUSTER_NAME}'..."
  kind delete cluster --name "${CLUSTER_NAME}"
else
  echo "[k8s-down] kind cluster '${CLUSTER_NAME}' does not exist."
fi
