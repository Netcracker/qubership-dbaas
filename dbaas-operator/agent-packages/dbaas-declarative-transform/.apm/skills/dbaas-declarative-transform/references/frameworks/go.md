# Go DBaaS clients

Resolve `go.mod`, replacements, and build source. Follow `NewDbaaSPool`, `ServiceDatabase`,
`TenantDatabase`, `GetOrCreateDb`, `GetConnection`, DB client wrappers, classifier functions,
`DbParams.Classifier`, and `BaseDbParams`. Method names do not prove scope when classifiers can be
overridden.

Confirm the resolved base client registers a provider which scans `/etc/secrets/dbaas-secrets`, parses
`metadata.json` and `connectionProperties.json`, and runs before REST fallback. Otherwise require an
upgrade or adapter. Match canonical classifier, lowercase type, and requested role. Preserve Mongo's
top-level `dbClassifier: default` in `extraKeys`.

## Proven baseline

The repository E2E baseline uses base client v3.5.3 and PostgreSQL client v4.4.3. Treat older versions
as unproven until their resolved source contains the mounted provider. Do not require these exact
versions when a newer resolved version preserves the contract.
