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

## Custom logical DB providers (issue #776)

`NewDbaaSPool`/`PoolOptions.LogicalDbProviders` accepts application-supplied providers ahead of the
base client's own mounted-secret and REST providers -- the base client itself is never modified;
this section is about judging what the *consumer's* custom provider does before trusting it.

1. Inspect every entry in `PoolOptions.LogicalDbProviders` the consumer registers, not just the base
   client's own.
2. A custom provider whose code path always returns a database or connection -- never `(nil, nil)`
   to fall through -- is **UNPROVEN**, never `SUPPORTED`, regardless of how it is documented. It
   permanently shadows the mounted-secret provider registered after it, so mounting the Secret would
   have no effect: the custom provider still answers first.
3. To reach `SUPPORTED` in operator mode, the consumer needs one of:
   - construct `NewDbaaSPool()` without registering the custom provider at all; or
   - change the custom provider itself to return `(nil, nil)` when it should not handle the lookup,
     so `NewDbaaSPool` continues to the mounted-secret provider.
4. Keep the custom provider active, unchanged, in legacy (REST) mode -- only its operator-mode
   behavior needs to change.
5. When the chart uses the `capabilityGuard` (see [contracts.md](../contracts.md)'s "Operator
   namespace" section and the plan's optional `operatorModeEnvironment`), use the same
   `DBAAS_OPERATOR_ENABLED` environment variable the guard injects as the one shared signal for
   which branch the custom provider takes -- do not invent a second, independent mode flag.
6. This is a real application source change, not something `apply_migration.py` performs (see this
   skill's writer-exclusive rule) -- require the consumer to add unit tests proving *both* modes
   (legacy: custom provider still answers; operator: custom provider yields to the mounted-secret
   provider) before recording the identity as `SUPPORTED` in the inventory. Never mark it `SUPPORTED`
   on inspection alone, and never attempt a generic, automated Go source rewrite -- this is a
   judgment call the consumer's own tests must prove, one provider at a time.

## Resolve version evidence

Treat the target service's `go.mod`, `go.sum`, replacements, and resolved module graph as the source
of truth. Do not copy a client version from this skill. When mounted-secret support is absent, consult
the upstream [base-client releases](https://github.com/Netcracker/qubership-core-lib-go-dbaas-base-client/releases)
and the relevant engine-client release page, choose a compatible candidate in the consumer's
`go.mod`, resolve the graph again, and prove the mounted provider and REST fallback. An E2E result is
evidence only for the exact resolved graph that was tested; it is not a minimum-version guarantee.
