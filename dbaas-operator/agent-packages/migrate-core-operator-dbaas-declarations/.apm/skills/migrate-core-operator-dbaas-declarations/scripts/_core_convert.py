#!/usr/bin/env python3
"""Legacy Core Operator DBaaS field mapping.

This module carries the tested conversion logic that used to live in
``convert_dbaas_crs.py``. It is now an internal library consumed only by
``apply_migration.py``; it writes nothing and parses no command line.

``convert_documents`` turns a list of parsed legacy documents into a list of
target resource dictionaries (``InternalDatabase`` / ``DatabaseAccessPolicy``),
along with non-blocking warnings and blocking errors. Deterministic naming, YAML
serialization, and source cleanup are the runner's responsibility.
"""

from __future__ import annotations

import dataclasses
import json
import math
import re
from typing import Any

RESERVED_CLASSIFIER_KEYS = {
    "microserviceName",
    "scope",
    "namespace",
    "tenantId",
    "customKeys",
}
DATABASE_DECLARATION_FIELDS = {
    "classifierConfig",
    "type",
    "lazy",
    "settings",
    "namePrefix",
    "versioningConfig",
    "initialInstantiation",
}
DB_POLICY_FIELDS = {
    "apiVersion",
    "kind",
    "metadata",
    "microserviceName",
    "services",
    "policy",
    "disableGlobalPermissions",
}
PRESERVED_METADATA_FIELDS = {"name", "namespace"}

DEFAULT_SERVICE_TEMPLATE = "{{ .Values.SERVICE_NAME }}"
DEFAULT_NAMESPACE_TEMPLATE = "{{ .Values.NAMESPACE }}"


@dataclasses.dataclass
class ConversionContext:
    """Resolved decisions the field mapping needs.

    ``service_name_explicit`` records whether the plan pinned the owning service
    or the mapping is allowed to fall back to source-derived identity.
    """

    operator_namespace: str
    service_name: str = DEFAULT_SERVICE_TEMPLATE
    service_name_explicit: bool = False
    namespace: str = DEFAULT_NAMESPACE_TEMPLATE


@dataclasses.dataclass
class ConvertedResource:
    """One target resource plus the identity hints the runner names it from."""

    body: dict[str, Any]
    legacy_kind: str
    source_ref: str
    name_hint: str
    parent_name: str | None
    parent_index: int | None


def convert_documents(
    documents: list[Any],
    ctx: ConversionContext,
    *,
    source_ref: str,
) -> tuple[list[ConvertedResource], list[str], list[str]]:
    warnings: list[str] = []
    errors: list[str] = []
    resources: list[ConvertedResource] = []
    for doc_index, doc in enumerate(documents, start=1):
        if doc is None:
            continue
        for item_index, item in enumerate(as_legacy_items(doc), start=1):
            resources.extend(
                _convert_item(
                    item, doc_index, item_index, ctx, warnings, errors,
                    source_ref=f"{source_ref}#{doc_index - 1}",
                )
            )
    return resources, warnings, errors


def as_legacy_items(doc: Any) -> list[dict[str, Any]]:
    if isinstance(doc, list):
        return [item for item in doc if isinstance(item, dict)]
    if isinstance(doc, dict):
        return [doc]
    return []


def is_supported_legacy_item(item: Any) -> bool:
    if not isinstance(item, dict):
        return False
    kind = str(item.get("kind", ""))
    sub_kind = str(item.get("subKind", ""))
    legacy = (sub_kind or kind).lower()
    if legacy in {"databasedeclaration", "dbpolicy"}:
        return True
    if kind == "DBaaS" and str(item.get("subKind", "")).lower() in {
        "databasedeclaration",
        "dbpolicy",
    }:
        return True
    return "declarations" in item and isinstance(item.get("declarations"), list)


