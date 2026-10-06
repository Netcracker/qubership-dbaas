#!/usr/bin/env bash
# Collect DBaaS diagnostics without masking the workflow's original failure.
#
# Optional environment:
#   OUT_DIR                output directory (default ./logs)
#   DBAAS_NAMESPACE        aggregator and operator namespace (default dbaas)
#   PG_NAMESPACE           PostgreSQL adapter and backup daemon namespace (default postgres)
#   EXTRA_DEPLOYMENTS      space-separated "deployment:namespace" pairs to collect as well
#   AGGREGATOR_LOG_MODE    "full" (default) or "health-only"; health-only keeps only aggregator
#                          health warnings because full logs can contain encrypted credentials
set -uo pipefail

OUT_DIR="${OUT_DIR:-./logs}"
DBAAS_NAMESPACE="${DBAAS_NAMESPACE:-dbaas}"
PG_NAMESPACE="${PG_NAMESPACE:-postgres}"
EXTRA_DEPLOYMENTS="${EXTRA_DEPLOYMENTS:-}"
AGGREGATOR_LOG_MODE="${AGGREGATOR_LOG_MODE:-full}"
mkdir -p "$OUT_DIR"

AGGREGATOR_HEALTH_PATTERN='\[class=AdapterHealthCheck\] [A-Za-z0-9_-]+ [A-Za-z0-9_.:-]+ has problem\. Status: (PROBLEM|UNKNOWN|DOWN)$|\[class=AbstractDbaasAdapterRESTClient\] Failed to get health of adapter of type [A-Za-z0-9_-]+$|\[class=DbaasPostgresConnectHealthCheck\] Postgres connection is lost$'

DEPLOY_NS_PAIRS=(
  "dbaas-aggregator:${DBAAS_NAMESPACE}"
  "dbaas-operator:${DBAAS_NAMESPACE}"
  "dbaas-postgres-adapter:${PG_NAMESPACE}"
  "postgres-backup-daemon:${PG_NAMESPACE}"
)
for pair in $EXTRA_DEPLOYMENTS; do
  DEPLOY_NS_PAIRS+=("$pair")
done

for pair in "${DEPLOY_NS_PAIRS[@]}"; do
  deploy="${pair%%:*}"
  ns="${pair##*:}"
  echo "=== ${deploy} (ns=${ns}) ==="

  kubectl -n "$ns" describe deploy "$deploy" > "$OUT_DIR/${deploy}.deploy-describe.txt" 2>&1 || true

  selector=$(
    kubectl get deploy "$deploy" -n "$ns" -o go-template='{{range $k,$v := .spec.selector.matchLabels}}{{printf "%s=%s\n" $k $v}}{{end}}' 2>/dev/null \
      | paste -sd, -
  )

  if [[ -z "$selector" ]]; then
    echo "No deployment ${deploy} in ${ns} (not deployed?)" >&2
    continue
  fi

  kubectl -n "$ns" describe pod -l "$selector" > "$OUT_DIR/${deploy}.pods-describe.txt" 2>&1 || true

  pods=$(kubectl get pods -n "$ns" -l "$selector" -o name 2>/dev/null)
  if [[ -z "$pods" ]]; then
    echo "No pods found for deployment ${deploy} in ${ns}" >&2
    continue
  fi

  for pod in $pods; do
    pod_name=${pod#pod/}
    echo "--- ${pod} ---"
    if [[ "$deploy" == "dbaas-aggregator" && "$AGGREGATOR_LOG_MODE" == "health-only" ]]; then
      { kubectl logs "$pod" -n "$ns" --all-containers=true 2>/dev/null | grep -E "$AGGREGATOR_HEALTH_PATTERN"; } \
        > "$OUT_DIR/${pod_name}.health.log" || true
      { kubectl logs "$pod" -n "$ns" --all-containers=true --previous 2>/dev/null | grep -E "$AGGREGATOR_HEALTH_PATTERN"; } \
        > "$OUT_DIR/${pod_name}.previous.health.log" || true
      continue
    fi
    kubectl logs "$pod" -n "$ns" --all-containers=true > "$OUT_DIR/${pod_name}.log" 2>&1 || true
    kubectl logs "$pod" -n "$ns" --all-containers=true --previous > "$OUT_DIR/${pod_name}.previous.log" 2>&1 || true
  done
  echo
done

for ns in $(printf '%s\n' "$DBAAS_NAMESPACE" "$PG_NAMESPACE" "${DEPLOY_NS_PAIRS[@]##*:}" | sort -u); do
  kubectl -n "$ns" get events --sort-by=.lastTimestamp > "$OUT_DIR/events-${ns}.txt" 2>&1 || true
done
