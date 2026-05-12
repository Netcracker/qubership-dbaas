# Qubership Platform — Engineering Standards for AI Agents

> This file defines mandatory conventions for all Go microservices on the Qubership platform.
> AI agents (Claude Code, Cursor, Copilot, etc.) **must** follow these rules when generating,
> editing, or reviewing code. Place this file as `CLAUDE.md` (or equivalent) in every repo root.

---

## Language & Runtime

- **Go 1.26+** — all services are written in Go.
- Module path convention: `github.com/netcracker/qubership-<product>/<component>`
- Use Go modules (`go.mod` / `go.sum`). Never use `dep`, `glide`, or `vendor/`.

---

## Logging

### Required library

```
github.com/netcracker/qubership-core-lib-go/v3/logging
```

**Do NOT use** `log`, `fmt.Println`, `go.uber.org/zap` directly, `logrus`, `klog`, or
`slog` for application logging. The platform logger wraps zap internally and integrates
with context propagation (request IDs, trace context).

### Usage

```go
import "github.com/netcracker/qubership-core-lib-go/v3/logging"

// Package-level logger — one per package, named after the component.
var log = logging.GetLogger("my-component")

// Leveled methods: Infof, Debugf, Errorf, Warnf.
log.Infof("starting reconciliation name=%s", name)
log.Errorf("failed to create Pod: %v", err)

// Context-aware logging (carries X-Request-Id automatically):
log.InfoC(ctx, "registered database type=%s dbName=%s", dbType, dbName)
log.ErrorC(ctx, "aggregator call failed: %v", err)
```

### Logging style (Kubernetes conventions)

- Start messages with a capital letter.
- Do **not** end messages with a period.
- Use active voice: `"Created Deployment"`, not `"deployment was created"`.
- Use past tense for completed actions: `"Failed to create Pod"`, not `"Cannot create Pod"`.
- Always specify the object type: `"Deleted Pod"`, not `"Deleted"`.
- Attach structured key=value pairs inline: `log.Infof("backoff configured base=%v max=%v", base, max)`.

### Bridging logr (controller-runtime)

When controller-runtime requires a `logr.Logger`, bridge it via a `logrAdapter` that
delegates to `logging.GetLogger`. See `cmd/logr_adapter.go` for the reference
implementation. **Do NOT** use `sigs.k8s.io/controller-runtime/pkg/log/zap` in production;
it is acceptable only in test suites.

```go
ctrl.SetLogger(newLogrLogger("my-operator"))
```

---

## Context Propagation & Request IDs

### Required library

```
github.com/netcracker/qubership-core-lib-go/v3/context-propagation/ctxmanager
github.com/netcracker/qubership-core-lib-go/v3/context-propagation/baseproviders/xrequestid
```

### Initialization (in `main()`)

```go
ctxmanager.Register([]ctxmanager.ContextProvider{
    xrequestid.XRequestIdProvider{},
})
```

### Per-reconcile / per-request

Every reconcile loop or incoming request handler must generate a unique request ID and
attach it to the context:

```go
requestID := uuid.New().String()
ctx = ctxmanager.InitContext(ctx, map[string]any{
    "X-Request-Id": requestID,
})
```

This enables end-to-end tracing across operator → aggregator → adapter logs.

---

## Error Handling

### Required library

```
github.com/netcracker/qubership-core-lib-go-error-handling/v3
```

Specifically, use the TMF error response format for parsing upstream errors:

```go
import "github.com/netcracker/qubership-core-lib-go-error-handling/v3/tmf"
```

### Error classification pattern

All errors from external services must be classified into:

| Category | HTTP codes | Operator behaviour | Phase |
|---|---|---|---|
| **Spec rejection** (permanent) | 400, 403, 409, 410, 422 | No retry, wait for spec change | `InvalidConfiguration` |
| **Auth error** (transient) | 401 | Retry with backoff | `BackingOff` |
| **Server / network** (transient) | 5xx, timeouts | Retry with backoff | `BackingOff` |

