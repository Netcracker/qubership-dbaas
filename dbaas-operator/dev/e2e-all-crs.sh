#!/usr/bin/env bash
#
# Reconciles every managed CR kind through the local kind + aggregator-mock
# environment. Workload CRs live in test-ns; PermanentBalancingRule lives in
# the assigned operator namespace, dbaas-system.
#
# Resources are intentionally left in place for inspection in OpenLens.
set -euo pipefail

OPERATOR_NS="${OPERATOR_NS:-dbaas-system}"
WORKLOAD_NS="${WORKLOAD_NS:-test-ns}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESOURCES="${SCRIPT_DIR}/test-resources"

pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; exit 1; }
section() { printf '\n\033[1m%s\033[0m\n' "$1"; }

for binary in kubectl base64 grep; do
  command -v "$binary" >/dev/null 2>&1 || fail "required tool not found: $binary"
done

wait_succeeded() {
  local resource="$1" name="$2" namespace="$3"
  kubectl wait --for=jsonpath='{.status.phase}'=Succeeded \
    "${resource}/${name}" -n "$namespace" --timeout=120s >/dev/null 2>&1 \
    || fail "${resource}/${name} did not reach Succeeded"
  local generation observed ready
  generation="$(kubectl get "${resource}/${name}" -n "$namespace" -o jsonpath='{.metadata.generation}')"
  observed="$(kubectl get "${resource}/${name}" -n "$namespace" -o jsonpath='{.status.observedGeneration}')"
  ready="$(kubectl get "${resource}/${name}" -n "$namespace" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}')"
  [ "$observed" = "$generation" ] && [ "$ready" = "True" ] \
    || fail "${resource}/${name} did not reconcile its current generation"
  pass "${resource}/${name} reconciled current generation"
}

section "0. Preconditions and deterministic cleanup"
kubectl get namespace "$OPERATOR_NS" >/dev/null 2>&1 || fail "namespace ${OPERATOR_NS} does not exist"
kubectl get namespace "$WORKLOAD_NS" >/dev/null 2>&1 || fail "namespace ${WORKLOAD_NS} does not exist"
kubectl get deployment/dbaas-operator -n "$OPERATOR_NS" >/dev/null 2>&1 \
  || fail "dbaas-operator is not deployed in ${OPERATOR_NS}"

for namespace in default "$WORKLOAD_NS" "$OPERATOR_NS"; do
  kubectl delete permanentbalancingrule permanent-balancing-rules -n "$namespace" \
    --ignore-not-found --wait=true >/dev/null
done

kubectl delete -f "$RESOURCES/dsc-success.yaml" --ignore-not-found --wait=true >/dev/null
kubectl delete -f "$RESOURCES/dsc-tenant-materialize.yaml" --ignore-not-found --wait=true >/dev/null
kubectl delete -f "$RESOURCES/idb-success-sync.yaml" --ignore-not-found --wait=true >/dev/null
kubectl delete -f "$RESOURCES/idb-tenant-materialize.yaml" --ignore-not-found --wait=true >/dev/null
kubectl delete -f "$RESOURCES/dap-success.yaml" --ignore-not-found --wait=true >/dev/null
kubectl delete -f "$RESOURCES/mbr-success.yaml" --ignore-not-found --wait=true >/dev/null
kubectl delete -f "$RESOURCES/nbr-success.yaml" --ignore-not-found --wait=true >/dev/null
kubectl delete -f "$RESOURCES/edb-with-secret.yaml" --ignore-not-found --wait=true >/dev/null
kubectl delete -f "$RESOURCES/edb-201-created.yaml" --ignore-not-found --wait=true >/dev/null
kubectl delete externaldatabase foreign-postgres -n "$WORKLOAD_NS" \
  --ignore-not-found --wait=true >/dev/null
kubectl delete secret dsc-success-secret dsc-tenant-materialize-secret -n "$WORKLOAD_NS" \
  --ignore-not-found --wait=true >/dev/null

kubectl apply -f "$RESOURCES/secret-rbac.yaml" >/dev/null
kubectl apply -f "$RESOURCES/secret.yaml" >/dev/null
kubectl rollout restart deployment/dbaas-aggregator -n "$OPERATOR_NS" >/dev/null
kubectl rollout status deployment/dbaas-aggregator -n "$OPERATOR_NS" --timeout=120s >/dev/null
pass "local DBaaS environment is ready"

section "1. ExternalDatabase (two CRs)"
kubectl apply -f "$RESOURCES/edb-with-secret.yaml" >/dev/null
kubectl apply -f "$RESOURCES/edb-201-created.yaml" >/dev/null
wait_succeeded externaldatabase my-postgres "$WORKLOAD_NS"
wait_succeeded externaldatabase edb-201 "$WORKLOAD_NS"

section "2. InternalDatabase (two CRs)"
kubectl apply -f "$RESOURCES/idb-success-sync.yaml" >/dev/null
kubectl apply -f "$RESOURCES/idb-tenant-materialize.yaml" >/dev/null
wait_succeeded internaldatabase idb-success-sync "$WORKLOAD_NS"
wait_succeeded internaldatabase idb-tenant-materialize "$WORKLOAD_NS"
kubectl logs -n "$OPERATOR_NS" deployment/dbaas-aggregator | grep -qE 'create database .*tenantId="acme"' \
  || fail "tenant InternalDatabase did not materialize tenantId=acme"
