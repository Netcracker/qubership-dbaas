"""End-to-end tests of a combined migration -- the declaration writer of this
package, then the mounted-secret writer of its sibling package, which reuses the
InternalDatabase resources the declaration writer listed in its result -- and of
each package used without the other."""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any

try:
    import yaml
except ImportError:  # pragma: no cover - optional test dependency
    yaml = None

PACKAGE_ROOT = Path(__file__).resolve().parents[1]
MOUNTED_PACKAGE_ROOT = PACKAGE_ROOT.parent / "dbaas-mounted-secret-migration"
DECLARATION_RUNNER = (
    PACKAGE_ROOT / ".apm/skills/migrate-core-operator-dbaas-declarations/scripts/apply_migration.py"
)
MOUNTED_RUNNER = MOUNTED_PACKAGE_ROOT / ".apm/skills/dbaas-mounted-secret-migration/scripts/apply_migration.py"

OPERATOR_NAMESPACE = '{{ include "coretpl.dbaasNamespace" $ }}'
DECLARATIONS = (
    "apiVersion: nc.core.dbaas/v3\n"
    "kind: DatabaseDeclaration\n"
    "declarations:\n"
    "  - classifierConfig:\n"
    "      classifier:\n"
    "        microserviceName: orders\n"
    "        scope: service\n"
    "    type: postgresql\n"
    "---\n"
    "apiVersion: nc.core.dbaas/v3\n"
    "kind: DatabaseDeclaration\n"
    "declarations:\n"
    "  - classifierConfig:\n"
    "      classifier:\n"
    "        microserviceName: orders\n"
    "        scope: service\n"
    "    type: mongodb\n"
)
DEPLOYMENT = (
    "apiVersion: apps/v1\n"
    "kind: Deployment\n"
    "metadata:\n"
    "  name: orders\n"
    "  namespace: '{{ .Values.NAMESPACE }}'\n"
    "spec:\n"
    "  template:\n"
    "    spec:\n"
    "      containers:\n"
    "        - name: orders\n"
    "          image: orders:latest\n"
)
DECLARATION_OUTPUT = "templates/dbaas-operator-resources.yaml"
MOUNTED_OUTPUT = "templates/dbaas-mounted-secret-resources.yaml"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def scaffold(tmp: Path) -> Path:
    repo = tmp / "repo"
    files = {
        "chart/Chart.yaml": "apiVersion: v2\nname: orders\nversion: 0.1.0\n",
        "chart/values.yaml": "NAMESPACE: orders-ns\n",
        "chart/templates/_coretpl.tpl": '{{- define "coretpl.dbaasNamespace" -}}dbaas-operator{{- end -}}\n',
        "chart/templates/dbaas-declaration.yaml": DECLARATIONS,
        "chart/templates/deployment.yaml": DEPLOYMENT,
    }
    for rel, content in files.items():
        (repo / rel).parent.mkdir(parents=True, exist_ok=True)
        (repo / rel).write_text(content, encoding="utf-8")
    return repo


def run(runner: Path, repo: Path, plan: dict[str, Any], mode: str, tmp: Path) -> tuple[int, dict[str, Any]]:
    plan_path = tmp / "plan.json"
    plan_path.write_text(json.dumps(plan), encoding="utf-8")
    proc = subprocess.run(
        [sys.executable, str(runner), "--repo-root", str(repo), "--plan", str(plan_path), f"--{mode}"],
        capture_output=True, text=True, check=False,
    )
    report = json.loads(proc.stdout) if proc.stdout.strip() else {}
    report["__stdout"] = proc.stdout
    return proc.returncode, report


def declaration_plan(repo: Path) -> dict[str, Any]:
    output = repo / "chart" / DECLARATION_OUTPUT
    root = {
        "root": "chart",
        "kind": "helm",
        "operatorNamespace": OPERATOR_NAMESPACE,
        "serviceName": "orders",
        "namespace": "{{ .Values.NAMESPACE }}",
        "outputFile": DECLARATION_OUTPUT,
        "sources": [
            {"path": "chart/templates/dbaas-declaration.yaml",
             "sha256": sha256(repo / "chart/templates/dbaas-declaration.yaml")},
        ],
    }
    if output.is_file():
        root["outputSha256"] = sha256(output)
    return {"schemaVersion": 1, "roots": [root]}


def reused(handoff: list[dict[str, Any]], db_type: str) -> dict[str, Any]:
    """The mounted-secret internalDatabase field for the handoff entry of ``db_type``."""

    [entry] = [item for item in handoff if item["type"] == db_type]
    return {
        "mode": "existing",
        "path": entry["rootRelativeOutputFile"],
        "sha256": entry["outputSha256"],
        "name": entry["name"],
        "documentIndex": entry["documentIndex"],
    }


