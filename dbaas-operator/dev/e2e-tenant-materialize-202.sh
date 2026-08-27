#!/usr/bin/env bash
#
# e2e-tenant-materialize-202.sh — end-to-end check that a tenant-scoped InternalDatabase
# survives an ASYNCHRONOUS tenant database creation: the aggregator answers the get-or-create
# with 202 Accepted (no credentials yet) for a while, and the operator has to keep retrying
# until the database answers for real.
#
# Prerequisite: the dev environment is up (./dev/kind-up.sh).
#
# Flow (against the aggregator-mock, rule originService=tenant-svc-202 → pendingCalls=3):
#   1. Apply idb-tenant-materialize-202.yaml + dsc-tenant-materialize-202.yaml together —
#      the same shape a service uses in production: a tenant declaration plus a claim that
#      turns it into a Secret.
#   2. While the mock answers 202, the InternalDatabase must report Phase=WaitingForDependency
#      with Ready=False/ProvisioningStarted, Stalled=False and an EMPTY status.trackingId
#      (an empty trackingId is what tells this wait apart from the async-provisioning wait).
#      The database is not recorded yet, so the claim's Secret must NOT exist.
#   3. After the pending budget is spent the mock creates the database (200); the
#      InternalDatabase reaches Succeeded and the claim writes the Secret.
#
# Before the operator accepted 202 (PR #624) step 2 ended in Phase=BackingOff and step 3 never
# happened: the Secret stayed missing and the consuming pod hung on FailedMount.
#
# Usage:
#   ./dev/kind-up.sh
#   ./dev/e2e-tenant-materialize-202.sh
#
# The mock's pending budget is per-process, so the script restarts the mock to make repeated
# runs deterministic. Override the kept-resource cleanup with KEEP=1.
set -euo pipefail