Use typed error structs with helper methods (`IsAuthError()`, `IsSpecRejection()`) rather
than checking status codes inline in controller code. See `internal/client/types.go` for
the `AggregatorError` reference.

---

## HTTP Client

### Required library

```
github.com/go-resty/resty/v2
```

**Do NOT use** raw `net/http` for outgoing REST calls. Resty provides retries,
interceptors, and structured request building.

### Authentication pattern

Use projected service account tokens via the platform token source:

```go
import "github.com/netcracker/qubership-core-lib-go/v3/security/tokensource"

token, err := tokensource.GetAudienceToken(ctx, tokensource.AudienceDBaaS)
```

Attach tokens via a Resty `OnBeforeRequest` hook, not manually per call.

---

## Memory Limits

### Required import (side-effect)

```go
import _ "github.com/netcracker/qubership-core-lib-go/v3/memlimit"
```

This blank import auto-configures `GOMEMLIMIT` based on the container's cgroup limits.
**Always include it** in `cmd/main.go`. Do NOT set `GOMEMLIMIT` manually.

---

## Docker

### Base image (runtime stage)

```dockerfile
FROM ghcr.io/netcracker/qubership-core-base:<version>
```

**Do NOT use** `gcr.io/distroless`, `alpine`, `ubuntu`, `scratch`, or Docker Hub base
images. The platform base image includes security patches, CA certificates, and a
non-root user (UID 10001).

### Build pattern (multi-stage)

```dockerfile
FROM --platform=$BUILDPLATFORM golang:1.26 AS builder
ARG TARGETOS
ARG TARGETARCH

WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH} go build -a -o myservice ./cmd/

FROM ghcr.io/netcracker/qubership-core-base:2.2.11
WORKDIR /app
COPY --chown=10001:0 --chmod=555 --from=builder /app/myservice /app/myservice
USER 10001:10001
ENTRYPOINT ["/app/myservice"]
```

### Rules

- Always use multi-stage builds. The builder stage uses `golang:1.26`.
- Cross-compilation via `TARGETOS`/`TARGETARCH` — never use QEMU emulation.
- `CGO_ENABLED=0` — always. No CGO dependencies.
- Run as non-root: `USER 10001:10001`.
- Set `--chown=10001:0 --chmod=555` on the binary.

---

## Kubernetes Operator Framework

### Required framework

- **Kubebuilder** for scaffolding.
- **controller-runtime** (`sigs.k8s.io/controller-runtime`) for the operator runtime.
- **Ginkgo v2 + Gomega** for tests.
- **envtest** for integration tests (real API server + etcd, no cluster needed).

### API design (`api/<version>/*_types.go`)

- Use `metav1.Condition` for status conditions — never custom string fields.
- Embed a shared `OperatorStatus` struct for common fields (`Phase`, `ObservedGeneration`, `Conditions`).
- Define a `Phase` enum with these standard values:
  `Unknown → Processing → Succeeded | BackingOff | InvalidConfiguration`
- Use `+kubebuilder:validation:XValidation` (CEL) for immutability rules on spec fields.
- Use `+listType=map` / `+listMapKey=type` on Condition slices for strategic merge.
- Implement `SetObservedGeneration(int64)` on every CR root type.

```go
// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:resource:scope=Namespaced
// +kubebuilder:printcolumn:name="Phase",type="string",JSONPath=".status.phase"
// +kubebuilder:printcolumn:name="Age",type="date",JSONPath=".metadata.creationTimestamp"
```

### Controller design (`internal/controller/*_controller.go`)

- **Idempotent reconciliation**: must be safe to run multiple times.
- **Snapshot → defer patch** pattern: deep-copy the object at entry, defer a status patch
  on exit so the status always reflects the actual outcome, even on error.

```go
original := obj.DeepCopy()
defer func() {
    patchStatusOnExit(ctx, r.Status(), obj, original, &retErr, ...)
}()
```

- **GenerationChangedPredicate**: filter events to spec changes only.
- Use `client.IgnoreNotFound(err)` for the initial GET.
- Use `workqueue.NewTypedItemExponentialFailureRateLimiter` for backoff (configurable via flags).

