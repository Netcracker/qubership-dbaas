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
    # The exact original source of this document -- its leading ``---`` separator
    # (if any), any whole-document Helm guard lines, comments and blank lines
    # included. A retained document is written back from this verbatim; nothing is
    # re-serialized. ``region_index`` groups documents that share one ``---``
    # region (the rare inline ``--- key: value`` shape), which cannot be excised
    # by text.
    text: str = ""
    region_index: int = 0
    # Only set on the first document: the file's leading comment / blank-line
    # preamble. It is also a prefix of ``text``; the runner prepends it to the
    # output when the first document is removed but a later one is kept, so a
    # file-level header is not lost with the declaration it happened to precede.
    leading: str = ""


def parse_source(text: str, *, filename: str) -> list[ParsedDocument]:
    """Split ``text`` into documents, resolving the supported guard patterns.

    Each :class:`ParsedDocument` carries both the parsed ``body`` (from a
    sanitized copy that quotes templated scalars) and the ``text`` -- the exact
    original bytes of that document -- so the runner never needs a second
    splitting pass to keep an unmigrated document byte-for-byte.
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

    # Separators appear identically in the raw text and in ``sanitized_lines``
    # (guard lines are the only thing dropped, and a guard never spans a `---`),
    # so splitting both on the shared separator predicate keeps region *i* of one
    # aligned with region *i* of the other.
    raw_regions = _regions(text.splitlines(keepends=True))
    san_regions = _regions(list(zip(sanitized_lines, line_guard)), key=lambda pair: pair[0])
    if len(raw_regions) != len(san_regions):  # pragma: no cover - defensive invariant
        raise UnsupportedHelm(
            [f"{filename}: could not align document boundaries; normalize the file's `---` separators"]
        )

    documents: list[ParsedDocument] = []
    pending_prefix = ""  # comment-only / empty regions, folded onto the next document
    line_no = 1
    for region_index, (raw_region, san_region) in enumerate(zip(raw_regions, san_regions)):
        region_text = "".join(raw_region)
        content_lines = [sl for sl, _ in san_region if _is_yaml_content(sl)]
        if not content_lines:
            pending_prefix += region_text
            line_no += len(raw_region)
            continue

        san_text = "\n".join(sl for sl, _ in san_region) + "\n"
        bodies = [body for body in yaml.safe_load_all(san_text) if body is not None]
        if not bodies:
            pending_prefix += region_text
            line_no += len(raw_region)
            continue

        # The guard of a document is the guard of its first real YAML line, so a
        # comment or blank line before a whole-document {{- if }} does not make it
        # look unguarded.
        guard = next((g for sl, g in san_region if _is_yaml_content(sl)), None)
        combined = pending_prefix + region_text
        pending_prefix = ""
        source_line = line_no
        for body in bodies:
            documents.append(
                ParsedDocument(
                    body=body,
                    guard=guard,
                    source_line=source_line,
                    text=combined,
                    region_index=region_index,
                )
            )
            combined = ""  # a multi-body region's text belongs to its first entry
        line_no += len(raw_region)

    if pending_prefix and documents:
        documents[-1].text += pending_prefix
    if documents:
        documents[0].leading = _leading_preamble(documents[0].text)
    return documents


def _leading_preamble(text: str) -> str:
    """The leading run of blank and comment lines at the start of ``text`` -- the
    file's preamble, up to its first real content or ``---`` separator."""

    cut = 0
    for line in text.splitlines(keepends=True):
        stripped = line.strip()
        if stripped and not stripped.startswith("#"):
            break
        cut += len(line)
    return text[:cut]


def _is_yaml_content(line: str) -> bool:
    stripped = line.strip()
    return bool(stripped) and not is_document_separator(line) and not stripped.startswith("#")


def _regions(lines: list[Any], *, key=lambda line: line) -> list[list[Any]]:
    """Split ``lines`` at column-zero document separators. Every region after the
    first begins with its own ``---`` line, so concatenating a subset of regions
    reproduces a valid multi-document file with its separators intact."""

    regions: list[list[Any]] = [[]]
    for line in lines:
        if is_document_separator(key(line)):
            regions.append([line])
        else:
            regions[-1].append(line)
    return regions
