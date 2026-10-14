# DBaaS Aggregator troubleshooting

## dbaas-aggregator installation and upgrade

### Helm render fails with env variable must be set!

**Symptoms:**

- Deployment of the `dbaas-aggregator` chart fails before any pod or Job is created.
- The deploy log contains one of:
  - `BACKUP_DAEMON_DBAAS_ACCESS_PASSWORD env variable must be set!`
  - `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD env variable must be set!`
  - `DBAAS_TENANT_PASSWORD env variable must be set!`
  - `DBAAS_DB_EDITOR_CREDENTIALS_PASSWORD env variable must be set!`
  - `DISCR_TOOL_USER_PASSWORD env variable must be set!`

**Root cause:**

The template of Secret `dbaas-security-configuration-secret` marks these five passwords as `required`. An empty or
missing deployment parameter stops the Helm render.

**How to check:**

1. Find the parameter named in the error in the deployment parameters of the `dbaas-aggregator` application. It is
   absent or empty.

**How to fix:**

1. Set the named parameter in the deployment parameters of the `dbaas-aggregator` application and redeploy.
2. On an update of an existing installation, reuse the value that was set during the first installation. For
   `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`, the value must also match the credentials configured in every DBaaS
   adapter and in Cloud Core projects (dbaas-agent); a new value breaks adapter registration — see
   `Adapters or clients get 401 from dbaas-aggregator after a credential change`.

**Sources:**

- `helm-templates/dbaas-aggregator/templates/Secret.yaml` (lines 1-5)
- `docs/installation/parameters.md` — section `CREDENTIALS`

### Pre-install hook fails with Wrong parameter: POSTGRES_HOST

**Symptoms:**

- Job `validation-hook-dbaas-aggregator` fails; the Helm release does not progress.
- The Job log contains `Try to reach PostgreSQL host, attempt 36` followed by:

```text
ERROR! CHECK FAILED!
Wrong parameter:
POSTGRES_HOST=<host>
POSTGRES_PORT=<port>
```

**Root cause:**

`validation.sh` opens a TCP connection to `POSTGRES_HOST:POSTGRES_PORT` 36 times with a 5 s delay (about 3 minutes).
It fails with exit code `122` when no attempt succeeds. Causes, most frequent first:

1. `POSTGRES_HOST` or `POSTGRES_PORT` does not identify a reachable PostgreSQL service. With
   `USE_POSTGRES_PORT_FORWARD: true`, `prepare-database.sh` takes the namespace from the second dot-separated segment,
   so the first two segments of `POSTGRES_HOST` must be `<service-name>.<namespace>`; a full cluster DNS name with those
   segments is also valid. With port-forward disabled, another resolvable DNS name is valid. A URL is not valid in
   either mode.
2. The PostgreSQL cluster is down or has no leader at the time of deployment.
3. A NetworkPolicy blocks traffic from the DBaaS namespace to the PostgreSQL namespace.

**How to check:**

1. Compare `POSTGRES_HOST`, `POSTGRES_PORT`, and `USE_POSTGRES_PORT_FORWARD` in the deployment parameters with the
   PostgreSQL service evidence in the ticket. When port-forward is enabled, the host must start with
   `<service>.<namespace>`; otherwise it must be a resolvable hostname. It must not be a pod name or URL.
2. Look in the ticket for PostgreSQL alerts (for example `PostgreSQL is Down`) at the same time. If present, the
   failure is in PostgreSQL; use the PostgreSQL troubleshooting skill.

**How to fix:**

1. Correct `POSTGRES_HOST` / `POSTGRES_PORT` in the deployment parameters of the `dbaas-aggregator` application and
   redeploy. These values are written to Secret `dbaas-aggregator-env-variables` (hook) and to env of Deployment
   `dbaas-aggregator`.
2. If PostgreSQL itself is down, restore PostgreSQL first, then redeploy DBaaS.

**Sources:**

- `validation-image/validation.sh` — function `check_postgres`
- `helm-templates/dbaas-aggregator/templates/HookValidation.yaml`, `HookSecret.yaml`
- `docs/installation/parameters.md` — `POSTGRES_HOST`, `POSTGRES_PORT`

### Pre-install hook fails with It is not possible to create a database from the user

**Symptoms:**

- Job `validation-hook-dbaas-aggregator` fails after about 3 minutes.
- The Job log contains `Try to create test DB, attempt 36` followed by:

```text
ERROR! CHECK FAILED!
It is not possible to create a database from the user:
POSTGRES_USER=<user>
POSTGRES_PASSWORD=*****
```

**Root cause:**

When `DBAAS_OWN_PG_DB_CREATED_MANUALLY` is `false` (default), `validation.sh` runs `CREATE DATABASE
validation_check_database` as `POSTGRES_DBAAS_USER`. If `POSTGRES_DBAAS_USER` / `POSTGRES_DBAAS_PASSWORD` are empty,
it uses `POSTGRES_DBA_USER` / `POSTGRES_DBA_PASSWORD` instead. The check fails when:

1. The password is wrong for that user (most common after a PostgreSQL redeploy with new credentials).
2. The user has no `CREATEDB` privilege. `POSTGRES_DBAAS_USER` must be created with `CREATEDB`.
3. The user does not exist in PostgreSQL.

**How to check:**

1. Read the `POSTGRES_USER=` line in the Job log. It tells which parameter pair was used: the DBaaS user or the DBA
   user.
2. Compare that user and password with the deployment parameters of the PostgreSQL application from the ticket.

**How to fix:**

1. Correct `POSTGRES_DBAAS_USER` / `POSTGRES_DBAAS_PASSWORD` (or `POSTGRES_DBA_USER` / `POSTGRES_DBA_PASSWORD`) in the
   deployment parameters of the `dbaas-aggregator` application and redeploy.
