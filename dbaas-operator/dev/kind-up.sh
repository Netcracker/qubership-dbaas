#!/usr/bin/env bash
# kind-up.sh — spins up a full local test environment for dbaas-operator in kind.
#
# Steps:
#   1. Create a kind cluster (skipped if already exists)
#   2. Install CRDs via make install
#   3. Build Docker images for the operator and aggregator-mock
#   4. Load images into kind (no registry needed)
#   5. Apply k8s manifests: namespace dbaas-system, aggregator-mock, operator
#   6. Apply test resources: namespace test-ns, secret pg-credentials
#   7. Wait for both deployments to roll out
#
# Usage:
#   ./dev/kind-up.sh                              # cluster "dbaas", Basic Auth (default)
#   KIND_CLUSTER=my-cluster ./dev/kind-up.sh
#   KUBERNETES_M2M_ENABLED=true ./dev/kind-up.sh  # use M2M (projected SA token) instead
#
# Auth mode: by default the operator authenticates to the aggregator-mock with HTTP
# Basic Auth as the dbaas-operator user (matching the production default). Set
# KUBERNETES_M2M_ENABLED=true to switch it to the M2M Bearer-token path. The mock
# accepts either, so no mock reconfiguration is needed.
#
# After the script completes:
#   kubectl apply -f dev/test-resources/edb-with-secret.yaml
#   kubectl get externaldatabase -n test-ns my-postgres -w
#
# To tear down: ./dev/kind-down.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

KIND_CLUSTER="${KIND_CLUSTER:-dbaas}"
OPERATOR_IMAGE="dbaas-operator:dev"
MOCK_IMAGE="aggregator-mock:dev"
# Aggregator auth mode for the operator: false (default) → HTTP Basic Auth as the
# dbaas-operator user; true → M2M projected SA token. Exported for envsubst below.
export KUBERNETES_M2M_ENABLED="${KUBERNETES_M2M_ENABLED:-false}"

# ── Colors ────────────────────────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()    { echo -e "${GREEN}[kind-up]${NC} $*"; }
warn()    { echo -e "${YELLOW}[kind-up]${NC} $*"; }
die()     { echo -e "${RED}[kind-up] ERROR${NC} $*" >&2; exit 1; }

# ── Prerequisites ─────────────────────────────────────────────────────────────
for cmd in kind kubectl docker make envsubst; do
  command -v "$cmd" &>/dev/null || die "'$cmd' not found. Please install it and retry."
done

# ── 1. Kind cluster ───────────────────────────────────────────────────────────
if kind get clusters 2>/dev/null | grep -qx "${KIND_CLUSTER}"; then
  warn "Cluster '${KIND_CLUSTER}' already exists — skipping creation."
else
  info "Creating kind cluster '${KIND_CLUSTER}'..."
  kind create cluster --name "${KIND_CLUSTER}"
fi

kubectl config use-context "kind-${KIND_CLUSTER}" &>/dev/null || \
  die "Failed to switch kubectl context to kind-${KIND_CLUSTER}"

info "Cluster ready: $(kubectl cluster-info --context "kind-${KIND_CLUSTER}" 2>&1 | head -1)"

# ── 2. CRDs ───────────────────────────────────────────────────────────────────
info "Installing CRDs..."
(cd "${REPO_ROOT}" && make install)

# ── 3. Build Docker images ────────────────────────────────────────────────────
info "Building operator image (${OPERATOR_IMAGE})..."
docker build -t "${OPERATOR_IMAGE}" "${REPO_ROOT}"

info "Building aggregator-mock image (${MOCK_IMAGE})..."
docker build \
  -f "${REPO_ROOT}/dev/aggregator-mock/Dockerfile" \
  -t "${MOCK_IMAGE}" \
  "${REPO_ROOT}"

# ── 4. Load images into kind ──────────────────────────────────────────────────
info "Loading images into cluster '${KIND_CLUSTER}'..."
kind load docker-image "${OPERATOR_IMAGE}" --name "${KIND_CLUSTER}"
kind load docker-image "${MOCK_IMAGE}"     --name "${KIND_CLUSTER}"

# ── 5. Infrastructure manifests ───────────────────────────────────────────────
info "Applying infrastructure manifests (operator auth mode: KUBERNETES_M2M_ENABLED=${KUBERNETES_M2M_ENABLED})..."
kubectl apply -f "${REPO_ROOT}/dev/k8s/mock-aggregator.yaml"
# Substitute only KUBERNETES_M2M_ENABLED so the operator manifest picks up the chosen
# auth mode; all other content is passed through untouched.
envsubst '${KUBERNETES_M2M_ENABLED}' < "${REPO_ROOT}/dev/k8s/operator.yaml" | kubectl apply -f -

# ── 6. Test resources ─────────────────────────────────────────────────────────
info "Applying test resources (namespace test-ns, NamespaceBinding, secret)..."
kubectl apply -f "${REPO_ROOT}/dev/test-resources/namespace.yaml"
# NamespaceBinding must exist before the operator will reconcile CRs in test-ns
# (the namespace must be claimed for this operator). Applied after the namespace.
kubectl apply -f "${REPO_ROOT}/dev/test-resources/namespacebinding.yaml"
# Namespaced Secret RBAC for test-ns: the operator holds no cluster-wide Secret access, so each
# business namespace must grant it via a Role + RoleBinding — applied alongside the
# NamespaceBinding, exactly as a real onboarding would. (The operator's own namespace is covered
# by the Role in dev/k8s/operator.yaml.)
kubectl apply -f "${REPO_ROOT}/dev/test-resources/secret-rbac.yaml"
kubectl apply -f "${REPO_ROOT}/dev/test-resources/secret.yaml"

# ── 7. Wait for rollouts ──────────────────────────────────────────────────────
info "Waiting for aggregator-mock to be ready..."
kubectl rollout status deployment/dbaas-aggregator -n dbaas-system --timeout=120s

info "Waiting for dbaas-operator to be ready..."
kubectl rollout status deployment/dbaas-operator  -n dbaas-system --timeout=120s

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  Environment ready!${NC}"
echo -e "${GREEN}════════════════════════════════════════════════════${NC}"
echo ""
echo "Pods in dbaas-system:"
kubectl get pods -n dbaas-system
echo ""
echo "Next steps:"
echo "  # Test 1 — happy path (secret exists, mock returns 200):"
echo "  kubectl apply -f dev/test-resources/edb-with-secret.yaml"
echo "  kubectl get externaldatabase -n test-ns my-postgres -w"
echo ""
echo "  # Test 2 — BackingOff (secret missing):"
echo "  kubectl apply -f dev/test-resources/edb-missing-secret.yaml"
echo "  kubectl get externaldatabase -n test-ns missing-secret-test -w"
echo ""
echo "  # Operator logs:"
echo "  kubectl logs -n dbaas-system deployment/dbaas-operator -f"
echo ""
echo "  # Tear down:"
echo "  ./dev/kind-down.sh"
