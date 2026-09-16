#!/usr/bin/env bash
# Pulls the continuous probe's JSONL log out of the cluster and applies the pass/fail rules: strict
# zero-failure acceptance, a bounded gap between samples and measurement boundaries, and a minimum
# sample count in the fixed-duration baseline and post-transition windows.
#
# The transition window gets no fixed sample-count floor: its duration is whatever the `helm upgrade`
# actually took. A fast rollout might not contain three samples at a one-second interval. The gap
# check spans the entire measured period, including the start and end boundaries.
#
# A failed sample is never retried away here — evaluate-probes.sh only reads what probe.go already
# recorded. The probe process itself is the one that keeps sampling after a failure (see
# test-apps/dbaas-availability-probe/main.go); this script's job is only to refuse to hide that failure
# behind a summary that looks green.
#
# Required environment:
#   DBAAS_NAMESPACE
#   TRANSITION_TIMESTAMPS_FILE - written by deploy-fixture.sh and transition-aggregator.sh: probeStart,
#                                 transitionStart, transitionEnd, and postMeasurementEnd, one RFC3339
#                                 timestamp per line. The post window ends before the sample-service
#                                 restart, which is outside the availability measurement.
#   OUT_DIR                      - where probe.jsonl and probe-summary.txt are written (uploaded as
#                                  diagnostics regardless of outcome by the calling workflow step).
#   MIN_SAMPLES_PER_WINDOW      - minimum recorded samples required per probe kind in the baseline and
#                                  post windows (default 3; each runs 40-65s at a one-second interval, so
#                                  this is a low bar that only catches "the probe never actually ran").
#                                  Not applied to the transition window — see above.
#   MAX_GAP_SECONDS              - maximum allowed time between samples of the same probe kind, including
#                                  the interval from probeStart to the first sample and from the last
#                                  sample to postMeasurementEnd (default 3).
set -euo pipefail

: "${DBAAS_NAMESPACE:?}" "${TRANSITION_TIMESTAMPS_FILE:?}" "${OUT_DIR:?}"
MIN_SAMPLES_PER_WINDOW="${MIN_SAMPLES_PER_WINDOW:-3}"
MAX_GAP_SECONDS="${MAX_GAP_SECONDS:-3}"

mkdir -p "$OUT_DIR"
probe_log="$OUT_DIR/probe.jsonl"
summary_file="$OUT_DIR/probe-summary.txt"

if [ ! -f "$TRANSITION_TIMESTAMPS_FILE" ]; then
  echo "Transition timestamps file not found: $TRANSITION_TIMESTAMPS_FILE" >&2
  exit 1
fi
probe_start="$(sed -n 's/^probeStart=//p' "$TRANSITION_TIMESTAMPS_FILE" | head -1)"
transition_start="$(sed -n 's/^transitionStart=//p' "$TRANSITION_TIMESTAMPS_FILE" | head -1)"
transition_end="$(sed -n 's/^transitionEnd=//p' "$TRANSITION_TIMESTAMPS_FILE" | head -1)"
post_measurement_end="$(sed -n 's/^postMeasurementEnd=//p' "$TRANSITION_TIMESTAMPS_FILE" | head -1)"
if [ -z "$probe_start" ] || [ -z "$transition_start" ] || [ -z "$transition_end" ] || [ -z "$post_measurement_end" ]; then
  echo "A measurement timestamp is missing from $TRANSITION_TIMESTAMPS_FILE" >&2
  exit 1
fi
if [[ ! "$probe_start" < "$transition_start" || ! "$transition_start" < "$transition_end" || ! "$transition_end" < "$post_measurement_end" ]]; then
  echo "Measurement timestamps are not in chronological order: $TRANSITION_TIMESTAMPS_FILE" >&2
  exit 1
fi
echo "transition window: $transition_start .. $transition_end (measured through $post_measurement_end)"

fail=0

echo "=== Checking whether the continuous probe container ever restarted ==="
# A restart can silently create a gap in coverage — the container's own log buffer for the crashed
# instance is captured below (--previous), but a gap during that crash is not, so a restart is a
# failure on its own rather than something the sample-count checks below can be trusted to catch.
restart_count="$(kubectl -n "$DBAAS_NAMESPACE" get pods -l app.kubernetes.io/name=dbaas-availability-probe \
  -o jsonpath='{.items[0].status.containerStatuses[0].restartCount}' 2>/dev/null || true)"
if [ -n "$restart_count" ] && [ "$restart_count" != "0" ]; then
  echo "dbaas-availability-probe container restarted $restart_count time(s) during the run" >&2
  fail=1
fi

echo "=== Collecting probe log (current + previous, in case the container ever restarted) ==="
{
  kubectl -n "$DBAAS_NAMESPACE" logs deploy/dbaas-availability-probe --all-containers=true 2>/dev/null || true
  kubectl -n "$DBAAS_NAMESPACE" logs deploy/dbaas-availability-probe --all-containers=true --previous 2>/dev/null || true
} | grep -E '^\{' > "$probe_log" || true

total_lines="$(wc -l < "$probe_log" | tr -d ' ')"
echo "Collected $total_lines probe result lines"
if [ "$total_lines" -eq 0 ]; then
  echo "No probe results collected at all — the probe Deployment likely never ran" >&2
  exit 1
fi

