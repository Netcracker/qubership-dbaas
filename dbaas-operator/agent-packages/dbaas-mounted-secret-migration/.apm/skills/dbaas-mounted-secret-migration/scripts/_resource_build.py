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


def _is_templated(value: Any) -> bool:
    if isinstance(value, str):
        return "{{" in value
    if isinstance(value, list):
        return any(_is_templated(item) for item in value)
    if isinstance(value, dict):
        return any(_is_templated(item) for item in value.values())
    return False


# A hash of Helm's per-release-unique `.Release.Name` keeps templated identities
# distinct without copying dots or a truncation-boundary hyphen from a legal
# release name into generated volume names (which require a DNS label).
_RELEASE_NAME_HASH_LEN = 8
_RELEASE_NAME_BUDGET = _RELEASE_NAME_HASH_LEN
_RELEASE_NAME_EXPR = f'{{{{ trunc {_RELEASE_NAME_HASH_LEN} (sha256sum .Release.Name) }}}}'

# ``_with_tail`` appends a role token plus a keep_tail suffix (e.g.
# "-admin-credentials") to a templated stem after this module returns it, so
# the static stem must leave room for that suffix too -- not consume the
# entire remainder of the 63-character budget itself. This is the ceiling
# ``_with_tail`` truncates/hashes the parts+keep_tail suffix into, mirroring
# how ``dns_label`` bounds the equivalent non-templated composition.
_RELEASE_TAIL_BUDGET = 20


def identity_stem(
    classifier: dict[str, Any],
    db_type: str,
    *,
    discriminator: str | None,
) -> tuple[str, bool]:
    """Build the naming stem for a SUPPORTED datasource's classifier.

    Returns ``(stem, templated)``. ``classifier["microserviceName"]`` and
    ``["scope"]`` are required, non-empty strings for every SUPPORTED
    datasource -- ``apply_migration.py`` rejects the plan before this runs
    otherwise -- so they are read directly rather than defaulted.

    When either is still a Helm expression (the chart is installed more than
    once, each time with a different value), slugifying the expression's
    literal text would generate the identical stem for every install --
    exactly the hazard ``_check_service_identity`` exists to prevent. Instead
    ``stem`` embeds ``.Release.Name``, the one value Helm itself guarantees is
    unique per release in a namespace, ahead of the remaining static parts.
    ``templated`` tells the caller to compose the rest of the name with
    ``_with_tail`` instead of ``dns_label``, which would mangle the embedded
    expression.
    """

    microservice = classifier["microserviceName"]
    scope = classifier["scope"]
    identity_values = {
        key: value for key, value in _wire_classifier(classifier).items() if key != "namespace"
    }
    if _is_templated(identity_values) or _is_templated(db_type):
        return _templated_identity_stem(classifier, db_type, discriminator=discriminator), True

    parts = [microservice, db_type.lower(), scope]
    tenant = classifier.get("tenantId")
    if tenant:
        parts.append(str(tenant))
    if discriminator:
        parts.append(str(discriminator))
        return dns_label(*parts), False
    extra_identity = {
        key: value
        for key, value in _wire_classifier(classifier).items()
        if key not in {"microserviceName", "scope", "namespace", "tenantId"}
    }
    if extra_identity:
        digest = hashlib.sha256(canonical(extra_identity).encode("utf-8")).hexdigest()[:8]
        parts.append(digest)
    return dns_label(*parts), False


def _templated_identity_stem(
    classifier: dict[str, Any], db_type: str, *, discriminator: str | None
) -> str:
    static_parts = []
    for value in (classifier["microserviceName"], db_type.lower(), classifier["scope"]):
        if not _is_templated(value):
            static_parts.append(value)
    tenant = classifier.get("tenantId")
    if tenant and not _is_templated(tenant):
        static_parts.append(str(tenant))
    if discriminator:
        static_parts.append(str(discriminator))
    static_extra = {
        key: value
        for key, value in _wire_classifier(classifier).items()
        if key not in {"microserviceName", "scope", "namespace", "tenantId"}
        and not _is_templated(value)
    }
    if static_extra:
        static_parts.append(
            hashlib.sha256(canonical(static_extra).encode("utf-8")).hexdigest()[:8]
        )
    # Reserve _RELEASE_NAME_BUDGET + 1 (separator) for the release-name
    # expression ahead of this stem, and _RELEASE_TAIL_BUDGET + 1 (separator)
    # for the parts+keep_tail suffix _with_tail appends after it, so the
    # worst-case rendered name (release name + static stem + longest tail,
    # e.g. "<release>-<stem>-<role>-credentials") still fits in 63 chars.
    static_stem = common.dns_label(
        *static_parts,
        limit=common.DNS_LABEL_MAX - _RELEASE_NAME_BUDGET - 1 - _RELEASE_TAIL_BUDGET - 1,
    )
    return f"{_RELEASE_NAME_EXPR}-{static_stem}"