def _convert_item(
    item: dict[str, Any],
    doc_index: int,
    item_index: int,
    ctx: ConversionContext,
    warnings: list[str],
    errors: list[str],
    *,
    source_ref: str,
) -> list[ConvertedResource]:
    kind = str(item.get("kind", ""))
    sub_kind = str(item.get("subKind", ""))
    legacy_kind = sub_kind or kind
    legacy_kind_lower = legacy_kind.lower()

    if kind == "DBaaS":
        body = dict(item.get("spec") or {})
        metadata = dict(item.get("metadata") or {})
    else:
        body = dict(item)
        metadata = dict(item.get("metadata") or {})

    _warn_dropped_metadata(metadata, f"Document {doc_index}", warnings)

    if legacy_kind_lower in {"databasedeclaration", ""} and "declarations" in body:
        legacy_kind_lower = "databasedeclaration"

    if legacy_kind_lower == "databasedeclaration":
        declarations = body.get("declarations")
        if declarations is None:
            declarations = [body]
        if not isinstance(declarations, list):
            errors.append(
                f"Document {doc_index}: DatabaseDeclaration.declarations is not a list "
                f"(found {type(declarations).__name__}); fix the source"
            )
            return []
        multiple = len(declarations) > 1
        out: list[ConvertedResource] = []
        for declaration_index, declaration in enumerate(declarations, start=1):
            if not isinstance(declaration, dict):
                errors.append(
                    f"Document {doc_index}: declaration #{declaration_index} is not an object; "
                    "a structural defect cannot be resolved away"
                )
                continue
            body_dict, hint = _convert_database_declaration(
                declaration, metadata, doc_index, declaration_index, multiple, ctx, warnings, errors
            )
            out.append(
                ConvertedResource(
                    body=body_dict,
                    legacy_kind="DatabaseDeclaration",
                    source_ref=source_ref,
                    name_hint=hint,
                    parent_name=metadata.get("name"),
                    parent_index=declaration_index if multiple else None,
                )
            )
        return out

    if legacy_kind_lower == "dbpolicy":
        body_dict, hint = _convert_db_policy(
            body, metadata, doc_index, item_index, ctx, warnings, errors
        )
        return [
            ConvertedResource(
                body=body_dict,
                legacy_kind="DbPolicy",
                source_ref=source_ref,
                name_hint=hint,
                parent_name=metadata.get("name"),
                parent_index=None,
            )
        ]

    if kind == "DBaaS":
        # A kind: DBaaS wrapper is unambiguously a legacy DBaaS resource the user
        # means to migrate. An unsupported subKind is a hard error, not a warning
        # that can be resolved away and then trip source-cleanup validation.
        errors.append(
            f"Document {doc_index}: kind DBaaS with unsupported subKind "
            f"{sub_kind or '<none>'!r}; this migration converts only DatabaseDeclaration and DbPolicy"
        )
        return []
    warnings.append(f"Document {doc_index}: skipped unsupported kind {legacy_kind!r}")
    return []


def _convert_database_declaration(
    declaration: dict[str, Any],
    old_metadata: dict[str, Any],
    doc_index: int,
    declaration_index: int,
    multiple_declarations: bool,
    ctx: ConversionContext,
    warnings: list[str],
    errors: list[str],
) -> tuple[dict[str, Any], str]:
    _warn_unknown_fields(
        declaration, DATABASE_DECLARATION_FIELDS, f"DatabaseDeclaration #{declaration_index}", warnings
    )
    classifier_config = declaration.get("classifierConfig") or {}
    classifier = (
        classifier_config.get("classifier") if isinstance(classifier_config, dict) else None
    )
    if not isinstance(classifier, dict):
        classifier = {}
        warnings.append(
            f"DatabaseDeclaration #{declaration_index}: missing classifierConfig.classifier"
        )
    default_name = database_name_hint(declaration, classifier, doc_index, declaration_index)
    namespace = old_metadata.get("namespace") or ctx.namespace
    target_classifier = convert_classifier(classifier, ctx.service_name)
    legacy_namespace = target_classifier.pop("namespace", None)
    if legacy_namespace not in (None, "", namespace):
        warnings.append(
            f"InternalDatabase {default_name} classifier.namespace {legacy_namespace!r} differs "
            f"from metadata.namespace {namespace!r}; omitted classifier.namespace"
        )

    spec: dict[str, Any] = {
        "operatorNamespace": ctx.operator_namespace,
        "classifier": target_classifier,
    }
    for field in ("type", "namePrefix", "versioningConfig", "initialInstantiation"):
        if field in declaration:
            spec[field] = convert_nested_classifiers(declaration[field], ctx.service_name)

    if "lazy" in declaration:
        spec["lazy"] = coerce_bool(declaration["lazy"])
        if not isinstance(spec["lazy"], bool):
            errors.append(
                f"InternalDatabase {default_name} has a non-boolean lazy value "
                f"{declaration['lazy']!r}; set true or false in the source"
            )

    if "settings" in declaration:
        settings = declaration["settings"]
        spec["settings"] = settings
        if not isinstance(settings, dict):
            errors.append(
                f"InternalDatabase {default_name} has invalid settings at settings: expected an object"
            )
        else:
            for path, reason in json_value_errors(settings, "settings"):
                errors.append(
                    f"InternalDatabase {default_name} has invalid JSON value at {path}: {reason}"
                )

    for required_key in ("microserviceName", "scope"):
        if not target_classifier.get(required_key):
            errors.append(
                f"InternalDatabase {default_name} is missing required classifier.{required_key}"
            )
    if not spec.get("type"):
        errors.append(f"InternalDatabase {default_name} is missing required spec.type")

    if spec.get("lazy") is True:
        initial = spec.get("initialInstantiation")
        if isinstance(initial, dict) and initial.get("approach") == "clone":
            errors.append(
                f"InternalDatabase {default_name}: lazy=true with "
                "initialInstantiation.approach=clone is invalid"
            )
    initial = spec.get("initialInstantiation")
    if (
        isinstance(initial, dict)
        and initial.get("approach") == "clone"
        and "sourceClassifier" not in initial
    ):
        errors.append(
            f"InternalDatabase {default_name}: initialInstantiation.approach=clone "
            "requires sourceClassifier"
        )
    _validate_source_classifier_owner(spec, default_name, warnings, errors)

    body = {
        "apiVersion": "dbaas.netcracker.com/v1",
        "kind": "InternalDatabase",
        "metadata": {"name": default_name, "namespace": namespace},
        "spec": spec,
    }
    return body, default_name


