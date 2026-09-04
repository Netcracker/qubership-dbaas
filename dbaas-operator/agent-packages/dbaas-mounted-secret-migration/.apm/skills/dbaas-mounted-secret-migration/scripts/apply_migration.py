#!/usr/bin/env python3
"""Deterministic runner for the DBaaS mounted-secret migration.

The skill inventories the service and resolves every ambiguous value into a JSON
plan; this script is the only writer of migration files. It generates the
canonical ``InternalDatabase`` / ``DatabaseSecretClaim`` file, mounts every
generated Secret into the plan-selected containers, adds
``DBAAS_OPERATOR_NAMESPACE`` to the chart values and schema, removes the
superseded legacy declarations, and validates the result with the bundled
inventory validator in a temporary tree before touching the working copy.

Invocation and the plan / result envelopes are defined in ``_migration_common``.

Plan (`migrationKind: "mounted-secret"`) shape::

    {
      "inputs": {
        "operatorNamespace": "{{ .Values.DBAAS_OPERATOR_NAMESPACE }}",
        "datasources": [ <the section-2 inventory, effective wire classifiers> ]
      },
      "decisions": {
        "root": "chart",
        "rootKind": "helm",
        "workloadNamespace": "{{ .Values.NAMESPACE }}",
        "originService": "orders",
        "outputFile": "templates/dbaas-mounted-secret-resources.yaml",
        "valuesFile": "values.yaml",
        "schemaFile": "values.schema.json",
        "nameDiscriminators": {"<datasource-id>": "configs"},
        "claims": [
          {"datasourceId": "orders-postgresql-service", "role": "",
           "workloadFile": "templates/deployment.yaml",
           "workloadKind": "Deployment", "workloadName": "orders",
           "containers": ["orders"], "initContainers": []}
        ],
        "supersededDeclarations": ["templates/dbaas-configuration.json"],
        "outputOwnership": {}
      },
      "targets": [ ... ]
    }
"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any

import _migration_common as common
import _resource_build as build
import validate_generated
from _resource_build import GenerationError, claim_key
from _workload_patch import WorkloadError, patch_workloads

try:  # common.run() checks this before any work and reports it as a blocked result.
    import yaml
except ImportError:  # pragma: no cover - exercised only without the pinned dependency
    yaml = None  # type: ignore[assignment]

MIGRATION_KIND = "mounted-secret"
DEFAULT_HELM_OUTPUT = "templates/dbaas-mounted-secret-resources.yaml"
DEFAULT_PLAIN_OUTPUT = "dbaas-mounted-secret-resources.yaml"
OPERATOR_NAMESPACE_VALUE = "DBAAS_OPERATOR_NAMESPACE"
SUPPORTED_COMPAT = {
    "NATIVE_MOUNTED_PROVIDER",
    "EXPLICIT_SECRET_ADAPTER",
    "DIRECT_KUBERNETES_SECRET",
}
_DECISION_KEYS = {
    "root",
    "rootKind",
    "workloadNamespace",
    "originService",
    "outputFile",
    "valuesFile",
    "schemaFile",
    "nameDiscriminators",
    "claims",
    "supersededDeclarations",
    "outputOwnership",
}
_CLAIM_KEYS = {
    "datasourceId",
    "role",
    "workloadFile",
    "workloadKind",
    "workloadName",
    "containers",
    "initContainers",
}


class MountedSecretEngine:
    migration_kind = MIGRATION_KIND
    required_modules = ("yaml",)
    input_keys = {"operatorNamespace", "datasources"}
    decision_keys = _DECISION_KEYS

    def affected_roots(self, repo_root: Path, plan: common.Plan) -> list[str]:
        return [_require_str(plan.decisions, "root")]

    # ----------------------------------------------------------------- #

    def build_changes(self, repo_root: Path, plan: common.Plan) -> common.Changes:
        common._reject_unknown(plan.decisions, _DECISION_KEYS, "plan.decisions")
        decisions = plan.decisions
        root = _require_str(decisions, "root")
        root_kind = decisions.get("rootKind", "plain")
        if root_kind not in {"helm", "plain"}:
            raise common.bad_input("plan.decisions.rootKind must be 'helm' or 'plain'")
        operator_namespace = plan.inputs.get("operatorNamespace")
        if not isinstance(operator_namespace, str) or not operator_namespace.strip():
            raise common.bad_input("plan.inputs.operatorNamespace is required and must be non-empty")
        workload_namespace = _require_str(decisions, "workloadNamespace")
        origin_service = _require_str(decisions, "originService")
        for key in ("outputFile", "valuesFile", "schemaFile"):
            if key in decisions:
                common.expect(decisions[key], str, f"plan.decisions.{key}")
        discriminators = common.expect_str_map(
            common.expect_optional(
                decisions.get("nameDiscriminators"), dict, "plan.decisions.nameDiscriminators", {}
            ),
            "plan.decisions.nameDiscriminators",
        )
        ownership = common.expect_optional(
            decisions.get("outputOwnership"), dict, "plan.decisions.outputOwnership", {}
        )
        datasources = plan.inputs.get("datasources")
        if not isinstance(datasources, list) or not datasources:
            raise common.bad_input("plan.inputs.datasources must be a non-empty list")
        _validate_datasource_schema(datasources)

        raw_claims = common.expect_optional(
            decisions.get("claims"), list, "plan.decisions.claims", []
        )
        known_ids = {ds["id"] for ds in datasources}
        for index, claim in enumerate(raw_claims):
            where = f"plan.decisions.claims[{index}]"
            if not isinstance(claim, dict):
                raise common.bad_input(f"{where} must be an object")
            common._reject_unknown(claim, _CLAIM_KEYS, where)
            if claim.get("datasourceId") not in known_ids:
                raise common.bad_input(
                    f"{where}.datasourceId {claim.get('datasourceId')!r} is not a known datasource"
                )
            if not isinstance(claim.get("role", ""), str):
                raise common.bad_input(f"{where}.role must be a string")
            for list_key in ("containers", "initContainers"):
                if list_key in claim:
                    common.expect_str_list(claim[list_key], f"{where}.{list_key}")
            for str_key in ("workloadFile", "workloadKind", "workloadName"):
                if str_key in claim:
                    common.expect(claim[str_key], str, f"{where}.{str_key}")

        blocking: list[str] = []
        _check_compatibility(datasources, blocking)
        _check_namespaces(datasources, workload_namespace, blocking)
        _check_role_coverage(datasources, raw_claims, blocking)
        _check_physical_binding(datasources, blocking)
        _check_duplicate_identities(datasources, discriminators, blocking)

        if blocking:
            raise common.unsupported("the migration cannot be applied safely", blocking)

        try:
            resources, name_bundle = build.build_resources(
                datasources,
                raw_claims,
                operator_namespace=operator_namespace,
                workload_namespace=workload_namespace,
                origin_service=origin_service,
                discriminators=discriminators,
            )
        except GenerationError as exc:
            raise common.unsupported("resource generation blocked", exc.entries) from None

        if not resources:
            raise common.unsupported(
                "the plan generates no resources",
                [
                    "no SUPPORTED datasource has a claim, so there is nothing to migrate; "
                    "the runner will not create an empty output file or touch the chart values"
                ],
            )

        changes = common.Changes()

        output_rel = _output_path(root, root_kind, decisions.get("outputFile"))
        content = _render(resources)
        _guard_collision(repo_root, output_rel, ownership, content)
        changes.set_content(output_rel, content)

        _patch_workloads(repo_root, root, datasources, raw_claims, name_bundle, changes)

        if root_kind == "helm":
            _update_values(repo_root, root, decisions, changes)

        _strip_superseded(
            repo_root,
            root,
            common.expect_optional(
                decisions.get("supersededDeclarations"),
                list,
                "plan.decisions.supersededDeclarations",
                [],
            ),
            datasources,
            workload_namespace,
            origin_service,
            changes,
        )
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
        decisions = plan.decisions
        root = decisions["root"]
        root_kind = decisions.get("rootKind", "plain")
        operator_namespace = plan.inputs["operatorNamespace"]
        workload_namespace = decisions.get("workloadNamespace", "")

        inventory_text = json.dumps({"datasources": plan.inputs["datasources"]})
        manifest_rels = {
            _output_path(root, root_kind, decisions.get("outputFile")),
            *(
                _join(root, claim["workloadFile"])
                for claim in decisions.get("claims") or []
                if isinstance(claim, dict) and claim.get("workloadFile")
            ),
        }
        changed_manifest_rels = [
            rel
            for rel in sorted(manifest_rels)
            if changes.files.get(rel) is not None and (tree_root / rel).is_file()
        ]

        if root_kind == "helm":
            results.extend(
                _validate_helm_root(
                    tree_root,
                    root,
                    decisions,
                    inventory_text,
                    operator_namespace,
                    workload_namespace,
                )
            )
        else:
            inventory_path = tree_root / "__inventory.json"
            inventory_path.write_text(inventory_text, encoding="utf-8")
            manifest_paths = [tree_root / rel for rel in changed_manifest_rels]
            for path in manifest_paths:
                text = path.read_text(encoding="utf-8")
                if "{{" in text and "}}" in text:
                    results.append(
                        common.ValidationResult(
                            "no-helm-in-plain",
                            "failed",
                            f"{path.name}: a plain-manifest output must not contain Helm expressions",
                        )
                    )
            try:
                errors = validate_generated.validate(
                    manifest_paths, inventory_path, operator_namespace
                )
            except Exception as exc:  # noqa: BLE001
                errors = [f"validator raised: {exc}"]
            results.append(
                common.ValidationResult(
                    "validate_generated", "failed" if errors else "passed", "; ".join(errors)
                )
            )
        return results


# --------------------------------------------------------------------------- #
# Helpers
# --------------------------------------------------------------------------- #


def _require_str(obj: dict[str, Any], key: str) -> str:
    value = obj.get(key)
    if not isinstance(value, str) or not value:
        raise common.bad_input(f"plan.decisions.{key} is required")
    return value


def _join(root: str, rel: str) -> str:
    return f"{root.rstrip('/')}/{rel.lstrip('/')}"


def _output_path(root: str, root_kind: str, override: Any) -> str:
    if isinstance(override, str) and override:
        return _join(root, override)
    return _join(root, DEFAULT_HELM_OUTPUT if root_kind == "helm" else DEFAULT_PLAIN_OUTPUT)


def _render(resources: list[dict[str, Any]]) -> str:
    chunks = []
    for resource in resources:
        chunks.append(
            "---\n"
            + yaml.safe_dump(
                resource, sort_keys=False, default_flow_style=False, width=1_000_000
            )
        )
    return "".join(chunks)


def _check_compatibility(datasources: list[dict[str, Any]], blocking: list[str]) -> None:
    for ds in datasources:
        if ds.get("migrationFeasibility") != "SUPPORTED":
            continue
        compat = ds.get("compatibility") or {}
        mode = compat.get("mode") if isinstance(compat, dict) else compat
        if mode not in SUPPORTED_COMPAT:
            blocking.append(
                f"datasource {ds.get('id')!r}: mounted-secret compatibility is not proven "
                f"(compatibility.mode={mode!r}); prove it before apply"
            )


def _check_namespaces(
    datasources: list[dict[str, Any]], workload_namespace: str, blocking: list[str]
) -> None:
    for ds in datasources:
        if ds.get("migrationFeasibility") != "SUPPORTED":
            continue
        classifier = ds.get("classifier") or {}
        namespace = classifier.get("namespace")
        if namespace != workload_namespace:
            blocking.append(
                f"datasource {ds.get('id')!r}: inventory classifier.namespace {namespace!r} "
                f"must equal decisions.workloadNamespace {workload_namespace!r} so the generated "
                "CR metadata.namespace and the mounted lookup agree"
            )


def _check_role_coverage(
    datasources: list[dict[str, Any]], claims: list[dict[str, Any]], blocking: list[str]
) -> None:
    claims_by_ds: dict[str, set[str]] = {}
    for claim in claims:
        claims_by_ds.setdefault(claim.get("datasourceId", ""), set()).add(
            str(claim.get("role", "")).strip()
        )
    for ds in datasources:
        if ds.get("migrationFeasibility") != "SUPPORTED":
            continue
        expected = {str(role).strip() for role in ds.get("requestedRoles", [""])}
        actual = claims_by_ds.get(ds["id"], set())
        if expected != actual:
            blocking.append(
                f"datasource {ds['id']!r}: plan claims cover roles {sorted(actual)} "
                f"but the inventory requests {sorted(expected)}"
            )


_DATASOURCE_KEYS = {
    "id",
    "type",
    "classifier",
    "requestedRoles",
    "parameters",
    "codeLocations",
    "migrationFeasibility",
    "compatibility",
}
_PARAMETER_KEYS = {"namePrefix", "settings", "physicalDatabaseId"}
_COMPATIBILITY_KEYS = {"mode", "evidence"}
_REQUIRED_CLASSIFIER_STRINGS = ("microserviceName", "scope", "namespace")


def _validate_datasource_schema(datasources: list[dict[str, Any]]) -> None:
    feasibility = {"SUPPORTED", "NOT_SUPPORTED_DYNAMIC", "BLOCKED", "AMBIGUOUS"}
    seen: set[str] = set()
    for index, ds in enumerate(datasources):
        where = f"plan.inputs.datasources[{index}]"
        if not isinstance(ds, dict):
            raise common.bad_input(f"{where} must be an object")
        common._reject_unknown(ds, _DATASOURCE_KEYS, where)
        ds_id = ds.get("id")
        if not isinstance(ds_id, str) or not ds_id:
            raise common.bad_input(f"{where}.id is required")
        if ds_id in seen:
            raise common.bad_input(f"{where}.id {ds_id!r} is duplicated")
        seen.add(ds_id)
        if not isinstance(ds.get("type"), str) or not ds["type"]:
            raise common.bad_input(f"{where}.type is required")

        classifier = ds.get("classifier")
        if not isinstance(classifier, dict) or not classifier:
            raise common.bad_input(f"{where}.classifier must be a non-empty object")
        feasibility_value = ds.get("migrationFeasibility")
        if feasibility_value not in feasibility:
            raise common.bad_input(
                f"{where}.migrationFeasibility must be one of {sorted(feasibility)}"
            )
        if feasibility_value == "SUPPORTED":
            # A generated identity must carry a complete deployment-time classifier.
            for key in _REQUIRED_CLASSIFIER_STRINGS:
                value = classifier.get(key)
                if not isinstance(value, str) or not value.strip():
                    raise common.bad_input(
                        f"{where}.classifier.{key} is required and must be a non-empty string "
                        "for a SUPPORTED datasource"
                    )
            if "customKeys" in classifier and not isinstance(classifier["customKeys"], dict):
                raise common.bad_input(f"{where}.classifier.customKeys must be an object")
            if "tenantId" in classifier and not isinstance(classifier["tenantId"], str):
                raise common.bad_input(f"{where}.classifier.tenantId must be a string")

        parameters = ds.get("parameters")
        if parameters is not None:
            if not isinstance(parameters, dict):
                raise common.bad_input(f"{where}.parameters must be an object")
            common._reject_unknown(parameters, _PARAMETER_KEYS, f"{where}.parameters")
            if "namePrefix" in parameters and not isinstance(parameters["namePrefix"], str):
                raise common.bad_input(f"{where}.parameters.namePrefix must be a string")
            if "settings" in parameters and not isinstance(parameters["settings"], dict):
                raise common.bad_input(f"{where}.parameters.settings must be an object")
            if "physicalDatabaseId" in parameters and not isinstance(
                parameters["physicalDatabaseId"], str
            ):
                raise common.bad_input(f"{where}.parameters.physicalDatabaseId must be a string")

        compatibility = ds.get("compatibility")
        if compatibility is not None:
            if not isinstance(compatibility, dict):
                raise common.bad_input(f"{where}.compatibility must be an object")
            common._reject_unknown(compatibility, _COMPATIBILITY_KEYS, f"{where}.compatibility")

        code_locations = ds.get("codeLocations")
        if code_locations is not None and (
            not isinstance(code_locations, list)
            or not all(isinstance(entry, str) for entry in code_locations)
        ):
            raise common.bad_input(f"{where}.codeLocations must be a list of strings")

        roles = ds.get("requestedRoles", [""])
        if not isinstance(roles, list) or not roles or not all(isinstance(r, str) for r in roles):
            raise common.bad_input(f"{where}.requestedRoles must be a non-empty list of strings")


def _check_duplicate_identities(
    datasources: list[dict[str, Any]],
    discriminators: dict[str, Any],
    blocking: list[str],
) -> None:
    """Two SUPPORTED datasources that resolve to the same database must agree.

    ``build_resources`` de-duplicates by ``database_key`` and keeps the first
    entry, so a second datasource with the same identity but a different
    ``classifier`` encoding, ``parameters``, or ``nameDiscriminators`` value would
    otherwise be silently ignored and the generated resource and Secret names
    would depend on inventory order.
    """

    seen: dict[str, tuple[str, tuple[str, str, str]]] = {}
    for ds in datasources:
        if ds.get("migrationFeasibility") != "SUPPORTED":
            continue
        key = build.database_key(ds["classifier"], ds["type"])
        fingerprint = (
            build.canonical(ds["classifier"]),
            build.canonical(ds.get("parameters") or {}),
            str(discriminators.get(ds["id"]) or ""),
        )
        if key in seen:
            if seen[key][1] != fingerprint:
                blocking.append(
                    f"datasources {seen[key][0]!r} and {ds['id']!r} resolve to the same database "
                    "identity but disagree on classifier encoding, parameters, or name "
                    "discriminator; merge them into one inventory entry or disambiguate the identity"
                )
        else:
            seen[key] = (ds["id"], fingerprint)


def _check_physical_binding(datasources: list[dict[str, Any]], blocking: list[str]) -> None:
    """A physical database binding has no declarative mapping; it must stay on REST.

    The skill classifies any datasource that uses ``PhysicalDatabaseId`` as
    ``BLOCKED``. Enforce that here so a plan cannot mark it ``SUPPORTED`` and have
    the runner silently drop the binding while generating a normal
    ``InternalDatabase``.
    """

    for ds in datasources:
        if ds.get("migrationFeasibility") != "SUPPORTED":
            continue
        physical = (ds.get("parameters") or {}).get("physicalDatabaseId")
        if isinstance(physical, str) and physical.strip():
            blocking.append(
                f"datasource {ds.get('id')!r}: parameters.physicalDatabaseId {physical!r} has no "
                "declarative mapping; classify it BLOCKED and keep it on the REST path"
            )


def _patch_workloads(
    repo_root: Path,
    root: str,
    datasources: list[dict[str, Any]],
    claims: list[dict[str, Any]],
    name_bundle: dict[str, dict[str, str]],
    changes: common.Changes,
) -> None:
    by_id = {ds["id"]: ds for ds in datasources}
    # Group by (file, workload identity) so two workloads in one file never
    # receive each other's mounts, and patch each file exactly once.
    per_workload: dict[tuple[str, str, str], dict[str, Any]] = {}
    for claim in claims:
        ds = by_id[claim["datasourceId"]]
        if ds.get("migrationFeasibility") != "SUPPORTED":
            continue
        key = claim_key(ds["classifier"], ds["type"], str(claim.get("role", "")))
        bundle = name_bundle[key]
        rel = _require_str(claim, "workloadFile")
        kind = _require_str(claim, "workloadKind")
        name = _require_str(claim, "workloadName")
        entry = per_workload.setdefault((rel, kind, name), {"kind": kind, "name": name, "mounts": []})
        entry["mounts"].append(
            {
                "volume": bundle["volume"],
                "secret": bundle["secret"],
                "mountPath": bundle["mountPath"],
                "containers": list(claim.get("containers") or []),
                "initContainers": list(claim.get("initContainers") or []),
            }
        )

    by_file: dict[str, list[dict[str, Any]]] = {}
    for (rel, _, _), entry in per_workload.items():
        by_file.setdefault(rel, []).append(entry)

    for rel, targets in by_file.items():
        full_rel = _join(root, rel)
        target = common.resolve_within(repo_root, full_rel, what="workload file")
        if not target.is_file():
            raise common.unsupported("workload file missing", [f"{full_rel}: file not found"])
        try:
            patched = patch_workloads(
                target.read_text(encoding="utf-8"),
                filename=full_rel,
                targets=targets,
            )
        except WorkloadError as exc:
            raise common.unsupported("workload adapter blocked", exc.entries) from None
        changes.set_content(full_rel, patched)


def _generated_databases(datasources: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    """Map each generated database identity key to the datasource that produced it."""

    result: dict[str, dict[str, Any]] = {}
    for ds in sorted(
        (ds for ds in datasources if ds.get("migrationFeasibility") == "SUPPORTED"),
        key=lambda d: build.database_key(d["classifier"], d["type"]),
    ):
        result.setdefault(build.database_key(ds["classifier"], ds["type"]), ds)
    return result


# Fields a legacy DatabaseDeclaration may carry that the mounted-secret runner
# reproduces on the generated InternalDatabase (from ``parameters``).
_DECLARATION_IDENTITY_FIELDS = {"classifierConfig", "type"}
_DECLARATION_CREATION_FIELDS = {"settings", "namePrefix"}
# Fields whose behaviour the mounted-secret generator does NOT carry across.
_DECLARATION_UNSUPPORTED_FIELDS = {"versioningConfig", "initialInstantiation"}
# CR-envelope keys that are noise when a whole document is one declaration.
_DECLARATION_WRAPPER_FIELDS = {"apiVersion", "kind", "subKind", "metadata"}
# Legacy placeholders for the owning service, resolved to decisions.originService.
_SERVICE_PLACEHOLDERS = {
    "{{$SERVICE_NAME}}",
    "{{ $SERVICE_NAME }}",
    "${SERVICE_NAME}",
    "{{ .Values.SERVICE_NAME }}",
    "{{.Values.SERVICE_NAME}}",
}


def _normalize_legacy_classifier(
    classifier: dict[str, Any],
    *,
    doc_namespace: Any,
    workload_namespace: str,
    origin_service: str,
) -> dict[str, Any]:
    """Bring a raw legacy classifier to the effective wire form the inventory uses.

    Discovery records ``inputs.datasources[].classifier`` already resolved: the
    workload namespace is filled in and the owning-service placeholder is
    replaced. Apply the same two rewrites to a classifier read straight from a
    legacy file before comparing identities, so a declaration that merely omits
    the namespace or still uses ``{{$SERVICE_NAME}}`` is not seen as a different
    database. When the classifier omits the namespace, the declaration document's
    own ``metadata.namespace`` wins over the workload fallback, so a declaration
    that belongs to a different namespace does not falsely match.
    """

    out = dict(classifier)
    service = out.get("microserviceName")
    if isinstance(service, str) and service.strip() in _SERVICE_PLACEHOLDERS:
        out["microserviceName"] = origin_service
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
    origin_service: str,
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
        origin_service=origin_service,
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


def _strip_superseded(
    repo_root: Path,
    root: str,
    entries: list[Any],
    datasources: list[dict[str, Any]],
    workload_namespace: str,
    origin_service: str,
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

    generated = _generated_databases(datasources)
    for rel in entries:
        full_rel = _join(root, rel)
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
                    origin_service=origin_service,
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


def _update_values(
    repo_root: Path, root: str, decisions: dict[str, Any], changes: common.Changes
) -> None:
    values_rel = _join(root, decisions.get("valuesFile", "values.yaml"))
    schema_rel = _join(root, decisions.get("schemaFile", "values.schema.json"))

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
    try:
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
    except ValueError as exc:
        raise common.bad_input(f"{schema_rel}: invalid JSON: {exc}") from None
    changed = False
    properties = schema.setdefault("properties", {})
    if properties.get(OPERATOR_NAMESPACE_VALUE) != {"type": "string", "minLength": 1}:
        properties[OPERATOR_NAMESPACE_VALUE] = {"type": "string", "minLength": 1}
        changed = True
    required = schema.setdefault("required", [])
    if OPERATOR_NAMESPACE_VALUE not in required:
        required.append(OPERATOR_NAMESPACE_VALUE)
        changed = True
    if changed:
        changes.set_content(schema_rel, json.dumps(schema, indent=2) + "\n")


_VALUE_REF = re.compile(r"\{\{-?\s*\.Values\.([A-Za-z0-9_]+)\s*-?\}\}")


def _template_value_keys(*texts: str) -> set[str]:
    keys: set[str] = set()
    for text in texts:
        keys.update(_VALUE_REF.findall(text))
    return keys


def _resolve_templates(text: str, values: dict[str, str]) -> str:
    return _VALUE_REF.sub(lambda m: values.get(m.group(1), m.group(0)), text)


def _validate_helm_root(
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
    schema_issue = _check_schema_requires_operator_namespace(
        tree_root / _join(root, decisions.get("schemaFile", "values.schema.json"))
    )
    results.append(
        common.ValidationResult("values-schema", "failed" if schema_issue else "passed", schema_issue or "")
    )
    values_issue = _check_values_has_operator_namespace(
        tree_root / _join(root, decisions.get("valuesFile", "values.yaml"))
    )
    results.append(
        common.ValidationResult("values-yaml", "failed" if values_issue else "passed", values_issue or "")
    )

    chart_dir = tree_root / root if root else tree_root
    helm = shutil.which("helm")
    if helm is None:
        results.append(
            common.ValidationResult(
                "helm-render",
                "failed",
                "helm is not on PATH; a helm root cannot be certified without rendering it. "
                "Install helm (or run where it is available) and re-run",
            )
        )
        return results
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
    values = {key: f"pilot-{key.lower().replace('_', '-')}" for key in value_keys}
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
    resolved_operator_ns = _resolve_templates(operator_namespace, values)
    try:
        errors = validate_generated.validate(
            [rendered_path],
            rendered_inventory,
            resolved_operator_ns,
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


def _check_values_has_operator_namespace(values_path: Path) -> str | None:
    if not values_path.is_file():
        return f"{values_path.name}: missing after generation"
    try:
        loaded = yaml.safe_load(values_path.read_text(encoding="utf-8"))
    except yaml.YAMLError as exc:
        return f"{values_path.name}: invalid YAML: {exc}"
    if not isinstance(loaded, dict) or OPERATOR_NAMESPACE_VALUE not in loaded:
        return f"{OPERATOR_NAMESPACE_VALUE} is not a top-level key in {values_path.name}"
    return None


def _check_schema_requires_operator_namespace(schema_path: Path) -> str | None:
    if not schema_path.is_file():
        return f"{schema_path.name}: missing after generation"
    try:
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
    except ValueError as exc:
        return f"{schema_path.name}: invalid JSON: {exc}"
    prop = (schema.get("properties") or {}).get(OPERATOR_NAMESPACE_VALUE)
    if prop != {"type": "string", "minLength": 1}:
        return f"{OPERATOR_NAMESPACE_VALUE} property is not a non-empty string"
    if OPERATOR_NAMESPACE_VALUE not in (schema.get("required") or []):
        return f"{OPERATOR_NAMESPACE_VALUE} is not in the schema required list"
    return None


def _guard_collision(
    repo_root: Path, output_rel: str, ownership: dict[str, Any], rendered: str
) -> None:
    target = common.resolve_within(repo_root, output_rel, what="output path")
    if not target.exists():
        return
    if common.sha256_file(target) == common.sha256_bytes(rendered.encode("utf-8")):
        return
    declared = ownership.get(output_rel)
    if not isinstance(declared, dict) or "sha256" not in declared:
        raise common.unsupported(
            "output file collision",
            [f"{output_rel}: file exists and is not declared in decisions.outputOwnership"],
        )
    if common.sha256_file(target) != declared["sha256"]:
        raise common.MigrationError(
            common.EXIT_PRECONDITION,
            "owned output file changed since discovery",
            [f"{output_rel}: sha256 does not match the declared ownership hash"],
        )


if __name__ == "__main__":
    raise SystemExit(common.run(MountedSecretEngine()))