2. If the user lacks `CREATEDB`, the PostgreSQL DBA must grant it (`ALTER USER <user> CREATEDB`) before redeploy.
3. If the DBaaS user is not allowed to create databases by policy, pre-create the registry database and set
   `DBAAS_OWN_PG_DB_CREATED_MANUALLY: true` together with `POSTGRES_DBAAS_DATABASE_NAME`, `POSTGRES_DBAAS_USER`,
   `POSTGRES_DBAAS_PASSWORD`. With PostgreSQL 15+ the user also needs `GRANT ALL ON SCHEMA public` on that database.

**Sources:**

- `validation-image/validation.sh` — function `check_postgres`
- `docs/installation/parameters.md` — `POSTGRES_DBAAS_DATABASE_NAME / POSTGRES_DBAAS_USER / POSTGRES_DBAAS_PASSWORD`,
  `DBAAS_OWN_PG_DB_CREATED_MANUALLY`

### Pre-install hook fails because POSTGRES_DBAAS_DATABASE_NAME is missing or does not exist

**Symptoms:**

- Job `validation-hook-dbaas-aggregator` fails.
- The Job log contains one of:

```text
ERROR! CHECK FAILED!
POSTGRES_DBAAS_DATABASE_NAME must be set if DBAAS_OWN_PG_DB_CREATED_MANUALLY=true
```

```text
ERROR! CHECK FAILED!
POSTGRES_DBAAS_DATABASE_NAME=<name> does not exists.
```

**Root cause:**

With `DBAAS_OWN_PG_DB_CREATED_MANUALLY: true`, DBaaS does not create its registry database. The database named in
`POSTGRES_DBAAS_DATABASE_NAME` must already exist and accept a connection from `POSTGRES_DBAAS_USER`.

**How to check:**

1. Check that `POSTGRES_DBAAS_DATABASE_NAME` is set in the deployment parameters of the `dbaas-aggregator`
   application.
2. Compare the name with the database the DBA created. Names are case-sensitive.

**How to fix:**

1. Set `POSTGRES_DBAAS_DATABASE_NAME` to the existing database name and redeploy, or ask the DBA to create the
   database and grant `POSTGRES_DBAAS_USER` access to it.

**Sources:**

- `validation-image/validation.sh` — function `check_postgres`

### Pre-hook fails with Postgres user dbaas_user_<namespace> already exists

**Symptoms:**

- Job `dbaas-aggregator-pre-hook` fails with exit code `121`.
- The Job log contains `Create db as dba user` and then:

```text
Postgres user dbaas_user_<namespace> already exists, so you should pass dbaas specific user through POSTGRES_DBAAS_USER:POSTGRES_DBAAS_PASSWORD parameters
```

**Root cause:**

Secret `dbaas-storage-credentials` does not exist in the DBaaS namespace, so `prepare-database.sh` tries to create the
registry user `dbaas_user_<namespace>` (hyphens replaced with `_`) through `POSTGRES_DBA_USER`. The user already exists
in PostgreSQL from an earlier installation, and its generated password is unknown. Typical trigger: the namespace was
recreated or Secret `dbaas-storage-credentials` was deleted while the PostgreSQL user and registry database stayed.

**How to check:**

1. The Job log line `Create db as dba user` confirms the DBA path was taken.
2. The ticket history shows a namespace recreation, a cleanup, or a manual deletion of `dbaas-storage-credentials`.

**How to fix:**

1. Set `POSTGRES_DBAAS_USER: dbaas_user_<namespace>` and `POSTGRES_DBAAS_PASSWORD: <known password>` in the deployment
   parameters of the `dbaas-aggregator` application. If the password is unknown, the PostgreSQL DBA resets it with
   `ALTER USER dbaas_user_<namespace> WITH PASSWORD '<new password>'` first. Redeploy.
2. Keep `POSTGRES_DBAAS_DATABASE_NAME` pointing at the existing registry database (default: namespace name with `-`
   replaced by `_`), so DBaaS reuses its registry instead of starting with an empty one.

**How to avoid this issue:**

Do not delete Secret `dbaas-storage-credentials`. It is the only place where the generated registry password is
stored.

**Sources:**

- `validation-image/prepare-database.sh`
- `helm-templates/dbaas-aggregator/templates/HookSecret.yaml` — default database name

### Pre-hook fails with Dbaas needs a postgres dbaas user for storing information

**Symptoms:**

- Job `dbaas-aggregator-pre-hook` fails with exit code `121`.
- The Job log contains:

```text
Dbaas needs a postgres dbaas user for storing information, so you should pass dbaas specific user through POSTGRES_DBAAS_USER:POSTGRES_DBAAS_PASSWORD parameters
```

**Root cause:**

Secret `dbaas-storage-credentials` does not exist and neither credential pair is complete: `POSTGRES_DBAAS_USER` /
`POSTGRES_DBAAS_PASSWORD` and `POSTGRES_DBA_USER` / `POSTGRES_DBA_PASSWORD` both have an empty value.

**How to check:**

1. Check the four parameters in the deployment parameters of the `dbaas-aggregator` application. At least one pair
   must have both values set.

**How to fix:**

1. Set `POSTGRES_DBAAS_USER` / `POSTGRES_DBAAS_PASSWORD` (preferred) or `POSTGRES_DBA_USER` / `POSTGRES_DBA_PASSWORD`
   and redeploy.

**Sources:**

- `validation-image/prepare-database.sh`
- `docs/installation/parameters.md` — `POSTGRES_DBA_USER / POSTGRES_DBA_PASSWORD`

### Pre-hook fails with database creation error

**Symptoms:**

