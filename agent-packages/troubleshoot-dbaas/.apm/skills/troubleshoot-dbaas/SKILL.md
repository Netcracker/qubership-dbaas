---
name: troubleshoot-dbaas
description: Diagnose DBaaS and the Operator failure modes using exact error strings, alert mappings, and deployment parameter paths for dbaas-aggregator and dbaas-operator (Netcracker/qubership-dbaas). Load when the ticket mentions dbaas-aggregator, DBaaS Aggregator, dbaas-operator, a CORE-DBAAS error code, alert `Pod is Down` for a DBaaS component, lost or ghost databases, logical or physical database registration, DBaaS adapter registration, balancing rules, classifiers, Jobs `validation-hook-dbaas-aggregator` / `dbaas-aggregator-pre-hook`, Secrets `dbaas-storage-credentials` / `dbaas-aggregator-encryption-secret` / `dbaas-security-configuration-secret`, or a `dbaas.netcracker.com` custom resource reconciled by dbaas-operator.
---

# DBaaS assessment

DBaaS provisions logical databases for microservices on physical database clusters (PostgreSQL, MongoDB, Cassandra,
OpenSearch, ClickHouse, ArangoDB, Redis) through a DBaaS adapter that each cluster ships. DBaaS keeps the registry of
all logical databases and their connection properties.

Source of truth: the current `main` branch of `Netcracker/qubership-dbaas`. Paths in case `**Sources:**` blocks are
relative to that repository.

## Scope

In scope:

- dbaas-aggregator: installation hooks, registry storage, health, adapter registration, balancing rules, REST errors
  `CORE-DBAAS-<nnnn>`, registry consistency (lost and ghost databases).
- dbaas-operator: the DBaaS custom resources defined in `dbaas-operator/config/crd/bases/`, their status phases,
  reasons, and events.

Out of scope — route to another skill:

- Adapter health `PROBLEM`, adapter crashes, and engine errors of the database cluster itself → troubleshooting skill
  of that database type (`troubleshoot-postgres`, `troubleshoot-mongodb`, `troubleshoot-cassandra`,
  `troubleshoot-opensearch`, `troubleshoot-clickhouse`, `troubleshoot-arangodb`, `troubleshoot-redis`).
- PostgreSQL that hosts the DBaaS registry being down → `troubleshoot-postgres`. DBaaS only reports the symptom.

## Components and resources

dbaas-aggregator (Java/Quarkus, REST API `/api/v3/dbaas/*`), Helm chart `dbaas-aggregator`, flat UPPERCASE values:

- Deployment `dbaas-aggregator`, container `dbaas-aggregator`, port `8080`. Probes `/probes/live` and `/probes/ready`
  check only the in-memory H2 cache and password encryption; `/health` adds PostgreSQL (`dbaasPostgresConnect`) and
  adapters (`adaptersAccessIndicator`).
- Registry: a database in the PostgreSQL cluster from `POSTGRES_HOST`, plus an in-memory H2 copy refreshed every
  10 minutes. When PostgreSQL is unreachable, reads fall back to H2 and writes fail.
- Helm pre-install/pre-upgrade hook Jobs, in this order:
  1. `validation-hook-dbaas-aggregator` (weight `-200`, script `validation.sh`): TCP check of
     `POSTGRES_HOST:POSTGRES_PORT`; with `DBAAS_OWN_PG_DB_CREATED_MANUALLY: false`, creates and drops
     `validation_check_database` as the configured user; with `true`, checks that `POSTGRES_DBAAS_DATABASE_NAME`
     exists. Exit code `122` on failure.
  2. `dbaas-aggregator-pre-hook` (weight `-100`, script `prepare-database.sh`): creates Secret
     `dbaas-aggregator-encryption-secret` if missing, then — only if Secret `dbaas-storage-credentials` is missing —
     creates the registry user/database and that Secret. Exit code `121` on failure.
- Secrets:
  - `dbaas-storage-credentials` — keys `username`, `password`, `database`. Runtime source of registry credentials.
  - `dbaas-aggregator-encryption-secret` — keys `sym-key`, `default-key`. Encrypts all database passwords stored in
    the registry.
  - `dbaas-security-configuration-secret` — key `users.json` with API users, including `dbaas-operator`. Read by
    dbaas-aggregator only at startup.
  - `dbaas-aggregator-env-variables` — hook-only env for both Jobs.
