#!/usr/bin/env python3
"""Deterministic writer for the Core Operator DBaaS declaration migration.

The skill builds a plan (outside the consumer repository) and calls this
script exactly once as the only process allowed to create, modify, or delete
consumer files. This is package-local: it shares a CLI/result *contract* with
the mounted-secret package's writer, not code.

    apply_migration.py --repo-root PATH --plan PLAN.json --check|--apply

Exit codes: 0 valid/changed/unchanged, 2 invalid plan, 3 a source changed
since the plan was built, 4 unsupported input or a missing dependency (helm),
5 the generated output failed validation. One JSON result envelope goes to
stdout; nothing is ever written to a report file.

Plan shape::

    {
      "schemaVersion": 1,
      "roots": [
        {
          "root": "chart",
          "kind": "helm",
          "operatorNamespace": "dbaas-system",
          "serviceName": "{{ .Values.SERVICE_NAME }}",
          "namespace": "{{ .Values.NAMESPACE }}",
          "namePrefix": "",
          "helmValues": {"SERVICE_NAME": "orders", "NAMESPACE": "orders-ns"},
          "outputFile": "templates/dbaas-operator-resources.yaml",
          "sources": [{"path": "chart/templates/dbaas-configuration.json", "sha256": "<hash>"}],
          "nameOverrides": {"chart/templates/dbaas-configuration.json#1#1#1": "orders-db"},
          "capabilityGuard": "dbaas.netcracker.com/v1"
        }
      ]
    }

A source is matched against the plan's recorded SHA-256 before it is read.
Every supported item in a source is converted; a source is deleted only when
every item in it converted successfully -- unless ``capabilityGuard`` (optional;
omitted above by default) is set, in which case it is preserved instead, guarded
to the operator-absent branch (see references/mapping.md's "Capability guard"
section). A ``nameOverrides`` key addresses one generated resource as
``<source path>#<doc index>#<item index>#<declaration index>`` (all 1-based) and
is required whenever that resource's default name would be templated or would
mix literal text with a Helm expression.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

import convert_dbaas_crs as convert  # noqa: E402

try:
    import yaml
except ImportError:  # pragma: no cover - exercised only without the pinned dependency
    yaml = None  # type: ignore[assignment]

EXIT_OK = 0
EXIT_BAD_PLAN = 2
EXIT_STALE_SOURCE = 3
EXIT_UNSUPPORTED = 4
EXIT_VALIDATION = 5

_GUARD_IF = re.compile(r"^\s*\{\{-?\s*if\b.*-?\}\}\s*$")
_GUARD_END = re.compile(r"^\s*\{\{-?\s*end\s*-?\}\}\s*$")
_ANY_VALUE_REF = re.compile(r"\.Values\.([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*)")

# A standalone Helm action: the whole line, once stripped, is one (or more
# concatenated) {{ ... }} action(s) and nothing else -- if/else/range/with/end/
# define/block/template, or a "{{- $x := ... }}" variable assignment.
_STANDALONE_HELM_LINE = re.compile(r"^\{\{-?.*-?\}\}$", re.S)
# A non-nested {{ ... }} expression, matched non-greedily so two separate
# expressions on one line ("{{ .A }}-{{ .B }}") are each matched on their own.
_INLINE_TEMPLATE = re.compile(r"\{\{(?:(?!\{\{|\}\}).)*\}\}", re.S)
# A mapping-entry scalar whose value contains a template: "key: {{ .X }}" or
# "- key: {{ .X }}" (a sequence item that is itself a one-line mapping).
_SCALAR_WITH_TEMPLATE = re.compile(r"^(\s*[^#\n][^:]*:\s*)(.*\{\{.*\}\}.*)$")
# A bare sequence-item scalar with no mapping key at all: "- {{ .X }}".
_SEQ_ITEM_WITH_TEMPLATE = re.compile(r"^(\s*-\s+)(\{\{.*\}\}.*)$")

_ROOT_KEYS = {
    "root", "kind", "operatorNamespace", "serviceName", "namespace",
    "namePrefix", "helmValues", "outputFile", "outputSha256", "sources", "nameOverrides",
    "capabilityGuard",
}


def _capability_guard_open(capability_guard: str) -> str:
    return '{{- if .Capabilities.APIVersions.Has "' + capability_guard + '" }}\n'


def _negated_guard_open(capability_guard: str) -> str:
    return '{{- if not (.Capabilities.APIVersions.Has "' + capability_guard + '") }}\n'


_CAPABILITY_GUARD_CLOSE = "{{- end }}\n"


def _wrap_in_negated_guard(capability_guard: str, span: str) -> str:
    # issue #776: preserve a superseded legacy declaration's bytes -- comments
    # and labels included -- verbatim, guarded to render only in the
    # operator-absent fallback branch, instead of deleting it.
    body = span if span.endswith("\n") else span + "\n"
    return _negated_guard_open(capability_guard) + body + _CAPABILITY_GUARD_CLOSE
_SOURCE_KEYS = {"path", "sha256"}
_SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")


class PlanError(Exception):
    pass


class StaleSourceError(Exception):
    pass


class UnsupportedError(Exception):
    pass


class ValidationFailure(Exception):
    def __init__(self, problems: list[str]) -> None:
        super().__init__("; ".join(problems))
        self.problems = problems


# --------------------------------------------------------------------------- #
# Path safety
# --------------------------------------------------------------------------- #


def canonical_path(value: Any, what: str) -> str:
    if not isinstance(value, str) or not value:
        raise PlanError(f"{what} must be a non-empty string")
    normalized = value.replace("\\", "/")
    # Checked on the normalized value, not the raw one: a UNC path
    # ("\\server\share") only starts with "/" once its backslashes are
    # normalized, so checking the raw value here would let it slip through
    # as an apparently harmless relative path.
    if normalized.startswith("/") or (len(normalized) > 1 and normalized[1] == ":"):
        raise PlanError(f"{what} must be a repository-relative path with no '..': {value!r}")
    parts = [p for p in normalized.split("/") if p not in ("", ".")]
    if ".." in parts:
        raise PlanError(f"{what} must be a repository-relative path with no '..': {value!r}")
    if not parts:
        raise PlanError(f"{what} must not be empty: {value!r}")
    return "/".join(parts)


def resolve_within(repo_root: Path, rel: str, what: str) -> Path:
    candidate = (repo_root / rel).resolve()
    root = repo_root.resolve()
    try:
        candidate.relative_to(root)
    except ValueError:
        raise PlanError(f"{what} escapes the repository root: {rel!r}") from None
    walk = root
    for part in Path(rel).parts:
        walk = walk / part
        if walk.is_symlink():
            raise PlanError(f"{what} traverses a symlink at {rel!r}")
    return candidate


# --------------------------------------------------------------------------- #
# Plan loading
# --------------------------------------------------------------------------- #


def load_plan(plan_path: Path) -> dict[str, Any]:
    try:
        raw = json.loads(plan_path.read_text(encoding="utf-8"), parse_constant=convert.reject_json_constant)
    except (OSError, ValueError) as exc:
        raise PlanError(f"cannot read plan: {exc}") from None
    if not isinstance(raw, dict):
        raise PlanError("plan must be a JSON object")
    unknown = sorted(set(raw) - {"schemaVersion", "roots"})
    if unknown:
        raise PlanError(f"plan has unknown properties: {', '.join(unknown)}")
    if raw.get("schemaVersion") != 1:
        raise PlanError(f"plan.schemaVersion must be 1, got {raw.get('schemaVersion')!r}")
    roots = raw.get("roots")
    if not isinstance(roots, list) or not roots:
        raise PlanError("plan.roots must be a non-empty array")

    output_paths: set[str] = set()
    source_paths: set[str] = set()
    seen_roots: set[str] = set()
    seen_namespaces: set[str] = set()
    normalized_roots = []
    for index, root in enumerate(roots):
        where = f"plan.roots[{index}]"
        if not isinstance(root, dict):
            raise PlanError(f"{where} must be an object")
        unknown = sorted(set(root) - _ROOT_KEYS)
        if unknown:
            raise PlanError(f"{where} has unknown properties: {', '.join(unknown)}")
        norm = dict(root)
        if "root" not in root:
            raise PlanError(f"{where}.root is required (use \"\" or \".\" for the repository root)")
        root_path = root["root"]
        if not isinstance(root_path, str):
            raise PlanError(f"{where}.root must be a string")
        norm["root"] = "" if root_path in ("", ".") else canonical_path(root_path, f"{where}.root")
        # Two plan entries for the same physical root would each be validated (and, for a
        # helm root, rendered) independently -- neither entry's duplicate-object check would
        # ever see the other's generated resources, even though both land in the same chart
        # once applied. One entry per root closes that blind spot.
        if norm["root"] in seen_roots:
            raise PlanError(f"{where}.root {root['root']!r} is declared more than once")
        seen_roots.add(norm["root"])
        if root.get("kind") not in ("helm", "plain"):
            raise PlanError(f"{where}.kind must be 'helm' or 'plain'")
        for key in ("operatorNamespace", "serviceName", "namespace"):
            if not isinstance(root.get(key), str) or not root[key].strip():
                raise PlanError(f"{where}.{key} is required and must be a non-empty string")
        if root["namespace"] in seen_namespaces:
            raise PlanError(
                f"{where}.namespace {root['namespace']!r} is shared by another root; "
                "use one root per target namespace so duplicate resources are checked together"
            )
        seen_namespaces.add(root["namespace"])
        if "namePrefix" in root and not isinstance(root["namePrefix"], str):
            raise PlanError(f"{where}.namePrefix must be a string")
        # capabilityGuard is optional (issue #776): omitting it preserves the
        # existing operator-only behavior exactly. When present, the
        # generated resource is wrapped in a ".Capabilities.APIVersions.Has"
        # guard and the superseded source document is preserved -- guarded to
        # the operator-absent branch -- instead of deleted (see build_root).
        if "capabilityGuard" in root and (
            not isinstance(root["capabilityGuard"], str) or not root["capabilityGuard"].strip()
        ):
            raise PlanError(f"{where}.capabilityGuard must be a non-empty string")
        for field in ("helmValues", "nameOverrides"):
            value = root.get(field)
            if value is None:
                continue
            if not isinstance(value, dict) or not all(isinstance(k, str) and isinstance(v, str) for k, v in value.items()):
                raise PlanError(f"{where}.{field} must be an object with string keys and string values")
        sources = root.get("sources")
        if not isinstance(sources, list) or not sources:
            raise PlanError(f"{where}.sources must be a non-empty array")
        norm_sources = []
        for s_index, source in enumerate(sources):
            s_where = f"{where}.sources[{s_index}]"
            if not isinstance(source, dict):
                raise PlanError(f"{s_where} must be an object")
            unknown = sorted(set(source) - _SOURCE_KEYS)
            if unknown:
                raise PlanError(f"{s_where} has unknown properties: {', '.join(unknown)}")
            path = canonical_path(source.get("path"), f"{s_where}.path")
            sha256 = source.get("sha256")
            if not isinstance(sha256, str) or _SHA256.fullmatch(sha256) is None:
                raise PlanError(f"{s_where}.sha256 must be a 64-character hex digest")
            if path in source_paths:
                raise PlanError(f"source {path!r} is listed more than once across plan.roots")
            source_paths.add(path)
            norm_sources.append({"path": path, "sha256": sha256.lower()})
        norm["sources"] = norm_sources
        output_rel = canonical_path(root.get("outputFile"), f"{where}.outputFile") if root.get("outputFile") else None
        if output_rel is None:
            raise PlanError(f"{where}.outputFile is required")
        full_output = f"{norm['root']}/{output_rel}" if norm["root"] else output_rel
        if full_output in output_paths:
            raise PlanError(f"output path {full_output!r} is used by more than one root")
        output_paths.add(full_output)
        norm["outputFile"] = full_output
        output_sha256 = root.get("outputSha256")
        if output_sha256 is not None and (
            not isinstance(output_sha256, str) or _SHA256.fullmatch(output_sha256) is None
        ):
            raise PlanError(f"{where}.outputSha256 must be a 64-character hex digest or null")
        norm["outputSha256"] = output_sha256.lower() if output_sha256 is not None else None
        normalized_roots.append(norm)

    if output_paths & source_paths:
        raise PlanError(f"a generated output is also a migration source: {sorted(output_paths & source_paths)}")
    return {"roots": normalized_roots}


def check_source_hashes(repo_root: Path, plan: dict[str, Any]) -> None:
    stale = []
    for root in plan["roots"]:
        for source in root["sources"]:
            target = resolve_within(repo_root, source["path"], "source path")
            if not target.is_file():
                stale.append(f"{source['path']}: source file is missing")
                continue
            actual = hashlib.sha256(target.read_bytes()).hexdigest()
            if actual != source["sha256"]:
                stale.append(f"{source['path']}: sha256 changed since the plan was built")
    if stale:
        raise StaleSourceError("; ".join(stale))


def check_output_ownership(repo_root: Path, plan: dict[str, Any]) -> None:
    """An existing output file must be either absent or proven owned before
    the writer is allowed to replace its content -- unconditionally
    assigning generated content to an output path has no concept of "this
    file already exists and I do not know what it is"."""

    problems: list[str] = []
    stale: list[str] = []
    for root in plan["roots"]:
        target = resolve_within(repo_root, root["outputFile"], "output path")
        if not target.is_file():
            continue
        expected = root.get("outputSha256")
        if expected is None:
            problems.append(
                f"{root['outputFile']}: file already exists and the plan does not set "
                "outputSha256 for this root; record the file's current sha256 to prove it was "
                "accounted for, or remove it first"
            )
            continue
        actual = hashlib.sha256(target.read_bytes()).hexdigest()
        if actual != expected:
            stale.append(f"{root['outputFile']}: expected sha256 {expected}, found {actual}")
    if problems:
        raise UnsupportedError("; ".join(problems))
    if stale:
        raise StaleSourceError("; ".join(stale))


