#!/usr/bin/env bash
# Deploy all three sample services (go / spring / quarkus) with a given aggregator URL and
# secret-mount mode, so the integration test can be run in two phases:
#   - mount proof:   <broken aggregator URL> true   (working DML can only come from the mount)
#   - REST fallback: <working aggregator URL> false  (no mount → client resolves via the REST API)
#
# Usage: deploy-sample-services.sh <AGG_URL> <MOUNT_SECRETS>
# Relies on job-level env: TEST_NAMESPACE and the {GO,SPRING,QUARKUS}_SERVICE_NAME /
# _IMAGE_REPOSITORY / _IMAGE_TAG variables.
set -euo pipefail

AGG_URL="$1"
MOUNT_SECRETS="$2"

helm upgrade --install "$GO_SERVICE_NAME" \
  test-apps/go-test-app-service/helm-templates/go-test-app-service \
  -f test-apps/go-test-app-service/helm-templates/go-test-app-service/resource-profiles/dev.yaml \
  --namespace "$TEST_NAMESPACE" \
  --set NAMESPACE="$TEST_NAMESPACE" \
  --set IMAGE_REPOSITORY="$GO_IMAGE_REPOSITORY" \
  --set TAG="$GO_IMAGE_TAG" \
  --set IMAGE_PULL_POLICY="Never" \
  --set MOUNT_SECRETS="$MOUNT_SECRETS" \
  --set DBAAS_AGENT="$AGG_URL"

helm upgrade --install "$SPRING_SERVICE_NAME" \
  test-apps/spring-test-app-service/helm-templates/spring-test-app-service \
  -f test-apps/spring-test-app-service/helm-templates/spring-test-app-service/resource-profiles/dev.yaml \
  --namespace "$TEST_NAMESPACE" \
  --set NAMESPACE="$TEST_NAMESPACE" \
  --set IMAGE_REPOSITORY="$SPRING_IMAGE_REPOSITORY" \
  --set TAG="$SPRING_IMAGE_TAG" \
  --set IMAGE_PULL_POLICY="Never" \
  --set MOUNT_SECRETS="$MOUNT_SECRETS" \
  --set API_DBAAS_ADDRESS="$AGG_URL"

helm upgrade --install "$QUARKUS_SERVICE_NAME" \
  test-apps/quarkus-test-app-service/helm-templates/quarkus-test-app-service \
  -f test-apps/quarkus-test-app-service/helm-templates/quarkus-test-app-service/resource-profiles/dev.yaml \
  --namespace "$TEST_NAMESPACE" \
  --set NAMESPACE="$TEST_NAMESPACE" \
  --set IMAGE_REPOSITORY="$QUARKUS_IMAGE_REPOSITORY" \
  --set TAG="$QUARKUS_IMAGE_TAG" \
  --set IMAGE_PULL_POLICY="Never" \
  --set MOUNT_SECRETS="$MOUNT_SECRETS" \
  --set API_DBAAS_ADDRESS="$AGG_URL"
