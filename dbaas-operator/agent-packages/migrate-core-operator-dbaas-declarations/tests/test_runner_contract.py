"""Black-box contract assertions shared by every script-driven migration runner.

The mounted-secret package carries the same file. A repository-level check keeps
the two copies identical so the CLI, plan envelope, result envelope, and exit
codes cannot drift.
"""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from _harness import preconditions_for, run_migration, targets_for

VALID_SOURCE = json.dumps(
    {
        "kind": "DatabaseDeclaration",
        "declarations": [
            {
                "classifierConfig": {
                    "classifier": {"scope": "service", "microserviceName": "svc"}
                },
                "type": "postgresql",
            }
        ],
    }
)


def scaffold(tmp: Path) -> tuple[Path, str, str]:
    repo = tmp / "repo"
    source_rel = "deploy/dbaas.json"
    output_rel = "deploy/dbaas-operator-resources.yaml"
    (repo / "deploy").mkdir(parents=True)
    (repo / source_rel).write_text(VALID_SOURCE, encoding="utf-8")
    return repo, source_rel, output_rel


def plan_for(repo: Path, source_rel: str, output_rel: str) -> dict:
    return {
        "schemaVersion": 1,
        "migrationKind": "core-declarations",
        "repository": {"preconditions": preconditions_for(repo, source_rel, output_rel)},
        "inputs": {
            "sources": [
                {"path": source_rel, "root": "deploy", "rootKind": "plain", "documents": None}
            ]
        },
        "decisions": {
            "operatorNamespace": "dbaas-system",
            "serviceName": "svc",
            "serviceNameExplicit": True,
            "namespace": "deploy-ns",
        },
        "targets": targets_for(source_rel, output_rel),
    }


class RunnerContractTest(unittest.TestCase):
    def test_check_never_changes_the_repository(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo, source_rel, output_rel = scaffold(tmp)
            snapshot = (repo / source_rel).read_bytes()
            code, report = run_migration(repo, plan_for(repo, source_rel, output_rel), "check", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertEqual((repo / source_rel).read_bytes(), snapshot)
            self.assertFalse((repo / output_rel).exists())

    def test_apply_changes_only_declared_paths(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo, source_rel, output_rel = scaffold(tmp)
            (repo / "deploy" / "unrelated.txt").write_text("keep me\n", encoding="utf-8")
            code, report = run_migration(repo, plan_for(repo, source_rel, output_rel), "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertEqual((repo / "deploy" / "unrelated.txt").read_text(encoding="utf-8"), "keep me\n")
            touched = set(report["createdFiles"]) | set(report["modifiedFiles"]) | set(report["deletedFiles"])
            self.assertEqual(touched, {source_rel, output_rel})

    def test_repeated_apply_is_idempotent(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo, source_rel, output_rel = scaffold(tmp)
            self.assertEqual(run_migration(repo, plan_for(repo, source_rel, output_rel), "apply", tmp)[0], 0)
            first = (repo / output_rel).read_bytes()
            code, report = run_migration(repo, plan_for(repo, source_rel, output_rel), "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertEqual(report["status"], "unchanged")
            self.assertEqual((repo / output_rel).read_bytes(), first)

    def test_stale_source_hash_fails_before_writing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo, source_rel, output_rel = scaffold(tmp)
            plan = plan_for(repo, source_rel, output_rel)
            (repo / source_rel).write_text(VALID_SOURCE + "\n\n", encoding="utf-8")
            code, report = run_migration(repo, plan, "apply", tmp)
            self.assertEqual(code, 3)
            self.assertEqual(report["status"], "blocked")
            self.assertFalse((repo / output_rel).exists())

    def test_path_escape_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo, source_rel, output_rel = scaffold(tmp)
            plan = plan_for(repo, source_rel, output_rel)
            plan["inputs"]["sources"][0]["path"] = "../outside.json"
            code, report = run_migration(repo, plan, "apply", tmp)
            self.assertEqual(code, 2)
            self.assertEqual(report["status"], "blocked")

    def test_untargeted_path_is_refused(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo, source_rel, output_rel = scaffold(tmp)
            the_plan = plan_for(repo, source_rel, output_rel)
            the_plan["targets"] = [{"path": output_rel}]  # source deletion not allowed
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 2)
            self.assertEqual(report["status"], "blocked")
            self.assertFalse((repo / output_rel).exists())
            self.assertTrue((repo / source_rel).exists())

    def test_missing_precondition_is_refused(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo, source_rel, output_rel = scaffold(tmp)
            the_plan = plan_for(repo, source_rel, output_rel)
            the_plan["repository"]["preconditions"] = []
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 2)
            self.assertFalse((repo / output_rel).exists())

    def test_unknown_plan_field_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo, source_rel, output_rel = scaffold(tmp)
            plan = plan_for(repo, source_rel, output_rel)
            plan["surprise"] = True
            code, report = run_migration(repo, plan, "check", tmp)
            self.assertEqual(code, 2)
            self.assertIn("unknown", report["validation"][0]["details"].lower())

    def test_expected_revision_is_not_part_of_the_contract(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo, source_rel, output_rel = scaffold(tmp)
            plan = plan_for(repo, source_rel, output_rel)
            plan["repository"]["expectedRevision"] = "deadbeef"
            code, report = run_migration(repo, plan, "check", tmp)
            self.assertEqual(code, 2)
            self.assertIn("unknown", report["validation"][0]["details"].lower())

    def test_exactly_one_mode_is_required(self) -> None:
        import subprocess
        import sys

        from _harness import RUNNER

        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo, source_rel, output_rel = scaffold(tmp)
            plan_path = tmp / "plan.json"
            plan_path.write_text(json.dumps(plan_for(repo, source_rel, output_rel)), encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(RUNNER), "--repo-root", str(repo), "--plan", str(plan_path)],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 2)

    def test_result_envelope_shape(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo, source_rel, output_rel = scaffold(tmp)
            code, report = run_migration(repo, plan_for(repo, source_rel, output_rel), "check", tmp)
            self.assertEqual(code, 0)
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
            self.assertEqual(report["schemaVersion"], 1)
            self.assertEqual(report["createdFiles"], sorted(report["createdFiles"]))


if __name__ == "__main__":
    unittest.main()