# --------------------------------------------------------------------------- #
# Source parsing -- reuses convert_dbaas_crs's document/item logic, adding
# only the exact-span and whole-document-guard tracking that module has no
# reason to carry for its own single-shot CLI use.
# --------------------------------------------------------------------------- #


def _mask_for_spans(text: str) -> str:
    """Length- and line-preserving mask used only so ``yaml.compose_all`` can
    locate each document's span (issue #776): a standalone Helm action line
    (``if``/``else``/``range``/``with``/``end``/``define``/``block``/
    ``template``, or a ``{{- $x := ... }}`` assignment -- not only the
    ``if``/``end`` guard pair) is blanked to spaces, and an inline
    ``{{ ... }}`` template expression is replaced with same-length filler so
    an otherwise-unquoted scalar (``name: {{ .Values.SERVICE_NAME }}``)
    parses as a plain YAML string.

    Every replacement is exactly as long as what it replaces, so line count,
    column positions, and every untouched byte are unchanged -- the node
    marks ``yaml.compose_all`` reports against this masked text stay valid
    offsets into the *original* text. This buffer is never used to read a
    document's actual value (see ``_mask_for_value`` for that); a masked
    scalar's content here is disposable filler.
    """

    out = []
    for line in text.splitlines(keepends=True):
        body = line.rstrip("\r\n")
        ending = line[len(body):]
        stripped = body.strip()
        if stripped.startswith("{{") and _STANDALONE_HELM_LINE.match(stripped):
            out.append(" " * len(body) + ending)
            continue
        if "{{" in body:
            out.append(_INLINE_TEMPLATE.sub(lambda m: "x" * len(m.group(0)), body) + ending)
            continue
        out.append(line)
    return "".join(out)


