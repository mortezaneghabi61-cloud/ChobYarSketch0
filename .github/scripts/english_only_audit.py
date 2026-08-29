#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOTS = [Path("app/src/main")]
EXTENSIONS = {".java", ".kt", ".xml", ".gradle", ".properties", ".json", ".txt"}
ARABIC = re.compile(r"[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF]")

violations = []
for root in ROOTS:
    if not root.exists():
        continue
    for path in root.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in EXTENSIONS:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for line_no, line in enumerate(text.splitlines(), 1):
            if ARABIC.search(line):
                violations.append((str(path), line_no, line.strip()))

if violations:
    print("English-only UI gate failed: Arabic/Persian script remains in production source.\n")
    for path, line_no, line in violations:
        print(f"{path}:{line_no}: {line}")
    print(f"\nTotal violations: {len(violations)}")
    sys.exit(1)

print("English-only UI gate passed: no Arabic/Persian script found in app/src/main.")
