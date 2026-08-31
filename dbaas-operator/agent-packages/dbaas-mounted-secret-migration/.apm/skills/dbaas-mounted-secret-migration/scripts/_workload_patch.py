#!/usr/bin/env python3
"""Representation-preserving workload-manifest adapter for the mounted-secret migration.

Supported shapes in the first release:

- plain Kubernetes ``Deployment`` / ``StatefulSet`` YAML;
- Helm ``Deployment`` / ``StatefulSet`` templates whose templating is confined to
  whole scalar values (``key: {{ .Values.x }}``).

The adapter never deserializes and reserializes the whole document. It parses a
sanitized copy only to locate the pod spec, its ``volumes`` list, and the target
containers, then inserts the required ``volumes`` and ``volumeMounts`` nodes as
text at the correct indentation. Every untouched byte -- comments, numeric and
boolean scalars, ``replicas: {{ ... }}``, anchors, key order -- is preserved.

A standalone Helm action (``if`` / ``range`` / ``with`` / ``include`` / ``define``
/ ``block`` / ``template`` / ``end`` / ``else``) or a standalone ``{{ ... }}``
assignment inside the manifest is rejected with a file and line, so the runner
fails closed instead of mangling the template.
"""

from __future__ import annotations

import re
from typing import Any

try:  # The runner checks this dependency before any work and reports it cleanly.
    import yaml
except ImportError:  # pragma: no cover - exercised only without the pinned dependency
    yaml = None  # type: ignore[assignment]

_BLOCK_ACTION = re.compile(
    r"^\s*\{\{-?\s*(if|range|with|include|define|block|template|end|else)\b"
)
_STANDALONE_ACTION = re.compile(r"^\s*\{\{-?.*-?\}\}\s*$")
_WHOLE_VALUE_TEMPLATE = re.compile(
    r"^(?P<prefix>\s*(?:-\s+)?[A-Za-z0-9_.\"'-]+:\s*)(?P<value>\{\{.*\}\})\s*$"
)


class WorkloadError(Exception):
    def __init__(self, entries: list[str]) -> None:
        super().__init__("; ".join(entries))
        self.entries = entries


# --------------------------------------------------------------------------- #
# Public entry point
# --------------------------------------------------------------------------- #


def patch_workloads(text: str, *, filename: str, targets: list[dict[str, Any]]) -> str:
    """Patch one or more workloads in a single manifest file.

    ``targets`` entries: ``{"kind", "name", "mounts": [...]}``. Each mount is
    ``{"volume", "secret", "mountPath", "containers", "initContainers"}``.
    """

    if yaml is None:  # pragma: no cover
        raise WorkloadError([f"{filename}: PyYAML is required to patch workload manifests"])

    _reject_helm_actions(text, filename)
    lines = text.splitlines(keepends=True)

    try:
        nodes = [node for node in yaml.compose_all(_sanitize(text, filename)) if node is not None]
    except yaml.YAMLError as exc:
        raise WorkloadError(
            [f"{filename}: not valid YAML after sanitizing templated scalars: {exc}"]
        ) from None

    # Each edit is (line_index, replace_count, text): replace_count 0 inserts
    # before the line, 1 replaces that one line (used to turn `volumes: []` into a
    # block list). Applied bottom-up so earlier indices stay valid.
    edits: list[tuple[int, int, str]] = []
    problems: list[str] = []
    for target in targets:
        node = _find_workload(nodes, target["kind"], target["name"])
        if node is None:
            problems.append(f"{filename}: no {target['kind']} named {target['name']!r} in the manifest")
            continue
        _plan_edits(node, lines, filename, target, edits, problems)

    if problems:
        raise WorkloadError(problems)

    for line_index, replace_count, chunk in sorted(edits, key=lambda item: -item[0]):
        lines[line_index:line_index + replace_count] = [chunk]
    result = "".join(lines)
    return result if result.endswith("\n") else result + "\n"


# --------------------------------------------------------------------------- #
# Pre-parse guards
# --------------------------------------------------------------------------- #


def _reject_helm_actions(text: str, filename: str) -> None:
    problems: list[str] = []
    for number, line in enumerate(text.splitlines(), start=1):
        if _BLOCK_ACTION.match(line):
            problems.append(
                f"{filename}:{number}: Helm block action is not supported by the first-release "
                f"workload adapter: {line.strip()!r}"
            )
            continue
        if _STANDALONE_ACTION.match(line) and not _WHOLE_VALUE_TEMPLATE.match(line):
            problems.append(
                f"{filename}:{number}: standalone Helm action is not supported by the first-release "
                f"workload adapter: {line.strip()!r}"
            )
    if problems:
        raise WorkloadError(problems)


