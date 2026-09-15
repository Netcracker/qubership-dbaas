#!/usr/bin/env bash
# Pulls the continuous probe's JSONL log out of the cluster and applies the pass/fail rules: strict
# zero-failure acceptance, with enough samples recorded in each of the baseline / transition /
# post-transition windows to prove the probe was actually running throughout, not just quiet.
#
# A failed sample is never retried away here — evaluate-probes.sh only reads what probe.go already
# recorded. The probe process itself is the one that keeps sampling after a failure (see
# test-apps/dbaas-availability-probe/main.go); this script's job is only to refuse to hide that failure
# behind a summary that looks green.
#
# Required environment:
#   DBAAS_NAMESPACE
#   TRANSITION_TIMESTAMPS_FILE - written by transition-aggregator.sh: transitionStart=<RFC3339> and
#                                 transitionEnd=<RFC3339>, one per line.
#   OUT_DIR                      - where probe.jsonl and probe-summary.txt are written (uploaded as
#                                  diagnostics regardless of outcome by the calling workflow step).
#   MIN_SAMPLES_PER_WINDOW      - minimum recorded samples required per probe kind in each window
#                                  (default 3; baseline/post windows run 40-65s at a one-second interval, so
#                                  this is a low bar that only catches "the probe never actually ran").
set -euo pipefail

: "${DBAAS_NAMESPACE:?}" "${TRANSITION_TIMESTAMPS_FILE:?}" "${OUT_DIR:?}"
MIN_SAMPLES_PER_WINDOW="${MIN_SAMPLES_PER_WINDOW:-3}"

mkdir -p "$OUT_DIR"
probe_log="$OUT_DIR/probe.jsonl"
summary_file="$OUT_DIR/probe-summary.txt"

if [ ! -f "$TRANSITION_TIMESTAMPS_FILE" ]; then
  echo "Transition timestamps file not found: $TRANSITION_TIMESTAMPS_FILE" >&2
  exit 1
fi
transition_start="$(sed -n 's/^transitionStart=//p' "$TRANSITION_TIMESTAMPS_FILE" | head -1)"
transition_end="$(sed -n 's/^transitionEnd=//p' "$TRANSITION_TIMESTAMPS_FILE" | head -1)"
if [ -z "$transition_start" ] || [ -z "$transition_end" ]; then
  echo "transitionStart/transitionEnd missing from $TRANSITION_TIMESTAMPS_FILE" >&2
  exit 1
fi
echo "transition window: $transition_start .. $transition_end"

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
  echo "transitionStart=$transition_start"
  echo "transitionEnd=$transition_end"
  echo "probeContainerRestarts=${restart_count:-unknown}"
  echo
} > "$summary_file"

# Fixed, not derived from the log: a probe kind that never produced a single line (for example, its
# goroutine never started) must fail the run rather than silently being skipped because a dynamic
# `jq -r '.probe' | sort -u` never saw it. Must match the "checks" map keys in
# test-apps/dbaas-availability-probe/main.go exactly.
expected_probe_kinds="aggregator-ready aggregator-health dbaas-classifier sample-postgres-ping"

for kind in $expected_probe_kinds; do
  baseline_total=$(jq -c --arg k "$kind" --arg s "$transition_start" \
    'select(.probe==$k and .timestamp<$s)' "$probe_log" | wc -l | tr -d ' ')
  baseline_fail=$(jq -c --arg k "$kind" --arg s "$transition_start" \
    'select(.probe==$k and .timestamp<$s and .success==false)' "$probe_log" | wc -l | tr -d ' ')

  transition_total=$(jq -c --arg k "$kind" --arg s "$transition_start" --arg e "$transition_end" \
    'select(.probe==$k and .timestamp>=$s and .timestamp<=$e)' "$probe_log" | wc -l | tr -d ' ')
  transition_fail=$(jq -c --arg k "$kind" --arg s "$transition_start" --arg e "$transition_end" \
    'select(.probe==$k and .timestamp>=$s and .timestamp<=$e and .success==false)' "$probe_log" | wc -l | tr -d ' ')

  post_total=$(jq -c --arg k "$kind" --arg e "$transition_end" \
    'select(.probe==$k and .timestamp>$e)' "$probe_log" | wc -l | tr -d ' ')
  post_fail=$(jq -c --arg k "$kind" --arg e "$transition_end" \
    'select(.probe==$k and .timestamp>$e and .success==false)' "$probe_log" | wc -l | tr -d ' ')

  {
    echo "probe=$kind"
    echo "  baseline:   total=$baseline_total fail=$baseline_fail"
    echo "  transition: total=$transition_total fail=$transition_fail"
    echo "  post:       total=$post_total fail=$post_fail"
  } | tee -a "$summary_file"

  if [ "$baseline_total" -lt "$MIN_SAMPLES_PER_WINDOW" ] || \
     [ "$transition_total" -lt "$MIN_SAMPLES_PER_WINDOW" ] || \
     [ "$post_total" -lt "$MIN_SAMPLES_PER_WINDOW" ]; then
    echo "  INSUFFICIENT SAMPLES for probe=$kind (need >= $MIN_SAMPLES_PER_WINDOW per window)" | tee -a "$summary_file"
    fail=1
  fi
  if [ "$baseline_fail" -ne 0 ] || [ "$transition_fail" -ne 0 ] || [ "$post_fail" -ne 0 ]; then
    echo "  FAILURE RECORDED for probe=$kind" | tee -a "$summary_file"
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  echo "=== Probe evaluation FAILED (see $summary_file) ===" | tee -a "$summary_file"
  exit 1
fi

echo "=== Probe evaluation PASSED: zero failures, sufficient samples in every window for every probe kind ===" | tee -a "$summary_file"
