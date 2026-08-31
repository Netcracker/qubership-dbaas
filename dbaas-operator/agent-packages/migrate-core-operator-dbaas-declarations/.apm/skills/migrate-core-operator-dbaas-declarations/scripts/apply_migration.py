#!/usr/bin/env python3
"""Deterministic runner for the Core Operator DBaaS declaration migration.

The skill discovers the consumer repository and writes a plan; this script is
the only writer of migration files. It validates the plan and its source
preconditions, converts every selected legacy document with the shared field
mapping, derives target names from a fixed algorithm, writes one canonical
resource file per chart or manifest root, removes the migrated legacy documents
from their source, and validates the result in a temporary tree before touching
the working copy.

Invocation and the plan / result envelopes are defined in ``_migration_common``.

Plan (`migrationKind: "core-declarations"`) shape::

    {
      "inputs": {
        "sources": [
          {"path": "chart/templates/dbaas-configuration.json",
           "root": "chart", "rootKind": "helm", "documents": null}
        ]
      },
      "decisions": {
        "operatorNamespace": "dbaas-system",
        "serviceName": "{{ .Values.SERVICE_NAME }}",
        "serviceNameExplicit": false,
        "namespace": "{{ .Values.NAMESPACE }}",
        "outputFileByRoot": {"chart": "templates/dbaas-operator-resources.yaml"},
        "resourceNames": {"chart/templates/dbaas-configuration.json#0#1": "configs-db"},
        "warningResolutions": ["<exact converter warning the agent has resolved>"],
        "outputOwnership": {"chart/templates/dbaas-operator-resources.yaml": {"sha256": "<hash>"}}
      },
      "targets": [
        {"path": "chart/templates/dbaas-operator-resources.yaml", "ownership": "create"}
      ]
    }
"""

from __future__ import annotations

import collections
import json
import re
from pathlib import Path
from typing import Any

import _core_convert as convert
import _migration_common as common
from _helm_source import UnsupportedHelm, parse_source

MIGRATION_KIND = "core-declarations"
DEFAULT_HELM_OUTPUT = "templates/dbaas-operator-resources.yaml"
DEFAULT_PLAIN_OUTPUT = "dbaas-operator-resources.yaml"
# A whole-line Helm guard action, as emitted by `_render_file` / `_reserialize_yaml`.
# Only these exact standalone lines are stripped for validation -- never an
# arbitrary line that merely starts with "{{" (which could be block-scalar text).
_GUARD_LINE = re.compile(r"^\s*\{\{-?\s*(if|else|end)\b.*\}\}\s*$")

_SOURCE_KEYS = {"path", "root", "rootKind", "documents"}
_DECISION_KEYS = {
    "operatorNamespace",
    "serviceName",
    "serviceNameExplicit",
    "namespace",
    "outputFileByRoot",
    "resourceNames",
    "warningResolutions",
    "outputOwnership",
}


