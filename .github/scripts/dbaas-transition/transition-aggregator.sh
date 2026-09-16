#!/usr/bin/env bash
# Upgrades or downgrades the dbaas-aggregator release with one `helm upgrade` onto the TARGET-version
# chart and image. The other components remain unchanged during measurement. Afterward, the sample
# service restarts and the post-transition Job checks a fresh DBaaS lookup and the seeded record.
#
# Required environment:
#   TARGET_DIR            - target-version qubership-dbaas checkout (only its
#                            helm-templates/dbaas-aggregator chart is used).
#   HARNESS_DIR            - current-branch checkout (values overlay + probe image).
#   PG_NAMESPACE, DBAAS_NAMESPACE
#   TARGET_TAG              - image tag (e.g. v6.15.0) to transition the aggregator to.
#   PROBE_IMAGE_REPOSITORY, PROBE_IMAGE_TAG
#   POSTGRES_PASSWORD, DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD, DBAAS_TENANT_PASSWORD,
#   DBAAS_DB_EDITOR_CREDENTIALS_PASSWORD, DISCR_TOOL_USER_PASSWORD, BACKUP_DAEMON_DBAAS_ACCESS_PASSWORD
#                            - identical values passed to deploy-fixture.sh; re-rendering the same
#                              overlay with a different TAG must not change any credential the aggregator
#                              was already deployed with.
#   FIXTURE_FINGERPRINT_FILE - must contain the JSON line captured by deploy-fixture.sh.
#   TRANSITION_TIMESTAMPS_FILE
#                            - this script appends transitionStart / transitionEnd / postMeasurementEnd
#                              (UTC RFC3339) here for evaluate-probes.sh and collect-diagnostics.sh to
#                              read. postMeasurementEnd marks the end of the strictly-measured window,
#                              recorded right before the intentional sample-service restart below — a
#                              sample-service restart may interrupt sample-postgres-ping briefly, and
#                              that is not an aggregator availability regression, so
#                              evaluate-probes.sh must not hold it to the zero-failure rule.
#   FIXED_COMPONENT_IMAGES_FILE
#                            - operator/patroni/adapter/sample-service images deploy-fixture.sh recorded
#                              before the transition; this script re-reads the same images afterward and
#                              fails if any of them drifted.
#   POST_TRANSITION_SECONDS - how long to keep probing after rollout completes (default 65; plan requires
#                              at least 60).
set -euo pipefail

: "${TARGET_DIR:?}" "${HARNESS_DIR:?}" "${DBAAS_NAMESPACE:?}" "${TARGET_TAG:?}"
: "${PROBE_IMAGE_REPOSITORY:?}" "${PROBE_IMAGE_TAG:?}" "${POSTGRES_PASSWORD:?}"
: "${DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD:?}" "${DBAAS_TENANT_PASSWORD:?}"
: "${DBAAS_DB_EDITOR_CREDENTIALS_PASSWORD:?}" "${DISCR_TOOL_USER_PASSWORD:?}"
: "${BACKUP_DAEMON_DBAAS_ACCESS_PASSWORD:?}" "${FIXTURE_FINGERPRINT_FILE:?}"
: "${TRANSITION_TIMESTAMPS_FILE:?}" "${FIXED_COMPONENT_IMAGES_FILE:?}"

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

echo "=== Post-transition prerequisite check: both replicas still ready, no fixed component drifted ==="
ready="$(kubectl -n "$DBAAS_NAMESPACE" get deployment dbaas-aggregator -o jsonpath='{.status.readyReplicas}')"
if [ "${ready:-0}" -ne 2 ]; then
  echo "Expected 2 ready aggregator replicas after the transition, got ${ready:-0}" >&2
  exit 1
