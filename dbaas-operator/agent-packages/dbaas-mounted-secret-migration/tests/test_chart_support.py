"""Unit tests for chart-side render-probe support (no helm/subprocess needed)."""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(
    0,
    str(
        Path(__file__).resolve().parents[1]
        / ".apm"
        / "skills"
        / "dbaas-mounted-secret-migration"
        / "scripts"
    ),
)

import _chart_support as chart_support  # noqa: E402


class TemplateValueKeysTest(unittest.TestCase):
    def test_dotted_path_is_captured_whole_not_truncated(self) -> None:
        keys = chart_support._template_value_keys('{{ .Values.database.name }}')
        self.assertEqual(keys, {"database.name"})
        self.assertNotIn("database", keys)

    def test_bare_reference_alongside_nested_one_drops_the_bare_key(self) -> None:
        # If the source text references both .Values.database (bare) and
        # .Values.database.name (nested), --set database=... would replace the
        # chart's real `database: {...}` mapping with a scalar and then
        # conflict with --set database.name=... in the same Helm invocation.
        # Only the more specific key should survive.
        keys = chart_support._template_value_keys(
            '{{ .Values.database }} and {{ .Values.database.name }}'
        )
        self.assertEqual(keys, {"database.name"})

    def test_unrelated_bare_and_nested_keys_both_survive(self) -> None:
        keys = chart_support._template_value_keys(
            '{{ .Values.NAMESPACE }} {{ .Values.database.name }} {{ .Values.database.host }}'
        )
        self.assertEqual(keys, {"NAMESPACE", "database.name", "database.host"})

    def test_pilot_value_for_a_dotted_key_is_a_plain_string(self) -> None:
        value = chart_support._pilot_value("database.name")
        self.assertEqual(value, "pilot-database.name")

    def test_resolve_templates_substitutes_a_dotted_whole_value(self) -> None:
        resolved = chart_support._resolve_templates(
            "{{ .Values.database.namespace }}", {"database.namespace": "orders-ns"}
        )
        self.assertEqual(resolved, "orders-ns")


class OperatorNamespaceSchemaTest(unittest.TestCase):
    def test_valid_optional_property_keeps_all_existing_metadata(self) -> None:
        source = json.dumps(
            {
                "type": "object",
                "properties": {
                    "DBAAS_OPERATOR_NAMESPACE": {
                        "type": "string",
                        "description": "Operator namespace",
                        "default": "",
                    }
                },
            },
            indent=2,
        ) + "\n"
        self.assertIsNone(
            chart_support._schema_with_optional_operator_namespace(source)
        )

    def test_required_min_length_is_removed_without_losing_metadata(self) -> None:
        source = json.dumps(
            {
                "type": "object",
                "properties": {
                    "DBAAS_OPERATOR_NAMESPACE": {
                        "type": "string",
                        "minLength": 1,
                        "description": "Operator namespace",
                    }
                },
                "required": ["DBAAS_OPERATOR_NAMESPACE"],
            },
            indent=2,
        ) + "\n"
        updated = chart_support._schema_with_optional_operator_namespace(source)
        self.assertIsNotNone(updated)
        parsed = json.loads(updated)
        property_schema = parsed["properties"]["DBAAS_OPERATOR_NAMESPACE"]
        self.assertEqual(property_schema["type"], "string")
        self.assertEqual(property_schema["description"], "Operator namespace")
        self.assertNotIn("minLength", property_schema)
        self.assertNotIn("DBAAS_OPERATOR_NAMESPACE", parsed.get("required", []))


if __name__ == "__main__":
    unittest.main()
