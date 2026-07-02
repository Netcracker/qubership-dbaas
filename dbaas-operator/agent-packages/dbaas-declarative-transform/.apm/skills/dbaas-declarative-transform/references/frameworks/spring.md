# Spring DBaaS clients

Use this reference for Spring Boot applications using Qubership DBaaS Java clients. Read
`../contracts.md` for classifier and Secret identity rules and `../dynamic-topologies.md` whenever
tenant context, shards, schemas, buckets, or logical database names are involved.

## Resolve the effective client first

Resolve the Maven or Gradle dependency graph and imported BOMs. Record the effective versions of
the base client and every engine starter. Do not infer mounted-secret support from an annotation,
framework name, sibling checkout, or newer source branch.

Search the resolved JARs or sources for the mounted-secret provider, its registration in the
`DatabasePool` provider chain, the expected Secret path/files, classifier/type/role matching, and
REST fallback. If that path is absent, classify otherwise-static identities as `BLOCKED` pending a
client upgrade or a proven non-provisioning adapter.

## Find every datasource and client

Search production code and configuration for:

- `@EnableDbaasDefault`, `@EnableServiceDbaasPostgresql`, `@EnableTenantDbaasPostgresql`, and the
  corresponding MongoDB, Cassandra, OpenSearch, ClickHouse, Redis, and ArangoDB annotations;
- `DatabasePool`, `DbaasClassifierFactory`, `DbaaSChainClassifierBuilder`,
  `newServiceClassifierBuilder`, and `newTenantClassifierBuilder`;
- `DbaasProxyDataSource`, `DbaasPostgresProxyDataSource`, engine clients, and direct
  `getOrCreateDatabase`/`getConnection` calls;
- `DatabaseConfig.builder()` calls, especially `userRole`, `dbNamePrefix`, `databaseSettings`, and
  custom classifier keys;
- `@Bean`, `@Primary`, `@Qualifier`, conditional/profile annotations, lazy beans, and manually
  constructed clients;
- `dbaas.api.*`, `dbaas.postgres.*`, named datasource, Hikari, Flyway, Liquibase, JPA, and schema
  configuration in every active profile and rendered ConfigMap;
- legacy `DatabaseDeclaration` resources and their settings, versioning, and initial-instantiation
  behavior.

Annotations enable infrastructure; they do not identify every logical database. Treat each proxy
datasource/client constructor and each distinct classifier builder as a separate call path until
canonical identity comparison proves otherwise.

## Trace common construction patterns

For a proxy datasource such as:

```java
new DbaasPostgresProxyDataSource(
    databasePool,
    classifierFactory.newTenantClassifierBuilder()
        .withCustomKey(LOGICAL_DB_NAME, configs),
    DatabaseConfig.builder()
        .userRole(properties.getRuntimeUserRole())
        .dbNamePrefix(properties.getDbPrefix())
        .databaseSettings(settings)
        .build())
```

Record PostgreSQL as the type, preserve `logicalDbName` inside nested `customKeys`, resolve the role
and prefix from active properties, and trace how the tenant builder obtains `tenantId`.
`DbaasProxyDataSource` can defer provisioning until first use, so application startup alone is not
proof that a datasource was exercised.

Inspect custom `DbaasClassifierFactory` subclasses. A factory may read `TenantContext` dynamically,
force a configured default tenant, or override standard keys. Classify each resulting identity:

- use `SUPPORTED` only for a finite tenant rendered at deployment time;
- use `NOT_SUPPORTED_DYNAMIC` when request, message, scheduler, or thread context supplies the
  tenant;
- split mixed behavior so static tenants can migrate while runtime tenants keep REST fallback.

Treat named beans, primary beans, JPA entity-manager factories, transaction managers, Flyway or
Liquibase instances, and health checks as consumers of the datasource rather than new database
identities unless they construct another classifier.

## Map legacy declarations

When the chart already contains `kind: DBaaS` / `subKind: DatabaseDeclaration`, use it as evidence,
not as the sole source of truth. Compare its classifier, type, settings, `versioningConfig`, and
`initialInstantiation` with the Java request. Convert only after both agree. Preserve explicit
versioning and clone behavior in the new `InternalDatabase`; do not silently remove it.

The current `InternalDatabase.spec.settings` accepts string values. If a legacy declaration or Java
settings object contains arrays, booleans, or nested objects, mark the identity `BLOCKED` until the
target operator/aggregator defines the exact string encoding. Never stringify complex settings by
guessing.

## Prove runtime behavior

Mount one generated Secret for each requested role into every consuming application container.
Exercise lazy datasources, migrations, and one database operation per identity. With the REST
endpoint deliberately unreachable, prove a mounted-secret hit and successful DML. Then test the
retained REST path separately for unsupported dynamic identities. Include credential rotation when
the resolved client claims rotation support.

## Proven baseline

The repository E2E baseline uses Spring DBaaS client 9.1.4 with Spring Boot 4.0.6. It proves service
and static-tenant classifiers with empty and admin roles, Flyway, DML, mounted-secret resolution with
REST unreachable, and REST fallback without mounts. Treat this as evidence only for that resolved
dependency graph. Older versions and different wrappers remain unproven.
