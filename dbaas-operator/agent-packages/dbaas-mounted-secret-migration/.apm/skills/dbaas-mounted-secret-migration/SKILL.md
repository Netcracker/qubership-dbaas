---
name: dbaas-mounted-secret-migration
description: >-
  Migrate Qubership DBaaS-backed Go, Spring, and Quarkus microservices from runtime REST provisioning
  to deployment-time InternalDatabase and DatabaseSecretClaim resources with mounted Secrets. Use only
  when the user asks to migrate an existing service to this mounted-secret workflow.
---

# Migrate DBaaS provisioning to mounted Secrets

Inventory every logical database identity before building a plan. Generate one
`InternalDatabase` for each unique `(classifier, type)` and one `DatabaseSecretClaim` for each
unique `(classifier, type, requested userRole)`. Keep dynamic tenant provisioning on the existing
runtime path.

Detect the framework and read its reference:

- [Go](references/frameworks/go.md)
- [Spring](references/frameworks/spring.md)
- [Quarkus and BSS clients](references/frameworks/quarkus.md)

Report other frameworks as outside scope. For tenant, shard, schema, bucket, or
logical-to-physical behavior, also read
[dynamic-topologies.md](references/dynamic-topologies.md). Framework references define discovery
and Secret consumption; the CR identity rules below remain common.

Read [contracts.md](references/contracts.md) before building a plan, and
[testing.md](references/testing.md) when validating generated output or running a cluster test.

`scripts/apply_migration.py` is the only process allowed to create, modify, or delete files in the
consumer repository. Once discovery produces a plan, run it with `--check` to validate, then call
it with `--apply` exactly once to write -- never hand-edit a generated resource, a patched workload
manifest, `values.yaml`/`values.schema.json`, or a superseded declaration afterward. If the writer
blocks, fix the plan (or the source it points at) and rerun both steps.

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
`DbParams.Classifier` that overrides their default classifier; trace that function. Do not default
an unresolved database type to PostgreSQL -- mark the datasource `AMBIGUOUS` and stop generation.

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
`DbParams.Classifier` may be static; judge the function, not the method name. Only a `SUPPORTED`
identity belongs in the plan built in step 4 -- the writer rejects any other value outright rather
than filtering around it.

### Deduplicate by identity

Canonicalize classifier maps by keys and values for comparison.

- Repeated call sites with the same `(classifier, type)` share one `InternalDatabase`.
- Different types always require different `InternalDatabase` resources.
- Different classifier keys or values require different `InternalDatabase` resources.
- Different requested roles share the database but require separate claims and mounted Secrets.

The operator assignment is a deploy-time value, not a namespace baked into the repository. Expose it
as `DBAAS_OPERATOR_NAMESPACE` -- a Helm value the service populates when it deploys (for example
through Argo CD) -- and record it as the placeholder `{{ .Values.DBAAS_OPERATOR_NAMESPACE }}` in
the plan's `operatorNamespace`; the writer registers that value, with an empty default, in
`values.yaml`/`values.schema.json` for you. The operator reads its own namespace from
`CLOUD_NAMESPACE`, so nothing needs to be hardcoded here. Only for plain manifests, which cannot
template a value, resolve a concrete namespace instead: prefer an explicit deployment value, verify
it against the intended `dbaas-operator` Deployment or Pod when a cluster is available, and stop and
ask if it cannot be proven -- never assume `dbaas-system` or reuse the workload namespace.

Report all dynamic, blocked, and ambiguous entries; never generate placeholders that could create
the wrong database.

## 3. Map the imperative request onto plan fields

Preserve the runtime request exactly when filling in each datasource's `classifier` and
`parameters`:

- typed classifier keys go to `classifier.microserviceName`, `scope`, `namespace`, and `tenantId`;
- a runtime top-level extension key goes to `classifier.extraKeys` (Mongo's default classifier, for
  example, adds top-level `dbClassifier: default`); a runtime nested `customKeys` object goes to
  `classifier.customKeys`;
