# InternalDatabase

`InternalDatabase` declares a logical database that dbaas-aggregator creates and manages for a microservice. The
database doesn't need to exist in advance.

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: InternalDatabase
metadata:
  name: my-app-db
  namespace: my-namespace
spec:
  operatorNamespace: dbaas-system
  classifier:
    microserviceName: my-service
    scope: service
  type: postgresql
```

## Fields

The classifier is described in [Classifier](../DBaaS%20Operator.md#classifier).

| Field                                   | Mutable | Description                                                                                                                                                                                            |
|-----------------------------------------|:-------:|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `classifier`                            |   No    | Identity of the database                                                                                                                                                                               |
| `type`                                  |   No    | Database type, such as `postgresql` or `mongodb`                                                                                                                                                       |
| `lazy`                                  |   Yes   | Creates the database on first access instead of now. Not allowed with `initialInstantiation.approach: clone`.                                                                                          |
| `namePrefix`                            |   Yes   | Prefix of the database name in the DBMS                                                                                                                                                                |
| `physicalDatabaseId`                    |   Yes   | Places new databases on this physical database instead of following the balancing rules. A change doesn't move an existing database. Ignored for clones, which stay on the source's physical database. |
| `settings`                              |   Yes   | Adapter-specific settings, such as `encoding` or `pgExtensions`                                                                                                                                        |
| `versioningConfig.approach`             |   Yes   | How a new version is created during a Blue-Green update, such as `clone`. Without `versioningConfig` the database isn't versioned.                                                                     |
| `initialInstantiation.approach`         |   Yes   | `new` (the default) creates an empty database; `clone` copies `sourceClassifier`                                                                                                                       |
| `initialInstantiation.sourceClassifier` |   Yes   | The database to clone. Required with `clone`; its `microserviceName` must equal the classifier's.                                                                                                      |

## How It Works

The operator submits the declaration to dbaas-aggregator. The aggregator either provisions the database at once, or
starts an asynchronous operation and returns a tracking ID. In the second case the operator stores it in
`status.trackingId`, sets the phase to `WaitingForDependency`, and polls the operation with the
[poll backoff](../status-and-troubleshooting.md#exponential-poll-backoff) until it finishes.

- A spec change during polling drops the operation and submits the new spec.
- An operation the aggregator terminated (`TERMINATED`) or forgot (`404`) is submitted again.
- A failed operation (`FAILED`) stalls the resource with `AggregatorRejected`.

## Tenant Databases

A `scope: tenant` declaration without `tenantId` is a template: dbaas-aggregator creates each tenant's database when
that tenant first connects.

With a `tenantId`, the operator also creates that tenant's database right away, so a `DatabaseSecretClaim` for it
resolves without waiting for the first connection. The resource becomes `Succeeded` only when the tenant database
exists. While the aggregator is still creating it, the phase is `WaitingForDependency` with an empty
`status.trackingId`.

```yaml
spec:
  classifier:
    microserviceName: my-service
    scope: tenant
    tenantId: acme
  type: postgresql
```

## Reasons

Besides the [shared reasons](../status-and-troubleshooting.md#shared-reasons):

| Reason                | Condition     | Meaning                                                                                            |
|-----------------------|---------------|----------------------------------------------------------------------------------------------------|
| `DatabaseProvisioned` | `Ready=True`  | The database exists                                                                                |
| `ProvisioningStarted` | `Ready=False` | The aggregator is still creating it; `status.trackingId` is set when there is an operation to poll |
| `OperationTerminated` | `Ready=False` | The aggregator terminated the operation; the operator submits it again                             |

## Examples

Clone an existing database:

```yaml
spec:
  classifier:
    microserviceName: my-service
    scope: service
  type: postgresql
  initialInstantiation:
    approach: clone
    sourceClassifier:
      microserviceName: my-service
      scope: service
```

A versioned database with adapter settings:

```yaml
spec:
  classifier:
    microserviceName: payments
    scope: service
    customKeys:
      logicalDBName: payments
  type: postgresql
  namePrefix: pay
  settings:
    encoding: UTF8
    pgExtensions:
      - vector
  versioningConfig:
    approach: clone
```

```bash
kubectl get dbidb -n my-namespace
kubectl get dbidb my-app-db -n my-namespace -o jsonpath='{.status.trackingId}'
```
