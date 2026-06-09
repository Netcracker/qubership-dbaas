# Operator Metrics

Custom Prometheus metrics exposed by the DBaaS Operator at `/metrics`.
Each metric is registered at startup and scraped by Prometheus.

---

## Secret Watcher

### `dbaas_reconcile_trigger_total`
**Type:** Counter

**Labels:** `controller`, `trigger`

`controller`: `externaldatabase` | `databaseaccesspolicy` | `internaldatabase`

`trigger`: `spec_change` | `secret_change` | `namespace_binding_change` | `polling`

Counts every reconcile invocation, tagged by what caused it. A `secret_change` increment means the watcher detected a credential rotation and re-registered the database automatically without a CR spec change.

Trigger classification is best-effort. Overlapping events for the same object can swap labels between queued reconciles. A stamped trigger can also be consumed by a reconcile that is later skipped on namespace ownership, for example while a matching `NamespaceBinding` has not yet propagated to the informer cache; the follow-up reconcile then falls back to `spec_change`. The metric is useful for dashboard-level distribution and feature proof, but it should not be used as exact causal tracing or as an alert source.

**Dashboard:** Stacked time series by trigger. A spike in `secret_change` is direct proof the feature fired.

---

### `dbaas_secret_rotation_propagation_seconds`
**Type:** Histogram

Measures the end-to-end time from a Secret change being detected to the ExternalDatabase reaching `Succeeded`. This is the SLO metric for credential rotation: it answers "how quickly does a password change take effect?"

The start timestamp is kept in memory by the active operator process. If the operator restarts after the Secret event and before the ExternalDatabase succeeds, that specific propagation sample is not recorded.

**Dashboard:** P50 / P90 / P99 latency lines over time.

---

### `dbaas_secret_resolution_errors_total`
**Type:** Counter

**Labels:** `namespace`, `reason`

Counts failures reading a credentials Secret during reconcile. Each increment means a credential rotation or CR create/update could not resolve valid credentials until the Secret is fixed.

**Dashboard:** Error rate time series per namespace/reason. Any non-zero sustained rate is actionable.

---

## Aggregator Interaction

### `dbaas_aggregator_request_duration_seconds`
**Type:** Histogram

**Labels:** `controller`, `operation` (`register_external_database` | `apply_config` | `poll_status`)

Tracks HTTP call latency to dbaas-aggregator per operation type. Identifies whether the aggregator is the bottleneck in slow registrations or provisioning.

**Dashboard:** P50 / P90 / P99 latency lines, split by controller and operation.

---

### `dbaas_aggregator_requests_total`
**Type:** Counter

**Labels:** `controller`, `operation`, `result` (`success` | `auth_error` | `spec_rejection` | `server_error` | `network_error`)

Counts every aggregator call by outcome. The `result` label routes failures to the right owner:

| Result | Meaning | Owner |
|---|---|---|
| `success` | Call succeeded | - |
| `spec_rejection` | Aggregator rejected the payload by content | Application team / CR owner |
| `auth_error` | Operator credentials invalid | Platform team |
| `server_error` | Aggregator returned 5xx | Aggregator team |
| `network_error` | No response was received | Network / platform team |

**Dashboard:** Stacked rate by result. Failure buckets are split because they have different owners.

---

## Dashboard-Only Resource Health Substitutes

The operator does not currently export `dbaas_resource_phase`, `dbaas_resources_stuck`, or `dbaas_async_operations_in_flight`.

Those gauges require exact current-state knowledge across DBaaS CRs. A periodic sweeper can compute them, but it must list every relevant CR in each owned namespace. That cost scales linearly with the number of CRs and becomes less attractive as DBaaS adoption grows.

A custom in-memory tracker could avoid periodic listing, but it would need to handle startup sync, status-only updates, deletes, operator restarts, leader changes, and NamespaceBinding ownership changes. That complexity is not justified for the current dashboard signal.

The dashboard can still project close substitutes from event/rate metrics. These are not exact current-state counts; they show recent activity that usually explains why resources would be unhealthy.

### Substitute for `dbaas_resource_phase`

`dbaas_resource_phase` would answer: "how many CRs are currently in each phase?"

Without a direct state gauge, use failure distribution over the dashboard range as the closest operational view of resource health:

```promql
sum by (controller, result) (
  increase(dbaas_aggregator_requests_total{result!="success"}[$__range])
)
```

```promql
sum by (exported_namespace, reason) (
  increase(dbaas_secret_resolution_errors_total{namespace="<operator-namespace>"}[$__range])
)
```

Dashboard title: `Recent Failed Operations by Owner`

This shows failure pressure by owner bucket, but it does not count current CR phases.

### Substitute for `dbaas_resources_stuck`

`dbaas_resources_stuck` would answer: "has any CR stayed in a bad state long enough to need attention?"

Without a direct phase gauge, use repeated failure activity as a stuck-like signal. This is intentionally different from the resource-health distribution above: it looks for failures that keep recurring in the recent window.

```promql
sum by (controller, result) (
  rate(dbaas_aggregator_requests_total{result!="success"}[15m])
) > 0
```

```promql
sum by (exported_namespace, reason) (
  rate(dbaas_secret_resolution_errors_total{namespace="<operator-namespace>"}[15m])
) > 0
```

Dashboard title: `Sustained Failure Signals`

This is a close alerting substitute for quiet stuck resources, but it is event-based. If a CR failed once and then no more reconciles happened, this query will eventually go quiet.

If a reliable non-sweep phase metric is introduced later, the preferred stuck query would be:

```promql
min_over_time(dbaas_resource_phase{phase=~"BackingOff|InvalidConfiguration"}[15m]) > 0
```

### Substitute for `dbaas_async_operations_in_flight`

`dbaas_async_operations_in_flight` would answer: "how many InternalDatabases currently have an active tracking ID?"

Without a direct state gauge, use a backlog proxy: polling activity without matching terminal completions.

```promql
sum(
  rate(dbaas_reconcile_trigger_total{controller="internaldatabase",trigger="polling"}[15m])
)
```

```promql
sum(
  rate(dbaas_async_operation_duration_seconds_count[15m])
)
```

Dashboard title: `Async Provisioning Activity`

If the first query is non-zero while the second is near zero, InternalDatabases are being polled but not reaching terminal states. It does not count currently in-flight operations.

---

## Async Provisioning

### `dbaas_async_operation_duration_seconds`
**Type:** Histogram

**Labels:** `result` (`success` | `failed` | `terminated`)

End-to-end provisioning time from async submission (HTTP 202) to a terminal state. This is the user-visible provisioning SLO: the time a user waits from submitting a InternalDatabase to having a usable database.

`terminated` means the aggregator cancelled the operation mid-flight; the operator resubmits automatically.

The start timestamp is kept in memory after the operator submits an async operation. If the operator restarts before the terminal poll result, that operation is still reconciled correctly, but its duration sample is not recorded.

Operational note: alerts on this histogram should rely on absolute latency percentiles, not on sample-rate dips. A drop in `rate(dbaas_async_operation_duration_seconds_count[...])` after an operator restart usually reflects lost samples, not a slowdown in actual provisioning.

**Dashboard:** P50 / P90 / P99 per result label. Completion rate (operations/s) as a secondary panel.