class CoreEngine:
    migration_kind = MIGRATION_KIND
    required_modules = ("yaml",)
    input_keys = {"sources"}
    decision_keys = _DECISION_KEYS

    # ----------------------------------------------------------------- #

    def affected_roots(self, repo_root: Path, plan: common.Plan) -> list[str]:
        roots: list[str] = []
        for source in _sources(plan):
            root = source["root"]
            if root not in roots:
                roots.append(root)
        return roots

    # ----------------------------------------------------------------- #

    def build_changes(self, repo_root: Path, plan: common.Plan) -> common.Changes:
        decisions = plan.decisions
        common._reject_unknown(decisions, _DECISION_KEYS, "plan.decisions")
        operator_namespace = decisions.get("operatorNamespace")
        if not isinstance(operator_namespace, str) or not operator_namespace.strip():
            raise common.bad_input("plan.decisions.operatorNamespace is required and must be non-empty")

        service_name = decisions.get("serviceName")
        if service_name is not None:
            common.expect(service_name, str, "plan.decisions.serviceName")
        namespace = decisions.get("namespace")
        if namespace is not None:
            common.expect(namespace, str, "plan.decisions.namespace")
        ctx = convert.ConversionContext(
            operator_namespace=operator_namespace,
            service_name=service_name or convert.DEFAULT_SERVICE_TEMPLATE,
            service_name_explicit=common.expect_bool(
                decisions.get("serviceNameExplicit"),
                "plan.decisions.serviceNameExplicit",
                default=False,
            ),
            namespace=namespace or convert.DEFAULT_NAMESPACE_TEMPLATE,
        )
        # Not unique: two occurrences of the same warning need two approvals.
        warning_resolutions = common.expect_str_list(
            common.expect_optional(
                decisions.get("warningResolutions"), list, "plan.decisions.warningResolutions", []
            ),
            "plan.decisions.warningResolutions",
        )
        resolutions_left = collections.Counter(warning_resolutions)
        name_overrides = common.expect_str_map(
            common.expect_optional(
                decisions.get("resourceNames"), dict, "plan.decisions.resourceNames", {}
            ),
            "plan.decisions.resourceNames",
        )
        raw_output_by_root = common.expect_str_map(
            common.expect_optional(
                decisions.get("outputFileByRoot"), dict, "plan.decisions.outputFileByRoot", {}
            ),
            "plan.decisions.outputFileByRoot",
        )
        output_by_root: dict[str, str] = {}
        for key, value in raw_output_by_root.items():
            canon = _normalize_root(key)
            if canon in output_by_root:
                raise common.bad_input(
                    f"plan.decisions.outputFileByRoot has two entries for root {canon!r}"
                )
            output_by_root[canon] = value
        ownership = common.expect_optional(
            decisions.get("outputOwnership"), dict, "plan.decisions.outputOwnership", {}
        )
        used_override_keys: set[str] = set()

        source_paths = {source["path"] for source in _sources(plan)}
        colliding = _output_paths(plan) & source_paths
        if colliding:
            raise common.unsupported(
                "a generated output path is also a migration source",
                [
                    f"{path}: output path collides with a source file; set a distinct "
                    "decisions.outputFileByRoot entry for that root"
                    for path in sorted(colliding)
                ],
            )

        # One normalized root must have a single rootKind, and a plan must not mix
        # Helm and plain roots -- their namespace/service semantics differ, so
        # they are run separately.
        kind_by_norm: dict[str, str] = {}
        for source in _sources(plan):
            norm = _normalize_root(source["root"])
            kind = source.get("rootKind", "plain")
            if norm in kind_by_norm and kind_by_norm[norm] != kind:
                raise common.bad_input(
                    f"root {source['root']!r} is declared as both "
                    f"{kind_by_norm[norm]!r} and {kind!r}"
                )
            kind_by_norm[norm] = kind
        if {"helm", "plain"} <= set(kind_by_norm.values()):
            raise common.bad_input(
                "plan.inputs.sources mixes helm and plain roots; run them in separate migrations"
            )

        changes = common.Changes()
        blocking: list[str] = []
        per_root: dict[str, list[tuple[dict[str, Any], str | None]]] = {}
        root_kind_by_root: dict[str, str] = {}
        source_rewrites: dict[str, str | None] = {}
        identities: set[tuple[str, str, str]] = set()

        for source in _sources(plan):
            common._reject_unknown(source, _SOURCE_KEYS, "plan.inputs.sources[]")
            rel = source["path"]
            # Canonicalize: "chart" and "chart/" are one root and must index one
            # per_root bucket, or the second output write silently overwrites the
            # first while both sources are still deleted.
            root = _normalize_root(source["root"])
            if not root:
                raise common.bad_input(
                    f"source {rel!r} root must name a directory, not the repository root"
                )
            root_kind = source.get("rootKind", "plain")
            root_kind_by_root[root] = root_kind
            path = common.resolve_within(repo_root, rel, what="source path")
            if not path.is_file():
                if _declared_absent(plan, rel):
                    # Discovery already recorded this source as gone: a prior apply
                    # consumed it. Skip it so a repeated run stays idempotent.
                    continue
                blocking.append(f"{rel}: source file is missing")
                continue

            try:
                documents, guards, remaining_text = _load_source(path, rel)
            except UnsupportedHelm as exc:
                blocking.extend(exc.entries)
                continue
            except common.MigrationError as exc:
                blocking.extend([str(exc), *exc.entries])
                continue

            selected = source.get("documents")
            if selected is not None:
                unselected_supported = [
                    index
                    for index, doc in enumerate(documents)
                    if index not in set(selected)
                    and convert.is_supported_legacy_item(_as_item(doc))
                ]
                if unselected_supported:
                    blocking.append(
                        f"{rel}: source.documents leaves supported legacy documents "
                        f"{unselected_supported} unmigrated; migrate the whole file or split it first"
                    )
            selected_docs = _select(documents, selected)
            resources, warnings, errors = convert.convert_documents(
                selected_docs, ctx, source_ref=rel
            )
            blocking.extend(errors)
            for warning in warnings:
                # An approval is source-scoped (`<path>: <warning>`) and consumed
                # once, so approving one occurrence does not blanket every
                # identical warning across sources or within one source.
                scoped = f"{rel}: {warning}"
                if resolutions_left[scoped] > 0:
                    resolutions_left[scoped] -= 1
                    changes.warn(f"{rel}: accepted converter warning: {warning}")
                else:
                    blocking.append(f"{rel}: unresolved converter warning: {warning}")

            # A legacy document is stripped from the source once it counts as
            # migrated. Refuse to rewrite or delete the source unless every
            # selected legacy document actually produced a resource, so a skipped
            # conversion can never leave the source gone with no replacement.
            produced_refs = {resource.source_ref for resource in resources}
            for doc_index, doc in enumerate(selected_docs):
                if doc is None:
                    continue
                if (
                    convert.is_supported_legacy_item(_as_item(doc))
                    and f"{rel}#{doc_index}" not in produced_refs
                ):
                    blocking.append(
                        f"{rel}: legacy document #{doc_index} was selected for migration but "
                        "produced no converted resource; refusing to rewrite or delete the source"
                    )

            for resource in resources:
                name = _derive_name(resource, name_overrides, used_override_keys)
                resource.body["metadata"]["name"] = name
                if "{{" not in name and not common.is_dns_label(name):
                    blocking.append(f"{rel}: derived name {name!r} is not a DNS-1123 label")
                identity = (
                    resource.body["kind"],
                    str(resource.body["metadata"].get("namespace", "")),
                    name,
                )
                if identity in identities:
                    blocking.append(
                        f"duplicate generated resource kind={identity[0]} "
                        f"namespace={identity[1]} name={identity[2]}"
                    )
                identities.add(identity)
                guard = guards.get(resource.source_ref)
                per_root.setdefault(root, []).append((resource.body, guard))

            source_rewrites[rel] = remaining_text

        for key in sorted(set(name_overrides) - used_override_keys):
            blocking.append(
                f"decisions.resourceNames key {key!r} matched no generated resource; a "
                "multi-declaration wrapper is addressed as '<source>#<document>#<item>'"
            )
        for entry, remaining in sorted(resolutions_left.items()):
            if remaining > 0:
                blocking.append(
                    f"decisions.warningResolutions entry {entry!r} matched no converter warning "
                    "(use the source-scoped form '<source>: <warning>')"
                )

        if blocking:
            raise common.unsupported(
                "the plan contains conditions the runner cannot apply", blocking
            )

        for root, entries in per_root.items():
            output_rel = _output_path(
                root, root_kind_by_root.get(root, "plain"), output_by_root.get(root)
            )
            entries.sort(key=lambda item: _sort_key(item[0]))
            content = _render_file([body for body, _ in entries], entries)
            _guard_collision(repo_root, output_rel, ownership, content)
            changes.set_content(output_rel, content)

        for rel, text in source_rewrites.items():
            if text is None:
                changes.delete(rel)
            else:
                changes.set_content(rel, text)

        return changes

    # ----------------------------------------------------------------- #

    def validate_tree(
        self,
        tree_root: Path,
        repo_root: Path,
        plan: common.Plan,
        changes: common.Changes,
    ) -> list[common.ValidationResult]:
        results = [common.ValidationResult("plan", "passed")]
        problems: list[str] = []
        output_paths = _output_paths(plan)

        plain_outputs = _plain_output_paths(plan)
        for path, content in changes.files.items():
            file_path = tree_root / path
            if content is None:
                if file_path.exists():
                    problems.append(f"{path}: expected the migrated source file to be removed")
                continue
            if path in output_paths:
                problems.extend(_check_generated(path, file_path))
                if path in plain_outputs and "{{" in (content or ""):
                    problems.append(
                        f"{path}: a plain-manifest output must not contain Helm expressions"
                    )
            elif file_path.exists():
                problems.extend(_check_source_cleaned(path, file_path))

        results.append(
            common.ValidationResult(
                "generated-resources",
                "failed" if problems else "passed",
                "; ".join(problems),
            )
        )
        return results


