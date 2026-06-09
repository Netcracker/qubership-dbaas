# Local development — kind

A quick way to spin up a full test environment for manual testing of `dbaas-operator`
in a local [kind](https://kind.sigs.k8s.io/) cluster.

## What gets deployed

| Resource | Namespace | Description |
|---|---|---|
| `aggregator-mock` Deployment + Service | `dbaas-system` | HTTP stub for dbaas-aggregator |
| `aggregator-mock-rules` ConfigMap | `dbaas-system` | Per-request routing rules: `rules.json` (EDB by `dbName`), `apply-rules.json` (DatabaseAccessPolicy/InternalDatabase by `microserviceName`), `poll-rules.json` (InternalDatabase async poll by `trackingId`) |
| `dbaas-operator` Deployment | `dbaas-system` | Operator with RBAC — watches all namespaces cluster-wide |
| Namespace `test-ns` | — | Working namespace for CRs |
| `NamespaceBinding/binding` | `test-ns` | Claims `test-ns` for this operator (operatorNamespace=`dbaas-system`) |
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
./dev/kind-up.sh
```

Creates a cluster named `dbaas`. To use a different name:

```bash
KIND_CLUSTER=my-cluster ./dev/kind-up.sh
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

**Namespace ownership** is now declared through `NamespaceBinding` rather than a
`--watch-namespaces` flag.  The operator runs cluster-wide but only reconciles
resources in namespaces where a `NamespaceBinding` named `binding` exists
and its `spec.operatorNamespace` matches the operator's own namespace (`CLOUD_NAMESPACE`).

`dev/test-resources/namespacebinding.yaml` (included in the directory) creates
the binding for `test-ns` automatically when you apply the full directory.

Apply all test CRs at once and observe their phases:

```bash
kubectl apply -f dev/test-resources/ -n test-ns
kubectl get namespacebinding -n test-ns       # binding   dbaas-system
kubectl get externaldatabase -n test-ns
kubectl get databaseaccesspolicy -n test-ns
kubectl get internaldatabase -n test-ns
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

### DatabaseAccessPolicy

The mock routes `POST /api/declarations/v1/apply` requests by `metadata.microserviceName` from the request body.
Rules are defined in `apply-rules.json` inside the `aggregator-mock-rules` ConfigMap.

Expected output:

```
NAME                      PHASE
dap-success                Succeeded
dap-400                    InvalidConfiguration
dap-401                    BackingOff
dap-500                    BackingOff
dap-invalid-empty-spec     InvalidConfiguration
```

| CR file | `microserviceName` | Mock response | Expected Phase | `Ready.reason` | `Stalled` |
|---|---|---|---|---|---|
| `dap-success.yaml` | `svc-ok` | 200 (default) | `Succeeded` | `PolicyApplied` | `False` |
| `dap-400.yaml` | `svc-400` | 400 | `InvalidConfiguration` | `AggregatorRejected` | `True` |
| `dap-401.yaml` | `svc-401` | 401 | `BackingOff` | `Unauthorized` | `False` |
| `dap-500.yaml` | `svc-500` | 500 | `BackingOff` | `AggregatorError` | `False` |
| `dap-invalid-empty-spec.yaml` | `svc-invalid-empty-spec` | — (no HTTP call) | `InvalidConfiguration` | `InvalidSpec` | `True` |

> **Note:** `dap-invalid-empty-spec.yaml` exercises controller-level pre-flight validation — a case the CRD schema cannot enforce: both `services` and `policy` are absent (each is `+optional` individually, but the controller requires at least one).

### InternalDatabase

The mock routes `POST /api/declarations/v1/apply` requests by `metadata.microserviceName`.
For `InternalDatabase` the default response is HTTP 202 (async) with
`trackingId = "tracking-<microserviceName>"`. Poll responses are controlled by
`poll-rules.json` (keyed by `trackingId`); missing rule → `COMPLETED`.

Expected output (`kubectl get internaldatabase -n test-ns`):

```
NAME                    PHASE                  TYPE
idb-success-sync         Succeeded              postgresql
idb-success-async        Succeeded              postgresql
idb-custom-keys          Succeeded              postgresql
idb-400-bad-request      InvalidConfiguration   postgresql
idb-401-unauthorized     BackingOff             postgresql
idb-500-server-error     BackingOff             postgresql
idb-poll-failed          InvalidConfiguration   postgresql
idb-poll-terminated      BackingOff             postgresql
idb-invalid-lazy-clone   InvalidConfiguration   postgresql
idb-invalid-no-source    InvalidConfiguration   postgresql
```

| CR file | `microserviceName` | Apply response | Poll response | Expected Phase | `Ready.reason` | `Stalled` |
|---|---|---|---|---|---|---|
| `idb-success-sync.yaml` | `idb-svc-sync` | 200 (rule) | — | `Succeeded` | `DatabaseProvisioned` | `False` |
| `idb-success-async.yaml` | `idb-svc-async` | 202 (default) | `COMPLETED` (default) | `Succeeded` | `DatabaseProvisioned` | `False` |
| `idb-custom-keys.yaml` | `idb-svc-custom-keys` | 202 (default) | `COMPLETED` (default) | `Succeeded` | `DatabaseProvisioned` | `False` |
| `idb-400-bad-request.yaml` | `idb-svc-400` | 400 (rule) | — | `InvalidConfiguration` | `AggregatorRejected` | `True` |
| `idb-401-unauthorized.yaml` | `idb-svc-401` | 401 (rule) | — | `BackingOff` | `Unauthorized` | `False` |
| `idb-500-server-error.yaml` | `idb-svc-500` | 500 (rule) | — | `BackingOff` | `AggregatorError` | `False` |
| `idb-poll-failed.yaml` | `idb-poll-failed` | 202 (default) | `FAILED` (poll rule) | `InvalidConfiguration` | `AggregatorRejected` | `True` |
| `idb-poll-terminated.yaml` | `idb-poll-terminated` | 202 (default) | `TERMINATED` (poll rule) | `BackingOff` (cycling) | `OperationTerminated` | `False` |
| `idb-invalid-lazy-clone.yaml` | `idb-svc-lazy` | — (no HTTP call) | — | `InvalidConfiguration` | `InvalidSpec` | `True` |
| `idb-invalid-no-source.yaml` | `idb-svc-nosrc` | — (no HTTP call) | — | `InvalidConfiguration` | `InvalidSpec` | `True` |

`idb-invalid-lazy-clone` and `idb-invalid-no-source` exercise controller-level pre-flight checks
(`lazy=true` + `approach=clone` and `approach=clone` without `sourceClassifier` respectively).
These are cross-field constraints that the CRD schema cannot enforce.

`idb-custom-keys` exercises the `classifier.customKeys` field, which accepts any JSON value type
(string, number, boolean, nested object). The operator converts these to the aggregator wire format
under `classifierConfig.customKeys`. Check the mock logs to verify all four types are transmitted
correctly:

```bash
kubectl logs -n dbaas-system deployment/dbaas-aggregator | grep -A 10 "idb-svc-custom-keys"
```

### Changing rules without rebuilding

Edit the ConfigMap directly and restart the pod:

```bash
kubectl edit configmap aggregator-mock-rules -n dbaas-system
kubectl rollout restart deployment/dbaas-aggregator -n dbaas-system
```

Override the global fallback code for all unmatched requests:

```bash
kubectl set env deployment/dbaas-aggregator -n dbaas-system MOCK_RESPONSE_CODE=400
```

## Useful commands

```bash
# Operator logs
kubectl logs -n dbaas-system deployment/dbaas-operator -f

# aggregator-mock logs (shows incoming requests and rule matches)
kubectl logs -n dbaas-system deployment/dbaas-aggregator -f

# Full CR status — ExternalDatabase
kubectl get externaldatabase -n test-ns edb-401 -o yaml

# Full CR status — DatabaseAccessPolicy
kubectl get databaseaccesspolicy -n test-ns dap-401 -o yaml

# Full CR status — InternalDatabase
kubectl get internaldatabase -n test-ns idb-400-bad-request -o yaml

# Events for a CR
kubectl get events -n test-ns --field-selector involvedObject.name=dap-401
kubectl get events -n test-ns --field-selector involvedObject.name=idb-poll-failed

# Reset a CR — delete and reapply
kubectl delete externaldatabase -n test-ns edb-401
kubectl apply -f dev/test-resources/edb-401-unauthorized.yaml -n test-ns

kubectl delete databaseaccesspolicy -n test-ns dap-401
kubectl apply -f dev/test-resources/dap-401.yaml -n test-ns

kubectl delete internaldatabase -n test-ns idb-401-unauthorized
kubectl apply -f dev/test-resources/idb-401-unauthorized.yaml -n test-ns

# Redeploy all test resources at once (NamespaceBinding is preserved — it has a deletion-protection finalizer)
kubectl delete externaldatabase,databaseaccesspolicy,internaldatabase -n test-ns --all
kubectl apply -f dev/test-resources/ -n test-ns
```

## Tear down

```bash
./dev/kind-down.sh
```

Or delete the cluster manually:

```bash
kind delete cluster --name dbaas
```

## File structure

```
dev/
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
    ├── namespacebinding.yaml        # NamespaceBinding/binding — claims test-ns (operatorNamespace=dbaas-system)
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
    │   # DatabaseAccessPolicy test CRs
    ├── dap-success.yaml                # DatabaseAccessPolicy — 200 OK → Succeeded (reason: PolicyApplied)
    ├── dap-400.yaml                    # DatabaseAccessPolicy — 400 → InvalidConfiguration
    ├── dap-401.yaml                    # DatabaseAccessPolicy — 401 → BackingOff
    ├── dap-500.yaml                    # DatabaseAccessPolicy — 500 → BackingOff
    ├── dap-invalid-empty-spec.yaml     # DatabaseAccessPolicy — pre-flight: no services/policy → InvalidConfiguration
    │
    │   # InternalDatabase test CRs
    ├── idb-success-sync.yaml                 # IDB — apply-rule 200 (sync) → Succeeded
    ├── idb-success-async.yaml                # IDB — 202 default → poll COMPLETED → Succeeded
    ├── idb-custom-keys.yaml                  # IDB — classifier.customKeys with string/number/boolean/object values → Succeeded
    ├── idb-400-bad-request.yaml              # IDB — apply-rule 400 → InvalidConfiguration
    ├── idb-401-unauthorized.yaml             # IDB — apply-rule 401 → BackingOff
    ├── idb-500-server-error.yaml             # IDB — apply-rule 500 → BackingOff
    ├── idb-poll-failed.yaml                  # IDB — 202 → poll FAILED → InvalidConfiguration
    ├── idb-poll-terminated.yaml              # IDB — 202 → poll TERMINATED → BackingOff (resubmits automatically)
    ├── idb-invalid-lazy-clone.yaml           # IDB — pre-flight: lazy=true + clone → InvalidConfiguration
    └── idb-invalid-no-source.yaml           # IDB — pre-flight: clone without sourceClassifier → InvalidConfiguration
```
