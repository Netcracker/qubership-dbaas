#!/usr/bin/env python3
"""Shared runner contract for the script-driven DBaaS migration skills.

This module is vendored byte-for-byte into every APM migration package so that
each package stays independently installable. It owns the parts of the runner
that must not drift between packages:

- the ``apply_migration.py`` command line (``--repo-root``/``--plan``/``--check``
  vs ``--apply``/``--report``);
- the common plan envelope and its strict validation;
- repository-relative path safety;
- SHA-256 source preconditions;
- the machine-readable result envelope and fixed exit codes;
- the "build in memory, validate in a temporary tree, then commit atomically"
  write transaction with rollback.

A package supplies an :class:`Engine` that turns a validated plan into a
:class:`Changes` set and validates a materialized tree. Everything else is here.

Repository-level contract tests assert that the copies in each package are
identical and that both runners honour the exit and result contract.
"""

from __future__ import annotations

import argparse
import dataclasses
import hashlib
import importlib.util
import json
import os
import re
import shutil
import sys
import tempfile
from pathlib import Path, PurePosixPath
from typing import Any, Callable, Protocol

# Fixed exit behaviour. The skill maps each code to a recovery action, so these
# values are part of the contract and must never be renumbered.
EXIT_OK = 0
EXIT_BAD_INPUT = 2
EXIT_PRECONDITION = 3
EXIT_UNSUPPORTED = 4
EXIT_VALIDATION = 5
EXIT_TRANSACTION = 6

SCHEMA_VERSION = 1

_ENVELOPE_KEYS = {
    "schemaVersion",
    "migrationKind",
    "repository",
    "inputs",
    "decisions",
    "targets",
}
_REPOSITORY_KEYS = {"preconditions"}
_PRECONDITION_KEYS = {"path", "sha256", "absent"}


class MigrationError(Exception):
    """A blocking condition with a fixed exit code and reportable entries."""

    def __init__(self, exit_code: int, message: str, entries: list[str] | None = None) -> None:
        super().__init__(message)
        self.exit_code = exit_code
        self.entries = list(entries or [])


def bad_input(message: str, entries: list[str] | None = None) -> MigrationError:
    return MigrationError(EXIT_BAD_INPUT, message, entries)


def unsupported(message: str, entries: list[str] | None = None) -> MigrationError:
    return MigrationError(EXIT_UNSUPPORTED, message, entries)


# --------------------------------------------------------------------------- #
# Plan envelope
# --------------------------------------------------------------------------- #


@dataclasses.dataclass(frozen=True)
class Precondition:
    path: str
    sha256: str | None
    absent: bool


@dataclasses.dataclass
class Plan:
    schema_version: int
    migration_kind: str
    preconditions: list[Precondition]
    inputs: dict[str, Any]
    decisions: dict[str, Any]
    targets: list[dict[str, Any]]
    raw: dict[str, Any]


def _reject_unknown(obj: dict[str, Any], allowed: set[str], where: str) -> None:
    unknown = sorted(set(obj) - allowed)
    if unknown:
        raise bad_input(f"{where} has unknown properties: {', '.join(unknown)}")


# --------------------------------------------------------------------------- #
# Typed plan accessors
#
# The envelope rejects unknown keys, but nested values must be type-checked
# before use so a JSON string like "false" cannot be coerced to a boolean and a
# malformed map cannot reach a ``.get`` call as an ``internal error``.
# --------------------------------------------------------------------------- #

_TYPE_NAMES = {bool: "a boolean", int: "an integer", str: "a string", list: "a list", dict: "an object"}


def _typename(types: Any) -> str:
    if isinstance(types, tuple):
        return " or ".join(_TYPE_NAMES.get(t, getattr(t, "__name__", str(t))) for t in types)
    return _TYPE_NAMES.get(types, getattr(types, "__name__", str(types)))


def expect(value: Any, types: Any, where: str) -> Any:
    """Return ``value`` when it is one of ``types``, else raise a bad-input error.

    ``bool`` is a subclass of ``int``; an ``int`` requirement rejects a bool.
    """

    if types is int and isinstance(value, bool):
        raise bad_input(f"{where} must be an integer, not a boolean")
    if not isinstance(value, types):
        raise bad_input(f"{where} must be {_typename(types)}, got {type(value).__name__}")
    return value


