## Classifier v3 migration process

### Problem

According to the DBaaS solution each logical database must have classifier which is a part of composite primary key of a
logical database. Before DBaaS 3.18 a classifier did not have a particular structure and this often causes internal
problems and did not allow to implement some features. A new v3 rest API version is introduced since DBaaS 3.18. This
version uses a classifier with the particular structure:

```text
"classifier":{
  "tenantId": "tenant id:,                  //mandatory if you use tenant scope 
  "microserviceName": "microservice name",  //mandatory
  "scope": "tenant | service",              //mandatory
  "namespace" : "namespace"                 //mandatory
  "custom_keys": <>                         //optional
}
```

But before using this version we need to migrate from old (v1/v2) classifiers to new (v3) classifiers. This migration is
made automatically during an update process and classifiers will have the view:

*For service databases*:

| V1/V2 classifier, before migration                                                                                                            | V3 classifier, after migration                                                                                                                                                              |
|-----------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| "classifier": {<br/> &emsp; "microserviceName": "service-name",<br/> &emsp; "isService": true, <br/> &emsp; "custom_key": custom_object<br/>} | "classifier": {<br/> &emsp; "microserviceName": "service-name",<br/> &emsp; "scope": "service",<br/> &emsp; "namespace" : "some-namespace", <br/>  &emsp; "custom_key": custom_object<br/>} |

*For tenant databases*:

| V1/V2 classifier, before migration                                                                                                                | V3 classifier, after migration                                                                                                                                                                                                 |
|---------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| "classifier": {<br/> &emsp; "microserviceName": "service-name",<br/> &emsp; "tenantId": "some id", <br/> &emsp; "custom_key": custom_object<br/>} | "classifier": {<br/> &emsp; "microserviceName": "service-name",<br/> &emsp; "scope": "tenant", <br/> &emsp; "tenantId": "some id",<br/> &emsp; "namespace" : "some-namespace", <br/>  &emsp; "custom_key": custom_object<br/>} |

DBaaS aggregator validates all existing classifiers before running the migration. If there are classifiers that don't
have `'microserviceName'/'isService'/'tenantId'` fields then these classifiers are not migrated automatically and marked
as `{"V3_TRANSFORMATION": "fail"}`. In this case client will not be able to use v3 with these databases, and **you need
to update existing classifier manually**.

In order to evaluate an environment state for the number of incorrect classifiers to plan a maintenance window, you may
use recommendations from the following section.

### Classifier verification

This section describes how you can verify if there are databases with incorrect classifiers and how many there are. This
allows you to fix problems and plan a maintenance window **before** dbaas updating to 3.18.1 or higher:

#### 1. Find all incorrect classifiers

Information about incorrect databases is printed to log during migration, or you can call the API:

```shell script
curl <dbaas_host>/api/v2/dbaas/{namespace}/databases/failed_transformation -u <creds dba user>
```

#### 2. Change used classifier inside microservice

You should pick each classifier and find out to which service in a cloud it belongs. After that, you should ask a
microservice owner to change used classifiers inside this microservice.
A valid v3 classifier must have the following view:

```text
"classifier": {
  "tenantId": "tenant id:,                  //mandatory if you use tenant scope 
  "microserviceName": "microservice name",  //mandatory
  "scope": "tenant | service",              //mandatory
  "namespace" : "namespace"                 //mandatory
  "custom_keys": <>                         //optional
}
```

> **Pay attention**: Service with an updated classifier **must not be updated** before the forth step of this instruction
> is done. Otherwise, a new database will be **created**!

#### 3. Scale to zero microservice replica

When the second step is ready, you need to scale microservice's replica to zero. It guarantees that an extra database
won't be created during the fourth step.

#### 4. Update classifier

Send a request to DBaaS to update classifier. You should specify an old classifier from the log, a new v3 classifier
which will be used by microservice and put `fromV1orV2ToV3` to `true`. You can find more about the API here:
[Update existing logical database classifier](../rest-api.md#update-existing-database-classifier)

#### 5. Update service with new classifier

When classifier is updated in DBaaS Aggregator (4 step), you should update service from step 2. Replica count can be
returned to its previous state.