# --------------------------------------------------------------------------- #
# Helpers
# --------------------------------------------------------------------------- #


def _declared_absent(plan: common.Plan, rel: str) -> bool:
    return any(pre.path == rel and pre.absent for pre in plan.preconditions)


def _sources(plan: common.Plan) -> list[dict[str, Any]]:
    sources = plan.inputs.get("sources")
    if not isinstance(sources, list) or not sources:
        raise common.bad_input("plan.inputs.sources must be a non-empty list")
    for source in sources:
        if not isinstance(source, dict) or not isinstance(source.get("path"), str):
            raise common.bad_input("each plan.inputs.sources entry needs a string path")
        if not isinstance(source.get("root"), str) or not source["root"]:
            raise common.bad_input(f"source {source.get('path')!r} needs a repository-relative root")
        if source.get("rootKind", "plain") not in {"helm", "plain"}:
            raise common.bad_input(f"source {source['path']!r} rootKind must be 'helm' or 'plain'")
    return sources


def _select(documents: list[Any], indices: Any) -> list[Any]:
    """Keep the selected documents; blank the rest so index alignment survives."""

    if indices is None:
        return documents
    # bool is a subclass of int, so `[true]` must not slip through as index 1.
    if not isinstance(indices, list) or not all(
        isinstance(i, int) and not isinstance(i, bool) for i in indices
    ):
        raise common.bad_input("source.documents must be a list of integers or null")
    keep = set(indices)
    if any(i < 0 or i >= len(documents) for i in keep):
        raise common.bad_input("source.documents references a document index that does not exist")
    return [doc if index in keep else None for index, doc in enumerate(documents)]


