#!/usr/bin/env python3
"""Deterministic writer for the mounted-secret DBaaS migration skill.

The skill builds a datasource inventory and a small semantic plan, then calls this
script exactly once as the only process allowed to create, modify, or delete files
in the consumer repository. Nothing repairs the result by hand afterward.

    apply_migration.py --repo-root PATH --plan PLAN.json --check|--apply

Contract:

- one JSON result envelope to stdout;
- exit 0 for a valid / changed / unchanged result;
- exit 2 invalid plan, 3 stale source hash, 4 unsupported input/dependency,
  5 generated-output validation failure;
- ``--help`` exits 0 (argparse default); unknown plan fields are rejected;
- ``--check`` never writes; a repeated ``--apply`` reports ``unchanged``;
- never a bare traceback for an expected condition.

Plan shape (one entry per deployment root; a plan may declare more than one --
identity and collision checks are scoped to each root, so two roots may legitimately
reuse the same generated name when their workload namespaces differ)::

    {
      "roots": [
        {
          "root": "chart",                 // repo-relative; "" or "." is the repo root
          "kind": "helm",                  // "helm" | "plain"
          "outputFile": "templates/dbaas-mounted-secret-resources.yaml",
          "operatorNamespace": "{{ .Values.DBAAS_OPERATOR_NAMESPACE }}",
          "workloadNamespace": "{{ .Values.NAMESPACE }}",
          "originService": "orders",
          "helmValues": {"API_DBAAS_ADDRESS": "http://dbaas-aggregator.dbaas-operator:8080"},
          "datasources": [
            {
              "id": "orders-postgresql-service", "type": "postgresql",
              "classifier": {"microserviceName": "orders", "scope": "service"},
              "requestedRoles": [""], "parameters": {"namePrefix": "", "settings": {}},
              "resourceName": null           // required only when classifier is templated
            }
          ],
          "claims": [
            {"datasourceId": "orders-postgresql-service", "role": "",
             "workloadFile": "templates/deployment.yaml", "workloadKind": "Deployment",
             "workloadName": "orders", "containers": ["orders"], "initContainers": []}
          ],
          "supersededDeclarations": [
            {"path": "templates/dbaas-declaration.yaml", "documentIndex": null}
          ],
          "sourceHashes": {"templates/deployment.yaml": "<sha256>", "...": "..."}
        }
      ]
    }

``sourceHashes`` keys are root-relative (like ``workloadFile`` and
``supersededDeclarations[].path``) and must cover every path the writer reads
under this root (every workload file, every superseded-declaration file, and
``values.yaml`` / ``values.schema.json`` when present) with the SHA-256 the
skill inspected; a mismatch is a stale-plan error (exit 3), not silently
re-read.
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
from pathlib import Path, PurePosixPath
from typing import Any

try:
    import yaml
except ImportError:  # pragma: no cover - exercised only without the pinned dependency
    yaml = None

sys.path.insert(0, str(Path(__file__).resolve().parent))
if yaml is not None:
    # validate_generated.py itself raises SystemExit at import time when
    # PyYAML is missing; importing it unconditionally here would crash the
    # whole module before main() ever gets a chance to report the missing
    # dependency through the documented JSON envelope and exit code 4.
    import validate_generated as validator  # noqa: E402
else:
    validator = None  # type: ignore[assignment]

EXIT_OK = 0
EXIT_BAD_INPUT = 2
EXIT_STALE = 3
EXIT_UNSUPPORTED = 4
EXIT_VALIDATION = 5

DNS_MAX = 63
RESERVED_CLASSIFIER_KEYS = {"microserviceName", "scope", "namespace", "tenantId", "customKeys"}
MOUNT_ROOT = "/etc/secrets/dbaas-secrets"
DBAAS_OPERATOR_NAMESPACE_VALUE = "DBAAS_OPERATOR_NAMESPACE"
_TAIL_BUDGET = 20
_PILOT_RELEASE = "dbaas-migration-pilot"
_SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")


class MigrationError(Exception):
    def __init__(self, exit_code: int, message: str, entries: list[str] | None = None) -> None:
        super().__init__(message)
        self.exit_code = exit_code
        self.entries = list(entries or [])


def bad_input(message: str, entries: list[str] | None = None) -> MigrationError:
    return MigrationError(EXIT_BAD_INPUT, message, entries)


def stale(message: str, entries: list[str] | None = None) -> MigrationError:
    return MigrationError(EXIT_STALE, message, entries)


def unsupported(message: str, entries: list[str] | None = None) -> MigrationError:
    return MigrationError(EXIT_UNSUPPORTED, message, entries)


# --------------------------------------------------------------------------- #
# Path safety
# --------------------------------------------------------------------------- #


def canonical_path(value: Any, *, what: str = "path") -> str:
    if not isinstance(value, str) or not value:
        raise bad_input(f"{what} must be a non-empty string")
    normalized = value.replace("\\", "/")
    # Reject outright rather than silently reinterpreting: a leading "/" (a
    # POSIX absolute path, or a UNC "\\server\share" once backslashes
    # normalize to "//server/share") and a drive letter ("C:...") both look
    # like an ordinary relative path once you strip/ignore the parts that
    # make them dangerous -- collapsing "/etc/passwd" to "etc/passwd", or
    # letting pathlib treat "C:/..." as absolute and discard repo_root
    # entirely when joined, silently repoints the writer at the wrong file
    # instead of failing loudly.
    if normalized.startswith("/") or (len(normalized) > 1 and normalized[1] == ":"):
        raise bad_input(f"{what} must be repository-relative, got {value!r}")
    parts = [part for part in normalized.split("/") if part not in ("", ".")]
    if ".." in parts:
        raise bad_input(f"{what} must not contain '..', got {value!r}")
    if not parts:
        raise bad_input(f"{what} must not be empty, got {value!r}")
    return "/".join(parts)


def resolve_within(repo_root: Path, relative: str, *, what: str = "path") -> Path:
    rel = canonical_path(relative, what=what)
    root = repo_root.resolve()
    candidate = (root / rel).resolve()
    try:
        candidate.relative_to(root)
    except ValueError:
        raise bad_input(f"{what} escapes the repository root: {relative!r}") from None
    walk = root
    for part in PurePosixPath(rel).parts:
        walk = walk / part
        if walk.is_symlink():
            raise bad_input(f"{what} traverses a symlink at {walk.relative_to(root).as_posix()!r}")
    return candidate


def join_rel(root: str, rel: str, *, what: str = "path") -> str:
    """Join a root-relative child path onto ``root``.

    ``rel`` is validated as its own repository-relative path *before* it is
    joined onto ``root`` -- validating the combined string instead would let
    an absolute path (``/etc/a``), a drive letter (``C:\\a``), or a UNC path
    (``\\\\server\\share``) hide its dangerous prefix behind whatever
    ``root`` prepends to it, since ``canonical_path``'s "does this start
    with / or a drive letter" check only fires at position 0 of the string
    it is given -- a prefix like ``chart/`` pushes it out of view instead of
    rejecting it.
    """

    safe_rel = canonical_path(rel, what=what)
    root = root.strip("/")
    return safe_rel if root in ("", ".") else f"{root}/{safe_rel}"


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


# --------------------------------------------------------------------------- #
# DNS-1123 naming
# --------------------------------------------------------------------------- #


def _slug(value: Any) -> str:
    slug = re.sub(r"-+", "-", re.sub(r"[^a-z0-9-]+", "-", str(value).lower())).strip("-")
    return slug or "dbaas"


def dns_label(*parts: Any, keep_tail: str = "", limit: int = DNS_MAX) -> str:
    """A deterministic RFC-1123 label. ``keep_tail`` is never truncated: past
    ``limit`` the identity is hashed and shortened -- down to nothing if that is
    what it takes -- ahead of it, so two long identities (or the same identity
    with different tails) never collapse to one name."""

    identity = _slug("-".join(str(p) for p in parts if p not in (None, "")))
    tail = _slug(keep_tail) if keep_tail else ""
    tail_part = f"-{tail}" if tail else ""
    full = identity + tail_part
    if len(full) <= limit:
        return full
    digest = hashlib.sha256(identity.encode("utf-8")).hexdigest()[:8]
    min_total = len(digest) + len(tail_part)
    if min_total > limit:
        raise unsupported(
            f"cannot build a DNS-1123 label within {limit} characters for keep_tail {keep_tail!r}"
        )
    head_len = max(limit - 1 - len(digest) - len(tail_part), 0)
    head = identity[:head_len].strip("-")
    return f"{head}-{digest}{tail_part}" if head else f"{digest}{tail_part}"


def is_dns_label(value: Any) -> bool:
    return isinstance(value, str) and 1 <= len(value) <= DNS_MAX and re.fullmatch(
        r"[a-z0-9](?:[-a-z0-9]*[a-z0-9])?", value
    ) is not None


def is_templated(value: Any) -> bool:
    return isinstance(value, str) and "{{" in value


def wire_classifier(classifier: dict[str, Any]) -> dict[str, Any]:
    """Flatten to the effective runtime identity: a top-level key wins over the
    same key repeated inside ``extraKeys``."""

    wire = {key: value for key, value in classifier.items() if key != "extraKeys"}
    for key, value in (classifier.get("extraKeys") or {}).items():
        wire.setdefault(key, value)
    return wire


def canonical(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def identity_stem(classifier: dict[str, Any], db_type: str, resource_name: Any) -> tuple[str, bool]:
    microservice = classifier["microserviceName"]
    scope = classifier["scope"]
    if is_templated(microservice) or is_templated(scope):
        if not isinstance(resource_name, str) or not resource_name:
            raise unsupported(
                f"datasource classifier is templated (microserviceName={microservice!r}, "
                f"scope={scope!r}); the plan must supply resourceName -- an explicit stem "
                "containing a release-specific Helm expression -- since slugifying the "
                "template text would collide across every release of this chart"
            )
        if "{{" not in resource_name or ".Release.Name" not in resource_name:
            raise unsupported(
                f"datasource resourceName {resource_name!r} must be a Helm expression that "
                "embeds .Release.Name, the one value Helm guarantees unique per release"
            )
        return resource_name, True
    parts = [microservice, db_type.lower(), scope]
    tenant = classifier.get("tenantId")
    if tenant:
        parts.append(str(tenant))
    extra = {
        key: value
        for key, value in wire_classifier(classifier).items()
        if key not in {"microserviceName", "scope", "namespace", "tenantId"}
    }
    if extra:
        parts.append(hashlib.sha256(canonical(extra).encode("utf-8")).hexdigest()[:8])
    return dns_label(*parts), False


def templated_tail(*parts: str, keep_tail: str) -> str:
    non_empty = [str(part) for part in parts if part not in (None, "")]
    if not non_empty:
        return dns_label(keep_tail, limit=_TAIL_BUDGET) if keep_tail else ""
    return dns_label(*non_empty, keep_tail=keep_tail, limit=_TAIL_BUDGET)


def with_tail(stem: str, *parts: str, keep_tail: str, templated: bool) -> str:
    if templated:
        tail = templated_tail(*parts, keep_tail=keep_tail)
        return f"{stem}-{tail}" if tail else stem
    return dns_label(stem, *parts, keep_tail=keep_tail)


def role_token(role: str) -> str:
    role = role.strip()
    return dns_label(role) if role else "default"


def cr_classifier(classifier: dict[str, Any]) -> dict[str, Any]:
    """Split the plan classifier into the CR encoding (typed fields + extraKeys).

    A top-level key wins over the same key repeated inside extraKeys, matching
    ``wire_classifier``'s precedence -- the identity every de-duplication and
    naming decision is computed from.
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
        if key in RESERVED_CLASSIFIER_KEYS:
            continue
        extra.setdefault(key, value)
    typed.pop("namespace", None)
    result = {key: typed[key] for key in ("microserviceName", "scope", "tenantId") if key in typed}
    if "customKeys" in typed:
        result["customKeys"] = typed["customKeys"]
    if extra:
        result["extraKeys"] = extra
    return result


