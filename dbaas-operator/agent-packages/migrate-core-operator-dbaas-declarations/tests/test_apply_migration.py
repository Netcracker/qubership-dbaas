"""Tests for the package-local writer (scripts/apply_migration.py).

Separate from test_convert_dbaas_crs.py, which covers the pure conversion
functions in isolation: this file is about root/transaction/validation
concerns -- multi-root collision scoping, byte-preserving source splicing,
path safety, and the atomic write/rollback contract -- that only exist once
a plan and a real repository tree are involved.
"""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any

try:
    import yaml
except ImportError:  # pragma: no cover - optional test dependency
    yaml = None

PACKAGE_ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = PACKAGE_ROOT / ".apm" / "skills" / "migrate-core-operator-dbaas-declarations" / "scripts"
RUNNER = SCRIPTS / "apply_migration.py"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(repo: Path, plan: dict[str, Any], mode: str, tmp: Path) -> tuple[int, dict[str, Any]]:
    plan_path = tmp / f"plan-{mode}-{id(plan)}.json"
    plan_path.write_text(json.dumps(plan, indent=2), encoding="utf-8")
    proc = subprocess.run(
        [sys.executable, str(RUNNER), "--repo-root", str(repo), "--plan", str(plan_path), f"--{mode}"],
        capture_output=True, text=True, check=False,
    )
    try:
        report = json.loads(proc.stdout) if proc.stdout.strip() else {}
    except ValueError:
        report = {}
    report["__stderr"] = proc.stderr
    return proc.returncode, report


def write(repo: Path, rel: str, content: str) -> Path:
    path = repo / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path


def root_plan(repo: Path, root: str, source_rel: str, output_rel: str, **overrides: Any) -> dict[str, Any]:
    plan = {
        "schemaVersion": 1,
        "roots": [
            {
                "root": root,
                "kind": "plain",
                "operatorNamespace": "dbaas-system",
                "serviceName": "svc",
                "namespace": "deploy-ns",
                "outputFile": output_rel,
                "sources": [{"path": source_rel, "sha256": sha256(repo / source_rel)}],
            }
        ],
    }
    plan["roots"][0].update(overrides)
    return plan


DECLARATION = {
    "apiVersion": "nc.core.dbaas/v3",
    "kind": "DatabaseDeclaration",
    "declarations": [
        {
            "classifierConfig": {"classifier": {"microserviceName": "svc", "scope": "service"}},
            "type": "postgresql",
        }
    ],
}