def _nonempty_str(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _nonempty_list(value: Any) -> bool:
    return isinstance(value, list) and len(value) > 0


SERVICE_ROLE_FIELDS = {"name", "roles"}
POLICY_ROLE_FIELDS = {"type", "defaultRole", "additionalRole"}


def _check_service_role(entry: dict[str, Any], index: int, errors: list[str]) -> None:
    """Validate one ``spec.services`` entry against the ServiceRole CRD shape."""

    where = f"DatabaseAccessPolicy.spec.services[{index}]"
    unknown = sorted(set(entry) - SERVICE_ROLE_FIELDS)
    if unknown:
        errors.append(f"{where} has unsupported fields: {', '.join(unknown)}")
    if not _nonempty_str(entry.get("name")):
        errors.append(f"{where}.name must be a non-empty string")
    roles = entry.get("roles")
    if not isinstance(roles, list) or not roles or not all(_nonempty_str(role) for role in roles):
        errors.append(f"{where}.roles must be a non-empty list of non-empty strings")


def _check_policy_role(entry: dict[str, Any], index: int, errors: list[str]) -> None:
    """Validate one ``spec.policy`` entry against the PolicyRole CRD shape."""

    where = f"DatabaseAccessPolicy.spec.policy[{index}]"
    unknown = sorted(set(entry) - POLICY_ROLE_FIELDS)
    if unknown:
        errors.append(f"{where} has unsupported fields: {', '.join(unknown)}")
    for key in ("type", "defaultRole"):
        if not _nonempty_str(entry.get(key)):
            errors.append(f"{where}.{key} must be a non-empty string")
    additional = entry.get("additionalRole")
    if additional is not None and (
        not isinstance(additional, list) or not all(_nonempty_str(role) for role in additional)
    ):
        errors.append(f"{where}.additionalRole must be a list of non-empty strings")


def _convert_db_policy(
    body: dict[str, Any],
    old_metadata: dict[str, Any],
    doc_index: int,
    item_index: int,
    ctx: ConversionContext,
    warnings: list[str],
    errors: list[str],
) -> tuple[dict[str, Any], str]:
    _warn_unknown_fields(body, DB_POLICY_FIELDS, "DatabaseAccessPolicy", warnings)
    source_microservice_name = body.get("microserviceName") or _label_value(
        old_metadata, "app.kubernetes.io/instance"
    )
    if ctx.service_name_explicit:
        microservice_name = ctx.service_name
    elif source_microservice_name:
        microservice_name = source_microservice_name
    else:
        microservice_name = None
        errors.append(
            "DatabaseAccessPolicy.spec.microserviceName is unresolved: the source has no "
            "microserviceName and the plan did not pin serviceName (serviceNameExplicit)"
        )

    spec: dict[str, Any] = {
        "operatorNamespace": ctx.operator_namespace,
        "microserviceName": normalize_service_template(
            str(microservice_name or "UNRESOLVED"), ctx.service_name
        ),
    }
    for field in ("services", "policy"):
        if field not in body:
            continue
        value = body[field]
        # The CRD types both as arrays of objects; a scalar or mapping here would
        # be rejected at admission, so block it now instead of emitting it.
        if not isinstance(value, list) or not all(isinstance(item, dict) for item in value):
            errors.append(
                f"DatabaseAccessPolicy.spec.{field} must be a list of objects, got "
                f"{type(value).__name__}"
            )
        else:
            check = _check_service_role if field == "services" else _check_policy_role
            for index, item in enumerate(value):
                check(item, index, errors)
        spec[field] = value
    if "disableGlobalPermissions" in body:
        coerced = coerce_bool(body["disableGlobalPermissions"])
        spec["disableGlobalPermissions"] = coerced
        if not isinstance(coerced, bool):
            errors.append(
                "DatabaseAccessPolicy.spec.disableGlobalPermissions must be a boolean, got "
                f"{body['disableGlobalPermissions']!r}"
            )
    if not _nonempty_list(spec.get("services")) and not _nonempty_list(spec.get("policy")):
        errors.append(
            "DatabaseAccessPolicy needs a non-empty services or policy list; the CRD requires "
            "at least one entry across the two"
        )

    namespace = old_metadata.get("namespace") or ctx.namespace
    hint = old_metadata.get("name") or "database-access-policy"
    body_dict = {
        "apiVersion": "dbaas.netcracker.com/v1",
        "kind": "DatabaseAccessPolicy",
        "metadata": {"name": hint, "namespace": namespace},
        "spec": spec,
    }
    return body_dict, hint


# --------------------------------------------------------------------------- #
# Shared helpers (unchanged behaviour from convert_dbaas_crs.py)
# --------------------------------------------------------------------------- #


def json_value_errors(value: Any, path: str) -> list[tuple[str, str]]:
    if value is None or isinstance(value, (str, bool, int)):
        return []
    if isinstance(value, float):
        return [] if math.isfinite(value) else [(path, "non-finite numbers are not valid JSON")]
    if isinstance(value, list):
        errors: list[tuple[str, str]] = []
        for index, item in enumerate(value):
            errors.extend(json_value_errors(item, f"{path}[{index}]"))
        return errors
    if isinstance(value, dict):
        errors = []
        for key, item in value.items():
            if not isinstance(key, str):
                errors.append((f"{path}[{key!r}]", "object keys must be strings"))
                continue
            errors.extend(json_value_errors(item, _json_child_path(path, key)))
        return errors
    return [(path, f"{type(value).__name__} values are not valid JSON")]


def _json_child_path(path: str, key: str) -> str:
    if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_-]*", key):
        return f"{path}.{key}"
    return f"{path}[{json.dumps(key, ensure_ascii=False)}]"