- Monitoring (`MONITORING_ENABLED: true`): PrometheusRule `dbaas-registry-discrepancy`, GrafanaDashboard.

dbaas-operator (Go), Helm chart `dbaas-operator`:

- Reconciles a CR only when the CR's `spec.operatorNamespace` equals the operator's namespace.
- CR status model: `status.phase` (`Processing`, `Succeeded`, `BackingOff`, `InvalidConfiguration`,
  `WaitingForDependency`), conditions `Ready` and `Stalled`, `status.lastRequestId` for log correlation with the
  aggregator's `request_id=`.
  - `Stalled=True` → the operator stops retrying until the resource generation changes. This describes controller
    behavior; it does not prove that the initiating failure was a permanent specification error.
  - `Ready=False` and `Stalled=False` → transient; the operator retries by itself.

## Alert map

Load the named section from the stated canonical case file.

- Alert `Pod is Down` for `dbaas-aggregator`:
  - with `OOMKilled` or `OutOfMemoryError` →
    `references/aggregator.md` / `dbaas-aggregator is OOMKilled or slow on environments with many databases`
  - with `PasswordEncryption does not work`, `An error occurred while getting decrypted password`, or
    `cannot decrypt password` → `references/aggregator.md` / `dbaas-aggregator is not ready because password encryption fails`
  - with `Pending`, `FailedScheduling`, or `didn't match Pod's node affinity/selector` →
    `references/aggregator.md` / `dbaas-aggregator pods stay Pending after installation`
- Alert `Pod is Down` for `dbaas-operator` with `FailedMount` for `dbaas-security-configuration-secret` or
  `failed to load aggregator credentials from` → `references/operator.md` /
  `dbaas-operator cannot start because its Basic-auth Secret is missing or invalid`.
- Alert `Pod is Down` for the PostgreSQL cluster used by `POSTGRES_HOST`, together with `Postgres connection is lost`
  in dbaas-aggregator → `references/aggregator.md` / `dbaas-aggregator lost connection to its PostgreSQL registry`;
  use `troubleshoot-postgres` for the database-cluster cause.

Do not treat Prometheus rule names as platform alert names. If `Pod is Down` has none of the evidence above, do not
guess a section from the alert alone; use the exact error, pod state, event, or CR status from the ticket.

## Exact evidence routing

- For an exact log string, error code, CR phase, or condition reason, read `references/log-signatures.md` and load the
  canonical section it names.
- For evidence that crosses dbaas-operator, dbaas-aggregator, adapters, or registry consistency, read
  `references/common-workflows.md` before loading a case.
- For an Aggregator installation, runtime, registration, placement, logical-database, or registry case, load the
  matching section from `references/aggregator.md`.
- For a dbaas-operator startup, CR status, Secret, or finalizer case, load the matching section from
  `references/operator.md`.

For a `CORE-DBAAS-<nnnn>` code not mapped by `references/log-signatures.md`, read the code title in
`CORE-DBAAS error codes` at the end of this file. Use the response detail and the initiating Aggregator or adapter log
to classify the cause; the HTTP status class alone does not distinguish a bad client request from component state or
an infrastructure failure.

## Parameters and where they live

All dbaas-aggregator parameters are deployment parameters of the `dbaas-aggregator` application (Helm values of chart
`dbaas-aggregator`). Recommend a change as: «Set `<PARAM>: <value>` in the deployment parameters of the
`dbaas-aggregator` application and redeploy.» Never recommend editing the rendered Kubernetes object directly; the
next deployment reverts it.

Do not rely on copied default values in this skill. Read the defaults and profile overrides from the current source:

- dbaas-aggregator: `helm-templates/dbaas-aggregator/values.yaml`, `values.schema.json`, and `resource-profiles/`;
- dbaas-operator: `dbaas-operator/helm-templates/dbaas-operator/values.yaml` and `values.schema.json`;
- operator startup flags: `dbaas-operator/cmd/main.go` and the constant referenced by each flag.

Use the matching chart template to verify where a value is rendered before recommending a change.

Rendered into Deployment `dbaas-aggregator`:

