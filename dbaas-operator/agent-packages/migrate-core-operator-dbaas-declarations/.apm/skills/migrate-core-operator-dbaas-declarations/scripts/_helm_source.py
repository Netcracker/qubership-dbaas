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
_UNSUPPORTED_KEYWORDS = ("range ", "with ", "include ", "define ", "template ", "block ")


@dataclasses.dataclass
class ParsedDocument:
    body: Any
    guard: str | None  # a full ``{{- if ... }}`` line, or None
    source_line: int


def parse_source(text: str, *, filename: str) -> list[ParsedDocument]:
    """Split ``text`` into documents, resolving the supported guard patterns."""

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
        is_content = bool(stripped) and stripped != "---" and not stripped.startswith("#")

        if stripped == "---":
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
            if any(keyword in lowered + " " for keyword in _UNSUPPORTED_KEYWORDS):
                entries.append(
                    f"{filename}:{number}: unsupported standalone template action {{{{ {body} }}}}"
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

    sanitized = "\n".join(sanitized_lines) + "\n"
    documents: list[ParsedDocument] = []
    # PyYAML does not report per-document guards, so re-split on '---' ourselves
    # to keep the guard alignment we tracked above.
    raw_docs = _split_documents(sanitized_lines, line_guard)
    for doc_text, guard, start_line in raw_docs:
        loaded = list(yaml.safe_load_all(doc_text))
        for body in loaded:
            if body is None:
                continue
            documents.append(ParsedDocument(body=body, guard=guard, source_line=start_line))
    return documents


def _is_yaml_content(line: str) -> bool:
    stripped = line.strip()
    return bool(stripped) and stripped != "---" and not stripped.startswith("#")


def _split_documents(
    lines: list[str], guards: list[str | None]
) -> list[tuple[str, str | None, int]]:
    docs: list[tuple[str, str | None, int]] = []
    current: list[tuple[str, str | None]] = []
    start_line = 1

    def flush() -> None:
        if not any(_is_yaml_content(item) for item, _ in current):
            return
        # The guard of a document is the guard of its first real YAML line, not
        # its first physical line: comments or blank lines before a whole-document
        # {{- if }} must not make the document look unguarded.
        guard = next(
            (g for item, g in current if _is_yaml_content(item)),
            None,
        )
        text = "\n".join(item for item, _ in current) + "\n"
        docs.append((text, guard, start_line))

    for index, line in enumerate(lines):
        if line.strip() == "---":
            flush()
            current = []
            start_line = index + 2
            continue
        if not current:
            start_line = index + 1
        current.append((line, guards[index] if index < len(guards) else None))
    flush()
    return docs