def _sanitize(text: str, filename: str) -> str:
    """Quote whole-value ``{{ ... }}`` scalars so PyYAML can parse, one line to
    one line so node marks map back to the original text."""

    out: list[str] = []
    for line in text.splitlines():
        match = _WHOLE_VALUE_TEMPLATE.match(line)
        if match:
            value = match.group("value").strip().replace("'", "''")
            out.append(f"{match.group('prefix')}'{value}'")
            continue
        out.append(line)
    return "\n".join(out) + "\n"


# --------------------------------------------------------------------------- #
# Node navigation
# --------------------------------------------------------------------------- #


def _is_mapping(node: Any) -> bool:
    return isinstance(node, yaml.MappingNode)


def _is_sequence(node: Any) -> bool:
    return isinstance(node, yaml.SequenceNode)


def _is_null(node: Any) -> bool:
    return isinstance(node, yaml.ScalarNode) and node.tag == "tag:yaml.org,2002:null"


def _mapping_pairs(node: Any) -> list[tuple[Any, Any]]:
    return list(node.value) if _is_mapping(node) else []


def _mapping_get(node: Any, key: str) -> Any:
    for key_node, value_node in _mapping_pairs(node):
        if isinstance(key_node, yaml.ScalarNode) and key_node.value == key:
            return value_node
    return None


def _key_pair(node: Any, key: str) -> tuple[Any, Any]:
    for key_node, value_node in _mapping_pairs(node):
        if isinstance(key_node, yaml.ScalarNode) and key_node.value == key:
            return key_node, value_node
    return None, None


def _scalar(node: Any) -> Any:
    return node.value if isinstance(node, yaml.ScalarNode) else None


def _walk(node: Any, path: list[str]) -> Any:
    for key in path:
        node = _mapping_get(node, key)
        if node is None:
            return None
    return node


def _find_workload(nodes: list[Any], kind: str, name: str) -> Any:
    for node in nodes:
        if not _is_mapping(node):
            continue
        if _scalar(_mapping_get(node, "kind")) != kind:
            continue
        if _scalar(_walk(node, ["metadata", "name"])) == name:
            return node
    return None


def _indent(text_line: str) -> int:
    return len(text_line) - len(text_line.lstrip(" "))


def _block_content_end(lines: list[str], start_line: int, min_indent: int) -> int:
    """Line index just past a block node's content.

    PyYAML's ``end_mark`` for the last child of a mapping points at the next
    de-indented token, which lands on a later line. Instead, walk forward from
    ``start_line`` over blank lines and lines indented deeper than ``min_indent``
    and stop at the first line that dedents to a sibling or parent.
    """

    index = start_line + 1
    total = len(lines)
    while index < total:
        stripped = lines[index].strip()
        if stripped == "" or _indent(lines[index]) > min_indent:
            index += 1
            continue
        break
    while index - 1 > start_line and lines[index - 1].strip() == "":
        index -= 1
    return index


def _child_key_column(mapping_node: Any) -> int:
    return mapping_node.value[0][0].start_mark.column


def _seq_item_column(seq_node: Any, lines: list[str]) -> int:
    """Column of the ``-`` dash of a block sequence's items, read from the text.

    PyYAML marks a sequence item at its value, not its dash, so the dash column
    is taken from the original line to keep a new item aligned with the rest.
    """

    first_line = seq_node.value[0].start_mark.line
    return _indent(lines[first_line])


# --------------------------------------------------------------------------- #
# Edit planning
# --------------------------------------------------------------------------- #


def _plan_edits(
    node: Any,
    lines: list[str],
    filename: str,
    target: dict[str, Any],
    edits: list[tuple[int, int, str]],
    problems: list[str],
) -> None:
    kind, name = target["kind"], target["name"]
    pod_spec = _walk(node, ["spec", "template", "spec"])
    if pod_spec is None or _is_null(pod_spec) or not _is_mapping(pod_spec):
        problems.append(f"{filename}: {kind}/{name} has no spec.template.spec mapping")
        return

    _plan_volumes(pod_spec, lines, filename, target, edits, problems)
    _plan_mounts(pod_spec, lines, filename, target, edits, problems)


def _plan_seq_key(
    mapping_node: Any,
    key: str,
    lines: list[str],
    new_items: list[Any],
    render_item: Any,
    edits: list[tuple[int, int, str]],
    problems: list[str],
    what: str,
) -> None:
    """Insert rendered ``- ...`` blocks under ``mapping_node[key]``.

    Creates the key as a block list when it is absent or an empty inline ``[]``;
    a null value or a non-empty inline list is a typed error.
    """

    key_node, value_node = _key_pair(mapping_node, key)
    key_col = _child_key_column(mapping_node)

    if value_node is not None and _is_null(value_node):
        problems.append(f"{what} is present but null; give it a block list or remove it")
        return
    if _is_sequence(value_node) and value_node.flow_style:
        if value_node.value:
            problems.append(f"{what} is an inline list; rewrite it as a block list before migrating")
            return
        # `<key>: []` -> replace that one line with a block list.
        block = " " * key_col + f"{key}:\n" + "".join(
            render_item(item, key_col + 2) for item in new_items
        )
        edits.append((key_node.start_mark.line, 1, block))
        return
    if value_node is not None and not _is_sequence(value_node):
        problems.append(f"{what} is not a block list")
        return

    if value_node is None or not value_node.value:
        anchor = (
            key_node.start_mark.line
            if key_node is not None
            else mapping_node.value[-1][0].start_mark.line
        )
        insert_at = _block_content_end(lines, anchor, key_col)
        block = " " * key_col + f"{key}:\n" + "".join(
            render_item(item, key_col + 2) for item in new_items
        )
        edits.append((insert_at, 0, block))
        return

    dash_col = _seq_item_column(value_node, lines)
    insert_at = _block_content_end(lines, value_node.value[-1].start_mark.line, dash_col)
    edits.append((insert_at, 0, "".join(render_item(item, dash_col) for item in new_items)))