### Condition management

- Two standard condition types: `Ready` and `Stalled`.
- `Ready=True` — resource successfully processed.
- `Ready=False` — error; reason indicates the category.
- `Stalled=True` — permanent error, no retry until spec changes.
- `Stalled=False` — transient error, controller retries automatically.
- Use `setCondition()` helper that preserves `LastTransitionTime` when status is unchanged.

### Kubernetes Events

- Emit events via `record.EventRecorder` for every significant outcome.
- Event reasons: CamelCase constants, past tense for successes (`DatabaseRegistered`),
  present participle for ongoing problems (`Unauthorized`).
- Define all event reason constants in one file (`events.go`) — shared between Events and
  Condition reasons.
- Support disabling events via `K8S_EVENTS_ENABLED=false` with a `noopRecorder`.

---

## Testing

### Test framework

```
github.com/onsi/ginkgo/v2
github.com/onsi/gomega
```

**Do NOT use** `testing.T` assertions or `testify`. All tests use Ginkgo BDD style.

### Controller integration tests

- Use `envtest.Environment` — real API server + etcd, no mocked K8s.
- Mock external services (aggregator) with `httptest.NewServer`.
- Use `record.NewFakeRecorder` to capture and assert Kubernetes events.
- Create helper functions for common patterns:
  - `reconcileAndFetchObject[T]()` — reconcile then re-fetch.
  - `findCondition()` — locate a condition by type.
  - `expectRecordedEvent()` / `expectRecordedEventContaining()` — assert events.
  - `mineOwnershipResolver()` / `foreignOwnershipResolver()` — pre-seed ownership cache.

### Running tests

```bash
make test-unit       # Unit tests only, no envtest, fast
make test            # Unit + controller integration tests (downloads envtest binaries)
make test-e2e        # Full e2e in a Kind cluster (isolated)
```

---

## Linting

### Linter: golangci-lint v2

Configuration lives in `.golangci.yml` at the repo root. Enabled linters include:
`errcheck`, `govet`, `staticcheck`, `revive`, `gocyclo`, `misspell`, `unused`,
`ginkgolinter`, `logcheck`, `modernize`, `prealloc`, among others.

### Commands

```bash
make lint            # Check only
make lint-fix        # Auto-fix
```

### Key rules enforced

- `logcheck` — validates Kubernetes logging conventions.
- `ginkgolinter` — enforces Ginkgo best practices.
- `revive` with `import-shadowing` — catches shadowed imports.
- `gofmt` + `goimports` — enforced formatting.

---

## Helm Charts

- Helm templates live in `helm-templates/<component>/`.
- Provide resource profiles: `dev.yaml`, `dev-ha.yaml`, `prod-nonha.yaml`, `prod.yaml`.
- Always include a `values.schema.json` for value validation.
- CRDs are rendered as Helm templates (not raw `config/crd/bases` output).

---

## Code Generation (auto-generated files)

### NEVER edit these files manually

| File | Regenerated by |
|---|---|
| `config/crd/bases/*.yaml` | `make manifests` |
| `config/rbac/role.yaml` | `make manifests` |
| `**/zz_generated.*.go` | `make generate` |
| `PROJECT` | `kubebuilder` CLI |

### After editing `*_types.go` or RBAC markers

```bash
make manifests    # Regenerate CRDs + RBAC
make generate     # Regenerate DeepCopy
```

### After editing any `*.go` file

```bash
make lint-fix     # Auto-fix style
make test         # Run all tests
```

---

## Project Layout

