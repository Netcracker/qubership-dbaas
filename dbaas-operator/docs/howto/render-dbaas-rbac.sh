#!/usr/bin/env bash
# Renders the ClusterRole and ClusterRoleBinding templates of the dbaas-operator chart
# without cloning the repository. Builds a minimal chart in WORKDIR, downloads the two
# templates from GitHub, and runs helm template. WORKDIR is deleted on every run.
#
# Environment variables:
#   REF        branch name or full ref in qubership-dbaas (default: main)
#   WORKDIR    directory for the temporary chart (default: /tmp/dbaas-rbac-chart)
#   NAMESPACE, SERVICE_NAME, APPLICATION_NAME, MANAGED_BY, K8S_EVENTS_ENABLED
#              values written to values.yaml
#
# Example: REF=refs/tags/1.2.3 NAMESPACE=my-ns ./render-dbaas-rbac.sh
set -euo pipefail

REF="${REF:-main}"
WORKDIR="${WORKDIR:-/tmp/dbaas-rbac-chart}"
NAMESPACE="${NAMESPACE:-dbaas}"
SERVICE_NAME="${SERVICE_NAME:-dbaas-operator}"
APPLICATION_NAME="${APPLICATION_NAME:-dbaas}"
MANAGED_BY="${MANAGED_BY:-helm}"
K8S_EVENTS_ENABLED="${K8S_EVENTS_ENABLED:-true}"

# A bare branch name becomes refs/heads/<branch>; a full ref such as refs/tags/<tag> is used as is.
case "$REF" in
  refs/*) REF_PATH="$REF" ;;
  *)      REF_PATH="refs/heads/$REF" ;;
esac
BASE="https://raw.githubusercontent.com/Netcracker/qubership-dbaas/${REF_PATH}/dbaas-operator/helm-templates/dbaas-operator/templates"

for tool in helm curl; do
  command -v "$tool" >/dev/null || { echo "$tool not found in PATH" >&2; exit 1; }
done

echo "==> Creating chart in $WORKDIR (ref: $REF_PATH)"
rm -rf "$WORKDIR"
mkdir -p "$WORKDIR/templates"

cat > "$WORKDIR/Chart.yaml" <<'EOF'
apiVersion: v2
name: dbaas-operator-rbac
version: 0.0.1
EOF

cat > "$WORKDIR/values.yaml" <<EOF
# Both templates render only when DBAAS_OPERATOR_ENABLED is true
# and restrictedEnvironment is false.
DBAAS_OPERATOR_ENABLED: true
restrictedEnvironment: false
NAMESPACE: ${NAMESPACE}
SERVICE_NAME: ${SERVICE_NAME}
APPLICATION_NAME: ${APPLICATION_NAME}
MANAGED_BY: ${MANAGED_BY}
K8S_EVENTS_ENABLED: ${K8S_EVENTS_ENABLED}
EOF

echo "==> Downloading templates"
for f in ClusterRole.yaml ClusterRoleBinding.yaml; do
  curl -fsSL -o "$WORKDIR/templates/$f" "$BASE/$f"
done

echo "==> Running helm template"
helm template dbaas-operator "$WORKDIR" > "$WORKDIR/out.yaml"

if ! grep -q '^kind: ClusterRole$' "$WORKDIR/out.yaml" \
   || ! grep -q '^kind: ClusterRoleBinding$' "$WORKDIR/out.yaml"; then
  echo "ClusterRole or ClusterRoleBinding is missing from the output; see $WORKDIR/out.yaml" >&2
  exit 1
fi

echo "==> Done: $WORKDIR/out.yaml"
grep -E '^kind:|^  name:|namespace:' "$WORKDIR/out.yaml"