def mounted_plan(repo: Path, handoff: list[dict[str, Any]], db_types: list[str]) -> dict[str, Any]:
    datasources = [
        {
            "id": f"orders-{db_type}",
            "type": db_type,
            "classifier": {"microserviceName": "orders", "scope": "service"},
            "requestedRoles": [""],
            "internalDatabase": reused(handoff, db_type),
        }
        for db_type in db_types
    ]
    claims = [
        {
            "datasourceId": f"orders-{db_type}", "role": "",
            "workloadFile": "templates/deployment.yaml", "workloadKind": "Deployment",
            "workloadName": "orders", "containers": ["orders"], "initContainers": [],
        }
        for db_type in db_types
    ]
    root = {
        "root": "chart",
        "kind": "helm",
        "outputFile": MOUNTED_OUTPUT,
        "operatorNamespace": OPERATOR_NAMESPACE,
        "workloadNamespace": "{{ .Values.NAMESPACE }}",
        "originService": "orders",
        "datasources": datasources,
        "claims": claims,
        "sourceHashes": {"templates/deployment.yaml": sha256(repo / "chart/templates/deployment.yaml")},
    }
    output = repo / "chart" / MOUNTED_OUTPUT
    if output.is_file():
        root["outputSha256"] = sha256(output)
    return {"roots": [root]}


def kinds(path: Path) -> list[tuple[str, str]]:
    """The ``(kind, spec.type)`` of every resource in a generated file, guards removed."""

    lines = [line for line in path.read_text(encoding="utf-8").splitlines() if not line.startswith("{{-")]
    return sorted(
        (doc["kind"], doc["spec"].get("type", "")) for doc in yaml.safe_load_all("\n".join(lines)) if doc
    )