{
  echo "Probe evaluation summary"
  echo "probeStart=$probe_start"
  echo "transitionStart=$transition_start"
  echo "transitionEnd=$transition_end"
  echo "postMeasurementEnd=$post_measurement_end"
  echo "probeContainerRestarts=${restart_count:-unknown}"
  echo
} > "$summary_file"

# Fixed, not derived from the log: a probe kind that never produced a single line (for example, its
# goroutine never started) must fail the run rather than silently being skipped because a dynamic
# `jq -r '.probe' | sort -u` never saw it. Must match the "checks" map keys in
# test-apps/dbaas-availability-probe/main.go exactly.
expected_probe_kinds="aggregator-ready aggregator-health dbaas-classifier sample-postgres-ping"

# Converts a fixed-width "...T..:..:...NNNNNNNNNZ" timestamp (see probe.go's timestampLayout) to a
# fractional Unix epoch. jq's builtin fromdateiso8601 cannot parse the fractional-second part, so the
# integer-second prefix and the 9-digit fraction are parsed and combined separately.
read -r -d '' JQ_EPOCH_DEF <<'EOF' || true
def epoch: (.[0:19] + "Z" | strptime("%Y-%m-%dT%H:%M:%SZ") | mktime) + ((.[20:29] | tonumber) / 1000000000);
EOF

for kind in $expected_probe_kinds; do
  baseline_total=$(jq -c --arg k "$kind" --arg b "$probe_start" --arg s "$transition_start" \
    'select(.probe==$k and .timestamp>=$b and .timestamp<$s)' "$probe_log" | wc -l | tr -d ' ')
  baseline_fail=$(jq -c --arg k "$kind" --arg b "$probe_start" --arg s "$transition_start" \
    'select(.probe==$k and .timestamp>=$b and .timestamp<$s and .success==false)' "$probe_log" | wc -l | tr -d ' ')

  transition_total=$(jq -c --arg k "$kind" --arg s "$transition_start" --arg e "$transition_end" \
    'select(.probe==$k and .timestamp>=$s and .timestamp<=$e)' "$probe_log" | wc -l | tr -d ' ')
  transition_fail=$(jq -c --arg k "$kind" --arg s "$transition_start" --arg e "$transition_end" \
    'select(.probe==$k and .timestamp>=$s and .timestamp<=$e and .success==false)' "$probe_log" | wc -l | tr -d ' ')

  # Bounded above by postMeasurementEnd: samples recorded during/after the intentional sample-service
  # restart that follows are fixture activity, not part of what this evaluator measures.
  post_total=$(jq -c --arg k "$kind" --arg e "$transition_end" --arg p "$post_measurement_end" \
    'select(.probe==$k and .timestamp>$e and .timestamp<=$p)' "$probe_log" | wc -l | tr -d ' ')
  post_fail=$(jq -c --arg k "$kind" --arg e "$transition_end" --arg p "$post_measurement_end" \
    'select(.probe==$k and .timestamp>$e and .timestamp<=$p and .success==false)' "$probe_log" | wc -l | tr -d ' ')

  # Include the measurement boundaries so a probe that starts late or stops early cannot pass.
  # Sort samples because a restarted container's previous log is appended after its current log.
  max_gap=$(jq -s -r --arg k "$kind" --arg s "$probe_start" --arg p "$post_measurement_end" "
    $JQ_EPOCH_DEF
    ([\$s | epoch] + ([.[] | select(.probe==\$k and .timestamp>=\$s and .timestamp<=\$p) | .timestamp | epoch] | sort) + [\$p | epoch]) as \$t
    | [range(1; (\$t|length)) | \$t[.] - \$t[. - 1]] | max
  " "$probe_log")

  {
    echo "probe=$kind"
    echo "  baseline:   total=$baseline_total fail=$baseline_fail"
    echo "  transition: total=$transition_total fail=$transition_fail"
    echo "  post:       total=$post_total fail=$post_fail"
    printf "  max gap in measured window: %.3fs (limit %ss)\n" "$max_gap" "$MAX_GAP_SECONDS"
  } | tee -a "$summary_file"

  # The transition has no fixed sample-count floor because its duration depends on Helm.
  if [ "$baseline_total" -lt "$MIN_SAMPLES_PER_WINDOW" ] || \
     [ "$post_total" -lt "$MIN_SAMPLES_PER_WINDOW" ]; then
    echo "  INSUFFICIENT SAMPLES for probe=$kind (need >= $MIN_SAMPLES_PER_WINDOW in baseline/post)" | tee -a "$summary_file"
    fail=1
  fi
  if [ "$baseline_fail" -ne 0 ] || [ "$transition_fail" -ne 0 ] || [ "$post_fail" -ne 0 ]; then
    echo "  FAILURE RECORDED for probe=$kind" | tee -a "$summary_file"
    fail=1
  fi
  if ! awk -v g="$max_gap" -v limit="$MAX_GAP_SECONDS" 'BEGIN { exit !(g <= limit) }'; then
    echo "  GAP TOO LARGE for probe=$kind (${max_gap}s > ${MAX_GAP_SECONDS}s) — the probe went quiet somewhere" | tee -a "$summary_file"
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  echo "=== Probe evaluation FAILED (see $summary_file) ===" | tee -a "$summary_file"
  exit 1
fi

echo "=== Probe evaluation PASSED: zero failures and continuous sample coverage ===" | tee -a "$summary_file"
