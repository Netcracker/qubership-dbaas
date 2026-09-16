#!/usr/bin/env bash
# Best-effort diagnostics for the dbaas-aggregator transition test. Intended to run under `if: always()`,
# so it must never fail the job itself — every kubectl/helm call is guarded with `|| true` and there is
# no `set -e`. Never dumps a rendered Kubernetes Secret or a raw classifier/connection-properties
# response body.
#
# Raw dbaas-aggregator logs include encrypted password fields, so this script uploads only known
# health warnings. It also copies the initial pods' evidence that deploy-fixture.sh captured before
# the rolling update replaced them.
set -uo pipefail

: "${PG_NAMESPACE:?}" "${DBAAS_NAMESPACE:?}"
OUT_DIR="${OUT_DIR:-./diagnostics}"
mkdir -p "$OUT_DIR"

echo "=== Pods / Deployments / ReplicaSets / Endpoints ==="
kubectl get pods,deployments,replicasets,endpoints -A -o wide > "$OUT_DIR/all-resources.txt" 2>&1 || true

echo "=== Images and digests actually running ==="
{
  echo "--- dbaas-aggregator ---"
  kubectl -n "$DBAAS_NAMESPACE" get deployment dbaas-aggregator -o jsonpath='{.spec.template.spec.containers[0].image}{"\n"}'
  kubectl -n "$DBAAS_NAMESPACE" get pods -l name=dbaas-aggregator -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.status.containerStatuses[0].imageID}{"\n"}{end}'
  echo "--- dbaas-operator (fixed component) ---"
  kubectl -n "$DBAAS_NAMESPACE" get deployment dbaas-operator -o jsonpath='{.spec.template.spec.containers[0].image}{"\n"}'
  echo "--- patroni (fixed component) ---"
  kubectl -n "$PG_NAMESPACE" get pods -l app=patroni -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.spec.containers[0].image}{"\n"}{end}'
  echo "--- dbaas-postgres-adapter (fixed component) ---"
  kubectl -n "$PG_NAMESPACE" get deployment dbaas-postgres-adapter -o jsonpath='{.spec.template.spec.containers[0].image}{"\n"}'
  echo "--- go-test-app-service ---"
  kubectl -n "$DBAAS_NAMESPACE" get deployment go-test-app-service -o jsonpath='{.spec.template.spec.containers[0].image}{"\n"}'
} > "$OUT_DIR/images.txt" 2>&1 || true

echo "=== Fixed-component image comparison (initial vs. what transition-aggregator.sh saw post-transition) ==="
[ -n "${FIXED_COMPONENT_IMAGES_FILE:-}" ] && [ -f "$FIXED_COMPONENT_IMAGES_FILE" ] && \
  cp "$FIXED_COMPONENT_IMAGES_FILE" "$OUT_DIR/fixed-component-images-initial.txt" || true

echo "=== Kubernetes events ==="
for ns in "$DBAAS_NAMESPACE" "$PG_NAMESPACE"; do
  kubectl -n "$ns" get events --sort-by=.lastTimestamp > "$OUT_DIR/events-${ns}.txt" 2>&1 || true
done

echo "=== Helm status and history (dbaas-aggregator) ==="
helm -n "$DBAAS_NAMESPACE" status dbaas-aggregator > "$OUT_DIR/helm-status-dbaas-aggregator.txt" 2>&1 || true
helm -n "$DBAAS_NAMESPACE" history dbaas-aggregator > "$OUT_DIR/helm-history-dbaas-aggregator.txt" 2>&1 || true

echo "=== Sanitized Helm values (dbaas-aggregator) — credential keys redacted, never the raw values ==="
helm -n "$DBAAS_NAMESPACE" get values dbaas-aggregator -o yaml 2>/dev/null \
  | sed -E 's/^(.*(PASSWORD|CREDENTIALS)[A-Za-z_]*:).*/\1 "***REDACTED***"/' \
  > "$OUT_DIR/helm-values-dbaas-aggregator.sanitized.yaml" || true

echo "=== Deployment describe (rollout conditions, replica status) ==="
kubectl -n "$DBAAS_NAMESPACE" describe deployment dbaas-aggregator > "$OUT_DIR/dbaas-aggregator.describe.txt" 2>&1 || true
kubectl -n "$DBAAS_NAMESPACE" describe deployment dbaas-operator > "$OUT_DIR/dbaas-operator.describe.txt" 2>&1 || true
kubectl -n "$DBAAS_NAMESPACE" describe deployment go-test-app-service > "$OUT_DIR/go-test-app-service.describe.txt" 2>&1 || true

