#!/usr/bin/env python3
"""Stage a safe upgrade from NamespaceBinding to per-CR operatorNamespace."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any


NAMESPACE_BINDING_CRD = "namespacebindings.dbaas.netcracker.com"
NAMESPACE_BINDING_FINALIZER = "platform.dbaas.netcracker.com/binding-protection"
WORKLOAD_RESOURCES = (
    "externaldatabases",
    "internaldatabases",
    "databasesecretclaims",
    "databaseaccesspolicies",
    "microservicebalancingrules",
    "namespacebalancingrules",
)
PERMANENT_RESOURCE = "permanentbalancingrules"
CRD_FILES = tuple(
    f"dbaas.netcracker.com_{resource}.yaml"
    for resource in (*WORKLOAD_RESOURCES, PERMANENT_RESOURCE)
)


@dataclass(frozen=True)
class AssignmentPatch:
    resource: str
    namespace: str
    name: str
    operator_namespace: str


class Kubectl:
    def __init__(self, context: str) -> None:
        self.prefix = ["kubectl", "--context", context]

    def run(self, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
        result = subprocess.run(
            [*self.prefix, *args],
            capture_output=True,
            check=False,
            text=True,
        )
        if check and result.returncode != 0:
            detail = result.stderr.strip() or result.stdout.strip()
            raise RuntimeError(f"kubectl {' '.join(args)} failed: {detail}")
        return result

    def get_json(self, resource: str, *args: str) -> dict[str, Any]:
        result = self.run("get", resource, *args, "-o", "json")
        return json.loads(result.stdout)


def item_identity(item: dict[str, Any]) -> tuple[str, str]:
    metadata = item.get("metadata") or {}
    return str(metadata.get("namespace") or "default"), str(metadata.get("name") or "")


def build_plan(
    bindings: list[dict[str, Any]],
    resources: dict[str, list[dict[str, Any]]],
) -> tuple[list[AssignmentPatch], list[str]]:
    namespace_assignments: dict[str, str] = {}
    errors: list[str] = []
    patches: list[AssignmentPatch] = []

    for binding in bindings:
        namespace, name = item_identity(binding)
        operator_namespace = str((binding.get("spec") or {}).get("operatorNamespace") or "")
        if not operator_namespace:
            errors.append(f"NamespaceBinding {namespace}/{name} has no spec.operatorNamespace")
            continue
        previous = namespace_assignments.get(namespace)
        if previous and previous != operator_namespace:
            errors.append(
                f"namespace {namespace} has conflicting NamespaceBinding assignments: "
                f"{previous} and {operator_namespace}"
            )
            continue
        namespace_assignments[namespace] = operator_namespace

    for resource in WORKLOAD_RESOURCES:
        for item in resources.get(resource, []):
            namespace, name = item_identity(item)
            current = str((item.get("spec") or {}).get("operatorNamespace") or "")
            target = namespace_assignments.get(namespace, "")
            if current:
                if target and current != target:
                    errors.append(
                        f"{resource} {namespace}/{name} has operatorNamespace={current}, "
                        f"but its NamespaceBinding assigns {target}"
                    )
                continue
            if not target:
                errors.append(
                    f"{resource} {namespace}/{name} has no operatorNamespace and no NamespaceBinding assignment"
                )
                continue
            patches.append(AssignmentPatch(resource, namespace, name, target))

    for item in resources.get(PERMANENT_RESOURCE, []):
        namespace, name = item_identity(item)
        current = str((item.get("spec") or {}).get("operatorNamespace") or "")
        if current and current != namespace:
            errors.append(
                f"{PERMANENT_RESOURCE} {namespace}/{name} has operatorNamespace={current}; "
                "PermanentBalancingRule requires operatorNamespace to equal metadata.namespace"
            )
        elif not current:
            patches.append(AssignmentPatch(PERMANENT_RESOURCE, namespace, name, namespace))

    return patches, errors


def list_bindings(kubectl: Kubectl) -> list[dict[str, Any]]:
    crd = kubectl.run(
        "get", "crd", NAMESPACE_BINDING_CRD, "--ignore-not-found", "-o", "name"
    )
    if not crd.stdout.strip():
        return []
    return list(kubectl.get_json("namespacebindings", "--all-namespaces").get("items") or [])


def list_managed_resources(kubectl: Kubectl) -> dict[str, list[dict[str, Any]]]:
    return {
        resource: list(kubectl.get_json(resource, "--all-namespaces").get("items") or [])
        for resource in (*WORKLOAD_RESOURCES, PERMANENT_RESOURCE)
    }


def apply_compatible_crds(kubectl: Kubectl, crd_dir: Path) -> None:
    for filename in CRD_FILES:
        path = crd_dir / filename
        if not path.is_file():
            raise RuntimeError(f"required CRD manifest not found: {path}")
        print(f"Applying migration-compatible CRD {path.name}")
        kubectl.run("apply", "-f", str(path))


def patch_assignments(kubectl: Kubectl, patches: list[AssignmentPatch]) -> None:
    for patch in patches:
        print(
            f"Assigning {patch.resource} {patch.namespace}/{patch.name} "
            f"to operator namespace {patch.operator_namespace}"
        )
        kubectl.run(
            "patch",
            patch.resource,
            patch.name,
            "--namespace",
            patch.namespace,
            "--type=merge",
            "--patch",
            json.dumps({"spec": {"operatorNamespace": patch.operator_namespace}}),
        )


def verify_assignments(kubectl: Kubectl) -> None:
    _, errors = build_plan([], list_managed_resources(kubectl))
    if errors:
        raise RuntimeError("assignment verification failed:\n- " + "\n- ".join(errors))


def release_binding(kubectl: Kubectl, binding: dict[str, Any]) -> None:
    namespace, name = item_identity(binding)
    print(f"Deleting NamespaceBinding {namespace}/{name}")
    kubectl.run(
        "delete",
        "namespacebinding",
        name,
        "--namespace",
        namespace,
        "--wait=false",
        "--ignore-not-found=true",
    )

    current_result = kubectl.run(
        "get",
        "namespacebinding",
        name,
        "--namespace",
        namespace,
        "--ignore-not-found",
        "-o",
        "json",
    )
    if current_result.stdout.strip():
        current = json.loads(current_result.stdout)
        metadata = current.get("metadata") or {}
        finalizers = list(metadata.get("finalizers") or [])
        if NAMESPACE_BINDING_FINALIZER in finalizers:
            remaining = [value for value in finalizers if value != NAMESPACE_BINDING_FINALIZER]
            operation = "replace" if remaining else "remove"
            patch: list[dict[str, Any]] = [
                {"op": "test", "path": "/metadata/resourceVersion", "value": metadata["resourceVersion"]},
                {"op": operation, "path": "/metadata/finalizers"},
            ]
            if remaining:
                patch[-1]["value"] = remaining
            kubectl.run(
                "patch",
                "namespacebinding",
                name,
                "--namespace",
                namespace,
                "--type=json",
                "--patch",
                json.dumps(patch),
            )

    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        result = kubectl.run(
            "get",
            "namespacebinding",
            name,
            "--namespace",
            namespace,
            "--ignore-not-found",
            "-o",
            "json",
        )
        if not result.stdout.strip():
            return
        time.sleep(1)
    remaining = json.loads(result.stdout).get("metadata", {}).get("finalizers") or []
    raise RuntimeError(
        f"NamespaceBinding {namespace}/{name} is still terminating with finalizers {remaining}; "
        "do not upgrade until their owning controllers release them"
    )


def parse_args() -> argparse.Namespace:
    repository_root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(
        description="Safely migrate live NamespaceBinding assignments before upgrading dbaas-operator"
    )
    parser.add_argument("--context", required=True, help="Exact kubeconfig context to migrate")
    parser.add_argument(
        "--crd-dir",
        type=Path,
        default=repository_root / "config" / "crd" / "bases",
        help="Directory containing the new generated CRD manifests",
    )
    parser.add_argument(
        "--execute",
        action="store_true",
        help="Apply CRDs, patch live CRs, and delete NamespaceBindings; without this flag only print the plan",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    kubectl = Kubectl(args.context)
    kubectl.run("cluster-info")

    bindings = list_bindings(kubectl)
    resources = list_managed_resources(kubectl)
    patches, errors = build_plan(bindings, resources)
    if errors:
        raise RuntimeError("migration preflight failed:\n- " + "\n- ".join(errors))

    print(f"Context: {args.context}")
    print(f"NamespaceBindings to retire: {len(bindings)}")
    print(f"CRs requiring operatorNamespace: {len(patches)}")
    for patch in patches:
        print(
            f"  {patch.resource} {patch.namespace}/{patch.name} -> {patch.operator_namespace}"
        )

    if not args.execute:
        print("Dry run only; rerun with --execute after reviewing this plan")
        return 0

    apply_compatible_crds(kubectl, args.crd_dir.resolve())
    patch_assignments(kubectl, patches)
    verify_assignments(kubectl)
    for binding in bindings:
        release_binding(kubectl, binding)

    print("Migration complete; all CRs are assigned and NamespaceBindings are gone")
    print("Proceed immediately with the dbaas-operator Helm upgrade")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1) from None
