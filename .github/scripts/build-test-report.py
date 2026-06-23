#!/usr/bin/env python3
"""Build a markdown summary of Surefire reports for posting to a GitHub Issue."""
import html
import os
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

ARTIFACTS_DIR = Path(os.environ.get("ARTIFACTS_DIR", "artifacts"))
OUTPUT_FILE = Path(os.environ.get("OUTPUT_FILE", "report.md"))
RUN_URL = os.environ.get("RUN_URL", "")
ARTIFACT_URL = os.environ.get("ARTIFACT_URL", "")
BRANCH = os.environ.get("BRANCH", "")
COMMIT_SHA = os.environ.get("COMMIT_SHA", "")[:7]
RUN_NUMBER = os.environ.get("RUN_NUMBER", "")
MENTIONS = os.environ.get("MENTIONS", "").strip()

STACK_TRACE_LINE_LIMIT = 60
COMMENT_BYTE_BUDGET = 60000  # GitHub hard limit is 65536; leave headroom


@dataclass
class ModuleStats:
    name: str
    tests: int = 0
    failures: int = 0
    errors: int = 0
    skipped: int = 0
    time: float = 0.0
    failed_cases: list = field(default_factory=list)


def fmt_time(seconds: float) -> str:
    s = int(round(seconds))
    m, s = divmod(s, 60)
    if m == 0:
        return f"{s}s"
    h, m = divmod(m, 60)
    if h == 0:
        return f"{m}m {s:02d}s"
    return f"{h}h {m:02d}m {s:02d}s"


def parse_module(module_dir: Path) -> ModuleStats:
    stats = ModuleStats(name=module_dir.name)
    reports_dir = module_dir / "surefire-reports"
    if not reports_dir.is_dir():
        return stats
    for xml_path in sorted(reports_dir.glob("TEST-*.xml")):
        try:
            root = ET.parse(xml_path).getroot()
        except ET.ParseError:
            continue
        stats.tests += int(root.get("tests", "0"))
        stats.failures += int(root.get("failures", "0"))
        stats.errors += int(root.get("errors", "0"))
        stats.skipped += int(root.get("skipped", "0"))
        try:
            stats.time += float(root.get("time", "0"))
        except ValueError:
            pass
        for tc in root.findall("testcase"):
            for kind in ("failure", "error"):
                elem = tc.find(kind)
                if elem is None:
                    continue
                stats.failed_cases.append(
                    {
                        "classname": tc.get("classname", ""),
                        "name": tc.get("name", ""),
                        "kind": kind,
                        "message": (elem.get("message") or "").strip(),
                        "trace": (elem.text or "").strip(),
                    }
                )
                break
    return stats


def collect_all() -> list[ModuleStats]:
    if not ARTIFACTS_DIR.is_dir():
        return []
    return [
        parse_module(child)
        for child in sorted(ARTIFACTS_DIR.iterdir())
        if child.is_dir() and (child / "surefire-reports").is_dir()
    ]


def truncate_trace(text: str) -> str:
    lines = text.splitlines()
    if len(lines) <= STACK_TRACE_LINE_LIMIT:
        return text
    omitted = len(lines) - STACK_TRACE_LINE_LIMIT
    return "\n".join(lines[:STACK_TRACE_LINE_LIMIT]) + f"\n... ({omitted} more lines — see Surefire artifact)"


def build_title(status: str, bad: int) -> str:
    if status == "failed":
        word = "test" if bad == 1 else "tests"
        return f"❌ Integration tests #{RUN_NUMBER} FAILED ({bad} {word}) — {BRANCH}"
    if status == "success":
        return f"✅ Integration tests #{RUN_NUMBER} PASSED — {BRANCH}"
    return f"⚠️ Integration tests #{RUN_NUMBER} DID NOT RUN — {BRANCH}"


def render(modules: list[ModuleStats]) -> tuple[str, str, str, int]:
    total_tests = sum(m.tests for m in modules)
    total_failed = sum(m.failures for m in modules)
    total_errors = sum(m.errors for m in modules)
    total_skipped = sum(m.skipped for m in modules)
    total_time = sum(m.time for m in modules)
    bad = total_failed + total_errors

    if not modules:
        status = "no-tests"
    elif bad > 0:
        status = "failed"
    else:
        status = "success"
    title = build_title(status, bad)

    lines = []
    meta = f"**Branch:** `{BRANCH}` · **Commit:** `{COMMIT_SHA}`"

    if status == "no-tests":
        lines += [
            f"### ⚠️ Integration tests #{RUN_NUMBER} — DID NOT RUN",
            meta,
            "",
            "No Surefire reports found — a step before `mvn test` likely failed.",
        ]
        if MENTIONS:
            lines += ["", f"cc {MENTIONS}"]
        if RUN_URL:
            lines += ["", f"[Workflow run]({RUN_URL})"]
        return "\n".join(lines), status, title, 0

    if status == "failed":
        word = "test" if bad == 1 else "tests"
        lines.append(f"### ❌ Integration tests #{RUN_NUMBER} — FAILED ({bad} {word})")
    else:
        lines.append(f"### ✅ Integration tests #{RUN_NUMBER} — PASSED")

    lines.append(f"{meta} · **Test time:** {fmt_time(total_time)}")
    if MENTIONS:
        lines += ["", f"cc {MENTIONS}"]

    lines += [
        "",
        "| Module | Tests | Failed | Errors | Skipped | Time |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for m in modules:
        lines.append(
            f"| {m.name} | {m.tests} | {m.failures} | {m.errors} | {m.skipped} | {fmt_time(m.time)} |"
        )
    lines.append(
        f"| **Total** | **{total_tests}** | **{total_failed}** | **{total_errors}** | **{total_skipped}** | **{fmt_time(total_time)}** |"
    )

    if status == "failed":
        lines.append("")
        for m in modules:
            for case in m.failed_cases:
                msg = case["message"]
                short = (msg[:200] + "…") if len(msg) > 200 else msg
                summary = html.escape(f"{m.name} → {case['classname']}.{case['name']}")
                short_esc = html.escape(short)
                lines += [
                    "<details>",
                    f"<summary><code>{summary}</code> — {case['kind']}: {short_esc}</summary>",
                    "",
                    "```",
                    truncate_trace(case["trace"]),
                    "```",
                    "</details>",
                    "",
                ]

    links = []
    if RUN_URL:
        links.append(f"[Workflow run]({RUN_URL})")
    if ARTIFACT_URL:
        links.append(f"[Surefire artifact]({ARTIFACT_URL})")
    if links:
        if lines and lines[-1] != "":
            lines.append("")
        lines.append(" · ".join(links))

    body = "\n".join(lines)
    if len(body.encode("utf-8")) > COMMENT_BYTE_BUDGET:
        body = body.encode("utf-8")[:COMMENT_BYTE_BUDGET].decode("utf-8", errors="ignore")
        body += "\n\n*…(truncated, see Surefire artifact)*"
    return body, status, title, bad


def main():
    modules = collect_all()
    body, status, title, bad = render(modules)
    OUTPUT_FILE.write_text(body, encoding="utf-8")
    output_path = os.environ.get("GITHUB_OUTPUT")
    if output_path:
        with open(output_path, "a", encoding="utf-8") as f:
            f.write(f"status={status}\n")
            f.write(f"failed_count={bad}\n")
            f.write(f"title={title}\n")
    print(f"Built report: status={status}, failed={bad}, title={title!r}, bytes={len(body.encode())}")


if __name__ == "__main__":
    main()
