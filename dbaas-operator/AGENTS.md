# AGENTS.md (dbaas-operator)

The operator reconciles DBaaS custom resources and calls the dbaas-aggregator API. It is built with Kubebuilder and
controller-runtime. All resources are in the single group `dbaas.netcracker.com/v1`: `ExternalDatabase`,
`InternalDatabase`, `DatabaseSecretClaim`, `DatabaseAccessPolicy`, `MicroserviceBalancingRule`,
`NamespaceBalancingRule`, `PermanentBalancingRule` and `PhysicalDatabase`.

Entry points: `cmd/main.go` (manager setup and flags), `api/v1/*_types.go` (CRD schemas),
`internal/controller/` (reconcilers), `internal/client/` (aggregator client), `internal/poller/` (credential
rotation). The Kustomize `config/` folder is for local development and envtest; production installs use the Helm chart
in `helm-templates/dbaas-operator/`.

## Logging

Use `github.com/netcracker/qubership-core-lib-go/v3/logging` in production code. Don't use `log`, `fmt.Println`, zap,
logrus, klog, slog or controller-runtime's `log.FromContext`.

```go
var log = logging.GetLogger("my-component")

log.InfoC(ctx, "Registered database type=%s dbName=%s", dbType, dbName)
log.ErrorC(ctx, "Failed to call aggregator: %v", err)
```

- Use the `...C(ctx, ...)` methods where a context exists; they add the request ID.
- Where controller-runtime needs a `logr.Logger`, use the bridge in `cmd/logr_adapter.go`. The controller-runtime zap
  logger is allowed only in tests.
- Messages follow the Kubernetes style that `logcheck` checks: a capital letter, no final period, past tense for a
  completed action (`Failed to create Pod`), the object type named, and `key=value` pairs inline.

## Request IDs

`main()` calls `requestcontext.RegisterProviders()`, and every reconcile starts with
`ctx, requestID := requestcontext.WithFreshRequestID(ctx)`. The aggregator client rejects a call made without it.

## Calling the Aggregator

- Use `github.com/go-resty/resty/v2`, not raw `net/http`. Attach credentials in an `OnBeforeRequest` hook, not per
  call.
- `M2M_AUTH_MODE` must match the aggregator's setting, or the aggregator rejects the call:
  - `legacy` (the default): HTTP Basic. Credentials come from `users.json` in the mounted
    `dbaas-security-configuration-secret` and reload when the Secret changes. Never read them from environment
    variables.
  - `hybrid` or `k8s`: a projected service-account token from `security/tokensource`.
- The operator has no inbound endpoint. It picks up credential rotations by polling the aggregator.

Classify every aggregator error with the `AggregatorError` helpers in `internal/client/types.go` (`IsSpecRejection()`,
`IsAuthError()`), never by checking status codes in a controller:

| Category | HTTP codes | Behavior | Phase |
|---|---|---|---|
| Spec rejection | 400, 403, 409, 410, 422 | No retry until the spec changes | `InvalidConfiguration` |
| Auth error | 401 | Retry with backoff | `BackingOff` |
| Server or network | 5xx, timeouts | Retry with backoff | `BackingOff` |

Parse error bodies with the TMF format from `qubership-core-lib-go-error-handling/v3/tmf`.

## API Design (`api/v1/*_types.go`)

- Status conditions are `metav1.Condition` with `+listType=map` and `+listMapKey=type`. Embed the shared
  `OperatorStatus` (`Phase`, `ObservedGeneration`, `Conditions`) and implement `SetObservedGeneration(int64)`.
- `status.conditions` is the API contract. `Phase` is only a summary for `kubectl get`: never base controller logic on
  it, and never give it an OpenAPI enum. With an enum, the API server rejects the whole status write, conditions
  included, when a new phase value ships before the CRD is updated.
- Express immutability and singleton rules with CEL (`+kubebuilder:validation:XValidation`).
- Document every field: the comments become the CRD descriptions.

## Controllers (`internal/controller/`)

- Reconciliation is idempotent.
- Watch with `GenerationChangedPredicate`, so only spec changes trigger a reconcile.
- Call `config.controllerOptions()` once per registered controller, so each gets its own backoff state; never share
  one `Options` value.