def expect_optional(value: Any, types: Any, where: str, default: Any) -> Any:
    """``default`` only when ``value`` is missing (``None``); a wrong type -- even
    a falsy one like ``[]`` for an object -- is rejected, never coerced."""

    if value is None:
        return default
    return expect(value, types, where)


def expect_bool(value: Any, where: str, *, default: bool | None = None) -> bool:
    if value is None and default is not None:
        return default
    if not isinstance(value, bool):
        raise bad_input(f"{where} must be a boolean (true or false), got {value!r}")
    return value


def expect_str_map(value: Any, where: str) -> dict[str, str]:
    expect(value, dict, where)
    for key, item in value.items():
        if not isinstance(key, str) or not isinstance(item, str):
            raise bad_input(f"{where} must map strings to strings")
    return value


def expect_str_list(value: Any, where: str, *, unique: bool = False) -> list[str]:
    expect(value, list, where)
    if not all(isinstance(item, str) for item in value):
        raise bad_input(f"{where} must be a list of strings")
    if unique and len(set(value)) != len(value):
        raise bad_input(f"{where} must not contain duplicate entries")
    return list(value)


def load_plan(
    plan_path: Path,
    expected_migration_kind: str,
    *,
    input_keys: set[str] | None = None,
    decision_keys: set[str] | None = None,
) -> Plan:
    try:
        text = plan_path.read_text(encoding="utf-8")
    except OSError as exc:
        raise bad_input(f"cannot read plan: {exc}") from None
    try:
        raw = json.loads(text)
    except ValueError as exc:
        raise bad_input(f"plan is not valid JSON: {exc}") from None
    if not isinstance(raw, dict):
        raise bad_input("plan must be a JSON object")

    _reject_unknown(raw, _ENVELOPE_KEYS, "plan")

    if raw.get("schemaVersion") != SCHEMA_VERSION:
        raise bad_input(
            f"plan schemaVersion must be {SCHEMA_VERSION}, got {raw.get('schemaVersion')!r}"
        )
    migration_kind = raw.get("migrationKind")
    if migration_kind != expected_migration_kind:
        raise bad_input(
            f"plan migrationKind must be {expected_migration_kind!r}, got {migration_kind!r}"
        )

    repository = expect_optional(raw.get("repository"), dict, "plan.repository", {})
    _reject_unknown(repository, _REPOSITORY_KEYS, "plan.repository")

    preconditions: list[Precondition] = []
    raw_preconditions = expect_optional(
        repository.get("preconditions"), list, "plan.repository.preconditions", []
    )
    for index, entry in enumerate(raw_preconditions):
        if not isinstance(entry, dict):
            raise bad_input(f"plan.repository.preconditions[{index}] must be an object")
        _reject_unknown(entry, _PRECONDITION_KEYS, f"plan.repository.preconditions[{index}]")
        path = entry.get("path")
        if not isinstance(path, str) or not path:
            raise bad_input(f"plan.repository.preconditions[{index}].path is required")
        absent = expect_bool(
            entry.get("absent"), f"plan.repository.preconditions[{index}].absent", default=False
        )
        sha256 = entry.get("sha256")
        if absent:
            if sha256 is not None:
                raise bad_input(
                    f"plan.repository.preconditions[{index}] sets absent and sha256 together"
                )
        else:
            if not isinstance(sha256, str) or len(sha256) != 64:
                raise bad_input(
                    f"plan.repository.preconditions[{index}].sha256 must be a 64-character hex digest"
                )
        preconditions.append(Precondition(path=path, sha256=sha256, absent=absent))

    inputs = expect_optional(raw.get("inputs"), dict, "plan.inputs", {})
    decisions = expect_optional(raw.get("decisions"), dict, "plan.decisions", {})
    targets = expect_optional(raw.get("targets"), list, "plan.targets", [])
    if input_keys is not None:
        _reject_unknown(inputs, input_keys, "plan.inputs")
    if decision_keys is not None:
        _reject_unknown(decisions, decision_keys, "plan.decisions")
    seen_targets: set[str] = set()
    for index, target in enumerate(targets):
        if not isinstance(target, dict) or not isinstance(target.get("path"), str) or not target["path"]:
            raise bad_input(f"plan.targets[{index}] must be an object with a non-empty string path")
        _reject_unknown(target, {"path", "ownership"}, f"plan.targets[{index}]")
        if target["path"] in seen_targets:
            raise bad_input(f"plan.targets lists {target['path']!r} more than once")
        seen_targets.add(target["path"])

    return Plan(
        schema_version=SCHEMA_VERSION,
        migration_kind=migration_kind,
        preconditions=preconditions,
        inputs=inputs,
        decisions=decisions,
        targets=targets,
        raw=raw,
    )