- Job `dbaas-aggregator-pre-hook` fails with exit code `121`.
- The Job log contains `start create db with host=...` followed by a `psql` error and `database creation error`.

**Root cause:**

`CREATE DATABASE <POSTGRES_DBAAS_DATABASE_NAME>` failed. The `psql` error above the message names the reason:
authentication failure, missing `CREATEDB`, or PostgreSQL unavailable. With `USE_POSTGRES_PORT_FORWARD: true` the hook
connects through a port-forward to the pod labeled `pgtype=primary` in the namespace taken from `POSTGRES_HOST`; when
no such pod exists, the connection to `localhost` fails.

**How to check:**

1. Read the `psql` error line directly above `database creation error`.
2. If the log shows `pg primary pod name` with an empty value, the port-forward path found no primary pod.

**How to fix:**

1. For authentication or privilege errors, apply the fix from
   `Pre-install hook fails with It is not possible to create a database from the user`.
2. If the deployer has direct network access to PostgreSQL, set `USE_POSTGRES_PORT_FORWARD: false` in the deployment
   parameters of the `dbaas-aggregator` application and redeploy.

**Sources:**

- `validation-image/prepare-database.sh` — functions `create_db`, port-forward block

### Changed registry credentials in deployment parameters have no effect

**Symptoms:**

- `POSTGRES_DBAAS_USER`, `POSTGRES_DBAAS_PASSWORD`, `POSTGRES_DBAAS_DATABASE_NAME`, or
  `DBAAS_OWN_PG_DB_CREATED_MANUALLY` were changed and DBaaS was redeployed, but dbaas-aggregator still uses the old
  user, password, or database.
- The `dbaas-aggregator-pre-hook` Job log contains:

```text
Secret with name "dbaas-storage-credentials" exists. Skip prepare pg database
```

**Root cause:**

dbaas-aggregator reads its registry credentials only from Secret `dbaas-storage-credentials` (keys `username`,
`password`, `database`, mounted at `/etc/secrets/pod-secrets`). `prepare-database.sh` creates this Secret once and
skips all database preparation on every later deployment while the Secret exists. The deployment parameters are not
read again.

**How to check:**

1. The skip message above is present in the `dbaas-aggregator-pre-hook` Job log.

**How to fix:**

1. To rotate the registry password: the PostgreSQL DBA runs `ALTER USER <username from dbaas-storage-credentials> WITH
PASSWORD '<new password>'`; then update key `password` in Secret `dbaas-storage-credentials`; then redeploy
   dbaas-aggregator. Also update `POSTGRES_DBAAS_PASSWORD` in the deployment parameters to keep them consistent.
2. To move the registry to another database, migrate the registry data first, then update keys `username`, `password`,
   `database` in Secret `dbaas-storage-credentials` to the new values and redeploy dbaas-aggregator. Do not point the
   Secret at an empty database unless losing the existing registry contents is intended.

**How to avoid this issue:**

Treat Secret `dbaas-storage-credentials` as the source of truth for registry credentials after the first install.

**Sources:**

- `validation-image/prepare-database.sh` — `isSecretExist` check
- `helm-templates/dbaas-aggregator/templates/Deployment.yaml` — volume `pod-secrets`
- `docs/howto/Rotate credentials.md` — section `3. DBaaS Aggregator -> PG`

### dbaas-aggregator pods stay Pending after installation

**Symptoms:**

- Pods of Deployment `dbaas-aggregator` (or `dbaas-operator`) stay in `Pending`.
- Pod events report that no node matches the node selector.
- Alert `Pod is Down` can report a `dbaas-aggregator` pod with this state.

**Root cause:**

Both charts set `nodeSelector: <NODE_SELECTOR_DBAAS_KEY>: <REGION_DBAAS>`, default `region: database`. Nodes without
this label cannot host the pods.

**How to check:**

1. Compare `NODE_SELECTOR_DBAAS_KEY` / `REGION_DBAAS` in the deployment parameters with the node labels shown in the
   ticket.

**How to fix:**

1. Set `NODE_SELECTOR_DBAAS_KEY` and `REGION_DBAAS` in the deployment parameters of the `dbaas-aggregator` (and
   `dbaas-operator`) application to a label that exists on the target nodes, and redeploy; or ask the cluster
   administrator to label the nodes `region=database`.

**Sources:**

- `helm-templates/dbaas-aggregator/templates/Deployment.yaml` — `nodeSelector`
- `docs/installation/installation.md` — section `Requirements`, item 3

## dbaas-aggregator runtime

### dbaas-aggregator lost connection to its PostgreSQL registry

**Symptoms:**

- dbaas-aggregator log contains `Postgres connection is lost`.
- dbaas-aggregator log contains lines like
  `Catch exception = ... while trying to find all physical databases in Postgre, go to h2 database`.
- `GET /health` returns `503` with component `dbaasPostgresConnect` in status `DOWN`.
- The pod stays `Running` and `Ready`; it is not restarted.
- Existing databases are still returned to services, but creating databases, registering adapters, and other write
  operations fail with `500`.

**Root cause:**

Liveness (`/probes/live`) and readiness (`/probes/ready`) check only the in-memory H2 cache and password encryption.
They do not check PostgreSQL. When the registry PostgreSQL is unreachable, read requests fall back to the H2 copy of
the registry (refreshed every 10 minutes), and writes fail. Causes:

1. The PostgreSQL cluster in `POSTGRES_HOST` is down, in failover, or has no leader.
2. The password of the registry user was changed in PostgreSQL but not in Secret `dbaas-storage-credentials`.
3. DNS or network policy no longer permits dbaas-aggregator to reach `POSTGRES_HOST:POSTGRES_PORT`.

**How to check:**