def _load_source(path: Path, rel: str) -> tuple[list[Any], dict[str, str | None], str | None]:
    """Return (documents, guard-by-source-ref, remaining source text or None)."""

    suffix = path.suffix.lower()
    text = path.read_text(encoding="utf-8-sig")
    guards: dict[str, str | None] = {}

    if suffix == ".json":
        try:
            data = json.loads(text, parse_constant=_reject_json_constant)
        except ValueError as exc:
            raise common.bad_input(f"{rel}: invalid JSON: {exc}") from None
        documents = data if isinstance(data, list) else [data]
        migrated = [_legacy_verdict(doc) == "all" for doc in documents]
        _reject_mixed_sequences(rel, documents)
        if isinstance(data, list):
            remaining = [doc for doc, done in zip(documents, migrated) if not done]
            remaining_text = (
                None if not remaining else json.dumps(remaining, indent=2) + "\n"
            )
        else:
            remaining_text = None if all(migrated) else text
        return documents, guards, remaining_text

    if suffix in {".yaml", ".yml"}:
        parsed = parse_source(text, filename=rel)
        documents = [entry.body for entry in parsed]
        for index, entry in enumerate(parsed):
            guards[f"{rel}#{index}"] = entry.guard
        _reject_mixed_sequences(rel, documents)
        migrated = [_legacy_verdict(entry.body) == "all" for entry in parsed]
        remaining = [
            entry for entry, done in zip(parsed, migrated) if not done
        ]
        if not remaining:
            remaining_text: str | None = None
        else:
            remaining_text = _reserialize_yaml(rel, remaining)
        return documents, guards, remaining_text

    raise common.bad_input(f"{rel}: unsupported source extension {suffix!r}")


