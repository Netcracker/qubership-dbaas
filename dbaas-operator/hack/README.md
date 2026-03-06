# Local development — kind

A quick way to spin up a full test environment for manual testing of `dbaas-operator`
in a local [kind](https://kind.sigs.k8s.io/) cluster.

## What gets deployed

| Resource | Namespace | Description |
|---|---|---|
| `aggregator-mock` Deployment + Service | `dbaas-system` | HTTP stub for dbaas-aggregator |
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

### Test 1 — happy path

Secret exists, aggregator-mock returns 200.

```bash
kubectl apply -f hack/test-resources/edb-with-secret.yaml
kubectl get externaldatabasedeclaration -n test-ns my-postgres -w
```

Expected result:

```
NAME          PHASE     TYPE         DBNAME
my-postgres   Updated   postgresql   mydb
```

### Test 2 — BackingOff (Secret missing)

```bash
kubectl apply -f hack/test-resources/edb-missing-secret.yaml
kubectl get externaldatabasedeclaration -n test-ns missing-secret-test -w
```

Expected result: `PHASE = BackingOff`. The operator retries with exponential backoff.

### Test 3 — InvalidConfiguration (4xx from aggregator)

On a 4xx response the operator sets `InvalidConfiguration` and does **not** requeue
(a retry only happens when the spec changes).

```bash
# Configure mock to return 400
kubectl set env deployment/aggregator-mock -n dbaas-system MOCK_RESPONSE_CODE=400

# Recreate the CR
kubectl delete externaldatabasedeclaration -n test-ns my-postgres --ignore-not-found
kubectl apply -f hack/test-resources/edb-with-secret.yaml

kubectl get externaldatabasedeclaration -n test-ns my-postgres
# Expected: PHASE = InvalidConfiguration

# Restore mock to 200
kubectl set env deployment/aggregator-mock -n dbaas-system MOCK_RESPONSE_CODE=200
```

## Useful commands

```bash
# Operator logs
kubectl logs -n dbaas-system deployment/dbaas-operator -f

# aggregator-mock logs (shows incoming requests)
kubectl logs -n dbaas-system deployment/aggregator-mock -f

# Full CR status
kubectl get externaldatabasedeclaration -n test-ns my-postgres -o yaml

# Reset a CR — delete and reapply
kubectl delete externaldatabasedeclaration -n test-ns my-postgres
kubectl apply -f hack/test-resources/edb-with-secret.yaml
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
│   ├── mock-aggregator.yaml    # Deployment + Service for aggregator-mock
│   └── operator.yaml           # ServiceAccount + RBAC + Deployment for the operator
└── test-resources/
    ├── namespace.yaml           # namespace test-ns
    ├── secret.yaml              # Secret pg-credentials
    ├── edb-with-secret.yaml     # ExternalDatabaseDeclaration (happy path)
    └── edb-missing-secret.yaml  # ExternalDatabaseDeclaration (BackingOff)
```
