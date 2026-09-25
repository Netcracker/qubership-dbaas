from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any

try:
    import yaml  # type: ignore
except ImportError:  # pragma: no cover - optional test dependency
    yaml = None


PACKAGE_ROOT = Path(__file__).resolve().parents[1]
CONVERTER = (
    PACKAGE_ROOT
    / ".apm"
    / "skills"
    / "migrate-core-operator-dbaas-declarations"
    / "scripts"
    / "convert_dbaas_crs.py"
)


def declaration(settings: dict[str, Any]) -> dict[str, Any]:
    return {
        "apiVersion": "core.qubership.org/v1",
        "kind": "DatabaseDeclaration",
        "metadata": {
            "name": "dca-db",
            "namespace": "test-namespace",
        },
        "classifierConfig": {
            "classifier": {
                "microserviceName": "dca",
                "scope": "service",
            }
        },
        "type": "postgresql",
        "settings": settings,
    }


class ConvertDbaasCrsSettingsTest(unittest.TestCase):
    def run_converter(self, content: str, suffix: str) -> tuple[subprocess.CompletedProcess[str], str | None]:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / f"input{suffix}"
            output_path = Path(directory) / "output.yaml"
            input_path.write_text(content, encoding="utf-8")
            result = subprocess.run(
                [
                    sys.executable,
                    str(CONVERTER),
                    "--input",
                    str(input_path),
                    "--output",
                    str(output_path),
                    "--namespace",
                    "test-namespace",
                    "--operator-namespace",
                    "dbaas-system",
                    "--service-name",
                    "dca",
                ],
                capture_output=True,
                check=False,
                text=True,
            )
            output = output_path.read_text(encoding="utf-8") if output_path.exists() else None
            return result, output

    @unittest.skipIf(yaml is None, "PyYAML is required for YAML conversion tests")
    def test_yaml_only_settings_are_rejected_with_full_paths(self) -> None:
        content = """
apiVersion: core.qubership.org/v1
kind: DatabaseDeclaration
metadata:
  name: dca-db
  namespace: test-namespace
classifierConfig:
  classifier:
    microserviceName: dca
    scope: service
type: postgresql
settings:
  timeout: .nan
  createdAt: 2026-08-10
  payload: !!binary |
    SGVsbG8=
  nested:
    1: invalid-key
"""

        result, output = self.run_converter(content, ".yaml")

        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(output)
        self.assertIn("InternalDatabase dca-db", result.stderr)
        self.assertIn("settings.timeout: non-finite numbers are not valid JSON", result.stderr)
        self.assertIn("settings.createdAt: date values are not valid JSON", result.stderr)
        self.assertIn("settings.payload: bytes values are not valid JSON", result.stderr)
        self.assertIn("settings.nested[1]: object keys must be strings", result.stderr)

    def test_json_nan_is_rejected_during_parsing(self) -> None:
        content = json.dumps(declaration({"timeout": float("nan")}))

        result, output = self.run_converter(content, ".json")

        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(output)
        self.assertIn("numeric constant 'NaN' is not valid JSON", result.stderr)

    @unittest.skipIf(yaml is None, "PyYAML is required to verify generated YAML")
    def test_valid_json_settings_are_preserved(self) -> None:
        settings = {
            "encoding": "UTF8",
            "timeout": 30.5,
            "retries": 3,
            "enabled": True,
            "nullable": None,
            "pgExtensions": ["vector"],
            "nested": {"a": 1},
        }
        content = json.dumps(declaration(settings))

        result, output = self.run_converter(content, ".json")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIsNotNone(output)
        resource = yaml.safe_load(output)
        self.assertEqual(resource["spec"]["operatorNamespace"], "dbaas-system")
        self.assertEqual(resource["spec"]["settings"], settings)

    @unittest.skipIf(yaml is None, "PyYAML is required to verify generated YAML")
    def test_physical_database_id_is_preserved(self) -> None:
        payload = declaration({})
        payload["physicalDatabaseId"] = "postgresql-prod-a"
        content = json.dumps(payload)

        result, output = self.run_converter(content, ".json")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIsNotNone(output)
        resource = yaml.safe_load(output)
        self.assertEqual(resource["spec"]["physicalDatabaseId"], "postgresql-prod-a")

    @unittest.skipIf(yaml is None, "PyYAML is required to verify generated YAML")
    def test_database_access_policy_includes_operator_namespace(self) -> None:
        content = json.dumps(
            {
                "apiVersion": "nc.core.dbaas/v3",
                "kind": "DbPolicy",
                "microserviceName": "dca",
                "services": [{"name": "inventory", "roles": ["readonly"]}],
            }
        )

        result, output = self.run_converter(content, ".json")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIsNotNone(output)
        resource = yaml.safe_load(output)
        self.assertEqual(resource["kind"], "DatabaseAccessPolicy")
        self.assertEqual(resource["spec"]["operatorNamespace"], "dbaas-system")


