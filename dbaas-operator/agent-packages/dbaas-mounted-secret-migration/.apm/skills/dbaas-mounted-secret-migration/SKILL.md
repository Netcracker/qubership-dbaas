---
name: dbaas-mounted-secret-migration
description: >-
  Migrate Qubership DBaaS-backed Go, Spring, and Quarkus microservices from runtime REST provisioning
  to deployment-time InternalDatabase and DatabaseSecretClaim resources with mounted Secrets. Use only
  when the user asks to migrate an existing service to this mounted-secret workflow.
---

# Migrate DBaaS provisioning to mounted Secrets

The bundled script `scripts/apply_migration.py` is the only writer of migration files. This skill
inventories the service, proves compatibility, resolves every ambiguous value, and writes a JSON
plan; the script generates the resources, patches the workloads, updates the chart values, and
validates the result. Do not generate CRs, edit workload manifests, or edit the script output by
hand. A missing case is fixed in the script and covered by a fixture.

## Execution boundary

1. **Discover and decide.** Inventory every logical database identity, prove mounted-secret
   compatibility, resolve names and container selections, and write the plan JSON to a temporary
   path. Change no consumer file in this phase.
2. **Apply and verify.** Invoke `scripts/apply_migration.py` with `--apply`. The script validates
   the plan and source hashes, generates one canonical resource file, mounts every generated Secret
   into the plan-selected containers, adds `DBAAS_OPERATOR_NAMESPACE` to the chart values and
   `values.schema.json`, removes the superseded legacy declarations, and validates the result in a
   temporary tree before touching the working copy. For a `helm` root the runner renders the
   candidate chart with `helm template --namespace <resolved workloadNamespace>` and validates the
   rendered Kubernetes objects, treating that namespace as the effective one for any resource that
   omits `metadata.namespace` -- `helm` must be on PATH, or the run is blocked rather than certifying
   raw templates. Pass `--report <path>` only outside the consumer repository; the report is
   execution output, not a repository artifact.

Detect the framework and read its reference:

- [Go](references/frameworks/go.md)
- [Spring](references/frameworks/spring.md)
- [Quarkus and BSS clients](references/frameworks/quarkus.md)

Report other frameworks as outside scope. For tenant, shard, schema, bucket, or logical-to-physical
behavior, also read [dynamic-topologies.md](references/dynamic-topologies.md). Read
[contracts.md](references/contracts.md) for the identity and Secret rules and
[testing.md](references/testing.md) for the validation layers.

## 1. Verify generated-secret compatibility

Inspect the target service's resolved dependencies, not a sibling checkout or an assumed version.
Prove one mode using the framework reference and record it in the plan as
`datasources[].compatibility.mode`:

- `NATIVE_MOUNTED_PROVIDER`: the client reads and identity-matches the operator Secret;
- `EXPLICIT_SECRET_ADAPTER`: code maps it without provisioning;
- `DIRECT_KUBERNETES_SECRET`: it maps into supported connection properties.

Any other state, including a resolved client that predates mounted-secret support, is not proven.
The runner blocks a SUPPORTED datasource whose `compatibility.mode` is not one of the three above.
Report the exact dependency or BOM upgrade needed and keep the identity out of scope until an
upgraded graph is proven. Never generate mounts while implying that an incompatible client will
consume them. Do not remove the REST fallback.

## 2. Build a datasource inventory

Search production source, dependencies, configuration, and workload manifests. Use the selected
framework reference to find datasource factories, annotations, wrappers, DBaaS operations, and
legacy `DatabaseDeclaration` resources. For a legacy wrapper with multiple `declarations[]` items,
inventory each item independently.

For every call path, resolve:

- the exact database type string;
- the classifier function and every emitted key/value;
- whether each value is fixed for a deployment or derived from request/runtime context;
- `BaseDbParams.NamePrefix`, `Settings`, `PhysicalDatabaseId`, and `Role`;
- every Deployment/StatefulSet container and init container that consumes the datasource;
- the credential-consumption mode and its evidence;
- the source locations that prove the result.