pass "tenant InternalDatabase produced the expected aggregator call"

section "3. DatabaseSecretClaim (two CRs and their Secrets)"
kubectl apply -f "$RESOURCES/dsc-success.yaml" >/dev/null
kubectl apply -f "$RESOURCES/dsc-tenant-materialize.yaml" >/dev/null
wait_succeeded databasesecretclaim dsc-success "$WORKLOAD_NS"
wait_succeeded databasesecretclaim dsc-tenant-materialize "$WORKLOAD_NS"
for secret in dsc-success-secret dsc-tenant-materialize-secret; do
  kubectl get secret "$secret" -n "$WORKLOAD_NS" >/dev/null 2>&1 \
    || fail "DatabaseSecretClaim did not create Secret ${secret}"
done
tenant_metadata="$(kubectl get secret dsc-tenant-materialize-secret -n "$WORKLOAD_NS" \
  -o jsonpath='{.data.metadata\.json}' | base64 -d)"
echo "$tenant_metadata" | grep -Eq '"scope"[[:space:]]*:[[:space:]]*"tenant"' \
  || fail "tenant claim Secret has the wrong scope"
echo "$tenant_metadata" | grep -Eq '"tenantId"[[:space:]]*:[[:space:]]*"acme"' \
  || fail "tenant claim Secret has the wrong tenantId"
echo "$tenant_metadata" | grep -Eq '"microserviceName"[[:space:]]*:[[:space:]]*"idb-tenant"' \
  || fail "tenant claim Secret has the wrong microserviceName"
pass "both claims created Secrets; tenant classifier is preserved"

section "4. DatabaseAccessPolicy"
kubectl apply -f "$RESOURCES/dap-success.yaml" >/dev/null
wait_succeeded databaseaccesspolicy dap-success "$WORKLOAD_NS"

section "5. Namespace-scoped balancing rules"
kubectl apply -f "$RESOURCES/mbr-success.yaml" >/dev/null
kubectl apply -f "$RESOURCES/nbr-success.yaml" >/dev/null
wait_succeeded microservicebalancingrule microservice-balancing-rules "$WORKLOAD_NS"
wait_succeeded namespacebalancingrule namespace-balancing-rules "$WORKLOAD_NS"

section "6. PermanentBalancingRule placement and reconciliation"
kubectl apply -f "$RESOURCES/pbr-success.yaml" >/dev/null
wait_succeeded permanentbalancingrule permanent-balancing-rules "$OPERATOR_NS"

kubectl apply -f "$RESOURCES/pbr-invalid-namespace.yaml" >/dev/null
kubectl wait --for=jsonpath='{.status.phase}'=InvalidConfiguration \
  permanentbalancingrule/permanent-balancing-rules -n "$WORKLOAD_NS" --timeout=120s >/dev/null 2>&1 \
  || fail "misplaced PermanentBalancingRule was not rejected"
invalid_generation="$(kubectl get permanentbalancingrule/permanent-balancing-rules -n "$WORKLOAD_NS" -o jsonpath='{.metadata.generation}')"
invalid_observed="$(kubectl get permanentbalancingrule/permanent-balancing-rules -n "$WORKLOAD_NS" -o jsonpath='{.status.observedGeneration}')"
invalid_stalled="$(kubectl get permanentbalancingrule/permanent-balancing-rules -n "$WORKLOAD_NS" -o jsonpath='{.status.conditions[?(@.type=="Stalled")].status}')"
[ "$invalid_observed" = "$invalid_generation" ] && [ "$invalid_stalled" = "True" ] \
  || fail "misplaced PermanentBalancingRule did not report a terminal validation error"
if kubectl logs -n "$OPERATOR_NS" deployment/dbaas-aggregator | grep -q 'must-not-be-applied'; then
  fail "misplaced PermanentBalancingRule reached the aggregator"
fi
pass "misplaced PermanentBalancingRule was rejected before aggregator application"
kubectl delete -f "$RESOURCES/pbr-invalid-namespace.yaml" --wait=true >/dev/null

while read -r namespace operator_namespace; do
  [ "$namespace" = "$OPERATOR_NS" ] && [ "$operator_namespace" = "$OPERATOR_NS" ] \
    || fail "a PermanentBalancingRule remains outside the DBaaS namespace"
done < <(kubectl get permanentbalancingrules -A \
  -o jsonpath='{range .items[*]}{.metadata.namespace}{" "}{.spec.operatorNamespace}{"\n"}{end}')
pass "only the PermanentBalancingRule in ${OPERATOR_NS} remains"

section "RESULT: PASS"
kubectl get \
  externaldatabases,internaldatabases,databasesecretclaims,databaseaccesspolicies,microservicebalancingrules,namespacebalancingrules \
  -n "$WORKLOAD_NS"
kubectl get permanentbalancingrules -n "$OPERATOR_NS"