def _split_trailing_comment(value: str) -> tuple[str, str]:
    """Split a plain (unquoted) scalar's trailing ``# comment`` (if any) from
    its real value (issue #776) -- matching YAML's own rule that a ``#``
    preceded by whitespace starts a comment on a plain scalar, so it is
    never captured as part of the quoted value. Only searched *after* the
    expression's last ``}}``, so a literal ``#`` inside the template
    expression itself is never mistaken for a comment marker.
    """

    last_close = value.rfind("}}")
    search_from = last_close + 2 if last_close != -1 else 0
    match = re.search(r"\s#", value[search_from:])
    if match is None:
        return value, ""
    comment_start = search_from + match.start() + 1  # index of '#' itself
    return value[:comment_start].rstrip(), value[comment_start:]


def _mask_for_value(text: str) -> str:
    """Parse-only mask (issue #776) used to read one already-located
    document's actual value: a standalone Helm action line is blanked to
    nothing, and an unquoted templated scalar is single-quoted so it loads as
    the exact expression text (``{{ .Values.SERVICE_NAME }}`` becomes that
    literal string value, not a masked placeholder). This buffer's offsets
    are never used for source replacement -- only its parsed value is read --
    so quoting is free to change the text's length.
    """

    out_lines = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("{{") and _STANDALONE_HELM_LINE.match(stripped):
            out_lines.append("")
            continue
        match = _SCALAR_WITH_TEMPLATE.match(line) or _SEQ_ITEM_WITH_TEMPLATE.match(line)
        if match:
            prefix, value = match.groups()
            # A quoted scalar is already valid YAML. Preserve it verbatim so
            # a literal " #" inside the quotes is not mistaken for a comment.
            original_bare = value.strip()
            if original_bare.startswith(("'", '"')):
                out_lines.append(line)
                continue
            value_part, comment_part = _split_trailing_comment(value)
            bare = value_part.strip()
            if not (
                (bare.startswith("'") and bare.endswith("'"))
                or (bare.startswith('"') and bare.endswith('"'))
            ):
                escaped = bare.replace("'", "''")
                suffix = f" {comment_part}" if comment_part else ""
                out_lines.append(f"{prefix}'{escaped}'{suffix}")
                continue
        out_lines.append(line)
    return "\n".join(out_lines)


def _contains_standalone_helm_action(text: str) -> bool:
    """Whether ``text`` (a document's already-guard-stripped ``inner``
    content) contains any standalone Helm action line -- if/else/range/with/
    end/define/block/template, or a ``{{- $x := ... }}`` assignment.
    ``_mask_for_value`` blanks each of these to nothing when reading a
    document's value, which is safe for locating static fields but would
    silently discard one of these found *inside* a document actually being
    converted (see ``build_root``'s use of this check)."""

    return any(
        line.strip().startswith("{{") and _STANDALONE_HELM_LINE.match(line.strip())
        for line in text.splitlines()
    )


