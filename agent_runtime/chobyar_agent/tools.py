from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

from agents import function_tool

ROOT = Path(os.getenv("CHOBYAR_REPO_ROOT", Path(__file__).resolve().parents[2])).resolve()

_BLOCKED_NAMES = {
    "credentials.json",
    "secrets.json",
}
_BLOCKED_RELATIVE = {
    "AGENTS.md",
    "docs/SHAPR3D_PROFESSOR_AGENT.md",
    "docs/CAD_KERNEL_REWRITE_V2.md",
}
_BLOCKED_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx", ".key", ".pem"}
_BLOCKED_PARTS = {".git", ".agent-state"}
_WRITE_EXTENSIONS = {".java", ".kt", ".cpp", ".cc", ".c", ".h", ".hpp", ".xml", ".json", ".md", ".txt", ".gradle", ".pro"}
_WRITE_FILENAMES = {"CMakeLists.txt", "settings.gradle", "gradle.properties"}

_VALIDATIONS: dict[str, list[str]] = {
    "status": ["git", "status", "--short"],
    "unit": ["./gradlew", "testDebugUnitTest", "--no-daemon"],
    "debug_build": ["./gradlew", "assembleDebug", "--no-daemon"],
    "rc_build": ["./gradlew", "assembleReleaseCandidate", "--no-daemon"],
}


def _resolve_repo_path(path: str) -> Path:
    if not path or path.startswith("~"):
        raise ValueError("A repository-relative path is required")
    candidate = (ROOT / path).resolve()
    try:
        candidate.relative_to(ROOT)
    except ValueError as exc:
        raise ValueError("Path escapes the repository root") from exc
    rel = candidate.relative_to(ROOT)
    rel_text = rel.as_posix()
    if any(part in _BLOCKED_PARTS for part in rel.parts):
        raise ValueError("Path is protected")
    if rel_text in _BLOCKED_RELATIVE:
        raise ValueError("Policy/architecture file is protected")
    if candidate.name.startswith(".env") or candidate.name in _BLOCKED_NAMES:
        raise ValueError("Secret-bearing file is protected")
    if candidate.suffix.lower() in _BLOCKED_SUFFIXES:
        raise ValueError("Key/signing file is protected")
    return candidate


def _write_allowed(path: Path) -> bool:
    rel = path.relative_to(ROOT)
    if rel.parts[:2] == (".github", "workflows"):
        return False
    if rel.as_posix() == "app/build.gradle" and os.getenv("CHOBYAR_AGENT_ALLOW_BUILD_CONFIG") != "1":
        return False
    return path.suffix.lower() in _WRITE_EXTENSIONS or path.name in _WRITE_FILENAMES


def _run(args: list[str], timeout: int = 120) -> str:
    proc = subprocess.run(
        args,
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=timeout,
        check=False,
    )
    output = proc.stdout[-20000:]
    return f"exit={proc.returncode}\n{output}"


@function_tool
def repo_overview() -> str:
    """Return branch/status plus the main CAD architecture reference files present in the checkout."""
    refs = [
        "AGENTS.md",
        "docs/CAD_KERNEL_REWRITE_V2.md",
        "docs/manual26100-progress.md",
        "docs/REFERENCE_WORKFLOW_ACCEPTANCE.md",
        "docs/PERFORMANCE_REAL_DEVICE_ACCEPTANCE.md",
        "docs/SHAPR3D_PROFESSOR_AGENT.md",
    ]
    present = [p for p in refs if (ROOT / p).exists()]
    return _run(["git", "status", "--short", "--branch"]) + "\nreferences:\n" + "\n".join(present)


@function_tool
def read_repo_file(path: str, start_line: int = 1, end_line: int = 400) -> str:
    """Read a UTF-8 repository file by relative path and line range. Secret/key and protected policy files are blocked."""
    target = _resolve_repo_path(path)
    if not target.is_file():
        return "not found"
    if target.stat().st_size > 2_000_000:
        return "file too large; narrow the investigation"
    text = target.read_text(encoding="utf-8", errors="replace").splitlines()
    start = max(1, start_line)
    end = min(len(text), max(start, end_line))
    rows = [f"{idx}: {text[idx - 1]}" for idx in range(start, end + 1)]
    return "\n".join(rows)[:20000]


@function_tool
def search_repo(query: str, path: str = "app/src") -> str:
    """Search source/docs for a literal or regex pattern. Results are capped and sensitive paths are excluded."""
    base = _resolve_repo_path(path)
    if not base.exists():
        return "search path not found"
    if shutil.which("rg"):
        args = ["rg", "-n", "--hidden", "--glob", "!.git/**", "--glob", "!.agent-state/**", "--glob", "!.env*", "--glob", "!*.jks", query, str(base)]
        return _run(args)[:20000]
    matches: list[str] = []
    for item in base.rglob("*"):
        if not item.is_file() or item.suffix.lower() in _BLOCKED_SUFFIXES or item.name.startswith(".env"):
            continue
        try:
            lines = item.read_text(encoding="utf-8", errors="ignore").splitlines()
        except OSError:
            continue
        for index, line in enumerate(lines, 1):
            if query.lower() in line.lower():
                matches.append(f"{item.relative_to(ROOT)}:{index}:{line}")
                if len(matches) >= 80:
                    return "\n".join(matches)
    return "\n".join(matches)


@function_tool
def write_repo_file(path: str, content: str) -> str:
    """Write one text source/test/doc file. Requires CHOBYAR_AGENT_ALLOW_WRITES=1; CI/workflow, secret, signing and policy files are protected."""
    if os.getenv("CHOBYAR_AGENT_ALLOW_WRITES") != "1":
        return "write blocked: run agent with --apply to enable repository edits"
    target = _resolve_repo_path(path)
    if not _write_allowed(target):
        return "write blocked for this path/type"
    if len(content.encode("utf-8")) > 1_000_000:
        return "write blocked: content exceeds 1 MB"
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and target.is_symlink():
        return "write blocked: symlink target"
    target.write_text(content, encoding="utf-8")
    return f"wrote {target.relative_to(ROOT)} ({len(content)} chars)"


@function_tool
def git_diff() -> str:
    """Return the current unstaged/staged source diff, capped for review."""
    return _run(["git", "diff", "--", ".", ":(exclude).agent-state/**"])[:30000]


@function_tool
def run_validation(name: str) -> str:
    """Run one allow-listed validation: status, unit, debug_build, or rc_build. Arbitrary shell commands are not permitted."""
    command = _VALIDATIONS.get(name)
    if command is None:
        return "unknown validation; choose status, unit, debug_build, or rc_build"
    timeout = 1200 if name in {"unit", "debug_build", "rc_build"} else 120
    return _run(command, timeout=timeout)
