# NamespaceBinding — Design & Implementation

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

### Namespace Ownership via NamespaceBinding

A new CRD `NamespaceBinding` is introduced (group `dbaas.netcracker.com/v1`):

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: NamespaceBinding
metadata:
  name: binding              # always exactly "binding" — singleton per namespace
  namespace: <business-ns>
spec:
  operatorNamespace: <operator CLOUD_NAMESPACE>   # e.g. "dbaas-system"
```

- **One object per namespace** — the name is fixed to `"binding"`
  (CRD CEL rule: `self.metadata.name == 'binding'`).
- **`spec.operatorNamespace` is immutable** after creation (CEL transition rule:
  `self == oldSelf`). Changing which operator owns a namespace is only possible
  by recreating the object.
- **The operator runs cluster-wide** (without `--watch-namespaces`): it sees
  NamespaceBindings in all namespaces and reconciles workload resources only in
  namespaces where `operatorNamespace` matches its own `CLOUD_NAMESPACE`.

### Protection Against Premature Deletion

When a NamespaceBinding is created, the operator adds the finalizer
`platform.dbaas.netcracker.com/binding-protection`.
When a NamespaceBinding is deleted, the operator checks for blocking resources
(`ExternalDatabase`, `DatabaseDeclaration`, `DbPolicy`) in the same namespace:

- If any are present, the finalizer is **not removed**, the object remains in
  `DeletionTimestamp` state, and a Warning event `BindingBlocked` is recorded.
- As soon as all blocking resources are gone, the operator removes the
  finalizer and Kubernetes completes the deletion of the NamespaceBinding.

---

## Implementation

### 1. API — `api/v1/namespacebinding_types.go`

```
NamespaceBinding
└── Spec.OperatorNamespace  string   // immutable (CEL transition rule)
```

Constants:
- `NamespaceBindingName = "binding"` — the only allowed name.
- `NamespaceBindingProtectionFinalizer = "platform.dbaas.netcracker.com/binding-protection"`.

There is no status subresource; observability is provided through Kubernetes
Events.

### 2. Package `internal/ownership/`

#### `resolver.go` — OwnershipResolver

A write-through in-memory cache, safe for concurrent use (`sync.RWMutex`).
There are four namespace states:

| State     | Meaning |
|-----------|---------|
| `Mine`    | A NamespaceBinding exists and `operatorNamespace == CLOUD_NAMESPACE` |
| `Foreign` | A NamespaceBinding exists and `operatorNamespace` belongs to another operator instance |
| `Unbound` | A live API lookup confirmed that no NamespaceBinding exists yet |
| `Unknown` | No cached information is available (transient: startup or post-Forget) |

Methods:
- `SetOwner(namespace, operatorNamespace)` — called by `NamespaceBindingReconciler` on
  every create/update.
- `Forget(namespace)` — called on NamespaceBinding deletion (deletes the entry
  from the map, resetting it to Unknown).
- `GetState(namespace)` — fast path without IO.
- `IsMyNamespace(ctx, namespace)` — used by workload reconcilers:
  - **Fast path**: if the cache has an entry (Mine, Foreign, or Unbound), return
    it immediately without any API call.
  - **Slow path**: GET NamespaceBinding from the API, update the cache. If the
    object does not exist, cache the namespace as `Unbound` and return `false`.
- `WarmupOwnershipCache(ctx)` — LIST all NamespaceBindings cluster-wide and fill
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
├── KindChecker[ExternalDatabaseList]         (always)
├── KindChecker[DatabaseDeclarationList]      (only if alpha enabled)
└── KindChecker[DbPolicyList]                 (only if alpha enabled)
```

### 3. `internal/controller/namespacebinding_controller.go` and the `binding → workloads` watch

Reconcile loop:

```
GET NamespaceBinding
├── Not Found → Forget(namespace), return OK
│
├── Found, DeletionTimestamp == 0 (creation/update)
│   ├── SetOwner(namespace, operatorNamespace)   ← update cache
│   └── finalizer missing?
│       └── Patch add finalizer, emit Normal/BindingRegistered
│
└── Found, DeletionTimestamp != 0 (deletion in progress)
    ├── SetOwner(namespace, operatorNamespace)   ← keep cache current until the end
    ├── spec.operatorNamespace != MyNamespace?
    │   └── Foreign binding → skip mutations, return OK
    ├── HasBlockingResources?
    │   ├── Yes → emit Warning/BindingBlocked, return (requeue comes from watches)
    │   └── No  → Patch remove finalizer, Forget(namespace)
```

