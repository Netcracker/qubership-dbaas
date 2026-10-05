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
VALIDATOR = SCRIPTS / "validate_generated.py"

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
class CapabilityGuardTest(unittest.TestCase):
    """Issue #776 phase 4: the optional capabilityGuard/operatorModeEnvironment
    plan fields wrap every generated resource, inserted volume/mount, and
    operator-mode env var in a ".Capabilities.APIVersions.Has" guard, and
    preserve a superseded legacy declaration under the negated (operator-
    absent) branch instead of deleting it. Omitting capabilityGuard preserves
    the existing operator-only behavior exactly."""

    GUARD = "dbaas.netcracker.com/v1"
    LEGACY_DECLARATION = {
        "kind": "DatabaseDeclaration",
        "declarations": [
            {
                "classifierConfig": {"classifier": {"microserviceName": "orders", "scope": "service"}},
                "type": "postgresql",
            }
        ],
    }

    def test_omitting_capability_guard_preserves_existing_behavior(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            output = (repo / "chart/templates/dbaas-mounted-secret-resources.yaml").read_text(encoding="utf-8")
            self.assertNotIn(".Capabilities.APIVersions.Has", output)
            names = {entry["name"] for entry in report["validation"]}
            self.assertNotIn("validate-operator-absent-fallback", names)

    def test_generated_resources_new_mounts_and_env_are_guarded(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            declaration_path = repo / "chart/templates/dbaas-declaration.json"
            declaration_path.write_text(json.dumps(self.LEGACY_DECLARATION), encoding="utf-8")
            the_plan = plan(
                repo,
                capabilityGuard=self.GUARD,
                operatorModeEnvironment={"name": "DBAAS_OPERATOR_ENABLED", "value": "true"},
            )
            the_plan["roots"][0]["supersededDeclarations"] = [{"path": "templates/dbaas-declaration.json"}]
            the_plan["roots"][0]["sourceHashes"]["templates/dbaas-declaration.json"] = sha256(declaration_path)

            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            names = {entry["name"]: entry for entry in report["validation"]}
            self.assertEqual(names["validate-rendered"]["status"], "passed")
            self.assertEqual(names["validate-operator-absent-fallback"]["status"], "passed")

            output = (repo / "chart/templates/dbaas-mounted-secret-resources.yaml").read_text(encoding="utf-8")
            self.assertTrue(output.startswith(f'{{{{- if .Capabilities.APIVersions.Has "{self.GUARD}" }}}}\n'))
            self.assertIn("kind: InternalDatabase", output)
            self.assertIn("kind: DatabaseSecretClaim", output)
            self.assertTrue(output.rstrip("\n").endswith("{{- end }}"))

            deployment = (repo / "chart/templates/deployment.yaml").read_text(encoding="utf-8")
            self.assertIn(f'{{{{- if .Capabilities.APIVersions.Has "{self.GUARD}" }}}}', deployment)
            self.assertIn("name: orders-postgresql-service-default-secret", deployment)
            self.assertIn("name: DBAAS_OPERATOR_ENABLED", deployment)
            self.assertIn("value: 'true'", deployment)

            # The legacy declaration is preserved, not deleted, guarded to the
            # operator-absent (negated) branch -- comments/labels included,
            # since it is the original bytes verbatim.
            self.assertTrue(declaration_path.exists())
            preserved = declaration_path.read_text(encoding="utf-8")
            self.assertIn(f'{{{{- if not (.Capabilities.APIVersions.Has "{self.GUARD}") }}}}', preserved)
            self.assertIn(json.dumps(self.LEGACY_DECLARATION), preserved)
            self.assertIn("{{- end }}", preserved)

            # Repeated apply (of the resources/mounts/env this writer still
            # owns -- a real workflow would no longer list the now-migrated
            # declaration in a fresh plan's supersededDeclarations at all) is
            # unchanged.
            second_plan = plan(
                repo,
                capabilityGuard=self.GUARD,
                operatorModeEnvironment={"name": "DBAAS_OPERATOR_ENABLED", "value": "true"},
            )
            code2, report2 = run_migration(repo, second_plan, "apply", tmp)
            self.assertEqual(code2, 0, report2.get("__stderr"))
            self.assertEqual(report2["status"], "unchanged")

    def test_upgrading_an_unguarded_apply_guards_the_existing_mount_env_and_volume(self) -> None:
        # A real upgrade path: apply once with no capabilityGuard (3.0.0
        # behavior), then apply again with capabilityGuard now set, against
        # the existing output/workload. The pre-existing volume/mount/env
        # must not be treated as "idempotent, nothing to do" -- left
        # unconditional, they would still be required on a cluster where the
        # operator (and the Secret its CRs would populate) is absent.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            first_plan = plan(repo)
            code, report = run_migration(repo, first_plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            deployment_before = (repo / "chart/templates/deployment.yaml").read_text(encoding="utf-8")
            self.assertNotIn(".Capabilities.APIVersions.Has", deployment_before)

            second_plan = plan(
                repo,
                capabilityGuard=self.GUARD,
                operatorModeEnvironment={"name": "DBAAS_OPERATOR_ENABLED", "value": "true"},
                outputSha256=sha256(repo / "chart/templates/dbaas-mounted-secret-resources.yaml"),
            )
            code2, report2 = run_migration(repo, second_plan, "apply", tmp)
            self.assertEqual(code2, 0, report2.get("__stderr"))
            names = {entry["name"]: entry for entry in report2["validation"]}
            self.assertEqual(names["validate-operator-absent-fallback"]["status"], "passed")

            deployment_after = (repo / "chart/templates/deployment.yaml").read_text(encoding="utf-8")
            # The pre-existing mount, volume, and (newly added) env are each
            # individually wrapped -- not merely present somewhere in a file
            # that also happens to contain the guard string elsewhere.
            guard_open = f'{{{{- if .Capabilities.APIVersions.Has "{self.GUARD}" }}}}'
            self.assertIn(
                f"{guard_open}\n            - name: orders-postgresql-service-default-secret\n"
                "              mountPath: /etc/secrets/dbaas-secrets/orders-postgresql-service-default-credentials\n"
                "              readOnly: true\n            {{- end }}",
                deployment_after,
            )
            self.assertIn(
                f"{guard_open}\n        - name: orders-postgresql-service-default-secret\n"
                "          secret:\n            secretName: orders-postgresql-service-default-credentials\n"
                "        {{- end }}",
                deployment_after,
            )

            # A third apply (already guarded) is a true no-op: no double-wrapping.
            third_plan = plan(
                repo,
                capabilityGuard=self.GUARD,
                operatorModeEnvironment={"name": "DBAAS_OPERATOR_ENABLED", "value": "true"},
            )
            code3, report3 = run_migration(repo, third_plan, "apply", tmp)
            self.assertEqual(code3, 0, report3.get("__stderr"))
            self.assertEqual(report3["status"], "unchanged")
            self.assertEqual(
                (repo / "chart/templates/deployment.yaml").read_text(encoding="utf-8"), deployment_after
            )

    def test_upgrading_adjacent_unguarded_items_is_idempotent(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            datasources = [
                datasource(),
                datasource(id="orders-mongodb-service", type="mongodb"),
            ]
            claims = [claim(), claim(datasourceId="orders-mongodb-service")]

            code, report = run_migration(
                repo, plan(repo, datasources=datasources, claims=claims), "apply", tmp
            )
            self.assertEqual(code, 0, report.get("__stderr"))

            guarded_plan = plan(
                repo,
                datasources=datasources,
                claims=claims,
                capabilityGuard=self.GUARD,
                operatorModeEnvironment={"name": "DBAAS_OPERATOR_ENABLED", "value": "true"},
                outputSha256=sha256(repo / "chart/templates/dbaas-mounted-secret-resources.yaml"),
            )
            code2, report2 = run_migration(repo, guarded_plan, "apply", tmp)
            self.assertEqual(code2, 0, report2.get("__stderr"))
            names = {entry["name"]: entry for entry in report2["validation"]}
            self.assertEqual(names["validate-operator-absent-fallback"]["status"], "passed")

            deployment_after = (repo / "chart/templates/deployment.yaml").read_text(encoding="utf-8")
            guard_open = f'{{{{- if .Capabilities.APIVersions.Has "{self.GUARD}" }}}}'
            # One guard wraps both adjacent mounts, one wraps both adjacent
            # volumes, and one wraps the operator-mode environment entry.
            self.assertEqual(deployment_after.count(guard_open), 3)

            repeated_plan = plan(
                repo,
                datasources=datasources,
                claims=claims,
                capabilityGuard=self.GUARD,
                operatorModeEnvironment={"name": "DBAAS_OPERATOR_ENABLED", "value": "true"},
            )
            code3, report3 = run_migration(repo, repeated_plan, "apply", tmp)
            self.assertEqual(code3, 0, report3.get("__stderr"))
            self.assertEqual(report3["status"], "unchanged")
            self.assertEqual(
                (repo / "chart/templates/deployment.yaml").read_text(encoding="utf-8"), deployment_after
            )

    def test_operator_mode_environment_requires_capability_guard(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo, operatorModeEnvironment={"name": "DBAAS_OPERATOR_ENABLED", "value": "true"})
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 2, report.get("__stderr"))
            self.assertTrue(any("capabilityGuard" in e for e in report.get("blocking", [])))


class GeneratedManifestValidationTest(unittest.TestCase):
    def run_validator(self, value: str) -> subprocess.CompletedProcess[str]:
        manifest = (
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
            "          env:\n"
            "            - name: DBAAS_OPERATOR_ENABLED\n"
            f"              value: {value}\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            manifest_path = Path(directory) / "deployment.yaml"
            manifest_path.write_text(manifest, encoding="utf-8")

            return subprocess.run(
                [sys.executable, str(VALIDATOR), str(manifest_path)],
                capture_output=True,
                text=True,
                check=False,
            )

    def test_environment_value_must_be_a_string(self) -> None:
        proc = self.run_validator("true")
        self.assertEqual(proc.returncode, 1)
        self.assertIn(
            "Deployment/default/orders container orders: env 'DBAAS_OPERATOR_ENABLED' value must be a string, got bool",
            proc.stderr,
        )

    def test_null_environment_value_is_treated_as_empty(self) -> None:
        proc = self.run_validator("")
        self.assertEqual(proc.returncode, 0, proc.stderr)


@unittest.skipUnless(shutil.which("helm"), "helm is not on PATH")
class HelmApplyTest(unittest.TestCase):
    def test_quoted_templated_workload_and_resource_names_are_idempotent(self) -> None:
        deployment = DEPLOYMENT.replace(
            "  name: orders\n", "  name: '{{ .Values.SERVICE_NAME }}'\n"
        ).replace(
            "        - name: orders\n", "        - name: '{{ .Values.SERVICE_NAME }}'\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp, deployment=deployment)
            templated_datasource = datasource(
                classifier={
                    "microserviceName": "{{ .Values.SERVICE_NAME }}",
                    "scope": "service",
                },
                resourceName="{{ .Release.Name }}-postgresql-service",
            )
            templated_claim = claim(
                workloadName="{{ .Values.SERVICE_NAME }}",
                containers=["{{ .Values.SERVICE_NAME }}"],
            )
            the_plan = plan(
                repo,
                datasources=[templated_datasource],
                claims=[templated_claim],
                capabilityGuard="dbaas.netcracker.com/v1",
            )

            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertEqual(report["status"], "changed")

            repeated_plan = plan(
                repo,
                datasources=[templated_datasource],
                claims=[templated_claim],
                capabilityGuard="dbaas.netcracker.com/v1",
            )
            code2, report2 = run_migration(repo, repeated_plan, "apply", tmp)
            self.assertEqual(code2, 0, report2.get("__stderr"))
            self.assertEqual(report2["status"], "unchanged")

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

            # The writer never touches values.yaml / values.schema.json -- operatorNamespace
            # must already be a concrete, verified value or Helm expression in the plan.
            self.assertEqual((repo / "chart/values.yaml").read_text(encoding="utf-8"), VALUES)
            self.assertEqual(
                (repo / "chart/values.schema.json").read_text(encoding="utf-8"), SCHEMA
            )

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

    def test_list_valued_parameters_blocks(self) -> None:
        # `or {}` would coerce a present-but-falsy value ([]) to {} before the type
        # check ever saw it, silently accepting a malformed plan.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo, datasources=[datasource(parameters=[])])
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 2, report.get("__stderr"))
            self.assertIn("parameters must be an object", report["validation"][0]["details"])

    def test_list_valued_parameters_settings_blocks(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo, datasources=[datasource(parameters={"settings": []})])
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 2, report.get("__stderr"))
            self.assertIn("parameters.settings must map string keys", report["validation"][0]["details"])

    def test_unknown_parameter_field_blocks(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo, datasources=[datasource(parameters={"bogus": 1})])
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 2, report.get("__stderr"))
            self.assertIn("parameters has unknown properties: bogus", report["validation"][0]["details"])

    def test_list_valued_name_prefix_blocks(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo, datasources=[datasource(parameters={"namePrefix": []})])
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 2, report.get("__stderr"))
            self.assertIn("parameters.namePrefix must be a string", report["validation"][0]["details"])

    def test_duplicate_datasource_identity_within_one_root_is_rejected(self) -> None:
        # Two datasources sharing one (classifier, type) identity is always
        # ambiguous: build_resources() and verify_superseded() would each
        # independently have to pick one as authoritative for that identity,
        # with nothing forcing those picks to agree.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(
                repo,
                datasources=[
                    datasource(id="ds-a"),
                    datasource(id="ds-b", parameters={"settings": {"maxPoolSize": 10}}),
                ],
                claims=[claim(datasourceId="ds-a")],
            )
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 2, report.get("__stderr"))
            self.assertIn("duplicates an earlier datasource", report["validation"][0]["details"])

    def test_classifier_namespace_pin_differing_from_workload_namespace_is_rejected(self) -> None:
        # The operator always materializes into the workload namespace; a
        # classifier pinned to a different, concrete one can never be
        # honored and must not be silently dropped.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(
                repo,
                operatorNamespace="dbaas-system",
                workloadNamespace="orders-ns",
                datasources=[
                    datasource(classifier={"microserviceName": "orders", "scope": "service", "namespace": "other-ns"})
                ],
            )
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertIn("differs from", "".join(report.get("blocking", [])))

    def test_classifier_namespace_pin_on_templated_workload_namespace_is_rejected(self) -> None:
        # cr_classifier() unconditionally drops classifier.namespace, trusting that the
        # operator's own workload-namespace materialization already covers it. When
        # workloadNamespace is still a Helm expression, a literal pin can never be proven
        # to match whatever a deployer eventually supplies -- this must fail closed, not
        # pass simply because no *proven* mismatch exists.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(
                repo,
                datasources=[
                    datasource(classifier={"microserviceName": "orders", "scope": "service", "namespace": "orders-ns"})
                ],
            )
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertIn("cannot be proven", "".join(report.get("blocking", [])))

    def test_classifier_namespace_pin_matching_templated_workload_namespace_is_allowed(self) -> None:
        # An exact string match with workloadNamespace is provably redundant even while
        # workloadNamespace is still a Helm expression: the same literal expression
        # renders to the same value in both places. Rejecting this would reject the
        # ordinary case of a classifier that (harmlessly, if uselessly) re-states the
        # workload namespace's own Helm expression verbatim.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(
                repo,
                datasources=[
                    datasource(
                        classifier={
                            "microserviceName": "orders",
                            "scope": "service",
                            "namespace": "{{ .Values.NAMESPACE }}",
                        }
                    )
                ],
            )
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))

    def test_datasource_identity_collision_across_different_namespaces_is_allowed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            for name in ("chart-a", "chart-b"):
                (repo / name / "templates").mkdir(parents=True)
                (repo / name / "Chart.yaml").write_text(CHART_YAML, encoding="utf-8")
                namespace = "orders-ns" if name == "chart-a" else "orders-b"
                (repo / name / "values.yaml").write_text(
                    f"NAMESPACE: {namespace}\nSERVICE_NAME: orders\n", encoding="utf-8"
                )
                (repo / name / "templates" / "deployment.yaml").write_text(DEPLOYMENT, encoding="utf-8")
            roots = [
                root_plan(repo, root="chart-a", operatorNamespace="dbaas-system", workloadNamespace="orders-ns"),
                root_plan(repo, root="chart-b", operatorNamespace="dbaas-system", workloadNamespace="orders-b"),
            ]
            the_plan = {"roots": roots}
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            doc_a = [d for d in yaml.safe_load_all((repo / "chart-a/templates/dbaas-mounted-secret-resources.yaml").read_text(encoding="utf-8")) if d]
            doc_b = [d for d in yaml.safe_load_all((repo / "chart-b/templates/dbaas-mounted-secret-resources.yaml").read_text(encoding="utf-8")) if d]
            self.assertEqual(
                {d["metadata"]["name"] for d in doc_a}, {d["metadata"]["name"] for d in doc_b}
            )

    def test_two_roots_cannot_hide_collisions_in_one_namespace(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            for name in ("chart-a", "chart-b"):
                (repo / name / "templates").mkdir(parents=True)
                (repo / name / "templates/deployment.yaml").write_text(DEPLOYMENT, encoding="utf-8")
            roots = [
                root_plan(repo, root="chart-a", operatorNamespace="dbaas-system", workloadNamespace="orders-ns"),
                root_plan(repo, root="chart-b", operatorNamespace="dbaas-system", workloadNamespace="orders-ns"),
            ]
            code, report = run_migration(repo, {"roots": roots}, "check", tmp)
            self.assertEqual(code, 2, report)
            self.assertIn("shared by another root", report["validation"][0]["details"])

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

    def test_writer_never_touches_values_files_regardless_of_operator_namespace(self) -> None:
        # Issue #776 phase 5: the writer no longer registers any default for
        # DBAAS_OPERATOR_NAMESPACE (or any other value key) in values.yaml /
        # values.schema.json -- operatorNamespace must already be a concrete,
        # verified value or Helm expression when the plan is built.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            (repo / "chart" / "values.schema.json").unlink()
            values_before = (repo / "chart" / "values.yaml").read_bytes()
            the_plan = plan(repo, operatorNamespace="dbaas-system")
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertFalse((repo / "chart" / "values.schema.json").exists())
            self.assertEqual((repo / "chart" / "values.yaml").read_bytes(), values_before)

    def test_chart_pinned_empty_operator_namespace_value_fails_validation(self) -> None:
        # A chart that explicitly chose DBAAS_OPERATOR_NAMESPACE (or any other
        # values.yaml key the plan's operatorNamespace resolves through) with an
        # empty value must fail spec.operatorNamespace's required-non-empty
        # check -- the writer must never synthesize a clean pilot value that
        # would mask the real, broken pinned value.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            values_path = repo / "chart" / "values.yaml"
            values_path.write_text(
                values_path.read_text(encoding="utf-8") + 'DBAAS_OPERATOR_NAMESPACE: ""\n',
                encoding="utf-8",
            )
            the_plan = plan(repo, operatorNamespace="{{ .Values.DBAAS_OPERATOR_NAMESPACE }}")
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 5, report.get("__stderr"))
            names = {entry["name"]: entry for entry in report["validation"]}
            self.assertEqual(names["validate-rendered"]["status"], "failed")
            self.assertIn("required and must be non-empty", names["validate-rendered"]["details"])

    def test_chart_pinned_invalid_operator_namespace_is_caught(self) -> None:
        # Same guarantee as above for an invalid (not empty) pinned value --
        # rendering with a synthesized clean value regardless would make the
        # expected-vs-rendered comparison compare a substitution against
        # itself and never see the real, broken pinned value.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            values_path = repo / "chart" / "values.yaml"
            values_path.write_text(
                values_path.read_text(encoding="utf-8") + 'DBAAS_OPERATOR_NAMESPACE: "Not_A_Valid_NS!"\n',
                encoding="utf-8",
            )
            code, report = run_migration(repo, plan(repo), "check", tmp)
            self.assertEqual(code, 5, report.get("__stderr"))
            names = {entry["name"]: entry for entry in report["validation"]}
            self.assertEqual(names["validate-rendered"]["status"], "failed")
            self.assertIn("not a valid RFC-1123 namespace label", names["validate-rendered"]["details"])

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

    def test_unrelated_if_block_elsewhere_in_deployment_succeeds(self) -> None:
        # Issue #776: a standalone Helm block action is no longer rejected
        # outright -- one that sits nowhere near the volumes/mounts this
        # writer edits must not block the run at all.
        deployment = DEPLOYMENT.replace(
            "          image: orders:latest\n",
            "          image: orders:latest\n"
            "          {{- if .Values.extra }}\n"
            "          env:\n"
            "            - name: EXTRA\n"
            "              value: \"1\"\n"
            "          {{- end }}\n",
        )
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp, deployment=deployment)
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            patched = (repo / "chart/templates/deployment.yaml").read_text(encoding="utf-8")
            self.assertIn("{{- if .Values.extra }}", patched)
            self.assertIn("{{- end }}", patched)
            self.assertIn("mountPath: /etc/secrets/dbaas-secrets/orders-postgresql-service-default-credentials", patched)

    def test_conditional_volume_entry_alongside_an_unconditional_new_insertion(self) -> None:
        # The "volumes:" key itself is unconditional (always renders); one
        # pre-existing item under it is conditionally included. The writer's
        # own new item is appended after every existing item -- including the
        # conditional one -- and stays unconditional itself, since it is
        # positioned after the guard closes, not inside it.
        deployment = DEPLOYMENT.replace(
            "      volumes:\n        - name: config\n          configMap:\n            name: orders-config\n",
            "      volumes:\n"
            "        - name: config\n"
            "          configMap:\n"
            "            name: orders-config\n"
            "        {{- if .Values.extra }}\n"
            "        - name: extra\n"
            "          configMap:\n"
            "            name: extra-config\n"
            "        {{- end }}\n",
        )
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp, deployment=deployment)
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            patched = (repo / "chart/templates/deployment.yaml").read_text(encoding="utf-8")
            self.assertIn("{{- if .Values.extra }}", patched)
            self.assertIn("{{- end }}", patched)
            # The new volume is the last entry, after the guard closes -- not
            # spliced in between the conditional item and its own {{- end }}.
            self.assertIn(
                "{{- end }}\n        - name: orders-postgresql-service-default-secret", patched
            )

    def test_conditional_mount_entry_alongside_an_unconditional_new_insertion(self) -> None:
        deployment = DEPLOYMENT.replace(
            "          image: orders:latest\n"
            "          # keep this comment exactly where it is\n",
            "          image: orders:latest\n"
            "          volumeMounts:\n"
            "            - name: config\n"
            "              mountPath: /etc/config\n"
            "          {{- if .Values.extra }}\n"
            "            - name: extra\n"
            "              mountPath: /etc/extra\n"
            "          {{- end }}\n"
            "          # keep this comment exactly where it is\n",
        )
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp, deployment=deployment)
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            patched = (repo / "chart/templates/deployment.yaml").read_text(encoding="utf-8")
            self.assertIn(
                "{{- end }}\n            - name: orders-postgresql-service-default-secret", patched
            )

    def test_unrelated_range_and_variable_assignment_elsewhere_do_not_block(self) -> None:
        # A topology range and a "{{- $x := ... }}" assignment, unrelated to
        # the volumes/mounts this writer edits, must not block the run.
        deployment = DEPLOYMENT.replace(
            "apiVersion: apps/v1\n",
            "apiVersion: apps/v1\n"
            "{{- $unused := \"noop\" }}\n",
        ).replace(
            "      volumes:\n        - name: config\n          configMap:\n            name: orders-config\n",
            "      volumes:\n"
            "        - name: config\n"
            "          configMap:\n"
            "            name: orders-config\n"
            "      {{- range .Values.topologyVolumes }}\n"
            "        - name: {{ .name }}\n"
            "          configMap:\n"
            "            name: {{ .configMap }}\n"
            "      {{- end }}\n",
        )
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp, deployment=deployment)
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            patched = (repo / "chart/templates/deployment.yaml").read_text(encoding="utf-8")
            self.assertIn('{{- $unused := "noop" }}', patched)
            self.assertIn("{{- range .Values.topologyVolumes }}", patched)
            self.assertIn(
                "mountPath: /etc/secrets/dbaas-secrets/orders-postgresql-service-default-credentials", patched
            )

    def test_repeated_apply_with_helm_constructs_is_unchanged(self) -> None:
        deployment = DEPLOYMENT.replace(
            "          image: orders:latest\n",
            "          image: orders:latest\n"
            "          {{- if .Values.extra }}\n"
            "          env:\n"
            "            - name: EXTRA\n"
            "              value: \"1\"\n"
            "          {{- end }}\n",
        )
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp, deployment=deployment)
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            first = (repo / "chart/templates/deployment.yaml").read_bytes()
            code2, report2 = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code2, 0, report2.get("__stderr"))
            self.assertEqual(report2["status"], "unchanged")
            self.assertEqual((repo / "chart/templates/deployment.yaml").read_bytes(), first)

    def test_source_lines_remain_byte_identical_outside_inserted_spans(self) -> None:
        # Every original line -- Helm actions included -- must still appear,
        # in order and byte-identical, as a subsequence of the patched output;
        # only genuinely new lines may be interleaved between them.
        deployment = DEPLOYMENT.replace(
            "          image: orders:latest\n",
            "          image: orders:latest\n"
            "          {{- if .Values.extra }}\n"
            "          env:\n"
            "            - name: EXTRA\n"
            "              value: \"1\"\n"
            "          {{- end }}\n",
        )
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp, deployment=deployment)
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            patched_lines = (repo / "chart/templates/deployment.yaml").read_text(encoding="utf-8").splitlines()
            original_lines = deployment.splitlines()
            position = 0
            for original in original_lines:
                try:
                    position = patched_lines.index(original, position) + 1
                except ValueError:
                    self.fail(f"original line not found, in order, in patched output: {original!r}")

    def test_range_generating_the_entire_target_container_list_blocks(self) -> None:
        # No static container entry named "orders" exists at all -- every
        # container comes from a range. Masking makes the file parse, but the
        # target container genuinely cannot be found: an actionable error,
        # not a silent no-op or a corrupted insertion.
        deployment = (
            "apiVersion: apps/v1\n"
            "kind: Deployment\n"
            "metadata:\n"
            "  name: orders\n"
            "  namespace: '{{ .Values.NAMESPACE }}'\n"
            "spec:\n"
            "  template:\n"
            "    spec:\n"
            "      containers:\n"
            "      {{- range .Values.containers }}\n"
            "        - name: {{ .name }}\n"
            "          image: {{ .image }}\n"
            "      {{- end }}\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp, deployment=deployment)
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertTrue(any("orders" in e and "not found" in e for e in report.get("blocking", [])))

    def test_target_list_entirely_wrapped_in_a_conditional_blocks(self) -> None:
        # The whole "volumes:" key -- not merely one item under it -- lives
        # inside a conditional with no static (always-rendering) insertion
        # point: real Helm strips "volumes:" (and anything spliced after the
        # masked-transparent {{- end }}) whenever .Values.extra is falsy,
        # leaving the container's volumeMount with nothing to consume. This
        # is caught downstream by the real helm-render/validate-rendered
        # pass, not silently accepted -- never a "changed"/"unchanged" status.
        deployment = DEPLOYMENT.replace(
            "      volumes:\n",
            "      {{- if .Values.extra }}\n      volumes:\n",
        ).replace("            name: orders-config\n", "            name: orders-config\n      {{- end }}\n")
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp, deployment=deployment)
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 5, report.get("__stderr"))
            self.assertEqual(report["status"], "blocked")
            names = {entry["name"]: entry for entry in report["validation"]}
            self.assertEqual(names["validate-rendered"]["status"], "failed")

    def test_mixed_line_ending_workload_blocks(self) -> None:
        # "\r\n" appears (so a blanket "uses_crlf" check would say CRLF) but most lines
        # are bare "\n" -- normalizing to "\n" for editing and then unconditionally
        # re-adding "\r\n" on the way out would rewrite every one of those never-CRLF
        # lines too. There is no line-ending-preserving edit path here, so this must
        # block rather than guess which lines were meant to keep which ending.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            body = DEPLOYMENT.replace("apiVersion: apps/v1\n", "apiVersion: apps/v1\r\n")
            repo = scaffold(tmp, deployment=body)
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertTrue(any("mixed line endings" in e for e in report.get("blocking", [])))
            self.assertEqual((repo / "chart/templates/deployment.yaml").read_bytes(), body.encode("utf-8"))

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

    def test_replacing_a_workload_file_preserves_its_permission_bits(self) -> None:
        # commit() replaces an existing file via mkstemp() + os.replace() -- mkstemp()
        # always creates its temp file mode 0600, and os.replace() carries that mode over
        # verbatim unless the original mode is explicitly restored first.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            target = repo / "chart" / "templates" / "deployment.yaml"
            target.chmod(0o755)
            code, report = run_migration(repo, plan(repo), "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertEqual(target.stat().st_mode & 0o777, 0o755)

    def test_helm_values_with_set_syntax_characters_render(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo, helmValues={"SPECIAL_VALUE": "a,b={x}"})
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 0, report)

    def test_values_file_final_newline_style_is_preserved(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            values = repo / "chart/values.yaml"
            values.write_bytes(VALUES.rstrip("\n").encode("utf-8"))
            the_plan = plan(repo)
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report)
            self.assertFalse(values.read_bytes().endswith(b"\n"))

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
            # Two YAML documents: the first is spliced out by supersededDeclarations
            # (documentIndex: 1), the second survives -- this is a content
            # *modification* of an already-existing file (verify_superseded's
            # changes.set_content path), not a delete, matching what the real
            # commit() must do: mkstemp() a replacement in the file's own parent
            # directory. Lives directly under "chart/", not "chart/templates/",
            # and sorts after "templates" alphabetically ("t" < "z").
            declaration_text = (
                "kind: DatabaseDeclaration\n"
                "declarations:\n"
                "  - classifierConfig:\n"
                "      classifier: {microserviceName: orders, scope: service}\n"
                "    type: postgresql\n"
                "---\n"
                "apiVersion: v1\n"
                "kind: ConfigMap\n"
                "metadata:\n"
                "  name: unrelated\n"
            )
            declaration_path = repo / "chart/zz-dbaas-declaration.yaml"
            declaration_path.write_text(declaration_text, encoding="utf-8")
            the_plan = plan(repo)
            the_plan["roots"][0]["supersededDeclarations"] = [
                {"path": "zz-dbaas-declaration.yaml", "documentIndex": 1}
            ]
            the_plan["roots"][0]["sourceHashes"]["zz-dbaas-declaration.yaml"] = sha256(declaration_path)

            declaration_before = declaration_path.read_bytes()
            deployment_before = (repo / "chart/templates/deployment.yaml").read_bytes()
            output_before_exists = (repo / "chart/templates/dbaas-mounted-secret-resources.yaml").exists()
            # sorted(changes.files) writes both chart/templates/* entries before
            # chart/zz-dbaas-declaration.yaml (alphabetically "templates" < "zz-..."),
            # so making only "chart" itself (not "chart/templates") read-only lets
            # the two templates/* writes succeed first and fails replacing the
            # declaration -- proving the already-applied templates/* files get
            # rolled back.
            chart_dir = repo / "chart"
            mode = chart_dir.stat().st_mode
            try:
                os.chmod(chart_dir, 0o500)
                code, report = run_migration(repo, the_plan, "apply", tmp)
            finally:
                os.chmod(chart_dir, mode)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertEqual(declaration_path.read_bytes(), declaration_before)
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

    def test_workload_file_cannot_also_be_a_superseded_declaration_source(self) -> None:
        # apply_workload_patches() and verify_superseded() each write a new
        # version of any file they touch into the same shared Changes
        # object; if the same path is both a claim's workload file and a
        # superseded-declaration source, whichever runs second silently
        # discards the other's edit. Reject the overlap outright.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            the_plan = plan(repo)
            the_plan["roots"][0]["supersededDeclarations"] = [
                {"path": "templates/deployment.yaml", "documentIndex": None}
            ]
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 2, report.get("__stderr"))
            self.assertIn("also be a superseded declaration source", report["validation"][0]["details"])

    def test_unaddressed_document_that_also_matches_a_datasource_blocks(self) -> None:
        # A document the plan does not address but that itself fully
        # matches a migrated datasource is a live legacy declaration left
        # racing the generated resource -- block instead of leaving it.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            text = (
                "kind: DatabaseDeclaration\n"
                "declarations:\n"
                "  - classifierConfig:\n"
                "      classifier: {microserviceName: orders, scope: service}\n"
                "    type: postgresql\n"
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
                {"path": "templates/dbaas-declaration.yaml", "documentIndex": 1}
            ]
            the_plan["roots"][0]["sourceHashes"]["templates/dbaas-declaration.yaml"] = sha256(
                repo / "chart/templates/dbaas-declaration.yaml"
            )
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertIn(
                "shares a migrated datasource's classifier/type identity", report["validation"][0]["details"]
            )
            self.assertTrue((repo / "chart/templates/dbaas-declaration.yaml").exists())

    def test_unaddressed_document_with_same_identity_but_different_settings_blocks(self) -> None:
        # (classifier, type) identity alone is what a running deployment uses to decide
        # "is this the same database" -- a settings difference does not make an
        # unaddressed document unrelated to the migrated datasource, it only decides
        # which one wins the race. This must block just like a fully-matching document.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            text = (
                "kind: DatabaseDeclaration\n"
                "declarations:\n"
                "  - classifierConfig:\n"
                "      classifier: {microserviceName: orders, scope: service}\n"
                "    type: postgresql\n"
                "---\n"
                "kind: DatabaseDeclaration\n"
                "declarations:\n"
                "  - classifierConfig:\n"
                "      classifier: {microserviceName: orders, scope: service}\n"
                "    type: postgresql\n"
                "    settings: {maxPoolSize: 10}\n"
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
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertIn(
                "shares a migrated datasource's classifier/type identity", report["validation"][0]["details"]
            )
            self.assertTrue((repo / "chart/templates/dbaas-declaration.yaml").exists())

    def test_comment_only_document_does_not_alias_the_next_documents_span(self) -> None:
        # A comment-only document composes to a real (":null"-tagged) node,
        # not Python None -- left unfiltered, its marks fall inside the
        # range attributed to the *next* document, making documentIndex 1
        # and 2 compute the same byte span and removing neither.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            text = (
                "---\n"
                "# just a comment, no content\n"
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
            self.assertNotIn("DatabaseDeclaration", kept)
            self.assertIn("kind: ConfigMap", kept)

    def test_explicit_null_document_is_preserved_not_treated_as_phantom(self) -> None:
        # An explicit `null` document composes to the same ":null"-tagged node kind as a
        # comment-only/empty one, but it has real content and a real (non-zero-width)
        # byte span -- filtering on the tag alone would silently drop this document from
        # the list entirely, shifting every later document's index and letting its own
        # span (which the phantom-only filter never earns) be misattributed. It must
        # survive, byte-for-byte, as its own numbered document.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold(tmp)
            text = (
                "kind: DatabaseDeclaration\n"
                "declarations:\n"
                "  - classifierConfig:\n"
                "      classifier: {microserviceName: orders, scope: service}\n"
                "    type: postgresql\n"
                "---\n"
                "null\n"
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
            self.assertNotIn("DatabaseDeclaration", kept)
            self.assertIn("null\n", kept)
            self.assertIn("kind: ConfigMap", kept)

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


OPERATOR_NAMESPACE_FROM_API_DBAAS_ADDRESS = (
    '{{ (index (splitList "." (first (splitList ":" '
    '(last (splitList "://" $.Values.API_DBAAS_ADDRESS))))) 1) }}'
)


@unittest.skipUnless(shutil.which("helm"), "helm is not on PATH")
class OperatorNamespaceFromApiDbaasAddressTest(unittest.TestCase):
    """Issue #776 phase 5: render tests for the documented Helm expression that
    derives operatorNamespace from a namespaced Kubernetes service address in
    API_DBAAS_ADDRESS (see references/contracts.md)."""

    def render(self, tmp: Path, api_dbaas_address: str) -> subprocess.CompletedProcess[str]:
        chart = tmp / "probe-chart"
        (chart / "templates").mkdir(parents=True)
        (chart / "Chart.yaml").write_text("apiVersion: v2\nname: probe\nversion: 0.1.0\n", encoding="utf-8")
        (chart / "templates" / "resource.yaml").write_text(
            "apiVersion: dbaas.netcracker.com/v1\n"
            "kind: InternalDatabase\n"
            "metadata:\n"
            "  name: probe\n"
            "spec:\n"
            f"  operatorNamespace: {OPERATOR_NAMESPACE_FROM_API_DBAAS_ADDRESS}\n",
            encoding="utf-8",
        )
        values_file = tmp / "values.yaml"
        values_file.write_text(
            yaml.safe_dump({"API_DBAAS_ADDRESS": api_dbaas_address}), encoding="utf-8"
        )
        return subprocess.run(
            ["helm", "template", "probe-release", str(chart), "--values", str(values_file)],
            capture_output=True, text=True, check=False,
        )

    def test_short_cluster_service_address_produces_second_dns_label(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            proc = self.render(Path(directory), "http://dbaas-aggregator.dbaas:8080")
            self.assertEqual(proc.returncode, 0, proc.stderr)
            resource = yaml.safe_load(proc.stdout)
            self.assertEqual(resource["spec"]["operatorNamespace"], "dbaas")

    def test_longer_cluster_dns_name_produces_second_dns_label(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            proc = self.render(
                Path(directory), "http://dbaas-aggregator.dbaas-operator.svc.cluster.local:8080"
            )
            self.assertEqual(proc.returncode, 0, proc.stderr)
            resource = yaml.safe_load(proc.stdout)
            self.assertEqual(resource["spec"]["operatorNamespace"], "dbaas-operator")

    def test_empty_address_fails_to_render(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            proc = self.render(Path(directory), "")
            self.assertNotEqual(proc.returncode, 0)

    def test_single_label_address_fails_to_render(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            proc = self.render(Path(directory), "http://dbaas-aggregator:8080")
            self.assertNotEqual(proc.returncode, 0)


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

    def test_new_generated_files_use_normal_manifest_permissions(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold_plain(tmp)
            output = repo / "deploy/dbaas-mounted-secret-resources.yaml"
            code, report = run_migration(repo, plan(repo, root="deploy", kind="plain"), "apply", tmp)
            self.assertEqual(code, 0, report)
            self.assertEqual(output.stat().st_mode & 0o777, 0o644)

    def test_non_mapping_yaml_document_before_workload_is_ignored_safely(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold_plain(tmp, deployment="- harmless\n---\n" + PLAIN_DEPLOYMENT)
            code, report = run_migration(repo, plan(repo, root="deploy", kind="plain"), "check", tmp)
            self.assertEqual(code, 5, report)
            self.assertNotIn("internal", json.dumps(report))


class LoadPlanTest(unittest.TestCase):
    def test_falsey_unsupported_and_malformed_plan_values_block(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold_plain(tmp)
            cases = {
                "physicalDatabaseId": {
                    "datasources": [datasource(parameters={"physicalDatabaseId": 0})]
                },
                "numeric tenantId": {
                    "datasources": [datasource(classifier={"microserviceName": "orders", "scope": "service", "tenantId": 1})]
                },
                "invalid namespace": {"workloadNamespace": "BAD SPACE"},
                "non-hex digest": {"sourceHashes": {"deployment.yaml": "g" * 64}},
            }
            for label, overrides in cases.items():
                with self.subTest(label=label):
                    the_plan = plan(repo, root="deploy", kind="plain", **overrides)
                    code, report = run_migration(repo, the_plan, "check", tmp)
                    self.assertIn(code, (2, 4), report)
                    self.assertNotIn("internal", json.dumps(report))

    def test_boolean_document_index_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold_plain(tmp)
            declaration = repo / "deploy/declaration.yaml"
            declaration.write_text("kind: DatabaseDeclaration\ndeclarations: []\n", encoding="utf-8")
            the_plan = plan(
                repo,
                root="deploy",
                kind="plain",
                supersededDeclarations=[{"path": "declaration.yaml", "documentIndex": True}],
                sourceHashes=source_hashes(repo, "deploy", "deployment.yaml", "declaration.yaml"),
            )
            code, report = run_migration(repo, the_plan, "check", tmp)
            self.assertEqual(code, 2, report)

    def test_dbaas_wrapper_and_commented_separator_are_supported(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = scaffold_plain(tmp)
            declaration = repo / "deploy/declaration.yaml"
            declaration.write_text(
                "apiVersion: v1\nkind: ConfigMap\nmetadata: {name: keep}\n"
                "--- # legacy declaration\nkind: DBaaS\nsubKind: DatabaseDeclaration\nspec:\n"
                "  declarations:\n"
                "    - classifierConfig:\n"
                "        classifier: {microserviceName: orders, scope: service}\n"
                "      type: postgresql\n",
                encoding="utf-8",
            )
            the_plan = plan(
                repo,
                root="deploy",
                kind="plain",
                supersededDeclarations=[{"path": "declaration.yaml", "documentIndex": 2}],
                sourceHashes=source_hashes(repo, "deploy", "deployment.yaml", "declaration.yaml"),
            )
            code, report = run_migration(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report)
            remaining = declaration.read_text(encoding="utf-8")
            self.assertIn("ConfigMap", remaining)
            self.assertNotIn("DatabaseDeclaration", remaining)
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


class MaskForReadingTrailingCommentTest(unittest.TestCase):
    """Issue #776: _mask_for_reading (the verification-only quoting mask)
    must never capture a trailing YAML comment into the quoted value -- the
    same defect found and fixed in the declaration writer's identical,
    package-local _mask_for_value."""

    @classmethod
    def setUpClass(cls) -> None:
        if str(SCRIPTS) not in sys.path:
            sys.path.insert(0, str(SCRIPTS))
        import apply_migration as am  # noqa: PLC0415

        cls.am = am

    @unittest.skipIf(yaml is None, "PyYAML is required")
    def test_trailing_comment_after_unquoted_expression_is_not_captured(self) -> None:
        text = "name: {{ .Values.SERVICE_NAME }} # trailing comment\n"
        masked = self.am._mask_for_reading(text)
        loaded = yaml.safe_load(masked)
        self.assertEqual(loaded["name"], "{{ .Values.SERVICE_NAME }}")

    def test_split_trailing_comment_leaves_expression_only_value(self) -> None:
        value, comment = self.am._split_trailing_comment("{{ .Values.SERVICE_NAME }} # trailing comment")
        self.assertEqual(value, "{{ .Values.SERVICE_NAME }}")
        self.assertEqual(comment, "# trailing comment")

    def test_split_trailing_comment_ignores_hash_with_no_preceding_whitespace(self) -> None:
        # A "#" glued directly onto the expression's own closing "}}" (no
        # whitespace before it) is not a YAML comment marker at all here.
        value, comment = self.am._split_trailing_comment("{{ .Values.SERVICE_NAME }}#not-a-comment")
        self.assertEqual(value, "{{ .Values.SERVICE_NAME }}#not-a-comment")
        self.assertEqual(comment, "")

    @unittest.skipIf(yaml is None, "PyYAML is required")
    def test_quoted_expression_preserves_literal_hash_and_external_comment(self) -> None:
        cases = {
            'name: "{{ .Values.SERVICE_NAME }} # literal"\n': "{{ .Values.SERVICE_NAME }} # literal",
            "name: '{{ .Values.SERVICE_NAME }} # literal'\n": "{{ .Values.SERVICE_NAME }} # literal",
            'name: "{{ .Values.SERVICE_NAME }}" # trailing comment\n': "{{ .Values.SERVICE_NAME }}",
        }
        for text, expected in cases.items():
            with self.subTest(text=text):
                masked = self.am._mask_for_reading(text)
                self.assertEqual(yaml.safe_load(masked)["name"], expected)


if __name__ == "__main__":
    unittest.main()
