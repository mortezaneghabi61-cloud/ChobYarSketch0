#!/usr/bin/env python3
"""Fail-closed audit for placeholder UI strings in production sources.

The current ChobYar UI contains migration artifacts where the literal token
"text" (including fused forms such as "Modeltext" and "textSelection") reached
user-visible labels, dialogs and status messages. This gate deliberately scans
only production sources/resources and reports every suspicious string literal.

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
FUSED_PREFIX = re.compile(r"\btext[A-Z][A-Za-z0-9_]*")
FUSED_SUFFIX = re.compile(r"[A-Za-z0-9_]+text\b", re.IGNORECASE)
STANDALONE = re.compile(r"\btext\b", re.IGNORECASE)


@dataclass(frozen=True)
class Violation:
    path: Path
    line: int
    literal: str


def suspicious_literal(literal: str) -> bool:
    value = literal[1:-1]
    if FUSED_PREFIX.search(value):
        return True
    if FUSED_SUFFIX.search(value):
        # Do not flag ordinary English words that merely end in "text".
        words = {w.lower() for w in re.findall(r"[A-Za-z0-9_]+", value)}
        if words.issubset({"context", "plaintext"}):
            return False
        return True
    return STANDALONE.search(value) is not None


def scan_file(path: Path) -> list[Violation]:
    try:
        source = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return []

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
    print("UI placeholder audit self-test passed.")


def main() -> int:
    if "--self-test" in sys.argv[1:]:
        self_test()
        return 0

    violations = discover()
    if not violations:
        print("UI placeholder gate passed: zero suspicious production string literals.")
        return 0

    print("UI placeholder gate failed: suspicious production string literals remain.\n")
    counts: dict[Path, int] = {}
    for item in violations:
        counts[item.path] = counts.get(item.path, 0) + 1
        print(f"{item.path}:{item.line}: {item.literal}")

    print("\nViolations by file:")
    for path, count in sorted(counts.items(), key=lambda x: (-x[1], str(x[0]))):
        print(f"{count:4d}  {path}")
    print(f"\nTotal suspicious string literals: {len(violations)}")
    print(f"Files affected: {len(counts)}")
    print("Fail-closed: replace the placeholder with intentional UI copy or add a reviewed ui-placeholder-ok suppression for a genuinely legitimate occurrence.")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