def _validate_source_classifier_owner(
    spec: dict[str, Any], resource_name_hint: str, warnings: list[str], errors: list[str]
) -> None:
    initial = spec.get("initialInstantiation")
    if not isinstance(initial, dict):
        return
    source_classifier = initial.get("sourceClassifier")
    if not isinstance(source_classifier, dict):
        return
    target_classifier = spec.get("classifier")
    if not isinstance(target_classifier, dict):
        return
    target_owner = target_classifier.get("microserviceName")
    source_owner = source_classifier.get("microserviceName")
    if not source_owner and target_owner:
        source_classifier["microserviceName"] = target_owner
        warnings.append(
            f"InternalDatabase {resource_name_hint} sourceClassifier.microserviceName was missing; "
            "filled it from classifier.microserviceName"
        )
    elif target_owner and source_owner != target_owner:
        errors.append(
            f"InternalDatabase {resource_name_hint} sourceClassifier.microserviceName "
            f"{source_owner!r} must match classifier.microserviceName {target_owner!r}; "
            "cross-service clones are invalid"
        )


def database_name_hint(
    declaration: dict[str, Any],
    classifier: dict[str, Any],
    doc_index: int,
    declaration_index: int,
) -> str:
    scope = str(classifier.get("scope") or "db")
    custom_keys = classifier.get("customKeys")
    logical_name = None
    if isinstance(custom_keys, dict):
        logical_name = (
            custom_keys.get("logicalDbName")
            or custom_keys.get("logicalDBName")
            or custom_keys.get("logicalDBname")
        )
    if logical_name:
        return sanitize_name(f"{scope}-{logical_name}-db")
    if classifier.get("transactional") is True:
        return sanitize_name(f"{scope}-transactional-db")
    db_type = declaration.get("type")
    if db_type:
        return sanitize_name(f"{scope}-{db_type}-db")
    return f"internaldatabase-{doc_index}-{declaration_index}"


def convert_nested_classifiers(value: Any, service_name: str) -> Any:
    if isinstance(value, dict):
        converted = {}
        for key, nested in value.items():
            if key == "sourceClassifier" and isinstance(nested, dict):
                converted[key] = convert_classifier(nested, service_name)
            else:
                converted[key] = convert_nested_classifiers(nested, service_name)
        return converted
    if isinstance(value, list):
        return [convert_nested_classifiers(item, service_name) for item in value]
    return value


