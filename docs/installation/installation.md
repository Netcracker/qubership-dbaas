# DBaaS Aggregator Installation Guide

The document describes the installation process for DBaaS Aggregator.

* [Prerequisites](#prerequisites)
    * [PaaS Compatibility List](#paas-compatibility-list)
    * [Preinstalled Software](#preinstalled-software)
    * [Databases Compatibility Matrix](#databases-compatibility-matrix)
    * [Upgrade Specifics](#upgrade-specifics)
    * [Hardware Requirements](#hardware-requirements)
        * [Heap Memory Configuration](#heap-memory-configuration)
    * [Requirements](#requirements)
* [Installation](#installation)
    * [Create Namespace](#create-namespace)
    * [Configuration](#configuration)
        * [Multiple Physical Databases](#multiple-physical-databases)
        * [Deployment Parameters](#deployment-parameters)
    * [Installation Process](#installation-process)
    * [DBaaS 4.5+ upgrade procedure on OpenShift 4.14+](#dbaas-45-upgrade-procedure-on-openshift-414)

## Prerequisites

### PaaS Compatibility List

The following PaaS versions are supported:

| PaaS type                  | Versions      | Support type |
|----------------------------|---------------|--------------|
| kubernetes                 | 1.21.x-1.32.x | Full support |
| Openshift OCP (enterprise) | 4.11-4.18     | Full support |

### Preinstalled Software

The following software should be installed before DBaaS installation:

| Dependency | Version                                                                       |
|------------|-------------------------------------------------------------------------------|
| PostgreSQL | [PostgreSQL 14-17](https://github.com/Netcracker/pgskipper-operator/releases) |

### Databases Compatibility Matrix

Note that despite DBaaS is used to manage various DBs (Cassandra, MongoDB, etc.) - these DBs could be installed after
DBaaS installation.
This table contains minimal supported versions:

| Database type | Database version                                                                      |
|---------------|---------------------------------------------------------------------------------------|
| PostgreSQL    | [1.28.0-4](https://github.com/Netcracker/pgskipper-operator/releases)                 |
| MongoDB       | [1.21.3](https://github.com/Netcracker/qubership-mongodb-operator/releases)           |
| OpenSearch    | [0.3.1](https://github.com/Netcracker/qubership-opensearch/releases)                  |
| Cassandra     | [1.35.0](https://github.com/Netcracker/qubership-cassandra-operator/releases)         |
| Clickhouse    | [0.13.0-3](https://github.com/Netcracker/qubership-clickhouse-operator-helm/releases) |
| ArangoDB      | 0.20.0-3                                                                              |
| Redis         | [2.10.3](https://github.com/Netcracker/qubership-redis/releases)                      |

### Upgrade Specifics

- If you update from 3.17.0 or older version then migration procedure to v3 classifier will be performed. If there is
  incorrect classifier, update won't be performed. So, it's highly recommended to check microservices' classifiers and
  update to correct classifier by procedure: [Classifier v3 migration process](./Classifier%20v3%20migration%20process.md).  
  By incorrect classifier we mean a classifier which does contains such necessary fields as 'microserviceName',
  'isService' or 'isServiceDb', 'tenantId' in case of tenant database.

- If you upgrade from version 3.x.x to 4.x.x, then it's strongly recommended doing via 4.2.0 version as it allows to
  avoid
  some problems in case if there are databases with incorrect classifiers.

- If you're upgrading from DBaaS 4.4.0 or earlier versions in OpenShift environments, there are important notes to
  consider:
    - Maintenance Window Requirement: Installation on OpenShift necessitates a maintenance window.
    - Changes in Supported Entities: OpenShift-specific entities like DeploymentConfig and Router are no longer
      supported. After installation, it's essential to carry
      out [housekeeping procedures](#dbaas-45-upgrade-procedure-on-openshift-414).

### Hardware Requirements

Depending on installation mode (Development, Production) - different HW resources should be available for installation:

|              | CPU request | CPU limit | RAM request | RAM limit |
|--------------|-------------|-----------|-------------|-----------|
| Dev profile  | 100         | 2000      | 526         | 526       |
| Prod profile | 100         | 4000      | 710         | 710       | 

CPU is in millicores, RAM is in Mb.

#### Heap Memory Configuration

For Development environments with many namespaces you should set the sufficient -Xmx value in dbaas-aggregator
Deployment.

Refer to [Heap memory for H2 cache](./Heap%20memory%20for%20H2%20cache.md) for details.

### Requirements

1. PostgreSQL user with CREATEDB and public schema privilege is required. This user credentials should be either
   provided to DBaaS installation scripts or used to manually create registry database.
2. User for DBaaS deployment should have `administrator` role and `port-forward` grants in all namespaces where
   databases managed by DBaaS will be deployed (PostgreSQL, MongoDb, Cassandra. Redis, etc.) and project-admin grants
   to the project where dbaas-aggregator will be deployed.
3. DBaaS installation procedure requires specific label with key 'region' to be set up on nodes before deploy job
   execution. Default value of the label is 'database'. For example: 'region=database'. The label name and value can be
   customized with NODE_SELECTOR_DBAAS_KEY and REGION_DBAAS envs.

## Installation

This section describes both initial DBaaS installation and update. Some steps are optional - see note in every section.

### Create Namespace

**Note**: this step is required only for initial installation. Should be skipped for update installation.

The suggested namespace name for DBaaS Aggregator is `dbaas`.

### Configuration

#### Multiple Physical Databases

If you have several physical databases of the same type (for example you have two PostgreSQL clusters you want to use
with one DBaaS instance), then before DBaaS Aggregator installation you need to scale to 0 all DBaaS adapters of that
type, except one, which will be the default adapter of that type. (The logical databases will be created through there
by default). If you do not do this, then the default adapter will be the one which will be registered first.

Remember to scale all DBaaS adapters' pods back after DBaaS Aggregator installation has finished and the default adapter
is registered.

#### Deployment Parameters

**Important information**: You should create DBaaS-specific user manually with CREATEDB optional or pass PostgreSQL DBA
user credentials.  
If you do not do it and default POSTGRES_DBA_USER/POSTGRES_DBA_PASSWORD do not match then you will get an installation
fail.

All, except POSTGRES_DBAAS_USER/POSTGRES_DBAAS_PASSWORD, parameters are `optional`, so if you do not specify your own
values then the default values will be applied.

| Parameter name                                                                                                                            | Description                                                                                                                                                                                                                                                                                         | Default                                                                         |
|-------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------|
| [POSTGRES_HOST](./parameters.md#postgres_host)                                                                                            | Name of host to connect to PostgreSQL physical database.                                                                                                                                                                                                                                            | pg-patroni.postgresql                                                           |
| [POSTGRES_PORT](./parameters.md#postgres_port)                                                                                            | Port number to connect to the PostgreSQL physical database.                                                                                                                                                                                                                                         | 5432                                                                            | 
| [PRODUCTION_MODE](./parameters.md#production_mode)                                                                                        | If PRODUCTION_MODE is set to true, dropping databases in dbaas is not allowed.                                                                                                                                                                                                                      | true                                                                            |
| [NODE_SELECTOR_DBAAS_KEY](./parameters.md#node_selector_dbaas_key)                                                                        | It indicates a node region key where pods will be located                                                                                                                                                                                                                                           | region                                                                          |
| [REGION_DBAAS](./parameters.md#region_dbaas)                                                                                              | It indicates a node region where pods will be located                                                                                                                                                                                                                                               | database                                                                        |
| [priorityClassName](./parameters.md#priorityClassName)                                                                                    | Defines priority of the pod.                                                                                                                                                                                                                                                                        | None                                                                            |
| [CLOUD_TOPOLOGY_KEY](./parameters.md#cloud_topology_key)                                                                                  | Nodes' label to match for inner pod anti-affinity soft rule                                                                                                                                                                                                                                         | kubernetes.io/hostname                                                          |
| [DBAAS_DEFAULT_PHYSICAL_DATABASES_DISABLED](./parameters.md#dbaas_default_physical_databases_disabled)                                    | Disable default physical databases. Default physical database is used when dedicated rules for microservice and namespace are not found.                                                                                                                                                            | false                                                                           |
| [READONLY_CONTAINER_FILE_SYSTEM_ENABLED](./parameters.md#readonly_container_file_system_enabled)                                          | If READONLY_CONTAINER_FILE_SYSTEM_ENABLED is set to true, dbaas-aggregator will be deployed with read-only file system in its container.                                                                                                                                                            | false                                                                           |
| [MONITORING_ENABLED](./parameters.md#monitoring_enabled)                                                                                  | Flag to install podmonitor custom resource and grafana dashboard for DBaaS                                                                                                                                                                                                                          | true                                                                            |
| [DBAAS_OWN_PG_DB_CREATED_MANUALLY](./parameters.md#dbaas_own_pg_db_created_manually)                                                      | Setting this parameter to `true` disables automatic database creation. Allows to use user's own database.                                                                                                                                                                                           | false                                                                           |
| [USE_POSTGRES_PORT_FORWARD](./parameters.md#use_postgres_port_forward)                                                                    | Enable or disable PostgreSQL port-forward during DBaaS Aggregator installation. It is needed if Deployer does not have direct access to PostgreSQL database                                                                                                                                         | true                                                                            |
| [POSTGRES_DBA_USER/POSTGRES_DBA_PASSWORD](./parameters.md#postgres_dba_user--postgres_dba_password)                                       | PostgreSQL user with privileged rights (SUPERUSER). You should pass it if you do not specify `POSTGRES_DBAAS_USER`                                                                                                                                                                                  | postgres/password                                                               |
| [POSTGRES_DBAAS_USER/POSTGRES_DBAAS_PASSWORD](./parameters.md#postgres_dbaas_database_name--postgres_dbaas_user--postgres_dbaas_password) | PostgreSQL DBaaS-specific user. Should be created manually with `CREATEDB` optional or you can use `POSTGRES_DBA_USER` for creating such user automatically. In this case PG DBaaS user will have `dbaas_user_<namespace>` username and random password                                             | None <p>  `(Required if POSTGRES_DBA_USER/POSTGRES_DBA_PASSWORD not specified)` |
| [POSTGRES_DBAAS_DATABASE_NAME](./parameters.md#postgres_dbaas_database_name--postgres_dbaas_user--postgres_dbaas_password)                | PostgreSQL logical database where DBaaS will store its own data. If database does not exist, DBaaS Aggregator creates it automatically                                                                                                                                                              | `<NAMESPACE>`                                                                   |
| [DBAAS_RECREATE_DEPLOYMENT_STRATEGY](./parameters.md#dbaas_recreate_deployment_strategy)                                                  | Use recreate deployment strategy instead of rolling. It Ensure a more reliable migration process                                                                                                                                                                                                    | false                                                                           |
| [DBAAS_PREMATURE_REGISTRATION_ADAPTER_ADDRESSES](./parameters.md#dbaas_premature_registration_adapter_addresses)                          | Addresses of the adapters, that have to be registered as soon as DBaaS Aggregator starts. Addresses must be separated by comma. Example: `http://dbaas-postgres-adapter.postgresql-dbaas:8080,http://dbaas-mongo-adapter.mongo-cluster:8080`                                                        | None                                                                            |
| [DBAAS_DB_EDITOR_CREDENTIALS_USERNAME](./parameters.md#dbaas_db_editor_credentials_username--dbaas_db_editor_credentials_password)        | Username for role "dbaas-db-editor". This role can edit logical database registration in DBaaS store database, e.g. it is needed for API: update existing classifier.                                                                                                                               | dbaas-db-editor                                                                 |
| [DBAAS_DB_EDITOR_CREDENTIALS_PASSWORD](./parameters.md#dbaas_db_editor_credentials_username--dbaas_db_editor_credentials_password)        | Password for role "dbaas-db-editor". This role can edit logical database registration in DBaaS store database, e.g. it is needed for API: update existing classifier.                                                                                                                               | None                                                                            |
| [DBAAS_CLUSTER_DBA_CREDENTIALS_USERNAME](./parameters.md#dbaas_cluster_dba_credentials_username--dbaas_cluster_dba_credentials_password)  | Username for user "cluster-dba" used to authorize access to databases administration API by deployer and functional projects. Also "cluster-dba" is used by DBaaS adapters for registration. Same credentials should be used during installation of functional Cloud-Core-based and DBaaS adapters. | cluster-dba                                                                     |
| [DBAAS_CLUSTER_DBA_CREDENTIALS_PASSWORD](./parameters.md#dbaas_cluster_dba_credentials_username--dbaas_cluster_dba_credentials_password)  | Password for user "cluster-dba" used to authorize access to databases administration API by deployer and functional projects. Also "cluster-dba" is used by DBaaS adapters for registration. Same credentials should be used during installation of functional Cloud-Core-based and DBaaS adapters. | None                                                                            |
| [BACKUP_DAEMON_DBAAS_ACCESS_USERNAME](./parameters.md#backup_daemon_dbaas_access_username--backup_daemon_dbaas_access_password)           | Username for "dbaas-backup" user used to authorize access to backup collection and restoration by project backup daemon, should be generated on DBaaS installation or should be equal to the same value used during cloud-project-backup installation.                                              | backup-daemon                                                                   |
| [BACKUP_DAEMON_DBAAS_ACCESS_PASSWORD](./parameters.md#backup_daemon_dbaas_access_username--backup_daemon_dbaas_access_password)           | Password for "dbaas-backup" user used to authorize access to backup collection and restoration by project backup daemon, should be generated on DBaaS installation or should be equal to the same value used during cloud-project-backup installation.                                              | None                                                                            |
| [DBAAS_TENANT_USERNAME](./parameters.md#dbaas_tenant_username--dbaas_tenant_password)                                                     | Username for "dbaas-tenant" user used by tenant-manager to clean sandbox tenant. Same credentials should be used during installation of functional projects where tenant-manager is included.                                                                                                       | dbaas-tenant                                                                    |
| [DBAAS_TENANT_PASSWORD](./parameters.md#dbaas_tenant_username--dbaas_tenant_password)                                                     | Password for "dbaas-tenant" user used by tenant-manager to clean sandbox tenant. Same credentials should be used during installation of functional projects where tenant-manager is included.                                                                                                       | None                                                                            |

You can find more info about existing and removed parameters in [parameters.md](./parameters.md)

### Installation Process

You should prepare values.yaml file with all required deployment parameters and perform usual helm installation
to the desired namespace.

### DBaaS 4.5+ upgrade procedure on OpenShift 4.14+

**⚠️ Warning**  
**Upgrading procedure to DBaaS 4.5 and higher on OpenShift 4.14+ environment requires maintenance windows.**

Since DeploymentConfig is being deprecated with OpenShift 4.14, instead of Openshift specific entities (
DeploymentConfig, Route)
DBaaS will use Kubernetes ones (Deployment, Ingress). Therefore, if you are going to update DBaaS to 4.5 or higher
version, you must do the following steps:

1. Before updating OpenShift and DBaaS to a new version,
   scale down all DBaaS Aggregator pods in the namespace where you intend to update DBaaS.

```shell
    oc scale deploymentconfig dbaas-aggregator --replicas=0 -n "<namespace>"
```

2. Update DBaaS Aggregator to 4.5+ version. After updating, DBaaS should have both DeploymentConfig and Route entities
   along with the new Deployment and Ingress entities. Ensure that the `DeploymentConfig entity should have 0 replicas`,
   and all pods must be generated from DeploymentConfig

3. After updating DBaaS, perform the housekeeping procedure, which involves the removal of OpenShift-specific entities,
   such as DeploymentConfig and Route (see command below):

```shell
    oc delete deploymentconfig --namespace="<namespace>" dbaas-aggregator
    oc delete route --namespace="<namespace>" aggregator  
```

\* Instead of `<namespace>` provide namespace name where dbaas was updated.  
\* Instead of oc you may use kubectl

4. The final step is to update OpenShift to version 4.14 and above.