- `BaseDbParams.NamePrefix` goes to `parameters.namePrefix`; `BaseDbParams.Settings` (preserving
  each value's JSON type, never stringified) goes to `parameters.settings`;
- `BaseDbParams.Role` becomes one entry in `requestedRoles` exactly, including the difference
  between omitted/empty and an explicit role;
- connection-pool, migration, retry, and client options remain application configuration, not plan
  fields;
- `PhysicalDatabaseId` has no confirmed field in the current `InternalDatabase` contract and the
  writer refuses a non-empty `parameters.physicalDatabaseId` outright: mark that identity `BLOCKED`
  during inventory instead.

When a legacy `DatabaseDeclaration` supersedes into this migration, the writer refuses to delete or
splice it if it sets `versioningConfig`, `initialInstantiation`, or a non-default `lazy` -- there is
no proven mapping for those in the mounted-secret contract. Migrate the datasource itself, but leave
that declaration out of `supersededDeclarations` and flag it for manual follow-up.

## 4. Build the plan and call the writer

Group inventory datasources and workload claims by deployment root -- a plan may cover more than
one root, and identity/collision checks stay scoped to each root, so two roots may reuse a name.
`helmValues` (omitted above) overrides a chart value the writer does not otherwise resolve.

```json
{
  "roots": [
    {
      "root": "chart",
      "kind": "helm",
      "outputFile": "templates/dbaas-mounted-secret-resources.yaml",
      "operatorNamespace": "{{ .Values.DBAAS_OPERATOR_NAMESPACE }}",
      "workloadNamespace": "{{ .Values.NAMESPACE }}",
      "originService": "orders",
      "datasources": [
        {
          "id": "orders-postgresql-service",
          "type": "postgresql",
          "classifier": {"microserviceName": "orders", "scope": "service"},
          "requestedRoles": [""],
          "parameters": {"namePrefix": "", "settings": {}}
        }
      ],
      "claims": [
        {
          "datasourceId": "orders-postgresql-service", "role": "",
          "workloadFile": "templates/deployment.yaml", "workloadKind": "Deployment",
          "workloadName": "orders", "containers": ["orders"], "initContainers": []
        }
      ],
      "supersededDeclarations": [{"path": "templates/dbaas-declaration.yaml"}],
      "sourceHashes": {"templates/deployment.yaml": "<sha256>", "templates/dbaas-declaration.yaml": "<sha256>"}
    }
  ]
}
```

`sourceHashes` keys are root-relative and must record the SHA-256 of every file this root reads
(every workload file, every superseded-declaration file, `values.yaml`, `values.schema.json`) at the
moment of discovery; the writer refuses to act on a hash that no longer matches (something changed
underneath the plan -- rebuild it).

A datasource's `resourceName` is required only when `classifier.microserviceName` or
`classifier.scope` is still a Helm expression, and must itself embed `.Release.Name`, the one value
Helm guarantees unique per release -- the writer rejects both a plan that omits it for a templated
identity and one that slugifies the template text into a fixed literal instead (every release of
the chart would then generate the same name).

Run, from the directory containing this `SKILL.md`:

```bash
python scripts/apply_migration.py --repo-root <repo> --plan <plan.json> --check
python scripts/apply_migration.py --repo-root <repo> --plan <plan.json> --apply
```

Read the JSON result on stdout. Exit 0 covers a `valid` (`--check`), `changed`, or `unchanged`
(`--apply`) result; 2 is an invalid plan, 3 a stale source hash, 4 an unsupported input or missing
dependency (including `helm` for a helm root), 5 a generated-output validation failure. Every
blocking entry names the file and the reason.

## 5. Cases the writer refuses rather than guesses at

An empty or missing pod-spec mapping; a standalone Helm block action or assignment inside a
workload manifest; a volume/mount name that already exists pointing at a different secret; a
templated classifier identity with no `resourceName`, or one with no live `.Release.Name`
expression; a superseded declaration carrying `physicalDatabaseId`, `versioningConfig`,
`initialInstantiation`, or a non-default `lazy` (see step 3); a migration source path that is also
the generated output path; a plain-manifest output that still contains a Helm expression after
generation; a missing `helm` binary for a helm root.

A warning is never permission to drop data: an unresolved ambiguity blocks the run instead of
guessing.