# --------------------------------------------------------------------------- #
# Plan loading
# --------------------------------------------------------------------------- #

_ROOT_KEYS = {
    "root", "kind", "outputFile", "outputSha256", "operatorNamespace", "workloadNamespace",
    "originService", "helmValues", "datasources", "claims", "supersededDeclarations",
    "sourceHashes", "valuesFile", "schemaFile",
}
_DATASOURCE_KEYS = {
    "id", "type", "classifier", "requestedRoles", "parameters", "migrationFeasibility",
    "resourceName", "codeLocations",
}
_PARAMETER_KEYS = {"namePrefix", "settings", "physicalDatabaseId"}
_CLAIM_KEYS = {
    "datasourceId", "role", "workloadFile", "workloadKind", "workloadName",
    "containers", "initContainers",
}
_DECLARATION_KEYS = {"path", "documentIndex"}
_UNSUPPORTED_LEGACY_FIELDS = ("physicalDatabaseId", "versioningConfig", "initialInstantiation")


def _reject_unknown(obj: dict[str, Any], allowed: set[str], where: str) -> None:
    if not isinstance(obj, dict):
        raise bad_input(f"{where} must be an object")
    unknown = sorted(set(obj) - allowed)
    if unknown:
        raise bad_input(f"{where} has unknown properties: {', '.join(unknown)}")


def load_plan(plan_path: Path, repo_root: Path) -> dict[str, Any]:
    try:
        text = plan_path.read_text(encoding="utf-8")
    except OSError as exc:
        raise bad_input(f"cannot read plan: {exc}") from None
    try:
        raw = json.loads(text, parse_constant=_reject_json_constant)
    except ValueError as exc:
        raise bad_input(f"plan is not valid JSON: {exc}") from None
    if not isinstance(raw, dict):
        raise bad_input("plan must be a JSON object")
    _reject_unknown(raw, {"roots"}, "plan")
    roots = raw.get("roots")
    if not isinstance(roots, list) or not roots:
        raise bad_input("plan.roots must be a non-empty list")

    seen_roots: set[str] = set()
    seen_outputs: set[str] = set()
    seen_namespaces: set[str] = set()
    for index, entry in enumerate(roots):
        where = f"plan.roots[{index}]"
        _reject_unknown(entry, _ROOT_KEYS, where)
        raw_root = entry.get("root")
        if not isinstance(raw_root, str):
            raise bad_input(f"{where}.root must be a string")
        norm_root = "" if raw_root in ("", ".") else canonical_path(raw_root, what=f"{where}.root")
        if norm_root in seen_roots:
            raise bad_input(f"{where}.root {raw_root!r} is declared more than once")
        seen_roots.add(norm_root)
        entry["_root"] = norm_root
        if entry.get("kind") not in ("helm", "plain"):
            raise bad_input(f"{where}.kind must be 'helm' or 'plain'")
        for key in ("operatorNamespace", "workloadNamespace", "originService"):
            if not isinstance(entry.get(key), str) or not entry[key].strip():
                raise bad_input(f"{where}.{key} is required and must be a non-empty string")
        if not is_templated(entry["operatorNamespace"]) and not is_dns_label(entry["operatorNamespace"]):
            raise bad_input(f"{where}.operatorNamespace must be a valid RFC-1123 namespace label")
        if not is_templated(entry["workloadNamespace"]) and not is_dns_label(entry["workloadNamespace"]):
            raise bad_input(f"{where}.workloadNamespace must be a valid RFC-1123 namespace label")
        output_file = entry.get("outputFile")
        if not isinstance(output_file, str) or not output_file:
            raise bad_input(f"{where}.outputFile is required")
        output_path = join_rel(norm_root, output_file, what=f"{where}.outputFile")
        if output_path in seen_outputs:
            raise bad_input(f"two roots write to the same outputFile {output_path!r}")
        seen_outputs.add(output_path)
        if entry["workloadNamespace"] in seen_namespaces:
            raise bad_input(
                f"{where}.workloadNamespace {entry['workloadNamespace']!r} is shared by another root; "
                "use one root per target namespace so duplicate resources are checked together"
            )
        seen_namespaces.add(entry["workloadNamespace"])
        entry["_outputPath"] = output_path
        output_sha256 = entry.get("outputSha256")
        if output_sha256 is not None and (
            not isinstance(output_sha256, str) or _SHA256.fullmatch(output_sha256) is None
        ):
            raise bad_input(f"{where}.outputSha256 must be a 64-character hex digest or null")
        entry["outputSha256"] = output_sha256.lower() if output_sha256 is not None else None

        datasources = entry.get("datasources")
        if not isinstance(datasources, list) or not datasources:
            raise bad_input(f"{where}.datasources must be a non-empty list")
        ids: set[str] = set()
        identities: set[tuple[str, str]] = set()
        for ds_index, ds in enumerate(datasources):
            ds_where = f"{where}.datasources[{ds_index}]"
            _reject_unknown(ds, _DATASOURCE_KEYS, ds_where)
            if not isinstance(ds.get("id"), str) or not ds["id"]:
                raise bad_input(f"{ds_where}.id is required")
            if ds["id"] in ids:
                raise bad_input(f"{ds_where}.id {ds['id']!r} is duplicated")
            ids.add(ds["id"])
            if not isinstance(ds.get("type"), str) or not ds["type"]:
                raise bad_input(f"{ds_where}.type is required")
            # Feasibility triage (SUPPORTED / NOT_SUPPORTED_DYNAMIC / BLOCKED / AMBIGUOUS)
            # is the skill's inventory-building job, not this writer's: a datasource
            # only belongs in this plan at all once it is SUPPORTED, so any other
            # value here is a plan-authoring mistake, not something to filter around.
            feasibility = ds.get("migrationFeasibility", "SUPPORTED")
            if feasibility != "SUPPORTED":
                raise bad_input(
                    f"{ds_where}.migrationFeasibility is {feasibility!r}; only a SUPPORTED "
                    "datasource belongs in this plan -- keep others on the REST path"
                )
            ds["migrationFeasibility"] = feasibility
            classifier = ds.get("classifier")
            if not isinstance(classifier, dict) or not classifier:
                raise bad_input(f"{ds_where}.classifier must be a non-empty object")
            for key in ("microserviceName", "scope"):
                if not isinstance(classifier.get(key), str) or not classifier[key].strip():
                    raise bad_input(f"{ds_where}.classifier.{key} is required and must be a non-empty string")
            if not is_templated(classifier["scope"]) and classifier["scope"] not in ("service", "tenant"):
                raise bad_input(f"{ds_where}.classifier.scope must be 'service' or 'tenant'")
            for key in ("namespace", "tenantId"):
                if key in classifier and not isinstance(classifier[key], str):
                    raise bad_input(f"{ds_where}.classifier.{key} must be a string")
            custom_keys = classifier.get("customKeys")
            if custom_keys is not None and (
                not isinstance(custom_keys, dict) or not validator.is_json_value(custom_keys)
            ):
                raise bad_input(f"{ds_where}.classifier.customKeys must be an object containing valid JSON")
            classifier_namespace = classifier.get("namespace")
            # cr_classifier() unconditionally drops classifier.namespace (typed.pop("namespace",
            # None)) since the operator always materializes InternalDatabase/DatabaseSecretClaim
            # in the workload namespace -- so dropping the pin is only safe when it is already
            # provably redundant. Exact string equality with workloadNamespace proves that even
            # while workloadNamespace is still a Helm expression (the same literal expression
            # renders to the same value in both places, whatever that ends up being); anything
            # else -- a literal differing from a concrete workloadNamespace, or any pin at all
            # once workloadNamespace is a *different* Helm expression whose rendered value cannot
            # be compared -- is not provably redundant, so it blocks rather than being dropped
            # unverified.
            if classifier_namespace and classifier_namespace != entry["workloadNamespace"]:
                if is_templated(entry["workloadNamespace"]):
                    raise unsupported(
                        f"{ds_where}.classifier.namespace {classifier_namespace!r} is pinned, but "
                        f"{where}.workloadNamespace {entry['workloadNamespace']!r} is a different Helm "
                        "expression -- whether the rendered namespace will match the pin cannot be "
                        "proven, and the operator always materializes into the workload namespace "
                        "regardless, so this pin cannot be migrated unverified"
                    )
                raise unsupported(
                    f"{ds_where}.classifier.namespace {classifier_namespace!r} differs from "
                    f"{where}.workloadNamespace {entry['workloadNamespace']!r}; the operator always "
                    "materializes into the workload namespace, so a classifier pinned to a "
                    "different namespace cannot be migrated"
                )
            extra_keys = classifier.get("extraKeys")
            if extra_keys is not None:
                if not isinstance(extra_keys, dict):
                    raise bad_input(f"{ds_where}.classifier.extraKeys must be an object")
                reserved = sorted(RESERVED_CLASSIFIER_KEYS & set(extra_keys))
                if reserved:
                    raise bad_input(f"{ds_where}.classifier.extraKeys must not repeat reserved keys: {', '.join(reserved)}")
                if not validator.is_json_value(extra_keys):
                    raise bad_input(f"{ds_where}.classifier.extraKeys must contain valid JSON")
            for key, value in classifier.items():
                if key not in RESERVED_CLASSIFIER_KEYS | {"extraKeys"} and not validator.is_json_value(value):
                    raise bad_input(f"{ds_where}.classifier.{key} must be a valid JSON value")
            # Two datasources sharing one (classifier, type) identity is
            # always ambiguous, never a legitimate shape: build_resources()
            # and verify_superseded() would each have to pick one of them as
            # authoritative, and nothing forces those two independent picks
            # to agree -- reject it here instead of letting them silently
            # disagree about which datasource's parameters were "the ones
            # that got migrated".
            identity = (canonical(wire_classifier(classifier)), ds["type"].lower())
            if identity in identities:
                raise bad_input(
                    f"{ds_where}: datasource identity (classifier, type) duplicates an earlier "
                    f"datasource in {where}.datasources"
                )
            identities.add(identity)
            roles = ds.get("requestedRoles", [""])
            if not isinstance(roles, list) or not roles or not all(isinstance(r, str) for r in roles):
                raise bad_input(f"{ds_where}.requestedRoles must be a non-empty list of strings")
            ds["requestedRoles"] = roles
            # `or {}` would coerce a present-but-falsy value (`[]`, `""`, `0`) to `{}`
            # before the type check below ever saw it -- only a genuinely absent
            # (None/missing) parameters is a legitimate default; anything else present
            # must be validated as given, not silently replaced.
            parameters = ds.get("parameters")
            if parameters is None:
                parameters = {}
            else:
                _reject_unknown(parameters, _PARAMETER_KEYS, f"{ds_where}.parameters")
            if "physicalDatabaseId" in parameters:
                raise unsupported(
                    f"{ds_where}: physicalDatabaseId {parameters['physicalDatabaseId']!r} has no proven mapping in the "
                    "mounted-secret contract"
                )
            name_prefix = parameters.get("namePrefix")
            if name_prefix is not None and not isinstance(name_prefix, str):
                raise bad_input(f"{ds_where}.parameters.namePrefix must be a string")
            settings = parameters.get("settings")
            if settings is not None and (
                not isinstance(settings, dict)
                or not all(isinstance(k, str) and validator.is_json_value(v) for k, v in settings.items())
            ):
                raise bad_input(f"{ds_where}.parameters.settings must map string keys to valid JSON values")
            ds["parameters"] = parameters

        claims = entry.get("claims")
        if not isinstance(claims, list) or not claims:
            raise bad_input(f"{where}.claims must be a non-empty list")
        for claim_index, claim in enumerate(claims):
            claim_where = f"{where}.claims[{claim_index}]"
            _reject_unknown(claim, _CLAIM_KEYS, claim_where)
            if claim.get("datasourceId") not in ids:
                raise bad_input(f"{claim_where}.datasourceId {claim.get('datasourceId')!r} is not declared in datasources")
            claimed_ds = next(d for d in datasources if d["id"] == claim["datasourceId"])
            if claimed_ds["migrationFeasibility"] != "SUPPORTED":
                raise bad_input(
                    f"{claim_where} targets datasource {claim['datasourceId']!r}, which is "
                    f"{claimed_ds['migrationFeasibility']!r}, not SUPPORTED"
                )
            if not isinstance(claim.get("role", ""), str):
                raise bad_input(f"{claim_where}.role must be a string")
            for field in ("workloadFile", "workloadKind", "workloadName"):
                if not isinstance(claim.get(field), str) or not claim[field]:
                    raise bad_input(f"{claim_where}.{field} is required")
            if claim.get("workloadKind") not in ("Deployment", "StatefulSet"):
                raise bad_input(f"{claim_where}.workloadKind must be 'Deployment' or 'StatefulSet'")
            containers = claim.get("containers", [])
            init_containers = claim.get("initContainers", [])
            if not isinstance(containers, list) or not all(isinstance(c, str) for c in containers):
                raise bad_input(f"{claim_where}.containers must be a list of strings")
            if not isinstance(init_containers, list) or not all(isinstance(c, str) for c in init_containers):
                raise bad_input(f"{claim_where}.initContainers must be a list of strings")
            if not containers and not init_containers:
                raise bad_input(f"{claim_where} names no containers or initContainers to mount into")
            claim["containers"] = containers
            claim["initContainers"] = init_containers
            claim["_workloadPath"] = join_rel(norm_root, claim["workloadFile"], what=f"{claim_where}.workloadFile")

        declarations = entry.get("supersededDeclarations", [])
        if not isinstance(declarations, list):
            raise bad_input(f"{where}.supersededDeclarations must be a list")
        for decl_index, decl in enumerate(declarations):
            decl_where = f"{where}.supersededDeclarations[{decl_index}]"
            _reject_unknown(decl, _DECLARATION_KEYS, decl_where)
            if not isinstance(decl.get("path"), str) or not decl["path"]:
                raise bad_input(f"{decl_where}.path is required")
            doc_index = decl.get("documentIndex")
            if doc_index is not None and (isinstance(doc_index, bool) or not isinstance(doc_index, int)):
                raise bad_input(f"{decl_where}.documentIndex must be an integer or null")
            decl["_path"] = join_rel(norm_root, decl["path"], what=f"{decl_where}.path")

        helm_values = entry.get("helmValues", {})
        if not isinstance(helm_values, dict) or not all(
            isinstance(k, str) and isinstance(v, str) for k, v in helm_values.items()
        ):
            raise bad_input(f"{where}.helmValues must map strings to strings")
        entry["helmValues"] = helm_values

        source_hashes = entry.get("sourceHashes", {})
        if not isinstance(source_hashes, dict) or not all(
            isinstance(k, str) and isinstance(v, str) and _SHA256.fullmatch(v) is not None
            for k, v in source_hashes.items()
        ):
            raise bad_input(f"{where}.sourceHashes must map repository-relative paths to sha256 hex digests")
        normalized_hashes: dict[str, str] = {}
        for key, value in source_hashes.items():
            path = join_rel(norm_root, key, what=f"{where}.sourceHashes key")
            if path in normalized_hashes:
                raise bad_input(f"{where}.sourceHashes contains duplicate path {path!r}")
            normalized_hashes[path] = value.lower()
        entry["sourceHashes"] = normalized_hashes

        workload_paths = {c["_workloadPath"] for c in claims}
        declaration_paths = {d["_path"] for d in declarations}
        # apply_workload_patches() and verify_superseded() each independently
        # compute a new version of any file they touch and write it into the
        # same shared Changes object; whichever runs second wins and the
        # other's edit is silently discarded. Rather than trying to make one
        # pass aware of the other's in-memory edit (and recompute byte spans
        # that would have shifted under it), refuse the overlap outright: a
        # claim's workload file must not also be a superseded-declaration
        # source in the same root.
        overlap = sorted(workload_paths & declaration_paths)
        if overlap:
            raise bad_input(
                f"{where}: a workload file cannot also be a superseded declaration source in "
                f"the same root: {', '.join(overlap)}"
            )
        touched = workload_paths | declaration_paths
        # update_values() reads -- and sometimes writes -- values.yaml and
        # values.schema.json under exactly this same condition; those files
        # need the same stale-plan protection as every workload/declaration
        # the writer reads, or an edit to either between discovery and apply
        # goes uncaught.
        if entry["operatorNamespace"] == f"{{{{ .Values.{DBAAS_OPERATOR_NAMESPACE_VALUE} }}}}":
            values_rel = join_rel(norm_root, entry.get("valuesFile", "values.yaml"), what=f"{where}.valuesFile")
            touched.add(values_rel)
            schema_rel = join_rel(
                norm_root, entry.get("schemaFile", "values.schema.json"), what=f"{where}.schemaFile"
            )
            if (repo_root / schema_rel).is_file():
                touched.add(schema_rel)
        # sourceHashes is how a stale-plan edit gets caught (exit 3) instead
        # of silently re-read; an entry missing here for a file the writer
        # will actually read is not "optional", it is a coverage hole.
        missing_hashes = sorted(touched - set(entry["sourceHashes"]))
        if missing_hashes:
            raise bad_input(f"{where}.sourceHashes is missing an entry for: {', '.join(missing_hashes)}")
        if output_path in touched:
            raise bad_input(f"{where}.outputFile {output_path!r} collides with a migration source")

    return raw


