# Backup & Restore V2

## Table of Contents

- [Requirements](#requirements)
- [Overview](#overview)
  - [Backup API](#backup-api)
  - [Restore API](#restore-api)
  - [Dry Run](#dry-run)
- [High Level e2e Backup & Restore Process](#high-level-e2e-backup--restore-process)
- [Open API](#open-api)
- [Data Model](#data-model)
  - [Backup V2 Data Model](#backup-v2-data-model)
  - [Restore V2 Data Model](#restore-v2-data-model)
- [Sequence Diagrams](#sequence-diagrams)
  - [Start Backup](#start-backup)
  - [Check Backup Async](#check-backup-async)
  - [Start Restore](#start-restore)
  - [Check Restore Async](#check-restore-async)
  - [Retry Restore](#retry-restore)
  - [Get Backup Metadata](#get-backup-metadata)
  - [Upload Backup Metadata](#upload-backup-metadata)
  - [Delete Backup](#delete-backup)
- [State Diagrams](#state-diagrams)
  - [Backup](#backup)
  - [Restore](#restore)
- [Configuration Parameters](#configuration-parameters)


> ⚠️ **Warning**
> Backup & Restore V2 is available in DBaaS starting from version 26.1.
>
> Backup and restore are supported for any external database engine, provided that the corresponding DBaaS Adapter and Backup Daemon implement the common backup/restore endpoints required by DBaaS.
>
> The Backup Daemon must also be configured with access to an S3-compatible object storage, which is used to store backup binary data (payloads).

## Requirements

- Flexible Backup scope
  - Backup namespace
  - Backup several microservices
- Flexible Restore scope (to another namespace and/or tenant):
  - Restore full backup
  - Restore part of backup
- Backup/Restore portability
  - Capability to restore backup in another DBaaS

## Overview

Backup & Restore V2 provides flexible control over which databases are backed up and restored.

DBaaS acts as a registry of logical databases. Each logical database is identified by one or more classifiers.

A classifier is a sorted map of key-value pairs. Common keys include:

- `namespace`
- `microserviceName`
- `scope`
- `tenantId` (when applicable)

`databaseType` and `databaseKind` are filter fields used in Backup/Restore requests, not classifier keys stored as-is.

### Backup API

Backup & Restore V2 supports flexible database filtering and backs up databases that match include/exclude criteria.

As a result, a `Backup` entity is created in DBaaS. It includes:

- Parameters used to create the backup
- Adapter-level and database-level backup state
- Logical databases included in backup
- Storage location (`storageName` + `blobPath`) of backup data

Backups are portable across DBaaS instances via metadata export/import.
APIs are provided to export and upload backup metadata with digest validation.

### Restore API

Backup & Restore V2 provides restore operations for logical databases stored in a backup.

Key restore parameters include:

- Backup name
- Filter criteria to select databases from backup metadata
- Mapping rules for namespaces and tenants
- External database strategy (`FAIL` / `SKIP` / `INCLUDE`)
- Storage location (`storageName` + `blobPath`)

As a result, a `Restore` entity is created in DBaaS. It includes:

- Parameters used to perform the restore
- Adapter-level and database-level restore state
- Logical and external databases selected for restore

During restore, classifier collisions in the target environment are detected and handled according to restore logic (including classifier replacement/transient replacement cases).

### Dry Run

Both backup and restore support `dryRun`.

- `backup dryRun`: validates/filter-calculates resulting backup structure without starting real backup execution or persisting operation state.
- `restore dryRun`: executes adapter restore calls in dry-run mode and returns calculated result without applying final DBaaS changes.

## High Level e2e Backup & Restore Process

In E2E, DBaaS is only one component involved in backup and restore. The overall backup/restore process is orchestrated by Cloud Backuper.

DBaaS only receives backup and restore commands, processes them, determines which databases must be backed up or restored, and calls the corresponding DBaaS Adapter so it can delegate execution to the Backup Daemon.

![Backup and Restore V2](../images/backup-and-restore-v2/Backup_and_Restore_V2.png)

DBaaS Backup & Restore V2 process supports exporting Backup Metadata that describes the backup structure.

Using this Backup Metadata, logical databases can be restored in any DBaaS environment where the required adapters are available.

To transfer backup binaries and Backup Metadata across different environments, an S3-compatible shared object storage is required.

The backup process returns a unique `backupName` (left side), which is then used to restore databases from that backup (right side).

## Open API

Open API: [`../OpenAPI.json`](../OpenAPI.json)

## Data Model

### Backup V2 Data Model

![Backup V2 Data Model](../images/backup-and-restore-v2/backup-v2-data-model.png)

Source: [`../diagrams/backup-and-restore-v2/backup-v2-data-model.puml`](../diagrams/backup-and-restore-v2/backup-v2-data-model.puml)

#### Backup

Backup is the top-level backup operation entity that stores the requested filters and strategy plus the aggregated state of the backup (status, totals, size, errors) and links to all logical and external
database backups.

| Field | Type | Description | Source |
|---|---|---|---|
| name | String | Unique name of the backup | BackupRequest.backupName |
| storageName | String | Name of the storage backend containing the backup | BackupRequest.storageName |
| blobPath | String | Path in the storage where backup will be stored | BackupRequest.blobPath |
| externalDatabaseStrategy | ExternalDatabaseStrategy | How to handle external databases during backup | BackupRequest.externalDatabaseStrategy |
| filterCriteria | FilterCriteriaEntity (jsonb) | Filter criteria | BackupRequest.filterCriteria |
| logicalBackups | List<LogicalBackup> | Collection of logical backups grouped by adapter/database type; each entry contains its backup databases and tracks the progress/status of the backup for that group | - |
| externalDatabases | List<BackupExternalDatabase> | List of externally managed databases included in the backup (according to the external database strategy), with their names, types, and classifiers | - |
| status | BackupStatus | Current state of the backup operation | - |
| total | Integer | Total number of databases being backed up | - |
| completed | Integer | Number of databases successfully backed up | - |
| size | Long | Total size of the backup in bytes | - |
| errorMessage | String | Error details if the backup failed | - |
| attemptCount | int | Number of tracking/aggregation attempts made for this backup (used by the scheduler to limit retries) | — |
| imported | boolean | Indicates that the backup metadata was imported from an external source (backup metadata) rather than created by a local backup operation | — |
| digest | String | SHA-256 digest of the backup metadata, used to verify integrity during upload/import | — |
| ignoreNotBackupableDatabases | boolean | Whether non-backupable databases were ignored during backup | BackupRequest.ignoreNotBackupableDatabases |

#### LogicalBackup

Databases that pass the input filters and identified as internal are grouped by adapterId; for each adapterId, a LogicalBackup is created. Each LogicalBackup contains BackupDatabase entries for every
database in that group (name, classifiers, settings, users, configurational, etc.). In short, LogicalBackup is an aggregate for one adapter’s group of databases with its populated BackupDatabase list.

| Field | Type | Description |
|---|---|---|
| id | UUID | Auto-generated primary key |
| logicalBackupName | String | Name of the logical backup in adapter |
| backup | Backup | Parent backup operation that this logical backup belongs to |
| adapterId | String | Unique identifier of the adapter |
| type | String | Type of the adapter |
| backupDatabases | List<BackupDatabase> | List of logical backup databases |
| status | BackupTaskStatus | Current state of the backup databases of one adapter |
| errorMessage | String | Error message if backup failed |
| creationTime | Instant | Timestamp when the backup was created |
| completionTime | Instant | Timestamp when the backup completed |

#### BackupDatabase

BackupDatabase is a per-database backup record inside a LogicalBackup, containing the database identity (name/classifiers), settings/users, and backup execution details (status, size, duration, path,
errors, timestamps).

| Field | Type | Description |
|---|---|---|
| id | UUID | Auto-generated primary key |
| logicalBackup | LogicalBackup | Parent logical backup that this database entry belongs to |
| name | String | Name of the database |
| classifiers | List<SortedMap<String,Object>> | List of database classifiers. Each classifier is a sorted map of attributes. |
| settings | Map<String,Object> | Database settings as a key-value map |
| users | List<User> | List of database users |
| configurational | boolean | Indicates the type of the database |
| status | BackupTaskStatus | Current state of the backup database |
| size | long | Size of the backup |
| duration | long | Duration of the backup operation |
| path | String | Path to the backup file in the storage |
| errorMessage | String | Error message if the backup failed |
| creationTime | Instant | Timestamp when the backup was created |

#### BackupExternalDatabase

BackupExternalDatabase entries are created from databases that pass the input filters and are then identified as external.

| Field | Type | Description |
|---|---|---|
| id | UUID | Auto-generated primary key |
| backup | Backup | Parent backup operation that this external database belongs to |
| name | String | Name of the external database |
| type | String | Type of the database |
| classifiers | List<SortedMap<String,Object>> | List of database classifiers. Each classifier is a sorted map of attributes. |

#### FilterCriteriaEntity

Filtering uses FilterCriteria with include (filter) and exclude (exclude). A registry matches the include stage if it satisfies at least one filter in filter (OR across filters), and within a single filter
all provided fields must match (AND across fields). After that, any registry that matches at least one filter in exclude is removed (OR across exclude filters), with the same AND across fields rule inside
each exclude filter.

| Field   | Type | Description |
|---------|---|---|
| include | List<FilterEntity> | Include databases that match any of the filters in the list |
| exclude | List<FilterEntity> | Exclude databases that match any of the filters in the list |

#### FilterEntity

A single FilterEntity works as an AND over its fields: for a database to match, every field that is filled in the filter must match the database (namespace, microserviceName, databaseType, databaseKind).
Empty fields are ignored.

| Field | Type | Description |
|---|---|---|
| namespace | List<String> | Filter by Kubernetes namespaces |
| microserviceName | List<String> | Filter by microservice names |
| databaseType | List<DatabaseType> | Filter by database types |
| databaseKind | List<DatabaseKind> | Filter by database kinds |

### Restore V2 Data Model

![Restore V2 Data Model](../images/backup-and-restore-v2/restore-v2-data-model.png)

Source: [`../diagrams/backup-and-restore-v2/restore-v2-data-model.puml`](../diagrams/backup-and-restore-v2/restore-v2-data-model.puml)

#### Restore

Represents a restore operation created from a backup, including the selected databases (logical and external), mapping/filtering applied, current status, and aggregated progress statistics

| Field | Type | Description | Source |
|---|---|---|---|
| name | String | Unique name of the restore | RestoreRequest.restoreName |
| backup | Backup | Reference to parent backup entity (one-to-one relation) | - |
| storageName | String | Name of the storage backend containing the restore | RestoreRequest.storageName |
| blobPath | String | Path to the restore file in the storage | RestoreRequest.blobPath |
| filterCriteria | FilterCriteriaEntity (jsonb) | Filter criteria | RestoreRequest.filterCriteria |
| mapping | MappingEntity (jsonb) | Mapping to use for the restore operation | RestoreRequest.mapping |
| logicalRestores | List<LogicalRestore> | List of logical restores | - |
| externalDatabaseStrategy | ExternalDatabaseStrategy | How to handle external databases during restore | RestoreRequest.externalDatabaseStrategy |
| externalDatabases | List<RestoreExternalDatabase> | List of external databases | - |
| status | RestoreStatus | Current state of the restore operation | - |
| total | Integer | Total number of databases being restored | - |
| completed | Integer | Completed databases restore operation | - |
| errorMessage | String | Aggregated error messages during restore operation | - |
| attemptCount | int | Number of tracking/aggregation attempts made for this restore (used by the scheduler to limit retries) | — |

#### LogicalRestore

Represents a per-adapter restore task within a restore operation, containing the grouped restore databases, adapter identifiers, and the current status/progress of that logical restore. It is created
during restore initialization by grouping backup databases by target adapter and logical backup name.

| Field (LogicalRestore) | Type | Description |
|---|---|---|
| id | UUID | Auto-generated primary key |
| logicalRestoreName | String | Name of the logical restore in adapter |
| restore | Restore | Parent restore operation that this logical restore belongs to |
| adapterId | String | Unique identifier of the adapter |
| type | String | Type of the adapter |
| restoreDatabases | List<RestoreDatabase> | List of logical restore databases |
| status | RestoreTaskStatus | Current state of the restore operation |
| errorMessage | String | Information about error message during restore process |
| creationTime | Instant | Aggregated information about creation time of databases in adapter |
| completionTime | Instant | Aggregated information about completion time of databases in adapter |

#### RestoreExternalDatabase

Represents an externally managed database included in a restore operation, created during restore initialization after applying external database strategy and filter/mapping rules.

| Field (RestoreExternalDatabase) | Type | Description (from DTO) |
|---|---|---|
| id | UUID | Auto-generated primary key |
| restore | Restore | Parent restore operation that this external database belongs to |
| name | String | Name of the external database |
| type | String | Type of the database |
| classifiers | List<ClassifierDetails> | Classifier objects describing database attributes |

#### RestoreDatabase

Represents a single database being restored within a logical restore, including its classifiers, settings, users, and status. It is created during restore initialization from a backup database and mapping/
filtering results.

| Field | Type | Description |
|---|---|---|
| id | UUID | Auto-generated primary key |
| logicalRestore | LogicalRestore | Parent logical restore that this database entry belongs to |
| backupDatabase | BackupDatabase | Source backup database used for restore |
| name | String | Name of the database |
| classifiers | List<ClassifierDetails> | List of classifier details; each item contains classifier metadata and a sorted map in field classifier |
| settings | Map<String,Object> | Database settings as a key-value map |
| users | List<User> | List of database users |
| bgVersion | String | Blue-Green version of database |
| status | RestoreTaskStatus | Current state of the restore database |
| duration | long | Duration of the restore operation |
| path | String | Path to the restore file in the storage |
| errorMessage | String | Error message if the restore failed |
| creationTime | Instant | Timestamp when the restore was created |

#### MappingEntity

Encapsulates the namespace/tenant mapping applied during restore initialization to transform classifiers from the backup into target values.

| Field | Type | Description |
|---|---|---|
| namespaces | Map<String,String> | Mapping of source namespace to target namespace |
| tenants | Map<String,String> | Mapping of source tenant to target tenant |

#### ClassifierDetails

Represents a classifier used to identify a logical database, including its current and original key-value maps, role in mapping (NEW/REPLACED/TRANSIENT_REPLACED), and optional link to a previous database.

| Field | Type | Description |
|---|---|---|
| type | ClassifierType | Classifier role in restore mapping (NEW / REPLACED / TRANSIENT_REPLACED), indicating how this classifier is treated during restore |
| previousDatabase | String | Name of the existing database previously associated with this classifier, used when the classifier replaces or transiently replaces another database during restore |
| classifier | SortedMap<String,Object> | Classifier key-value map (namespace, microservice name, tenant, etc.) that identifies a logical database instance |
| classifierBeforeMapper | SortedMap<String,Object> | Original (pre-mapping) classifier key-value map preserved to track how mapping changed the classifier during restore |

## Sequence Diagrams

Below are sequence diagrams for the main Backup & Restore V2 processes. Steps that are already clear from the diagrams are not described separately.

### Start Backup

Starts an asynchronous backup operation for the specified databases and returns a BackupResponse; returns 200 if the backup is already completed/failed, otherwise 202. It can be invoked in dryRun mode, in which case no state mutations or persistence occur.

![Start Backup](../images/backup-and-restore-v2/start-backup-sequence.png)

Source: [`../diagrams/backup-and-restore-v2/start-backup-sequence.puml`](../diagrams/backup-and-restore-v2/start-backup-sequence.puml)

**Step 7:** Step loads all registries from the repository using filter, then filters them by removing invalid ones (marked for drop or not in CREATED state) and any that match a filled exclude filter (namespace/microservice/type/kind). If nothing remains, it throws exception that database list is empty.

**Step 8:** The step validates and filters the databases for backup. It splits them into external vs internal, applies ExternalDatabaseStrategy (FAIL throws, SKIP excludes, INCLUDE keeps), then detects non‑backupable internal DBs (backupDisabled=true or adapter doesn’t support backup/restore). If ignoreNotBackupableDatabases is false it throws; otherwise it removes them. The result is internal DBs (minus non‑backupable) plus external DBs only when strategy is INCLUDE.

**Step 9:** Step builds the full in‑memory Backup structure from the filtered DBs and request: creates the base Backup, splits databases into internal/external, groups internal ones by DBaaS Adapter identifier into LogicalBackup objects with BackupDatabase entries, builds BackupExternalDatabase entries for externals, sets both lists on the Backup, and returns it.

**Step 13:** Step updates a LogicalBackup from the adapter response: sets logical backup ID, status, error message, creation/completion times, then maps each returned database by name and updates the matching BackupDatabase with status, size, duration, creation time, path, and error message.

**Step 14:** Step aggregates the overall Backup status and metrics from all LogicalBackup and BackupDatabase entries: collects logical backup statuses, sums DB count and size, counts completed DBs, gathers/logs error messages, then sets backup.status, size, total, completed, and errorMessage accordingly

### Check Backup Async

Periodically scans backups that need processing, tracks corresponding logical backups via adapters, updates aggregated status/attempts, and persists the results.

![Check Backup Async](../images/backup-and-restore-v2/check-backup-async-sequence.png)

Source: [`../diagrams/backup-and-restore-v2/check-backup-async-sequence.puml`](../diagrams/backup-and-restore-v2/check-backup-async-sequence.puml)

**Step 1:** Step returns all Backup entities whose status is IN_PROGRESS

**Steps 6,9:** Step updates a LogicalBackup from the adapter response: sets logical backup ID, status, error message, creation/completion times, then maps each returned database by name and updates the matching BackupDatabase with status, size, duration, creation time, path, and error message.

**Step 10:** Step aggregates the overall Backup status and metrics from all LogicalBackup and BackupDatabase entries: collects logical backup statuses, sums DB count and size, counts completed DBs, gathers/logs error messages, then sets backup.status, size, total, completed, and errorMessage accordingly

### Start Restore

Initiates an asynchronous restore from an existing backup and returns a RestoreResponse; returns 200 if the restore is already completed/failed, otherwise 202. Supports dryRun mode to validate the restore path without mutating state or persisting changes.

![Start Restore](../images/backup-and-restore-v2/start-restore-sequence.png)

Source: [`../diagrams/backup-and-restore-v2/start-restore-sequence.puml`](../diagrams/backup-and-restore-v2/start-restore-sequence.puml)

**Step 8:** Step validates that a restore can proceed: if the Backup status is not COMPLETED, it logs an error and throws exceprion with a message indicating the Backup status. Otherwise it does nothing.

**Steps 9,16:**: Step builds a full Restore from the Backup and RestoreRequest: filters external databases by strategy and filter criteria, filters internal backup databases by filter criteria, throws exception if nothing matches, applies mapping to both internal and external classifiers and checks for collisions, enriches classifiers, groups internal databases by target adapter/logical backup key, creates LogicalRestore and RestoreDatabase entries, then constructs the Restore (name, backup, storage, blob path, strategy, mapping, filter criteria), wires relations, logs a summary, and returns the initialized object.

**Steps 12,20,24:** Step updates a LogicalRestore from adapter response: sets restore ID, status, error, creation/completion times, then for each returned DB updates the matching RestoreDatabase (by previous DB name) with status, duration, path, error, creation time, and possibly new name. If a returned DB isn’t found in current restore list, it logs a warning and marks the logical restore as FAILED with an error message.

**Steps 13,21,25:**: Step aggregates overall restore status and metrics: collects LogicalRestore statuses, counts total and completed restore databases, gathers/logs error messages, then sets restore.status, total, completed, duration, and errorMessage (and logs the aggregated result)

**Step 15:** Step ensures only one restore runs at a time. It acquires a lock, rejects the operation if another restore is already in progress, runs the supplied action, and always releases the lock at the end

### Check Restore Async

Periodically scans restores that need processing (those with status IN_PROGRESS), tracks in‑progress logical restores via adapters, updates aggregated status/attempts, and persists the results; once completed, it ensures users and initializes logical databases.

![Check Restore Async](../images/backup-and-restore-v2/check-restore-async-sequence.png)

Source: [`../diagrams/backup-and-restore-v2/check-restore-async-sequence.puml`](../diagrams/backup-and-restore-v2/check-restore-async-sequence.puml)

**Step 1:** Step returns all Restore entities with status IN_PROGRESS

**Steps 6,9:** Step updates a LogicalRestore from adapter response: sets restore ID, status, error, creation/completion times, then updates each matching RestoreDatabase (by previous DB name) with status, duration, path, error, creation time, and new name if changed. If a returned DB isn’t found, it marks the logical restore as FAILED with an error.

**Step 10:** Step aggregates overall restore status and metrics: collects LogicalRestore statuses, counts total and completed restore databases, gathers/logs error messages, then sets restore.status, total, completed, duration, and errorMessage.

**Step 13:** Step makes sure all required users exist in the target database by calling the adapter for each user (with retries). It logs progress and returns the list of ensured users; if any user cannot be ensured, it throws an error.

**Step 15:** Step creates logical databases based on a completed restore. For each restored internal database it marks any replaced DBs as orphans, creates a new logical DB with classifiers/settings/users, fills connection properties/resources, encrypts credentials, and saves it. For each restored external DB it creates a corresponding logical DB and saves it. Finally it marks the restore as COMPLETED.

### Retry Restore

Retries a failed restore operation by reset/marking its logical restores for retry and returning 202 with the updated RestoreResponse; only allowed when the restore status is FAILED.

![Retry Restore](../images/backup-and-restore-v2/retry-restore-sequence.png)

Source: [`../diagrams/backup-and-restore-v2/retry-restore-sequence.puml`](../diagrams/backup-and-restore-v2/retry-restore-sequence.puml)

**Step 7:** Step ensures only one restore runs at a time. It acquires a lock, rejects the operation if another restore is already in progress, runs the supplied action, and always releases the lock at the end

**Step 9:** Step aggregates overall restore status and metrics: collects LogicalRestore statuses, counts total and completed restore databases, gathers/logs error messages, then sets restore.status, total, completed, duration, and errorMessage.

### Get Backup Metadata

Returns backup metadata and a SHA‑256 Digest header for a completed backup; if the backup is not in COMPLETED status it responds with 422.

![Get Backup Metadata](../images/backup-and-restore-v2/get-backup-metadata-sequence.png)

Source: [`../diagrams/backup-and-restore-v2/get-backup-metadata-sequence.puml`](../diagrams/backup-and-restore-v2/get-backup-metadata-sequence.puml)

**Step 6:**: Step serializes BackupResponse object to deterministic JSON, hashes it with SHA‑256, encodes the hash in Base64, and returns a string like SHA-256=base64.

### Upload Backup Metadata

Uploads backup metadata with a required SHA‑256 Digest header, validates the digest, and either imports the backup or restores a previously deleted imported backup; responds with 409 for invalid state. Also method can return imported DELETED backup to COMPLETED state.

![Upload Backup Metadata](../images/backup-and-restore-v2/upload-backup-metadata-sequence.png)

Source: [`../diagrams/backup-and-restore-v2/upload-backup-metadata-sequence.puml`](../diagrams/backup-and-restore-v2/upload-backup-metadata-sequence.puml)

**Step 2:** Step serializes BackupResponse object to deterministic JSON, hashes it with SHA‑256, encodes the hash in Base64, and returns a string like SHA-256=base64.

### Delete Backup

Deletes a backup by name: if force=false it marks the backup as DELETED, otherwise it starts adapter-side deletion and returns 202; only COMPLETED/FAILED/DELETED statuses are allowed.

![Delete Backup](../images/backup-and-restore-v2/delete-backup-sequence.png)

Source: [`../diagrams/backup-and-restore-v2/delete-backup-sequence.puml`](../diagrams/backup-and-restore-v2/delete-backup-sequence.puml)

## State Diagrams

### Backup

![Backup V2 State Diagram](../images/backup-and-restore-v2/backup-v2-state.png)

Source: [`../diagrams/backup-and-restore-v2/backup-v2-state.puml`](../diagrams/backup-and-restore-v2/backup-v2-state.puml)

### Restore

![Restore V2 State Diagram](../images/backup-and-restore-v2/restore-v2-state.png)

Source: [`../diagrams/backup-and-restore-v2/restore-v2-state.puml`](../diagrams/backup-and-restore-v2/restore-v2-state.puml)

## Configuration Parameters

| Parameter | Environment Variable | Default Value | Description |
|---|---|---|---|
| `dbaas.backup-restore.check.interval` | `DBAAS_BACKUP_RESTORE_CHECK_INTERVAL` | `1m` | Interval for periodic backup/restore status checks. |
| `dbaas.backup-restore.check.attempts` | `DBAAS_BACKUP_RESTORE_CHECK_ATTEMPTS` | `20` | Max number of tracking attempts before marking backup/restore as `FAILED`. |
| `dbaas.backup-restore.retry.delay.seconds` | `DBAAS_BACKUP_RESTORE_RETRY_DELAY_SECONDS` | `3` | Delay between adapter retry attempts. |
| `dbaas.backup-restore.retry.attempts` | `DBAAS_BACKUP_RESTORE_RETRY_ATTEMPTS` | `3` | Max number of adapter retry attempts. |
| `backup.aggregator.async.thread.pool.size` | `BACKUP_AGGREGATOR_ASYNC_THREAD_POOL_SIZE` | `10` | Thread pool size for async backup/restore operations. |
| `shedlock.defaults.lock-at-most-for` | `DBAAS_BACKUP_RESTORE_CHECK_LOCK_TIMEOUT` | `PT10M` | Max lock holding time for schedulers. |
