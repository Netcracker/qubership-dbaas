"""Unit coverage for the shared YAML document-separator predicate."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(
    0,
    str(
        Path(__file__).resolve().parents[1]
        / ".apm"
        / "skills"
        / "migrate-core-operator-dbaas-declarations"
        / "scripts"
    ),
)

from _helm_source import UnsupportedHelm, is_document_separator, parse_source  # noqa: E402

SEPARATORS = [
    "---",
    "---\n",
    "---\r\n",
    "--- ",
    "---\t",
    "---   \t ",
    "--- # a trailing comment",
    "---   #no space before hash still fine after ws",
    "---\t# tab then comment",
]

NOT_SEPARATORS = [
    "    ---",           # indented: literal text inside a block scalar
    "\t---",             # indented with a tab
    "----",              # four dashes
    "--- key: value",    # a document that starts inline
    "---x",              # not whitespace-delimited
    "- --- ",            # a list item, not a separator
    "#---",              # a comment
    "",                  # blank line
    "some: value",       # ordinary content
    "---#immediately",   # no whitespace before the hash
]


class DocumentSeparatorTest(unittest.TestCase):
    def test_recognized_separator_shapes(self) -> None:
        for line in SEPARATORS:
            with self.subTest(line=line):
                self.assertTrue(is_document_separator(line))

    def test_rejected_shapes(self) -> None:
        for line in NOT_SEPARATORS:
            with self.subTest(line=line):
                self.assertFalse(is_document_separator(line))


class ParsedDocumentTest(unittest.TestCase):
    def test_documents_are_split_and_bodies_parsed(self) -> None:
        source = (
            "# a leading comment\n"
            "kind: DatabaseDeclaration\n"
            "type: postgresql\n"
            "--- # a separator comment\n"
            "apiVersion: v1\n"
            "kind: ConfigMap\n"
            "data:\n"
            "  note: keep me\n"
        )
        docs = parse_source(source, filename="s.yaml")
        self.assertEqual(len(docs), 2)
        self.assertEqual(docs[0].body["kind"], "DatabaseDeclaration")
        self.assertEqual(docs[1].body["kind"], "ConfigMap")
        self.assertIsNone(docs[1].guard)

    def test_whole_document_guard_lines_belong_to_the_document(self) -> None:
        source = (
            "{{- if .Values.enabled }}\n"
            "kind: DatabaseDeclaration\n"
            "type: postgresql\n"
            "{{- end }}\n"
        )
        docs = parse_source(source, filename="s.yaml")
        self.assertEqual(len(docs), 1)
        self.assertEqual(docs[0].guard, "{{- if .Values.enabled }}")

    def test_indented_doc_marker_inside_a_block_scalar_is_not_a_separator(self) -> None:
        # A block scalar containing an indented "--- # marker" line: that line is
        # literal scalar text, not a document separator, so the file is still
        # exactly two documents, not three.
        source = (
            "kind: DatabaseDeclaration\n"
            "type: postgresql\n"
            "---\n"
            "apiVersion: v1\n"
            "kind: ConfigMap\n"
            "data:\n"
            "  script: |\n"
            "    keep-line\n"
            "    --- # embedded marker\n"
            "    # keep-comment\n"
        )
        docs = parse_source(source, filename="s.yaml")
        self.assertEqual(len(docs), 2)
        self.assertEqual(
            docs[1].body["data"]["script"],
            "keep-line\n--- # embedded marker\n# keep-comment\n",
        )

    def test_unparseable_section_is_a_blocked_result_not_a_traceback(self) -> None:
        # A ``---`` that carries inline content makes PyYAML raise; the parser
        # error is surfaced verbatim rather than misattributed.
        source = "kind: DatabaseDeclaration\n--- kind: ConfigMap\n"
        with self.assertRaises(UnsupportedHelm) as ctx:
            parse_source(source, filename="s.yaml")
        message = str(ctx.exception)
        self.assertIn("s.yaml:1: could not parse this section as YAML", message)
        self.assertIn("mapping values are not allowed here", message)

    def test_second_document_after_an_end_marker_is_rejected(self) -> None:
        # ``...`` ends a document mid-region without a whole-line ``---``, so the
        # region can no longer be mapped to one verbatim block. Either the region
        # splits into two bodies or PyYAML refuses it; both give a blocked result.
        source = "kind: DatabaseDeclaration\ntype: postgresql\n...\nkind: ConfigMap\n"
        with self.assertRaises(UnsupportedHelm) as ctx:
            parse_source(source, filename="s.yaml")
        message = str(ctx.exception)
        self.assertTrue(
            "more than one YAML document" in message
            or "could not parse this section as YAML" in message,
            message,
        )


if __name__ == "__main__":
    unittest.main()