Do not infer scope from a method name, default an unresolved type to PostgreSQL, or generate
placeholders. Classify each identity:

- `SUPPORTED`: every classifier value is known at deployment time;
- `NOT_SUPPORTED_DYNAMIC`: any value, especially `tenantId`, comes from request/runtime context;
- `BLOCKED`: the request uses a field with no confirmed declarative mapping, including
  `PhysicalDatabaseId`;
- `AMBIGUOUS`: type, classifier, role, or parameter flow cannot be proven statically.

Only `SUPPORTED` identities are generated. The others are reported and left on the REST path. The
runner blocks the whole apply if a plan claim targets a non-`SUPPORTED` identity, and it rejects a
`SUPPORTED` datasource that still carries a non-empty `parameters.physicalDatabaseId`, so a
mislabelled physical binding cannot slip through.

Record the inventory under `inputs.datasources` in the effective runtime wire form: the classifier
carries the resolved workload namespace and top-level extension keys directly (no `extraKeys` in the
inventory). `classifier.namespace` must equal `decisions.workloadNamespace`, so a Helm layout uses
`{{ .Values.NAMESPACE }}` in both places and a plain layout uses the same concrete namespace.

```json
{
  "id": "orders-postgresql-service",
  "type": "postgresql",
  "classifier": {"microserviceName": "orders", "namespace": "{{ .Values.NAMESPACE }}", "scope": "service"},
  "requestedRoles": [""],
  "parameters": {"namePrefix": "", "settings": {}, "physicalDatabaseId": ""},
  "codeLocations": ["internal/storage/postgres.go:42"],
  "migrationFeasibility": "SUPPORTED",
  "compatibility": {"mode": "NATIVE_MOUNTED_PROVIDER", "evidence": "resolved base client vX registers the provider"}
}
```

## 3. Resolve the operator assignment

The operator assignment is a deploy-time value. For a Helm layout, set
`inputs.operatorNamespace` to `{{ .Values.DBAAS_OPERATOR_NAMESPACE }}`; the runner adds that value to
`values.yaml` (empty default) and makes it a required, non-empty property in `values.schema.json`.
For a plain-manifest layout, resolve a concrete namespace: prefer an explicit deployment value,
verify it against the intended `dbaas-operator` Deployment or Pod when a cluster is available, and
stop and ask if it cannot be proven. Never assume `dbaas-system` or reuse the workload namespace.

## 4. Resolve the remaining decisions

Record under `decisions`:

- **`root`**, **`rootKind`** (`helm` / `plain`), and **`workloadNamespace`**;
- **`originService`**: the `app.kubernetes.io/name` label on every claim;
- **`claims`**: one entry per requested role of each SUPPORTED datasource, naming the
  `workloadFile`, `workloadKind`, `workloadName`, consuming `containers`, and `initContainers`. The
  claim roles must exactly match the datasource's `requestedRoles`.
- **`nameDiscriminators`**: a short readable discriminator for a datasource whose identity needs
  more than `<microservice>-<type>-<scope>`. Without one, the runner appends the first eight hex
  characters of the canonical-classifier SHA-256. Two datasources that resolve to the same
  `(classifier, type)` must carry the same discriminator (or none); a disagreement is a blocking
  error, since the generated names would otherwise depend on inventory order.
- **`supersededDeclarations`**: legacy declaration files the runner should delete, given as
  repository-relative paths. The runner does not take your word for what a file contains: it parses
  each file, requires every document to be a `DatabaseDeclaration` — either a standalone declaration
  or a wrapper whose `declarations` list is non-empty and holds only objects — and for every
  declaration checks both its `(classifier, type)` identity (resolving the owning-service placeholder
  the way discovery does, and taking a missing classifier namespace from the document's
  `metadata.namespace` before the workload fallback) and its creation behaviour against the matched
  `SUPPORTED` datasource: `settings` and `namePrefix` must match `parameters`, and `lazy: true`,
  `versioningConfig`, `initialInstantiation`, or any unrecognized field blocks the delete. Unrelated
  resources, a `DbPolicy` document (this migration does not replace access policies), a declaration
  for a `NOT_SUPPORTED_DYNAMIC` / `BLOCKED` / `AMBIGUOUS` identity or another namespace, or a
  semantic mismatch blocks the run — split the file so the unmigrated declaration is preserved.
