from __future__ import annotations

import importlib.util
import subprocess
import sys
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "migrate_namespace_bindings.py"
SPEC = importlib.util.spec_from_file_location("migrate_namespace_bindings", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def resource(namespace: str, name: str, operator_namespace: str | None = None) -> dict:
    spec = {}
    if operator_namespace is not None:
        spec["operatorNamespace"] = operator_namespace
    return {"metadata": {"namespace": namespace, "name": name}, "spec": spec}


class MigrationPlanTest(unittest.TestCase):
    def test_uses_binding_for_workloads_and_resource_namespace_for_permanent_rule(self) -> None:
        bindings = [resource("tenant-a", "binding", "dbaas-system")]
        resources = {
            "externaldatabases": [resource("tenant-a", "postgres")],
            "permanentbalancingrules": [resource("dbaas-system", "permanent-balancing-rules")],
        }

        patches, errors = MODULE.build_plan(bindings, resources)

        self.assertEqual(errors, [])
        self.assertEqual(
            patches,
            [
                MODULE.AssignmentPatch("externaldatabases", "tenant-a", "postgres", "dbaas-system"),
                MODULE.AssignmentPatch(
                    "permanentbalancingrules",
                    "dbaas-system",
                    "permanent-balancing-rules",
                    "dbaas-system",
                ),
            ],
        )

    def test_rejects_unassigned_workload_without_binding(self) -> None:
        patches, errors = MODULE.build_plan(
            [], {"internaldatabases": [resource("tenant-a", "postgres")]}
        )

        self.assertEqual(patches, [])
        self.assertIn("no operatorNamespace and no NamespaceBinding assignment", errors[0])

    def test_rejects_assignment_that_conflicts_with_binding(self) -> None:
        bindings = [resource("tenant-a", "binding", "dbaas-system")]
        resources = {
            "databasesecretclaims": [resource("tenant-a", "claim", "other-operator")]
        }

        patches, errors = MODULE.build_plan(bindings, resources)

        self.assertEqual(patches, [])
        self.assertIn("but its NamespaceBinding assigns dbaas-system", errors[0])


class KubectlReadTest(unittest.TestCase):
    def test_missing_binding_crd_is_an_empty_successful_lookup(self) -> None:
        kubectl = mock.Mock()
        kubectl.run.return_value = subprocess.CompletedProcess([], 0, "", "")

        self.assertEqual(MODULE.list_bindings(kubectl), [])
        kubectl.run.assert_called_once_with(
            "get",
            "crd",
            MODULE.NAMESPACE_BINDING_CRD,
            "--ignore-not-found",
            "-o",
            "name",
        )
        kubectl.get_json.assert_not_called()

    def test_binding_crd_lookup_error_is_not_treated_as_not_found(self) -> None:
        kubectl = mock.Mock()
        kubectl.run.side_effect = RuntimeError("kubectl authorization failed")

        with self.assertRaisesRegex(RuntimeError, "authorization failed"):
            MODULE.list_bindings(kubectl)

    def test_binding_read_error_during_release_is_not_treated_as_deleted(self) -> None:
        kubectl = mock.Mock()
        kubectl.run.side_effect = [
            subprocess.CompletedProcess([], 0, "", ""),
            RuntimeError("kubectl connection failed"),
        ]

        with self.assertRaisesRegex(RuntimeError, "connection failed"):
            MODULE.release_binding(kubectl, resource("tenant-a", "binding", "dbaas-system"))


if __name__ == "__main__":
    unittest.main()
