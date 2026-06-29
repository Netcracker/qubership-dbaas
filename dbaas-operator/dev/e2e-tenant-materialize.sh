#!/usr/bin/env bash
#
# e2e-tenant-materialize.sh — end-to-end check that a tenant-scoped InternalDatabase with a
# pinned tenantId materializes a concrete tenant database, and a matching DatabaseSecretClaim
# then resolves that database into a Secret.
#
# Prerequisite: the dev environment is up (./dev/kind-up.sh).
#
# Flow (against the aggregator-mock, which is stateful for tenant databases):
#   1. Apply idb-tenant-materialize.yaml — a tenant InternalDatabase pinning tenantId=acme.
#      Beyond the declarative apply, the operator issues a get-or-create
#      (PUT /api/v3/dbaas/test-ns/databases) that materializes {scope=tenant, tenantId=acme}.
#      The mock records that database.
#   2. Apply dsc-tenant-materialize.yaml — a DatabaseSecretClaim with the SAME classifier.
#      The operator calls get-by-classifier; the mock returns the database (200) BECAUSE it
#      was materialized in step 1 (without it the mock answers 404 / DatabaseNotFound and the
#      claim would wait). The operator writes the Secret dsc-tenant-materialize-secret.
#
# Usage:
#   ./dev/kind-up.sh
#   ./dev/e2e-tenant-materialize.sh
#
# Override the kept-resource cleanup with KEEP=1 to leave the CRs and Secret in place.
set -euo pipefail

NS="test-ns"
IDB="idb-tenant-materialize"
DSC="dsc-tenant-materialize"
SECRET="dsc-tenant-materialize-secret"
TENANT="acme"
MOCK_DEPLOY="deployment/dbaas-aggregator"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✗ %s\033[0m\n' "$1"; exit 1; }
info() { printf '\033[1m%s\033[0m\n' "$1"; }

for bin in kubectl jq base64; do
  command -v "$bin" >/dev/null 2>&1 || fail "required tool not found: $bin"
done

info "0. Preconditions"
kubectl get ns "$NS" >/dev/null 2>&1 || fail "namespace $NS not found — run ./dev/kind-up.sh first"
kubectl -n dbaas-system get deploy/dbaas-operator >/dev/null 2>&1 \
  || fail "dbaas-operator not deployed — run ./dev/kind-up.sh first"
pass "kind dev environment is up"

info "1. Materialize the tenant database (InternalDatabase, tenantId=$TENANT)"
kubectl apply -f "$SCRIPT_DIR/test-resources/idb-tenant-materialize.yaml" >/dev/null
if kubectl wait --for=jsonpath='{.status.phase}'=Succeeded \
    "internaldatabase/$IDB" -n "$NS" --timeout=90s >/dev/null 2>&1; then
  pass "InternalDatabase reached Phase=Succeeded"
else
  phase=$(kubectl get "internaldatabase/$IDB" -n "$NS" -o jsonpath='{.status.phase}' 2>/dev/null || echo "?")
  fail "InternalDatabase did not reach Succeeded (phase=$phase)"
fi

# Confirm the operator issued the get-or-create (materialization) call to the mock.
if kubectl logs -n dbaas-system "$MOCK_DEPLOY" 2>/dev/null \
    | grep -qE "create database .*tenantId=\"$TENANT\""; then
  pass "mock saw the get-or-create call for tenantId=$TENANT (database materialized)"
else
  fail "mock did NOT log the get-or-create call — tenant database was not materialized"
fi

info "2. Claim resolves the tenant database into a Secret"
kubectl apply -f "$SCRIPT_DIR/test-resources/dsc-tenant-materialize.yaml" >/dev/null
if kubectl wait --for=jsonpath='{.status.phase}'=Succeeded \
    "databasesecretclaim/$DSC" -n "$NS" --timeout=90s >/dev/null 2>&1; then
  pass "DatabaseSecretClaim reached Phase=Succeeded"
else
  phase=$(kubectl get "databasesecretclaim/$DSC" -n "$NS" -o jsonpath='{.status.phase}' 2>/dev/null || echo "?")
  fail "DatabaseSecretClaim did not reach Succeeded (phase=$phase) — get-by-classifier likely 404 (DB not materialized)"
fi

kubectl get "secret/$SECRET" -n "$NS" >/dev/null 2>&1 || fail "Secret $SECRET was not created"
meta="$(kubectl get "secret/$SECRET" -n "$NS" -o jsonpath='{.data.metadata\.json}' | base64 -d)"
echo "    metadata.json .classifier = $(echo "$meta" | jq -c '.classifier')"
echo "$meta" | jq -e ".classifier.scope == \"tenant\" and .classifier.tenantId == \"$TENANT\" and .classifier.microserviceName == \"idb-tenant\"" >/dev/null \
  || fail "Secret descriptor classifier is not the expected tenant identity"
pass "Secret carries the tenant classifier (scope=tenant, tenantId=$TENANT)"

info "RESULT: PASS — tenant InternalDatabase materialized the database; the claim resolved it into a Secret"

if [ "${KEEP:-0}" != "1" ]; then
  kubectl delete -f "$SCRIPT_DIR/test-resources/dsc-tenant-materialize.yaml" --ignore-not-found >/dev/null 2>&1 || true
  kubectl delete -f "$SCRIPT_DIR/test-resources/idb-tenant-materialize.yaml" --ignore-not-found >/dev/null 2>&1 || true
  kubectl delete "secret/$SECRET" -n "$NS" --ignore-not-found >/dev/null 2>&1 || true
fi
