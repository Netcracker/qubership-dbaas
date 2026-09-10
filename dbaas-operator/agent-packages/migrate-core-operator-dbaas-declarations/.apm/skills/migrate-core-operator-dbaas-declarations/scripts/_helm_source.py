#!/usr/bin/env python3
"""Parse legacy source files that may contain Helm template syntax.

The runner never comments template actions out. It supports exactly the guard
patterns confirmed for the first release and blocks, with a source line, on
anything else:

- a scalar value that is entirely one ``{{ ... }}`` action, for example
  ``namespace: {{ .Values.NAMESPACE }}`` (quoted before YAML parsing, preserved
  verbatim on output);
- a whole-document ``{{- if <pipeline> }} ... {{- end }}`` wrapper around a
  single legacy resource (the guard is recorded and re-emitted around that
  resource in the generated file).

``range``, ``with``, ``include``, ``define``, ``else``, nested guards, and a
partially templated scalar are unsupported and raise :class:`UnsupportedHelm`.

This module parses documents; it does not preserve them. A source file whose
documents are only ever entirely migrated or entirely left alone is supported.
A file that mixes a migrated declaration with unrelated content in the same
``---``-delimited file is the caller's problem to reject, not this module's to
splice back together -- see ``apply_migration.py``'s source-rewrite logic.
"""

from __future__ import annotations

import dataclasses
import re
from typing import Any

try:  # common.run() checks this before any work and reports it as a blocked result.
    import yaml
except ImportError:  # pragma: no cover - exercised only without the pinned dependency
    yaml = None  # type: ignore[assignment]


class UnsupportedHelm(Exception):
    """A Helm construct outside the confirmed first-release support set."""

    def __init__(self, entries: list[str]) -> None:
        super().__init__("; ".join(entries))
        self.entries = entries


_ACTION_LINE = re.compile(r"^\s*\{\{[-\s]*(?P<body>.*?)[-\s]*\}\}\s*$")
_SCALAR_TEMPLATE = re.compile(r"^(?P<prefix>\s*[A-Za-z0-9_.\"'-]+:\s*)(?P<value>\{\{.*\}\})\s*$")


# A YAML document separator: ``---`` at column zero, then either nothing but
# trailing spaces/tabs, or whitespace and a comment. `\r?\n` is stripped before
# matching, so both newline styles work. An indented ``---`` (literal text in a
# block scalar) and a ``---`` carrying inline document content do not match.
_DOCUMENT_SEPARATOR = re.compile(r"^---([ \t]+#.*|[ \t]*)$")


def is_document_separator(line: str) -> bool:
    """Whether ``line`` is a whole-line YAML document separator.

    Both this module and the runner's mixed-file rewrite use this one predicate,
    so they always agree on where a document begins.
    """

    return _DOCUMENT_SEPARATOR.match(line.rstrip("\r\n")) is not None


@dataclasses.dataclass
class ParsedDocument:
    body: Any
    guard: str | None  # a full ``{{- if ... }}`` line, or None
    source_line: int