def _templated_tail(*parts: str, keep_tail: str) -> str:
    """Compose the parts+keep_tail suffix ``_with_tail`` appends to a templated stem.

    Bounded to ``_RELEASE_TAIL_BUDGET`` the same way ``dns_label`` bounds a
    static identity: readable when short, deterministically truncated and
    hashed when ``parts`` (a caller-supplied role name) would otherwise push
    the rendered name past the budget ``_templated_identity_stem`` reserved
    for it.
    """

    non_empty = [str(part) for part in parts if part is not None and str(part) != ""]
    if not non_empty:
        return common.dns_label(keep_tail, limit=_RELEASE_TAIL_BUDGET) if keep_tail else ""
    return common.dns_label(*non_empty, keep_tail=keep_tail, limit=_RELEASE_TAIL_BUDGET)


def _with_tail(stem: str, *parts: str, keep_tail: str, templated: bool) -> str:
    """Append ``parts`` and ``keep_tail`` to ``stem``.

    A templated stem embeds a live Helm expression and must not be run
    through ``dns_label``'s slug regex -- it would mangle the expression, so
    the suffix is bounded separately by ``_templated_tail`` instead.
    """

    if templated:
        tail = _templated_tail(*parts, keep_tail=keep_tail)
        return f"{stem}-{tail}" if tail else stem
    return dns_label(stem, *parts, keep_tail=keep_tail)


def role_token(role: str) -> str:
    role = role.strip()
    return dns_label(role) if role else "default"


def cr_classifier(classifier: dict[str, Any]) -> dict[str, Any]:
    """Split the inventory classifier into the CR encoding (typed + extraKeys).

    A top-level key wins over the same key repeated inside ``extraKeys``, matching
    ``_wire_classifier``'s precedence -- the identity every de-duplication, naming,
    and legacy-declaration match is computed from. Reversing the order here would
    let the runner delete a legacy declaration under one identity while emitting a
    CR that resolves under another.
    """

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
        extra.setdefault(key, value)
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
    names: dict[str, tuple[str, bool]] = {}
    for ds in sorted(supported.values(), key=lambda d: database_key(d["classifier"], d["type"])):
        key = database_key(ds["classifier"], ds["type"])
        if key in databases:
            continue
        stem, templated = identity_stem(
            ds["classifier"], ds["type"], discriminator=discriminators.get(ds["id"])
        )
        name = _with_tail(stem, keep_tail="db", templated=templated)
        names[key] = (stem, templated)
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
        stem, templated = names[db_key]
        token = role_token(role)
        secret_name = _with_tail(stem, token, keep_tail="credentials", templated=templated)
        bundle = {
            "database": _with_tail(stem, keep_tail="db", templated=templated),
            "claim": _with_tail(stem, token, keep_tail="claim", templated=templated),
            "secret": secret_name,
            "volume": _with_tail(stem, token, keep_tail="secret", templated=templated),
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
        # A templated name (an embedded .Release.Name expression) is checked
        # for DNS-1123 validity after render, the same as any other templated
        # field this runner emits -- it is not a fixed label to validate here.
        name = meta["name"]
        if "{{" not in name and not common.is_dns_label(name):
            errors.append(f"{body['kind']} {name!r} is not a valid DNS-1123 label")

    for field in ("database", "claim", "secret", "volume"):
        for bundle in name_bundle.values():
            value = bundle[field]
            if "{{" not in value and not common.is_dns_label(value):
                errors.append(f"generated {field} name {value!r} is not a valid DNS-1123 label")
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
