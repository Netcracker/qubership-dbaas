# Quarkus and BSS DBaaS clients

Use this reference for direct Qubership Quarkus DBaaS extensions and BSS wrappers. Read
`../contracts.md` for classifier and Secret identity rules and `../dynamic-topologies.md` for tenant,
shard, schema, bucket, or logical-to-physical routing.

## Resolve direct and transitive versions

Resolve the effective Maven dependency graph and all imported BOMs. Record the versions of
`dbaas-datasource-*`, `dbaas-common`, the base DBaaS client, and BSS adapters such as
`bsscq-dbaas-client-adapter` or `bsscq-datasource-dbaas`. A BSS artifact name does not prove which
DBaaS extension version it supplies.

Inspect the resolved JARs or sources for mounted-secret lookup, provider registration, Secret
path/files, classifier/type/role matching, and REST fallback. Classify otherwise-static identities
as `BLOCKED` when the resolved graph predates that support. State the required upgrade; do not
generate mounts and imply that an older client will consume them.

## Direct Quarkus extension patterns

Search production code for:

- CDI producers using `@Produces`, `@ApplicationScoped`, `@Singleton`, `@Named`, qualifiers, and
  alternatives;
- `DbaasQuarkusPostgresqlDatasourceBuilder`, `DbaaSDataSource`, `AgroalDataSource`,
  `DatabaseConfig`, `ServiceClassifierBuilder`, and tenant classifier builders;
- direct `DbaasClient.getOrCreateDatabase`, `DatabasePool`, and engine-specific client calls;
- `quarkus.dbaas.datasource.*`, `quarkus.dbaas.datasources.<name>.*`,
  `quarkus.dbaas.flyway.*`, and `quarkus.dbaas.postgresql.api.*` across profiles;
- standard Quarkus datasource/Flyway configuration that consumes or wraps a DBaaS datasource;
- legacy `DatabaseDeclaration` resources, rendered environment variables, and Secret mounts.

A common custom datasource is:

```java
@Produces
@Singleton
@Named(transactional)
DataSource transactional(DbaasQuarkusPostgresqlDatasourceBuilder builder) {
    return builder.newBuilder(
            new ServiceClassifierBuilder(baseClassifier)
                .withCustomKey(logicalDbName, transactional))
        .withSchema(schema)
        .withDatabaseConfig(databaseConfig)
        .build();
}
```

The emitted classifier contains standard service keys plus nested
`customKeys.logicalDbName=transactional`. `withSchema`, Agroal pool properties, and Flyway settings
remain application configuration. Map only `DatabaseConfig` creation parameters supported by the
CR contract.

The extension may also produce a default bean such as `@Named(SERVICE_DATASOURCE) AgroalDataSource`.
Inventory that default identity even when no application method calls `getOrCreateDatabase`
directly. Trace every named datasource independently and then deduplicate by canonical identity.

## BSS factory and topology patterns

Separately search BSS applications for `bsscq-datasource-dbaas`, `MultitenantDataSourceFactory`,
`InitDataSourceService`, `ShardingClient`, `createDataSource`, `createBucketsIfAbsent`,
`initShardDataSources`, `SERVICE_DATASOURCE`, and properties under `quarkus.bss.datasource.*` or
`bss.datasource.*`.

Inspect `use-dbaas-client`, `logical-to-physical-database-id-map`, `use-service-db`,
`init-tenant-on-start`, `default-db-name`, `create-shard-on-start`, and `shards`. These factories may
create schemas, buckets, shards, or tenant datasources after startup. Inventory those operations
separately from the database identity. Keep runtime routing and per-tenant initialization when CRs
cannot represent them.

Use `SUPPORTED` only when every tenant and placement input is finite and rendered at deployment
time. Use `NOT_SUPPORTED_DYNAMIC` for request/message context tenants. Use `BLOCKED` for physical
database IDs or logical-to-physical maps without a proven CR mapping.

## Configuration mapping

Trace property values through MicroProfile config, environment interpolation, profile overrides,
Helm values, and ConfigMaps. In particular resolve:

- `quarkus.dbaas.postgresql.api.runtime-user-role` to the claim role;
- `quarkus.dbaas.postgresql.api.db-prefix` to `InternalDatabase.spec.namePrefix`;
- database creation settings to `spec.settings` only when the target CR accepts their exact string
  representation;
- `quarkus.dbaas.datasource.schema`, JDBC/Agroal pool, Flyway, retry, and connection properties as
  application-only configuration.

Do not treat `quarkus.bss.datasource.dev-only.*` or standard JDBC development profiles as DBaaS
identities unless the production profile uses the same path.

## Legacy declarations and runtime proof

Compare existing `DatabaseDeclaration` classifiers against the actual builder output. Preserve
explicit versioning and initial-instantiation behavior. Block complex array, boolean, or nested
settings until their `map[string]string` encoding is proven for the target operator.

Exercise every named datasource because CDI may initialize it lazily. Prove migrations and DML with
REST unreachable, then verify retained REST fallback separately. Test CDI startup ordering and
credential rotation where supported.

## Proven baseline

The repository E2E baseline uses `com.netcracker.cloud.quarkus:dbaas-datasource-postgresql:10.1.2`
with Quarkus 3.33.1. It proves service and static-tenant classifiers with empty and admin roles,
Flyway, DML, mounted-secret resolution with REST unreachable, and REST fallback without mounts.
Treat this as evidence only for that resolved dependency graph. Do not transfer it to older direct
extensions or BSS wrappers without source proof or the same E2E.