def convert_classifier(classifier: dict[str, Any], service_name: str) -> dict[str, Any]:
    converted: dict[str, Any] = {}
    extra_keys: dict[str, Any] = {}
    for key, value in classifier.items():
        if key in RESERVED_CLASSIFIER_KEYS:
            if key == "microserviceName" and isinstance(value, str):
                converted[key] = normalize_service_template(value, service_name)
            else:
                converted[key] = value
        else:
            extra_keys[key] = value
    if extra_keys:
        converted["extraKeys"] = extra_keys
    return converted


def normalize_service_template(value: str, service_name: str) -> str:
    if value.strip() in {"{{$SERVICE_NAME}}", "{{ $SERVICE_NAME }}", "${SERVICE_NAME}"}:
        return service_name
    return value


def coerce_bool(value: Any) -> Any:
    if isinstance(value, str):
        lowered = value.strip().lower()
        if lowered == "true":
            return True
        if lowered == "false":
            return False
    return value


def _label_value(metadata: dict[str, Any], key: str) -> Any:
    labels = metadata.get("labels")
    if isinstance(labels, dict):
        return labels.get(key)
    return None


def _warn_unknown_fields(
    source: dict[str, Any], known_fields: set[str], context: str, warnings: list[str]
) -> None:
    unknown = sorted(set(source) - known_fields)
    if unknown:
        warnings.append(f"{context} has unsupported fields that were dropped: {', '.join(unknown)}")


def _warn_dropped_metadata(metadata: dict[str, Any], context: str, warnings: list[str]) -> None:
    dropped = sorted(set(metadata) - PRESERVED_METADATA_FIELDS)
    if dropped:
        warnings.append(f"{context} metadata fields were dropped: {', '.join(dropped)}")


def sanitize_name(value: str) -> str:
    if "{{" in value or "}}" in value:
        return value
    value = value.lower()
    value = re.sub(r"[^a-z0-9-]+", "-", value)
    value = re.sub(r"-+", "-", value).strip("-")
    return value or "dbaas-resource"


# --------------------------------------------------------------------------- #
# Canonical YAML serialization
# --------------------------------------------------------------------------- #

try:  # pragma: no cover - optional dependency, present in CI
    import yaml
except Exception:  # noqa: BLE001
    yaml = None  # type: ignore[assignment]


def dump_resources(resources: list[dict[str, Any]]) -> str:
    """Serialize resources as a stable multi-document YAML string.

    One document per resource, keys in insertion order, no line wrapping, so a
    repeated run is byte-for-byte identical.
    """

    chunks: list[str] = []
    for resource in resources:
        if yaml is not None:
            body = yaml.safe_dump(
                resource,
                sort_keys=False,
                allow_unicode=False,
                default_flow_style=False,
                width=1_000_000,
            )
        else:
            body = _dump_yaml(resource)
        chunks.append("---\n" + body.rstrip("\n") + "\n")
    return "".join(chunks)


def _dump_yaml(value: Any, indent: int = 0) -> str:
    spaces = " " * indent
    if isinstance(value, dict):
        lines: list[str] = []
        for key, nested in value.items():
            formatted_key = json.dumps(str(key))
            if isinstance(nested, (dict, list)) and nested:
                lines.append(f"{spaces}{formatted_key}:")
                lines.append(_dump_yaml(nested, indent + 2).rstrip("\n"))
            elif isinstance(nested, (dict, list)):
                lines.append(f"{spaces}{formatted_key}: {'{}' if isinstance(nested, dict) else '[]'}")
            else:
                lines.append(f"{spaces}{formatted_key}: {_format_scalar(nested)}")
        return "\n".join(lines) + "\n"
    if isinstance(value, list):
        lines = []
        for item in value:
            if isinstance(item, (dict, list)) and item:
                lines.append(f"{spaces}-")
                lines.append(_dump_yaml(item, indent + 2).rstrip("\n"))
            elif isinstance(item, (dict, list)):
                lines.append(f"{spaces}- {'{}' if isinstance(item, dict) else '[]'}")
            else:
                lines.append(f"{spaces}- {_format_scalar(item)}")
        return "\n".join(lines) + "\n"
    return f"{spaces}{_format_scalar(value)}\n"


def _format_scalar(value: Any) -> str:
    if value is True:
        return "true"
    if value is False:
        return "false"
    if value is None:
        return "null"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        if math.isnan(value):
            return ".nan"
        if math.isinf(value):
            return ".inf" if value > 0 else "-.inf"
        return repr(value)
    return json.dumps(str(value))