class ApplyMigrationTest(unittest.TestCase):
    def test_apply_migrates_and_a_second_apply_against_the_same_plan_is_stale(self) -> None:
        # Core deletes a source once every declaration in it is migrated, so
        # (unlike a regenerate-in-place writer) a *literal* repeated apply
        # has nothing left to consume -- the correct, fail-closed behavior is
        # rejecting the second run as a stale source, not silently doing
        # nothing. check_source_hashes re-runs immediately before the real
        # write too, so a plan describing an already-consumed source can
        # never partially apply.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps(DECLARATION))
            plan = root_plan(repo, "deploy", "deploy/dbaas.json", "dbaas-operator-resources.yaml")

            code, report = run(repo, plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertEqual(report["status"], "changed")
            self.assertFalse((repo / "deploy/dbaas.json").exists())
            out = (repo / "deploy/dbaas-operator-resources.yaml").read_text(encoding="utf-8")
            first_bytes = (repo / "deploy/dbaas-operator-resources.yaml").read_bytes()
            self.assertIn("kind: InternalDatabase", out)
            self.assertIn("operatorNamespace: dbaas-system", out)

            code2, report2 = run(repo, plan, "apply", tmp)
            self.assertEqual(code2, 3, report2.get("__stderr"))
            self.assertEqual((repo / "deploy/dbaas-operator-resources.yaml").read_bytes(), first_bytes)

    def test_replacing_a_file_preserves_its_permission_bits(self) -> None:
        # commit() replaces an existing file via mkstemp() + os.replace() -- mkstemp()
        # always creates its temp file mode 0600, and os.replace() carries that mode
        # over verbatim unless the original mode is explicitly restored first. An
        # executable (0755) file silently losing its executable bit on every migration
        # run would be a real, if quiet, regression for anything that ships a hook script
        # alongside the declarations being migrated.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps(DECLARATION))
            output = write(repo, "deploy/dbaas-operator-resources.yaml", "placeholder: true\n")
            output.chmod(0o755)
            plan = root_plan(
                repo, "deploy", "deploy/dbaas.json", "dbaas-operator-resources.yaml",
                outputSha256=sha256(output),
            )
            code, report = run(repo, plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertEqual(output.stat().st_mode & 0o777, 0o755)

    def test_strict_boolean_and_unknown_field_rejection(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            bad = dict(DECLARATION)
            bad["declarations"] = [dict(DECLARATION["declarations"][0], lazy="maybe")]
            write(repo, "deploy/dbaas.json", json.dumps(bad))
            plan = root_plan(repo, "deploy", "deploy/dbaas.json", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertTrue(any("non-boolean lazy" in e for e in report.get("errors", [])))

    def test_preserved_declaration_fields_round_trip(self) -> None:
        declaration = {
            "apiVersion": "nc.core.dbaas/v3",
            "kind": "DatabaseDeclaration",
            "declarations": [
                {
                    "classifierConfig": {"classifier": {"microserviceName": "svc", "scope": "service"}},
                    "type": "postgresql",
                    "lazy": False,
                    "namePrefix": "svc",
                    "physicalDatabaseId": "pg-prod-a",
                    "versioningConfig": {"approach": "clone"},
                    "settings": {"pgExtensions": ["vector"], "nested": {"a": 1}},
                }
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps(declaration))
            plan = root_plan(repo, "deploy", "deploy/dbaas.json", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            if yaml is not None:
                doc = yaml.safe_load((repo / "deploy/dbaas-operator-resources.yaml").read_text(encoding="utf-8"))
                self.assertEqual(doc["spec"]["physicalDatabaseId"], "pg-prod-a")
                self.assertEqual(doc["spec"]["versioningConfig"], {"approach": "clone"})
                self.assertEqual(doc["spec"]["settings"]["pgExtensions"], ["vector"])
                self.assertIs(doc["spec"]["lazy"], False)

    def test_db_policy_preserves_its_own_microservice_name(self) -> None:
        # root_plan()'s default serviceName is "svc"; the policy's own
        # microserviceName ("inventory") must win over it, not be silently
        # overwritten -- a root covering policies for several microservices
        # must not rewrite every one of them to the same name.
        declaration = {
            "apiVersion": "nc.core.dbaas/v3",
            "kind": "DbPolicy",
            "microserviceName": "inventory",
            "services": [{"name": "cdc-streaming-platform", "roles": ["streaming"]}],
        }
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbpolicy.json", json.dumps(declaration))
            plan = root_plan(repo, "deploy", "deploy/dbpolicy.json", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            out = (repo / "deploy/dbaas-operator-resources.yaml").read_text(encoding="utf-8")
            self.assertIn("microserviceName: inventory", out)

    def test_lazy_true_with_clone_approach_blocks(self) -> None:
        declaration = {
            "apiVersion": "nc.core.dbaas/v3",
            "kind": "DatabaseDeclaration",
            "declarations": [
                {
                    "classifierConfig": {"classifier": {"microserviceName": "svc", "scope": "service"}},
                    "type": "postgresql",
                    "lazy": True,
                    "initialInstantiation": {
                        "approach": "clone",
                        "sourceClassifier": {"microserviceName": "svc", "scope": "service"},
                    },
                }
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps(declaration))
            plan = root_plan(repo, "deploy", "deploy/dbaas.json", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertTrue(
                any("lazy=true with initialInstantiation.approach=clone is invalid" in e for e in report.get("errors", []))
            )
            self.assertTrue((repo / "deploy/dbaas.json").exists())

    def test_cross_service_source_classifier_blocks(self) -> None:
        declaration = {
            "apiVersion": "nc.core.dbaas/v3",
            "kind": "DatabaseDeclaration",
            "declarations": [
                {
                    "classifierConfig": {"classifier": {"microserviceName": "svc", "scope": "service"}},
                    "type": "postgresql",
                    "initialInstantiation": {
                        "approach": "clone",
                        "sourceClassifier": {"microserviceName": "other-svc", "scope": "service"},
                    },
                }
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps(declaration))
            plan = root_plan(repo, "deploy", "deploy/dbaas.json", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertTrue(any("cross-service clones are invalid" in e for e in report.get("errors", [])))
            self.assertTrue((repo / "deploy/dbaas.json").exists())

    def test_classifier_namespace_pin_differing_from_target_namespace_blocks(self) -> None:
        # The operator always derives spec.classifier.namespace from metadata.namespace; a
        # legacy classifier pinned to a different namespace can never be honored, and a
        # warning here would not stop --apply from deleting the source. Must block.
        declaration = {
            "apiVersion": "nc.core.dbaas/v3",
            "kind": "DatabaseDeclaration",
            "declarations": [
                {
                    "classifierConfig": {
                        "classifier": {"microserviceName": "svc", "scope": "service", "namespace": "other-ns"}
                    },
                    "type": "postgresql",
                }
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps(declaration))
            plan = root_plan(repo, "deploy", "deploy/dbaas.json", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertTrue(any("classifier.namespace" in e and "differs from" in e for e in report.get("errors", [])))
            self.assertTrue((repo / "deploy/dbaas.json").exists())

    def test_clone_without_source_classifier_blocks(self) -> None:
        # Without a sourceClassifier the operator has nothing to clone from -- invalid by
        # the converter's own judgment, not merely worth a second look, so a warning here
        # would not stop --apply from deleting the source.
        declaration = {
            "apiVersion": "nc.core.dbaas/v3",
            "kind": "DatabaseDeclaration",
            "declarations": [
                {
                    "classifierConfig": {"classifier": {"microserviceName": "svc", "scope": "service"}},
                    "type": "postgresql",
                    "initialInstantiation": {"approach": "clone"},
                }
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps(declaration))
            plan = root_plan(repo, "deploy", "deploy/dbaas.json", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertTrue(
                any("initialInstantiation.approach=clone requires sourceClassifier" in e for e in report.get("errors", []))
            )
            self.assertTrue((repo / "deploy/dbaas.json").exists())

    def test_source_equals_output_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps(DECLARATION))
            plan = root_plan(repo, "deploy", "deploy/dbaas.json", "dbaas.json")
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 2, report.get("__stderr"))
            self.assertTrue(any("also a migration source" in e for e in report.get("errors", [])))

    def test_two_roots_reuse_the_same_resource_name_without_colliding(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "chartA/dbaas.json", json.dumps(DECLARATION))
            write(repo, "chartB/dbaas.json", json.dumps(DECLARATION))
            plan = {
                "schemaVersion": 1,
                "roots": [
                    {
                        "root": "chartA", "kind": "plain", "operatorNamespace": "dbaas-system",
                        "serviceName": "svc", "namespace": "ns-a",
                        "outputFile": "dbaas-operator-resources.yaml",
                        "sources": [{"path": "chartA/dbaas.json", "sha256": sha256(repo / "chartA/dbaas.json")}],
                    },
                    {
                        "root": "chartB", "kind": "plain", "operatorNamespace": "dbaas-system",
                        "serviceName": "svc", "namespace": "ns-b",
                        "outputFile": "dbaas-operator-resources.yaml",
                        "sources": [{"path": "chartB/dbaas.json", "sha256": sha256(repo / "chartB/dbaas.json")}],
                    },
                ],
            }
            code, report = run(repo, plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            a = (repo / "chartA/dbaas-operator-resources.yaml").read_text(encoding="utf-8")
            b = (repo / "chartB/dbaas-operator-resources.yaml").read_text(encoding="utf-8")
            self.assertIn("name: service-postgresql-db", a)
            self.assertIn("name: service-postgresql-db", b)

    def test_mixed_yaml_document_is_preserved_byte_for_byte(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            unrelated = "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: keep-me\ndata:\n  x: '1'\n"
            declaration_yaml = (
                "apiVersion: nc.core.dbaas/v3\nkind: DatabaseDeclaration\ndeclarations:\n"
                "  - classifierConfig:\n      classifier:\n        microserviceName: svc\n"
                "        scope: service\n    type: postgresql\n"
            )
            write(repo, "deploy/mixed.yaml", f"{unrelated}---\n{declaration_yaml}")
            plan = root_plan(repo, "deploy", "deploy/mixed.yaml", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            remaining = (repo / "deploy/mixed.yaml").read_text(encoding="utf-8")
            self.assertEqual(remaining, unrelated)

    def test_explicit_null_document_is_preserved_not_treated_as_phantom(self) -> None:
        # An explicit `null` document composes to the same ":null"-tagged node kind as a
        # comment-only/empty one, but it has real content and a real (non-zero-width)
        # byte span -- filtering on the tag alone would silently drop it from the
        # document list, shifting every later document's span attribution. It must
        # survive byte-for-byte, distinct from the migrated declaration.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            declaration_yaml = (
                "apiVersion: nc.core.dbaas/v3\nkind: DatabaseDeclaration\ndeclarations:\n"
                "  - classifierConfig:\n      classifier:\n        microserviceName: svc\n"
                "        scope: service\n    type: postgresql\n"
            )
            write(repo, "deploy/mixed.yaml", f"{declaration_yaml}---\nnull\n")
            plan = root_plan(repo, "deploy", "deploy/mixed.yaml", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            remaining = (repo / "deploy/mixed.yaml").read_text(encoding="utf-8")
            self.assertEqual(remaining, "---\nnull\n")

    def test_yaml_preamble_before_first_document_marker_is_preserved(self) -> None:
        # A comment before the *first* document's own leading "---" belongs to
        # no document at all; it must survive even though that first document
        # (unrelated to the migration) is the one being retained.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            unrelated = "---\napiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: keep-me\n"
            declaration_yaml = (
                "apiVersion: nc.core.dbaas/v3\nkind: DatabaseDeclaration\ndeclarations:\n"
                "  - classifierConfig:\n      classifier:\n        microserviceName: svc\n"
                "        scope: service\n    type: postgresql\n"
            )
            write(repo, "deploy/mixed.yaml", f"# preamble must survive\n{unrelated}---\n{declaration_yaml}")
            plan = root_plan(repo, "deploy", "deploy/mixed.yaml", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            remaining = (repo / "deploy/mixed.yaml").read_text(encoding="utf-8")
            self.assertTrue(remaining.startswith("# preamble must survive"))
            self.assertIn("kind: ConfigMap", remaining)
            self.assertNotIn("DatabaseDeclaration", remaining)

    def test_yaml_preamble_survives_when_first_document_is_the_one_migrated(self) -> None:
        # Same as above, but the migrated (removed) document comes FIRST and
        # the retained ConfigMap comes second -- the preamble is tracked
        # independently of the first document's own span, so it must survive
        # regardless of which document the first one turns out to be.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            declaration_yaml = (
                "apiVersion: nc.core.dbaas/v3\nkind: DatabaseDeclaration\ndeclarations:\n"
                "  - classifierConfig:\n      classifier:\n        microserviceName: svc\n"
                "        scope: service\n    type: postgresql\n"
            )
            unrelated = "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: keep-me\n"
            write(repo, "deploy/mixed.yaml", f"# preamble must survive\n---\n{declaration_yaml}---\n{unrelated}")
            plan = root_plan(repo, "deploy", "deploy/mixed.yaml", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            remaining = (repo / "deploy/mixed.yaml").read_text(encoding="utf-8")
            self.assertTrue(remaining.startswith("# preamble must survive"))
            self.assertIn("kind: ConfigMap", remaining)
            self.assertNotIn("DatabaseDeclaration", remaining)

    def test_declaration_with_ordinary_chart_labels_is_not_blocked(self) -> None:
        # An ordinary chart-managed source -- every Helm-templated legacy
        # declaration carries boilerplate labels like these -- must migrate
        # normally. labels/annotations are ordinary Kubernetes metadata with
        # no converter-owned mapping, so they carry forward onto the
        # generated CR verbatim rather than being dropped or blocking.
        declaration = {
            "apiVersion": "nc.core.dbaas/v3",
            "kind": "DatabaseDeclaration",
            "metadata": {
                "name": "dbaas-declaration",
                "labels": {"app.kubernetes.io/name": "svc", "helm.sh/chart": "svc-1.0.0"},
                "annotations": {"meta.helm.sh/release-name": "svc"},
            },
            "declarations": [
                {
                    "classifierConfig": {"classifier": {"microserviceName": "svc", "scope": "service"}},
                    "type": "postgresql",
                }
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps(declaration))
            plan = root_plan(repo, "deploy", "deploy/dbaas.json", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertFalse((repo / "deploy/dbaas.json").exists())
            if yaml is not None:
                doc = yaml.safe_load((repo / "deploy/dbaas-operator-resources.yaml").read_text(encoding="utf-8"))
                self.assertEqual(
                    doc["metadata"]["labels"], {"app.kubernetes.io/name": "svc", "helm.sh/chart": "svc-1.0.0"}
                )
                self.assertEqual(doc["metadata"]["annotations"], {"meta.helm.sh/release-name": "svc"})

    def test_declaration_with_non_object_labels_blocks(self) -> None:
        # reject_dropped_metadata() treats "labels" as always-preserved by name alone and
        # never flags it as dropped -- a malformed (non-object) value must still be caught,
        # in target_metadata() itself, or it would be silently omitted with no error at all.
        declaration = {
            "apiVersion": "nc.core.dbaas/v3",
            "kind": "DatabaseDeclaration",
            "metadata": {"name": "dbaas-declaration", "labels": "not-an-object"},
            "declarations": [
                {
                    "classifierConfig": {"classifier": {"microserviceName": "svc", "scope": "service"}},
                    "type": "postgresql",
                }
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps(declaration))
            plan = root_plan(repo, "deploy", "deploy/dbaas.json", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertTrue(any("metadata.labels must be an object" in e for e in report.get("errors", [])))
            self.assertTrue((repo / "deploy/dbaas.json").exists())

    def test_declaration_with_genuinely_unknown_metadata_field_still_blocks(self) -> None:
        # A metadata field this converter does not recognize at all -- not
        # labels/annotations, which are known-and-intentionally-unmapped --
        # must still block instead of silently vanishing.
        declaration = {
            "apiVersion": "nc.core.dbaas/v3",
            "kind": "DatabaseDeclaration",
            "metadata": {"name": "dbaas-declaration", "generateName": "dbaas-declaration-"},
            "declarations": [
                {
                    "classifierConfig": {"classifier": {"microserviceName": "svc", "scope": "service"}},
                    "type": "postgresql",
                }
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps(declaration))
            plan = root_plan(repo, "deploy", "deploy/dbaas.json", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertTrue(any("would be dropped" in e for e in report.get("errors", [])))
            self.assertTrue((repo / "deploy/dbaas.json").exists())

    def test_owner_references_and_finalizers_block(self) -> None:
        # Kubernetes does not automatically recreate arbitrary owner references or
        # finalizers on a differently-named/differently-kinded replacement resource --
        # unlike the truly API-server-regenerated fields, these must block, not vanish.
        declaration = {
            "apiVersion": "nc.core.dbaas/v3",
            "kind": "DatabaseDeclaration",
            "metadata": {
                "name": "dbaas-declaration",
                "ownerReferences": [{"apiVersion": "v1", "kind": "ConfigMap", "name": "parent", "uid": "abc"}],
                "finalizers": ["example.com/protect"],
            },
            "declarations": [
                {
                    "classifierConfig": {"classifier": {"microserviceName": "svc", "scope": "service"}},
                    "type": "postgresql",
                }
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps(declaration))
            plan = root_plan(repo, "deploy", "deploy/dbaas.json", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            joined = " ".join(report.get("errors", []))
            self.assertIn("would be dropped", joined)
            self.assertIn("ownerReferences", joined)
            self.assertIn("finalizers", joined)
            self.assertTrue((repo / "deploy/dbaas.json").exists())

    def test_numeric_metadata_namespace_blocks(self) -> None:
        declaration = {
            "apiVersion": "nc.core.dbaas/v3",
            "kind": "DatabaseDeclaration",
            "metadata": {"name": "dbaas-declaration", "namespace": 12345},
            "declarations": [
                {
                    "classifierConfig": {"classifier": {"microserviceName": "svc", "scope": "service"}},
                    "type": "postgresql",
                }
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps(declaration))
            plan = root_plan(repo, "deploy", "deploy/dbaas.json", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertTrue(any("metadata.namespace must be a string" in e for e in report.get("errors", [])))
            self.assertTrue((repo / "deploy/dbaas.json").exists())

    def test_numeric_label_value_blocks(self) -> None:
        declaration = {
            "apiVersion": "nc.core.dbaas/v3",
            "kind": "DatabaseDeclaration",
            "metadata": {"name": "dbaas-declaration", "labels": {"app.kubernetes.io/version": 5}},
            "declarations": [
                {
                    "classifierConfig": {"classifier": {"microserviceName": "svc", "scope": "service"}},
                    "type": "postgresql",
                }
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps(declaration))
            plan = root_plan(repo, "deploy", "deploy/dbaas.json", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertTrue(any("metadata.labels values must be strings" in e for e in report.get("errors", [])))
            self.assertTrue((repo / "deploy/dbaas.json").exists())

    def test_list_valued_annotation_blocks(self) -> None:
        declaration = {
            "apiVersion": "nc.core.dbaas/v3",
            "kind": "DatabaseDeclaration",
            "metadata": {"name": "dbaas-declaration", "annotations": {"example.com/list": ["a", "b"]}},
            "declarations": [
                {
                    "classifierConfig": {"classifier": {"microserviceName": "svc", "scope": "service"}},
                    "type": "postgresql",
                }
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps(declaration))
            plan = root_plan(repo, "deploy", "deploy/dbaas.json", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertTrue(any("metadata.annotations values must be strings" in e for e in report.get("errors", [])))
            self.assertTrue((repo / "deploy/dbaas.json").exists())

    def test_numeric_classifier_field_blocks(self) -> None:
        declaration = {
            "apiVersion": "nc.core.dbaas/v3",
            "kind": "DatabaseDeclaration",
            "declarations": [
                {
                    "classifierConfig": {"classifier": {"microserviceName": "svc", "scope": 42}},
                    "type": "postgresql",
                }
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps(declaration))
            plan = root_plan(repo, "deploy", "deploy/dbaas.json", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertTrue(any("classifier.scope must be a string" in e for e in report.get("errors", [])))
            self.assertTrue((repo / "deploy/dbaas.json").exists())

    def test_numeric_database_type_blocks(self) -> None:
        declaration = {
            "apiVersion": "nc.core.dbaas/v3",
            "kind": "DatabaseDeclaration",
            "declarations": [
                {
                    "classifierConfig": {"classifier": {"microserviceName": "svc", "scope": "service"}},
                    "type": 5,
                }
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps(declaration))
            plan = root_plan(repo, "deploy", "deploy/dbaas.json", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertTrue(any(".type must be a string" in e for e in report.get("errors", [])))
            self.assertTrue((repo / "deploy/dbaas.json").exists())

    def test_list_valued_name_prefix_blocks(self) -> None:
        declaration = {
            "apiVersion": "nc.core.dbaas/v3",
            "kind": "DatabaseDeclaration",
            "declarations": [
                {
                    "classifierConfig": {"classifier": {"microserviceName": "svc", "scope": "service"}},
                    "type": "postgresql",
                    "namePrefix": [],
                }
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps(declaration))
            plan = root_plan(repo, "deploy", "deploy/dbaas.json", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertTrue(any(".namePrefix must be a string" in e for e in report.get("errors", [])))
            self.assertTrue((repo / "deploy/dbaas.json").exists())

    def test_duplicate_root_declaration_is_rejected(self) -> None:
        # Two plan entries for the same physical root would each be validated (and
        # rendered) independently -- neither entry's duplicate-object check would ever
        # see the other's generated resources, even though both land in the same chart
        # once applied.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas-a.json", json.dumps(DECLARATION))
            write(repo, "deploy/dbaas-b.json", json.dumps(DECLARATION))
            plan = {
                "schemaVersion": 1,
                "roots": [
                    {
                        "root": "deploy", "kind": "plain", "operatorNamespace": "dbaas-system",
                        "serviceName": "svc", "namespace": "ns",
                        "outputFile": "resources-a.yaml",
                        "sources": [{"path": "deploy/dbaas-a.json", "sha256": sha256(repo / "deploy/dbaas-a.json")}],
                    },
                    {
                        "root": "deploy", "kind": "plain", "operatorNamespace": "dbaas-system",
                        "serviceName": "svc", "namespace": "ns",
                        "outputFile": "resources-b.yaml",
                        "sources": [{"path": "deploy/dbaas-b.json", "sha256": sha256(repo / "deploy/dbaas-b.json")}],
                    },
                ],
            }
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 2, report.get("__stderr"))
            self.assertTrue(any("declared more than once" in e for e in report.get("errors", [])))

    def test_unc_style_source_path_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps(DECLARATION))
            plan = {
                "schemaVersion": 1,
                "roots": [
                    {
                        "root": "deploy", "kind": "plain", "operatorNamespace": "dbaas-system",
                        "serviceName": "svc", "namespace": "ns",
                        "outputFile": "dbaas-operator-resources.yaml",
                        "sources": [{"path": "\\\\server\\share\\dbaas.json", "sha256": sha256(repo / "deploy/dbaas.json")}],
                    }
                ],
            }
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 2, report.get("__stderr"))
            self.assertTrue(any("repository-relative" in e for e in report.get("errors", [])))

    @unittest.skipUnless(shutil.which("helm"), "helm is not on PATH")
    def test_whole_document_helm_guard_is_preserved_around_generated_output(self) -> None:
        # A Helm guard only makes sense in a Helm template context; a plain
        # root correctly rejects any "{{" in its generated output, so this
        # must declare a real helm root (with a Chart.yaml to render), not
        # root_plan()'s "plain" default.
        body = (
            "{{- if .Values.enabled }}\n"
            "apiVersion: nc.core.dbaas/v3\nkind: DatabaseDeclaration\ndeclarations:\n"
            "  - classifierConfig:\n      classifier:\n        microserviceName: svc\n"
            "        scope: service\n    type: postgresql\n"
            "{{- end }}\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            (repo / "chart/templates").mkdir(parents=True)
            (repo / "chart/Chart.yaml").write_text("apiVersion: v2\nname: svc\nversion: 0.1.0\n", encoding="utf-8")
            write(repo, "chart/templates/dbaas.yaml", f"---\n{body}")
            plan = root_plan(
                repo, "chart", "chart/templates/dbaas.yaml", "templates/dbaas-operator-resources.yaml",
                kind="helm", namespace="{{ .Values.NAMESPACE }}",
            )
            code, report = run(repo, plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            out = (repo / "chart/templates/dbaas-operator-resources.yaml").read_text(encoding="utf-8")
            self.assertIn("{{- if .Values.enabled }}", out)
            self.assertIn("{{- end }}", out)

    def test_partial_helm_guard_is_rejected(self) -> None:
        body = "{{- if .Values.enabled }}\napiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: x\n"
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "chart/mixed.yaml", f"---\n{body}")
            plan = root_plan(repo, "chart", "chart/mixed.yaml", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))

    def test_mixed_json_array_blocks_instead_of_partial_rewrite(self) -> None:
        payload = [dict(DECLARATION), {"apiVersion": "v1", "kind": "ConfigMap", "metadata": {"name": "x"}}]
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/mixed.json", json.dumps(payload))
            plan = root_plan(repo, "deploy", "deploy/mixed.json", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertTrue(any("mixes supported and unsupported" in e for e in report.get("errors", [])))

    def test_classifier_extra_keys_collision_is_rejected(self) -> None:
        declaration = {
            "apiVersion": "nc.core.dbaas/v3",
            "kind": "DatabaseDeclaration",
            "declarations": [
                {
                    "classifierConfig": {
                        "classifier": {"microserviceName": "svc", "scope": "service", "extraKeys": {"x": 1}}
                    },
                    "type": "postgresql",
                }
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps(declaration))
            plan = root_plan(repo, "deploy", "deploy/dbaas.json", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertTrue(any("literal 'extraKeys'" in e for e in report.get("errors", [])))

    def _templated_scope_declaration(self) -> dict[str, Any]:
        return {
            "apiVersion": "nc.core.dbaas/v3",
            "kind": "DatabaseDeclaration",
            "declarations": [
                {
                    "classifierConfig": {
                        "classifier": {"microserviceName": "svc", "scope": "{{ .Values.SCOPE }}"}
                    },
                    "type": "postgresql",
                }
            ],
        }

    def test_templated_scope_requires_explicit_name_override(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps(self._templated_scope_declaration()))
            plan = root_plan(repo, "deploy", "deploy/dbaas.json", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))
            self.assertTrue(any("nameOverrides" in e for e in report.get("errors", [])))

    @unittest.skipUnless(shutil.which("helm"), "helm is not on PATH")
    def test_release_specific_override_passes_helm_validation(self) -> None:
        # A whole-template default (auto-derived) is rejected, but a
        # deliberately-constructed override may mix a live Helm expression
        # with literal text -- its safety is the render-time DNS-1123 check,
        # not a static shape restriction. Only meaningful on a helm root: a
        # plain root's output must never contain a Helm expression at all.
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            (repo / "chart").mkdir(parents=True)
            (repo / "chart/Chart.yaml").write_text("apiVersion: v2\nname: svc\nversion: 0.1.0\n", encoding="utf-8")
            write(repo, "chart/dbaas.json", json.dumps(self._templated_scope_declaration()))
            plan = root_plan(
                repo, "chart", "chart/dbaas.json", "dbaas-operator-resources.yaml",
                kind="helm", namespace="{{ .Values.NAMESPACE }}",
                nameOverrides={"chart/dbaas.json#1#1#1": "{{ trunc 20 .Release.Name }}-svc-db"},
            )
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))

    def test_bom_and_crlf_source_preserved_when_nothing_migrates(self) -> None:
        body = "apiVersion: v1\r\nkind: ConfigMap\r\nmetadata:\r\n  name: keep\r\n"
        original = b"\xef\xbb\xbf" + body.encode("utf-8")
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            (repo / "deploy").mkdir(parents=True)
            (repo / "deploy/other.yaml").write_bytes(original)
            plan = root_plan(repo, "deploy", "deploy/other.yaml", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 4, report.get("__stderr"))  # no supported declarations at all
            self.assertEqual((repo / "deploy/other.yaml").read_bytes(), original)

    def test_bom_and_crlf_preserved_in_a_partial_migration(self) -> None:
        # Unlike the "nothing migrates" case above, this file has one
        # document that migrates and one that is retained -- exercising the
        # actual byte-span rewrite path, where a BOM/CRLF regression
        # wouldn't be caught by a source that is simply left untouched.
        unrelated = "apiVersion: v1\r\nkind: ConfigMap\r\nmetadata:\r\n  name: keep-me\r\n"
        declaration_yaml = (
            "apiVersion: nc.core.dbaas/v3\r\nkind: DatabaseDeclaration\r\ndeclarations:\r\n"
            "  - classifierConfig:\r\n      classifier:\r\n        microserviceName: svc\r\n"
            "        scope: service\r\n    type: postgresql\r\n"
        )
        raw = b"\xef\xbb\xbf" + f"{unrelated}---\r\n{declaration_yaml}".encode("utf-8")
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            (repo / "deploy").mkdir(parents=True)
            (repo / "deploy/mixed.yaml").write_bytes(raw)
            plan = root_plan(repo, "deploy", "deploy/mixed.yaml", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            remaining = (repo / "deploy/mixed.yaml").read_bytes()
            self.assertTrue(remaining.startswith(b"\xef\xbb\xbf"))
            self.assertIn(b"\r\n", remaining)
            self.assertNotIn(b"DatabaseDeclaration", remaining)

    def test_root_dot_and_nested_root(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "dbaas.json", json.dumps(DECLARATION))
            plan = root_plan(repo, ".", "dbaas.json", "dbaas-operator-resources.yaml")
            code, report = run(repo, plan, "apply", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))
            self.assertTrue((repo / "dbaas-operator-resources.yaml").exists())

    def test_stale_source_hash_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps(DECLARATION))
            plan = root_plan(repo, "deploy", "deploy/dbaas.json", "dbaas-operator-resources.yaml")
            write(repo, "deploy/dbaas.json", json.dumps(DECLARATION) + " ")
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 3, report.get("__stderr"))

    def test_path_alias_is_rejected_as_duplicate_source(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps(DECLARATION))
            plan = {
                "schemaVersion": 1,
                "roots": [
                    {
                        "root": "deploy", "kind": "plain", "operatorNamespace": "dbaas-system",
                        "serviceName": "svc", "namespace": "ns",
                        "outputFile": "dbaas-operator-resources.yaml",
                        "sources": [
                            {"path": "deploy/dbaas.json", "sha256": sha256(repo / "deploy/dbaas.json")},
                            {"path": "deploy/./dbaas.json", "sha256": sha256(repo / "deploy/dbaas.json")},
                        ],
                    }
                ],
            }
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 2, report.get("__stderr"))

    @unittest.skipUnless(shutil.which("helm"), "helm is not on PATH")
    def test_helm_root_renders_and_validates(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            (repo / "chart/templates").mkdir(parents=True)
            (repo / "chart/Chart.yaml").write_text("apiVersion: v2\nname: svc\nversion: 0.1.0\n", encoding="utf-8")
            write(repo, "chart/templates/dbaas.json", json.dumps(DECLARATION))
            plan = root_plan(
                repo, "chart", "chart/templates/dbaas.json", "templates/dbaas-operator-resources.yaml",
                kind="helm", namespace="{{ .Values.NAMESPACE }}",
                operatorNamespace='{{ index (splitList "." (first (splitList ":" (last (splitList "://" .Values.API_DBAAS_ADDRESS))))) 1 }}',
            )
            code, report = run(repo, plan, "check", tmp)
            self.assertEqual(code, 0, report.get("__stderr"))

    def test_missing_helm_is_exit_4_not_a_validation_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            (repo / "chart").mkdir(parents=True)
            (repo / "chart/Chart.yaml").write_text("apiVersion: v2\nname: svc\nversion: 0.1.0\n", encoding="utf-8")
            write(repo, "chart/dbaas.json", json.dumps(DECLARATION))
            plan = root_plan(
                repo, "chart", "chart/dbaas.json", "dbaas-operator-resources.yaml",
                kind="helm", namespace="{{ .Values.NAMESPACE }}",
            )
            plan_path = tmp / "plan.json"
            plan_path.write_text(json.dumps(plan), encoding="utf-8")
            env_path = "" if sys.platform != "win32" else "C:\\Windows"
            proc = subprocess.run(
                [sys.executable, str(RUNNER), "--repo-root", str(repo), "--plan", str(plan_path), "--check"],
                capture_output=True, text=True, check=False,
                env={**__import__("os").environ, "PATH": env_path},
            )
            self.assertEqual(proc.returncode, 4, proc.stderr)

    def test_invalid_command_line_still_emits_the_json_envelope(self) -> None:
        # argparse's own error path (missing/unknown args) calls sys.exit(2)
        # directly with a plain-text usage message -- bypassing the "one
        # JSON result envelope" contract that every other exit path honors.
        proc = subprocess.run(
            [sys.executable, str(RUNNER), "--repo-root", "somewhere"],  # --plan and mode omitted
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(proc.returncode, 2, proc.stderr)
        report = json.loads(proc.stdout)
        self.assertEqual(report["status"], "blocked")

    def test_generated_crd_shape_is_validated_fail_closed(self) -> None:
        base = DECLARATION["declarations"][0]
        cases = {
            "numeric physicalDatabaseId": dict(base, physicalDatabaseId=0),
            "list initialInstantiation": dict(base, initialInstantiation=[]),
            "numeric clone approach": dict(base, initialInstantiation={"approach": 1}),
            "incomplete source classifier": dict(
                base,
                initialInstantiation={
                    "approach": "clone", "sourceClassifier": {"microserviceName": "svc"}
                },
            ),
            "list versioningConfig": dict(base, versioningConfig=[]),
            "list customKeys": {
                **base,
                "classifierConfig": {
                    "classifier": {"microserviceName": "svc", "scope": "service", "customKeys": []}
                },
            },
        }
        for label, declaration in cases.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                tmp = Path(directory)
                repo = tmp / "repo"
                payload = {"kind": "DatabaseDeclaration", "declarations": [declaration]}
                write(repo, "deploy/dbaas.json", json.dumps(payload))
                the_plan = root_plan(repo, "deploy", "deploy/dbaas.json", "resources.yaml")
                code, report = run(repo, the_plan, "check", tmp)
                self.assertEqual(code, 4, report)
                self.assertNotIn("internal error", json.dumps(report))

    def test_arbitrary_classifier_json_value_is_preserved(self) -> None:
        declaration = {
            **DECLARATION["declarations"][0],
            "classifierConfig": {
                "classifier": {"microserviceName": "svc", "scope": "service", "shard": 3}
            },
        }
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps({"kind": "DatabaseDeclaration", "declarations": [declaration]}))
            the_plan = root_plan(repo, "deploy", "deploy/dbaas.json", "resources.yaml")
            code, report = run(repo, the_plan, "check", tmp)
            self.assertEqual(code, 0, report)
            self.assertIn("createdFiles", report)
            self.assertIn("deletedFiles", report)

    def test_top_level_items_have_distinct_override_keys(self) -> None:
        item = {**DECLARATION, "metadata": {"name": "{{ .Values.DB_NAME }}"}}
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "chart/dbaas.yaml", yaml.safe_dump([item, item], sort_keys=False))
            the_plan = root_plan(
                repo, "chart", "chart/dbaas.yaml", "resources.yaml",
                nameOverrides={
                    "chart/dbaas.yaml#1#1#1": "first-db",
                    "chart/dbaas.yaml#1#2#1": "second-db",
                },
            )
            code, report = run(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report)
            output = (repo / "chart/resources.yaml").read_text(encoding="utf-8")
            self.assertIn("name: first-db", output)
            self.assertIn("name: second-db", output)

    def test_commented_separator_and_guard_trivia_are_preserved(self) -> None:
        body = yaml.safe_dump(DECLARATION, sort_keys=False)
        source = "--- # declaration\n# guard comment\n{{- if .Values.DB_ENABLED }}\n" + body + "{{- end }}\n\n"
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            (repo / "chart/templates").mkdir(parents=True)
            (repo / "chart/Chart.yaml").write_text(
                "apiVersion: v2\nname: svc\nversion: 0.1.0\n", encoding="utf-8"
            )
            write(repo, "chart/templates/dbaas.yaml", source)
            the_plan = root_plan(
                repo, "chart", "chart/templates/dbaas.yaml", "templates/resources.yaml",
                kind="helm", namespace="{{ .Values.NAMESPACE }}",
            )
            code, report = run(repo, the_plan, "apply", tmp)
            self.assertEqual(code, 0, report)
            output = (repo / "chart/templates/resources.yaml").read_text(encoding="utf-8")
            self.assertIn("# guard comment\n{{- if .Values.DB_ENABLED }}", output)
            self.assertTrue(output.endswith("{{- end }}\n\n"))

    def test_plan_rejects_non_hex_hash_and_non_string_root(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            repo = tmp / "repo"
            write(repo, "deploy/dbaas.json", json.dumps(DECLARATION))
            for label, mutation in (("hash", "g" * 64), ("root", [])):
                with self.subTest(label=label):
                    the_plan = root_plan(repo, "deploy", "deploy/dbaas.json", "resources.yaml")
                    if label == "hash":
                        the_plan["roots"][0]["sources"][0]["sha256"] = mutation
                    else:
                        the_plan["roots"][0]["root"] = mutation
                    code, report = run(repo, the_plan, "check", tmp)
                    self.assertEqual(code, 2, report)
                    self.assertNotIn("internal error", json.dumps(report))

    def test_help_exits_zero(self) -> None:
        proc = subprocess.run(
            [sys.executable, str(RUNNER), "--help"], capture_output=True, text=True, check=False
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("--repo-root", proc.stdout)


if __name__ == "__main__":
    unittest.main()
