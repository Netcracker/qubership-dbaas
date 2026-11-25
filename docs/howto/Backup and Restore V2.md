# Backup & Restore V2

> **Available from DBaaS 26.1.** Backup and restore are supported for any database engine whose DBaaS Adapter and
> Backup Daemon implement the common backup/restore endpoints. The Backup Daemon needs access to an S3-compatible
> object storage that holds the backup payloads.

Backup & Restore V2 backs up and restores the logical databases you select with a filter. You call the API, DBaaS
resolves which databases the filter covers, delegates the work to the DBaaS Adapter of each database type, and the
backup payload goes to the object storage you name in the request.

In the overall process an external tool (Cloud Backuper) orchestrates backup and restore. DBaaS receives the
commands, determines the databases, calls the adapters, and tracks the result.

- [Prerequisites](#prerequisites)
- [Concepts](#concepts)
    - [Databases and classifiers](#databases-and-classifiers)
    - [Filters](#filters)
    - [External databases](#external-databases)
    - [Namespace and tenant mapping](#namespace-and-tenant-mapping)
    - [Dry run](#dry-run)
- [How to use](#how-to-use)
    - [Create a backup](#create-a-backup)
    - [Track a backup](#track-a-backup)
    - [Restore to the same environment](#restore-to-the-same-environment)
    - [Track a restore](#track-a-restore)
    - [Restore to another namespace or tenant](#restore-to-another-namespace-or-tenant)
    - [Names of restored databases](#names-of-restored-databases)
    - [Check what a restore will do](#check-what-a-restore-will-do)
    - [Retry a failed restore](#retry-a-failed-restore)
    - [Move a backup to another DBaaS environment](#move-a-backup-to-another-dbaas-environment)
    - [Delete a backup or restore](#delete-a-backup-or-restore)
- [Statuses](#statuses)
- [Errors and limitations](#errors-and-limitations)
- [Configuration](#configuration)
- [Related](#related)

## Prerequisites

- The DBaaS Adapter and the Backup Daemon of each database type you want to back up implement the backup/restore
  endpoints of the [adapter contract](../api/adapter-contract.yaml). A database whose adapter does not support
  backup/restore cannot be included in a backup; see [Errors and limitations](#errors-and-limitations).
- The Backup Daemon is configured with access to an S3-compatible object storage. Your request references it with
  `storageName` and `blobPath`.
- You authenticate with the backup daemon user, `BACKUP_DAEMON_DBAAS_ACCESS_USERNAME` and
  `BACKUP_DAEMON_DBAAS_ACCESS_PASSWORD` (the role is `BACKUP_MANAGER`). Each section below names the endpoint;
  request and response schemas live in the [REST API reference](../api/rest-api.md#backup-and-restore).

## Concepts

### Databases and classifiers

DBaaS keeps a registry of logical databases. Each logical database is identified by one or more classifiers. A
classifier is a sorted map of key-value pairs; the common keys are `namespace`, `microserviceName`, `scope`, and
`tenantId` (when applicable). `databaseType` and `databaseKind` are filter fields used in requests, not classifier
keys.

### Filters

`filterCriteria` selects the databases a backup or restore covers, with an `include` list and an `exclude` list:

- A database is selected when it matches at least one filter in `include` (OR across filters).
- Within one filter, every filled field must match (AND across fields). Empty fields are ignored.
- Any selected database that matches at least one filter in `exclude` is removed, with the same OR/AND rules.

| Field              | Type                 | Selects databases with the given... |
|--------------------|----------------------|-------------------------------------|
| `namespace`        | List of String       | Kubernetes namespaces               |
| `microserviceName` | List of String       | microservice names                  |
| `databaseType`     | List of DatabaseType | database types                      |
| `databaseKind`     | List of DatabaseKind | database kinds                      |

When you back up, databases that are marked for deletion or are not in the `CREATED` state are not selected.

### External databases

An external database is registered in DBaaS but not managed by it. The `externalDatabaseStrategy` request field
(default `FAIL`) decides what happens when an external database falls into your selection:

| Strategy  | Effect                                                                                                      |
|-----------|-------------------------------------------------------------------------------------------------------------|
| `FAIL`    | The request is rejected with 400, listing the external databases                                            |
| `SKIP`    | External databases are excluded from the operation                                                          |
| `INCLUDE` | External databases are recorded in the backup and included in a restore when the restore strategy allows it |

### Namespace and tenant mapping

The optional `mapping` field of a restore request rewrites classifier values, so a backup can be restored into
another namespace or tenant:

- `namespaces`: a map of source namespace to target namespace.
- `tenants`: a map of source tenant to target tenant.

The mapping applies to every selected database before the restore. If the mapping makes two databases share one
classifier, DBaaS rejects the restore with 409.

### Dry run

Both backup and restore accept the `dryRun` query parameter:

- `backup?dryRun=true`: DBaaS computes which databases the filter selects and returns the resulting structure,
  without starting a backup and without persisting any operation.
- `restore?dryRun=true`: DBaaS runs the full restore validation, including a dry-run call to every involved
  adapter, and returns the calculated result without applying any change.

## How to use

### Create a backup

Call [Initiate database backup](../api/rest-api.md#initiate-database-backup):
`POST /api/backups/v1/backup`.

- `backupName`: a unique name you choose.
- `storageName`, `blobPath`: the object storage and the path where the adapters store the backup payload.
- `filterCriteria`: the databases to back up.
- `externalDatabaseStrategy`: default `FAIL`.
- `ignoreNotBackupableDatabases`: default `false`; set `true` to skip databases that cannot be backed up instead of
  failing.

```bash
curl --request POST 'http://localhost:8080/api/backups/v1/backup' \
    --header 'Authorization: Basic ...' \
    --data '{
      "backupName": "backup-prod-20261103",
      "storageName": "minio",
      "blobPath": "backups/backup-prod-20261103",
      "filterCriteria": {
        "include": [
          { "namespace": ["prod"], "microserviceName": ["inventory-service"] }
        ]
      },
      "externalDatabaseStrategy": "SKIP"
    }'
```

The response is `202` with a `BackupResponse`: the backup name, the selected databases (`logicalBackups` grouped by
adapter, `externalDatabases`), and the progress fields `total`, `completed`, `size`. The status is `200` instead
when the backup is already `COMPLETED` or `FAILED`.

### Track a backup

Poll [Get backup status](../api/rest-api.md#get-backup-status):
`GET /api/backups/v1/backup/{backupName}/status`.

```bash
curl --request GET 'http://localhost:8080/api/backups/v1/backup/backup-prod-20261103/status' \
    --header 'Authorization: Basic ...'
```

```json
{
  "status": "IN_PROGRESS",
  "total": 3,
  "completed": 1,
  "size": 55091,
  "errorMessage": null
}
```

DBaaS asks the adapters for progress on a schedule (default 1 minute, see
[Configuration](#configuration)). The final status is `COMPLETED` or `FAILED`; on failure `errorMessage` carries
the aggregated error from the adapters. [Get backup details](../api/rest-api.md#get-backup-details)
(`GET /api/backups/v1/backup/{backupName}`) returns the same fields plus the per-database breakdown with sizes,
durations, paths, and errors.

### Restore to the same environment

Call [Restore from backup](../api/rest-api.md#restore-from-backup):
`POST /api/backups/v1/backup/{backupName}/restore`.

- The backup must be `COMPLETED`; otherwise DBaaS responds 422.
- One restore runs at a time in DBaaS; a second request is rejected with 409 until the first one finishes.
- Without `filterCriteria`, all databases of the backup are restored; with it, only the matching ones.

```bash
curl --request POST 'http://localhost:8080/api/backups/v1/backup/backup-prod-20261103/restore' \
    --header 'Authorization: Basic ...' \
    --data '{
      "restoreName": "restore-prod-20261103",
      "storageName": "minio",
      "blobPath": "backups/backup-prod-20261103"
    }'
```

The response is `202` with a `RestoreResponse`: the restore name, the selected databases (`logicalRestores` grouped by
adapter, `externalDatabases`), and the progress fields `total` and `completed`. The status is `200` instead
when the restore is already `COMPLETED` or `FAILED`.

### Track a restore

Poll [Get restore status](../api/rest-api.md#get-restore-status):
`GET /api/backups/v1/restore/{restoreName}/status`.

```bash
curl --request GET 'http://localhost:8080/api/backups/v1/restore/restore-prod-20261103/status' \
    --header 'Authorization: Basic ...'
```

```json
{
  "status": "IN_PROGRESS",
  "total": 3,
  "completed": 1,
  "errorMessage": null
}
```

DBaaS asks the adapters for progress on a schedule (default 1 minute, see
[Configuration](#configuration)). The final status is `COMPLETED` or `FAILED`; on failure `errorMessage` carries
the aggregated error from the adapters.
[Get restore details](../api/rest-api.md#get-restore-details)
(`GET /api/backups/v1/restore/{restoreName}`) returns the same fields plus the per-database breakdown with
durations, paths, and errors.

### Restore to another namespace or tenant

Pass `mapping` in the restore request:

```bash
curl --request POST 'http://localhost:8080/api/backups/v1/backup/backup-prod-20261103/restore' \
    --header 'Authorization: Basic ...' \
    --data '{
      "restoreName": "restore-to-uat",
      "storageName": "minio",
      "blobPath": "backups/backup-prod-20261103",
      "mapping": {
        "namespaces": { "prod": "uat" },
        "tenants": { "tenant-a": "tenant-b" }
      }
    }'
```

Two things to know before you restore into an environment that already has databases:

- A restored database does not keep the name it had in the backup; see
  [Names of restored databases](#names-of-restored-databases).
- When a logical database with the target classifier already exists, the restored database takes its classifier and
  the existing database is marked as an orphan. When the mapping makes two databases share one classifier, DBaaS
  rejects the restore with 409 before anything is changed.

### Names of restored databases

A restored database is created under a new name in the target environment. The adapter of the target environment
generates the name, because the target may already contain a database with the original name.

The restore details report each restored database under its new `name`, identified by its `classifiers`. Each
classifier entry keeps the original (pre-mapping) classifier in `classifierBeforeMapper`, so you pair a backup
database with its restored one by that classifier. When the restore replaced an existing logical database, the
classifier entry names the replaced one in `previousDatabase`.

### Check what a restore will do

Add `?dryRun=true` to the restore call. DBaaS performs the same validation as a real restore, including a dry-run
call to each involved adapter, and returns the calculated result without applying any change. Use it before a
restore into another environment to verify the selection and the mapping.

### Retry a failed restore

Call [Retry restore](../api/rest-api.md#retry-restore): `POST /api/backups/v1/restore/{restoreName}/retry`.

- Only a restore in the `FAILED` status can be retried; otherwise DBaaS responds 422.
- The retry resets the failed per-adapter tasks and the attempt counter, and the restore goes back to
  `IN_PROGRESS`.

```bash
curl --request POST 'http://localhost:8080/api/backups/v1/restore/restore-prod-20261103/retry' \
    --header 'Authorization: Basic ...'
```

### Move a backup to another DBaaS environment

A backup is portable: the backup payload in object storage plus the backup metadata is enough to restore in another
DBaaS environment, provided the required adapters are available there.

1. Wait until the backup is `COMPLETED`.
2. Download the metadata with [Get backup metadata](../api/rest-api.md#get-backup-metadata):
   `GET /api/backups/v1/backup/{backupName}/metadata`. The response body is the backup metadata, and the `Digest`
   header carries its SHA-256 checksum.
3. Make the backup payload available in the object storage of the target environment, under the same `storageName`
   and `blobPath`.
4. On the target DBaaS, upload the metadata with [Upload backup metadata](../api/rest-api.md#upload-backup-metadata):
   `POST /api/backups/v1/operation/uploadMetadata`, with the same `Digest` header. DBaaS verifies the checksum and
   registers the backup as imported.

```bash
curl --request GET 'http://localhost:8080/api/backups/v1/backup/backup-prod-20261103/metadata' \
    --header 'Authorization: Basic ...'
```

```bash
curl --request POST 'http://target-dbaas:8080/api/backups/v1/operation/uploadMetadata' \
    --header 'Authorization: Basic ...' \
    --header 'Digest: SHA-256=<base64-checksum>' \
    --data @backup-prod-20261103.json
```

After the upload you restore from the imported backup like from any local one. If the imported backup was deleted,
uploading the same metadata (the same digest) returns it to `COMPLETED`.

### Delete a backup or restore

Backups:

- `DELETE /api/backups/v1/backup/{backupName}` (default, `force=false`): marks the backup `DELETED` and responds 204.
  The payload stays in the object storage.
- `DELETE /api/backups/v1/backup/{backupName}?force=true`: DBaaS asks the adapters to delete the payload and
  responds 202. The backup goes through `DELETE_IN_PROGRESS` to `DELETED`.
- Only `COMPLETED`, `FAILED`, and `DELETED` backups can be processed; otherwise DBaaS responds 422.

Restores: `DELETE /api/backups/v1/restore/{restoreName}` removes a finished restore operation and responds 204.

## Statuses

Backup statuses:

| Status               | Meaning                                                                   |
|----------------------|---------------------------------------------------------------------------|
| `NOT_STARTED`        | The backup is created and waiting for execution                           |
| `IN_PROGRESS`        | At least one adapter is still working                                     |
| `COMPLETED`          | All selected databases are backed up; metadata can be exported            |
| `FAILED`             | At least one database failed; `errorMessage` carries the aggregated error |
| `DELETE_IN_PROGRESS` | Adapter-side deletion of the payload is running (`force=true`)            |
| `DELETED`            | The backup is deleted; the payload may still be in the object storage     |

Restore statuses: `NOT_STARTED`, `IN_PROGRESS`, `COMPLETED`, `FAILED`, `DELETED`.

The status endpoints report progress in `total` (selected databases), `completed` (finished databases), `size`
(total payload in bytes, backups only), and `errorMessage`.

## Errors and limitations

Typical rejections, with the TMF error text they carry:

| Response | Error text                                                                                                                                        | Cause and action                                                                                                                                                            |
|----------|---------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 400      | `The backup/restore request can't be processed. External databases not allowed for backup by strategy=FAIL: ...`                                  | An external database falls into the selection and the strategy is `FAIL`. Use `SKIP` or `INCLUDE`, or narrow the filter.                                                    |
| 400      | `The backup/restore request can't be processed. Backup operation unsupported for databases: ...`                                                  | A selected database cannot be backed up (it is disabled for backup, or its adapter does not support backup/restore). Exclude it or set `ignoreNotBackupableDatabases=true`. |
| 404      | `No backup with id: '...' is found`                                                                                                               | The backup name is unknown in this DBaaS. For an imported backup, upload its metadata first.                                                                                |
| 404      | `No databases matching the filtering criteria were found during the backup` (backup) or `Databases that match filterCriteria not found` (restore) | The filter selects nothing. Widen the filter or check the classifier values.                                                                                                |
| 409      | `Resource with name '...' already exists`                                                                                                         | A backup or restore with that name exists. Choose a new name.                                                                                                               |
| 409      | `Operation 'restore' is already in progress.`                                                                                                     | Another restore is running. Wait for it to finish.                                                                                                                          |
| 409      | `Duplicate classifiers detected after mapping. ...`                                                                                               | The mapping makes two databases share one classifier. Fix the mapping.                                                                                                      |
| 422      | `Restore can't be processed due to backup status IN_PROGRESS`                                                                                     | The backup is not `COMPLETED`. Wait for it to finish.                                                                                                                       |
| 422      | `has invalid status 'IN_PROGRESS'. Only FAILED restores can be processed.`                                                                        | Retry is available only from the `FAILED` status.                                                                                                                           |
| 422      | `has invalid status='IN_PROGRESS'. Only COMPLETED, FAILED or DELETED backups can be processed.`                                                   | Delete is available only for finished or already deleted backups.                                                                                                           |
| 422      | `can't produce metadata for backup ... in status FAILED`                                                                                          | Metadata can be exported only from a `COMPLETED` backup.                                                                                                                    |
| 409      | `Digest header mismatch: expected digest ... but got ...`                                                                                         | The uploaded metadata does not match its digest header.                                                                                                                     |
| 409      | `backup already exists and is not in DELETED status`                                                                                              | The backup name already exists here and is not deleted. Upload only when the name is unknown or the existing backup is `DELETED`.                                           |

Limitations:

- One restore runs at a time in DBaaS.
- A restore requires the backup to be `COMPLETED`.
- A restore can be retried only from `FAILED`.
- Databases marked for deletion or not in the `CREATED` state are never selected for backup.
- The restore endpoint is not idempotent: a repeated call with the same `restoreName` is rejected with 409.

## Configuration

The backup/restore scheduler is tuned with the installation parameters `DBAAS_BACKUP_RESTORE_CHECK_INTERVAL`,
`DBAAS_BACKUP_RESTORE_CHECK_ATTEMPTS`, `DBAAS_BACKUP_RESTORE_RETRY_DELAY_SECONDS`,
`DBAAS_BACKUP_RESTORE_RETRY_ATTEMPTS`, and `DBAAS_BACKUP_RESTORE_CHECK_LOCK_TIMEOUT`. See the
[installation parameters](../installation/parameters.md) for defaults and recommendations.

## Related

- [REST API reference: Backup and Restore](../api/rest-api.md#backup-and-restore)
- [OpenAPI specification](../api/OpenAPI.json)
- [Adapter contract](../api/adapter-contract.yaml)
- Internal design: [Backup & Restore V2 design](../development/backup-and-restore-v2.md)
