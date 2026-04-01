#!/usr/bin/env bash

set -euo pipefail

cluster_name="dbaas"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
green='\033[0;32m'
reset='\033[0m'
kind_config=""
patroni_values=""
script_name="$(basename "${BASH_SOURCE[0]}")"

usage() {
  cat <<EOF
Usage: ${script_name} [--help]

End-to-end bootstrap for DBaaS in a local kind cluster.

Requirements:
  - kind, kubectl, make available in PATH
  - Docker running (for kind)
EOF
}

case "${1:-}" in
  -h|--help)
    usage
    exit 0
    ;;
esac

cleanup() {
  if [[ -n "${kind_config}" ]]; then
    rm -f "${kind_config}"
  fi
  if [[ -n "${patroni_values}" ]]; then
    rm -f "${patroni_values}"
  fi
}
trap cleanup EXIT

if ! command -v kind >/dev/null 2>&1; then
  echo "kind is not installed; please install it first." >&2
  exit 1
fi

if kind get clusters | grep -qx "${cluster_name}"; then
  printf '%b\n' "${green}Cluster '${cluster_name}' already exists; skipping creation.${reset}"
else
  printf '%b\n' "${green}Creating kind cluster '${cluster_name}'...${reset}"
  kind_config="$(mktemp)"

  cat <<'EOF' > "${kind_config}"
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
  - role: worker
  - role: worker
EOF

  kind create cluster --name "${cluster_name}" --config "${kind_config}"
fi

patroni_values="$(mktemp)"

if kubectl get namespace minio >/dev/null 2>&1; then
  printf '%b\n' "${green}Namespace 'minio' already exists; skipping creation.${reset}"
else
  printf '%b\n' "${green}Creating namespace 'minio'...${reset}"
  kubectl create namespace minio --dry-run=client -o yaml | kubectl apply -f -
fi

if kubectl get secret minio-env -n minio >/dev/null 2>&1; then
  printf '%b\n' "${green}Secret 'minio-env' already exists; skipping creation.${reset}"
else
  printf '%b\n' "${green}Creating MinIO env secret...${reset}"
  cat <<'EOF' | kubectl apply -f -
apiVersion: v1
kind: Secret
metadata:
  name: minio-env
  namespace: minio
type: Opaque
stringData:
  config.env: |
    export MINIO_ROOT_USER=minio
    export MINIO_ROOT_PASSWORD=minio123
EOF
fi

if kubectl get deployment minio-operator -n minio-operator >/dev/null 2>&1; then
  printf '%b\n' "${green}MinIO operator already exists; skipping apply.${reset}"
else
  printf '%b\n' "${green}Applying MinIO operator...${reset}"
  kubectl kustomize "github.com/minio/operator?ref=v7.1.1" | kubectl apply -f -
fi

printf '%b\n' "${green}Waiting for MinIO operator deployment...${reset}"
kubectl wait -n minio-operator --for=condition=Available deployment/minio-operator --timeout=300s

if kubectl get tenant test-minio -n minio >/dev/null 2>&1; then
  printf '%b\n' "${green}MinIO tenant already exists; skipping apply.${reset}"
else
  printf '%b\n' "${green}Deploying MinIO tenant...${reset}"
  cat <<'EOF' | kubectl apply -f -
apiVersion: minio.min.io/v2
kind: Tenant
metadata:
  name: test-minio
  namespace: minio
spec:
  buckets:
    - name: dbaas-backup-restore-test
  configuration:
    name: minio-env
  requestAutoCert: false
  pools:
    - servers: 1
      name: pool-0
      volumesPerServer: 1
      volumeClaimTemplate:
        metadata:
          name: data
        spec:
          accessModes:
            - ReadWriteOnce
          resources:
            requests:
              storage: 2Gi
EOF
fi

printf '%b\n' "${green}Waiting for MinIO tenant readiness...${reset}"
for _ in {1..30}; do
  state="$(kubectl get tenant test-minio -n minio -o jsonpath='{.status.currentState}')"
  printf "Current state: %s\n" "${state}"
  if [[ "${state}" == "Ready" || "${state}" == "Initialized" ]]; then
    printf '%b\n' "${green}MinIO Tenant is ready${reset}"
    break
  fi
  sleep 10
done

if [[ "${state:-}" != "Ready" && "${state:-}" != "Initialized" ]]; then
  echo "MinIO Tenant did not become ready in time" >&2
  exit 1
fi

printf '%b\n' "${green}Creating patroni-services-values.yaml...${reset}"
cat <<'EOF' > "${patroni_values}"
postgresPassword: ${POSTGRES_PASSWORD}

backupDaemon:
  install: true
  storage:
    storageClass: ${STORAGE_CLASS}
    type: pv
    size: 2Gi
  s3Storage:
    url: http://minio.minio.svc.cluster.local
    accessKeyId: minio
    secretAccessKey: minio123
    bucket: dbaas-backup-restore-test
    region: region123
  securityContext:
    runAsUser: 101
    fsGroup: 101

dbaas:
  install: true
  aggregator:
    registrationAddress: http://${DBAAS_SERVICE_NAME}.${DBAAS_NAMESPACE}.svc.cluster.local:8080
    registrationUsername: cluster-dba
    registrationPassword: password
EOF

printf '%b\n' "${green}Deploying DBaaS...${reset}"
make -C "${script_dir}" install CONFIG_FILE=local.mk PATRONI_SERVICES_VALUES_FILE="${patroni_values}"

printf '%b\n' "${green}Waiting for postgres-backup-daemon deployment...${reset}"
kubectl rollout status deployment/postgres-backup-daemon \
  -n postgres \
  --timeout=300s
