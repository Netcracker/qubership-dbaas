"""Unit tests for the deterministic resource builder."""

from __future__ import annotations

import hashlib
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

import _resource_build as build  # noqa: E402
import _migration_common as common  # noqa: E402


def datasource(**overrides) -> dict:
    base = {
        "id": "orders-postgresql-service",
        "type": "postgresql",
        "classifier": {"microserviceName": "orders", "namespace": "orders-ns", "scope": "service"},
        "requestedRoles": [""],
        "parameters": {},
        "migrationFeasibility": "SUPPORTED",
    }
    base.update(overrides)
    return base


def claim(role: str = "") -> dict:
    return {"datasourceId": "orders-postgresql-service", "role": role}


class ResourceBuildTest(unittest.TestCase):
    def test_single_supported_datasource(self) -> None:
        resources, bundle = build.build_resources(
            [datasource()],
            [claim()],
            operator_namespace="dbaas-system",
            workload_namespace="orders-ns",
            origin_service="orders",
            discriminators={},
        )
        kinds = [r["kind"] for r in resources]
        self.assertEqual(kinds, ["InternalDatabase", "DatabaseSecretClaim"])
        internal = resources[0]
        self.assertEqual(internal["metadata"]["name"], "orders-postgresql-service-db")
        self.assertNotIn("namespace", internal["spec"]["classifier"])
        self.assertIs(internal["spec"]["lazy"], False)

    def test_two_roles_share_database(self) -> None:
        resources, _ = build.build_resources(
            [datasource(requestedRoles=["", "admin"])],
            [claim(""), claim("admin")],
            operator_namespace="ns",
            workload_namespace="orders-ns",
            origin_service="orders",
            discriminators={},
        )
        self.assertEqual(sum(r["kind"] == "InternalDatabase" for r in resources), 1)
        self.assertEqual(sum(r["kind"] == "DatabaseSecretClaim" for r in resources), 2)
        claims = [r for r in resources if r["kind"] == "DatabaseSecretClaim"]
        self.assertEqual(
            {c["spec"]["userRole"] for c in claims}, {"", "admin"}
        )

    def test_extra_classifier_key_uses_hash_discriminator(self) -> None:
        ds = datasource(
            id="a", classifier={"microserviceName": "orders", "namespace": "orders-ns", "scope": "service", "region": "eu"}
        )
        resources, _ = build.build_resources(
            [ds], [{"datasourceId": "a", "role": ""}],
            operator_namespace="ns", workload_namespace="orders-ns", origin_service="orders",
            discriminators={},
        )
        name = resources[0]["metadata"]["name"]
        self.assertRegex(name, r"orders-postgresql-service-[0-9a-f]{8}-db")
        self.assertEqual(resources[0]["spec"]["classifier"]["extraKeys"], {"region": "eu"})

    def test_blocked_datasource_is_not_generated(self) -> None:
        resources, _ = build.build_resources(
            [datasource(), datasource(id="dyn", migrationFeasibility="NOT_SUPPORTED_DYNAMIC")],
            [claim()],
            operator_namespace="ns",
            workload_namespace="orders-ns",
            origin_service="orders",
            discriminators={},
        )
        self.assertEqual(sum(r["kind"] == "InternalDatabase" for r in resources), 1)

    def test_names_stay_within_63_chars(self) -> None:
        ds = datasource(
            classifier={
                "microserviceName": "a" * 40,
                "namespace": "orders-ns",
                "scope": "service",
                "k": "v",
            }
        )
        resources, bundle = build.build_resources(
            [ds], [{"datasourceId": ds["id"], "role": "readonly"}],
            operator_namespace="ns", workload_namespace="orders-ns", origin_service="orders",
            discriminators={},
        )
        for value in bundle[next(iter(bundle))].values():
            if value.startswith("/"):
                continue
            self.assertLessEqual(len(value), 63)

    def test_templated_names_stay_within_63_chars_after_render(self) -> None:
        # microserviceName is templated, so identity_stem embeds a live
        # .Release.Name expression instead of slugging it; the role name is
        # long enough that, before the tail budget fix, the rendered secret
        # name ("<release>-<stem>-<role>-credentials") would exceed 63.
        ds = datasource(
            classifier={
                "microserviceName": "{{ .Values.SERVICE_NAME }}",
                "namespace": "orders-ns",
                "scope": "service",
            },
            requestedRoles=["extremely-long-role-name-for-testing"],
        )
        resources, bundle = build.build_resources(
            [ds],
            [{"datasourceId": ds["id"], "role": "extremely-long-role-name-for-testing"}],
            operator_namespace="ns",
            workload_namespace="orders-ns",
            origin_service="orders",
            discriminators={},
        )
        internal = next(r for r in resources if r["kind"] == "InternalDatabase")
        names = [internal["metadata"]["name"], *bundle[next(iter(bundle))].values()]
        for name in names:
            if name.startswith("/"):
                continue
            self.assertIn(build._RELEASE_NAME_EXPR, name)
            # Substitute the exact hexadecimal release-hash budget.
            rendered = name.replace(
                build._RELEASE_NAME_EXPR, "a" * build._RELEASE_NAME_HASH_LEN
            )
            self.assertLessEqual(len(rendered), 63, rendered)

    def test_release_names_sharing_a_prefix_do_not_collide(self) -> None:
        # Sprig's sha256sum is a plain hex-encoded SHA-256 digest, the same
        # thing hashlib.sha256().hexdigest() computes.
        def render_release_expr(release_name: str) -> str:
            return hashlib.sha256(release_name.encode("utf-8")).hexdigest()[
                : build._RELEASE_NAME_HASH_LEN
            ]

        ds = datasource(
            classifier={
                "microserviceName": "{{ .Values.SERVICE_NAME }}",
                "namespace": "orders-ns",
                "scope": "service",
            }
        )
        resources, _ = build.build_resources(
            [ds],
            [{"datasourceId": ds["id"], "role": ""}],
            operator_namespace="ns",
            workload_namespace="orders-ns",
            origin_service="orders",
            discriminators={},
        )
        internal = next(r for r in resources if r["kind"] == "InternalDatabase")
        name_template = internal["metadata"]["name"]
        self.assertIn(build._RELEASE_NAME_EXPR, name_template)

        # Two distinct release names sharing every character the old
        # `trunc 20 .Release.Name` truncation alone would have kept.
        release_a = "shared-prefix-twenty" + "-install-one"
        release_b = "shared-prefix-twenty" + "-install-two"
        self.assertEqual(len(release_a[:20]), 20)
        self.assertEqual(release_a[:20], release_b[:20])
        self.assertNotEqual(release_a, release_b)

        rendered_a = name_template.replace(build._RELEASE_NAME_EXPR, render_release_expr(release_a))
        rendered_b = name_template.replace(build._RELEASE_NAME_EXPR, render_release_expr(release_b))
        self.assertNotEqual(rendered_a, rendered_b)

        # Dots are legal in Helm release names but not in Kubernetes volume
        # names. Only the hexadecimal hash is embedded in generated names.
        rendered_dotted = name_template.replace(
            build._RELEASE_NAME_EXPR, render_release_expr("orders.blue")
        )
        self.assertTrue(common.is_dns_label(rendered_dotted))

    def test_any_templated_identity_component_uses_the_release_hash(self) -> None:
        cases = [
            ({"tenantId": "{{ .Values.TENANT }}"}, "postgresql"),
            ({"extraKeys": {"logicalDb": "{{ .Values.LOGICAL_DB }}"}}, "postgresql"),
            ({}, "{{ .Values.DB_TYPE }}"),
        ]
        for classifier_patch, db_type in cases:
            with self.subTest(classifier_patch=classifier_patch, db_type=db_type):
                classifier = {
                    "microserviceName": "orders",
                    "namespace": "orders-ns",
                    "scope": "service",
                    **classifier_patch,
                }
                stem, templated = build.identity_stem(
                    classifier, db_type, discriminator=None
                )
                self.assertTrue(templated)
                self.assertIn(build._RELEASE_NAME_EXPR, stem)


if __name__ == "__main__":
    unittest.main()