# --------------------------------------------------------------------------- #
# Path safety
# --------------------------------------------------------------------------- #


def resolve_within(repo_root: Path, relative: str, *, what: str = "path") -> Path:
    """Resolve ``relative`` under ``repo_root`` or raise.

    Rejects absolute paths, parent traversal, and symlink escapes. The returned
    path is absolute and guaranteed to be ``repo_root`` or a descendant.
    """

    if not isinstance(relative, str) or not relative:
        raise bad_input(f"{what} must be a non-empty string")
    pure = PurePosixPath(relative.replace("\\", "/"))
    if pure.is_absolute() or relative.startswith("/") or (len(relative) > 1 and relative[1] == ":"):
        raise bad_input(f"{what} must be repository-relative, got {relative!r}")
    if any(part == ".." for part in pure.parts):
        raise bad_input(f"{what} must not contain '..', got {relative!r}")

    root = repo_root.resolve()
    candidate = (root / pure).resolve()
    try:
        candidate.relative_to(root)
    except ValueError:
        raise bad_input(f"{what} escapes the repository root: {relative!r}") from None

    # Reject a symlink anywhere along the in-repo portion of the path.
    walk = root
    for part in pure.parts:
        walk = walk / part
        if walk.is_symlink():
            raise bad_input(f"{what} traverses a symlink at {walk.relative_to(root).as_posix()!r}")
    return candidate


def rel_posix(repo_root: Path, path: Path) -> str:
    return path.resolve().relative_to(repo_root.resolve()).as_posix()


# --------------------------------------------------------------------------- #
# Deterministic DNS-1123 label helper (shared by both packages)
# --------------------------------------------------------------------------- #

_DNS_LABEL_RE = re.compile(r"[a-z0-9](?:[-a-z0-9]*[a-z0-9])?")
DNS_LABEL_MAX = 63


def _slug(value: str) -> str:
    slug = re.sub(r"-+", "-", re.sub(r"[^a-z0-9-]+", "-", str(value).lower())).strip("-")
    return slug or "dbaas"


def dns_label(*parts: Any, keep_tail: str = "", limit: int = DNS_LABEL_MAX) -> str:
    """Build a deterministic RFC-1123 label from ``parts``.

    ``keep_tail`` is a short suffix (a role or resource-kind token) that must stay
    readable: when the full name would exceed ``limit`` the identity portion is
    truncated and an 8-hex-char hash of it is inserted before ``keep_tail``, so
    two long identities -- or the same identity with different tails -- never
    collapse to one name.
    """

    identity = _slug("-".join(str(p) for p in parts if p is not None and str(p) != ""))
    tail = _slug(keep_tail) if keep_tail else ""
    full = f"{identity}-{tail}" if tail else identity
    if len(full) <= limit:
        return full.strip("-")
    digest = hashlib.sha256(identity.encode("utf-8")).hexdigest()[:8]
    reserved = len(digest) + 1 + (len(tail) + 1 if tail else 0)
    head = identity[: max(limit - reserved, 1)].strip("-")
    result = f"{head}-{digest}" + (f"-{tail}" if tail else "")
    return result.strip("-")[:limit]


def is_dns_label(value: str) -> bool:
    return (
        isinstance(value, str)
        and 1 <= len(value) <= DNS_LABEL_MAX
        and _DNS_LABEL_RE.fullmatch(value) is not None
    )


# --------------------------------------------------------------------------- #
# Preconditions
# --------------------------------------------------------------------------- #


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def check_preconditions(repo_root: Path, plan: Plan) -> None:
    failures: list[str] = []
    for precondition in plan.preconditions:
        target = resolve_within(repo_root, precondition.path, what="precondition path")
        if precondition.absent:
            if target.exists():
                failures.append(f"{precondition.path}: expected absent but the file exists")
            continue
        if not target.is_file():
            failures.append(f"{precondition.path}: expected file is missing")
            continue
        actual = sha256_file(target)
        if actual != precondition.sha256:
            failures.append(
                f"{precondition.path}: sha256 changed since discovery "
                f"(expected {precondition.sha256}, found {actual})"
            )
    if failures:
        raise MigrationError(
            EXIT_PRECONDITION,
            "source preconditions changed after discovery",
            failures,
        )


