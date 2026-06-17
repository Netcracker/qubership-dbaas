# DBaaS Operator

## Table of Contents

- [Overview](#overview)
- [High-Level Architecture](#high-level-architecture)
- [API Endpoints](#api-endpoints)
  - [ExternalDatabase Registration Endpoint](#externaldatabase-registration-endpoint)
  - [DatabaseAccessPolicy Apply Endpoint](#databaseaccesspolicy-apply-endpoint)
- [Authentication: Projected Service Account Token](#authentication-projected-service-account-token)
- [RBAC and Required Permissions](#rbac-and-required-permissions)
  - [Default Installation](#default-installation)
  - [Restricted Environment](#restricted-environment)
- [Custom Resources](#custom-resources)
  - [NamespaceBinding](#namespacebinding)
    - [Resource Fields](#namespacebinding-resource-fields)
    - [How It Works](#how-namespacebinding-works)
    - [Finalizer Protection](#finalizer-protection)
    - [Usage Examples](#namespacebinding-usage-examples)
  - [ExternalDatabase](#externaldatabase)
    - [Resource Fields](#externaldatabase-resource-fields)
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
    - [Status Reference](#internaldatabase-status-reference)
    - [Usage Examples](#internaldatabase-usage-examples)
  - [Balancing Rule CRDs](#balancing-rule-crds)
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
- [Configuration Parameters](#configuration-parameters)
  - [Reconcile Backoff](#reconcile-backoff)

---

## Overview

DBaaS Operator is a Kubernetes operator that integrates with dbaas-aggregator. It runs cluster-wide and manages the following custom resources:

| Custom Resource | API Group | Scope | Purpose |
|-----------------|-----------|-------|---------|
| `NamespaceBinding` | `dbaas.netcracker.com/v1` | Namespaced | Declares that a namespace is managed by this operator instance |
| `ExternalDatabase` | `dbaas.netcracker.com/v1` | Namespaced | Registers a pre-existing database with dbaas-aggregator |
| `DatabaseAccessPolicy` | `dbaas.netcracker.com/v1` | Namespaced | Declares database role assignments for microservices in a namespace |
| `InternalDatabase` | `dbaas.netcracker.com/v1` | Namespaced | Declares a logical database that dbaas-aggregator should provision and manage |
| `MicroserviceBalancingRule` | `dbaas.netcracker.com/v1` | Namespaced | Declares per-microservice physical database placement rules in a business namespace |
| `NamespaceBalancingRule` | `dbaas.netcracker.com/v1` | Namespaced | Declares per-namespace physical database placement rules in a business namespace |
| `PermanentBalancingRule` | `dbaas.netcracker.com/v1` | Namespaced | Declares permanent placement rules from the operator namespace, targeting owned business namespaces |

---

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Kubernetes Cluster                    │
│                                                         │
│  ┌──────────────────────────────────────────────────┐  │
│  │               dbaas-system namespace              │  │
│  │                                                  │  │
│  │  ┌──────────────────────────────────────────┐   │  │
│  │  │           dbaas-operator Pod             │   │  │
│  │  │                                          │   │  │
│  │  │  NamespaceBinding controller             │   │  │
│  │  │  ExternalDatabase controller             │   │  │
│  │  │  DatabaseAccessPolicy controller                     │   │  │
│  │  │  InternalDatabase controller          │   │  │
│  │  └──────────────────────────────────────────┘   │  │
│  └──────────────────────────────────────────────────┘  │
│                          │                              │
│         watches (cluster-wide)                          │
│                          │                              │
│  ┌───────────────────────┼──────────────────────────┐  │
│  │     app-namespace     │                          │  │
│  │                       ▼                          │  │
│  │  NamespaceBinding    ── ownership check          │  │
│  │  ExternalDatabase    ── reconcile ─────────────── ┼──┼──▶ dbaas-aggregator
│  │  DatabaseAccessPolicy            ── reconcile ─────────────── ┼──┤
│  │  InternalDatabase ── reconcile ─────────────── ┼──┤
│  │  Secret (credentials)                            │  │
│  └──────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

**Key design decisions:**

- The operator runs **cluster-wide** — no static `--watch-namespaces` list.
- Namespace ownership is determined dynamically via `NamespaceBinding` CRs.
- Workload CRs in namespaces without a matching `NamespaceBinding` are silently skipped.
- Credentials for `ExternalDatabase` are read from Kubernetes Secrets at reconcile time.
- The operator watches referenced Secrets and automatically reconciles affected `ExternalDatabase` CRs when their credentials rotate — no manual spec change required.
- Authentication to dbaas-aggregator uses a projected service account token (rotated automatically by Kubernetes).
- Resource-identity fields on all workload CRs are immutable after creation (enforced by CRD CEL rules) — to retarget a CR at a different database, microservice, or operator instance, delete and recreate it. See the per-resource sections for the exact set of immutable fields.

---

## API Endpoints

The operator calls the following dbaas-aggregator endpoints:

| Method | URL | Used by | Purpose |
|--------|-----|---------|---------|
| `PUT` | `/api/v3/dbaas/{namespace}/databases/registration/externally_manageable` | `ExternalDatabase` reconciler | Register or update an externally managed database |
| `POST` | `/api/declarations/v1/apply` | `DatabaseAccessPolicy` and `InternalDatabase` reconcilers | Apply a declarative database role policy or database declaration |
| `GET` | `/api/declarations/v1/operation/{trackingId}/status` | `InternalDatabase` reconciler | Poll the status of an asynchronous provisioning operation |
| `PUT` | `/api/v3/dbaas/{namespace}/physical_databases/rules/onMicroservices` | `MicroserviceBalancingRule` reconciler | Apply the microservice balancing rule set for a business namespace |
| `PUT` | `/api/v3/dbaas/{namespace}/physical_databases/balancing/rules/{ruleName}` | `NamespaceBalancingRule` reconciler | Create or update one named namespace balancing rule |
| `PUT` | `/api/v3/dbaas/balancing/rules/permanent` | `PermanentBalancingRule` reconciler | Apply permanent balancing rules for target namespaces |
| `DELETE` | `/api/v3/dbaas/balancing/rules/permanent` | `PermanentBalancingRule` reconciler | Remove previously applied permanent balancing rules during update or deletion |

### ExternalDatabase Registration Endpoint

**`PUT /api/v3/dbaas/{namespace}/databases/registration/externally_manageable`**

The `{namespace}` segment is taken from `spec.classifier.namespace` if that field is set; otherwise from `metadata.namespace`.

The operator always sends `updateConnectionProperties: true`, which means the request creates the database registration if it does not exist, or updates the connection properties if it does.

**Possible responses and operator behavior:**

| HTTP Code | Situation | Operator outcome |
|-----------|-----------|-----------------|
| `200 OK` / `201 Created` | Successfully registered or updated | `Succeeded` — `Ready=True` |
| `400` | Invalid classifier (missing required fields) | `InvalidConfiguration` — `Ready=False`, `Stalled=True`, reason `AggregatorRejected` |
| `401` | Missing or invalid auth token | `BackingOff` — retried, reason `Unauthorized` |
| `403` | `tenantId` in classifier does not match JWT | `InvalidConfiguration` — `Ready=False`, `Stalled=True`, reason `AggregatorRejected` |
| `409` | Database exists but is not externally managed | `InvalidConfiguration` — `Ready=False`, `Stalled=True`, reason `AggregatorRejected` |
| `410` / `422` | Aggregator-side spec rejection (rare for this endpoint, but handled the same as 400/403/409) | `InvalidConfiguration` — `Ready=False`, `Stalled=True`, reason `AggregatorRejected` |
| `5xx` | Aggregator error | `BackingOff` — retried, reason `AggregatorError` |
| Network error | Aggregator unreachable | `BackingOff` — retried, reason `AggregatorError` |

### DatabaseAccessPolicy Apply Endpoint

**`POST /api/declarations/v1/apply`**

The operator posts a declarative payload with `subKind: DbPolicy`. The `microserviceName` from the CR spec is sent in the payload `metadata`, not in the spec body.

**Possible responses and operator behavior:**

| HTTP Code | Situation | Operator outcome |
|-----------|-----------|-----------------|
| `200 OK` | Policy applied successfully | `Succeeded` — `Ready=True`, reason `PolicyApplied` |
| `400` / `403` / `409` / `410` / `422` | Invalid or permanently rejected policy spec | `InvalidConfiguration` — `Ready=False`, `Stalled=True`, reason `AggregatorRejected` |
| `401` | Missing or invalid auth token | `BackingOff` — retried, reason `Unauthorized` |
| `5xx` | Aggregator error | `BackingOff` — retried, reason `AggregatorError` |
| Network error | Aggregator unreachable | `BackingOff` — retried, reason `AggregatorError` |

---

## Authentication: Projected Service Account Token

The operator authenticates to dbaas-aggregator using a **Kubernetes projected service account token** with `audience=dbaas`.

```
Per-request authentication flow:

  Reconcile loop
       │
       ▼
  Read /var/run/secrets/tokens/dbaas/token   ← Kubernetes rotates this automatically
       │
       ▼
  Authorization: Bearer <token>  →  dbaas-aggregator
```

**How it works:**

- The token is mounted into the operator pod via a `projected` volume with `audience: dbaas` and `expirationSeconds: 600`.
- Kubernetes rotates the token automatically before it expires (roughly every 10 minutes for a 600-second lifetime).
- The token is read from disk on **every** outbound HTTP request — there is no client-side caching. Token rotation is therefore fully transparent with no pod restart required.

**Volume configuration (from Deployment):**

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

**Requirement on the dbaas-aggregator side:** the aggregator must be configured to accept tokens with `audience=dbaas` and validate them against the Kubernetes token review API.

> **No inbound endpoint** — the operator does not expose any authenticated HTTP endpoint; all dbaas-aggregator traffic is **outbound** (see [API Endpoints](#api-endpoints)). Credential rotations are picked up by **polling** the aggregator, not pushed to the operator — see [Rotation Polling](#rotation-polling). The projected token shown above is used only in M2M mode (`KUBERNETES_M2M_ENABLED=true`); in the default Basic Auth mode the operator authenticates with the `dbaas-operator` user instead.

---

## RBAC and Required Permissions

The operator needs a `ServiceAccount`, a `ClusterRole`, a `ClusterRoleBinding`, a namespace-scoped `Role`, and a `RoleBinding` to function correctly. By default the Helm chart creates all of these automatically. In environments where cluster-scoped resources cannot be created, set `restrictedEnvironment: true` — the chart will then create only the `ServiceAccount`, the namespace-scoped `Role`, and the `RoleBinding`, skipping the `ClusterRole`/`ClusterRoleBinding`, which must be applied manually using the manifests below.

### Default Installation

When `restrictedEnvironment: false` (the default), the chart creates:

| Resource | Name | Scope | Purpose |
|----------|------|-------|---------|
| `ServiceAccount` | `dbaas-operator` | Namespaced (operator namespace) | Pod identity |
| `ClusterRole` | `dbaas-operator` | Cluster-wide | Access to dbaas CRs and Secrets across all namespaces |
| `ClusterRoleBinding` | `dbaas-operator` | Cluster-wide | Binds `ClusterRole` to the `ServiceAccount` |
| `Role` | `dbaas-operator` | Namespaced (operator namespace) | Leader election leases and event recording |
| `RoleBinding` | `dbaas-operator` | Namespaced (operator namespace) | Binds `Role` to the `ServiceAccount` |

Only permissions that genuinely require cluster-wide access are in the `ClusterRole`. Leader election leases and Kubernetes Events are always written to the operator's own namespace, so they use a namespace-scoped `Role`.

### Restricted Environment

When `restrictedEnvironment: true`, only the `ServiceAccount`, `Role`, and `RoleBinding` are created by the chart. You must create the `ClusterRole` and `ClusterRoleBinding` manually before starting the operator.

#### Why cluster-scoped RBAC is needed

The operator runs cluster-wide and watches resources in all namespaces. Namespace-scoped `Role`/`RoleBinding` cannot grant access to resources across multiple namespaces, so a `ClusterRole` is required for dbaas CRs and Secrets.

Leader election leases and Kubernetes Events, however, are always created in the operator's own namespace — a namespace-scoped `Role` is sufficient and more secure.

#### Required ClusterRole

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: dbaas-operator
rules:
  # DatabaseAccessPolicy: the controller reads and watches CRs.
  # Status is written via the /status subresource.
  - apiGroups: ["dbaas.netcracker.com"]
    resources: ["databaseaccesspolicies"]
    verbs: ["get", "list", "watch"]

  - apiGroups: ["dbaas.netcracker.com"]
    resources: ["databaseaccesspolicies/status"]
    verbs: ["get", "update", "patch"]

  # InternalDatabase: the controller reads and watches CRs.
  # Status is written via the /status subresource.
  - apiGroups: ["dbaas.netcracker.com"]
    resources: ["internaldatabases"]
    verbs: ["get", "list", "watch"]

  - apiGroups: ["dbaas.netcracker.com"]
    resources: ["internaldatabases/status"]
    verbs: ["get", "update", "patch"]

  # ExternalDatabase: the controller only reads (Get/List) and watches CRs.
  # Status is written via the /status subresource — no write access to the main resource is needed.
  - apiGroups: ["dbaas.netcracker.com"]
    resources: ["externaldatabases"]
    verbs: ["get", "list", "watch"]

  - apiGroups: ["dbaas.netcracker.com"]
    resources: ["externaldatabases/status"]
    verbs: ["get", "update", "patch"]

  # DatabaseSecretClaim: the controller reads and watches CRs; patch on the main
  # resource is required for the rotation poller to stamp the
  # dbaas.netcracker.com/rotation-trigger annotation. Status is written via the
  # /status subresource.
  - apiGroups: ["dbaas.netcracker.com"]
    resources: ["databasesecretclaims"]
    verbs: ["get", "list", "watch", "patch"]

  - apiGroups: ["dbaas.netcracker.com"]
    resources: ["databasesecretclaims/status"]
    verbs: ["get", "update", "patch"]

  # NamespaceBinding: patch is required to add/remove the binding-protection finalizer (client.MergeFrom).
  # Kubernetes additionally checks update on /finalizers when metadata.finalizers changes during a patch.
  - apiGroups: ["dbaas.netcracker.com"]
    resources: ["namespacebindings"]
    verbs: ["get", "list", "watch", "patch"]

  - apiGroups: ["dbaas.netcracker.com"]
    resources: ["namespacebindings/finalizers"]
    verbs: ["update"]

  # Balancing rules: the controllers read and watch the singleton CRs.
  # Microservice and permanent rules use finalizers for aggregator-side cleanup.
  # Namespace rules do not use a finalizer until the aggregator exposes a delete API.
  - apiGroups: ["dbaas.netcracker.com"]
    resources: ["microservicebalancingrules"]
    verbs: ["get", "list", "watch", "patch"]

  - apiGroups: ["dbaas.netcracker.com"]
    resources: ["microservicebalancingrules/finalizers"]
    verbs: ["update"]

  - apiGroups: ["dbaas.netcracker.com"]
    resources: ["microservicebalancingrules/status"]
    verbs: ["get", "update", "patch"]

  - apiGroups: ["dbaas.netcracker.com"]
    resources: ["namespacebalancingrules"]
    verbs: ["get", "list", "watch"]

  - apiGroups: ["dbaas.netcracker.com"]
    resources: ["namespacebalancingrules/status"]
    verbs: ["get", "update", "patch"]

  - apiGroups: ["dbaas.netcracker.com"]
    resources: ["permanentbalancingrules"]
    verbs: ["get", "list", "watch", "patch"]

  - apiGroups: ["dbaas.netcracker.com"]
    resources: ["permanentbalancingrules/finalizers"]
    verbs: ["update"]

  - apiGroups: ["dbaas.netcracker.com"]
    resources: ["permanentbalancingrules/status"]
    verbs: ["get", "update", "patch"]

  # Read Secrets to resolve credentials referenced by ExternalDatabase CRs.
  # get is required to read Secret data during reconcile.
  # list and watch are required for the metadata-only Secret watch that triggers
  # automatic reconciliation when a referenced Secret changes (credential rotation).
  # Secrets: read credentials referenced by ExternalDatabase CRs and materialize
  # the target Secret for DatabaseSecretClaim CRs.
  # get reads Secret data during reconcile; list/watch back the metadata-only
  # Secret watch that triggers reconciliation on referenced-Secret changes;
  # create/update/patch write the DatabaseSecretClaim-managed Secret.
  # Only metadata (not data) is cached — Secret bodies are fetched on demand.
  - apiGroups: [""]
    resources: ["secrets"]
    verbs: ["get", "list", "watch", "create", "update", "patch"]
```

#### Required ClusterRoleBinding

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: dbaas-operator
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: dbaas-operator
subjects:
  - kind: ServiceAccount
    name: dbaas-operator
    namespace: <operator-namespace>   # replace with the namespace where the operator runs
```

#### Required Role

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: dbaas-operator
  namespace: <operator-namespace>     # replace with the namespace where the operator runs
rules:
  # Leader election — the lease object is always in the operator's own namespace
  - apiGroups: ["coordination.k8s.io"]
    resources: ["leases"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]

  # Kubernetes Events — required only when K8S_EVENTS_ENABLED=true
  # Remove this block if K8S_EVENTS_ENABLED=false
  - apiGroups: [""]
    resources: ["events"]
    verbs: ["create", "patch"]
```

#### Required RoleBinding

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: dbaas-operator
  namespace: <operator-namespace>     # replace with the namespace where the operator runs
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: dbaas-operator
subjects:
  - kind: ServiceAccount
    name: dbaas-operator
    namespace: <operator-namespace>   # replace with the namespace where the operator runs
```

#### Permission reference

**ClusterRole** (cluster-wide access):

| API group | Resource | Verbs | Why it is needed |
|-----------|----------|-------|-----------------|
| `dbaas.netcracker.com` | `databaseaccesspolicies` | `get`, `list`, `watch` | Watch and read CRs across all namespaces; status is written via `/status` subresource |
| `dbaas.netcracker.com` | `databaseaccesspolicies/status` | `get`, `update`, `patch` | Write reconcile outcome to `status.phase` and `status.conditions` |
| `dbaas.netcracker.com` | `internaldatabases` | `get`, `list`, `watch` | Watch and read CRs across all namespaces; status is written via `/status` subresource |
| `dbaas.netcracker.com` | `internaldatabases/status` | `get`, `update`, `patch` | Write reconcile outcome to `status.phase`, `status.conditions`, and `status.trackingID` |
| `dbaas.netcracker.com` | `externaldatabases` | `get`, `list`, `watch` | Watch and read CRs across all namespaces; status is written via `/status` subresource |
| `dbaas.netcracker.com` | `externaldatabases/status` | `get`, `update`, `patch` | Write reconcile outcome to `status.phase` and `status.conditions` |
| `dbaas.netcracker.com` | `databasesecretclaims` | `get`, `list`, `watch`, `patch` | Watch and read CRs; `patch` is required for the rotation poller to stamp the `dbaas.netcracker.com/rotation-trigger` annotation on matched CRs |
| `dbaas.netcracker.com` | `databasesecretclaims/status` | `get`, `update`, `patch` | Write reconcile outcome to `status.phase`, `status.conditions`, `status.lastRotatedAt`, and `status.firstNotFoundAt` |
| `dbaas.netcracker.com` | `namespacebindings` | `get`, `list`, `watch`, `patch` | Watch and read CRs; `patch` is required to add/remove the `binding-protection` finalizer via `client.MergeFrom` |
| `dbaas.netcracker.com` | `namespacebindings/finalizers` | `update` | Kubernetes additionally checks this permission when `metadata.finalizers` changes during a patch |
| `dbaas.netcracker.com` | `microservicebalancingrules` | `get`, `list`, `watch`, `patch` | Watch and read singleton microservice balancing rule CRs; `patch` is required to add/remove the cleanup finalizer |
| `dbaas.netcracker.com` | `microservicebalancingrules/finalizers` | `update` | Kubernetes additionally checks this permission when `metadata.finalizers` changes during a patch |
| `dbaas.netcracker.com` | `microservicebalancingrules/status` | `get`, `update`, `patch` | Write reconcile outcome and last-applied rule data |
| `dbaas.netcracker.com` | `namespacebalancingrules` | `get`, `list`, `watch` | Watch and read singleton namespace balancing rule CRs |
| `dbaas.netcracker.com` | `namespacebalancingrules/status` | `get`, `update`, `patch` | Write reconcile outcome and last-applied rule data |
| `dbaas.netcracker.com` | `permanentbalancingrules` | `get`, `list`, `watch`, `patch` | Watch and read singleton permanent balancing rule CRs; `patch` is required to add/remove the cleanup finalizer |
| `dbaas.netcracker.com` | `permanentbalancingrules/finalizers` | `update` | Kubernetes additionally checks this permission when `metadata.finalizers` changes during a patch |
| `dbaas.netcracker.com` | `permanentbalancingrules/status` | `get`, `update`, `patch` | Write reconcile outcome and last-applied rule data |
| `""` (core) | `secrets` | `get`, `list`, `watch` | `get`: read Secret data during reconcile. `list`/`watch`: metadata-only Secret watch that triggers automatic reconciliation when a referenced Secret changes (credential rotation). Secret bodies are not cached. |
| `""` (core) | `secrets` | `get`, `list`, `watch`, `create`, `update`, `patch` | `get`: read Secret data during reconcile. `list`/`watch`: metadata-only Secret watch that triggers automatic reconciliation when a referenced Secret changes (credential rotation). `create`/`update`/`patch`: materialize the Secret managed by a `DatabaseSecretClaim` CR. Secret bodies are not cached. |

**Role** (operator namespace only):

| API group | Resource | Verbs | Why it is needed |
|-----------|----------|-------|-----------------|
| `coordination.k8s.io` | `leases` | `get`, `list`, `watch`, `create`, `update`, `patch`, `delete` | Leader election lock (required when `LEADER_ELECT=true`) |
| `""` (core) | `events` | `create`, `patch` | Emit Kubernetes Events on reconcile outcomes (required when `K8S_EVENTS_ENABLED=true`) |

> **Note:** If you set `K8S_EVENTS_ENABLED=false` (the default), you may omit the `events` rule from the `Role`. If you set `LEADER_ELECT=false`, you may omit the `leases` rule, but this is only safe when running a single replica.

---

## Custom Resources

### NamespaceBinding

`NamespaceBinding` is a coordination resource that declares that a namespace belongs to a particular operator instance. It has no representation in dbaas-aggregator — it is a Kubernetes-only concept.

#### NamespaceBinding Resource Fields

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: NamespaceBinding
metadata:
  name: binding          # must always be "binding"
  namespace: my-namespace
spec:
  operatorNamespace: dbaas-system   # namespace where the operator pod runs
```

| Field | Required | Mutable | Description |
|-------|:--------:|:-------:|-------------|
| `metadata.name` | Yes | — | Must always be `binding`. Enforced by CRD CEL validation. |
| `metadata.namespace` | Yes | — | The business namespace being claimed. |
| `spec.operatorNamespace` | Yes | No | Must match the operator pod's own namespace (`CLOUD_NAMESPACE`). Immutable after creation. |

**Constraints:**

- Only one `NamespaceBinding` is allowed per namespace. The name is fixed to `binding`.
- `spec.operatorNamespace` is immutable after creation (enforced by CEL: `self == oldSelf`).

#### How NamespaceBinding Works

The operator runs cluster-wide and watches all namespaces. Before reconciling any workload resource (`ExternalDatabase`, `DatabaseAccessPolicy`, `InternalDatabase`), it checks whether the resource's namespace is owned by this operator instance.

Ownership is determined by looking for a `NamespaceBinding` named `binding` in the same namespace and comparing `spec.operatorNamespace` with the operator's own `CLOUD_NAMESPACE` environment variable.

```
ExternalDatabase / DatabaseAccessPolicy / InternalDatabase reconcile triggered
         │
         ▼
  Look up NamespaceBinding "binding" in the same namespace
         │
         ├── Not found (Unbound) ──▶ Skip, requeue after 5 minutes
         │
         ├── Found, operatorNamespace ≠ CLOUD_NAMESPACE (Foreign) ──▶ Skip, no requeue
         │
         └── Found, operatorNamespace = CLOUD_NAMESPACE (Mine) ──▶ Proceed with reconcile
```

When a `NamespaceBinding` is created or updated, the operator automatically re-enqueues all workload CRs in that namespace — so existing `ExternalDatabase`, `DatabaseAccessPolicy`, and `InternalDatabase` objects are reconciled immediately without requiring a spec change.

| Cache state | Meaning | Operator action |
|-------------|---------|-----------------|
| `Unknown` | No cache entry yet (startup or transient) | Requeue after 30 seconds |
| `Unbound` | No `NamespaceBinding` in this namespace | Requeue after 5 minutes (safety net) |
| `Foreign` | Binding belongs to a different operator | Skip, no requeue |
| `Mine` | Binding matches this operator | Proceed with reconcile |

#### Finalizer Protection

When a `NamespaceBinding` is reconciled, the operator adds the finalizer:

```
platform.dbaas.netcracker.com/binding-protection
```

This finalizer prevents the `NamespaceBinding` from being deleted while workload resources still exist in the namespace, because deleting the binding would orphan those resources.

| Situation | Result |
|-----------|--------|
| Namespace still contains `ExternalDatabase`, `DatabaseAccessPolicy`, or `InternalDatabase` resources | Finalizer is kept; deletion is blocked; a `BindingBlocked` warning event is emitted |
| No blocking workload resources remain | Finalizer is removed; Kubernetes completes the deletion |

#### NamespaceBinding Usage Examples

**Claim a namespace for this operator instance:**

```bash
kubectl apply -f - <<EOF
apiVersion: dbaas.netcracker.com/v1
kind: NamespaceBinding
metadata:
  name: binding
  namespace: my-namespace
spec:
  operatorNamespace: dbaas-system
EOF
```

**Check that the operator has processed the binding:**

`NamespaceBinding` has no `status` field. For this resource, the presence of the finalizer is the single indicator that the operator has picked it up and is actively managing the namespace:

```bash
kubectl get namespacebinding binding -n my-namespace -o jsonpath='{.metadata.finalizers}'
# ["platform.dbaas.netcracker.com/binding-protection"]
```

If the finalizer is present, the operator owns the namespace and will reconcile workload resources (`ExternalDatabase`, `DatabaseAccessPolicy`) within it.

This is intentional. `NamespaceBinding` is a declaration of ownership, not a job or pipeline — its semantics are binary: either the operator has claimed the namespace or it has not. A `status` field would add complexity without real benefit, and stale status values would be misleading in edge cases (e.g., operator restart). The finalizer is sufficient and follows the established Kubernetes practice for simple ownership resources.

**Delete a binding (after removing all workload resources):**

```bash
# Remove all workload resources first
kubectl delete externaldatabase,databaseaccesspolicy,internaldatabase --all -n my-namespace

# Then delete the binding
kubectl delete namespacebinding binding -n my-namespace
```

---

### ExternalDatabase

`ExternalDatabase` registers a pre-existing database instance with dbaas-aggregator. The database must already exist in the DBMS — the operator does not provision it.

Short name: `dbedb`

`kubectl get dbedb` columns: `PHASE`, `TYPE`, `DBNAME`, `AGE`

#### ExternalDatabase Resource Fields

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: ExternalDatabase
metadata:
  name: my-postgres-external
  namespace: my-namespace
spec:
  classifier:
    microserviceName: my-service   # required, minLength: 1
    scope: service                 # required, minLength: 1; "service" or "tenant"
    namespace: my-namespace        # optional; if set, must equal metadata.namespace
    # tenantId: my-tenant          # required when scope=tenant
    # customKeys:                  # optional, adapter-specific identifiers
    #   logicalDBName: mydb        # string
    #   shardCount: 5              # number — preserved as JSON number on the wire
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
| `customKeys` | No | Adapter-specific identifiers (e.g. `logicalDBName`). Values can be any valid JSON type (string, number, boolean, nested object, array); not validated by the aggregator. See the mapping rules below. |

##### `customKeys` → aggregator wire mapping

The aggregator declares `ExternalDatabaseRequestV3.classifier` as
`SortedMap<String, Object>` and stores it as JSONB, so the wire format supports
any JSON value — including nested objects and arrays. The controller's
`classifierToFlatMap` builds the wire payload from `spec.classifier` like this:

1. **Structured fields first.** `microserviceName` and `scope` are always
   emitted at the top level of the classifier map. `namespace` and `tenantId`
   are emitted at the top level when set (empty strings are skipped).
2. **`customKeys` are flattened to the same top level.** Every entry in
   `customKeys` becomes a top-level key in the wire classifier — there is no
   nested `customKeys` envelope on the wire.
3. **Native JSON types are preserved.** A string stays a JSON string, a number
   stays a number, a boolean stays a boolean, a nested object/array is sent
   as-is. The controller does not stringify non-string values.
4. **Structured fields win on key collision.** If a user puts
   `customKeys.microserviceName` (or `scope` / `namespace` / `tenantId`) into
   the spec, the explicit structured field always wins. This prevents
   accidentally overriding the identity fields from `customKeys`.

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
  "logicalDBName": "configs",
  "shardCount": 5,
  "region": { "name": "us-east", "az": "a" }
}
```

> The aggregator sorts classifier keys alphabetically for identity comparison.
> Two classifiers with the same set of keys and JSON-equal values resolve to
> the same database; differing values in any nested object yield different
> identities (JSONB deep-compare).

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

**Priority when building the aggregator request:** `role` and Secret credentials always override matching keys in `extraProperties`.

**`credentialsSecretRef` fields:**

| Field | Required | Description |
|-------|:--------:|-------------|
| `name` | Yes | Name of the Kubernetes Secret |
| `keys` | Yes | List of `{key, name}` mappings. At least one entry required. Duplicate `name` values within the list are rejected by the controller with `InvalidSpec`. |
| `keys[].key` | Yes | Key in `Secret.data` to read (e.g., `db-user`) |
| `keys[].name` | Yes | Target field name in the aggregator request (e.g., `username`) |

> **Credential rotation:** the operator watches every Secret referenced by any `ExternalDatabase` CR and automatically reconciles affected CRs when a referenced Secret changes (e.g., during credential rotation). Updated credentials are pushed to dbaas-aggregator without any manual spec change. The watch is metadata-only — Secret bodies are not cached in operator memory; the full content is fetched from the API server only at reconcile time.

#### How ExternalDatabase Works

A reconcile is triggered when any of the following happens:

- The CR is created.
- The CR spec changes (i.e., `metadata.generation` increments).
- A Secret referenced via `credentialsSecretRef` changes (credential rotation) — the operator watches every referenced Secret and re-enqueues all `ExternalDatabase` CRs that reference it.
- The covering `NamespaceBinding` is created or updated (e.g., the namespace is being claimed for the first time).

On each reconcile, the controller:

1. Checks namespace ownership via `NamespaceBinding` (skips if not owned).
2. Validates that `spec.classifier.namespace`, if set, equals `metadata.namespace`.
3. Reads credentials from all referenced Kubernetes Secrets.
4. Sends a `PUT` request to dbaas-aggregator to register or update the database.
5. Updates `status.phase` and `status.conditions` based on the outcome.

```
CR created / spec changed / referenced Secret changed
        │
        ▼
  Ownership check (NamespaceBinding)
        │ not owned → skip
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

**`status.phase`** — human-readable summary for `kubectl get dbedb`.

| Phase | Meaning |
|-------|---------|
| `Unknown` | CR just created, not yet processed |
| `Processing` | Controller is actively reconciling (transient) |
| `Succeeded` | Successfully registered with dbaas-aggregator |
| `BackingOff` | Transient error — retrying with exponential backoff (see [Reconcile Backoff](#reconcile-backoff)) |
| `InvalidConfiguration` | Permanent error — will not retry until spec is changed |

**`status.conditions`** — canonical machine-readable state. Use these for automation and alerting.

`LastTransitionTime` is preserved when `Status` (True/False) has not changed — a change in `Reason` or `Message` at the same `Status` does not reset the transition time.

**`Ready`** — is the database registered?

| Status | Meaning |
|--------|---------|
| `True` | Successfully registered for the current generation |
| `False` | Registration failed — check `Reason` and `Message` |

**`Stalled`** — will retrying help?

| Status | Meaning |
|--------|---------|
| `True` | Permanent error — the spec must be corrected; the controller will not retry |
| `False` | Transient error or success — the controller retries automatically |

**Reason vocabulary:**

| Reason | Applied to | Meaning |
|--------|-----------|---------|
| `DatabaseRegistered` | `Ready=True` | Successfully registered with dbaas-aggregator |
| `Succeeded` | `Stalled=False` (on success) | Not stalled; last operation succeeded |
| `InvalidSpec` | `Ready=False`, `Stalled=True` | Local validation failed before calling aggregator |
| `SecretError` | `Ready=False`, `Stalled=False` | Failed to resolve credentials from a referenced Kubernetes Secret. Sub-categories visible via the `dbaas_secret_resolution_errors_total{reason=...}` metric: `secret_not_found`, `key_missing`, `key_empty`, `forbidden` (RBAC denial), `secret_read_failed` (other API / I/O errors). |
| `Unauthorized` | `Ready=False`, `Stalled=False` | Aggregator returned 401 |
| `AggregatorRejected` | `Ready=False`, `Stalled=True` | Aggregator returned 400 / 403 / 409 / 410 / 422 — permanent spec issue |
| `AggregatorError` | `Ready=False`, `Stalled=False` | Aggregator returned 5xx, or network error |

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

**Diagnostic rules:**

- **`Stalled=True`** — fix the spec. The controller will not retry on its own.
- **`Stalled=False` + `Ready=False`** — wait. The controller is retrying automatically.
- **`status.lastRequestId`** — use this value to correlate operator logs with dbaas-aggregator logs.

**`status.observedGeneration`** is set only when the controller exits cleanly (no requeue). If `metadata.generation > status.observedGeneration`, the current spec has not been fully processed yet.

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
# NAME                    PHASE       TYPE         DBNAME              AGE
# my-postgres-external    Succeeded   postgresql   my_application_db   2m

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

`DatabaseAccessPolicy` declares the database role assignments for microservices in a namespace. The operator forwards this declaration to dbaas-aggregator, which applies the role grants when provisioning or connecting databases for those microservices.

Short name: `dbdp`

`kubectl get dbdp` columns: `PHASE`, `MICROSERVICENAME`, `AGE`

#### DatabaseAccessPolicy Resource Fields

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: DatabaseAccessPolicy
metadata:
  name: my-policy
  namespace: my-namespace
spec:
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
| `spec.microserviceName` | Yes | **No** | The microservice that owns this policy. Sent as `metadata.microserviceName` in the aggregator payload. Immutable after creation (CRD CEL rule `self == oldSelf`): repointing the same CR at a different microservice would silently rewrite role grants under the original K8s object and lose the audit link to who created the policy. Create a new CR for a different service. |
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

> **Constraint:** at least one of `spec.services` or `spec.policy` must be non-empty. A CR with both fields absent is rejected by the controller with `InvalidSpec` before the aggregator is contacted.

#### How DatabaseAccessPolicy Works

Each time the spec changes (i.e., `metadata.generation` increments), the controller:

1. Checks namespace ownership via `NamespaceBinding` (skips if not owned).
2. Validates that at least one of `services` or `policy` is non-empty.
3. Sends a `POST /api/declarations/v1/apply` request to dbaas-aggregator with `subKind: DbPolicy`.
4. Updates `status.phase` and `status.conditions` based on the outcome.

```
CR created / spec changed
        │
        ▼
  Ownership check (NamespaceBinding)
        │ not owned → skip
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

**`status.phase`** — human-readable summary for `kubectl get dbdp`.

| Phase | Meaning |
|-------|---------|
| `Unknown` | CR just created, not yet processed |
| `Processing` | Controller is actively reconciling (transient) |
| `Succeeded` | Policy successfully applied via dbaas-aggregator |
| `BackingOff` | Transient error — retrying with exponential backoff (see [Reconcile Backoff](#reconcile-backoff)) |
| `InvalidConfiguration` | Permanent error — will not retry until spec is changed |

**`Ready`** — is the policy applied?

| Status | Meaning |
|--------|---------|
| `True` | Successfully applied for the current generation |
| `False` | Apply failed — check `Reason` and `Message` |

**`Stalled`** — will retrying help?

| Status | Meaning |
|--------|---------|
| `True` | Permanent error — the spec must be corrected; the controller will not retry |
| `False` | Transient error or success — the controller retries automatically |

**Reason vocabulary:**

| Reason | Applied to | Meaning |
|--------|-----------|---------|
| `PolicyApplied` | `Ready=True` | Policy successfully applied via dbaas-aggregator |
| `Succeeded` | `Stalled=False` (on success) | Not stalled; last operation succeeded |
| `InvalidSpec` | `Ready=False`, `Stalled=True` | Local validation failed — both `services` and `policy` are empty |
| `Unauthorized` | `Ready=False`, `Stalled=False` | Aggregator returned 401 |
| `AggregatorRejected` | `Ready=False`, `Stalled=True` | Aggregator returned 400 / 403 / 409 / 410 / 422 — permanent spec issue |
| `AggregatorError` | `Ready=False`, `Stalled=False` | Aggregator returned 5xx, or network error |

**Full state matrix:**

| Scenario | `phase` | `Ready` | `Reason` | `Stalled` |
|----------|---------|:-------:|----------|:---------:|
| Applied (200) | `Succeeded` | `True` | `PolicyApplied` | `False` |
| Both `services` and `policy` empty | `InvalidConfiguration` | `False` | `InvalidSpec` | `True` |
| Aggregator 401 | `BackingOff` | `False` | `Unauthorized` | `False` |
| Aggregator 400 / 403 / 409 / 410 / 422 | `InvalidConfiguration` | `False` | `AggregatorRejected` | `True` |
| Aggregator 5xx / network | `BackingOff` | `False` | `AggregatorError` | `False` |

**Diagnostic rules:**

- **`Stalled=True`** — fix the spec. The controller will not retry on its own.
- **`Stalled=False` + `Ready=False`** — wait. The controller is retrying automatically.
- **`status.lastRequestId`** — use this value to correlate operator logs with dbaas-aggregator logs.

#### DatabaseAccessPolicy Usage Examples

**Grant a specific microservice admin access:**

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: DatabaseAccessPolicy
metadata:
  name: my-policy
  namespace: my-namespace
spec:
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
  microserviceName: my-service
  policy:
    - type: postgresql
      defaultRole: readonly
      additionalRole:
        - admin
```

**Check status:**

```bash
kubectl get dbdp -n my-namespace
# NAME        PHASE       MICROSERVICENAME   AGE
# my-policy   Succeeded   my-service         1m

kubectl describe dbdp my-policy -n my-namespace
```

**Troubleshoot a stuck resource:**

```bash
# Check conditions
kubectl get dbdp my-policy -n my-namespace -o jsonpath='{.status.conditions}' | jq .

# Use lastRequestId to correlate with aggregator logs
kubectl get dbdp my-policy -n my-namespace -o jsonpath='{.status.lastRequestId}'
```

---

### InternalDatabase

`InternalDatabase` declares a logical database that dbaas-aggregator should provision and manage on behalf of the owning microservice. Unlike `ExternalDatabase`, the database does **not** need to exist in advance — the aggregator creates it (and, depending on the configured adapter, the underlying physical DB / user / schema).

Provisioning is **asynchronous**: the aggregator returns `202 Accepted` with a `trackingId`, and the operator polls the operation status until it reaches a terminal state.

Short name: `dbdd`

`kubectl get dbdd` columns: `PHASE`, `MICROSERVICENAME`, `TYPE`, `AGE`

#### InternalDatabase Resource Fields

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: InternalDatabase
metadata:
  name: my-app-db
  namespace: my-namespace
spec:
  classifier:
    microserviceName: my-service   # required
    scope: service                 # required; "service" or "tenant"
    # namespace: my-namespace      # optional; if set, must equal metadata.namespace
    # tenantId: my-tenant          # only meaningful when scope=tenant
    # customKeys:                  # optional adapter-specific identifiers (arbitrary JSON)
    #   logicalDBName: payments
  type: postgresql
  # lazy: false                    # if true, defer provisioning until first access
  # namePrefix: "myapp"            # prefix applied to the physical DB name
  # settings:                      # adapter-specific connection / DB settings (string map)
  #   encoding: UTF8
  # versioningConfig:
  #   approach: clone              # how a new version is created during blue-green
  # initialInstantiation:
  #   approach: clone              # "clone" or "new" (default: "new")
  #   sourceClassifier:            # required when approach=clone
  #     microserviceName: my-service
  #     scope: service
```

**`spec.classifier`** — uniquely identifies the database in dbaas-aggregator.

| Key | Required | Notes |
|-----|:--------:|-------|
| `microserviceName` | Yes | Name of the owning microservice |
| `scope` | Yes | `service` or `tenant` |
| `namespace` | No | If set, must equal `metadata.namespace` — controller-side validation; mismatch causes `InvalidConfiguration`/`InvalidSpec`. If absent, the aggregator uses `metadata.namespace` from the request |
| `tenantId` | No | Only meaningful when `scope=tenant`. When absent, the aggregator applies the declaration for every tenant already registered in the namespace |
| `customKeys` | No | Adapter-specific identifiers. Values can be any JSON type (string, number, boolean, nested object). Not validated by the aggregator — passed through as-is |

**Top-level spec fields:**

| Field | Required | Mutable | Description |
|-------|:--------:|:-------:|-------------|
| `spec.classifier` | Yes | **No** | Database identity in dbaas-aggregator. Immutable after creation (CRD CEL rule `self == oldSelf`): switching the classifier on an existing CR would re-target the controller at a different database while `status.trackingID` and `status.observedGeneration` still reference the original one. Delete and recreate the CR to rebind. |
| `spec.type` | Yes | **No** | Database engine type (e.g., `postgresql`, `mongodb`). Must match a type known to dbaas-aggregator. Immutable after creation: changing the engine mid-flight would request provisioning of a fresh database on a different adapter while the original one stays registered under the same CR identity. |
| `spec.lazy` | No | Yes | When `true`, provisioning is deferred until first access. Defaults to `false`. **Prohibited** in combination with `initialInstantiation.approach=clone` — controller rejects with `InvalidSpec` |
| `spec.settings` | No | Yes | Free-form string-to-string map of adapter-specific settings |
| `spec.namePrefix` | No | Yes | Prefix applied to the physical database name created in the DBMS |
| `spec.versioningConfig` | No | Yes | Strategy for blue-green database versioning. If absent → `versioningType=static`. If present → `versioningType=version` |
| `spec.initialInstantiation` | No | Yes | Initial database creation strategy. If absent → `approach=new` |

> **Note on `spec.classifier` immutability** — the CEL rule is a strict structural equality check (`self == oldSelf`). Once the CR is created, the exact shape of the classifier is frozen: you can neither add an optional sub-field that was omitted (e.g. `namespace`, `tenantId`, `customKeys`) nor remove one that was present. The same caveat applies as for `ExternalDatabase.spec.classifier` — see the immutability note in that section for the practical implications (the controller still defaults `classifier.namespace` to `metadata.namespace` when the field is absent, so the aggregator receives the right namespace either way).

**`spec.versioningConfig` fields:**

| Field | Required | Description |
|-------|:--------:|-------------|
| `approach` | No | Strategy for creating a new database version during blue-green updates. Adapter-specific; aggregator default is `clone` |

**`spec.initialInstantiation` fields:**

| Field | Required | Description |
|-------|:--------:|-------------|
| `approach` | No | `clone` (clone from `sourceClassifier`) or `new` (create an empty database). Default behaviour when the field is absent is `new` |
| `sourceClassifier` | Required when `approach=clone` | Classifier of the source database to clone from. **Constraint:** `sourceClassifier.microserviceName` must equal `classifier.microserviceName` (enforced by the controller) |

> **Note on async provisioning:** the operator stores the aggregator's `trackingId` in `status.trackingID` and polls until the operation completes (every 5 s). While polling, `status.phase` is `WaitingForDependency` and `status.conditions[].reason` is `ProvisioningStarted`. Spec changes during polling clear the stale `trackingID` and start a fresh submission — see [Status Reference](#internaldatabase-status-reference).

#### How InternalDatabase Works

A reconcile is triggered when any of the following happens:

- The CR is created.
- The CR spec changes (i.e., `metadata.generation` increments).
- The covering `NamespaceBinding` is created or updated.
- A polling cycle: while an async operation is in progress (`status.trackingID` is set), the controller re-enqueues itself every 5 seconds.

The reconcile loop has two branches:

- **SUBMIT** — no pending `trackingID`. Validates the spec, builds the declarative payload, sends `POST /api/declarations/v1/apply` with `subKind=DatabaseDeclaration`.
- **POLL** — `status.trackingID` present. Sends `GET /api/declarations/v1/operation/{trackingId}/status` and reacts to the returned task state.

```
CR created / spec changed
        │
        ▼
  Ownership check (NamespaceBinding)
        │ not owned → skip
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
        ├── trackingID present in status?
        │
        ▼ no                              ▼ yes
  ┌── SUBMIT ──────────────┐    ┌── POLL ─────────────────┐
  │ POST /apply            │    │ GET /operation/{id}     │
  │   401 ▶ BackingOff     │    │   401 ▶ BackingOff      │
  │   400/403/409/410/422  │    │   404 ▶ BackingOff      │
  │     ▶ InvalidConfig    │    │     (trackingID cleared │
  │   5xx/network          │    │      → resubmit)        │
  │     ▶ BackingOff       │    │   5xx/network           │
  │   200 OK ▶ Succeeded   │    │     ▶ BackingOff        │
  │   202 Accepted         │    │                         │
  │     store trackingID   │    │ task state:             │
  │     ▶ WaitingForDep    │    │   IN_PROGRESS ▶ poll    │
  └────────────────────────┘    │   COMPLETED ▶ Succeeded │
                                │   FAILED    ▶ InvalidConfig
                                │   TERMINATED ▶ BackingOff
                                │     (trackingID cleared │
                                │      → resubmit)        │
                                └─────────────────────────┘
```

#### InternalDatabase Status Reference

**`status.phase`** — human-readable summary for `kubectl get dbdd`.

| Phase | Meaning |
|-------|---------|
| `Unknown` | CR just created, not yet processed |
| `Processing` | Controller is actively reconciling (transient) |
| `WaitingForDependency` | Async provisioning in progress; controller is polling the aggregator |
| `Succeeded` | Operation completed successfully |
| `BackingOff` | Transient error — retrying with exponential backoff (see [Reconcile Backoff](#reconcile-backoff)) |
| `InvalidConfiguration` | Permanent error — will not retry until spec is changed |

**`status.trackingID`** — aggregator-assigned tracking ID for an in-flight async operation.

- Set when `POST /api/declarations/v1/apply` returns `202 Accepted`.
- Cleared when polling completes (`COMPLETED`, `FAILED`) or the operation must be re-submitted (`TERMINATED`, `404 Not Found`).
- While `trackingID` is non-empty, every reconcile goes through the POLL branch (no resubmission).

**`status.pendingOperationGeneration`** — the `metadata.generation` value captured when `trackingID` was set. If a newer `generation` is observed during a reconcile, the stale `trackingID` is discarded and the operation is re-submitted with the new spec.

**`status.conditions`** — canonical machine-readable state. Same `Ready` / `Stalled` structure as `ExternalDatabase`.

**Reason vocabulary:**

| Reason | Applied to | Meaning |
|--------|-----------|---------|
| `DatabaseProvisioned` | `Ready=True` | Operation completed (`200 OK` synchronous or polled `COMPLETED`) |
| `Succeeded` | `Stalled=False` (on success) | Not stalled; last operation succeeded |
| `InvalidSpec` | `Ready=False`, `Stalled=True` | Local validation failed before calling aggregator |
| `ProvisioningStarted` | `Ready=False`, `Stalled=False` | `202 Accepted` received; async polling in progress |
| `Unauthorized` | `Ready=False`, `Stalled=False` | Aggregator returned 401 |
| `AggregatorRejected` | `Ready=False`, `Stalled=True` | Aggregator returned 400 / 403 / 409 / 410 / 422 on submit, or returned `FAILED` on poll — permanent spec issue |
| `AggregatorError` | `Ready=False`, `Stalled=False` | Aggregator returned 5xx, polling 404 (trackingID expired), or network error |
| `OperationTerminated` | `Ready=False`, `Stalled=False` | Poll returned `TERMINATED` (aggregator restart or admin cancellation). The stale `trackingID` is cleared and the controller resubmits on the next reconcile |

**Full state matrix:**

| Scenario | `phase` | `Ready` | `Reason` | `Stalled` | `trackingID` |
|----------|---------|:-------:|----------|:---------:|:------------:|
| Pre-flight failed | `InvalidConfiguration` | `False` | `InvalidSpec` | `True` | — |
| POST → 401 | `BackingOff` | `False` | `Unauthorized` | `False` | — |
| POST → 400 / 403 / 409 / 410 / 422 | `InvalidConfiguration` | `False` | `AggregatorRejected` | `True` | — |
| POST → 5xx / network | `BackingOff` | `False` | `AggregatorError` | `False` | — |
| POST → 200 OK (sync) | `Succeeded` | `True` | `DatabaseProvisioned` | `False` | — |
| POST → 202 Accepted | `WaitingForDependency` | `False` | `ProvisioningStarted` | `False` | set |
| Poll → IN_PROGRESS | `WaitingForDependency` | `False` | `ProvisioningStarted` | `False` | set |
| Poll → COMPLETED | `Succeeded` | `True` | `DatabaseProvisioned` | `False` | cleared |
| Poll → FAILED | `InvalidConfiguration` | `False` | `AggregatorRejected` | `True` | cleared |
| Poll → TERMINATED | `BackingOff` | `False` | `OperationTerminated` | `False` | cleared (resubmits) |
| Poll → 404 (trackingID expired) | `BackingOff` | `False` | `AggregatorError` | `False` | cleared (resubmits) |
| Poll → 401 / 5xx / network | `BackingOff` | `False` | `Unauthorized` / `AggregatorError` | `False` | preserved (keeps polling) |

**Diagnostic rules:**

- **`Stalled=True`** — fix the spec. The controller will not retry on its own.
- **`Stalled=False` + `Ready=False`, phase=`WaitingForDependency`** — async provisioning is still running; the controller polls every 5 seconds.
- **`Stalled=False` + `Ready=False`, phase=`BackingOff`** — transient error, controller is retrying with exponential backoff.
- **`status.lastRequestId`** — correlate operator logs with aggregator logs.

#### InternalDatabase Usage Examples

**Minimal declaration (synchronous-friendly, non-versioned):**

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: InternalDatabase
metadata:
  name: my-app-db
  namespace: my-namespace
spec:
  classifier:
    microserviceName: my-service
    scope: service
  type: postgresql
```

**Clone from an existing database:**

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: InternalDatabase
metadata:
  name: my-app-db-clone
  namespace: my-namespace
spec:
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
  classifier:
    microserviceName: payments
    scope: service
    customKeys:
      logicalDBName: payments
  type: postgresql
  namePrefix: pay
  settings:
    encoding: UTF8
  versioningConfig:
    approach: clone
```

**Check status:**

```bash
kubectl get dbdd -n my-namespace
# NAME              PHASE                  MICROSERVICENAME   TYPE         AGE
# my-app-db         Succeeded              payments           postgresql   2m
# my-app-db-clone   WaitingForDependency   payments           postgresql   10s
```

**Watch async progress:**

```bash
# The trackingID field is populated while async provisioning is in progress
kubectl get dbdd my-app-db -n my-namespace -o jsonpath='{.status.trackingID}{"\n"}'

# Full status (phase, conditions, trackingID, lastRequestId)
kubectl get dbdd my-app-db -n my-namespace -o yaml
```

**Troubleshoot a stuck resource:**

```bash
# Check conditions for the human-readable error message
kubectl get dbdd my-app-db -n my-namespace -o jsonpath='{.status.conditions}' | jq .

# Use lastRequestId to correlate with aggregator logs
kubectl get dbdd my-app-db -n my-namespace -o jsonpath='{.status.lastRequestId}'
```

---

### Balancing Rule CRDs

The operator exposes three balancing rule CRDs. Each CR stores a **list** of rule entries, and each kind is intentionally a singleton within its allowed scope. The operator validates the Kubernetes resource, checks namespace ownership, and reconciles the desired rule list into dbaas-aggregator. dbaas-aggregator remains the runtime source of truth when a logical database is created and a physical database must be selected.

| Kind | Fixed `metadata.name` | Where the CR lives | What it controls |
|------|------------------------|--------------------|------------------|
| `MicroserviceBalancingRule` | `microservice-balancing-rules` | Business namespace | Per-microservice placement rules for that namespace |
| `NamespaceBalancingRule` | `namespace-balancing-rules` | Business namespace | Per-namespace placement rules for that namespace |
| `PermanentBalancingRule` | `permanent-balancing-rules` | Operator namespace (`CLOUD_NAMESPACE`) | Permanent placement rules that target owned business namespaces |

Any other `metadata.name` is rejected by the controller as `InvalidConfiguration` / `InvalidSpec`. For the two business-namespace CRDs, use one CR per business namespace and edit `spec.rules` to add, update, or remove entries. For permanent rules, use one CR in the operator namespace.

#### Balancing Rule Resource Fields

**`MicroserviceBalancingRule`**

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: MicroserviceBalancingRule
metadata:
  name: microservice-balancing-rules
  namespace: payments
spec:
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
| `metadata.namespace` | Yes | Business namespace. Must have a `NamespaceBinding` owned by this operator. |
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
  rules:
    - name: pg-payments
      type: postgresql
      physicalDatabaseId: postgresql-payments
      order: 10
```

| Field | Required | Description |
|-------|:--------:|-------------|
| `metadata.name` | Yes | Must be `namespace-balancing-rules`. |
| `metadata.namespace` | Yes | Business namespace. Must have a `NamespaceBinding` owned by this operator. |
| `spec.rules` | Yes | Non-empty list of namespace balancing entries. |
| `spec.rules[].name` | Yes | Aggregator rule name. Names are global in the aggregator, so reuse across CRs can clobber state. The controller performs a best-effort global duplicate-name check. |
| `spec.rules[].type` | Yes | Database type. |
| `spec.rules[].physicalDatabaseId` | Yes | Target physical database identifier. |
| `spec.rules[].order` | Yes | Rule priority for the same namespace and database type. Higher `order` wins in the aggregator. |

`order` is mandatory so rule priority is explicit. Without it, omitted values would default to `0`, which makes rule precedence easy to change accidentally and makes duplicate priorities harder to detect. The controller rejects duplicate `type + order` pairs within the singleton CR; cross-CR order conflicts are ultimately enforced by the aggregator with `409 Conflict`.

**`PermanentBalancingRule`**

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: PermanentBalancingRule
metadata:
  name: permanent-balancing-rules
  namespace: dbaas-system
spec:
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
| `metadata.namespace` | Yes | Must be the operator namespace (`CLOUD_NAMESPACE`), not a business namespace. |
| `spec.rules` | Yes | Non-empty list of permanent balancing entries. |
| `spec.rules[].dbType` | Yes | Database type. |
| `spec.rules[].physicalDatabaseId` | Yes | Target physical database identifier. |
| `spec.rules[].namespaces` | Yes | Non-empty list of target business namespaces. Every target namespace must be owned by this operator. |

Within one CR, the same `dbType + namespace` pair cannot appear more than once.

#### How Balancing Rules Work

A reconcile is triggered when a balancing rule CR is created, updated, deleted, or re-enqueued after a relevant `NamespaceBinding` change.

Common flow:

1. Read the singleton CR.
2. Check ownership:
   - Microservice and namespace rules require a `NamespaceBinding` in the CR namespace.
   - Permanent rules require the CR to be in the operator namespace, and every target namespace must be owned by this operator.
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

`status.appliedRules` records what the operator last successfully applied to the aggregator. This allows the controller to detect removed entries and clean up aggregator-side state when a supported cleanup API exists.

| Kind | On create/update | On item removal from `spec.rules` | On CR deletion |
|------|------------------|------------------------------------|----------------|
| `MicroserviceBalancingRule` | Adds a finalizer, applies the full desired list, stores applied `type + microservices`. | Sends cleanup for removed applied `type + microservices` by applying an empty rule set for those entries, then applies the new desired list. | Finalizer cleans up all applied microservice entries before Kubernetes removes the CR. |
| `NamespaceBalancingRule` | Adds a finalizer, applies each desired namespace rule by name, and stores applied entries. | Calls `DELETE /api/v3/dbaas/{namespace}/physical_databases/balancing/rules/{ruleName}` for removed applied rule names, then applies the new desired list. | Finalizer deletes all applied namespace rules before Kubernetes removes the CR. |
| `PermanentBalancingRule` | Adds a finalizer, verifies target namespace ownership, applies the full desired list, stores applied `dbType + namespaces`. | Sends cleanup through `DELETE /api/v3/dbaas/balancing/rules/permanent` for removed applied entries, then applies the new desired list. | Finalizer deletes all applied permanent entries before Kubernetes removes the CR. |

For blue-green cleanup, keep the old operator running until any finalizers on microservice, namespace, and permanent rule CRs have completed.

#### Balancing Rule Status Reference

**`status.phase`**

| Phase | Meaning |
|-------|---------|
| `Unknown` | CR just created, not yet processed. |
| `Processing` | Controller is actively reconciling. |
| `WaitingForDependency` | Permanent rule targets a namespace that is not owned yet; the controller requeues and waits for `NamespaceBinding`. |
| `Succeeded` | Rules were applied successfully. |
| `BackingOff` | Transient aggregator/auth/network error; controller retries with exponential backoff. |
| `InvalidConfiguration` | Permanent spec or cleanup limitation; requires user action before success. |

**`status.appliedRules`**

`status.appliedRules` is controller-owned bookkeeping. Users edit `spec.rules`; the operator writes `status.appliedRules` after successful reconcile so it can compare desired state with previously applied state later.

**Reason vocabulary**

| Reason | Meaning |
|--------|---------|
| `BalancingRuleApplied` | Desired balancing rules were successfully applied to dbaas-aggregator. |
| `InvalidSpec` | Controller-side validation failed before calling aggregator. |
| `WaitingForNamespaceBinding` | The rule is waiting for an owned `NamespaceBinding`. |
| `AggregatorRejected` | Aggregator returned a permanent rejection such as `400`, `403`, `409`, `410`, or `422`. |
| `Unauthorized` | Aggregator returned `401`; usually token/auth configuration. |
| `AggregatorError` | Aggregator returned `5xx` or the request failed due to network/I/O. |

#### Balancing Rule Usage Examples

**Claim the business namespace first:**

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: NamespaceBinding
metadata:
  name: binding
  namespace: payments
spec:
  operatorNamespace: dbaas-system
```

**Microservice balancing singleton:**

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: MicroserviceBalancingRule
metadata:
  name: microservice-balancing-rules
  namespace: payments
spec:
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

**Permanent balancing singleton in the operator namespace:**

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: PermanentBalancingRule
metadata:
  name: permanent-balancing-rules
  namespace: dbaas-system
spec:
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

`DatabaseSecretClaim` requests credentials for a database already managed by dbaas-aggregator and materializes them into a named Kubernetes `Secret` in the same namespace. The operator does **not** provision the database — it looks the database up by classifier and writes the returned `connectionProperties` into the target Secret, keeping it in sync as credentials rotate.

The Secret is created with an `ownerReference` to the CR, so deleting the `DatabaseSecretClaim` cascades to the materialized Secret.

`kubectl get databasesecretclaim` columns: `PHASE`, `TYPE`, `AGE`

> **Required label** — `metadata.labels["app.kubernetes.io/name"]` must be set. Its value is sent as `originService` in the get-by-classifier request, which the aggregator uses to resolve the service's role grants (see [DatabaseAccessPolicy](#databaseaccesspolicy)). A CR without this label is rejected with `InvalidConfiguration`/`InvalidSpec` and the aggregator is never called. The check is enforced at the controller level (CEL validation of `metadata.labels` is not supported by controller-gen at the root schema).

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
  classifier:
    microserviceName: my-service   # required
    scope: service                 # required; "service" or "tenant"
    namespace: my-namespace        # the aggregator always stores this; keep it set
    # tenantId: my-tenant          # only meaningful when scope=tenant
    # customKeys:                  # optional adapter-specific identifiers (arbitrary JSON)
    #   logicalDBName: payments
  type: postgresql                 # required
  # userRole: admin                # optional; permission level of the returned credentials
  secretName: my-app-db-secret     # required; name of the Secret to create/update
```

**`spec.classifier`** — identifies the database in dbaas-aggregator. Same structure and semantics as [InternalDatabase](#internaldatabase-resource-fields).

**Top-level spec fields:**

| Field | Required | Mutable | Description |
|-------|:--------:|:-------:|-------------|
| `spec.classifier` | Yes | **No** | Database identity in dbaas-aggregator. Immutable after creation (CEL `self == oldSelf`): repointing at a different database would write foreign credentials under the same Secret while `status` still references the original. Delete and recreate the CR to rebind. |
| `spec.type` | Yes | **No** | Database engine type (e.g., `postgresql`, `mongodb`). Immutable after creation. |
| `spec.userRole` | No | **No** | Role/permission level of the requested credentials (e.g., `admin`, `ro`, `rw`). When absent, the aggregator resolves the effective role through `DatabaseAccessPolicy` (`defaultRole`) and the global permission registry. Immutable after creation. |
| `spec.secretName` | Yes | **No** | Name of the Kubernetes Secret the operator creates or updates in the CR's namespace. Immutable after creation — changing it would orphan the previously materialized Secret. Two `DatabaseSecretClaim` CRs in the same namespace must not target the same `secretName` (see the sibling-conflict tiebreak below). |

The materialized Secret is of type `Opaque` and stores two keys:

- **`connectionProperties.json`** — the aggregator's `connectionProperties` map serialized as JSON (credentials: `url`, `host`, `port`, `username`, `password`, `role`, …; the exact shape is adapter-specific).
- **`metadata.json`** — a self-describing descriptor `{ classifier, type, userRole, id, name, namespace, settings }` that lets a consumer match the Secret to a database request without calling the aggregator (used by dbaas-client when it reads connection properties from a mounted Secret instead of REST). The `classifier`, `type`, and `userRole` form the **match key**: `classifier` is the same canonical flat map the operator sends to the aggregator (`namespace` defaulted to `metadata.namespace`, empty optional fields omitted); `userRole` mirrors `spec.userRole` (the *requested* role, not the role the aggregator resolved at runtime) and is omitted when empty. The `id`, `name`, `namespace`, and `settings` fields mirror the aggregator's `DatabaseResponseV3SingleCP` so the client can reconstruct a full `LogicalDb` from the mounted Secret; they are descriptive only (not part of the match key) and omitted when empty. `id` in particular may be absent — the aggregator returns it best-effort on a by-classifier lookup.

The operator also stamps the labels `app.kubernetes.io/managed-by=dbaas-operator` and `app.kubernetes.io/name=<value from the CR>`.

#### How DatabaseSecretClaim Works

A reconcile is triggered when any of the following happens:

- The CR is created.
- The CR spec changes (`metadata.generation` increments).
- The covering `NamespaceBinding` is created or updated.
- Another `DatabaseSecretClaim` in the namespace sharing the same `spec.secretName` is created, deleted, or changed (sibling-conflict recovery).
- The rotation poller patches the `dbaas.netcracker.com/rotation-trigger` annotation (credential rotation — see [Rotation Polling](#rotation-polling) below).
- A safety-net re-poll: every successful reconcile re-enqueues itself after 1 hour to recover from any missed rotation event.

```
CR created / spec changed / rotation-trigger annotation changed
        │
        ▼
  Ownership check (NamespaceBinding)
        │ not owned → skip
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

Two behaviours are worth calling out:

- **Content-aware update** — on a rotation-triggered reconcile the operator compares the existing Secret's `connectionProperties.json` (and managed labels) against what it would write. If they already match, it skips the write entirely: no Secret update, no event, `lastRotatedAt` unchanged. This avoids needlessly waking every pod that mounts the Secret (the kubelet reloads mounted Secrets on change). Only a genuine content change is written and reported as `SecretRotated`.
- **Sibling-conflict tiebreak** — if two CRs in the namespace target the same `secretName`, the older one (by `creationTimestamp`, falling back to UID lexical order on a tie) wins and proceeds; the younger one moves to `SecretConflict`. The loser recovers automatically — without a spec change — once the winner is deleted or rebinds, because the controller watches sibling `DatabaseSecretClaim`s by `secretName`.

##### Rotation Polling

When a credential is rotated on the aggregator side, the operator picks it up by **polling** — it is not pushed. The operator exposes no inbound endpoint; all dbaas-aggregator traffic is outbound (see [API Endpoints](#api-endpoints)). A leader-only background loop (the **rotation poller**) periodically reads the aggregator's changed-databases feed and stamps the rotation-trigger annotation on the affected `DatabaseSecretClaim` CRs, which wakes the reconciler.

| Aspect | Value |
|--------|-------|
| Feed | `GET /api/v3/dbaas/databases/changed?sinceTs=&sinceId=` — cluster-scoped, requires the `CLUSTER_OPERATOR` role. Returns the databases whose credentials changed after the cursor, plus the feed's high-water mark. |
| Cadence | Every `DBAAS_ROTATION_POLL_INTERVAL` (Go duration; default `30s`). |
| Leader-gated | Yes — the poller runs only on the elected leader, alongside the reconcilers. |
| Cursor | In-memory keyset cursor `(lastRotatedAt, id)`, seeded from the feed's high-water mark at startup (before the first poll) so rotations around leader acquisition are not skipped. Not persisted — correctness is backstopped by the startup reconcile and the 1-hour safety-net requeue. |
| Authentication | The operator's normal **outbound** credentials (Basic Auth or M2M token — see [Configuration Parameters](#configuration-parameters)); there is no separate inbound auth surface. |

Flow:

```
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

The poller **does not** reconcile directly — it only patches an annotation; the change propagates through the Kubernetes watch so the reconciler performs the actual Secret update. Because the poller and the reconcilers are both leader-gated, the trigger and the reconcile run on the same instance.

###### Why the lookup ignores `userRole`

The cache index the poller queries is keyed by `(classifier, type)` **only** — it deliberately omits `userRole`. The changed-databases feed signals that a *database's* credentials changed, without naming which role rotated; and even if it did, the operator could not reliably map that role to specific CRs. This is a consequence of where role resolution happens.

**The aggregator resolves the effective role at request time, not the operator.** When the operator calls get-by-classifier, it sends the CR's `spec.userRole` verbatim (which may be empty) together with `originService` (the `app.kubernetes.io/name` label). The aggregator then computes the *effective* role from inputs that live entirely on its side and can change without the `DatabaseSecretClaim` CR ever being touched:

- **The `DatabaseAccessPolicy` for the microservice in that namespace.** For the requested `type` it carries a `defaultRole` and an optional `additionalRole` list:
  - When `spec.userRole` is empty, the effective role becomes the policy's `defaultRole` — which may be any role name the platform team configured, not necessarily `admin`.
  - When `spec.userRole` is set, it is accepted only if it appears in `additionalRole` (or equals `defaultRole`); the matched value, lower-cased, becomes the effective role.
- **Whether the request is first-party or cross-service.** If `originService` equals the classifier's `microserviceName`, the policy path above applies. If it is a *different* service (e.g., a CDC consumer reading another service's database), the aggregator instead matches `originService` against the policy's `services` grants, and the effective role is whichever granted role matches the request.
- **The global permission registry**, consulted as a fallback when no policy entry matches and global permissions are not disabled — again defaulting `userRole` to `admin` only when nothing else resolves.

(The aggregator-side logic is `DatabaseRolesService.getSupportRole`.)

**Two consequences for the operator:**

1. The same `spec.userRole` on two CRs can resolve to two different effective roles, and an empty `spec.userRole` can resolve to *anything* the policy dictates. There is no static, CR-local function from `spec.userRole` to the aggregator's effective role.
2. A `DatabaseAccessPolicy` edit changes the effective role of existing `DatabaseSecretClaim` CRs **without** changing those CRs — so any operator-side mapping would have to be invalidated and recomputed every time a policy changes, duplicating the aggregator's resolution and racing its cache.

Matching a specific rotated role to the affected CRs would require the operator to replicate all of the above. Rather than do that, **the poller wakes every `DatabaseSecretClaim` that shares the changed database's `(classifier, type)`, regardless of role.** Each woken CR then re-fetches its own credentials through get-by-classifier — where the aggregator performs the authoritative role resolution — and the [content-aware update](#how-databasesecretclaim-works) writes nothing when the returned credentials are unchanged. So CRs bound to a role other than the one that rotated simply perform a cheap no-op; only the CR(s) whose effective role actually changed get a Secret write and a `SecretRotated` event.

The over-fetch is bounded and cheap: a classifier is typically referenced by 1–3 `DatabaseSecretClaim` CRs (one per role), each costing one get-by-classifier round-trip and no Secret churn on a no-op. Trading a couple of redundant reads for not reimplementing — and not having to keep coherent — the aggregator's role-resolution rules is the right balance.

> Anything a poll misses — e.g. a rotation that commits with an out-of-order timestamp below the advanced cursor — is caught by the startup full reconcile (on start / leader failover) and the 1-hour per-CR safety-net requeue.

#### DatabaseSecretClaim Status Reference

**`status.phase`** — human-readable summary for `kubectl get databasesecretclaim`.

| Phase | Meaning |
|-------|---------|
| `Unknown` | CR just created, not yet processed |
| `Processing` | Controller is actively reconciling (transient) |
| `Succeeded` | The target Secret is present and current |
| `BackingOff` | Transient error — retrying with exponential backoff (see [Reconcile Backoff](#reconcile-backoff)) |
| `InvalidConfiguration` | Permanent error — will not retry until spec is changed |

**`status.firstNotFoundAt`** — timestamp of the first `DatabaseNotFound` (404) response in the current streak. Set on the first 404, cleared on any successful aggregator response. Used to detect a CR that has been waiting too long for its database to appear (e.g., a typo in `spec.classifier`): after a fixed timeout the Ready reason switches to `DatabaseNotFoundTimeout` and per-cycle Warning events stop, while polling continues so the CR self-heals if the database eventually appears.

**`status.lastRotatedAt`** — timestamp of the most recent connection-properties change written to the target Secret. Advanced only when the Secret bytes actually change (rotation or first fill of an adopted Secret); no-op reconciles and the initial creation do **not** advance it.

**`status.conditions`** — canonical machine-readable state. Same `Ready` / `Stalled` structure as the other resources.

**Reason vocabulary:**

| Reason | Applied to | Meaning |
|--------|-----------|---------|
| `SecretCreated` | `Ready=True` | Secret present and current — initial creation or recreation after a deletion race |
| `SecretRotated` | `Ready=True` | The Secret's content was just changed (credential rotation or first fill of an adopted Secret) |
| `SecretUpToDate` | `Ready=True` | Steady-state confirmation — the Secret already matched the desired content (no-op), or a metadata/label backfill rewrote it without a credential change. No event is emitted and `lastRotatedAt` is not advanced |
| `InvalidSpec` | `Ready=False`, `Stalled=True` | Local validation failed: `classifier.namespace` mismatch or missing `app.kubernetes.io/name` label |
| `SecretConflict` | `Ready=False`, `Stalled=True` | The target Secret is owned by another resource, or another `DatabaseSecretClaim` claims the same `secretName` |
| `EmptyConnectionProperties` | `Ready=False`, `Stalled=False` | Aggregator returned `200` with an empty `connectionProperties` map — treated as transient and retried |
| `DatabaseNotFound` | `Ready=False`, `Stalled=False` | Aggregator returned `404`/`CORE-DBAAS-4006` — the database is not yet registered; retried |
| `DatabaseNotFoundTimeout` | `Ready=False`, `Stalled=False` | The `DatabaseNotFound` streak exceeded the timeout (≈10 min) — polling continues but the per-cycle Warning events stop; likely a wrong classifier |
| `Unauthorized` | `Ready=False`, `Stalled=False` | Aggregator returned `401` |
| `AggregatorRejected` | `Ready=False`, `Stalled=True` | Aggregator returned `400` / `403` / `409` / `410` / `422` — permanent spec issue |
| `AggregatorError` | `Ready=False`, `Stalled=False` | Aggregator returned `5xx`, a `404` without a TMF body (blue-green: no active namespace), or a network error |

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

**Diagnostic rules:**

- **`Stalled=True`** — fix the spec (or the conflicting sibling / pre-existing Secret). The controller will not retry on its own.
- **`Stalled=False` + `Ready=False`** — transient; the controller retries with exponential backoff. A persistent `DatabaseNotFound` usually means the `InternalDatabase` for this classifier has not provisioned yet — or the classifier is wrong (watch for `DatabaseNotFoundTimeout`).
- **`status.lastRotatedAt`** — when this was last advanced tells you when credentials last actually changed.
- **`status.lastRequestId`** — correlate operator logs with aggregator logs.

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
# NAME               PHASE       TYPE         AGE
# my-app-db-secret   Succeeded   postgresql   5s

# Inspect the materialized Secret
kubectl get secret my-app-db-secret -n my-namespace -o jsonpath='{.data.connectionProperties\.json}' | base64 -d | jq .

# See when credentials were last rotated (empty until the first rotation)
kubectl get databasesecretclaim my-app-db-secret -n my-namespace -o jsonpath='{.status.lastRotatedAt}'

# Check conditions for the human-readable error message
kubectl get databasesecretclaim my-app-db-secret -n my-namespace -o jsonpath='{.status.conditions}' | jq .

# Use lastRequestId to correlate with aggregator logs
kubectl get databasesecretclaim my-app-db-secret -n my-namespace -o jsonpath='{.status.lastRequestId}'
```

---

## Configuration Parameters

The following parameters control the operator's deployment and behavior. They are set as Helm values.

**Service parameters** — affect operator runtime behavior:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `DBAAS_OPERATOR_ENABLED` | boolean | `false` | When `false`, no Kubernetes resources are created by the Helm chart (Deployment, RBAC, and CRDs are all skipped). Must be set to `true` to deploy the operator. |
| `LEADER_ELECT` | boolean | `true` | Enables leader election. Required when running more than one replica to ensure only one active instance processes resources at a time. |
| `K8S_EVENTS_ENABLED` | boolean | `false` | When `true`, the operator emits Kubernetes Events on reconcile outcomes (visible in `kubectl describe`). Requires additional RBAC (`create`, `patch` on `core/events`). |
| `DBAAS_AGGREGATOR_URL` | string | `http://dbaas-aggregator:8080` | Base URL of the dbaas-aggregator API. Override only when the aggregator is not reachable at the default in-cluster service address (e.g. cross-cluster deployments). Read by the operator as an environment variable; not set by the Helm chart unless explicitly configured. |
| `KUBERNETES_M2M_ENABLED` | boolean | `false` | Selects how the operator authenticates to dbaas-aggregator; **must match the aggregator's own `KUBERNETES_M2M_ENABLED`**. `false` (default): HTTP Basic Auth as `DBAAS_AGGREGATOR_USERNAME`, with the password read from the mounted security Secret. `true`: Kubernetes projected service-account token (Bearer / M2M). The aggregator rejects Bearer tokens outright when its M2M is disabled, so a mismatch fails every call. |
| `DBAAS_AGGREGATOR_USERNAME` | string | `dbaas-operator` | Basic Auth user, used only when `KUBERNETES_M2M_ENABLED=false`. Must exist in the aggregator's `users.json` with the `DB_CLIENT` and `CLUSTER_OPERATOR` roles (provisioned via the aggregator's `DBAAS_OPERATOR_CREDENTIALS_*` values). |
| `DBAAS_SECURITY_CONFIGURATION_LOCATION` | string | `/etc/dbaas/security` | Mount path of the aggregator security Secret (`users.json`) from which the Basic Auth password is read and hot-reloaded on rotation. Basic Auth mode only. |
| `DBAAS_ROTATION_POLL_INTERVAL` | string | `""` (→ `30s`) | Poll period (Go duration, e.g. `15s`, `1m`) for the aggregator's changed-databases feed used to propagate credential rotations. Empty uses the operator's built-in default (`30s`). |
| `LOG_LEVEL` | string | `info` | Log verbosity. Allowed values: `debug`, `info`, `warn`, `error`. |
| `restrictedEnvironment` | boolean | `false` | When `true`, the Helm chart does not create `ClusterRole` and `ClusterRoleBinding` (which must be applied manually). The namespace-scoped `Role` and `RoleBinding` are always created. |
| `MONITORING_ENABLED` | boolean | — | When `true`, creates a `PodMonitor` for Prometheus scraping and imports Grafana dashboards. Requires Platform System Monitor CRDs. |

**Deployment parameters** — control pod scheduling and resources:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `PAAS_PLATFORM` | string | `KUBERNETES` | Target platform. Allowed values: `KUBERNETES`, `OPENSHIFT`. Controls security context settings. |
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

### Reconcile Backoff

When a reconcile attempt fails with a transient error (Secret not found, aggregator 5xx, network error, etc.), the controller does not retry immediately. It uses an **exponential backoff** rate limiter: the delay doubles on each consecutive failure for the same object, up to a configured maximum.

This behaviour is controlled by two operator startup flags, which can be set via `args` in the Deployment:

| Flag | Default | Description |
|------|---------|-------------|
| `--backoff-base-delay` | `1s` | Initial retry delay after the first failure. Doubles on each subsequent consecutive failure for the same object. |
| `--backoff-max-delay` | `5m` | Maximum delay cap. Once reached, retries continue at this interval until the error is resolved. |

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
  - --metrics-bind-address=:8080
  - --leader-elect
  - --backoff-base-delay=5s
  - --backoff-max-delay=10m
```
