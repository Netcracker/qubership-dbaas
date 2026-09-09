#!/usr/bin/env python3
"""Legacy DatabaseDeclaration analysis and safe deletion for the mounted-secret
migration.

The plan supplies only the paths of the declaration files it believes are now
superseded. ``strip_superseded`` does not take that on trust: it parses each
file, extracts the identity of every declaration in it, and deletes the file
only when every one of those declarations was regenerated as a SUPPORTED
datasource -- otherwise it blocks with a "split the file" message.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import _migration_common as common
import _resource_build as build

try:  # common.run() checks this before any work and reports it as a blocked result.
    import yaml
except ImportError:  # pragma: no cover - exercised only without the pinned dependency
    yaml = None  # type: ignore[assignment]

# Fields a legacy DatabaseDeclaration may carry that the mounted-secret runner
# reproduces on the generated InternalDatabase (from ``parameters``).
_DECLARATION_IDENTITY_FIELDS = {"classifierConfig", "type"}
_DECLARATION_CREATION_FIELDS = {"settings", "namePrefix"}
# Fields whose behaviour the mounted-secret generator does NOT carry across.
_DECLARATION_UNSUPPORTED_FIELDS = {"versioningConfig", "initialInstantiation"}
# CR-envelope keys that are noise when a whole document is one declaration.
_DECLARATION_WRAPPER_FIELDS = {"apiVersion", "kind", "subKind", "metadata"}


def generated_databases(datasources: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    """Map each generated database identity key to the datasource that produced it."""

    result: dict[str, dict[str, Any]] = {}
    for ds in sorted(
        (ds for ds in datasources if ds.get("migrationFeasibility") == "SUPPORTED"),
        key=lambda d: build.database_key(d["classifier"], d["type"]),
    ):
        result.setdefault(build.database_key(ds["classifier"], ds["type"]), ds)
    return result


def _normalize_legacy_classifier(
    classifier: dict[str, Any],
    *,
    doc_namespace: Any,
    workload_namespace: str,
) -> dict[str, Any]:
    """Bring a raw legacy classifier to the effective wire form the inventory uses.

    Only the namespace is filled in: discovery records
    ``inputs.datasources[].classifier`` with the workload namespace resolved, so a
    declaration that merely omits the namespace is not seen as a different
    database. When the classifier omits the namespace, the declaration document's
    own ``metadata.namespace`` wins over the workload fallback, so a declaration
    that belongs to a different namespace does not falsely match.

    ``microserviceName`` is compared verbatim. A legacy declaration that still
    templates it (``{{ .Values.SERVICE_NAME }}``) will therefore not match a
    generated identity that carries a literal, and cleanup blocks with a "split
    the file" message instead of silently deleting a declaration whose per-install
    service identity the migration did not preserve. ``_check_service_identity``
    is the guard on the other side: it requires the inventory identity to equal
    ``decisions.originService``.
    """

    out = dict(classifier)
    if not out.get("namespace"):
        if isinstance(doc_namespace, str) and doc_namespace.strip():
            out["namespace"] = doc_namespace
        else:
            out["namespace"] = workload_namespace
    return out


def _declaration_units(doc: dict[str, Any]) -> tuple[list[dict[str, Any]], str | None]:
    """Return ``(units, error)`` for one parsed document.

    ``units`` is the list of individual declaration objects; ``error`` is set when
    the document is not a usable legacy ``DatabaseDeclaration`` wrapper (unknown
    kind, an empty ``declarations`` list, or a non-object entry), so cleanup can
    refuse to delete a file it cannot fully account for.
    """

    kind = str(doc.get("subKind") or doc.get("kind") or "")
    recognized = kind == "DatabaseDeclaration" or (
        str(doc.get("kind") or "") == "DBaaS" and kind.lower() == "databasedeclaration"
    )
    inner = doc.get("spec") if isinstance(doc.get("spec"), dict) else doc

    if "declarations" in inner:
        if not recognized:
            return [], f"a declarations wrapper must be kind DatabaseDeclaration, got {kind or '<none>'!r}"
        declarations = inner.get("declarations")
        if not isinstance(declarations, list) or not declarations:
            return [], "declarations must be a non-empty list"
        if not all(isinstance(unit, dict) for unit in declarations):
            return [], "declarations contains a non-object entry"
        return declarations, None

    if not recognized:
        return [], f"unrecognized document kind {kind or '<none>'!r}"
    return [inner], None


def _verify_declaration_migrated(
    where: str,
    unit: dict[str, Any],
    generated: dict[str, dict[str, Any]],
    problems: list[str],
    *,
    doc_namespace: Any,
    workload_namespace: str,
) -> None:
    raw_classifier = (unit.get("classifierConfig") or {}).get("classifier")
    db_type = unit.get("type")
    if not isinstance(raw_classifier, dict) or not isinstance(db_type, str) or not db_type:
        problems.append(
            f"{where}: a declaration has no resolvable classifier/type, so the runner cannot "
            "prove it was migrated"
        )
        return
    label = db_type.lower()
    classifier = _normalize_legacy_classifier(
        raw_classifier,
        doc_namespace=doc_namespace,
        workload_namespace=workload_namespace,
    )
    ds = generated.get(build.database_key(classifier, db_type))
    if ds is None:
        fingerprint = build.canonical(build._wire_classifier(classifier))
        problems.append(
            f"{where}: declares a {label} database {fingerprint} that was not generated as a "
            "SUPPORTED datasource; split the file so this declaration is preserved on the REST path"
        )
        return

    unknown = sorted(
        set(unit)
        - _DECLARATION_IDENTITY_FIELDS
        - _DECLARATION_CREATION_FIELDS
        - _DECLARATION_UNSUPPORTED_FIELDS
        - _DECLARATION_WRAPPER_FIELDS
        - {"lazy"}
    )
    if unknown:
        problems.append(
            f"{where}: the {label} declaration carries fields the mounted-secret runner does not "
            f"reproduce: {', '.join(unknown)}"
        )
    for field in sorted(_DECLARATION_UNSUPPORTED_FIELDS & set(unit)):
        problems.append(
            f"{where}: the {label} declaration sets {field!r}, which the mounted-secret migration "
            "does not carry into the generated InternalDatabase"
        )
    if "lazy" in unit and str(unit["lazy"]).strip().lower() not in ("false", "none", ""):
        problems.append(
            f"{where}: the {label} declaration sets lazy={unit['lazy']!r}; the generated "
            "InternalDatabase is always eager, so deleting it would change provisioning behaviour"
        )

    params = ds.get("parameters") or {}
    if (unit.get("settings") or None) != (params.get("settings") or None):
        problems.append(
            f"{where}: the {label} declaration settings differ from the generated datasource "
            "parameters.settings; the generated InternalDatabase would not preserve them"
        )
    if (unit.get("namePrefix") or "") != (params.get("namePrefix") or ""):
        problems.append(
            f"{where}: the {label} declaration namePrefix {unit.get('namePrefix') or ''!r} differs "
            f"from the generated parameters.namePrefix {params.get('namePrefix') or ''!r}"
        )


def _parse_legacy_declaration_file(text: str) -> list[dict[str, Any]] | None:
    """Flat list of legacy declaration documents, or ``None`` if the file holds
    anything that is not a legacy DBaaS declaration."""

    stripped = text.lstrip()
    try:
        raw = (
            [json.loads(text)]
            if stripped.startswith(("{", "["))
            else list(yaml.safe_load_all(_scalar_safe(text)))
        )
    except (ValueError, yaml.YAMLError):
        return None
    flat: list[Any] = []
    for doc in raw:
        if isinstance(doc, list):
            flat.extend(doc)
        elif doc is not None:
            flat.append(doc)
    if not flat:
        return None
    for doc in flat:
        if not isinstance(doc, dict):
            return None
        kind = str(doc.get("subKind") or doc.get("kind") or "")
        if kind not in ("DatabaseDeclaration", "DbPolicy", "dbPolicy") and "declarations" not in doc:
            return None
    return flat


def strip_superseded(
    repo_root: Path,
    root: str,
    entries: list[Any],
    datasources: list[dict[str, Any]],
    workload_namespace: str,
    changes: common.Changes,
) -> None:
    """Delete a legacy declaration file only after the runner has itself confirmed
    that every database it declares was generated as a SUPPORTED datasource.

    The plan supplies only the paths. Provenance is not taken on trust: the runner
    parses each file, extracts the identity of every declaration in it, and blocks
    the whole delete if any of them was not migrated, so a file that also declares
    a blocked or dynamic identity keeps its declaration.
    """

    if not isinstance(entries, list) or not all(isinstance(item, str) and item for item in entries):
        raise common.bad_input(
            "plan.decisions.supersededDeclarations must be a list of repository-relative paths"
        )
    if not entries:
        return

    generated = generated_databases(datasources)
    for rel in entries:
        full_rel = common.join_rel(root, rel)
        target = common.resolve_within(repo_root, full_rel, what="superseded declaration")
        if not target.is_file():
            continue

        docs = _parse_legacy_declaration_file(target.read_text(encoding="utf-8"))
        if docs is None:
            raise common.unsupported(
                "mixed superseded file",
                [
                    f"{full_rel}: contains content other than legacy DBaaS DatabaseDeclaration "
                    "documents; split the file before migrating so unrelated content is preserved"
                ],
            )

        problems: list[str] = []
        for doc in docs:
            kind = str(doc.get("subKind") or doc.get("kind") or "")
            if kind in ("DbPolicy", "dbPolicy"):
                problems.append(
                    f"{full_rel}: contains a {kind} document; this migration does not replace "
                    "access policies, so deleting the file would drop it"
                )
                continue
            units, unit_error = _declaration_units(doc)
            if unit_error is not None:
                problems.append(f"{full_rel}: {unit_error}")
                continue
            doc_namespace = (doc.get("metadata") or {}).get("namespace")
            for unit in units:
                _verify_declaration_migrated(
                    full_rel,
                    unit,
                    generated,
                    problems,
                    doc_namespace=doc_namespace,
                    workload_namespace=workload_namespace,
                )

        if problems:
            raise common.unsupported(
                "a superseded declaration file would drop an unmigrated or changed identity",
                sorted(set(problems)),
            )
        changes.delete(full_rel)


def _scalar_safe(text: str) -> str:
    lines = []
    for line in text.splitlines():
        key, sep, rest = line.partition(":")
        value = rest.strip()
        if sep and value and "{{" in value and not (value[:1] in "'\""):
            lines.append(f"{key}: '{value.replace(chr(39), chr(39) * 2)}'")
        else:
            lines.append(line)
    return "\n".join(lines) + "\n"
