# ExternalDatabase

`ExternalDatabase` registers a database that already exists in a DBMS with dbaas-aggregator, so services can find it
by classifier. The operator doesn't create the database.

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: pg-credentials
  namespace: my-namespace
stringData:
  db-user: app_user
  db-pass: s3cr3t
---
apiVersion: dbaas.netcracker.com/v1
kind: ExternalDatabase
metadata:
  name: my-postgres-external
  namespace: my-namespace
spec:
  operatorNamespace: dbaas-system
  classifier:
    microserviceName: my-service
    scope: service
  type: postgresql
  dbName: my_application_db
  connectionProperties:
    - role: admin
      credentialsSecretRef:
        name: pg-credentials
        keys:
          - key: db-user
            name: username
          - key: db-pass
            name: password
      extraProperties:
        sslMode: "require"
```

## Fields

The classifier is described in [Classifier](../DBaaS%20Operator.md#classifier).

| Field                                         | Mutable | Description                                                                                                                                      |
|-----------------------------------------------|:-------:|--------------------------------------------------------------------------------------------------------------------------------------------------|
| `classifier`                                  |   No    | Identity of the database                                                                                                                         |
| `type`                                        |   No    | Database type, such as `postgresql`                                                                                                              |
| `dbName`                                      |   No    | Name of the database in the DBMS                                                                                                                 |
| `connectionProperties[]`                      |   Yes   | One entry per role; at least one                                                                                                                 |
| `connectionProperties[].role`                 |   Yes   | Role name, such as `admin` or `readonly`                                                                                                         |
| `connectionProperties[].credentialsSecretRef` |   Yes   | A Secret in the same namespace. Each `keys` entry copies the Secret key `key` into the connection property `name`; `name` values must be unique. |
| `connectionProperties[].extraProperties`      |   Yes   | Other connection properties, such as `host`, `port` or `sslMode`. `role` and Secret values override keys of the same name.                       |

The namespace needs [Secret access](../installation/installation.md#secret-access) with `get`.

## Credential Changes

The operator re-reads the Secrets and registers the database again every `DBAAS_EXTERNAL_DATABASE_RESYNC_INTERVAL`
(`10m` by default). To apply a Secret change at once, change the `dbaas.netcracker.com/refresh` annotation:

```bash
kubectl annotate externaldatabase <name> dbaas.netcracker.com/refresh="$(date +%s)" --overwrite
```

## Reasons

Besides the [shared reasons](../status-and-troubleshooting.md#shared-reasons):

| Reason               | Condition     | Meaning                                                                                                                            |
|----------------------|---------------|------------------------------------------------------------------------------------------------------------------------------------|
| `DatabaseRegistered` | `Ready=True`  | The database is registered                                                                                                         |
| `SecretError`        | `Ready=False` | A referenced Secret or key is missing, empty or forbidden; retried. The `dbaas_secret_resolution_errors_total` metric shows which. |

`InvalidSpec` means `classifier.namespace` differs from `metadata.namespace`, or `keys` repeats a `name`.
`AggregatorRejected` with `409` means a database with this classifier exists and isn't externally managed.
