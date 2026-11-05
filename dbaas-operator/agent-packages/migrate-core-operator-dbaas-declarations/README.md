# migrate-core-operator-dbaas-declarations

An APM package that helps coding agents migrate legacy Core Operator DBaaS database
declarations and database policies to the dedicated `dbaas-operator` custom
resources:

- `DatabaseDeclaration` to `InternalDatabase`
- `DbPolicy` to `DatabaseAccessPolicy`

It handles legacy DBaaS JSON and YAML resources regardless of directory layout,
including `deployments/`, `<service-name>-deployments/`, and Helm chart folders.

The skill migrates declarations only and does not invoke `dbaas-mounted-secret-migration`. Its
writer result lists every generated `InternalDatabase` in `internalDatabases`. When you ask for both
migrations, the agent runs this skill first and then `dbaas-mounted-secret-migration`, which reuses
those resources and adds the `DatabaseSecretClaim` resources and workload mounts. Install that
package separately for a combined migration.

## Contents

- `.apm/skills/migrate-core-operator-dbaas-declarations/SKILL.md` - the migration workflow and
  validation contract.
- `.apm/skills/migrate-core-operator-dbaas-declarations/references/mapping.md` - field-by-field
  mappings and validation rules.
- `.apm/skills/migrate-core-operator-dbaas-declarations/references/examples.md` - representative
  before-and-after manifests.
- `.apm/skills/migrate-core-operator-dbaas-declarations/scripts/apply_migration.py` - the
  deterministic writer: the only process allowed to create, modify, or delete files in the consumer
  repository, given a plan built from discovery. The CoreTPL preparation of `Chart.yaml`,
  `Chart.lock`, and `charts/` that precedes it is the one exception. See its module docstring for
  the plan shape, the `--check`/`--apply` contract, and the `internalDatabases` list the
  mounted-secret migration reads.
- `.apm/skills/migrate-core-operator-dbaas-declarations/scripts/convert_dbaas_crs.py` - the
  conversion implementation `apply_migration.py` calls. Also usable directly as a small standalone
  CLI for a single legacy file when the full plan/writer flow is not needed; its output is then a
  draft to compare against the source, not something `apply_migration.py` applies.

The writer requires `operatorNamespace` in every plan root and writes that value to every generated
CR. Use the namespace of the dbaas-operator instance, which may differ from the workload namespace.
Helm consumers must use the latest CoreTPL release that provides `coretpl.dbaasNamespace`.

## Install

```sh
apm install Netcracker/qubership-dbaas/dbaas-operator/agent-packages/migrate-core-operator-dbaas-declarations
```

Then invoke the `migrate-core-operator-dbaas-declarations` skill by name and optionally provide one
or more manifest file or directory paths to define the migration scope.

## Requirements

- Python 3 to use the optional converter.
- PyYAML when converting YAML input. JSON conversion works without it.