NS="test-ns"
IDB="idb-tenant-materialize-202"
DSC="dsc-tenant-materialize-202"
SECRET="dsc-tenant-materialize-202-secret"
TENANT="acme-202"
MOCK_DEPLOY="deployment/dbaas-aggregator"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✗ %s\033[0m\n' "$1"; exit 1; }
info() { printf '\033[1m%s\033[0m\n' "$1"; }

for bin in kubectl jq base64; do
  command -v "$bin" >/dev/null 2>&1 || fail "required tool not found: $bin"
done

cleanup_crs() {
  kubectl delete -f "$SCRIPT_DIR/test-resources/dsc-tenant-materialize-202.yaml" --ignore-not-found >/dev/null 2>&1 || true
  kubectl delete -f "$SCRIPT_DIR/test-resources/idb-tenant-materialize-202.yaml" --ignore-not-found >/dev/null 2>&1 || true
  kubectl delete "secret/$SECRET" -n "$NS" --ignore-not-found >/dev/null 2>&1 || true
}

info "0. Preconditions"
kubectl get ns "$NS" >/dev/null 2>&1 || fail "namespace $NS not found — run ./dev/kind-up.sh first"
kubectl -n dbaas-system get deploy/dbaas-operator >/dev/null 2>&1 \
  || fail "dbaas-operator not deployed — run ./dev/kind-up.sh first"
kubectl -n dbaas-system get cm/aggregator-mock-rules -o jsonpath='{.data.create-db-rules\.json}' \
  | jq -e '."tenant-svc-202".pendingCalls > 0' >/dev/null 2>&1 \
  || fail "mock rule tenant-svc-202 has no pendingCalls budget — re-apply dev/k8s/mock-aggregator.yaml"
pass "kind dev environment is up, mock has the pendingCalls rule"

# The pending budget lives in the mock process; restart it so a re-run starts from a clean count.
cleanup_crs
kubectl -n dbaas-system rollout restart "$MOCK_DEPLOY" >/dev/null
kubectl -n dbaas-system rollout status "$MOCK_DEPLOY" --timeout=120s >/dev/null
pass "aggregator-mock restarted (pending budget reset)"

info "1. Apply the tenant InternalDatabase and its claim (tenantId=$TENANT)"
kubectl apply -f "$SCRIPT_DIR/test-resources/idb-tenant-materialize-202.yaml" >/dev/null
kubectl apply -f "$SCRIPT_DIR/test-resources/dsc-tenant-materialize-202.yaml" >/dev/null
pass "CRs applied"

info "2. While the aggregator is still creating the database"
# Sample the whole status at once: the CR also passes through WaitingForDependency for the
# declarative apply, but that wait carries a trackingId — this one must not.
snapshot=""
deadline=$((SECONDS + 90))
while [ "$SECONDS" -lt "$deadline" ]; do
  json="$(kubectl get "internaldatabase/$IDB" -n "$NS" -o json 2>/dev/null || true)"
  if [ -n "$json" ] && echo "$json" | jq -e '
        .status.phase == "WaitingForDependency"
        and ((.status.trackingId // "") == "")
        and ((.status.conditions // []) | any(.type == "Ready"   and .status == "False" and .reason == "ProvisioningStarted"))
        and ((.status.conditions // []) | any(.type == "Stalled" and .status == "False"))' >/dev/null 2>&1; then
    snapshot="$json"
    break
  fi
  sleep 1
done
if [ -z "$snapshot" ]; then
  phase=$(kubectl get "internaldatabase/$IDB" -n "$NS" -o jsonpath='{.status.phase}' 2>/dev/null || echo "?")
  fail "InternalDatabase never reported the materialization wait (last phase=$phase)"
fi
pass "InternalDatabase: Phase=WaitingForDependency, Ready=False/ProvisioningStarted, Stalled=False"
pass "status.trackingId is empty — the wait is the materialization one, not the async provisioning one"

if kubectl logs -n dbaas-system "$MOCK_DEPLOY" 2>/dev/null | grep -q "202 (still creating)"; then
  pass "mock answered the get-or-create with 202 Accepted"
else
  fail "mock never logged a 202 for the get-or-create — the scenario did not exercise the pending path"
fi

if kubectl get "secret/$SECRET" -n "$NS" >/dev/null 2>&1; then
  fail "Secret $SECRET exists while the database is still being created"
fi
pass "Secret $SECRET absent — the claim is still waiting (the production symptom: FailedMount)"

info "3. Convergence once the database is created for real"
if kubectl wait --for=jsonpath='{.status.phase}'=Succeeded \
    "internaldatabase/$IDB" -n "$NS" --timeout=120s >/dev/null 2>&1; then
  pass "InternalDatabase reached Phase=Succeeded"
else
  phase=$(kubectl get "internaldatabase/$IDB" -n "$NS" -o jsonpath='{.status.phase}' 2>/dev/null || echo "?")
  fail "InternalDatabase did not converge (phase=$phase) — the operator gave up on the 202 path"
fi

pending_count=$(kubectl logs -n dbaas-system "$MOCK_DEPLOY" 2>/dev/null | grep -c "202 (still creating)" || true)
echo "    mock answered 202 $pending_count time(s) before creating the database"
[ "$pending_count" -ge 2 ] || fail "expected the operator to retry the get-or-create at least twice"
pass "operator retried the get-or-create until it succeeded"

if kubectl wait --for=jsonpath='{.status.phase}'=Succeeded \
    "databasesecretclaim/$DSC" -n "$NS" --timeout=120s >/dev/null 2>&1; then
  pass "DatabaseSecretClaim reached Phase=Succeeded"
else
  phase=$(kubectl get "databasesecretclaim/$DSC" -n "$NS" -o jsonpath='{.status.phase}' 2>/dev/null || echo "?")
  fail "DatabaseSecretClaim did not reach Succeeded (phase=$phase)"
fi

kubectl get "secret/$SECRET" -n "$NS" >/dev/null 2>&1 || fail "Secret $SECRET was not created"
meta="$(kubectl get "secret/$SECRET" -n "$NS" -o jsonpath='{.data.metadata\.json}' | base64 -d)"
echo "    metadata.json .classifier = $(echo "$meta" | jq -c '.classifier')"
echo "$meta" | jq -e ".classifier.scope == \"tenant\" and .classifier.tenantId == \"$TENANT\" and .classifier.microserviceName == \"tenant-svc-202\"" >/dev/null \
  || fail "Secret descriptor classifier is not the expected tenant identity"
pass "Secret carries the tenant classifier (scope=tenant, tenantId=$TENANT)"

info "RESULT: PASS — the operator waited out an asynchronous tenant database creation and converged"

if [ "${KEEP:-0}" != "1" ]; then
  cleanup_crs
fi
