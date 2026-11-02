# AGENTS.md (dbaas)

`dbaas-aggregator` is a Quarkus REST service (`/api/v3/dbaas/*`, package `com.netcracker.cloud.dbaas`), layered as
`controller` → `service` → `dao`/`repositories` → JPA entities. It routes requests over HTTP to per-type database
adapters (PostgreSQL, MongoDB, Redis, Cassandra, OpenSearch, ClickHouse, ArangoDB). Registrations are
namespace-isolated and identified by a classifier. PostgreSQL is the system of record, H2 an in-memory read mirror.
`dbaas-integration-tests` are black-box tests (OkHttp) against a deployed cluster.

Full rules: [aggregator guidelines](../docs/development/aggregator.md) and
[testing and CI guidelines](../docs/development/testing-and-ci.md).

## Do

- Use constructor injection and `@ApplicationScoped` for new CDI beans; put `@Transactional` on service methods only.
- Map DTOs with MapStruct mappers in `mapper/` (`componentModel = "cdi"`); keep Lombok before MapStruct in
  `annotationProcessorPaths`.
- Name new Flyway migrations `V1.NNN__<Description>.sql` with the next free number; keep the H2 schema in sync for
  mirrored tables.
- Build `Database` / `DatabaseRegistry` test fixtures with `DatabaseBuilder`; name tests `test<Logic>_<Condition>`.
- Tag new `*IT` tests with DB-type and area tags when applicable.
- Update `docs/api/OpenAPI.json`, `docs/api/rest-api.md` and `docs/api/adapter-contract.yaml` with API changes.

## Don't

- Don't edit or rename existing Flyway migrations.
- Don't hand-write DTO converters when a MapStruct mapper covers the case.
- Don't add `@Transactional` to DAOs or REST controllers.

## Constraints

- Keep the v3 API backward compatible: the operator, client libraries and integration tests use it.
- Auth is Basic + Kubernetes service-account JWT; every endpoint needs `@RolesAllowed` (unannotated endpoints are
  denied).

## Testing

- Unit tests: JUnit and Mockito. Component tests: `@QuarkusTest` with Testcontainers PostgreSQL.
- Integration tests run against a deployed stack and are skipped by default (`skipIT=true`).

## Commands

```bash
mvn clean package -DskipTests -pl dbaas/dbaas-aggregator -am    # build the aggregator
mvn test -pl dbaas/dbaas-aggregator -Dtest=<ClassName>          # one test class
docker build -f dbaas/Dockerfile -t dbaas-aggregator:latest .
```