def _reject_json_constant(value: str) -> Any:
    raise ValueError(f"numeric constant {value!r} is not valid JSON")


# --------------------------------------------------------------------------- #
# Change set / transaction
# --------------------------------------------------------------------------- #


class Changes:
    def __init__(self) -> None:
        self.files: dict[str, str | None] = {}

    def set_content(self, path: str, content: str) -> None:
        self.files[path] = content

    def delete(self, path: str) -> None:
        self.files[path] = None


def check_source_hashes(repo_root: Path, root_plan: dict[str, Any]) -> None:
    failures: list[str] = []
    for rel, expected in root_plan["sourceHashes"].items():
        target = resolve_within(repo_root, rel, what="source path")
        if not target.is_file():
            failures.append(f"{rel}: expected file is missing")
            continue
        actual = sha256_file(target)
        if actual != expected:
            failures.append(f"{rel}: sha256 changed since discovery (expected {expected}, found {actual})")
    if failures:
        raise stale("source hashes changed after discovery", failures)


def check_output_ownership(repo_root: Path, root_plan: dict[str, Any], generated_content: str | None) -> None:
    """An existing output file must be either absent or proven owned before
    the writer is allowed to replace its content.

    Without this, a file that already exists at the computed output path --
    for any reason, including one the plan never accounted for -- is
    silently overwritten the moment the generated content is assigned;
    unconditional assignment has no concept of "this content already exists
    and I do not know what it is".
    """

    target = resolve_within(repo_root, root_plan["_outputPath"], what="output path")
    if not target.is_file():
        return  # nothing pre-existing to protect
    expected = root_plan.get("outputSha256")
    if expected is not None:
        actual = sha256_file(target)
        if actual != expected:
            raise stale(
                "existing output changed since the plan was built",
                [f"{root_plan['_outputPath']}: expected sha256 {expected}, found {actual}"],
            )
        return
    # No outputSha256 was recorded for this root. Blocking unconditionally
    # here would also block a repeated --apply of an unchanged plan, since
    # its own previous output always exists by the second run and the skill
    # never re-collects outputSha256 for content it just wrote itself. Allow
    # it only when the existing bytes already equal what this run would
    # generate -- an unrelated pre-existing file (any byte difference) still
    # blocks below exactly as before.
    if generated_content is not None and target.read_bytes() == generated_content.encode("utf-8"):
        return
    raise unsupported(
        "output file collision",
        [
            f"{root_plan['_outputPath']}: file already exists and the plan does not set "
            "outputSha256 for this root; record the file's current sha256 to prove it was "
            "accounted for, or remove it first"
        ],
    )


def _in_root(root: str, path: str) -> bool:
    return root == "" or path == root or path.startswith(f"{root}/")


