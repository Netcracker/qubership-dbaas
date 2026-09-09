---
name: migrate-core-operator-dbaas-declarations
description: "Migrate legacy Core Operator DBaaS declarations to dbaas-operator resources. Use only when the user asks to migrate DatabaseDeclaration/DbPolicy resources to InternalDatabase/DatabaseAccessPolicy."
---

# Migrate Core Operator DBaaS declarations

Convert legacy DBaaS declarations into dedicated Kubernetes resources:

- `DatabaseDeclaration` to `apiVersion: dbaas.netcracker.com/v1`, `kind: InternalDatabase`
- `DbPolicy` or `dbPolicy` to `apiVersion: dbaas.netcracker.com/v1`, `kind: DatabaseAccessPolicy`

The bundled script `scripts/apply_migration.py` is the only writer of migration files. This skill
discovers the repository and records decisions; the script performs every transformation. Do not
convert resources by hand, generate the YAML yourself, or edit the script output afterward.

## Execution boundary

1. **Discover and decide.** Trace the consumer repository, resolve every ambiguous value, and write a
   JSON migration plan to a temporary path. Change no consumer file in this phase.
2. **Apply and verify.** Invoke `scripts/apply_migration.py` with `--apply`. The script validates the
   plan and source hashes, converts the selected documents, derives target names, writes one
   canonical resource file per chart or manifest root, removes the migrated legacy documents, and
   validates the result in a temporary tree before touching the working copy.

A missing conversion case is fixed in the script and covered by a fixture, never worked around with
a hand edit.

## Workflow

1. Establish the migration scope from user-provided paths or content-based repository discovery. Find
   JSON or YAML with `kind: DatabaseDeclaration`, `kind: DbPolicy` or `dbPolicy`, or `kind: DBaaS`
   plus `subKind: DatabaseDeclaration` or `DbPolicy`. Common locations include
   `**/dbaas-configuration.json`, `deployments/`, `<service-name>-deployments/`, and Helm chart
   `declarations/` or `templates/` directories.
2. For each source, record its repository-relative path, its owning chart or plain-manifest root, and
   whether that root is `helm` or `plain`. Give one physical root one spelling: `chart` and `chart/`
   are the same root and must not appear as two entries with different `rootKind` or output files.
3. Read [references/mapping.md](references/mapping.md) and [references/examples.md](references/examples.md)
   to understand what the script will produce.
4. Resolve every decision the script needs (see below). Inspect the target repository's current
   `InternalDatabase` and `DatabaseAccessPolicy` CRD schemas so the resolved values match the
   contract.
5. Write the plan JSON to a temporary file.
6. Resolve `scripts/apply_migration.py` relative to this `SKILL.md`. Run it with `--check` first if a
   dry run is useful, then with `--apply`.
7. Build the completion report from the result JSON plus the discovery evidence. Treat the final
   `git diff` as review evidence, not an invitation to normalize output by hand.

## Decisions to resolve before writing the plan

Record these under `decisions` in the plan:

- **`operatorNamespace`** (required, non-empty): the namespace of the dbaas-operator instance that
  will manage the generated resources. It is not necessarily the workload namespace. Ask when it is
  not known.
- **`serviceName`** and **`serviceNameExplicit`**: the owning service. Set `serviceNameExplicit` to
  `true` only when the source context is unambiguous or the user pinned it; otherwise the script
  falls back to source-derived identity and the run blocks until you confirm it.
- **`namespace`**: `metadata.namespace` for generated resources. Use `{{ .Values.NAMESPACE }}` for
  a `helm` root and a concrete namespace for a `plain` root; a plain-root output that still contains
  a Helm expression fails validation. A single plan may not mix `helm` and `plain` roots, and one
  normalized root may not carry two `rootKind` values -- run them separately.
- **`resourceNames`**: an explicit target name for a resource whose derived name you want to
  override. A single-declaration document is keyed `<source-path>#<document-index>`; a declaration
  inside a multi-declaration wrapper is keyed `<source-path>#<document-index>#<item-index>` so one
  override cannot fan out to every child. A key that matches nothing blocks the run. Every derived
  name is passed through the shared 63-character DNS-label helper.
