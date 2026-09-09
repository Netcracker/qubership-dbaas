# DBaaS mounted-secret migration

Migrate Go, Spring, and Quarkus DBaaS services from runtime REST provisioning to
`InternalDatabase` and `DatabaseSecretClaim` resources whose generated Secrets are mounted into the
application.

## How it works

The skill has a strict execution boundary:

1. The coding agent inventories every classifier identity, database type, creation parameter, and
   requested role, proves mounted-secret compatibility for each identity, resolves the container
   selections and names, and writes a JSON migration plan to a temporary file. It changes no
   consumer file in this phase.
2. The bundled `scripts/apply_migration.py` runner validates the plan and its source hashes,
   generates one canonical `InternalDatabase` / `DatabaseSecretClaim` file, mounts every generated
   Secret read-only into the plan-selected containers, adds `DBAAS_OPERATOR_NAMESPACE` to the chart
   `values.yaml` and `values.schema.json`, removes the superseded legacy declarations, and validates
   the result with `validate_generated.py` in a temporary tree before it touches the working copy.

The runner is the only writer of migration files. Manual CR generation, workload edits, and editing
the runner output are not part of the workflow.

## Contents

- `.apm/skills/dbaas-mounted-secret-migration/SKILL.md` — the discovery and plan workflow.
- `.apm/skills/dbaas-mounted-secret-migration/references/` — the identity contract, per-framework
  discovery guides, dynamic-topology rules, and the test layers.
- `.apm/skills/dbaas-mounted-secret-migration/scripts/apply_migration.py` — the mandatory migration
  runner. Shares its CLI, plan envelope, result envelope, and exit codes with the other DBaaS
  migration packages through the vendored `_migration_common.py`.
- `.apm/skills/dbaas-mounted-secret-migration/scripts/validate_generated.py` — the inventory /
  resource / mount consistency validator, used by the runner and still callable on its own for
  diagnostics.

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
apm install Netcracker/qubership-dbaas/dbaas-operator/agent-packages/dbaas-mounted-secret-migration
```

Then invoke the `dbaas-mounted-secret-migration` skill from a service repository. Review the
inventory and the generated diff before applying it to a cluster.

## Requirements

- Python 3.11 or newer.
- PyYAML. The runner uses it for workload parsing, canonical YAML output, and temporary-tree
  validation.