def check_changes_within_roots(plan: dict[str, Any], changes: Changes) -> None:
    """Every changed path must belong to some declared root.

    Checked once, globally, across every root -- not per-materialize-call,
    since a multi-root plan's ``changes`` legitimately spans more than one
    root and a per-call check would reject a perfectly valid path that
    simply belongs to a *different* root than the one being materialized.
    """

    roots = [root_plan["_root"] for root_plan in plan["roots"]]
    for path in changes.files:
        if not any(_in_root(root, path) for root in roots):
            raise unsupported(f"target {path!r} is outside every root declared by the plan")


def materialize_tree(repo_root: Path, root: str, changes: Changes, dest: Path) -> None:
    """Copy ``root``'s existing content into ``dest``, then apply only the
    subset of ``changes`` that belongs to this root -- a path belonging to a
    different root (in a multi-root plan's shared ``changes`` set) is left
    alone here; ``check_changes_within_roots`` is what proves every path
    belongs to *some* declared root."""

    source = repo_root if root == "" else resolve_within(repo_root, root, what="root")
    if source.exists():
        target = dest if root == "" else dest / root
        target.mkdir(parents=True, exist_ok=True)
        shutil.copytree(source, target, symlinks=False, dirs_exist_ok=True, ignore=shutil.ignore_patterns(".git"))
    for path, content in changes.files.items():
        if not _in_root(root, path):
            continue
        file_path = dest / path
        if content is None:
            if file_path.exists():
                file_path.unlink()
            continue
        file_path.parent.mkdir(parents=True, exist_ok=True)
        # write_bytes, not write_text(..., newline=""): the newline= keyword
        # on Path.write_text() is Python 3.10+ only, and TypeErrors on the
        # stock Python 3.9 shipped by macOS, RHEL 8/9, and Debian 11. Writing
        # the already-encoded bytes directly needs no newline parameter at
        # all -- there is no text-mode translation to disable.
        file_path.write_bytes(content.encode("utf-8"))


def _secure_temp_write(target: Path, content: bytes) -> None:
    # mkstemp() always creates its temp file mode 0600, regardless of the file it is about
    # to replace -- os.replace() then carries that mode over verbatim, silently stripping
    # group/other read and the executable bit from whatever the target had before (a
    # 0755 script, say). Restore the original mode, or use the normal 0644 mode for a
    # newly generated manifest.
    try:
        original_mode = target.stat().st_mode & 0o777
    except OSError:
        original_mode = 0o644
    fd, tmp_name = tempfile.mkstemp(prefix=f".{target.name}.", suffix=".tmp", dir=str(target.parent))
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(content)
        os.chmod(tmp_name, original_mode)
        os.replace(tmp_name, target)
    except BaseException:
        try:
            os.unlink(tmp_name)
        except OSError:
            pass
        raise


def commit(repo_root: Path, changes: Changes) -> None:
    backups: dict[str, tuple[bytes, int] | None] = {}
    applied: list[str] = []
    try:
        for path in sorted(changes.files):
            target = resolve_within(repo_root, path, what="target path")
            content = changes.files[path]
            backups[path] = (
                (target.read_bytes(), target.stat().st_mode & 0o777) if target.is_file() else None
            )
            if content is None:
                if target.is_file():
                    target.unlink()
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                _secure_temp_write(target, content.encode("utf-8"))
            applied.append(path)
    except Exception as exc:  # noqa: BLE001 - roll back then report typed
        for path in reversed(applied):
            target = resolve_within(repo_root, path, what="target path")
            backup = backups.get(path)
            if backup is None:
                if target.is_file():
                    target.unlink()
            else:
                original, mode = backup
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(original)
                os.chmod(target, mode)
        # The documented contract has only 2/3/4/5; a write-transaction failure
        # (permissions, disk full, ...) is an environment/dependency problem,
        # not a plan-content one, so it maps to EXIT_UNSUPPORTED rather than a
        # sixth undocumented code.
        raise unsupported("write transaction failed and was rolled back", [str(exc)]) from exc


def classify(repo_root: Path, changes: Changes) -> dict[str, list[str]]:
    created: list[str] = []
    modified: list[str] = []
    deleted: list[str] = []
    unchanged: list[str] = []
    for path in sorted(changes.files):
        target = resolve_within(repo_root, path, what="target path")
        content = changes.files[path]
        if content is None:
            (deleted if target.exists() else unchanged).append(path)
            continue
        new_bytes = content.encode("utf-8")
        if target.is_file():
            (unchanged if target.read_bytes() == new_bytes else modified).append(path)
        else:
            created.append(path)
    return {"createdFiles": created, "modifiedFiles": modified, "deletedFiles": deleted, "unchangedFiles": unchanged}


# --------------------------------------------------------------------------- #
# Resource generation
# --------------------------------------------------------------------------- #


def build_resources(root_plan: dict[str, Any]) -> tuple[list[dict[str, Any]], dict[str, dict[str, str]]]:
    by_id = {ds["id"]: ds for ds in root_plan["datasources"]}
    databases: dict[str, dict[str, Any]] = {}
    names: dict[str, tuple[str, bool]] = {}
    for ds in sorted(root_plan["datasources"], key=lambda d: canonical(wire_classifier(d["classifier"])) + d["type"]):
        classifier = ds["classifier"]
        key = f"{canonical(wire_classifier(classifier))}|{ds['type'].lower()}"
        if key in databases:
            continue
        stem, templated = identity_stem(classifier, ds["type"], ds.get("resourceName"))
        name = with_tail(stem, keep_tail="db", templated=templated)
        names[key] = (stem, templated)
        spec: dict[str, Any] = {
            "operatorNamespace": root_plan["operatorNamespace"],
            "classifier": cr_classifier(classifier),
            "type": ds["type"].lower(),
            "lazy": False,
        }
        params = ds["parameters"]
        if params.get("namePrefix"):
            spec["namePrefix"] = params["namePrefix"]
        if params.get("settings"):
            spec["settings"] = params["settings"]
        databases[key] = {
            "apiVersion": "dbaas.netcracker.com/v1",
            "kind": "InternalDatabase",
            "metadata": {"name": name, "namespace": root_plan["workloadNamespace"]},
            "spec": spec,
        }

    claim_bodies: dict[str, dict[str, Any]] = {}
    name_bundle: dict[str, dict[str, str]] = {}
    for claim in root_plan["claims"]:
        ds = by_id[claim["datasourceId"]]
        classifier = ds["classifier"]
        role = str(claim.get("role", ""))
        db_key = f"{canonical(wire_classifier(classifier))}|{ds['type'].lower()}"
        claim_key = f"{db_key}|{role.strip()}"
        stem, templated = names[db_key]
        token = role_token(role)
        secret_name = with_tail(stem, token, keep_tail="credentials", templated=templated)
        bundle = {
            "database": with_tail(stem, keep_tail="db", templated=templated),
            "claim": with_tail(stem, token, keep_tail="claim", templated=templated),
            "secret": secret_name,
            "volume": with_tail(stem, token, keep_tail="secret", templated=templated),
            "mountPath": f"{MOUNT_ROOT}/{secret_name}",
        }
        name_bundle[f"{claim_key}|{claim['_workloadPath']}"] = bundle
        if claim_key in claim_bodies:
            continue
        claim_bodies[claim_key] = {
            "apiVersion": "dbaas.netcracker.com/v1",
            "kind": "DatabaseSecretClaim",
            "metadata": {
                "name": bundle["claim"],
                "namespace": root_plan["workloadNamespace"],
                "labels": {"app.kubernetes.io/name": root_plan["originService"]},
            },
            "spec": {
                "operatorNamespace": root_plan["operatorNamespace"],
                "classifier": cr_classifier(classifier),
                "type": ds["type"].lower(),
                "userRole": role.strip(),
                "secretName": bundle["secret"],
            },
        }

    check_collisions(databases, claim_bodies)
    ordered = sorted(
        [*databases.values(), *claim_bodies.values()],
        key=lambda body: (0 if body["kind"] == "InternalDatabase" else 1, body["metadata"]["name"]),
    )
    return ordered, name_bundle


def check_collisions(databases: dict[str, Any], claims: dict[str, Any]) -> None:
    owners: dict[tuple[str, str, str], str] = {}
    problems: list[str] = []
    for body in [*databases.values(), *claims.values()]:
        meta = body["metadata"]
        identity = (body["kind"], meta.get("namespace", ""), meta["name"])
        if identity in owners:
            problems.append(f"name collision: {body['kind']} {meta['name']!r} is produced by more than one identity")
        owners[identity] = meta["name"]
        name = meta["name"]
        if "{{" not in name and not is_dns_label(name):
            problems.append(f"{body['kind']} {name!r} is not a valid DNS-1123 label")
    if problems:
        raise unsupported("resource name collision", problems)


def render_resources(bodies: list[dict[str, Any]]) -> str:
    chunks = []
    for body in bodies:
        chunks.append("---\n" + yaml.safe_dump(body, sort_keys=False, allow_unicode=False, default_flow_style=False))
    return "".join(chunks)


# --------------------------------------------------------------------------- #
# Workload patching -- raw span insertion, never a full re-dump
# --------------------------------------------------------------------------- #

_BLOCK_ACTION = re.compile(r"^\s*\{\{-?\s*(if|range|with|include|define|block|template|end|else)\b")
_STANDALONE_ACTION = re.compile(r"^\s*\{\{-?.*-?\}\}\s*$")


class WorkloadError(Exception):
    def __init__(self, entries: list[str]) -> None:
        super().__init__("; ".join(entries))
        self.entries = entries


def _yaml_scalar(value: str) -> str:
    if value.startswith("{{"):
        return "'" + value.replace("'", "''") + "'"
    return value


