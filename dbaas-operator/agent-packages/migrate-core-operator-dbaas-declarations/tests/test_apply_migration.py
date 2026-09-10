from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import yaml

from _harness import RUNNER, preconditions_for, run_migration, targets_for

DECLARATION_JSON = {
    "apiVersion": "nc.core.dbaas/v3",
    "kind": "DatabaseDeclaration",
    "declarations": [
        {
            "classifierConfig": {
                "classifier": {
                    "scope": "service",
                    "microserviceName": "{{$SERVICE_NAME}}",
                    "customKeys": {"logicalDBName": "configs"},
                }
            },
            "type": "postgresql",
            "settings": {"pgExtensions": ["vector"]},
            "versioningConfig": {"approach": "clone"},
        },
        {
            "classifierConfig": {
                "classifier": {"scope": "service", "microserviceName": "{{$SERVICE_NAME}}"}
            },
            "type": "postgresql",
        },
    ],
}


def make_repo(root: Path, source_body: str, source_rel: str) -> Path:
    repo = root / "repo"
    (repo / Path(source_rel).parent).mkdir(parents=True, exist_ok=True)
    (repo / source_rel).write_text(source_body, encoding="utf-8")
    return repo


def base_plan(
    repo: Path, source_rel: str, *, root: str, root_kind: str, output_rel: str, namespace=None
) -> dict:
    if namespace is None:
        namespace = "{{ .Values.NAMESPACE }}" if root_kind == "helm" else "deploy-ns"
    service_name = "{{ .Values.SERVICE_NAME }}" if root_kind == "helm" else "svc"
    return {
        "schemaVersion": 1,
        "migrationKind": "core-declarations",
        "repository": {
            "preconditions": preconditions_for(repo, source_rel, output_rel),
        },
        "inputs": {
            "sources": [
                {"path": source_rel, "root": root, "rootKind": root_kind, "documents": None}
            ]
        },
        "decisions": {
            "operatorNamespace": "dbaas-system",
            "serviceName": service_name,
            "namespace": namespace,
        },
        "targets": targets_for(source_rel, output_rel),
    }


