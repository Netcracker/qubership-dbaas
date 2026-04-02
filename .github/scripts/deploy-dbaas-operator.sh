#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

docker build -t dbaas-operator:dev "${REPO_ROOT}/dbaas-operator"
kind load docker-image dbaas-operator:dev --name kind
kubectl apply -f "${REPO_ROOT}/.github/declarations/dbaas-operator.yaml"
kubectl rollout status deployment/dbaas-operator -n dbaas --timeout=120s
