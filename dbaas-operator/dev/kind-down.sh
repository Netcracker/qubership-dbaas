#!/usr/bin/env bash
# kind-down.sh — deletes the kind cluster created by kind-up.sh.
#
# Usage:
#   ./dev/kind-down.sh
#   KIND_CLUSTER=my-cluster ./dev/kind-down.sh

set -euo pipefail

KIND_CLUSTER="${KIND_CLUSTER:-dbaas}"

GREEN='\033[0;32m'; RED='\033[0;31m'; NC='\033[0m'
info() { echo -e "${GREEN}[kind-down]${NC} $*"; }
die()  { echo -e "${RED}[kind-down] ERROR${NC} $*" >&2; exit 1; }

command -v kind &>/dev/null || die "'kind' not found."

if ! kind get clusters 2>/dev/null | grep -qx "${KIND_CLUSTER}"; then
  info "Cluster '${KIND_CLUSTER}' does not exist — nothing to do."
  exit 0
fi

info "Deleting kind cluster '${KIND_CLUSTER}'..."
kind delete cluster --name "${KIND_CLUSTER}"
info "Done."
