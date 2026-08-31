#!/usr/bin/env python3
"""Deterministic resource generation for the mounted-secret migration.

Pure functions only: given the datasource inventory and the plan decisions,
produce the ``InternalDatabase`` / ``DatabaseSecretClaim`` bodies and the
collision-free names for every resource, Secret, volume, and mount. The runner
owns file placement, workload patching, and validation.
"""

from __future__ import annotations

import hashlib
import json
from typing import Any

import _migration_common as common

RESERVED_CLASSIFIER_KEYS = {"microserviceName", "scope", "namespace", "tenantId", "customKeys"}
MOUNT_ROOT = "/etc/secrets/dbaas-secrets"
GENERATED = ("SUPPORTED",)


def canonical(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def database_key(classifier: dict[str, Any], db_type: str) -> str:
    return f"{canonical(_wire_classifier(classifier))}|{db_type.lower()}"


def claim_key(classifier: dict[str, Any], db_type: str, role: str) -> str:
    return f"{database_key(classifier, db_type)}|{role.strip()}"


def _wire_classifier(classifier: dict[str, Any]) -> dict[str, Any]:
    """Flatten the inventory classifier to its effective runtime wire form."""

    wire = {key: value for key, value in classifier.items() if key != "extraKeys"}
    for key, value in (classifier.get("extraKeys") or {}).items():
        wire.setdefault(key, value)
    return wire


def dns_label(*parts: Any, keep_tail: str = "") -> str:
    """Deterministic RFC-1123 label; see ``_migration_common.dns_label``."""

    return common.dns_label(*parts, keep_tail=keep_tail)


def identity_stem(
    classifier: dict[str, Any],
    db_type: str,
    *,
    discriminator: str | None,
) -> str:
    microservice = str(classifier.get("microserviceName") or "service")
    scope = str(classifier.get("scope") or "service")
    parts = [microservice, db_type.lower(), scope]
    tenant = classifier.get("tenantId")
    if tenant:
        parts.append(str(tenant))
    if discriminator:
        parts.append(str(discriminator))
        return dns_label(*parts)
    extra_identity = {
        key: value
        for key, value in _wire_classifier(classifier).items()
        if key not in {"microserviceName", "scope", "namespace", "tenantId"}
    }
    if extra_identity:
        digest = hashlib.sha256(canonical(extra_identity).encode("utf-8")).hexdigest()[:8]
        parts.append(digest)
    return dns_label(*parts)


def role_token(role: str) -> str:
    role = role.strip()
    return dns_label(role) if role else "default"


def cr_classifier(classifier: dict[str, Any]) -> dict[str, Any]:
    """Split the inventory classifier into the CR encoding (typed + extraKeys)."""

    typed: dict[str, Any] = {}
    extra: dict[str, Any] = {}
    for key, value in classifier.items():
        if key == "extraKeys":
            continue
        if key in RESERVED_CLASSIFIER_KEYS:
            typed[key] = value
        else:
            extra[key] = value
    for key, value in (classifier.get("extraKeys") or {}).items():
        extra[key] = value
    typed.pop("namespace", None)  # the operator derives it from metadata.namespace
    result = {key: typed[key] for key in ("microserviceName", "scope", "tenantId") if key in typed}
    if "customKeys" in typed:
        result["customKeys"] = typed["customKeys"]
    if extra:
        result["extraKeys"] = extra
    return result


class GenerationError(Exception):
    def __init__(self, entries: list[str]) -> None:
        super().__init__("; ".join(entries))
        self.entries = entries


def build_resources(
    datasources: list[dict[str, Any]],
    claims: list[dict[str, Any]],
    *,
    operator_namespace: str,
    workload_namespace: str,
    origin_service: str,
    discriminators: dict[str, str],
) -> tuple[list[dict[str, Any]], dict[str, dict[str, str]]]:
    """Return (sorted resource bodies, per-claim-key name bundle)."""

    errors: list[str] = []
    by_id = {ds["id"]: ds for ds in datasources}
    supported = {
        ds["id"]: ds for ds in datasources if ds.get("migrationFeasibility") in GENERATED
    }

    databases: dict[str, dict[str, Any]] = {}
    names: dict[str, str] = {}
    for ds in sorted(supported.values(), key=lambda d: database_key(d["classifier"], d["type"])):
        key = database_key(ds["classifier"], ds["type"])
        if key in databases:
            continue
        stem = identity_stem(
            ds["classifier"], ds["type"], discriminator=discriminators.get(ds["id"])
        )
        name = dns_label(stem, keep_tail="db")
        names[key] = stem
        params = ds.get("parameters") or {}
        spec: dict[str, Any] = {
            "operatorNamespace": operator_namespace,
            "classifier": cr_classifier(ds["classifier"]),
            "type": ds["type"].lower(),
            "lazy": False,
        }
        if params.get("namePrefix"):
            spec["namePrefix"] = params["namePrefix"]
        if params.get("settings"):
            spec["settings"] = params["settings"]
        databases[key] = {
            "apiVersion": "dbaas.netcracker.com/v1",
            "kind": "InternalDatabase",
            "metadata": {"name": name, "namespace": workload_namespace},
            "spec": spec,
        }

    claim_bodies: dict[str, dict[str, Any]] = {}
    name_bundle: dict[str, dict[str, str]] = {}
    for claim in claims:
        ds = by_id.get(claim.get("datasourceId"))
        if ds is None:
            errors.append(f"claim references unknown datasource {claim.get('datasourceId')!r}")
            continue
        if ds["id"] not in supported:
            errors.append(
                f"claim for {ds['id']!r} targets a non-SUPPORTED identity "
                f"({ds.get('migrationFeasibility')})"
            )
            continue
        role = str(claim.get("role", ""))
        key = claim_key(ds["classifier"], ds["type"], role)
        db_key = database_key(ds["classifier"], ds["type"])
        stem = names[db_key]
        token = role_token(role)
        secret_name = dns_label(stem, token, keep_tail="credentials")
        bundle = {
            "database": dns_label(stem, keep_tail="db"),
            "claim": dns_label(stem, token, keep_tail="claim"),
            "secret": secret_name,
            "volume": dns_label(stem, token, keep_tail="secret"),
            "mountPath": f"{MOUNT_ROOT}/{secret_name}",
        }
        name_bundle[key] = bundle
        if key in claim_bodies:
            continue
        claim_bodies[key] = {
            "apiVersion": "dbaas.netcracker.com/v1",
            "kind": "DatabaseSecretClaim",
            "metadata": {
                "name": bundle["claim"],
                "namespace": workload_namespace,
                "labels": {"app.kubernetes.io/name": origin_service},
            },
            "spec": {
                "operatorNamespace": operator_namespace,
                "classifier": cr_classifier(ds["classifier"]),
                "type": ds["type"].lower(),
                "userRole": role,
                "secretName": bundle["secret"],
            },
        }

    _check_final_name_collisions(databases, claim_bodies, name_bundle, errors)

    if errors:
        raise GenerationError(errors)

    ordered = sorted(
        [*databases.values(), *claim_bodies.values()],
        key=lambda body: (
            0 if body["kind"] == "InternalDatabase" else 1,
            body["metadata"]["name"],
        ),
    )
    return ordered, name_bundle


def _check_final_name_collisions(
    databases: dict[str, dict[str, Any]],
    claim_bodies: dict[str, dict[str, Any]],
    name_bundle: dict[str, dict[str, str]],
    errors: list[str],
) -> None:
    """After every name is derived, two distinct identities must not collapse onto
    the same resource, Secret, volume, or mount path."""

    resource_owner: dict[tuple[str, str, str], str] = {}
    for body in [*databases.values(), *claim_bodies.values()]:
        meta = body["metadata"]
        identity = (body["kind"], str(meta.get("namespace", "")), meta["name"])
        if identity in resource_owner:
            errors.append(
                f"name collision: {body['kind']} {meta['name']!r} in namespace "
                f"{meta.get('namespace', '')!r} is produced by more than one identity"
            )
        resource_owner[identity] = meta["name"]
        for field in ("name",):
            if not common.is_dns_label(meta[field]):
                errors.append(f"{body['kind']} {meta[field]!r} is not a valid DNS-1123 label")

    for field in ("database", "claim", "secret", "volume"):
        for bundle in name_bundle.values():
            if not common.is_dns_label(bundle[field]):
                errors.append(f"generated {field} name {bundle[field]!r} is not a valid DNS-1123 label")
    # secret / volume / mount path are per (database, role) and must never be
    # shared by two different claim identities.
    for field in ("secret", "volume", "mountPath"):
        owner: dict[str, str] = {}
        for key, bundle in name_bundle.items():
            value = bundle[field]
            if value in owner and owner[value] != key:
                errors.append(
                    f"name collision: {field} {value!r} is produced by two claim identities"
                )
            owner[value] = key