def _as_item(doc: Any) -> Any:
    if isinstance(doc, list):
        for item in doc:
            if isinstance(item, dict):
                return item
    return doc


def _legacy_verdict(body: Any) -> str:
    """Classify a document: ``none`` (not legacy), ``all`` (every sequence item is
    a supported legacy declaration), or ``mixed`` (a legacy declaration sits next
    to a non-mapping value or an unrelated object)."""

    if not isinstance(body, list):
        return "all" if convert.is_supported_legacy_item(_as_item(body)) else "none"
    if not body:
        return "none"
    # Every item counts, including non-mappings: a bare scalar next to a legacy
    # declaration is unrelated content that must not be deleted with the file.
    flags = [
        isinstance(item, dict) and convert.is_supported_legacy_item(item) for item in body
    ]
    if not any(flags):
        return "none"
    return "all" if all(flags) else "mixed"


def _reject_mixed_sequences(rel: str, documents: list[Any]) -> None:
    for index, body in enumerate(documents):
        if _legacy_verdict(body) == "mixed":
            raise common.unsupported(
                "a document mixes legacy declarations with unrelated objects",
                [
                    f"{rel}: document #{index} is a sequence that mixes legacy DBaaS declarations "
                    "with other objects; split it into separate documents before migrating"
                ],
            )


def _reject_json_constant(value: str) -> Any:
    raise ValueError(f"numeric constant {value!r} is not valid JSON")


_DOC_MARKER = "---\n"


def _strip_doc_marker(dumped: str) -> str:
    """Remove exactly the leading ``---\\n`` serializer prefix.

    A character-set strip (``lstrip('-\\n')``) also eats a leading ``-`` list
    marker from a kept top-level YAML list, corrupting the rewritten source.
    """

    return dumped[len(_DOC_MARKER):] if dumped.startswith(_DOC_MARKER) else dumped


def _reserialize_yaml(rel: str, entries: list[Any]) -> str:
    chunks: list[str] = []
    for entry in entries:
        body = _strip_doc_marker(convert.dump_resources([entry.body])).rstrip("\n")
        if entry.guard:
            chunks.append(f"{_DOC_MARKER}{entry.guard}\n{body}\n{{{{- end }}}}\n")
        else:
            chunks.append(f"{_DOC_MARKER}{body}\n")
    text = "".join(chunks)
    # Never commit a corrupt rewrite: reparse the kept content (guards removed).
    probe = "\n".join(
        line for line in text.splitlines() if not _GUARD_LINE.match(line)
    )
    try:
        list(convert.yaml.safe_load_all(probe))  # type: ignore[union-attr]
    except Exception as exc:  # noqa: BLE001
        raise common.unsupported(
            "the rewritten source would not be valid YAML",
            [f"{rel}: {exc}"],
        ) from None
    return text


def _override_keys(resource: convert.ConvertedResource) -> list[str]:
    """resourceNames keys that may name this resource.

    A declaration inside a multi-declaration wrapper is addressed only by
    ``<source>#<document>#<item>`` so one override cannot fan out to every child.
    """

    if resource.parent_index is not None:
        return [f"{resource.source_ref}#{resource.parent_index}"]
    return [resource.source_ref]


def _derive_name(
    resource: convert.ConvertedResource, overrides: dict[str, Any], used: set[str]
) -> str:
    raw: str | None = None
    for key in _override_keys(resource):
        candidate = overrides.get(key)
        if isinstance(candidate, str) and candidate:
            used.add(key)
            raw = candidate
            break
    if raw is None:
        if resource.parent_name and resource.parent_index is not None:
            raw = f"{resource.parent_name}-{resource.parent_index}"
        elif resource.parent_name:
            raw = str(resource.parent_name)
        else:
            raw = resource.name_hint
    if "{{" in raw:
        return raw  # a templated name; its literal form is validated after render
    return common.dns_label(raw)


