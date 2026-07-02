---
name: dbaas-declarative-transform
description: >-
  Migrate Qubership DBaaS-backed Go, Spring, and Quarkus microservices from runtime provisioning to
  deployment-time InternalDatabase and DatabaseSecretClaim resources. Use when inventorying datasources,
  classifiers, logical databases, tenants, shards, roles, migrations, generated Secrets, and mounts. Generate only
  deployment-known identities and prove the resolved client version's Secret-consumption contract.
---

# Transform DBaaS provisioning to declarative resources

Inventory every logical database identity before editing manifests. Generate one
`InternalDatabase` for each unique `(classifier, type)` and one `DatabaseSecretClaim` for each
unique `(classifier, type, requested userRole)`. Keep dynamic tenant provisioning on the existing
runtime path.

Detect the framework and read its reference:

- [Go](references/frameworks/go.md)
- [Spring](references/frameworks/spring.md)
- [Quarkus and BSS clients](references/frameworks/quarkus.md)

Report other frameworks as outside scope.

For tenant, shard, schema, bucket, or logical-to-physical behavior, also read
[dynamic-topologies.md](references/dynamic-topologies.md). Framework references define discovery
and Secret consumption; the CR identity rules below remain common.

Read [contracts.md](references/contracts.md) before generating resources. Read
[testing.md](references/testing.md) when validating generated output or running a cluster test.
Use `scripts/validate_generated.py` for deterministic inventory/resource/mount consistency checks.

## 1. Verify generated-secret compatibility

Inspect the target service's resolved dependencies, not a sibling checkout or an assumed version.

Prove one mode using the framework reference:

- `NATIVE_MOUNTED_PROVIDER`: client reads and identity-matches the operator Secret;
- `EXPLICIT_SECRET_ADAPTER`: code maps it without provisioning;
- `DIRECT_KUBERNETES_SECRET`: it maps into supported connection properties;
- `UNPROVEN`: no compatible path is demonstrated.

Only the first three can be `SUPPORTED`; `UNPROVEN` is `BLOCKED`. A valid Secret does not prove
client compatibility.

If the resolved client predates mounted-secret support, report the exact dependency or BOM upgrade
needed and keep the identity `BLOCKED` until source inspection or E2E proves the upgraded graph.
Never generate mounts while implying that an incompatible client will consume them.

Do not remove the REST fallback. Supported declarative identities should hit the mounted provider;
unsupported dynamic identities may still need runtime provisioning.

## 2. Build a datasource inventory

Search production source, dependencies, configuration, and workload manifests. Exclude tests only after
checking that they are not the sole documentation of a wrapper's behavior.

Use the selected framework reference to find datasource factories, annotations, wrappers, actual
DBaaS operations, and legacy `DatabaseDeclaration` resources. Treat a legacy declaration as
evidence to reconcile with code, not as a substitute for tracing the runtime request.

For a legacy wrapper containing multiple `declarations[]` items, inventory each item independently.
Do not reuse the wrapper's `metadata.name`; derive each generated name from that item's full identity.

For every call path, resolve:

- the exact database type string;
- the classifier function and every emitted key/value;
- whether each value is fixed for a deployment or derived from request/runtime context;
- `BaseDbParams.NamePrefix`, `Settings`, `PhysicalDatabaseId`, and `Role`;
- all deployment/stateful-set containers that consume the datasource;
- the credential-consumption mode and evidence from the resolved client;
- the source locations that prove the result.

Do not infer scope solely from `ServiceDatabase` or `TenantDatabase`. Both APIs accept an explicit
`DbParams.Classifier` that overrides their default classifier. Trace that function.

Do not default an unresolved database type to PostgreSQL. Mark the datasource `AMBIGUOUS` and stop
generation for it.

### Feasibility

Classify an identity as:

- `SUPPORTED`: every classifier value is known from source, deployment values, or environment
  configuration at deployment time;
- `NOT_SUPPORTED_DYNAMIC`: any identity value, especially `tenantId`, comes from request context,
  `tenant.Of(ctx)`, or another runtime-only source;
- `BLOCKED`: the imperative request uses a field without a confirmed declarative mapping, including
  `PhysicalDatabaseId`;
- `AMBIGUOUS`: type, classifier, role, or parameter flow cannot be proven statically.

`TenantDatabase(...)` with its default classifier is dynamic. A custom classifier supplied through
`DbParams.Classifier` may be static; judge the function, not the method name.

### Deduplicate by identity

Canonicalize classifier maps by keys and values for comparison.

- Repeated call sites with the same `(classifier, type)` share one `InternalDatabase`.
- Different types always require different `InternalDatabase` resources.
- Different classifier keys or values require different `InternalDatabase` resources.
- Different requested roles share the database but require separate claims and mounted Secrets.

Produce the inventory before making changes:

```json
{
  "datasources": [
    {
      "id": "orders-postgresql-service",
      "type": "postgresql",
      "classifier": {
        "microserviceName": "orders",
        "namespace": "orders-ns",
        "scope": "service"
      },
      "requestedRoles": [""],
      "parameters": {
        "namePrefix": "",
        "settings": {},
        "physicalDatabaseId": ""
      },
      "codeLocations": ["internal/storage/postgres.go:42"],
      "migrationFeasibility": "SUPPORTED"
    }
  ]
}
```