@unittest.skipUnless(shutil.which("helm") and yaml is not None, "helm and PyYAML are required")
class ChainedMigrationTest(unittest.TestCase):
    def migrate_declarations(self, repo: Path, tmp: Path) -> list[dict[str, Any]]:
        code, checked = run(DECLARATION_RUNNER, repo, declaration_plan(repo), "check", tmp)
        self.assertEqual(code, 0, checked["__stdout"])
        code, applied = run(DECLARATION_RUNNER, repo, declaration_plan(repo), "apply", tmp)
        self.assertEqual(code, 0, applied["__stdout"])
        self.assertEqual(applied["internalDatabases"], checked["internalDatabases"])
        return applied["internalDatabases"]

    def test_the_mounted_secret_writer_reuses_every_database_of_the_declaration_writer(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            handoff = self.migrate_declarations(repo, tmp)
            self.assertEqual(
                (repo / "chart/templates/deployment.yaml").read_text(encoding="utf-8"), DEPLOYMENT
            )
            declared = (repo / "chart" / DECLARATION_OUTPUT).read_bytes()

            code, report = run(
                MOUNTED_RUNNER, repo, mounted_plan(repo, handoff, ["postgresql", "mongodb"]), "apply", tmp
            )

            self.assertEqual(code, 0, report["__stdout"])
            self.assertEqual((repo / "chart" / DECLARATION_OUTPUT).read_bytes(), declared)
            self.assertEqual(
                kinds(repo / "chart" / DECLARATION_OUTPUT),
                [("InternalDatabase", "mongodb"), ("InternalDatabase", "postgresql")],
            )
            self.assertEqual(
                kinds(repo / "chart" / MOUNTED_OUTPUT),
                [("DatabaseSecretClaim", "mongodb"), ("DatabaseSecretClaim", "postgresql")],
            )
            self.assertEqual(
                (repo / "chart/templates/deployment.yaml").read_text(encoding="utf-8").count("mountPath:"), 2
            )

    def test_a_blocked_mounted_secret_run_keeps_the_declaration_result(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            handoff = self.migrate_declarations(repo, tmp)
            declared = (repo / "chart" / DECLARATION_OUTPUT).read_bytes()
            plan = mounted_plan(repo, handoff, ["postgresql"])
            plan["roots"][0]["datasources"][0]["migrationFeasibility"] = "BLOCKED"

            code, report = run(MOUNTED_RUNNER, repo, plan, "apply", tmp)

            self.assertEqual(code, 2, report["__stdout"])
            self.assertEqual((repo / "chart" / DECLARATION_OUTPUT).read_bytes(), declared)
            self.assertFalse((repo / "chart" / MOUNTED_OUTPUT).exists())
            self.assertEqual(
                (repo / "chart/templates/deployment.yaml").read_text(encoding="utf-8"), DEPLOYMENT
            )

    def test_a_rerun_after_a_client_upgrade_adds_only_the_new_claim(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            handoff = self.migrate_declarations(repo, tmp)
            # The mongodb client cannot read mounted Secrets yet, so the first
            # mounted-secret run covers postgresql only.
            code, report = run(MOUNTED_RUNNER, repo, mounted_plan(repo, handoff, ["postgresql"]), "apply", tmp)
            self.assertEqual(code, 0, report["__stdout"])
            declared = (repo / "chart" / DECLARATION_OUTPUT).read_bytes()

            code, rerun = run(DECLARATION_RUNNER, repo, declaration_plan(repo), "apply", tmp)
            self.assertEqual(code, 0, rerun["__stdout"])
            self.assertEqual(rerun["status"], "unchanged")
            self.assertEqual(rerun["internalDatabases"], handoff)
            code, upgraded = run(
                MOUNTED_RUNNER, repo, mounted_plan(repo, rerun["internalDatabases"], ["postgresql", "mongodb"]),
                "apply", tmp,
            )

            self.assertEqual(code, 0, upgraded["__stdout"])
            self.assertEqual((repo / "chart" / DECLARATION_OUTPUT).read_bytes(), declared)
            self.assertEqual(
                kinds(repo / "chart" / MOUNTED_OUTPUT),
                [("DatabaseSecretClaim", "mongodb"), ("DatabaseSecretClaim", "postgresql")],
            )

    def test_a_completed_combined_migration_is_unchanged_on_rerun(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            handoff = self.migrate_declarations(repo, tmp)
            secret_plan = mounted_plan(repo, handoff, ["postgresql", "mongodb"])
            code, report = run(MOUNTED_RUNNER, repo, secret_plan, "apply", tmp)
            self.assertEqual(code, 0, report["__stdout"])
            migrated = {path: path.read_bytes() for path in (repo / "chart").rglob("*") if path.is_file()}

            code, declarations = run(DECLARATION_RUNNER, repo, declaration_plan(repo), "apply", tmp)
            self.assertEqual(code, 0, declarations["__stdout"])
            code, secrets = run(MOUNTED_RUNNER, repo, secret_plan, "apply", tmp)
            self.assertEqual(code, 0, secrets["__stdout"])

            self.assertEqual([declarations["status"], secrets["status"]], ["unchanged", "unchanged"])
            self.assertEqual(
                {path: path.read_bytes() for path in (repo / "chart").rglob("*") if path.is_file()}, migrated
            )


def apm_dependencies(package_root: Path) -> list[str]:
    manifest = yaml.safe_load((package_root / "apm.yml").read_text(encoding="utf-8")) or {}
    return list(((manifest.get("dependencies") or {}).get("apm")) or [])


def copy_alone(package_root: Path, tmp: Path, runner: Path) -> Path:
    """Copy one package, without its sibling, and return its writer in the copy."""

    copy = tmp / "packages" / package_root.name
    shutil.copytree(package_root, copy, ignore=shutil.ignore_patterns("__pycache__"))
    return copy / runner.relative_to(package_root)


def standalone_mounted_plan(repo: Path) -> dict[str, Any]:
    """A mounted-secret plan with no internalDatabase field, as a 4.0.0 plan has."""

    plan = mounted_plan(repo, [], [])
    plan["roots"][0]["datasources"] = [{
        "id": "orders-postgresql", "type": "postgresql",
        "classifier": {"microserviceName": "orders", "scope": "service"}, "requestedRoles": [""],
    }]
    plan["roots"][0]["claims"] = [{
        "datasourceId": "orders-postgresql", "role": "",
        "workloadFile": "templates/deployment.yaml", "workloadKind": "Deployment",
        "workloadName": "orders", "containers": ["orders"], "initContainers": [],
    }]
    return plan


@unittest.skipUnless(shutil.which("helm") and yaml is not None, "helm and PyYAML are required")
class PackageIndependenceTest(unittest.TestCase):
    def test_neither_package_depends_on_the_other(self) -> None:
        self.assertNotIn("../dbaas-mounted-secret-migration", apm_dependencies(PACKAGE_ROOT))
        self.assertNotIn("../migrate-core-operator-dbaas-declarations", apm_dependencies(MOUNTED_PACKAGE_ROOT))

    def test_the_declaration_writer_runs_without_the_mounted_secret_package(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            runner = copy_alone(PACKAGE_ROOT, tmp, DECLARATION_RUNNER)

            code, report = run(runner, repo, declaration_plan(repo), "apply", tmp)

            self.assertEqual(code, 0, report["__stdout"])
            self.assertEqual([entry["type"] for entry in report["internalDatabases"]], ["postgresql", "mongodb"])
            self.assertFalse((repo / "chart" / MOUNTED_OUTPUT).exists())
            self.assertEqual((repo / "chart/templates/deployment.yaml").read_text(encoding="utf-8"), DEPLOYMENT)

    def test_the_mounted_secret_writer_runs_without_the_declaration_package(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            # A service with no legacy declarations: nothing for the declaration
            # migration to convert.
            (repo / "chart/templates/dbaas-declaration.yaml").unlink()
            runner = copy_alone(MOUNTED_PACKAGE_ROOT, tmp, MOUNTED_RUNNER)

            code, report = run(runner, repo, standalone_mounted_plan(repo), "apply", tmp)

            self.assertEqual(code, 0, report["__stdout"])
            self.assertEqual(
                kinds(repo / "chart" / MOUNTED_OUTPUT),
                [("DatabaseSecretClaim", "postgresql"), ("InternalDatabase", "postgresql")],
            )
            self.assertEqual(report["reusedInternalDatabases"], [])
            self.assertFalse((repo / "chart" / DECLARATION_OUTPUT).exists())


if __name__ == "__main__":
    unittest.main()
