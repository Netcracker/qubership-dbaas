# DBaaS Operator — Metrics

This page documents the Prometheus metrics exposed by **dbaas-operator** and how to use them.

## Table of Contents

- [Where metrics are exposed](#where-metrics-are-exposed)
- [Metric categories](#metric-categories)
- [Event & operation metrics](#event--operation-metrics)
- [Resource-state metrics](#resource-state-metrics)
- [Grafana dashboard](#grafana-dashboard)
- [Label value reference](#label-value-reference)
- [Standard runtime metrics](#standard-runtime-metrics)
- [Example PromQL queries](#example-promql-queries)
- [Cardinality & high-availability notes](#cardinality--high-availability-notes)

**Related documents:** [DBaaS Operator](../DBaaS%20Operator.md) — custom resources, phases, and
condition reasons behind these metrics.

---

## Where metrics are exposed

Metrics are exposed on a dedicated HTTP scrape endpoint. The operator serves **no application API**
of its own — only this `/metrics` endpoint and Kubernetes health probes.

| Property | Value |
|---|---|
| Path | `/metrics` |
| Bind address | `:8080` (configurable via the `--http-bind-address` flag) |
| Protocol | Plain HTTP (no authentication, no TLS) |
| Format | Prometheus text exposition format |

On the operator pod the ports are named:

| Port name | Container port | Purpose |
|---|---|---|
| `metrics` | `8080` | Prometheus `/metrics` endpoint |
| `web` | `8081` | Kubernetes liveness/readiness probes |

### Scraping (shipped by the Helm chart)

The chart ships scraping and a dashboard out of the box, both gated on `DBAAS_OPERATOR_ENABLED`
**and** `MONITORING_ENABLED`. In the chart's `values.yaml` these default to
`DBAAS_OPERATOR_ENABLED: false` and `MONITORING_ENABLED: true`, so the operator itself is **off by
default** — once you enable it (`DBAAS_OPERATOR_ENABLED=true`), the `PodMonitor` and dashboard are
created automatically (with `MONITORING_ENABLED` left at its `true` default):

- **`PodMonitor`** (`<SERVICE_NAME>-pod-monitor`, `monitoring.coreos.com/v1`) — selects the operator
  pods (`name: <SERVICE_NAME>`) in the operator's namespace and scrapes the `metrics` port at
  `/metrics` every **30 s** over HTTP. It carries the label
  `app.kubernetes.io/processed-by-operator: victoriametrics-operator`, so the **VictoriaMetrics
  Operator** reconciles it into scrape config (the `PodMonitor` CRD is shared with prometheus-operator).
- **`GrafanaDashboard`** (`dbaas-operator-dashboard`, `integreatly.org/v1alpha1`) — a ready-made
  dashboard built on the metrics below, reconciled by the Grafana Operator. See
  [Grafana dashboard](#grafana-dashboard).

Set `MONITORING_ENABLED=false` to disable both.

If you run the operator **outside** this chart (e.g. the Kustomize `config/` overlay), wire up
scraping yourself — point a `PodMonitor`/`ServiceMonitor` at the `metrics` port (`8080`, path
`/metrics`), or use Prometheus pod annotations:

```yaml
prometheus.io/scrape: "true"
prometheus.io/port: "8080"
prometheus.io/path: "/metrics"
```

All custom metric names are prefixed with `dbaas_`.

---

## Metric categories

The operator publishes two families of metrics:

1. **Event & operation metrics** — counters and histograms recorded as the operator works
   (reconciles, calls to dbaas-aggregator, async provisioning). **Low cardinality** (no resource
   names in labels). Use these for rates, error ratios, and latency SLOs.

2. **Resource-state metrics** — `kube-state-metrics`-style **gauges** that describe the *current
   state* of each custom resource (CR) the operator manages. They are computed **on every scrape**
   by a custom collector and **include the resource name** in their labels (higher cardinality).
   Use these for "which CR is unhealthy / stuck / deleting" dashboards and alerts.

---

## Event & operation metrics

| Metric | Type | Labels | Description |
|---|---|---|---|
| `dbaas_reconcile_trigger_total` | Counter | `controller`, `trigger` | Reconcile invocations broken down by what triggered them. Complements the framework's reconcile totals with the trigger dimension. |
| `dbaas_aggregator_requests_total` | Counter | `controller`, `operation`, `result` | Calls to dbaas-aggregator by controller, operation, and outcome. The `result` label separates user errors (`spec_rejection`), local operator wiring errors (`configuration_error`), and platform errors (`auth_error`, `server_error`). |
| `dbaas_aggregator_request_duration_seconds` | Histogram | `controller`, `operation` | Latency of HTTP calls to dbaas-aggregator. Buckets: `0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30` s. |
| `dbaas_async_operation_duration_seconds` | Histogram | `result` | End-to-end duration of asynchronous `InternalDatabase` provisioning, from async submit (HTTP 202) to the final poll outcome. Buckets: `1, 5, 10, 30, 60, 300, 600, 1800, 3600, 7200` s. The submit timestamp is held in operator memory, so an operation that was in flight across an operator restart or a leadership change is never observed — `_count` under-reports completions after a restart. |
| `dbaas_secret_resolution_errors_total` | Counter | `namespace`, `reason` | Failures reading the credential `Secret` referenced by an `ExternalDatabase` assigned to this operator instance. A non-zero value means a database may be left without valid credentials — direct service impact. |

> Histograms expose the usual `_bucket`, `_sum`, and `_count` series.
>
> **Namespace label rename:** on the raw `/metrics` endpoint, `dbaas_secret_resolution_errors_total`
> uses the label **`namespace`**. When scraped by Prometheus/VictoriaMetrics — including via the
> chart's `PodMonitor` — that label collides with the scrape target's own `namespace` label and is
> automatically renamed to **`exported_namespace`**. Query scraped data by `exported_namespace` (the
> shipped dashboard does); only the raw endpoint uses `namespace`.

---

## Resource-state metrics

Except for `dbaas_resource_collector_success` (per-kind list health) and `dbaas_resource_unassigned`
(the inverse — CRs *not* assigned here; see their rows below), these gauges are emitted **only for
resources assigned to this operator instance** through `spec.operatorNamespace == CLOUD_NAMESPACE`. The
gauges are derived from the CR object state the operator has
listed/cached — `status` (phase, conditions, observed generation, rotation timestamps), `spec`
(operator assignment and desired balancing-rule targets), and `metadata` (`deletionTimestamp`,
finalizers) — **not** from an independent read-back of dbaas-aggregator.

| Metric | Type | Labels | Description |
|---|---|---|---|
| `dbaas_resource_phase` | Gauge | `kind`, `resource_namespace`, `name`, `phase` | Value `1` on the series matching the resource's current phase (one series per resource). |
| `dbaas_resource_condition` | Gauge | `kind`, `resource_namespace`, `name`, `condition`, `status`, `reason` | Value `1` per current status condition (e.g. `Ready`, `Stalled`) with its status and reason. |
| `dbaas_resource_observed_generation_lag` | Gauge | `kind`, `resource_namespace`, `name` | `metadata.generation − status.observedGeneration`. `> 0` means the latest spec has not been fully reconciled yet. |
| `dbaas_resource_deletion_state` | Gauge | `kind`, `resource_namespace`, `name`, `state` | Emitted only while a resource is being deleted. `state` is `deleting` or `deleting_with_finalizer`. |
| `dbaas_balancing_rule_desired_targets` | Gauge | `kind`, `resource_namespace`, `name`, `target_type` | Desired balancing-rule target count from `spec` (operator intent). `kind` is limited to the three balancing-rule kinds. For `NamespaceBalancingRule` the value counts **rules**, not targets (`target_type="rule"`); `MicroserviceBalancingRule` counts microservices and `PermanentBalancingRule` counts namespaces. |
| `dbaas_balancing_rule_applied_targets` | Gauge | `kind`, `resource_namespace`, `name`, `target_type` | Applied balancing-rule target count recorded in `status` (last operator-applied state). Same `kind` and `target_type` semantics as `dbaas_balancing_rule_desired_targets`. |
| `dbaas_secret_claim_last_rotation_timestamp_seconds` | Gauge | `resource_namespace`, `name` | Unix timestamp of the most recent connection-properties rotation applied by a `DatabaseSecretClaim`. Present only after the first rotation. |
| `dbaas_secret_claim_first_not_found_timestamp_seconds` | Gauge | `resource_namespace`, `name` | Unix timestamp when the current `DatabaseNotFound` streak started for a `DatabaseSecretClaim`. Present only while the claim's database is missing. |
| `dbaas_resource_collector_success` | Gauge | `kind` | `1` if the latest resource-metrics collection for that CR kind succeeded, `0` if the list call failed. Emitted per kind before the operator-assignment filter, so it reports list health even when no CR is assigned to this operator. Watch for `0` — other resource-state series for that kind may be stale or missing. |
| `dbaas_resource_unassigned` | Gauge | `kind`, `resource_namespace`, `name`, `operator_namespace` | Value `1` for each managed CR whose `spec.operatorNamespace` does **not** match this operator's `CLOUD_NAMESPACE`, so no operator here reconciles it. The `operator_namespace` label carries the CR's declared `spec.operatorNamespace`. This is the only place an orphaned CR — usually an `operatorNamespace` typo — is visible, since it otherwise produces no phase, condition, or event. Read through an uncached, paged list with a server-side `spec.operatorNamespace != CLOUD_NAMESPACE` field selector (needs the selectable fields that are GA in Kubernetes 1.32). **Assumes one operator per cluster:** with several operators, a CR assigned to a *different* operator also appears here — scope alerts with `operator_namespace !~ "<known live operators>"` to keep only genuinely orphaned CRs. |
| `dbaas_resource_unassigned_scan_success` | Gauge | `kind` | `1` if the latest orphan scan behind `dbaas_resource_unassigned` succeeded for that kind, `0` if its list failed. Gate any alert on `dbaas_resource_unassigned` with this so a permanently failing scan (RBAC drift, apiserver issue) cannot make an orphaned CR look absent. |

> **Caveat (balancing rules):** desired/applied target counts and applied state are operator
> intent / last-applied snapshots. They do **not** prove the referenced physical database exists
> or that dbaas-aggregator has the rule applied right now.

---

## Grafana dashboard

The chart ships a ready-made Grafana dashboard for these metrics. It is delivered as a
`GrafanaDashboard` custom resource (`dbaas-operator-dashboard`, `integreatly.org/v1alpha1`, dashboard
title pattern `DBaaS Operator - <namespace>`) from `templates/Dashboard.yaml`, gated on
`DBAAS_OPERATOR_ENABLED && MONITORING_ENABLED` (chart defaults: `DBAAS_OPERATOR_ENABLED: false`,
`MONITORING_ENABLED: true` — so it ships once the operator is enabled) and reconciled by the
**Grafana Operator**. Its UID follows the monitoring convention
`<namespace-prefix>-<namespace-hash>-dbaas-operator`; Helm derives it from `NAMESPACE`, keeps up
to 18 characters as a readable prefix, and appends the first six characters of its SHA-256 hash
to preserve uniqueness while staying within Grafana's 40-character UID limit.

The dashboard exposes three variables:

- **`datasource`** (shown as **Cloud**) — Prometheus, VictoriaMetrics, or Promxy data source.
- **`cluster`** — source Kubernetes cluster added by Promxy. `All` uses `.*`, which also matches
  metrics without a `cluster` label when the dashboard uses Prometheus/VictoriaMetrics directly.
- **`namespace`** — DBaaS Operator namespace discovered from
  `dbaas_resource_collector_success`, restricted to the selected cluster. It defaults to the
  namespace of the chart installation that provisioned the dashboard.

Every panel query is scoped by `cluster=~"$cluster"` and `namespace=~"$namespace"`. This allows one
provisioned dashboard to switch between operator instances without changing its UID. The default
range is the last 30 minutes and automatic refresh is disabled. This is intentional because
automatic refresh can hinder issue investigation by changing the displayed data while evidence
is being inspected. Refresh the dashboard manually when updated metric samples are needed.

**To open it:** in Grafana, open `DBaaS Operator - <operator-namespace>`, then select **Cloud**,
**Cluster**, and **DBaaS Operator Namespace**.
The dashboard is organized into rows that mirror the metric groups above. `CR Health Overview`
appears first and is expanded by default; all detailed rows are collapsed until needed.

### Credentials & Secret Resolution

| Panel | What it shows | Based on |
|---|---|---|
| Reconcile Triggers by Source (EDB) | ExternalDatabase reconcile rate by trigger source | `dbaas_reconcile_trigger_total` |
| Secret Resolution Errors | Rate of credential-`Secret` read failures by namespace and reason | `dbaas_secret_resolution_errors_total` |

### Aggregator Interaction Health

| Panel | What it shows | Based on |
|---|---|---|
| Aggregator Call Latency (seconds) | Latency percentiles of dbaas-aggregator calls by operation | `dbaas_aggregator_request_duration_seconds` |
| Aggregator Call Outcomes (calls/s) | Aggregator call rate split by `result` | `dbaas_aggregator_requests_total` |

### Historical Failure Activity

| Panel | What it shows | Based on |
|---|---|---|
| Recent Failed Operations by Owner | Recent aggregator / secret failures attributed to the owning controller | `dbaas_aggregator_requests_total`, `dbaas_secret_resolution_errors_total` |
| Sustained Failure Signals | Longer-window failure trend (persistent vs. transient) | `dbaas_aggregator_requests_total`, `dbaas_secret_resolution_errors_total` |
| Async Provisioning Activity | InternalDatabase async submit / poll activity | `dbaas_async_operation_duration_seconds_count`, `dbaas_reconcile_trigger_total` |
| Async Provisioning End-to-End Duration (seconds) | End-to-end provisioning duration percentiles | `dbaas_async_operation_duration_seconds` |
| Async Completion Rate by Outcome | Async completions/s by `result` (success / failed / terminated) | `dbaas_async_operation_duration_seconds_count` |

### Balancing Rules

| Panel | What it shows | Based on |
|---|---|---|
| Balancing Rule Aggregator Calls | Apply / delete calls for balancing rules by operation and result | `dbaas_aggregator_requests_total` |

### CR Health Overview

| Panel | What it shows | Based on |
|---|---|---|
| CRs by Current Phase | Count of CRs per phase, by kind | `dbaas_resource_phase` |
| Unready CRs by Reason | CRs with non-ready conditions, grouped by reason | `dbaas_resource_condition` |
| CRs Waiting for Observed Generation | CRs whose latest spec is not yet reconciled (generation lag) | `dbaas_resource_observed_generation_lag` |
| CR Metrics Collector Health | Whether the resource-state collector succeeds, per kind | `dbaas_resource_collector_success` |

### DatabaseSecretClaim Health

| Panel | What it shows | Based on |
|---|---|---|
| SecretClaim DatabaseNotFound Age | How long each claim's database has been missing | `dbaas_secret_claim_first_not_found_timestamp_seconds` |
| SecretClaim Time Since Last Rotation | Time since the last connection-properties rotation | `dbaas_secret_claim_last_rotation_timestamp_seconds` |

### Placement and Resource Lifecycle

| Panel | What it shows | Based on |
|---|---|---|
| Balancing Rule Desired vs Applied Targets | Desired (spec) vs. applied (status) target counts. No data means no balancing-rule CRs assigned to this operator currently exist. | `dbaas_balancing_rule_desired_targets`, `dbaas_balancing_rule_applied_targets` |
| Resources being deleted | CRs currently in deletion. No data means no resources are being deleted. | `dbaas_resource_deletion_state` |
| Unassigned CRs (no owning operator) | Managed CRs whose `spec.operatorNamespace` matches no operator here. Non-zero usually means an `operatorNamespace` typo. Assumes one operator per cluster; the `operator_namespace` label lets an alert exclude known live operators. | `dbaas_resource_unassigned` |
| Unassigned orphan-scan health | Whether the orphan scan behind the panel above succeeded, per kind (`1` ok, `0` failed). A `0` means that kind's scan failed, so the panel above may be missing orphans; gate any alert on it with this signal. | `dbaas_resource_unassigned_scan_success` |

---

## Label value reference

**`controller`** (lowercase; event/operation metrics):
`externaldatabase`, `internaldatabase`, `databaseaccesspolicy`, `databasesecretclaim`,
`microservicebalancingrule`, `namespacebalancingrule`, `permanentbalancingrule`, `balancingrule`.

> The three balancing-rule kinds share one reconciler, so **aggregator-call** metrics group them
> under `balancingrule`, while **reconcile-trigger** metrics use the per-kind names.

**`kind`** (CamelCase; resource-state metrics):
`ExternalDatabase`, `InternalDatabase`, `DatabaseAccessPolicy`, `DatabaseSecretClaim`,
`MicroserviceBalancingRule`, `NamespaceBalancingRule`, `PermanentBalancingRule`.

**`trigger`** (what caused a reconcile):

| Value | Meaning |
|---|---|
| `spec_change` | The CR spec / generation changed. It is also the **default/catch-all** bucket: for `ExternalDatabase` it additionally counts the periodic resync (`ResyncInterval`) and `refresh`-annotation force-reconciles, since those are not classified as any of the more specific triggers below. |
| `polling` | An `InternalDatabase` reconcile that polls an in-progress async provisioning operation (`status.trackingId` is set). Emitted only with `controller="internaldatabase"` — the rotation poller's feed shows up as `rotation_trigger` instead |
| `rotation_trigger` | A rotation-trigger annotation was stamped — `databasesecretclaim` only |
| `sibling_secret_claim_change` | A related `DatabaseSecretClaim` changed — `databasesecretclaim` only |
| `safety_net` | Periodic safety-net re-reconcile — `databasesecretclaim` only |

> Not every `controller` × `trigger` combination can occur. All trigger counters are recorded
> **after** the operator-assignment check, so reconciles of CRs assigned to another operator are
> not counted.

**`operation`** (which aggregator call):
`register_external_database`, `apply_config`, `poll_status`, `get_database_by_classifier`,
`create_database`, `apply_microservice_balancing_rule`, `cleanup_microservice_balancing_rule`,
`apply_namespace_balancing_rule`, `delete_namespace_balancing_rule`,
`apply_permanent_balancing_rule`, `delete_permanent_balancing_rule`.

The rotation poller's changed-databases feed (`GET /api/v3/dbaas/databases/changed`) has no `operation`
value — it is not recorded in `dbaas_aggregator_requests_total` at all.

`create_database` is the get-or-create call (`PUT /api/v3/dbaas/{ns}/databases`) the
`InternalDatabase` controller issues to eagerly materialize the concrete
`{scope=tenant, tenantId}` database for a tenant declaration that pins a `tenantId`.

**`result`** (aggregator call outcome — `dbaas_aggregator_requests_total`):

| Value | Meaning | Severity |
|---|---|---|
| `success` | Call succeeded | — |
| `spec_rejection` | Aggregator rejected the request (400/403/409/410/422) | User error — fix the CR |
| `configuration_error` | The operator could not propagate its required request context; the call was rejected locally | Operator wiring error — verify provider registration and request-context initialization |
| `auth_error` | Authentication failed (401) | Platform error |
| `server_error` | Aggregator 5xx | Platform error |
| `network_error` | Connection/timeout, no HTTP response | Platform error |
| `database_not_found` | Database not yet known to the aggregator | Normal `DatabaseSecretClaim` waiting state — **not** a failure |

**`result`** (async provisioning — `dbaas_async_operation_duration_seconds`):
`success`, `failed`, `terminated`.

**`reason`** (secret resolution failures — `dbaas_secret_resolution_errors_total`):
`secret_not_found`, `key_missing`, `key_empty`, `forbidden`, `secret_read_failed`.

**`phase`** (resource phase): `Unknown`, `Processing`, `WaitingForDependency`, `Succeeded`, `BackingOff`,
`InvalidConfiguration`. `WaitingForDependency` is used only by `InternalDatabase`, while it polls dbaas-aggregator for
an in-progress async provisioning operation.

**`target_type`** (balancing rules): `microservice` (MicroserviceBalancingRule),
`rule` (NamespaceBalancingRule), `namespace` (PermanentBalancingRule).

---

## Standard runtime metrics

In addition to the `dbaas_*` metrics above, the same `/metrics` endpoint also exposes the standard
metrics provided by the controller framework and Go runtime, including:

- `controller_runtime_reconcile_total`, `controller_runtime_reconcile_errors_total`,
  `controller_runtime_reconcile_time_seconds_*` — per-controller reconcile counts, errors, latency.
- `workqueue_*` — work-queue depth, adds, retries, and latency per controller.
- `rest_client_requests_total` — Kubernetes API client calls.
- `go_*`, `process_*` — Go runtime and process metrics (memory, goroutines, CPU, file descriptors).

---

## Example PromQL queries

Reconcile rate by trigger:

```promql
sum by (controller, trigger) (rate(dbaas_reconcile_trigger_total[5m]))
```

Aggregator error rate (excluding the normal `database_not_found` waiting state):

```promql
sum by (operation, result) (
  rate(dbaas_aggregator_requests_total{result!~"success|database_not_found"}[5m])
)
```

Aggregator p99 latency per operation:

```promql
histogram_quantile(0.99,
  sum by (le, operation) (rate(dbaas_aggregator_request_duration_seconds_bucket[5m])))
```

p95 end-to-end InternalDatabase provisioning time:

```promql
histogram_quantile(0.95,
  sum by (le) (rate(dbaas_async_operation_duration_seconds_bucket[30m])))
```

Credential resolution failures in the last 15 minutes (direct service impact):

```promql
sum by (exported_namespace, reason) (increase(dbaas_secret_resolution_errors_total[15m]))
```

Resources not in the `Succeeded` phase:

```promql
max by (kind, resource_namespace, name, phase) (
  dbaas_resource_phase{phase!="Succeeded"}
) == 1
```

Resources permanently stalled (need a spec fix):

```promql
dbaas_resource_condition{condition="Stalled", status="True"} == 1
```

Specs not yet reconciled (possible stuck controller):

```promql
dbaas_resource_observed_generation_lag > 0
```

`DatabaseSecretClaim`s whose database has been missing for over 5 minutes:

```promql
time() - dbaas_secret_claim_first_not_found_timestamp_seconds > 300
```

A resource-metrics collection failing for a CR kind:

```promql
dbaas_resource_collector_success == 0
```

---

## Cardinality & high-availability notes

- **Resource-state metrics include the CR name** (`name` label). Cardinality grows with the number
  of CRs assigned to this operator. The framework's event/operation counters and histograms
  deliberately omit resource names to stay low-cardinality.
- **Leader election (HA):** with multiple replicas, only the **active leader** runs the
  reconcilers, so the event/operation counters (`dbaas_reconcile_trigger_total`,
  `dbaas_aggregator_*`, `dbaas_async_*`) are non-zero only on the leader. When scraping all
  replicas, aggregate across pods (e.g. `sum(...)`).
- The **resource-state gauges** are computed at scrape time on whichever replica is scraped, so the
  same series can appear on more than one pod. De-duplicate with `max by (...)` (as in the phase
  example above) or scrape a single endpoint.
- A `dbaas_resource_collector_success == 0` for a kind means that kind's list call failed during the
  last scrape; treat its other resource-state series as possibly stale.
