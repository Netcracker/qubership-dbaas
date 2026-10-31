# PhysicalDatabase

`PhysicalDatabase` registers a physical database, a DBMS cluster with its dbaas adapter, with dbaas-aggregator. The
adapter and the DBMS must already run. The resource holds only the adapter address and credentials; the operator asks
the adapter for everything else.

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: dbaas-adapter-credentials
  namespace: dbaas-db-adapters
stringData:
  username: "dbaas-aggregator"
  password: "<adapter-password>"
---
apiVersion: dbaas.netcracker.com/v1
kind: PhysicalDatabase
metadata:
  name: postgres-core
  namespace: dbaas-db-adapters
spec:
  operatorNamespace: dbaas-system
  adapterAddress: http://pg-dbaas-adapter.postgres:8080
  credentialsSecretRef:
    name: dbaas-adapter-credentials
```

> **Note:** deleting a `PhysicalDatabase` doesn't remove the registration, because logical databases live on it.

## Fields

| Field                       | Mutable | Description                                                                                                                                                     |
|-----------------------------|:-------:|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `adapterAddress`            |   Yes   | Adapter base URL with a scheme and a host. dbaas-aggregator accepts only a scheme change, such as a switch to `https`; any other change is rejected with `409`. |
| `credentialsSecretRef.name` |   Yes   | Secret with the adapter's own `username` and `password`. dbaas-aggregator uses them to call the adapter.                                                        |

The namespace needs [Secret access](../installation/installation.md#secret-access) with `get`.

## How It Works

1. The operator calls the adapter's `GET /api/v2/adapter/physical_database` with the Secret's credentials. The adapter
   must serve this endpoint.
2. It copies the answer into the status: `physicalDatabaseId`, `type`, `labels`, `supportedRoles`, `features`,
   `readOnlyHost` and `apiVersions`.
3. It registers the physical database with dbaas-aggregator. When the new roles need a migration of existing logical
   databases, the aggregator parks the registration; the phase is `WaitingForDependency` and the operator repeats the
   registration with the [poll backoff](../status-and-troubleshooting.md#exponential-poll-backoff).

The operator registers again every `DBAAS_PHYSICAL_DATABASE_RESYNC_INTERVAL` (`10m` by default), which picks up a
changed adapter password. To apply it at once, change the `dbaas.netcracker.com/refresh` annotation:

```bash
kubectl annotate physicaldatabase postgres-core -n dbaas-db-adapters \
  dbaas.netcracker.com/refresh="$(date +%s)" --overwrite
```

When two resources of the same operator use the same `adapterAddress` or report the same `physicalDatabaseId`, the
older one wins and the younger one stalls with `RegistrationConflict`.

## Reasons

Besides the [shared reasons](../status-and-troubleshooting.md#shared-reasons):

| Reason                       | Condition      | Meaning                                                  |
|------------------------------|----------------|----------------------------------------------------------|
| `PhysicalDatabaseRegistered` | `Ready=True`   | The physical database is registered                      |
| `RoleMigrationStarted`       | `Ready=False`  | dbaas-aggregator is migrating roles; the operator waits  |
| `RegistrationConflict`       | `Stalled=True` | See above                                                |
| `SecretError`                | `Ready=False`  | The Secret or a key is missing or can't be read          |
| `Unauthorized`               | `Ready=False`  | The adapter or dbaas-aggregator answered `401`           |
| `AdapterError`               | `Ready=False`  | The adapter answered `404` or `503`, or wasn't reachable |

```bash
kubectl get physicaldatabase -n dbaas-db-adapters -o wide
```
