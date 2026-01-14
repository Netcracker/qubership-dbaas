#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${OUT_DIR:-./logs}"
mkdir -p "$OUT_DIR"

# Map deployments to namespaces.
DEPLOY_NS_PAIRS=(
  "dbaas-aggregator:dbaas"
  "dbaas-postgres-adapter:postgres"
  "postgres-backup-daemon:postgres"
)

for pair in "${DEPLOY_NS_PAIRS[@]}"; do
  deploy="${pair%%:*}"
  ns="${pair##*:}"
  echo "=== ${deploy} (ns=${ns}) ==="

  selector=$(
    kubectl get deploy "$deploy" -n "$ns" -o go-template='{{range $k,$v := .spec.selector.matchLabels}}{{printf "%s=%s\n" $k $v}}{{end}}' \
      | paste -sd, -
  )

  if [[ -z "$selector" ]]; then
    echo "No selector found for deployment ${deploy} in ${ns}" >&2
    continue
  fi

  pods=$(kubectl get pods -n "$ns" -l "$selector" -o name)
  if [[ -z "$pods" ]]; then
    echo "No pods found for deployment ${deploy} in ${ns}" >&2
    continue
  fi

  for pod in $pods; do
    pod_name=${pod#pod/}
    out_file="$OUT_DIR/${pod_name}.log"
    echo "--- ${pod} -> ${out_file} ---"
    kubectl logs "$pod" -n "$ns" --all-containers=true > "$out_file"
  done
  echo

done
