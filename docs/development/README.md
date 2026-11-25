# Development Guidelines

Conventions for contributing to qubership-dbaas.

- [Aggregator](aggregator.md): code rules, layering, security, REST API, database, error handling
- [Testing and CI](testing-and-ci.md): test levels, integration-test tags, CI, deployment
- [Backup & Restore V2 design](backup-and-restore-v2.md): internal behavior, data model, sequence diagrams, adapter
  and aggregator requirements
- [dbaas-operator/AGENTS.md](../../dbaas-operator/AGENTS.md): Go operator conventions

## Technology Stack

- **dbaas-aggregator:** Java, Quarkus (RESTEasy, Hibernate ORM Panache, CDI, Flyway), Maven, Lombok, MapStruct
- **dbaas-operator:** Go, Kubebuilder and controller-runtime, Make
- **Persistence:** PostgreSQL as the system of record, H2 in-memory as a read mirror
- **Tooling:** GitHub Actions, golangci-lint, Renovate, Docker, Helm

## Architecture

The repository holds two deployables, both installed with Helm:

- **dbaas-aggregator** is a layered REST service (`/api/v3/dbaas/*`). It keeps the registry of logical databases and
  routes create, delete, backup and restore requests over HTTP to per-type adapters (PostgreSQL, MongoDB, Redis,
  Cassandra, OpenSearch, ClickHouse, ArangoDB). Each logical database belongs to a namespace and is identified by a
  classifier.
- **dbaas-operator** reconciles DBaaS custom resources (`ExternalDatabase`, `InternalDatabase`,
  `DatabaseAccessPolicy` and others) and calls the aggregator API.

Writes go to PostgreSQL. Reads are served from the H2 mirror, which PostgreSQL change notifications and a periodic full
reload keep in sync.

## Dependencies and New Patterns

Discuss new runtime dependencies, build tooling, architectural patterns and major framework upgrades with the
maintainers before you introduce them. Some libraries are deliberately kept on old versions; don't upgrade them as a
side effect of other work.