def _plan_volumes(
    pod_spec: Any,
    lines: list[str],
    filename: str,
    target: dict[str, Any],
    edits: list[tuple[int, int, str]],
    problems: list[str],
) -> None:
    kind, name = target["kind"], target["name"]
    wanted: list[tuple[str, str]] = []
    seen: set[str] = set()
    for mount in target["mounts"]:
        if mount["volume"] not in seen:
            seen.add(mount["volume"])
            wanted.append((mount["volume"], mount["secret"]))

    volumes_node = _mapping_get(pod_spec, "volumes")
    existing = {
        _scalar(_mapping_get(item, "name"))
        for item in (volumes_node.value if _is_sequence(volumes_node) else [])
    }
    new_items = [(vol, secret) for vol, secret in wanted if vol not in existing]
    if not new_items:
        return

    _plan_seq_key(
        pod_spec, "volumes", lines, new_items,
        lambda item, indent: _volume_item(item[0], item[1], indent),
        edits, problems,
        f"{filename}: {kind}/{name} spec.template.spec.volumes",
    )


def _plan_mounts(
    pod_spec: Any,
    lines: list[str],
    filename: str,
    target: dict[str, Any],
    edits: list[tuple[int, int, str]],
    problems: list[str],
) -> None:
    kind, name = target["kind"], target["name"]
    by_container: dict[tuple[str, str], list[dict[str, Any]]] = {}
    for mount in target["mounts"]:
        for field in ("containers", "initContainers"):
            for container_name in mount.get(field) or []:
                by_container.setdefault((field, container_name), []).append(mount)

    matched: set[tuple[str, str]] = set()
    for field in ("containers", "initContainers"):
        seq = _mapping_get(pod_spec, field)
        if seq is not None and _is_null(seq):
            problems.append(f"{filename}: {kind}/{name} spec.template.spec.{field} is present but null")
            return
        if not _is_sequence(seq):
            continue
        for container in seq.value:
            if not _is_mapping(container):
                problems.append(f"{filename}: {kind}/{name} has a non-mapping entry under {field}")
                return
            container_name = _scalar(_mapping_get(container, "name"))
            key = (field, container_name)
            if key in by_container:
                matched.add(key)
                _plan_container_mounts(container, lines, filename, by_container[key], edits, problems)

    for (field, container_name) in by_container:
        if (field, container_name) not in matched:
            problems.append(
                f"{filename}: {kind}/{name} has no {field[:-1]} named {container_name!r}"
            )


def _plan_container_mounts(
    container: Any,
    lines: list[str],
    filename: str,
    mounts: list[dict[str, Any]],
    edits: list[tuple[int, int, str]],
    problems: list[str],
) -> None:
    container_name = _scalar(_mapping_get(container, "name"))
    mounts_node = _mapping_get(container, "volumeMounts")
    existing = {
        _scalar(_mapping_get(item, "name"))
        for item in (mounts_node.value if _is_sequence(mounts_node) else [])
    }
    new_mounts: list[dict[str, Any]] = []
    added: set[str] = set()
    for mount in mounts:
        if mount["volume"] not in existing and mount["volume"] not in added:
            added.add(mount["volume"])
            new_mounts.append(mount)
    if not new_mounts:
        return  # idempotent

    _plan_seq_key(
        container, "volumeMounts", lines, new_mounts, _mount_item, edits, problems,
        f"{filename}: container {container_name!r} volumeMounts",
    )


# --------------------------------------------------------------------------- #
# Rendering
# --------------------------------------------------------------------------- #


def _volume_item(volume: str, secret: str, indent: int) -> str:
    pad = " " * indent
    inner = " " * (indent + 2)
    return (
        f"{pad}- name: {volume}\n"
        f"{inner}secret:\n"
        f"{inner}  secretName: {secret}\n"
    )


def _mount_item(mount: dict[str, Any], indent: int) -> str:
    pad = " " * indent
    inner = " " * (indent + 2)
    return (
        f"{pad}- name: {mount['volume']}\n"
        f"{inner}mountPath: {mount['mountPath']}\n"
        f"{inner}readOnly: true\n"
    )
