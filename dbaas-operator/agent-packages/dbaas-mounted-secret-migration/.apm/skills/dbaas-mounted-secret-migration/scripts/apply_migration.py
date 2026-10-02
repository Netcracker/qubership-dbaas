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
          // Derived from a namespaced Kubernetes service address in API_DBAAS_ADDRESS --
          // the second DNS label is the operator's namespace. See references/contracts.md
          // for when a plain literal or an explicit override is required instead.
          "operatorNamespace": "{{ (index (splitList \".\" (first (splitList \":\" (last (splitList \"://\" $.Values.API_DBAAS_ADDRESS))))) 1) }}",
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
under this root (every workload file and every superseded-declaration file)
with the SHA-256 the skill inspected; a mismatch is a stale-plan error (exit
3), not silently re-read. The writer never reads or writes ``values.yaml`` /
``values.schema.json`` -- ``operatorNamespace`` must already be a concrete,
verified value or Helm expression when the plan is built (see
references/contracts.md).
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
    "sourceHashes", "capabilityGuard", "operatorModeEnvironment",
}
_OPERATOR_MODE_ENV_KEYS = {"name", "value"}
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
        # capabilityGuard is optional (issue #776): omitting it preserves the
        # existing operator-only behavior exactly. When present, every
        # generated resource and inserted volume/mount is wrapped in a
        # ".Capabilities.APIVersions.Has" guard, and a superseded declaration
        # is preserved (guarded to the operator-absent branch) instead of
        # deleted -- see build_resources/apply_workload_patches/verify_superseded.
        capability_guard = entry.get("capabilityGuard")
        if capability_guard is not None and (
            not isinstance(capability_guard, str) or not capability_guard.strip()
        ):
            raise bad_input(f"{where}.capabilityGuard must be a non-empty string")
        operator_mode_environment = entry.get("operatorModeEnvironment")
        if operator_mode_environment is not None:
            if capability_guard is None:
                raise bad_input(f"{where}.operatorModeEnvironment requires capabilityGuard to be set")
            _reject_unknown(operator_mode_environment, _OPERATOR_MODE_ENV_KEYS, f"{where}.operatorModeEnvironment")
            for key in ("name", "value"):
                if not isinstance(operator_mode_environment.get(key), str) or not operator_mode_environment[key]:
                    raise bad_input(f"{where}.operatorModeEnvironment.{key} is required and must be a non-empty string")
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


def _capability_guard_open(capability_guard: str) -> str:
    return '{{- if .Capabilities.APIVersions.Has "' + capability_guard + '" }}\n'


_CAPABILITY_GUARD_CLOSE = "{{- end }}\n"


def render_resources(bodies: list[dict[str, Any]], capability_guard: str | None = None) -> str:
    chunks = []
    for body in bodies:
        chunks.append("---\n" + yaml.safe_dump(body, sort_keys=False, allow_unicode=False, default_flow_style=False))
    content = "".join(chunks)
    if capability_guard:
        # issue #776: every generated InternalDatabase/DatabaseSecretClaim only
        # renders when the target cluster's dbaas-operator CRDs are present --
        # the operator is never assumed to be installed. Omitting
        # capabilityGuard on the plan preserves the unwrapped, operator-only
        # output exactly as before.
        content = _capability_guard_open(capability_guard) + content + _CAPABILITY_GUARD_CLOSE
    return content


# --------------------------------------------------------------------------- #
# Workload patching -- raw span insertion, never a full re-dump
# --------------------------------------------------------------------------- #

# A standalone Helm action: the whole line, once stripped, is one (or more
# concatenated) {{ ... }} action(s) and nothing else -- if/else/range/with/end/
# define/block/template, or a "{{- $x := ... }}" variable assignment.
_STANDALONE_HELM_LINE = re.compile(r"^\{\{-?.*-?\}\}$", re.S)
_HELM_BLOCK_OPEN = re.compile(r"^\{\{-?\s*(?:if|range|with|define|block)\b")
_HELM_BLOCK_END = re.compile(r"^\{\{-?\s*end\s*-?\}\}$")
# A non-nested {{ ... }} expression, matched non-greedily so two separate
# expressions on one line ("{{ .A }}-{{ .B }}") are each matched on their own.
_INLINE_TEMPLATE = re.compile(r"\{\{(?:(?!\{\{|\}\}).)*\}\}", re.S)


