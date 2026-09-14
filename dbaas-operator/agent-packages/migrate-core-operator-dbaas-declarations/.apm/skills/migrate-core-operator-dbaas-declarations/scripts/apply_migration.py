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
          "sources": [{"path": "templates/dbaas-configuration.json", "sha256": "<hash>"}],
          "nameOverrides": {"templates/dbaas-configuration.json#1#1": "orders-db"}
        }
      ]
    }

A source is matched against the plan's recorded SHA-256 before it is read.
Every supported item in a source is converted; a source is deleted only when
every item in it converted successfully. A ``nameOverrides`` key addresses
one generated resource as ``<source path>#<doc index>#<declaration index>``
(1-based, matching the document/declaration enumeration the shared converter
already uses) and is required whenever that resource's default name would be
templated or would mix literal text with a Helm expression.
"""

from __future__ import annotations

import argparse
import hashlib
import json
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

_DNS_LABEL = re.compile(r"^[a-z0-9](?:[-a-z0-9]*[a-z0-9])?$")
_GUARD_IF = re.compile(r"^\s*\{\{-?\s*if\b.*-?\}\}\s*$")
_GUARD_END = re.compile(r"^\s*\{\{-?\s*end\s*-?\}\}\s*$")
_ANY_VALUE_REF = re.compile(r"\.Values\.([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*)")

_ROOT_KEYS = {
    "root", "kind", "operatorNamespace", "serviceName", "namespace",
    "namePrefix", "helmValues", "outputFile", "outputSha256", "sources", "nameOverrides",
}
_SOURCE_KEYS = {"path", "sha256"}


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
        norm["root"] = "" if root["root"] in ("", ".") else canonical_path(root["root"], f"{where}.root")
        if root.get("kind") not in ("helm", "plain"):
            raise PlanError(f"{where}.kind must be 'helm' or 'plain'")
        for key in ("operatorNamespace", "serviceName", "namespace"):
            if not isinstance(root.get(key), str) or not root[key].strip():
                raise PlanError(f"{where}.{key} is required and must be a non-empty string")
        if "helmValues" in root and not isinstance(root["helmValues"], dict):
            raise PlanError(f"{where}.helmValues must be an object")
        if "nameOverrides" in root and not isinstance(root["nameOverrides"], dict):
            raise PlanError(f"{where}.nameOverrides must be an object")
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
            if not isinstance(sha256, str) or len(sha256) != 64:
                raise PlanError(f"{s_where}.sha256 must be a 64-character hex digest")
            if path in source_paths:
                raise PlanError(f"source {path!r} is listed more than once across plan.roots")
            source_paths.add(path)
            norm_sources.append({"path": path, "sha256": sha256})
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
        if output_sha256 is not None and (not isinstance(output_sha256, str) or len(output_sha256) != 64):
            raise PlanError(f"{where}.outputSha256 must be a 64-character hex digest or null")
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


def _sanitize_guards(text: str) -> str:
    """Blank out whole-line Helm guard actions so PyYAML can parse the rest.

    ``{{- if ... }}`` is not valid YAML on its own -- composing the raw text
    would fail before guard detection ever ran. Each guard line is replaced
    by spaces of the exact same length (never removed), so every other
    character keeps its original offset and indices from composing this
    sanitized text stay valid into the original.
    """

    out = []
    for line in text.splitlines(keepends=True):
        body = line.rstrip("\r\n")
        if _GUARD_IF.match(body) or _GUARD_END.match(body):
            out.append(" " * len(body) + line[len(body):])
        else:
            out.append(line)
    return "".join(out)


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
        nodes = [n for n in yaml.compose_all(_sanitize_guards(text)) if n is not None]
    except yaml.YAMLError as exc:
        raise UnsupportedError(f"{rel}: not valid YAML: {exc}") from None

    marker_starts = [m.start() for m in re.finditer(r"^---[ \t]*\r?\n", text, re.M)]
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
        inner = content
        if (
            len(lines) >= 2
            and _GUARD_IF.match(lines[0].rstrip("\r\n"))
            and _GUARD_END.match(lines[-1].rstrip("\r\n"))
        ):
            guard = lines[0].rstrip("\r\n")
            inner = "".join(lines[1:-1])
        elif any(_GUARD_IF.match(l.rstrip("\r\n")) or _GUARD_END.match(l.rstrip("\r\n")) for l in lines):
            raise UnsupportedError(
                f"{rel}: a Helm guard action does not wrap the entire document; partial guards are unsupported"
            )
        try:
            value = yaml.safe_load(inner)
        except yaml.YAMLError as exc:
            raise UnsupportedError(f"{rel}: not valid YAML inside guard: {exc}") from None
        docs.append({"start": start, "end": end, "value": value, "guard": guard})
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


def _name_key(rel: str, doc_index: int, declaration_index: int) -> str:
    return f"{rel}#{doc_index}#{declaration_index}"


def _args_for_root(root: dict[str, Any]) -> argparse.Namespace:
    ns = argparse.Namespace()
    ns.operator_namespace = root["operatorNamespace"]
    ns.namespace = root["namespace"]
    ns.service_name = root["serviceName"]
    ns.service_name_explicit = True
    ns.name_prefix = root.get("namePrefix") or ""
    return ns


def build_root(repo_root: Path, root: dict[str, Any]) -> tuple[dict[str, str | None], list[str]]:
    """Return (changes for this root, warnings). Raises PlanError/UnsupportedError."""

    args = _args_for_root(root)
    overrides: dict[str, str] = root.get("nameOverrides") or {}
    used_overrides: set[str] = set()
    warnings: list[str] = []
    errors: list[str] = []
    resources: list[dict[str, Any]] = []
    entries: list[tuple[dict[str, Any], str | None]] = []
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
            if value is None:
                doc_results.append((doc, False))
                continue
            supported = _document_supported(value)
            doc_results.append((doc, supported))
            if not supported:
                continue
            guard = doc.get("guard") if not is_json else None
            for item in convert.as_legacy_items(value):
                kind = str(item.get("kind", ""))
                sub_kind = str(item.get("subKind", ""))
                legacy_kind = (sub_kind or kind).lower()
                body = dict(item.get("spec") or {}) if kind == "DBaaS" else dict(item)
                metadata = dict(item.get("metadata") or {})
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
                        key = _name_key(rel, doc_index, declaration_index)
                        override = overrides.get(key)
                        if override is not None:
                            used_overrides.add(key)
                        try:
                            resource = convert.convert_database_declaration(
                                declaration, metadata, doc_index, declaration_index, multiple,
                                args, warnings, errors, override_name=override,
                            )
                        except convert.TemplatedNameRequired as exc:
                            raise UnsupportedError(
                                f"{rel}#{doc_index}#{declaration_index}: {exc}; add a plan.roots[].nameOverrides "
                                f"entry for key {key!r}"
                            ) from None
                        resources.append(resource)
                        entries.append((resource, guard))
                elif legacy_kind == "dbpolicy":
                    key = _name_key(rel, doc_index, 1)
                    override = overrides.get(key)
                    if override is not None:
                        used_overrides.add(key)
                    try:
                        resource = convert.convert_db_policy(
                            body, metadata, doc_index, 1, args, warnings, errors, override_name=override
                        )
                    except convert.TemplatedNameRequired as exc:
                        raise UnsupportedError(
                            f"{rel}#{doc_index}#1: {exc}; add a plan.roots[].nameOverrides entry for key {key!r}"
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
            changes[rel] = None
        elif all(supported for _d, supported in doc_results):
            changes[rel] = None
        elif not any(supported for _d, supported in doc_results):
            pass  # every document was irrelevant -- leave the source untouched
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
    if errors:
        raise UnsupportedError("; ".join(errors))

    output_text = _render_output(entries)
    changes[root["outputFile"]] = output_text
    return changes, warnings


def _render_output(entries: list[tuple[dict[str, Any], str | None]]) -> str:
    chunks = []
    for resource, guard in entries:
        body = yaml.safe_dump(resource, sort_keys=False, allow_unicode=False, default_flow_style=False)
        if guard:
            chunks.append(f"---\n{guard}\n{body.rstrip(chr(10))}\n{{{{- end }}}}\n")
        else:
            chunks.append(f"---\n{body}")
    return "".join(chunks)


# --------------------------------------------------------------------------- #
# Validation
# --------------------------------------------------------------------------- #


def _is_dns_label(name: Any) -> bool:
    return isinstance(name, str) and 1 <= len(name) <= 63 and _DNS_LABEL.fullmatch(name) is not None


def _check_generated_object(obj: dict[str, Any], problems: list[str]) -> None:
    identity = f"{obj.get('kind')}/{(obj.get('metadata') or {}).get('name')}"
    name = (obj.get("metadata") or {}).get("name")
    if "{{" not in str(name) and not _is_dns_label(name):
        problems.append(f"{identity}: name is not a valid DNS-1123 label of at most 63 characters")
    spec = obj.get("spec") or {}
    operator_ns = spec.get("operatorNamespace")
    if not str(operator_ns or "").strip():
        problems.append(f"{identity}: spec.operatorNamespace is required")
    elif "{{" not in str(operator_ns) and not _is_dns_label(operator_ns):
        # Both CRDs pattern this as an RFC-1123 label; a plausible-looking
        # non-empty string (uppercase, a space) would otherwise pass here
        # only to be rejected by the API server at admission.
        problems.append(f"{identity}: spec.operatorNamespace {operator_ns!r} is not a valid RFC-1123 namespace label")
    if obj.get("kind") == "InternalDatabase":
        classifier = spec.get("classifier") or {}
        for key in ("microserviceName", "scope"):
            if not classifier.get(key):
                problems.append(f"{identity}: spec.classifier.{key} is required")
        scope = classifier.get("scope")
        if isinstance(scope, str) and "{{" not in scope and scope not in ("service", "tenant"):
            problems.append(f"{identity}: spec.classifier.scope must be 'service' or 'tenant', got {scope!r}")
        if not spec.get("type"):
            problems.append(f"{identity}: spec.type is required")
    elif obj.get("kind") == "DatabaseAccessPolicy":
        if not spec.get("microserviceName"):
            problems.append(f"{identity}: spec.microserviceName is required")
        if not spec.get("services") and not spec.get("policy"):
            problems.append(f"{identity}: spec.services or spec.policy is required")


def _pilot_value(key: str) -> str:
    return f"pilot-{key.lower().replace('_', '-')}"


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
        existing = chart_values.get(key)
        values[key] = str(existing) if isinstance(existing, (str, int, float, bool)) else _pilot_value(key)
    helm_values = root.get("helmValues") or {}
    values.update({str(k): str(v) for k, v in helm_values.items()})

    release_ns = root["namespace"]
    for key, value in values.items():
        release_ns = release_ns.replace(f"{{{{ .Values.{key} }}}}", value)
    if "{{" in release_ns:
        release_ns = "dbaas-migration-pilot"

    cmd = [helm, "template", "dbaas-migration-pilot", str(chart_dir), "--namespace", release_ns]
    for key, value in sorted(values.items()):
        cmd += ["--set", f"{key}={value}"]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=180, check=False)
    except (OSError, subprocess.SubprocessError) as exc:
        return [f"helm template did not run: {exc}"]
    if proc.returncode != 0:
        return [f"helm template failed: {proc.stderr.strip()[:2000]}"]

    problems: list[str] = []
    try:
        docs = [d for d in yaml.safe_load_all(proc.stdout) if isinstance(d, dict)]
    except yaml.YAMLError as exc:
        return [f"rendered output is not valid YAML: {exc}"]
    for doc in docs:
        if doc.get("kind") in ("InternalDatabase", "DatabaseAccessPolicy"):
            _check_generated_object(doc, problems)
    return problems


# --------------------------------------------------------------------------- #
# Transaction
# --------------------------------------------------------------------------- #


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
                    target.write_text(content, encoding="utf-8", newline="")

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
    backups: dict[str, bytes | None] = {}
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
            backups[rel] = target.read_bytes() if existed else None
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
            fd, tmp_name = tempfile.mkstemp(dir=str(target.parent), prefix=f".{target.name}.")
            try:
                with open(fd, "wb") as handle:
                    handle.write(new_bytes)
                Path(tmp_name).replace(target)
            except BaseException:
                Path(tmp_name).unlink(missing_ok=True)
                raise
            applied.append(rel)
            (created if not existed else modified).append(rel)
    except Exception:
        for rel in reversed(applied):
            target = resolve_within(repo_root, rel, "target path")
            original = backups.get(rel)
            if original is None:
                target.unlink(missing_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(original)
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
            print(json.dumps({"status": "valid", "warnings": warnings}))
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
