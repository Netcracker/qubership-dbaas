# DBaaS log and status signatures

Match ticket evidence as a fixed string. Each entry points to one canonical
case; load only that section from `aggregator.md` or `operator.md`.

## Installation and upgrade

- `env variable must be set!` -> `aggregator.md` / `Helm render fails with env variable must be set!`
- `Wrong parameter:` with `POSTGRES_HOST=` -> `aggregator.md` / `Pre-install hook fails with Wrong parameter: POSTGRES_HOST`
- `It is not possible to create a database from the user` -> `aggregator.md` / `Pre-install hook fails with It is not possible to create a database from the user`
- `POSTGRES_DBAAS_DATABASE_NAME must be set if DBAAS_OWN_PG_DB_CREATED_MANUALLY=true` or `POSTGRES_DBAAS_DATABASE_NAME=<name> does not exists.` -> `aggregator.md` / `Pre-install hook fails because POSTGRES_DBAAS_DATABASE_NAME is missing or does not exist`
- `already exists, so you should pass dbaas specific user through` -> `aggregator.md` / `Pre-hook fails with Postgres user dbaas_user_<namespace> already exists`
- `Dbaas needs a postgres dbaas user for storing information` -> `aggregator.md` / `Pre-hook fails with Dbaas needs a postgres dbaas user for storing information`
- `database creation error` -> `aggregator.md` / `Pre-hook fails with database creation error`
- `Secret with name "dbaas-storage-credentials" exists. Skip prepare pg database` with changed credentials not applied -> `aggregator.md` / `Changed registry credentials in deployment parameters have no effect`

## Aggregator runtime

- `Postgres connection is lost` or `go to h2 database` -> `aggregator.md` / `dbaas-aggregator lost connection to its PostgreSQL registry`
- `PasswordEncryption does not work`, `An error occurred while getting decrypted password`, or `cannot decrypt password` -> `aggregator.md` / `dbaas-aggregator is not ready because password encryption fails`
- `OutOfMemoryError` or `OOMKilled` -> `aggregator.md` / `dbaas-aggregator is OOMKilled or slow on environments with many databases`
- `has problem. Status:` -> `aggregator.md` / `Physical database adapter reports a problem`

## Adapter registration and placement

- `conflicts with already saved address` or `sent physicalDatabaseIdentifier =` -> `aggregator.md` / `Adapter registration rejected because the adapter address changed`
- `CORE-DBAAS-4017` or `Request to adapter failed. Status code:` -> `aggregator.md` / `Adapter registration handshake returns Request to adapter failed. Status code`
- `401` from dbaas-aggregator that begins after `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` or `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD` changed -> `aggregator.md` / `Adapters or clients get 401 from dbaas-aggregator after a credential change`
- `CORE-DBAAS-4000` or `Requested physical database is not registered` -> `aggregator.md` / `Database creation fails with Requested physical database is not registered`
- `CORE-DBAAS-4033`, `No balancing rule exists to determine physical database`, or `Default physical databases are disabled and no dedicated rules found` -> `aggregator.md` / `Database creation fails with No balancing rule exists to determine physical database`
- `Physical database with label:`, `More than one physical database registered with label:`, or `has no labels` -> `aggregator.md` / `Microservice balancing rule points to a label that matches no physical database`

## Logical database requests

- `CORE-DBAAS-4003` or `allowed only in non prod mode` -> `aggregator.md` / `Delete request rejected with CORE-DBAAS-4003`
- `CORE-DBAAS-4055` or `CORE-DBAAS-4004` -> `aggregator.md` / `Request rejected with namespace isolation error CORE-DBAAS-4055 or CORE-DBAAS-4004`
- `CORE-DBAAS-4023` or `cannot be accessed by` -> `aggregator.md` / `Request rejected because the role is not allowed CORE-DBAAS-4023`
- `CORE-DBAAS-4006` or `No database of type:` -> `aggregator.md` / `Database lookup returns CORE-DBAAS-4006`
- `CORE-DBAAS-4010` or `Invalid classifier` -> `aggregator.md` / `Request rejected with Invalid classifier CORE-DBAAS-4010`
- `CORE-DBAAS-4053` or `is already in progress` -> `aggregator.md` / `Request rejected with Operation already in progress CORE-DBAAS-4053`
- Ghost database list from `/debug/internal/ghost` -> `aggregator.md` / `Ghost databases reported in the physical database`

## Operator and CR status

- `FailedMount` for `dbaas-security-configuration-secret` or `failed to load aggregator credentials from /etc/dbaas/security` -> `operator.md` / `dbaas-operator cannot start because its Basic-auth Secret is missing or invalid`
- Empty `PHASE`, no conditions, no events, and metric `dbaas_resource_unassigned` = 1 -> `operator.md` / `DBaaS custom resource has no status at all`
- Reason `Unauthorized` -> `operator.md` / `DBaaS custom resource is BackingOff with reason Unauthorized`
- Reason `AggregatorError` -> `operator.md` / `DBaaS custom resource is BackingOff with reason AggregatorError`
- Reason `InvalidSpec` or `AggregatorRejected` -> `operator.md` / `DBaaS custom resource is InvalidConfiguration`
- Reason `SecretError` or metric `dbaas_secret_resolution_errors_total` -> `operator.md` / `ExternalDatabase is BackingOff with reason SecretError`
- `DatabaseSecretClaim` in `Processing` with no conditions -> `operator.md` / `DatabaseSecretClaim stuck in Processing without conditions`
- Reason `DatabaseNotFound` or `DatabaseNotFoundTimeout` -> `operator.md` / `DatabaseSecretClaim reports DatabaseNotFound or DatabaseNotFoundTimeout`
- Reason `SecretConflict` -> `operator.md` / `DatabaseSecretClaim reports SecretConflict`
- `InternalDatabase` phase `WaitingForDependency` with reason `ProvisioningStarted` -> `operator.md` / `InternalDatabase stays in WaitingForDependency`
- Balancing rule CR in `Terminating` -> `operator.md` / `Balancing rule CR stuck in Terminating`
- All DBaaS CRDs and CRs gone after an operator redeploy -> `operator.md` / `All DBaaS custom resources disappeared after an operator redeploy`

Reason `OperationTerminated` on an `InternalDatabase` is not a failure. The
operator resubmits after an Aggregator restart or cancelled operation.

For an unmapped `CORE-DBAAS-<nnnn>` code, use `CORE-DBAAS error codes` in
`SKILL.md`. Classify it from the response detail and the initiating Aggregator or
adapter log; do not infer client error versus infrastructure failure from the
HTTP status class alone.