def _normalize_root(root: str) -> str:
    normalized = common.normalize_roots([root])
    return normalized[0] if normalized else ""


def _output_path(root: str, root_kind: str, override: Any) -> str:
    if isinstance(override, str) and override:
        rel = override
    elif root_kind == "helm":
        rel = DEFAULT_HELM_OUTPUT
    else:
        rel = DEFAULT_PLAIN_OUTPUT
    return f"{root.rstrip('/')}/{rel}"


def _guard_collision(
    repo_root: Path, output_rel: str, ownership: dict[str, Any], rendered: str
) -> None:
    target = common.resolve_within(repo_root, output_rel, what="output path")
    if not target.exists():
        return
    actual = common.sha256_file(target)
    if actual == common.sha256_bytes(rendered.encode("utf-8")):
        return  # already the generated content; a repeated run is idempotent
    declared = ownership.get(output_rel)
    if not isinstance(declared, dict) or "sha256" not in declared:
        raise common.unsupported(
            "output file collision",
            [f"{output_rel}: file exists and is not declared in decisions.outputOwnership"],
        )
    if actual != declared["sha256"]:
        raise common.MigrationError(
            common.EXIT_PRECONDITION,
            "owned output file changed since discovery",
            [f"{output_rel}: sha256 {actual} does not match declared {declared['sha256']}"],
        )


def _sort_key(body: dict[str, Any]) -> tuple[str, str, str]:
    metadata = body.get("metadata") or {}
    return (
        str(body.get("kind", "")),
        str(metadata.get("namespace", "")),
        str(metadata.get("name", "")),
    )


def _render_file(bodies: list[dict[str, Any]], entries: list[tuple[dict[str, Any], str | None]]) -> str:
    guard_by_id = {id(body): guard for body, guard in entries}
    chunks: list[str] = []
    for body in bodies:
        doc = convert.dump_resources([body])
        guard = guard_by_id.get(id(body))
        if guard:
            inner = doc[len("---\n"):] if doc.startswith("---\n") else doc
            chunks.append(f"---\n{guard}\n{inner.rstrip(chr(10))}\n{{{{- end }}}}\n")
        else:
            chunks.append(doc)
    return "".join(chunks)


def _output_by_root(plan: common.Plan) -> dict[str, str]:
    result: dict[str, str] = {}
    raw = plan.decisions.get("outputFileByRoot")
    if isinstance(raw, dict):
        for key, value in raw.items():
            if isinstance(key, str) and isinstance(value, str):
                result[_normalize_root(key)] = value
    return result


def _output_paths(plan: common.Plan, *, plain_only: bool = False) -> set[str]:
    """Every repository-relative output path the plan will generate."""

    output_by_root = _output_by_root(plan)
    paths: set[str] = set()
    for source in plan.inputs.get("sources") or []:
        if not isinstance(source, dict):
            continue
        raw_root = source.get("root")
        if not isinstance(raw_root, str) or not raw_root:
            continue
        root = _normalize_root(raw_root)
        if not root:
            continue
        kind = source.get("rootKind", "plain")
        if plain_only and kind != "plain":
            continue
        paths.add(_output_path(root, kind, output_by_root.get(root)))
    return paths


def _plain_output_paths(plan: common.Plan) -> set[str]:
    return _output_paths(plan, plain_only=True)


