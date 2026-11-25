#!/usr/bin/env bash
# Relies on job-level env: TEST_NAMESPACE and the {GO,SPRING,QUARKUS}_SERVICE_NAME /
# _IMAGE_REPOSITORY / _IMAGE_TAG variables.
set -euo pipefail

AGG_URL="$1"
MOUNT_SECRETS="$2"
M2M_AUTH_MODE="${3:-legacy}"
SPRING_M2M_AUTH_MODE="${4:-$M2M_AUTH_MODE}"

helm upgrade --install "$GO_SERVICE_NAME" \
  test-apps/go-test-app-service/helm-templates/go-test-app-service \
  --namespace "$TEST_NAMESPACE" \
  --set NAMESPACE="$TEST_NAMESPACE" \
  --set IMAGE_REPOSITORY="$GO_IMAGE_REPOSITORY" \
  --set TAG="$GO_IMAGE_TAG" \
  --set IMAGE_PULL_POLICY="Never" \
  --set MOUNT_SECRETS="$MOUNT_SECRETS" \
  --set M2M_AUTH_MODE="$M2M_AUTH_MODE" \
  --set DBAAS_AGENT="$AGG_URL" \
  --set API_DBAAS_ADDRESS="$AGG_URL"

helm upgrade --install "$SPRING_SERVICE_NAME" \
  test-apps/spring-test-app-service/helm-templates/spring-test-app-service \
  --namespace "$TEST_NAMESPACE" \
  --set NAMESPACE="$TEST_NAMESPACE" \
  --set IMAGE_REPOSITORY="$SPRING_IMAGE_REPOSITORY" \
  --set TAG="$SPRING_IMAGE_TAG" \
  --set IMAGE_PULL_POLICY="Never" \
  --set MOUNT_SECRETS="$MOUNT_SECRETS" \
  --set M2M_AUTH_MODE="$SPRING_M2M_AUTH_MODE" \
  --set API_DBAAS_ADDRESS="$AGG_URL"

helm upgrade --install "$QUARKUS_SERVICE_NAME" \
  test-apps/quarkus-test-app-service/helm-templates/quarkus-test-app-service \
  --namespace "$TEST_NAMESPACE" \
  --set NAMESPACE="$TEST_NAMESPACE" \
  --set IMAGE_REPOSITORY="$QUARKUS_IMAGE_REPOSITORY" \
  --set TAG="$QUARKUS_IMAGE_TAG" \
  --set IMAGE_PULL_POLICY="Never" \
  --set MOUNT_SECRETS="$MOUNT_SECRETS" \
  --set M2M_AUTH_MODE="$M2M_AUTH_MODE" \
  --set API_DBAAS_ADDRESS="$AGG_URL"