def parse_source(text: str, *, filename: str) -> list[ParsedDocument]:
    """Split ``text`` into documents, resolving the supported guard patterns.

    Each :class:`ParsedDocument` carries the parsed ``body``, from a sanitized
    copy that quotes templated scalars so PyYAML can parse them.
    """

    entries: list[str] = []
    sanitized_lines: list[str] = []
    guard_stack: list[str] = []
    # Map each sanitized line index back to the guard active when it was emitted.
    line_guard: list[str | None] = []
    # A supported guard wraps exactly one whole document. Track document content
    # so a guard that opens or closes mid-document, or spans a `---`, is rejected
    # instead of silently dropping the condition.
    doc_has_content = False
    guard_closed_in_doc = False

    for number, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        separator = is_document_separator(line)
        is_content = bool(stripped) and not separator and not stripped.startswith("#")

        if separator:
            if guard_stack:
                entries.append(
                    f"{filename}:{number}: a Helm guard must not span a document boundary"
                )
            doc_has_content = False
            guard_closed_in_doc = False
            sanitized_lines.append(line)
            line_guard.append(guard_stack[-1] if guard_stack else None)
            continue

        action = _ACTION_LINE.match(line)
        if action:
            body = action.group("body").strip()
            lowered = body.lower()
            if lowered.startswith("if "):
                if doc_has_content or guard_closed_in_doc:
                    entries.append(
                        f"{filename}:{number}: a Helm guard must enclose the whole document, "
                        "not part of one"
                    )
                guard_stack.append(line.strip())
                continue
            if lowered == "end":
                if not guard_stack:
                    entries.append(f"{filename}:{number}: unmatched {{{{ end }}}}")
                else:
                    guard_stack.pop()
                    if not guard_stack:
                        guard_closed_in_doc = True
                continue
            if lowered == "else" or lowered.startswith("else "):
                entries.append(
                    f"{filename}:{number}: {{{{ else }}}} branches are not supported in the first release"
                )
                continue
            entries.append(
                f"{filename}:{number}: unsupported standalone template action {{{{ {body} }}}}"
            )
            continue

        if len(guard_stack) > 1:
            entries.append(f"{filename}:{number}: nested Helm guards are not supported")

        if is_content and guard_closed_in_doc and not guard_stack:
            entries.append(
                f"{filename}:{number}: content after {{{{ end }}}} must start a new document; "
                "a Helm guard must enclose the whole document"
            )

        scalar = _SCALAR_TEMPLATE.match(line)
        if scalar:
            value = scalar.group("value").strip()
            quoted = "'" + value.replace("'", "''") + "'"
            sanitized_lines.append(f"{scalar.group('prefix')}{quoted}")
            line_guard.append(guard_stack[-1] if guard_stack else None)
            if is_content:
                doc_has_content = True
            continue

        if "{{" in line and "}}" in line and not stripped.startswith("#"):
            entries.append(
                f"{filename}:{number}: partially templated scalar is not supported: {stripped!r}"
            )

        sanitized_lines.append(line)
        line_guard.append(guard_stack[-1] if guard_stack else None)
        if is_content:
            doc_has_content = True

    if guard_stack:
        entries.append(f"{filename}: unterminated Helm guard {guard_stack[-1]!r}")

    if entries:
        raise UnsupportedHelm(entries)

    regions = _regions(list(zip(sanitized_lines, line_guard)), key=lambda pair: pair[0])
    documents: list[ParsedDocument] = []
    line_no = 1
    for region in regions:
        content_lines = [sl for sl, _ in region if _is_yaml_content(sl)]
        if not content_lines:
            line_no += len(region)
            continue

        san_text = "\n".join(sl for sl, _ in region) + "\n"
        try:
            bodies = [body for body in yaml.safe_load_all(san_text) if body is not None]
        except yaml.YAMLError as exc:
            # Surface a parse failure as a blocked result rather than an uncaught
            # traceback. A common cause is a `---` separator that carries inline
            # document content, but the message reports the parser error itself
            # so ordinary malformed YAML is not misattributed.
            detail = " ".join(str(exc).split())
            raise UnsupportedHelm(
                [f"{filename}:{line_no}: could not parse this section as YAML: {detail}"]
            ) from None
        if not bodies:
            line_no += len(region)
            continue
        if len(bodies) > 1:
            # PyYAML found more than one document between two whole-line ``---``
            # separators -- an inline ``--- key: value`` or a ``...`` end marker.
            raise UnsupportedHelm(
                [
                    f"{filename}:{line_no}: this section holds more than one YAML document; "
                    "each document needs its own whole-line `---` separator"
                ]
            )

        # The guard of a document is the guard of its first real YAML line, so a
        # comment or blank line before a whole-document {{- if }} does not make it
        # look unguarded.
        guard = next((g for sl, g in region if _is_yaml_content(sl)), None)
        documents.append(ParsedDocument(body=bodies[0], guard=guard, source_line=line_no))
        line_no += len(region)

    return documents


def _is_yaml_content(line: str) -> bool:
    stripped = line.strip()
    return bool(stripped) and not is_document_separator(line) and not stripped.startswith("#")


def _regions(lines: list[Any], *, key=lambda line: line) -> list[list[Any]]:
    """Split ``lines`` at column-zero document separators into per-document
    groups. Every region after the first begins with its own ``---`` line."""

    regions: list[list[Any]] = [[]]
    for line in lines:
        if is_document_separator(key(line)):
            regions.append([line])
        else:
            regions[-1].append(line)
    return regions
