"""Issue #776 regression fixtures: minimal, local chart layouts modeled on
publicly documented patterns (a config-server-like conditional workload, a
site-management-like topology range, a control-plane-like legacy-credential
fallback), each exercised through the real writer's --check without any
source preprocessing.
"""

from __future__ import annotations

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

PACKAGE_ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = PACKAGE_ROOT / ".apm" / "skills" / "dbaas-mounted-secret-migration" / "scripts"
RUNNER = SCRIPTS / "apply_migration.py"
FIXTURES = Path(__file__).resolve().parent / "fixtures" / "issue_776"


def sha256(path: Path) -> str:
    import hashlib

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
    def test_config_server_like_conditional_volumes_and_mounts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = copy_fixture("config-server-like", tmp)
            deployment = repo / "chart/templates/deployment.yaml"
            the_plan = {
                "roots": [
                    {
                        "root": "chart",
                        "kind": "helm",
                        "outputFile": "templates/dbaas-mounted-secret-resources.yaml",
                        "operatorNamespace": "{{ .Values.DBAAS_OPERATOR_NAMESPACE }}",
                        "workloadNamespace": "{{ .Values.NAMESPACE }}",
                        "originService": "config-server",
                        "datasources": [
                            {
                                "id": "config-server-postgresql-service",
                                "type": "postgresql",
                                "classifier": {"microserviceName": "config-server", "scope": "service"},
                                "requestedRoles": [""],
                                "parameters": {},
                            }
                        ],
                        "claims": [
                            {
                                "datasourceId": "config-server-postgresql-service", "role": "",
                                "workloadFile": "templates/deployment.yaml", "workloadKind": "Deployment",
                                "workloadName": "config-server", "containers": ["config-server"],
                                "initContainers": [],
                            }
                        ],
                        "sourceHashes": {"templates/deployment.yaml": sha256(deployment)},
                    }
                ]
            }
            code, report = run_check(repo, the_plan, tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertEqual(report["status"], "valid")

    def test_site_management_like_topology_range_and_conditional_env(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = copy_fixture("site-management-like", tmp)
            deployment = repo / "chart/templates/deployment.yaml"
            the_plan = {
                "roots": [
                    {
                        "root": "chart",
                        "kind": "helm",
                        "outputFile": "templates/dbaas-mounted-secret-resources.yaml",
                        "operatorNamespace": "{{ .Values.DBAAS_OPERATOR_NAMESPACE }}",
                        "workloadNamespace": "{{ .Values.NAMESPACE }}",
                        "originService": "site-management",
                        "datasources": [
                            {
                                "id": "site-management-mongodb-service",
                                "type": "mongodb",
                                "classifier": {"microserviceName": "site-management", "scope": "service"},
                                "requestedRoles": [""],
                                "parameters": {},
                            }
                        ],
                        "claims": [
                            {
                                "datasourceId": "site-management-mongodb-service", "role": "",
                                "workloadFile": "templates/deployment.yaml", "workloadKind": "Deployment",
                                "workloadName": "site-management", "containers": ["site-management"],
                                "initContainers": [],
                            }
                        ],
                        "sourceHashes": {"templates/deployment.yaml": sha256(deployment)},
                    }
                ]
            }
            code, report = run_check(repo, the_plan, tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertEqual(report["status"], "valid")

    def test_control_plane_like_operator_mode_environment_under_capability_guard(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = copy_fixture("control-plane-like", tmp)
            deployment = repo / "chart/templates/deployment.yaml"
            the_plan = {
                "roots": [
                    {
                        "root": "chart",
                        "kind": "helm",
                        "outputFile": "templates/dbaas-mounted-secret-resources.yaml",
                        "operatorNamespace": (
                            '{{ (index (splitList "." (first (splitList ":" '
                            '(last (splitList "://" $.Values.API_DBAAS_ADDRESS))))) 1) }}'
                        ),
                        "workloadNamespace": "{{ .Values.NAMESPACE }}",
                        "originService": "control-plane",
                        "datasources": [
                            {
                                "id": "control-plane-postgresql-service",
                                "type": "postgresql",
                                "classifier": {"microserviceName": "control-plane", "scope": "service"},
                                "requestedRoles": [""],
                                "parameters": {},
                            }
                        ],
                        "claims": [
                            {
                                "datasourceId": "control-plane-postgresql-service", "role": "",
                                "workloadFile": "templates/deployment.yaml", "workloadKind": "Deployment",
                                "workloadName": "control-plane", "containers": ["control-plane"],
                                "initContainers": [],
                            }
                        ],
                        "sourceHashes": {"templates/deployment.yaml": sha256(deployment)},
                        "capabilityGuard": "dbaas.netcracker.com/v1",
                        "operatorModeEnvironment": {"name": "DBAAS_OPERATOR_ENABLED", "value": "true"},
                    }
                ]
            }
            code, report = run_check(repo, the_plan, tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertEqual(report["status"], "valid")


if __name__ == "__main__":
    unittest.main()