```
cmd/main.go                           Entry point, manager setup, flag parsing
cmd/logr_adapter.go                   logr → platform logger bridge
api/v1/*_types.go                     Stable CRD schemas
api/v1alpha1/*_types.go               Alpha CRD schemas (behind ALPHA_APIS_ENABLED)
internal/controller/*_controller.go   Reconciliation logic
internal/controller/helpers.go        Shared condition/status/ownership utilities
internal/controller/events.go         Event reason constants
internal/controller/conditions.go     Condition type constants + timing intervals
internal/client/                      HTTP client for external APIs
internal/ownership/                   Namespace ownership resolution
config/                               Kustomize manifests (stable APIs only)
config-dev/                           Kustomize manifests (stable + alpha APIs)
helm-templates/                       Helm chart templates
dev/                                  Local development utilities (Kind, mocks)
```

---

## Common Patterns to Follow

### Reconcile function skeleton

```go
func (r *MyReconciler) Reconcile(ctx context.Context, req ctrl.Request) (result ctrl.Result, retErr error) {
    // 1. Generate request ID
    requestID := uuid.New().String()
    ctx = ctxmanager.InitContext(ctx, map[string]any{"X-Request-Id": requestID})

    // 2. Fetch the CR
    obj := &myapi.MyResource{}
    if err := r.Get(ctx, req.NamespacedName, obj); err != nil {
        return ctrl.Result{}, client.IgnoreNotFound(err)
    }

    // 3. Check namespace ownership
    owned, result, err := checkOwnership(ctx, r.Ownership, obj.Namespace, obj.Name, "MyResource")
    if err != nil { return ctrl.Result{}, err }
    if !owned  { return result, nil }

    // 4. Snapshot + defer status patch
    original := obj.DeepCopy()
    defer func() {
        patchStatusOnExit(ctx, r.Status(), obj, original, &retErr, ..., "MyResource")
    }()

    // 5. Mark Processing
    obj.Status.Phase = myapi.PhaseProcessing

    // 6. Validate spec (permanent errors → no requeue)
    // 7. Build request
    // 8. Call external service
    // 9. Mark Succeeded + emit event
    return ctrl.Result{}, nil
}
```

### SetupWithManager skeleton

```go
func (r *MyReconciler) SetupWithManager(mgr ctrl.Manager, opts ctrlcontroller.Options) error {
    return ctrl.NewControllerManagedBy(mgr).
        For(&myapi.MyResource{},
            builder.WithPredicates(predicate.GenerationChangedPredicate{})).
        Watches(&dbaasv1.NamespaceBinding{},
            handler.EnqueueRequestsFromMapFunc(r.enqueueForBinding)).
        WithOptions(opts).
        Named("myresource").
        Complete(r)
}
```

---

## License Header

Every `.go` file must start with:

```go
/*
Copyright 2026.

Licensed under the Apache License, Version 2.0 (the "License");
...
*/
```

Use `dev/boilerplate.go.txt` as the source for `controller-gen`.

---

## Environment Variables

| Variable | Purpose | Required |
|---|---|---|
| `CLOUD_NAMESPACE` | Operator's own namespace (ownership checks) | Yes |
| `DBAAS_AGGREGATOR_URL` | Aggregator base URL (default: `http://dbaas-aggregator:8080`) | No |
| `K8S_EVENTS_ENABLED` | Enable/disable Kubernetes event recording (`true`/`false`) | No |
| `ALPHA_APIS_ENABLED` | Enable v1alpha1 API controllers (`true`/`false`) | No |

---

## What NOT to Do

- **Do NOT** use `fmt.Println`, `log.Printf`, or any logger other than `logging.GetLogger`.
- **Do NOT** use Docker Hub base images or `scratch`.
- **Do NOT** use `testify` — use Ginkgo + Gomega.
- **Do NOT** edit auto-generated files (CRDs, RBAC, DeepCopy, PROJECT).
- **Do NOT** remove `// +kubebuilder:scaffold:*` comments.
- **Do NOT** create API or webhook files manually — use `kubebuilder create api/webhook`.
- **Do NOT** configure OpenTelemetry or zap directly — use the platform libraries.
- **Do NOT** use `GOMEMLIMIT` manually — use the `memlimit` blank import.
- **Do NOT** use custom string fields for status conditions — use `metav1.Condition`.
- **Do NOT** inline HTTP status code checks in controllers — use typed error structs.
