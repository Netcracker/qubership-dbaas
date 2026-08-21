# Go Test App Service

A small Go workload used by DBaaS integration tests to validate mounted-secret resolution in the Go DBaaS PostgreSQL client path.

The mounted-secret feature lives in `qubership-core-lib-go-dbaas-base-client`. This service is only the black-box Kubernetes consumer used to prove the end-to-end flow:

1. `InternalDatabase` provisions a PostgreSQL logical database.
2. `DatabaseSecretClaim` creates a Kubernetes Secret with `connectionProperties.json` and `metadata.json`.
3. The Helm chart mounts that Secret under `/etc/secrets/dbaas-secrets/<secretName>`.
4. The Go DBaaS PostgreSQL client resolves the mounted Secret, runs migrations, and performs DML.

## Endpoints

- `GET /health`
- `GET /postgres/connection-properties`
- `GET /postgres/ping`
- `DELETE /postgres/items`
- `POST /postgres/items`
- `GET /postgres/items`
- `POST /postgres-admin/rotation-probe`

The `/postgres-admin/*` endpoints use the service-scoped `admin` datasource; `/postgres-tenant/*` and
`/postgres-tenant-admin/*` cover the tenant quadrants with the same `items` contract.

## Credential rotation probe

`POST /postgres-admin/rotation-probe` writes, reads back, and deletes one row in a single
transaction, and answers 200 only if it commits:

```json
{ "probeId": "<unique-test-id>" }
```

```json
{ "probeId": "<unique-test-id>", "status": "ok" }
```

It answers 400 for a missing, blank, or oversized `probeId`, and 500 when the datasource or the
transaction fails. The response carries no connection properties, username, or password, so a failing
test can log it as it stands.

`GoPostgresSecretRotationIT` calls this before and after rotating the PostgreSQL credential. The
probe resolves its datasource through `GetSqlDb` on every request, which is where the client detects
a stale pool and re-reads the mounted Secret. Resolving it once at startup, or connecting with
`FindConnectionProperties` plus `pgx.Connect`, would bypass the behavior under test.

## Integration test phases

The `Sample Services Integration Tests` workflow deploys this service three times against the same
black-box contract, toggling how the DBaaS connection is resolved:

- **Pass A — mounted secret**: secrets mounted, aggregator URL unreachable → a working round-trip can
  only come from the mount.
- **Pass B — REST fallback**: no mount, `DBAAS_AGENT` at the `dbaas-agent` stub → the client resolves
  each database over REST through the agent.
- **Pass C — direct M2M**: no mount, `M2M_ENABLED=true`, `API_DBAAS_ADDRESS` at the real aggregator. A
  `dbaas`-audience projected token is mounted at `/var/run/secrets/tokens/dbaas/token`;
  `restclient.NewDbaasRestClient()` sends it as a Bearer straight to the aggregator — no dbaas-agent.

A fourth pass, **PostgreSQL rotation**, reuses the Pass A deployment: it rotates the `admin`
credential through the DBaaS password-change API, terminates the database session through the
[database proxy test stack](../database-proxy-test-stack), and requires this service to reconnect
within 120 seconds without its pod restarting.

| Env var | Purpose | Default |
|---|---|---|
| `MICROSERVICE_NAME` / `MICROSERVICE_NAMESPACE` | dbaas classifier identity | service name / `default` |
| `DBAAS_AGENT` | agent URL (koanf `dbaas.agent`), used in Pass B | `http://dbaas-aggregator:8080` |
| `API_DBAAS_ADDRESS` | direct aggregator URL (koanf `api.dbaas.address`), used in Pass C | — |
| `KUBERNETES_M2M_ENABLED` | Pass C: authenticate the aggregator with a projected `dbaas` token | `false` |

The integration tests live in
[`test-apps/test-apps-integration-tests`](../test-apps-integration-tests) as `GoTestAppServiceIT` and
`GoPostgresSecretRotationIT`.
