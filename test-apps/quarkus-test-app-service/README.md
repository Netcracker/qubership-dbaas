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

## Integration test phases

The `Sample Services Integration Tests` workflow deploys this service three times against the same
black-box contract, toggling how the DBaaS connection is resolved:

- **Pass A — mounted secret**: secrets mounted, `API_DBAAS_ADDRESS` unreachable → a working round-trip
  can only come from the mount.
- **Pass B — REST fallback**: no mount, `API_DBAAS_ADDRESS` at the `dbaas-agent` stub → the client
  resolves each database over REST through the agent (Basic auth).
- **Pass C — direct M2M**: no mount, `API_DBAAS_ADDRESS` at the real aggregator, `M2M_ENABLED=true`.
  A `dbaas`-audience projected token is mounted at `/var/run/secrets/tokens/dbaas/token`; clearing the
  aggregator basic-auth creds makes `DbaasClientProducer` select the M2M client, which calls the
  aggregator directly with that token as a Bearer — no dbaas-agent.

The `quarkus_test_app_items` table is created by the dbaas Flyway integration
(`MigrationService.migrate`) from `classpath:db/migration`, run lazily on the first request.
`baseline-version=0` lets `V1` apply on top of the (non-empty) dbaas-provisioned schema.

## Build & run

```bash
mvn -B package          # produces target/quarkus-app/quarkus-run.jar (fast-jar)
java -jar target/quarkus-app/quarkus-run.jar
```

The build depends on the dbaas Quarkus extension snapshot that carries the mounted-secret feature
(`com.netcracker.cloud.quarkus:dbaas-datasource-postgresql:10.1.3-mounted-secret-SNAPSHOT`), published
to GitHub Packages.

| Env var | Purpose | Default |
|---|---|---|
| `MICROSERVICE_NAME` | dbaas classifier `microserviceName` | `quarkus-test-app-service` |
| `MICROSERVICE_NAMESPACE` | dbaas classifier `namespace` | `default` |
| `API_DBAAS_ADDRESS` | REST target: dbaas-agent (Pass B) or the aggregator directly (Pass C) | `http://dbaas-aggregator:8080` |
| `KUBERNETES_M2M_ENABLED` | Pass C: call the aggregator directly with a `dbaas`-audience projected token | `false` |
| `QUARKUS_DBAAS_API_AGGREGATOR_USERNAME` / `_PASSWORD` | cleared in Pass C so the M2M client is selected | `dbaas` / `dbaas` |
| `LOG_LEVEL` | log level | `INFO` |

The `http://dbaas-aggregator:8080` default above is the standalone binary's fallback. The Helm chart
sets `API_DBAAS_ADDRESS` to a namespaced in-cluster host (`http://dbaas-aggregator.dbaas:8080` by
default): the `InternalDatabase` and `DatabaseSecretClaim` templates read its second DNS label as
`spec.operatorNamespace`, so the chart value must keep the `<service>.<namespace>` form and a short
host without a namespace label fails chart rendering.

Runs on the same digest-pinned Java base image as dbaas-aggregator
(`ghcr.io/netcracker/qubership-java-base-prof`).

The integration test lives in
[`test-apps/test-apps-integration-tests`](../test-apps-integration-tests) as `QuarkusTestAppServiceIT`.