def _template_filler(match: re.Match[str]) -> str:
    length = len(match.group(0))
    digest = hashlib.sha256(match.group(0).encode("utf-8")).hexdigest()
    return ("x" + digest * ((length // len(digest)) + 1))[:length]


def _mask_helm_template(text: str) -> str:
    """Length- and line-preserving mask (issue #776) used only so
    ``yaml.compose_all`` can locate static workload/pod-spec/container/
    volume/mount/environment nodes: a standalone Helm action line
    (``if``/``else``/``range``/``with``/``end``/``define``/``block``/
    ``template``, or a ``{{- $x := ... }}`` assignment) is blanked to spaces,
    and an inline ``{{ ... }}`` template expression is replaced with
    deterministic same-length filler so an otherwise-unquoted scalar
    (``name: {{ .Values.SERVICE_NAME }}``) parses as plain YAML.

    Every replacement is exactly as long as what it replaces, so line count,
    column positions, and every untouched byte are unchanged -- an edit
    computed from a node mark against this masked text applies at the same
    offset into the *original* text. Callers must feed this the already
    CRLF-normalized ``work`` text (bare ``\\n`` only): a masked scalar's
    content is disposable filler, never read back.
    """

    out = []
    for line in text.splitlines(keepends=True):
        body = line.rstrip("\n")
        ending = line[len(body):]
        stripped = body.strip()
        if stripped.startswith("{{") and _STANDALONE_HELM_LINE.match(stripped):
            out.append(" " * len(body) + ending)
            continue
        if "{{" in body:
            out.append(_INLINE_TEMPLATE.sub(_template_filler, body) + ending)
            continue
        out.append(line)
    return "".join(out)


# A mapping-entry scalar whose value contains a template: "key: {{ .X }}" or
# "- key: {{ .X }}" (a sequence item that is itself a one-line mapping).
_SCALAR_WITH_TEMPLATE = re.compile(r"^(\s*[^#\n][^:]*:\s*)(.*\{\{.*\}\}.*)$")
# A bare sequence-item scalar with no mapping key at all: "- {{ .X }}".
_SEQ_ITEM_WITH_TEMPLATE = re.compile(r"^(\s*-\s+)(\{\{.*\}\}.*)$")


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


def _mask_for_reading(text: str) -> str:
    """Parse-only mask (issue #776), distinct from ``_mask_helm_template``:
    a standalone Helm action line is blanked to nothing, and an unquoted
    templated scalar is single-quoted so it loads as the exact expression
    text (``{{ .Release.Name }}`` becomes that literal string value, not
    filler). Used only to read a value back -- by ``_verify_insertions``, to
    confirm a just-inserted volume/mount is reachable where it belongs, name
    intact even when that name is itself a Helm expression (a templated
    resource identity) -- never to locate a node's byte offset, so quoting
    is free to change the text's length.
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
            # A quoted scalar already parses as YAML, including either an
            # external trailing comment or a literal " #" inside its quotes.
            # Comment splitting applies only to plain scalars.
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


class WorkloadError(Exception):
    def __init__(self, entries: list[str]) -> None:
        super().__init__("; ".join(entries))
        self.entries = entries


def _yaml_scalar(value: str) -> str:
    if value.startswith("{{"):
        return "'" + value.replace("'", "''") + "'"
    return value


def _yaml_string_scalar(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def _masked_template_value(value: Any) -> Any:
    if not isinstance(value, str):
        return value
    return _INLINE_TEMPLATE.sub(_template_filler, value)


def _template_value_matches(actual: Any, expected: Any) -> bool:
    return actual == expected or actual == _masked_template_value(expected)


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
        if name_node is not None and _template_value_matches(name_node.value, name):
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
    """Where the block anchored at ``start_line`` ends: the first line at or
    below ``min_indent`` that is not blank.

    A standalone Helm action line (issue #776) -- masked to blank spaces for
    parsing, but still literally present with its original text in this
    unmasked ``lines`` array -- is also treated as insignificant here,
    transparent to the scan regardless of its own indentation: a real next
    sibling key, or the true end of a sequence, may sit past a conditional
    wrapping something else entirely. ``patch_workload`` re-verifies every
    inserted item is reachable in the result afterward (``_verify_insertions``),
    which catches an insertion point this transparency moved outside its
    target's workload/container -- but not every case: a conditional wrapping
    the *entire* target key/sequence still parses as "found" under masking
    (YAML tolerates a blank line, what the conditional's closing action
    becomes, between sequence items regardless of the real indentation the
    guard implies), so that specific shape relies on the real
    helm-render/validate-rendered pass downstream instead.
    """

    index = start_line + 1
    total = len(lines)
    while index < total:
        stripped = lines[index].strip()
        if stripped == "" or _STANDALONE_HELM_LINE.match(stripped) or _indent(lines[index]) > min_indent:
            index += 1
            continue
        break
    # Trim back over trailing *blank* lines only -- reached only when the loop
    # above ran off the end of the file without finding a real next line, so
    # the insertion point does not land after a pile of trailing whitespace.
    # A standalone Helm action line is not blank filler: it is a meaningful
    # boundary that the insertion point must stay *after* once skipped, so it
    # is never trimmed back over here even when the forward scan above
    # treated it as transparent.
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


def _render_env(name: str, value: str, indent: int) -> str:
    pad = " " * indent
    return f"{pad}- name: {_yaml_scalar(name)}\n{pad}  value: {_yaml_string_scalar(value)}\n"


def _guard_wrapped(capability_guard: str | None, indent: int, block: str) -> str:
    """Wrap ``block`` (already-rendered, ``indent``-indented sequence items)
    in the capability guard (issue #776), or return it unchanged when no
    guard is configured. The guard lines' own indentation is cosmetic --
    masking treats a standalone Helm action line as blank regardless of its
    column -- but matches ``indent`` for readability."""

    if not capability_guard:
        return block
    pad = " " * indent
    open_line = pad + '{{- if .Capabilities.APIVersions.Has "' + capability_guard + '" }}\n'
    close_line = pad + "{{- end }}\n"
    return open_line + block + close_line


def _item_content_end(lines: list[str], start_line: int, min_indent: int) -> int:
    """Where an existing item's own content ends: the first line at or below
    ``min_indent`` (blank or not). Deliberately *not* ``_block_content_end``:
    that function treats a standalone Helm action line as transparent so an
    insertion point can be found past a conditional wrapping something
    unrelated -- exactly wrong here, since the line right after an existing
    item is very often the ``{{- end }}`` closing its own guard, which this
    function must stop *at*, never skip past.
    """

    index = start_line + 1
    total = len(lines)
    while index < total:
        if lines[index].strip() == "" or _indent(lines[index]) > min_indent:
            index += 1
            continue
        break
    return index


def _existing_item_span(lines: list[str], item: Any) -> tuple[int, int]:
    """(start_line, end_line_exclusive) for an already-existing sequence
    item node. Used only to locate an existing item's own span for the
    guard-wrapping check below, never to edit its content.
    """

    start_line = item.start_mark.line
    dash_col = _indent(lines[start_line])
    end_line = _item_content_end(lines, start_line, dash_col)
    return start_line, end_line


def _is_item_guarded(lines: list[str], item: Any, capability_guard: str) -> bool:
    """Return whether the item is inside this capability guard.

    One guard may wrap several adjacent generated sequence items. Track Helm
    block nesting up to the item's first line instead of requiring an opening
    and closing action immediately around that individual item.
    """

    target_open = _capability_guard_open(capability_guard).strip()
    stack: list[bool] = []
    for raw_line in lines[:item.start_mark.line]:
        action = raw_line.strip()
        if _HELM_BLOCK_OPEN.match(action):
            stack.append(action == target_open)
        elif _HELM_BLOCK_END.match(action) and stack:
            stack.pop()
    return any(stack)


def _guard_existing_item(lines: list[str], item: Any, capability_guard: str, edits: list[tuple[int, int, str]]) -> None:
    """Wrap an already-existing, unguarded sequence item in the capability
    guard in place -- two pure insertions around its untouched span, never a
    rewrite of its own content."""

    start_line, end_line = _existing_item_span(lines, item)
    pad = " " * _indent(lines[start_line])
    edits.append((start_line, 0, pad + _capability_guard_open(capability_guard)))
    edits.append((end_line, 0, pad + _CAPABILITY_GUARD_CLOSE))


def _guard_existing_items(
    lines: list[str], items: list[Any], capability_guard: str, edits: list[tuple[int, int, str]]
) -> None:
    """Guard existing items, merging adjacent spans under one guard.

    Separate per-item edits collide where one item's closing insertion and
    the next item's opening insertion share a line. Merging each contiguous
    run avoids inverted, nested guards and matches how new items are emitted.
    """

    spans = sorted((_existing_item_span(lines, item) for item in items), key=lambda span: span[0])
    runs: list[list[int]] = []
    for start_line, end_line in spans:
        if runs and start_line <= runs[-1][1]:
            runs[-1][1] = max(runs[-1][1], end_line)
        else:
            runs.append([start_line, end_line])
    for start_line, end_line in runs:
        pad = " " * _indent(lines[start_line])
        edits.append((start_line, 0, pad + _capability_guard_open(capability_guard)))
        edits.append((end_line, 0, pad + _CAPABILITY_GUARD_CLOSE))


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


def _existing_named(existing: dict[str, Any], name: str) -> Any:
    if name in existing:
        return existing[name]
    return existing.get(_masked_template_value(name))


def _find_container(pod_spec: Any, container_field: str, container_name: str) -> Any:
    for key, value in pod_spec.value:
        if not (hasattr(key, "value") and key.value == container_field and value.tag.endswith(":seq")):
            continue
        for item in value.value:
            if not item.tag.endswith(":map"):
                continue
            for item_key, item_value in item.value:
                if (
                    hasattr(item_key, "value")
                    and item_key.value == "name"
                    and _template_value_matches(getattr(item_value, "value", None), container_name)
                ):
                    return item
    return None


def patch_workload(
    text: str,
    *,
    filename: str,
    targets: list[dict[str, Any]],
    capability_guard: str | None = None,
    operator_mode_environment: dict[str, str] | None = None,
) -> str:
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
    lines = work.splitlines(keepends=True)
    if lines and not lines[-1].endswith("\n"):
        lines[-1] += "\n"
    try:
        # Parse the masked buffer, not the original: a standalone Helm action is no
        # longer rejected outright (issue #776) -- it is blanked to same-length
        # spaces/filler so compose_all can locate the static structure around it.
        # Node marks from the masked text are still valid offsets into `lines`
        # (the original, unmasked text) since every replacement preserves length.
        nodes = [node for node in yaml.compose_all(_mask_helm_template(work)) if node is not None]
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

        # Appends below are ordered outermost-first (new sibling items before
        # guarding an existing item in place) for the same reason as the
        # container loop further down: an existing item's own guard-close
        # must end up closest to it, not have newly appended siblings land
        # between it and its own close (see the container loop's comment).
        existing_volumes = _existing_names(pod_spec, "volumes")
        new_volumes: list[tuple[str, str]] = []
        volumes_needing_guard: list[Any] = []
        for volume_name, secret_name in target["volumes"]:
            existing_item = _existing_named(existing_volumes, volume_name)
            if existing_item is not None:
                existing_secret = _walk(existing_item, ["secret", "secretName"])
                if existing_secret is None or not _template_value_matches(existing_secret.value, secret_name):
                    problems.append(f"{filename}: volume {volume_name!r} already exists with a different secret")
                    continue
                # issue #776: an existing, matching volume from a prior apply
                # made *before* capabilityGuard was set on this plan is not
                # idempotent-and-done -- it is unconditional today, and would
                # stay unconditional forever if silently skipped here, mounting
                # a Secret the (possibly absent) operator never creates in the
                # operator-absent branch. Guard it in place instead; only a
                # volume already wrapped in exactly this guard is a true no-op.
                if capability_guard and not _is_item_guarded(lines, existing_item, capability_guard):
                    volumes_needing_guard.append(existing_item)
                continue  # idempotent: already exactly this mount (and now guarded if needed)
            new_volumes.append((volume_name, secret_name))
        if new_volumes:
            _insert_block_list(
                pod_spec, "volumes", lines,
                lambda indent, items=new_volumes: _guard_wrapped(
                    capability_guard, indent, "".join(_render_volume(n, s, indent) for n, s in items)
                ),
                edits, problems, f"{filename} {target['kind']}/{target['name']} spec.template.spec.volumes",
            )
        if volumes_needing_guard:
            _guard_existing_items(lines, volumes_needing_guard, capability_guard, edits)

        for container_field, mounts in (("containers", target["containerMounts"]), ("initContainers", target["initContainerMounts"])):
            for container_name, wanted in mounts.items():
                container_node = _find_container(pod_spec, container_field, container_name)
                if container_node is None:
                    problems.append(f"{filename}: {container_field} {container_name!r} not found in {target['kind']}/{target['name']}")
                    continue
                # Two or more of the edits below can compute the exact same
                # insertion line: _block_content_end (used to find a new key's
                # or a new sequence item's insertion point) treats a standalone
                # Helm action as transparent, and an unconditional existing key
                # with nothing else after it in the container gives every edit
                # anchored off "the end of this container's existing content"
                # the same landing spot, regardless of which of these three
                # concerns it belongs to. Two edits at one line apply in
                # reverse-append order (the later-appended one ends up first,
                # pushing the earlier one after it), so appends below are
                # ordered outermost-first: operatorModeEnvironment's new "env"
                # key (a whole new sibling key, belongs furthest out) before
                # new volumeMounts items (new siblings *within* the existing
                # "volumeMounts" sequence) before guarding an existing item in
                # place (issue #776 -- tightly bound to that exact item, must
                # end up closest to it, closing immediately after it rather
                # than after content appended alongside it).
                existing_mounts = _existing_names(container_node, "volumeMounts")
                new_mounts: list[tuple[str, str]] = []
                mounts_needing_guard: list[Any] = []
                for volume_name, mount_path in wanted:
                    existing_item = _existing_named(existing_mounts, volume_name)
                    if existing_item is not None:
                        existing_path = _walk(existing_item, ["mountPath"])
                        if existing_path is None or not _template_value_matches(existing_path.value, mount_path):
                            problems.append(f"{filename}: {container_field} {container_name!r} already mounts {volume_name!r} at a different path")
                            continue
                        # issue #776: an existing, matching mount predating
                        # capabilityGuard must be guarded now, not left
                        # unconditional forever.
                        if capability_guard and not _is_item_guarded(lines, existing_item, capability_guard):
                            mounts_needing_guard.append(existing_item)
                        continue  # idempotent
                    new_mounts.append((volume_name, mount_path))

                if operator_mode_environment is not None:
                    env_name = operator_mode_environment["name"]
                    env_value = operator_mode_environment["value"]
                    existing_env = _existing_names(container_node, "env")
                    env_needing_guard = None
                    existing_item = _existing_named(existing_env, env_name)
                    if existing_item is not None:
                        existing_value = _walk(existing_item, ["value"])
                        if existing_value is None or not _template_value_matches(existing_value.value, env_value):
                            problems.append(
                                f"{filename}: {container_field} {container_name!r} already has env "
                                f"{env_name!r} with a different value"
                            )
                        elif not _is_item_guarded(lines, existing_item, capability_guard):
                            # operatorModeEnvironment requires capabilityGuard (load_plan),
                            # so an existing, matching entry predating it must be guarded now.
                            env_needing_guard = existing_item
                    else:
                        _insert_block_list(
                            container_node, "env", lines,
                            lambda indent: _guard_wrapped(
                                capability_guard, indent, _render_env(env_name, env_value, indent)
                            ),
                            edits, problems, f"{filename} {container_field} {container_name!r} env",
                        )

                if new_mounts:
                    _insert_block_list(
                        container_node, "volumeMounts", lines,
                        lambda indent, items=new_mounts: _guard_wrapped(
                            capability_guard, indent, "".join(_render_mount(n, p, indent) for n, p in items)
                        ),
                        edits, problems, f"{filename} {container_field} {container_name!r} volumeMounts",
                    )

                if mounts_needing_guard:
                    _guard_existing_items(lines, mounts_needing_guard, capability_guard, edits)
                if operator_mode_environment is not None and env_needing_guard is not None:
                    _guard_existing_item(lines, env_needing_guard, capability_guard, edits)

    if problems:
        raise WorkloadError(problems)
    for line_index, replace_count, chunk in sorted(edits, key=lambda item: -item[0]):
        lines[line_index:line_index + replace_count] = [chunk]
    result = "".join(lines)

    # _block_content_end treats a standalone Helm action as transparent so a
    # real next sibling key (or a sequence's true end) can be found past a
    # conditional wrapping something unrelated (issue #776). Re-locate every
    # inserted item in the fully edited result to catch the cases that check
    # can still get wrong -- most reliably a target the edit moved outside
    # its workload/container entirely, or edited text that no longer parses
    # at all. It cannot detect every semantic break: YAML tolerates a blank
    # line (what a masked Helm action line becomes) anywhere between sequence
    # items regardless of the real indentation the guard closing over it
    # would imply, so a conditional wrapping the *entire* target key/sequence
    # (not just unrelated content) still reads as "found" here even though
    # real Helm would omit it whenever the condition is false -- that specific
    # shape is instead caught downstream, by the real helm-render/
    # validate-rendered pass against actual chart values.
    verification_problems = _verify_insertions(result, filename, targets, operator_mode_environment)
    if verification_problems:
        raise WorkloadError(verification_problems)

    # The synthetic trailing newline above exists only so internal editing
    # never appends content onto an unterminated last line; restore the
    # original's own final-newline state here rather than always keeping it
    # (an edit landing at the true end of file already brings its own
    # newline, so removing this one leaves that content correctly
    # terminated too -- it never strips anything the edit itself needed).
    if not had_trailing_newline and result.endswith("\n"):
        result = result[:-1]
    return result.replace("\n", "\r\n") if uses_crlf else result


def _verify_insertions(
    result: str,
    filename: str,
    targets: list[dict[str, Any]],
    operator_mode_environment: dict[str, str] | None = None,
) -> list[str]:
    """Confirm every requested volume and mount is actually reachable where
    it belongs in the fully edited ``result`` (issue #776) -- the precise
    blocking path for an insertion point that turned out to sit outside the
    structure it meant to extend (see ``patch_workload``'s call site)."""

    try:
        nodes = [node for node in yaml.compose_all(_mask_for_reading(result)) if node is not None]
    except yaml.YAMLError as exc:
        return [f"{filename}: editing produced invalid YAML: {exc}"]

    problems: list[str] = []
    for target in targets:
        node = _find_workload(nodes, target["kind"], target["name"])
        pod_spec = _walk(node, ["spec", "template", "spec"]) if node is not None else None
        if pod_spec is None or not _is_mapping(pod_spec):
            problems.append(
                f"{filename}: {target['kind']}/{target['name']}: editing did not preserve a static "
                "spec.template.spec mapping"
            )
            continue

        volumes = _existing_names(pod_spec, "volumes")
        for volume_name, secret_name in target["volumes"]:
            volume = _existing_named(volumes, volume_name)
            secret_ref = _walk(volume, ["secret", "secretName"]) if volume is not None else None
            if secret_ref is None or not _template_value_matches(secret_ref.value, secret_name):
                problems.append(
                    f"{filename}: {target['kind']}/{target['name']}: volume {volume_name!r} is not safely "
                    "nested under spec.template.spec.volumes after editing -- the insertion point found by "
                    "indentation alone landed outside a Helm conditional/loop that wraps the target structure "
                    "itself; no static insertion point exists"
                )

        for container_field, mounts in (
            ("containers", target["containerMounts"]),
            ("initContainers", target["initContainerMounts"]),
        ):
            for container_name, wanted in mounts.items():
                container_node = _find_container(pod_spec, container_field, container_name)
                mount_names = _existing_names(container_node, "volumeMounts") if container_node is not None else {}
                for volume_name, mount_path in wanted:
                    mount = _existing_named(mount_names, volume_name)
                    path_ref = _walk(mount, ["mountPath"]) if mount is not None else None
                    if path_ref is None or not _template_value_matches(path_ref.value, mount_path):
                        problems.append(
                            f"{filename}: {target['kind']}/{target['name']} {container_field} "
                            f"{container_name!r}: volumeMount {volume_name!r} is not safely nested after editing "
                            "-- no static insertion point exists"
                        )
                if operator_mode_environment is not None:
                    env_names = _existing_names(container_node, "env") if container_node is not None else {}
                    env_name = operator_mode_environment["name"]
                    env = _existing_named(env_names, env_name)
                    value_ref = _walk(env, ["value"]) if env is not None else None
                    if value_ref is None or not _template_value_matches(
                        value_ref.value, operator_mode_environment["value"]
                    ):
                        problems.append(
                            f"{filename}: {target['kind']}/{target['name']} {container_field} "
                            f"{container_name!r}: env {env_name!r} is not safely nested after editing -- "
                            "no static insertion point exists"
                        )
    return problems


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
            patched = patch_workload(
                original, filename=path, targets=targets,
                capability_guard=root_plan.get("capabilityGuard"),
                operator_mode_environment=root_plan.get("operatorModeEnvironment"),
            )
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


def _negated_guard_open(capability_guard: str) -> str:
    return '{{- if not (.Capabilities.APIVersions.Has "' + capability_guard + '") }}\n'


def _wrap_in_negated_guard(capability_guard: str, span: str) -> str:
    # issue #776: preserve a superseded legacy declaration's bytes -- comments
    # and labels included -- verbatim, rather than deleting it, guarded to
    # render only in the operator-absent fallback branch. Ensure a trailing
    # newline before "{{- end }}" so it never lands glued onto the span's own
    # last line.
    body = span if span.endswith("\n") else span + "\n"
    return _negated_guard_open(capability_guard) + body + _CAPABILITY_GUARD_CLOSE


def verify_superseded(repo_root: Path, root_plan: dict[str, Any], changes: Changes) -> None:
    capability_guard = root_plan.get("capabilityGuard")
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
            if capability_guard:
                changes.set_content(path, _wrap_in_negated_guard(capability_guard, text))
            else:
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
            if capability_guard:
                changes.set_content(
                    path, preamble + _wrap_in_negated_guard(capability_guard, text[docs[0]["start"]:])
                )
            else:
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
        if capability_guard:
            # Every document keeps its original position; an addressed one is
            # wrapped to the operator-absent branch in place rather than removed.
            parts = [
                _wrap_in_negated_guard(capability_guard, text[doc["start"]:doc["end"]])
                if i in addressed
                else text[doc["start"]:doc["end"]]
                for i, doc in enumerate(docs, start=1)
            ]
            changes.set_content(path, preamble + "".join(parts))
            continue
        kept = "".join(text[doc["start"]:doc["end"]] for i, doc in enumerate(docs, start=1) if i not in addressed)
        if kept:
            changes.set_content(path, preamble + kept)
        else:
            changes.delete(path)


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
        # The writer never registers a default for any value key (including
        # DBAAS_OPERATOR_NAMESPACE -- it no longer gets special treatment): when
        # the chart's own values.yaml already pins a value, rendering with it is
        # the whole point of this check, including a real-but-empty value, since
        # a chart that explicitly chose an empty operator namespace must fail
        # spec.operatorNamespace's required-non-empty check here, not be
        # silently masked by a clean pilot substitute. Only a key genuinely
        # absent from values.yaml gets a pilot placeholder.
        if isinstance(existing, (str, int, float, bool)):
            resolved[key] = str(existing)
        else:
            resolved[key] = overrides[key] = _pilot_value(key)
    overrides.update(root_plan["helmValues"])
    resolved.update(root_plan["helmValues"])
    release_namespace = _resolve_templates(root_plan["workloadNamespace"], resolved) or _PILOT_RELEASE
    values_file = tree_root / ".dbaas-migration-values.yaml"
    values_file.write_text(yaml.safe_dump(_values_tree(overrides), sort_keys=True), encoding="utf-8")
    capability_guard = root_plan.get("capabilityGuard")

    def render(*, api_versions: str | None) -> tuple[str | None, list[dict[str, str]]]:
        cmd = [
            helm, "template", _PILOT_RELEASE, str(chart_dir),
            "--namespace", release_namespace, "--values", str(values_file),
        ]
        if api_versions:
            cmd += ["--api-versions", api_versions]
        try:
            proc = subprocess.run(cmd, capture_output=True, text=True, timeout=180, check=False)
        except (OSError, subprocess.SubprocessError) as exc:
            return None, [{"name": "helm-render", "status": "failed", "details": f"helm template did not run: {exc}"}]
        if proc.returncode != 0:
            return None, [{"name": "helm-render", "status": "failed", "details": f"helm template failed: {proc.stderr.strip()[:2000]}"}]
        return proc.stdout, [{"name": "helm-render", "status": "passed", "details": ""}]

    # Without a capability guard, a single render at the runner's own default
    # capabilities is the whole (pre-issue-#776) validation. With one, that
    # default render normally lacks the guard's capability -- rendering the
    # operator-absent fallback branch -- so the writer's own gate must
    # instead render *with* --api-versions <capabilityGuard> to certify the
    # branch its generated resources actually live in (see the "twice"
    # requirement in references/contracts.md's "Helm-templated workloads"
    # section); the second, default-capabilities render is certified
    # separately, below, to prove the fallback branch is what actually
    # renders when the operator's CRDs are absent.
    stdout, render_results = render(api_versions=capability_guard)
    results.extend(render_results)
    if stdout is None:
        return results

    rendered_path = tree_root / "__rendered.yaml"
    rendered_path.write_text(stdout, encoding="utf-8")
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

    if capability_guard:
        # The second, operator-absent render: native CRs and the operator-mode
        # env var must be entirely absent -- proving the guard actually
        # suppresses them rather than merely being present as inert text.
        fallback_stdout, fallback_results = render(api_versions=None)
        results.extend(r for r in fallback_results if r["status"] != "passed")
        if fallback_stdout is not None:
            fallback_problems: list[str] = []
            try:
                fallback_docs = [d for d in yaml.safe_load_all(fallback_stdout) if isinstance(d, dict)]
            except yaml.YAMLError as exc:
                fallback_problems.append(f"operator-absent render is not valid YAML: {exc}")
                fallback_docs = []
            for doc in fallback_docs:
                if doc.get("kind") in ("InternalDatabase", "DatabaseSecretClaim"):
                    fallback_problems.append(
                        f"operator-absent render (no --api-versions) still contains "
                        f"{doc.get('kind')} {doc.get('metadata', {}).get('name')!r}; "
                        "the capability guard did not suppress it"
                    )
            operator_mode_environment = root_plan.get("operatorModeEnvironment")
            if operator_mode_environment:
                env_name = operator_mode_environment["name"]
                for doc in fallback_docs:
                    for container in (doc.get("spec", {}).get("template", {}).get("spec", {}).get("containers") or []):
                        for entry in container.get("env") or []:
                            if isinstance(entry, dict) and entry.get("name") == env_name:
                                fallback_problems.append(
                                    f"operator-absent render still sets env {env_name!r} on "
                                    f"container {container.get('name')!r}"
                                )
            # issue #776: the generated Secret volume/volumeMount must be just
            # as absent as the native CRs -- a workload that still mounts it
            # here would fail on a real cluster with the operator's CRDs
            # absent (the Secret those CRs would have populated never exists),
            # even though every native-CR check above passed. This is what
            # actually catches an existing, unconditional mount left over
            # from an apply made before capabilityGuard was set (see
            # patch_workload's idempotency checks) if that upgrade path is
            # ever reintroduced incorrectly.
            _, fallback_name_bundle = build_resources(root_plan)
            expected_secret_names = {bundle["secret"] for bundle in fallback_name_bundle.values()}
            expected_volume_names = {bundle["volume"] for bundle in fallback_name_bundle.values()}
            for doc in fallback_docs:
                pod_spec = (doc.get("spec") or {}).get("template", {})
                pod_spec = pod_spec.get("spec") if isinstance(pod_spec, dict) else None
                if not isinstance(pod_spec, dict):
                    continue
                doc_id = f"{doc.get('kind')}/{(doc.get('metadata') or {}).get('name')!r}"
                for volume in pod_spec.get("volumes") or []:
                    if not isinstance(volume, dict):
                        continue
                    secret = volume.get("secret")
                    if isinstance(secret, dict) and secret.get("secretName") in expected_secret_names:
                        fallback_problems.append(
                            f"operator-absent render still has volume {volume.get('name')!r} mounting "
                            f"Secret {secret.get('secretName')!r} in {doc_id}"
                        )
                for field in ("containers", "initContainers"):
                    for container in pod_spec.get(field) or []:
                        if not isinstance(container, dict):
                            continue
                        for mount in container.get("volumeMounts") or []:
                            if isinstance(mount, dict) and mount.get("name") in expected_volume_names:
                                fallback_problems.append(
                                    f"operator-absent render still mounts volume {mount.get('name')!r} "
                                    f"in {field} {container.get('name')!r} of {doc_id}"
                                )
            results.append({
                "name": "validate-operator-absent-fallback",
                "status": "failed" if fallback_problems else "passed",
                "details": "; ".join(fallback_problems),
            })

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
        content = render_resources(bodies, root_plan.get("capabilityGuard"))
        changes.set_content(root_plan["_outputPath"], content)
        apply_workload_patches(repo_root, root_plan, name_bundle, changes)
        verify_superseded(repo_root, root_plan, changes)
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
