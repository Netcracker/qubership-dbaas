#!/usr/bin/env python3
"""Scripted pilot for the mounted-secret migration runner.

Copies a chart fixture (or a real service chart passed on the command line) into a
scratch directory, builds a migration plan for it, and exercises the full runner
contract against it:

1. ``--check`` -- must not touch the working copy;
2. ``--apply`` -- must render the chart, validate the rendered manifests, and
   commit only the declared paths;
3. ``--apply`` again -- must be byte-for-byte idempotent (``status: unchanged``).

Usage::

    python pilot/run_pilot.py                         # bundled legacy-go-service fixture
    python pilot/run_pilot.py --chart /path/to/chart  # a real service chart

    # Render a real, already-migrated chart and run the exact rendered-manifest
    # validator the runner uses on a helm root (finding 9), against a supplied
    # inventory:
    python pilot/run_pilot.py --validate-chart <chart> --inventory <inventory.json> \
        --operator-namespace <ns> [--set K=V ...]

``helm`` must be on PATH. Exit code 0 means the pilot passed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
RUNNER = HERE.parent / ".apm" / "skills" / "dbaas-mounted-secret-migration" / "scripts" / "apply_migration.py"
DEFAULT_CHART = HERE / "fixtures" / "legacy-go-service" / "chart"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build_plan(repo: Path) -> dict:
    chart = "chart"
    output = f"{chart}/templates/dbaas-mounted-secret-resources.yaml"
    declaration = f"{chart}/templates/dbaas-declaration.yaml"
    deployment = f"{chart}/templates/deployment.yaml"
    values = f"{chart}/values.yaml"
    schema = f"{chart}/values.schema.json"

    def precondition(rel: str) -> dict:
        path = repo / rel
        return {"path": rel, "sha256": sha256(path)} if path.is_file() else {"path": rel, "absent": True}

    return {
        "schemaVersion": 1,
        "migrationKind": "mounted-secret",
        "repository": {
            "preconditions": [
                precondition(output),
                precondition(deployment),
                precondition(values),
                precondition(schema),
                precondition(declaration),
            ]
        },
        "inputs": {
            "operatorNamespace": "{{ .Values.DBAAS_OPERATOR_NAMESPACE }}",
            "datasources": [
                {
                    "id": "orders-postgresql-service",
                    "type": "postgresql",
                    "classifier": {
                        "microserviceName": "orders",
                        "namespace": "{{ .Values.NAMESPACE }}",
                        "scope": "service",
                    },
                    "requestedRoles": [""],
                    "parameters": {"namePrefix": "", "settings": {}, "physicalDatabaseId": ""},
                    "codeLocations": ["internal/storage/postgres.go:42"],
                    "migrationFeasibility": "SUPPORTED",
                    "compatibility": {
                        "mode": "NATIVE_MOUNTED_PROVIDER",
                        "evidence": "resolved client vX registers the mounted provider",
                    },
                }
            ],
        },
        "decisions": {
            "root": chart,
            "rootKind": "helm",
            "workloadNamespace": "{{ .Values.NAMESPACE }}",
            "originService": "orders",
            "claims": [
                {
                    "datasourceId": "orders-postgresql-service",
                    "role": "",
                    "workloadFile": "templates/deployment.yaml",
                    "workloadKind": "Deployment",
                    "workloadName": "orders",
                    "containers": ["orders"],
                    "initContainers": ["migrate"],
                }
            ],
            "supersededDeclarations": ["templates/dbaas-declaration.yaml"],
        },
        "targets": [
            {"path": output},
            {"path": deployment},
            {"path": values},
            {"path": schema},
            {"path": declaration},
        ],
    }


def run(repo: Path, plan_path: Path, mode: str, report: Path) -> tuple[int, dict]:
    proc = subprocess.run(
        [sys.executable, str(RUNNER), "--repo-root", str(repo), "--plan", str(plan_path),
         f"--{mode}", "--report", str(report)],
        capture_output=True, text=True, check=False,
    )
    sys.stderr.write(proc.stderr)
    return proc.returncode, json.loads(report.read_text()) if report.exists() else {}


def validate_rendered_chart(chart: Path, inventory: Path, operator_namespace: str,
                            sets: list[str]) -> int:
    """Render a real chart and run the runner's rendered-manifest validator on it."""

    sys.path.insert(0, str(RUNNER.parent))
    import validate_generated  # noqa: E402

    with tempfile.TemporaryDirectory(prefix="dbaas-pilot-") as tmp:
        out = Path(tmp) / "rendered.yaml"
        cmd = ["helm", "template", "dbaas-migration-pilot", str(chart)]
        for pair in sets:
            cmd += ["--set", pair]
        proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
        if proc.returncode != 0:
            print(f"helm template failed:\n{proc.stderr}", file=sys.stderr)
            return 1
        out.write_text(proc.stdout, encoding="utf-8")
        errors = validate_generated.validate([out], inventory, operator_namespace)
        if errors:
            print("VALIDATION ERRORS:", file=sys.stderr)
            for error in errors:
                print(f"  - {error}", file=sys.stderr)
            return 1
        rendered = proc.stdout
        kinds = {}
        for line in rendered.splitlines():
            if line.startswith("kind: "):
                kinds[line[6:]] = kinds.get(line[6:], 0) + 1
        print(f"rendered {chart.name}: {kinds}")
        print("rendered-manifest validation PASSED")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--chart", type=Path, default=DEFAULT_CHART,
                        help="a chart directory to migrate (default: the bundled fixture)")
    parser.add_argument("--validate-chart", type=Path,
                        help="render this chart and validate the rendered manifests only")
    parser.add_argument("--inventory", type=Path)
    parser.add_argument("--operator-namespace")
    parser.add_argument("--set", dest="sets", action="append", default=[])
    args = parser.parse_args()
    if shutil.which("helm") is None:
        print("FAIL: helm is not on PATH", file=sys.stderr)
        return 2

    if args.validate_chart is not None:
        if args.inventory is None or args.operator_namespace is None:
            parser.error("--validate-chart needs --inventory and --operator-namespace")
        return validate_rendered_chart(
            args.validate_chart, args.inventory, args.operator_namespace, args.sets
        )

    with tempfile.TemporaryDirectory(prefix="dbaas-pilot-") as tmp:
        work = Path(tmp)
        repo = work / "repo"
        shutil.copytree(args.chart, repo / "chart")
        plan = build_plan(repo)
        plan_path = work / "plan.json"
        plan_path.write_text(json.dumps(plan, indent=2))

        before = {p.relative_to(repo).as_posix(): p.read_bytes() for p in repo.rglob("*") if p.is_file()}
        code, report = run(repo, plan_path, "check", work / "check.json")
        after = {p.relative_to(repo).as_posix(): p.read_bytes() for p in repo.rglob("*") if p.is_file()}
        assert code == 0, f"check exited {code}: {report}"
        assert before == after, "check mode changed the working copy"
        assert all(v["status"] == "passed" for v in report["validation"]), report["validation"]
        print("check: ok (no writes, validation passed)")

        # The plan pins the current file hashes; check mode did not change them.
        code, report = run(repo, plan_path, "apply", work / "apply.json")
        assert code == 0, f"apply exited {code}: {report}"
        assert report["status"] == "changed", report
        touched = set(report["createdFiles"]) | set(report["modifiedFiles"]) | set(report["deletedFiles"])
        assert touched == {t["path"] for t in plan["targets"]} - set(report["unchangedFiles"]), touched
        print(f"apply: ok (status=changed, touched={sorted(touched)})")
        print("rendered manifests:")
        for line in (work / "apply.json").read_text().splitlines():
            if "validate_rendered" in line or "helm-render" in line:
                print("  " + line.strip())

        first = (repo / "chart/templates/dbaas-mounted-secret-resources.yaml").read_bytes()
        deployment_after = (repo / "chart/templates/deployment.yaml").read_text()
        assert "replicas: {{ .Values.replicaCount }}" in deployment_after, "templated replicas not preserved"
        assert "# keep this comment exactly where it is" in deployment_after, "comment lost"
        print("workload patch preserved templated replicas and comments")

        plan2 = build_plan(repo)
        (work / "plan2.json").write_text(json.dumps(plan2, indent=2))
        code, report = run(repo, work / "plan2.json", "apply", work / "apply2.json")
        assert code == 0, f"second apply exited {code}: {report}"
        assert report["status"] == "unchanged", report
        assert (repo / "chart/templates/dbaas-mounted-secret-resources.yaml").read_bytes() == first
        print("second apply: ok (status=unchanged, byte-for-byte identical)")

    print("\nPILOT PASSED")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as exc:
        print(f"\nPILOT FAILED: {exc}", file=sys.stderr)
        raise SystemExit(1)
