# Go DBaaS clients

Use this reference for Go applications using Qubership DBaaS clients. Read
`../contracts.md` for classifier and Secret identity rules and `../dynamic-topologies.md` whenever
tenant context, shards, schemas, buckets, or logical database names are involved.

Resolve `go.mod`, replacements, and build source. Follow `NewDbaaSPool`, `ServiceDatabase`,
`TenantDatabase`, `GetOrCreateDb`, `GetConnection`, DB client wrappers, classifier functions,
`DbParams.Classifier`, and `BaseDbParams`. Method names do not prove scope when classifiers can be
overridden.

Confirm the resolved base client registers a provider which scans `/etc/secrets/dbaas-secrets`, parses
`metadata.json` and `connectionProperties.json`, and runs before REST fallback. Otherwise require an
upgrade or adapter. Match canonical classifier, lowercase type, and requested role. Preserve Mongo's
top-level `dbClassifier: default` in `extraKeys`.

## Resolve version evidence

Treat the target service's `go.mod`, `go.sum`, replacements, and resolved module graph as the source
of truth. Do not copy a client version from this skill. When mounted-secret support is absent, consult
the upstream [base-client releases](https://github.com/Netcracker/qubership-core-lib-go-dbaas-base-client/releases)
and the relevant engine-client release page, choose a compatible candidate in the consumer's
`go.mod`, resolve the graph again, and prove the mounted provider and REST fallback. An E2E result is
evidence only for the exact resolved graph that was tested; it is not a minimum-version guarantee.
