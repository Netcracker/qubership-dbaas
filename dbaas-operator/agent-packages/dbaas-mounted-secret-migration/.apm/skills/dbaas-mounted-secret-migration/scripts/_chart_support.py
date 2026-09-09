#!/usr/bin/env python3
"""Chart-side support for the mounted-secret migration.

Two responsibilities the runner delegates here:

- ``update_values`` -- register the ``DBAAS_OPERATOR_NAMESPACE`` chart value and
  its (optional) schema entry, but only when the plan templates
  ``spec.operatorNamespace`` from it, with a minimal in-place text edit;
- ``validate_helm_root`` -- render the candidate chart with deterministic values
  and validate the rendered Kubernetes objects, never the raw templates.
"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any

import _migration_common as common
import validate_generated

try:  # common.run() checks this before any work and reports it as a blocked result.
    import yaml
except ImportError:  # pragma: no cover - exercised only without the pinned dependency
    yaml = None  # type: ignore[assignment]

OPERATOR_NAMESPACE_VALUE = "DBAAS_OPERATOR_NAMESPACE"
_OPERATOR_NAMESPACE_SCHEMA = {"type": "string"}

_VALUE_REF = re.compile(r"\{\{-?\s*\.Values\.([A-Za-z0-9_]+)\s*-?\}\}")
# Every ``.Values.<name>`` reference, including one nested inside a larger Helm
# pipeline such as the ``API_DBAAS_ADDRESS``-derived operator-namespace
# expression. Used only to synthesize deterministic ``--set`` values for the
# render probe, never to substitute text.
_ANY_VALUE_REF = re.compile(r"\.Values\.([A-Za-z0-9_]+)")


# --------------------------------------------------------------------------- #
# values.yaml / values.schema.json
# --------------------------------------------------------------------------- #


def update_values(
    repo_root: Path,
    root: str,
    decisions: dict[str, Any],
    operator_namespace: str,
    changes: common.Changes,
) -> None:
    """Register ``DBAAS_OPERATOR_NAMESPACE`` as a chart value -- but only when the
    plan actually templates ``spec.operatorNamespace`` from it.

    When ``inputs.operatorNamespace`` is a literal namespace or any other Helm
    expression (for example one derived from ``API_DBAAS_ADDRESS``), that value is
    written straight into the generated CRs and the chart needs no extra knob, so
    this is a no-op.

    When the plan does reference ``{{ .Values.DBAAS_OPERATOR_NAMESPACE }}``, the
    key is added with an empty default and registered as an *optional* string.
    The deployer supplies the real namespace at install time; leaving it
    non-required with no ``minLength`` keeps ``helm lint`` and ``helm template``
    working against the chart defaults, so a consumer's CI is not broken by the
    migration.
    """

    if OPERATOR_NAMESPACE_VALUE not in _template_value_keys(operator_namespace):
        return

    values_rel = common.join_rel(root, decisions.get("valuesFile", "values.yaml"))
    schema_rel = common.join_rel(root, decisions.get("schemaFile", "values.schema.json"))

    values_path = common.resolve_within(repo_root, values_rel, what="values file")
    if not values_path.is_file():
        raise common.unsupported("values file missing", [f"{values_rel}: file not found"])
    values_text = values_path.read_text(encoding="utf-8")
    if not any(
        line[:1] not in (" ", "\t")
        and line.split(":", 1)[0].rstrip() == OPERATOR_NAMESPACE_VALUE
        for line in values_text.splitlines()
    ):
        suffix = "" if values_text.endswith("\n") else "\n"
        changes.set_content(
            values_rel, f'{values_text}{suffix}{OPERATOR_NAMESPACE_VALUE}: ""\n'
        )

    schema_path = common.resolve_within(repo_root, schema_rel, what="values schema file")
    if not schema_path.is_file():
        raise common.unsupported("values schema missing", [f"{schema_rel}: file not found"])
    schema_text = schema_path.read_text(encoding="utf-8")
    try:
        new_schema_text = _schema_with_optional_operator_namespace(schema_text)
    except ValueError as exc:
        raise common.bad_input(f"{schema_rel}: invalid JSON: {exc}") from None
    if new_schema_text is not None:
        changes.set_content(schema_rel, new_schema_text)


def _schema_with_optional_operator_namespace(text: str) -> str | None:
    """Register ``DBAAS_OPERATOR_NAMESPACE`` as an optional string in a values
    schema, editing the file text in place so an untouched schema keeps its
    formatting. Returns ``None`` when the schema already has the key right, and
    falls back to a full reserialize only when the surgical edit would not
    produce the intended shape."""

    schema = json.loads(text)
    properties = schema.get("properties")
    required = schema.get("required")
    has_property = (
        isinstance(properties, dict)
        and properties.get(OPERATOR_NAMESPACE_VALUE) == _OPERATOR_NAMESPACE_SCHEMA
    )
    in_required = isinstance(required, list) and OPERATOR_NAMESPACE_VALUE in required
    if has_property and not in_required:
        return None

    edited = text
    if not (isinstance(properties, dict) and OPERATOR_NAMESPACE_VALUE in properties):
        edited = _insert_json_object_entry(
            edited, "properties", f'"{OPERATOR_NAMESPACE_VALUE}": {{ "type": "string" }}'
        )
    if in_required:
        edited = _remove_json_array_string(edited, "required", OPERATOR_NAMESPACE_VALUE)

    try:
        parsed = json.loads(edited)
        surgical_ok = (
            isinstance(parsed, dict)
            and (parsed.get("properties") or {}).get(OPERATOR_NAMESPACE_VALUE)
            == _OPERATOR_NAMESPACE_SCHEMA
            and OPERATOR_NAMESPACE_VALUE not in (parsed.get("required") or [])
        )
    except ValueError:
        surgical_ok = False
    if surgical_ok and edited != text:
        return edited

    schema.setdefault("properties", {})[OPERATOR_NAMESPACE_VALUE] = dict(_OPERATOR_NAMESPACE_SCHEMA)
    if isinstance(schema.get("required"), list) and OPERATOR_NAMESPACE_VALUE in schema["required"]:
        schema["required"].remove(OPERATOR_NAMESPACE_VALUE)
    return json.dumps(schema, indent=2) + "\n"


def _match_brace(text: str, open_index: int) -> int:
    depth = 0
    in_str = False
    esc = False
    for index in range(open_index, len(text)):
        char = text[index]
        if in_str:
            if esc:
                esc = False
            elif char == "\\":
                esc = True
            elif char == '"':
                in_str = False
            continue
        if char == '"':
            in_str = True
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index
    return -1


def _insert_json_object_entry(text: str, key: str, entry: str) -> str:
    match = re.search(rf'"{re.escape(key)}"\s*:\s*\{{', text)
    if not match:
        return text
    open_index = match.end() - 1
    close_index = _match_brace(text, open_index)
    if close_index < 0:
        return text
    body = text[open_index + 1:close_index]
    line_start = text.rfind("\n", 0, close_index) + 1
    if line_start > open_index and not text[line_start:close_index].strip():
        # The closing brace sits on its own line: match the sibling indentation.
        close_indent = text[line_start:close_index]
        entry_indent = close_indent + "  "
        stripped = body.rstrip()
        joined = (
            f"{stripped},\n{entry_indent}{entry}\n{close_indent}"
            if stripped
            else f"\n{entry_indent}{entry}\n{close_indent}"
        )
    else:
        # A compact object on one line: stay on one line.
        stripped = body.strip()
        joined = f"{stripped}, {entry}" if stripped else f"{entry}"
        joined = f" {joined} " if body.startswith(" ") or body.endswith(" ") else joined
    return text[:open_index + 1] + joined + text[close_index:]


def _remove_json_array_string(text: str, key: str, value: str) -> str:
    match = re.search(rf'"{re.escape(key)}"\s*:\s*\[([^\]]*)\]', text)
    if not match:
        return text
    items = re.sub(rf'\s*"{re.escape(value)}"\s*,?', "", match.group(1), count=1)
    items = re.sub(r",(\s*)$", r"\1", items)
    items = re.sub(r"^(\s*),", r"\1", items)
    return text[: match.start(1)] + items + text[match.end(1) :]


# --------------------------------------------------------------------------- #
# Helm render + rendered-manifest validation
# --------------------------------------------------------------------------- #


def _template_value_keys(*texts: str) -> set[str]:
    keys: set[str] = set()
    for text in texts:
        keys.update(_ANY_VALUE_REF.findall(text))
    return keys


def _pilot_value(key: str) -> str:
    """A deterministic placeholder for one chart value in the render probe.

    ``API_DBAAS_ADDRESS`` gets a real URL shape so an operator-namespace
    expression that parses it (``splitList "://"`` ... ``index ... 1``) renders to
    a concrete namespace instead of an empty string.
    """

    if key == "API_DBAAS_ADDRESS":
        return "http://dbaas-aggregator.dbaas-operator:8080"
    return f"pilot-{key.lower().replace('_', '-')}"


def _resolve_templates(text: str, values: dict[str, str]) -> str:
    return _VALUE_REF.sub(lambda m: values.get(m.group(1), m.group(0)), text)


def validate_helm_root(
    tree_root: Path,
    root: str,
    decisions: dict[str, Any],
    inventory_text: str,
    operator_namespace: str,
    workload_namespace: str,
) -> list[common.ValidationResult]:
    """Render the candidate chart with deterministic values and validate the
    rendered Kubernetes objects -- never the raw templates."""

    results: list[common.ValidationResult] = []
    uses_ns_value = OPERATOR_NAMESPACE_VALUE in _template_value_keys(operator_namespace)
    if uses_ns_value:
        # The plan templates spec.operatorNamespace from a dedicated chart value:
        # confirm the runner registered it, but as an *optional* string, so the
        # chart still renders with its defaults.
        value_issue = _check_operator_namespace_value(
            tree_root / common.join_rel(root, decisions.get("valuesFile", "values.yaml")),
            tree_root / common.join_rel(root, decisions.get("schemaFile", "values.schema.json")),
        )
        results.append(
            common.ValidationResult(
                "values-operator-namespace",
                "failed" if value_issue else "passed",
                value_issue or "",
            )
        )

    chart_dir = tree_root / root if root else tree_root
    helm = shutil.which("helm")
    if helm is None:
        # A helm root cannot be certified without rendering it. This is a missing
        # dependency (exit 4), not a validation failure (exit 5): raising here lets
        # common.run() report it as a blocked result with the documented exit code.
        raise common.unsupported(
            "helm is required to certify a Helm root",
            ["helm is not on PATH; install it (or run where it is available) and re-run"],
        )
    if not (chart_dir / "Chart.yaml").is_file():
        results.append(
            common.ValidationResult(
                "helm-render", "failed", f"{root or '.'}: not a Helm chart (no Chart.yaml)"
            )
        )
        return results

    value_keys = _template_value_keys(
        inventory_text, operator_namespace, workload_namespace, json.dumps(decisions)
    )
    values = {key: _pilot_value(key) for key in value_keys}
    # An explicit plan.decisions.helmValues entry (a schema-required value with no
    # default, an image tag, ...) overrides a synthesized placeholder -- and must
    # do so everywhere the render is interpreted, not just on the command line:
    # the release namespace, the rendered inventory, and the expected operator
    # namespace are all resolved from this same map.
    values.update(_extra_helm_values(decisions))
    # The resolved workload namespace is the Helm release namespace, so a chart
    # that omits metadata.namespace still renders into it and its resources match
    # claims generated there.
    release_namespace = _resolve_templates(workload_namespace, values) or "dbaas-migration-pilot"
    cmd = [helm, "template", "dbaas-migration-pilot", str(chart_dir), "--namespace", release_namespace]
    for key, value in sorted(values.items()):
        cmd += ["--set", f"{key}={value}"]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=180, check=False)
    except (OSError, subprocess.SubprocessError) as exc:
        results.append(common.ValidationResult("helm-render", "failed", f"helm template did not run: {exc}"))
        return results
    if proc.returncode != 0:
        results.append(
            common.ValidationResult("helm-render", "failed", f"helm template failed: {proc.stderr.strip()[:2000]}")
        )
        return results
    results.append(common.ValidationResult("helm-render", "passed"))

    rendered_path = tree_root / "__rendered.yaml"
    rendered_path.write_text(proc.stdout, encoding="utf-8")
    rendered_inventory = tree_root / "__rendered-inventory.json"
    rendered_inventory.write_text(_resolve_templates(inventory_text, values), encoding="utf-8")
    # Resolve a literal or a whole-value ``{{ .Values.X }}`` operator namespace to
    # the concrete string the chart rendered, so the validator can assert an exact
    # match. A derived expression (``splitList`` ... ) cannot be resolved here, so
    # fall back to the validator's presence-and-non-empty check.
    if "{{" not in operator_namespace:
        expected_operator_ns: str | None = operator_namespace
    else:
        substituted = _resolve_templates(operator_namespace, values)
        expected_operator_ns = substituted if "{{" not in substituted else None
    try:
        errors = validate_generated.validate(
            [rendered_path],
            rendered_inventory,
            expected_operator_ns,
            default_namespace=release_namespace,
        )
    except Exception as exc:  # noqa: BLE001
        errors = [f"validator raised on the rendered chart: {exc}"]
    results.append(
        common.ValidationResult(
            "validate_rendered", "failed" if errors else "passed", "; ".join(errors)
        )
    )
    return results


def _extra_helm_values(decisions: dict[str, Any]) -> dict[str, str]:
    raw = decisions.get("helmValues")
    if not isinstance(raw, dict):
        return {}
    return {str(key): str(value) for key, value in raw.items()}


def _check_operator_namespace_value(values_path: Path, schema_path: Path) -> str | None:
    """When the plan templates ``spec.operatorNamespace`` from the dedicated chart
    value, that value must be a plain top-level string that the chart can render
    with its defaults -- i.e. present, and (if the schema constrains it) an
    *optional* string with no ``minLength``. A required or ``minLength`` entry
    would break ``helm lint`` / ``helm template`` for a consumer that has not set
    the value yet."""

    if not values_path.is_file():
        return f"{values_path.name}: missing after generation"
    try:
        loaded = yaml.safe_load(values_path.read_text(encoding="utf-8"))
    except yaml.YAMLError as exc:
        return f"{values_path.name}: invalid YAML: {exc}"
    if not isinstance(loaded, dict) or OPERATOR_NAMESPACE_VALUE not in loaded:
        return f"{OPERATOR_NAMESPACE_VALUE} is not a top-level key in {values_path.name}"

    if not schema_path.is_file():
        return None  # a chart without a values schema is fine
    try:
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
    except ValueError as exc:
        return f"{schema_path.name}: invalid JSON: {exc}"
    prop = (schema.get("properties") or {}).get(OPERATOR_NAMESPACE_VALUE)
    if prop is not None:
        if not isinstance(prop, dict) or prop.get("type") != "string":
            return f"{OPERATOR_NAMESPACE_VALUE} schema property must be a string"
        if prop.get("minLength"):
            return (
                f"{OPERATOR_NAMESPACE_VALUE} schema property sets minLength, so the chart "
                "will not render with its (empty) default"
            )
    if OPERATOR_NAMESPACE_VALUE in (schema.get("required") or []):
        return (
            f"{OPERATOR_NAMESPACE_VALUE} is in the schema required list, so the chart "
            "will not render with its defaults"
        )
    return None