class DgpOnlyPolicyTest(unittest.TestCase):
    """Issue #776: a legacy DbPolicy carrying only disableGlobalPermissions
    (true or false) is a valid, complete policy on its own -- the converter
    checks field *presence*, not its truth value."""

    def run_converter(self, content: str, suffix: str) -> tuple[subprocess.CompletedProcess[str], str | None]:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / f"input{suffix}"
            output_path = Path(directory) / "output.yaml"
            input_path.write_text(content, encoding="utf-8")
            result = subprocess.run(
                [
                    sys.executable,
                    str(CONVERTER),
                    "--input",
                    str(input_path),
                    "--output",
                    str(output_path),
                    "--namespace",
                    "test-namespace",
                    "--operator-namespace",
                    "dbaas-system",
                    "--service-name",
                    "dca",
                ],
                capture_output=True,
                check=False,
                text=True,
            )
            output = output_path.read_text(encoding="utf-8") if output_path.exists() else None
            return result, output

    @unittest.skipIf(yaml is None, "PyYAML is required to verify generated YAML")
    def test_json_dgp_only_true_converts(self) -> None:
        content = json.dumps(
            {"apiVersion": "nc.core.dbaas/v3", "kind": "DbPolicy", "disableGlobalPermissions": True}
        )

        result, output = self.run_converter(content, ".json")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIsNotNone(output)
        resource = yaml.safe_load(output)
        self.assertEqual(resource["kind"], "DatabaseAccessPolicy")
        self.assertIs(resource["spec"]["disableGlobalPermissions"], True)
        self.assertNotIn("services", resource["spec"])
        self.assertNotIn("policy", resource["spec"])

    @unittest.skipIf(yaml is None, "PyYAML is required to verify generated YAML")
    def test_json_dgp_only_false_converts_and_survives_yaml_serialization(self) -> None:
        content = json.dumps(
            {"apiVersion": "nc.core.dbaas/v3", "kind": "DbPolicy", "disableGlobalPermissions": False}
        )

        result, output = self.run_converter(content, ".json")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIsNotNone(output)
        # A round trip through the real YAML dumper/loader, not just the in-memory
        # dict, is what proves an explicit `false` is not dropped or coerced to
        # the field being merely absent.
        resource = yaml.safe_load(output)
        self.assertIn("disableGlobalPermissions", resource["spec"])
        self.assertIs(resource["spec"]["disableGlobalPermissions"], False)

    @unittest.skipIf(yaml is None, "PyYAML is required for YAML conversion tests")
    def test_yaml_dbaas_wrapper_subkind_dbpolicy_dgp_only_true_converts(self) -> None:
        content = """
apiVersion: dbaas.netcracker.com/v1
kind: DBaaS
subKind: DbPolicy
metadata:
  name: legacy-policy
spec:
  disableGlobalPermissions: true
"""

        result, output = self.run_converter(content, ".yaml")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIsNotNone(output)
        resource = yaml.safe_load(output)
        self.assertEqual(resource["kind"], "DatabaseAccessPolicy")
        self.assertIs(resource["spec"]["disableGlobalPermissions"], True)

    def test_empty_policy_with_no_services_policy_or_dgp_still_fails(self) -> None:
        content = json.dumps({"apiVersion": "nc.core.dbaas/v3", "kind": "DbPolicy"})

        result, output = self.run_converter(content, ".json")

        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(output)
        self.assertIn("disableGlobalPermissions present", result.stderr)