fi
current_operator_image="$(kubectl -n "$DBAAS_NAMESPACE" get deployment dbaas-operator -o jsonpath='{.spec.template.spec.containers[0].image}')"
current_patroni_image="$(kubectl -n "$PG_NAMESPACE" get pods -l app=patroni -o jsonpath='{.items[0].spec.containers[0].image}')"
current_adapter_image="$(kubectl -n "$PG_NAMESPACE" get deployment dbaas-postgres-adapter -o jsonpath='{.spec.template.spec.containers[0].image}')"
# go-test-app-service's image must also stay unchanged — it is checked here, before the intentional
# restart below. A restart does not change the image reference, so checking before or after would give
# the same answer; checking here keeps every fixed-component comparison in one place.
current_sample_image="$(kubectl -n "$DBAAS_NAMESPACE" get deployment go-test-app-service -o jsonpath='{.spec.template.spec.containers[0].image}')"
drifted=0
while IFS='=' read -r component initial_image; do
  case "$component" in
    dbaas-operator) current="$current_operator_image" ;;
    patroni) current="$current_patroni_image" ;;
    dbaas-postgres-adapter) current="$current_adapter_image" ;;
    go-test-app-service) current="$current_sample_image" ;;
    *) continue ;;
  esac
  if [ "$current" != "$initial_image" ]; then
    echo "Fixed component '$component' image drifted: was $initial_image, now $current" >&2
    drifted=1
  else
    echo "$component image unchanged: $current"
  fi
done < "$FIXED_COMPONENT_IMAGES_FILE"
if [ "$drifted" -ne 0 ]; then
  echo "A fixed component's image changed during the transition — see above" >&2
  exit 1
fi

# Everything above this point is the strictly-measured window: evaluate-probes.sh holds every sample
# up to here to the zero-failure rule. Everything below — the sample-service restart and the
# post-transition functional check — is deliberate fixture activity, not part of what's being
# measured, so it must not be able to fail the job by tripping the continuous probe's pass/fail gate.
echo "postMeasurementEnd=$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)" >> "$TRANSITION_TIMESTAMPS_FILE"

echo "=== Restarting the sample service to force a fresh DBaaS lookup outside the measured window ==="
kubectl -n "$DBAAS_NAMESPACE" rollout restart deployment/go-test-app-service
kubectl -n "$DBAAS_NAMESPACE" rollout status deployment/go-test-app-service --timeout=180s

echo "=== Post-transition functional verification (one-shot Job: read seeded record and check database identity) ==="
fingerprint_json="$(cat "$FIXTURE_FINGERPRINT_FILE")"
kubectl -n "$DBAAS_NAMESPACE" delete job dbaas-fixture-verify-post --ignore-not-found
verify_manifest="$(mktemp)"
cat > "$verify_manifest" <<EOF
apiVersion: batch/v1
kind: Job
metadata:
  name: dbaas-fixture-verify-post
  namespace: $DBAAS_NAMESPACE
spec:
  backoffLimit: 0
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: verify-post
          image: $PROBE_IMAGE_REPOSITORY:$PROBE_IMAGE_TAG
          imagePullPolicy: Never
          env:
            - name: PROBE_MODE
              value: verify-post
            - name: AGGREGATOR_URL
              value: http://dbaas-aggregator.$DBAAS_NAMESPACE:8080
            - name: SAMPLE_SERVICE_URL
              value: http://go-test-app-service.$DBAAS_NAMESPACE:8080
            - name: NAMESPACE
              value: $DBAAS_NAMESPACE
            - name: FINGERPRINT_JSON
              value: '$fingerprint_json'
          securityContext:
            readOnlyRootFilesystem: true
            runAsNonRoot: true
            runAsUser: 10001
            runAsGroup: 10001
            allowPrivilegeEscalation: false
            seccompProfile:
              type: RuntimeDefault
            capabilities:
              drop: [ALL]
EOF
kubectl apply -f "$verify_manifest"
rm -f "$verify_manifest"

if ! kubectl -n "$DBAAS_NAMESPACE" wait --for=condition=complete job/dbaas-fixture-verify-post --timeout=180s; then
  echo "Post-transition functional verification Job failed or timed out:" >&2
  kubectl -n "$DBAAS_NAMESPACE" logs job/dbaas-fixture-verify-post --all-containers=true >&2 || true
  exit 1
fi

echo "=== transition-aggregator.sh done ==="