- **`outputFile`**, **`valuesFile`**, **`schemaFile`**: only when they differ from the defaults
  (`templates/dbaas-mounted-secret-resources.yaml`, `values.yaml`, `values.schema.json`).
- **`outputOwnership`**: the current SHA-256 of an existing output file the migration must overwrite.

`repository.preconditions` and `targets` must together account for every file the run will touch —
the generated resource file, every patched workload, `values.yaml`, `values.schema.json`, and every
superseded declaration. The runner refuses (exit `2`) to write or delete a path that is not in both
lists.

The runner owns every name. `InternalDatabase` is `<identity>-db`; the claim, Secret, and volume use
`<identity>-<role-or-default>-{claim,credentials,secret}`; the mount path is
`/etc/secrets/dbaas-secrets/<secretName>` with `readOnly: true`.

## 5. Apply

Write the plan JSON to a temporary file. Resolve `scripts/apply_migration.py` relative to this
`SKILL.md`. Run `--check` first if a dry run helps, then `--apply`.

The first-release workload adapter supports plain Kubernetes `Deployment` / `StatefulSet` YAML and
Helm templates whose templating is confined to whole scalar values. It edits the manifest in place
by inserting only the required `volumes` and `volumeMounts` nodes, so comments, numeric and boolean
scalars, `replicas: {{ ... }}`, and key order are byte-preserved and a repeat run is idempotent. A
standalone Helm action (`if`, `range`, `with`, `include`, `end`, an `{{ $x := ... }}` assignment,
...) inside a workload manifest, a present-but-null `volumes` / `containers` / `volumeMounts`, or a
non-empty inline (`[ ... ]`) list where a block list is expected, makes the runner fail closed; do
not fall back to a hand edit. An empty inline `volumes: []` / `volumeMounts: []` is rewritten as a
block list.

The runner refuses a plan that generates nothing (no `SUPPORTED` datasource has a claim) rather than
writing an empty output file and touching the chart values.

## Handling script failures

Any non-zero exit stops the migration. Report the exit category and the exact blocking entries.

| Exit | Meaning | Recovery |
| --- | --- | --- |
| `2` | Invalid CLI or plan | Fix the plan and re-run |
| `3` | A source changed after discovery | Re-inventory and rebuild the plan |
| `4` | An unsupported transformation, or a missing dependency (`helm`, PyYAML) | Resolve the entries or install the dependency |
| `5` | Generated-output or rendered-chart validation failed | Treat as a script bug; fix it and add a fixture |
| `6` | The write transaction or report publication failed and rolled back | Investigate the filesystem error |

`--help` exits `0`. A missing `helm` or PyYAML is reported as a blocked JSON result with exit `4`,
never as a bare traceback.

## Completion report

Build the report from the result JSON plus discovery evidence:

- every discovered logical database identity and its evidence;
- the operator assignment used and how it was resolved;
- SUPPORTED, dynamic, blocked, and ambiguous counts;
- the deduplication decisions;
- `createdFiles`, `modifiedFiles`, `deletedFiles` from the result;
- the validation entries from the result: `validate_generated` for a plain root, or `helm-render`
  plus `validate_rendered` for a helm root;
- dependency compatibility evidence;
- the runtime fallback paths that remain and why.

Call the migration complete only when the generated mounted Secrets match the client lookup key
`canonical classifier | lowercase type | trimmed requested role`, the result `status` is `changed`
or `unchanged`, and every validation entry passed. A mounted-provider hit against a real client, and
CR reconciliation against a real operator, are separate proofs described in
[testing.md](references/testing.md).