Report all dynamic, blocked, and ambiguous entries. Never generate placeholders that could create
the wrong database.

## 3. Map the imperative request

Preserve the runtime request exactly:

- typed classifier keys map to `spec.classifier.microserviceName`, `scope`, `namespace`, and
  `tenantId`;
- a runtime top-level extension key maps to `spec.classifier.extraKeys` so it remains top-level on
  the wire;
- a runtime nested `customKeys` object maps to `spec.classifier.customKeys`;
- `BaseDbParams.NamePrefix` maps to `InternalDatabase.spec.namePrefix`;
- database-creation `BaseDbParams.Settings` map to `InternalDatabase.spec.settings` only when every
  value is representable as a string;
- `BaseDbParams.Role` maps to `DatabaseSecretClaim.spec.userRole` exactly, including the difference
  between omitted/empty and an explicit role;
- connection-pool, migration, retry, and client options remain application configuration;
- `PhysicalDatabaseId` has no confirmed field in the current `InternalDatabase` contract: mark it
  `BLOCKED` unless the target operator/aggregator contract proves a mapping.

When replacing a legacy `DatabaseDeclaration`, preserve explicit `versioningConfig` and
`initialInstantiation` only after verifying that its classifier matches the runtime request. Block
array, boolean, or nested settings until their exact string encoding is defined by the target
operator contract.

Mongo's default classifier adds top-level `dbClassifier: default`. Preserve it under `extraKeys`.
Apply the same rule to any custom top-level classifier extension.

## 4. Choose collision-free names

Build a stable DNS label from the full identity, not only service and scope.

1. Start with `<microservice>-<type>-<scope>`.
1. Append a static tenant ID for tenant scope.
1. Append a short meaningful discriminator for additional classifier identity fields. If no safe,
   concise discriminator exists, append the first eight lowercase hex characters of a SHA-256 hash
   of the canonical classifier JSON.
1. Normalize to lowercase DNS-1123 syntax and keep Kubernetes names at most 63 characters. Preserve
   the hash suffix when truncating.

Use these suffixes:

```text
InternalDatabase:    <identity>-db
DatabaseSecretClaim: <identity>-<role-or-default>-claim
Secret:              <identity>-<role-or-default>-credentials
Volume:              <identity>-<role-or-default>-secret
```

Check every generated resource, Secret, volume, and mount name for collisions before writing.

## 5. Generate resources

For every supported database identity, generate an `InternalDatabase`. For every requested role of
that identity, generate a claim. Use the canonical templates in
[contracts.md](references/contracts.md).

Rules:

- Set `metadata.namespace` to the workload namespace.
- Omit `classifier.namespace` and let the operator derive it, or set it to the workload namespace
  consistently in both resources. Never copy a differing legacy namespace.
- Copy the complete classifier and type identically into the paired claim.
- Add non-empty `app.kubernetes.io/name` to each claim; it becomes `originService`.
- Set `lazy` to a YAML boolean, normally `false`, unless the existing deployment contract explicitly
  requires lazy provisioning. Never quote boolean values.
- Omit defaulted optional fields instead of inventing values.
- Do not add `initialInstantiation` or versioning behavior unless the existing configuration
  requires it.

Prefer the consumer repository's existing Helm/declaration layout. For plain manifests, use a
coherent existing manifests directory. Do not create backup files; rely on version-control diffs.

## 6. Mount every generated Secret

Update each `Deployment` or `StatefulSet` container that consumes the corresponding role:

```yaml
volumes:
  - name: orders-postgresql-service-default-secret
    secret:
      secretName: orders-postgresql-service-default-credentials

containers:
  - name: orders
    volumeMounts:
      - name: orders-postgresql-service-default-secret
        mountPath: /etc/secrets/dbaas-secrets/orders-postgresql-service-default-credentials
        readOnly: true
```

The final path component must equal `DatabaseSecretClaim.spec.secretName`. Preserve existing Helm
expressions, volumes, mounts, init containers, and sidecars. Mount only into containers that use the
database.

## 7. Validate before completion

Perform all applicable checks from [testing.md](references/testing.md):

1. Render Helm templates before validating YAML.
1. Run `scripts/validate_generated.py --inventory <inventory.json> <rendered-or-plain-yaml>`.
1. Validate syntax and run client-side and server-side dry runs when a suitable cluster is present.
1. Compare canonical classifiers and type between each InternalDatabase and claim.
1. Verify claim role against every client request role.
1. Verify that all names are unique and DNS-compatible.
1. Verify each claim Secret has at least one consuming volume/mount and every consumer uses the
   required path and read-only mode. Multiple application containers may intentionally share it.
1. Confirm unsupported dynamic call paths were not removed or redirected.

Do not use a one-InternalDatabase-to-one-claim count check: multiple roles legitimately create
multiple claims for one database.

## Completion report

Report:

- every discovered logical database identity and its evidence;
- supported, dynamic, blocked, and ambiguous counts;
- the deduplication decisions;
- every generated or modified file;
- validation commands and their actual results;
- dependency compatibility evidence;
- remaining runtime fallback paths and why they remain.

Call the migration complete only when generated mounted Secrets match the client lookup key:
`canonical classifier | lowercase type | trimmed requested role`.