# --------------------------------------------------------------------------- #
# Change set
# --------------------------------------------------------------------------- #


@dataclasses.dataclass
class Changes:
    """Candidate repository contents keyed by repository-relative POSIX path.

    A value of ``None`` marks a deletion. Content is compared against the working
    tree to classify each entry as created / modified / deleted / unchanged.
    """

    files: dict[str, str | None] = dataclasses.field(default_factory=dict)
    warnings: list[str] = dataclasses.field(default_factory=list)

    def set_content(self, path: str, content: str) -> None:
        self.files[path] = content

    def delete(self, path: str) -> None:
        self.files[path] = None

    def warn(self, message: str) -> None:
        if message not in self.warnings:
            self.warnings.append(message)


def enforce_plan_scope(plan: "Plan", file_lists: dict[str, list[str]]) -> None:
    """Every touched path must be an explicit target and carry a precondition.

    This is what stops a plan with empty ``targets`` / ``preconditions`` from
    silently creating or deleting files: the runner refuses to write a path the
    plan did not name and pin.
    """

    allowed = {target["path"] for target in plan.targets}
    preconditions = {pre.path: pre for pre in plan.preconditions}
    problems: list[str] = []

    def require_target(path: str, verb: str) -> None:
        if path not in allowed:
            problems.append(f"{path}: {verb} path is not listed in plan.targets")

    for path in file_lists["createdFiles"]:
        require_target(path, "created")
        pre = preconditions.get(path)
        if pre is None or not pre.absent:
            problems.append(f"{path}: a created path needs an 'absent: true' precondition")
    for verb, key in (("modified", "modifiedFiles"), ("deleted", "deletedFiles")):
        for path in file_lists[key]:
            require_target(path, verb)
            pre = preconditions.get(path)
            if pre is None or pre.sha256 is None:
                problems.append(f"{path}: a {verb} path needs a sha256 precondition")

    if problems:
        raise MigrationError(EXIT_BAD_INPUT, "plan scope is incomplete", problems)


def classify_changes(repo_root: Path, changes: Changes) -> dict[str, list[str]]:
    created: list[str] = []
    modified: list[str] = []
    deleted: list[str] = []
    unchanged: list[str] = []
    for path in sorted(changes.files):
        target = resolve_within(repo_root, path, what="target path")
        new_content = changes.files[path]
        if new_content is None:
            if target.exists():
                deleted.append(path)
            else:
                unchanged.append(path)
            continue
        new_bytes = new_content.encode("utf-8")
        if target.is_file():
            if target.read_bytes() == new_bytes:
                unchanged.append(path)
            else:
                modified.append(path)
        else:
            created.append(path)
    return {
        "createdFiles": created,
        "modifiedFiles": modified,
        "deletedFiles": deleted,
        "unchangedFiles": unchanged,
    }


# --------------------------------------------------------------------------- #
# Validation result
# --------------------------------------------------------------------------- #


@dataclasses.dataclass
class ValidationResult:
    name: str
    status: str  # "passed" | "failed"
    details: str = ""

    def as_dict(self) -> dict[str, str]:
        return {"name": self.name, "status": self.status, "details": self.details}

    @property
    def passed(self) -> bool:
        return self.status == "passed"


# --------------------------------------------------------------------------- #
# Engine protocol
# --------------------------------------------------------------------------- #


class Engine(Protocol):
    migration_kind: str
    # Optional: extra runtime modules the engine needs (checked before any work),
    # and the allow-lists for plan.inputs / plan.decisions keys.
    required_modules: tuple[str, ...]
    input_keys: set[str]
    decision_keys: set[str]

    def affected_roots(self, repo_root: Path, plan: Plan) -> list[str]:
        """Repository-relative directories to materialize for validation."""

    def build_changes(self, repo_root: Path, plan: Plan) -> Changes:
        """Compute every candidate file change from the validated plan."""

    def validate_tree(
        self,
        tree_root: Path,
        repo_root: Path,
        plan: Plan,
        changes: Changes,
    ) -> list[ValidationResult]:
        """Validate the materialized tree; return one entry per check."""


