# Go test app service

This HTTP test app exercises four PostgreSQL datasources from the Go DBaaS client:

- `/postgres`: service scope with the default role.
- `/postgres-admin`: service scope with the `admin` role.
- `/postgres-tenant`: tenant scope with the default role.
- `/postgres-tenant-admin`: tenant scope with the `admin` role.

Each datasource uses the same migrations. The tenant endpoints use the fixed tenant ID `acme`.

## Database resources

The Helm chart creates one service-scoped and one tenant-scoped `InternalDatabase`. It also creates four
`DatabaseSecretClaim` resources, one for each scope and role combination used by the datasources.

When `MOUNT_SECRETS` is enabled, the generated Secrets are mounted under
`/etc/secrets/dbaas-secrets/<secretName>`. The Go DBaaS client selects each Secret by classifier, database type, and
user role.

Keep the chart's `TENANT_ID` and `DATABASE_SECRET_USER_ROLE` values set to `acme` and `admin`; these values match the
fixed classifiers used by the app's tenant and administrative datasources.

## Configuration

| Environment variable | Purpose | Default |
|---|---|---|
| `HTTP_ADDR` | HTTP server listen address | `:8080` |
| `MICROSERVICE_NAME` | Microservice name used in DBaaS classifiers | `SERVICE_NAME` Helm value |
| `MICROSERVICE_NAMESPACE` | Namespace used in DBaaS classifiers | Pod namespace |
| `LOG_LEVEL` | Application and library log level | `info` |
| `DBAAS_AGENT` | DBaaS REST endpoint used when a mounted Secret does not match | `http://dbaas-aggregator.dbaas:8080` |
| `API_DBAAS_ADDRESS` | Aggregator endpoint used for direct Kubernetes M2M access | Empty |
| `KUBERNETES_M2M_ENABLED` | Enables direct aggregator access with a projected service-account token | `false` |
| `BASECLIENT_RETRY_MAX_ATTEMPTS` | Configures DBaaS base-client retry attempts | `0` |
| `BASECLIENT_RETRY_DELAY_MS` | Configures the DBaaS base-client retry delay in milliseconds | `10` |

## Endpoints

| Method and path | Behavior |
|---|---|
| `GET /health` | Reports readiness |
| `GET /postgres/ping` | Pings the service-scoped database |
| `GET /postgres/connection-properties` | Returns sanitized connection properties |
| `GET`, `POST`, or `DELETE /{datasource}/items` | Reads, creates, or deletes test rows for a datasource |
| `POST /postgres-admin/rotation-probe` | Executes one transactional credential-rotation probe |

`{datasource}` is one of the four datasource prefixes listed above.

## Credential-rotation probe

`POST /postgres-admin/rotation-probe` accepts:

```json
{ "probeId": "<unique-test-id>" }
```

The handler resolves the datasource through `GetSqlDb` on every request. It writes, reads, and deletes one row in a
single transaction, then returns HTTP 200 only after the transaction commits:

```json
{ "probeId": "<unique-test-id>", "status": "ok" }
```

The body must contain one JSON object with a nonblank `probeId` of at most 200 characters. The endpoint returns HTTP
400 for invalid input and HTTP 500 when datasource resolution or the transaction fails. Its response does not expose
connection properties or credentials.