echo "=== Pod describe + logs (current and previous) — dbaas-aggregator excluded, see below ==="
DEPLOY_NS_PAIRS=(
  "dbaas-operator:$DBAAS_NAMESPACE"
  "go-test-app-service:$DBAAS_NAMESPACE"
  "dbaas-availability-probe:$DBAAS_NAMESPACE"
  "dbaas-postgres-adapter:$PG_NAMESPACE"
)
for pair in "${DEPLOY_NS_PAIRS[@]}"; do
  deploy="${pair%%:*}"
  ns="${pair##*:}"

  selector=$(
    kubectl get deploy "$deploy" -n "$ns" -o go-template='{{range $k,$v := .spec.selector.matchLabels}}{{printf "%s=%s\n" $k $v}}{{end}}' 2>/dev/null \
      | paste -sd, -
  )
  [ -z "$selector" ] && { echo "No deployment $deploy in $ns" >> "$OUT_DIR/missing-deployments.txt"; continue; }

  kubectl -n "$ns" describe pod -l "$selector" > "$OUT_DIR/${deploy}.pods-describe.txt" 2>&1 || true

  for pod in $(kubectl get pods -n "$ns" -l "$selector" -o name 2>/dev/null); do
    pod_name=${pod#pod/}
    kubectl logs "$pod" -n "$ns" --all-containers=true --tail=2000 > "$OUT_DIR/${pod_name}.log" 2>&1 || true
    kubectl logs "$pod" -n "$ns" --all-containers=true --previous --tail=2000 > "$OUT_DIR/${pod_name}.previous.log" 2>&1 || true
  done
done

echo "=== dbaas-aggregator: describe + sanitized health evidence only (raw logs contain encrypted password fields) ==="
agg_selector=$(
  kubectl get deploy dbaas-aggregator -n "$DBAAS_NAMESPACE" -o go-template='{{range $k,$v := .spec.selector.matchLabels}}{{printf "%s=%s\n" $k $v}}{{end}}' 2>/dev/null \
    | paste -sd, -
)
if [ -z "$agg_selector" ]; then
  echo "No deployment dbaas-aggregator in $DBAAS_NAMESPACE" >> "$OUT_DIR/missing-deployments.txt"
else
  kubectl -n "$DBAAS_NAMESPACE" describe pod -l "$agg_selector" > "$OUT_DIR/dbaas-aggregator.pods-describe.txt" 2>&1 || true
  {
    for pod in $(kubectl get pods -n "$DBAAS_NAMESPACE" -l "$agg_selector" -o name 2>/dev/null); do
      pod_name=${pod#pod/}
      for suffix in "" "--previous"; do
        echo "--- $pod_name ${suffix:-current} ---"
        # shellcheck disable=SC2086 # $suffix is a fixed, script-controlled flag, not user input
        kubectl logs "$pod" -n "$DBAAS_NAMESPACE" --all-containers=true --tail=2000 $suffix 2>/dev/null \
          | grep -E \
              -e '\[class=AdapterHealthCheck\] [A-Za-z0-9_-]+ [A-Za-z0-9_.:-]+ has problem\. Status: (PROBLEM|UNKNOWN|DOWN)$' \
              -e '\[class=AbstractDbaasAdapterRESTClient\] Failed to get health of adapter of type [A-Za-z0-9_-]+$' \
              -e '\[class=DbaasPostgresConnectHealthCheck\] Postgres connection is lost$' \
          || true
      done
    done
  } > "$OUT_DIR/dbaas-aggregator.health-evidence.txt"
fi

echo "=== Initial aggregator pods' health evidence (captured before the rollout replaced them) ==="
[ -n "${INITIAL_AGGREGATOR_HEALTH_EVIDENCE_FILE:-}" ] && [ -f "$INITIAL_AGGREGATOR_HEALTH_EVIDENCE_FILE" ] && \
  cp "$INITIAL_AGGREGATOR_HEALTH_EVIDENCE_FILE" "$OUT_DIR/dbaas-aggregator.initial-health-evidence.txt" || true

echo "=== Fixture Job logs (verify / verify-post / verify-health) ==="
kubectl -n "$DBAAS_NAMESPACE" logs job/dbaas-fixture-verify --all-containers=true > "$OUT_DIR/job-dbaas-fixture-verify.log" 2>&1 || true
kubectl -n "$DBAAS_NAMESPACE" logs job/dbaas-fixture-verify-post --all-containers=true > "$OUT_DIR/job-dbaas-fixture-verify-post.log" 2>&1 || true
kubectl -n "$DBAAS_NAMESPACE" logs job/dbaas-fixture-verify-health --all-containers=true > "$OUT_DIR/job-dbaas-fixture-verify-health.log" 2>&1 || true

echo "=== Transition timestamps ==="
[ -n "${TRANSITION_TIMESTAMPS_FILE:-}" ] && [ -f "$TRANSITION_TIMESTAMPS_FILE" ] && \
  cp "$TRANSITION_TIMESTAMPS_FILE" "$OUT_DIR/transition-timestamps.txt" || true

echo "Diagnostics written to $OUT_DIR"