1. Look in the ticket for PostgreSQL alerts or a failover at the time `Postgres connection is lost` started.
2. Look in the aggregator log for an authentication error from the PostgreSQL JDBC driver
   (`password authentication failed for user`). If present, cause 2 applies.
3. A timeout, name-resolution failure, or connection-refused message without a matching PostgreSQL outage identifies
   the network or DNS path; use the supplied NetworkPolicy and service evidence to locate the mismatch.

**How to fix:**

1. Cause 1: restore the PostgreSQL cluster (PostgreSQL troubleshooting skill). dbaas-aggregator reconnects by itself;
   no restart is needed.
2. Cause 2: update key `password` in Secret `dbaas-storage-credentials` to the current PostgreSQL password and
   redeploy dbaas-aggregator — see `Changed registry credentials in deployment parameters have no effect`.
3. Cause 3: restore DNS resolution and allow traffic from the DBaaS namespace to `POSTGRES_HOST:POSTGRES_PORT`.

**Sources:**

<!-- markdownlint-disable line-length -->

- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/monitoring/indicators/DbaasPostgresConnectHealthCheck.java`

<!-- markdownlint-enable line-length -->
<!-- markdownlint-disable line-length -->

- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/monitoring/indicators/DbaasH2StorageConnectHealthCheck.java`
- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/service/HealthService.java`
- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/controller/HealthController.java`
- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/dao/jpa/PhysicalDatabaseDbaasRepositoryImpl.java`

<!-- markdownlint-enable line-length -->

- `dbaas/dbaas-aggregator/src/main/resources/application.properties` — `dbaas.h2.sync.every`

### dbaas-aggregator is not ready because password encryption fails

**Symptoms:**

- Readiness probe `/probes/ready` returns `503`; the pod is `Running` but not `Ready`, or is restarted by the liveness
  probe `/probes/live`.
- Alert `Pod is Down` fires for `dbaas-aggregator`.
- The probe response or log contains `PasswordEncryption does not work`.
- Services get connection properties without a usable password; the log contains
  `An error occurred while getting decrypted password` or
  `Got incorrect connection properties without valid password field, cannot decrypt password.`

**Root cause:**

Database passwords in the registry are encrypted with the keys from Secret `dbaas-aggregator-encryption-secret` (keys
`sym-key` and `default-key`, mounted as `SYM_KEY` and `KS_DEFAULT_KEY` under `/etc/secrets/pod-secrets`). The
`dbaas-aggregator-pre-hook` Job generates new random keys whenever this Secret is missing. If the Secret was deleted
or replaced, encryption of new passwords may work, but the passwords stored with the old keys can no longer be
decrypted.

**How to check:**

1. In the `dbaas-aggregator-pre-hook` Job log of the last deployment, `The secret [encryption-secret] not found!`
   means new keys were generated. `The Secret [dbaas-aggregator-encryption-secret] already exists.` means the keys
   were kept.
2. Check the ticket history for a namespace recreation, a Secret cleanup, or a restore of the namespace without
   Secrets.

**How to fix:**

1. If the original Secret is available from a backup of the namespace or from another site, restore it with its
   original `sym-key` and `default-key` values, then restart dbaas-aggregator.
2. If the original keys are lost, the stored passwords cannot be recovered. Rotate the passwords of affected logical
   databases through the DBaaS change-password API (`docs/rest-api.md` — `Change user password`) so they are stored
   with the new keys. Escalate to the DBaaS team before doing this on production.

**How to avoid this issue:**

Never delete Secret `dbaas-aggregator-encryption-secret`. Include it in namespace backups.

**Sources:**

- `validation-image/prepare-database.sh` — function `ensure-encryption-secret`
- `helm-templates/dbaas-aggregator/templates/Deployment.yaml` — volume `pod-secrets`

<!-- markdownlint-disable line-length -->

- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/monitoring/indicators/PasswordEncryptionHealthIndicator.java`

<!-- markdownlint-enable line-length -->

- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/service/PasswordEncryption.java`

### dbaas-aggregator is OOMKilled or slow on environments with many databases

**Symptoms:**

- The `dbaas-aggregator` container is terminated with reason `OOMKilled`; alert `Pod is Down` fires for the pod.
- `java.lang.OutOfMemoryError: Java heap space` in the aggregator log.
- Long GC pauses on the `GC Section` of the DBaaS Grafana dashboard; requests time out.

**Root cause:**

dbaas-aggregator keeps a full copy of the registry in an in-memory H2 database. Heap demand grows with the number of
logical databases: about 128 MB of `-Xmx` per 1000 databases. The default profiles set `-Xmx128m`, which fits up to
about 500 databases.

**How to check:**

1. Get the number of logical databases from the ticket (dashboard panel `Number Of Databases`, or
   `overallLogicalDbNumber` from `GET /api/v3/dbaas/debug/internal/info`).
2. Read the current `MEM_ARGS` and `MEMORY_LIMIT` from the deployment parameters.

**How to fix:**

1. Set `-Xmx` inside `MEM_ARGS` by database count: up to 500 → `-Xmx128m`, 500-1000 → `-Xmx256m`, 1001-2000 →
   `-Xmx384m`, 2001-3000 → `-Xmx512m`, 3001-4000 → `-Xmx640m`. Keep `-Xms` equal to `-Xmx`.
2. Raise `MEMORY_LIMIT` to cover the new heap: JVM memory = `-Xmx` + `MaxMetaspaceSize` + `ReservedCodeCacheSize` +
   `MaxDirectMemorySize` + `Xss`×200; `MEMORY_LIMIT` = JVM memory × 1.2. `MEMORY_LIMIT` sets both `requests.memory`
   and `limits.memory` of container `dbaas-aggregator`.
3. Set both values in the deployment parameters of the `dbaas-aggregator` application and redeploy. Do not edit
   Deployment `dbaas-aggregator` directly: the next redeploy reverts the edit.

**Sources:**

- `docs/installation/Heap memory for H2 cache.md`
- `helm-templates/dbaas-aggregator/values.yaml`
- `helm-templates/dbaas-aggregator/resource-profiles/dev.yaml`
- `helm-templates/dbaas-aggregator/resource-profiles/dev-ha.yaml`
- `helm-templates/dbaas-aggregator/resource-profiles/prod-nonha.yaml`
- `helm-templates/dbaas-aggregator/resource-profiles/prod.yaml`
- `helm-templates/dbaas-aggregator/templates/Deployment.yaml` — `args`, `resources`

### Physical database adapter reports a problem

**Symptoms:**

- dbaas-aggregator log repeats every 2 minutes: `<type> <adapter identifier> has problem. Status: <status>`.
- `GET /health` returns `200` with status `PROBLEM` and component `adaptersAccessIndicator`.
- Metric `dbaas_adapter_health{type="<type>",identifier="<id>"}` is `0`; dashboard panel `Adapter Health Status` shows
  the adapter down.
- `GET /api/v3/dbaas/debug/internal/info` shows `healthStatus` other than `UP` for a `physicalDatabaseId`.

**Root cause:**

dbaas-aggregator reports the health returned by every registered, non-disabled adapter every 2 minutes. A non-`UP`
state can come from the adapter, the database cluster behind it, or the network path from dbaas-aggregator to the
adapter. The health state alone does not select one of those causes.

**How to check:**

1. Take `<type>` and `<adapter identifier>` from the log line. The type names the database (`postgresql`, `mongodb`,
   `cassandra`, `opensearch`, `clickhouse`, `arangodb`, `redis`).
2. Look in the ticket for alerts and logs of that database cluster and adapter. If neither reports a failure, inspect
   the supplied adapter-address and NetworkPolicy evidence.

**How to fix:**

1. If the adapter or database cluster reports the initiating failure, continue with the troubleshooting skill of that
   database type. If only dbaas-aggregator cannot reach the adapter, correct the registered adapter address or network
   policy. The status returns to `UP` on the next poll after connectivity and adapter health recover.

**Sources:**

- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/monitoring/AdapterHealthCheck.java`
- `docs/FAQ/DBaaS Troubleshooting.md` — `Get overall status`

## Adapter registration and physical database placement

### Adapter registration rejected because the adapter address changed

**Symptoms:**

- The DBaaS adapter fails to register; the aggregator returns `409`.
- dbaas-aggregator log contains:
  - `Address from request: <new address> conflicts with already saved address: <old address>`, or
  - `sent physicalDatabaseIdentifier = <new id>, stored at db = <old id>`

**Root cause:**

A physical database is identified by `physicalDatabaseId` (phydbid) and by adapter address; both are unique and
immutable. The registration is rejected when:

1. The same phydbid is registered again from a different adapter address. Typical triggers: the database cluster was
   reinstalled in a different namespace, or the adapter service was renamed. A change only from `http` to `https` on
   the same host is allowed.
2. The same adapter address is registered with a different phydbid. Typical trigger: the phydbid parameter of the
   database cluster was changed during redeploy.

**How to check:**

1. Compare the two values in the log line with the ticket: which namespace or phydbid is new.

**How to fix:**

1. Prefer restoring the previous value: deploy the database cluster with the original phydbid, or keep the original
   namespace/service name.
2. **DANGEROUS — removes the physical-database registration until its adapter registers again.** Only when
   `GET /api/v3/dbaas/debug/internal/databases?filter=physicalDbId==<phydbid>` returns an empty list and the physical
   database is not the global default, call `DELETE /api/v3/dbaas/{type}/physical_databases/{phydbid}` with role
   `dba_client`. The API otherwise returns `406` with `Connected logical databases still exist` or `because it's
global`.
3. If logical databases still point to the old physical database, they must be migrated first — see
   `docs/howto/Migrate logical DBs between physical DBs.md`. Do not delete the physical database registration in this
   state.

**Sources:**

- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/service/PhysicalDatabasesService.java` —
  `findAndValidate`, `findAndValidateV3`

<!-- markdownlint-disable line-length -->

- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/controller/v3/PhysicalDatabaseRegistrationControllerV3.java` — delete checks

<!-- markdownlint-enable line-length -->

- `docs/rest-api.md` — `Register new physical database`, `Delete physical database`

### Adapter registration handshake returns Request to adapter failed. Status code

**Symptoms:**

- The DBaaS adapter fails to register; the aggregator returns `502`.
- The response or aggregator log contains `CORE-DBAAS-4017` and `Request to adapter failed. Status code: <code>`.

**Root cause:**

During registration dbaas-aggregator calls back the adapter at `adapterAddress` with the credentials sent in the
registration request. `CORE-DBAAS-4017` is produced only when that handshake receives a non-`200` HTTP response:

