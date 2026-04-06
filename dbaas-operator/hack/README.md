# Local development — kind

A quick way to spin up a full test environment for manual testing of `dbaas-operator`
in a local [kind](https://kind.sigs.k8s.io/) cluster.

## What gets deployed

| Resource | Namespace | Description |
|---|---|---|
| `aggregator-mock` Deployment + Service | `dbaas-system` | HTTP stub for dbaas-aggregator |
| `aggregator-mock-rules` ConfigMap | `dbaas-system` | Per-request routing rules: `rules.json` (EDB by `dbName`), `apply-rules.json` (DbPolicy/DatabaseDeclaration by `microserviceName`), `poll-rules.json` (DatabaseDeclaration async poll by `trackingId`) |
| `dbaas-operator` Deployment | `dbaas-system` | Operator with RBAC — watches all namespaces cluster-wide |
| Namespace `test-ns` | — | Working namespace for CRs |
| `OperatorBinding/registration` | `test-ns` | Claims `test-ns` for this operator (location=`dbaas-system`) |
| Secret `pg-credentials` | `test-ns` | Test credentials for ExternalDatabase |

## Prerequisites

| Tool | Version |
|---|---|
| [kind](https://kind.sigs.k8s.io/docs/user/quick-start/#installation) | v0.20+ |
| [kubectl](https://kubernetes.io/docs/tasks/tools/) | v1.28+ |
| [docker](https://docs.docker.com/get-docker/) | 20+ |
| make | any |

## Start the environment

```bash
./hack/kind-up.sh
```

Creates a cluster named `dbaas`. To use a different name:

```bash
KIND_CLUSTER=my-cluster ./hack/kind-up.sh
```

The script runs these steps in order:

1. `kind create cluster` — creates the cluster (skipped if it already exists)
2. `make install` — installs CRDs
3. `docker build` — builds `dbaas-operator:dev` and `aggregator-mock:dev` images
4. `kind load docker-image` — loads images into the cluster (no registry required)
5. `kubectl apply` — deploys aggregator-mock and the operator into `dbaas-system`
6. `kubectl apply` — creates namespace `test-ns` and secret `pg-credentials`
7. Waits for `rollout status` on both deployments

## Test scenarios

**Namespace ownership** is now declared through `OperatorBinding` rather than a
`--watch-namespaces` flag.  The operator runs cluster-wide but only reconciles
resources in namespaces where an `OperatorBinding` named `registration` exists
and its `spec.location` matches the operator's own namespace (`CLOUD_NAMESPACE`).

`hack/test-resources/operatorbinding.yaml` (included in the directory) creates
the binding for `test-ns` automatically when you apply the full directory.

Apply all test CRs at once and observe their phases:

```bash
kubectl apply -f hack/test-resources/ -n test-ns
kubectl get operatorbinding -n test-ns       # registration   dbaas-system
kubectl get externaldatabase -n test-ns
kubectl get dbpolicy -n test-ns
kubectl get databasedeclaration -n test-ns
```

### ExternalDatabase

The mock routes `PUT .../externally_manageable` requests by `dbName` from the request body.
Rules are defined in `rules.json` inside the `aggregator-mock-rules` ConfigMap.

Expected output:

```
NAME                      PHASE                  TYPE         DBNAME
my-postgres               Succeeded              postgresql   mydb
edb-201                   Succeeded              postgresql   db-201
edb-400                   InvalidConfiguration   postgresql   db-400
edb-401                   BackingOff             postgresql   db-401
edb-403                   InvalidConfiguration   postgresql   db-403
edb-409                   InvalidConfiguration   postgresql   db-409
edb-500                   BackingOff             postgresql   db-500
missing-secret-test       BackingOff             postgresql   ghostdb
secret-missing-key-test   BackingOff             postgresql   mydb
edb-secret-empty-key      BackingOff             postgresql   mydb-empty-key
```

| CR file | `dbName` | Mock response | Expected Phase | `Ready.reason` | `Stalled` |
|---|---|---|---|---|---|
| `edb-with-secret.yaml` | `mydb` | 200 (default) | `Succeeded` | `Registered` | `False` |
| `edb-201-created.yaml` | `db-201` | 201 | `Succeeded` | `Registered` | `False` |
| `edb-400-bad-request.yaml` | `db-400` | 400 | `InvalidConfiguration` | `AggregatorRejected` | `True` |
| `edb-401-unauthorized.yaml` | `db-401` | 401 | `BackingOff` | `Unauthorized` | `False` |
| `edb-403-forbidden.yaml` | `db-403` | 403 | `InvalidConfiguration` | `AggregatorRejected` | `True` |
| `edb-409-conflict.yaml` | `db-409` | 409 | `InvalidConfiguration` | `AggregatorRejected` | `True` |
| `edb-500-server-error.yaml` | `db-500` | 500 | `BackingOff` | `AggregatorError` | `False` |
| `edb-missing-secret.yaml` | `ghostdb` | — (no HTTP call) | `BackingOff` | `SecretError` | `False` |
| `edb-secret-missing-key.yaml` | `mydb` | — (no HTTP call) | `BackingOff` | `SecretError` | `False` |
| `edb-secret-empty-key.yaml` | `mydb-empty-key` | — (no HTTP call) | `BackingOff` | `SecretError` | `False` |

### DbPolicy

The mock routes `POST /api/declarations/v1/apply` requests by `metadata.microserviceName` from the request body.
Rules are defined in `apply-rules.json` inside the `aggregator-mock-rules` ConfigMap.

Expected output:

```
NAME                      PHASE
dp-success                Succeeded
dp-400                    InvalidConfiguration
dp-401                    BackingOff
dp-500                    BackingOff
dp-invalid-empty-spec     InvalidConfiguration
```

| CR file | `microserviceName` | Mock response | Expected Phase | `Ready.reason` | `Stalled` |
|---|---|---|---|---|---|
| `dbpolicy-success.yaml` | `svc-ok` | 200 (default) | `Succeeded` | `PolicyApplied` | `False` |
| `dbpolicy-400.yaml` | `svc-400` | 400 | `InvalidConfiguration` | `AggregatorRejected` | `True` |
| `dbpolicy-401.yaml` | `svc-401` | 401 | `BackingOff` | `Unauthorized` | `False` |
| `dbpolicy-500.yaml` | `svc-500` | 500 | `BackingOff` | `AggregatorError` | `False` |
| `dbpolicy-invalid-empty-spec.yaml` | `svc-invalid-empty-spec` | — (no HTTP call) | `InvalidConfiguration` | `InvalidSpec` | `True` |

> **Note:** `dbpolicy-invalid-empty-spec.yaml` exercises controller-level pre-flight validation — a case the CRD schema cannot enforce: both `services` and `policy` are absent (each is `+optional` individually, but the controller requires at least one).

### DatabaseDeclaration

The mock routes `POST /api/declarations/v1/apply` requests by `metadata.microserviceName`.
For `DatabaseDeclaration` the default response is HTTP 202 (async) with
`trackingId = "tracking-<microserviceName>"`. Poll responses are controlled by
`poll-rules.json` (keyed by `trackingId`); missing rule → `COMPLETED`.

Expected output (`kubectl get databasedeclaration -n test-ns`):

```
NAME                    PHASE                  TYPE
dd-success-sync         Succeeded              postgresql
dd-success-async        Succeeded              postgresql
dd-400-bad-request      InvalidConfiguration   postgresql
dd-401-unauthorized     BackingOff             postgresql
dd-500-server-error     BackingOff             postgresql
dd-poll-failed          InvalidConfiguration   postgresql
dd-poll-terminated      BackingOff             postgresql
dd-invalid-lazy-clone   InvalidConfiguration   postgresql
dd-invalid-no-source    InvalidConfiguration   postgresql
```

| CR file | `microserviceName` | Apply response | Poll response | Expected Phase | `Ready.reason` | `Stalled` |
|---|---|---|---|---|---|---|
| `dd-success-sync.yaml` | `dd-svc-sync` | 200 (rule) | — | `Succeeded` | `DatabaseProvisioned` | `False` |
| `dd-success-async.yaml` | `dd-svc-async` | 202 (default) | `COMPLETED` (default) | `Succeeded` | `DatabaseProvisioned` | `False` |
| `dd-400-bad-request.yaml` | `dd-svc-400` | 400 (rule) | — | `InvalidConfiguration` | `AggregatorRejected` | `True` |
| `dd-401-unauthorized.yaml` | `dd-svc-401` | 401 (rule) | — | `BackingOff` | `Unauthorized` | `False` |
| `dd-500-server-error.yaml` | `dd-svc-500` | 500 (rule) | — | `BackingOff` | `AggregatorError` | `False` |
| `dd-poll-failed.yaml` | `dd-poll-failed` | 202 (default) | `FAILED` (poll rule) | `InvalidConfiguration` | `AggregatorRejected` | `True` |
| `dd-poll-terminated.yaml` | `dd-poll-terminated` | 202 (default) | `TERMINATED` (poll rule) | `BackingOff` (cycling) | `OperationTerminated` | `False` |
| `dd-invalid-lazy-clone.yaml` | `dd-svc-lazy` | — (no HTTP call) | — | `InvalidConfiguration` | `InvalidSpec` | `True` |
| `dd-invalid-no-source.yaml` | `dd-svc-nosrc` | — (no HTTP call) | — | `InvalidConfiguration` | `InvalidSpec` | `True` |

`dd-invalid-lazy-clone` and `dd-invalid-no-source` exercise controller-level pre-flight checks
(`lazy=true` + `approach=clone` and `approach=clone` without `sourceClassifier` respectively).
These are cross-field constraints that the CRD schema cannot enforce.

### Changing rules without rebuilding

Edit the ConfigMap directly and restart the pod:

```bash
kubectl edit configmap aggregator-mock-rules -n dbaas-system
kubectl rollout restart deployment/aggregator-mock -n dbaas-system
```

Override the global fallback code for all unmatched requests:

```bash
kubectl set env deployment/aggregator-mock -n dbaas-system MOCK_RESPONSE_CODE=400
```

## Useful commands

```bash
# Operator logs
kubectl logs -n dbaas-system deployment/dbaas-operator -f

# aggregator-mock logs (shows incoming requests and rule matches)
kubectl logs -n dbaas-system deployment/aggregator-mock -f

# Full CR status — ExternalDatabase
kubectl get externaldatabase -n test-ns edb-401 -o yaml

# Full CR status — DbPolicy
kubectl get dbpolicy -n test-ns dp-401 -o yaml

# Full CR status — DatabaseDeclaration
kubectl get databasedeclaration -n test-ns dd-400-bad-request -o yaml

# Events for a CR
kubectl get events -n test-ns --field-selector involvedObject.name=dp-401
kubectl get events -n test-ns --field-selector involvedObject.name=dd-poll-failed

# Reset a CR — delete and reapply
kubectl delete externaldatabase -n test-ns edb-401
kubectl apply -f hack/test-resources/edb-401-unauthorized.yaml -n test-ns

kubectl delete dbpolicy -n test-ns dp-401
kubectl apply -f hack/test-resources/dbpolicy-401.yaml -n test-ns

kubectl delete databasedeclaration -n test-ns dd-401-unauthorized
kubectl apply -f hack/test-resources/dd-401-unauthorized.yaml -n test-ns

# Redeploy all test resources at once (OperatorBinding is preserved — it has a deletion-protection finalizer)
kubectl delete externaldatabase,dbpolicy,databasedeclaration -n test-ns --all
kubectl apply -f hack/test-resources/ -n test-ns
```

## Tear down

```bash
./hack/kind-down.sh
```

Or delete the cluster manually:

```bash
kind delete cluster --name dbaas
```

## File structure

```
hack/
├── kind-up.sh                  # spins up the environment from scratch
├── kind-down.sh                # deletes the kind cluster
├── aggregator-mock/
│   ├── main.go                 # HTTP stub for dbaas-aggregator
│   └── Dockerfile
├── k8s/
│   ├── mock-aggregator.yaml    # Namespace + Secret + ConfigMap + Deployment + Service for aggregator-mock
│   └── operator.yaml           # ServiceAccount + RBAC + Deployment for the operator
└── test-resources/
    ├── namespace.yaml               # namespace test-ns
    ├── operatorbinding.yaml         # OperatorBinding/registration — claims test-ns (location=dbaas-system)
    ├── secret.yaml                  # Secret pg-credentials (and pg-credentials-incomplete)
    │
    │   # ExternalDatabase test CRs
    ├── edb-with-secret.yaml         # EDB — 200 OK (happy path, uses secret)
    ├── edb-201-created.yaml         # EDB — 201 Created → Succeeded
    ├── edb-400-bad-request.yaml     # EDB — 400 → InvalidConfiguration
    ├── edb-401-unauthorized.yaml    # EDB — 401 → BackingOff
    ├── edb-403-forbidden.yaml       # EDB — 403 → InvalidConfiguration
    ├── edb-409-conflict.yaml        # EDB — 409 → InvalidConfiguration
    ├── edb-500-server-error.yaml    # EDB — 500 → BackingOff
    ├── edb-missing-secret.yaml      # EDB — Secret does not exist → BackingOff
    ├── edb-secret-missing-key.yaml  # EDB — Secret exists, key missing → BackingOff
    ├── edb-secret-empty-key.yaml    # EDB — Secret exists, key present but empty → BackingOff (SecretError, not Unauthorized)
    │
    │   # DbPolicy test CRs
    ├── dbpolicy-success.yaml                # DbPolicy — 200 OK → Succeeded (reason: PolicyApplied)
    ├── dbpolicy-400.yaml                    # DbPolicy — 400 → InvalidConfiguration
    ├── dbpolicy-401.yaml                    # DbPolicy — 401 → BackingOff
    ├── dbpolicy-500.yaml                    # DbPolicy — 500 → BackingOff
    ├── dbpolicy-invalid-empty-spec.yaml     # DbPolicy — pre-flight: no services/policy → InvalidConfiguration
    │
    │   # DatabaseDeclaration test CRs
    ├── dd-success-sync.yaml                 # DD — apply-rule 200 (sync) → Succeeded
    ├── dd-success-async.yaml                # DD — 202 default → poll COMPLETED → Succeeded
    ├── dd-400-bad-request.yaml              # DD — apply-rule 400 → InvalidConfiguration
    ├── dd-401-unauthorized.yaml             # DD — apply-rule 401 → BackingOff
    ├── dd-500-server-error.yaml             # DD — apply-rule 500 → BackingOff
    ├── dd-poll-failed.yaml                  # DD — 202 → poll FAILED → InvalidConfiguration
    ├── dd-poll-terminated.yaml              # DD — 202 → poll TERMINATED → BackingOff (resubmits automatically)
    ├── dd-invalid-lazy-clone.yaml           # DD — pre-flight: lazy=true + clone → InvalidConfiguration
    └── dd-invalid-no-source.yaml            # DD — pre-flight: clone without sourceClassifier → InvalidConfiguration
```
