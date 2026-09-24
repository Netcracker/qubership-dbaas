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

`scripts/apply_migration.py` is the only process that creates, modifies, or deletes a file named in
its plan -- a generated resource, a patched workload manifest, or a superseded declaration. Once
discovery produces a plan, run it with `--check` to validate, then call it with `--apply` exactly
once to write; never hand-edit any of those files afterward. If the writer blocks, fix the plan (or
the source it points at) and rerun both steps.

This rule is scoped to the writer's own plan-named files, not to the whole consumer repository. A
normal code-editing workflow may still modify application source and tests outside the plan -- for
example, when [go.md](references/frameworks/go.md)'s custom-provider guidance requires a source
change before an identity can be marked `SUPPORTED`. After such a source change, rebuild the
inventory and the plan from the new state; do not reuse a plan built against the old source.

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

The operator assignment is a deploy-time value, not a namespace baked into the repository. The
writer never registers any default for it in `values.yaml`/`values.schema.json` -- the plan's
`operatorNamespace` must already be a concrete, verified value or Helm expression. For a Helm
chart, when `API_DBAAS_ADDRESS` is a namespaced in-cluster Kubernetes service address (e.g.
`http://dbaas-aggregator.dbaas-operator:8080`), derive it with the documented expression (see
references/contracts.md's "Operator namespace" section) instead:

```gotemplate
{{ (index (splitList "." (first (splitList ":" (last (splitList "://" $.Values.API_DBAAS_ADDRESS))))) 1) }}
```

If a chart already pins `DBAAS_OPERATOR_NAMESPACE` (or whatever value key that expression resolves
through) to a real value, that value wins -- but an explicitly chosen empty value must fail
validation, not be masked. When `API_DBAAS_ADDRESS` is external, single-label, empty, or otherwise
cannot identify the operator namespace, require an explicit, verified literal in the plan instead.
For plain manifests, which cannot template a value, always resolve a concrete namespace: prefer an
explicit deployment value, verify it against the intended `dbaas-operator` Deployment or Pod when a
cluster is available, and stop and ask if it cannot be proven -- never assume `dbaas-system` or
reuse the workload namespace.

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
one root, but each root must target a distinct workload namespace. Roots may reuse a name only when
those namespaces differ.
`helmValues` (omitted above) overrides a chart value the writer does not otherwise resolve.

```json
{
  "roots": [
    {
      "root": "chart",
      "kind": "helm",
      "outputFile": "templates/dbaas-mounted-secret-resources.yaml",
      "operatorNamespace": "{{ (index (splitList \".\" (first (splitList \":\" (last (splitList \"://\" $.Values.API_DBAAS_ADDRESS))))) 1) }}",
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
      "sourceHashes": {"templates/deployment.yaml": "<sha256>", "templates/dbaas-declaration.yaml": "<sha256>"},
      "capabilityGuard": "dbaas.netcracker.com/v1",
      "operatorModeEnvironment": {"name": "DBAAS_OPERATOR_ENABLED", "value": "true"}
    }
  ]
}
```

`capabilityGuard` and `operatorModeEnvironment` (both omitted above) are optional -- see
references/contracts.md's "Capability guard" section for a mixed cluster where the dbaas-operator
CRDs may not be installed. Omitting `capabilityGuard` keeps every prior behavior unchanged.

`sourceHashes` keys are root-relative and must record the SHA-256 of every file this root reads
(every workload file, every superseded-declaration file) at the moment of discovery; the writer
refuses to act on a hash that no longer matches (something changed underneath the plan -- rebuild
it). The writer never reads or writes `values.yaml` / `values.schema.json`.

Each `supersededDeclarations` entry addresses one YAML file's contents through its optional
`documentIndex`: omit it to address the whole file (every top-level `---`-separated document must
be a proven, migrated declaration, or the run blocks); set it to a 1-based document number to
address only that document, leaving every other document in the file untouched. List one entry per
document when a single file interleaves declarations with unrelated content (a `ConfigMap`, say) or
mixes declarations that migrate on different schedules. `documentIndex` is not supported for a JSON
source -- a JSON array is always all-or-nothing. Any document the plan leaves unaddressed that still
shares a migrated datasource's `(classifier, type)` identity blocks the run: list it too, even if its
settings differ from what was migrated -- a settings mismatch does not make the two declarations
unrelated, it only decides which one wins the race against the generated resource.

Numbering counts *parsed* documents, not raw `---`-separated regions: a region that is empty or holds
only a comment produces no document at all and consumes no index, so it is skipped when counting --
the document before it and the document after it are adjacent index numbers. An explicit `null` or
`~` document is a real document with real content, unlike an empty/comment-only region, and does
consume its own index. When a file mixes any of these, count with a YAML-aware tool rather than by
eye; a wrong guess surfaces as `--check` reporting a mismatch (wrong content, or `documentIndex` out
of range) against the document it actually resolved, addressed as `<path>#<index>`.

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

An empty or missing pod-spec mapping; a volume/mount name that already exists pointing at a
different secret; a target container/list that only Helm can produce (a `range` that generates the
whole target container list, or a conditional wrapping the entire target key/sequence with no
static insertion point -- see references/contracts.md's "Helm-templated workloads" section); a
templated classifier identity with no `resourceName`, or one with no live `.Release.Name`
expression; a superseded declaration carrying `physicalDatabaseId`, `versioningConfig`,
`initialInstantiation`, or a non-default `lazy` (see step 3); a migration source path that is also
the generated output path; a plain-manifest output that still contains a Helm expression after
generation; a missing `helm` binary for a helm root. A standalone Helm action (`if`/`else`/`range`/
`with`/`end`/an assignment) elsewhere in the manifest, or wrapping only unrelated content, no longer
blocks the run by itself -- masking, not a blanket rejection, is what makes editing around it safe.

A warning is never permission to drop data: an unresolved ambiguity blocks the run instead of
guessing.
