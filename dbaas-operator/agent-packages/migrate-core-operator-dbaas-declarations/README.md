# migrate-core-operator-dbaas-declarations

An APM package that migrates legacy Core Operator DBaaS database declarations and database policies
to the dedicated `dbaas-operator` custom resources:

- `DatabaseDeclaration` to `InternalDatabase`
- `DbPolicy` to `DatabaseAccessPolicy`

It handles legacy DBaaS JSON and YAML resources regardless of directory layout, including
`deployments/`, `<service-name>-deployments/`, and Helm chart folders.

## How it works

The skill has a strict execution boundary:

1. The coding agent discovers the repository, resolves every ambiguous value, and writes a JSON
   migration plan to a temporary file. It changes no consumer file in this phase.
2. The bundled `scripts/apply_migration.py` runner validates the plan and its source hashes,
   converts every selected legacy document, derives target names from a fixed algorithm, writes one
   canonical resource file per chart or manifest root, removes the migrated legacy documents, and
   validates the result in a temporary tree before it touches the working copy.

The runner is the only writer of migration files. Manual conversion, direct YAML generation, and
editing the runner output are not part of the workflow; a missing case is fixed in the runner and
covered by a fixture.

## Contents

- `.apm/skills/migrate-core-operator-dbaas-declarations/SKILL.md` — the discovery and plan workflow.
- `.apm/skills/migrate-core-operator-dbaas-declarations/references/mapping.md` — field-by-field
  mappings and validation rules.
- `.apm/skills/migrate-core-operator-dbaas-declarations/references/examples.md` — representative
  before-and-after manifests.
- `.apm/skills/migrate-core-operator-dbaas-declarations/scripts/apply_migration.py` — the mandatory
  migration runner. Shares its CLI, plan envelope, result envelope, and exit codes with the other
  DBaaS migration packages through the vendored `_migration_common.py`.

## Runner contract

```bash
python <skill-directory>/scripts/apply_migration.py \
  --repo-root <consumer-repository-root> \
  --plan <temporary-plan.json> \
  --apply \
  --report <temporary-result.json>
```

`--check` runs the full transformation and validation in a temporary tree and writes nothing.
Exactly one of `--check` and `--apply` is required. Exit codes: `0` success, `2` invalid CLI or
plan, `3` a source changed after discovery, `4` an unsupported or unresolved transformation, `5`
generated-output validation failed, `6` the write transaction failed and rolled back.

## Install

```sh
apm install Netcracker/qubership-dbaas/dbaas-operator/agent-packages/migrate-core-operator-dbaas-declarations
```

Then invoke the `migrate-core-operator-dbaas-declarations` skill by name and optionally provide one
or more manifest file or directory paths to define the migration scope.

## Requirements

- Python 3.11 or newer.
- PyYAML. The runner uses it for YAML sources, canonical YAML output, and temporary-tree validation,
  so it is required even for JSON-only migrations.