def _indent(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def _find_workload(nodes: list[Any], kind: str, name: str) -> Any:
    for node in nodes:
        if not _is_mapping(node):
            continue
        mapping = {k.value: v for k, v in node.value if hasattr(k, "value")}
        kind_node = mapping.get("kind")
        metadata_node = mapping.get("metadata")
        if kind_node is None or metadata_node is None or kind_node.value != kind:
            continue
        meta_map = {k.value: v for k, v in metadata_node.value if hasattr(k, "value")}
        name_node = meta_map.get("name")
        if name_node is not None and name_node.value == name:
            return node
    return None


def _walk(node: Any, path: list[str]) -> Any:
    for segment in path:
        if node is None or not hasattr(node, "value") or not isinstance(node.value, list):
            return None
        found = None
        for key, value in node.value:
            if hasattr(key, "value") and key.value == segment:
                found = value
                break
        node = found
    return node


def _is_mapping(node: Any) -> bool:
    return node is not None and hasattr(node, "value") and isinstance(node.value, list) and node.tag.endswith(":map")


def _child_key_column(mapping_node: Any) -> int:
    if not mapping_node.value:
        return mapping_node.start_mark.column + 2
    return mapping_node.value[0][0].start_mark.column


def _block_content_end(lines: list[str], start_line: int, min_indent: int) -> int:
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


def _insert_block_list(
    mapping_node: Any, key: str, lines: list[str], render: Any,
    edits: list[tuple[int, int, str]], problems: list[str], what: str,
) -> None:
    """Insert ``render(indent)``'s rendered entries under ``mapping_node[key]``.

    ``indent`` is resolved here, not by the caller, and differs by case: a
    fresh or empty-inline list's items are indented one level deeper than the
    key that introduces them (standard Kubernetes YAML style); an existing
    list's items are indented to match its own first existing item exactly --
    a new item at any other column desynchronizes the sequence and breaks the
    YAML parse (go-yaml's "did not find expected key").
    """

    key_node = value_node = None
    for k, v in mapping_node.value:
        if hasattr(k, "value") and k.value == key:
            key_node, value_node = k, v
            break
    key_col = _child_key_column(mapping_node)

    if value_node is not None and value_node.tag.endswith(":null"):
        problems.append(f"{what} is present but null; give it a block list or remove it")
        return
    if value_node is not None and value_node.tag.endswith(":seq") and value_node.flow_style:
        if value_node.value:
            problems.append(f"{what} is an inline list; rewrite it as a block list before migrating")
            return
        block = " " * key_col + f"{key}:\n" + render(key_col + 2)
        edits.append((key_node.start_mark.line, 1, block))
        return
    if value_node is not None and not (value_node.tag.endswith(":seq")):
        problems.append(f"{what} is not a block list")
        return

    if value_node is None or not value_node.value:
        anchor = key_node.start_mark.line if key_node is not None else mapping_node.value[-1][0].start_mark.line
        insert_at = _block_content_end(lines, anchor, key_col)
        block = " " * key_col + f"{key}:\n" + render(key_col + 2)
        edits.append((insert_at, 0, block))
        return

    dash_col = _indent(lines[value_node.value[0].start_mark.line])
    insert_at = _block_content_end(lines, value_node.value[-1].start_mark.line, dash_col)
    edits.append((insert_at, 0, render(dash_col)))


def _render_volume(name: str, secret: str, indent: int) -> str:
    pad = " " * indent
    return f"{pad}- name: {_yaml_scalar(name)}\n{pad}  secret:\n{pad}    secretName: {_yaml_scalar(secret)}\n"


def _render_mount(name: str, mount_path: str, indent: int) -> str:
    pad = " " * indent
    return f"{pad}- name: {_yaml_scalar(name)}\n{pad}  mountPath: {_yaml_scalar(mount_path)}\n{pad}  readOnly: true\n"


def _existing_names(mapping_node: Any, key: str, name_field: str = "name") -> dict[str, Any]:
    result: dict[str, Any] = {}
    for k, v in mapping_node.value:
        if hasattr(k, "value") and k.value == key and v.tag.endswith(":seq"):
            for item in v.value:
                if not item.tag.endswith(":map"):
                    continue
                for ik, iv in item.value:
                    if hasattr(ik, "value") and ik.value == name_field and hasattr(iv, "value"):
                        result[iv.value] = item
    return result


def patch_workload(text: str, *, filename: str, targets: list[dict[str, Any]]) -> str:
    if yaml is None:  # pragma: no cover
        raise WorkloadError([f"{filename}: PyYAML is required"])
    uses_crlf = "\r\n" in text
    # A file that mixes "\r\n" and bare "\n" is not "CRLF" or "LF", it is both -- normalizing
    # to "\n" for editing and then unconditionally re-adding "\r\n" to every line on the way
    # out (below) would convert every originally-bare-"\n" line to "\r\n" too, rewriting lines
    # this function never touched. There is no line-ending-preserving edit here (unlike the
    # byte-span splicing elsewhere in this file), so block instead of guessing which lines
    # were meant to keep which ending.
    if uses_crlf and "\n" in text.replace("\r\n", ""):
        raise WorkloadError([f"{filename}: mixed line endings; cannot edit in place"])
    work = text.replace("\r\n", "\n") if uses_crlf else text
    had_trailing_newline = work.endswith("\n")
    for lineno, line in enumerate(work.splitlines(), start=1):
        if _BLOCK_ACTION.match(line):
            raise WorkloadError([f"{filename}:{lineno}: standalone Helm block action; refusing to patch"])
        stripped = line.strip()
        if stripped.startswith("{{") and _STANDALONE_ACTION.match(line) and ":" not in stripped:
            raise WorkloadError([f"{filename}:{lineno}: standalone Helm action; refusing to patch"])

    lines = work.splitlines(keepends=True)
    if lines and not lines[-1].endswith("\n"):
        lines[-1] += "\n"
    try:
        nodes = [node for node in yaml.compose_all(work) if node is not None]
    except yaml.YAMLError as exc:
        raise WorkloadError([f"{filename}: not valid YAML: {exc}"]) from None

    edits: list[tuple[int, int, str]] = []
    problems: list[str] = []
    for target in targets:
        node = _find_workload(nodes, target["kind"], target["name"])
        if node is None:
            problems.append(f"{filename}: no {target['kind']} named {target['name']!r} in the manifest")
            continue
        pod_spec = _walk(node, ["spec", "template", "spec"])
        if pod_spec is None or not _is_mapping(pod_spec):
            problems.append(f"{filename}: {target['kind']}/{target['name']} has no spec.template.spec mapping")
            continue
        if not pod_spec.value:
            problems.append(
                f"{filename}: {target['kind']}/{target['name']} spec.template.spec is an empty mapping; "
                "it must already define a containers list to mount the generated secret into"
            )
            continue

        existing_volumes = _existing_names(pod_spec, "volumes")
        new_volumes: list[tuple[str, str]] = []
        for volume_name, secret_name in target["volumes"]:
            if volume_name in existing_volumes:
                existing_secret = _walk(existing_volumes[volume_name], ["secret", "secretName"])
                if existing_secret is not None and existing_secret.value == secret_name:
                    continue  # idempotent: already exactly this mount
                problems.append(f"{filename}: volume {volume_name!r} already exists with a different secret")
                continue
            new_volumes.append((volume_name, secret_name))
        if new_volumes:
            _insert_block_list(
                pod_spec, "volumes", lines,
                lambda indent, items=new_volumes: "".join(_render_volume(n, s, indent) for n, s in items),
                edits, problems, f"{filename} {target['kind']}/{target['name']} spec.template.spec.volumes",
            )

        for container_field, mounts in (("containers", target["containerMounts"]), ("initContainers", target["initContainerMounts"])):
            for container_name, wanted in mounts.items():
                container_node = None
                for k, v in pod_spec.value:
                    if hasattr(k, "value") and k.value == container_field and v.tag.endswith(":seq"):
                        for item in v.value:
                            if not item.tag.endswith(":map"):
                                continue
                            for ik, iv in item.value:
                                if hasattr(ik, "value") and ik.value == "name" and getattr(iv, "value", None) == container_name:
                                    container_node = item
                        break
                if container_node is None:
                    problems.append(f"{filename}: {container_field} {container_name!r} not found in {target['kind']}/{target['name']}")
                    continue
                existing_mounts = _existing_names(container_node, "volumeMounts")
                new_mounts: list[tuple[str, str]] = []
                for volume_name, mount_path in wanted:
                    if volume_name in existing_mounts:
                        existing_path = _walk(existing_mounts[volume_name], ["mountPath"])
                        if existing_path is not None and existing_path.value == mount_path:
                            continue  # idempotent
                        problems.append(f"{filename}: {container_field} {container_name!r} already mounts {volume_name!r} at a different path")
                        continue
                    new_mounts.append((volume_name, mount_path))
                if new_mounts:
                    _insert_block_list(
                        container_node, "volumeMounts", lines,
                        lambda indent, items=new_mounts: "".join(_render_mount(n, p, indent) for n, p in items),
                        edits, problems, f"{filename} {container_field} {container_name!r} volumeMounts",
                    )

    if problems:
        raise WorkloadError(problems)
    for line_index, replace_count, chunk in sorted(edits, key=lambda item: -item[0]):
        lines[line_index:line_index + replace_count] = [chunk]
    result = "".join(lines)
    # The synthetic trailing newline above exists only so internal editing
    # never appends content onto an unterminated last line; restore the
    # original's own final-newline state here rather than always keeping it
    # (an edit landing at the true end of file already brings its own
    # newline, so removing this one leaves that content correctly
    # terminated too -- it never strips anything the edit itself needed).
    if not had_trailing_newline and result.endswith("\n"):
        result = result[:-1]
    return result.replace("\n", "\r\n") if uses_crlf else result


def apply_workload_patches(repo_root: Path, root_plan: dict[str, Any], name_bundle: dict[str, dict[str, str]], changes: Changes) -> None:
    by_file: dict[str, list[dict[str, Any]]] = {}
    for claim in root_plan["claims"]:
        role = str(claim.get("role", ""))
        ds = next(d for d in root_plan["datasources"] if d["id"] == claim["datasourceId"])
        db_key = f"{canonical(wire_classifier(ds['classifier']))}|{ds['type'].lower()}"
        claim_key = f"{db_key}|{role.strip()}"
        bundle = name_bundle[f"{claim_key}|{claim['_workloadPath']}"]
        by_file.setdefault(claim["_workloadPath"], []).append((claim, bundle))

    for path, entries in by_file.items():
        target = resolve_within(repo_root, path, what="workload path")
        if not target.is_file():
            raise unsupported(f"{path}: workload file is missing")
        # newline="" disables universal-newline translation -- Path.read_text()'s
        # default silently rewrites every "\r\n" to "\n" before patch_workload
        # ever sees the text, which would make its own uses_crlf detection
        # permanently false and CRLF preservation dead code.
        with target.open(encoding="utf-8", newline="") as handle:
            original = handle.read()
        by_workload: dict[tuple[str, str], list[tuple[Any, dict[str, str]]]] = {}
        for claim, bundle in entries:
            by_workload.setdefault((claim["workloadKind"], claim["workloadName"]), []).append((claim, bundle))
        targets = []
        for (kind, name), pairs in by_workload.items():
            volumes = [(bundle["volume"], bundle["secret"]) for _, bundle in pairs]
            container_mounts: dict[str, list[tuple[str, str]]] = {}
            init_mounts: dict[str, list[tuple[str, str]]] = {}
            for claim, bundle in pairs:
                for container in claim["containers"]:
                    container_mounts.setdefault(container, []).append((bundle["volume"], bundle["mountPath"]))
                for container in claim["initContainers"]:
                    init_mounts.setdefault(container, []).append((bundle["volume"], bundle["mountPath"]))
            targets.append({"kind": kind, "name": name, "volumes": volumes, "containerMounts": container_mounts, "initContainerMounts": init_mounts})
        try:
            patched = patch_workload(original, filename=path, targets=targets)
        except WorkloadError as exc:
            raise unsupported("workload adapter blocked", exc.entries) from None
        changes.set_content(path, patched)


# --------------------------------------------------------------------------- #
# Superseded legacy declarations
# --------------------------------------------------------------------------- #


def _as_legacy_items(doc: Any) -> list[Any]:
    if isinstance(doc, dict) and doc.get("kind") == "DBaaS":
        outer_sub_kind = doc.get("subKind")
        spec = doc.get("spec")
        if not isinstance(spec, dict):
            return [spec]
        doc = dict(spec)
        if not doc.get("kind") and not doc.get("subKind") and outer_sub_kind:
            doc["subKind"] = outer_sub_kind
    if isinstance(doc, dict) and str(doc.get("kind") or doc.get("subKind") or "").lower() == "databasedeclaration":
        declarations = doc.get("declarations")
        if declarations is None:
            return [doc]
        if not isinstance(declarations, list):
            return [declarations]
        # Every entry is returned, non-dicts included: a caller that silently
        # dropped a non-dict entry here would see only the dict entries and
        # could conclude the whole document is a fully-proven, migrated
        # declaration -- with the dropped entry deleted right along with it.
        return list(declarations)
    return []


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


def _split_yaml_source(text: str, path: str) -> tuple[str, list[dict[str, Any]]]:
    """Return ``(preamble, docs)``: one ``docs`` entry per top-level
    ``---``-separated document, each carrying the exact byte span (including
    its own leading marker, if any) needed to reconstruct the file with some
    documents removed and the rest byte-for-byte untouched.

    ``preamble`` is any text before the file's first document -- a header
    comment, say -- that belongs to no single document at all. It must not
    be folded into whichever document happens to be first: if that specific
    document is the one removed, tying the preamble to its span would delete
    the preamble right along with it, even though a later, retained document
    also depends on it surviving. The caller re-attaches it whenever at
    least one document remains, independent of which one that is.
    """

    if yaml is None:  # pragma: no cover
        raise unsupported(f"{path}: PyYAML is required to read YAML declarations")
    try:
        # A comment-only or otherwise empty document does not compose to Python None
        # (that only happens past the last document in the stream) -- it composes to a
        # zero-width ScalarNode whose marks can fall inside the range this function
        # would otherwise attribute to the *next* real document, making both compute
        # the same byte span. Excluding only that phantom node (see
        # _is_phantom_empty_node), not Python None nor every ":null"-tagged node, is
        # what keeps every remaining document's span its own without also dropping a
        # document that is an explicit, real `null`/`~` value.
        nodes = [n for n in yaml.compose_all(text) if n is not None and not _is_phantom_empty_node(n)]
    except yaml.YAMLError as exc:
        raise unsupported(f"{path}: not valid YAML: {exc}") from None
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
        try:
            value = yaml.safe_load(content)
        except yaml.YAMLError as exc:
            raise unsupported(f"{path}: not valid YAML: {exc}") from None
        docs.append({"start": start, "end": end, "value": value})
    preamble = text[: docs[0]["start"]] if docs else ""
    return preamble, docs


def _check_declaration_items(
    where: str, items: list[Any], known: dict[tuple[str, str], dict[str, Any]]
) -> list[str]:
    problems: list[str] = []
    if not items:
        problems.append(f"{where}: does not contain a supported legacy declaration; not superseding")
        return problems
    for item in items:
        if not isinstance(item, dict):
            problems.append(f"{where}: declaration entry {item!r} is not an object; not superseding")
            continue
        for field in _UNSUPPORTED_LEGACY_FIELDS:
            if field in item:
                problems.append(f"{where}: declaration sets {field!r}, which has no proven mapping; not superseding")
        lazy = item.get("lazy")
        if lazy not in (None, False, "false", "False"):
            problems.append(f"{where}: declaration sets non-default lazy={lazy!r}; not superseding")
        classifier_config = item.get("classifierConfig")
        classifier = classifier_config.get("classifier") if isinstance(classifier_config, dict) else None
        if not isinstance(classifier, dict):
            problems.append(f"{where}: declaration classifierConfig.classifier must be an object; not superseding")
            continue
        db_type = str(item.get("type", "")).lower()
        identity = (canonical(wire_classifier(classifier)), db_type)
        ds = known.get(identity)
        if ds is None:
            problems.append(f"{where}: declaration classifier/type does not match any migrated datasource; not superseding")
            continue
        # Matching identity alone does not prove the migrated datasource
        # actually carries this declaration's settings/namePrefix -- a plan
        # could migrate the identity while silently dropping its content.
        params = ds.get("parameters") or {}
        legacy_settings = item.get("settings") or {}
        if legacy_settings != (params.get("settings") or {}):
            problems.append(
                f"{where}: declaration settings do not match the migrated datasource's parameters.settings; "
                "not superseding"
            )
        legacy_prefix = str(item.get("namePrefix") or "")
        if legacy_prefix != str(params.get("namePrefix") or ""):
            problems.append(
                f"{where}: declaration namePrefix does not match the migrated datasource's "
                "parameters.namePrefix; not superseding"
            )
    return problems


def _identity_overlaps_known(items: list[Any], known: dict[tuple[str, str], dict[str, Any]]) -> bool:
    """True when any item's (classifier, type) identity matches a migrated datasource,
    independent of whether its settings/namePrefix also match.

    Identity alone is what a running deployment uses to decide "is this the same
    database" -- two declarations sharing it will race each other (and the operator's
    generated resource) regardless of whether their settings happen to agree, so a
    settings difference must not be read as "this document is unrelated."
    """

    for item in items:
        if not isinstance(item, dict):
            continue
        classifier_config = item.get("classifierConfig")
        classifier = classifier_config.get("classifier") if isinstance(classifier_config, dict) else None
        if not isinstance(classifier, dict):
            continue
        db_type = str(item.get("type", "")).lower()
        if (canonical(wire_classifier(classifier)), db_type) in known:
            return True
    return False


def verify_superseded(repo_root: Path, root_plan: dict[str, Any], changes: Changes) -> None:
    known: dict[tuple[str, str], dict[str, Any]] = {
        (canonical(wire_classifier(ds["classifier"])), ds["type"].lower()): ds
        for ds in root_plan["datasources"]
    }

    # Several plan entries may each address one document inside the same
    # file (via documentIndex); group by path so unrelated content elsewhere
    # in that file -- a ConfigMap in another YAML document, say -- is never
    # silently deleted along with the addressed declaration.
    by_path: dict[str, list[dict[str, Any]]] = {}
    for decl in root_plan.get("supersededDeclarations", []):
        by_path.setdefault(decl["_path"], []).append(decl)

    for path, decls in by_path.items():
        target = resolve_within(repo_root, path, what="superseded declaration path")
        if not target.is_file():
            raise unsupported(f"{path}: superseded declaration file is missing")
        suffix = target.suffix.lower()
        # Read raw bytes and decode manually -- text-mode I/O only disables
        # newline translation when given newline=""; it says nothing about a
        # BOM. "utf-8-sig" would strip one on read but, asymmetrically,
        # always re-add one on write, so it cannot by itself distinguish
        # "had a BOM" from "never had one." Decoding bytes directly (rather
        # than reading through any text-mode wrapper) also preserves CRLF
        # verbatim in a retained (spliced) document's span, since nothing
        # here performs universal-newline translation either.
        raw = target.read_bytes()
        has_bom = raw.startswith(b"\xef\xbb\xbf")
        text = (raw[3:] if has_bom else raw).decode("utf-8")

        if suffix == ".json":
            # A JSON array has no per-element byte span worth preserving the
            # way a YAML document does; only a whole-file removal is
            # supported, and only when every top-level element is a proven,
            # migrated declaration -- otherwise the file is left untouched
            # (never partially reformatted) and the run blocks.
            if any(d.get("documentIndex") is not None for d in decls):
                raise bad_input(f"{path}: documentIndex is not supported for a JSON source")
            try:
                data = json.loads(text)
            except ValueError as exc:
                raise unsupported(f"{path}: not valid JSON: {exc}") from None
            elements = data if isinstance(data, list) else [data]
            problems = []
            for element in elements:
                problems.extend(_check_declaration_items(path, _as_legacy_items(element), known))
            if problems:
                raise unsupported("a superseded declaration is not fully proven migrated", problems)
            changes.delete(path)
            continue

        preamble, docs = _split_yaml_source(text, path)
        if has_bom:
            preamble = "﻿" + preamble
        addressed = sorted({d["documentIndex"] for d in decls if d.get("documentIndex") is not None})
        whole_file = any(d.get("documentIndex") is None for d in decls)
        if whole_file and addressed:
            raise bad_input(
                f"{path}: a documentIndex entry and a whole-file entry (documentIndex: null) "
                "cannot both address this path"
            )

        if whole_file:
            # No documentIndex was given: this entry addresses the whole
            # file, so every document in it must be a proven, migrated
            # declaration or the file is left untouched.
            problems = []
            for index, doc in enumerate(docs, start=1):
                problems.extend(_check_declaration_items(f"{path}#{index}", _as_legacy_items(doc["value"]), known))
            if problems:
                raise unsupported("a superseded declaration is not fully proven migrated", problems)
            changes.delete(path)
            continue

        out_of_range = [i for i in addressed if i < 1 or i > len(docs)]
        if out_of_range:
            raise bad_input(f"{path}: documentIndex {out_of_range} out of range (file has {len(docs)} document(s))")
        problems = []
        for index in addressed:
            doc = docs[index - 1]
            problems.extend(_check_declaration_items(f"{path}#{index}", _as_legacy_items(doc["value"]), known))
        if problems:
            raise unsupported("a superseded declaration is not fully proven migrated", problems)
        # A document this plan does *not* address but that shares a migrated
        # datasource's (classifier, type) identity is a live legacy declaration left
        # racing the generated resource -- the same silent-duplicate-provisioning risk
        # supersededDeclarations exists to close, just for a document nobody remembered
        # to list. This must fire on identity alone: a settings/namePrefix difference
        # does not make the two declarations unrelated, it only means whichever one
        # wins the race provisions with the wrong parameters. Block instead of leaving it.
        for index, doc in enumerate(docs, start=1):
            if index in addressed:
                continue
            if _identity_overlaps_known(_as_legacy_items(doc["value"]), known):
                raise unsupported(
                    "a document not addressed by supersededDeclarations shares a migrated "
                    "datasource's classifier/type identity",
                    [f"{path}#{index}: add this documentIndex to supersededDeclarations too"],
                )
        # Splice out only the addressed documents; every other document --
        # including any unrelated content sharing this file -- is retained
        # byte-for-byte from its own original span. The preamble (if any) is
        # reattached whenever some document survives, regardless of which
        # document(s) that is.
        kept = "".join(text[doc["start"]:doc["end"]] for i, doc in enumerate(docs, start=1) if i not in addressed)
        if kept:
            changes.set_content(path, preamble + kept)
        else:
            changes.delete(path)


# --------------------------------------------------------------------------- #
# values.yaml / values.schema.json
# --------------------------------------------------------------------------- #


def update_values(repo_root: Path, root_plan: dict[str, Any], changes: Changes) -> None:
    if root_plan["operatorNamespace"] != f"{{{{ .Values.{DBAAS_OPERATOR_NAMESPACE_VALUE} }}}}":
        return
    values_rel = join_rel(root_plan["_root"], root_plan.get("valuesFile", "values.yaml"), what="valuesFile")
    values_path = resolve_within(repo_root, values_rel, what="values file")
    if not values_path.is_file():
        raise unsupported(f"{values_rel}: values file missing")
    # Path.read_text() performs universal-newline translation -- every "\r\n" in the file
    # would silently become "\n" in values_text, and that flattened text is what gets
    # echoed back as the unchanged prefix below, rewriting a CRLF file's every existing
    # line as LF. Decoding raw bytes instead performs no such translation.
    values_text = values_path.read_bytes().decode("utf-8")
    if not any(
        line[:1] not in (" ", "\t") and line.split(":", 1)[0].rstrip() == DBAAS_OPERATOR_NAMESPACE_VALUE
        for line in values_text.splitlines()
    ):
        newline = "\r\n" if "\r\n" in values_text else "\n"
        separator = "" if values_text.endswith("\n") else newline
        ending = newline if values_text.endswith("\n") else ""
        changes.set_content(values_rel, f'{values_text}{separator}{DBAAS_OPERATOR_NAMESPACE_VALUE}: ""{ending}')

    schema_rel = join_rel(root_plan["_root"], root_plan.get("schemaFile", "values.schema.json"), what="schemaFile")
    schema_path = resolve_within(repo_root, schema_rel, what="values schema file")
    if not schema_path.is_file():
        return
    # newline="" so CRLF reaches _edit_values_schema verbatim -- it does its
    # own CRLF normalize/restore round trip, the same pattern patch_workload
    # uses for a workload manifest.
    with schema_path.open(encoding="utf-8", newline="") as handle:
        raw = handle.read()
    try:
        schema = json.loads(raw)
    except ValueError as exc:
        raise bad_input(f"{schema_rel}: invalid JSON: {exc}") from None
    if not isinstance(schema, dict):
        return
    properties = schema.get("properties")
    required = schema.get("required")
    properties_correct = isinstance(properties, dict) and properties.get(DBAAS_OPERATOR_NAMESPACE_VALUE) == {
        "type": "string"
    }
    required_needs_removal = isinstance(required, list) and DBAAS_OPERATOR_NAMESPACE_VALUE in required
    if properties_correct and not required_needs_removal:
        return  # already semantically correct -- leave the file untouched

    edited = _edit_values_schema(raw, schema_rel, properties_correct, required_needs_removal)
    changes.set_content(schema_rel, edited)


def _insert_yaml_member(work: str, map_node: Any, key: str, value: Any) -> tuple[int, int, str]:
    """Edit tuple inserting ``key: value`` as the first member of the
    (already-composed) YAML/JSON mapping node ``map_node``.

    Matches the mapping's own compact-vs-pretty style -- a first member
    that starts on the same line as ``{`` versus one on its own indented
    line -- instead of always gluing a compact entry onto the "{" line,
    which would look wrong pasted into an otherwise pretty object.
    """

    open_index = map_node.start_mark.index
    entry_text = json.dumps(key) + ": " + json.dumps(value)
    if not map_node.value:
        close_index = map_node.end_mark.index - 1  # index of '}'
        inner = work[open_index + 1 : close_index]
        if "\n" not in inner:
            return open_index + 1, open_index + 1, entry_text
        # A pretty (but empty) mapping: match the closing brace's own
        # indentation, one level deeper, instead of gluing a compact entry
        # onto the "{" line -- the closing "}" itself stays untouched and
        # keeps the original whitespace leading up to it.
        line_start = work.rfind("\n", 0, close_index) + 1
        closing_indent = work[line_start:close_index]
        return open_index + 1, open_index + 1, "\n" + closing_indent + "  " + entry_text
    first_key = map_node.value[0][0]
    pretty = "\n" in work[open_index:first_key.start_mark.index]
    entry = entry_text + ","
    if pretty:
        entry = "\n" + " " * first_key.start_mark.column + entry
    return open_index + 1, open_index + 1, entry


def _edit_values_schema(raw: str, schema_rel: str, properties_correct: bool, required_needs_removal: bool) -> str:
    """Apply only the byte spans that need to change, leaving every other
    byte -- property order, indentation, compact vs. pretty layout,
    newline style -- exactly as it was.

    Locates those spans the same way ``patch_workload`` locates spans in a
    workload manifest: compose the (CRLF-normalized) text with PyYAML and
    read node start/end marks, never a custom parser.
    """

    uses_crlf = "\r\n" in raw
    if uses_crlf and "\n" in raw.replace("\r\n", ""):
        raise unsupported(f"{schema_rel}: mixed line endings; cannot edit in place")
    work = raw.replace("\r\n", "\n") if uses_crlf else raw
    try:
        root = yaml.compose(work)
    except yaml.YAMLError as exc:
        # The caller already proved this is valid JSON via json.loads(); a
        # compose() failure here means PyYAML's (YAML 1.1) grammar rejects
        # some valid-JSON construct (a literal tab used as whitespace, for
        # example) -- not that the document itself is invalid.
        raise unsupported(f"{schema_rel}: valid JSON layout is unsupported for in-place editing: {exc}") from None
    if not _is_mapping(root):
        raise unsupported(f"{schema_rel}: not a JSON object at the top level; cannot edit in place")

    def top_member(name: str) -> Any | None:
        for key_node, value_node in root.value:
            if key_node.value == name:
                return value_node
        return None

    edits: list[tuple[int, int, str]] = []

    if not properties_correct:
        properties_node = top_member("properties")
        if properties_node is None:
            edits.append(
                _insert_yaml_member(work, root, "properties", {DBAAS_OPERATOR_NAMESPACE_VALUE: {"type": "string"}})
            )
        elif not _is_mapping(properties_node):
            raise unsupported(f"{schema_rel}: properties is not a JSON object; cannot edit in place")
        else:
            prop_value_node = None
            for key_node, value_node in properties_node.value:
                if key_node.value == DBAAS_OPERATOR_NAMESPACE_VALUE:
                    prop_value_node = value_node
                    break
            if prop_value_node is None:
                edits.append(_insert_yaml_member(work, properties_node, DBAAS_OPERATOR_NAMESPACE_VALUE, {"type": "string"}))
            else:
                edits.append(
                    (prop_value_node.start_mark.index, prop_value_node.end_mark.index, json.dumps({"type": "string"}))
                )

    if required_needs_removal:
        required_node = top_member("required")
        if required_node is None or not required_node.tag.endswith(":seq"):
            raise unsupported(f"{schema_rel}: required is not a JSON array; cannot edit in place")
        target = next((item for item in required_node.value if item.value == DBAAS_OPERATOR_NAMESPACE_VALUE), None)
        if target is None:
            raise unsupported(f"{schema_rel}: could not relocate {DBAAS_OPERATOR_NAMESPACE_VALUE!r} in required")
        seq_start = required_node.start_mark.index
        seq_end = required_node.end_mark.index
        elem_start = target.start_mark.index
        elem_end = target.end_mark.index
        # Remove the element plus one adjacent comma (and its whitespace),
        # preferring the preceding comma so the remaining array keeps its
        # existing per-line layout with nothing left to reformat.
        before = elem_start
        while before > seq_start + 1 and work[before - 1] in " \t\n":
            before -= 1
        if before > seq_start + 1 and work[before - 1] == ",":
            edits.append((before - 1, elem_end, ""))
        else:
            after = elem_end
            while after < seq_end - 1 and work[after] in " \t\n":
                after += 1
            if after < seq_end - 1 and work[after] == ",":
                after += 1
                while after < seq_end - 1 and work[after] in " \t\n":
                    after += 1
            edits.append((elem_start, after, ""))

    for start, end, replacement in sorted(edits, key=lambda e: -e[0]):
        work = work[:start] + replacement + work[end:]

    try:
        result = json.loads(work)
    except ValueError as exc:
        raise unsupported(f"{schema_rel}: edited JSON is invalid: {exc}") from None
    if result.get("properties", {}).get(DBAAS_OPERATOR_NAMESPACE_VALUE) != {"type": "string"}:
        raise unsupported(f"{schema_rel}: edit did not produce the expected properties.{DBAAS_OPERATOR_NAMESPACE_VALUE}")
    result_required = result.get("required")
    if isinstance(result_required, list) and DBAAS_OPERATOR_NAMESPACE_VALUE in result_required:
        raise unsupported(f"{schema_rel}: edit did not remove {DBAAS_OPERATOR_NAMESPACE_VALUE} from required")

    return work.replace("\n", "\r\n") if uses_crlf else work


# --------------------------------------------------------------------------- #
# Helm render + validation
# --------------------------------------------------------------------------- #

_ANY_VALUE_REF = re.compile(r"\.Values\.([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*)")
_VALUE_REF = re.compile(r"\{\{-?\s*\.Values\.([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*)\s*-?\}\}")


def _pilot_value(key: str) -> str:
    if key == "API_DBAAS_ADDRESS":
        return "http://dbaas-aggregator.dbaas-operator:8080"
    return f"pilot-{key.lower().replace('_', '-')}"


def _resolve_templates(text: str, values: dict[str, str]) -> str:
    return _VALUE_REF.sub(lambda m: values.get(m.group(1), m.group(0)), text)


def _chart_values(chart_dir: Path) -> dict[str, Any]:
    values_path = chart_dir / "values.yaml"
    if not values_path.is_file() or yaml is None:
        return {}
    try:
        loaded = yaml.safe_load(values_path.read_text(encoding="utf-8"))
    except yaml.YAMLError:
        return {}
    return loaded if isinstance(loaded, dict) else {}


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
                raise bad_input(f"helmValues key {dotted_key!r} conflicts with another key")
            current = child
        if parts[-1] in current and isinstance(current[parts[-1]], dict):
            raise bad_input(f"helmValues key {dotted_key!r} conflicts with another key")
        current[parts[-1]] = value
    return result


def _inventory_classifier(classifier: dict[str, Any], workload_namespace: str) -> dict[str, Any]:
    # effective_classifier (validate_generated.py) always injects
    # classifier.namespace from the generated CR's metadata.namespace before
    # computing its identity key; the inventory side must inject the same
    # workload namespace here or every identity comparison mismatches on a
    # present-vs-absent namespace key alone.
    wire = wire_classifier(classifier)
    wire["namespace"] = workload_namespace
    return wire


def validate_root(tree_root: Path, root_plan: dict[str, Any], output_content: str) -> list[dict[str, str]]:
    results: list[dict[str, str]] = []
    root = root_plan["_root"]
    chart_dir = tree_root / root if root else tree_root
    inventory_datasources = []
    for ds in root_plan["datasources"]:
        inventory_datasources.append({
            "id": ds["id"], "type": ds["type"],
            "classifier": _inventory_classifier(ds["classifier"], root_plan["workloadNamespace"]),
            "requestedRoles": ds["requestedRoles"], "migrationFeasibility": "SUPPORTED",
        })

    if root_plan["kind"] == "plain":
        if "{{" in output_content:
            return [{"name": "no-helm-in-plain-output", "status": "failed", "details": f"{root_plan['_outputPath']}: a plain-manifest output must not contain Helm expressions"}]
        inventory_path = tree_root / "__inventory.json"
        inventory_path.write_text(json.dumps({"datasources": inventory_datasources}), encoding="utf-8")
        try:
            errors = validator.validate(
                [tree_root / root_plan["_outputPath"]]
                + [tree_root / c["_workloadPath"] for c in root_plan["claims"]],
                inventory_path,
                root_plan["operatorNamespace"],
                default_namespace=root_plan["workloadNamespace"],
            )
        except (TypeError, ValueError) as exc:
            errors = [str(exc)]
        results.append({"name": "validate-generated", "status": "failed" if errors else "passed", "details": "; ".join(errors)})
        return results

    helm = shutil.which("helm")
    if helm is None:
        raise unsupported("helm is required to certify a helm root", [f"{root}: helm is not on PATH"])
    if not (chart_dir / "Chart.yaml").is_file():
        return [{"name": "helm-render", "status": "failed", "details": f"{root or '.'}: not a Helm chart (no Chart.yaml)"}]

    scan_text = output_content + json.dumps(root_plan["operatorNamespace"]) + json.dumps(root_plan["workloadNamespace"])
    value_keys = set(_ANY_VALUE_REF.findall(scan_text))
    chart_values = _chart_values(chart_dir)
    resolved: dict[str, str] = {}
    overrides: dict[str, str] = {}
    for key in value_keys:
        existing = _value_at(chart_values, key)
        # DBAAS_OPERATOR_NAMESPACE is deliberately registered with an empty
        # default (update_values) so the chart stays installable before a
        # deployer supplies the real value -- validating against that empty
        # placeholder would make spec.operatorNamespace's required-non-empty
        # check fail on every render, not just a genuinely broken one, so a
        # pilot value stands in for it *only* when the chart has not already
        # supplied a real one. When the chart's own values.yaml pins a real
        # (possibly invalid) value, rendering with it is the whole point of
        # this check -- silently substituting a clean pilot value instead
        # would make expected_operator_ns below compare the substitution
        # against itself and never catch a genuinely bad pinned value.
        if key == DBAAS_OPERATOR_NAMESPACE_VALUE and existing in (None, ""):
            resolved[key] = overrides[key] = _pilot_value(key)
        elif isinstance(existing, (str, int, float, bool)):
            resolved[key] = str(existing)
        else:
            resolved[key] = overrides[key] = _pilot_value(key)
    overrides.update(root_plan["helmValues"])
    resolved.update(root_plan["helmValues"])
    release_namespace = _resolve_templates(root_plan["workloadNamespace"], resolved) or _PILOT_RELEASE
    values_file = tree_root / ".dbaas-migration-values.yaml"
    values_file.write_text(yaml.safe_dump(_values_tree(overrides), sort_keys=True), encoding="utf-8")
    cmd = [
        helm, "template", _PILOT_RELEASE, str(chart_dir),
        "--namespace", release_namespace, "--values", str(values_file),
    ]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=180, check=False)
    except (OSError, subprocess.SubprocessError) as exc:
        return [{"name": "helm-render", "status": "failed", "details": f"helm template did not run: {exc}"}]
    if proc.returncode != 0:
        return [{"name": "helm-render", "status": "failed", "details": f"helm template failed: {proc.stderr.strip()[:2000]}"}]
    results.append({"name": "helm-render", "status": "passed", "details": ""})

    rendered_path = tree_root / "__rendered.yaml"
    rendered_path.write_text(proc.stdout, encoding="utf-8")
    rendered_inventory = tree_root / "__rendered-inventory.json"
    rendered_inventory.write_text(_resolve_templates(json.dumps({"datasources": inventory_datasources}), resolved), encoding="utf-8")
    expected_operator_ns = None
    if "{{" not in root_plan["operatorNamespace"]:
        expected_operator_ns = root_plan["operatorNamespace"]
    else:
        substituted = _resolve_templates(root_plan["operatorNamespace"], resolved)
        expected_operator_ns = substituted if "{{" not in substituted else None
    try:
        errors = validator.validate(
            [rendered_path], rendered_inventory, expected_operator_ns, default_namespace=release_namespace
        )
    except (TypeError, ValueError) as exc:
        errors = [str(exc)]
    results.append({"name": "validate-rendered", "status": "failed" if errors else "passed", "details": "; ".join(errors)})
    return results


