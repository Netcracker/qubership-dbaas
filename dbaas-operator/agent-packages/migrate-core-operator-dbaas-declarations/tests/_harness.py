"""Shared helpers for the core-declarations runner tests."""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
from pathlib import Path
from typing import Any

PACKAGE_ROOT = Path(__file__).resolve().parents[1]
SKILL_DIR = PACKAGE_ROOT / ".apm" / "skills" / "migrate-core-operator-dbaas-declarations"
SCRIPTS = SKILL_DIR / "scripts"
RUNNER = SCRIPTS / "apply_migration.py"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run_migration(repo_root: Path, plan: dict[str, Any], mode: str, tmp: Path) -> tuple[int, dict[str, Any]]:
    plan_path = tmp / "plan.json"
    report_path = tmp / "report.json"
    plan_path.write_text(json.dumps(plan, indent=2), encoding="utf-8")
    result = subprocess.run(
        [
            sys.executable,
            str(RUNNER),
            "--repo-root",
            str(repo_root),
            "--plan",
            str(plan_path),
            f"--{mode}",
            "--report",
            str(report_path),
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    report: dict[str, Any] = {}
    if report_path.exists():
        report = json.loads(report_path.read_text(encoding="utf-8"))
    report["__stderr"] = result.stderr
    return result.returncode, report


def targets_for(*relatives: str) -> list[dict[str, Any]]:
    return [{"path": relative, "ownership": "own"} for relative in relatives]


def preconditions_for(repo_root: Path, *relatives: str) -> list[dict[str, Any]]:
    entries: list[dict[str, Any]] = []
    for relative in relatives:
        path = repo_root / relative
        if path.is_file():
            entries.append({"path": relative, "sha256": sha256(path)})
        else:
            entries.append({"path": relative, "absent": True})
    return entries
