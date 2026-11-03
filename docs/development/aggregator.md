# Aggregator Guidelines

Rules for `dbaas/dbaas-aggregator` (package `com.netcracker.cloud.dbaas`).

## New Code and Legacy Code

Apply the rules below to new classes. In an existing file, follow the pattern the file already uses, and don't refactor
it as part of an unrelated change. Field `@Inject` and `@Transactional` on controllers or DAOs are legacy: keep them
where they are and don't copy them into new classes.

## Layering

```text
controller (JAX-RS) -> service (business logic, transactions) -> dao / repositories -> entity
```

- `controller/`, `controller/v3/`: endpoints; every path is a constant in `DbaasApiPath`.
- `service/`: business logic. `@Transactional` belongs on service methods only.
- `repositories/pg/`, `repositories/h2/`, `dao/jpa/`: data access.
- `dto/` and `mapper/`: request and response models, mapped with MapStruct (`componentModel = "cdi"`). Don't write a
  converter by hand when a mapper covers the case.
- `rest/`: MicroProfile REST clients to the adapters.

## CDI Beans

New beans are `@ApplicationScoped` with constructor injection, either an `@Inject` constructor or Lombok
`@RequiredArgsConstructor`. Producers go in `config/ServicesConfig`. Lombok must stay before MapStruct in
`annotationProcessorPaths`.

```java
@ApplicationScoped
@RequiredArgsConstructor
public class ExampleService {
    private final DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;

    @Transactional
    public void markForDrop(UUID databaseId) {
        ...
    }
}
```

## Recurring Patterns

- **Multi-step work** (Blue-Green database creation, backup then restore) runs as a `ProcessDefinition` of tasks in
  `service/processengine/`, so it survives a restart.
- **Scheduled jobs** use `@Scheduled` together with ShedLock `@SchedulerLock`, so only one replica runs each job.
- **Adapter calls** go through `DbaasAdapterRESTClientFactory`. Retries use MicroProfile `@Retry` on the client and
  Failsafe `RetryPolicy` from `utils/RetryPolicyUtil`.
- **Deleting a logical database** marks it `markedForDrop`; the adapter drops it later.

## Security

- Authentication accepts HTTP Basic (users from `users.json`) or a Kubernetes service-account token with audience
  `dbaas`. Both are handled by `config/security/BasicAndKubernetesAuthMechanism`.
- `quarkus.security.jaxrs.deny-unannotated-endpoints=true` is set, so every endpoint needs `@RolesAllowed`; an
  endpoint without it is denied.
- Connection passwords are encrypted with `service/PasswordCipher` before they are stored.

## REST API

- Namespace-scoped resources carry the namespace in the path (`/api/v3/dbaas/{namespace}/databases`); type-scoped ones
  carry the type (`/api/v3/dbaas/{type}/physical_databases`).
- A feature with its own lifecycle gets its own versioned root, such as `/api/backups/v1` or `/api/bluegreen/v1`.
- Keep the v3 API backward compatible: the operator, the client libraries and the integration tests depend on it.

When an endpoint changes, update `docs/api/OpenAPI.json` and `docs/api/rest-api.md`. A change to
`DbaasAdapterRestClientV2` regenerates `docs/api/adapter-contract.yaml` during the build; commit the regenerated file.

## Database

### PostgreSQL and the H2 Mirror

PostgreSQL is the system of record. An in-memory H2 database mirrors the tables of databases, classifiers and physical
databases. PostgreSQL `LISTEN`/`NOTIFY` (`service/event/listener/AbstractPgTableListener`) and a periodic full reload
(`service/PeriodicH2Reload`) keep it in sync.

- Write to PostgreSQL; read hot paths from the H2 repositories.
- PostgreSQL entities live in `entity/pg`, H2 entities in `entity/h2`, and the mapped superclass both sides extend in
  `entity/shared`. When a mirrored table changes, change the PostgreSQL migration, the H2 schema under
  `db/migration/h2` and the mapped superclass together.

### Migrations

- PostgreSQL migrations live in `src/main/resources/db/migration/postgresql`, named `V1.NNN__<Description>.sql` with
  the next free number.
- Java migrations, for data that SQL can't transform, live in `src/main/java/db/migration/postgresql` as
  `V1_NNN__<Description>.java`. They share the same number sequence.
- Never edit or rename an existing PostgreSQL migration: Flyway validates checksums.
- Migrations are forward-only. Fix a bad migration with a new one.
- The H2 schema under `db/migration/h2` is the exception. The mirror database is in-memory, so Flyway builds it from
  scratch on every aggregator start and no deployed schema has to catch up: edit the existing H2 file rather than
  adding a migration.
- Guard DDL with `IF NOT EXISTS` and `IF EXISTS`.

### Entities

- Tables are snake_case and singular (`database`, `physical_database`). The classifier table maps to
  `DatabaseRegistry`.
- Primary keys are `UUID`.
- Classifiers and other structured fields are `jsonb`, mapped with `AttributeConverter`s from `converter/`.
- Enums are stored as strings.
- Check the fetch type of a relation before you query over a collection; some relations are `EAGER`.

### Queries and Transactions

- Use Panache queries in the repositories. Debug listings use RSQL filters (`rsql/`).
- Paginate with Panache `.range(...)` or keyset-style `.page(0, limit)` with `sinceTs`/`sinceId`; follow the repository
  you are extending.
- Use `QuarkusTransaction.requiringNew()` for a write that must commit on its own.

## Errors

Throw an `ErrorCodeException` subclass with a code in `exceptions/ErrorCodes.java`, and map it with an
`ExceptionMapper` in `controller/error/`. Responses use the TMF error format; `DefaultExceptionMapper` handles anything
unmapped. Don't introduce another error format.

## Naming

- Controllers: `<Feature>ControllerV<n>`. Services: `<Feature>Service`. Mappers: `<Feature>Mapper` in `mapper/`.
- Exception mappers: `<Exception>ExceptionMapper` in `controller/error/`.

## Comments

Write self-describing code and comment only what the code can't say, such as the H2 mirror sync or a process-engine
step. Controllers carry Swagger `@Operation` and `@APIResponse` annotations.

## Configuration

Settings are read from `application.properties` as `${ENV:default}` placeholders, and the Helm chart in
`helm-templates/dbaas-aggregator` sets the variables. A new setting needs both, plus an entry in
`docs/installation/parameters.md` if users can change it.
