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


class ParsedDocumentTextTest(unittest.TestCase):
    def test_each_document_carries_its_exact_original_text(self) -> None:
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
        # Concatenating every document's text reproduces the file byte-for-byte.
        self.assertEqual("".join(doc.text for doc in docs), source)
        # The ConfigMap document keeps its separator-with-comment.
        self.assertIn("--- # a separator comment", docs[1].text)
        self.assertEqual(docs[1].body["kind"], "ConfigMap")

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
        self.assertEqual(docs[0].text, source)

    def test_file_preamble_is_recorded_on_the_first_document(self) -> None:
        source = (
            "# a file header\n"
            "\n"
            "# more header\n"
            "kind: DatabaseDeclaration\n"
            "type: postgresql\n"
            "---\n"
            "kind: ConfigMap\n"
        )
        docs = parse_source(source, filename="s.yaml")
        self.assertEqual(docs[0].leading, "# a file header\n\n# more header\n")
        self.assertEqual(docs[1].leading, "")
        # The preamble is still a prefix of the first document's own text.
        self.assertTrue(docs[0].text.startswith(docs[0].leading))

    def test_file_starting_with_a_separator_has_no_preamble(self) -> None:
        docs = parse_source("---\nkind: DatabaseDeclaration\n", filename="s.yaml")
        self.assertEqual(docs[0].leading, "")

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