# --------------------------------------------------------------------------- #
# Write transaction
# --------------------------------------------------------------------------- #


def normalize_roots(roots: list[str]) -> list[str]:
    """Clean, de-duplicated, overlap-free repository-relative roots.

    ``"."`` / ``""`` / ``"/"`` collapse to the repository root (``""``), which, if
    present, subsumes every other root. A trailing slash is an alias. A root
    nested inside another is dropped so a single ``copytree`` covers it.
    """

    normalized: set[tuple[str, ...]] = set()
    for root in roots:
        parts = tuple(
            part
            for part in PurePosixPath(str(root).replace("\\", "/")).parts
            if part not in ("", ".", "/")
        )
        if ".." in parts:
            raise bad_input(f"affected root must not contain '..': {root!r}")
        normalized.add(parts)
    if () in normalized:
        return [""]
    kept: list[str] = []
    for parts in sorted(normalized):
        if any(other != parts and parts[: len(other)] == other for other in normalized):
            continue  # covered by an ancestor root already in the set
        kept.append("/".join(parts))
    return kept


def _materialize_tree(repo_root: Path, roots: list[str], changes: Changes, dest: Path) -> None:
    materialized = normalize_roots(roots)
    for root in materialized:
        source = repo_root if root == "" else resolve_within(repo_root, root, what="affected root")
        if not source.exists():
            continue
        target = dest if root == "" else dest / root
        if source.is_dir():
            target.mkdir(parents=True, exist_ok=True)
            shutil.copytree(source, target, symlinks=False, dirs_exist_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)

    for path, content in changes.files.items():
        pure = PurePosixPath(path)
        covered = any(
            root == "" or PurePosixPath(root) == pure or PurePosixPath(root) in pure.parents
            for root in materialized
        )
        if not covered:
            raise unsupported(
                f"target {path!r} is outside every affected root declared by the plan"
            )
        file_path = dest / path
        if content is None:
            if file_path.exists():
                file_path.unlink()
            continue
        file_path.parent.mkdir(parents=True, exist_ok=True)
        # newline="" disables platform newline translation: candidate content is
        # LF, and the on-disk bytes must stay LF so a repeat run classifies the
        # same content as unchanged on Windows too.
        file_path.write_text(content, encoding="utf-8", newline="")


def _rollback_commit(
    repo_root: Path, applied: list[str], backups: dict[str, bytes | None]
) -> None:
    for path in reversed(applied):
        target = resolve_within(repo_root, path, what="target path")
        original = backups.get(path)
        if original is None:
            if target.is_file():
                target.unlink()
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(original)


def _commit(repo_root: Path, changes: Changes) -> tuple[list[str], dict[str, bytes | None]]:
    """Atomically apply ``changes`` to the working tree, rolling back on error.

    Returns ``(applied, backups)`` so a later failure (report publication) can
    undo the commit.
    """

    backups: dict[str, bytes | None] = {}
    applied: list[str] = []
    try:
        for path in sorted(changes.files):
            target = resolve_within(repo_root, path, what="target path")
            content = changes.files[path]
            backups[path] = target.read_bytes() if target.is_file() else None
            if content is None:
                if target.is_file():
                    target.unlink()
                applied.append(path)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            tmp = target.with_name(f".{target.name}.migration.tmp")
            # newline="" keeps the bytes exactly as built (LF), so the committed
            # file matches the hash the temporary tree validated.
            tmp.write_text(content, encoding="utf-8", newline="")
            os.replace(tmp, target)
            applied.append(path)
    except Exception as exc:  # noqa: BLE001 - rollback then re-raise as a typed error
        _rollback_commit(repo_root, applied, backups)
        raise MigrationError(
            EXIT_TRANSACTION,
            "write transaction failed and was rolled back",
            [str(exc)],
        ) from exc
    return applied, backups


# --------------------------------------------------------------------------- #
# Result envelope
# --------------------------------------------------------------------------- #