def _is_phantom_empty_node(node: Any) -> bool:
    """True only for the zero-width ``ScalarNode`` a comment-only or otherwise empty
    document composes to, never for a real ``null``/``~`` document.

    Both compose to a node tagged ``:null``, so the tag alone cannot tell them apart --
    an explicit ``null`` or ``~`` document has real content (``value`` is the literal
    text, and its marks span that text), while an empty one composes to ``value == ""``
    with ``start_mark.index == end_mark.index``: a zero-width node whose marks can
    alias the *next* real document's span. Filtering on the zero width, not the tag, is
    what excludes only the phantom without also dropping a genuine null document.
    """

    return (
        node.tag.endswith(":null")
        and node.start_mark.index == node.end_mark.index
    )


def _split_yaml_documents(text: str, rel: str) -> tuple[str, list[dict[str, Any]]]:
    """Return ``(preamble, docs)``: one ``docs`` entry per top-level document,
    with an exact byte span that starts at that document's own leading
    ``---`` marker (if any) and ends just before the next one -- not
    PyYAML's node marks, which start at the first content token and omit
    the marker. Retaining several documents means concatenating their spans
    in order, and each span must carry its own separator for that to stay
    valid YAML.

    ``preamble`` is any text before the file's first document -- a header
    comment, say -- that belongs to no single document. It must not be
    folded into whichever document happens to be first: if that specific
    document is the one removed, tying the preamble to its span would
    delete it right along with it, even when a later, retained document
    also depends on it surviving. The caller re-attaches it whenever at
    least one document remains, independent of which one that is.
    """

    try:
        # A comment-only or otherwise empty document does not compose to Python None
        # (that only happens past the last document in the stream) -- it composes to a
        # zero-width ScalarNode whose marks can fall inside the range this function
        # would otherwise attribute to the *next* real document, making both compute
        # the same byte span. Excluding only that phantom node (see
        # _is_phantom_empty_node), not Python None nor every ":null"-tagged node, is
        # what keeps every remaining document's span its own without also dropping a
        # document that is an explicit, real `null`/`~` value.
        nodes = [
            n
            for n in yaml.compose_all(_mask_for_spans(text))
            if n is not None and not _is_phantom_empty_node(n)
        ]
    except yaml.YAMLError as exc:
        raise UnsupportedError(f"{rel}: not valid YAML: {exc}") from None

    marker_starts = [
        m.start() for m in re.finditer(r"^---(?:[ \t]+#[^\r\n]*)?[ \t]*\r?\n", text, re.M)
    ]
    docs = []
    for node in nodes:
        preceding = [m for m in marker_starts if m <= node.start_mark.index]
        start = preceding[-1] if preceding else 0
        following = [m for m in marker_starts if m > node.start_mark.index]
        end = following[0] if following else len(text)
        raw = text[start:end]
        content = raw[raw.index("\n") + 1 :] if raw.startswith("---") else raw
        lines = content.splitlines(keepends=True)
        guard = None
        guard_action = None
        inner = content
        meaningful = [
            index for index, line in enumerate(lines)
            if line.strip() and not line.lstrip().startswith("#")
        ]
        if (
            len(meaningful) >= 2
            and _GUARD_IF.match(lines[meaningful[0]].rstrip("\r\n"))
            and _GUARD_END.match(lines[meaningful[-1]].rstrip("\r\n"))
        ):
            first, last = meaningful[0], meaningful[-1]
            guard = ("".join(lines[: first + 1]), "".join(lines[last:]))
            guard_action = lines[first].strip()
            inner = "".join(lines[first + 1:last])
        elif any(_GUARD_IF.match(l.rstrip("\r\n")) for l in lines):
            # An unmatched {{- if ... }} (no whole-document guard pair found
            # above) is genuinely a partial guard. A standalone {{- end }}
            # with no such "if" is not necessarily one -- it is the same
            # closing token range/with/define/block use, and is otherwise
            # reported more specifically as nested Helm control flow (see
            # _contains_standalone_helm_action in build_root) rather than
            # misreported as a mismatched guard here.
            raise UnsupportedError(
                f"{rel}: a Helm guard action does not wrap the entire document; partial guards are unsupported"
            )
        try:
            value = yaml.safe_load(_mask_for_value(inner))
        except yaml.YAMLError as exc:
            raise UnsupportedError(f"{rel}: not valid YAML inside guard: {exc}") from None
        docs.append({
            "start": start,
            "end": end,
            "value": value,
            "guard": guard,
            "guardAction": guard_action,
            "inner": inner,
        })
    preamble = text[: docs[0]["start"]] if docs else ""
    return preamble, docs


def _document_supported(value: Any) -> bool:
    """Whether every item convert_dbaas_crs would look at in this document is
    a recognized DatabaseDeclaration/DbPolicy item -- never judged from only
    the first item of a list."""

    if isinstance(value, list) and any(not isinstance(entry, dict) for entry in value):
        # as_legacy_items silently drops a non-dict list entry (a scalar, a
        # string) rather than surfacing it; without this check, a document
        # made of one recognized dict item plus one dropped scalar would
        # read as "fully supported" from the *filtered* list alone, and the
        # whole document -- scalar included -- would be deleted with it.
        return False
    items = convert.as_legacy_items(value)
    if not items:
        return False
    for item in items:
        kind = str(item.get("kind", ""))
        sub_kind = str(item.get("subKind", ""))
        legacy_kind = (sub_kind or kind).lower()
        if legacy_kind not in ("databasedeclaration", "dbpolicy"):
            return False
    return True


