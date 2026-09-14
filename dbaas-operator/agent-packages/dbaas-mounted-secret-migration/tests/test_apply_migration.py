"""Tests for the mounted-secret migration writer (scripts/apply_migration.py)."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - PyYAML is a pinned test dependency
    yaml = None

PACKAGE_ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = PACKAGE_ROOT / ".apm" / "skills" / "dbaas-mounted-secret-migration" / "scripts"
RUNNER = SCRIPTS / "apply_migration.py"

CHART_YAML = "apiVersion: v2\nname: orders\nversion: 0.1.0\n"
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
    "  replicas: {{ .Values.replicaCount }}\n"
    "  template:\n"
    "    spec:\n"
    "      containers:\n"
    "        - name: orders\n"
    "          image: orders:latest\n"
    "          # keep this comment exactly where it is\n"
    "      volumes:\n"
    "        - name: config\n"
    "          configMap:\n"
    "            name: orders-config\n"
)
PLAIN_DEPLOYMENT = (
    "apiVersion: apps/v1\n"
    "kind: Deployment\n"
    "metadata:\n"
    "  name: orders\n"
    "spec:\n"
    "  template:\n"
    "    spec:\n"
    "      containers:\n"
    "        - name: orders\n"
    "          image: orders:latest\n"
)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def scaffold(tmp: Path, *, deployment: str = DEPLOYMENT) -> Path:
    repo = tmp / "repo"
    (repo / "chart" / "templates").mkdir(parents=True)
    (repo / "chart" / "Chart.yaml").write_text(CHART_YAML, encoding="utf-8")
    (repo / "chart" / "values.yaml").write_text(VALUES, encoding="utf-8")
    (repo / "chart" / "values.schema.json").write_text(SCHEMA, encoding="utf-8")
    (repo / "chart" / "templates" / "deployment.yaml").write_text(deployment, encoding="utf-8")
    return repo


def scaffold_plain(tmp: Path, *, deployment: str = PLAIN_DEPLOYMENT) -> Path:
    repo = tmp / "repo"
    (repo / "deploy").mkdir(parents=True)
    (repo / "deploy" / "deployment.yaml").write_text(deployment, encoding="utf-8")
    return repo


def datasource(**overrides) -> dict:
    base = {
        "id": "orders-postgresql-service",
        "type": "postgresql",
        "classifier": {"microserviceName": "orders", "scope": "service"},
        "requestedRoles": [""],
        "parameters": {},
        "migrationFeasibility": "SUPPORTED",
    }
    base.update(overrides)
    return base


def claim(**overrides) -> dict:
    base = {
        "datasourceId": "orders-postgresql-service",
        "role": "",
        "workloadFile": "templates/deployment.yaml",
        "workloadKind": "Deployment",
        "workloadName": "orders",
        "containers": ["orders"],
        "initContainers": [],
    }
    base.update(overrides)
    return base


def source_hashes(repo: Path, root: str, *relatives: str) -> dict:
    # load_plan joins each sourceHashes key with the plan's own root before
    # resolving it (root-relative in, repo-relative internally) -- so `root`
    # here must match whatever root the resulting dict is actually placed
    # under, the same way workloadFile/supersededDeclarations[].path do.
    return {rel: sha256(repo / root / rel) for rel in relatives}


def root_plan(repo: Path, *, root="chart", kind="helm", datasources=None, claims=None, **overrides) -> dict:
    default_workload_file = "templates/deployment.yaml" if kind == "helm" else "deployment.yaml"
    operator_namespace = overrides.get(
        "operatorNamespace", "{{ .Values.DBAAS_OPERATOR_NAMESPACE }}" if kind == "helm" else "dbaas-system"
    )
    base = {
        "root": root,
        "kind": kind,
        "outputFile": "templates/dbaas-mounted-secret-resources.yaml" if kind == "helm" else "dbaas-mounted-secret-resources.yaml",
        "operatorNamespace": operator_namespace,
        "workloadNamespace": "{{ .Values.NAMESPACE }}" if kind == "helm" else "orders-ns",
        "originService": "orders",
        "datasources": datasources if datasources is not None else [datasource()],
        "claims": claims if claims is not None else [claim(workloadFile=default_workload_file)],
    }
    # Computed lazily, and skipped entirely when the caller supplies its own
    # sourceHashes: hashing repo/root/default_workload_file unconditionally
    # would crash outright for a caller passing an unrelated root (e.g.
    # root="" while the real file lives under "chart/"), since that default
    # path need not exist there at all.
    if "sourceHashes" not in overrides:
        hash_targets = [default_workload_file]
        if operator_namespace == "{{ .Values.DBAAS_OPERATOR_NAMESPACE }}":
            # load_plan now requires a hash for values.yaml/values.schema.json
            # whenever update_values() would read them -- the same condition
            # it checks itself.
            if (repo / root / "values.yaml").is_file():
                hash_targets.append("values.yaml")
            if (repo / root / "values.schema.json").is_file():
                hash_targets.append("values.schema.json")
        base["sourceHashes"] = source_hashes(repo, root, *hash_targets)
    base.update(overrides)
    return base


def plan(repo: Path, **overrides) -> dict:
    return {"roots": [root_plan(repo, **overrides)]}


def run_migration(repo: Path, the_plan: dict, mode: str, tmp: Path) -> tuple[int, dict]:
    plan_path = tmp / "plan.json"
    plan_path.write_text(json.dumps(the_plan, indent=2), encoding="utf-8")
    result = subprocess.run(
        [sys.executable, str(RUNNER), "--repo-root", str(repo), "--plan", str(plan_path), f"--{mode}"],
        capture_output=True, text=True, check=False,
    )
    try:
        parsed = json.loads(result.stdout) if result.stdout.strip() else {}
    except ValueError:
        parsed = {}
    parsed["__stderr"] = result.stderr
    parsed["__stdout"] = result.stdout
    return result.returncode, parsed


@unittest.skipUnless(shutil.which("helm"), "helm is not on PATH")
class HelmApplyTest(unittest.TestCase):
    def test_apply_generates_resources_and_mount_then_repeated_apply_is_unchanged(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo)
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertEqual(report["status"], "changed")

            output = repo / "chart/templates/dbaas-mounted-secret-resources.yaml"
            docs = [d for d in yaml.safe_load_all(output.read_text(encoding="utf-8")) if d]
            kinds = sorted(d["kind"] for d in docs)
            self.assertEqual(kinds, ["DatabaseSecretClaim", "InternalDatabase"])
            for doc in docs:
                self.assertEqual(doc["spec"]["operatorNamespace"], "{{ .Values.DBAAS_OPERATOR_NAMESPACE }}")
                self.assertNotIn("namespace", doc["spec"]["classifier"])

            deployment_text = (repo / "chart/templates/deployment.yaml").read_text(encoding="utf-8")
            self.assertIn("replicas: {{ .Values.replicaCount }}", deployment_text)
            self.assertIn("# keep this comment exactly where it is", deployment_text)
            # Every remaining Helm expression must be resolved before this is
            # handed to plain PyYAML -- an unresolved "{{ ... }}" parses as a
            # (likely invalid) flow mapping, not the scalar it renders to.
            resolved_text = deployment_text.replace(
                "{{ .Values.NAMESPACE }}", "orders-ns"
            ).replace("{{ .Values.replicaCount }}", "1")
            deployment = yaml.safe_load(resolved_text)
            pod = deployment["spec"]["template"]["spec"]
            self.assertEqual(len(pod["volumes"]), 2)  # pre-existing "config" + the new one
            mount = pod["containers"][0]["volumeMounts"][0]
            self.assertTrue(mount["readOnly"])
            self.assertEqual(mount["mountPath"], "/etc/secrets/dbaas-secrets/orders-postgresql-service-default-credentials")

            values_text = (repo / "chart/values.yaml").read_text(encoding="utf-8")
            self.assertIn('DBAAS_OPERATOR_NAMESPACE: ""', values_text)
            schema = json.loads((repo / "chart/values.schema.json").read_text(encoding="utf-8"))
            self.assertNotIn("DBAAS_OPERATOR_NAMESPACE", schema.get("required", []))
            self.assertEqual(schema["properties"]["DBAAS_OPERATOR_NAMESPACE"], {"type": "string"})

            # Repeated apply with refreshed source hashes must be a no-op.
            plan2 = plan(repo)
            code2, report2 = run_migration(repo, plan2, "apply", tmp)
            self.assertEqual(code2, 0, report2.get("__stderr"))
            self.assertEqual(report2["status"], "unchanged")
            self.assertEqual((repo / "chart/templates/dbaas-mounted-secret-resources.yaml").read_bytes(), output.read_bytes())

    def test_check_mode_never_writes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            before = {p: (repo / p).read_bytes() for p in ("chart/values.yaml", "chart/templates/deployment.yaml")}
            code, report = run_migration(repo, plan(repo), "check", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertEqual(report["status"], "valid")
            self.assertFalse((repo / "chart/templates/dbaas-mounted-secret-resources.yaml").exists())
            for rel, content in before.items():
                self.assertEqual((repo / rel).read_bytes(), content)

    def test_two_roles_expand_claims_not_databases(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(
                repo,
                datasources=[datasource(requestedRoles=["", "admin"])],
                claims=[claim(role=""), claim(role="admin")],
            )
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            docs = [d for d in yaml.safe_load_all((repo / "chart/templates/dbaas-mounted-secret-resources.yaml").read_text(encoding="utf-8")) if d]
            self.assertEqual(sum(d["kind"] == "InternalDatabase" for d in docs), 1)
            self.assertEqual(sum(d["kind"] == "DatabaseSecretClaim" for d in docs), 2)

    def test_role_with_surrounding_whitespace_is_normalized_consistently(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(
                repo,
                datasources=[datasource(requestedRoles=["admin"])],
                claims=[claim(role="  admin  ")],
            )
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            docs = [d for d in yaml.safe_load_all((repo / "chart/templates/dbaas-mounted-secret-resources.yaml").read_text(encoding="utf-8")) if d]
            claim_doc = next(d for d in docs if d["kind"] == "DatabaseSecretClaim")
            self.assertEqual(claim_doc["spec"]["userRole"], "admin")

    def test_extra_classifier_key_uses_hash_discriminator_and_extra_keys(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(
                repo,
                datasources=[datasource(classifier={"microserviceName": "orders", "scope": "service", "region": "eu"})],
            )
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            docs = [d for d in yaml.safe_load_all((repo / "chart/templates/dbaas-mounted-secret-resources.yaml").read_text(encoding="utf-8")) if d]
            internal = next(d for d in docs if d["kind"] == "InternalDatabase")
            self.assertRegex(internal["metadata"]["name"], r"orders-postgresql-service-[0-9a-f]{8}-db")
            self.assertEqual(internal["spec"]["classifier"]["extraKeys"], {"region": "eu"})

    def test_reserved_key_inside_extra_keys_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(
                repo,
                datasources=[datasource(classifier={
                    "microserviceName": "orders", "scope": "service", "extraKeys": {"tenantId": "sneaky"},
                })],
            )
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 2, report.get("__stderr"))
            self.assertIn("reserved keys", report["validation"][0]["details"])

    def test_missing_classifier_scope_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo, datasources=[datasource(classifier={"microserviceName": "orders"})])
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 2, report.get("__stderr"))
            self.assertIn("classifier.scope", report["validation"][0]["details"])

    def test_datasource_identity_collision_across_roots_is_allowed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            for name in ("chart-a", "chart-b"):
                (repo / name / "templates").mkdir(parents=True)
                (repo / name / "Chart.yaml").write_text(CHART_YAML, encoding="utf-8")
                (repo / name / "values.yaml").write_text(VALUES, encoding="utf-8")
                (repo / name / "templates" / "deployment.yaml").write_text(DEPLOYMENT, encoding="utf-8")
            roots = [
                root_plan(repo, root="chart-a", operatorNamespace="dbaas-system", workloadNamespace="orders-ns"),
                root_plan(repo, root="chart-b", operatorNamespace="dbaas-system", workloadNamespace="orders-ns"),
            ]
            the_plan = {"roots": roots}
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            doc_a = [d for d in yaml.safe_load_all((repo / "chart-a/templates/dbaas-mounted-secret-resources.yaml").read_text(encoding="utf-8")) if d]
            doc_b = [d for d in yaml.safe_load_all((repo / "chart-b/templates/dbaas-mounted-secret-resources.yaml").read_text(encoding="utf-8")) if d]
            self.assertEqual(
                {d["metadata"]["name"] for d in doc_a}, {d["metadata"]["name"] for d in doc_b}
            )

    def test_templated_release_specific_name_renders_and_validates(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            resource_name = '{{ trunc 20 .Release.Name | trimSuffix "-" }}-svc'
            the_plan = plan(
                repo,
                datasources=[datasource(
                    classifier={"microserviceName": "{{ .Values.SERVICE_NAME }}", "scope": "service"},
                    resourceName=resource_name,
                )],
            )
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            content = (repo / "chart/templates/dbaas-mounted-secret-resources.yaml").read_text(encoding="utf-8")
            self.assertIn(".Release.Name", content)
            names = [entry["name"] for entry in report["validation"]]
            self.assertIn("validate-rendered", names)

    def test_templated_identity_without_release_name_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(
                repo,
                datasources=[datasource(
                    classifier={"microserviceName": "{{ .Values.SERVICE_NAME }}", "scope": "service"},
                    resourceName="{{ .Values.SERVICE_NAME }}-svc",
                )],
            )
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertIn(".Release.Name", "".join(report.get("blocking", [])))

    def test_templated_identity_with_no_resource_name_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(
                repo,
                datasources=[datasource(classifier={"microserviceName": "{{ .Values.SERVICE_NAME }}", "scope": "service"})],
            )
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertIn("resourceName", "".join(report.get("blocking", [])))

    def test_missing_values_schema_is_valid_and_untouched(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            (repo / "chart" / "values.schema.json").unlink()
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertFalse((repo / "chart" / "values.schema.json").exists())
            self.assertIn('DBAAS_OPERATOR_NAMESPACE: ""', (repo / "chart" / "values.yaml").read_text(encoding="utf-8"))

    def test_literal_operator_namespace_does_not_touch_values(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo, operatorNamespace="dbaas-system")
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertNotIn("DBAAS_OPERATOR_NAMESPACE", (repo / "chart" / "values.yaml").read_text(encoding="utf-8"))

    def test_null_pod_spec_is_a_typed_unsupported_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            deployment = (
                "apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: orders\n"
                "  namespace: '{{ .Values.NAMESPACE }}'\nspec:\n  template:\n    spec:\n"
            )
            repo = scaffold(tmp, deployment=deployment)
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertTrue(any("spec.template.spec" in e for e in report.get("blocking", [])))

    def test_empty_pod_spec_mapping_is_a_typed_unsupported_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            deployment = (
                "apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: orders\n"
                "  namespace: '{{ .Values.NAMESPACE }}'\nspec:\n  template:\n    spec: {}\n"
            )
            repo = scaffold(tmp, deployment=deployment)
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertTrue(any("empty mapping" in e for e in report.get("blocking", [])))

    def test_standalone_helm_block_action_in_workload_blocks(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            deployment = DEPLOYMENT.replace(
                "      volumes:\n",
                "      {{- if .Values.extra }}\n      volumes:\n",
            ).replace("            name: orders-config\n", "            name: orders-config\n      {{- end }}\n")
            repo = scaffold(tmp, deployment=deployment)
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertTrue(any("block action" in e for e in report.get("blocking", [])))

    def test_crlf_and_missing_final_newline_workload_is_preserved(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            body = DEPLOYMENT.rstrip("\n").replace("\n", "\r\n")  # CRLF, no trailing newline
            repo = scaffold(tmp, deployment=body)
            the_plan = plan(repo)
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            patched = (repo / "chart/templates/deployment.yaml").read_bytes()
            self.assertIn(b"\r\n", patched)
            self.assertNotIn(b"\n\n", patched.replace(b"\r\n", b""))  # no stray bare LF introduced

    def test_idempotent_mount_already_present_is_a_no_op(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo)
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            deployment_before = (repo / "chart/templates/deployment.yaml").read_bytes()
            plan2 = plan(repo)
            code2, report2 = run_migration(repo, plan2, "apply", tmp)
            self.assertEqual(code2, 0, report2.get("__stderr"))
            self.assertEqual(report2["status"], "unchanged")
            self.assertEqual((repo / "chart/templates/deployment.yaml").read_bytes(), deployment_before)

    def test_mount_name_collision_with_a_different_secret_blocks(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            deployment = DEPLOYMENT.replace(
                "      volumes:\n        - name: config\n",
                "      volumes:\n        - name: orders-postgresql-service-default-secret\n"
                "          secret:\n            secretName: some-other-secret\n        - name: config\n",
            )
            repo = scaffold(tmp, deployment=deployment)
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertTrue(any("different secret" in e for e in report.get("blocking", [])))

    def test_missing_helm_on_path_is_exit_4(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            env = dict(os.environ)
            # Remove every directory that could contain a helm executable.
            env["PATH"] = str(tmp / "empty-path")
            (tmp / "empty-path").mkdir()
            plan_path = tmp / "plan.json"
            plan_path.write_text(json.dumps(plan(repo)), encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(RUNNER), "--repo-root", str(repo), "--plan", str(plan_path), "--check"],
                capture_output=True, text=True, check=False, env=env,
            )
            self.assertEqual(result.returncode, 4, result.stderr)
            report = json.loads(result.stdout)
            self.assertTrue(any("helm is not on PATH" in e for e in report.get("blocking", [])))

    def test_stale_source_hash_is_exit_3(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo)
            (repo / "chart/templates/deployment.yaml").write_text(DEPLOYMENT + "\n# edited after discovery\n", encoding="utf-8")
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 3, report.get("__stderr"))
            self.assertTrue(any("sha256 changed" in e for e in report.get("blocking", [])))

    def test_source_equals_output_path_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo, outputFile="templates/deployment.yaml")
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 2, report.get("__stderr"))
            self.assertIn("collides with a migration source", report["validation"][0]["details"])

    def test_duplicate_root_declaration_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = {"roots": [root_plan(repo), root_plan(repo, root="chart")]}
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 2, report.get("__stderr"))
            self.assertIn("declared more than once", report["validation"][0]["details"])

    def test_two_distinct_roots_writing_the_same_output_path_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            # root="" (the repo root) with outputFile="chart/out.yaml" resolves to
            # the same final path as root="chart" with outputFile="out.yaml" --
            # two genuinely different declared roots colliding on one output file.
            first = root_plan(
                repo, root="", outputFile="chart/out.yaml", operatorNamespace="dbaas-system",
                sourceHashes=source_hashes(repo, "", "chart/templates/deployment.yaml"),
            )
            first["claims"][0]["workloadFile"] = "chart/templates/deployment.yaml"
            second = root_plan(repo, root="chart", outputFile="out.yaml")
            the_plan = {"roots": [first, second]}
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 2, report.get("__stderr"))
            self.assertIn("write to the same outputFile", report["validation"][0]["details"])

    @unittest.skipIf(os.name == "nt", "POSIX directory-permission semantics; runs on the Linux CI runner")
    def test_write_failure_mid_transaction_rolls_back(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo)
            values_before = (repo / "chart/values.yaml").read_bytes()
            deployment_before = (repo / "chart/templates/deployment.yaml").read_bytes()
            output_before_exists = (repo / "chart/templates/dbaas-mounted-secret-resources.yaml").exists()
            # sorted(changes.files) writes both chart/templates/* entries before
            # chart/values.yaml (alphabetically "templates" < "values"), so making
            # only "chart" itself (not "chart/templates") read-only lets the two
            # templates/* writes succeed first and fails on values.yaml -- proving
            # the already-applied templates/* files get rolled back.
            chart_dir = repo / "chart"
            mode = chart_dir.stat().st_mode
            try:
                os.chmod(chart_dir, 0o500)
                code, report = run_migration(repo, the_plan, "apply", tmp)
            finally:
                os.chmod(chart_dir, mode)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertEqual((repo / "chart/values.yaml").read_bytes(), values_before)
            self.assertEqual((repo / "chart/templates/deployment.yaml").read_bytes(), deployment_before)
            self.assertEqual((repo / "chart/templates/dbaas-mounted-secret-resources.yaml").exists(), output_before_exists)

    def test_superseded_declaration_with_unconsumed_field_blocks(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            declaration = {
                "kind": "DatabaseDeclaration",
                "declarations": [
                    {
                        "classifierConfig": {"classifier": {"microserviceName": "orders", "scope": "service"}},
                        "type": "postgresql",
                        "versioningConfig": {"approach": "new"},
                    }
                ],
            }
            (repo / "chart/templates/dbaas-declaration.json").write_text(json.dumps(declaration), encoding="utf-8")
            the_plan = plan(repo)
            the_plan["roots"][0]["supersededDeclarations"] = [{"path": "templates/dbaas-declaration.json"}]
            the_plan["roots"][0]["sourceHashes"]["templates/dbaas-declaration.json"] = sha256(repo / "chart/templates/dbaas-declaration.json")
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertTrue(any("versioningConfig" in e for e in report.get("blocking", [])))
            self.assertTrue((repo / "chart/templates/dbaas-declaration.json").exists())

    def test_superseded_declaration_fully_migrated_is_deleted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            declaration = {
                "kind": "DatabaseDeclaration",
                "declarations": [
                    {
                        "classifierConfig": {"classifier": {"microserviceName": "orders", "scope": "service"}},
                        "type": "postgresql",
                    }
                ],
            }
            (repo / "chart/templates/dbaas-declaration.json").write_text(json.dumps(declaration), encoding="utf-8")
            the_plan = plan(repo)
            the_plan["roots"][0]["supersededDeclarations"] = [{"path": "templates/dbaas-declaration.json"}]
            the_plan["roots"][0]["sourceHashes"]["templates/dbaas-declaration.json"] = sha256(repo / "chart/templates/dbaas-declaration.json")
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertFalse((repo / "chart/templates/dbaas-declaration.json").exists())
            self.assertIn("chart/templates/dbaas-declaration.json", report["deletedFiles"])

    def test_superseded_json_declaration_with_unrecognized_sibling_item_blocks(self) -> None:
        # A non-object entry sitting next to a fully-migrated declaration must
        # never be silently dropped from consideration -- that would let the
        # whole file read as "fully proven migrated" and get deleted with it.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            declaration = {
                "kind": "DatabaseDeclaration",
                "declarations": [
                    {
                        "classifierConfig": {"classifier": {"microserviceName": "orders", "scope": "service"}},
                        "type": "postgresql",
                    },
                    "keep-me",
                ],
            }
            (repo / "chart/templates/dbaas-declaration.json").write_text(json.dumps(declaration), encoding="utf-8")
            the_plan = plan(repo)
            the_plan["roots"][0]["supersededDeclarations"] = [{"path": "templates/dbaas-declaration.json"}]
            the_plan["roots"][0]["sourceHashes"]["templates/dbaas-declaration.json"] = sha256(repo / "chart/templates/dbaas-declaration.json")
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertTrue(any("is not an object" in e for e in report.get("blocking", [])))
            self.assertTrue((repo / "chart/templates/dbaas-declaration.json").exists())

    def test_superseded_yaml_declaration_preserves_preamble_comment(self) -> None:
        # A comment before a file's first "---" marker belongs to no document
        # and must survive even when the addressed document is spliced out.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            text = (
                "# preamble must survive\n"
                "---\n"
                "kind: ConfigMap\n"
                "metadata:\n"
                "  name: keep-me\n"
                "---\n"
                "kind: DatabaseDeclaration\n"
                "declarations:\n"
                "  - classifierConfig:\n"
                "      classifier: {microserviceName: orders, scope: service}\n"
                "    type: postgresql\n"
            )
            (repo / "chart/templates/dbaas-declaration.yaml").write_text(text, encoding="utf-8")
            the_plan = plan(repo)
            the_plan["roots"][0]["supersededDeclarations"] = [
                {"path": "templates/dbaas-declaration.yaml", "documentIndex": 2}
            ]
            the_plan["roots"][0]["sourceHashes"]["templates/dbaas-declaration.yaml"] = sha256(
                repo / "chart/templates/dbaas-declaration.yaml"
            )
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            kept = (repo / "chart/templates/dbaas-declaration.yaml").read_text(encoding="utf-8")
            self.assertIn("# preamble must survive", kept)
            self.assertIn("kind: ConfigMap", kept)
            self.assertNotIn("DatabaseDeclaration", kept)

    def test_superseded_yaml_declaration_preserves_preamble_in_reverse_document_order(self) -> None:
        # Same as above, but the migrated (removed) document comes FIRST and
        # the retained ConfigMap comes second -- the preamble is tracked
        # independently of the first document's own span, so it must survive
        # regardless of which document the first one turns out to be.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            text = (
                "# preamble must survive\n"
                "---\n"
                "kind: DatabaseDeclaration\n"
                "declarations:\n"
                "  - classifierConfig:\n"
                "      classifier: {microserviceName: orders, scope: service}\n"
                "    type: postgresql\n"
                "---\n"
                "kind: ConfigMap\n"
                "metadata:\n"
                "  name: keep-me\n"
            )
            (repo / "chart/templates/dbaas-declaration.yaml").write_text(text, encoding="utf-8")
            the_plan = plan(repo)
            the_plan["roots"][0]["supersededDeclarations"] = [
                {"path": "templates/dbaas-declaration.yaml", "documentIndex": 1}
            ]
            the_plan["roots"][0]["sourceHashes"]["templates/dbaas-declaration.yaml"] = sha256(
                repo / "chart/templates/dbaas-declaration.yaml"
            )
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            kept = (repo / "chart/templates/dbaas-declaration.yaml").read_text(encoding="utf-8")
            self.assertTrue(kept.startswith("# preamble must survive"))
            self.assertIn("kind: ConfigMap", kept)
            self.assertNotIn("DatabaseDeclaration", kept)

    def test_superseded_yaml_declaration_preserves_bom_and_crlf(self) -> None:
        # A partial splice (one document removed, one retained) must
        # reproduce the source's BOM and CRLF exactly -- not just when
        # nothing migrates at all and the file is never rewritten.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            body = (
                "kind: ConfigMap\r\n"
                "metadata:\r\n"
                "  name: keep-me\r\n"
                "---\r\n"
                "kind: DatabaseDeclaration\r\n"
                "declarations:\r\n"
                "  - classifierConfig:\r\n"
                "      classifier: {microserviceName: orders, scope: service}\r\n"
                "    type: postgresql\r\n"
            )
            raw = b"\xef\xbb\xbf" + body.encode("utf-8")
            (repo / "chart/templates/dbaas-declaration.yaml").write_bytes(raw)
            the_plan = plan(repo)
            the_plan["roots"][0]["supersededDeclarations"] = [
                {"path": "templates/dbaas-declaration.yaml", "documentIndex": 2}
            ]
            the_plan["roots"][0]["sourceHashes"]["templates/dbaas-declaration.yaml"] = sha256(
                repo / "chart/templates/dbaas-declaration.yaml"
            )
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            kept_bytes = (repo / "chart/templates/dbaas-declaration.yaml").read_bytes()
            self.assertTrue(kept_bytes.startswith(b"\xef\xbb\xbf"))
            self.assertIn(b"\r\n", kept_bytes)
            self.assertNotIn(b"DatabaseDeclaration", kept_bytes)

    def test_label_value_with_uppercase_dot_and_underscore_is_accepted(self) -> None:
        # Kubernetes label values allow uppercase letters, "_", and "." -- a
        # DNS-1123-label-shaped check would reject a perfectly valid value.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo, originService="Orders.API_v1")
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            docs = [d for d in yaml.safe_load_all((repo / "chart/templates/dbaas-mounted-secret-resources.yaml").read_text(encoding="utf-8")) if d]
            claim_doc = next(d for d in docs if d["kind"] == "DatabaseSecretClaim")
            self.assertEqual(claim_doc["metadata"]["labels"]["app.kubernetes.io/name"], "Orders.API_v1")


class PlainApplyTest(unittest.TestCase):
    def test_plain_namespace_less_workload_validates(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold_plain(tmp)
            the_plan = plan(repo, root="deploy", kind="plain")
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertEqual(report["status"], "changed")
            content = (repo / "deploy/dbaas-mounted-secret-resources.yaml").read_text(encoding="utf-8")
            self.assertNotIn("{{", content)

    def test_helm_expression_in_plain_output_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold_plain(tmp)
            the_plan = plan(
                repo, root="deploy", kind="plain",
                datasources=[datasource(classifier={"microserviceName": "{{ .Values.SERVICE_NAME }}", "scope": "service"}, resourceName='{{ trunc 20 .Release.Name | trimSuffix "-" }}-svc')],
            )
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 5, report.get("__stderr"))
            names = {entry["name"]: entry for entry in report["validation"]}
            self.assertEqual(names["no-helm-in-plain-output"]["status"], "failed")


class LoadPlanTest(unittest.TestCase):
    def test_absolute_and_unc_child_paths_are_rejected_not_silently_joined(self) -> None:
        # A child path is validated on its own, before it is joined onto the
        # root -- joining first would let "chart/" hide an absolute path's
        # or a UNC path's dangerous prefix from the "starts with / or a
        # drive letter" check, silently reinterpreting it as a harmless
        # nested relative path instead of rejecting it.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            for bad_path in ("/etc/a", "C:\\a", "\\\\server\\share"):
                with self.subTest(bad_path=bad_path):
                    the_plan = plan(repo, claims=[claim(workloadFile=bad_path)])
                    code, report = run_migration(repo, the_plan, "check", tmp)
                    self.assertEqual(code, 2, report.get("__stderr"))
                    self.assertIn("repository-relative", report["validation"][0]["details"])

    def test_unknown_top_level_field_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo)
            the_plan["unexpected"] = True
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 2, report.get("__stderr"))

    def test_non_supported_feasibility_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo, datasources=[datasource(migrationFeasibility="BLOCKED")])
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 2, report.get("__stderr"))
            self.assertIn("only a SUPPORTED", report["validation"][0]["details"])

    def test_help_exits_zero(self) -> None:
        result = subprocess.run(
            [sys.executable, str(RUNNER), "--help"], capture_output=True, text=True, check=False
        )
        self.assertEqual(result.returncode, 0)
        self.assertIn("--repo-root", result.stdout)

    def test_missing_pyyaml_emits_json_envelope_and_exit_4(self) -> None:
        # A fake yaml.py placed ahead of the real PyYAML on PYTHONPATH makes
        # `import yaml` fail exactly like an uninstalled dependency, without
        # actually uninstalling anything. This used to crash at module import
        # (validate_generated.py raises SystemExit when yaml is missing),
        # exiting 1 with a plain-text message instead of the documented JSON
        # envelope and exit code 4.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            fake_yaml_dir = tmp / "fake-yaml"
            fake_yaml_dir.mkdir()
            (fake_yaml_dir / "yaml.py").write_text("raise ImportError('simulated missing PyYAML')\n", encoding="utf-8")
            env = dict(os.environ)
            existing = env.get("PYTHONPATH", "")
            env["PYTHONPATH"] = str(fake_yaml_dir) + (os.pathsep + existing if existing else "")

            repo = scaffold(tmp)
            plan_path = tmp / "plan.json"
            plan_path.write_text(json.dumps(plan(repo)), encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(RUNNER), "--repo-root", str(repo), "--plan", str(plan_path), "--check"],
                capture_output=True, text=True, check=False, env=env,
            )
            self.assertEqual(result.returncode, 4, result.stderr)
            self.assertEqual(result.stderr, "")
            report = json.loads(result.stdout)
            self.assertEqual(report["status"], "blocked")
            dependency_entries = [e for e in report["validation"] if e["name"] == "dependency"]
            self.assertEqual(len(dependency_entries), 1)
            self.assertEqual(dependency_entries[0]["status"], "failed")

            help_result = subprocess.run(
                [sys.executable, str(RUNNER), "--help"], capture_output=True, text=True, check=False, env=env,
            )
            self.assertEqual(help_result.returncode, 0, help_result.stderr)


if __name__ == "__main__":
    unittest.main()
