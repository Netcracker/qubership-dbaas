# Local development — kind

A quick way to spin up a full test environment for manual testing of `dbaas-operator`
in a local [kind](https://kind.sigs.k8s.io/) cluster.

## What gets deployed

| Resource | Namespace | Description |
|---|---|---|
| `aggregator-mock` Deployment + Service | `dbaas-system` | HTTP stub for dbaas-aggregator |
| `aggregator-mock-rules` ConfigMap | `dbaas-system` | Per-dbName response code rules |
| `dbaas-operator` Deployment | `dbaas-system` | Operator with RBAC |
| Namespace `test-ns` | — | Working namespace for CRs |
| Secret `pg-credentials` | `test-ns` | Test credentials for ExternalDatabaseDeclaration |

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

The aggregator-mock routes requests by `dbName` from the request body.
Rules are defined in the `aggregator-mock-rules` ConfigMap (`hack/k8s/mock-aggregator.yaml`).

Apply all test CRs at once and observe their phases:

```bash
kubectl apply -f hack/test-resources/ -n test-ns
kubectl get externaldatabasedeclaration -n test-ns
```

Expected output:

```
NAME                  PHASE                  TYPE         DBNAME
my-postgres           Updated                postgresql   mydb
edb-201               Updated                postgresql   db-201
edb-400               InvalidConfiguration   postgresql   db-400
edb-401               BackingOff             postgresql   db-401
edb-403               InvalidConfiguration   postgresql   db-403
edb-409               InvalidConfiguration   postgresql   db-409
edb-500               BackingOff             postgresql   db-500
missing-secret-test   BackingOff             postgresql   ghostdb
```

### CR reference table

| CR file | dbName | Mock response | Expected Phase | Condition reason |
|---|---|---|---|---|
| `edb-with-secret.yaml` | `mydb` | 200 (default) | `Updated` | `Registered` |
| `edb-201-created.yaml` | `db-201` | 201 | `Updated` | `Registered` |
| `edb-400-bad-request.yaml` | `db-400` | 400 | `InvalidConfiguration` | `AggregatorRejected` |
| `edb-401-unauthorized.yaml` | `db-401` | 401 | `BackingOff` | `Unauthorized` |
| `edb-403-forbidden.yaml` | `db-403` | 403 | `InvalidConfiguration` | `AggregatorRejected` |
| `edb-409-conflict.yaml` | `db-409` | 409 | `InvalidConfiguration` | `AggregatorRejected` |
| `edb-500-server-error.yaml` | `db-500` | 500 | `BackingOff` | `AggregatorError` |
| `edb-missing-secret.yaml` | `ghostdb` | — (no HTTP call) | `BackingOff` | `SecretError` |

### Changing rules without rebuilding

Edit the ConfigMap directly and restart the pod:

```bash
kubectl edit configmap aggregator-mock-rules -n dbaas-system
kubectl rollout restart deployment/aggregator-mock -n dbaas-system
```

Override the global fallback code for all unmatched dbNames:

```bash
kubectl set env deployment/aggregator-mock -n dbaas-system MOCK_RESPONSE_CODE=400
```

## Useful commands

```bash
# Operator logs
kubectl logs -n dbaas-system deployment/dbaas-operator -f

# aggregator-mock logs (shows incoming requests and rule matches)
kubectl logs -n dbaas-system deployment/aggregator-mock -f

# Full CR status
kubectl get externaldatabasedeclaration -n test-ns edb-401 -o yaml

# Reset a CR — delete and reapply
kubectl delete externaldatabasedeclaration -n test-ns edb-401
kubectl apply -f hack/test-resources/edb-401-unauthorized.yaml
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
│   ├── mock-aggregator.yaml    # Namespace + ConfigMap + Deployment + Service for aggregator-mock
│   └── operator.yaml           # ServiceAccount + RBAC + Deployment for the operator
└── test-resources/
    ├── namespace.yaml               # namespace test-ns
    ├── secret.yaml                  # Secret pg-credentials
    ├── edb-with-secret.yaml         # ExternalDatabaseDeclaration — 200 OK (happy path)
    ├── edb-201-created.yaml         # ExternalDatabaseDeclaration — 201 Created
    ├── edb-400-bad-request.yaml     # ExternalDatabaseDeclaration — 400 InvalidConfiguration
    ├── edb-401-unauthorized.yaml    # ExternalDatabaseDeclaration — 401 BackingOff
    ├── edb-403-forbidden.yaml       # ExternalDatabaseDeclaration — 403 InvalidConfiguration
    ├── edb-409-conflict.yaml        # ExternalDatabaseDeclaration — 409 InvalidConfiguration
    ├── edb-500-server-error.yaml    # ExternalDatabaseDeclaration — 500 BackingOff
    └── edb-missing-secret.yaml      # ExternalDatabaseDeclaration — BackingOff (Secret missing)
```
