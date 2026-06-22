# Troubleshooting APIs

DBaaS offers several built-in tools for analyzing problems.

## Get overall status

> Since DBaaS 5.3.0

You can see the general status of DBaaS by using the following API:

```
GET {dbaas_host}/api/v3/dbaas/debug/internal/info
```

The response contains information about number of logical databases and health of adapters/DBaaS. This will allow you to
evaluate the operability and load of the adapters and the DBaaS itself.

<details><summary>Example</summary>

Full documentation: [Get overall status](../rest-api.md#get-overall-status)

```bash
# Request
curl --location --request GET http://localhost:8080/api/v3/dbaas/debug/internal/info \
    -H 'Authorization: Basic abcdef=' 
```

# Response

```json
{
  "overallHealthStatus": "UP",
  "overallLogicalDbNumber": 59,
  "physicalDatabaseInfoList": [
    {
      "physicalDatabaseId": "postgresql-1",
      "healthStatus": "UP",
      "logicalDbNumber": "68"
    },
    {
      "physicalDatabaseId": "cassandra-1",
      "healthStatus": "UP",
      "logicalDbNumber": "11"
    },
    {
      "physicalDatabaseId": "clickhouse:main",
      "healthStatus": "UP",
      "logicalDbNumber": "5"
    }
  ]
}
```

</details>

## Find lost databases

> Since DBaaS 5.3.0

During the work of DBaaS, a situation may occur when the logical database was removed from the physical adapter for some
reason, but remained registered in DBaaS. Such databases can disrupt the work of DBaaS, causing errors in some
situations, and they should be deleted from the registry in a timely manner. The following API allows you to detect such
databases:

```
GET {dbaas_host}/api/v3/dbaas/debug/internal/lost
```

The response contains a list of lost databases for each physical adapter.

<details><summary>Example</summary>

Full documentation: [Get lost databases](../rest-api.md#get-lost-databases)

```bash
# Request
curl -X GET \
  http://localhost:8080/api/v3/dbaas/debug/internal/lost \
  -H 'Authorization: Basic abcdef='
```

# Response

```json
[
  {
    "physicalDatabaseId": "cassandra-dev",
    "errorMessage": null,
    "databases": [
      {
        "id": "2gfdsbf5-7ad4-42x8-ag76-a4612d048f0c",
        "classifier": {
          "microserviceName": "service-1",
          "namespace": "namespace1",
          "scope": "service"
        },
        "namespace": "namespace1",
        "type": "cassandra",
        "name": "dbaas_e905eef3c79841e09c4e916c2cc2bb14",
        "externallyManageable": false,
        "timeDbCreation": "2023-01-10T08:17:33.725+00:00",
        "settings": null,
        "backupDisabled": false,
        "physicalDatabaseId": "cassandra-dev",
        "connectionProperties": [
          ...
        ]
      }
    ]
  }
]
```

</details>

## Find ghost databases

> Since DBaaS 5.3.0

In case of problems with the physical adapter or in case of manual intervention, the logical database can be deleted
from the DBaaS registry, but still exist in the physical adapter. Such bases are called ghost databases, they do not
disrupt the work of DBaaS, but take up space in the physical adapter. The following API allows you to detect such
databases:

```
GET {dbaas_host}/api/v3/dbaas/debug/internal/ghost
```

The response contains a list of ghost databases in each adapter. But this list may contain the service databases
necessary for the operation of physical adapters (for example, the *postgres* database in the case of the PostgreSQL
DBMS), so this list needs to be analyzed manually.

<details><summary>Example</summary>

Full
documentation: [Get ghost databases](../rest-api.md#get-ghost-databases)

```bash
# Request
curl -X GET \
  http://localhost:8080/api/v3/dbaas/debug/internal/ghost \
  -H 'Authorization: Basic abcdef=' 
```

# Response

```json
[
  {
    "physicalDatabaseId": "redis-dev",
    "dbNames": [
    ],
    "errorMessage": null
  },
  {
    "physicalDatabaseId": "postgresql-dev:postgres",
    "dbNames": [
      "dbaas_autotests_b3a63ae3795",
      "template0",
      "dbaas_autotests_990c9ea8317",
      "template1",
      "dbaas_dev",
      "dbaas-test-service_candidate-test-namespace_120530154311024",
      "dbaas-declarative-service_dbaas-autotests_143622345041124",
      "dbaas-test-service_active-test-namespace_081927841021124",
      "dbaas-test-service_dbaas-autotests_143628563041124",
      "postgres"
    ],
    "errorMessage": null
  }
]
```

</details>

## Find logical databases

> Since DBaaS 5.3.0

The following API can be used to analyze problems with registered logical databases:

```
GET {dbaas_host}/api/v3/dbaas/debug/internal/databases
```

It allows you to get a list of logical databases based on a number of filtering criteria, such as:

- namespace
- microservice
- tenantId
- logicalDbName
- bgVersion
- type
- roles
- name
- physicalDbId
- physicalDbAdapterUrl

The filtering condition is written in RSQL format, the details of which you can find
here: [https://github.com/jirutka/rsql-parser](https://github.com/jirutka/rsql-parser)

The response contains detailed information about the logical databases found.

<details><summary>Example</summary>

Full
documentation: [Find Debug Logical Databases](../rest-api.md#find-debug-logical-databases)

```bash
# Request
curl --location --request GET http://localhost:8080/api/v3/dbaas/debug/internal/databases?filter=namespace==dbaas-autotests; \
	microservice==dbaas-declarative-service; \
	logicalDbName==configs; \
	type!=clickhouse; \
	roles=in=("ro","rw"); \
	physicalDbId==postgresql-dev:postgres; \
	physicalDbAdapterUrl==http://dbaas-postgres-adapter.postgresql-dev:8080 \
-H 'Authorization: Basic abcdef' \
-H "Accept: application/json"
```

# Response

```json
[
  {
    "namespace": "dbaas-autotests",
    "microservice": "dbaas-declarative-service",
    "tenantId": "ce2ab06d-1e61-4036-99b1-e39fs3da741b",
    "logicalDbName": "configs",
    "bgVersion": "2",
    "type": "postgresql",
    "roles": [
      "admin",
      "streaming",
      "rw",
      "ro"
    ],
    "name": "dbaas-declarative-service_dbaas-autotests_175104799071124",
    "physicalDbId": "postgresql-dev:postgres",
    "physicalDbAdapterUrl": "http://dbaas-postgres-adapter.postgresql-dev:8080",
    "declaration": {
      "id": "4cb9e41a-e4b2-4529-98fc-392c72873c75",
      "settings": null,
      "lazy": false,
      "instantiationApproach": "new",
      "versioningApproach": "new",
      "versioningType": "static",
      "classifier": {
        "custom_keys": {
          "logicalDBName": "configs"
        },
        "microserviceName": "dbaas-declarative-service",
        "namespace": "dbaas-autotests",
        "scope": "service"
      },
      "type": "postgresql",
      "namePrefix": null,
      "namespace": "dbaas-autotests"
    }
  }
]
```

</details>

## Dump DBaaS registry

> Since DBaaS 5.2.0

If all other tools have not helped you find the source of the problem, you can get a raw dump of the internal state
of DBaaS using the following API:

```
GET {dbaas_host}/api/v3/dbaas/debug/internal/dump
```

By default, it will return a zip-archive containing a full copy of some internal DBaaS tables. This information can be
used for an in-depth analysis of the state of DBaaS installation, and can be requested by the support team if a ticket
is created to analyze the problem.

<details><summary>Example</summary>

Full
documentation: [Get Dump of Dbaas Database Information](../rest-api.md#get-dump-of-dbaas-database-information)

```bash
# Request
curl --location --request GET http://localhost:8080/api/v3/dbaas/debug/internal/dump \
  -H 'Authorization: Basic abcdef=' \
  -H "Accept: application/json"
```

# Response

```json
{
  "rules": {
    "defaultRules": [
      {
        ...
      }
    ],
    "namespaceRules": [
      {
        ...
      }
    ],
    "microserviceRules": [
      {
        ...
      }
    ],
    "permanentRules": [
      {
        ...
      }
    ]
  },
  "logicalDatabases": [
    {
      ...
    }
  ],
  "declarativeConfigurations": [
    {
      ...
    }
  ],
  "blueGreenDomains": [
    {
      ...
    }
  ]
}
```

</details>

## Database exists in DBaaS registry but is missing in the actual database instance

### Symptom

An application fails to start because it cannot connect to its database, even though DBaaS returns connection properties without error. The database is present in the DBaaS registry but does not actually exist in the physical database instance.

### Cause

This is known as a **lost database**. It happens when the physical database was deleted (manually, by a failed cleanup, or by a storage failure) while the logical database record remained in the DBaaS registry. DBaaS trusts its own registry, so it returns the stale connection properties, and the application fails when it tries to use them.

### How to confirm

**Step 1.** Check the lost databases report:

```bash
curl -X GET \
  http://{dbaas_host}/api/v3/dbaas/debug/internal/lost \
  -H 'Authorization: Basic <credentials>'
```

If the affected database appears in the response, the registry is inconsistent and needs to be fixed.

**Step 2.** Optionally, retrieve the exact registry record to get the classifier and physical database ID:

```bash
curl -X GET \
  "http://{dbaas_host}/api/v3/dbaas/debug/internal/databases?filter=namespace=={namespace};microservice=={microserviceName}" \
  -H 'Authorization: Basic <credentials>' \
  -H 'Accept: application/json'
```

### Fix options

Choose the option that fits the situation:

#### Option A — Recreate the database (recommended when data can be empty)

Use this when the application can start with a fresh, empty database (e.g., the database will be re-populated on first start or via migration).

```bash
curl -X POST \
  http://{dbaas_host}/api/v3/dbaas/{namespace}/databases/recreate \
  -H 'Authorization: Basic <credentials>' \
  -H 'Content-Type: application/json' \
  -d '[
    {
      "type": "{db_type}",
      "classifier": {
        "microserviceName": "{microserviceName}",
        "namespace": "{namespace}",
        "scope": "service"
      },
      "physicalDatabaseId": "{physicalDatabaseId}"
    }
  ]'
```

DBaaS creates a new empty physical database, updates the registry record, and returns new connection properties. The previous stale record is archived. After this call, restart the affected application — it will receive the new connection properties on its next DBaaS call.

> **Note:** This operation is prohibited while a blue-green deployment is in progress.

> **Note:** Each call to this API creates a new database, even if the database was already recreated before. If the response contains entries in `unsuccessfully`, fix only those entries in the next request.

#### Option B — Delete from registry and let the application re-create it

Use this when the application always creates its database on startup (i.e., the database creation is idempotent from the application's perspective).

Delete the stale registry entry:

```bash
curl -X DELETE \
  "http://{dbaas_host}/api/v3/dbaas/{namespace}/databases/{db_type}?force=true" \
  -H 'Authorization: Basic <credentials>' \
  -H 'Content-Type: application/json' \
  -d '{
    "classifier": {
      "microserviceName": "{microserviceName}",
      "namespace": "{namespace}",
      "scope": "service"
    }
  }'
```

The `force=true` parameter makes DBaaS remove the registry entry even if the adapter returns an error (which it will, since the physical database is already gone). After deletion, restarting the application causes it to request a new database from DBaaS, which will provision one normally.

> **Warning:** This endpoint is disabled in PROD mode (`406 Not Acceptable`). In that case, use Option A.

#### Option C — Restore from backup

Use this when the database contained important data that must be recovered. Refer to [Backup and Restore](../howto/Backup%20and%20Restore%20V2.md) for the restore procedure. After a successful restore, the physical database will exist again and the registry inconsistency will be resolved automatically.