**Watches in `NamespaceBindingReconciler`** (`workload → binding`):
`handler.EnqueueRequestsFromMapFunc` is configured for `ExternalDatabase`
(always) plus `DatabaseDeclaration` and `DbPolicy` (when `alphaEnabled`).
Mapping: any object
→ `{Namespace: obj.Namespace, Name: "binding"}`. This guarantees that
deleting the last blocking resource immediately triggers another finalizer
check.

**Watches in workload reconcilers** (`binding → workloads`):
Each of the three workload controllers (`ExternalDatabase`,
`DatabaseDeclaration`, and `DbPolicy`) also watches `NamespaceBinding`. When a
NamespaceBinding is created or updated, the `enqueueForBinding` mapping does a
LIST of all corresponding CRs in the same namespace and returns one
`reconcile.Request` per object.
This solves the key scenario where a CR is created before the NamespaceBinding:
without this watch it would remain "skipped" until the next change to its own
spec.

### 4. Ownership Check in Workload Reconcilers

At the beginning of each of the three `Reconcile` methods (before `DeepCopy`
and `defer`), the reconciler checks the namespace state and requeues accordingly:

```go
if mine, err := r.Ownership.IsMyNamespace(ctx, <obj>.Namespace); err != nil {
    return ctrl.Result{}, err
} else if !mine {
    switch r.Ownership.GetState(<obj>.Namespace) {
    case ownership.Unknown:
        // Transient — requeue quickly until cache settles.
        return ctrl.Result{RequeueAfter: ownershipPollInterval}, nil
    case ownership.Unbound:
        // No binding yet — requeue at a long interval as a lost-trigger
        // safety net; the NamespaceBinding watch fan-out handles the
        // normal case when a binding is eventually created.
        return ctrl.Result{RequeueAfter: ownershipUnboundRetryInterval}, nil
    default:
        // Foreign — no requeue; the watch re-triggers on binding changes.
        return ctrl.Result{}, nil
    }
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
- `NamespaceBindingReconciler` is initialized before the workload reconcilers.

### 6. RBAC

Permissions for `namespacebindings`
(`get/list/watch/create/update/patch/delete`) and
`namespacebindings/finalizers` (`update`) are added in
`config/rbac/role.yaml` (generated by kubebuilder),
`hack/k8s/operator.yaml`, and
`helm-templates/.../ClusterRole.yaml`.

---

## Tests

### `internal/ownership/resolver_test.go`

Unit tests using a fake client (without envtest) cover:
- `GetState` / `SetOwner` / `Forget` — cache state transitions.
- `IsMyNamespace` fast path (cache) and slow path (API lookup, Not Found →
  cached as Unbound, Foreign).
- Unbound → overwritten by `SetOwner` when a binding is created.
- `WarmupOwnershipCache` — cache pre-population at startup.
- `KindChecker` — empty and non-empty namespace cases.
- `CompositeChecker` — OR semantics, short-circuit behavior, and `Add()`.

### `internal/controller/namespacebinding_controller_test.go`

Ginkgo/Gomega + envtest cover:
- Creation: finalizer added, cache → Mine, `BindingRegistered` event emitted.
- Second reconcile (finalizer already present): idempotent, no events.
- Foreign binding: cache → Foreign, no finalizer added, no events.
- Foreign binding on deletion: finalizer not removed by non-owning instance.
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
# Without NamespaceBinding, the operator skips all resources in test-ns
kubectl logs deployment/dbaas-operator -n dbaas-system | grep "skipping\|unbound"

# Create the binding — the operator starts processing test-ns
kubectl apply -f hack/test-resources/namespacebinding.yaml

# Attempt to delete the binding while workload resources still exist — blocked
kubectl delete namespacebinding binding -n test-ns
kubectl get events -n test-ns --field-selector involvedObject.name=binding
# → Warning BindingBlocked: deletion deferred: namespace still contains dbaas workload resources

# Delete all workload resources → binding is deleted automatically
kubectl delete externaldatabase,databasedeclaration,dbpolicy --all -n test-ns
kubectl get namespacebinding -n test-ns
# → No resources found
```
