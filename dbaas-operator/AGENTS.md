# AGENTS.md (dbaas-operator)

The operator reconciles DBaaS custom resources and calls the dbaas-aggregator API. It is built with Kubebuilder and
controller-runtime. All resources are in the single group `dbaas.netcracker.com/v1`: `ExternalDatabase`,
`InternalDatabase`, `DatabaseSecretClaim`, `DatabaseAccessPolicy`, `MicroserviceBalancingRule`,
`NamespaceBalancingRule`, `PermanentBalancingRule` and `PhysicalDatabase`.

Entry points: `cmd/main.go` (manager setup and flags), `api/v1/*_types.go` (CRD schemas),
`internal/controller/` (reconcilers), `internal/client/` (aggregator client), `internal/poller/` (credential
rotation). The Kustomize `config/` folder is for local development and envtest; production installs use the Helm chart
in `helm-templates/dbaas-operator/`.

## Backward Compatibility

CRD fields are additive and optional only. Never rename, remove or repurpose an existing field, including a status
field or a condition type the API already reports.

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

Every reconcile runs under a fresh request ID, and every call to the aggregator carries it on the context. The
aggregator client rejects a call made without one.

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

| Category          | HTTP codes              | Behavior                        | Phase                  |
|-------------------|-------------------------|---------------------------------|------------------------|
| Spec rejection    | 400, 403, 409, 410, 422 | No retry until the spec changes | `InvalidConfiguration` |
| Auth error        | 401                     | Retry with backoff              | `BackingOff`           |
| Server or network | 5xx, timeouts           | Retry with backoff              | `BackingOff`           |

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
- Gate every reconcile on `isEligibleForOperator` with the operator namespace from the spec, and return without a
  requeue when it is false.
- Treat a missing object as done: return `client.IgnoreNotFound(err)` from `Get`.
- Watch with `GenerationChangedPredicate`, so only spec changes trigger a reconcile.
- Call `config.controllerOptions()` once per registered controller, so each gets its own backoff state; never share
  one `Options` value.
- Put RBAC markers on the controller.
- Keep a `DeepCopy` baseline and defer `patchStatusOnExit` with the named return error and a predicate for when to
  record the observed generation, so the status is patched on every exit, including an error.
- Conditions: `Ready=True` means processed; `Ready=False` means an error, and its reason names the category.
  `Stalled=True` means a permanent error, with no retry until the spec changes; `Stalled=False` means the controller
  retries. Set conditions with `setCondition()`, which keeps `LastTransitionTime` when the status is unchanged.
- Emit an event for every significant outcome. Reason constants live in `events.go` and are shared with condition
  reasons: past tense for success (`DatabaseRegistered`), present participle for an ongoing problem (`Unauthorized`).

## Go Code

- Start every `.go` file with the license header from `hack/boilerplate.go.txt`.
- Keep the blank import `github.com/netcracker/qubership-core-lib-go/v3/memlimit` in `cmd/main.go`: it sets
  `GOMEMLIMIT` from the container limits. Don't set `GOMEMLIMIT` by hand.

## Helm Chart

Gate every template except the ConfigMap on `DBAAS_OPERATOR_ENABLED`, and keep `values.schema.json` and the resource
profiles `dev`, `dev-ha`, `prod-nonha` and `prod` up to date.

## Monitoring

When you change a metric, a label, histogram buckets or a dashboard panel, update the dashboard JSON and
`docs/monitoring/DBaaS Operator Metrics.md` in the same change.

## Generated Files

Never edit these by hand:

| File                                                 | Regenerated by        |
|------------------------------------------------------|-----------------------|
| `config/crd/bases/*.yaml`, `config/rbac/role.yaml`   | `make manifests`      |
| `**/zz_generated.*.go`                               | `make generate`       |
| `helm-templates/dbaas-operator/templates/crd-*.yaml` | `make sync-helm-crds` |
| `PROJECT`                                            | `kubebuilder` CLI     |

After editing `*_types.go` or RBAC markers, run `make manifests generate sync-helm-crds`. The `verify-generated` job in
CI fails when the committed files differ. Create new APIs with `kubebuilder create api`, and keep the
`// +kubebuilder:scaffold:*` markers.

## Testing

- Controller tests use Ginkgo and Gomega with envtest (`internal/controller/suite_test.go`). Don't use testify. Plain
  `testing.T` table tests are fine for small units such as `cmd/` and `internal/client/`.
- Mock the aggregator with `httptest.NewServer`, and assert events with `record.NewFakeRecorder`.
- End-to-end tests run by hand on a dedicated Kind cluster with the aggregator mock (`dev/README.md`), never on a shared
  cluster.

## Commands

After a Go change, `make test` and `make lint` must pass; `make test-unit` is the fast loop while iterating.

```bash
make test-unit    # unit tests, no envtest
make test         # unit and envtest controller tests
make lint         # make lint-fix applies fixes
```