def build_result(
    migration_kind: str,
    status: str,
    file_lists: dict[str, list[str]],
    warnings: list[str],
    validation: list[ValidationResult],
) -> dict[str, Any]:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "migrationKind": migration_kind,
        "status": status,
        "createdFiles": sorted(file_lists.get("createdFiles", [])),
        "modifiedFiles": sorted(file_lists.get("modifiedFiles", [])),
        "deletedFiles": sorted(file_lists.get("deletedFiles", [])),
        "unchangedFiles": sorted(file_lists.get("unchangedFiles", [])),
        "warnings": sorted(warnings),
        "validation": [entry.as_dict() for entry in validation],
    }


def blocked_result(migration_kind: str, entries: list[str], detail: str) -> dict[str, Any]:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "migrationKind": migration_kind,
        "status": "blocked",
        "createdFiles": [],
        "modifiedFiles": [],
        "deletedFiles": [],
        "unchangedFiles": [],
        "warnings": [],
        "validation": [ValidationResult("plan", "failed", detail).as_dict()],
        "blocking": sorted(entries),
    }


def _report_bytes(result: dict[str, Any]) -> bytes:
    """Deterministic report bytes: UTF-8, LF newlines, on every platform."""

    return (json.dumps(result, indent=2, sort_keys=False) + "\n").encode("utf-8")


def _resolve_report_path(
    report_path: Path | None, repo_root: Path, plan_path: Path
) -> Path | None:
    """Reject a report path that would write into the consumer repository or the
    plan file. The report is execution output, not a repository artifact."""

    if report_path is None:
        return None
    resolved = report_path.expanduser().resolve()
    root = repo_root.resolve()
    if resolved == root or root in resolved.parents:
        raise bad_input(
            f"--report must be outside --repo-root; it is execution output, not a "
            f"consumer-repository artifact: {report_path}"
        )
    if resolved == plan_path.expanduser().resolve():
        raise bad_input("--report must not be the plan file")
    return resolved


def _write_report(report_path: Path | None, result: dict[str, Any]) -> None:
    payload = _report_bytes(result)
    if report_path is None:
        sys.stdout.write(payload.decode("utf-8"))
        return
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_bytes(payload)


def _stage_report(report_path: Path | None, payload: bytes) -> Path | None:
    """Write the report bytes to a sibling ``.partial`` file before committing the
    repository, so a later publication failure cannot lose the result."""

    if report_path is None:
        return None
    try:
        report_path.parent.mkdir(parents=True, exist_ok=True)
        staged = report_path.with_name(report_path.name + ".partial")
        staged.write_bytes(payload)
    except OSError as exc:
        raise MigrationError(
            EXIT_TRANSACTION, "cannot stage the result report", [str(exc)]
        ) from exc
    return staged


def _publish_report(report_path: Path | None, staged: Path | None, payload: bytes) -> None:
    if report_path is None:
        sys.stdout.write(payload.decode("utf-8"))
        return
    os.replace(staged, report_path)


def _emit_fallback(report_path: Path | None, result: dict[str, Any]) -> None:
    """Last-resort result delivery that never repeats a report-path write failure."""

    payload = _report_bytes(result)
    if report_path is not None:
        try:
            report_path.parent.mkdir(parents=True, exist_ok=True)
            report_path.write_bytes(payload)
            return
        except OSError:
            pass
    sys.stdout.write(payload.decode("utf-8"))


# --------------------------------------------------------------------------- #
# CLI orchestration
# --------------------------------------------------------------------------- #


def _parse_args(argv: list[str], migration_kind: str) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="apply_migration.py",
        description=f"Deterministic {migration_kind} migration runner.",
    )
    parser.add_argument("--repo-root", required=True, type=Path, help="Consumer repository root")
    parser.add_argument("--plan", required=True, type=Path, help="Path to the migration plan JSON")
    parser.add_argument("--report", type=Path, help="Where to write the JSON result (default: stdout)")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument(
        "--check",
        dest="mode",
        action="store_const",
        const="check",
        help="Compute and validate every change in a temporary tree; write nothing",
    )
    mode.add_argument(
        "--apply",
        dest="mode",
        action="store_const",
        const="apply",
        help="Validate then atomically write the approved changes",
    )
    try:
        return parser.parse_args(argv)
    except SystemExit as exc:  # argparse already printed the reason
        if exc.code in (0, None):
            raise  # --help / --version: let the clean exit through unchanged
        raise MigrationError(EXIT_BAD_INPUT, "invalid command line") from exc


