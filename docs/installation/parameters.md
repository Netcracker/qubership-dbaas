# DBaaS Installation Parameters

- [DBaaS Aggregator Installation Parameters](#dbaas-aggregator-installation-parameters)
    * [Regular Parameters](#regular-parameters)
        + [USE_POSTGRES_PORT_FORWARD](#use_postgres_port_forward)
        + [POSTGRES_DBA_USER / POSTGRES_DBA_PASSWORD](#postgres_dba_user--postgres_dba_password)
        + [POSTGRES_DBAAS_DATABASE_NAME / POSTGRES_DBAAS_USER / POSTGRES_DBAAS_PASSWORD](#postgres_dbaas_database_name--postgres_dbaas_user--postgres_dbaas_password)
        + [DBAAS_RECREATE_DEPLOYMENT_STRATEGY](#dbaas_recreate_deployment_strategy)
        + [POSTGRES_HOST](#postgres_host)
        + [DBAAS_PREMATURE_REGISTRATION_ADAPTER_ADDRESSES](#dbaas_premature_registration_adapter_addresses)
        + [DBAAS_DEFAULT_PHYSICAL_DATABASES_DISABLED](#dbaas_default_physical_databases_disabled)
        + [NODE_SELECTOR_DBAAS_KEY](#node_selector_dbaas_key)
        + [REGION_DBAAS](#region_dbaas)
        + [DBAAS_OWN_PG_DB_CREATED_MANUALLY](#dbaas_own_pg_db_created_manually)
        + [MONITORING_ENABLED](#monitoring_enabled)
        + [CLOUD_TOPOLOGY_KEY](#cloud_topology_key)
        + [DR_MANAGEABLE](#dr_manageable)
        + [PRODUCTION_MODE](#production_mode)
        + [INTERNAL_TLS_ENABLED](#internal_tls_enabled)
        + [READONLY_CONTAINER_FILE_SYSTEM_ENABLED](#readonly_container_file_system_enabled)
        + [priorityClassName](#priorityClassName)
    * [CREDENTIALS](#credentials)
        + [DBAAS_DB_EDITOR_CREDENTIALS_USERNAME / DBAAS_DB_EDITOR_CREDENTIALS_PASSWORD](#dbaas_db_editor_credentials_username--dbaas_db_editor_credentials_password)
        + [DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME / DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD](#dbaas_cluster_dba_credentials_username--dbaas_cluster_dba_credentials_password)
        + [BACKUP_DAEMON_DBAAS_ACCESS_USERNAME / BACKUP_DAEMON_DBAAS_ACCESS_PASSWORD](#backup_daemon_dbaas_access_username--backup_daemon_dbaas_access_password)
        + [DBAAS_TENANT_USERNAME / DBAAS_TENANT_PASSWORD](#dbaas_tenant_username--dbaas_tenant_password)
        + [DISCR_TOOL_USER_USERNAME / DISCR_TOOL_USER_PASSWORD](#discr_tool_user_username--discr_tool_user_password)
- [REMOVED](#removed)
    + [RUN_SMOKE_ELASTICSEARCH](#run_smoke_elasticsearch)
    + [ELASTICSEARCH_DBAAS_ADAPTER_ADDRESS](#elasticsearch_dbaas_adapter_address)
    + [ELASTICSEARCH_DBAAS_AGGREGATOR_USERNAME / ELASTICSEARCH_DBAAS_AGGREGATOR_PASSWORD](#elasticsearch_dbaas_aggregator_username--elasticsearch_dbaas_aggregator_password)
    + [MONGO_DBAAS_AGGREGATOR_USERNAME / MONGO_DBAAS_AGGREGATOR_PASSWORD](#mongo_dbaas_aggregator_username--mongo_dbaas_aggregator_password)
    + [POSTGRES_DBAAS_AGGREGATOR_USERNAME / POSTGRES_DBAAS_AGGREGATOR_PASSWORD](#postgres_dbaas_aggregator_username--postgres_dbaas_aggregator_password)
    + [DBAAS_AGGREGATOR_USERNAME / DBAAS_AGGREGATOR_PASSWORD](#dbaas_aggregator_username--dbaas_aggregator_password)
    + [MONGO_DBAAS_ADAPTER_ADDRESS](#mongo_dbaas_adapter_address)
    + [POSTGRES_DBAAS_ADAPTER_ADDRESS](#postgres_dbaas_adapter_address)
    + [INSTALL_ADAPTERS](#install_adapters)
    + [KMS_EXTERNAL_ADDRESS / KMS_INTERNAL_ADDRESS / KMS_ACCOUNT_USERNAME / KMS_ACCOUNT_PASSWORD](#kms_external_address--kms_internal_address--kms_account_username--kms_account_password)
    + [MONITORING_INSTALL](#monitoring_install)
    + [MONGO_HOST / MONGO_DBAAS_AUTH_DB / MONGO_DBAAS_USER / MONGO_DBAAS_PASSWORD / DBAAS_MONGO_DATABASE](#mongo_host--mongo_dbaas_auth_db--mongo_dbaas_user--mongo_dbaas_password--dbaas_mongo_database)
    + [ZOOKEEPER_ADDRESS](#zookeeper_address)
    + [DBAAS_ZOOKEEPER_REPLICATION](#dbaas_zookeeper_replication)
    + [VAULT_INTEGRATION](#VAULT_INTEGRATION)
    + [VAULT_ADDR](#vault_addr)
    + [VAULT_TOKEN](#vault_token)
    + [DBAAS_VAULT_MIGRATION](#dbaas_vault_migration)
    + [VAULT_DISABLED_ADAPTERS_LIST](#vault_disabled_adapters_list)
    + [RUN_DBAAS_SMOKE / RUN_SMOKE_POSTGRES / RUN_SMOKE_MONGO / RUN_SMOKE_OPENSEARCH](#run_dbaas_smoke--run_smoke_postgres--run_smoke_mongo--run_smoke_opensearch)

## DBaaS Aggregator Installation Parameters

### Regular Parameters

#### USE_POSTGRES_PORT_FORWARD

**since 3.0.0**

This parameter indicates that PostgreSQL port-forward should be created and used during DBaaS Aggregator installation.

| Default | Recommended                                                                                                                                                                                                              | 
|---------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| true    | If your deployment process has direct access to postgresql database then you should not create and use postgresql port-forward during DBaaS installation. It can be useful when you have an external postgresql database | 

#### POSTGRES_DBA_USER / POSTGRES_DBA_PASSWORD

**since 3.0.0**

Postgresql user with `SUPERUSER` privilege. This user is needed for creating DBaaS-specific automatically. This user
creates DBaaS user, DBaaS database, and gives DBaaS owner role to DBaaS user.

| Parameter             | Default  | Recommended                                                                                              | 
|-----------------------|----------|----------------------------------------------------------------------------------------------------------|
| POSTGRES_DBA_USER     | postgres | We do not recommend to pass this parameter and use `POSTGRES_DBAAS_USER/POSTGRES_DBAAS_PASSWORD` instead | 
| POSTGRES_DBA_PASSWORD | password | We do not recommend to pass this parameter and use `POSTGRES_DBAAS_USER/POSTGRES_DBAAS_PASSWORD` instead | 

#### POSTGRES_DBAAS_DATABASE_NAME / POSTGRES_DBAAS_USER / POSTGRES_DBAAS_PASSWORD

**since 3.0.0**

These parameters are needed for connecting to Postgresql physical database where DBaaS will store its own data.
A logical database with name `POSTGRES_DBAAS_DATABASE_NAME` can be existed or not. In the latter case, DBaaS Aggregator
creates it himself with passed or default name. `POSTGRES_DBAAS_USER/POSTGRES_DBAAS_PASSWORD` are credentials for
postgres DBaaS specific role. You must create this user manually with `CREATEDB` option. For example:
`create user dbaas_user with encrypted password 'qwerty54321' CREATEDB;`. `CREATEDB` option is specified in order to
allow the user create his own databases.
If you do not pass these credentials then you must pass `POSTGRES_DBA_USER/POSTGRES_DBA_PASSWORD` and new user with name
`dbaas_user_<namespace>` and autogenerated password will be created automatically.
Do not pass values for dbaas-user through parameters for `POSTGRES_DBA_USER`. We expect that `POSTGRES_DBA_USER` already
created and do not create it by ourselves.

| Parameter                    | Default                   | Recommended                                                                                                                                                                     | 
|------------------------------|---------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| POSTGRES_DBAAS_DATABASE_NAME | `<NAMESPACE>`             | If you do not pass your name then you should be sure that logical database with default name `<namespace>` does not exist                                                       | 
| POSTGRES_DBAAS_USER          | **-** <p>No default value | With security point of view, we recommend to create such user yourself and pass to deployment process only DBaaS specific user. User should be created with `CREATEDB` optional | 
| POSTGRES_DBAAS_PASSWORD      | **-** <p>No default value | Pass username and password only an existing user                                                                                                                                |

#### DBAAS_RECREATE_DEPLOYMENT_STRATEGY

**since 3.0.0**

You should consider this mode in migration procedure.
This parameter changes deployment strategy from rolling to recreate
(https://docs.openshift.com/container-platform/3.11/dev_guide/deployments/deployment_strategies.html).
Recreate deployment incurs downtime because, for a brief period, no instances of your application are running. By the
point of services it's safe, because platform tries to run application about 10 minutes (default time) but DBaaS
Aggregator starts up about 30 sec.   
Thus, after start up DBaaS Aggregator, platform stabilizes situation and services will be got database connection.

| Default | Recommended                                                                                                 | 
|---------|-------------------------------------------------------------------------------------------------------------|
| false   | We recommend to use this mode in migration procedure for consistent data. Note that you will have down time |  

#### POSTGRES_HOST

**since 3.0.0**

DBaaS Aggregator needs this parameter for connecting with postgresql database.

| Default               | Recommended                                                                                                   | 
|-----------------------|---------------------------------------------------------------------------------------------------------------|
| pg-patroni.postgresql | Postgres host contains of postgres service name and namespace separated by point `<servcie_name>.<namespace>` | 

#### POSTGRES_PORT

**since 3.20.0**

DBaaS Aggregator needs this parameter for connecting with postgresql database.

| Default | Recommended                                     | 
|---------|-------------------------------------------------|
| 5432    | Postgres port contains of postgres service port | 

#### DBAAS_PREMATURE_REGISTRATION_ADAPTER_ADDRESSES

**since 2.3.0**

List of adapter addresses, which must be registered premature: as soon as DBaaS Aggregator starts.

DBaaS Aggregator sends notifications to the listed adapters during startup, so they will try to register themselves
immediately.

For the most cases this parameter is optional, but recommended, since all the DBaaS adapters register themselves
periodically and DBaaS Aggregator does not need to forcefully initiate adapters registration. However, if
DBAAS_PREMATURE_REGISTRATION_ADAPTER_ADDRESSES is not specified, DBaaS Aggregator would not know about adapter until
adapter's periodical registration triggers.

Note, that all the listed DBaaS adapters must be configured to register themselves in this DBaaS Aggregator, since
DBAAS_PREMATURE_REGISTRATION_ADAPTER_ADDRESSES parameter just triggers immediate registration without specifying any
target DBaaS Aggregator.

| Default                    | Recommended                                                                                                                                                                                                                                                                                 | 
|----------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **-** <p>No default value. | Addresses of the adapters, that have to be registered as soon as DBaaS Aggregator starts. Addresses must be separated by comma, e.g.: **http://dbaas-opensearch-adapter.opensearch:8080,http://dbaas-postgres-adapter.postgresql-dbaas:8080,http://dbaas-mongo-adapter.mongo-cluster:8080** | 

#### DBAAS_DEFAULT_PHYSICAL_DATABASES_DISABLED

**since 4.1.0**

Prevents database creation in the default physical database when no dedicated balancing rules are found for microservice
and namespace.

| Default | Recommended                                                                                                                                   |
|---------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| false   | We recommend to use default value unless you need fully manual assignment of physical databases to microservices based on the balancing rules |

#### NODE_SELECTOR_DBAAS_KEY

**since 3.9.0**

A DBaaS node selector key specifies a key value of nodeSelector. You can use node selectors to place on which node dbaas
pods will be located.

| Default | Recommended                    | 
|---------|--------------------------------|
| region  | Provides key for node selector |

#### REGION_DBAAS

**since 1.0.0**

It indicates a node region where pods will be located

| Default  | Recommended                                                          | 
|----------|----------------------------------------------------------------------|
| database | The indicated region must exist otherwise an installation won't pass | 

#### CLOUD_TOPOLOGY_KEY

**since 3.7.0**

Default value: `kubernetes.io/hostname`

Nodes' label to match for inner pod anti-affinity soft rule.

#### DBAAS_OWN_PG_DB_CREATED_MANUALLY

**since 3.6.0**

Default value: `false`

Setting this parameter to `true` disables automatic database creation by dbaas and allows user to use his own database,
username and password which could be created manually.
Should be used with `POSTGRES_DBAAS_DATABASE_NAME, POSTGRES_DBAAS_USER, POSTGRES_DBAAS_PASSWORD`.

```yaml
DBAAS_OWN_PG_DB_CREATED_MANUALLY=true;
POSTGRES_DBAAS_DATABASE_NAME=db_name;
POSTGRES_DBAAS_USER=db_user;
POSTGRES_DBAAS_PASSWORD=db_password;
```

***For PostgreSQL 15***

PostgreSQL 15 revokes the CREATE permission from all users except a database owner from the public (or default)
schema. [Link to documentation](https://www.postgresql.org/about/news/postgresql-15-released-2526/#:%7E:text=PostgreSQL%2015%20also%20revokes%20the%20CREATE%20permission%20from%20all%20users%20except%20a%20database%20owner%20from%20the%20public)

Please note that if you are not creating a database as `POSTGRES_DBAAS_USER`, you need to grant rights to the public
schema for `POSTGRES_DBAAS_USER`.
For example, if the DBaaS database is created as pg_user, run the following command:

```psql "host=pg_host port=pg_port dbname=POSTGRES_DBAAS_DATABASE_NAME user=pg_user password=pg_password" -c "GRANT ALL ON SCHEMA public TO POSTGRES_DBAAS_USER;"```

#### MONITORING_ENABLED

**since 3.14.0**

Flag to install podmonitor and grafana dashboard for DBaaS.

| Default | Recommended                                                                         | 
|---------|-------------------------------------------------------------------------------------|
| true    | Set to true if need to enable prometheus monitoring and install dashboard for DBaaS | 

#### DR_MANAGEABLE

This parameter is required for Disaster Recovery environments. Without this parameter and prerequisites (Role,
RoleBinding) DBaaS won't be replicated to next k8s cluster.

| Default | Recommended                    |
|---------|--------------------------------|
| false   | Possible values = true / false |

#### PRODUCTION_MODE

If PRODUCTION_MODE is set to true, dropping databases in DBaaS is not allowed.

| Default | Recommended                    |
|---------|--------------------------------|
| true    | Possible values = true / false |

#### INTERNAL_TLS_ENABLED

If INTERNAL_TLS_ENABLED is set to true, DBaaS Aggregator will work in TLS mode:

* two ports will be available: 8080(http) and 8443(https)
* all connections via https proto will be secured

| Default | Recommended                                |
|---------|--------------------------------------------|
| false   | Set to true if need to enable TLS in DBaaS |

#### READONLY_CONTAINER_FILE_SYSTEM_ENABLED

**since 3.19.0**

If READONLY_CONTAINER_FILE_SYSTEM_ENABLED is set to true, DBaaS Aggregator will be deployed with read-only file system
in its container.
Note: Only valid for deployments in Kubernetes.

| Default | Recommended                                         |
|---------|-----------------------------------------------------|
| false   | Set to true if need to enable read-only file system |

#### priorityClassName

**since 4.8.0**

Defines priority of the pod.
Specifies [Priority Class](https://kubernetes.io/docs/concepts/scheduling-eviction/pod-priority-preemption/#priorityclass).

| Default | Recommended                                     |
|---------|-------------------------------------------------|
| empty   | You should create the priority class beforehand |

### CREDENTIALS

#### DBAAS_DB_EDITOR_CREDENTIALS_USERNAME / DBAAS_DB_EDITOR_CREDENTIALS_PASSWORD

**since 2.2.0**

These credentials are for role "dbaas-db-editor". This role can edit logical database registration in DBaaS store
database. For example this role is needed for update existing classifiers.

| Default                                                                                                      | Recommended                                                                                                                                                                                                    | 
|--------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| <p>DBAAS_DB_EDITOR_CREDENTIALS_USERNAME=**dbaas-db-editor** <p>DBAAS_DB_EDITOR_CREDENTIALS_PASSWORD=**None** | You can generate these credentials during first DBaaS installation, save them in configuration and do not change them (at least until you know, that all functional projects are updated with new credentials) | 

#### DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME / DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD

Credentials used to authorize function project services to access databases, should be generated on DBaaS installation
or equal to the same value used during functional project installation if DBaaS is installed after application project
installation.

Config-server, tenant-manager and dbaas-agent would use these credentials during deploy of functional project
where any of these components presented.

Also, these credentials are used by dbaas-adapters for self-registration so these credentials in `dbaas-aggregator` and
those credentials in `dbaas-adapter` must match.

| Default                                                                                                                                                                     | Recommended                                                                                                                                                                                                                                                                                                                                                       | 
|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| <p>DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME=**cluster-dba** <p>DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD=**None** <p>You need to change that if you need to secure installation. | <p>You can generate these credentials during first DBaaS installation, save them in configuration and do not change them (at least until you know, that all functional projects are updated with new credentials). <p>Same credentials should be used during installation of functional projects where dbaas-agent or config-server or tenant-manager is included | 

#### BACKUP_DAEMON_DBAAS_ACCESS_USERNAME / BACKUP_DAEMON_DBAAS_ACCESS_PASSWORD

Credentials used to authorize access to backup collection and restoration by project backup daemon, should be generated
on DBaaS installation or should be equal to the same value used during cloud-project-backup installation.

| Default                                                                                                                                                                 | Recommended                                                                                                                                                                                                                                                                                                                                      | 
|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| <p>BACKUP_DAEMON_DBAAS_ACCESS_USERNAME=**backup-daemon** <p>BACKUP_DAEMON_DBAAS_ACCESS_PASSWORD=**None** <p>You need to change that if you need to secure installation. | <p>You can generate these credentials during first DBaaS installation, save them in configuration and do not change them (at least until you know, that all project-backup projects are updated with new credentials). <p>Same credentials should be used during installation of project-backup projects where project-backup daemon is included | 

#### DBAAS_TENANT_USERNAME / DBAAS_TENANT_PASSWORD

Credentials used to authorize access to database list used by tenant-manager to clean sandbox tenant, should be
generated on DBaaS installation or equal to the same value used during functional project installation if DBaaS is
installed after application project installation.

| Default                                                                                                                                | Recommended                                                                                                                                                                                                                                                                                                                       | 
|----------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| <p>DBAAS_TENANT_USERNAME=*dbaas-tenant* <p>DBAAS_TENANT_PASSWORD=*None* <p>You need to change that if you need to secure installation. | <p>You can generate these credentials during first DBaaS installation, save them in configuration and do not change them (at least until you know, that all functional projects are updated with new credentials). <p>Same credentials should be used during installation of functional projects where tenant-manager is included | 

#### DISCR_TOOL_USER_USERNAME / DISCR_TOOL_USER_PASSWORD

**DEPRECATED**

Credentials used to access read operations: list all databases in namespace and get database by
classifier, should be generated on DBaaS installation.

| Default                                                                                                                                         | Recommended                                                                                                              | 
|-------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------|
| <p>DISCR_TOOL_USER_USERNAME=*discr_tool_user* <p>DISCR_TOOL_USER_PASSWORD=*None* <p>You need to change that if you need to secure installation. | <p>You can generate these credentials during first DBaaS installation, save them in configuration and do not change them | 

## REMOVED

#### RUN_SMOKE_ELASTICSEARCH

**removed in 4.0.0**

Removed RUN_SMOKE_ELASTICSEARCH parameter as elasticsearch is not supported since DBaaS 4.0.0.

#### ELASTICSEARCH_DBAAS_ADAPTER_ADDRESS

**since 1.3.0**

**deprecated for > 1.4.0**

**removed in 2.3.0**

Elasticsearch adapter address, elasticsearch adapter should be reachable under that address from aggregator in runtime,
otherwise monitoring would have PROBLEM status and DBA operations with Mongo would not be available.

Although it is monitored, but if value is empty, elasticsearch considered not installed, and it does not cause status
lowering.

Since 1.4.0 this parameter would be used only once during migration.

| Default                                                                                              | Recommended                                                                                                                                                                                                                                                                                                                                                                                                | 
|------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| <p>**-** <p>By default aggregator does not expect elasticsearch to be installed and would not use it | <p>You need to check is elasticsearch required to be integrated to DBaaS in this cloud, check if version of installed cluster is supported and configure address in the form <p>`http://dbaas-elasticsearch-adapter.<ELASTICSEARCH_ADAPTER_PROJECT>:8080` <p>, where ELASTICSEARCH_ADAPTER_PROJECT is a name of elasticsearch cluster project in Openshift. For example in could be elasticsearch5-cluster |

#### ELASTICSEARCH_DBAAS_AGGREGATOR_USERNAME / ELASTICSEARCH_DBAAS_AGGREGATOR_PASSWORD

**since 1.3.0**

**deprecated for > 1.4.0**

**removed in 2.3.0**

Basic username and password authentication for mongo, postgres, and elasticsearch adapters. DBaaS Aggregator authorizes
with such credentials in specific DBaaS adapter. These parameters are used only for default adapters.

Since 1.4.0 this parameter would be used only once during migration.

| Default                                                                                                 | Recommended                                                                                                                                                                                                                                                                                                         | 
|---------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Default value is taken from **DBAAS_AGGREGATOR_USERNAME** and **DBAAS_AGGREGATOR_PASSWORD** parameters. | You have to be sure that DBaaS adapters have been installed. Also credentials between DBaaS Aggregator and specific DBaaS adapter are identical. Starting from DBaaS Release 1.4.0#Requirements DBaaS Aggregator is able to register DBaaS adapters and use basic credentials which were passed during registration | 

#### MONGO_DBAAS_AGGREGATOR_USERNAME / MONGO_DBAAS_AGGREGATOR_PASSWORD

**since 1.3.0**

**deprecated for > 1.4.0**

**removed in 2.3.0**

Basic username and password authentication for mongo, postgres, and elasticsearch adapters. DBaaS Aggregator authorizes
with such credentials in specific DBaaS adapter. These parameters are used only for default adapters.

Since 1.4.0 this parameter would be used only once during migration.

| Default                                                                                                 | Recommended                                                                                                                                                                                                                                                                                                         | 
|---------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Default value is taken from **DBAAS_AGGREGATOR_USERNAME** and **DBAAS_AGGREGATOR_PASSWORD** parameters. | You have to be sure that DBaaS adapters have been installed. Also credentials between DBaaS Aggregator and specific DBaaS adapter are identical. Starting from DBaaS Release 1.4.0#Requirements DBaaS Aggregator is able to register DBaaS adapters and use basic credentials which were passed during registration |

#### POSTGRES_DBAAS_AGGREGATOR_USERNAME / POSTGRES_DBAAS_AGGREGATOR_PASSWORD

**since 1.3.0**

**deprecated for > 1.4.0**

**removed in 2.3.0**

Basic username and password authentication for mongo, postgres, and elasticsearch adapters. DBaaS Aggregator authorizes
with such credentials in specific DBaaS adapter. These parameters are used only for default adapters.

Since 1.4.0 this parameter would be used only once during migration.

| Default                                                                                                 | Recommended                                                                                                                                                                                                                                                                                                         | 
|---------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Default value is taken from **DBAAS_AGGREGATOR_USERNAME** and **DBAAS_AGGREGATOR_PASSWORD** parameters. | You have to be sure that DBaaS adapters have been installed. Also credentials between DBaaS Aggregator and specific DBaaS adapter are identical. Starting from DBaaS Release 1.4.0#Requirements DBaaS Aggregator is able to register DBaaS adapters and use basic credentials which were passed during registration | 

#### DBAAS_AGGREGATOR_USERNAME / DBAAS_AGGREGATOR_PASSWORD

**deprecated for > 1.4.0**

**removed in 2.3.0**

Credentials used to access DBaaS adapters installed with databases. These credentials are specified during PostgreSQL
and MongoDB installation. During DBaaS installation- values should be obtained from DB installation logs/history.

Since 1.4.0 this parameter would be used only once during migration.

| Default                                                                                                                                                                                                                                                                                                                                                                | Recommended                                                                                                                                                                                                                                                                                                 | 
|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| <p>DBAAS_AGGREGATOR_USERNAME = **dbaas-aggregator** <p>DBAAS_AGGREGATOR_PASSWORD = **dbaas-aggregator** <p>You need to change that if you set not default credentials during installation of adapters in database cluster deploy configurations. <p>Note: until DBaaS 1.3.0 it is assumed that MongoDB and PostgreSQL adapters are installed with the same credentials | <p>Firstly define in what projects physical database clusters are installed. <p>Find their installation configurations and check what credentials has been set during last successful installations. These credentials should be identical (until 1.3.0) and you need to configure them in these parameters |

#### MONGO_DBAAS_ADAPTER_ADDRESS

**deprecated for > 1.4.0**

**removed in 2.3.0**

Mongo adapter address, Mongo adapter should be reachable under that address from aggregator in runtime, otherwise
monitoring would have PROBLEM status and DBA operations with Mongo would not be available

Smoke test DBaaS#AutosmokeDuringDeploy since 1.3.0 would test if Mongo as actually available and working during DBaaS
Aggregator deploy.

Since 1.4.0 this parameter would be used only once during migration.

| Default                                                                                                                                                                                                                         | Recommended                                                                                                                                                                  | 
|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| <p>**http://dbaas-mongo-adapter.mongo-cluster:8080** <p>You need to change it only if DBaaS Mongo Adapter installed is not in Mongo cluster, or default service name "dbaas-mongo-adapter" has been changed during installation | <p>`http://dbaas-mongo-adapter.<MONGO_ADAPTER_PROJECT>:8080` <p>MONGO_ADAPTER_PROJECT is a name of Mongo cluster project in Openshift. For example in could be mongo-cluster |

#### POSTGRES_DBAAS_ADAPTER_ADDRESS

**deprecated for > 1.4.0**

**removed in 2.3.0**

Postgres adapter address, should be reachable under that address from aggregator in runtime, otherwise monitoring would
have PROBLEM status and DBA operations with postgres would not be available.

Since 1.4.0 this parameter would be used only once during migration.

| Default                                                                                                                                                                                                                                          | Recommended                                                                                                                                                                                 | 
|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| <p>**http://dbaas-postgres-adapter.postgresql-dbaas:8080** <p>You need to change it only if DBaaS Postgres Adapter installed is not in postgresql-project, or default service name "dbaas-postgres-adapter" has been changed during installation | <p>`http://dbaas-postgres-adapter.<POSTGRES_ADAPTER_PROJECT>:8080` <p>POSTGRES_ADAPTER_PROJECT is a name of postgres cluster project in Openshift. For example in could be postgresql-dbaas | 

#### INSTALL_ADAPTERS

**deprecated since 1.2.0**

**removed in 1.5.0**

This parameter defines if adapters would be installed during DBaaS Aggregator deploy.

| Default   | Recommended                                                                                                                                                                                                                     | 
|-----------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **false** | <p>Installation of adapters with aggregator is DEPRECATED feature and should not be used after 1.2.0 release (including). <p>The value must not be equal "true" in versions > 1.0.0. <p>Feature was removed in versions > 1.4.0 | 

#### KMS_EXTERNAL_ADDRESS / KMS_INTERNAL_ADDRESS / KMS_ACCOUNT_USERNAME / KMS_ACCOUNT_PASSWORD

**removed in 3.9.0**

These parameters required for ability to encrypt and store database passwords in KMS. If these parameters are not
specified, the passwords will be encrypted and stored by DBaaS.

| Default                                                                                   | Recommended                                                                                                                                                                                                                                                                                                                                                    | 
|-------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| <p>KMS_EXTERNAL_ADDRESS <p>KMS_INTERNAL_ADDRESS                                           | <p>When installing DBaaS, you need to determine where databases passwords will be stored. <p>By default, DBaaS itself encrypts and stores passwords. If you want password management to be performed by the KMS service, you need to set these parameters. <p>KMS_EXTERNAL_ADDRESS - External KMS address <p>KMS_INTERNAL_ADDRESS - Internal cloud KMS address | 
| <p>KMS_ACCOUNT_USERNAME=`<dbaas-project-name>-<namespace>` <p>KMS_ACCOUNT_PASSWORD=`None` | You can set the desired username and password to access the KMS. During the deployment, DBaaS will create an account with the specified parameters or default credentials will be used.                                                                                                                                                                        |

#### MONITORING_INSTALL

**deprecated for > 3.17.0**

**removed in 3.20.0**

Flag to install podmonitor and grafana dashboard for DBaaS.

| Default | Recommended                                                                         | 
|---------|-------------------------------------------------------------------------------------|
| false   | Set to true if need to enable prometheus monitoring and install dashboard for DBaaS | 

#### MONGO_HOST / MONGO_DBAAS_AUTH_DB / MONGO_DBAAS_USER / MONGO_DBAAS_PASSWORD / DBAAS_MONGO_DATABASE

**deprecated in 3.8.0**

**removed in 3.22.0**

MONGO_HOST should be reachable from DBaaS Aggregator, where Mongo database cluster should accept connections on port
27017 .

DBaaS Aggregator would store in Mongo databases registration, information about backups etc.

Without Mongo, DBaaS Aggregator with versions < 3.0.0 would not work. In versions with 3.0.0 - 3.8.0 they are needed to
perform migration (if `MONGO_HOST` is presented and migration to PostgreSQL is not performed then it will be) and since
3.8.0 these parameters are deleted. So if you want to update DBaaS Aggregator from 2.x to 3.8.x or lastest you should do
it through the intermediate version 3.0.0 - 3.7.0.

The rest are credentials to create database in MongoDB, where DBaaS Aggregator would store data.

MONGO_DBAAS_USER - name of user that will be used by DBaaS itself to access internal registry.

MONGO_DBAAS_AUTH_DB - name of DB where user was located.

DBAAS_MONGO_DATABASE defines name of Mongo logical
database (https://docs.mongodb.com/manual/core/databases-and-collections/#create-a-database) where DBaaS would store
its data (like databases registration or collected backups registration).

Installation script would try to create this database during deploy using provided credentials. If this operation
fails - installation would be failed.

| Parameter                                                          | Default                                                                                                                                                                | Recommended                                                                                                                                                                                                                                                                                                                                               | 
|--------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| MONGO_HOST                                                         | <p>You need to specify the parameter if you need to perform migration procedure. If you do not have MongoDB you should not pass this parameter                         | This parameter is deprecated and is only needed for migration procedure from MongoDB to postgresql. In the next major release it will be deleted                                                                                                                                                                                                          | 
| <p>MONGO_DBAAS_AUTH_DB <p>MONGO_DBAAS_USER <p>MONGO_DBAAS_PASSWORD | <p>MONGO_DBAAS_AUTH_DB = **admin** <p>MONGO_DBAAS_USER = **dbaas** <p>MONGO_DBAAS_PASSWORD = **None**                                                                  | When installing DBaaS you need to define where it would store data. It's required for DBaaS to be able to reach Mongo database from it's container. <p>When you know where MongoDB is located, you need to define what's credentials of DBaaS user has been used during installation of Mongo cluster and use them during this installation               | 
| DBAAS_MONGO_DATABASE                                               | <p>**dbaas** <p>You should change it if you are deploying more than one DBaaS aggregators storing their data in this Mongo cluster and want their data to be separated | When installing DBaaS you need to define if there is another DBaaS already installed in same cloud, check what's Mongo cluster it configured to use and if some DBaaS already stores data in same Mongo cluster in default logical database, you need to change this name to avoid conflicts. If you miss this change, the results could be unpredictable |

#### ZOOKEEPER_ADDRESS

**since 2.2.0**

**removed in 4.0.0**

Default value: not exist

Format: `<zookeeper_service_name>.<zookeeper_namespace>:<port>`

This parameter specifies address of Zookeeper storage in cloud. DBaaS Aggregator uses this address for connect to
Zookeeper and store data (for example in replication mode).

#### DBAAS_ZOOKEEPER_REPLICATION

**since 2.2.0**

**removed in 4.0.0**

Default value: `false`

This parameter enables Zookeeper replication mechanism and should be pass together with **ZOOKEEPER_ADDRESS**, otherwise
DBaaS cannot join to Zookeeper storage. You can read more about DBaaS Zookeeper replication on page: _DBaaS Zookeeper
replication_.

#### DBAAS_AGGREGATOR_ADDRESS

**since 3.19.0**

**removed in 5.0.0**

Since 24.3 release version DBaaS ingress is removed and ingress url is no longer used (DBAAS_AGGREGATOR_ADDRESS
variable). Related doc:
[Ingress deprecation and replacement](../dev%20operations/Ingress%20deprecation%20and%20replacement.md)

Instead of DBaaS ingress url, all services should use DBaaS port-forward url (FWD_DBAAS_URL variable).

#### VAULT_INTEGRATION

*Note - this flag is introduced temporary and has temporary naming*
**since 3.6.0**

**deprecated for > 3.6.0**

**removed in 5.5.0**

Flag that turns on integration with Vault. When set to true - DBaaS starts to manage new DBs creation and management via
Vault. Before first DB is created in Vault (and if you haven't specified DBAAS_VAULT_MIGRATION=true) - this setting is
revertable. But when at least one DB is created with passwords managed by Vault - this can't be reverted. Vault
integration should be already turned on in every supported DB (currently - only PostgreSQL) managed by current DBaaS.

#### VAULT_ADDR

**since 3.6.0**

**removed in 5.5.0**

Vault URL (despite where it is installed - in cloud or provided as service). Is required only when Vault integration is
turned on see parameter VAULT_INTEGRATION.

#### VAULT_TOKEN

**since 3.6.0**

**removed in 5.5.0**

Valid vault token of power user (approle or root) that will be used during deployment process to register microservices-
DB owners in Vault. Token is used only during installation process and is not used in runtime. Thus - it is usually
short-lived and could expire in 30 minutes.

#### DBAAS_VAULT_MIGRATION

**since 3.6.0**

**removed in 5.5.0**

Defines if it is required to perform passwords migration from dbaas to Vault, otherwise existing DBs passwords will be
kept intact in DBaaS registry, only new DBs will be created with password managed by Vault.

#### VAULT_DISABLED_ADAPTERS_LIST

**since 3.14.0**

**removed in 5.5.0**

Comma separated set of physical database ids (adapters) to disable Vault integration for. Applicable only for physical
databases which does not support Vault database engine integration. When physical db id specified in this param, DBaaS
will store external/managed DBs passwords internally rather than in Vault Key-Value storage.

#### RUN_DBAAS_SMOKE / RUN_SMOKE_POSTGRES / RUN_SMOKE_MONGO / RUN_SMOKE_OPENSEARCH

**since 1.3.0**

**removed in 6.0.0**

Smoke tests is not supported anymore.
