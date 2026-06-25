# Qubership DBaaS — Agent Guide

## Repository Overview

Dual-stack monorepo:

| Component                      | Language | Framework                        | Location                            |
|--------------------------------|----------|----------------------------------|-------------------------------------|
| **dbaas-aggregator**           | Java 21  | Quarkus 3.27                     | `dbaas/dbaas-aggregator/`           |
| **dbaas-operator**             | Go 1.26  | Kubebuilder / controller-runtime | `dbaas-operator/`                   |
| **dbaas-integration-tests**    | Java 21  | JUnit 5 + REST-assured           | `dbaas/dbaas-integration-tests/`    |
| **encryption-services-cipher** | Java 21  | —                                | `dbaas/encryption-services-cipher/` |
| **helm-templates**             | YAML     | Helm                             | `helm-templates/`                   |

The aggregator exposes the REST API (`/api/v3/dbaas/*`) that routes requests to database adapters. The operator
reconciles Kubernetes custom resources (`ExternalDatabase`, `InternalDatabase`, `DatabaseAccessPolicy` and others) and
calls the aggregator.

> **Go operator conventions** are fully documented in `dbaas-operator/AGENTS.md`. This file covers the Java aggregator
> and shared build/test workflows.

---

## Build Commands

### Java (Maven)

```bash
# Build all Java modules (skip tests)
mvn clean install -DskipTests

# Build and run unit tests
mvn clean install

# Build and run unit + integration tests
mvn verify -DskipIT=false

# Package only (no tests)
mvn clean package -DskipTests

# Build a single module
mvn clean install -DskipTests -pl dbaas/dbaas-aggregator -am
```

### Go (Make — from `dbaas-operator/`)

```bash
make build          # Compile operator binary
make test-unit      # Unit tests only (fast, no envtest)
make test           # Unit + controller integration tests
make test-e2e       # Full e2e in a Kind cluster
make lint           # Check only
make lint-fix       # Auto-fix style
make manifests      # Regenerate CRDs + RBAC from markers
make generate       # Regenerate DeepCopy methods
```

---

## dbaas-aggregator — Java / Quarkus Conventions

### Technology Stack

- **Runtime**: Java 21, Quarkus 3.27.3 (legacy-jar packaging)
- **REST**: RESTEasy (JAX-RS)
- **Persistence**: Hibernate ORM + Panache, Flyway migrations
- **Databases**: PostgreSQL (primary store), H2 (in-memory classifier cache)
- **DI**: Quarkus CDI
- **Build helpers**: Lombok 1.18, MapStruct 1.6
- **Resilience**: Failsafe 2.4 (retries), ShedLock 7.7 (distributed scheduling)
- **Auth**: Basic (JDBC) + Kubernetes JWT (SmallRye JWT)
- **Query**: RSQL Parser 2.1
- **Observability**: Prometheus (`/prometheus`), OpenAPI (`/swagger-ui`, `/v3/api-docs`), SmallRye Health

### Source Layout

```
dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/
├── config/           Configuration beans (H2, PostgreSQL, security)
├── connections/      Database connection handlers (DefaultConnectionHandler, CassandraConnectionHandler)
├── controller/       JAX-RS endpoint declarations
├── converter/        DTO ↔ entity converters (MapStruct; excluded from coverage)
├── dao/              Data access objects
├── dto/              Data transfer objects (excluded from coverage)
├── entity/           JPA entities — h2/ and pg/ sub-packages (excluded from coverage)
├── enums/            Shared enumerations (excluded from coverage)
├── exceptions/       Custom exception types (excluded from coverage)
├── logging/          Request/response logging utilities
├── mapper/           MapStruct mappers
├── monitoring/       Metrics and monitoring endpoints
├── repositories/     Panache / Spring Data repositories
├── rest/             REST endpoint implementations
├── rsql/             RSQL query support
├── security/         Auth mechanism implementations
├── serializer/       Custom Jackson serializers
├── service/          Business logic (the core layer — write tests here)
│   └── processengine/processes/  Multi-step database creation processes
└── utils/            Shared utility functions
```

### Lombok + MapStruct Rules

- Use `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor` on DTOs and entities.
- MapStruct mappers live in `mapper/` and are CDI `@ApplicationScoped` beans (`componentModel = "cdi"`).
- Never write hand-coded `toDto`/`fromDto` methods when a mapper annotation covers the case.
- The annotation processor order matters: Lombok must run before MapStruct. The POM already enforces this — do not
  reorder the `annotationProcessorPaths`.

### Flyway Migration Rules

- New migrations: `V<next_int>__<snake_case_description>.sql` (e.g., `V42__add_index_on_databases_namespace.sql`).
- Never edit or rename an existing migration file — Flyway validates checksums.
- H2 and PostgreSQL migrations are separate; keep them in sync when adding schema changes.
- Use `IF NOT EXISTS` / `IF EXISTS` guards for index and column operations.

### Quarkus CDI

- Prefer constructor injection over field injection (`@Inject` on constructor).
- Use `@ApplicationScoped` for stateless services and DAOs.
- Use `@Transactional` at the service layer, not in DAOs or controllers.
- Integration tests run with `@QuarkusTest`; do not use Spring's `@SpringBootTest`.

### Testing (Java)

- **Unit tests**: `src/test/java/**/*Test.java` — JUnit 5 + Mockito. Use `@ExtendWith(MockitoExtension.class)`.
- **Integration tests**: `dbaas/dbaas-integration-tests/` — REST-assured + Testcontainers PostgreSQL. Run with
  `mvn verify -DskipIT=false`.
- Coverage is measured by JaCoCo and reported to SonarCloud. The following packages are **excluded** from coverage
  requirements: `converter`, `dto`, `entity`, `enums`, `exceptions`.
- Name test methods in `test<mainTestedLogic>_<WhatAndUnderWhatCondition>` style (e.g.,
  `testMarkAsOrphan_shouldBeMarkedForDrop`).
- When constructing `Database` / `DatabaseRegistry` objects in tests, use `DatabaseBuilder` (and its inner
  `DatabaseRegistryBuilder`) from `com.netcracker.cloud.dbaas.utils.DatabaseBuilder`. Prefer its predefined constants (
  `PG_TYPE`, `TEST_NS`, etc.) over hard-coded literals. Never build these entities
  manually (setting individual fields by hand) when `DatabaseBuilder` covers the case.

---

## Docker Images

```bash
# Build aggregator image
docker build -f dbaas/Dockerfile -t dbaas-aggregator:latest .

# Build operator image (from repo root)
docker build -f dbaas-operator/Dockerfile -t dbaas-operator:latest .
```

---

## Git

Commit messages must follow **Conventional Commits** (`feat:`, `fix:`, `chore:`, `refactor:`, `docs:`, `test:`).

---

## What NOT to Do

- **Do NOT** rename or alter existing Flyway migration files.
- **Do NOT** write hand-coded DTO converters when MapStruct covers the case.
- **Do NOT** add `@Transactional` to DAOs or REST controllers — it belongs on the service layer.

## What to Do

- Add empty line at the end of all created files.
