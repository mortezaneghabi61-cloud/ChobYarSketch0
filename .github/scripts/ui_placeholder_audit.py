#!/usr/bin/env python3
"""Fail-closed audit for placeholder UI strings in production sources.

The current ChobYar UI contains migration artifacts where the literal token
"text" (including fused forms such as "Modeltext" and "textSelection") reached
user-visible labels, dialogs, resources and status messages. This gate scans
production Java/Kotlin string literals plus XML element text values.

A legitimate occurrence can be suppressed only on the same source line with
`ui-placeholder-ok`; suppressions stay review-visible and should be rare.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re
import sys

ROOTS = (
    Path("app/src/main/java"),
    Path("app/src/main/res"),
)
EXTENSIONS = {".java", ".kt", ".xml"}
ALLOW_MARKER = "ui-placeholder-ok"
STRING_LITERAL = re.compile(r'"(?:\\.|[^"\\])*"')
XML_TEXT = re.compile(r">([^<]+)<")
FUSED_PREFIX = re.compile(r"\btext[A-Z][A-Za-z0-9_]*")
FUSED_SUFFIX = re.compile(r"[A-Za-z0-9_]+text\b", re.IGNORECASE)
STANDALONE = re.compile(r"\btext\b", re.IGNORECASE)


@dataclass(frozen=True)
class Violation:
    path: Path
    line: int
    literal: str


def suspicious_value(value: str) -> bool:
    if FUSED_PREFIX.search(value):
        return True
    if FUSED_SUFFIX.search(value):
        # Do not flag ordinary English words that merely end in "text".
        words = {w.lower() for w in re.findall(r"[A-Za-z0-9_]+", value)}
        if words.issubset({"context", "plaintext"}):
            return False
        return True
    return STANDALONE.search(value) is not None


def suspicious_literal(literal: str) -> bool:
    return suspicious_value(literal[1:-1])


def scan_code_file(path: Path, source: str) -> list[Violation]:
    violations: list[Violation] = []
    for line_no, line in enumerate(source.splitlines(), 1):
        stripped = line.lstrip()
        if ALLOW_MARKER in line:
            continue
        if stripped.startswith("//") or stripped.startswith("*"):
            continue
        for match in STRING_LITERAL.finditer(line):
            literal = match.group(0)
            if suspicious_literal(literal):
                violations.append(Violation(path, line_no, literal))
    return violations


def scan_xml_file(path: Path, source: str) -> list[Violation]:
    violations: list[Violation] = []
    for line_no, line in enumerate(source.splitlines(), 1):
        if ALLOW_MARKER in line:
            continue
        # Attribute names/values can legitimately contain identifiers such as
        # android:text; only user-visible element text is in scope here.
        for match in XML_TEXT.finditer(line):
            value = match.group(1).strip()
            if value and suspicious_value(value):
                violations.append(Violation(path, line_no, repr(value)))
    return violations


def scan_file(path: Path) -> list[Violation]:
    try:
        source = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return []
    if path.suffix.lower() == ".xml":
        return scan_xml_file(path, source)
    return scan_code_file(path, source)


def discover() -> list[Violation]:
    violations: list[Violation] = []
    for root in ROOTS:
        if not root.exists():
            continue
        for path in sorted(root.rglob("*")):
            if path.is_file() and path.suffix.lower() in EXTENSIONS:
                violations.extend(scan_file(path))
    return violations


def self_test() -> None:
    must_fail = [
        '"text"',
        '"DXF text"',
        '"Modeltext"',
        '"textSelection"',
        '"text text text"',
    ]
    must_pass = [
        '"context"',
        '"plaintext"',
        '"Texture"',
        '"Selection"',
        '"Exact Dimension"',
    ]
    for literal in must_fail:
        assert suspicious_literal(literal), f"expected placeholder detection: {literal}"
    for literal in must_pass:
        assert not suspicious_literal(literal), f"unexpected placeholder detection: {literal}"

    xml_fail = '<string name="bad">DXF text</string>'
    xml_pass = '<string name="good">Export DXF</string>'
    assert scan_xml_file(Path("strings.xml"), xml_fail), "expected XML placeholder detection"
    assert not scan_xml_file(Path("strings.xml"), xml_pass), "unexpected XML placeholder detection"
    print("UI placeholder audit self-test passed for code literals and XML text values.")


def main() -> int:
    if "--self-test" in sys.argv[1:]:
        self_test()
        return 0

    violations = discover()
    if not violations:
        print("UI placeholder gate passed: zero suspicious production UI values.")
        return 0

    print("UI placeholder gate failed: suspicious production UI values remain.\n")
    counts: dict[Path, int] = {}
    for item in violations:
        counts[item.path] = counts.get(item.path, 0) + 1
        print(f"{item.path}:{item.line}: {item.literal}")

    print("\nViolations by file:")
    for path, count in sorted(counts.items(), key=lambda x: (-x[1], str(x[0]))):
        print(f"{count:4d}  {path}")
    print(f"\nTotal suspicious UI values: {len(violations)}")
    print(f"Files affected: {len(counts)}")
    print("Fail-closed: replace the placeholder with intentional UI copy or add a reviewed ui-placeholder-ok suppression for a genuinely legitimate occurrence.")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