def parse_source(repo_root: Path, rel: str) -> tuple[str, list[dict[str, Any]], bool, str]:
    """Return (original_text, documents, is_json, preamble). Each document
    dict has ``value``, and for YAML also ``start``/``end``/``guard``.
    ``preamble`` is always empty for a JSON source -- it has no per-document
    byte span, and thus no leading text outside one, to track. For a YAML
    source it also carries a leading BOM character (U+FEFF) when the file
    had one: a BOM is one more kind of leading byte no single document
    owns, so a retained/kept rewrite must reproduce it exactly like the
    header-comment preamble it is prepended alongside.
    """

    path = resolve_within(repo_root, rel, "source path")
    raw = path.read_bytes()
    has_bom = raw.startswith(b"\xef\xbb\xbf")
    # Decoding raw bytes directly -- never text-mode I/O -- is what keeps a
    # BOM and CRLF sequences byte-for-byte intact. Path.read_text() (or
    # open() without newline="") both apply universal-newline translation
    # unconditionally, silently rewriting every "\r\n" to "\n" before any
    # byte span is ever computed; the "utf-8-sig" codec additionally
    # consumes a BOM on read but -- asymmetrically -- always re-adds one on
    # write, so it cannot by itself tell "had a BOM" from "never had one."
    text = (raw[3:] if has_bom else raw).decode("utf-8")
    if path.suffix.lower() == ".json":
        try:
            data = json.loads(text, parse_constant=convert.reject_json_constant)
        except ValueError as exc:
            raise UnsupportedError(f"{rel}: invalid JSON: {exc}") from None
        # One entry per top-level array element (or a single entry for a bare
        # object), matching YAML's one-entry-per-document shape -- so doc_index
        # uniquely addresses each migratable unit even when a JSON array mixes
        # several DatabaseDeclaration/DbPolicy wrapper objects. A JSON source
        # is never partially rewritten (see build_root: it is deleted whole
        # or left untouched), so its BOM is never something this writer
        # needs to reproduce.
        values = data if isinstance(data, list) else [data]
        return text, [{"value": v} for v in values], True, ""
    if yaml is None:
        raise UnsupportedError("PyYAML is required to read YAML sources")
    preamble, docs = _split_yaml_documents(text, rel)
    if has_bom:
        preamble = "﻿" + preamble
    return text, docs, False, preamble


# --------------------------------------------------------------------------- #
# Root processing
# --------------------------------------------------------------------------- #


def _name_key(rel: str, doc_index: int, item_index: int, declaration_index: int) -> str:
    return f"{rel}#{doc_index}#{item_index}#{declaration_index}"


def _args_for_root(root: dict[str, Any]) -> argparse.Namespace:
    ns = argparse.Namespace()
    ns.operator_namespace = root["operatorNamespace"]
    ns.namespace = root["namespace"]
    ns.service_name = root["serviceName"]
    # False, not True: convert_db_policy() only overrides a source's own
    # microserviceName when this is True (matching convert_dbaas_crs.py's
    # own CLI, where it is True only when a caller passed --service-name
    # explicitly). root["serviceName"] is a required plan field, so setting
    # this True unconditionally would make it *always* win over -- and
    # silently delete the evidence for -- a source's own microserviceName,
    # which is wrong the moment one root's sources cover more than one
    # microservice. False here means root["serviceName"] is the fallback
    # used only when a source does not specify its own.
    ns.service_name_explicit = False
    ns.name_prefix = root.get("namePrefix") or ""
    return ns