- Put RBAC markers on the controller.
- Conditions: `Ready=True` means processed; `Ready=False` means an error, and its reason names the category.
  `Stalled=True` means a permanent error, with no retry until the spec changes; `Stalled=False` means the controller
  retries. Set conditions with `setCondition()`, which keeps `LastTransitionTime` when the status is unchanged.
- Emit an event for every significant outcome. Reason constants live in `events.go` and are shared with condition
  reasons: past tense for success (`DatabaseRegistered`), present participle for an ongoing problem (`Unauthorized`).

```go
func (r *MyReconciler) Reconcile(ctx context.Context, req ctrl.Request) (result ctrl.Result, retErr error) {
    ctx, requestID := requestcontext.WithFreshRequestID(ctx)

    obj := &myapi.MyResource{}
    if err := r.Get(ctx, req.NamespacedName, obj); err != nil {
        return ctrl.Result{}, client.IgnoreNotFound(err)
    }
    if !isEligibleForOperator(ctx, obj.Spec.OperatorNamespace, r.MyNamespace, obj.Namespace, obj.Name, "MyResource") {
        return ctrl.Result{}, nil
    }

    original := obj.DeepCopy()
    defer func() {
        patchStatusOnExit(ctx, r.Status(), obj, original, &retErr, ..., "MyResource")
    }()

    obj.Status.Phase = myapi.PhaseProcessing
    // Validate the spec (a permanent error means no requeue), call the aggregator, mark Succeeded, emit an event.
    return ctrl.Result{}, nil
}

func (r *MyReconciler) SetupWithManager(mgr ctrl.Manager, config RateLimiterConfig) error {
    return ctrl.NewControllerManagedBy(mgr).
        For(&myapi.MyResource{}, builder.WithPredicates(predicate.GenerationChangedPredicate{})).
        WithOptions(config.controllerOptions()).
        Named("myresource").
        Complete(r)
}
```

The deferred patch writes the status on every exit, including an error.

## Testing

- Controller tests use Ginkgo and Gomega with envtest (`internal/controller/suite_test.go`). Don't use testify. Plain
  `testing.T` table tests are fine for small units such as `cmd/` and `internal/client/`.
- Mock the aggregator with `httptest.NewServer`, and assert events with `record.NewFakeRecorder`.
- End-to-end tests run by hand on a dedicated Kind cluster with the aggregator mock (`dev/README.md`), never on a shared
  cluster.

```bash
make test-unit    # unit tests, no envtest
make test         # unit and envtest controller tests
make lint         # make lint-fix applies fixes
```

## Generated Files

Never edit these by hand:

| File | Regenerated by |
|---|---|
| `config/crd/bases/*.yaml`, `config/rbac/role.yaml` | `make manifests` |
| `**/zz_generated.*.go` | `make generate` |
| `helm-templates/dbaas-operator/templates/crd-*.yaml` | `make sync-helm-crds` |
| `PROJECT` | `kubebuilder` CLI |

After editing `*_types.go` or RBAC markers, run `make manifests generate sync-helm-crds`. The `verify-generated` job in
CI fails when the committed files differ. Create new APIs with `kubebuilder create api`, and keep the
`// +kubebuilder:scaffold:*` markers.

## Other Rules

- `cmd/main.go` keeps the blank import `github.com/netcracker/qubership-core-lib-go/v3/memlimit`, which sets
  `GOMEMLIMIT` from the container limits. Don't set `GOMEMLIMIT` by hand.
- Every Helm template except the ConfigMap is gated on `DBAAS_OPERATOR_ENABLED`. The chart keeps `values.schema.json`
  and the resource profiles `dev`, `dev-ha`, `prod-nonha` and `prod` up to date.
- The runtime image is `ghcr.io/netcracker/qubership-core-base`, built with `CGO_ENABLED=0` and run as user `10001`.
- A change to a metric, a label, histogram buckets or a dashboard panel updates
  `docs/monitoring/DBaaS Operator Metrics.md` (and the dashboard JSON) in the same change.
- Every `.go` file starts with the license header from `hack/boilerplate.go.txt`.
