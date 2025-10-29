This article describes the process of changing the classification of service logical database to tenant logical
database ([Service vs Tenant databases](../FAQ/Service%20vs%20Tenant%20databases.md)).

## Prerequisites

1. Know the classifiers of service database which you want to migrate;
2. Know **tenant-id** ;
3. Make sure that is installed DBaaS Aggregator of 3.20.0 version (or higher) with "update classifier" API support;
4. Know DBAAS_DB_EDITOR_CREDENTIALS_USERNAME/DBAAS_DB_EDITOR_CREDENTIALS_PASSWORD credentials which were installed
   during deploy.

## Migration procedure

#### 1) Send request to DBaaS Aggregator

Send request to update classifier to DBaaS Aggregator project.

Old service classifier place into "from", new tenant classifier place into "to" and set **"clone"=true.**

To migrate service classifier to tenant you have to set scope="tenant" and add one more field tenantId={**tenant-id**}

[PUT /api/v3/dbaas/namespaces/{namespace}/databases/update-classifier/{type}](../rest-api.md#update-existing-database-classifier)

Example request body :

```
{
  "from": {
      "microserviceName": "service1",
      "namespace": "namespace1",
      "scope": "service"
  },
  "to": {
      "microserviceName": "service1",
      "namespace": "namespace1",
      "scope": "tenant",
      "tenantId": {tenant-id}
  },
  "clone": true
}
```

#### 2) Check that migration was done success.

Send request to DBaaS Aggregator to get list databases from namespaces.

[GET /api/v3/dbaas/{namespace}/databases/list](../rest-api.md#get-database-by-classifier)

You will receive all databases from namespaces. Response contains 2 databases with bellow classifiers.

```
classifier {
      "microserviceName": "service1",
      "namespace": "namespace1",
      "scope": "service"
  }
```

And

```
classifier{
      "microserviceName": "service1",
      "namespace": "namespace1",
      "scope": "tenant",
      "tenantId": {tenant-id}
  }
```

#### 3) Service changed

You have to use tenant client instead of service client.

Example for opensearch-dbaas-client:

```
Before:
    @Autowired
    @Qualifier(SERVICE_NATIVE_OPENSEARCH_CLIENT)
    private DbaasOpensearchClient serviceClient;

After:
    @Autowired
    @Qualifier(TENANT_NATIVE_OPENSEARCH_CLIENT)
    private DbaasOpensearchClient tenantClient;
```

#### 4) Check database with old classifier

When updated service will start.

Check that database with old classifier has is externally_manageable flag.

[POST /api/v3/dbaas/{namespace}/databases/get-by-classifier/{type}](../rest-api.md#get-database-by-classifier)

Example request body:

```
{
  "classifier": {
      "microserviceName": "service1",
      "namespace": "namespace1",
      "scope": "service"
  },   
  "originService": "service1"
}
```

Response body **must** contain "externallyManageable": true.
