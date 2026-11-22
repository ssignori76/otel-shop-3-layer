#!/usr/bin/env bash
set -euo pipefail

BIN_DIR="${HOME}/.local/bin"
KIND_VERSION="${KIND_VERSION:-v0.25.0}"
KUBECTL_VERSION="${KUBECTL_VERSION:-v1.31.3}"

mkdir -p "${BIN_DIR}"
export PATH="${BIN_DIR}:${PATH}"

ARCH="$(uname -m)"
case "${ARCH}" in
  x86_64)  DL_ARCH="amd64" ;;
  aarch64) DL_ARCH="arm64" ;;
  *)
    echo "[ERROR] Unsupported architecture: ${ARCH}" >&2
    exit 1
    ;;
esac

if ! command -v kind >/dev/null 2>&1; then
  echo "[k8s-tools] Installing kind ${KIND_VERSION} into ${BIN_DIR}/kind..."
  curl -fsSL -o "${BIN_DIR}/kind" \
    "https://kind.sigs.k8s.io/dl/${KIND_VERSION}/kind-linux-${DL_ARCH}"
  chmod +x "${BIN_DIR}/kind"
else
  echo "[k8s-tools] kind already installed: $(command -v kind)"
fi

if ! command -v kubectl >/dev/null 2>&1; then
  echo "[k8s-tools] Installing kubectl ${KUBECTL_VERSION} into ${BIN_DIR}/kubectl..."
  curl -fsSL -o "${BIN_DIR}/kubectl" \
    "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${DL_ARCH}/kubectl"
  curl -fsSL -o "/tmp/kubectl.sha256" \
    "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${DL_ARCH}/kubectl.sha256"
  echo "$(cat /tmp/kubectl.sha256)  ${BIN_DIR}/kubectl" | sha256sum -c -
  rm -f /tmp/kubectl.sha256
  chmod +x "${BIN_DIR}/kubectl"
else
  echo "[k8s-tools] kubectl already installed: $(command -v kubectl)"
fi

kind version
kubectl version --client