class WrapperLabelFilterTest(unittest.TestCase):
    """Issue #776: the Core Operator's kind: DBaaS wrapper stamps two labels
    describing its own processing state onto the wrapper -- those must not
    reach the generated native CR, but every ordinary label, Argo CD/tracking
    label, and annotation must survive untouched, on both wrapped and direct
    (non-wrapper) resources."""

    def run_converter(self, content: str, suffix: str = ".json") -> tuple[subprocess.CompletedProcess[str], str | None]:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / f"input{suffix}"
            output_path = Path(directory) / "output.yaml"
            input_path.write_text(content, encoding="utf-8")
            result = subprocess.run(
                [
                    sys.executable,
                    str(CONVERTER),
                    "--input",
                    str(input_path),
                    "--output",
                    str(output_path),
                    "--namespace",
                    "test-namespace",
                    "--operator-namespace",
                    "dbaas-system",
                    "--service-name",
                    "dca",
                ],
                capture_output=True,
                check=False,
                text=True,
            )
            output = output_path.read_text(encoding="utf-8") if output_path.exists() else None
            return result, output

    @unittest.skipIf(yaml is None, "PyYAML is required to verify generated YAML")
    def test_wrapper_only_labels_removed_ordinary_labels_and_annotations_kept(self) -> None:
        content = json.dumps(
            {
                "apiVersion": "dbaas.netcracker.com/v1",
                "kind": "DBaaS",
                "subKind": "DbPolicy",
                "metadata": {
                    "name": "legacy-policy",
                    "labels": {
                        "app.kubernetes.io/processed-by-operator": "true",
                        "deployer.cleanup/allow": "true",
                        "app.kubernetes.io/name": "orders",
                        "app.kubernetes.io/instance": "orders-release",
                        "argocd.argoproj.io/instance": "orders",
                    },
                    "annotations": {
                        "app.kubernetes.io/processed-by-operator": "true",
                        "argocd.argoproj.io/tracking-id": "orders:apps/Deployment:orders/orders",
                    },
                },
                "spec": {"disableGlobalPermissions": True},
            }
        )

        result, output = self.run_converter(content)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIsNotNone(output)
        resource = yaml.safe_load(output)
        labels = resource["metadata"]["labels"]
        self.assertNotIn("app.kubernetes.io/processed-by-operator", labels)
        self.assertNotIn("deployer.cleanup/allow", labels)
        self.assertEqual(labels["app.kubernetes.io/name"], "orders")
        self.assertEqual(labels["app.kubernetes.io/instance"], "orders-release")
        self.assertEqual(labels["argocd.argoproj.io/instance"], "orders")
        # Annotations are never filtered, even when a wrapper-only label name
        # happens to appear among them.
        self.assertEqual(
            resource["metadata"]["annotations"],
            {
                "app.kubernetes.io/processed-by-operator": "true",
                "argocd.argoproj.io/tracking-id": "orders:apps/Deployment:orders/orders",
            },
        )

    @unittest.skipIf(yaml is None, "PyYAML is required to verify generated YAML")
    def test_direct_non_wrapper_resource_keeps_a_same_named_label(self) -> None:
        # A direct (non-"kind: DBaaS") DbPolicy is never the Core Operator's
        # wrapper -- a label that happens to share a wrapper-only label's name
        # here is a real, unrelated label and must survive.
        content = json.dumps(
            {
                "apiVersion": "nc.core.dbaas/v3",
                "kind": "DbPolicy",
                "metadata": {
                    "name": "direct-policy",
                    "labels": {"app.kubernetes.io/processed-by-operator": "keep-me"},
                },
                "disableGlobalPermissions": True,
            }
        )

        result, output = self.run_converter(content)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIsNotNone(output)
        resource = yaml.safe_load(output)
        self.assertEqual(
            resource["metadata"]["labels"], {"app.kubernetes.io/processed-by-operator": "keep-me"}
        )

    @unittest.skipIf(yaml is None, "PyYAML is required to verify generated YAML")
    def test_generated_metadata_with_filtered_labels_passes_label_validation(self) -> None:
        # A zero-argument, successful CLI run already runs validate_target_resource
        # (main()) against the generated object -- a non-zero exit here would mean
        # the filtered label set failed Kubernetes label-key/value validation.
        content = json.dumps(
            {
                "apiVersion": "dbaas.netcracker.com/v1",
                "kind": "DBaaS",
                "subKind": "DbPolicy",
                "metadata": {
                    "name": "legacy-policy",
                    "labels": {
                        "app.kubernetes.io/processed-by-operator": "true",
                        "deployer.cleanup/allow": "true",
                    },
                },
                "spec": {"disableGlobalPermissions": True},
            }
        )

        result, output = self.run_converter(content)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIsNotNone(output)
        resource = yaml.safe_load(output)
        self.assertNotIn("labels", resource["metadata"])


if __name__ == "__main__":
    unittest.main()