def _check_dependencies(engine: Engine) -> None:
    """A missing runtime module is reported as a blocked result with a documented
    exit code, never as a bare import traceback and exit ``1``."""

    missing = [
        name
        for name in getattr(engine, "required_modules", ())
        if importlib.util.find_spec(name) is None
    ]
    if missing:
        raise MigrationError(
            EXIT_UNSUPPORTED,
            "a required Python module is not available in this environment",
            [f"{name}: install it (for example `pip install PyYAML`) and re-run" for name in missing],
        )


def run(engine: Engine, argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    report_path: Path | None = None
    try:
        args = _parse_args(argv, engine.migration_kind)
        _check_dependencies(engine)

        repo_root = args.repo_root
        if not repo_root.is_dir():
            raise bad_input(f"--repo-root is not a directory: {repo_root}")
        repo_root = repo_root.resolve()

        # Isolate the report before any repository work: --check must never write
        # into the repository, and no run may overwrite a source or the plan.
        report_path = _resolve_report_path(args.report, repo_root, args.plan)

        plan = load_plan(
            args.plan,
            engine.migration_kind,
            input_keys=getattr(engine, "input_keys", None),
            decision_keys=getattr(engine, "decision_keys", None),
        )
        check_preconditions(repo_root, plan)

        changes = engine.build_changes(repo_root, plan)
        roots = engine.affected_roots(repo_root, plan)

        file_lists = classify_changes(repo_root, changes)
        enforce_plan_scope(plan, file_lists)

        with tempfile.TemporaryDirectory(prefix="dbaas-migration-") as tmp:
            tree_root = Path(tmp)
            _materialize_tree(repo_root, roots, changes, tree_root)
            validation = engine.validate_tree(tree_root, repo_root, plan, changes)

        failed = [entry for entry in validation if not entry.passed]
        if failed:
            result = build_result(
                engine.migration_kind,
                "blocked",
                {key: [] for key in file_lists},
                changes.warnings,
                validation,
            )
            _write_report(report_path, result)
            return EXIT_VALIDATION

        touched = (
            file_lists["createdFiles"]
            or file_lists["modifiedFiles"]
            or file_lists["deletedFiles"]
        )
        status = "changed" if touched else "unchanged"
        result = build_result(
            engine.migration_kind, status, file_lists, changes.warnings, validation
        )

        if args.mode == "apply" and touched:
            # Re-check every source hash immediately before writing: the temporary
            # tree validated an earlier snapshot, and an edit in between must not
            # be silently overwritten.
            check_preconditions(repo_root, plan)
            # Commit and report publication are one recoverable operation: stage
            # the report first, commit, then publish atomically; if publication
            # fails after the commit, roll the repository back and report through
            # a channel that cannot repeat the same failure.
            payload = _report_bytes(result)
            staged = _stage_report(report_path, payload)
            applied, backups = _commit(repo_root, changes)
            try:
                _publish_report(report_path, staged, payload)
            except OSError as exc:
                _rollback_commit(repo_root, applied, backups)
                # The commit was undone: emit a blocked transaction result, not
                # the success envelope that still lists files as modified.
                rolled_back = blocked_result(
                    engine.migration_kind,
                    [f"report publication failed: {exc}"],
                    "report publication failed after commit; repository was rolled back",
                )
                sys.stdout.write(_report_bytes(rolled_back).decode("utf-8"))
                sys.stderr.write(
                    f"error: report publication failed after commit; repository rolled back: {exc}\n"
                )
                return EXIT_TRANSACTION
        else:
            _write_report(report_path, result)
        return EXIT_OK

    except MigrationError as exc:
        detail = "; ".join([str(exc), *exc.entries]) if exc.entries else str(exc)
        sys.stderr.write(f"error: {detail}\n")
        _emit_fallback(report_path, blocked_result(engine.migration_kind, exc.entries, str(exc)))
        return exc.exit_code

    except Exception as exc:  # noqa: BLE001 - never crash without a machine-readable result
        message = f"internal error: {type(exc).__name__}: {exc}"
        sys.stderr.write(f"error: {message}\n")
        _emit_fallback(report_path, blocked_result(engine.migration_kind, [message], message))
        return EXIT_BAD_INPUT


def main_for(build_engine: Callable[[], Engine]) -> int:
    return run(build_engine())
