#!/usr/bin/env bash
# Periodic snapshots of what the aggregator and its PostgreSQL are blocked on.
#
# Aimed at the stall where every code path guarded by DBAAS_REPOSITORIES_MUTEX
# freezes: logical database writes and H2 cache reloads stop, external database
# registrations pile up unanswered, and the aggregator only recovers when the pod
# is replaced. Log lines alone cannot name the blocked frame, so each round takes
# a JVM thread dump and a PostgreSQL lock snapshot at the same moment.
#
# Runs in the background for the whole test step, so it must NEVER fail the job:
# no `set -e`, every kubectl call guarded, unavailable pods skipped.
set -uo pipefail

OUT_DIR="${OUT_DIR:-./thread-dumps}"
INTERVAL="${INTERVAL:-60}"
AGG_NS="${AGG_NS:-dbaas}"
AGG_DEPLOY="${AGG_DEPLOY:-dbaas-aggregator}"
PG_NS="${PG_NS:-postgres}"
PG_POD="${PG_POD:-pg-patroni-node1-0}"

mkdir -p "$OUT_DIR"

# SIGQUIT prints the dump to the JVM's stdout, which Fluent Bit already collects.
# jcmd returns it on our own stdout instead, which keeps the dumps timestamped and
# separate from a multi-megabyte pod log, so try jcmd first.
dump_threads() {
  local pod=$1 stamp=$2
  local out="$OUT_DIR/${pod}-${stamp}.threads.txt"

  # shellcheck disable=SC2016  # the body runs in the pod's shell, not this one
  kubectl -n "$AGG_NS" exec "$pod" -c "$AGG_DEPLOY" -- sh -c '
    pid=""
    for proc in /proc/[0-9]*; do
      [ -r "$proc/cmdline" ] || continue
      case "$(tr "\0" " " < "$proc/cmdline")" in
        *java*) pid="${proc#/proc/}"; break ;;
      esac
    done
    [ -n "$pid" ] || { echo "no java process found"; exit 1; }

    if command -v jcmd >/dev/null 2>&1; then
      jcmd "$pid" Thread.print -l
    else
      echo "jcmd unavailable, sent SIGQUIT to pid $pid — dump is in the pod stdout"
      kill -3 "$pid"
    fi
  ' > "$out" 2>&1

  if [ ! -s "$out" ]; then
    rm -f "$out"
  fi
}

# A thread parked on a PostgreSQL row lock looks like an ordinary socket read in the
# thread dump. pg_stat_activity names the blocker, so capture both sides.
dump_pg_locks() {
  local stamp=$1
  kubectl -n "$PG_NS" exec "$PG_POD" -- psql -U postgres -d postgres -X -P pager=off -c "
    SELECT now() AS captured_at, a.pid, a.state, a.wait_event_type, a.wait_event,
           age(clock_timestamp(), a.xact_start) AS xact_age,
           age(clock_timestamp(), a.query_start) AS query_age,
           cardinality(pg_blocking_pids(a.pid)) AS blockers,
           pg_blocking_pids(a.pid) AS blocked_by,
           left(a.query, 200) AS query
      FROM pg_stat_activity a
     WHERE a.backend_type = 'client backend'
       AND a.state <> 'idle'
     ORDER BY xact_age DESC NULLS LAST;
  " > "$OUT_DIR/pg-activity-${stamp}.txt" 2>&1

  if [ ! -s "$OUT_DIR/pg-activity-${stamp}.txt" ]; then
    rm -f "$OUT_DIR/pg-activity-${stamp}.txt"
  fi
}

echo "Watching $AGG_DEPLOY in $AGG_NS every ${INTERVAL}s, writing to $OUT_DIR"

while :; do
  stamp=$(date -u +%Y%m%dT%H%M%SZ)

  pods=$(kubectl -n "$AGG_NS" get pods -l "name=$AGG_DEPLOY" \
           --field-selector=status.phase=Running -o name 2>/dev/null)
  for pod in $pods; do
    dump_threads "${pod#pod/}" "$stamp"
  done

  dump_pg_locks "$stamp"
  sleep "$INTERVAL"
done
