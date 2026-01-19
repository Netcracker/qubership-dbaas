#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${OUT_DIR:-./pod-logs}"
KIND_CLUSTER="${KIND_CLUSTER:-kind}"
POD_RE='(dbaas-aggregator|dbaas-postgres-adapter|postgres-backup-daemon)'

mkdir -p "$OUT_DIR"

nodes=$(docker ps -a --format '{{.Names}}' | grep "^${KIND_CLUSTER}-" || true)
if [[ -z "$nodes" ]]; then
  echo "No kind nodes found for cluster ${KIND_CLUSTER}" >&2
  exit 1
fi

for node in $nodes; do
  echo "=== node ${node} ==="

  dirs=$(docker exec "$node" sh -c "ls -1 /var/log/pods 2>/dev/null" || true)
  if [[ -z "$dirs" ]]; then
    echo "No /var/log/pods on ${node}" >&2
    continue
  fi

  while IFS= read -r dir; do
    [[ -z "$dir" ]] && continue

    ns="${dir%%_*}"
    rest="${dir#*_}"
    pod="${rest%_*}"

    if [[ ! "$pod" =~ $POD_RE ]]; then
      continue
    fi

    out_dir="${OUT_DIR}/${ns}/${pod}"
    mkdir -p "$out_dir"

    log_files=$(docker exec "$node" sh -c "find /var/log/pods/${dir} -type f -name '*.log' 2>/dev/null" || true)
    if [[ -z "$log_files" ]]; then
      continue
    fi

    while IFS= read -r log_file; do
      [[ -z "$log_file" ]] && continue
      rel="${log_file#/var/log/pods/${dir}/}"
      rel_dir="$(dirname "$rel")"
      base="$(basename "$log_file")"
      dest_dir="${out_dir}/${rel_dir}"
      mkdir -p "$dest_dir"
      echo "--- ${node}:${log_file} -> ${dest_dir}/${base} ---"
      docker exec "$node" sh -c "cat '$log_file'" > "${dest_dir}/${base}"
    done <<< "$log_files"

  done <<< "$dirs"
done