- `MEM_ARGS` → container `args` (JVM flags, including `-Xmx`).
- `MEMORY_LIMIT` → both `resources.requests.memory` and `resources.limits.memory`.
- `CPU_REQUEST` / `CPU_LIMIT` → `resources.requests.cpu` / `resources.limits.cpu`.
- `REPLICAS` → `spec.replicas`.
- `LIVENESS_PROBE_INITIAL_DELAY_SECONDS` → `livenessProbe.initialDelaySeconds`.
- `NODE_SELECTOR_DBAAS_KEY` / `REGION_DBAAS` → `nodeSelector`.
- `DBAAS_RECREATE_DEPLOYMENT_STRATEGY` → `strategy.type: Recreate` when `true`.
- Container env: `POSTGRES_HOST`, `POSTGRES_PORT`, `PRODUCTION_MODE`, `DBAAS_DEFAULT_PHYSICAL_DATABASES_DISABLED`,
  `DBAAS_PREMATURE_REGISTRATION_ADAPTER_ADDRESSES`, `DBAAS_SECURITY_NAMESPACE_ISOLATION_ENABLED`,
  `M2M_AUTH_MODE`, `KUBERNETES_M2M_AUDIENCE`, `DBAAS_LOG_LEVEL`, `DBAAS_BACKUP_RESTORE_CHECK_LOCK_TIMEOUT`,
  `DBAAS_BACKUP_RESTORE_CHECK_INTERVAL`, `DBAAS_BACKUP_RESTORE_CHECK_ATTEMPTS`,
  `DBAAS_BACKUP_RESTORE_RETRY_DELAY_SECONDS`, `DBAAS_BACKUP_RESTORE_RETRY_ATTEMPTS`.

Rendered into Secret `dbaas-aggregator-env-variables` and used only by the hook Jobs: `POSTGRES_DBA_USER`,
`POSTGRES_DBA_PASSWORD`, `POSTGRES_DBAAS_USER`, `POSTGRES_DBAAS_PASSWORD`, `POSTGRES_DBAAS_DATABASE_NAME`,
`DBAAS_OWN_PG_DB_CREATED_MANUALLY`, `USE_POSTGRES_PORT_FORWARD`. The validation Job reads these values on every install
or upgrade. The prepare Job exits without using the database-preparation values when Secret
`dbaas-storage-credentials` already exists.

Rendered into Secret `dbaas-security-configuration-secret` (`users.json`): `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` /
`_PASSWORD`, `DBAAS_DB_EDITOR_CREDENTIALS_USERNAME` / `_PASSWORD`, `BACKUP_DAEMON_DBAAS_ACCESS_USERNAME` / `_PASSWORD`,
`DBAAS_TENANT_USERNAME` / `_PASSWORD`, `DISCR_TOOL_USER_USERNAME` / `_PASSWORD`. The `dbaas-operator` password is
generated by the chart and kept across upgrades.

`MONITORING_ENABLED` → creates PrometheusRule `dbaas-registry-discrepancy` and the GrafanaDashboard.

dbaas-operator parameters are deployment parameters of the `dbaas-operator` application (chart `dbaas-operator`):

- `DBAAS_OPERATOR_ENABLED` → when `false`, the chart renders only a stub ConfigMap. Changing an existing Helm release
  from `true` to `false` removes the previously managed CRDs and consequently all instances of those CRDs.
- `NAMESPACE` → names of `ClusterRole` / `ClusterRoleBinding` and the binding subject; must equal the release namespace.
- `M2M_AUTH_MODE` → selects Basic authentication in `legacy` mode and Bearer-token authentication in `hybrid` or
  `k8s` mode. The standard deployment supplies the same setting to dbaas-operator and dbaas-aggregator; investigate a
  mismatch only when the supplied deployment evidence shows a per-service override or partial deployment.
- `REPLICAS`, `LEADER_ELECT`, `MEMORY_REQUEST`, `MEMORY_LIMIT`, `CPU_REQUEST`, `CPU_LIMIT` → Deployment
  `dbaas-operator`.
- `DBAAS_AGGREGATOR_URL` → container env.
- `DBAAS_EXTERNAL_DATABASE_RESYNC_INTERVAL`, `DBAAS_ROTATION_POLL_INTERVAL`, `K8S_EVENTS_ENABLED`,
  `restrictedEnvironment`, `MONITORING_ENABLED`.
