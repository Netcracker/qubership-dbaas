# Operator-to-Aggregator Contract

The dbaas-aggregator endpoints the operator calls, and how it maps their answers to resource status. Change this page
together with `internal/client/` when the contract changes.

## Endpoints

| Method          | Path                                                                                  | Caller                                                                                            |
|-----------------|---------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------|
| `POST`          | `/api/declarations/v1/apply`                                                          | `InternalDatabase` (`subKind: DatabaseDeclaration`), `DatabaseAccessPolicy` (`subKind: DbPolicy`) |
| `GET`           | `/api/declarations/v1/operation/{trackingId}/status`                                  | `InternalDatabase`, while an operation runs                                                       |
| `PUT`           | `/api/v3/dbaas/{namespace}/databases`                                                 | `InternalDatabase` with a `tenantId`: get-or-create of the tenant database                        |
| `PUT`           | `/api/v3/dbaas/{namespace}/databases/registration/externally_manageable`              | `ExternalDatabase`, with `updateConnectionProperties: true`                                       |
| `POST`          | `/api/v3/dbaas/{namespace}/databases/get-by-classifier/{type}`                        | `DatabaseSecretClaim`, with `originService` and `userRole`                                        |
| `GET`           | `/api/v3/dbaas/databases/changed?sinceTs=&sinceId=&limit=`                            | Rotation poller; needs `CLUSTER_OPERATOR`                                                         |
| `PUT`           | `/api/v3/dbaas/{namespace}/physical_databases/rules/onMicroservices`                  | `MicroserviceBalancingRule`                                                                       |
| `PUT`, `DELETE` | `/api/v3/dbaas/{namespace}/physical_databases/balancing/rules/{ruleName}`             | `NamespaceBalancingRule`                                                                          |
| `PUT`, `DELETE` | `/api/v3/dbaas/balancing/rules/permanent`                                             | `PermanentBalancingRule`                                                                          |
| `PUT`           | `/api/v3/dbaas/{type}/physical_databases/{physicalDatabaseId}?internalMigration=true` | `PhysicalDatabase`                                                                                |

`PhysicalDatabase` also calls the adapter: `GET {adapterAddress}/api/v2/adapter/physical_database`.

`{namespace}` is `classifier.namespace` when set, otherwise `metadata.namespace`. Requests time out after 30 seconds,
and the client doesn't retry; the controller retries with the reconcile backoff.

## Response Handling

All resource-driven calls share this mapping, classified by the `AggregatorError` helpers in
`internal/client/types.go`:

| Answer                                | Phase                                  | Reason               |
|---------------------------------------|----------------------------------------|----------------------|
| `400`, `403`, `409`, `410`, `422`     | `InvalidConfiguration`, `Stalled=True` | `AggregatorRejected` |
| `401`                                 | `BackingOff`                           | `Unauthorized`       |
| `5xx`, any other `4xx`, network error | `BackingOff`                           | `AggregatorError`    |

Exceptions:

| Call                    | Answer                                  | Result                                                                            |
|-------------------------|-----------------------------------------|-----------------------------------------------------------------------------------|
| apply                   | `202` with `trackingId`                 | `WaitingForDependency`; poll the operation                                        |
| operation status        | `COMPLETED` / `FAILED`                  | `Succeeded` / `AggregatorRejected`; `trackingId` cleared                          |
| operation status        | `TERMINATED`, or HTTP `404`             | `BackingOff`; `trackingId` cleared, apply again                                   |
| operation status        | `401`, `5xx`                            | `BackingOff`; `trackingId` kept                                                   |
| tenant get-or-create    | `202`                                   | `WaitingForDependency`; the body has no credentials, repeat with the poll backoff |
| get-by-classifier       | `404` with `CORE-DBAAS-4006`            | `DatabaseNotFound`; `404` without a TMF body is `AggregatorError`                 |
| get-by-classifier       | `200` with empty `connectionProperties` | `EmptyConnectionProperties`                                                       |
| namespace rule `DELETE` | `404`                                   | Treated as removed                                                                |
| physical database `PUT` | `202`                                   | `WaitingForDependency`, `RoleMigrationStarted`; the body isn't read               |
| changed-databases feed  | any error                               | Logged; the cursor stays and the next tick retries. No resource status changes.   |

## Rotation Feed

The first call without `sinceTs` returns only the feed's high-water mark, which seeds the in-memory cursor
`(lastRotatedAt, id)`. Later calls return databases whose credentials changed after the cursor. For each, the poller
finds `DatabaseSecretClaim` resources by `(classifier, type)` through a cache index and patches their
`dbaas.netcracker.com/rotation-trigger` annotation; the reconciler then fetches the credentials. The cursor isn't
persisted: the full reconcile at startup and the hourly requeue cover what a restart loses. See
[Rotation role lookup](designs/rotation-role-lookup.md) for why the index ignores `userRole`.
