# Quarkus Test App Service

A small Quarkus workload used by DBaaS integration tests to validate mounted-secret resolution in
the **Quarkus** DBaaS PostgreSQL client path (the `dbaas-datasource` extension). It is the Quarkus
counterpart of [`go-test-app-service`](../go-test-app-service) /
[`spring-test-app-service`](../spring-test-app-service) and satisfies the same black-box HTTP
contract.

The mounted-secret feature lives in `core-quarkus-extensions/dbaas-client`
(`MountedSecretConnectionSource` + a file-backed `LogicalDbProvider`). This service is only the
black-box Kubernetes consumer used to prove the end-to-end flow:

1. `InternalDatabase` provisions a PostgreSQL logical database.
2. `DatabaseSecretClaim` creates a Secret with `connectionProperties.json` and `metadata.json`.
3. The Helm chart mounts that Secret under `/etc/secrets/dbaas-secrets/<secretName>`.
4. The dbaas-datasource provider resolves the mounted Secret (no REST), and the service creates its
   table and performs DML.

## Endpoints

- `GET /health`
- `GET /postgres/connection-properties`
- `GET /postgres/ping`
- `DELETE /postgres/items`
- `POST /postgres/items` — body `{"name": "<string>"}`
- `GET /postgres/items` — `{"items": [{"id", "name", "createdAt"}]}`

## How it resolves the database

The service injects the extension's `@Named("serviceDataSource") AgroalDataSource`. On the first
connection the dbaas provider chain is consulted: the file-backed mounted-secret provider resolves
the database from `/etc/secrets/dbaas-secrets` **before** the agent provider (REST). The integration
test deploys the service with `API_DBAAS_ADDRESS` pointed at an **unreachable** host, so a successful
insert/list proves the connection came from the mounted secret, not REST.

The `quarkus_test_app_items` table is created lazily via `CREATE TABLE IF NOT EXISTS` on the
dbaas-resolved connection (the dbaas Flyway integration is exercised by the Spring service).

## Build & run

```bash
mvn -B package          # produces target/quarkus-app/quarkus-run.jar (fast-jar)
java -jar target/quarkus-app/quarkus-run.jar
```

The build depends on the dbaas Quarkus extension snapshot that carries the mounted-secret feature
(`com.netcracker.cloud.quarkus:dbaas-datasource-postgresql:10.1.1-SNAPSHOT`), published to GitHub
Packages.

| Env var | Purpose | Default |
|---|---|---|
| `MICROSERVICE_NAME` | dbaas classifier `microserviceName` | `quarkus-test-app-service` |
| `MICROSERVICE_NAMESPACE` | dbaas classifier `namespace` | `default` |
| `API_DBAAS_ADDRESS` | REST fallback target (used only on a mounted-secret miss) | `http://dbaas-aggregator:8080` |
| `LOG_LEVEL` | log level | `INFO` |

Runs on the same digest-pinned Java base image as dbaas-aggregator
(`ghcr.io/netcracker/qubership-java-base-prof`).

The integration test lives in
[`test-apps/test-apps-integration-tests`](../test-apps-integration-tests) as `QuarkusTestAppServiceIT`.