- **`outputFileByRoot`**: only when a root must not use the canonical output filename. The runner
  blocks if the resulting output path equals one of the migration sources.
- **`warningResolutions`**: a list -- one entry per accepted non-semantic converter warning, in the
  source-scoped form `<source-path>: <warning>` (for example a dropped wrapper-only label, or an
  auto-filled `sourceClassifier.microserviceName`). Each entry is consumed once, so if the same
  warning text occurs twice in one source you list it twice. An unlisted warning blocks the run; an
  accepted one is echoed into the result `warnings`; an entry that matches nothing blocks the run.
  Semantically invalid conditions — a missing required classifier
  field or type, a non-boolean `lazy`, a cross-service clone, `lazy: true` with a clone, a clone
  without `sourceClassifier`, an unresolved policy owner, an invalid `settings` value, a
  `DatabaseAccessPolicy` that violates the CRD shape (a `services` entry with an unknown field or
  without a non-empty `name` or non-empty `roles`, a `policy` entry with an unknown field or without
  a non-empty `type` or `defaultRole`, a non-boolean `disableGlobalPermissions`, or no non-empty
  `services` or `policy` list at all) — and structural defects — a `declarations` value that is not
  a list,
  a non-object declaration entry, a sequence document that mixes legacy declarations with anything
  else, a Helm guard that does not bracket a whole document, a `kind: DBaaS` wrapper with an
  unsupported `subKind` — are permanently blocking errors and cannot be listed here. The runner also
  blocks before it rewrites or deletes a source when a selected legacy document produced no resource,
  and after cleanup it reparses each rewritten source to confirm it is valid YAML/JSON with no legacy
  declaration left. Fix the source or pin the decision instead.
- **`outputOwnership`**: the current SHA-256 of any existing output file this migration must
  overwrite, so a collision with unrelated content still blocks.

`repository.preconditions` and `targets` must together account for every file the run will touch.
List each legacy source and each generated output in `targets`, give every existing one a SHA-256
precondition, and give every not-yet-created output an `absent: true` precondition. The runner
refuses (exit `2`) to create, modify, or delete a path that is not in both lists.

## Canonical output

The script writes one file per root:

- Helm root: `<root>/templates/dbaas-operator-resources.yaml`
- Plain-manifest root: `<root>/dbaas-operator-resources.yaml`

Resources are sorted by kind, then namespace, then name, one YAML document each. A supported Helm
guard (`{{- if <pipeline> }} ... {{- end }}` bracketing a whole document, or a fully templated
scalar) is preserved. A guard that wraps only part of a document, spans a `---`, or any other
template action blocks the run and reports its source line.

The script removes the migrated documents from each source and deletes a source file once nothing
unrelated remains. Mixed files keep their unrelated content.

## Handling script failures

Any non-zero exit stops the migration. Report the exit category and the exact blocking entries from
the result JSON. Do not hand-edit around the failure.

| Exit | Meaning | Recovery |
| --- | --- | --- |
| `2` | Invalid CLI, or an invalid plan (unknown key, wrong type, mixed roots, unmatched key) | Fix the plan and re-run |
| `3` | A source changed after discovery | Re-discover and rebuild the plan |
| `4` | An unsupported transformation, or a missing dependency (PyYAML) | Resolve the listed entries, or add a fixture |
| `5` | Generated-output validation failed | Treat as a script bug; fix it and add a fixture |
| `6` | The write transaction or report publication failed and rolled back | Investigate the filesystem error |

`--help` exits `0`. Pass `--report <path>` only outside the consumer repository -- the report is
execution output, not a repository artifact -- and never point it at the plan file.

## Completion report

Build the report from the result JSON plus discovery evidence:

- every legacy source and the documents migrated from it;
- the `operatorNamespace` used and how it was confirmed;
- the resolved owning service and whether it was pinned or derived;
- `createdFiles`, `modifiedFiles`, `deletedFiles` from the result;
- every resolved converter warning;
- the validation entries from the result;
- whether CRD-schema or cluster validation was run, and any checks that remain pending.

A successful reconciliation against an aggregator mock proves CRD and operator contract
compatibility. It does not prove that a physical database was provisioned.
