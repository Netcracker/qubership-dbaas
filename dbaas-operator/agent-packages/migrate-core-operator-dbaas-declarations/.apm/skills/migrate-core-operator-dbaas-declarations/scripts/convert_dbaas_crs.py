#!/usr/bin/env python3
"""Convert legacy DBaaS declarations to dedicated dbaas-operator CRDs.

The converter is intentionally conservative. It supports common legacy JSON
inputs with the Python standard library. If PyYAML is installed, it also reads
legacy YAML and uses PyYAML for output formatting.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path
from typing import Any

try:
    import yaml  # type: ignore
except Exception:  # pragma: no cover - optional dependency
    yaml = None


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
    "physicalDatabaseId",
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
# Kubernetes-managed metadata that is never meaningful to carry onto a brand-new
# resource; dropping these needs no warning. Anything else dropped (labels,
# annotations, ...) still warns -- it may carry deployment-relevant information.
SILENTLY_DROPPED_METADATA_FIELDS = {
    "creationTimestamp",
    "resourceVersion",
    "uid",
    "generation",
    "managedFields",
    "selfLink",
    "ownerReferences",
    "finalizers",
}


class TemplatedNameRequired(Exception):
    """A default resource name is templated, or mixes literal text with a Helm
    expression, and no explicit override was supplied.

    Slugging the template source text into one fixed literal would collide
    across every release of the same chart; the caller must supply an
    explicit, release-specific ``override_name`` instead (see
    ``resource_name``).
    """

    def __init__(self, hint: str) -> None:
        super().__init__(f"templated identity requires an explicit target name: {hint!r}")
        self.hint = hint


def is_templated(value: Any) -> bool:
    return isinstance(value, str) and "{{" in value


def is_whole_template(value: str) -> bool:
    """Whether ``value`` is entirely one ``{{ ... }}`` Helm expression, as opposed
    to literal text mixed with one. Only a whole-template value's rendered
    result is the deployer's responsibility to keep DNS-1123-safe; a mixed
    value cannot be checked at all and must never be produced automatically."""

    return re.fullmatch(r"\{\{.*\}\}", value.strip()) is not None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, help="Legacy JSON/YAML file")
    parser.add_argument("--output", required=True, help="Output YAML file")
    parser.add_argument("--service-name")
    parser.add_argument("--namespace", default="{{ .Values.NAMESPACE }}")
    parser.add_argument(
        "--operator-namespace",
        required=True,
        help="Namespace of the dbaas-operator instance that will manage the generated resources",
    )
    parser.add_argument("--name-prefix", default="")
    args = parser.parse_args()
    # argparse's required=True still accepts an empty string. spec.operatorNamespace is
    # required and MinLength=1 in the CRDs, so an empty value would emit a CR the API
    # server rejects; fail here with a clear message instead.
    if not args.operator_namespace.strip():
        parser.error("--operator-namespace must not be empty")
    args.service_name_explicit = args.service_name is not None
    args.service_name = args.service_name or "{{ .Values.SERVICE_NAME }}"

    source = Path(args.input)
    warnings: list[str] = []
    errors: list[str] = []
    docs = load_documents(source, warnings)
    resources: list[dict[str, Any]] = []

    try:
        for doc_index, doc in enumerate(docs, start=1):
            if doc is None:
                continue
            for item_index, item in enumerate(as_legacy_items(doc), start=1):
                resources.extend(convert_item(item, doc_index, item_index, args, warnings, errors))
    except TemplatedNameRequired as exc:
        raise SystemExit(
            f"{exc}. This CLI has no per-item name override; either give the source resource a "
            "concrete metadata.name, or migrate it through the package writer (apply_migration.py) "
            "with an explicit, release-specific plan name instead."
        ) from None

    if not resources:
        raise SystemExit("No supported DBaaS declarations found")

    reject_duplicate_resources(resources, errors)
    for warning in warnings:
        print(f"WARNING: {warning}", file=sys.stderr)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(
            f"Conversion failed with {len(errors)} error(s); the output file was not written"
        )
    Path(args.output).write_text(dump_yaml_documents(resources), encoding="utf-8")
    print(f"Wrote {len(resources)} resource(s) to {args.output}", file=sys.stderr)
    return 0


def reject_json_constant(value: str) -> Any:
    raise ValueError(f"numeric constant {value!r} is not valid JSON")


def load_documents(path: Path, warnings: list[str]) -> list[Any]:
    text = path.read_text(encoding="utf-8-sig")
    suffix = path.suffix.lower()
    if suffix == ".json":
        try:
            data = json.loads(text, parse_constant=reject_json_constant)
        except ValueError as exc:
            raise SystemExit(f"{path}: invalid JSON: {exc}") from None
        return data if isinstance(data, list) else [data]
    if yaml is None:
        raise SystemExit("YAML input requires PyYAML. Install PyYAML or convert the source to JSON first.")
    try:
        return list(yaml.safe_load_all(text))
    except yaml.YAMLError:
        warnings.append(
            "YAML required Helm-template sanitization; standalone template actions were commented and "
            "conditional semantics must be reviewed manually"
        )
        return list(yaml.safe_load_all(sanitize_helm_yaml(text)))


def sanitize_helm_yaml(text: str) -> str:
    """Make common Helm-template YAML parseable enough for migration.

    Comment standalone template actions and quote scalar template expressions.
    This makes common chart declarations parseable, but can remove conditional
    semantics; load_documents emits a warning whenever this fallback is used.
    """

    sanitized: list[str] = []
    scalar_with_template = re.compile(r"^(\s*[^#\n][^:]*:\s*)(.*\{\{.*\}\}.*)$")
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("{{") and ":" not in stripped:
            sanitized.append(f"{line[: len(line) - len(line.lstrip())]}# {stripped}")
            continue
        match = scalar_with_template.match(line)
        if match:
            prefix, value = match.groups()
            bare_value = value.strip()
            if not (bare_value.startswith("'") and bare_value.endswith("'")):
                escaped = bare_value.strip('"').replace("'", "''")
                sanitized.append(f"{prefix}'{escaped}'")
                continue
        sanitized.append(line)
    return "\n".join(sanitized) + "\n"


def as_legacy_items(doc: Any) -> list[dict[str, Any]]:
    if isinstance(doc, list):
        return [item for item in doc if isinstance(item, dict)]
    if isinstance(doc, dict):
        return [doc]
    return []


def convert_item(
    item: dict[str, Any],
    doc_index: int,
    item_index: int,
    args: argparse.Namespace,
    warnings: list[str],
    errors: list[str],
) -> list[dict[str, Any]]:
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

    reject_dropped_metadata(metadata, f"Document {doc_index}", errors)

    if legacy_kind_lower == "databasedeclaration":
        declarations = body.get("declarations")
        if declarations is None:
            # No declarations[] wrapper: the document itself is the one
            # declaration, but it still carries envelope fields (apiVersion,
            # kind, metadata) that DATABASE_DECLARATION_FIELDS was never
            # meant to validate -- those belong to the document, not the
            # declaration body, and would otherwise be rejected as unknown.
            declarations = [
                {k: v for k, v in body.items() if k not in ("apiVersion", "kind", "subKind", "metadata")}
            ]
        if not isinstance(declarations, list):
            warnings.append(f"Document {doc_index}: DatabaseDeclaration.declarations is not a list")
            return []
        resources = []
        for declaration_index, declaration in enumerate(declarations, start=1):
            if not isinstance(declaration, dict):
                warnings.append(f"Document {doc_index}: skipped non-object declaration #{declaration_index}")
                continue
            resources.append(
                convert_database_declaration(
                    declaration,
                    metadata,
                    doc_index,
                    declaration_index,
                    len(declarations) > 1,
                    args,
                    warnings,
                    errors,
                )
            )
        return resources

    if legacy_kind_lower == "dbpolicy":
        return [convert_db_policy(body, metadata, doc_index, item_index, args, warnings, errors)]

    warnings.append(f"Document {doc_index}: skipped unsupported kind/subKind {legacy_kind!r}")
    return []


def convert_database_declaration(
    declaration: dict[str, Any],
    old_metadata: dict[str, Any],
    doc_index: int,
    declaration_index: int,
    multiple_declarations: bool,
    args: argparse.Namespace,
    warnings: list[str],
    errors: list[str],
    override_name: str | None = None,
) -> dict[str, Any]:
    reject_unknown_fields(
        declaration,
        DATABASE_DECLARATION_FIELDS,
        f"DatabaseDeclaration #{declaration_index}",
        errors,
    )
    classifier_config = declaration.get("classifierConfig") or {}
    classifier = classifier_config.get("classifier") if isinstance(classifier_config, dict) else None
    if not isinstance(classifier, dict):
        classifier = {}
        errors.append(f"DatabaseDeclaration #{declaration_index}: missing classifierConfig.classifier")
    default_name = database_name_hint(declaration, classifier, doc_index, declaration_index)
    metadata = target_metadata(
        old_metadata,
        args,
        default_name,
        doc_index,
        declaration_index,
        disambiguate_parent=multiple_declarations,
        override_name=override_name,
    )
    target_classifier = convert_classifier(
        classifier, args.service_name, errors, f"DatabaseDeclaration #{declaration_index} classifier"
    )
    legacy_namespace = target_classifier.pop("namespace", None)
    if legacy_namespace not in (None, "", metadata["namespace"]):
        warnings.append(
            f"InternalDatabase {metadata['name']} classifier.namespace {legacy_namespace!r} differs from "
            f"metadata.namespace {metadata['namespace']!r}; omitted classifier.namespace so the operator derives it"
        )

    spec: dict[str, Any] = {
        "operatorNamespace": args.operator_namespace,
        "classifier": target_classifier,
    }

    for field in ("type", "namePrefix", "physicalDatabaseId", "versioningConfig", "initialInstantiation"):
        if field in declaration:
            spec[field] = convert_nested_classifiers(
                declaration[field], args.service_name, errors, f"DatabaseDeclaration #{declaration_index}.{field}"
            )

    if "lazy" in declaration:
        coerced = coerce_bool(declaration["lazy"])
        if not isinstance(coerced, bool):
            errors.append(
                f"InternalDatabase {metadata['name']} has non-boolean lazy {declaration['lazy']!r}; "
                "use true or false"
            )
        else:
            spec["lazy"] = coerced

    if "settings" in declaration:
        settings = declaration["settings"]
        spec["settings"] = settings
        if not isinstance(settings, dict):
            errors.append(
                f"InternalDatabase {metadata['name']} has invalid settings at settings: expected an object"
            )
        else:
            for path, reason in json_value_errors(settings, "settings"):
                errors.append(
                    f"InternalDatabase {metadata['name']} has invalid JSON value at {path}: {reason}"
                )

    target_classifier = spec["classifier"]
    for required_key in ("microserviceName", "scope"):
        if not target_classifier.get(required_key):
            warnings.append(f"InternalDatabase {default_name} is missing classifier.{required_key}")
    if not spec.get("type"):
        warnings.append(f"InternalDatabase {default_name} is missing type")

    if spec.get("lazy") is True:
        initial = spec.get("initialInstantiation")
        if isinstance(initial, dict) and initial.get("approach") == "clone":
            warnings.append("lazy=true with initialInstantiation.approach=clone is invalid")
    initial = spec.get("initialInstantiation")
    if isinstance(initial, dict) and initial.get("approach") == "clone" and "sourceClassifier" not in initial:
        warnings.append("initialInstantiation.approach=clone requires sourceClassifier")
    validate_source_classifier_owner(spec, default_name, warnings)

    return {
        "apiVersion": "dbaas.netcracker.com/v1",
        "kind": "InternalDatabase",
        "metadata": metadata,
        "spec": spec,
    }


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
            errors.extend(json_value_errors(item, json_child_path(path, key)))
        return errors
    return [(path, f"{type(value).__name__} values are not valid JSON")]


def json_child_path(path: str, key: str) -> str:
    if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_-]*", key):
        return f"{path}.{key}"
    return f"{path}[{json.dumps(key, ensure_ascii=False)}]"


def validate_source_classifier_owner(
    spec: dict[str, Any],
    resource_name_hint: str,
    warnings: list[str],
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
        warnings.append(
            f"InternalDatabase {resource_name_hint} sourceClassifier.microserviceName must match "
            "classifier.microserviceName; cross-service clones are invalid"
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
        return f"{scope}-{logical_name}-db"
    if classifier.get("transactional") is True:
        return f"{scope}-transactional-db"
    db_type = declaration.get("type")
    if db_type:
        return f"{scope}-{db_type}-db"
    return f"internaldatabase-{doc_index}-{declaration_index}"


def convert_db_policy(
    body: dict[str, Any],
    old_metadata: dict[str, Any],
    doc_index: int,
    item_index: int,
    args: argparse.Namespace,
    warnings: list[str],
    errors: list[str],
    override_name: str | None = None,
) -> dict[str, Any]:
    reject_unknown_fields(body, DB_POLICY_FIELDS, "DatabaseAccessPolicy", errors)
    source_microservice_name = body.get("microserviceName") or label_value(
        old_metadata, "app.kubernetes.io/instance"
    )
    if getattr(args, "service_name_explicit", False):
        microservice_name = args.service_name
    else:
        microservice_name = source_microservice_name or args.service_name
    if not source_microservice_name and not getattr(args, "service_name_explicit", False):
        warnings.append(
            "DatabaseAccessPolicy.spec.microserviceName uses the --service-name fallback; "
            "verify it against the owning service"
        )
    if not microservice_name:
        errors.append("DatabaseAccessPolicy.spec.microserviceName could not be derived")
        microservice_name = "TODO-service-name"

    spec: dict[str, Any] = {
        "operatorNamespace": args.operator_namespace,
        "microserviceName": normalize_service_template(str(microservice_name), args.service_name),
    }
    for field in ("services", "policy"):
        if field in body:
            spec[field] = body[field]
    if "disableGlobalPermissions" in body:
        coerced = coerce_bool(body["disableGlobalPermissions"])
        if not isinstance(coerced, bool):
            errors.append(
                f"DatabaseAccessPolicy.spec.disableGlobalPermissions {body['disableGlobalPermissions']!r} "
                "is not a boolean"
            )
        else:
            spec["disableGlobalPermissions"] = coerced

    if not spec.get("services") and not spec.get("policy"):
        errors.append("DatabaseAccessPolicy must have a non-empty services or policy list")

    return {
        "apiVersion": "dbaas.netcracker.com/v1",
        "kind": "DatabaseAccessPolicy",
        "metadata": target_metadata(
            old_metadata, args, "database-access-policy", doc_index, item_index, override_name=override_name
        ),
        "spec": spec,
    }


def convert_nested_classifiers(value: Any, service_name: str, errors: list[str], context: str) -> Any:
    if isinstance(value, dict):
        converted = {}
        for key, nested in value.items():
            if key == "sourceClassifier" and isinstance(nested, dict):
                converted[key] = convert_classifier(nested, service_name, errors, f"{context}.sourceClassifier")
            else:
                converted[key] = convert_nested_classifiers(nested, service_name, errors, context)
        return converted
    if isinstance(value, list):
        return [convert_nested_classifiers(item, service_name, errors, context) for item in value]
    return value


def convert_classifier(
    classifier: dict[str, Any], service_name: str, errors: list[str], context: str
) -> dict[str, Any]:
    # The wire-form "extraKeys" is a target-CR concept this converter
    # introduces; a legacy classifier that already uses that literal key name
    # would silently collide with it below -- reject rather than guess which
    # one wins.
    if "extraKeys" in classifier:
        errors.append(f"{context} already has a literal 'extraKeys' key; rename it before migrating")
    converted: dict[str, Any] = {}
    extra_keys: dict[str, Any] = {}
    for key, value in classifier.items():
        if key == "extraKeys":
            continue
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


def target_metadata(
    old_metadata: dict[str, Any],
    args: argparse.Namespace,
    default_prefix: str,
    doc_index: int,
    item_index: int,
    disambiguate_parent: bool = False,
    override_name: str | None = None,
) -> dict[str, Any]:
    return {
        "name": resource_name(
            old_metadata,
            args,
            default_prefix,
            doc_index,
            item_index,
            disambiguate_parent=disambiguate_parent,
            override_name=override_name,
        ),
        "namespace": old_metadata.get("namespace") or args.namespace,
    }


def resource_name(
    old_metadata: dict[str, Any],
    args: argparse.Namespace,
    default_prefix: str,
    doc_index: int,
    item_index: int,
    disambiguate_parent: bool = False,
    override_name: str | None = None,
) -> str:
    if override_name is not None:
        # A caller-supplied override (e.g. a plan's nameOverrides entry) is
        # deliberately constructed, unlike an auto-derived default -- it may
        # freely embed a Helm expression (typically ".Release.Name", for a
        # release-specific name) alongside literal text; the whole-template
        # restriction below exists only to stop *automatically assembling*
        # such a mix from untrusted pieces. An override's rendered value is
        # instead checked by the caller's render-time DNS-1123 validation.
        return override_name if is_templated(override_name) else sanitize_name(override_name)

    old_name = old_metadata.get("name")
    if old_name:
        name = f"{old_name}-{item_index}" if disambiguate_parent else str(old_name)
    else:
        prefix = args.name_prefix or default_prefix
        name = f"{prefix}-{doc_index}-{item_index}"

    if is_templated(name):
        if is_whole_template(name):
            return name
        # A templated scope/logical name mixed with literal text (e.g. the
        # "-2" multi-declaration suffix, or database_name_hint's own
        # "<scope>-<type>-db" shape) cannot be checked for a valid rendered
        # name and must never be produced automatically.
        raise TemplatedNameRequired(name)
    return sanitize_name(name)


def label_value(metadata: dict[str, Any], key: str) -> Any:
    labels = metadata.get("labels")
    if isinstance(labels, dict):
        return labels.get(key)
    return None


def reject_unknown_fields(
    source: dict[str, Any],
    known_fields: set[str],
    context: str,
    errors: list[str],
) -> None:
    # An unrecognized field on a legacy declaration/policy is exactly the
    # silent-data-loss risk this converter exists to close -- block instead
    # of dropping it with a warning the caller can ignore.
    unknown = sorted(set(source) - known_fields)
    if unknown:
        errors.append(f"{context} has unsupported fields that would be dropped: {', '.join(unknown)}")


def reject_dropped_metadata(metadata: dict[str, Any], context: str, errors: list[str]) -> None:
    # Kubernetes-managed fields are silently safe to drop; anything else
    # (labels, annotations, ...) may carry deployment-relevant information
    # this converter has no mapping for. A warning the caller can ignore
    # still lets --apply delete the source, so this blocks instead --
    # consistent with the package's fail-closed rule that ambiguity never
    # gets resolved by a warning acknowledgement.
    dropped = sorted(
        set(metadata) - PRESERVED_METADATA_FIELDS - SILENTLY_DROPPED_METADATA_FIELDS
    )
    if dropped:
        errors.append(f"{context} metadata fields would be dropped: {', '.join(dropped)}")


def reject_duplicate_resources(
    resources: list[dict[str, Any]], errors: list[str], *, root: str = ""
) -> None:
    # Called once per output root by a multi-root caller, so two independent
    # roots may legitimately reuse the same generated name -- only a
    # collision within one root's own resource list is rejected. A duplicate
    # identity would silently overwrite one CR with another when applied, so
    # this blocks instead of warning.
    seen: set[tuple[str, str, str]] = set()
    prefix = f"{root}: " if root else ""
    for resource in resources:
        metadata = resource.get("metadata") or {}
        identity = (
            str(resource.get("kind") or ""),
            str(metadata.get("namespace") or ""),
            str(metadata.get("name") or ""),
        )
        if identity in seen:
            errors.append(
                f"{prefix}duplicate generated resource kind={identity[0]} "
                f"namespace={identity[1]} name={identity[2]}"
            )
        seen.add(identity)


def sanitize_name(value: str) -> str:
    # Callers resolve templating (whole-template passthrough vs
    # TemplatedNameRequired for a mixed value) before ever reaching here; this
    # function only slugs a fully concrete name.
    value = value.lower()
    value = re.sub(r"[^a-z0-9-]+", "-", value)
    value = re.sub(r"-+", "-", value).strip("-")
    return value or "dbaas-resource"


def dump_yaml_documents(resources: list[dict[str, Any]]) -> str:
    if yaml is not None:
        return "---\n" + "---\n".join(
            yaml.safe_dump(resource, sort_keys=False, allow_unicode=False) for resource in resources
        )
    return "---\n" + "---\n".join(dump_yaml(resource) for resource in resources)


def dump_yaml(value: Any, indent: int = 0) -> str:
    spaces = " " * indent
    if isinstance(value, dict):
        lines: list[str] = []
        for key, nested in value.items():
            formatted_key = json.dumps(str(key))
            if isinstance(nested, (dict, list)):
                if nested:
                    lines.append(f"{spaces}{formatted_key}:")
                    lines.append(dump_yaml(nested, indent + 2).rstrip("\n"))
                else:
                    empty_value = "{}" if isinstance(nested, dict) else "[]"
                    lines.append(f"{spaces}{formatted_key}: {empty_value}")
            else:
                lines.append(f"{spaces}{formatted_key}: {format_scalar(nested)}")
        return "\n".join(lines) + "\n"
    if isinstance(value, list):
        lines = []
        for item in value:
            if isinstance(item, (dict, list)):
                if item:
                    lines.append(f"{spaces}-")
                    lines.append(dump_yaml(item, indent + 2).rstrip("\n"))
                else:
                    empty_value = "{}" if isinstance(item, dict) else "[]"
                    lines.append(f"{spaces}- {empty_value}")
            else:
                lines.append(f"{spaces}- {format_scalar(item)}")
        return "\n".join(lines) + "\n"
    return f"{spaces}{format_scalar(value)}\n"


def format_scalar(value: Any) -> str:
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
        text = repr(value)
        if "e" in text.lower():
            mantissa, exponent = re.split(r"[eE]", text, maxsplit=1)
            if "." not in mantissa:
                mantissa += ".0"
            return f"{mantissa}e{exponent}"
        return text
    return json.dumps(str(value))


if __name__ == "__main__":
    raise SystemExit(main())
