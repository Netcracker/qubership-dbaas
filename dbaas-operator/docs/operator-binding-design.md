# OperatorBinding — Design & Implementation

## Problem

The operator manages three kinds of workload resources: `ExternalDatabase`,
`DatabaseDeclaration`, and `DbPolicy`.

**Initial problem:** the namespaces handled by the operator were configured via
the static `--watch-namespaces` flag (comma-separated list). This meant:

- To add or remove a namespace, the Pod had to be recreated by changing
  Deployment args.
- In multi-tenant environments where multiple operator instances share the
  cluster, there was no formal contract defining which instance owned which
  namespace.
- There was no protection against accidental binding deletion while DBaaS
  resources were still present in the namespace.

**Goal:** replace the static flag with a declarative Kubernetes resource that
allows namespace ownership to be defined at runtime and prevents ownership from
being released while workload CRs still exist in that namespace.

---

## Solution Concept

### Namespace Ownership via OperatorBinding

A new CRD `OperatorBinding` is introduced (group `dbaas.netcracker.com/v1`):

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: OperatorBinding
metadata:
  name: registration        # always exactly "registration" — singleton per namespace
  namespace: <business-ns>
spec:
  location: <operator CLOUD_NAMESPACE>   # e.g. "dbaas-system"
```

- **One object per namespace** — the name is fixed to `"registration"`
  (CRD CEL rule: `self.metadata.name == 'registration'`).
- **`spec.location` is immutable** after creation (CEL transition rule:
  `self == oldSelf`). Changing which operator owns a namespace is only possible
  by recreating the object.
- **The operator runs cluster-wide** (without `--watch-namespaces`): it sees
  OperatorBindings in all namespaces and reconciles workload resources only in
  namespaces where `location` matches its own `CLOUD_NAMESPACE`.

### Protection Against Premature Deletion

When an OperatorBinding is created, the operator adds the finalizer
`platform.dbaas.netcracker.com/binding-protection`.
When an OperatorBinding is deleted, the operator checks for blocking resources
(`ExternalDatabase`, `DatabaseDeclaration`, `DbPolicy`) in the same namespace:

- If any are present, the finalizer is **not removed**, the object remains in
  `DeletionTimestamp` state, and a Warning event `BindingBlocked` is recorded.
- As soon as all blocking resources are gone, the operator removes the
  finalizer and Kubernetes completes the deletion of the OperatorBinding.

---

## Implementation

### 1. API — `api/v1/operatorbinding_types.go`

```
OperatorBinding
└── Spec.Location  string   // immutable (CEL transition rule)
```

Constants:
- `OperatorBindingName = "registration"` — the only allowed name.
- `OperatorBindingProtectionFinalizer = "platform.dbaas.netcracker.com/binding-protection"`.

There is no status subresource; observability is provided through Kubernetes
Events.

### 2. Package `internal/ownership/`

#### `resolver.go` — OwnershipResolver

A write-through in-memory cache, safe for concurrent use (`sync.RWMutex`).
There are three namespace states:

| State | Meaning |
|-----------|---------|
| `Mine`    | An OperatorBinding exists and `location == CLOUD_NAMESPACE` |
| `Foreign` | An OperatorBinding exists and `location` belongs to another operator instance |
| `Unknown` | No cached information is available |

Methods:
- `SetOwner(namespace, location)` — called by `OperatorBindingReconciler` on
  every create/update.
- `Forget(namespace)` — called on OperatorBinding deletion (deletes the entry
  from the map rather than storing `Unknown`).
- `GetState(namespace)` — fast path without IO.
- `IsMyNamespace(ctx, namespace)` — used by workload reconcilers:
  - **Fast path**: if the cache has an entry, return it immediately.
  - **Slow path**: GET OperatorBinding from the API, update the cache. If the
    object does not exist, return `false` without caching (a binding may appear
    later).
- `WarmupOwnershipCache(ctx)` — LIST all OperatorBindings cluster-wide and fill
  the cache. Registered at startup via `mgr.Add(Runnable)` with
  `NeedLeaderElection() = false` (runs on every Pod, not only the leader).
  Errors are non-fatal; the operator falls back to the slow path.

#### `checker.go` — BlockingResourceChecker

An interface plus two implementations:

- `KindChecker[L client.ObjectList]` — generic checker that verifies whether at
  least one object of a given type exists in a namespace via `LIST + Limit(1)`.
- `CompositeChecker` — aggregates multiple checkers with OR semantics
  (short-circuit on the first `true`). Supports `Add()` after creation.

In `cmd/main.go`, the composition depends on `ALPHA_APIS_ENABLED`:

```
CompositeChecker
├── KindChecker[ExternalDatabaseList]         (всегда)
├── KindChecker[DatabaseDeclarationList]      (только если alpha enabled)
└── KindChecker[DbPolicyList]                 (только если alpha enabled)
```

### 3. `internal/controller/operatorbinding_controller.go` and the `binding → workloads` watch

Reconcile loop:

```
GET OperatorBinding
├── Not Found → Forget(namespace), return OK
│
├── Found, DeletionTimestamp == 0 (creation/update)
│   ├── SetOwner(namespace, location)   ← update cache
│   └── finalizer missing?
│       └── Patch add finalizer, emit Normal/BindingRegistered
│
└── Found, DeletionTimestamp != 0 (deletion in progress)
    ├── SetOwner(namespace, location)   ← keep cache current until the end
    ├── HasBlockingResources?
    │   ├── Yes → emit Warning/BindingBlocked, return (requeue comes from watches)
    │   └── No  → Patch remove finalizer, Forget(namespace)