def _check_source_cleaned(path: str, file_path: Path) -> list[str]:
    """A rewritten (not deleted) source must parse and hold no legacy declaration.

    Parsed, not a substring scan: a comment or an unrelated string containing
    ``DatabaseDeclaration`` must not fail cleanup.
    """

    text = file_path.read_text(encoding="utf-8")
    if path.endswith(".json"):
        try:
            data = json.loads(text)
        except ValueError as exc:
            return [f"{path}: rewritten source is not valid JSON: {exc}"]
        docs = data if isinstance(data, list) else [data]
    else:
        probe = "\n".join(line for line in text.splitlines() if not _GUARD_LINE.match(line))
        try:
            docs = list(convert.yaml.safe_load_all(probe))  # type: ignore[union-attr]
        except Exception as exc:  # noqa: BLE001
            return [f"{path}: rewritten source is not valid YAML: {exc}"]
    for doc in docs:
        for item in convert.as_legacy_items(doc):
            if convert.is_supported_legacy_item(item):
                return [f"{path}: a legacy DBaaS declaration is still present after cleanup"]
    return []


def _check_generated(path: str, file_path: Path) -> list[str]:
    problems: list[str] = []
    text = file_path.read_text(encoding="utf-8")
    # Remove only the exact whole-line guard actions this runner emits, never a
    # line that merely starts with "{{" (that could be block-scalar content).
    stripped = "\n".join(line for line in text.splitlines() if not _GUARD_LINE.match(line))
    try:
        docs = [doc for doc in convert.yaml.safe_load_all(stripped) if doc]  # type: ignore[union-attr]
    except Exception as exc:  # noqa: BLE001
        return [f"{path}: generated file is not valid YAML after guard removal: {exc}"]
    for doc in docs:
        identity = (doc.get("metadata") or {}).get("name", "<unnamed>")
        if doc.get("apiVersion") != "dbaas.netcracker.com/v1":
            problems.append(f"{path}: {identity} has wrong apiVersion")
        kind = doc.get("kind")
        if kind not in {"InternalDatabase", "DatabaseAccessPolicy"}:
            problems.append(f"{path}: {identity} has unexpected kind {kind!r}")
        spec = doc.get("spec") or {}
        if "classifierConfig" in spec:
            problems.append(f"{path}: {identity} still carries spec.classifierConfig")
        if not str(spec.get("operatorNamespace", "")).strip():
            problems.append(f"{path}: {identity} is missing spec.operatorNamespace")
        if kind == "InternalDatabase":
            classifier = spec.get("classifier") or {}
            for key in ("microserviceName", "scope"):
                if not classifier.get(key):
                    problems.append(f"{path}: {identity} is missing classifier.{key}")
            if not spec.get("type"):
                problems.append(f"{path}: {identity} is missing spec.type")
        if kind == "DatabaseAccessPolicy":
            if not spec.get("microserviceName"):
                problems.append(f"{path}: {identity} is missing spec.microserviceName")
            has_services = isinstance(spec.get("services"), list) and spec["services"]
            has_policy = isinstance(spec.get("policy"), list) and spec["policy"]
            if not has_services and not has_policy:
                problems.append(f"{path}: {identity} has no non-empty services or policy list")
            services = spec.get("services")
            if "services" in spec and (
                not isinstance(services, list)
                or not all(
                    isinstance(entry, dict)
                    and isinstance(entry.get("name"), str)
                    and entry.get("name", "").strip()
                    and isinstance(entry.get("roles"), list)
                    and entry.get("roles")
                    and all(isinstance(r, str) and r.strip() for r in entry["roles"])
                    for entry in services
                )
            ):
                problems.append(f"{path}: {identity} spec.services entries are not valid ServiceRole objects")
            policy = spec.get("policy")
            if "policy" in spec and (
                not isinstance(policy, list)
                or not all(
                    isinstance(entry, dict)
                    and isinstance(entry.get("type"), str)
                    and entry.get("type", "").strip()
                    and isinstance(entry.get("defaultRole"), str)
                    and entry.get("defaultRole", "").strip()
                    for entry in policy
                )
            ):
                problems.append(f"{path}: {identity} spec.policy entries are not valid PolicyRole objects")
            if "disableGlobalPermissions" in spec and not isinstance(
                spec["disableGlobalPermissions"], bool
            ):
                problems.append(f"{path}: {identity} spec.disableGlobalPermissions must be a boolean")
    return problems


if __name__ == "__main__":
    raise SystemExit(common.run(CoreEngine()))
