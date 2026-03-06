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
#   ./hack/kind-up.sh                    # cluster name defaults to "dbaas"
#   KIND_CLUSTER=my-cluster ./hack/kind-up.sh
#
# After the script completes:
#   kubectl apply -f hack/test-resources/edb-with-secret.yaml
#   kubectl get externaldatabasedeclaration -n test-ns my-postgres -w
#
# To tear down: ./hack/kind-down.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

KIND_CLUSTER="${KIND_CLUSTER:-dbaas}"
OPERATOR_IMAGE="dbaas-operator:dev"
MOCK_IMAGE="aggregator-mock:dev"

# ── Colors ────────────────────────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()    { echo -e "${GREEN}[kind-up]${NC} $*"; }
warn()    { echo -e "${YELLOW}[kind-up]${NC} $*"; }
die()     { echo -e "${RED}[kind-up] ERROR${NC} $*" >&2; exit 1; }

# ── Prerequisites ─────────────────────────────────────────────────────────────
for cmd in kind kubectl docker make; do
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
  -f "${REPO_ROOT}/hack/aggregator-mock/Dockerfile" \
  -t "${MOCK_IMAGE}" \
  "${REPO_ROOT}"

# ── 4. Load images into kind ──────────────────────────────────────────────────
info "Loading images into cluster '${KIND_CLUSTER}'..."
kind load docker-image "${OPERATOR_IMAGE}" --name "${KIND_CLUSTER}"
kind load docker-image "${MOCK_IMAGE}"     --name "${KIND_CLUSTER}"

# ── 5. Infrastructure manifests ───────────────────────────────────────────────
info "Applying infrastructure manifests..."
kubectl apply -f "${REPO_ROOT}/hack/k8s/mock-aggregator.yaml"
kubectl apply -f "${REPO_ROOT}/hack/k8s/operator.yaml"

# ── 6. Test resources ─────────────────────────────────────────────────────────
info "Applying test resources (namespace test-ns, secret)..."
kubectl apply -f "${REPO_ROOT}/hack/test-resources/namespace.yaml"
kubectl apply -f "${REPO_ROOT}/hack/test-resources/secret.yaml"

# ── 7. Wait for rollouts ──────────────────────────────────────────────────────
info "Waiting for aggregator-mock to be ready..."
kubectl rollout status deployment/aggregator-mock -n dbaas-system --timeout=120s

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
echo "  kubectl apply -f hack/test-resources/edb-with-secret.yaml"
echo "  kubectl get externaldatabasedeclaration -n test-ns my-postgres -w"
echo ""
echo "  # Test 2 — BackingOff (secret missing):"
echo "  kubectl apply -f hack/test-resources/edb-missing-secret.yaml"
echo "  kubectl get externaldatabasedeclaration -n test-ns missing-secret-test -w"
echo ""
echo "  # Operator logs:"
echo "  kubectl logs -n dbaas-system deployment/dbaas-operator -f"
echo ""
echo "  # Tear down:"
echo "  ./hack/kind-down.sh"
