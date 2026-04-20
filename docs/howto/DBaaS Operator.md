# DBaaS Operator

## Table of Contents

- [Overview](#overview)
- [High-Level Architecture](#high-level-architecture)
- [API Endpoints](#api-endpoints)
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
- [Configuration Parameters](#configuration-parameters)
  - [Reconcile Backoff](#reconcile-backoff)

---

## Overview

DBaaS Operator is a Kubernetes operator that integrates with dbaas-aggregator. It runs cluster-wide and manages the following custom resources:

| Custom Resource | API Group | Scope | Purpose |
|-----------------|-----------|-------|---------|
| `NamespaceBinding` | `dbaas.netcracker.com/v1` | Namespaced | Declares that a namespace is managed by this operator instance |
| `ExternalDatabase` | `dbaas.netcracker.com/v1` | Namespaced | Registers a pre-existing database with dbaas-aggregator |

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
│  │  └──────────────────────────────────────────┘   │  │
│  └──────────────────────────────────────────────────┘  │
│                          │                              │
│         watches (cluster-wide)                          │
│                          │                              │
│  ┌───────────────────────┼──────────────────────────┐  │
│  │     app-namespace     │                          │  │
│  │                       ▼                          │  │
│  │  NamespaceBinding ─── ownership check            │  │
│  │  ExternalDatabase ─── reconcile ──────────────── ┼──┼──▶ dbaas-aggregator
│  │  Secret (credentials)                            │  │
│  └──────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

**Key design decisions:**

- The operator runs **cluster-wide** — no static `--watch-namespaces` list.
- Namespace ownership is determined dynamically via `NamespaceBinding` CRs.
- Workload CRs in namespaces without a matching `NamespaceBinding` are silently skipped.
- Credentials for `ExternalDatabase` are read from Kubernetes Secrets at reconcile time.
- Authentication to dbaas-aggregator uses a projected service account token (rotated automatically by Kubernetes).

---

## API Endpoints

The operator calls the following dbaas-aggregator endpoint:

| Method | URL | Used by | Purpose |
|--------|-----|---------|---------|
| `PUT` | `/api/v3/dbaas/{namespace}/databases/registration/externally_manageable` | `ExternalDatabase` reconciler | Register or update an externally managed database |

### ExternalDatabase Registration Endpoint

**`PUT /api/v3/dbaas/{namespace}/databases/registration/externally_manageable`**

The `{namespace}` segment is taken from `spec.classifier["namespace"]` if that key is set; otherwise from `metadata.namespace`.

The operator always sends `updateConnectionProperties: true`, which means the request creates the database registration if it does not exist, or updates the connection properties if it does.

**Possible responses and operator behavior:**

| HTTP Code | Situation | Operator outcome |
|-----------|-----------|-----------------|
| `201 Created` | Successfully registered or updated | `Succeeded` — `Ready=True` |
| `400` | Invalid classifier (missing required fields) | `InvalidConfiguration` — `Ready=False`, `Stalled=True`, reason `AggregatorRejected` |
| `401` | Missing or invalid auth token | `BackingOff` — retried, reason `Unauthorized` |
| `403` | `tenantId` in classifier does not match JWT | `InvalidConfiguration` — `Ready=False`, `Stalled=True`, reason `AggregatorRejected` |
| `409` | Database exists but is not externally managed | `InvalidConfiguration` — `Ready=False`, `Stalled=True`, reason `AggregatorRejected` |
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
  # ExternalDatabase: the controller only reads (Get/List) and watches CRs.
  # Status is written via the /status subresource — no write access to the main resource is needed.
  - apiGroups: ["dbaas.netcracker.com"]
    resources: ["externaldatabases"]
    verbs: ["get", "list", "watch"]

  # Update ExternalDatabase status subresource
  - apiGroups: ["dbaas.netcracker.com"]
    resources: ["externaldatabases/status"]
    verbs: ["get", "update", "patch"]

  # NamespaceBinding: patch is required to add/remove the binding-protection finalizer (client.MergeFrom).
  # Kubernetes additionally checks update on /finalizers when metadata.finalizers changes during a patch.
  - apiGroups: ["dbaas.netcracker.com"]
    resources: ["namespacebindings"]
    verbs: ["get", "list", "watch", "patch"]

  - apiGroups: ["dbaas.netcracker.com"]
    resources: ["namespacebindings/finalizers"]
    verbs: ["update"]

  # Read Secrets to resolve credentials referenced by ExternalDatabase CRs.
  # Secrets are fetched directly from the API server on each reconcile (not cached),
  # so only get is required — list and watch are not needed.
  - apiGroups: [""]
    resources: ["secrets"]
    verbs: ["get"]
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
| `dbaas.netcracker.com` | `externaldatabases` | `get`, `list`, `watch` | Watch and read CRs across all namespaces; status is written via `/status` subresource |
| `dbaas.netcracker.com` | `externaldatabases/status` | `get`, `update`, `patch` | Write reconcile outcome to `status.phase` and `status.conditions` |
| `dbaas.netcracker.com` | `namespacebindings` | `get`, `list`, `watch`, `patch` | Watch and read CRs; `patch` is required to add/remove the `binding-protection` finalizer via `client.MergeFrom` |
| `dbaas.netcracker.com` | `namespacebindings/finalizers` | `update` | Kubernetes additionally checks this permission when `metadata.finalizers` changes during a patch |
| `""` (core) | `secrets` | `get` | Read credential Secrets referenced by `ExternalDatabase` CRs (fetched directly, not cached) |

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

The operator runs cluster-wide and watches all namespaces. Before reconciling any workload resource (e.g., `ExternalDatabase`), it checks whether the resource's namespace is owned by this operator instance.

Ownership is determined by looking for a `NamespaceBinding` named `binding` in the same namespace and comparing `spec.operatorNamespace` with the operator's own `CLOUD_NAMESPACE` environment variable.

```
ExternalDatabase reconcile triggered
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

When a `NamespaceBinding` is created or updated, the operator automatically re-enqueues all workload CRs in that namespace — so existing `ExternalDatabase` objects are reconciled immediately without requiring a spec change.

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
| Namespace still contains `ExternalDatabase` resources | Finalizer is kept; deletion is blocked; a `BindingBlocked` warning event is emitted |
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

If the finalizer is present, the operator owns the namespace and will reconcile workload resources (`ExternalDatabase`) within it.

This is intentional. `NamespaceBinding` is a declaration of ownership, not a job or pipeline — its semantics are binary: either the operator has claimed the namespace or it has not. A `status` field would add complexity without real benefit, and stale status values would be misleading in edge cases (e.g., operator restart). The finalizer is sufficient and follows the established Kubernetes practice for simple ownership resources.

**Delete a binding (after removing all workload resources):**

```bash
# Remove all ExternalDatabase resources first
kubectl delete externaldatabase --all -n my-namespace

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
    namespace: my-namespace        # optional; if set, must equal metadata.namespace
    microserviceName: my-service   # required
    scope: service                 # required; "service" or "tenant"
    # tenantId: my-tenant          # required when scope=tenant
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

**`spec.classifier`** — uniquely identifies the database in dbaas-aggregator. A sorted map of key-value pairs.

| Key | Required | Notes |
|-----|:--------:|-------|
| `microserviceName` | Yes | Name of the owning microservice |
| `scope` | Yes | `service` or `tenant` |
| `tenantId` | When `scope=tenant` | Must be present when scope is `tenant` |
| `namespace` | No | If set, must equal `metadata.namespace`. If absent, `metadata.namespace` is used in the aggregator URL. |

**Top-level spec fields:**

| Field | Required | Mutable | Description |
|-------|:--------:|:-------:|-------------|
| `spec.classifier` | Yes | No | Database identity in dbaas-aggregator. Immutable after creation. |
| `spec.type` | Yes | No | Database engine type (e.g., `postgresql`, `mongodb`). Must match a type known to dbaas-aggregator. Immutable after creation. |
| `spec.dbName` | Yes | No | Logical database name. Included in the aggregator request URL. Immutable after creation. |
| `spec.connectionProperties` | Yes | Yes | List of connection entries, one per access role. At least one entry required. |

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

> **Important:** The operator does **not** watch Secrets for changes. Secrets are read once per reconcile, which is triggered only by a change to the `ExternalDatabase` spec (i.e., when `metadata.generation` increments). If you rotate credentials in a Secret, you must also make a change to the `ExternalDatabase` spec — for example, add or update an annotation — to trigger a new reconcile and push the updated credentials to dbaas-aggregator.

#### How ExternalDatabase Works

Each time the spec changes (i.e., `metadata.generation` increments), the controller:

1. Checks namespace ownership via `NamespaceBinding` (skips if not owned).
2. Validates that `spec.classifier["namespace"]`, if set, equals `metadata.namespace`.
3. Reads credentials from all referenced Kubernetes Secrets.
4. Sends a `PUT` request to dbaas-aggregator to register or update the database.
5. Updates `status.phase` and `status.conditions` based on the outcome.

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
    classifier["namespace"] ≠ metadata.namespace? ──▶ InvalidConfiguration (InvalidSpec)
        │
        ▼
  Read Secrets
    Secret not found? ──────────────────────────────▶ BackingOff (SecretError, retried)
    Key missing or empty? ──────────────────────────▶ BackingOff (SecretError, retried)
        │
        ▼
  Call dbaas-aggregator PUT
    401 ────────────────────────────────────────────▶ BackingOff (Unauthorized, retried)
    400 / 403 / 409 ────────────────────────────────▶ InvalidConfiguration (AggregatorRejected)
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
| `SecretError` | `Ready=False`, `Stalled=False` | Secret not found, or required key is missing or empty |
| `Unauthorized` | `Ready=False`, `Stalled=False` | Aggregator returned 401 |
| `AggregatorRejected` | `Ready=False`, `Stalled=True` | Aggregator returned 400 / 403 / 409 — permanent spec issue |
| `AggregatorError` | `Ready=False`, `Stalled=False` | Aggregator returned 5xx, or network error |

**Full state matrix:**

| Scenario | `phase` | `Ready` | `Reason` | `Stalled` |
|----------|---------|:-------:|----------|:---------:|
| Registered (201) | `Succeeded` | `True` | `DatabaseRegistered` | `False` |
| `classifier["namespace"]` mismatch | `InvalidConfiguration` | `False` | `InvalidSpec` | `True` |
| Secret not found | `BackingOff` | `False` | `SecretError` | `False` |
| Secret key missing or empty | `BackingOff` | `False` | `SecretError` | `False` |
| Aggregator 401 | `BackingOff` | `False` | `Unauthorized` | `False` |
| Aggregator 400 / 403 / 409 | `InvalidConfiguration` | `False` | `AggregatorRejected` | `True` |
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

## Configuration Parameters

The following parameters control the operator's deployment and behavior. They are set as Helm values.

**Service parameters** — affect operator runtime behavior:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `DBAAS_OPERATOR_ENABLED` | boolean | `false` | When `false`, no Kubernetes resources are created by the Helm chart (Deployment, RBAC, and CRDs are all skipped). Must be set to `true` to deploy the operator. |
| `LEADER_ELECT` | boolean | `true` | Enables leader election. Required when running more than one replica to ensure only one active instance processes resources at a time. |
| `K8S_EVENTS_ENABLED` | boolean | `false` | When `true`, the operator emits Kubernetes Events on reconcile outcomes (visible in `kubectl describe`). Requires additional RBAC (`create`, `patch` on `core/events`). |
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
