#!/usr/bin/env bash
# Upgrades or downgrades the dbaas-aggregator release with one `helm upgrade` onto the TARGET-version
# chart and image while the continuous probes remain active.
#
# Required environment:
#   TARGET_DIR            - target-version qubership-dbaas checkout (only its
#                            helm-templates/dbaas-aggregator chart is used).
#   HARNESS_DIR            - current-branch checkout containing the values overlay.
#   PG_NAMESPACE, DBAAS_NAMESPACE
#   TARGET_TAG              - image tag (e.g. v6.15.0) to transition the aggregator to.
#   POSTGRES_PASSWORD, DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD, DBAAS_TENANT_PASSWORD,
#   DBAAS_DB_EDITOR_CREDENTIALS_PASSWORD, DISCR_TOOL_USER_PASSWORD, BACKUP_DAEMON_DBAAS_ACCESS_PASSWORD
#                            - identical values passed to deploy-fixture.sh; re-rendering the same
#                              overlay with a different TAG must not change any credential the aggregator
#                              was already deployed with.
#   TRANSITION_TIMESTAMPS_FILE
#                            - this script appends transitionStart / transitionEnd / postMeasurementEnd
#                              (UTC RFC3339) here for evaluate-probes.sh and collect-diagnostics.sh to
#                              read. postMeasurementEnd marks the end of the strictly-measured window,
#                              recorded after the post-transition readiness check.
#   POST_TRANSITION_SECONDS - how long to keep probing after rollout completes (default 65; plan requires
#                              at least 60).
set -euo pipefail

: "${TARGET_DIR:?}" "${HARNESS_DIR:?}" "${DBAAS_NAMESPACE:?}" "${TARGET_TAG:?}"
: "${POSTGRES_PASSWORD:?}"
: "${DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD:?}" "${DBAAS_TENANT_PASSWORD:?}"
: "${DBAAS_DB_EDITOR_CREDENTIALS_PASSWORD:?}" "${DISCR_TOOL_USER_PASSWORD:?}"
: "${BACKUP_DAEMON_DBAAS_ACCESS_PASSWORD:?}" "${TRANSITION_TIMESTAMPS_FILE:?}"

POST_TRANSITION_SECONDS="${POST_TRANSITION_SECONDS:-65}"
DBAAS_VALUES_FILE="$HARNESS_DIR/.github/scripts/dbaas-transition/dbaas-values-transition.yaml"

# The overlay also references PG_NAMESPACE, DBAAS_SERVICE_NAME, REGION_DBAAS, NODE_SELECTOR_DBAAS_KEY,
# and KUBERNETES_M2M_ENABLED — deploy-fixture.sh set these directly for its own envsubst call, but that
# does not survive into this separate script invocation, so restate the same defaults bootstrap/local.mk
# uses (deploy-fixture.sh's aggregator install passes the same values explicitly, for the same reason).
export PG_NAMESPACE="${PG_NAMESPACE:-postgres}"
export DBAAS_SERVICE_NAME="${DBAAS_SERVICE_NAME:-dbaas-aggregator}"
export REGION_DBAAS="${REGION_DBAAS:-database}"
export NODE_SELECTOR_DBAAS_KEY="${NODE_SELECTOR_DBAAS_KEY:-region}"
export KUBERNETES_M2M_ENABLED=true
export TAG="$TARGET_TAG"
export DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD DBAAS_TENANT_PASSWORD DBAAS_DB_EDITOR_CREDENTIALS_PASSWORD \
  DISCR_TOOL_USER_PASSWORD BACKUP_DAEMON_DBAAS_ACCESS_PASSWORD POSTGRES_PASSWORD

echo "=== Rendering the same values overlay with TAG=$TARGET_TAG (no --reuse-values) ==="
rendered_values="$(mktemp)"
envsubst < "$DBAAS_VALUES_FILE" > "$rendered_values"

echo "transitionStart=$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)" >> "$TRANSITION_TIMESTAMPS_FILE"

echo "=== helm upgrade dbaas-aggregator onto the target-version chart/image (no --atomic: a failed state must stay up for diagnostics) ==="
helm upgrade dbaas-aggregator \
  "$TARGET_DIR/helm-templates/dbaas-aggregator" \
  --namespace "$DBAAS_NAMESPACE" \
  -f "$rendered_values" \
  --wait --timeout 5m
rm -f "$rendered_values"

echo "transitionEnd=$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)" >> "$TRANSITION_TIMESTAMPS_FILE"

echo "=== Verifying the running aggregator image matches the requested target tag ==="
running_image="$(kubectl -n "$DBAAS_NAMESPACE" get deployment dbaas-aggregator -o jsonpath='{.spec.template.spec.containers[0].image}')"
echo "running image: $running_image"
case "$running_image" in
  *":$TARGET_TAG") ;;
  *)
    echo "Expected the aggregator image tag to be $TARGET_TAG, got: $running_image" >&2
    exit 1
    ;;
esac

echo "=== Continuing to probe for ${POST_TRANSITION_SECONDS}s after rollout completion ==="
sleep "$POST_TRANSITION_SECONDS"

echo "=== Post-transition check: both aggregator replicas are ready ==="
ready="$(kubectl -n "$DBAAS_NAMESPACE" get deployment dbaas-aggregator -o jsonpath='{.status.readyReplicas}')"
if [ "${ready:-0}" -ne 2 ]; then
  echo "Expected 2 ready aggregator replicas after the transition, got ${ready:-0}" >&2
  exit 1
fi
echo "postMeasurementEnd=$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)" >> "$TRANSITION_TIMESTAMPS_FILE"

echo "=== transition-aggregator.sh done ==="