# --------------------------------------------------------------------------- #
# Orchestration
# --------------------------------------------------------------------------- #


def build_changes(repo_root: Path, plan: dict[str, Any]) -> Changes:
    # load_plan already requires every plan.roots[].datasources entry to be
    # SUPPORTED (and the list to be non-empty), so every root here has
    # something to generate -- no further feasibility filtering needed.
    changes = Changes()
    for root_plan in plan["roots"]:
        if root_plan["kind"] == "helm" and shutil.which("helm") is None:
            raise unsupported("helm is required to build a helm-root plan", [f"{root_plan['_root']}: helm is not on PATH"])
        bodies, name_bundle = build_resources(root_plan)
        content = render_resources(bodies)
        changes.set_content(root_plan["_outputPath"], content)
        apply_workload_patches(repo_root, root_plan, name_bundle, changes)
        verify_superseded(repo_root, root_plan, changes)
        update_values(repo_root, root_plan, changes)
    return changes


def run(repo_root: Path, plan_path: Path, mode: str) -> dict[str, Any]:
    plan = load_plan(plan_path, repo_root)
    for root_plan in plan["roots"]:
        check_source_hashes(repo_root, root_plan)

    changes = build_changes(repo_root, plan)
    # check_output_ownership needs this run's generated content to allow an
    # already-applied output through on a repeated --apply (see its own
    # docstring), so it runs after build_changes -- never before -- computes
    # that content.
    for root_plan in plan["roots"]:
        check_output_ownership(repo_root, root_plan, changes.files.get(root_plan["_outputPath"]))
    check_changes_within_roots(plan, changes)
    file_lists = classify(repo_root, changes)

    # One independent temporary tree per root -- not one shared tree for
    # every root -- so root "." and a nested chart root, or two unrelated
    # roots, never risk copying overlapping content into one destination.
    validation: list[dict[str, str]] = []
    for root_plan in plan["roots"]:
        with tempfile.TemporaryDirectory(prefix="dbaas-mounted-secret-") as tmp:
            tree_root = Path(tmp)
            materialize_tree(repo_root, root_plan["_root"], changes, tree_root)
            output_content = changes.files.get(root_plan["_outputPath"], "")
            validation.extend(validate_root(tree_root, root_plan, output_content or ""))

    failed = [entry for entry in validation if entry["status"] != "passed"]
    if failed:
        return {
            "status": "blocked",
            "createdFiles": [], "modifiedFiles": [], "deletedFiles": [], "unchangedFiles": [],
            "validation": validation,
        }

    touched = file_lists["createdFiles"] or file_lists["modifiedFiles"] or file_lists["deletedFiles"]
    if mode == "apply" and touched:
        check_source_hashes_all(repo_root, plan, changes)
        commit(repo_root, changes)
        status = "changed"
    elif mode == "apply":
        status = "unchanged"
    else:
        status = "valid"
    return {**file_lists, "status": status, "validation": validation}


