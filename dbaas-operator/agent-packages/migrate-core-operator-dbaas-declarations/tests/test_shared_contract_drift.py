"""Guard against the vendored runner contract drifting between packages.

Each migration package ships its own copy of ``_migration_common.py`` so it stays
independently installable. When both packages are checked out together (the
monorepo layout), their copies must be byte-identical.
"""

from __future__ import annotations

import hashlib
import unittest
from pathlib import Path

from _harness import SCRIPTS

SIBLINGS = [
    Path(__file__).resolve().parents[2]
    / "dbaas-mounted-secret-migration"
    / ".apm"
    / "skills"
    / "dbaas-mounted-secret-migration"
    / "scripts"
    / "_migration_common.py",
]


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class SharedContractDriftTest(unittest.TestCase):
    def test_migration_common_matches_sibling_packages(self) -> None:
        local = SCRIPTS / "_migration_common.py"
        self.assertTrue(local.is_file())
        for sibling in SIBLINGS:
            if not sibling.is_file():
                self.skipTest(f"sibling package not checked out: {sibling}")
            self.assertEqual(
                digest(local),
                digest(sibling),
                f"_migration_common.py differs from {sibling}",
            )


if __name__ == "__main__":
    unittest.main()
