#!/usr/bin/env bash
# Best-effort collection of DBaaS component diagnostics. Intended to run with
# `if: always()`, so it must NEVER fail the step — a failed deploy should still
# produce artifacts. Hence no `set -e`; every kubectl call is guarded with `|| true`.
set -uo pipefail

OUT_DIR="${OUT_DIR:-./logs}"
mkdir -p "$OUT_DIR"

# Map deployments to namespaces.
DEPLOY_NS_PAIRS=(
  "dbaas-aggregator:dbaas"
  "dbaas-operator:dbaas"
  "test-apps/go-test-app-service:dbaas"
  "dbaas-postgres-adapter:postgres"
  "postgres-backup-daemon:postgres"
)

for pair in "${DEPLOY_NS_PAIRS[@]}"; do
  deploy="${pair%%:*}"
  ns="${pair##*:}"
  echo "=== ${deploy} (ns=${ns}) ==="

  # Deployment describe — surfaces rollout conditions and replica status.
  kubectl -n "$ns" describe deploy "$deploy" > "$OUT_DIR/${deploy}.deploy-describe.txt" 2>&1 || true

  selector=$(
    kubectl get deploy "$deploy" -n "$ns" -o go-template='{{range $k,$v := .spec.selector.matchLabels}}{{printf "%s=%s\n" $k $v}}{{end}}' 2>/dev/null \
      | paste -sd, -
  )

  if [[ -z "$selector" ]]; then
    echo "No deployment ${deploy} in ${ns} (not deployed?)" >&2
    continue
  fi

  # Pod describe — surfaces scheduling / image-pull / CrashLoopBackOff / probe Events.
  kubectl -n "$ns" describe pod -l "$selector" > "$OUT_DIR/${deploy}.pods-describe.txt" 2>&1 || true

  pods=$(kubectl get pods -n "$ns" -l "$selector" -o name 2>/dev/null)
  if [[ -z "$pods" ]]; then
    echo "No pods found for deployment ${deploy} in ${ns}" >&2
    continue
  fi

  for pod in $pods; do
    pod_name=${pod#pod/}
    echo "--- ${pod} ---"
    kubectl logs "$pod" -n "$ns" --all-containers=true > "$OUT_DIR/${pod_name}.log" 2>&1 || true
    # Previous container log captures the crash that triggered a restart
    # (e.g. operator os.Exit on startup) — the single most useful signal.
    kubectl logs "$pod" -n "$ns" --all-containers=true --previous > "$OUT_DIR/${pod_name}.previous.log" 2>&1 || true
  done
  echo
done

# Namespace events surface failures not tied to a single pod's stdout
# (FailedScheduling, ImagePullBackOff, quota, PVC, webhook, hook timeouts).
for ns in dbaas postgres; do
  kubectl -n "$ns" get events --sort-by=.lastTimestamp > "$OUT_DIR/events-${ns}.txt" 2>&1 || true
done
