# DBaaS REST API docs

This documentation presents REST API for "Database as a Service" component. DBaaS is aggregator for all adapters. 
DBaaS is purposed to aggregate the requests for the administrated databases and send to the necessary adapter. 
DBaaS stores information about all databases used in cloud project. These databases are isolated by namespace. 
DBaaS uses Classifier for identification a database within one cloud namespace. Classifier indicates service information, 
such as type of database (service or tenant), microservice name and namespace, tenantId in case of tenant databases.

> Note: all examples are given with mandatory fields only, optional fields have been omitted.

* [DBaaS REST API docs](#dbaas-rest-api-docs)
  * [Terms and Definitions](#terms-and-definitions)
    * [Classifier](#classifier)
    * [Definitions](#definitions)
  * [Database Administration](#database-administration)
    * [Get or create new database](#get-or-create-new-database)
    * [List of all databases](#list-of-all-databases)
    * [Get databases by  dbName](#get-databases-by-dbname)
    * [Get database by classifier](#get-database-by-classifier)
    * [Delete database by classifier](#delete-database-by-classifier)
    * [List of ghosts and lost databases](#list-of-ghosts-and-lost-databases-deprecated)
    * [External database registration](#external-database-registration)
  * [Database operations](#database-operations)
    * [Change user password](#change-user-password)
    * [Recreate database with existing classifier](#recreate-database-with-existing-classifier)
    * [Update existing classifier](#update-existing-database-classifier)
    * [Update physical host in connection properties](#update-physical-host-in-connection-properties)
    * [Update existing connection properties](#update-existing-database-connection-properties)
    * [Link databases to another namespace](#link-databases-to-another-namespace)
  * [Balancing rules](#balancing-rules)
    * [On Namespace PhysDB Balancing Rules](#on-namespace-physdb-balancing-rule)
    * [On Microservice PhysDB Balancing Rules](#on-microservice-physdb-balancing-rule)
    * [Validation for microservice balancing rules](#validation-for-microservices-balancing-rules)
    * [Debugging for microservice balancing rules](#debugging-for-microservice-balancing-rules)
    * [Get permanent namespace balancing rules](#get-permanent-namespace-balancing-rule)
    * [Add permanent namespace balancing rules](#add-permanent-namespace-balancing-rule)
    * [Delete permanent namespace balancing rules](#delete-permanent-namespace-balancing-rule)
    * [Change default physical database](#change-default-physical-database)
    * [Get on microservice physical database balancing rules](#get-on-microservice-physical-database-balancing-rules)
  * [Physical database registration](#physicaldatabaseregistration)
    * [Register new physical database](#register-new-physical-database)
    * [List registered physical databases](#list-registered-physical-databases)
    * [Delete physical database](#delete-physical-database)
  * [Aggregated backup administration](#aggregatedbackupadministration)
    * [Get all backups of namespace](#get-all-backups-of-namespace)
    * [Restore namespace](#restore-namespace)
    * [Validate backup](#validate-backup)
    * [Get restoration info](#get-restoration-info)
    * [Get backup info](#get-backup-info)
    * [Add new backup info](#add-new-backup-info)
    * [Backup namespace](#backup-namespace)
    * [Delete backup](#delete-backup)
  * [Aggregated Backup Restore](#aggregatedbackuprestore)
    * [Bulk restore database](#bulk-restore-database)
    * [Bulk get restoration info](#bulk-get-restoration-info)
  * [Migration](#migration)
    * [Register database](#register-databases)
    * [Register databases with user creation](#register-databases-with-user-creation)
  * [Database users](#database-users)
    * [Get or create user](#get-or-create-user)
    * [Detete user](#delete-user)
    * [Rotate user password](#rotate-user-password)
    * [Restore users](#restore-users)
  * [Access grants](#access-grants)
      * [Get access grants](#get-access-grants)
  * [Blue-Green operations](#blue-green-operations)
    * [Get orphan databases](#get-orphan-databases)
    * [Delete orphan databases](#delete-orphan-databases)
  * [Declarative operations](#declarative-operations)
    * [Get extended process status info](#get-extended-process-status-info)
  * [Composite Structure](#composite-structure)
    * [Save or Update Composite Structure](#save-or-update-composite-structure)
    * [Get List Composite Structures](#get-list-composite-structures)
    * [Get Composite Structure by Id](#get-composite-structure-by-id)
    * [Delete Composite Structure registration by Id](#delete-composite-structure-registration-by-id)
  * [Debug operations](#debug-operations)
    * [Get Dump of Dbaas Database Information](#get-dump-of-dbaas-database-information)
    * [Get lost databases](#get-lost-databases)
    * [Get ghost databases](#get-ghost-databases)
    * [Get Overall Status](#get-overall-status)
    * [Find Debug Logical Databases](#find-debug-logical-databases)

## Terms and Definitions
### Classifier

Classifier is a part of complex database identifier. Pair of type and classifier is sufficient to clearly identify the database.
From users point of view classifier is a JSON object with list of mandatory and optional fields.

Structure of V3 classifier:

```text
"classifier":{
    "tenantId": id, //mandatory if you use tenant scope 
    "microserviceName": "name", //mandatory
    "scope": "tenant"|"service", //mandatory
    "namespace": "namespace"    //mandatory
    "custom_keys": <>            //optional
}
```

where custom keys - are arbitrary keys that can be specified by users.
For example, imagine you have a second service. In this case, your classifier must contain some additional custom_key,
let's say logicalDbId:

```text
"classifier":{
    "tenantId": id, //mandatory if you use tenant scope 
    "microserviceName": "name", //mandatory
    "scope": "tenant"|"service", //mandatory
    "namespace": "namespace"    //mandatory
    "logicalDbId": "second-service-db"
}
```
Here, logicalDbId is the custom key that is being used to uniquely define the second service's database.

### Definitions

* **Physical Database** - separate database cluster, such as postgresql, cassandra and so on  
* **Logical Database** - logical database inside physical database - usually is a result of "create database" query or
  something similar
* **Physical Database Identifier** - string, which should uniquely identify Physical Database in DbaaS Aggregator
* **DbaaS Adapter** - service installed with Physical Database usually in the same Openshift project, which adapts DB
  specific queries to DbaaS HTTP Contract
* **Database Type** - string, uniquely identifies one of supported databases (f.e. postgresql or mongodb)
* **PhysicalDB Labels** - set of key-value pairs corresponding to specific PhyDB, which is constant most of the time,
  but could be changed during PhyDB upgrade, could be used by PhyDB to provide clients with information describing this
  specific cluster, like API version, number of nodes, plugins versions, etc

## Database Administration

### Get or create new database
Creates new database and returns it with connection information, or returns the already created database if it exists. 

* **URI:**  `PUT {dbaas_host}/api/v3/dbaas/{namespace}/databases`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD` 
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type      | Name                              | Description                                                                                                                              | Schema                                          |
|-----------|-----------------------------------|------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------|
| **Path**  | **namespace**  <br>*required*     | Namespace where database will be placed and with which namespace they will be associated                                                 | string                                          |
| **Body**  | **createRequest**  <br>*required* | The model for creating the database in the DBaaS                                                                                         | [DatabaseCreateRequest](#databasecreaterequest) |
| **Query** | **async**  <br>*optional*         | Determines if database should be created asynchronously - will send 202 response instead of waiting for database creation in the adapter | boolean                                         |


* **Success Response:**  
  
| HTTP Code | Description                         | Schema                                |
|-----------|-------------------------------------|---------------------------------------|
| **200**   | Already having such database.       | [DatabaseResponse](#databaseresponse) |
| **201**   | Database created.                   | [DatabaseResponse](#databaseresponse) |
| **202**   | Database is in process of creation. | [DatabaseResponse](#databaseresponse) |

* **Error Response:** 

| HTTP Code | Description                                                     | Schema |
|-----------|-----------------------------------------------------------------|--------|
| **400**   | There is no appropriate adapter for the specified database type | string |
| **401**   | Requested role is not allowed.                                  | string |
| **403**   | You cannot access databases in this namespace.                  | string |
| **500**   | Unknown error which may be related with internal work of DbaaS. | string |

* **Sample call**

  Request:
    ```bash
    curl -X PUT \
      http://localhost:8080/api/v3/dbaas/cloud-core-dev1/databases \
      -H 'Content-Type: application/json' \
      -H 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' \
      -d '{
        "classifier":{
            "microserviceName":"test-service",
            "scope":"service", 
            "namespace":"cloud-core-dev1"
        },
        "type":"postgresql",
        "originService":"test-service"
      }'
    ```
  Response:
    ```text
    OK 200 
    or 
    CREATED 201
    ```
  Response body:
    ```json
    {
        "id": "475ebd72-1409-4a06-8bd8-e408f21e3819",
        "classifier": {
            "microserviceName": "test-service",
            "namespace": "cloud-core-dev1",
            "scope": "service"
        },
        "namespace": "cloud-core-dev1",
        "type": "postgresql",
        "name": "dbaas_c4245fb2989c48b38ded815c61a95e82",
        "externallyManageable": false,
        "timeDbCreation": "2023-03-07T08:08:09.631+00:00",
        "settings": null,
        "backupDisabled": false,
        "physicalDatabaseId": "core-postgresql",
        "connectionProperties": {
            "host": "pg-patroni.core-postgresql",
            "name": "dbaas_c4245fb2989c48b38ded815c61a95e82",
            "password": "126ccad16e05400fa03be2a3e1461e87",
            "port": 5432,
            "role": "admin",
            "url": "jdbc:postgresql://pg-patroni.core-postgresql:5432/dbaas_c4245fb2989c48b38ded815c61a95e82",
            "username": "dbaas_e54c65c92d464c2f885078d03354ade6"
        }
    }
    ```
  
### List of all databases
Returns the list of all databases.

* **URI:**  `GET {dbaas_host}/api/v3/dbaas/{namespace}/databases/list`
* **Headers:**  
  not required
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type      | Name                              | Description                                         | Schema  | Default   |
|-----------|-----------------------------------|-----------------------------------------------------|---------|-----------|
| **Path**  | **namespace**  <br>*required*     | Project namespace in which the databases are used   | string  |           |
| **Query** | **withResources**  <br>*optional* | Parameter for adding database resources to response | boolean | `"false"` |

* **Success Response:**

| HTTP Code | Description                    | Schema                                                      |
|-----------|--------------------------------|-------------------------------------------------------------|
| **200**   | List of databases in namespace | < [DatabaseResponseListCP](#databaseresponselistcp) > array |

* **Error Response:**

| HTTP Code | Description                    | Schema                                              |
|-----------|--------------------------------|-----------------------------------------------------|
| **500**   | Internal error                 | No Content                                          |

* **Sample call**

  Request:
    ```bash
    curl -X GET \
      http://localhost:8080/api/v3/dbaas/cloud-core-dev-1/databases/list?withResources=false \
      -H 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' 
    ```
  Response:
    ```text
    OK 200
    ```
  
  Response body:
  ```json
  [  
    {
      "id": "475ebd72-1409-4a06-8bd8-e408f21e3819",
      "classifier": {
        "microserviceName": "test-service",
        "namespace": "cloud-core-dev1",
        "scope": "service"
      },
      "namespace": "cloud-core-dev1",
      "type": "postgresql",
      "name": "dbaas_c4245fb2989c48b38ded815c61a95e82",
      "externallyManageable": false,
      "timeDbCreation": "2023-03-07T08:08:09.631+00:00",
      "settings": null,
      "backupDisabled": false,
      "physicalDatabaseId": "core-postgresql",
      "connectionProperties": [{
        "host": "pg-patroni.core-postgresql",
        "name": "dbaas_c4245fb2989c48b38ded815c61a95e82",
        "encryptedPassword": "{v2c}{AES}{DEFAULT_KEY}{4llOhNokx+N2tHiM1ryv0A==}",
        "port": 5432,
        "role": "admin",
        "url": "jdbc:postgresql://pg-patroni.core-postgresql:5432/dbaas_c4245fb2989c48b38ded815c61a95e82",
        "username": "dbaas_e54c65c92d464c2f885078d03354ade6"
      }]
    }, 
    {}
  ]
    ```
  
### Get databases by dbName

Returns the list of databases by logical database name.

* **URI:**  `GET {dbaas_host}/api/v3/dbaas/databases/find-by-name/{dbname}?namespace={namespace}`
* **Headers:**  
  not required
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME`
  and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type      | Name                                     | Description                               | Schema | Default |
|-----------|------------------------------------------|-------------------------------------------|--------|---------|
| **Path**  | **dbname**     <br>*required*            | Name of the database to search for        | string |         |
| **Query** | **namespace**  <br>*optional*            | namespace in which the databases are used | string |         |
| **Query** | *withDecryptedPassword**  <br>*optional* | Is password should be decrypted           | string | false   |

* **Success Response:**

| HTTP Code | Description                    | Schema                                                      |
|-----------|--------------------------------|-------------------------------------------------------------|
| **200**   | List of databases with name    | < [DatabaseResponseListCP](#databaseresponselistcp) > array |

* **Error Response:**

| HTTP Code | Description                    | Schema                                              |
|-----------|--------------------------------|-----------------------------------------------------|
| **500**   | Internal error                 | No Content                                          |

* **Sample call**

  Request:
    ```bash
    curl -X GET \
      http://localhost:8080/api/v3/dbaas/databases/find-by-name/dbaas_e905eef3c79841e09c4e916c2cc2bb14 \
      -H 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' 
    ```
  or
    ```bash
    curl -X GET \
      http://localhost:8080/api/v3/dbaas/databases/find-by-name/dbaas_e905eef3c79841e09c4e916c2cc2bb14?namespace=cloud-core-dev-1 \
      -H 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' 
    ```
  Response:
    ```text
    OK 200
    ```

  Response body:
  ```json
  [
    {
      "id": "25227bf5-7ad4-4ce8-ad76-a46a53048f0c",
      "classifier": {
        "microserviceName": "control-plane",
        "namespace": "cloud-core-dev-1",
        "scope": "service"
      },
      "namespace": "cloud-core-dev-1",
      "type": "postgresql",
      "name": "dbaas_e905eef3c79841e09c4e916c2cc2bb14",
      "externallyManageable": false,
      "timeDbCreation": "2023-01-10T08:17:33.725+00:00",
      "settings": null,
      "backupDisabled": false,
      "physicalDatabaseId": "core-postgresql",
      "connectionProperties": [
        {
          "role": "admin",
          "port": 5432,
          "host": "pg-patroni.core-postgresql",
          "name": "dbaas_e905eef3c79841e09c4e916c2cc2bb14",
          "url": "jdbc:postgresql://pg-patroni.core-postgresql:5432/dbaas_e905eef3c79841e09c4e916c2cc2bb14",
          "username": "dbaas_ba08dfdefcc74c5a94f8e509f5cd0c10",
          "encryptedPassword": "{v2c}{AES}{DEFAULT_KEY}{aNvlsnC4jaWuxQbvsPLAMyamBnx70TpE9/BEnFgxBUElBmaDigwXwS9tCEI9UG5i}"
        },
        {
          "role": "rw",
          "port": 5432,
          "host": "pg-patroni.core-postgresql",
          "name": "dbaas_e905eef3c79841e09c4e916c2cc2bb14",
          "url": "jdbc:postgresql://pg-patroni.core-postgresql:5432/dbaas_e905eef3c79841e09c4e916c2cc2bb14",
          "username": "dbaas_536e52f474944b4d932e455ceb8a0872",
          "encryptedPassword": "{v2c}{AES}{DEFAULT_KEY}{Sh8vWxGCPgKMH1Ac6NNnmAmG9qF2d9K+8jrWYdwOMMUlBmaDigwXwS9tCEI9UG5i}"
        },
        {
          "role": "ro",
          "port": 5432,
          "host": "pg-patroni.core-postgresql",
          "name": "dbaas_e905eef3c79841e09c4e916c2cc2bb14",
          "url": "jdbc:postgresql://pg-patroni.core-postgresql:5432/dbaas_e905eef3c79841e09c4e916c2cc2bb14",
          "username": "dbaas_5b7d4f245bc24027890824f4e3c6482a",
          "encryptedPassword": "{v2c}{AES}{DEFAULT_KEY}{OjdB7jTeCP9Ij21oQg78vjSmncxthW/WU0EjybfvCF0lBmaDigwXwS9tCEI9UG5i}"
        },
        {
          "role": "streaming",
          "port": 5432,
          "host": "pg-patroni.core-postgresql",
          "name": "dbaas_e905eef3c79841e09c4e916c2cc2bb14",
          "url": "jdbc:postgresql://pg-patroni.core-postgresql:5432/dbaas_e905eef3c79841e09c4e916c2cc2bb14",
          "username": "dbaas_a4aef6daaa3c4325bd0e6ff0d00fda5b",
          "encryptedPassword": "{v2c}{AES}{DEFAULT_KEY}{ZNm1be1ebje0ZiYE8OqMpdQxzHpfTgY0e/Wtop3lbzolBmaDigwXwS9tCEI9UG5i}"
        }
      ]
    }
  ]
    ```

Request:

  ```bash
    curl -X GET \
      http://localhost:8080/api/v3/dbaas/databases/find-by-name/dbaas_e905eef3c79841e09c4e916c2cc2bb14?namespace=cloud-core-dev-1&withDecryptedPassword=true \
      -H 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' 
  ```

Response:

```text
    OK 200
```

Response body:

```json
  [
  {
    "id": "25227bf5-7ad4-4ce8-ad76-a46a53048f0c",
    "classifier": {
      "microserviceName": "control-plane",
      "namespace": "cloud-core-dev-1",
      "scope": "service"
    },
    "namespace": "cloud-core-dev-1",
    "type": "postgresql",
    "name": "dbaas_e905eef3c79841e09c4e916c2cc2bb14",
    "externallyManageable": false,
    "timeDbCreation": "2023-01-10T08:17:33.725+00:00",
    "settings": null,
    "backupDisabled": false,
    "physicalDatabaseId": "core-postgresql",
    "connectionProperties": [
      {
        "role": "admin",
        "port": 5432,
        "host": "pg-patroni.core-postgresql",
        "name": "dbaas_e905eef3c79841e09c4e916c2cc2bb14",
        "url": "jdbc:postgresql://pg-patroni.core-postgresql:5432/dbaas_e905eef3c79841e09c4e916c2cc2bb14",
        "username": "dbaas_ba08dfdefcc74c5a94f8e509f5cd0c10",
        "password": "8ca938940c584c05bd94c41201c010a4"
      },
      {
        "role": "rw",
        "port": 5432,
        "host": "pg-patroni.core-postgresql",
        "name": "dbaas_e905eef3c79841e09c4e916c2cc2bb14",
        "url": "jdbc:postgresql://pg-patroni.core-postgresql:5432/dbaas_e905eef3c79841e09c4e916c2cc2bb14",
        "username": "dbaas_536e52f474944b4d932e455ceb8a0872",
        "password": "8ca938940c584c05bd94c41201c010a4"
      },
      {
        "role": "ro",
        "port": 5432,
        "host": "pg-patroni.core-postgresql",
        "name": "dbaas_e905eef3c79841e09c4e916c2cc2bb14",
        "url": "jdbc:postgresql://pg-patroni.core-postgresql:5432/dbaas_e905eef3c79841e09c4e916c2cc2bb14",
        "username": "dbaas_5b7d4f245bc24027890824f4e3c6482a",
        "password": "8ca938940c584c05bd94c41201c010a4"
      },
      {
        "role": "streaming",
        "port": 5432,
        "host": "pg-patroni.core-postgresql",
        "name": "dbaas_e905eef3c79841e09c4e916c2cc2bb14",
        "url": "jdbc:postgresql://pg-patroni.core-postgresql:5432/dbaas_e905eef3c79841e09c4e916c2cc2bb14",
        "username": "dbaas_a4aef6daaa3c4325bd0e6ff0d00fda5b",
        "password": "8ca938940c584c05bd94c41201c010a4"
      }
    ]
  }
]
```

### Get database by classifier
Returns connection to an already created database using classifier to search.

* **URI:**  `POST {dbaas_host}/api/v3/dbaas/{namespace}/databases/get-by-classifier/{type}`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                                  | Description                                                                                         | Schema                                                    |
|----------|---------------------------------------|-----------------------------------------------------------------------------------------------------|-----------------------------------------------------------|
| **Path** | **namespace**  <br>*required*         | For which namespace user want to receive databases                                                  | string                                                    |
| **Path** | **type**  <br>*required*              | The type of physical database in which the database was created. For example PostgreSQL  or MongoDB | string                                                    |
| **Body** | **classifierRequest**  <br>*required* | Classifier on which to search databases.                                                            | [ClassifierWithRolesRequest](#classifierwithrolesrequest) |

* **Success Response:**

| HTTP Code | Description                 | Schema                                |
|-----------|-----------------------------|---------------------------------------|
| **200**   | Successfully found database | [DatabaseResponse](#databaseresponse) |

* **Error Response:**

| HTTP Code | Description                               | Schema |
|-----------|-------------------------------------------|--------|
| **401**   | Requested role is not allowed             | string |
| **404**   | Cannot find database with such classifier | string |

* **Sample call**

  Request:
    ```bash
    curl -X POST \
     http://localhost:8080/api/v3/dbaas/cloud-core-dev1/databases/get-by-classifier/postgresql \
      -H "Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=" \
      -H 'Content-Type: application/json' \
      -d '{
        "classifier": {
          "microserviceName": "test-service",
          "namespace": "cloud-core-dev1",
          "scope": "service"
        },
        "originService":"test-service"
      }'
    ```
  Response:
    ```text
    OK 200
    ```
  Response body:
    ```json
      {
         "id":null,
         "classifier":{
            "microserviceName":"test-service",
            "namespace":"cloud-core-dev1",
            "scope":"service"
         },
         "namespace":"cloud-core-dev1",
         "type":"postgresql",
         "name":"dbaas_c4245fb2989c48b38ded815c61a95e82",
         "externallyManageable":false,
         "timeDbCreation":"2023-03-07T08:08:09.631+00:00",
         "settings":null,
         "backupDisabled":false,
         "physicalDatabaseId":"core-postgresql",
         "connectionProperties":{
            "password":"126ccad16e05400fa03be2a3e1461e87",
            "role":"admin",
            "port":5432,
            "host":"pg-patroni.core-postgresql",
            "name":"dbaas_c4245fb2989c48b38ded815c61a95e82",
            "url":"jdbc:postgresql://pg-patroni.core-postgresql:5432/dbaas_c4245fb2989c48b38ded815c61a95e82",
            "username":"dbaas_e54c65c92d464c2f885078d03354ade6"
         }
      }
    ```

### Delete database by classifier
Deletes database by classifier in the specific namespace.

* **URI:**  `DELETE {dbaas_host}/api/v3/dbaas/{namespace}/databases/{type}`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                                  | Description                                                              | Schema                                                    |
|----------|---------------------------------------|--------------------------------------------------------------------------|-----------------------------------------------------------|
| **Path** | **namespace**  <br>*required*         | Project namespace in which the base is used                              | string                                                    |
| **Path** | **type**  <br>*required*              | The physical type of logical database. For example mongodb or postgresql | string                                                    |
| **Body** | **classifierRequest**  <br>*required* | A unique identifier of the document in the database                      | [ClassifierWithRolesRequest](#classifierwithrolesrequest) |

* **Success Response:**

| HTTP Code | Description                                                                    | Schema |
|-----------|--------------------------------------------------------------------------------|--------|
| **200**   | Successfully deleted database or database with such classifier does not exist. | string |

* **Error Response:**

| HTTP Code | Description                                                             | Schema |
|-----------|-------------------------------------------------------------------------|--------|
| **401**   | Requested role is not allowed                                           | string |
| **403**   | You cannot access databases in this namespace                           | string |
| **406**   | Dbaas is working in PROD mode. Deleting logical databases is prohibited | string |

* **Sample call**

  Request:
    ```bash
    curl -X DELETE \
     http://localhost:8080/api/v3/dbaas/test-dbaas/databases/postgresql \
      -H "Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=" \
      -H 'Content-Type: application/json' \
      -d '{
        "classifier":{
          "microserviceName":"test-service",
          "scope":"service", 
          "namespace":"test-dbaas"
        },
        "originService":"test-service"
      }'

    ```
  Response:
    ```text
    OK 200
    ```
  
### List of ghosts and lost databases. Deprecated.
Databases may get lost if they were marked to delete but were not actually deleted. 
An existing database stays as a ghost if it was not registered in DBaaS.

* **URI:**  `GET {dbaas_host}/api/v3/dbaas/{namespace}/databases/statuses`
* **Headers:**  
  not required
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                                  | Description                                                              | Schema                                                    |
|----------|---------------------------------------|--------------------------------------------------------------------------|-----------------------------------------------------------|
| **Path** | **namespace**  <br>*required*         | Project namespace in which the base is used                              | string                                                    |

* **Success Response:**

| HTTP Code | Description                        | Schema                          |
|-----------|------------------------------------|---------------------------------|
| **200**   | List of ghosts and lost databases. | [DatabasesInfo](#databasesinfo) |

* **Error Response:**

| HTTP Code | Description                                                             | Schema     |
|-----------|-------------------------------------------------------------------------|------------|
| **500**   | Requested role is not allowed                                           | No Content |

* **Sample call**

  Request:
    ```bash
    curl -X GET \
     http://localhost:8080/api/v3/dbaas/test-dbaas/databases/statuses \
      -H "Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8="
    ```
  Response:
    ```text
    OK 200
    ```
  
```json
{
    "global": {
        "name": "all",
        "totalDatabases": [
            {
                "name": "dbaas_db_1"
            },
            ...
        ],
        "registration": {
            "totalDatabases": [
                {
                    "name": "dbaas_db_2"
                },
                ...
            ],
            "lostDatabases": [],
            "ghostDatabases": []
        },
        "deletingDatabases": []
    },
    "perAdapters": [
        {
            "name": "postgresql",
            "totalDatabases": [
                {
                    "name": "dbaas_core"
                },
              ...
            ],
            "registration": {
                "totalDatabases": [],
                "lostDatabases": [],
                "ghostDatabases": []
            },
            "deletingDatabases": []
        },
        {
            "name": "elasticsearch",
            "totalDatabases": [],
            "registration": {
                "totalDatabases": [],
                "lostDatabases": [],
                "ghostDatabases": []
            },
            "deletingDatabases": []
        },
        ...
    ]
}
```

### External database registration
This API supports registration in DbaaS for any external logical database.

* **URI:**  `PUT {dbaas_host}/api/v3/dbaas/{namespace}/databases/registration/externally_manageable`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                                        | Description                                          | Schema                                              |
|----------|---------------------------------------------|------------------------------------------------------|-----------------------------------------------------|
| **Path** | **namespace**  <br>*required*               | Namespace where microservice for this db exists      | string                                              |
| **Body** | **externalDatabaseRequest**  <br>*required* | Request with connection information for new database | [ExternalDatabaseRequest](#externaldatabaserequest) |

* **Success Response:**

| HTTP Code | Description                                     | Schema                                                |
|-----------|-------------------------------------------------|-------------------------------------------------------|
| **200**   | Successfully found database.                    | [ExternalDatabaseResponse](#externaldatabaseresponse) |
| **201**   | The database was added or updated successfully. | [ExternalDatabaseResponse](#externaldatabaseresponse) |

* **Error Response:**

| HTTP Code | Description                                                                                                   | Schema     |
|-----------|---------------------------------------------------------------------------------------------------------------|------------|
| **401**   | Requested role is not allowed                                                                                 | string     |
| **403**   | Namespace in classifier and path variable are not equal                                                       | string     |
| **409**   | Logical database with such classifier and type already exist in namespace and it is internal logical database | string     |
| **500**   | Internal error                                                                                                | No Content |

* **Sample call**

  Request:
    ```bash
    curl -X PUT \
     http://localhost:8080/api/v3/dbaas/test-dbaas/databases/registration/externally_manageable \
      -H "Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=" \
      -H 'Content-Type: application/json' \
      -d '{
        "classifier":{
          "microserviceName":"test-service",
          "scope":"service", 
          "namespace":"test-dbaas"
        },
        "connectionProperties": [{
          "host": "pg-patroni.cpq-postgresql",
          "port": "5432",
          "url": "jdbc:postgresql://pg-patroni.cpq-postgresql:5432/dbaas_123",
          "role": "admin"
        }],
        "dbName": "dbaas_123",
        "type": "postgresql",
        "updateConnectionProperties": false
      }'
    ```
  
  Response:
    ```text
    OK 200
    or
    CREATED 201
    ```
  Response body:
    ```json
      {
         "id": "uuid-externally-manageable",
         "classifier":{
            "microserviceName":"test-service",
            "scope":"service", 
            "namespace":"test-dbaas"
         },
         "namespace":"test-dbaas",
         "type":"postgresql",
         "name":"dbaas_123",
         "externallyManageable":false,
         "timeDbCreation":"2023-03-07T08:08:09.631+00:00",
         "connectionProperties": [{
          "host": "pg-patroni.cpq-postgresql",
          "port": "5432",
          "url": "jdbc:postgresql://pg-patroni.cpq-postgresql:5432/dbaas_123",
          "role": "admin"
          }]
      }
    ```

## Database operations
This controller contains API for operations with already created databases, users. 

### Change user password
The API changes password of a user that is related to the specified database. A password will be changed 
to a random value.

If classifier is not passed then all passwords of databases in the namespace and type will be changed.

* **URI:**  `POST {dbaas_host}/api/v3/dbaas/namespaces/{namespace}/password-changes`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                                      | Description                                                                         | Schema                                          |
|----------|-------------------------------------------|-------------------------------------------------------------------------------------|-------------------------------------------------|
| **Path** | **namespace**  <br>*required*             | Namespace where microservice for this db exists                                     | string                                          |
| **Body** | **passwordChangeRequest**  <br>*required* | Describes the database and the type of database that needs a password to be changed | [PasswordChangeRequest](#passwordchangerequest) |

* **Success Response:**

| HTTP Code | Description                                                                                                                                                           | Schema                                            |
|-----------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------|
| **200**   | The passwords have been changed successfully. If errors will occur during the password changes, then the errors are aggregated and returned with maximum error status | [PasswordChangeResponse](#passwordchangeresponse) |

* **Error Response:**

| HTTP Code | Description                                                                                                   | Schema     |
|-----------|---------------------------------------------------------------------------------------------------------------|------------|
| **401**   | Requested role is not allowed                                                                                 | string     |

* **Sample call**

  Request:
    ```bash
    curl -X POST \
     http://localhost:8080/api/v3/dbaas/namespaces/test-namespace/password-changes \
      -H "Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=" \
      -H 'Content-Type: application/json' \
      -d '{
        "classifier":{
          "microserviceName":"test-service",
          "scope":"service", 
          "namespace":"test-namespace"
        },
        "type": "postgresql",
        "userRole": "admin"
      }'
    ```
  Response:
    ```text
    OK 200
    ```
  Response body:
  ```json
  {
   "changed":[
      {
         "classifier":{
            "microserviceName":"test-service",
            "scope":"service", 
            "namespace":"test-namespace"
         },
         "connection":{
            "password":"763cc8be6f0a4756b6b48f50e6a63ed8",
            "role":"admin",
            "port":5432,
            "host":"pg-patroni.core-postgresql",
            "name":"dbaas_autotests_677188448d914e67b1818d637a995184",
            "url":"jdbc:postgresql://pg-patroni.core-postgresql:5432/dbaas_autotests_677188448d914e67b1818d637a995184",
            "username":"dbaas_49e7c0c76765454f9ce8bf0ceea0b923"
         }
      }
   ],
   "failed":[]
  }
  ```

### Recreate database with existing classifier
Recreate existing database with same classifier in the same physicalDb or in another. 
The API can be useful if you want to migrate associated with microservice logical db to another 
physical database. So, DbaaS creates a new empty database. After it, you will get a new connection 
and can perform a migration. Pay attention, each request will produce a new database even if the 
database was previously recreated. So, if your response contains unsuccessful databases you must 
leave only these databases in the request. Otherwise successful databases will be recreated again. 
The previous database is not deleted but is marked as archived.

* **URI:**  `POST {dbaas_host}/api/v3/dbaas/namespaces/{namespace}/databases/recreate`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `dbaas-db-editor` role. Specified as `DBAAS_DB_EDITOR_CREDENTIALS_USERNAME` and `DBAAS_DB_EDITOR_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_db_editor_credentials_username-dbaas_db_editor_credentials_password).
* **Request body:**

| Type     | Name                                          | Description                                                                                                                                                                   | Schema                                                        |
|----------|-----------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------|
| **Path** | **namespace**  <br>*required*                 | Namespace with which new database will be associated                                                                                                                          | string                                                        |
| **Body** | **recreateDatabasesRequests**  <br>*required* | Request body must contain registered physicalDatabaseId and classifier of created logical database. The list of created databases can be found by 'List of all databases' API | < [RecreateDatabaseRequest](#recreatedatabaserequest) > array |

**Success Response:**

| HTTP Code | Description                                                       | Schema                                                |
|-----------|-------------------------------------------------------------------|-------------------------------------------------------|
| **200**   | All requested databases were recreated. There are no unsuccessful | [RecreateDatabaseResponse](#recreatedatabaseresponse) |

* **Error Response:**

| HTTP Code | Description                                                                                                                                                     | Schema                                                |
|-----------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------|
| **400**   | Request does not pass validation. Maybe passed physical databases id is unregistered or logical database with requested classifier has not been created before. | string                                                |
| **500**   | Some requested databases were not recreated. There are unsuccessful                                                                                             | [RecreateDatabaseResponse](#recreatedatabaseresponse) |

* **Sample call**

  Request:
    ```bash
    curl -X POST \
     http://localhost:8080/api/v3/dbaas/namespaces/test-namespace/databases/recreate \
      -H "Authorization: Basic ZGJhYXMtZGItZWRpdG9yOjllUm1fUTU1ZmU=" \
      -H 'Content-Type: application/json' \
      -d '[
        {
          "classifier":{
            "microserviceName":"test-service",
            "scope":"service", 
            "namespace":"test-namespace"
          },
          "physicalDatabaseId": "phys-db-id-123",
          "type": "postgresql"
        }
      ]'
    ```
  Response:
    ```text
    OK 200
    ```
  Response body:
  ```json
  {
   "successfully":[
      {
         "classifier":{
            "microserviceName":"test-service",
            "scope":"service", 
            "namespace":"test-namespace"
          },
         "type":"postgresql",
         "newDb":{
            "id":"8a8af799-47c2-4b3d-a7ff-db8ba4bd6f09",
            "classifier":{
            "microserviceName":"test-service",
            "scope":"service", 
            "namespace":"test-namespace"
          },
            "connectionProperties":{
              ...
            },
            "resources":[
               ...
            ],
            "namespace":"test-namespace",
            "type":"postgresql",
            "adapterId":"2a7e39f0-925b-44f7-99ab-8e92cf7e03af",
            "name":"dbaas_35880dd2820a48df84dd696a30ad2d5a",
            "markedForDrop":false,
            "timeDbCreation":"2023-03-03T13:41:34.781+00:00",
            "backupDisabled":false,
            "settings":null,
            "connectionDescription":null,
            "warnings":null,
            "externallyManageable":false,
            "dbOwnerRoles":null,
            "classifierV3Migrated":true
         }
      }
   ],
   "unsuccessfully":[]
  }
  ```

### Update existing database classifier
The API allows to update existing database classifier

* **URI:**  `PUT {dbaas_host}/api/v3/dbaas/namespaces/{namespace}/databases/update-classifier/{type}`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  - Basic type with credentials with `dbaas-db-editor` role. Specified as `DBAAS_DB_EDITOR_CREDENTIALS_USERNAME` and `DBAAS_DB_EDITOR_CREDENTIALS_PASSWORD`
    [deployment parameters](./installation/parameters.md#dbaas_db_editor_credentials_username-dbaas_db_editor_credentials_password).
  - Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
    [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                                        | Description                                                                    | Schema                                              |
|----------|---------------------------------------------|--------------------------------------------------------------------------------|-----------------------------------------------------|
| **Path** | **namespace**  <br>*required*               | Project namespace in which the databases are used                              | string                                              |
| **Path** | **type**  <br>*required*                    | Type of physical database where database was created, e.g. mongodb, postgresql | string                                              |
| **Body** | **updateClassifierRequest**  <br>*required* | Contains primary and target classifier                                         | [UpdateClassifierRequest](#updateclassifierrequest) |

**Success Response:**

| HTTP Code | Description                                  | Schema                                            |
|-----------|----------------------------------------------|---------------------------------------------------|
| **200**   | Database classifier was updated successfully | [DatabaseResponseListCP](#databaseresponselistcp) |

* **Error Response:**

| HTTP Code | Description                                                             | Schema |
|-----------|-------------------------------------------------------------------------|--------|
| **400**   | "from" or "to" classifiers must not be empty                            | string |
| **401**   | Requested role is not allowed                                           | string |
| **404**   | There is no database with provided "from" classifier                    | string |
| **406**   | "from" or "to" classifiers contain namespace different from in the path | string |
| **409**   | There is a database with provided "to" classifier or                    | string |

* **Sample call**

  Request:
    ```bash
    curl -X PUT \
     http://localhost:8080/api/v3/dbaas/namespaces/test-namespace/databases/update-classifier/postgresql \
      -H "Authorization: Basic ZGJhYXMtZGItZWRpdG9yOjllUm1fUTU1ZmU=" \
      -H 'Content-Type: application/json' \
      -d '
      {
        "clone": false,
        "from":{
            "microserviceName":"test-service",
            "scope":"service", 
            "namespace":"test-namespace"
        },
        "fromV1orV2ToV3": false,
        "to":{
            "microserviceName":"test-service",
            "scope":"service", 
            "namespace":"test-namespace",
            "extraKey": "some-extra-key"
        }
      }
      '
    ```
  Response:
    ```text
    OK 200
    ```

### Update Physical Host in Connection Properties

Updates the physical database host in the connection properties of logical databases. 
This API allows users to specify the new physical host and optionally create a copy of the registry record, 
ensuring that existing logical databases are updated efficiently.

* **URI:**  `POST {dbaas_host}/api/v3/dbaas/databases/update-host`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                                   | Description                                                                                                                                                                                                                               | Schema                                        | Default |
|----------|----------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------|---------|
| **Body** | **updateHostRequests**  <br>*required* | List of request objects containing the necessary information to update the physical host in a logical database. Each request in the list contains essential details such as the database type, classifier, and physical host information. | List<[UpdateHostRequest](#updatehostrequest)> |         |

The request body must include a list of `UpdateHostRequest` objects that define the parameters for updating the physical host.

* **Success Response:**

| HTTP Code | Description                            | Schema                                                      |
|-----------|----------------------------------------|-------------------------------------------------------------|
| **200**   | The host has been updated successfully | < [DatabaseResponseListCP](#databaseresponselistcp) > array |

If the operation is successful, a list of updated databases will be returned. The response includes detailed information about each updated database, allowing you to verify that the new host has been correctly set.

* **Error Response:**

| HTTP Code | Description                         | Schema               |
|-----------|-------------------------------------|----------------------|
| **500**   | Internal error                      | No Content           |

An error response may occur if there is an issue with the input parameters or an internal server problem. Ensure that the request body is correctly formatted and that all required fields are provided.

* **Sample call**

  Request:
    ```bash
    curl -X POST \
      http://localhost:8080/api/v3/dbaas/databases/update-host \
      -H 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' \
      -H 'Content-Type: application/json' \
      -d '[
        {
          "type": "postgresql",
          "classifier": { "microserviceName": "control-plane", "namespace": "cloud-core-dev-1", "scope": "service" },
          "makeCopy": true,
          "physicalDatabaseHost": "pg-patroni.core-postgresql",
          "physicalDatabaseId": "core-postgresql"
        }
      ]'
    ```
  Response:
    ```text
    OK 200
    ```

  Response body:
  ```json
      [
   {
      "id":"43548057-135f-46c2-9e0b-2e909050719e",
      "classifier":{
         "microserviceName":"test-service",
         "namespace":"cloud-core-dev5",
         "scope":"service"
      },
      "namespace":"cloud-core-dev5",
      "type":"postgresql",
      "name":"test-service_cloud-core-dev5_134951374251024",
      "externallyManageable":false,
      "timeDbCreation":"2024-10-25T14:22:32.707+00:00",
      "settings":null,
      "backupDisabled":false,
      "physicalDatabaseId":"postgresql-dev:postgres",
      "connectionProperties":[
         {
            "roHost":"pg-patroni-ro.postgresql-dev",
            "role":"admin",
            "port":5432,
            "host":"pg-patroni.postgresql-dev",
            "name":"test-service_cloud-core-dev5_134951374251024",
            "url":"jdbc:postgresql://pg-patroni.postgresql-dev:5432/test-service_cloud-core-dev5_134951374251024",
            "username":"dbaas_5e2e186e68374646b154feff7fdb67e0",
            "encryptedPassword":"{v2c}{AES}{DEFAULT_KEY}{zdcqYoCQlyG6tDwlli9NZHESDa8zDkNy9RJ8rqXyjIkHTqdKUfmt9siqP41dfPTe}"
         }
      ]
   }
]
    ```

### Update existing database connection properties
The API allows to update existing database connection properties.  
Related article: https://perch.qubership.org/display/CLOUDCORE/Update+Connection+Properties  

* **URI:**  `PUT {dbaas_host}/api/v3/dbaas/namespaces/{namespace}/databases/update-connection/{type}`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `dbaas-db-editor` role. Specified as `DBAAS_DB_EDITOR_CREDENTIALS_USERNAME` and `DBAAS_DB_EDITOR_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_db_editor_credentials_username-dbaas_db_editor_credentials_password).
* **Request body:**

| Type     | Name                                                  | Description                                                                    | Schema                                                                  |
|----------|-------------------------------------------------------|--------------------------------------------------------------------------------|-------------------------------------------------------------------------|
| **Path** | **namespace**  <br>*required*                         | Project namespace in which the databases are used                              | string                                                                  |
| **Path** | **type**  <br>*required*                              | Type of physical database where database was created, e.g. mongodb, postgresql | string                                                                  |
| **Body** | **updateConnectionPropertiesRequest**  <br>*required* | Contains classifier and new connection properties                              | [UpdateConnectionPropertiesRequest](#updateconnectionpropertiesrequest) |

**Success Response:**

| HTTP Code | Description                                              | Schema                                |
|-----------|----------------------------------------------------------|---------------------------------------|
| **200**   | Database connection properties were updated successfully | [DatabaseResponse](#databaseresponse) |

* **Error Response:**

| HTTP Code | Description                                                      | Schema |
|-----------|------------------------------------------------------------------|--------|
| **400**   | Database classifier or new connection properties must not be nil | string |
| **404**   | there is no existing database with such type and classifier      | string |

* **Sample call**

  Request:
    ```bash
    curl -X PUT \
     http://localhost:8080/api/v3/dbaas/namespaces/test-namespace/databases/update-connection/postgresql \
      -H "Authorization: Basic ZGJhYXMtZGItZWRpdG9yOjllUm1fUTU1ZmU=" \
      -H 'Content-Type: application/json' \
      -d '
      {
        "classifier":{
            "microserviceName":"test-service",
            "scope":"service", 
            "namespace":"test-namespace"
         },
        "connectionProperties": {
            "password":"test-pwd",
            "role":"admin",
            "port":5432,
            "host":"pg-patroni.core-postgresql",
            "name":"dbaas_db",
            "url":"jdbc:postgresql://pg-patroni.core-postgresql:5432/dbaas_db",
            "username":"dbaas_username"
        }
      }
      '
    ```
  Response:
    ```text
    OK 200
    ```
  Response body:
  ```json
      {
         "id":null,
         "classifier":{
            "microserviceName":"test-service",
            "scope":"service", 
            "namespace":"test-namespace"
         },
         "namespace":"test-namespace",
         "type":"postgresql",
         "name":"dbaas_db",
         "externallyManageable":false,
         "timeDbCreation":"2023-03-07T08:08:09.631+00:00",
         "settings":null,
         "backupDisabled":false,
         "physicalDatabaseId":"core-postgresql",
         "connectionProperties": {
            "password":"test-pwd",
            "role":"admin",
            "port":5432,
            "host":"pg-patroni.core-postgresql",
            "name":"dbaas_db",
            "url":"jdbc:postgresql://pg-patroni.core-postgresql:5432/dbaas_db",
            "username":"dbaas_username"
        }
      }
    ```

### Link databases to another namespace
The API allows to link databases for the requested microservices to another namespace.
This action will create additional classifiers for required databases in the target namespace, if there is no such classifiers. So, these databases will be accessible from both old and target namespaces.

* **URI:**  `PUT {dbaas_host}/api/v3/dbaas/namespaces/{namespace}/databases/link`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `dbaas-db-editor` role. Specified as `DBAAS_DB_EDITOR_CREDENTIALS_USERNAME` and `DBAAS_DB_EDITOR_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_db_editor_credentials_username-dbaas_db_editor_credentials_password).
* **Request body:**

| Type     | Name                                     | Description                                       | Schema                                        |
|----------|------------------------------------------|---------------------------------------------------|-----------------------------------------------|
| **Path** | **namespace**  <br>*required*            | Project namespace in which the databases are used | string                                        |
| **Body** | **linkDatabasesRequest**  <br>*required* | Contains service names and target namespace       | [LinkDatabasesRequest](#linkdatabasesrequest) |

**Success Response:**

| HTTP Code | Description                                                               | Schema                                                      |
|-----------|---------------------------------------------------------------------------|-------------------------------------------------------------|
| **200**   | All databases for requested microservices were linked to target namespace | < [DatabaseResponseListCP](#databaseresponselistcp) > array |

* **Error Response:**

| HTTP Code | Description                                                            | Schema |
|-----------|------------------------------------------------------------------------|--------|
| **400**   | Request does not pass validation. Maybe some required fields are empty | string |
| **500**   | Some error during databases linking                                    | string |

* **Sample call**

  Request:
    ```bash
    curl -X POST \
     http://localhost:8080/api/v3/dbaas/namespaces/test-namespace-1/databases/link \
      -H "Authorization: Basic ZGJhYXMtZGItZWRpdG9yOjllUm1fUTU1ZmU=" \
      -H 'Content-Type: application/json' \
      -d '
      {
        "serviceNames":["service1", "service2"],
        "targetNamespace": "test-namespace-2"
      }
      '
    ```
  Response:
    ```text
    OK 200
    ```
  Response body:
  ```json
      [
        {
            "id": "014fcc4d-79e5-40f3-ad65-69b5cd42c57c",
            "classifier": {
                "microserviceName": "service1",
                "namespace": "test-namespace-2",
                "scope": "service"
            },
            "namespace": "test-namespace-2",
            "type": "postgresql",
            "name": "dbaas_db_402f45a4abe",
            "externallyManageable": false,
            "timeDbCreation": "2024-11-05T14:04:36.353+00:00",
            "settings": null,
            "backupDisabled": false,
            "physicalDatabaseId": "postgres:postgres",
            "connectionProperties": [...]
        },
        {
            "id": "014fha4w-79w5-40f3-oa65-69n5im42e57",
            "classifier": {
                "microserviceName": "service2",
                "namespace": "test-namespace-2",
                "scope": "service"
            },
            "namespace": "test-namespace-2",
            "type": "postgresql",
            "name": "dbaas_db_32131314abe",
            "externallyManageable": false,
            "timeDbCreation": "2024-11-05T14:04:36.353+00:00",
            "settings": null,
            "backupDisabled": false,
            "physicalDatabaseId": "postgres:postgres",
            "connectionProperties": [...]
        }
      ]
    ```

## Balancing rules
Allows to configure a logic of balancing logical databases over physical.

### On namespace physDb balancing rule
Auto balancing rules allows configure behavior of DbaaS when a physical database of some specific 
type is chosen for new logical database. This rule currently works for new databases only, no 
migration of logical databases between physical databases is supported yet.
Related page: https://perch.qubership.org/display/CLOUDCORE/How+to+configure+namespace+autobalance+rules

* **URI:**  `PUT {dbaas_host}/api/v3/dbaas/{namespace}/physical_databases/balancing/rules/{ruleName}`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                          | Description                                                                                                                                                                 | Schema                                              |
|----------|-------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------|
| **Path** | **namespace**  <br>*required* | Namespace where the rule will be placed, each rule must have a namespace. Rules works only on logical databases created in the same namespace where they have been created. | string                                              |
| **Path** | **ruleName**  <br>*required*  | Name of the rule used as an identifier                                                                                                                                      | string                                              |
| **Body** | **request**  <br>*required*   | Request body with rules                                                                                                                                                     | [RuleRegistrationRequest](#ruleregistrationrequest) |


* **Success Response:**

| HTTP Code | Description           | Schema     |
|-----------|-----------------------|------------|
| **200**   | Existing rule changed | No Content |
| **201**   | New rule created      | No Content |

* **Error Response:**

| HTTP Code | Description                                                     | Schema     |
|-----------|-----------------------------------------------------------------|------------|
| **409**   | Cannot create two different rules for same type with same order | No Content |

* **Sample call**

  Request:
    ```bash
    curl -X PUT \
     http://localhost:8080/api/v3/dbaas/test-namespace/physical_databases/balancing/rules/test-rule \
      -H "Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=" \
      -H 'Content-Type: application/json' \
      -d '{
        "rule": {
          "config": {
            "perNamespace": {
                "phydbid": "phys_id_123"
            }
          },
          "type": "perNamespace"
        },
        "type": "postgresql"
      }'
    ```
  Response:
    ```text
    OK 200 
    or
    CREATED 201
    ```

### Add permanent namespace balancing rule
Allows adding new permanent namespace balancing rule. Balancing rules are intended to define in which physical 
database new logical database should be created. This API allows add such rule for a namespace: 
it means that all logical databases for microservices in this namespace will be placed in specific 
physical database according to rule. Such rule is permanent, and it won't be deleted during physical 
database deletion.  
Related article: https://perch.qubership.org/display/CLOUDCORE/Physical+DB+permanent+balancing+rules 

> WARNING! Rules can be overridden. Rule's integrity and validity is the responsibility of project(applies) side.
> It means that rule doesn't merge and there can be only one version of rule. If you change configuration of previous rule and send it, then logical databases will be created by the new changed rule. Therefore, be careful before deleting or modifying the rule.

* **URI:**  `PUT {dbaas_host}/api/v3/dbaas/balancing/rules/permanent`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  - Basic type with credentials with `dbaas-db-editor` role. Specified as `DBAAS_DB_EDITOR_CREDENTIALS_USERNAME` and `DBAAS_DB_EDITOR_CREDENTIALS_PASSWORD`
    [deployment parameters](./installation/parameters.md#dbaas_db_editor_credentials_username-dbaas_db_editor_credentials_password).
  - Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
    [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                        | Description                                                 | Schema                                                                  |
|----------|-----------------------------|-------------------------------------------------------------|-------------------------------------------------------------------------|
| **Body** | **request**  <br>*required* | List of on namespace balancing rules expected to be applied | < [PermanentPerNamespaceRuleDTO](#permanentpernamespaceruledto) > array |

**Success Response:**

| HTTP Code | Description       | Schema                                                                  |
|-----------|-------------------|-------------------------------------------------------------------------|
| **200**   | New rules created | < [PermanentPerNamespaceRuleDTO](#permanentpernamespaceruledto) > array |

* **Error Response:**

| HTTP Code | Description                                                                                      | Schema     |
|-----------|--------------------------------------------------------------------------------------------------|------------|
| **400**   | Cannot create two different rules for same namespace with different physicalDbId and same DbType | No Content |

* **Sample call**

  Request:
    ```bash
    curl -X PUT \
     http://localhost:8080/api/v3/dbaas/balancing/rules/permanent \
      --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' \
      --header 'Content-Type: application/json' \
      --data '[
        {
          "dbType":"postgresql",
          "physicalDatabaseId":"core-postgresql-1",
          "namespaces": [
            "test-namespace-3",
            "test-namespace-2"
          ]
        },
        {
          "dbType":"mongodb",
          "physicalDatabaseId":"core-mongodb",
          "namespaces": [
            "test-namespace-3",
            "test-namespace-4"
          ]
        }
      ]'
    ```
  Response:
    ```text
    OK 200
    ```
  
  ```json
  [
        {
          "dbType":"postgresql",
          "physicalDatabaseId":"core-postgresql-1",
          "namespaces": [
            "test-namespace-3",
            "test-namespace-2"
          ]
        },
        {
          "dbType":"mongodb",
          "physicalDatabaseId":"core-mongodb",
          "namespaces": [
            "test-namespace-3",
            "test-namespace-4"
          ]
        }
      ]
  ```

### Get permanent namespace balancing rule
Get list of applied permanent balancing rules.

* **URI:**  `GET {dbaas_host}/api/v3/dbaas/balancing/rules/permanent`
* **Headers:**  
  Not required
* **Authorization:**
  - Basic type with credentials with `dbaas-db-editor` role. Specified as `DBAAS_DB_EDITOR_CREDENTIALS_USERNAME` and `DBAAS_DB_EDITOR_CREDENTIALS_PASSWORD`
    [deployment parameters](./installation/parameters.md#dbaas_db_editor_credentials_username-dbaas_db_editor_credentials_password).
  - Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
    [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type      | Name                          | Description                                    | Schema |
|-----------|-------------------------------|------------------------------------------------|--------|
| **Query** | **namespace**  <br>*optional* | Namespace for which the rules will be searched | string |

**Success Response:**

| HTTP Code | Description          | Schema                                                                  |
|-----------|----------------------|-------------------------------------------------------------------------|
| **200**   | Return founded rules | < [PermanentPerNamespaceRuleDTO](#permanentpernamespaceruledto) > array |

* **Error Response:**

| HTTP Code | Description     | Schema     |
|-----------|-----------------|------------|
| **400**   | Rules not found | No Content |

* **Sample call**

  Request:
    ```bash
    curl  'http://localhost:8080/api/v3/dbaas/balancing/rules/permanent' \
        --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8='
    ```
  Response:
    ```text
    OK 200
    ```
  Response body
  ```json
  [
        {
          "dbType":"postgresql",
          "physicalDatabaseId":"core-postgresql-1",
          "namespaces": [
            "test-namespace-3",
            "test-namespace-2"
          ]
        },
        {
          "dbType":"mongodb",
          "physicalDatabaseId":"core-mongodb",
          "namespaces": [
            "test-namespace-3",
            "test-namespace-4"
          ]
        }
      ]
  ```

### Delete permanent namespace balancing rule
Delete all permanent balancing rules on namespace.

* **URI:**  `DELETE {dbaas_host}/api/v3/dbaas/balancing/rules/permanent`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  - Basic type with credentials with `dbaas-db-editor` role. Specified as `DBAAS_DB_EDITOR_CREDENTIALS_USERNAME` and `DBAAS_DB_EDITOR_CREDENTIALS_PASSWORD`
    [deployment parameters](./installation/parameters.md#dbaas_db_editor_credentials_username-dbaas_db_editor_credentials_password).
  - Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
    [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                              | Description                                                                                                                          | Schema                                                                              |
|----------|-----------------------------------|--------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------|
| **Body** | **rulesToDelete**  <br>*required* | List of on namespace physDb balancing rules expected to be deleted. If DbType is specified, only rules for this type will be deleted | < [PermanentPerNamespaceRuleDeleteDTO](#permanentpernamespaceruledeletedto) > array |

**Success Response:**

| HTTP Code | Description   | Schema     |
|-----------|---------------|------------|
| **200**   | Rules deleted | No Content |

* **Error Response:**
  
* **Sample call**

  Request:
    ```bash
    curl  --request DELETE 'http://localhost:8080/api/v3/dbaas/balancing/rules/permanent' \
        --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' \
        --header 'Content-Type: application/json' \
        --data '[
          {
            "namespaces": [
              "test-namespace-4".
              "test-namespace-3"
            ]
          }
        ]'
    ```
  Response:
    ```text
    OK 200
    ```

### On microservice physDb balancing rule
Allows adding balancing rules for microservices.  Balancing rules are intended to define in which
physical database new logical database should be created. This API allows adding such rules for 
each microservice, or for group of microservices separately.  
Related article: https://perch.qubership.org/display/CLOUDCORE/On+Microservice+physical+DB+balancing+rule

> *WARNING! Rules can be overridden. Rule's integrity and validity is the responsibility of project(applies) side.*
> It means that rule doesn't merge and there can be only one version of rule. If you change configuration of previous rule and send it, then logical databases will be created by the new changed rule. Therefore, be careful before deleting or modifying the rule.


* **URI:**  `PUT {dbaas_host}/api/v3/dbaas/{namespace}/physical_databases/rules/onMicroservices`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                                          | Description                                                                                                                                                                 | Schema                                                            |
|----------|-----------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------|
| **Path** | **namespace**  <br>*required*                 | Namespace where the rule will be placed, each rule must have a namespace. Rules works only on logical databases created in the same namespace where they have been created. | string                                                            |
| **Body** | **onMicroserviceRuleRequest**  <br>*required* | onMicroserviceRuleRequest                                                                                                                                                   | < [OnMicroserviceRuleRequest](#onmicroservicerulerequest) > array |


* **Success Response:**

| HTTP Code | Description      | Schema                                                |
|-----------|------------------|-------------------------------------------------------|
| **200**   | OK               | < [PerMicroserviceRule](#permicroservicerule) > array |
| **201**   | New rule created | < [PerMicroserviceRule](#permicroservicerule) > array |

* **Error Response:**

| HTTP Code | Description                      | Schema     |
|-----------|----------------------------------|------------|
| **400**   | Received request with wrong body | No Content |

* **Sample call**

  Request:
    ```bash
    curl -X PUT \
     http://localhost:8080/api/v3/dbaas/test-namespace/physical_databases/rules/onMicroservices \
      -H "Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=" \
      -H 'Content-Type: application/json' \
      -d '[
        {
          "microservices": [
            "tenant-manager",
            "site-management"
          ],
          "rules": [
            {
              "label": "core-postgresql_nalancing_rules=core-postgresql"
            }
          ],
          "type": "postgresql"
        }
      ]'
    ```
  Response:
    ```text
    OK 200 
    or
    CREATED 201
    ```
  
  ```json
  [
    {
      "namespace": "test-db",
      "microservice": "tenant-manager",
      "rules": [
        {
          "label": "clusterName=patroni"
        }
      ],
      "type": "postgresql",
      "createDate": "2023-03-03T15:24:43.970+00:00",
      "updateDate": "2023-03-03T15:24:43.970+00:00",
      "generation": 0,
      "id": "5094a824-871c-4950-9521-234c49a740e5"
    },
    {
      "namespace": "test-db",
      "microservice": "site-management",
      "rules": [
        {
          "label": "clusterName=patroni"
        }
      ],
      "type": "postgresql",
      "createDate": "2023-03-03T15:24:43.970+00:00",
      "updateDate": "2023-03-03T15:24:43.970+00:00",
      "generation": 0,
      "id": "a6af7270-5f54-4f66-8ba1-9b642fed0f0e"
    }
  ]
  ```

### Validation for microservices' balancing rules
This API receives JSON-configs with rules for microservices and returns in response mapping label 
to physical db (whether all mentioned lables exist), indicates errors if any. Response also 
contains information about default physical databases for each db type.  
Related article: https://perch.qubership.org/display/CLOUDCORE/On+microservice+balancing+rule+validation

* **URI:**  `PUT {dbaas_host}/api/v3/dbaas/{namespace}/physical_databases/rules/onMicroservices/validation`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                                          | Description                                                                                                                                                                           | Schema                                                            |
|----------|-----------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------|
| **Path** | **namespace**  <br>*required*                 | Namespace where the rule is expected to be placed, each rule must have a namespace. Rules works only on logical databases created in the same namespace where they have been created. | string                                                            |
| **Body** | **onMicroserviceRuleRequest**  <br>*required* | List of rules on microservices to validate                                                                                                                                            | < [OnMicroserviceRuleRequest](#onmicroservicerulerequest) > array |


* **Success Response:**

| HTTP Code | Description     | Schema                                          |
|-----------|-----------------|-------------------------------------------------|
| **200**   | Schema is valid | [ValidateRulesResponse](#validaterulesresponse) |

* **Error Response:**

| HTTP Code | Description         | Schema     |
|-----------|---------------------|------------|
| **400**   | Schema is not valid | No Content |

* **Sample call**

  Request:
    ```bash
    curl  --request PUT 'http://localhost:8080/api/v3/dbaas/cloud-core-dev-1/physical_databases/rules/onMicroservices/validation' \
        --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' \
        --header 'Content-Type: application/json' \
        --data '[
          {
            "microservices": [
              "tenant-manager",
              "site-management"
            ],
            "rules": [
              {
                "label": "core-postgresql_nalancing_rules=core-postgresql"
              }
            ],
            "type": "postgresql"
          }
        ]'
    ```
  Response:
    ```text
    OK 200
    ```
  
  ```json
    {
      "mapLabelToPhysicalDb": {
        "clusterName=patroni": "core-postgresql"
      },
      "defaultPhysicalDatabases": {
        "postgresql": "core-postgresql",
        "elasticsearch": "core-elasticsearch",
        "opensearch": "core-opensearch",
        "cassandra": "core-cassandra",
        "arangodb": "core-arangodb",
        "mongodb": "core-mongodb",
        "redis": "core-redis"
      }
    }
  ```
  
### Debugging for microservice balancing rules
This API receives JSON-configs with rules and list of microservices to check and returns in response
a mapping what physical database is going to be assigned to each microservice from the request based
on the balancing rules from the request and the existing rules in DBaaS. Response will also contain
a list of labels for the assigned physical database to help analyze which rule was applied.

* **URI:**  `POST {dbaas_host}/api/v3/dbaas/{namespace}/physical_databases/rules/debug`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                                          | Description                                                                                                                                                                           | Schema                                                            |
|----------|-----------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------|
| **Path** | **namespace**  <br>*required*                 | Namespace where the rule is expected to be placed, each rule must have a namespace. Rules works only on logical databases created in the same namespace where they have been created. | string                                                            |
| **Body** | **rules**  <br>*required*                     | List of rules on microservices to debug                                                                                                                                               | < [OnMicroserviceRuleRequest](#onmicroservicerulerequest) > array |
| **Body** | **microservices**  <br>*required*             | List of microservices to debug                                                                                                                                                        | string array                                                      |


* **Success Response:**

| HTTP Code | Description     | Schema                                          |
|-----------|-----------------|-------------------------------------------------|
| **200**   | OK              | [DebugRulesResponse](#debugrulesresponse)       |

* **Error Response:**

| HTTP Code | Description         | Schema     |
|-----------|---------------------|------------|
| **500**   | Error happened when processing rules | No Content |

* **Sample call**

  Request:
    ```bash
    curl  --request POST 'http://localhost:8080/api/v3/dbaas/cloud-core-dev-1/physical_databases/rules/debug' \
        --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' \
        --header 'Content-Type: application/json' \
        --data '{
          "rules": [
            {
              "microservices": [
                "tenant-manager",
                "site-management"
              ],
              "rules": [
                {
                  "label": "core-postgresql_nalancing_rules=core-postgresql"
                }
              ],
              "type": "postgresql"
            }
          ],
          "microservices": ["tenant-manager", "site-management"]
        }'
    ```
  Response:
    ```text
    OK 200
    ```
  
  ```json
    {
      "tenant-manager": {
        "postgresql": {
          "labels": {
            "core-postgresql_nalancing_rules": "core-postgresql"
          },
          "physicalDbIdentifier": "core-postgresql",
          "appliedRuleInfo": "Microservice balancing rule was applied."
        }
      },
      "site-management": {
        "postgresql": {
          "labels": {
            "core-postgresql_nalancing_rules": "core-postgresql"
          },
          "physicalDbIdentifier": "core-postgresql",
          "appliedRuleInfo": "Microservice balancing rule was applied."
        }
      }
    }
  ```

### Debugging for microservice balancing rules

This API receives JSON-configs with rules and list of microservices to check and returns in response
a mapping what physical database is going to be assigned to each microservice from the request based
on the balancing rules from the request and the existing rules in DBaaS. Response will also contain
a list of labels for the assigned physical database to help analyze which rule was applied.

* **URI:**  `POST {dbaas_host}/api/v3/dbaas/{namespace}/physical_databases/rules/debug`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME`
  and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                              | Description                                                                                                                                                                           | Schema                                                            |
|----------|-----------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------|
| **Path** | **namespace**  <br>*required*     | Namespace where the rule is expected to be placed, each rule must have a namespace. Rules works only on logical databases created in the same namespace where they have been created. | string                                                            |
| **Body** | **rules**  <br>*required*         | List of rules on microservices to debug                                                                                                                                               | < [OnMicroserviceRuleRequest](#onmicroservicerulerequest) > array |
| **Body** | **microservices**  <br>*required* | List of microservices to debug                                                                                                                                                        | string array                                                      |

* **Success Response:**

| HTTP Code | Description | Schema                                    |
|-----------|-------------|-------------------------------------------|
| **200**   | OK          | [DebugRulesResponse](#debugrulesresponse) |

* **Error Response:**

| HTTP Code | Description                          | Schema     |
|-----------|--------------------------------------|------------|
| **500**   | Error happened when processing rules | No Content |

* **Sample call**

  Request:
    ```bash
    curl  --request POST 'http://localhost:8080/api/v3/dbaas/cloud-core-dev-1/physical_databases/rules/debug' \
        --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' \
        --header 'Content-Type: application/json' \
        --data '{
          "rules": [
            {
              "microservices": [
                "tenant-manager",
                "site-management"
              ],
              "rules": [
                {
                  "label": "core-postgresql_nalancing_rules=core-postgresql"
                }
              ],
              "type": "postgresql"
            }
          ],
          "microservices": ["tenant-manager", "site-management"]
        }'
    ```
  Response:
    ```text
    OK 200
    ```

  ```json
    {
      "tenant-manager": {
        "postgresql": {
          "labels": {
            "core-postgresql_nalancing_rules": "core-postgresql"
          },
          "physicalDbIdentifier": "core-postgresql",
          "appliedRuleInfo": "Microservice balancing rule was applied."
        }
      },
      "site-management": {
        "postgresql": {
          "labels": {
            "core-postgresql_nalancing_rules": "core-postgresql"
          },
          "physicalDbIdentifier": "core-postgresql",
          "appliedRuleInfo": "Microservice balancing rule was applied."
        }
      }
    }
  ```

### Change default physical database
Moves the 'global' flag to the specified existing physical database

* **URI:**  `PUT {dbaas_host}/api/v3/dbaas/{type}/physical_databases/{phydbid}/global`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                        | Description                                                                      | Schema |
|----------|-----------------------------|----------------------------------------------------------------------------------|--------|
| **Path** | **type**  <br>*required*    | Type of database. Example: MongoDB, PostgreSQL, elasticsearch, etc.              | string |
| **Path** | **phydbid**  <br>*required* | Physical database identifier. The value belongs to the specific database cluster | string |

* **Success Response:**

| HTTP Code | Description                        | Schema |
|-----------|------------------------------------|--------|
| **200**   | Updated existing physical database | string |

* **Error Response:**

| HTTP Code | Description                                     | Schema |
|-----------|-------------------------------------------------|--------|
| **404**   | Specified physical database does not registered | string |

* **Sample call**

  Request:
    ```bash
    curl --request PUT 'https://localhost:8080/api/v3/dbaas/postgresql/physical_databases/postgresql-core-tls/global' \
      --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' \
      --header 'Content-Type: application/json'
    ```
  Response:
    ```text
    OK 200
    ```

### Get on microservice physical database balancing rules
Get on microservice physical database balancing rules.

* **URI:**  `GET {dbaas_host}/api/v3/dbaas/{namespace}/physical_databases/rules/onMicroservices`
* **Headers:**  
  Not required
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type       | Name                          | Description                                                                                                                                                            | Schema |
|------------|-------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|
| **Path**   | **namespace**  <br>*required* | Namespace where the rule is placed, each rule must have a namespace. Rules works only on logical databases created in the same namespace where they have been created. | string |

**Success Response:**

| HTTP Code | Description        | Schema                                                 |
|-----------|--------------------|--------------------------------------------------------|
| **200**   | OK                 | < [PerMicroserviceRule](#permicroservicerule) > array  |

* **Error Response:**

| HTTP Code | Description                                                     | Schema  |
|-----------|-----------------------------------------------------------------|---------|
| **500**   | Unknown error which may be related with internal work of DbaaS. | string  |

* **Sample call**

  Request:
    ```bash
    curl --request GET 'http://localhost:8080/api/v3/dbaas/test-namespace/physical_databases/rules/onMicroservices' \
        --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8='
    ```
  Response:
    ```text
    OK 200
    ```
  Response body
  ```json
  [
      {
          "namespace": "test-namespace",
          "microservice": "dbaas-test-service",
          "rules": [
              {
                  "label": "clusterName=patroni"
              }
          ],
          "type": "postgresql",
          "createDate": "2024-10-28T10:22:29.829+00:00",
          "updateDate": "2024-10-28T10:22:29.829+00:00",
          "generation": 0,
          "id": "5ad8820b-e185-47c9-8274-2d2edfbfd880"
      }
  ]
  ```

## PhysicalDatabaseRegistration
Provides API to register new physical databases

### Register new physical database
Creates new physical database and returns path to it with physical database identifier.

* **URI:**  `PUT {dbaas_host}/api/v3/dbaas/{type}/physical_databases/{phydbid}`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                           | Description                                                                      | Schema                                                              |
|----------|--------------------------------|----------------------------------------------------------------------------------|---------------------------------------------------------------------|
| **Path** | **phydbid**  <br>*required*    | Physical database identifier. The value belongs to the specific database cluster | string                                                              |
| **Path** | **type**  <br>*required*       | Type of database. Example: MongoDB, PostgreSQL, elasticsearch, etc.              | string                                                              |
| **Body** | **parameters**  <br>*required* | Parameters for registering physical database.                                    | [PhysicalDatabaseRegistryRequest](#physicaldatabaseregistryrequest) |

* **Success Response:**

| HTTP Code | Description                                              | Schema |
|-----------|----------------------------------------------------------|--------|
| **200**   | Updated existing database                                | string |
| **201**   | Database created                                         | string |
| **202**   | Adapter should continue to create roles for new portions | string |

* **Error Response:**

| HTTP Code | Description                                                                                                                                                                 | Schema     |
|-----------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------|
| **400**   | Adapter already running                                                                                                                                                     | No Content |
| **409**   | Database could not be registered as physical database already exists with another adapter id or the same adapter already exists and it is used with other physical database | No Content |
| **502**   | Adapter is not available during handshake process                                                                                                                           | No Content |

* **Sample call**

  Request:
    ```bash
    curl --request PUT 'https://localhost:8080/api/v3/dbaas/postgresql/physical_databases/postgresql-core-tls' \
      --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' \
      --header 'Content-Type: application/json' \
      --data '{
        "adapterAddress":"https://dbaas-postgres-adapter.postgresql-core-tls:8080",
        "httpBasicCredentials":{
          "password":"dbaas-aggregator",
          "username":"dbaas-aggregator"
        },
        "lables":{
          "clusterName":"common"
        },
        "metadata":{
          "apiVersion":"v2",
          "supportedRoles":[
            "admin"
          ],
          "features":{
            "multiusers":false,
            "tls":true
          }
        },
        "status":"run"
      }'
    ```
  Response:
    ```text
    OK 200
    ```

### List registered physical databases
Returns the list of registered physical databases by database type. If parameter type is set as "all"
then all registered physical databases for all types will be shown.

* **URI:**  `GET {dbaas_host}/api/v3/dbaas/{type}/physical_databases`
* **Headers:**  
  Not required
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                     | Description                                                                                                                | Schema |
|----------|--------------------------|----------------------------------------------------------------------------------------------------------------------------|--------|
| **Path** | **type**  <br>*required* | Type of database, for example: MongoDB, PostgreSQL, elasticsearch, etc. or all - to list all registered physical databases | string |

* **Success Response:**

| HTTP Code | Description                                                | Schema                                                            |
|-----------|------------------------------------------------------------|-------------------------------------------------------------------|
| **200**   | Registered physical databases by specific type were found. | [RegisteredPhysicalDatabasesDTO](#registeredphysicaldatabasesdto) |

* **Error Response:**

| HTTP Code | Description                                                    | Schema     |
|-----------|----------------------------------------------------------------|------------|
| **404**   | Registered physical databases by specific type were not found. | No Content |

* **Sample call**

  Request:
    ```bash
    curl 'http://localhost:8080/api/v3/dbaas/all/physical_databases' \
        --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8='
    ```
  Response:
    ```text
    OK 200
    ```
  
  ```json
    {
    "identified": {
      "core-mongodb": {
        "supportedVersion": "v2",
        "supportedRoles": [
          "admin",
          "ro",
          "rw",
          "streaming"
        ],
        "type": "mongodb",
        "adapterId": "de979234-c21c-4dd4-ae2d-44c52f13d6e8",
        "adapterAddress": "http://dbaas-mongo-adapter.core-mongodb:8080",
        "global": true,
        "labels": {
          "clusterName": "mongodb"
        },
        "supports": {
          "settings": true,
          "backupRestore": true,
          "users": true,
          "describeDatabases": true
        }
      },
      "core-elasticsearch": {
        ...
      },
      "core-postgresql": {
       ...
      }
    }
  }
  ```

### Delete physical database
Deletes physical database by database type and physical database id

* **URI:**  `DELETE {dbaas_host}/api/v3/dbaas/{type}/physical_databases/{phydbid}`
* **Headers:**  
  Not required
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                        | Description                                                                      | Schema |
|----------|-----------------------------|----------------------------------------------------------------------------------|--------|
| **Path** | **phydbid**  <br>*required* | Physical database identifier. The value belongs to the specific database cluster | string |
| **Path** | **type**  <br>*required*    | Type of database, for example: MongoDB, PostgreSQL, elasticsearch, etc.          | string |

* **Success Response:**

| HTTP Code | Description                              | Schema |
|-----------|------------------------------------------|--------|
| **200**   | Successfully deleted physical databases. | object |

* **Error Response:**

| HTTP Code | Description                                                             | Schema     |
|-----------|-------------------------------------------------------------------------|------------|
| **404**   | Physical database with specific type and id was not found.              | No Content |
| **406**   | Database is marked as default or there are connected logical databases. | No Content |

* **Sample call**

  Request:
    ```bash
    curl --request DELETE 'http://localhost:8080/api/v3/dbaas/postgresql/physical_databases/postgresql-core' \
        --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8='
    ```
  Response:
    ```text
    OK 200
    ```

## AggregatedBackupAdministration
Allows to get a list of available backups, triggers backup collector and restores some specific backup. 
All backup management is per namespace.

### Get all backups of namespace
Lists all backups prepared for specified namespace

* **URI:**  `GET {dbaas_host}/api/v3/dbaas/{namespace}/backups`
* **Headers:**  
  Not required
* **Authorization:**
  Basic type with credentials with `backup-daemon` role. Specified as `BACKUP_DAEMON_DBAAS_ACCESS_USERNAME` and `BACKUP_DAEMON_DBAAS_ACCESS_PASSWORD`
  [deployment parameters](./installation/parameters.md#backup_daemon_dbaas_access_username-backup_daemon_dbaas_access_password).
* **Request body:**

| Type     | Name                          | Description | Schema |
|----------|-------------------------------|-------------|--------|
| **Path** | **namespace**  <br>*required* | namespace   | string |

* **Success Response:**

| HTTP Code | Description                    | Schema                                        |
|-----------|--------------------------------|-----------------------------------------------|
| **200**   | Successfully returned backups. | < [NamespaceBackup](#namespacebackup) > array |

* **Error Response:**
  - 
* **Sample call**

  Request:
    ```bash
    curl --request GET 'http://localhost:8080/api/v3/dbaas/test-database/backups' \
      --header 'Authorization: Basic YmFja3VwLWRhZW1vbjpoZmZfZTM0X0pmcnQ=' 
    ```
  Response:
    ```text
    OK 200
    ```
    
### Restore namespace
Restores database within the initial namespace which was used during backup or to another namespace

* **URI:**  `POST {dbaas_host}/api/v3/dbaas/{namespace}/backups/{backupId}/restorations` or `POST {dbaas_host}/api/v3/dbaas/{namespace}/backups/{backupId}/restore`
* **Headers:**  
  Not required
* **Authorization:**
  Basic type with credentials with `backup-daemon` role. Specified as `BACKUP_DAEMON_DBAAS_ACCESS_USERNAME` and `BACKUP_DAEMON_DBAAS_ACCESS_PASSWORD`
  [deployment parameters](./installation/parameters.md#backup_daemon_dbaas_access_username-backup_daemon_dbaas_access_password).
* **Request body:**

| Type      | Name                                | Description                                                                                                                                 | Schema        |
|-----------|-------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------|---------------|
| **Path**  | **backupId**  <br>*required*        | Backup identifier                                                                                                                           | string (uuid) |
| **Path**  | **namespace**  <br>*required*       | This parameter specifies namespace where backup was made                                                                                    | string        |
| **Query** | **targetNamespace**  <br>*optional* | This parameter specifies namespace for restoring to another project. This parameter is needed if backup and restore projects are different. | string        |

* **Success Response:**

| HTTP Code | Description                                                     | Schema |
|-----------|-----------------------------------------------------------------|--------|
| **200**   | Backup successfully restored                                    | object |
| **202**   | Namespace restoration started, return backup id to track status | object |

* **Error Response:**

| HTTP Code | Description                        | Schema     |
|-----------|------------------------------------|------------|
| **400**   | Selected backup cannot be restored | No Content |
| **404**   | Selected backup not found          | No Content |

* **Sample call**

  Request:
    ```bash
    curl --request POST 'http://localhost:8080/api/v3/dbaas/test-namespace/backups/backup-id-123/restorations' \
      --header 'Authorization: Basic YmFja3VwLWRhZW1vbjpoZmZfZTM0X0pmcnQ=' 
    ```
  Response:
    ```text
    OK 200
    ```

### Validate backup
Validates backup of the specified namespace

* **URI:**  `GET {dbaas_host}/api/v3/dbaas/{namespace}/backups/{backupId}/validate`
* **Headers:**  
  Not required
* **Authorization:**
  Basic type with credentials with `backup-daemon` role. Specified as `BACKUP_DAEMON_DBAAS_ACCESS_USERNAME` and `BACKUP_DAEMON_DBAAS_ACCESS_PASSWORD`
  [deployment parameters](./installation/parameters.md#backup_daemon_dbaas_access_username-backup_daemon_dbaas_access_password).
* **Request body:**

| Type     | Name                          | Description                     | Schema        |
|----------|-------------------------------|---------------------------------|---------------|
| **Path** | **backupId**  <br>*required*  | Identifier of backup            | string (uuid) |
| **Path** | **namespace**  <br>*required* | Namespace where backup was made | string        |

* **Success Response:**

| HTTP Code | Description                             | Schema |
|-----------|-----------------------------------------|--------|
| **200**   | Backup is validated and can be restored | object |

* **Error Response:**

| HTTP Code | Description                                                                                | Schema     |
|-----------|--------------------------------------------------------------------------------------------|------------|
| **404**   | Selected backup not found                                                                  | No Content |
| **500**   | Selected backup cannot be restored, probably it had been removed or failed to be collected | No Content |

* **Sample call**

  Request:
    ```bash
    curl --request GET 'http://localhost:8080/api/v3/dbaas/dbaas-autotests/backups/00175878-a80f-43e4-990c-2036df499297/validate' \
      --header 'Authorization: Basic YmFja3VwLWRhZW1vbjpoZmZfZTM0X0pmcnQ=' 
    ```
  Response:
    ```text
    OK 200
    ```

### Get restoration info
Returns restoration info

* **URI:**  `GET {dbaas_host}/api/v3/dbaas/{namespace}/backups/{backupId}/restorations/{restorationId}`
* **Headers:**  
  Not required
* **Authorization:**
  Basic type with credentials with `backup-daemon` role. Specified as `BACKUP_DAEMON_DBAAS_ACCESS_USERNAME` and `BACKUP_DAEMON_DBAAS_ACCESS_PASSWORD`
  [deployment parameters](./installation/parameters.md#backup_daemon_dbaas_access_username-backup_daemon_dbaas_access_password).
* **Request body:**

| Type     | Name                              | Description                     | Schema        |
|----------|-----------------------------------|---------------------------------|---------------|
| **Path** | **backupId**  <br>*required*      | Identifier of backup process    | string (uuid) |
| **Path** | **namespace**  <br>*required*     | Namespace where backup was made | string        |
| **Path** | **restorationId**  <br>*required* | Identifier of restore process   | string (uuid) |

* **Success Response:**

| HTTP Code | Description                                 | Schema                              |
|-----------|---------------------------------------------|-------------------------------------|
| **200**   | Restoration info was successfully collected | [NamespaceBackup](#namespacebackup) |

* **Error Response:**

| HTTP Code | Description                              | Schema     |
|-----------|------------------------------------------|------------|
| **404**   | Selected backup or restoration not found | No Content |

* **Sample call**

  Request:
    ```bash
    curl --request GET 'http://localhost:8080/api/v3/dbaas/backups/00175878-a80f-43e4-990c-2036df499297/restorations/00878571-a80f-43e4-990c-792994fd6301' \
      --header 'Authorization: Basic YmFja3VwLWRhZW1vbjpoZmZfZTM0X0pmcnQ=' 
    ```
  Response:
    ```text
    OK 200
    ```

### Get backup info
Returns restoration info

* **URI:**  `GET {dbaas_host}/api/v3/dbaas/{namespace}/backups/{backupId}`
* **Headers:**  
  Not required
* **Authorization:**
  Basic type with credentials with `backup-daemon` role. Specified as `BACKUP_DAEMON_DBAAS_ACCESS_USERNAME` and `BACKUP_DAEMON_DBAAS_ACCESS_PASSWORD`
  [deployment parameters](./installation/parameters.md#backup_daemon_dbaas_access_username-backup_daemon_dbaas_access_password).
* **Request body:**

| Type     | Name                          | Description                     | Schema        |
|----------|-------------------------------|---------------------------------|---------------|
| **Path** | **backupId**  <br>*required*  | Identifier of backup process    | string (uuid) |
| **Path** | **namespace**  <br>*required* | Namespace where backup was made | string        |

* **Success Response:**

| HTTP Code | Description                            | Schema                              |
|-----------|----------------------------------------|-------------------------------------|
| **200**   | Backup info was successfully collected | [NamespaceBackup](#namespacebackup) |

* **Error Response:**

| HTTP Code | Description                              | Schema     |
|-----------|------------------------------------------|------------|
| **404**   | Selected backup or restoration not found | No Content |

* **Sample call**

  Request:
    ```bash
    curl --request GET 'http://localhost:8080/api/v3/dbaas/dbaas-autotests/backups/00175878-a80f-43e4-990c-2036df499297' \
      --header 'Authorization: Basic YmFja3VwLWRhZW1vbjpoZmZfZTM0X0pmcnQ=' 
    ```
  Response:
    ```text
    OK 200
    ```

### Add new backup info
Adds new backup info to specific backup id

* **URI:**  `PUT {dbaas_host}/api/v3/dbaas/{namespace}/backups/{backupId}`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `backup-daemon` role. Specified as `BACKUP_DAEMON_DBAAS_ACCESS_USERNAME` and `BACKUP_DAEMON_DBAAS_ACCESS_PASSWORD`
  [deployment parameters](./installation/parameters.md#backup_daemon_dbaas_access_username-backup_daemon_dbaas_access_password).
* **Request body:**

| Type     | Name                          | Description                              | Schema                                    |
|----------|-------------------------------|------------------------------------------|-------------------------------------------|
| **Path** | **backupId**  <br>*required*  | Identifier of backup process             | string (uuid)                             |
| **Path** | **namespace**  <br>*required* | Namespace of databases in backup time    | string                                    |
| **Body** | **backupDTO**  <br>*optional* | The object for saving backup information | [NamespaceBackupDTO](#namespacebackupdto) |

* **Success Response:**

| HTTP Code | Description                        | Schema |
|-----------|------------------------------------|--------|
| **200**   | Information was added successfully | object |

* **Error Response:**

| HTTP Code | Description                                            | Schema     |
|-----------|--------------------------------------------------------|------------|
| **403**   | Backup namespace and specified namespace are different | No Content |

* **Sample call**

  Request:
    ```bash
    curl --request PUT 'http://localhost:8080/api/v3/dbaas/dbaas-autotest/backups/00175878-a80f-43e4-990c-2036df499297' \
      --header 'Authorization: Basic YmFja3VwLWRhZW1vbjpoZmZfZTM0X0pmcnQ=' \
      --header 'Content-Type: application/json' \
      --data '{
        "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
        "status": "ARCHIVE"
      }
      '
    ```
  Response:
    ```text
    OK 200
    ```

### Backup namespace
Start backup collection process for specified namespace

* **URI:**  `POST {dbaas_host}/api/v3/dbaas/{namespace}/backups/collect`
* **Headers:**  
  Not Required
* **Authorization:**
  Basic type with credentials with `backup-daemon` role. Specified as `BACKUP_DAEMON_DBAAS_ACCESS_USERNAME` and `BACKUP_DAEMON_DBAAS_ACCESS_PASSWORD`
  [deployment parameters](./installation/parameters.md#backup_daemon_dbaas_access_username-backup_daemon_dbaas_access_password).
* **Request body:**

| Type      | Name                                             | Description                                                                                               | Schema  | Default   |
|-----------|--------------------------------------------------|-----------------------------------------------------------------------------------------------------------|---------|-----------|
| **Path**  | **namespace**  <br>*required*                    | Namespace of the database needs to be saved                                                               | string  | -         |
| **Query** | **allowEviction**  <br>*optional*                | Allows to disable eviction on adapters for current backup                                                 | boolean | `"true"`  |
| **Query** | **ignoreNotBackupableDatabases**  <br>*optional* | The parameter enables(by default)/disables validating of DBaaS adapters on the supported backup procedure | boolean | `"false"` |

* **Success Response:**

| HTTP Code | Description                                                             | Schema                              |
|-----------|-------------------------------------------------------------------------|-------------------------------------|
| **200**   | OK                                                                      | object                              |
| **201**   | Backup successfully collected                                           | [NamespaceBackup](#namespacebackup) |
| **202**   | Backup of namespace databases started, return backup id to track status | string (uuid)                       |

* **Error Response:**

| HTTP Code | Description                                          | Schema |
|-----------|------------------------------------------------------|--------|
| **501**   | Some backup adapters do not support backup operation | string |

* **Sample call**

  Request:
    ```bash
    curl  --request POST 'http://localhost:8080/api/v3/dbaas/backup-namespace/backups/collect' \
        --header 'Authorization: Basic YmFja3VwLWRhZW1vbjpoZmZfZTM0X0pmcnQ=' 
    ```
  Response:
    ```text
    OK 200
    ```
  
### Delete backup
Start backup collection process for specified namespace

* **URI:**  `DELETE {dbaas_host}/api/v3/dbaas/{namespace}/backups/{backupId}`
* **Headers:**  
  Not Required
* **Authorization:**
  Basic type with credentials with `backup-daemon` role. Specified as `BACKUP_DAEMON_DBAAS_ACCESS_USERNAME` and `BACKUP_DAEMON_DBAAS_ACCESS_PASSWORD`
  [deployment parameters](./installation/parameters.md#backup_daemon_dbaas_access_username-backup_daemon_dbaas_access_password).
* **Request body:**

| Type     | Name                          | Description                              | Schema        |
|----------|-------------------------------|------------------------------------------|---------------|
| **Path** | **backupId**  <br>*required*  | Identifier of backup needs to be deleted | string (uuid) |
| **Path** | **namespace**  <br>*required* | Namespace of the database backup         | string        |

* **Success Response:**

| HTTP Code | Description                 | Schema                                              |
|-----------|-----------------------------|-----------------------------------------------------|
| **200**   | Backup successfully deleted | [NamespaceBackupDeletion](#namespacebackupdeletion) |

* **Error Response:**

| HTTP Code | Description                             | Schema |
|-----------|-----------------------------------------|--------|
| **403**   | Backup deletion is forbidden            | string |
| **404**   | Backup is not found                     | string |
| **500**   | The following backup can not be deleted | string |

* **Sample call**

  Request:
    ```bash
    curl --request DELETE 'http://localhost:8080/api/v3/dbaas/dbaas-autotests_source/backups/0b53eb7c-a0bb-419a-a81c-27bc3f716b2a' \
        --header 'Authorization: Basic YmFja3VwLWRhZW1vbjpoZmZfZTM0X0pmcnQ=' 
    ```
  Response:
    ```text
    OK 200
    ```
  
  ```json
  {
  "deleteResults":[
    {
    "databasesBackup":{
      "id":"7bf248cb-061a-491c-8e9a-7b9c2f2294e4",
      "status":"SUCCESS",
      "adapterId":"de979234-c21c-4dd4-ae2d-44c52f13d6e8",
      "databases":[
        "dbaas_autotests-fdac3788-3dac-47a3-b7b4-5bf2b76bc813",
        "dbaas_autotests-786c2d3e-0f47-40e8-b6c7-91254d72b492"
      ]
    },
    "status":"SUCCESS",
    "adapterId":"de979234-c21c-4dd4-ae2d-44c52f13d6e8",
    "message":"\"Backup 20230210T093931 successfully removed\""
    }
  ],
  "failReasons":[],
  "status":"SUCCESS"
  }
  ```

## AggregatedBackupRestore
Allows to get list of available backups, trigger backup collector and restore some specific backup. 
All backup management is per namespace.

### Bulk restore database
Start backup collection process for specified namespace

* **URI:**  `POST {dbaas_host}/api/v3/dbaas/backups/{backupId}/restorations` or `POST {dbaas_host}/api/v3/dbaas/backups/{backupId}/restore`
* **Headers:**  
  Not Required
* **Authorization:**
  Basic type with credentials with `backup-daemon` role. Specified as `BACKUP_DAEMON_DBAAS_ACCESS_USERNAME` and `BACKUP_DAEMON_DBAAS_ACCESS_PASSWORD`
  [deployment parameters](./installation/parameters.md#backup_daemon_dbaas_access_username-backup_daemon_dbaas_access_password).
* **Request body:**

| Type      | Name                                | Description                                                                                                                                        | Schema        |
|-----------|-------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|---------------|
| **Path**  | **backupId**  <br>*required*        | Identifier of backup process                                                                                                                       | string (uuid) |
| **Query** | **targetNamespace**  <br>*optional* | This parameter specifies namespace for restoring to another project. This parameter is needed to use if backup and restore projects are different. | string        |

* **Success Response:**

| HTTP Code | Description                                                     | Schema |
|-----------|-----------------------------------------------------------------|--------|
| **200**   | Backup successfully restored                                    | object |
| **202**   | Namespace restoration started, return backup id to track status | object |

* **Error Response:**

| HTTP Code | Description                        | Schema     |
|-----------|------------------------------------|------------|
| **400**   | Selected backup cannot be restored | No Content |
| **404**   | Selected backup not found          | No Content |

* **Sample call**

  Request:
    ```bash
    curl --request POST 'http://localhost:8080/api/v3/dbaas/backups/0b53eb7c-a0bb-419a-a81c-27bc3f716b2a/restorations' \
        --header 'Authorization: Basic YmFja3VwLWRhZW1vbjpoZmZfZTM0X0pmcnQ=' 
    ```
  Response:
    ```text
    OK 200
    ```
  
### Bulk get restoration info
Returns restoration info

* **URI:**  `GET {dbaas_host}/api/v3/dbaas/backups/{backupId}/restorations/{restorationId}`
* **Headers:**  
  Not Required
* **Authorization:**
  Basic type with credentials with `backup-daemon` role. Specified as `BACKUP_DAEMON_DBAAS_ACCESS_USERNAME` and `BACKUP_DAEMON_DBAAS_ACCESS_PASSWORD`
  [deployment parameters](./installation/parameters.md#backup_daemon_dbaas_access_username-backup_daemon_dbaas_access_password).
* **Request body:**

| Type     | Name                              | Description                   | Schema        |
|----------|-----------------------------------|-------------------------------|---------------|
| **Path** | **backupId**  <br>*required*      | Identifier of backup process  | string (uuid) |
| **Path** | **restorationId**  <br>*required* | Identifier of restore process | string (uuid) |

* **Success Response:**

| HTTP Code | Description                          | Schema                              |
|-----------|--------------------------------------|-------------------------------------|
| **200**   | Backup validated and can be restored | [NamespaceBackup](#namespacebackup) |

* **Error Response:**

| HTTP Code | Description                              | Schema     |
|-----------|------------------------------------------|------------|
| **404**   | Selected backup or restoration not found | No Content |

* **Sample call**

  Request:
    ```bash
    curl --request GET 'http://localhost:8080/api/v3/dbaas/backups/0b53eb7c-a0bb-419a-a81c-27bc3f716b2a/restorations/0888888-restore-id' \
        --header 'Authorization: Basic YmFja3VwLWRhZW1vbjpoZmZfZTM0X0pmcnQ=' 
    ```
  Response:
    ```text
    OK 200
    ```

## Migration
Provides API to migrate: database registration from another source, database passwords to external system.

### Register databases
This API allows you to register the database in DBaaS. The registered database would not be created, 
and it is assumed that it already exists in the cluster The purpose for this API is to register 
databases which were not created through DBaaS. If registered database already exist in DBaaS then 
it will not be added.  
Related article:[https://perch.qubership.org/display/CLOUDCORE/Register+logical+database+as+internal](https://perch.qubership.org/display/CLOUDCORE/Register+logical+database+as+internal#Registerlogicaldatabaseasinternal-Withoutusercreation)

* **URI:**  `PUT {dbaas_host}/api/v3/dbaas/migration/databases`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `migration-client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                                    | Description         | Schema                                                        |
|----------|-----------------------------------------|---------------------|---------------------------------------------------------------|
| **Body** | **databasesToRegister**  <br>*required* | databasesToRegister | < [RegisterDatabaseRequest](#registerdatabaserequest) > array |

* **Success Response:**

| HTTP Code | Description         | Schema |
|-----------|---------------------|--------|
| **200**   | Migration completed | string |

* **Error Response:**

| HTTP Code | Description                     | Schema |
|-----------|---------------------------------|--------|
| **500**   | Internal error during migration | string |

* **Sample call**

  Request:
    ```bash
    curl --request PUT 'http://localhost:8080/api/v3/dbaas/migration/databases' \
        --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' \
        --header 'Content-Type: application/json' \
        --data '[
            {
              "adapterId": "uuid-adapter-id",
              "backupDisabled": true,
              "classifier":{
                  "microserviceName":"test-service",
                  "scope":"service", 
                  "namespace":"test-namespace"
               },
              "connectionProperties": [
                {
                    "password":"test-pwd",
                    "role":"admin",
                    "port":5432,
                    "host":"pg-patroni.core-postgresql",
                    "name":"dbaas_db_name",
                    "url":"jdbc:postgresql://pg-patroni.core-postgresql:5432/dbaas_db_name",
                    "username":"dbaas_user_name"
                }
              ],
              "name": "dbaas_db_name",
              "namespace": "test-namespace",
              "physicalDatabaseId": "core-postgresql",
              "resources": [
                {
                  "kind": "user",
                  "name": "pg_admin_user"
                }
              ],
              "type": "postgresql"
            }
          ]'
    ```
  Response:
    ```text
    OK 200
    ```

### Register databases with user creation
This API allows you to register the database in DBaaS with user creation. It can be useful when
username and password is unknown and you want you database to dbaas registry. Previous users will not
be unbind, so in point of security you should unbind user them by yourself.The registered database
would not be created, and it is assumed that it already exists in the cluster The purpose for this
API is to register databases which were not created through DBaaS. If registered database already
exist in DBaaS then it will not be added.  

Since 3.22 this API supports two features:
- physical database id autodiscovery based on dbHost parameter
- the ability to register an external logical database as an internal. Please note that in this case 
database's field `physicalDBId` will be either taken from request or resolved automatically, and value for field
`backupDisabled` will be taken from request.

Related article:[https://perch.qubership.org/display/CLOUDCORE/Register+logical+database+as+internal](https://perch.qubership.org/display/CLOUDCORE/Register+logical+database+as+internal#Registerlogicaldatabaseasinternal-Withusercreation)

* **URI:**  `PUT {dbaas_host}/api/v3/dbaas/migration/databases/with-user-creation`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `migration-client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                                    | Description         | Schema                                                                                        |
|----------|-----------------------------------------|---------------------|-----------------------------------------------------------------------------------------------|
| **Body** | **databasesToRegister**  <br>*required* | databasesToRegister | < [RegisterDatabaseWithUserCreationRequest](#registerdatabasewithusercreationrequest) > array |

* **Success Response:**

| HTTP Code | Description                                                                                    | Schema                              |
|-----------|------------------------------------------------------------------------------------------------|-------------------------------------|
| **200**   | Migration completed. Response is a map where key is database type and value is MigrationResult | [MigrationResult](#migrationresult) |

* **Error Response:**

| HTTP Code | Description                                                                                                                   | Schema                              |
|-----------|-------------------------------------------------------------------------------------------------------------------------------|-------------------------------------|
| **409**   | There are some conflicts detected during migration. Response is a map where key is database type and value is MigrationResult | [MigrationResult](#migrationresult) |
| **500**   | Internal error during migration. Response is a map where key is database type and value is MigrationResult                    | [MigrationResult](#migrationresult) |

* **Sample call**

  Request:
    ```bash
    curl --request PUT 'http://localhost:8080/api/v3/dbaas/migration/databases/with-user-creation' \
        --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' \
        --header 'Content-Type: application/json' \
        --data '[
          {
            "classifier":{
                  "microserviceName":"test-service",
                  "scope":"service", 
                  "namespace":"test-namespace"
            },
            "name": "dbaas_dn_name",
            // one of parameters physicalDatabaseId or dbHost should present
            "physicalDatabaseId": "core-postgresql",
            "dbHost": "pg-patroni.core-postgresql"
            "type": "postgresql"
          }
        ]'
    ```
  Response:
    ```text
    OK 200
    ```

## Database users

Allows to get or create specific user for database, rotate user password and delete user.

### Get or create user

The API allows to get or create specific user for database.

* **URI:**  `PUT {dbaas_host}/api/v3/dbaas/users`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME`
  and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                                       | Description                                       | Schema                                            |
|----------|--------------------------------------------|---------------------------------------------------|---------------------------------------------------|
| **Body** | **GetOrCreateUserRequest**  <br>*required* | Parameters for getting or creating specific user. | [GetOrCreateUserRequest](#getorcreateuserrequest) |

* **Success Response:**

| HTTP Code | Description             | Schema                                              |
|-----------|-------------------------|-----------------------------------------------------|
| **200**   | User is already created | [GetOrCreateUserResponse](#getorcreateuserresponse) |
| **201**   | New user is created     | [GetOrCreateUserResponse](#getorcreateuserresponse) |
| **202**   | Operation in progress   | string                                              |

* **Error Response:**

| HTTP Code | Description                     | Schema |
|-----------|---------------------------------|--------|
| **404**   | Requested database is not found | String |

* **Sample call**

  Request:
    ```bash
    curl --request PUT 'http://localhost:8080/api/v3/dbaas/users' \
        --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' \
        --header 'Content-Type: application/json' \
        --data '{
            "classifier":{
                  "microserviceName":"test-service",
                  "scope":"service", 
                  "namespace":"test-namespace"
            }, 
            "logicalUserId": "some-service",
            "type": "postgresql",
            "role": "admin"          
          }'
    ```
  Response:
    ```text
    OK 200 
    or 
    CREATED 201
    ```
  Response body:
    ```json
    {
        "userId": "475ebd72-1409-4a06-8bd8-e408f21e3819",
        "connectionProperties": {
            "host": "pg-patroni.core-postgresql",
            "name": "dbaas_c4245fb2989c48b38ded815c61a95e82",
            "password": "126ccad16e05400fa03be2a3e1461e87",
            "port": 5432,
            "role": "admin",
            "url": "jdbc:postgresql://pg-patroni.core-postgresql:5432/dbaas_c4245fb2989c48b38ded815c61a95e82",
            "username": "dbaas_e54c65c92d464c2f885078d03354ade6",
            "logicalUserId": "some-service"
        }
    }
    ```

### Delete user

The API allows to delete specific user for database.

* **URI:**  `DELETE {dbaas_host}/api/v3/dbaas/users`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME`
  and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                                     | Description                   | Schema                                        |
|----------|------------------------------------------|-------------------------------|-----------------------------------------------|
| **Body** | **UserOperationRequest**  <br>*required* | Parameters for user deletion. | [UserOperationRequest](#useroperationrequest) |

* **Success Response:**

| HTTP Code | Description                  | Schema |
|-----------|------------------------------|--------|
| **200**   | User is successfully deleted | string |
| **204**   | User is not found            | string |

* **Error Response:**

| HTTP Code | Description | Schema |
|-----------|-------------|--------|
| **404**   | Error       | String |

* **Sample call**

  Request:
    ```bash
    curl --request DELETE 'http://localhost:8080/api/v3/dbaas/users' \
        --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' \
        --header 'Content-Type: application/json' \
        --data '{
            "userId": "e54c65c92d464c2f885078d03354ade6"          
          }'
    ```
  Response:
    ```text
    OK 200 
    or 
    NO CONTENT 204
    ```

### Rotate user password

The API allows to rotate password for specific user.

* **URI:**  `POST {dbaas_host}/api/v3/dbaas/users/rotate-password`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME`
  and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                                     | Description                   | Schema                                        |
|----------|------------------------------------------|-------------------------------|-----------------------------------------------|
| **Body** | **UserOperationRequest**  <br>*required* | Parameters for user deletion. | [UserOperationRequest](#useroperationrequest) |

* **Success Response:**

| HTTP Code | Description                     | Schema                                        |
|-----------|---------------------------------|-----------------------------------------------|
| **200**   | Password was successfully reset | [ConnectionProperties](#connectionproperties) |

* **Error Response:**

| HTTP Code | Description | Schema |
|-----------|-------------|--------|
| **404**   | Error       | String |

* **Sample call**

  Request:
    ```bash
    curl --request POST 'http://localhost:8080/api/v3/dbaas/users/rotate-password' \
        --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' \
        --header 'Content-Type: application/json' \
        --data '{
            "userId": "e54c65c92d464c2f885078d03354ade6"          
          }'
    ```
  Response:
    ```text
    OK 200
    ```
  ```json
  {
    "connectionProperties":{
      "host":"pg-patroni.core-postgresql",
      "name":"dbaas_c4245fb2989c48b38ded815c61a95e82",
      "password":"126ccad16e05400fa03be2a3e1461e87",
      "port":5432,
      "role":"admin",
      "url":"jdbc:postgresql://pg-patroni.core-postgresql:5432/dbaas_c4245fb2989c48b38ded815c61a95e82",
      "username":"dbaas_e54c65c92d464c2f885078d03354ade6",
      "logicalUserId":"some-service"
    }
  }
  ```  

### Restore users

The API allows to restore used for one database or for databases in namespace

* **URI:**  `POST {dbaas_host}/api/v3/dbaas/users/restore`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME`
  and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                                    | Description                   | Schema                                      |
|----------|-----------------------------------------|-------------------------------|---------------------------------------------|
| **Body** | **RestoreUsersRequest**  <br>*required* | Parameters for users restore. | [RestoreUsersRequest](#restoreusersrequest) |

* **Success Response:**

| HTTP Code | Description                     | Schema              |
|-----------|---------------------------------|---------------------|
| **200**   | Users was successfully restored | [Message](#message) |

* **Error Response:**

| HTTP Code | Description                                                           | Schema                                        |
|-----------|-----------------------------------------------------------------------|-----------------------------------------------|
| **40x**   | client specific, validation errors                                    | String                                        |
| **500**   | Error during restore or adapter does not support user restore feature | [RestoreUsersResponse](#restoreusersresponse) |

* **Sample call**

  Request:
    ```bash
    curl --request POST 'http://localhost:8080/api/v3/dbaas/users/restore' \
        --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' \
        --header 'Content-Type: application/json' \
        --data '{
            "classifier":{
                  "microserviceName":"test-service",
                  "scope":"service", 
                  "namespace":"test-namespace"
            }, 
            "type": "postgresql"                
          }'
    ```
  Response:
    ```text
    OK 200
    ```
  ```json
  {
    "message": "all users were restored"
  }
  ```  

Response:
```text
InternalServerError 500
```
  ```json
  
  {
    "unsuccessfully": [
      {
        "connectionProperties": {
          "host": "pg-patroni.core-postgresql",
          "name": "dbaas_c4245fb2989c48b38ded815c61a95e82",
          "password": "126ccad16e05400fa03be2a3e1461e87",
          "port": 5432,
          "role": "admin",
          "url": "jdbc:postgresql://pg-patroni.core-postgresql:5432/dbaas_c4245fb2989c48b38ded815c61a95e82",
          "username": "dbaas_e54c65c92d464c2f885078d03354ade6"
        },
        "errorMessage": "Error during restore users"
      }
    ]
  }
  
  ```  


## Access grants

### Get access grants

The API allows to get actual access grants of microservice databases.

* **URI:**  `GET /api/v3/dbaas/namespaces/{namespace}/services/{serviceName}/access-grants`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME`
  and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                            | Description                                                                         | Schema |
|----------|---------------------------------|-------------------------------------------------------------------------------------|--------|
| **Path** | **namespace**  <br>*required*   | Namespace                                                                           | string |
| **Path** | **serviceName**  <br>*required* | the name of the microservice for which the list of access grants is being requested | String |

* **Success Response:**

| HTTP Code | Description                            | Schema                                        |
|-----------|----------------------------------------|-----------------------------------------------|
| **200**   | Access grants was successfully founded | [AccessGrantsResponse](#accessgrantsresponse) |

* **Error Response:**

| HTTP Code | Description                                                              | Schema |
|-----------|--------------------------------------------------------------------------|--------|
| **404**   | Access grants for requested namespace and microservice name is not found | String |

* **Sample call**

  Request:
    ```bash
    curl --request PUT 'http://localhost:8080/api/v3/dbaas/namespaces/{namespace}/services/{serviceName}/access-grants' \
        --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' \
    ```
  Response:
    ```text
    OK 200
    ```
  Response body:
    ```json
  {
    "services": [
      {
        "name": "rw-service",
        "roles": ["rw"]
      },
      {
        "name": "ro-service",
        "roles": ["ro"]
      }
    ],
    "policies": [
      {
        "type": "postgresql",
        "defaultRole": "admin",
        "additionalRole": ["ro", "rw"]
      }
    ],
    "disableGlobalPermissions": false
  }
    ```

## Blue-Green operations

### Get orphan databases

The API allows to get list of databases with ORPHAN status. In non-PROD mode such databases are deleted automatically. 

* **URI:**  `GET /api/bluegreen/v1/operation/orphans`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME`
  and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                           | Description                                        | Schema       |
|----------|--------------------------------|----------------------------------------------------|--------------|
| **Body** | **namespaces**  <br>*required* | List of namespaces for collecting orphan databases | List<String> |

* **Success Response:**

| HTTP Code | Description       | Schema                                                       |
|-----------|-------------------|--------------------------------------------------------------|
| **200**   | List of databases | List < [OrphanDatabasesResponse](#orphandatabasesresponse) > |

* **Error Response:**

| HTTP Code | Description               | Schema |
|-----------|---------------------------|--------|
| **400**   | Bad request               | String |
| **500**   | Internal processing error | String |

* **Sample call**

  Request:
    ```bash
    curl --location --request GET 'http://localhost:8080/api/bluegreen/v1/operation/orphans' \
                --header 'Content-Type: application/json' \
                --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' \
                --data '{
                    "namespaces": [
                        "sosh-ns-1",
                        "sosh-ns-2"
                    ]
                  }'
    ```
  Response:
    ```text
    OK 200
    ```
  Response body:
    ```json
    [
      {
          "name": "dbaas_e68bf9e03fc443cd852494ed811acbda",
          "classifier": {
              "MARKED_FOR_DROP": "MARKED_FOR_DROP",
              "customKeys": {
                  "logicalDbName": "configs"
              },
              "microserviceName": "dbaas-spring-service",
              "namespace": "sosh-ns-2",
              "scope": "service"
          },
          "type": "postgresql",
          "namespace": "sosh-ns-2",
          "dbCreationTime": "2023-12-12T09:44:48.591+00:00",
          "physicalDbId": "core-postgresql",
          "bgVersion": "v4"
      },
      {
          "name": "dbaas_88c3df2f31954b0d88420e37b9885340",
          "classifier": {
              "MARKED_FOR_DROP": "MARKED_FOR_DROP",
              "microserviceName": "test-service",
              "namespace": "sosh-ns-2",
              "scope": "service"
          },
          "type": "postgresql",
          "namespace": "sosh-ns-2",
          "dbCreationTime": "2023-12-12T09:46:50.991+00:00",
          "physicalDbId": "core-postgresql",
          "bgVersion": null
      }
  ]
    ```

### Delete orphan databases

The API allows to delete Orphan databases. This API will drop databases even when dbaas is in PROD mode, 
so use it carefully. If delete=true then real deletion will be performed. Otherwise, API will just collect
list of databases ready for deletion.

* **URI:**  `DELETE /api/bluegreen/v1/operation/orphans`
* **Headers:**  
  `Content-Type: application/json`
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME`
  and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                        | Description                             | Schema                                        |
|----------|-----------------------------|-----------------------------------------|-----------------------------------------------|
| **Body** | **request**  <br>*required* | Body with extra parameters for deletion | [DeleteOrphansRequest](#DeleteOrphansRequest) |

* **Success Response:**

| HTTP Code | Description       | Schema                                                       |
|-----------|-------------------|--------------------------------------------------------------|
| **200**   | List of databases | List < [OrphanDatabasesResponse](#orphandatabasesresponse) > |

* **Error Response:**

| HTTP Code | Description               | Schema |
|-----------|---------------------------|--------|
| **400**   | Bad request               | String |
| **500**   | Internal processing error | String |

* **Sample call**

  Request:
    ```bash
    curl --location --request DELETE 'http://localhost:8080/api/bluegreen/v1/operation/orphans' \
        --header 'Content-Type: application/json' \
        --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' \
        --data '{
            "namespaces": [
                "sosh-ns-1",
                "sosh-ns-2"
            ],
            "delete": true
          }'
    ```
  Response:
    ```text
    OK 200
    ```
  Response body:
    ```json
    [
      {
          "name": "dbaas_e68bf9e03fc443cd852494ed811acbda",
          "classifier": {
              "MARKED_FOR_DROP": "MARKED_FOR_DROP",
              "customKeys": {
                  "logicalDbName": "configs"
              },
              "microserviceName": "dbaas-spring-service",
              "namespace": "sosh-ns-2",
              "scope": "service"
          },
          "type": "postgresql",
          "namespace": "sosh-ns-2",
          "dbCreationTime": "2023-12-12T09:44:48.591+00:00",
          "physicalDbId": "core-postgresql",
          "bgVersion": "v4"
      },
      {
          "name": "dbaas_88c3df2f31954b0d88420e37b9885340",
          "classifier": {
              "MARKED_FOR_DROP": "MARKED_FOR_DROP",
              "microserviceName": "test-service",
              "namespace": "sosh-ns-2",
              "scope": "service"
          },
          "type": "postgresql",
          "namespace": "sosh-ns-2",
          "dbCreationTime": "2023-12-12T09:46:50.991+00:00",
          "physicalDbId": "core-postgresql",
          "bgVersion": null
      }
  ]
    ```

## Declarative operations

### Get extended process status info

The API allows to get an extended info about process status for troubleshooting purposes.

* **URI:**  `GET /api/declarations/v1/operation/{trackingId}/extendedTroubleshootingInfo`
* **Headers:**  
  Not Required
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME`
  and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                           | Description           | Schema        |
|----------|--------------------------------|-----------------------|---------------|
| **Path** | **trackingId**  <br>*required* | Identifier of process | string (uuid) |

* **Success Response:**

| HTTP Code | Description                  | Schema                                                              |
|-----------|------------------------------|---------------------------------------------------------------------|
| **200**   | Extended process status info | [OperationStatusExtendedResponse](#OperationStatusExtendedResponse) |

* **Error Response:**

| HTTP Code | Description         | Schema     |
|-----------|---------------------|------------|
| **404**   | Operation not found | No Content |

* **Sample call**

  Request:
    ```bash
    curl --location --request GET 'http://localhost:8080/api/declarations/v1/operation/{trackingId}/extendedTroubleshootingInfo' \
        --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8='
    ```
  Response:
    ```text
    OK 200
    ```
  Response body:
    ```json
  {
      "status": "WAITING_FOR_RESOURCES",
      "message": "0 of 2 tasks are processed",
      "operationDetails": {
          "tasks": [
              {
                  "taskId": "d8c5de95-d835-4501-9c68-3ecddf0dd138",
                  "taskName": "backup_task:5c96a503-c69c-4671-a9ae-23345763f1f6",
                  "state": {
                      "status": "WAITING_FOR_RESOURCES",
                      "description": "Waiting for source DB with classifier {dbaas_auto_test=toClone, microserviceName=service3, namespace=dbaas-autotests, scope=service}"
                  },
                  "classifier": {
                      "dbaas_auto_test": "clone3",
                      "microserviceName": "service3",
                      "namespace": "dbaas-autotests",
                      "scope": "service"
                  },
                  "type": "postgresql",
                  "backupId": "a65d88ef-ee28-49ae-b66b-1cb99bed3eea",
                  "restoreId": "f58a5814-7759-4d48-aced-76d6fc1ced46"
              },
              {
                  "taskId": "9655a2b3-9d1c-4c1e-b490-03dbe95708c0",
                  "taskName": "restore_task:5c96a503-c69c-4671-a9ae-23345763f1f6",
                  "state": {
                      "status": "NOT_STARTED",
                      "description": "Not started"
                  },
                  "classifier": {
                      "dbaas_auto_test": "clone3",
                      "microserviceName": "service3",
                      "namespace": "dbaas-autotests",
                      "scope": "service"
                  },
                  "type": "postgresql",
                  "backupId": "a65d88ef-ee28-49ae-b66b-1cb99bed3eea",
                  "restoreId": "f58a5814-7759-4d48-aced-76d6fc1ced46"
              }
          ]
      }
  }

  ```

## Composite Structure

### Save or Update Composite Structure

Save or update composite structure in DBaaS.

* **URI:**  `POST /api/composite/v1/structures`
* **Headers:**  
  Not Required
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME`
  and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type     | Name                        | Description                                     | Schema                                    |
|----------|-----------------------------|-------------------------------------------------|-------------------------------------------|
| **Body** | **request**  <br>*required* | Request body that describes composite structure | [CompositeStructure](#CompositeStructure) |

* **Success Response:**

| HTTP Code | Description                                       | Schema |
|-----------|---------------------------------------------------|--------|
| **204**   | Composite structure successfully saved or updated | None   |

* **Error Response:**

| HTTP Code | Description                                                                                      | Schema                                |
|-----------|--------------------------------------------------------------------------------------------------|---------------------------------------|
| **400**   | Validation error. See reason in response message                                                 | [TmfErrorResponse](#TmfErrorResponse) |
| **409**   | Conflict error. Namespace in request body is already associated with another composite structure | [TmfErrorResponse](#TmfErrorResponse) |
| **500**   | Internal error                                                                                   | [TmfErrorResponse](#TmfErrorResponse) |

* **Sample call**

  Request:
    ```bash
        curl GET 'http://localhost:8090/api/composite/v1/structures' \
            --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' \
            --data '
                {
                    "id": "ns-1",
                    "namespaces": ["ns-1","ns-2","ns-3"]
                }'
    ```
  Response:
    ```text
    No Content 204
    ```

### Get List Composite Structures

Get list of all registered composite structures in DBaaS.

* **URI:**  `GET /api/composite/v1/structures`
* **Headers:**  
  Not Required
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME`
  and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).

* **Success Response:**

| HTTP Code | Description                                 | Schema                                                              |
|-----------|---------------------------------------------|---------------------------------------------------------------------|
| **200**   | List of all registered composite structures | < [CompositeStructureResponse](#CompositeStructureResponse) > array |

* **Error Response:**

| HTTP Code | Description    | Schema                                |
|-----------|----------------|---------------------------------------|
| **500**   | Internal error | [TmfErrorResponse](#TmfErrorResponse) |

* **Sample call**

  Request:
    ```bash
        curl GET 'http://localhost:8090/api/composite/v1/structures' \
            --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8='
    ```
  Response:
    ```text
    OK 200
    [
      {
        "id": "ns-1",
        "namespaces": [
            "ns-1",
            "ns-2",
            "ns-3"
        ]
      },
      {
        "id": "base-namespace",
        "namespaces": [
            "satellite-2",
            "satellite-1",
            "base-namespace"
        ]
      }
    ]
    ```

### Get Composite Structure by Id

Get composite structure by id

* **URI:**  `GET /api/composite/v1/structures/{id}`
* **Headers:**  
  Not Required
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME`
  and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).

* **Request body:**

| Type     | Name                   | Description                               | Schema |
|----------|------------------------|-------------------------------------------|--------|
| **PATH** | **id**  <br>*required* | Composite id with which it was registered | String |

* **Success Response:**

| HTTP Code | Description                                | Schema                                                    |
|-----------|--------------------------------------------|-----------------------------------------------------------|
| **200**   | composite structure with given id is found | [CompositeStructureResponse](#CompositeStructureResponse) |

* **Error Response:**

| HTTP Code | Description                                 | Schema                                |
|-----------|---------------------------------------------|---------------------------------------|
| **404**   | composite structure with given id not found | [TmfErrorResponse](#TmfErrorResponse) |
| **500**   | Internal error                              | [TmfErrorResponse](#TmfErrorResponse) |

* **Sample call**

  Request:
    ```bash
        curl GET 'http://localhost:8090/api/composite/v1/structures/ns-1' \
            --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8='
    ```
  Response:
    ```text
    OK 200
      {
        "id": "ns-1",
        "namespaces": [
            "ns-1",
            "ns-2",
            "ns-3"
        ]
      }
    ```

### Delete Composite Structure registration by Id

This API removes registration of composite structure by Id in DBaaS

* **URI:**  `DELETE /api/composite/v1/structures/{id}/delete`
* **Headers:**  
  Not Required
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME`
  and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).

* **Request body:**

| Type     | Name                   | Description                               | Schema |
|----------|------------------------|-------------------------------------------|--------|
| **PATH** | **id**  <br>*required* | Composite id with which it was registered | String |

* **Success Response:**

| HTTP Code | Description                                            | Schema |
|-----------|--------------------------------------------------------|--------|
| **204**   | composite structure with given id is found and deleted | None   |

* **Error Response:**

| HTTP Code | Description                                 | Schema                                |
|-----------|---------------------------------------------|---------------------------------------|
| **404**   | composite structure with given id not found | [TmfErrorResponse](#TmfErrorResponse) |
| **500**   | Internal error                              | [TmfErrorResponse](#TmfErrorResponse) |

* **Sample call**

  Request:
    ```bash
        curl DELETE 'http://localhost:8080/api/composite/v1/structures/ns-1/delete' \
            --header 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8='
    ```
  Response:
    ```text
    OK 204
    ```

## Debug operations

### Get Dump of Dbaas Database Information
Retrieves a dump of DbaaS database information, including logical databases, physical databases, declarative configurations, BG domains and balancing rules.
By default, response body is returned as compressed zip file with json file inside.
However, it is possible to get response body in JSON format instead of file.

* **URI:**  `GET {dbaas_host}/api/v3/dbaas/debug/internal/dump`
* **Headers:**  
  `Accept: application/json`
  or
  `Accept: application/octet-stream`

> *WARNING! If 'Accept' header in request has 'application/json' value then response body is returned in JSON format.*
> *If 'Accept' header in request has 'application/octet-stream' value then response body is returned as compressed zip file with json file inside.*
> *If 'Accept' header is skipped in request then server handles request as it does for 'Accept' header with 'application/octet-stream' value.*
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**
  Not required
* **Success Response:**

| HTTP Code | Description                  | Schema |
|-----------|------------------------------|--------|
| **200**   | Successfully retrieved dump  |        |

* **Error Response:**

| HTTP Code | Description                            | Schema |
|-----------|----------------------------------------|--------|
| **500**   | An error occurred during getting dump  | String |

* **Sample call with 'Accept' header and value 'application/json'**

  Request:
    ```bash
    curl --location --request GET http://localhost:8080/api/v3/dbaas/debug/internal/dump \
      -H 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' \
      -H "Accept: application/json"
    ```
  Response:
    ```text
    OK 200
    ```

  Response headers:
  `Content-Type: application/json`

  Response body:
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

* **Sample call with 'Accept' header and value 'application/octet-stream'**

  Request:
    ```bash
    curl --location --request GET http://localhost:8080/api/v3/dbaas/debug/internal/dump \
      -H 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' \
      -H "Accept: application/octet-stream"
    ```
  Response:
    ```test
    OK 200
    ```

  Response headers:
  `Content-Disposition: attachment; filename="dbaas_dump.zip"`
  `Content-Type: application/octet-stream`

  Response body:
  `zip file`

### Get lost databases

Returns the list of lost databases (databases that registered in DBaaS, but not exists in adapter).

* **URI:**  `GET {dbaas_host}/api/v3/dbaas/debug/internal/lost`
* **Headers:**  
  not required
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME`
  and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**
  No
* **Success Response:**

| HTTP Code | Description                 | Schema                                                    |
|-----------|-----------------------------|-----------------------------------------------------------|
| **200**   | List of databases with name | < [LostDatabasesResponse](#lostdatabasesresponse) > array |

* **Error Response:**

| HTTP Code | Description    | Schema     |
|-----------|----------------|------------|
| **500**   | Internal error | No Content |

* **Sample call**

  Request:
    ```bash
    curl -X GET \
      http://localhost:8080/api/v3/dbaas/debug/internal/lost \
      -H 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' 
    ```
  Response:
    ```text
    OK 200
    ```

  Response body:

```json
  [
  {
    "physicalDatabaseId": "cassandra-dev",
    "errorMessage": null,
    "databases": [
      {
        "id": "25227bf5-7ad4-4ce8-ad76-a46a53048f0c",
        "classifier": {
          "microserviceName": "control-plane",
          "namespace": "cloud-core-dev-1",
          "scope": "service"
        },
        "namespace": "cloud-core-dev-1",
        "type": "postgresql",
        "name": "dbaas_e905eef3c79841e09c4e916c2cc2bb14",
        "externallyManageable": false,
        "timeDbCreation": "2023-01-10T08:17:33.725+00:00",
        "settings": null,
        "backupDisabled": false,
        "physicalDatabaseId": "core-postgresql",
        "connectionProperties": [
          {
            "role": "admin",
            "port": 5432,
            "host": "pg-patroni.core-postgresql",
            "name": "dbaas_e905eef3c79841e09c4e916c2cc2bb14",
            "url": "jdbc:postgresql://pg-patroni.core-postgresql:5432/dbaas_e905eef3c79841e09c4e916c2cc2bb14",
            "username": "dbaas_ba08dfdefcc74c5a94f8e509f5cd0c10",
            "encryptedPassword": "{v2c}{AES}{DEFAULT_KEY}{aNvlsnC4jaWuxQbvsPLAMyamBnx70TpE9/BEnFgxBUElBmaDigwXwS9tCEI9UG5i}"
          },
          {
            "role": "rw",
            "port": 5432,
            "host": "pg-patroni.core-postgresql",
            "name": "dbaas_e905eef3c79841e09c4e916c2cc2bb14",
            "url": "jdbc:postgresql://pg-patroni.core-postgresql:5432/dbaas_e905eef3c79841e09c4e916c2cc2bb14",
            "username": "dbaas_536e52f474944b4d932e455ceb8a0872",
            "encryptedPassword": "{v2c}{AES}{DEFAULT_KEY}{Sh8vWxGCPgKMH1Ac6NNnmAmG9qF2d9K+8jrWYdwOMMUlBmaDigwXwS9tCEI9UG5i}"
          },
          {
            "role": "ro",
            "port": 5432,
            "host": "pg-patroni.core-postgresql",
            "name": "dbaas_e905eef3c79841e09c4e916c2cc2bb14",
            "url": "jdbc:postgresql://pg-patroni.core-postgresql:5432/dbaas_e905eef3c79841e09c4e916c2cc2bb14",
            "username": "dbaas_5b7d4f245bc24027890824f4e3c6482a",
            "encryptedPassword": "{v2c}{AES}{DEFAULT_KEY}{OjdB7jTeCP9Ij21oQg78vjSmncxthW/WU0EjybfvCF0lBmaDigwXwS9tCEI9UG5i}"
          }
        ]
        }
      ]
    }
  ]

```

### Get ghost databases

Returns the list of ghost databases (databases that exists in adapter, but not registered in DBaaS).

* **URI:**  `GET {dbaas_host}/api/v3/dbaas/debug/internal/ghost`
* **Headers:**  
  not required
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME`
  and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**
  No
* **Success Response:**

| HTTP Code | Description                 | Schema                                                      |
|-----------|-----------------------------|-------------------------------------------------------------|
| **200**   | List of databases with name | < [GhostDatabasesResponse](#ghostdatabasesresponse) > array |

* **Error Response:**

| HTTP Code | Description    | Schema     |
|-----------|----------------|------------|
| **500**   | Internal error | No Content |

* **Sample call**

  Request:
    ```bash
    curl -X GET \
      http://localhost:8080/api/v3/dbaas/debug/internal/ghost \
      -H 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' 
    ```
  Response:
    ```text
    OK 200
    ```

  Response body:

```json
[
  {
    "physicalDatabaseId": "redis-dev",
    "dbNames": [],
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

### Get overall status

Get DBaaS overall status. Status contains information about number of logical databases and health of adapters/DBaaS.

* **URI:**  `GET {dbaas_host}/api/v3/dbaas/debug/internal/info`
* **Headers:**  
  Not required
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME`
  and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).

* **Request body:**
  Not required
* **Success Response:**

| HTTP Code | Description                           | Schema                                          |
|-----------|---------------------------------------|-------------------------------------------------|
| **200**   | Successfully retrieved ovarall status | [OverallStatusResponse](#overallstatusresponse) |

* **Error Response:**

| HTTP Code | Description                                     | Schema |
|-----------|-------------------------------------------------|--------|
| **500**   | An error occurred during getting overall status | String |

* **Sample call**

  Request:
    ```bash
    curl --location --request GET http://localhost:8080/api/v3/dbaas/debug/internal/info \
      -H 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' 
    ```
  Response:
    ```text
    OK 200
    ```
  Response body:

```json
{
  "overallHealthStatus": "UP",
  "overallLogicalDbNumber": 59,
  "physicalDatabaseInfoList": [
    {
      "physicalDatabaseId": "core-postgresql",
      "healthStatus": "UP",
      "logicalDbNumber": "68"
    },
    {
      "physicalDatabaseId": "core-cassandra",
      "healthStatus": "UP",
      "logicalDbNumber": "11"
    },
    {
      "physicalDatabaseId": "core-clickhouse:clickhouse",
      "healthStatus": "UP",
      "logicalDbNumber": "5"
    }
  ]
}
```

### Find Debug Logical Databases
Retrieves Logical Database instances in near-tabular form.
Operation supports filters in 'filter' query parameter in style of RESTful Service Query Language (RSQL).

* **URI:**  `GET {dbaas_host}/api/v3/dbaas/debug/internal/databases`
  * **Headers:**  
    Not required
* **Authorization:**
  Basic type with credentials with `dba_client` role. Specified as `DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME` and `DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD`
  [deployment parameters](./installation/parameters.md#dbaas_cluster_dba_credentials_username-dbaas_cluster_dba_credentials_password).
* **Request body:**

| Type      | Name                       | Description                                                                                                                                                   | Schema        |
|-----------|----------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------|
| **Query** | **filter**  <br>*optional* | This parameter specifies custom RESTful Service Query Language (RSQL) query to apply filtering. <br>More details here: https://github.com/jirutka/rsql-parser | string        |

* **Supported selectors (filtering parameters) and their RSQL operators for 'filter' query parameter**

| Selector                                | RSQL Operations     | Description                                                                                                                                                                                                                                         | Schema           | Example                                                                 |
|-----------------------------------------|---------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------|-------------------------------------------------------------------------|
| **namespace** <br>*optional*            | **==**, **!=**      | Filtering by 'namespace' field of 'classifier' one belonging to specific Logical Database                                                                                                                                                           | string           | namespace==dbaas-autotests                                              |
| **microservice** <br>*optional*         | **==**, **!=**      | Filtering by 'microserviceName' field of 'classifier' one belonging to specific Logical Database                                                                                                                                                    | string           | microservice!=dbaas-declarative-service                                 |
| **tenantId** <br>*optional*             | **==**, **!=**      | Filtering by 'tenantId' field of 'classifier' belonging to specific Logical Database                                                                                                                                                                | string           | tenantId==ce22b065-1e61-4076-99b1-e397b6da741b                          |
| **logicalDbName** <br>*optional*        | **==**, **!=**      | Filtering by 'logicalDBName' field (or by 'logicalDbName' if previous is absent or by 'logicalDbId' if previous is absent) of 'custom_keys' field (or of 'customKeys' if previous is absent) of 'classifier' belonging to specific Logical Database | string           | logicalDbName!=configs                                                  |
| **bgVersion** <br>*optional*            | **==**, **!=**      | Filtering by 'bgVersion' field belonging to specific Logical Database                                                                                                                                                                               | string           | bgVersion==2                                                            |
| **type** <br>*optional*                 | **==**, **!=**      | Filtering by 'type' field belonging to specific Logical Database                                                                                                                                                                                    | string           | type!=clickhouse                                                        |
| **roles** <br>*optional*                | **=in=**, **=out=** | Filtering by 'role' field of 'connectionProperties' one belonging to specific Logical Database                                                                                                                                                      | array of strings | roles=in=("ro","rw")                                                    |
| **name** <br>*optional*                 | **==**, **!=**      | Filtering by 'name' field belonging to specific Logical Database                                                                                                                                                                                    | string           | name==dbaas-test-service_dbaas-autotests_175112571071124                |
| **physicalDbId** <br>*optional*          | **==**, **!=**      | Filtering by 'physicalDatabaseId' field belonging to specific Logical Database                                                                                                                                                                      | string           | physicalDbId==postgresql-dev:postgres                                   |
| **physicalDbAdapterUrl** <br>*optional* | **==**, **!=**      | Filtering by 'address' field of Physical Database which specific Logical Database belongs to                                                                                                                                                        | string           | physicalDbAdapterUrl!=http://dbaas-postgres-adapter.postgresql-dev:8080 |

* **Success Response:**

| HTTP Code | Description                                            | Schema |
|-----------|--------------------------------------------------------|--------|
| **200**   | Successfully retrieved list of debug logical databases |        |

* **Error Response:**

| HTTP Code | Description                                      | Schema |
|-----------|--------------------------------------------------|--------|
| **400**   | Incorrect RSQL query in 'filter' query parameter | String |
| **500**   | An error occurred during getting response        | String | 

* **Sample call**

  Request:
    ```bash
    curl --location --request GET http://localhost:8080/api/v3/dbaas/debug/internal/databases?filter=namespace==dbaas-autotests; \
            microservice==dbaas-declarative-service; \
            logicalDbName==configs; \
            type!=clickhouse; \
            roles=in=("ro","rw"); \
            physicalDbId==postgresql-dev:postgres; \
            physicalDbAdapterUrl==http://dbaas-postgres-adapter.postgresql-dev:8080 \
      -H 'Authorization: Basic Y2x1c3Rlci1kYmE6Qm5tcTU1NjdfUE8=' \
      -H "Accept: application/json"
    ```
  Response:
    ```text
    OK 200
    ```

  Response body:
  ```json
    [
        {
            "namespace": "dbaas-autotests",
            "microservice": "dbaas-declarative-service",
            "tenantId": "ce22b065-1e61-4076-99b1-e397b6da741b",
            "logicalDbName": "configs",
            "bgVersion": "2",
            "type": "opensearch",
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

## DBaaS entities

### DatabaseCreateRequest

Request model for adding database to DBaaS

| Name                                   | Description                                                                                                                                                                                                                                                                                                                                                                                      | Schema  |
|----------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------|
| **classifier**  <br>*required*         | It is a part of composite unique key. For more info see [classifier](#classifier).                                                                                                                                                                                                                                                                                                               | object  |
| **originService**  <br>*required*      | Origin service is service which sends request                                                                                                                                                                                                                                                                                                                                                    | string  |
| **type**  <br>*required*               | Indicate the type of physical database in which you want to create a database. For example mongodb or postgresql                                                                                                                                                                                                                                                                                 | string  |
| **backupDisabled**  <br>*optional*     | This field indicates if backup is disabled or not. If true - database would not be backed up. The backupDisabled parameter can not be modified; it is installed only once during creating database request. Default is false                                                                                                                                                                     | boolean |
| **namePrefix**  <br>*optional*         | This is a prefix of the database name.                                                                                                                                                                                                                                                                                                                                                           | string  |
| **physicalDatabaseId**  <br>*optional* | Specifies the identificator of physical database where a logical database will be created. If it is not specified then logical database will be created in default physical database. For more convenient way of definig where to create logical database checl [Balancing Rules](#balancing-rules). You can get the list of all physical databases by "List registered physical databases" API. | string  |
| **settings**  <br>*optional*           | Additional settings for creating database. There is a possibility to update settings after database creation.                                                                                                                                                                                                                                                                                    | object  |
| **userRole**  <br>*optional*           | Indicates connection properties with which user role should be returned to a client. Default value is admin if parameter is not set in declarative configuration.                                                                                                                                                                                                                                | string  |

### DatabaseResponse

| Name                                     | Description                                                                                                                                                                                                           | Schema             |
|------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------|
| **classifier**  <br>*required*           | It is a part of composite unique key. For more info see [classifier](#classifier)                                                                                                                                     | object             |
| **name**  <br>*required*                 | Name of database. It may be generated or, if name was specified in the request, then it will be specified.                                                                                                            | string             |
| **namespace**  <br>*required*            | Namespace where database is placed.                                                                                                                                                                                   | string             |
| **type**  <br>*required*                 | Type of database, for example PostgreSQL or MongoDB                                                                                                                                                                   | string             |
| **connectionProperties**  <br>*required* | This is an information about connection to database. It contains such keys as url, username, password.                                                                                                                | object             |
| **backupDisabled**  <br>*optional*       | This field indicates if backup is disabled or not. If true, database would not be backed up. Example: false                                                                                                           | boolean            |
| **externallyManageable**  <br>*optional* | This parameter specifies if a control over the database is not carried out by the DbaaS adapter                                                                                                                       | boolean            |
| **id**  <br>*optional*                   | It is an internal unique identifier of the document in the database. This field might not be used when searching by classifier for security purpose. And it exists in the response when executing Create database API | string (uuid)      |
| **physicalDatabaseId**  <br>*require*    | Physical database identifier where the registered database should be located. If it is absent, adapter id may be used to identify the target physical database.                                                       | string             |
| **settings**  <br>*optional*             | Additional settings for creating a database.                                                                                                                                                                          | object             |
| **timeDbCreation**  <br>*require*        | Time of database creation.                                                                                                                                                                                            | string (date-time) |

### ClassifierWithRolesRequest

| Name                              | Description                                                                         | Schema |
|-----------------------------------|-------------------------------------------------------------------------------------|--------|
| **classifier**  <br>*required*    | It is a part of composite unique key. For more info see [classifier](#classifier)   | object |
| **originService**  <br>*required* | Origin service is service which sends request                                       | string |
| **userRole**  <br>*optional*      | Indicates connection properties with which user role should be returned to a client | string |

### DatabasesInfo

| Name                            | Schema                                                  |
|---------------------------------|---------------------------------------------------------|
| **global**  <br>*optional*      | [DatabasesInfoSegment](#databasesinfosegment)           |
| **perAdapters**  <br>*optional* | < [DatabasesInfoSegment](#databasesinfosegment) > array |

### DatabasesInfoSegment

| Name                                  | Schema                                                  |
|---------------------------------------|---------------------------------------------------------|
| **deletingDatabases**  <br>*optional* | < [DatabaseInfo](#databaseinfo) > array                 |
| **name**  <br>*optional*              | string                                                  |
| **registration**  <br>*optional*      | [DatabasesRegistrationInfo](#databasesregistrationinfo) |
| **totalDatabases**  <br>*optional*    | < [DatabaseInfo](#databaseinfo) > array                 |

### DatabaseInfo

| Name                     | Schema |
|--------------------------|--------|
| **name**  <br>*optional* | string |

### DatabasesRegistrationInfo

| Name                               | Schema                                  |
|------------------------------------|-----------------------------------------|
| **ghostDatabases**  <br>*optional* | < [DatabaseInfo](#databaseinfo) > array |
| **lostDatabases**  <br>*optional*  | < [DatabaseInfo](#databaseinfo) > array |
| **totalDatabases**  <br>*optional* | < [DatabaseInfo](#databaseinfo) > array |

### ExternalDatabaseRequest

| Name                                           | Description                                                                                                                                                                                                                                                    | Schema                          |
|------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------|
| **classifier**  <br>*required*                 | It is a part of composite unique key. For more info see [classifier](#classifier)                                                                                                                                                                              | < string, object > map          |
| **connectionProperties**  <br>*required*       | There is an information about connection to database. It contains such keys as url, username and password. Pay attention that connection properties must contain role value. Connection properties should contain info about all roles (if they are supported) | < map < string, object > > list |
| **dbName**  <br>*required*                     | Name of logical database.                                                                                                                                                                                                                                      | string                          |
| **type**  <br>*required*                       | Type of physical database.                                                                                                                                                                                                                                     | string                          |
| **updateConnectionProperties**  <br>*optional* | Is connection properties update required. False by default. If true, then old connection properties will be replaced by the new ones provided.                                                                                                                 | boolean                         |

### PasswordChangeRequest

| Name                           | Description                                                                       | Schema |
|--------------------------------|-----------------------------------------------------------------------------------|--------|
| **classifier**  <br>*optional* | It is a part of composite unique key. For more info see [classifier](#classifier) | object |
| **type**  <br>*required*       | Physical database type.                                                           | string |
| **userRole**  <br>*optional*   | Indicates for which user password should be changed                               | string |

### PasswordChangeResponse

| Name                        | Description                                                                                           | Schema                                        |
|-----------------------------|-------------------------------------------------------------------------------------------------------|-----------------------------------------------|
| **changed**  <br>*optional* | List containing "classifier:connection" information with which the password was changed successfully. | < [PasswordChanged](#passwordchanged) > array |
| **failed**  <br>*optional*  | List containing fail information.                                                                     | < [PasswordFailed](#passwordfailed) > array   |

### PasswordChanged

| Name                           | Description                                                                       | Schema |
|--------------------------------|-----------------------------------------------------------------------------------|--------|
| **classifier**  <br>*required* | It is a part of composite unique key. For more info see [classifier](#classifier) | object |
| **connection**  <br>*required* | New database connection.                                                          | object |

### PasswordFailed

| Name                           | Description                                                                       | Schema |
|--------------------------------|-----------------------------------------------------------------------------------|--------|
| **classifier**  <br>*required* | It is a part of composite unique key. For more info see [classifier](#classifier) | object |
| **message**  <br>*required*    | Error message.                                                                    | string |

### RecreateDatabaseRequest
Request model for recreate existing database. The database will have the same settings and classifier as original

| Name                                   | Description                                                                                                                                                                              | Schema |
|----------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|
| **classifier**  <br>*required*         | It is a part of composite unique key. For more info see [classifier](#classifier)                                                                                                        | object |
| **physicalDatabaseId**  <br>*required* | Specifies the identificator of physical database where a logical database will be recreated. You can get the list of all physical databases by "List registered physical databases" API. | string |
| **type**  <br>*required*               | The physical type of logical database. For example mongodb or postgresql                                                                                                                 | string |

### RecreateDatabaseResponse
Response model for recreate existing database API. The model contains successful and unsuccessful databases

| Name                               | Description                                                                                                                 | Schema                                  |
|------------------------------------|-----------------------------------------------------------------------------------------------------------------------------|-----------------------------------------|
| **successfully**  <br>*optional*   | The list contains successfully recreated databases.                                                                         | < [Recreated](#recreated) > array       |
| **unsuccessfully**  <br>*optional* | The list contains requests from which an error occurred during recreating. For these requests databases were not recreated. | < [NotRecreated](#notrecreated) > array |

### Recreated

| Name                           | Description                                                                                                                                                 | Schema                                |
|--------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------|
| **classifier**  <br>*required* | It is a part of composite unique key. For more info see [classifier](#classifier)                                                                           | object                                |
| **newDb**  <br>*required*      | A recreated logical database. This database has the same classifier as a original but connection properties are different (url, dbname, username, password) | [DatabaseResponse](#databaseresponse) |
| **type**  <br>*required*       | Requested physical type of logical database. For example mongodb or postgresql                                                                              | string                                |

### NotRecreated

| Name                           | Description                                                                       | Schema |
|--------------------------------|-----------------------------------------------------------------------------------|--------|
| **classifier**  <br>*required* | It is a part of composite unique key. For more info see [classifier](#classifier) | object |
| **error**  <br>*required*      | Contains a message of error that occurred during recreating                       | string |
| **type**  <br>*required*       | Requested physical type of logical database. For example mongodb or postgresql    | string |


### UpdateClassifierRequest
Contains primary or source ("from") classifier by which a database record will be found and changed to target classifier ("to")

| Name                               | Description                                                                     | Schema                 |
|------------------------------------|---------------------------------------------------------------------------------|------------------------|
| **clone**  <br>*optional*          | Whether to create copy of record database in dbaas.  <br>**Example** : `false`  | boolean                |
| **from**  <br>*required*           | Primary or source classifier.                                                   | < string, object > map |
| **fromV1orV2ToV3**  <br>*optional* | Whether this request is needed for update V1 or V2 classifier to V3 classifier. | boolean                |
| **to**  <br>*required*             | Target classifier.                                                              | < string, object > map |

### UpdateConnectionPropertiesRequest
Contains classifier by which a database record for updating connection properties will be found and new connection properties

| Name                                     | Description                                                                                                                                                                                                                                                                                                                                                                                | Schema                 |
|------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------|
| **classifier**  <br>*required*           | Database classifier.                                                                                                                                                                                                                                                                                                                                                                       | < string, object > map |
| **connectionProperties**  <br>*required* | New connection properties. Structure of connection properties for different db types may be found at https://perch.qubership.org/pages/viewpage.action?spaceKey=CLOUDCORE&title=DbaaS+Adapters. Should contain "role", because update procedure is done for one role in a time.                                                                                                            | <string, object> map   |
| **dbName**  <br>*optional*               | Name of database.                                                                                                                                                                                                                                                                                                                                                                          | string                 |
| **physicalDatabaseId**  <br>*optional*   | Specifies the new identification of physical database where a logical database is already located. You have to pass this parameter if your goal is to update and specify a new physical database.It is an optional parameter and if not specified then physical database will not change. FYI: You can get the list of all physical databases by "List registered physical databases" API. | string                 |
| **resources**  <br>*optional*            | The list of the resources which related to the logical database, for example: user, database.You should pass this parameter if you change username or database name. In order to update the list you should get an original list, change one use and pass. FYI: You can get the list of origin database resources by "List of all databases" API with "withResources" query parameter.     | object                 |

### LinkDatabasesRequest
Link databases request allows to link existing databases to different namespace

| Name                                | Description                                                                        | Schema           |
|-------------------------------------|------------------------------------------------------------------------------------|------------------|
| **serviceNames**  <br>*required*    | The list of microservice names whose databases will be linked to target namespace. | < string > array |
| **targetNamespace**  <br>*required* | Namespace, to which databases will be linked.                                      | string           |

### RuleRegistrationRequest
Rule registration request allows to add a new rule for the specific type of logical databases, which would be applied in specific order.

| Name                      | Description                                                                                                                                                                                                             | Schema                |
|---------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------|
| **order**  <br>*optional* | Inside namespace+type domain, order defines which rule would be used first. The lesser order takes precedence. The order is optional; if not specified then the maximum over namespace+type domain would be calculated. | integer (int64)       |
| **rule**  <br>*optional*  | Object with rules                                                                                                                                                                                                       | [RuleBody](#rulebody) |
| **type**  <br>*required*  | Type of database required. The rule would only work on logical databases of the specified type.                                                                                                                         | string                |

### RuleBody
Rule allows to define physical database for new logical databases

| Name                       | Description                                                                                                                                             | Schema                 |
|----------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------|
| **config**  <br>*required* | Configuration contains rule-specific information: <br> - perNamespace rule only expects phydbid (physical database identifier) to be specified in rule. | Map < String, Object > |
| **type**  <br>*required*   | Type of rule is required, it defines what logic would be used when rule is applying.                                                                    | enum (perNamespace)    |

### PerMicroserviceRule 
Rule allows to define in which physical database new logical databases should be created for stated microservices

| Name                            | Description                                                                        | Schema                                              |
|---------------------------------|------------------------------------------------------------------------------------|-----------------------------------------------------|
| **namespace** <br>*required*    | Namespace with which rules will be associated                                      | string                                              |
| **microservice** <br>*required* | Name of microservice for which database rules should be applied                    | string                                              |
| **rules** <br>*required*        | List of configured rules                                                           | < [RuleOnMicroservice](#ruleonmicroservice) > array |
| **type** <br>*required*         | Physical db type (ex. postgresql, mongodb, etc), for which rule should be applied. | string                                              |
| **createDate** <br>*optional*   | Time of rules creation                                                             | date                                                |
| **updateDate** <br>*optional*   | Time of rules update                                                               | date                                                |
| **generation** <br>*optional*   | Current rule generation                                                            | date                                                |

### RuleOnMicroservice

| Name  | Description                       | Schema |
|-------|-----------------------------------|--------|
| label | Label is used to mark physical DB | string |

### OnMicroserviceRuleRequest
Rule registration request allows to add a new rule for the specific type of logical databases, which would be applied in specific order.

| Name                              | Description                                                              | Schema                                              |
|-----------------------------------|--------------------------------------------------------------------------|-----------------------------------------------------|
| **microservices**  <br>*required* | List of microservice names to which the specified rule has a place to be | < string > array                                    |
| **rules**  <br>*optional*         | List of rules to microservices. Allows to define physical database       | < [RuleOnMicroservice](#ruleonmicroservice) > array |
| **type**  <br>*required*          | Type of physical database which logical base belongs to                  | string                                              |

### PermanentPerNamespaceRuleDTO
Rule allows to define in which physical database new logical databases should be created

| Name                                   | Description                                                                             | Schema           |
|----------------------------------------|-----------------------------------------------------------------------------------------|------------------|
| **dbType**  <br>*required*             | Physical db type (ex. postgresql, mongodb, etc), for which rule should be applied.      | string           |
| **namespaces**  <br>*required*         | Namespaces for which rules should be applied                                            | < string > array |
| **physicalDatabaseId**  <br>*required* | Identifier of physical database where newly created logical databases should be placed. | string           |

### PermanentPerNamespaceRuleDeleteDTO

| Name                           | Description                                                                                               | Schema           |
|--------------------------------|-----------------------------------------------------------------------------------------------------------|------------------|
| **dbType**  <br>*optional*     | Db type for which rules should be deleted. If omitted all rules for specified namespaces will be deleted. | string           |
| **namespaces**  <br>*required* | Namespaces for which rules should be deleted                                                              | < string > array |

### ValidateRulesResponse

| Name                         | Description                                                | Schema                 |
|------------------------------|------------------------------------------------------------|------------------------|
| **mapLabelToPhysicalDb**     | Map with pairs of label : corresponding physical database. | < string, string > map |
| **defaultPhysicalDatabases** | Default physical databases for each registered type.       | < string, string > map |

### DebugRulesResponse

| Name                         | Description                                                                       | Schema                                                                  |
|------------------------------|-----------------------------------------------------------------------------------|-------------------------------------------------------------------------|
| -                            | Map with pairs of microservice : map with pairs of dbType : DebugRulesDbTypeData  | < string, <string, [DebugRulesDbTypeData](#debugrulesdbtypedata)> > map |

### DebugRulesDbTypeData

| Name                     | Description                                                                          | Schema                 |
|--------------------------|--------------------------------------------------------------------------------------|------------------------|
| **labels**               | Map with pairs of label key : label value.                                           | < string, string > map |
| **physicalDbIdentifier** | Identifier of the physical database                                                  | string                 |
| **appliedRuleInfo**      | Information about what type of rule was applied to resolve database for microservice | string                 | 


### PhysicalDatabaseRegistryRequest
Request model for sending physical database registration to DBaaS

| Name                                     | Description                                                                                                   | Schema                                        |
|------------------------------------------|---------------------------------------------------------------------------------------------------------------|-----------------------------------------------|
| **adapterAddress**  <br>*required*       | Physical address of DbaaS adapter. The address is used for CRUD operation with logic databases.               | string                                        |
| **httpBasicCredentials**  <br>*required* | Basic authentication username and password for requests from DbaaS Aggregator to DbaaS adapter.               | [HttpBasicCredentials](#httpbasiccredentials) |
| **labels**  <br>*optional*               | Additional information about physical database. It may be a version of database cluster, any labels, and etc. | < string, string > map                        |
| **metadata**  <br>*required*             | Information about supported roles, adapter api version and features                                           | [Metadata](#metadata)                         |
| **status**  <br>*required*               | Adapter status: running or run                                                                                | string                                        |

### HttpBasicCredentials

| Name                         | Description                    | Schema |
|------------------------------|--------------------------------|--------|
| **password**  <br>*required* | Basic authentication password. | string |
| **username**  <br>*required* | Basic authentication username. | string |

### Metadata

| Name                               | Description                           | Schema                  |
|------------------------------------|---------------------------------------|-------------------------|
| **apiVersion**  <br>*required*     | Adapter API version                   | string                  |
| **features**  <br>*required*       | Prohibition or permission of features | < string, boolean > map |
| **supportedRoles**  <br>*required* | list of supported roles               | < string > array        |

### RegisteredPhysicalDatabasesDTO

| Name                           | Description                                                        | Schema                                                                                              |
|--------------------------------|--------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| **identified**  <br>*optional* | List of registered physical databases with with a known identifier | < string, [PhysicalDatabaseRegistrationResponseDTO](#physicaldatabaseregistrationresponsedto) > map |

### NamespaceBackup

| Name                             | Description                                                                                                         | Schema                                                                   |
|----------------------------------|---------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------|
| **backups**  <br>*optional*      | List of adapters with backup information.                                                                           | < [DatabasesBackup](#databasesbackup) > array                            |
| **created**  <br>*optional*      | Data of backup process creation.                                                                                    | string (date-time)                                                       |
| **databases**  <br>*required*    | List of backup databases.                                                                                           | < [DatabaseResponse](#databaseresponse) > array                          |
| **failReasons**  <br>*optional*  | List of errors that can occur during backup process.                                                                | < string > array                                                         |
| **id**  <br>*required*           | A unique identifier of the backup process. Backup process is associated with this id.                               | string (uuid)                                                            |
| **namespace**  <br>*required*    | This parameter specifies project namespace whose databases are needed to save.                                      | string                                                                   |
| **restorations**  <br>*optional* | The object stores a restoring information related to this backup.                                                   | < [NamespaceRestoration](#namespacerestoration) > array                  |
| **status**  <br>*optional*       | Status of backup process. This field may contain: FAIL, ACTIVE, PROCEEDING, RESTORING, INVALIDATED, DELETION_FAILED | enum (ACTIVE, DELETION_FAILED, FAIL, INVALIDATED, PROCEEDING, RESTORING) |

### DatabasesBackup

| Name                          | Description                                                               | Schema                           |
|-------------------------------|---------------------------------------------------------------------------|----------------------------------|
| **adapterId**  <br>*optional* | This field contains the adapter id.                                       | string                           |
| **databases**  <br>*optional* | List of databases' names                                                  | < string > array                 |
| **id**  <br>*optional*        || string (uuid)                                                             |
| **localId**  <br>*optional*   | Identifier of an adapter associated with backup process                   | string                           |
| **status**  <br>*optional*    | This field contains the status of backup process of the specific adapter. | enum (FAIL, PROCEEDING, SUCCESS) |
| **trackId**  <br>*optional*   | Identifier for polling process specific adapter.                          | string                           |
| **trackPath**  <br>*optional* | Priority path for polling process.                                        | string                           |

### NamespaceRestoration

| Name                               | Schema                                    |
|------------------------------------|-------------------------------------------|
| **failReasons**  <br>*optional*    | < string > array                          |
| **id**  <br>*optional*             | string (uuid)                             |
| **restoreResults**  <br>*optional* | < [RestoreResult](#restoreresult) > array |
| **status**  <br>*optional*         | enum (FAIL, PROCEEDING, SUCCESS)          |

### RestoreResult

| Name                                | Description                                                                                                                                                                                                                            | Schema                              |
|-------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------|
| **adapterId**  <br>*optional*       || string                                                                                                                                                                                                                                 |
| **changedNameDb**  <br>*optional*   | This associative array contain database names, where key: "old db name", value: "new db name". This array will not be empty if the targetNamespace parameter will be passed and it will be filled during restore to another namespace. | < string, string > map              |
| **databasesBackup**  <br>*optional* | This object contains information about restoring the specific adapter.                                                                                                                                                                 | [DatabasesBackup](#databasesbackup) |
| **id**  <br>*optional*              || string (uuid)                                                                                                                                                                                                                          |
| **status**  <br>*optional*          | The field contains the restore status of specific adapter.                                                                                                                                                                             | enum (FAIL, PROCEEDING, SUCCESS)    |

### NamespaceBackupDTO

| Name                             | Schema                                                                   |
|----------------------------------|--------------------------------------------------------------------------|
| **backups**  <br>*optional*      | < [DatabasesBackup](#databasesbackup) > array                            |
| **created**  <br>*optional*      | string (date-time)                                                       |
| **databases**  <br>*optional*    | < [BackupedDatabase](#backupeddatabase) > array                          |
| **failReasons**  <br>*optional*  | < string > array                                                         |
| **id**  <br>*optional*           | string (uuid)                                                            |
| **namespace**  <br>*optional*    | string                                                                   |
| **restorations**  <br>*optional* | < [NamespaceRestoration](#namespacerestoration) > array                  |
| **status**  <br>*optional*       | enum (ACTIVE, DELETION_FAILED, FAIL, INVALIDATED, PROCEEDING, RESTORING) |

### BackupedDatabase

This configuration is used for backups purposes

| Name                                      | Description                                                                                                                                                                                                                                               | Schema                                                |
|-------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------|
| **adapterId**  <br>*required*             | This field indicates for which adapter the database was created.                                                                                                                                                                                          | string                                                |
| **backupDisabled**  <br>*optional*        | This field indicates if backup is disabled or not. If true, database would not be backed up. Example: false                                                                                                                                               | boolean                                               |
| **classifier**  <br>*required*            | It is a part of composite unique key. For more info see [classifier](#classifier)                                                                                                                                                                         | object                                                |
| **connectionDescription**  <br>*optional* | This parameter describes connection properties.                                                                                                                                                                                                           | [ConnectionDescriptionReq](#connectiondescriptionreq) |
| **connectionProperties**  <br>*required*  | The information about connection to database. It contains such keys as url, authDbName, username, password, port, host.Setting keys depends on the database type.                                                                                         | object                                                |
| **dbOwnerRoles**  <br>*optional*          | The list of roles which are related to this logical database. The external security service (e.g. DBaaS Agent) can perform a verification process on this field.                                                                                          | < string > array                                      |
| **dbState**  <br>*optional*               || [DbStateReq](#dbstatereq)                                                                                                                                                                                                                                 |
| **externallyManageable**  <br>*optional*  | This parameter specifies if a control over the database is not carried out by the DbaaS adapter.                                                                                                                                                          | boolean                                               |
| **id**  <br>*optional*                    | A unique identifier of the document in the database. This field may not be used when searching by classifier for security purpose. In appears in response when Create database API is executed.                                                           | string (uuid)                                         |
| **markedForDrop**  <br>*optional*         | A marker indicating that the database will be deleted.                                                                                                                                                                                                    | boolean                                               |
| **name**  <br>*required*                  | Name of database. It may be generated or, if name was specified in a request then it will be specified.                                                                                                                                                   | string                                                |
| **namespace**  <br>*required*             | Namespace where database is placed                                                                                                                                                                                                                        | string                                                |
| **oldClassifier**  <br>*optional*         | Old classifier describes the purpose of the database and distinguishes this database from other database in the same namespase. It contains such keys as dbClassifier, isService, microserviceName, namespace. Setting keys depends on the database type. | object                                                |
| **physicalDatabaseId**  <br>*optional*    || string                                                                                                                                                                                                                                                    |
| **resources**  <br>*required*             | It lists resource which will be deleted when sending the request for delete a database                                                                                                                                                                    | object                                                |
| **settings**  <br>*optional*              | Additional settings for creating a database                                                                                                                                                                                                               | object                                                |
| **timeDbCreation**  <br>*optional*        | Time to create a database                                                                                                                                                                                                                                 | string (date-time)                                    |
| **type**  <br>*required*                  | Type of database, for example postgresql or mongodb                                                                                                                                                                                                       | string                                                |
| **warnings**  <br>*optional*              | Lists warning messages                                                                                                                                                                                                                                    | < string > array                                      |

### ConnectionDescriptionReq

| Name                       | Schema                                                      |
|----------------------------|-------------------------------------------------------------|
| **fields**  <br>*optional* | < string, [FieldDescriptionReq](#fielddescriptionreq) > map |

### FieldDescriptionReq

| Name                     | Schema          |
|--------------------------|-----------------|
| **type**  <br>*optional* | enum (PASSWORD) |

### DbStateReq

| Name                            | Schema                                                          |
|---------------------------------|-----------------------------------------------------------------|
| **description**  <br>*optional* | string                                                          |
| **id**  <br>*optional*          | string (uuid)                                                   |
| **state**  <br>*optional*       | enum (ARCHIVED, CREATED, DELETING, DELETING_FAILED, PROCESSING) |

### NamespaceBackupDeletion

| Name                              | Schema                                  |
|-----------------------------------|-----------------------------------------|
| **deleteResults**  <br>*optional* | < [DeleteResult](#deleteresult) > array |
| **failReasons**  <br>*optional*   | < string > array                        |
| **status**  <br>*optional*        | enum (FAIL, PROCEEDING, SUCCESS)        |

### DeleteResult

| Name                                | Schema                              |
|-------------------------------------|-------------------------------------|
| **adapterId**  <br>*optional*       | string                              |
| **databasesBackup**  <br>*optional* | [DatabasesBackup](#databasesbackup) |
| **message**  <br>*optional*         | string                              |
| **status**  <br>*optional*          | enum (FAIL, PROCEEDING, SUCCESS)    |

### RegisterDatabaseRequest
Request to add database to registration


| Name                                     | Description                                                                                                                                                                                  | Schema                              |
|------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------|
| **adapterId**  <br>*optional*            | Identifier of an adapter to work with database. If not specified then the default would be used.                                                                                             | string                              |
| **backupDisabled**  <br>*optional*       | This parameter specifies if the DbaaS should except this database from backup/restore procedure. The parameter cannot be modified and it is installed only once during registration request. | boolean                             |
| **classifier**  <br>*required*           | It is a part of composite unique key. For more info see [classifier](#classifier)                                                                                                            | < string, object > map              |
| **connectionProperties**  <br>*required* | Connection properties used to connect to database. It contains such keys as url, authDbName, username, password, port, host. Setting keys depends on the database type.                      | < < string, object > map > array    |
| **name**  <br>*required*                 | Name of database.                                                                                                                                                                            | string                              |
| **namespace**  <br>*required*            | Namespace where database is placed.                                                                                                                                                          | string                              |
| **physicalDatabaseId**  <br>*required*   | Physical database identifier where the registered database should be located. If it is absent, adapter id may be used to identify the target physical database.                              | string                              |
| **resources**  <br>*required*            | List of the resources needed to drop during database drop.                                                                                                                                   | < [DbResource](#dbresource) > array |
| **type**  <br>*required*                 | The type of database, for example postgresql or mongodb.                                                                                                                                     | string                              |

### DbResource

| Name                     | Description                                        | Schema |
|--------------------------|----------------------------------------------------|--------|
| **kind**  <br>*required* | The kind of resource. For example database or user | string |
| **name**  <br>*required* | Name of the resource.                              | string |

### RegisterDatabaseWithUserCreationRequest
Request to add database to registration

| Name                                   | Description                                                                                                                                                                                                            | Schema                 |
|----------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------|
| **backupDisabled**  <br>*optional*     | This parameter specifies if the DbaaS should except this database from backup/restore procedure. The parameter cannot be modified and it is installed only once during registration request. Default is false          | boolean                |
| **classifier**  <br>*required*         | It is a part of composite unique key. For more info see [classifier](#classifier)                                                                                                                                      | < string, object > map |
| **name**  <br>*required*               | Name of database.                                                                                                                                                                                                      | string                 |
| **physicalDatabaseId**  <br>*optional* | Physical database identifier where the registered database should be located. If it is absent, adapter id may be used to identify the target physical database. **Either physicalDatabaseId or dbHost should present** | string                 |
| **dbHost**  <br>*optional*             | Physical database host where the registered database is located. Must be in format: <service-name>.<namespace>, e.g.: pg-patroni.postgresql-core. **Either physicalDatabaseId or dbHost should present**               | string                 |
| **type**  <br>*required*               | The type of database, for example postgresql or mongodb.                                                                                                                                                               | string                 |
### MigrationResult

| Name                               | Schema                                                      |
|------------------------------------|-------------------------------------------------------------|
| **conflicted**  <br>*optional*     | < string > array                                            |
| **failed**  <br>*optional*         | < string > array                                            |
| **failureReasons**  <br>*optional* | < string > array                                            |
| **migrated**  <br>*optional*       | < string > array                                            |
| **migratedDbInfo**  <br>*optional* | < [DatabaseResponseListCP](#databaseresponselistcp) > array |

### DatabaseResponseListCP

| Name                                     | Description                                                                                                                                                                                           | Schema                       |
|------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------|
| **backupDisabled**  <br>*optional*       | This field indicates if backup is disabled or not. If true, database would not be backed up. Example: false                                                                                           | boolean                      |
| **classifier**  <br>*required*           | It is a part of composite unique key. For more info see [classifier](#classifier)                                                                                                                     | object                       |
| **connectionProperties**  <br>*required* | This is an information about connection to database. It contains such keys as url, username, password.                                                                                                | List< Map <string, object> > |
| **externallyManageable**  <br>*optional* | This parameter specifies if a control over the database is not carried out by the DbaaS adapter                                                                                                       | boolean                      |
| **id**  <br>*optional*                   | A unique identifier of the document in the database. This field might not be used when searching by classifier for security purpose. And it exists in the response when executing Create database API | string (uuid)                |
| **name**  <br>*required*                 | Name of database. It may be generated or, if name was specified in the request, then it will be specified.                                                                                            | string                       |
| **namespace**  <br>*required*            | Namespace where database is placed.                                                                                                                                                                   | string                       |
| **physicalDatabaseId**  <br>*optional*   | Physical database identifier where the registered database should be located. If it is absent, adapter id may be used to identify the target physical database.                                       | string                       |
| **resources**  <br>*optional*            | list of resources is necessary for bulk drop resources operation. Specified if you add query parameter "withResources" = true to request                                                              | List < object >              |
| **settings**  <br>*optional*             | Additional settings for creating a database.                                                                                                                                                          | object                       |
| **timeDbCreation**  <br>*optional*       | Time to create a database.                                                                                                                                                                            | string (date-time)           |
| **type**  <br>*required*                 | Type of database, for example PostgreSQL or MongoDB                                                                                                                                                   | string                       |

### ExternalDatabaseResponse

| Name                                     | Description                                                                                                                                                                                           | Schema                       |
|------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------|
| **classifier**  <br>*required*           | It is a part of composite unique key. For more info see [classifier](#classifier)                                                                                                                     | object                       |
| **connectionProperties**  <br>*required* | This is an information about connection to database. It contains such keys as url, username, password.                                                                                                | List< Map <string, object> > |
| **externallyManageable**  <br>*optional* | This parameter specifies if a control over the database is not carried out by the DbaaS adapter                                                                                                       | boolean                      |
| **id**  <br>*required*                   | A unique identifier of the document in the database. This field might not be used when searching by classifier for security purpose. And it exists in the response when executing Create database API | string (uuid)                |
| **name**  <br>*required*                 | Name of database. It may be generated or, if name was specified in the request, then it will be specified.                                                                                            | string                       |
| **namespace**  <br>*required*            | Namespace where database is placed.                                                                                                                                                                   | string                       |
| **timeDbCreation**  <br>*optional*       | Time to create a database.                                                                                                                                                                            | string (date-time)           |
| **type**  <br>*required*                 | Type of database, for example PostgreSQL or MongoDB                                                                                                                                                   | string                       |

### PhysicalDatabaseRegistrationResponseDTO

| Name                               | Description                                                                      | Schema                  |
|------------------------------------|----------------------------------------------------------------------------------|-------------------------|
| **adapterAddress**  <br>*required* | Adapter address.                                                                 | string                  |
| **adapterId**  <br>*required*      | Adapter identifier.                                                              | string                  |
| **global**  <br>*required*         | If physical database is global, it is used as a default for its database type.   | boolean                 |
| **labels**  <br>*required*         | Additional information that has been sent during physical database registration. | < string, string > map  |
| **supports**  <br>*required*       | Information about features this adapter supports.                                | < string, boolean > map |
| **type**  <br>*required*           | Adapter type.                                                                    | string                  |

### GetOrCreateUserRequest

| Name                               | Description                                                                                                                   | Schema              |
|------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|---------------------|
| **classifier**  <br>*required*     | It is a part of composite unique key. For more info see [classifier](#classifier)                                             | Map<String, String> |
| **logicalUserId**  <br>*required*  | User uniq identifier. Using this field with classifier and type dbaas will determine to create or return created before user. | string              |
| **type**  <br>*required*           | Adapter type.                                                                                                                 | string              |
| **physicalDbId**  <br>*optional*   | Identificator of physical database related to a logical database .                                                            | string              |
| **usernamePrefix**  <br>*optional* | Prefix for username.                                                                                                          | string              |
| **userRole**  <br>*optional*       | User role, for example admin, rw, ro                                                                                          | string              |

### GetOrCreateUserResponse

| Name                                     | Description                                                                                            | Schema                       |
|------------------------------------------|--------------------------------------------------------------------------------------------------------|------------------------------|
| **connectionProperties**  <br>*required* | This is an information about connection to database. It contains such keys as url, username, password. | List< Map <string, object> > |
| **userId**  <br>*required*               | User uniq identifier.                                                                                  | string                       |

### UserOperationRequest

| Name                              | Description                                                                                                                   | Schema              |
|-----------------------------------|-------------------------------------------------------------------------------------------------------------------------------|---------------------|
| **classifier**  <br>*optional*    | It is a part of composite unique key. For more info see [classifier](#classifier)                                             | Map<String, String> |
| **userId**  <br>*optional*        | User uniq identifier. Request should contain userId parameter or logicalUserId, classifier and type.                          | string              |
| **logicalUserId**  <br>*optional* | User uniq identifier. Using this field with classifier and type dbaas will determine to create or return created before user. | string              |
| **type**  <br>*optional*          | Adapter type.                                                                                                                 | string              |

### ConnectionProperties

| Name                                     | Description                                                                                            | Schema              |
|------------------------------------------|--------------------------------------------------------------------------------------------------------|---------------------|
| **connectionProperties**  <br>*required* | This is an information about connection to database. It contains such keys as url, username, password. | Map<String, String> |

### DeleteOrphansRequest

| Name                           | Description                                                                                                                                                                                                                  | Schema       |
|--------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------|
| **namespaces**  <br>*required* | List of target namespaces for databases deletion                                                                                                                                                                             | List<String> |
| **delete**  <br>*required*     | confirmation parameter. If this is not passed or false then orhan database will not be deleted and response will contain orhan databases that are registered in DbaaS. If set to true - then real deletion will be performed | Boolean      |


### OrphanDatabasesResponse

| Name                               | Description                                             | Schema              |
|------------------------------------|---------------------------------------------------------|---------------------|
| **dbName**  <br>*required*         | Name of orphan database                                 | String              |
| **classifier**  <br>*required*     | Classifier of orphan database                           | Map with classifier |
| **type**  <br>*required*           | Type of orphan database                                 | String              |
| **namespace**  <br>*required*      | Namespace of orphan database                            | String              |
| **dbCreationTime**  <br>*optional* | Time of database creation                               | Date                |
| **physicalDbId**  <br>*optional*   | Id of phycial database where orphan database is created | String              |
| **bgVersion**  <br>*optional*      | Current BGVersion of database                           | String              |

### AccessGrantsResponse

| Name                                         | Description                                | Schema                            |
|----------------------------------------------|--------------------------------------------|-----------------------------------|
| **services**  <br>*optional*                 | List of services and their available roles | List<[ServiceRole](#ServiceRole)> |
| **policies**  <br>*optional*                 | List of own microservice policies          | List<[PolicyRole](#PolicyRole)>   |
| **disableGlobalPermissions**  <br>*required* | Is global permissions disable              | Boolean                           |


### ServiceRole

| Name                      | Description                         | Schema       |
|---------------------------|-------------------------------------|--------------|
| **name**  <br>*required*  | Microservice name                   | String       |
| **roles**  <br>*required* | List of available roles for service | List<String> |

### PolicyRole

| Name                               | Description                                                                       | Schema       |
|------------------------------------|-----------------------------------------------------------------------------------|--------------|
| **type**  <br>*required*           | Physical database type                                                            | String       |
| **defaultRole**  <br>*required*    | The role that will be used by default if userRole is not specified when requested | String       |
| **additionalRole**  <br>*required* | List of available additional roles                                                | List<String> |

### OperationStatusExtendedResponse

| Name                                 | Description                          | Schema                                                                                |
|--------------------------------------|--------------------------------------|---------------------------------------------------------------------------------------|
| **status**  <br>*required*           | Status of process                    | enum (NOT_STARTED, IN_PROGRESS, WAITING_FOR_RESOURCES, COMPLETED, FAILED, TERMINATED) |
| **message**  <br>*required*          | Generic description of work progress | String                                                                                |
| **operationDetails**  <br>*required* | Detailed info about process          | [OperationDetails](#OperationDetails)                                                 |

### OperationDetails

| Name                      | Description              | Schema                            |
|---------------------------|--------------------------|-----------------------------------|
| **tasks**  <br>*required* | List of tasks of process | List<[TaskDetails](#TaskDetails)> |

### TaskDetails

| Name                           | Description                                | Schema                            |
|--------------------------------|--------------------------------------------|-----------------------------------|
| **taskId**  <br>*required*     | Task id                                    | String                            |
| **taskName**  <br>*required*   | Task name in "task_type:process_id" format | String                            |
| **state**  <br>*required*      | Task state description                     | [OperationState](#OperationState) |
| **classifier**  <br>*required* | Target DB classifier for task              | Object                            |
| **type**  <br>*required*       | DB type for task                           | String                            |
| **backupId**  <br>*optional*   | Backup id in case of backup/restore task   | String                            |
| **restoreId**  <br>*optional*  | Restore id in case of backup/restore task  | String                            |

### OperationState

| Name                            | Description                                   | Schema                                                                                |
|---------------------------------|-----------------------------------------------|---------------------------------------------------------------------------------------|
| **status**  <br>*required*      | Task status                                   | enum (NOT_STARTED, IN_PROGRESS, WAITING_FOR_RESOURCES, COMPLETED, FAILED, TERMINATED) |
| **description**  <br>*required* | Detailed description of current state of task | String                                                                                |

#### UpdateHostRequest

| Name                                     | Description                                                                                                                                                                                                                                                      | Schema              |
|------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------|
| **type**  <br>*required*                 | The physical type of logical database. For example, mongodb or postgresql. This value determines which type of physical host should be updated.                                                                                                                  | string              |
| **classifier**  <br>*required*           | The unique key of the existing database. The classifier uniquely identifies the logical database that needs its physical host updated.                                                                                                                           | Map<string, object> |
| **makeCopy**  <br>*optional*             | If true, a copy of the logical database registry will be created before applying changes. If false, changes will be made directly to the existing registry. Making a copy helps in preserving the original configuration for rollback purposes. Default is true. | boolean             |
| **physicalDatabaseHost**  <br>*required* | Specifies the physical database host to be updated. The host must follow the format: `<physical database k8s service>.<physical database namespace>`. Example: `pg-patroni.core-postgresql`.                                                                     | string              |
| **physicalDatabaseId**  <br>*required*   | Specifies the ID of the new physical database. The value can be found in the dbaas-adapter environment. This ID is used to uniquely identify the target physical database where the logical database will be hosted.                                             | string              |


### CompositeStructure

| Name                           | Description                                                                                                     | Schema       |
|--------------------------------|-----------------------------------------------------------------------------------------------------------------|--------------|
| **id**  <br>*required*         | Composite identifier. Usually it's baseline or origin baseline in blue-green deployment                         | String       |
| **namespaces**  <br>*required* | Namespaces that are included in composite structure. This list should contain baseline and satellite namespaces | List<String> |

### CompositeStructureResponse

| Name                           | Description                                                                                                     | Schema       |
|--------------------------------|-----------------------------------------------------------------------------------------------------------------|--------------|
| **id**  <br>*required*         | Composite identifier. Usually it's baseline or origin baseline in blue-green deployment                         | String       |
| **namespaces**  <br>*required* | Namespaces that are included in composite structure. This list should contain baseline and satellite namespaces | List<String> |

### TmfErrorResponse

| Name                        | Description                                                                                         | Schema                |
|-----------------------------|-----------------------------------------------------------------------------------------------------|-----------------------|
| **id**  <br>*required*      | Unique error id                                                                                     | String(UUID)          |
| **code**  <br>*required*    | Error Code like CORE-DBAAS-4001. Unique error identifier                                            | String                |
| **reason**  <br>*required*  | Human readable summary of error                                                                     | String                |
| **message**  <br>*required* | Detailed error description with technical details                                                   | String                |
| **status**  <br>*required*  | HTTP response code                                                                                  | String                |
| **meta**  <br>*optional*    | Structure that contains extra data that will help investigate problem. May contain any custom data. | String in JSON format |
| **@type**  <br>*required*   | Contant. Equal to NC.TMFErrorResponse.v1.0                                                          | String                |


### RestoreUsersRequest

| Name                           | Description                                                                                                                                                                     | Schema                 |
|--------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------|
| **classifier**  <br>*required* | Don't pass this parameter for restore users for all databases with passed namespace and type. It is a part of composite unique key. For more info see [classifier](#classifier) | Map<String, String>    |
| **type**  <br>*required*       | Adapter type.                                                                                                                                                                   | string                 |
| **role**  <br>*optional*       | User role. If this parameter passed, only users with this role will be processed.                                                                                               | string                 |

### RestoreUsersResponse

| Name                               | Description                           | Schema                                            |
|------------------------------------|---------------------------------------|---------------------------------------------------|
| **unsuccessfully**  <br>*optional* | List of successfully restored users   | List<[SuccessfulRestore](#successfulrestore)>     |
| **successfully**  <br>*optional*   | List of unsuccessfully restored users | List<[UnsuccessfulRestore](#unsuccessfulrestore)> |                                      |


### SuccessfulRestore

| Name                                       | Description                                                                                                                           | Schema                |
|--------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------|-----------------------|
| **connectionProperties**  <br>*required*   | Successfully restored users. This is an information about connection to database. It contains such keys as url, username, password.   | <Map <string, object> |

### UnsuccessfulRestore

| Name                                      | Description                                                                                                               | Schema                 |
|-------------------------------------------|---------------------------------------------------------------------------------------------------------------------------|------------------------|
| **connectionProperties**  <br>*required*  | Not restored user. This is an information about connection to database. It contains such keys as url, username, password. | Map <string, object>   |
| **errorMessage**  <br>*required*          | Detailed error description with technical details                                                                         | String                 |

### Message

| Name                           | Description                                       | Schema     |
|--------------------------------|---------------------------------------------------|------------|
| **message**  <br>*required*    | Detailed description with technical details       | String     |

### GhostDatabasesResponse

| Name                                   | Description                                                            | Schema       |
|----------------------------------------|------------------------------------------------------------------------|--------------|
| **dbNames**  <br>*required*            | Logical database names.                                                | List<String> |
| **physicalDatabaseId**  <br>*required* | Identificator of physical database related to these logical databases. | String       |
| **errorMessage**  <br>*optional*       | Error message.                                                         | String       |

### LostDatabasesResponse

| Name                                   | Description                                                            | Schema                                                  |
|----------------------------------------|------------------------------------------------------------------------|---------------------------------------------------------|
| **databases**  <br>*required*          | Logical databases.                                                     | List<[DatabaseResponseListCP](#databaseresponselistcp)> |
| **physicalDatabaseId**  <br>*required* | Identificator of physical database related to these logical databases. | String                                                  |
| **errorMessage**  <br>*optional*       | Error message.                                                         | String                                                  |

### OverallStatusResponse

| Name                                         | Description                          | Schema                                              |
|----------------------------------------------|--------------------------------------|-----------------------------------------------------|
| **overallHealthStatus**  <br>*required*      | Overall health status of DBaaS       | String                                              |
| **overallLogicalDbNumber**  <br>*required*   | Number of DBaaS logical databases    | Integer                                             |
| **physicalDatabaseInfoList**  <br>*required* | Information about physical databases | List<[PhysicalDatabaseInfo](#physicaldatabaseinfo)> |

### PhysicalDatabaseInfo

| Name                                   | Description                                                   | Schema |
|----------------------------------------|---------------------------------------------------------------|--------|
| **physicalDatabaseId**  <br>*required* | Physical database identifier                                  | String |
| **healthStatus**  <br>*required*       | Overall health status of adapter                              | String |
| **logicalDbNumber**  <br>*required*    | Number of logical databases related to this physical database | String |



