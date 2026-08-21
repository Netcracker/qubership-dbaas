#!/usr/bin/env bash
# Build the DBaaS Proxy fixture image from a dbaas-proxy checkout.
#
# dbaas-proxy is not published to a public registry, and its Go module lives on the internal GitLab,
# so the binary is compiled here with the host toolchain and then packaged on the public platform
# base image. Run this wherever the internal module is reachable, then push the image or load it
# into kind.
#
# Usage:
#   build-image.sh --source <path-to-dbaas-proxy-checkout> [options]
#
# Options:
#   --source <path>    dbaas-proxy checkout to build from. Required.
#   --binary <path>    Package this prebuilt linux/amd64 binary instead of compiling. Use it when
#                      the Go modules resolve on a different host than docker, or when an earlier
#                      CI step already produced the binary.
#   --ref <ref>        Git ref to build. Default: origin/master.
#   --image <ref>      Image to produce. Default: dbaas-proxy:local.
#   --kind <cluster>   Load the image into this kind cluster after building.
#   --push             Push the image after building.
set -euo pipefail

SOURCE=""
REF="origin/master"
IMAGE="dbaas-proxy:local"
BINARY=""
KIND_CLUSTER=""
PUSH="false"

while [ $# -gt 0 ]; do
  case "$1" in
    --source) SOURCE="${2:?--source needs a path}"; shift 2 ;;
    --binary) BINARY="${2:?--binary needs a path}"; shift 2 ;;
    --ref)    REF="${2:?--ref needs a git ref}";    shift 2 ;;
    --image)  IMAGE="${2:?--image needs a reference}"; shift 2 ;;
    --kind)   KIND_CLUSTER="${2:?--kind needs a cluster name}"; shift 2 ;;
    --push)   PUSH="true"; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$SOURCE" ]; then
  echo "--source is required: pass the path to a dbaas-proxy checkout." >&2
  exit 2
fi
if [ ! -d "$SOURCE/.git" ]; then
  echo "Not a git checkout: $SOURCE" >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

echo "Exporting $REF from $SOURCE"
git -C "$SOURCE" archive "$REF" | tar -x -C "$WORKDIR"

COMMIT="$(git -C "$SOURCE" rev-parse "$REF")"
echo "Building dbaas-proxy at $COMMIT"

if [ -n "$BINARY" ]; then
  if [ ! -f "$BINARY" ]; then
    echo "No such binary: $BINARY" >&2
    exit 2
  fi
  echo "Packaging prebuilt binary $BINARY"
  cp "$BINARY" "$WORKDIR/dbaas-proxy"
else
  # linux/amd64 and CGO_ENABLED=0 so the binary runs on the platform base image. This step needs the
  # internal Go module to resolve; pass --binary instead when it does not.
  ( cd "$WORKDIR/dbaas-proxy-service" \
    && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o "$WORKDIR/dbaas-proxy" . )
fi

cp "$WORKDIR/dbaas-proxy-service/application.yaml" "$WORKDIR/application.yaml"
cp "$SCRIPT_DIR/Dockerfile" "$WORKDIR/Dockerfile"

echo "Building image $IMAGE"
docker build \
  --label "org.opencontainers.image.source=dbaas-proxy" \
  --label "org.opencontainers.image.revision=$COMMIT" \
  -t "$IMAGE" "$WORKDIR"

if [ -n "$KIND_CLUSTER" ]; then
  echo "Loading $IMAGE into kind cluster $KIND_CLUSTER"
  kind load docker-image "$IMAGE" --name "$KIND_CLUSTER"
fi

if [ "$PUSH" = "true" ]; then
  echo "Pushing $IMAGE"
  docker push "$IMAGE"
fi

echo "Built $IMAGE from dbaas-proxy $COMMIT"