```

**Watches in `OperatorBindingReconciler`** (`workload → binding`):
`handler.EnqueueRequestsFromMapFunc` is configured for `ExternalDatabase`
(always) plus `DatabaseDeclaration` and `DbPolicy` (when `alphaEnabled`).
Mapping: any object
→ `{Namespace: obj.Namespace, Name: "registration"}`. This guarantees that
deleting the last blocking resource immediately triggers another finalizer
check.

**Watches in workload reconcilers** (`binding → workloads`):
Each of the three workload controllers (`ExternalDatabase`,
`DatabaseDeclaration`, and `DbPolicy`) also watches `OperatorBinding`. When an
OperatorBinding is created or updated, the `enqueueForBinding` mapping does a
LIST of all corresponding CRs in the same namespace and returns one
`reconcile.Request` per object.
This solves the key scenario where a CR is created before the OperatorBinding:
without this watch it would remain "skipped" until the next change to its own
spec.

### 4. Ownership Check in Workload Reconcilers

At the beginning of each of the three `Reconcile` methods (before `DeepCopy`
and `defer`):

```go
if mine, err := r.Ownership.IsMyNamespace(ctx, <obj>.Namespace); err != nil {
    return ctrl.Result{}, err
} else if !mine {
    log.InfoC(ctx, "skipping ...: namespace not owned by this operator")
    return ctrl.Result{}, nil
}
```

The reconciler structs are extended with the field
`Ownership *ownership.OwnershipResolver`.

### 5. `cmd/main.go`

- The `--watch-namespaces` flag and all `cache.Options.DefaultNamespaces` logic
  are removed.
- Reading the `CLOUD_NAMESPACE` env var is added (required; fatal if missing).
  It is injected into the Deployment via downward API
  `fieldRef: metadata.namespace`.
- `ownershipWarmupRunnable` is registered via `mgr.Add()`.
- `OperatorBindingReconciler` is initialized before the workload reconcilers.

### 6. RBAC

Permissions for `operatorbindings`
(`get/list/watch/create/update/patch/delete`) and
`operatorbindings/finalizers` (`update`) are added in
`config/rbac/role.yaml` (generated by kubebuilder),
`hack/k8s/operator.yaml`, and
`helm-templates/.../ClusterRole.yaml`.

---

## Tests

### `internal/ownership/resolver_test.go`

Unit tests using a fake client (without envtest) cover:
- `GetState` / `SetOwner` / `Forget` — cache state transitions.
- `IsMyNamespace` fast path (cache) and slow path (API lookup, Not Found,
  Foreign).
- `WarmupOwnershipCache` — cache pre-population at startup.
- `KindChecker` — empty and non-empty namespace cases.
- `CompositeChecker` — OR semantics, short-circuit behavior, and `Add()`.

### `internal/controller/operatorbinding_controller_test.go`

Ginkgo/Gomega + envtest cover:
- Creation: finalizer added, cache → Mine, `BindingRegistered` event emitted.
- Second reconcile (finalizer already present): idempotent, no events.
- Foreign binding: cache → Foreign.
- Deletion with no blocking resources: finalizer removed, `Forget` called.
- Deletion with blocking resources (via `alwaysBlockingChecker`): finalizer
  preserved, `BindingBlocked` event emitted.
- Not Found: `Forget` called, no error.

Existing `ExternalDatabase`, `DatabaseDeclaration`, and `DbPolicy` tests are
extended: each reconciler now gets `Ownership: mineOwnershipResolver(ns)`.
This helper pre-seeds the resolver with `Mine` state for the test namespace
(fast path, no API call).

---

## What Was Removed

| Before | After |
|------|-------|
| `--watch-namespaces=ns1,ns2` Deployment arg | No longer needed; the operator is cluster-wide |
| `cache.Options.DefaultNamespaces` in manager | Removed |
| `WATCH_NAMESPACES` in Helm values | Removed |
| Watches limited to explicitly configured namespaces | Cluster-wide watch + per-object ownership check |

---

## Manual Verification (kind)

```bash
# Without OperatorBinding, the operator skips all resources in test-ns
kubectl logs deployment/dbaas-operator -n dbaas-system | grep "skipping"

# Create the binding — the operator starts processing test-ns
kubectl apply -f hack/test-resources/operatorbinding.yaml

# Attempt to delete the binding while workload resources still exist — blocked
kubectl delete operatorbinding registration -n test-ns
kubectl get events -n test-ns --field-selector involvedObject.name=registration
# → Warning BindingBlocked: deletion deferred: namespace still contains dbaas workload resources

# Delete all workload resources → binding is deleted automatically
kubectl delete externaldatabase,databasedeclaration,dbpolicy --all -n test-ns
kubectl get operatorbinding -n test-ns
# → No resources found
```
