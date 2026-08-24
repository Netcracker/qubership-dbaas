# DBaaS Operator

## Table of Contents

- [Overview](#overview)
- [High-Level Architecture](#high-level-architecture)
- [Prerequisites and Installation](#prerequisites-and-installation)
- [API Endpoints](#api-endpoints)
  - [Common Response Handling](#common-response-handling)
  - [ExternalDatabase Registration Endpoint](#externaldatabase-registration-endpoint)
  - [DatabaseAccessPolicy Apply Endpoint](#databaseaccesspolicy-apply-endpoint)
  - [InternalDatabase Apply Endpoint](#internaldatabase-apply-endpoint)
  - [InternalDatabase Operation Status Endpoint](#internaldatabase-operation-status-endpoint)
  - [DatabaseSecretClaim Connection Lookup Endpoint](#databasesecretclaim-connection-lookup-endpoint)
  - [MicroserviceBalancingRule Endpoint](#microservicebalancingrule-endpoint)
  - [NamespaceBalancingRule Endpoints](#namespacebalancingrule-endpoints)
  - [PermanentBalancingRule Endpoints](#permanentbalancingrule-endpoints)
  - [Rotation Poller Changed-Databases Feed](#rotation-poller-changed-databases-feed)
- [Authentication: Basic Auth or M2M Token](#authentication-basic-auth-or-m2m-token)
  - [Basic Auth (Default)](#basic-auth-default)
  - [M2M Token](#m2m-token-kubernetes_m2m_enabledtrue)
- [RBAC and Required Permissions](#rbac-and-required-permissions)
  - [Default Installation](#default-installation)
  - [Restricted Environment](#restricted-environment)
    - [Why Cluster-Scoped RBAC Is Needed](#why-cluster-scoped-rbac-is-needed)
    - [RBAC Manifests (Source of Truth)](#rbac-manifests-source-of-truth)
    - [Permission Reference](#permission-reference)
  - [Secret Access (Namespaced)](#secret-access-namespaced)
- [Custom Resources](#custom-resources)
  - [Common Status Model](#common-status-model)
  - [ExternalDatabase](#externaldatabase)
    - [Resource Fields](#externaldatabase-resource-fields)
    - [Classifier → Aggregator Wire Mapping](#classifier--aggregator-wire-mapping)
    - [How It Works](#how-externaldatabase-works)
    - [Status Reference](#externaldatabase-status-reference)
    - [Usage Examples](#externaldatabase-usage-examples)
  - [DatabaseAccessPolicy](#databaseaccesspolicy)
    - [Resource Fields](#databaseaccesspolicy-resource-fields)
    - [How It Works](#how-databaseaccesspolicy-works)
    - [Status Reference](#databaseaccesspolicy-status-reference)
    - [Usage Examples](#databaseaccesspolicy-usage-examples)
  - [InternalDatabase](#internaldatabase)
    - [Resource Fields](#internaldatabase-resource-fields)
    - [How It Works](#how-internaldatabase-works)
    - [Tenant Database Materialization](#tenant-database-materialization)
    - [Status Reference](#internaldatabase-status-reference)
    - [Usage Examples](#internaldatabase-usage-examples)
  - [Balancing Rule CRDs](#balancing-rule-crds) — `MicroserviceBalancingRule`, `NamespaceBalancingRule`,
    `PermanentBalancingRule`
    - [Resource Fields](#balancing-rule-resource-fields)
    - [How Balancing Rules Work](#how-balancing-rules-work)
    - [Lifecycle and Cleanup](#balancing-rule-lifecycle-and-cleanup)
    - [Status Reference](#balancing-rule-status-reference)
    - [Usage Examples](#balancing-rule-usage-examples)
  - [DatabaseSecretClaim](#databasesecretclaim)
    - [Resource Fields](#databasesecretclaim-resource-fields)
    - [How It Works](#how-databasesecretclaim-works)
    - [Rotation Polling](#rotation-polling)
    - [Status Reference](#databasesecretclaim-status-reference)
    - [Usage Examples](#databasesecretclaim-usage-examples)
- [Kubernetes Events](#kubernetes-events)
- [Configuration Parameters](#configuration-parameters)
  - [Ports and Probes](#ports-and-probes)
  - [Startup Flags](#startup-flags)
  - [Reconcile Backoff](#reconcile-backoff)

**Related documents:** [DBaaS Operator Metrics](../monitoring/DBaaS%20Operator%20Metrics.md) ·
[Migrating declarations from Core Operator](migrate-declarations-from-core-operator.md)
---

## Overview

DBaaS Operator is a Kubernetes operator that integrates with dbaas-aggregator. It runs cluster-wide and manages the
following custom resources (CRs):

| Custom Resource | API Group | Scope | Purpose |
|-----------------|-----------|-------|---------|
| `ExternalDatabase` | `dbaas.netcracker.com/v1` | Namespaced | Registers a pre-existing database with dbaas-aggregator |
| `DatabaseAccessPolicy` | `dbaas.netcracker.com/v1` | Namespaced | Declares database role assignments for microservices in a namespace |
| `InternalDatabase` | `dbaas.netcracker.com/v1` | Namespaced | Declares a logical database that dbaas-aggregator should provision and manage |
| `DatabaseSecretClaim` | `dbaas.netcracker.com/v1` | Namespaced | Materializes a managed database's connection credentials into a Kubernetes Secret and keeps it in sync as they rotate |
| `MicroserviceBalancingRule` | `dbaas.netcracker.com/v1` | Namespaced | Declares per-microservice physical database placement rules in a business namespace |
| `NamespaceBalancingRule` | `dbaas.netcracker.com/v1` | Namespaced | Declares per-namespace physical database placement rules in a business namespace |
| `PermanentBalancingRule` | `dbaas.netcracker.com/v1` | Namespaced | Declares permanent placement rules targeting any business namespaces. Its singleton lives in the assigned operator namespace. |

---

## High-Level Architecture

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ dbaas-operator Pod  —  runs cluster-wide; pod in the dbaas-system namespace │
│                                                                             │
│ Controllers (one reconciler per kind):                                      │
│     DatabaseSecretClaim     MicroserviceBalancingRule                       │
│     ExternalDatabase        NamespaceBalancingRule                          │
│     InternalDatabase        DatabaseAccessPolicy                            │
│     PermanentBalancingRule                                                  │
│                                                                             │
│ Rotation poller (leader-only): polls the changed-databases feed and stamps  │
│ the rotation-trigger annotation on matching DatabaseSecretClaim CRs.        │
└─────────────────────────────────────────────────────────────────────────────┘

        │  all traffic is OUTBOUND (Basic Auth by default, or M2M Bearer token)
        ▼
  dbaas-aggregator

Assignment: a managed CR is reconciled only when its own
`spec.operatorNamespace` equals the operator's `CLOUD_NAMESPACE` — the namespace where
dbaas-operator itself runs, not the workload namespace the CR lives in. CRs assigned to
another operator are skipped without status or external side effects.

Managed CRs are routed by their own immutable spec.operatorNamespace.
PermanentBalancingRule additionally lives in that assigned operator namespace;
the other kinds may live in business namespaces. Secrets remain namespaced and
require the corresponding per-namespace Secret RBAC grant.
```

**Key design decisions:**

- The operator runs **cluster-wide** — no static `--watch-namespaces` list.
- Each managed workload CR declares its operator directly in immutable `spec.operatorNamespace`.
- CRs whose `spec.operatorNamespace` differs from the `CLOUD_NAMESPACE` of the operator are silently skipped.
- Credentials for `ExternalDatabase` are read from Kubernetes Secrets at reconcile time. The operator does **not** watch
  Secrets — each `ExternalDatabase` is re-reconciled on a periodic resync (`DBAAS_EXTERNAL_DATABASE_RESYNC_INTERVAL`,
  default `10m`), which re-reads the referenced Secrets and so picks up credential rotations without a spec change.
  (`DatabaseSecretClaim` rotation is driven separately by the leader's changed-databases-feed poller.)
- Secret access is **namespaced**, not cluster-wide: the `ClusterRole` carries no `secrets` permission. Each namespace
  containing Secret-backed CRs grants access through a small `Role` + `RoleBinding` — see
  [Secret access (namespaced)](#secret-access-namespaced).
- Authentication to dbaas-aggregator is dual-mode (`KUBERNETES_M2M_ENABLED`): HTTP Basic Auth by default, or a projected
  service-account token (M2M) when enabled — see [Authentication](#authentication-basic-auth-or-m2m-token).
- Resource-identity fields on all workload CRs are immutable after creation (enforced by CRD CEL rules) — to retarget a
  CR at a different database, microservice, or operator instance, delete and recreate it. See the per-resource sections
  for the exact set of immutable fields.

---

## Prerequisites and Installation

**Prerequisites**

- Kubernetes 1.32 or newer — the CRDs rely on CEL validation rules (`x-kubernetes-validations`), and
  the operator-assignment cache filter uses CRD **selectable fields** on `spec.operatorNamespace`,
  which are GA in 1.32. On an older server the operator's informers fail to sync at startup.
- A reachable dbaas-aggregator. In the default Basic Auth mode the operator must run **in the same namespace
  as dbaas-aggregator**: the chart mounts `dbaas-security-configuration-secret` by name from the pod's own
  namespace, so if the aggregator chart has not created it there, the pod never starts (`FailedMount`).
  A Secret that exists but carries no `dbaas-operator` entry is a different failure — the operator logs a
  fatal error and exits.
- The `monitoring.coreos.com` and `integreatly.org` CRDs when `MONITORING_ENABLED` is left at its default
  `true`; otherwise set it to `false`.

**Installation**

The operator ships as part of the DBaaS Helm chart. Two values are load-bearing and have no usable defaults:

```bash
helm upgrade --install <release> helm-templates/dbaas-operator \
  -f helm-templates/dbaas-operator/resource-profiles/<profile>.yaml \
  --set DBAAS_OPERATOR_ENABLED=true \
  --set NAMESPACE=<operator-namespace>
```

- `DBAAS_OPERATOR_ENABLED=true` — with the default `false` the chart renders only a placeholder ConfigMap.
- `NAMESPACE` — the templates read `.Values.NAMESPACE`, not `.Release.Namespace`; see
  [Restricted Environment](#restricted-environment) for what goes wrong when it is left at `default`.
- A resource profile supplies `CPU_REQUEST`/`CPU_LIMIT`, which are absent from `values.yaml`.

After installing, grant per-namespace Secret access for every namespace the operator will manage — see
[Secret access (namespaced)](#secret-access-namespaced).

**Upgrade**

The CRDs ship as chart templates gated on `DBAAS_OPERATOR_ENABLED`, so `helm upgrade` also upgrades them,
and setting that value back to `false` **deletes the CRDs and every CR they define**. Treat it as a
destructive operation rather than a way to pause the operator; scale the Deployment to zero instead.

---

## API Endpoints

The operator calls the following dbaas-aggregator endpoints:

| Method | URL | Used by | Purpose |
|--------|-----|---------|---------|
| `PUT` | `/api/v3/dbaas/{namespace}/databases/registration/externally_manageable` | `ExternalDatabase` reconciler | Register or update an externally managed database |
| `POST` | `/api/declarations/v1/apply` | `DatabaseAccessPolicy` and `InternalDatabase` reconcilers | Apply a declarative database role policy (`subKind=DbPolicy`) or database declaration (`subKind=DatabaseDeclaration`) |
| `GET` | `/api/declarations/v1/operation/{trackingId}/status` | `InternalDatabase` reconciler | Poll the status of an asynchronous provisioning operation |
| `PUT` | `/api/v3/dbaas/{namespace}/databases` | `InternalDatabase` reconciler (tenant declarations with a pinned `tenantId`) | Get-or-create the concrete `{scope=tenant, tenantId}` database after the declarative apply. Answers `202` while it is still creating the database — see [Tenant database materialization](#tenant-database-materialization) |
| `POST` | `/api/v3/dbaas/{namespace}/databases/get-by-classifier/{type}` | `DatabaseSecretClaim` reconciler (and rotation-poller fan-out) | Fetch the connection properties of a registered database |
| `PUT` | `/api/v3/dbaas/{namespace}/physical_databases/rules/onMicroservices` | `MicroserviceBalancingRule` reconciler | Apply the microservice balancing rule set for a business namespace |
| `PUT` | `/api/v3/dbaas/{namespace}/physical_databases/balancing/rules/{ruleName}` | `NamespaceBalancingRule` reconciler | Create or update one named namespace balancing rule |
| `DELETE` | `/api/v3/dbaas/{namespace}/physical_databases/balancing/rules/{ruleName}` | `NamespaceBalancingRule` reconciler | Remove one named namespace balancing rule on item removal or CR deletion |
| `PUT` | `/api/v3/dbaas/balancing/rules/permanent` | `PermanentBalancingRule` reconciler | Apply permanent balancing rules for target namespaces |
| `DELETE` | `/api/v3/dbaas/balancing/rules/permanent` | `PermanentBalancingRule` reconciler | Remove previously applied permanent balancing rules during update or deletion |
| `GET` | `/api/v3/dbaas/databases/changed` | Rotation poller (leader-only) | Pull databases whose credentials changed (rotation/restore) since a keyset cursor |

All calls are **outbound**; the operator exposes no inbound API endpoint (its only listeners are `/metrics` and the
health probes — see [Ports and Probes](#ports-and-probes)). Most are synchronous; the declarative `apply` endpoint may
return `202 Accepted` with a `trackingId` for asynchronous provisioning (polled via the operation-status endpoint). Each
endpoint is detailed below.

Every request carries a fixed **30 s** timeout and the client performs **no retries of its own** — a failed call is
surfaced to the reconciler, which retries according to the [Reconcile Backoff](#reconcile-backoff) policy. Neither the
timeout nor the retry behavior is configurable.

### Common Response Handling

These outcomes are the same for every **CR-driven** endpoint below and are not repeated in the
per-endpoint tables; each of those tables lists only the responses whose meaning is specific to that
endpoint. They do **not** apply to the
[rotation poller's changed-databases feed](#rotation-poller-changed-databases-feed), which drives no CR
status at all: a `401`, `5xx`, or network error there is logged, the cursor is left where it was, and the
request is repeated on the next tick — no CR moves to `BackingOff` and none reports `Unauthorized`.

| HTTP Code | Situation | Operator outcome |
|-----------|-----------|-----------------|
| `401` | Missing or invalid credentials | `BackingOff` — retried, reason `Unauthorized` |
| `5xx` | Aggregator error | `BackingOff` — retried, reason `AggregatorError` |
| Network error | Aggregator unreachable | `BackingOff` — retried, reason `AggregatorError` |

`400`, `403`, `409`, `410`, and `422` are the permanent set: they yield `InvalidConfiguration` with
`Ready=False`, `Stalled=True`, and reason `AggregatorRejected`. The per-endpoint tables still list them,
because what each code *means* differs by endpoint. **Any other 4xx** — `404` included, unless an endpoint
below gives it a specific meaning — is treated as transient and handled like a `5xx`.

### ExternalDatabase Registration Endpoint

**`PUT /api/v3/dbaas/{namespace}/databases/registration/externally_manageable`**

The `{namespace}` segment is taken from `spec.classifier.namespace` if that field is set; otherwise from
`metadata.namespace`.

The operator always sends `updateConnectionProperties: true`, which means the request creates the database registration
if it does not exist, or updates the connection properties if it does.

**Possible responses and operator behavior:**

| HTTP Code | Situation | Operator outcome |
|-----------|-----------|-----------------|
| `200 OK` / `201 Created` | Successfully registered or updated | `Succeeded` — `Ready=True` |
| `400` | Invalid classifier (missing required fields) | `InvalidConfiguration` — `Ready=False`, `Stalled=True`, reason `AggregatorRejected` |
| `403` | `tenantId` in classifier does not match JWT | `InvalidConfiguration` — `Ready=False`, `Stalled=True`, reason `AggregatorRejected` |
| `409` | Database exists but is not externally managed | `InvalidConfiguration` — `Ready=False`, `Stalled=True`, reason `AggregatorRejected` |
| `410` / `422` | Aggregator-side spec rejection (rare for this endpoint, but handled the same as 400/403/409) | `InvalidConfiguration` — `Ready=False`, `Stalled=True`, reason `AggregatorRejected` |

### DatabaseAccessPolicy Apply Endpoint

**`POST /api/declarations/v1/apply`**

The operator posts a declarative payload with `subKind: DbPolicy`. The `microserviceName` from the CR spec is sent in
the payload `metadata`, not in the spec body.

**Possible responses and operator behavior:**

| HTTP Code | Situation | Operator outcome |
|-----------|-----------|-----------------|
| `200 OK` | Policy applied successfully | `Succeeded` — `Ready=True`, reason `PolicyApplied` |
| `400` / `403` / `409` / `410` / `422` | Invalid or permanently rejected policy spec | `InvalidConfiguration` — `Ready=False`, `Stalled=True`, reason `AggregatorRejected` |

### InternalDatabase Apply Endpoint

**`POST /api/declarations/v1/apply`**

The same declarative endpoint as above, but the `InternalDatabase` reconciler posts `kind: DBaaS`, `subKind:
DatabaseDeclaration`. The CR `spec` (classifier, `type`, `settings`, `versioningConfig`, `initialInstantiation`) is
forwarded as the payload `spec`; `microserviceName` is carried in the payload `metadata`. Unlike `DbPolicy`,
provisioning a database may be **synchronous or asynchronous**: a `202 Accepted` carries a `trackingId` that the
operator then polls (see the next endpoint).

**Possible responses and operator behavior:**

| HTTP Code | Situation | Operator outcome |
|-----------|-----------|-----------------|
| `200 OK` | Provisioned synchronously | `Succeeded` — `Ready=True`, reason `DatabaseProvisioned` |
| `202 Accepted` | Async operation accepted; response carries `trackingId` | `WaitingForDependency` — reason `ProvisioningStarted`; the controller persists `status.trackingId` and polls the operation-status endpoint |
| `400` / `403` / `409` / `410` / `422` | Invalid or permanently rejected declaration | `InvalidConfiguration` — `Ready=False`, `Stalled=True`, reason `AggregatorRejected` |

See [InternalDatabase Status Reference](#internaldatabase-status-reference) for the full phase model.

### InternalDatabase Operation Status Endpoint

**`GET /api/declarations/v1/operation/{trackingId}/status`**

After a `202 Accepted` from the apply endpoint, the controller polls this endpoint with the returned `{trackingId}`
(persisted in `status.trackingId`) every `pollRequeueAfter` until the operation reaches a terminal state. The response
body carries a `status` (`TaskState`) field — `NOT_STARTED` / `IN_PROGRESS` / `COMPLETED` / `FAILED` / `TERMINATED` — so
outcomes are driven by that value as well as by the HTTP code.

**Possible responses and operator behavior:**

| Response | Situation | Operator outcome |
|----------|-----------|-----------------|
| `status=COMPLETED` | Provisioning finished | `Succeeded` — `Ready=True`, reason `DatabaseProvisioned`; `trackingId` cleared |
| `status=IN_PROGRESS` / `NOT_STARTED` | Still running | `WaitingForDependency` — requeued after the poll interval, reason `ProvisioningStarted` |
| `status=FAILED` | Provisioning failed | `InvalidConfiguration` — `Ready=False`, `Stalled=True`, reason `AggregatorRejected`; `trackingId` cleared |
| `status=TERMINATED` | Canceled mid-flight (aggregator restart or admin terminate) | `BackingOff` — `trackingId` cleared and the operation is **resubmitted** on the next reconcile, reason `OperationTerminated` |
| HTTP `401` | Missing or invalid credentials | `BackingOff` — `trackingId` kept, retried, reason `Unauthorized` |
| HTTP `404` | `trackingId` expired or unknown | `BackingOff` — `trackingId` cleared, operation **resubmitted** next reconcile, reason `AggregatorError` |
| HTTP `5xx` / Network error | Aggregator error / unreachable | `BackingOff` — `trackingId` kept, retried, reason `AggregatorError` |

### DatabaseSecretClaim Connection Lookup Endpoint

**`POST /api/v3/dbaas/{namespace}/databases/get-by-classifier/{type}`**

The `{namespace}` segment is taken from `spec.classifier.namespace` (defaulting to `metadata.namespace`); `{type}` is
`spec.type`. The reconciler posts the CR `classifier`, the `app.kubernetes.io/name` label as `originService`, and
`spec.userRole`. The aggregator resolves the **effective role** and returns the database's `connectionProperties`, which
the operator materializes into the target Secret. The same call is re-issued when the rotation poller signals a change.

**Possible responses and operator behavior:**

| HTTP Code | Situation | Operator outcome |
|-----------|-----------|-----------------|
| `200 OK` (with `connectionProperties`) | Credentials retrieved | `Succeeded` — `Ready=True`; reason `SecretCreated` (first write) / `SecretRotated` (content changed) / `SecretUpToDate` (no change) |
| `200 OK` (empty `connectionProperties` for the role) | Role not yet provisioned | `BackingOff` — retried, reason `EmptyConnectionProperties` |
| `404` + `CORE-DBAAS-4006` | Database not yet registered | `BackingOff` — retried, reason `DatabaseNotFound` (switches to `DatabaseNotFoundTimeout` after a prolonged wait) |
| `400` / `403` / `409` / `410` / `422` | Invalid classifier or permanent rejection | `InvalidConfiguration` — `Ready=False`, `Stalled=True`, reason `AggregatorRejected` |
| `5xx` / `404` (no TMF body) / Network error | Aggregator error / unreachable | `BackingOff` — retried, reason `AggregatorError` |

A pre-flight failure where the target Secret is owned by a different resource yields reason `SecretConflict` without
contacting the aggregator. See [DatabaseSecretClaim → How It Works](#how-databasesecretclaim-works) for the
content-aware Secret update and [Rotation Polling](#rotation-polling) for how rotations trigger a re-fetch.

### MicroserviceBalancingRule Endpoint

**`PUT /api/v3/dbaas/{namespace}/physical_databases/rules/onMicroservices`**

The `{namespace}` segment is the CR's `metadata.namespace`. The reconciler sends the full desired rule set (`type`,
`rules[].label`, `microservices`). On item removal it first applies an empty rule set for the dropped `type +
microservices` entries (cleanup), then re-applies the desired list.

**Possible responses and operator behavior:**

| HTTP Code | Situation | Operator outcome |
|-----------|-----------|-----------------|
| `200 OK` / `201 Created` | Rules applied | `Succeeded` — `Ready=True`, reason `BalancingRuleApplied` |
| `400` / `403` / `409` / `410` / `422` | Invalid or rejected rule set | `InvalidConfiguration` — `Ready=False`, `Stalled=True`, reason `AggregatorRejected` |

### NamespaceBalancingRule Endpoints

**`PUT` / `DELETE /api/v3/dbaas/{namespace}/physical_databases/balancing/rules/{ruleName}`**

The `{namespace}` segment is the CR's `metadata.namespace`. Each entry in `spec.rules` is applied by name with a `PUT`.
Entries removed from the spec — and all entries on CR deletion — are removed with the corresponding `DELETE`.
`status.appliedRules` records what the operator last applied so it knows what to delete.

**Possible responses and operator behavior:**

| HTTP Code | Situation | Operator outcome |
|-----------|-----------|-----------------|
| `200 OK` / `201 Created` (`PUT`) | Rule applied | `Succeeded` — `Ready=True`, reason `BalancingRuleApplied` |
| `200` / `204` / `404` (`DELETE`) | Rule removed (or already absent) | Cleanup succeeds; reconcile continues |
| `400` / `403` / `409` / `410` / `422` | Invalid or rejected rule | `InvalidConfiguration` — `Ready=False`, `Stalled=True`, reason `AggregatorRejected` |

### PermanentBalancingRule Endpoints

**`PUT` / `DELETE /api/v3/dbaas/balancing/rules/permanent`**

Cluster-scoped aggregator endpoint (no `{namespace}` segment). The reconciler sends the full desired list
(`dbType`, `physicalDatabaseId`, `namespaces`) with a `PUT`. Removed entries — and all entries on CR
deletion — are removed with the `DELETE` variant. The CR itself is assigned through
`spec.operatorNamespace`; its target namespaces need no separate operator assignment.

**Possible responses and operator behavior:**

| HTTP Code | Situation | Operator outcome |
|-----------|-----------|-----------------|
| `200 OK` / `201 Created` (`PUT`) | Rules applied | `Succeeded` — `Ready=True`, reason `BalancingRuleApplied` |
| `200` / `204` (`DELETE`) | Rules removed | Cleanup succeeds; reconcile continues |
| `400` / `403` / `409` / `410` / `422` | Invalid or rejected rule set | `InvalidConfiguration` — `Ready=False`, `Stalled=True`, reason `AggregatorRejected` |

### Rotation Poller Changed-Databases Feed

**`GET /api/v3/dbaas/databases/changed?sinceTs={iso}&sinceId={uuid}&limit={n}`**

A leader-only background loop (the **rotation poller**) pulls this **cluster-scoped** feed every
`DBAAS_ROTATION_POLL_INTERVAL` (default `30s`); it requires the `CLUSTER_OPERATOR` role. The first (since-less) call
returns only the feed's high-water mark to seed the keyset cursor `(lastRotatedAt, id)`; subsequent calls return
databases whose credentials changed strictly after the cursor. For each returned database the poller stamps the
`dbaas.netcracker.com/rotation-trigger` annotation on the matching `DatabaseSecretClaim` CR(s), which then re-fetch via
the connection-lookup endpoint. This feed drives **no CR phase directly** — it is infrastructure, so failures are logged
and retried on the next tick.

**Possible responses and poller behavior:**

| HTTP Code | Situation | Poller outcome |
|-----------|-----------|-----------------|
| `200 OK` | Changes (or the high-water mark) returned | Affected `DatabaseSecretClaim` CRs are woken; the cursor advances by the last returned item |
| `401` / `5xx` / Network error | Auth/aggregator error | Logged; the cursor is **not** advanced and the poll is retried on the next tick |

See [DatabaseSecretClaim → Rotation Polling](#rotation-polling) for the full cursor and role-resolution discussion.

---

## Authentication: Basic Auth or M2M Token

The operator authenticates to dbaas-aggregator in one of two mutually exclusive modes, selected by the
`KUBERNETES_M2M_ENABLED` environment variable. **The operator's setting must match the aggregator's
`KUBERNETES_M2M_ENABLED`** — when the aggregator has M2M disabled it rejects Bearer tokens outright (`401`), so an
operator configured for M2M against a non-M2M aggregator fails every call.

| `KUBERNETES_M2M_ENABLED` | Mode | Credential sent |
|--------------------------|------|-----------------|
| `false` (**default**) | HTTP **Basic Auth** | `Authorization: Basic <base64(username:password)>` |
| `true` | **M2M** Bearer token | `Authorization: Bearer <projected SA token, audience=dbaas>` |

### Basic Auth (Default)

- The aggregator's Helm chart auto-generates the `dbaas-operator` user password at deploy time and stores it — together
  with all other aggregator users — in `dbaas-security-configuration-secret` (key `users.json`). No external credential
  input is required.
- The operator chart mounts `dbaas-security-configuration-secret` at `/etc/dbaas/security`. At startup the operator
  parses `users.json` and extracts the entry for the hardcoded username `dbaas-operator`; if the entry is absent it logs
  a fatal error and exits.
- A filesystem watcher reloads `users.json` whenever the mounted Secret changes, so a password rotation is applied
  **without a pod restart** (the value is swapped atomically; there is no other caching).
- **Aggregator side:** the `dbaas-operator` user is included in `users.json` with the `DB_CLIENT` and `CLUSTER_OPERATOR`
  roles automatically — no manual credential configuration is needed on either side.

### M2M Token (`KUBERNETES_M2M_ENABLED=true`)

- A projected service-account token (`audience=dbaas`, `expirationSeconds=600`) is mounted at
  `/var/run/secrets/tokens/dbaas/token`.
- Kubernetes rotates the token automatically before it expires; the operator reads it from disk on **every** outbound
  request (no client-side caching), so rotation is fully transparent with no pod restart.
- **Aggregator side:** the aggregator must accept tokens with `audience=dbaas` and validate them against the Kubernetes
  token review API, and the operator's service account must map to the `CLUSTER_OPERATOR` (and `DB_CLIENT`) roles in the
  aggregator's service-account-roles configuration.

Volume configuration (M2M mode, from the Deployment):

```yaml
volumes:
  - name: projected-tokens
    projected:
      defaultMode: 292
      sources:
        - serviceAccountToken:
            path: dbaas/token
            audience: dbaas
            expirationSeconds: 600
containers:
  - volumeMounts:
      - name: projected-tokens
        mountPath: /var/run/secrets/tokens
        readOnly: true
```

> **No inbound API endpoint** — the operator exposes no authenticated HTTP endpoint; the only listeners are `/metrics`
> and the health probes (see [Ports and Probes](#ports-and-probes)). All dbaas-aggregator traffic is **outbound** (see
> [API Endpoints](#api-endpoints)). Credential rotations are picked up by **polling** the aggregator, not pushed to the
> operator — see [Rotation Polling](#rotation-polling).

---

## RBAC and Required Permissions

The operator needs a `ServiceAccount`, a `ClusterRole`, a `ClusterRoleBinding`, a namespace-scoped `Role`, and a
`RoleBinding` to function correctly. By default the Helm chart creates all of these automatically. In environments where
cluster-scoped resources cannot be created, set `restrictedEnvironment: true` — the chart will then create only the
`ServiceAccount`, the namespace-scoped `Role`, and the `RoleBinding`, skipping the `ClusterRole`/`ClusterRoleBinding`,
which must be applied manually using the manifests below.

### Default Installation

When `restrictedEnvironment: false` (the default), the chart creates:

| Resource | Name | Scope | Purpose |
|----------|------|-------|---------|
| `ServiceAccount` | `dbaas-operator` | Namespaced (operator namespace) | Pod identity |
| `ClusterRole` | `dbaas-operator` | Cluster-wide | Access to dbaas CRs across all namespaces (**no `secrets`** — Secret access is namespaced, see below) |
| `ClusterRoleBinding` | `dbaas-operator-<NAMESPACE>` (e.g. `dbaas-operator-dbaas-system`, truncated to 63 characters) | Cluster-wide | Binds `ClusterRole` to the `ServiceAccount` |
| `Role` | `dbaas-operator` | Namespaced (operator namespace) | Leader-election leases and event recording |
| `RoleBinding` | `dbaas-operator` | Namespaced (operator namespace) | Binds `Role` to the `ServiceAccount` |

Only permissions that genuinely require cluster-wide access are in the `ClusterRole`. Leader election leases and
Kubernetes Events are always written to the operator's own namespace, so they use a namespace-scoped `Role`.

### Restricted Environment

When `restrictedEnvironment: true`, only the `ServiceAccount`, `Role`, and `RoleBinding` are created by the chart. You
must create the `ClusterRole` and `ClusterRoleBinding` manually before starting the operator.

#### Why Cluster-Scoped RBAC Is Needed

The operator runs cluster-wide and watches dbaas CRs in all namespaces. Namespace-scoped `Role`/`RoleBinding` cannot
grant access to resources across multiple namespaces, so a `ClusterRole` is required for the dbaas CRs. **Secrets are
the exception**: the operator holds no cluster-wide `secrets` permission — Secret access is granted per namespace (see
[Secret access (namespaced)](#secret-access-namespaced)).

Two things are scoped to the operator's own namespace and therefore use a namespace-scoped `Role`:
leader-election leases and Kubernetes Events. All managed CR kinds, including
`PermanentBalancingRule`, are watched cluster-wide and filtered by `spec.operatorNamespace`.

#### RBAC Manifests (Source of Truth)

The chart renders the RBAC objects from the templates below. They are the single
source of truth and are intentionally **not** reproduced inline here (so this doc
never drifts from the code):

- [`ClusterRole.yaml`](../../helm-templates/dbaas-operator/templates/ClusterRole.yaml) — cluster-wide access to dbaas
  CRs (no `secrets`)
- [`ClusterRoleBinding.yaml`](../../helm-templates/dbaas-operator/templates/ClusterRoleBinding.yaml) — binds the
  `ClusterRole` to the `ServiceAccount`
- [`Role.yaml`](../../helm-templates/dbaas-operator/templates/Role.yaml) — operator-namespace-only access for
  leader-election leases and Events
- [`RoleBinding.yaml`](../../helm-templates/dbaas-operator/templates/RoleBinding.yaml) — binds the `Role` to the
  `ServiceAccount`

The Helm RBAC templates are **hand-maintained**: `make manifests` regenerates only
[`config/rbac/role.yaml`](../../config/rbac/role.yaml) from the controllers' `+kubebuilder:rbac`
markers, and `make sync-helm-crds` regenerates only the CRD templates. Keep the chart
templates in step with `config/rbac/role.yaml` by hand when the markers change.

The cluster-scoped templates are gated on `not restrictedEnvironment`, so with
`restrictedEnvironment: true` the chart skips the `ClusterRole`/`ClusterRoleBinding`
(the `ServiceAccount`/`Role`/`RoleBinding` are still created). Render the two
cluster-scoped objects from the chart and apply them manually before starting the
operator:

```bash
helm template <release> helm-templates/dbaas-operator \
  -f helm-templates/dbaas-operator/resource-profiles/dev.yaml \
  --set NAMESPACE=<operator-namespace> \
  --set DBAAS_OPERATOR_ENABLED=true --set restrictedEnvironment=false \
  -s templates/ClusterRole.yaml -s templates/ClusterRoleBinding.yaml \
  | kubectl apply -f -
```

Both extra arguments are load-bearing:

- `--set NAMESPACE=<operator-namespace>` is what fills in the binding subject. The templates read
  `.Values.NAMESPACE` (default `default`), **not** `.Release.Namespace`, so passing only `--namespace`
  binds the `ClusterRole` to a `ServiceAccount` in `default` and the operator silently reconciles nothing.
- `-f .../resource-profiles/<profile>.yaml` supplies `CPU_REQUEST`/`CPU_LIMIT`, which live in the resource
  profiles rather than `values.yaml`. Helm renders every template before `-s` filters the output, so without
  a profile the whole command aborts in `HorizontalPodAutoscaler.yaml` with `decimal division by 0`.

#### Permission Reference

The tables below explain *why* each permission is needed; the authoritative rule
set is the linked templates above (and the `+kubebuilder:rbac` markers they are
generated from).

**ClusterRole** (cluster-wide access):

| API group | Resource | Verbs | Why it is needed |
|-----------|----------|-------|-----------------|
| `dbaas.netcracker.com` | `databaseaccesspolicies` | `get`, `list`, `watch` | Watch and read CRs across all namespaces; status is written via `/status` subresource |
| `dbaas.netcracker.com` | `databaseaccesspolicies/status` | `get`, `update`, `patch` | Write reconcile outcome to `status.phase` and `status.conditions` |
| `dbaas.netcracker.com` | `internaldatabases` | `get`, `list`, `watch` | Watch and read CRs across all namespaces; status is written via `/status` subresource |
| `dbaas.netcracker.com` | `internaldatabases/status` | `get`, `update`, `patch` | Write reconcile outcome to `status.phase`, `status.conditions`, and `status.trackingId` |
| `dbaas.netcracker.com` | `externaldatabases` | `get`, `list`, `watch` | Watch and read CRs across all namespaces; status is written via `/status` subresource |
| `dbaas.netcracker.com` | `externaldatabases/status` | `get`, `update`, `patch` | Write reconcile outcome to `status.phase` and `status.conditions` |
| `dbaas.netcracker.com` | `databasesecretclaims` | `get`, `list`, `watch`, `patch` | Watch and read CRs; `patch` is required for the rotation poller to stamp the `dbaas.netcracker.com/rotation-trigger` annotation on matched CRs |
| `dbaas.netcracker.com` | `databasesecretclaims/status` | `get`, `update`, `patch` | Write reconcile outcome to `status.phase`, `status.conditions`, `status.lastRotatedAt`, and `status.firstNotFoundAt` |
| `dbaas.netcracker.com` | `databasesecretclaims/finalizers` | `update` | `SetControllerReference` sets `blockOwnerDeletion: true` on the owner reference of managed Secrets; with the `OwnerReferencesPermissionEnforcement` admission plugin enabled, writing such a reference requires `update` on the owner's `finalizers` subresource |
| `dbaas.netcracker.com` | `microservicebalancingrules` | `get`, `list`, `watch`, `patch` | Watch and read singleton microservice balancing rule CRs; `patch` is required to add/remove the cleanup finalizer |
| `dbaas.netcracker.com` | `microservicebalancingrules/finalizers` | `update` | Kubernetes additionally checks this permission when `metadata.finalizers` changes during a patch |
| `dbaas.netcracker.com` | `microservicebalancingrules/status` | `get`, `update`, `patch` | Write reconcile outcome and last-applied rule data |
| `dbaas.netcracker.com` | `namespacebalancingrules` | `get`, `list`, `watch`, `patch` | Watch and read singleton namespace balancing rule CRs; `patch` is required to add/remove the cleanup finalizer |
| `dbaas.netcracker.com` | `namespacebalancingrules/finalizers` | `update` | Kubernetes additionally checks this permission when `metadata.finalizers` changes during a patch |
| `dbaas.netcracker.com` | `namespacebalancingrules/status` | `get`, `update`, `patch` | Write reconcile outcome and last-applied rule data |
| `dbaas.netcracker.com` | `permanentbalancingrules` | `get`, `list`, `watch`, `patch` | Watch and read singleton permanent balancing rule CRs; `patch` is required to add/remove the cleanup finalizer |
| `dbaas.netcracker.com` | `permanentbalancingrules/finalizers` | `update` | Kubernetes additionally checks this permission when `metadata.finalizers` changes during a patch |
| `dbaas.netcracker.com` | `permanentbalancingrules/status` | `get`, `update`, `patch` | Write reconcile outcome and last-applied rule data |

> **Secrets are not in the `ClusterRole`** — Secret access is namespaced (see [Secret access
> (namespaced)](#secret-access-namespaced) below).

**Role** (operator namespace only):

| API group | Resource | Verbs | Why it is needed |
|-----------|----------|-------|-----------------|
| `coordination.k8s.io` | `leases` | `get`, `list`, `watch`, `create`, `update`, `patch`, `delete` | Leader election lock (required when `LEADER_ELECT=true`) |
| `""` (core) | `events` | `create`, `patch` | Emit Kubernetes Events on reconcile outcomes (required when `K8S_EVENTS_ENABLED=true`) |

> **Note:** The chart omits the `events` rule automatically when `K8S_EVENTS_ENABLED=false` (the default); the advice to
> drop it applies to hand-written `Role` manifests. The `leases` rule is **not** gated — it is always rendered. With
> `LEADER_ELECT=false` you may omit it from a hand-written `Role`, but that is only safe when running a single replica.

### Secret Access (Namespaced)

The operator holds **no cluster-wide `secrets` permission** — its `ClusterRole` grants access only to dbaas CRs. This
keeps Secret access least-privilege: the operator reads its own aggregator credentials from a mounted volume
(`/etc/dbaas/security`), not the Kubernetes API, so it needs no Secret RBAC merely to start.

Secret access is granted **per namespace** by a `Role` + `RoleBinding`:

| API group | Resource | Verbs | Why it is needed |
|-----------|----------|-------|-----------------|
| `""` (core) | `secrets` | `get`, `create`, `update`, `patch` | `get`: read the credential Secret referenced by an `ExternalDatabase`, and read back the Secret managed by a `DatabaseSecretClaim`. `create`/`update`/`patch`: materialize and keep the `DatabaseSecretClaim` Secret in sync. **No `list`/`watch`** — the operator runs no Secret informer. **No `delete`** — owned Secrets are garbage-collected via `ownerReferences`. |

- The `Role` and `RoleBinding` live in the **business** namespace; the `RoleBinding` subject is the operator's
  `ServiceAccount` (`dbaas-operator`) in the **operator** namespace.
- Without them, `ExternalDatabase` (reads a referenced credential Secret) and `DatabaseSecretClaim` (creates the owned
  Secret) fail with `forbidden`.
- The operator's own namespace needs this bundle **only if it also hosts Secret-backed workload CRs**;
  leader-election leases, Events, and balancing-rule CRs do not require Secret access.

A ready-to-apply `Role` + `RoleBinding` bundle for one namespace is in
[`config/samples/namespaced-secret-rbac.yaml`](../../config/samples/namespaced-secret-rbac.yaml). Apply it for each
namespace the operator manages.

---

## Custom Resources

All the CRs are namespaced, expose a `status` subresource, and belong to the `dbaas` category, so
`kubectl get dbaas -n <namespace>` lists every DBaaS CR in a namespace at once. Each kind also has a short
name:

| Kind | Short name | Print columns |
|------|-----------|---------------|
| `ExternalDatabase` | `dbedb` | `PHASE`, `READY`, `TYPE`, `DBNAME`, `AGE` |
| `DatabaseAccessPolicy` | `dbdap` | `PHASE`, `READY`, `MICROSERVICENAME`, `AGE` |
| `InternalDatabase` | `dbidb` | `PHASE`, `READY`, `MICROSERVICENAME`, `TYPE`, `AGE` |
| `DatabaseSecretClaim` | `dbdsc` | `PHASE`, `READY`, `TYPE`, `AGE` |
| `MicroserviceBalancingRule` | `dbmbr` | `PHASE`, `READY`, `AGE` |
| `NamespaceBalancingRule` | `dbnbr` | `PHASE`, `READY`, `AGE` |
| `PermanentBalancingRule` | `dbpbr` | `PHASE`, `READY`, `AGE` |

All managed CR kinds require immutable `spec.operatorNamespace`. The operator reconciles
a CR only when that value equals its `CLOUD_NAMESPACE`; otherwise it leaves the resource untouched.
Change the assignment by deleting and recreating the CR.

### Installation and the retired NamespaceBinding model

This operator ships no automated upgrade from the retired `NamespaceBinding` model. It assumes
either a greenfield install or GitOps-managed manifests: the assignment is carried declaratively by
each CR's `spec.operatorNamespace`, so a GitOps tool applies the field and prunes the old
`NamespaceBinding` objects during a normal sync. A cluster that still runs live `NamespaceBinding`
resources needs its own migration before adopting this chart, because `spec.operatorNamespace` is
required and immutable once set.

### Common Status Model

Every kind reports state the same way. This section defines the shared parts; each kind's own **Status
Reference** below lists only what is specific to it.

**`status.phase`** — human-readable summary for `kubectl get`. Read `status.conditions` for automation:
phase summarizes them and carries no information they do not already have.

| Phase | Meaning |
|-------|---------|
| *(empty)* | CR just created; the controller has not written status yet — `kubectl get` shows a blank `PHASE`. The `dbaas_resource_phase` metric reports it as `phase="Unknown"`. |
| `Processing` | Controller is actively reconciling (transient) |
| `Succeeded` | The desired state was reached — see each kind for what that means |
| `BackingOff` | Transient error — retried, in most cases with [exponential backoff](#reconcile-backoff) |
| `InvalidConfiguration` | Permanent error — will not retry until the spec is changed |

`InternalDatabase` adds `WaitingForDependency` while an asynchronous provisioning operation is in flight.

**`status.conditions`** — canonical machine-readable state. Use these for automation and alerting.

| Condition | `True` | `False` |
|-----------|--------|---------|
| `Ready` | The current generation was processed successfully | Processing failed — check `Reason` and `Message` |
| `Stalled` | Permanent error: the spec must be corrected, and the controller will not retry on its own | Not permanently stalled. This is the normal value on success as well as during a transient failure, so it does **not** by itself mean anything is being retried — read it together with `Ready` |

`LastTransitionTime` is preserved when `Status` (`True`/`False`) has not changed — a change in `Reason` or
`Message` at the same `Status` does not reset it.

**Shared reason vocabulary** — every kind can report these; kind-specific reasons are listed with the kind.

| Reason | Applied to | Meaning |
|--------|-----------|---------|
| `Succeeded` | `Stalled=False` (on success) | Not stalled; the last operation succeeded |
| `InvalidSpec` | `Ready=False`, `Stalled=True` | Controller-side validation failed before calling the aggregator |
| `Unauthorized` | `Ready=False`, `Stalled=False` | Aggregator returned `401` |
| `AggregatorRejected` | `Ready=False`, `Stalled=True` | Aggregator returned `400`, `403`, `409`, `410`, or `422` — permanent spec issue |
| `AggregatorError` | `Ready=False`, `Stalled=False` | Aggregator returned `5xx`, or the call failed at the network level |

**Diagnostic rules:**

- **`Stalled=True`** — fix the spec. The controller will not retry on its own.
- **`Ready=False` + `Stalled=False`** — transient; the controller is retrying. See
  [Reconcile Backoff](#reconcile-backoff) for which paths back off and which re-poll at a fixed interval.
- **`Ready=True` + `Stalled=False`** — the steady state, not a retry. Nothing is scheduled beyond the
  kind's own resync or watch events: every kind re-reconciles on a spec change, an `ExternalDatabase`
  on its periodic resync, and a `DatabaseSecretClaim` on a rotation trigger or its hourly safety net.
- **`status.lastRequestId`** — correlate operator logs with dbaas-aggregator logs. `DatabaseSecretClaim` is
  the exception: it never writes this field — see its
  [Status Reference](#databasesecretclaim-status-reference).

**`status.observedGeneration`** — the generation the controller last finished processing. If
`metadata.generation > status.observedGeneration`, the current spec has not been fully processed yet. It is
stamped when a reconcile reaches a terminal outcome — success or a permanent (`Stalled=True`) failure — and
is left behind on a transient failure.

---

### ExternalDatabase

`ExternalDatabase` registers a pre-existing database instance with dbaas-aggregator. The database must already exist in
the DBMS — the operator does not provision it.

Short name: `dbedb`

`kubectl get dbedb` columns: `PHASE`, `READY`, `TYPE`, `DBNAME`, `AGE`

#### ExternalDatabase Resource Fields

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: ExternalDatabase
metadata:
  name: my-postgres-external
  namespace: my-namespace
spec:
  operatorNamespace: dbaas-system
  classifier:
    microserviceName: my-service   # required, minLength: 1
    scope: service                 # required, minLength: 1; "service" or "tenant"
    namespace: my-namespace        # optional; if set, must equal metadata.namespace
    # tenantId: my-tenant          # required when scope=tenant
    # customKeys:                  # optional, adapter-specific identifiers (nested under "customKeys" on the wire)
    #   logicalDBName: mydb        # string
    #   shardCount: 5              # number — preserved as JSON number on the wire
    # extraKeys:                   # optional, arbitrary identity fields flattened to the classifier top level
    #   region:                    # nested object — preserved as JSON object
    #     name: us-east
    #     az: a
  type: postgresql
  dbName: my_application_db
  connectionProperties:
    - role: admin
      credentialsSecretRef:
        name: pg-credentials
        keys:
          - key: db-user
            name: username
          - key: db-pass
            name: password
      extraProperties:
        sslMode: "require"
```

**`spec.classifier`** — uniquely identifies the database in dbaas-aggregator. A typed struct (CRD-validated).

| Field | Required | Notes |
|-------|:--------:|-------|
| `microserviceName` | Yes | Name of the owning microservice. `minLength: 1`. CRD admission rejects missing/empty values. |
| `scope` | Yes | `service` or `tenant`. `minLength: 1`. |
| `tenantId` | When `scope=tenant` | Tenant identifier for multi-tenant deployments. |
| `namespace` | No | If set, must equal `metadata.namespace` (controller-side check); if absent, `metadata.namespace` is used in the aggregator URL. |
| `customKeys` | No | Adapter-specific identifiers (e.g. `logicalDBName`), emitted as a **nested** `customKeys` object on the wire (`classifier.customKeys.*`). Values can be any valid JSON type (string, number, boolean, nested object, array); not validated by the aggregator. See the mapping rules below. |
| `extraKeys` | No | Arbitrary additional identity fields **flattened onto the classifier top level** (legacy open-classifier compatibility — see the mapping rules below). The reserved keys `microserviceName`, `scope`, `namespace`, `tenantId`, `customKeys` are not allowed — the controller rejects the spec with `InvalidConfiguration`. |

##### Classifier → Aggregator Wire Mapping

The aggregator declares the classifier as `SortedMap<String, Object>` and stores
it as JSONB, so the wire format supports any JSON value — including nested
objects and arrays. The controller's `ClassifierFlatMap` builds the wire payload
from `spec.classifier` like this:

1. **Structured fields first.** `microserviceName` and `scope` are always
   emitted at the top level of the classifier map. `namespace` and `tenantId`
   are emitted at the top level when set (empty strings are skipped).
2. **`customKeys` stay nested.** They are emitted as a single nested `customKeys`
   object — the canonical dbaas-client shape (`classifier.customKeys.*`). They
   are **not** flattened to the top level.
3. **`extraKeys` are flattened to the top level.** Every entry in `extraKeys`
   becomes a top-level key alongside the identity scalars, reproducing the legacy
   open-classifier model (dbaas-client `withProperty` / `withProperties`).
4. **Native JSON types are preserved** for both `customKeys` and `extraKeys`: a
   string stays a JSON string, a number stays a number, a boolean stays a
   boolean, a nested object/array is sent as-is. The controller does not
   stringify non-string values.
5. **Reserved keys are rejected.** If `extraKeys` contains `microserviceName`,
   `scope`, `namespace`, `tenantId` or `customKeys`, the controller rejects the
   CR with `InvalidConfiguration` (a CEL rule cannot guard this map because its
   values are unstructured JSON, so the check lives in the controller).
   `ClassifierFlatMap` additionally skips any reserved key defensively, so the
   typed fields always win and a stray reserved key can never corrupt identity.

Example. For the spec snippet:

```yaml
spec:
  classifier:
    microserviceName: my-service
    scope: service
    namespace: my-namespace
    customKeys:
      logicalDBName: configs
      shardCount: 5
    extraKeys:
      region:
        name: us-east
        az: a
```

the controller sends the following `classifier` to dbaas-aggregator:

```json
{
  "microserviceName": "my-service",
  "scope": "service",
  "namespace": "my-namespace",
  "customKeys": { "logicalDBName": "configs", "shardCount": 5 },
  "region": { "name": "us-east", "az": "a" }
}
```

> **Identity & symmetry.** The aggregator sorts classifier keys alphabetically
> and compares the whole JSONB for identity: two classifiers with the same keys
> and JSON-equal values resolve to the same database (differing values in any
> nested object yield different identities — JSONB deep-compare). Because
> `customKeys` and `extraKeys` are part of that identity, every consumer's
> dbaas-client must build the **same** keys/values — otherwise the database (and
> any mounted Secret) will not be found.

**Top-level spec fields:**

| Field | Required | Mutable | Description |
|-------|:--------:|:-------:|-------------|
| `spec.classifier` | Yes | No | Database identity in dbaas-aggregator. Immutable after creation. |
| `spec.type` | Yes | No | Database engine type (e.g., `postgresql`, `mongodb`). Must match a type known to dbaas-aggregator. Immutable after creation. |
| `spec.dbName` | Yes | No | Logical database name. Included in the aggregator request URL. Immutable after creation. |
| `spec.connectionProperties` | Yes | Yes | List of connection entries, one per access role. At least one entry required. |

> **Note on `spec.classifier` immutability.** The CRD enforces immutability with the CEL rule
> `self == oldSelf` — a strict structural comparison. Once an `ExternalDatabase` is created, the
> exact shape of `spec.classifier` is frozen: you can neither add an optional field that was
> initially omitted (e.g. `namespace`, `tenantId`, `customKeys`) nor remove one that was present.
>
> In particular, `spec.classifier.namespace` defaults to `metadata.namespace` at the controller
> level *only when the field is absent from the spec*. After creation, this defaulting is
> effectively frozen — adding an explicit `spec.classifier.namespace` later (even with the same
> value as `metadata.namespace`) will be rejected by `kube-apiserver` with
> `"spec.classifier is immutable after creation"`. If you want an explicit namespace in the
> classifier, set it at creation time.
>
> Functionally this is not a limitation: the controller always uses `metadata.namespace` as the
> default when `spec.classifier.namespace` is empty, so the aggregator receives the correct
> namespace in either form. The constraint only applies to refactoring an existing CR's YAML.

**`spec.connectionProperties[]` fields:**

| Field | Required | Description |
|-------|:--------:|-------------|
| `role` | Yes | Access role name (e.g., `admin`, `readonly`). Adapter-specific. |
| `credentialsSecretRef` | No | Reference to a Kubernetes Secret containing credentials. Secret must be in the same namespace as the CR. |
| `extraProperties` | No | Free-form map of additional adapter-specific connection properties (e.g., `host`, `port`, `sslMode`). |

**Priority when building the aggregator request:** `role` and Secret credentials always override matching keys in
`extraProperties`.

**`credentialsSecretRef` fields:**

| Field | Required | Description |
|-------|:--------:|-------------|
| `name` | Yes | Name of the Kubernetes Secret |
| `keys` | Yes | List of `{key, name}` mappings. At least one entry required. Duplicate `name` values within the list are rejected by the controller with `InvalidSpec`. |
| `keys[].key` | Yes | Key in `Secret.data` to read (e.g., `db-user`) |
| `keys[].name` | Yes | Target field name in the aggregator request (e.g., `username`) |

> **Credential rotation:** the operator does **not** watch Secrets. Each `ExternalDatabase` is re-reconciled on a
> periodic resync (`DBAAS_EXTERNAL_DATABASE_RESYNC_INTERVAL`, default `10m`); every reconcile re-reads the referenced
> Secrets and pushes any changed credentials to dbaas-aggregator without a manual spec change. So a credential rotation
> is picked up within one resync interval rather than instantly. Secret bodies are not cached — they are fetched from
> the API server only at reconcile time.

> **Force an immediate refresh:** to apply a referenced-Secret change at once (instead of waiting for the resync),
> change the `dbaas.netcracker.com/refresh` annotation on the CR — the controller reconciles immediately, re-reads the
> Secret, and re-registers with dbaas-aggregator. Use a changing value (e.g. a timestamp) so the underlying watch fires:

```bash
kubectl annotate externaldatabase <name> dbaas.netcracker.com/refresh="$(date +%s)" --overwrite
```

#### How ExternalDatabase Works

A reconcile is triggered when any of the following happens:

- The CR is created.
- The CR spec changes (i.e., `metadata.generation` increments).
- The periodic resync fires (every `DBAAS_EXTERNAL_DATABASE_RESYNC_INTERVAL`, default `10m`). Each reconcile re-reads
  the referenced Secrets, so a credential rotation is picked up on the next resync — the operator does **not** watch
  Secrets, so the reaction is bounded by this interval rather than instant.
- The `dbaas.netcracker.com/refresh` annotation changes — a manual escape hatch to apply a referenced-Secret change at
  once, without waiting for the resync (see below).

On each reconcile, the controller:

1. Checks `spec.operatorNamespace` against `CLOUD_NAMESPACE` (skips if assigned elsewhere).
2. Validates that `spec.classifier.namespace`, if set, equals `metadata.namespace`.
3. Reads credentials from all referenced Kubernetes Secrets.
4. Sends a `PUT` request to dbaas-aggregator to register or update the database.
5. Updates `status.phase` and `status.conditions` based on the outcome.

```text
CR created / spec changed / periodic resync (re-reads Secrets)
        │
        ▼
  Operator assignment check (`spec.operatorNamespace`)
        │ assigned elsewhere → skip
        ▼
  phase = Processing
        │
        ▼
  Pre-flight validation
    classifier.namespace ≠ metadata.namespace? ──────▶ InvalidConfiguration (InvalidSpec)
    duplicate name in credentialsSecretRef.keys? ────▶ InvalidConfiguration (InvalidSpec)
        │
        ▼
  Read Secrets
    Secret not found? ──────────────────────────────▶ BackingOff (SecretError, retried)
    Key missing or empty? ──────────────────────────▶ BackingOff (SecretError, retried)
        │
        ▼
  Call dbaas-aggregator PUT
    401 ────────────────────────────────────────────▶ BackingOff (Unauthorized, retried)
    400 / 403 / 409 / 410 / 422 ────────────────────▶ InvalidConfiguration (AggregatorRejected)
    5xx / network ──────────────────────────────────▶ BackingOff (AggregatorError, retried)
        │
        ▼
  Succeeded — Ready=True / DatabaseRegistered
```

#### ExternalDatabase Status Reference

Shared phases, conditions, reasons, and diagnostic rules are described in
[Common Status Model](#common-status-model). `Succeeded` here means the database is registered with
dbaas-aggregator. Kind-specific reasons:

| Reason | Applied to | Meaning |
|--------|-----------|---------|
| `DatabaseRegistered` | `Ready=True` | Successfully registered with dbaas-aggregator |
| `SecretError` | `Ready=False`, `Stalled=False` | Failed to resolve credentials from a referenced Kubernetes Secret. Sub-categories are visible through the `dbaas_secret_resolution_errors_total{reason=...}` metric: `secret_not_found`, `key_missing`, `key_empty`, `forbidden` (RBAC denial), `secret_read_failed` (other API or I/O errors). |

**Full state matrix:**

| Scenario | `phase` | `Ready` | `Reason` | `Stalled` |
|----------|---------|:-------:|----------|:---------:|
| Registered (201) | `Succeeded` | `True` | `DatabaseRegistered` | `False` |
| `classifier.namespace` mismatch | `InvalidConfiguration` | `False` | `InvalidSpec` | `True` |
| Duplicate `name` in `credentialsSecretRef.keys` | `InvalidConfiguration` | `False` | `InvalidSpec` | `True` |
| Secret not found / key missing / key empty / forbidden / read failed | `BackingOff` | `False` | `SecretError` | `False` |
| Aggregator 401 | `BackingOff` | `False` | `Unauthorized` | `False` |
| Aggregator 400 / 403 / 409 / 410 / 422 | `InvalidConfiguration` | `False` | `AggregatorRejected` | `True` |
| Aggregator 5xx / network | `BackingOff` | `False` | `AggregatorError` | `False` |

See [Diagnostic rules](#common-status-model) for reading these conditions.

**`status.observedGeneration`** is stamped whenever the reconcile returns without an error — on success and
on a permanent (`Stalled=True`) failure alike. A transient failure returns an error and leaves it behind.

#### ExternalDatabase Usage Examples

**Full example with credentials Secret:**

```yaml
# Secret with database credentials (must be in the same namespace as the CR)
apiVersion: v1
kind: Secret
metadata:
  name: pg-external-credentials
  namespace: my-namespace
type: Opaque
stringData:
  db-user: app_user
  db-pass: s3cr3t
---
apiVersion: dbaas.netcracker.com/v1
kind: ExternalDatabase
metadata:
  name: my-postgres-external
  namespace: my-namespace
spec:
  operatorNamespace: dbaas-system
  classifier:
    namespace: my-namespace
    microserviceName: my-service
    scope: service
  type: postgresql
  dbName: my_application_db
  connectionProperties:
    - role: admin
      credentialsSecretRef:
        name: pg-external-credentials
        keys:
          - key: db-user
            name: username
          - key: db-pass
            name: password
      extraProperties:
        sslMode: "require"
        connectTimeout: "10"
    - role: readonly
      credentialsSecretRef:
        name: pg-external-credentials-ro
        keys:
          - key: db-user
            name: username
          - key: db-pass
            name: password
```

**Check status:**

```bash
kubectl get dbedb -n my-namespace
# NAME                    PHASE       READY   TYPE         DBNAME              AGE
# my-postgres-external    Succeeded   True    postgresql   my_application_db   2m

kubectl describe dbedb my-postgres-external -n my-namespace
```

**Troubleshoot a stuck resource:**

```bash
# Check conditions
kubectl get dbedb my-postgres-external -n my-namespace -o jsonpath='{.status.conditions}' | jq .

# If Stalled=True — the spec has an error; read the Message field
# If Stalled=False and Ready=False — transient error, controller is retrying;
#   use lastRequestId to look up logs
kubectl get dbedb my-postgres-external -n my-namespace -o jsonpath='{.status.lastRequestId}'
```

---

### DatabaseAccessPolicy

`DatabaseAccessPolicy` declares the database role assignments for microservices in a namespace. The operator forwards
this declaration to dbaas-aggregator, which applies the role grants when provisioning or connecting databases for those
microservices.

Short name: `dbdap`

`kubectl get dbdap` columns: `PHASE`, `READY`, `MICROSERVICENAME`, `AGE`

#### DatabaseAccessPolicy Resource Fields

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: DatabaseAccessPolicy
metadata:
  name: my-policy
  namespace: my-namespace
spec:
  operatorNamespace: dbaas-system
  microserviceName: my-service
  services:
    - name: other-service
      roles:
        - admin
    - name: reporting-service
      roles:
        - readonly
  policy:
    - type: postgresql
      defaultRole: readonly
      additionalRole:
        - admin
  disableGlobalPermissions: false
```

**Top-level spec fields:**

| Field | Required | Mutable | Description |
|-------|:--------:|:-------:|-------------|
| `spec.microserviceName` | Yes | **No** | The microservice that owns this policy. Sent as `metadata.microserviceName` in the aggregator payload. Immutable after creation (CRD CEL rule `self == oldSelf`): repointing the same CR at a different microservice would silently rewrite role grants under the original Kubernetes object and lose the audit link to who created the policy. Create a new CR for a different service. |
| `spec.services` | At least one of `services` or `policy` | Yes | Per-microservice role assignments. |
| `spec.policy` | At least one of `services` or `policy` | Yes | Default role rules per database type, applied to services not listed in `services`. |
| `spec.disableGlobalPermissions` | No | Yes | When `true`, opts out of dbaas-aggregator's default global permission grants. Defaults to `false`. |

**`spec.services[]` fields:**

| Field | Required | Description |
|-------|:--------:|-------------|
| `name` | Yes | Microservice name. Must match the service's `app.kubernetes.io/name` label. Minimum length: 1. |
| `roles` | Yes | List of database roles granted to this microservice. At least one role required. Role names are adapter-specific (e.g., `admin`, `readonly`, `readwrite`). |

**`spec.policy[]` fields:**

| Field | Required | Description |
|-------|:--------:|-------------|
| `type` | Yes | Database engine type this rule applies to (e.g., `postgresql`, `mongodb`). Must match a type known to dbaas-aggregator. |
| `defaultRole` | Yes | Role assigned to any microservice not explicitly listed in `services`. |
| `additionalRole` | No | Extra roles that may be granted beyond `defaultRole`. Interpretation is adapter-specific. |

> **Constraint:** at least one of `spec.services` or `spec.policy` must be non-empty. A CR with both fields absent is
> rejected by the controller with `InvalidSpec` before the aggregator is contacted.

#### How DatabaseAccessPolicy Works

Each time the spec changes (i.e., `metadata.generation` increments), the controller:

1. Checks `spec.operatorNamespace` against `CLOUD_NAMESPACE` (skips if assigned elsewhere).
2. Validates that at least one of `services` or `policy` is non-empty.
3. Sends a `POST /api/declarations/v1/apply` request to dbaas-aggregator with `subKind: DbPolicy`.
4. Updates `status.phase` and `status.conditions` based on the outcome.

```text
CR created / spec changed
        │
        ▼
  Operator assignment check (`spec.operatorNamespace`)
        │ assigned elsewhere → skip
        ▼
  phase = Processing
        │
        ▼
  Pre-flight validation
    services and policy both empty? ────────────────▶ InvalidConfiguration (InvalidSpec)
        │
        ▼
  Call dbaas-aggregator POST /api/declarations/v1/apply
    401 ────────────────────────────────────────────▶ BackingOff (Unauthorized, retried)
    400 / 403 / 409 / 410 / 422 ────────────────────▶ InvalidConfiguration (AggregatorRejected)
    5xx / network ──────────────────────────────────▶ BackingOff (AggregatorError, retried)
        │
        ▼
  Succeeded — Ready=True / PolicyApplied
```

#### DatabaseAccessPolicy Status Reference

Shared phases, conditions, reasons, and diagnostic rules are described in
[Common Status Model](#common-status-model). `Succeeded` here means the policy was applied through
dbaas-aggregator. Kind-specific reasons:

| Reason | Applied to | Meaning |
|--------|-----------|---------|
| `PolicyApplied` | `Ready=True` | Policy successfully applied through dbaas-aggregator |

`InvalidSpec` has a single cause for this kind: both `services` and `policy` are empty.

**Full state matrix:**

| Scenario | `phase` | `Ready` | `Reason` | `Stalled` |
|----------|---------|:-------:|----------|:---------:|
| Applied (200) | `Succeeded` | `True` | `PolicyApplied` | `False` |
| Both `services` and `policy` empty | `InvalidConfiguration` | `False` | `InvalidSpec` | `True` |
| Aggregator 401 | `BackingOff` | `False` | `Unauthorized` | `False` |
| Aggregator 400 / 403 / 409 / 410 / 422 | `InvalidConfiguration` | `False` | `AggregatorRejected` | `True` |
| Aggregator 5xx / network | `BackingOff` | `False` | `AggregatorError` | `False` |

See [Diagnostic rules](#common-status-model) for reading these conditions.

#### DatabaseAccessPolicy Usage Examples

**Grant a specific microservice admin access:**

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: DatabaseAccessPolicy
metadata:
  name: my-policy
  namespace: my-namespace
spec:
  operatorNamespace: dbaas-system
  microserviceName: my-service
  services:
    - name: other-service
      roles:
        - admin
```

**Set default roles per database type (for all services not explicitly listed):**

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: DatabaseAccessPolicy
metadata:
  name: my-policy
  namespace: my-namespace
spec:
  operatorNamespace: dbaas-system
  microserviceName: my-service
  policy:
    - type: postgresql
      defaultRole: readonly
      additionalRole:
        - admin
```

**Check status:**

```bash
kubectl get dbdap -n my-namespace
# NAME        PHASE       READY   MICROSERVICENAME   AGE
# my-policy   Succeeded   True    my-service         1m

kubectl describe dbdap my-policy -n my-namespace
```

**Troubleshoot a stuck resource:**

```bash
# Check conditions
kubectl get dbdap my-policy -n my-namespace -o jsonpath='{.status.conditions}' | jq .

# Use lastRequestId to correlate with aggregator logs
kubectl get dbdap my-policy -n my-namespace -o jsonpath='{.status.lastRequestId}'
```

---

### InternalDatabase

`InternalDatabase` declares a logical database that dbaas-aggregator should provision and manage on behalf of the owning
microservice. Unlike `ExternalDatabase`, the database does **not** need to exist in advance — the aggregator creates it
(and, depending on the configured adapter, the underlying physical DB / user / schema).

Provisioning may be **synchronous or asynchronous**: the aggregator either completes the apply and returns `200 OK`, or
returns `202 Accepted` with a `trackingId`, in which case the operator polls the operation status until it reaches a
terminal state.

Short name: `dbidb`

`kubectl get dbidb` columns: `PHASE`, `READY`, `MICROSERVICENAME`, `TYPE`, `AGE`

#### InternalDatabase Resource Fields

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: InternalDatabase
metadata:
  name: my-app-db
  namespace: my-namespace
spec:
  operatorNamespace: dbaas-system
  classifier:
    microserviceName: my-service   # required
    scope: service                 # required; "service" or "tenant"
    # namespace: my-namespace      # optional; if set, must equal metadata.namespace
    # tenantId: my-tenant          # only meaningful when scope=tenant
    # customKeys:                  # optional adapter-specific identifiers, nested under "customKeys" on the wire
    #   logicalDBName: payments
    # extraKeys:                   # optional arbitrary identity fields, flattened to the classifier top level
    #   region: eu
  type: postgresql
  # lazy: false                    # if true, defer provisioning until first access
  # namePrefix: "myapp"            # prefix applied to the physical DB name
  # settings:                      # adapter-specific connection / DB settings (JSON values)
  #   encoding: UTF8
  #   pgExtensions:
  #     - vector
  # versioningConfig:
  #   approach: clone              # how a new version is created during blue-green
  # initialInstantiation:
  #   approach: clone              # "clone" or "new" (default: "new")
  #   sourceClassifier:            # required when approach=clone
  #     microserviceName: my-service
  #     scope: service
```

**`spec.classifier`** — uniquely identifies the database in dbaas-aggregator.

| Field | Required | Notes |
|-----|:--------:|-------|
| `microserviceName` | Yes | Name of the owning microservice |
| `scope` | Yes | `service` or `tenant` |
| `namespace` | No | If set, must equal `metadata.namespace` — controller-side validation; mismatch causes `InvalidConfiguration`/`InvalidSpec`. If absent, the **operator** defaults it to `metadata.namespace` before sending; the aggregator requires a namespace in the classifier and rejects one without it |
| `tenantId` | No | Only meaningful when `scope=tenant`. **When absent**, the declaration is a tenant-agnostic template — the aggregator applies it to tenants already registered in the namespace and materializes a per-tenant database lazily, on each tenant's first runtime connection. **When set**, the operator additionally eagerly materializes that concrete tenant's database after the declarative apply — see [Tenant database materialization](#tenant-database-materialization) |
| `customKeys` | No | Adapter-specific identifiers, emitted as a **nested** `customKeys` object on the wire (`classifier.customKeys.*`). Values can be any JSON type (string, number, boolean, nested object). Not validated by the aggregator — passed through as-is |
| `extraKeys` | No | Arbitrary additional identity fields **flattened onto the classifier top level** (legacy open-classifier compatibility). The reserved keys `microserviceName`, `scope`, `namespace`, `tenantId`, `customKeys` are not allowed — the controller rejects the spec with `InvalidConfiguration`. Both the operator and every consuming dbaas-client must produce the same keys/values for identity to match |

**Top-level spec fields:**

| Field | Required | Mutable | Description |
|-------|:--------:|:-------:|-------------|
| `spec.classifier` | Yes | **No** | Database identity in dbaas-aggregator. Immutable after creation (CRD CEL rule `self == oldSelf`): switching the classifier on an existing CR would re-target the controller at a different database while `status.trackingId` and `status.observedGeneration` still reference the original one. Delete and recreate the CR to rebind. |
| `spec.type` | Yes | **No** | Database engine type (e.g., `postgresql`, `mongodb`). Must match a type known to dbaas-aggregator. Immutable after creation: changing the engine mid-flight would request provisioning of a fresh database on a different adapter while the original one stays registered under the same CR identity. |
| `spec.lazy` | No | Yes | When `true`, provisioning is deferred until first access. Defaults to `false`. **Prohibited** in combination with `initialInstantiation.approach=clone` — controller rejects with `InvalidSpec` |
| `spec.settings` | No | Yes | Free-form map of adapter-specific settings. Values may be any valid JSON type |
| `spec.namePrefix` | No | Yes | Prefix applied to the physical database name created in the DBMS |
| `spec.versioningConfig` | No | Yes | Strategy for blue-green database versioning. If absent → `versioningType=static`. If present → `versioningType=version` |
| `spec.initialInstantiation` | No | Yes | Initial database creation strategy. If absent → `approach=new` |

> **Note on `spec.classifier` immutability** — the CEL rule is a strict structural equality check (`self == oldSelf`).
> Once the CR is created, the exact shape of the classifier is frozen: you can neither add an optional sub-field that
> was omitted (e.g. `namespace`, `tenantId`, `customKeys`) nor remove one that was present. The same caveat applies as
> for `ExternalDatabase.spec.classifier` — see the immutability note in that section for the practical implications (the
> controller still defaults `classifier.namespace` to `metadata.namespace` when the field is absent, so the aggregator
> receives the right namespace either way).

**`spec.versioningConfig` fields:**

| Field | Required | Description |
|-------|:--------:|-------------|
| `approach` | No | Strategy for creating a new database version during blue-green updates. Adapter-specific; aggregator default is `clone` |

**`spec.initialInstantiation` fields:**

| Field | Required | Description |
|-------|:--------:|-------------|
| `approach` | No | `clone` (clone from `sourceClassifier`) or `new` (create an empty database). Default behavior when the field is absent is `new` |
| `sourceClassifier` | Required when `approach=clone` | Classifier of the source database to clone from. **Constraint:** `sourceClassifier.microserviceName` must equal `classifier.microserviceName` (enforced by the controller) |

> **Note on async provisioning:** the operator stores the aggregator's `trackingId` in `status.trackingId` and polls
> until the operation completes (every 5 s). While polling, `status.phase` is `WaitingForDependency` and
> `status.conditions[].reason` is `ProvisioningStarted`. Spec changes during polling clear the stale `trackingId` and
> start a fresh submission — see [Status Reference](#internaldatabase-status-reference).

#### How InternalDatabase Works

A reconcile is triggered when any of the following happens:

- The CR is created.
- The CR spec changes (i.e., `metadata.generation` increments).
- A polling cycle: while an async operation is in progress (`status.trackingId` is set), the controller re-enqueues
  itself every 5 seconds.

The reconcile loop has two branches:

- **SUBMIT** — no pending `trackingId`. Validates the spec, builds the declarative payload, sends `POST
  /api/declarations/v1/apply` with `subKind=DatabaseDeclaration`.
- **POLL** — `status.trackingId` present. Sends `GET /api/declarations/v1/operation/{trackingId}/status` and reacts to
  the returned task state.

```text
CR created / spec changed
        │
        ▼
  Operator assignment check (`spec.operatorNamespace`)
        │ assigned elsewhere → skip
        ▼
  phase = Processing
        │
        ▼
  Pre-flight validation (controller-side)
    classifier.namespace ≠ metadata.namespace? ─────▶ InvalidConfiguration (InvalidSpec)
    lazy=true AND initialInstantiation.approach=clone? ▶ InvalidConfiguration (InvalidSpec)
    approach=clone AND sourceClassifier absent? ────▶ InvalidConfiguration (InvalidSpec)
    sourceClassifier.microserviceName ≠ classifier.microserviceName? ▶ InvalidConfiguration (InvalidSpec)
        │
        ├── trackingId present in status?
        │
        ▼ no                              ▼ yes
  ┌── SUBMIT ──────────────┐    ┌── POLL ─────────────────┐
  │ POST /apply            │    │ GET /operation/{id}     │
  │   401 ▶ BackingOff     │    │   401 ▶ BackingOff      │
  │   400/403/409/410/422  │    │   404 ▶ BackingOff      │
  │     ▶ InvalidConfig    │    │     (trackingId cleared │
  │   5xx/network          │    │      → resubmit)        │
  │     ▶ BackingOff       │    │   5xx/network           │
  │   200 OK ▶ Succeeded   │    │     ▶ BackingOff        │
  │   202 Accepted         │    │                         │
  │     store trackingId   │    │ task state:             │
  │     ▶ WaitingForDep    │    │   IN_PROGRESS ▶ poll    │
  └────────────────────────┘    │   COMPLETED ▶ Succeeded │
                                │   FAILED    ▶ InvalidConfig
                                │   TERMINATED ▶ BackingOff
                                │     (trackingId cleared │
                                │      → resubmit)        │
                                └─────────────────────────┘
```

> For a `scope=tenant` declaration that pins a `tenantId`, the **Succeeded** transition (both the
> `200 OK` and `COMPLETED` paths) is preceded by a get-or-create that materializes the concrete
> tenant database — see [Tenant database materialization](#tenant-database-materialization) below.

#### Tenant Database Materialization

A `scope=tenant` declaration is, by default, a **tenant-agnostic template**: the aggregator drops `tenantId` when it
stores a tenant declaration and provisions a concrete per-tenant database only when that tenant first connects at
runtime (plus for tenants already registered in the namespace). A freshly declared tenant that has never connected
therefore has **no database** — and a `DatabaseSecretClaim` for `{scope=tenant, tenantId}` would call the
[connection-lookup endpoint](#databasesecretclaim-connection-lookup-endpoint), get `DatabaseNotFound`, and wait
indefinitely.

When the classifier **pins a concrete `tenantId`**, the operator closes that gap. After the declarative apply succeeds —
on both the synchronous `200 OK` and the asynchronous `COMPLETED` paths — and **before** marking the CR `Succeeded`, it
issues a get-or-create for that exact tenant database:

```text
PUT /api/v3/dbaas/{namespace}/databases
  {
    "classifier": { …, "scope": "tenant", "tenantId": "<tenantId>" },
    "type": "<type>",
    "originService": "<microserviceName>"
  }
```

This materializes the database exactly as the tenant's first runtime connection would, so a matching
`DatabaseSecretClaim` resolves immediately instead of waiting on `DatabaseNotFound`. The call is:

- **scoped** — a no-op for `scope=service`, or for a tenant declaration **without** a pinned `tenantId` (the
  tenant-agnostic template behavior is unchanged);
- **idempotent** — get-or-create returns the existing database on subsequent reconciles;
- **possibly asynchronous** — the aggregator answers `200`/`201` when the database is ready, and `202 Accepted` when it
  only started creating it. A `202` body carries no usable credentials (`password: null`, requested role absent), so
  the operator treats it as *not done*: the CR goes to `WaitingForDependency` with `Ready=False` / reason
  `ProvisioningStarted` and is re-tried every 5 s until the database answers with credentials. There is no `trackingId`
  for this endpoint, so the retry repeats the idempotent apply + get-or-create rather than polling;
- **gating** — if it fails, the CR does **not** become `Succeeded`: a transient/5xx failure surfaces as `BackingOff` and
  is retried on the next reconcile, exactly like the `apply` call;
- **observable** — recorded on `dbaas_aggregator_requests_total` and `dbaas_aggregator_request_duration_seconds` under
  `operation="create_database"`.

#### InternalDatabase Status Reference

Shared phases, conditions, reasons, and diagnostic rules are described in
[Common Status Model](#common-status-model). This kind adds one phase, `WaitingForDependency`, which has
**two** causes:

- the declarative apply returned `202 Accepted` and the controller is polling the operation
  (`status.trackingId` is set), or
- the apply is done but the pinned-tenant get-or-create returned `202` — the database is still being
  created, and `status.trackingId` is **empty** because that endpoint issues none (see
  [Tenant Database Materialization](#tenant-database-materialization)).

`Succeeded` means both steps finished.

**`status.trackingId`** — aggregator-assigned tracking ID for an in-flight async operation.

- Set when `POST /api/declarations/v1/apply` returns `202 Accepted`.
- Cleared when polling completes (`COMPLETED`, `FAILED`) or the operation must be re-submitted (`TERMINATED`, `404 Not
  Found`).
- While `trackingId` is non-empty, every reconcile goes through the POLL branch (no resubmission).
- An **empty** `trackingId` together with `WaitingForDependency` is not an inconsistency: it is the
  tenant-materialization wait, which has nothing to poll and instead repeats the idempotent apply +
  get-or-create every 5 s.

**`status.pendingOperationGeneration`** — the `metadata.generation` value captured when `trackingId` was set. If a newer
`generation` is observed during a reconcile, the stale `trackingId` is discarded and the operation is re-submitted with
the new spec. It is reset to `0` together with `trackingId` whenever the operation reaches a terminal state
(`COMPLETED`/`FAILED`) or the tracking is cleared (`TERMINATED`/`404`); `0` therefore means "no pending async
operation".

**Kind-specific reasons** (in addition to the [shared vocabulary](#common-status-model)):

| Reason | Applied to | Meaning |
|--------|-----------|---------|
| `DatabaseProvisioned` | `Ready=True` | Operation completed (`200 OK` synchronous, or polled `COMPLETED`) |
| `ProvisioningStarted` | `Ready=False`, `Stalled=False` | A `202 Accepted` is outstanding: either the declarative apply is being polled (`trackingId` set), or the pinned-tenant get-or-create is still creating the database (`trackingId` empty) |
| `OperationTerminated` | `Ready=False`, `Stalled=False` | Poll returned `TERMINATED` (aggregator restart or admin cancellation). The stale `trackingId` is cleared and the controller resubmits on the next reconcile |

For this kind `AggregatorRejected` also covers a polled `FAILED`, and `AggregatorError` also covers a
polling `404` (expired `trackingId`).

> While an operation is `IN_PROGRESS`, the poll refreshes only the `Ready` condition. A `Stalled` condition
> left by an earlier transient error keeps its previous reason and message until the operation reaches a
> terminal state.

**Full state matrix:**

| Scenario | `phase` | `Ready` | `Reason` | `Stalled` | `trackingId` |
|----------|---------|:-------:|----------|:---------:|:------------:|
| Pre-flight failed | `InvalidConfiguration` | `False` | `InvalidSpec` | `True` | — |
| POST → 401 | `BackingOff` | `False` | `Unauthorized` | `False` | — |
| POST → 400 / 403 / 409 / 410 / 422 | `InvalidConfiguration` | `False` | `AggregatorRejected` | `True` | — |
| POST → 5xx / network | `BackingOff` | `False` | `AggregatorError` | `False` | — |
| POST → 200 OK (sync), materialization 200/201 or not needed | `Succeeded` | `True` | `DatabaseProvisioned` | `False` | — |
| Apply done, tenant materialization → 202 | `WaitingForDependency` | `False` | `ProvisioningStarted` | `False` | **empty** |
| Apply done, tenant materialization → 401 | `BackingOff` | `False` | `Unauthorized` | `False` | — |
| Apply done, tenant materialization → 400 / 403 / 409 / 410 / 422 | `InvalidConfiguration` | `False` | `AggregatorRejected` | `True` | — |
| Apply done, tenant materialization → 5xx / other 4xx / network | `BackingOff` | `False` | `AggregatorError` | `False` | — |
| POST → 202 Accepted | `WaitingForDependency` | `False` | `ProvisioningStarted` | `False` | set |
| Poll → IN_PROGRESS | `WaitingForDependency` | `False` | `ProvisioningStarted` | `False` | set |
| Poll → COMPLETED | `Succeeded` | `True` | `DatabaseProvisioned` | `False` | cleared |
| Poll → FAILED | `InvalidConfiguration` | `False` | `AggregatorRejected` | `True` | cleared |
| Poll → TERMINATED | `BackingOff` | `False` | `OperationTerminated` | `False` | cleared (resubmits) |
| Poll → 404 (trackingId expired) | `BackingOff` | `False` | `AggregatorError` | `False` | cleared (resubmits) |
| Poll → 401 / 5xx / network | `BackingOff` | `False` | `Unauthorized` / `AggregatorError` | `False` | preserved (keeps polling) |

**Diagnostic rules** — in addition to the [shared rules](#common-status-model):

- **`Ready=False` with phase `WaitingForDependency`** — not an error: something is still provisioning and the
  controller re-checks every 5 seconds. Only phase `BackingOff` means a failed call is being retried.
  Read `status.trackingId` to tell the two waits apart: **set** means the declarative operation is being
  polled; **empty** means the pinned-tenant database is still being created and the controller repeats the
  apply + get-or-create. An empty `trackingId` here is expected — that endpoint returns none. If the wait
  does not clear, look at the tenant database on the aggregator side rather than at the CR.

#### InternalDatabase Usage Examples

**Minimal declaration (synchronous-friendly, non-versioned):**

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: InternalDatabase
metadata:
  name: my-app-db
  namespace: my-namespace
spec:
  operatorNamespace: dbaas-system
  classifier:
    microserviceName: my-service
    scope: service
  type: postgresql
```

**Tenant-scoped with a pinned `tenantId` (eager materialization):**

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: InternalDatabase
metadata:
  name: my-app-db-acme
  namespace: my-namespace
spec:
  operatorNamespace: dbaas-system
  classifier:
    microserviceName: my-service
    scope: tenant
    tenantId: acme        # operator materializes this concrete tenant's database after apply
  type: postgresql
```

After the declarative apply, the operator get-or-creates the `{scope=tenant, tenantId: acme}` database, so a
`DatabaseSecretClaim` with the same classifier resolves without waiting on `DatabaseNotFound` — see [Tenant database
materialization](#tenant-database-materialization).

**Clone from an existing database:**

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: InternalDatabase
metadata:
  name: my-app-db-clone
  namespace: my-namespace
spec:
  operatorNamespace: dbaas-system
  classifier:
    microserviceName: my-service
    scope: service
  type: postgresql
  initialInstantiation:
    approach: clone
    sourceClassifier:
      microserviceName: my-service   # must match classifier.microserviceName
      scope: service
```

**Versioned (blue-green) database with adapter settings:**

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: InternalDatabase
metadata:
  name: payments-db
  namespace: my-namespace
spec:
  operatorNamespace: dbaas-system
  classifier:
    microserviceName: payments
    scope: service
    customKeys:
      logicalDBName: payments
  type: postgresql
  namePrefix: pay
  settings:
    encoding: UTF8
    pgExtensions:
      - vector
  versioningConfig:
    approach: clone
```

**Check status:**

```bash
kubectl get dbidb -n my-namespace
# NAME              PHASE                  READY   MICROSERVICENAME   TYPE         AGE
# my-app-db         Succeeded              True    my-service         postgresql   2m
# my-app-db-clone   WaitingForDependency   False   my-service         postgresql   10s
```

**Watch async progress:**

```bash
# The trackingId field is populated while async provisioning is in progress
kubectl get dbidb my-app-db -n my-namespace -o jsonpath='{.status.trackingId}{"\n"}'

# Full status (phase, conditions, trackingId, lastRequestId)
kubectl get dbidb my-app-db -n my-namespace -o yaml
```

**Troubleshoot a stuck resource:**

```bash
# Check conditions for the human-readable error message
kubectl get dbidb my-app-db -n my-namespace -o jsonpath='{.status.conditions}' | jq .

# Use lastRequestId to correlate with aggregator logs
kubectl get dbidb my-app-db -n my-namespace -o jsonpath='{.status.lastRequestId}'
```

---

### Balancing Rule CRDs

The operator exposes three balancing rule CRDs. Each CR stores a **list** of rule entries, and each kind is
intentionally a singleton within its allowed scope. The operator validates the Kubernetes resource and reconciles the
desired rule list into dbaas-aggregator. All three CRDs declare their operator through
immutable `spec.operatorNamespace`.
dbaas-aggregator remains the runtime source of truth when a logical
database is created and a physical database must be selected.

| Kind | Fixed `metadata.name` | Where the CR lives | What it controls |
|------|------------------------|--------------------|------------------|
| `MicroserviceBalancingRule` | `microservice-balancing-rules` | Business namespace (operator-assigned) | Per-microservice placement rules for that namespace |
| `NamespaceBalancingRule` | `namespace-balancing-rules` | Business namespace (operator-assigned) | Per-namespace placement rules for that namespace |
| `PermanentBalancingRule` | `permanent-balancing-rules` | Assigned operator namespace | Permanent placement rules targeting any business namespaces |

For `PermanentBalancingRule`, `metadata.namespace` must equal `spec.operatorNamespace`. The controller rejects any
other placement as `InvalidConfiguration` before calling dbaas-aggregator. The namespaces listed in
`spec.rules[].namespaces` do not need their own assignment; dbaas-aggregator remains the authority on those targets.

Any other `metadata.name` is rejected **at admission** by a root-level CRD CEL rule (`self.metadata.name ==
'<fixed-name>'`), so `kubectl apply` fails and no CR is created — there is no object to carry an `InvalidConfiguration`
status. The controller repeats the check as defense in depth. For the two business-namespace CRDs, use one CR per
business namespace and edit `spec.rules` to add, update, or remove entries. Use one permanent singleton in the
assigned operator namespace.

#### Balancing Rule Resource Fields

**`MicroserviceBalancingRule`**

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: MicroserviceBalancingRule
metadata:
  name: microservice-balancing-rules
  namespace: payments
spec:
  operatorNamespace: dbaas-system
  rules:
    - type: postgresql
      label: core_balancing_rule=core
      microservices:
        - control-plane
        - identity-provider
```

| Field | Required | Description |
|-------|:--------:|-------------|
| `metadata.name` | Yes | Must be `microservice-balancing-rules`. |
| `metadata.namespace` | Yes | Business namespace. `spec.operatorNamespace` must identify this operator. |
| `spec.operatorNamespace` | Yes | Operator namespace; must equal `CLOUD_NAMESPACE` and is immutable after creation. |
| `spec.rules` | Yes | Non-empty list of microservice balancing entries. |
| `spec.rules[].type` | Yes | Database type, for example `postgresql` or `mongodb`. |
| `spec.rules[].label` | Yes | Physical database label selector in `key=value` form. |
| `spec.rules[].microservices` | Yes | Non-empty list of microservice names affected by this rule. |

Within one CR, the same `type + microservice` pair cannot appear more than once.

**`NamespaceBalancingRule`**

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: NamespaceBalancingRule
metadata:
  name: namespace-balancing-rules
  namespace: payments
spec:
  operatorNamespace: dbaas-system
  rules:
    - name: pg-payments
      type: postgresql
      physicalDatabaseId: postgresql-payments
      order: 10
```

| Field | Required | Description |
|-------|:--------:|-------------|
| `metadata.name` | Yes | Must be `namespace-balancing-rules`. |
| `metadata.namespace` | Yes | Business namespace. `spec.operatorNamespace` must identify this operator. |
| `spec.operatorNamespace` | Yes | Operator namespace; must equal `CLOUD_NAMESPACE` and is immutable after creation. |
| `spec.rules` | Yes | Non-empty list of namespace balancing entries. |
| `spec.rules[].name` | Yes | Aggregator rule name. Names are global in the aggregator, so reuse across CRs can clobber state. The controller performs a best-effort global duplicate-name check. |
| `spec.rules[].type` | Yes | Database type. |
| `spec.rules[].physicalDatabaseId` | Yes | Target physical database identifier. |
| `spec.rules[].order` | Yes | Rule priority for the same namespace and database type. Higher `order` wins in the aggregator. |

`order` is mandatory so rule priority is explicit. Without it, omitted values would default to `0`, which makes rule
precedence easy to change accidentally and makes duplicate priorities harder to detect. The controller rejects duplicate
`type + order` pairs within the singleton CR; cross-CR order conflicts are ultimately enforced by the aggregator with
`409 Conflict`.

**`PermanentBalancingRule`**

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: PermanentBalancingRule
metadata:
  name: permanent-balancing-rules
  namespace: dbaas-system
spec:
  operatorNamespace: dbaas-system
  rules:
    - dbType: postgresql
      physicalDatabaseId: postgresql-prod-a
      namespaces:
        - payments
        - orders
```

| Field | Required | Description |
|-------|:--------:|-------------|
| `metadata.name` | Yes | Must be `permanent-balancing-rules`. |
| `metadata.namespace` | Yes | Must equal `spec.operatorNamespace`, placing this singleton in the assigned operator namespace. |
| `spec.operatorNamespace` | Yes | Operator namespace; must equal `CLOUD_NAMESPACE` and is immutable after creation. |
| `spec.rules` | Yes | Non-empty list of permanent balancing entries. |
| `spec.rules[].dbType` | Yes | Database type. |
| `spec.rules[].physicalDatabaseId` | Yes | Target physical database identifier. |
| `spec.rules[].namespaces` | Yes | Non-empty list of target business namespaces. Target namespaces do **not** need an operator assignment; the aggregator is the authority on targets. |

Within one CR, the same `dbType + namespace` pair cannot appear more than once.

#### How Balancing Rules Work

A reconcile is triggered when a balancing rule CR is created, updated, or deleted.

Common flow:

1. Read the singleton CR.
2. Check that `spec.operatorNamespace == CLOUD_NAMESPACE`; otherwise leave the CR untouched.
3. Validate the fixed name and `spec.rules`.
4. Apply the desired rule data to dbaas-aggregator.
5. Update `status.phase`, `status.conditions`, `status.lastRequestId`, and `status.appliedRules`.
6. Emit Kubernetes Events when enabled.

Aggregator calls by kind:

| Kind | Aggregator operation |
|------|----------------------|
| `MicroserviceBalancingRule` | Sends the full microservice rule list to `PUT /api/v3/dbaas/{namespace}/physical_databases/rules/onMicroservices`. |
| `NamespaceBalancingRule` | Sends one `PUT /api/v3/dbaas/{namespace}/physical_databases/balancing/rules/{ruleName}` request per `spec.rules[]` entry. |
| `PermanentBalancingRule` | Sends the full permanent rule list to `PUT /api/v3/dbaas/balancing/rules/permanent`. |

#### Balancing Rule Lifecycle and Cleanup

`status.appliedRules` records what the operator last successfully applied to the aggregator. This allows the controller
to detect removed entries and clean up aggregator-side state.

| Kind | On create/update | On item removal from `spec.rules` | On CR deletion |
|------|------------------|------------------------------------|----------------|
| `MicroserviceBalancingRule` | Adds a finalizer, applies the full desired list, stores applied `type + microservices`. | Sends cleanup for removed applied `type + microservices` by applying an empty rule set for those entries, then applies the new desired list. | Finalizer cleans up all applied microservice entries before Kubernetes removes the CR. |
| `NamespaceBalancingRule` | Adds a finalizer, applies each desired namespace rule by name, and stores applied entries. | Calls `DELETE /api/v3/dbaas/{namespace}/physical_databases/balancing/rules/{ruleName}` for removed applied rule names, then applies the new desired list. | Finalizer deletes all applied namespace rules before Kubernetes removes the CR. |
| `PermanentBalancingRule` | Adds a finalizer, applies the full desired list (no target-ownership check), stores applied `dbType + namespaces`. | Sends cleanup through `DELETE /api/v3/dbaas/balancing/rules/permanent` for removed applied entries, then applies the new desired list. | Finalizer deletes all applied permanent entries before Kubernetes removes the CR. |

For blue-green cleanup, keep the old operator running until any finalizers on microservice, namespace, and permanent
rule CRs have completed.

#### Balancing Rule Status Reference

Shared phases, conditions, reasons, and diagnostic rules are described in
[Common Status Model](#common-status-model). `Succeeded` here means the desired rules were applied to
dbaas-aggregator.

> **No status is written during deletion.** All three deletion paths return before the deferred status
> patch is installed, so a CR stuck in `Terminating` because aggregator cleanup keeps failing still shows
> its pre-deletion `phase` and conditions. The failure surfaces only as a Warning `AggregatorError` event
> and a backoff retry.

**`status.appliedRules`**

`status.appliedRules` is controller-owned bookkeeping. Users edit `spec.rules`; the operator writes
`status.appliedRules` after a successful reconcile so it can compare desired state with previously applied
state later. It is **not** a full echo of the spec — two of the three kinds record a subset:

| Kind | Recorded per entry | Not recorded |
|------|--------------------|--------------|
| `MicroserviceBalancingRule` | `type`, `microservices` | `label` |
| `NamespaceBalancingRule` | `name`, `type`, `physicalDatabaseId`, `order` | — |
| `PermanentBalancingRule` | `dbType`, `namespaces` | `physicalDatabaseId` |

**Kind-specific reason** (in addition to the [shared vocabulary](#common-status-model)):

| Reason | Applied to | Meaning |
|--------|-----------|---------|
| `BalancingRuleApplied` | `Ready=True` | Desired balancing rules were successfully applied to dbaas-aggregator. |

**Diagnostic rules** — in addition to the [shared rules](#common-status-model):

- **CR stuck in `Terminating`** — the aggregator cleanup call keeps failing. Check the CR's Warning
  events and confirm `spec.operatorNamespace` still identifies this operator; assignment is checked
  before the deletion branch.

#### Balancing Rule Usage Examples

**Microservice balancing singleton:**

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: MicroserviceBalancingRule
metadata:
  name: microservice-balancing-rules
  namespace: payments
spec:
  operatorNamespace: dbaas-system
  rules:
    - type: postgresql
      label: core_balancing_rule=core
      microservices:
        - control-plane
        - identity-provider
    - type: mongodb
      label: ext_balancing_rule=ext
      microservices:
        - notification-engine
```

**Namespace balancing singleton:**

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: NamespaceBalancingRule
metadata:
  name: namespace-balancing-rules
  namespace: payments
spec:
  operatorNamespace: dbaas-system
  rules:
    - name: pg-payments
      type: postgresql
      physicalDatabaseId: postgresql-payments
      order: 10
    - name: mongo-payments
      type: mongodb
      physicalDatabaseId: mongodb-payments
      order: 20
```

**Permanent balancing singleton in the assigned operator namespace** (here `dbaas-system`;
target namespaces remain aggregator-managed):

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: PermanentBalancingRule
metadata:
  name: permanent-balancing-rules
  namespace: dbaas-system
spec:
  operatorNamespace: dbaas-system
  rules:
    - dbType: postgresql
      physicalDatabaseId: postgresql-prod-a
      namespaces:
        - payments
        - orders
```

**Check status:**

```bash
kubectl get microservicebalancingrule microservice-balancing-rules -n payments -o yaml
kubectl get namespacebalancingrule namespace-balancing-rules -n payments -o yaml
kubectl get permanentbalancingrule permanent-balancing-rules -n dbaas-system -o yaml
```

### DatabaseSecretClaim

`DatabaseSecretClaim` requests credentials for a database already managed by dbaas-aggregator and materializes them into
a named Kubernetes `Secret` in the same namespace. The operator does **not** provision the database — it looks the
database up by classifier and writes the returned `connectionProperties` into the target Secret, keeping it in sync as
credentials rotate.

The Secret is created with an `ownerReference` to the CR, so deleting the `DatabaseSecretClaim` cascades to the
materialized Secret.

`kubectl get databasesecretclaim` (short name `dbdsc`) columns: `PHASE`, `READY`, `TYPE`, `AGE`

> **Required label** — `metadata.labels["app.kubernetes.io/name"]` must be set. Its value is sent as `originService` in
> the get-by-classifier request, which the aggregator uses to resolve the service's role grants (see
> [DatabaseAccessPolicy](#databaseaccesspolicy)). A CR without this label is rejected with
> `InvalidConfiguration`/`InvalidSpec` and the aggregator is never called. The check is enforced at the controller level
> (CEL validation of `metadata.labels` is not supported by controller-gen at the root schema).

#### DatabaseSecretClaim Resource Fields

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: DatabaseSecretClaim
metadata:
  name: my-app-db-secret
  namespace: my-namespace
  labels:
    app.kubernetes.io/name: my-service   # required — sent as originService
spec:
  operatorNamespace: dbaas-system
  classifier:
    microserviceName: my-service   # required
    scope: service                 # required; "service" or "tenant"
    namespace: my-namespace        # the aggregator always stores this; keep it set
    # tenantId: my-tenant          # only meaningful when scope=tenant
    # customKeys:                  # optional adapter-specific identifiers, nested under "customKeys" on the wire
    #   logicalDBName: payments
    # extraKeys:                   # optional arbitrary identity fields, flattened to the classifier top level
    #   region: eu
  type: postgresql                 # required
  # userRole: admin                # optional; permission level of the returned credentials
  secretName: my-app-db-secret     # required; name of the Secret to create/update
```

**`spec.classifier`** — identifies the database in dbaas-aggregator. Same structure and semantics as
[InternalDatabase](#internaldatabase-resource-fields).

**Top-level spec fields:**

| Field | Required | Mutable | Description |
|-------|:--------:|:-------:|-------------|
| `spec.classifier` | Yes | **No** | Database identity in dbaas-aggregator. Immutable after creation (CEL `self == oldSelf`): repointing at a different database would write foreign credentials under the same Secret while `status` still references the original. Delete and recreate the CR to rebind. |
| `spec.type` | Yes | **No** | Database engine type (e.g., `postgresql`, `mongodb`). Immutable after creation. |
| `spec.userRole` | No | **No** | Role/permission level of the requested credentials (e.g., `admin`, `ro`, `rw`). When absent, the aggregator resolves the effective role through `DatabaseAccessPolicy` (`defaultRole`) and the global permission registry. Immutable **once set**: the CEL rule fires only when the field is present in both the old and the new object, so it can still be added if it was omitted at creation, or removed — only changing one set value to another is rejected. |
| `spec.secretName` | Yes | **No** | Name of the Kubernetes Secret the operator creates or updates in the CR's namespace. Immutable after creation — changing it would orphan the previously materialized Secret. Two `DatabaseSecretClaim` CRs in the same namespace must not target the same `secretName` (see the sibling-conflict tiebreak below). |

The materialized Secret is of type `Opaque` and stores two keys:

- **`connectionProperties.json`** — the aggregator's `connectionProperties` map serialized as JSON (credentials: `url`,
  `host`, `port`, `username`, `password`, `role`, …; the exact shape is adapter-specific).
- **`metadata.json`** — a self-describing descriptor `{ classifier, type, userRole, id, name, namespace, settings }`
  that lets a consumer match the Secret to a database request without calling the aggregator (used by dbaas-client when
  it reads connection properties from a mounted Secret instead of REST). The `classifier`, `type`, and `userRole` form
  the **match key**: `classifier` is the same canonical flat map the operator sends to the aggregator (`namespace`
  defaulted to `metadata.namespace`, empty optional fields omitted); `userRole` mirrors `spec.userRole` (the *requested*
  role, not the role the aggregator resolved at runtime) and is omitted when empty. The `id`, `name`, `namespace`, and
  `settings` fields mirror the aggregator's `DatabaseResponseV3SingleCP` so the client can reconstruct a full
  `LogicalDb` from the mounted Secret; they are descriptive only (not part of the match key) and omitted when empty.
  `id` in particular may be absent — the aggregator returns it best-effort on a by-classifier lookup.

The operator also stamps the labels `app.kubernetes.io/managed-by=dbaas-operator` and `app.kubernetes.io/name=<value
from the CR>`.

#### How DatabaseSecretClaim Works

A reconcile is triggered when any of the following happens:

- The CR is created.
- The CR spec changes (`metadata.generation` increments).
- Another `DatabaseSecretClaim` in the namespace sharing the same `spec.secretName` is created, deleted, or changed
  (sibling-conflict recovery).
- The rotation poller patches the `dbaas.netcracker.com/rotation-trigger` annotation (credential rotation — see
  [Rotation Polling](#rotation-polling) below).
- A safety-net re-poll: every successful reconcile re-enqueues itself after 1 hour to recover from any missed rotation
  event.

```text
CR created / spec changed / rotation-trigger annotation changed
        │
        ▼
  Operator assignment check (`spec.operatorNamespace`)
        │ assigned elsewhere → skip
        ▼
  phase = Processing
        │
        ▼
  Pre-flight validation (controller-side)
    classifier.namespace ≠ metadata.namespace?            ─▶ InvalidConfiguration (InvalidSpec)
    app.kubernetes.io/name label missing?                 ─▶ InvalidConfiguration (InvalidSpec)
    target Secret exists, owned by another resource?      ─▶ InvalidConfiguration (SecretConflict)
    another DatabaseSecretClaim claims the same secretName?    ─▶ InvalidConfiguration (SecretConflict)
        (older claimant wins — by creationTimestamp, UID on tie)
        │
        ▼
  POST /api/v3/dbaas/{ns}/databases/get-by-classifier/{type}
    (originService = app.kubernetes.io/name label, userRole = spec.userRole)
        │
        │   401              ─▶ BackingOff (Unauthorized)
        │   400/403/409/410/422 ▶ InvalidConfiguration (AggregatorRejected)
        │   404 + CORE-DBAAS-4006 ▶ BackingOff (DatabaseNotFound) — DB not yet provisioned
        │       └─ continuous streak > 10 min ▶ BackingOff (DatabaseNotFoundTimeout)
        │   404 (no TMF body) / 5xx / network ▶ BackingOff (AggregatorError)
        │   200 OK, empty connectionProperties ▶ BackingOff (EmptyConnectionProperties)
        │   200 OK with connectionProperties
        ▼
  Write target Secret (race-aware)
    Create → on AlreadyExists: re-fetch, owner-conflict check, then Update
        │
        ├─ Secret created                 ▶ Succeeded (SecretCreated)
        ├─ existing content identical      ▶ Succeeded (no write, no event)
        └─ existing content differs        ▶ Succeeded (SecretRotated, stamp lastRotatedAt)
        │
        ▼
  RequeueAfter 1h (safety-net re-poll)
```

Two behaviors are worth calling out:

- **Content-aware update** — on a rotation-triggered reconcile the operator compares the existing Secret's
  `connectionProperties.json` (and managed labels) against what it would write. If they already match, it skips the
  write entirely: no Secret update, no event, `lastRotatedAt` unchanged. This avoids needlessly waking every pod that
  mounts the Secret (the kubelet reloads mounted Secrets on change). Only a genuine content change is written and
  reported as `SecretRotated`.
- **Sibling-conflict tiebreak** — if two CRs in the namespace target the same `secretName`, the older one (by
  `creationTimestamp`, falling back to UID lexical order on a tie) wins and proceeds; the younger one moves to
  `SecretConflict`. The loser recovers automatically — without a spec change — once the winner is deleted or rebinds,
  because the controller watches sibling `DatabaseSecretClaim`s by `secretName`.

##### Rotation Polling

When a credential is rotated on the aggregator side, the operator picks it up by **polling** — it is not pushed. The
operator exposes no inbound endpoint; all dbaas-aggregator traffic is outbound (see [API Endpoints](#api-endpoints)). A
leader-only background loop (the **rotation poller**) periodically reads the aggregator's changed-databases feed and
stamps the rotation-trigger annotation on the affected `DatabaseSecretClaim` CRs, which wakes the reconciler.

| Aspect | Value |
|--------|-------|
| Feed | `GET /api/v3/dbaas/databases/changed?sinceTs=&sinceId=` — cluster-scoped, requires the `CLUSTER_OPERATOR` role. Returns the databases whose credentials changed after the cursor, plus the feed's high-water mark. |
| Cadence | Every `DBAAS_ROTATION_POLL_INTERVAL` (Go duration; default `30s`). |
| Leader-gated | Yes — the poller runs only on the elected leader, alongside the reconcilers. |
| Cursor | In-memory keyset cursor `(lastRotatedAt, id)`, seeded from the feed's high-water mark at startup (before the first poll) so rotations around leader acquisition are not skipped. Not persisted — correctness is backstopped by the startup reconcile and the 1-hour safety-net requeue. |
| Authentication | The operator's normal **outbound** credentials (Basic Auth or M2M token — see [Configuration Parameters](#configuration-parameters)); there is no separate inbound auth surface. |

Flow:

```text
[rotation poller — leader only, every DBAAS_ROTATION_POLL_INTERVAL]
        │  GET /api/v3/dbaas/databases/changed?sinceTs=&sinceId=   (outbound; Basic or M2M)
        ▼
   for each changed database in the returned page:
     resolve DatabaseSecretClaim CRs by (classifier, type) via the cache field index,
     scoped to classifier.namespace
        │
     patch dbaas.netcracker.com/rotation-trigger on each match
        │
   advance the in-memory cursor to the page's last (lastRotatedAt, id)
        ▼
[Kubernetes watch] ─ annotation change ─▶ reconciler runs ─▶ content-aware Secret update
```

The poller **does not** reconcile directly — it only patches an annotation; the change propagates through the Kubernetes
watch so the reconciler performs the actual Secret update. Because the poller and the reconcilers are both leader-gated,
the trigger and the reconcile run on the same instance.

##### Why the Lookup Ignores `userRole`

The cache index the poller queries is keyed by `(classifier, type)` **only** — it deliberately omits `userRole`. The
changed-databases feed signals that a *database's* credentials changed, without naming which role rotated; and even if
it did, the operator could not reliably map that role to specific CRs. This is a consequence of where role resolution
happens.

**The aggregator resolves the effective role at request time, not the operator.** When the operator calls
get-by-classifier, it sends the CR's `spec.userRole` verbatim (which may be empty) together with `originService` (the
`app.kubernetes.io/name` label). The aggregator then computes the *effective* role from inputs that live entirely on its
side and can change without the `DatabaseSecretClaim` CR ever being touched:

- **The `DatabaseAccessPolicy` for the microservice in that namespace.** For the requested `type` it carries a
  `defaultRole` and an optional `additionalRole` list:
  - When `spec.userRole` is empty, the effective role becomes the policy's `defaultRole` — which may be any role name
    the platform team configured, not necessarily `admin`.
  - When `spec.userRole` is set, it is accepted only if it appears in `additionalRole` (or equals `defaultRole`); the
    matched value, lower-cased, becomes the effective role.
- **Whether the request is first-party or cross-service.** If `originService` equals the classifier's
  `microserviceName`, the policy path above applies. If it is a *different* service (e.g., a CDC consumer reading
  another service's database), the aggregator instead matches `originService` against the policy's `services` grants,
  and the effective role is whichever granted role matches the request.
- **The global permission registry**, consulted as a fallback when no policy entry matches and global permissions are
  not disabled — again defaulting `userRole` to `admin` only when nothing else resolves.

(The aggregator-side logic is `DatabaseRolesService.getSupportRole`.)

**Two consequences for the operator:**

1. The same `spec.userRole` on two CRs can resolve to two different effective roles, and an empty `spec.userRole` can
   resolve to *anything* the policy dictates. There is no static, CR-local function from `spec.userRole` to the
   aggregator's effective role.
2. A `DatabaseAccessPolicy` edit changes the effective role of existing `DatabaseSecretClaim` CRs **without** changing
   those CRs — so any operator-side mapping would have to be invalidated and recomputed every time a policy changes,
   duplicating the aggregator's resolution and racing its cache.

Matching a specific rotated role to the affected CRs would require the operator to replicate all of the above. Rather
than do that, **the poller wakes every `DatabaseSecretClaim` that shares the changed database's `(classifier, type)`,
regardless of role.** Each woken CR then re-fetches its own credentials through get-by-classifier — where the aggregator
performs the authoritative role resolution — and the [content-aware update](#how-databasesecretclaim-works) writes
nothing when the returned credentials are unchanged. So CRs bound to a role other than the one that rotated perform a
cheap no-op; only the CR(s) whose effective role actually changed get a Secret write and a `SecretRotated` event.

The over-fetch is bounded and cheap: a classifier is typically referenced by 1–3 `DatabaseSecretClaim` CRs (one per
role), each costing one get-by-classifier round-trip and no Secret churn on a no-op. Trading a couple of redundant reads
for not reimplementing — and not having to keep coherent — the aggregator's role-resolution rules is the right balance.

> Anything a poll misses — e.g. a rotation that commits with an out-of-order timestamp below the advanced cursor — is
> caught by the startup full reconcile (on start / leader failover) and the 1-hour per-CR safety-net requeue.

#### DatabaseSecretClaim Status Reference

Shared phases, conditions, reasons, and diagnostic rules are described in
[Common Status Model](#common-status-model). `Succeeded` here means the target Secret is present and
current. Two phase behaviors are specific to this kind:

| Phase | Kind-specific behavior |
|-------|------------------------|
| `BackingOff` | `Unauthorized` and `AggregatorError` retry with [exponential backoff](#reconcile-backoff), but `DatabaseNotFound`, `DatabaseNotFoundTimeout`, and `EmptyConnectionProperties` re-poll at a **fixed 5-second interval** that never widens. |
| `Processing` (stuck) | A Kubernetes API error while reading or writing the target Secret — including `forbidden` from missing [namespaced Secret RBAC](#secret-access-namespaced) — returns before any condition is written. The CR keeps `phase: Processing` with no `Ready` condition and no event; the reconcile is retried with exponential backoff. Check the operator log. |

**`status.firstNotFoundAt`** — timestamp of the first `DatabaseNotFound` (404) response in the current streak. Set on
the first 404, cleared on any successful aggregator response. Used to detect a CR that has been waiting too long for its
database to appear (e.g., a typo in `spec.classifier`): after a fixed timeout the Ready reason switches to
`DatabaseNotFoundTimeout` and per-cycle Warning events stop, while polling continues so the CR self-heals if the
database eventually appears.

**`status.lastRotatedAt`** — timestamp of the most recent connection-properties change written to the target Secret.
Advanced only when the Secret bytes actually change (rotation or first fill of an adopted Secret); no-op reconciles and
the initial creation do **not** advance it.

**Kind-specific reasons** (in addition to the [shared vocabulary](#common-status-model)):

| Reason | Applied to | Meaning |
|--------|-----------|---------|
| `SecretCreated` | `Ready=True` | Secret present and current — initial creation or recreation after a deletion race |
| `SecretRotated` | `Ready=True` | The Secret's content was just changed (credential rotation or first fill of an adopted Secret) |
| `SecretUpToDate` | `Ready=True` | Steady-state confirmation — the Secret already matched the desired content (no-op), or a metadata/label backfill rewrote it without a credential change. No event is emitted and `lastRotatedAt` is not advanced |
| `SecretConflict` | `Ready=False`, `Stalled=True` | The target Secret is owned by another resource, or another `DatabaseSecretClaim` claims the same `secretName` |
| `EmptyConnectionProperties` | `Ready=False`, `Stalled=False` | Aggregator returned `200` with an empty `connectionProperties` map — treated as transient and retried |
| `DatabaseNotFound` | `Ready=False`, `Stalled=False` | Aggregator returned `404`/`CORE-DBAAS-4006` — the database is not yet registered; retried |
| `DatabaseNotFoundTimeout` | `Ready=False`, `Stalled=False` | The `DatabaseNotFound` streak exceeded the timeout (≈10 min) — polling continues but the per-cycle Warning events stop; likely a wrong classifier |

For this kind `InvalidSpec` covers a `classifier.namespace` mismatch or a missing `app.kubernetes.io/name`
label, and `AggregatorError` also covers a `404` without a TMF body (blue-green: no active namespace).

**Full state matrix:**

| Scenario | `phase` | `Ready` | `Reason` | `Stalled` |
|----------|---------|:-------:|----------|:---------:|
| Missing label / classifier.namespace mismatch | `InvalidConfiguration` | `False` | `InvalidSpec` | `True` |
| Target Secret owned by another resource | `InvalidConfiguration` | `False` | `SecretConflict` | `True` |
| Sibling claims same secretName (younger loses) | `InvalidConfiguration` | `False` | `SecretConflict` | `True` |
| get-by-classifier → 401 | `BackingOff` | `False` | `Unauthorized` | `False` |
| get-by-classifier → 400 / 403 / 409 / 410 / 422 | `InvalidConfiguration` | `False` | `AggregatorRejected` | `True` |
| get-by-classifier → 404 / CORE-DBAAS-4006 | `BackingOff` | `False` | `DatabaseNotFound` | `False` |
| DatabaseNotFound streak > 10 min | `BackingOff` | `False` | `DatabaseNotFoundTimeout` | `False` |
| get-by-classifier → 5xx / network / 404 (no TMF) | `BackingOff` | `False` | `AggregatorError` | `False` |
| 200 OK, empty connectionProperties | `BackingOff` | `False` | `EmptyConnectionProperties` | `False` |
| Secret created | `Succeeded` | `True` | `SecretCreated` | `False` |
| Secret content unchanged (no-op) | `Succeeded` | `True` | `SecretUpToDate` | `False` |
| Secret metadata/label backfill (no credential change) | `Succeeded` | `True` | `SecretUpToDate` | `False` |
| Secret content changed (rotation) | `Succeeded` | `True` | `SecretRotated` | `False` |

**Diagnostic rules** — in addition to the [shared rules](#common-status-model):

- **`Stalled=True`** — besides a spec error, this also covers a conflicting sibling claim or a pre-existing
  Secret owned by something else.
- **`Stalled=False` + `Ready=False`** — note the two retry regimes above: the
  not-found family re-polls every 5 seconds indefinitely rather than backing off. A persistent `DatabaseNotFound`
  usually means the `InternalDatabase` for this classifier has not provisioned yet — or the classifier is wrong (watch
  for `DatabaseNotFoundTimeout`).
- **`status.lastRotatedAt`** — when this was last advanced tells you when credentials last actually changed.
- **Request correlation** — unlike the other kinds, the `DatabaseSecretClaim` reconciler does **not** write
  `status.lastRequestId`. Correlate through the `requestId=` suffix carried by this CR's Kubernetes Events and by the
  operator log lines for the reconcile.

#### DatabaseSecretClaim Usage Examples

**Materialize credentials for an existing database:**

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: DatabaseSecretClaim
metadata:
  name: my-app-db-secret
  namespace: my-namespace
  labels:
    app.kubernetes.io/name: my-service
spec:
  operatorNamespace: dbaas-system
  classifier:
    microserviceName: my-service
    scope: service
    namespace: my-namespace
  type: postgresql
  userRole: admin
  secretName: my-app-db-secret
```

```bash
kubectl apply -f databasesecretclaim.yaml

# Watch until the Secret is materialized
kubectl get databasesecretclaim my-app-db-secret -n my-namespace -w
# NAME               PHASE       READY   TYPE         AGE
# my-app-db-secret   Succeeded   True    postgresql   5s

# Inspect the materialized Secret
kubectl get secret my-app-db-secret -n my-namespace -o jsonpath='{.data.connectionProperties\.json}' | base64 -d | jq .

# See when credentials were last rotated (empty until the first rotation)
kubectl get databasesecretclaim my-app-db-secret -n my-namespace -o jsonpath='{.status.lastRotatedAt}'

# Check conditions for the human-readable error message
kubectl get databasesecretclaim my-app-db-secret -n my-namespace -o jsonpath='{.status.conditions}' | jq .

# Correlate with aggregator logs through the requestId carried in the CR's events
kubectl get events -n my-namespace --field-selector involvedObject.name=my-app-db-secret
```

---

## Kubernetes Events

The operator emits Kubernetes Events on reconcile outcomes when `K8S_EVENTS_ENABLED=true`. With the default
`false` every recorder is a no-op, so none of the events below appear in `kubectl describe`.

**Normal**

| Reason | Emitted when |
|--------|--------------|
| `DatabaseRegistered` | An `ExternalDatabase` was registered with dbaas-aggregator. |
| `PolicyApplied` | A `DatabaseAccessPolicy` was applied. |
| `ProvisioningStarted` | An `InternalDatabase` apply returned `202 Accepted`. |
| `DatabaseProvisioned` | An `InternalDatabase` apply returned `200 OK`, or its async operation reached `COMPLETED`. |
| `BalancingRuleApplied` | Any of the three balancing-rule kinds applied its rules. |
| `SecretCreated` | A `DatabaseSecretClaim` created the target Secret, or recreated it after a deletion race. |
| `SecretRotated` | A `DatabaseSecretClaim` wrote changed credentials (`connectionProperties.json` differs). |

**Warning**

| Reason | Emitted when |
|--------|--------------|
| `InvalidSpec` | Any controller-side pre-flight validation failed. |
| `SecretError` | An `ExternalDatabase` credential Secret could not be read. |
| `Unauthorized` | The aggregator returned `401`, on submit or on an `InternalDatabase` poll. |
| `AggregatorRejected` | The aggregator returned `400`, `403`, `409`, `410`, or `422`, or an `InternalDatabase` operation reported `FAILED`. |
| `AggregatorError` | The aggregator returned `5xx`, the call failed at the network level, an `InternalDatabase` poll returned `404`, or a balancing-rule cleanup call failed. |
| `OperationTerminated` | An `InternalDatabase` operation reported `TERMINATED`; the operator resubmits. |
| `SecretConflict` | A `DatabaseSecretClaim` found the target Secret owned by another resource, or lost the sibling tiebreak. |
| `EmptyConnectionProperties` | The aggregator returned `200 OK` with an empty connection-properties map. |
| `DatabaseNotFound` | The aggregator returned `404` with `CORE-DBAAS-4006`; emitted once per poll cycle. |
| `DatabaseNotFoundTimeout` | Emitted once, when a `DatabaseNotFound` streak crosses 10 minutes. |

**Condition-only reasons** — these appear in `status.conditions` but are never emitted as events:
`Succeeded` and `SecretUpToDate`.

---

## Configuration Parameters

The operator is configured from three places, which this section keeps separate because they are set
differently: **Helm values** (rendered into the Deployment by the chart), **environment variables** read
directly by the binary, and **startup flags** passed as container `args`.

**Service parameters** (Helm values) — affect operator runtime behavior:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `DBAAS_OPERATOR_ENABLED` | boolean | `false` | When `false`, no operator resources are created by the Helm chart (Deployment, RBAC, CRDs, and monitoring objects are all skipped); only a placeholder `<SERVICE_NAME>-stub` ConfigMap is rendered. Must be set to `true` to deploy the operator. **The CRDs ship as chart templates**, so setting this back to `false` on an existing release deletes them along with every CR they define. |
| `LEADER_ELECT` | boolean | `true` | Enables leader election; a truthy value makes the chart append the `--leader-elect` flag. Required when running more than one replica to ensure only one active instance processes resources at a time. The binary's own default is `false` — see [Startup flags](#startup-flags). |
| `K8S_EVENTS_ENABLED` | boolean | `false` | When `true`, the operator emits Kubernetes Events on reconcile outcomes (visible in `kubectl describe`). Requires additional RBAC (`create`, `patch` on `core/events`). |
| `KUBERNETES_M2M_ENABLED` | boolean | `false` | Selects how the operator authenticates to dbaas-aggregator; **must match the aggregator's own `KUBERNETES_M2M_ENABLED`**. `false` (default): HTTP Basic Auth, with credentials read from `users.json` in the aggregator-created `dbaas-security-configuration-secret`, mounted at `/etc/dbaas/security` (see [Authentication](#authentication-basic-auth-or-m2m-token)). `true`: Kubernetes projected service-account token (Bearer / M2M). The aggregator rejects Bearer tokens outright when its M2M is disabled, so a mismatch fails every call. |
| `DBAAS_ROTATION_POLL_INTERVAL` | string | `""` (→ `30s`) | Poll period (Go duration, e.g. `15s`, `1m`) for the aggregator's changed-databases feed used to propagate `DatabaseSecretClaim` credential rotations. Empty uses the operator's built-in default (`30s`); a value that is unparseable or not positive is logged and ignored, and the default applies. |
| `DBAAS_EXTERNAL_DATABASE_RESYNC_INTERVAL` | string | `""` (→ `10m`) | Resync period (Go duration, e.g. `30s`, `1m`) for `ExternalDatabase` CRs. The operator does not watch Secrets, so a referenced credential Secret change is picked up on the next resync rather than instantly. Empty uses the operator's built-in default (`10m`); a value that is unparseable or not positive is logged and ignored, and the default applies. |
| `LOG_LEVEL` | string | `info` | Log verbosity. Allowed values: `debug`, `info`, `warn`, `error`, `fatal`. Per-package overrides (`LOGGING_LEVEL_<PACKAGE>`, `LOG_LEVEL_PACKAGE_<PACKAGE>`) and `LOGGING_LEVEL_ROOT` take precedence over this value. |
| `restrictedEnvironment` | boolean | `false` | When `true`, the Helm chart does not create `ClusterRole` and `ClusterRoleBinding` (which must be applied manually). The namespace-scoped `Role` and `RoleBinding` are always created. |
| `MONITORING_ENABLED` | boolean | `true` | When `true`, creates a `PodMonitor` for Prometheus scraping and imports Grafana dashboards. Because it defaults to `true`, enabling the operator also creates both objects — set it to `false` if the `monitoring.coreos.com` and `integreatly.org` CRDs are not installed, otherwise the release fails to apply. |

**Environment variables read by the binary** — these are not Helm values; the chart either injects them
or leaves them unset:

| Variable | Source | Default | Description |
|----------|--------|---------|-------------|
| `CLOUD_NAMESPACE` | Injected by the chart from `metadata.namespace` (downward API) | none | **Required.** The operator logs an error and exits at startup if it is unset. Defines which managed CRs are eligible (`spec.operatorNamespace == CLOUD_NAMESPACE`). |
| `DBAAS_AGGREGATOR_URL` | Not exposed by the chart | `http://dbaas-aggregator:8080` | Base URL of the dbaas-aggregator API. Override only when the aggregator is not reachable at the default in-cluster service address (for example, cross-cluster deployments). The chart has no value for it — `--set DBAAS_AGGREGATOR_URL=...` does nothing; patch the Deployment `env` block instead. |

**Deployment parameters** — control pod scheduling and resources:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `NAMESPACE` | string | `default` | Namespace the chart renders into. Load-bearing beyond naming: the `ClusterRoleBinding` subject and the `ClusterRoleBinding` name are built from it, so it must be set explicitly (`--namespace` alone is not read by these templates). |
| `IMAGE_REPOSITORY` / `TAG` | string | env-specific | Operator image and tag. |
| `PAAS_PLATFORM` | string | `KUBERNETES` | Target platform. Allowed values: `KUBERNETES`, `OPENSHIFT`. Controls security-context settings and, together with `PAAS_VERSION`, the `HorizontalPodAutoscaler` API version. |
| `REPLICAS` | integer | `1` | Number of operator pod replicas. Set `LEADER_ELECT=true` when using more than one. |
| `CPU_REQUEST` / `CPU_LIMIT` | string | env-specific | Pod CPU resource requests and limits. |
| `MEMORY_REQUEST` / `MEMORY_LIMIT` | string | env-specific | Pod memory resource requests and limits. |
| `NODE_SELECTOR_DBAAS_KEY` | string | `region` | Node selector label key used to schedule the operator pod. |
| `REGION_DBAAS` | string | `database` | Node selector label value used to schedule the operator pod. |
| `DEPLOYMENT_STRATEGY_TYPE` | string | — | Deployment update strategy. Allowed values: `recreate`, `best_effort_controlled_rollout`, `ramped_slow_rollout`, `custom_rollout`. Defaults to standard RollingUpdate (`25%/25%`) when not set. |
| `LIVENESS_PROBE_INITIAL_DELAY_SECONDS` | integer | `15` | Seconds to wait before the first liveness probe check. |
| `READONLY_CONTAINER_FILE_SYSTEM_ENABLED` | boolean | `true` | Mounts the container filesystem as read-only (Kubernetes only). |
| `HPA_ENABLED` | boolean | `false` | Enables Horizontal Pod Autoscaler. |
| `HPA_MIN_REPLICAS` | integer | — | Minimum number of replicas for HPA. |
| `HPA_MAX_REPLICAS` | integer | — | Maximum number of replicas for HPA. |
| `HPA_AVG_CPU_UTILIZATION_TARGET_PERCENT` | integer | — | Target average CPU utilization (%) for HPA scale decisions. |

See [`values.schema.json`](../../helm-templates/dbaas-operator/values.schema.json) for the full set of chart
values, including the topology, deployment-strategy, and `HPA_SCALING_*` knobs not listed above.

### Ports and Probes

| Port | Container port name | Serves |
|------|---------------------|--------|
| `8080` | `metrics` | Prometheus `/metrics` (plain HTTP, no auth) — see [DBaaS Operator Metrics](../monitoring/DBaaS%20Operator%20Metrics.md) |
| `8081` | `web` | Health probes: liveness `GET /healthz`, readiness `GET /readyz` |

The liveness probe starts after `LIVENESS_PROBE_INITIAL_DELAY_SECONDS` (default `15`) and runs every 20 s; the
readiness probe starts after 5 s and runs every 10 s. Both addresses are configurable through
[startup flags](#startup-flags).

### Startup Flags

The chart hard-codes the container `args`; none of these flags has a Helm value. To change one, edit the
Deployment template or patch the rendered Deployment.

| Flag | Binary default | Description |
|------|----------------|-------------|
| `--http-bind-address` | `:8080` | Address of the HTTP server that hosts `/metrics`. |
| `--health-probe-bind-address` | `:8081` | Address of the health-probe endpoints (`/healthz`, `/readyz`). |
| `--leader-elect` | `false` | Enables leader election. The chart appends this flag when `LEADER_ELECT` is truthy, so a chart install has it on by default. |
| `--backoff-base-delay` | `1s` | Initial retry delay after the first failure — see [Reconcile Backoff](#reconcile-backoff). |
| `--backoff-max-delay` | `5m` | Maximum retry delay cap — see [Reconcile Backoff](#reconcile-backoff). |

### Reconcile Backoff

When a reconcile attempt fails with a transient error (Secret not found, aggregator 5xx, network error, etc.), the
controller does not retry immediately. It uses an **exponential backoff** rate limiter: the delay doubles on each
consecutive failure for the same object, up to a configured maximum.

This behavior is controlled by the two backoff [startup flags](#startup-flags): `--backoff-base-delay`
(default `1s`) doubles on each consecutive failure for the same object, up to `--backoff-max-delay`
(default `5m`). The chart exposes no values for them, so tuning means editing the Deployment `args`.

**Backoff does not cover every retry.** Several paths requeue after a fixed interval with no error, which
bypasses the rate limiter entirely:

| Interval | Path |
|----------|------|
| 5 s | `InternalDatabase` async poll and `TERMINATED` resubmit; `DatabaseSecretClaim` `DatabaseNotFound`, `DatabaseNotFoundTimeout`, and `EmptyConnectionProperties` |
| 1 s | `DatabaseSecretClaim` Secret create/update race reconverge |
| 10 min | `ExternalDatabase` resync (`DBAAS_EXTERNAL_DATABASE_RESYNC_INTERVAL`) |
| 1 h | `DatabaseSecretClaim` rotation safety net |

**Example sequence for a single object:**

| Failure | Delay before next attempt |
|---------|--------------------------|
| 1st | 1s |
| 2nd | 2s |
| 3rd | 4s |
| 4th | 8s |
| … | … (doubles each time) |
| N-th | up to 5m (cap) |

The counter is reset when a reconcile succeeds — the next failure starts from `--backoff-base-delay` again.

**To tune** (example Deployment args):

```yaml
args:
  - --health-probe-bind-address=:8081
  - --http-bind-address=:8080
  - --leader-elect
  - --backoff-base-delay=5s
  - --backoff-max-delay=10m
```
