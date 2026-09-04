"""Field-mapping unit tests for the internal converter module."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / ".apm" / "skills" / "migrate-core-operator-dbaas-declarations" / "scripts"))

import _core_convert as convert  # noqa: E402


def context(**overrides) -> convert.ConversionContext:
    base = {"operator_namespace": "dbaas-system", "service_name": "dca", "service_name_explicit": True}
    base.update(overrides)
    return convert.ConversionContext(**base)


def declaration(settings) -> dict:
    return {
        "kind": "DatabaseDeclaration",
        "classifierConfig": {"classifier": {"microserviceName": "dca", "scope": "service"}},
        "type": "postgresql",
        "settings": settings,
    }


class CoreConvertTest(unittest.TestCase):
    def test_valid_json_settings_are_preserved(self) -> None:
        settings = {"encoding": "UTF8", "timeout": 30.5, "list": ["vector"], "nested": {"a": 1}}
        resources, warnings, errors = convert.convert_documents(
            [declaration(settings)], context(), source_ref="s.json"
        )
        self.assertEqual(errors, [])
        self.assertEqual(len(resources), 1)
        spec = resources[0].body["spec"]
        self.assertEqual(spec["settings"], settings)
        self.assertEqual(spec["operatorNamespace"], "dbaas-system")

    def test_non_finite_setting_is_an_error_with_path(self) -> None:
        _, _, errors = convert.convert_documents(
            [declaration({"timeout": float("inf")})], context(), source_ref="s.json"
        )
        self.assertTrue(any("settings.timeout" in error for error in errors))

    def test_extra_classifier_keys_move_under_extra_keys(self) -> None:
        item = {
            "kind": "DatabaseDeclaration",
            "classifierConfig": {
                "classifier": {"scope": "service", "microserviceName": "svc", "transactional": True}
            },
            "type": "postgresql",
        }
        resources, _, _ = convert.convert_documents([item], context(), source_ref="s.json")
        classifier = resources[0].body["spec"]["classifier"]
        self.assertEqual(classifier["extraKeys"], {"transactional": True})
        self.assertNotIn("transactional", classifier)

    def test_dbpolicy_maps_to_access_policy(self) -> None:
        item = {
            "kind": "DbPolicy",
            "microserviceName": "dca",
            "services": [{"name": "inventory", "roles": ["readonly"]}],
            "disableGlobalPermissions": "false",
        }
        resources, _, _ = convert.convert_documents([item], context(), source_ref="s.json")
        body = resources[0].body
        self.assertEqual(body["kind"], "DatabaseAccessPolicy")
        self.assertEqual(body["spec"]["operatorNamespace"], "dbaas-system")
        self.assertIs(body["spec"]["disableGlobalPermissions"], False)

    def test_cross_service_clone_is_an_error(self) -> None:
        item = {
            "kind": "DatabaseDeclaration",
            "classifierConfig": {"classifier": {"scope": "service", "microserviceName": "svc"}},
            "type": "postgresql",
            "initialInstantiation": {
                "approach": "clone",
                "sourceClassifier": {"scope": "service", "microserviceName": "other"},
            },
        }
        _, _, errors = convert.convert_documents([item], context(), source_ref="s.json")
        self.assertTrue(any("cross-service clones are invalid" in error for error in errors))

    def test_empty_services_list_without_policy_is_an_error(self) -> None:
        item = {"kind": "DbPolicy", "microserviceName": "dca", "services": []}
        _, _, errors = convert.convert_documents([item], context(), source_ref="s.json")
        self.assertTrue(any("non-empty services or policy" in error for error in errors))

    def test_unknown_service_role_field_is_rejected(self) -> None:
        item = {
            "kind": "DbPolicy",
            "microserviceName": "dca",
            "services": [{"name": "inventory", "roles": ["readonly"], "legacyOnly": True}],
        }
        _, _, errors = convert.convert_documents([item], context(), source_ref="s.json")
        self.assertTrue(any("unsupported fields: legacyOnly" in error for error in errors))

    def test_policy_role_without_default_role_is_rejected(self) -> None:
        item = {
            "kind": "DbPolicy",
            "microserviceName": "dca",
            "policy": [{"type": "postgresql"}],
        }
        _, _, errors = convert.convert_documents([item], context(), source_ref="s.json")
        self.assertTrue(any("policy[0].defaultRole" in error for error in errors))

    def test_dump_resources_is_stable(self) -> None:
        resources, _, _ = convert.convert_documents(
            [declaration({"a": 1})], context(), source_ref="s.json"
        )
        body = resources[0].body
        self.assertEqual(convert.dump_resources([body]), convert.dump_resources([body]))


if __name__ == "__main__":
    unittest.main()
