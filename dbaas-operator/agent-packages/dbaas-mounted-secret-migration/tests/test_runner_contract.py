"""Black-box contract assertions for the mounted-secret runner.

These mirror the core-declarations package's contract test so the shared CLI,
plan envelope, result envelope, and exit codes cannot drift between runners.
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from _harness import RUNNER, run_migration
from test_apply_migration import plan, scaffold


class RunnerContractTest(unittest.TestCase):
    def test_check_never_changes_the_repository(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            snapshot = {p: p.read_bytes() for p in repo.rglob("*") if p.is_file()}
            code, report = run_migration(repo, plan(repo), "check", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            after = {p: p.read_bytes() for p in repo.rglob("*") if p.is_file()}
            self.assertEqual(snapshot, after)

    def test_apply_changes_only_declared_paths(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            (repo / "chart" / "README.md").write_text("keep\n", encoding="utf-8")
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertEqual((repo / "chart" / "README.md").read_text(encoding="utf-8"), "keep\n")
            touched = set(report["createdFiles"]) | set(report["modifiedFiles"]) | set(report["deletedFiles"])
            self.assertTrue(touched)
            self.assertNotIn("chart/README.md", touched)

    def test_repeated_apply_is_idempotent(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            self.assertEqual(run_migration(repo, plan(repo), "apply", tmp)[0], 0)
            output = repo / "chart/templates/dbaas-mounted-secret-resources.yaml"
            first = output.read_bytes()
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertEqual(report["status"], "unchanged")
            self.assertEqual(output.read_bytes(), first)

    def test_stale_hash_fails_before_writing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo)
            (repo / "chart/values.yaml").write_text("NAMESPACE: orders-ns\n", encoding="utf-8")
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 3)
            self.assertFalse((repo / "chart/templates/dbaas-mounted-secret-resources.yaml").exists())

    def test_unknown_plan_field_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo)
            the_plan["nope"] = 1
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 2)

    def test_exactly_one_mode_is_required(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            plan_path = tmp / "plan.json"
            plan_path.write_text(json.dumps(plan(repo)), encoding="utf-8")
            for extra in ([], ["--check", "--apply"]):
                result = subprocess.run(
                    [sys.executable, str(RUNNER), "--repo-root", str(repo), "--plan", str(plan_path), *extra],
                    capture_output=True,
                    text=True,
                    check=False,
                )
                self.assertEqual(result.returncode, 2)

    def test_result_envelope_shape(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            code, report = run_migration(repo, plan(repo), "check", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            for key in (
                "schemaVersion",
                "migrationKind",
                "status",
                "createdFiles",
                "modifiedFiles",
                "deletedFiles",
                "unchangedFiles",
                "warnings",
                "validation",
            ):
                self.assertIn(key, report)
            self.assertEqual(report["migrationKind"], "mounted-secret")


if __name__ == "__main__":
    unittest.main()
