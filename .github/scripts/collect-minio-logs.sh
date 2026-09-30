#!/usr/bin/env bash
set -uo pipefail

OUT_DIR="${OUT_DIR:-./minio-logs}"
mkdir -p "$OUT_DIR"

echo "=== minio-operator ==="

kubectl -n minio-operator describe deploy minio-operator \
  > "$OUT_DIR/minio-operator.deploy-describe.txt" 2>&1 || true

kubectl -n minio-operator logs deploy/minio-operator --all-containers=true \
  > "$OUT_DIR/minio-operator.log" 2>&1 || true

kubectl -n minio-operator get events --sort-by=.lastTimestamp \
  > "$OUT_DIR/events-minio-operator.txt" 2>&1 || true

echo "=== minio tenant ==="

# Full Tenant YAML: .status.currentState, .status.healthStatus, and any error
# messages are the first things to check when the tenant fails to reach Ready.
kubectl -n minio get tenant test-minio -o yaml \
  > "$OUT_DIR/minio-tenant.yaml" 2>&1 || true

# PVC status — a PVC stuck in Pending is the most common cause of a stalled
# tenant on Kind (no default storage class, or capacity exhausted).
kubectl -n minio get pvc \
  > "$OUT_DIR/minio-pvc.txt" 2>&1 || true

kubectl -n minio describe pod -l "v1.min.io/tenant=test-minio" \
  > "$OUT_DIR/minio-pods-describe.txt" 2>&1 || true

kubectl -n minio get events --sort-by=.lastTimestamp \
  > "$OUT_DIR/events-minio.txt" 2>&1 || true

minio_pods=$(kubectl get pods -n minio -l "v1.min.io/tenant=test-minio" -o name 2>/dev/null || true)
for pod in $minio_pods; do
  pod_name="${pod#pod/}"
  kubectl logs "$pod" -n minio --all-containers=true \
    > "$OUT_DIR/${pod_name}.log" 2>&1 || true
  kubectl logs "$pod" -n minio --all-containers=true --previous \
    > "$OUT_DIR/${pod_name}.previous.log" 2>&1 || true
done
