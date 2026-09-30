# migrate-core-operator-dbaas-declarations

An APM package that helps coding agents migrate legacy Core Operator DBaaS database
declarations and database policies to the dedicated `dbaas-operator` custom
resources:

- `DatabaseDeclaration` to `InternalDatabase`
- `DbPolicy` to `DatabaseAccessPolicy`

It handles legacy DBaaS JSON and YAML resources regardless of directory layout,
including `deployments/`, `<service-name>-deployments/`, and Helm chart folders.

## Contents

- `.apm/skills/migrate-core-operator-dbaas-declarations/SKILL.md` - the migration workflow and
  validation contract.
- `.apm/skills/migrate-core-operator-dbaas-declarations/references/mapping.md` - field-by-field
  mappings and validation rules.
- `.apm/skills/migrate-core-operator-dbaas-declarations/references/examples.md` - representative
  before-and-after manifests.
- `.apm/skills/migrate-core-operator-dbaas-declarations/scripts/apply_migration.py` - the
  deterministic writer: the only process allowed to create, modify, or delete files in the consumer
  repository, given a plan built from discovery. See its module docstring for the plan shape and the
  `--check`/`--apply` contract.
- `.apm/skills/migrate-core-operator-dbaas-declarations/scripts/convert_dbaas_crs.py` - the
  conversion implementation `apply_migration.py` calls. Also usable directly as a small standalone
  CLI for a single legacy file when the full plan/writer flow is not needed; its output is then a
  draft to compare against the source, not something `apply_migration.py` applies.

The writer requires `operatorNamespace` in every plan root and writes that value to every generated
CR. Use the namespace of the dbaas-operator instance, which may differ from the workload namespace.

## Install

```sh
apm install Netcracker/qubership-dbaas/dbaas-operator/agent-packages/migrate-core-operator-dbaas-declarations
```

Then invoke the `migrate-core-operator-dbaas-declarations` skill by name and
optionally provide one or more manifest file or directory paths to define the
migration scope.

## Requirements

- Python 3 to use the optional converter.
- PyYAML when converting YAML input. JSON conversion works without it.
