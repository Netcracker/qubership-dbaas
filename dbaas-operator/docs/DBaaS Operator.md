# DBaaS Operator

DBaaS Operator lets a service declare its databases, credentials, access policies and placement rules as Kubernetes
custom resources. It reconciles each resource by calling dbaas-aggregator and reports the outcome in the resource's
status.

- [Installation](installation/installation.md): prerequisites, Helm install, RBAC, Secret access
- [Parameters](installation/parameters.md): settings, authentication, startup flags
- [Status and troubleshooting](status-and-troubleshooting.md): phases, conditions, retries, events, common failures
- [DBaaS Operator Metrics](monitoring/DBaaS%20Operator%20Metrics.md)
- [Onboarding a service](howto/onboarding.md)

## Custom Resources

All resources are namespaced, belong to `dbaas.netcracker.com/v1` and share the `dbaas` category, so
`kubectl get dbaas -n <namespace>` lists all of them at once.

| Kind                                                          | Short name | Purpose                                                            |
|---------------------------------------------------------------|------------|--------------------------------------------------------------------|
| [`InternalDatabase`](resources/internal-database.md)          | `dbidb`    | Declares a logical database that dbaas-aggregator provisions       |
| [`ExternalDatabase`](resources/external-database.md)          | `dbedb`    | Registers a database that already exists                           |
| [`DatabaseSecretClaim`](resources/database-secret-claim.md)   | `dbdsc`    | Writes a database's credentials into a Secret and keeps it current |
| [`DatabaseAccessPolicy`](resources/database-access-policy.md) | `dbdap`    | Declares which roles microservices get on the databases            |
| [`MicroserviceBalancingRule`](resources/balancing-rules.md)   | `dbmbr`    | Places the databases of listed microservices on physical databases |
| [`NamespaceBalancingRule`](resources/balancing-rules.md)      | `dbnbr`    | Places the databases of a namespace on physical databases          |
| [`PermanentBalancingRule`](resources/balancing-rules.md)      | `dbpbr`    | Places the databases of listed namespaces permanently              |
| [`PhysicalDatabase`](resources/physical-database.md)          | `dbpdr`    | Registers a physical database and its adapter                      |

## Architecture

```text
┌───────────────────────────────────────────────────────────────────┐
│ dbaas-operator pod (cluster-wide)                                 │
│                                                                   │
│  One controller per kind                                          │
│  Rotation poller (leader only): reads the changed-databases feed  │
│  and wakes the matching DatabaseSecretClaim resources             │
└───────────────────────────────────────────────────────────────────┘
        │  outbound only: HTTP Basic or a service-account token
        ▼
  dbaas-aggregator  ──▶  database adapters
```

- The operator watches its resources in every namespace. All its calls to dbaas-aggregator are outbound; it listens
  only for `/metrics` and the health probes.
- Credential changes made on the aggregator side reach `DatabaseSecretClaim` Secrets through the rotation poller.
- The operator doesn't watch Secrets. `ExternalDatabase` and `PhysicalDatabase` re-read the Secrets they reference on
  a periodic resync, so a changed password applies within one resync interval. To apply it at once, change the
  resource's `dbaas.netcracker.com/refresh` annotation. See [Parameters](installation/parameters.md#settings).

## Operator Assignment

Every resource names the operator that owns it in `spec.operatorNamespace`. An operator reconciles a resource only
when that value equals its own namespace (`CLOUD_NAMESPACE`), and leaves other resources untouched, without a status
or an event. Several operators can therefore share a cluster.

`spec.operatorNamespace` is immutable on almost all resources. To move a resource to another operator, delete it
and create it again.

## Immutable Identity

The fields that identify what a resource points at (the classifier, the database type, the target Secret name) are
immutable after creation, enforced by CRD validation rules. To point a resource at a different database or Secret,
delete it and create a new one. Each resource page lists its immutable fields.

## Classifier

`InternalDatabase`, `ExternalDatabase` and `DatabaseSecretClaim` identify a database by a classifier:

| Field              |       Required       | Notes                                                                               |
|--------------------|:--------------------:|-------------------------------------------------------------------------------------|
| `microserviceName` |         Yes          | The microservice that owns the database                                             |
| `scope`            |         Yes          | `service` or `tenant`                                                               |
| `tenantId`         | With `scope: tenant` | The tenant; see [tenant databases](resources/internal-database.md#tenant-databases) |
| `namespace`        |          No          | Must equal `metadata.namespace` when set; defaults to it when absent. A `DatabaseSecretClaim` may name another namespace in its [composite structure](resources/database-secret-claim.md#cross-namespace-access). |
| `customKeys`       |          No          | Any additional identity keys, sent nested under `customKeys`                        |
| `extraKeys`        |          No          | Any additional identity keys, sent at the top level of the classifier               |

`customKeys` and `extraKeys` accept the same keys and values of any JSON type, such as `logicalDBName` or `region`.
They differ only in where the keys land in the classifier sent to dbaas-aggregator, so pick the one that matches how
the service's dbaas-client builds its classifier.

### Classifier → Aggregator Wire Mapping

The operator sends the classifier to dbaas-aggregator as one JSON object:

- `microserviceName`, `scope`, and `namespace` and `tenantId` when set, go at the top level.
- `customKeys` stays a nested object under the `customKeys` key.
- Each `extraKeys` entry goes at the top level, next to `microserviceName`.
- Values keep their JSON type: a number stays a number and an object stays an object.

`extraKeys` may not contain `microserviceName`, `scope`, `namespace`, `tenantId` or `customKeys`; the resource is
rejected with reason `InvalidSpec`.

```yaml
classifier:
  microserviceName: my-service
  scope: service
  namespace: my-namespace
  customKeys:
    logicalDBName: configs
  extraKeys:
    region: { name: us-east, az: a }
```

```json
{
  "microserviceName": "my-service",
  "scope": "service",
  "namespace": "my-namespace",
  "customKeys": {
    "logicalDBName": "configs"
  },
  "region": {
    "name": "us-east",
    "az": "a"
  }
}
```

dbaas-aggregator compares the whole object to find a database. Every client of the same database, including the
service's dbaas-client, must send the same keys and values, or the database isn't found.

The classifier is immutable as a whole: after creation you can neither add an optional field, such as an explicit
`namespace` equal to `metadata.namespace`, nor remove one. Set the fields you want at creation time.
