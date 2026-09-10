"""Fail the build if this package's ``_migration_common.py`` copy is stale.

The canonical source is ``agent-packages/migration-runtime/_migration_common.py``;
``sync_copies.py`` in that directory copies it, behind a generated-file banner,
into this package's own ``scripts/_migration_common.py`` so the package stays
independently installable. This test re-derives the same banner and content and
compares it byte-for-byte against the committed copy, so an edit to the
canonical source that was not followed by re-running ``sync_copies.py`` fails
here instead of silently drifting.
"""

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

PACKAGE_ROOT = Path(__file__).resolve().parents[1]
RUNTIME_DIR = PACKAGE_ROOT.parent / "migration-runtime"
COPY_PATH = (
    PACKAGE_ROOT
    / ".apm"
    / "skills"
    / "dbaas-mounted-secret-migration"
    / "scripts"
    / "_migration_common.py"
)


def _load_sync_copies():
    spec = importlib.util.spec_from_file_location(
        "sync_copies", RUNTIME_DIR / "sync_copies.py"
    )
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class SharedContractDriftTest(unittest.TestCase):
    def test_local_copy_matches_the_canonical_source(self) -> None:
        sync_copies = _load_sync_copies()
        expected = sync_copies.generated_content()
        actual = COPY_PATH.read_text(encoding="utf-8")
        self.assertEqual(
            actual,
            expected,
            "scripts/_migration_common.py is stale; edit "
            "agent-packages/migration-runtime/_migration_common.py and re-run "
            "agent-packages/migration-runtime/sync_copies.py",
        )


if __name__ == "__main__":
    unittest.main()
