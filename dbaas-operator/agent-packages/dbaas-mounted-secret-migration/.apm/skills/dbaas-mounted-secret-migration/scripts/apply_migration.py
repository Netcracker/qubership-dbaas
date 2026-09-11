#!/usr/bin/env python3
"""Deterministic runner for the DBaaS mounted-secret migration.

The skill inventories the service and resolves every ambiguous value into a JSON
plan; this script is the only writer of migration files. It generates the
canonical ``InternalDatabase`` / ``DatabaseSecretClaim`` file (writing
``inputs.operatorNamespace`` verbatim into every ``spec.operatorNamespace``),
mounts every generated Secret into the plan-selected containers, registers the
``DBAAS_OPERATOR_NAMESPACE`` chart value when -- and only when -- the plan
templates the operator namespace from it, removes the superseded legacy
declarations, and validates the result with the bundled inventory validator in a
temporary tree before touching the working copy.

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
        "helmValues": {"image.tag": "1.2.3"},
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
from pathlib import Path
from typing import Any

import _chart_support as chart
import _legacy_cleanup as cleanup
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
    "helmValues",
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
        return [common.normalize_root(_require_str(plan.decisions, "root"))]

    # ----------------------------------------------------------------- #

    def build_changes(self, repo_root: Path, plan: common.Plan) -> common.Changes:
        # plan.decisions keys are already checked against _DECISION_KEYS by
        # common.load_plan(decision_keys=...) before build_changes runs.
        decisions = plan.decisions
        root = common.normalize_root(_require_str(decisions, "root"))
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
        # Extra `helm template --set key=value` pairs for chart values the runner
        # does not synthesize itself (a schema-required value with no default, an
        # image tag, ...). Applied after the synthesized pilot values, so an
        # explicit entry wins on conflict.
        helm_values = common.expect_str_map(
            common.expect_optional(
                decisions.get("helmValues"), dict, "plan.decisions.helmValues", {}
            ),
            "plan.decisions.helmValues",
        )
        if helm_values and decisions.get("rootKind") != "helm":
            raise common.bad_input(
                "plan.decisions.helmValues is only meaningful for a helm root"
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
        _check_service_identity(datasources, origin_service, blocking)
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
        common.guard_output_collision(repo_root, output_rel, ownership, content)
        changes.set_content(output_rel, content)

        _patch_workloads(repo_root, root, datasources, raw_claims, name_bundle, changes)

        if root_kind == "helm":
            chart.update_values(repo_root, root, decisions, operator_namespace, changes)

        cleanup.strip_superseded(
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
        root = common.normalize_root(decisions["root"])
        root_kind = decisions.get("rootKind", "plain")
        operator_namespace = plan.inputs["operatorNamespace"]
        workload_namespace = decisions.get("workloadNamespace", "")

        inventory_text = json.dumps({"datasources": plan.inputs["datasources"]})

        if root_kind == "helm":
            results.extend(
                chart.validate_helm_root(
                    tree_root,
                    root,
                    decisions,
                    inventory_text,
                    operator_namespace,
                    workload_namespace,
                )
            )
        else:
            manifest_rels = {
                _output_path(root, root_kind, decisions.get("outputFile")),
                *(
                    common.join_rel(root, claim["workloadFile"])
                    for claim in decisions.get("claims") or []
                    if isinstance(claim, dict) and claim.get("workloadFile")
                ),
            }
            changed_manifest_rels = [
                rel
                for rel in sorted(manifest_rels)
                if changes.files.get(rel) is not None and (tree_root / rel).is_file()
            ]
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
                    manifest_paths, inventory_path, operator_namespace,
                    default_namespace=workload_namespace,
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


def _output_path(root: str, root_kind: str, override: Any) -> str:
    if isinstance(override, str) and override:
        return common.join_rel(root, override)
    return common.join_rel(root, DEFAULT_HELM_OUTPUT if root_kind == "helm" else DEFAULT_PLAIN_OUTPUT)


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


def _check_service_identity(
    datasources: list[dict[str, Any]], origin_service: str, blocking: list[str]
) -> None:
    """The classifier's service identity must match ``decisions.originService``.

    This is the service-identity counterpart of ``_check_namespaces``. If the
    legacy declaration templated ``microserviceName`` from ``{{ .Values.SERVICE_NAME }}``
    but discovery recorded a literal, two installs of the same chart with
    different ``SERVICE_NAME`` values would generate the same classifier and share
    one database. Requiring the inventory identity to equal ``originService`` --
    which is what the claim's ``app.kubernetes.io/name`` label carries -- keeps a
    templated deployment templated end to end.
    """

    for ds in datasources:
        if ds.get("migrationFeasibility") != "SUPPORTED":
            continue
        classifier = ds.get("classifier") or {}
        service = classifier.get("microserviceName")
        if service != origin_service:
            blocking.append(
                f"datasource {ds.get('id')!r}: inventory classifier.microserviceName {service!r} "
                f"must equal decisions.originService {origin_service!r} so the generated identity "
                "matches the claim's app.kubernetes.io/name and a templated service name stays "
                "templated"
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
            if "extraKeys" in classifier:
                if not isinstance(classifier["extraKeys"], dict):
                    # _resource_build.py's _wire_classifier/cr_classifier call
                    # .items() on this unconditionally; a non-empty list, string,
                    # or boolean here would otherwise raise AttributeError deep
                    # inside name/identity generation instead of failing here as
                    # the invalid plan input it is.
                    raise common.bad_input(f"{where}.classifier.extraKeys must be an object")
                reserved = sorted(build.RESERVED_CLASSIFIER_KEYS & set(classifier["extraKeys"]))
                if reserved:
                    # cr_classifier's own extraKeys merge does not filter
                    # reserved keys back out -- a reserved key with no
                    # top-level shadow would otherwise reach the generated CR
                    # nested under spec.classifier.extraKeys, where
                    # validate_generated only catches it as a generated-output
                    # defect (exit 5) instead of the invalid plan input it is.
                    raise common.bad_input(
                        f"{where}.classifier.extraKeys must not repeat reserved keys: "
                        f"{', '.join(reserved)}"
                    )

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
        full_rel = common.join_rel(root, rel)
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


if __name__ == "__main__":
    raise SystemExit(common.run(MountedSecretEngine()))
