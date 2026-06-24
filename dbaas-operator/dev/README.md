# Local development — kind

A quick way to spin up a full test environment for manual testing of `dbaas-operator`
in a local [kind](https://kind.sigs.k8s.io/) cluster.

## What gets deployed

| Resource | Namespace | Description |
|---|---|---|
| `aggregator-mock` Deployment + Service | `dbaas-system` | HTTP stub for dbaas-aggregator |
| `aggregator-mock-rules` ConfigMap | `dbaas-system` | Per-request routing rules: `rules.json` (EDB by `dbName`), `apply-rules.json` (DatabaseAccessPolicy/InternalDatabase by `microserviceName`), `poll-rules.json` (InternalDatabase async poll by `trackingId`), `changed.json` (rotation feed), `get-by-classifier.json` (DatabaseSecretClaim connection properties by `originService`) |
| `dbaas-operator` Deployment | `dbaas-system` | Operator — watches CRs cluster-wide; Secret access is **namespaced** (no cluster-wide Secret RBAC) |
| `dbaas-operator-aggregator-credentials` Secret | `dbaas-system` | Operator's own Basic Auth credentials (`username`/`password`) read in Basic mode |
| `Role`/`RoleBinding` `dbaas-operator` | `dbaas-system` | Operator's own-namespace RBAC (leases, events, permanentbalancingrules) — **no Secret access** |
| Namespace `test-ns` | — | Working namespace for CRs |
| `NamespaceBinding/binding` | `test-ns` | Claims `test-ns` for this operator (operatorNamespace=`dbaas-system`) |
| `Role`/`RoleBinding` `dbaas-operator-secrets` | `test-ns` | **Per-namespace Secret access for the operator** — required because the operator has no cluster-wide Secret RBAC |
| Secret `pg-credentials` | `test-ns` | Test credentials for ExternalDatabase |

`kind-up.sh` deploys all of the above. The test CRs (ExternalDatabase / DatabaseAccessPolicy /
InternalDatabase) are applied separately — see [Test scenarios](#test-scenarios).

## Prerequisites

| Tool | Version |
|---|---|
| [kind](https://kind.sigs.k8s.io/docs/user/quick-start/#installation) | v0.20+ |
| [kubectl](https://kubernetes.io/docs/tasks/tools/) | v1.28+ |
| [docker](https://docs.docker.com/get-docker/) | 20+ |
| make | any |
| envsubst (gettext) | any |

## Start the environment

```bash
./dev/kind-up.sh
```

Creates a cluster named `dbaas`. To use a different name:

```bash
KIND_CLUSTER=my-cluster ./dev/kind-up.sh
```

### Aggregator auth mode

By default the operator authenticates to the aggregator-mock with **HTTP Basic Auth**
as the `dbaas-operator` user (matching the production default, `KUBERNETES_M2M_ENABLED=false`).
The credentials come from the operator's own `dbaas-operator-aggregator-credentials` Secret
(plain `username`/`password` keys); the mock does not validate the value. To exercise the
**M2M** path (projected SA Bearer token) instead:

```bash
KUBERNETES_M2M_ENABLED=true ./dev/kind-up.sh
```

The mock accepts either Basic Auth or a Bearer token, so no mock reconfiguration is needed.

The script runs these steps in order:

1. `kind create cluster` — creates the cluster (skipped if it already exists)
2. `make install` — installs CRDs
3. `docker build` — builds `dbaas-operator:dev` and `aggregator-mock:dev` images
4. `kind load docker-image` — loads images into the cluster (no registry required)
5. `kubectl apply` — deploys aggregator-mock and the operator into `dbaas-system`
6. `kubectl apply` — creates namespace `test-ns`, its `NamespaceBinding`, the namespaced Secret **`Role`+`RoleBinding`** (`secret-rbac.yaml`), and secret `pg-credentials`
7. Waits for `rollout status` on both deployments

## Test scenarios

**Namespace ownership** is now declared through `NamespaceBinding` rather than a
`--watch-namespaces` flag.  The operator runs cluster-wide but only reconciles
resources in namespaces where a `NamespaceBinding` named `binding` exists
and its `spec.operatorNamespace` matches the operator's own namespace (`CLOUD_NAMESPACE`).

`dev/test-resources/namespacebinding.yaml` (included in the directory) creates
the binding for `test-ns` automatically when you apply the full directory.

### Secret access is namespaced

The operator deploy holds **no Secret RBAC at all** — not cluster-wide, and not even in its own
namespace (it reads its own aggregator credentials from a mounted volume, not the API). It can
read/write Secrets only in a namespace where a `Role` + `RoleBinding` grant its ServiceAccount
(`dbaas-operator` in `dbaas-system`) access. So **every namespace it works in must have such a
`Role` + `RoleBinding` installed alongside its `NamespaceBinding`** — otherwise `ExternalDatabase`
(reads a referenced credential Secret) and `DatabaseSecretClaim` (creates the owned Secret) fail
with `forbidden`.

`dev/test-resources/secret-rbac.yaml` provisions this for `test-ns`, and `kind-up.sh` applies it
next to the `NamespaceBinding` — exactly as a real namespace onboarding would. The granted verbs
are the minimum the operator uses: `get, create, update, patch` (no `list`/`watch` — there is no
Secret informer; no `delete` — owned Secrets are garbage-collected via ownerReferences). See
`config/samples/namespaced-secret-rbac.yaml` for the production-shaped template.

> The operator is deployed **without a self-`NamespaceBinding`** for its own namespace. The own-namespace
> `Role` above still grants Secret access (the operator reads its own aggregator-credentials Secret),
> but the operator only reconciles CRs in a namespace once a `NamespaceBinding` for it exists — create
> one for `dbaas-system` too if you want the operator to manage CRs in its own namespace.

Apply all test CRs at once and observe their phases:

```bash
kubectl apply -f dev/test-resources/ -n test-ns
kubectl get namespacebinding -n test-ns       # binding   dbaas-system
kubectl get externaldatabase -n test-ns
kubectl get databaseaccesspolicy -n test-ns
kubectl get internaldatabase -n test-ns
kubectl get databasesecretclaim -n test-ns
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

### DatabaseSecretClaim (and rotation)

`DatabaseSecretClaim` exercises two mock endpoints:

- `POST /api/v3/dbaas/{ns}/databases/get-by-classifier/{type}` — returns the connection
  properties for the requested database. The mock keys the response by `originService`
  (the CR's `app.kubernetes.io/name` label) from `get-by-classifier.json`; an unmatched
  service gets a synthetic default property set.
- `GET /api/v3/dbaas/databases/changed` — the rotation feed (`changed.json`). The
  since-less **seed** call reports no history (`highWaterMark=null`), so the operator's
  rotation poller seeds at epoch and then **replays** the configured changed entries once,
  simulating rotations that happen after the operator starts. Each entry wakes every
  `DatabaseSecretClaim` whose `(classifier, type)` matches, which re-fetches via
  get-by-classifier. Once the poller's cursor passes the newest entry the feed returns
  nothing and the poller converges.

Expected output (`kubectl get databasesecretclaim -n test-ns`):

```
NAME          PHASE       READY
dsc-success   Succeeded   SecretUpToDate
```

| CR file | `app.kubernetes.io/name` | Flow | Expected Phase | `Ready.reason` |
|---|---|---|---|---|
| `dsc-success.yaml` | `secret-svc` | get-by-classifier 200 → Secret created; rotation fan-out → re-fetch (no-op) | `Succeeded` | `SecretCreated`, then `SecretUpToDate` after the rotation fan-out |
| `dsc-extra-keys.yaml` | `extra-svc` | same flow with `spec.classifier.extraKeys` (arbitrary top-level fields, flattened on the wire); the `changed.json` entry's FLAT classifier carries `region` and the poller must reverse-map it to match the CR | `Succeeded` | `SecretCreated`, then `SecretUpToDate` after the rotation fan-out |

The materialized Secret `dsc-success-secret` holds `connectionProperties.json` (the
get-by-classifier response) and `metadata.json` (classifier, type, userRole, id, name).

**Watch the rotation pipeline:**

```bash
# Poller seed + fan-out (leader-only)
kubectl logs -n dbaas-system deployment/dbaas-operator | grep -i "rotation poller"
#   ... Rotation poller seeded cursor at epoch (no rotations recorded yet)
#   ... Rotation poller processed changes count=1 cursor=(2026-06-18 10:00:00 +0000 UTC,1111...)

# The fan-out stamps the rotation-trigger annotation on the matched CR
kubectl get databasesecretclaim dsc-success -n test-ns \
  -o jsonpath='{.metadata.annotations.dbaas\.netcracker\.com/rotation-trigger}'

# Mock side — changed feed + get-by-classifier calls
kubectl logs -n dbaas-system deployment/dbaas-aggregator | grep -iE "changed feed|get-by-classifier"
```

**Force a real `SecretRotated`:** edit the password under `secret-svc` in
`get-by-classifier.json` (the `aggregator-mock-rules` ConfigMap) and restart the mock —
the next safety-net re-poll (or a fresh rotation entry in `changed.json`) re-fetches the
changed credentials and writes the Secret, emitting `SecretRotated`:

```bash
kubectl edit configmap aggregator-mock-rules -n dbaas-system   # change the password
kubectl rollout restart deployment/dbaas-aggregator -n dbaas-system
```

#### extraKeys end-to-end (`dev/e2e-extra-keys.sh`)

`spec.classifier.extraKeys` lets a CR carry arbitrary identity fields that the
operator **flattens onto the top level** of the wire classifier (unlike
`customKeys`, which stays nested). The CR-side envelope and the aggregator's flat
form therefore differ — `dev/e2e-extra-keys.sh` checks they line up at every
boundary against a live cluster:

```bash
./dev/kind-up.sh            # if not already up
./dev/e2e-extra-keys.sh
```

It applies `dsc-extra-keys.yaml` and asserts:

1. the CR reaches `Succeeded` (get-by-classifier matched, Secret created);
2. the Secret's `metadata.json` `.classifier` carries `region` **flat** on the top
   level with no `extraKeys` envelope;
3. the rotation poller stamps the rotation-trigger annotation — proving it
   reverse-mapped the flat `changed.json` classifier back through `ExtraKeys` and
   matched the CR by index key (without that round-trip the extra field would be
   dropped and the rotation silently lost).

The `extra-svc` entries in `changed.json` and `get-by-classifier.json` are already
wired into the mock ConfigMap, so a fresh `kind-up.sh` is all the setup it needs.
Pass `KEEP=1` to leave the CR and Secret in place after the run.

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
│   ├── mock-aggregator.yaml    # Namespace + ConfigMap + Deployment + Service for aggregator-mock
│   └── operator.yaml           # ServiceAccount + RBAC + credentials Secret + Deployment for the operator
└── test-resources/
    ├── namespace.yaml               # namespace test-ns
    ├── namespacebinding.yaml        # NamespaceBinding/binding — claims test-ns (operatorNamespace=dbaas-system)
    ├── secret-rbac.yaml             # namespaced Secret Role+RoleBinding for test-ns + self-binding for dbaas-system
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
    ├── idb-invalid-no-source.yaml           # IDB — pre-flight: clone without sourceClassifier → InvalidConfiguration
    │
    │   # DatabaseSecretClaim test CR
    └── dsc-success.yaml                      # DSC — get-by-classifier 200 → Secret created; rotation fan-out → re-fetch (no-op)
```
