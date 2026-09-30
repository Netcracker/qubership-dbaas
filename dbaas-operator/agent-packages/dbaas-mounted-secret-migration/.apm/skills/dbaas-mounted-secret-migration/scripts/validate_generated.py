#!/usr/bin/env python3
"""Validate DBaaS declarative resources against a datasource inventory."""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path
from typing import Any, Iterable

try:
    import yaml
except ImportError as exc:  # pragma: no cover - exercised only without the pinned dependency
    raise SystemExit(
        "PyYAML is required; install it in the execution environment before running "
        "scripts/validate_generated.py"
    ) from exc


DNS_LABEL = re.compile(r"^[a-z0-9](?:[-a-z0-9]*[a-z0-9])?$")
# Kubernetes label VALUES (unlike DNS-1123 labels) may contain uppercase
# letters, "_", and "." in addition to "-", so long as they start and end
# with an alphanumeric character.
LABEL_VALUE = re.compile(r"^[A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?$")
RESERVED_EXTRA_KEYS = {"microserviceName", "scope", "namespace", "tenantId", "customKeys"}
WORKLOAD_KINDS = {"Deployment", "StatefulSet"}


def canonical(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def is_json_value(value: Any) -> bool:
    if value is None or isinstance(value, (str, bool, int)):
        return True
    if isinstance(value, float):
        return math.isfinite(value)
    if isinstance(value, list):
        return all(is_json_value(item) for item in value)
    if isinstance(value, dict):
        return all(isinstance(key, str) and is_json_value(item) for key, item in value.items())
    return False


def database_key(classifier: dict[str, Any], db_type: str) -> str:
    return f"{canonical(classifier)}|{db_type.lower()}"


def claim_key(classifier: dict[str, Any], db_type: str, role: str) -> str:
    return f"{database_key(classifier, db_type)}|{role.strip()}"


def describe_keys(keys: set[str]) -> str:
    return "; ".join(sorted(keys))


def manifest_files(paths: Iterable[Path]) -> list[Path]:
    result: list[Path] = []
    for path in paths:
        if path.is_dir():
            result.extend(sorted(path.rglob("*.yaml")))
            result.extend(sorted(path.rglob("*.yml")))
        elif path.is_file():
            result.append(path)
        else:
            raise ValueError(f"manifest path does not exist: {path}")
    return result


def load_objects(paths: list[Path]) -> list[dict[str, Any]]:
    objects: list[dict[str, Any]] = []
    for path in manifest_files(paths):
        with path.open(encoding="utf-8") as stream:
            for index, document in enumerate(yaml.safe_load_all(stream), start=1):
                if document is None:
                    continue
                if not isinstance(document, dict):
                    raise ValueError(f"{path}:{index}: manifest document must be a mapping")
                document["__source"] = f"{path}:{index}"
                objects.append(document)
    return objects


def object_identity(obj: dict[str, Any], *, default_namespace: str = "default") -> str:
    metadata = obj.get("metadata") or {}
    namespace = metadata.get("namespace") or default_namespace
    return f"{obj.get('kind', '<missing>')}/{namespace}/{metadata.get('name', '<missing>')}"


def check_name(name: Any, what: str, errors: list[str]) -> None:
    if not isinstance(name, str) or not name:
        errors.append(f"{what}: name is required")
    elif len(name) > 63 or DNS_LABEL.fullmatch(name) is None:
        errors.append(f"{what}: {name!r} is not a DNS-1123 label of at most 63 characters")


def check_label_value(value: Any, what: str, errors: list[str]) -> None:
    # Kubernetes label values may also be empty; a non-empty value is checked
    # against the label-value charset/length rule, so "orders " (trailing
    # space) or a 64-character value cannot slip past a bare non-empty check.
    if value == "":
        return
    if not isinstance(value, str) or len(value) > 63 or LABEL_VALUE.fullmatch(value) is None:
        errors.append(f"{what}: {value!r} is not a valid Kubernetes label value")


def effective_classifier(
    obj: dict[str, Any], errors: list[str], *, default_namespace: str = "default"
) -> dict[str, Any] | None:
    spec = obj.get("spec") or {}
    classifier = spec.get("classifier")
    identity = object_identity(obj, default_namespace=default_namespace)
    if not isinstance(classifier, dict):
        errors.append(f"{identity}: spec.classifier must be a mapping")
        return None

    metadata = obj.get("metadata") or {}
    namespace = metadata.get("namespace") or default_namespace
    classifier = dict(classifier)
    classifier_namespace = classifier.get("namespace")
    if classifier_namespace not in (None, "", namespace):
        errors.append(
            f"{identity}: classifier namespace {classifier_namespace!r} does not match metadata namespace {namespace!r}"
        )
    classifier["namespace"] = namespace

    custom_keys = classifier.get("customKeys")
    if custom_keys is not None and not isinstance(custom_keys, dict):
        errors.append(f"{identity}: classifier.customKeys must be a mapping")

    extra_keys = classifier.get("extraKeys")
    if extra_keys is None:
        extra_keys = {}
    if not isinstance(extra_keys, dict):
        errors.append(f"{identity}: classifier.extraKeys must be a mapping")
    else:
        reserved = sorted(RESERVED_EXTRA_KEYS.intersection(extra_keys))
        if reserved:
            errors.append(f"{identity}: classifier.extraKeys contains reserved keys: {', '.join(reserved)}")

        # The CR stores arbitrary top-level runtime classifier extensions under
        # extraKeys. Compare identities using the flattened wire representation.
        classifier.pop("extraKeys", None)
        for key, value in extra_keys.items():
            if key in RESERVED_EXTRA_KEYS:
                continue
            if key in classifier:
                errors.append(f"{identity}: classifier.extraKeys key {key!r} collides with classifier.{key}")
                continue
            classifier[key] = value

    scope = classifier.get("scope")
    if scope is not None and not (isinstance(scope, str) and scope.strip()):
        errors.append(f"{identity}: classifier.scope must be a non-empty string")
    elif isinstance(scope, str) and "{{" not in scope and scope not in ("service", "tenant"):
        # The CRD enforces this enum at admission; catch it here instead of
        # letting a plausible-looking non-empty string (e.g. "global") pass
        # validation only to be rejected by the API server. A still-templated
        # value is deferred to render time, same as elsewhere in this module.
        errors.append(f"{identity}: classifier.scope must be 'service' or 'tenant', got {scope!r}")
    microservice_name = classifier.get("microserviceName")
    if microservice_name is not None and not (
        isinstance(microservice_name, str) and microservice_name.strip()
    ):
        errors.append(f"{identity}: classifier.microserviceName must be a non-empty string")

    return classifier


def validate_inventory(
    inventory_path: Path,
    internals: dict[str, dict[str, Any]],
    claims: dict[str, dict[str, Any]],
    errors: list[str],
) -> None:
    with inventory_path.open(encoding="utf-8") as stream:
        inventory = json.load(stream)

    inventory_error_count = len(errors)
    expected_databases: set[str] = set()
    expected_claims: set[str] = set()
    for datasource in inventory.get("datasources", []):
        if datasource.get("migrationFeasibility") != "SUPPORTED":
            continue
        classifier = datasource.get("classifier")
        db_type = datasource.get("type")
        if not isinstance(classifier, dict) or not isinstance(db_type, str) or not db_type:
            errors.append(f"inventory datasource {datasource.get('id', '<unknown>')}: classifier and type are required")
            continue
        classifier_namespace = classifier.get("namespace")
        if not isinstance(classifier_namespace, str) or not classifier_namespace:
            errors.append(
                f"inventory datasource {datasource.get('id', '<unknown>')}: "
                "classifier.namespace must contain the effective workload namespace"
            )
            continue
        if "extraKeys" in classifier:
            errors.append(
                f"inventory datasource {datasource.get('id', '<unknown>')}: classifier must use the effective "
                "wire form; flatten classifier.extraKeys into top-level keys"
            )
            continue
        db_key = database_key(classifier, db_type)
        expected_databases.add(db_key)
        roles = datasource.get("requestedRoles", [""])
        if not isinstance(roles, list) or not all(isinstance(role, str) for role in roles):
            errors.append(f"inventory datasource {datasource.get('id', '<unknown>')}: requestedRoles must be strings")
            continue
        expected_claims.update(claim_key(classifier, db_type, role) for role in roles)

    if len(errors) > inventory_error_count:
        return

    missing_databases = expected_databases - set(internals)
    extra_databases = set(internals) - expected_databases
    missing_claims = expected_claims - set(claims)
    extra_claims = set(claims) - expected_claims
    if missing_databases:
        errors.append(f"missing InternalDatabase identities: {describe_keys(missing_databases)}")
    if extra_databases:
        errors.append(f"unexpected InternalDatabase identities: {describe_keys(extra_databases)}")
    if missing_claims:
        errors.append(f"missing DatabaseSecretClaim identities: {describe_keys(missing_claims)}")
    if extra_claims:
        errors.append(f"unexpected DatabaseSecretClaim identities: {describe_keys(extra_claims)}")


def validate(
    paths: list[Path],
    inventory: Path | None,
    operator_namespace: str | None = None,
    *,
    default_namespace: str = "default",
) -> list[str]:
    errors: list[str] = []
    objects = load_objects(paths)
    seen_objects: set[str] = set()
    internals: dict[str, dict[str, Any]] = {}
    claims: dict[str, dict[str, Any]] = {}
    secret_claims: dict[tuple[str, str], str] = {}

    for obj in objects:
        identity = object_identity(obj, default_namespace=default_namespace)
        if identity in seen_objects:
            errors.append(f"duplicate object identity: {identity}")
        seen_objects.add(identity)
        metadata = obj.get("metadata") or {}
        check_name(metadata.get("name"), identity, errors)

        kind = obj.get("kind")
        if kind not in {"InternalDatabase", "DatabaseSecretClaim"}:
            continue
        classifier = effective_classifier(obj, errors, default_namespace=default_namespace)
        spec = obj.get("spec") or {}
        # spec.operatorNamespace is required and immutable on every managed CR. When the caller
        # passes the resolved operator namespace, also assert an exact match, since reusing the
        # workload namespace here is the most common mistake.
        cr_operator_namespace = spec.get("operatorNamespace")
        if not isinstance(cr_operator_namespace, str) or not cr_operator_namespace.strip():
            errors.append(f"{identity}: spec.operatorNamespace is required and must be non-empty")
        else:
            if "{{" not in cr_operator_namespace and (
                len(cr_operator_namespace) > 63 or DNS_LABEL.fullmatch(cr_operator_namespace) is None
            ):
                # The CRD's pattern requires an RFC-1123 label; a plausible-looking
                # non-empty string (uppercase, a space, a trailing hyphen) would
                # otherwise pass here only to be rejected by the API server at
                # admission. A still-templated value is deferred to render time.
                errors.append(
                    f"{identity}: spec.operatorNamespace {cr_operator_namespace!r} is not a valid "
                    "RFC-1123 namespace label"
                )
            if operator_namespace is not None and cr_operator_namespace != operator_namespace:
                errors.append(
                    f"{identity}: spec.operatorNamespace {cr_operator_namespace!r} does not match the "
                    f"expected operator namespace {operator_namespace!r}"
                )
        db_type = spec.get("type")
        if classifier is None or not isinstance(db_type, str) or not db_type:
            errors.append(f"{identity}: spec.type is required")
            continue
        db_key = database_key(classifier, db_type)

        if kind == "InternalDatabase":
            lazy = spec.get("lazy")
            if lazy is not None and not isinstance(lazy, bool):
                errors.append(f"{identity}: spec.lazy must be a boolean")
            settings = spec.get("settings")
            if settings is None:
                settings = {}
            if not isinstance(settings, dict) or not all(
                isinstance(key, str) and is_json_value(value) for key, value in settings.items()
            ):
                errors.append(f"{identity}: spec.settings must map string keys to valid JSON values")
            if db_key in internals:
                errors.append(
                    f"duplicate InternalDatabase identity: {identity} and "
                    f"{object_identity(internals[db_key], default_namespace=default_namespace)}"
                )
            internals[db_key] = obj
            continue

        labels = metadata.get("labels") or {}
        name_label = labels.get("app.kubernetes.io/name") if isinstance(labels, dict) else None
        if not isinstance(labels, dict) or not name_label:
            errors.append(f"{identity}: non-empty app.kubernetes.io/name label is required")
        else:
            check_label_value(name_label, f"{identity} metadata.labels['app.kubernetes.io/name']", errors)
        role = spec.get("userRole", "")
        if not isinstance(role, str):
            errors.append(f"{identity}: spec.userRole must be a string")
            role = ""
        elif role != role.strip():
            # claim_key strips the role before keying the identity; a writer
            # emitting an untrimmed spec.userRole would key one claim while
            # writing a different-looking (but identity-equal) role value.
            errors.append(f"{identity}: spec.userRole {role!r} must already be normalized (no leading/trailing whitespace)")
        key = claim_key(classifier, db_type, role)
        if key in claims:
            errors.append(
                f"duplicate DatabaseSecretClaim lookup identity: {identity} and "
                f"{object_identity(claims[key], default_namespace=default_namespace)}"
            )
        claims[key] = obj
        secret_name = spec.get("secretName")
        check_name(secret_name, f"{identity} spec.secretName", errors)
        namespace = metadata.get("namespace") or default_namespace
        secret_key = (namespace, secret_name)
        if secret_key in secret_claims:
            errors.append(f"{identity}: Secret {namespace}/{secret_name} is also claimed by {secret_claims[secret_key]}")
        secret_claims[secret_key] = identity

    for key, claim in claims.items():
        db_key = key.rsplit("|", 1)[0]
        if db_key not in internals:
            errors.append(
                f"{object_identity(claim, default_namespace=default_namespace)}: "
                "no InternalDatabase has the same classifier and type"
            )

    mount_occurrences: dict[tuple[str, str], list[tuple[str, Any, Any]]] = {}
    for obj in objects:
        if obj.get("kind") not in WORKLOAD_KINDS:
            continue
        metadata = obj.get("metadata") or {}
        namespace = metadata.get("namespace") or default_namespace
        pod_spec = (((obj.get("spec") or {}).get("template") or {}).get("spec") or {})
        volume_secrets: dict[str, str] = {}
        seen_volume_names: set[str] = set()
        for volume in pod_spec.get("volumes") or []:
            if not isinstance(volume, dict):
                continue
            volume_name = volume.get("name")
            if volume_name in seen_volume_names:
                errors.append(
                    f"{object_identity(obj, default_namespace=default_namespace)}: "
                    f"duplicate volume name {volume_name!r}"
                )
            seen_volume_names.add(volume_name)
            secret = volume.get("secret") or {}
            if secret.get("secretName"):
                volume_secrets[volume_name] = secret["secretName"]
        for container_field, container_label in (("containers", "container"), ("initContainers", "initContainer")):
            for container in pod_spec.get(container_field) or []:
                for mount in container.get("volumeMounts") or []:
                    secret_name = volume_secrets.get(mount.get("name"))
                    if not secret_name:
                        continue
                    secret_key = (namespace, secret_name)
                    mount_occurrences.setdefault(secret_key, []).append(
                        (
                            f"{object_identity(obj, default_namespace=default_namespace)} "
                            f"{container_label} {container.get('name', '<missing>')}",
                            mount.get("mountPath"),
                            mount.get("readOnly"),
                        )
                    )

    for secret_key, claim_identity in secret_claims.items():
        occurrences = mount_occurrences.get(secret_key, [])
        if not occurrences:
            errors.append(
                f"{claim_identity}: Secret {secret_key[0]}/{secret_key[1]} must have at least one consuming volumeMount"
            )
            continue
        expected_path = f"/etc/secrets/dbaas-secrets/{secret_key[1]}"
        for location, mount_path, read_only in occurrences:
            if mount_path != expected_path or read_only is not True:
                errors.append(
                    f"{claim_identity}: mount in {location} must use path {expected_path!r} with readOnly: true"
                )

    if inventory is not None:
        validate_inventory(inventory, internals, claims, errors)
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifests", nargs="+", type=Path)
    parser.add_argument("--inventory", type=Path)
    parser.add_argument(
        "--operator-namespace",
        help="If set, assert every managed CR's spec.operatorNamespace equals this value",
    )
    parser.add_argument(
        "--default-namespace",
        default="default",
        help="Namespace to assume for a manifest that omits metadata.namespace (default: %(default)s)",
    )
    args = parser.parse_args()
    try:
        errors = validate(
            args.manifests,
            args.inventory,
            args.operator_namespace,
            default_namespace=args.default_namespace,
        )
    except (OSError, ValueError, json.JSONDecodeError, yaml.YAMLError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    if errors:
        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        return 1
    print("ok: generated DBaaS resources match the inventory and workload mounts")
    return 0


if __name__ == "__main__":
    sys.exit(main())