def build_root(repo_root: Path, root: dict[str, Any]) -> tuple[dict[str, str | None], list[str]]:
    """Return (changes for this root, warnings). Raises PlanError/UnsupportedError."""

    args = _args_for_root(root)
    capability_guard = root.get("capabilityGuard")
    overrides: dict[str, str] = root.get("nameOverrides") or {}
    used_overrides: set[str] = set()
    warnings: list[str] = []
    errors: list[str] = []
    resources: list[dict[str, Any]] = []
    entries: list[tuple[dict[str, Any], tuple[str, str] | None]] = []
    changes: dict[str, str | None] = {}

    for source in root["sources"]:
        rel = source["path"]
        text, docs, is_json, preamble = parse_source(repo_root, rel)
        if not docs:
            # `all(...)` over an empty sequence is vacuously true -- without this
            # check a source with zero documents (an empty top-level JSON array,
            # or a YAML file with no document) would read as "everything
            # migrated" and be deleted.
            raise UnsupportedError(f"{rel}: no document to migrate")

        doc_results: list[tuple[dict[str, Any], bool]] = []  # (doc, fully_supported)
        for doc_index, doc in enumerate(docs, start=1):
            value = doc["value"]
            doc["capabilityFallbackGuard"] = bool(
                capability_guard
                and doc.get("guardAction") == _negated_guard_open(capability_guard).strip()
            )
            if value is None:
                doc_results.append((doc, False))
                continue
            supported = _document_supported(value)
            doc_results.append((doc, supported))
            if not supported:
                continue
            # issue #776: a document's *value* was read from a masked copy that
            # blanks every standalone Helm action -- if/else/range/with/end/
            # assignment -- to nothing (_mask_for_value), which is safe for
            # locating and reading a document's static fields but silently
            # drops the semantics of any such action found *inside* the
            # document being converted (excluding its own already-handled
            # whole-document guard): a `{{- range .Values.externalServices }}`
            # over a services list collapses to one static entry whose name is
            # still the literal loop-variable expression, not one entry per
            # service. That is not a lossy-but-usable conversion, it is a
            # corrupted one -- block instead of emitting it.
            if not is_json and _contains_standalone_helm_action(doc["inner"]):
                raise UnsupportedError(
                    f"{rel}#{doc_index}: contains nested Helm control flow (if/else/range/with/"
                    "assignment) inside the declaration being converted; this cannot be preserved "
                    "in the static generated resource and is not supported for automatic "
                    "conversion -- migrate this document manually"
                )
            guard = doc.get("guard") if not is_json else None
            if doc["capabilityFallbackGuard"]:
                # This guard was emitted by an earlier run to retain the
                # legacy declaration only for operator-absent clusters. It is
                # not source semantics to carry into the generated native CR.
                guard = None
            for item_index, item in enumerate(convert.as_legacy_items(value), start=1):
                kind = str(item.get("kind", ""))
                sub_kind = str(item.get("subKind", ""))
                legacy_kind = (sub_kind or kind).lower()
                is_wrapper = kind == "DBaaS"
                body_value = item.get("spec") if is_wrapper else item
                metadata_value = item.get("metadata")
                if not isinstance(body_value, dict):
                    raise UnsupportedError(f"{rel}#{doc_index}#{item_index}: spec must be an object")
                if metadata_value is not None and not isinstance(metadata_value, dict):
                    raise UnsupportedError(f"{rel}#{doc_index}#{item_index}: metadata must be an object")
                body = dict(body_value)
                metadata = dict(metadata_value or {})
                convert.reject_dropped_metadata(metadata, f"{rel}#{doc_index}", errors)

                if legacy_kind == "databasedeclaration":
                    declarations = body.get("declarations")
                    if declarations is None:
                        # No declarations[] wrapper: the document itself is
                        # the one declaration, but it still carries envelope
                        # fields (apiVersion, kind, metadata) that the strict
                        # unknown-field check was never meant to validate.
                        declarations = [
                            {k: v for k, v in body.items() if k not in ("apiVersion", "kind", "subKind", "metadata")}
                        ]
                    if not isinstance(declarations, list):
                        raise UnsupportedError(f"{rel}#{doc_index}: declarations is not a list")
                    multiple = len(declarations) > 1
                    for declaration_index, declaration in enumerate(declarations, start=1):
                        if not isinstance(declaration, dict):
                            raise UnsupportedError(
                                f"{rel}#{doc_index}: declaration #{declaration_index} is not an object"
                            )
                        key = _name_key(rel, doc_index, item_index, declaration_index)
                        override = overrides.get(key)
                        if override is not None:
                            used_overrides.add(key)
                        try:
                            resource = convert.convert_database_declaration(
                                declaration, metadata, doc_index, declaration_index, multiple,
                                args, warnings, errors, override_name=override, is_wrapper=is_wrapper,
                            )
                        except convert.TemplatedNameRequired as exc:
                            raise UnsupportedError(
                                f"{rel}#{doc_index}#{item_index}#{declaration_index}: {exc}; "
                                "add a plan.roots[].nameOverrides "
                                f"entry for key {key!r}"
                            ) from None
                        resources.append(resource)
                        entries.append((resource, guard))
                elif legacy_kind == "dbpolicy":
                    key = _name_key(rel, doc_index, item_index, 1)
                    override = overrides.get(key)
                    if override is not None:
                        used_overrides.add(key)
                    try:
                        resource = convert.convert_db_policy(
                            body, metadata, doc_index, 1, args, warnings, errors,
                            override_name=override, is_wrapper=is_wrapper,
                        )
                    except convert.TemplatedNameRequired as exc:
                        raise UnsupportedError(
                            f"{rel}#{doc_index}#{item_index}#1: {exc}; "
                            f"add a plan.roots[].nameOverrides entry for key {key!r}"
                        ) from None
                    resources.append(resource)
                    entries.append((resource, guard))

        # A JSON file is one atomic unit: only fully-removable when every
        # top-level element converted; otherwise the whole array is "mixed"
        # and blocks (never partially reformatted).
        if is_json:
            if not all(supported for _doc, supported in doc_results):
                raise UnsupportedError(
                    f"{rel}: the JSON source mixes supported and unsupported/irrelevant items; "
                    "migrate it as a whole or split it first"
                )
            # issue #776: preserve the source under capabilityGuard instead of
            # deleting it -- the native CR (in the output file) and the legacy
            # declaration (here) render in opposite, mutually exclusive branches.
            changes[rel] = _wrap_in_negated_guard(capability_guard, text) if capability_guard else None
        elif all(supported for _d, supported in doc_results):
            if capability_guard:
                if len(doc_results) == 1 and doc_results[0][0]["capabilityFallbackGuard"]:
                    changes[rel] = text
                else:
                    changes[rel] = preamble + _wrap_in_negated_guard(
                        capability_guard, text[docs[0]["start"]:]
                    )
            else:
                changes[rel] = None
        elif not any(supported for _d, supported in doc_results):
            changes[rel] = preamble + text[docs[0]["start"]:]  # report it without changing its bytes
        elif capability_guard:
            # Every document keeps its original position; a supported one is
            # wrapped to the operator-absent branch in place rather than removed.
            parts = [
                (
                    text[d["start"]:d["end"]]
                    if d["capabilityFallbackGuard"]
                    else _wrap_in_negated_guard(capability_guard, text[d["start"]:d["end"]])
                ) if supported
                else text[d["start"]:d["end"]]
                for d, supported in doc_results
            ]
            changes[rel] = preamble + "".join(parts)
        else:
            # The preamble (if any) is reattached whenever some document
            # survives, regardless of which document(s) that is.
            kept = "".join(text[d["start"]:d["end"]] for d, supported in doc_results if not supported)
            changes[rel] = preamble + kept

    unmatched = sorted(set(overrides) - used_overrides)
    if unmatched:
        raise PlanError(f"nameOverrides keys matched no generated resource: {unmatched}")

    if errors:
        raise UnsupportedError("; ".join(errors))
    if not resources:
        raise UnsupportedError("no supported declarations were found for this root")

    convert.reject_duplicate_resources(resources, errors, root=root["root"] or ".")
    for resource in resources:
        convert.validate_target_resource(resource, errors)
    if errors:
        raise UnsupportedError("; ".join(errors))

    output_text = _render_output(entries, capability_guard)
    changes[root["outputFile"]] = output_text
    return changes, warnings


def _render_output(
    entries: list[tuple[dict[str, Any], tuple[str, str] | None]],
    capability_guard: str | None = None,
) -> str:
    chunks = []
    for resource, guard in entries:
        body = yaml.safe_dump(resource, sort_keys=False, allow_unicode=False, default_flow_style=False)
        if guard:
            prefix, suffix = guard
            body = f"{prefix}{body}{suffix}"
        if capability_guard:
            # issue #776: the native CR only renders when the target cluster's
            # dbaas-operator CRDs are present -- nested inside any pre-existing
            # guard the source document already carried (both conditions apply).
            body = _capability_guard_open(capability_guard) + body + _CAPABILITY_GUARD_CLOSE
        chunks.append(f"---\n{body}")
    return "".join(chunks)


# --------------------------------------------------------------------------- #
# Validation
# --------------------------------------------------------------------------- #


def _check_generated_object(obj: dict[str, Any], problems: list[str]) -> None:
    convert.validate_target_resource(obj, problems)


def _pilot_value(key: str) -> str:
    return f"pilot-{key.lower().replace('_', '-')}"


def _value_at(values: dict[str, Any], dotted_key: str) -> Any:
    current: Any = values
    for part in dotted_key.split("."):
        if not isinstance(current, dict) or part not in current:
            return None
        current = current[part]
    return current


