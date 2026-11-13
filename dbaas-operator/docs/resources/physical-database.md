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

> **Note:**
> - Deleting a `PhysicalDatabase` CR doesn't remove the physical database in DBaaS, because logical databases live on
>   it.
> - If a `PhysicalDatabase` CR is created for a physical database that is already registered in DBaaS, the operator
>   starts reconciling the CR. It updates the existing registration only if necessary; otherwise, no changes are made.

## Fields

| Field                       | Mutable | Description                                                                                                                                                     |
|-----------------------------|:-------:|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `operatorNamespace`         |   Yes   | Namespace of the operator that manages the resource. Unlike on other resources, a wrong value can be corrected in place, without a recreation of CR.            |
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

## Physical Database ID Conflict

dbaas-aggregator keeps the `physicalDatabaseId` of a registered adapter address and rejects another one with `409` and
`CORE-DBAAS-4011`. This happens when the adapter starts reporting another identifier, for example after the identifier
is overridden in the adapter's deployment parameters. The resource reports `PhysicalDatabaseIdConflict` with
`Ready=False` and `Stalled=False`, and the message holds the aggregator's text:

```text
Physical database identifier conflict. Physical database with address '<adapterAddress>' is already registered with
physicalDatabaseId '<registered-id>'. ...
```

The identifier comes from the adapter, not from the resource, so the operator registers again with the
[poll backoff](../status-and-troubleshooting.md#exponential-poll-backoff) and records one Warning Event per attempt. The
resource becomes `Ready` on the first registration the aggregator accepts.

A conflict that lasts more than 10 minutes needs a change outside the resource. The reason then becomes
`PhysicalDatabaseIdConflictTimeout`, the operator records one Warning Event and no more, and it registers again every
`DBAAS_PHYSICAL_DATABASE_RESYNC_INTERVAL` (`10m` by default). To register at once after the fix, change the
`dbaas.netcracker.com/refresh` annotation. The start of the conflict is in `status.firstIdConflictAt`; an adapter or
Secret error, like any other answer from the aggregator, clears it. To end the conflict, do one of the following:

- Restore `<registered-id>` in the adapter's deployment parameters.
- If the new identifier is intended, delete the old registration with
  [`DELETE /api/v3/dbaas/{type}/physical_databases/{phydbid}`](../../../docs/api/rest-api.md#delete-physical-database).
  The aggregator refuses the deletion with `406` while the old physical database is the default for its type or holds
  logical databases. Such a registration can't move to another identifier, so restore the old one.

dbaas-aggregator answers the two conflicts with separate codes since 6.18.0. An earlier aggregator answers both with
`CORE-DBAAS-4011`, so the operator retries an address conflict as well instead of stalling.

A `409` with `CORE-DBAAS-4057` is the address conflict: the identifier the adapter reports is registered with another
address. The aggregator accepts a new address only when it keeps the host and changes the scheme. The resource stalls
with `AggregatorRejected`, and the operator retries only after the spec changes. Set `spec.adapterAddress` back to the
registered address. Alternatively, give the adapter at the new address another identifier and change the
`dbaas.netcracker.com/refresh` annotation to register again.

## Reasons

Besides the [shared reasons](../status-and-troubleshooting.md#shared-reasons):

| Reason                              | Condition      | Meaning                                                                                               |
|-------------------------------------|----------------|-------------------------------------------------------------------------------------------------------|
| `PhysicalDatabaseRegistered`        | `Ready=True`   | The physical database is registered                                                                   |
| `RoleMigrationStarted`              | `Ready=False`  | dbaas-aggregator is migrating roles; the operator waits                                               |
| `PhysicalDatabaseIdConflict`        | `Ready=False`  | The adapter reports another `physicalDatabaseId`; the operator retries                                |
| `PhysicalDatabaseIdConflictTimeout` | `Ready=False`  | The conflict lasted more than 10 minutes; the operator retries at the resync interval, without Events |
| `RegistrationConflict`              | `Stalled=True` | See above                                                                                             |
| `SecretError`                       | `Ready=False`  | The Secret or a key is missing or can't be read                                                       |
| `Unauthorized`                      | `Ready=False`  | The adapter or dbaas-aggregator answered `401`                                                        |
| `AdapterError`                      | `Ready=False`  | The adapter answered `404` or `503`, or wasn't reachable                                              |

```bash
kubectl get physicaldatabase -n dbaas-db-adapters -o wide
```