- Backoff flags `--backoff-base-delay` and `--backoff-max-delay` have no Helm value; they are container `args` of
  Deployment `dbaas-operator`.
- `spec.operatorNamespace` is not a Helm value. For CR kinds whose current schema defines it as required and immutable,
  it must equal the namespace of Deployment `dbaas-operator`, which the operator receives as `CLOUD_NAMESPACE`.
  Verify the applicable kinds in `dbaas-operator/config/crd/bases/`.

## Forbidden actions

- Deleting or regenerating Secret `dbaas-aggregator-encryption-secret`. The pre-hook creates new random keys, and every
  password already stored in the registry becomes undecryptable. No condition makes this safe.
- Deleting Secret `dbaas-storage-credentials` to make the hook "re-run". It holds the only copy of the generated
  registry password; with `POSTGRES_DBA_USER` the next deploy fails with `already exists, so you should pass dbaas
specific user through`. To change registry credentials, update keys of this Secret — see
  `Changed registry credentials in deployment parameters have no effect`.
- Setting `PRODUCTION_MODE: false` on a production environment to unblock a delete. For lost databases use
  `POST /api/v3/dbaas/namespaces/{namespace}/databases/recreate`.
- `DELETE /api/v3/dbaas/{type}/physical_databases/{phydbid}` while logical databases still point to that physical
  database. It is allowed only when `GET /api/v3/dbaas/debug/internal/databases?filter=physicalDbId==<phydbid>` returns
  an empty list.
- Setting `DBAAS_SECURITY_NAMESPACE_ISOLATION_ENABLED: false` to fix `CORE-DBAAS-4055`.
- Setting `DBAAS_OPERATOR_ENABLED: false` to pause dbaas-operator. Use `REPLICAS: 0` in the `dbaas-operator`
  deployment parameters.
- Editing immutable CR fields in place:
  - every kind: `spec.operatorNamespace`
  - `InternalDatabase`: `spec.classifier`, `spec.type`
  - `ExternalDatabase`: `spec.classifier`, `spec.type`, `spec.dbName`
  - `DatabaseAccessPolicy`: `spec.microserviceName`
  - `DatabaseSecretClaim`: `spec.classifier`, `spec.type`, `spec.secretName`; `spec.userRole` cannot change directly
    from one set value to another
    Delete and recreate the CR to change an immutable identity field, subject to the balancing-rule finalizer rule below.
- Force-deleting an unassigned `MicroserviceBalancingRule`, `NamespaceBalancingRule`, or `PermanentBalancingRule` that
  still has a DBaaS finalizer. The finalizer proves an operator previously claimed the CR; removing it can leave a rule
  applied in dbaas-aggregator. Restore an operator whose `CLOUD_NAMESPACE` matches `spec.operatorNamespace` and let it
  perform cleanup. An unassigned balancing-rule CR with no finalizer has not been applied by this operator and may be
  recreated.
- Deleting an `InternalDatabase` while `status.trackingId` is set, to restart a slow provisioning.
- Editing `users.json` in Secret `dbaas-security-configuration-secret` by hand. Redeploy `dbaas-aggregator` so the
  chart regenerates it.
- Direct SQL changes in the DBaaS registry database. Use the DBaaS REST API.
- Dropping a ghost database without its owner's confirmation; DBaaS cannot restore it.

## Loading the reference

References are separated by purpose:

- `references/common-workflows.md` — cross-component request correlation, credential changes, provisioning, and
  registry consistency.
- `references/log-signatures.md` — exact error, log, CR phase, and condition-reason routing.
- `references/aggregator.md` — canonical dbaas-aggregator cases.
- `references/operator.md` — canonical dbaas-operator cases.

Every canonical case is a unique `###` section in `aggregator.md` or `operator.md` and starts with `**Symptoms:**`.
Do not read both case files in full.

Resolve `SKILL_DIR` to the directory that contains this `SKILL.md`; do not assume the current working directory is the
skill directory.

1. Use the alert map or `log-signatures.md` to select a file and exact case heading. Use `common-workflows.md` only
   when evidence crosses components.