def _values_tree(values: dict[str, str]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for dotted_key, value in values.items():
        current = result
        parts = dotted_key.split(".")
        for part in parts[:-1]:
            child = current.setdefault(part, {})
            if not isinstance(child, dict):
                raise PlanError(f"helmValues key {dotted_key!r} conflicts with another key")
            current = child
        if parts[-1] in current and isinstance(current[parts[-1]], dict):
            raise PlanError(f"helmValues key {dotted_key!r} conflicts with another key")
        current[parts[-1]] = value
    return result


def validate_plain_root(text: str, root_label: str) -> list[str]:
    problems = []
    if "{{" in text:
        problems.append(f"{root_label}: a plain-manifest output must not contain a Helm expression")
    try:
        docs = [d for d in yaml.safe_load_all(text) if d]
    except yaml.YAMLError as exc:
        return [f"{root_label}: generated output is not valid YAML: {exc}"]
    for doc in docs:
        _check_generated_object(doc, problems)
    return problems


def validate_helm_root(tree_root: Path, root: dict[str, Any], generated_content: str) -> list[str]:
    helm = shutil.which("helm")
    if helm is None:
        raise UnsupportedError("helm is required to validate a helm root and is not on PATH")
    chart_dir = tree_root / root["root"] if root["root"] else tree_root
    if not (chart_dir / "Chart.yaml").is_file():
        return [f"{root['root'] or '.'}: not a Helm chart (no Chart.yaml)"]

    chart_values: dict[str, Any] = {}
    values_path = chart_dir / "values.yaml"
    if values_path.is_file():
        try:
            loaded = yaml.safe_load(values_path.read_text(encoding="utf-8"))
            if isinstance(loaded, dict):
                chart_values = loaded
        except yaml.YAMLError:
            pass

    keys = set(_ANY_VALUE_REF.findall(generated_content))
    keys.update(_ANY_VALUE_REF.findall(root["namespace"]))
    keys.update(_ANY_VALUE_REF.findall(root["serviceName"]))
    values: dict[str, str] = {}
    for key in keys:
        existing = _value_at(chart_values, key)
        values[key] = str(existing) if isinstance(existing, (str, int, float, bool)) else _pilot_value(key)
    helm_values = root.get("helmValues") or {}
    values.update({str(k): str(v) for k, v in helm_values.items()})

    release_ns = root["namespace"]
    for key, value in values.items():
        release_ns = release_ns.replace(f"{{{{ .Values.{key} }}}}", value)
    if "{{" in release_ns:
        release_ns = "dbaas-migration-pilot"

    values_file = tree_root / ".dbaas-migration-values.yaml"
    values_file.write_text(yaml.safe_dump(_values_tree(values), sort_keys=True), encoding="utf-8")
    capability_guard = root.get("capabilityGuard")

    def render(*, api_versions: str | None) -> tuple[str | None, list[str]]:
        cmd = [
            helm, "template", "dbaas-migration-pilot", str(chart_dir),
            "--namespace", release_ns, "--values", str(values_file),
        ]
        if api_versions:
            cmd += ["--api-versions", api_versions]
        try:
            proc = subprocess.run(cmd, capture_output=True, text=True, timeout=180, check=False)
        except (OSError, subprocess.SubprocessError) as exc:
            return None, [f"helm template did not run: {exc}"]
        if proc.returncode != 0:
            return None, [f"helm template failed: {proc.stderr.strip()[:2000]}"]
        return proc.stdout, []

    # Without a capability guard, a single render at the runner's own default
    # capabilities is the whole (pre-issue-#776) validation. With one, the
    # default render lacks the guard's capability -- rendering the
    # operator-absent fallback branch -- so certifying the branch the
    # generated resource actually lives in requires rendering *with*
    # --api-versions <capabilityGuard> instead (see the "twice" requirement
    # in mapping.md's Helm-templated sources note); the second,
    # default-capabilities render is certified separately below.
    stdout, problems = render(api_versions=capability_guard)
    if stdout is None:
        return problems

    try:
        docs = [d for d in yaml.safe_load_all(stdout) if isinstance(d, dict)]
    except yaml.YAMLError as exc:
        return [f"rendered output is not valid YAML: {exc}"]
    generated_kinds = [doc for doc in docs if doc.get("kind") in ("InternalDatabase", "DatabaseAccessPolicy")]
    for doc in generated_kinds:
        _check_generated_object(doc, problems)
    if capability_guard and not generated_kinds:
        # _check_generated_object is passive -- it validates whatever is
        # found and is silently a no-op when nothing is. Without this, a
        # capability guard that never evaluates true (a typo in the
        # condition, say) would render nothing and still "pass".
        problems.append(
            f"rendered with --api-versions {capability_guard!r} but no InternalDatabase/"
            "DatabaseAccessPolicy was found -- the capability guard did not let it render"
        )

    if capability_guard:
        fallback_stdout, fallback_problems = render(api_versions=None)
        problems.extend(fallback_problems)
        if fallback_stdout is not None:
            try:
                fallback_docs = [d for d in yaml.safe_load_all(fallback_stdout) if isinstance(d, dict)]
            except yaml.YAMLError as exc:
                problems.append(f"operator-absent render is not valid YAML: {exc}")
                fallback_docs = []
            for doc in fallback_docs:
                if doc.get("kind") in ("InternalDatabase", "DatabaseAccessPolicy"):
                    problems.append(
                        f"operator-absent render (no --api-versions) still contains "
                        f"{doc.get('kind')} {doc.get('metadata', {}).get('name')!r}; "
                        "the capability guard did not suppress it"
                    )
    return problems


# --------------------------------------------------------------------------- #
# Transaction
# --------------------------------------------------------------------------- #


def classify(repo_root: Path, changes: dict[str, str | None]) -> dict[str, list[str]]:
    result = {key: [] for key in ("createdFiles", "modifiedFiles", "deletedFiles", "unchangedFiles")}
    for rel, content in sorted(changes.items()):
        target = resolve_within(repo_root, rel, "target path")
        if content is None:
            result["deletedFiles" if target.is_file() else "unchangedFiles"].append(rel)
        elif not target.is_file():
            result["createdFiles"].append(rel)
        elif target.read_bytes() == content.encode("utf-8"):
            result["unchangedFiles"].append(rel)
        else:
            result["modifiedFiles"].append(rel)
    return result


def materialize_and_validate(repo_root: Path, plan: dict[str, Any], all_changes: dict[str, str | None]) -> None:
    problems: list[str] = []
    for root in plan["roots"]:
        with tempfile.TemporaryDirectory(prefix="core-migration-") as tmp:
            tree_root = Path(tmp)
            source = repo_root if not root["root"] else repo_root / root["root"]
            dest = tree_root if not root["root"] else tree_root / root["root"]
            if source.is_dir():
                shutil.copytree(source, dest, symlinks=False, dirs_exist_ok=True, ignore=shutil.ignore_patterns(".git"))
            dest.mkdir(parents=True, exist_ok=True)
            for rel, content in all_changes.items():
                if rel != root["outputFile"] and rel not in {s["path"] for s in root["sources"]}:
                    continue
                target = tree_root / rel
                if content is None:
                    target.unlink(missing_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    # write_bytes, not write_text(..., newline=""): the
                    # newline= keyword on Path.write_text() is Python 3.10+
                    # only, and TypeErrors on the stock Python 3.9 shipped by
                    # macOS, RHEL 8/9, and Debian 11. Writing the
                    # already-encoded bytes directly needs no newline
                    # parameter at all -- there is no text-mode translation
                    # to disable.
                    target.write_bytes(content.encode("utf-8"))

            output_content = all_changes.get(root["outputFile"])
            if output_content is None:
                continue
            if root["kind"] == "plain":
                problems.extend(validate_plain_root(output_content, root["outputFile"]))
            else:
                problems.extend(validate_helm_root(tree_root, root, output_content))
    if problems:
        raise ValidationFailure(problems)


def commit(repo_root: Path, changes: dict[str, str | None]) -> dict[str, list[str]]:
    created, modified, deleted, unchanged = [], [], [], []
    backups: dict[str, tuple[bytes, int] | None] = {}
    applied: list[str] = []
    # Writes (creates/modifies) before deletes: if the transaction fails
    # partway through, a legacy source is never gone with its generated
    # replacement not yet in place.
    ordered = sorted(rel for rel, content in changes.items() if content is not None) + sorted(
        rel for rel, content in changes.items() if content is None
    )
    try:
        for rel in ordered:
            target = resolve_within(repo_root, rel, "target path")
            new_content = changes[rel]
            existed = target.is_file()
            backups[rel] = (
                (target.read_bytes(), target.stat().st_mode & 0o777) if existed else None
            )
            if new_content is None:
                if existed:
                    target.unlink()
                    deleted.append(rel)
                    applied.append(rel)
                else:
                    unchanged.append(rel)
                continue
            new_bytes = new_content.encode("utf-8")
            if existed and target.read_bytes() == new_bytes:
                unchanged.append(rel)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            original_mode = target.stat().st_mode & 0o777 if existed else 0o644
            fd, tmp_name = tempfile.mkstemp(dir=str(target.parent), prefix=f".{target.name}.")
            try:
                with open(fd, "wb") as handle:
                    handle.write(new_bytes)
                os.chmod(tmp_name, original_mode)
                Path(tmp_name).replace(target)
            except BaseException:
                Path(tmp_name).unlink(missing_ok=True)
                raise
            applied.append(rel)
            (created if not existed else modified).append(rel)
    except Exception:
        for rel in reversed(applied):
            target = resolve_within(repo_root, rel, "target path")
            backup = backups.get(rel)
            if backup is None:
                target.unlink(missing_ok=True)
            else:
                original, mode = backup
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(original)
                os.chmod(target, mode)
        raise
    return {"createdFiles": created, "modifiedFiles": modified, "deletedFiles": deleted, "unchangedFiles": unchanged}


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Deterministic core-declarations migration writer.")
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--plan", required=True, type=Path)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", dest="mode", action="store_const", const="check")
    mode.add_argument("--apply", dest="mode", action="store_const", const="apply")
    try:
        args = parser.parse_args(argv)
    except SystemExit as exc:
        # argparse itself calls sys.exit() on --help (0) and on a parse error
        # (2), both to stderr/stdout as plain text -- bypassing the "one JSON
        # result envelope" contract entirely. --help's exit 0 is not an error
        # and is left alone; a parse error gets the same envelope as any
        # other bad invocation.
        if exc.code in (0, None):
            raise
        print(json.dumps({"status": "blocked", "errors": ["invalid command line"]}))
        return EXIT_BAD_PLAN

    if yaml is None:
        print(json.dumps({"status": "blocked", "errors": ["PyYAML is required"]}))
        return EXIT_UNSUPPORTED

    repo_root = args.repo_root.resolve()
    if not repo_root.is_dir():
        print(json.dumps({"status": "blocked", "errors": [f"--repo-root is not a directory: {repo_root}"]}))
        return EXIT_BAD_PLAN

    try:
        plan = load_plan(args.plan)
        check_source_hashes(repo_root, plan)
        check_output_ownership(repo_root, plan)

        all_changes: dict[str, str | None] = {}
        warnings: list[str] = []
        for root in plan["roots"]:
            root_changes, root_warnings = build_root(repo_root, root)
            all_changes.update(root_changes)
            warnings.extend(root_warnings)

        materialize_and_validate(repo_root, plan, all_changes)

        if args.mode == "check":
            print(json.dumps({"status": "valid", "warnings": warnings, **classify(repo_root, all_changes)}))
            return EXIT_OK

        check_source_hashes(repo_root, plan)
        check_output_ownership(repo_root, plan)
        file_lists = commit(repo_root, all_changes)
        touched = file_lists["createdFiles"] or file_lists["modifiedFiles"] or file_lists["deletedFiles"]
        result = {"status": "changed" if touched else "unchanged", "warnings": warnings, **file_lists}
        print(json.dumps(result))
        return EXIT_OK

    except PlanError as exc:
        print(json.dumps({"status": "blocked", "errors": [str(exc)]}))
        return EXIT_BAD_PLAN
    except StaleSourceError as exc:
        print(json.dumps({"status": "blocked", "errors": [str(exc)]}))
        return EXIT_STALE_SOURCE
    except UnsupportedError as exc:
        print(json.dumps({"status": "blocked", "errors": [str(exc)]}))
        return EXIT_UNSUPPORTED
    except ValidationFailure as exc:
        print(json.dumps({"status": "blocked", "errors": exc.problems}))
        return EXIT_VALIDATION
    except Exception as exc:  # noqa: BLE001 - never crash without a machine-readable result
        message = f"internal error: {type(exc).__name__}: {exc}"
        print(json.dumps({"status": "blocked", "errors": [message]}))
        print(message, file=sys.stderr)
        return EXIT_BAD_PLAN


if __name__ == "__main__":
    raise SystemExit(main())
