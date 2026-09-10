"""Regression tests for the shared runner contract (_migration_common).

The single copy lives in agent-packages/migration-runtime/; _harness puts it on
sys.path. The mounted-secret package has a parallel test for the same contract.
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from _harness import RUNNER, run_migration  # noqa: F401

import _migration_common as common  # noqa: E402

from test_runner_contract import plan_for, scaffold  # noqa: E402


class CommonRunnerTest(unittest.TestCase):
    def test_help_exits_zero(self) -> None:
        result = subprocess.run(
            [sys.executable, str(RUNNER), "--help"], capture_output=True, text=True, check=False
        )
        self.assertEqual(result.returncode, 0)
        self.assertIn("--repo-root", result.stdout)

    def test_missing_dependency_is_a_blocked_result(self) -> None:
        class FakeEngine:
            migration_kind = "core-declarations"
            required_modules = ("definitely_missing_module_xyz",)

        with self.assertRaises(common.MigrationError) as ctx:
            common._check_dependencies(FakeEngine())
        self.assertEqual(ctx.exception.exit_code, common.EXIT_UNSUPPORTED)

    def test_falsy_wrong_type_envelope_values_are_rejected(self) -> None:
        for key, bad in (("repository", []), ("inputs", []), ("decisions", []), ("targets", {})):
            with tempfile.TemporaryDirectory() as directory:
                tmp = Path(directory)
                repo, source_rel, output_rel = scaffold(tmp)
                the_plan = plan_for(repo, source_rel, output_rel)
                the_plan[key] = bad
                code, report = run_migration(repo, the_plan, "check", tmp)
                self.assertEqual(code, 2, key)
                self.assertIn(f"plan.{key}", report["validation"][0]["details"])

    def test_string_false_absent_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo, source_rel, output_rel = scaffold(tmp)
            the_plan = plan_for(repo, source_rel, output_rel)
            for entry in the_plan["repository"]["preconditions"]:
                if entry.get("absent"):
                    entry["absent"] = "false"
                    break
            else:
                the_plan["repository"]["preconditions"].append(
                    {"path": "deploy/x.json", "absent": "false"}
                )
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 2)
            self.assertIn("absent", report["validation"][0]["details"])

    def test_report_path_inside_repo_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo, _, _ = scaffold(tmp)
            plan_path = tmp / "plan.json"
            plan_path.write_text("{}", encoding="utf-8")
            result = subprocess.run(
                [
                    sys.executable, str(RUNNER), "--repo-root", str(repo),
                    "--plan", str(plan_path), "--check",
                    "--report", str(repo / "deploy" / "report.json"),
                ],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 2)
            self.assertIn("--report must be outside", result.stderr)

    def test_unwritable_report_rolls_back_the_apply(self) -> None:
        # A report path whose parent cannot be created (it already exists as a
        # plain file) fails inside the same transaction as the repository
        # write, so the repository mutation rolls back with it instead of
        # committing silently with no discoverable result.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo, source_rel, output_rel = scaffold(tmp)
            blocked_parent = tmp / "not-a-directory"
            blocked_parent.write_text("occupied", encoding="utf-8")
            plan_path = tmp / "plan.json"
            plan_path.write_text(json.dumps(plan_for(repo, source_rel, output_rel)), encoding="utf-8")
            result = subprocess.run(
                [
                    sys.executable, str(RUNNER), "--repo-root", str(repo),
                    "--plan", str(plan_path), "--apply",
                    "--report", str(blocked_parent / "report.json"),
                ],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, common.EXIT_TRANSACTION)
            self.assertFalse((repo / output_rel).exists())
            self.assertTrue((repo / source_rel).is_file())

    def test_normalize_root_handles_dot_and_trailing_slash(self) -> None:
        self.assertEqual(common.normalize_root("."), "")
        self.assertEqual(common.normalize_root("/"), "")
        self.assertEqual(common.normalize_root("chart/"), "chart")
        self.assertEqual(common.normalize_root("chart"), "chart")
        self.assertEqual(common.normalize_root("chart/templates"), "chart/templates")

    def test_join_rel_handles_the_repository_root(self) -> None:
        self.assertEqual(common.join_rel("chart", "templates/x.yaml"), "chart/templates/x.yaml")
        self.assertEqual(common.join_rel("chart/", "/templates/x.yaml"), "chart/templates/x.yaml")
        for repo_root in ("", ".", "/"):
            self.assertEqual(common.join_rel(repo_root, "x.yaml"), "x.yaml")

    def test_dns_label_truncates_with_a_stable_hash(self) -> None:
        long_a = common.dns_label("x" * 80, keep_tail="credentials")
        self.assertTrue(common.is_dns_label(long_a))
        self.assertTrue(long_a.endswith("-credentials"))
        self.assertEqual(long_a, common.dns_label("x" * 80, keep_tail="credentials"))


if __name__ == "__main__":
    unittest.main()
