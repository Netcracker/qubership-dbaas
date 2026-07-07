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
