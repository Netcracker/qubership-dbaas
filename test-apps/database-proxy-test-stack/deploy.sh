#!/usr/bin/env bash
# Deploy the DBaaS Proxy and HAProxy test fixture with helm, then wait for both to become available.
#
# Deploy this before any application creates an InternalDatabase or DatabaseSecretClaim. The
# operator writes the generated Secret from whatever the aggregator returns, so a claim reconciled
# before the proxy is in the control path gets a direct database endpoint and never reaches HAProxy.
#
# Usage:
#   deploy.sh --namespace <ns> --image <repository:tag> [options]
#
# Options:
#   --namespace <ns>      Namespace to deploy into. Required.
#   --image <ref>         DBaaS Proxy image as <repository>:<tag>. Required, and the tag must be
#                         immutable so a rerun deploys the same proxy.
#   --upstream <url>      Aggregator URL that DBaaS Proxy forwards /api requests to.
#                         Default: http://dbaas-aggregator.<namespace>:8080
#   --mapping <spec>      Database mapping as <name>:<listenPort>:<targetHost>:<targetPort>.
#                         Repeatable. Overrides the chart default when given at least once.
#   --release <name>      Helm release name. Default: database-proxy-test-stack.
#   --timeout <duration>  Rollout wait per deployment. Default: 300s.
set -euo pipefail

NAMESPACE=""
IMAGE=""
UPSTREAM=""
RELEASE="database-proxy-test-stack"
TIMEOUT="300s"
MAPPINGS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --namespace) NAMESPACE="${2:?--namespace needs a value}"; shift 2 ;;
    --image)     IMAGE="${2:?--image needs a value}";         shift 2 ;;
    --upstream)  UPSTREAM="${2:?--upstream needs a value}";   shift 2 ;;
    --release)   RELEASE="${2:?--release needs a value}";     shift 2 ;;
    --timeout)   TIMEOUT="${2:?--timeout needs a value}";     shift 2 ;;
    --mapping)   MAPPINGS+=("${2:?--mapping needs a value}"); shift 2 ;;
    -h|--help)   sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$NAMESPACE" ]; then
  echo "--namespace is required." >&2
  exit 2
fi
if [ -z "$IMAGE" ]; then
  echo "--image is required: build test-apps/dbaas-proxy-stub, or package the real dbaas-proxy with build-image.sh." >&2
  exit 2
fi

# Split on the LAST colon so a registry with a port, or a digest reference, still parses.
IMAGE_REPOSITORY="${IMAGE%:*}"
IMAGE_TAG="${IMAGE##*:}"
if [ -z "$IMAGE_REPOSITORY" ] || [ -z "$IMAGE_TAG" ] || [ "$IMAGE_REPOSITORY" = "$IMAGE_TAG" ]; then
  echo "--image must be <repository>:<tag>, got: $IMAGE" >&2
  exit 2
fi

if [ -z "$UPSTREAM" ]; then
  UPSTREAM="http://dbaas-aggregator.${NAMESPACE}:8080"
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHART="$SCRIPT_DIR/helm-templates/database-proxy-test-stack"

HELM_ARGS=(
  upgrade --install "$RELEASE" "$CHART"
  --namespace "$NAMESPACE"
  --set NAMESPACE="$NAMESPACE"
  --set DBAAS_PROXY_IMAGE_REPOSITORY="$IMAGE_REPOSITORY"
  --set DBAAS_PROXY_TAG="$IMAGE_TAG"
  --set DBAAS_PROXY_UPSTREAM_URL="$UPSTREAM"
)

for index in "${!MAPPINGS[@]}"; do
  IFS=':' read -r name listen_port target_host target_port <<< "${MAPPINGS[$index]}"
  if [ -z "$name" ] || [ -z "$listen_port" ] || [ -z "$target_host" ] || [ -z "$target_port" ]; then
    echo "--mapping must be <name>:<listenPort>:<targetHost>:<targetPort>, got: ${MAPPINGS[$index]}" >&2
    exit 2
  fi
  HELM_ARGS+=(
    --set "DATABASE_MAPPINGS[$index].name=$name"
    --set "DATABASE_MAPPINGS[$index].listenPort=$listen_port"
    --set "DATABASE_MAPPINGS[$index].targetHost=$target_host"
    --set "DATABASE_MAPPINGS[$index].targetPort=$target_port"
  )
done

helm "${HELM_ARGS[@]}"

kubectl -n "$NAMESPACE" rollout status deployment/tcp-proxy --timeout="$TIMEOUT"
kubectl -n "$NAMESPACE" rollout status deployment/dbaas-proxy --timeout="$TIMEOUT"

# The startup log lists the mappings DBaaS Proxy matches against. A rewrite that silently does not
# happen is the most common failure in this fixture, so surface them here rather than at assert time.
echo "DBaaS Proxy mappings:"
kubectl -n "$NAMESPACE" logs deployment/dbaas-proxy --tail=50 | grep -iE 'proxy (host|mappings)|->' || true