def check_source_hashes_all(repo_root: Path, plan: dict[str, Any], changes: Changes) -> None:
    for root_plan in plan["roots"]:
        check_source_hashes(repo_root, root_plan)
        check_output_ownership(repo_root, root_plan, changes.files.get(root_plan["_outputPath"]))


def main() -> int:
    parser = argparse.ArgumentParser(description="Deterministic mounted-secret migration writer.")
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--plan", required=True, type=Path)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", dest="mode", action="store_const", const="check")
    mode.add_argument("--apply", dest="mode", action="store_const", const="apply")
    try:
        args = parser.parse_args()
    except SystemExit as exc:
        if exc.code in (0, None):
            raise
        print(json.dumps({"status": "blocked", "validation": [{"name": "cli", "status": "failed", "details": "invalid command line"}]}))
        return EXIT_BAD_INPUT

    if yaml is None:
        print(json.dumps({"status": "blocked", "validation": [{"name": "dependency", "status": "failed", "details": "PyYAML is required"}]}))
        return EXIT_UNSUPPORTED
    if not args.repo_root.is_dir():
        print(json.dumps({"status": "blocked", "validation": [{"name": "plan", "status": "failed", "details": f"--repo-root is not a directory: {args.repo_root}"}]}))
        return EXIT_BAD_INPUT

    try:
        result = run(args.repo_root.resolve(), args.plan, args.mode)
    except MigrationError as exc:
        detail = "; ".join([str(exc), *exc.entries]) if exc.entries else str(exc)
        print(json.dumps({
            "status": "blocked",
            "createdFiles": [], "modifiedFiles": [], "deletedFiles": [], "unchangedFiles": [],
            "validation": [{"name": "plan", "status": "failed", "details": detail}],
            "blocking": sorted(exc.entries) if exc.entries else [str(exc)],
        }, indent=2))
        return exc.exit_code
    except Exception as exc:  # noqa: BLE001 - never a bare traceback
        print(json.dumps({
            "status": "blocked",
            "createdFiles": [], "modifiedFiles": [], "deletedFiles": [], "unchangedFiles": [],
            "validation": [{"name": "internal", "status": "failed", "details": f"{type(exc).__name__}: {exc}"}],
        }, indent=2))
        return EXIT_BAD_INPUT

    print(json.dumps(result, indent=2))
    if result["status"] == "blocked":
        return EXIT_VALIDATION
    return EXIT_OK


if __name__ == "__main__":
    raise SystemExit(main())
