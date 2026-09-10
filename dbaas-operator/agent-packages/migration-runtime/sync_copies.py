#!/usr/bin/env python3
"""Copy the canonical ``_migration_common.py`` into each skill package.

Each migration skill is an independently installable package, so it may not
depend on anything outside its own ``.apm/skills/<skill>/scripts/`` directory
at runtime. This script is the packaging-time step that keeps that true while
still giving the two packages one canonical, single-edited source: run it
after editing ``_migration_common.py`` here, then commit both this file's
source and the regenerated copies it writes.

``tests/test_shared_contract_drift.py`` in each package re-derives the same
banner and content and fails the build if a committed copy is stale.
"""

from __future__ import annotations

from pathlib import Path

RUNTIME_DIR = Path(__file__).resolve().parent
SOURCE = RUNTIME_DIR / "_migration_common.py"

# (package directory name, skill name) -- the skill name differs from the
# package directory only in that both happen to match today, but a package
# renaming its skill without renaming its directory must not silently miss a
# copy, so the skill name is listed explicitly rather than derived.
PACKAGES = [
    ("migrate-core-operator-dbaas-declarations", "migrate-core-operator-dbaas-declarations"),
    ("dbaas-mounted-secret-migration", "dbaas-mounted-secret-migration"),
]

BANNER = (
    "# GENERATED FILE -- do not edit.\n"
    "# Canonical source: agent-packages/migration-runtime/_migration_common.py\n"
    "# Regenerate with: python agent-packages/migration-runtime/sync_copies.py\n"
)


def generated_content() -> str:
    return BANNER + SOURCE.read_text(encoding="utf-8")


def copy_targets() -> list[Path]:
    agent_packages = RUNTIME_DIR.parent
    return [
        agent_packages / package / ".apm" / "skills" / skill / "scripts" / "_migration_common.py"
        for package, skill in PACKAGES
    ]


def main() -> int:
    content = generated_content()
    for target in copy_targets():
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8", newline="")
        print(f"wrote {target.relative_to(RUNTIME_DIR.parent.parent)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
