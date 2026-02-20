#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${OUT_DIR:-./fluentbit-logs}"
KIND_CLUSTER="${KIND_CLUSTER:-kind}"

mkdir -p "$OUT_DIR"

nodes=$(docker ps -a --format '{{.Names}}' | grep "^${KIND_CLUSTER}-" || true)
if [[ -z "$nodes" ]]; then
  echo "No kind nodes found for cluster ${KIND_CLUSTER}" >&2
  exit 1
fi

for node in $nodes; do
  echo "=== ${node} ==="
  if ! docker exec "$node" sh -c "test -d /var/log/fluent-bit"; then
    echo "No /var/log/fluent-bit on ${node}"
    continue
  fi

  node_dir="${OUT_DIR}/${node}"
  mkdir -p "$node_dir"

  for log in dbaas-aggregator.log dbaas-postgres-adapter.log postgres-backup-daemon.log; do
    if docker exec "$node" sh -c "test -f /var/log/fluent-bit/${log}"; then
      echo "Split ${log} from ${node}"
      docker exec "$node" sh -c "cat /var/log/fluent-bit/${log}" | \
        jq -Rr 'fromjson? | select(.kubernetes.pod_name and .log) | select(.kubernetes.pod_name|test("^(dbaas-aggregator|dbaas-postgres-adapter|postgres-backup-daemon)")) | [.kubernetes.pod_name, .log] | @tsv' | \
        while IFS=$'\t' read -r pod_name msg; do
          [[ -z "$pod_name" ]] && continue
          printf '%s\n' "$msg" >> "${node_dir}/${pod_name}.log"
        done
    fi
  done
done
