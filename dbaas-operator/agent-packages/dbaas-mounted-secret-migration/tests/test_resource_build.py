"""Unit tests for the deterministic resource builder."""

from __future__ import annotations

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


if __name__ == "__main__":
    unittest.main()
