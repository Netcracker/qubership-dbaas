#!/usr/bin/env bash
#
# e2e-extra-keys.sh — end-to-end check for arbitrary top-level classifier fields
# (spec.classifier.extraKeys) against a running kind cluster.
#
# Prerequisite: the dev environment is up (./dev/kind-up.sh). The mock's
# changed.json and get-by-classifier.json already carry the "extra-svc" entries
# this test relies on, so a fresh `kind-up.sh` is enough — no extra wiring.
#
# It applies dev/test-resources/dsc-extra-keys.yaml and asserts the three places
# where the CR's extraKeys-envelope and the aggregator's FLAT classifier must align:
#
#   1. The DatabaseSecretClaim reaches Phase=Succeeded (get-by-classifier matched,
#      Secret created).
#   2. The Secret's metadata.json descriptor carries the extra field FLAT on the
#      classifier top level (region=eu) with no "extraKeys" envelope on the wire.
#   3. The rotation poller stamps the rotation-trigger annotation on the CR —
#      proving it reverse-mapped the flat changed.json classifier back through
#      ExtraKeys and matched this CR by index key (the round-trip that would
#      otherwise silently drop the extra field and lose the rotation).
#
# Usage:
#   ./dev/kind-up.sh
#   ./dev/e2e-extra-keys.sh
#
# Override the kept-resource cleanup with KEEP=1 to leave the CR in place.
set -euo pipefail

NS="test-ns"
CR="dsc-extra-keys"
SECRET="dsc-extra-keys-secret"
ANN_KEY='dbaas\.netcracker\.com/rotation-trigger'
ROTATION_TIMEOUT="${ROTATION_TIMEOUT:-120}" # seconds; poller default interval is 30s
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✗ %s\033[0m\n' "$1"; exit 1; }
info() { printf '\033[1m%s\033[0m\n' "$1"; }

for bin in kubectl jq base64; do
  command -v "$bin" >/dev/null 2>&1 || fail "required tool not found: $bin"
done

info "0. Preconditions"
kubectl get ns "$NS" >/dev/null 2>&1 || fail "namespace $NS not found — run ./dev/kind-up.sh first"
kubectl -n dbaas-system get deploy/dbaas-operator >/dev/null 2>&1 || fail "dbaas-operator not deployed — run ./dev/kind-up.sh first"
pass "kind dev environment is up"

info "1. Apply the extraKeys DatabaseSecretClaim"
kubectl apply -f "$SCRIPT_DIR/test-resources/dsc-extra-keys.yaml" >/dev/null
if kubectl wait --for=jsonpath='{.status.phase}'=Succeeded \
    "databasesecretclaim/$CR" -n "$NS" --timeout=90s >/dev/null 2>&1; then
  pass "DatabaseSecretClaim reached Phase=Succeeded"
else
  phase=$(kubectl get "databasesecretclaim/$CR" -n "$NS" -o jsonpath='{.status.phase}' 2>/dev/null || echo "?")
  fail "CR did not reach Succeeded (phase=$phase)"
fi

info "2. Secret descriptor carries the extra field FLAT on the classifier"
kubectl get "secret/$SECRET" -n "$NS" >/dev/null 2>&1 || fail "Secret $SECRET was not created"
meta="$(kubectl get "secret/$SECRET" -n "$NS" -o jsonpath='{.data.metadata\.json}' | base64 -d)"
echo "    metadata.json .classifier = $(echo "$meta" | jq -c '.classifier')"
echo "$meta" | jq -e '.classifier.region == "eu"' >/dev/null \
  || fail "classifier.region != \"eu\" — extra field not flattened onto the top level"
pass "classifier.region == \"eu\" (flattened to the top level)"
echo "$meta" | jq -e '.classifier | has("extraKeys") | not' >/dev/null \
  || fail "classifier still carries an \"extraKeys\" envelope — must be flat on the wire"
pass "no \"extraKeys\" envelope on the wire classifier"
echo "$meta" | jq -e '.classifier.microserviceName == "extra-svc" and .classifier.scope == "service" and .classifier.namespace == "test-ns"' >/dev/null \
  || fail "identity scalars missing/incorrect in the descriptor"
pass "identity scalars present (microserviceName/scope/namespace)"

info "3. Rotation poller matches the extraKeys classifier (reverse round-trip)"
echo "    waiting up to ${ROTATION_TIMEOUT}s for the rotation-trigger annotation…"
deadline=$((SECONDS + ROTATION_TIMEOUT))
trigger=""
while [ "$SECONDS" -lt "$deadline" ]; do
  trigger="$(kubectl get "databasesecretclaim/$CR" -n "$NS" \
    -o jsonpath="{.metadata.annotations.$ANN_KEY}" 2>/dev/null || true)"
  [ -n "$trigger" ] && break
  sleep 5
done
[ -n "$trigger" ] \
  || fail "rotation-trigger annotation never set — poller did NOT match the extraKeys CR (the flat changed.json classifier was not reverse-mapped into ExtraKeys)"
pass "rotation-trigger annotation set (trigger=$trigger) — poller matched via the round-trip"

info "RESULT: PASS — extraKeys works end-to-end (get-by-classifier, descriptor, rotation)"

if [ "${KEEP:-0}" != "1" ]; then
  kubectl delete -f "$SCRIPT_DIR/test-resources/dsc-extra-keys.yaml" --ignore-not-found >/dev/null 2>&1 || true
  kubectl delete "secret/$SECRET" -n "$NS" --ignore-not-found >/dev/null 2>&1 || true
fi
