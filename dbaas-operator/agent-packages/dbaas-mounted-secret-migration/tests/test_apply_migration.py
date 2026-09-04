from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import yaml

from _harness import preconditions_for, run_migration, targets_for

VALUES = "NAMESPACE: orders-ns\nSERVICE_NAME: orders\n"
SCHEMA = json.dumps(
    {
        "$schema": "https://json-schema.org/draft-07/schema#",
        "type": "object",
        "properties": {"NAMESPACE": {"type": "string"}},
        "required": ["NAMESPACE"],
    }
)
DEPLOYMENT = (
    "apiVersion: apps/v1\n"
    "kind: Deployment\n"
    "metadata:\n"
    "  name: orders\n"
    "  namespace: '{{ .Values.NAMESPACE }}'\n"
    "spec:\n"
    "  template:\n"
    "    spec:\n"
    "      containers:\n"
    "        - name: orders\n"
    "          image: orders:latest\n"
    "      volumes:\n"
    "        - name: config\n"
    "          configMap:\n"
    "            name: orders-config\n"
)


CHART_YAML = "apiVersion: v2\nname: orders\nversion: 0.1.0\n"


def scaffold(tmp: Path, *, roles=("",)) -> Path:
    repo = tmp / "repo"
    (repo / "chart" / "templates").mkdir(parents=True)
    (repo / "chart" / "Chart.yaml").write_text(CHART_YAML, encoding="utf-8")
    (repo / "chart" / "values.yaml").write_text(VALUES, encoding="utf-8")
    (repo / "chart" / "values.schema.json").write_text(SCHEMA, encoding="utf-8")
    (repo / "chart" / "templates" / "deployment.yaml").write_text(DEPLOYMENT, encoding="utf-8")
    return repo


def plan(repo: Path, *, roles=("",)) -> dict:
    output = "chart/templates/dbaas-mounted-secret-resources.yaml"
    touched = (
        "chart/templates/deployment.yaml",
        "chart/values.yaml",
        "chart/values.schema.json",
        output,
    )
    return {
        "schemaVersion": 1,
        "migrationKind": "mounted-secret",
        "repository": {"preconditions": preconditions_for(repo, *touched)},
        "inputs": {
            "operatorNamespace": "{{ .Values.DBAAS_OPERATOR_NAMESPACE }}",
            "datasources": [
                {
                    "id": "orders-postgresql-service",
                    "type": "postgresql",
                    "classifier": {
                        "microserviceName": "orders",
                        "namespace": "{{ .Values.NAMESPACE }}",
                        "scope": "service",
                    },
                    "requestedRoles": list(roles),
                    "parameters": {"namePrefix": "", "settings": {}, "physicalDatabaseId": ""},
                    "codeLocations": ["internal/storage/postgres.go:42"],
                    "migrationFeasibility": "SUPPORTED",
                    "compatibility": {"mode": "NATIVE_MOUNTED_PROVIDER", "evidence": "resolved graph"},
                }
            ],
        },
        "decisions": {
            "root": "chart",
            "rootKind": "helm",
            "workloadNamespace": "{{ .Values.NAMESPACE }}",
            "originService": "orders",
            "claims": [
                {
                    "datasourceId": "orders-postgresql-service",
                    "role": role,
                    "workloadFile": "templates/deployment.yaml",
                    "workloadKind": "Deployment",
                    "workloadName": "orders",
                    "containers": ["orders"],
                    "initContainers": [],
                }
                for role in roles
            ],
            "supersededDeclarations": [],
        },
        "targets": targets_for(*touched),
    }


