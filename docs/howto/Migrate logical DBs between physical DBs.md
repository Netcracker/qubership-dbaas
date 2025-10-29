`SINCE 3.11.0`

> Warning: **This procedure implies a maintenance window.**

# Problem

You have existing logical databases in some physical database. These databases are used by microservices and contain
some valuable data. Your goal and desire is to have the databases with these data located in a different cluster
(physical database). In other words, perform data migration from one physical database to another.

# Requirements

1. You must
   know [DB_EDITOR](../installation/parameters.md#dbaas_db_editor_credentials_username--dbaas_db_editor_credentials_password)
   and [CLUSTER_DBA](../installation/parameters.md#dbaas_cluster_dba_credentials_username--dbaas_cluster_dba_credentials_password)
   credentials;
2. You must know physical database identifier where you want to migrate data. This identifier can be found in
   environments of dbaas-adapter;
3. Have access to physical database to migrate data manually.
4. DBaaS-aggregator 3.11 or higher version.

# Solution

Solution is based on recreating new databases in a new physical database. A new database will have a different database
name, username, password but will have the same settings and classifier as an original database.

## 1. Collect list of databases

The first step, you should collect classifiers and types of logical databases which you want to recreate in another
physDb. To do this, you can use a [List of all databases](../rest-api.md#list-of-all-databases) API to get list of
databases in a specific namespace. You have to use CLUSTER_DBA credentials for calling this API.

After databases have been received, you should copy and save classifiers and connection properties of databases that
should be recreated in another physDB. You will need these data in the following steps.

## 2. Scale down pods

You should scale down all pods in your project for data consistency. If you do not do it your business data may split up
between databases.

## 3. Create new databases

The next step is create an empty database in a new physical database. Also, new connections must be updated in DBaaS
Aggregator. For these purposes you can use
a [Recreate database with existing classifier](../rest-api.md#recreate-database-with-existing-classifier) API. This API
requires DB_EDITOR credentials and list of classifiers which were collected in the first step. Request body looks like
this:

```json
[
  {
    "type": "<>",
    "classifier": {},
    "physicalDatabaseId": "<>"
  },
  {
    "type": "<>",
    "classifier": {},
    "physicalDatabaseId": "<>"
  }
]
```

For example:

```json
[
  {
    "type": "postgresql",
    "classifier": {
      "scope": "service",
      "microserviceName": "your-service",
      "namespace": "phys-db-migration"
    },
    "physicalDatabaseId": "postgresql-1"
  }
]
```

If everything is fine you will get a new connection properties in a response body.

> Warning:
> Pay attention, each request will produce a new database even if the database was previously recreated. So, if your
> response contains unsuccessful databases you must leave only these databases in a new request. Otherwise, successful
> databases will be recreated again.

For the next step you should know old and new connection properties for data migration.

## 4. Make data migration

Once you have created a new databases you need to perform migration procedure. Migration procedure for different types
of databases are different. So, by question how to do it, you should read the official documentation.

## 5. Scale up pods

Pods can be scaled up after the migration has been performed. Old databases of microservice were not deleted and marked
as archived.