class ApplyMigrationTest(unittest.TestCase):
    def test_json_helm_source_is_split_and_removed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "chart/templates/dbaas-configuration.json"
            output_rel = "chart/templates/dbaas-operator-resources.yaml"
            repo = make_repo(tmp, json.dumps(DECLARATION_JSON, indent=2) + "\n", source_rel)
            plan = base_plan(
                repo, source_rel, root="chart", root_kind="helm", output_rel=output_rel
            )

            code, report = run_migration(repo, plan, "apply", tmp)

            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertEqual(report["status"], "changed")
            self.assertFalse((repo / source_rel).exists(), "fully migrated source must be deleted")
            generated = yaml.safe_load_all((repo / output_rel).read_text(encoding="utf-8"))
            docs = [doc for doc in generated if doc]
            self.assertEqual(len(docs), 2)
            self.assertEqual({doc["kind"] for doc in docs}, {"InternalDatabase"})
            for doc in docs:
                self.assertEqual(doc["spec"]["operatorNamespace"], "dbaas-system")
                self.assertEqual(
                    doc["spec"]["classifier"]["microserviceName"], "{{ .Values.SERVICE_NAME }}"
                )
            names = [doc["metadata"]["name"] for doc in docs]
            self.assertEqual(names, sorted(names), "resources are sorted by kind/namespace/name")

    def test_check_mode_writes_nothing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "deploy/dbaas.json"
            output_rel = "deploy/dbaas-operator-resources.yaml"
            repo = make_repo(tmp, json.dumps(DECLARATION_JSON) + "\n", source_rel)
            plan = base_plan(
                repo, source_rel, root="deploy", root_kind="plain", output_rel=output_rel
            )
            before = (repo / source_rel).read_bytes()

            code, report = run_migration(repo, plan, "check", tmp)

            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertEqual(report["status"], "changed")
            self.assertEqual((repo / source_rel).read_bytes(), before)
            self.assertFalse((repo / output_rel).exists())

    def test_apply_is_byte_for_byte_idempotent(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "chart/templates/dbaas-configuration.json"
            output_rel = "chart/templates/dbaas-operator-resources.yaml"
            repo = make_repo(tmp, json.dumps(DECLARATION_JSON) + "\n", source_rel)
            plan = base_plan(
                repo, source_rel, root="chart", root_kind="helm", output_rel=output_rel
            )

            code, _ = run_migration(repo, plan, "apply", tmp)
            self.assertEqual(code, 0)
            first = (repo / output_rel).read_bytes()

            # Re-run with a plan whose source precondition now expects the file absent.
            plan2 = base_plan(
                repo, source_rel, root="chart", root_kind="helm", output_rel=output_rel
            )
            code2, report2 = run_migration(repo, plan2, "apply", tmp)
            self.assertEqual(code2, 0, report2.get("__stderr"))
            self.assertEqual(report2["status"], "unchanged")
            self.assertEqual((repo / output_rel).read_bytes(), first)

    def test_scalar_helm_template_is_preserved(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "chart/templates/dbaas.yaml"
            output_rel = "chart/templates/dbaas-operator-resources.yaml"
            body = (
                "apiVersion: core.netcracker.com/v1\n"
                "kind: DBaaS\n"
                "subKind: DatabaseDeclaration\n"
                "metadata:\n"
                "  name: service-db\n"
                "  namespace: {{ .Values.NAMESPACE }}\n"
                "spec:\n"
                "  classifierConfig:\n"
                "    classifier:\n"
                "      scope: service\n"
                "      microserviceName: {{ .Values.SERVICE_NAME }}\n"
                "  type: postgresql\n"
            )
            repo = make_repo(tmp, body, source_rel)
            plan = base_plan(
                repo, source_rel, root="chart", root_kind="helm", output_rel=output_rel
            )

            code, report = run_migration(repo, plan, "apply", tmp)

            self.assertEqual(code, 0, report.get("__stderr"))
            text = (repo / output_rel).read_text(encoding="utf-8")
            self.assertIn("{{ .Values.NAMESPACE }}", text)
            self.assertIn("{{ .Values.SERVICE_NAME }}", text)

    def test_missing_required_field_is_a_blocking_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "deploy/dbaas.json"
            output_rel = "deploy/dbaas-operator-resources.yaml"
            payload = {
                "kind": "DatabaseDeclaration",
                "declarations": [
                    {"classifierConfig": {"classifier": {"scope": "service", "microserviceName": "x"}}}
                ],
            }
            repo = make_repo(tmp, json.dumps(payload), source_rel)
            plan = base_plan(
                repo, source_rel, root="deploy", root_kind="plain", output_rel=output_rel
            )

            code, report = run_migration(repo, plan, "apply", tmp)

            self.assertEqual(code, 4)
            self.assertEqual(report["status"], "blocked")
            self.assertTrue(any("spec.type" in entry for entry in report["blocking"]))

    def test_cross_service_clone_cannot_be_resolved_away(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "deploy/dbaas.json"
            output_rel = "deploy/dbaas-operator-resources.yaml"
            payload = {
                "kind": "DatabaseDeclaration",
                "declarations": [
                    {
                        "classifierConfig": {"classifier": {"scope": "service", "microserviceName": "svc"}},
                        "type": "postgresql",
                        "initialInstantiation": {
                            "approach": "clone",
                            "sourceClassifier": {"scope": "service", "microserviceName": "other"},
                        },
                    }
                ],
            }
            repo = make_repo(tmp, json.dumps(payload), source_rel)
            plan = base_plan(
                repo, source_rel, root="deploy", root_kind="plain", output_rel=output_rel
            )
            plan["decisions"]["serviceNameExplicit"] = True
            plan["decisions"]["serviceName"] = "svc"

            code, report = run_migration(repo, plan, "apply", tmp)

            self.assertEqual(code, 4)
            self.assertTrue(any("cross-service clones are invalid" in e for e in report["blocking"]))
            self.assertFalse((repo / output_rel).exists())

    def test_non_list_declarations_cannot_be_resolved_away(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "deploy/dbaas.json"
            output_rel = "deploy/dbaas-operator-resources.yaml"
            payload = {"kind": "DatabaseDeclaration", "declarations": "oops"}
            repo = make_repo(tmp, json.dumps(payload), source_rel)
            plan = base_plan(
                repo, source_rel, root="deploy", root_kind="plain", output_rel=output_rel
            )

            code, report = run_migration(repo, plan, "apply", tmp)

            self.assertEqual(code, 4)
            self.assertEqual(report["status"], "blocked")
            self.assertTrue((repo / source_rel).is_file(), "source must not be deleted")
            self.assertFalse((repo / output_rel).exists())

    def test_selected_document_that_produces_nothing_does_not_delete_source(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "deploy/dbaas.json"
            output_rel = "deploy/dbaas-operator-resources.yaml"
            # A well-formed but empty declaration wrapper: counted as legacy, yet
            # it converts to nothing. The source must survive.
            payload = {"kind": "DatabaseDeclaration", "declarations": []}
            repo = make_repo(tmp, json.dumps(payload), source_rel)
            plan = base_plan(
                repo, source_rel, root="deploy", root_kind="plain", output_rel=output_rel
            )

            code, report = run_migration(repo, plan, "apply", tmp)

            self.assertEqual(code, 4)
            self.assertTrue(any("produced no converted resource" in e for e in report["blocking"]))
            self.assertTrue((repo / source_rel).is_file())
            self.assertFalse((repo / output_rel).exists())

    def test_output_path_colliding_with_source_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "deploy/dbaas.json"
            repo = make_repo(tmp, json.dumps(DECLARATION_JSON), source_rel)
            plan = {
                "schemaVersion": 1,
                "migrationKind": "core-declarations",
                "repository": {"preconditions": preconditions_for(repo, source_rel)},
                "inputs": {
                    "sources": [
                        {"path": source_rel, "root": "deploy", "rootKind": "plain", "documents": None}
                    ]
                },
                "decisions": {
                    "operatorNamespace": "dbaas-system",
                    "serviceName": "{{ .Values.SERVICE_NAME }}",
                    "namespace": "{{ .Values.NAMESPACE }}",
                    "outputFile": "dbaas.json",
                },
                "targets": targets_for(source_rel),
            }

            code, report = run_migration(repo, plan, "apply", tmp)

            self.assertEqual(code, 4)
            self.assertTrue(any("collides with a source" in e for e in report["blocking"]))
            self.assertTrue((repo / source_rel).is_file())

    def test_partial_helm_guard_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "chart/templates/dbaas.yaml"
            output_rel = "chart/templates/dbaas-operator-resources.yaml"
            body = (
                "apiVersion: core.netcracker.com/v1\n"
                "kind: DBaaS\n"
                "subKind: DatabaseDeclaration\n"
                "metadata:\n"
                "  name: service-db\n"
                "spec:\n"
                "  {{- if .Values.enabled }}\n"
                "  classifierConfig:\n"
                "    classifier:\n"
                "      scope: service\n"
                "      microserviceName: svc\n"
                "  {{- end }}\n"
                "  type: postgresql\n"
            )
            repo = make_repo(tmp, body, source_rel)
            plan = base_plan(
                repo, source_rel, root="chart", root_kind="helm", output_rel=output_rel
            )

            code, report = run_migration(repo, plan, "apply", tmp)

            self.assertEqual(code, 4)
            self.assertTrue(any("whole document" in e for e in report["blocking"]))
            self.assertTrue((repo / source_rel).is_file())

    def test_whole_document_helm_guard_is_preserved(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "chart/templates/dbaas.yaml"
            output_rel = "chart/templates/dbaas-operator-resources.yaml"
            body = (
                "{{- if .Values.enabled }}\n"
                "apiVersion: core.netcracker.com/v1\n"
                "kind: DBaaS\n"
                "subKind: DatabaseDeclaration\n"
                "metadata:\n"
                "  name: service-db\n"
                "spec:\n"
                "  classifierConfig:\n"
                "    classifier:\n"
                "      scope: service\n"
                "      microserviceName: svc\n"
                "  type: postgresql\n"
                "{{- end }}\n"
            )
            repo = make_repo(tmp, body, source_rel)
            plan = base_plan(
                repo, source_rel, root="chart", root_kind="helm", output_rel=output_rel
            )

            code, report = run_migration(repo, plan, "apply", tmp)

            self.assertEqual(code, 0, report.get("__stderr"))
            text = (repo / output_rel).read_text(encoding="utf-8")
            self.assertIn("{{- if .Values.enabled }}", text)
            self.assertIn("{{- end }}", text)

    def test_mixed_yaml_sequence_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "deploy/dbaas.yaml"
            output_rel = "deploy/dbaas-operator-resources.yaml"
            body = (
                "- classifierConfig:\n"
                "    classifier:\n"
                "      scope: service\n"
                "      microserviceName: svc\n"
                "  type: postgresql\n"
                "  kind: DatabaseDeclaration\n"
                "- apiVersion: v1\n"
                "  kind: ConfigMap\n"
                "  metadata:\n"
                "    name: keep-me\n"
            )
            repo = make_repo(tmp, body, source_rel)
            plan = base_plan(
                repo, source_rel, root="deploy", root_kind="plain", output_rel=output_rel
            )

            code, report = run_migration(repo, plan, "apply", tmp)

            self.assertEqual(code, 4)
            self.assertTrue(any("mixes legacy declarations" in e for e in report["blocking"]))
            self.assertIn("keep-me", (repo / source_rel).read_text(encoding="utf-8"))

    def test_invalid_access_policy_types_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "deploy/dbpolicy.json"
            output_rel = "deploy/dbaas-operator-resources.yaml"
            payload = {
                "kind": "DbPolicy",
                "microserviceName": "svc",
                "services": "not-a-list",
                "disableGlobalPermissions": "not-a-bool",
            }
            repo = make_repo(tmp, json.dumps(payload), source_rel)
            plan = base_plan(
                repo, source_rel, root="deploy", root_kind="plain", output_rel=output_rel
            )
            plan["decisions"]["serviceNameExplicit"] = True
            plan["decisions"]["serviceName"] = "svc"

            code, report = run_migration(repo, plan, "apply", tmp)

            self.assertEqual(code, 4)
            self.assertTrue(any("must be a list of objects" in e for e in report["blocking"]))
            self.assertTrue(any("must be a boolean" in e for e in report["blocking"]))
            self.assertFalse((repo / output_rel).exists())

    def test_comment_before_whole_document_guard_is_preserved(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "chart/templates/dbaas.yaml"
            output_rel = "chart/templates/dbaas-operator-resources.yaml"
            body = (
                "# DBaaS declaration for the orders service\n"
                "\n"
                "{{- if .Values.enabled }}\n"
                "apiVersion: core.netcracker.com/v1\n"
                "kind: DBaaS\n"
                "subKind: DatabaseDeclaration\n"
                "metadata:\n"
                "  name: service-db\n"
                "spec:\n"
                "  classifierConfig:\n"
                "    classifier:\n"
                "      scope: service\n"
                "      microserviceName: svc\n"
                "  type: postgresql\n"
                "{{- end }}\n"
            )
            repo = make_repo(tmp, body, source_rel)
            plan = base_plan(
                repo, source_rel, root="chart", root_kind="helm", output_rel=output_rel
            )

            code, report = run_migration(repo, plan, "apply", tmp)

            self.assertEqual(code, 0, report.get("__stderr"))
            text = (repo / output_rel).read_text(encoding="utf-8")
            self.assertIn("{{- if .Values.enabled }}", text)
            self.assertIn("{{- end }}", text)

    def test_mixed_sequence_with_a_scalar_value_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "deploy/dbaas.yaml"
            output_rel = "deploy/dbaas-operator-resources.yaml"
            body = (
                "- classifierConfig:\n"
                "    classifier:\n"
                "      scope: service\n"
                "      microserviceName: svc\n"
                "  type: postgresql\n"
                "  kind: DatabaseDeclaration\n"
                "- preserve-this-value\n"
            )
            repo = make_repo(tmp, body, source_rel)
            plan = base_plan(
                repo, source_rel, root="deploy", root_kind="plain", output_rel=output_rel
            )

            code, report = run_migration(repo, plan, "apply", tmp)

            self.assertEqual(code, 4)
            self.assertTrue(any("mixes legacy declarations" in e for e in report["blocking"]))
            self.assertIn("preserve-this-value", (repo / source_rel).read_text(encoding="utf-8"))

    def test_incomplete_access_policy_entries_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "deploy/dbpolicy.json"
            output_rel = "deploy/dbaas-operator-resources.yaml"
            payload = {
                "kind": "DbPolicy",
                "microserviceName": "svc",
                "services": [{}],
                "policy": [{"type": "postgresql"}],
            }
            repo = make_repo(tmp, json.dumps(payload), source_rel)
            plan = base_plan(
                repo, source_rel, root="deploy", root_kind="plain", output_rel=output_rel
            )
            plan["decisions"]["serviceNameExplicit"] = True
            plan["decisions"]["serviceName"] = "svc"

            code, report = run_migration(repo, plan, "apply", tmp)

            self.assertEqual(code, 4)
            blocking = " ".join(report["blocking"])
            self.assertIn("services[0].name", blocking)
            self.assertIn("services[0].roles", blocking)
            self.assertIn("policy[0].defaultRole", blocking)
            self.assertFalse((repo / output_rel).exists())

    def test_dropped_metadata_field_is_a_blocking_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "deploy/dbaas.json"
            output_rel = "deploy/dbaas-operator-resources.yaml"
            payload = {
                "kind": "DatabaseDeclaration",
                "metadata": {"name": "svc-db", "annotations": {"note": "keep"}},
                "declarations": [
                    {
                        "classifierConfig": {
                            "classifier": {"scope": "service", "microserviceName": "svc"}
                        },
                        "type": "postgresql",
                    }
                ],
            }
            repo = make_repo(tmp, json.dumps(payload), source_rel)
            plan = base_plan(
                repo, source_rel, root="deploy", root_kind="plain", output_rel=output_rel
            )

            code, report = run_migration(repo, plan, "apply", tmp)

            self.assertEqual(code, 4)
            self.assertEqual(report["status"], "blocked")
            self.assertTrue(any("annotations" in e for e in report["blocking"]))
            self.assertFalse((repo / output_rel).exists())

    def test_generated_file_uses_lf_newlines(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "deploy/dbaas.json"
            output_rel = "deploy/dbaas-operator-resources.yaml"
            repo = make_repo(tmp, json.dumps(DECLARATION_JSON), source_rel)
            plan = base_plan(
                repo, source_rel, root="deploy", root_kind="plain", output_rel=output_rel
            )
            code, report = run_migration(repo, plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertNotIn(b"\r", (repo / output_rel).read_bytes())

    def test_trailing_slash_root_alias_does_not_lose_data(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            decl_a = {"kind": "DatabaseDeclaration", "declarations": [
                {"classifierConfig": {"classifier": {"scope": "service", "microserviceName": "svc"}},
                 "type": "postgresql"}]}
            decl_b = {"kind": "DatabaseDeclaration", "declarations": [
                {"classifierConfig": {"classifier": {"scope": "tenant", "microserviceName": "svc"}},
                 "type": "postgresql"}]}
            repo = make_repo(tmp, json.dumps(decl_a), "chart/a.json")
            (repo / "chart").mkdir(parents=True, exist_ok=True)
            (repo / "chart/b.json").write_text(json.dumps(decl_b), encoding="utf-8")
            output_rel = "chart/dbaas-operator-resources.yaml"
            plan = {
                "schemaVersion": 1,
                "migrationKind": "core-declarations",
                "repository": {"preconditions": preconditions_for(repo, "chart/a.json", "chart/b.json", output_rel)},
                "inputs": {
                    "sources": [
                        {"path": "chart/a.json", "root": "chart", "rootKind": "plain", "documents": None},
                        {"path": "chart/b.json", "root": "chart/", "rootKind": "plain", "documents": None},
                    ]
                },
                "decisions": {"operatorNamespace": "dbaas-system", "serviceName": "svc",
                              "serviceNameExplicit": True, "namespace": "ns"},
                "targets": targets_for("chart/a.json", "chart/b.json", output_rel),
            }
            code, report = run_migration(repo, plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            docs = [d for d in yaml.safe_load_all((repo / output_rel).read_text(encoding="utf-8")) if d]
            self.assertEqual(len(docs), 2)  # one declaration from each source, none lost
            self.assertFalse((repo / "chart/a.json").exists())
            self.assertFalse((repo / "chart/b.json").exists())

    def test_string_false_service_name_explicit_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "deploy/dbaas.json"
            output_rel = "deploy/dbaas-operator-resources.yaml"
            repo = make_repo(tmp, json.dumps(DECLARATION_JSON), source_rel)
            plan = base_plan(repo, source_rel, root="deploy", root_kind="plain", output_rel=output_rel)
            plan["decisions"]["serviceNameExplicit"] = "false"
            code, report = run_migration(repo, plan, "check", tmp)
            self.assertEqual(code, 2)
            self.assertIn("serviceNameExplicit", report["validation"][0]["details"])

    def test_non_string_namespace_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "deploy/dbaas.json"
            output_rel = "deploy/dbaas-operator-resources.yaml"
            repo = make_repo(tmp, json.dumps(DECLARATION_JSON), source_rel)
            plan = base_plan(repo, source_rel, root="deploy", root_kind="plain", output_rel=output_rel)
            plan["decisions"]["namespace"] = 123
            code, report = run_migration(repo, plan, "check", tmp)
            self.assertEqual(code, 2)
            self.assertIn("namespace", report["validation"][0]["details"])

    def test_unsupported_field_is_reported_once_per_declaration(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "deploy/dbaas.json"
            output_rel = "deploy/dbaas-operator-resources.yaml"
            decl = {"classifierConfig": {"classifier": {"scope": "service", "microserviceName": "svc"}},
                    "type": "postgresql", "legacyOnly": "x"}
            payload = {"kind": "DatabaseDeclaration", "metadata": {"name": "orders"},
                       "declarations": [dict(decl), dict(decl)]}
            repo = make_repo(tmp, json.dumps(payload), source_rel)
            plan = base_plan(repo, source_rel, root="deploy", root_kind="plain", output_rel=output_rel)
            plan["decisions"]["resourceNames"] = {
                f"{source_rel}#0#1": "a-db", f"{source_rel}#0#2": "b-db"}
            code, report = run_migration(repo, plan, "apply", tmp)
            self.assertEqual(code, 4)
            self.assertEqual(
                sum("legacyOnly" in e for e in report["blocking"]), 2,
                report["blocking"],
            )

    def test_boolean_document_index_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "deploy/dbaas.json"
            output_rel = "deploy/dbaas-operator-resources.yaml"
            repo = make_repo(tmp, json.dumps([DECLARATION_JSON]), source_rel)
            plan = base_plan(repo, source_rel, root="deploy", root_kind="plain", output_rel=output_rel)
            plan["inputs"]["sources"][0]["documents"] = [True]
            code, report = run_migration(repo, plan, "check", tmp)
            self.assertEqual(code, 2)
            self.assertIn("list of integers", report["validation"][0]["details"])

    def test_falsy_wrong_type_output_ownership_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "deploy/dbaas.json"
            output_rel = "deploy/dbaas-operator-resources.yaml"
            repo = make_repo(tmp, json.dumps(DECLARATION_JSON), source_rel)
            plan = base_plan(repo, source_rel, root="deploy", root_kind="plain", output_rel=output_rel)
            plan["decisions"]["outputOwnership"] = []
            code, report = run_migration(repo, plan, "check", tmp)
            self.assertEqual(code, 2)
            self.assertIn("outputOwnership", report["validation"][0]["details"])

    def test_runner_file_exists(self) -> None:
        self.assertTrue(RUNNER.is_file())

    def test_kind_dbaas_with_unsupported_subkind_is_a_hard_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "deploy/dbaas.yaml"
            output_rel = "deploy/dbaas-operator-resources.yaml"
            body = "kind: DBaaS\nsubKind: SomethingElse\nspec:\n  foo: bar\n"
            repo = make_repo(tmp, body, source_rel)
            plan = base_plan(
                repo, source_rel, root="deploy", root_kind="plain", output_rel=output_rel
            )
            code, report = run_migration(repo, plan, "apply", tmp)
            self.assertEqual(code, 4)
            self.assertTrue(any("unsupported subKind" in e for e in report["blocking"]))
            self.assertTrue((repo / source_rel).is_file())

    def test_multi_declaration_resource_names_are_item_specific(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "deploy/dbaas.json"
            output_rel = "deploy/dbaas-operator-resources.yaml"
            payload = {
                "kind": "DatabaseDeclaration",
                "metadata": {"name": "orders"},
                "declarations": [
                    {"classifierConfig": {"classifier": {"scope": "service", "microserviceName": "svc"}},
                     "type": "postgresql"},
                    {"classifierConfig": {"classifier": {"scope": "tenant", "microserviceName": "svc"}},
                     "type": "postgresql"},
                ],
            }
            repo = make_repo(tmp, json.dumps(payload), source_rel)
            plan = base_plan(
                repo, source_rel, root="deploy", root_kind="plain", output_rel=output_rel
            )
            plan["decisions"]["resourceNames"] = {
                f"{source_rel}#0#1": "orders-service-db",
                f"{source_rel}#0#2": "orders-tenant-db",
            }
            code, report = run_migration(repo, plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            names = sorted(
                d["metadata"]["name"]
                for d in yaml.safe_load_all((repo / output_rel).read_text(encoding="utf-8"))
                if d
            )
            self.assertEqual(names, ["orders-service-db", "orders-tenant-db"])

    def test_unmatched_resource_names_key_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "deploy/dbaas.json"
            output_rel = "deploy/dbaas-operator-resources.yaml"
            repo = make_repo(tmp, json.dumps(DECLARATION_JSON), source_rel)
            plan = base_plan(
                repo, source_rel, root="deploy", root_kind="plain", output_rel=output_rel
            )
            plan["decisions"]["resourceNames"] = {"deploy/nope.json#0": "x"}
            code, report = run_migration(repo, plan, "apply", tmp)
            self.assertEqual(code, 4)
            self.assertTrue(any("matched no generated resource" in e for e in report["blocking"]))

    def test_mixed_migrated_and_unrelated_documents_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "deploy/dbaas.yaml"
            output_rel = "deploy/dbaas-operator-resources.yaml"
            # A migrated declaration, then an unrelated document in the same file
            # as a separate `---` section. The runner does not splice a file back
            # together around the document it removes -- it blocks and asks for
            # the file to be split first.
            body = (
                "kind: DBaaS\n"
                "subKind: DatabaseDeclaration\n"
                "spec:\n"
                "  classifierConfig:\n"
                "    classifier: {scope: service, microserviceName: svc}\n"
                "  type: postgresql\n"
                "---\n"
                "- alpha\n"
                "- beta\n"
            )
            repo = make_repo(tmp, body, source_rel)
            plan = base_plan(
                repo, source_rel, root="deploy", root_kind="plain", output_rel=output_rel
            )
            code, report = run_migration(repo, plan, "apply", tmp)
            self.assertEqual(code, 4)
            self.assertTrue(
                any("mixes migrated and unmigrated" in e for e in report["blocking"]),
                report["blocking"],
            )
            self.assertTrue((repo / source_rel).is_file())
            self.assertFalse((repo / output_rel).exists())

    def test_repository_root_source_is_migrated(self) -> None:
        # A plain layout can keep its declarations at the repository root; "." is
        # a legitimate root and its output lands at the repository root too.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "dbaas.json"
            output_rel = "dbaas-operator-resources.yaml"
            repo = make_repo(tmp, json.dumps(DECLARATION_JSON) + "\n", source_rel)
            plan = base_plan(
                repo, source_rel, root=".", root_kind="plain", output_rel=output_rel
            )
            code, report = run_migration(repo, plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertTrue((repo / output_rel).is_file())
            self.assertFalse((repo / source_rel).exists())

    def test_mixed_file_block_names_the_source_to_split(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "deploy/dbaas.yaml"
            output_rel = "deploy/dbaas-operator-resources.yaml"
            # A file-level header, a migrated declaration, then an unrelated
            # ConfigMap in the same file. Blocked, not spliced -- the source is
            # untouched and the block names the file to split.
            body = (
                "# Databases for the orders service.\n"
                "# Owned by the platform team.\n"
                "\n"
                "kind: DBaaS\n"
                "subKind: DatabaseDeclaration\n"
                "spec:\n"
                "  classifierConfig:\n"
                "    classifier: {scope: service, microserviceName: svc}\n"
                "  type: postgresql\n"
                "---\n"
                "apiVersion: v1\n"
                "kind: ConfigMap\n"
                "metadata:\n"
                "  name: keep-me\n"
            )
            repo = make_repo(tmp, body, source_rel)
            plan = base_plan(
                repo, source_rel, root="deploy", root_kind="plain", output_rel=output_rel
            )
            code, report = run_migration(repo, plan, "apply", tmp)
            self.assertEqual(code, 4)
            self.assertTrue(any(source_rel in e for e in report["blocking"]), report["blocking"])
            self.assertEqual(
                (repo / source_rel).read_text(encoding="utf-8"), body, "source must be untouched"
            )

    def test_mixed_helm_and_plain_roots_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = make_repo(tmp, json.dumps(DECLARATION_JSON), "chart/a.json")
            (repo / "deploy").mkdir(parents=True)
            (repo / "deploy/b.json").write_text(json.dumps(DECLARATION_JSON), encoding="utf-8")
            plan = {
                "schemaVersion": 1,
                "migrationKind": "core-declarations",
                "repository": {"preconditions": preconditions_for(repo, "chart/a.json", "deploy/b.json")},
                "inputs": {
                    "sources": [
                        {"path": "chart/a.json", "root": "chart", "rootKind": "helm", "documents": None},
                        {"path": "deploy/b.json", "root": "deploy", "rootKind": "plain", "documents": None},
                    ]
                },
                "decisions": {"operatorNamespace": "dbaas-system", "serviceName": "svc",
                              "serviceNameExplicit": True, "namespace": "ns"},
                "targets": targets_for("chart/a.json", "deploy/b.json"),
            }
            code, report = run_migration(repo, plan, "check", tmp)
            self.assertEqual(code, 2)
            self.assertIn("mixes helm and plain", report["validation"][0]["details"])

    def test_multiple_distinct_roots_are_rejected(self) -> None:
        # _migration_common.py materializes only the first affected root for
        # validation, so a plan spanning two distinct (same-kind) roots is not
        # partially supported -- it must be rejected outright, not silently
        # validated against only one of the two roots.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = make_repo(tmp, json.dumps(DECLARATION_JSON), "chartA/a.json")
            (repo / "chartB").mkdir(parents=True)
            (repo / "chartB/b.json").write_text(json.dumps(DECLARATION_JSON), encoding="utf-8")
            plan = {
                "schemaVersion": 1,
                "migrationKind": "core-declarations",
                "repository": {"preconditions": preconditions_for(repo, "chartA/a.json", "chartB/b.json")},
                "inputs": {
                    "sources": [
                        {"path": "chartA/a.json", "root": "chartA", "rootKind": "plain", "documents": None},
                        {"path": "chartB/b.json", "root": "chartB", "rootKind": "plain", "documents": None},
                    ]
                },
                "decisions": {"operatorNamespace": "dbaas-system", "serviceName": "svc",
                              "serviceNameExplicit": True, "namespace": "ns"},
                "targets": targets_for("chartA/a.json", "chartB/b.json"),
            }
            code, report = run_migration(repo, plan, "check", tmp)
            self.assertEqual(code, 2)
            self.assertIn("spans multiple roots", report["validation"][0]["details"])

    def test_empty_json_array_source_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "deploy/dbaas.json"
            output_rel = "deploy/dbaas-operator-resources.yaml"
            repo = make_repo(tmp, "[]", source_rel)
            plan = base_plan(
                repo, source_rel, root="deploy", root_kind="plain", output_rel=output_rel
            )
            code, report = run_migration(repo, plan, "apply", tmp)
            self.assertEqual(code, 4)
            self.assertTrue(any("no documents to migrate" in e for e in report["blocking"]))
            self.assertTrue((repo / source_rel).is_file(), "an empty source must not be deleted")

    def test_comment_only_yaml_source_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source_rel = "deploy/dbaas.yaml"
            output_rel = "deploy/dbaas-operator-resources.yaml"
            repo = make_repo(tmp, "# nothing here\n", source_rel)
            plan = base_plan(
                repo, source_rel, root="deploy", root_kind="plain", output_rel=output_rel
            )
            code, report = run_migration(repo, plan, "apply", tmp)
            self.assertEqual(code, 4)
            self.assertTrue(any("no documents to migrate" in e for e in report["blocking"]))
            self.assertTrue((repo / source_rel).is_file(), "an empty source must not be deleted")


if __name__ == "__main__":
    unittest.main()