class MountedSecretApplyTest(unittest.TestCase):
    def test_apply_generates_resources_and_mount(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertEqual(report["status"], "changed")

            output = repo / "chart/templates/dbaas-mounted-secret-resources.yaml"
            docs = [d for d in yaml.safe_load_all(output.read_text(encoding="utf-8")) if d]
            kinds = sorted(d["kind"] for d in docs)
            self.assertEqual(kinds, ["DatabaseSecretClaim", "InternalDatabase"])
            for doc in docs:
                self.assertEqual(
                    doc["spec"]["operatorNamespace"], "{{ .Values.DBAAS_OPERATOR_NAMESPACE }}"
                )
                self.assertNotIn("namespace", doc["spec"]["classifier"])

            deployment = yaml.safe_load((repo / "chart/templates/deployment.yaml").read_text(encoding="utf-8"))
            pod = deployment["spec"]["template"]["spec"]
            self.assertIn(
                "orders-postgresql-service-default-secret", [v["name"] for v in pod["volumes"]]
            )
            mount = pod["containers"][0]["volumeMounts"][0]
            self.assertTrue(mount["readOnly"])
            self.assertEqual(
                mount["mountPath"],
                "/etc/secrets/dbaas-secrets/orders-postgresql-service-default-credentials",
            )

            values = (repo / "chart/values.yaml").read_text(encoding="utf-8")
            self.assertIn('DBAAS_OPERATOR_NAMESPACE: ""', values)
            schema = json.loads((repo / "chart/values.schema.json").read_text(encoding="utf-8"))
            self.assertIn("DBAAS_OPERATOR_NAMESPACE", schema["required"])
            self.assertEqual(schema["properties"]["DBAAS_OPERATOR_NAMESPACE"]["minLength"], 1)

    def test_multiple_roles_expand_claims_not_databases(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            code, report = run_migration(repo, plan(repo, roles=("", "admin")), "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            docs = [
                d
                for d in yaml.safe_load_all(
                    (repo / "chart/templates/dbaas-mounted-secret-resources.yaml").read_text(encoding="utf-8")
                )
                if d
            ]
            self.assertEqual(sum(d["kind"] == "InternalDatabase" for d in docs), 1)
            self.assertEqual(sum(d["kind"] == "DatabaseSecretClaim" for d in docs), 2)

    def test_unproven_compatibility_blocks(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            broken = plan(repo)
            broken["inputs"]["datasources"][0]["compatibility"] = {"mode": "UNPROVEN"}
            code, report = run_migration(repo, broken, "apply", tmp)
            self.assertEqual(code, 4)
            self.assertTrue(any("compatibility" in e for e in report["blocking"]))

    def test_helm_block_action_in_workload_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            deployment = repo / "chart/templates/deployment.yaml"
            deployment.write_text(
                DEPLOYMENT.replace(
                    "      volumes:\n",
                    "      {{- if .Values.extra }}\n      volumes:\n",
                ).replace("            name: orders-config\n", "            name: orders-config\n      {{- end }}\n"),
                encoding="utf-8",
            )
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 4)
            self.assertTrue(any("block action" in e for e in report["blocking"]))

    def test_mixed_superseded_file_is_not_deleted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            mixed = repo / "chart/templates/legacy.yaml"
            mixed.write_text(
                "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: keep\n"
                "---\nkind: DatabaseDeclaration\ndeclarations: []\n",
                encoding="utf-8",
            )
            the_plan = plan(repo)
            the_plan["decisions"]["supersededDeclarations"] = ["templates/legacy.yaml"]
            the_plan["repository"]["preconditions"].append(
                {"path": "chart/templates/legacy.yaml", "sha256": __import__("hashlib").sha256(mixed.read_bytes()).hexdigest()}
            )
            the_plan["targets"].append({"path": "chart/templates/legacy.yaml"})
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 4)
            self.assertTrue(mixed.is_file())
            self.assertTrue(any("other than legacy" in e for e in report["blocking"]))

    def test_two_workloads_in_one_file_do_not_cross_mounts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            multi = (
                DEPLOYMENT
                + "---\n"
                + DEPLOYMENT.replace("name: orders\n", "name: orders-worker\n", 1)
            )
            (repo / "chart/templates/deployment.yaml").write_text(multi, encoding="utf-8")
            the_plan = plan(repo, roles=("", "admin"))
            # route the two claims to the two different Deployments in the same file
            the_plan["decisions"]["claims"][1]["workloadName"] = "orders-worker"
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            docs = list(
                yaml.safe_load_all((repo / "chart/templates/deployment.yaml").read_text(encoding="utf-8"))
            )
            by_name = {d["metadata"]["name"]: d for d in docs if d}
            first_mounts = by_name["orders"]["spec"]["template"]["spec"]["containers"][0].get("volumeMounts", [])
            worker_mounts = by_name["orders-worker"]["spec"]["template"]["spec"]["containers"][0].get(
                "volumeMounts", []
            )
            self.assertEqual(len(first_mounts), 1)
            self.assertEqual(len(worker_mounts), 1)
            self.assertNotEqual(first_mounts[0]["name"], worker_mounts[0]["name"])

    def test_malformed_datasource_returns_exit_2_with_report(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            broken = plan(repo)
            del broken["inputs"]["datasources"][0]["id"]
            code, report = run_migration(repo, broken, "check", tmp)
            self.assertEqual(code, 2)
            self.assertEqual(report["status"], "blocked")
            self.assertIn("validation", report)

    def test_role_coverage_mismatch_blocks(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            broken = plan(repo, roles=("",))
            broken["inputs"]["datasources"][0]["requestedRoles"] = ["", "admin"]
            code, report = run_migration(repo, broken, "check", tmp)
            self.assertEqual(code, 4)

    def test_physical_database_id_blocks(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            broken = plan(repo)
            broken["inputs"]["datasources"][0]["parameters"]["physicalDatabaseId"] = "physical-db-42"
            code, report = run_migration(repo, broken, "check", tmp)
            self.assertEqual(code, 4)
            self.assertTrue(any("physicalDatabaseId" in e for e in report["blocking"]))
            self.assertFalse(
                (repo / "chart/templates/dbaas-mounted-secret-resources.yaml").exists()
            )

    def test_incomplete_classifier_blocks(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            broken = plan(repo)
            del broken["inputs"]["datasources"][0]["classifier"]["scope"]
            code, report = run_migration(repo, broken, "check", tmp)
            self.assertEqual(code, 2)
            self.assertEqual(report["status"], "blocked")
            self.assertIn("classifier.scope", report["validation"][0]["details"])

    def test_unknown_datasource_parameter_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            broken = plan(repo)
            broken["inputs"]["datasources"][0]["parameters"]["surprise"] = True
            code, report = run_migration(repo, broken, "check", tmp)
            self.assertEqual(code, 2)

    SUPPORTED_DECL = (
        "kind: DatabaseDeclaration\n"
        "classifierConfig:\n"
        "  classifier:\n"
        "    microserviceName: orders\n"
        "    namespace: '{{ .Values.NAMESPACE }}'\n"
        "    scope: service\n"
        "type: postgresql\n"
    )
    FOREIGN_DECL = (
        "kind: DatabaseDeclaration\n"
        "classifierConfig:\n"
        "  classifier:\n"
        "    microserviceName: orders\n"
        "    namespace: '{{ .Values.NAMESPACE }}'\n"
        "    scope: tenant\n"
        "type: postgresql\n"
    )

    def _add_legacy_file(self, repo: Path, the_plan: dict, rel: str, body: str) -> Path:
        legacy = repo / "chart" / rel
        legacy.write_text(body, encoding="utf-8")
        the_plan["repository"]["preconditions"].append(
            {
                "path": f"chart/{rel}",
                "sha256": __import__("hashlib").sha256(legacy.read_bytes()).hexdigest(),
            }
        )
        the_plan["targets"].append({"path": f"chart/{rel}"})
        the_plan["decisions"]["supersededDeclarations"] = [rel]
        return legacy

    def test_superseded_file_removed_when_the_runner_verifies_every_declaration(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo)
            legacy = self._add_legacy_file(repo, the_plan, "templates/legacy.yaml", self.SUPPORTED_DECL)

            code, report = run_migration(repo, the_plan, "apply", tmp)

            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertFalse(legacy.exists())

    def test_superseded_file_blocked_when_it_also_declares_an_unmigrated_identity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo)
            legacy = self._add_legacy_file(
                repo,
                the_plan,
                "templates/legacy.yaml",
                self.SUPPORTED_DECL + "---\n" + self.FOREIGN_DECL,
            )

            code, report = run_migration(repo, the_plan, "apply", tmp)

            self.assertEqual(code, 4)
            self.assertTrue(
                any("not generated as a SUPPORTED datasource" in e for e in report["blocking"])
            )
            self.assertTrue(legacy.is_file())

    def test_superseded_file_with_a_dbpolicy_is_blocked(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo)
            legacy = self._add_legacy_file(
                repo,
                the_plan,
                "templates/legacy.yaml",
                self.SUPPORTED_DECL + "---\nkind: DbPolicy\nmicroserviceName: orders\n",
            )

            code, report = run_migration(repo, the_plan, "apply", tmp)

            self.assertEqual(code, 4)
            self.assertTrue(any("does not replace access policies" in e for e in report["blocking"]))
            self.assertTrue(legacy.is_file())

    def test_superseded_wrapper_with_empty_declarations_is_blocked(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo)
            legacy = self._add_legacy_file(
                repo, the_plan, "templates/legacy.yaml", "kind: DatabaseDeclaration\ndeclarations: []\n"
            )

            code, report = run_migration(repo, the_plan, "apply", tmp)

            self.assertEqual(code, 4)
            self.assertTrue(any("non-empty list" in e for e in report["blocking"]))
            self.assertTrue(legacy.is_file())

    def test_superseded_wrapper_with_a_scalar_declaration_entry_is_blocked(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo)
            legacy = self._add_legacy_file(
                repo,
                the_plan,
                "templates/legacy.yaml",
                "kind: DatabaseDeclaration\n"
                "declarations:\n"
                "  - classifierConfig:\n"
                "      classifier: {microserviceName: orders, namespace: '{{ .Values.NAMESPACE }}', scope: service}\n"
                "    type: postgresql\n"
                "  - keep-this-scalar\n",
            )

            code, report = run_migration(repo, the_plan, "apply", tmp)

            self.assertEqual(code, 4)
            self.assertTrue(any("non-object entry" in e for e in report["blocking"]))
            self.assertTrue(legacy.is_file())

    def test_superseded_declaration_with_changed_settings_is_blocked(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo)
            legacy = self._add_legacy_file(
                repo,
                the_plan,
                "templates/legacy.yaml",
                self.SUPPORTED_DECL + "settings:\n  pgExtensions: [vector]\n",
            )

            code, report = run_migration(repo, the_plan, "apply", tmp)

            self.assertEqual(code, 4)
            self.assertTrue(any("settings differ" in e for e in report["blocking"]))
            self.assertTrue(legacy.is_file())

    def test_conflicting_duplicate_identities_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo)
            dup = {
                "id": "orders-postgresql-dup",
                "type": "postgresql",
                "classifier": {
                    "microserviceName": "orders",
                    "namespace": "{{ .Values.NAMESPACE }}",
                    "scope": "service",
                },
                "requestedRoles": [""],
                "parameters": {"namePrefix": "", "settings": {"pgExtensions": ["vector"]}},
                "migrationFeasibility": "SUPPORTED",
                "compatibility": {"mode": "NATIVE_MOUNTED_PROVIDER"},
            }
            the_plan["inputs"]["datasources"].append(dup)
            the_plan["decisions"]["claims"].append(
                {
                    "datasourceId": "orders-postgresql-dup",
                    "role": "",
                    "workloadFile": "templates/deployment.yaml",
                    "workloadKind": "Deployment",
                    "workloadName": "orders",
                    "containers": ["orders"],
                    "initContainers": [],
                }
            )

            code, report = run_migration(repo, the_plan, "check", tmp)

            self.assertEqual(code, 4)
            self.assertTrue(
                any("resolve to the same database identity" in e for e in report["blocking"])
            )

    def test_duplicate_identity_with_conflicting_discriminator_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo)
            dup = {
                "id": "orders-postgresql-dup",
                "type": "postgresql",
                "classifier": {
                    "microserviceName": "orders",
                    "namespace": "{{ .Values.NAMESPACE }}",
                    "scope": "service",
                },
                "requestedRoles": [""],
                "parameters": {"namePrefix": "", "settings": {}, "physicalDatabaseId": ""},
                "migrationFeasibility": "SUPPORTED",
                "compatibility": {"mode": "NATIVE_MOUNTED_PROVIDER"},
            }
            the_plan["inputs"]["datasources"].append(dup)
            the_plan["decisions"]["nameDiscriminators"] = {"orders-postgresql-dup": "configs"}
            the_plan["decisions"]["claims"].append(
                {
                    "datasourceId": "orders-postgresql-dup",
                    "role": "",
                    "workloadFile": "templates/deployment.yaml",
                    "workloadKind": "Deployment",
                    "workloadName": "orders",
                    "containers": ["orders"],
                    "initContainers": [],
                }
            )

            code, report = run_migration(repo, the_plan, "check", tmp)

            self.assertEqual(code, 4)
            self.assertTrue(any("name discriminator" in e for e in report["blocking"]))

    def test_superseded_declaration_in_another_namespace_is_not_deleted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo)
            legacy = self._add_legacy_file(
                repo,
                the_plan,
                "templates/legacy.yaml",
                "kind: DatabaseDeclaration\n"
                "metadata:\n"
                "  namespace: shared-infra\n"
                "classifierConfig:\n"
                "  classifier:\n"
                "    microserviceName: orders\n"
                "    scope: service\n"
                "type: postgresql\n",
            )

            code, report = run_migration(repo, the_plan, "apply", tmp)

            self.assertEqual(code, 4)
            self.assertTrue(
                any("not generated as a SUPPORTED datasource" in e for e in report["blocking"])
            )
            self.assertTrue(legacy.is_file())

    def test_superseded_declaration_with_legacy_placeholders_still_matches(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo)
            legacy = self._add_legacy_file(
                repo,
                the_plan,
                "templates/legacy.yaml",
                "kind: DatabaseDeclaration\n"
                "classifierConfig:\n"
                "  classifier:\n"
                "    microserviceName: '{{$SERVICE_NAME}}'\n"
                "    scope: service\n"
                "type: postgresql\n",
            )

            code, report = run_migration(repo, the_plan, "apply", tmp)

            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertFalse(legacy.exists())

    def test_superseded_declaration_with_clone_behaviour_is_blocked(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo)
            legacy = self._add_legacy_file(
                repo,
                the_plan,
                "templates/legacy.yaml",
                self.SUPPORTED_DECL + "initialInstantiation:\n  approach: clone\n",
            )

            code, report = run_migration(repo, the_plan, "apply", tmp)

            self.assertEqual(code, 4)
            self.assertTrue(any("initialInstantiation" in e for e in report["blocking"]))
            self.assertTrue(legacy.is_file())

    def test_nested_operator_namespace_does_not_shadow_the_root_value(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            (repo / "chart/values.yaml").write_text(
                "NAMESPACE: orders-ns\nSERVICE_NAME: orders\n"
                "nested:\n  DBAAS_OPERATOR_NAMESPACE: wrong-place\n",
                encoding="utf-8",
            )
            the_plan = plan(repo)

            code, report = run_migration(repo, the_plan, "apply", tmp)

            self.assertEqual(code, 0, report.get("__stderr"))
            values = (repo / "chart/values.yaml").read_text(encoding="utf-8")
            self.assertIn('\nDBAAS_OPERATOR_NAMESPACE: ""\n', values)

    def test_plan_that_generates_nothing_is_refused(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo)
            the_plan["inputs"]["datasources"][0]["migrationFeasibility"] = "NOT_SUPPORTED_DYNAMIC"
            the_plan["decisions"]["claims"] = []
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 4)
            self.assertTrue(any("nothing to migrate" in e for e in report["blocking"]))
            self.assertFalse(
                (repo / "chart/templates/dbaas-mounted-secret-resources.yaml").exists()
            )

    def test_templated_replicas_and_comments_survive_workload_patching(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            deployment = repo / "chart/templates/deployment.yaml"
            deployment.write_text(
                "apiVersion: apps/v1\n"
                "kind: Deployment\n"
                "metadata:\n"
                "  name: orders\n"
                "  namespace: '{{ .Values.NAMESPACE }}'\n"
                "spec:\n"
                "  replicas: {{ .Values.replicaCount }}\n"
                "  template:\n"
                "    spec:\n"
                "      containers:\n"
                "        - name: orders  # the main container\n"
                "          image: orders:latest\n",
                encoding="utf-8",
            )
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            patched = deployment.read_text(encoding="utf-8")
            self.assertIn("replicas: {{ .Values.replicaCount }}", patched)
            self.assertIn("# the main container", patched)
            self.assertIn("volumeMounts:", patched)

    def test_null_volumes_field_is_a_typed_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            (repo / "chart/templates/deployment.yaml").write_text(
                DEPLOYMENT.replace(
                    "      volumes:\n        - name: config\n          configMap:\n"
                    "            name: orders-config\n",
                    "      volumes:\n",
                ),
                encoding="utf-8",
            )
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 4)
            self.assertTrue(any("volumes is present but null" in e for e in report["blocking"]))

    def test_standalone_helm_assignment_in_workload_blocks(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            (repo / "chart/templates/deployment.yaml").write_text(
                "{{- $svc := .Values.SERVICE_NAME }}\n" + DEPLOYMENT, encoding="utf-8"
            )
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 4)
            self.assertTrue(any("standalone Helm action" in e for e in report["blocking"]))

    def test_empty_inline_volumes_list_becomes_a_block_list(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            (repo / "chart/templates/deployment.yaml").write_text(
                "apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: orders\n"
                "  namespace: '{{ .Values.NAMESPACE }}'\n"
                "spec:\n  template:\n    spec:\n"
                "      containers:\n        - name: orders\n          image: orders:latest\n"
                "          volumeMounts: []\n"
                "      volumes: []\n",
                encoding="utf-8",
            )
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            patched = (repo / "chart/templates/deployment.yaml").read_text(encoding="utf-8")
            self.assertEqual(patched.count("volumes:"), 1)
            self.assertEqual(patched.count("volumeMounts:"), 1)
            self.assertNotIn("volumes: []", patched)
            doc = yaml.safe_load(patched.replace("{{ .Values.NAMESPACE }}", "ns"))
            pod = doc["spec"]["template"]["spec"]
            self.assertEqual(len(pod["volumes"]), 1)
            self.assertEqual(len(pod["containers"][0]["volumeMounts"]), 1)

    def test_namespace_less_helm_workload_validates(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            # A Deployment with no metadata.namespace -- it lands in the release ns.
            (repo / "chart/templates/deployment.yaml").write_text(
                "apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: orders\n"
                "spec:\n  template:\n    spec:\n"
                "      containers:\n        - name: orders\n          image: orders:latest\n",
                encoding="utf-8",
            )
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            names = {v["name"]: v["status"] for v in report["validation"]}
            self.assertEqual(names.get("validate_rendered"), "passed")

    def test_string_false_disc_map_and_non_string_discriminator_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            broken = plan(repo)
            broken["decisions"]["nameDiscriminators"] = {"orders-postgresql-service": 5}
            code, report = run_migration(repo, broken, "check", tmp)
            self.assertEqual(code, 2)
            self.assertIn("nameDiscriminators", report["validation"][0]["details"])


if __name__ == "__main__":
    unittest.main()
