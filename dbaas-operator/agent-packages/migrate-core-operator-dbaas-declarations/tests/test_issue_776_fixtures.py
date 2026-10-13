"""Issue #776 regression fixtures: minimal, local layouts modeled on the
public patterns described in the issue (see fixtures/issue_776/README.md),
each exercised through the real writer's --check without any source
preprocessing.
"""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - optional test dependency
    yaml = None

PACKAGE_ROOT = Path(__file__).resolve().parent.parent
SCRIPTS = PACKAGE_ROOT / ".apm" / "skills" / "migrate-core-operator-dbaas-declarations" / "scripts"
RUNNER = SCRIPTS / "apply_migration.py"
FIXTURES = Path(__file__).resolve().parent / "fixtures" / "issue_776"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def copy_fixture(name: str, tmp: Path) -> Path:
    repo = tmp / "repo"
    dest = repo / "chart"
    shutil.copytree(FIXTURES / name, dest)
    return repo


def run_check(repo: Path, the_plan: dict, tmp: Path) -> tuple[int, dict]:
    plan_path = tmp / "plan.json"
    plan_path.write_text(json.dumps(the_plan, indent=2), encoding="utf-8")
    result = subprocess.run(
        [sys.executable, str(RUNNER), "--repo-root", str(repo), "--plan", str(plan_path), "--check"],
        capture_output=True, text=True, check=False,
    )
    try:
        parsed = json.loads(result.stdout) if result.stdout.strip() else {}
    except ValueError:
        parsed = {}
    parsed["__stderr"] = result.stderr
    return result.returncode, parsed


@unittest.skipUnless(shutil.which("helm"), "helm is not on PATH")
@unittest.skipIf(yaml is None, "PyYAML is required")
class Issue776FixturesTest(unittest.TestCase):
    def test_config_server_like_wrapper_labels_dgp_only_and_unquoted_names(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = copy_fixture("config-server-like", tmp)
            source = repo / "chart/dbaas-declaration.yaml"
            the_plan = {
                "schemaVersion": 1,
                "roots": [
                    {
                        "root": "chart",
                        "kind": "helm",
                        "operatorNamespace": "dbaas-system",
                        "serviceName": "{{ .Values.SERVICE_NAME }}",
                        "namespace": "{{ .Values.NAMESPACE }}",
                        "outputFile": "dbaas-operator-resources.yaml",
                        "sources": [{"path": "chart/dbaas-declaration.yaml", "sha256": sha256(source)}],
                    }
                ],
            }
            code, report = run_check(repo, the_plan, tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertEqual(report["status"], "valid")

    def test_site_management_like_classifier_extension_and_dgp_only_false(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = copy_fixture("site-management-like", tmp)
            source = repo / "chart/dbaas-declaration.yaml"
            the_plan = {
                "schemaVersion": 1,
                "roots": [
                    {
                        "root": "chart",
                        "kind": "helm",
                        "operatorNamespace": "dbaas-system",
                        "serviceName": "site-management",
                        "namespace": "{{ .Values.NAMESPACE }}",
                        "outputFile": "dbaas-operator-resources.yaml",
                        "sources": [{"path": "chart/dbaas-declaration.yaml", "sha256": sha256(source)}],
                    }
                ],
            }
            code, report = run_check(repo, the_plan, tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertEqual(report["status"], "valid")


if __name__ == "__main__":
    unittest.main()
