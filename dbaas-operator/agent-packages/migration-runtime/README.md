# migration-runtime

`_migration_common.py` is the shared runner contract for the two script-driven DBaaS migration
skills:

- `migrate-core-operator-dbaas-declarations`
- `dbaas-mounted-secret-migration`

It owns the parts that must behave identically in both: the `apply_migration.py` command line, the
plan and result envelopes, repository-relative path safety, SHA-256 preconditions, the fixed exit
codes, and the "build in memory, validate in a temporary tree, then commit atomically" write
transaction.

## Why it lives here

Each skill needs its own copy under `.apm/skills/<skill>/scripts/` so that the package stays
independently installable -- neither skill may depend on files outside its own package at runtime.
This directory is where the one canonical source is edited; `sync_copies.py` copies it, behind a
generated-file banner, into each package's `scripts/_migration_common.py`.

## Editing the contract

1. Edit `_migration_common.py` here.
2. Run `python agent-packages/migration-runtime/sync_copies.py` to regenerate both packages' copies.
3. Commit the source and both regenerated copies together.

`tests/test_shared_contract_drift.py` in each package re-derives the same generated content and
fails the build if a committed copy does not match -- so a source edit that was not followed by a
sync cannot merge unnoticed.

## Consumption

Each skill is installed on its own; a consumer installing `dbaas-mounted-secret-migration` gets a
complete, self-contained `scripts/` directory and never needs to see this directory or vendor it
separately. `migration-runtime/` itself is not part of either shipped package -- it is a
development-time-only source of truth for keeping the two copies in sync.
