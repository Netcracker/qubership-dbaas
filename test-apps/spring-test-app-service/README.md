# Spring Test App Service

A small Spring Boot workload used by DBaaS integration tests to validate mounted-secret resolution
in the **Spring** DBaaS PostgreSQL client path. It is the Spring counterpart of
[`go-test-app-service`](../go-test-app-service) and satisfies the same black-box HTTP contract.

The mounted-secret feature lives in `dbaas-client-base` (`com.netcracker.cloud:dbaas-client`). This
service is only the black-box Kubernetes consumer used to prove the end-to-end flow:

1. `InternalDatabase` provisions a PostgreSQL logical database.
2. `DatabaseSecretClaim` creates a Kubernetes Secret with `connectionProperties.json` and `metadata.json`.
3. The Helm chart mounts that Secret under `/etc/secrets/dbaas-secrets/<secretName>`.
4. `@EnableServiceDbaasPostgresql` resolves the mounted Secret (no REST to dbaas-aggregator),
   `@EnableFlywayPostgresql` runs the migration, and the service performs DML.

## Endpoints

- `GET /health`
- `GET /postgres/connection-properties`
- `GET /postgres/ping`
- `DELETE /postgres/items`
- `POST /postgres/items`  — body `{"name": "<string>"}`
- `GET /postgres/items`   — `{"items": [{"id", "name", "createdAt"}]}`

## How it resolves the database

`servicePostgresDatasource` (from `dbaas-client-postgres-starter`) is a lazy proxy. The first JDBC
call triggers `DatabasePool.createDatabase`, which checks the mounted secret **before** any REST
call. When the secret is present the connection is built from it and the aggregator is never
contacted.

The integration test makes that the explicit verification: it deploys the service with
`API_DBAAS_ADDRESS` pointed at an **unreachable** host. The `InternalDatabase`/`DatabaseSecretClaim`
are provisioned by the operator (via the real aggregator), but the service can only reach its
database through the mounted secret — so a successful insert/list proves the connection did not come
from REST (a REST call to a dead host cannot return one).

## Build & run

```bash
mvn -B package                       # produces target/spring-test-app-service.jar
java -jar target/spring-test-app-service.jar
```

The jar depends on the dbaas client snapshot that carries the mounted-secret feature
(`9.1.3-mounted-secret-SNAPSHOT`, pinned in `pom.xml`), published to GitHub Packages.

| Env var | Purpose | Default |
|---|---|---|
| `MICROSERVICE_NAME` | dbaas classifier `microserviceName` | `spring-test-app-service` |
| `MICROSERVICE_NAMESPACE` | dbaas classifier `namespace` | `default` |
| `API_DBAAS_ADDRESS` | REST fallback target (used only on a mounted-secret miss) | `http://dbaas-aggregator:8080` |
| `LOG_LEVEL` | root + dbaas log level | `INFO` |
| `JAVA_TOOL_OPTIONS` | JVM options (heap sizing) | — |

## Notes

- Pins **Spring Boot 4.0** to match the Spring line the dbaas client (`microservice-restclient 7.x`)
  is built against; a 3.x parent causes a `RestClientAutoConfiguration` bean clash.
- Excludes the JPA/Hibernate the starter declares at compile scope (this service uses only
  `JdbcTemplate`); leaving them on the classpath would let Spring Boot auto-configure JPA and
  eagerly touch the datasource at startup, breaking boot-without-aggregator.
- Enables the basic-auth RestTemplate flavor (`dbaas.restclient.resttemplate.basic-auth=true`) — a
  plain `RestTemplate` with no platform gateway/M2M dependency — so the service boots standalone.
- Runs on the same digest-pinned Java base image as dbaas-aggregator (`dbaas/Dockerfile` →
  `ghcr.io/netcracker/qubership-java-base-prof:25-alpine-2.3.3`); the static-binary
  `qubership-core-base` ships no JRE.

The integration test lives in
[`test-apps/test-apps-integration-tests`](../test-apps-integration-tests) as `SpringTestAppServiceIT`.
