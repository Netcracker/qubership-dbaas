`SINCE 3.20`

# Description

There are cases when logical databases were created and used not through DBaaS. But at some point, someone wants to
switch to use DBaaS and register their logical databases as internals for full control over them through DBaaS. In this
case, after registration, these databases will be treated as if they were originally created through DBaaS. In order to
do it, there are two APIs, the first one implies that the logical database's credentials are known and there is no need
to recreate the users. The second is intended in the case when the logical database's credentials are not known, and
it is required to create and use a new users during the database registration.

***With user creation*** API also supports:

- Physical database id autodiscovery based on dbHost request field. Physical database id may be discovered by it's host
  value. Host must be in format: <service-name>.<namespace>, e.g.: pg-patroni.postgresql-dev
- Registering external logical databases as internal. If logical database was previously registered as external it may
  be moved to internal databases with this API. New credentials will be generated.

# API

**The database must physically exist in the specified physical db. If the database does not exist, then the migration
will not go through and the API will give an error.**

## Without user creation

| HTTP method | PUT                                                                                                                                      |
|-------------|------------------------------------------------------------------------------------------------------------------------------------------|
| URL         | {dbaas_aggregator_address}/api/v3/dbaas/migration/databases                                                                              |
| Security    | yes, [basic authorization](../installation/parameters.md#dbaas_cluster_dba_credentials_username--dbaas_cluster_dba_credentials_password) |

Request body:

```json
[{
  "classifier": {                  // composite database key. Must be in v3 format, required
  "scope": "tenant" or "service",  //mandatory
  "tenantId": "<id>",              //mandatory if you use tenant scope
  "microserviceName": "<name>",    //mandatory
  "namespace" : "<namespace>"      //mandatory
  "custom_keys": <>                //optional
},
  "connectionProperties": [{  // required. Each database type has own connection properties structure
      "role": "",             // required
      ...
    },
    ...
  ],
  "resources": [{            // required 
    "kind": "database",
    "name": "<database name>"
  }, {
    "kind": "user",          // if passed several connections then all users must be passed
    "name": ""
  }, {
    "kind": "user",
    "name": ""
  }, {
    "kind": "user",
    "name": "dbaas_86ec1511ab194e24a249cbb67e1de944"
  }
  ],
  "namespace": "",          // required
  "type": "",               // required e.g.: postgresql, mongodb
  "name": "",               // required e.g: database name
  "physicalDatabaseId": "", // required, physical database id where database is placed
  "backupDisabled": false   // optional, default is false
}]
```

Request body example:

```json
[
  {
    "classifier": {
      "namespace": "ns-1",
      "microserviceName": "ms-1-test",
      "scope": "service"
    },
    "connectionProperties": [
      {
        "host": "pg-patroni.postgresql-dev",
        "name": "dbaas_dd4a01b4b92d48039c75a6546be337f9",
        "password": "12345",
        "port": 5432,
        "role": "admin",
        "url": "jdbc:postgresql://pg-patroni.postgresql-dev:5432/dbaas_dd4a01b4b92d48039c75a6546be337f9",
        "username": "dbaas_ef2952820c184ef69b9703105e93fcb9"
      },
      {
        "host": "pg-patroni.postgresql-dev",
        "name": "dbaas_dd4a01b4b92d48039c75a6546be337f9",
        "password": "23456",
        "port": 5432,
        "role": "streaming",
        "url": "jdbc:postgresql://pg-patroni.postgresql-dev:5432/dbaas_dd4a01b4b92d48039c75a6546be337f9",
        "username": "dbaas_616e15660cba4c3f88ffb413fd7459f4"
      },
      {
        "host": "pg-patroni.postgresql-dev",
        "name": "dbaas_dd4a01b4b92d48039c75a6546be337f9",
        "password": "54321",
        "port": 5432,
        "role": "rw",
        "url": "jdbc:postgresql://pg-patroni.postgresql-dev:5432/dbaas_dd4a01b4b92d48039c75a6546be337f9",
        "username": "dbaas_86ec1511ab194e24a249cbb67e1de944"
      },
      {
        "host": "pg-patroni.postgresql-dev",
        "name": "dbaas_dd4a01b4b92d48039c75a6546be337f9",
        "password": "87654",
        "port": 5432,
        "role": "ro",
        "url": "jdbc:postgresql://pg-patroni.postgresql-dev:5432/dbaas_dd4a01b4b92d48039c75a6546be337f9",
        "username": "dbaas_4481dc18c3c94e56b601723492130448"
      }
    ],
    "resources": [
      {
        "kind": "database",
        "name": "dbaas_dd4a01b4b92d48039c75a6546be337f9"
      },
      {
        "kind": "user",
        "name": "dbaas_ef2952820c184ef69b9703105e93fcb9"
      },
      {
        "kind": "user",
        "name": "dbaas_616e15660cba4c3f88ffb413fd7459f4"
      },
      {
        "kind": "user",
        "name": "dbaas_86ec1511ab194e24a249cbb67e1de944"
      },
      {
        "kind": "user",
        "name": "dbaas_4481dc18c3c94e56b601723492130448"
      }
    ],
    "namespace": "ns-1",
    "type": "postgresql",
    "name": "dbaas_dd4a01b4b92d48039c75a6546be337f9",
    "physicalDatabaseId": "postgresql-dev",
    "backupDisabled": false
  }
]
```

Response body:

```json
{
  "<database type>": { // database type
    "migrated": [      // list db name which successfully migrated
      "", ""
    ],
    "migratedDbInfo": [{  // db info of successfully migrated databases
      "id": "",           // internal db id
      "classifier": {},
      "namespace": "",
      "type": "",
      "name": "",         // database name
      "timeDbCreation": "",
      "backupDisabled": false | true,
      "physicalDatabaseId": "",
      "connectionProperties": [{}],
    }],
    "conflicted": [],        // list databases which can pass validation
    "failed": [],			 // list database which failed during migration
    "failureReasons": []     // migration failure
  }
}
```

Postgresql request example:

```json
{
  "postgresql": {
    "migrated": [
      "dbaas_dd4a01b4b92d48039c75a6546be337f9"
    ],
    "migratedDbInfo": [
      {
        "id": "d1a4c2a6-bf19-4638-afdb-e9be337992c8",
        "classifier": {
          "microserviceName": "ms-1-test",
          "namespace": "ns-1",
          "scope": "service"
        },
        "namespace": "ns-1",
        "type": "postgresql",
        "name": "dbaas_dd4a01b4b92d48039c75a6546be337f9",
        "externallyManageable": false,
        "timeDbCreation": "2023-02-17T09:00:40.363+00:00",
        "settings": null,
        "backupDisabled": false,
        "physicalDatabaseId": "postgresql-dev",
        "connectionProperties": [
          {
            "password": "44444",
            "role": "admin",
            "port": 5432,
            "host": "pg-patroni.postgresql-dev",
            "name": "dbaas_dd4a01b4b92d48039c75a6546be337f9",
            "url": "jdbc:postgresql://pg-patroni.postgresql-dev:5432/dbaas_dd4a01b4b92d48039c75a6546be337f9",
            "username": "dbaas_ef2952820c184ef69b9703105e93fcb9"
          },
          {
            "password": "22222",
            "role": "streaming",
            "port": 5432,
            "host": "pg-patroni.postgresql-dev",
            "name": "dbaas_dd4a01b4b92d48039c75a6546be337f9",
            "url": "jdbc:postgresql://pg-patroni.postgresql-dev:5432/dbaas_dd4a01b4b92d48039c75a6546be337f9",
            "username": "dbaas_616e15660cba4c3f88ffb413fd7459f4"
          },
          {
            "password": "77777",
            "role": "rw",
            "port": 5432,
            "host": "pg-patroni.postgresql-dev",
            "name": "dbaas_dd4a01b4b92d48039c75a6546be337f9",
            "url": "jdbc:postgresql://pg-patroni.postgresql-dev:5432/dbaas_dd4a01b4b92d48039c75a6546be337f9",
            "username": "dbaas_86ec1511ab194e24a249cbb67e1de944"
          },
          {
            "password": "88888",
            "role": "ro",
            "port": 5432,
            "host": "pg-patroni.postgresql-dev",
            "name": "dbaas_dd4a01b4b92d48039c75a6546be337f9",
            "url": "jdbc:postgresql://pg-patroni.postgresql-dev:5432/dbaas_dd4a01b4b92d48039c75a6546be337f9",
            "username": "dbaas_4481dc18c3c94e56b601723492130448"
          }
        ]
      }
    ],
    "conflicted": [],
    "failed": [],
    "failureReasons": []
  }
}
```

## With user creation

| HTTP method | PUT                                                                                                                                      |
|-------------|------------------------------------------------------------------------------------------------------------------------------------------|
| URL         | {dbaas_aggregator_address}/api/v3/dbaas/migration/databases/with-user-creation                                                           |
| Security    | yes, [basic authorization](../installation/parameters.md#dbaas_cluster_dba_credentials_username--dbaas_cluster_dba_credentials_password) |

Request body:

```json
[{
  "classifier": {                  // composite database key. Must be in v3 format, required
      "scope": "tenant" or "service",  //mandatory
      "tenantId": "<id>",              //mandatory if you use tenant scope
      "microserviceName": "<name>",    //mandatory
      "namespace" : "<namespace>",     //mandatory
      "custom_keys": <>                //optional
  },
  "namespace": "",            // required
  "type": "",                 // required e.g.: postgresql, mongodb
  "name": "",                 // required e.g: database name
  "physicalDatabaseId": "",   // optional, physical database id where database is placed. One of parameters physicalDatabaseId or dbHost must present!
  "dbHost": "", 			  // optional, physical database host where database is placed. One of parameters physicalDatabaseId or dbHost must present!
  "backupDisabled": false     // optional, default is false
  }]
```

Request body example:

```json
[
  {
    "classifier": {
      "microserviceName": "ms1",
      "namespace": "test-app",
      "scope": "service"
    },
    "type": "postgresql",
    "name": "dbaas_bf91b6c59a1a434293c1a81b2811486a",
    "physicalDatabaseId": "postgresql-dev"
  },
  {
    "classifier": {
      "microserviceName": "ms1",
      "namespace": "test-app",
      "scope": "service"
    },
    "type": "mongodb",
    "name": "2e96c896-d78a-41f2-b0d5-8641f54e1b35",
    "physicalDatabaseId": "mongodb-dev"
  },
  {
    "classifier": {
      "microserviceName": "ms2",
      "namespace": "test-app",
      "scope": "service"
    },
    "type": "mongodb",
    "name": "2e96c896-d78a-41f2-b0d5-8641f54e1b37",
    "dbHost": "mongodb.mongodb-dev"
  }
]
```

Response body:

```json
{
    "<database type>": { // database type
    "migrated": [        // list db name which successfully migrated
      "", ""
    ],
    "migratedDbInfo": [{  // db info of successfully migrated databases
      "id": "",           // internal db id
      "classifier": {},
      "namespace": "",
      "type": "",
      "name": "",         // database name
      "timeDbCreation": "",
      "backupDisabled": false | true,
      "physicalDatabaseId": "",
      "connectionProperties": [{}]
    }],
    "conflicted": [],        // list databases which can pass validation
    "failed": [],			 // list database which failed during migration
    "failureReasons": []     // migration failure
  }
}
```

Postgresql response example:

```json
{
  "postgresql": {
    "migrated": [
      "dbaas_dd4a01b4b92d48039c75a6546be337f9"
    ],
    "migratedDbInfo": [
      {
        "id": "d1a4c2a6-bf19-4638-afdb-e9be337992c8",
        "classifier": {
          "microserviceName": "ms-1-test",
          "namespace": "ns-1",
          "scope": "service"
        },
        "namespace": "ns-1",
        "type": "postgresql",
        "name": "dbaas_dd4a01b4b92d48039c75a6546be337f9",
        "externallyManageable": false,
        "timeDbCreation": "2023-02-17T09:00:40.363+00:00",
        "settings": null,
        "backupDisabled": false,
        "physicalDatabaseId": "postgresql-dev",
        "connectionProperties": [
          {
            "password": "11111",
            "role": "admin",
            "port": 5432,
            "host": "pg-patroni.postgresql-dev",
            "name": "dbaas_dd4a01b4b92d48039c75a6546be337f9",
            "url": "jdbc:postgresql://pg-patroni.postgresql-dev:5432/dbaas_dd4a01b4b92d48039c75a6546be337f9",
            "username": "dbaas_ef2952820c184ef69b9703105e93fcb9"
          },
          {
            "password": "22222",
            "role": "streaming",
            "port": 5432,
            "host": "pg-patroni.postgresql-dev",
            "name": "dbaas_dd4a01b4b92d48039c75a6546be337f9",
            "url": "jdbc:postgresql://pg-patroni.postgresql-dev:5432/dbaas_dd4a01b4b92d48039c75a6546be337f9",
            "username": "dbaas_616e15660cba4c3f88ffb413fd7459f4"
          },
          {
            "password": "33333",
            "role": "rw",
            "port": 5432,
            "host": "pg-patroni.postgresql-dev",
            "name": "dbaas_dd4a01b4b92d48039c75a6546be337f9",
            "url": "jdbc:postgresql://pg-patroni.postgresql-dev:5432/dbaas_dd4a01b4b92d48039c75a6546be337f9",
            "username": "dbaas_86ec1511ab194e24a249cbb67e1de944"
          },
          {
            "password": "44444",
            "role": "ro",
            "port": 5432,
            "host": "pg-patroni.postgresql-dev",
            "name": "dbaas_dd4a01b4b92d48039c75a6546be337f9",
            "url": "jdbc:postgresql://pg-patroni.postgresql-dev:5432/dbaas_dd4a01b4b92d48039c75a6546be337f9",
            "username": "dbaas_4481dc18c3c94e56b601723492130448"
          }
        ]
      }
    ],
    "conflicted": [],
    "failed": [],
    "failureReasons": []
  }
}
```
