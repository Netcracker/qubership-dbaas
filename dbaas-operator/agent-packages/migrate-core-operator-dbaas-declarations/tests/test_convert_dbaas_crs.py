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


if __name__ == "__main__":
    unittest.main()