2. List canonical case titles with line numbers when the route is unclear:

   ```bash
   grep -n "^### " "$SKILL_DIR/references/aggregator.md" "$SKILL_DIR/references/operator.md"
   ```

3. If the ticket string is not mapped, search all references with a fixed-string match:

   ```bash
   grep -n -F -- "<exact string from the ticket>" "$SKILL_DIR"/references/*.md
   ```

   A match in `log-signatures.md` or `common-workflows.md` names the canonical case file and heading. A direct match in
   `aggregator.md` or `operator.md` belongs to the nearest `###` line above it.

4. Read only the selected canonical section: from its `###` line up to the line before the next `##` or `###` line.
   Read with `offset` and `limit`.

## CORE-DBAAS error codes

Titles from `ErrorCodes.java`, for codes without their own section. The HTTP status depends on the endpoint.

- `CORE-DBAAS-2000` Unexpected exception (`500`; read the stack trace in the aggregator log)
- `CORE-DBAAS-4001` Invalid database backup request
- `CORE-DBAAS-4002` Conflict database request
- `CORE-DBAAS-4005` Invalid DB Create request
- `CORE-DBAAS-4007` Invalid Password change request
- `CORE-DBAAS-4008` Balancing rule was not created/updated as there is conflicting rule with same order and database
  type
- `CORE-DBAAS-4009` Password change operation failed (details in `/meta/response`)
- `CORE-DBAAS-4011` Invalid physical identifier
- `CORE-DBAAS-4012` Backup not found
- `CORE-DBAAS-4013` Deleting target backup is not allowed
- `CORE-DBAAS-4014` Failed to delete backup
- `CORE-DBAAS-4015` Restoration not found
- `CORE-DBAAS-4016` The request body must contain at least one microserviceName
- `CORE-DBAAS-4018` Backup execution failed
- `CORE-DBAAS-4019` Failed to re-create databases
- `CORE-DBAAS-4020` Invalid update connection properties request
- `CORE-DBAAS-4021` Connection properties for role does not exist in database
- `CORE-DBAAS-4022` The request body must contain origin service
- `CORE-DBAAS-4024` Connection properties doesn't contain field 'role'
- `CORE-DBAAS-4025` Connection properties must not be null or empty
- `CORE-DBAAS-4026` Create request must not contain username, password, dbName or InitScriptIdentifiers when used V2
  adapter version
- `CORE-DBAAS-4027` Register request does not contain required fields
- `CORE-DBAAS-4028` DbHost name has wrong format
- `CORE-DBAAS-4029` Logical database can't be found
- `CORE-DBAAS-4030` Backup request have not been passed validate procedure
- `CORE-DBAAS-4031` Requested user is not found
- `CORE-DBAAS-4032` Error during deletion from adapter
- `CORE-DBAAS-4034` Duplicate balancing rule
- `CORE-DBAAS-4035` Unknown declarative configuration kind (supported: `DatabaseDeclaration`, `DbPolicy`)
- `CORE-DBAAS-4036` Declarative configuration request validation failed (`400`)
- `CORE-DBAAS-4037` Init domain request validation error
- `CORE-DBAAS-4038` Request body contains invalid settings state
- `CORE-DBAAS-4039` Blue-green domain is not exist
- `CORE-DBAAS-4040` Blue-green namespace is not empty
- `CORE-DBAAS-4041` Can't find database in active namespace
- `CORE-DBAAS-4042` Can't interact with not versioned db
- `CORE-DBAAS-4043` Request does not contain required fields
- `CORE-DBAAS-4044` Invalid link databases request
- `CORE-DBAAS-4045` Adapter address name has wrong format
- `CORE-DBAAS-4046` Resource already exists
- `CORE-DBAAS-4047` Operation not allowed
- `CORE-DBAAS-4048` Illegal state of entity
- `CORE-DBAAS-4049` Unprocessable resource
- `CORE-DBAAS-4050` Operation not implemented
- `CORE-DBAAS-4051` Digest calculation failed
- `CORE-DBAAS-4052` Digest mismatch
- `CORE-DBAAS-4054` Invalid tenantId in classifier
- `CORE-DBAAS-4056` Adapter request failed (`500`; inspect the adapter response or error for the initiating failure)
- `CORE-DBAAS-7002` trackingId not found
- `CORE-DBAAS-7003` Invalid composite structure request