1. `401`/`403`: the credentials the adapter sent in `httpBasicCredentials` are not the ones the adapter itself accepts
   (mismatch inside the database cluster's deployment parameters).
2. `404`: `adapterAddress` or the adapter API path/version is wrong.
3. `5xx`: the adapter received the handshake but failed while processing it.

A DNS failure, connection refusal, or timeout occurs before an HTTP response and therefore is not established by this
error code; classify that from the adjacent exception instead.

**How to check:**

1. Read `<code>` in the message.
2. Compare `adapterAddress` from the adapter's registration log with the adapter service name and namespace. Format:
   `<schema>://<service-name>.<namespace>:<port>`.
3. For `5xx`, inspect the adapter log at the same timestamp for the initiating failure.

**How to fix:**

1. For `401`/`403`, align the adapter's own API credentials in the deployment parameters of the database cluster and
   redeploy it (see that database's troubleshooting skill).
2. For `404`, correct the adapter address or deploy a compatible adapter API version.
3. For `5xx`, fix the adapter-side failure found in its log.

**Sources:**

<!-- markdownlint-disable line-length -->

- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/service/PhysicalDatabaseRegistrationHandshakeClient.java`

<!-- markdownlint-enable line-length -->
<!-- markdownlint-disable line-length -->

- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/exceptions/AdapterUnavailableException.java`
- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/controller/error/AdapterUnavailableExceptionMapper.java`

<!-- markdownlint-enable line-length -->

### Adapters or clients get 401 from dbaas-aggregator after a credential change

**Symptoms:**

- DBaaS adapters cannot register; dbaas-agent or microservices cannot get databases; both receive `401` from
  dbaas-aggregator.
- Started after `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` / `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD` were changed or
  regenerated, or after DBaaS was reinstalled.

**Root cause:**

DBaaS adapters register with the `cluster-dba` credentials, and dbaas-agent / Cloud Core projects call DBaaS with the
same credentials. They are configured separately in each consumer. A value changed only in DBaaS (or only in a
consumer) causes `401`.

**How to check:**

1. Compare the time of the first `401` with the time of the last DBaaS or database cluster deployment.
2. Compare `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` in DBaaS deployment parameters with the value in the failing
   adapter's or project's parameters.

**How to fix:**

1. Use one value everywhere: set the same credentials in the deployment parameters of `dbaas-aggregator`, of every
   database cluster that has a DBaaS adapter, and of every Cloud Core project. Redeploy them in that order, then
   restart dbaas-agent. Full order: `docs/howto/Rotate credentials.md`, section `6. DBaaS Adapter -> DBaaS Aggregator`.

**Sources:**

- `docs/howto/Rotate credentials.md` — section 6
- `docs/installation/parameters.md` — `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME / DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`

### Database creation fails with Requested physical database is not registered

**Symptoms:**

- Database creation returns `400` with `CORE-DBAAS-4000` and `Requested physical database is not registered`, followed
  by either `DB type: <type>` or `Identifier: <physicalDatabaseId>`.

**Root cause:**

DBaaS resolved database placement to a physical database that is not registered. The detail identifies the path:

1. `Identifier: <physicalDatabaseId>` — `InternalDatabase.spec.physicalDatabaseId`, the create request, or a namespace
   or permanent balancing rule pins placement to an ID that is not registered.
2. `DB type: <type>` — no namespace or microservice rule selected a database, and no registered physical database of
   that type is marked global. The adapter may not have registered yet, or registered databases exist but none is
   global.

**How to check:**

1. Read the detail after `Requested physical database is not registered` to distinguish `Identifier` from `DB type`.
2. Check the response of `GET /api/v3/dbaas/all/physical_databases` in the ticket for the named ID or an entry of
   `<type>` and its `global` flag.
3. For `Identifier`, compare the missing ID with `InternalDatabase.spec.physicalDatabaseId` and the applicable
   namespace/permanent balancing rule. For `DB type`, check the adapter log for registration errors.

**How to fix:**

1. For `Identifier`, change `InternalDatabase.spec.physicalDatabaseId` or the balancing rule to the exact ID of a
   registered physical database. If that ID should exist, fix its adapter registration instead; registration retries
   periodically.
2. For `DB type`, fix adapter registration when no adapter of the type is present.
   `DBAAS_PREMATURE_REGISTRATION_ADAPTER_ADDRESSES` (deployment parameter of `dbaas-aggregator`) makes listed adapters
   register right after the aggregator starts.
3. If adapters of the type exist but none is global, make one the default with
   `PUT /api/v3/dbaas/{type}/physical_databases/{phydbid}/global` (role `dba_client`).

**Sources:**

- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/service/DBaaService.java` — `getAdapter`
- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/service/BalancingRulesService.java` —
  `getDatabaseForNamespace`, `getGlobalPhysicalDatabaseIdentifier`
- `docs/rest-api.md` — `Change default physical database`

### Database creation fails with No balancing rule exists to determine physical database

**Symptoms:**

- Database creation returns `500` with `CORE-DBAAS-4033` and
  `No balancing rule exists to determine physical database for microservice = <ms> and namespace = <ns>`.
- dbaas-aggregator log contains
  `Default physical databases are disabled and no dedicated rules found for namespace <ns> and database type <type>`
  and `Unable to determine physical database from rules for microservice '<ms>' in namespace '<ns>'`.

**Root cause:**

DBaaS selects the physical database in this order: microservice rule → namespace rule (including permanent rules) →
default physical database of the type. `DBAAS_DEFAULT_PHYSICAL_DATABASES_DISABLED: true` removes the last step, so a
namespace without its own rule for `<type>` gets no physical database.

**How to check:**

1. Check `DBAAS_DEFAULT_PHYSICAL_DATABASES_DISABLED` in the deployment parameters of `dbaas-aggregator`.
2. Look in the ticket for a `NamespaceBalancingRule`, `PermanentBalancingRule`, or `MicroserviceBalancingRule` CR for
   namespace `<ns>` and type `<type>`, and for its status `Ready=True`.

**How to fix:**

1. Create a balancing rule for the namespace: a `NamespaceBalancingRule` CR in namespace `<ns>`, or a
   `PermanentBalancingRule` entry for `<ns>` and `<type>`; or the equivalent REST API
   (`docs/rest-api.md` — `Balancing rules`).
2. Set `DBAAS_DEFAULT_PHYSICAL_DATABASES_DISABLED: false` only if the environment does not require manual placement.

**Sources:**

- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/service/BalancingRulesService.java` —
  `applyBalancingRules`, `applyNamespaceBalancingRule`
- `docs/installation/parameters.md` — `DBAAS_DEFAULT_PHYSICAL_DATABASES_DISABLED`

### Microservice balancing rule points to a label that matches no physical database

**Symptoms:**

- Database creation or rule validation returns `500` with one of:
  - `Physical database with label: <key>=<value> not registered`
  - `More than one physical database registered with label: <key>=<value>`
  - `Labels=<label> from request incorrect`
- dbaas-aggregator log contains `Rule in namespace=<ns> with microservice=<ms> and databaseType=<type> has no labels`
  or `... is empty`.

**Root cause:**

A microservice balancing rule selects the physical database by one label `<key>=<value>`. Exactly one registered
physical database of that type must carry this label in its registration `labels`.

**How to check:**

1. Compare the rule's `label` with the `labels` of the physical databases of that type in
   `GET /api/v3/dbaas/all/physical_databases`.

**How to fix:**

1. Fix `label` in the `MicroserviceBalancingRule` CR (or the rule sent to
   `PUT /api/v3/dbaas/{namespace}/physical_databases/rules/onMicroservices`) to a label carried by exactly one physical
   database, or add the label to the database cluster's adapter registration parameters and redeploy that cluster.

**Sources:**

- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/service/BalancingRulesService.java` —
  `getCheckPhysicalDatabaseByLabel`, `processMicroserviceBalancingRules`

## Logical database requests

### Delete request rejected with CORE-DBAAS-4003

**Symptoms:**

- A delete of a logical database, a balancing rule, or a backup returns `403` with `CORE-DBAAS-4003` and
  `Deleting logical databases and balancing rules is allowed only in non prod mode`.

**Root cause:**

`PRODUCTION_MODE` is `true` (default). In this mode DBaaS rejects every API call that drops databases or rules.

**How to check:**

1. Check `PRODUCTION_MODE` in the deployment parameters of `dbaas-aggregator`.

**How to fix:**

1. On production keep `PRODUCTION_MODE: true`. For a lost database use the recreate API instead of delete — see
   `Application cannot connect although DBaaS returns connection properties`.
2. **DANGEROUS — enables destructive database, rule, and backup deletion endpoints until the value is restored.** Only
   on an environment confirmed to be non-production and where deletion is intended, set `PRODUCTION_MODE: false` in
   the deployment parameters of `dbaas-aggregator` and redeploy. Set it back to `true` after the deletion and redeploy.

**Sources:**

- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/controller/abstact/AbstractController.java`

<!-- markdownlint-disable line-length -->

- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/exceptions/ForbiddenDeleteOperationException.java` — mapped to `403`

<!-- markdownlint-enable line-length -->

### Request rejected with namespace isolation error CORE-DBAAS-4055 or CORE-DBAAS-4004

**Symptoms:**

- A service gets `403` with one of:
  - `CORE-DBAAS-4055` with:

    ```text
    Namespace from path '<a>' and namespace from jwt token '<b>' doesn't not match or aren't in the same composite structure
    ```

  - `CORE-DBAAS-4004`: `Namespace from request '<a>' is not equal to one from database classifier '<b>'.`

**Root cause:**

1. `CORE-DBAAS-4055`: the request uses a Kubernetes service-account token (M2M) whose namespace differs from the
   namespace in the URL, and the two namespaces are not in one composite structure. Checked only when
   `DBAAS_SECURITY_NAMESPACE_ISOLATION_ENABLED: true` (default) and the caller is not `CLUSTER_OPERATOR`.
2. `CORE-DBAAS-4004`: `classifier.namespace` in the request body differs from `{namespace}` in the URL.

**How to check:**

1. Compare the two namespaces in the message.
2. For 4055, check whether the calling namespace should be part of a composite structure (Blue-Green or composite
   deployment) with the target namespace.

**How to fix:**

1. 4004: fix the client so `classifier.namespace` equals the URL namespace.
2. 4055: register the composite structure (`docs/rest-api.md` — `Save or Update Composite Structure`) when the
   namespaces belong together; otherwise fix the client to call its own namespace. Do not recommend
   `DBAAS_SECURITY_NAMESPACE_ISOLATION_ENABLED: false` as a fix.

**Sources:**

<!-- markdownlint-disable line-length -->

- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/security/validators/NamespaceValidationRequestFilter.java`

<!-- markdownlint-enable line-length -->

- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/exceptions/ErrorCodes.java`

<!-- markdownlint-disable line-length -->

- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/controller/error/ForbiddenNamespaceExceptionMapper.java`

<!-- markdownlint-enable line-length -->

### Request rejected because the role is not allowed CORE-DBAAS-4023

**Symptoms:**

- A service gets `403` with `CORE-DBAAS-4023` and
  `Requested role <role> from '<owner>' service cannot be accessed by '<caller>' service`.

**Root cause:**

Access to another service's database, or to a non-default role, is controlled by the access policy of the owning
service: `DatabaseAccessPolicy` CR (dbaas-operator) or legacy `DbPolicy` declaration. The policy does not grant
`<role>` to `<caller>`.

**How to check:**

1. Find the `DatabaseAccessPolicy` (or `DbPolicy`) of `<owner>` in the ticket and check whether `<caller>` and
   `<role>` are listed in `services`/`policy`.
2. If the policy is a CR, check its status is `Ready=True`; a policy that never applied has no effect.

**How to fix:**

1. Add `<caller>` with `<role>` to the owner's `DatabaseAccessPolicy` and redeploy the owning service.

**Sources:**

- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/exceptions/ErrorCodes.java`
- `dbaas-operator/docs/DBaaS Operator.md` — section `DatabaseAccessPolicy`

### Database lookup returns CORE-DBAAS-4006

**Symptoms:**

- Get-by-classifier returns `404` with `CORE-DBAAS-4006` and `No database of type: '<type>' with classifier: <...>`.
- A `DatabaseSecretClaim` reports reason `DatabaseNotFound`.

**Root cause:**

No database with exactly this classifier and type is registered. Classifier matching is exact: `microserviceName`,
`scope`, `namespace`, `tenantId`, and every `customKeys` / top-level extra key must be equal.

**How to check:**

1. Compare the classifier from the message with the classifier of the database owner's declaration
   (`InternalDatabase` CR or the owner's dbaas-client configuration).
2. Check the response of `GET /api/v3/dbaas/debug/internal/databases?filter=namespace==<ns>;microservice==<ms>` in the
   ticket for the registered classifier.

**How to fix:**

1. Make the consumer's classifier equal to the registered one, or create the database first (deploy the owning
   service / `InternalDatabase`).

**Sources:**

- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/exceptions/ErrorCodes.java`
- `dbaas-operator/docs/howto/migrate-declarations-from-core-operator.md` — section `Gotchas`

### Request rejected with Invalid classifier CORE-DBAAS-4010

**Symptoms:**

- A request returns `400` with `CORE-DBAAS-4010` and `Invalid classifier. <reason>. Classifier: <...>`.

**Root cause:**

The v3 API requires `microserviceName`, `scope` (`service` or `tenant`), `namespace`, and for `scope: tenant` a
`tenantId`.

**How to check:**

1. Read `<reason>` and the classifier in the message; find the missing or wrong field.

**How to fix:**

1. For dbaas-client configuration, fix the classifier and redeploy the owning service.
2. `InternalDatabase.spec.classifier` is immutable. Create a replacement CR with a different `metadata.name` and the
   correct classifier instead of editing the existing CR in place.

**Sources:**

- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/exceptions/ErrorCodes.java`
- `docs/rest-api.md` — section `Classifier`

### Request rejected with Operation already in progress CORE-DBAAS-4053

**Symptoms:**

- A request returns `409` with `CORE-DBAAS-4053` and `Operation '<name>' is already in progress.`

**Root cause:**

DBaaS permits only one restore operation at a time unless the restore request explicitly allows parallel execution.
The global restore lock or an existing non-completed restore rejected the new restore request.

**How to check:**

1. Read the active restore names appended to the error, when present, and find their states in the supplied restore
   status evidence.

**How to fix:**

1. Wait until every active restore finishes, then retry. Do not use the `/allowParallel` restore endpoint merely to
   bypass this error; use it only when the restore procedure explicitly permits concurrent restores.

**Sources:**

- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/exceptions/OperationAlreadyRunningException.java`
- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/service/DbBackupV2Service.java` —
  `restoreLockWrapper`

<!-- markdownlint-disable line-length -->

- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/controller/error/OperationAlreadyRunningExceptionMapper.java`

<!-- markdownlint-enable line-length -->

## Registry consistency

### Application cannot connect although DBaaS returns connection properties

**Symptoms:**

- Dashboard panel `Registry discrepancy count over time` or `Number of Lost Databases per Adapter Type` is above 0.
- A microservice fails to start because it cannot connect to its database (for example `database "<name>" does not
exist`), while DBaaS returns its connection properties without error.

**Root cause:**

The database is **lost**: registered in DBaaS but missing in the physical database. It was deleted directly in the
database cluster (manually, by a failed cleanup, by storage loss, or by restoring the cluster from an older backup).
DBaaS trusts its registry and keeps returning the stale record. The metric
`dbaas_registration_databases_lost_number` is recalculated every 10 minutes.

**How to check:**

1. Find the affected database in the response of `GET /api/v3/dbaas/debug/internal/lost` attached to the ticket.

**How to fix:**

1. If the data can be empty or is re-populated by the application: call
   `POST /api/v3/dbaas/namespaces/{namespace}/databases/recreate` with the classifier, type, and `physicalDatabaseId`. DBaaS
   creates a new empty database and archives the stale record. Restart the application afterward. Each call creates
   another new database; retry only the entries listed in `unsuccessfully`.
2. If the data is needed: restore the database from backup (`docs/howto/Backup and Restore V2.md`).
3. **DANGEROUS — removes the registry record; on PROD returns `403` `CORE-DBAAS-4003`.** Only on a non-production
   environment where the application creates its database on start: `DELETE /api/v3/dbaas/{namespace}/databases/{type}`
   with the classifier, then restart the application.

**Sources:**

- `docs/FAQ/DBaaS Troubleshooting.md` — `Database exists in DBaaS registry but is missing in the actual physical
database instance (lost)`
- `helm-templates/dbaas-aggregator/templates/PrometheusRule.yaml`
- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/monitoring/MetricCollector.java`

### Ghost databases reported in the physical database

**Symptoms:**

- Dashboard panel `Number of Not Registered Databases per Adapter Type` is above 0.
- `GET /api/v3/dbaas/debug/internal/ghost` lists database names for a physical database.

**Root cause:**

A database exists in the physical database but has no DBaaS registry record (manual creation, a failed cleanup, or a
registry record deleted manually). Ghost databases do not break DBaaS; they take space. The list also contains the
cluster's own service databases (for PostgreSQL: `postgres`, `template0`, `template1`).

**How to check:**

1. Exclude the cluster's service databases from the list. The rest are candidates.

**How to fix:**

1. **DANGEROUS — permanently deletes the ghost database and all data it contains.** The database owner must first
   confirm that no service uses it. Only then may the database-cluster DBA drop it; DBaaS has no API for this action and
   cannot restore the data.

**Sources:**

- `docs/FAQ/DBaaS Troubleshooting.md` — `Find ghost databases`
